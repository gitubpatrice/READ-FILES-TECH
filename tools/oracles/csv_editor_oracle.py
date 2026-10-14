"""Oracle of the CSV editor (phase 5): checked with tools independent of the app.

For each CSV made by real software (Excel through COM, PowerShell, Python's `csv`):
  1. saved WITHOUT a change, it is the same file: `sha256sum` of both equal;
  2. saved with ONE cell changed, Python's `csv` module reads the same table with that cell changed, and
     the bytes differ only around that cell (common prefix and suffix cover the rest).
The app's side writes the files: the JVM test `CsvEditsOracleTest`, run first by this script.

Usage (from the repository root): python -I tools/oracles/csv_editor_oracle.py [--skip-build]
"""
import csv
import hashlib
import io
import os
import shutil
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OUT = os.path.join(ROOT, "app", "build", "csv-editor-oracle")
FOLDERS = [os.path.join(ROOT, "tools", "corpus", "csv"), os.path.join(ROOT, "tools", "corpus", "csv-editor")]
VALUE = 'Édité; x, "q"'

# The separator each file was written with, known from how it was made — not from the app's detection.
SEPARATORS = {
    "excel-csv-1252.csv": ";", "excel-csv-utf8.csv": ";", "ps-default-culture.csv": ";",
    "ps-unicode-culture-type.csv": ";", "ps-utf8-comma.csv": ",",
    "excel-mac-cr.csv": ";", "excel-msdos.csv": ";", "excel-unicode-tab.csv": "\t", "excel-utf8-local.csv": ";",
    "excel-utf8-no-final-break.csv": ";", "excel-xlcsv-comma.csv": ",", "excel-xlcsv-local.csv": ";",
    "python-lf.csv": ",", "python-quote-all-semicolon.csv": ";", "python-sep-blank-lines.csv": ",",
}


def sha256(path):
    """`sha256sum` when the shell has it, hashlib otherwise (the same digest)."""
    tool = shutil.which("sha256sum")
    if tool:
        return subprocess.run([tool, path], capture_output=True, text=True, check=True).stdout.split()[0].lstrip("\\")
    with open(path, "rb") as handle:
        return hashlib.sha256(handle.read()).hexdigest()


def decode(data):
    """As a reader of CSV text would: a byte order mark says it; else UTF-8 when valid; else Windows-1252."""
    if data.startswith(b"\xef\xbb\xbf"):
        return data[3:].decode("utf-8"), "utf-8"
    if data.startswith(b"\xff\xfe"):
        return data[2:].decode("utf-16-le"), "utf-16-le"
    if data.startswith(b"\xfe\xff"):
        return data[2:].decode("utf-16-be"), "utf-16-be"
    try:
        return data.decode("utf-8"), "utf-8"
    except UnicodeDecodeError:
        return data.decode("cp1252", errors="replace"), "cp1252"


def rows(data, separator):
    """The rows with content, as a spreadsheet shows them: no `sep=` line, no row of empty fields."""
    text, _ = decode(data)
    read = list(csv.reader(io.StringIO(text, newline=""), delimiter=separator))
    if text.startswith("sep="):  # Excel's hint: "sep=," read with "," is two fields
        read = read[1:]
    return [row for row in read if any(field != "" for field in row)]


def changed_span(before, after):
    """Bytes of `before` and of `after` outside their common prefix and suffix."""
    prefix = 0
    while prefix < min(len(before), len(after)) and before[prefix] == after[prefix]:
        prefix += 1
    suffix = 0
    while suffix < min(len(before), len(after)) - prefix and before[-1 - suffix] == after[-1 - suffix]:
        suffix += 1
    return len(before) - prefix - suffix, len(after) - prefix - suffix


def main():
    if "--skip-build" not in sys.argv:
        # --rerun: a test task served from the build cache would leave the files of an earlier run here.
        command = ["sh", "gradlew", ":app:testDebugUnitTest", "--rerun", "--tests", "*CsvEditsOracleTest", "-q"]
        build = subprocess.run(command, cwd=ROOT, capture_output=True, text=True, encoding="utf-8", errors="replace")
        if build.returncode != 0:
            print("BUILD FAILED\n" + (build.stdout + build.stderr)[-2000:])
            return 2
    failures = 0
    with open(os.path.join(OUT, "manifest.tsv"), encoding="utf-8") as handle:
        entries = [line.rstrip("\n").split("\t") for line in handle if line.strip()]
    for name, position, column, change in entries:
        source = next(os.path.join(folder, name) for folder in FOLDERS if os.path.isfile(os.path.join(folder, name)))
        same = os.path.join(OUT, name + ".same")
        verdicts = ["sha256 equal" if sha256(source) == sha256(same) else "SHA256 DIFFERS"]
        if change == "CHANGED":
            before = open(source, "rb").read()
            after = open(os.path.join(OUT, name + ".cell"), "rb").read()
            separator = SEPARATORS[name]
            expected = rows(before, separator)
            p, c = int(position), int(column)
            old = expected[p][c] if c < len(expected[p]) else ""
            expected[p] = expected[p] + [""] * (c + 1 - len(expected[p]))
            expected[p][c] = VALUE
            verdicts.append("table equal but that cell" if rows(after, separator) == expected else "TABLE DIFFERS")
            _, encoding = decode(before)
            span_before, span_after = changed_span(before, after)
            # A field's bytes at most: its text encoded, each quote doubled, two quotes around.
            bound_before = 2 * len(old.encode(encoding, errors="replace")) + 2
            bound_after = 2 * len(VALUE.encode(encoding)) + 2
            local = span_before <= bound_before and span_after <= bound_after
            verdicts.append("bytes differ in the cell only (%d -> %d)" % (span_before, span_after) if local else
                            "BYTES DIFFER ELSEWHERE (%d > %d or %d > %d)" % (span_before, bound_before, span_after, bound_after))
        ok = all(v[0].islower() for v in verdicts)
        failures += 0 if ok else 1
        print("%-34s %s" % (name, "; ".join(verdicts)))
    print("%d files, %d failed" % (len(entries), failures))
    return 1 if failures else 0


sys.stdout.reconfigure(errors="replace")
sys.exit(main())
