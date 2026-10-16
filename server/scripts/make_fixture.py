"""Generate `example_cells.json`, `example_cell.json` and `example_reports.json` from synthetic data.

The data is invented, but it goes through the real code path: synthetic minute/event/report
records in the upload format → schema validation → SQLite → publish.publish(). So the
fixtures have exactly the format the server emits.

    python scripts/make_fixture.py            # writes server/example_cells.json, example_cell.json,
                                              # example_reports.json
    python scripts/make_fixture.py --map-dir /tmp/map   # also keep the full map tree there
"""

from __future__ import annotations

import argparse
import json
import random
import sys
import tempfile
from dataclasses import dataclass
from datetime import date, datetime, timedelta
from pathlib import Path

SERVER = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(SERVER))

from stadtlaerm_server import db, publish  # noqa: E402
from stadtlaerm_server import synthetic as syn  # noqa: E402
from stadtlaerm_server.lv95 import cell_of as wgs84_to_cell  # noqa: E402
from stadtlaerm_server.schemas import EventIn, MinuteIn, ReportIn  # noqa: E402

NOW = datetime.fromisoformat("2026-10-08T18:40:00+02:00")
TODAY = date(2026, 10, 8)
CLIP = datetime(2026, 10, 8, 18, 0)  # nothing measured after this (naive local); NOW is 18:40
DAYS = 30
# migration v5 records the day it ran as `counted_since`; the fixture pins the live deploy date.
COUNTED_SINCE_AT = datetime.fromisoformat("2026-10-16T09:00:00+02:00")


@dataclass(frozen=True)
class Profile:
    """Character of a street: background, burstiness and event rates per hour."""

    base: float  # L90 in the small hours, dB(A)
    spread: tuple[float, float]  # random part of L10 − L90 on top of 3 dB
    rates: dict[str, float]  # events per hour on a weekday
    weekend: float = 1.0  # factor on rates (and +1.5 dB background) on Fri/Sat
    levels: dict[str, tuple[float, float]] | None = None
    day_rise: float = 8.0  # background in the day above the small hours
    reports: tuple[tuple[str, float], ...] = (("andere", 1.0),)  # report categories and weights


SEESTRASSE = Profile(45.0, (1.0, 4.0), {"road_traffic": 6.0, "loud_vehicle": 1.6, "voices": 0.5}, 1.8,
                     {"loud_vehicle": (72, 88), "road_traffic": (60, 72)}, 10.0,
                     (("motorrad", 4), ("poser", 3), ("auto", 1)))  # fmt: skip
BEDERSTRASSE = Profile(42.0, (1.0, 4.5), {"road_traffic": 4.5, "loud_vehicle": 0.9, "rail_tram": 0.8}, 1.4,
                       {"loud_vehicle": (70, 84), "rail_tram": (62, 70)}, 9.0,
                       (("motorrad", 2), ("lastwagen", 1), ("auto", 1)))  # fmt: skip
LANGSTRASSE = Profile(47.0, (2.0, 6.0), {"voices": 5.0, "music": 1.2, "road_traffic": 3.5, "loud_vehicle": 1.2}, 2.2,
                      {"voices": (58, 74), "music": (58, 70), "loud_vehicle": (72, 86)}, 7.0,
                      (("poser", 3), ("auto", 2), ("andere", 2)))  # fmt: skip
KREIS7 = Profile(33.0, (3.0, 9.0), {"road_traffic": 0.8, "voices": 0.3, "unclassified": 0.3, "loud_vehicle": 0.1}, 1.1,
                 {"road_traffic": (48, 60), "loud_vehicle": (62, 74)}, 9.0,
                 (("motorrad", 1), ("andere", 1)))  # fmt: skip
HIGHWAY = Profile(55.0, (0.5, 2.0), {"road_traffic": 9.0, "loud_vehicle": 1.0, "aircraft": 0.1}, 1.2,
                  {"road_traffic": (64, 74), "loud_vehicle": (72, 82), "aircraft": (60, 66)}, 8.0,
                  (("lastwagen", 3), ("motorrad", 1)))  # fmt: skip

