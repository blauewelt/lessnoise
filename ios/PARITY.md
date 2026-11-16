# iOS vs Android: what the iPhone app lacks (as of 2026-11-16)

The iOS app in `ios/` is a port of the Android **Online-Version 0.6.3** (versionCode 16, 2026-10-15).
`main` is at Android **0.9.6** (versionCode 54, 2026-11-01). This file lists, from
`android/CHANGELOG.md`, `android/README.md` and `git log 4447bdd..origin/main` (82 commits), what the
Android app gained since then that the iPhone app does not have. It lists; nothing is ported here.
The version in brackets is the Android version that brought it (Labor·Karte versions reached the
Online-Version with the next published one: 0.8.x → 0.9.0, 0.9.1–0.9.4.6 → 0.9.5, 0.9.5.1 → 0.9.6).

## Does today's server accept what the iPhone app sends?

Checked against `server/stadtlaerm_server/schemas.py` on main (schema v9): the iOS request bodies
are the Android 0.6.3 ones byte for byte (golden fixture `LessNoiseCore/Tests/Fixtures/payloads.json`),
and every field in them is still accepted. Validating the fixture bodies with the server's own
Pydantic models (`SiteIn`, `MinuteIn`, `EventIn`, `ReportIn`):

- **Site and minutes: refused.** The iPhone sends `audio_source: "IOS"`
  (`AudioSource.IOS_MEASUREMENT`); the server accepts only `UNPROCESSED`, `VOICE_RECOGNITION` and
  their `+usb|+bluetooth|+wired|+other` forms (`AUDIO_SOURCES`). With `IOS` substituted, 6 of 6
  site bodies and 14 of 14 minute bodies fail on `audio_source` (one minute body also fails as
  Android sends it: an intentional `valid_s > duration_s` edge case of the fixture). So no iPhone
  measurement reaches the map until the server accepts `IOS` (DESIGN.md §15, still open).
- **Events: accepted** (12 of 12; they carry no `audio_source`). Since the map server of
  2026-10-16, events whose category is not machine or motor noise (voices, music, `unclassified`)
  are not stored and come back as `dropped_by_category` (6 of the 12 fixture events); the iOS
  parser ignores that key, so they count as sent. With the classifier off, every iPhone event is
  `unclassified` and would be dropped.
- **Reports: accepted** (3 of 3). The 0.6.3 categories (`motorrad`, `auto`, `lastwagen`, `poser`,
  `andere`) are still in `REPORT_CATEGORIES`.
- No app version gate: the server has no minimum app build, and the endpoints and response shape
  (`accepted`, `duplicates`, `rejected`) are unchanged.

## Affects what is sent to the server

- Only events of machine and motor noise leave the phone (`Payloads.isMapEvent`: map category, not wind, excess ≥ 6.5 dB, LAFmax ≥ max(floor, 30 dB(A)), ≥ 0.5 s); the iPhone still sends every non-wind event and relies on the server to drop the rest (0.6.4).
- «Unterwegs» as a start mode whose measurement shares nothing (no location, minutes or events), database column `measurements.unterwegs`; on the iPhone «Unterwegs» is still the 0.6.3 switch and does not stop sharing (0.6.4).
- «Unterwegs erkannt»: the motion sensor turns a carried window measurement into «unterwegs», nothing of that span is sent (table `moving_spans`), newest records wait 35 s (0.6.7).
- Reports at any location accuracy (server bound 10 000 m; iPhone still requires ± 50 m) (0.6.7).
- Direction of travel with a report (`direction`: bearing, true/magnetic north, compass accuracy) (0.7.0).
- Microphone choice and calibration per microphone; non-built-in microphones send `audio_source` `…+usb|+bluetooth|+wired` (0.7.2).
- Category «Flugzeug» (`flugzeug`) in «Laut! Melden» (0.7.7 → 0.8.0).
- Report level: peak and LAeq of the seconds the report dialog was open, their number and whether calibrated (`level`, schema v8) (0.8.5 → 0.9.0).
- Moving the map under the dot to correct the reported place (sent with a fixed 15 m accuracy, ≤ 2 km, Switzerland only) (0.8.1 → 0.9.0).
- Location fix taken when the report dialog opens, sent only with «Senden» (0.8.1 → 0.9.0).
- Category optional, direction optional, «Anzahl» 1–9 (`count`), new kinds `bahn`, `baustelle`, `garten`, `hupen`; fallback for servers before schema v9 (0.9.4.5 → 0.9.5).
- Report categories offered: Töff · Auto · Andere (Lastwagen, Flugzeug, Bahn, Baustelle, Gartengeräte, Hupen); «Poser» and «anderes Fahrzeug» no longer offered (still accepted) (0.9.1, 0.9.4.2 → 0.9.5).
- swisstopo map tiles around the true position (and the dragged place) loaded while the report dialog is open — a request to `wmts.geo.admin.ch` outside the «Karte» tab (0.7.8 → 0.8.0, 0.8.1 → 0.9.0).

