"""Contribution API round trip (DESIGN.md §4) with validation and abuse limits (§8)."""

from __future__ import annotations

import gzip
import json
import sqlite3
from datetime import datetime, timedelta

from stadtlaerm_server.aggregate import ZONE
from stadtlaerm_server.app import hash_token

SITE = {
    "cell": "h26824_12473",
    "placement": "open_window",
    "floor": 3,
    "street_facing": True,
    "device_model": "Pixel 8",
    "audio_source": "UNPROCESSED",
    "calibrated": True,
    "calibration_offset_db": 121.9,
    "note": "2. OG, Seestrasse-Seite",
}


def recent(minutes_ago: int) -> datetime:
    return (datetime.now(ZONE) - timedelta(minutes=minutes_ago)).replace(second=0, microsecond=0)


def minute(t: datetime, laeq: float = 42.3) -> dict:
    return {
        "start": t.isoformat(timespec="seconds"),
        "duration_s": 60.0, "valid_s": 59.5, "coverage": 0.992,
        "laeq_db": laeq, "lafmax_db": 61.0, "lafmin_db": 33.1,
        "l1_db": 55.0, "l10_db": 47.2, "l50_db": 41.0, "l90_db": 36.4,
        "event_count": 2, "dominant_category": "road_traffic",
        "category_shares": {"road_traffic": 0.41, "loud_vehicle": 0.05, "unclassified": 0.54},
        "calibration_id": "a1b2", "calibration_offset_db": 121.9, "calibrated": True,
        "audio_source": "UNPROCESSED", "clock_corrections": 0,
    }  # fmt: skip


def event(t: datetime, lafmax: float = 71.4) -> dict:
    return {
        "start": t.isoformat(timespec="milliseconds"),
        "duration_s": 2.75, "lafmax_db": lafmax, "sel_db": 74.0, "background_db": 38.1,
        "threshold_db": 10.0, "min_level_db": 30.0, "category": "loud_vehicle",
        "category_score": 0.31, "calibration_id": "a1b2", "calibrated": True,
    }  # fmt: skip


def report(t: datetime, lat: float = 47.3553, lon: float = 8.5355, category: str = "motorrad") -> dict:
    return {"time": t.isoformat(timespec="seconds"), "lat": lat, "lon": lon, "accuracy_m": 9.0, "category": category}


def register(client) -> tuple[str, dict]:
    r = client.post("/v1/devices", json={"app_version": "0.4.0", "app_build": 40})
    assert r.status_code == 201, r.text
    body = r.json()
    return body["device_id"], {"Authorization": f"Bearer {body['token']}"}


def rows(settings, sql, *args):
    with sqlite3.connect(settings.db_path) as c:
        return c.execute(sql, args).fetchall()


