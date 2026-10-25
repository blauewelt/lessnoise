"""Schema migrations (DESIGN.md §5): existing rows are kept and mean what they meant before.

v2 → v3: records get an optional own hectare; v3 → v4: records and sites get an optional shifted
point (location noise, §2 rule 2, §4.3); v4 → v5: events of categories other than the five map
categories are deleted, `counted_since` recorded and published (§5, §6)."""

from __future__ import annotations

import json
import logging
from datetime import datetime
from pathlib import Path

from test_publish import LAST, NOW, add_day

from stadtlaerm_server import db, publish
from stadtlaerm_server.config import MAP_CATEGORIES


def test_v2_database_is_migrated_and_keeps_its_rows(tmp_path: Path, monkeypatch):
    path = tmp_path / "db.sqlite"
    conn = db.connect(path)
    # A database as schema v2 left it (the live server before this change).
    monkeypatch.setattr(db, "MIGRATIONS", db.MIGRATIONS[:2])
    assert db.migrate(conn) == 2
    assert "cell" not in {r[1] for r in conn.execute("PRAGMA table_info(minutes)")}
    conn.execute("INSERT INTO devices (id, token_hash, created_at, last_seen_at) VALUES ('dev-a', 'h', 'c', 'l')")
    conn.execute(
        "INSERT INTO sites (device_id, cell, placement, device_model, audio_source, calibrated, updated_at) "
        "VALUES ('dev-a', 'h26824_12473', 'open_window', 'Pixel 8', 'UNPROCESSED', 1, '')"
    )
    # Minutes and events in the v2 column set (no `cell`, no point): write through a temporary v2 column list.
    later = ("cell", "point_e", "point_n", "sigma_m")
    monkeypatch.setattr(db, "MINUTE_COLUMNS", tuple(c for c in db.MINUTE_COLUMNS if c not in later))
    monkeypatch.setattr(db, "EVENT_COLUMNS", tuple(c for c in db.EVENT_COLUMNS if c not in later))
    original_minute_row, original_event_row = db.minute_row, db.event_row

    def v2_row(fn):
        def row(*a):
            r = fn(*a)
            for c in later:
                r.pop(c, None)
            return r

        return row

    monkeypatch.setattr(db, "minute_row", v2_row(original_minute_row))
    monkeypatch.setattr(db, "event_row", v2_row(original_event_row))
    add_day(conn, "dev-a", LAST, 42.0, 4.0, 1)
    conn.execute(
        "INSERT INTO hourly (device_id, hour_utc, valid_s, valid_minutes, laeq_db, events, calibrated) "
        "VALUES ('dev-a', 3600000, 3600, 60, 41.5, '{\"voices\":2}', 1)"
    )
    conn.execute("INSERT INTO counters (name, value) VALUES ('devices_deleted', 3)")
    conn.commit()
    before = {t: conn.execute(f"SELECT COUNT(*) FROM {t}").fetchone()[0] for t in ("minutes", "events", "hourly")}
    assert before["minutes"] == 720 and before["events"] > 0 and before["hourly"] == 1
    voices = conn.execute("SELECT COUNT(*) FROM events WHERE category = 'voices'").fetchone()[0]
    assert voices > 0
    monkeypatch.undo()

    latest = len(db.MIGRATIONS)
    assert db.migrate(conn) == latest == 8
    assert conn.execute("SELECT version FROM schema_version").fetchone()[0] == latest
    after = {t: conn.execute(f"SELECT COUNT(*) FROM {t}").fetchone()[0] for t in ("minutes", "events", "hourly")}
    # Every row stays, except the events of other categories (v5).
    assert after == {**before, "events": before["events"] - voices}
    assert conn.execute("SELECT COUNT(*) FROM minutes WHERE cell IS NOT NULL").fetchone()[0] == 0
    assert conn.execute("SELECT COUNT(*) FROM events WHERE cell IS NOT NULL").fetchone()[0] == 0
    assert tuple(conn.execute("SELECT device_id, hour_utc, cell, laeq_db, events FROM hourly").fetchone()) == (
        "dev-a",
        3600000,
        "",
        41.5,
        "{}",  # v5: the hourly row stays, its count of another category goes
    )
    assert conn.execute("SELECT value FROM counters").fetchone()[0] == 3
    assert conn.execute("SELECT COUNT(*) FROM reports").fetchone()[0] == 0
    assert conn.execute("PRAGMA foreign_key_check").fetchall() == []
    assert conn.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
    # Running it again changes nothing.
    assert db.migrate(conn) == latest

    # The old rows still belong to the site's hectare, and deleting the device still removes everything.
    cells = publish.publish(conn, tmp_path / "map", now=NOW)
    assert [c["cell"] for c in cells["cells"]] == ["h26824_12473"]
    assert cells["cells"][0]["day"]["measured_hours"] == 12.0
    db.delete_device(conn, "dev-a")
    assert all(conn.execute(f"SELECT COUNT(*) FROM {t}").fetchone()[0] == 0 for t in ("minutes", "events", "hourly"))
    assert json.loads(json.dumps(publish.publish(conn, tmp_path / "map", now=NOW)["cells"])) == []
    conn.close()


