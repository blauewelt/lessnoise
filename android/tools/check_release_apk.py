#!/usr/bin/env python3
"""Release checks of one published APK (android/README.md → «Release checklist», PRIVACY.md → «How to verify»).

    python3 tools/check_release_apk.py <offline|public> <apk> --version 0.8.0 --code 32 \
        --build-tools $ANDROID_SDK_ROOT/build-tools/35.0.0 [--cert-sha256 a3d5…]

Used by .github/workflows/release-build.yml on the signed APKs and runnable locally on any
release APK (without --cert-sha256 the signer is not checked, e.g. for an unsigned local build).
Exits 1 on the first failed check group and prints every result. Needs only Python 3 and the
build-tools (apksigner, aapt2, dexdump); no network.

Checks:
  * signer certificate SHA-256 (apksigner verify --print-certs), if --cert-sha256 is given;
  * aapt2 dump badging: package ch.lessnoise.app, versionName, versionCode;
  * aapt2 dump permissions: exactly the set of PRIVACY.md «How to verify» for the edition
    (offline: no INTERNET / ACCESS_NETWORK_STATE / location; public: those four too; neither
    ACCESS_BACKGROUND_LOCATION);
  * dex (dexdump): no Labor classes (ch/lessnoise/app/labor, recorder/wav/aac/muxer/clip-assembler
    class names, MediaMuxer/MediaCodec/RIFF/WAVE/manifest.jsonl strings) in either edition;
    offline: no MapScreen*, no ReportMapTiles*, no alert/*, no «loud_alert», no MotionDetector*, and
    no ch/lessnoise class that refers to android.webkit.WebView or android.hardware.SensorManager;
    public: all of these present.
"""

import argparse
import os
import re
import subprocess
import sys
import tempfile
import zipfile

BASE_PERMISSIONS = {
    "android.permission.RECORD_AUDIO",
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.FOREGROUND_SERVICE_MICROPHONE",
    "android.permission.POST_NOTIFICATIONS",
    "android.permission.WAKE_LOCK",
    "ch.lessnoise.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
}
NETWORK_AND_LOCATION = {
    "android.permission.INTERNET",
    "android.permission.ACCESS_NETWORK_STATE",
    "android.permission.ACCESS_FINE_LOCATION",
    "android.permission.ACCESS_COARSE_LOCATION",
}
EXPECTED_PERMISSIONS = {
    "offline": BASE_PERMISSIONS,
    "public": BASE_PERMISSIONS | NETWORK_AND_LOCATION,
}
DECLARED_PERMISSIONS = {"ch.lessnoise.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"}

LABOR_CLASS_NAME = re.compile(r"labor|recorder|wav|aac|muxer|clipassembler", re.IGNORECASE)
LABOR_STRING = re.compile(rb"Landroid/media/MediaMuxer;|Landroid/media/MediaCodec;|RIFF|WAVE|manifest\.jsonl|ch/lessnoise/app/labor/")

failures = []


def check(ok, what):
    print(("ok    " if ok else "FAIL  ") + what)
    if not ok:
        failures.append(what)


def run(tool, *args):
    r = subprocess.run([tool, *args], capture_output=True, text=True)
    if r.returncode != 0:
        print(r.stdout + r.stderr)
        check(False, f"{os.path.basename(tool)} {' '.join(args[:2])} exit {r.returncode}")
    return r.stdout


def dex_classes(dexdump, dex_files):
    """{class descriptor: set of type descriptors referenced in its code}, from dexdump -d."""
    classes = {}
    current = None
    for d in dex_files:
        out = subprocess.run([dexdump, "-d", d], capture_output=True, text=True, errors="replace").stdout
        for line in out.splitlines():
            m = re.match(r"\s*Class descriptor\s*:\s*'([^']+)'", line)
            if m:
                current = m.group(1)
                classes.setdefault(current, set())
                continue
            if current is not None:
                for t in re.findall(r"L[A-Za-z0-9_$/]+;", line):
                    classes[current].add(t)
    return classes


