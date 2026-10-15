# Weniger Lärm – shared noise map: data model, contribution API, map

Status: design, 2026-10-08; schema v3 (days instead of nights, a hectare per record, manual
reports) 2026-10-12; schema v4 (location noise: a shifted point per record, app 0.6.2) 2026-10-14. This document is the contract between the three parts that make the
shared map: the **app** (sends), the **server** (stores and aggregates) and the **website**
(shows). Each part is built against this file; when one of them needs a change, this file
changes first.

## 1. What the map is for

A resident-run picture of road noise in Switzerland: where it is loud, where it is *bursty*
(quiet street, loud single motorbikes), how many loud single events per hour, at which hours
of the day, and how that changes over weeks — plus the places where people reported a loud
passing vehicle by hand. No time of day is special: someone may measure all day, only in the
evening or only at night, and every measured hour counts the same. It is meant to be shown to
the authorities and to neighbours — so every number on it must be reproducible from the stored
data, and the stored data must be explainable to the person who contributed it.

## 2. Privacy rules (not optional)

These rules are the design. They go into PRIVACY.md and the website when the feature ships.

1. **No audio, ever.** The app already never stores audio; the upload sends only the
   minute records and event records the app keeps today (levels, percentiles, category
   shares, event start/duration/peak/category). The top-3 raw AudioSet labels per event are
   **not** sent — the category is enough for the map and the labels add fingerprinting
   surface for no gain.
2. **Measurements carry a shifted point, never the true location.** Since app 0.6.2 (schema v4)
   the app moves the place of a shared measurement on the phone *before* anything is sent. From
   the one location fix right after «Starten» (since 0.6.3 while the measurement already runs, and
   only while the app is open; or from pasted coordinates) it computes the true point in the
   national LV95 coordinate system (§3) and adds a random offset: two independent normal draws
   N(0, σ²), one east, one north, where σ is the contributor's setting «Standort verschieben»
   (25, **50 (default)**, 100 or 200 m). Only the shifted point, rounded to whole metres, and σ are
   sent (`point`, §4.2–§4.4). The true coordinates are neither stored nor sent, and the offset is
   not stored either. **The offset is the same every time for the same place:** the two draws
   come from HMAC-SHA256 (a keyed hash) of the true hectare id and σ under a random 32-byte secret
   that is created on the phone and never leaves it (`android/dsp/…/geo/LocationNoise.kt`).
   This is on purpose: with a fresh offset per measurement, anyone holding many measurements made
   at one home could average them and find the home (the error of the mean shrinks as σ/√n);
   with a fixed offset the average ends at the shifted point. Another hectare gets an
   independent offset. «Neu starten» (until 0.6.0 «Neue Kennung») and «Vom Server löschen» make a new secret,
   so afterwards every place gets a new offset. The server stores the point with the record and
   shows the record in its **display cell**, the 100 m hectare that contains the shifted point
   (§3); the 100 m grid of the map is a display grid, not the privacy protection. Points and σ of
   measurements are never published. What remains:
   - someone who knows σ knows that the true point is usually near the shifted one: within σ in
     about 39 % of cases, within 2σ in about 86 %, within 3σ in about 99 % (with the default
     50 m: usually within 100 m). The noise hides the exact address, not the neighbourhood;
   - with `MIN_DEVICES_PER_CELL = 1` a lone contributor's display cell is shown on its own and
     still lies near their home: with the default 50 m it is mostly the home's own hectare or a
     neighbouring one;
   - a home close to a hectare border can produce fixes on both sides, i.e. two independent
     offsets; averaging two offsets only shrinks them by √2. The same holds across a new secret
     («Neu starten»): someone who kept both the old and the new data could combine two offsets;
   - the server (and whoever operates it) holds each device's shifted points, linked by the device
     id; it cannot remove the offset, which needs the phone's secret;
   - a contributor who measures in several places appears in several display cells, never linked
     to each other (no device id is published) — but a reader who knows that one person measures
     in a sparse area could follow that person's cells day by day.
   Before the first upload the app's one question «Auf die Lärmkarte?» (rule 3, since 0.6.3) says
   that a point moved by about σ is sent instead of the location; its full explanation, one tap away
   («Mehr dazu» in the dialog, «Was gesendet wird ▸» in «Teilen»), says that the point is moved the
   same way at the same place, that the true place is usually within 2σ and that a lone
   contributor's field lies near them (`ui/ShareTexts.kt`); datenschutz.html says the same and that
   a lone contributor's fields can be followed day by day. (Until 0.6.2 the whole explanation stood
   before the switch.)
   Apps up to 0.5.1 send no point and no record cell; their records count for the site's hectare,
   which the contributor picked and the app snapped to the 100 m grid — for them the hectare is
   still the protection, as before. Records with a `cell` and no point (schema v3; apps 0.6.0 and
   0.6.1, which snapped the place to its hectare) are stored and shown in that hectare as before.
3. **Off by default, one explicit opt-in.** Upload is a setting («Messwerte teilen»), off until
   the contributor says yes. Since app 0.6.3 the opt-in is **one dialog**, «Auf die Lärmkarte?»,
   shown once at the first «Starten» of the Online-Version (or when the switch «Auf die Karte senden»
   in «Teilen» is turned on before that). It names, in three lines, what is sent (the values per
   minute and the loud moments, with a point moved by about σ instead of the location), what is
   never sent (audio, name, the exact location — except with «Laut! Melden»), where it goes (our
   server in Switzerland) and how to delete (switch off and delete in «Teilen»; the server deletes
   everything after 60 days without new values); the full explanation of «Teilen» is one tap away
   («Mehr dazu»). «Ja, auf die Karte» switches sharing on; «Nur auf dem Telefon» keeps it off;
   dismissing the dialog starts nothing. The question is not asked again; the switch in «Teilen»
   changes the answer later. Until 0.6.2 the opt-in was the switch in «Teilen» behind the full
   explanation and a «Weiter» button. Switching it off stops uploads at once. Switching it on during
   a running measurement shares that whole measurement (its records get the point once the fix is
   there); a measurement that ended unshared is never sent.
