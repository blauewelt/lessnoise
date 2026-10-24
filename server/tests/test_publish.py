"""Publishing the map files (§6) from the database, and the retention job (§5)."""

from __future__ import annotations

import json
from datetime import UTC, date, datetime, timedelta
from pathlib import Path

import pytest

from stadtlaerm_server import aggregate as agg
from stadtlaerm_server import db, publish, retention
from stadtlaerm_server import synthetic as syn
from stadtlaerm_server.config import MAP_CATEGORIES
from stadtlaerm_server.schemas import EventIn, MinuteIn, ReportIn

# Publishing "now" = 18:40 on Sunday 25 October 2026, the 25-hour day of the autumn DST change.
NOW = datetime.fromisoformat("2026-10-25T18:40:00+01:00")
LAST = date(2026, 10, 25)
RECEIVED = "2026-10-25T17:00:00+00:00"


def add_device(conn, cell, model="Pixel 8", hidden=False, dev=None) -> str:
    dev = dev or f"dev-{cell}-{model}-{hidden}".replace(" ", "")
    conn.execute("INSERT INTO devices (id, token_hash, created_at, hidden) VALUES (?, 'x', '', ?)", (dev, int(hidden)))
    if cell is not None:
        conn.execute(
            "INSERT INTO sites (device_id, cell, placement, device_model, audio_source, calibrated, updated_at) "
            "VALUES (?, ?, 'open_window', ?, 'UNPROCESSED', 1, '')",
            (dev, cell, model),
        )
    return dev


def add_day(conn, dev, day: date, base: float, rate: float, seed: int, calibrated=True, start_h=6, hours=12, cell=None):
    """A measurement from `start_h` o'clock on `day` for `hours` hours (it may run past midnight).

    With `cell`, every record carries that hectare (a new app); without, none (an older app)."""
    l90 = syn.day_profile(base)
    start = datetime(day.year, day.month, day.day) + timedelta(hours=start_h)
    rates = {"road_traffic": rate, "loud_vehicle": rate / 5, "voices": rate / 4}
    evs = syn.random_events(start, hours, seed, rates, {"loud_vehicle": (70.0, 88.0)})
    mins = syn.minutes(start, start + timedelta(hours=hours), l90, evs, seed=seed, calibrated=calibrated, cell=cell)
    db.upsert_rows(conn, "minutes", db.MINUTE_COLUMNS, [db.minute_row(dev, MinuteIn(**m), RECEIVED) for m in mins])
    ev_rows = [db.event_row(dev, EventIn(**e), RECEIVED) for e in syn.event_records(evs, l90, calibrated, cell)]
    db.upsert_rows(conn, "events", db.EVENT_COLUMNS, ev_rows)


def add_report(conn, dev, at: datetime, lat: float, lon: float, category: str = "motorrad", direction=None):
    r = ReportIn(**syn.report_record(at, lat, lon, category, accuracy_m=123.4, direction=direction))
    db.upsert_rows(conn, "reports", db.REPORT_COLUMNS, [db.report_row(dev, r, RECEIVED)], key="time_utc")


@pytest.fixture
def conn(tmp_path: Path):
    c = db.connect(tmp_path / "db.sqlite")
    db.migrate(c)
    yield c
    c.close()


def load(p: Path) -> dict:
    return json.loads(p.read_text())


def device_day(conn, dev, day, cell=None):
    s, e = agg.day_bounds(day)
    return agg.summarize_device_day(day, db.load_minutes(conn, dev, s, e, cell), db.load_events(conn, dev, s, e, cell))


