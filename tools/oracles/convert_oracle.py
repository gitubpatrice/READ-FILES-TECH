"""Independent references for the Convert tool (phase 5): what other software reads in what it made.

Usage:
  python -I tools/oracles/convert_oracle.py check               what the JVM tests left in app/build/oracles/convert/
  python -I tools/oracles/convert_oracle.py check-device <dir>  what the phone made of the corpus (pulled from Conversions/)

`check`:
- xlsx/: each <name>.xlsx beside the <name>.csv it was made of. The CSV is read by Python's own `csv`
  module (encoding: a BOM, else UTF-8, else Windows-1252; the separator: the one of , ; TAB | whose cells
  fall where Excel finds cells; the rows of empty fields skipped as the CSV viewer skips them), then the
  workbook by openpyxl AND by Excel itself (excel_cells.vbs, through cscript): every cell must be the CSV's
  — text for text, and a number only where the text is that number written plainly (its value equal to the
  text's, Decimal for Decimal). openpyxl does not decode ECMA-376's `_xHHHH_` in an inline string, which
  Excel does: its texts are decoded here (a control character of a binary file is written so).
- images-to-pdf/: manifest.json names, page by page, the JPEG each page must carry (pypdf's raw stream) —
  byte for byte, but without its metadata segments (EXIF, XMP, IPTC, comments: no GPS position in a PDF),
  restated here independently, with no such segment left and the pixels Pillow decodes equal to the
  source's —, or the raw RGB (and alpha) its pixels must decode to (pypdf's FlateDecode); pdftotext must
  count the pages too.

`check-device <dir>`: the outputs the phone made, named <source>_<stamp>.<ext> as the app names them:
- <pdf>_*.txt (PDF -> Text of tools/corpus/pdf/<pdf>.pdf): each page between its `--- Page N ---` marker
  and the next equal to pdftotext's page (form feeds), up to white space;
- <text>_*.pdf (Text -> PDF of tools/corpus/text|markdown-json/<text>.txt|.md): the text pdftotext AND
  pypdf extract from all the pages equal to the source's, up to white space (lines are wrapped);
- <image>_*.jpg|png (Image -> JPG/PNG): Pillow finds no EXIF, no GPS, no XMP, and the size is the source's
  upright size (a JPEG of EXIF orientation 6 comes out turned).
"""
import csv
import decimal
import io
import json
import pathlib
import re
import subprocess
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[2]
OUTPUTS = ROOT / "app" / "build" / "oracles" / "convert"
CORPUS = ROOT / "tools" / "corpus"
VBS = pathlib.Path(__file__).resolve().parent / "excel_cells.vbs"
SEPARATORS = [",", ";", "\t", "|"]
EXACT_NUMBER = re.compile(r"-?(0|[1-9][0-9]*)(\.[0-9]*[1-9])?")
STAMP = re.compile(r"_\d{8}_\d{6}(_\d+)?$")
PAGE_MARKER = re.compile(r"^--- Page (\d+) ---$", re.MULTILINE)
WHITE = re.compile(r"\s+")

sys.stdout.reconfigure(encoding="utf-8", errors="replace")
failures = []


def fail(message):
    failures.append(message)
    print("ECHEC", message)


def is_exact_number(text):
    """The writer's rule, restated: plain digits, no leading or trailing zero, not -0, at most 15 digits."""
    if not EXACT_NUMBER.fullmatch(text) or text in ("-0",):
        return False
    return len(text.replace("-", "").replace(".", "")) <= 15


def decodings(data):
    """The texts the CSV may be, by its BOM, else UTF-8, else Windows-1252 (a broken last character too)."""
    if data.startswith(b"\xef\xbb\xbf"):
        return [data[3:].decode("utf-8", errors="replace")]
    if data.startswith(b"\xff\xfe"):
        return [data[2:].decode("utf-16-le", errors="replace")]
    if data.startswith(b"\xfe\xff"):
        return [data[2:].decode("utf-16-be", errors="replace")]
    return [data.decode("utf-8", errors="replace"), data.decode("cp1252", errors="replace")]


