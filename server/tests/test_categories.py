"""Only machine and motor noise on the public map (DESIGN.md §1, §4.4, §6): what the server drops on
arrival, what it ignores when it reads stored rows, the quality gates, the voices/music rule for
minutes, the published category keys and what makes a field calibrated."""

from __future__ import annotations

import itertools
import json
import logging
from datetime import datetime, timedelta
from pathlib import Path

import pytest
from test_api import SITE, event, recent, register, rows

from stadtlaerm_server import aggregate as agg
from stadtlaerm_server import db, publish
from stadtlaerm_server.config import MAP_CATEGORIES
from stadtlaerm_server.schemas import EventIn, MinuteIn

NOW = datetime.fromisoformat("2026-10-14T20:00:00+02:00")
DAY = NOW.date()
CELL = "h26824_12473"
RECEIVED = "2026-10-14T18:00:00+00:00"
NOT_ON_MAP = ("voices", "music", "unclassified", "wind", "glass_breaking")  # the last: an unknown id


@pytest.fixture
def conn(tmp_path: Path):
    c = db.connect(tmp_path / "db.sqlite")
    db.migrate(c)
    yield c
    c.close()


def add_device(conn, dev: str = "dev-a") -> str:
    conn.execute("INSERT INTO devices (id, token_hash, created_at) VALUES (?, 'x', '')", (dev,))
    conn.execute(
        "INSERT INTO sites (device_id, cell, placement, device_model, audio_source, calibrated, updated_at) "
        "VALUES (?, ?, 'open_window', 'Pixel 8', 'UNPROCESSED', 1, '')",
        (dev, CELL),
    )
    return dev


def at(h: int, m: int, s: float = 0.0) -> str:
    t = datetime(2026, 10, 14, h, m, tzinfo=agg.ZONE) + timedelta(seconds=s)
    return t.isoformat(timespec="milliseconds")


def add_minutes(conn, dev, first: int, n: int, laeq: float, shares: dict | None = None, calibrated=True, hour=10):
    recs = [
        MinuteIn(
            start=at(hour, first + i), duration_s=60.0, valid_s=60.0, coverage=1.0, laeq_db=laeq,
            l10_db=laeq + 2, l90_db=laeq - 3, event_count=0, calibrated=calibrated,
            category_shares={"road_traffic": 0.6} if shares is None else shares,
        )
        for i in range(n)
    ]  # fmt: skip
    db.upsert_rows(conn, "minutes", db.MINUTE_COLUMNS, [db.minute_row(dev, m, RECEIVED) for m in recs])


def add_event(conn, dev, minute: int, category="road_traffic", lafmax=70.0, duration=3.0, threshold=6.5,
              min_level=30.0, calibrated=True, second=10.0):  # fmt: skip
    e = EventIn(
        start=at(10, minute, second), duration_s=duration, lafmax_db=lafmax, threshold_db=threshold,
        min_level_db=min_level, category=category, calibrated=calibrated,
    )  # fmt: skip
    db.upsert_rows(conn, "events", db.EVENT_COLUMNS, [db.event_row(dev, e, RECEIVED)])


def published(conn, tmp_path) -> tuple[dict, dict]:
    """(the cell's entry in cells.json, cells/{cell}.json)."""
    conn.commit()
    cells = publish.publish(conn, tmp_path / "map", now=NOW)
    entry = next(c for c in cells["cells"] if c["cell"] == CELL)
    return entry, json.loads((tmp_path / "map" / "cells" / f"{CELL}.json").read_text())


# ---- on arrival ------------------------------------------------------------------------------


