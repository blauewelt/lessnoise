#!/usr/bin/env python3
"""Golden fixtures for the Kotlin port of the event head (android/dsp, package ch.lessnoise.dsp.head).

Writes 32 small synthetic 16 kHz mono 16-bit WAVs (deterministic, seeded) and, for each, the
feature values, model inputs and head probabilities computed by the Labor analysis's own Python
code on exactly those WAVs:

  android/dsp/src/test/resources/eventhead/<name>.wav
  android/dsp/src/test/resources/eventhead/golden.json

Reference code (read-only, outside the repository; pass other paths with the options):
  --features  /home/claude/labor/step3/features.py        tick_series, kotlin_features, tail_end,
                                                          envelope_features, tick_spectra, spectral_features
  --dataset   /home/claude/labor/step3/train/dataset.py   f1_row (model input transforms)
  --head      android/dsp/src/main/resources/ch/lessnoise/dsp/head/head_F1_MLP_v2.json

Usage:  python3 android/tools/event_head_golden.py   (needs numpy, scipy)

Each clip plays the role of a Labor clip: `start` = event start tick (the manifest's
offsetOfEventStartInClipMs / 125), `durationS` = the manifest duration. The level-envelope
extras are computed with the same statements as features.py compute_clip (copied below, marked);
everything else calls the reference functions. YAMNet is not involved (the head does not use it).
"""
import argparse
import base64
import importlib.util
import json
import math
import os
import wave

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
ANDROID = os.path.dirname(HERE)
OUT = os.path.join(ANDROID, "dsp/src/test/resources/eventhead")
FS = 16000
OFFSET = 127.773  # calibrated offset used by features.py for every set


