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

Two kinds of run (DESIGN.md §6, «Publish cadence»), driven by [Publisher.run] every
PUBLISH_INTERVAL_S (30 s):

* **full** — everything from the database: at startup, on the first run after local midnight,
  at least every FULL_EVERY_S (10 minutes), and after anything that can move or remove data
  (a deleted or expired device, a new or moved site, a change of the `hidden` flags).
* **incremental** — when the API only added records ([Changes]): for the touched devices only
  the touched days and, in the 30-day profile, the touched clock hours are read again
  ([update_part]), and only the files those records change are rewritten: the touched cells' files, the day files from the oldest touched day to today,
  cells.json, and stats.json / reports.json when their content changed. Every other file keeps
  its content until the next full run.
* nothing — when nothing changed since the last run.

Both kinds produce the same content for the files they write (tests/test_live_publish.py).
"""

from __future__ import annotations

import json
import math
import os
import shutil
import sqlite3
import tempfile
import threading
from collections import defaultdict
from collections.abc import Iterable, Iterator
from dataclasses import dataclass, field
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
# A full run at least this often, whatever changed: devices that fall out of the 7-day count,
# late corrections of older days, generated_at of every file.
FULL_EVERY_S = 600.0


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


@dataclass
class DevicePart:
    """One device's share of the map: what a run keeps of it, so the next run can redo only
    the devices that sent something. Lives in memory only, like the rest of [MapState]."""

    days: dict[tuple[str, date], agg.DeviceDay]  # (cell, day) → summary, days of the 119-day range
    profiles: dict[str, list[agg.DeviceProfileHour]]  # cell → 24 hours over the profile window
    model: str | None
    active: bool  # a valid minute in the last ACTIVE_DAYS (network.devices_active_7d)


@dataclass(frozen=True)
class _Span:
    """The dates and instants one run works with."""

    now: datetime
    latest: date  # today
    first_day: date  # the oldest day any published window reaches
    profile_from: date
    profile_start: int
    active_since: int
    now_ms: int
    generated_at: str

    @classmethod
    def at(cls, now: datetime) -> _Span:
        latest = agg.today(now)
        profile_from = latest - timedelta(days=PROFILE_DAYS - 1)
        return cls(
            now=now,
            latest=latest,
            first_day=latest - timedelta(days=HISTORY_DAYS + WINDOW_LONG - 2),
            profile_from=profile_from,
            profile_start=agg.day_bounds(profile_from)[0],
            active_since=int((now - timedelta(days=ACTIVE_DAYS)).timestamp() * 1000),
            now_ms=int(now.timestamp() * 1000),
            generated_at=now.astimezone(agg.ZONE).isoformat(timespec="seconds"),
        )

    @property
    def end_ms(self) -> int:
        """End of what is read from the database: today, or a little later (clock skew)."""
        return max(agg.day_bounds(self.latest)[1], self.now_ms + 1)


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


def load_device(conn: sqlite3.Connection, site: DeviceSite, start_ms: int, end_ms: int) -> DeviceData:
    minutes = [m for m in db.load_minutes(conn, site.device_id, start_ms, end_ms, site.cell) if m.cell is not None]
    events = [e for e in db.load_events(conn, site.device_id, start_ms, end_ms, site.cell) if e.cell is not None]
    return DeviceData(site, minutes, events)


def load_devices(conn: sqlite3.Connection, start_ms: int, end_ms: int) -> Iterator[DeviceData]:
    """One device at a time, so memory stays at one device's raw rows however large the network."""
    rows = conn.execute(
        "SELECT d.id, s.cell, s.device_model FROM devices d LEFT JOIN sites s ON s.device_id = d.id "
        "WHERE d.hidden = 0 ORDER BY d.id"
    ).fetchall()
    for device_id, cell, model in rows:
        data = load_device(conn, DeviceSite(device_id, cell, model), start_ms, end_ms)
        if data.minutes or data.events:
            yield data


