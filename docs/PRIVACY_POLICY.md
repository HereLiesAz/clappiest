# Privacy Policy

**The Clappiest** (`com.hereliesaz.clappiest`)
Developer: HereLiesAz
Effective: 2026-10-24

## Summary

- The app listens through your microphone to hear your clap code. It analyses the sound in memory and discards it.
- Optional "when to listen" rules can pause listening by time, charging, battery, headphones, whether the phone seems to be on you, a saved place, or a saved Wi-Fi network. They are all off until you turn them on. Location is used only by the place and Wi-Fi rules, only on your phone, and is never stored or sent.
- It has **no internet permission**. It cannot send anything off your phone.
- When you open the app it asks the Google Play Store app on your phone whether a newer version exists. That question is made by Google Play, not by this app. You can turn it off.
- No account, no ads, no analytics, no tracking, no third-party code that collects data.
- The developer collects nothing and holds nothing about you.

## What the app does with your microphone

The microphone is used in three situations, and only in these:

1. **Armed, screen off:** after you tap **ARM**, whenever the screen is off (and your optional "When to listen" rules allow it), to detect the number of claps you set. It is released when the screen turns on.
2. **While the alarm sounds:** in short pauses, to hear two claps so you can silence it. You can turn this off in the app.
3. **App open and disarmed:** the main screen listens to draw the live test waveform, only while the app is on screen and the microphone is allowed.

Audio is read in short pieces, measured, and discarded straight away. It is never written to storage and never transmitted.

Android shows a notification, and on recent versions a microphone indicator, whenever the app listens.

## What the app stores on your phone

Stored only in the app's private storage:

| Data | Why |
|---|---|
| Clap count, sensitivity, trigger mode | Remember your settings |
| Alarm sound choice (a reference to a system sound or a file you picked) | Play your chosen sound |
| "When to listen" rules you set: hours and days, charging, battery and headphones choices, and, if you add them, the **names and coordinates of places you saved** and the **names of Wi-Fi networks you saved** | Decide when to listen |

The app saves no audio. (An earlier version could save a 1.5-second custom sound recording; this version deletes any such recording when it is opened.) Chosen alarm files are referenced, not copied.

## Backups

The app opts out of Android's Auto Backup, so its settings, including any places and Wi-Fi names you saved, are not copied to your Google Account or to a new phone. (Earlier versions did not opt out, so a backup made by an older version may exist in your Google Account; delete it under Google One / Backup settings.)

## Update check

When you open the app, at most once every 12 hours, it asks the Google Play Store app on your phone whether Google Play has a newer version for your device. The Play Store app answers it, talking to Google as it does for any app. This app sends nothing itself; it has no internet permission. Google handles the exchange under [Google's privacy policy](https://policies.google.com/privacy). The developer receives nothing.

The check works only for copies that Google Play installed. For any other copy it fails quietly and you see nothing.

If a newer version exists, the app shows a popup. **Update** opens the app's Google Play page. **Later** hides that version for three days. The app stores the time of the last check, and which version you tapped **Later** on and when. It never downloads or installs anything itself. Turn the check off with **Check for updates** on the main screen.

## When to listen rules

You can ask the app to listen only in some circumstances. Each rule is optional and off by default.

- **Hours, charging, battery, headphones:** read from Android on the phone.
- **On me:** the app reads the accelerometer and proximity sensors while the screen is off, to guess whether the phone is in a hand or pocket. The readings are measured as they arrive and discarded; nothing is stored or sent.
- **Places and Wi-Fi:** if you add one, Android asks for permission to use your location. The app then uses your location, including while the app is closed or the screen is off, only to compare it with the places you saved, and reads the name of the Wi-Fi network you are connected to only to compare it with the networks you saved. The current position is held in memory and is not stored, logged, shared or transmitted. The places and names you saved stay on your phone. You can refuse location; the rest of the app and its other rules work without it.

A rule the app cannot check (permission refused, no sensor) never stops it listening.

## What the app does not do

- No network access, so no uploading, syncing or remote logging.
- No location use unless you add a place or Wi-Fi rule; no contacts, camera, calls, messages, or advertising ID.
- No analytics, crash-reporting or advertising libraries.
- No sale, sharing or disclosure of data. There is none to share.

## Permissions

| Permission | Used for |
|---|---|
| Microphone | Hearing your clap code |
| Notifications | The required "listening" notice and the alarm's Stop button |
| Foreground service (microphone) and wake lock | Listening reliably with the screen off |
| Location (optional), Wi-Fi and network state | Place and Wi-Fi rules only |

Details: [PERMISSIONS.md](PERMISSIONS.md).

## Google Play

The app is distributed through Google Play. Google processes install and device information, and answers the update check, under its own privacy policy. The developer receives from Play Console only aggregated statistics and the crash and not-responding reports that Google collects from users who have chosen to share them (Android vitals). Those reports describe a crash (stack trace, device model, Android version), not your activity, and contain no audio.

## Retention and deletion

- Settings remain until you clear the app's storage or uninstall it.
- To remove them: Android Settings → Apps → The Clappiest → Storage → **Clear storage**, or uninstall.
- Because the developer holds no data about you, there is nothing to request, correct or delete from the developer.

## Your rights

Wherever privacy law (such as the GDPR, UK GDPR or CCPA/CPRA) gives you rights of access, correction, deletion, portability or objection, they have nothing to attach to here: the developer does not collect or hold personal data. The developer does not sell or share personal information and does not use it for profiling.

## Children

The app is not directed at children under 13 and does not knowingly collect data from anyone.

## Security

Data stays in Android's per-app sandbox. Nothing leaves the device, so there is no transmission to intercept.

## Changes

If this policy changes, the new version will be published at the same address with a new effective date. If a future version adds any data collection, the app will ask first and this policy will say so before that version ships.

## Contact

Questions: open an issue at https://github.com/HereLiesAz/clappiest/issues
Email: **[CONTACT EMAIL: required by Google Play; add before publishing]**
