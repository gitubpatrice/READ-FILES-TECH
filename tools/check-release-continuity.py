#!/usr/bin/env python3
"""Assert that the new release APKs CONTINUE the published release, read from both sets of APKs.

Why this exists
---------------
check-release-apks.py reads the new APKs against this repository: the package it expects, the base of
version.properties, the floor 25503 written when the port began. None of that is the release users have.
An APK can pass it and still update no phone: signed by another key (Android refuses the update, and
uninstalling destroys the vault), a versionCode not above the APK a user installed, the launcher activity or
a tile renamed (the system forgets the icon pinned and the tiles placed). Each of these is read here from
the APKs of the PUBLISHED release, downloaded, never from a constant (plan, phase 9: release.yml in two
steps; docs/09-PROTOCOLE-BASCULE.md §1).

What is checked, on each new APK
--------------------------------
- its package is the published one: an isolated `.next` build would install NEXT TO the app;
- its versionCode is above the highest of the published APKs;
- its certificate is the published APKs' one, and the only one (SHA-256; a key rotation is refused, not read);
- the published launcher activity is there, under the same name;
- every published quick-settings tile is there, same name, same permission;
- the authority of the published androidx FileProvider is there (docs D-012).
The published APKs must agree among themselves on all of it, and must yield a launcher activity, a tile and a
FileProvider: read as nothing — aapt2 printing otherwise one day — they would make every comparison pass
(GPT 5.6 review, 2026-10-25).

Usage
-----
    python3 tools/check-release-continuity.py NEW_APK_DIR PUBLISHED_APK_DIR
    python3 tools/check-release-continuity.py --negative-control NEW_APK_DIR PUBLISHED_APK_DIR
    python3 tools/check-release-continuity.py --self-test PUBLISHED_APK_DIR

`--negative-control` checks that the real APKs pass, then breaks a copy of what was read from them once per
defect — a `.next` package, a versionCode of 3000, another certificate, a renamed tile… — and requires each
copy to fail, for its own reason. `--self-test` does the same with no new APK: the new set is the published
one with its versionCodes raised, which is how the CI keeps this check honest between two releases.
`--from-dumps` (before the directories) reads `<apk>.badging.txt`, `<apk>.manifest.txt` (aapt2 xmltree of
AndroidManifest.xml) and `<apk>.certs.txt` (apksigner verify --print-certs) instead of APKs.
"""

from __future__ import annotations

import glob
import os
import re
import subprocess
import sys
from dataclasses import dataclass, field

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

IN_CI = os.environ.get("GITHUB_ACTIONS") == "true"

QS_TILE = "android.service.quicksettings.action.QS_TILE"
FILE_PROVIDER = "androidx.core.content.FileProvider"
BADGING, MANIFEST, CERTS = ".badging.txt", ".manifest.txt", ".certs.txt"
DUMPS = (BADGING, MANIFEST, CERTS)
# Every certificate apksigner names — signers, a source stamp, a rotation lineage: one more than the published
# certificate fails, which is the side to fail on.
DIGEST = re.compile(r"(certificate SHA-256 digest: )([0-9a-fA-F]{64})")

Dumps = dict[str, str]


def fail(lines: list[str]) -> None:
    prefix = "::error::" if IN_CI else "ERROR: "
    for line in lines:
        print(f"{prefix}{line}")


def build_tool(name: str) -> str:
    home = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not home:
        raise FileNotFoundError("ANDROID_HOME is not set")
    candidates = [
        path for path in glob.glob(os.path.join(home, "build-tools", "*", name + "*"))
        if os.path.basename(path) in (name, name + ".exe", name + ".bat")
    ]
    if not candidates:
        raise FileNotFoundError(f"no {name} under {home}/build-tools")
    # By version, not as text: "9.0.0" sorts after "37.0.0" as text.
    return max(candidates, key=lambda p: [int(n) for n in re.findall(r"\d+", os.path.basename(os.path.dirname(p)))])


def run(command: list[str]) -> str:
    result = subprocess.run(command, capture_output=True, text=True, encoding="utf-8")
    if result.returncode != 0:
        raise RuntimeError(f"{os.path.basename(command[0])} failed on {command[-1]}: {(result.stderr or result.stdout).strip()}")
    return result.stdout


def dumps_of(apk: str) -> Dumps:
    aapt2 = build_tool("aapt2")
    return {
        BADGING: run([aapt2, "dump", "badging", apk]),
        MANIFEST: run([aapt2, "dump", "xmltree", "--file", "AndroidManifest.xml", apk]),
        # `verify` first: an APK whose signature does not verify is not signed by anyone.
        CERTS: run([build_tool("apksigner"), "verify", "--print-certs", apk]),
    }


