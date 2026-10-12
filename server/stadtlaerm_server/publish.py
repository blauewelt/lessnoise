"""Builds the public map files of DESIGN.md §6 from the database and writes them atomically.

Layout in MAP_DIR (served under /v1/map/):

    cells.json              every shown cell, windows ending today (§6.1)
    cells/{cell}.json       one cell: ≤ 90 days, newest first, plus the 24-hour profile (§6.2)
    days/{date}.json        cells.json as it was for each of the last 90 days (§6.3)
    reports.json            manual reports of the last 30 days, no device ids (§6.4)
    stats.json              project-wide usage counts, no places, no ids (§6.5, stats.py)

Only devices that are not `hidden` contribute. A record belongs to its own cell when the app
sent one (§4.3, §4.4), otherwise to the cell of the device's *current* site; records with
neither are not shown. Files of cells or days that are no longer shown are removed, so a
deleted device disappears from every file on the next run.
"""

from __future__ import annotations

import json
import os
import shutil
import sqlite3
import tempfile
from collections import defaultdict
from collections.abc import Iterable, Iterator
from dataclasses import dataclass
from datetime import UTC, date, datetime, timedelta
from pathlib import Path

from . import aggregate as agg
from . import db, stats
from .schemas import REPORT_CATEGORIES

HISTORY_DAYS = 90
WINDOW_LONG = 30
WINDOW_SHORT = 7
PROFILE_DAYS = 30
REPORT_DAYS = 30
ACTIVE_DAYS = 7
PERIODS = {"last_7_days": WINDOW_SHORT, "last_30_days": WINDOW_LONG}
DEFAULT_PERIOD = "last_7_days"


@dataclass(frozen=True)
class DeviceSite:
    device_id: str
    cell: str | None  # None: the device never sent a site (only records with their own cell count)
    device_model: str | None


@dataclass
class DeviceData:
    """Everything the publisher needs from one device, independent of the database.

    Every minute and event carries its resolved cell (its own, else the site's)."""

    site: DeviceSite
    minutes: list[agg.Minute]
    events: list[agg.Event]


