# Weniger Lärm for iPhone (in progress)

The iOS version of «Weniger Lärm», the **Online-Version** first (measuring, «Teilen» with the map
upload, «Laut! Melden», the tab «Karte»). Native Swift, in this repository next to `android/`,
`server/` and `docs/`. The app itself cannot be built yet (no Mac, no Apple developer account);
what exists is the platform-independent core and its proof:

- **`LessNoiseCore/`** — a Swift package (library `LessNoiseCore`, iOS 16+ / macOS 13+, also builds
  on Linux) with a line-by-line port of the measuring core of `android/dsp` and of the pure logic
  of `android/app` that the iOS app reuses.
- **Golden tests** — the Kotlin code writes reference results to `LessNoiseCore/Tests/Fixtures/`;
  the Swift tests recompute everything and compare. Bit-for-bit in practice: the largest level
  difference seen is 1.4·10⁻¹⁴ dB.
- **[DESIGN.md](DESIGN.md)** — the plan for the app layer (audio, background, location, upload,
  storage, map tab, classifier, privacy texts, signing).

## What is ported (`LessNoiseCore/Sources/LessNoiseCore/`)

| Swift | Kotlin original | What |
|---|---|---|
| `DSP/FrequencyWeighting.swift`, `TimeWeighting.swift`, `Acoustics.swift` | `dsp/…/FrequencyWeighting.kt`, `TimeWeighting.kt`, `Acoustics.kt` | A-weighting (3 bilinear biquads, effective high pole by the same grid search, any sample rate), Z, Fast/Slow, levels, default offset 112.35 dB |
| `DSP/MeasurementEngine.swift`, `Records.swift`, `Percentiles.swift`, `SilenceDetector.swift`, `Resampler.swift` | `MeasurementEngine.kt`, `Records.kt`, `Percentiles.kt`, `SilenceDetector.kt`, `Resampler.kt` | the whole pipeline: 125 ms ticks, LAF, LAeq,1s/1min, LAFmax/min, L1/L10/L50/L90, valid seconds, silence/mic-silenced guard, clock anchoring, minute records held back until their events are decided, classifier plumbing (decimator, 1 s ring) |
| `DSP/EventDetector.swift`, `EventFeatures.swift` | `EventDetector.kt`, `EventFeatures.kt` | detector v2: local floor (L90 of 30 s, quickselect), excess 6.5 dB, hysteresis, min duration, event floor, 300 s cap, tail, features (rise/decay, jaggedness, mid band, LF share, LF flutter), wind rule, shape |
| `DSP/Measurements.swift`, `Csv.swift` | `Measurements.kt`, `Csv.kt` | measurement summaries, splitting, the CSV export (byte-identical) |
| `Calibration/` | `calibration/Calibration.kt`, `Recalibration.kt` | calibration measurement, tone analysis, warnings, re-evaluation of stored data, the event floor following the calibration; iOS audio-source mapping |
| `Classify/CategoryMapper.swift` | `classify/CategoryMapper.kt`, `SoundClassifier.kt` | AudioSet → categories, classifier interface and input gain (not the model) |
| `Geo/` | `geo/Lv95.kt`, `geo/LocationNoise.kt` | WGS84↔LV95, hectare ids, location noise (HMAC-SHA256 via CryptoKit; swift-crypto on Linux) |
| `Upload/` | `app/…/upload/Payloads.kt`, `CellInput.kt`, `Reports.kt`, `Uploader.kt`, `UploadClient.kt`, `UploadWorker.kt` (LiveUploadPlan, UploadCadence), `location/LocationPolicy.kt` | request bodies (byte-identical), gzip, the uploader (same requests, batches, markers, budget), live upload plan with 429 back-off, coordinates input, the one-fix rules |
| `UI/` | `app/…/ui/ShareStatus.kt`, `ShareTexts.kt`, `MapLinks.kt`, `AppTabs.kt`, `karte/…/alert/LoudAlertRule.kt` | status line and map banner, all German texts of «Teilen», «Unterwegs» alert rule, map URL/fragment and host lock-down, tab back stack |
| `Support/` | — | JVM semantics the port needs (`JavaMath.round` half-up, `JavaFormat.fixed` = `String.format(Locale.ROOT,"%.nf")`, `Double.toString`), an ordered JSON writer that prints like kotlinx.serialization |

**Not ported:** the classifier model (YAMNet/LiteRT — phase 2 decides Core ML vs TensorFlow Lite,
see DESIGN.md), the Room database (iOS: SQLite, DESIGN.md), Compose UI, Android services and
WorkManager, the Labor build. Kotlin's `Long` is Swift's `Int` (64-bit on every supported platform).

