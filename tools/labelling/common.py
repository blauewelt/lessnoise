"""Shared helpers of the Labor labeller (tools/labelling): set discovery, manifest reading, clip context,
resumable JSONL output, audio normalisation, the stage-B gate.

Ported from the Step 2 labeller of 2026-10-20 (label_v3.py / common.py). Differences: no hard-coded data
root (sets are found under a directory given on the command line), key prefixes come from the set name or
--prefix instead of a fixed table, the vor-kalibrierung level fix is a parameter (level_offset_db), and
manifest and output lines that do not parse are skipped instead of stopping the run.

Everything read here is untrusted data (exports from a phone): run the scripts with `python3 -I`.
"""
from __future__ import annotations

import io
import json
import pathlib
import re
import wave

import numpy as np

MANIFEST = 'manifest.jsonl'

# ---- set discovery ------------------------------------------------------------------------------------

def find_sets(data):
    """{set name: set root} for every set under `data`.

    A set is a directory that contains manifest.jsonl. The set name is the name of the directory directly
    under `data`; when a zip wrapped everything in one folder (e.g. `<set>/Laerm/manifest.jsonl`), the
    manifest is searched one level further down and the set root is that wrapping folder, the name stays
    `<set>`. Two wrapping folders with a manifest in one directory become `<set>-<folder>` each.
    `data` itself is a set when it contains manifest.jsonl directly (name = its own directory name).
    """
    data = pathlib.Path(data)
    if not data.is_dir():
        raise FileNotFoundError(f'data directory not found: {data}')
    if (data / MANIFEST).is_file():
        return {data.name: data}
    out = {}
    for d in sorted(p for p in data.iterdir() if p.is_dir() and not p.name.startswith('.')):
        if (d / MANIFEST).is_file():
            out[d.name] = d
            continue
        inner = sorted(p for p in d.iterdir() if p.is_dir() and (p / MANIFEST).is_file())
        if len(inner) == 1:
            out[d.name] = inner[0]
        else:
            for p in inner:
                out[f'{d.name}-{p.name}'] = p
    return out


def default_prefix(set_name):
    """Key prefix of a set when --prefix is not given: the set name itself."""
    return set_name


def clip_key(prefix, event_id):
    return f'{prefix}_ev_{event_id}'


def event_id_of_file(file):
    """eventId from a clip file name `ev_<id>_<yyyyMMdd_HHmmss>.wav` (directory part ignored)."""
    m = re.match(r'ev_(\d+)_', pathlib.PurePosixPath(str(file)).name)
    return int(m.group(1)) if m else None

# ---- manifest ------------------------------------------------------------------------------------------

def load_manifest(set_root):
    """Clip lines of a set's manifest, in file order, one per clip file (first line wins), without the
    bulky classifierTrace. NUL bytes around a line (left by an interrupted write) are stripped; lines that
    are still not valid JSON (a manifest cut off by a full phone) are skipped and counted.

    Returns (clips, skipped_lines).
    """
    clips, seen, bad = [], set(), 0
    with open(pathlib.Path(set_root) / MANIFEST, 'rb') as fh:
        for raw in fh:
            if b'"clip"' not in raw:
                continue
            try:
                c = json.loads(raw.strip(b'\x00 \t\r\n').decode('utf-8'))
            except (UnicodeDecodeError, json.JSONDecodeError):
                bad += 1
                continue
            if not isinstance(c, dict) or c.get('type') != 'clip' or not c.get('file') or c.get('eventId') is None:
                continue
            if c['file'] in seen:
                continue
            seen.add(c['file'])
            c.pop('classifierTrace', None)
            clips.append(c)
    return clips, bad


def clip_path(set_root, c):
    """Absolute path of a clip's WAV; refuses a `file` that points outside the set (untrusted manifest)."""
    root = pathlib.Path(set_root).resolve()
    p = (root / c['file']).resolve()
    if root != p and root not in p.parents:
        raise ValueError(f'clip file outside the set: {c["file"]!r}')
    return p

# ---- context given to the model ------------------------------------------------------------------------

def clip_context(c, level_offset_db=0.0):
    """Numbers given to the model as context. Never mentions location or what the source is.
    `level_offset_db` is added to the peak level (Step 2 used +15.42 dB for the uncalibrated
    vor-kalibrierung night)."""
    hhmm = str(c.get('eventStart') or '')[11:16]
    peak = float(c['lafMaxDb']) + level_offset_db
    f = c.get('features') or {}
    excess = f.get('excessDb')
    if excess is None:
        excess = float(c['lafMaxDb']) - float(c['backgroundDb'])
    off = (c.get('offsetOfEventStartInClipMs') or 0) / 1000.0
    clip_s = (c.get('clipDurationMs') or 0) / 1000.0
    dur = float(c.get('durationS') or 0.0)
    beyond = c.get('truncated') or (clip_s and off + dur > clip_s + 0.5)
    return dict(hhmm=hhmm, peak=peak, excess=float(excess), offset=off, dur=dur, clip_s=clip_s, beyond=bool(beyond))


def context_text(ctx):
    """Copied verbatim from Step 2 (common.context_text)."""
    dur = f"lasts about {ctx['dur']:.1f} s" if not ctx['beyond'] else \
        f"lasts {ctx['dur']:.0f} s, longer than the clip, so it continues past the end of the clip"
    return (f"Context measured by the recording app (numbers, not hints about the source):\n"
            f"- The recorder is a smartphone. During this recording it was either lying still or being carried by a person; you are not told which.\n"
            f"- Local time of the event: {ctx['hhmm']}.\n"
            f"- Measured peak level of the event: about {ctx['peak']:.0f} dB(A), which is {ctx['excess']:.0f} dB above the local background level measured just before it.\n"
            f"- The detected event starts about {ctx['offset']:.1f} s into the clip and {dur}. The clip also contains a few seconds before and after the event.\n")