def csv_rows(text, separator):
    rows = csv.reader(io.StringIO(text, newline=""), delimiter=separator, strict=False)
    return [row for row in rows if any(field != "" for field in row)]


def expected_cells(rows):
    """{(row, column): text}, 1-based, the empty fields left out as the writer leaves them out."""
    return {(r + 1, c + 1): field for r, row in enumerate(rows) for c, field in enumerate(row) if field != ""}


def same_cell(text, kind, value):
    """A cell read back as [kind] ("s" or "n") with [value] is the CSV's [text]."""
    if is_exact_number(text):
        return kind == "n" and decimal.Decimal(str(value).replace(",", ".")) == decimal.Decimal(text)
    return kind == "s" and value == text


def openpyxl_cells(path):
    try:
        import openpyxl
    except ImportError:
        # Installed with `pip install --user openpyxl`, which `python -I` leaves out: the user's folder only.
        import site
        sys.path.append(site.getusersitepackages())
        import openpyxl
    book = openpyxl.load_workbook(path, read_only=True)
    if len(book.worksheets) != 1:
        fail("%s: %d sheets" % (path.name, len(book.worksheets)))
    cells = {}
    for r, row in enumerate(book.worksheets[0].iter_rows(values_only=True), start=1):
        for c, value in enumerate(row, start=1):
            if value is None:
                continue
            cells[(r, c)] = ("s", unescape(value)) if isinstance(value, str) else ("n", value)
    book.close()
    return cells


def unescape(text):
    """ECMA-376's `_xHHHH_` (ST_Xstring), which Excel decodes and openpyxl leaves as it is in an inline string."""
    return re.sub(r"_x([0-9A-Fa-f]{4})_", lambda m: chr(int(m.group(1), 16)), text)


def excel_cells(path):
    with tempfile.TemporaryDirectory() as folder:
        out = pathlib.Path(folder) / "cells.txt"
        run = subprocess.run(["cscript", "//nologo", str(VBS), str(path.resolve()), str(out)], capture_output=True, text=True)
        if run.returncode != 0:
            fail("%s: Excel refused it (%s)" % (path.name, (run.stdout + run.stderr).strip()))
            return None
        lines = out.read_text(encoding="utf-8-sig").splitlines()
    if lines[0] != "sheets\t1":
        fail("%s: Excel reads %s" % (path.name, lines[0]))
    cells = {}
    for line in lines[1:]:
        r, c, kind, value = line.split("\t")
        if kind == "s":
            value = bytes.fromhex(value).decode("utf-16-be", errors="surrogatepass")
        cells[(int(r), int(c))] = (kind, value)
    return cells


def compare(name, reader, expected, got):
    if got is None:
        return False
    missing = [key for key in expected if key not in got]
    extra = [key for key in got if key not in expected]
    wrong = [key for key in expected if key in got and not same_cell(expected[key], *got[key])]
    if missing or extra or wrong:
        sample = (wrong or missing or extra)[0]
        fail("%s (%s): %d missing, %d extra, %d different; first %s: CSV %r, read %r"
             % (name, reader, len(missing), len(extra), len(wrong), sample, expected.get(sample), got.get(sample)))
        return False
    return True


def check_xlsx():
    folder = OUTPUTS / "xlsx"
    workbooks = sorted(folder.glob("*.xlsx"))
    if not workbooks:
        fail("no workbook in %s: run the JVM tests first" % folder)
    for workbook in workbooks:
        data = (folder / (workbook.stem + ".csv")).read_bytes()
        by_openpyxl = openpyxl_cells(workbook)
        by_excel = excel_cells(workbook)
        # The reading of the CSV whose cells are where Excel found cells: the encoding and the separator are
        # the CSV viewer's choice, tested on their own; what this checks is what the workbook holds.
        read = by_excel or by_openpyxl
        readings = [(t, s, expected_cells(csv_rows(t, s))) for t in decodings(data) for s in SEPARATORS]
        # Where Excel found cells, and, of the decodings, the one Excel's texts are (a Windows-1252 file is
        # also UTF-8 with replacement characters).
        placed = [r for r in readings if set(r[2]) == set(read)] or readings[:1]
        _, separator, expected = next((r for r in placed if all(same_cell(v, *read[k]) for k, v in r[2].items())), placed[0])
        ok = compare(workbook.name, "openpyxl", expected, by_openpyxl) & compare(workbook.name, "Excel", expected, by_excel)
        if ok:
            numbers = sum(1 for kind, _ in by_openpyxl.values() if kind == "n")
            print("OK   %-36s %4d cells (%d numbers), separator %r, openpyxl and Excel" % (workbook.name, len(expected), numbers, separator))