def test_round_trip(client, settings):
    dev, auth = register(client)

    # The token is stored only as its SHA-256.
    token = auth["Authorization"].split()[1]
    assert len(token) == 43  # 32 bytes base64url without padding
    assert rows(settings, "SELECT token_hash FROM devices WHERE id = ?", dev) == [(hash_token(token),)]
    assert token not in json.dumps(rows(settings, "SELECT * FROM devices"))

    assert client.put(f"/v1/devices/{dev}/site", json=SITE, headers=auth).status_code == 204
    site2 = {**SITE, "placement": "balcony", "floor": None}
    assert client.put(f"/v1/devices/{dev}/site", json=site2, headers=auth).status_code == 204
    assert rows(settings, "SELECT placement, floor, street_facing FROM sites") == [("balcony", None, 1)]

    ts = [recent(m) for m in range(120, 60, -1)]
    r = client.post(f"/v1/devices/{dev}/minutes", json=[minute(t) for t in ts], headers=auth)
    assert r.status_code == 200, r.text
    assert r.json() == {"accepted": 60, "duplicates": 0, "rejected": [], "dropped_by_category": 0}

    # gzip body; 30 already stored (re-evaluated, louder) and 30 new.
    batch = [minute(t, laeq=50.0) for t in ts[30:]] + [minute(recent(m)) for m in range(60, 30, -1)]
    r = client.post(
        f"/v1/devices/{dev}/minutes",
        content=gzip.compress(json.dumps(batch).encode()),
        headers={**auth, "Content-Encoding": "gzip", "Content-Type": "application/json"},
    )
    assert r.json() == {"accepted": 30, "duplicates": 30, "rejected": [], "dropped_by_category": 0}
    assert rows(settings, "SELECT COUNT(*) FROM minutes") == [(90,)]
    # The later record replaced the stored one.
    assert rows(settings, "SELECT laeq_db FROM minutes WHERE start_iso = ?", ts[45].isoformat()) == [(50.0,)]
    assert rows(settings, "SELECT laeq_db FROM minutes WHERE start_iso = ?", ts[0].isoformat()) == [(42.3,)]

    # Duplicates inside one batch count once as new, then as duplicates.
    e1 = recent(50) + timedelta(seconds=12.375)
    evs = [event(e1), event(e1, lafmax=80.0), event(recent(40))]
    r = client.post(f"/v1/devices/{dev}/events", json=evs, headers=auth)
    assert r.json() == {"accepted": 2, "duplicates": 1, "rejected": [], "dropped_by_category": 0}
    assert rows(settings, "SELECT lafmax_db FROM events ORDER BY start_utc") == [(80.0,), (71.4,)]
    r = client.post(
        f"/v1/devices/{dev}/events",
        content=gzip.compress(json.dumps(evs[:1]).encode()),
        headers={**auth, "Content-Encoding": "gzip"},
    )
    assert r.json() == {"accepted": 0, "duplicates": 1, "rejected": [], "dropped_by_category": 0}
    # Millisecond starts are kept.
    assert rows(settings, "SELECT start_utc % 1000 FROM events ORDER BY start_utc")[0] == (375,)


def test_delete_removes_everything(client, settings):
    dev, auth = register(client)
    other, other_auth = register(client)
    for d, a in ((dev, auth), (other, other_auth)):
        client.put(f"/v1/devices/{d}/site", json=SITE, headers=a)
        client.post(f"/v1/devices/{d}/minutes", json=[minute(recent(10))], headers=a)
        client.post(f"/v1/devices/{d}/events", json=[event(recent(10))], headers=a)
        assert client.post(f"/v1/devices/{d}/reports", json=[report(recent(10))], headers=a).json()["accepted"] == 1
    with sqlite3.connect(settings.db_path) as c:
        c.execute(
            "INSERT INTO hourly (device_id, hour_utc, valid_s, valid_minutes, events, calibrated) "
            "VALUES (?, 0, 0, 0, '{}', 0)",
            (dev,),
        )

    r = client.delete(f"/v1/devices/{dev}", headers=auth)
    assert r.status_code == 204
    for table in ("minutes", "events", "sites", "hourly", "reports"):
        assert rows(settings, f"SELECT COUNT(*) FROM {table} WHERE device_id = ?", dev) == [(0,)], table
    assert rows(settings, "SELECT COUNT(*) FROM devices WHERE id = ?", dev) == [(0,)]
    # The other device is untouched.
    assert rows(settings, "SELECT COUNT(*) FROM minutes WHERE device_id = ?", other) == [(1,)]
    assert rows(settings, "SELECT COUNT(*) FROM reports WHERE device_id = ?", other) == [(1,)]
    # The old token no longer works.
    assert client.post(f"/v1/devices/{dev}/minutes", json=[], headers=auth).status_code == 401