4. **Delete means delete.** «Meine Daten auf dem Server löschen» removes every row for
   the device — site, minutes, events, hourly rows and reports — synchronously, and the server
   answers only after the rows are gone. The published files are rebuilt on the next cycle
   without them.
5. **No account, no identity.** A device gets a random id and a secret token at opt-in.
   The id is never shown on the public map. «Neue Kennung» in settings deletes the server
   data and starts a fresh id.
6. **Measurements are public only as aggregates.** The website never fetches per-device rows.
   The server publishes per-cell, per-day aggregates (section 6). The minimum number of devices
   per shown cell is a server parameter `MIN_DEVICES_PER_CELL`, **default 1**: a lone
   contributor who opted in, whose place was moved by about σ before it was sent (rule 2), is
   showing their own street's noise, which is the point of the project. Raise it later if the network grows
   and the map becomes dense enough that it would not go blank. Manual reports are the one
   exception and have their own rule (10).
7. **Swiss hosting.** The server runs in Switzerland (see section 8), under two host names
   for the same machine (`api.lessnoise.ch`, and `api.stadtlaerm.ch` for app versions up to
   0.5.1 and while the website moves). The website's main page still loads nothing from third
   parties; the map page is the one exception and says so (swisstopo tiles and our own API
   host only; see section 7). Since app 0.6.3 the Online-Version shows that map page in its tab
   «Karte» (a WebView, loaded when the tab is opened, kept paused while another tab is shown): it then loads the page from wenigerlaerm.ch
   (GitHub Pages), the tiles from swisstopo and the published files from our API host, exactly
   like a browser, and nothing from any other host (the app refuses other hosts). It sends no
   measurement and no location; the centre of the view (the shifted point of the newest shared
   measurement) is in the URL fragment, which never leaves the phone — but the tiles requested for
   the area on screen tell swisstopo which area is being looked at, as for any visitor of the map.
8. **Minimisation on publish.** Event times are published only as counts per day and per
   clock hour of the 24-hour profile, never as a list of events. The 24-hour profile of a cell
   (§6.2) is a mean over 30 days: it shows at which hours of the day someone measured there,
   not on which day. Report times are rounded down to the full hour (rule 10). Device models
   are published only as counts per model over the whole network («Beteiligte Geräte»), never
   per cell. The usage figures of `stats.json` (§6.5) are likewise whole-network counts only.
9. **Data of a silent device does not stay.** When no authenticated request has arrived
   from a device for 60 days (`INACTIVE_DELETE_DAYS`), the server deletes every row of it,
   reports included, exactly as in rule 4, and the map is rebuilt without it. «Without
   uploads» means: the device's `last_seen_at` (set on every authenticated request;
   `created_at` if there never was one) is older than the cutoff. This covers the people who
   cannot or do not press «Löschen»: who switched to the Offline-Version (no internet
   permission, so it cannot delete), uninstalled the app, lost the phone, or switched sharing
   off and forgot. It strengthens the rules above: nothing is kept about a person who has
   stopped contributing, and no data outlives the contribution by more than 60 days. What
   remains is +1 on the anonymous counter `devices_expired` (§6.5). The app then gets `401`
   and asks for «Neue Kennung», which starts over with a fresh id.
10. **Manual reports are published at the reported point, an hour, and nothing else.** A
    report (§4.7) is the contributor's explicit statement «here, now, a loud motorbike / car /
    lorry / poser passed». Its place *is* its content, so the server stores the point
    (rounded to 5 decimals of a degree, ≈ 1 m) and publishes it in `reports.json` (§6.4) as it
    was reported — not snapped to a hectare. What is published per report is exactly: the
    point, the category, and the time **rounded down to the full hour**. Never published: the
    device id, the exact time, the location accuracy, any link between two reports. Within an
    hour the reports are sorted by place, not by time or device, so the order reveals no
    sequence (§6.4). Only the last 30 days are published; reports of hidden devices are left
    out; `MIN_DEVICES_PER_CELL` does not apply (a report is a statement, not a measurement of
    someone's home). The raw report is deleted with the device (rules 4, 9) and at the latest
    after 2 years (§5). Risk that remains, and that the app must say on its report screen:
    someone who reports often from the same spot (their balcony, their front door) makes that
    spot visible on the map as a cluster of points, at hour resolution. (Since app 0.6.3 the
    confirmation of every report says «Wer oft vom gleichen Ort meldet, macht ihn auf der Karte
    sichtbar.», because the first-report consent is skipped once sharing is on.)

## 3. Cell ids

A cell id is the hectare in LV95: `h` + E/100 + `_` + N/100, both integers,
e.g. `h26824_12473` for the hectare whose south-west corner is E 2 682 400, N 1 247 300.

WGS84 ↔ LV95 uses swisstopo's published approximate formulas (accuracy ≈ 1 m, far below the
100 m cell). The same pure function exists in Kotlin (`dsp` module, `geo/Lv95.kt`) and in
JavaScript (`docs/map/lv95.js`), each unit-tested against the reference points below and
against each other on a fixture of Zürich points. No agent needs to look anything up:

| Point | LV95 E | LV95 N | φ (ETRF93) | λ (ETRF93) | tolerance |
|---|---|---|---|---|---|
| swisstopo worked example | 2 700 000.00 | 1 100 000.00 | 46° 02′ 38.87″ | 8° 43′ 49.79″ | 1 m |
| AGNES ZIMM (Zimmerwald) | 2 602 030.740 | 1 191 775.030 | 46° 52′ 37.540569″ | 7° 27′ 54.983511″ | 1 m |
| AGNES ETH2 (ETH Zürich) | 2 680 910.112 | 1 251 259.201 | 47° 24′ 25.842486″ | 8° 30′ 38.194637″ | 1 m |
| AGNES LOMO (Locarno Monti) | 2 704 160.863 | 1 114 349.376 | 46° 10′ 21.225556″ | 8° 47′ 14.732003″ | 1 m |
| AGNES GENE (Genève) | 2 498 930.196 | 1 122 714.152 | 46° 14′ 53.692140″ | 6° 07′ 41.065513″ | 3 m |

