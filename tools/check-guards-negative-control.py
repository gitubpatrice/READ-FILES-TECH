#!/usr/bin/env python3
"""NEGATIVE control of the CI guards — the proof that each can go red, and for the right reason.

Why this exists
---------------
A guard that returned 0 whatever happened would be worse than none: it would hand out a false
guarantee on every run. So every failure each guard claims to catch is REPRODUCED here, in a
throwaway directory, and two things are verified:
  - the guard returns 1, not 0;
  - its output names the RIGHT cause. Failing for another reason is a false green in disguise.

A POSITIVE WITNESS opens each series: a healthy input must return 0. Without it, a guard broken into
returning 1 on everything would pass the whole series.

Guards covered:
  - tools/check-manifest-permissions.py  (the "no internet permission" promise)
  - tools/check-manifest-shape.py        (backup off, nothing debuggable, the exported components)
  - tools/check-release-apks.py          (package and versionCode of every release APK)
  - tools/check-test-results.py          (a test run that executed zero tests)

Against REAL artefacts, which proves each guard reads what the build produces, not only a fixture:
  --real-manifest PATH         the manifest of a release APK (apkanalyzer): the permission guard
                               passes on it, and fails once INTERNET is injected into a copy; the shape
                               guard (--apk, where resource references are ids) passes on it, and fails
                               once an exported activity, allowBackup or a missing rule is injected.
  --real-merged-manifest PATH  the merged release manifest: the same, the shape guard as it is.

Adapted from App Manager Tech. No dependencies, never touches the repository.

    python3 tools/check-guards-negative-control.py [--real-manifest PATH | --real-merged-manifest PATH]
"""

from __future__ import annotations

import os
import re
import shutil
import subprocess
import sys
import tempfile

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

HERE = os.path.dirname(os.path.abspath(__file__))
MANIFEST_CHECK = os.path.join(HERE, "check-manifest-permissions.py")
SHAPE_CHECK = os.path.join(HERE, "check-manifest-shape.py")
APKS_CHECK = os.path.join(HERE, "check-release-apks.py")
TESTS_CHECK = os.path.join(HERE, "check-test-results.py")
VERSION_PROPERTIES = os.path.join(os.path.dirname(HERE), "version.properties")

# The guards print `::error::` annotations and write the job summary under GitHub Actions. The
# failures provoked here are EXPECTED: left in that environment, they would paint red annotations on a
# green run, and the positive witness would write a fake test count into the summary.
GUARD_ENV = {k: v for k, v in os.environ.items() if k not in ("GITHUB_ACTIONS", "GITHUB_STEP_SUMMARY")}

ISOLATED = "com.readfilestech.read_files_tech.next"
FLUTTER = "com.readfilestech.read_files_tech"
MAIN_ACTIVITY = "com.readfilestech.read_files_tech.MainActivity"

# --- Permission fixtures -------------------------------------------------------------------------


STORAGE_PERMISSIONS = (
    '    <uses-permission\n'
    '        android:name="android.permission.READ_EXTERNAL_STORAGE"\n'
    '        android:maxSdkVersion="32" />\n'
    '    <uses-permission\n'
    '        android:name="android.permission.WRITE_EXTERNAL_STORAGE"\n'
    '        android:maxSdkVersion="29" />\n'
    '    <uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE" />\n'
)


def manifest(package: str) -> str:
    # Permissions written across several lines, the shape the manifest merger produces: the positive
    # witness proves they are still READ.
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<manifest xmlns:android="http://schemas.android.com/apk/res/android"\n'
        f'    package="{package}">\n'
        '    <uses-permission\n'
        f'        android:name="{package}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION" />\n'
        f'{STORAGE_PERMISSIONS}'
        '    <application android:label="Read Files Tech" />\n'
        '</manifest>\n'
    )


MANIFEST_OK = manifest(ISOLATED)


def permission(name: str) -> str:
    return f'    <uses-permission android:name="{name}" />\n'