def read_set(directory: str, from_dumps: bool) -> dict[str, Dumps]:
    if not from_dumps:
        return {os.path.basename(p): dumps_of(p) for p in sorted(glob.glob(os.path.join(directory, "*.apk")))}
    out: dict[str, Dumps] = {}
    for path in sorted(glob.glob(os.path.join(directory, "*.apk" + BADGING))):
        name = os.path.basename(path)[: -len(BADGING)]
        out[name] = {}
        for suffix in DUMPS:
            with open(os.path.join(directory, name + suffix), encoding="utf-8") as f:
                out[name][suffix] = f.read()
    return out


@dataclass
class Element:
    tag: str
    attrs: dict[str, str] = field(default_factory=dict)
    children: list[Element] = field(default_factory=list)

    def walk(self):
        yield self
        for child in self.children:
            yield from child.walk()


ELEMENT_LINE = re.compile(r"^( *)E: (\S+)")
# `A: http://schemas.android.com/apk/res/android:name(0x01010003)="value" (Raw: "value")`, or `=true`, `=@0x7f…`.
ATTRIBUTE_LINE = re.compile(
    r"^( *)A: (?:http://schemas\.android\.com/apk/res/android:)?([\w.-]+?)(?:\(0x[0-9a-f]+\))?=(.*?)(?: \(Raw: .*\))?$",
)


def parse_xmltree(text: str) -> Element:
    """The tree aapt2 prints, rebuilt from its indentation: an element owns what is indented under it."""
    root = Element("#document")
    stack: list[tuple[int, Element]] = [(-1, root)]
    for line in text.splitlines():
        if match := ELEMENT_LINE.match(line):
            indent = len(match.group(1))
            while stack[-1][0] >= indent:
                stack.pop()
            element = Element(match.group(2))
            stack[-1][1].children.append(element)
            stack.append((indent, element))
        elif match := ATTRIBUTE_LINE.match(line):
            indent = len(match.group(1))
            while stack[-1][0] >= indent:
                stack.pop()
            value = match.group(3)
            if len(value) >= 2 and value.startswith('"') and value.endswith('"'):
                value = value[1:-1]
            stack[-1][1].attrs[match.group(2)] = value
    return root


@dataclass
class Facts:
    package: str | None
    version_code: int | None
    launchers: set[str]
    tiles: dict[str, str | None]
    file_providers: set[str]
    certificates: set[str]

    def components(self) -> tuple:
        """What must not change from one APK of a release to another, nor from a release to the next."""
        return frozenset(self.launchers), frozenset(self.tiles.items()), frozenset(self.file_providers)


def is_tile(service: Element) -> bool:
    return any(
        action.tag == "action" and action.attrs.get("name") == QS_TILE
        for intent_filter in service.children if intent_filter.tag == "intent-filter"
        for action in intent_filter.children
    )


def facts_of(dumps: Dumps) -> Facts:
    badging = dumps[BADGING]
    package = re.search(r"^package: name='([^']+)' versionCode='(\d+)'", badging, re.M)
    tiles: dict[str, str | None] = {}
    providers: set[str] = set()
    for element in parse_xmltree(dumps[MANIFEST]).walk():
        if element.tag == "service" and is_tile(element):
            tiles[element.attrs.get("name", "?")] = element.attrs.get("permission")
        if element.tag == "provider" and element.attrs.get("name") == FILE_PROVIDER:
            providers |= set(filter(None, element.attrs.get("authorities", "").split(";")))
    return Facts(
        package=package.group(1) if package else None,
        version_code=int(package.group(2)) if package else None,
        launchers=set(re.findall(r"^launchable-activity: name='([^']+)'", badging, re.M)),
        tiles=tiles,
        file_providers=providers,
        certificates={m.group(2).lower() for m in DIGEST.finditer(dumps[CERTS])},
    )


def reference_problems(reference: dict[str, Facts]) -> list[str]:
    """What makes the published APKs unusable as a reference: disagreeing, or read as nothing."""
    packages = {f.package for f in reference.values()}
    certificates = {c for f in reference.values() for c in f.certificates}
    shapes = {f.components() for f in reference.values()}
    if None in packages or len(packages) != 1 or len(certificates) != 1 or len(shapes) != 1 or any(
            len(f.certificates) != 1 for f in reference.values()):
        return [
            f"the published APKs disagree among themselves (packages {sorted(map(str, packages))}, certificates "
            f"{sorted(certificates)}, {len(shapes)} sets of components): nothing can be concluded against them.",
        ]
    first = next(iter(reference.values()))
    unread = [what for what, found in (("launcher activity", first.launchers), ("quick-settings tile", first.tiles),
                                       ("FileProvider", first.file_providers)) if not found]
    if unread:
        return [f"the published APKs yield no {', no '.join(unread)}: aapt2's output is no longer read as it was, and "
                "every comparison of those would pass — nothing can be concluded."]
    return []


