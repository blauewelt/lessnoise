# tools/labelling — Gemini labels for Labor recordings

`label.py` sends each event clip of a Labor recording to a Gemini model (Google's multimodal model,
via the Gemini API) and writes what the model hears as JSON lines. It is the Step 2 labeller of
2026-10-20 (two stages, measured context, peak-normalised audio), ported out of the analysis folder,
with a new stage B for the class «loud vehicle». It runs on a laptop or in GitHub Actions
(`.github/workflows/labor-label.yml`). The app does not use it; nothing here changes the app.

**The recordings are personal data and are never committed to this repository** — not the zips, not
the WAVs, not the label files (they describe what was audible where and when the owner recorded).
Keep data and output outside the checkout; `labels/` at the repository root (the default `--out-dir`
when run from there) is git-ignored.

## Stages

- **Stage A** (every clip), prompt `prompts/stageA2.txt`, unchanged from Step 2: a 1–4-word label,
  all audible sources, and yes/no questions — `phone_or_body` (the phone or the person carrying it
  makes the dominant sound), `road_vehicle`, `rain`, `wet_road`, `rail`, `aircraft`, `voices`,
  `wind_on_mic`, `vehicle_type`, `description`.
- **Stage B** (clips where stage A heard a road vehicle: `road_vehicle` yes, a `vehicle_type`, or a
  road word in the label, as in Step 2), prompt `prompts/stageB_loud.txt`. It follows the owner's
  definition of a loud vehicle (analysis notes of 2026-10-23/24): a rise in level that stays road
  traffic, where the engine or exhaust is heard above the tyre noise. «Above a certain speed, hearing
  tyres means a normal vehicle; hearing the engine above the tyres means a loud vehicle.»

Both prompts get the numbers the app measured (local time, peak dB(A) — A-weighted decibels —, rise
over the local background, where the event starts in the clip and how long it lasts) and no hint about
the place or the source.

## Data layout it reads

`--data DIR` (or the environment variable `LABOR_DATA`) is a folder of sets. A **set** is any
directory directly under it that holds `manifest.jsonl`, or holds one folder that does (zips made on
the phone may wrap everything in `Laerm/`). The set name is the directory name under `DIR`.

```
DIR/
  rossberg-22oct/Laerm/manifest.jsonl, clips/ev_<id>_<yyyyMMdd_HHmmss>.wav, continuous/, classifier/
  carried-0.7.3/manifest.jsonl, clips/…
```

From `manifest.jsonl` only the lines with `"type":"clip"` are used, and of those the fields:
`eventId`, `file` (path of the WAV inside the set), `eventStart` (local time HH:MM), `lafMaxDb` (peak
level), `features.excessDb` (rise over the local floor; without detector-v2 features `lafMaxDb −
backgroundDb`), `durationS`, `offsetOfEventStartInClipMs`, `clipDurationMs`, `truncated`.
`classifierTrace` is dropped. Lines that do not parse are skipped and counted. WAVs are 16 kHz mono
16-bit; leading digital silence of 0.25 s or more is cut, then the audio is peak-normalised to 0.9 of
full scale in memory (the file is not changed) before it is uploaded.

Clip key: `<prefix>_ev_<eventId>`, prefix = `--prefix` or the set name.

## Commands

```sh
python3 -m pip install -r tools/labelling/requirements.txt
export GEMINI_API_KEY=…            # only from the environment; never an argument, never printed

# what would be sent (no key needed, nothing is sent)
python3 -I tools/labelling/label.py --data ~/labor-data --dry-run

# both stages on one set, first 20 clips, output in ~/labor-labels
python3 -I tools/labelling/label.py --data ~/labor-data --set rossberg-22oct --stage AB \
    --max-clips 20 --out-dir ~/labor-labels

# only some clips: a JSON list of keys ("rossberg-22oct_ev_812") or [set, file] pairs
python3 -I tools/labelling/label.py --data ~/labor-data --only keys.json --threads 8

# counts per set, stage and class → ~/labor-labels/summary.json
python3 -I tools/labelling/summary.py --labels ~/labor-labels --param model=gemini-3.8-flash
```