INTERNET_MULTI_LINE = (
    '    <uses-permission\n'
    '        android:name="android.permission.INTERNET"\n'
    '        android:maxSdkVersion="99" />\n'
)
INTERNET_SDK_23 = '    <uses-permission-sdk-23 android:name="android.permission.INTERNET" />\n'
INTERNET_SDK_M = '    <uses-permission-sdk-m android:name="android.permission.INTERNET" />\n'


def with_before_application(content: str, extra: str) -> str:
    return content.replace("    <application", extra + "    <application", 1)


# --- Shape fixtures ------------------------------------------------------------------------------

TILE = "com.readfilestech.read_files_tech.{}TileService"
TILE_PERMISSION = "android.permission.BIND_QUICK_SETTINGS_TILE"


def tile(name: str) -> str:
    return (
        f'        <service android:name="{TILE.format(name)}" android:exported="true"\n'
        f'            android:permission="{TILE_PERMISSION}">\n'
        '            <intent-filter><action android:name="android.service.quicksettings.action.QS_TILE" /></intent-filter>\n'
        '        </service>\n'
    )


QUERIES = (
    '    <queries>\n'
    '        <package android:name="com.pdftech.pdf_tech" />\n'
    '        <package android:name="com.infomaniak.drive" />\n'
    '        <package android:name="me.proton.android.drive" />\n'
    '    </queries>\n'
)

SHAPE_OK = (
    '<?xml version="1.0" encoding="utf-8"?>\n'
    '<manifest xmlns:android="http://schemas.android.com/apk/res/android"\n'
    f'    package="{ISOLATED}">\n'
    f'{QUERIES}'
    '    <application\n'
    '        android:allowBackup="false"\n'
    '        android:fullBackupContent="false"\n'
    '        android:dataExtractionRules="@xml/data_extraction_rules"\n'
    '        android:usesCleartextTraffic="false"\n'
    '        android:requestLegacyExternalStorage="true">\n'
    f'        <activity android:name="{MAIN_ACTIVITY}" android:exported="true" android:taskAffinity="">\n'
    '            <intent-filter><action android:name="android.intent.action.MAIN" /></intent-filter>\n'
    '        </activity>\n'
    f'{tile("Scanner")}{tile("Ocr")}{tile("Vault")}'
    '        <receiver android:name="androidx.profileinstaller.ProfileInstallReceiver"\n'
    '            android:exported="true" android:permission="android.permission.DUMP">\n'
    '            <intent-filter><action android:name="androidx.profileinstaller.action.INSTALL_PROFILE" /></intent-filter>\n'
    '        </receiver>\n'
    '        <provider android:name="androidx.startup.InitializationProvider" android:exported="false" />\n'
    f'        <provider android:name="androidx.core.content.FileProvider" android:authorities="{ISOLATED}.fileprovider"\n'
    '            android:exported="false" android:grantUriPermissions="true" />\n'
    '    </application>\n'
    '</manifest>\n'
)


def in_application(content: str, extra: str) -> str:
    return content.replace("    </application>", extra + "    </application>", 1)


EXTRA_EXPORTED_ACTIVITY = '        <activity android:name="com.example.TestActivity" android:exported="true" />\n'
IMPLICITLY_EXPORTED = (
    '        <activity android:name="com.example.Implicit">\n'
    '            <intent-filter><action android:name="android.intent.action.VIEW" /></intent-filter>\n'
    '        </activity>\n'
)
EXPORTED_PROVIDER = '        <provider android:name="com.example.Provider" android:exported="true" />\n'

# --- Release APK fixtures (aapt2 badging output) -------------------------------------------------

RANKS = {"universal": 0, "armeabi-v7a": 1, "arm64-v8a": 2, "x86_64": 3}


def version_base() -> int:
    with open(VERSION_PROPERTIES, encoding="utf-8") as f:
        for line in f:
            match = re.match(r"\s*versionCode\s*=\s*(\d+)\s*$", line)
            if match:
                return int(match.group(1))
    raise ValueError(f"no versionCode in {VERSION_PROPERTIES}")


def badging(package: str, code: int) -> str:
    return f"package: name='{package}' versionCode='{code}' versionName='3.0.0' platformBuildVersionName='16'\n"


