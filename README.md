# Weniger Lärm

**Deutsch:** Weniger Lärm (bis v0.5.1 «Stadtlärm») ist ein offenes Messnetz für Strassenlärm,
von Anwohnerinnen und Anwohnern für Anwohnerinnen und Anwohner. Eine Android-App misst den
Schallpegel, solange eine Messung läuft – von «Start» bis «Stopp», zu jeder Tageszeit, auch über
mehrere Tage –, hält laute Einzelereignisse (z. B. Töffs, Poser) mit Zeit, Spitzenpegel und
wahrscheinlicher Quelle fest, zeigt jede Messung als Grafik (ihr Zeitraum und der Tagesverlauf)
und exportiert die Daten als CSV. Es wird nie Audio gespeichert oder übertragen.
Es gibt die App in zwei Ausführungen: «Weniger Lärm» (Standard) hat gar keine Internet- und keine
Standort-Berechtigung. «Weniger Lärm · Karte» sendet nur dann etwas ins Internet, wenn man
unter «Teilen» (bis 0.6.0 «Messwerte teilen») selbst «Auf die Karte senden» einschaltet: dann Pegel und Lärmspitzen jeder geteilten Messung mit der
100-m-Zelle, in der sie gestartet wurde (das Telefon bestimmt den Standort beim Start einmal und
behält nur die Zelle), und Meldungen mit «Laut! Melden» (Zeit, genauer Standort dieser Meldung,
Kategorie; die Koordinaten werden nach dem Senden auf dem Telefon gelöscht) – nie Audio, an den
Server des Projekts in der Schweiz, für eine gemeinsame Lärmkarte. Der Standort wird nie im
Hintergrund gelesen. Ein fest montierter Fenstersensor ist in Entwicklung. Download und Anleitung:
[wenigerlaerm.ch](https://wenigerlaerm.ch).

Weniger Lärm («Less Noise»; until v0.5.1 «Stadtlärm») is an open road-noise measurement network run
by residents for residents ([wenigerlaerm.ch](https://wenigerlaerm.ch)). An Android app measures
A-weighted sound levels while a measurement runs (one run from Start to Stop, at any time of day,
possibly over several days), records loud single events (motorbikes, revving cars, …) with time,
peak level and likely source, shows each measurement as a chart (its own time span and a 24-hour
profile) and exports CSV. The data stays on the phone of the person who measured it, unless they use
the map edition («Weniger Lärm · Karte») and switch on the opt-in «Messwerte teilen» (since 0.6.1
the tab «Teilen») to contribute
the levels and events of their measurements, each at the 100 m cell where it was started, and
reports made with «Laut! Melden», to a shared noise map. The default download, «Weniger Lärm», has
no internet and no location permission at all.

## Privacy

No audio is ever stored or transmitted; sound source classification runs on the phone and only
levels and statistics are kept. Up to v0.4.0 the app had no internet permission at all, and neither
has the default edition **«Weniger Lärm»** (`lessnoise.apk`; app id `ch.lessnoise.app` since
0.6.0). **«Weniger Lärm · Karte»** (`lessnoise-karte.apk`, same app id, installs over the other
and keeps the data) uses the internet only for the opt-in upload «Messwerte teilen» described in
[server/DESIGN.md](server/DESIGN.md) §2 and §4: off until switched on; minute and event numbers of
shared measurements, each with the 100 m cell of its measurement (never the coordinates of that
fix, never the raw classifier labels), and «Laut! Melden» reports with the exact place of the
report (deleted from the phone once sent); to one host (`api.lessnoise.ch`, in Switzerland),
through one network class (`android/app/src/main/kotlin/ch/lessnoise/app/upload/UploadClient.kt`),
with no third-party SDK; deletable on the server from the app at any time («Daten auf dem Server
löschen», reports included), and deleted by the server automatically after 60 days without
uploads. Its location permission (`ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`) is used only
while the app is open: one fix at «Start» of a shared measurement (turned into the cell on the
phone) and one per report; location is never read in the background (no
`ACCESS_BACKGROUND_LOCATION`). On the server (schema v3) a minute or event may carry its own hectare instead of the site's,
and manual reports of a loud vehicle are stored with their point and published on the
map at that point, with the time rounded down to the full hour and no device id
([PRIVACY.md](PRIVACY.md), «On the server»). The map shows per-hectare figures per calendar day
(all hours), not per night.
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
  permission and cannot report anything), and anything about Karte edition users who leave
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
- **Phone app v0.6.1 (published 2026-10-13, the current version):** «weniger Text, weniger
  Optionen» — tabs Messen · Messungen · Einstellungen (plus «Teilen» in «Weniger Lärm · Karte»);
  Kalibrieren and the two remaining detector settings under Einstellungen › Erweitert; ten
  options removed and fixed at their former defaults (see [android/CHANGELOG.md](android/CHANGELOG.md));
  about half the words of 0.6.0. Nothing about what is stored or sent changes.
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
