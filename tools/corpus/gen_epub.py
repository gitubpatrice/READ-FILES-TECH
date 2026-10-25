"""Hostile books for the reader (ViewerKind.READER), generated where corpus.py builds the corpus.

Each case: the file's name, its viewer, the outcome expected, the time budget on the device. CorpusCheck
opens the book as the screen does, then reads its first chapter: a hostile chapter is the first of its
spine. Also here, the HTML viewer's reading mode, which the reader shows: a page made by real software
(CPython's pydoc, copied from tools/corpus/html/), and a page over the reading mode's cap.
"""
import os
import pathlib
import shutil
import zipfile

MIB = 1024 * 1024
EPUB_CHAPTER = 50 * MIB  # FileCaps.EPUB_CHAPTER
EPUB_PACKAGE = 16 * MIB  # FileCaps.EPUB_PACKAGE
EPUB_ITEMS = 20_000  # FileCaps.EPUB_ITEMS
HTML_VIEWER = 20 * MIB  # FileCaps.HTML_VIEWER
HERE = pathlib.Path(__file__).resolve().parent
LOCAL_HEADER = b"PK" + bytes([3, 4])
CENTRAL_RECORD = b"PK" + bytes([1, 2])
OPF = "OEBPS/content.opf"
CONTAINER = (
    '<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">'
    '<rootfiles><rootfile full-path="%s" media-type="application/oebps-package+xml"/></rootfiles></container>'
)
XHTML_HEAD = (
    b'<?xml version="1.0" encoding="utf-8"?>\n<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.1//EN" '
    b'"http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd">\n<html xmlns="http://www.w3.org/1999/xhtml"><head><title>t</title></head><body>'
)
XHTML_TAIL = b"</body></html>"


def _xhtml(body):
    return XHTML_HEAD + body + XHTML_TAIL