## Build and test (Linux or macOS)

```sh
export PATH=/home/claude/swift/swift-6.1.2-RELEASE-ubuntu24.04/usr/bin:$PATH   # this machine; on a Mac: Xcode's toolchain
cd ios/LessNoiseCore
swift test
```

`swift test` runs 20 test functions (Swift Testing; 28 cases, the engine and replay tests are
parameterized): 16 golden tests and 4 unit tests, about 35 s in a debug build.

## Golden tests

The JVM implementation is the reference. Two Kotlin test classes run the production code on fixed
inputs and write what it computes:

- `android/dsp/src/test/kotlin/ch/lessnoise/dsp/golden/DspGoldenTest.kt` → `filters.json`
  (A-weighting at 48/44.1/32/16/96 kHz: effective pole, coefficients, gain, magnitudes, impulse and
  noise responses; Butterworth sections; feature filters; Fast/Slow; decimator; input gain; silence
  detector), `percentiles.json`, `engine.json` (7 synthetic cases: tone on noise with every tick and
  second; bursts incl. too short, across a minute, wind thump, 35 s long, below the floor, calibrated
  offset; exact zeros and a system-silenced mic, zone UTC; full-scale clipping across the DST change;
  a 1 % fast wall clock across new year; the classifier plumbing with deterministic scores; a 315 s
  event force-closed at 300 s with minutes held back), `replay.json` (the three replay WAVs of
  `dsp/src/test/resources/replay`, plus the committed expected CSVs are checked too), `features.json`,
  `calibration.json` (calibrator tone, noise, clipping, silence, invalidation, tone analysis, FFT,
  re-evaluation A→B→C and back, event floor, signed texts), `geo.json` (swisstopo/AGNES reference
  points of server/DESIGN.md §3, 44 Zürich points, cell ids), `location_noise.json` (4 secrets × 6
  points × 4 σ: HMAC seed, normals, offset, shifted point), `records.json` (ISO-8601 in 5 zones,
  summaries, splitter, CSV with quoting/originals, category decisions).
- `android/app/src/testPublic/kotlin/ch/lessnoise/app/golden/AppGoldenTest.kt` → `payloads.json`
  (site/minute/event/report bodies, rounding, gzip header/CRC), `upload_scenario.json` (the real
  Uploader against FakeServer in 23 steps: registration, site, reports, small batches with
  same-start rows, an unshared and a too-old measurement, refused records, point attached later,
  events-only run, 503, 429, site change, rewind, disable, delete — every request and body),
  `live_plan.json`, `share_status.json` (all 4 096 combinations), `ui_location.json` (alert rule,
  map URLs and host checks, re-centring steps, tab history, coordinates input, fix decisions),
  `texts.json` (every German text), `upload_rules.json`.

Signals are specs, not samples (SplitMix64 noise, sines as a rotation by stored cos/sin, bursts,
square waves, zeros), so both sides build bit-identical input without calling `sin()` per sample.

**Tolerances** (asserted; the observed maxima are printed by `swift test` as `[golden] … max |Δ|`):
levels (LAF, LAeq, percentiles, SEL, floors, features in dB) 1·10⁻⁶ dB — observed ≤ 1.4·10⁻¹⁴;
filter coefficients, impulse and per-sample outputs 1·10⁻¹² — observed ≤ 1.1·10⁻¹⁶; float samples
(decimator, upsampler, input gain) 1·10⁻⁷ — observed 0; LV95 metres 1·10⁻⁶ and degrees 1·10⁻¹¹
before rounding, exact after (cells, shifted points); HMAC seeds exact; event counts, sample
indices, epoch ms, ISO strings, categories, shapes, wind flags, CSV text and JSON bodies **exact**.

**Regenerate** after a change on the Android side (needs the Android SDK and a JDK):

```sh
ios/LessNoiseCore/regenerate-fixtures.sh   # sets LESSNOISE_GOLDEN_DIR and runs the two Gradle test classes
cd ios/LessNoiseCore && swift test
```

Without `LESSNOISE_GOLDEN_DIR` the generators still run with every `:dsp:test` and
`:app:testPublicDebugUnitTest` and write to `android/*/build/ios-golden/` (so they stay working).

## Next, on the Mac

See [DESIGN.md](DESIGN.md) «What needs the Mac»: generate the project with XcodeGen, run
`swift test` and the app's tests on macOS/iOS Simulator (CryptoKit and the Compression-framework
gzip path are only exercised there), then the device tests.
