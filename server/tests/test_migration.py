"""Schema migrations (DESIGN.md §5): existing rows are kept and mean what they meant before.

v2 → v3: records get an optional own hectare; v3 → v4: records and sites get an optional shifted
point (location noise, §2 rule 2, §4.3)."""

from __future__ import annotations

import json
from pathlib import Path

from test_publish import LAST, NOW, add_day

from stadtlaerm_server import db, publish


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
    monkeypatch.undo()

    latest = len(db.MIGRATIONS)
    assert db.migrate(conn) == latest == 4
    assert conn.execute("SELECT version FROM schema_version").fetchone()[0] == latest
    after = {t: conn.execute(f"SELECT COUNT(*) FROM {t}").fetchone()[0] for t in ("minutes", "events", "hourly")}
    assert after == before
    assert conn.execute("SELECT COUNT(*) FROM minutes WHERE cell IS NOT NULL").fetchone()[0] == 0
    assert conn.execute("SELECT COUNT(*) FROM events WHERE cell IS NOT NULL").fetchone()[0] == 0
    assert tuple(conn.execute("SELECT device_id, hour_utc, cell, laeq_db, events FROM hourly").fetchone()) == (
        "dev-a",
        3600000,
        "",
        41.5,
        '{"voices":2}',
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

    assert db.migrate(conn) == 4
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
    assert db.migrate(conn) == 4

    # Old rows are shown exactly as before: the site's hectare and the record's own hectare.
    cells = publish.publish(conn, tmp_path / "map", now=NOW)
    assert sorted(c["cell"] for c in cells["cells"]) == ["h26824_12473", "h26900_12500"]
    conn.close()
