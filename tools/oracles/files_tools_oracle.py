"""Independent references for Create a ZIP, Duplicates, Content search and Bulk rename (phase 5).

Usage:
  python -I tools/oracles/files_tools_oracle.py run       prepare, run FilesToolsOracleTest (Gradle), check
  python -I tools/oracles/files_tools_oracle.py prepare   writes the input folders under app/build/oracles/files/input/
  python -I tools/oracles/files_tools_oracle.py check     reads what FilesToolsOracleTest left in app/build/oracles/files/out/

`prepare` makes, from a fixed seed, never from the app's code:
- dup/: identical files across folders, files of one size but different (same first and last 64 KiB, a
  different middle), empty files, small identical files (under the 4 KiB threshold), copies in the trash
  (.RFT_Corbeille, .RFT_Corbeille.next), in a hidden folder, in Android/data and in a cache folder;
- search/: texts in UTF-8, UTF-8 with a BOM and Windows-1252, in sub-folders and a hidden one, of the 19
  extensions and of others, one over 2 MiB, one in the trash; search-queries.json;
- zip/: files of one name in several folders (case apart), an empty file, a Unicode name, random bytes;
- rename-cases.json: names and rules of the bulk rename.

`check`:
- duplicates.json: the groups `sha256sum` makes of dup/ under the finder's documented rules (4 KiB and more;
  hidden entries, the trash, Android/data|obb and `cache` folders left out) — equal, group for group;
- search.json: per query, the files `grep -r` finds in search/ (the 19 extensions, the trash excluded; the
  word given in UTF-8 AND in Windows-1252 bytes; files over 2 MiB dropped, the app counts them apart) — equal;
- archive.zip: 7-Zip lists and tests it, Python's zipfile tests it, every member's SHA-256 is its source's
  (`sha256sum`), the names are the manifest's and unique case ignored;
- rename.json: the names Python's `re` and the documented rules give, and the same names refused.
"""
import hashlib
import json
import os
import pathlib
import random
import re
import shutil
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
BASE = ROOT / "app" / "build" / "oracles" / "files"
INPUT = BASE / "input"
OUT = BASE / "out"
MIB = 1024 * 1024
MIN_BYTES = 4096
SEARCH_CAP = 2 * MIB
TEXT_EXTENSIONS = ["txt", "md", "csv", "xml", "json", "html", "htm", "css", "js", "php", "dart", "ts", "yaml", "yml", "ini",
                   "conf", "log", "sh", "bat"]
TRASH = [".RFT_Corbeille", ".RFT_Corbeille.next"]
BACKSLASH = chr(92)


def write(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data if isinstance(data, bytes) else data.encode("utf-8"))