def _filled(total, chunk, prefix=b"", suffix=b""):
    """[prefix], [chunk] repeated, [suffix]: [total] bytes in all."""
    middle = total - len(prefix) - len(suffix)
    return prefix + (chunk * (middle // len(chunk) + 1))[:middle] + suffix


def _opf(items, spine, toc=None):
    """An OPF of [items] (id, href, media type), its spine the ids [spine]."""
    manifest = "".join('<item id="%s" href="%s" media-type="%s"/>' % item for item in items)
    refs = "".join('<itemref idref="%s"/>' % idref for idref in spine)
    toc = ' toc="%s"' % toc if toc else ""
    return (
        '<?xml version="1.0" encoding="UTF-8"?><package xmlns="http://www.idpf.org/2007/opf" version="2.0">'
        "<metadata/><manifest>%s</manifest><spine%s>%s</spine></package>" % (manifest, toc, refs)
    ).encode("utf-8")


def _book(path, files, container=True, opf=OPF):
    """Writes the book [files] (name to bytes) at [path], its mimetype first and stored, as OCF wants."""
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as book:
        book.writestr(zipfile.ZipInfo("mimetype"), b"application/epub+zip", zipfile.ZIP_STORED)
        if container:
            book.writestr("META-INF/container.xml", CONTAINER % opf)
        for name, content in files.items():
            book.writestr(name, content)


def _chapters(*bodies):
    """A book of these chapters, in this order: c1.xhtml, c2.xhtml…"""
    items = [("c%d" % i, "c%d.xhtml" % i, "application/xhtml+xml") for i in range(1, len(bodies) + 1)]
    files = {"OEBPS/c%d.xhtml" % i: _xhtml(body) for i, body in enumerate(bodies, 1)}
    files[OPF] = _opf(items, [item[0] for item in items])
    return files


def _lie(path, name, declared):
    """Rewrites the uncompressed size of entry [name], in its local header and its central record, to [declared]."""
    data = bytearray(path.read_bytes())
    encoded = name.encode("utf-8")
    local = data.find(LOCAL_HEADER)
    while local >= 0:
        if data[local + 30 : local + 30 + len(encoded)] == encoded:
            data[local + 22 : local + 26] = declared.to_bytes(4, "little")
            break
        local = data.find(LOCAL_HEADER, local + 4)
    central = data.find(CENTRAL_RECORD)
    while central >= 0:
        if data[central + 46 : central + 46 + len(encoded)] == encoded:
            data[central + 24 : central + 28] = declared.to_bytes(4, "little")
            break
        central = data.find(CENTRAL_RECORD, central + 4)
    path.write_bytes(bytes(data))


def generate(out):
    cases = []

    def case(name, expect="ok", max_millis=5000):
        cases.append({"file": name, "viewer": "READER", "expect": expect, "maxMillis": max_millis})

    # A deflate bomb: 60 MiB of spaces in a chapter of 60 KB, over the chapter's cap — refused before it is inflated.
    _book(out / "epub-bomb-chapter.epub", _chapters(b"<p>" + b" " * (60 * MIB) + b"</p>", b"<p>two</p>"))
    case("epub-bomb-chapter.epub", "too_large")
    # The same bomb declaring 1 KB: its lie is caught one byte past the kilobyte.
    _book(out / "epub-bomb-lying.epub", _chapters(b"<p>" + b" " * (60 * MIB) + b"</p>"))
    _lie(out / "epub-bomb-lying.epub", "OEBPS/c1.xhtml", 1024)
    case("epub-bomb-lying.epub", "unreadable")
    # Inventory D22: one idref 100 000 times in the spine — one chapter, read once.
    files = _chapters(b"<p>repeated</p>")
    files[OPF] = _opf([("c1", "c1.xhtml", "application/xhtml+xml")], ["c1"] * 100_000)
    _book(out / "epub-spine-repeat.epub", files)
    case("epub-spine-repeat.epub")
    # A spine of a million items: an OPF past the package's cap, refused before it is parsed.
    files = _chapters(b"<p>one</p>", b"<p>two</p>", b"<p>three</p>")
    files[OPF] = _opf([("c%d" % i, "c%d.xhtml" % i, "application/xhtml+xml") for i in (1, 2, 3)], ["c1", "c2", "c3"] * 333_334)
    assert len(files[OPF]) > EPUB_PACKAGE
    _book(out / "epub-spine-million.epub", files)
    case("epub-spine-million.epub", "too_large")
    # A manifest past the items cap, under the package's.
    files = _chapters(b"<p>one</p>")
    files[OPF] = _opf([("i%d" % i, "c1.xhtml", "application/xhtml+xml") for i in range(EPUB_ITEMS + 1)], ["i0"])
    _book(out / "epub-manifest-too-many.epub", files)
    case("epub-manifest-too-many.epub", "too_many_items")
    # No container.xml: a ZIP of XHTML is no book.
    _book(out / "epub-no-container.epub", _chapters(b"<p>one</p>"), container=False)
    case("epub-no-container.epub", "unreadable")
    # An OPF with a DOCTYPE, and a "billion laughs" in it: never parsed.
    files = _chapters(b"<p>one</p>")
    laughs = '<!DOCTYPE package [<!ENTITY a "aaaaaaaaaa">' + "".join(
        '<!ENTITY %s "%s">' % (chr(98 + i), ("&%s;" % chr(97 + i)) * 10) for i in range(9)
    ) + "]>"
    files[OPF] = files[OPF].replace(b"<package", laughs.encode("ascii") + b"<package", 1)
    _book(out / "epub-opf-doctype.epub", files)
    case("epub-opf-doctype.epub", "unreadable")
    # A million nested divs (6 MB): flattened past the depth, its text read.
    _book(out / "epub-nested-divs.epub", _chapters(b"<div>" * 1_000_000 + b"<p>deep</p>" + b"</div>" * 1_000_000))
    case("epub-nested-divs.epub", max_millis=10000)
    # A chapter exactly at the chapter's cap, all markup and one paragraph: read; one byte more is refused.
    span = b'<span class="x"></span>'
    _book(out / "epub-chapter-at-cap.epub", {**_chapters(b""), "OEBPS/c1.xhtml": _filled(EPUB_CHAPTER, span, XHTML_HEAD, b"<p>end</p>" + XHTML_TAIL)})
    case("epub-chapter-at-cap.epub", max_millis=20000)
    _book(out / "epub-chapter-over-cap.epub", {**_chapters(b""), "OEBPS/c1.xhtml": _filled(EPUB_CHAPTER + 1, span, XHTML_HEAD, XHTML_TAIL)})
    case("epub-chapter-over-cap.epub", "too_large", 2000)
    # A chapter of 16 MiB of short paragraphs: past the text the reader keeps, refused as it grows.
    _book(out / "epub-text-over-cap.epub", {**_chapters(b""), "OEBPS/c1.xhtml": _filled(16 * MIB, b"<p>lorem ipsum</p>", XHTML_HEAD, XHTML_TAIL)})
    case("epub-text-over-cap.epub", "too_large", 10000)
    # References out of the archive, every spelling: none read; the one inside is.
    hrefs = ["../../../outside.xhtml", "%2E%2E/%2E%2E/outside.xhtml", "..%5C..%5Coutside.xhtml", "/OEBPS/c1.xhtml",
             "file:///sdcard/Download/x.xhtml", "C:/x.xhtml", "content://x/y.xhtml", "c1.xhtml"]
    files = _chapters(b"<p>inside</p>")
    files[OPF] = _opf([("h%d" % i, href, "application/xhtml+xml") for i, href in enumerate(hrefs)], ["h%d" % i for i in range(len(hrefs))])
    files["outside.xhtml"] = _xhtml(b"<p>outside</p>")
    _book(out / "epub-hrefs-escape.epub", files)
    case("epub-hrefs-escape.epub")
    # An NCX of a million points: read up to the package's cap, the book opens.
    files = _chapters(b"<p>one</p>")
    files[OPF] = _opf([("c1", "c1.xhtml", "application/xhtml+xml"), ("ncx", "toc.ncx", "application/x-dtbncx+xml")], ["c1"], toc="ncx")
    points = b"".join(b'<navPoint id="p%d"><navLabel><text>Point %d</text></navLabel><content src="c1.xhtml"/></navPoint>' % (i, i)
                      for i in range(1_000_000))
    files["OEBPS/toc.ncx"] = b'<?xml version="1.0"?><!DOCTYPE ncx PUBLIC "-//NISO//DTD ncx 2005-1//EN" ' \
        b'"http://www.daisy.org/z3986/2005/ncx-2005-1.dtd"><ncx><navMap>' + points + b"</navMap></ncx>"
    _book(out / "epub-ncx-million.epub", files)
    case("epub-ncx-million.epub", max_millis=10000)
    # XHTML 1.1's DTD named and its entities used, in a chapter and in the NCX (its own DTD named): read.
    files = _chapters("<h1>&Eacute;t&eacute;</h1><p>caf&eacute;&nbsp;&mdash; &laquo;&nbsp;oui&nbsp;&raquo;</p>".encode("ascii"))
    files[OPF] = _opf([("c1", "c1.xhtml", "application/xhtml+xml"), ("ncx", "toc.ncx", "application/x-dtbncx+xml")], ["c1"], toc="ncx")
    files["OEBPS/toc.ncx"] = b'<?xml version="1.0"?><!DOCTYPE ncx PUBLIC "-//NISO//DTD ncx 2005-1//EN" ' \
        b'"http://www.daisy.org/z3986/2005/ncx-2005-1.dtd"><ncx xmlns="http://www.daisy.org/z3986/2005/ncx/"><navMap>' \
        b'<navPoint id="p1"><navLabel><text>&Eacute;t&eacute;</text></navLabel><content src="c1.xhtml"/></navPoint></navMap></ncx>'
    _book(out / "epub-entities.epub", files)
    case("epub-entities.epub")
    # A DRM: its chapters would be noise — a format not opened.
    files = _chapters(b"<p>one</p>")
    files["META-INF/encryption.xml"] = (
        b'<?xml version="1.0"?><encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container" '
        b'xmlns:enc="http://www.w3.org/2001/04/xmlenc#"><enc:EncryptedData><enc:EncryptionMethod '
        b'Algorithm="http://www.w3.org/2001/04/xmlenc#aes128-cbc"/><enc:CipherData><enc:CipherReference URI="OEBPS/c1.xhtml"/>'
        b"</enc:CipherData></enc:EncryptedData></encryption>"
    )
    _book(out / "epub-drm.epub", files)
    case("epub-drm.epub", "unsupported")
    # Not a book at all, under a book's name; and nothing at all.
    (out / "epub-random-bytes.epub").write_bytes(os.urandom(MIB))
    case("epub-random-bytes.epub", "unreadable")
    (out / "epub-empty.epub").write_bytes(b"")
    case("epub-empty.epub", "unreadable", 2000)

    # The HTML viewer's reading mode: a page pydoc wrote, and a page over the reading mode's cap.
    shutil.copyfile(HERE / "html" / "python-pydoc-utf8.html", out / "reader-pydoc.html")
    case("reader-pydoc.html", max_millis=2000)
    (out / "reader-over-cap.html").write_bytes(_filled(HTML_VIEWER + 1, b"<p>a</p>", b"<!doctype html><body>"))
    case("reader-over-cap.html", "too_large", 2000)
    return cases