def badgings(package: str = ISOLATED, codes: dict[str, int] | None = None) -> dict[str, str]:
    base = version_base()
    codes = {abi: base * 10 + rank for abi, rank in RANKS.items()} | (codes or {})
    return {f"app-{abi}-release.apk.badging.txt": badging(package, code) for abi, code in codes.items()}


# --- Test report fixtures ------------------------------------------------------------------------

def report(name: str, tests: int, skipped: int = 0) -> str:
    cases = "".join(f'  <testcase name="t{i}" classname="{name}" time="0.001"/>\n' for i in range(tests))
    return (
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        f'<testsuite name="{name}" tests="{tests}" skipped="{skipped}" failures="0" errors="0">\n'
        f"{cases}</testsuite>\n"
    )


def report_skips_marked_in_cases_only(name: str, tests: int) -> str:
    """Every test skipped, marked by a <skipped/> element while the suite attribute says 0 — the shape
    an instrumented report may take for failed assumptions."""
    cases = "".join(
        f'  <testcase name="t{i}" classname="{name}" time="0.001"><skipped/></testcase>\n'
        for i in range(tests)
    )
    return (
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        f'<testsuite name="{name}" tests="{tests}" skipped="0" failures="0" errors="0">\n'
        f"{cases}</testsuite>\n"
    )


REPORTS_OK = {
    "TEST-com.example.ATest.xml": report("com.example.ATest", 3),
    "TEST-com.example.BTest.xml": report("com.example.BTest", 2, skipped=1),
}


# --- Harness -------------------------------------------------------------------------------------

def write(path: str, content: str) -> None:
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(content)


def run(script: str, target: str, extra: list[str]) -> tuple[int, str]:
    r = subprocess.run(
        [sys.executable, script, *extra, target],
        capture_output=True, text=True, encoding="utf-8", errors="replace", env=GUARD_ENV,
    )
    return r.returncode, (r.stdout or "") + (r.stderr or "")


def manifest_case(work: str, content: str | None) -> str:
    """Writes the manifest (None = no file at all) and returns its path."""
    path = os.path.join(work, "manifest", "AndroidManifest.xml")
    shutil.rmtree(os.path.dirname(path), ignore_errors=True)
    if content is not None:
        write(path, content)
    return path


def files_case(work: str, name: str, files: dict[str, str] | None) -> str:
    """Writes the files into a fresh directory (None = no directory at all) and returns it."""
    path = os.path.join(work, name)
    shutil.rmtree(path, ignore_errors=True)
    if files is not None:
        # Gradle always writes a `binary/` directory next to the test reports: an empty run keeps it.
        os.makedirs(os.path.join(path, "binary"))
        for file_name, content in files.items():
            write(os.path.join(path, file_name), content)
    return path


def without(files: dict[str, str], name: str) -> dict[str, str]:
    return {k: v for k, v in files.items() if k != name}