def prepare():
    if INPUT.exists():
        shutil.rmtree(INPUT)
    rng = random.Random(20261014)

    def noise(n):
        return bytes(rng.getrandbits(8) for _ in range(n))

    dup = INPUT / "dup"
    photo = noise(50_000)
    for path in ["DCIM/photo.jpg", "Download/photo (1).jpg", "Backup/2026/photo.jpg"]:
        write(dup / path, photo)
    video = noise(300_000)
    write(dup / "Movies/clip.mp4", video)
    write(dup / "Movies/old/clip.mp4", video)
    head, tail = noise(70_000), noise(70_000)
    write(dup / "same-size/a.bin", head + b"AAAA" + tail)
    write(dup / "same-size/b.bin", head + b"BBBB" + tail)
    write(dup / "same-size/c.bin", head + b"AAAA" + tail)
    write(dup / "empty1.txt", b"")
    write(dup / "empty2.txt", b"")
    write(dup / "small1.txt", b"small identical")
    write(dup / "small2.txt", b"small identical")
    unique = noise(10_000)
    write(dup / "Documents/Straße é.pdf", unique)
    for trash in TRASH:
        write(dup / trash / "items/1_2/Straße é.pdf", unique)
    write(dup / ".hidden/Straße é.pdf", unique)
    write(dup / "Android/data/com.app/Straße é.pdf", unique)
    write(dup / "app/cache/Straße é.pdf", unique)

    search = INPUT / "search"
    write(search / "notes.txt", "Le contrat signé le 2026-10-14.\nautre ligne\n")
    write(search / "latin/lettre.txt", "Contrat signé\r\nà Paris\r\n".encode("windows-1252"))
    write(search / "latin/table.csv", "nom;statut\nDupont;signé\n".encode("windows-1252"))
    write(search / "bom/readme.md", bytes([0xEF, 0xBB, 0xBF]) + "# CONTRAT\n\nsigné\n".encode("utf-8"))
    write(search / ".config/app.ini", "[contrat]\ndate=2026-01-02\n")
    write(search / "deep/a/b/c/script.sh", "echo contrat\n")
    write(search / "deep/run.bat", "rem nothing here\n")
    write(search / "web/page.html", "<p>CONTRAT</p>")
    write(search / "data.bin", "contrat signé 2026-10-14")
    write(search / "photo.png", "contrat")
    write(search / "big.log", "contrat\n" + "x" * (SEARCH_CAP + 10))
    for trash in TRASH:
        write(search / trash / "items/1_2/old.txt", "contrat signé")
    queries = [
        {"id": "word-ci", "text": "contrat", "matchCase": False, "regex": False},
        {"id": "word-cs", "text": "CONTRAT", "matchCase": True, "regex": False},
        {"id": "accent-cs", "text": "signé", "matchCase": True, "regex": False},
        {"id": "regex-date", "text": BACKSLASH + "d{4}-" + BACKSLASH + "d{2}-" + BACKSLASH + "d{2}", "matchCase": True, "regex": True},
    ]
    write(INPUT / "search-queries.json", json.dumps(queries, ensure_ascii=False, indent=1))

    zipped = INPUT / "zip"
    write(zipped / "a/report.pdf", noise(300_000))
    write(zipped / "b/report.pdf", noise(1000))
    write(zipped / "c/Report.PDF", b"third")
    write(zipped / "empty.txt", b"")
    write(zipped / "Straße é.txt", "unicode".encode("utf-8"))
    write(zipped / "compressible.log", b"line of a log\n" * 70_000)

    digit = BACKSLASH + "d"
    word = BACKSLASH + "w"
    cases = [
        {"mode": "numbering", "base": "Photo", "start": 1, "digits": 3, "keepExtension": True,
         "names": ["IMG_3.jpg", "IMG_1.JPG", "scan.pdf", ".bashrc", "notes."]},
        {"mode": "numbering", "base": "Vacances", "start": 98, "digits": 0, "keepExtension": False, "names": ["a.jpg", "b.jpg", "c.jpg"]},
        {"mode": "affixes", "prefix": "2026-", "suffix": "_v2", "keepExtension": True, "names": ["archive.tar.gz", "photo.jpg", "README"]},
        {"mode": "replace", "pattern": "IMG_(" + digit + "+)", "replacement": "Photo_$1", "keepExtension": True,
         "names": ["IMG_0042.jpg", "IMG_7.JPG", "other.png"]},
        {"mode": "replace", "pattern": "(" + word + "+)-(" + word + "+)", "replacement": "$2-$1", "keepExtension": True,
         "names": ["alpha-beta.txt", "one-two.md"]},
        {"mode": "replace", "pattern": "^[ab]$", "replacement": "same", "keepExtension": True, "names": ["a.txt", "b.TXT", "c.txt"]},
        {"mode": "numbering", "base": "Photo", "start": 1, "digits": 0, "keepExtension": True, "names": ["Photo_2.jpg", "x.jpg"]},
        {"mode": "affixes", "prefix": "é" * 126, "suffix": "", "keepExtension": True, "names": ["a.txt", "b"]},
        {"mode": "replace", "pattern": "a", "replacement": "x/y", "keepExtension": True, "names": ["a.txt"]},
    ]
    write(INPUT / "rename-cases.json", json.dumps(cases, ensure_ascii=False, indent=1))
    write(INPUT / "ready", "")
    print("input written:", INPUT)


def relative(root, path):
    return path.relative_to(root).as_posix()


def sha256sum(paths):
    """The digests `sha256sum` (coreutils) gives, by path."""
    if not paths:
        return {}
    run = subprocess.run(["sha256sum", "--"] + [p.as_posix() for p in paths], capture_output=True, check=True)
    digests = {}
    for path, line in zip(paths, run.stdout.decode("utf-8", "replace").splitlines()):
        digests[path] = line.split()[0].lstrip(BACKSLASH)
    return digests