def test_auth_failures(client):
    dev, auth = register(client)
    other, other_auth = register(client)
    url = f"/v1/devices/{dev}/minutes"
    assert client.post(url, json=[]).status_code == 401
    assert client.post(url, json=[], headers={"Authorization": "Basic abc"}).status_code == 401
    assert client.post(url, json=[], headers={"Authorization": "Bearer wrong"}).status_code == 401
    assert client.post(url, json=[], headers=other_auth).status_code == 401  # another device's token
    assert client.post("/v1/devices/nope/minutes", json=[], headers=auth).status_code == 401
    assert client.delete(f"/v1/devices/{dev}", headers=other_auth).status_code == 401
    r = client.put(f"/v1/devices/{dev}/site", json=SITE)
    assert r.status_code == 401 and r.headers["www-authenticate"] == "Bearer"
    assert client.post(url, json=[], headers=auth).status_code == 200


def test_validation_failures(client, settings):
    dev, auth = register(client)
    url = f"/v1/devices/{dev}/minutes"

    assert client.post("/v1/devices", json={"app_version": "0.4.0"}).status_code == 422
    for bad in ({**SITE, "cell": "h1_2"}, {**SITE, "cell": "h10000_10000"}, {**SITE, "placement": "roof"},
                {**SITE, "note": "x" * 201}, {**SITE, "lat": 47.36}):  # fmt: skip
        r = client.put(f"/v1/devices/{dev}/site", json=bad, headers=auth)
        assert r.status_code == 422, bad
        assert r.json()["detail"][0]["reason"]

    t = recent(30)
    cases = {
        "laeq_db": {**minute(t), "laeq_db": 141.0},
        "l90_db": {**minute(t), "l90_db": -1.0},
        "coverage": {**minute(t), "coverage": 1.2},
        "old": minute(recent(8 * 24 * 60)),
        "future": minute(recent(-60)),
        "naive": {**minute(t), "start": t.replace(tzinfo=None).isoformat()},
        "extra": {**minute(t), "top_labels": ["Motorcycle"]},
        "valid_s": {**minute(t), "valid_s": 61.0, "duration_s": 60.0},
    }
    good = minute(recent(31))
    r = client.post(url, json=[good, *cases.values()], headers=auth)
    assert r.status_code == 200
    body = r.json()
    assert body["accepted"] == 1
    assert [x["index"] for x in body["rejected"]] == list(range(1, len(cases) + 1))
    reasons = {k: rej["reasons"][0] for k, rej in zip(cases, body["rejected"], strict=True)}
    assert reasons["laeq_db"]["field"] == "laeq_db" and "140" in reasons["laeq_db"]["reason"]
    assert reasons["coverage"]["field"] == "coverage"
    assert "7 days old" in reasons["old"]["reason"]
    assert "future" in reasons["future"]["reason"]
    assert "zone offset" in reasons["naive"]["reason"]
    assert reasons["extra"]["field"] == "top_labels"
    assert rows(settings, "SELECT COUNT(*) FROM minutes") == [(1,)]

    # Whole-request failures.
    assert client.post(url, json={"start": "x"}, headers=auth).status_code == 422
    assert client.post(url, json=[minute(recent(5))] * 1441, headers=auth).status_code == 413
    assert client.post(url, content=b"{not json", headers=auth).status_code == 400
    r = client.post(url, content=b"not gzip", headers={**auth, "Content-Encoding": "gzip"})
    assert r.status_code == 400
    assert client.post(url, content=b"[]", headers={**auth, "Content-Encoding": "br"}).status_code == 415


def test_body_size_limit(make_client):
    client = make_client(max_body_bytes=10_000)
    dev, auth = register(client)
    url = f"/v1/devices/{dev}/minutes"
    big = [minute(recent(m)) for m in range(100, 60, -1)]
    assert client.post(url, json=big, headers=auth).status_code == 413
    # The same body gzipped is small enough on the wire and accepted.
    r = client.post(url, content=gzip.compress(json.dumps(big).encode()), headers={**auth, "Content-Encoding": "gzip"})
    assert r.status_code == 200 and r.json()["accepted"] == 40
    # A gzip bomb (small on the wire, huge decompressed) is refused.
    bomb = gzip.compress(b"[" + b" " * 200_000 + b"]")
    assert len(bomb) < 10_000
    assert client.post(url, content=bomb, headers={**auth, "Content-Encoding": "gzip"}).status_code == 413


