# Changelog

## 0.9.4.1 — Labor only (branch labor-0.9.1): export runs in the background with progress; ZIP no longer gets cancelled (versionCode 46)

In short: the owner's «Als ZIP teilen» of 2,8 GB showed «ZIP …» for a long time without progress and
was then killed by Android when he left the app. Both exports now run in a foreground service with a
progress bar and «Abbrechen», and the ZIP is many times faster. Only the Labor editions (`labor`,
`laborKarte`) change; the public editions get the version number only. First version with four
components (the owner's rule for a Labor-only fix).

- **Foreground service** `LaborExportService` (type `dataSync`; `FOREGROUND_SERVICE_DATA_SYNC` in the
  two Labor manifests only, checked by `tools/check_release_apk.py`): «In Ordner exportieren» and
  «Als ZIP teilen» go on when the app is left or the screen turns off. Notification «Export · 412 von
  1830 Dateien · 165 von 820 MB» with a progress bar and «Abbrechen»; at the end «Export fertig» (for a
  ZIP: tap to share). Partial wake lock only while copying.
- **In the app:** a progress bar (by bytes) with the same line and «Abbrechen» under the export
  buttons; a second export while one runs is refused with a short message; deleting is disabled
  during an export. A finished ZIP opens the share menu by itself while the section is on screen,
  else «ZIP teilen» or the notification.
- **ZIP:** audio (`.wav`, `.m4a`) stored without compression, `.jsonl` deflated (synthetic 220 MB:
  10–11 s → 0,3–0,4 s on a desktop JVM); 1 MB chunks, never a whole file in memory; written to a temp
  file and renamed when complete — «Abbrechen» or an error leaves no half ZIP and changes nothing in
  the ledger; its files count as exported only after the ZIP is complete and its share menu opened.
- **Free space:** before a ZIP the app checks the cache partition; if short it says «Zu wenig
  Speicher für ZIP: n MB nötig, m MB frei – Ordner-Export nutzen» and offers the folder export.
- **Folder export:** «Abbrechen» stops within 1 MB; what was completely copied stays recorded, the
  half-copied file is deleted from the folder.
- **Version parsers:** the server's stats (`app_versions`) accept four numeric parts
  (`server/stadtlaerm_server/stats.py`); `docs/update.html`'s `compareVersions` already did (new test
  `tests/unit/update-version.test.js`).
- **Docs:** android/README.md («Export in the background»), PRIVACY.md (Labor: exports run as a
  foreground service with a notification). No promise changes.
- **Tests:** `ExportJobTest` (testLabor), one new case in `IncrementalExportTest`, four renders in
  `ExportScopeScreenshots`, `test_stats.test_version_key`, `update-version.test.js`.

## 0.9.4 — Labor only (branch labor-0.9.1): export scope «Alles» / «Seit letztem Export» / «Letzte 24 h», «Ohne Stundendateien», «Exportierte Aufnahmen löschen» (versionCode 45)

In short: a Labor phone that has recorded for weeks can hand over just last night instead of
building a 3,5 GB ZIP, and free its storage without losing what was not exported yet. Only the
Labor editions (`labor`, `laborKarte`) change; the public editions get the version number only.

