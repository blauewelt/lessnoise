# Stadtlärm

**Deutsch:** Stadtlärm ist ein offenes Messnetz für Strassenlärm, von Anwohnerinnen und
Anwohnern für Anwohnerinnen und Anwohner, beginnend in Zürich. Eine Android-App misst den
Schallpegel durchgehend, auch nachts, hält laute Einzelereignisse (z. B. Töffs, Poser) mit Zeit,
Spitzenpegel und wahrscheinlicher Quelle fest, fasst jede Nacht von 22 bis 6 Uhr zusammen, zeigt
den Verlauf als Grafik und exportiert die Daten als CSV. Es wird nie Audio gespeichert oder übertragen.
Ab v0.5.0 gibt es die App in zwei Ausführungen: Die Offline-Version
(Standard) hat, wie alle Versionen bis v0.4.0, gar keine Internet-Berechtigung. Die Karten-Version
sendet nur dann etwas ins Internet, wenn man «Messwerte teilen» selbst einschaltet:
dann Pegel und Ereignisse, aber nie Audio und nie den genauen Standort, an den
Server des Projekts in der Schweiz, für eine gemeinsame Lärmkarte pro Hektare. Ab v0.5.1 bestimmt
die Karten-Version die Hektare selbst: Sie fragt nach der Standort-Berechtigung, liest den Standort
nur, wenn man «Standort bestimmen» tippt, einmal und nie im Hintergrund, und rechnet ihn auf dem
Telefon in die Hektare um; die Koordinaten werden weder gespeichert noch gesendet. Die
Offline-Version hat keine Standort-Berechtigung. Ein fest
montierter Fenstersensor ist in Entwicklung. Download und Anleitung: [wenigerlaerm.ch](https://wenigerlaerm.ch).

Stadtlärm is an open road-noise measurement network run by residents for residents, starting in
Zürich ([wenigerlaerm.ch](https://wenigerlaerm.ch)). An Android app measures A-weighted sound levels
continuously, including at night, records loud single events (motorbikes, revving cars, …) with
time, peak level and likely source, summarises each night (22:00–06:00) and exports CSV. The
data stays on the phone of the person who measured it, unless they use the map edition
(«Karten-Version», from v0.5.0) and switch on the opt-in «Messwerte teilen» to
contribute their levels and events to a shared per-hectare noise map. The default download, the
«Offline-Version», has no internet permission at all.

## Privacy

No audio is ever stored or transmitted; sound source classification runs on the phone and only
levels and statistics are kept. Up to v0.4.0 (the version published on wenigerlaerm.ch) the app has no
internet permission at all, and from v0.5.0 neither has the default
**Offline-Version** (`stadtlaerm.apk`). The **Karten-Version** (`stadtlaerm-karte.apk`, same app id,
installs over the other and keeps the data) uses the internet for exactly one thing, the opt-in upload «Messwerte teilen» described
in [server/DESIGN.md](server/DESIGN.md) §2 and §4: off until switched on, minute and event
numbers plus a 100 m hectare (never coordinates, never the raw classifier labels), to the
project's server in Switzerland (`api.lessnoise.ch`; `api.stadtlaerm.ch` for app versions up to
0.5.1, the same server), through one network class
(`android/app/src/main/kotlin/ch/stadtlaerm/app/upload/UploadClient.kt`), with no third-party SDK;
deletable on the server from the app at any time, and deleted by the server automatically after
60 days without uploads. From v0.5.1 the Karten-Version also has the location permission
(`ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`), used only when you tap «Standort bestimmen» in
the placement step: one fix from Android's own location service, turned into the hectare on the
phone; the coordinates are neither stored nor sent, and location is never read in the background
(no `ACCESS_BACKGROUND_LOCATION`). The Offline-Version has no location permission.
On the server (schema v3) a minute or event may carry its own hectare instead of the site's,
and manual reports of a loud vehicle are stored with their point and published on the
map at that point, with the time rounded down to the full hour and no device id
([PRIVACY.md](PRIVACY.md), «On the server»). The map shows per-hectare figures per calendar day
(all hours), not per night.
[PRIVACY.md](PRIVACY.md) explains how the code keeps these promises and how to check them.

## Usage numbers

What the project counts, and who counts it — totals over the whole project, all public:

- **Downloads:** not counted. The APKs are served from the website (`docs/download/`, GitHub
  Pages), which reports nothing. `python3 scripts/download_stats.py` reads GitHub Release download
  counts, should a version ever also be attached to a GitHub Release.
- **Sharing:** the server publishes [`stats.json`](https://api.stadtlaerm.ch/v1/map/stats.json)
  with the map files ([server/DESIGN.md](server/DESIGN.md) §6.5): devices registered, with a
  hectare, ever shared, active in 7/30 days, deleted, deleted by the server after 60 days without
  uploads; hectares on the map; shared device-days; app versions of active devices; registrations
  per ISO week. Computed from the data the map already
  uses; no ids, no places, no IP addresses.
- `python3 scripts/usage_report.py` prints both in one short report.
- **Not counted:** anything about the Offline-Version beyond its downloads (it has no internet
  permission and cannot report anything), and anything about Karten-Version users who leave
  «Messwerte teilen» off. No analytics, no crash reports, no app-start pings in any edition.

## Repository layout

| Path | Contents |
|---|---|
| [`android/`](android/) | Android app (Kotlin): measurement service, on-device sound classifier, calibration, UI, plus the platform-independent `dsp` module. See [android/README.md](android/README.md) for install, calibration and build instructions |
| [`firmware/`](firmware/) | Planned ESP32-S3 windowsill sensor (placeholder, no code yet) |
| [`tools/weather/`](tools/weather/) | Command-line tool that joins MeteoSwiss station weather (wind, gusts, rain, temperature) with the app's CSV export, per minute, hour and night |
| [`docs/`](docs/) | The website [wenigerlaerm.ch](https://wenigerlaerm.ch) (static HTML/CSS, one hash-pinned inline script on `update.html`, served by GitHub Pages), the noise map `karte.html` (vendored Leaflet, swisstopo tiles and the project's API as its only external hosts; see [docs/README.md](docs/README.md)) and, in `docs/download/`, the Offline-Version APK as a fallback for old links (new downloads come from GitHub Releases) |
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
- **Phone app v0.5.1 (published 2026-10-12, the current version):** the Karten-Version determines the hectare
  itself with «Standort bestimmen» (location permission, one fix on tap, coordinates neither stored
  nor sent); pasting coordinates remains as a second way. The Offline-Version is unchanged and has
  no location permission.
- **Noise map and server:** the contribution server ([server/](server/)) runs in Switzerland at
  `api.stadtlaerm.ch` and, once its DNS record exists, `api.lessnoise.ch` (the same server); the
  map page `docs/karte.html` is on the website, covers all of Switzerland, shows the last 7 or 30
  days per hectare with a 24-hour profile, and the manual reports as points.
- The app passes its JVM unit tests and JVM renders but is not yet validated on many devices. Download at
  [wenigerlaerm.ch](https://wenigerlaerm.ch); changes in [android/CHANGELOG.md](android/CHANGELOG.md).
- **Windowsill sensor:** planned, see [firmware/README.md](firmware/README.md).

Bug reports and measurements from different phone models are welcome as
[GitHub issues](https://github.com/blauewelt/lessnoise/issues).

## License

Apache-2.0, see [LICENSE](LICENSE).
