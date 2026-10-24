"""label.py — two-stage Gemini labeller for the clips of Weniger Lärm Labor recordings.

Stage A (every clip): what is audible — free-text label, all sources, and yes/no questions (phone or
  carrier makes the dominant sound, road vehicle, rain, wet road, rail, aircraft, voices, wind on the
  microphone), with the numbers the app measured as context.  Prompt: prompts/stageA2.txt (Step 2, unchanged).
Stage B (clips where stage A heard a road vehicle): «loud vehicle» questions — what was audible before
  the rise, whether tyres or engine dominate during it, a 0–10 loud-vehicle score, and whether the sound
  class changes during the rise.  Prompt: prompts/stageB_loud.txt.

Usage (always `python3 -I`; the API key comes only from the environment variable GEMINI_API_KEY):
  python3 -I tools/labelling/label.py --data /path/to/data --dry-run
  python3 -I tools/labelling/label.py --data /path/to/data --set rossberg --stage AB --max-clips 20
  LABOR_DATA=/path/to/data python3 -I tools/labelling/label.py --only keys.json --threads 8

See tools/labelling/README.md for the data layout, the options and the output schema.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import pathlib
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import common  # noqa: E402

PROMPT_A = HERE / 'prompts' / 'stageA2.txt'
PROMPT_B = HERE / 'prompts' / 'stageB_loud.txt'
B_FIELDS = ('before', 'dominant', 'loud_score', 'class_change', 'reason')
B_BEFORE = ('traffic_hum', 'quiet', 'other')
B_DOMINANT = ('tyres', 'engine', 'both', 'unclear')


def parse_args(argv=None):
    ap = argparse.ArgumentParser(description='Two-stage Gemini labeller for Labor clips (see README.md).')
    ap.add_argument('--data', default=os.environ.get('LABOR_DATA'),
                    help='directory holding the sets (default: env LABOR_DATA)')
    ap.add_argument('--set', action='append', dest='sets', metavar='NAME',
                    help='set to label (repeatable; default: every set found under --data)')
    ap.add_argument('--prefix', help='clip-key prefix (default: the set name); only with a single set')
    ap.add_argument('--model', default='gemini-3.8-flash')
    ap.add_argument('--stage', default='AB', choices=['A', 'B', 'AB'])
    ap.add_argument('--only', metavar='LIST.json',
                    help='JSON list of clip keys ("<prefix>_ev_<id>") or [set, file] pairs; other clips are skipped')
    ap.add_argument('--max-clips', type=int, default=0,
                    help='label at most the first N selected clips of each set in manifest order (0 = all)')
    ap.add_argument('--threads', type=int, default=6)
    ap.add_argument('--temperature', type=float, default=0.2)
    ap.add_argument('--thinking', choices=['MINIMAL', 'LOW', 'MEDIUM', 'HIGH'], help='default: the model default')
    ap.add_argument('--level-offset-db', type=float, default=0.0,
                    help='added to the peak level given as context (an uncalibrated phone; Step 2 used 15.42 for vor-kalibrierung)')
    ap.add_argument('--out-dir', default='labels')
    ap.add_argument('--tag', help='optional prefix of the output file names')
    ap.add_argument('--prompt-a', default=str(PROMPT_A))
    ap.add_argument('--prompt-b', default=str(PROMPT_B))
    ap.add_argument('--dry-run', action='store_true',
                    help='list what would be sent (clips per stage, prompt names); no API key needed, nothing is sent')
    a = ap.parse_args(argv)
    if not a.data:
        ap.error('give --data or set LABOR_DATA')
    if a.max_clips < 0 or a.threads < 1:
        ap.error('--max-clips must be >= 0 and --threads >= 1')
    return a


def load_only(path, prefixes):
    """Set of clip keys from an --only list (keys, or [set, file] pairs mapped with the sets' prefixes)."""
    items = json.loads(pathlib.Path(path).read_text(encoding='utf-8'))
    if not isinstance(items, list):
        raise SystemExit(f'--only: {path} is not a JSON list')
    keys = set()
    for x in items:
        if isinstance(x, str):
            keys.add(x)
        elif isinstance(x, (list, tuple)) and len(x) == 2 and x[0] in prefixes:
            eid = common.event_id_of_file(x[1])
            if eid is not None:
                keys.add(common.clip_key(prefixes[x[0]], eid))
    return keys


class Labeller:
    def __init__(self, a):
        self.a = a
        self.out_dir = pathlib.Path(a.out_dir)
        self.prompt = {'A': pathlib.Path(a.prompt_a).read_text(encoding='utf-8'),
                       'B': pathlib.Path(a.prompt_b).read_text(encoding='utf-8')}
        self.pname = {'A': pathlib.Path(a.prompt_a).name, 'B': pathlib.Path(a.prompt_b).name}
        self.psha = {s: hashlib.sha1(t.encode()).hexdigest()[:10] for s, t in self.prompt.items()}
        found = common.find_sets(a.data)
        if not found:
            raise SystemExit(f'no set (directory with {common.MANIFEST}) under {a.data}')
        names = a.sets or sorted(found)
        missing = [n for n in names if n not in found]
        if missing:
            raise SystemExit(f'set(s) not found under {a.data}: {", ".join(missing)}; found: {", ".join(sorted(found))}')
        if a.prefix and len(names) != 1:
            raise SystemExit('--prefix needs exactly one set (use --set)')
        self.sets = {n: found[n] for n in names}
        self.prefix = {n: (a.prefix or common.default_prefix(n)) for n in names}
        self.only = load_only(a.only, self.prefix) if a.only else None
        self.lock = threading.Lock()
        self.client = None
        self._key = None

    # -- selection ---------------------------------------------------------------------------------
    def selected(self, set_name):
        clips, bad = common.load_manifest(self.sets[set_name])
        p = self.prefix[set_name]
        if self.only is not None:
            clips = [c for c in clips if common.clip_key(p, c['eventId']) in self.only]
        if self.a.max_clips:
            clips = clips[:self.a.max_clips]
        return clips, bad

    def out_path(self, stage, set_name):
        return self.out_dir / common.output_name(stage, set_name, self.a.model, self.a.tag)

    def plan(self, stage, set_name):
        clips, bad = self.selected(set_name)
        if stage == 'B':
            road = {r['file'] for r in common.read_jsonl(self.out_path('A', set_name)) if common.heard_vehicle(r)}
            clips = [c for c in clips if c['file'] in road]
        todo = common.todo(clips, self.out_path(stage, set_name))
        return clips, todo, bad

    # -- API ---------------------------------------------------------------------------------------
    def connect(self):
        key = os.environ.get('GEMINI_API_KEY')
        if not key:
            raise SystemExit('GEMINI_API_KEY is not set (the key is read from the environment only)')
        from google import genai  # imported here so that --dry-run and the tests need no google-genai
        from google.genai import types
        self._key, self.types = key, types
        self.client = genai.Client(api_key=key)

    def redact(self, s):
        s = str(s)
        return s.replace(self._key, '***') if self._key else s

    def ask(self, prompt, wav):
        t = self.types
        cfg = t.GenerateContentConfig(
            response_mime_type='application/json', temperature=self.a.temperature,
            thinking_config=t.ThinkingConfig(thinking_level=self.a.thinking) if self.a.thinking else None)
        r = self.client.models.generate_content(
            model=self.a.model, contents=[t.Part.from_bytes(data=wav, mime_type='audio/wav'), prompt], config=cfg)
        lab = json.loads(r.text)
        if isinstance(lab, list) and lab:
            lab = lab[0]
        if not isinstance(lab, dict):
            raise ValueError('response is not a JSON object')
        u = r.usage_metadata
        lab['_served_by'] = getattr(r, 'model_version', None)
        lab['_tokens'] = dict(prompt=getattr(u, 'prompt_token_count', None), output=getattr(u, 'candidates_token_count', None),
                              thoughts=getattr(u, 'thoughts_token_count', None)) if u else None
        return lab

    def append(self, path, row):
        with self.lock:
            with open(path, 'a', encoding='utf-8') as f:
                f.write(json.dumps(row, ensure_ascii=False) + '\n')

    def work(self, job):
        stage, set_name, c, out = job
        key = common.clip_key(self.prefix[set_name], c['eventId'])
        try:
            wav, trimmed = common.load_audio(common.clip_path(self.sets[set_name], c))
        except Exception as e:  # missing or unreadable WAV: logged, not retried
            print(f'{key} {stage}: audio not readable: {type(e).__name__}: {e}', flush=True)
            self.append(self.out_dir / 'errors.jsonl', dict(key=key, set=set_name, file=c['file'], stage=stage,
                                                          model=self.a.model, error=f'audio: {type(e).__name__}'))
            return 0
        ctx = common.clip_context(c, self.a.level_offset_db)
        if trimmed:
            ctx['offset'] = max(0.0, ctx['offset'] - trimmed)
        prompt = self.prompt[stage].replace('{context}', common.context_text(ctx))
        err = None
        for attempt in range(6):
            try:
                t0 = time.time()
                lab = self.ask(prompt, wav)
                if stage == 'B':
                    bad = check_b(lab)
                    if bad:
                        lab['_invalid'] = bad
                lab.update(key=key, set=set_name, prefix=self.prefix[set_name], file=c['file'], eventId=c['eventId'],
                           model=self.a.model, stage=stage, tag=self.a.tag, prompt=self.pname[stage],
                           prompt_sha=self.psha[stage], context=ctx, trimmed_s=round(trimmed, 2),
                           temperature=self.a.temperature, thinking=self.a.thinking,
                           latency_s=round(time.time() - t0, 1))
                self.append(out, lab)
                return 1
            except Exception as e:
                err = f'{type(e).__name__}: {self.redact(e)[:160]}'
                print(f'{key} {stage} attempt {attempt}: {err}', flush=True)
                time.sleep(5 * (attempt + 1))
        self.append(self.out_dir / 'errors.jsonl', dict(key=key, set=set_name, file=c['file'], stage=stage,
                                                      model=self.a.model, error=err))
        return 0

    # -- stages ------------------------------------------------------------------------------------
    def run(self, stage):
        jobs = []
        for n in self.sets:
            clips, todo, bad = self.plan(stage, n)
            print(f'stage {stage} {n}: {len(clips)} clips selected, {len(clips) - len(todo)} done, {len(todo)} to do'
                  + (f' ({bad} manifest lines skipped)' if bad else ''), flush=True)
            jobs += [(stage, n, c, self.out_path(stage, n)) for c in todo]
        t0 = time.time()
        with ThreadPoolExecutor(self.a.threads) as ex:
            ok = sum(ex.map(self.work, jobs))
        dt = time.time() - t0
        print(f'stage {stage}: {ok}/{len(jobs)} ok in {dt / 60:.1f} min', flush=True)
        return ok, len(jobs)

    def dry_run(self):
        print(f'dry run: model {self.a.model}, stage {self.a.stage}, out-dir {self.out_dir}, nothing is sent')
        for s in self.a.stage:
            print(f'prompt {s}: {self.pname[s]} (sha1 {self.psha[s]}, {len(self.prompt[s])} characters)')
        for n, root in self.sets.items():
            print(f'set {n}: root {root}, key prefix {self.prefix[n]!r}')
            for s in self.a.stage:
                clips, todo, bad = self.plan(s, n)
                missing, secs = 0, 0.0
                for c in todo:
                    try:
                        p = common.clip_path(root, c)
                        secs += (c.get('clipDurationMs') or 0) / 1000.0
                        if not p.is_file():
                            missing += 1
                    except ValueError:
                        missing += 1
                note = ''
                if s == 'B' and 'A' in self.a.stage:
                    note = ' (gate = stage-A output present now; stage A of this run adds to it)'
                print(f'  stage {s}: {len(clips)} clips selected, {len(clips) - len(todo)} done, {len(todo)} would be sent'
                      f' ({secs / 60:.1f} min of audio, {missing} WAV missing){note}'
                      + (f'; {bad} manifest lines skipped' if bad else ''))
                for c in todo[:3]:
                    print(f'    e.g. {common.clip_key(self.prefix[n], c["eventId"])} {c["file"]}')


def check_b(lab):
    """Normalise a stage-B answer in place; returns the list of problems (missing field, value out of range).
    A problem does not stop the run: the row is kept with `_invalid` so the summary can count it."""
    bad = [f'missing {f}' for f in B_FIELDS if f not in lab]
    if 'before' in lab:
        lab['before'] = str(lab['before']).strip().lower().replace(' ', '_')
        if lab['before'] not in B_BEFORE: bad.append('before')
    if 'dominant' in lab:
        lab['dominant'] = str(lab['dominant']).strip().lower()
        if lab['dominant'] not in B_DOMINANT: bad.append('dominant')
    if 'loud_score' in lab:
        try:
            score = int(round(float(lab['loud_score'])))
        except (TypeError, ValueError):
            score = None
        if score is None or not 0 <= score <= 10:
            bad.append('loud_score')
        else:
            lab['loud_score'] = score
    if 'class_change' in lab:
        cc = lab['class_change']
        lab['class_change'] = 'no' if cc in (None, False, '', 'none') else str(cc).strip().lower()
    return bad


def main(argv=None):
    a = parse_args(argv)
    lab = Labeller(a)
    if a.dry_run:
        lab.dry_run()
        return 0
    lab.out_dir.mkdir(parents=True, exist_ok=True)
    lab.connect()
    for s in a.stage:
        lab.run(s)
    print('done', flush=True)
    return 0


if __name__ == '__main__':
    sys.exit(main())
