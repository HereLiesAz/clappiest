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
| T28 | Clap to silence | Trigger the alarm (T4); during a quiet gap clap twice | Alarm stops and does not resume; notification gone; listening resumes while the screen is off |
| T29 | Silence reliability | T28 ×20 at 0.5, 1, 2 m, sensitivity 0.5 | Record success rate and the number of gaps needed |
| T30 | Not heard while sounding | Clap twice while the alarm is sounding | No effect (by design) |
| T31 | Gap sizing | Alarm in another room, then walk to it | Record whether 3 s of quiet is enough to react and clap; adjust constants |
| T32 | Toggle off | Turn off "Clap twice to silence the alarm" → T4 | Unbroken alarm; no gaps; unlock and **Stop** work |
| T33 | Alarm pulse vs screen | Trigger the alarm; turn the screen on with the power button | Alarm keeps pulsing; clapping still silences it |
| T34 | Single clap or noise | During a gap: one clap; speech; a door slam | Alarm not silenced by a single clap or speech (record door-slam outcome) |
| T35 | Echo cancellation verdict | Trigger the alarm on each device; log which mode it reaches (add a temporary log line for `dismissMode`) | Record CONTINUOUS or WINDOWS per device and source |
| T36 | Clap over a sounding alarm | On a CONTINUOUS device: clap twice while the alarm sounds, 0.5 / 1 / 2 m | Record success rate; compare with clapping in the gap |
| T37 | Replay gap | Alarm with a short tone, then a 30 s file, then a 4 min song | Gap of about 1 s after each replay; claps in the gap silence it |
| T38 | Percussive alarm | Use a drum or click-heavy file at sensitivity 0.5 and 1.0, no claps | Alarm not silenced for 2 min; record any false stops |
| T39 | Alarm loudness | Compare the alarm's loudness with and without the dismiss microphone (sound-level meter or by ear) | No audible drop from opening the microphone as `VOICE_COMMUNICATION` |
| T40 | Update popup | Install an older build from Google Play (internal testing); publish a newer one; open the app | Popup "A new version is available on Google Play"; **Update** opens the Play listing |
| T41 | Later | Tap **Later**; reopen; wait 3 days (or edit `snoozedUntil`) | No popup meanwhile; popup again after; a newer version asks at once |
| T42 | Switch off | Turn off "Check for updates"; open the app | No popup, no Play call |
| T43 | Not installed by Play | Sideload the GitHub APK; open the app | No popup, no crash, no delay opening the app |
| T44 | Throttle | Open the app 5 times within 12 hours | Play asked once |
| T45 | No internet permission | `aapt2 dump permissions` on the release APK | `INTERNET` absent |
| T46 | Real clap pass rate | 30 flat-handed claps each at 0.5, 1, 2 m, phone on a table, one at a time (1 s apart) | Set a 2-clap code and clap in pairs, 15 pairs per distance; each pair registers only if both claps pass, so pair success = (per-clap rate)². Target ≥ 81% of pairs (90% per clap) |
| T47 | Cupped and soft claps | Repeat T46 with cupped hands, fingers on palm, and light claps | Record the pass rate; expect cupped claps to be lower-pitched. If low, relax `ClapShape` |
| T48 | Code success | 20 attempts each of a 2-, 3-, 4-clap code at a natural rhythm | Target ≥ 85% success; record failures and whether the claps sounded different |
| T49 | Thumps and knocks | Door slam, table knock, knuckle on phone case, stamping, dropping a book; 20 each, 3 in a row | No alarm |
| T50 | Voices and tones | Shouting "hey" 3 times, a dog bark recording, doorbell, alarm beeps, whistle | No alarm |
| T51 | Sharp sounds (known weak) | Typing, mouse clicks, can opening, cutlery on a plate, light switch | Record false matches over 10 min each; expect some (KNOWN_ISSUES) |
| T52 | Mixed code | Clap, clap, then a door knock as the third | No match for a 3-clap code (the knock is ignored); a 2-clap code matches |
| T53 | Steady noise | Run a fan, television or tap at normal volume next to the phone; arm; wait 30 s; clap the code | Alarm sounds. Repeat with the room silent for a minute first, then the noise switched on |
| T54 | Microphone taken | Arm; screen off; start a voice recorder or call from another device's perspective (or toggle the microphone privacy switch); free it | Notification reads "Can't reach the microphone" and listening resumes within about 10 s of release; no crash |
| T55 | No alarm sound | Choose a file, delete it from storage, trigger the alarm | The default alarm plays; if the phone has none, the alarm volume is not left at maximum and Stop works |
| T56 | Hours rule | Set "Never during these hours" for a window starting 3 minutes from now; arm; screen off | Listens now; within a few minutes of the start the notification reads "Not listening / Resting: scheduled off"; clapping does nothing; after the end it listens again |
| T57 | Overnight window | Window 22:00 to 06:00, set the phone clock inside it | Paused; outside it listening |
| T58 | Charging | "Not while charging"; arm; screen off; plug in, then unplug | Pauses on plug-in, resumes on unplug, each within a few seconds |
| T59 | Battery floor | Set a level just above the current charge | Paused with "battery below N%" |
| T60 | Headphones | "Not while headphones are connected"; connect wired, then Bluetooth earbuds | Pauses on each; resumes when removed |
| T61 | On me, walking | "Not while it seems to be on me"; arm; screen off; walk with the phone in a pocket | Notification becomes "Resting: on you" within about 10 s of walking; clapping does nothing |
| T62 | On me, table | Put the phone face-up on a table, screen off | Within about 45 s of stillness it listens again; clapping triggers |
| T63 | On me, still pocket | Sit still for 5 minutes with the phone in a trouser pocket | Record whether it stays "on you" (expected: yes, from the body's tremor); repeat sitting very still, and with the phone in a bag |
| T71 | On me, couch cushions | Drop the phone between sofa cushions, screen off; nobody sits on the sofa | "Resting: on you" for about 90 s at most, then it listens; clap it. Repeat while sitting on the cushion and record what it does |
| T64 | On me, battery | Rule on; 1 h with the screen off, phone on a table | Record battery drop against the same hour with the rule off |
| T65 | Place | Save "Work" while there; set "Not at these places"; arm; screen off; stay, then leave 1 km | Paused at the place (within about 5 minutes of the screen going off); listens after leaving |
| T66 | Location access | With location denied, add a place rule | The line says it can't be checked; the app keeps listening; "Allow location" shows the disclosure, then the system prompt; granting "while using" then shows the settings step |
| T67 | Wi-Fi | Save the home network; "Not on these networks"; arm; screen off; connect and disconnect | Pauses on that network, resumes off it |
| T68 | Alarm is never cut | Trigger the alarm, then change a rule that would pause it | The alarm keeps sounding; after silencing, the rule applies |
| T69 | Rules cleared | Turn every rule off | Listening returns whenever the screen is off |
| T70 | TalkBack | Walk the "When to listen" screen with TalkBack | Each choice announces its label and selected state; day buttons announce On/Off |
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