def test_publish_files_and_format(conn, tmp_path):
    a = add_device(conn, "h26824_12473")
    b = add_device(conn, "h26824_12473", model="Galaxy A54")
    c = add_device(conn, "h26830_12490", model="Pixel 7a")
    hidden = add_device(conn, "h26840_12500", hidden=True)
    for i in range(10):
        day = LAST - timedelta(days=i)
        add_day(conn, a, day, 44.0, 6.0, 100 + i, start_h=0, hours=18)
        add_day(conn, c, day, 38.0, 2.0, 200 + i, calibrated=False)
    add_day(conn, b, LAST, 40.0, 3.0, 300)
    add_day(conn, hidden, LAST, 70.0, 30.0, 400)
    (tmp_path / "map" / "nights").mkdir(parents=True)
    (tmp_path / "map" / "nights" / "2026-10-24.json").write_text("{}")  # left over from schema v2
    conn.commit()

    out = tmp_path / "map"
    cells = publish.publish(conn, out, now=NOW)
    assert load(out / "cells.json") == cells
    assert cells["day"] == "2026-10-25"
    assert cells["generated_at"] == "2026-10-25T18:40:00+01:00"
    assert cells["default_period"] == "last_7_days"
    assert cells["periods"] == {
        "last_7_days": {"from": "2026-10-19", "to": "2026-10-25", "days": 7},
        "last_30_days": {"from": "2026-09-26", "to": "2026-10-25", "days": 30},
    }
    assert cells["min_devices_per_cell"] == 1 and cells["loud_event_db"] == 75.0
    # The local date migration v5 ran (here: when the test database was made), as ISO date.
    assert cells["counted_since"] == db.counted_since(conn) == agg.today(datetime.now(UTC)).isoformat()
    by_cell = {x["cell"]: x for x in cells["cells"]}
    assert set(by_cell) == {"h26824_12473", "h26830_12490"}  # hidden device not shown
    assert not (out / "nights").exists()

    two = by_cell["h26824_12473"]
    assert set(two) == {"cell", "devices", "calibrated", "day", "last_7_days", "last_30_days"}
    assert two["devices"] == 2 and two["calibrated"] is True
    d = two["day"]
    assert set(d) == set(agg.BLOCK_KEYS)
    # Exactly the five machine and motor categories, in order (add_day also sends voices).
    assert list(d["events_per_h_by_category"]) == list(MAP_CATEGORIES)
    # The cell value equals the combination of the two device days computed directly.
    assert d == agg.combine_cell_day({a: device_day(conn, a, LAST), b: device_day(conn, b, LAST)}).block()
    # a: 00:00–18:00 on a 25-hour day = 19 real hours; b: 06:00–18:00 = 12 h; the mean of the devices.
    assert d["measured_hours"] == pytest.approx((19 + 12) / 2)
    w7 = two["last_7_days"]
    assert w7["days_with_data"] == 7 and w7["devices"] == 2
    assert w7["measured_hours"] == pytest.approx(6 * 18 + (19 + 12) / 2)  # sum of the daily cell hours
    assert two["last_30_days"]["days_with_data"] == 10
    assert set(w7) == set(agg.BLOCK_KEYS)
    assert by_cell["h26830_12490"]["calibrated"] is False

    assert cells["network"] == {"devices_active_7d": 3, "device_models": {"Galaxy A54": 1, "Pixel 7a": 1, "Pixel 8": 1}}

    # Per-cell history and the 24-hour profile.
    cf = load(out / "cells" / "h26824_12473.json")
    assert set(cf) == {
        "generated_at", "cell", "devices", "calibrated", "loud_event_db", "counted_since", "days", "profile"
    }  # fmt: skip
    assert cf["counted_since"] == cells["counted_since"]
    assert cf["cell"] == "h26824_12473" and cf["devices"] == 2
    assert len(cf["days"]) == 10 and cf["days"][0]["day"] == "2026-10-25"
    assert cf["days"][0] == {"day": "2026-10-25", **d}
    assert cf["days"][1]["devices"] == 1
    assert cf["profile"]["from"] == "2026-09-26" and cf["profile"]["to"] == "2026-10-25"
    hours = cf["profile"]["hours"]
    assert [h["hour"] for h in hours] == list(range(24))
    assert set(hours[0]) == {"hour", "laeq_db", "l90_db", "l10_db", "events_per_h", "events_per_h_by_category",
                             "loud_events_per_h", "measured_hours", "devices"}  # fmt: skip
    assert all(h["laeq_db"] is not None for h in hours[:18])  # a measured 00–18 h every day
    assert all(h["laeq_db"] is None and h["measured_hours"] == 0 for h in hours[18:])
    assert hours[2]["measured_hours"] == pytest.approx(11.0)  # ten days, «02» twice on 25 October
    assert hours[10]["devices"] == 2
    assert not (out / "cells" / "h26840_12500.json").exists()

    # Past days: 90 files; a day before any data shows no cells.
    df = sorted(p.stem for p in (out / "days").glob("*.json"))
    assert len(df) == 90 and df[-1] == "2026-10-25" and df[0] == "2026-07-28"
    assert load(out / "days" / "2026-10-25.json") == cells
    past = load(out / "days" / "2026-10-20.json")
    assert past["day"] == "2026-10-20" and past["periods"]["last_7_days"]["from"] == "2026-10-14"
    assert {x["cell"] for x in past["cells"]} == {"h26824_12473", "h26830_12490"}
    assert next(x for x in past["cells"] if x["cell"] == "h26824_12473")["devices"] == 1
    assert load(out / "days" / "2026-08-01.json")["cells"] == []
    # No temp files left behind.
    assert not list(out.rglob("*.tmp"))