(AGNES is swisstopo's permanent GNSS station network; the published station coordinates are
the reference. The approximate formulas were checked against all five on 2026-10-08: worst
case 2.2 m at Genève, the rest under 0.5 m.)

**Display cell (schema v4).** A record that carries a shifted point (§4.3) is shown in the
hectare that contains that point: `h` + ⌊E/100⌋ + `_` + ⌊N/100⌋, the same arithmetic as for a
WGS84 point. The hectare is only the grid the map draws; the protection of the place is the
noise added on the phone (§2 rule 2).

Cell → polygon for display: the four corners (E, N), (E+100, N), (E+100, N+100), (E, N+100)
converted to WGS84; drawn as a quadrilateral (it is not quite a square in Web Mercator and
that is fine).


## 4. What the app sends

All timestamps are ISO-8601 with zone offset, as in the CSV export. Levels in dB(A), one
decimal. JSON, optionally gzip-compressed request bodies (`Content-Encoding: gzip`),
idempotent on `(device_id, start)` (reports: `(device_id, time)`). Base URL: new app versions
use `https://api.lessnoise.ch`; `https://api.stadtlaerm.ch` answers identically (§8).

### 4.1 Device (`POST /v1/devices`)

Request: `{ "app_version": "0.4.0", "app_build": 40 }` (no device info yet).
Response: `{ "device_id": "<uuid4>", "token": "<32 random bytes, base64url>" }`.
The token is sent as `Authorization: Bearer <token>` on every later call for that device.

### 4.2 Site (`PUT /v1/devices/{id}/site`)

The sensor's placement. Sent at opt-in and whenever the contributor changes it. Exactly one of
`cell` (apps up to 0.5.1: the hectare the contributor picked) and `point` (app 0.6.2, schema v4:
the shifted point of the newest shared measurement; each measurement has its own point, carried
by its minutes and events, §4.3/§4.4). The server stores the site's display cell (§3) in `cell`.
The other fields are optional details the app remembers.

```json
{
  "point": { "e": 2682437, "n": 1247391, "sigma_m": 50 },   // or (apps ≤ 0.5.1) "cell": "h26824_12473"
  "placement": "open_window | balcony | behind_glass | other",
  "floor": 3,                      // 0 = ground floor; null = not given
  "street_facing": true,           // mic faces the street (vs courtyard)
  "device_model": "Pixel 8",       // android.os.Build.MODEL
  "audio_source": "UNPROCESSED | VOICE_RECOGNITION",
  "calibrated": true,
  "calibration_offset_db": 121.9,
  "note": "optional free text ≤ 200 chars, e.g. '2. OG, Seestrasse-Seite'"
}
```

`note` is for the contributor's own memory and the project's eyes; it is never published.

### 4.3 Minutes (`POST /v1/devices/{id}/minutes`)

Array of up to 1 440 minute records, each the app's `MinuteEntity` minus the `orig_*` and
`recalibrated_*` columns (the server receives the current, i.e. re-evaluated, levels and the
calibration id they were measured with — that is enough to reason about calibration):

```json
{
  "start": "2026-10-07T23:14:00+02:00",
  "point": { "e": 2682437, "n": 1247391, "sigma_m": 50 },   // optional (schema v4), see below
  "duration_s": 60.0,
  "valid_s": 59.5,
  "coverage": 0.992,
  "laeq_db": 42.3, "lafmax_db": 61.0, "lafmin_db": 33.1,
  "l1_db": 55.0, "l10_db": 47.2, "l50_db": 41.0, "l90_db": 36.4,
  "event_count": 2,
  "dominant_category": "road_traffic",
  "category_shares": { "road_traffic": 0.41, "loud_vehicle": 0.05, "unclassified": 0.54 },
  "calibration_id": "a1b2…", "calibration_offset_db": 121.9, "calibrated": true,
  "audio_source": "UNPROCESSED",
  "clock_corrections": 0
}
```

**`point`** (optional, from schema v4): where the minute was measured, as the app sends it
since 0.6.2 — the **shifted** LV95 point of its measurement (§2 rule 2): `e` and `n` are JSON
integers in metres inside the LV95 box of Switzerland used for cells (E 2 480 000–2 839 999,
N 1 070 000–1 299 999, inclusive), `sigma_m` is the σ of the noise, one of 25, 50, 100, 200.
The server stores `point_e`, `point_n`, `sigma_m` with the row and sets the row's cell to the
display cell (§3), the hectare containing the point; aggregation and publication use only that
cell. The point and σ are never published.

**`cell`** (optional, from schema v3): a hectare id of §3 (`^h\d{5}_\d{5}$`) inside the same
box. Sent by apps 0.6.0 and 0.6.1 (before schema v4); still accepted and stored as before.

A record carries at most one of the two: both → rejected (`{field: "point", reason: "send either
cell or point, not both"}`). With neither (apps up to 0.5.1) the record counts for the device's
**current** site cell when the map is built. A record with a malformed or out-of-range cell or
point, or a σ that is not one of the four, is rejected with the field and the reason; the other
records of the batch are stored. A device without a site may send records that all carry a point
or a cell; records with neither are stored but not shown.

Response: `{ "accepted": n, "duplicates": m, "rejected": [ {index, start, reasons}, … ] }`.
A record whose `start` already exists for the device replaces the stored one, its point and cell
included (re-evaluation after a calibration sends the day again).

The app (0.6.2 and later; 0.6.0 and 0.6.1 sent the `cell` of the measurement) sends a `point` with every minute and event of a shared measurement,
determined on the phone at (since 0.6.3: right after) the measurement's «Start» (the same point for all records of one
measurement); minutes of a measurement without a point are not sent at all. It never sends the
true hectare.

