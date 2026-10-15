"""Project-wide usage figures (DESIGN.md §6.5): `stats.json` next to the map files.

Only counts over the whole network: no device ids, no per-device rows, no cells, no places,
no IP addresses (the server stores none). Computed from the data the server already keeps
for the map; nothing new is collected for it — except two integers in the `counters` table:
`devices_deleted`, incremented when a device deletes itself (db.delete_device), and
`devices_expired`, incremented when the server deletes a device after INACTIVE_DELETE_DAYS
without uploads (retention.expire_inactive).

Definitions (all "now" = the publish time):

- `devices_registered`: rows in `devices`, hidden ones included (deleted devices are gone).
- `devices_with_site`: of those, the ones that have sent a site (a hectare).
- `devices_ever_shared`: devices with at least one minute record, counting those whose old
  minutes the retention job already reduced to `hourly` rows.
- `devices_active_7d` / `_30d`: devices with a valid minute (the map's rule, aggregate.Minute.valid:
  coverage ≥ 50 %, a level, valid time > 0) that started in the last 7 / 30 days. Same rule as
  `network.devices_active_7d` in cells.json, but over every device (hidden and site-less ones
  included), so it can be larger than the map's figure.
- `devices_deleted_total`: how many devices have deleted their data on the server since the
  counter exists (one integer, no ids, no dates).
- `devices_expired_total`: how many devices the server deleted completely because they sent
  nothing for INACTIVE_DELETE_DAYS (60) days (one integer, no ids, no dates). Kept apart from
  `devices_deleted_total`, which counts deletions the device asked for.
- `cells_with_data_30d`: hectares shown on the current map (cells.json), i.e. with data in the
  30 calendar days up to today, after MIN_DEVICES_PER_CELL.
- `days_shared_total`: device-days (local calendar days, Europe/Zurich) with at least one valid
  minute, including days already reduced to hourly rows (an hour with valid minutes).
- `app_versions`: devices active in the last 30 days per `app_version`, the version the app
  reported when it registered (the server is not told about later updates). Strings that do not
  look like a version number are counted as "andere", a missing one as "unbekannt".
- `registrations_by_week`: devices created per ISO week (Europe/Zurich), the last 12 weeks
  including the current one, oldest first, zeros included. Only devices that still exist.
"""

from __future__ import annotations

import re
import sqlite3
from collections import Counter
from collections.abc import Iterable, Mapping
from dataclasses import dataclass, field
from datetime import UTC, date, datetime, timedelta

from . import aggregate as agg

ACTIVE_SHORT_DAYS = 7
ACTIVE_LONG_DAYS = 30
WEEKS = 12
VERSION_RE = re.compile(r"^[0-9]{1,3}\.[0-9]{1,3}(\.[0-9]{1,3})?(-[a-z]{1,12})?$")

# SQL form of aggregate.Minute.valid (kept in step by tests/test_stats.py).
VALID_MINUTE_SQL = f"coverage >= {agg.MIN_MINUTE_COVERAGE} AND laeq_db IS NOT NULL AND valid_s > 0"


def _count(conn: sqlite3.Connection, sql: str, *args: object) -> int:
    return int(conn.execute(sql, args).fetchone()[0])


def _active_ids(conn: sqlite3.Connection, since_ms: int, now_ms: int) -> set[str]:
    rows = conn.execute(
        f"SELECT DISTINCT device_id FROM minutes WHERE start_utc >= ? AND start_utc <= ? AND {VALID_MINUTE_SQL}",
        (since_ms, now_ms),
    )
    return {r[0] for r in rows}


def _version_key(v: str | None) -> str:
    if v is None or v == "":
        return "unbekannt"
    return v if VERSION_RE.match(v) else "andere"


def _iso_week(d: date) -> str:
    y, w, _ = d.isocalendar()
    return f"{y}-W{w:02d}"


def _created_local_date(created_at: str | None) -> date | None:
    if not created_at:
        return None
    try:
        t = datetime.fromisoformat(created_at)
    except ValueError:
        return None
    if t.tzinfo is None:
        t = t.replace(tzinfo=UTC)
    return t.astimezone(agg.ZONE).date()


def _since(now: datetime, days: int) -> int:
    return int((now - timedelta(days=days)).timestamp() * 1000)


@dataclass
class Scan:
    """The figures that read every minute row: the active devices and the device-days.

    A full publish run makes one with [scan]; the runs in between keep it and [refresh] it for
    the devices that sent records since (publish.Publisher), so a 30-second run does not read
    the whole `minutes` table. Devices that merely fall out of the 7/30-day windows are noticed
    at the next full run (at most 10 minutes later)."""

    active_7: set[str] = field(default_factory=set)
    active_30: set[str] = field(default_factory=set)
    days: set[tuple[str, date]] = field(default_factory=set)


