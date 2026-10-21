# Event head on the phone (dsp, package `ch.lessnoise.dsp.head` + `classify/EventHead.kt`)

The event head is a small multi-label classifier (a 2-layer perceptron, 68 inputs → 64 hidden
units → 9 classes) trained in the Labor analysis (`/home/claude/labor/step3/train`, model
`head_F1_MLP_nosit`: feature set F1 without the situation features). It works on numbers computed
from each event — levels, envelope shape, envelope periodicity and spectral shape — not on audio.
On the phone it runs **in the Labor editions only and for logging only**: its probabilities and
decision go into the Labor manifest's `event` and `clip` lines; the category the app shows,
stores and uploads stays the YAMNet decision, and the public editions do not run it
(`EditionEventHead.create()` returns null there).

## Pieces

| class | role |
|---|---|
| `classify/EventHead.kt` `EventHeadClassifier` | interface next to `SoundClassifier`: `predict(inputs)`, `decide(scores)`, `evaluate(map by name)` |
| `classify/EventHead.kt` `EventHead` | loads the JSON (`resources/ch/lessnoise/dsp/head/head_F1_MLP_nosit.json`), NaN → training median, z-score, ReLU layer, sigmoid layer, `enabled` flags and thresholds |
| `head/TickSpectrum.kt` `TickSpectrum` | per 125 ms tick: 4096-point periodic-Hann FFT of the 16 kHz signal centred on the tick, and its summary |
| `head/TickSpectrum.kt` `HeadTickStore` | per-tick ring (same numbering as `EventFeatureTracker`): five 25 ms A-weighted mean squares + the spectrum summary |
| `head/TickSpectrum.kt` `TonalAccumulator` | event-mean 40–800 Hz spectrum, fed tick by tick |
| `head/HeadFeatures.kt` `HeadFeatureExtractor` | the per-event features (port of `step3/features.py`) |
| `head/HeadFeatures.kt` `HeadInputs.f1` | the model inputs (port of `step3/train/dataset.py` `f1_row`) |
| `MeasurementEngine(…, eventHead = …)` | records the per-tick values, computes the head once per event, puts the result into `EventFeatures.head` and `DetectedEvent.headScores/headDecision` |

The result (`EventHeadResult`: model name, `scores` class → probability, `decision`, `inputs`)
reaches the listener with `onEventClosed`/`onEventEmitted`; `NoiseEvent.headScores` and
`.headDecision` read it. Room stores `EventFeatures` field by field and ignores `head`, the upload
reads Room only: nothing of the head is stored in the measurement database or sent.

## Inputs (68, in the JSON's order)

