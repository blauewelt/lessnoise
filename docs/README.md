# docs/ — the website wenigerlaerm.ch (formerly stadtlaerm.ch, which now redirects via blauewelt/stadtlaerm-weiterleitung)

Static files served by GitHub Pages (`CNAME`: wenigerlaerm.ch). No build step. German, Swiss
spelling (ss, never ß), «Sie», guillemets, system fonts, no third-party requests — except on the
map page, which says so. Since 0.6.0 the app is «Weniger Lärm», published in two editions that
the site calls the «Offline-Version» (no internet, no location) and the «Online-Version» (sharing
for the map and «Laut! Melden»); it speaks of measurements, never of nights;
`tests/pages.spec.js` checks that for index, datenschutz and update.

| File | What |
|---|---|
| `index.html`, `style.css` | Home page (promise, the two uses, download with version/size/SHA-256, installation and calibration as collapsed `<details>`, map, privacy in two lines, association) and the shared stylesheet (light/dark via CSS variables) |
| `datenschutz.html` | Privacy page (mirrors [../PRIVACY.md](../PRIVACY.md)) |
| `update.html` | Update check opened by the app (`#v=…&c=…&e=offline` or `karte`; one inline script, pinned by hash in its CSP — after editing it run `python3 android/tools/csp_hash.py --write docs/update.html`). Installs older than `data-first-code` (13 = 0.6.0) are «Stadtlärm», another app id: they are told to uninstall it first |
| `karte.html` | The noise map ([../server/DESIGN.md](../server/DESIGN.md) §7) |
| `map/lv95.js` | WGS84 ↔ Swiss LV95 and hectare cell ids (§3), pure functions |
| `map/model.js` | Pure helpers of the map: wording, colour scales, report categories, the card's 24-hour chart model (with the LSV bands) |
| `map/karte.js`, `map/karte.css` | The map page itself (Leaflet, legend, period selector and day steps, reports layer, card) |
| `vendor/leaflet/` | Leaflet 1.9.4, copied unchanged from the npm package (`node_modules/leaflet/dist`) |
| `download/` | The signed APKs the download buttons link: `stadtlaerm.apk` = «Offline-Version», `stadtlaerm-karte.apk` = «Online-Version». The file names stayed when the app was renamed (0.6.0, as decided for the release) so that existing links, bookmarks and `scripts/download_stats.py` keep working. At release, replace both files and fill in version, size and SHA-256 in `index.html` (`.facts`) and `data-version`/`data-code` in `update.html` |
| `img/` | `karte-360.png` (map screenshot, `scripts/karte_screenshot.js`); `messung-tag(-dark).png` (home page, a Paparazzi render of `ChartScreenshots.messung24h*`, synthetic data, cropped to 1082 × 1850); `chart-nacht*.png` show the old screen of 0.5.x and are no longer linked |

## The map page

- Reads `cells.json`, `days/{date}.json`, `cells/{cell}.json` and `reports.json` from the URL
  in the map element's `data-api` attribute (`https://api.lessnoise.ch/v1/map/`); if that host
  cannot be reached at all before it has answered once, it switches to `data-api-fallback`
  (`https://api.stadtlaerm.ch/v1/map/`, the same server) for the rest of the visit. The page's
  CSP allows `connect-src` only to these two hosts, so a different API host needs a CSP change
  too. Remove the fallback and its CSP entry once api.lessnoise.ch has served the map for a while.
- Tiles: swisstopo `ch.swisstopo.pixelkarte-grau` (Web Mercator), credit «© swisstopo».
- The formats are those of `server/example_cells.json`, `server/example_cell.json` and
  `server/example_reports.json`, which the server's tests check against the real publisher.
- No word «Nacht» on the map: the unit tests check `karte.html` and `map/*`.

## Before committing a change to the map

```sh
python3 scripts/stamp_assets.py            # hash-stamp the ?v= of karte.html's scripts/styles
npm test                                   # unit tests, stamp check, Playwright (360 px and 1280 px)
```

`npm test` runs `node --test 'tests/unit/*.test.js'`, `python3 scripts/stamp_assets.py --check`
and `playwright test`. The Playwright tests serve `docs/` locally and answer the API from the
server fixtures and swisstopo with a grey placeholder tile; a request to any other host fails.
`node scripts/karte_screenshot.js` (with `python3 -m http.server 8090 --directory docs`
running) renders `img/karte-360.png` the same way; `REAL_TILES=1` lets the swisstopo tiles
through.

To try the page against a local server, serve `docs/` and change `data-api` and the CSP's
`connect-src` together — and change them back before committing (a test checks the CSP).