## Visible in the app

- «Anderes Fahrzeug» instead of «anderes» as the report label (0.6.4).
- Per-phone measurement limit («Messgrenze») estimated from stored minutes, «≤» values, chart band, CSV column `at_floor` (0.6.5).
- «Antippen zum Melden» / notification tap opens «Laut! Melden» while unterwegs (0.6.8).
- Built-in microphone pinned; headset microphones refused, later selectable under «Erweitert › Mikrofon» (0.6.6, 0.7.1, 0.7.2).
- One notification for «Sehr laut» (the measurement notification turns into the alert for 30 s) (0.7.6 → 0.8.0).
- Compass rose with swisstopo streets under the arrow in «Wohin fuhr es?» (0.7.6, 0.7.8 → 0.8.0).
- «erkannt» marker instead of preselected buttons for the suggested category (0.7.7 → 0.8.0).
- «Laut! Melden» as one dialog with «Senden» as the main button; fits one screen (0.8.1, 0.9.1, 0.9.4.2 → 0.9.0/0.9.5).
- Optional photo of the vehicle, kept on the phone only, never sent (replaced the 0.7.6 typed plate) (0.8.5 → 0.9.0).
- App shortcut and home-screen widget «Laut! Melden» (0.8.4 → 0.9.0).
- Dark «Karte» tab following the system theme (0.8.2 → 0.9.0).
- «Teilen» in sections «Messungen am Fenster» / «Meldungen unterwegs» (0.8.8 → 0.9.0).
- New look of «Messen» and the other screens (status chip, Material level card, logo, actions at the bottom, «Mehr dazu» sheet) and «Langzeitmessung starten» (0.8.3–0.8.8 → 0.9.0).
- Clarity pass: headings and helper lines on every element («Grundrauschen (ohne Spitzen)», «leise bis laut», «Hervorheben:», …) (0.9.2 → 0.9.5).
- Detector class `loud_vehicle` shown as «Lautes Fahrzeug» instead of «Töff & Poser» (0.9.3 → 0.9.5).
- The big «Laut! Melden» on «Messen», also while a measurement runs; «Melden» in the measurement notification (0.9.4.4 → 0.9.5).
- Three ways on «Messen»: «Laut! Melden», «Langzeitmessung starten», «Unterwegs messen» with the «Sehr laut» notification (0.9.4.6 → 0.9.5).
- «Server zu alt – wird später gesendet» for reports refused by an old server (0.9.4.5 → 0.9.5).
- Calibration shown as deviation from the standard («Abweichung vom Standard −2,3 dB»), «Korrektur von Hand» in that unit, «Wirklich speichern?» above 20 dB (0.9.5.1 → 0.9.6).
- Green/red calibration banner on «Messen» and atop «Kalibrieren» (0.9.6).

## Labor only (not needed on iPhone)

- Labor manifest `inputDevice`, `motion`/`motion_minute`, `report` lines (0.6.6, 0.6.7, 0.6.9, 0.7.6, 0.8.5, 0.9.4.3).
- Position as a second motion signal and `FOREGROUND_SERVICE_LOCATION` (0.6.9; removed again in 0.9.4.6).
- Event head v2 scores, logging only (0.7.3).
- «In Ordner exportieren», ZIP without hour files, export scope, «Exportierte Aufnahmen löschen», export in a foreground service (0.7.4, 0.9.4, 0.9.4.1).
- Storage cap as an audio budget, warning and start block, 5 GB default (0.7.5).
- Short video instead of a photo in «Laut! Melden» (0.9.4.3; switched off again in 0.9.4.6).
- One switch «Audio speichern» on the Labor card (0.9.4.6).
- Signed Labor and release builds from GitHub Actions (0.7.4, 0.8.0) — tooling; the iPhone gets its own (`.github/workflows/ios-testflight.yml`).

## Counts

Affects what is sent: 12. Visible in the app: 19. Labor only: 8.
