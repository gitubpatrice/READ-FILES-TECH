#!/usr/bin/env python3
"""Assert that nothing of Google's ML Kit, Play services, Firebase or telemetry is in the release build.

Why this exists
---------------
Read Files Tech's apps are on no store of Google's and depend on none of its services (docs D-032,
Patrice, 2026-10-22). The OCR used ML Kit until then, which reported each use through Google Play
services' client telemetry, a binding that needs no permission: the permission guard could not see it,
and the manifest guard saw only the components it declares (tools/check-manifest-shape.py). A library
of these groups can do its work without a single component — so the CLASSPATH is checked, before R8:
any artefact of them that the release would package fails, whatever it declares.

The groups: `com.google.mlkit`, `com.google.android.gms` (Play services), `com.google.android.datatransport`
(the telemetry transport), `com.google.firebase`, `com.google.android.odml` (ML Kit's on-device runtime);
each with its sub-groups.

What is read
------------
The report of `./gradlew :app:dependencies --configuration releaseRuntimeClasspath`, saved to a file:
every module resolved, transitive ones included, as Gradle SELECTED it (`a:b:1 -> 2` is b at 2; `a:b:1 ->
c:d:2` is c:d — a substitution). Constraints (`(c)`) are not dependencies and are not counted. A report
that is not one of that configuration, holds no dependency, or says a resolution FAILED is a failure of the
check, not a verdict.

Usage
-----
    ./gradlew :app:dependencies --configuration releaseRuntimeClasspath > classpath.txt
    python3 tools/check-release-classpath.py classpath.txt

Exit code 0 when no forbidden artefact is found, 1 otherwise. Negative control:
tools/check-guards-negative-control.py.
"""

from __future__ import annotations

import os
import re
import sys

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

CONFIGURATION = "releaseRuntimeClasspath"

FORBIDDEN_GROUPS = (
    "com.google.mlkit",
    "com.google.android.gms",
    "com.google.android.datatransport",
    "com.google.firebase",
    "com.google.android.odml",
)

# `+--- group:name:version`, `\--- group:name:version -> other (*)`, `+--- group:name:{strictly 1} -> 1`,
# `+--- project :lib`: the module's first token, then the rest of the line.
LINE = re.compile(r"[+\\]--- (?P<module>\S+)(?P<rest>.*)$")
SELECTED = re.compile(r" -> (?P<selected>[^\s(]+)")

IN_CI = os.environ.get("GITHUB_ACTIONS") == "true"


def fail(lines: list[str]) -> None:
    prefix = "::error::" if IN_CI else "ERROR: "
    for line in lines:
        print(f"{prefix}{line}")


def forbidden(group: str) -> bool:
    return any(group == g or group.startswith(g + ".") for g in FORBIDDEN_GROUPS)


def selected_module(module: str, selected: str | None) -> str:
    """The coordinates Gradle chose: a version change keeps the module, a substitution names another."""
    if selected is None:
        return module
    if ":" in selected:
        return selected
    group, name = module.split(":")[:2]
    return f"{group}:{name}:{selected}"


def main(argv: list[str]) -> int:
    if len(argv) != 2 or argv[1].startswith("--"):
        print(__doc__)
        return 2
    path = argv[1]
    if not os.path.isfile(path):
        fail([f"dependency report not found: {path}",
              f"run first: ./gradlew :app:dependencies --configuration {CONFIGURATION} > {path}"])
        return 1
    with open(path, encoding="utf-8", errors="replace") as f:
        lines = f.read().splitlines()

    if not any(line.startswith(f"{CONFIGURATION} - ") for line in lines):
        fail([f"{path} is not a report of {CONFIGURATION} -", "this is a failure of the check, not a verdict."])
        return 1
    failed = [line.strip() for line in lines if "FAILED" in line]
    if failed:
        fail([f"{path}: a resolution FAILED ({failed[0]}) -", "this is a failure of the check, not a verdict."])
        return 1

    modules: set[str] = set()
    found: list[str] = []
    for line in lines:
        match = LINE.search(line)
        if match is None or match.group("module") == "project" or ":" not in match.group("module"):
            continue
        rest = match.group("rest")
        if "(c)" in rest:
            continue
        selected = SELECTED.search(rest)
        module = selected_module(match.group("module"), selected.group("selected") if selected else None)
        modules.add(module)
        if forbidden(module.split(":")[0]):
            found.append(module)

    if not modules:
        fail([f"{path} lists no dependency -", "this is a failure of the check, not a verdict."])
        return 1
    print(f"Report: {path} ({len(modules)} modules in {CONFIGURATION})")
    if found:
        fail([f"Google artefact in the release classpath: {module}" for module in sorted(set(found))])
        return 1
    print("OK: nothing of ML Kit, Play services, Firebase or Google's telemetry in the release classpath.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
