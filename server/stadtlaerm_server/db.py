"""SQLite storage (DESIGN.md §5): connection setup, migrations by version, row access.

Times: `start_utc` / `time_utc` are UTC epoch **milliseconds** (events have millisecond
starts); `start_iso` / `time_iso` are the timestamps exactly as the app sent them. Bookkeeping
times (`created_at`, `received_at`, …) are ISO-8601 UTC strings.
"""

from __future__ import annotations

import json
import sqlite3
from collections.abc import Collection, Iterator, Sequence
from contextlib import contextmanager
from datetime import UTC, datetime
from pathlib import Path

from . import aggregate as agg
from .schemas import EventIn, MinuteIn, ReportIn

MIGRATIONS: list[str] = [
    # 1: the tables of DESIGN.md §5, plus `hourly` for the retention job.
    """
    CREATE TABLE devices (
        id TEXT PRIMARY KEY,
        token_hash TEXT NOT NULL,
        app_version TEXT,
        app_build INTEGER,
        created_at TEXT NOT NULL,
        last_seen_at TEXT,
        hidden INTEGER NOT NULL DEFAULT 0
    );
    CREATE TABLE sites (
        device_id TEXT PRIMARY KEY REFERENCES devices(id) ON DELETE CASCADE,
        cell TEXT NOT NULL,
        placement TEXT NOT NULL,
        floor INTEGER,
        street_facing INTEGER,
        device_model TEXT,
        audio_source TEXT,
        calibrated INTEGER,
        calibration_offset_db REAL,
        note TEXT,
        updated_at TEXT NOT NULL
    );
    CREATE INDEX sites_cell ON sites(cell);
    CREATE TABLE minutes (
        device_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
        start_utc INTEGER NOT NULL,
        start_iso TEXT NOT NULL,
        duration_s REAL NOT NULL,
        valid_s REAL NOT NULL,
        coverage REAL NOT NULL,
        laeq_db REAL, lafmax_db REAL, lafmin_db REAL,
        l1_db REAL, l10_db REAL, l50_db REAL, l90_db REAL,
        event_count INTEGER NOT NULL,
        dominant_category TEXT,
        category_shares TEXT NOT NULL,
        calibration_id TEXT,
        calibration_offset_db REAL,
        calibrated INTEGER NOT NULL,
        audio_source TEXT,
        clock_corrections INTEGER NOT NULL,
        received_at TEXT NOT NULL,
        PRIMARY KEY (device_id, start_utc)
    ) WITHOUT ROWID;
    CREATE INDEX minutes_start ON minutes(start_utc);
    CREATE TABLE events (
        device_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
        start_utc INTEGER NOT NULL,
        start_iso TEXT NOT NULL,
        duration_s REAL NOT NULL,
        lafmax_db REAL NOT NULL,
        sel_db REAL,
        background_db REAL,
        threshold_db REAL,
        min_level_db REAL,
        category TEXT NOT NULL,
        category_score REAL,
        calibration_id TEXT,
        calibrated INTEGER NOT NULL,
        received_at TEXT NOT NULL,
        PRIMARY KEY (device_id, start_utc)
    ) WITHOUT ROWID;
    CREATE INDEX events_start ON events(start_utc);
    CREATE TABLE hourly (
        device_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
        hour_utc INTEGER NOT NULL,
        valid_s REAL NOT NULL,
        valid_minutes INTEGER NOT NULL,
        laeq_db REAL, l90_db REAL, l10_db REAL, dynamics_db REAL,
        events TEXT NOT NULL,
        loudest_db REAL,
        calibrated INTEGER NOT NULL,
        PRIMARY KEY (device_id, hour_utc)
    ) WITHOUT ROWID;
    """,
    # 2: named integer counters for stats.json (DESIGN.md §6.5): `devices_deleted` (devices that
    # deleted themselves) and `devices_expired` (deleted after INACTIVE_DELETE_DAYS without uploads,
    # retention.expire_inactive) — one number each, no ids, no dates. No schema change needed.
    """
    CREATE TABLE counters (
        name TEXT PRIMARY KEY,
        value INTEGER NOT NULL
    );
    """,
    # 3: no more nights (DESIGN.md §6). Minutes and events may carry their own hectare (§4.3,
    # §4.4); existing rows keep NULL = "the device's site cell", exactly what they meant before.
    # `hourly` gets the cell in its key ('' = the site cell), so the retention job keeps the
    # hectare of a moving device; existing hourly rows keep '' . New table `reports` (§4.7).
    """
    ALTER TABLE minutes ADD COLUMN cell TEXT;
    ALTER TABLE events ADD COLUMN cell TEXT;
    CREATE TABLE hourly_v3 (
        device_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
        hour_utc INTEGER NOT NULL,
        cell TEXT NOT NULL DEFAULT '',
        valid_s REAL NOT NULL,
        valid_minutes INTEGER NOT NULL,
        laeq_db REAL, l90_db REAL, l10_db REAL, dynamics_db REAL,
        events TEXT NOT NULL,
        loudest_db REAL,
        calibrated INTEGER NOT NULL,
        PRIMARY KEY (device_id, hour_utc, cell)
    ) WITHOUT ROWID;
    INSERT INTO hourly_v3 (device_id, hour_utc, cell, valid_s, valid_minutes, laeq_db, l90_db, l10_db,
                           dynamics_db, events, loudest_db, calibrated)
        SELECT device_id, hour_utc, '', valid_s, valid_minutes, laeq_db, l90_db, l10_db,
               dynamics_db, events, loudest_db, calibrated FROM hourly;
    DROP TABLE hourly;
    ALTER TABLE hourly_v3 RENAME TO hourly;
    CREATE TABLE reports (
        device_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
        time_utc INTEGER NOT NULL,
        time_iso TEXT NOT NULL,
        lat REAL NOT NULL,
        lon REAL NOT NULL,
        accuracy_m REAL NOT NULL,
        category TEXT NOT NULL,
        received_at TEXT NOT NULL,
        PRIMARY KEY (device_id, time_utc)
    ) WITHOUT ROWID;
    CREATE INDEX reports_time ON reports(time_utc);
    """,
    # 4: location noise (DESIGN.md §2 rule 2, §4.3). Minutes, events and sites may carry the
    # shifted LV95 point the app sent (whole metres) and its σ; `cell` then holds the display cell,
    # the hectare that contains that point, so aggregation and the published files are unchanged.
    # Existing rows keep NULL points and their cell (or NULL = the site cell), as before.
    """
    ALTER TABLE minutes ADD COLUMN point_e INTEGER;
    ALTER TABLE minutes ADD COLUMN point_n INTEGER;
    ALTER TABLE minutes ADD COLUMN sigma_m INTEGER;
    ALTER TABLE events ADD COLUMN point_e INTEGER;
    ALTER TABLE events ADD COLUMN point_n INTEGER;
    ALTER TABLE events ADD COLUMN sigma_m INTEGER;
    ALTER TABLE sites ADD COLUMN point_e INTEGER;
    ALTER TABLE sites ADD COLUMN point_n INTEGER;
    ALTER TABLE sites ADD COLUMN sigma_m INTEGER;
    """,
]

