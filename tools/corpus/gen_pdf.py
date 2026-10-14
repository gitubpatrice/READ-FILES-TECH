"""Hostile files for the PDF viewer (ViewerKind.PDF), generated where corpus.py builds the corpus.

The corpus loads what the screen shows first, through the `:pdf` process as the screen does (CorpusCheck):
the document, its first and last pages at a phone's width, and its outline. A file that ends the `:pdf`
process (a native crash of pdfium, out of memory) must come out as `reader_stopped`, the app alive — the
corpus's own report proves it: the provider answers.

Outcomes: `ok`, `unreadable`, `unsupported`, `password_required`, `certificate`, `reader_stopped`.
⚠️ Every outcome below is a PREDICTION of what pdfium does, written without a device (no adb for the agent
that wrote it, 2026-10-14): a different outcome that is not a death of the app is a fact to record in
corpus.json, not a defect to fix. What PdfBox makes of the encrypted ones is measured on the JVM
(PdfBoxReadingTest, on the same forms written by app/src/test/.../TestPdfs.kt).

Each file is written byte by byte (`_Pdf`): no PDF library writes a cyclic page tree on request.
"""
import os
import random
import zlib
from pathlib import Path

HERE = Path(__file__).resolve().parent
REAL = HERE / "pdf" / "edge-fr-3p.pdf"

PUB_SEC = b"/Filter /Adobe.PubSec /SubFilter /adbe.pkcs7.s4 /V 1 /Recipients [<3080>]"


class _Pdf:
    """Objects written in order, then their cross-reference table (or stream) and trailer."""

    def __init__(self, version=b"1.7"):
        self.out = bytearray(b"%PDF-" + version + b"\n%\xe2\xe3\xcf\xd3\n")
        self.offsets = {}

    def obj(self, number, body, stream=None):
        self.offsets[number] = len(self.out)
        self.out += b"%d 0 obj\n" % number + body
        if stream is not None:
            self.out += b"\nstream\n" + stream + b"\nendstream"
        self.out += b"\nendobj\n"

    def section(self, numbers, size, trailer, first=True):
        """A cross-reference section of [numbers], then its trailer; its offset."""
        start = len(self.out)
        self.out += b"xref\n"
        if first:
            self.out += b"0 1\n0000000000 65535 f \n"
        for number in numbers:
            self.out += b"%d 1\n%010d 00000 n \n" % (number, self.offsets[number])
        self.out += b"trailer\n<< /Size %d /Root 1 0 R %s >>\nstartxref\n%d\n%%%%EOF\n" % (size, trailer, start)
        return start

    def table(self, trailer=b""):
        """One section listing every object 1..n, written as one subsection (fast for 100 000 objects)."""
        start = len(self.out)
        count = max(self.offsets) + 1
        free = b"0000000000 65535 f \n"
        rows = [free] + [b"%010d 00000 n \n" % self.offsets[n] if n in self.offsets else free for n in range(1, count)]
        self.out += b"xref\n0 %d\n" % count + b"".join(rows)
        self.out += b"trailer\n<< /Size %d /Root 1 0 R %s >>\nstartxref\n%d\n%%%%EOF\n" % (count, trailer, start)

    def save(self, path):
        with open(path, "wb") as handle:
            handle.write(self.out)


