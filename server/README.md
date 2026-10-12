# Weniger Lärm contribution server

The server behind the shared noise map: phones that opted in to «Messwerte teilen» send their
minute and event records and their manual reports here, and every 10 minutes the server turns
them into per-hectare figures per calendar day (plus a 24-hour profile per hectare) and a list
of the reports of the last 30 days, which the map page reads as static JSON. [DESIGN.md](DESIGN.md) is the contract;
this file is about running it.

One process (Python 3.12, FastAPI, SQLite) does everything: the upload API, the static map
files under `/v1/map/`, and a background loop that republishes the map and runs the daily
retention job (which also deletes devices that sent nothing for 60 days). Caddy in front of it handles TLS for every host name in `API_HOSTS` (default
`api.lessnoise.ch` and `api.stadtlaerm.ch`, both serve the same API).

## Layout

| Path | What it is |
|---|---|
| `stadtlaerm_server/app.py` | The HTTP API of DESIGN.md §4, auth, gzip bodies, limits, `/v1/map/*`, background loop |
| `stadtlaerm_server/schemas.py` | Request validation (§4 fields, §8 limits) |
| `stadtlaerm_server/db.py` | SQLite schema (§5), migrations by version, row helpers |
| `stadtlaerm_server/aggregate.py` | Day, hour and 24-hour-profile arithmetic, pure functions (DESIGN.md §6) |
| `stadtlaerm_server/lv95.py` | WGS84 → LV95 and the Switzerland checks for cells and report points |
| `stadtlaerm_server/publish.py` | Builds and atomically writes `cells.json`, `cells/{cell}.json`, `days/{date}.json`, `reports.json`, `stats.json` |
| `stadtlaerm_server/stats.py` | The project-wide usage counts of `stats.json` (DESIGN.md §6.5) |
| `stadtlaerm_server/retention.py` | Reduces raw rows older than 2 years to hourly aggregates, deletes reports older than 2 years; deletes devices without uploads for 60 days (§2.9) |
| `stadtlaerm_server/synthetic.py` | Invented test data (a port of the app's chart test fixtures) |
| `scripts/make_fixture.py` | Generates `example_cells.json`, `example_cell.json` and `example_reports.json` through the real publisher |
| `scripts/backup.sh` | Nightly encrypted SQLite backup |
| `scripts/deploy.sh`, `scripts/install-autodeploy.sh` | Automatic pull-based deployment (5-minute timer) |
| `example_cells.json` | A `cells.json` for 25 Zürich hectares, invented data, exact server format (day 8 Oct 2026, 18:40) |
| `example_cell.json` | The matching `cells/h26828_12455.json` (Seestrasse, two devices, around the clock) |
| `example_reports.json` | The matching `reports.json` (137 invented reports near the sites) |

## Run locally

```sh
cd server
python3.12 -m venv .venv
.venv/bin/pip install -e '.[dev]'
DB_PATH=data/stadtlaerm.sqlite MAP_DIR=data/map .venv/bin/python -m stadtlaerm_server serve
# → http://localhost:8000/v1/map/cells.json  (published at startup, empty until data arrives)
```

(`make venv` and `make run` do the same.) Try it:

```sh
curl -s -X POST localhost:8000/v1/devices -H 'Content-Type: application/json' \
     -d '{"app_version":"0.4.0","app_build":40}'
# {"device_id":"…","token":"…"}  — then send  Authorization: Bearer <token>  on every call
```

Other commands: `python -m stadtlaerm_server publish` rebuilds the map files once,
`python -m stadtlaerm_server retention` runs the retention job once (2-year reduction, then
deletion of devices without uploads for `INACTIVE_DELETE_DAYS`; republishes the map if any
device was deleted; prints `{"hours": …, "minutes_deleted": …, "events_deleted": …,
"reports_deleted": …, "devices_expired": …}`),
`python -m stadtlaerm_server stats` prints the usage counts of `stats.json` (computed now,
nothing written).

To regenerate the example fixture after a format change: `python scripts/make_fixture.py`
(about 40 s). It writes synthetic days (three measuring schedules: around the clock, evenings,
daytime) and reports for 25 hectares into a temporary database and runs the real `publish()` on
it, so the files are always what the server really emits.

## Run the tests

```sh
cd server
.venv/bin/python -m pytest        # or: make test
```

The tests cover the API round trip (register, site, minutes and events as plain and gzip JSON,
duplicates, delete, auth and validation failures, rate limits, body limits, map headers),
per-record cells (stored, validated, fallback to the site cell), reports (validation, dedupe,
gzip, rate limit, delete, expiry, 2-year retention, `reports.json` rounding and no device ids),
the day arithmetic (a measurement over midnight, the 25-hour and 23-hour DST days,
low-coverage minutes, two- and three-device cells, the 24-hour profile and its 30-minute rule),
the v2 → v3 migration (rows kept), the Caddy/compose host list, publishing, retention, the
deletion of devices without uploads for 60 days (`tests/test_expiry.py`: 61 days deleted with
every row, 59 kept, `created_at` when never seen, 0 disables, hidden devices, separate counter,
map after publish, log line, CLI), and the usage counts of `stats.json` (every field, after a
delete and after retention).

## The API in one screen

| Call | Auth | Answer |
|---|---|---|
| `POST /v1/devices` `{app_version, app_build}` | – | `201 {device_id, token}` |
| `PUT /v1/devices/{id}/site` (DESIGN §4.2) | Bearer | `204` |
| `POST /v1/devices/{id}/minutes` `[…≤ 1440]` | Bearer | `200 {accepted, duplicates, rejected}` |
| `POST /v1/devices/{id}/events` `[…≤ 2000]` | Bearer | `200 {accepted, duplicates, rejected}` |
| `POST /v1/devices/{id}/reports` `[…≤ 50]` (DESIGN §4.7) | Bearer | `200 {accepted, duplicates, rejected}` |
| `DELETE /v1/devices/{id}` | Bearer | `204` after every row is gone |
| `GET /v1/map/cells.json`, `/v1/map/cells/{cell}.json`, `/v1/map/days/{date}.json`, `/v1/map/reports.json` | – | static JSON |
| `GET /v1/map/stats.json` | – | static JSON: project-wide usage counts (below) |
| `GET /healthz` | – | `{ok: true, commit}` (the deployed git commit) |

Every call answers the same on `api.lessnoise.ch` and `api.stadtlaerm.ch`. Minutes and events
may carry `"cell": "h<E100>_<N100>"` (DESIGN §4.3); without it they count for the device's
current site cell.

Bodies may be plain JSON or gzip (`Content-Encoding: gzip`), at most 2 MB on the wire.
Errors are `{"detail": …}`; validation errors list `{field, reason}` for each problem.

**Record-level rejects.** In a minutes, events or reports upload, a record that fails
validation (a level outside 0–140 dB, coverage outside 0–1, a start more than 7 days old or in
the future, a cell or report point outside Switzerland, an accuracy over 200 m, an unknown
category or field…) is skipped and reported in `rejected` as `{index, start, reasons}` (reports:
`{index, time, reasons}`); the valid
records of the same batch are stored. The app should treat rejected records as permanently
refused (they will never be accepted) and move on, rather than retrying the batch forever.

**Status codes.** 401 bad or missing token (unknown device and wrong token look the same),
413 body or batch too large, 415 unknown `Content-Encoding`, 400 not JSON / not gzip,
422 schema errors in the whole body, 429 rate limit (with `Retry-After`).

## Deploy on a fresh Ubuntu VPS at Infomaniak

What you need: an Infomaniak VPS or Public Cloud instance in Switzerland (the smallest size
is plenty: the database grows by roughly 100 MB per device per year of measuring 8 hours a day, three times that around the clock), Ubuntu 24.04, SSH
access, and the DNS zones of `lessnoise.ch` and `stadtlaerm.ch` (at Infomaniak).

1. **DNS.** In the Infomaniak manager, in each zone (`lessnoise.ch`, `stadtlaerm.ch`): add an
   `A` record `api` → the server's IPv4 address (and an `AAAA` record for its IPv6 address if
   it has one). Wait until `dig +short api.lessnoise.ch` and `dig +short api.stadtlaerm.ch`
   show the address. A host whose record is still missing does not block the other: Caddy
   gets each certificate on its own and keeps retrying the missing one in the background (its
   log shows the failed attempts until the record exists). To serve fewer hosts set
   `API_HOSTS` in `.env`.
