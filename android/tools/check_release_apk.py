#!/usr/bin/env python3
"""Release checks of an APK of Weniger Lärm (android/README.md → «Release checklist», PRIVACY.md →
«How to verify» and «Labor-Build»). One script since 0.9.0: the release checks of 0.8.0 (signer,
version, exact permission sets, no Labor code) merged with the edition rules of 0.8.4 (alert code
Labor·Karte-only, shortcut and widget in the editions with the map).

    python3 tools/check_release_apk.py <offline|public|labor|laborKarte> <apk> \
        [--version 0.9.0 --code 41] [--build-tools $ANDROID_SDK_ROOT/build-tools/35.0.0] [--cert-sha256 a3d5…]
    python3 tools/check_release_apk.py [options] EDITION=APK [EDITION=APK ...]

Used by .github/workflows/release-build.yml on the two signed published APKs (offline, public) and
runnable locally on any APK (without --cert-sha256 the signer is not checked, e.g. for an unsigned
local build; without --version/--code those are not checked). Prints every result; exit 1 if any
check fails. Needs only Python 3 and the build-tools (apksigner, aapt2, dexdump); no network.

Checks:
  * signer certificate SHA-256 (apksigner verify --print-certs), exactly one signer, if --cert-sha256;
  * aapt2 dump badging: package (ch.lessnoise.app for offline/public, ch.lessnoise.labor for the Labor
    builds), versionName, versionCode;
  * permissions: for the two published editions exactly the set of PRIVACY.md «How to verify»
    (offline: no INTERNET / ACCESS_NETWORK_STATE / location; public: those four too) — so in
    particular **no CAMERA** (the photo of «Laut! Melden» is taken by the phone's camera app), no
    ACCESS_BACKGROUND_LOCATION, no FOREGROUND_SERVICE_LOCATION; for the Labor builds the network and
    location rules of their edition; FOREGROUND_SERVICE_DATA_SYNC (0.9.4.1, the export service) in
    both Labor builds and in neither published edition;
  * manifest: the widget ReportWidget and the app shortcut (android.app.shortcuts) only in the
    editions with the map (public, laborKarte);
  * dex (dexdump): no Labor code in the published editions (ch/lessnoise/app/labor, class names
    matching labor|recorder|wav|aac|muxer|clipassembler, MediaMuxer/MediaCodec/RIFF/WAVE/
    manifest.jsonl strings); no ML Kit / Google Play services / Firebase classes in any edition;
    «Sehr laut» / «Unterwegs» (ch/lessnoise/app/alert/*, the channel id string "loud_alert") only in
    laborKarte — the Online-Version has none since 0.9.0 (0.8.4 in Labor·Karte); map tab (MapScreen*),
    WebView and SensorManager referrers, motion detector, the swisstopo tiles of «Laut! Melden»
    (ReportMapTiles*/ReportTileFetcher*) and the shortcut/widget classes (ch/lessnoise/app/report/*)
    only in public and laborKarte; the report dialog's level meter (ReportLevel*) and photos
    (ReportPhotos*) in every edition that has «Laut! Melden» (public, laborKarte).
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
PUBLISHED = {"offline", "public"}
MAP = {"public", "laborKarte"}
LABOR = {"labor", "laborKarte"}

BASE_PERMISSIONS = {
    "android.permission.RECORD_AUDIO",
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.FOREGROUND_SERVICE_MICROPHONE",
    "android.permission.POST_NOTIFICATIONS",
    "android.permission.WAKE_LOCK",
}
NETWORK_AND_LOCATION = {
    "android.permission.INTERNET",
    "android.permission.ACCESS_NETWORK_STATE",
    "android.permission.ACCESS_FINE_LOCATION",
    "android.permission.ACCESS_COARSE_LOCATION",
}
# PRIVACY.md → «How to verify»: the exact uses-permission set of each published edition
# (plus AndroidX's own declared DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION, used by the app itself).
EXPECTED_PERMISSIONS = {
    "offline": BASE_PERMISSIONS | {"ch.lessnoise.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"},
    "public": BASE_PERMISSIONS | NETWORK_AND_LOCATION | {"ch.lessnoise.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"},
}
NEVER = {
    "android.permission.CAMERA",
    "android.permission.ACCESS_BACKGROUND_LOCATION",
    "android.permission.RECEIVE_BOOT_COMPLETED",
    "android.permission.ACCESS_WIFI_STATE",
}
FGS_LOCATION = "android.permission.FOREGROUND_SERVICE_LOCATION"
# 0.9.4.1: the Labor export runs in a foreground service of type dataSync (labor/LaborExportService.kt).
FGS_DATA_SYNC = "android.permission.FOREGROUND_SERVICE_DATA_SYNC"

LABOR_CLASS_NAME = re.compile(r"labor|recorder|wav|aac|muxer|clipassembler", re.IGNORECASE)
LABOR_STRING = re.compile(rb"Landroid/media/MediaMuxer;|Landroid/media/MediaCodec;|RIFF|WAVE|manifest\.jsonl|ch/lessnoise/app/labor/")
GOOGLE_SDK = re.compile(r"^Lcom/google/(mlkit|android/gms|firebase|android/datatransport)/")

# (title, kind, pattern, editions that must have > 0; every other edition must have 0)
DEX_RULES = [
    ("map tab classes ch/lessnoise/app/ui/MapScreen*", "class", r"^Lch/lessnoise/app/ui/MapScreen", MAP),
    ("ch/lessnoise classes referring to android.webkit.WebView", "ref", "Landroid/webkit/WebView;", MAP),
    ("motion detector classes ch/lessnoise/app/motion/MotionDetector*", "class", r"^Lch/lessnoise/app/motion/MotionDetector", MAP),
    ("ch/lessnoise classes referring to android.hardware.SensorManager", "ref", "Landroid/hardware/SensorManager;", MAP),
    ("swisstopo tile classes ReportMapTiles*/ReportTileFetcher*", "class", r"^Lch/lessnoise/app/ui/Report(MapTiles|TileFetcher)", MAP),
    ("shortcut/widget classes ch/lessnoise/app/report/*", "class", r"^Lch/lessnoise/app/report/", MAP),
    ("«Sehr laut» / «Unterwegs» classes ch/lessnoise/app/alert/*", "class", r"^Lch/lessnoise/app/alert/", {"laborKarte"}),
    ("string \"loud_alert\" (the «Sehr laut» channel)", "string", b"loud_alert", {"laborKarte"}),
    ("Labor classes ch/lessnoise/app/labor/*", "class", r"^Lch/lessnoise/app/labor/", LABOR),
    ("audio encoding (string Landroid/media/MediaMuxer;)", "string", b"Landroid/media/MediaMuxer;", LABOR),
    ("ML Kit / Play services / Firebase classes", "class", GOOGLE_SDK.pattern, set()),
]

failures = []


def check(ok, what):
    print(("  ok    " if ok else "  FAIL  ") + what)
    if not ok:
        failures.append(what)


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


def run(tool, *args):
    r = subprocess.run([str(tool), *map(str, args)], capture_output=True, text=True)
    if r.returncode != 0:
        print(r.stdout + r.stderr)
        check(False, f"{Path(tool).name} {' '.join(map(str, args[:2]))} exit {r.returncode}")
    return r.stdout


def dex_scan(dexdump, apk):
    """Class descriptors, {referenced type: ch/lessnoise classes referring to it}, raw dex bytes."""
    classes, refs, raw = [], {}, b""
    needles = [r[2] for r in DEX_RULES if r[1] == "ref"]
    with tempfile.TemporaryDirectory() as tmp, zipfile.ZipFile(apk) as z:
        dexes = sorted(n for n in z.namelist() if re.fullmatch(r"classes\d*\.dex", n))
        for n in dexes:
            p = Path(tmp) / n
            p.write_bytes(z.read(n))
            raw += p.read_bytes()
            current = None
            proc = subprocess.Popen([str(dexdump), "-d", str(p)], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                                    text=True, errors="replace")
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


def check_apk(bt, edition, apk, version, code, cert):
    print(f"== {edition}: {apk}")
    if cert:
        certs = run(bt / "apksigner", "verify", "--print-certs", apk)
        got = re.findall(r"certificate SHA-256 digest:\s*([0-9a-f]{64})", certs)
        check(got == [cert.lower()], f"signer certificate SHA-256 {got} == [{cert}]")
    else:
        print("  skip  signer (no --cert-sha256)")

    lines = run(bt / "aapt2", "dump", "badging", apk).splitlines()
    m = re.match(r"package: name='([^']*)' versionCode='([^']*)' versionName='([^']*)'", lines[0] if lines else "")
    pkg = m.group(1) if m else "?"
    want_pkg = "ch.lessnoise.app" if edition in PUBLISHED else "ch.lessnoise.labor"
    check(pkg == want_pkg, f"package {pkg} (expected {want_pkg})")
    if code:
        check(m is not None and m.group(2) == code, f"versionCode {m.group(2) if m else '?'} (expected {code})")
    if version:
        want_name = version + {"labor": "-labor", "laborKarte": "-labor-karte"}.get(edition, "")
        check(m is not None and m.group(3) == want_name, f"versionName {m.group(3) if m else '?'} (expected {want_name})")

    uses, declared, other = set(), set(), []
    for line in run(bt / "aapt2", "dump", "permissions", apk).splitlines():
        if line.startswith("package:"):
            continue
        mu = re.match(r"uses-permission: name='([^']+)'$", line)
        md = re.match(r"permission: (\S+)$", line)
        if mu:
            uses.add(mu.group(1))
        elif md:
            declared.add(md.group(1))
        elif line.strip():
            other.append(line)
    if edition in PUBLISHED:
        want = EXPECTED_PERMISSIONS[edition]
        check(uses == want, f"uses-permission = PRIVACY.md set for {edition} (extra {sorted(uses - want)}, missing {sorted(want - uses)})")
        check(declared == {"ch.lessnoise.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"}, f"declared permissions {sorted(declared)}")
        check(not other, f"no other permission lines {other}")
    has_net = edition in MAP
    for p in sorted(NETWORK_AND_LOCATION):
        check((p in uses) == has_net, f"{'has' if has_net else 'lacks'} {p}")
    for p in sorted(NEVER):
        check(p not in uses, f"lacks {p}")
    check((FGS_LOCATION in uses) == (edition == "laborKarte"), f"{FGS_LOCATION} only in laborKarte")
    check((FGS_DATA_SYNC in uses) == (edition in LABOR), f"{FGS_DATA_SYNC} {'present (Labor export service)' if edition in LABOR else 'absent'}")

    xml = run(bt / "aapt2", "dump", "xmltree", "--file", "AndroidManifest.xml", apk)
    widget = "ch.lessnoise.app.report.ReportWidget" in xml
    shortcuts = "android.app.shortcuts" in xml
    check(widget == has_net, f"manifest {'registers' if has_net else 'does not register'} the widget ReportWidget")
    check(shortcuts == has_net, f"manifest {'declares' if has_net else 'does not declare'} the app shortcut (android.app.shortcuts)")

    classes, refs, raw, ndex = dex_scan(bt / "dexdump", apk)
    print(f"  ({len(classes)} classes in {ndex} dex files)")
    check(len(classes) > 1000, "dexdump listed the classes")
    for title, kind, what, present_in in DEX_RULES:
        if kind == "class":
            n = sum(1 for c in classes if re.search(what, c))
        elif kind == "ref":
            n = len(refs.get(what, ()))
        else:
            n = raw.count(what)
        must = edition in present_in
        check((n > 0) == must, f"{title}: {n} ({'expected > 0' if must else 'expected 0'})")
    # «Laut! Melden» exists exactly where the map is: its level meter and photo code with it.
    for title, pat in (("report level meter ReportLevel*", r"^Lch/lessnoise/app/ui/ReportLevel"),
                       ("report photo ReportPhotos*", r"^Lch/lessnoise/app/ui/ReportPhotos")):
        n = sum(1 for c in classes if re.search(pat, c))
        if edition in MAP:
            check(n > 0, f"{title}: {n} (expected > 0)")
        else:
            print(f"  info  {title}: {n} (compiled from src/main; unreachable without uploads)")
    if edition in PUBLISHED:
        names_hit = sorted(c for c in classes if LABOR_CLASS_NAME.search(c))
        check(not names_hit, f"no class name matching labor|recorder|wav|aac|muxer|clipassembler ({names_hit[:5]})")
        str_hit = sorted({s.decode() for s in re.findall(rb"[\x20-\x7e]{4,}", raw) if LABOR_STRING.search(s)})
        check(not str_hit, f"no MediaMuxer/MediaCodec/RIFF/WAVE/manifest.jsonl/labor strings ({str_hit[:5]})")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("targets", nargs="+", metavar="EDITION APK | EDITION=APK")
    ap.add_argument("--version")
    ap.add_argument("--code")
    ap.add_argument("--build-tools")
    ap.add_argument("--cert-sha256")
    a = ap.parse_args()
    if len(a.targets) == 2 and "=" not in a.targets[0] and a.targets[0] in EDITIONS:
        pairs = [(a.targets[0], a.targets[1])]
    else:
        pairs = []
        for t in a.targets:
            ed, sep, path = t.partition("=")
            if not sep or ed not in EDITIONS:
                sys.exit(f"expected EDITION=APK with EDITION one of {', '.join(EDITIONS)}: {t!r}")
            pairs.append((ed, path))
    bt = find_build_tools(a.build_tools)
    for ed, path in pairs:
        before = len(failures)
        check_apk(bt, ed, Path(path), a.version, a.code, a.cert_sha256)
        print(f"== {ed}: " + ("all checks passed" if len(failures) == before else f"{len(failures) - before} check(s) FAILED"))
        print()
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