def test_rate_limits(make_client):
    client = make_client(registrations_per_ip_per_day=3, requests_per_device_per_hour=5)
    for _ in range(3):
        register(client)
    r = client.post("/v1/devices", json={"app_version": "0.4.0", "app_build": 40})
    assert r.status_code == 429 and int(r.headers["retry-after"]) > 0

    # Per device, not per IP: a second device still has its own budget.
    client2 = make_client(registrations_per_ip_per_day=10, requests_per_device_per_hour=5)
    a, a_auth = register(client2)
    b, b_auth = register(client2)
    for _ in range(5):
        assert client2.post(f"/v1/devices/{a}/minutes", json=[], headers=a_auth).status_code == 200
    assert client2.post(f"/v1/devices/{a}/minutes", json=[], headers=a_auth).status_code == 429
    assert client2.post(f"/v1/devices/{b}/minutes", json=[], headers=b_auth).status_code == 200
    # Delete is never rate limited.
    assert client2.delete(f"/v1/devices/{a}", headers=a_auth).status_code == 204


def test_map_files_are_served_with_cache_and_cors(make_client, settings):
    client = make_client(cors_origin="https://example.test")
    (settings.map_dir / "cells").mkdir(parents=True)
    (settings.map_dir / "cells.json").write_text('{"cells": []}')
    (settings.map_dir / "cells" / "h26824_12473.json").write_text("{}")
    r = client.get("/v1/map/cells.json")
    assert r.status_code == 200 and r.json() == {"cells": []}
    assert r.headers["cache-control"] == "max-age=15"
    assert r.headers["access-control-allow-origin"] == "https://example.test"
    assert r.headers["content-type"].startswith("application/json")
    assert client.get("/v1/map/cells/h26824_12473.json").status_code == 200
    assert client.get("/v1/map/missing.json").status_code == 404
    assert client.get("/v1/map/../db.sqlite").status_code == 404
    assert client.get("/v1/map/%2e%2e/db.sqlite").status_code == 404


def test_map_files_answer_conditional_requests_with_304(make_client, settings):
    """The map page polls every 30 s; an unchanged file costs an empty 304 (DESIGN.md §6, §8)."""
    import os

    client = make_client(cors_origin="https://example.test")
    settings.map_dir.mkdir(parents=True)
    path = settings.map_dir / "cells.json"
    path.write_text('{"cells": []}')
    os.utime(path, (1_792_000_000, 1_792_000_000))
    r = client.get("/v1/map/cells.json")
    assert r.status_code == 200
    etag, last_modified = r.headers["etag"], r.headers["last-modified"]
    assert etag.startswith('"') and last_modified == "Wed, 14 Oct 2026 17:46:40 GMT"

    def get(**headers):
        return client.get("/v1/map/cells.json", headers=headers)

    for r in (
        get(**{"If-None-Match": etag}),
        get(**{"If-None-Match": f'"other", W/{etag}'}),  # a list, and the weak form
        get(**{"If-None-Match": "*"}),
        get(**{"If-None-Match": f'W/{etag[:-1]}-gzip"'}),  # as a compressing proxy (Caddy) may pass it on
        get(**{"If-Modified-Since": last_modified}),
        get(**{"If-Modified-Since": "Wed, 14 Oct 2026 18:00:00 GMT"}),
    ):
        assert r.status_code == 304 and r.content == b""
        assert r.headers["etag"] == etag and r.headers["cache-control"] == "max-age=15"
        assert r.headers["access-control-allow-origin"] == "https://example.test"
    for r in (
        get(**{"If-None-Match": '"other"'}),
        get(**{"If-None-Match": f'{etag[:-1]}-other"'}),
        get(**{"If-Modified-Since": "Wed, 14 Oct 2026 17:46:39 GMT"}),
        get(**{"If-Modified-Since": "not a date"}),
        # If-None-Match wins over If-Modified-Since (RFC 9110 §13.1.3)
        get(**{"If-None-Match": '"other"', "If-Modified-Since": last_modified}),
    ):
        assert r.status_code == 200 and r.json() == {"cells": []}

    # A new version of the file: new validators, the old ones get the new body.
    path.write_text('{"cells": [1]}')
    os.utime(path, (1_792_000_030, 1_792_000_030))
    r = get(**{"If-None-Match": etag, "If-Modified-Since": last_modified})
    assert r.status_code == 200 and r.json() == {"cells": [1]} and r.headers["etag"] != etag
    assert get(**{"If-Modified-Since": last_modified}).status_code == 200
    assert get(**{"If-None-Match": r.headers["etag"]}).status_code == 304


