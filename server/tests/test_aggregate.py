"""The day arithmetic of DESIGN.md §6.1 and the 24-hour profile of §6.2 on synthetic data."""

from __future__ import annotations

import math
from datetime import UTC, date, datetime, timedelta

import pytest

from stadtlaerm_server import aggregate as agg
from stadtlaerm_server import synthetic as syn
from stadtlaerm_server.aggregate import Event, Minute


def energy(levels, weights=None):
    w = weights or [1.0] * len(levels)
    return 10 * math.log10(sum(10 ** (lv / 10) * x for lv, x in zip(levels, w, strict=True)) / sum(w))


def ms(s: str) -> int:
    return int(datetime.fromisoformat(s).timestamp() * 1000)


# ---- days -----------------------------------------------------------------------------------


def test_day_lengths_follow_dst():
    assert agg.day_length_s(date(2026, 10, 7)) == 24 * 3600
    # Autumn change: 03:00 CEST → 02:00 CET on Sunday 25 October 2026: 25 hours.
    assert agg.day_length_s(date(2026, 10, 25)) == 25 * 3600
    # Spring change: Sunday 29 March 2026: 23 hours.
    assert agg.day_length_s(date(2026, 3, 29)) == 23 * 3600


def test_day_of_and_hour_of_day_use_local_time():
    assert agg.day_of(ms("2026-10-07T23:59:59+02:00")) == date(2026, 10, 7)
    assert agg.day_of(ms("2026-10-08T00:00:00+02:00")) == date(2026, 10, 8)
    assert agg.day_of(ms("2026-10-07T22:30:00+00:00")) == date(2026, 10, 8)  # 00:30 in Zürich
    # Both 02:30s of the autumn change are on 25 October, hour 2.
    for s in ("2026-10-25T02:30:00+02:00", "2026-10-25T02:30:00+01:00"):
        assert agg.day_of(ms(s)) == date(2026, 10, 25) and agg.hour_of_day(ms(s)) == 2


def test_today():
    assert agg.today(datetime.fromisoformat("2026-10-08T06:40:00+02:00")) == date(2026, 10, 8)
    assert agg.today(datetime(2026, 10, 7, 22, 30, tzinfo=UTC)) == date(2026, 10, 8)  # 00:30 local


def test_median_even_and_odd():
    assert agg.median([3.0, 1.0, 2.0]) == 2.0
    assert agg.median([4.0, 1.0, 3.0, 2.0]) == 2.5
    assert agg.median([]) is None


# ---- one device: a measurement over midnight ------------------------------------------------