def test_min_devices_per_cell(conn, tmp_path):
    a = add_device(conn, "h26824_12473")
    b = add_device(conn, "h26824_12473", model="Galaxy A54")
    c = add_device(conn, "h26830_12490")
    for dev, seed in ((a, 1), (b, 2), (c, 3)):
        add_day(conn, dev, LAST, 42.0, 4.0, seed)
    conn.commit()
    cells = publish.publish(conn, tmp_path / "map", now=NOW, min_devices=2)
    assert [x["cell"] for x in cells["cells"]] == ["h26824_12473"]
    assert cells["min_devices_per_cell"] == 2


def test_cell_without_data_today_keeps_window_values(conn, tmp_path):
    a = add_device(conn, "h26824_12473")
    add_day(conn, a, LAST - timedelta(days=3), 42.0, 4.0, 1)
    conn.commit()
    cells = publish.publish(conn, tmp_path / "map", now=NOW)
    (entry,) = cells["cells"]
    assert entry["day"] is None
    assert entry["last_7_days"]["days_with_data"] == 1
    assert entry["devices"] == 1


def test_measurement_over_midnight_counts_for_both_days(conn, tmp_path):
    a = add_device(conn, "h26824_12473")
    add_day(conn, a, LAST - timedelta(days=2), 42.0, 4.0, 1, start_h=20, hours=10)  # 23 Oct 20:00 → 24 Oct 06:00
    conn.commit()
    publish.publish(conn, tmp_path / "map", now=NOW)
    cf = load(tmp_path / "map" / "cells" / "h26824_12473.json")
    assert [(x["day"], x["measured_hours"]) for x in cf["days"]] == [("2026-10-24", 6.0), ("2026-10-23", 4.0)]
    w7 = load(tmp_path / "map" / "cells.json")["cells"][0]["last_7_days"]
    assert w7["days_with_data"] == 2 and w7["measured_hours"] == 10.0 and w7["devices"] == 1


def test_record_cell_wins_over_the_site_cell(conn, tmp_path):
    """A record with its own cell (§4.3) counts there; one without falls back to the site cell."""
    moving = add_device(conn, "h26824_12473", dev="dev-moving")
    add_day(conn, moving, LAST, 42.0, 4.0, 1, start_h=6, hours=4)  # no cell: the site's
    add_day(conn, moving, LAST, 50.0, 8.0, 2, start_h=12, hours=3, cell="h26900_12500")
    siteless = add_device(conn, None, dev="dev-siteless")
    add_day(conn, siteless, LAST, 45.0, 4.0, 3, start_h=8, hours=2, cell="h26910_12510")
    lost = add_device(conn, None, dev="dev-lost")  # no site, no cells: nowhere to show it
    add_day(conn, lost, LAST, 45.0, 4.0, 4)
    conn.commit()
    cells = publish.publish(conn, tmp_path / "map", now=NOW)
    by_cell = {x["cell"]: x for x in cells["cells"]}
    assert set(by_cell) == {"h26824_12473", "h26900_12500", "h26910_12510"}
    assert by_cell["h26824_12473"]["day"]["measured_hours"] == 4.0
    assert by_cell["h26900_12500"]["day"]["measured_hours"] == 3.0
    assert by_cell["h26910_12510"]["day"]["measured_hours"] == 2.0
    assert cells["network"] == {"devices_active_7d": 2, "device_models": {"Pixel 8": 1, "unbekannt": 1}}
    prof = load(tmp_path / "map" / "cells" / "h26900_12500.json")["profile"]["hours"]
    assert [h["hour"] for h in prof if h["laeq_db"] is not None] == [12, 13, 14]
    # Moving the site moves only the records without a cell.
    conn.execute("UPDATE sites SET cell = 'h26830_12490' WHERE device_id = ?", (moving,))
    conn.commit()
    cells = publish.publish(conn, tmp_path / "map", now=NOW)
    assert {x["cell"] for x in cells["cells"]} == {"h26830_12490", "h26900_12500", "h26910_12510"}