def test_v3_database_is_migrated_to_v4_and_keeps_its_rows(tmp_path: Path, monkeypatch):
    """v3 → v4 (location noise): every row stays, old rows get NULL points and keep their cell."""
    path = tmp_path / "db.sqlite"
    conn = db.connect(path)
    monkeypatch.setattr(db, "MIGRATIONS", db.MIGRATIONS[:3])
    assert db.migrate(conn) == 3
    assert "point_e" not in {r[1] for r in conn.execute("PRAGMA table_info(minutes)")}
    conn.execute("INSERT INTO devices (id, token_hash, created_at, last_seen_at) VALUES ('dev-a', 'h', 'c', 'l')")
    conn.execute("INSERT INTO devices (id, token_hash, created_at, last_seen_at) VALUES ('dev-b', 'h', 'c', 'l')")
    conn.execute(
        "INSERT INTO sites (device_id, cell, placement, device_model, audio_source, calibrated, updated_at) "
        "VALUES ('dev-a', 'h26824_12473', 'open_window', 'Pixel 8', 'UNPROCESSED', 1, '')"
    )
    later = ("point_e", "point_n", "sigma_m")
    monkeypatch.setattr(db, "MINUTE_COLUMNS", tuple(c for c in db.MINUTE_COLUMNS if c not in later))
    monkeypatch.setattr(db, "EVENT_COLUMNS", tuple(c for c in db.EVENT_COLUMNS if c not in later))
    original_minute_row, original_event_row = db.minute_row, db.event_row

    def v3_row(fn):
        def row(*a):
            r = fn(*a)
            for c in later:
                r.pop(c, None)
            return r

        return row

    monkeypatch.setattr(db, "minute_row", v3_row(original_minute_row))
    monkeypatch.setattr(db, "event_row", v3_row(original_event_row))
    add_day(conn, "dev-a", LAST, 42.0, 4.0, 1)  # an app up to 0.5.1: no cell, the site's hectare
    add_day(conn, "dev-b", LAST, 50.0, 6.0, 2, cell="h26900_12500")  # app 0.6.0/0.6.1: own hectare
    conn.execute(
        "INSERT INTO hourly (device_id, hour_utc, cell, valid_s, valid_minutes, laeq_db, events, calibrated) "
        "VALUES ('dev-a', 3600000, '', 3600, 60, 41.5, '{}', 1)"
    )
    conn.execute(
        "INSERT INTO reports (device_id, time_utc, time_iso, lat, lon, accuracy_m, category, received_at) "
        "VALUES ('dev-b', 1, 't', 47.37691, 8.54169, 12.3, 'auto', 'r')"
    )
    conn.commit()
    tables = ("devices", "sites", "minutes", "events", "hourly", "reports")
    before = {t: conn.execute(f"SELECT COUNT(*) FROM {t}").fetchone()[0] for t in tables}
    cells_before = conn.execute("SELECT cell, COUNT(*) FROM minutes GROUP BY cell ORDER BY cell").fetchall()
    monkeypatch.undo()

    monkeypatch.setattr(db, "MIGRATIONS", db.MIGRATIONS[:4])
    assert db.migrate(conn) == 4
    monkeypatch.undo()
    assert {t: conn.execute(f"SELECT COUNT(*) FROM {t}").fetchone()[0] for t in tables} == before
    assert conn.execute("SELECT cell, COUNT(*) FROM minutes GROUP BY cell ORDER BY cell").fetchall() == cells_before
    for table in ("minutes", "events", "sites"):
        cols = {r[1] for r in conn.execute(f"PRAGMA table_info({table})")}
        assert set(later) <= cols, table
        assert (
            conn.execute(f"SELECT COUNT(*) FROM {table} WHERE point_e IS NOT NULL OR sigma_m IS NOT NULL").fetchone()[0]
            == 0
        )
    assert conn.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
    monkeypatch.setattr(db, "MIGRATIONS", db.MIGRATIONS[:4])
    assert db.migrate(conn) == 4
    monkeypatch.undo()

    # Old rows are shown exactly as before: the site's hectare and the record's own hectare.
    cells = publish.publish(conn, tmp_path / "map", now=NOW)
    assert sorted(c["cell"] for c in cells["cells"]) == ["h26824_12473", "h26900_12500"]
    conn.close()