def check_duplicates(failures):
    root = INPUT / "dup"
    listed = []
    for folder, dirs, files in os.walk(root):
        here = pathlib.Path(folder)
        kept = []
        for d in dirs:
            skipped = d.startswith(".") or d in TRASH or d == "cache" or (d in ("data", "obb") and here.name == "Android")
            if not skipped:
                kept.append(d)
        dirs[:] = kept
        listed += [here / f for f in files if not f.startswith(".") and (here / f).stat().st_size >= MIN_BYTES]
    groups = {}
    for path, digest in sha256sum(listed).items():
        groups.setdefault(digest, []).append(relative(root, path))
    expected = sorted(sorted(g) for g in groups.values() if len(g) >= 2)
    found = sorted(sorted(g) for g in json.loads((OUT / "duplicates.json").read_text(encoding="utf-8")))
    if found != expected:
        failures.append("duplicates: app %s, sha256sum %s" % (found, expected))
    print("duplicates: %d groups, %d files listed by the oracle" % (len(expected), len(listed)))


def ascii_folded(pattern, extended):
    """[pattern] matching its ASCII letters in either case, as an ERE: the grep 3.0 of the Windows shell aborts on -i."""
    out = bytearray()
    for byte in pattern:
        char = chr(byte)
        if char.isascii() and char.isalpha():
            out += ("[" + char.lower() + char.upper() + "]").encode("ascii")
        elif not extended and char in ".[]()*+?{}|^$" + BACKSLASH:
            out += (BACKSLASH + char).encode("ascii")
        else:
            out.append(byte)
    return bytes(out)


def grep(root, patterns, ignore_case, extended):
    if ignore_case:
        patterns = [ascii_folded(p, extended) for p in patterns]
        extended = True
    pattern_file = BASE / "grep-patterns.bin"
    pattern_file.write_bytes(b"\n".join(patterns) + b"\n")
    command = ["grep", "-r", "-l", "-E" if extended else "-F", "-f", pattern_file.as_posix()]
    command += ["--include=*." + ext for ext in TEXT_EXTENSIONS]
    command += ["--exclude-dir=" + name for name in TRASH]
    command.append(root.as_posix())
    run = subprocess.run(command, capture_output=True, env=dict(os.environ, LC_ALL="C"))
    if run.returncode not in (0, 1):
        raise RuntimeError("grep failed: " + run.stderr.decode("utf-8", "replace"))
    files = [pathlib.Path(line) for line in run.stdout.decode("utf-8").splitlines() if line]
    return sorted(relative(root, f.resolve()) for f in files if f.stat().st_size <= SEARCH_CAP)


def check_search(failures):
    root = (INPUT / "search").resolve()
    found = json.loads((OUT / "search.json").read_text(encoding="utf-8"))
    for query in json.loads((INPUT / "search-queries.json").read_text(encoding="utf-8")):
        text = query["text"]
        if query["regex"]:
            # RE2's \d is ASCII [0-9], as grep's [0-9] under LC_ALL=C.
            patterns = [text.replace(BACKSLASH + "d", "[0-9]").encode("ascii")]
        else:
            # The word as a UTF-8 text holds it, and as a Windows-1252 one does.
            patterns = sorted({text.encode("utf-8"), text.encode("windows-1252")})
        expected = grep(root, patterns, ignore_case=not query["matchCase"], extended=query["regex"])
        if sorted(found[query["id"]]) != expected:
            failures.append("search %s: app %s, grep %s" % (query["id"], sorted(found[query["id"]]), expected))
        print("search %-10s %s" % (query["id"], expected))


def seven_zip():
    return shutil.which("7z") or r"C:/Program Files/7-Zip/7z.exe"