def load(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m


# ----------------------------------------------------------------------------- synthetic clips
def butter(x, kind, fc, order=2):
    from scipy.signal import butter as bt, lfilter
    b, a = bt(order, fc / (FS / 2), btype=kind)
    return lfilter(b, a, x)


def band_noise(rng, n, lo, hi):
    x = rng.standard_normal(n)
    if lo:
        x = butter(x, "high", lo, 4)
    if hi:
        x = butter(x, "low", hi, 4)
    return x / max(np.sqrt(np.mean(x ** 2)), 1e-12)


def harmonics(n, f0_start, f0_end, k=6, rolloff=0.7):
    t = np.arange(n) / FS
    f0 = np.linspace(f0_start, f0_end, n)
    ph = 2 * np.pi * np.cumsum(f0) / FS
    x = sum((rolloff ** (h - 1)) * np.sin(h * ph) for h in range(1, k + 1))
    return x / np.sqrt(np.mean(x ** 2))


def env_sin(n):
    return np.sin(np.pi * np.arange(n) / n)


def env_hann(n):
    return 0.5 - 0.5 * np.cos(2 * np.pi * np.arange(n) / n)


def clicks(rng, n, rate_hz, width=80):
    x = np.zeros(n)
    period = int(FS / rate_hz)
    for s in range(int(period * 0.3), n - width, period):
        j = s + rng.randint(-200, 200)
        if 0 <= j < n - width:
            x[j:j + width] += rng.standard_normal(width) * np.exp(-np.arange(width) / 15)
    return x / max(np.sqrt(np.mean(x ** 2)), 1e-12)


def make(kind, seed, pre_s, dur_s, post_s, level_db, bg_rms=3e-4, **kw):
    """Background noise (white, pink-ish) + an event of `kind` of `dur_s` starting after `pre_s`."""
    rng = np.random.RandomState(seed)
    n_pre, n_ev, n_post = int(pre_s * FS), int(dur_s * FS), int(post_s * FS)
    n = n_pre + n_ev + n_post
    bg = rng.standard_normal(n)
    bg = 0.5 * bg + 0.5 * butter(bg, "low", 800, 1) * 3
    bg *= bg_rms / np.sqrt(np.mean(bg ** 2))
    a = bg_rms * 10 ** (level_db / 20)  # event RMS relative to the background
    m = n_ev
    if kind == "hump":
        ev = band_noise(rng, m, 300, 3000) * env_sin(m)
    elif kind == "passby":  # engine harmonics with a Doppler drop + tyre noise
        ev = (0.8 * harmonics(m, kw.get("f0a", 120), kw.get("f0b", 95)) + 0.6 * band_noise(rng, m, 400, 4000)) * env_sin(m) ** 2
    elif kind == "rev":  # revving engine: rising harmonics, rising level
        ev = harmonics(m, kw.get("f0a", 80), kw.get("f0b", 260), k=8, rolloff=0.8) * np.linspace(0.3, 1.0, m) * env_hann(m) ** 0.3
    elif kind == "thump":
        ev = band_noise(rng, m, 25, 80) * env_hann(m)
    elif kind == "knock":
        ev = np.zeros(m)
        w = min(m, 1600)
        ev[:w] = rng.standard_normal(w) * np.exp(-np.arange(w) / 200)
        ev /= np.sqrt(np.mean(ev ** 2))
    elif kind == "steps":
        ev = clicks(rng, m, kw.get("rate", 2.0)) + 0.1 * band_noise(rng, m, 100, 2000)
    elif kind == "voices":
        t = np.arange(m) / FS
        am = 0.5 + 0.5 * np.sin(2 * np.pi * kw.get("syll", 4.0) * t + rng.uniform(0, 6.28)) ** 2
        v = harmonics(m, 180, 150, k=12, rolloff=0.85)
        v = butter(v, "low", 2500, 2)
        ev = v / np.sqrt(np.mean(v ** 2)) * am
    elif kind == "wind":
        flutter = np.exp(np.cumsum(rng.standard_normal(m) * 0.004))
        flutter = flutter / flutter.mean()
        ev = band_noise(rng, m, 20, 150) * flutter * env_hann(m) ** 0.2
    elif kind == "rain":
        ev = band_noise(rng, m, 500, 7000) * np.minimum(1, np.arange(m) / (0.3 * FS)) + 0.5 * clicks(rng, m, 15.0, 30)
    elif kind == "aircraft":
        ev = (band_noise(rng, m, 50, 1500) + 0.3 * harmonics(m, 300, 280, k=3)) * env_sin(m) ** 1.5
    elif kind == "squeal":
        ev = (np.sin(2 * np.pi * kw.get("f", 2600) * np.arange(m) / FS) + 0.3 * band_noise(rng, m, 200, 2000)) * env_sin(m) ** 0.5
    elif kind == "tone":
        ev = np.sqrt(2) * np.sin(2 * np.pi * 1000 * np.arange(m) / FS)
    else:
        raise ValueError(kind)
    ev = ev / max(np.sqrt(np.mean(ev ** 2)), 1e-12) * a
    x = bg.copy()
    x[n_pre:n_pre + n_ev] += ev
    for (s0, s1) in kw.get("silence", []):  # digital silence (invalid audio), seconds
        x[int(s0 * FS):int(s1 * FS)] = 0.0
    return np.clip(x, -1, 32767 / 32768)


CLIPS = [
    # name, kind, seed, pre_s, dur_s, post_s, level_db, extra
    ("hump_a", "hump", 101, 1.5, 3.0, 1.25, 14, {}),
    ("hump_b", "hump", 102, 1.5, 1.5, 1.25, 10, {}),
    ("hump_loud", "hump", 103, 1.5, 4.0, 1.25, 25, {}),
    ("passby_a", "passby", 111, 1.5, 3.0, 1.25, 16, {}),
    ("passby_b", "passby", 112, 1.5, 2.25, 1.25, 12, {"f0a": 90, "f0b": 70}),
    ("passby_long", "passby", 113, 1.5, 5.0, 1.25, 18, {}),
    ("rev_a", "rev", 121, 1.5, 2.5, 1.25, 20, {}),
    ("rev_b", "rev", 122, 1.5, 3.5, 1.25, 24, {"f0a": 60, "f0b": 320}),
    ("thump_a", "thump", 131, 1.5, 0.5, 1.25, 30, {}),
    ("thump_b", "thump", 132, 1.5, 0.75, 1.25, 22, {}),
    ("knock_a", "knock", 141, 1.5, 0.5, 1.25, 18, {}),
    ("knock_b", "knock", 142, 1.5, 1.0, 1.25, 26, {}),
    ("steps_2hz", "steps", 151, 1.5, 3.0, 1.25, 15, {"rate": 2.0}),
    ("steps_3hz", "steps", 152, 1.5, 2.5, 1.25, 12, {"rate": 3.0}),
    ("voices_a", "voices", 161, 1.5, 2.5, 1.25, 14, {}),
    ("voices_b", "voices", 162, 1.5, 3.5, 1.25, 18, {"syll": 3.0}),
    ("wind_a", "wind", 171, 1.5, 3.0, 1.25, 20, {}),
    ("wind_b", "wind", 172, 1.5, 2.0, 1.25, 28, {}),
    ("rain_a", "rain", 181, 1.5, 2.5, 1.25, 12, {}),
    ("aircraft_a", "aircraft", 191, 1.5, 4.5, 1.25, 15, {}),
    ("squeal_a", "squeal", 201, 1.5, 2.0, 1.25, 20, {}),
    ("squeal_b", "squeal", 202, 1.5, 1.25, 1.25, 16, {"f": 3400}),
    ("tone_a", "tone", 211, 1.5, 2.0, 1.25, 15, {}),
    ("short_a", "hump", 221, 1.5, 0.5, 1.25, 15, {}),
    ("short_edge", "knock", 222, 0.5, 0.75, 1.25, 20, {}),           # window extension clamped at the clip start
    ("tail_cut", "hump", 231, 1.5, 2.5, 0.25, 15, {}),               # clip ends 0.25 s after the event: extension/tail clamped
    ("trunc", "passby", 232, 1.5, 3.0, 0.0, 15, {"trunc": 1.0}),     # event runs past the clip end
    ("pre_silence", "hump", 241, 1.5, 2.0, 1.25, 15, {"silence": [(0.2, 0.4)]}),
    ("ev_silence", "passby", 242, 1.5, 3.0, 1.25, 15, {"silence": [(2.6, 2.65)]}),
    ("pre_all_silent", "hump", 243, 1.0, 2.0, 2.0, 15, {"silence": [(0.0, 0.6)]}),
    ("quiet_a", "voices", 251, 1.5, 2.0, 1.25, 7, {}),
    ("rev_short", "rev", 252, 1.5, 1.25, 1.25, 22, {}),
]


def write_wav(path, x):
    q = np.round(np.clip(x, -1, 32767 / 32768) * 32767).astype("<i2")
    with wave.open(path, "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(FS)
        w.writeframes(q.tobytes())


def num(v):
    v = float(v)
    return None if math.isnan(v) or math.isinf(v) else v


def head_predict(J, x):
    def unb64(d):
        return np.frombuffer(base64.b64decode(d["b64"]), dtype="<f4").reshape(d["shape"]).astype(np.float64)
    from scipy.special import expit
    z = (np.where(np.isnan(x), np.array(J["median"]), x) - np.array(J["mean"])) / np.array(J["scale"])
    return expit(np.maximum(z @ unb64(J["W1"]) + unb64(J["b1"]), 0) @ unb64(J["W2"]) + unb64(J["b2"]))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--features", default="/home/claude/labor/step3/features.py")
    ap.add_argument("--dataset", default="/home/claude/labor/step3/train/dataset.py")
    ap.add_argument("--head", default=os.path.join(ANDROID, "dsp/src/main/resources/ch/lessnoise/dsp/head/head_F1_MLP_v2.json"))
    a = ap.parse_args()
    F = load(a.features, "features_ref")
    D = load(a.dataset, "dataset_ref")
    J = json.load(open(a.head))
    os.makedirs(OUT, exist_ok=True)
    clips = []
    for name, kind, seed, pre_s, dur_s, post_s, level, kw in CLIPS:
        x = make(kind, seed, pre_s, dur_s, post_s, level, **{k: v for k, v in kw.items() if k != "trunc"})
        path = os.path.join(OUT, name + ".wav")
        write_wav(path, x)
        x = F.read_wav(path)  # exactly what the reference reads
        ts = F.tick_series(x, OFFSET)
        nt = ts["nt"]
        start = int(round(pre_s * 8))
        duration_s = dur_s + kw.get("trunc", 0.0)
        end_full = start + int(round(duration_s * 8))
        end = min(end_full, nt)
        out = {}
        # --- copied from features.py compute_clip (floor, LAFmax, tail, kotlin features, extras) ---
        floor = F.percentile_lin(ts["laf"][:start], 10.0)
        floor_source = "pre"
        if math.isnan(floor):
            floor = F.percentile_lin(ts["laf"][end:], 10.0)
            floor_source = "post"
        lafmax = float(np.nanmax(ts["lafmax_tick"][start:end])) if end > start else np.nan
        te, tail_clip_end = F.tail_end(F.Ticks(ts["laf"]), start, end, floor)
        kt, p, best_start = F.kotlin_features(ts, start, end, max(te, end), floor, lafmax, duration_s)
        out.update(kt)
        out["laf_floor_pre_db"] = floor
        out["laf_max_abs_db"] = lafmax
        curve_peak = float(np.nanmax(ts["laf"][start:end])) if end > start else np.nan
        out["peak_excess_db"] = curve_peak - floor
        out["peak_time_in_event_s"] = (p - start) * 0.125
        seg = ts["laf"][start:min(nt, te)]
        out["dur_above6_s"] = float(np.sum(seg >= floor + 6.0)) * 0.125
        out["tail_end_ticks"] = te - end
        if end - start >= 3:
            L = ts["laf"][start:end]
            d2 = L[2:] - 2 * L[1:-1] + L[:-2]
            out["env_d2_rms_db"] = float(np.sqrt(np.mean(d2 ** 2)))
        else:
            out["env_d2_rms_db"] = np.nan
        Lb = 10 * np.log10(np.maximum(ts["eBand"], 1e-30))
        lo_i = max(0, start - 8)
        rises = [Lb[i + 4] - Lb[i] for i in range(lo_i, end) if i + 4 < nt]
        out["onset_mid_rise_0p5s_db"] = float(np.nanmax(rises)) if len(rises) and not np.all(np.isnan(rises)) else np.nan
        F.envelope_features(ts, start, end, out)
        P = F.tick_spectra(x, nt)
        w = min(F.MID_TOP, max(end - start, 1))
        F.spectral_features(P, ts["invalid"], start, end, p, best_start, w, OFFSET, out)
        # --- end of copy ---
        background = floor - 2.0  # stands in for the manifest's 5-min background
        row = {"set": "kalibriert", "lafMaxDb_corr": lafmax, "backgroundDb_corr": background, "durationS": duration_s,
               "local_hour": 23.0, "motion_state": "STOP", "s_since_session_start": 600.0}
        for k in ("localFloorDb", "excessDb", "riseS", "decayS", "jaggedness", "midBandRiseDb", "lfShare", "lfFlutterDb", "wind", "shape"):
            row["app_" + k] = out["kt_" + k]
        for k, v in out.items():
            row.setdefault(k, v)
        f1 = D.f1_row(row)
        xin = np.array([f1[c] for c in J["inputs"]], float)
        prob = head_predict(J, xin[None, :])[0]
        decision = [c for i, c in enumerate(J["classes"]) if J["enabled"][c] and prob[i] >= J["thresholds"][c]]
        clips.append({
            "name": name, "kind": kind, "file": name + ".wav", "startTick": start, "durationS": duration_s,
            "ticks": int(nt), "endTick": int(end), "backgroundDb": background, "floorSource": floor_source,
            "peakTick": int(p), "bestStart": int(best_start), "topTicks": int(w), "tailEndTick": int(te),
            "features": {k: (num(v) if not isinstance(v, str) else v) for k, v in out.items()},
            "inputs": [num(v) for v in xin],
            "probabilities": [float(v) for v in prob],
            "decision": decision,
        })
    golden = {
        "generator": "android/tools/event_head_golden.py",
        "reference": {"features": os.path.abspath(a.features), "dataset": os.path.abspath(a.dataset)},
        "offsetDb": OFFSET, "sampleRate": FS, "wavScale": 32767.0,
        "inputNames": J["inputs"], "classes": J["classes"], "clips": clips,
    }
    with open(os.path.join(OUT, "golden.json"), "w") as fh:
        json.dump(golden, fh, indent=1)
    size = sum(os.path.getsize(os.path.join(OUT, c["file"])) for c in clips)
    print("%d clips, %.2f MB of WAV -> %s" % (len(clips), size / 1e6, OUT))


if __name__ == "__main__":
    main()
