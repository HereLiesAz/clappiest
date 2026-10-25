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
4. **Background** `noiseRms` follows `rms` slowly (factor 0.05 per frame), only while no onset is pending and `rms < 3 × noiseRms`, floored at 50.

### Sequences

- A confirmed clap less than 120 ms after the previous one is ignored as an echo.
- The sequence ends after 1200 ms with no further clap. It matches only if the count equals the target exactly.
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

### Hearing a clap over the alarm

The alarm plays at maximum volume beside the microphone. A clap made a metre away is far quieter than that, and the adaptive background floor would rise to the alarm's level, so claps would never register. The service therefore mutes the alarm for a window, discards the first 400 ms, and listens only inside it; see ARCHITECTURE.md ("Alarm pulse").

| Constant | Value | Meaning |
|---|---|---|
| `ALARM_LEAD_MS` | 4000 | Unbroken alarm before the first window |
| `DISMISS_WINDOW_MS` | 3000 | Muted listening window |
| `ALARM_ON_MS` | 3500 | Alarm between windows |
| `WINDOW_SETTLE_MS` | 400 | Samples discarded at the start of a window |

These are estimates, not measurements. Tune them on devices (TESTING.md T28–T31).

### Tuning

| Problem | Adjust |
|---|---|
| Misses distant claps | Lower `minPeak`, lower `onsetRatio` |
| Fires on door slams | Lower `DECAY_RATIO`, lower `MAX_CLAP_MS` |
| Misses claps in reverberant rooms | Raise `MAX_CLAP_MS`, `DECAY_RATIO` |
| Merges rapid claps | Lower `MIN_GAP_MS` |

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
