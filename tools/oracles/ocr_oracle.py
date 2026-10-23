"""The OCR's oracle (phase 5): pictures of KNOWN text, drawn by Pillow — an independent writer, not the code
that reads them — read on a device by the app's own pipeline (OcrCheck, in the `debug` or `check` build:
`content call --method ocr`), the text judged here. The engine is Tesseract (docs D-032).

Usage:
    python -I tools/oracles/ocr_oracle.py --serial emulator-5554 [--package <applicationId>] [--grant] [--keep DIR]
        [--lang eng+fra] [--tessdata FILE.traineddata ...] [--psm N] [--scale F] [--scan]

What the app does is the default. To MEASURE what it does not: --lang and --tessdata (other trained data,
pushed to the device; the app needs all-files access, --grant), --psm (another page segmentation mode),
--scale (the decoded picture resized by F), --scan (the picture first made a page by the scanner's code).

The pictures (made in a scratch folder, `--keep DIR` to keep a copy):
  a-arial-36.png         eight lines of Arial 36 px, black on white, 1400 x 700
  b-times-26.jpg         the same lines in Times New Roman 26 px, JPEG q90
  c-exif-6.jpg           a page stored turned a quarter (EXIF Orientation 6): upright only once it is applied
                         (checked here by Pillow's own `exif_transpose`)
  d-photo-12mpx.jpg      a page photographed: 4032 x 3024 (the S9's sensor), paper not quite white under uneven
                         light, turned 1.5 degrees, blurred, grain, JPEG q70
  e-48mpx.jpg            8000 x 6000, large text: decoded halved (4000 x 3000), the text still found
  f-no-text.png          a gradient and shapes, no character
  g-100mpx.png           10 000 x 10 000 (100 megapixels, a few hundred KB): refused before any decode
  h-empty.png            0 bytes;  i-garbage.jpg  random bytes;  j-cut.jpg  half of b-times-26.jpg;
  k-jpeg-named-png.png   b-times-26.jpg under a .png name
  l-other-latin.png      eight lines of German, Spanish, Portuguese, Danish, Polish and Czech, Arial 36 px

What passes:
  - each CLEAN page (a, b, c): at least 95 % of its words found, IN READING ORDER (the longest common
    subsequence of the words expected and the words found, over the words expected); words compared
    case-folded, punctuation around them dropped, accents kept. ML Kit, the 2.15.3's engine, read every
    picture of text at 98.4 to 100 %; 95 % is the bar Tesseract was given (docs D-032);
  - the photo-like page, the 48 Mpx one and the other latin languages (d, e, l): read (outcome ok), their
    share of words MEASURED and reported, not judged against the bar;
  - every picture of text: the size decoded where it is the point (DECODED): c-exif-6 upright, the 12 Mpx
    photo whole, the 48 Mpx one halved; and read in 10 s at most, decode and recognition together;
  - the picture without text: outcome ok, text empty (the screen then says "No text found in this image");
  - 100 megapixels: refused (too_many_pixels), the app alive; the hostile files: refused or read, never a crash.
Exit code 0 only if everything passes.
"""
import argparse
import base64
import json
import pathlib
import random
import re
import shutil
import subprocess
import sys
import tempfile
import unicodedata

from PIL import Image, ImageDraw, ImageFilter, ImageFont, ImageOps

DEFAULT_PACKAGE = "com.readfilestech.read_files_tech.next"
DEVICE_DIR = "/sdcard/Download/rft-ocr"
# Other trained data, for --tessdata: Tesseract reads `<data path>/tessdata/<lang>.traineddata`.
DEVICE_DATA = "/sdcard/Download/rft-ocr-data"
FONT_DIRS = [pathlib.Path("C:/Windows/Fonts"), pathlib.Path("/usr/share/fonts/truetype/msttcorefonts")]