2. **Firewall.** In the Infomaniak cloud firewall (and `ufw` if you use it) allow TCP 22, 80,
   443 and UDP 443. Caddy needs port 80 to obtain the certificate.
3. **Docker.**
   ```sh
   sudo apt update && sudo apt install -y ca-certificates curl git gnupg sqlite3
   curl -fsSL https://get.docker.com | sudo sh
   ```
4. **Code and config.**
   ```sh
   sudo git clone https://github.com/blauewelt/lessnoise /opt/stadtlaerm
   cd /opt/stadtlaerm/server
   sudo cp .env.example .env && sudo nano .env      # usually nothing to change
   ```
5. **Start.**
   ```sh
   sudo docker compose up -d --build
   sudo docker compose logs -f                      # Caddy logs "certificate obtained" per host
   curl https://api.lessnoise.ch/healthz            # {"ok":true,"commit":"unknown"}
   curl https://api.stadtlaerm.ch/healthz           # the same
   curl -I https://api.stadtlaerm.ch/v1/map/cells.json
   ```
   The SQLite file and the map files live in the Docker volume `server_data`
   (`/data` in the container); certificates in `server_caddy_data`.
6. **Backups** (DESIGN §5: nightly, encrypted, 30 days). Create a passphrase and keep a copy
   of it somewhere safe *outside* the server (without it the backups are useless):
   ```sh
   sudo sh -c 'openssl rand -base64 32 > /root/.stadtlaerm-backup-passphrase && chmod 600 /root/.stadtlaerm-backup-passphrase'
   ```
   Optional off-site copy: create an Infomaniak Swiss Backup / object storage bucket, configure
   it as an rclone remote (`sudo apt install rclone && sudo rclone config`, S3 provider
   "Other", the Infomaniak endpoint), and set `BACKUP_REMOTE=<remote>:<bucket>` in `.env`.
   Then add the cron job (`sudo crontab -e`):
   ```cron
   15 4 * * * /opt/stadtlaerm/server/scripts/backup.sh >> /var/log/stadtlaerm-backup.log 2>&1
   ```
   `backup.sh` runs `sqlite3 … ".backup …"` inside the container (a consistent copy while the
   app keeps writing), gzips it, encrypts it with GPG (AES-256, the passphrase file), writes it
   to `/var/backups/stadtlaerm/`, copies it to `BACKUP_REMOTE` if set, and deletes copies older
   than 30 days. Restore:
   ```sh
   gpg -d stadtlaerm-YYYYmmdd-HHMM.sqlite.gz.gpg | gunzip > stadtlaerm.sqlite
   sudo docker compose stop app
   # remove the old write-ahead log first, it belongs to the old file
   sudo docker compose run --rm --no-deps app rm -f /data/stadtlaerm.sqlite-wal /data/stadtlaerm.sqlite-shm
   sudo docker compose cp stadtlaerm.sqlite app:/data/stadtlaerm.sqlite
   sudo docker compose start app
   ```