def test_rate_limit_default_allows_an_upload_every_30_seconds(client):
    """360 authenticated requests per device and hour by default (DESIGN.md §8)."""
    from stadtlaerm_server.config import Settings

    assert Settings().requests_per_device_per_hour == 360
    assert Settings.from_env({}).requests_per_device_per_hour == 360
    assert Settings.from_env({"REQUESTS_PER_DEVICE_PER_HOUR": "60"}).requests_per_device_per_hour == 60
    assert Settings().publish_interval_s == 30.0
    assert Settings.from_env({}).publish_interval_s == 30.0
    assert Settings().registrations_per_ip_per_day == 10  # unchanged
    dev, auth = register(client)
    codes = [client.post(f"/v1/devices/{dev}/minutes", json=[], headers=auth).status_code for _ in range(360)]
    assert codes == [200] * 360
    assert client.post(f"/v1/devices/{dev}/minutes", json=[], headers=auth).status_code == 429


def test_map_cors_allows_each_configured_origin(make_client, settings):
    client = make_client(cors_origin="https://wenigerlaerm.ch, https://stadtlaerm.ch")
    settings.map_dir.mkdir(parents=True, exist_ok=True)
    (settings.map_dir / "cells.json").write_text('{"cells": []}')
    for origin in ("https://wenigerlaerm.ch", "https://stadtlaerm.ch"):
        r = client.get("/v1/map/cells.json", headers={"Origin": origin})
        assert r.headers["access-control-allow-origin"] == origin
        assert r.headers["vary"] == "Origin"
    r = client.get("/v1/map/cells.json", headers={"Origin": "https://evil.example"})
    assert r.headers["access-control-allow-origin"] == "https://wenigerlaerm.ch"
    assert (
        client.get("/v1/map/missing.json", headers={"Origin": "https://stadtlaerm.ch"}).headers[
            "access-control-allow-origin"
        ]
        == "https://stadtlaerm.ch"
    )


def test_schema_version_and_wal(client, settings):
    assert rows(settings, "SELECT version FROM schema_version") == [(5,)]
    assert rows(settings, "PRAGMA journal_mode") == [("wal",)]
    idx = {r[0] for r in rows(settings, "SELECT name FROM sqlite_master WHERE type = 'index'")}
    assert {"minutes_start", "events_start", "sites_cell", "reports_time"} <= idx


