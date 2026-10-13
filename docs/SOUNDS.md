# Bundled alarm sounds

Break Alarm ships three alarm sounds. All three are original: they were synthesized for this
fork by [`tools/generate_sounds.py`](../tools/generate_sounds.py) from plain sine partials. No
samples, recordings, sound libraries or third-party material went into them, and none comes from
BSDIGI or any other app.

| Sound | File | Length | What it is |
|---|---|---|---|
| Soft chime | `app/src/main/res/raw/alarm_soft_chime.wav` | 3.0 s | Two soft struck notes, C5 then G5 |
| Gentle bell | `app/src/main/res/raw/alarm_gentle_bell.wav` | 4.5 s | One G4 bell strike with slightly inharmonic partials |
| Calm ambient tone | `app/src/main/res/raw/alarm_calm_tone.wav` | 6.0 s | A slow A-major pad that swells and fades |

All are 22,050 Hz, 16-bit mono PCM WAV, peaking at −6 dBFS. Each fades in and out at its edges,
so it loops without a click while the alarm rings.

## License

The three sound files are dedicated to the public domain under
[CC0 1.0 Universal](https://creativecommons.org/publicdomain/zero/1.0/). The script that
generates them is part of this repository and, like the rest of the code, is licensed under the
GNU GPL v3 (see [`LICENSE`](../LICENSE)).

> CC0 was chosen by the fork's author so the sounds can be reused freely. It is compatible with
> the GPL. If you redistribute this fork with different sounds, document their source and
> license here.

## Reproducing the files

```bash
python3 tools/generate_sounds.py
shasum -a 256 app/src/main/res/raw/alarm_*.wav
```

The output is deterministic and uses only the Python standard library. Running the script
again must reproduce these checksums exactly:

```text
963f0e6dc04267eab68b30f0cc2762b3d72c595871158b0cd985b4e26e892efc  alarm_soft_chime.wav
50d615e68727666cfe265b9b28c44cd8900f24763eb409ed2e1218194e7fa049  alarm_gentle_bell.wav
983de67ecb6a21aa8a7f6f9f3422c38af09bacfacbfdc7587b9dde7f54c4ce6c  alarm_calm_tone.wav
```

## How loud they play

The app never changes the phone's volume. A sound plays on the alarm stream (`USAGE_ALARM`), so
the phone's own alarm volume is the ceiling. Within that, Settings > Alarm > Loudness scales the
sound from 10 % to 100 % (default 70 %). Each alarm fades in from 30 % of that level over about
8 seconds. The sound stops after the configured timeout (30 s, 1, 2 or 5 minutes; default 1
minute), and Dismiss and Snooze stay on screen afterwards.
