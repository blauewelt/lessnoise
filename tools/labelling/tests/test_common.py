"""Tests for tools/labelling (no network, no API key, no google-genai needed).

Run from the repository root:
    python3 -m pytest tools/labelling/tests -q
"""
from __future__ import annotations

import io
import json
import pathlib
import sys
import wave

import numpy as np
import pytest

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
import common  # noqa: E402
import label  # noqa: E402
import summary  # noqa: E402


def clip_line(eid, stamp='20261022_113427', **kw):
    c = {'type': 'clip', 'eventId': eid, 'file': f'clips/ev_{eid}_{stamp}.wav',
         'eventStart': '2026-10-22T23:34:27.890+02:00', 'durationS': 1.5, 'lafMaxDb': 62.0,
         'backgroundDb': 50.0, 'features': {'excessDb': 11.0}, 'offsetOfEventStartInClipMs': 5000,
         'clipDurationMs': 11500, 'classifierTrace': [{'t': 0}]}
    c.update(kw)
    return c


def write_wav(path, x, sr=16000):
    path.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(path), 'wb') as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(sr)
        w.writeframes(np.asarray(x, dtype=np.int16).tobytes())


def make_set(root, eids, wrap=None, extra_lines=()):
    base = root / wrap if wrap else root
    base.mkdir(parents=True, exist_ok=True)
    lines = [json.dumps({'type': 'session_start'})]
    for e in eids:
        lines.append(json.dumps({'type': 'event', 'eventId': e}))
        lines.append(json.dumps(clip_line(e)))
        write_wav(base / clip_line(e)['file'], (np.sin(np.arange(1600) / 5) * 300).astype(np.int16))
    lines += list(extra_lines)
    (base / 'manifest.jsonl').write_text('\n'.join(lines) + '\n')
    return base


# ---- set discovery and prefix -------------------------------------------------------------------------

def test_find_sets_plain_and_wrapped(tmp_path):
    make_set(tmp_path / 'carried', [1, 2])
    make_set(tmp_path / 'rossberg', [3], wrap='Laerm')
    (tmp_path / 'empty').mkdir()
    (tmp_path / 'deep' / 'a' / 'b').mkdir(parents=True)
    (tmp_path / 'deep' / 'a' / 'b' / 'manifest.jsonl').write_text('')   # two levels down: not a set
    sets = common.find_sets(tmp_path)
    assert sets == {'carried': tmp_path / 'carried', 'rossberg': tmp_path / 'rossberg' / 'Laerm'}


def test_find_sets_two_wrappers_and_data_is_set(tmp_path):
    make_set(tmp_path / 'x', [1], wrap='A')
    make_set(tmp_path / 'x', [2], wrap='B')
    assert sorted(common.find_sets(tmp_path)) == ['x-A', 'x-B']
    make_set(tmp_path / 'solo', [1])
    assert common.find_sets(tmp_path / 'solo') == {'solo': tmp_path / 'solo'}


def test_prefix_default_and_override(tmp_path):
    make_set(tmp_path / 'sill-rossberg', [7], wrap='Laerm')
    a = label.parse_args(['--data', str(tmp_path), '--dry-run'])
    lab = label.Labeller(a)
    assert lab.prefix == {'sill-rossberg': 'sill-rossberg'}
    assert common.clip_key(lab.prefix['sill-rossberg'], 7) == 'sill-rossberg_ev_7'
    a = label.parse_args(['--data', str(tmp_path), '--prefix', 'ros', '--dry-run'])
    assert label.Labeller(a).prefix == {'sill-rossberg': 'ros'}


def test_prefix_needs_single_set(tmp_path):
    make_set(tmp_path / 'a', [1]); make_set(tmp_path / 'b', [2])
    with pytest.raises(SystemExit):
        label.Labeller(label.parse_args(['--data', str(tmp_path), '--prefix', 'p']))