def problems_between(new: dict[str, Dumps], published: dict[str, Dumps], verbose: bool = True) -> list[str]:
    if not new:
        return ["no new APK to check."]
    if not published:
        return ["no published APK to compare with: nothing can be concluded."]
    reference = {name: facts_of(dumps) for name, dumps in published.items()}
    unusable = reference_problems(reference)
    if unusable:
        return unusable
    first = next(iter(reference.values()))
    package, certificate = first.package, next(iter(first.certificates))
    highest_name, highest = max(((n, f.version_code or 0) for n, f in reference.items()), key=lambda item: item[1])

    problems: list[str] = []
    for name, dumps in sorted(new.items()):
        facts = facts_of(dumps)
        if verbose:
            print(f"  {name:30s} package={facts.package} versionCode={facts.version_code} "
                  f"certificate={','.join(sorted(c[:8] for c in facts.certificates)) or '-'}")
        if facts.package != package:
            problems.append(f"{name}: package {facts.package}, the published app is {package} — it would install next "
                            "to it, with an empty vault.")
        if facts.version_code is None or facts.version_code <= highest:
            problems.append(f"{name}: versionCode {facts.version_code} is not above {highest} ({highest_name}, published) "
                            "— Android would refuse the update on the phones that installed it.")
        if facts.certificates != {certificate}:
            problems.append(f"{name}: signed by {sorted(facts.certificates) or 'nobody'}, the published app by "
                            f"{certificate} — Android would refuse the update, and uninstalling destroys the vault.")
        for launcher in sorted(first.launchers - facts.launchers):
            problems.append(f"{name}: no launcher activity {launcher} — the icon pinned on the home screen would go.")
        for tile, permission in sorted(first.tiles.items()):
            if tile not in facts.tiles:
                problems.append(f"{name}: no quick-settings tile {tile} — the tile placed in the panel would go.")
            elif facts.tiles[tile] != permission:
                problems.append(f"{name}: the tile {tile} is protected by {facts.tiles[tile]}, the published one by "
                                f"{permission}.")
        for authority in sorted(first.file_providers - facts.file_providers):
            problems.append(f"{name}: no FileProvider {authority}, the authority the published app shares files under "
                            "(docs D-012).")
    return problems


def raised(published: dict[str, Dumps]) -> dict[str, Dumps]:
    """The published set as a new one would look: each versionCode raised above all of them."""
    highest = max(facts_of(d).version_code or 0 for d in published.values())
    out = {}
    for rank, (name, dumps) in enumerate(sorted(published.items())):
        out[name] = dict(dumps)
        out[name][BADGING] = re.sub(r"versionCode='\d+'", f"versionCode='{highest + 1 + rank}'", dumps[BADGING], count=1)
    return out


def with_defect(apks: dict[str, Dumps], suffix: str, change, every: bool = False) -> dict[str, Dumps]:
    """A copy of `apks` whose FIRST APK (or every one) has `change` applied to one of its dumps."""
    out = {name: dict(dumps) for name, dumps in apks.items()}
    for name in sorted(out) if every else sorted(out)[:1]:
        changed = change(out[name][suffix])
        if changed == out[name][suffix]:
            raise ValueError(f"a defect changed nothing in {name}{suffix}: its control would prove nothing.")
        out[name][suffix] = changed
    return out