def _split(data: DeviceData) -> tuple[dict, dict]:
    """(cell, day) → minutes, (cell, day) → events."""
    mins: dict[tuple[str, date], list[agg.Minute]] = defaultdict(list)
    evs: dict[tuple[str, date], list[agg.Event]] = defaultdict(list)
    for m in data.minutes:
        mins[(m.cell, agg.day_of(m.start_ms))].append(m)  # type: ignore[index]
    for e in data.events:
        evs[(e.cell, agg.day_of(e.start_ms))].append(e)  # type: ignore[index]
    return mins, evs


def _summaries(d: DeviceData, span: _Span, loud_db: float, only_days: set[date] | None = None) -> dict:
    mins, evs = _split(d)
    out = {}
    for (cell, day), ms in mins.items():
        if span.first_day <= day <= span.latest and (only_days is None or day in only_days):
            s = agg.summarize_device_day(day, ms, evs.get((cell, day), []), loud_db)
            if s is not None:
                out[(cell, day)] = s
    return out


def _profiles(minutes: list[agg.Minute], events: list[agg.Event], span: _Span, loud_db: float) -> dict:
    """cell → the device's 24 profile hours, for every cell with a valid minute in the window."""
    p_mins_all = [m for m in minutes if m.start_ms >= span.profile_start]
    p_evs_all = [e for e in events if e.start_ms >= span.profile_start]
    profiles = {}
    for cell in sorted({m.cell for m in p_mins_all if m.valid}):  # type: ignore[type-var]
        p_mins = [m for m in p_mins_all if m.cell == cell]
        p_evs = [e for e in p_evs_all if e.cell == cell]
        profiles[cell] = agg.device_profile(p_mins, p_evs, loud_db)
    return profiles


def device_part(d: DeviceData, span: _Span, loud_db: float = agg.DEFAULT_LOUD_EVENT_DB) -> DevicePart:
    """The device's share of the map, from all its records the files need (a full run)."""
    active = any(span.active_since <= m.start_ms <= span.now_ms and m.valid for m in d.minutes)
    return DevicePart(_summaries(d, span, loud_db), _profiles(d.minutes, d.events, span, loud_db),
                      d.site.device_model, active)  # fmt: skip


EMPTY_PROFILE_HOUR = agg.device_profile([], [])[0]  # an hour without a valid minute


def update_part(
    conn: sqlite3.Connection,
    site: DeviceSite,
    span: _Span,
    loud_db: float,
    base: DevicePart | None,
    hours: set[int],
) -> DevicePart:
    """`base` brought up to date after new records in the UTC hours `hours` (epoch ms // 1 h).

    Exactly what [device_part] gives on all records, reading only what can have changed: the
    summaries of the days of those hours, and the profile rows of their local clock hours (a
    profile row depends only on the records of its clock hour, over the 30 days). Everything
    else is taken from `base` — valid because records only change through the API, which notes
    them ([Changes]), or through what forces a full run (a site, a deletion, `hidden`)."""
    days = sorted(d for d in {agg.day_of(h * agg.HOUR_MS) for h in hours} if span.first_day <= d <= span.latest)
    summaries = {k: v for k, v in base.days.items() if k[1] not in days} if base else {}
    if days:
        data = load_device(conn, site, agg.day_bounds(days[0])[0], agg.day_bounds(days[-1])[1])
        summaries.update(_summaries(data, span, loud_db, set(days)))

    profiles = dict(base.profiles) if base else {}
    local_hours = {agg.hour_of_day(h * agg.HOUR_MS) for h in hours if (h + 1) * agg.HOUR_MS > span.profile_start}
    if local_hours:
        # A Swiss clock hour is UTC + 1 h (CET) or + 2 h (CEST): read those UTC hours, keep the exact ones.
        utc = {(lh - k) % 24 for lh in local_hours for k in (1, 2)}
        mins = db.load_minutes(conn, site.device_id, span.profile_start, span.end_ms, site.cell, utc)
        evs = db.load_events(conn, site.device_id, span.profile_start, span.end_ms, site.cell, utc)
        mins = [m for m in mins if m.cell is not None and agg.hour_of_day(m.start_ms) in local_hours]
        evs = [e for e in evs if e.cell is not None and agg.hour_of_day(e.start_ms) in local_hours]
        fresh = _profiles(mins, evs, span, loud_db)  # right for `local_hours`, empty elsewhere
        for cell in sorted(set(profiles) | set(fresh)):
            old = profiles.get(cell) or [EMPTY_PROFILE_HOUR] * 24
            new = fresh.get(cell) or [EMPTY_PROFILE_HOUR] * 24
            rows = [new[h] if h in local_hours else old[h] for h in range(24)]
            if any(r.valid_s > 0 for r in rows):
                profiles[cell] = rows
            else:
                profiles.pop(cell, None)

    active = conn.execute(
        "SELECT 1 FROM minutes WHERE device_id = ? AND start_utc >= ? AND start_utc <= ? "
        f"AND COALESCE(cell, ?) IS NOT NULL AND {stats.VALID_MINUTE_SQL} LIMIT 1",
        (site.device_id, span.active_since, span.now_ms, site.cell),
    ).fetchone()
    return DevicePart(summaries, profiles, site.device_model, active is not None)


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


