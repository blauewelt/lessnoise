# Weniger Lärm for iPhone — app design (first version built, not released)

Scope of the first version: the **Online-Version** of android/README.md («Messen», «Messungen»,
«Karte», «Teilen», «Einstellungen», «Laut! Melden», «Unterwegs»), German UI, same server and API
(`https://api.lessnoise.ch`, server/DESIGN.md §4). No Offline-Version, no Labor build. Every
promise of PRIVACY.md and server/DESIGN.md §2 holds unchanged: no audio stored or sent, only the
shifted point (σ default 50 m, deterministic per place via the device secret), location only while
the app is open, opt-in, delete means delete.

## 1. Structure

```
ios/
  LessNoiseCore/          Swift package: DSP, detector, calibration, geo, payloads, uploader, texts (golden-tested)
  LessNoiseKit/           Swift package (GRDB + LessNoiseCore), everything without Apple UI/hardware frameworks,
                          tested on Linux and macOS:
    Storage/              AppDatabase (GRDB, Room v7 tables), repositories, CSV export, re-evaluation,
                          upload state / report queue / settings persistence
    Measure/              MeasurementSession: AudioInput → MeasurementEngine on one serial queue, DB queue,
                          live state, interruption gap fill, classifier pipeline
    Share/                ShareController («Auf die Lärmkarte?», one fix, attach point), ReportFlow, Locator protocol
    Upload/               UploadCoordinator (LiveUploadPlan loop, after-stop, soon, hourly fallback), DB upload source
    Alerts/ Calibration/ Map/ UI/   «Unterwegs», calibration flow, map lock-down rules + tab history, German formatting
    AppServices.swift     the wiring (Android's AppContainer)
  project.yml             XcodeGen spec (app «WenigerLaerm», unit tests, scheme)
  App/                    SwiftUI app target «WenigerLaerm» (display name «Weniger Lärm»)
    Platform/             AudioCapture (AVAudioSession/AVAudioEngine), OneShotLocator (CoreLocation),
                          URLSessionTransport + NetworkMonitor, Keychain/UserDefaults/protected-file stores,
                          BGAppRefreshTask, background task, local notifications
    UI/                   tabs, «Messen», «Messungen» (Swift Charts), «Karte» (WKWebView), «Teilen», sheets,
                          «Einstellungen» / «Erweitert» (calibration)
  AppTests/               XCTest on the simulator: transport (URLProtocol stub), stores, Info.plist, WebKit rules
```