def test_record_cell_is_stored_and_validated(client, settings):
    """Minutes and events may carry their own hectare (§4.3, §4.4); a bad one is rejected."""
    dev, auth = register(client)
    client.put(f"/v1/devices/{dev}/site", json=SITE, headers=auth)
    with_cell = {**minute(recent(20)), "cell": "h26900_12500"}
    without = minute(recent(19))  # an older app: no cell → the site cell when the map is built
    bad_format = {**minute(recent(18)), "cell": "26900_12500"}
    outside = {**minute(recent(17)), "cell": "h20000_12500"}  # E 2 000 000: west of Switzerland
    r = client.post(f"/v1/devices/{dev}/minutes", json=[with_cell, without, bad_format, outside], headers=auth)
    body = r.json()
    assert body["accepted"] == 2
    assert [(x["index"], x["reasons"][0]["field"]) for x in body["rejected"]] == [(2, "cell"), (3, "cell")]
    assert "pattern" in body["rejected"][0]["reasons"][0]["reason"]
    assert body["rejected"][1]["reasons"][0]["reason"] == "cell is not an LV95 hectare in Switzerland"
    assert rows(settings, "SELECT cell FROM minutes ORDER BY start_utc") == [("h26900_12500",), (None,)]

    evs = [{**event(recent(20)), "cell": "h26900_12500"}, event(recent(19)), {**event(recent(18)), "cell": "h1_2"}]
    body = client.post(f"/v1/devices/{dev}/events", json=evs, headers=auth).json()
    assert body["accepted"] == 2 and [x["index"] for x in body["rejected"]] == [2]
    assert rows(settings, "SELECT cell FROM events ORDER BY start_utc") == [("h26900_12500",), (None,)]

    # Re-sending a minute (re-evaluation) replaces its cell too.
    client.post(f"/v1/devices/{dev}/minutes", json=[{**without, "cell": "h26901_12500"}], headers=auth)
    assert rows(settings, "SELECT cell FROM minutes ORDER BY start_utc") == [("h26900_12500",), ("h26901_12500",)]

    # The map: the record's cell where it has one, the site's where it has none.
    client.app.state.publish_once()
    shown = {c["cell"] for c in json.loads((settings.map_dir / "cells.json").read_text())["cells"]}
    assert shown == {"h26900_12500", "h26901_12500"}
    client.post(f"/v1/devices/{dev}/minutes", json=[minute(recent(16))], headers=auth)
    client.app.state.publish_once()
    shown = {c["cell"] for c in json.loads((settings.map_dir / "cells.json").read_text())["cells"]}
    assert shown == {"h26900_12500", "h26901_12500", SITE["cell"]}


POINT = {"e": 2682437, "n": 1247391, "sigma_m": 50}  # a shifted point (§4.3); its display cell is h26824_12473


def test_record_point_is_stored_and_shown_in_its_display_cell(client, settings):
    """App 0.6.0 sends a shifted LV95 point instead of a cell (§2 rule 2, §4.3, schema v4)."""
    dev, auth = register(client)
    t = [recent(30 - i) for i in range(12)]
    good = [
        {**minute(t[0]), "point": POINT},
        {**minute(t[1]), "point": {"e": 2690099, "n": 1250000, "sigma_m": 200}},
        minute(t[2]),  # neither: an older app (the site cell, here none)
    ]
    bad = [
        {**minute(t[3]), "point": {**POINT, "sigma_m": 30}},
        {**minute(t[4]), "point": {**POINT, "sigma_m": "50"}},
        {**minute(t[5]), "point": {**POINT, "e": 2682437.5}},
        {**minute(t[6]), "point": {**POINT, "e": 2479999}},  # 1 m west of the box
        {**minute(t[7]), "point": {**POINT, "n": 1300000}},  # 1 m north of the box
        {**minute(t[8]), "point": POINT, "cell": "h26824_12473"},  # both
        {**minute(t[9]), "point": {"e": 2682437, "n": 1247391}},  # no sigma
        {**minute(t[10]), "point": {**POINT, "lat": 47.3}},  # unknown field
        {**minute(t[11]), "point": {**POINT, "n": True}},
    ]
    body = client.post(f"/v1/devices/{dev}/minutes", json=good + bad, headers=auth).json()
    assert body["accepted"] == 3
    assert [x["index"] for x in body["rejected"]] == list(range(3, 12))
    assert all(x["reasons"][0]["field"].startswith("point") for x in body["rejected"]), body["rejected"]
    reasons = [x["reasons"][0]["reason"] for x in body["rejected"]]
    assert reasons[0] == "sigma_m must be one of 25, 50, 100, 200"
    assert reasons[3] == reasons[4] == "point is not in the LV95 box of Switzerland"
    assert reasons[5] == "send either cell or point, not both"
    assert rows(settings, "SELECT cell, point_e, point_n, sigma_m FROM minutes ORDER BY start_utc") == [
        ("h26824_12473", 2682437, 1247391, 50),
        ("h26900_12500", 2690099, 1250000, 200),
        (None, None, None, None),
    ]

    # The box edges themselves are inside.
    edge = [{**event(t[0]), "point": {"e": 2480000, "n": 1299999, "sigma_m": 25}}, {**event(t[1]), "point": POINT}]
    body = client.post(f"/v1/devices/{dev}/events", json=edge, headers=auth).json()
    assert body["accepted"] == 2 and body["rejected"] == []
    assert rows(settings, "SELECT cell, point_e, point_n, sigma_m FROM events ORDER BY start_utc") == [
        ("h24800_12999", 2480000, 1299999, 25),
        ("h26824_12473", 2682437, 1247391, 50),
    ]

    # Re-sending a minute replaces its point; a re-sent cell-only record clears it.
    moved = {**good[0], "point": {**POINT, "e": 2682537}}
    client.post(f"/v1/devices/{dev}/minutes", json=[moved], headers=auth)
    assert rows(settings, "SELECT cell, point_e FROM minutes WHERE point_n = 1247391") == [("h26825_12473", 2682537)]

    # The map shows the display cells and never the points or σ.
    client.app.state.publish_once()
    shown = {c["cell"] for c in json.loads((settings.map_dir / "cells.json").read_text())["cells"]}
    assert shown == {"h26825_12473", "h26900_12500"}  # a cell needs minutes; events alone do not make one
    published = "".join(f.read_text() for f in settings.map_dir.rglob("*.json"))
    for secret in ("2682537", "1247391", "2690099", "point", "sigma"):
        assert secret not in published, secret