@dataclass
class MapState:
    """What the published files are made of, kept between runs (in memory, never written)."""

    span: _Span
    min_devices: int
    loud_db: float
    parts: dict[str, DevicePart] = field(default_factory=dict)  # device id → its share
    by_cell_day: dict[str, dict[date, dict[str, agg.DeviceDay]]] = field(default_factory=dict)
    index: agg.DayIndex = field(default_factory=agg.DayIndex)
    # Local date (ISO) since which only machine and motor noise is kept (db.counted_since, §6).
    counted_since: str | None = None

    @classmethod
    def of(
        cls,
        parts: dict[str, DevicePart],
        span: _Span,
        min_devices: int,
        loud_db: float,
        counted_since: str | None = None,
    ) -> MapState:
        st = cls(span, min_devices, loud_db, index=agg.DayIndex(min_devices=min_devices), counted_since=counted_since)
        for dev in sorted(parts):
            st.parts[dev] = parts[dev]
            for (cell, day), s in parts[dev].days.items():
                st.by_cell_day.setdefault(cell, {}).setdefault(day, {})[dev] = s
        for cell, days in st.by_cell_day.items():
            for day in days:
                st.reindex(cell, day)
        return st

    def reindex(self, cell: str, day: date) -> None:
        """Recomputes the index entry of (cell, day) from the device days, in device id order
        (the order a full run adds them in, so both kinds of run give the same floats)."""
        devs = self.by_cell_day.get(cell, {}).get(day, {})
        cells = self.index.cells
        cells.get(cell, {}).pop(day, None)
        if devs:
            self.index.add(cell, day, {k: devs[k] for k in sorted(devs)})
        if cell in cells and not cells[cell]:
            del cells[cell]

    def set_part(self, dev: str, new: DevicePart | None) -> set[tuple[str, date]]:
        """Replaces the device's share; returns the (cell, day) pairs whose device days changed."""
        old = self.parts.pop(dev, None)
        if new is not None:
            self.parts = dict(sorted({**self.parts, dev: new}.items()))
        changed = set()
        for key in set(old.days if old else ()) | set(new.days if new else ()):
            before = old.days.get(key) if old else None
            after = new.days.get(key) if new else None
            if before == after:
                continue
            cell, day = key
            slot = self.by_cell_day.setdefault(cell, {}).setdefault(day, {})
            if after is None:
                slot.pop(dev, None)
            else:
                slot[dev] = after
            if not slot:
                del self.by_cell_day[cell][day]
                if not self.by_cell_day[cell]:
                    del self.by_cell_day[cell]
            changed.add(key)
        for key in changed:
            self.reindex(*key)
        return changed

    def network(self) -> dict:
        models: dict[str, int] = defaultdict(int)
        active = 0
        for p in self.parts.values():
            if p.active:
                active += 1
                models[p.model or "unbekannt"] += 1
        return {"devices_active_7d": active, "device_models": dict(sorted(models.items()))}

    def shown_cells(self) -> list[str]:
        """Cells in at least one of the 90 day files: data on any day of the 119-day range."""
        return sorted(c for c, days in self.index.cells.items() if days)

    def day_file(self, day: date, network: dict) -> dict:
        return {
            "generated_at": self.span.generated_at,
            "day": day.isoformat(),
            "default_period": DEFAULT_PERIOD,
            "periods": _periods(day),
            "min_devices_per_cell": self.min_devices,
            "loud_event_db": self.loud_db,
            "counted_since": self.counted_since,
            "cells": [e for c in sorted(self.index.cells) if (e := _cell_entry(self.index, c, day)) is not None],
            "network": network,
        }

    def cell_file(self, cell: str) -> dict:
        latest = self.span.latest
        history = []
        for i in range(HISTORY_DAYS):
            day = latest - timedelta(days=i)
            if (c := self.index.get(cell, day)) is not None:
                history.append({"day": day.isoformat(), **c.block()})
        newest = history[0] if history else None
        profiles = [p.profiles[cell] for p in self.parts.values() if cell in p.profiles]  # device id order
        return {
            "generated_at": self.span.generated_at,
            "cell": cell,
            "devices": newest["devices"] if newest else 0,
            "calibrated": newest["calibrated"] if newest else False,
            "loud_event_db": self.loud_db,
            "counted_since": self.counted_since,
            "days": history,
            "profile": {
                "from": self.span.profile_from.isoformat(),
                "to": latest.isoformat(),
                "hours": agg.combine_profile(profiles, self.min_devices),
            },
        }

    def all_files(self) -> tuple[dict, dict[str, dict], dict[str, dict]]:
        network = self.network()
        day_files = {}
        for i in range(HISTORY_DAYS):
            day = self.span.latest - timedelta(days=i)
            day_files[day.isoformat()] = self.day_file(day, network)
        cell_files = {cell: self.cell_file(cell) for cell in self.shown_cells()}
        return day_files[self.span.latest.isoformat()], cell_files, day_files