def write_json_atomic(path: Path, obj: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp = tempfile.mkstemp(dir=path.parent, prefix=f".{path.name}.", suffix=".tmp")
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            json.dump(obj, f, ensure_ascii=False, separators=(",", ":"))
            f.flush()
            os.fsync(f.fileno())
        os.chmod(tmp, 0o644)
        os.replace(tmp, path)
    except BaseException:
        Path(tmp).unlink(missing_ok=True)
        raise


def load_devices(conn: sqlite3.Connection, start_ms: int, end_ms: int) -> Iterator[DeviceData]:
    """One device at a time, so memory stays at one device's raw rows however large the network."""
    rows = conn.execute(
        "SELECT d.id, s.cell, s.device_model FROM devices d LEFT JOIN sites s ON s.device_id = d.id "
        "WHERE d.hidden = 0 ORDER BY d.id"
    ).fetchall()
    for device_id, cell, model in rows:
        minutes = [m for m in db.load_minutes(conn, device_id, start_ms, end_ms, cell) if m.cell is not None]
        events = [e for e in db.load_events(conn, device_id, start_ms, end_ms, cell) if e.cell is not None]
        if minutes or events:
            yield DeviceData(DeviceSite(device_id, cell, model), minutes, events)


def _split(data: DeviceData) -> tuple[dict, dict]:
    """(cell, day) → minutes, (cell, day) → events."""
    mins: dict[tuple[str, date], list[agg.Minute]] = defaultdict(list)
    evs: dict[tuple[str, date], list[agg.Event]] = defaultdict(list)
    for m in data.minutes:
        mins[(m.cell, agg.day_of(m.start_ms))].append(m)  # type: ignore[index]
    for e in data.events:
        evs[(e.cell, agg.day_of(e.start_ms))].append(e)  # type: ignore[index]
    return mins, evs


def _cell_entry(index: agg.DayIndex, cell: str, day: date) -> dict | None:
    """The entry of `cell` in the file of `day`; None if the cell has no data in the 30 days."""
    long = index.window(cell, day, WINDOW_LONG)
    if all(d is None for d in long):
        return None
    w30 = agg.window_block(long)
    return {
        "cell": cell,
        "devices": w30["devices"],
        "calibrated": w30["calibrated"],
        "day": long[0].block() if long[0] is not None else None,
        "last_7_days": agg.window_block(long[:WINDOW_SHORT]),
        "last_30_days": w30,
    }


def _periods(day: date) -> dict:
    return {
        k: {"from": (day - timedelta(days=n - 1)).isoformat(), "to": day.isoformat(), "days": n}
        for k, n in PERIODS.items()
    }


def build(
    devices: Iterable[DeviceData], now: datetime, min_devices: int = 1, loud_db: float = agg.DEFAULT_LOUD_EVENT_DB
) -> tuple[dict, dict[str, dict], dict[str, dict]]:
    """Pure: device data → (cells.json, {cell: cells/{cell}.json}, {date: days/{date}.json})."""
    latest = agg.today(now)
    first_day = latest - timedelta(days=HISTORY_DAYS + WINDOW_LONG - 2)
    profile_from = latest - timedelta(days=PROFILE_DAYS - 1)
    profile_start = agg.day_bounds(profile_from)[0]
    generated_at = now.astimezone(agg.ZONE).isoformat(timespec="seconds")
    active_since = int((now - timedelta(days=ACTIVE_DAYS)).timestamp() * 1000)
    now_ms = int(now.timestamp() * 1000)

    # Pass over the devices: keep only their day summaries and their 24-hour profiles.
    per_cell_day: dict[str, dict[date, dict[str, agg.DeviceDay]]] = defaultdict(lambda: defaultdict(dict))
    profiles: dict[str, list[list[agg.DeviceProfileHour]]] = defaultdict(list)
    models: dict[str, int] = defaultdict(int)
    active = 0
    for d in devices:
        if any(active_since <= m.start_ms <= now_ms and m.valid for m in d.minutes):
            active += 1
            models[d.site.device_model or "unbekannt"] += 1
        mins, evs = _split(d)
        for (cell, day), ms in mins.items():
            if first_day <= day <= latest:
                s = agg.summarize_device_day(day, ms, evs.get((cell, day), []), loud_db)
                if s is not None:
                    per_cell_day[cell][day][d.site.device_id] = s
        for cell in {m.cell for m in d.minutes if m.start_ms >= profile_start and m.valid}:
            p_mins = [m for m in d.minutes if m.cell == cell and m.start_ms >= profile_start]
            p_evs = [e for e in d.events if e.cell == cell and e.start_ms >= profile_start]
            profiles[cell].append(agg.device_profile(p_mins, p_evs, loud_db))  # type: ignore[index]
    network = {"devices_active_7d": active, "device_models": dict(sorted(models.items()))}

    index = agg.DayIndex(min_devices=min_devices)
    for cell, days in per_cell_day.items():
        for day, device_days in days.items():
            index.add(cell, day, device_days)

    day_files = {}
    for i in range(HISTORY_DAYS):
        day = latest - timedelta(days=i)
        day_files[day.isoformat()] = {
            "generated_at": generated_at,
            "day": day.isoformat(),
            "default_period": DEFAULT_PERIOD,
            "periods": _periods(day),
            "min_devices_per_cell": min_devices,
            "loud_event_db": loud_db,
            "cells": [e for c in sorted(index.cells) if (e := _cell_entry(index, c, day)) is not None],
            "network": network,
        }
    cells_json = day_files[latest.isoformat()]

    # One history file per cell that appears in any of the 90 day files.
    cell_files = {}
    for cell in sorted({e["cell"] for f in day_files.values() for e in f["cells"]}):
        history = []
        for i in range(HISTORY_DAYS):
            day = latest - timedelta(days=i)
            if (c := index.get(cell, day)) is not None:
                history.append({"day": day.isoformat(), **c.block()})
        newest = history[0] if history else None
        cell_files[cell] = {
            "generated_at": generated_at,
            "cell": cell,
            "devices": newest["devices"] if newest else 0,
            "calibrated": newest["calibrated"] if newest else False,
            "loud_event_db": loud_db,
            "days": history,
            "profile": {
                "from": profile_from.isoformat(),
                "to": latest.isoformat(),
                "hours": agg.combine_profile(profiles.get(cell, []), min_devices),
            },
        }
    return cells_json, cell_files, day_files


def build_reports(rows: Iterable[tuple[int, float, float, str]], now: datetime) -> dict:
    """reports.json (§6.4) from (time_utc ms, lat, lon, category) of non-hidden devices.

    The last REPORT_DAYS calendar days up to today. Each time is rounded *down* to the full
    hour (local ISO with offset); no device id, no accuracy. Sorted by (hour, lat, lon,
    category), so within an hour the order says nothing about who reported what."""
    latest = agg.today(now)
    first = latest - timedelta(days=REPORT_DAYS - 1)
    a, b = agg.day_bounds(first)[0], agg.day_bounds(latest)[1]
    items = []
    for t_ms, lat, lon, category in rows:
        if a <= t_ms < b:
            hour = t_ms // agg.HOUR_MS * agg.HOUR_MS
            items.append((hour, lat, lon, category))
    items.sort()
    return {
        "generated_at": now.astimezone(agg.ZONE).isoformat(timespec="seconds"),
        "from": first.isoformat(),
        "to": latest.isoformat(),
        "categories": list(REPORT_CATEGORIES),
        "reports": [
            {"time": agg.local(h).isoformat(timespec="seconds"), "lat": lat, "lon": lon, "category": c}
            for h, lat, lon, c in items
        ],
    }


def load_reports(conn: sqlite3.Connection, start_ms: int, end_ms: int) -> list[tuple[int, float, float, str]]:
    return [
        tuple(r)  # type: ignore[misc]
        for r in conn.execute(
            "SELECT r.time_utc, r.lat, r.lon, r.category FROM reports r JOIN devices d ON d.id = r.device_id "
            "WHERE d.hidden = 0 AND r.time_utc >= ? AND r.time_utc < ?",
            (start_ms, end_ms),
        )
    ]


def write(map_dir: Path, cells_json: dict, cell_files: dict[str, dict], day_files: dict[str, dict]) -> None:
    map_dir.mkdir(parents=True, exist_ok=True)
    for name, obj in day_files.items():
        write_json_atomic(map_dir / "days" / f"{name}.json", obj)
    for cell, obj in cell_files.items():
        write_json_atomic(map_dir / "cells" / f"{cell}.json", obj)
    # cells.json last, so a reader that sees it also finds the files it points to
    write_json_atomic(map_dir / "cells.json", cells_json)
    for sub, keep in (("days", set(day_files)), ("cells", set(cell_files))):
        for p in (map_dir / sub).glob("*.json"):
            if p.stem not in keep:
                p.unlink(missing_ok=True)
    # The night files of schema v2 (nights/{date}.json) are no longer published.
    shutil.rmtree(map_dir / "nights", ignore_errors=True)


def build_from_db(
    conn: sqlite3.Connection, now: datetime, min_devices: int = 1, loud_db: float = agg.DEFAULT_LOUD_EVENT_DB
) -> tuple[dict, dict[str, dict], dict[str, dict]]:
    """[build] on the devices in the database, over the days the published files need."""
    latest = agg.today(now)
    first_day = latest - timedelta(days=HISTORY_DAYS + WINDOW_LONG)
    start_ms = agg.day_bounds(first_day)[0]
    end_ms = max(agg.day_bounds(latest)[1], int(now.timestamp() * 1000) + 1)
    return build(load_devices(conn, start_ms, end_ms), now, min_devices, loud_db)


def current_stats(conn: sqlite3.Connection, now: datetime | None = None, min_devices: int = 1) -> dict:
    """stats.json as a publish run at `now` would write it, without writing anything."""
    now = now or datetime.now(UTC)
    cells_json, _, _ = build_from_db(conn, now, min_devices)
    return stats.compute(conn, now, len(cells_json["cells"]))


def publish(
    conn: sqlite3.Connection,
    map_dir: Path,
    now: datetime | None = None,
    min_devices: int = 1,
    loud_db: float = agg.DEFAULT_LOUD_EVENT_DB,
) -> dict:
    """Reads the database, writes every map file, reports.json and stats.json; returns cells.json."""
    now = now or datetime.now(UTC)
    cells_json, cell_files, day_files = build_from_db(conn, now, min_devices, loud_db)
    write(map_dir, cells_json, cell_files, day_files)
    latest = agg.today(now)
    a = agg.day_bounds(latest - timedelta(days=REPORT_DAYS - 1))[0]
    write_json_atomic(map_dir / "reports.json", build_reports(load_reports(conn, a, agg.day_bounds(latest)[1]), now))
    write_json_atomic(map_dir / "stats.json", stats.compute(conn, now, len(cells_json["cells"])))
    return cells_json
