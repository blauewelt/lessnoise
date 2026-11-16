# Weniger Lärm for iPhone (first version, not released)

The iOS version of «Weniger Lärm», the **Online-Version** (measuring with the screen locked,
«Messungen», «Teilen» with the map upload, «Laut! Melden», «Unterwegs», the tab «Karte»,
calibration). Native Swift (SwiftUI, iOS 16+), in this repository next to `android/`, `server/`
and `docs/`. Three parts:

- **`LessNoiseCore/`** — a Swift package (library `LessNoiseCore`, iOS 16+ / macOS 13+, also builds
  on Linux) with a line-by-line port of the measuring core of `android/dsp` and of the pure logic
  of `android/app` that the iOS app reuses, proven by **golden tests**: the Kotlin code writes
  reference results to `LessNoiseCore/Tests/Fixtures/`, the Swift tests recompute everything and
  compare (largest level difference seen 1.4·10⁻¹⁴ dB).
- **`LessNoiseKit/`** — a second Swift package with everything of the app that needs no Apple UI or
  hardware framework, so that it is tested on Linux and macOS alike: storage (GRDB/SQLite, the same
  tables and columns as Android's Room database), the measurement session around the engine
  (interruptions recorded as invalid time), «Auf die Lärmkarte?» with the one location fix,
  «Laut! Melden», the live upload cadence, «Unterwegs», calibration, the map's lock-down rules,
  German formatting. 40 XCTest tests with fakes for the microphone, location, network and clock.
- **`App/`** (Xcode target `WenigerLaerm`, display name «Weniger Lärm») — the thin iOS adapters
  (AVAudioSession/AVAudioEngine, CoreLocation, URLSession, Keychain, UserNotifications,
  BackgroundTasks, WKWebView) and the SwiftUI screens; **`AppTests/`** tests what only runs on iOS
  (transport, stores, Info.plist, WebKit rules and lock-down, notifications, the 44.1 → 48 kHz
  resampler) on the simulator: 15 tests, of which the Keychain test is skipped in the unsigned CI
  build (no Keychain entitlement without signing).

**[DESIGN.md](DESIGN.md)** is the design of the app layer, with the list of what must change on the
server, the website and in PRIVACY.md before an iOS release.

## Build on a Mac

```sh
brew install xcodegen
cd ios
xcodegen                     # writes WenigerLaerm.xcodeproj and App/Info.plist from project.yml (not committed)
open WenigerLaerm.xcodeproj  # scheme «WenigerLaerm»; ⌘R on a simulator, ⌘U runs AppTests
```

From the command line (what CI does, `.github/workflows/ios.yml`):

```sh
(cd LessNoiseCore && swift test) && (cd LessNoiseKit && swift test)
xcodebuild test -project WenigerLaerm.xcodeproj -scheme WenigerLaerm \
  -destination 'platform=iOS Simulator,name=iPhone 16' CODE_SIGNING_ALLOWED=NO
```

The packages resolve GRDB (MIT, the app's one dependency) and, for the Linux tests only,
swift-crypto from github.com. The category files are the Android app's
(`android/app/src/main/assets/categories.json`, `yamnet_labels.txt`), referenced, not copied.

**CI:** every push to the branches `ios-app` and `ios-testflight*` that touches `ios/**` runs the workflow «iOS» on GitHub's
macOS runner: both packages' `swift test`, `xcodegen`, `xcodebuild build-for-testing` and the
simulator tests without signing; the test summary is in the run's summary, logs and the
`.xcresult` are an artifact.

## Signing with a personal team (on a Mac; without a Mac see «TestFlight» below)

1. Xcode → Settings → Accounts: add the Apple ID of the developer account (individual).
2. In `project.yml` set `DEVELOPMENT_TEAM` to the Team ID (developer.apple.com → Membership), run
   `xcodegen` again. Signing is automatic; the bundle id is `ch.lessnoise.app`.
3. Capabilities the app uses: Background Modes → Audio (in Info.plist), Time Sensitive
   Notifications (entitlement in `project.yml`; if the account cannot sign it, delete those two
   lines — «Sehr laut» then arrives as a normal notification). No location background mode, no
   iCloud, no push.
4. Select the iPhone as destination and run (⌘R). The first launch on a device needs
   Settings → General → VPN & Device Management → trust the developer (only for development builds).

## Classifier

The iPhone recognises sounds with **Apple's built-in classifier** (SoundAnalysis,
`SNClassifySoundRequest(classifierIdentifier: .version1)`; on the phone, no download, no
dependency) instead of Android's YAMNet, which has no supported Swift package (DESIGN.md §9).
`App/categories-ios.json` maps Apple's label identifiers to the app's categories (same ids, names,
order and thresholds as `android/app/src/main/assets/categories.json`). To see Apple's identifiers:
`swift ios/scripts/apple-sound-labels.swift` on a Mac (CI writes them to `apple-sound-labels.txt`
in the run's artifact; a copy is in `LessNoiseKit/Tests/Fixtures/apple-sound-labels.txt`). Categories
can differ from Android's for the same sound — DESIGN.md §9 «Categories differ by platform».

## TestFlight (GitHub Actions, no Mac)

TestFlight is Apple's beta-testing service: builds uploaded to App Store Connect (Apple's portal for
apps) install on invited iPhones through Apple's «TestFlight» app. The signed build and the upload
are made by the workflow «iOS TestFlight» (`.github/workflows/ios-testflight.yml`) on GitHub's macOS
runner, the same idea as `labor-build.yml` for Android: the signing key lives only in the
repository's Actions secrets, never in a chat session or on a laptop.

The workflow has two jobs. **archive-unsigned** always runs: `xcodegen`, then a Release archive for
a real iPhone (`generic/platform=iOS`) without signing; it proves the device build compiles and
links, and its summary shows version, build number and the size of the app. **testflight** runs
only when all four secrets below are set (otherwise it ends green with «Signing secrets not set —
skipped»): it writes the API key to the runner's temp folder, archives with automatic signing
(Xcode creates the distribution certificate and the provisioning profile itself with the API key,
`-allowProvisioningUpdates`), exports with `ios/ExportOptions.plist` (method `app-store-connect`,
destination `upload`) — which uploads to App Store Connect — and deletes the key.

**Once (owner, on Apple's websites, from any browser):**

1. Apple Developer Program membership (individual first, DESIGN.md §14); note the **Team ID**.
2. App Store Connect: a new app record — platform iOS, name «Weniger Lärm», primary language
   German, bundle id `ch.lessnoise.app` (register the bundle id first if the form asks for it),
   SKU e.g. `wenigerlaerm-ios`.
3. App Store Connect: an **API key** of type *Team* with the role **Admin** (Admin is needed so that
   Xcode may create the signing certificate and the provisioning profile). Download the `.p8` file (Apple offers it only once); note the key id and the
   issuer id shown with the keys.
4. GitHub → this repository → Settings → Secrets and variables → Actions → four repository
   secrets:
   - `APPLE_TEAM_ID` — the Team ID
   - `ASC_KEY_ID` — the API key's id
   - `ASC_ISSUER_ID` — the issuer id
   - `ASC_KEY_P8` — the whole text of the `.p8` file, including the `BEGIN`/`END PRIVATE KEY` lines
5. TestFlight → Internal Testing: a group with yourself (and other team members) as testers.

**Per build (a session or the owner):**

1. Push a branch named `ios-testflight*` that touches `ios/**`, or run the workflow by hand:
   `gh workflow run ios-testflight.yml --ref <branch>` (input `build_number` overrides the build
   number). Without the input the build number is the run number + 1, so it rises with every run;
   `MARKETING_VERSION` (the visible version, `project.yml`) is raised by a commit for a new version.
2. Wait for the green run; its summary says «Version …, build … — uploaded to App Store Connect».
3. App Store Connect processes the build (minutes to about half an hour) before it shows under
   TestFlight. Export compliance is answered by the app itself (`ITSAppUsesNonExemptEncryption =
   NO`: only Apple's TLS and CryptoKit).
4. Internal testers install or update it through the TestFlight app (no review). External testers
   need Apple's beta review: «Test Information» (what to test, contact) and the review notes of
   DESIGN.md §3 (why the app records in the background).

Before external testing and before the App Store: the privacy label and texts of DESIGN.md
«Before an iOS release», and the server must accept the audio source `IOS` (`ios/PARITY.md`: today
it refuses iPhone sites and minutes). What Android gained since the port: **[PARITY.md](PARITY.md)**.

## Device test checklist

Nothing below can be tested on a simulator or in CI; each needs an iPhone (ideally two models).

1. **Screen-locked measuring for 8 h** (on the charger): «Starten», lock the phone, leave it over
   night. Afterwards: one measurement, minutes without gaps (CSV: `coverage` ≈ 1 in every row),
   the orange microphone indicator was there the whole time, no crash. Repeat once on battery.
2. **Battery:** the same night on battery: % per hour (Settings → Battery); Instruments «Energy
   Log» once for an hour. Expected order: a few % per hour; report the number.
3. **Calibration:** «Einstellungen › Erweitert › Mit Messgerät vergleichen» next to a sound level
   meter with steady pink noise ≥ 60 dB(A) (30 s); then pink noise −10/−20 dB steps must come out
   as the same steps (proves `.measurement` has no AGC); calibrator 94 dB at 1 kHz if available.
   «Mikrofon» under «Erweitert» shows the input and its rate (48000 Hz expected).
4. **Interruptions:** during a measurement, a phone call (accepted, 1 min), Siri, an alarm, a
   voice memo in another app. Each: «Mikrofon ist stumm» while it lasts, measuring resumes by
   itself afterwards, the CSV shows the lost time as `valid_s` < 60 in those minutes. Also with
   the screen locked (resuming in the background is the uncertain case).
5. **Notification delivery («Unterwegs»):** switch on (permission prompt appears then, not at
   launch), measure, play a loud motorbike/traffic recording ≥ 75 dB(A): one «Sehr laut» per
   2 min at most, also with the screen locked and in a Focus (only if «Time Sensitive» was
   allowed); the tap opens «Laut! Melden» with the time of the event. (Needs the classifier for
   the categories; without it no «Sehr laut» is ever posted — see DESIGN.md §9.)
6. **Upload under poor network:** share a measurement with Low Data Mode, then airplane mode for
   10 min, then a weak cellular signal: the status line says «Kein Internet – wird nachgeholt»,
   nothing is lost (server count = phone count afterwards), «Nur über WLAN» holds on cellular.
   Watch the requests with a proxy or the server log: only `api.lessnoise.ch`.
7. **Location:** one fix after «Starten» only while the app is open (lock the phone right after
   «Starten»: status «Standort wird bestimmt …» until the app is opened again); «Genauer
   Standort» off → the message about it; denied → «Kein Standort – tippen» and «Koordinaten
   einfügen». The location arrow never shows while the app is in the background.
8. **Map tab:** loads only when opened, re-centres on the shifted point, links (Datenschutz,
   GitHub) open in Safari; Safari's Web Inspector shows no request outside the four hosts.
9. **«Vom Server löschen»**, **CSV export** (opens in Numbers / Files), **reinstall** (old token
   and secret gone).

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

**Not ported:** the classifier model (YAMNet — DESIGN.md §9: TensorFlow Lite has no official
SwiftPM package; the app runs with the classifier off for now), Compose UI, Android services and
WorkManager (iOS: LessNoiseKit and App/), the Labor build. Kotlin's `Long` is Swift's `Int` (64-bit
on every supported platform).

**The audio source of iPhones** is `IOS` (`AudioSource.IOS_MEASUREMENT`, the one place it is
defined). The server accepts only `UNPROCESSED | VOICE_RECOGNITION` today: it must accept `IOS`
before the app is released (DESIGN.md «Before an iOS release»); no test sends to the live server.

## Build and test the packages (Linux or macOS)

```sh
export PATH=/home/claude/swift/swift-6.1.2-RELEASE-ubuntu24.04/usr/bin:$PATH   # this machine; on a Mac: Xcode's toolchain
(cd ios/LessNoiseCore && swift test)   # 20 test functions (Swift Testing, 28 cases), ≈ 35 s
(cd ios/LessNoiseKit && swift test)    # 40 XCTest tests, ≈ 40 s
```

LessNoiseCore: 16 golden tests and 4 unit tests (the engine and replay tests are parameterized).
On Apple platforms the gzip test inflates the Compression framework's real deflate output and
checks CRC and size (on Linux the bodies are stored blocks).

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

## Next

The device tests above, the classifier decision (DESIGN.md §9), then the server, website and
privacy changes of DESIGN.md «Before an iOS release», then TestFlight.