# ---- resumable output ----------------------------------------------------------------------------------

def read_jsonl(p):
    """Rows of a JSONL file; a missing file is empty, a line that does not parse (a run killed while
    writing) is skipped so that its clip is labelled again."""
    p = pathlib.Path(p)
    if not p.exists():
        return []
    out = []
    with open(p, encoding='utf-8', errors='replace') as fh:
        for line in fh:
            line = line.strip()
            if not line:
                continue
            try:
                r = json.loads(line)
            except json.JSONDecodeError:
                continue
            if isinstance(r, dict):
                out.append(r)
    return out


def done_files(out_path):
    """Clip files already labelled in an output file (as Step 2: matched on the manifest's `file`)."""
    return {r['file'] for r in read_jsonl(out_path) if r.get('file')}


def todo(clips, out_path):
    """Clips not yet in the output file, order kept."""
    done = done_files(out_path)
    return [c for c in clips if c['file'] not in done]


def output_name(stage, set_name, model, tag=None):
    base = f'{set_name}.{stage}.{model}.jsonl'
    return f'{tag}.{base}' if tag else base

# ---- audio ---------------------------------------------------------------------------------------------

def peak_normalise(x, target=0.9):
    """int16 samples scaled so that the largest magnitude is `target` of full scale (all-zero stays zero)."""
    x = np.asarray(x, dtype=np.float32)
    peak = float(np.abs(x).max()) if x.size else 0.0
    return (x * (target * 32767 / (peak or 1.0))).astype(np.int16)


def load_audio(path, target=0.9):
    """Step 2's audio path: 16-bit mono WAV, leading digital silence (>= 0.25 s of exact zeros) trimmed,
    peak-normalised to `target` full scale, in memory. Returns (wav bytes, trimmed seconds)."""
    with wave.open(str(path)) as w:
        if w.getsampwidth() != 2 or w.getnchannels() != 1:
            raise ValueError(f'expected 16-bit mono WAV, got {w.getnchannels()} ch x {8 * w.getsampwidth()} bit')
        sr = w.getframerate()
        x = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float32)
    nz = np.flatnonzero(x)
    lead = int(nz[0]) if len(nz) else 0
    trim = lead if lead >= 0.25 * sr else 0
    y = peak_normalise(x[trim:], target)
    b = io.BytesIO()
    with wave.open(b, 'wb') as o:
        o.setnchannels(1); o.setsampwidth(2); o.setframerate(sr); o.writeframes(y.tobytes())
    return b.getvalue(), trim / sr

# ---- stage-B gate (copied from Step 2: scripts/score.py GROUPS, common.groups_fixed) --------------------

GROUPS = [
    ('poser',     r'poser|revv|accelerat(ing|es)? hard|aggressive|screech|loud exhaust'),
    ('road',      r'traffic|\bcar\b|cars|motorway|autobahn|vehicle|truck|\bbus\b|scooter|motorbike|engine|pass-by|passing'),
    ('aircraft',  r'aircraft|airplane|plane|jet|flyover'),
    ('rail',      r'train|tram|rail'),
    ('voices',    r'voice|speech|talk|announce|crowd|child|shout|conversation|babble|chatter|station'),
    ('steps',     r'walk|running|\brun\b|footstep|jog|steps|stride'),
    ('breathing', r'breath|pant|sniff|nose|sigh|cough'),
    ('handling',  r'handl|pocket|fabric|rustl|zipper|rubbing|carried|bag'),
    ('rain',      r'rain|wet|wiper'),
    ('wind',      r'wind|gust'),
    ('impulse',   r'click|thud|knock|door|snap|bang|boom|ding|chime|clap|tear|cutlery|clink|clack|squeak|beep|tick|thump|impact|thunder|bird|chirp|horn|whine'),
    ('hiss',      r'hiss cloud|hiss swell|mid-band|tyre|tire'),
    ('nothing',   r'nothing|steady|background|rumble|\bhum\b|rushing|hiss|ambient|drone|idling'),
]
RAIN_FIXED = r'\brain|drizzle|downpour|raindrop'
WIND_FIXED = r'\bwind\b|\bwindy\b|gust'


def groups(text):
    t = (text or '').lower()
    if not t:
        return set()
    g = {name for name, pat in GROUPS if re.search(pat, t)}
    if 'poser' in g: g.add('road')
    if 'hiss' in g: g.discard('hiss'); g.add('road')
    if 'cutlery' in t: g.add('impulse')
    if 'nothing' in g and len(g) > 1: g.discard('nothing')
    return g or {'other'}


def groups_fixed(text):
    """groups() with word-boundary rain/wind patterns ("train" is not rain, "window" is not wind)."""
    t = (text or '').lower()
    g = groups(t)
    g.discard('rain'); g.discard('wind')
    if re.search(RAIN_FIXED, t): g.add('rain')
    if re.search(WIND_FIXED, t): g.add('wind')
    if g - {'other'}: g.discard('other')
    if 'nothing' in g and len(g) > 1: g.discard('nothing')
    return g or {'other'}


def heard_vehicle(row):
    """Stage-A row says a road vehicle is audible (Step 2's gate: the yes/likely answer, a vehicle type,
    or a road word in the free-text label)."""
    return (row.get('road_vehicle') in ('yes', 'likely') or bool(row.get('vehicle_type'))
            or 'road' in groups_fixed(row.get('label')))
