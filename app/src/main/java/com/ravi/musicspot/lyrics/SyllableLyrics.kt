package com.ravi.musicspot.lyrics

import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

/**
 * Parsers and decryptors for syllable-level / word-by-word karaoke lyrics formats (KRC and YRC).
 */
object KrcDecryptor {
    // Standard KuGou KRC XOR key table (16 bytes)
    private val KRC_KEY = byteArrayOf(
        0x40, 0x47, 0x61, 0x77, 0x5e, 0x32, 0x74, 0x47,
        0x51, 0x36, 0x31, 0x2d, 0xce.toByte(), 0xd2.toByte(), 0x6e, 0x69
    )

    /**
     * Decrypts raw KuGou .krc binary data.
     * Expects "krc1" 4-byte magic header, followed by XOR-encrypted zlib stream.
     */
    fun decrypt(data: ByteArray): String? {
        if (data.size <= 4) return null
        // Verify 'krc1' header: 0x6b, 0x72, 0x63, 0x31
        if (data[0] != 0x6b.toByte() || data[1] != 0x72.toByte() ||
            data[2] != 0x63.toByte() || data[3] != 0x31.toByte()
        ) {
            return null
        }

        val decrypted = ByteArray(data.size - 4)
        for (i in decrypted.indices) {
            decrypted[i] = (data[i + 4].toInt() xor KRC_KEY[i % 16].toInt()).toByte()
        }

        return runCatching {
            val inflater = Inflater()
            try {
                inflater.setInput(decrypted)
                val bos = ByteArrayOutputStream(decrypted.size * 2)
                val buf = ByteArray(2048)
                while (!inflater.finished()) {
                    val count = inflater.inflate(buf)
                    if (count <= 0) break
                    bos.write(buf, 0, count)
                }
                bos.toString("UTF-8")
            } finally {
                inflater.end()
            }
        }.getOrNull()
    }
}

object KrcParser {
    private val LINE_RE = Regex("""^\[(\d+),(\d+)](.*)$""")
    private val WORD_RE = Regex("""<(\d+),(\d+),\d*>([^<]*)""")
    private val TAG_RE = Regex("""<(\d+),(\d+),\d*>""")
    private val OFFSET_RE = Regex("""\[offset:\s*([+-]?\d+)]""", RegexOption.IGNORE_CASE)

    fun parse(text: String, durationMs: Long = 0L): List<LyricLine> {
        val offset = OFFSET_RE.find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val lines = mutableListOf<LyricLine>()

        for (rawLine in text.lineSequence()) {
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty()) continue
            val match = LINE_RE.matchEntire(trimmed) ?: continue

            val lineStartRel = match.groupValues[1].toLongOrNull() ?: continue
            val lineDur = match.groupValues[2].toLongOrNull() ?: continue
            val body = match.groupValues[3]

            val lineStart = (lineStartRel - offset).coerceAtLeast(0L)
            val lineEnd = lineStart + lineDur

            val words = mutableListOf<LyricWord>()
            for (wMatch in WORD_RE.findAll(body)) {
                val wOffset = wMatch.groupValues[1].toLongOrNull() ?: 0L
                val wDur = wMatch.groupValues[2].toLongOrNull() ?: 0L
                val wText = wMatch.groupValues[3]
                if (wText.isNotEmpty()) {
                    val wStart = lineStart + wOffset
                    val wEnd = wStart + wDur
                    words.add(LyricWord(wText, wStart, wEnd))
                }
            }

            val fullText = if (words.isNotEmpty()) {
                words.joinToString("") { it.text }
            } else {
                TAG_RE.replace(body, "").trim()
            }.replace(Regex("""\s+"""), " ").trim()

            if (fullText.isNotEmpty()) {
                val isWordSynced = words.size >= 2
                lines.add(
                    LyricLine(
                        startMs = lineStart,
                        endMs = lineEnd,
                        text = fullText,
                        words = if (isWordSynced) words else LrcParser.spreadWords(fullText, lineStart, lineEnd),
                        wordSynced = isWordSynced
                    )
                )
            }
        }

        return lines.sortedBy { it.startMs }
    }
}

object YrcParser {
    // NetEase YRC format: [line_start,line_dur](word_start_rel,word_dur,0)word...
    private val LINE_RE = Regex("""^\[(\d+),(\d+)](.*)$""")
    private val WORD_RE = Regex("""\((\d+),(\d+),\d*\)([^()]*)""")
    private val TAG_RE = Regex("""\((\d+),(\d+),\d*\)""")

    fun parse(text: String, durationMs: Long = 0L): List<LyricLine> {
        val lines = mutableListOf<LyricLine>()

        for (rawLine in text.lineSequence()) {
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty()) continue
            val match = LINE_RE.matchEntire(trimmed) ?: continue

            val lineStart = match.groupValues[1].toLongOrNull() ?: continue
            val lineDur = match.groupValues[2].toLongOrNull() ?: continue
            val body = match.groupValues[3]
            val lineEnd = lineStart + lineDur

            val words = mutableListOf<LyricWord>()
            for (wMatch in WORD_RE.findAll(body)) {
                val wTime = wMatch.groupValues[1].toLongOrNull() ?: 0L
                val wDur = wMatch.groupValues[2].toLongOrNull() ?: 0L
                val wText = wMatch.groupValues[3]
                if (wText.isNotEmpty()) {
                    // wTime can be relative offset from lineStart or absolute time
                    val wStart = if (wTime >= lineStart) wTime else lineStart + wTime
                    val wEnd = wStart + wDur
                    words.add(LyricWord(wText, wStart, wEnd))
                }
            }

            val fullText = if (words.isNotEmpty()) {
                words.joinToString("") { it.text }
            } else {
                TAG_RE.replace(body, "").trim()
            }.replace(Regex("""\s+"""), " ").trim()

            if (fullText.isNotEmpty()) {
                val isWordSynced = words.size >= 2
                lines.add(
                    LyricLine(
                        startMs = lineStart,
                        endMs = lineEnd,
                        text = fullText,
                        words = if (isWordSynced) words else LrcParser.spreadWords(fullText, lineStart, lineEnd),
                        wordSynced = isWordSynced
                    )
                )
            }
        }

        return lines.sortedBy { it.startMs }
    }
}