def build_state(
    devices: Iterable[DeviceData],
    now: datetime,
    min_devices: int = 1,
    loud_db: float = agg.DEFAULT_LOUD_EVENT_DB,
    counted_since: str | None = None,
) -> MapState:
    span = _Span.at(now)
    parts = {d.site.device_id: device_part(d, span, loud_db) for d in devices}
    return MapState.of(parts, span, min_devices, loud_db, counted_since)


def build(
    devices: Iterable[DeviceData],
    now: datetime,
    min_devices: int = 1,
    loud_db: float = agg.DEFAULT_LOUD_EVENT_DB,
    counted_since: str | None = None,
) -> tuple[dict, dict[str, dict], dict[str, dict]]:
    """Pure: device data → (cells.json, {cell: cells/{cell}.json}, {date: days/{date}.json})."""
    return build_state(devices, now, min_devices, loud_db, counted_since).all_files()


def build_reports(rows: Iterable[tuple], now: datetime) -> dict:
    """reports.json (§6.4) from (time_utc ms, lat, lon, category[, direction_deg, direction_accuracy
    [, level_lafmax_db]]) of non-hidden devices.

    The last REPORT_DAYS calendar days up to today. Each time is rounded *down* to the full
    hour (local ISO with offset); no device id, no accuracy. `direction` is the reported direction of
    travel in whole degrees (app 0.6.7), null when none was given or the compass said «unreliable»;
    north and accuracy class are not published. `lafmax_db` (app 0.8.5) is the peak level while the
    report dialog was open, rounded to whole dB, null when none was measured; the LAeq, the seconds and
    the calibration flag are not published. Sorted by (hour, lat, lon, category, direction, lafmax_db),
    so within an hour the order says nothing about who reported what."""
    latest = agg.today(now)
    first = latest - timedelta(days=REPORT_DAYS - 1)
    a, b = agg.day_bounds(first)[0], agg.day_bounds(latest)[1]
    items = []
    for row in rows:
        t_ms, lat, lon, category = row[:4]
        deg, acc = (row[4], row[5]) if len(row) >= 6 else (None, None)
        direction = deg if deg is not None and acc != "unreliable" else None
        peak = row[6] if len(row) >= 7 else None
        level = None if peak is None else int(math.floor(peak + 0.5))
        if a <= t_ms < b:
            hour = t_ms // agg.HOUR_MS * agg.HOUR_MS
            items.append((hour, lat, lon, category, -1 if direction is None else direction, -1 if level is None else level))
    items.sort()
    return {
        "generated_at": now.astimezone(agg.ZONE).isoformat(timespec="seconds"),
        "from": first.isoformat(),
        "to": latest.isoformat(),
        "categories": list(REPORT_CATEGORIES),
        "reports": [
            {
                "time": agg.local(h).isoformat(timespec="seconds"), "lat": lat, "lon": lon, "category": c,
                "direction": None if d < 0 else d,
                "lafmax_db": None if v < 0 else v,
            }
            for h, lat, lon, c, d, v in items
        ],
    }


