#!/usr/bin/env python3
"""Checks an APK of Weniger Lärm against the edition rules of PRIVACY.md («How to verify»,
«Labor-Build») and android/README.md (release checklist): permissions, manifest entries and what
the dex contains.

Usage (from android/):
  python3 tools/check_release_apk.py [--build-tools DIR] [EDITION=]APK ...
EDITION is offline | public | labor | laborKarte; without it the edition is read from the APK
(package ch.lessnoise.app or ch.lessnoise.labor, with or without INTERNET). Debug and release APKs
are checked alike (debug ones also contain x86_64 code; that is not checked here). Needs aapt2 and
dexdump from the Android SDK's build-tools (found via --build-tools, $ANDROID_HOME,
$ANDROID_SDK_ROOT or sdk.dir in local.properties). Exit code 1 if any rule fails.

0.8.4: the «Sehr laut» notification and the «Unterwegs» measurement (classes ch/lessnoise/app/alert/*,
the channel id string "loud_alert") are Labor·Karte-only; the Online-Version (public) must be as free
of them as the Offline-Version. The shortcut and widget «Laut! Melden» (ch/lessnoise/app/report/*,
android.app.shortcuts) are in public and laborKarte only.
"""
import argparse
import os
import re
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

EDITIONS = ("offline", "public", "labor", "laborKarte")
NET = ["android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE",
       "android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION"]
NEVER = ["android.permission.ACCESS_BACKGROUND_LOCATION", "android.permission.RECEIVE_BOOT_COMPLETED",
         "android.permission.ACCESS_WIFI_STATE"]

# Rules as (name, how to count, which editions must have > 0; all others must have 0).
MAP = {"public", "laborKarte"}
LABOR = {"labor", "laborKarte"}
DEX_RULES = [
    ("Labor classes ch/lessnoise/app/labor/*", "class", r"^Lch/lessnoise/app/labor/", LABOR),
    ("map tab classes ch/lessnoise/app/ui/MapScreen*", "class", r"^Lch/lessnoise/app/ui/MapScreen", MAP),
    ("ch/lessnoise classes referring to Landroid/webkit/WebView;", "ref", "Landroid/webkit/WebView;", MAP),
    ("motion detector classes ch/lessnoise/app/motion/MotionDetector*", "class", r"^Lch/lessnoise/app/motion/MotionDetector", MAP),
    ("ch/lessnoise classes referring to Landroid/hardware/SensorManager;", "ref", "Landroid/hardware/SensorManager;", MAP),
    ("«Sehr laut» / «Unterwegs» classes ch/lessnoise/app/alert/*", "class", r"^Lch/lessnoise/app/alert/", {"laborKarte"}),
    ("string \"loud_alert\" (the «Sehr laut» channel)", "string", b"loud_alert", {"laborKarte"}),
    ("shortcut/widget classes ch/lessnoise/app/report/*", "class", r"^Lch/lessnoise/app/report/", MAP),
    ("audio encoding (string Landroid/media/MediaMuxer;)", "string", b"Landroid/media/MediaMuxer;", LABOR),
]


def find_build_tools(arg):
    cands = []
    if arg:
        cands.append(Path(arg))
    for env in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        if os.environ.get(env):
            cands += sorted((Path(os.environ[env]) / "build-tools").glob("*"), reverse=True)
    lp = Path(__file__).resolve().parent.parent / "local.properties"
    if lp.is_file():
        m = re.search(r"^sdk\.dir=(.*)$", lp.read_text(), re.M)
        if m:
            cands += sorted((Path(m.group(1).strip()) / "build-tools").glob("*"), reverse=True)
    for c in cands:
        if (c / "aapt2").is_file() and (c / "dexdump").is_file():
            return c
    sys.exit("aapt2/dexdump not found: pass --build-tools <sdk>/build-tools/<version>")


def run(cmd):
    return subprocess.run(cmd, check=True, capture_output=True, text=True).stdout


def permissions(bt, apk):
    out = run([str(bt / "aapt2"), "dump", "permissions", str(apk)])
    return set(re.findall(r"uses-permission: name='([^']+)'", out))