### 4.4 Events (`POST /v1/devices/{id}/events`)

Array of up to 2 000 events:

```json
{
  "start": "2026-10-07T23:14:12.375+02:00",
  "point": { "e": 2682437, "n": 1247391, "sigma_m": 50 },   // optional (schema v4), as for minutes
  "duration_s": 2.75,
  "lafmax_db": 71.4,
  "sel_db": 74.0,
  "background_db": 38.1,
  "threshold_db": 6.5,
  "min_level_db": 30.0,
  "category": "loud_vehicle",
  "category_score": 0.31,
  "calibration_id": "a1b2…", "calibrated": true
}
```

`threshold_db` is the start threshold the app's detector had in force: since app v0.4.0
(detector v2) the excess over the local floor (L90 of the last 30 s; default 6.5 dB), before that
the threshold over the 5-min background (default 10 dB). Events the app flags as wind on the
microphone (detector v2) are not sent, just as the app leaves them out of its own counts; the
minutes' `event_count` (§4.3) likewise counts events without wind. `point` (since app 0.6.2): as for
minutes, the shifted point of the event's measurement; events of a measurement without a point are
not sent.

`point` and `cell` follow the rules of §4.3. Idempotent on `(device_id, start)`; same replace rule as
minutes.

### 4.5 Delete (`DELETE /v1/devices/{id}`)

Deletes the device, its site, minutes, events, hourly rows and reports in one transaction.
Response `204`. Never rate limited.

### 4.6 Upload cadence in the app

While «Messwerte teilen» is on, since app 0.6.3: while a shared measurement runs, about 2 minutes
after it got its point and then every 10 minutes; shortly (15 s) after it stops; after a report;
and at the end of every full hour as the fallback. Each upload first sends waiting reports (§4.7),
then all minutes and events of shared measurements newer than the last acknowledged `start`, in
batches, over any connection — mobile data is allowed by default since 0.6.3 (less than 0.1 MB a
day); the setting «Nur über WLAN» (until 0.6.2 on by default) restricts it to Wi-Fi/unmetered
networks. One upload is about 2–3 requests; a two-hour measurement makes at most about 16 requests in
its busiest hour, far below the limit of 60 per device and hour (§8; app test `UploadCadenceTest`).
A 429 or 5xx answer is retried with backoff from 10 minutes; nothing is lost because the local
database is the source of truth. Since 0.6.3 a measurement starts without a point and gets it once
the fix is there; the app then moves its acknowledged markers back to the measurement's start so
that the minutes before the fix are sent too (`Uploader.pointAttached`). The tab «Teilen» and the
screen «Messen» show one status line («Wird geteilt – erste Werte in wenigen Minuten», «Auf der
Karte · zuletzt gesendet …», «Kein Internet – wird nachgeholt», …); «Teilen» › «Weitere Angaben»
shows the number of minutes still to send. Reports are sent with the next upload (the app agent
documents the details in android/README.md).

### 4.7 Reports (`POST /v1/devices/{id}/reports`)

A manual report: the contributor reports in the app that a loud vehicle is passing here and now. Bearer
auth and rate limit as for the other calls; body = JSON array of at most **50** reports:

```json
{
  "time": "2026-10-08T14:23:05+02:00",   // ISO-8601 with offset
  "lat": 47.355312,                      // WGS84 degrees
  "lon": 8.535549,
  "accuracy_m": 12.0,                    // the location fix's accuracy, ≤ 200 m
  "category": "motorrad"                 // motorrad | auto | lastwagen | poser | andere
}
```

Validation, per report (a bad one is skipped and listed in `rejected` as
`{index, time, reasons: [{field, reason}]}`, the others are stored):

- `time` with a zone offset, not more than `MAX_RECORD_AGE_DAYS` (7) days old and not more
  than `CLOCK_SKEW_S` (600 s) in the future — the rule of minutes and events;
- the point inside Switzerland: first the WGS84 box of the map (lat 45.8–47.85,
  lon 5.9–10.55), then its LV95 hectare inside the box of §4.3 (reported as field `lat`);
  border areas of neighbouring countries inside that box are accepted, as for site cells;
- `0 ≤ accuracy_m ≤ 200`; `category` one of the five; no other fields.

Stored: `lat`/`lon` rounded to 5 decimals (≈ 1.1 m north–south, 0.75 m east–west),
`accuracy_m` to 0.1 m (never published), `time` as sent and as UTC milliseconds.
Idempotent on `(device_id, time)`: the same time again counts as a duplicate and replaces the
stored report. Response like minutes: `{accepted, duplicates, rejected}`.

### 4.7 Reports (`POST /v1/devices/{id}/reports`, app 0.6.0)

«Laut! Melden»: a person reports a loud moment while out and about (no measurement running). Array
of up to 50 reports, bearer auth as above:

```json
{
  "time": "2026-10-12T16:05:03+02:00",
  "lat": 47.37691,
  "lon": 8.54169,
  "accuracy_m": 12.3,
  "category": "motorrad | auto | lastwagen | poser | andere"
}
```

`time` is the moment of the tap, to the second, with the phone's offset; the server rounds it for
publication. `lat`/`lon` are WGS84 with 5 decimals, from one precise fix with `accuracy_m` ≤ 50.
This is the only request with exact coordinates. The app keeps them only until the server has
answered for the report, then deletes them (it keeps category, time and cell). Reports are not
shifted: their place is their content (§2 rule 10). Response like
minutes: `{ "accepted": n, "duplicates": m, "rejected": [...] }`. The app treats a `404`/`405`
as "server without reports yet" and retries later; any other `4xx` drops that batch.

## 5. Server storage (SQLite)