LINES = [
    "The quick brown fox jumps over the lazy dog",
    "Invoice number 4821 dated 22 October 2026",
    "Total amount due 1234.56 EUR before Friday",
    "Read Files Tech reads every file offline",
    "Portez ce vieux whisky au juge blond qui fume",
    "Une lettre envoyée à Marseille en été",
    "Pack my box with five dozen liquor jugs",
    "Nothing in this picture leaves the phone",
]
# Six other languages of the latin alphabet, with their own letters: what the 2.15.3's engine read (ML Kit's
# latin model) and what trained data for English and French alone may not (docs D-032). Measured only.
OTHER_LATIN = [
    "Zwölf Boxkämpfer jagen Viktor quer über den Sylter Deich",
    "Die große Straße führt zum Fuß des Berges",
    "El veloz murciélago hindú comía feliz cardillo y kiwi",
    "La cigüeña tocaba el saxofón detrás del palenque",
    "Luís argüia à Júlia que fé chá óxido pôr zângão",
    "Høj bly gom vandt fræk sexquiz på wc",
    "Pchnąć w tę łódź jeża lub ośm skrzyń fig",
    "Příliš žluťoučký kůň úpěl ďábelské ódy",
]
PASS_RATIO = 0.95
# The pages measured, not judged against PASS_RATIO (docs D-032): the photo-like one, the 48 Mpx one, and the
# other latin languages.
MEASURED = ("d-photo-12mpx.jpg", "e-48mpx.jpg", "l-other-latin.png")
EXPECTED = {"l-other-latin.png": OTHER_LATIN}
# A page read in more than this, decode and recognition together, fails (docs D-032).
MAX_MILLIS = 10_000
# The size the app must decode, where it is the point: upright (an engine may read sideways text — ML Kit did,
# 100 % of the words of c-exif-6 WITHOUT its tag — so only the size tells the EXIF orientation was applied),
# whole at 12 Mpx, halved at 48 Mpx. Checked unless --scale or --scan change the size on purpose.
DECODED = {"c-exif-6.jpg": "1400x700", "d-photo-12mpx.jpg": "4032x3024", "e-48mpx.jpg": "4000x3000"}


def font(name, size):
    for folder in FONT_DIRS:
        path = folder / name
        if path.exists():
            return ImageFont.truetype(str(path), size)
    sys.exit("font not found: %s (looked in %s)" % (name, ", ".join(map(str, FONT_DIRS))))


def page(size, font_name, font_size, margin, background="white", ink="black", lines=LINES):
    picture = Image.new("RGB", size, background)
    draw = ImageDraw.Draw(picture)
    face = font(font_name, font_size)
    y = margin
    for line in lines:
        draw.text((margin, y), line, font=face, fill=ink)
        y += int(font_size * 1.6)
    if y > size[1]:
        sys.exit("the lines do not fit in %s" % (size,))
    return picture