Options: `--model` (default `gemini-3.8-flash`), `--stage A|B|AB` (default AB: A, then B on A's
vehicle clips), `--set NAME` (repeatable; default all sets), `--prefix`, `--only`, `--max-clips N`
(the first N selected clips of each set in manifest order; 0 = all), `--threads` (default 6),
`--temperature` (0.2), `--thinking MINIMAL|LOW|MEDIUM|HIGH`, `--level-offset-db` (added to the
peak level in the context, for an uncalibrated phone), `--out-dir` (default `labels`), `--tag`
(prefix of the output names), `--prompt-a`/`--prompt-b` (other prompt files), `--dry-run`.

Runs are **resumable**: a clip already in the output file is skipped, so re-running the same command
after an interruption only does the rest. A failed clip (six attempts) goes to `errors.jsonl` and is
tried again on the next run.

Tests (no network, no key): `python3 -m pytest tools/labelling/tests -q`.

## GitHub Actions

Workflow «Labor labelling» (`workflow_dispatch` only; inputs `drive_file_id`, `set_name`, `model`,
`stage`, `max_clips`, `only_gist`). The owner shares the recording zip on Google Drive «anyone with
the link» for the run; the job downloads it, checks it is a zip and not Drive's HTML page, unzips it
into the runner's temp folder, runs stage A and B with the repository secret `GEMINI_API_KEY`,
deletes the audio (also on failure) and uploads `labels/` as artifact
`labels-<set_name>-<run id>` for 14 days. The repository is public: signed-in GitHub users can see
the run log (which shows the Drive file id) and download the artifact, so switch the link sharing off
after the run.

## Output

One file per set, stage and model: `<out-dir>/[<tag>.]<set>.<stage>.<model>.jsonl`, one JSON object
per clip.

Every row: `key`, `set`, `prefix`, `file`, `eventId`, `model`, `stage`, `tag`, `prompt` (file name),
`prompt_sha` (first 10 hex digits of the prompt's SHA-1), `context` (`hhmm`, `peak`, `excess`,
`offset`, `dur`, `clip_s`, `beyond`), `trimmed_s`, `temperature`, `thinking`, `latency_s`,
`_served_by` (model version that answered), `_tokens`.

Stage A adds: `label`, `all_sources`, `distinct_event` (bool), `phone_or_body`
(yes|likely|unlikely|no), `phone_or_body_reason`, `road_vehicle`, `rain`, `wet_road`, `rail`,
`aircraft`, `voices`, `wind_on_mic` (yes|no), `vehicle_type` (car|motorbike|truck_or_bus|scooter|
several|unknown|null), `description`.

Stage B adds:

| field | values |
|---|---|
| `before` | `traffic_hum`, `quiet`, `other` — what is audible before the rise |
| `dominant` | `tyres`, `engine`, `both`, `unclear` — the vehicle sound that dominates during the event |
| `loud_score` | integer 0–10 (0 = no vehicle in the event, 1 = tyre-dominated pass-by, 5 = engine as prominent as tyres, 10 = engine/exhaust far above ordinary traffic) |
| `class_change` | `no`, or 1–3 words naming the sound that causes the rise when it is not road traffic (`door`, `voice`, `aircraft` …) |
| `reason` | one sentence |
| `_invalid` | only when an answer was missing or out of range: the list of problems |

`errors.jsonl`: `key`, `set`, `file`, `stage`, `model`, `error` for clips that failed.

`summary.json` (`summary.py`): `params` (run parameters given with `--param`), `sets.<set>.<stage
model>` with `clips`, the prompt names and — stage A — `distinct_event`, `phone_or_body`, `yes`
(count per yes/no question), `vehicle_type`, `heard_vehicle` (clips that go to stage B),
`label_groups` (labels mapped to coarse groups by Step 2's word patterns); — stage B — `before`,
`dominant`, `loud_score` (clips per score 0–10), `class_change` (`no`, `changed`, the ten most
frequent sounds), `invalid`; and `failed` (clips in `errors.jsonl` that never succeeded).
