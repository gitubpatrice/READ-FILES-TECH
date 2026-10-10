#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Assert the permissions of the MERGED release manifest against the reviewed allowlist.

Why this exists
---------------
Since 2.15.3 Read Files Tech holds NO network permission, like Notes Tech, Agenda Tech, App
Manager Tech and SMS Tech (decision of 2026-10-10). Until 2.15.2 it declared INTERNET for the
update check; earlier still, before 2.15, the permission was in the APK WITHOUT being in the
source manifest at all: the manifest merger added it through
`com.google.android.datatransport:transport-backend-cct`, which Google ML Kit pulls in. The
source manifest now removes both permissions with `tools:node="remove"`. A promise no build
checks is an intention: this script makes the build fail when it breaks. Model:
agenda_tech/tools/check-manifest-permissions.py.

Two traps this script exists to avoid
-------------------------------------
1. **The source manifest is not evidence.** It contains INTERNET *in order to remove it*, and a
   dependency can add a permission at merge time. Only the merged manifest of the RELEASE variant
   says what the published APK carries (the debug variant of a Flutter app adds INTERNET on purpose,
   for the development tools).
2. **`<uses-permission>` can span several lines.** A line-based grep misses it. This parses the XML.

Usage
-----
    python3 tools/check-manifest-permissions.py [path-to-merged-manifest]
    python3 tools/check-manifest-permissions.py --controle-negatif

