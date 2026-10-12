"""Hostile files for the CSV viewer (ViewerKind.CSV), generated where corpus.py builds the corpus.

Each case: the file's name, its viewer, the outcome expected, the time budget on the device.
"""
import os

MIB = 1024 * 1024
CSV_FILE_CAP = 50 * MIB  # FileCaps.CSV_FILE


def _write(path, chunk, total, head=b""):
    with open(path, "wb") as handle:
        handle.write(head)
        total -= len(head)
        while total > 0:
            piece = chunk[: min(len(chunk), total)]
            handle.write(piece)
            total -= len(piece)


def generate(out):
    cases = []

    def case(name, expect, max_millis=15000):
        cases.append({"file": name, "viewer": "CSV", "expect": expect, "maxMillis": max_millis})

    # One byte over the cap: refused before anything is read.
    _write(out / "csv-over-cap.csv", b"a;b;c\r\n" * 4096, CSV_FILE_CAP + 1)
    case("csv-over-cap.csv", "too_large", 2000)
    # The cap exactly, one column of one byte: 26 million rows, the first 1 048 576 shown (FileCaps.CSV_ROWS),
    # the bytes of the others let go.
    _write(out / "csv-one-column-one-byte.csv", b"x\n" * 65536, CSV_FILE_CAP)
    case("csv-one-column-one-byte.csv", "ok")
    # The cap exactly, ten short cells a row (about a million rows): the heap the table keeps for a full file.
    header = b";".join(b"c%d" % i for i in range(10)) + b"\r\n"
    rows = b"".join(b"%d;ab%d;%d,%d;x;yz;%d;q;%d;w;e\r\n" % (i, i % 97, i % 13, i % 7, i * 7, i % 5) for i in range(1000))
    _write(out / "csv-at-cap-short-cells.csv", rows, CSV_FILE_CAP, head=header)
    case("csv-at-cap-short-cells.csv", "ok")
    # Nothing but line breaks, 50 MiB of them: 52 million empty rows, none kept — an empty table.
    _write(out / "csv-empty-lines.csv", b"\n" * 65536, CSV_FILE_CAP)
    case("csv-empty-lines.csv", "ok")
    # A single line of 5 million separators: one row of empty fields, which is no row (csv 8's skipEmptyLines).
    _write(out / "csv-five-million-separators.csv", b"," * 65536, 5_000_000)
    case("csv-five-million-separators.csv", "ok")
    # 100 000 columns in one row: the first 16 384 shown (FileCaps.CSV_COLUMNS).
    with open(out / "csv-100000-columns.csv", "wb") as handle:
        handle.write(b",".join(b"c%d" % i for i in range(100_000)) + b"\n")
        handle.write(b",".join(b"%d" % i for i in range(100_000)) + b"\n")
    case("csv-100000-columns.csv", "ok", 5000)
    # A quote never closed: the second row's first cell is the rest of the file, 10 MiB, shown cut.
    _write(out / "csv-quote-never-closed.csv", b"a;b\n\"never closed;" + b"1;2\n" * (10 * MIB // 4), 10 * MIB)
    case("csv-quote-never-closed.csv", "ok", 5000)
    # Not text at all, under a CSV name: read as Windows-1252, a table of whatever it holds, never a crash.
    with open(out / "csv-random-bytes.csv", "wb") as handle:
        handle.write(os.urandom(MIB))
    case("csv-random-bytes.csv", "ok", 5000)
    # A UTF-16 BOM and an odd number of bytes: the last half unit is U+FFFD in the last cell, not a crash.
    with open(out / "csv-utf16-odd.csv", "wb") as handle:
        handle.write(b"\xff\xfe" + "a;b\r\n1;2".encode("utf-16-le") + b"\x41")
    case("csv-utf16-odd.csv", "ok", 2000)
    return cases