def permission_cases(work: str) -> list[tuple]:
    network = "NETWORK permission in the release manifest"
    wrong_package = "is not the release manifest of"
    return [
        ("permissions: POSITIVE WITNESS (multi-line element)", MANIFEST_CHECK, [],
         lambda: manifest_case(work, MANIFEST_OK), 0, "OK: no network permission"),
        ("permissions: POSITIVE WITNESS, replacing build (--replace)", MANIFEST_CHECK, ["--replace"],
         lambda: manifest_case(work, manifest(FLUTTER)), 0, "OK: no network permission"),
        ("permissions: INTERNET on one line", MANIFEST_CHECK, [],
         lambda: manifest_case(work, with_before_application(MANIFEST_OK, permission("android.permission.INTERNET"))),
         1, network),
        ("permissions: INTERNET across several lines", MANIFEST_CHECK, [],
         lambda: manifest_case(work, with_before_application(MANIFEST_OK, INTERNET_MULTI_LINE)), 1, network),
        ("permissions: INTERNET via uses-permission-sdk-23", MANIFEST_CHECK, [],
         lambda: manifest_case(work, with_before_application(MANIFEST_OK, INTERNET_SDK_23)), 1, network),
        ("permissions: INTERNET via uses-permission-sdk-m", MANIFEST_CHECK, [],
         lambda: manifest_case(work, with_before_application(MANIFEST_OK, INTERNET_SDK_M)), 1, network),
        ("permissions: ACCESS_NETWORK_STATE (androidx.work's)", MANIFEST_CHECK, [],
         lambda: manifest_case(work, with_before_application(
             MANIFEST_OK, permission("android.permission.ACCESS_NETWORK_STATE"))), 1, network),
        ("permissions: ACCESS_WIFI_STATE", MANIFEST_CHECK, [],
         lambda: manifest_case(work, with_before_application(
             MANIFEST_OK, permission("android.permission.ACCESS_WIFI_STATE"))), 1, network),
        ("permissions: replacing build read as the isolated one", MANIFEST_CHECK, [],
         lambda: manifest_case(work, manifest(FLUTTER)), 1, wrong_package + " " + ISOLATED),
        ("permissions: isolated build read with --replace", MANIFEST_CHECK, ["--replace"],
         lambda: manifest_case(work, MANIFEST_OK), 1, wrong_package + " " + FLUTTER),
        ("permissions: no package attribute", MANIFEST_CHECK, [],
         lambda: manifest_case(work, MANIFEST_OK.replace(f'\n    package="{ISOLATED}"', "")), 1, "(package=None)"),
        ("permissions: permission outside the reviewed list", MANIFEST_CHECK, [],
         lambda: manifest_case(work, with_before_application(
             MANIFEST_OK, permission("android.permission.READ_CONTACTS"))), 1, "outside the reviewed list"),
        ("permissions: reviewed permission absent", MANIFEST_CHECK, [],
         lambda: manifest_case(work, MANIFEST_OK.replace(
             "DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION", "OTHER_PERMISSION")), 1, "absent from the manifest"),
        ("permissions: all-files access absent (the explorer would see nothing on Android 11+)", MANIFEST_CHECK, [],
         lambda: manifest_case(work, MANIFEST_OK.replace(
             '    <uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE" />\n', "")),
         1, "absent from the manifest"),
        ("permissions: READ_MEDIA_IMAGES back (docs D-027)", MANIFEST_CHECK, [],
         lambda: manifest_case(work, with_before_application(
             MANIFEST_OK, permission("android.permission.READ_MEDIA_IMAGES"))), 1, "outside the reviewed list"),
        ("permissions: CAMERA back (docs D-027)", MANIFEST_CHECK, [],
         lambda: manifest_case(work, with_before_application(
             MANIFEST_OK, permission("android.permission.CAMERA"))), 1, "outside the reviewed list"),
        ("permissions: no permission at all", MANIFEST_CHECK, [],
         lambda: manifest_case(work, MANIFEST_OK.split("    <uses-permission")[0]
                               + '    <application android:label="x" />\n</manifest>\n'),
         1, "no permission found"),
        ("permissions: not an application manifest", MANIFEST_CHECK, [],
         lambda: manifest_case(work, MANIFEST_OK.replace('    <application android:label="Read Files Tech" />\n', "")),
         1, "is not an application manifest"),
        ("permissions: file missing", MANIFEST_CHECK, [],
         lambda: manifest_case(work, None), 1, "merged manifest not found"),
        ("permissions: malformed XML", MANIFEST_CHECK, [],
         lambda: manifest_case(work, MANIFEST_OK.replace("</manifest>", "")), 1, "merged manifest unreadable"),
    ]


