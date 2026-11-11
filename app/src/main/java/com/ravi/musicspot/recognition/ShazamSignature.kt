package com.ravi.musicspot.recognition

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin

/**
 * Pack 6 Part 2: audio fingerprint in the format Shazam's public recognition endpoint accepts (no account, no key).
 * Pure Kotlin, no Android. A port of the open-source SongRec fingerprinting (marin-m/SongRec, python-version
 * `fingerprinting/algorithm.py` + `signature_format.py`, GPL-3.0, the same licence family as the NewPipe code UpZe
 * already ships): 16 kHz mono PCM → 2048-point Hanning FFT every 128 samples → spread peaks → peaks per band.
 * Only these peaks (a few hundred numbers) ever leave the phone, never the audio.
 */
class ShazamSignature {
    class Peak(val pass: Int, val magnitude: Int, val bin: Int)

    /** Frequency band id → peaks (bands 0..3: 250–520, 520–1450, 1450–3500, 3500–5500 Hz). */
    val bands = sortedMapOf<Int, MutableList<Peak>>()
    var samples = 0; private set

    private val ring = ShortArray(2048)
    private var ringPos = 0
    private val fftOut = Array(256) { DoubleArray(1025) }
    private var fftPos = 0
    private var fftWritten = 0
    private val spread = Array(256) { DoubleArray(1025) }
    private var spreadPos = 0
    private var spreadWritten = 0
    private val re = DoubleArray(2048)
    private val im = DoubleArray(2048)

    /** Feeds 16 kHz mono signed 16-bit samples; whole 128-sample chunks are processed, a tail under 128 is dropped. */
    fun feed(pcm: ShortArray, n: Int = pcm.size) {
        var i = 0
        while (i + 128 <= n) { process(pcm, i); i += 128 }
    }

    val peakCount: Int get() = bands.values.sumOf { it.size }
    val durationMs: Long get() = samples * 1000L / SAMPLE_RATE

    private fun process(pcm: ShortArray, off: Int) {
        samples += 128
        System.arraycopy(pcm, off, ring, ringPos, 128)
        ringPos = (ringPos + 128) % 2048
        // Oldest → newest, windowed.
        for (k in 0 until 2048) { re[k] = ring[(ringPos + k) % 2048] * HANNING[k]; im[k] = 0.0 }
        fft(re, im)
        val out = fftOut[fftPos]
        for (k in 0..1024) out[k] = maxOf((re[k] * re[k] + im[k] * im[k]) / (1 shl 17), 1e-10)
        fftPos = (fftPos + 1) % 256; fftWritten++
        spreadPeaks(out)
        if (spreadWritten >= 46) recognise()
    }

    private fun spreadPeaks(last: DoubleArray) {
        val s = last.copyOf()
        for (p in 0 until 1025) {
            if (p < 1023) s[p] = maxOf(last[p], last[p + 1], last[p + 2])
            var v = s[p]
            for (former in intArrayOf(-1, -3, -6)) {
                val f = spread[Math.floorMod(spreadPos + former, 256)]
                v = maxOf(f[p], v); f[p] = v
            }
        }
        System.arraycopy(s, 0, spread[spreadPos], 0, 1025)
        spreadPos = (spreadPos + 1) % 256; spreadWritten++
    }

    private fun recognise() {
        val f46 = fftOut[Math.floorMod(fftPos - 46, 256)]
        val f49 = spread[Math.floorMod(spreadPos - 49, 256)]
        for (bin in 10 until 1015) {
            val v = f46[bin]
            if (v < 1.0 / 64 || v < f49[bin - 1]) continue
            var maxN = 0.0
            for (o in NEIGHBOURS) maxN = maxOf(f49[bin + o], maxN)
            if (v <= maxN) continue
            var maxT = maxN
            for (o in OTHER_OFFSETS) maxT = maxOf(spread[Math.floorMod(spreadPos + o, 256)][bin - 1], maxT)
            if (v <= maxT) continue
            val pass = spreadWritten - 46
            val mag = ln(maxOf(1.0 / 64, v)) * 1477.3 + 6144
            val before = ln(maxOf(1.0 / 64, f46[bin - 1])) * 1477.3 + 6144
            val after = ln(maxOf(1.0 / 64, f46[bin + 1])) * 1477.3 + 6144
            val var1 = mag * 2 - before - after
            if (var1 <= 0) continue
            val var2 = (after - before) * 32 / var1
            val corrected = bin * 64 + var2
            val hz = corrected * (SAMPLE_RATE / 2.0 / 1024 / 64)
            val band = when {
                hz < 250 -> continue
                hz < 520 -> 0
                hz < 1450 -> 1
                hz < 3500 -> 2
                hz <= 5500 -> 3
                else -> continue
            }
            bands.getOrPut(band) { mutableListOf() } += Peak(pass, mag.toInt(), corrected.toInt())
        }
    }