```
devices(id TEXT PK, token_hash TEXT, app_version TEXT, app_build INT, created_at, last_seen_at, hidden INT DEFAULT 0)
sites(device_id PK→devices, cell TEXT, placement TEXT, floor INT, street_facing INT,
      device_model TEXT, audio_source TEXT, calibrated INT, calibration_offset_db REAL,
      note TEXT, updated_at,
      point_e INT NULL, point_n INT NULL, sigma_m INT NULL)   -- v4; cell = the point's display cell
minutes(device_id, start_utc INT, start_iso TEXT, … all fields of 4.3 …, received_at,
        cell TEXT NULL,                    -- v3; NULL = the device's site cell
        point_e INT NULL, point_n INT NULL, sigma_m INT NULL,  -- v4; then cell = display cell
        PRIMARY KEY(device_id, start_utc))
events(device_id, start_utc INT, start_iso TEXT, … all fields of 4.4 …, received_at,
       cell TEXT NULL,                     -- v3; NULL = the device's site cell
       point_e INT NULL, point_n INT NULL, sigma_m INT NULL,   -- v4; then cell = display cell
       PRIMARY KEY(device_id, start_utc))
hourly(device_id, hour_utc INT, cell TEXT DEFAULT '', valid_s, valid_minutes, laeq_db, l90_db,
       l10_db, dynamics_db, events TEXT, loudest_db, calibrated,
       PRIMARY KEY(device_id, hour_utc, cell))   -- v3: cell in the key, '' = the site cell
reports(device_id, time_utc INT, time_iso TEXT, lat REAL, lon REAL, accuracy_m REAL,
        category TEXT, received_at, PRIMARY KEY(device_id, time_utc))   -- v3
counters(name TEXT PK, value INT)          -- v2; 'devices_deleted', 'devices_expired' (§6.5)
```

`token_hash` is SHA-256 of the token; the token itself is never stored. `hidden` lets the
operator keep a misbehaving device out of the aggregates and out of `reports.json` without
deleting the data (it is still deletable by its owner). Indexes on `minutes(start_utc)`,
`events(start_utc)`, `reports(time_utc)`, `sites(cell)`.

**Migration v2 → v3** (runs at startup, one transaction): `ALTER TABLE minutes/events ADD
COLUMN cell` (existing rows: NULL, i.e. the site cell, exactly what they meant before);
`hourly` is rebuilt with `cell` in its primary key (a `WITHOUT ROWID` key cannot be altered),
existing rows copied with `cell = ''`; new table `reports`. No row is lost or changed.

**Migration v3 → v4** (runs at startup, one transaction): `ALTER TABLE minutes/events/sites ADD
COLUMN point_e, point_n, sigma_m` (INTEGER). Existing rows get NULL and keep their cell, i.e. they
are shown exactly where they were before. No row is lost or changed. Points are never read by the
aggregation; the retention job's hourly rows keep only the cell, so a point is gone with its raw
row after 2 years at the latest.

Retention: raw minutes and events are kept for 2 years (`RETENTION_DAYS`), then reduced to
per-device hourly rows (one per device, UTC hour and hectare; the arithmetic of
`aggregate.summarize_hour`) by the daily job, and deleted. Raw reports older than 2 years are
deleted without any reduction.

Expiry (§2.9), in the same daily job and in `python -m stadtlaerm_server retention`: every
device whose `COALESCE(last_seen_at, created_at)` is more than `INACTIVE_DELETE_DAYS` (default
60; 0 disables) days old is deleted with all its rows (minutes, events, hourly, reports,
sites, devices), one transaction per device, hidden devices included. The transaction
re-reads the time under a write lock, so a device that uploads while the job runs is kept.
Each deletion adds 1 to the counter `devices_expired`, kept apart from `devices_deleted`
(deletions the device asked for). The job logs one line with the count, never an id. If any
device expired, the map is republished at once. Because the 2-year retention only applies to
devices that keep sending, it now matters only for long-running contributors.

`last_seen_at` is updated by every authenticated request (site, minutes, events, reports),
including one that carries no records (an empty list). This is accepted: only the holder of
the device's token can make such a request, so a device can only keep *its own* data alive,
and the app never makes one — it calls the server only when the site changed or there are
records to send (`android/…/upload/Uploader.kt`). A rate-limited request (429) is refused
before `last_seen_at` is touched.

Backups: nightly SQLite `.backup` to encrypted object storage in Switzerland, 30 days.

## 6. Published aggregates (what the website reads)

Rebuilt every 10 minutes and written as static JSON files served with
`Cache-Control: max-age=300` under `/v1/map/` (on both API hosts). The website only ever reads
these. Times are Europe/Zurich local time with offset.

**Days, not nights.** The unit is the **calendar day in local time** (Europe/Zurich): 00:00 to
00:00, so 24 h, or 23 h / 25 h on the days the clocks change, all hours. A record belongs to
the day its start falls in, and to its cell (its display cell or own cell, else the site's,
§3, §4.3); a measurement
over midnight counts for both days, each with its own minutes. The newest day is **today**
(still running; the files are rebuilt every 10 minutes), so a contributor sees today's data on
the map within minutes.

