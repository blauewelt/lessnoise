# Changelog

## Unreleased — event head (branch event-head; not in any APK yet)

- **Event head, Labor editions only, logging only:** the dsp module ports the event features of the
  Labor multi-label head (`head_F1_MLP_nosit`: envelope periodicity, 1/3-octave rises, spectral
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