def shape_cases(work: str) -> list[tuple]:
    def case(label: str, content: str | None, code: int, text: str, extra: list[str] | None = None) -> tuple:
        return (f"shape: {label}", SHAPE_CHECK, extra or [], lambda: manifest_case(work, content), code, text)

    return [
        case("POSITIVE WITNESS", SHAPE_OK, 0, "OK: backup off"),
        case("allowBackup=\"true\"", SHAPE_OK.replace('allowBackup="false"', 'allowBackup="true"'),
             1, "android:allowBackup> is 'true'"),
        case("allowBackup absent (its default is true)", SHAPE_OK.replace('        android:allowBackup="false"\n', ""),
             1, "android:allowBackup> is None"),
        case("fullBackupContent absent", SHAPE_OK.replace('        android:fullBackupContent="false"\n', ""),
             1, "android:fullBackupContent> is None"),
        case("dataExtractionRules absent",
             SHAPE_OK.replace('        android:dataExtractionRules="@xml/data_extraction_rules"\n', ""),
             1, "android:dataExtractionRules> is None"),
        case("usesCleartextTraffic=\"true\"", SHAPE_OK.replace('usesCleartextTraffic="false"', 'usesCleartextTraffic="true"'),
             1, "android:usesCleartextTraffic> is 'true'"),
        case("debuggable=\"true\"", SHAPE_OK.replace("<application\n", '<application\n        android:debuggable="true"\n'),
             1, "android:debuggable='true'"),
        case("debuggable by resource reference", SHAPE_OK.replace("<application\n", '<application\n        android:debuggable="@bool/dbg"\n'),
             1, "android:debuggable='@bool/dbg'"),
        case("an activity exported by resource reference",
             in_application(SHAPE_OK, '        <activity android:name="com.example.ByRef" android:exported="@bool/x" />\n'),
             1, "outside the reviewed list: com.example.ByRef"),
        case("MainActivity without taskAffinity", SHAPE_OK.replace(' android:taskAffinity=""', ""),
             1, "android:taskAffinity is None"),
        case("an extra exported activity", in_application(SHAPE_OK, EXTRA_EXPORTED_ACTIVITY),
             1, "outside the reviewed list: com.example.TestActivity"),
        case("an activity exported by its intent filter alone", in_application(SHAPE_OK, IMPLICITLY_EXPORTED),
             1, "outside the reviewed list: com.example.Implicit"),
        case("an exported provider", in_application(SHAPE_OK, EXPORTED_PROVIDER),
             1, "outside the reviewed list: com.example.Provider"),
        case("ProfileInstallReceiver without its permission",
             SHAPE_OK.replace(' android:permission="android.permission.DUMP"', ""),
             1, "ProfileInstallReceiver is guarded by None"),
        case("MainActivity renamed", SHAPE_OK.replace(MAIN_ACTIVITY, "com.readfilestech.rft.MainActivity"),
             1, "reviewed exported component absent: " + MAIN_ACTIVITY),
        case("requestLegacyExternalStorage absent (Android 10 lists nothing)",
             SHAPE_OK.replace('\n        android:requestLegacyExternalStorage="true"', ""),
             1, "android:requestLegacyExternalStorage> is None"),
        case("a tile without its permission (any app could bind it)",
             SHAPE_OK.replace(f'android:name="{TILE.format("Vault")}" android:exported="true"\n            android:permission="{TILE_PERMISSION}"',
                              f'android:name="{TILE.format("Vault")}" android:exported="true"'),
             1, "VaultTileService is guarded by None"),
        case("a tile renamed (the user's placed tile would vanish)", SHAPE_OK.replace(TILE.format("Ocr"), TILE.format("Text")),
             1, "reviewed exported component absent: " + TILE.format("Ocr")),
        case("the FileProvider exported",
             SHAPE_OK.replace('android:exported="false" android:grantUriPermissions="true"', 'android:exported="true" android:grantUriPermissions="true"'),
             1, "outside the reviewed list: androidx.core.content.FileProvider"),
        case("the FileProvider under another authority",
             SHAPE_OK.replace(f'"{ISOLATED}.fileprovider"', f'"{ISOLATED}.provider"'),
             1, "FileProvider: authorities is"),
        case("the FileProvider without per-URI grants",
             SHAPE_OK.replace(' android:grantUriPermissions="true"', ""),
             1, "android:grantUriPermissions is None"),
        case("the FileProvider absent", SHAPE_OK.replace("androidx.core.content.FileProvider", "com.example.OtherProvider"),
             1, "FileProvider found 0 times"),
        case("a package queried beyond the reviewed three",
             SHAPE_OK.replace("    </queries>", '        <package android:name="com.whatsapp" />\n    </queries>'),
             1, "<queries> packages are"),
        case("PDF Tech no longer queried (\"Edit in PDF Tech\" would vanish on Android 11+)",
             SHAPE_OK.replace('        <package android:name="com.pdftech.pdf_tech" />\n', ""),
             1, "<queries> packages are"),
        case("replacing build read as the isolated one", SHAPE_OK.replace(f'package="{ISOLATED}"', f'package="{FLUTTER}"'),
             1, "is not the release manifest of " + ISOLATED),
        # The package everywhere, the FileProvider's authority included.
        case("POSITIVE WITNESS, replacing build (--replace)",
             SHAPE_OK.replace(ISOLATED, FLUTTER), 0, "OK: backup off", ["--replace"]),
        case("file missing", None, 1, "merged manifest not found"),
        case("malformed XML", SHAPE_OK.replace("</manifest>", ""), 1, "merged manifest unreadable"),
    ]