def pdf_pages(path):
    run = subprocess.run(["pdftotext", "-enc", "UTF-8", str(path), "-"], capture_output=True)
    return run.stdout.decode("utf-8").split("\f")[:-1]


JPEG_KEPT_APPS = {0xE0, 0xE2, 0xEE}  # JFIF, the ICC profile, Adobe's colour transform: what draws the image


def jpeg_segments(data):
    """(marker, start, end) of each segment before the image data, then (SOS or EOI, start, len(data))."""
    position = 2
    while position + 4 <= len(data):
        while data[position] == 0xFF and data[position + 1] == 0xFF:
            position += 1
        marker = data[position + 1]
        if marker in (0xDA, 0xD9):
            yield marker, position, len(data)
            return
        end = position + 2 + int.from_bytes(data[position + 2:position + 4], "big")
        yield marker, position, end
        position = end


def jpeg_without_metadata(data):
    """The JPEG with its metadata segments left out — every APPn but JFIF, ICC and Adobe, and comments."""
    kept = [data[start:end] for marker, start, end in jpeg_segments(data)
            if not (0xE0 <= marker <= 0xEF or marker == 0xFE) or marker in JPEG_KEPT_APPS]
    return data[:2] + b"".join(kept)


def jpeg_metadata_markers(data):
    return [marker for marker, _, _ in jpeg_segments(data)
            if (0xE0 <= marker <= 0xEF or marker == 0xFE) and marker not in JPEG_KEPT_APPS]


def check_images_pdf():
    import pypdf
    from PIL import Image
    folder = OUTPUTS / "images-to-pdf"
    manifest = json.loads((folder / "manifest.json").read_text(encoding="utf-8"))
    pdf = folder / manifest["pdf"]
    reader = pypdf.PdfReader(str(pdf))
    pages = manifest["pages"]
    if len(reader.pages) != len(pages) or len(pdf_pages(pdf)) != len(pages):
        fail("%s: %d pages (pypdf), %d (pdftotext), %d expected" % (pdf.name, len(reader.pages), len(pdf_pages(pdf)), len(pages)))
        return
    for number, (page, expected) in enumerate(zip(reader.pages, pages), start=1):
        objects = page["/Resources"]["/XObject"]
        image = objects[list(objects.keys())[0]].get_object()
        if "jpeg" in expected:
            source = pathlib.Path(expected["jpeg"])
            source = source if source.is_absolute() else folder / source
            original = source.read_bytes()
            embedded = image._data
            same = image["/Filter"] == "/DCTDecode" and embedded == jpeg_without_metadata(original)
            clean = not jpeg_metadata_markers(embedded)
            pixels = Image.open(io.BytesIO(embedded)).tobytes() == Image.open(io.BytesIO(original)).tobytes()
            print(("OK  " if same and clean and pixels else "ECHEC"),
                  "page %d: the JPEG %s, byte for byte but its metadata (%d of %d bytes), pixels equal: %s"
                  % (number, source.name, len(embedded), len(original), pixels))
            if not (same and clean and pixels):
                fail("%s page %d: not the JPEG %s without its metadata" % (pdf.name, number, source.name))
        else:
            rgb = (folder / expected["rgb"]).read_bytes()
            same = image["/Filter"] == "/FlateDecode" and image.get_data() == rgb
            if "alpha" in expected:
                same = same and image["/SMask"].get_object().get_data() == (folder / expected["alpha"]).read_bytes()
            else:
                same = same and "/SMask" not in image
            print(("OK  " if same else "ECHEC"), "page %d: %dx%d pixels without loss%s" % (number, expected["width"], expected["height"], ", soft mask" if "alpha" in expected else ""))
            if not same:
                fail("%s page %d: pixels changed" % (pdf.name, number))


