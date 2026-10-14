"""Makes the real PDFs of the PDF viewer's corpus (tools/corpus/pdf/), by real software — never by PdfBox
or PdfRenderer, which read them.

  - Microsoft Edge headless (Chromium / Skia), `--print-to-pdf`: a French text of three pages, and a
    document whose headings Edge writes as an outline (`--generate-pdf-document-outline`).
  - pypdf (an independent PDF library, not the app's readers): the three pages encrypted in AES-256 (R6)
    under a non-ASCII user password — the case the viewer asks a password for — and in AES-128 under an
    owner password only, which opens without asking.

The prototype's files (`rft_kotlin_proto_pdf/tools/pdf-fixtures/make_fixtures.py`: Edge CJK, a ReportLab
document, a CJK font not embedded written by hand) are copied as they are; corpus.json says which is which.

Usage: python -I tools/corpus/make_pdf.py   (Windows, Edge installed; pypdf and cryptography for AES)
"""
import os
import pathlib
import subprocess
import tempfile
from html import escape

import pypdf

HERE = pathlib.Path(__file__).resolve().parent / "pdf"
EDGE = r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"

# The password of `pypdf-aes256.pdf`, also in PdfUnlockTest (app/src/test): non-ASCII, as AES-256 (R6)
# passes it through SASLprep and UTF-8.
USER_PASSWORD = "Mot2passe-é€"
OWNER_PASSWORD = "propriétaire-RFT"

CSS = """
@page { size: A4; margin: 20mm; }
body { font-family: 'Times New Roman', serif; font-size: 14pt; line-height: 1.5; }
.page { page-break-after: always; }
.page:last-child { page-break-after: auto; }
"""

PAGES = [
    ["Page un — introduction.", "Le cœur de l’élève bat à tout rompre : « Où est passée la clé ? »"],
    ["Page deux — la suite.", "Ça coûte 12,50 € — déjà payé à la caisse nº 3, reçu à l’appui."],
    ["Page trois — annexe.", "Œuvre complète, reçue le 9 octobre ; montant 1 250 €."],
]

# Chapter, section: what Edge turns into a two-level outline, one chapter per page.
CHAPTERS = [
    ("Chapitre 1 — Début", ["Section 1.1 — Contexte", "Section 1.2 — Objectifs"]),
    ("Chapitre 2 — Milieu", ["Section 2.1 — Méthode"]),
    ("Chapitre 3 — Fin", []),
]


def edge_print(html, out_pdf, *flags):
    with tempfile.TemporaryDirectory() as scratch:
        source = os.path.join(scratch, "in.html")
        with open(source, "w", encoding="utf-8") as handle:
            handle.write(html)
        subprocess.run(
            [EDGE, "--headless", "--disable-gpu", "--no-pdf-header-footer", *flags,
             "--user-data-dir=" + os.path.join(scratch, "profile"), "--print-to-pdf=" + str(out_pdf),
             "file:///" + source.replace("\\", "/")],
            check=True, timeout=120, capture_output=True,
        )
    if not out_pdf.exists() or out_pdf.stat().st_size < 500:
        raise SystemExit("Edge wrote nothing: %s" % out_pdf)


def page_html(body):
    return "<!doctype html><html lang='fr'><head><meta charset='utf-8'><style>%s</style></head><body>%s</body></html>" % (CSS, body)


def main():
    HERE.mkdir(exist_ok=True)
    three = "".join("<div class='page'>%s</div>" % "".join("<p>%s</p>" % escape(line) for line in page) for page in PAGES)
    edge_print(page_html(three), HERE / "edge-fr-3p.pdf")

    chapters = "".join(
        "<div class='page'><h1>%s</h1><p>Texte.</p>%s</div>" % (escape(title), "".join("<h2>%s</h2><p>Texte.</p>" % escape(s) for s in sections))
        for title, sections in CHAPTERS
    )
    edge_print(page_html(chapters), HERE / "edge-outline.pdf", "--generate-pdf-document-outline")

    writer = pypdf.PdfWriter(clone_from=str(HERE / "edge-fr-3p.pdf"))
    writer.encrypt(user_password=USER_PASSWORD, owner_password=OWNER_PASSWORD, algorithm="AES-256")
    with open(HERE / "pypdf-aes256.pdf", "wb") as handle:
        writer.write(handle)
    # An owner password only (the common "printing forbidden" file): it opens without asking.
    writer = pypdf.PdfWriter(clone_from=str(HERE / "edge-fr-3p.pdf"))
    writer.encrypt(user_password="", owner_password=OWNER_PASSWORD, algorithm="AES-128")
    with open(HERE / "pypdf-owner-only.pdf", "wb") as handle:
        writer.write(handle)
    print("OK", sorted(p.name for p in HERE.iterdir()))


if __name__ == "__main__":
    main()