def test_intake_drops_non_machine_categories_with_2xx_and_a_counter(client, settings, caplog):
    dev, auth = register(client)
    assert client.put(f"/v1/devices/{dev}/site", json=SITE, headers=auth).status_code == 204
    t0 = recent(30)
    cats = ("loud_vehicle", "road_traffic", *NOT_ON_MAP)
    evs = [{**event(t0 + timedelta(seconds=10 * i)), "category": c} for i, c in enumerate(cats)]
    with caplog.at_level(logging.INFO, logger="stadtlaerm"):
        r = client.post(f"/v1/devices/{dev}/events", json=evs, headers=auth)
    assert r.status_code == 200, r.text
    assert r.json() == {"accepted": 2, "duplicates": 0, "rejected": [], "dropped_by_category": 5}
    assert rows(settings, "SELECT category FROM events ORDER BY start_utc") == [("loud_vehicle",), ("road_traffic",)]
    assert "events: dropped_by_category=5" in caplog.text
    assert dev not in caplog.text  # the log line carries no device id
    # Sending them again: still 2xx, still dropped, nothing stored.
    r = client.post(f"/v1/devices/{dev}/events", json=evs[2:], headers=auth)
    assert r.status_code == 200 and r.json()["dropped_by_category"] == 5 and r.json()["accepted"] == 0


def test_dropped_event_replaces_the_stored_one_with_its_start(client, settings):
    dev, auth = register(client)
    t = recent(20)
    assert client.post(f"/v1/devices/{dev}/events", json=[event(t)], headers=auth).json()["accepted"] == 1
    r = client.post(f"/v1/devices/{dev}/events", json=[{**event(t), "category": "voices"}], headers=auth)
    assert r.json() == {"accepted": 0, "duplicates": 0, "rejected": [], "dropped_by_category": 1}
    assert rows(settings, "SELECT COUNT(*) FROM events") == [(0,)]
    # In one batch a kept record with the same start wins over a dropped one.
    batch = [{**event(t), "category": "music"}, event(t)]
    assert client.post(f"/v1/devices/{dev}/events", json=batch, headers=auth).json()["dropped_by_category"] == 1
    assert rows(settings, "SELECT category FROM events") == [("loud_vehicle",)]


# ---- stored rows -------------------------------------------------------------------------------


def test_stored_non_machine_events_are_ignored_and_never_published(conn, tmp_path):
    """Rows stored before the server filtered (apps 0.5.x–0.6.3 sent every category) stay in the
    table but never reach the map, the profile or the hourly rows."""
    dev = add_device(conn)
    add_minutes(conn, dev, 0, 60, 50.0)
    add_event(conn, dev, 5, "loud_vehicle", lafmax=82.0)
    add_event(conn, dev, 6, "road_traffic")
    for i, c in enumerate(NOT_ON_MAP):
        add_event(conn, dev, 20 + i, c, lafmax=95.0)
    assert conn.execute("SELECT COUNT(*) FROM events").fetchone()[0] == 7  # not deleted
    s, e = agg.day_bounds(DAY)
    assert [x.category for x in db.load_events(conn, dev, s, e)] == ["loud_vehicle", "road_traffic"]

    entry, cell_file = published(conn, tmp_path)
    d = entry["day"]
    assert d["events_per_h"] == 2.0 and d["loud_events"] == 1.0 and d["loudest_event_db"] == 82.0
    blocks = [d, entry["last_7_days"], entry["last_30_days"], *cell_file["days"], *cell_file["profile"]["hours"]]
    for b in blocks:
        assert list(b["events_per_h_by_category"]) == list(MAP_CATEGORIES)
    assert d["events_per_h_by_category"] == {
        "loud_vehicle": 1.0, "road_traffic": 1.0, "rail_tram": 0.0, "aircraft": 0.0, "construction": 0.0,
    }  # fmt: skip

    # The retention job's hourly row of 10:00 local (local midnight + 10 h) counts the same events.
    ten = agg.day_bounds(DAY)[0] + 10 * agg.HOUR_MS
    hour = agg.summarize_hour(ten, db.load_minutes(conn, dev, s, e), db.load_events(conn, dev, s, e))
    assert hour.events == {"loud_vehicle": 1, "road_traffic": 1, "rail_tram": 0, "aircraft": 0, "construction": 0}