# When a phone measures, as (start hour, hours): around the clock, evenings into the morning, or
# during the day. The map has no special hours; people measure when they can.
ALL_DAY = (0, 24)
EVENINGS = (18, 14)
DAYTIME = (7, 12)


@dataclass(frozen=True)
class Site:
    name: str
    lat: float
    lon: float
    profile: Profile
    devices: int = 1
    offset_db: float = 0.0  # this hectare is a bit louder/quieter than its street's profile
    models: tuple[str, ...] = ("Pixel 8",)
    uncalibrated: bool = False
    days: int = DAYS  # how many of the last 30 days it measured
    skip_today: bool = False  # no data today (shows `day: null`)
    schedule: tuple[int, int] = EVENINGS
    with_cell: bool = True  # the records carry their hectare (app ≥ 0.6); False: an older app


SITES = [
    # Kreis 2: Seestrasse (Poser route along the lake) and Bederstrasse
    Site("Seestrasse / Mutschellenstrasse", 47.3553, 8.5355, SEESTRASSE, 2, 1.0, ("Pixel 8", "Galaxy A54"),
         schedule=ALL_DAY),
    Site("Seestrasse / Kilchbergstrasse", 47.3497, 8.5381, SEESTRASSE, 1, 0.0, ("Pixel 7a",)),
    Site("Seestrasse / Bellariastrasse", 47.3595, 8.5345, SEESTRASSE, 1, 1.5, ("Pixel 8",), schedule=DAYTIME),
    Site("Seestrasse, Wollishofen", 47.3455, 8.5390, SEESTRASSE, 1, -1.0, ("Pixel 6a",), uncalibrated=True),
    Site("Bederstrasse / Brunaustrasse", 47.3580, 8.5300, BEDERSTRASSE, 1, 0.5, ("Pixel 8",)),
    Site("Bederstrasse / Breitingerstrasse", 47.3610, 8.5315, BEDERSTRASSE, 2, 0.0, ("Pixel 8 Pro", "Pixel 7a"),
         schedule=ALL_DAY, with_cell=False),
    Site("Bederstrasse / Lessingstrasse", 47.3552, 8.5272, BEDERSTRASSE, 1, -0.5, ("Galaxy S23",), days=12),
    Site("Hofstrasse, Kreis 2 (courtyard)", 47.3568, 8.5320, KREIS7, 1, 3.0, ("Pixel 8",)),
    # Kreis 4: Langstrasse
    Site("Langstrasse / Dienerstrasse", 47.3781, 8.5268, LANGSTRASSE, 2, 1.0, ("Pixel 8", "Fairphone 5")),
    Site("Langstrasse / Brauerstrasse", 47.3767, 8.5254, LANGSTRASSE, 1, 0.0, ("Pixel 7a",)),
    Site("Langstrasse / Militärstrasse", 47.3796, 8.5282, LANGSTRASSE, 1, 2.0, ("Galaxy A54",)),
    Site("Langstrasse / Hohlstrasse", 47.3808, 8.5265, LANGSTRASSE, 1, -0.5, ("Pixel 8",), schedule=DAYTIME),
    Site("Brauerstrasse, Kreis 4", 47.3760, 8.5225, LANGSTRASSE, 1, -4.0, ("Pixel 6a",), uncalibrated=True),
    Site("Helvetiaplatz", 47.3757, 8.5277, LANGSTRASSE, 1, -2.0, ("Pixel 8",), days=20, with_cell=False),
    Site("Kanzleistrasse, Kreis 4", 47.3742, 8.5262, LANGSTRASSE, 1, -5.0, ("Pixel 7a",), skip_today=True, days=9),
    # Kreis 7: quiet residential streets
    Site("Kapfstrasse, Hirslanden", 47.3606, 8.5722, KREIS7, 1, 0.0, ("Pixel 8",)),
    Site("Klosbachstrasse, Hottingen", 47.3706, 8.5603, KREIS7, 1, 2.0, ("Pixel 7a",), schedule=ALL_DAY),
    Site("Freiestrasse, Hottingen", 47.3683, 8.5572, KREIS7, 2, 1.0, ("Pixel 8", "Galaxy S23")),
    Site("Hofackerstrasse, Hirslanden", 47.3634, 8.5677, KREIS7, 1, -1.0, ("Pixel 8",)),
    Site("Witikonerstrasse / Eierbrecht", 47.3608, 8.5850, KREIS7, 1, 4.0, ("Pixel 6a",)),
    Site("Toblerstrasse, Fluntern", 47.3765, 8.5600, KREIS7, 1, -2.0, ("Pixel 8",), uncalibrated=True, days=18,
         schedule=DAYTIME),
    # A3 Sihlhochstrasse ending at Brunau
    Site("Sihlhochstrasse / Brunau", 47.3505, 8.5232, HIGHWAY, 2, 0.0, ("Pixel 8", "Pixel 7a"), schedule=ALL_DAY),
    Site("Allmend Brunau", 47.3478, 8.5212, HIGHWAY, 1, -3.0, ("Galaxy A54",)),
    Site("Sihlhochstrasse, Giesshübel", 47.3540, 8.5248, HIGHWAY, 1, 1.0, ("Pixel 8",)),
    Site("Brunaustrasse near A3", 47.3528, 8.5270, HIGHWAY, 1, -6.0, ("Pixel 8 Pro",)),
]


