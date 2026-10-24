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
- exactly these four APKs: one missing or one extra fails;
- the languages: exactly the translated ones (values-XX/strings.xml, the rule app/build.gradle.kts
  derives localeFilters from). One missing was stripped from the APK, its users read English; one
  extra is a library's translation that localeFilters no longer filters (docs D-033);
- the languages Android 13's picker offers (the locale config AGP generated, `aapt2 dump xmltree`):
  the same ones — AGP takes them from every resource folder, not from the strings.xml alone.

Usage
-----
    python3 tools/check-release-apks.py [--replace] [apk-directory]
    python3 tools/check-release-apks.py [--replace] --from-badging DIR   (negative control only)

`--from-badging` reads `<apk-name>.badging.txt` (and `<apk-name>.localeconfig.txt`) files instead of
running aapt2, so the negative control can feed it broken outputs without building broken APKs.
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
RES = os.path.join(ROOT, "app", "src", "main", "res")
# The rule of app/build.gradle.kts: English is values/, and every values-XX/ with a strings.xml ships.
# `car` is the car UI mode, not a language (aapt2 reads it so).
LANGUAGE_DIR = re.compile(r"values-((?!car$)[a-z]{2,3})")
# aapt2 names the unqualified resources `--_--`; English is them.
DEFAULT_LOCALE = "--_--"
# Next to `<apk>.badging.txt` with --from-badging: the xmltree of its generated locale config.
LOCALE_CONFIG_SUFFIX = ".localeconfig.txt"

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


def translated_languages() -> set[str]:
    """The languages of this app besides English, as app/build.gradle.kts reads them."""
    return {
        match.group(1)
        for directory in os.listdir(RES)
        if (match := LANGUAGE_DIR.fullmatch(directory)) and os.path.isfile(os.path.join(RES, directory, "strings.xml"))
    }


def language_problems(name: str, text: str, expected: set[str]) -> list[str]:
    line = re.search(r"^locales:(.*)$", text, re.M)
    if not line:
        return [f"{name}: no locales line in the badging output."]
    found = set(re.findall(r"'([^']*)'", line.group(1)))
    problems = []
    if DEFAULT_LOCALE not in found:
        problems.append(f"{name}: no unqualified resources ({DEFAULT_LOCALE}) — English, the source, is missing.")
    stripped = expected - found
    if stripped:
        problems.append(
            f"{name}: translated but not in the APK: {', '.join(sorted(stripped))} — stripped by localeFilters, "
            "its users would read English.",
        )
    unfiltered = found - expected - {DEFAULT_LOCALE, "en"}
    if unfiltered:
        problems.append(
            f"{name}: languages no translation of this app ships: {', '.join(sorted(unfiltered))} — the libraries' "
            "own, which localeFilters no longer filters.",
        )
    return problems


def locale_config_of(apk: str) -> str:
    """The xmltree of every variant of the locale config AGP wrote (generateLocaleConfig). Its path is read in
    the resource table: the release build shortens resource paths (res/xml/… becomes res/Ed.xml)."""
    table = subprocess.run([aapt2(), "dump", "resources", apk], capture_output=True, text=True, encoding="utf-8")
    lines = table.stdout.splitlines()
    paths: list[str] = []
    for i, line in enumerate(lines):
        if line.strip().endswith("xml/_generated_res_locale_config"):
            for variant in lines[i + 1:]:
                found = re.search(r"\(file\) (\S+)", variant)
                if not found:
                    break
                paths.append(found.group(1))
    dumps = []
    for path in paths:
        result = subprocess.run([aapt2(), "dump", "xmltree", "--file", path, apk], capture_output=True, text=True, encoding="utf-8")
        dumps.append(result.stdout)
    return "".join(dumps)


def locale_config_problems(name: str, dump: str, translated: set[str]) -> list[str]:
    """The languages Android 13's picker offers, in every variant of the config: exactly English and the
    translated ones. AGP takes them from EVERY resource folder of the app and of its local modules, where
    localeFilters takes the values-XX/strings.xml: a values-ja/ brought by a module would be offered in
    the picker, and stripped from the APK — the app would stay in English."""
    variants = dump.split("E: locale-config")[1:]
    if not variants:
        return [f"{name}: no generated locale config (generateLocaleConfig): no per-app language picker on Android 13+."]
    expected = translated | {"en"}
    problems = []
    for variant in variants:
        offered = set(re.findall(r'android:name\(0x[0-9a-f]+\)="([^"]+)"', variant))
        if offered - expected:
            problems.append(
                f"{name}: the language picker offers {', '.join(sorted(offered - expected))}, which no translation "
                "of this app ships — choosing it would leave the app in English.",
            )
        if expected - offered:
            problems.append(f"{name}: the language picker does not offer {', '.join(sorted(expected - offered))}.")
    return problems


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
        languages = translated_languages()
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
                config_path = os.path.join(source, name + LOCALE_CONFIG_SUFFIX)
                config = open(config_path, encoding="utf-8").read() if os.path.isfile(config_path) else ""
            else:
                text = badging_of(path)
                config = locale_config_of(path)
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
        problems += language_problems(name, text, languages)
        problems += locale_config_problems(name, config, languages)
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
          f"{HIGHEST_FLUTTER_CODE_PUBLISHED}, languages en + {', '.join(sorted(languages))}.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
