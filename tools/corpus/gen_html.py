"""Hostile pages for the HTML viewer (ViewerKind.HTML), generated where corpus.py builds the corpus.

Each case: the file's name, its viewer, the outcome expected, the time budget on the device. On the
device, CorpusCheck asks the viewer's loader for every address a page names, and for the app's private
files by the three ways of inventory D20 (a path above the folder, file:, the FileProvider): whatever is
served must be a file of the page's folder, outside the app's sandbox — else the page is reported
`unreadable`. A page that names only refused addresses passes: refused is the point.

Also written here, next to the real pages of tools/corpus/html/ (corpus.py copies those as
`html__<name>`), the files they reference: their folder must be served, not only refused.
"""
import os

MIB = 1024 * 1024
HTML_VIEWER_CAP = 20 * MIB  # FileCaps.HTML_VIEWER
PACKAGES = ["com.readfilestech.read_files_tech", "com.readfilestech.read_files_tech.next"]
# The smallest PNG: one transparent pixel.
PNG = bytes.fromhex(
    "89504e470d0a1a0a0000000d4948445200000001000000010806000000"
    "1f15c4890000000d49444154789c6360000002000100e221bc330000000049454e44ae426082"
)


def _write(path, chunk, total, prefix=b"", suffix=b""):
    """[prefix], then [chunk] repeated, then [suffix]: [total] bytes in all."""
    with open(path, "wb") as handle:
        handle.write(prefix)
        total -= len(prefix) + len(suffix)
        while total > 0:
            piece = chunk[: min(len(chunk), total)]
            handle.write(piece)
            total -= len(piece)
        handle.write(suffix)


def _d20_references():
    """The private files of the app, every way a page could name them."""
    lines = []
    for package in PACKAGES:
        for root in ("data/data/%s" % package, "data/user/0/%s" % package):
            private = root + "/shared_prefs/settings.xml"
            up = "../" * 6
            lines += [
                '<img src="%s%s">' % (up, private),
                '<img src="%s">' % (up + private).replace("/", "%2F"),
                '<img src="%s">' % (up + private).replace("..", "%2E%2E"),
                '<img src="%s">' % (up + private).replace("/", chr(92)),
                '<img src="file:///%s">' % private,
                '<img src="file://localhost/%s">' % private,
                '<img src="content://%s.fileprovider/root/%s">' % (package, private),
                '<img srcset="%s%s 1x, file:///%s 2x">' % (up, private, private),
                '<link rel="stylesheet" href="%s%s">' % (up, private),
                '<div style="background:url(%s%s)"></div>' % (up, private),
                '<style>@import url("file:///%s"); p { background: url(%s%s) }</style>' % (private, up, private),
                '<video poster="file:///%s"></video>' % private,
                '<svg><image href="file:///%s"/></svg>' % private,
            ]
    return "\n".join(lines)