def test_published_category_keys_are_exactly_the_five():
    assert MAP_CATEGORIES == ("loud_vehicle", "road_traffic", "rail_tram", "aircraft", "construction")
    assert agg.CATEGORIES == MAP_CATEGORIES
    assert list(agg.window_block([None])["events_per_h_by_category"]) == list(MAP_CATEGORIES)
    # Even records that bypass the database (unknown id in hand-built data) add no key.
    t = int(datetime(2026, 10, 14, 10, tzinfo=agg.ZONE).timestamp() * 1000)
    mins = [agg.Minute(t + i * 60_000, 60.0, 1.0, 50.0, 52.0, 47.0, True) for i in range(60)]
    evs = [
        agg.Event(t + 1000, 70.0, "voices"),
        agg.Event(t + 2000, 70.0, "made_up"),
        agg.Event(t + 3000, 70.0, "aircraft"),
    ]
    dd = agg.summarize_device_day(DAY, mins, evs)
    assert list(dd.events_per_h_by_category) == list(MAP_CATEGORIES) and dd.event_count == 1
    c = agg.combine_cell_day({"a": dd})
    assert list(c.block()["events_per_h_by_category"]) == list(MAP_CATEGORIES)
    rows_ = agg.combine_profile([agg.device_profile(mins, evs)])
    assert all(list(r["events_per_h_by_category"]) == list(MAP_CATEGORIES) for r in rows_)
    assert rows_[10]["events_per_h"] == 1.0


# ---- quality gates -----------------------------------------------------------------------------


GATE_CASES = [
    # (duration_s, lafmax_db, threshold_db, min_level_db, counts)
    (3.0, 70.0, 6.5, 30.0, True),
    (0.5, 30.0, 6.5, None, True),  # every gate exactly at its edge
    (0.49, 70.0, 6.5, 30.0, False),  # too short
    (3.0, 29.9, 6.5, None, False),  # below 30 dB
    (3.0, 39.0, 6.5, 40.0, False),  # below the app's own minimum level
    (3.0, 40.0, 6.5, 40.0, True),
    (3.0, 35.0, 6.5, 20.0, True),  # a lower app minimum does not lower the 30 dB floor…
    (3.0, 29.0, 6.5, 20.0, False),  # …which still applies
    (3.0, 70.0, 6.4, 30.0, False),  # excess setting below 6.5 dB
    (3.0, 70.0, 10.0, 30.0, True),  # detector v1's 10 dB
    (3.0, 70.0, None, 30.0, True),  # no threshold sent: not checked
]


@pytest.mark.parametrize(("duration", "lafmax", "threshold", "min_level", "counts"), GATE_CASES)
def test_quality_gates(conn, duration, lafmax, threshold, min_level, counts):
    dev = add_device(conn)
    add_event(conn, dev, 1, lafmax=lafmax, duration=duration, threshold=threshold, min_level=min_level)
    s, e = agg.day_bounds(DAY)
    assert len(db.load_events(conn, dev, s, e)) == (1 if counts else 0)
    assert agg.event_counts("road_traffic", duration, lafmax, threshold, min_level) is counts
    assert conn.execute("SELECT COUNT(*) FROM events").fetchone()[0] == 1  # ignored, not deleted


def test_gate_sql_and_python_agree(conn):
    """db.EVENT_COUNTS_SQL and aggregate.event_counts on a grid around every edge."""
    dev = add_device(conn)
    grid = list(itertools.product(
        ("road_traffic", "aircraft", "voices", "wind"), (0.4, 0.5, 2.0), (29.9, 30.0, 39.9, 40.0, 80.0),
        (None, 6.4, 6.5, 12.0), (None, 20.0, 30.0, 40.0),
    ))  # fmt: skip
    recs = [
        db.event_row(dev, EventIn(start=at(10, 0, i * 0.01), duration_s=d, lafmax_db=lv, threshold_db=th,
                                  min_level_db=ml, category=c, calibrated=True), RECEIVED)
        for i, (c, d, lv, th, ml) in enumerate(grid)
    ]  # fmt: skip
    db.upsert_rows(conn, "events", db.EVENT_COLUMNS, recs)
    s, e = agg.day_bounds(DAY)
    got = {x.start_ms for x in db.load_events(conn, dev, s, e)}
    want = {r["start_utc"] for r, g in zip(recs, grid, strict=True) if agg.event_counts(*g)}
    assert got == want and 0 < len(want) < len(grid)


