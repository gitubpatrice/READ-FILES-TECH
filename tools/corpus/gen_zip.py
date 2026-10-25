"""Hostile archives for the ZIP viewer (ViewerKind.ZIP), generated where corpus.py builds the corpus.

The corpus loads what the screen shows first: the LISTING (ZipArchive.open). An archive whose entries
are refused one by one (encrypted, a link, a name leaving the folder, a size that lies) still lists:
its outcome is `ok`, and what extraction does with each entry is tested on the JVM
(app/src/test/.../data/viewer/zip). Python's zipfile writes what it can; the rest is written byte by
byte (`_raw`): zipfile turns `\\` into `/` on Windows and cuts a name at its NUL.

Each case: the file's name, its viewer, the outcome expected, the time budget on the device.
"""
import random
import struct
import zipfile
import zlib

MIB = 1024 * 1024
ZIP_VIEWER_CAP = 500 * MIB  # FileCaps.ZIP_VIEWER
ENTRY_CAP = 200 * MIB  # FileCaps.ZIP_ENTRY_DECOMPRESSED
MAX_ENTRIES = 200_000  # FileCaps.ZIP_VIEWER_ENTRIES
MAX_DIRECTORY = 32 * MIB  # FileCaps.ZIP_VIEWER_DIRECTORY


def _deflate(data):
    compressor = zlib.compressobj(6, zlib.DEFLATED, -15)
    return compressor.compress(data) + compressor.flush()


def _raw(path, entries, shares=None):
    """A ZIP written field by field. entries: dicts with name (bytes), data (bytes), and optionally
    method (0/8), size (a declared uncompressed size), attributes, made_by, utf8, zip64.
    shares: {index: other index} — that record points at the other's local header."""
    shares = shares or {}
    body = bytearray()
    written = []
    for entry in entries:
        method = entry.get("method", 8)
        stored = _deflate(entry["data"]) if method == 8 else entry["data"]
        crc = zlib.crc32(entry["data"])
        size = entry.get("size", len(entry["data"]))
        flags = 0x800 if entry.get("utf8", True) else 0
        offset = len(body)
        body += struct.pack("<IHHHHHIIIHH", 0x04034B50, 20, flags, method, 0, 0, crc, len(stored), size, len(entry["name"]), 0)
        body += entry["name"] + stored
        written.append((entry, offset, crc, len(stored), size, flags, method))
    start = len(body)
    for i, (entry, offset, crc, compressed, size, flags, method) in enumerate(written):
        at = written[shares[i]][1] if i in shares else offset
        extra = b""
        if entry.get("zip64"):
            extra = struct.pack("<HHQQQ", 1, 24, size, compressed, at)
            size = compressed = at = 0xFFFFFFFF
        body += struct.pack(
            "<IHHHHHHIIIHHHHHII", 0x02014B50, entry.get("made_by", 3 << 8 | 30), 45 if extra else 20, flags, method, 0, 0,
            crc, compressed, size, len(entry["name"]), len(extra), 0, 0, 0, entry.get("attributes", 0), at,
        )
        body += entry["name"] + extra
    count = min(len(entries), 0xFFFF)  # 16 bits: an old writer's count modulo 65 536, the records walked anyway
    body += struct.pack("<IHHHHIIH", 0x06054B50, 0, 0, count, count, len(body) - start, start, 0)
    with open(path, "wb") as handle:
        handle.write(body)


def _lie(path, real, declared):
    """Rewrites the uncompressed size [real] into [declared] in both headers (the Dart suite's forgeZip)."""
    with open(path, "r+b") as handle:
        data = bytearray(handle.read())
        for signature, field in ((b"PK\x03\x04", 22), (b"PK\x01\x02", 24)):
            at = data.find(signature)
            while at >= 0:
                if struct.unpack_from("<I", data, at + field)[0] == real:
                    struct.pack_into("<I", data, at + field, declared)
                at = data.find(signature, at + 1)
        handle.seek(0)
        handle.write(data)


