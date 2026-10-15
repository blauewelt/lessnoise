# Weniger Lärm for iPhone — app design (plan, not built yet)

Scope of the first version: the **Online-Version** of android/README.md («Messen», «Messungen»,
«Karte», «Teilen», «Einstellungen», «Laut! Melden», «Unterwegs»), German UI, same server and API
(`https://api.lessnoise.ch`, server/DESIGN.md §4). No Offline-Version, no Labor build. Every
promise of PRIVACY.md and server/DESIGN.md §2 holds unchanged: no audio stored or sent, only the
shifted point (σ default 50 m, deterministic per place via the device secret), location only while
the app is open, opt-in, delete means delete.

## 1. Structure

```
ios/
  LessNoiseCore/          Swift package: DSP, detector, calibration, geo, payloads, uploader, texts (done, golden-tested)
  project.yml             XcodeGen spec (to write on the Mac)
  App/                    SwiftUI app target «WenigerLaerm» (to write)
    Audio/                AudioCapture (AVAudioEngine) → MeasurementEngine on one serial queue
    Data/                 SQLite (GRDB): minutes, events, measurements, calibrations; CSV export
    Upload/               URLSessionTransport (UploadTransport), LiveUploader (LiveUploadPlan), BG task
    Location/             OneShotLocator (CLLocationManager, when in use)
    UI/                   tabs, screens, map tab (WKWebView), notifications
  AppTests/               XCTest on the simulator: data layer, transport against a local fake, UI state
```

`project.yml` (XcodeGen) keeps the Xcode project out of git: the Mac only needs
`brew install xcodegen && cd ios && xcodegen && xcodebuild -scheme WenigerLaerm -destination 'platform=iOS Simulator,name=iPhone 16' test`.
Targets: app (iOS 16.0, iPhone only, portrait), local package `LessNoiseCore`, GRDB via SPM.
Bundle id proposal: `ch.lessnoise.app` (same as Android; stores are separate) — owner decides.

## 2. Audio

- `AVAudioSession`: category `.record`, mode **`.measurement`** (no AGC, no noise suppression,
  no voice processing on the built-in microphones), `setPreferredSampleRate(48_000)`,
  `setPreferredIOBufferDuration(0.02)`, preferred input = the built-in microphone
  (`availableInputs` port type `.builtInMic`); no `.allowBluetooth`: AirPods/Bluetooth headsets
  are not measured with (they deliver 16/24 kHz voice-processed audio).
- `AVAudioEngine.inputNode.installTap(bufferSize: 4800)`; the tap copies the first channel and hands
  it to **one serial queue** that owns `MeasurementEngine` (the core is single-threaded by contract;
  `setMicSilenced` may be called from anywhere). The engine needs 48 kHz (`fs % 8 == 0`,
  `fs % 3 == 0`): if the hardware runs at 44.1 kHz (some routes), an `AVAudioConverter` resamples
  to 48 kHz mono Float32 first (A-weighting itself is golden-tested at 44.1 kHz too).
- Interruptions (call, Siri, another app taking the mic): `interruptionNotification` →
  `setMicSilenced(true)` until `.ended`, then restart the engine; the core then marks the time
  invalid exactly like Android's silenced mic (coverage, no events). Route changes: restart.
- Calibration flow: the same three methods as Android (sound level meter, calibrator, manual)
  using `CalibrationMeasurement`, `CalibrationMath`, `Recalibration`, `EventFloor` from the core.
  **One audio source** on iOS (see `Calibration/Recalibration.swift`): stored and sent as
  `UNPROCESSED`, because the server accepts only `UNPROCESSED | VOICE_RECOGNITION`. The default
  offset 112.35 dB comes from Android's CDD guideline, which does not bind Apple: uncalibrated
  iPhone data stays flagged `calibrated=false` as on Android; a per-model default table is a later
  improvement once calibrated iPhones exist.

## 3. Measuring with the screen locked

`UIBackgroundModes: audio`. An active recording session keeps the app running with the screen
locked; iOS shows the orange microphone dot / red status-bar pill the whole time (good: visible).
**App Store review risk, honestly:** background audio is accepted for recording apps, but
reviewers question apps that "record" without producing audio files; a noise meter that measures
for hours is unusual. Mitigation in the review notes: user-started measurement only, visible
indicator, a «Stopp» button in the app and a Live Activity/notification, no audio stored,
link to PRIVACY.md. Fallback if rejected: measure only in the foreground with
`isIdleTimerDisabled` and a dimmed black screen (much weaker product). Expect at least one
round of questions.

Battery: A-weighting + 5 feature biquads + Fast per sample ≈ 48 k × ~60 flops/s; measure on the
device (Instruments «Energy Log») over a night.