def _one_page(pdf, catalog_extra=b""):
    pdf.obj(1, b"<< /Type /Catalog /Pages 2 0 R " + catalog_extra + b" >>")
    pdf.obj(2, b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
    text = b"BT /F1 24 Tf 72 700 Td (Read Files Tech) Tj ET"
    pdf.obj(3, b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Contents 30 0 R "
               b"/Resources << /Font << /F1 31 0 R >> >> >>")
    pdf.obj(30, b"<< /Length %d >>" % len(text), text)
    pdf.obj(31, b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")


def _pub_sec(path, variant):
    pdf = _Pdf()
    _one_page(pdf)
    if variant == "direct":
        pdf.table(b"/Encrypt << " + PUB_SEC + b" >>")
    elif variant == "escaped":
        pdf.table(b"/Encrypt << " + PUB_SEC.replace(b"/Adobe.PubSec", b"/Adobe#2EPubSec") + b" >>")
    elif variant == "indirect":
        pdf.obj(4, b"<< " + PUB_SEC + b" >>")
        pdf.table(b"/Encrypt 4 0 R")
    elif variant == "incremental":
        first = pdf.section([1, 2, 3, 30, 31], 32, b"")
        pdf.obj(4, b"<< " + PUB_SEC + b" >>")
        pdf.section([4], 32, b"/Encrypt 4 0 R /Prev %d" % first, first=False)
    else:  # an xref stream for the trailer (PDF 1.5)
        pdf.obj(4, b"<< " + PUB_SEC + b" >>")
        start = len(pdf.out)
        pdf.offsets[32] = start
        rows = bytearray(b"\x00\x00\x00\x00\x00\xff\xff")
        for number in range(1, 33):
            at = pdf.offsets.get(number)
            rows += b"\x01" + at.to_bytes(4, "big") + b"\x00\x00" if at is not None else b"\x00\x00\x00\x00\x00\x00\x00"
        pdf.obj(32, b"<< /Type /XRef /Size 33 /W [1 4 2] /Root 1 0 R /Encrypt 4 0 R /Length %d >>" % len(rows), bytes(rows))
        pdf.out += b"startxref\n%d\n%%%%EOF\n" % start
    pdf.save(path)


def _pages(path, count):
    """[count] blank pages, A4 inherited from the root, under one flat /Kids."""
    pdf = _Pdf()
    pdf.obj(1, b"<< /Type /Catalog /Pages 2 0 R >>")
    kids = b" ".join(b"%d 0 R" % (3 + i) for i in range(count))
    pdf.obj(2, b"<< /Type /Pages /MediaBox [0 0 595 842] /Kids [" + kids + b"] /Count %d >>" % count)
    for i in range(count):
        pdf.obj(3 + i, b"<< /Type /Page /Parent 2 0 R >>")
    pdf.table()
    pdf.save(path)


def _huge_boxes(path):
    """A page of 14 400 points a side times a UserUnit of 75 000, then one of 1 x 100 000 points."""
    pdf = _Pdf()
    pdf.obj(1, b"<< /Type /Catalog /Pages 2 0 R >>")
    pdf.obj(2, b"<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 >>")
    pdf.obj(3, b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 14400 14400] /UserUnit 75000 >>")
    pdf.obj(4, b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 1 100000] >>")
    pdf.table()
    pdf.save(path)


def _deep_tree(path, depth):
    """One page under [depth] nested /Pages nodes."""
    pdf = _Pdf()
    pdf.obj(1, b"<< /Type /Catalog /Pages 2 0 R >>")
    for level in range(depth):
        number = 2 + level
        parent = b"/Parent %d 0 R " % (number - 1) if level else b""
        pdf.obj(number, b"<< /Type /Pages " + parent + b"/Kids [%d 0 R] /Count 1 /MediaBox [0 0 595 842] >>" % (number + 1))
    pdf.obj(2 + depth, b"<< /Type /Page /Parent %d 0 R >>" % (1 + depth))
    pdf.table()
    pdf.save(path)


def _cyclic_tree(path):
    """A page, then a /Pages node whose kid is the root itself."""
    pdf = _Pdf()
    pdf.obj(1, b"<< /Type /Catalog /Pages 2 0 R >>")
    pdf.obj(2, b"<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 /MediaBox [0 0 595 842] >>")
    pdf.obj(3, b"<< /Type /Page /Parent 2 0 R >>")
    pdf.obj(4, b"<< /Type /Pages /Parent 2 0 R /Kids [2 0 R] /Count 1 >>")
    pdf.table()
    pdf.save(path)


def _hostile_outline(path, chain, depth):
    """Two pages; an outline whose first entry loops to itself and holds a child pointing back to it,
    then a chain of [chain] siblings, the first of which nests [depth] levels deep."""
    pdf = _Pdf()
    pdf.obj(1, b"<< /Type /Catalog /Pages 2 0 R /Outlines 10 0 R >>")
    pdf.obj(2, b"<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 /MediaBox [0 0 595 842] >>")
    pdf.obj(3, b"<< /Type /Page /Parent 2 0 R >>")
    pdf.obj(4, b"<< /Type /Page /Parent 2 0 R >>")
    first_chain = 100
    pdf.obj(10, b"<< /Type /Outlines /First 11 0 R /Last 11 0 R >>")
    # A: its /Next is itself, and its child's child is A again.
    pdf.obj(11, b"<< /Title (Boucle) /Parent 10 0 R /Next %d 0 R /First 12 0 R /Last 12 0 R /Dest [3 0 R /Fit] >>" % first_chain)
    pdf.obj(12, b"<< /Title (Enfant) /Parent 11 0 R /First 11 0 R /Last 11 0 R /Dest [4 0 R /Fit] >>")
    for i in range(chain):
        number = first_chain + i
        following = b"/Next %d 0 R " % (number + 1) if i < chain - 1 else b"/Next 11 0 R "
        child = b"/First %d 0 R " % (first_chain + chain) if i == 0 else b""
        pdf.obj(number, b"<< /Title (Entree %d) /Parent 10 0 R " % i + following + child + b"/Dest [%d 0 R /Fit] >>" % (3 + i % 2))
    for level in range(depth):
        number = first_chain + chain + level
        child = b"/First %d 0 R " % (number + 1) if level < depth - 1 else b""
        pdf.obj(number, b"<< /Title (Niveau %d) " % level + child + b"/Dest [3 0 R /Fit] >>")
    pdf.table()
    pdf.save(path)


def _deflate_bomb(path, total):
    """A page whose content stream inflates to [total] bytes of spaces — valid, and drawing nothing."""
    compressor = zlib.compressobj(9)
    chunk = b" " * (1 << 20)
    data = bytearray()
    for _ in range(total // len(chunk)):
        data += compressor.compress(chunk)
    data += compressor.flush()
    pdf = _Pdf()
    pdf.obj(1, b"<< /Type /Catalog /Pages 2 0 R >>")
    pdf.obj(2, b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
    pdf.obj(3, b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Contents 4 0 R >>")
    pdf.obj(4, b"<< /Length %d /Filter /FlateDecode >>" % len(data), bytes(data))
    pdf.table()
    pdf.save(path)


def generate(out):
    cases = []

    def case(name, expect, max_millis=15000):
        cases.append({"file": name, "viewer": "PDF", "expect": expect, "maxMillis": max_millis})

    real = REAL.read_bytes()
    # Half of Edge's file: no cross-reference, no trailer (the prototype: 40 bytes less were refused).
    (out / "pdf-truncated.pdf").write_bytes(real[: len(real) // 2])
    case("pdf-truncated.pdf", "unreadable")
    # Its middle overwritten with zeros: the prototype saw PdfRenderer render a blank page, no error.
    middle = bytearray(real)
    middle[len(real) // 3: 2 * len(real) // 3] = bytes(len(real) // 3)
    (out / "pdf-middle-zeroed.pdf").write_bytes(bytes(middle))
    case("pdf-middle-zeroed.pdf", "ok")
    (out / "pdf-random-bytes.pdf").write_bytes(random.Random(7).randbytes(1 << 20))
    case("pdf-random-bytes.pdf", "unreadable")
    (out / "pdf-empty.pdf").write_bytes(b"")
    case("pdf-empty.pdf", "unreadable")

    # 100 000 pages: opened on its first, nothing measured up front; the last one rendered too.
    _pages(out / "pdf-100000-pages.pdf", 100_000)
    case("pdf-100000-pages.pdf", "ok")
    _huge_boxes(out / "pdf-huge-mediabox.pdf")
    case("pdf-huge-mediabox.pdf", "ok")
    # pdfium stops at 1 024 levels (kMaxPageLevel): no page left to show.
    _deep_tree(out / "pdf-deep-page-tree.pdf", 5000)
    case("pdf-deep-page-tree.pdf", "unreadable")
    _cyclic_tree(out / "pdf-cyclic-page-tree.pdf")
    case("pdf-cyclic-page-tree.pdf", "ok")
    # An outline of loops, 20 000 siblings (the panel lists 5 000) and 10 000 levels (it reads 32).
    _hostile_outline(out / "pdf-outline-hostile.pdf", 20_000, 10_000)
    case("pdf-outline-hostile.pdf", "ok")
    # 1 GiB of content in 1 MiB: pdfium inflating it whole would end the :pdf process, never the app.
    _deflate_bomb(out / "pdf-deflate-bomb.pdf", 1 << 30)
    case("pdf-deflate-bomb.pdf", "reader_stopped", 30000)

    # Encrypted for certificates: refused in its own words by PdfBox's own choice of handler, in every
    # form the encryption dictionary can take (the four of the plan's review, and the plain one).
    for variant in ("direct", "escaped", "indirect", "incremental", "xref-stream"):
        _pub_sec(out / ("pdf-pubsec-%s.pdf" % variant), variant)
        case("pdf-pubsec-%s.pdf" % variant, "certificate")
    # A handler neither reader knows: unsupported, not damaged.
    pdf = _Pdf()
    _one_page(pdf)
    pdf.table(b"/Encrypt << /Filter /FOO.Unknown /V 1 >> /ID [<00> <00>]")
    pdf.save(out / "pdf-unknown-handler.pdf")
    case("pdf-unknown-handler.pdf", "unsupported")
    return cases


if __name__ == "__main__":
    target = Path(os.environ.get("RFT_PDF_OUT", "."))
    for item in generate(target):
        print(item)