Exit code 0 when the manifest matches the allowlist exactly, 1 otherwise. The negative control
writes faulty manifests and requires every one of them to be REJECTED: a check that cannot fail
protects nothing.
"""

from __future__ import annotations

import os
import sys
import tempfile
import xml.etree.ElementTree as ET

ANDROID_NS = "http://schemas.android.com/apk/res/android"

DEFAULT_MANIFEST = os.path.join(
    "build", "app", "intermediates", "merged_manifest", "release",
    "processReleaseMainManifest", "AndroidManifest.xml",
)

FORBIDDEN = {
    "android.permission.INTERNET",
    "android.permission.ACCESS_NETWORK_STATE",
    "android.permission.ACCESS_WIFI_STATE",
    "android.permission.CHANGE_NETWORK_STATE",
    "android.permission.CHANGE_WIFI_STATE",
}

# Measured on the 2.15.3 release APK (`aapt2 dump permissions`, 2026-10-10). Each entry is
# explained in PRIVACY.md / PRIVACY.fr.md. A new one must be a deliberate act: add it here AND
# there, or remove the dependency that brought it.
ALLOWED = {
    "android.permission.READ_EXTERNAL_STORAGE",
    "android.permission.WRITE_EXTERNAL_STORAGE",
    "android.permission.MANAGE_EXTERNAL_STORAGE",
    "android.permission.READ_MEDIA_IMAGES",
    "android.permission.READ_MEDIA_VIDEO",
    "android.permission.READ_MEDIA_AUDIO",
    "android.permission.REQUEST_INSTALL_PACKAGES",
    "android.permission.CAMERA",
    "com.readfilestech.read_files_tech.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
}

IN_CI = os.environ.get("GITHUB_ACTIONS") == "true"


def fail(lines: list[str]) -> None:
    prefix = "::error::" if IN_CI else "ERREUR: "
    for line in lines:
        print(f"{prefix}{line}")


def declared_permissions(manifest_path: str) -> set[str]:
    """Every `android:name` of a `<uses-permission>` element, parsed as XML rather than grepped."""
    root = ET.parse(manifest_path).getroot()
    names = set()
    for tag in ("uses-permission", "uses-permission-sdk-23"):
        for element in root.iter(tag):
            name = element.get(f"{{{ANDROID_NS}}}name")
            if name:
                names.add(name)
    return names


def check(manifest_path: str, quiet: bool = False) -> int:
    if not os.path.isfile(manifest_path):
        fail([
            f"merged manifest not found: {manifest_path}",
            "build it first: flutter build apk --release (or --config-only + processReleaseMainManifest)",
        ])
        return 1
    try:
        found = declared_permissions(manifest_path)
    except ET.ParseError as error:
        fail([f"unreadable merged manifest ({manifest_path}): {error}"])
        return 1
    if not found:
        fail([
            f"no permission found in {manifest_path} -",
            "that is a failure of the check, not an absence of permissions.",
        ])
        return 1

    if not quiet:
        print(f"Permissions in the merged release manifest ({len(found)}):")
        for name in sorted(found):
            print(f"  {name}")

    problems: list[str] = []
    forbidden_present = sorted(found & FORBIDDEN)
    if forbidden_present:
        problems.append("NETWORK permission in the merged release manifest:")
        problems += [f"  {name}" for name in forbidden_present]
        problems.append(
            "Read Files Tech promises no Internet permission. Either the dependency that pulled it "
            "goes, or it is removed with tools:node=\"remove\" and the removal is justified.",
        )
    unexpected = sorted(found - ALLOWED - FORBIDDEN)
    if unexpected:
        problems.append("Permission(s) outside the reviewed list:")
        problems += [f"  {name}" for name in unexpected]
        problems.append(
            "A permission that arrives transitively must be a deliberate act: add it to ALLOWED "
            "in this script AND to PRIVACY.md / PRIVACY.fr.md, or remove the dependency.",
        )
    # An error, not a note (unlike Agenda Tech's model): PRIVACY.md states the APK carries
    # EXACTLY these permissions, and a vanished CAMERA would also break the scanner and OCR.
    missing = sorted(ALLOWED - found)
    if missing:
        problems.append("Expected permission(s) absent from the merged manifest:")
        problems += [f"  {name}" for name in missing]
        problems.append(
            "If the removal is intended, remove them from ALLOWED and from PRIVACY.md / "
            "PRIVACY.fr.md, which state the exact list.",
        )

    if problems:
        if not quiet:
            print()
        fail(problems)
        return 1
    if not quiet:
        print()
        print("OK: no network permission, and nothing outside the reviewed list.")
    return 0


def negative_control() -> int:
    """Every faulty manifest below must be REJECTED, and the clean one accepted."""
    head = (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        f'<manifest xmlns:android="{ANDROID_NS}" package="x">\n'
    )
    allowed = "".join(f'  <uses-permission android:name="{p}" />\n' for p in sorted(ALLOWED))
    cases = {
        "propre (doit PASSER)": (allowed, 0),
        "INTERNET": (allowed + '  <uses-permission android:name="android.permission.INTERNET" />\n', 1),
        "ACCESS_NETWORK_STATE sur plusieurs lignes": (
            allowed + "  <uses-permission\n      android:name=\n"
            '        "android.permission.ACCESS_NETWORK_STATE"\n      />\n',
            1,
        ),
        "INTERNET en uses-permission-sdk-23": (
            allowed + '  <uses-permission-sdk-23 android:name="android.permission.INTERNET" />\n', 1,
        ),
        "permission hors liste": (
            allowed + '  <uses-permission android:name="android.permission.READ_CONTACTS" />\n', 1,
        ),
        "CAMERA manquante": (
            allowed.replace('  <uses-permission android:name="android.permission.CAMERA" />\n', ""),
            1,
        ),
        "aucune permission": ("", 1),
    }
    bad = 0
    with tempfile.TemporaryDirectory() as tmp:
        for label, (body, expected) in cases.items():
            path = os.path.join(tmp, "AndroidManifest.xml")
            with open(path, "w", encoding="utf-8") as fh:
                fh.write(head + body + "</manifest>\n")
            code = check(path, quiet=True)
            verdict = "ok" if code == expected else "FAUX"
            if code != expected:
                bad += 1
            print(f"  [{verdict}] {label} : code {code}, attendu {expected}")
    if bad:
        fail([f"negative control: {bad} case(s) not handled as expected"])
        return 1
    print("Negative control OK: every faulty manifest was rejected, the clean one accepted.")
    return 0


def main(argv: list[str]) -> int:
    if len(argv) > 1 and argv[1] == "--controle-negatif":
        return negative_control()
    return check(argv[1] if len(argv) > 1 else DEFAULT_MANIFEST)


if __name__ == "__main__":
    sys.exit(main(sys.argv))