## 4. Location (one fix, when in use)

`CLLocationManager`, `requestWhenInUseAuthorization` only — no «Always», no `location`
background mode. One `requestLocation()` right after «Starten» **only while the app is active**
(`UIApplication.applicationState == .active`; with the audio background mode the process runs
in the background too, so this check is required). If the app goes to the background first:
`LocateState.pending`, tried when it is opened again (same as Android 0.6.3). Reduced accuracy
(`accuracyAuthorization == .reducedAccuracy`) = Android's «Ungefähr» → `APPROXIMATE_ONLY`.
`horizontalAccuracy` is used like Android's accuracy (thresholds 75 m at «Start», 50 m for reports,
25 m good enough); negative = invalid. The fix goes straight into `LocationPolicy.decide` →
`LocationNoise.shift`; only the shifted point is stored (measurement row) and sent.

## 5. Upload

- `URLSessionTransport: UploadTransport`: `URLSessionConfiguration.ephemeral` (no cookies, no
  cache, no credential storage), `httpShouldSetCookies = false`, redirects refused in
  `willPerformHTTPRedirection` (a redirect could point to another host), timeouts 15/30 s, the
  headers `UploadClient.buildRequest` sets (User-Agent `LessNoise`, no device details), body =
  the gzip bytes. ATS default (TLS 1.2+), no exceptions.
- While a shared measurement runs (app alive through the audio mode): a `Task` driven by
  `LiveUploadPlan` (first run 30 s after the point, then every 2 min, events after 30 s, 429
  back-off) calling `Uploader.run(withMinutes:)`. After «Stopp»: one run after 15 s.
- Fallback: `BGAppRefreshTask` (`ch.lessnoise.upload`) with `earliestBeginDate` =
  `UploadCadence.delayToNextHourMs` — iOS decides when (or whether) it runs, so uploads outside a
  measurement may wait until the app is opened; also run on every app start. Nothing is lost
  (markers move only on acknowledgement, the database is the source of truth).
- «Nur über WLAN»: `NWPathMonitor` (`isExpensive || isConstrained` → wait).

## 6. Notifications («Unterwegs» → «Sehr laut»)

Local `UNNotificationRequest` when `LoudAlertRule.shouldAlert` (≥ 75 dB(A), Töff & Poser /
Strassenverkehr, not wind, ≤ 1 per 2 min); title `LoudAlertRule.title`, body «Antippen zum Melden»;
the tap carries the event time and category in `userInfo` → `LoudAlertIntent.preset` → prefilled
«Laut! Melden». Permission asked when «Unterwegs» is switched on, not at launch.

## 7. Tab «Karte»

`WKWebView` with `WKWebsiteDataStore.nonPersistent()`. Navigation delegate: only
`MapLinks.staysInApp` stays, other links open in Safari; **every** request is limited to
`MapLinks.ALLOWED_HOSTS` with a `WKContentRuleList` (block all, then `ignore-previous-rules` for the
four hosts) — the delegate alone does not see sub-resources, Android's `shouldInterceptRequest`
does. Only the two scripts of `MapLinks` are evaluated; `MapCentring` decides load vs re-centre;
the page is kept while another tab is shown, loading is stopped when hidden. The tab history
(`TabHistory`) drives the app's own back button / edge swipe (iOS has no system back between tabs).

## 8. Storage

**GRDB (SQLite)**, chosen over SwiftData: SwiftData needs iOS 17 (target is 16); the data is
relational and append-heavy (one minute row per minute, ordered range queries
`start > marker ORDER BY start, rowid LIMIT n` exactly like Room), migrations must be explicit and
testable, and CSV export streams rows. Tables mirror Room's (`minutes`, `events`, `measurements`
with `point_e/point_n/sigma_m`, `calibrations`); NaN → NULL as on Android. GRDB is one MIT-licensed
dependency; the zero-dependency alternative is the system `SQLite3` module with a small wrapper.
- The database file: `FileProtectionType.completeUntilFirstUserAuthentication` (the app writes
  while the phone is locked), `isExcludedFromBackup = true` (Android: `allowBackup=false`).
- Token and noise secret: Keychain, `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`, not
  synchronizable (Android: Keystore-encrypted prefs). Other upload state: `UserDefaults`.

## 9. Classifier (phase 2)

| | Core ML (YAMNet converted with coremltools) | TensorFlowLiteSwift |
|---|---|---|
| Model | `.mlpackage` ≈ 4–8 MB; the log-mel front end may have to be done in Swift (vDSP) and only the CNN converted | the same `yamnet.tflite` (4.1 MB) as Android |
| Runtime | system (CPU/ANE), no extra binary | TensorFlowLiteC ≈ 3–5 MB in the app |
| Parity with Android | needs a new golden test of scores (conversion/precision differences) | identical model; scores golden-testable against `tools/verify_yamnet.py` |
| Licence | YAMNet Apache-2.0; coremltools BSD-3 (build time only) | Apache-2.0 |