# ---- minutes: mostly voices or music -----------------------------------------------------------


def test_minutes_mostly_voices_or_music_count_as_time_but_not_for_levels(conn, tmp_path):
    dev = add_device(conn)
    add_minutes(conn, dev, 0, 20, 50.0)  # road traffic
    add_minutes(conn, dev, 20, 10, 50.0, shares={})  # no shares: kept
    add_minutes(conn, dev, 30, 10, 50.0, shares={"voices": 0.29, "music": 0.2})  # 0.49: kept
    add_minutes(conn, dev, 40, 10, 80.0, shares={"voices": 0.3, "music": 0.2})  # exactly 0.5: left out
    add_minutes(conn, dev, 50, 10, 80.0, shares={"music": 0.9, "road_traffic": 0.1})  # left out
    s, e = agg.day_bounds(DAY)
    mins = db.load_minutes(conn, dev, s, e)
    assert [bool(m.counts_levels) for m in mins] == [True] * 40 + [False] * 20
    assert [bool(agg.minute_counts_levels(sh)) for sh in ({}, None, {"voices": 0.49}, {"voices": 0.25, "music": 0.25})] == [
        True, True, True, False,
    ]  # fmt: skip

    entry, cell_file = published(conn, tmp_path)
    d = entry["day"]
    assert d["laeq_db"] == 50.0 and d["l10_db"] == 52.0 and d["l90_db"] == 47.0 and d["dynamics_db"] == 5.0
    assert d["measured_hours"] == 1.0  # all 60 minutes count as measured time
    h10 = cell_file["profile"]["hours"][10]
    assert h10["laeq_db"] == 50.0 and h10["measured_hours"] == 1.0


def test_day_of_only_voices_minutes_has_time_but_no_levels(conn, tmp_path):
    dev = add_device(conn)
    add_minutes(conn, dev, 0, 60, 70.0, shares={"voices": 0.8})
    add_event(conn, dev, 3, "road_traffic")
    entry, cell_file = published(conn, tmp_path)
    d = entry["day"]
    assert d["laeq_db"] is None and d["l10_db"] is None and d["dynamics_db"] is None
    assert d["measured_hours"] == 1.0 and d["events_per_h"] == 1.0
    assert entry["last_7_days"]["laeq_db"] is None
    h10 = cell_file["profile"]["hours"][10]
    assert h10["laeq_db"] is None and h10["events_per_h"] == 1.0  # 60 valid minutes: the hour is shown


# ---- calibration -------------------------------------------------------------------------------


def test_a_field_with_an_uncalibrated_event_is_not_calibrated(conn, tmp_path):
    dev = add_device(conn)
    add_minutes(conn, dev, 0, 60, 50.0, calibrated=True)
    add_event(conn, dev, 5, "road_traffic", calibrated=True)
    # Not counted, so they do not matter: another category, and an event below a gate.
    add_event(conn, dev, 6, "voices", calibrated=False)
    add_event(conn, dev, 7, "road_traffic", lafmax=25.0, calibrated=False)
    entry, cell_file = published(conn, tmp_path / "a")
    assert entry["calibrated"] is True and entry["day"]["calibrated"] is True and cell_file["calibrated"] is True

    add_event(conn, dev, 8, "loud_vehicle", lafmax=80.0, calibrated=False)  # counted, uncalibrated
    entry, cell_file = published(conn, tmp_path / "b")
    assert entry["day"]["events_per_h"] == 2.0  # still counted: calibration is not a gate
    assert entry["calibrated"] is False and entry["day"]["calibrated"] is False
    assert entry["last_7_days"]["calibrated"] is False and cell_file["calibrated"] is False