7. **Updates.** Automatic, see «Automatic deployment» below. By hand:
   `cd /opt/stadtlaerm && sudo git pull && cd server && sudo docker compose up -d --build`.
   Schema migrations run automatically at startup (table `schema_version`).

### Automatic deployment

The server pulls; nothing is pushed to it. A systemd timer runs `scripts/deploy.sh` every
5 minutes. It fetches the configured branch from GitHub (the public repository, read-only, no
key), and if the branch moved it checks out the new commit, rebuilds and restarts the
containers (Caddy too if the `Caddyfile` changed) and waits until `/healthz` — called through
Caddy on this machine — answers with the new commit. If it does not, the script goes back to
the previous commit and does not try that commit again; the next push is tried normally. A
branch without `server/compose.yaml` is never deployed.

Set up once (default branch `main`; run again to change the branch):

```sh
sudo /opt/stadtlaerm/server/scripts/install-autodeploy.sh main
```

- Log: `journalctl -u stadtlaerm-deploy` (one line per deployment, rollback or refusal).
- Deploy now instead of waiting: `sudo systemctl start stadtlaerm-deploy`.
- Which commit runs: `curl https://api.stadtlaerm.ch/healthz` → `{"ok":true,"commit":"…"}`
  (`unknown` when started by hand without `GIT_COMMIT`).