def apk_cases(work: str) -> list[tuple]:
    base = version_base()
    universal = "app-universal-release.apk.badging.txt"

    def case(label: str, files: dict[str, str] | None, code: int, text: str, extra: list[str] | None = None) -> tuple:
        return (f"apks: {label}", APKS_CHECK, (extra or []) + ["--from-badging"],
                lambda: files_case(work, "badging", files), code, text)

    return [
        case("POSITIVE WITNESS (four APKs)", badgings(), 0, "OK: four release APKs"),
        case("POSITIVE WITNESS, replacing build (--replace)", badgings(FLUTTER), 0, "OK: four release APKs", ["--replace"]),
        case("universal APK left at the bare base", badgings(codes={"universal": base}),
             1, f"app-universal-release.apk: versionCode {base}, expected {base * 10}"),
        case("one ABI with the rank of another", badgings(codes={"x86_64": base * 10 + 2}),
             1, f"app-x86_64-release.apk: versionCode {base * 10 + 2}, expected {base * 10 + 3}"),
        case("a code not above Flutter 2.15.3", badgings(codes={"universal": 25503}),
             1, "versionCode 25503 is not above 25503"),
        case("isolated APKs released as the replacing build", badgings(), 1,
             f"package {ISOLATED}, expected {FLUTTER}", ["--replace"]),
        case("replacing APKs read as the isolated build", badgings(FLUTTER), 1, f"package {FLUTTER}, expected {ISOLATED}"),
        case("an APK missing", without(badgings(), universal), 1, "release APK missing: app-universal-release.apk"),
        case("an unexpected APK", badgings() | {"app-riscv64-release.apk.badging.txt": badging(ISOLATED, base * 10 + 4)},
             1, "unexpected release APK: app-riscv64-release.apk"),
        case("unreadable badging", badgings() | {universal: "ERROR: not an APK\n"}, 1, "no package line"),
        case("directory missing", None, 1, "directory not found"),
    ]


def test_cases(work: str) -> list[tuple]:
    def case(label: str, files: dict[str, str] | None, code: int, text: str) -> tuple:
        return (f"tests: {label}", TESTS_CHECK, [], lambda: files_case(work, "test-results", files), code, text)

    return [
        case("POSITIVE WITNESS (4 executed, 1 skipped)", REPORTS_OK, 0, "Tests executed: 4 (1 skipped, 2 reports)"),
        case("no report at all (the JUnit 6 symptom)", {}, 1, "no TEST-*.xml report"),
        case("directory missing", None, 1, "test results directory not found"),
        case("suites that count zero tests", {"TEST-com.example.ATest.xml": report("com.example.ATest", 0)},
             1, "0 tests executed in"),
        case("every test skipped, marked in the cases only",
             {"TEST-device-_app-.xml": report_skips_marked_in_cases_only("com.example.ATest", 3)}, 1, "0 tests executed in"),
        case("every test skipped", {"TEST-com.example.ATest.xml": report("com.example.ATest", 3, skipped=3)},
             1, "0 tests executed in"),
        case("malformed report", {"TEST-com.example.ATest.xml": "<testsuite tests="}, 1, "test report unreadable"),
        case("not a JUnit report", {"TEST-com.example.ATest.xml": "<coverage/>\n"}, 1, "no <testsuite> element"),
    ]


