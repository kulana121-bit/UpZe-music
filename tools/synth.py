#!/usr/bin/env python3
"""Original royalty-free demo tracks for MusicSpot, synthesized from scratch (numpy).
Each track: chord pad + bass + arpeggio/pluck + optional drums, simple stereo mix, encoded to OGG Vorbis.
Writes app res/raw/track_XX.ogg and gen/tracks.json (durations)."""
import numpy as np, json, subprocess, os, sys
from scipy.signal import lfilter

SR = 44100
OUT = sys.argv[1] if len(sys.argv) > 1 else "out"
os.makedirs(OUT, exist_ok=True)
rng = np.random.default_rng(7)

def note_hz(n):  # midi
    return 440.0 * 2 ** ((n - 69) / 12)

def env_adsr(n, a=0.01, d=0.1, s=0.7, r=0.2):
    t = np.arange(n) / SR
    tot = n / SR
    e = np.ones(n) * s
    e[t < a] = t[t < a] / max(a, 1e-4)
    m = (t >= a) & (t < a + d)
    e[m] = 1 - (1 - s) * (t[m] - a) / max(d, 1e-4)
    rm = t > tot - r
    e[rm] *= np.clip((tot - t[rm]) / max(r, 1e-4), 0, 1)
    return e


def onepole(x, cutoff):
    a = np.exp(-2 * np.pi * cutoff / SR)
    return lfilter([1 - a], [1, -a], x)

def saw(f, n, phase=0.0):
    t = np.arange(n) / SR
    return 2 * ((f * t + phase) % 1.0) - 1

def tri(f, n):
    return 2 * np.abs(saw(f, n)) - 1

def sine(f, n):
    return np.sin(2 * np.pi * f * np.arange(n) / SR)

def pad_chord(notes, dur, bright=1200, detune=0.006):
    n = int(dur * SR)
    out = np.zeros(n)
    for m in notes:
        f = note_hz(m)
        for d in (-detune, 0, detune):
            out += saw(f * (1 + d), n, rng.random()) * 0.33
    out = onepole(onepole(out, bright), bright)
    return out * env_adsr(n, a=min(0.6, dur * 0.3), d=0.3, s=0.8, r=min(0.6, dur * 0.3)) / len(notes)

def pluck(m, dur, decay=0.996):
    """Karplus-Strong string."""
    n = int(dur * SR)
    p = max(2, int(SR / note_hz(m)))
    buf = rng.uniform(-1, 1, p)
    out = np.zeros(n)
    # block-wise KS: each period is average of previous period shifted
    pos = 0
    cur = buf
    while pos < n:
        take = min(p, n - pos)
        out[pos:pos + take] = cur[:take]
        nxt = decay * 0.5 * (cur + np.roll(cur, -1))
        cur = nxt
        pos += p
    return out * env_adsr(n, a=0.002, d=0.05, s=1.0, r=0.05)

def ep_note(m, dur):  # electric-piano-ish FM bell
    n = int(dur * SR)
    t = np.arange(n) / SR
    f = note_hz(m)
    mod = np.sin(2 * np.pi * f * 2 * t) * 1.5 * np.exp(-t * 6)
    x = np.sin(2 * np.pi * f * t + mod) * np.exp(-t * 2.2)
    return x * env_adsr(n, a=0.003, d=0.1, s=1, r=0.08)

def bass_note(m, dur, kind="sine"):
    n = int(dur * SR)
    f = note_hz(m)
    if kind == "saw":
        x = onepole(onepole(saw(f, n), 500), 500) * 1.6
    else:
        x = sine(f, n) + 0.3 * sine(2 * f, n)
    return x * env_adsr(n, a=0.005, d=0.15, s=0.7, r=0.05)

def kick(dur=0.35):
    n = int(dur * SR)
    t = np.arange(n) / SR
    f = 50 + 110 * np.exp(-t * 30)
    ph = 2 * np.pi * np.cumsum(f) / SR
    return np.sin(ph) * np.exp(-t * 9) * 1.1

def snare(dur=0.25):
    n = int(dur * SR)
    t = np.arange(n) / SR
    noise = rng.uniform(-1, 1, n)
    noise = noise - onepole(noise, 1500)
    return (noise * np.exp(-t * 18) * 0.6 + np.sin(2 * np.pi * 190 * t) * np.exp(-t * 25) * 0.4)

def hat(dur=0.06, open_=False):
    n = int((0.25 if open_ else dur) * SR)
    t = np.arange(n) / SR
    noise = rng.uniform(-1, 1, n)
    noise = noise - onepole(noise, 6000)
    return noise * np.exp(-t * (12 if open_ else 60)) * 0.35