    /** The binary signature (header + CRC-32 + peaks), as SongRec's `encode_to_binary`. */
    fun encode(): ByteArray {
        val contents = ByteArrayOutputStream()
        for ((band, peaks) in bands) {
            val pb = ByteArrayOutputStream()
            var pass = 0
            for (p in peaks) {
                if (p.pass - pass >= 255) { pb.write(0xFF); pb.le32(p.pass); pass = p.pass }
                pb.write(p.pass - pass)
                pb.le16(p.magnitude); pb.le16(p.bin)
                pass = p.pass
            }
            val body = pb.toByteArray()
            contents.le32(0x60030040 + band); contents.le32(body.size); contents.write(body)
            repeat((4 - body.size % 4) % 4) { contents.write(0) }
        }
        val c = contents.toByteArray()
        val tail = ByteArrayOutputStream()
        // Header after magic1 + crc: size, magic2, 3 void, sample-rate id, 2 void, samples+rate*0.24, fixed value.
        tail.le32(c.size + 8); tail.le32(0x94119C00.toInt())
        repeat(3) { tail.le32(0) }
        tail.le32(3 shl 27) // 16 000 Hz
        repeat(2) { tail.le32(0) }
        tail.le32((samples + SAMPLE_RATE * 0.24).toInt())
        tail.le32((15 shl 19) + 0x40000)
        tail.le32(0x40000000); tail.le32(c.size + 8)
        tail.write(c)
        val rest = tail.toByteArray()
        val crc = CRC32().apply { update(rest) }.value.toInt()
        val out = ByteArrayOutputStream()
        out.le32(0xCAFE2580.toInt()); out.le32(crc); out.write(rest)
        return out.toByteArray()
    }

    fun uri(): String = DATA_URI_PREFIX + java.util.Base64.getEncoder().encodeToString(encode())

    companion object {
        const val SAMPLE_RATE = 16_000
        const val DATA_URI_PREFIX = "data:audio/vnd.shazam.sig;base64,"
        /** numpy.hanning(2050)[1:-1]. */
        private val HANNING = DoubleArray(2048) { n -> 0.5 - 0.5 * cos(2 * PI * (n + 1) / 2049) }
        private val NEIGHBOURS = intArrayOf(-10, -7, -4, -3, 1, 2, 5, 8)
        private val OTHER_OFFSETS = intArrayOf(-53, -45) + (165..200 step 7).toList() + (214..249 step 7).toList()

        /** In-place radix-2 FFT (n = 2048). */
        private fun fft(re: DoubleArray, im: DoubleArray) {
            val n = re.size
            var j = 0
            for (i in 1 until n) {
                var bit = n shr 1
                while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
                j = j xor bit
                if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
            }
            var len = 2
            while (len <= n) {
                val ang = -2 * PI / len
                val wr = cos(ang); val wi = sin(ang)
                var i = 0
                while (i < n) {
                    var cr = 1.0; var ci = 0.0
                    for (k in 0 until len / 2) {
                        val a = i + k; val b = a + len / 2
                        val xr = re[b] * cr - im[b] * ci
                        val xi = re[b] * ci + im[b] * cr
                        re[b] = re[a] - xr; im[b] = im[a] - xi
                        re[a] += xr; im[a] += xi
                        val ncr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = ncr
                    }
                    i += len
                }
                len = len shl 1
            }
        }

        private fun ByteArrayOutputStream.le32(v: Int) { write(v and 0xFF); write(v ushr 8 and 0xFF); write(v ushr 16 and 0xFF); write(v ushr 24 and 0xFF) }
        private fun ByteArrayOutputStream.le16(v: Int) { write(v and 0xFF); write(v ushr 8 and 0xFF) }
    }
}
