#!/usr/bin/env python3
"""Generates Break Alarm's bundled alarm sounds.

The three sounds are original works synthesized here from sine partials, with no samples or
third-party material. Running the script again reproduces the files byte for byte, so the
committed WAVs can always be checked against their source. See docs/SOUNDS.md for the license.

    python3 tools/generate_sounds.py

Only the Python standard library is used. Output: 16-bit mono PCM WAV in app/src/main/res/raw.
"""

import math
import os
import struct
import wave

RATE = 22050
PEAK = 0.5  # -6 dBFS: gentle by design, the in-app volume scales it further down.
OUT_DIR = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res", "raw")


def envelope(t, attack, tau):
    """A soft raised-cosine attack followed by an exponential decay."""
    if t < 0:
        return 0.0
    if t < attack:
        return 0.5 - 0.5 * math.cos(math.pi * t / attack)
    return math.exp(-(t - attack) / tau)


def edge_fade(i, total, fade):
    """Fades the first and last samples to silence so the sound loops without a click."""
    n = int(fade * RATE)
    if i < n:
        return i / n
    if i > total - n:
        return max(0.0, (total - i) / n)
    return 1.0


def soft_chime():
    """Two warm notes a fifth apart (C5, G5), then quiet — a chime every three seconds."""
    seconds = 3.0
    notes = [(0.0, 523.25), (0.42, 783.99)]
    partials = [(1.0, 1.0, 0.75), (2.0, 0.22, 0.35), (3.0, 0.05, 0.2)]  # ratio, gain, decay
    total = int(seconds * RATE)
    out = []
    for i in range(total):
        t = i / RATE
        s = 0.0
        for start, freq in notes:
            local = t - start
            if local < 0:
                continue
            for ratio, gain, tau in partials:
                s += gain * envelope(local, 0.008, tau) * math.sin(2 * math.pi * freq * ratio * local)
        out.append(s * edge_fade(i, total, 0.05))
    return out


def gentle_bell():
    """A soft struck bell on G4 with inharmonic partials and a slow shimmer."""
    seconds = 4.5
    freq = 392.0
    # Partial ratios of an idealized bar/bell, each decaying faster the higher it is.
    partials = [(1.0, 1.0, 1.6), (1.003, 0.5, 1.6), (2.76, 0.32, 0.8), (5.40, 0.12, 0.4), (8.93, 0.04, 0.25)]
    total = int(seconds * RATE)
    out = []
    for i in range(total):
        t = i / RATE
        s = 0.0
        for ratio, gain, tau in partials:
            s += gain * envelope(t, 0.004, tau) * math.sin(2 * math.pi * freq * ratio * t)
        out.append(s * edge_fade(i, total, 0.05))
    return out


def calm_tone():
    """A slowly swelling A-major pad that breathes in and out over six seconds."""
    seconds = 6.0
    chord = [220.0, 329.63, 440.0, 554.37]
    gains = [1.0, 0.7, 0.55, 0.3]
    total = int(seconds * RATE)
    out = []
    for i in range(total):
        t = i / RATE
        swell = math.sin(math.pi * t / seconds) ** 2  # silence -> full -> silence
        tremolo = 1.0 - 0.12 * (0.5 - 0.5 * math.cos(2 * math.pi * 0.5 * t))
        s = 0.0
        for f, g in zip(chord, gains):
            s += g * (math.sin(2 * math.pi * f * t) + 0.08 * math.sin(2 * math.pi * 2 * f * t))
        out.append(s * swell * tremolo)
    return out


def write(name, samples):
    peak = max(abs(s) for s in samples) or 1.0
    scale = PEAK / peak
    frames = b"".join(struct.pack("<h", int(round(s * scale * 32767))) for s in samples)
    path = os.path.join(OUT_DIR, name + ".wav")
    with wave.open(path, "wb") as f:
        f.setnchannels(1)
        f.setsampwidth(2)
        f.setframerate(RATE)
        f.writeframes(frames)
    print(f"wrote {os.path.normpath(path)} ({len(samples) / RATE:.1f} s)")


if __name__ == "__main__":
    os.makedirs(OUT_DIR, exist_ok=True)
    write("alarm_soft_chime", soft_chime())
    write("alarm_gentle_bell", gentle_bell())
    write("alarm_calm_tone", calm_tone())