def generate(out):
    cases = []

    def case(name, expect="ok", max_millis=3000):
        cases.append({"file": name, "viewer": "HTML", "expect": expect, "maxMillis": max_millis})

    # What the real pages reference: their style sheets, beside them.
    (out / "calendar.css").write_text("table.year { background: url(calendar-bg.png) }", encoding="utf-8")
    (out / "calendar-bg.png").write_bytes(PNG)
    (out / "calendrier été.css").write_text("th { color: #336699 }", encoding="utf-8")

    # A page saved with its folder, as Edge's "Save as, complete" does: its folder is served.
    folder = out / "html-saved_files"
    folder.mkdir()
    (folder / "style.css").write_text("@import url(more.css); p { background: url(a.png) }", encoding="utf-8")
    (folder / "more.css").write_text("h1 { color: #aa0000 }", encoding="utf-8")
    (folder / "a.png").write_bytes(PNG)
    (out / "html-saved.html").write_text(
        '<!DOCTYPE html>\n<!-- saved from url=(0024)https://www.example.org/ -->\n<html><head><meta charset="utf-8">'
        '<link rel="stylesheet" href="html-saved_files/style.css"></head>'
        '<body><h1>Été</h1><img src="html-saved_files/a.png" srcset="html-saved_files/a.png 2x">'
        '<a href="html-saved_files/a.png">picture</a> <a href="#top">top</a></body></html>',
        encoding="utf-8",
    )
    case("html-saved.html")

    # Inventory D20: the app's private files, by a path above the folder, file: and the FileProvider.
    (out / "html-d20.html").write_text(
        "<!doctype html><html><head><title>D20</title></head><body>\n%s\n</body></html>" % _d20_references(),
        encoding="utf-8",
    )
    case("html-d20.html")
    # A <base> pointing at the sandbox: base-uri 'none', and the loader serves nothing there anyway.
    (out / "html-base-sandbox.html").write_text(
        '<html><head><base href="file:///data/data/%s/"></head><body><img src="shared_prefs/settings.xml">'
        '<img src="files/vault/index.json"></body></html>' % PACKAGES[0],
        encoding="utf-8",
    )
    case("html-base-sandbox.html")

    # The network, every way a page could try it; and what it does without a tap.
    (out / "html-remote.html").write_text(
        '<!DOCTYPE html><html><head><base href="https://attacker.example/">'
        '<meta http-equiv="refresh" content="0; url=https://attacker.example/refresh">'
        '<link rel="stylesheet" href="https://attacker.example/s.css"><link rel="prefetch" href="https://attacker.example/p">'
        '<style>@import "https://attacker.example/i.css"; body { background: url(//attacker.example/b.png) }'
        '@font-face { font-family: x; src: url(https://attacker.example/f.woff) }</style></head><body>'
        '<img src="https://attacker.example/pixel.png" srcset="https://attacker.example/2x.png 2x">'
        '<picture><source srcset="https://attacker.example/s.webp"></picture>'
        '<iframe src="https://attacker.example/frame"></iframe><iframe srcdoc="<img src=https://attacker.example/srcdoc>"></iframe>'
        '<object data="https://attacker.example/o"></object><embed src="https://attacker.example/e">'
        '<form action="https://attacker.example/f" method="post"><input name="q"></form>'
        '<svg xmlns="http://www.w3.org/2000/svg"><script>fetch("https://attacker.example/svg")</script>'
        '<image href="https://attacker.example/svg.png"/></svg>'
        '<audio src="https://attacker.example/a.mp3"></audio><video src="https://attacker.example/v.mp4"></video>'
        '<a href="javascript:fetch(1)">js</a><a href="intent://x#Intent;end">intent</a></body></html>',
        encoding="utf-8",
    )
    case("html-remote.html")
    # A refresh to the page itself: a reload loop, unless the viewer refuses what nobody tapped.
    (out / "html-refresh-self.html").write_text(
        '<meta http-equiv="refresh" content="0"><p>refresh</p>', encoding="utf-8"
    )
    case("html-refresh-self.html")
    # A '>' in a quoted DOCTYPE identifier ends the DOCTYPE (the 2.15.3 put its policy in the body).
    (out / "html-doctype-quote.html").write_text(
        '<!DOCTYPE html PUBLIC "a>b" "c>d"><html><head><img src="https://attacker.example/q.png"></head></html>',
        encoding="utf-8",
    )
    case("html-doctype-quote.html")

    # Encodings no meta declares: Windows-1252, UTF-16 with a BOM, UTF-8.
    text = "<html><body><p>Été à la française : éàèçùôîïëœ — 12 € « guillemets »</p></body></html>"
    (out / "html-1252-nometa.html").write_bytes(text.replace("—", "-").encode("windows-1252"))
    case("html-1252-nometa.html")
    (out / "html-utf16-bom.html").write_bytes(b"\xff\xfe" + ("<!DOCTYPE html>" + text).encode("utf-16-le"))
    case("html-utf16-bom.html")
    (out / "html-utf8-nometa.html").write_bytes(text.encode("utf-8"))
    case("html-utf8-nometa.html")

    # Deep nesting: 200 000 open <div>, nothing closed (1 MB).
    _write(out / "html-deep-nesting.html", b"<div>", 1_000_000, prefix=b"<!doctype html><body>")
    case("html-deep-nesting.html", max_millis=5000)
    # One line at the cap: read whole, its colours found, its source indexed.
    _write(out / "html-one-line-at-cap.html", b"<b style=color:#123456>x</b>", HTML_VIEWER_CAP, prefix=b"<p>")
    case("html-one-line-at-cap.html", max_millis=15000)
    # One line of 50 MiB: over the cap, refused before it is read.
    _write(out / "html-one-line-50mib.html", b"<i>a</i>", 50 * MIB, prefix=b"<p>")
    case("html-one-line-50mib.html", "too_large", 2000)
    # Not HTML at all, under an HTML name; and nothing at all.
    with open(out / "html-random-bytes.html", "wb") as handle:
        handle.write(os.urandom(MIB))
    case("html-random-bytes.html")
    (out / "html-empty.html").write_bytes(b"")
    case("html-empty.html", max_millis=2000)
    return cases
