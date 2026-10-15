"""The 30-second publish cycle (DESIGN.md §6, «Publish cadence»): the change marker decides
between a full run, an incremental run and no run; an incremental run writes the same content
as a full run would for every file it writes."""

from __future__ import annotations

import json
import sqlite3
import time
from datetime import date, datetime, timedelta
from pathlib import Path

import pytest
from test_api import SITE, event, minute, recent, register, report
from test_publish import LAST, NOW, add_day, add_device, add_report

from stadtlaerm_server import aggregate as agg
from stadtlaerm_server import db, publish
from stadtlaerm_server import synthetic as syn

CELL_A, CELL_B, CELL_C = "h26824_12473", "h26830_12490", "h26900_12500"


@pytest.fixture
def conn(tmp_path: Path):
    c = db.connect(tmp_path / "db.sqlite")
    db.migrate(c)
    yield c
    c.close()


def load(p: Path) -> dict:
    return json.loads(p.read_text())


def snapshot(out: Path) -> dict[str, tuple[int, str]]:
    return {str(p.relative_to(out)): (p.stat().st_mtime_ns, p.read_text()) for p in sorted(out.rglob("*.json"))}


def span_hours(day: date, start_h: int, hours: int) -> set[int]:
    """The UTC hours (epoch ms // 1 h) the API notes for records from `start_h` o'clock on `day`
    for `hours` hours (add_day's span)."""
    a = syn.epoch_ms(datetime(day.year, day.month, day.day) + timedelta(hours=start_h))
    b = syn.epoch_ms(datetime(day.year, day.month, day.day) + timedelta(hours=start_h + hours))
    return set(range(a // agg.HOUR_MS, -(-b // agg.HOUR_MS)))


def network(conn) -> dict[str, str]:
    """Three devices, ten days: two in cell A (one with own cells), one in B."""
    a = add_device(conn, CELL_A, dev="dev-a")
    b = add_device(conn, CELL_A, model="Galaxy A54", dev="dev-b")
    c = add_device(conn, CELL_B, model="Pixel 7a", dev="dev-c")
    for i in range(10):
        day = LAST - timedelta(days=i)
        add_day(conn, a, day, 44.0, 6.0, 100 + i, start_h=0, hours=10)
        add_day(conn, c, day, 38.0, 2.0, 200 + i, calibrated=False, hours=6)
    add_day(conn, b, LAST, 40.0, 3.0, 300, hours=4, cell=CELL_A)
    add_report(conn, a, datetime(2026, 10, 25, 9, 12), 47.3553, 8.5355)
    conn.commit()
    return {"a": a, "b": b, "c": c}


def test_run_decides_full_incremental_or_nothing(conn, tmp_path):
    devs = network(conn)
    out = tmp_path / "map"
    p = publish.Publisher(out)
    nothing = publish.Pending

    assert p.run(conn, NOW, nothing()) == "full"  # the first run after a (re)start
    before = snapshot(out)
    assert p.run(conn, NOW + timedelta(seconds=30), nothing()) == "skipped"
    assert snapshot(out) == before  # not even rewritten: ETags stay, browsers get 304

    # New minutes of device c today: only an incremental run.
    add_day(conn, devs["c"], LAST, 60.0, 30.0, 999, start_h=17, hours=1)
    conn.commit()
    pend = publish.Pending(touched={devs["c"]: span_hours(LAST, 17, 1)})
    assert p.run(conn, NOW + timedelta(seconds=60), pend) == "incremental"
    after = snapshot(out)
    rewritten = {k for k in after if after[k][0] != before[k][0]}
    # stats.json: same counts (device c was active today already), so not rewritten either
    assert rewritten == {"cells.json", f"days/{LAST}.json", f"cells/{CELL_B}.json"}

    # Ten minutes after the last full run: a full run even with nothing new.
    assert p.run(conn, NOW + timedelta(seconds=599), nothing()) == "skipped"
    assert p.run(conn, NOW + timedelta(seconds=600), nothing()) == "full"
    full_at = NOW + timedelta(seconds=600)
    assert load(out / "cells.json")["generated_at"] == "2026-10-25T18:50:00+01:00"

    # Something that only a full run gets right (a site, a delete): full at once.
    assert p.run(conn, full_at + timedelta(seconds=30), publish.Pending(full=True)) == "full"
    # The operator hides a device from outside the process: noticed by the fingerprint.
    conn.execute("UPDATE devices SET hidden = 1 WHERE id = ?", (devs["b"],))
    conn.commit()
    assert p.run(conn, full_at + timedelta(seconds=60), nothing()) == "full"
    assert load(out / "cells.json")["network"]["device_models"] == {"Pixel 7a": 1, "Pixel 8": 1}
    assert p.run(conn, full_at + timedelta(seconds=90), nothing()) == "skipped"


def test_first_run_after_midnight_is_full(conn, tmp_path):
    network(conn)
    out = tmp_path / "map"
    p = publish.Publisher(out)
    evening = datetime.fromisoformat("2026-10-25T23:59:40+01:00")
    assert p.run(conn, evening, publish.Pending()) == "full"
    assert p.run(conn, evening + timedelta(seconds=10), publish.Pending()) == "skipped"
    assert p.run(conn, evening + timedelta(seconds=30), publish.Pending()) == "full"  # 00:00:10
    cells = load(out / "cells.json")
    assert cells["day"] == "2026-10-26" and cells["periods"]["last_7_days"]["from"] == "2026-10-20"
    assert (out / "days" / "2026-10-26.json").exists() and not (out / "days" / "2026-07-28.json").exists()
    # Same with records waiting: the window shift needs the full run.
    p2 = publish.Publisher(tmp_path / "map2")
    p2.run(conn, evening, publish.Pending())
    assert (
        p2.run(conn, evening + timedelta(seconds=30), publish.Pending(touched={"dev-a": span_hours(LAST, 20, 1)}))
        == "full"
    )


@pytest.mark.parametrize("min_devices", [1, 2])
def test_incremental_run_writes_what_a_full_run_writes(conn, tmp_path, min_devices):
    devs = network(conn)
    out = tmp_path / "map"
    p = publish.Publisher(out, min_devices=min_devices)
    p.run(conn, NOW, publish.Pending())
    first = snapshot(out)

    # Late and new records: device a three days back (moved to its own cell C) and today;
    # device b today in its cell; a new device d (site in B, never on the map before); a report.
    add_day(conn, devs["a"], LAST - timedelta(days=3), 52.0, 9.0, 501, start_h=18, hours=3, cell=CELL_C)
    add_day(conn, devs["a"], LAST, 47.0, 9.0, 502, start_h=12, hours=2)
    add_day(conn, devs["b"], LAST, 41.0, 4.0, 503, start_h=11, hours=2, cell=CELL_A)
    d = add_device(conn, CELL_B, model="Fairphone 5", dev="dev-d")
    add_day(conn, d, LAST, 45.0, 5.0, 504, start_h=8, hours=3)
    add_report(conn, d, datetime(2026, 10, 25, 18, 20), 47.36, 8.52, "poser")
    conn.commit()
    pend = publish.Pending(
        touched={
            devs["a"]: span_hours(LAST - timedelta(days=3), 18, 3) | span_hours(LAST, 12, 2),
            devs["b"]: span_hours(LAST, 11, 2),
            d: span_hours(LAST, 8, 3),
        },
        reports=True,
    )
    later = NOW + timedelta(seconds=90)
    assert p.run(conn, later, pend) == "incremental"
    inc = snapshot(out)

    ref = tmp_path / "ref"
    publish.publish(conn, ref, now=later, min_devices=min_devices)
    full = snapshot(ref)
    assert set(inc) == set(full)
    rewritten = {k for k in inc if inc[k][0] != first.get(k, (None,))[0]}
    for name in full:
        got, want = json.loads(inc[name][1]), json.loads(full[name][1])
        if name in rewritten:
            assert got == want, name  # every file the incremental run wrote is exactly the full run's
        else:
            # untouched: only generated_at (and, in day files older than the oldest touched day,
            # the whole-network counts) wait for the next full run
            for x in (got, want):
                x.pop("generated_at", None)
                x.pop("network", None)
            assert got == want, name
    three_days_ago = (LAST - timedelta(days=3)).isoformat()
    assert {f"days/{(LAST - timedelta(days=i)).isoformat()}.json" for i in range(4)} <= rewritten
    assert f"days/{(LAST - timedelta(days=4)).isoformat()}.json" not in rewritten
    assert {"cells.json", "reports.json", "stats.json"} <= rewritten
    assert f"cells/{CELL_A}.json" in rewritten
    if min_devices == 1:  # a lone device's cell C is shown only with MIN_DEVICES_PER_CELL = 1
        assert f"cells/{CELL_C}.json" in rewritten
        assert load(out / "cells" / f"{CELL_C}.json")["days"][0]["day"] == three_days_ago
    else:
        assert not (out / "cells" / f"{CELL_C}.json").exists()
    assert len(load(out / "reports.json")["reports"]) == 2


def test_many_incremental_runs_stay_equal_to_full_runs(conn, tmp_path):
    """Random uploads over six cycles — new, late, replaced records, several cells — and after
    each incremental run every file it wrote equals a full run's, and cells.json always does."""
    import random

    r = random.Random(7)
    devs = network(conn)
    devs["d"] = add_device(conn, None, dev="dev-d")  # no site: only records with their own cell count
    conn.commit()
    out = tmp_path / "map"
    p = publish.Publisher(out)
    t = NOW - timedelta(minutes=5)
    p.run(conn, t, publish.Pending())
    for i in range(6):
        pend = publish.Pending()
        for _ in range(r.randint(1, 3)):
            dev = r.choice(sorted(devs.values()))
            day = LAST - timedelta(days=r.choice([0, 0, 0, 1, 2, 6]))
            start_h, hours = r.randrange(0, 17), r.randint(1, 3)
            cell = r.choice([None, CELL_A, CELL_C]) if dev != "dev-d" else r.choice([CELL_A, CELL_C])
            add_day(conn, dev, day, r.uniform(35, 60), r.uniform(1, 20), r.randrange(10**6),
                    start_h=start_h, hours=hours, cell=cell)  # fmt: skip
            pend.touched.setdefault(dev, set()).update(span_hours(day, start_h, hours))
        conn.commit()
        t += timedelta(seconds=30)
        before = snapshot(out)
        assert p.run(conn, t, pend) == "incremental", i
        inc = snapshot(out)
        ref = tmp_path / f"ref{i}"
        publish.publish(conn, ref, now=t)
        full = snapshot(ref)
        assert set(inc) == set(full), i
        assert inc["cells.json"][1] == full["cells.json"][1], i
        for name in inc:
            if inc[name][0] != before.get(name, (None,))[0]:
                assert inc[name][1] == full[name][1], (i, name)


def test_incremental_run_removes_a_cell_that_lost_its_data(conn, tmp_path):
    """Replaced records (a duplicate start wins, §4.3) can empty a cell: its file goes."""
    a = add_device(conn, CELL_A, dev="dev-a")
    add_day(conn, a, LAST, 44.0, 6.0, 1, start_h=6, hours=1, cell=CELL_C)
    add_day(conn, a, LAST, 44.0, 6.0, 2, start_h=8, hours=1)
    conn.commit()
    out = tmp_path / "map"
    p = publish.Publisher(out)
    p.run(conn, NOW, publish.Pending())
    assert (out / "cells" / f"{CELL_C}.json").exists()
    conn.execute("UPDATE minutes SET cell = NULL WHERE device_id = ?", (a,))  # as if re-sent without a cell
    conn.execute("UPDATE events SET cell = NULL WHERE device_id = ?", (a,))
    conn.commit()
    assert (
        p.run(conn, NOW + timedelta(seconds=30), publish.Pending(touched={a: span_hours(LAST, 6, 3)})) == "incremental"
    )
    assert not (out / "cells" / f"{CELL_C}.json").exists()
    assert [c["cell"] for c in load(out / "cells.json")["cells"]] == [CELL_A]
    assert load(out / "cells" / f"{CELL_A}.json")["days"][0]["measured_hours"] == 2.0


def test_changes_marker_is_taken_once_and_put_back_after_a_failure():
    ch = publish.Changes()
    assert ch.take().empty
    t = int(datetime.fromisoformat("2026-10-25T23:30:00+01:00").timestamp() * 1000)
    ch.records("dev", [t, t + 3_600_000])  # 23:30 and 00:30 local: two days
    ch.records("dev", [])
    ch.reports()
    p = ch.take()
    assert p.touched == {"dev": {t // agg.HOUR_MS, t // agg.HOUR_MS + 1}} and p.reports and not p.full
    assert p.days() == {"dev": {date(2026, 10, 25), date(2026, 10, 26)}}
    assert ch.take().empty  # taken: the next run starts from nothing
    ch.registered()
    ch.put_back(p)  # the run failed: nothing is lost, and the next run is a full one
    q = ch.take()
    assert q.touched == p.touched and q.reports and q.stats and q.full


def test_api_notes_changes_and_the_cycle_follows_them(client, settings):
    """Through the API: what each request marks, and what the next cycle does."""
    cycle = client.app.state.publish_cycle
    assert cycle() == "full"  # the first cycle
    assert cycle() == "skipped"

    dev, auth = register(client)
    assert client.app.state.changes.take().stats  # a registration: stats.json only …
    client.app.state.changes.registered()
    assert cycle() == "incremental"

    assert client.put(f"/v1/devices/{dev}/site", json=SITE, headers=auth).status_code == 204
    assert cycle() == "full"  # a new site: records without a cell move with it
    assert client.put(f"/v1/devices/{dev}/site", json={**SITE, "note": "anders"}, headers=auth).status_code == 204
    assert cycle() == "skipped"  # same cell and phone model: nothing published changes
    assert client.put(f"/v1/devices/{dev}/site", json={**SITE, "cell": "h26830_12490"}, headers=auth).status_code == 204
    assert cycle() == "full"

    r = client.post(f"/v1/devices/{dev}/minutes", json=[minute(recent(5)), minute(recent(4))], headers=auth)
    assert r.json()["accepted"] == 2
    client.post(f"/v1/devices/{dev}/events", json=[event(recent(5), 80.0)], headers=auth)
    assert cycle() == "incremental"
    cells = json.loads((settings.map_dir / "cells.json").read_text())
    (entry,) = cells["cells"]
    assert entry["cell"] == "h26830_12490" and entry["day"]["loud_events"] == 1
    # A request that changes nothing: rejected records, an empty upload.
    client.post(f"/v1/devices/{dev}/minutes", json=[], headers=auth)
    client.post(f"/v1/devices/{dev}/minutes", json=[minute(recent(60 * 24 * 9))], headers=auth)
    assert cycle() == "skipped"

    client.post(f"/v1/devices/{dev}/reports", json=[report(recent(3))], headers=auth)
    assert cycle() == "incremental"
    assert len(json.loads((settings.map_dir / "reports.json").read_text())["reports"]) == 1

    # Deleting: gone from every file on the next cycle.
    assert client.delete(f"/v1/devices/{dev}", headers=auth).status_code == 204
    assert cycle() == "full"
    assert json.loads((settings.map_dir / "cells.json").read_text())["cells"] == []
    assert json.loads((settings.map_dir / "reports.json").read_text())["reports"] == []
    assert not list((settings.map_dir / "cells").glob("*.json"))


def test_background_loop_publishes_new_records_within_a_cycle(make_client, settings):
    client = make_client(background_jobs=True, publish_interval_s=0.05)

    def wait_for(check) -> bool:
        for _ in range(200):
            try:
                if check():
                    return True
            except (FileNotFoundError, json.JSONDecodeError):
                pass
            time.sleep(0.02)
        return False

    assert wait_for(lambda: (settings.map_dir / "cells.json").exists())
    dev, auth = register(client)
    client.put(f"/v1/devices/{dev}/site", json=SITE, headers=auth)
    client.post(f"/v1/devices/{dev}/minutes", json=[minute(recent(5))], headers=auth)
    assert wait_for(lambda: json.loads((settings.map_dir / "cells.json").read_text())["cells"])
    client.delete(f"/v1/devices/{dev}", headers=auth)
    assert wait_for(lambda: not json.loads((settings.map_dir / "cells.json").read_text())["cells"])


def test_failed_cycle_keeps_the_changes(client, settings, monkeypatch):
    cycle = client.app.state.publish_cycle
    cycle()
    dev, auth = register(client)
    client.put(f"/v1/devices/{dev}/site", json=SITE, headers=auth)
    cycle()
    client.post(f"/v1/devices/{dev}/minutes", json=[minute(recent(5))], headers=auth)

    def broken(*_a, **_k):
        raise sqlite3.OperationalError("disk I/O error")

    monkeypatch.setattr(publish, "load_device", broken)
    with pytest.raises(sqlite3.OperationalError):
        cycle()
    monkeypatch.undo()
    assert cycle() == "full"
    assert json.loads((settings.map_dir / "cells.json").read_text())["cells"]