**Per device and day** (the app's rules, with the 22–6 h restriction removed):

- a minute is *valid* if its coverage is ≥ 0.5 and it has an LAeq;
- `laeq_db` = energy mean of the valid minutes' LAeq, weighted by their valid seconds;
- `l90_db` / `l10_db` = median over the valid minutes of the minute L90 / L10;
- `dynamics_db` = median over the valid minutes of (L10 − L90);
- `measured_hours` = valid seconds / 3600;
- `events_per_h` (and per category) = events of the day in the cell / measured hours;
- `loud_events` = events with `lafmax_db ≥ LOUD_EVENT_DB` (server setting, default 75 dB(A));
  `loud_events_per_h` = that / measured hours; `loudest_event_db` = the largest LAFmax.

**Several devices in one cell and day:** levels are the energy mean of the device values,
rates and counts the arithmetic mean over the devices (so two phones side by side do not
double the hours or the motorbikes), dynamics the median of the device medians, the loudest
event the maximum. A cell-day needs at least `MIN_DEVICES_PER_CELL` devices.

**Windows** (`last_7_days`, `last_30_days`, ending with the file's day): each day with data
counts once — energy mean of the daily levels, median of the daily dynamics, mean of the daily
rates, sums of `measured_hours` and `loud_events`, the maximum of the loudest events, distinct
devices over the window. A day with one measured hour weighs as much as one with 24 (as nights
did); `measured_hours` and `days_with_data` say how much stands behind a value.

The arithmetic is one pure module (`server/stadtlaerm_server/aggregate.py`) with tests for a
measurement over midnight and both DST days.

### 6.1 `cells.json` — the map

Every block (a day, a window) has the same keys, so the map can colour any metric for any
period:

```json
{
  "generated_at": "2026-10-08T18:40:00+02:00",
  "day": "2026-10-08",                      // today: the last day of both windows
  "default_period": "last_7_days",          // what the map shows first
  "periods": {
    "last_7_days":  { "from": "2026-10-02", "to": "2026-10-08", "days": 7 },
    "last_30_days": { "from": "2026-09-09", "to": "2026-10-08", "days": 30 }
  },
  "min_devices_per_cell": 1,
  "loud_event_db": 75.0,                    // LOUD_EVENT_DB
  "cells": [
    {
      "cell": "h26828_12455",
      "devices": 2,                         // distinct devices in the 30 days
      "calibrated": true,                   // all of them calibrated
      "day": {                              // today, or null if no data today
        "laeq_db": 58.3, "l90_db": 55.8, "l10_db": 60.6, "dynamics_db": 5.5,
        "events_per_h": 7.1,
        "events_per_h_by_category": { "loud_vehicle": 1.4, "road_traffic": 5.2, "rail_tram": 0.0,
          "aircraft": 0.0, "construction": 0.0, "voices": 0.4, "music": 0.0, "unclassified": 0.0 },
        "loud_events_per_h": 1.2, "loud_events": 20.5, "loudest_event_db": 88.3,
        "measured_hours": 17.2, "days_with_data": 1, "devices": 2, "calibrated": true
      },
      "last_7_days":  { "laeq_db": 59.1, "…": "same keys", "measured_hours": 160.0, "days_with_data": 7 },
      "last_30_days": { "laeq_db": 58.9, "…": "same keys", "measured_hours": 709.4, "days_with_data": 30 }
    }
  ],
  "network": { "devices_active_7d": 30, "device_models": { "Pixel 8": 13, "…": 1 } }
}
```

A cell is listed while it has data on any day of the 30-day window; a window without data
has every value `null`, `measured_hours` 0 and `days_with_data` 0.
`events_per_h_by_category` always lists the eight app categories. Values are rounded to 0.1.

### 6.2 `cells/{cell}.json` — one cell's days and its 24-hour profile

```json
{
  "generated_at": "2026-10-08T18:40:00+02:00",
  "cell": "h26828_12455", "devices": 2, "calibrated": true, "loud_event_db": 75.0,
  "days": [ { "day": "2026-10-08", "…": "the block of §6.1" }, "… newest first, only days with data, ≤ 90" ],
  "profile": {
    "from": "2026-09-09", "to": "2026-10-08",
    "hours": [
      { "hour": 7, "laeq_db": 59.6, "l90_db": 55.1, "l10_db": 60.6, "events_per_h": 9.5,
        "events_per_h_by_category": { "loud_vehicle": 2.0, "…": 0.0 },
        "loud_events_per_h": 1.7, "measured_hours": 54.0, "devices": 2 },
      "… 24 rows, hour 0–23 local clock time"
    ]
  }
}
```

**24-hour profile:** for each local clock hour 0–23, over the 30 days ending today: per device
the energy mean of its valid minutes in that hour (weighted by valid seconds, so across days),
L90/L10 the median of the minute values, events and loud events per hour of valid measurement;
then across devices each device once (energy mean of levels, mean of rates). An hour with
fewer than **30 valid minutes in total** (all devices, all days), or fewer devices than
`MIN_DEVICES_PER_CELL`, has `null` levels and rates; its `measured_hours` and `devices` are
still given. On the autumn DST day the hour «02» occurs twice and both count for hour 2; in
spring hour 2 has no data that day.

### 6.3 `days/{date}.json`

`cells.json` as it was at the end of that day (windows ending that day), for each of the last
90 days, also days without any cell, so the map can step back day by day. `nights/` of
schema v2 is removed on the first publish after the update.

### 6.4 `reports.json` — manual reports

```json
{
  "generated_at": "2026-10-08T18:40:00+02:00",
  "from": "2026-09-09", "to": "2026-10-08",
  "categories": ["motorrad", "auto", "lastwagen", "poser", "andere"],
  "reports": [
    { "time": "2026-10-08T14:00:00+02:00", "lat": 47.35531, "lon": 8.53555, "category": "motorrad" }
  ]
}
```

The reports of the last 30 calendar days up to today, of devices that are not hidden.
`time` is the report time **rounded down to the full hour**, local ISO with offset (Swiss
offsets are whole hours, so the UTC hour and the local hour coincide; in the autumn DST night
the two «02:00» hours keep their offsets). `lat`/`lon` as stored (5 decimals). No device id,
no accuracy, no exact time. Sorted by (hour, lat, lon, category): deterministic and testable,
and within an hour the order says nothing about the order of the reports or who made them —
chosen over a random shuffle, which could not be tested and would change the file on every run.

### 6.5 `stats.json` — usage figures of the whole project

Written in the same publish run, served the same way (`/v1/map/stats.json`, same cache and
CORS headers), and printed by `python -m stadtlaerm_server stats`. Counts only:

```json
{
  "generated_at": "2026-10-25T06:40:00+01:00",
  "devices_registered": 6, "devices_with_site": 4, "devices_ever_shared": 5,
  "devices_active_7d": 3, "devices_active_30d": 4, "devices_deleted_total": 0,
  "devices_expired_total": 0, "cells_with_data_30d": 2, "days_shared_total": 14,
  "app_versions": { "0.5.0": 2, "andere": 1, "unbekannt": 1 },
  "registrations_by_week": [ { "week": "2026-W32", "devices": 1 }, "… 12 weeks, oldest first …" ]
}
```

`days_shared_total` (schema v3, was `nights_shared_total`): device-days (local calendar days)
with at least one valid minute, including days already reduced to hourly rows.
`cells_with_data_30d`: the cells of `cells.json`, i.e. with data in the 30 days up to today.

The exact definitions are in `server/stadtlaerm_server/stats.py`. In short: active = a valid
minute in the window (the rule of `network.devices_active_7d`, but over every device, hidden
and site-less ones included); `devices_ever_shared` counts minutes already reduced to hourly
rows by retention; `app_versions` is the version reported at registration (the server is not
told about updates), per device active in the last 30 days, anything that does not look like a
version number as `"andere"`; `registrations_by_week` counts only devices that still exist.
`devices_deleted_total` and `devices_expired_total` are the only numbers kept for this
purpose alone: one integer each in `counters`, incremented in the same transaction as a
device's deletion — on the owner's request, or by the server after 60 days without uploads
(§2.9).

**Privacy.** The file adds no new data collection and no new kind of data: every figure is a
count over the whole network, computed from the rows the map already uses, with no place
(no cell, no breakdown by area), no device id, no time finer than an ISO week (and only for
registrations), and no IP address — the server has none to give (no access log in Caddy or
uvicorn; rate limits keep IPs in memory only). A deletion still removes every row of the
device (§2.4, §2.9); what remains is +1 on an anonymous counter, which cannot be traced back to a
device. So §2 is not weakened. No breakdown finer than the fields above may be added without
changing this section first. The app's Offline-Version has no internet permission and
contributes nothing to this file; only devices that switched on «Messwerte teilen» in the
Online-Version (0.5.x: «Karten-Version») are counted.

## 7. The map page (`docs/karte.html`)

- Own CSP: `default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data:
  https://wmts.geo.admin.ch; connect-src https://api.lessnoise.ch https://api.stadtlaerm.ch`.
  The page reads `https://api.lessnoise.ch/v1/map/` (`data-api`); if that host cannot be
  reached at all (no DNS record yet, no certificate) before any answer, it switches once to
  `https://api.stadtlaerm.ch/v1/map/` (`data-api-fallback`) for the rest of the visit. The old
  host stays in the CSP until api.lessnoise.ch has served the map for a while; then both the
  fallback and the CSP entry go. The page says, in one sentence above the map, that it loads
  map tiles from swisstopo and the measurements and reports from the project's server and
  nothing else. `datenschutz.html` says the same.
- **Leaflet** (vendored into `docs/vendor/`, no CDN), raster tiles
  `https://wmts.geo.admin.ch/1.0.0/ch.swisstopo.pixelkarte-grau/default/current/3857/{z}/{x}/{y}.jpeg`
  with credit «© swisstopo», bounded to Switzerland (swisstopo answers 400 outside).
- Cells drawn as filled quadrilaterals (section 3), coloured by the chosen metric over the
  chosen period: **Mittlerer Pegel** (LAeq, default), **Hintergrund** (L90), **Dynamik**,
  **Ereignisse/h**, **Töff & Poser/h**, **Sehr laut/h** (loud events). Period selector
  «Letzte 7 Tage» (default, from `default_period`) / «Letzte 30 Tage»; ‹ › move the period's
  last day back and forth through the last 90 days (`days/{date}.json`).
- The level scale is **neutral** (one blue hue, light → dark = quiet → loud, 5-dB classes
  45 · 50 · 55 · 60 · 65): the map shows measurements, not a legal judgement. No legal values
  in the legend.
- The Swiss Noise Abatement Ordinance (Lärmschutz-Verordnung, LSV, Anhang 3, road traffic;
  values are *Beurteilungspegel Lr* in dB(A)) appears only in the card's 24-hour profile chart,
  as two clock-time bands for sensitivity level ES II (residential zones), each band spanning
  Planungswert to Immissionsgrenzwert: **6–22 Uhr** 55 / 60 and **22–6 Uhr** 45 / 50. The
  card says that the ordinance's values are rated levels with corrections, assessed over the
  whole year, while the chart shows measured hourly means — orientation, not a legal finding.
  The table, so that nobody has to look it up (night values: as documented in this file since
  v0.5.0; day values: LSV Anhang 3 Ziff. 2 — OPEN: to be checked against the ordinance text by
  the maintainer, see WORKLOG 2026-10-12):

  | Empfindlichkeitsstufe | Planungswert Tag / Nacht | Immissionsgrenzwert Tag / Nacht | Alarmwert Tag / Nacht |
  |---|---|---|---|
  | ES I (recreation zones) | 50 / 40 | 55 / 45 | 65 / 60 |
  | ES II (residential) | 55 / 45 | 60 / 50 | 70 / 65 |
  | ES III (mixed residential/commercial) | 60 / 50 | 65 / 55 | 70 / 65 |
  | ES IV (industrial) | 65 / 55 | 70 / 60 | 75 / 70 |

  (Tag = 6–22 Uhr, Nacht = 22–6 Uhr.)
- Layer **«Meldungen»** (on by default, checkbox «Meldungen zeigen»): the reports of the chosen
  period from `reports.json` as small round markers with a letter per category (M Motorrad,
  P Poser, L Lastwagen, A Auto, + Andere; the colour only helps), tooltip «category · day,
  hour–hour+1 Uhr». The legend lists the categories and the number of reports in the period.
- Tap/hover card in the style of earth's pixel inspector: cell, devices, the chosen period's
  figures each with *when* they were measured, the 24-hour profile chart (L90–L10 band, LAeq
  step line, Töff & Poser per hour as amber bars, hours without enough data as grey gaps, the
  LSV bands), the figures of the file's day, and the other period. «Unkalibriert» badge when
  any device in the cell is uncalibrated.
- The page never uses the word «Nacht»: nothing about night is special any more.
- Everything is static files plus JSON fetches (cells.json, reports.json, a cell's file on
  tap, a day's file on ‹ ›); works at 360 px; dark mode via the site's CSS variables.
- **Keeping up to date** (0.6.3): while the page is visible (`document.visibilityState ===
  'visible'`) it fetches cells.json again every 2 minutes through the same `getJson` (same host,
  `credentials: "omit"`, no referrer, the browser's HTTP cache honoured — the files are served with
  `Cache-Control: max-age=300`, so a change shows up at most about 5 minutes after it was
  published, plus the 10-minute publishing cycle). Only when `generated_at` changed does it fetch
  reports.json, redraw cells and reports, and re-fetch the open card's cell file — the view, metric,
  period, the day shown (an older day stays shown; › then reaches the new one) and the open card
  stay. Hidden page: no timer; visible again: one check at once. `window.KarteCheck()` runs the same
  check (one at a time); the app's tab «Karte» calls it when it shows its kept page again. The
  requests carry nothing about the viewer (no view, no chosen field, no location).
  Hash-stamped asset URLs (`scripts/stamp_assets.py`) so a phone never runs yesterday's script
  against today's JSON.
- **View fragment** (0.6.3): `karte.html#lat=<WGS84 latitude>&lon=<longitude>&z=<zoom>`, e.g.
  `karte.html#lat=47.37690&lon=8.54170&z=16`, opens the map centred there. The page reads it on load
  and on `hashchange` (`KarteModel.parseView`): exactly these three keys, plain decimal numbers,
  the point inside the map's bounds (45.80–47.85° N, 5.90–10.55° E) and the zoom within 7–18;
  anything else is ignored and the default view is used. A valid view replaces the default view
  and the fit to the data, scrolls the page to the map (`#metric`, the first control), and sets
  only the view: no marker is drawn there. Browsers never send the fragment to a server. The app's
  tab «Karte» uses it with the **shifted** point of the newest shared measurement (`MapLinks.url`,
  5 decimals, zoom 16), and the plain anchor `karte.html#metric` when there is none. WGS84 rather
  than LV95, because Leaflet works in WGS84 and any map link can be written by hand.
- Playwright tests at 360 px and 1280 px against the fixtures: renders, legend, period switch,
  card and chart, LSV bands, reports layer, day steps, fallback host, no «Nacht», CSP exact,
  view fragment (valid, invalid, hashchange, no marker, not in any request).

## 8. Hosting

Code is hosting-agnostic: one container (Python 3.12, FastAPI, SQLite on a volume, the
aggregation job as a loop in the same process), reverse-proxied by Caddy for TLS.
Target: **Infomaniak** (Swiss company, the project's registrar already), a small VPS /
Public Cloud instance in Switzerland. Host names: `API_HOSTS` (default
`api.lessnoise.ch, api.stadtlaerm.ch`), one Caddy site block for all of them, a certificate per
host; each host's DNS record must point at the machine. A host without a DNS record yet only
fails its own certificate (Caddy retries in the background) and does not block the others.
New app versions use `api.lessnoise.ch`; `api.stadtlaerm.ch` stays for apps up to 0.5.1.
Alternative if a managed service is preferred: Google Cloud Run in `europe-west6` (Zürich)
with the SQLite file on a mounted volume — the container does not change. Deployment is
`docker compose up`; `server/README.md` documents it.

Abuse limits: 10 device registrations per IP per day, 60 requests per device per hour (reports
included), request body ≤ 2 MB, strict schema validation (values outside 0–140 dB, coverage
outside 0–1, starts or report times more than 7 days old or in the future, cells, measurement
points or report points outside Switzerland, a σ other than 25/50/100/200 m are rejected with the
reason).

## 9. App changes (public flavour)

(Since 0.5.0 the public app is published in two editions with the same app id: the
**Karten-Version**, Gradle flavour `public`, which has everything below, and the
**Offline-Version**, flavour `offline`, which has no `INTERNET` permission and no
«Messwerte teilen» at all. See PRIVACY.md.)

- Manifest gains `INTERNET` (the `tools:node="remove"` goes); `PRIVACY.md`, `README.md`
  and the website change in the same release to say: internet is used only for the opt-in
  upload described here, nothing else, and here is how to check (one network class,
  `upload/UploadClient.kt`, one host, no third-party SDK). Since 0.6.3 there is a second, separate
  use: the tab «Karte» shows `docs/karte.html` in a WebView while it is open (rule 7; only in the
  editions with the map, `app/src/karte/`), locked down (no storage, no file access, no JavaScript
  bridge, no geolocation, no cookies, only the page's hosts), and the same documents say so.
- **0.6.3 opt-in and start** (rule 3): the first «Starten» asks «Auf die Lärmkarte?» once; with
  «Ja, auf die Karte» the measurement starts at once and the one location fix runs in parallel while
  the app is open (never in the background; retried when the app comes back to the foreground);
  the shifted point is attached to the running measurement. No fix: no dialog, the status line on
  «Messen» says «Kein Standort – tippen» and offers «Nochmals», the location or app settings and
  «Koordinaten einfügen». The tab «Teilen» has the status line and the switch on top and folds the
  explanation and the placement away.
- (Up to 0.6.0) screen **Einstellungen → Messwerte teilen**: explanation (what is sent / what is
  never sent / where it goes / how to delete), the map placement step (a small map, same
  swisstopo tiles, pin → hectare shown as a square, «Dieses Hektar wird gezeigt»),
  placement/floor/street-facing fields, the switch, «nur über WLAN», «Zuletzt gesendet»,
  «Meine Daten auf dem Server löschen», «Neue Kennung».
- Upload worker: WorkManager periodic + on measurement stop; batches as in 4.6; tested on
  the JVM with a fake server.
- Nothing in the measurement path changes.

## 10. Out of scope for the first version

Per-device public pages, user accounts, comparing cells, exporting from the website,
the windowsill sensor's own upload (it will speak the same API).
