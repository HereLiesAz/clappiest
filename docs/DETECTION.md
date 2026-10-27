# Detection

Both detectors are pure Kotlin, take 16-bit mono PCM at 16 kHz, and return `true` on a match.

## Clap detector (`ClapDetector`)

A clap is an impulse: sudden, loud against the background, and gone within ~200 ms.

### Per 10 ms frame

1. Compute `peak` (max |sample|) and `rms`.
2. **Onset** when all hold:
   - `peak ≥ minPeak`
   - `rms > noiseRms × onsetRatio`
   - `quiet` (the previous frames fell below `2 × noiseRms`; prevents a sustained sound from re-triggering mid-sound)
3. **Confirm** the clap when `rms` falls below `onsetRms × 0.4` within 200 ms of onset. If it lingers past 200 ms the sequence is abandoned (speech, music, rumble).
3a. **Verify the sound** ([below](#verifying-that-a-sound-is-a-clap)): a confirmed clap is measured once 32 ms of audio after its onset exists. If it is not clap-like it is ignored as if it had not happened.
4. **Background** `noiseRms` follows `rms` while no onset is pending, floored at 50: at 0.05 per frame when `rms < 3 × noiseRms`, and at 0.005 per frame (about 2 s to settle) when louder. A clap lasts tens of milliseconds, so it barely moves the floor; a fan, a television or traffic lasts seconds and is absorbed. Without the slow rise, steady noise above about 3.5 × the floor left `quiet` false for good and the detector deaf (fixed; see KNOWN_ISSUES).

### Sequences

- A confirmed clap less than 120 ms after the previous one is ignored as an echo.
- The sequence ends after 1200 ms with no further clap. It matches only if the count equals the target exactly **and the claps sound alike** (their spectral centroids differ by at most a factor of 1.8).
- A match resets the counter. The service ends its read loop on a match, so the detector is rebuilt when listening resumes.

### Sensitivity `s` (0..1)

| Quantity | Formula | s = 0 | s = 0.5 | s = 1 |
|---|---|---|---|---|
| `minPeak` (fraction of full scale) | `0.30 − 0.25·s` | 0.30 | 0.175 | 0.05 |
| `onsetRatio` | `6 − 4·s` | 6 | 4 | 2 |

### Constants

| Name | Value |
|---|---|
| `FRAME_MS` | 10 |
| `MIN_GAP_MS` | 120 |
| `MAX_GAP_MS` | 1200 |
| `MAX_CLAP_MS` | 200 |
| `DECAY_RATIO` | 0.4 |
| initial `noiseRms` | 300 |

### Finish-on-target mode

`ClapDetector(..., finishOnTarget = true)` fires the moment the target-th clap is confirmed, without the 1.2 s quiet period, and so also accepts a longer sequence. It silences the alarm (target 2). Every other rule above still applies, so a sustained sound or two claps more than 1.2 s apart do not fire it. After firing, the count resets.

### Fast floor (`fastFloor = true`)

The normal background floor follows loud frames only slowly (about 2 s to settle), so claps cannot raise it but it is too slow to hear a clap over a sound that has only just begun. With `fastFloor` the floor follows any level in both directions at 0.2 per 10 ms frame (about 50 ms to settle). A pending onset still freezes it, so a clap is judged against the steady sound beneath it. A sustained or slowly changing sound produces no onset that decays within 200 ms, so it is rejected as before. Unit tests show the fast floor hears two claps over a steady tone after 1 s, and the slow floor only after several seconds.

A percussive alarm sound (drum hits, short clicks) can still look like claps. Two such hits within 1.2 s would silence the alarm.

### Hearing a clap over the alarm

The alarm plays at maximum volume beside the microphone. A clap a metre away is far quieter than that, so the service does three things (ARCHITECTURE.md, "Alarm replay gap"):

1. Opens the microphone with the phone's echo canceller where available.
2. Pauses the alarm 1 s between replays and listens in the gap.
3. **Measures** whether cancellation worked (`EchoGuard`) and, if not, mutes the alarm for a window every 3.5 s.

`EchoGuard` compares microphone RMS while the alarm is audible with RMS while it is silent, needing 500 ms of each (after a 400 ms settle). A ratio at or below 2.5 means cancelled. The silent level is floored at 60 so a very quiet room cannot make the ratio infinite.

| Constant | Value | Meaning |
|---|---|---|
| `REPLAY_GAP_MS` | 1000 | Silence between replays |
| `ALARM_LEAD_MS` | 4000 | Alarm before the first forced window |
| `DISMISS_WINDOW_MS` | 3000 | Forced muted window |
| `ALARM_ON_MS` | 3500 | Alarm between forced windows |
| `WINDOW_SETTLE_MS` | 400 | Ignored after any sound/silence change |
| `EchoGuard` `minMs` / `maxRatio` | 500 / 2.5 | Evidence needed; cancelled threshold |

These are estimates, not measurements. Tune them on devices (TESTING.md T28–T38).

### Verifying that a sound is a clap

Loud-and-brief is not a clap. A door slam, a knock, a footstep, a bark and an alarm beep can be just as loud and brief. On 920 real recordings the timing-and-loudness test alone "heard claps" in nearly everything: keyboard typing (8 per 5-second clip), footsteps (92% of clips), can-opening (100%), door knocks (92%), laughter, dog barks.

A hand clap has a recognisable *shape*: broadband and noisy, energy from about 1 to 6 kHz, almost none below 400 Hz, and no single pitch. `ClapShape` measures a 32 ms window starting 5 ms before the onset (512-point FFT, Hann window):

| Measure | Clap | Rejected when |
|---|---|---|
| Spectral centroid | ~1.7 kHz in real claps; ~4 kHz for white noise | below 900 Hz (knocks, footsteps, slams, thumps) |
| Share of energy below 400 Hz | ~2% | above 20% |
| Flatness 1–7 kHz (1 = noise, 0 = tone) | ~0.20 | below 0.02 (barks, voices, sirens, alarms, beeps) |

The limits are deliberately loose. A clap code needs **every** clap to pass: with a per-clap pass rate *p*, a code of *n* claps is accepted with probability *pⁿ*. So the limits were set to keep about 95% of claps, not to reject as much as possible.

**The claps of one code must also sound alike.** If the brightest and the dullest clap in a sequence differ in centroid by more than 1.8×, it is not a deliberate code.

The alarm-silencing detector (`verifyShape = false`) skips both checks: over a sounding alarm's residue a clap's spectrum is mixed with the alarm's and cannot be judged.

#### Evidence

The real `ClapDetector` run over 920 five-second recordings from [ESC-50](https://github.com/karolpiczak/ESC-50) (40 per class; CC BY-NC 3.0, used for offline analysis only and not distributed). Applause stands in for claps; 22 other classes (knocks, footsteps, typing, clicks, voices, animals, alarms and more) are the things to reject. Sensitivity 0.5.

| | Shape check off | Shape check on |
|---|---|---|
| Applause claps accepted | 206 | 196 (**95% kept**) |
| Events accepted from the 22 other classes | 2,481 | 1,048 (58% removed) |
| False matches per hour, 2-clap code | 82.6 | 42.5 |
| False matches per hour, **3-clap code** | **56.5** | **15.5** (−72%) |
| False matches per hour, 4-clap code | 35.2 | 9.8 |
| False matches per hour, 5-clap code | 27.0 | 11.5 |

Events removed per class: door knocks 100%, sirens 100%, dogs 95%, coughs 86%, alarm clocks 85%, footsteps 78%, laughter 70%, rooster 79%. Barely changed: **can opening 6%, clock ticking 12%, glass breaking 16%, mouse clicks 40%, keyboard typing 30%.** Those are sharp broadband sounds; nothing about a single one separates it from a clap, and duration does not either (real claps ring about as long as clicks do).

The false-match rates are per hour of *that sound playing continuously*, so they overstate everyday life; they are useful as a comparison, not a forecast. Longer codes are safer: prefer 3 or more claps.

**Caveats.** ESC-50 is not a phone microphone and applause is not a deliberate clap code; the 95% is measured on real applause onsets, not on single claps near a phone. T46–T52 in [TESTING.md](TESTING.md) measure it properly, including cupped-hand claps, which are lower-pitched and may fail the limits.

### Tuning

| Problem | Adjust |
|---|---|
| Misses distant claps | Lower `minPeak`, lower `onsetRatio` |
| Fires on door slams | Lower `DECAY_RATIO`, lower `MAX_CLAP_MS` |
| Misses claps in reverberant rooms | Raise `MAX_CLAP_MS`, `DECAY_RATIO` |
| Merges rapid claps | Lower `MIN_GAP_MS` |
| Real claps are ignored | Lower `ClapShape.MIN_CENTROID_HZ`, raise `MAX_LOW_SHARE`, lower `MIN_FLATNESS`, or raise `SIMILAR_RATIO` |
| Knocks, taps or voices count as claps | Raise `MIN_CENTROID_HZ`, lower `MAX_LOW_SHARE`, raise `MIN_FLATNESS` |

Changes must keep `ClapDetectorTest` green and add a case for the new behaviour.

## Custom-sound detector (`CustomSoundDetector`)

Normalised cross-correlation of the raw waveform between a stored 1.5 s template and the most recent 1.5 s of audio, evaluated every 10 ms:

~~~
corr = Σ (x − x̄)(r − r̄) / (‖x − x̄‖ · ‖r − r̄‖)
match when corr ≥ 0.55 + clamp(0.3·sensitivity, 0, 0.35)
~~~

**Known defect.** Raw-waveform correlation is sensitive to phase and to pitch changes of a fraction of a percent, neither of which a human whistle can hold. In simulation a re-whistled template correlates above the 0.70 default threshold in 0% of trials at 0.5% pitch variation. The threshold also rises with sensitivity, the reverse of clap mode. Details, evidence and the proposed replacement are in [KNOWN_ISSUES.md](KNOWN_ISSUES.md) #1–#2.

### Proposed replacement

Match spectrogram shape, which is phase-invariant:

1. Frame at 20 ms (512-point FFT, 320 hop, Hann window).
2. Sum magnitudes into 32 bands, take `log(1 + m)`.
3. Z-score the template and each 1.5 s window of frames; match on mean product ≥ 0.6–0.8.
4. Trim the template to its onset; record 2–3 takes and keep the most self-consistent.

In the same simulation this matched 97–100% of re-whistles at ≤2% pitch variation, with 0% false positives on loud broadband noise and on whistles 20–40% away in pitch.