def test_loud_event_threshold(conn, tmp_path):
    a = add_device(conn, "h26824_12473")
    add_day(conn, a, LAST, 42.0, 10.0, 7)
    conn.commit()
    s, e = agg.day_bounds(LAST)
    levels = [ev.lafmax_db for ev in db.load_events(conn, a, s, e)]
    for threshold in (75.0, 80.0):
        cells = publish.publish(conn, tmp_path / "map", now=NOW, loud_db=threshold)
        d = cells["cells"][0]["day"]
        assert cells["loud_event_db"] == threshold
        assert d["loud_events"] == sum(1 for x in levels if x >= threshold)
        assert d["loud_events_per_h"] == round(d["loud_events"] / 12, 1)
    assert load(tmp_path / "map" / "cells" / "h26824_12473.json")["loud_event_db"] == 80.0


def test_deleted_device_disappears_on_next_publish(conn, tmp_path):
    a = add_device(conn, "h26824_12473")
    add_day(conn, a, LAST, 42.0, 4.0, 1)
    add_report(conn, a, datetime(2026, 10, 25, 9, 12), 47.3553, 8.5355)
    conn.commit()
    out = tmp_path / "map"
    publish.publish(conn, out, now=NOW)
    assert (out / "cells" / "h26824_12473.json").exists()
    assert len(load(out / "reports.json")["reports"]) == 1
    db.delete_device(conn, a)
    cells = publish.publish(conn, out, now=NOW)
    assert cells["cells"] == [] and cells["network"]["devices_active_7d"] == 0
    assert not (out / "cells" / "h26824_12473.json").exists()
    assert all(load(p)["cells"] == [] for p in (out / "days").glob("*.json"))
    assert load(out / "reports.json")["reports"] == []


def test_reports_json(conn, tmp_path):
    a = add_device(conn, "h26824_12473", dev="dev-aaaa")
    b = add_device(conn, None, dev="dev-bbbb")  # reports need no site
    hidden = add_device(conn, "h26830_12490", hidden=True, dev="dev-hidden")
    add_report(conn, a, datetime(2026, 10, 25, 9, 59, 59), 47.355312, 8.535549, "motorrad")
    add_report(conn, b, datetime(2026, 10, 25, 9, 1), 47.350000, 8.530000, "poser")
    add_report(conn, a, datetime(2026, 10, 25, 9, 30), 47.360000, 8.540000, "lastwagen")
    add_report(conn, b, datetime(2026, 10, 25, 2, 30), 47.37, 8.52, "auto")  # the first 02:30, CEST
    add_report(conn, a, datetime(2026, 9, 26, 0, 5), 47.38, 8.51, "andere")  # first day of the 30
    add_report(conn, a, datetime(2026, 9, 25, 23, 55), 47.39, 8.50, "andere")  # one day too old
    add_report(conn, hidden, datetime(2026, 10, 25, 9, 15), 47.36, 8.54, "auto")
    conn.commit()
    out = tmp_path / "map"
    publish.publish(conn, out, now=NOW)
    r = load(out / "reports.json")
    assert set(r) == {"generated_at", "from", "to", "categories", "reports"}
    assert (r["from"], r["to"]) == ("2026-09-26", "2026-10-25")
    assert r["categories"] == ["motorrad", "auto", "lastwagen", "flugzeug", "poser", "andere"]
    assert r["reports"] == [
        {"time": "2026-09-26T00:00:00+02:00", "lat": 47.38, "lon": 8.51, "category": "andere", "direction": None},
        {"time": "2026-10-25T02:00:00+02:00", "lat": 47.37, "lon": 8.52, "category": "auto", "direction": None},
        # 09:01, 09:30 and 09:59:59 are all «09:00»; within the hour, sorted by place, not by time or device.
        {"time": "2026-10-25T09:00:00+01:00", "lat": 47.35, "lon": 8.53, "category": "poser", "direction": None},
        {"time": "2026-10-25T09:00:00+01:00", "lat": 47.35531, "lon": 8.53555, "category": "motorrad", "direction": None},
        {"time": "2026-10-25T09:00:00+01:00", "lat": 47.36, "lon": 8.54, "category": "lastwagen", "direction": None},
    ]
    text = (out / "reports.json").read_text()
    for forbidden in ("dev-", "accuracy", "123.4", "device"):
        assert forbidden not in text
    # Stored to 5 decimals (≈ 1 m), as received otherwise.
    assert tuple(conn.execute("SELECT lat, lon, accuracy_m FROM reports WHERE category = 'motorrad'").fetchone()) == (
        47.35531,
        8.53555,
        123.4,
    )