def test_data_from_env(tmp_path, monkeypatch):
    make_set(tmp_path / 'a', [1])
    monkeypatch.setenv('LABOR_DATA', str(tmp_path))
    assert label.parse_args([]).data == str(tmp_path)


# ---- manifest -----------------------------------------------------------------------------------------

def test_load_manifest_skips_broken_lines_and_duplicates(tmp_path):
    bad = ['{"type":"clip","eventId":9,"file":"clips/ev_9_x.wav"',         # cut off: skipped, counted
           '\x00\x00' + json.dumps(clip_line(4)) + '\x00',              # NUL padding: recovered
           '\x00\x00{"type":"clip","x":"\x00"}',                          # NUL inside: skipped, counted
           json.dumps(clip_line(1))]                                      # duplicate of clip 1
    root = make_set(tmp_path / 's', [1, 2], extra_lines=bad)
    clips, skipped = common.load_manifest(root)
    assert [c['eventId'] for c in clips] == [1, 2, 4]
    assert skipped == 2
    assert 'classifierTrace' not in clips[0]


def test_clip_path_refuses_escape(tmp_path):
    root = make_set(tmp_path / 's', [1])
    assert common.clip_path(root, clip_line(1)).is_file()
    with pytest.raises(ValueError):
        common.clip_path(root, {'file': '../../etc/passwd'})


def test_only_keys_and_pairs(tmp_path):
    make_set(tmp_path / 's', [1, 2, 3])
    only = tmp_path / 'only.json'
    only.write_text(json.dumps(['s_ev_1', ['s', 'clips/ev_3_20261022_113427.wav'], ['other', 'ev_2_x.wav']]))
    lab = label.Labeller(label.parse_args(['--data', str(tmp_path), '--only', str(only)]))
    clips, _ = lab.selected('s')
    assert [c['eventId'] for c in clips] == [1, 3]


def test_max_clips_takes_first_in_manifest_order(tmp_path):
    make_set(tmp_path / 's', [5, 4, 9])
    lab = label.Labeller(label.parse_args(['--data', str(tmp_path), '--max-clips', '2']))
    assert [c['eventId'] for c in lab.selected('s')[0]] == [5, 4]


# ---- context ------------------------------------------------------------------------------------------

def test_context_numbers_and_text():
    ctx = common.clip_context(clip_line(1), level_offset_db=15.0)
    assert ctx['hhmm'] == '23:34' and ctx['peak'] == 77.0 and ctx['excess'] == 11.0
    assert ctx['offset'] == 5.0 and not ctx['beyond']
    t = common.context_text(ctx)
    assert 'about 77 dB(A)' in t and '11 dB above' in t and 'lasts about 1.5 s' in t
    long = common.clip_context(clip_line(1, durationS=90.0, truncated=True))
    assert long['beyond'] and 'continues past the end' in common.context_text(long)


def test_prompts_have_context_slot_and_no_location_words():
    for name in ('stageA2.txt', 'stageB_loud.txt'):
        t = (HERE.parent / 'prompts' / name).read_text()
        assert '{context}' in t
        low = t.lower()
        for w in ('poser', 'motorway', 'autobahn', 'zürich', 'zurich', 'window', 'highway', 'street'):
            assert w not in low, (name, w)


# ---- resumable output ---------------------------------------------------------------------------------

def test_resume_skips_done_and_survives_cut_line(tmp_path):
    out = tmp_path / 'x.A.m.jsonl'
    clips = [clip_line(i) for i in (1, 2, 3)]
    assert [c['eventId'] for c in common.todo(clips, out)] == [1, 2, 3]       # no file yet
    out.write_text(json.dumps({'file': clips[0]['file'], 'label': 'car'}) + '\n'
                   + '{"file": "' + clips[1]['file'] + '", "lab')                # run killed mid-line
    assert [c['eventId'] for c in common.todo(clips, out)] == [2, 3]
    assert common.output_name('A', 'carried-0.7.3', 'gemini-3.8-flash') == 'carried-0.7.3.A.gemini-3.8-flash.jsonl'
    assert common.output_name('B', 's', 'm', tag='t') == 't.s.B.m.jsonl'


