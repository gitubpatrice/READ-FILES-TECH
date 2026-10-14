"""Oracle of PROPERTY of the image tools (Compress, Remove EXIF): Pillow — an independent reader, not the
platform encoder that wrote them — reads every output and checks what the port promises:

  * no metadata: `getexif()` empty, no GPS directory, no APP1 segment at all in a JPEG (neither EXIF nor
    XMP), no eXIf / XMP / raw-profile chunk in a PNG; an ICC profile is reported (a colour space's
    description, needed for a wide-gamut photo), not refused;
  * upright: the output looks like its source turned by the source's EXIF orientation
    (`ImageOps.exif_transpose`), and unlike the source as stored when that orientation turns it;
  * size: Remove EXIF keeps the upright size exactly; Compress gives min(width, --max-width) and the height
    in proportion (1 pixel of rounding), and, at --quality 60 or lower, a file smaller than its source.

Usage, after merging (the outputs are made on the phone, by the app):
  1. python -I tools/oracles/make_image_inputs.py, then copy tools/corpus/image-tools/* and
     tools/corpus/image/system-drawing*.{jpg,png} to the phone;
  2. run Remove EXIF on each, and Compress at quality 60, width 1920 (or the values you pass below);
  3. pull "Files Tech/Sans-EXIF" and "Files Tech/Compressions", then
     python -I tools/oracles/image_outputs.py --inputs <folder of the inputs> --outputs <pulled folder>
        --max-width 1920 --quality 60
Outputs are matched to their source by name: <base>_no_exif_<stamp>.<ext>, <base>_compressed_<stamp>.jpg.
Exit code 0 only if every output passes, and at least one was checked.

  python -I tools/oracles/image_outputs.py --self-test
checks the oracle itself: copies made by Pillow, good ones and ones carrying each defect (GPS kept, XMP
kept, not upright, too wide, larger than the source); each defect must be caught, each good copy pass.
"""
import argparse
import io
import pathlib
import re
import sys
import tempfile

from PIL import Image, ImageChops, ImageCms, ImageOps, ImageStat

NAME = re.compile(r"^(?P<base>.+)_(?P<tool>compressed|no_exif)_\d{8}_\d{6}(?:_\d+)?\.(?P<ext>jpg|png)$")
INPUT_EXTENSIONS = (".jpg", ".jpeg", ".png", ".webp", ".heic", ".heif", ".bmp", ".gif")
SIDE = 64
# Mean absolute difference (0-255) of two 64 x 64 greyscale thumbnails: a re-encoded copy of the same
# picture stays far under it; a picture on its side, far over.
SAME_PICTURE = 14.0


def thumb(img):
    if img.mode in ("RGBA", "LA", "P"):
        img = img.convert("RGBA")
        white = Image.new("RGBA", img.size, (255, 255, 255, 255))
        img = Image.alpha_composite(white, img)
    return img.convert("L").resize((SIDE, SIDE), Image.Resampling.BILINEAR)


def distance(a, b):
    return ImageStat.Stat(ImageChops.difference(thumb(a), thumb(b))).mean[0]


def metadata_problems(path, img):
    problems = []
    exif = img.getexif()
    if len(exif):
        problems.append("EXIF kept: %d tags" % len(exif))
    if exif.get_ifd(0x8825):
        problems.append("GPS directory kept")
    if img.format == "JPEG":
        app1 = [data[:32] for marker, data in getattr(img, "applist", []) if marker == "APP1"]
        if app1:
            problems.append("APP1 segment(s) kept: %s" % app1)
    for key in ("exif", "xmp", "XML:com.adobe.xmp", "Raw profile type exif", "Raw profile type APP1"):
        if key in img.info:
            problems.append("%s kept" % key)
    return problems


def icc_note(img):
    icc = img.info.get("icc_profile")
    if not icc:
        return "no ICC"
    try:
        description = ImageCms.getProfileDescription(ImageCms.ImageCmsProfile(io.BytesIO(icc))).strip()
    except (OSError, ImageCms.PyCMSError):
        description = "unreadable"
    return "ICC %d bytes (%s)" % (len(icc), description)