- **«Export»** (Einstellungen → Labor, above «In Ordner exportieren» / «Als ZIP teilen»; applies to
  both, remembered): «Alles» (as before: the folder gets what is new or changed, the ZIP everything),
  «Seit letztem Export» (only what is in no folder and no ZIP yet — the ZIP now shares the folder
  export's ledger and records its files in it; never exported: «Noch nie exportiert – nimmt alles»),
  «Letzte 24 h» (default: clips, classifier logs and hour files whose name — else lastModified —
  falls in the last 24 h, and a filtered `manifest.jsonl` with the window's lines plus each of their
  sessions' `session_start`, last `clock`, open `continuous_open` and last `storage_full` /
  `logs_full`; the manifest on the phone stays whole).
- **«Ohne Stundendateien»** (one switch for both buttons, on by default) leaves out
  `continuous/*.m4a`. A size line shows the result before pressing, e.g. «Letzte 24 h ohne
  Stundendateien: 412 Dateien, 165 MB».
- **ZIP name** carries the scope: `lessnoise-labor-<yyyyMMdd>-24h.zip` / `-seit-export.zip` /
  `-alles.zip` (was `lessnoise-labor-<yyyyMMdd_HHmm>.zip`).
- **«Exportierte Aufnahmen löschen»** next to «Alle Aufnahmen löschen»: deletes only files already
  exported to a folder or packed into a ZIP and unchanged since, never the manifest; the dialog names
  count and size.
- **Docs:** android/README.md («Export scope»), PRIVACY.md (Labor: exports can be partial; the new
  delete action). No promise changes.
- **Tests:** `ExportScopeTest` (11 cases, testLabor), `ExportScopeScreenshots` (Paparazzi, testLaborKarte).

## 0.9.3 — Labor·Karte first (branch labor-0.9.1): detector class «loud_vehicle» shown as «Lautes Fahrzeug» (versionCode 44)

In short: the detector's category for motorcycles and revving engines is now called «Lautes
Fahrzeug» — a loud vehicle, not a judgement about its driver. Only the name changes: the id
`loud_vehicle`, what the detector counts, what is sent and what the map stores stay as they were.

- **App:** the chip «Hervorheben», the «Klingt nach» chip in «Messen», the list of Lärmspitzen and
  the chart's tooltip say «Lautes Fahrzeug» (from `categories.json` / `ChartCategories`). Where a
  count or a series is meant the plural reads better: the summary line «… 413 Lärmspitzen, davon 0
  laute Fahrzeuge …» («davon 1 lautes Fahrzeug»; `ChartCategories.countName`) and the chart legend
  «Laute Fahrzeuge» (`ChartCategories.legendName`). Labor·Karte's alert reads «Sehr laut: Lautes
  Fahrzeug, 84 dB». The «Teilen» bullet says «(z. B. «Lautes Fahrzeug»)».
- **Map (website):** the source chip «Lautes Fahrzeug», the per-hour label «Laute Fahrzeuge/h», the
  bars under the 24-hour chart «Laute Fahrzeuge pro h», the legend title «Ereignisse «Lautes
  Fahrzeug» pro Stunde Messzeit» and the explanations («laute Fahrzeuge, Strassenverkehr, …»).
- **Unchanged:** the report categories of «Laut! Melden» — «Töff» (`motorrad`) is offered as before,
  «Poser» (`poser`) stays only as the name of old reports.
- **Docs:** PRIVACY.md, the website's Datenschutz page, README.md, android/README.md and
  server/DESIGN.md use the new name; no promise changes.

## 0.9.2 — Labor·Karte first (branch labor-0.9.1): clarity pass — every element says what it is for (versionCode 43)

In short: everything a first-time user sees now says what it is for, in a few words. Nothing that is
sent or kept changes.

- **«Laut! Melden»:** a heading «Wohin fuhr es?» above the compass and, always, «Telefon in die
  Fahrtrichtung halten» (until 0.9.1 the red «Kompass ungenau: …» line replaced it, and the arrow had
  no explanation; now it is an extra line under it). Both sit above the disc, so a small phone shows
  them without scrolling. While the map can be dragged the location row reads «Standort ± 100 m ·
  Karte verschiebbar». The level card says «Mikrofon läuft». The «×» on the photo is easier to hit
  (40 dp).
- **«Meldungen senden?»** and «Was gesendet wird» now name everything a report carries: time, exact
  place, direction, level and kind of noise.
- **«Messen»:** «Grundrauschen (ohne Spitzen)»; saying no to the microphone now shows «Ohne Mikrofon
  kann die App nicht messen.».
- **«Messungen»:** the band in the chart is «leise bis laut» (was «Schwankung»); the category chips
  are labelled «Hervorheben:»; empty tiles say «noch kein Wert», «noch keine» or «keine» instead of «–».
- **«Teilen»:** «Noch nicht gesendet» (was «Noch zu senden»); the chip «Kein Standort für die Karte»;
  the photo full-screen has «Foto löschen» (was «Löschen»).
- **«Einstellungen»:** «Exportieren» and «Alle löschen» are rows with a line each («Alle Messungen als
  Datei teilen», «Messungen auf diesem Telefon; Kalibrierungen bleiben»); «Erweitert» lists «Lärmspitzen,
  Mikrofon, Kalibrieren»; in «Erweitert» the «Lärmspitzen» card says what it sets, «Mikrofon» says
  which microphone measures, «Kalibrieren» says what calibrating does.
- PRIVACY.md, docs/datenschutz.html and android/README.md updated where they quote a changed text.

## 0.9.1 — Labor·Karte first (branch labor-0.9.1) (versionCode 42)

In short: «Laut! Melden» is quicker to finish. The dialog offers four choices and «Senden» is its
big main button. Nothing that is sent or kept changes.

- **Four categories** (owner's request): «Was war es?» offers Töff, Auto, Lastwagen and Flugzeug in a
  2 × 2 grid (the same 72 dp buttons, icon over label). «Poser» and «anderes Fahrzeug» are gone from
  the dialog. Reports made with them by 0.9.0 and older are kept: still in the queue if not yet sent,
  still shown in «Meldungen», still on the map. The server keeps accepting both (older apps in the
  field still send them; server/DESIGN.md §4.7).
- **«Senden» is the main button**: full width, 56 dp high, with the send icon, directly under the line
  «Wer oft vom gleichen Ort meldet, macht ihn auf der Karte sichtbar.»; disabled until a category is
  chosen. Tapped before the location is found it shows a small progress circle and «Standort wird
  gesucht …» (the progress bar in the location card is gone). «Abbrechen» is a text button centred
  under it.
- **Short screens:** compass, level, location, categories and photo scroll; the line, «Senden» and
  «Abbrechen» always stay on screen (a thin line marks where the scrolling part ends when it scrolls).
- **«Sehr laut» notification (Labor·Karte only):** a loud vehicle now suggests Töff and Auto (was Töff
  and Poser); road traffic Auto and Lastwagen; aircraft Flugzeug — unchanged.
- No change to what a report sends, to permissions, or to privacy. Texts: docs/anleitung.html,
  PRIVACY.md, android/README.md and server/DESIGN.md name the four choices.

## 0.9.0 — 2026-10-25 (versionCode 41; published: Offline-Version and Online-Version on the website)

In short: the first published version since 0.8.0. It brings the unpublished Labor·Karte builds
0.8.1–0.8.8 to the two published editions. **Online-Version:** «Laut! Melden» is one dialog with
«Senden»; you can move the map under the dot to correct the place; the dialog measures the level
while it is open and sends peak and average with the report; an optional photo of the vehicle stays
on the phone instead of the typed plate; an app shortcut and a home-screen widget open the dialog;
there is no «Unterwegs» measurement and no «Sehr laut» notification any more; the «Karte» tab is
dark when the phone is. **Offline-Version:** a new look («Messen», «Messungen», «Einstellungen»),
the start button «Langzeitmessung starten»; nothing it does with data changes. No new permission in
either edition (no camera permission). The privacy page (datenschutz.html), PRIVACY.md and
server/DESIGN.md §2 and §4.7 say what changed.

**Online-Version**

- **«Laut! Melden» in one dialog** (0.8.1/0.8.2): the compass with swisstopo's streets on top, the
  level, «Standort ± n m», «Was war es?» with six buttons (one selected), «Foto vom Fahrzeug
  (optional)», the line «Wer oft vom gleichen Ort meldet, macht ihn auf der Karte sichtbar.», then
  «Abbrechen» / «Senden». No confirmation step any more; «Senden» before the location is found sends
  the report when it arrives. **Privacy:** the one location fix is now taken when the dialog opens
  (it was after the category), so it — and swisstopo's tiles around it — happen also if you then
  cancel; the location is sent only with «Senden».
- **Move the map under the dot** (0.8.1): drag the map with one finger when the vehicle drove on or
  the location is off; the dot in the middle is then the reported place, sent with a fixed accuracy
  of 15 m, at most 2 km from your location and only in Switzerland; «Zurück zum Standort» undoes it.
  **Privacy:** after the finger lifts the app loads swisstopo's tiles around the new place too, so
  swisstopo also sees that area (with your IP address), as for the tiles around you since 0.8.0.
- **Level with the report** (0.8.5): while the dialog is open the microphone runs through the same
  level meter as a measurement («jetzt» and «Spitze»; while a measurement runs, its values are used).
  Nothing is recorded. The report carries the peak and the average of those seconds, their number and
  whether the phone is calibrated; the map shows the peak in whole dB on the report's tooltip
  («84 dB»). Needs the server's schema v8 (deployed with this release); an older server gets the
  report without the level. Without the microphone permission the report has no level.
- **Photo instead of «Kontrollschild»** (0.8.5): «Foto vom Fahrzeug (optional)» opens the phone's
  camera app (no camera permission in this app); the photo is shrunk, stored without EXIF in the app's
  private storage, shown only in «Teilen» › «Meldungen», **never sent**, and deleted with its report
  (or alone with «Löschen»). A plate typed in 0.8.0 stays listed on the phone and is never sent.
- **App shortcut and widget «Laut! Melden»** (0.8.4): a long press on the icon, or the widget's one
  button, opens the dialog. They send nothing and read no location by themselves.
- **No measurement on the road** (0.8.4): «Messen» shows «Laut! Melden» first and «Langzeitmessung
  starten» (for hours at an open window, on the charger). The «Unterwegs» start, the «Sehr laut»
  notification and the «Melden» button on the measurement's notification are gone from this edition
  (they stay in the Labor·Karte test build). A carried window measurement still pauses sharing
  («Unterwegs erkannt · nichts auf die Karte»).
- **Dark «Karte» tab** (0.8.2): the map page in its dark style when the phone is dark; same page, same
  tiles, same hosts (new library `androidx.webkit`, no network of its own).
- **«Teilen»** (0.8.8) in sections: «Messungen am Fenster» (the switch «Auf die Karte senden», «Nur über
  WLAN», «Standort verschieben», «Noch zu senden», placement) and «Meldungen unterwegs» (sent reports
  with thumbnails and level), «Was gesendet wird», «Deine Daten auf dem Server». One switch for both,
  and the page now says so.

**Both editions**

- **New look** (0.8.3, 0.8.6, 0.8.7): «Messen» without the old beige card — a status chip, a Material
  level card, while idle the logo in the middle and the actions at the bottom; «Mehr dazu» as a bottom
  sheet; Material cards, rows and icons on every screen; colours without the Material purple/beige.
  The status chip says the day of the last send when it was not today (0.8.6 fix).
- **«Langzeitmessung starten»** (0.8.8, was «Starten»/«Messung starten») with «Für Stunden am offenen
  Fenster, am Ladegerät.».
- **Idle «Messen», new in 0.9.0** (owner): the helper lines under the buttons are centred, 8 dp under
  their button (was 4), 20 dp between the groups (was 16); the Online-Version's tagline is «Kurz laut?
  Melden. Oft laut? Lange messen.» (was «… Oft laut? Messen.»), matching «Langzeitmessung starten»;
  Offline keeps «Miss den Lärm vor deinem Fenster.».

**Labor·Karte only** (not in the published editions): the «Unterwegs aufnehmen» button with the
«Sehr laut» notification and the notification's «Melden» (0.8.4), the preselected category from that
notification (0.8.2), the Labor manifest's `report` line with `photo: true/false` (0.8.5).

**Release tooling and code**

- The swisstopo tiles of the report dialog stay on in the Online-Version as in 0.8.0: the Labor·Karte
  branch still had the 0.7.8 edition flag (`ReportMapTiles.ENABLED`), which this release removes
  from the dialog too, so dragging the map exists wherever the streets are drawn (public, laborKarte).
  `UploadSourceIsolationTest` and `ReportMapTilesTest` pin it, including the drag tiles (no request
  while the finger is down, none for a centre outside Switzerland, the 2 km clamp).
- `android/tools/check_release_apk.py` is one script (0.8.0's release checks merged with 0.8.4's
  edition rules): signer, version, exact permissions per PRIVACY.md (no `CAMERA`), widget/shortcut
  only with the map, no `alert/*` / `loud_alert` in either published APK, no Labor, ML Kit, Play
  services or Firebase classes.

## 0.8.8 — Labor·Karte only, not published (branch labor-one-tap): Teilen split into window measurements and reports (versionCode 40)

The owner: the page should make clear that most choices are about the long measurement at the window.
Layout and wording only; what is sent, when, and the one sharing switch are unchanged.

- **«Teilen»** (title «Teilen», was «Auf die Lärmkarte»; intro «Was von dir auf die Lärmkarte kommt.»)
  in four sections: **«Messungen am Fenster»** (window icon; «Für lange Messungen: Pegel pro
  100-m-Quadrat, Ort leicht verschoben.») with the switch «Auf die Karte senden», the status chip and,
  always visible (no «Weitere Angaben» fold any more), «Nur über WLAN», «Standort verschieben», «Noch
  zu senden» and «Wo liegt das Telefon? (freiwillig)» — drawn disabled while sharing is off;
  **«Meldungen unterwegs»** (megaphone; «Mit «Laut! Melden»: genauer Ort, nur wenn du sendest.») with
  «Gesendet n», the last reports as in 0.8.6, and the line «Eine Meldung schaltet «Auf die Karte
  senden» ein – das gilt dann auch für Messungen am Fenster.» (reports and window measurements use one
  switch); **«Was gesendet wird»** (fold-out, for both); **«Deine Daten auf dem Server»**.
- **Idle «Messen»:** the status chip only when there is something to do (a problem such as
  «Unterbrochen · tippen für «Neu starten»»); «Auf der Karte · …», «Teilen an …» and «Nicht auf der
  Karte» are no longer shown there («Teilen» shows them; the running screen keeps its chip).
- **Idle «Messen» buttons:** «Langzeitmessung starten» (was «Messung starten») with «Für Stunden am
  offenen Fenster, am Ladegerät.»; under «Laut! Melden» the line «Unterwegs: lauten Verkehr sofort
  melden.» Offline and Labor: the start button with its line only.

## 0.8.7 — Labor·Karte only, not published (branch labor-one-tap): idle Messen screen with logo, actions at the bottom (versionCode 39)

The owner: on the idle «Messen» the buttons were crammed at the top and the bottom two thirds were empty.

- **Idle «Messen» in three zones:** the title on top; in the middle of the free space the app's logo
  (the launcher artwork of the edition on its launcher background: dark blue, Labor red with the record
  dot; 120 dp circle), «Weniger Lärm», one line («Kurz laut? Melden. Oft laut? Messen.»; Offline and
  Labor: «Miss den Lärm vor deinem Fenster.») and the status chip; at the bottom, within thumb reach,
  the 0.8.6 buttons unchanged («Laut! Melden» filled, «Messung starten» tonal with its line, Labor·Karte
  «Unterwegs aufnehmen» outlined with its Labor line; without reports «Messung starten» filled).
- **«Mehr dazu»** is a centred text button that opens a bottom sheet with the same texts and the guide
  link (it folded out under the buttons until 0.8.6), so the layout does not move.
- Small phones and large fonts: the screen scrolls instead of clipping; with room the buttons stay at
  the bottom. The running state is unchanged.
- New colour resource `logo_background` (main: the launcher background; src/labor: the Labor red, which
  the Labor launcher background now references instead of a literal).

## 0.8.6 — Labor·Karte only, not published (branch labor-one-tap): visual polish, no functional change (versionCode 38)

In short (the owner: «more space, add some icons, so the look becomes more balanced»): layout,
spacing, type scale, icons and scheme colours; one display fix (the status chip's day).

- **«Laut! Melden» dialog** in three groups, 20 dp apart: the disc (the «© swisstopo» credit in its
  bottom-right corner, «Nord · 358°» in titleMedium, the hint under it); a tonal card with the level as
  two stat blocks («57,5 dB(A) jetzt» with the graphic_eq icon, «68,4 dB(A) Spitze») and a location row
  with an icon (my_location / location_searching / edit_location with «Zurück zum Standort»); «Was war
  es?» with the six categories as 72 dp buttons, icon over label (Töff two_wheeler, Auto directions_car,
  Lastwagen local_shipping, Flugzeug flight, Poser speed, anderes Fahrzeug more_horiz), 12 dp apart,
  the selected one tonal with a primary outline and a check, «erkannt» in the corner; then «Foto vom
  Fahrzeug (optional)» (56 dp thumbnail with «×» and «Neues Foto» once taken). The rule-10 line with an
  info icon directly above the actions; «Senden» a filled button with the send icon; the megaphone
  above the title. Same behaviour, same texts otherwise.
- **«Messen»:** one height (56 dp) and one label style for «Laut! Melden» (filled), «Messung starten»
  (tonal) and «Unterwegs aufnehmen» (outlined); helper lines in bodyMedium 4 dp under their button,
  16 dp between groups, 24 dp before the status chip (now in body size); «Mehr dazu» with an info icon.
- **Status chip «Auf der Karte · …»** now says the day when the last send was not today («gestern
  18:42», «23.10. 18:42»); until now only the time, so a send just after midnight read «00:00» the
  next morning.
- **Every screen:** one screen title style and 16 dp margins; cards are plain surfaceContainer cards
  (12 dp corners, 16 dp inside); fold-outs with chevron icons instead of «▸/▾»; switches as list rows;
  «Vom Server löschen» / «Alle löschen» outlined in the error colour, delete confirmations in the error
  colour; «Einstellungen» rows with icons and chevrons («Akku-Optimierung ausschalten», «Erweitert»);
  «Messungen»: the selected measurement highlighted, «geteilt» a small chip, empty and loading states
  with an icon; «Teilen» › «Meldungen» as list rows with the category icon; bottom bar icons graphic_eq,
  history, map (AppIcons).
- New Paparazzi renders (PolishScreenshots, ReportDialogScreenshots) of the dialog and the screens,
  light and dark.

## 0.8.5 — Labor·Karte only, not published (branch labor-one-tap) (versionCode 37)

In short (the owner's requests): «Laut! Melden» measures while it is open and sends the level with
the report; a photo replaces the typed licence plate.

- **Level while the dialog is open.** With no measurement running, the microphone runs through the
  same dsp engine as a measurement (`MeasurementEngine`, the phone's calibration offset, in the app's
  process, no classifier, no audio tap), from the dialog's opening to its closing; while a measurement
  runs, the dialog uses its live values and opens no second microphone. Under the disc: «58,2 dB(A)
  jetzt · Spitze 81,4 dB(A)». The report carries `level` {peak LAFmax, LAeq of those seconds, seconds,
  calibrated}; «Teilen» › «Meldungen» shows «· 81 dB». No audio is stored or sent. The microphone
  permission is asked when the dialog opens if it is missing; without it, no level.
- **Server** (schema v8, not deployed): `LevelIn` on reports, four new columns, `reports.json`
  publishes `lafmax_db` in whole dB; the map's report tooltip shows «84 dB». An older server refuses
  the field: the app resends the report without it in the same run.
- **«Foto» instead of «Kontrollschild».** The text field is gone; «Foto» opens the phone's camera app
  (no camera permission), the photo is re-encoded (≤ 1600 px, JPEG 85, no EXIF), shown as a 64 dp
  thumbnail with «×» / «Neues Foto», and kept only in the app's private files with the report. Never
  sent; deleted with the report or alone («Löschen» in the full-screen view of «Meldungen», which shows
  thumbnails). Plates of 0.7.6–0.8.4 reports are still listed. The 0.8.2 text-recognition code
  (`PlateOcr`) is removed. The Labor manifest `report` line says `photo: true/false`.
- PRIVACY.md, README, android/README, server/DESIGN.md updated; the website's datenschutz.html changes
  when this reaches a release (WORKLOG).

## 0.8.4 — Labor·Karte only, not published (branch labor-one-tap) (versionCode 36)

In short (the owner's decision): no mode switch — a running measurement means the phone lies at a
window, no running measurement means the person holds the phone and is on the road, so «Laut!
Melden» is the first button while nothing runs and also opens from an app shortcut and a home-screen
widget. The «Unterwegs» measurement and the «Sehr laut» notification leave the Online-Version and
stay in Labor·Karte only, for comparing the prompt with manual reports.

- **«Messen», idle** (editions with the map): «Laut! Melden» (primary, 56 dp, megaphone) on top, then
  «Messung starten» (tonal), the line «Zum Messen: Telefon ans offene Fenster, am Ladegerät.», the
  status chip, «Mehr dazu». No floating button any more. Offline and Labor builds: «Messung starten»
  primary as before. **Running**: as 0.8.3 (level card, «Stoppen», «Läuft seit …», chip,
  Lärmspitzen), no «Laut! Melden» on the screen. The mode switch, its stored choice and
  `MeasureMode.kt` are gone (`MeasureTexts.kt` keeps the texts).
- **Online-Version: no measurement on the road.** No «Unterwegs» start, no «Sehr laut» notification
  or channel, no «Antippen zum Melden» / «Melden» on the measurement notification: the alert code
  moved from `src/karte` to `src/laborKarte` (the public APK has no `ch/lessnoise/app/alert/*` class
  and no `loud_alert` string any more; new `tools/check_release_apk.py`). The motion detector stays:
  a carried window run pauses sharing and the chip says «Unterwegs erkannt · nichts auf die Karte».
- **Labor·Karte**: a third, outlined button «Unterwegs aufnehmen» («Labor: nimmt Audio auf, teilt
  nichts.») starts the «Unterwegs» measurement exactly as before, with the «Sehr laut» notification and
  the notification's «Melden», to compare the prompt with manual reports.
- **App shortcut «Laut! Melden»** (long press on the icon) and **widget «Laut! Melden»** (one button,
  2×1 cells, plain RemoteViews), Online-Version and Labor·Karte: both open the report dialog with the
  time of the tap and nothing preselected, also on a cold start; they send nothing by themselves. No new
  permission, no new dependency.
- PRIVACY.md and README updated; the website (docs/) changes when this reaches a release.

## 0.8.3 — Labor·Karte only, not published (branch labor-one-tap) (versionCode 35)

In short: «Messen» is redesigned (owner's verdict: the «Laut! Melden» dialog is good, the rest is
still the first version). A mode switch at the top, one primary button, the status as a chip instead
of the beige card, a Material level card, and a colour scheme without the Material purple and beige
defaults (dark as good as light). Same data, same hosts, same two ways to start, no new permission,
no new dependency. The «Laut! Melden» dialog, the service and the upload are unchanged.

- **Mode switch «Am Fenster» | «Unterwegs»** (Material segmented buttons, editions with the map):
  replaces the two start buttons «Messen» | «Unterwegs» and the line under them. Remembered on the
  phone (default «Am Fenster»). Disabled while a measurement runs («Zum Wechseln zuerst stoppen» on a
  tap); it then shows the run's mode, and **«Unterwegs erkannt»** while the motion detector says the
  phone is carried in an «Am Fenster» run (the stored choice stays). Offline and Labor builds: no
  switch, as before no «Unterwegs».
- **One primary action**: «Messung starten» (56 dp, play icon) / «Stoppen» (error colour, stop icon),
  ONE line under it («Am Fenster: Telefon ans offene Fenster, am Ladegerät.» / «Unterwegs: Mitteilung
  bei sehr lautem Verkehr. Nichts kommt auf die Karte.» / «Läuft seit 18:30 · Bildschirm darf aus
  sein.»). The placement and microphone paragraphs and the guide link are folded under «Mehr dazu ⌄».
- **«Laut! Melden»**: in «Unterwegs» (idle, running, and during «Unterwegs erkannt») a floating
  button with a megaphone above the bottom bar; at the window an outlined button under «Messung
  starten» while idle, as before. Same report action and notification preset.
- **Status chip** instead of the beige/pink card, on «Messen», «Teilen» and above the map: icon +
  short text + «›» when tappable — «Auf der Karte · 18:42» (cloud_done → tab «Karte»), «Standort wird
  gesucht …» (spinner), «Kein Standort» (location_off, error colour; the location options as before),
  «Nicht auf der Karte» (cloud_off → tab «Teilen»; until 0.8.2 the tap asked «Auf die Lärmkarte?»
  directly), «Unterwegs · nichts auf die Karte», «Unterwegs erkannt · nichts auf die Karte».
- **Level card**: the bar is a rounded 8 dp Material progress bar coloured by level (primary below
  60 dB, tertiary 60–75, error from 75), «Klingt nach» is a chip inside the same card, the
  Lärmspitzen are Material list items.
- **Colours**: the scheme now sets every role (surface containers, outlines, tertiary container,
  inverse); the lavender bottom bar and the beige secondary container (tab indicator, «geteilt»
  badge) are calm neutral / blue-grey tones of the brand navy. Icons not in the core set (window,
  walk, megaphone, clouds, location) are drawn as vectors in the app (`AppIcons`), no extended icon
  library.

## 0.8.2 — Labor·Karte only, not published (branch labor-one-tap) (versionCode 34)

In short: the tab «Karte» is dark when the app is; «Laut! Melden» has its «Senden» back; the
«Kontrollschild» field has a camera button (no text recognition yet). Same data, same hosts, no new
permission, no new dependency in the Offline and Labor builds.

- **Dark «Karte»**: the map page's own dark style (prefers-color-scheme, docs/map/karte.css with the
  CSS filter on the swisstopo tiles) is used when the app is dark (`isSystemInDarkTheme()`). The
  WebView was always light because the Activity theme is light; it is now created with a dark
  platform theme when the app is dark (`MapDark`). Algorithmic darkening of the WebView stays off;
  old WebViews: force dark with «web theme darkening only». New dependency in the two map editions
  only: `androidx.webkit:webkit:1.12.1`. No website change; allowed hosts and CSP unchanged.
- **«Senden»** (owner's decision): a tap on a category selects it (one, drawn filled; «erkannt»
  kept), «Senden» sends with the compass reading of that tap; enabled with a category and a fix
  present or still searched (then it waits as before). «Antippen sendet die Meldung.» removed; the
  rule-10 line stays. From the «Sehr laut» notification a single suggested category is preselected
  (one tap: «Senden»); two suggestions (Töff/Poser, Auto/Lastwagen — today's usual case) preselect
  nothing.
- **Camera button on «Kontrollschild»**: opens the phone's camera app (no camera permission), the
  photo goes to the app's cache and is deleted at once, unread. **No text recognition**: the bundled
  Google ML Kit was checked and not added (Google Play services, Firebase's logging transport to
  firebaselogging.googleapis.com, about +20 MB); the field says «Foto gelöscht – Erkennung noch
  nicht eingebaut, bitte eintippen». `PlateOcr.bestPlate` (Swiss canton plates, FL, foreign
  fallback) is written and tested for when a recogniser is decided.

## 0.8.1 — Labor·Karte only, not published (branch labor-one-tap) (versionCode 33)

In short: «Laut! Melden» is one dialog, and one tap on what was loud sends the report. Until 0.7.8
it took three dialogs and three taps («Was war laut?» → «Wohin fuhr es?» → «Meldung … – senden?»).
Same data, same hosts, the one fix, the plate on the phone only. 32/0.8.0 is the public release on
another branch; this build carries the next number so it installs over it on the test phone.

- **One dialog «Laut! Melden»** (after the first-use consent, unchanged): the compass disc with the
  streets (200 dp, was 240 dp; tiles still Labor·Karte only) and «Nordost · 47°», the line «Telefon
  in die Fahrtrichtung halten» (or the figure-eight hint), «Standort wird gesucht …» / «Standort ± n m»,
  «Kontrollschild (optional)», the six categories in two columns (suggested ones first with
  «erkannt», now under the label), «Antippen sendet die Meldung.», the rule-10 line «Wer oft vom
  gleichen Ort meldet, macht ihn auf der Karte sichtbar.», and «Abbrechen». Scrollable on small
  screens; a tap outside does nothing.
- **Tapping a category sends**: that category, the compass reading of that instant (true north with
  the declination at the fix; none without a compass or before its first reading), the plate, the
  time, the fix. **No confirmation any more** (owner's decision): «Senden», «Richtung übernehmen» and
  «Überspringen» are gone.
- **The fix starts when the dialog opens** (was: after the category), so it is usually there before
  the tap. Tapped earlier: the buttons are disabled under a progress bar and the report goes out when
  the fix arrives. No fix: «Kein genauer Standort» → «Nochmals» returns to the dialog with the category
  kept. Labor·Karte: the swisstopo tiles are therefore requested as soon as the dialog has its fix,
  also if the person then cancels (PRIVACY.md).
- **Move the map under the dot** (owner's request: the car drove away, or the fix is off; Labor·Karte,
  where the streets are drawn): a one-finger drag on the disc moves the map, the dot stays at the
  centre. The drag is turned back by the disc's rotation, so up the screen moves along where the
  phone points; while the finger is down the disc does not follow the compass; the map stops 2 km
  from the fix. Under the disc «Standort von Hand gesetzt» and «Zurück zum Standort» (back to the
  fix). The tap then sends the place under the dot with a fixed accuracy of 15 m
  (`MANUAL_ACCURACY_M`) and its own 100 m cell in the local list; not moved, the fix as before. After
  the finger lifts (300 ms) the tiles touching the 150 m circle around the new centre are requested
  (tiles already in memory not again, at most 64 kept; same host, 4 at a time, 512 kB, Switzerland
  only): swisstopo can then also see the area of the place set by hand (PRIVACY.md). The Online-Version
  and the stand-in have no drag. Pure maths in `ReportMapTiles` (`screenDragToMapOffset`,
  `offsetToLatLon`, `latLonToOffset`, `clampOffset`, `evict`) and `LocationFlows` (`reportMovedPlace`,
  `reportPlace`); `Panel(reading, fix, moved, onMove)`.
- Code: `ReportDirectionUi` keeps the disc (`Panel`) and the compass (`rememberReading`, which owns
  the sensors and the orientation lock for the dialog's lifetime); the Step composable is gone; the
  `src/nomap` stand-in has the same API. `reportConfirmDialogText`, `reportConfirmText` and
  `reportPlateLine` are deleted (nothing uses them). New pure helpers `reportCategoryRows`,
  `reportNext` (CHOOSE / WAIT / SEND / FAILED), `reportFixLine`.
- Tests: new `ReportFlowTest`; `ReportsTest`, `ShareTextsTest`, `ReportDirectionTest` no longer test
  the confirmation texts; `LoudAlertIsolationTest` pins the stand-in's API; `ReportMapTilesTest` the
  200 dp disc and (moving the map) the drag rotation at 0°/±90°/180°, 100 px = 150 m on a 200 px disc,
  the 2 km stop and the tile eviction; `ReportFlowTest` the moved place (15 m, its own cell, 2 km);
  `EditionTextTest` 33 / 0.8.1.

## 0.8.0 — 2026-10-24 (versionCode 32; published: Offline-Version and Online-Version on the website)

In short: the first published version since 0.7.2. It brings the changes of the unpublished
Labor·Karte builds 0.7.3–0.7.8 that concern «Laut! Melden» and the «Sehr laut» notification to the
**Online-Version**: one notification instead of two, a compass with the real streets under the
arrow, an optional licence-plate field that stays on the phone, the new category «Flugzeug», and an
«erkannt» marker instead of pre-filled buttons. **One privacy change:** while «Wohin fuhr es?» is
open, the Online-Version now loads swisstopo's map tiles around your true position (below). The
**Offline-Version** has none of these features (no reports, no notification, no map); for it 0.8.0
changes nothing you can see. No new permission in either edition.

- **One notification for «Sehr laut»** (Online-Version). A very loud vehicle while «Unterwegs» no
  longer adds a second notification: the measurement's own notification turns into the alert for
  30 s («Sehr laut: Töff & Poser, 84 dB – Antippen zum Melden», with sound and vibration on the
  channel «Sehr laut»), with «Melden» and «Stopp»; then it shows the level again (0.7.6).
- **Compass with real streets in «Wohin fuhr es?»** (Online-Version). The arrow stands in a compass
  rose that turns with the phone (0.7.6), and once the report's location is found, swisstopo's grey
  map of the ≈ 300 m around you lies under it, turned like the ground in front of you (0.7.8; until
  then «Standort wird gesucht …»). The dialog is wider (94 % of the screen) for the larger disc.
  **Privacy:** for this the app requests from swisstopo (the Federal Office of Topography,
  `wmts.geo.admin.ch`, the same host the tab «Karte» already uses) the tiles touching the 150 m
  circle around your **true** position — GET only, no redirects, no cache, no cookies, only inside
  Switzerland, only while that step is open, kept in memory only. swisstopo sees those requests
  with your IP address, like a visitor looking at that area on the map; nothing of it is stored or
  sent to the project's server. docs/datenschutz.html, PRIVACY.md and server/DESIGN.md §2 rule 7
  say so.
- **«Kontrollschild (optional)»** in the confirmation of «Laut! Melden» (Online-Version): one text
  field, at most 12 characters. The plate is stored **on the phone only** (report queue and the list
  of sent reports under «Teilen»), never sent; the report body is unchanged (0.7.6).
- **New category «Flugzeug»** (Online-Version, server, map): Töff, Auto, Lastwagen, Flugzeug, Poser,
  anderes Fahrzeug; on the map marker «F». The server accepts `flugzeug` from this release on (it
  deploys from `main` with this version); a «Flugzeug» report sent to a server without it would be
  refused and dropped (0.7.7).
- **«erkannt» instead of filled buttons** in «Was war laut?» (Online-Version): every category button
  looks alike; one that fits the «Sehr laut» event has a small «erkannt» after its label, so nothing
  looks preselected (0.7.7).
- **Not in the published editions** (Labor builds only, unchanged by this release): the event head
  v2 scores in the Labor manifest (0.7.3, logging only; the public editions do not run the head),
  «In Ordner exportieren» (0.7.4), the Labor storage cap as an audio budget with warning and start
  block, the 5 GB default (0.7.5), the position rule of Labor·Karte (0.7.5; the published editions
  have no position signal), the Labor·Karte `report` manifest line (0.7.6), and signed Labor builds
  from GitHub Actions (0.7.4).
- **Release builds from GitHub Actions** (`.github/workflows/release-build.yml`): both published
  APKs are built and signed by a workflow with the repository's keystore secrets and checked there
  (signer certificate, package and version, permissions per PRIVACY.md, no Labor classes, no map,
  WebView, alert or motion classes in the Offline-Version), so no session needs the key.
- Tests: `UploadSourceIsolationTest` and `ReportMapTilesTest` pin that the tile download has no
  edition flag any more, is started only by the direction step with a fix inside Switzerland, and
  is compiled only into the editions with the map (public, laborKarte).

## 0.7.8 — Labor·Karte only, not published (branch labor-report-ui) (versionCode 31)

In short: in «Wohin fuhr es?» the reporter now sees the real streets around them under the arrow,
turning with the phone, so they can recognise where they stand and turn the phone to where the
vehicle went. **Labor·Karte only, and a privacy change there:** swisstopo (the Federal Office of
Topography, `wmts.geo.admin.ch`) receives, at report time, map-tile requests for the area around the
reporter's true position, with the phone's IP address (PRIVACY.md, «Internal variant `laborKarte`»).
The Online-Version shows the compass rose without tiles and requests nothing; its privacy page
(docs/datenschutz.html) must say so in the same change that enables it there. Offline and Labor
(without the map) unchanged. Nothing new is sent to the project's server.

- **Streets under the arrow** (Labor·Karte, `BuildConfig.LABOR_UNTERWEGS`). Once the location fix
  is there, swisstopo's grey map (the website map's tiles) at zoom 18 around the fix, 300 m across
  the 240 dp disc, turned by the true heading, faint (60 %) and fading in, the reporter as a dot at
  the centre, «© swisstopo» below. 9–16 tiles (every tile touching the 150 m disc; a zoom-18 tile
  is ≈ 103 m, so 3 × 3 would leave the disc's edge empty), downloaded while the step is on screen
  only, 5 s timeouts, in memory only, cancelled when the step closes; a missing tile is simply not
  drawn. While the fix is searched: the rose alone and «Standort wird gesucht …».
- **Wider dialog** for the 240 dp disc (94 % of the screen width; until 0.7.7 a 160 dp disc in the
  platform's dialog width). The 0.7.6 decorative street lines are gone in every edition.
- Tests: `ReportMapTilesTest` (Web Mercator tile maths against a Python fixture, round trips, tile
  coverage of the disc, 300 m at any screen density, the URL equals docs/map/karte.js `TILES`, the
  flag); `UploadSourceIsolationTest` allows network code in `ReportMapTiles.kt` and pins its one
  address, GET only, no redirects, no cache and the flag.

## 0.7.7 — Labor·Karte only, not published (branch labor-report-ui) (versionCode 30)

In short: «Was war laut?» no longer looks like a multiple choice when it comes from the «Sehr
laut» notification, and «Laut! Melden» has a new category «Flugzeug». The code is in `src/main` and
`src/karte`, so the Online-Version would get the same changes when it is next built; only
Labor·Karte is built at this version. **The server must have the schema change deployed before a
«Flugzeug» report is accepted**: until then the server rejects it (field `category`) and the app
drops it from the queue as it drops every refused report (it is counted in `refusedTotal`, which
«Teilen» shows as «n Werte waren zu alt und wurden nicht übernommen.»; it is not in the list of sent
reports and is not sent again). Offline and Labor (without the map) unchanged.

- **Marker instead of filled buttons** (editions with «Laut! Melden»). From the «Sehr laut»
  notification the fitting categories were filled buttons and the others outlined, which read as
  if they were already selected. Now every category button is outlined alike; a fitting one has a
  small «erkannt» in the accent colour after its label. The fitting ones still come first, «Erkannt
  um 11:14» stays, and nothing is picked.
- **New category «Flugzeug»** (id `flugzeug`), between «Lastwagen» and «Poser»: Töff, Auto,
  Lastwagen, Flugzeug, Poser, anderes Fahrzeug. The «Sehr laut» alert still fires only for
  `loud_vehicle` and `road_traffic` (`LoudAlertRule.CATEGORIES` unchanged); should it ever fire for
  `aircraft`, «Flugzeug» is the suggested category. Server: `flugzeug` in `REPORT_CATEGORIES`
  (schemas.py) and in `reports.json` `categories`; map: marker «F», legend «Flugzeug». Nothing new
  leaves the phone: a report still carries time, location, accuracy, category and the optional
  direction.

## 0.7.6 — Labor·Karte only, not published (branch labor-report-ui) (versionCode 29)

In short: «Sehr laut» is one notification instead of two, the compass step «Wohin fuhr es?» shows a
compass rose that visibly turns with the phone, and «Laut! Melden» has an optional licence-plate
field that stays on the phone. Nothing new is sent to the server; no new permission. The code is in
`src/main` and `src/karte`, so the Online-Version would get the same changes when it is next built;
only Labor·Karte is built at this version. Offline and Labor (without the map) unchanged.

- **One notification for «Sehr laut»** (editions with the map). Until 0.7.5 a very loud vehicle
  while «unterwegs» posted a second notification (id 7501) next to «Messung läuft». Now the alert
  replaces the content of the measurement's own notification for 30 s: one heads-up with sound and
  vibration on the channel «Sehr laut» (unchanged, importance high), title «Sehr laut: Töff & Poser,
  84 dB», text «Antippen zum Melden», tap and a «Melden» button open «Laut! Melden» with the event's
  time as before, «Stopp» stays. During the 30 s neither the level text nor a change of the report
  tap overwrites it; then the next post restores the normal notification (silent channel, level
  text or «Antippen zum Melden», whatever is in force then). `LoudAlertNotifier` hands the alert to
  `MeasurementService` (`ACTION_LOUD_ALERT`); the window is a pure rule in `NotificationCadence`
  (`alertUntil`, `alertOpen`, `levelDue`), tested in `ServicePolicyTest`. If the owner switched the
  «Sehr laut» channel off, nothing replaces the notification.
- **Compass rose in «Wohin fuhr es?»** (editions with the map). The fixed arrow (where the phone
  points) now stands in a 160 dp compass rose that turns with the phone: ring, 16 ticks, the letters
  N, O, S, W (N larger, in the accent colour) and a faint schematic street grid that turns with it
  (decorative, no map data). Live label «Nordost · 47°», calibration hint, buttons, orientation lock
  and sensor lifecycle unchanged.
- **«Kontrollschild (optional)»** in the confirmation of «Laut! Melden» (editions with «Laut!
  Melden»): one text field, capitals, at most 12 characters, no format check (trimmed, spaces
  collapsed). The plate is stored **on the phone only**: in the report queue and, after sending, in
  the list of sent reports («24.10. 16:05 · Töff · ZH 123» under «Teilen» › «Meldungen»); the report
  body sent to the server is unchanged and has no plate (`ReportsTest.thePlateIsNeverSent`). The
  confirmation shows «Kontrollschild: ZH 123» as a last line. Labor·Karte also writes a `report` line
  (time, category, plate, direction; no coordinates) to the local `manifest.jsonl` at «Senden».

## 0.7.5 — Labor·Karte only, not published (branch labor-storage) (versionCode 28)

In short: a full Labor storage cap no longer silences the manifest, the app warns before the cap
and refuses to start a recording measurement at it, new installs get 5 GB, and the position no
longer turns a still phone «unterwegs». Offline- and Online-Version unchanged and not built.

- **The cap is an audio budget** (Labor and Labor·Karte). When «Maximaler Speicher» is used up,
  clips and hour files stop as before, but `event` lines (features and, in Labor·Karte, the event
  head's scores), `clock` lines and the classifier log go on, up to 200 MB beyond the cap
  (`StorageBudget.LOG_MARGIN_BYTES`, ≈ 200 h of logging; then one `logs_full` line and only
  session lines). Before, a full cap stopped the whole recorder: from 2026-10-18 13:05 the owner's
  manifest got no `event` and no `clip` line for five days, only `motion_minute` and one
  `storage_full` per session. `storage_full` is written once when it happens (and once per session
  start while still full, now with `"audio":false,"logging":true`); `session_stop` gains
  `clipsSkipped` (events without a clip because of the cap) and `linesDropped`.
- **Warning and block.** With audio recording on, a red line «Speicher fast voll: n MB frei von x GB
  – Aufnahme würde bald stoppen. Exportieren und löschen, oder Limit erhöhen.» on «Messen» and in
  Einstellungen › Labor once less than max(10 % of the cap, 200 MB) is free. At the cap «Messen»
  (and «Unterwegs») does not start and says why: export and delete, raise the limit, or switch
  recording off. Pure rule `StoragePolicy` (Policies.kt), tested.
- **Default cap 5 GB** for new installs (choices unchanged; a stored setting keeps its value).
- **Position rule (Labor·Karte):** the position may switch the run to MOVING only if the motion
  detector saw movement within the last 3 minutes; fixes worse than 100 m (was 500 m) are ignored,
  and so is a fix of a still phone whose distance jumped faster than 30 km/h. In the night
  2026-10-22→23 a still phone (motion median ≈ 0.002 m/s²) was MOVING for 508 minutes because fused
  fixes wandered 900–2100 m. `byMotion`/`byPosition` are logged as before.

## 0.7.4 — Labor·Karte only, not published (branch labor-export) (versionCode 27)

In short: the recordings can leave the phone again — incremental export to a folder of your choice
— and signed Labor builds now come from GitHub Actions (`.github/workflows/labor-build.yml`), so no
session needs the signing key. Head v2 logging from 0.7.3 unchanged. Offline- and Online-Version
not built at this version (website stays at 0.7.2).

- **«In Ordner exportieren»** (Labor and Labor·Karte, Einstellungen › Labor) replaces «Ordner
  anzeigen», which opened `Android/data/…` that Android 11+ hides from file managers. The first
  press (or «Ordner wählen») opens Android's folder picker — Downloads, USB or the Google Drive
  app —, the grant is persisted, and every later press copies only the files that are not yet
  there (ledger of path + size + lastModified; the manifest always, the hour file being written
  never). Progress and a final «Exportiert: n Dateien, x MB nach …» line in the section.
- **«Als ZIP teilen»** no longer refuses when the hour files exceed 500 MB: the ZIP leaves them
  out and the dialog says so (the folder export covers them).
- Nothing changes in the Offline- and Online-Version; no new permission (the folder grant is a
  per-folder URI permission from the picker, not a manifest permission).

## 0.7.3 — Labor·Karte only, not published (branch event-head) (versionCode 26)

In short: the Labor·Karte build logs the event head's scores (v2) next to the old category —
logging only; nothing the app shows, stores or uploads changes. No new permission. The Offline-
and Online-Version were not built or published at this version (the website stays at 0.7.2).

- **Event head v2 bundled:** `head_F1_MLP_v2.json` (Labor `step3/train`, retrained on the complete
  v2 labels; same schema, same 68 inputs in the same order, same enabled classes carried / road /
  aircraft, new weights and thresholds) replaces `head_F1_MLP_nosit.json`. The manifest's
  `"head":{"model":…}` now reads `weniger-laerm-head-F1-MLP/head_F1_MLP_v2` (JSON name + bundled
  file), so lines written by v1 and v2 can be told apart. Golden values regenerated (only the
  probabilities and decisions changed; features and inputs are identical).
- **Event head, Labor editions only, logging only:** the dsp module ports the event features of the
  Labor multi-label head (first `head_F1_MLP_nosit`, now v2: envelope periodicity, 1/3-octave rises, spectral
  centroid/flatness/tonality, onset, …) and runs the head per event (`EventHead`, JSON weights
  bundled in dsp). Its per-class probabilities and decision go into the Labor manifest's `event`
  (with the model inputs) and `clip` lines. The category shown, stored and uploaded is unchanged
  (YAMNet); the public editions do not run it. Details: `dsp/EVENT_HEAD.md`.

## 0.7.2 — 2026-10-20 (versionCode 25; published: Offline-Version and Online-Version on the website)

In short: Gemessen wird mit dem eingebauten Mikrofon; ein anderes Mikrofon lässt sich unter
«Einstellungen → Erweitert → Mikrofon» wählen. No new permission.

- **Manual microphone choice (replaces the 0.7.1 refusal):** by default the app stays on the
  built-in microphone and never switches to a headset by itself. «Einstellungen → Erweitert →
  Mikrofon» lists the connected microphones (built-in first, «Standard»); the choice is remembered
  per microphone (type + product name + address, stored as kind + hash). A chosen microphone is
  used whenever it is connected — at the start and when it is connected or disconnected during a
  measurement, which then continues with that microphone and its calibration; otherwise the
  built-in one, with «Mikrofon: eingebaut (gewähltes nicht verbunden)» on «Messen». Classic
  Bluetooth headsets (HFP/SCO) are listed «nicht nutzbar» (they would need call routing, which the
  app never starts); USB, wired and LE Audio microphones are pinned with setPreferredDevice. If
  Android routes another input, the capture is restarted once, then the start fails with «Android
  nimmt ein anderes Mikrofon als das gewählte. Bitte nochmals starten.»
- **Calibration per microphone:** database v10 — calibrations, minutes and events carry the
  microphone; existing rows are the built-in one. «Kalibrieren» calibrates the microphone in use and
  names it («Mikrofon»); a measurement with a microphone that was never calibrated is uncalibrated.
  «Alte Messungen anpassen» stays within one microphone; the «Messgrenze» is the built-in one's.
- **Sent:** a minute measured with another microphone sends its kind in `audio_source`
  («UNPROCESSED+usb», «+bluetooth», «+wired»); built-in minutes send exactly what 0.7.1 sent.
  Server: accepts these values (server/DESIGN.md §4.3); until it is redeployed such minutes are
  refused per record and stay on the phone. Privacy texts updated (datenschutz.html, PRIVACY.md).
- Texts: Anleitung «Messen» step 1 and «Kalibrieren» (calibration is per microphone).
- Tests: MicChoiceTest (identity, list, resolution table, route check, wire format vs. the server's
  list), MicrophoneDbTest (migration 9 → 10, calibration lookup per microphone, payload, re-evaluation
  keeps the microphone), noise floor and re-evaluation scope per microphone, UI lines; server test
  for the new `audio_source` values.

## 0.7.1 — 2026-10-20 (versionCode 24; published: Offline-Version and Online-Version on the website)

In short: Misst nur mit dem eingebauten Mikrofon; Kopfhörer-Mikrofone werden abgelehnt. And 0.7.0
(below) reaches the website. No new permission, nothing new stored or sent.

- **Built-in microphone (since 0.6.6, now with the owner's wording):** the app pins the phone's
  built-in microphone and checks which input Android actually routes after the start and on every
  routing change. If it is a headset (Bluetooth SCO, LE Audio, wired, USB), it does not measure and
  says «Das Telefon misst nur mit dem eingebauten Mikrofon. Bitte Kopfhörer oder externes Mikrofon
  trennen.» (was: «Gemessen wird nur mit dem Mikrofon des Telefons. Bitte das Headset trennen und
  neu starten.»). Never starts Bluetooth SCO, never requests a communication device.
- **«Mikrofon: eingebaut»** next to «Audioquelle» in «Kalibrieren» (every edition), once this screen
  or the running measurement has opened the microphone; any other input would read e.g.
  «Mikrofon: Bluetooth-Headset!». The log names the number of built-in microphones (no names).
- Tests: the policy as one table (inputs → pinned id, routed type → measure/refuse), the fact text,
  the refusal text, and an isolation test (no Bluetooth/communication-device calls, manifests'
  permissions pinned).

## 0.7.0 — 2026-10-17 (versionCode 23; Labor · Karte build for the owner's phone, not on the website)

In short: one version for three lines of work that were built separately as 0.6.7–0.6.9 (the
entries below): «Unterwegs erkannt», «Laut! Melden» at any location accuracy, and the direction of
travel in «Laut! Melden». versionCode 23 is above every earlier build, so it installs over 0.6.9.

Built on 0.6.6 + branch auto-unterwegs (0.6.7–0.6.9/22) + branch report-any-accuracy (0.6.7/20).

- **«Unterwegs erkannt»** with «Antippen zum Melden» and, in Labor · Karte only, the «Melden» button
  on the notification, trams and trains, and the position as a second signal: as in 0.6.7–0.6.9 of
  branch auto-unterwegs, below.
- **Reports at any location accuracy:** as in 0.6.7 of branch report-any-accuracy, below (server:
  `accuracy_m` up to 10 000 m).
- **Direction of travel (new, editions with the map):** after «Was war laut?» the dialog «Wohin fuhr
  es?» shows a live arrow of where the phone points (held flat: the top of the screen; held upright:
  the back of the phone). «Richtung übernehmen» keeps it, «Überspringen» sends the report as before.
  A hint to move the phone in a figure-eight appears only while the compass reports low accuracy.
  Not shown on phones without a compass. The report then carries one bearing in whole degrees (true
  north from the declination at the report's place; magnetic if that is not available) and the
  compass's accuracy class; the confirmation says e.g. «Richtung Nordost». No new permission.
- **Server and map:** the server (schema v6) accepts and stores the optional `direction`; the map
  draws a small arrow on the report's point. Against a server without it (the deployed one until it
  is updated) the app sends the report again without the direction instead of losing it. Not snapped
  to the street: no road data exists in the app or on the server (server-side follow-up).

## 0.6.9 — Labor·Karte only, not published (branch auto-unterwegs) (versionCode 22)

- **Labor·Karte only** (flag `LABOR_UNTERWEGS`; the Online-Version behaves as 0.6.8, Offline and Labor
  unchanged): a **«Melden»** button next to «Stopp» on the «Messung läuft» notification during every
  run (opens «Laut! Melden» at the time of the tap, nothing preselected); the level text stays in every
  state. Motion detector for trams and trains: enters at 0.07 m/s², stays from 0.05 m/s² (was 0.25).
  The **position as a second signal** in «Messen» runs: about once a minute at low power, only while the
  run goes, only with a location permission already granted (foreground-service type «location»); only
  the first fix and the latest distance in memory, never stored or sent; «unterwegs» after 4 fixes in a
  row more than max(150 m, both accuracies) from the start. The run is «unterwegs» if motion or position
  says so. Readout «Bewegung 0,12 m/s² · 400 m · unterwegs» on «Messen», one `motion_minute` line per
  minute in `manifest.jsonl`. New permission: `FOREGROUND_SERVICE_LOCATION`.

## 0.6.8 — not published (branch auto-unterwegs) (versionCode 21)

In short: while a measurement is «unterwegs», a tap on the «Messung läuft» notification opens
«Laut! Melden» — report a poser you see yourself. No new setting, no new permission. Nothing new
is sent.

- **Melden aus der Mitteilung** (Online-Version and Labor·Karte; not in the Offline and Labor builds):
  while the run was started with «Unterwegs», or a «Messen» run is «Unterwegs erkannt», the ongoing
  measurement notification says «Antippen zum Melden» (title unchanged) and its tap opens «Laut!
  Melden» with the time of the tap and no category preselected; then as always (one location fix with
  the app open, confirmation, «Senden»). When the phone lies still again, the notification shows the
  level and opens the app as before. Re-posted only when that state changes. Own intent action
  (`REPORT_NOW`) and request code (3), so it is never mistaken for the «Sehr laut» tap, which is
  unchanged. Works while the app is already open (single-top, `onNewIntent`).

## 0.6.7 — not published (branch auto-unterwegs) (versionCode 20)

In short: «Unterwegs erkannt» — a measurement started with «Messen» notices that the phone is
being carried, alerts like «Unterwegs» and shares nothing of that time span. No new setting, no new
permission. Nothing new is sent.

- **Unterwegs erkannt** (Online-Version and Labor·Karte; not in the Offline and Labor builds): while a
  «Messen» measurement runs, the app reads the accelerometer on the phone (20 Hz, batched up to 5 s).
  About 20 s of walking or cycling switch to «unterwegs», about 3 minutes lying still switch back; a
  bump, picking the phone up or a red light do not. Meanwhile the «Sehr laut» notification works
  exactly as in «Unterwegs», «Laut! Melden» is shown under «Stoppen», the status line says
  «Unterwegs erkannt: Mitteilung bei sehr lautem Verkehr, nichts auf die Karte.», and no minute or
  event of that span is sent (database v9: table `moving_spans`, start and end only). While a «Messen»
  measurement runs, the newest records wait 35 s before they may be sent (loud events reach the live
  map after about 35 s instead of 30 s). The «Unterwegs» button is unchanged.
- **Labor·Karte only:** every change still ↔ carried is a `motion` line in `manifest.jsonl` with the
  numbers that decided it; Einstellungen › Labor shows «Bewegung» while measuring.

## 0.6.7 — not published (branch report-any-accuracy) (versionCode 20)

In short: «Laut! Melden» sends a report at whatever accuracy the phone's location has. Nothing new
is sent.

Built on 0.6.6. (Asked for as part of «0.6.5»; 0.6.5 and 0.6.6 already existed, so it is 0.6.7.)

- **Reports at any location accuracy:** until 0.6.6 a report needed a fix of ± 50 m or better and
  otherwise stopped with «Zu ungenau (± 107 m) – am Fenster mit WLAN klappt es meist besser». Now
  the fix is used as it is; the confirmation shows its accuracy as before («Meldung: Töff, 16:05,
  Standort ± 107 m – senden?») and the report carries it to the server as before. Still needed:
  «Genauer Standort» allowed, a place in Switzerland. No fix at all (or one without any accuracy)
  → «Kein Standort gefunden» and «Nochmals», as before.
- **Unchanged:** the fix for a shared measurement (± 75 m at most, only the shifted point leaves
  the phone).
- **Server:** accepts a report's `accuracy_m` up to 10 000 m (was 200 m; a bound against nonsense,
  no quality gate). Until the server is redeployed, a report with a fix worse than ± 200 m is
  refused by the server and counted as refused on the phone.

## 0.6.6 — 2026-10-17 (published on the website) (versionCode 19)

In short: the app measures only with the phone's own microphone, never with a connected headset's.
No new setting, no new permission. Nothing new is sent.

Built on 0.6.5 (which was not on the website; the website goes from 0.6.4 to 0.6.6).

- **Built-in microphone only:** the app pins the phone's built-in microphone before it starts
  recording and checks afterwards which input Android really uses, also when a headset connects
  during a measurement. A headset microphone (Bluetooth, LE Audio, cable, USB) is tuned to the
  wearer's voice and the calibration belongs to the phone's microphone, so the app does not measure
  with it: it stops and says «Gemessen wird nur mit dem Mikrofon des Telefons. Bitte das Headset
  trennen und neu starten.» (on «Messen», and in «Kalibrieren»). A phone that reports no built-in
  microphone measures as before.
- **Labor only:** Einstellungen › Labor shows «Mikrofon» (which input delivers the audio) while
  measuring; the manifest's `session_start` has `inputDevice`.

## 0.6.5 — 2026-10-16 (versionCode 18; sent to the owner's phone, not on the website)

In short: the app estimates its own measurement limit («Messgrenze») from the minutes it has
already measured and marks values at that limit with «≤». No new setting. Nothing new is sent.

Built on 0.6.4.

- **Noise floor per phone, estimated:** for the audio source the app measures with, the 2nd
  percentile of the valid minutes' L90 without their calibration offset, over all stored minutes;
  only from 60 such minutes on, so a single glitch minute far below is ignored. Shown with the active
  calibration (it moves when you calibrate); a minute is «an der Messgrenze» if its L90 is at most
  3 dB above the floor. Computed with one database query at app start and after each measurement,
  never while measuring. It is an estimate, not a measurement of the microphone: a phone that has
  only measured loud places has no quiet minutes, and its estimate is then too high until quieter
  minutes come in.
- **Where it shows:** «Grundrauschen» in the measurement list and the chart's tile reads «≤ 34,0
  dB(A)» (floor + 3 dB) when at least half of the measurement's valid minutes are at the limit,
  with the line «Leiser als das Telefon messen kann.»; the chart draws a light band «Messgrenze»
  from the bottom up to floor + 3 dB; «Grundrauschen» on «Messen» shows «≤ …» when the current
  5-minute L90 is at the limit; Erweitert › Kalibrieren says «Messgrenze dieses Telefons: ca. 34
  dB(A) (aus N Minuten geschätzt)». Below 60 minutes none of this appears.
- **Export:** the minutes CSV ends with a new column `at_floor` (0/1); no column was renamed or moved.
- **Unchanged:** events (they already need 6.5 dB over the local floor and the event floor), the
  upload and what it sends, PRIVACY.md. The removed check «Eigenrauschen messen» stays removed.

## 0.6.4 — 2026-10-16 (published on the website) (versionCode 17)

In short: only machine and motor noise leaves the phone; «Unterwegs» is a way to start a
measurement that shares nothing; «Laut! Melden» says «anderes Fahrzeug». Nothing new is sent.

Built on 0.6.3 and on the map server of 2026-10-16 (which keeps only machine and motor noise).

- **Only map events are sent:** one rule (`Payloads.isMapEvent`) for the upload and for the live
  events upload: category «Töff & Poser», «Strassenverkehr», «Tram & Bahn», «Flugzeug» or
  «Baustelle», not wind, excess setting ≥ 6.5 dB, LAFmax ≥ max(floor setting, 30 dB(A)), ≥ 0.5 s
  long — the same checks as the server. Voices, music, unclassified sounds and wind never leave the
  phone and no longer cause an upload request. Not a setting. No new field in the requests.
- **«Unterwegs» is a start mode** (Online-Version): «Messen» shows two buttons, «Messen» and
  «Unterwegs», with one line. A measurement started with «Unterwegs» measures, shows the «Sehr laut»
  notification (unchanged, also uncalibrated) and offers «Laut! Melden» while it runs, but shares
  nothing: no question, no location, no minutes, no events, whatever the sharing switch says; the
  status line says «Unterwegs – diese Messung kommt nicht auf die Karte». «Messen» shares as before
  and never shows the notification. The 0.6.3 switch is gone (database v7 → v8: column
  `measurements.unterwegs`).
- **«Laut! Melden»:** the category «anderes» is now «anderes Fahrzeug» (the id sent stays `andere`).
- **Texts:** «Was gesendet wird» and the question «Auf die Lärmkarte?» say «Lärmspitzen von Motoren
  und Maschinen»; PRIVACY.md, the website's privacy page and the guide describe the new rule and
  «Unterwegs». The Labor export is named `lessnoise-labor-…zip`.

## 0.6.3 — 2026-10-15 (published) (versionCode 16)

In short: one question «Auf die Lärmkarte?» at the first «Starten» replaces the old sharing gate;
measuring starts at once and the location is taken in parallel; shared values reach the map within
about a minute (the 30-second map server); the Online-Version gets a tab «Karte» with the website's
map and an optional «Unterwegs» notification for very loud traffic. The Offline-Version still has
no internet and no location permission and contains no map, WebView or notification code of ours.

Built on 0.6.2. Sharing made simple and the map in the app («Online-Version» only; the Offline-Version
and the Labor build behave exactly as before).

- **One question at the first «Starten»:** «Auf die Lärmkarte?» — three lines (what is sent, what
  never, how to delete), «Mehr dazu» for the full explanation, «Ja, auf die Karte» / «Nur auf dem
  Telefon». Asked once; the switch in «Teilen» changes it later. The «Weiter» gate in «Teilen» is gone.
  Updating from 0.6.2: sharing on counts as yes, everybody else is asked once.
- **Measuring never waits for the location:** the measurement starts at once; the one location fix
  runs while the app is open and its shifted point is attached to the running measurement, which is
  then shared as a whole (also the minutes before the fix). No fix: no dialog, a line «Kein Standort –
  tippen» with the options. Switching sharing on during a measurement shares that measurement.
- **Live map:** while a shared measurement runs, the first upload about 30 s after the point is
  known, each loud moment about 30 s after it ended (usually one request), the minutes every 2
  minutes; at once after «Stoppen»; hourly as before. After a 429 the app slows down, nothing is
  lost. Mobile data is allowed by default (at most about 3 MB a day measuring round the clock;
  «Nur über WLAN» is reset once to off). The map page refreshes every 30 s while visible.
- **One status line** on «Messen» and «Teilen»: «Standort wird bestimmt …», «Wird geteilt – erste
  Werte in etwa einer Minute», «Auf der Karte · zuletzt gesendet 14:05», «Kein Internet – wird
  nachgeholt» …
- **«Teilen» shorter:** status and switch on top; «Was gesendet wird ▸» and «Wo liegt das Telefon?
  (freiwillig) ▸» folded.
- **New tab «Karte»:** the website's noise map in the app (WebView, loaded only when the tab is
  opened, no other host, no cookies, no location; opens at the shifted point of the newest shared
  measurement via `karte.html#lat=…&lon=…&z=16`). The website's map page reads that new view fragment.
- **Back undoes tab switches:** every switch pushes the tab you leave, back returns to it (also from
  «Messen»), and only with nothing left does back leave the app; the map page's history no longer
  swallows it.
- **The map centres on your point whenever the tab is shown:** the page is kept across tab switches
  (paused while hidden; the page refreshes its data itself every 30 seconds while visible) and moved
  with `location.replace('#lat=…')` as soon as the point exists or changes — no reload, no history
  entry. The website's map page checks for new data every 30 seconds while visible (also on the web).
- **«Unterwegs» and «Sehr laut»:** a switch under «Starten» (off by default). While it is on and you
  measure, a very loud vehicle (≥ 75 dB, «Töff & Poser» or «Strassenverkehr», never wind) shows a
  notification «Sehr laut: Töff & Poser, 84 dB – Antippen zum Melden», at most one every 2 minutes;
  a tap opens «Laut! Melden» with the time of the noise and the fitting categories highlighted, also
  during a measurement. Online-Version only.
- **A banner above the map** while your values are not visible yet: «Noch kein Standort», «Erste
  Werte werden gesendet – auf der Karte in etwa einer Minute», «Gesendet HH:MM – die Karte
  aktualisiert sich laufend» (2 minutes) or «Du teilst nicht – tippen zum Teilen».

## 0.6.2 — 2026-10-14 (versionCode 15)

Built on 0.6.1. Four changes; the detector, the CSV columns and everything the offline edition does
are unchanged.

- **Location noise instead of hectare snapping** («Online-Version»): the place of a shared
  measurement is no longer snapped to the 100 m hectare. At «Starten» the app turns the one location
  fix (or pasted coordinates) into the true LV95 point and moves it at once by a Gaussian offset
  (independent N(0, σ²) east and north); only the shifted point is stored and sent. σ is the new
  setting **«Standort verschieben»** under «Teilen» › «Weitere Angaben»: 25, 50 (default), 100 or
  200 m, with one line «Mehr verbirgt deine genaue Adresse besser, macht die Karte aber ungenauer;
  gilt ab der nächsten Messung.» The offset is fixed per place — HMAC-SHA256 of the true hectare and
  σ under a 32-byte secret made on the phone (`dsp/geo/LocationNoise.kt`, `upload/NoiseSecret.kt`,
  stored encrypted like the token, never sent) — so averaging many measurements does not remove it;
  «Neu starten» and «Vom Server löschen» make a new secret. Room v6 → v7 adds `point_e`, `point_n`,
  `sigma_m` to `measurements`; rows of 0.6.0/0.6.1 keep their cell but are no longer sent. The
  «Teilen» explanation says how far the point is moved, that it is moved the same way at the same
  place, that the true place is usually (86 of 100) within 2σ and that a lone contributor's square
  lies near them. «Laut! Melden» is unchanged (exact place).
- **Upload contract (server schema v4):** every minute and event record and the site carry
  `"point": {"e", "n", "sigma_m"}` instead of `"cell"`; the server derives the display cell (the
  hectare containing the point), stores the point, never publishes it, and still accepts `cell`
  from older apps. Needs the server with schema v4 (an older server refuses `point`).
- **Editions renamed «Offline-Version» and «Online-Version»** (were «Weniger Lärm» and «Weniger
  Lärm · Karte») in Einstellungen › «Über die App» (name, summary, how to switch), on the website
  (download buttons, «Welche Version?», checksum table, update check, privacy page, map page) and
  in the docs. The launcher label of both stays «Weniger Lärm»; app id, flavours, file names
  (`lessnoise.apk`, `lessnoise-karte.apk`) and the `&e=offline|karte` of the update check are
  unchanged.
- **The Online-Version is the default download** and is listed first everywhere: the filled button
  on the home page and in the Anleitung, the one-line choice («Die Online-Version misst, teilt auf
  Wunsch mit der Lärmkarte und meldet unterwegs. Wer noch mehr Privatsphäre möchte, nimmt die
  Offline-Version: ohne Internet und ohne Standort, dafür ohne Karte und Meldungen.»), the checksum
  table, the privacy page and the docs. The Offline-Version is the alternative for even more privacy,
  with fewer features. update.html offers the Online-Version first to a visitor without a version;
  an installed app still gets its own edition first (an app without `&e=` counts as offline, as
  before). In the app, «Über die App» of the Online-Version now reads «Noch mehr Privatsphäre: die
  Offline-Version ohne Internet von wenigerlaerm.ch installieren. …».
- **Privacy texts:** a sent report keeps «Art, Zeit und den ungefähren Ort» (was «Hektare»);
  datenschutz.html, PRIVACY.md, README.md, DESIGN.md §2 rule 2 describe the location noise and what
  it does not hide.
- **Tests:** migration 6 → 7 on SQLite, the shifted point per record and unshared rows (also
  cell-only rows) in the upload, the location noise (reference values computed in Python,
  determinism per place, σ, sample spread, edge bytes, server box), the «Teilen» texts, the edition
  names; server: v3 → v4 migration, `point` validation, site with a point.

## 0.6.1 — 2026-10-13 (versionCode 14)

**Weniger Text, weniger Optionen.** Goal: nobody who opens the app for the first time should be put
off by explanations and settings they do not need. The texts follow the reviewed proposal (app_texts.json, written for 0.5.1 and
applied to 0.6.0 by meaning), about half the words of 0.6.0; nothing about what is measured, stored
or sent changes.

- **Tabs:** Messen · Messungen · Einstellungen, plus **Teilen** in «Weniger Lärm · Karte» (was a card
  and a sub-screen in Einstellungen). «Kalibrieren» moved to **Einstellungen › Erweitert**, together
  with «Alte Messungen anpassen» (was under «Daten») and the two remaining detector settings.
  «Daten» became the card **«Deine Messungen»** (counts, «Exportieren», «Alle löschen»).
- **Einstellungen:** «Lange messen» (button «Akku-Optimierung ausschalten»), «Deine Messungen»,
  «Über die App» (version, edition, how to switch, «Update prüfen»), «Datenschutz», «Erweitert ›».
- **Removed options, now fixed at their former defaults** (values stored by 0.6.0 are ignored):
  «Lokaler Hintergrund: Fenster» (30 s); wind «Anteil 20–200 Hz mindestens» (93 %) and «Flattern
  mindestens» (4.5 dB); «Wind-Ereignisse in der Grafik zeigen» (now never drawn; never counted, as
  before); «Klassifikation (YAMNet)» (always on); classifier «Intervall» (1 s); «Pegelanpassung für
  Klassifikator» (on); «CPU während der Messung wach halten (Wake-Lock)» (on); «Messung beenden,
  wenn die App geschlossen wird» (off: keeps running); calibration comparison window 30 | 60 s (30 s).
  The detector and classifier behave exactly as 0.6.0 with default settings.
- **Hidden under Erweitert:** «Lärmspitze ab … über Grundrauschen» (6.5 dB) and «Lärmspitzen erst
  ab» (30 dB(A), follows calibrations). Under «Teilen» › «Weitere Angaben»: «Nur über WLAN» (on),
  «Noch zu senden»; under «Wo steht das Telefon?» › «Weitere Angaben»: Stockwerk, Notiz.
- **Messen:** before the start one placement hint and one microphone line («Die App braucht das
  Mikrofon. Es wird nie Audio gespeichert oder gesendet.»), «Starten»; while measuring «Jetzt»,
  «Grundrauschen», «Klingt nach», «Stoppen», «Läuft seit … Der Bildschirm darf aus sein.», «Letzte
  Lärmspitzen» (time, peak, source). Gone: the «kalibriert/unkalibriert» chip, the two other
  background values, the AudioSet labels and percentages, the «Letzte volle Minute» card, the
  measuring-chain line, the one-time notice of the 0.4 threshold migration.
- **Messungen:** legend «Mittel pro Minute», «Schwankung», «andere Spitzen»; tiles «Grundrauschen»,
  «Lärmspitzen … pro Stunde», «Lauteste»; one summary line «Mittel … · n Lärmspitzen, davon k … ·
  p % gemessen» (no dynamics, no gap list); two-line tooltips (the Labor build keeps the detector
  details); no «unkalibriert»/«nachträglich kalibriert» badges; «Tagesverlauf» offered only for
  measurements of 24 h or more. «Unklassifiziert» → «Sonstiges».
- **Plain words** everywhere: Mittel, Grundrauschen, Lärmspitzen, 100-m-Quadrat, Korrektur (for the
  calibration offset); «du»; Swiss spelling. Notification «Letzte Minute: … dB(A)». Error messages
  say what to do. «Mehr dazu» links open wenigerlaerm.ch/anleitung.html#messen, #kalibrieren,
  #teilen, #daten and /datenschutz.html in the browser (no request by the app itself).
- **Teilen:** «Auf die Lärmkarte», one sentence why, the explanation in short lists (what is sent,
  location, never sent, where — now including that a 100 m square where nobody else measures shows
  your values, server/DESIGN.md §2 rule 2 —, deleting), «Weiter», afterwards behind «Was gesendet
  wird ▸». Switch «Auf die Karte senden». «Vom Server löschen»; «Neu starten» (was «Neue Kennung»)
  only when the server no longer knows the phone. One message for every retried upload failure.
  The first «Laut! Melden» consent also says that the point appears on the map and that reporting
  often from the same spot makes it visible (DESIGN.md §2 rule 10).
- **Tests:** removed settings fixed (and not read from storage), plain-word check on the UI string
  literals, «Mehr dazu» link targets, the new texts. JVM screenshots for the website from synthetic
  data: `docs/img/app-messung.png`, `app-messung-dark.png` (chart) and `app-messen.png` (Messen while
  measuring; the app module now applies the Paparazzi plugin for this one test).

## 0.6.0 — 2026-10-13 (versionCode 13; published on wenigerlaerm.ch)

The app is now called **«Weniger Lärm»**, nothing about nights is special any more, and in the map
edition every shared measurement has its own place. There are no installs of 0.6.0 yet, so the
breaking changes below need no transition.

- **New name and app ids.** Launcher label «Weniger Lärm» (French «Moins de bruit», Italian «Meno
  rumore», English «Less Noise»); Labor builds «Weniger Lärm Labor» (also localized, so that a
  French, Italian or English phone does not show the public name). Editions shown to users:
  «Weniger Lärm» (flavour `offline`) and «Weniger Lärm · Karte» (`public`); the internal flavour
  names are unchanged. App ids `ch.lessnoise.app` (offline, public) and `ch.lessnoise.labor`
  (labor, laborKarte), Kotlin packages and Gradle namespaces `ch.lessnoise.*` (moved with `git mv`).
  Because the app id changed, 0.6.0 installs **next to** a 0.5.x «Stadtlärm», not over it, and
  starts with empty storage. Same release key. CSV exports are named `wenigerlaerm-…`, the
  calibration export `wenigerlaerm-kalibrierungen.json`.
- **Server** `https://api.lessnoise.ch` (`BuildConfig.API_BASE`, formerly `STADTLAERM_API`; the
  test override `-Pstadtlaerm.api=…` is unchanged). «Nach Update suchen» opens
  `https://wenigerlaerm.ch/update.html`. User-Agent `LessNoise`.
- **Measurements instead of nights.** The unit is a measurement: one run from «Start» to «Stopp», at
  any time of day, possibly over several days. New Room table `measurements` (database v5 → v6):
  «Start» stores a row (and, if shared, its 100 m cell), «Stopp» its end; minutes and events belong
  to the measurement that started last before them. The migration keeps every minute and event and
  derives measurements for older data from the stored minutes (a gap of more than 10 minutes
  starts a new one). A measurement the system killed before «Stopp» ends with its last minute.
- **«Messungen» tab** (was «Nächte»): the list of measurements, newest first, with start – end,
  Dauer, Mittel, Grundrauschen and Lärmspitzen pro Stunde. Tapping one shows its chart: **Messung**
  (its own time span; hourly values above 36 h) or **Tagesverlauf** (level by hour of day over all
  days of the measurement). ‹ › step through the measurements. No night shading, no Nacht/Tag/Woche.
  Summary tiles «Grundrauschen (L90)», «Lärmspitzen», «Lautestes».
- **Texts without nights:** «Nachtmessung & Akku» → «Messung & Akku»; the expert texts no longer
  speak of nights; Labor clip list «Diese Nacht» → «Letzte 24 h». Shorter texts in the sharing
  screen, the location messages and the edition descriptions.
- **Location per measurement** (Karte editions, with «Messwerte teilen» on): «Messung starten»
  takes one precise location fix (the 0.5.1 `LocationFetcher`/`LocationPolicy`) and stores the 100 m
  cell with the measurement; the coordinates are neither stored nor sent. If the fix fails, one
  dialog: «Nochmals», «Koordinaten einfügen», «Ohne Teilen starten». The fixed hectare of
  «Aufstellung» and the button «Standort bestimmen» are gone; placement, floor, street side and
  note stay as optional, remembered fields.
- **Upload contract:** every minute and event record carries `"cell"` of its measurement; records of
  a measurement without a cell are never sent. The site call sends the placement details with the
  cell of the newest shared measurement. Loud moments of a shared measurement thus appear on the
  map (one sentence in the sharing explanation says so). Wind events stay excluded.
- **«Laut! Melden»** (Karte editions, on «Messen» while no measurement runs): «Was war laut?» (Töff,
  Auto, Lastwagen, Poser, anderes) → one precise fix (≤ 50 m, else «Nochmals») → «Meldung: …,
  Uhrzeit, Standort ± n m – senden?». Sent to the new `POST /v1/devices/{id}/reports` (time of the
  tap to the second with offset, latitude/longitude with 5 decimals, accuracy, category; ≤ 50 per
  request) by the same WorkManager upload. The first report while sharing is off shows a short
  consent and switches sharing on only with «Ja, einschalten». The exact coordinates stay on the
  phone only until the server has the report; afterwards only category, time and cell are kept.
  Switching sharing off or «Daten auf dem Server löschen» also drops unsent reports. A server
  without the reports call (404) keeps them for later; other refusals drop the batch.
- **Unchanged:** the offline and Labor editions have no location, no reports and no internet
  (checked with `aapt2`); the detector, calibration, re-evaluation and CSV columns are unchanged.
- **Tests:** migration 5 → 6 on SQLite, measurement rows → list, measurement splitting, cell per
  record and unshared measurements in the upload, report body/queue/deletion of coordinates,
  category ids, the report accuracy rule, the Room v6 schema.

## 0.5.1 — 2026-10-12 (versionCode 12; published on wenigerlaerm.ch)

Also on the website with this release: the noise map covers all of Switzerland (zoom out to the
whole country; sharp tiles on phones).

The Karten-Version determines the hectare itself. Nobody pastes coordinates by hand, so the
placement step now has a button that asks the phone for its location once.

- **«Standort bestimmen»** in «Messwerte teilen → Aufstellung» (only where
  `BuildConfig.UPLOAD_AVAILABLE`: Karten-Version and the internal Labor-Version mit Karte). On tap the
  app asks for the location permission (Activity Result API, «Genau» or «Ungefähr» on Android 12+),
  then gets **one** fix from the platform `LocationManager` — no Google Play Services: GPS and
  network provider, whichever are on, at the same time; `getCurrentLocation` on Android 11+, a single
  `requestLocationUpdates` on Android 8–10; at most 30 s with «Abbrechen»; a last-known location
  only if younger than 2 min and accurate to ≤ 50 m. The fix becomes the 100 m hectare on the phone
  (`CellInput.fromLatLon` → `Lv95.cellId`, the map's formulas); the screen shows only «Hektare
  h26833_12479 · Genauigkeit ± 12 m», and «Aufstellung speichern» keeps the hectare. The
  coordinates are never stored (not in settings, Room or logs) and never sent.
- **Not set silently:** with only «Ungefähr» allowed, or a fix worse than ± 75 m, the hectare is not
  taken; the screen explains that a hectare needs «Genauer Standort» and offers the app settings,
  another try, or pasting coordinates. Location services off → a link to the system location
  settings.
- **Pasting coordinates** stays as the second way, collapsed under «Oder Koordinaten einfügen».
  Placement, floor, street side and note are unchanged.
- **Permissions:** `ACCESS_FINE_LOCATION` and `ACCESS_COARSE_LOCATION` in the Karten-Version
  (`src/public/AndroidManifest.xml`) and in `laborKarte`; the **Offline-Version and the Labor build
  have no location permission** (removed in their manifests). `ACCESS_BACKGROUND_LOCATION` is removed
  for every edition: location is read only on the button, while the screen is open. Location hardware
  is declared optional (`uses-feature … required="false"`).
- **Texts:** the explanation «Was nie gesendet wird» no longer says the app has no location
  permission; it says when location is read and that only the hectare is kept. About card and
  Einstellungen → App-Version: the Offline-Version is «ohne Internet- und ohne Standort-Berechtigung».
  PRIVACY.md, README.md, server/DESIGN.md §2.2, datenschutz.html and index.html («Welche Version?»)
  describe the location permission.
- **Code and tests:** new package `ch.stadtlaerm.app.location` (`LocationPolicy.kt`, pure rules;
  `LocationFetcher.kt`, the only file using `android.location`). `LocationPolicyTest` (lat/lon →
  hectare for Zürich points, accuracy/«Ungefähr»/last-known rules, texts) and
  `LocationIsolationTest` (manifests per flavour; no logging, storage or network in the location
  code; the upload never sees a location).
- Version 0.5.1 / code 12 for all editions (`0.5.1-labor`, `0.5.1-labor-karte`). Not on the website
  yet: `docs/download/` and `docs/update.html` still offer 0.5.0.

## 0.5.0 — 2026-10-11 (versionCode 11; published on stadtlaerm.ch)

0.5.0 = 0.4.0 (detector v2, below) + the opt-in «Messwerte teilen» for the shared noise map. The
sharing feature was developed on the `map-design` branch as an unpublished "0.4.0" (code 10); it
never shipped under that number. The server runs at api.stadtlaerm.ch; the website still offers
0.4.0.

### Two published editions

- **Offline-Version** (flavour `offline`, `stadtlaerm.apk`, the default download): the public app
  **without** `INTERNET` and `ACCESS_NETWORK_STATE` and without «Messwerte teilen»
  (`UPLOAD_AVAILABLE = false`). Data leaves the phone only through the existing exports. 0.4.0
  users get it as their normal update, with no new permission.
- **Karten-Version** (flavour `public`, `stadtlaerm-karte.apk`): the public app as described below,
  with the opt-in «Messwerte teilen».
- Both have app id `ch.stadtlaerm.app`, version 0.5.0 / code 11 and the same key: installing one over
  the other switches the edition and keeps the data. `BuildConfig.EDITION` (`offline` | `karte` |
  `labor`) is shown in Einstellungen → App-Version with a sentence on how to switch, and «Nach Update
  suchen» adds `&e=<edition>` so update.html offers the same edition. The Offline-Version, started
  after a Karten-Version with sharing on, switches sharing off and cancels the upload jobs.
- **Downloads via GitHub Releases:** both APKs are attached to the release `v0.5.0` under fixed
  names; the website links there (GitHub counts downloads per file, `scripts/download_stats.py`).
  `docs/download/stadtlaerm.apk` remains as a fallback for old links (Offline-Version).
- **Server:** `stats.json` next to the map files (`/v1/map/stats.json`, `python -m
  stadtlaerm_server stats`): project-wide counts only (registered/active/sharing devices,
  deletions, hectares, shared nights, app versions, registrations per week; server/DESIGN.md §6.4).
  Schema v2 adds a single deletion counter. `scripts/usage_report.py` combines both sources.
- **Server deletes silent devices after 60 days:** a device with no upload (no authenticated
  request) for 60 days is deleted completely, like «Löschen» (`INACTIVE_DELETE_DAYS`, daily job and
  `python -m stadtlaerm_server retention`; anonymous counter `devices_expired_total` in
  `stats.json`; server/DESIGN.md §2.9). This covers phones that switched to the Offline-Version,
  uninstalled the app or stopped sharing without deleting. In the app, a refused token (401) now
  explains the likely reason («… 60 Tage lang sind von diesem Telefon keine Messwerte angekommen …»)
  and still waits for «Neue Kennung» (no silent re-registration); the sharing explanation
  («Löschen») and the Karten-Version's switch hint mention the 60-day rule.

### Added by the merge

- **Wind events are not shared.** Events flagged as wind by detector v2 are filtered out on the
  phone before upload (`upload/Payloads.kt`), the same way they are left out of the app's own
  counts. The minute field `event_count` sent to the server is detector v2's count without wind
  (the minute's `wind_event_count` is not sent). The payload fields are unchanged
  (server/DESIGN.md §4.3/§4.4); none of the new detector features (local floor, excess, rise,
  decay, LF share, …) are sent. `threshold_db` in the event payload is now detector v2's excess
  over the local floor.
- **Labor about text:** the card «Über Stadtlärm & Datenschutz» no longer claims in the Labor
  edition that audio never leaves the working memory; it says that Labor can store clips and hour
  files on the phone when switched on and never sends anything (no internet permission). The
  public text is unchanged.

### Public app, Karten-Version (`ch.stadtlaerm.app`, version 0.5.0, code 11; not yet published)

In short: an opt-in «Messwerte teilen» sends the measured levels to the shared noise map — off by
default; the `INTERNET` permission is added for exactly this. The location is placed on the phone
to a 100 m × 100 m hectare square; «Meine Daten auf dem Server löschen» deletes everything sent;
«Neue Kennung» starts over with a new device id. Nothing in the measurement changed.

- **«Messwerte teilen» (opt-in upload for the shared noise map).** New screen
  Einstellungen → Messwerte teilen (server/DESIGN.md §2, §4, §9): an explanation of what is sent,
  what is never sent (audio, the precise location, name, the raw classifier labels), where it goes
  (api.stadtlaerm.ch, Switzerland) and how to delete; the placement step (coordinates in, hectare
  out, on the phone; placement, floor, street side, note); the switch (off by default), «Nur über
  WLAN» (default on), «Zuletzt gesendet», the minutes still to send, «Meine Daten auf dem Server
  löschen» and «Neue Kennung». Uploads run 5 min after every full hour and 15 s after a measurement
  stops (WorkManager), in batches of ≤ 1440 minutes / ≤ 2000 events, resume from the last
  acknowledged start, and never resend records the server refused.
- **Permissions:** the Karten-Version now has `INTERNET` and `ACCESS_NETWORK_STATE` (the
  Offline-Version has neither), used only by
  `upload/UploadClient.kt` for one host. `ACCESS_WIFI_STATE` and `RECEIVE_BOOT_COMPLETED` stay
  removed; no location permission. PRIVACY.md, the READMEs and the website say so.
- New dependency: `androidx.work:work-runtime-ktx` 2.9.1 (AndroidX, scheduling only). The network
  code uses the platform's `HttpURLConnection`; no third-party SDK.
- `dsp`: `geo/Lv95.kt`, WGS84 ↔ LV95 and hectare ids, the same formulas as the map's
  `docs/map/lv95.js`, tested against swisstopo's reference points.
- Nothing in the measurement path changed by the sharing feature. The database schema stays at v5
  (detector v2); the sharing feature only adds read-only queries.

### «Stadtlärm Labor» (`ch.stadtlaerm.labor`, `0.5.0-labor`, never published)

- No functional change: no network permission (its manifest removes `INTERNET` and
  `ACCESS_NETWORK_STATE`), «Messwerte teilen» is not offered.
- New internal flavour `laborKarte` («Labor-Version mit Karte», same app id, `0.5.0-labor-karte`, never published): the Labor build plus the opt-in «Messwerte teilen» (`INTERNET`, same upload code, audio never sent — `UploadSourceIsolationTest`); the plain Labor build installed over it switches sharing off and cancels the upload jobs.

## 0.4.0 — 2026-10-09 (detector v2; both flavours, versionCode 9; public app published on stadtlaerm.ch)

Why: in one recorded night (528 event clips, uncalibrated) the 5-minute L90 sat a median 3.1 dB
below the level right around an event, so «background + 10 dB» fired on nothing in particular and a
third of the events barely exceeded the local hum; 14 % of the events were wind on the microphone;
and the constant highway hum was mixed into the event figures.

### Event detection

- **Local floor:** L90 of LAF over the trailing 30 s (setting «Lokaler Hintergrund: Fenster»,
  10–60 s), updated every 125 ms, frozen while an event runs. Events start at **local floor +
  excess** (setting «Ereignis-Schwelle über lokalem Hintergrund», default 6.5 dB, 3–20 dB in 0.5 dB
  steps), end 3 dB below, need ≥ 0.5 s and the event floor (unchanged, 30 dB(A)), at most 300 s.
  Detection starts after 5 s of history (was 30 s). The 5-min background is still computed, stored
  with every event and shown live. The old setting is migrated once: excess = max(3, threshold −
  3.5) (10 → 6.5, 7 → 3.5); a changed threshold is announced once (Messen and Einstellungen).
- **Features per event:** excess over the floor, rise and decay time (10↔90 %), jaggedness,
  A-weighted 250 Hz–4.5 kHz rise against the 5 s before, unweighted 20–200 Hz energy share and
  low-frequency flutter (definitions in README → Event detection). Computed with five 2nd-order
  Butterworth sections per sample and per-tick sums (no FFT, no allocation per sample). For the
  decay the level is followed up to 5 s past the end; events are reported after that.
- **Wind flag:** `lf_share ≥ 0.93` or `lf_flutter_db ≥ 4.5` (both under «Experten»; fitted on the
  recorded night, see Validation). Wind events are
  stored with category «Wind» (`wind`) and exported, but excluded from events/h, `event_count`, the
  category counts and highlights, the loudest event and the night's events. The chart draws them as
  small hollow grey dots («Wind (ausgeschlossen)», can be hidden).
- **Shape** (informational): hump, jagged, impulse or long.

### Data

- Database v5 (migration from v4, tested against SQLite): events gain `local_floor_db`, `excess_db`,
  `rise_s`, `decay_s`, `jaggedness`, `mid_band_rise_db`, `lf_share`, `lf_flutter_db`, `wind`, `shape`;
  minutes gain `local_floor_db` (median over the minute) and `wind_event_count`. Existing data keeps
  its values (no features, not wind).
- CSV exports: the same columns appended at the end. `threshold_db` is now the excess over the local
  floor (before: the threshold over the 5-min background). `event_count` counts bursts without wind.
- Re-evaluation with a later calibration also moves the local floor (no new `orig_` columns needed).

### Display

- Chart summary tiles: «Hintergrund (L90)» (the hum: median of the minutes' L90), «Ereignisse»
  (bursts per hour without wind, «n Wind ausgeschlossen» below), «Lautestes» (loudest burst). The
  line below has the LAeq, the number of events (and of the highlighted category), coverage,
  dynamics and gaps. The night list shows the hum and events/h for every night and the number of
  excluded wind events. The tooltip of an event shows the local floor and excess, and the shape
  (wind: LF share and flutter). Messen shows the live local floor.

### «Stadtlärm Labor» (`0.4.0-labor`)

- `AudioTap.onEventEnded` carries the features; the manifest gets an `event` line for every event
  (also without a clip), a `features` object in `clip` lines, and the detector settings in
  `session_start` (`eventExcessDb`, `localFloorWindowS`, wind thresholds instead of
  `eventThresholdDb`). Clips are cut back to end + 5 s when the end is reported late.

### Validation (one recorded night, Labor build)

- The night's continuous recording (8.7 h) replayed through the engine and matched to the 528
  weakly labelled clips: at excess 6.5 dB / floor 30 dB(A) 64 % of the vehicle passes are kept and
  84 % of the "background" events dropped (floor 20: 80 % / 72 %); 5 dB keeps the background too,
  8 dB loses half of the passes. Wind thresholds fitted to the labels: 0.93 / 4.5 dB (97.4 %
  agreement on 340 events; first guess 0.95 / 3.8 dB: 96.2 %). Details: README → Event detection.

### Development

- Offline replay harness (`dsp/src/test/.../Replay.kt`): WAV (16 kHz upsampled ×3 polyphase, or
  48 kHz) → engine → events/minutes CSV; three synthetic fixtures (smooth hump, LF wind thump,
  steady tone) with expected outputs; `./gradlew :dsp:replay --args="clip.wav"` for real clips,
  `:dsp:replayBatch` for all clips of a Labor manifest and `:dsp:replayNight` for a whole night's
  continuous recording (one CSV each; the audio stays outside the repository).

## 0.3.4 — 2026-10-08

### Public app (`ch.stadtlaerm.app`, not published)

- **No functional change.** Version 0.3.4 (code 8) only so that both flavours share one version.
  The chart gained optional, flavour-agnostic hooks for event clips (a clip reference per event,
  an `onPlayClip` callback); the public app passes none, so it draws and behaves exactly as in
  0.3.3. This build is not published; stadtlaerm.ch stays on 0.3.3.

### «Stadtlärm Labor» (`ch.stadtlaerm.labor`, `0.3.4-labor`, never published): listen to clips

- **Chart:** events that have a clip get a thin ring around their dot (the dot colour keeps its
  meaning); their tooltip has an «Abspielen» button. The clip of an event is found through the
  manifest (event id, checked against the event's start time), indexed once and cached until the
  manifest or the clips folder changes.
- **Player («Clip» sheet):** event time, category, LAFmax, event duration, clip length; a bar of
  the clip with the pre-roll and post-roll shaded and the event span highlighted (ticks where
  classifier windows end), tap/drag or the scrub bar to seek; play/pause; «‹ Vorheriger» /
  «Nächster ›» step to the neighbouring event with a clip in the chart window (by time); «Teilen»
  shares the WAV (FileProvider). Below the bar: the classifier result of the second being heard,
  read from the clip's `classifierTrace` (top-3 labels with scores, the category decision, the
  input gain, LAF). Played on the media stream (`USAGE_MEDIA`, MediaPlayer, transient audio
  focus); a hint appears if the media volume is 0 or the phone is on silent, and while a
  measurement runs (the speaker is measured too). Playback only, the microphone is not touched.
- **Einstellungen → Labor → «Clips anhören»:** all clips newest first or only the current night's
  (time, category, LAFmax, duration, whether a trace exists), category filter chips, count and
  total size; tapping plays it, «‹»/«›» step through the filtered list.
- **Messen → «Letzte Ereignisse»:** the red record dot is now a button that plays the clip.
- Robustness: a missing or corrupt WAV shows «Clip nicht gefunden»; the player is released when
  the sheet closes, when a measurement starts or stops, and pauses when the app goes to the
  background. Clips without a manifest line (only the file) still play, with the time from the
  file name.
- New JVM tests (`app/src/testLabor/…/ClipPlaybackLogicTest.kt`): manifest clip index (event id →
  file, trace), neighbour order, trace lookup for a playback position, progress-bar geometry
  (also truncated clips), WAV check; chart: dot accent and «Abspielen» only with a clip reference.

## 0.3.3 — 2026-10-07

### Public app (`ch.stadtlaerm.app`, published)

- **Notification while measuring:** title «Mikrofon aktiv – Messung läuft», text with the current
  LAeq as before, «Stopp» button. The notification channel now has importance *default*, so the
  icon stays visible in the status bar (no sound, no vibration). Because Android cannot raise the
  importance of an existing channel, the channel has a new id (`measurement_active`); the old one
  is removed.
- **New setting «Messung beenden, wenn die App geschlossen wird»** (Einstellungen → Messung, off
  by default). Off: as before, the measurement keeps running as a background service when the app
  is swiped away. On: swiping the app away stops the measurement exactly like «Stopp»
  (`Service.onTaskRemoved`), including the current partial minute. A stop now stays in the
  foreground until the last minute and event are in the database (at most 3 s).
- **Two product flavours** (Gradle dimension `edition`): `public` (this app) and `labor` (below).
  The measurement service hands the audio blocks, the event lifecycle and the classifier results
  to an `AudioTap`, which in the public app is always the no-op `NoAudioTap`; the engine reports
  sample indices and classifier results through new no-op listener hooks. **No change to the
  measurement**, same manifest and permissions (no `INTERNET`). The public APK contains no
  recording code; how to check is described in PRIVACY.md → «Labor-Build». APK paths now include
  the flavour (`app/build/outputs/apk/public/release/app-public-release.apk`).

### New: «Stadtlärm Labor» (`ch.stadtlaerm.labor`, `0.3.3-labor`, never published)

A diagnostics build that can record audio to debug the event detector and the sound-source
classifier, only after «Einstellungen → Labor → Audio während der Messung aufzeichnen» is
switched on and confirmed. Red icon and a red banner on every screen («LABOR-VERSION – kann Audio
aufzeichnen», «Aufnahme läuft»).

- Event clips: WAV 16 kHz, 16-bit, 5 s before to 5 s after the event, at most 60 s (flagged
  `truncated`), from a 6 s ring buffer of the 48 kHz input; clip rate every / every 2nd / every
  5th event.
- Continuous recording: AAC-LC 16 kHz 64 kbit/s, one M4A per wall-clock hour.
- Classifier trace: every clip's manifest line carries the classifier's per-second results inside
  the clip (top-5 labels and scores, category decision and score, input gain, LAF); every
  classifier run of the measurement is also logged to `classifier/<yyyyMMdd_HH>.jsonl`.
- `manifest.jsonl`: session start/stop (device, versions, audio chain, calibration, event
  threshold and floor, settings, drop counts), sample clock and clock corrections, per-clip event
  data, per-file sample mapping.
- Storage cap (default 2 GB; recording stops, measuring continues), used space and file counts,
  «Ordner anzeigen», «Als ZIP teilen» (clips, manifest, classifier logs; hour files only under
  500 MB) and «Alle Aufnahmen löschen». Events with a clip get a red dot in «Letzte Ereignisse».
- All of it lives in `app/src/labor/`; JVM tests in `app/src/testLabor/`.

## 0.3.2 — 2026-10-07

The event floor follows the calibration, and old measurements can be re-evaluated with a new
calibration.

- **Event floor follows the calibration.** When a calibration is saved and becomes active for this
  device and audio source (also «Auf Standard»), the floor «Mindestpegel für Ereignisse» moves by
  the change of the offset (new − previous, the default if none was active), rounded to 0.5 dB and
  limited to 20–70 dB(A). The calibration screen confirms it: «Kalibrierung gespeichert. Offset
  +9.6 dB gegenüber vorher; der Mindestpegel für Ereignisse wurde von 30.0 auf 39.5 dB(A)
  angepasst.» The setting now moves in 0.5 dB steps and is shown with one decimal.
- **Re-evaluate old measurements («nachträglich kalibriert»).** After saving a calibration the app
  offers to re-evaluate earlier minutes and events with the same audio source that were measured
  without or with another calibration (dialog «Neu bewerten» / «Nicht jetzt»; also «Daten → Alte
  Messungen neu bewerten»). All level columns move by (new − original offset); the original values
  are kept (`orig_*`, `recalibrated_from_id`, `recalibration_offset_db`) and later re-evaluations
  always start from them, so corrections never compound. Runs in the background with a progress
  bar, in one transaction. `event_count` stays as recorded (the night list and the chart apply the
  current floor at read time).
- Re-evaluated data counts as calibrated; the night list and the chart summary note «nachträglich
  kalibriert», and so does the chart tooltip.
- CSV export: the new columns are appended to both files (earlier columns unchanged).
- Database v4 (migration adds the nullable re-evaluation columns to `minutes` and `events`).

## 0.3.1 — 2026-10-07

Update check that keeps the app without any internet permission.

- **Design.** The app still has no internet permission: «Nach Update suchen» only opens
  `stadtlaerm.ch/update.html#v=…&c=…` in the browser. The page compares the installed version
  from the URL fragment, which is never sent to the server, with the published one in the
  browser.
- **Einstellungen → App-Version:** «Stadtlärm 0.3.1 (Build vom 7.10.2026)» and the button «Nach
  Update suchen» («Kein Browser gefunden» if no browser is installed).
- **Offline age reminder:** from 30 days after the build date, Einstellungen and the Messen
  screen show «Diese App-Version ist n Tage alt. Nach Update suchen?»; «Später» hides it for 14
  days. New `BuildConfig.BUILD_DATE` (date of the Gradle build, `SOURCE_DATE_EPOCH` overrides).
- Website: new page `update.html` (one inline script, allowed by its SHA-256 in the
  Content-Security-Policy), linked in the footer; the privacy page explains the comparison.
  Release checklist in the README.

## 0.3.0 — 2026-10-07

Test version: a chart of the measured noise, an absolute floor for events, and a classification
rule that no longer drops quiet traffic into «unklassifiziert».

- **Chart («Nächte»).** A chart of the measured levels over time, drawn by the app itself (no
  chart library): the background band L90–L10, the LAeq line and every event as a dot at its
  LAFmax. Range buttons **Nacht · Tag · Woche** and ‹ › navigation (or swipe sideways on the
  chart); the screen opens on the running night, else on the most recent night with data. The
  line and band break wherever a minute is missing or has < 50 % valid audio; gaps of 10 min
  or more are marked «keine Messung». The week view shows hourly values (energy mean; an hour
  needs 30 valid minutes) and only events of the highlighted category ≥ 60 dB(A), at most the
  loudest 300. One category is highlighted in orange (default: Töff & Poser; a scrolling row of
  chips below the chart, remembered); the others are grey. Tap for a tooltip with the minute's
  levels or the event's details (with weekday and date in the day and week views); a summary row
  shows LAeq, event count and the loudest event of the window, plus the measured share of the
  time, **events per hour** of valid measurement, the **dynamics** (median over the valid minutes
  of L10 − L90) and any interruptions. The night list shows events/h and dynamics too. Events of
  other categories are drawn as smaller grey dots (6 dp) so a night with hundreds of quiet
  pass-bys does not bury the LAeq line. Tapping a night in the list opens it in the chart.
- **Event floor.** New setting «Mindestpegel für Ereignisse (LAFmax)», 20–70 dB(A), default 30.
  An event is only kept if its LAFmax reaches the floor: this filters noise at the phone itself
  (typing, breathing ≈ 20–30 dB) while quiet pass-bys stay (on a real windowsill night, highway
  passes peaked at a median of 37 dB(A), 13 dB above a 20 dB background). The floor in force is stored with each event (`min_level_db`,
  also in the events CSV). Events recorded before 0.3.0 are filtered with the current floor when
  the night list and the chart are shown, so both agree; their CSV export is unchanged (empty
  `min_level_db`).
- **Category rule.** If the top-1 AudioSet label belongs to a category and scores ≥ 0.10
  (`top1_threshold`), that category wins; otherwise the best category if its score ≥ 0.20
  (`category_threshold`, formerly `threshold`, now inclusive); otherwise «unklassifiziert». Before,
  only the 0.2 rule applied, and on a real night 328 of 440 events whose best label was «Vehicle»
  (scores 0.1–0.3) ended unclassified. Tie-break unchanged (Töff & Poser first). Applies to new
  measurements; stored events keep their category.
- Database v3 (migration adds `events.min_level_db`).
- New Gradle module `chart/` (drawing model, Compose chart, app theme) with JVM tests and
  Paparazzi renders.


## 0.2.0 — 2026-10-06

First signed public release. No functional changes to measurement, classification or storage.

- **Signed with the project's release key** (`CN=Christian Frank, O=Stadtlaerm, L=Zuerich, C=CH`,
  certificate SHA-256 `a3d5d10e…3142be`). Earlier builds were debug builds signed with a
  development key; Android cannot update across signing keys, so **uninstall any earlier
  Stadtlärm build first** (export your data before, uninstalling deletes it).
- Release APK contains only the phone ABIs (`arm64-v8a`, `armeabi-v7a`); the `x86_64` emulator
  ABI is now in debug builds only. Code shrinking stays off.
- Repository restructured as a monorepo (`android/`, `firmware/`, `docs/` for the website
  [stadtlaerm.ch](https://stadtlaerm.ch)); the APK is published at
  `https://stadtlaerm.ch/download/stadtlaerm.apk`.
- Optional release signing in Gradle (see README, "Signed release build"); without a key the
  release build is unsigned, so the public repository builds for everyone.

## 0.1.1 — 2026-10-06

Review fixes; not yet tested on a device.

- **Silenced microphone.** On Android 10+ a call or voice assistant makes the app receive zeros.
  This is now detected (`AudioRecordingConfiguration.isClientSilenced` on API 29+, plus a
  digital-silence check on all versions: ≥ 10 ms of exact zeros or a level below −130 dBFS per
  125 ms block). That time, plus 0.5 s of filter recovery, is excluded from LAeq, LAF statistics,
  percentiles, event detection and the background L90; classifier windows touching it are
  ignored. Minutes store `validSeconds`/`coverage`; nights leave out minutes with < 50 %
  coverage and show coverage; CSV has `valid_s`, `coverage` and `clock_corrections` columns.
- **Timestamps.** Sample time is anchored to the wall clock at the first delivered audio block
  (not when the engine is created) and re-anchored once per minute if drift exceeds 500 ms
  (latency-robust estimate: minimum over the minute). Corrections are counted per minute.
  Minute boundaries stay aligned to wall-clock minutes.
- **Calibration integrity.** A calibration measurement is aborted, with a message, and cannot be
  saved if the app goes to the background (ON_STOP) or the microphone is silenced. Starting a
  new run discards the previous unsaved result of that method.
- **Event classification.** The default classifier interval is (and stays) 1 s. When an event
  ends, the classifier results overlapping it are used immediately; if none overlapped, one
  extra classification is run right at event end (previously the app waited up to 3 s, or for the
  next periodic window).
- **Foreground service.** If the microphone permission is missing when the service starts, it is
  promoted as a `shortService` (API 34+) and stopped cleanly instead of crashing.
- **Smaller fixes.** Night length is computed from actual local 22:00–06:00 (7 h / 9 h on DST
  nights). Events are counted in the minute in which they start (a minute is held until a pending
  candidate is confirmed or discarded). Database insert failures are caught and logged by
  exception type only. The "Pegelanpassung" settings text now describes exactly what it does,
  and the setting is read at measurement start like all others.
- **Database v2** with a migration from v1 (level columns nullable for minutes without valid
  audio; new columns `validSeconds`, `coverage`, `clockCorrections`).
- Also included from the independent review (commit 7ffa218): LiteRT interpreter closed on its
  own thread, stop-during-start race, capture cleanup on failed start, calibration clipping check,
  battery settings fallback.

## 0.1.0 — 2026-10-06

First version: A-weighted LAF/LAeq/Ln measurement, event detection, YAMNet source categories,
calibration (reference meter, acoustic calibrator, manual), night summaries, CSV export.
