"""Makes the inputs of the image tools' oracle (Compress, Remove EXIF): pictures carrying GPS tags, never a
personal photo. Written by Pillow — an independent writer, not the code that reads them.

Usage:  python -I tools/oracles/make_image_inputs.py
Writes tools/corpus/image-tools/ (no corpus.json there: corpus.py, the viewers' run, skips the folder):

  pillow-gps-photo.jpg         2400 x 1600, JPEG q95, a photo-like picture (gradients, shapes, grain):
                               EXIF IFD0 Make/Model/Software, EXIF DateTimeOriginal, a GPS IFD
                               (latitude, longitude), and an XMP packet.
  pillow-gps-orientation-6.jpg the same tags, stored 1600 x 1200 with EXIF Orientation 6: shown upright it
                               is 1200 x 1600, "TOP" and the arrow at the top.
  pillow-gps.png               800 x 600 RGBA with transparent corners, an eXIf chunk (GPS) and XMP.
  pillow-gps.webp              800 x 600 lossy, EXIF (GPS) and XMP chunks.

The tests of the EXIF reader read these files too (ImageMetadataTest).
"""
import pathlib
import random
import sys

from PIL import Image, ImageDraw

HERE = pathlib.Path(__file__).resolve().parent
OUT = HERE.parent / "corpus" / "image-tools"

XMP = (
    b'<?xpacket begin="" id="W5M0MpCehiHzreSzNTczkc9d"?>'
    b'<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">'
    b'<rdf:Description xmlns:dc="http://purl.org/dc/elements/1.1/" dc:creator="Files Tech test"/>'
    b"</rdf:RDF></x:xmpmeta>"
    b'<?xpacket end="w"?>'
)


def tags(orientation=1):
    exif = Image.Exif()
    exif[0x010F] = "Files Tech Test"  # Make
    exif[0x0110] = "Pillow camera"  # Model
    exif[0x0131] = "make_image_inputs.py"  # Software
    if orientation != 1:
        exif[0x0112] = orientation
    exif.get_ifd(0x8769)[0x9003] = "2026:10:14 12:34:56"  # DateTimeOriginal
    gps = exif.get_ifd(0x8825)
    gps[1] = "N"  # GPSLatitudeRef
    gps[2] = (48.0, 51.0, 24.0)  # GPSLatitude
    gps[3] = "E"  # GPSLongitudeRef
    gps[4] = (2.0, 21.0, 3.0)  # GPSLongitude
    return exif


def photo(width, height, seed):
    """Gradients, discs and grain: compresses like a photo, not like a flat drawing."""
    rng = random.Random(seed)
    img = Image.linear_gradient("L").resize((width, height)).convert("RGB")
    sky = Image.new("RGB", (width, height), (40, 110, 190))
    img = Image.blend(img, sky, 0.55)
    draw = ImageDraw.Draw(img)
    for _ in range(60):
        x, y, r = rng.randrange(width), rng.randrange(height), rng.randrange(10, width // 8)
        draw.ellipse((x - r, y - r, x + r, y + r), fill=(rng.randrange(256), rng.randrange(256), rng.randrange(256)))
    grain = Image.effect_noise((width, height), 28).convert("RGB")
    return Image.blend(img, grain, 0.18)


def upright_marked(width, height):
    """A picture whose top is unmistakable: TOP written there, an arrow pointing to it, a red top band."""
    img = photo(width, height, seed=6)
    draw = ImageDraw.Draw(img)
    draw.rectangle((0, 0, width, height // 10), fill=(220, 20, 20))
    cx = width // 2
    draw.polygon([(cx, height // 8), (cx - width // 5, height // 3), (cx + width // 5, height // 3)], fill=(255, 255, 255))
    draw.rectangle((cx - width // 14, height // 3, cx + width // 14, height * 3 // 4), fill=(255, 255, 255))
    draw.text((cx - 12, height // 20), "TOP", fill=(255, 255, 255))
    return img


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    photo(2400, 1600, seed=1).save(OUT / "pillow-gps-photo.jpg", quality=95, exif=tags(), xmp=XMP)
    # Orientation 6: the stored picture must turn a quarter clockwise to show upright, so it is stored
    # turned a quarter counter-clockwise (Pillow's ROTATE_90).
    upright = upright_marked(1200, 1600)
    upright.transpose(Image.Transpose.ROTATE_90).save(OUT / "pillow-gps-orientation-6.jpg", quality=92, exif=tags(6))
    clear = photo(800, 600, seed=3).convert("RGBA")
    mask = Image.new("L", clear.size, 255)
    ImageDraw.Draw(mask).rectangle((0, 0, 120, 120), fill=0)
    clear.putalpha(mask)
    clear.save(OUT / "pillow-gps.png", exif=tags(), xmp=XMP)
    photo(800, 600, seed=4).save(OUT / "pillow-gps.webp", quality=85, exif=tags(), xmp=XMP)
    for path in sorted(OUT.iterdir()):
        print("%-30s %8d bytes" % (path.name, path.stat().st_size))


if __name__ == "__main__":
    sys.exit(main())