def place(buf, x, at, gain=1.0):
    i = int(at * SR)
    if i >= len(buf):
        return
    j = min(len(buf), i + len(x))
    buf[i:j] += x[: j - i] * gain

def reverb(x, mix=0.25):
    out = x.copy()
    for d, g in ((0.031, 0.5), (0.047, 0.42), (0.071, 0.35), (0.113, 0.28), (0.167, 0.2), (0.241, 0.14)):
        k = int(d * SR)
        y = np.zeros_like(x)
        y[k:] = x[:-k] * g
        out += y * mix
    return out

# chord helpers (midi roots)
def triad(root, kind="maj", inv=0, seventh=False):
    iv = {"maj": [0, 4, 7], "min": [0, 3, 7], "sus": [0, 5, 7]}[kind]
    if seventh:
        iv = iv + [10 if kind == "min" else 11]
    return [root + i for i in iv]

TRACKS = [
    # title, artist, album, bpm, progression [(root, kind)], style, seconds
    dict(id="t01", title="Neon Afterglow", artist="Lumen Drive", album="City of Echoes", bpm=104, key=57,
         prog=[(57, "min"), (53, "maj"), (48, "maj"), (55, "maj")], style="synthwave", secs=44),
    dict(id="t02", title="Velvet Static", artist="Mira Sol", album="Night Bloom", bpm=86, key=50,
         prog=[(50, "min"), (55, "maj"), (48, "maj"), (57, "min")], style="lofi", secs=42),
    dict(id="t03", title="Glass Horizon", artist="Lumen Drive", album="City of Echoes", bpm=120, key=52,
         prog=[(52, "min"), (48, "maj"), (55, "maj"), (50, "maj")], style="house", secs=40),
    dict(id="t04", title="Slow Tide", artist="Oriel Bay", album="Coastal Hours", bpm=70, key=48,
         prog=[(48, "maj"), (45, "min"), (53, "maj"), (55, "sus")], style="ambient", secs=48),
    dict(id="t05", title="Paper Lanterns", artist="Kaito Rain", album="Small Lights", bpm=92, key=55,
         prog=[(55, "maj"), (52, "min"), (48, "maj"), (50, "maj")], style="acoustic", secs=40),
    dict(id="t06", title="Midnight Circuit", artist="Vanta Rose", album="Low Orbit", bpm=112, key=53,
         prog=[(53, "min"), (49, "maj"), (56, "maj"), (51, "maj")], style="synthwave", secs=42),
    dict(id="t07", title="Coffee & Rain", artist="Mira Sol", album="Night Bloom", bpm=78, key=53,
         prog=[(53, "maj"), (50, "min"), (46, "maj"), (48, "maj")], style="lofi", secs=40),
    dict(id="t08", title="Aurora Steps", artist="Oriel Bay", album="Coastal Hours", bpm=100, key=50,
         prog=[(50, "maj"), (47, "min"), (55, "maj"), (57, "maj")], style="acoustic", secs=38),
    dict(id="t09", title="Pulse Theory", artist="Vanta Rose", album="Low Orbit", bpm=124, key=45,
         prog=[(45, "min"), (41, "maj"), (48, "maj"), (43, "maj")], style="house", secs=40),
    dict(id="t10", title="Lighthouse Hum", artist="Kaito Rain", album="Small Lights", bpm=66, key=52,
         prog=[(52, "maj"), (49, "min"), (45, "maj"), (47, "sus")], style="ambient", secs=46),
]