def _event(conn, dev: str, start_ms: int, category: str) -> None:
    conn.execute(
        "INSERT INTO events (device_id, start_utc, start_iso, duration_s, lafmax_db, category, calibrated, received_at) "
        "VALUES (?, ?, 't', 2.0, 70.0, ?, 1, 'r')",
        (dev, start_ms, category),
    )


def test_v4_to_v5_deletes_events_of_other_categories(tmp_path: Path, monkeypatch, caplog):
    """v4 → v5 (owner's decision of 2026-10-16): stored events whose category is not one of the
    five map categories are deleted, not just ignored; everything else stays; `counted_since` is
    the local day of the migration and is published in cells.json, day files and cell files."""
    conn = db.connect(tmp_path / "db.sqlite")
    monkeypatch.setattr(db, "MIGRATIONS", db.MIGRATIONS[:4])
    assert db.migrate(conn) == 4
    monkeypatch.undo()
    assert db.counted_since(conn) is None  # no `meta` table before v5
    conn.execute("INSERT INTO devices (id, token_hash, created_at, last_seen_at) VALUES ('dev-a', 'h', 'c', 'l')")
    conn.execute(
        "INSERT INTO sites (device_id, cell, placement, device_model, audio_source, calibrated, updated_at) "
        "VALUES ('dev-a', 'h26824_12473', 'open_window', 'Pixel 8', 'UNPROCESSED', 1, '')"
    )
    add_day(conn, "dev-a", LAST, 42.0, 4.0, 1)  # road_traffic, loud_vehicle and voices events
    others = ("voices", "music", "unclassified", "wind", "something_new")
    for i, cat in enumerate(others):
        _event(conn, "dev-a", 1_000 + i, cat)
    for i, cat in enumerate(MAP_CATEGORIES):
        _event(conn, "dev-a", 2_000 + i, cat)
    conn.execute(
        "INSERT INTO hourly (device_id, hour_utc, cell, valid_s, valid_minutes, laeq_db, events, calibrated) "
        "VALUES ('dev-a', 3600000, '', 3600, 60, 41.5, '{\"road_traffic\":3,\"voices\":2,\"music\":1}', 1)"
    )
    conn.execute(
        "INSERT INTO reports (device_id, time_utc, time_iso, lat, lon, accuracy_m, category, received_at) "
        "VALUES ('dev-a', 1, 't', 47.37691, 8.54169, 12.3, 'andere', 'r')"
    )
    conn.commit()
    marks = ",".join("?" * len(MAP_CATEGORIES))
    kept_before = conn.execute(f"SELECT COUNT(*) FROM events WHERE category IN ({marks})", MAP_CATEGORIES).fetchone()[0]
    other_before = conn.execute(
        f"SELECT COUNT(*) FROM events WHERE category NOT IN ({marks})", MAP_CATEGORIES
    ).fetchone()[0]
    assert other_before > len(others)  # add_day's voices too
    minutes_before = conn.execute("SELECT COUNT(*) FROM minutes").fetchone()[0]
    map_dir = tmp_path / "map"
    cells_before = publish.publish(conn, map_dir, now=NOW)
    assert cells_before["counted_since"] is None

    deploy = datetime.fromisoformat("2026-10-16T00:30:00+02:00")  # 22:30 UTC the day before
    with caplog.at_level(logging.INFO, logger="stadtlaerm"):
        assert db.migrate(conn, now=deploy) == 8
    assert f"deleted {other_before} stored events of other categories, trimmed 1 hourly rows" in caplog.text
    assert "dev-a" not in caplog.text  # counts only, no device id

    assert (
        conn.execute(f"SELECT COUNT(*) FROM events WHERE category NOT IN ({marks})", MAP_CATEGORIES).fetchone()[0] == 0
    )
    assert conn.execute("SELECT COUNT(*) FROM events").fetchone()[0] == kept_before
    assert conn.execute("SELECT COUNT(*) FROM minutes").fetchone()[0] == minutes_before  # minutes untouched
    assert [tuple(r) for r in conn.execute("SELECT category FROM reports")] == [("andere",)]  # reports untouched
    assert tuple(conn.execute("SELECT events, laeq_db FROM hourly").fetchone()) == ('{"road_traffic":3}', 41.5)
    assert db.counted_since(conn) == "2026-10-16"  # the local (Zürich) day, not the UTC one
    assert conn.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
    # Running it again changes nothing.
    assert db.migrate(conn) == 8 and db.counted_since(conn) == "2026-10-16"

    # The map does not change (those events were already ignored), except for `counted_since`.
    cells = publish.publish(conn, map_dir, now=NOW)
    assert cells["counted_since"] == "2026-10-16"
    assert {**cells, "counted_since": None, "generated_at": 0} == {**cells_before, "generated_at": 0}
    cell = json.loads((map_dir / "cells" / "h26824_12473.json").read_text())
    assert cell["counted_since"] == "2026-10-16"
    day = json.loads((map_dir / "days" / f"{LAST.isoformat()}.json").read_text())
    assert day["counted_since"] == "2026-10-16"
    conn.close()


