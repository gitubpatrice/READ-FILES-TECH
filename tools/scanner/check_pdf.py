"""Reads the scanner's PDFs with an independent reader (pypdf), against what each page must hold.

Usage: python tools/scanner/check_pdf.py [folder]   (not -I: pypdf may be in the user site-packages)   (default: app/build/scanner-pdf)

JpegPdfTest writes each PDF next to a JSON manifest: per page, the JPEG's width, height, channels and
SHA-256. For each PDF, pypdf (strict mode) must open it, find that many pages, and on each page one
image XObject whose filter is DCTDecode, whose size and colour space match, and whose stream is the
JPEG byte for byte; the page must have the image's shape with its long side at 842 points (A4).
Not a runtime dependency: `pip install pypdf` on the computer that runs it.
"""
import hashlib
import json
import pathlib
import sys

from pypdf import PdfReader

LONG_SIDE_PT = 842.0


def check(pdf_path, manifest):
    errors = []
    reader = PdfReader(str(pdf_path), strict=True)
    if len(reader.pages) != len(manifest):
        return ["%d pages, %d expected" % (len(reader.pages), len(manifest))]
    for n, (page, want) in enumerate(zip(reader.pages, manifest), start=1):
        images = page["/Resources"]["/XObject"]
        if len(images) != 1:
            errors.append("page %d: %d images" % (n, len(images)))
            continue
        image = list(images.values())[0].get_object()
        space = "/DeviceGray" if want["components"] == 1 else "/DeviceRGB"
        facts = {
            "filter": (image["/Filter"], "/DCTDecode"),
            "width": (image["/Width"], want["width"]),
            "height": (image["/Height"], want["height"]),
            "colour space": (image["/ColorSpace"], space),
            "sha256": (hashlib.sha256(image._data).hexdigest(), want["sha256"]),
        }
        for name, (got, expected) in facts.items():
            if got != expected:
                errors.append("page %d: %s %s, expected %s" % (n, name, got, expected))
        box = page.mediabox
        w, h = float(box.width), float(box.height)
        long_side = max(want["width"], want["height"])
        expected_box = (LONG_SIDE_PT * want["width"] / long_side, LONG_SIDE_PT * want["height"] / long_side)
        if abs(w - expected_box[0]) > 0.01 or abs(h - expected_box[1]) > 0.01:
            errors.append("page %d: %.2f x %.2f pt, expected %.2f x %.2f" % (n, w, h, *expected_box))
    return errors


def main():
    folder = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "app/build/scanner-pdf")
    pdfs = sorted(folder.glob("*.pdf"))
    if not pdfs:
        print("no PDF in", folder, "- run JpegPdfTest first")
        return 2
    failed = 0
    for pdf in pdfs:
        manifest = json.loads(pdf.with_suffix(".json").read_text(encoding="utf-8"))
        errors = check(pdf, manifest)
        print("%-30s %s" % (pdf.name, "OK, %d pages" % len(manifest) if not errors else "FAILED"))
        for e in errors:
            print("   ", e)
        failed += bool(errors)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