def photo_like(rng):
    """A page under a lamp: off-white paper, light falling off to one side, a slight turn, blur and grain."""
    width, height = 4032, 3024
    sheet = page((3400, 2300), "times.ttf", 48, 140, background=(238, 234, 222), ink=(35, 32, 30))
    sheet = sheet.rotate(1.5, resample=Image.BICUBIC, expand=True, fillcolor=(90, 85, 80))
    canvas = Image.new("RGB", (width, height), (90, 85, 80))
    canvas.paste(sheet, ((width - sheet.width) // 2, (height - sheet.height) // 2))
    # Uneven light: darker towards the right.
    shade = Image.linear_gradient("L").rotate(90, expand=True).resize((width, height)).point(lambda v: 255 - v // 4)
    canvas = Image.composite(canvas, Image.new("RGB", (width, height), (0, 0, 0)), shade)
    canvas = canvas.filter(ImageFilter.GaussianBlur(1.6))
    grain = Image.effect_noise((width, height), 28).convert("RGB")
    canvas = Image.blend(canvas, grain, 0.12)
    return canvas


def no_text(rng):
    """A picture without a character: a sky-like gradient, broad blocks of colour, softened — no thin stroke a
    recogniser could take for a letter (a thin arc of an ellipse came out as ")" on the emulator)."""
    picture = Image.linear_gradient("L").resize((1200, 900)).convert("RGB")
    draw = ImageDraw.Draw(picture)
    for _ in range(6):
        x, y = rng.randrange(900), rng.randrange(600)
        draw.rectangle((x, y, x + rng.randrange(150, 300), y + rng.randrange(150, 300)), fill=tuple(rng.randrange(256) for _ in range(3)))
    return picture.filter(ImageFilter.GaussianBlur(12))


def save_jpeg(picture, path, quality, exif=None):
    picture.save(path, "JPEG", quality=quality, **({"exif": exif} if exif else {}))


def make(out):
    """Writes the pictures into [out]; returns {file: kind}: text (with its lines), empty, refused, hostile."""
    rng = random.Random(20261022)
    cases = {}
    page((1400, 700), "arial.ttf", 36, 40).save(out / "a-arial-36.png")
    cases["a-arial-36.png"] = "text"
    times = out / "b-times-26.jpg"
    save_jpeg(page((1100, 520), "times.ttf", 26, 30), times, 90)
    cases[times.name] = "text"

    upright = page((1400, 700), "arial.ttf", 34, 40)
    stored = upright.transpose(Image.Transpose.ROTATE_90)  # turned a quarter anticlockwise: Orientation 6 shows it upright
    exif = Image.Exif()
    exif[0x0112] = 6
    rotated = out / "c-exif-6.jpg"
    save_jpeg(stored, rotated, 92, exif.tobytes())
    with Image.open(rotated) as check:
        if ImageOps.exif_transpose(check).size != upright.size:
            sys.exit("c-exif-6.jpg: Pillow does not read it back upright")
    cases[rotated.name] = "text"

    save_jpeg(photo_like(rng), out / "d-photo-12mpx.jpg", 70)
    cases["d-photo-12mpx.jpg"] = "text"
    save_jpeg(page((8000, 6000), "arial.ttf", 150, 300), out / "e-48mpx.jpg", 85)
    cases["e-48mpx.jpg"] = "text"
    no_text(rng).save(out / "f-no-text.png")
    cases["f-no-text.png"] = "empty"
    Image.new("L", (10_000, 10_000), 255).save(out / "g-100mpx.png", optimize=True)
    cases["g-100mpx.png"] = "refused"
    page((1400, 700), "arial.ttf", 36, 40, lines=OTHER_LATIN).save(out / "l-other-latin.png")
    cases["l-other-latin.png"] = "text"

    (out / "h-empty.png").write_bytes(b"")
    (out / "i-garbage.jpg").write_bytes(bytes(rng.randrange(256) for _ in range(50_000)))
    data = times.read_bytes()
    (out / "j-cut.jpg").write_bytes(data[: len(data) // 2])
    shutil.copyfile(times, out / "k-jpeg-named-png.png")
    for name in ("h-empty.png", "i-garbage.jpg", "j-cut.jpg", "k-jpeg-named-png.png"):
        cases[name] = "hostile"
    return cases


def words(text):
    found = []
    for raw in unicodedata.normalize("NFC", text).split():
        word = raw.strip(".,;:!?\"'()[]«»").casefold()
        if word:
            found.append(word)
    return found


def in_order(expected, found):
    """The longest common subsequence of two word lists: the words found in the order expected."""
    previous = [0] * (len(found) + 1)
    for e in expected:
        current = [0]
        for j, f in enumerate(found):
            current.append(previous[j] + 1 if e == f else max(previous[j + 1], current[j]))
        previous = current
    return previous[-1]


def adb(serial, *args, check=True):
    run = subprocess.run(["adb", "-s", serial, *args], capture_output=True, text=True, encoding="utf-8", errors="replace")
    if check and run.returncode != 0:
        sys.exit("adb %s failed: %s" % (" ".join(args), (run.stdout + run.stderr).strip()))
    return run.stdout


def report_of(output):
    match = re.search(r"report=([A-Za-z0-9+/=]+)", output)
    return json.loads(base64.b64decode(match.group(1)).decode("utf-8")) if match else None


def judge(name, kind, result, check_size=True):
    """None when the result is what [kind] asks, else why not."""
    outcome, text = result["outcome"], result["text"]
    if kind == "text":
        if outcome != "ok":
            return "outcome %s" % outcome
        if check_size and result["decoded"] != DECODED.get(name, result["decoded"]):
            return "decoded %s, expected %s" % (result["decoded"], DECODED[name])
        expected = words(" ".join(EXPECTED.get(name, LINES)))
        ratio = in_order(expected, words(text)) / len(expected)
        result["ratio"] = ratio
        millis = (result["decodeMillis"] or 0) + (result["recognizeMillis"] or 0)
        if millis > MAX_MILLIS:
            return "read in %d ms, more than %d" % (millis, MAX_MILLIS)
        if name in MEASURED:
            return None
        return None if ratio >= PASS_RATIO else "%.1f %% of the words in order" % (100 * ratio)
    if kind == "empty":
        return None if outcome == "ok" and text == "" else "outcome %s, text %r" % (outcome, text)
    if kind == "refused":
        return None if outcome == "too_many_pixels" else "outcome %s" % outcome
    # hostile: refused in words, or read; never a crash (a crash leaves no report at all).
    return None if outcome in ("ok", "unreadable", "cannot_open", "too_many_pixels") else "outcome %s" % outcome


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", required=True)
    parser.add_argument("--package", default=DEFAULT_PACKAGE)
    parser.add_argument("--grant", action="store_true", help="appops MANAGE_EXTERNAL_STORAGE allow")
    parser.add_argument("--keep", help="a folder to copy the pictures to")
    parser.add_argument("--lang", help="Tesseract's languages, e.g. Latin (default: the app's)")
    parser.add_argument("--tessdata", nargs="+", help="trained data files to read instead of the app's")
    parser.add_argument("--psm", type=int, help="Tesseract's page segmentation mode (default: the app's)")
    parser.add_argument("--scale", type=float, help="the decoded picture resized by this factor")
    parser.add_argument("--scan", action="store_true", help="the picture first made a page by the scanner")
    args = parser.parse_args()
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    # The judge first: the text itself passes, the same lines in another order do not.
    expected = words(" ".join(LINES))
    swapped = words(" ".join(LINES[4:] + LINES[:4]))
    if in_order(expected, expected) != len(expected) or in_order(expected, swapped) / len(expected) >= PASS_RATIO:
        sys.exit("the judge does not tell reading order apart")

    with tempfile.TemporaryDirectory(prefix="rft-ocr-") as scratch:
        out = pathlib.Path(scratch) / "rft-ocr"
        out.mkdir()
        cases = make(out)
        if args.keep:
            shutil.copytree(out, args.keep, dirs_exist_ok=True)
        if args.grant:
            adb(args.serial, "shell", "appops", "set", args.package, "MANAGE_EXTERNAL_STORAGE", "allow")
        adb(args.serial, "shell", "rm", "-rf", DEVICE_DIR)
        adb(args.serial, "push", str(out), DEVICE_DIR)
    extras = []
    if args.tessdata:
        adb(args.serial, "shell", "rm", "-rf", DEVICE_DATA)
        adb(args.serial, "shell", "mkdir", "-p", DEVICE_DATA + "/tessdata")
        for model in args.tessdata:
            adb(args.serial, "push", model, DEVICE_DATA + "/tessdata/" + pathlib.Path(model).name)
        extras += ["--extra", "tessdata:s:" + DEVICE_DATA]
    if args.lang:
        extras += ["--extra", "lang:s:" + args.lang]
    if args.psm is not None:
        extras += ["--extra", "psm:i:%d" % args.psm]
    if args.scale is not None:
        extras += ["--extra", "scale:f:%s" % args.scale]
    if args.scan:
        extras += ["--extra", "scan:b:true"]
    uri = "content://%s.portcheck" % args.package
    output = adb(args.serial, "shell", "content", "call", "--uri", uri, "--method", "ocr", "--arg", DEVICE_DIR, *extras, check=False)
    adb(args.serial, "shell", "rm", "-rf", DEVICE_DIR, check=False)
    report = report_of(output)
    if report is None:
        print("THE APP DIED during the oracle (no report):\n" + output[-2000:])
        return 1
    print("options: %s" % (" ".join(extras[1::2]) or "the app's"))
    print("trained data checked (or copied) in %s ms%s" % (
        report.get("prepareMillis"), ", FAILED: %s" % report["prepared"] if report.get("prepared") else ""))
    results = {r["file"]: r for r in report["results"]}
    failures = 0 if not report.get("prepared") else 1
    print("%-22s %-8s %-16s %-11s %7s %7s  %s" % ("file", "kind", "outcome", "decoded", "dec ms", "ocr ms", "verdict"))
    for name, kind in cases.items():
        result = results.get(name)
        check_size = args.scale is None and not args.scan
        problem = "no result" if result is None else judge(name, kind, result, check_size)
        failures += problem is not None
        r = result or {}
        verdict = ("MEASURED" if name in MEASURED else "OK") if problem is None else "FAIL " + problem
        if "ratio" in r:
            verdict += " (%.1f %% of the words in order)" % (100 * r["ratio"])
        if r.get("detail"):
            verdict += " [%s]" % r["detail"]
        print("%-22s %-8s %-16s %-11s %7s %7s  %s" % (
            name, kind, r.get("outcome"), r.get("decoded"), r.get("decodeMillis"), r.get("recognizeMillis"), verdict))
    print()
    for name, kind in cases.items():
        if kind == "text" and name in results:
            print("--- %s\n%s" % (name, results[name]["text"]))
    print()
    print("%d picture(s) failed" % failures if failures else "every picture passed")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
