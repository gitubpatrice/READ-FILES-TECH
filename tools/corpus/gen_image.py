"""Hostile files for the image viewer (ViewerKind.IMAGE), generated where corpus.py builds the corpus.

Each case: the file's name, its viewer, the outcome expected, the time budget on the device. The load is the
screen's (ImagePages.decode, CorpusCheck): the caps, the header, then the decode for a page of 1080 x 2400.
"""
import random
import struct
import zlib
from pathlib import Path

MIB = 1024 * 1024
IMAGE_FILE_CAP = 100 * MIB  # FileCaps.IMAGE_FILE
HEADER_BYTES = 64 * 1024  # ImageBounds.HEADER_BYTES
HERE = Path(__file__).resolve().parent
PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"


def _chunk(kind, data):
    return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))


def _ihdr(width, height, color_type=0):
    # 8 bits a sample; colour type 0 = grey, 2 = RGB; no interlace.
    return _chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, color_type, 0, 0, 0))


def _png(width, height, idat=b"", color_type=0):
    """A PNG whose header announces [width] x [height], whatever its data: what a probe reads."""
    return PNG_SIGNATURE + _ihdr(width, height, color_type) + _chunk(b"IDAT", idat or zlib.compress(b"\0" * 2)) + _chunk(b"IEND", b"")


