"""Day, hour and 24-hour-profile arithmetic for the shared map (DESIGN.md §6). Pure functions, no I/O.

No time of day is special: a measurement may run at any hour, for minutes or for days.

* A **day** is a calendar day in local time, Europe/Zurich: 00:00 to 00:00, so 24 h, or 23 h /
  25 h on the days the clocks change. A record belongs to the day its *start* falls in; a
  measurement that runs over midnight counts for both days, each with its own minutes.
* A minute is *valid* if its coverage is ≥ 0.5 and it has an LAeq (the app's rule).
* Day LAeq = energy mean of the valid minutes' LAeq, weighted by their valid seconds.
* Events per hour = events of the day / hours of valid measurement (valid seconds of the
  valid minutes). Every event is counted, wherever it starts in the day.
* Loud events = events with LAFmax ≥ LOUD_EVENT_DB (default 75 dB(A)); per hour likewise.
* Dynamics = median over the valid minutes of (L10 − L90); an even count takes the mean of
  the middle two.
* Day L10 / L90 = median over the valid minutes of the minute L10 / L90.
* Measured hours = valid seconds / 3600.

Several devices in one cell (§6.1): levels are the energy mean of the device values, rates and
counts the arithmetic mean over the devices, dynamics the median of the device medians, the
loudest event the maximum, measured hours the mean of the device hours.

Windows (`last_7_days`, `last_30_days`): each day with data counts once — energy mean of the
daily levels, median of the daily dynamics, mean of the daily rates, sums of hours and counts.
"""

from __future__ import annotations

import math
from collections.abc import Iterable, Mapping, Sequence
from dataclasses import dataclass, field
from datetime import date, datetime, time, timedelta
from zoneinfo import ZoneInfo

ZONE = ZoneInfo("Europe/Zurich")
MIN_MINUTE_COVERAGE = 0.5
# An hour of the 24-hour profile (and of the retention job's hourly rows when published) needs
# this many valid minutes for its levels (the app's week-view rule).
MIN_VALID_MINUTES_PER_HOUR = 30
HOUR_MS = 3_600_000
DEFAULT_LOUD_EVENT_DB = 75.0

# The app's source categories (android/README.md, «Source categories»). Published blocks
# always carry all of them, so the website can rely on the keys; unknown ids are added.
CATEGORIES: tuple[str, ...] = (
    "loud_vehicle",
    "road_traffic",
    "rail_tram",
    "aircraft",
    "construction",
    "voices",
    "music",
    "unclassified",
)


# ---- input records ------------------------------------------------------------------------


@dataclass(frozen=True)
class Minute:
    start_ms: int  # UTC epoch milliseconds
    valid_s: float
    coverage: float
    laeq_db: float | None
    l10_db: float | None
    l90_db: float | None
    calibrated: bool
    cell: str | None = None  # the record's own hectare (§4.3); None = the device's site cell

    @property
    def valid(self) -> bool:
        return self.coverage >= MIN_MINUTE_COVERAGE and self.laeq_db is not None and self.valid_s > 0


@dataclass(frozen=True)
class Event:
    start_ms: int  # UTC epoch milliseconds
    lafmax_db: float
    category: str
    cell: str | None = None


# ---- small maths --------------------------------------------------------------------------


def energy_mean(levels: Sequence[float], weights: Sequence[float] | None = None) -> float | None:
    """10·log10 of the (weighted) mean of 10^(L/10); None for no input or zero weight."""
    if not levels:
        return None
    w = weights if weights is not None else [1.0] * len(levels)
    total = sum(w)
    if total <= 0:
        return None
    return 10 * math.log10(sum(10 ** (lv / 10) * wi for lv, wi in zip(levels, w, strict=True)) / total)