def test_measurement_over_midnight_counts_for_both_days():
    mins_rec, evs_rec = syn.friday_measurement()
    mins, evs = syn.to_agg(mins_rec, evs_rec)
    fri = agg.summarize_device_day(syn.FRIDAY, mins, evs)
    sat = agg.summarize_device_day(syn.SATURDAY, mins, evs)
    assert fri is not None and sat is not None

    def inside(day):
        a, b = agg.day_bounds(day)
        return [m for m in mins_rec if a <= syn.to_agg([m])[0][0].start_ms < b]

    assert len(inside(syn.FRIDAY)) == 150  # 21:30–24:00
    assert len(inside(syn.SATURDAY)) == 390 - 25  # 00:00–06:30 minus the 03:10–03:35 gap
    assert fri.valid_s == pytest.approx(150 * 60) and fri.measured_hours == pytest.approx(2.5)
    assert sat.valid_s == pytest.approx(365 * 60)
    assert fri.laeq_db == pytest.approx(energy([m["laeq_db"] for m in inside(syn.FRIDAY)]))
    assert sat.laeq_db == pytest.approx(energy([m["laeq_db"] for m in inside(syn.SATURDAY)]))
    # Friday has 7 events from 22:12 to 23:58, Saturday 11 from 00:20 to 05:59:20; only machine
    # and motor noise counts: Friday's 2 voices and Saturday's 2 voices + 1 unclassified do not.
    assert fri.event_count == 5 and sat.event_count == 8
    assert fri.events_per_h == pytest.approx(5 / 2.5)
    assert fri.events_per_h_by_category["loud_vehicle"] == pytest.approx(2 / 2.5)
    assert list(sat.events_per_h_by_category) == list(agg.CATEGORIES)
    assert "voices" not in sat.events_per_h_by_category and "music" not in sat.events_per_h_by_category
    # Loud events (≥ 75 dB): 78 and 85 on Friday; 81 and 76 on Saturday (72 is below).
    assert fri.loud_events == 2 and sat.loud_events == 2
    assert fri.loud_events_per_h == pytest.approx(2 / 2.5)
    assert agg.summarize_device_day(syn.SATURDAY, mins, evs, loud_db=80.0).loud_events == 1
    assert fri.loudest_event_db == 85.0 and sat.loudest_event_db == 81.0
    assert fri.calibrated is False
    assert sat.l10_db > sat.l90_db
    spreads = sorted(m["l10_db"] - m["l90_db"] for m in inside(syn.SATURDAY))
    assert sat.dynamics_db == pytest.approx(spreads[len(spreads) // 2])  # 365: odd count


def test_low_coverage_minutes_are_left_out():
    t0 = ms("2026-10-07T14:00:00+02:00")
    mins = [
        Minute(t0, 60.0, 1.0, 40.0, 43.0, 38.0, True),
        Minute(t0 + 60_000, 30.0, 0.5, 50.0, 60.0, 40.0, True),  # exactly 0.5: kept
        Minute(t0 + 120_000, 29.0, 0.49, 90.0, 95.0, 30.0, True),  # below: dropped
        Minute(t0 + 180_000, 60.0, 1.0, None, None, None, True),  # no level: dropped
    ]
    evs = [Event(t0 + 5000, 70.0, "loud_vehicle"), Event(t0 + 125_000, 80.0, "road_traffic")]
    d = agg.summarize_device_day(date(2026, 10, 7), mins, evs)
    assert d.valid_s == 90.0
    assert d.laeq_db == pytest.approx(energy([40.0, 50.0], [60.0, 30.0]))
    assert d.dynamics_db == pytest.approx((5.0 + 20.0) / 2)
    # Events are per hour of *valid* time, and every event of the day counts.
    assert d.events_per_h == pytest.approx(2 / (90 / 3600))
    assert d.loud_events == 1 and d.loudest_event_db == 80.0


def test_no_valid_minutes_gives_no_day():
    t0 = ms("2026-10-07T23:00:00+02:00")
    assert agg.summarize_device_day(date(2026, 10, 7), [Minute(t0, 10.0, 0.17, 40.0, 41.0, 39.0, True)], []) is None
    assert agg.summarize_device_day(date(2026, 10, 7), [], [Event(t0, 70.0, "voices")]) is None


def test_uncalibrated_minute_makes_the_day_uncalibrated():
    t0 = ms("2026-10-07T09:00:00+02:00")
    mins = [Minute(t0, 60, 1, 40, 42, 38, True), Minute(t0 + 60_000, 60, 1, 40, 42, 38, False)]
    assert agg.summarize_device_day(date(2026, 10, 7), mins, []).calibrated is False


# ---- the DST days ---------------------------------------------------------------------------


def test_autumn_dst_day_has_25_hours():
    day = date(2026, 10, 25)
    l90 = syn.day_profile(42.0)
    start = datetime(2026, 10, 25, 0, 0)
    evs_in = syn.random_events(start, 25, 5, {"road_traffic": 3.0, "loud_vehicle": 0.5}, {})
    mins_rec = syn.minutes(start, datetime(2026, 10, 26, 0, 0), l90, evs_in, seed=4)
    mins, evs = syn.to_agg(mins_rec, syn.event_records(evs_in, l90))
    assert len(mins) == 1500
    d = agg.summarize_device_day(day, mins, evs)
    assert d.measured_hours == 25.0
    a, b = agg.day_bounds(day)
    assert d.events_per_h == pytest.approx(len([e for e in evs if a <= e.start_ms < b]) / 25)

    profile = agg.combine_profile([agg.device_profile(mins, evs)])
    assert [h["hour"] for h in profile] == list(range(24))
    assert profile[2]["measured_hours"] == 2.0  # «02» twice
    assert all(h["measured_hours"] == 1.0 for i, h in enumerate(profile) if i != 2)


def test_spring_dst_day_has_23_hours():
    day = date(2026, 3, 29)
    mins_rec = syn.minutes(datetime(2026, 3, 29, 0, 0), datetime(2026, 3, 30, 0, 0), syn.day_profile(40.0), seed=2)
    mins, _ = syn.to_agg(mins_rec)
    assert len(mins) == 23 * 60
    assert agg.summarize_device_day(day, mins, []).measured_hours == 23.0
    profile = agg.combine_profile([agg.device_profile(mins, [])])
    assert profile[2]["measured_hours"] == 0.0 and profile[2]["laeq_db"] is None  # 02:00 does not exist
    assert profile[3]["measured_hours"] == 1.0


# ---- several devices in a cell --------------------------------------------------------------


def _device(day, base, rate, seed, calibrated=True, spread=(0.0, 2.5), gaps=(), hours=(18, 8)):
    """A measurement from `hours[0]` o'clock on `day` for `hours[1]` hours, summarised for `day`."""
    l90 = syn.day_profile(base)
    start = datetime(day.year, day.month, day.day, hours[0])
    evs_in = syn.random_events(start, hours[1], seed, {"road_traffic": rate, "loud_vehicle": rate / 4}, {})
    end = start + timedelta(hours=hours[1])
    mins_rec = syn.minutes(start, end, l90, evs_in, gaps=gaps, seed=seed, calibrated=calibrated, spread=spread)
    mins, evs = syn.to_agg(mins_rec, syn.event_records(evs_in, l90, calibrated))
    return agg.summarize_device_day(day, mins, evs)


def test_two_device_cell_combination_rules():
    day = date(2026, 10, 7)
    a = _device(day, 44.0, 6.0, 11)
    b = _device(day, 36.0, 2.0, 12, calibrated=False, spread=(4.0, 8.0),
                gaps=[(datetime(2026, 10, 7, 19), datetime(2026, 10, 7, 21))], hours=(16, 8))  # fmt: skip
    c = agg.combine_cell_day({"a": a, "b": b})
    assert c.devices == 2
    assert c.calibrated is False
    assert c.laeq_db == pytest.approx(energy([a.laeq_db, b.laeq_db]))  # energy mean of levels
    assert c.laeq_db > (a.laeq_db + b.laeq_db) / 2  # louder than the arithmetic mean
    assert c.events_per_h == pytest.approx((a.events_per_h + b.events_per_h) / 2)  # mean of rates
    assert c.loud_events_per_h == pytest.approx((a.loud_events_per_h + b.loud_events_per_h) / 2)
    assert c.loud_events == pytest.approx((a.loud_events + b.loud_events) / 2)
    assert c.dynamics_db == pytest.approx((a.dynamics_db + b.dynamics_db) / 2)  # median of two medians
    assert c.loudest_event_db == max(a.loudest_event_db, b.loudest_event_db)
    assert a.measured_hours == 6.0 and b.measured_hours == 6.0  # a: 18–24 h; b: 16–24 h minus 2 h
    assert c.measured_hours == pytest.approx(6.0)
    for k in agg.CATEGORIES:
        assert c.events_per_h_by_category[k] == pytest.approx(
            (a.events_per_h_by_category[k] + b.events_per_h_by_category[k]) / 2
        )
    block = c.block()
    assert set(block) == set(agg.BLOCK_KEYS)
    assert block["days_with_data"] == 1 and block["devices"] == 2


def test_three_device_dynamics_is_median_not_mean():
    base = agg.DeviceDay(45, 40, 48, 0.0, 1, {}, 0, 0.0, None, 3600, True, 1)
    days = {str(d): agg.DeviceDay(**{**base.to_dict(), "dynamics_db": d}) for d in (2.0, 3.0, 20.0)}
    assert agg.combine_cell_day(days).dynamics_db == 3.0


def test_window_block_counts_days_with_data():
    c = agg.combine_cell_day({"x": agg.DeviceDay(50, 40, 52, 6.0, 4.0, {}, 3, 1.5, 80, 7200, True, 8)})
    d = agg.combine_cell_day({"y": agg.DeviceDay(40, 35, 44, 8.0, 2.0, {}, 1, 0.5, 65, 3600, False, 2)})
    w = agg.window_block([c, None, d, None, None, None, None])
    assert w["days_with_data"] == 2
    assert w["laeq_db"] == pytest.approx(round(energy([50, 40]), 1))
    assert w["l90_db"] == pytest.approx(round(energy([40, 35]), 1))
    assert w["events_per_h"] == 3.0
    assert w["loud_events_per_h"] == 1.0
    assert w["loud_events"] == 4.0  # sum over the days
    assert w["dynamics_db"] == 7.0
    assert w["measured_hours"] == 3.0  # sum over the days
    assert w["loudest_event_db"] == 80.0
    assert w["devices"] == 2  # distinct devices over the window
    assert w["calibrated"] is False
    assert set(w) == set(agg.BLOCK_KEYS)
    empty = agg.window_block([None] * 7)
    assert empty["days_with_data"] == 0 and empty["laeq_db"] is None and empty["devices"] == 0
    assert set(empty) == set(agg.BLOCK_KEYS)


def test_same_device_on_several_days_is_one_device_in_the_window():
    one = agg.DeviceDay(50, 40, 52, 6.0, 4.0, {}, 3, 1.5, 80, 7200, True, 8)
    days = [agg.combine_cell_day({"x": one}), agg.combine_cell_day({"x": one, "y": one})]
    assert agg.window_block(days)["devices"] == 2


def test_day_index_min_devices():
    one = agg.DeviceDay(50, 40, 52, 6.0, 4.0, {}, 3, 1.5, 80, 7200, True, 8)
    idx = agg.DayIndex(min_devices=2)
    idx.add("h1", date(2026, 10, 7), {"x": one})
    idx.add("h2", date(2026, 10, 7), {"x": one, "y": one})
    assert idx.get("h1", date(2026, 10, 7)) is None
    assert idx.get("h2", date(2026, 10, 7)).devices == 2
    assert idx.window("h2", date(2026, 10, 8), 2) == [None, idx.get("h2", date(2026, 10, 7))]


# ---- the 24-hour profile --------------------------------------------------------------------


def _minute(local_iso: str, laeq: float, l10: float | None = None, l90: float | None = None) -> Minute:
    return Minute(ms(local_iso), 60.0, 1.0, laeq, l10 if l10 is not None else laeq + 2, l90 or laeq - 3, True)


def test_profile_is_energy_mean_over_days_and_devices():
    # Device A: hour 23 on two days, 20 minutes each at 50 and at 60 dB → 40 valid minutes.
    a = [_minute(f"2026-10-0{d}T23:{i:02d}:00+02:00", lv) for d, lv in ((5, 50.0), (6, 60.0)) for i in range(20)]
    # Device B: hour 23 on one day, 10 minutes at 40 dB.
    b = [_minute(f"2026-10-06T23:{i:02d}:00+02:00", 40.0) for i in range(10)]
    ev_a = [
        Event(ms("2026-10-05T23:05:00+02:00"), 80.0, "loud_vehicle"),
        Event(ms("2026-10-06T23:10:00+02:00"), 60.0, "road_traffic"),
        Event(ms("2026-10-06T23:12:00+02:00"), 90.0, "voices"),  # not machine noise: never counted
    ]
    pa, pb = agg.device_profile(a, ev_a), agg.device_profile(b, [])
    assert pa[23].laeq_db == pytest.approx(energy([50.0, 60.0]))  # equal minutes on both days
    assert pa[23].loud_events_per_h == pytest.approx(1 / (40 / 60))
    rows = agg.combine_profile([pa, pb])
    h23 = rows[23]
    assert h23["laeq_db"] == pytest.approx(round(energy([pa[23].laeq_db, 40.0]), 1))  # each device once
    assert h23["measured_hours"] == pytest.approx(round(50 / 60, 1))
    assert h23["devices"] == 2
    assert h23["events_per_h"] == pytest.approx(round((2 / (40 / 60) + 0) / 2, 1))
    assert h23["events_per_h_by_category"]["loud_vehicle"] == pytest.approx(round((1 / (40 / 60)) / 2, 1))
    assert h23["loud_events_per_h"] == pytest.approx(round((1 / (40 / 60)) / 2, 1))
    assert rows[22]["laeq_db"] is None and rows[22]["measured_hours"] == 0.0 and rows[22]["devices"] == 0


def test_profile_hour_with_fewer_than_30_valid_minutes_is_null():
    m29 = [_minute(f"2026-10-05T14:{i:02d}:00+02:00", 50.0) for i in range(29)]
    rows = agg.combine_profile([agg.device_profile(m29, [Event(ms("2026-10-05T14:05:00+02:00"), 90.0, "voices")])])
    assert rows[14]["laeq_db"] is None and rows[14]["events_per_h"] is None and rows[14]["loud_events_per_h"] is None
    assert rows[14]["measured_hours"] == pytest.approx(round(29 / 60, 1))
    m30 = m29 + [_minute("2026-10-06T14:00:00+02:00", 50.0)]  # the 30th on another day
    assert agg.combine_profile([agg.device_profile(m30, [])])[14]["laeq_db"] == 50.0
    # Two devices with 15 minutes each make 30 in total.
    a = [_minute(f"2026-10-05T09:{i:02d}:00+02:00", 50.0) for i in range(15)]
    b = [_minute(f"2026-10-05T09:{i:02d}:00+02:00", 50.0) for i in range(15)]
    rows = agg.combine_profile([agg.device_profile(a, []), agg.device_profile(b, [])])
    assert rows[9]["laeq_db"] == 50.0
    # …unless MIN_DEVICES_PER_CELL asks for more devices.
    assert agg.combine_profile([agg.device_profile(a, [])], min_devices=2)[9]["laeq_db"] is None