def _zeros_stream(handle, compressed_bytes):
    """Writes IDAT chunks holding ONE zlib stream of zeros, [compressed_bytes] long once compressed.

    Compressing ~90 GB of zeros would take minutes: a raw deflate of 64 MiB of zeros, ended by a full
    flush, is byte-aligned and independent of what came before it — so the same bytes repeated are a
    valid stream of N x 64 MiB zeros. Its Adler-32 is computed, not run: for zeros, a stays 1 and b is
    the length modulo 65521.
    """
    piece_zeros = 64 * MIB

    def piece():
        deflate = zlib.compressobj(9, zlib.DEFLATED, -15)
        return deflate.compress(b"\0" * piece_zeros) + deflate.flush(zlib.Z_FULL_FLUSH)

    block = piece()
    assert block == piece(), "a flushed piece must not depend on the compressor's state"
    repeats = max(1, compressed_bytes // len(block))
    total = repeats * piece_zeros
    adler = ((total % 65521) << 16) | 1
    # 78 DA: deflate, 32 KiB window, best compression; then the pieces, an empty final block, the Adler-32.
    stream_parts = [b"\x78\xda"] + [block] * repeats + [b"\x03\x00", struct.pack(">I", adler)]
    for part in stream_parts:
        handle.write(_chunk(b"IDAT", part))
    return total


def _bmp_top_down(width, height):
    """A valid 24-bit BMP whose height is NEGATIVE: its rows from the top down (BITMAPINFOHEADER)."""
    row = bytes((x * 4) % 256 for x in range(width * 3))
    row += b"\0" * ((4 - len(row) % 4) % 4)
    pixels = row * height
    header = struct.pack("<IiiHHIIiiII", 40, width, -height, 1, 24, 0, len(pixels), 2835, 2835, 0, 0)
    file_header = b"BM" + struct.pack("<IHHI", 14 + len(header) + len(pixels), 0, 0, 14 + len(header))
    return file_header + header + pixels


def _jpeg_frame_beyond_header(source):
    """A real JPEG whose frame now lies past the first 64 KiB, announcing 30 000 x 30 000.

    Only the decoder's own reading of the header can refuse it — the probe reads 64 KiB (ImageBounds).
    30 000, not 65 535: libjpeg refuses any side over 65 500 itself, and the case would test it, not us.
    """
    data = bytearray(source)
    sof = data.index(b"\xff\xc0")
    struct.pack_into(">HH", data, sof + 5, 30000, 30000)
    filler = b"\xff\xef" + struct.pack(">H", 65535) + b"\0" * 65533  # APP15, the largest segment there is
    return bytes(data[:2]) + filler + bytes(data[2:])


def generate(out):
    cases = []

    def case(name, expect, max_millis=15000):
        cases.append({"file": name, "viewer": "IMAGE", "expect": expect, "maxMillis": max_millis})

    # The decompression bomb of the brief: a few hundred bytes announcing 100 000 x 100 000 (40 GB of RGBA).
    (out / "image-png-100000x100000.png").write_bytes(_png(100000, 100000, color_type=2))
    case("image-png-100000x100000.png", "too_many_pixels", 2000)
    # Both sides at the limit, 144 Mpx in all: the pixel cap, not the side one, refuses it.
    (out / "image-png-12000x12000.png").write_bytes(_png(12000, 12000, color_type=2))
    case("image-png-12000x12000.png", "too_many_pixels", 2000)
    # One pixel, and a zlib stream of zeros 90 MB long (~90 GB once inflated): the decoder must stop at the
    # end of the image, not inflate the rest. Under the file cap, so it reaches the decoder.
    with open(out / "image-png-1x1-90mb-stream.png", "wb") as handle:
        handle.write(PNG_SIGNATURE + _ihdr(1, 1))
        _zeros_stream(handle, 90 * 1000 * 1000)
        handle.write(_chunk(b"IEND", b""))
    case("image-png-1x1-90mb-stream.png", "ok")
    # One pixel, and 90 MB of zeros in 90 KB: inflated, it would hold 90 MB for one pixel.
    (out / "image-png-1x1-inflates-90mb.png").write_bytes(_png(1, 1, idat=zlib.compress(b"\0" * (90 * 1000 * 1000), 9)))
    case("image-png-1x1-inflates-90mb.png", "ok", 5000)
    # A real JPEG (System.Drawing) cut in half: shown as far as it goes, the rest blank — as BitmapFactory.
    jpeg = (HERE / "image" / "system-drawing.jpg").read_bytes()
    (out / "image-jpeg-truncated.jpg").write_bytes(jpeg[: len(jpeg) // 2])
    case("image-jpeg-truncated.jpg", "ok", 3000)
    # A frame the probe cannot see: past 64 KiB, announcing 30 000 x 30 000. The platform's header refuses it.
    beyond = _jpeg_frame_beyond_header(jpeg)
    assert beyond.index(b"\xff\xc0") > HEADER_BYTES
    (out / "image-jpeg-frame-past-64k.jpg").write_bytes(beyond)
    case("image-jpeg-frame-past-64k.jpg", "too_many_pixels", 2000)
    # A negative height is the order of the rows, not a damaged file: shown, the right way up.
    (out / "image-bmp-negative-height.bmp").write_bytes(_bmp_top_down(64, 48))
    case("image-bmp-negative-height.bmp", "ok", 2000)
    # Ten bytes of GIF announcing 65 535 x 65 535 (the 2.15.x hole of a global threshold).
    (out / "image-gif-10-bytes.gif").write_bytes(b"GIF89a" + struct.pack("<HH", 65535, 65535))
    case("image-gif-10-bytes.gif", "too_many_pixels", 2000)
    # A VP8L of 25 bytes announcing 16 384 x 16 384 (the 2.15.x hole of the WebP branch).
    bits = (16383 & 0x3FFF) | ((16383 & 0x3FFF) << 14)
    vp8l = b"RIFF" + struct.pack("<I", 17) + b"WEBPVP8L" + struct.pack("<I", 5) + b"\x2f" + struct.pack("<I", bits)
    (out / "image-webp-vp8l-25-bytes.webp").write_bytes(vp8l)
    case("image-webp-vp8l-25-bytes.webp", "too_many_pixels", 2000)
    # Not an image at all, under an image's name (a seed: the same bytes on every run).
    noise = random.Random(4).randbytes(256 * 1024)
    assert noise[:2] != b"\xff\xd8"
    (out / "image-random-bytes.jpg").write_bytes(noise)
    case("image-random-bytes.jpg", "unreadable", 2000)
    # An empty file.
    (out / "image-empty.png").write_bytes(b"")
    case("image-empty.png", "unreadable", 2000)
    # One byte over the cap: refused before anything is read, whatever it holds.
    with open(out / "image-over-cap.png", "wb") as handle:
        head = _png(1, 1)
        handle.write(head)
        left = IMAGE_FILE_CAP + 1 - len(head)
        zeros = b"\0" * MIB
        while left > 0:
            handle.write(zeros[: min(len(zeros), left)])
            left -= min(len(zeros), left)
    case("image-over-cap.png", "too_large", 2000)
    return cases