MINUTE_COLUMNS = (
    "device_id", "start_utc", "start_iso", "duration_s", "valid_s", "coverage",
    "laeq_db", "lafmax_db", "lafmin_db", "l1_db", "l10_db", "l50_db", "l90_db",
    "event_count", "dominant_category", "category_shares", "calibration_id",
    "calibration_offset_db", "calibrated", "audio_source", "clock_corrections", "received_at", "cell",
    "point_e", "point_n", "sigma_m",
)  # fmt: skip
EVENT_COLUMNS = (
    "device_id", "start_utc", "start_iso", "duration_s", "lafmax_db", "sel_db",
    "background_db", "threshold_db", "min_level_db", "category", "category_score",
    "calibration_id", "calibrated", "received_at", "cell", "point_e", "point_n", "sigma_m",
)  # fmt: skip
REPORT_COLUMNS = ("device_id", "time_utc", "time_iso", "lat", "lon", "accuracy_m", "category", "received_at")
# Reports: 5 decimals of a degree ≈ 1.1 m north–south, 0.75 m east–west in Switzerland.
REPORT_DECIMALS = 5


def utc_now_iso() -> str:
    return datetime.now(UTC).isoformat(timespec="seconds")


def connect(path: Path | str) -> sqlite3.Connection:
    if str(path) != ":memory:":
        Path(path).parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(path, timeout=10, check_same_thread=False)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("PRAGMA synchronous=NORMAL")
    conn.execute("PRAGMA foreign_keys=ON")
    conn.execute("PRAGMA busy_timeout=10000")
    return conn