def test_retention_reduces_old_rows_to_hours_per_cell(conn):
    a = add_device(conn, "h26824_12473")
    old = date(2024, 9, 1)
    add_day(conn, a, old, 42.0, 6.0, 1, start_h=6, hours=4)
    add_day(conn, a, old, 42.0, 6.0, 2, start_h=12, hours=2, cell="h26900_12500")
    add_day(conn, a, LAST, 42.0, 6.0, 3)
    add_report(conn, a, datetime(2024, 9, 1, 8, 0), 47.36, 8.54)
    add_report(conn, a, datetime(2026, 10, 25, 8, 0), 47.36, 8.54)
    conn.commit()
    s, e = agg.day_bounds(old)
    n_old = conn.execute("SELECT COUNT(*) FROM minutes WHERE start_utc < ?", (e,)).fetchone()[0]
    minutes = db.load_minutes(conn, a, s, e)
    events = db.load_events(conn, a, s, e)
    expected = {}
    for m in minutes:
        h = m.start_ms // agg.HOUR_MS * agg.HOUR_MS
        same = ([x for x in minutes if x.cell == m.cell], [x for x in events if x.cell == m.cell])
        expected[(h, m.cell or "")] = agg.summarize_hour(h, *same)

    result = retention.run_retention(conn, now=NOW, retention_days=730)
    assert result["minutes_deleted"] == n_old == 360
    assert result["hours"] == 6
    assert result["reports_deleted"] == 1
    assert conn.execute("SELECT COUNT(*) FROM reports").fetchone()[0] == 1
    assert conn.execute("SELECT COUNT(*) FROM events WHERE start_utc < ?", (e,)).fetchone()[0] == 0
    hours = conn.execute(
        "SELECT hour_utc, cell, laeq_db, valid_minutes, events FROM hourly ORDER BY hour_utc"
    ).fetchall()
    assert [(h[0], h[1]) for h in hours] == sorted(expected)
    assert [h[1] for h in hours] == [""] * 4 + ["h26900_12500"] * 2
    assert [round(h[2], 6) for h in hours] == [round(expected[(h[0], h[1])].laeq_db, 6) for h in hours]
    assert all(h[3] == 60 for h in hours)
    assert sum(sum(json.loads(h[4]).values()) for h in hours) == sum(sum(x.events.values()) for x in expected.values())

    # Idempotent: a second run finds nothing old.
    assert retention.run_retention(conn, now=NOW, retention_days=730) == {
        "hours": 0,
        "minutes_deleted": 0,
        "events_deleted": 0,
        "reports_deleted": 0,
    }
    assert conn.execute("SELECT COUNT(*) FROM hourly").fetchone()[0] == 6


def test_background_publish_on_startup(make_client, settings):
    """The app publishes once at startup when background jobs are on."""
    import time

    make_client(background_jobs=True, publish_interval_s=3600)
    for _ in range(50):
        if (settings.map_dir / "stats.json").exists():
            break
        time.sleep(0.05)
    cells = load(settings.map_dir / "cells.json")
    assert cells["cells"] == [] and cells["min_devices_per_cell"] == 1
    assert load(settings.map_dir / "reports.json")["reports"] == []