def source_of(output, folders, extensions):
    stem = STAMP.sub("", output.stem)
    for folder in folders:
        for extension in extensions:
            candidate = CORPUS / folder / (stem + extension)
            if candidate.is_file():
                return candidate
    return None


def squeeze(text):
    return WHITE.sub("", text)


def check_device(folder):
    import pypdf
    from PIL import Image
    folder = pathlib.Path(folder)
    for output in sorted(folder.glob("*.txt")):
        pdf = source_of(output, ["pdf"], [".pdf"])
        if pdf is None:
            continue
        # The encrypted copy of the French document: its text is the document's.
        reference = CORPUS / "pdf" / "edge-fr-3p.pdf" if pdf.stem == "pypdf-aes256" else pdf
        text = output.read_text(encoding="utf-8")
        parts = PAGE_MARKER.split(text)
        ours = {int(parts[i]): parts[i + 1] for i in range(1, len(parts) - 1, 2)}
        theirs = pdf_pages(reference)
        if sorted(ours) != list(range(1, len(theirs) + 1)):
            fail("%s: pages %s, pdftotext %d" % (output.name, sorted(ours), len(theirs)))
            continue
        different = [n for n in ours if squeeze(ours[n]) != squeeze(theirs[n - 1])]
        if different:
            n = different[0]
            fail("%s: page %d differs from pdftotext: %r / %r" % (output.name, n, squeeze(ours[n])[:80], squeeze(theirs[n - 1])[:80]))
        else:
            print("OK   %s: %d pages equal to pdftotext's, up to white space" % (output.name, len(ours)))
    for output in sorted(folder.glob("*.pdf")):
        source = source_of(output, ["text", "markdown-json"], [".txt", ".md"])
        if source is None:
            continue
        data = source.read_bytes()
        expected = squeeze(decodings(data)[0])
        by_poppler = squeeze("".join(pdf_pages(output)))
        by_pypdf = squeeze("".join(page.extract_text() or "" for page in pypdf.PdfReader(str(output)).pages))
        for reader, got in (("pdftotext", by_poppler), ("pypdf", by_pypdf)):
            if got == expected:
                print("OK   %s: %s gives the source's %d characters back" % (output.name, reader, len(expected)))
            else:
                at = next((i for i, (a, b) in enumerate(zip(expected, got)) if a != b), min(len(expected), len(got)))
                fail("%s (%s): differs at %d: source %r, PDF %r" % (output.name, reader, at, expected[at:at + 20], got[at:at + 20]))
    for output in sorted(list(folder.glob("*.jpg")) + list(folder.glob("*.png"))):
        source = source_of(output, ["image"], [".jpg", ".png", ".gif", ".bmp", ".webp"])
        if source is None:
            continue
        with Image.open(output) as made, Image.open(source) as original:
            exif = made.getexif()
            leaks = [key for key in ("exif", "xmp", "XML:com.adobe.xmp") if key in made.info] + (["EXIF tags"] if len(exif) else [])
            orientation = original.getexif().get(0x0112, 1)
            upright = original.size[::-1] if orientation in (5, 6, 7, 8) else original.size
            if leaks:
                fail("%s: metadata left: %s" % (output.name, leaks))
            elif made.size != upright:
                fail("%s: %s, the source upright is %s" % (output.name, made.size, upright))
            else:
                print("OK   %s: no EXIF, no GPS, no XMP; %dx%d upright" % (output.name, *made.size))


def main():
    if len(sys.argv) >= 2 and sys.argv[1] == "check":
        check_xlsx()
        check_images_pdf()
    elif len(sys.argv) == 3 and sys.argv[1] == "check-device":
        check_device(sys.argv[2])
    else:
        print(__doc__)
        sys.exit(2)
    print("ECHECS : %d" % len(failures) if failures else "TOUT VERT")
    sys.exit(1 if failures else 0)


main()
