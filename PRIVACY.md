# Privacy — what Weniger Lärm does with sound

Weniger Lärm (until 0.5.1 «Stadtlärm») measures noise levels. It never records audio. This is the
core promise of the project, and this page explains how the code keeps it, so anyone can check.

Everything on this page is about the **public app** (`ch.lessnoise.app` since 0.6.0, before
`ch.stadtlaerm.app`; the APKs linked from wenigerlaerm.ch). It is published in two editions,
**«Weniger Lärm»** (no internet and no location permission at all) and **«Weniger Lärm · Karte»**
(internet only for the opt-in «Messwerte teilen» and «Laut! Melden»; location only for one fix at
the start of a shared measurement and for a report), see [Editions](#editions). Since v0.3.3 the
repository can also build a separate diagnostics app that *can* record audio; it is a different
app, is never published, and neither published edition contains any of its code. See
[Labor-Build](#labor-build) below.

## Editions

| | «Weniger Lärm» (offline) | «Weniger Lärm · Karte» |
|---|---|---|
| File (on the website, `docs/download/`) | `stadtlaerm.apk` — the default download | `stadtlaerm-karte.apk` |
| Gradle flavour | `offline` | `public` |
| App id, signing key | `ch.lessnoise.app` (0.6.0; before `ch.stadtlaerm.app`), release key | the same |
| `INTERNET`, `ACCESS_NETWORK_STATE` | **no** (removed in `app/src/offline/AndroidManifest.xml`) | yes, only for «Messwerte teilen» and «Laut! Melden» |
| `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` | **no** (removed in `app/src/offline/AndroidManifest.xml`) | yes, only while the app is open: one fix at «Start» of a shared measurement (turned into the 100 m cell on the phone; coordinates neither stored nor sent) and one fix per «Laut! Melden» report (sent with that report, then deleted from the phone) |
| `ACCESS_BACKGROUND_LOCATION` | no | **no** — location is never read in the background |
| «Messwerte teilen», «Laut! Melden» | do not exist (`BuildConfig.UPLOAD_AVAILABLE = false`) | off until you switch it on |
| Data leaves the phone | only when *you* export it (CSV; calibrations as JSON) and share it | the same, plus the opt-in upload |
| Can report anything (usage, crashes, versions) | **no** — it has no way to | only what «Messwerte teilen» sends, below |

Both editions are built from the same Kotlin sources (`app/src/main` and `app/src/public/kotlin`)
and contain no recording code. Because they share the app id and the key, installing one over the
other switches the edition and keeps the measurements. (0.6.0 changed the app id from
`ch.stadtlaerm.app` to `ch.lessnoise.app`: Android treats it as a new app, so 0.6.0 does not
install over 0.5.x and starts with empty storage.) The offline edition still contains the compiled
upload and location classes (shared code), but they can never run: every entry point returns when
`UPLOAD_AVAILABLE` is false, neither the settings card nor the «Laut! Melden» button exists, without
`INTERNET` Android would refuse the socket, and without `ACCESS_FINE_LOCATION`/`ACCESS_COARSE_LOCATION`
in its manifest Android would deny the permission without asking. When the offline edition is
installed over a Karte edition that had sharing on, it switches sharing off, drops reports not yet
sent (with their coordinates) and cancels the scheduled upload jobs at its first start
(`UploadModule.stopForOfflineEdition`); it keeps the device id and token so that the Karte edition
can still delete the data on the server later. Delete your server data in the Karte edition
*before* switching if you want it gone at once; otherwise the server deletes it by itself 60 days
after the last upload (see «Silent devices are deleted» below).

## Guarantees (app, since v0.1)

1. **Raw audio never touches disk.** No code path writes samples to a file, a database, the
   cache, shared preferences or the clipboard.
2. **Raw audio never leaves the app process.** There is no code that sends audio anywhere. Up to
   v0.4.0, and in the offline edition «Weniger Lärm» from v0.5.0, the manifest has no `INTERNET`
   permission at all. **From v0.5.0** the Karte edition has `INTERNET` for exactly one purpose:
   the opt-in upload «Messwerte teilen» (since 0.6.0 including «Laut! Melden»; see
   [«Messwerte teilen»](#messwerte-teilen-opt-in-upload-from-v050) below), which is **off until you
   switch it on**, sends only the numbers and reports listed there, to one host
   (`api.lessnoise.ch` since 0.6.0, before `api.stadtlaerm.ch`), through one class
   (`upload/UploadClient.kt`). Otherwise data leaves the phone only when *you* export a CSV/JSON
   file and share it through the Android share menu. «Nach Update suchen» (since v0.3.1) only hands
   a link to the browser; see "The website" below.
3. **Raw audio is never logged.** The only log line in the audio path reports an exception
   class name when the microphone cannot be opened.
4. **Raw audio is held only briefly, in memory, in small fixed-size buffers.** The largest is
   one second of 16 kHz audio for the sound-source classifier (see the table below). Every
   buffer is overwritten continuously and cleared when measurement stops.
5. **Only aggregates are stored:** levels (LAeq, LAFmax, LAFmin, L1/L10/L50/L90), event
   statistics (start, duration, LAFmax, SEL, background level), classifier category shares,
   the top-3 AudioSet label *names and scores* per event, calibration records, and (0.6.0) the
   start, stop and — for a shared measurement — the 100 m cell of each measurement. None of these
   can be turned back into audio.
6. **No analytics, no crash reporting, no cloud backup.** `android:allowBackup="false"`, and
   the data-extraction rules exclude everything from cloud backup and device transfer. No
   third-party SDK talks to the network: the upload uses Android's own `HttpURLConnection`.

## Where audio is handled in the code

| Buffer | Size | Code |
|---|---|---|
| AudioRecord internal buffer (owned by Android) | ≈ 0.5 s | `android/app/src/main/kotlin/ch/lessnoise/app/audio/AudioCapture.kt` (`build`) |
| Capture block (reused, cleared on stop) | 125 ms (6000 samples) | `AudioCapture.kt` (`loop`) |
| A-weighting / Fast integrator state | a few numbers, not audio | `android/dsp/.../FrequencyWeighting.kt`, `TimeWeighting.kt` |
| Anti-aliasing filter history | 241 samples (5 ms) | `android/dsp/.../Resampler.kt` (`DecimatingResampler`) |
| Classifier ring buffer, 16 kHz | 1 s (16000 samples) | `android/dsp/.../Resampler.kt` (`FloatRingBuffer`), owned by `MeasurementEngine.kt` |
| Classifier window copy (cleared after each inference) | 0.975 s (15600 samples) | `android/app/.../service/MeasurementService.kt` (`onBlock`) |
| Model input tensor (zeroed after each inference) | 0.975 s | `android/app/.../classify/YamnetClassifier.kt` |
| Calibration tone analysis frame | 8192 samples (0.17 s) | `android/dsp/.../calibration/Calibration.kt` (`CalibrationMeasurement`) |

Everything downstream of these buffers is numbers: `LafTick`, `SecondResult`, `MinuteRecord`,
`NoiseEvent` in `android/dsp/src/main/kotlin/ch/lessnoise/dsp/Records.kt`, and the Room entities in
`android/app/src/main/kotlin/ch/lessnoise/app/data/Database.kt` (which have no column that could hold
audio).

## What the classifier output reveals

The classifier stores which *kind* of sound was present (e.g. "Speech", "Motorcycle") and how
confident it was. It does not store what was said. Still, be aware that a log saying "Speech,
23:41" reveals that someone was talking near the phone at that time. Treat exported files
accordingly before you share them.

## How to verify

- `aapt2 dump permissions <apk>` (Android SDK build-tools) lists exactly:
  - **`stadtlaerm.apk`** (offline edition «Weniger Lärm», and every version up to v0.4.0):
    `RECORD_AUDIO`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`, `POST_NOTIFICATIONS`,
    `WAKE_LOCK` (plus AndroidX's internal `ch.lessnoise.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`).
    No `INTERNET`, no `ACCESS_NETWORK_STATE`, no `ACCESS_FINE_LOCATION`, no `ACCESS_COARSE_LOCATION`.
  - **`stadtlaerm-karte.apk`** («Weniger Lärm · Karte», from v0.5.0): the same plus `INTERNET` and
    `ACCESS_NETWORK_STATE`, both only for «Messwerte teilen» and «Laut! Melden» (below), and
    `ACCESS_FINE_LOCATION` and `ACCESS_COARSE_LOCATION`, only for the two location uses (below).
  - Neither has `ACCESS_BACKGROUND_LOCATION` (removed in `app/src/main/AndroidManifest.xml`) or a
    Wi-Fi-state, boot or storage permission. `aapt2 dump badging` shows
    `package: name='ch.lessnoise.app'` for both (up to 0.5.1: `ch.stadtlaerm.app`).
- Search the source of the public app: `grep -rn "FileOutputStream\|openFileOutput\|Socket\|HttpURLConnection\|openConnection\|URL(" android/app/src/main android/app/src/public android/dsp/src/main`
  finds network code in exactly one file, `android/app/src/main/kotlin/ch/lessnoise/app/upload/UploadClient.kt`
  (the upload), and nothing in the audio path (`android/app/src/labor/` is the separate Labor build, see below). The only file writes are the CSV/JSON exports in
  `android/app/.../data/Repositories.kt` and `android/app/.../ui/CalibrationViewModel.kt`, which contain aggregates only.

## «Messwerte teilen» (opt-in upload, from v0.5.0)

The shared noise map ([server/DESIGN.md](server/DESIGN.md), its §2 is the privacy contract) is
fed by phones whose owners switched on **Einstellungen → Messwerte teilen**. Since 0.6.0 the unit
is a **measurement** (one run from «Start» to «Stop», any time of day, possibly over several days),
and each shared measurement has its own place. The rules, and where the code keeps them:

| Rule | How |
|---|---|
| **Off by default**, one explicit opt-in | `UploadSnapshot.enabled = false`; the switch is enabled only after the explanation screen was read (`Uploader.enable()`, `ui/ShareScreen.kt`), or after you say yes to the short consent that the first «Laut! Melden» shows while sharing is off (time, exact place of the report, category; never audio; deletable with «Daten auf dem Server löschen»). Switching off cancels every scheduled job at once (`UploadModule.applySchedule()`), drops reports not yet sent (with their coordinates), and a running upload stops before its next request. |
| **Place per measurement** (0.6.0) | With sharing on, «Messung starten» takes **one** location fix and turns it into the 100 m cell (LV95 hectare id, e.g. `h26833_12479`) on the phone; the measurement row stores only that cell (`data/Database.kt` → `MeasurementEntity.cell`). If the fix fails, the app asks once: try again, paste coordinates (turned into the cell the same way, `upload/CellInput.kt`), or start this measurement without sharing. A measurement without a cell is never sent: neither its minutes nor its events. Measurements recorded before 0.6.0 or while sharing was off have no cell. |
| **What is sent** | The minute records (levels, percentiles, valid time, event count, category shares, calibration id/offset, audio source, clock corrections) and event records (start, duration, LAFmax, SEL, background, threshold, floor, category and its score, calibration) of `upload/Payloads.kt`, **each with the `cell` of its measurement** (0.6.0) — so the loud moments of a shared measurement appear on the map at that cell; plus the placement of §4.2 (the cell of the newest shared measurement, placement, floor, street side, phone model, audio source, calibration, optional note); plus «Laut! Melden» reports (below). Exactly the bodies of DESIGN.md §4. Events flagged as wind on the microphone are not sent (the app leaves them out of its own counts too), and a minute's event count is the count without wind. |
| **«Laut! Melden»** (0.6.0, only while no measurement runs) | You pick what was loud (Töff, Auto, Lastwagen, Poser, anderes); the app takes **one** precise fix (accuracy ≤ 50 m, else it says so and offers another try) and shows «Meldung: …, Uhrzeit, Standort ± n m – senden?». Only after «Senden» is the report queued: time of the tap (to the second, with the phone's UTC offset), **exact latitude/longitude (5 decimals, about 1 m)**, the accuracy in metres and the category, sent to `POST /v1/devices/{id}/reports`. This is the one case where the app sends exact coordinates. They exist on the phone only in the pending queue (a private SharedPreferences file, excluded from backup, `upload/Reports.kt`) until the server has answered for that report; then they are deleted, and only category, time and cell stay in a local list of sent reports. The server rounds the time for publication (DESIGN.md §4). |
| **What is never sent** | Audio (none exists). The top-3 raw AudioSet labels of events (`topLabelsJson` is not read by `Payloads`). The `orig_*`/re-evaluation columns. The detector-v2 features of events and minutes (local floor, excess, rise/decay, LF share and flutter, shape, wind flag, wind event count). The coordinates of the fix at «Start» or of pasted coordinates: only the cell is kept and sent. Exact coordinates only with a report you confirmed. No name, account, phone number, advertising id or Android id. |
| **Location** | Only the Karte edition (and the internal `laborKarte`) has `ACCESS_FINE_LOCATION` and `ACCESS_COARSE_LOCATION`; it asks for them only at «Messung starten» with sharing on, or at «Laut! Melden». Each time it gets **one** fix from Android's own `LocationManager` (no Google Play Services; GPS and network provider, whichever are on; `getCurrentLocation`, on Android 8–10 one update; at most 30 s, cancellable; a last-known location only if younger than 2 min and accurate to ≤ 50 m) while the app is open. At «Start» the fix becomes the cell at once (`location/LocationPolicy.decide` → `CellInput.fromLatLon` → `Lv95.cellId`) and is accepted only with «Genauer Standort» and an accuracy of ≤ 75 m; its coordinates live only in local variables of `location/LocationFetcher.kt` and `LocationPolicy.decide`: not in the settings, not in the database, not in a log, not in the upload. For a report (`LocationPolicy.decideReport`, ≤ 50 m) the coordinates go into the report queue described above. Never in the background: no `ACCESS_BACKGROUND_LOCATION`, no location request outside these two moments. The offline edition and the Labor build have no location permission (removed in their manifests). |
| **Where it goes** | One host, `BuildConfig.API_BASE` = `https://api.lessnoise.ch` (0.6.0; before `https://api.stadtlaerm.ch`; the project's server in Switzerland, DESIGN.md §8). HTTPS only (`UploadClient` refuses any other base URL except a loopback test server), redirects are not followed, no cookies, and the User-Agent is just `LessNoise` (no phone model or Android build). |
| **When** | After every full hour, shortly after a measurement stops and shortly after a report (WorkManager), only over Wi-Fi/unmetered networks unless «nur über WLAN» is switched off. At the first opt-in the last 7 days are included (the screen says so) — of shared measurements only; afterwards nothing measured while sharing was off is ever sent. |
| **Identity** | At the first upload the server issues a random device id and a secret token (DESIGN.md §4.1). The token is stored encrypted with a key held in the Android Keystore; the id is never shown on the public map. «Neue Kennung» deletes the server data and starts a fresh id. |
| **Delete means delete** | «Daten auf dem Server löschen» sends `DELETE /v1/devices/{id}` and waits for the server's `204` (sent only after every row is gone — minutes, events, site and reports —, DESIGN.md §2.4); only then are id and token forgotten on the phone, reports not yet sent are dropped, and sharing is switched off. On an error nothing changes and the screen says so. |
| **Silent devices are deleted** | If the server receives nothing from a device for **60 days** (no upload, no other authenticated request), it deletes every row of that device exactly as «Löschen» does, and the map is rebuilt without it (DESIGN.md §2.9, §5; `retention.expire_inactive`, setting `INACTIVE_DELETE_DAYS`). This covers phones that switched to the offline edition, uninstalled the app, or stopped sharing without deleting. Only an anonymous counter (`devices_expired`) goes up by one. If such a phone shares again, the server answers `401`; the app says why and offers «Neue Kennung», which starts over under a fresh id (it does not re-register silently). |

`ACCESS_NETWORK_STATE` is required by Android for background jobs that wait for a network (and
for «nur über WLAN»); it tells the app whether a connection is up and metered, not which network.
`ACCESS_WIFI_STATE` and `RECEIVE_BOOT_COMPLETED` (which WorkManager would add) are removed in the
manifest.

**How to verify:** the grep above (one network file); `android/app/src/test/.../upload/UploadTest.kt`
checks the exact request bodies against a local fake server (no labels, no originals, no
coordinates, the measurement's `cell` on every minute and event, nothing from a measurement
without a cell, gzip, bearer token, the fixed User-Agent, nothing sent while off);
`android/app/src/test/.../upload/ReportsTest.kt` checks the exact report body and that a report's
coordinates are gone from the phone once the server answered (also when sharing is switched off or
the server data is deleted); `android/app/src/test/.../location/LocationIsolationTest.kt` checks
that only `public` and `laborKarte` declare the location permissions (`offline` and `labor` remove
them, background location is removed everywhere) and that the location code logs, stores and
sends nothing, with `android.location` used in exactly one file; `LocationPolicyTest.kt` checks
the lat/lon → cell path and when a fix is accepted; and on a phone, Android's per-app data usage
(on a Pixel: *Settings → Apps → Weniger Lärm → Mobile data & Wi-Fi*) shows the app's traffic,
which stays at zero while sharing is off.

## Labor-Build

Since v0.3.3 the Android app has product flavours in the Gradle dimension `edition`; since
v0.5.0 three of them (`offline`, `public`, `labor`), plus the internal `laborKarte` described
[below](#internal-variant-laborkarte-weniger-lärm-labor--karte):

| | `offline` and `public` (the two published editions) | `labor` |
|---|---|---|
| App id / name | `ch.lessnoise.app`, «Weniger Lärm» (0.6.0; before `ch.stadtlaerm.app`, «Stadtlärm») | `ch.lessnoise.labor`, «Weniger Lärm Labor» (red icon; before `ch.stadtlaerm.labor`) |
| Published | yes: `stadtlaerm.apk` and `stadtlaerm-karte.apk` in `docs/download/` on wenigerlaerm.ch | **never**: not in `docs/`, not in a release, not linked from the website |
| Can record audio | no — the code does not exist in these apps | yes, only after switching it on, see below |

The Labor build exists to debug the event detector and the sound-source classifier with real
recordings. It is a separate app with its own app id (installed side by side, its own data); it
is signed with the same key only so that it can be updated.

**How the public app is kept free of recording code.** Android Gradle compiles a variant only
from `app/src/main/` plus the source set of its flavour. All code that writes audio — the clip
assembler, the WAV and AAC writers, the manifest, the Labor UI — lives exclusively in
`app/src/labor/` (`ch.lessnoise.app.labor.*` and the Labor `ch.lessnoise.app.edition.*`), so it
is not compiled into either published APK at all (the `offline` flavour adds only
`app/src/public/kotlin` to its sources, not `app/src/labor/`; only the unpublished `laborKarte`
adds `app/src/labor/kotlin` and `app/src/labor/res`, to itself). The shared code only contains:

- `app/src/main/kotlin/ch/lessnoise/app/audio/AudioTap.kt`: an interface through which a build
  *could* see the capture blocks and the event lifecycle, and `NoAudioTap`, which does nothing;
- `app/src/public/kotlin/ch/lessnoise/app/edition/Edition.kt`: the public `AudioTapProvider`,
  which always returns `NoAudioTap`, and empty UI hooks;
- in `MeasurementEngine`, listener hooks that report sample indices of event start/confirmation/
  end, the clock anchor and each classifier result (label names, scores, gain and level — numbers
  only; the default implementations do nothing).
- in the chart (since v0.3.4), an optional clip reference per event and an optional «Abspielen»
  callback. The public `EditionClips` provides no references and no callback, so the public chart
  never shows a play button; the clip index and the player (MediaPlayer) live in `app/src/labor/`.

The Labor build has no network access: its manifest (`app/src/labor/AndroidManifest.xml`) removes
`INTERNET` and `ACCESS_NETWORK_STATE`, and «Messwerte teilen» does not exist in it
(`BuildConfig.UPLOAD_AVAILABLE = false`). No storage permission in either build. This is the build
for a phone that measures in someone else's home: it cannot send anything.

**How to verify** (on **both** published APKs: `app/build/outputs/apk/offline/release/app-offline-release.apk`
= `stadtlaerm.apk` and `app/build/outputs/apk/public/release/app-public-release.apk` = `stadtlaerm-karte.apk`;
the commands below use the second name, run them on each):

1. Source: `find android/app/src -path '*labor*'` lists the only recording code;
   `grep -rln "FileOutputStream\|MediaMuxer\|MediaCodec\|RIFF" android/app/src/main android/app/src/public android/dsp/src/main`
   finds nothing; the only file writes there are the CSV/JSON exports (`writeText` in
   `Repositories.kt` and `CalibrationViewModel.kt`), which contain aggregates only.
2. The built APK: list its classes and strings, e.g. with the Android SDK (cmdline-tools,
   build-tools 35)
   ```sh
   apkanalyzer dex packages app-public-release.apk | grep -c 'ch.lessnoise.app.labor'   # 0
   unzip -o app-public-release.apk 'classes*.dex' -d dex/
   for d in dex/classes*.dex; do dexdump "$d" | grep 'Class descriptor'; done \
     | grep -iE 'labor|recorder|wav|aac|muxer|clipassembler'                              # nothing
   for d in dex/classes*.dex; do strings "$d"; done \
     | grep -E 'Landroid/media/MediaMuxer;|Landroid/media/MediaCodec;|RIFF|WAVE|manifest\.jsonl|ch/lessnoise/app/labor/'   # nothing
   ```
   For v0.3.3 this was checked on the signed public release APK (the one then on stadtlaerm.ch,
   SHA-256 `4fad06307d29f4ee088fdc4653acf48a39b29091a85214e7a0ee8d6b72fb0ac9`): `apkanalyzer` 0, `dexdump` lists 13401 classes and none of them
   matches, and no dex string references `MediaMuxer`, `MediaCodec`, `RIFF`/`WAVE`,
   `manifest.jsonl` or the `labor` package. As a control, the same commands on the Labor APK find
   828 `ch.stadtlaerm.app.labor` entries and 98 matching strings (the package was renamed to
   `ch.lessnoise.app.labor` in 0.6.0; use that name for 0.6.0 and later).
3. `aapt2 dump permissions` lists the permissions of «How to verify» above for each published APK;
   for the Labor APK the offline edition's set (no `INTERNET`, no `ACCESS_NETWORK_STATE`, no
   location); for the internal `laborKarte` APK the Karte edition's set (with `INTERNET`,
   `ACCESS_NETWORK_STATE`, `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`), package `ch.lessnoise.labor`.

**What the Labor build records** (only after «Einstellungen → Labor → Audio während der Messung
aufzeichnen» is switched on and confirmed; off by default): event clips (WAV, 16 kHz, 5 s before to
5 s after each event, at most 60 s) and/or a continuous recording (AAC, 16 kHz, 64 kbit/s, one file
per hour), plus a `manifest.jsonl` with the event data and the classifier's per-second results for
each clip, and `classifier/*.jsonl` with every classifier run (label names and scores, no audio). Files stay on the phone in
`Android/data/ch.lessnoise.labor/files/audio/` until deleted in the app («Alle Aufnahmen löschen»)
or by uninstalling it; they leave the phone only if the user shares them («Als ZIP teilen») or
copies them over USB. A red banner on every screen says «WENIGER LÄRM LABOR – kann Audio aufzeichnen»
(and «Aufnahme läuft» while recording). The Labor app has no `INTERNET` and no location permission
either (the internal `laborKarte` variant below has them, for «Messwerte teilen» and «Laut! Melden» only).
Recordings can contain voices of people nearby; use the Labor build only where everyone recorded
knows about it.

### Internal variant `laborKarte` («Weniger Lärm Labor · Karte»)

Since v0.5.0 the repository can also build the Labor app **with** «Messwerte teilen», for the
maintainer's own test phone (flavour `laborKarte`, `./gradlew assembleLaborKarteRelease`):

- **Never published**: not on the website, not in a GitHub Release, not on the `test-builds`
  branch, not in `docs/`. Same app id as the Labor build (`ch.lessnoise.labor`, version name
  `<version>-labor-karte`, e.g. `0.6.0-labor-karte`), so it installs over a Labor install and keeps its measurements and
  recordings; installing the plain Labor build over it switches «Messwerte teilen» off and cancels
  the upload jobs (as the offline edition does after the Karte edition), and from then on the
  phone has no internet permission again.
- **Internet only for the opt-in upload**: its manifest (`app/src/laborKarte/AndroidManifest.xml`)
  declares `INTERNET` and `ACCESS_NETWORK_STATE`, and `ACCESS_FINE_LOCATION` and
  `ACCESS_COARSE_LOCATION` for the fix at «Start» and «Laut! Melden», exactly like the Karte edition; it does not use
  `app/src/labor/AndroidManifest.xml`. «Messwerte teilen» is off until switched on. As in the
  Karte edition, the very first switch-on also sends the measurement values of the last 7 days,
  here only of shared measurements, i.e. those started with sharing on (values only, never audio).
- **Same upload code as the Karte edition**: `app/src/main/kotlin/ch/lessnoise/app/upload/`, one
  host (api.lessnoise.ch), the same measurement values (§2 of server/DESIGN.md). No flavour has
  its own upload classes.
- **Audio is never sent.** The upload reads only the Room tables (minutes, events, measurements, the
  active calibration), the site settings and the report queue; it has no code that opens a file, and the Labor recordings
  are files (`Android/data/ch.lessnoise.labor/files/audio/`). The unit test
  `app/src/test/kotlin/ch/lessnoise/app/upload/UploadSourceIsolationTest.kt` (runs in every
  flavour) fails if the upload package uses a file API (`java.io.File`, file streams, `filesDir`,
  cache dirs, `ContentResolver`, assets, `MediaStore` …), an audio class, the Labor package
  `ch.lessnoise.app.labor` or a recording folder name, if it imports a project class that is not
  on its allow-list, if a flavour adds upload classes, if the Labor code touches the upload or the
  network, or if network code appears anywhere but `upload/UploadClient.kt`. Recordings still
  leave the phone only when the user shares them («Als ZIP teilen») or copies them over USB, as in
  the Labor build.
- The banner says «WENIGER LÄRM LABOR · KARTE – kann Audio aufzeichnen»; Einstellungen → App-Version
  shows «Weniger Lärm Labor · Karte». The server counts its `app_version` (e.g. `0.6.0-labor-karte`) under
  «andere» in `stats.json` (it does not match the server's version pattern).

## The website

[wenigerlaerm.ch](https://wenigerlaerm.ch) (source in `docs/`) is static HTML and CSS. It sets no
cookies, runs no analytics and loads nothing from third parties (except the map page, below): no
web fonts, no CDNs, no external images. The only inline script is on `update.html` (allowed by its hash in the
Content-Security-Policy): the app opens `update.html#v=<version>&c=<versionCode>&e=<edition>`
(`e` since v0.5.0: `offline` or `karte`; the Labor builds send no `e`), the browser does not send the part after `#` to the
server, and the script compares it with the published version locally and offers the download of
the same edition. It is hosted on GitHub Pages; GitHub may log visitors' IP addresses for
technical reasons, see the
[GitHub General Privacy Statement](https://docs.github.com/site-policy/privacy-policies/github-general-privacy-statement).
The German privacy page is [docs/datenschutz.html](docs/datenschutz.html).

**Downloads.** Both APKs are served from the website itself (`docs/download/stadtlaerm.apk` =
«Weniger Lärm», `docs/download/stadtlaerm-karte.apk` = «Weniger Lärm · Karte»), i.e. by GitHub Pages like
every page of the site. GitHub sees the requesting IP address as for any page view; the project
counts nothing. (`scripts/download_stats.py` reads GitHub Release download counts and only has
something to report if a version is ever also attached to a GitHub Release.)

**The map page is the one exception.** `docs/karte.html` (the shared noise map, see
[server/DESIGN.md](server/DESIGN.md) §7) loads map tiles from swisstopo
(`wmts.geo.admin.ch`) and the published per-hectare aggregates from the project's own API host
(`api.lessnoise.ch`), and nothing else; its Content-Security-Policy allows exactly these two
hosts (`img-src` and `connect-src`) and scripts only from the site itself (vendored Leaflet,
no inline script). It says so in one sentence above the map, and `datenschutz.html` repeats it.
Both hosts necessarily see the visitor's IP address; the page sends no referrer and sets no
cookies, and the API server keeps no access log. All other pages still load nothing from third
parties.

## Usage numbers

The project wants to know whether the app is used, without learning anything about anyone. It
counts in one place, public, totals over the whole project. Downloads are not counted: the APKs
are served from the website (`docs/download/`), and GitHub Pages reports nothing to the project.
(`scripts/download_stats.py` would read GitHub Release download counts if a version is ever also
attached to a GitHub Release.)

1. **Sharing**, from the server's own data: `https://api.lessnoise.ch/v1/map/stats.json`
   ([server/DESIGN.md](server/DESIGN.md) §6.4), rebuilt with the map — how many devices are
   registered, have set a hectare, ever shared, were active in the last 7/30 days, deleted their
   data (one counter), were deleted by the server after 60 days without uploads (one counter), how many hectares are on the map, how many device-nights were shared, how
   many active devices reported which app version at registration, and registrations per ISO week.
   Counts only: no device ids, no cells or places, no IP addresses (the server stores none, and
   neither Caddy nor the app server keeps an access log). Nothing new is collected for it; the only
   numbers kept just for it are the two deletion counters, one integer each.

**Not counted:** downloads, and anything about the offline edition — it has no internet
permission and cannot report anything, by design; the Karte edition with sharing switched off;
app starts, measurements, crashes or versions of apps that do not share; downloads of
`docs/download/stadtlaerm.apk`. `scripts/usage_report.py` combines both sources into one short
text report.