def test_site_with_point(client, settings):
    dev, auth = register(client)
    site = {k: v for k, v in SITE.items() if k != "cell"}
    assert client.put(f"/v1/devices/{dev}/site", json={**site, "point": POINT}, headers=auth).status_code == 204
    assert rows(settings, "SELECT cell, point_e, point_n, sigma_m FROM sites") == [
        ("h26824_12473", 2682437, 1247391, 50)
    ]
    # Back to a cell (an older app on the same id): the point is cleared.
    assert client.put(f"/v1/devices/{dev}/site", json=SITE, headers=auth).status_code == 204
    assert rows(settings, "SELECT cell, point_e, sigma_m FROM sites") == [("h26824_12473", None, None)]
    for bad in (site, {**SITE, "point": POINT}, {**site, "point": {**POINT, "sigma_m": 10}}):
        assert client.put(f"/v1/devices/{dev}/site", json=bad, headers=auth).status_code == 422, bad
    # A record without a place of its own counts for the site's display cell.
    client.put(f"/v1/devices/{dev}/site", json={**site, "point": {**POINT, "e": 2700001}}, headers=auth)
    client.post(f"/v1/devices/{dev}/minutes", json=[minute(recent(15))], headers=auth)
    client.app.state.publish_once()
    shown = {c["cell"] for c in json.loads((settings.map_dir / "cells.json").read_text())["cells"]}
    assert shown == {"h27000_12473"}