def load_reports(conn: sqlite3.Connection, start_ms: int, end_ms: int) -> list[tuple]:
    where = "FROM reports r JOIN devices d ON d.id = r.device_id WHERE d.hidden = 0 AND r.time_utc >= ? AND r.time_utc < ?"
    try:
        cur = conn.execute(
            f"SELECT r.time_utc, r.lat, r.lon, r.category, r.direction_deg, r.direction_accuracy, r.level_lafmax_db {where}",
            (start_ms, end_ms),
        )
    except sqlite3.OperationalError:  # a database before v8 (no level columns)
        try:
            cur = conn.execute(
                f"SELECT r.time_utc, r.lat, r.lon, r.category, r.direction_deg, r.direction_accuracy {where}",
                (start_ms, end_ms),
            )
        except sqlite3.OperationalError:  # a database before v6 (no direction columns)
            cur = conn.execute(f"SELECT r.time_utc, r.lat, r.lon, r.category {where}", (start_ms, end_ms))
    return [tuple(r) for r in cur]


def reports_from_db(conn: sqlite3.Connection, now: datetime) -> dict:
    latest = agg.today(now)
    a = agg.day_bounds(latest - timedelta(days=REPORT_DAYS - 1))[0]
    return build_reports(load_reports(conn, a, agg.day_bounds(latest)[1]), now)


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


def _read_span(now: datetime) -> tuple[int, int]:
    span = _Span.at(now)
    return agg.day_bounds(span.latest - timedelta(days=HISTORY_DAYS + WINDOW_LONG))[0], span.end_ms


def build_from_db(
    conn: sqlite3.Connection, now: datetime, min_devices: int = 1, loud_db: float = agg.DEFAULT_LOUD_EVENT_DB
) -> tuple[dict, dict[str, dict], dict[str, dict]]:
    """[build] on the devices in the database, over the days the published files need."""
    return build(load_devices(conn, *_read_span(now)), now, min_devices, loud_db, db.counted_since(conn))


def current_stats(conn: sqlite3.Connection, now: datetime | None = None, min_devices: int = 1) -> dict:
    """stats.json as a publish run at `now` would write it, without writing anything."""
    now = now or datetime.now(UTC)
    cells_json, _, _ = build_from_db(conn, now, min_devices)
    return stats.compute(conn, now, len(cells_json["cells"]))


def hidden_fingerprint(conn: sqlite3.Connection) -> str:
    """The ids of the hidden devices. The operator sets `hidden` with sqlite3 from outside the
    process (server/README.md), so the API cannot report it; a full run follows any change."""
    row = conn.execute("SELECT group_concat(id, ',') FROM (SELECT id FROM devices WHERE hidden = 1 ORDER BY id)")
    return row.fetchone()[0] or ""


