"""Hostile files for the Markdown and JSON viewers (ViewerKind.MARKDOWN, ViewerKind.JSON), generated where
corpus.py builds the corpus.

A JSON that is not one, or past the caps, opens as its text, and a Markdown past its rendering caps as its
source: both are the outcome "ok" — what matters is the time, the heap, and that nothing hangs.
Each case: the file's name, its viewer, the outcome expected, the time budget on the device.
"""
import os

MIB = 1024 * 1024
TEXT_VIEWER_CAP = 50 * MIB  # FileCaps.TEXT_VIEWER, the cap of both viewers
JSON_VALUES = 2_000_000  # FileCaps.JSON_VALUES
JSON_DEPTH = 1_000  # FileCaps.JSON_DEPTH
MARKDOWN_RENDERED = 1 * MIB  # FileCaps.MARKDOWN_RENDERED
MARKDOWN_TABLE_CELLS = 100_000  # FileCaps.MARKDOWN_TABLE_CELLS
MARKDOWN_OPEN_BRACKETS = 1_000  # FileCaps.MARKDOWN_OPEN_BRACKETS


def _write(path, chunk, total, head=b"", tail=b""):
    """[head] once, then [chunk] repeated, then [tail]: [total] bytes in all."""
    with open(path, "wb") as handle:
        handle.write(head)
        total -= len(head) + len(tail)
        while total > 0:
            piece = chunk[: min(len(chunk), total)]
            handle.write(piece)
            total -= len(piece)
        handle.write(tail)


