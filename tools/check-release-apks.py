#!/usr/bin/env python3
"""Assert the package and the versionCode of EVERY release APK, read from the APKs themselves.

Why this exists
---------------
The build refuses a version base that is too low, but that checks `version.properties`, not what was
produced. The per-ABI code is applied to each output by the AGP variant API: if it did not reach one
output — the universal APK, which has no ABI filter, is the obvious candidate — that APK would ship
with the bare base (3000), below every Flutter APK installed, and Android would refuse the update
(INSTALL_FAILED_VERSION_DOWNGRADE) on every phone that took it. Same for the package: an APK built
without `-Prft.replaceFlutter=true` for a release would install NEXT TO the Flutter app, with an empty
vault. External review of the port plan, 2026-10-10, finding 2.

What is checked, on each of the four APKs (`aapt2 dump badging`)
---------------------------------------------------------------
- the package: `.next` by default, the Flutter one with --replace;
- versionCode == base * 10 + rank (universal 0, armeabi-v7a 1, arm64-v8a 2, x86_64 3);
- versionCode > 25503, the highest code the Flutter line published (2.15.3, x86_64 — docs D-016);
- exactly these four APKs: one missing or one extra fails.

Usage
-----
    python3 tools/check-release-apks.py [--replace] [apk-directory]
    python3 tools/check-release-apks.py [--replace] --from-badging DIR   (negative control only)

`--from-badging` reads `<apk-name>.badging.txt` files instead of running aapt2, so the negative
control can feed it broken outputs without building broken APKs.
"""

from __future__ import annotations

import glob
import os
import re
import subprocess
import sys

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
DEFAULT_APK_DIR = os.path.join(ROOT, "app", "build", "outputs", "apk", "release")
VERSION_PROPERTIES = os.path.join(ROOT, "version.properties")

FLUTTER_PACKAGE = "com.readfilestech.read_files_tech"
ISOLATED_PACKAGE = FLUTTER_PACKAGE + ".next"
HIGHEST_FLUTTER_CODE_PUBLISHED = 25503

RANKS = {
    "app-universal-release.apk": 0,
    "app-armeabi-v7a-release.apk": 1,
    "app-arm64-v8a-release.apk": 2,
    "app-x86_64-release.apk": 3,
}

IN_CI = os.environ.get("GITHUB_ACTIONS") == "true"


def fail(lines: list[str]) -> None:
    prefix = "::error::" if IN_CI else "ERROR: "
    for line in lines:
        print(f"{prefix}{line}")


def version_base() -> int:
    with open(VERSION_PROPERTIES, encoding="utf-8") as f:
        for line in f:
            match = re.match(r"\s*versionCode\s*=\s*(\d+)\s*$", line)
            if match:
                return int(match.group(1))
    raise ValueError(f"no versionCode in {VERSION_PROPERTIES}")


def aapt2() -> str:
    home = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not home:
        raise FileNotFoundError("ANDROID_HOME is not set")
    candidates = sorted(glob.glob(os.path.join(home, "build-tools", "*", "aapt2*")))
    candidates = [c for c in candidates if os.path.basename(c) in ("aapt2", "aapt2.exe")]
    if not candidates:
        raise FileNotFoundError(f"no aapt2 under {home}/build-tools")
    return candidates[-1]


def badging_of(apk: str) -> str:
    result = subprocess.run([aapt2(), "dump", "badging", apk], capture_output=True, text=True, encoding="utf-8")
    if result.returncode != 0:
        raise RuntimeError(f"aapt2 failed on {apk}: {result.stderr.strip()}")
    return result.stdout


def main(argv: list[str]) -> int:
    args = argv[1:]
    replace = "--replace" in args
    args = [a for a in args if a != "--replace"]
    from_badging = None
    if args[:1] == ["--from-badging"] and len(args) == 2:
        from_badging = args[1]
    elif len(args) > 1 or any(a.startswith("--") for a in args):
        print(__doc__)
        return 2
    source = from_badging or (args[0] if args else DEFAULT_APK_DIR)
    expected_package = FLUTTER_PACKAGE if replace else ISOLATED_PACKAGE

    if not os.path.isdir(source):
        fail([f"directory not found: {source}", "run first: ./gradlew :app:assembleRelease"])
        return 1
    try:
        base = version_base()
    except (OSError, ValueError) as error:
        fail([str(error)])
        return 1

    suffix = ".badging.txt" if from_badging else ""
    present = {os.path.basename(p)[: len(os.path.basename(p)) - len(suffix)]
               for p in glob.glob(os.path.join(source, "*.apk" + suffix))}
    problems: list[str] = []
    for name in sorted(set(RANKS) - present):
        problems.append(f"release APK missing: {name}")
    for name in sorted(present - set(RANKS)):
        problems.append(f"unexpected release APK: {name} — a new output needs a rank and a check.")

    for name in sorted(set(RANKS) & present):
        path = os.path.join(source, name + suffix)
        try:
            if from_badging:
                with open(path, encoding="utf-8") as f:
                    text = f.read()
            else:
                text = badging_of(path)
        except (OSError, RuntimeError, FileNotFoundError) as error:
            problems.append(str(error))
            continue
        match = re.search(r"package: name='([^']+)' versionCode='(\d+)'", text)
        if not match:
            problems.append(f"{name}: no package line in the badging output — not an APK aapt2 could read.")
            continue
        package, code = match.group(1), int(match.group(2))
        expected_code = base * 10 + RANKS[name]
        print(f"  {name:30s} package={package} versionCode={code}")
        if package != expected_package:
            problems.append(f"{name}: package {package}, expected {expected_package}.")
        if code != expected_code:
            problems.append(f"{name}: versionCode {code}, expected {expected_code} (base {base} * 10 + {RANKS[name]}).")
        if code <= HIGHEST_FLUTTER_CODE_PUBLISHED:
            problems.append(
                f"{name}: versionCode {code} is not above {HIGHEST_FLUTTER_CODE_PUBLISHED} (Flutter 2.15.3): "
                "the update would be refused on the phones that installed that APK.",
            )

    if problems:
        print()
        fail(problems)
        return 1
    print()
    print(f"OK: four release APKs, package {expected_package}, codes {base * 10}..{base * 10 + 3}, all above "
          f"{HIGHEST_FLUTTER_CODE_PUBLISHED}.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