def defects(new: dict[str, Dumps], published: dict[str, Dumps]) -> list[tuple[str, str, dict, dict]]:
    """Each defect: its label, what its failure must say, and the new and published sets that carry it. Every
    category is required — the baseline passing proved the reference has a launcher, a tile and a FileProvider."""
    reference = facts_of(next(iter(published.values())))
    other = "0" * 63 + "1" if reference.certificates != {"0" * 63 + "1"} else "f" * 64
    highest = max(facts_of(d).version_code or 0 for d in published.values())
    launcher = sorted(reference.launchers)[0]
    tile = sorted(reference.tiles)[0]
    authority = sorted(reference.file_providers)[0]
    cases = [
        ("an isolated .next build", "install next", with_defect(new, BADGING, lambda t: re.sub(
            r"^(package: name=')([^']+)'", r"\1\2.next'", t, count=1, flags=re.M)), published),
        ("a versionCode of 3000", "not above", with_defect(new, BADGING, lambda t: re.sub(
            r"versionCode='\d+'", "versionCode='3000'", t, count=1)), published),
        ("the versionCode of the published APK", "not above", with_defect(new, BADGING, lambda t: re.sub(
            r"versionCode='\d+'", f"versionCode='{highest}'", t, count=1)), published),
        ("another certificate", "refuse the update", with_defect(new, CERTS, lambda t: DIGEST.sub(
            rf"\g<1>{other}", t)), published),
        ("a second signer", "refuse the update", with_defect(new, CERTS, lambda t: t + (
            f"Signer #2 certificate SHA-256 digest: {other}\n")), published),
        ("an unsigned APK", "nobody", with_defect(new, CERTS, lambda t: DIGEST.sub("", t)), published),
        ("the launcher activity renamed", "launcher activity", with_defect(new, BADGING, lambda t: t.replace(
            f"launchable-activity: name='{launcher}'", f"launchable-activity: name='{launcher}Renamed'")), published),
        ("a tile renamed", "quick-settings tile", with_defect(new, MANIFEST, lambda t: t.replace(
            f'="{tile}"', f'="{tile}Renamed"')), published),
        ("a tile left unprotected", "protected by", with_defect(new, MANIFEST, lambda t: t.replace(
            '="android.permission.BIND_QUICK_SETTINGS_TILE"', '="android.permission.NONE"')), published),
        ("the FileProvider of a .next build", "FileProvider", with_defect(new, MANIFEST, lambda t: t.replace(
            f'="{authority}"', f'="{authority.replace(".fileprovider", ".next.fileprovider")}"')), published),
        # The reference itself: read as nothing, it would let anything pass.
        ("published tiles no longer read", "yield no", new, with_defect(published, MANIFEST, lambda t: t.replace(
            QS_TILE, "android.intent.action.UNREAD"), every=True)),
    ]
    if len(published) > 1:
        cases.append(("published APKs that disagree on the key", "disagree", new, with_defect(
            published, CERTS, lambda t: DIGEST.sub(rf"\g<1>{other}", t))))
        cases.append(("published APKs that disagree on a tile", "disagree", new, with_defect(
            published, MANIFEST, lambda t: t.replace(f'="{tile}"', f'="{tile}Renamed"'))))
    return cases


def negative_control(new: dict[str, Dumps], published: dict[str, Dumps]) -> int:
    baseline = problems_between(new, published)
    if baseline:
        fail(["the real APKs do not pass, so no defect can be told apart:", *baseline])
        return 1
    try:
        cases = defects(new, published)
    except ValueError as error:
        fail([str(error)])
        return 1
    caught = 0
    for label, reason, broken_new, broken_published in cases:
        found = problems_between(broken_new, broken_published, verbose=False)
        ok = any(reason in problem for problem in found)
        caught += ok
        print(f"  {label:45s} {'caught' if ok else 'NOT CAUGHT'}" + ("" if ok else f" — got: {found}"))
    if caught != len(cases):
        print()
        fail([f"NEGATIVE CONTROL: {caught}/{len(cases)} — a defect passes the continuity check."])
        return 1
    print(f"NEGATIVE CONTROL: {caught}/{len(cases)} -- every defect is detected, and for the right reason.")
    return 0


def main(argv: list[str]) -> int:
    args = argv[1:]
    from_dumps = "--from-dumps" in args
    mode = next((a for a in args if a in ("--negative-control", "--self-test")), None)
    args = [a for a in args if a not in ("--from-dumps", "--negative-control", "--self-test")]
    expected = 1 if mode == "--self-test" else 2
    if len(args) != expected or any(a.startswith("--") for a in args):
        print(__doc__)
        return 2
    for directory in args:
        if not os.path.isdir(directory):
            fail([f"directory not found: {directory}"])
            return 1
    try:
        published = read_set(args[-1], from_dumps)
        new = raised(published) if mode == "--self-test" else read_set(args[0], from_dumps)
    except (OSError, RuntimeError, FileNotFoundError, ValueError) as error:
        fail([str(error)])
        return 1
    print(f"published: {', '.join(sorted(published)) or '-'}")
    if not published:
        fail([f"no published APK in {args[-1]}: nothing can be concluded."])
        return 1
    if mode:
        return negative_control(new, published)
    problems = problems_between(new, published)
    if problems:
        print()
        fail(problems)
        return 1
    facts = facts_of(next(iter(published.values())))
    print()
    print(f"OK: {len(new)} APK(s) continue the published release — package {facts.package}, certificate "
          f"{next(iter(facts.certificates))[:16]}…, the launcher activity, {len(facts.tiles)} tiles and the FileProvider "
          "kept, every versionCode above the published ones.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