def dex_strings(dex_files):
    """Printable runs of ≥ 4 bytes, like `strings`."""
    found = []
    for d in dex_files:
        with open(d, "rb") as f:
            found += re.findall(rb"[\x20-\x7e]{4,}", f.read())
    return found


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("edition", choices=["offline", "public"])
    ap.add_argument("apk")
    ap.add_argument("--version", required=True)
    ap.add_argument("--code", required=True)
    ap.add_argument("--build-tools", required=True)
    ap.add_argument("--cert-sha256")
    a = ap.parse_args()
    bt = a.build_tools
    apksigner, aapt2, dexdump = (os.path.join(bt, t) for t in ("apksigner", "aapt2", "dexdump"))
    print(f"== {a.edition}: {a.apk}")

    # Signer
    if a.cert_sha256:
        certs = run(apksigner, "verify", "--print-certs", a.apk)
        got = re.findall(r"certificate SHA-256 digest:\s*([0-9a-f]{64})", certs)
        check(got == [a.cert_sha256.lower()], f"signer certificate SHA-256 {got} == [{a.cert_sha256}]")
    else:
        print("skip  signer (no --cert-sha256)")

    # Badging
    badging = run(aapt2, "dump", "badging", a.apk).splitlines()[0]
    m = re.match(r"package: name='([^']*)' versionCode='([^']*)' versionName='([^']*)'", badging)
    check(m is not None and m.group(1) == "ch.lessnoise.app", f"package ch.lessnoise.app ({badging[:80]})")
    check(m is not None and m.group(2) == a.code, f"versionCode {a.code}")
    check(m is not None and m.group(3) == a.version, f"versionName {a.version}")

    # Permissions: exactly the PRIVACY.md set, nothing unknown
    uses, declared, other = set(), set(), []
    for line in run(aapt2, "dump", "permissions", a.apk).splitlines():
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
    want = EXPECTED_PERMISSIONS[a.edition]
    check(uses == want, f"uses-permission = PRIVACY.md set for {a.edition} (extra {sorted(uses - want)}, missing {sorted(want - uses)})")
    check(declared == DECLARED_PERMISSIONS, f"declared permissions {sorted(declared)}")
    check(not other, f"no other permission lines {other}")
    check("android.permission.ACCESS_BACKGROUND_LOCATION" not in uses, "no ACCESS_BACKGROUND_LOCATION")

    # Dex
    with tempfile.TemporaryDirectory() as tmp:
        with zipfile.ZipFile(a.apk) as z:
            names = [n for n in z.namelist() if re.fullmatch(r"classes\d*\.dex", n)]
            for n in names:
                z.extract(n, tmp)
        dex = [os.path.join(tmp, n) for n in names]
        check(len(dex) > 0, f"dex files {names}")
        classes = dex_classes(dexdump, dex)
        strings = dex_strings(dex)
    print(f"      {len(classes)} classes in {len(dex)} dex files")
    check(len(classes) > 1000, "dexdump listed the classes")

    def count(prefix):
        return sum(1 for c in classes if c.startswith(prefix))

    def referrers(type_desc):
        return sorted(c for c, refs in classes.items() if c.startswith("Lch/lessnoise/") and type_desc in refs)

    labor = count("Lch/lessnoise/app/labor/")
    check(labor == 0, f"0 classes ch/lessnoise/app/labor ({labor})")
    names_hit = sorted(c for c in classes if LABOR_CLASS_NAME.search(c))
    check(not names_hit, f"no class name matching labor|recorder|wav|aac|muxer|clipassembler ({names_hit[:5]})")
    str_hit = sorted({s.decode() for s in strings if LABOR_STRING.search(s)})
    check(not str_hit, f"no MediaMuxer/MediaCodec/RIFF/WAVE/manifest.jsonl/labor strings ({str_hit[:5]})")

    has_loud_alert = any(b"loud_alert" in s for s in strings)
    found = {
        "MapScreen* classes": count("Lch/lessnoise/app/ui/MapScreen"),
        "ReportMapTiles* classes": count("Lch/lessnoise/app/ui/ReportMapTiles") + count("Lch/lessnoise/app/ui/ReportTileFetcher"),
        "alert/* classes": count("Lch/lessnoise/app/alert/"),
        "MotionDetector* classes": count("Lch/lessnoise/app/motion/MotionDetector"),
        "ch/lessnoise classes referring to WebView": len(referrers("Landroid/webkit/WebView;")),
        "ch/lessnoise classes referring to SensorManager": len(referrers("Landroid/hardware/SensorManager;")),
        "string loud_alert": int(has_loud_alert),
    }
    for what, n in found.items():
        if a.edition == "offline":
            check(n == 0, f"offline: 0 {what} ({n})")
        else:
            check(n > 0, f"public: {what} present ({n})")

    if failures:
        print(f"== {a.edition}: {len(failures)} check(s) FAILED")
        sys.exit(1)
    print(f"== {a.edition}: all checks passed")


if __name__ == "__main__":
    main()
