"""Oracle of the code editor (phase 5): checked with tools independent of the app.

Each text of `tools/corpus/editor/` is written by Python's own codecs, in the encoding, byte order mark
and line breaks listed in CASES. The app's side, the JVM test `CodeEditorOracleTest`, opens each through
the editor's real path (`EditedFile`), saves it WITHOUT a change, then with its second line changed,
into `app/build/editor-oracle/`. This script then checks, with its own tools:
  1. saved without a change, the file is the same file: `sha256sum` of both equal;
  2. saved with one line changed, Python's `difflib` on the lines decoded by the codec the file was
     MADE with (not the app's detection) finds that line only, with its own line break;
  3. each history copy is the file as it was before that save (`sha256sum`).

Usage (from the repository root):
  python -I tools/oracles/editor_oracle.py --make-corpus   writes the corpus (once; it is committed)
  python -I tools/oracles/editor_oracle.py [--skip-build]  runs the JVM side, then checks
"""
import difflib
import hashlib
import os
import shutil
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
CORPUS = os.path.join(ROOT, "tools", "corpus", "editor")
OUT = os.path.join(ROOT, "app", "build", "editor-oracle")
BOM = chr(0xFEFF)
CR = chr(13)
LF = chr(10)
CRLF = CR + LF

# The second line becomes this (CodeEditorOracleTest.VALUE): Windows-1252 has every character of it.
VALUE = "Ligne modifiée — 1 €"

# name -> (Python codec, text). A BOM is the text's first character, written by the codec itself.
CASES = {
    "utf8-lf.txt": ("utf-8", "Première ligne" + LF + "Deuxième — 20 €" + LF + "Troisième" + LF),
    "utf8-bom-crlf.md": ("utf-8", BOM + "# Titre" + CRLF + CRLF + "Café, thé, œuvre" + CRLF + "Fin" + CRLF),
    "utf16le-bom-crlf.txt": ("utf-16-le", BOM + "Ligne un" + CRLF + "Ligne deux — ü" + CRLF + "Ligne trois" + CRLF),
    "utf16be-bom-lf.json": ("utf-16-be", BOM + "{" + LF + '  "nom": "Zoë",' + LF + '  "emoji": "\U0001F600"' + LF + "}" + LF),
    "cp1252-crlf.csv": ("cp1252", "nom;prix" + CRLF + "Œuvre;12 €" + CRLF + "L’été;3" + CRLF),
    "cp1252-cr.txt": ("cp1252", "Première" + CR + "Deuxième" + CR + "Troisième" + CR),
    "utf8-no-final-break.txt": ("utf-8", "Un" + CRLF + "Deux" + CRLF + "Trois"),
    "utf8-mixed-breaks.txt": ("utf-8", "LF line" + LF + "CRLF line" + CRLF + "CR line" + CR + "last LF" + LF),
    "utf8-emoji-lf.md": ("utf-8", "\U0001F600 un" + LF + "漢字 deux" + LF + "three" + LF),
    "ascii-lf.conf": ("utf-8", "key=value" + LF + "other=1" + LF + "last=2" + LF),
}


def make_corpus():
    sys.path.insert(0, r"C:/Users/Pat/.claude/tools")
    from ecrire_atomique import remplacer  # writes text.encode(codec), the bytes exactly

    os.makedirs(CORPUS, exist_ok=True)
    for name, (codec, text) in CASES.items():
        remplacer(os.path.join(CORPUS, name), text, encodage=codec)
    return 0


def sha256(path):
    """`sha256sum` when the shell has it, hashlib otherwise (the same digest)."""
    tool = shutil.which("sha256sum")
    if tool:
        return subprocess.run([tool, path], capture_output=True, text=True, check=True).stdout.split()[0].lstrip(chr(92))
    with open(path, "rb") as handle:
        return hashlib.sha256(handle.read()).hexdigest()


def lines(path, codec):
    """The file's lines, each with its line break, decoded by the codec it was made with."""
    with open(path, "rb") as handle:
        return handle.read().decode(codec).splitlines(keepends=True)


def line_verdict(source, changed, codec):
    """Exactly the second line replaced by VALUE, its line break kept; every other line equal."""
    before, after = lines(source, codec), lines(changed, codec)
    ops = [op for op in difflib.SequenceMatcher(None, before, after, autojunk=False).get_opcodes() if op[0] != "equal"]
    if ops != [("replace", 1, 2, 1, 2)]:
        return "LINES DIFFER ELSEWHERE %s" % ops
    old = before[1]
    ending = old[len(old.rstrip(CRLF)):]
    if after[1] != VALUE + ending:
        return "LINE 2 IS %r, NOT %r" % (after[1], VALUE + ending)
    return "line 2 only, its line break %r kept" % ending


def main():
    if "--make-corpus" in sys.argv:
        return make_corpus()
    if "--skip-build" not in sys.argv:
        # --rerun: a test task served from the build cache would leave the files of an earlier run here.
        command = ["sh", "gradlew", ":app:testDebugUnitTest", "--rerun", "--tests", "*CodeEditorOracleTest", "-q"]
        build = subprocess.run(command, cwd=ROOT, capture_output=True, text=True, encoding="utf-8", errors="replace")
        if build.returncode != 0:
            print("BUILD FAILED" + LF + (build.stdout + build.stderr)[-2000:])
            return 2
    failures = 0
    for name, (codec, _) in sorted(CASES.items()):
        source = os.path.join(CORPUS, name)
        out = os.path.join(OUT, name)
        verdicts = [
            "sha256 equal" if sha256(source) == sha256(out + ".same") else "SHA256 DIFFERS",
            "history 1 = file before" if sha256(source) == sha256(out + ".history1") else "HISTORY 1 DIFFERS",
            "history 2 = file before" if sha256(out + ".same") == sha256(out + ".history2") else "HISTORY 2 DIFFERS",
            line_verdict(source, out + ".line", codec),
        ]
        ok = all(v[0].islower() for v in verdicts)
        failures += 0 if ok else 1
        print("%-26s %s" % (name, "; ".join(verdicts)))
    print("%d files, %d failed" % (len(CASES), failures))
    return 1 if failures else 0


sys.stdout.reconfigure(errors="replace")
sys.exit(main())
