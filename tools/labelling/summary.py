"""summary.py — counts per class and per stage over a labels directory written by label.py.

  python3 -I tools/labelling/summary.py --labels labels --param model=gemini-3.8-flash --param stage=AB

Writes <labels>/summary.json (see README.md → Output). Reads every *.jsonl in the directory; rows are
grouped by their own `set` and `stage` fields (set names may contain dots, so file names are not parsed).
"""
from __future__ import annotations

import argparse
import collections
import json
import pathlib
import sys

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import common  # noqa: E402

YES_NO_A = ('road_vehicle', 'rain', 'wet_road', 'rail', 'aircraft', 'voices', 'wind_on_mic')


def _count(values):
    return dict(sorted(collections.Counter('null' if v is None else str(v) for v in values).items()))


def summarise_a(rows):
    groups = collections.Counter()
    for r in rows:
        groups.update(common.groups_fixed(r.get('label')))
    return dict(
        clips=len(rows),
        distinct_event=sum(1 for r in rows if r.get('distinct_event') is True),
        phone_or_body=_count(r.get('phone_or_body') for r in rows),
        yes={f: sum(1 for r in rows if r.get(f) in ('yes', 'likely')) for f in YES_NO_A},
        vehicle_type=_count(r.get('vehicle_type') for r in rows if r.get('vehicle_type')),
        heard_vehicle=sum(1 for r in rows if common.heard_vehicle(r)),
        label_groups=dict(sorted(groups.items())),
    )


def summarise_b(rows):
    changed = collections.Counter(r.get('class_change') for r in rows if r.get('class_change') not in (None, 'no'))
    return dict(
        clips=len(rows),
        before=_count(r.get('before') for r in rows),
        dominant=_count(r.get('dominant') for r in rows),
        loud_score={str(k): sum(1 for r in rows if r.get('loud_score') == k) for k in range(11)},
        class_change=dict(no=sum(1 for r in rows if r.get('class_change') == 'no'),
                          changed=sum(changed.values()), top=dict(changed.most_common(10))),
        invalid=sum(1 for r in rows if r.get('_invalid')),
    )


def summarise(labels_dir, params=None):
    labels_dir = pathlib.Path(labels_dir)
    rows = collections.defaultdict(list)          # (set, stage, model) -> rows
    errors = []
    for p in sorted(labels_dir.glob('*.jsonl')):
        if p.name == 'errors.jsonl':
            errors = common.read_jsonl(p)
            continue
        for r in common.read_jsonl(p):
            if r.get('stage') in ('A', 'B') and r.get('set'):
                rows[(r['set'], r['stage'], r.get('model'))].append(r)
    sets = {}
    labelled = set()
    for (s, stage, model), rs in sorted(rows.items(), key=lambda kv: tuple(str(x) for x in kv[0])):
        labelled |= {(r.get('key'), stage) for r in rs}
        entry = summarise_a(rs) if stage == 'A' else summarise_b(rs)
        entry['model'] = model
        entry['prompt'] = sorted({f"{r.get('prompt')} {r.get('prompt_sha')}" for r in rs})
        sets.setdefault(s, {})[f'{stage}' if model is None else f'{stage} {model}'] = entry
    failed = [e for e in errors if (e.get('key'), e.get('stage')) not in labelled]
    return dict(params=params or {}, sets=sets,
                failed=dict(clips=len({(e.get('key'), e.get('stage')) for e in failed}),
                            by_error=_count(str(e.get('error', ''))[:60] for e in failed)))


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument('--labels', default='labels')
    ap.add_argument('--param', action='append', default=[], metavar='KEY=VALUE', help='run parameter to record')
    a = ap.parse_args(argv)
    params = dict(p.split('=', 1) for p in a.param if '=' in p)
    d = pathlib.Path(a.labels)
    d.mkdir(parents=True, exist_ok=True)
    s = summarise(d, params)
    (d / 'summary.json').write_text(json.dumps(s, ensure_ascii=False, indent=1) + '\n', encoding='utf-8')
    for name, stages in s['sets'].items():
        for st, e in stages.items():
            print(f'{name} {st}: {e["clips"]} clips')
    print(f'failed: {s["failed"]["clips"]}')
    return 0


if __name__ == '__main__':
    sys.exit(main())