def scan(conn: sqlite3.Connection, now: datetime) -> Scan:
    now_ms = int(now.timestamp() * 1000)
    # Device-days with a valid minute: distinct (device, hour) first, then hour → local day. Swiss
    # offsets are whole hours, so every minute of a UTC hour belongs to the same local day.
    device_hours = conn.execute(
        f"SELECT DISTINCT device_id, start_utc / {agg.HOUR_MS} FROM minutes WHERE {VALID_MINUTE_SQL} "
        f"UNION SELECT device_id, hour_utc / {agg.HOUR_MS} FROM hourly WHERE valid_minutes > 0"
    )
    return Scan(
        active_7=_active_ids(conn, _since(now, ACTIVE_SHORT_DAYS), now_ms),
        active_30=_active_ids(conn, _since(now, ACTIVE_LONG_DAYS), now_ms),
        days={(dev, agg.day_of(int(h) * agg.HOUR_MS)) for dev, h in device_hours},
    )


def refresh(conn: sqlite3.Connection, s: Scan, now: datetime, touched: Mapping[str, Iterable[date]]) -> None:
    """Updates `s` for the devices in `touched` (device id → local days that got records), with
    the same rules as [scan]: exact for those devices and days, read through the primary keys."""
    now_ms = int(now.timestamp() * 1000)
    valid_in = f"SELECT 1 FROM minutes WHERE device_id = ? AND start_utc >= ? AND start_utc < ? AND {VALID_MINUTE_SQL}"
    hour_in = "SELECT 1 FROM hourly WHERE device_id = ? AND hour_utc >= ? AND hour_utc < ? AND valid_minutes > 0"
    for dev, days in touched.items():
        for since, ids in ((_since(now, ACTIVE_SHORT_DAYS), s.active_7), (_since(now, ACTIVE_LONG_DAYS), s.active_30)):
            if conn.execute(valid_in + " LIMIT 1", (dev, since, now_ms + 1)).fetchone():
                ids.add(dev)
            else:
                ids.discard(dev)
        for d in days:
            a, b = agg.day_bounds(d)
            if (
                conn.execute(valid_in + " LIMIT 1", (dev, a, b)).fetchone()
                or conn.execute(hour_in + " LIMIT 1", (dev, a, b)).fetchone()
            ):
                s.days.add((dev, d))
            else:
                s.days.discard((dev, d))


def compute(conn: sqlite3.Connection, now: datetime, cells_with_data_30d: int, scanned: Scan | None = None) -> dict:
    """The stats.json object. `cells_with_data_30d` comes from the cells.json of the same run;
    `scanned` is a [Scan] kept up to date by the caller (default: scan now)."""
    sc = scanned if scanned is not None else scan(conn, now)
    active_30 = sc.active_30
    versions: Counter[str] = Counter()
    if active_30:
        for device_id, version in conn.execute("SELECT id, app_version FROM devices"):
            if device_id in active_30:
                versions[_version_key(version)] += 1
    days = sc.days

    today = now.astimezone(agg.ZONE).date()
    this_monday = today - timedelta(days=today.weekday())
    weeks = [_iso_week(this_monday - timedelta(weeks=i)) for i in range(WEEKS - 1, -1, -1)]
    first_monday = this_monday - timedelta(weeks=WEEKS - 1)
    per_week: Counter[str] = Counter()
    for (created_at,) in conn.execute("SELECT created_at FROM devices"):
        d = _created_local_date(created_at)
        if d is not None and first_monday <= d <= today:
            per_week[_iso_week(d)] += 1

    counters = {name: int(value) for name, value in conn.execute("SELECT name, value FROM counters")}

    return {
        "generated_at": now.astimezone(agg.ZONE).isoformat(timespec="seconds"),
        "devices_registered": _count(conn, "SELECT COUNT(*) FROM devices"),
        "devices_with_site": _count(conn, "SELECT COUNT(*) FROM devices d JOIN sites s ON s.device_id = d.id"),
        "devices_ever_shared": _count(
            conn,
            "SELECT COUNT(*) FROM devices d WHERE EXISTS (SELECT 1 FROM minutes m WHERE m.device_id = d.id) "
            "OR EXISTS (SELECT 1 FROM hourly h WHERE h.device_id = d.id)",
        ),
        "devices_active_7d": len(sc.active_7),
        "devices_active_30d": len(active_30),
        "devices_deleted_total": counters.get("devices_deleted", 0),
        "devices_expired_total": counters.get("devices_expired", 0),
        "cells_with_data_30d": cells_with_data_30d,
        "days_shared_total": len(days),
        "app_versions": dict(sorted(versions.items())),
        "registrations_by_week": [{"week": w, "devices": per_week.get(w, 0)} for w in weeks],
    }
