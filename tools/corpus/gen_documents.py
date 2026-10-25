"""Hostile files for the document viewer (ViewerKind.DOCUMENT), generated where corpus.py builds the corpus.

Each case: the file's name, its viewer, the outcome expected, the time budget on the device. The ZIP
archives are written by Python's zipfile at the byte level: the reader on the device is java.util.zip.
"""
import os
import pathlib
import shutil
import zipfile

MIB = 1024 * 1024
DOC_ZIPPED_CAP = 50 * MIB  # FileCaps.DOC_ZIPPED
PART_CAP = 200 * MIB  # FileCaps.ZIP_ENTRY_DECOMPRESSED
TEXT_CAP = 50 * MIB  # FileCaps.TEXT_VIEWER
STRETCH_CAP = 4 * MIB  # FileCaps.XML_STRETCH_CHARS
DEPTH_CAP = 256  # FileCaps.XML_DEPTH

REAL = pathlib.Path(__file__).resolve().parent / "documents"
WORD = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
HEAD = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:document xmlns:w="%s"><w:body>' % WORD).encode()
TAIL = b"</w:body></w:document>"
ODF_HEAD = (
    '<?xml version="1.0" encoding="UTF-8"?><office:document-content '
    'xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" '
    'xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0"><office:body><office:text>'
).encode()


def _docx(path, body_chunks):
    """A .docx of one main part whose body is written chunk by chunk (it may inflate to hundreds of MiB)."""
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as archive:
        with archive.open("word/document.xml", "w") as part:
            part.write(HEAD)
            for chunk in body_chunks:
                part.write(chunk)
            part.write(TAIL)


def _repeat(unit, total):
    """[unit] repeated until [total] bytes, in pieces of about a MiB."""
    piece = unit * max(1, MIB // len(unit))
    written = 0
    while written < total:
        yield piece
        written += len(piece)


def generate(out):
    cases = []

    def case(name, expect, max_millis=15000):
        cases.append({"file": name, "viewer": "DOCUMENT", "expect": expect, "maxMillis": max_millis})

    # A main part of 300 KB that inflates past 200 MiB: 35 million empty paragraphs, the most tags a
    # byte can buy. Refused at FileCaps.XML_ELEMENTS (4 million), after 24 MB: at 200 MiB it took 59 s
    # on an emulator.
    _docx(out / "documents-bomb.docx", _repeat(b"<w:p/>", PART_CAP + MIB))
    case("documents-bomb.docx", "too_many_items", 10000)

    # 60 MiB of text in a part of 61 MiB: the text shown is capped too, at the text viewer's cap.
    _docx(out / "documents-text-over-cap.docx", _repeat(b"<w:p><w:r><w:t>" + b"x" * 4000 + b"</w:t></w:r></w:p>", 60 * MIB))
    case("documents-text-over-cap.docx", "too_large")

    # 45 MiB of text, under the cap: shown — what the heap keeps at the cap, measured on the device.
    _docx(out / "documents-text-near-cap.docx", _repeat(b"<w:p><w:r><w:t>" + b"y" * 4000 + b"</w:t></w:r></w:p>", 45 * MIB))
    case("documents-text-near-cap.docx", "ok", 20000)

    # One text of 5 million chars: past the stretch the parser would hold whole.
    _docx(out / "documents-long-stretch.docx", [b"<w:p><w:r><w:t>"] + list(_repeat(b"z", STRETCH_CAP + MIB)) + [b"</w:t></w:r></w:p>"])
    case("documents-long-stretch.docx", "too_large", 5000)

    # A billion laughs: refused at its DOCTYPE, before any entity is declared.
    laughs = b'<!ENTITY lol "lol">' + b"".join(
        b'<!ENTITY lol%d "%s">' % (level, (b"&lol%d;" % (level - 1) if level > 1 else b"&lol;") * 10) for level in range(1, 10)
    )
    with zipfile.ZipFile(out / "documents-laughs.docx", "w", zipfile.ZIP_DEFLATED) as archive:
        xml = HEAD.replace(b"?>", b"?><!DOCTYPE w:document [" + laughs + b"]>", 1) + b"<w:p><w:r><w:t>&lol9;</w:t></w:r></w:p>" + TAIL
        archive.writestr("word/document.xml", xml)
    case("documents-laughs.docx", "unreadable", 2000)

    # A million nested elements: refused at the depth cap, not held.
    _docx(out / "documents-deep.docx", [b"<w:p>", b"<x>" * 1000000])
    case("documents-deep.docx", "unreadable", 2000)

    # The 2.15.3's quadratic trap: thousands of paragraphs never closed (5 s on a phone with its regex).
    with zipfile.ZipFile(out / "documents-never-closed.odt", "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("content.xml", ODF_HEAD + b"<text:p>" * 20000)
    case("documents-never-closed.odt", "unreadable", 2000)

    # A Word package whose main part is missing.
    rels = (
        b'<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" '
        b'Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>'
        b"</Relationships>"
    )
    with zipfile.ZipFile(out / "documents-no-main.docx", "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("[Content_Types].xml", b'<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"/>')
        archive.writestr("_rels/.rels", rels)
    case("documents-no-main.docx", "unreadable", 2000)

    # Word 97's .doc under a .docx name: told by its content, a format not opened.
    shutil.copyfile(REAL / "word-report.doc", out / "documents-ole-named.docx")
    case("documents-ole-named.docx", "unsupported", 2000)

    # A .docx named .odt and an .odt named .docx: told by their content, both open (the 2.15.3 refused both).
    shutil.copyfile(REAL / "word-report.docx", out / "documents-docx-named.odt")
    case("documents-docx-named.odt", "ok", 2000)
    shutil.copyfile(REAL / "word-report.odt", out / "documents-odt-named.docx")
    case("documents-odt-named.docx", "ok", 2000)

    # Random bytes under a document's name.
    with open(out / "documents-random.odt", "wb") as handle:
        handle.write(os.urandom(MIB))
    case("documents-random.odt", "unreadable", 2000)

    # One byte over the file's cap: refused before anything is read.
    with open(out / "documents-over-cap.docx", "wb") as handle:
        handle.truncate(DOC_ZIPPED_CAP + 1)
    case("documents-over-cap.docx", "too_large", 2000)
    return cases
