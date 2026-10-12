"""Hostile files for the spreadsheet viewer (ViewerKind.SHEET), generated where corpus.py builds the corpus.

Each case: the file's name, its viewer, the outcome expected, the time budget on the device. The archives
are written by Python's zipfile at the byte level: the reader on the device is the app's own (ZipArchive).
"""
import os
import pathlib
import random
import shutil
import zipfile

MIB = 1024 * 1024
SPREADSHEET_CAP = 50 * MIB  # FileCaps.SPREADSHEET
PART_CAP = 200 * MIB  # FileCaps.ZIP_ENTRY_DECOMPRESSED
SHEET_ROWS = 5000  # FileCaps.SHEET_ROWS
TEXT_CHARS = 32 * MIB  # FileCaps.SHEET_TEXT_CHARS

HERE = pathlib.Path(__file__).resolve().parent
REAL = HERE / "sheets"
MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
PKG = "http://schemas.openxmlformats.org/package/2006/relationships"
TYPES = (
    b'<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
    b'<Default Extension="xml" ContentType="application/xml"/></Types>'
)
ROOT_RELS = (
    '<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="%s"><Relationship Id="rId1" '
    'Type="%s/officeDocument" Target="xl/workbook.xml"/></Relationships>' % (PKG, REL)
).encode()
WORKBOOK = (
    '<?xml version="1.0" encoding="UTF-8"?><workbook xmlns="%s" xmlns:r="%s"><sheets>'
    '<sheet name="Feuil1" sheetId="1" r:id="rId1"/></sheets></workbook>' % (MAIN, REL)
).encode()
WORKBOOK_RELS = (
    '<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="%s">'
    '<Relationship Id="rId1" Type="%s/worksheet" Target="worksheets/sheet1.xml"/>'
    '<Relationship Id="rId2" Type="%s/sharedStrings" Target="sharedStrings.xml"/></Relationships>' % (PKG, REL, REL)
).encode()
SHEET_HEAD = ('<?xml version="1.0" encoding="UTF-8"?><worksheet xmlns="%s"><sheetData>' % MAIN).encode()
SHEET_TAIL = b"</sheetData></worksheet>"
SST_HEAD = ('<?xml version="1.0" encoding="UTF-8"?><sst xmlns="%s">' % MAIN).encode()
ODF_HEAD = (
    '<?xml version="1.0" encoding="UTF-8"?><office:document-content '
    'xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" '
    'xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0" '
    'xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0"><office:body>'
).encode()


def _xlsx(path, sheet_chunks, strings_chunks=None):
    """An .xlsx of one sheet whose part, and shared strings, are written chunk by chunk (they may inflate to
    hundreds of MiB)."""
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("[Content_Types].xml", TYPES)
        archive.writestr("_rels/.rels", ROOT_RELS)
        archive.writestr("xl/workbook.xml", WORKBOOK)
        archive.writestr("xl/_rels/workbook.xml.rels", WORKBOOK_RELS)
        with archive.open("xl/worksheets/sheet1.xml", "w", force_zip64=True) as part:
            for chunk in sheet_chunks:
                part.write(chunk)
        if strings_chunks is not None:
            with archive.open("xl/sharedStrings.xml", "w", force_zip64=True) as part:
                for chunk in strings_chunks:
                    part.write(chunk)


