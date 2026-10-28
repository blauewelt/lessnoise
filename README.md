# Weniger Lärm

**Deutsch:** Weniger Lärm (bis v0.5.1 «Stadtlärm») ist ein offenes Messnetz für Strassenlärm,
von Anwohnerinnen und Anwohnern für Anwohnerinnen und Anwohner. Eine Android-App misst den
Schallpegel, solange eine Messung läuft – von «Start» bis «Stopp», zu jeder Tageszeit, auch über
mehrere Tage –, hält laute Einzelereignisse (z. B. laute Fahrzeuge) mit Zeit, Spitzenpegel und
wahrscheinlicher Quelle fest, zeigt jede Messung als Grafik (ihr Zeitraum und der Tagesverlauf)
und exportiert die Daten als CSV. Es wird nie Audio gespeichert oder übertragen.
Es gibt die App in zwei Versionen. Die Online-Version (Standard) sendet nur dann Messwerte ins
Internet, wenn man beim ersten Start einer Messung die Frage «Auf die Lärmkarte?» mit «Ja, auf die Karte»
beantwortet oder unter «Teilen» «Auf die Karte senden» einschaltet: dann Pegel und Lärmspitzen
jeder geteilten Messung mit dem Ort, an dem sie gestartet wurde – zufällig verschoben,
standardmässig um etwa 50 m (in der App einstellbar); das Telefon bestimmt den Standort gleich nach
dem Start einmal, solange die App offen ist, und behält und sendet nur den verschobenen Punkt –, und
Meldungen mit «Laut! Melden» (Zeit, Standort dieser Meldung, so genau, wie das Telefon ihn gerade
hat, samt Genauigkeit, Kategorie, ab 0.7.0 wahlweise die Fahrtrichtung als Kompassrichtung, ab 0.9.0 der Spitzen- und der Mittelpegel der Sekunden, in denen das Melde-Fenster offen war – nur Zahlen, nichts aufgenommen; die Koordinaten werden nach dem Senden auf dem Telefon gelöscht; ein freiwilliges Foto vom Fahrzeug bleibt nur auf dem Telefon und wird nie gesendet) – nie Audio, an den
Server des Projekts in der Schweiz, für eine gemeinsame Lärmkarte. Der Standort wird nie im
Hintergrund gelesen. Seit 0.6.3 zeigt der Tab «Karte» die Lärmkarte der Website in der App; er lädt
sie nur, solange er offen ist, wie ein Browser (Seite, Kartenbilder von swisstopo, Kartendaten) und
sendet dabei keine Messwerte und keinen Standort. Ab 0.8.0 lädt «Laut! Melden», solange es offen ist, die
Kartenbilder der Umgebung des genauen Standorts von swisstopo (ab 0.9.0 auch die des Orts, an den
man die Karte verschiebt, bis 2 km), damit man die Strassen unter dem Pfeil sieht; swisstopo erfährt
dabei die IP-Adresse und die Gegend, wie bei der Karte. Ab 0.9.0 misst die Online-Version unterwegs
nicht mehr (keine Messung «Unterwegs», keine Mitteilung «Sehr laut»); unterwegs meldet man nur. Wer noch mehr Privatsphäre möchte, nimmt die Offline-Version: Sie hat gar
keine Internet- und keine Standort-Berechtigung, dafür keine Karte und keine Meldungen. Ein fest
montierter Fenstersensor ist in Entwicklung. Download und Anleitung:
[wenigerlaerm.ch](https://wenigerlaerm.ch).

Weniger Lärm («Less Noise»; until v0.5.1 «Stadtlärm») is an open road-noise measurement network run
by residents for residents ([wenigerlaerm.ch](https://wenigerlaerm.ch)). An Android app measures
A-weighted sound levels while a measurement runs (one run from Start to Stop, at any time of day,
possibly over several days), records loud single events (motorbikes, revving cars, …) with time,
peak level and likely source, shows each measurement as a chart (its own time span and a 24-hour
profile) and exports CSV. The data stays on the phone of the person who measured it, unless they use
the «Online-Version» (the edition with the map) and say yes to the opt-in («Auf die Lärmkarte?» at the
first start of a measurement since 0.6.3, or the switch in the tab «Teilen») to contribute
the levels and events of their measurements, each at the place where it was started, moved at
random on the phone by about 50 m by default (adjustable), and
reports made with «Laut! Melden», to a shared noise map, which the «Online-Version» also shows in its
tab «Karte» (0.6.3). The «Online-Version» is the default download
(since 0.6.2); the «Offline-Version», for those who want even more privacy, has no internet and no
location permission at all, and therefore no map and no reports.

## Privacy

No audio is ever stored or transmitted; sound source classification runs on the phone and only
levels and statistics are kept. Up to v0.4.0 the app had no internet permission at all, and neither
has the **«Offline-Version»** (`lessnoise.apk`; app id `ch.lessnoise.app` since 0.6.0), the edition
for even more privacy. The **«Online-Version»** (`lessnoise-karte.apk`, the default download since
0.6.2, same app id, installs over the other
and keeps the data) uses the internet for three things only. (1) The opt-in upload «Messwerte teilen» described in
[server/DESIGN.md](server/DESIGN.md) §2 and §4: off until you say yes («Auf die Lärmkarte?» at the
first start of a measurement, one dialog that names what is sent, what never, where and how to delete; or the
switch in «Teilen»); while a shared measurement runs each loud moment about 30 s after it ended and the minutes every 2 minutes (the live map), over mobile data too
unless «Nur über WLAN» is on; minute and event numbers of
shared measurements, each with the point of its measurement moved at random on the phone by a
Gaussian offset, σ = 50 m by default (25/50/100/200 m adjustable; the same offset every time for
the same place, so averaging does not remove it; never the coordinates of the fix, never the raw
classifier labels; since 0.7.2 a minute measured with a microphone the user chose instead of the
built-in one names only that microphone's kind, e.g. USB or Bluetooth), and «Laut! Melden» reports with the exact place of the
report (deleted from the phone once sent) and, since 0.7.0 and only if the reporter takes it, the direction of travel as one compass bearing, and since 0.9.0 (0.8.5 in the internal Labor·Karte build) the peak and average level of the seconds the report dialog was open, numbers only (the microphone runs through the measurement's level meter only while the dialog is open; nothing is recorded); never the optional photo of the vehicle, which the phone's camera app takes (the app has no camera permission) and which stays in the app's private storage on the phone; to one host (`api.lessnoise.ch`, in Switzerland),
through one network class (`android/app/src/main/kotlin/ch/lessnoise/app/upload/UploadClient.kt`),
with no third-party SDK; deletable on the server from the app at any time («Daten auf dem Server
löschen», reports included), and deleted by the server automatically after 60 days without
uploads. (2) Since 0.6.3 the tab «Karte», a locked-down WebView
(`android/app/src/karte/kotlin/ch/lessnoise/app/ui/MapScreen.kt`, only in the editions with the map)
that shows `https://wenigerlaerm.ch/karte.html` once the tab is opened (kept paused while another tab is shown), like a browser: the page, the
swisstopo tiles and the published map files, no other host, no cookies, no measurement and no
location sent (the view is in the part after `#`, which never leaves the phone; swisstopo sees which
map area is shown, as for any visitor) — see [PRIVACY.md](PRIVACY.md), «The tab «Karte»». (3) Since 0.8.0, in «Laut! Melden»,
while the report dialog is open (0.8.0: the step «Wohin fuhr es?») and the report's fix is there, swisstopo's grey map tiles
(`wmts.geo.admin.ch`, the Federal Office of Topography; the host the map tab already uses) for the
≈ 150 m around the reporter's **true** position and, since 0.9.0, around the place the reporter
moves the map to (at most 2 km away; that place is then sent with a fixed accuracy of 15 m), to draw the streets under the direction arrow
(`android/app/src/karte/kotlin/ch/lessnoise/app/ui/ReportMapTiles.kt`): GET only, no redirects, no
cache, no cookies, only inside Switzerland, kept in memory for that dialog only; swisstopo sees those
tile requests with the phone's IP address, like a visitor of the map, and nothing of it is stored or
sent to the project's server. The Online-Version's
location permission (`ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`) is used only
while the app is open: one fix right after the start of a shared measurement (moved by the location
noise on the phone; since 0.6.3 the measurement does not wait for it) and one per report (since 0.9.0 read when the report dialog opens, also if it is then cancelled); location is never read in the background (no
`ACCESS_BACKGROUND_LOCATION`). On the server (schema v4) a minute or event carries the shifted point it was sent with and is
shown in the 100 m field that contains it (the field is a display grid, not the protection);
and manual reports of a loud vehicle are stored with their point and published on the
map at that point, with the time rounded down to the full hour and no device id
([PRIVACY.md](PRIVACY.md), «On the server»). The map shows figures per 100 m field and calendar day
(all hours), not per night.
The website itself stores nothing about visitors, except an e-mail address someone leaves
voluntarily for update notices: only the address and the time, on the project's server in
Switzerland, used for nothing else and never passed on ([PRIVACY.md](PRIVACY.md), «The website»).
[PRIVACY.md](PRIVACY.md) explains how the code keeps these promises
and how to check them.

## Usage numbers

What the project counts, and who counts it — totals over the whole project, all public:

- **Downloads:** not counted. The APKs are served from the website (`docs/download/`, GitHub
  Pages), which reports nothing. `python3 scripts/download_stats.py` reads GitHub Release download
  counts, should a version ever also be attached to a GitHub Release.
- **Sharing:** the server publishes `stats.json` (`https://api.lessnoise.ch/v1/map/stats.json`)
  with the map files ([server/DESIGN.md](server/DESIGN.md) §6.5): devices registered, with a
  hectare, ever shared, active in 7/30 days, deleted, deleted by the server after 60 days without
  uploads; hectares on the map; shared device-days; app versions of active devices; registrations
  per ISO week. Computed from the data the map already
  uses; no ids, no places, no IP addresses.
- `python3 scripts/usage_report.py` prints both in one short report.
- **Not counted:** anything about the offline edition beyond its downloads (it has no internet
  permission and cannot report anything), and anything about Online-Version users who leave
  «Messwerte teilen» off. No analytics, no crash reports, no app-start pings in any edition.

## Repository layout

| Path | Contents |
|---|---|
| [`android/`](android/) | Android app (Kotlin): measurement service, on-device sound classifier, calibration, UI, plus the platform-independent `dsp` module. See [android/README.md](android/README.md) for install, calibration and build instructions |
| [`firmware/`](firmware/) | Planned ESP32-S3 windowsill sensor (placeholder, no code yet) |
| [`tools/weather/`](tools/weather/) | Command-line tool that joins MeteoSwiss station weather (wind, gusts, rain, temperature) with the app's CSV export, per minute, hour and night |
| [`docs/`](docs/) | The website [wenigerlaerm.ch](https://wenigerlaerm.ch) (static HTML/CSS, one hash-pinned inline script on `update.html`, served by GitHub Pages), the noise map `karte.html` (vendored Leaflet, swisstopo tiles and the project's API as its only external hosts; see [docs/README.md](docs/README.md)) and, in `docs/download/`, the offline edition's APK as a fallback for old links (new downloads come from GitHub Releases) |
| [`scripts/`](scripts/) | `stamp_assets.py` (cache stamps of the map page), `download_stats.py` and `usage_report.py` (usage numbers, see above), tests in `scripts/tests/` |
| [`PRIVACY.md`](PRIVACY.md) | What the project does with sound, with references into the code |
| [`server/`](server/) | The contribution server of the shared noise map (Python, FastAPI, SQLite); [server/DESIGN.md](server/DESIGN.md) is the contract between app, server and map |
| [`LICENSE`](LICENSE) | Apache License 2.0 |

## Status

- **Phone app v0.4.0 (published on wenigerlaerm.ch):** public test version with a night/day/week
  chart of the measurements, event detector v2 (events measured against the local background,
  wind on the microphone flagged and left out of the counts), an event floor that follows the
  calibration, re-evaluation of old measurements with a new calibration, and an update check that
  runs in the browser (the app itself has no internet permission).
- **Phone app v0.5.0 (published 2026-10-11), in two editions:** the **Offline-Version**
  (default download, no internet permission, the normal update for 0.4.0) and the
  **Karten-Version**: v0.4.0 plus the opt-in «Messwerte teilen», which sends levels and events
  (without wind events) with a 100 m hectare (never audio, never coordinates) to the shared noise
  map; it is off until switched on, and that is the only use of the internet permission it adds.
  Both are downloaded from the website (`docs/download/`).
- **Phone app v0.5.1 (published 2026-10-12):** the Karten-Version determines the hectare
  itself with «Standort bestimmen» (location permission, one fix on tap, coordinates neither stored
  nor sent); pasting coordinates remains as a second way. The Offline-Version is unchanged and has
  no location permission.
- **Phone app v0.6.0 (published 2026-10-13):** renamed to «Weniger Lärm» (app id
  `ch.lessnoise.app`, Labor `ch.lessnoise.labor`; a new app id, so it does not install over 0.5.x).
  Nothing about nights is special any more: the unit is a measurement from Start to Stop, listed
  under «Messungen» with its own chart and a 24-hour profile. In «Weniger Lärm · Karte» every shared
  measurement gets its own 100 m cell from one location fix at Start, and «Laut! Melden» reports a
  loud moment with its exact place. Server host `api.lessnoise.ch`.
- **Phone app v0.6.1 (published 2026-10-13):** «weniger Text, weniger
  Optionen» — tabs Messen · Messungen · Einstellungen (plus «Teilen» in «Weniger Lärm · Karte»);
  Kalibrieren and the two remaining detector settings under Einstellungen › Erweitert; ten
  options removed and fixed at their former defaults (see [android/CHANGELOG.md](android/CHANGELOG.md));
  about half the words of 0.6.0. Nothing about what is stored or sent changes.
- **Phone app v0.6.2 (published 2026-10-14):** the place of a shared measurement is moved on the phone by a
  Gaussian offset that is the same every time for the same place (σ 50 m by default, setting
  «Standort verschieben»: 25/50/100/200 m), instead of being snapped to the 100 m hectare; server
  schema v4 stores the shifted point and shows it in the 100 m square that contains it. The two
  editions are now called «Offline-Version» and «Online-Version» (the app is still «Weniger Lärm»).
- **Phone app v0.6.3 (published 2026-10-15):** sharing made simple — one question «Auf die
  Lärmkarte?» at the first «Starten»; the measurement starts at once and gets its shifted point when
  the fix arrives (all its minutes are sent); the live map: loud moments about 30 s after they end,
  minutes every 2 minutes, the map page refreshes every 30 s; over mobile data by default; one status line on «Messen» and «Teilen»; the new tab «Karte» shows the website's
  map in the app.
- **Phone app v0.6.4 (published 2026-10-16):** only machine and motor noise leaves the phone;
  «Unterwegs» is a start mode that shares nothing.
- **Phone app v0.6.6 (published 2026-10-17):** the phone's noise floor
  («Messgrenze», 0.6.5) is estimated from the stored minutes and values at it are shown with «≤»;
  the app measures only with the phone's built-in microphone, never a headset's.
- **Phone app v0.7.0 (Labor · Karte build for testing; built on branches as 0.6.7–0.6.9; published
  as part of 0.7.1):** «Unterwegs erkannt» — in the Online-Version a measurement started with «Messen»
  notices from the motion sensor (on the phone only, no permission, no location) that the phone is
  being carried; meanwhile it alerts like «Unterwegs», «Antippen zum Melden» opens a report, and
  nothing of that time span is shared. «Laut! Melden» sends a report at whatever accuracy the
  phone's location has, with that accuracy (it no longer stops at ± 50 m), and can carry the
  direction the vehicle drove, taken with the phone's compass.
- **Phone app v0.7.1 (published 2026-10-20):** 0.7.0 in both public editions;
  with headphones or a headset connected the app said «Das Telefon misst nur mit dem eingebauten
  Mikrofon. Bitte Kopfhörer oder externes Mikrofon trennen.» instead of measuring.
- **Phone app v0.7.2 (published 2026-10-20):** the built-in microphone stays
  the default and the app never switches to a headset by itself, but another microphone (USB, wired,
  LE Audio) can be chosen under «Einstellungen → Erweitert → Mikrofon»; the app then uses it
  whenever it is connected. Calibration is per microphone; a minute measured with another
  microphone sends that microphone's kind with its audio source.
- **Next (in the internal Labor·Karte test build 0.9.4.5, not published yet; the public app will be 0.9.5):**
  in «Laut! Melden» only the place is required — the kind of vehicle is optional (none chosen = none sent), the
  direction can be switched off («×» on the compass, «Ohne Richtung»), and «Anzahl» (1–9) counts several
  vehicles (sent only above 1). «Andere» folds out six choices: Lastwagen, Flugzeug, Bahn, Baustelle,
  Gartengeräte, Hupen. The map shows a report without a kind as a grey point, several vehicles as a short
  convoy (or «×3» without a direction). Nothing about the person is added. Server schema v9.
- **Before that (Labor·Karte 0.9.4.4):** «Messen» puts reporting
  first. «Laut! Melden» is one large button in both states — idle above «Langzeitmessung starten», and also
  while a measurement runs, under a compact level card, with «Stoppen» below it — and the measurement
  notification gets a «Melden» button that opens the report dialog; a tap on the notification opens
  «Messen». The report dialog says plainly «Die Meldung erscheint genau an diesem Ort auf der Karte.» (was
  «Wer oft vom gleichen Ort meldet, macht ihn auf der Karte sichtbar.»). Long measurements are unchanged; the
  Offline-Version has no reports and keeps its «Messen».
- **Phone app v0.9.0 (published 2026-10-25, the current version):** in the Online-Version
  «Laut! Melden» is one dialog with «Senden» (compass and streets, the map can be moved under the dot
  to correct the place, the level measured while the dialog is open and sent with the report, an
  optional photo of the vehicle that stays on the phone instead of the typed plate), also opened by an
  app shortcut and a home-screen widget; no «Unterwegs» measurement and no «Sehr laut» notification
  any more (a measurement is the long one at the window); a dark «Karte» tab and a redesigned «Messen»
  and «Teilen». The Offline-Version gets the new look and texts. Both APKs are built and signed by
  GitHub Actions.
- **Phone app v0.8.0 (published 2026-10-24):** in the Online-Version one
  notification for «Sehr laut» instead of two; in «Laut! Melden» a compass rose with the real
  streets around you under the direction arrow (map tiles from swisstopo, which sees those requests
  with your IP address), an optional «Kontrollschild» field that stays on the phone, the category
  «Flugzeug» and an «erkannt» marker instead of pre-filled buttons. The Offline-Version is
  unchanged for its users.
- **Noise map and server:** the contribution server ([server/](server/)) runs in Switzerland at
  `api.lessnoise.ch` (apps up to 0.5.1: `api.stadtlaerm.ch`, the same server); the
  map page `docs/karte.html` is on the website, covers all of Switzerland, shows the last 7 or 30
  days per hectare with a 24-hour profile, and the manual reports as points.
- The app passes its JVM unit tests and JVM renders but is not yet validated on many devices. Download at
  [wenigerlaerm.ch](https://wenigerlaerm.ch); changes in [android/CHANGELOG.md](android/CHANGELOG.md).
- **Windowsill sensor:** planned, see [firmware/README.md](firmware/README.md).

Bug reports and measurements from different phone models are welcome as
[GitHub issues](https://github.com/blauewelt/lessnoise/issues).

## License

Apache-2.0, see [LICENSE](LICENSE).