# ---- what changed since the last run ---------------------------------------------------------


@dataclass
class Pending:
    """Changes the API made since the last run (see [Changes])."""

    # device id → the UTC hours (epoch ms // 1 h) in which its new or replaced records start
    touched: dict[str, set[int]] = field(default_factory=dict)
    reports: bool = False  # reports.json and stats.json
    stats: bool = False  # stats.json only (a registration)
    full: bool = False  # a site or a device deleted: only a full run is right

    @property
    def empty(self) -> bool:
        return not (self.touched or self.reports or self.stats or self.full)

    def days(self) -> dict[str, set[date]]:
        """device id → the local days of its touched hours."""
        return {dev: {agg.day_of(h * agg.HOUR_MS) for h in hours} for dev, hours in self.touched.items()}

    def merge(self, other: Pending) -> None:
        for dev, hours in other.touched.items():
            self.touched.setdefault(dev, set()).update(hours)
        self.reports |= other.reports
        self.stats |= other.stats
        self.full |= other.full


class Changes:
    """The change marker: the API handlers note here, *after* their transaction committed, what
    they changed; each publish run takes everything noted so far ([take]) before it reads the
    database. A note made while a run reads is therefore either seen by that run or kept for
    the next one — never lost. One process (DESIGN.md §8), so a lock is enough."""

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._p = Pending()

    def records(self, device_id: str, starts_ms: Iterable[int]) -> None:
        hours = {ms // agg.HOUR_MS for ms in starts_ms}
        if hours:
            with self._lock:
                self._p.touched.setdefault(device_id, set()).update(hours)

    def reports(self) -> None:
        with self._lock:
            self._p.reports = True

    def registered(self) -> None:
        with self._lock:
            self._p.stats = True

    def structural(self) -> None:
        with self._lock:
            self._p.full = True

    def take(self) -> Pending:
        with self._lock:
            p, self._p = self._p, Pending()
            return p

    def put_back(self, p: Pending) -> None:
        """After a failed run: keep its changes, and make the next run a full one."""
        with self._lock:
            self._p.merge(p)
            self._p.full = True


# ---- the publisher -----------------------------------------------------------------------------


class _NeedFull(Exception):
    pass


class Publisher:
    """Writes the map files; keeps a [MapState] between runs so most runs redo only what changed."""

    def __init__(self, map_dir: Path, min_devices: int = 1, loud_db: float = agg.DEFAULT_LOUD_EVENT_DB) -> None:
        self.map_dir = map_dir
        self.min_devices = min_devices
        self.loud_db = loud_db
        self.state: MapState | None = None
        self.scan: stats.Scan | None = None
        self.hidden: str | None = None
        self.full_at: datetime | None = None
        self._stats: dict | None = None
        self._reports: dict | None = None

    def full(self, conn: sqlite3.Connection, now: datetime | None = None) -> dict:
        """Reads the database, writes every map file, reports.json and stats.json; returns cells.json."""
        now = now or datetime.now(UTC)
        hidden = hidden_fingerprint(conn)
        st = build_state(
            load_devices(conn, *_read_span(now)), now, self.min_devices, self.loud_db, db.counted_since(conn)
        )
        cells_json, cell_files, day_files = st.all_files()
        write(self.map_dir, cells_json, cell_files, day_files)
        reports = reports_from_db(conn, now)
        write_json_atomic(self.map_dir / "reports.json", reports)
        scan = stats.scan(conn, now)
        s = stats.compute(conn, now, len(cells_json["cells"]), scan)
        write_json_atomic(self.map_dir / "stats.json", s)
        self.state, self.scan, self.hidden, self.full_at = st, scan, hidden, now
        self._stats, self._reports = s, reports
        return cells_json

    def run(self, conn: sqlite3.Connection, now: datetime, pending: Pending) -> str:
        """One cycle: "full", "incremental" or "skipped" (nothing changed since the last run)."""
        st = self.state
        if (
            st is None
            or self.full_at is None
            or agg.today(now) != st.span.latest  # first run after local midnight: windows shift
            or not timedelta(0) <= now - self.full_at < timedelta(seconds=FULL_EVERY_S)
            or pending.full
            or hidden_fingerprint(conn) != self.hidden
        ):
            self.full(conn, now)
            return "full"
        if pending.empty:
            return "skipped"
        try:
            self.incremental(conn, now, pending)
        except _NeedFull:
            self.full(conn, now)
            return "full"
        return "incremental"

    def incremental(self, conn: sqlite3.Connection, now: datetime, pending: Pending) -> None:
        old = self.state
        assert old is not None and self.scan is not None
        span = _Span.at(now)
        if span.latest != old.span.latest:
            raise _NeedFull
        st = MapState(span, old.min_devices, old.loud_db, old.parts, old.by_cell_day, old.index, old.counted_since)
        changed: set[tuple[str, date]] = set()
        profile_cells: set[str] = set()
        for dev in sorted(pending.touched):
            row = conn.execute(
                "SELECT d.hidden, s.cell, s.device_model FROM devices d LEFT JOIN sites s ON s.device_id = d.id "
                "WHERE d.id = ?",
                (dev,),
            ).fetchone()
            if row is None or row[0]:
                if dev in st.parts:  # gone or hidden since the last full run: only a full run is right
                    raise _NeedFull
                continue  # a hidden device: never on the map
            before = st.parts.get(dev)
            part = update_part(conn, DeviceSite(dev, row[1], row[2]), span, st.loud_db, before, pending.touched[dev])
            if before is not None:
                profile_cells |= {c for c in set(before.profiles) | set(part.profiles)
                                  if before.profiles.get(c) != part.profiles.get(c)}  # fmt: skip
            else:
                profile_cells |= set(part.profiles)
            empty = not part.days and not part.profiles and not part.active
            changed |= st.set_part(dev, None if empty else part)
        self.state = st

        if pending.touched:
            network = st.network()
            touched_days = {d for _, d in changed}
            oldest = max(min(touched_days, default=span.latest), span.latest - timedelta(days=HISTORY_DAYS - 1))
            files = {}
            day = span.latest
            while day >= oldest:
                files[day] = st.day_file(day, network)
                day -= timedelta(days=1)
            shown = set(st.shown_cells())
            for cell in sorted({c for c, _ in changed} | profile_cells):
                path = self.map_dir / "cells" / f"{cell}.json"
                if cell in shown:
                    write_json_atomic(path, st.cell_file(cell))
                else:
                    path.unlink(missing_ok=True)
            for d in sorted(files):
                write_json_atomic(self.map_dir / "days" / f"{d.isoformat()}.json", files[d])
            write_json_atomic(self.map_dir / "cells.json", files[span.latest])
            cells_count = len(files[span.latest]["cells"])
        else:
            cells_count = self._stats["cells_with_data_30d"] if self._stats else 0

        if pending.reports:
            reports = reports_from_db(conn, now)
            if self._reports is None or _without_time(reports) != _without_time(self._reports):
                write_json_atomic(self.map_dir / "reports.json", reports)
                self._reports = reports
        stats.refresh(conn, self.scan, now, pending.days())
        s = stats.compute(conn, now, cells_count, self.scan)
        if self._stats is None or _without_time(s) != _without_time(self._stats):
            write_json_atomic(self.map_dir / "stats.json", s)
            self._stats = s


def _without_time(obj: dict) -> dict:
    return {k: v for k, v in obj.items() if k != "generated_at"}


def publish(
    conn: sqlite3.Connection,
    map_dir: Path,
    now: datetime | None = None,
    min_devices: int = 1,
    loud_db: float = agg.DEFAULT_LOUD_EVENT_DB,
) -> dict:
    """A full run: reads the database, writes every map file, reports.json and stats.json;
    returns cells.json."""
    return Publisher(map_dir, min_devices, loud_db).full(conn, now)