def test_write_json_atomic_replaces(tmp_path):
    p = tmp_path / "x" / "a.json"
    publish.write_json_atomic(p, {"a": 1})
    publish.write_json_atomic(p, {"a": 2})
    assert load(p) == {"a": 2}
    assert [q.name for q in p.parent.iterdir()] == ["a.json"]


def _shape(obj):
    """Keys and value types, recursively; list lengths and device model names ignored."""
    if isinstance(obj, dict):
        return {k: ("{model: int}" if k == "device_models" else _shape(v)) for k, v in obj.items()}
    if isinstance(obj, list):
        return [_shape(obj[0])] if obj else []
    if isinstance(obj, bool) or obj is None:
        return type(obj).__name__ if obj is not None else None
    return "number" if isinstance(obj, int | float) else type(obj).__name__


def _complete(obj) -> bool:
    """No null anywhere inside (so the shape comparison sees every type)."""
    if isinstance(obj, dict):
        return all(_complete(v) for v in obj.values())
    if isinstance(obj, list):
        return all(_complete(v) for v in obj)
    return obj is not None


def test_example_fixture_has_the_published_format(conn, tmp_path):
    """example_cells.json / example_cell.json / example_reports.json must look exactly like what
    publish() writes.

    Regenerate with `python scripts/make_fixture.py` when this fails after a format change.
    """
    a = add_device(conn, "h26824_12473")
    b = add_device(conn, "h26824_12473", model="Galaxy A54")
    for i in range(3):
        add_day(conn, a, LAST - timedelta(days=i), 44.0, 6.0, 10 + i, start_h=0, hours=18)
        add_day(conn, b, LAST - timedelta(days=i), 40.0, 3.0, 20 + i)
    add_report(
        conn, a, datetime(2026, 10, 25, 9, 12), 47.3553, 8.5355,
        direction={"bearing_deg": 87, "north": "true", "accuracy": "high"},
    )  # fmt: skip
    conn.commit()
    cells = publish.publish(conn, tmp_path / "map", now=NOW)
    server = Path(__file__).resolve().parent.parent
    example = load(server / "example_cells.json")
    full = next(c for c in example["cells"] if _complete(c))
    assert _shape({**example, "cells": [full]}) == _shape(cells)
    assert _shape(load(server / "example_cell.json")) == _shape(load(tmp_path / "map" / "cells" / "h26824_12473.json"))
    assert _shape(load(server / "example_reports.json")) == _shape(load(tmp_path / "map" / "reports.json"))
    assert 20 <= len(example["cells"]) <= 30
    assert len(load(server / "example_reports.json")["reports"]) >= 20


def test_reports_json_publishes_the_direction_in_whole_degrees_only(conn, tmp_path):
    """App 0.6.7 (§6.4): the direction of travel is published as whole degrees; north and the
    compass accuracy class are not; an «unreliable» compass reading is not published at all."""
    a = add_device(conn, None)
    t = datetime(2026, 10, 25, 9, 12)
    add_report(conn, a, t, 47.36, 8.54, direction={"bearing_deg": 87, "north": "true", "accuracy": "medium"})
    add_report(conn, a, t + timedelta(minutes=1), 47.37, 8.54, direction={"bearing_deg": 4, "north": "magnetic", "accuracy": "low"})
    add_report(conn, a, t + timedelta(minutes=2), 47.38, 8.54, direction={"bearing_deg": 200, "north": "true", "accuracy": "unreliable"})
    add_report(conn, a, t + timedelta(minutes=3), 47.39, 8.54)
    conn.commit()
    out = tmp_path / "map"
    publish.publish(conn, out, now=NOW)
    got = [(r["lat"], r["direction"]) for r in load(out / "reports.json")["reports"]]
    assert got == [(47.36, 87), (47.37, 4), (47.38, None), (47.39, None)]
    text = (out / "reports.json").read_text()
    for forbidden in ("magnetic", "medium", "unreliable", "north", "accuracy", "bearing"):
        assert forbidden not in text
