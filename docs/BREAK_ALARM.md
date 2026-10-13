# Break Alarm

Break Alarm is a fork of [reReminder](https://github.com/ProfessorQuantumUniverse/reReminder)
(GPL-3.0, by ProfessorQuantumUniverse), narrowed to one job: ring a gentle alarm every N minutes
so you take a break, reliably, even when the phone is locked or the screen is off.

## Identity

| | |
|---|---|
| App name | Break Alarm |
| `applicationId` | `io.github.eddocg.breakalarm`. This is provisional, derived from the GitHub account that hosts the fork. Change it before publishing if you prefer another namespace you control. |
| Kotlin package / `namespace` | `com.olaf.rereminder`, unchanged so upstream changes keep merging cleanly |
| Version | 0.1.0 (versionCode 1) |
| License | GPL-3.0, unchanged. Upstream copyright and attribution are kept; Settings > About credits upstream and links to the source. |

Because the `applicationId` differs, Break Alarm installs alongside reReminder. The alarm
screen's task affinity is derived from the `applicationId`, so the two apps' alarm screens
never share a task.

## Behaviour

| Action | Effect |
|---|---|
| Fresh install | One break reminder, 20 minutes, not running. Nothing rings until Start. |
| **Start** | The first alarm rings one full interval later. Later alarms follow a fixed cadence from the start time (`start + k·interval`). |
| **Dismiss** | Stops the sound. Future alarms are unaffected, and a pending snooze is cleared. |
| **Snooze** | Rings the current break again after the snooze length (default 5 minutes). The regular cadence is not moved. A snooze that would land at or after the next regular alarm is dropped, so you never get two alarms back to back. |
| **Pause** | Cancels all alarms. The screen offers Resume. |
| **Resume** | Restarts the countdown with a full interval from now. The time left at Pause is not kept, as the spec asks. |
| **Stop** | Cancels all alarms. The screen offers Start. |
| Interval change | While running, the countdown restarts at the new length. While paused or stopped, the new length is only remembered. The range is 1–240 minutes. |
| Working hours (optional) | Alarms keep their cadence and ring only inside the chosen days and hours. Ticks outside them are skipped, never moved. |
| Sound timeout | After 30 s, 1, 2 or 5 minutes (default 1 minute) the sound stops. Dismiss and Snooze stay on the alarm screen and in the notification. After a further 30 minutes without an answer, the alarm closes and a quiet "missed" notification remains. |

A late, dismissed or snoozed alarm never shifts the alarms after it. Process death, reboot,
app update, a time or timezone change, or an exact-alarm permission change all re-arm the
same next alarm from the stored state.

## Architecture

The break timer is an ordinary reReminder `Reminder`: an interval timer whose alert style is
`ALARM`. It therefore runs on upstream's tested engine, almost unchanged:

- **Storage.** `ReminderRepository` keeps a JSON file in device-protected storage, so it is
  readable before first unlock.
- **Schedule math.** `data/Schedule.kt` is pure and unit-tested. It computes a fixed grid
  (`anchor + k·interval`), filtered by the time windows.
- **Scheduling.** `ReminderScheduler.sync()` is idempotent. It arms exactly one
  `setExactAndAllowWhileIdle` alarm per reminder, plus at most one snooze alarm, and replaces
  rather than adds.
- **Ringing.** `AlarmReceiver` re-arms the next alarm before ringing, then hands off to
  `AlarmService`. That is a short-lived foreground service that exists only while ringing.
  `AlarmActivity` shows over the lock screen when Android allows it; otherwise the
  high-priority notification carries Dismiss and Snooze.
- **Recovery.** `BootReceiver` re-arms after boot (including locked boot), app update, time and
  timezone changes, and exact-alarm permission changes.

What this fork adds on top:

| File | Role |
|---|---|
| `data/BreakTimer.kt` | Pure break-timer vocabulary: default reminder, start/pause/resume/stop and interval transitions, and the screen state (remaining time, ring fraction, snooze, waiting for working hours). |
| `data/Schedule.kt` | `snoozeToKeep`: pure snooze de-duplication against the next regular alarm. `gridAfter`: after the clock is set back past the start, the grid continues backwards, so the next alarm stays within one interval. |
| `ui/timer/BreakTimerScreen.kt`, `BreakTimerViewModel.kt` | Main screen: circular countdown in MM:SS, Start/Pause/Resume/Stop, interval dialog, working-hours line, and a readiness card listing anything that would stop the alarm. |
| `utils/GentleSound.kt`, `res/raw/alarm_*.wav`, `tools/generate_sounds.py` | The three bundled sounds (see [SOUNDS.md](SOUNDS.md)). |
| `utils/Reliability.kt` (`AlarmReadiness`) | Checks notifications, exact alarms, the Android 14+ full-screen permission, and a muted alarm stream. Each blocker has an intent cascade that opens the right settings screen. |
| `service/AlarmService.kt`, `utils/AlertPlayer.kt` | In-app gain (10–100 %) with an 8-second fade-in, the sound timeout, and a silenced state that keeps the actions available. |
| `ui/settings/*` | Rewritten settings: sound with preview, loudness, sound timeout, snooze, vibration, working hours, reliability (including full-screen and alarm volume), and About/GPL. |
| `ui/setup/*` | The first-run checklist gained an Android 14+ full-screen step. |
| `ui/theme/*`, `res/values*/colors.xml` | A soft sage-green Material 3 palette in light and dark. Dynamic colour is off by default so the palette is consistent. |

These are deliberately **not** included:

- **No new dependencies.**
- **No network, no `INTERNET` permission, no analytics.**
- **No polling.** The countdown only ticks (every 250 ms) while the main screen is visible.
- **The system volume is never modified.**

Upstream's reminder list and editor are no longer reachable from navigation. Their code is
left in place so upstream merges stay small; removing it is a possible follow-up.

## Building and testing

Requirements: JDK 17 and the Android SDK (platform 37, build-tools 37.0.0). Point
`local.properties` at the SDK with `sdk.dir=…`.

```bash
./gradlew testDebugUnitTest     # JVM unit tests
./gradlew lintDebug             # Android lint (0 errors expected)
./gradlew assembleDebug         # app/build/outputs/apk/debug/app-debug.apk
```

The unit tests in `app/src/test` cover:

| Area | Tests |
|---|---|
| Fresh install, start, fixed cadence, late dismiss, stale-alarm detection | `BreakTimerTest` |
| Pause, resume (full interval), stop, interval clamp and restart | `BreakTimerTest` |
| Snooze: grid unaffected, dropped at or after the next break, dropped if expired | `BreakTimerTest` |
| Screen state: countdown, rounding, unarmed, snoozed, waiting for working hours, MM:SS | `BreakTimerTest` |
| Persistence round-trip, old data without `pausedAtMillis`, process recreation, idempotent re-arming | `BreakTimerTest` |
| Timezone change, clock set back, spring and autumn DST with a 20-minute interval, working-hours boundaries | `BreakTimerTest` |
| Alarm readiness blockers | `ReliabilityTest` |
| Sound catalogue and fallback | `GentleSoundTest` |
| Android 14+ full-screen setup step | `SetupStateTest` |
| Upstream schedule, codec and vendor tests | `ScheduleTest`, `ReminderCodecTest`, `ReliabilityTest` |

Unit tests cannot prove lock-screen, Doze or OEM behaviour. That needs a physical device.

### Emulator smoke test (12 Oct 2026)

These results come from an Android 15 (API 35) arm64 `google_apis` emulator, Pixel 6 profile,
with no lock-screen PIN. They were checked through `dumpsys alarm`, `dumpsys notification`,
logcat and screenshots. An emulator is not a substitute for the OnePlus 10T checklist below.

| Check | Result |
|---|---|
| First-run checklist, notification permission dialog, main screen, settings, light and dark | Rendered; no crash in the crash buffer |
| Start, 1-minute interval | Exactly one exact `RTC_WAKEUP` alarm at Start + 1:00. 5 wake-ups in 5 minutes, all on the grid (notification `when` matches to the millisecond) |
| Alarm while the phone is in use | Heads-up notification (alarm category, high priority) with Snooze and Dismiss |
| Screen turned off while ringing | SystemUI launched the full-screen alarm over the lock screen (`full_screen_intent`) |
| Snooze landing after the next break (1-minute interval) | Dropped; only the regular alarm stays armed; service stopped |
| Snooze landing before the next break (10-minute interval) | Snooze alarm at +5:00 and regular alarm unchanged, two distinct alarms |
| Dismiss | Regular alarm unchanged |
| Pause, Resume, Stop | Nothing armed; full interval from Resume; nothing armed |
| Interval change while running | Countdown restarted at the new length |
| Clock moved forward past a snooze | `TIME_SET` handled; overdue snooze rang at once; regular alarm unchanged |
| Clock moved back 15 minutes | Next alarm stays within one interval on the same grid |
| App process killed (`kill -9`) | Alarm stays armed |
| Reboot | Re-armed at `LOCKED_BOOT_COMPLETED`, same absolute time |
| Install over a running timer | Still armed afterwards |

Not covered on the emulator: hours of Doze, OEM battery management, audible sound output (the
emulator ran without audio), TalkBack, and direct boot with a PIN.

## Device validation (OnePlus 10T)

Install the debug APK and keep a log running:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -s AlarmReceiver AlarmService ReminderScheduler BootReceiver
adb shell dumpsys alarm | grep -A4 io.github.eddocg.breakalarm   # the armed alarm
```

Preparation, on the phone:

1. Complete the first-run checklist: allow notifications, exact alarms and (on Android 14+)
   full-screen alarms, and allow background running.
2. OxygenOS: Settings > Apps > Break Alarm > Battery > **Unrestricted**. Optionally lock the
   app in Recents. See the [dontkillmyapp OnePlus guide](https://dontkillmyapp.com/oneplus).
3. Settings > Sound: alarm volume above zero.

Checks. Set the interval to 1–2 minutes for quick runs unless noted, and record pass or fail
with the time observed:

| # | Scenario | Expected |
|---|---|---|
| 1 | Start, lock the phone, screen off | The alarm screen appears over the lock screen at start + interval, and the sound fades in |
| 2 | Dismiss | The sound stops; the next alarm is exactly one interval after the previous scheduled time |
| 3 | Snooze (5 min) on a 20-min interval | Rings again 5 min later; the regular alarm still rings at its time, with no extra alarm |
| 4 | Pause, wait past the due time | Nothing rings. Resume then rings one full interval after Resume |
| 5 | Stop | Nothing rings; Start is offered |
| 6 | Let it ring untouched | The sound stops after the timeout; Dismiss and Snooze still work |
| 7 | 20-min interval, screen off, idle for 1 h or more (Doze) | Every alarm rings on time, within a few seconds |
| 8 | Reboot while running, don't unlock | The next alarm still rings (direct boot) |
| 9 | Swipe the app away from Recents | Alarms keep ringing |
| 10 | Change the timezone while running | The next alarm keeps its absolute time (working hours follow the new local clock) |
| 11 | Revoke full-screen permission (Android 14+) | A heads-up notification with Dismiss and Snooze appears instead; the readiness card warns |
| 12 | Alarm volume at 0 | The readiness card and Settings warn; nothing changes the volume |
| 13 | Do Not Disturb on | Depends on DND's "Alarms" exception; note the behaviour |
| 14 | Install a newer build over a running timer | Still armed afterwards |
| 15 | Dark mode, TalkBack | Readable, and the countdown is announced as minutes and seconds |

Doze can be forced on the device with:

```bash
adb shell dumpsys deviceidle force-idle
adb shell dumpsys deviceidle unforce
```

## Known limitations

- **Force-stop cancels everything.** Settings > Apps > Force stop cancels all of an app's
  alarms; this is Android behaviour. The next Start, or opening the app, re-arms them.
- **OEM battery management.** OxygenOS and other OEM builds can still delay or kill
  background work despite the exemptions. No cross-OEM guarantee is made, and device results
  must come from the device checklist above.
- **Full-screen alarms on Android 14+** need the user's permission, and Google Play restricts
  `USE_FULL_SCREEN_INTENT` and `USE_EXACT_ALARM` to alarm-type apps. A Play release would need
  a policy declaration; sideloaded and F-Droid builds are unaffected.
- **Resume does not keep the remaining time.** It restarts with a full interval, as specified.
- **Long snoozes can be dropped.** A snooze longer than the time left until the next break is
  dropped in favour of that break, for example a 30-minute snooze on a 20-minute interval.
- **One working-hours window in the UI.** The data model supports several, and windows may
  cross midnight.
- **Do Not Disturb** handling of alarms follows the user's DND settings.
- **Leftover upstream wording.** A few first-run and reliability texts still speak of
  "reminders". The launcher glyph is upstream's, recoloured. A dedicated icon is a follow-up.