| inputs | where they come from on the phone |
|---|---|
| `excessDb`, `log10_riseS` (+0.05 s), `riseS_missing`, `log10_decayS` (+0.05 s), `decayS_missing`, `jaggedness`, `midBandRiseDb`, `lfShare`, `lfFlutterDb`, `wind_rule`, `shape_long/impulse/jagged/hump`, `localFloorDb` | the live detector's `EventFeatures` (unchanged; the training used the app's own values from the manifest) |
| `lafMaxDb`, `backgroundDb` | the event's LAFmax and 5-min background (`DetectedEvent`), as in the manifest |
| `onset_mid_rise_0p5s_db` | max over i ∈ [start − 8, end) of L_mid(i+4) − L_mid(i), L_mid = per-tick level of the A-weighted 250 Hz–4.5 kHz band (the tracker's band energy) |
| `peak_excess_db` | curve peak (max 125 ms LAF in the event) − pre-roll floor (10th percentile of the 40 valid LAF ticks before the start; if none, of the valid ticks after the end) |
| `log10_dur_above6_s` (+0.125 s) | ticks ≥ pre-roll floor + 6 dB from the start to the Python tail end (10 % level, new start at floor + 6.5, invalid audio, or 5 s) |
| `env_d2_rms_db` | RMS second difference of the LAF tick curve over the event |
| `peak_pos_rel` | (peak tick − start)·0.125 s / duration, ≤ 1 |
| `per125_r` | autocorrelation peak (lags 0.25–1 s, parabolic refinement) of the LAF ticks minus an 11-tick moving average, over the event (events < 2 s: symmetric 2 s window) |
| `per50_r`, `per50_hz`, `per50_hz_missing`, `mod_ratio_db`, `mod_peak_hz` | same on the 50 ms envelope (2 × 25 ms sub-blocks of A-weighted energy, 25-frame moving average); modulation spectrum (linear detrend, symmetric Hann, FFT ≥ 1024 at 20 Hz): 1–4 Hz vs 0.2–1 Hz energy, peak 1–4 Hz |
| `log2_centroid_peak_hz`, `log2_centroid_event_hz`, `log2_centroid_pre_hz`, `centroid_slope_oct_per_s` | spectral centroid 50–7500 Hz at the peak tick, event mean, pre-roll mean, least-squares slope of log2 over the event |
| `rise_1k_4k_db`, `rise_250_1k_db`, `hf_minus_mid_rise_db`, `hf_mid_ratio_slope_db_per_s` | 1–4 kHz and 250–1000 Hz power in the loudest 1 s (same 8 ticks as `midBandRiseDb`) vs the pre-roll; their difference; slope of 10·log10(P1–4k/P250–1k) over the event |
| `flatness_event`, `flatness_peak`, `flatness_pre` | spectral flatness 50–7500 Hz per tick: event mean, peak tick, pre-roll mean |
| `tonal_prom_db`, `log2_tonal_f_hz`, `tonal_prom_tick_median_db` | event-mean spectrum 40–800 Hz in dB minus its 15-bin running median, max in 80–600 Hz and its frequency; the same per tick, median over the event |
| `lf_share_fft` | power 20–200 Hz / 20–7500 Hz over the event (FFT version of `lfShare`) |
| `tob_25_rel_db` … `tob_6300_rel_db` (25) | 1/3-octave band power (bins fc·2^±1/6), event mean / pre-roll mean |

"Pre-roll" = the valid ticks of the 5 s (40 ticks) before the event start, which the tracker
holds anyway; spectral sets use valid ticks only, except the loudest-1-s span, which (as in the
Python) does not exclude invalid ticks.

## Deviations from the Python (`step3/features.py` on Labor clips)

1. **Signal path.** The Python computes LAF, band energies and the 50 ms envelope from the 16 kHz
   clip; the phone from the 48 kHz input (A-weighting designed at 48 kHz). The 16 kHz A-filter
   differs above ≈ 4 kHz, which moves broadband and impulsive envelopes and white-noise floors a
   little (`HeadEngineTest`, synthetic signals: `peak_excess_db` ≤ 0.6 dB, `env_d2_rms_db`
   ≤ 1.2 dB and `mod_ratio_db` ≤ 1.1 dB for clicks, centroid slope ≤ 0.07 oct/s). The spectra are
   the same on both sides: the phone reads the decimator output that the Labor clips are made of
   (frame centre shifted by the decimator's 40-sample group delay so it matches the tick's audio).
2. **Clip edges.** The Python pads the spectrum frames of the first and last tick of a clip by
   reflection; the phone has the real audio around every tick (so only a clip's first pre-roll
   tick, which enters the pre-roll means, can differ).
3. **Ticks after the end.** The Python reads up to 1 s after the event (periodicity window of
   events < 2 s, onset rise to end + 3). The engine therefore holds a finished event until
   `tracker.count ≥ max(end + 4, window end)` (≤ 8 ticks, 1 s, after the end; only with a head)
   before reporting it. When the measurement stops first, the window is clamped to the ticks
   there are (as the Python does at a clip end) and the last tick may have no spectrum.
4. **Tail for `dur_above6_s`.** The Python's tail rule is evaluated on the ticks available when the
   head runs (≥ end + 4); where the Python's tail would run longer (up to 5 s), later ticks above
   floor + 6 dB are not counted.
5. **Floors.** As in the Python: the detector inputs (`localFloorDb`, `excessDb`, …) use the
   detector's 30 s local floor; `peak_excess_db`/`dur_above6_s` use the 5 s pre-roll floor.
6. **Replay artefact (not on a phone).** Replaying a 16 kHz WAV upsamples it ×3 and the engine
   decimates it again: the 7–7.5 kHz bins pass the anti-alias FIR twice, so the flatness
   (50–7500 Hz) reads ≈ 0.01 lower than on the WAV.

## Verification

- `EventHeadGoldenTest`: 32 synthetic 16 kHz clips (`src/test/resources/eventhead/`, 5.1 MB,
  written with their expected values by `android/tools/event_head_golden.py`, which calls the
  Labor Python itself). `ClipHarness` runs the Python's tick pipeline at 16 kHz into the
  production classes. Every ported feature (the 8 detector-v2 ones and the 58 head features, among them every raw quantity behind the 68 inputs) matches
  within 1e-6 (relative for values > 1; observed ≤ 1.3e-12), the 68 inputs within 1e-6, the head
  probabilities within 1e-4 (observed 3e-14); peak tick, loudest span and tail end are equal.
- `HeadEngineTest`: the head inside the engine vs the harness on the same audio and span
  (deviations 1 and 6 above, with the stated tolerances); detection, categories and features are
  identical with and without a head; the head waits ≤ 8 ticks.
- `EventHeadTest`: JSON format, NaN imputation, enabled flags/thresholds, swapping the JSON.
- Real clips: `./gradlew :dsp:replayBatch --args="… --offset manifest"` writes per clip the
  engine's `head_<class>` and the port on the manifest span `span_<class>`; `replayNight` writes
  `head_<class>` per event; `android/tools/event_head_agreement.py` compares them with the
  training pipeline (results in the repository WORKLOG, branch event-head).

## Cost per tick

- 25 ms sub-block sums: 1 addition per input sample.
- One 4096-point complex FFT + summary (≈ 1 900 logarithms, a 15-point median over 194 bins):
  130 µs per tick on the development JVM (x86), i.e. ≈ 0.1 % of one core; a phone core is
  perhaps 3–5× slower.
- The 16 kHz decimator already runs for the classifier; with the classifier switched off it runs
  for the head (322 µs per tick on the same JVM).
- Memory: per-tick ring of ≈ 36 doubles × ≈ 2 600 ticks ≈ 0.75 MB; per event a 194-bin
  accumulator. No audio beyond the existing 1 s classifier ring.
- Per event: the features and the network (68·64 + 64·9 multiply-adds), negligible.

## Swapping in a retrained head

1. Export the new head in the same JSON format (`inputs`, `classes`, `median`, `mean`, `scale`,
   `W1`/`b1`/`W2`/`b2` as base64 little-endian float32, `enabled`, `thresholds`).
2. Replace `src/main/resources/ch/lessnoise/dsp/head/head_F1_MLP_nosit.json` (or add a file and
   point `EventHead.DEFAULT_RESOURCE` to it).
3. Inputs are matched by name. `HeadInputs.f1` produces the 68 F1 names above plus
   `log10_durationS`; a name it does not produce is fed as NaN, i.e. the training median —
   `EventHead.missingInputs(HeadInputs.f1(…).keys)` lists them (`EventHeadTest` checks the bundled
   head has none). The situation features (hour, motion, calibrated, time since start) are not
   produced. A different hidden size or class list needs no code change.
4. Regenerate the golden values: `python3 android/tools/event_head_golden.py --head <new json>`,
   then `./gradlew :dsp:test`.