Recommendation: start with TensorFlow Lite for parity (same categories, same thresholds), measure
size and battery, then decide on Core ML. Either plugs into `classifierDue` /
`copyClassifierWindow` / `onClassifierFrame` of the core with `ClassifierPreprocessor.normalize`.

## 10. What must be tested on a device

1. `.measurement` really disables AGC: level steps of a speaker (pink noise −10/−20/−30 dB) must
   come out as the same steps; compare with a sound level meter (calibration flow end to end).
2. Hardware sample rate on the built-in mic (48 kHz expected) and the converter path otherwise.
3. Hours with the screen locked: no gaps (coverage), interruptions by a call/Siri/alarm counted as
   invalid, restart afterwards; memory flat; battery per hour.
4. Location: one fix only while active, «Ungefähr» path, denial, no location indicator in the
   background.
5. Upload against the real server: registration, live map within ~1 min, «Vom Server löschen»,
   429 handling; no request to any other host (Charles/mitmproxy or the server log).
6. Map tab: no request outside the four hosts (Web Inspector), re-centring, links to Safari.
7. «Sehr laut» notification and its tap → prefilled report.
8. CSV export opens in Numbers/Files; values identical to the same WAV replayed on Android
   (`swift test` already proves the arithmetic).

## 11. Signing and TestFlight (owner)

1. Apple Developer Program (organisation or individual), Team ID.
2. In `project.yml`: `DEVELOPMENT_TEAM`, bundle id; Xcode → Signing: automatic.
3. App Store Connect: new app, bundle id, SKU, primary language German.
4. `xcodebuild archive` + `-exportArchive` (or Xcode Organizer) → upload; export compliance:
   uses only Apple's TLS/CryptoKit (exempt, `ITSAppUsesNonExemptEncryption = NO`).
5. TestFlight internal testers first (no review), then external (beta review).
6. Privacy «nutrition label» and review notes (section 12) before the first external build.

## 12. Privacy texts needed later (not changed now)

- **Info.plist** (German, `de` as development language):
  `NSMicrophoneUsageDescription` = «Weniger Lärm misst den Schallpegel. Es wird nie Audio
  gespeichert oder gesendet.»; `NSLocationWhenInUseUsageDescription` = «Nur wenn du teilst: einmal
  nach dem Starten, solange die App offen ist; gesendet wird ein verschobener Punkt, bei einer
  Meldung der genaue Ort.»; `UIBackgroundModes = [audio]`; `ITSAppUsesNonExemptEncryption = NO`.
- **PRIVACY.md / docs/datenschutz.html**: an iPhone section — background audio indicator, Keychain
  instead of Keystore, iOS location permission names («Beim Verwenden der App», «Genauer
  Standort»), that Apple's own App Store/TestFlight analytics and crash reports are Apple's and
  opt-in, no iCloud backup of the data, BGAppRefresh timing; the «How to verify» part needs an
  iOS equivalent (no APK to inspect: point to the source and the network test).
- **App Store privacy label** (proposal; owner decides): Data collected — *Location → Precise
  Location* (reports carry 5-decimal coordinates; the shifted point has metre resolution) for App
  Functionality; *Other Data* (noise levels/events) for App Functionality; *Identifiers → User ID*?
  The random device id + token links the records of one install: declare «Linked to you» to be
  safe, «Used for tracking: no». Not collected: audio, contacts, name, email, usage data,
  diagnostics (unless the owner enables App Store crash reports — Apple's, then declare
  Diagnostics).

## 13. What needs the Mac

1. `cd ios/LessNoiseCore && swift test` on macOS: the first run of the Apple code paths that Linux
   cannot run — CryptoKit's HMAC/SHA-256 (Linux uses swift-crypto, same API) and the
   Compression-framework deflate in `Gzip` (Linux writes stored blocks; the test must then inflate
   with `Compression` instead of the stored-block reader — add that check there).
2. Write `project.yml` and the app target; `xcodegen && xcodebuild test` on the simulator.
3. The device tests of section 10; then TestFlight (section 11).

## 14. Open questions for the owner

1. `audio_source` for iPhones: send `UNPROCESSED` (now) or add an iOS value to the server schema?
2. Bundle id and app name in the App Store («Weniger Lärm»?), developer account type.
3. Default calibration offset for iPhones: keep 112.35 dB (flagged uncalibrated) or collect a
   per-model table first?
4. GRDB as the one dependency of the app, or the system SQLite3 wrapper?
5. Classifier: accept TensorFlow Lite in phase 2 for parity?
