"""Schema v2 → v3 (DESIGN.md §5): existing rows are kept and mean what they meant before."""

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
    # Minutes and events in the v2 column set (no `cell`): write through a temporary v2 column list.
    monkeypatch.setattr(db, "MINUTE_COLUMNS", tuple(c for c in db.MINUTE_COLUMNS if c != "cell"))
    monkeypatch.setattr(db, "EVENT_COLUMNS", tuple(c for c in db.EVENT_COLUMNS if c != "cell"))
    original_minute_row, original_event_row = db.minute_row, db.event_row

    def v2_row(fn):
        def row(*a):
            r = fn(*a)
            r.pop("cell", None)
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

    assert db.migrate(conn) == 3
    assert conn.execute("SELECT version FROM schema_version").fetchone()[0] == 3
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
    assert db.migrate(conn) == 3

    # The old rows still belong to the site's hectare, and deleting the device still removes everything.
    cells = publish.publish(conn, tmp_path / "map", now=NOW)
    assert [c["cell"] for c in cells["cells"]] == ["h26824_12473"]
    assert cells["cells"][0]["day"]["measured_hours"] == 12.0
    db.delete_device(conn, "dev-a")
    assert all(conn.execute(f"SELECT COUNT(*) FROM {t}").fetchone()[0] == 0 for t in ("minutes", "events", "hourly"))
    assert json.loads(json.dumps(publish.publish(conn, tmp_path / "map", now=NOW)["cells"])) == []
    conn.close()