def badging(bt, apk):
    out = run([str(bt / "aapt2"), "dump", "badging", str(apk)])
    m = re.search(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", out)
    return m.groups() if m else ("?", "?", "?")


def manifest(bt, apk):
    return run([str(bt / "aapt2"), "dump", "xmltree", "--file", "AndroidManifest.xml", str(apk)])


def dex_scan(bt, apk):
    """Class descriptors, the set of ch/lessnoise classes per referenced type, and the raw dex bytes."""
    classes, refs, raw = [], {}, b""
    needles = [r[2] for r in DEX_RULES if r[1] == "ref"]
    with tempfile.TemporaryDirectory() as tmp, zipfile.ZipFile(apk) as z:
        dexes = sorted(n for n in z.namelist() if re.fullmatch(r"classes\d*\.dex", n))
        for n in dexes:
            p = Path(tmp) / n
            p.write_bytes(z.read(n))
            raw += p.read_bytes()
            current = None
            proc = subprocess.Popen([str(bt / "dexdump"), "-d", str(p)], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, errors="replace")
            for line in proc.stdout:
                m = re.match(r"\s*Class descriptor\s*:\s*'([^']+)'", line)
                if m:
                    current = m.group(1)
                    classes.append(current)
                    continue
                if current and current.startswith("Lch/lessnoise/"):
                    for nd in needles:
                        if nd in line:
                            refs.setdefault(nd, set()).add(current)
            proc.wait()
    return classes, refs, raw, len(dexes)


def check(bt, edition, apk):
    fails = 0

    def report(ok, text):
        nonlocal fails
        fails += 0 if ok else 1
        print(f"  {'PASS' if ok else 'FAIL'}  {text}")

    pkg, code, name = badging(bt, apk)
    perms = permissions(bt, apk)
    if edition is None:
        edition = ("public" if NET[0] in perms else "offline") if pkg == "ch.lessnoise.app" else ("laborKarte" if NET[0] in perms else "labor")
    print(f"{apk}: {edition} — {pkg} {name} ({code})")
    want_pkg = "ch.lessnoise.app" if edition in ("offline", "public") else "ch.lessnoise.labor"
    report(pkg == want_pkg, f"package {pkg} (expected {want_pkg})")

    has_net = edition in MAP
    for p in NET:
        report((p in perms) == has_net, f"{'has' if has_net else 'lacks'} {p}")
    for p in NEVER:
        report(p not in perms, f"lacks {p}")
    fgs_loc = "android.permission.FOREGROUND_SERVICE_LOCATION"
    report((fgs_loc in perms) == (edition == "laborKarte"), f"{fgs_loc} only in laborKarte")

    xml = manifest(bt, apk)
    widget = "ch.lessnoise.app.report.ReportWidget" in xml
    shortcuts = "android.app.shortcuts" in xml
    report(widget == has_net, f"manifest {'registers' if has_net else 'does not register'} the widget ReportWidget")
    report(shortcuts == has_net, f"manifest {'declares' if has_net else 'does not declare'} the app shortcut (android.app.shortcuts)")

    classes, refs, raw, ndex = dex_scan(bt, apk)
    print(f"  ({len(classes)} classes in {ndex} dex files)")
    for title, kind, what, present_in in DEX_RULES:
        if kind == "class":
            n = sum(1 for c in classes if re.search(what, c))
        elif kind == "ref":
            n = len(refs.get(what, ()))
        else:
            n = raw.count(what)
        must = edition in present_in
        report((n > 0) == must, f"{title}: {n} ({'expected > 0' if must else 'expected 0'})")
    return fails


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--build-tools")
    ap.add_argument("apks", nargs="+", metavar="[EDITION=]APK")
    a = ap.parse_args()
    bt = find_build_tools(a.build_tools)
    total = 0
    for spec in a.apks:
        edition, _, path = spec.rpartition("=")
        if edition and edition not in EDITIONS:
            sys.exit(f"unknown edition {edition!r} (one of {', '.join(EDITIONS)})")
        total += check(bt, edition or None, Path(path))
        print()
    print("ALL PASS" if total == 0 else f"{total} FAILED")
    sys.exit(1 if total else 0)


if __name__ == "__main__":
    main()
