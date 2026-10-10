#!/usr/bin/env python3
"""Assert the SHAPE of the merged release manifest: backup off, nothing debuggable, and exactly the
reviewed exported components.

Why this exists
---------------
The permission guard (check-manifest-permissions.py) sees permissions only. A manifest can lose what
protects the user's data without any permission changing:
  - `allowBackup` dropped (its default is TRUE): the vault's salt, KDF parameters and failure counters
    in shared_prefs/, and the encrypted files under app_flutter/vault/, would go to a Google Drive
    backup, and a device transfer would restore a failure counter at zero;
  - a component exported by a library, or by a test activity left in the main source set, is an entry
    point any app can call — the prototype had one (ProtoActivity) that read paths and passwords from
    its intent.
External review of the port plan, 2026-10-10, finding 3.

What is checked
---------------
1. The package, as in the permission guard: the file read is the release manifest of this app.
2. `<application>`: allowBackup="false", fullBackupContent="false", dataExtractionRules set,
   usesCleartextTraffic="false" — the values of the Flutter 2.15.3 —; debuggable never "true".
   MainActivity: taskAffinity="", as in the 2.15.3.
3. Exported components (activity, activity-alias, service, receiver, provider): the EXACT set below,
   each with the permission that guards it. A component with an intent filter and no `exported`
   attribute counts as exported (the pre-Android 12 default), so an omission cannot hide one.

Usage
-----
    python3 tools/check-manifest-shape.py [--replace] [merged-manifest]

Reads the merged manifest (resource references stay symbolic there, unlike in an APK's binary XML).
Exit code 0 when the shape matches, 1 otherwise.
"""

from __future__ import annotations

import os
import sys
import xml.etree.ElementTree as ET

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

A = "{http://schemas.android.com/apk/res/android}"

DEFAULT_MANIFEST = os.path.join(
    "app", "build", "intermediates", "merged_manifest", "release",
    "processReleaseMainManifest", "AndroidManifest.xml",
)

FLUTTER_PACKAGE = "com.readfilestech.read_files_tech"
ISOLATED_PACKAGE = FLUTTER_PACKAGE + ".next"

REQUIRED_APPLICATION_ATTRIBUTES = {
    "allowBackup": "false",
    "fullBackupContent": "false",
    "dataExtractionRules": "@xml/data_extraction_rules",
    "usesCleartextTraffic": "false",
}
NEVER_TRUE = ("debuggable",)

# Attributes a given component must carry, as in the 2.15.x manifest.
REQUIRED_COMPONENT_ATTRIBUTES = {
    # An empty affinity: another app declaring the package-name affinity cannot slip its own activity
    # into this task, in front of the vault's password screen (security review, 2026-10-10, M1).
    "com.readfilestech.read_files_tech.MainActivity": {"taskAffinity": ""},
}

# Full class name -> permission required to call it (None: open to every app, which must be deliberate).
# Each phase that exports a component (the three quick-settings tiles, docs D-012) adds it here.
EXPORTED = {
    # The launcher entry point. Its full name is the Flutter one (docs D-012).
    "com.readfilestech.read_files_tech.MainActivity": None,
    # androidx.profileinstaller: callable only with DUMP (signature|privileged — the shell, the system).
    "androidx.profileinstaller.ProfileInstallReceiver": "android.permission.DUMP",
}

COMPONENT_TAGS = ("activity", "activity-alias", "service", "receiver", "provider")

IN_CI = os.environ.get("GITHUB_ACTIONS") == "true"


def fail(lines: list[str]) -> None:
    prefix = "::error::" if IN_CI else "ERROR: "
    for line in lines:
        print(f"{prefix}{line}")


def exported_components(application: ET.Element) -> dict[str, str | None]:
    found: dict[str, str | None] = {}
    for tag in COMPONENT_TAGS:
        for element in application.iter(tag):
            exported = element.get(A + "exported")
            has_filter = element.find("intent-filter") is not None
            if exported == "true" or (exported is None and has_filter):
                found[element.get(A + "name") or f"<{tag} without a name>"] = element.get(A + "permission")
    return found


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
        fail([f"merged manifest not found: {manifest_path}", "run first: ./gradlew :app:processReleaseMainManifest"])
        return 1
    try:
        root = ET.parse(manifest_path).getroot()
    except ET.ParseError as error:
        fail([f"merged manifest unreadable ({manifest_path}): {error}"])
        return 1
    application = root.find("application")
    if root.tag != "manifest" or application is None:
        fail([f"{manifest_path} is not an application manifest -", "this is a failure of the check, not a verdict."])
        return 1
    if root.get("package") != expected_package:
        fail([
            f"{manifest_path} is not the release manifest of {expected_package} (package={root.get('package')}) -",
            "this is a failure of the check, not a verdict.",
        ])
        return 1

    problems: list[str] = []

    for attribute, expected in REQUIRED_APPLICATION_ATTRIBUTES.items():
        value = application.get(A + attribute)
        if value != expected:
            problems.append(f"<application android:{attribute}> is {value!r}, expected {expected!r}.")
    for attribute in NEVER_TRUE:
        if application.get(A + attribute) == "true":
            problems.append(f"<application android:{attribute}=\"true\"> in a release manifest.")

    for name, attributes in REQUIRED_COMPONENT_ATTRIBUTES.items():
        components = [e for tag in COMPONENT_TAGS for e in application.iter(tag) if e.get(A + "name") == name]
        if not components:
            problems.append(f"{name} not found in the manifest.")
            continue
        for attribute, expected in attributes.items():
            value = components[0].get(A + attribute)
            if value != expected:
                problems.append(f"{name}: android:{attribute} is {value!r}, expected {expected!r}.")

    found = exported_components(application)
    print(f"Manifest: {manifest_path}")
    print(f"Exported components ({len(found)}):")
    for name in sorted(found):
        mark = "ok " if name in EXPORTED and EXPORTED[name] == found[name] else "!! "
        print(f"  {mark}{name}  (permission: {found[name]})")

    for name in sorted(set(found) - set(EXPORTED)):
        problems.append(f"exported component outside the reviewed list: {name} (permission: {found[name]})")
    for name in sorted(set(EXPORTED) - set(found)):
        problems.append(f"reviewed exported component absent: {name} — renamed, moved or no longer exported?")
    for name in sorted(set(found) & set(EXPORTED)):
        if found[name] != EXPORTED[name]:
            problems.append(f"{name} is guarded by {found[name]!r}, expected {EXPORTED[name]!r}.")

    if problems:
        print()
        fail(problems)
        return 1
    print()
    print("OK: backup off, nothing debuggable, and exactly the reviewed exported components.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