def render(tr):
    secs = tr["secs"]
    n = int(secs * SR)
    L = np.zeros(n); R = np.zeros(n)
    beat = 60.0 / tr["bpm"]
    bar = beat * 4
    style = tr["style"]
    t = 0.0
    bi = 0
    while t < secs - 0.01:
        root, kind = tr["prog"][bi % len(tr["prog"])]
        chord = triad(root + 12, kind, seventh=style in ("lofi", "ambient"))
        dur = min(bar, secs - t)
        # pad
        bright = {"synthwave": 1800, "lofi": 900, "house": 2200, "ambient": 1100, "acoustic": 800}[style]
        pg = {"synthwave": 0.55, "lofi": 0.35, "house": 0.4, "ambient": 0.75, "acoustic": 0.25}[style]
        p = pad_chord(chord, dur + 0.3, bright=bright)
        place(L, p, t, pg); place(R, np.roll(p, 120), t, pg)
        # bass
        bkind = "saw" if style in ("synthwave", "house") else "sine"
        if style == "ambient":
            place(L, bass_note(root - 12, dur, "sine"), t, 0.35); place(R, bass_note(root - 12, dur, "sine"), t, 0.35)
        else:
            steps = 8 if style in ("synthwave", "house") else 4
            for s in range(steps):
                st = t + s * bar / steps
                if st >= secs: break
                m = root - 12 if (style != "house" or s % 2 == 1) else root - 12
                b = bass_note(m + (12 if (style == "synthwave" and s % 4 == 3) else 0), bar / steps * 0.9, bkind)
                place(L, b, st, 0.45); place(R, b, st, 0.45)
        # melodic layer
        if style in ("synthwave", "house"):
            arp = chord + [chord[0] + 12]
            for s in range(16):
                st = t + s * bar / 16
                if st >= secs: break
                m = arp[(s * (3 if style == "house" else 1)) % len(arp)] + 12
                x = onepole(saw(note_hz(m), int(bar / 16 * SR * 0.8)), 2500) * env_adsr(int(bar / 16 * SR * 0.8), 0.002, 0.08, 0.3, 0.03)
                pan = 0.5 + 0.4 * np.sin(s)
                place(L, x, st, 0.18 * (1 - pan) * 2); place(R, x, st, 0.18 * pan * 2)
        elif style in ("acoustic",):
            pattern = [0, 1, 2, 1, 3, 1, 2, 1]
            arp = chord + [chord[0] + 12]
            for s in range(8):
                st = t + s * bar / 8
                if st >= secs: break
                x = pluck(arp[pattern[s]], 1.2)
                place(L, x, st, 0.32 if s % 2 == 0 else 0.22); place(R, x, st, 0.22 if s % 2 == 0 else 0.32)
        elif style == "lofi":
            for s, off in enumerate((0, 1.5, 2.5)):
                st = t + off * beat
                if st >= secs: break
                x = sum(ep_note(m + 12, beat * 1.4) for m in chord[:3]) / 3
                place(L, x, st, 0.4); place(R, x, st, 0.4)
        elif style == "ambient":
            for s in range(4):
                st = t + s * beat + (0.25 * beat if s % 2 else 0)
                if st >= secs: break
                m = chord[rng.integers(0, len(chord))] + 24
                x = ep_note(m, beat * 2.5)
                place(L, x, st, 0.16 if s % 2 else 0.1); place(R, x, st, 0.1 if s % 2 else 0.16)
        # drums
        if style in ("synthwave", "house", "lofi", "acoustic") and t >= (bar if style != "house" else 0):
            for b in range(4):
                st = t + b * beat
                if st >= secs: break
                if style == "house" or b in (0, 2) or (style == "lofi" and b == 2):
                    k = kick(); place(L, k, st, 0.7); place(R, k, st, 0.7)
                if b in (1, 3):
                    sn = snare() * (0.6 if style in ("lofi", "acoustic") else 1)
                    place(L, sn, st, 0.45); place(R, sn, st, 0.45)
                for h in range(2):
                    hs = st + h * beat / 2 + (0.03 if (style == "lofi" and h == 1) else 0)
                    if hs >= secs: break
                    hh = hat(open_=(style == "house" and h == 1))
                    g = 0.5 if style != "acoustic" else 0.25
                    place(L, hh, hs, g * 0.8); place(R, hh, hs, g)
        t += bar
        bi += 1
    L = reverb(L, 0.3); R = reverb(R, 0.3)
    st = np.stack([L, R], axis=1)
    # vinyl crackle for lofi
    if style == "lofi":
        cr = (rng.random(n) > 0.9993) * rng.uniform(-0.3, 0.3, n)
        st += np.stack([cr, cr], axis=1) + rng.normal(0, 0.004, (n, 2))
    # fades
    fi = int(1.0 * SR); fo = int(3.0 * SR)
    st[:fi] *= np.linspace(0, 1, fi)[:, None]
    st[-fo:] *= np.linspace(1, 0, fo)[:, None] ** 1.5
    st = np.tanh(st / (np.max(np.abs(st)) + 1e-9) * 1.3) * 0.89
    return st

meta = []
for i, tr in enumerate(TRACKS, 1):
    pcm = render(tr)
    wav = (pcm * 32767).astype(np.int16).tobytes()
    out = os.path.join(OUT, f"track_{i:02d}.ogg")
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "s16le", "-ar", str(SR), "-ac", "2", "-i", "-",
                    "-c:a", "libvorbis", "-q:a", "1", out], input=wav, check=True)
    d = float(subprocess.check_output(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", out]).decode())
    meta.append(dict(res=f"track_{i:02d}", title=tr["title"], artist=tr["artist"], album=tr["album"], bpm=tr["bpm"],
                     style=tr["style"], durationMs=int(d * 1000), bytes=os.path.getsize(out)))
    print(meta[-1], flush=True)
json.dump(meta, open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "tracks.json"), "w"), indent=1)
