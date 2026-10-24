# On-device test plan

Clap mode has only been exercised on synthetic audio. Run this plan on real hardware before promoting beyond `alpha`.

## Device matrix (minimum)

| Slot | Examples |
|---|---|
| Android 8–9 (minSdk 26) | Any older device or emulator |
| Android 12–13 | Pixel or Samsung |
| Android 14+ | Pixel (foreground-service type enforcement) |
| One aggressive-battery OEM | Xiaomi, Oppo, Vivo, Huawei, OnePlus |

Record device, Android version, build version for every run.

## Cases

Expected results assume clap mode, sensitivity 0.5, 3 claps, unless stated.

| # | Case | Steps | Expected |
|---|---|---|---|
| T1 | First-run permissions | Fresh install → ARM | Microphone and notification prompts; service starts after grant |
| T2 | Permission denied | Deny microphone → ARM | "No microphone, no miracle."; no service |
| T3 | Arm and disarm | ARM → DISARM | Notification appears, then disappears; visualizer resumes |
| T4 | Core match | ARM → lock → wait 3 s → clap ×3 | Alarm plays; "Here I am" notification |
| T5 | Too few | Clap ×2 | No alarm |
| T6 | Too many | Clap ×4 | No alarm |
| T7 | Distance | Claps at 0.5, 1, 2, 4 m from the phone on a table | Record the furthest reliable distance |
| T8 | Containment | Phone in pocket, bag, under pillow, face-down | Record success rate out of 10 for each |
| T9 | Noise | TV talk, music, running tap, keyboard typing, dishes, door slam | No false alarms in 10 min each; record any |
| T10 | Reverb | Tiled bathroom, empty room, carpeted room | Record success rate |
| T11 | Silence the alarm | After T4, unlock | Alarm stops; alarm volume restored to previous value |
| T12 | Stop action | After T4, tap **Stop** without unlocking | Alarm stops; listening resumes while the screen stays off |
| T13 | Silent / vibrate / DND | Repeat T4 in each mode | Alarm audible in all three |
| T14 | Chosen sound | System tone; local file; then delete the file | Plays each; falls back to default alarm after deletion |
| T15 | Headphones | Repeat T4 with wired or Bluetooth headphones connected | Note output route and level (KNOWN_ISSUES #12) |
| T16 | Screen on | Arm; screen on | Microphone indicator off; no listening |
| T17 | Battery | Armed, screen off, 8 h overnight, 2 devices | Record %/h; target under 3 %/h |
| T18 | Reboot | Arm → reboot | Not listening; notification gone (by design). Re-arm works |
| T19 | Battery optimisation | Repeat T4 after 1 h idle, default vs Unrestricted | Record difference |
| T20 | Call interruption | Arm → receive a call → end | Record whether listening recovers (KNOWN_ISSUES #8) |
| T21 | Permission revoked while armed | Revoke microphone in Settings | Record behaviour (expected: service dies) |
| T22 | Visualizer lifecycle | Open app disarmed → Home → check microphone indicator and `adb shell dumpsys media.audio_flinger` | Microphone should be released (KNOWN_ISSUES #5) |
| T23 | Upgrade | Install previous release → set options → install new | Settings preserved |
| T24 | TalkBack | Navigate all controls | Every control has a spoken label (KNOWN_ISSUES #13) |
| T25 | Sensitivity | Sweep 0 → 1 at T7 distances | Monotonic: higher hears more |
| T26 | Custom sound (known failing) | Record a whistle → re-whistle | Expected to fail until KNOWN_ISSUES #1 is fixed; log scores |
| T27 | Lock-screen integrity | With a PIN/pattern/biometric set: arm → screen off → screen on, repeat 20×; also after an alarm and after **Stop**; then repeat with the app force-stopped | Lock screen appears every time, identically in both conditions (KNOWN_ISSUES R1) |

## Result log

~~~
Device:            Android:        Build:
Date:              Tester:
T4  pass/fail  notes
T7  furthest reliable distance:
...
~~~

## Release gate

- T1–T6, T11–T13, T16, T27 pass on every device in the matrix.
- T9: no false alarms.
- T17 meets the target or the user-facing battery note is updated.
- T22 passes.