def _repeat(unit, total):
    """[unit] repeated until [total] bytes, in pieces of about a MiB."""
    piece = unit * max(1, MIB // len(unit))
    written = 0
    while written < total:
        yield piece
        written += len(piece)


def _rows(first, last, cells):
    """Rows [first, last] (numbered from 1) of [cells] each, in pieces."""
    batch = []
    for r in range(first, last + 1):
        batch.append(b'<row r="%d">%s</row>' % (r, cells(r)))
        if len(batch) == 4096:
            yield b"".join(batch)
            batch = []
    yield b"".join(batch)


def _column(index):
    """0 -> A, 16383 -> XFD."""
    name = ""
    index += 1
    while index:
        index, rest = divmod(index - 1, 26)
        name = chr(65 + rest) + name
    return name


def generate(out):
    cases = []

    def case(name, expect, max_millis=15000):
        cases.append({"file": name, "viewer": "SHEET", "expect": expect, "maxMillis": max_millis})

    # A sheet part of 300 MiB in a file of a few hundred KB: rows of ten cells, far past the 5 000 shown.
    # Read as a stream, it stops at the row cap: shown, rows cut, the rest never inflated.
    row = b"<row>" + b"<c><v>1</v></c>" * 10 + b"</row>"
    _xlsx(out / "sheets-inflate.xlsx", [SHEET_HEAD] + list(_repeat(row, 300 * MIB)) + [SHEET_TAIL])
    case("sheets-inflate.xlsx", "ok", 5000)

    # One row whose cells never end: no row cap stops it — the elements cap does, before 200 MiB.
    _xlsx(out / "sheets-endless-row.xlsx", [SHEET_HEAD, b"<row>"] + list(_repeat(b"<c><v>1</v></c>", PART_CAP + MIB)))
    case("sheets-endless-row.xlsx", "too_many_items", 10000)

    # A shared-strings table that is a bomb: millions of strings, one of them shown.
    _xlsx(
        out / "sheets-strings-bomb.xlsx",
        [SHEET_HEAD, b'<row r="1"><c r="A1" t="s"><v>9999999</v></c></row>', SHEET_TAIL],
        [SST_HEAD] + list(_repeat(b"<si><t>a</t></si>", PART_CAP - MIB)),
    )
    case("sheets-strings-bomb.xlsx", "too_many_items", 10000)

    # Shared strings larger than a part may be once inflated, as they declare: refused before a byte is read.
    _xlsx(
        out / "sheets-strings-over-cap.xlsx",
        [SHEET_HEAD, b'<row r="1"><c r="A1" t="s"><v>0</v></c></row>', SHEET_TAIL],
        [SST_HEAD] + list(_repeat(b"<si><t>" + b"b" * 4000 + b"</t></si>", PART_CAP + MIB)),
    )
    case("sheets-strings-over-cap.xlsx", "too_large", 2000)

    # Excel's last row: 1 048 576 rows numbered, one cell each — the first 5 000 shown.
    _xlsx(out / "sheets-1048576-rows.xlsx", [SHEET_HEAD] + list(_rows(1, 1048576, lambda r: b'<c r="A%d"><v>%d</v></c>' % (r, r))) + [SHEET_TAIL])
    case("sheets-1048576-rows.xlsx", "ok", 5000)

    # Excel's last column: 16 384 cells in one row, A1 to XFD1 — the first 200 shown.
    cells = b"".join(b'<c r="%s1" t="inlineStr"><is><t>%d</t></is></c>' % (_column(c).encode(), c) for c in range(16384))
    _xlsx(out / "sheets-16384-columns.xlsx", [SHEET_HEAD, b'<row r="1">', cells, b"</row>", SHEET_TAIL])
    case("sheets-16384-columns.xlsx", "ok", 3000)

    # One far cell, XFD1048576: the 2.15.3 built the whole grid before taking its 5 000 rows (D21).
    _xlsx(out / "sheets-far-cell.xlsx", [SHEET_HEAD, b'<row r="1"><c r="A1"><v>1</v></c></row>',
                                          b'<row r="1048576"><c r="XFD1048576"><v>2</v></c></row>', SHEET_TAIL])
    case("sheets-far-cell.xlsx", "ok", 2000)

    # Inline texts past what a sheet may keep: 1 000 rows of 200 cells of 256 chars, 51 million chars.
    long_cell = b'<c t="inlineStr"><is><t>' + b"t" * 256 + b"</t></is></c>"
    text_row = b"<row>" + long_cell * 200 + b"</row>"
    _xlsx(out / "sheets-text-over-budget.xlsx", [SHEET_HEAD] + [text_row] * 1000 + [SHEET_TAIL])
    case("sheets-text-over-budget.xlsx", "too_large", 5000)

    # A billion laughs: refused at its DOCTYPE, before any entity is declared.
    laughs = b'<!ENTITY lol "lol">' + b"".join(
        b'<!ENTITY lol%d "%s">' % (level, (b"&lol%d;" % (level - 1) if level > 1 else b"&lol;") * 10) for level in range(1, 10)
    )
    head = SHEET_HEAD.replace(b"?>", b"?><!DOCTYPE worksheet [" + laughs + b"]>", 1)
    _xlsx(out / "sheets-laughs.xlsx", [head, b'<row r="1"><c r="A1" t="inlineStr"><is><t>&lol9;</t></is></c></row>', SHEET_TAIL])
    case("sheets-laughs.xlsx", "unreadable", 2000)

    # A million nested elements in a cell: refused at the depth cap, not held.
    _xlsx(out / "sheets-deep.xlsx", [SHEET_HEAD, b'<row r="1"><c r="A1"><v>', b"<x>" * 1000000])
    case("sheets-deep.xlsx", "unreadable", 2000)

    # A file at the cap: 1 048 576 rows of four cells, two of them shared strings (100 000 of them), and a
    # picture stored as Excel stores one. The heap must not hold what the sheet inflates to.
    rng = random.Random(20261012)
    strings = [SST_HEAD] + [b"".join(b"<si><t>Libell\xc3\xa9 %d</t></si>" % i for i in range(k, k + 10000)) for k in range(0, 100000, 10000)]
    strings.append(b"</sst>")
    big_rows = _rows(1, 1048576, lambda r: b'<c r="A%d"><v>%d</v></c><c r="B%d" t="s"><v>%d</v></c><c r="C%d"><v>%d.%02d</v></c><c r="D%d" t="s"><v>%d</v></c>'
                     % (r, r, r, r % 100000, r, r % 977, r % 100, r, (r * 7) % 100000))
    _xlsx(out / "sheets-at-cap.xlsx", [SHEET_HEAD] + list(big_rows) + [SHEET_TAIL], strings)
    room = SPREADSHEET_CAP - os.path.getsize(out / "sheets-at-cap.xlsx") - 64 * 1024
    if room > 0:
        with zipfile.ZipFile(out / "sheets-at-cap.xlsx", "a") as archive:
            archive.writestr(zipfile.ZipInfo("xl/media/image1.png"), rng.randbytes(room), compress_type=zipfile.ZIP_STORED)
    case("sheets-at-cap.xlsx", "ok", 15000)

    # An .xls whose first record declares 65 535 bytes, where BIFF8 allows 8 224: refused.
    data = bytearray((REAL / "excel-main.xls").read_bytes())
    bof = data.find(bytes([0x09, 0x08, 0x10, 0x00, 0x00, 0x06]))
    data[bof + 2:bof + 4] = b"\xff\xff"
    (out / "sheets-record-length.xls").write_bytes(bytes(data))
    case("sheets-record-length.xls", "unreadable", 2000)

    # An OLE file that is no workbook (Word 97) under an .xls name.
    shutil.copyfile(HERE / "documents" / "word-report.doc", out / "sheets-ole-not-workbook.xls")
    case("sheets-ole-not-workbook.xls", "unreadable", 2000)

    # The .xls cut in half.
    main = (REAL / "excel-main.xls").read_bytes()
    (out / "sheets-cut.xls").write_bytes(main[: len(main) // 2])
    case("sheets-cut.xls", "unreadable", 2000)

    # Told by the content, not the name: Excel's .xlsx under .xls and .ods names opens.
    shutil.copyfile(REAL / "excel-main.xlsx", out / "sheets-xlsx-named.xls")
    case("sheets-xlsx-named.xls", "ok", 2000)
    shutil.copyfile(REAL / "excel-main.xlsx", out / "sheets-xlsx-named.ods")
    case("sheets-xlsx-named.ods", "ok", 2000)

    # ODS: a billion columns and a billion rows repeated, with a text — unfolded up to the caps only.
    with zipfile.ZipFile(out / "sheets-repeat.ods", "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("mimetype", b"application/vnd.oasis.opendocument.spreadsheet")
        archive.writestr("content.xml", ODF_HEAD + b'<office:spreadsheet><table:table table:name="F">'
                         b'<table:table-row table:number-rows-repeated="1000000000"><table:table-cell '
                         b'table:number-columns-repeated="1000000000"><text:p>x</text:p></table:table-cell>'
                         b"</table:table-row></table:table></office:spreadsheet></office:body></office:document-content>")
    case("sheets-repeat.ods", "ok", 3000)

    # An OpenDocument TEXT under an .ods name: not a spreadsheet.
    shutil.copyfile(HERE / "documents" / "word-report.odt", out / "sheets-text-named.ods")
    case("sheets-text-named.ods", "unreadable", 2000)

    # Random bytes, an empty file, and one byte over the file's cap.
    with open(out / "sheets-random.xlsx", "wb") as handle:
        handle.write(os.urandom(MIB))
    case("sheets-random.xlsx", "unreadable", 2000)
    open(out / "sheets-empty.xls", "wb").close()
    case("sheets-empty.xls", "unreadable", 2000)
    with open(out / "sheets-over-cap.xlsx", "wb") as handle:
        handle.truncate(SPREADSHEET_CAP + 1)
    case("sheets-over-cap.xlsx", "too_large", 2000)
    return cases