def median(values: Iterable[float]) -> float | None:
    v = sorted(values)
    n = len(v)
    if n == 0:
        return None
    return v[n // 2] if n % 2 else (v[n // 2 - 1] + v[n // 2]) / 2


def mean(values: Iterable[float]) -> float | None:
    v = list(values)
    return sum(v) / len(v) if v else None


def r1(x: float | None) -> float | None:
    return None if x is None else round(x, 1)


# ---- days -----------------------------------------------------------------------------------


def local(ms: int, zone: ZoneInfo = ZONE) -> datetime:
    return datetime.fromtimestamp(ms / 1000, zone)


def day_of(ms: int, zone: ZoneInfo = ZONE) -> date:
    """Local calendar date of the instant."""
    return local(ms, zone).date()


def hour_of_day(ms: int, zone: ZoneInfo = ZONE) -> int:
    """Local clock hour 0–23 (the autumn change has two hours «02», the spring one none)."""
    return local(ms, zone).hour


def day_bounds(day: date, zone: ZoneInfo = ZONE) -> tuple[int, int]:
    """[00:00, next 00:00) of the local day in UTC epoch ms; DST-aware via the zone."""
    a = datetime.combine(day, time(0), zone)
    b = datetime.combine(day + timedelta(days=1), time(0), zone)
    return int(a.timestamp() * 1000), int(b.timestamp() * 1000)


def day_length_s(day: date, zone: ZoneInfo = ZONE) -> float:
    a, b = day_bounds(day, zone)
    return (b - a) / 1000


def today(now: datetime, zone: ZoneInfo = ZONE) -> date:
    """The local calendar day of `now`: the newest day on the map (still running)."""
    return now.astimezone(zone).date()


# ---- one device -----------------------------------------------------------------------------


def _counts(events: Iterable[Event]) -> dict[str, int]:
    counts = {c: 0 for c in CATEGORIES}
    for e in events:
        counts[e.category] = counts.get(e.category, 0) + 1
    return counts


@dataclass(frozen=True)
class DeviceDay:
    """One device's figures for one day in one cell. Only built when there is ≥ 1 valid minute."""

    laeq_db: float
    l90_db: float | None
    l10_db: float | None
    dynamics_db: float | None
    events_per_h: float
    events_per_h_by_category: dict[str, float]
    loud_events: int
    loud_events_per_h: float
    loudest_event_db: float | None
    valid_s: float
    calibrated: bool
    event_count: int

    @property
    def measured_hours(self) -> float:
        return self.valid_s / 3600

    def to_dict(self) -> dict:
        return self.__dict__.copy()


def summarize_device_day(
    day: date,
    minutes: Iterable[Minute],
    events: Iterable[Event],
    loud_db: float = DEFAULT_LOUD_EVENT_DB,
    zone: ZoneInfo = ZONE,
) -> DeviceDay | None:
    """The day summary for one device (records outside the day are ignored)."""
    a, b = day_bounds(day, zone)
    ms = [m for m in minutes if a <= m.start_ms < b and m.valid]
    evs = [e for e in events if a <= e.start_ms < b]
    if not ms:
        return None
    valid_s = sum(m.valid_s for m in ms)
    hours = valid_s / 3600
    laeq = energy_mean([m.laeq_db for m in ms], [m.valid_s for m in ms])  # type: ignore[misc]
    assert laeq is not None
    spreads = [m.l10_db - m.l90_db for m in ms if m.l10_db is not None and m.l90_db is not None]
    loud = sum(1 for e in evs if e.lafmax_db >= loud_db)
    return DeviceDay(
        laeq_db=laeq,
        l90_db=median(m.l90_db for m in ms if m.l90_db is not None),
        l10_db=median(m.l10_db for m in ms if m.l10_db is not None),
        dynamics_db=median(spreads),
        events_per_h=len(evs) / hours,
        events_per_h_by_category={k: v / hours for k, v in _counts(evs).items()},
        loud_events=loud,
        loud_events_per_h=loud / hours,
        loudest_event_db=max((e.lafmax_db for e in evs), default=None),
        valid_s=valid_s,
        calibrated=all(m.calibrated for m in ms),
        event_count=len(evs),
    )


@dataclass(frozen=True)
class DeviceHour:
    """One device's figures for one clock hour (UTC-aligned, which is local-aligned in CH).

    Used by the retention job (hourly rows of data older than 2 years)."""

    hour_ms: int
    valid_s: float
    valid_minutes: int
    laeq_db: float | None
    l90_db: float | None
    l10_db: float | None
    dynamics_db: float | None
    events: dict[str, int]
    loudest_db: float | None
    calibrated: bool


def summarize_hour(hour_ms: int, minutes: Iterable[Minute], events: Iterable[Event]) -> DeviceHour:
    ms = [m for m in minutes if hour_ms <= m.start_ms < hour_ms + HOUR_MS and m.valid]
    evs = [e for e in events if hour_ms <= e.start_ms < hour_ms + HOUR_MS]
    return DeviceHour(
        hour_ms=hour_ms,
        valid_s=sum(m.valid_s for m in ms),
        valid_minutes=len(ms),
        laeq_db=energy_mean([m.laeq_db for m in ms], [m.valid_s for m in ms]),  # type: ignore[misc]
        l90_db=median(m.l90_db for m in ms if m.l90_db is not None),
        l10_db=median(m.l10_db for m in ms if m.l10_db is not None),
        dynamics_db=median(m.l10_db - m.l90_db for m in ms if m.l10_db is not None and m.l90_db is not None),
        events=_counts(evs),
        loudest_db=max((e.lafmax_db for e in evs), default=None),
        calibrated=all(m.calibrated for m in ms),
    )


# ---- several devices in one cell ----------------------------------------------------------

BLOCK_KEYS = (
    "laeq_db", "l90_db", "l10_db", "dynamics_db", "events_per_h", "events_per_h_by_category",
    "loud_events_per_h", "loud_events", "loudest_event_db", "measured_hours", "days_with_data",
    "devices", "calibrated",
)  # fmt: skip


@dataclass(frozen=True)
class CellDay:
    device_ids: frozenset[str]  # never published; for distinct device counts over windows
    calibrated: bool
    laeq_db: float
    l90_db: float | None
    l10_db: float | None
    dynamics_db: float | None
    events_per_h: float
    events_per_h_by_category: dict[str, float]
    loud_events: float  # mean over the devices, so possibly fractional
    loud_events_per_h: float
    loudest_event_db: float | None
    measured_hours: float  # mean over the devices

    @property
    def devices(self) -> int:
        return len(self.device_ids)

    def block(self) -> dict:
        """The published block of one day (§6.1), rounded; same keys as a window block."""
        return {
            "laeq_db": r1(self.laeq_db),
            "l90_db": r1(self.l90_db),
            "l10_db": r1(self.l10_db),
            "dynamics_db": r1(self.dynamics_db),
            "events_per_h": r1(self.events_per_h),
            "events_per_h_by_category": {k: r1(v) for k, v in self.events_per_h_by_category.items()},
            "loud_events_per_h": r1(self.loud_events_per_h),
            "loud_events": r1(self.loud_events),
            "loudest_event_db": r1(self.loudest_event_db),
            "measured_hours": r1(self.measured_hours),
            "days_with_data": 1,
            "devices": self.devices,
            "calibrated": self.calibrated,
        }


def _categories(maps: Iterable[Mapping[str, float]]) -> list[str]:
    cats: list[str] = list(CATEGORIES)
    for m in maps:
        cats += [k for k in m if k not in cats]
    return cats


def combine_cell_day(device_days: Mapping[str, DeviceDay]) -> CellDay | None:
    """`device_days`: device id → that device's day in the cell."""
    if not device_days:
        return None
    days = list(device_days.values())
    n = len(days)
    cats = _categories(d.events_per_h_by_category for d in days)
    laeq = energy_mean([d.laeq_db for d in days])
    assert laeq is not None
    return CellDay(
        device_ids=frozenset(device_days),
        calibrated=all(d.calibrated for d in days),
        laeq_db=laeq,
        l90_db=energy_mean([d.l90_db for d in days if d.l90_db is not None]),
        l10_db=energy_mean([d.l10_db for d in days if d.l10_db is not None]),
        dynamics_db=median(d.dynamics_db for d in days if d.dynamics_db is not None),
        events_per_h=sum(d.events_per_h for d in days) / n,
        events_per_h_by_category={c: sum(d.events_per_h_by_category.get(c, 0.0) for d in days) / n for c in cats},
        loud_events=sum(d.loud_events for d in days) / n,
        loud_events_per_h=sum(d.loud_events_per_h for d in days) / n,
        loudest_event_db=max((d.loudest_event_db for d in days if d.loudest_event_db is not None), default=None),
        measured_hours=sum(d.measured_hours for d in days) / n,
    )


def window_block(days: Sequence[CellDay | None]) -> dict:
    """`last_7_days` / `last_30_days` (§6.1): each day with data counts once."""
    have = [d for d in days if d is not None]
    if not have:
        return {
            **dict.fromkeys(BLOCK_KEYS),
            "events_per_h_by_category": {c: None for c in CATEGORIES},
            "measured_hours": 0.0,
            "days_with_data": 0,
            "devices": 0,
        }
    cats = _categories(d.events_per_h_by_category for d in have)
    return {
        "laeq_db": r1(energy_mean([d.laeq_db for d in have])),
        "l90_db": r1(energy_mean([d.l90_db for d in have if d.l90_db is not None])),
        "l10_db": r1(energy_mean([d.l10_db for d in have if d.l10_db is not None])),
        "dynamics_db": r1(median(d.dynamics_db for d in have if d.dynamics_db is not None)),
        "events_per_h": r1(mean(d.events_per_h for d in have)),
        "events_per_h_by_category": {c: r1(mean(d.events_per_h_by_category.get(c, 0.0) for d in have)) for c in cats},
        "loud_events_per_h": r1(mean(d.loud_events_per_h for d in have)),
        "loud_events": r1(sum(d.loud_events for d in have)),
        "loudest_event_db": r1(max((d.loudest_event_db for d in have if d.loudest_event_db is not None), default=None)),
        "measured_hours": r1(sum(d.measured_hours for d in have)),
        "days_with_data": len(have),
        "devices": len(frozenset().union(*(d.device_ids for d in have))),
        "calibrated": all(d.calibrated for d in have),
    }


@dataclass
class DayIndex:
    """Helper for publish: cell → day → CellDay, honouring the minimum device count."""

    min_devices: int = 1
    cells: dict[str, dict[date, CellDay]] = field(default_factory=dict)

    def add(self, cell: str, day: date, device_days: Mapping[str, DeviceDay]) -> None:
        if len(device_days) < self.min_devices:
            return
        c = combine_cell_day(device_days)
        if c is not None:
            self.cells.setdefault(cell, {})[day] = c

    def get(self, cell: str, day: date) -> CellDay | None:
        return self.cells.get(cell, {}).get(day)

    def window(self, cell: str, last: date, days: int) -> list[CellDay | None]:
        """The `days` days ending with `last`, newest first."""
        return [self.get(cell, last - timedelta(days=i)) for i in range(days)]


# ---- the 24-hour profile (§6.2) -------------------------------------------------------------


@dataclass
class _ProfileHour:
    levels: list[float] = field(default_factory=list)
    weights: list[float] = field(default_factory=list)
    l90s: list[float] = field(default_factory=list)
    l10s: list[float] = field(default_factory=list)
    events: dict[str, int] = field(default_factory=dict)
    loud: int = 0

    @property
    def valid_s(self) -> float:
        return sum(self.weights)


@dataclass(frozen=True)
class DeviceProfileHour:
    """One device, one hour of the day (0–23), over all days of the profile window."""

    valid_s: float
    valid_minutes: int
    laeq_db: float | None
    l90_db: float | None
    l10_db: float | None
    events_per_h_by_category: dict[str, float]
    loud_events_per_h: float | None

    @property
    def events_per_h(self) -> float | None:
        return sum(self.events_per_h_by_category.values()) if self.valid_s > 0 else None


def device_profile(
    minutes: Iterable[Minute], events: Iterable[Event], loud_db: float = DEFAULT_LOUD_EVENT_DB, zone: ZoneInfo = ZONE
) -> list[DeviceProfileHour]:
    """24 rows, one per local clock hour: the device's valid minutes and events that started in
    that hour on any day of the given records (the caller picks the window).

    LAeq is the energy mean over the valid minutes weighted by valid seconds (so across days
    too); L90 / L10 the median of the minute values; events per hour of valid measurement."""
    acc = [_ProfileHour() for _ in range(24)]
    for m in minutes:
        if m.valid:
            h = acc[hour_of_day(m.start_ms, zone)]
            h.levels.append(m.laeq_db)  # type: ignore[arg-type]
            h.weights.append(m.valid_s)
            if m.l90_db is not None:
                h.l90s.append(m.l90_db)
            if m.l10_db is not None:
                h.l10s.append(m.l10_db)
    for e in events:
        h = acc[hour_of_day(e.start_ms, zone)]
        h.events[e.category] = h.events.get(e.category, 0) + 1
        h.loud += e.lafmax_db >= loud_db
    out = []
    for h in acc:
        hours = h.valid_s / 3600
        counts = {**{c: 0 for c in CATEGORIES}, **h.events}
        out.append(
            DeviceProfileHour(
                valid_s=h.valid_s,
                valid_minutes=len(h.levels),
                laeq_db=energy_mean(h.levels, h.weights),
                l90_db=median(h.l90s),
                l10_db=median(h.l10s),
                events_per_h_by_category={k: v / hours for k, v in counts.items()} if hours > 0 else {},
                loud_events_per_h=h.loud / hours if hours > 0 else None,
            )
        )
    return out


def combine_profile(per_device: Sequence[Sequence[DeviceProfileHour]], min_devices: int = 1) -> list[dict]:
    """The cell's 24 profile rows (§6.2). Each device counts once per hour (energy mean of the
    device levels, mean of the device rates), like a cell day. An hour with fewer than
    MIN_VALID_MINUTES_PER_HOUR valid minutes in total, or fewer devices than `min_devices`,
    has no figures (null); its measured hours are still given."""
    rows = []
    for hour in range(24):
        hs = [dev[hour] for dev in per_device if dev[hour].valid_s > 0]
        total_minutes = sum(h.valid_minutes for h in hs)
        row: dict = {"hour": hour}
        shown = bool(hs) and total_minutes >= MIN_VALID_MINUTES_PER_HOUR and len(hs) >= min_devices
        cats = _categories(h.events_per_h_by_category for h in hs)
        row.update(
            {
                "laeq_db": r1(energy_mean([h.laeq_db for h in hs])) if shown else None,  # type: ignore[misc]
                "l90_db": r1(energy_mean([h.l90_db for h in hs if h.l90_db is not None])) if shown else None,
                "l10_db": r1(energy_mean([h.l10_db for h in hs if h.l10_db is not None])) if shown else None,
                "events_per_h": r1(mean(h.events_per_h for h in hs)) if shown else None,  # type: ignore[misc]
                "events_per_h_by_category": {
                    c: (r1(mean(h.events_per_h_by_category.get(c, 0.0) for h in hs)) if shown else None) for c in cats
                },
                "loud_events_per_h": r1(mean(h.loud_events_per_h for h in hs)) if shown else None,  # type: ignore[misc]
                "measured_hours": r1(sum(h.valid_s for h in hs) / 3600),
                "devices": len(hs),
            }
        )
        rows.append(row)
    return rows