def day_records(site: Site, device: int, day: date, seed: int) -> tuple[list[dict], list[dict]]:
    """One measurement that starts on `day` (it may run into the next day)."""
    p = site.profile
    weekend = day.weekday() in (4, 5)  # Fri, Sat
    factor = p.weekend if weekend else 1.0
    base = p.base + site.offset_db + (1.5 if weekend and p.weekend > 1.3 else 0.0) + device * 0.8
    l90 = syn.day_profile(base, p.day_rise)
    rates = {k: v * factor for k, v in p.rates.items()}
    levels = {k: (lo + site.offset_db / 2, hi + site.offset_db / 2) for k, (lo, hi) in (p.levels or {}).items()}
    start_h, hours = site.schedule
    start = datetime(day.year, day.month, day.day) + timedelta(hours=start_h)
    end = min(start + timedelta(hours=hours), CLIP)
    r = random.Random(seed)
    events = syn.random_events(start, (end - start).total_seconds() / 3600, seed, rates, levels)
    # Fewer events in the small hours, where there is less traffic.
    events = [e for e in events if not (1 <= e.at.hour < 5 and r.random() < 0.6)]
    gaps = []
    if r.random() < 0.3:  # a phone call or a battery break now and then
        g = start + timedelta(minutes=r.randrange(0, max(1, int((end - start).total_seconds() // 60) - 60)))
        gaps.append((g, g + timedelta(minutes=r.randrange(10, 60))))
    events = [e for e in events if not any(a <= e.at < b for a, b in gaps)]
    calibrated = not site.uncalibrated
    cell = wgs84_to_cell(site.lat, site.lon) if site.with_cell else None
    mins = syn.minutes(start, end, l90, events, gaps=gaps, seed=seed, calibrated=calibrated,
                       offset_db=112.35 if site.uncalibrated else 118.0 + device, spread=p.spread, cell=cell)  # fmt: skip
    return mins, syn.event_records(events, l90, calibrated, cell)


def reports_for(site: Site, si: int) -> list[dict]:
    """Manual reports near the site's street: a few per week, more on busy streets, mostly evenings."""
    r = random.Random(7_000 + si)
    names = [c for c, _ in site.profile.reports]
    weights = [w for _, w in site.profile.reports]
    n = round(sum(site.profile.rates.values()) * 0.6 + 1)
    out = []
    for _ in range(n):
        day = TODAY - timedelta(days=r.randrange(0, DAYS))
        at = datetime(day.year, day.month, day.day, r.choice((7, 8, 12, 17, 18, 19, 20, 21, 22, 23)), r.randrange(60))
        if at > CLIP:
            at -= timedelta(days=1)
        lat = site.lat + r.uniform(-0.0012, 0.0012)
        lon = site.lon + r.uniform(-0.0016, 0.0016)
        out.append(syn.report_record(at, lat, lon, r.choices(names, weights)[0], accuracy_m=round(r.uniform(4, 30), 1)))
    return out


def build_db(path: Path) -> None:
    conn = db.connect(path)
    db.migrate(conn, now=COUNTED_SINCE_AT)
    received = "2026-10-08T16:30:00+00:00"
    for si, site in enumerate(SITES):
        cell = wgs84_to_cell(site.lat, site.lon)
        for d in range(site.devices):
            dev = f"fixture-{si:02d}-{d}"
            conn.execute("INSERT INTO devices (id, token_hash, created_at) VALUES (?, 'fixture', ?)", (dev, received))
            conn.execute(
                "INSERT INTO sites (device_id, cell, placement, floor, street_facing, device_model, audio_source, "
                "calibrated, updated_at) VALUES (?, ?, 'open_window', 2, 1, ?, 'UNPROCESSED', ?, ?)",
                (dev, cell, site.models[d % len(site.models)], int(not site.uncalibrated), received),
            )
            for i in range(site.days):
                day = TODAY - timedelta(days=i)
                if i == 0 and site.skip_today:
                    continue
                if i > 0 and random.Random(si * 1000 + d * 100 + i).random() < 0.12:
                    continue  # days without measurement
                if datetime(day.year, day.month, day.day) + timedelta(hours=site.schedule[0]) >= CLIP:
                    continue  # today's evening has not started yet
                mins, evs = day_records(site, d, day, seed=si * 10_000 + d * 1000 + i)
                # MinuteIn's "7 days old" rule is an API check, not a schema rule: the
                # fixture bypasses the API but not the schema, so the data is still validated.
                db.upsert_rows(
                    conn, "minutes", db.MINUTE_COLUMNS, [db.minute_row(dev, MinuteIn(**m), received) for m in mins]
                )
                db.upsert_rows(
                    conn, "events", db.EVENT_COLUMNS, [db.event_row(dev, EventIn(**e), received) for e in evs]
                )
            if d == 0:
                rows = [db.report_row(dev, ReportIn(**x), received) for x in reports_for(site, si)]
                for row in rows:  # one at a time: two random reports of a site may share a minute
                    db.upsert_rows(conn, "reports", db.REPORT_COLUMNS, [row], key="time_utc")
        conn.commit()
    conn.close()


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--map-dir", type=Path, help="also keep the full published tree here")
    args = ap.parse_args()
    cells = {wgs84_to_cell(s.lat, s.lon) for s in SITES}
    assert len(cells) == len(SITES), "two fixture sites fall into the same hectare"

    with tempfile.TemporaryDirectory() as tmp:
        dbp = Path(tmp) / "fixture.sqlite"
        build_db(dbp)
        out = args.map_dir or Path(tmp) / "map"
        with db.open_db(dbp) as conn:
            result = publish.publish(conn, out, now=NOW, min_devices=1)
        example = wgs84_to_cell(SITES[0].lat, SITES[0].lon)
        # Same content as the published files, indented so the fixture is readable in a diff.
        for src, dst in (
            (out / "cells.json", "example_cells.json"),
            (out / "cells" / f"{example}.json", "example_cell.json"),
            (out / "reports.json", "example_reports.json"),
        ):
            obj = json.loads(src.read_text(encoding="utf-8"))
            (SERVER / dst).write_text(json.dumps(obj, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        n_reports = len(json.loads((out / "reports.json").read_text(encoding="utf-8"))["reports"])
    print(
        f"{len(result['cells'])} cells, windows ending {result['day']} → example_cells.json; "
        f"{example} → example_cell.json; {n_reports} reports → example_reports.json"
    )


if __name__ == "__main__":
    main()