def test_reports_round_trip_and_validation(client, settings):
    dev, auth = register(client)
    url = f"/v1/devices/{dev}/reports"
    t = recent(30)
    r = client.post(url, json=[report(t), report(recent(29), category="poser")], headers=auth)
    assert r.status_code == 200 and r.json() == {
        "accepted": 2,
        "duplicates": 0,
        "rejected": [],
        "dropped_by_category": 0,
    }
    # Same (device, time) again: a duplicate, and the later one wins (as for minutes); gzip works.
    again = [report(t, lat=47.3600049, lon=8.5400051, category="auto")]
    r = client.post(
        url, content=gzip.compress(json.dumps(again).encode()), headers={**auth, "Content-Encoding": "gzip"}
    )
    assert r.json() == {"accepted": 0, "duplicates": 1, "rejected": [], "dropped_by_category": 0}
    assert rows(settings, "SELECT lat, lon, accuracy_m, category FROM reports ORDER BY time_utc") == [
        (47.36, 8.54001, 9.0, "auto"),  # rounded to 5 decimals
        (47.3553, 8.5355, 9.0, "poser"),
    ]
    assert rows(settings, "SELECT time_iso FROM reports ORDER BY time_utc")[0] == (t.isoformat(timespec="seconds"),)

    cases = {
        "old": report(recent(8 * 24 * 60)),
        "future": report(recent(-60)),
        "naive": {**report(recent(5)), "time": recent(5).replace(tzinfo=None).isoformat()},
        "outside": report(recent(5), lat=48.14, lon=11.58),  # München
        "north": report(recent(5), lat=47.9, lon=8.6),  # north of the box (Germany)
        "accuracy": {**report(recent(5)), "accuracy_m": 201.0},
        "category": report(recent(5), category="velo"),
        "missing": {k: v for k, v in report(recent(5)).items() if k != "accuracy_m"},
        "extra": {**report(recent(5)), "device": "x"},
        "nan": {**report(recent(5)), "lat": "NaN"},
    }
    good = report(recent(4))
    body = client.post(url, json=[good, *cases.values()], headers=auth).json()
    assert body["accepted"] == 1
    assert [x["index"] for x in body["rejected"]] == list(range(1, len(cases) + 1))
    reasons = {k: rej["reasons"][0] for k, rej in zip(cases, body["rejected"], strict=True)}
    assert body["rejected"][0]["time"] == cases["old"]["time"]
    assert reasons["old"] == {"field": "time", "reason": "time is more than 7 days old"}
    assert reasons["future"] == {"field": "time", "reason": "time is in the future"}
    assert "zone offset" in reasons["naive"]["reason"]
    assert reasons["outside"] == {"field": "lat", "reason": "the point (lat, lon) is not in Switzerland"}
    assert reasons["north"]["field"] == "lat"
    assert reasons["accuracy"]["field"] == "accuracy_m" and "200" in reasons["accuracy"]["reason"]
    assert reasons["category"]["field"] == "category"
    assert reasons["missing"]["field"] == "accuracy_m"
    assert reasons["extra"]["field"] == "device"
    assert reasons["nan"]["field"] == "lat"

    # Whole-request failures: at most 50 per request, a JSON array, a token.
    assert client.post(url, json=[report(recent(3))] * 51, headers=auth).status_code == 413
    assert client.post(url, json=report(recent(3)), headers=auth).status_code == 422
    assert client.post(url, json=[report(recent(3))]).status_code == 401


def test_reports_count_against_the_device_rate_limit(make_client):
    client = make_client(requests_per_device_per_hour=3)
    dev, auth = register(client)
    for _ in range(3):
        assert client.post(f"/v1/devices/{dev}/reports", json=[], headers=auth).status_code == 200
    assert client.post(f"/v1/devices/{dev}/reports", json=[], headers=auth).status_code == 429
    assert client.post(f"/v1/devices/{dev}/minutes", json=[], headers=auth).status_code == 429


def test_healthz_names_the_deployed_commit(make_client, monkeypatch):
    monkeypatch.delenv("GIT_COMMIT", raising=False)
    assert make_client().get("/healthz").json() == {"ok": True, "commit": "unknown"}
    monkeypatch.setenv("GIT_COMMIT", "0123456789ab")
    assert make_client().get("/healthz").json() == {"ok": True, "commit": "0123456789ab"}


def test_loud_event_db_setting(make_client, settings):
    from stadtlaerm_server.config import Settings

    assert Settings.from_env({}).loud_event_db == 75.0
    assert Settings.from_env({"LOUD_EVENT_DB": "80"}).loud_event_db == 80.0
    client = make_client(loud_event_db=80.0)
    client.app.state.publish_once()
    assert json.loads((settings.map_dir / "cells.json").read_text())["loud_event_db"] == 80.0
