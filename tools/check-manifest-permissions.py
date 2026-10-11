#!/usr/bin/env python3
"""Assert the permissions of the MERGED release manifest — or of a release APK — against a reviewed list.

Why this exists
---------------
Read Files Tech promises "no internet permission" since the Flutter 2.15.3 (docs D-014), on
files-tech.com, in the README and in PRIVACY.md. A promise no build checks is an intention: this
script turns it into a build constraint. Precedent in the portfolio: Agenda Tech PUBLISHED an APK
carrying ACCESS_NETWORK_STATE, pulled transitively by androidx.work, unnoticed until a later audit.

Two traps this script avoids
----------------------------
1. The source manifest is not evidence: libraries add permissions at merge time. Only the merged
   manifest — or the manifest of the APK itself — decides what the published APK asks for.
2. `<uses-permission>` can span several lines, a shape the merge produces. This parses the XML.

The list is EXACT, not only a ceiling
-------------------------------------
A permission outside the list fails (it must be a deliberate, reviewed act). A permission IN the list
but absent from the manifest fails too: the list, PRIVACY.md and the APK would otherwise describe three
different apps. Each phase of the port that adds a permission adds it here, in the same commit.

Usage
-----
    python3 tools/check-manifest-permissions.py [--replace] [manifest]

`manifest` defaults to the merged release manifest of the default build. It may also be the manifest
of a built APK, as printed by `apkanalyzer manifest print <apk>`.
`--replace` expects the build that takes the Flutter app's place (`-Prft.replaceFlutter=true`, package
without `.next`); without it, the default isolated build is expected (docs D-008).

Exit code 0 when the manifest matches the list exactly, 1 otherwise. Prints GitHub Actions
`::error::` annotations under CI, plain text otherwise.
"""

from __future__ import annotations

import os
import sys
import xml.etree.ElementTree as ET

# The Windows console is cp1252: a non-ASCII character in a message would otherwise crash the
# script, that is, report a FAILED check when the check succeeded.
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

ANDROID_NS = "http://schemas.android.com/apk/res/android"

DEFAULT_MANIFEST = os.path.join(
    "app", "build", "intermediates", "merged_manifest", "release",
    "processReleaseMainManifest", "AndroidManifest.xml",
)

FLUTTER_PACKAGE = "com.readfilestech.read_files_tech"
ISOLATED_PACKAGE = FLUTTER_PACKAGE + ".next"

# Must NEVER reach the APK, whatever the list says: the permissions that open a socket or read the
# network. Kept apart from the list so the failure message can say WHY. Same set as the Flutter guard.
FORBIDDEN = {
    "android.permission.INTERNET",
    "android.permission.ACCESS_NETWORK_STATE",
    "android.permission.ACCESS_WIFI_STATE",
    "android.permission.CHANGE_NETWORK_STATE",
    "android.permission.CHANGE_WIFI_STATE",
}


def allowed_for(package: str) -> set[str]:
    """Every permission the reviewed APK carries — no more, no less.

    Phase 1 (skeleton): the one androidx.core generates for its own non-exported receivers (signature
    level, named after the applicationId). Phase 3 (the explorer): shared storage, as the 2.15.3 — read
    up to Android 12, write up to Android 10, all-files access from Android 11. The 2.15.3 carries nine;
    READ_MEDIA_* and CAMERA do not come back (docs D-027), INTERNET and ACCESS_NETWORK_STATE never
    (D-014). Each permission comes with the phase that needs it, documented in PRIVACY.md at the same time.
    """
    return {
        f"{package}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
        "android.permission.READ_EXTERNAL_STORAGE",
        "android.permission.WRITE_EXTERNAL_STORAGE",
        "android.permission.MANAGE_EXTERNAL_STORAGE",
    }


IN_CI = os.environ.get("GITHUB_ACTIONS") == "true"


def fail(lines: list[str]) -> None:
    prefix = "::error::" if IN_CI else "ERROR: "
    for line in lines:
        print(f"{prefix}{line}")


def parse(manifest_path: str) -> tuple[ET.Element, set[str]]:
    """The manifest root and every permission it requests, parsed as XML rather than grepped."""
    root = ET.parse(manifest_path).getroot()
    names = set()
    # Every spelling, matched by prefix: `<uses-permission-sdk-23>` grants the same capability on
    # API 23+, and the platform parser still accepts its pre-release alias `<uses-permission-sdk-m>`
    # (aapt2 rejects it, the merger keeps it — measured on App Manager Tech, 2026-10-08).
    for element in root.iter():
        if element.tag.startswith("uses-permission"):
            name = element.get(f"{{{ANDROID_NS}}}name")
            if name:
                names.add(name)
    return root, names


def main(argv: list[str]) -> int:
    args = argv[1:]
    replace = "--replace" in args
    args = [a for a in args if a != "--replace"]
    if len(args) > 1 or any(a.startswith("--") for a in args):
        print(__doc__)
        return 2
    manifest_path = args[0] if args else DEFAULT_MANIFEST
    expected_package = FLUTTER_PACKAGE if replace else ISOLATED_PACKAGE

    if not os.path.isfile(manifest_path):
        fail([
            f"merged manifest not found: {manifest_path}",
            "run first: ./gradlew :app:processReleaseMainManifest",
        ])
        return 1

    try:
        root, found = parse(manifest_path)
    except ET.ParseError as error:
        fail([f"merged manifest unreadable ({manifest_path}): {error}"])
        return 1

    if root.tag != "manifest" or root.find("application") is None:
        fail([
            f"{manifest_path} is not an application manifest -",
            "this is a failure of the check, not an absence of permissions.",
        ])
        return 1

    package = root.get("package")
    if package != expected_package:
        fail([
            f"{manifest_path} is not the release manifest of {expected_package} (package={package}) -",
            "this is a failure of the check, not a verdict on permissions.",
        ])
        return 1

    if not found:
        # An empty result is a broken check, never a clean bill of health: androidx.core alone
        # declares one permission in every build of this app.
        fail([
            f"no permission found in {manifest_path} -",
            "this is a failure of the check, not an absence of permissions.",
        ])
        return 1

    allowed = allowed_for(package)
    print(f"Manifest: {manifest_path}")
    print(f"applicationId: {package}")
    print(f"Permissions ({len(found)}):")
    for name in sorted(found):
        mark = "ok " if name in allowed else "!! "
        print(f"  {mark}{name}")

    problems: list[str] = []

    forbidden_present = sorted(found & FORBIDDEN)
    if forbidden_present:
        problems.append("NETWORK permission in the release manifest:")
        problems += [f"  {name}" for name in forbidden_present]
        problems.append(
            "Read Files Tech publicly promises \"no internet permission\". Either the dependency that "
            "pulled it goes, or it is removed with tools:node=\"remove\" and the removal justified.",
        )

    unexpected = sorted(found - allowed - FORBIDDEN)
    if unexpected:
        problems.append("Permission(s) outside the reviewed list:")
        problems += [f"  {name}" for name in unexpected]
        problems.append(
            "A permission must be a deliberate act: add it to allowed_for() in this script AND to "
            "PRIVACY.md / PRIVACY.fr.md, or drop what brought it.",
        )

    missing = sorted(allowed - found)
    if missing:
        problems.append("Permission(s) in the reviewed list but absent from the manifest:")
        problems += [f"  {name}" for name in missing]
        problems.append(
            "The list, PRIVACY.md and the APK must describe the same app: remove them from "
            "allowed_for() and from PRIVACY.md if the removal is intended.",
        )

    if problems:
        print()
        fail(problems)
        return 1

    print()
    print("OK: no network permission, and exactly the reviewed list.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