def test_failed_python_step_leaves_the_old_version(tmp_path: Path, monkeypatch):
    conn = db.connect(tmp_path / "db.sqlite")
    monkeypatch.setattr(db, "MIGRATIONS", db.MIGRATIONS[:4])
    assert db.migrate(conn) == 4

    def broken(c, now):
        c.execute("DELETE FROM events")
        raise RuntimeError("boom")

    monkeypatch.setattr(db, "MIGRATIONS", [*db.MIGRATIONS[:4], broken])
    try:
        db.migrate(conn)
    except RuntimeError:
        pass
    assert db.schema_version(conn) == 4
    monkeypatch.undo()
    assert db.migrate(conn) == 8
    conn.close()


def test_app_start_after_v5_rebuilds_the_published_files(tmp_path: Path, monkeypatch):
    """An existing database crossing v5 gets its map files rewritten when the app starts, even
    without the background loop; a new database does not need that."""
    from stadtlaerm_server.app import create_app
    from stadtlaerm_server.config import Settings

    path = tmp_path / "db.sqlite"
    conn = db.connect(path)
    monkeypatch.setattr(db, "MIGRATIONS", db.MIGRATIONS[:4])
    db.migrate(conn)
    monkeypatch.undo()
    conn.close()
    settings = Settings(db_path=path, map_dir=tmp_path / "map", background_jobs=False)
    create_app(settings)
    cells = json.loads((tmp_path / "map" / "cells.json").read_text())
    assert cells["counted_since"] is not None and cells["counted_since"] == cells["counted_since"][:10]

    fresh = Settings(db_path=tmp_path / "new.sqlite", map_dir=tmp_path / "map2", background_jobs=False)
    create_app(fresh)
    assert not (tmp_path / "map2" / "cells.json").exists()


