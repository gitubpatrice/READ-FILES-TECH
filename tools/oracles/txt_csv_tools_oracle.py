"""Independent references for the text tools and the CSV tools (phase 5).

Usage:
  python -I tools/oracles/txt_csv_tools_oracle.py run                 prepare, run TxtCsvToolsOracleTest (Gradle), check
  python -I tools/oracles/txt_csv_tools_oracle.py prepare             writes the corpus under app/build/oracles/txt-csv/input/
  python -I tools/oracles/txt_csv_tools_oracle.py check               reads what TxtCsvToolsOracleTest left in .../out/
  python -I tools/oracles/txt_csv_tools_oracle.py check-device <dir>  checks the PDFs the phone made of that corpus

`prepare` writes, never from the app's code:
- texts/: the same kind of text in UTF-8, UTF-8 with a BOM, UTF-16 LE and BE with their BOM, Windows-1252; line
  breaks LF, CRLF and a lone CR; accents, an emoji, CJK, Python's white space (no-break space, U+2028, U+001C,
  U+0085) and what is not (U+200B, U+FEFF inside the text); a text of several of the tools' pieces of 32 768
  characters with a match across their boundary; an empty one; texts.json (each file's codec and BOM) and
  replace-cases.json (a query and its replacement on a file);
- csv/: CSV files in the dialects the viewer reads (`,` `;` TAB `|`, an Excel `sep=` line, UTF-8 with and without
  a BOM, Windows-1252, UTF-16 LE), quoted cells holding the separator, quotes and line breaks, cells `-5` `+33…`
  `=1+1`, rows of empty fields; merges.json (the files of each merge, in order).

`check`:
- stats.json: per text, Python's `len(text)`, `len(text.split())` and the 2.15.3's lines (`text.count("\\n") + 1`,
  0 for an empty text), the text decoded by Python's own codec — equal;
- counts.json: per case, `text.count(query)` — equal;
- replace/<case>.<ext>: the source's BOM, then `text.replace(query, replacement)` encoded by Python's codec —
  equal to the byte;
- merge/<merge>.csv: read back by Python's `csv` (its BOM apart, the first file's separator), equal to the rows
  Python's `csv` reads in the inputs — the first file's header once, the rows of empty fields skipped, a file
  whose header names the same columns in another order put back in the first file's order —; a BOM unless the
  first file is UTF-8 without one; CRLF after each row.

`check-device <dir>`: the PDFs the phone made of this corpus (Text tools' "Convert to PDF" of input/texts/*,
CSV tools' "Export to PDF" of input/csv/*), named <source>_<stamp>.pdf as the app names them:
- a text's: what pdftotext AND pypdf extract from all its pages equal to the source's, white space apart (the
  lines are wrapped);
- a table's: every cell found in what pdftotext AND pypdf extract — whole, or a beginning of it followed by an
  ellipsis (a cell is cut to its column) —, white space apart, and a cell of the header on every page.
"""
import csv
import io
import json
import pathlib
import re
import shutil
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
BASE = ROOT / "app" / "build" / "oracles" / "txt-csv"
INPUT = BASE / "input"
OUT = BASE / "out"
PIECE = 32 * 1024
BOMS = {"utf-8": b"\xef\xbb\xbf", "utf-16-le": b"\xff\xfe", "utf-16-be": b"\xfe\xff"}
WHITE = re.compile(r"\s+")
CONTROL = re.compile(r"[\x00-\x1f]")
ELLIPSIS = "…"
STAMP = re.compile(r"_\d{8}_\d{6}(_\d+)?$")

sys.stdout.reconfigure(encoding="utf-8", errors="replace")
failures = []


def fail(message):
    failures.append(message)
    print("ECHEC", message)


def write(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)


SAMPLE = ("Été à Noël : l'œuvre coûte 12 € — naïve façade\r\n"
          "second\u00A0line\u2028third\u001cfourth\u0085fifth\u200Bstill\uFEFFone\n"
          "CJK 漢字かな 한글, emoji 😀👍🏽 and a lone CR\rafter it\n"
          "\tindented\ttabs   and   spaces\n")
