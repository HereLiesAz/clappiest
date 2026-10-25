# Google Play listing

Everything needed to take *The Clappiest* from draft to published. Character limits are Play Console's; check them against the console before pasting, since limits change.

## Current Play state (2026-10-24)

`com.hereliesaz.clappiest` exists as a **draft app**. The central publisher uploads each build and stages it as a draft on `internal` and `alpha`. It skips `beta` and `production` because Play refuses them until the app's content declarations and store listing are complete. Completing this document's sections unblocks them; no pipeline change is needed.

## Checklist

| # | Item | Where in Play Console | Status |
|---|---|---|---|
| 1 | Privacy policy at a public URL | App content → Privacy policy | Text ready; **needs hosting** |
| 2 | Contact email | Store settings → Contact details | **Needs your address** |
| 3 | App access | App content | Ready (below) |
| 4 | Ads | App content | Ready: No |
| 5 | Content rating | App content | Ready (below) |
| 6 | Target audience | App content | Ready (below) |
| 7 | Data safety | App content | Ready; **verify the IP-address question first** (below) |
| 8 | Foreground service permissions | App content | Ready; **needs a demo video** |
| 9 | Government / financial / health / news declarations | App content | All No |
| 10 | Advertising ID | App content | No |
| 11 | Category and contact | Store settings | Ready |
| 12 | Store listing text | Main store listing | Ready |
| 13 | Graphics | Main store listing | Icon ready; **feature graphic and screenshots needed** |
| 14 | Testing requirement for production | Testing | See below |

## Listing

| Field | Value | Limit |
|---|---|---|
| App name | The Clappiest | 13 / 30 |
| Short description | Clap to find your phone. Works with the screen off. No account, no tracking. | 76 / 80 |
| Category | Tools | |
| Type | App | |
| Price | Free | |
| Default language | English (United States) | |
| Tags | Pick the closest offered: Tools, Utilities | per console |
| Website | https://github.com/HereLiesAz/clappiest | |
| Email | **[add]** | |
| Privacy policy URL | **[add]** | |

### Full description (en-US, 2,098 / 4,000)

~~~
Lost in the dark, found by applause.

The Clappiest finds your phone when you can't. Arm it, put the phone down, and lose it. Clap the number of times you chose and it plays the sound you chose, at full volume, until you pick it up.

HOW IT WORKS
• Choose how many claps (2 to 10), how sensitive it is, and which sound plays: a tone from your phone or any audio file.
• Tap ARM. A notification shows that it is listening.
• While the screen is off, the microphone listens for your exact clap count. Four claps will not answer a three-clap code.
• On a match the alarm loops on the alarm channel at full volume, so silent and vibrate modes do not mute it. Unlock the phone, tap Stop, or clap twice to silence it. The alarm pauses for a second between replays and, on phones that cannot filter it out of the microphone, for a few seconds at a time, so the app can hear you; clap twice during the quiet.
• Turn the screen on and it stops listening at once.
• Test it first. The main screen shows a live waveform and tells you when your clap code is recognized.

PRIVATE BY DESIGN
• Sound is analyzed on your phone, in memory, and thrown away. Nothing is recorded or saved.
• No account. No ads. No analytics.
• Your audio never leaves the phone. The only thing the app ever sends is a "newer version?" request to GitHub when you open it, which you can turn off.

GOOD TO KNOW
• Android requires a visible notification while an app listens in the background. The app shows one while armed.
• After a restart, or if your phone's battery saver stops the app, open it and tap ARM again. For best results, set the app's battery usage to Unrestricted.
• When a newer version is out, the app tells you and points you to Google Play or GitHub, wherever you installed it from. Google Play can take a little while to catch up.
• The alarm raises the alarm volume to maximum and restores it afterwards. Keep headphones out of your ears while it is armed.
• Loud rooms and muffled pockets can hide a clap. Use the live waveform to check how well it hears you.

Built by one person who has lost a phone in a couch.
~~~

Custom-sound mode is deliberately absent. Add this once KNOWN_ISSUES #1 is fixed and verified on devices:

~~~
CUSTOM SOUND
Prefer a whistle? Record it once and the app listens for that instead of claps.
~~~

### What's new, first release (en-US, ≤ 500)

~~~
First release. Clap a number of times you choose while the screen is off and your phone sounds an alarm you choose. Clap twice, unlock, or tap Stop to silence it. Live waveform to test your claps. Tells you when an update is out. No account, no ads, no tracking.
~~~

## Graphics

| Asset | Spec | State |
|---|---|---|
| App icon | 512 × 512 PNG, 32-bit, ≤ 1 MB | **Ready:** `app/src/main/ic_launcher-playstore.png` |
| Feature graphic | 1024 × 500 PNG or JPEG, no transparency | **Missing** |
| Phone screenshots | 2 to 8; 16:9 or 9:16; each side 320–3840 px; longest side ≤ 2× shortest | **Missing** |
| 7" / 10" tablet screenshots | Optional | Skip |
| Promo video | Optional YouTube URL | Skip |