def _zeros_entry(archive, name, total):
    """An entry of [total] zero bytes, deflated, written by pieces: never held whole."""
    info = zipfile.ZipInfo(name)
    info.compress_type = zipfile.ZIP_DEFLATED  # a ZipInfo given to open() keeps its own: STORED by default
    with archive.open(info, "w") as entry:
        chunk = bytes(MIB)
        left = total
        while left > 0:
            entry.write(chunk[: min(MIB, left)])
            left -= MIB


def generate(out):
    cases = []

    def case(name, expect, max_millis=5000):
        cases.append({"file": name, "viewer": "ZIP", "expect": expect, "maxMillis": max_millis})

    # A bomb whose headers lie: 10 MB of zeros declaring 1 KB. Listed; read, it stops at 1 KB + 1 byte.
    with zipfile.ZipFile(out / "zip-bomb-lying.zip", "w", zipfile.ZIP_DEFLATED) as archive:
        _zeros_entry(archive, "bombe.bin", 10_000_000)
    _lie(out / "zip-bomb-lying.zip", 10_000_000, 1024)
    case("zip-bomb-lying.zip", "ok", 2000)

    # An entry that inflates past the 200 MiB of an entry: honest (refused by its record), and lying.
    with zipfile.ZipFile(out / "zip-bomb-over-entry-cap.zip", "w", zipfile.ZIP_DEFLATED) as archive:
        _zeros_entry(archive, "gros.bin", ENTRY_CAP + MIB)
        archive.writestr("petit.txt", "petit")
    case("zip-bomb-over-entry-cap.zip", "ok", 2000)
    with zipfile.ZipFile(out / "zip-bomb-over-entry-cap-lying.zip", "w", zipfile.ZIP_DEFLATED) as archive:
        _zeros_entry(archive, "gros.bin", ENTRY_CAP + MIB)
    _lie(out / "zip-bomb-over-entry-cap-lying.zip", ENTRY_CAP + MIB, 1024)
    case("zip-bomb-over-entry-cap-lying.zip", "ok", 2000)

    # Names leaving the folder, each listed and refused on extraction; one ordinary name.
    names = [b"../../evil.txt", b"/abs/evil.txt", b"C:\\evil.txt", b"..\\..\\evil.txt", b"a/../../evil.txt",
             b"nul\x00name.txt", b"./dot.txt", b"line\nbreak.txt", b"ok/normal.txt"]
    _raw(out / "zip-slip-names.zip", [{"name": n, "data": b"x"} for n in names])
    case("zip-slip-names.zip", "ok", 2000)

    # A symbolic link (Unix, lrwxrwxrwx) and a file "under" it: never a link created.
    with zipfile.ZipFile(out / "zip-symlink.zip", "w") as archive:
        link = zipfile.ZipInfo("lien")
        link.create_system = 3
        link.external_attr = 0o120777 << 16
        archive.writestr(link, "../../../dehors")
        archive.writestr("lien/evade.txt", "x")
    case("zip-symlink.zip", "ok", 2000)

    # 70 000 entries: past the 65 535 of a classic end record, so zipfile writes a ZIP64 end record.
    with zipfile.ZipFile(out / "zip-70000-entries.zip", "w", zipfile.ZIP_STORED) as archive:
        for i in range(70_000):
            archive.writestr("d%02d/%06d" % (i // 1000, i), b"x")
    case("zip-70000-entries.zip", "ok", 15000)

    # One entry more than a listing holds: refused while the records are walked, in words.
    _raw(out / "zip-too-many-entries.zip", [{"name": b"%07d" % i, "data": b"", "method": 0} for i in range(MAX_ENTRIES + 1)])
    case("zip-too-many-entries.zip", "too_many_items", 15000)

    # An end record declaring a central directory above what a listing may cost: refused before reading it.
    with open(out / "zip-directory-too-large.zip", "wb") as handle:
        size = MAX_DIRECTORY + MIB
        for _ in range(size // MIB):
            handle.write(bytes(MIB))
        handle.write(struct.pack("<IHHHHIIH", 0x06054B50, 0, 0, 0, 0, size, 0, 0))
    case("zip-directory-too-large.zip", "too_many_items", 2000)

    # One byte over the cap of the viewer: refused before anything is read.
    with open(out / "zip-over-cap.zip", "wb") as handle:
        left = ZIP_VIEWER_CAP + 1
        while left > 0:
            handle.write(bytes(min(MIB, left)))
            left -= MIB
    case("zip-over-cap.zip", "too_large", 2000)

    # A central directory cut short, its end record kept: the offsets no longer hold.
    with zipfile.ZipFile(out / "zip-truncated-directory.zip", "w", zipfile.ZIP_DEFLATED) as archive:
        for name in ("a.txt", "b.txt", "c.txt"):
            archive.writestr(name, name * 100)
    data = (out / "zip-truncated-directory.zip").read_bytes()
    end = data.rfind(b"PK\x05\x06")
    (out / "zip-truncated-directory.zip").write_bytes(data[: end - 10] + data[end:])
    case("zip-truncated-directory.zip", "unreadable", 2000)

    # Not an archive: random bytes (seeded), and the 2.15.3's case — PK\3\4 then noise.
    noise = random.Random(7).randbytes(64 * 1024)
    assert b"PK\x05\x06" not in noise
    (out / "zip-random-bytes.zip").write_bytes(noise)
    case("zip-random-bytes.zip", "unreadable", 2000)
    (out / "zip-pk-then-noise.zip").write_bytes(b"PK\x03\x04" + bytes((i * 37 + 11) & 0xFF for i in range(4096)))
    case("zip-pk-then-noise.zip", "unreadable", 2000)

    # An empty archive is a valid one: listed empty, not refused.
    with zipfile.ZipFile(out / "zip-empty.zip", "w"):
        pass
    case("zip-empty.zip", "ok", 2000)

    # Entries sharing one block of data (the "better zip bomb" in small): listed, each refused.
    _raw(out / "zip-overlapping.zip", [{"name": b"noyau.bin", "data": bytes(MIB)}] + [{"name": b"copie%d.bin" % i, "data": bytes(MIB)} for i in range(3)],
         shares={1: 0, 2: 0, 3: 0})
    case("zip-overlapping.zip", "ok", 2000)

    # Backslashes for separators, as Windows PowerShell 5.1's Compress-Archive writes them (imitated here:
    # this worktree could not run PowerShell), and a traversal through them.
    _raw(out / "zip-backslash-separators.zip", [
        {"name": b"docs\\notes.md", "data": b"# notes"},
        {"name": b"docs\\sous-dossier\\a.txt", "data": b"a"},
        {"name": b"..\\..\\evil.txt", "data": b"x"},
    ])
    case("zip-backslash-separators.zip", "ok", 2000)

    # One name twice, two names differing by case, a file and a folder of one name.
    _raw(out / "zip-duplicates-and-case.zip", [
        {"name": b"double.txt", "data": b"premier"},
        {"name": b"double.txt", "data": b"second"},
        {"name": b"Lisez-moi.txt", "data": b"1"},
        {"name": b"LISEZ-MOI.TXT", "data": b"2"},
        {"name": b"a", "data": b"fichier"},
        {"name": b"a/b.txt", "data": b"dessous"},
    ])
    case("zip-duplicates-and-case.zip", "ok", 2000)

    # Sizes and offsets in ZIP64 blocks, names unflagged in code page 437 (as 7-Zip writes them).
    _raw(out / "zip-zip64-blocks.zip", [
        {"name": b"a.txt", "data": b"zip64", "zip64": True},
        {"name": "Straße.csv".encode("cp437"), "data": b"a;b", "utf8": False, "zip64": True},
    ])
    case("zip-zip64-blocks.zip", "ok", 2000)
    return cases