def migrate(conn: sqlite3.Connection) -> int:
    """Applies every migration newer than the stored version; returns the new version."""
    conn.execute("CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL)")
    row = conn.execute("SELECT version FROM schema_version").fetchone()
    if row is None:
        conn.execute("INSERT INTO schema_version (version) VALUES (0)")
        conn.commit()
        version = 0
    else:
        version = row[0]
    for i, script in enumerate(MIGRATIONS[version:], start=version + 1):
        # executescript commits first; wrap each step so a failure leaves the old version.
        conn.executescript(f"BEGIN;\n{script}\nUPDATE schema_version SET version = {i};\nCOMMIT;")
        version = i
    return version


@contextmanager
def open_db(path: Path | str) -> Iterator[sqlite3.Connection]:
    conn = connect(path)
    try:
        yield conn
    finally:
        conn.close()


# ---- writes -----------------------------------------------------------------------------


def upsert_rows(
    conn: sqlite3.Connection, table: str, columns: Sequence[str], rows: Sequence[dict], key: str = "start_utc"
) -> tuple[int, int]:
    """Insert-or-replace on (device_id, key). Returns (new rows, replaced rows).

    A record whose time already exists — stored earlier or earlier in the same batch — counts
    as a duplicate; the later one wins.
    """
    if not rows:
        return 0, 0
    device_id = rows[0]["device_id"]
    starts = [r[key] for r in rows]
    seen: set[int] = set()
    for i in range(0, len(starts), 900):
        chunk = starts[i : i + 900]
        q = f"SELECT {key} FROM {table} WHERE device_id = ? AND {key} IN ({','.join('?' * len(chunk))})"
        seen.update(r[0] for r in conn.execute(q, [device_id, *chunk]))
    accepted = duplicates = 0
    for s in starts:
        if s in seen:
            duplicates += 1
        else:
            accepted += 1
            seen.add(s)
    cols = ", ".join(columns)
    marks = ", ".join(f":{c}" for c in columns)
    updates = ", ".join(f"{c} = excluded.{c}" for c in columns if c not in ("device_id", key))
    conn.executemany(
        f"INSERT INTO {table} ({cols}) VALUES ({marks}) ON CONFLICT (device_id, {key}) DO UPDATE SET {updates}",
        rows,
    )
    return accepted, duplicates


def delete_device_rows(conn: sqlite3.Connection, device_id: str) -> bool:
    """Deletes every row of the device; the caller holds the transaction. True if a device row existed."""
    for table in ("minutes", "events", "hourly", "reports", "sites"):
        conn.execute(f"DELETE FROM {table} WHERE device_id = ?", (device_id,))
    return conn.execute("DELETE FROM devices WHERE id = ?", (device_id,)).rowcount > 0


def bump_counter(conn: sqlite3.Connection, name: str) -> None:
    conn.execute(
        "INSERT INTO counters (name, value) VALUES (?, 1) ON CONFLICT (name) DO UPDATE SET value = value + 1",
        (name,),
    )


def delete_device(conn: sqlite3.Connection, device_id: str) -> None:
    """Every row of the device, in one transaction (DESIGN.md §2.4, §4.5; reports §4.7).

    The only thing left behind is +1 on the anonymous counter `devices_deleted` (stats.json).
    """
    with conn:
        if delete_device_rows(conn, device_id):
            bump_counter(conn, "devices_deleted")


# ---- reads for aggregation ---------------------------------------------------------------