The icon is dark navy with a red-and-cream hand and sound rings. Keep screenshots and the feature graphic in the same palette: near-black, one red accent, cream highlights, no stock imagery.

### Screenshots (capture on a real phone)

~~~
adb exec-out screencap -p > shot-01.png
~~~

| # | Screen | Caption (≤ 80 chars) |
|---|---|---|
| 1 | Main screen, disarmed, visualizer idle | Lost in the dark, found by applause. |
| 2 | Visualizer showing RECOGNIZED | Test your claps before you need them. |
| 3 | Settings: claps, sensitivity, sound | Your count. Your sound. |
| 4 | Armed screen with "Listening" notification in the shade | Armed. Screen off. Listening. |
| 5 | Lock screen with the "Here I am" notification | Found. |

Hide the status-bar clock and notifications unrelated to the app, or use demo mode (`adb shell settings put global sysui_demo_allowed 1`).

## Content rating (IARC)

Category: **Utility, Productivity, Communication, or Other**.

| Question | Answer |
|---|---|
| Violence, blood, fear | No |
| Sexual content or nudity | No |
| Profanity or crude humor | No |
| Controlled substances | No |
| Gambling or simulated gambling | No |
| User-generated content or user interaction | No |
| Shares user location | No |
| Allows purchases | No |
| Unrestricted web access | No |

Expected: Everyone (ESRB), PEGI 3, USK 0.

## Target audience

**18 and over.** The app has no child appeal; choosing only adult ranges keeps it outside Google's Families policy and its extra obligations.

## App access

All functionality is available without login or special setup. Reviewer note:

~~~
No account is needed. Open the app, tap ARM, and allow the microphone and notifications. Lock the phone, wait a few seconds, then clap three times with a short pause between claps. The alarm plays until the phone is unlocked or Stop is tapped in the notification.
~~~

## Data safety

Google defines "collect" as transmitting data off the device. Audio is processed ephemerally in memory and never transmitted. The one network request is the update check to `api.github.com` (HTTPS GET, at most every 12 hours, user can turn it off). It carries no account, advertising or device identifier, no audio and no settings; the server necessarily sees the phone's IP address and a User-Agent of the form `Clappiest/<version> (Android)`.

| Question | Suggested answer |
|---|---|
| Does your app collect or share any of the required user data types? | **No**, for the reasons above |
| Is all data encrypted in transit? | Yes (HTTPS only; cleartext is blocked) |
| Do you provide a way to request data deletion? | Not applicable (nothing is held) |
| Data types | None selected |

**Verify before submitting.** The form has no "IP address" type, and I have not confirmed how Google treats the incidental IP address of a request to a third-party API. Read the current Data safety help text ("Data collection and sharing"). If Google counts it, the conservative disclosure is to declare the data type Google maps it to, as collected, not shared, optional (the user can turn the check off), purpose "App functionality". Re-check this section whenever a dependency, permission or network feature is added.

**Policy fit.** The app only notifies and links. It never downloads, installs or replaces itself, so it does not conflict with Play's rule that apps distributed on Play update only through Play.

## Foreground service permissions

Declare type **Microphone** (`FOREGROUND_SERVICE_MICROPHONE`).

**Use case (paste):**

~~~
The Clappiest helps users find a lost phone. After the user taps ARM, a foreground service listens through the microphone while the screen is off for a clap pattern the user configured, then plays a user-chosen alarm. The service is started only by an explicit user action in the visible app, shows a persistent notification for as long as it runs, and stops when the user taps DISARM or turns the screen on. Audio is analyzed in memory in real time and is neither stored nor transmitted; audio is never transmitted. While the alarm sounds, the same service briefly listens for two claps so the user can silence it hands-free. Without a foreground microphone service Android would stop the microphone as soon as the screen turns off, which is the only moment the feature is needed.
~~~

**Demo video** (30 to 60 seconds, unlisted YouTube link, no narration needed):

1. Open the app; show the settings screen.
2. Tap ARM; grant the permission prompts.
3. Pull down the shade: show the persistent "Listening in the dark" notification.
4. Lock the phone.
5. Clap the configured count.
6. Show the alarm notification. Either clap twice during a quiet gap or unlock; the alarm stops.
7. Reopen the app and tap DISARM; show the notification disappear.

## Testing requirement

Personal developer accounts created after November 2023 must run a closed test with a minimum number of opted-in testers (12 at last check) for 14 continuous days before applying for production access. Verify the current rule in Play Console → Dashboard. The pipeline already stages every build on `alpha`, which can serve as the closed-testing track: add testers there once the listing is complete.

## Pre-launch hygiene

- Replace the launcher icon (KNOWN_ISSUES #6) so the installed icon matches the listing.
- Fix or hide custom-sound mode (KNOWN_ISSUES #1) before production.
- Verify clap mode on at least two physical devices from different manufacturers ([TESTING.md](TESTING.md)).
- Close stale central-run issues on GitHub (#3, #5) once Play releases are passing.
- Confirm `beta` and `production` tracks accept a release after the listing is complete; then decide whether open testing or a staged production rollout is wanted. The central profile publishes `beta` as completed and `production` as a draft with existing releases preserved.