def _json(out, case):
    # One byte over the cap: refused before anything is read.
    _write(out / "json-over-cap.json", b"[0,0,0,0,0,0,0,0],\n", TEXT_VIEWER_CAP + 1)
    case("json-over-cap.json", "JSON", "too_large", 2000)
    # Dart's recursive json.decode overflowed its stack on these: a counter here, refused at its depth.
    _write(out / "json-million-deep.json", b"[", 1_000_000)
    case("json-million-deep.json", "JSON", "ok")
    _write(out / "json-cap-of-brackets.json", b"[", TEXT_VIEWER_CAP)
    case("json-cap-of-brackets.json", "JSON", "ok")
    _write(out / "json-deepest.json", b"[", 2 * JSON_DEPTH, tail=b"]" * JSON_DEPTH)
    case("json-deepest.json", "JSON", "ok", 2000)
    # Quotes: "" is a string, the third quote is where it stops being JSON.
    _write(out / "json-cap-of-quotes.json", b'"', TEXT_VIEWER_CAP)
    case("json-cap-of-quotes.json", "JSON", "ok")
    # One string of 50 MiB: one row, shown cut.
    _write(out / "json-one-string-at-cap.json", b"a", TEXT_VIEWER_CAP, head=b'["', tail=b'"]')
    case("json-one-string-at-cap.json", "JSON", "ok")
    # As many values as the cap, in 50 MiB: the heap kept at the cap (87 MB on the JVM).
    count = JSON_VALUES - 1
    value = b'"' + b"a" * ((TEXT_VIEWER_CAP - 2) // count - 3) + b'",'
    with open(out / "json-at-cap-values.json", "wb") as handle:
        handle.write(b"[" + value * (count - 1) + value[:-1] + b"]")
    case("json-at-cap-values.json", "JSON", "ok")
    # 26 million values: counted up to the cap, then shown as text.
    _write(out / "json-cap-of-zeros.json", b"0,", TEXT_VIEWER_CAP - 2, head=b"[", tail=b"0]")
    case("json-cap-of-zeros.json", "JSON", "ok")
    # Numbers of 10 000 digits: Dart made Infinity of them; shown as written.
    with open(out / "json-long-numbers.json", "wb") as handle:
        handle.write(b"[" + b",".join([b"9" * 10_000, b"-" + b"1" * 10_000 + b".5e400"] * 50) + b"]")
    case("json-long-numbers.json", "JSON", "ok", 2000)
    (out / "json-duplicate-keys.json").write_bytes(b'{"k": 1, "k": 2, "k": {"k": [3]}}')
    case("json-duplicate-keys.json", "JSON", "ok", 2000)
    # Not valid UTF-8: read as Windows-1252, as the text viewer does.
    (out / "json-invalid-utf8.json").write_bytes(b'{"a": "\xc3\x28 \xff\xfe \xe9t\xe9"}')
    case("json-invalid-utf8.json", "JSON", "ok", 2000)
    (out / "json-bom.json").write_bytes(b'\xef\xbb\xbf{"bom": true}')
    case("json-bom.json", "JSON", "ok", 2000)
    (out / "json-nan-infinity.json").write_bytes(b'{"a": NaN, "b": Infinity, "c": -Infinity}')
    case("json-nan-infinity.json", "JSON", "ok", 2000)
    (out / "json-utf16-odd.json").write_bytes(b"\xff\xfe" + '{"odd": 1}'.encode("utf-16-le") + b"\x41")
    case("json-utf16-odd.json", "JSON", "ok", 2000)
    with open(out / "json-random-bytes.json", "wb") as handle:
        handle.write(os.urandom(MIB))
    case("json-random-bytes.json", "JSON", "ok", 2000)
    (out / "json-empty.json").write_bytes(b"")
    case("json-empty.json", "JSON", "ok", 2000)


def _markdown(out, case):
    _write(out / "md-over-cap.md", b"# title\n\ntext\n\n", TEXT_VIEWER_CAP + 1)
    case("md-over-cap.md", "MARKDOWN", "too_large", 2000)
    # 50 MiB of `*`: past the rendering cap, its source only.
    _write(out / "md-cap-of-stars.md", b"*", TEXT_VIEWER_CAP)
    case("md-cap-of-stars.md", "MARKDOWN", "ok")
    # Rendered, at the rendering cap exactly.
    _write(out / "md-render-cap-stars.md", b"*", MARKDOWN_RENDERED)
    case("md-render-cap-stars.md", "MARKDOWN", "ok")
    _write(out / "md-render-cap-quotes.md", b">", MARKDOWN_RENDERED)
    case("md-render-cap-quotes.md", "MARKDOWN", "ok")
    _write(out / "md-render-cap-links.md", b"[a](https://example.org/b) ", MARKDOWN_RENDERED)
    case("md-render-cap-links.md", "MARKDOWN", "ok")
    _write(out / "md-render-cap-emphasis.md", b"*a _b ", MARKDOWN_RENDERED)
    case("md-render-cap-emphasis.md", "MARKDOWN", "ok")
    with open(out / "md-nested-lists.md", "wb") as handle:
        handle.write(b"\n".join(b"  " * level + b"- item" for level in range(500)))
    case("md-nested-lists.md", "MARKDOWN", "ok", 3000)
    # GFM pads every row to the header: a wide header over short lines, refused before parsing.
    columns = 1000
    header = b"|a" * columns + b"|\n" + b"|-" * columns + b"|\n"
    with open(out / "md-table-bomb.md", "wb") as handle:
        handle.write(header + b"x\n" * (MARKDOWN_TABLE_CELLS // columns))
    case("md-table-bomb.md", "MARKDOWN", "ok", 3000)
    # commonmark-java takes a time in the square of nested images: refused before parsing...
    depth = 100_000
    with open(out / "md-nested-images.md", "wb") as handle:
        handle.write(b"![" * depth + b"x" + b"](u)" * depth)
    case("md-nested-images.md", "MARKDOWN", "ok", 3000)
    # ...and rendered at the cap, paragraph after paragraph.
    paragraph = b"![" * MARKDOWN_OPEN_BRACKETS + b"x" + b"](u)" * MARKDOWN_OPEN_BRACKETS + b"\n\n"
    _write(out / "md-nested-images-at-cap.md", paragraph, MARKDOWN_RENDERED - MARKDOWN_RENDERED % len(paragraph))
    case("md-nested-images-at-cap.md", "MARKDOWN", "ok")
    # Images are never loaded, links never followed: the corpus check sees nothing written.
    (out / "md-images-and-links.md").write_bytes(
        b"![remote](https://example.org/pixel.png)\n\n![private](file:///data/data/com.readfilestech.read_files_tech/files/x)\n\n"
        b"![data](data:image/png;base64,iVBORw0KGgo=)\n\n[script](javascript:alert(1)) [file](file:///sdcard/x) [web](https://example.org)\n"
    )
    case("md-images-and-links.md", "MARKDOWN", "ok", 2000)
    with open(out / "md-random-bytes.md", "wb") as handle:
        handle.write(os.urandom(MIB))
    case("md-random-bytes.md", "MARKDOWN", "ok")
    (out / "md-utf16.md").write_bytes(b"\xff\xfe" + "# Été\n\n| a | b |\n|---|---|\n| é | ü |\n".encode("utf-16-le"))
    case("md-utf16.md", "MARKDOWN", "ok", 2000)
    (out / "md-empty.md").write_bytes(b"")
    case("md-empty.md", "MARKDOWN", "ok", 2000)


def generate(out):
    cases = []

    def case(name, viewer, expect, max_millis=15000):
        cases.append({"file": name, "viewer": viewer, "expect": expect, "maxMillis": max_millis})

    _json(out, case)
    _markdown(out, case)
    return cases
