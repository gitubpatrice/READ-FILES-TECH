"""Hostile files for the text viewer (ViewerKind.TEXT), generated where corpus.py builds the corpus.

Each case: the file's name, its viewer, the outcome expected, the time budget on the device.
"""
import os

MIB = 1024 * 1024
TEXT_VIEWER_CAP = 50 * MIB  # FileCaps.TEXT_VIEWER


def _write(path, chunk, total, prefix=b""):
    """[prefix] once (a BOM), then [chunk] repeated: [total] bytes in all."""
    with open(path, "wb") as handle:
        handle.write(prefix)
        total -= len(prefix)
        while total > 0:
            piece = chunk[: min(len(chunk), total)]
            handle.write(piece)
            total -= len(piece)


def generate(out):
    cases = []

    def case(name, expect, max_millis=15000):
        cases.append({"file": name, "viewer": "TEXT", "expect": expect, "maxMillis": max_millis})

    # One byte over the cap: refused before anything is read.
    _write(out / "text-over-cap.log", b"0123456789abcde\n" * 4096, TEXT_VIEWER_CAP + 1)
    case("text-over-cap.log", "too_large", 2000)
    # The cap exactly, one endless line (a minified script): 12 800 rows, nothing laid out whole.
    _write(out / "text-at-cap-one-line.js", b"var a=1;" * 4096, TEXT_VIEWER_CAP)
    case("text-at-cap-one-line.js", "ok")
    # Ten million empty lines, then the cap's worst index: 52 million rows of one line feed each — an
    # index of every row took 200 MB (Claude review R9); one start in 16 takes 13.
    _write(out / "text-ten-million-lines.txt", b"\n" * 65536, 10_000_000)
    case("text-ten-million-lines.txt", "ok")
    _write(out / "text-cap-of-line-feeds.txt", b"\n" * 65536, TEXT_VIEWER_CAP)
    case("text-cap-of-line-feeds.txt", "ok")
    _write(out / "text-cap-of-crlf.txt", b"\r\n" * 32768, TEXT_VIEWER_CAP)
    case("text-cap-of-crlf.txt", "ok")
    # UTF-16 made only of lone low surrogates: no row boundary was ever found, one row of 50 MiB (R9).
    _write(out / "text-utf16-lone-surrogates.txt", b"\x00\xdc" * 32768, TEXT_VIEWER_CAP, prefix=b"\xff\xfe")
    case("text-utf16-lone-surrogates.txt", "ok")
    _write(out / "text-utf16be-lone-surrogates.txt", b"\xdc\x00" * 32768, TEXT_VIEWER_CAP, prefix=b"\xfe\xff")
    case("text-utf16be-lone-surrogates.txt", "ok")
    # Not text at all, under a text name: shown as Windows-1252, never a crash.
    with open(out / "text-random-bytes.txt", "wb") as handle:
        handle.write(os.urandom(MIB))
    case("text-random-bytes.txt", "ok")
    # A UTF-16 BOM and an odd number of bytes: the last half unit is replaced, not a crash.
    with open(out / "text-utf16-odd.txt", "wb") as handle:
        handle.write(b"\xff\xfe" + "odd\n".encode("utf-16-le") + b"\x41")
    case("text-utf16-odd.txt", "ok", 2000)
    # Nothing but NUL bytes.
    _write(out / "text-nul.ini", b"\x00" * 4096, MIB)
    case("text-nul.ini", "ok", 2000)
    # An empty file.
    (out / "text-empty.txt").write_bytes(b"")
    case("text-empty.txt", "ok", 2000)
    return cases