def _utc_hours(utc_hours: Collection[int] | None) -> tuple[str, list[int]]:
    """SQL filter on the UTC clock hour (0–23) of `start_utc`, for [load_minutes] / [load_events]."""
    if utc_hours is None:
        return "", []
    hours = sorted(set(utc_hours))
    return f" AND (start_utc / {agg.HOUR_MS}) % 24 IN ({','.join('?' * len(hours))})", hours


def load_minutes(
    conn: sqlite3.Connection,
    device_id: str,
    start_ms: int,
    end_ms: int,
    default_cell: str | None = None,
    utc_hours: Collection[int] | None = None,
) -> list[agg.Minute]:
    """`cell` is the record's own hectare, else `default_cell` (the publisher passes the site cell).
    With `utc_hours`, only the minutes that start in those UTC clock hours (0–23)."""
    where, hours = _utc_hours(utc_hours)
    # Plain tuples straight into the NamedTuple (no sqlite3.Row): this runs once per stored minute.
    cur = conn.cursor()
    cur.row_factory = None
    cur.execute(
        "SELECT start_utc, valid_s, coverage, laeq_db, l10_db, l90_db, calibrated <> 0, COALESCE(cell, ?) "
        f"FROM minutes WHERE device_id = ? AND start_utc >= ? AND start_utc < ?{where} ORDER BY start_utc",
        (default_cell, device_id, start_ms, end_ms, *hours),
    )
    return list(map(agg.Minute._make, cur))


def load_events(
    conn: sqlite3.Connection,
    device_id: str,
    start_ms: int,
    end_ms: int,
    default_cell: str | None = None,
    utc_hours: Collection[int] | None = None,
) -> list[agg.Event]:
    where, hours = _utc_hours(utc_hours)
    cur = conn.cursor()
    cur.row_factory = None
    cur.execute(
        "SELECT start_utc, lafmax_db, category, COALESCE(cell, ?) FROM events "
        f"WHERE device_id = ? AND start_utc >= ? AND start_utc < ?{where} ORDER BY start_utc",
        (default_cell, device_id, start_ms, end_ms, *hours),
    )
    return list(map(agg.Event._make, cur))


def _place(d: dict, rec: MinuteIn | EventIn) -> None:
    """`point` → the columns point_e, point_n, sigma_m, and `cell` = the display cell (§4.3)."""
    del d["point"]
    p = rec.point
    d.update(
        cell=rec.display_cell,
        point_e=p.e if p else None,
        point_n=p.n if p else None,
        sigma_m=p.sigma_m if p else None,
    )


def minute_row(device_id: str, m: MinuteIn, received_at: str) -> dict:
    d = m.model_dump()
    del d["start"]
    _place(d, m)
    d.update(
        device_id=device_id, start_utc=m.start_ms, start_iso=m.start, received_at=received_at,
        category_shares=json.dumps(m.category_shares, separators=(",", ":")), calibrated=int(m.calibrated),
    )  # fmt: skip
    return d


def event_row(device_id: str, e: EventIn, received_at: str) -> dict:
    d = e.model_dump()
    del d["start"]
    _place(d, e)
    d.update(
        device_id=device_id,
        start_utc=e.start_ms,
        start_iso=e.start,
        received_at=received_at,
        calibrated=int(e.calibrated),
    )
    return d


def report_row(device_id: str, r: ReportIn, received_at: str) -> dict:
    return {
        "device_id": device_id,
        "time_utc": r.start_ms,
        "time_iso": r.time,
        "lat": round(r.lat, REPORT_DECIMALS),
        "lon": round(r.lon, REPORT_DECIMALS),
        "accuracy_m": round(r.accuracy_m, 1),
        "category": r.category,
        "received_at": received_at,
    }


def hour_row(device_id: str, h: agg.DeviceHour, cell: str | None = None) -> dict:
    return {
        "device_id": device_id,
        "hour_utc": h.hour_ms,
        "cell": cell or "",
        "valid_s": h.valid_s,
        "valid_minutes": h.valid_minutes,
        "laeq_db": h.laeq_db,
        "l90_db": h.l90_db,
        "l10_db": h.l10_db,
        "dynamics_db": h.dynamics_db,
        "events": json.dumps(h.events, separators=(",", ":")),
        "loudest_db": h.loudest_db,
        "calibrated": int(h.calibrated),
    }