def real_cases(work: str, path: str, merged: bool) -> list[tuple] | None:
    with open(path, encoding="utf-8") as f:
        real = f.read()
    if "<application" not in real or "</application>" not in real:
        return None
    internet = permission("android.permission.INTERNET").strip()
    cases = [
        ("REAL manifest: permissions POSITIVE WITNESS", MANIFEST_CHECK, [],
         lambda: manifest_case(work, real), 0, "OK: no network permission"),
        ("REAL manifest + INTERNET", MANIFEST_CHECK, [],
         lambda: manifest_case(work, real.replace("<application", internet + "\n    <application", 1)),
         1, "NETWORK permission in the release manifest"),
    ]
    # The shape guard reads both: the merged manifest as it is, the APK's with --apk (S8), where a
    # resource reference is an id.
    shape = [] if merged else ["--apk"]
    kind = "merged manifest" if merged else "APK manifest"
    cases += [
        (f"REAL {kind}: shape POSITIVE WITNESS", SHAPE_CHECK, shape,
         lambda: manifest_case(work, real), 0, "OK: backup off"),
        (f"REAL {kind} + an exported activity", SHAPE_CHECK, shape,
         lambda: manifest_case(work, real.replace("</application>", EXTRA_EXPORTED_ACTIVITY + "    </application>", 1)),
         1, "outside the reviewed list: com.example.TestActivity"),
        (f"REAL {kind} with allowBackup=\"true\"", SHAPE_CHECK, shape,
         lambda: manifest_case(work, real.replace('android:allowBackup="false"', 'android:allowBackup="true"', 1)),
         1, "android:allowBackup> is 'true'"),
        (f"REAL {kind} without its data extraction rules", SHAPE_CHECK, shape,
         lambda: manifest_case(work, re.sub(r'\s*android:dataExtractionRules="[^"]*"', "", real, count=1)),
         1, "android:dataExtractionRules> is None"),
    ]
    return cases


def main(argv: list[str]) -> int:
    real_path, merged = None, False
    if len(argv) == 3 and argv[1] in ("--real-manifest", "--real-merged-manifest"):
        real_path, merged = argv[2], argv[1] == "--real-merged-manifest"
    elif len(argv) != 1:
        print(__doc__)
        return 2

    for script in (MANIFEST_CHECK, SHAPE_CHECK, APKS_CHECK, TESTS_CHECK):
        if not os.path.isfile(script):
            print(f"ERROR: {script} not found.")
            return 2
    if real_path is not None and not os.path.isfile(real_path):
        # Asked for and absent is a failure, never a skip: a skipped case would pass for a green one.
        print(f"ERROR: {argv[1]} {real_path} not found. Build it first.")
        return 1

    work = tempfile.mkdtemp(prefix="ci-guards-")
    cases = permission_cases(work) + shape_cases(work) + apk_cases(work) + test_cases(work)
    if real_path is not None:
        extra = real_cases(work, real_path, merged)
        if extra is None:
            print(f"ERROR: {real_path} has no <application> element to inject into.")
            shutil.rmtree(work, ignore_errors=True)
            return 1
        cases += extra

    failures = []
    try:
        for label, guard, extra_args, setup, expected_code, expected_text in cases:
            code, output = run(guard, setup(), extra_args)
            if code != expected_code:
                verdict = "NOT DETECTED" if expected_code else "REFUSED a healthy input"
                print(f"  FAIL  {label:62s} code={code} (expected {expected_code}) {verdict}")
                print("        " + output.strip().replace("\n", "\n        "))
                failures.append(label)
            elif expected_text not in output:
                print(f"  FAIL  {label:62s} right code, but for ANOTHER reason")
                print(f"        expected: {expected_text}")
                print("        " + output.strip().replace("\n", "\n        "))
                failures.append(label)
            else:
                print(f"  OK    {label:62s} {'passes' if expected_code == 0 else 'detected, right cause'}")
    finally:
        shutil.rmtree(work, ignore_errors=True)

    print()
    if failures:
        print(f"NEGATIVE CONTROL: {len(cases) - len(failures)}/{len(cases)} -- FAILED: {', '.join(failures)}")
        return 1
    print(f"NEGATIVE CONTROL: {len(cases)}/{len(cases)} -- every defect is detected, and for the right reason.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