def test_stage_b_plan_is_gated_on_stage_a(tmp_path):
    make_set(tmp_path / 's', [1, 2, 3])
    out = tmp_path / 'labels'; out.mkdir()
    lab = label.Labeller(label.parse_args(['--data', str(tmp_path), '--out-dir', str(out)]))
    a_rows = [dict(file=clip_line(1)['file'], road_vehicle='yes', label='car pass-by'),
              dict(file=clip_line(2)['file'], road_vehicle='no', vehicle_type=None, label='cough'),
              dict(file=clip_line(3)['file'], road_vehicle='no', vehicle_type=None, label='distant traffic swell')]
    (out / 's.A.gemini-3.8-flash.jsonl').write_text(''.join(json.dumps(r) + '\n' for r in a_rows))
    clips, todo, _ = lab.plan('B', 's')
    assert [c['eventId'] for c in clips] == [1, 3]
    (out / 's.B.gemini-3.8-flash.jsonl').write_text(json.dumps({'file': clip_line(1)['file']}) + '\n')
    assert [c['eventId'] for c in lab.plan('B', 's')[1]] == [3]


# ---- audio --------------------------------------------------------------------------------------------

def test_peak_normalise_synthetic_wav(tmp_path):
    sr = 16000
    x = np.concatenate([np.zeros(sr // 2), (np.cos(2 * np.pi * 440 * np.arange(sr) / sr) * 100)]).astype(np.int16)
    p = tmp_path / 'quiet.wav'
    write_wav(p, x, sr)
    data, trimmed = common.load_audio(p)
    assert trimmed == pytest.approx(0.5, abs=1 / sr)                           # >= 0.25 s of zeros trimmed
    with wave.open(io.BytesIO(data)) as w:
        assert (w.getnchannels(), w.getsampwidth(), w.getframerate()) == (1, 2, sr)
        y = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16)
    assert len(y) == sr
    assert np.abs(y).max() == int(0.9 * 32767)
    assert p.read_bytes()[44:] == x.tobytes()                                   # file on disk untouched


def test_short_lead_not_trimmed_and_silence_stays_silent(tmp_path):
    sr = 16000
    x = np.concatenate([np.zeros(sr // 10), np.full(800, 50)]).astype(np.int16)   # 0.1 s of zeros
    write_wav(tmp_path / 'a.wav', x, sr)
    _, trimmed = common.load_audio(tmp_path / 'a.wav')
    assert trimmed == 0
    assert not common.peak_normalise(np.zeros(10, dtype=np.int16)).any()


# ---- stage-B answers and summary ----------------------------------------------------------------------

def test_check_b_normalises_and_flags():
    ok = {'before': 'Traffic hum', 'dominant': 'Engine', 'loud_score': '7', 'class_change': None, 'reason': 'r'}
    assert label.check_b(ok) == []
    assert ok['before'] == 'traffic_hum' and ok['dominant'] == 'engine' and ok['loud_score'] == 7 and ok['class_change'] == 'no'
    bad = {'before': 'loud', 'dominant': 'tyres', 'loud_score': 12, 'class_change': 'Door'}
    assert label.check_b(bad) == ['missing reason', 'before', 'loud_score'] and bad['class_change'] == 'door'


def test_summary_counts(tmp_path):
    rows_a = [dict(set='s.1', stage='A', model='m', key='k1', label='car pass-by', road_vehicle='yes', rain='no',
                   distinct_event=True, phone_or_body='no', vehicle_type='car', prompt='stageA2.txt', prompt_sha='x'),
              dict(set='s.1', stage='A', model='m', key='k2', label='footsteps', road_vehicle='no', distinct_event=False,
                   phone_or_body='yes', vehicle_type=None, prompt='stageA2.txt', prompt_sha='x')]
    rows_b = [dict(set='s.1', stage='B', model='m', key='k1', before='traffic_hum', dominant='engine', loud_score=7,
                   class_change='no', reason='r', prompt='stageB_loud.txt', prompt_sha='y')]
    (tmp_path / 's.1.A.m.jsonl').write_text(''.join(json.dumps(r) + '\n' for r in rows_a))
    (tmp_path / 's.1.B.m.jsonl').write_text(''.join(json.dumps(r) + '\n' for r in rows_b))
    (tmp_path / 'errors.jsonl').write_text(json.dumps(dict(key='k3', stage='A', error='ServerError: 503')) + '\n'
                                           + json.dumps(dict(key='k1', stage='B', error='old')) + '\n')
    summary.main(['--labels', str(tmp_path), '--param', 'model=m', '--param', 'max_clips=0'])
    s = json.loads((tmp_path / 'summary.json').read_text())
    a, b = s['sets']['s.1']['A m'], s['sets']['s.1']['B m']
    assert a['clips'] == 2 and a['yes']['road_vehicle'] == 1 and a['heard_vehicle'] == 1
    assert a['phone_or_body'] == {'no': 1, 'yes': 1} and a['label_groups'] == {'road': 1, 'steps': 1}
    assert b['dominant'] == {'engine': 1} and b['loud_score']['7'] == 1 and b['class_change']['no'] == 1
    assert s['failed']['clips'] == 1                                            # k1/B succeeded later
    assert s['params'] == {'model': 'm', 'max_clips': '0'}


# ---- the labelling loop with a fake client (no network) -----------------------------------------------

class _FakeModels:
    def __init__(self, key):
        self.key, self.calls = key, []

    def generate_content(self, model, contents, config):
        from types import SimpleNamespace as NS
        wav, prompt = contents
        self.calls.append(prompt)
        if len(self.calls) == 1:
            raise RuntimeError(f'503 for request with key={self.key}')            # first call fails once
        if 'loud vehicle score' in prompt:
            ans = {'before': 'quiet', 'dominant': 'engine', 'loud_score': 8, 'class_change': 'no', 'reason': 'r'}
        else:
            ans = {'label': 'car pass-by', 'road_vehicle': 'yes', 'vehicle_type': 'car'}
        return NS(text=json.dumps(ans), usage_metadata=None, model_version=model)


def test_run_a_then_b_with_fake_client(tmp_path, monkeypatch, capsys):
    from types import SimpleNamespace as NS
    make_set(tmp_path / 'data' / 's', [1, 2], wrap='Laerm')
    out = tmp_path / 'labels'
    a = label.parse_args(['--data', str(tmp_path / 'data'), '--out-dir', str(out), '--threads', '1'])
    lab = label.Labeller(a)
    out.mkdir()
    secret = 'AIza-test-secret'
    lab._key = secret
    lab.client = NS(models=_FakeModels(secret))
    lab.types = NS(GenerateContentConfig=lambda **k: k, ThinkingConfig=lambda **k: k,
                   Part=NS(from_bytes=lambda data, mime_type: data))
    monkeypatch.setattr(label.time, 'sleep', lambda s: None)
    assert lab.run('A') == (2, 2)
    assert lab.run('B') == (2, 2)
    assert lab.run('A') == (0, 0)                                               # resumed: nothing left
    rows_a = common.read_jsonl(out / 's.A.gemini-3.8-flash.jsonl')
    rows_b = common.read_jsonl(out / 's.B.gemini-3.8-flash.jsonl')
    assert sorted(r['key'] for r in rows_a) == ['s_ev_1', 's_ev_2']
    assert all(r['prompt'] == 'stageB_loud.txt' and r['loud_score'] == 8 and '_invalid' not in r for r in rows_b)
    assert '{context}' not in lab.client.models.calls[-1] and 'dB(A)' in lab.client.models.calls[-1]
    printed = capsys.readouterr().out
    assert 'attempt 0' in printed and secret not in printed and '***' in printed