- Switch off: `sudo systemctl disable --now stadtlaerm-deploy.timer`.

Consequence: whoever can push to the deployed branch decides what runs here within about
5 minutes. Keep the branch protected accordingly. Local edits to tracked files on the server
make the checkout fail (the log says so); keep server-only settings in the untracked `.env`.

**Operator tasks.** Usage numbers (the same as `https://api.stadtlaerm.ch/v1/map/stats.json`):
`sudo docker compose exec app python -m stadtlaerm_server stats`.
Keep a misbehaving device out of the map without deleting its data:
`sudo docker compose exec app sqlite3 /data/stadtlaerm.sqlite "UPDATE devices SET hidden = 1 WHERE id = '…'"`
(it disappears on the next publish; its owner can still delete it).

**Cloud Run alternative** (DESIGN §8): the same image runs on Google Cloud Run in
`europe-west6` with a volume mounted at `/data`; set `TRUST_PROXY=1`, keep one instance
(`--max-instances 1`, SQLite and the in-process rate limits assume a single process) and
`--no-cpu-throttling` so the background loop keeps running between requests.

## Environment variables

| Variable | Default | Meaning |
|---|---|---|
| `DB_PATH` | `data/stadtlaerm.sqlite` (container: `/data/stadtlaerm.sqlite`) | Where the SQLite database file lives. Created on first start. |
| `MAP_DIR` | `data/map` (container: `/data/map`) | Folder the published map files are written to and served from under `/v1/map/`. |
| `MIN_DEVICES_PER_CELL` | `1` | A hectare is shown on the map for a day (and an hour of its 24-hour profile has values) only if at least this many devices measured there. Raise it if the network becomes dense. Does not apply to reports. |
| `LOUD_EVENT_DB` | `75` | An event with LAFmax ≥ this (dB(A)) counts as a «loud event» (`loud_events`, `loud_events_per_h`; published as `loud_event_db`). |
| `PUBLISH_INTERVAL_S` | `600` | How often (seconds) the map files are rebuilt. They are also rebuilt at startup. |
| `BACKGROUND_JOBS` | `1` | `1` runs the publish loop and the daily retention job inside the server process; `0` turns them off (tests, or if you run `publish`/`retention` from cron instead). |
| `RETENTION_DAYS` | `730` | Raw minutes and events older than this are reduced to hourly figures and deleted; reports older than this are deleted (2 years per DESIGN §5). |
| `INACTIVE_DELETE_DAYS` | `60` | A device with no authenticated request (upload, site) for this many days is deleted with all its data in the daily job, like a deletion by its owner (DESIGN §2.9, §5); counted in `devices_expired_total`. `0` disables it. |
| `CORS_ORIGIN` | `https://wenigerlaerm.ch,https://stadtlaerm.ch` | The website origins (comma-separated) allowed to read the map files from the browser (`Access-Control-Allow-Origin` echoes the requesting origin if listed, else names the first). Use `http://localhost:8080` or similar for local website work. |
| `TRUST_PROXY` | `0` (compose: `1`) | `1` takes the client IP for the registration limit from the `X-Forwarded-For` header set by Caddy. Only turn on behind a proxy you control, otherwise clients could fake their IP. |
| `REGISTRATIONS_PER_IP_PER_DAY` | `10` | How many new device ids one IP address may create per day. |
| `REQUESTS_PER_DEVICE_PER_HOUR` | `60` | How many authenticated requests one device may make per hour (deleting is never limited). |
| `MAX_BODY_BYTES` | `2000000` | Largest request body accepted, as sent (compressed). A gzip body may expand to 8× this. |
| `MAX_RECORD_AGE_DAYS` | `7` | Minute, event and report records older than this are rejected. |
| `CLOCK_SKEW_S` | `600` | How far in the future a record's start (a report's time) may be (phone clock drift) before it is rejected as "in the future". |
| `HOST`, `PORT` | `0.0.0.0`, `8000` | Where the server listens inside the container. |
| `API_HOSTS` | `api.lessnoise.ch, api.stadtlaerm.ch` | (compose/Caddy) Every public host name the API answers on, comma-separated; Caddy gets one certificate per host. Replaces `API_HOST` (an old `API_HOST=` line in `.env` is ignored). |
| `BACKUP_PASSPHRASE_FILE` | `/root/.stadtlaerm-backup-passphrase` | (backup.sh) File holding the GPG passphrase for backups. |
| `BACKUP_DIR` | `/var/backups/stadtlaerm` | (backup.sh) Where encrypted backups are kept on the host. |
| `BACKUP_REMOTE` | empty | (backup.sh) rclone destination for an off-site copy, e.g. `swissbackup:stadtlaerm`; empty = local only. |
| `BACKUP_KEEP_DAYS` | `30` | (backup.sh) Days backups are kept. |