LATIN = "Été à Noël : l'œuvre coûte 12 € — naïve façade\r\nfin de ligne\r\nPrix : 10 €\r\n"


def prepare():
    if INPUT.exists():
        shutil.rmtree(INPUT)
    texts = {
        "utf8.txt": ("utf-8", False, SAMPLE),
        "utf8-bom.md": ("utf-8", True, "# Title\n\n" + SAMPLE),
        "utf16le.txt": ("utf-16-le", True, SAMPLE),
        "utf16be.txt": ("utf-16-be", True, SAMPLE),
        "cp1252.txt": ("cp1252", False, LATIN),
        "long.txt": ("utf-8", False, ("word " * (PIECE // 5)) + "nee" + "dle" + " tail " + "x" * (PIECE - 3) + "needle\n" * 3),
        "empty.txt": ("utf-8", False, ""),
        "no-final-newline.txt": ("utf-8", False, "one two\nthree"),
    }
    manifest = {}
    for name, (codec, bom, text) in texts.items():
        write(INPUT / "texts" / name, (BOMS[codec] if bom else b"") + text.encode(codec))
        manifest[name] = {"codec": codec, "bom": bom}
    write(INPUT / "texts.json", json.dumps(manifest, indent=1).encode())
    cases = [
        {"id": "utf8-accent", "file": "utf8.txt", "query": "é", "replacement": "e"},
        {"id": "utf8-crlf", "file": "utf8.txt", "query": "\r\n", "replacement": "\n"},
        {"id": "utf8-emoji", "file": "utf8.txt", "query": "😀", "replacement": "☺ smile"},
        {"id": "bom-title", "file": "utf8-bom.md", "query": "line", "replacement": "LIGNE"},
        {"id": "utf16le-cjk", "file": "utf16le.txt", "query": "漢字", "replacement": "kanji 😀"},
        {"id": "utf16be-word", "file": "utf16be.txt", "query": "second", "replacement": "2nd"},
        {"id": "cp1252-euro", "file": "cp1252.txt", "query": "€", "replacement": "euros"},
        {"id": "cp1252-accent", "file": "cp1252.txt", "query": "Prix", "replacement": "Coût"},
        {"id": "long-boundary", "file": "long.txt", "query": "needle", "replacement": "pin"},
        {"id": "long-words", "file": "long.txt", "query": "word ", "replacement": ""},
        {"id": "no-final", "file": "no-final-newline.txt", "query": "three", "replacement": "3"},
    ]
    write(INPUT / "replace-cases.json", json.dumps(cases, ensure_ascii=False, indent=1).encode("utf-8"))

    csvs = {
        "a.csv": ("utf-8", False, ",", False, 'name,qty,note\r\napple,-5,"red, green"\r\npear,+33 6 12,"say ""hi"""\r\n'
                                             'plum,=1+1,"two\r\nlines"\r\n,,\r\n\r\n'),
        "b.csv": ("utf-8", True, ",", False, "name,qty,note\nfig,7,@SUM\n"),
        "fr.csv": ("cp1252", False, ";", True, "nom;ville;prix\r\nDupont;Saint-Étienne;12,5 €\r\n\"Martin; Paul\";Lyon;3\r\n;;\r\n"),
        "pipe.csv": ("utf-8", False, "|", False, "nom|ville|prix\nDurand|Nîmes \"sud\"|7\n"),
        "tab.csv": ("utf-16-le", True, "\t", False, "nom\tville\tprix\r\nLi\t北京\t9\r\n"),
        "x.csv": ("utf-8", False, ",", False, "id,name,city,\n1,Ann,Paris,\n"),
        "y.csv": ("utf-8", False, ",", False, "city,id,name\nLyon,2,Bob\nNice,3,Cy,extra\n"),
        "empty.csv": ("utf-8", False, ",", False, ""),
    }
    meta = {}
    for name, (codec, bom, separator, sep_line, text) in csvs.items():
        prefix = ("sep=%s\r\n" % separator) if sep_line else ""
        write(INPUT / "csv" / name, (BOMS[codec] if bom else b"") + (prefix + text).encode(codec))
        meta[name] = {"codec": codec, "bom": bom, "separator": separator, "sepLine": sep_line}
    merges = {"same": ["a.csv", "b.csv"], "dialects": ["fr.csv", "pipe.csv", "tab.csv"], "reordered": ["x.csv", "empty.csv", "y.csv"]}
    write(INPUT / "csv.json", json.dumps({"files": meta, "merges": merges}, ensure_ascii=False, indent=1).encode("utf-8"))
    write(INPUT / "ready", b"")


def decoded(path, codec, bom):
    data = path.read_bytes()
    if bom:
        data = data[len(BOMS[codec]):]
    return data.decode(codec)


def lines_of(text):
    return 0 if not text else text.count("\n") + 1


def check_texts():
    manifest = json.loads((INPUT / "texts.json").read_text())
    stats = json.loads((OUT / "stats.json").read_text())
    for name, info in manifest.items():
        text = decoded(INPUT / "texts" / name, info["codec"], info["bom"])
        expected = [len(text), len(text.split()), lines_of(text)]
        if stats.get(name) != expected:
            fail("stats of %s: %s, Python %s" % (name, stats.get(name), expected))
    counts = json.loads((OUT / "counts.json").read_text())
    for case in json.loads((INPUT / "replace-cases.json").read_text(encoding="utf-8")):
        info = manifest[case["file"]]
        text = decoded(INPUT / "texts" / case["file"], info["codec"], info["bom"])
        if counts.get(case["id"]) != text.count(case["query"]):
            fail("count %s: %s, Python %d" % (case["id"], counts.get(case["id"]), text.count(case["query"])))
        expected = (BOMS[info["codec"]] if info["bom"] else b"") + text.replace(case["query"], case["replacement"]).encode(info["codec"])
        made = OUT / "replace" / (case["id"] + pathlib.Path(case["file"]).suffix)
        if not made.exists() or made.read_bytes() != expected:
            fail("replace %s: not the bytes of Python's str.replace" % case["id"])


def csv_rows(text, separator):
    rows = list(csv.reader(io.StringIO(text, newline=""), delimiter=separator))
    return [row for row in rows if any(cell != "" for cell in row)]


def input_rows(name, meta):
    info = meta[name]
    text = decoded(INPUT / "csv" / name, info["codec"], info["bom"])
    if info["sepLine"]:
        text = text.split("\r\n", 1)[1]
    return csv_rows(text, info["separator"])


def trimmed(header):
    header = list(header)
    while header and header[-1] == "":
        header.pop()
    return header


def expected_merge(files, meta):
    header, rows = None, []
    for name in files:
        found = input_rows(name, meta)
        if not found:
            continue
        if header is None:
            header, rows = found[0], found[1:]
            continue
        if trimmed(found[0]) == trimmed(header):
            rows += found[1:]
        else:
            order = [found[0].index(column) for column in trimmed(header)]
            rows += [[row[i] if i < len(row) else "" for i in order] + row[len(order):] for row in found[1:]]
    return [header] + rows


def check_merges():
    corpus = json.loads((INPUT / "csv.json").read_text(encoding="utf-8"))
    meta, outputs = corpus["files"], json.loads((OUT / "merges.json").read_text())
    for merge, files in corpus["merges"].items():
        data = (OUT / "merge" / (merge + ".csv")).read_bytes()
        first = meta[files[0]]
        wants_bom = not (first["codec"] == "utf-8" and not first["bom"])
        if data.startswith(BOMS["utf-8"]) != wants_bom:
            fail("merge %s: BOM %s, expected %s" % (merge, data.startswith(BOMS["utf-8"]), wants_bom))
        text = data.decode("utf-8-sig")
        if not text.endswith("\r\n"):
            fail("merge %s: the last row is not ended by CRLF" % merge)
        made = csv_rows(text, first["separator"])
        expected = expected_merge(files, meta)
        if made != expected:
            fail("merge %s: rows differ\n  made     %s\n  expected %s" % (merge, made, expected))
        if outputs[merge]["rows"] != len(expected) - 1:
            fail("merge %s: %d rows said, %d written" % (merge, outputs[merge]["rows"], len(expected) - 1))


def check():
    check_texts()
    check_merges()
    print("OK" if not failures else "%d FAILURES" % len(failures))
    return 0 if not failures else 1


def pypdf_module():
    try:
        import pypdf
    except ImportError:
        # Installed with `pip install --user pypdf`, which `python -I` leaves out: the user's folder only.
        import site
        sys.path.append(site.getusersitepackages())
        import pypdf
    return pypdf


def extracted(pdf):
    """The pages' texts by pdftotext, and all the text by pypdf."""
    run = subprocess.run(["pdftotext", "-enc", "UTF-8", str(pdf), "-"], capture_output=True, check=True)
    pages = run.stdout.decode("utf-8").split("\f")[:-1]
    reader = pypdf_module().PdfReader(str(pdf))
    return pages, "\n".join(page.extract_text() or "" for page in reader.pages)


def bare(text):
    return WHITE.sub("", text)


def check_text_pdf(pdf, source, info):
    text = decoded(source, info["codec"], info["bom"])
    pages, by_pypdf = extracted(pdf)
    for tool, found in (("pdftotext", "".join(pages)), ("pypdf", by_pypdf)):
        if bare(found) != bare(text):
            fail("%s (%s): the text extracted is not the source's" % (pdf.name, tool))


def cell_found(cell, text):
    """The cell whole, or a beginning of it followed by the ellipsis, white space apart."""
    cell = bare(CONTROL.sub(" ", cell))
    if not cell or cell in text:
        return True
    return any(cell[:n] + ELLIPSIS in text for n in range(len(cell), 0, -1))


def check_table_pdf(pdf, name, meta):
    rows = input_rows(name, meta)
    pages, by_pypdf = extracted(pdf)
    for tool, found in (("pdftotext", bare("".join(pages))), ("pypdf", bare(by_pypdf))):
        missing = [cell for row in rows for cell in row if not cell_found(cell, found)]
        if missing:
            fail("%s (%s): %d cells not found, first %r" % (pdf.name, tool, len(missing), missing[0]))
    header = [bare(CONTROL.sub(" ", cell)) for cell in rows[0] if cell] if rows else []
    for number, page in enumerate(pages, start=1):
        if header and not any(cell[:4] in bare(page) for cell in header):
            fail("%s: no cell of the header on page %d" % (pdf.name, number))


def check_device(folder):
    texts = json.loads((INPUT / "texts.json").read_text())
    meta = json.loads((INPUT / "csv.json").read_text(encoding="utf-8"))["files"]
    stems = {pathlib.Path(name).stem: name for name in texts}
    tables = {pathlib.Path(name).stem: name for name in meta}
    seen = 0
    for pdf in sorted(pathlib.Path(folder).glob("*.pdf")):
        stem = STAMP.sub("", pdf.stem)
        if stem in stems:
            check_text_pdf(pdf, INPUT / "texts" / stems[stem], texts[stems[stem]])
        elif stem in tables:
            check_table_pdf(pdf, tables[stem], meta)
        else:
            continue
        seen += 1
    print("%d PDF checked" % seen)
    print("OK" if not failures and seen else "%d FAILURES" % len(failures))
    return 0 if not failures and seen else 1


def main():
    command = sys.argv[1] if len(sys.argv) > 1 else "run"
    if command == "check-device":
        sys.exit(check_device(sys.argv[2]))
    if command in ("prepare", "run"):
        prepare()
    if command == "run":
        if OUT.exists():
            shutil.rmtree(OUT)
        # --rerun: sources seen before give the test task's results back from the build cache, the test not run
        # and nothing written in out/ (measured: a run after a sabotage restored found no stats.json).
        gradle = subprocess.run(["sh", "gradlew", ":app:testDebugUnitTest", "--tests", "*TxtCsvToolsOracleTest", "--rerun", "-q"], cwd=ROOT)
        if gradle.returncode != 0:
            sys.exit(gradle.returncode)
    if command in ("check", "run"):
        if not (OUT / "stats.json").exists():
            print("NOTHING TO CHECK: TxtCsvToolsOracleTest wrote nothing in", OUT)
            sys.exit(1)
        sys.exit(check())


if __name__ == "__main__":
    main()