`project.yml` (XcodeGen) keeps the Xcode project out of git: the Mac only needs
`brew install xcodegen && cd ios && xcodegen && xcodebuild -scheme WenigerLaerm -destination 'platform=iOS Simulator,name=iPhone 16' test`.
Targets: app (iOS 16.0, iPhone only, portrait), local packages `LessNoiseKit` → `LessNoiseCore`,
GRDB via SPM. Bundle id `ch.lessnoise.app` (owner's decision; same as Android, the stores are
separate), individual developer account, Team ID still a placeholder in `project.yml`.

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
  **One audio source** on iOS (see `Calibration/Recalibration.swift`): stored and sent as `IOS`
  (owner's decision; one constant, `AudioSource.IOS_MEASUREMENT`). The server accepts only
  `UNPROCESSED | VOICE_RECOGNITION` today and must accept `IOS` first (§15). The default offset
  112.35 dB comes from Android's CDD guideline, which does not bind Apple: uncalibrated iPhone data
  stays flagged `calibrated=false` as on Android (owner's decision); a per-model default table is
  a later improvement once calibrated iPhones exist.
- As built: iOS delivers no buffers during an interruption (Android delivers silenced zeros). The
  session feeds the missing time as silenced zeros when capture resumes (≤ 15 min; longer gaps are
  left to the engine's clock re-anchoring), so the timeline stays on the wall clock and the gap is
  recorded as invalid time (`valid_s`, `coverage`) exactly like Android. Engine configuration
  changes (route, sample rate) are treated as an interruption followed by a restart; if iOS does
  not allow the restart yet (call still running), it is retried every 5 s for 2 minutes.

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

**As built (planner's decision, 2026-10-15):** TensorFlow Lite has no supported Swift Package
Manager package, so the iPhone uses **Apple's built-in sound classifier**: SoundAnalysis
`SNClassifySoundRequest(classifierIdentifier: .version1)` — part of iOS, no model file, no download,
no dependency, nothing leaves the phone. `App/Platform/SoundAnalysisClassifier.swift` implements
the core's `SoundClassifier`: the engine hands over the latest 0.975 s at 16 kHz about once a
second (Android's window and cadence), one long-lived `SNAudioStreamAnalyzer` (overlap 0, window
0.975 s or the closest the classifier allows) classifies it, and the newest result's confidences
come back aligned with `knownClassifications`. `App/categories-ios.json` maps Apple's identifiers
to the app's categories with Android's ids, names, order (tie priority: «loud_vehicle» first) and
thresholds; the decision is the same `CategoryMapper` code. Wind stays the detector's. With
categories, «Sehr laut» works as on Android. Checks: LessNoiseKit tests on fake label streams; on
the iOS Simulator every identifier of the file must be in `knownClassifications`, and the
classifier must produce scores for the engine's windows. The removed TFLite variant and the Core ML
conversion of YAMNet remain options if parity with Android becomes more important than having no
dependency.

**Categories differ by platform.** iPhone categories come from a different model (Apple's, with
its own label set and training data) than Android's (YAMNet/AudioSet); category shares and event
categories of the same sound can differ between platforms, yet the map shows them together
(«Klingt nach», the category shares of a cell, the loud-vehicle highlight). Proposal before release:
1. *Compare on the same recordings:* replay the three WAVs of `android/dsp/src/test/resources/replay`
   and a set of labelled street recordings (motorbike, car, truck, tram, aircraft, construction,
   voices, music, wind) through both classifiers (Android: `tools/verify_yamnet.py`/the replay
   test; iOS: SoundAnalysis on macOS with `SNAudioFileAnalyzer` + `AppleSoundCategories`, a
   small macOS test in CI), and report per category: agreement of the dominant category per
   window, share differences per minute, and the confusion between loud_vehicle and road_traffic.
   Accept if the dominant category agrees in, say, ≥ 80 % of windows for loud_vehicle/road_traffic.
2. *Tune only the mapping, not the rules:* add/remove Apple identifiers in categories-ios.json
   (the thresholds stay Android's) until the comparison passes; record the result in the WORKLOG.
3. *Label the data:* the server already stores `audio_source` per minute (`IOS` vs
   `UNPROCESSED`/`VOICE_RECOGNITION`), so shares can be compared or separated per platform later
   without an app change; the map legend/«Über die Karte» should say that categories are estimated
   on the phone by two different recognisers (Android: YAMNet, iPhone: Apple) and can differ.
4. If they differ too much: show iPhone category shares on the map only after (1) passes, or
   publish them separately per platform (server change).

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

## 13. What needed the Mac (now done by CI, except the devices)

1. `cd ios/LessNoiseCore && swift test` on macOS: the first run of the Apple code paths that Linux
   cannot run — CryptoKit's HMAC/SHA-256 (Linux uses swift-crypto, same API) and the
   Compression-framework deflate in `Gzip` (Linux writes stored blocks; the test must then inflate
   with `Compression` instead of the stored-block reader — add that check there).
2. Write `project.yml` and the app target; `xcodegen && xcodebuild test` on the simulator.
3. The device tests of section 10; then TestFlight (section 11).

## 14. Owner's decisions (2026-10-15)

1. `audio_source` for iPhones: its own value `IOS` (server change needed, §15).
2. Bundle id `ch.lessnoise.app`, app name «Weniger Lärm», individual developer account first.
3. Default calibration offset 112.35 dB as on Android, data flagged uncalibrated.
4. GRDB (MIT) as the one dependency.
5. Classifier: TensorFlow Lite for parity; replaced by the planner (no supported SwiftPM package) with
   Apple's built-in SoundAnalysis classifier, see §9 «As built».

## 15. Before an iOS release: changes outside `ios/` (not made in this branch)

**Server** (`server/`):
1. `stadtlaerm_server/schemas.py`: accept `"IOS"` in `SiteIn.audio_source` (line ≈ 100, today
   `Literal["UNPROCESSED", "VOICE_RECOGNITION"]`) and in the minutes' `audio_source` (line ≈ 178);
   a test in `tests/test_api.py` with an iPhone site and minute (`device_model` like `iPhone16,1`).
   Until then every iPhone upload is refused and the app shows «Nicht gesendet: vom Server
   abgelehnt».
2. `server/DESIGN.md` §4.2/§4.3: document `IOS` (an iPhone's built-in microphones in AVAudioSession
   mode `.measurement`: no AGC, no noise suppression) next to the two Android values; say whether the
   map treats it like `UNPROCESSED` (the aggregation does not use the source today).
3. Nothing else: the request bodies, the cadence and the per-device limits are the Android 0.6.3 ones.

**PRIVACY.md and docs/datenschutz.html** (an «iPhone» section, same promises):
1. Audio: never stored or sent; measuring with the screen locked uses iOS's background audio mode,
   iOS shows the orange microphone indicator the whole time.
2. Location: «Beim Verwenden der App» only (never «Immer», no background location mode); one fix
   after «Starten» while the app is open; «Genauer Standort» needed; only the shifted point is
   stored; reports carry the exact place until the server has them (stored in a file that is not
   backed up and is deleted with the app).
3. Storage: the database in the app's own folder, excluded from iCloud/iTunes backups, protected
   «until first unlock»; the upload token and the location-noise secret in the Keychain («this
   device only», never synchronised; removed at the next installation if the app was deleted).
4. Network: the same two paths as the Android Online-Version (api.lessnoise.ch for the upload; the
   map tab loads wenigerlaerm.ch, wmts.geo.admin.ch, api.lessnoise.ch/api.stadtlaerm.ch, enforced
   by a WebKit content rule list); a fixed «Accept-Language: de» and the User-Agent «LessNoise».
5. Apple's own data: App Store / TestFlight analytics and crash reports are Apple's and only if the
   person allowed them in iOS; the app adds no analytics.
6. Background uploads: hourly when iOS grants a background refresh, otherwise when the app is
   opened; while measuring every 2 minutes as on Android.
7. «How to verify» for iPhone: no APK to inspect — the source in `ios/`, the CI run of the commit,
   the network test with a proxy (ios/README.md «Device test checklist» 6, 8).
8. Classifier: the iPhone uses Apple's built-in on-device sound recognition (SoundAnalysis), not
   YAMNet; no audio leaves the phone for it; categories can differ from Android's (§9).

**Map** (`docs/karte.html`, server publishing): before iPhone data appears, the category
comparison of §9 «Categories differ by platform» (1)–(2), and a line in the map's legend that
the categories come from two different on-phone recognisers.

**Website** (`docs/`): the start page and `anleitung.html` mention the iPhone (TestFlight first:
«Beta, Einladung auf Anfrage»), the iOS location prompt names, and that «Akku-Optimierung» is
Android-only; `datenschutz.html` as above. No download link until the App Store.

**App Store privacy label** (App Store Connect → App Privacy; proposal, owner decides):
- Data used to track you: **none**.
- Data linked to you (the random device id + token link one install's records; declared to be safe):
  - *Location → Precise Location*: «App Functionality» (reports carry 5-decimal coordinates; the
    shifted point has metre resolution).
  - *Other Data* (noise levels per minute, noise events, placement details): «App Functionality».
  - *Identifiers → User ID*: «App Functionality» (the random device id; no account).
- Not collected: contact info, health, financial, contacts, user content (no audio), browsing or
  search history, purchases, usage data, diagnostics (unless the owner turns on App Store crash
  reports — then *Diagnostics → Crash Data*, Apple's).
- Privacy policy URL: wenigerlaerm.ch/datenschutz.html (the iPhone section must be there first).

**App Store description** (German, draft):
> **Weniger Lärm** misst den Strassenlärm vor deinem Fenster – mit dem Mikrofon deines iPhones,
> stundenlang und auch bei gesperrtem Bildschirm. Die App zeigt den Pegel jetzt, das
> Grundrauschen und die Lärmspitzen; jede Messung bleibt als Verlauf auf dem Telefon und lässt sich
> als CSV exportieren. Wer will, teilt seine Messwerte auf die gemeinsame Lärmkarte von Zürich:
> Minutenwerte und Lärmspitzen, mit einem zufällig verschobenen Punkt statt des Standorts.
> Mit «Laut! Melden» meldest du einen lauten Töff oder Poser mit einem Tipp.
> Es wird nie Audio gespeichert oder gesendet. Teilen ist freiwillig, ausschaltbar und mit einem
> Tipp vom Server gelöscht. Offene Software vom Verein Weniger Lärm (Apache-2.0).
> Ohne Kalibrierung sind die Werte Richtwerte (± 5 dB); mit einem Schallpegelmesser lässt sich das
> iPhone kalibrieren.

Keywords: Lärm, Lärmmessung, Schallpegel, dB, Lärmkarte, Verkehrslärm, Töff, Zürich. Category:
Utilities. Age rating 4+. Review notes: see §3 (why the app records in the background; a demo of
«Starten» → lock the screen → «Stoppen»).
