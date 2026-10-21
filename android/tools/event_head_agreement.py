#!/usr/bin/env python3
"""Agreement of the phone's event head with the Labor training pipeline on real clips.

Inputs (read as data only):
  --replay   CSV of `./gradlew :dsp:replayBatch --args="--manifest …/manifest.jsonl --clips …/clips --out X.csv --offset manifest"`
             (+ X.csv.head_inputs.csv next to it), or the `<out>.clips.csv` of `:dsp:replayNight` (engine only)
  --dataset  /home/claude/labor/step3/train/dataset.npz   (keys, X1 = F1 inputs of every clip, f1_cols)
  --head     android/dsp/src/main/resources/ch/lessnoise/dsp/head/head_F1_MLP_v2.json
  --prefix   key prefix of the replayed set (kal | s3 | vor)

Python reference probability per clip = the JSON head applied to the dataset's F1 row (the
training pipeline's features.csv). Printed per class: Pearson r and max |Δp| between
  span:   the Kotlin port on the manifest's event span (ClipHarness, manifest detector values)
  engine: the replayed phone engine (48 kHz path, live detector, own event span)
and the Python probabilities; decision agreement for the enabled classes; per-input max |Δ| of
the span inputs vs the dataset's inputs.
"""
import argparse
import base64
import csv
import json
import math

import numpy as np


def head_predict(J, x):
    def unb64(d):
        return np.frombuffer(base64.b64decode(d["b64"]), dtype="<f4").reshape(d["shape"]).astype(np.float64)
    z = (np.where(np.isnan(x), np.array(J["median"]), x) - np.array(J["mean"])) / np.array(J["scale"])
    h = np.maximum(z @ unb64(J["W1"]) + unb64(J["b1"]), 0)
    return 1 / (1 + np.exp(-(h @ unb64(J["W2"]) + unb64(J["b2"]))))


def fnum(s):
    try:
        return float(s)
    except (TypeError, ValueError):
        return math.nan


def pearson(a, b):
    a, b = np.asarray(a, float), np.asarray(b, float)
    m = ~(np.isnan(a) | np.isnan(b))
    if m.sum() < 3 or np.std(a[m]) == 0 or np.std(b[m]) == 0:
        return math.nan, int(m.sum())
    return float(np.corrcoef(a[m], b[m])[0, 1]), int(m.sum())


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--replay", required=True)
    ap.add_argument("--dataset", default="/home/claude/labor/step3/train/dataset.npz")
    ap.add_argument("--head", default="android/dsp/src/main/resources/ch/lessnoise/dsp/head/head_F1_MLP_v2.json")
    ap.add_argument("--prefix", default="kal")
    a = ap.parse_args()
    J = json.load(open(a.head))
    D = np.load(a.dataset, allow_pickle=False)
    keys = [str(k) for k in D["keys"]]
    cols = [str(c) for c in D["f1_cols"]]
    idx = [cols.index(n) for n in J["inputs"]]
    X = D["X1"][:, idx].astype(np.float64)
    P = head_predict(J, X)
    row_of = {k: i for i, k in enumerate(keys)}
    classes = J["classes"]
    rows = list(csv.DictReader(open(a.replay)))
    night = rows and "v2_start_s" not in rows[0]  # replayNight <out>.clips.csv: no span columns, no span head
    if night:
        for r in rows:
            r.setdefault("v2_start_s", "nan"); r["manifest_start_s"] = "0"; r["manifest_dur_s"] = "0"
    py, eng, span, det, same = [], [], [], [], []
    for r in rows:
        k = "%s_ev_%s" % (a.prefix, r["event_id"])
        if k not in row_of:
            continue
        py.append(P[row_of[k]])
        span.append([fnum(r.get("span_" + c)) for c in classes])
        eng.append([fnum(r.get("head_" + c)) for c in classes])
        det.append(r["detected"] == "true")
        # The replayed event has the manifest's span (start and duration within half a tick).
        same.append(r["detected"] == "true" and abs(fnum(r["v2_start_s"]) - fnum(r["manifest_start_s"])) < 0.0625
                    and abs(fnum(r["v2_dur_s"]) - fnum(r["manifest_dur_s"])) < 0.0625)
    py, eng, span, det, same = np.array(py), np.array(eng), np.array(span), np.array(det), np.array(same)
    n_eng = int(np.sum(~np.isnan(eng[:, 0])))
    print("clips matched to the dataset: %d; engine event found: %d; span computed: %d" % (len(py), n_eng, int(np.sum(~np.isnan(span[:, 0])))))
    print()
    print("| class | enabled | span r | span max abs dp | engine r | engine median abs dp | engine max abs dp | n engine |")
    print("|---|---|---|---|---|---|---|---|")
    for j, c in enumerate(classes):
        rs, _ = pearson(span[:, j], py[:, j])
        re_, ne = pearson(eng[:, j], py[:, j])
        ds = np.nanmax(np.abs(span[:, j] - py[:, j])) if np.any(~np.isnan(span[:, j])) else math.nan
        de = np.abs(eng[:, j] - py[:, j])
        print("| %s | %s | %.4f | %.2g | %.3f | %.3f | %.3f | %d |" % (c, "yes" if J["enabled"][c] else "no", rs, ds, re_, np.nanmedian(de), np.nanmax(de), ne))
    if not same.any():
        same = None
    print()
    if same is not None:
        print("engine on the %d clips whose replayed event has the manifest's span:" % int(same.sum()))
    if same is not None:
        print()
        print("| class | engine r | engine median abs dp | engine max abs dp |")
        print("|---|---|---|---|")
        for j, c in enumerate(classes):
            r_, _ = pearson(eng[same, j], py[same, j])
            de = np.abs(eng[same, j] - py[same, j])
            print("| %s | %.3f | %.3f | %.3f |" % (c, r_, np.median(de), np.max(de)))
        print()
    for c in [c for c in classes if J["enabled"][c]]:
        j = classes.index(c)
        t = J["thresholds"][c]
        m = ~np.isnan(eng[:, j])
        a_ = (eng[m, j] >= t) == (py[m, j] >= t)
        s_ = (span[:, j] >= t) == (py[:, j] >= t)
        sm = ~np.isnan(span[:, j])
        print("decision %-9s thr %.4f: engine agrees on %d/%d (python positives %d, engine positives %d); span agrees on %d/%d"
              % (c, t, int(a_.sum()), int(m.sum()), int((py[m, j] >= t).sum()), int((eng[m, j] >= t).sum()), int(s_[sm].sum()), int(sm.sum())))
    # Inputs: span vs dataset
    try:
        inp = list(csv.DictReader(open(a.replay + ".head_inputs.csv")))
    except FileNotFoundError:
        return
    worst = {n: 0.0 for n in J["inputs"]}
    nanmis = {n: 0 for n in J["inputs"]}
    n = 0
    for r in inp:
        if r["source"] != "span":
            continue
        k = "%s_ev_%s" % (a.prefix, r["event_id"])
        if k not in row_of:
            continue
        n += 1
        x = X[row_of[k]]
        for i, name in enumerate(J["inputs"]):
            v = fnum(r[name])
            if math.isnan(v) and math.isnan(x[i]):
                continue
            if math.isnan(v) != math.isnan(x[i]):
                nanmis[name] += 1
                continue
            worst[name] = max(worst[name], abs(v - x[i]))
    print()
    print("span inputs vs dataset X1 over %d clips (CSV values rounded to 5 decimals): max |diff|, NaN mismatches" % n)
    for name in J["inputs"]:
        print("  %-30s %.3g  %d" % (name, worst[name], nanmis[name]))


if __name__ == "__main__":
    main()