def test_v5_database_gets_the_report_direction_columns_and_keeps_its_reports(tmp_path: Path, monkeypatch):
    """v5 → v6 (app 0.6.7): three nullable report columns; existing reports keep everything and
    read as «no direction»."""
    conn = db.connect(tmp_path / "db.sqlite")
    monkeypatch.setattr(db, "MIGRATIONS", db.MIGRATIONS[:5])
    assert db.migrate(conn) == 5
    conn.execute("INSERT INTO devices (id, token_hash, created_at, last_seen_at) VALUES ('dev-a', 'h', 'c', 'l')")
    conn.execute(
        "INSERT INTO reports (device_id, time_utc, time_iso, lat, lon, accuracy_m, category, received_at) "
        "VALUES ('dev-a', 1, 't', 47.37691, 8.54169, 12.3, 'andere', 'r')"
    )
    conn.commit()
    monkeypatch.undo()
    assert db.migrate(conn) == 8
    assert tuple(conn.execute("SELECT * FROM reports").fetchone()) == (
        "dev-a", 1, "t", 47.37691, 8.54169, 12.3, "andere", "r", None, None, None, None, None, None, None,
    )  # fmt: skip
    assert conn.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
    conn.close()


def test_v6_database_gets_the_empty_newsletter_table_and_keeps_its_rows(tmp_path: Path, monkeypatch):
    """v6 → v7 (website form, 2026-10-20): a new table of two columns, linked to nothing; every
    existing row stays."""
    conn = db.connect(tmp_path / "db.sqlite")
    monkeypatch.setattr(db, "MIGRATIONS", db.MIGRATIONS[:6])
    assert db.migrate(conn) == 6
    conn.execute("INSERT INTO devices (id, token_hash, created_at, last_seen_at) VALUES ('dev-a', 'h', 'c', 'l')")
    conn.commit()
    monkeypatch.undo()
    assert db.migrate(conn) == 8
    assert [r[1] for r in conn.execute("PRAGMA table_info(newsletter)")] == ["email", "created_at"]
    assert conn.execute("SELECT COUNT(*) FROM newsletter").fetchone()[0] == 0
    assert conn.execute("PRAGMA foreign_key_list(newsletter)").fetchall() == []
    assert [tuple(r) for r in conn.execute("SELECT id FROM devices")] == [("dev-a",)]
    assert conn.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
    conn.close()


def test_v7_database_gets_the_report_level_columns_and_keeps_its_reports(tmp_path: Path, monkeypatch):
    """v7 → v8 (app 0.8.5): four nullable report columns for the level while the report dialog was
    open; existing reports keep everything, direction included, and read as «no level»."""
    conn = db.connect(tmp_path / "db.sqlite")
    monkeypatch.setattr(db, "MIGRATIONS", db.MIGRATIONS[:7])
    assert db.migrate(conn) == 7
    conn.execute("INSERT INTO devices (id, token_hash, created_at, last_seen_at) VALUES ('dev-a', 'h', 'c', 'l')")
    conn.execute(
        "INSERT INTO reports (device_id, time_utc, time_iso, lat, lon, accuracy_m, category, received_at, "
        "direction_deg, direction_north, direction_accuracy) "
        "VALUES ('dev-a', 1, 't', 47.37691, 8.54169, 12.3, 'poser', 'r', 87, 'true', 'high')"
    )
    conn.commit()
    monkeypatch.undo()
    assert db.migrate(conn) == 8
    cols = [r[1] for r in conn.execute("PRAGMA table_info(reports)")]
    assert cols[-4:] == ["level_lafmax_db", "level_laeq_db", "level_seconds", "level_calibrated"]
    assert tuple(cols) == db.REPORT_COLUMNS
    assert tuple(conn.execute("SELECT * FROM reports").fetchone()) == (
        "dev-a", 1, "t", 47.37691, 8.54169, 12.3, "poser", "r", 87, "true", "high", None, None, None, None,
    )  # fmt: skip
    assert conn.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
    conn.close()
