# User guide

## What it does

Arm the app. When the screen is off it listens for your clap code. On a match it plays your chosen sound at full alarm volume until you unlock the phone or tap **Stop**.

It listens **only while the screen is off**. Turn the screen on and the microphone is released.

## Set up

1. Open the app.
2. **Claps**: choose 2 to 10. The count must match exactly: four claps do not answer a three-clap code.
3. **Sensitivity**: higher hears softer and more distant claps, and is easier to fool.
4. **Sound**: **System** picks a tone from your phone. **File** picks any audio file. The file is not copied; if you delete or move it, the default alarm plays instead.
5. Tap **ARM**. Allow the microphone and notifications when asked.

A notification stays visible while armed. Android requires it; it cannot be hidden.

## Test before you need it

While disarmed, the main screen shows a live waveform. Clap near the phone. The bars turn green when the sound is loud, and **RECOGNIZED** flashes when your full clap code is heard.

## Find your phone

1. Give it a couple of seconds after the screen turns off.
2. Clap your code, about a third of a second between claps.
3. The alarm plays. Unlock the phone or tap **Stop** to silence it. It then resumes listening when the screen next goes dark.

## Disarm

Open the app and tap **DISARM**.

## Keep it alive

Android and some manufacturers stop background apps to save battery. If it stops listening:

- **Re-arm after a restart.** Android does not allow a microphone service to start itself after boot.
- **Exempt it from battery optimisation.** Settings → Apps → The Clappiest → Battery → Unrestricted. Names vary by maker; on Xiaomi, Oppo, Vivo, Huawei, Samsung and OnePlus also enable "Autostart" or "Allow background activity" and lock the app in the recents list.
- **Do not force-stop it.** A force-stopped app stays dead until you open it.

## Volume and safety

The alarm sets the alarm volume to maximum and restores your setting afterwards. It plays on the alarm channel, so silent and vibrate modes do not mute it. If headphones are connected it may play through them at full volume. Do not leave headphones in your ears while it is armed.

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| Never triggers | Not armed; battery restriction; microphone busy; count mismatch | Check the notification is present; apply "Keep it alive"; test with the visualizer |
| Triggers on claps you did not make | Sensitivity too high | Lower it; raise the clap count |
| Misses claps | Sensitivity too low; muffled by pocket or bag; noisy room | Raise it; test at the distance you expect |
| Stops after a phone call | Another app had the microphone | Open the app and re-arm |
| "No microphone, no miracle." | Permission denied | Settings → Apps → The Clappiest → Permissions → Microphone |
| Silent alarm | Chosen file missing or unsupported | Pick a System sound; the default alarm is the fallback |

## Privacy

Audio is analysed in memory and discarded. The app has no internet permission. See [PRIVACY_POLICY.md](PRIVACY_POLICY.md).

## Custom sound mode

Experimental and unreliable in the current build. Use **Claps** until KNOWN_ISSUES #1 is fixed.
