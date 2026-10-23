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
   usesCleartextTraffic="false", requestLegacyExternalStorage="true" — the values of the Flutter
   2.15.3 —; debuggable never "true". MainActivity: taskAffinity="", as in the 2.15.3.
3. Exported components (activity, activity-alias, service, receiver, provider): the EXACT set below,
   each with the permission that guards it. A component with an intent filter and no `exported`
   attribute counts as exported (the pre-Android 12 default), so an omission cannot hide one.
4. The FileProvider (phase 3): once, authority `<package>.fileprovider`, not exported, per-URI grants;
   and `<queries>`: exactly the packages the explorer sends files to by name.
5. Nothing of Google's services (docs D-032): no component, exported or not, and no meta-data of ML Kit
   (`com.google.mlkit`), Play services (`com.google.android.gms`), its telemetry transport
   (`com.google.android.datatransport`) or Firebase (`com.google.firebase`). ML Kit, the OCR's engine until
   phase 5, brought all four — its init provider, Firebase's component discovery, the transport's
   schedulers. tools/check-release-classpath.py refuses their artefacts before the manifest is merged.

Usage
-----
    python3 tools/check-manifest-shape.py [--replace] [--apk] [manifest]

Reads the merged manifest by default (resource references stay symbolic there). `--apk` reads the
manifest of the APK itself (`apkanalyzer manifest print`), the artefact that ships — anything wired
after the merge would escape the merged one (security review of 2026-10-11, S8): there a resource
reference reads `@ref/0x…`, and is accepted where a reference is expected; every literal is compared
exactly. Exit code 0 when the shape matches, 1 otherwise.
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
    # Without it the explorer lists nothing on Android 10 (scoped storage): the S9 dead end of 2026-08-09.
    "requestLegacyExternalStorage": "true",
}

# The FileProvider of the 2.15.x, same authority (docs D-012): never exported, per-URI grants only.
FILE_PROVIDER = "androidx.core.content.FileProvider"
FILE_PROVIDER_ATTRIBUTES = {"exported": "false", "grantUriPermissions": "true"}

# Package visibility (Android 11+), EXACTLY the 2.15.3's: PDF Tech and the three cloud apps a file is sent
# to by name (kDrive and Proton from the explorer, Google Drive too from the row under a tool's output).
# Another package here is an app the port can enumerate, which the privacy text must then say.
QUERIED_PACKAGES = {"com.pdftech.pdf_tech", "com.infomaniak.drive", "me.proton.android.drive", "com.google.android.apps.docs"}
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
    # The three quick-settings tiles of the 2.15.x, same full names (D-012), callable by the system only.
    "com.readfilestech.read_files_tech.ScannerTileService": "android.permission.BIND_QUICK_SETTINGS_TILE",
    "com.readfilestech.read_files_tech.OcrTileService": "android.permission.BIND_QUICK_SETTINGS_TILE",
    "com.readfilestech.read_files_tech.VaultTileService": "android.permission.BIND_QUICK_SETTINGS_TILE",
    # androidx.profileinstaller: callable only with DUMP (signature|privileged — the shell, the system).
    "androidx.profileinstaller.ProfileInstallReceiver": "android.permission.DUMP",
}

COMPONENT_TAGS = ("activity", "activity-alias", "service", "receiver", "provider")

# Packages no component and no meta-data may come from, exported or not (docs D-032): ML Kit, Play services,
# its telemetry transport, Firebase. A prefix, not a list of names: a library of theirs arrives under names
# nobody reviewed.
FORBIDDEN_PACKAGES = (
    "com.google.mlkit.",
    "com.google.android.gms.",
    "com.google.android.datatransport.",
    "com.google.firebase.",
)

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
            # Anything but a literal "false" counts as exported: the merger can keep a resource
            # reference (`@bool/x`) whose value this file does not show (review of the guards, 2026-10-10).
            if (exported is not None and exported != "false") or (exported is None and has_filter):
                found[element.get(A + "name") or f"<{tag} without a name>"] = element.get(A + "permission")
    return found


def matches(value: str | None, expected: str, apk: bool) -> bool:
    """Equal, except that in an APK a resource reference reads `@ref/0x7f…`: there, an expected
    `@xml/…` is satisfied by a reference being present (the name is gone from the binary XML).
    Literal values (`"false"`, `"true"`, `""`) are compared exactly in both."""
    if apk and expected.startswith("@"):
        return value is not None and value.startswith("@ref/0x")
    return value == expected


def file_provider_problems(application: ET.Element, package: str) -> list[str]:
    """The FileProvider: present once, under `<package>.fileprovider`, not exported, granting per URI."""
    providers = [e for e in application.iter("provider") if e.get(A + "name") == FILE_PROVIDER]
    if len(providers) != 1:
        return [f"{FILE_PROVIDER} found {len(providers)} times, expected once."]
    provider = providers[0]
    problems = []
    authority = provider.get(A + "authorities")
    if authority != f"{package}.fileprovider":
        problems.append(f"{FILE_PROVIDER}: authorities is {authority!r}, expected {package + '.fileprovider'!r}.")
    for attribute, expected in FILE_PROVIDER_ATTRIBUTES.items():
        value = provider.get(A + attribute)
        if value != expected:
            problems.append(f"{FILE_PROVIDER}: android:{attribute} is {value!r}, expected {expected!r}.")
    return problems


def main(argv: list[str]) -> int:
    args = argv[1:]
    replace = "--replace" in args
    apk = "--apk" in args
    args = [a for a in args if a not in ("--replace", "--apk")]
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
        if not matches(value, expected, apk):
            problems.append(f"<application android:{attribute}> is {value!r}, expected {expected!r}.")
    for attribute in NEVER_TRUE:
        # Absent or a literal "false" only: a resource reference could hold "true".
        value = application.get(A + attribute)
        if value is not None and value != "false":
            problems.append(f"<application android:{attribute}={value!r}> in a release manifest.")

    for name, attributes in REQUIRED_COMPONENT_ATTRIBUTES.items():
        components = [e for tag in COMPONENT_TAGS for e in application.iter(tag) if e.get(A + "name") == name]
        if not components:
            problems.append(f"{name} not found in the manifest.")
            continue
        for attribute, expected in attributes.items():
            value = components[0].get(A + attribute)
            if value != expected:
                problems.append(f"{name}: android:{attribute} is {value!r}, expected {expected!r}.")

    problems += file_provider_problems(application, expected_package)
    for tag in COMPONENT_TAGS + ("meta-data",):
        for element in application.iter(tag):
            name = element.get(A + "name") or ""
            if name.startswith(FORBIDDEN_PACKAGES):
                problems.append(f"Google component in the manifest: <{tag}> {name}")
    queried = {p.get(A + "name") for q in root.iter("queries") for p in q.iter("package")}
    if queried != QUERIED_PACKAGES:
        problems.append(f"<queries> packages are {sorted(queried)}, expected {sorted(QUERIED_PACKAGES)}.")

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