def check_zip(failures):
    import zipfile
    archive = OUT / "archive.zip"
    manifest = json.loads((OUT / "zip-manifest.json").read_text(encoding="utf-8"))
    test = subprocess.run([seven_zip(), "t", str(archive)], capture_output=True)
    if test.returncode != 0 or b"Everything is Ok" not in test.stdout:
        failures.append("7-Zip test failed: " + test.stdout.decode("utf-8", "replace")[-300:])
    listing = subprocess.run([seven_zip(), "l", "-slt", "-sccUTF-8", str(archive)], capture_output=True)
    listed = [line[len("Path = "):] for line in listing.stdout.decode("utf-8", "replace").splitlines() if line.startswith("Path = ")][1:]
    if sorted(listed) != sorted(manifest):
        failures.append("7-Zip lists %s, the manifest %s" % (sorted(listed), sorted(manifest)))
    with zipfile.ZipFile(archive) as z:
        if z.testzip() is not None:
            failures.append("zipfile: a bad member: " + z.testzip())
        names = z.namelist()
        if len({n.lower() for n in names}) != len(names):
            failures.append("zip: two names equal but for case: %s" % names)
        sources = {name: INPUT / "zip" / source for name, source in manifest.items()}
        expected = sha256sum(list(sources.values()))
        for name, source in sources.items():
            member = hashlib.sha256(z.read(name)).hexdigest()
            if member != expected[source]:
                failures.append("zip: %s differs from %s" % (name, source))
    print("zip: %d members, 7-Zip and zipfile tested, each equal to its source" % len(manifest))


def expected_names(case):
    names = case["names"]
    out = []
    for index, name in enumerate(names):
        if case["mode"] == "replace" and not case["pattern"]:
            out.append(name)
            continue
        dot = name.rfind(".")
        has_ext = 0 < dot < len(name) - 1
        stem = name[:dot] if has_ext else name
        ext = name[dot:] if has_ext and case["keepExtension"] else ""
        if case["mode"] == "numbering":
            stem = "%s_%s" % (case["base"], str(case["start"] + index).zfill(case["digits"]))
        elif case["mode"] == "affixes":
            stem = case["prefix"] + stem + case["suffix"]
        else:
            replacement = re.sub(r"\$(\d)", lambda m: BACKSLASH + "g<" + m.group(1) + ">", case["replacement"])
            stem = re.sub(case["pattern"], replacement, stem)
        out.append(stem + ext)
    counts = {}
    for n in out:
        counts[n.lower()] = counts.get(n.lower(), 0) + 1
    existing = {n.lower() for n in names}
    results = []
    for old, new in zip(names, out):
        if not new or new in (".", "..") or any(c in "/" + BACKSLASH or ord(c) < 32 for c in new):
            problem = "INVALID"
        elif len(new.encode("utf-8")) > 255:
            problem = "TOO_LONG"
        elif counts[new.lower()] > 1:
            problem = "DUPLICATE"
        elif new.lower() != old.lower() and new.lower() in existing:
            problem = "TAKEN"
        else:
            problem = None
        results.append({"name": new, "problem": problem})
    return results


def check_rename(failures):
    cases = json.loads((INPUT / "rename-cases.json").read_text(encoding="utf-8"))
    found = json.loads((OUT / "rename.json").read_text(encoding="utf-8"))
    for case, app in zip(cases, found):
        expected = expected_names(case)
        if app != expected:
            failures.append("rename %s: app %s, Python %s" % (case["names"], app, expected))
    refused = sum(1 for case in cases for r in expected_names(case) if r["problem"])
    print("rename: %d cases, %d names refused, as Python's re and the rules say" % (len(cases), refused))


def check():
    failures = []
    check_duplicates(failures)
    check_search(failures)
    check_zip(failures)
    check_rename(failures)
    for failure in failures:
        print("FAILED:", failure)
    print("OK" if not failures else "%d FAILURES" % len(failures))
    return 0 if not failures else 1


def main():
    command = sys.argv[1] if len(sys.argv) > 1 else "run"
    if command in ("prepare", "run"):
        prepare()
    if command == "run":
        if (OUT).exists():
            shutil.rmtree(OUT)
        gradle = subprocess.run(["sh", "gradlew", ":app:testDebugUnitTest", "--tests", "*FilesToolsOracleTest", "-q"], cwd=ROOT)
        if gradle.returncode != 0:
            sys.exit(gradle.returncode)
    if command in ("check", "run"):
        sys.exit(check())


if __name__ == "__main__":
    main()
