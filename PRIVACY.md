# Privacy — what Weniger Lärm does with sound

Weniger Lärm (until 0.5.1 «Stadtlärm») measures noise levels. It never records audio. This is the
core promise of the project, and this page explains how the code keeps it, so anyone can check.

Everything on this page is about the **public app** (`ch.lessnoise.app` since 0.6.0, before
`ch.stadtlaerm.app`; the APKs linked from wenigerlaerm.ch). It is published in two editions,
the **«Online-Version»**, the default download since 0.6.2 (internet only for the opt-in «Messwerte
teilen» and «Laut! Melden» and, since 0.6.3, for showing the website's map in the tab «Karte» while
it is open — since 0.8.0 also for swisstopo's map tiles under the arrow of «Laut! Melden»; location
only for one fix right after the start of a shared measurement and for a report; since 0.9.0 no
measurement on the road and no «Sehr laut» notification), and the **«Offline-Version»** for even more privacy (no internet and no location permission
at all, therefore no map and no reports), see [Editions](#editions). Since v0.3.3 the
repository can also build a separate diagnostics app that *can* record audio; it is a different
app, is never published, and neither published edition contains any of its code. See
[Labor-Build](#labor-build) below.

## Editions

| | «Online-Version» | «Offline-Version» |
|---|---|---|
| File (on the website, `docs/download/`) | `lessnoise-karte.apk` — the default download since 0.6.2 | `lessnoise.apk` — for even more privacy (the default download until 0.6.1) |
| Gradle flavour | `public` | `offline` |
| App id, signing key | the same | `ch.lessnoise.app` (0.6.0; before `ch.stadtlaerm.app`), release key |
| `INTERNET`, `ACCESS_NETWORK_STATE` | yes, only for «Messwerte teilen» and «Laut! Melden» (since 0.8.0 including swisstopo's map tiles around your true position — since 0.9.0 also around a place you move the map to — while the report dialog is open; 0.8.0: the step «Wohin fuhr es?»; see the «Laut! Melden» row below), and (0.6.3) for the tab «Karte», which shows the map page of wenigerlaerm.ch while it is open (see [The tab «Karte»](#the-tab-karte-063)) | **no** (removed in `app/src/offline/AndroidManifest.xml`) |
| `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` | yes, only while the app is open: one fix right after the start of a shared measurement (since 0.6.3 while it already measures; never for a measurement started with «Unterwegs», 0.6.4–0.8.0; since 0.9.0 — 0.8.4 in the internal `laborKarte` build — there is no such measurement in the Online-Version) (moved at random on the phone by the location noise, about 50 m by default; only the shifted point is stored and sent, never the fix) and one fix per «Laut! Melden» report (since 0.9.0 read as soon as the report dialog opens, also if you then cancel; sent only with that report, then deleted from the phone) | **no** (removed in `app/src/offline/AndroidManifest.xml`) |
| `ACCESS_BACKGROUND_LOCATION` | **no** — location is never read in the background | no |
| «Messwerte teilen», «Laut! Melden» | off until you say yes («Auf die Lärmkarte?» at the first start of a measurement — «Langzeitmessung starten» since 0.9.0, «Starten» before —, the switch in «Teilen», or «Ja, einschalten» at the first report; one switch for both) | do not exist (`BuildConfig.UPLOAD_AVAILABLE = false`) |
| Tab «Karte» (0.6.3) | yes: the website's map page, loaded when the tab is opened (not in the background) | does not exist (the build contains no map screen and no WebView) |
| Measurement on the road («Unterwegs», «Sehr laut» notification) | **no** since 0.9.0 (0.8.4 in the internal `laborKarte` build): a measurement is always the one at the window; on the road you only report («Laut! Melden»). The published Online-Version 0.6.3–0.8.0 had it; its code is now in the internal `laborKarte` build only (`app/src/laborKarte/…/alert/`, see «Internal variant `laborKarte`») and not in this APK | no |
| App shortcut and widget «Laut! Melden» (0.9.0; 0.8.4 in `laborKarte`) | yes: a tap only opens the report dialog; they send nothing and read nothing by themselves | do not exist |
| Data leaves the phone | the same, plus the opt-in upload | only when *you* export it (CSV; calibrations as JSON) and share it |
| Can report anything (usage, crashes, versions) | only what «Messwerte teilen» sends, below | **no** — it has no way to |

Both editions are built from the same Kotlin sources (`app/src/main` and `app/src/public/kotlin`)
and contain no recording code. Because they share the app id and the key, installing one over the
other switches the edition and keeps the measurements. (0.6.0 changed the app id from
`ch.stadtlaerm.app` to `ch.lessnoise.app`: Android treats it as a new app, so 0.6.0 does not
install over 0.5.x and starts with empty storage.) The offline edition still contains the compiled
upload and location classes (shared code), but they can never run: every entry point returns when
`UPLOAD_AVAILABLE` is false, neither the settings card nor the «Laut! Melden» button exists, without
`INTERNET` Android would refuse the socket, and without `ACCESS_FINE_LOCATION`/`ACCESS_COARSE_LOCATION`
in its manifest Android would deny the permission without asking. When the offline edition is
installed over the Online-Version with sharing on, it switches sharing off, drops reports not yet
sent (with their coordinates) and cancels the scheduled upload jobs at its first start
(`UploadModule.stopForOfflineEdition`); it keeps the device id and token so that the Online-Version
can still delete the data on the server later. Delete your server data in the Online-Version
*before* switching if you want it gone at once; otherwise the server deletes it by itself 60 days
after the last upload (see «Silent devices are deleted» below).

## Guarantees (app, since v0.1)

1. **Raw audio never touches disk.** No code path writes samples to a file, a database, the
   cache, shared preferences or the clipboard.
2. **Raw audio never leaves the app process.** There is no code that sends audio anywhere. Up to
   v0.4.0, and in the offline edition («Offline-Version») from v0.5.0, the manifest has no `INTERNET`
   permission at all. **From v0.5.0** the map edition (since 0.6.2 «Online-Version») has `INTERNET`,
   until 0.6.2 for exactly one purpose, since 0.6.3 for two and since 0.8.0 for three, each in one place of the code:
   (1) the opt-in upload «Messwerte teilen» (since 0.6.0 including «Laut! Melden»; see
   [«Messwerte teilen»](#messwerte-teilen-opt-in-upload-from-v050) below), which is **off until you
   say yes**, sends only the numbers and reports listed there, to one host
   (`api.lessnoise.ch` since 0.6.0, before `api.stadtlaerm.ch`), through one class
   (`upload/UploadClient.kt`); (2) since 0.6.3 the tab **«Karte»**, which shows the map page of
   wenigerlaerm.ch in a WebView once you open the tab — like a browser it loads the page, swisstopo's
   map tiles and the published map files, and it sends no measurement and no location (see
   [The tab «Karte»](#the-tab-karte-063); `app/src/karte/.../ui/MapScreen.kt`, only in the editions
   with the map); (3) since 0.8.0, in «Laut! Melden», while the report dialog is open (0.8.0: the step «Wohin fuhr
   es?»), swisstopo's map tiles for the area around the report's location fix and, since 0.9.0, around
   a place you move the map to (at most 2 km away), to draw the streets under the arrow — the tile
   requests tell swisstopo those areas, with your IP address, and send nothing else
   (see the «Laut! Melden» row below; `app/src/karte/.../ui/ReportMapTiles.kt`, only in the editions
   with the map). Otherwise data leaves the phone only when *you* export a CSV/JSON
   file and share it through the Android share menu. «Update prüfen» (since v0.3.1; until 0.6.0
   «Nach Update suchen») and the «Mehr dazu» links (since 0.6.1, to pages of wenigerlaerm.ch) only
   hand a link to the browser; see "The website" below.
3. **Raw audio is never logged.** The only log line in the audio path reports an exception
   class name when the microphone cannot be opened.
4. **Raw audio is held only briefly, in memory, in small fixed-size buffers.** The largest is
   one second of 16 kHz audio for the sound-source classifier (see the table below). Every
   buffer is overwritten continuously and cleared when measurement stops.
5. **Only aggregates are stored** (of sound; the one other thing the app can keep is the optional
   photo of a vehicle you take for a report, since 0.9.0 in the Online-Version — an image from the
   phone's camera app, never sound, kept in the app's private storage and never sent; see «Laut!
   Melden» below): levels (LAeq, LAFmax, LAFmin, L1/L10/L50/L90), event
   statistics (start, duration, LAFmax, SEL, background level), classifier category shares,
   the top-3 AudioSet label *names and scores* per event, calibration records, and (0.6.0) the
   start, stop and — for a shared measurement — the shifted point of each measurement (see
   «Location noise» below). None of these can be turned back into audio.
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
  - **`lessnoise.apk`** (offline edition, «Offline-Version»; until 0.6.1 named `stadtlaerm.apk`, which up to v0.4.0 was the only edition):
    `RECORD_AUDIO`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`, `POST_NOTIFICATIONS`,
    `WAKE_LOCK` (plus AndroidX's internal `ch.lessnoise.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`).
    No `INTERNET`, no `ACCESS_NETWORK_STATE`, no `ACCESS_FINE_LOCATION`, no `ACCESS_COARSE_LOCATION`.
  - **`lessnoise-karte.apk`** («Online-Version», from v0.5.0): the same plus `INTERNET` and
    `ACCESS_NETWORK_STATE`, both only for «Messwerte teilen» and «Laut! Melden» and (0.6.3) the tab
    «Karte» (below; the tab adds no permission), and
    `ACCESS_FINE_LOCATION` and `ACCESS_COARSE_LOCATION`, only for the two location uses (below).
    **No `CAMERA`** (since 0.9.0 the report dialog's optional photo is taken by the phone's own camera
    app, which needs no permission of this app).
  - Neither has `ACCESS_BACKGROUND_LOCATION` (removed in `app/src/main/AndroidManifest.xml`) or a
    Wi-Fi-state, boot, storage or camera permission. `aapt2 dump badging` shows
    `package: name='ch.lessnoise.app'` for both (up to 0.5.1: `ch.stadtlaerm.app`).
- `python3 android/tools/check_release_apk.py <offline|public> <apk> [--version … --code …] [--cert-sha256 …]`
  (0.8.0; merged with the 0.8.4 Labor·Karte rules in 0.9.0; needs `apksigner`, `aapt2` and `dexdump`
  from the build-tools; GitHub Actions runs it on every release build) checks the signer, the version,
  that the permissions are exactly the sets above (so no `CAMERA`), the manifest and the dex of each
  APK: neither published APK contains Labor classes (`ch/lessnoise/app/labor/*`, recorder/encoder class
  names) or the Labor audio encoding, nor ML Kit, Google Play services or Firebase classes; the
  offline one contains no map tab (`MapScreen*`, no `ch.lessnoise` class referring to the WebView), no
  motion detector, no tile download and no shortcut or widget; **since 0.9.0 neither contains the
  «Sehr laut» / «Unterwegs» classes `ch/lessnoise/app/alert/*` or the string `loud_alert`** (until
  0.8.0 the Online-Version had them); the Online-Version contains the map tab, the motion detector,
  the tile download and the shortcut and widget (`ch/lessnoise/app/report/*`).
- Search the source of the public app: `grep -rn "FileOutputStream\|openFileOutput\|Socket\|HttpURLConnection\|openConnection\|URL(" android/app/src/main android/app/src/public android/dsp/src/main`
  finds network code in exactly one file, `android/app/src/main/kotlin/ch/lessnoise/app/upload/UploadClient.kt`
  (the upload), and nothing in the audio path; since 0.6.3 the only other network code is the WebView of
  the tab «Karte», `android/app/src/karte/kotlin/ch/lessnoise/app/ui/MapScreen.kt` (`grep -rn WebView android/app/src`),
  and, since 0.8.0 (0.7.8 in the internal `laborKarte`), the tile download of «Laut! Melden» (0.8.0: of the step «Wohin fuhr es?»; since 0.9.0 also around a place moved under the dot),
  `android/app/src/karte/kotlin/ch/lessnoise/app/ui/ReportMapTiles.kt` (one address template on
  `wmts.geo.admin.ch`, GET only; see the «Laut! Melden» row below);
  both are compiled into the Online-Version and the internal `laborKarte` only (`android/app/src/labor/` is the separate Labor build, see below). The only file writes are the CSV/JSON exports in
  `android/app/.../data/Repositories.kt` and `android/app/.../ui/CalibrationViewModel.kt`, which contain aggregates only
  (and, since 0.9.0 — 0.8.5 in `laborKarte` —, the «Laut! Melden» photo: the phone's camera app writes it into `cache/camera/`, the
  app re-encodes it into its private `files/reports/` and deletes the original; `ui/ReportPhotos.kt`,
  below; 0.8.2–0.8.4, `laborKarte` only: the «Kontrollschild» photo in `cache/plate/`, deleted unread).

## «Messwerte teilen» (opt-in upload, from v0.5.0)

The shared noise map ([server/DESIGN.md](server/DESIGN.md), its §2 is the privacy contract) is
fed by phones whose owners switched on «Messwerte teilen» — since 0.6.1 the tab **«Teilen»** with the
switch «Auf die Karte senden» (until 0.6.0 Einstellungen → Messwerte teilen; this page keeps the
feature's old name «Messwerte teilen»). Since 0.6.0 the unit
is a **measurement** (one run from «Start» to «Stop», any time of day, possibly over several days),
and each shared measurement has its own place. The rules, and where the code keeps them:

| Rule | How |
|---|---|
| **Off by default**, one explicit opt-in | `UploadSnapshot.enabled = false`. Since 0.6.3 the opt-in is **one question**, shown once at the first start of a measurement («Starten»; since 0.9.0 «Langzeitmessung starten») (or when you turn on the switch «Auf die Karte senden» in «Teilen» before that): **«Auf die Lärmkarte?»** with three lines — «Gesendet: Lärmwerte pro Minute und Lärmspitzen von Motoren und Maschinen, mit einem um etwa 50 m verschobenen Punkt statt deines Standorts – an unseren Server in der Schweiz.» / «Nie gesendet: Audio, Name, dein genauer Standort (ausser bei «Laut! Melden»).» / «Ausschalten und löschen jederzeit unter «Teilen»; nach 60 Tagen ohne neue Werte löscht der Server alles.» — and «Mehr dazu», which unfolds the full explanation (what is sent, the location rule including the location noise and what it does not hide, what is never sent, where it goes including that a 100 m square where nobody else measures shows your values near your home, when, how to delete). **«Ja, auf die Karte»** switches sharing on; **«Nur auf dem Telefon»** keeps it off; going back starts nothing (`ui/ShareFlow.kt`, texts in `ui/ShareTexts.kt`, the answer stored as `UploadSnapshot.shareDecision`). It is not asked again; the switch in «Teilen» changes the answer. Until 0.6.2 the switch was enabled only after the explanation screen was read («Weiter»); on the update to 0.6.3 a phone with sharing on counts as «yes», all others are asked once. The short consent of the first «Laut! Melden» while sharing is off stays (time, exact place and kind of noise; the point appears on the map, and reporting often from the same spot makes that spot visible; never audio; deletable with «Vom Server löschen»); its «Ja, einschalten» switches on the same switch, which then also shares measurements at the window («Teilen» says so since 0.9.0). Switching off cancels every scheduled job at once (`UploadModule.applySchedule()`), drops reports not yet sent (with their coordinates), and a running upload stops before its next request. |
| **Place per measurement** (0.6.0; shifted since 0.6.2; after the start since 0.6.3) | With sharing on, «Starten» starts the measurement at once and, while the app is open, takes **one** location fix in parallel, turns it into a point of the Swiss LV95 grid and moves it at once by the location noise (next row); the running measurement's row then gets only the shifted point, its σ and the 100 m display cell that contains it (`data/Database.kt` → `MeasurementEntity.pointE/pointN/sigmaM/cell`, `MeasurementDao.attachPoint`), and all of its minutes and events are sent with that point, also those recorded before the fix (`Uploader.pointAttached`). If the app is not in the foreground, no fix is taken; it is tried when you open the app again. If the fix fails, there is no dialog: the line on «Messen» says «Kein Standort – tippen» (since 0.8.3 «Kein Standort ›») and offers «Nochmals», the location or app settings, and «Koordinaten einfügen» (moved the same way, `upload/CellInput.kt` → `LocationPolicy.fromPasted`); until then the measurement is not shared. Switching sharing on during a measurement shares that whole measurement the same way; a measurement that already ended unshared stays unshared (no location is taken for it afterwards). A measurement without a point is never sent: neither its minutes nor its events. A measurement started with **«Unterwegs»** (0.6.4) never gets a point (`MeasurementEntity.unterwegs`, `MeasurementDao.attachPoint` refuses it, `sharedPoint()` is null for it), takes no location fix, and so shares nothing: no minutes, no events. Measurements recorded before 0.6.0 or while sharing was off have no point; neither do measurements made with 0.6.0 or 0.6.1 (they kept only the 100 m cell; after the update to 0.6.2 nothing more of them is sent, what was sent before stays on the server until deleted). |
| **Location noise** (0.6.2) | The point that is sent is the true point plus a random offset: two independent normal draws N(0, σ²) east and north, σ from the setting **«Standort verschieben»** («Teilen» › «Messungen am Fenster»; until 0.8.7 under «Weitere Angaben») — 25, **50 (default)**, 100 or 200 m; there is no «exact» choice. The offset is **the same every time for the same place**: it is derived from HMAC-SHA256 (a keyed hash) of the true 100 m hectare and σ under a secret of 32 random bytes made on the phone at the first shared «Starten» (`upload/NoiseSecret.kt`; stored like the token, encrypted with a key in the Android Keystore; never sent). So averaging many measurements made at the same home ends at the shifted point, not at the home; another hectare gets an independent offset. «Neu starten» and «Vom Server löschen» drop the secret, so afterwards every place gets a new offset. The math is one pure, unit-tested function, `android/dsp/src/main/kotlin/ch/lessnoise/dsp/geo/LocationNoise.kt`. Neither the true point nor the offset is stored. What it does not hide: whoever knows σ knows that the true point is usually within 2σ of the shifted one (about 86 % of cases; with 50 m: within 100 m); a lone contributor's 100 m square on the map is still near their home. |
| **What is sent** | The minute records (levels, percentiles, valid time, event count, category shares, calibration id/offset, audio source — since 0.7.2 with the microphone's kind `+usb`/`+bluetooth`/`+wired` when the user chose a microphone other than the built-in one, never its name or address — clock corrections) and event records (start, duration, LAFmax, SEL, background, threshold, floor, category and its score, calibration) of `upload/Payloads.kt`, **each with the shifted `point` of its measurement** (0.6.2: LV95 easting/northing in whole metres and σ, e.g. `{"e": 2682437, "n": 1247391, "sigma_m": 50}`; 0.6.0–0.6.1 sent the `cell`) — so the loud moments of a shared measurement appear on the map in the 100 m square that contains that point; plus the placement of §4.2 (the shifted point of the newest shared measurement, placement, floor, street side, phone model, audio source, calibration, optional note); plus «Laut! Melden» reports (below). Exactly the bodies of DESIGN.md §4. **Since 0.6.4 the app sends only map events** (`Payloads.isMapEvent`, one rule for what an upload includes and for which event starts an events-only upload; not a setting): category one of the five below, not flagged as wind on the microphone, the excess setting in force (`threshold_db`) at least 6.5 dB, LAFmax at least the event's own floor setting and at least 30 dB(A), and at least 0.5 s long — the server's checks. Events of voices, music, unclassified sounds or wind **never leave the phone**; they stay in the app's own list. No field was added for this: the detector features stay on the phone (below). A minute's event count is the count without wind. The app 0.5.x–0.6.3 sent the events of every category except wind; **the server keeps only machine and motor noise** — `loud_vehicle`, `road_traffic`, `rail_tram`, `aircraft`, `construction` («Töff & Poser», «Strassenverkehr», «Tram & Bahn», «Flugzeug», «Baustelle») — and drops events of any other category («Stimmen», «Musik», «Sonstiges», wind, an unknown id) on arrival, without storing them (since 2026-10-16; below and DESIGN.md §4.4). |
| **«Laut! Melden»** (0.6.0, the button only while no measurement runs; in the Online-Version 0.6.3–0.8.0 also from the «Sehr laut» notification while measuring, since 0.9.0 — 0.8.4 in `laborKarte` — only in the internal `laborKarte` build; since 0.9.0 also from the app shortcut and the widget, below) | You pick what was loud (Töff, Auto, Lastwagen, Flugzeug, Poser, anderes Fahrzeug; «Flugzeug» since 0.7.7; since 0.6.4 «anderes Fahrzeug» instead of «anderes», the id sent is still `andere`); the app takes **one** fix and uses it as the phone has it, at any accuracy (since 0.7.0; until 0.6.6 only ≤ 50 m), with only «Genauer Standort» allowed; without a fix it says so and offers another try. Until 0.7.8 this was three dialogs, and with a fix the last one asked «Meldung: …, Uhrzeit, Standort ± n m – senden?»; only «Senden» queued the report. Since 0.9.0 (0.8.1 in the internal `laborKarte` build) it is **one dialog** «Laut! Melden»: the fix is searched as soon as it opens and shown as «Standort ± n m», **even if you then cancel, the fix is taken** (it is sent only with «Senden»); in 0.8.1 (`laborKarte`) **tapping a category sent the report** (the dialog said «Antippen sendet die Meldung.»). Since 0.8.2 (`laborKarte`; Online-Version 0.9.0) a tap only **selects** the category and **«Senden»** sends it (in `laborKarte`, from the «Sehr laut» notification a single suggested category is preselected, so «Senden» is the one tap); «Abbrechen» sends nothing; «Senden» tapped before the fix is there: the report goes out when it arrives, or not at all if none is found. On every report since 0.6.3 the screen carries the line «Wer oft vom gleichen Ort meldet, macht ihn auf der Karte sichtbar.» (DESIGN.md §2 rule 10; the first-report consent says it too, but it is skipped once sharing is on) — until 0.7.8 in the confirmation, since 0.8.1 in the one dialog under the buttons (since 0.8.2 above «Senden»). The report queued is: time of the tap (to the second, with the phone's UTC offset), **the fix's latitude/longitude, not shifted (5 decimals, about 1 m; the place itself is only as accurate as the fix)**, the accuracy in metres and the category, sent to `POST /v1/devices/{id}/reports`. Since 0.9.0 (0.8.1 in the internal `laborKarte` build; in both editions with the map, where the streets are drawn under the compass) the place sent is the fix, or, if you moved the map under the dot (only inside Switzerland), the place you put the dot (up to 2 km from the fix), sent with a fixed accuracy of 15 m (and its own 100 m cell kept on the phone); moving the map may request further swisstopo tiles around the new centre (below). This is the one case where the app sends exact coordinates. They exist on the phone only in the pending queue (a private SharedPreferences file, excluded from backup, `upload/Reports.kt`) until the server has answered for that report; then they are deleted, and only category, time and the 100 m cell (the approximate place, «ungefährer Ort») stay in a local list of sent reports — and, for reports made with the published Online-Version 0.8.0 (or `laborKarte` 0.7.6–0.8.4), the licence plate if you typed one into the field «Kontrollschild», which since 0.9.0 (0.8.5 in `laborKarte`) no longer exists: still listed, kept on the phone only and **never sent**. **Photo instead of the plate (0.9.0 in the Online-Version; 0.8.5 in `laborKarte`; the code is in `src/main`):** the dialog's button **«Foto»** (since 0.8.6 labelled «Foto vom Fahrzeug (optional)») opens the phone's own camera app at your tap (Android's `ACTION_IMAGE_CAPTURE`; the app has **no camera permission** and declares none — the system camera takes the photo and writes it into a file this app hands it, `cache/camera/`). The app at once re-encodes it (at most 1600 px on the long side, JPEG; written without any EXIF data, so a GPS position the camera app may have stored in the photo is not kept) and deletes the camera's original; the photo is shown as a small picture in the dialog («×» removes it, «Neues Foto» replaces it). With «Senden» it is **stored only in the app's private storage** (`files/reports/`, which no other app can read; excluded from backup like everything else), its file name kept with the report; it is **shown only in your own list** («Teilen» › «Meldungen», a tap shows it large, with «Löschen» for the photo alone) and **never sent**: the report's request has no photo and no file name, and the upload code opens no file at all (`ReportsTest.thePlateAndThePhotoAreNeverSent`, `UploadSourceIsolationTest`). It is **deleted with the report**: when an unsent report is dropped («Vom Server löschen», switching sharing off) or refused by the server, when a sent one leaves the list of the last 500, or by «Löschen»; «Abbrechen» deletes it at once. A photo of a licence plate is personal data of the driver (and may show other people): the app keeps it only on the phone, for you, and sends it nowhere; deleting the app or its data deletes it. The server rounds the time for publication (DESIGN.md §4). **Streets under the compass (since 0.8.0; first in the internal `laborKarte` build, 0.7.8):** while the report dialog is on screen (0.8.0: the step «Wohin fuhr es?») and the report's fix is there, the app downloads swisstopo's grey map tiles (`wmts.geo.admin.ch`, swisstopo = the Federal Office of Topography; the same host and tiles as the tab «Karte» and the website's map) for the area around the fix and draws them faint under the arrow, turning with the phone. Unlike the tab «Karte», which opens near the *shifted* point, these requests are for the area around your **true** position: swisstopo receives them, with your IP address, at the moment you report — since 0.9.0 the fix starts as soon as the dialog opens, so the tiles are requested then, also if you then tap «Abbrechen» (0.8.0: after the category, also if you cancelled at the confirmation) —, as anyone looking at that area on the map would. The facts of the code (`app/src/karte/.../ui/ReportMapTiles.kt`): GET only, plain HTTPS image requests, no redirects followed, no cache (`useCaches = false`, no HTTP cache installed in the app), no cookies, no referrer, the fixed User-Agent «LessNoise», no other data; only the tiles at zoom 18 that touch the 150 m circle around the fix (9 to 16 tiles) and, since 0.9.0 (0.8.1 in `laborKarte`), when you drag the map under the dot (below), 300 ms after the finger lifts, those touching the 150 m circle around the new centre — at most 2 km from the fix — so swisstopo then also learns the area of the place you set (tiles already in memory are not requested again; at most 64 kept; 4 requests at a time, at most 512 kB per tile); only for a fix (or a centre) inside Switzerland's bounding box (where the grey map has data); only while the dialog is on screen (cancelled when it closes); the tiles are kept only in memory for that dialog — nothing is written to the phone, and nothing is sent to the project's server that it did not receive before (the report itself is unchanged). Before the fix is there the dialog shows «Standort wird gesucht …» and requests nothing. Since 0.9.0 the Karte tab can be dark (below), which changes nothing about what is loaded here. The Offline-Version has no such dialog and no network code. Since 0.7.0 the report can also carry the direction the vehicle drove: one compass bearing in whole degrees, whether it is true or magnetic north, and the compass's accuracy class — read from the phone's motion sensors only while the report's dialog is on screen, no permission, no sensor data kept; the map shows it on the report's point (until 2026-10-28 as a small arrowhead on the rim, since then as the point's one pointed corner). Until 0.7.8 you pointed the phone and tapped «Richtung übernehmen» («Überspringen» sent none); since 0.8.1 the compass is at the top of the one dialog and the report takes the reading of the moment you tap the category (since 0.8.2: the moment you tap «Senden») (none on a phone without a compass, or if the compass has no reading yet). **Level while the dialog is open (0.9.0 in the Online-Version; 0.8.5 in `laborKarte`; the code is in `src/main`):** while the dialog is on screen and no measurement runs, the microphone runs through **the same level meter as a measurement** (the app's own `MeasurementEngine`: A-weighting, Fast time weighting, 125 ms steps, the phone's calibration offset) in the app's own process — no classifier, no audio tap, no recording, not the measuring service — and the dialog shows the level now and the peak so far («58,2 dB(A) jetzt · Spitze 81,4 dB(A)»; since 0.8.6 as two figures, «58,2 dB(A) jetzt» and «81,4 dB(A) Spitze»); the microphone is closed when the dialog closes. While a measurement runs, the dialog opens no second microphone and takes that measurement's own values. **No audio is stored or sent**: the capture block exists only in the reused 125 ms buffer, as in a measurement. What is sent with the report is the **peak (LAFmax) and the average level (LAeq) of those seconds, rounded to 0.1 dB, the number of seconds (at most 600) and whether the phone is calibrated** (`level`, server/DESIGN.md §4.7); the map publishes only the peak, rounded to whole dB. The microphone permission is the one a measurement asks for; if it is not granted yet, the dialog asks for it when it opens, and without it the report has no level. The phone's list «Meldungen» keeps the peak and the average of a sent report. |
| **No measurement on the road** (Online-Version since 0.9.0; 0.8.4 in the internal `laborKarte` build) | «Messen» has no mode switch any more: a running measurement means the phone lies at a window, no running measurement means you hold the phone and are on the road. While nothing runs, the first button is **«Laut! Melden»**, then «Messung starten» («Zum Messen: Telefon ans offene Fenster, am Ladegerät.»; since 0.8.8 «Langzeitmessung starten» with «Für Stunden am offenen Fenster, am Ladegerät.», and under «Laut! Melden» «Unterwegs: lauten Verkehr sofort melden.»); while a measurement runs there is no report button on «Messen». The Online-Version has **no «Unterwegs» measurement and no «Sehr laut» notification**: no notification channel `loud_alert`, no alert watcher, no «Antippen zum Melden» or «Melden» on the measurement notification — the code is in the internal `laborKarte` build only (`app/src/laborKarte/…/alert/`, `…/ui/LoudAlerts.kt`; the Online-Version compiles the stand-in `app/src/nounterwegs/…/ui/LoudAlerts.kt`), and the APK contains no class `ch/lessnoise/app/alert/*` and no string `loud_alert` (`android/tools/check_release_apk.py`, below). On the road the app only reports: «Laut! Melden» as above. The published Online-Version 0.6.3–0.8.0 had the «Unterwegs» measurement and the «Sehr laut» notification; what they did is described under «Internal variant `laborKarte`», where they remain. |
| **App shortcut and widget «Laut! Melden»** (Online-Version since 0.9.0; 0.8.4 in the internal `laborKarte` build; Online-Version and `laborKarte` only, `app/src/karte/…/report/`) | A long press on the app icon offers the shortcut «Laut! Melden» (it can also be dragged to the home screen), and the home screen can hold a widget with one button «Laut! Melden». Both only **open the app's report dialog** — with the time of the tap as the report's time and nothing preselected, exactly like the button on «Messen» (also while a measurement runs). **They send nothing, read no location and run nothing in the background by themselves**: the widget shows no data and is never updated by a timer; the shortcut is a fixed entry in the app's manifest. Everything after the tap is «Laut! Melden» as above (the one fix only with the dialog open, only «Senden» queues the report). No new permission. |
| **«Unterwegs erkannt»: the motion sensor** (0.7.0; Online-Version and laborKarte only; `app/src/karte/…/motion/`, the Offline and Labor builds contain no detector and no sensor listener) | While a measurement started with **«Messen»** (since 0.8.3 «Messung starten») runs, the app reads the phone's **accelerometer** (motion sensor, 20 times a second, batched by the phone) **on the phone only**, to recognise that the phone is being carried. It needs no permission and reads **no location** for it. It keeps no sensor value: the detector holds only a few minutes of per-second numbers in memory, and the database stores only the start and end time of each span in which the phone was carried (table `moving_spans`). While the phone is carried, **nothing of that time span is shared** (in the Online-Version until 0.8.0 the measurement also behaved like «Unterwegs» and the «Sehr laut» notification could show; since 0.9.0 — 0.8.4 in `laborKarte` — only in the internal `laborKarte` build) — no minute and no event that overlaps it is ever sent (`PlaceIndex` in `upload/Uploader.kt`; the newest records wait 35 s before they may be sent, so a span the detector decides a little later still covers them). When the phone lies still again for about 3 minutes, sharing resumes as before. The status line says «Unterwegs erkannt: Mitteilung bei sehr lautem Verkehr, nichts auf die Karte.» (since 0.8.3 «Unterwegs erkannt · nichts auf die Karte»; in 0.8.3 the mode switch showed «Unterwegs erkannt» too; since 0.8.4 the status line is the only sign). Nothing of the sensor leaves the phone. |
| **What is never sent** | Audio (none exists). The top-3 raw AudioSet labels of events (`topLabelsJson` is not read by `Payloads`). The `orig_*`/re-evaluation columns. The detector-v2 features of events and minutes (local floor, excess, rise/decay, LF share and flutter, shape, wind flag, wind event count). The coordinates of the fix at «Starten» or of pasted coordinates, the true hectare, the offset and the noise secret: only the shifted point is kept and sent. Exact coordinates only with a report you confirmed. No name, account, phone number, advertising id or Android id. |
| **Location** | Only the Online-Version (and the internal `laborKarte`) has `ACCESS_FINE_LOCATION` and `ACCESS_COARSE_LOCATION`; it asks for them only at «Starten» (until 0.6.0 «Messung starten») with sharing on (or when you switch sharing on during a measurement, or tap «Nochmals»), or at «Laut! Melden». Each time it gets **one** fix from Android's own `LocationManager` (no Google Play Services; GPS and network provider, whichever are on; `getCurrentLocation`, on Android 8–10 one update; at most 30 s, cancellable; a last-known location only if younger than 2 min and accurate to ≤ 50 m) while the app is open — since 0.6.3 the fix for a measurement runs in the app's screen while the measurement already runs, is cancelled when you leave the app and taken again when you come back, never from the measuring service. At «Starten» the fix becomes the shifted point at once (`location/LocationPolicy.decide` → `CellInput.fromLatLon` → `LocationNoise.shift`) and is accepted only with «Genauer Standort» and an accuracy of ≤ 75 m; its coordinates live only in local variables of `location/LocationFetcher.kt` and `LocationPolicy.decide`: not in the settings, not in the database, not in a log, not in the upload. For a report (`LocationPolicy.decideReport`, any accuracy since 0.7.0, the accuracy sent with it) the coordinates go into the report queue described above. Never in the background: no `ACCESS_BACKGROUND_LOCATION`, no location request outside these two moments. The offline edition and the Labor build have no location permission (removed in their manifests). |
| **Where it goes** | One host, `BuildConfig.API_BASE` = `https://api.lessnoise.ch` (0.6.0; before `https://api.stadtlaerm.ch`; the project's server in Switzerland, DESIGN.md §8). HTTPS only (`UploadClient` refuses any other base URL except a loopback test server), redirects are not followed, no cookies, and the User-Agent is just `LessNoise` (no phone model or Android build). |
| **When** | Since 0.6.3 (the live map): while a shared measurement runs, about 30 s after it got its point, then the minutes every 2 minutes, and each loud moment (event) about 30 s after it ended (at most one upload per 30 s, usually one request; `upload/UploadWorker.kt` → `LiveUploadPlan`, in the app's process while the measurement's foreground service runs); 15 s after it stops; shortly after a report; and after every full hour as the fallback (WorkManager). With a loud moment every minute at most about 90 requests in an hour, within the server's limit of 360 per device and hour (60 up to the server of release 0.6.2; after a 429 the app slows down and nothing is lost; `UploadCadenceTest`). Over **any connection, mobile data included, by default since 0.6.3** (at most about 3 MB a day when measuring round the clock, usually much less); «Nur über WLAN» (under «Teilen» › «Messungen am Fenster», until 0.8.0 «Weitere Angaben»; on by default until 0.6.2, reset once to off by the update to 0.6.3) restricts it to Wi-Fi/unmetered networks. At the first opt-in the shared measurements of the last 7 days are included (normally there are none yet: a measurement is shared only if it has a point); afterwards nothing measured while sharing was off is ever sent, except the measurement that is running when you switch sharing on, which is shared as a whole. The explanation says so («Schaltest du Teilen während einer Messung ein, kommt diese ganze Messung auf die Karte, frühere nie.»). |
| **Identity** | At the first upload the server issues a random device id and a secret token (DESIGN.md §4.1). The token is stored encrypted with a key held in the Android Keystore; the id is never shown on the public map. «Vom Server löschen» forgets id, token and the location-noise secret, so switching on again starts with a fresh id and new offsets; «Neu starten» (until 0.6.0 «Neue Kennung», since 0.6.1 shown only when the server no longer knows the id) deletes the server data and continues under a fresh id and a new location-noise secret. |
| **Delete means delete** | «Vom Server löschen» (until 0.6.0 «Daten auf dem Server löschen»; the confirmation still asks «Daten auf dem Server löschen?») sends `DELETE /v1/devices/{id}` and waits for the server's `204` (sent only after every row is gone — minutes, events, site and reports —, DESIGN.md §2.4); only then are id, token and the location-noise secret forgotten on the phone, reports not yet sent are dropped, and sharing is switched off. On an error nothing changes and the screen says so. |
| **Silent devices are deleted** | If the server receives nothing from a device for **60 days** (no upload, no other authenticated request), it deletes every row of that device exactly as «Löschen» does, and the map is rebuilt without it (DESIGN.md §2.9, §5; `retention.expire_inactive`, setting `INACTIVE_DELETE_DAYS`). This covers phones that switched to the offline edition, uninstalled the app, or stopped sharing without deleting. Only an anonymous counter (`devices_expired`) goes up by one. If such a phone shares again, the server answers `401`; the app says why and offers «Neu starten», which starts over under a fresh id (it does not re-register silently). |

`ACCESS_NETWORK_STATE` is required by Android for background jobs that wait for a network (and
for «nur über WLAN»); it tells the app whether a connection is up and metered, not which network.
Since 0.6.3 the status line on «Messen» also uses it to say «Kein Internet – wird nachgeholt».
`ACCESS_WIFI_STATE` and `RECEIVE_BOOT_COMPLETED` (which WorkManager would add) are removed in the
manifest.

**How to verify:** the grep above (one network file); `android/app/src/test/.../upload/UploadTest.kt`
checks the exact request bodies against a local fake server (no labels, no originals, no
coordinates and no hectare, the measurement's shifted `point` on every minute and event, nothing
from a measurement without a point, gzip, bearer token, the fixed User-Agent, nothing sent while
off, the noise secret dropped by «Vom Server löschen» and «Neu starten»);
`android/dsp/src/test/.../geo/LocationNoiseTest.kt` checks the location noise (values computed
independently in Python, the same offset for the same hectare and σ, independent offsets
elsewhere, the sample standard deviation over 20 000 secrets ≈ σ, no NaN at edge bytes, the point
kept inside the server's box);
`android/app/src/test/.../upload/ReportsTest.kt` checks the exact report body and that a report's
coordinates are gone from the phone once the server answered (also when sharing is switched off or
the server data is deleted); `android/app/src/test/.../location/LocationIsolationTest.kt` checks
that only `public` and `laborKarte` declare the location permissions (`offline` and `labor` remove
them, background location is removed everywhere) and that the location code logs, stores and
sends nothing, with `android.location` used in exactly one file; `UploadTest.kt` also checks that a point attached after the start sends the minutes and events
recorded before the fix (0.6.3), and `UploadCadenceTest.kt` counts the requests of a two-hour
measurement at the 0.6.3 cadence against the server's hourly limit; `LocationPolicyTest.kt` checks
the lat/lon → shifted point path (also for pasted coordinates), that the point is stable for a
place, and when a fix is accepted; and on a phone, Android's per-app data usage
(on a Pixel: *Settings → Apps → Weniger Lärm → Mobile data & Wi-Fi*) shows the app's traffic,
which stays at zero while sharing is off and the tab «Karte» is not opened.

## The tab «Karte» (0.6.3)

The Online-Version (and the internal `laborKarte`) has a tab **«Karte»** that shows the website's
noise map, `https://wenigerlaerm.ch/karte.html` (below, «The website»), inside the app, in an
Android WebView (`android/app/src/karte/kotlin/ch/lessnoise/app/ui/MapScreen.kt`). The Offline-Version
and the Labor build do not have it: their builds contain no map screen and no WebView
(`app/src/nomap/`, checked with `dexdump` before a release).

| | |
|---|---|
| **When** | Only when you open the tab. Nothing is loaded before you open it. Since the 0.6.3 map tab fix the page is kept while you look at another tab, so that coming back does not load it again: it is then hidden and paused (JavaScript timers stopped, a page still loading is stopped; what the page had already started fetching may still finish), nothing new is loaded and the view is not moved; it is closed with the app's screen. It is not reloaded when shown again; the page keeps its data up to date by itself (below, «The website»: it asks for the published map files every 30 seconds while it is visible), and when the tab is shown again the app asks it to check at once. |
| **What it loads** | Like a browser that opens the map page: the page and its scripts from `wenigerlaerm.ch` (GitHub Pages), the map tiles from `wmts.geo.admin.ch` (swisstopo, the Federal Office of Topography), the published map files (fields, reports) from `api.lessnoise.ch` (or, if that host cannot be reached, `api.stadtlaerm.ch`, the same server). The app refuses any other host (`MapLinks.allowedHost`), in addition to the page's own Content-Security-Policy. Links to other pages (Datenschutz, GitHub, swisstopo …) open in your browser. These hosts see your IP address, as for any visitor of the map. Outside this tab, since 0.8.0 the app also loads tiles from `wmts.geo.admin.ch` (no other host) in «Laut! Melden» (0.8.0: the step «Wohin fuhr es?»), for the area around your **true** position at report time and, since 0.9.0, around a place you move the map to (at most 2 km away) — GET only, no redirects, no cache, only tiles touching the 150 m circle, only inside Switzerland; see the «Laut! Melden» row of [«Messwerte teilen»](#messwerte-teilen-opt-in-upload-from-v050). |
| **What it sends** | No measurement, no location, no device id, no cookies. Where the map opens: at the **shifted** point of your newest shared measurement (the point that was already sent, never your true location, which the app does not keep), at zoom 16, written into the address after `#` (`karte.html#lat=…&lon=…&z=16`); browsers never send that part to a server, the page's script reads it on the phone. Without a shared measurement the plain map opens (`karte.html#metric`). When your point appears or changes later (e.g. the first fix of a measurement while the tab is kept), the app moves the view by replacing only that part after `#`: the one script it ever runs in the page is `location.replace('#lat=…&lon=…&z=16')` (built only from the shifted point, `MapLinks.recentreScript`), no reload and no new history entry; it sends nothing either. What remains: the map tiles for the area on screen are requested from swisstopo, so swisstopo can tell from the tile requests (with your IP address) which area of a few hundred metres is being looked at — when the tab opens at your measurement, an area near your shifted point — as for anyone who looks at that area on the website. |
| **Locked down** | JavaScript on (the map needs it); no DOM storage, no file or content access, no JavaScript bridge to the app (the page cannot call the app; the app runs only two fixed scripts in the page and reads nothing back: the `location.replace` above, and `window.KarteCheck && window.KarteCheck()` when the tab is shown again, the page's own check for new map files), geolocation off (every permission request of the page is denied), no cookies at all, mixed content never, usage metrics of the WebView off (`android.webkit.WebView.MetricsOptOut`). Safe Browsing stays as Android's default (on): Android's WebView may check page addresses against Google Safe Browsing, as Chrome does. `UploadSourceIsolationTest` checks these settings and that the WebView is the app's only network code besides `UploadClient.kt` and (since 0.8.0 in the Online-Version, 0.7.8 in `laborKarte`) the tile download of «Laut! Melden», `ReportMapTiles.kt` (one address on `wmts.geo.admin.ch`, GET, no redirects, no cache, created only by the compass of the report dialog; see the «Laut! Melden» row); `MapLinksTest` checks the address, the allowed hosts (= the page's CSP) and which links stay in the app. |
| **Dark mode** (0.9.0; 0.8.2 in `laborKarte`) | When the app is dark (the phone's dark theme), the WebView is created with a dark platform theme and the page uses its own dark style (`prefers-color-scheme` in `docs/map/karte.css`, a CSS filter on the same swisstopo tiles); on old WebViews «web theme darkening only» is forced (`androidx.webkit`, a library compiled into the app, no network of its own). It changes only how the page is drawn: **the same page, tiles and files are loaded from the same hosts**; the allowed hosts and the page's CSP are unchanged. |
| **Permissions** | None added: it uses the `INTERNET` permission the Online-Version already has. |

## On the server: what is stored and what the map publishes (schema v4, from 0.6.2)

This part is about the server ([server/DESIGN.md](server/DESIGN.md) §2, §4–§6), whichever app
version sends. The app's side (when it reads the location, what its screens say) is described
above and in [android/README.md](android/README.md).

| | Stored on the server | Published on the map |
|---|---|---|
| **Minutes and events** (levels, events) | **Events only of the five machine and motor categories** (`loud_vehicle`, `road_traffic`, `rail_tram`, `aircraft`, `construction`): since 2026-10-16 the server drops an event of any other category — voices, music, unclassified, wind, an unknown id — when it arrives, without storing it (the app gets a normal answer and does not send it again). Rows of other categories stored before that date were deleted on 16 Oct 2026 (a one-off step of the server update, schema v5; the count of events in the retention job's hourly rows lost those categories too); minutes and reports were not changed. Minutes are stored with their category shares. Each row with the **shifted point** the app sent (`"point"`: LV95 whole metres and σ, §4.3; app 0.6.0) and its display cell, the 100 m hectare that contains that point; or, from older apps, a hectare id (`"cell"`), or nothing — then it counts for the device's site hectare. Never the true location: the app moved the point before sending (above). A point or hectare outside Switzerland, or a σ other than 25/50/100/200 m, is refused. | Per 100 m field (display cell) and **calendar day** (all hours, Europe/Zurich), the 7- and 30-day means, and a 24-hour profile (mean per clock hour over 30 days). **Only machine and motor noise counts:** event rates are published for exactly the five categories above, never for voices, music or other sounds; an event counts only if it passes the server's quality checks (at least 0.5 s, at least 30 dB(A) and the app's own minimum level, the app's excess setting at least 6.5 dB); a minute that is at least half voices or music counts as measured time but not for the levels (DESIGN.md §6). The files say since when this rule holds (`counted_since`, the date of the update, 2026-10-16); the map shows «gezählt seit 16.10.2026». Never the points or σ, no device id; device models only as counts over the whole network. With `MIN_DEVICES_PER_CELL = 1` a single contributor's field is shown, and it lies near their home; a contributor who measures in several places appears in several fields, not linked to each other. Since the 30-second map (server after 0.6.2, for app 0.6.3) the figures of the current day and the 24-hour profile are updated about every 30 seconds, so someone who watches a field with a single contributor can tell to within about a minute when a loud event was measured there; still never a list of events, no device id, no exact time (DESIGN.md §2 rule 8, §6). |
| **Manual reports** (§4.7) | Time as sent, point (latitude/longitude rounded to 5 decimals, ≈ 1 m; the fix or, app 0.9.0, the place moved under the dot, with accuracy 15 m), location accuracy, category, the direction if sent (app 0.6.7) and, since schema v8 (app 0.9.0; 0.8.5 in `laborKarte`), the level if sent: peak LAFmax and LAeq of the seconds the report dialog was open (0.1 dB), the number of seconds (1–600) and whether the phone is calibrated. Never a photo or a licence plate (the app has none to send). A point outside Switzerland or an accuracy over 200 m is refused. | `reports.json`: the reports of the last 30 days, each with the **exact reported point**, the category, the direction if sent, the peak level **rounded to whole dB** if sent (app 0.9.0; the map's tooltip shows «84 dB») and the time **rounded down to the full hour** — nothing else: no device id, no exact time, no accuracy, no LAeq, no seconds, no calibration flag; within an hour sorted by place, so the order says nothing about who reported what or in which order. Reports are published at the reported point because the person explicitly reported that place; whoever reports often from the same spot makes that spot visible as a cluster of points. |
| **Deletion** | «Löschen» and the 60-day expiry delete every row of the device, reports included. Raw minutes and events are reduced to hourly figures after 2 years; raw reports are deleted after 2 years. | Rebuilt without the deleted rows on the next publish cycle, a full rebuild (cycles about every 30 seconds; DESIGN.md §2 rule 4, §6). |

**Server host names.** The API answers on `api.lessnoise.ch` and, for app versions up to 0.5.1
and while the website moves, on `api.stadtlaerm.ch` — the same machine in Switzerland, the same
code, the same data. Neither host keeps an access log.

## Labor-Build

Since v0.3.3 the Android app has product flavours in the Gradle dimension `edition`; since
v0.5.0 three of them (`offline`, `public`, `labor`), plus the internal `laborKarte` described
[below](#internal-variant-laborkarte-weniger-lärm-labor--karte):

| | `offline` and `public` (the two published editions) | `labor` |
|---|---|---|
| App id / name | `ch.lessnoise.app`, «Weniger Lärm» (0.6.0; before `ch.stadtlaerm.app`, «Stadtlärm») | `ch.lessnoise.labor`, «Weniger Lärm Labor» (red icon; before `ch.stadtlaerm.labor`) |
| Published | yes: `lessnoise.apk` and `lessnoise-karte.apk` in `docs/download/` on wenigerlaerm.ch | **never**: not in `docs/`, not in a release, not linked from the website |
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
= `lessnoise.apk` and `app/build/outputs/apk/public/release/app-public-release.apk` = `lessnoise-karte.apk`;
the commands below use the second name, run them on each):

1. Source: `find android/app/src -path '*labor*'` lists the only recording code;
   `grep -rln "FileOutputStream\|MediaMuxer\|MediaCodec\|RIFF" android/app/src/main android/app/src/public android/dsp/src/main`
   finds nothing; the only file writes there are the CSV/JSON exports (`writeText` in
   `Repositories.kt` and `CalibrationViewModel.kt`), which contain aggregates only (since 0.8.5 also
   the «Laut! Melden» photo, re-encoded by `ui/ReportPhotos.kt` into the app's private `files/reports/`;
   a `FileOutputStream` there writes that JPEG, never audio).
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
   location); for the internal `laborKarte` APK the Online-Version's set (with `INTERNET`,
   `ACCESS_NETWORK_STATE`, `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`), package `ch.lessnoise.labor`.

**What the Labor build records** (only after «Einstellungen → Labor → Audio während der Messung
aufzeichnen» is switched on and confirmed; off by default): event clips (WAV, 16 kHz, 5 s before to
5 s after each event, at most 60 s) and/or a continuous recording (AAC, 16 kHz, 64 kbit/s, one file
per hour), plus a `manifest.jsonl` with the event data and the classifier's per-second results for
each clip, and `classifier/*.jsonl` with every classifier run (label names and scores, no audio). Files stay on the phone in
`Android/data/ch.lessnoise.labor/files/audio/` until deleted in the app («Alle Aufnahmen löschen»)
or by uninstalling it; they leave the phone only if the user shares them («Als ZIP teilen»), copies them over USB or
exports them with «In Ordner exportieren» (0.7.4) into a folder the user picks in Android's folder picker — which may be a cloud-storage app's folder, such as Google Drive; the app itself has no network access and sends nothing. A red banner on every screen says «WENIGER LÄRM LABOR – kann Audio aufzeichnen»
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
  the upload jobs (as the offline edition does after the Online-Version), and from then on the
  phone has no internet permission again.
- **Internet only for the opt-in upload** (and, since 0.6.3, the tab «Karte», as in the Online-Version): its manifest (`app/src/laborKarte/AndroidManifest.xml`)
  declares `INTERNET` and `ACCESS_NETWORK_STATE`, and `ACCESS_FINE_LOCATION` and
  `ACCESS_COARSE_LOCATION` for the fix right after «Starten» and «Laut! Melden», exactly like the Online-Version; it does not use
  `app/src/labor/AndroidManifest.xml`. «Messwerte teilen» is off until you say yes. As in the
  Online-Version, the very first switch-on also sends the measurement values of the last 7 days,
  here only of shared measurements, i.e. those with a point (values only, never audio).
- **Same upload code as the Online-Version**: `app/src/main/kotlin/ch/lessnoise/app/upload/`, one
  host (api.lessnoise.ch), the same measurement values (§2 of server/DESIGN.md). No flavour has
  its own upload classes.
- **Audio is never sent.** The upload reads only the Room tables (minutes, events, measurements, the
  moving spans of 0.7.0 (start and end only), the active calibration), the site settings and the report queue; it has no code that opens a file, and the Labor recordings
  are files (`Android/data/ch.lessnoise.labor/files/audio/`). The unit test
  `app/src/test/kotlin/ch/lessnoise/app/upload/UploadSourceIsolationTest.kt` (runs in every
  flavour) fails if the upload package uses a file API (`java.io.File`, file streams, `filesDir`,
  cache dirs, `ContentResolver`, assets, `MediaStore` …), an audio class, the Labor package
  `ch.lessnoise.app.labor` or a recording folder name, if it imports a project class that is not
  on its allow-list, if a flavour adds upload classes, if the Labor code touches the upload or the
  network, or if network code appears anywhere but `upload/UploadClient.kt`, the map tab's WebView
  (`ui/MapScreen.kt`) and, since 0.7.8 (Online-Version since 0.8.0), the tile download `ui/ReportMapTiles.kt` (the «Laut! Melden» row above). Recordings still
  leave the phone only when the user shares them («Als ZIP teilen»), copies them over USB or exports
  them to a folder of their choice («In Ordner exportieren», 0.7.4), as in the Labor build.
- **Motion log (0.7.0, Labor·Karte only):** each change of «Unterwegs erkannt» (still ↔ carried) is
  written as one `motion` line to the local `manifest.jsonl` with its time and the numbers that decided
  it (active seconds, the per-second variation of the acceleration), so the detector can be tuned.
  It stays in that file on the phone like the rest of the manifest; the upload never reads it.
- **«Unterwegs aufnehmen» and the «Sehr laut» notification (Labor·Karte only since 0.8.4;
  `app/src/laborKarte/…/alert/`, `…/ui/LoudAlerts.kt`; in the published Online-Version 0.6.3–0.8.0):** on
  «Messen», while nothing runs, a third button **«Unterwegs aufnehmen»** under «Messung starten» (since 0.8.8 «Langzeitmessung starten»; with
  the line «Labor: nimmt Audio auf, teilt nichts.») starts the «Unterwegs» measurement — on this build
  with the Labor recorder, like every measurement here — so that the «Sehr laut» prompt can be compared
  with reports made by hand. The measurement notification carries a «Melden» button during every run
  (0.6.9), which opens «Laut! Melden» at the time of the tap, like the shortcut and the widget. What the
  «Unterwegs» measurement and the notification do, as in the published Online-Version 0.6.3–0.8.0: since 0.6.4 «Messen» showed two start buttons, **«Messen»** and **«Unterwegs»** (in `laborKarte` 0.8.3 a switch «Am Fenster» | «Unterwegs», since 0.8.4 the button «Unterwegs aufnehmen»). A measurement started with **«Unterwegs» shares nothing**: no question, no location fix, no point, so none of its minutes and events is sent, whatever the sharing switch says. While it runs, an event of the detector (not wind) with LAFmax ≥ 75 dB(A) classified «Töff & Poser» or «Strassenverkehr» shows a local notification «Sehr laut: Töff & Poser, 84 dB – Antippen zum Melden» on its own channel «Sehr laut» (`loud_alert`; heads-up, sound and vibration as Android's defaults), at most one every 2 minutes; since 0.7.6 the measurement's own notification turns into that alert for 30 s instead of a second notification. **The notification itself sends nothing and reads no location.** A tap opens «Laut! Melden» with the event's time as the report's time; from there everything is as in the «Laut! Melden» row above. It uses the `POST_NOTIFICATIONS` permission the app already has for the measurement's notification.
- **Report log (0.7.6, Labor·Karte only):** when a report is queued in «Laut! Melden» (0.7.6–0.7.8 at «Senden», in 0.8.1 at the category tap, since 0.8.2 at «Senden» again) one `report` line in the local
  `manifest.jsonl`: time, category, whether a photo was taken (since 0.8.5 `photo: true/false`, never
  the photo or its name; 0.7.6–0.8.4: the licence plate if one was typed in), and the direction — no
  coordinates. It stays on the phone like the rest of the manifest; the upload never reads it.
- **Streets under the compass (0.7.8):** first in this build; since 0.8.0 the Online-Version does
  the same, so it is described for both in the «Laut! Melden» row of
  [«Messwerte teilen»](#messwerte-teilen-opt-in-upload-from-v050) (swisstopo's tiles around your
  true position while the report dialog is open). Moving the map under the dot (0.8.1 here; in the
  Online-Version since 0.9.0) is described there too.

- **Camera button on «Kontrollschild» (0.8.2–0.8.4, Labor·Karte; since 0.8.5 replaced by «Foto», see
  «Laut! Melden» above — the field and the text recognition code are gone):** the small camera button in the
  field opens the phone's own camera app (Android's `ACTION_IMAGE_CAPTURE`); the app itself has **no
  camera permission** and declares none (`aapt2 dump permissions` lists none). The camera app writes
  the one photo into this app's private cache (`cache/plate/`, a write grant for that one file),
  and the app **deletes it at once** when the camera returns (and any leftover at the next photo
  and at app start). **In 0.8.2 there is no text recognition:** the photo is deleted unread, the field
  stays empty and says so; you type the plate as before. An on-device recognition (Google ML Kit's
  bundled model) was checked and not added, because it brings Google Play services and Firebase's
  logging transport, which reports usage to Google (`firebaselogging.googleapis.com`); if a
  recognition is added later, only the text recognised on the phone goes into the field. Nothing of
  the photo is sent, and the plate stays on the phone as before (`ReportsTest.thePlateIsNeverSent`,
  `PlateOcrTest`).
- The banner says «WENIGER LÄRM LABOR · KARTE – kann Audio aufzeichnen»; Einstellungen → Über die App
  shows «Weniger Lärm Labor · Karte». The server counts its `app_version` (e.g. `0.6.0-labor-karte`) under
  «andere» in `stats.json` (it does not match the server's version pattern).

## The website

[wenigerlaerm.ch](https://wenigerlaerm.ch) (source in `docs/`) is static HTML and CSS (plus the
small scripts named below, all served from the site itself). It sets no
cookies, runs no analytics and loads nothing from third parties (except the map page, below): no
web fonts, no CDNs, no external images. The only inline script is on `update.html` (allowed by its hash in the
Content-Security-Policy): the app opens `update.html#v=<version>&c=<versionCode>&e=<edition>`
(`e` since v0.5.0: `offline` or `karte`; the Labor builds send no `e`), the browser does not send the part after `#` to the
server, and the script compares it with the published version locally and offers the download of
the same edition. It is hosted on GitHub Pages; GitHub may log visitors' IP addresses for
technical reasons, see the
[GitHub General Privacy Statement](https://docs.github.com/site-policy/privacy-policies/github-general-privacy-statement).
Since 0.6.1 the app's «Mehr dazu» links open `anleitung.html#messen`, `#kalibrieren`, `#teilen`,
`#daten` and `datenschutz.html` in the browser: plain links, nothing appended. Since 0.6.3 the
Online-Version shows `karte.html` in its tab «Karte» (see [The tab «Karte»](#the-tab-karte-063)),
with the view in the part after `#`. The German privacy page is [docs/datenschutz.html](docs/datenschutz.html).

**Downloads.** Both APKs are served from the website itself (`docs/download/lessnoise-karte.apk` =
«Online-Version», `docs/download/lessnoise.apk` = «Offline-Version»), i.e. by GitHub Pages like
every page of the site. GitHub sees the requesting IP address as for any page view; the project
counts nothing. (`scripts/download_stats.py` reads GitHub Release download counts and only has
something to report if a version is ever also attached to a GitHub Release.)

**The map page is the one exception.** `docs/karte.html` (the shared noise map, see
[server/DESIGN.md](server/DESIGN.md) §7) loads map tiles from swisstopo
(`wmts.geo.admin.ch`) and the published aggregates per 100 m field and the reports from the project's
own API server (`api.lessnoise.ch`; only if that host cannot be reached, `api.stadtlaerm.ch`,
the same server under its old name), and nothing else; its Content-Security-Policy allows
exactly these hosts (`img-src` and `connect-src`) and scripts only from the site itself
(vendored Leaflet, no inline script). It says so in one sentence above the map, and `datenschutz.html` repeats it.
While it is open and visible (since 0.6.3) the page asks the same API host for `cells.json` again
every 30 seconds, and once at once when it becomes visible again; only when the server has
published new files does it fetch `reports.json` (and the open field's file) again and redraw.
These requests are the same as the first ones (same host, no cookies, no referrer; the browser
revalidates its cached copy, so an unchanged file is a short «not modified») and carry nothing about the viewer — not the view, the chosen field or a
location. A hidden page (another browser tab, the app's other tabs) asks nothing.
Both hosts necessarily see the visitor's IP address; the page sends no referrer and sets no
cookies, and the API server keeps no access log. All other pages still load nothing from third
parties.

**E-mail address for update notices (website, 2026-10-20).** Above the download buttons on the
home page a visitor may leave an e-mail address to hear about new app versions — voluntary, not
needed for the download. `docs/js/newsletter.js` (served from the site, no inline script; the home
page's Content-Security-Policy allows `script-src 'self'` and `connect-src` to exactly the two API
hosts) sends nothing until «Eintragen» is tapped, then one `POST /v1/newsletter` with
`{"email": …}` to the project's server in Switzerland (`api.lessnoise.ch`; only if that host cannot
be reached, `api.stadtlaerm.ch`), without cookies or referrer. The server stores **only the
address (trimmed, lowercased) and the time it was first left** — no IP address, no user agent, no
link to any app, measurement or report (server/DESIGN.md §4.8); it sees the IP address as for any
request and keeps it only in memory as a rate-limit key, with no access log. A repeated address
gets the same answer as a new one, so the form reveals nothing about who is on the list. The
addresses are used only for notices of new app versions and important changes, are never passed
on, and are written to by the Verein from an ordinary mail account (whose provider sees the
recipient addresses, as with any e-mail). Deletion at any time: reply to such a notice or ask
the Verein through a [GitHub issue](https://github.com/blauewelt/lessnoise/issues) (public); the
address is then removed (`DELETE /v1/newsletter`) and is gone from the encrypted nightly backups
within 30 days. The German text is in `docs/datenschutz.html#email`.

## Usage numbers

The project wants to know whether the app is used, without learning anything about anyone. It
counts in one place, public, totals over the whole project. Downloads are not counted: the APKs
are served from the website (`docs/download/`), and GitHub Pages reports nothing to the project.
(`scripts/download_stats.py` would read GitHub Release download counts if a version is ever also
attached to a GitHub Release.)

1. **Sharing**, from the server's own data: `https://api.lessnoise.ch/v1/map/stats.json`
   ([server/DESIGN.md](server/DESIGN.md) §6.5), rebuilt with the map — how many devices are
   registered, have sent a place for their site, ever shared, were active in the last 7/30 days, deleted their
   data (one counter), were deleted by the server after 60 days without uploads (one counter), how many 100 m fields are on the map, how many device-days were shared, how
   many active devices reported which app version at registration, and registrations per ISO week.
   Counts only: no device ids, no cells or places, no IP addresses (the server stores none, and
   neither Caddy nor the app server keeps an access log). Nothing new is collected for it; the only
   numbers kept just for it are the two deletion counters, one integer each.

**Not counted:** downloads, and anything about the offline edition — it has no internet
permission and cannot report anything, by design; the Online-Version with sharing switched off;
app starts, measurements, crashes or versions of apps that do not share; downloads of
`docs/download/lessnoise.apk`. `scripts/usage_report.py` combines both sources into one short
text report.