def check(source_path, output_path, tool, max_width, quality):
    """The problems of one output; an empty list is a pass."""
    problems = []
    with Image.open(source_path) as source, Image.open(output_path) as out:
        out.load()
        problems += metadata_problems(output_path, out)
        orientation = source.getexif().get(0x0112, 1)
        upright = ImageOps.exif_transpose(source)
        if tool == "no_exif":
            if out.size != upright.size:
                problems.append("size %s, upright source %s" % (out.size, upright.size))
        else:
            width = min(upright.width, max_width)
            height = round(upright.height * width / upright.width)
            if out.width != width or abs(out.height - height) > 1:
                problems.append("size %s, expected %dx%d" % (out.size, width, height))
            if out.format != "JPEG":
                problems.append("format %s, expected JPEG" % out.format)
            if quality is not None and quality <= 60 and output_path.stat().st_size >= source_path.stat().st_size:
                problems.append("not smaller: %d >= %d bytes" % (output_path.stat().st_size, source_path.stat().st_size))
        near = distance(out, upright)
        if near > SAME_PICTURE:
            problems.append("not the upright picture (distance %.1f)" % near)
        if orientation in (5, 6, 7, 8):
            stored = distance(out, source.resize(out.size))
            if stored <= near:
                problems.append("looks as stored, not upright (%.1f <= %.1f)" % (stored, near))
        note = icc_note(out)
    return problems, note


def find_source(inputs, base):
    matches = [p for p in inputs.iterdir() if p.suffix.lower() in INPUT_EXTENSIONS and p.stem == base]
    return matches[0] if len(matches) == 1 else None


def run(inputs, outputs, max_width, quality):
    checked = failed = 0
    for output_path in sorted(p for p in outputs.rglob("*") if p.is_file()):
        match = NAME.match(output_path.name)
        if not match:
            continue
        source_path = find_source(inputs, match["base"])
        if source_path is None:
            print("SKIP %s: no single source named %s.*" % (output_path.name, match["base"]))
            continue
        problems, note = check(source_path, output_path, match["tool"], max_width, quality)
        checked += 1
        failed += bool(problems)
        print("%s %s <- %s (%s)" % ("FAIL" if problems else "OK  ", output_path.name, source_path.name, note))
        for problem in problems:
            print("       " + problem)
    print("%d checked, %d failed" % (checked, failed))
    return 0 if checked and not failed else 1


def self_test():
    """Each defect, made by Pillow, must be caught; each good copy must pass."""
    here = pathlib.Path(__file__).resolve().parent
    corpus = here.parent / "corpus" / "image-tools"
    photo, turned = corpus / "pillow-gps-photo.jpg", corpus / "pillow-gps-orientation-6.jpg"
    if not photo.exists():
        sys.exit("run make_image_inputs.py first")
    xmp = b'<x:xmpmeta xmlns:x="adobe:ns:meta/"/>'
    with tempfile.TemporaryDirectory() as tmp:
        tmp = pathlib.Path(tmp)
        made_count = [0]

        def made(source, tool, transform, ext="jpg", **save):
            # Named as the app names them, a counter for the stamp's _N: every case its own file.
            made_count[0] += 1
            path = tmp / ("%s_%s_20261014_120000_%d.%s" % (source.stem, tool, made_count[0], ext))
            with Image.open(source) as img:
                copy = transform(img)
                # Nothing carried over from the source (Pillow writes its info back): only what `save` adds.
                copy.info = {}
                copy.save(path, **save)
            return source, path, tool

        upright = ImageOps.exif_transpose

        def fit(img, width=1920):
            img = ImageOps.exif_transpose(img)
            width = min(width, img.width) if width == 1920 else width
            return img.resize((width, round(img.height * width / img.width)))

        cases = [
            (True, made(photo, "no_exif", upright)),
            (True, made(turned, "no_exif", upright, quality=95)),
            (True, made(photo, "compressed", fit, quality=60)),
            (True, made(turned, "compressed", fit, quality=60)),
            (False, made(photo, "no_exif", upright, exif=Image.open(photo).getexif())),
            (False, made(photo, "no_exif", upright, xmp=xmp)),
            (False, made(turned, "no_exif", lambda img: img.copy(), quality=95)),
            (False, made(photo, "compressed", lambda img: fit(img, 2000), quality=60)),
            (False, made(photo, "compressed", fit, quality=100, subsampling=0)),
        ]
        wrong = 0
        for good, (source, path, tool) in cases:
            problems, _ = check(source, path, tool, 1920, 60)
            verdict = "passes" if not problems else "caught: " + "; ".join(problems)
            ok = (not problems) == good
            wrong += not ok
            print("%s %-8s %-60s %s" % ("OK  " if ok else "BAD ", "good" if good else "defect", path.name, verdict))
    print("self-test: %d case(s) wrong" % wrong)
    return 1 if wrong else 0


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--inputs", type=pathlib.Path)
    parser.add_argument("--outputs", type=pathlib.Path)
    parser.add_argument("--max-width", type=int, default=1920)
    parser.add_argument("--quality", type=int)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        return self_test()
    if not args.inputs or not args.outputs:
        parser.error("--inputs and --outputs, or --self-test")
    return run(args.inputs, args.outputs, args.max_width, args.quality)


if __name__ == "__main__":
    sys.exit(main())