## Published files: details beyond DESIGN.md §6

These are choices the design left open; the website builds against them (see the fixtures).

- **Which day.** `cells.json` ends with *today* (local time), still running: it is rebuilt
  every 10 minutes, so new data appears within minutes. `generated_at` is local time with
  offset. `default_period` is `last_7_days`.
- **Cells without data today** stay on the map while they have any data in the last 30 days;
  their `day` is `null`; `devices` / `calibrated` of the entry are the distinct devices / all
  calibrated over the 30 days; each block has its own `devices` and `calibrated`.
- **Where a record counts.** A minute or event with a `point` (app 0.6.0, schema v4: the point
  the app already moved by the location noise, DESIGN.md §2 rule 2) counts in its display cell,
  the hectare that contains the point; the server stores that cell with the row at upload, so
  aggregation never reads the point, and the point and σ are never published. One with its own
  `cell` counts there. One with neither counts for the device's *current* site cell: if a
  contributor changes the placement to another hectare, the history of its cell-less records
  moves with it.
- **Day L90 / L10** are the medians over the valid minutes of the minute L90 / L10 (the
  "typical" quiet and loud level of a minute). With several devices, the energy mean.
- **`events_per_h_by_category`** always lists all eight app categories (zeros included).
- **`measured_hours`** and **`loud_events`** of a cell-day are the mean over its devices; in a
  window they are summed over the days with data.
- **`last_7_days` / `last_30_days`**: energy mean of the daily cell levels, median of the daily
  dynamics, mean of the daily rates, over the days with data in the window (ending with the
  file's day); `days_with_data` and `devices` (distinct) say what stands behind them.
- **`cells/{cell}.json`**: `{generated_at, cell, devices, calibrated, loud_event_db, days: [the
  day block + "day"; newest first; only days with data; ≤ 90], profile: {from, to, hours: [24
  rows, hour 0–23]}}`; `devices`/`calibrated` are those of the newest day. Profile hours need
  30 valid minutes in total over the 30 days (the app's week-view rule), otherwise their levels
  and rates are `null`.
- **`days/{date}.json`** exist for each of the last 90 days, also days without any cell; the
  old `nights/` folder is deleted on the first publish.
- **`reports.json`**: the last 30 calendar days up to today; time rounded down to the hour;
  sorted by (hour, lat, lon, category); hidden devices left out.
- **`network`** is the same in every file of one run (it describes now, not the past day);
  a device counts as active if it has a valid minute in the last 7 days. Models without a name
  are counted as `"unbekannt"`.
- All numbers are rounded to 0.1 dB / 0.1 events per hour / 0.1 h.
- **`stats.json`** (DESIGN.md §6.5) is written in every publish run next to `cells.json`:
  `generated_at`, `devices_registered` (hidden included), `devices_with_site`,
  `devices_ever_shared` (≥ 1 minute record ever, also after retention), `devices_active_7d` /
  `devices_active_30d` (a valid minute in the window, every device — so it can exceed
  `network.devices_active_7d`, which counts only devices on the map), `devices_deleted_total`
  (a single counter, table `counters`, schema v2), `devices_expired_total` (devices the server
  deleted after `INACTIVE_DELETE_DAYS` without uploads; a second counter, kept apart),
  `cells_with_data_30d` (= the number of cells in `cells.json`), `days_shared_total`
  (device-days with a valid minute, hourly rows included; was `nights_shared_total` before
  schema v3), `app_versions` (devices active in 30 days per registration-time version;
  non-version strings → `"andere"`), `registrations_by_week` (12 ISO weeks, Europe/Zurich,
  oldest first, devices that still exist). Whole-network counts only, never per cell or device.
