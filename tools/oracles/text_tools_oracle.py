"""Independent references for the Encode, Format, Compare and Colour picker tools (phase 5).

Usage:
  python -I tools/oracles/text_tools_oracle.py fixtures   writes the references the JVM tests read
  python -I tools/oracles/text_tools_oracle.py check      reads what the JVM tests wrote, and checks it

`fixtures` writes, under app/src/test/resources/tools/, what Python's own modules say — never the app's
code: base64, urllib.parse.quote / unquote and html.escape / unescape on a corpus of texts
(encode-oracle.json); colorsys on a grid of colours (colors-oracle.json); difflib and a longest common
subsequence by dynamic programming on pairs of texts (diff-oracle.json); and the JSON cases of the format
tool with the string literals its CSS and JS cases must keep (format/manifest.json). They are run again
only when the corpus changes, and committed.

`check` reads app/build/oracles/format/, where CodeFormatOracleTest writes each case formatted: every JSON
output must parse (json, object_pairs_hook: members in order, a duplicate key kept) to the value its input
parses to; every CSS and JS output must hold each string literal of its input, byte for byte; and the JS
program, minified, must print exactly what it prints as written (node).
"""
import base64
import collections
import colorsys
import decimal
import difflib
import html
import json
import os
import pathlib
import random
import subprocess
import sys
import tempfile
import urllib.parse

ROOT = pathlib.Path(__file__).resolve().parents[2]
RESOURCES = ROOT / "app" / "src" / "test" / "resources" / "tools"
OUTPUTS = ROOT / "app" / "build" / "oracles" / "format"
BACKSLASH = chr(92)


def write_atomically(path, text):
    """Writes [text] beside [path], then renames: the old file stays whole if anything fails."""
    path.parent.mkdir(parents=True, exist_ok=True)
    handle, temporary = tempfile.mkstemp(dir=path.parent, prefix=path.name, suffix=".tmp")
    try:
        with os.fdopen(handle, "w", encoding="utf-8", newline="") as out:
            out.write(text)
        os.replace(temporary, path)
    except BaseException:
        os.unlink(temporary)
        raise


def write_bytes_atomically(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    handle, temporary = tempfile.mkstemp(dir=path.parent, prefix=path.name, suffix=".tmp")
    try:
        with os.fdopen(handle, "wb") as out:
            out.write(data)
        os.replace(temporary, path)
    except BaseException:
        os.unlink(temporary)
        raise


def dump(path, value):
    write_atomically(path, json.dumps(value, ensure_ascii=True, separators=(",", ":")) + "\n")


# ---------------------------------------------------------------- encode

ENCODE_CORPUS = [
    "",
    "a",
    "Hello, World!",
    "é à ç ù — œ",
    "€ 100 % sûr",
    chr(0x1F600) + chr(0x1F44D) + chr(0x1F3FD),  # an emoji, and one with a skin tone: surrogate pairs
    "日本語のテキスト",
    "a b&c=d?e/f#g+h",
    "~-._ unreserved",
    "&amp;amp; &lt;b&gt;",
    "<script>alert('x' + \"y\")</script>",
    "line one\nline two\ttab\r\nCRLF",
    "  leading and trailing  ",
    chr(0) + "nul",
    "x" * 1000 + chr(0x1F600),
]

# HTML texts whose references are all in the reader's table (XHTML's 253 names, `;` closed) or numeric:
# html.unescape reads them as the app must. A name without its `;` (`&amp`) stays as written in the app,
# as in its reader; Python decodes HTML5's legacy ones, so none is here.
HTML_SAMPLES = [
    "&amp;lt;",
    "&amp;amp;amp;",
    "&lt;p class=&quot;x&quot;&gt;caf&eacute; &amp; cr&egrave;me&lt;/p&gt;",
    "&#233;&#xE9;&#XE9;&#x1F600;",
    "&#233 no semicolon",
    "&#150; &#146; &#128;",
    "&#0; &#xD800; &#x110000;",
    "&unknown; &nbsp;&copy;&euro;",
    "& alone, &; and &#; and &#x;",
    "&apos;&#39;&#x27;",
]

# Refused by both Python (b64decode, validate) and the app.
BASE64_INVALID = ["abc$", "a", "ab=c", "====", "Zm9v!"]


def encode_fixture():
    cases = []
    for text in ENCODE_CORPUS:
        data = text.encode("utf-8", "surrogatepass")
        cases.append({
            "text": text,
            "base64": base64.b64encode(data).decode("ascii"),
            "base64url": base64.urlsafe_b64encode(data).decode("ascii").rstrip("="),
            "url": urllib.parse.quote(text, safe=""),
            "htmlEscaped": html.escape(text, quote=True),
        })
    for invalid in BASE64_INVALID:
        try:
            base64.b64decode(invalid, validate=True)
        except ValueError:
            continue
        raise SystemExit("Python accepts %r: not a shared refusal" % invalid)
    samples = [{"html": sample, "text": html.unescape(sample)} for sample in HTML_SAMPLES]
    dump(RESOURCES / "encode-oracle.json", {"cases": cases, "html": samples, "base64Invalid": BASE64_INVALID})


# ---------------------------------------------------------------- colours

def half_up(value):
    return int(decimal.Decimal(repr(value)).quantize(decimal.Decimal(1), rounding=decimal.ROUND_HALF_UP))


def colors_fixture():
    steps = [0, 1, 51, 102, 127, 128, 204, 255]
    colours = []
    for r in steps:
        for g in steps:
            for b in steps:
                h, l, s = colorsys.rgb_to_hls(r / 255, g / 255, b / 255)
                hv, sv, v = colorsys.rgb_to_hsv(r / 255, g / 255, b / 255)
                colours.append({
                    "rgb": [r, g, b],
                    "hex": "#%02X%02X%02X" % (r, g, b),
                    "hsl": [h * 360, s, l],
                    "hsv": [hv * 360, sv, v],
                    "hslCode": "hsl(%d, %d%%, %d%%)" % (half_up(h * 360) % 360, half_up(s * 100), half_up(l * 100)),
                })
    dump(RESOURCES / "colors-oracle.json", {"colours": colours})


# ---------------------------------------------------------------- compare

def lcs_length(a, b):
    previous = [0] * (len(b) + 1)
    for x in a:
        current = [0]
        for j, y in enumerate(b):
            current.append(previous[j] + 1 if x == y else max(previous[j + 1], current[j]))
        previous = current
    return previous[-1]


def lines_of(text):
    """The app's lines: split at LF, no line after a final LF, a line's CR dropped (line endings not counted)."""
    if text == "":
        return []
    parts = text.split("\n")
    if text.endswith("\n"):
        parts.pop()
    return [part[:-1] if part.endswith("\r") else part for part in parts]


def diff_pairs():
    rng = random.Random(20261014)
    words = ["alpha", "beta", "gamma", "delta", "epsilon", "zeta", "eta", "theta", "iota", "kappa", "café", "日本"]
    base = ["%03d %s" % (i, " ".join(rng.choice(words) for _ in range(rng.randint(1, 6)))) for i in range(80)]
    pairs = []
    for case in range(10):
        edited = list(base)
        for _ in range(rng.randint(1, 12)):
            op = rng.choice(["insert", "delete", "change"])
            at = rng.randrange(len(edited))
            if op == "insert":
                edited.insert(at, "new %d.%d %s" % (case, at, rng.choice(words)))
            elif op == "delete":
                del edited[at]
            else:
                edited[at] = edited[at] + " (changed %d)" % case
        pairs.append(("edits-%d" % case, "\n".join(base) + "\n", "\n".join(edited) + "\n", False))
    pairs.append(("crlf-vs-lf", "\r\n".join(base[:40]) + "\r\n", "\n".join(base[:40]) + "\n", False))
    pairs.append(("no-final-newline", "\n".join(base[:30]), "\n".join(base[:30]) + "\n", False))
    pairs.append(("empty-vs-text", "", "\n".join(base[:5]) + "\n", False))
    pairs.append(("all-different", "\n".join(base[:50]) + "\n", "\n".join(l + "!" for l in base[:50]) + "\n", False))
    pairs.append(("prepend-append", "\n".join(base[10:60]) + "\n", "\n".join(base[:70]) + "\n", False))
    # Repeated lines: several shortest scripts exist, and difflib need not find one — counts only.
    pairs.append(("repeated-lines", "a\nb\na\nb\na\nc\n", "b\na\nb\nc\na\nb\n", True))
    pairs.append(("swapped", "x\ny\n", "y\nx\n", True))
    return pairs


def diff_fixture():
    out = []
    for name, a_text, b_text, ambiguous in diff_pairs():
        a, b = lines_of(a_text), lines_of(b_text)
        removed, added = [], []
        for tag, i1, i2, j1, j2 in difflib.SequenceMatcher(None, a, b, autojunk=False).get_opcodes():
            if tag in ("replace", "delete"):
                removed += a[i1:i2]
            if tag in ("replace", "insert"):
                added += b[j1:j2]
        lcs = lcs_length(a, b)
        if not ambiguous and (len(removed) != len(a) - lcs or len(added) != len(b) - lcs):
            raise SystemExit("%s: difflib is not minimal here, mark it ambiguous" % name)
        out.append({
            "name": name, "a": a_text, "b": b_text, "ambiguous": ambiguous,
            "removed": sorted(removed), "added": sorted(added), "lcs": lcs,
        })
    dump(RESOURCES / "diff-oracle.json", {"pairs": out})


# ---------------------------------------------------------------- format

def json_cases():
    """Each case: its bytes, and whether org.json can read it (it refuses a duplicate key and huge numbers)."""
    deep = "[" * 40 + "1" + "]" * 40
    escapes = '{"q": "a' + BACKSLASH + '"b", "s": "' + BACKSLASH + '/ ' + BACKSLASH + BACKSLASH + ' ' + BACKSLASH + 'n ' \
        + BACKSLASH + 'u00e9 ' + BACKSLASH + 'ud83d' + BACKSLASH + 'ude00", "raw": "é' + chr(0x1F600) + '"}'
    cases = [
        ("object", '{"name": "Read Files Tech", "version": [2, 15, 3], "ok": true, "none": null}', True),
        ("nested", json.dumps({"a": {"b": {"c": [1, {"d": [], "e": {}}, "x"]}}, "f": -0.5e-3}), True),
        ("escapes", escapes, True),
        ("deep", deep, True),
        ("numbers", '[0, -0, 1.5e300, 123456789012345678901234567890, 1E400, -1e-400]', False),
        ("duplicates", '{"k": 1, "k": 2, "k": {"k": 3}}', False),
        # No CR: the repository keeps text files in LF (.gitattributes), a CR would not survive a checkout.
        ("whitespace", ' \t\n {\n "a" \t: [ 1 ,\n 2 ] , "b":{ } } \n', True),
        ("scalar", '"just a string"', True),
        ("empty-containers", '[[], {}, [[]], {"a": {}}]', True),
    ]
    out = []
    for name, text, org in cases:
        out.append((name, text.encode("utf-8"), org))
    out.append(("bom-utf8", b"\xef\xbb\xbf" + '{"bom": "é"}'.encode("utf-8"), True))
    out.append(("utf16le", ('{"utf16": "é' + chr(0x1F600) + '"}').encode("utf-16"), True))
    return out


CSS_LITERALS = ['"utf-8"', '"theme.css"', '"Helvetica Neue"', "'Arial'", '"/* not a comment */ // nor this"',
                "'" + BACKSLASH + "201C'", "url(http://example.com/img.png)", ".esc" + BACKSLASH + ":name"]
JS_LITERALS = ['"http://example.com//path/*not a comment*/"', "'it" + BACKSLASH + "'s // not a comment'",
               "`nested ${", "} // still template`", "/" + BACKSLASH + "/" + BACKSLASH + "/[a-z]+/g",
               "/[/]+/", "/a" + BACKSLASH + "/b/i", '"v  a  l"', '"tab\tand  spaces"', '"one ' + BACKSLASH + "\ntwo" + '"',
               '"naïve"', "/ab+c/gi", '/a  b|"x"/', "/=" + BACKSLASH + "/*x/"]


def format_fixture():
    folder = RESOURCES / "format"
    manifest = []
    for name, data, org in json_cases():
        write_bytes_atomically(folder / (name + ".json"), data)
        manifest.append({"name": name, "file": name + ".json", "kind": "json", "orgJson": org})
    for file, kind, literals in (("styles.css", "css", CSS_LITERALS), ("program.js", "js", JS_LITERALS)):
        source = (folder / file).read_text(encoding="utf-8")
        missing = [literal for literal in literals if literal not in source]
        if missing:
            raise SystemExit("%s does not hold %r" % (file, missing))
        manifest.append({"name": file.split(".")[0], "file": file, "kind": kind, "literals": literals})
    dump(folder / "manifest.json", {"cases": manifest})


def pairs_hook(pairs):
    return ["object", pairs]


def check():
    manifest = json.loads((RESOURCES / "format" / "manifest.json").read_text(encoding="utf-8"))["cases"]
    failures = []
    checked = 0
    for case in manifest:
        source = (RESOURCES / "format" / case["file"]).read_bytes()
        if case["kind"] == "json":
            encoding = "utf-16" if source[:2] in (b"\xff\xfe", b"\xfe\xff") else "utf-8-sig"
            expected = json.loads(source.decode(encoding), object_pairs_hook=pairs_hook)
            for mode in ("indented", "minified"):
                output = OUTPUTS / ("%s.%s.json" % (case["name"], mode))
                value = json.loads(output.read_text(encoding="utf-8"), object_pairs_hook=pairs_hook)
                checked += 1
                if value != expected:
                    failures.append("%s: not the input's value" % output.name)
        else:
            output = OUTPUTS / ("%s.min.%s" % (case["name"], case["kind"]))
            text = output.read_text(encoding="utf-8")
            checked += 1
            for literal in case["literals"]:
                if literal not in text:
                    failures.append("%s: lost %r" % (output.name, literal))
            if case["kind"] == "js":
                original = run_node(RESOURCES / "format" / case["file"])
                minified = run_node(output)
                checked += 1
                if original != minified:
                    failures.append("%s prints %r, the original %r" % (output.name, minified, original))
    for failure in failures:
        print("FAIL", failure)
    print("%d outputs checked, %d failures" % (checked, len(failures)))
    return 1 if failures else 0


def run_node(path):
    run = subprocess.run(["node", str(path)], capture_output=True, text=True, encoding="utf-8")
    if run.returncode != 0:
        return "exit %d: %s" % (run.returncode, run.stderr.strip()[-300:])
    return run.stdout


if __name__ == "__main__":
    sys.stdout.reconfigure(errors="replace")
    if sys.argv[1:] == ["fixtures"]:
        encode_fixture()
        colors_fixture()
        diff_fixture()
        format_fixture()
        print("fixtures written under", RESOURCES)
    elif sys.argv[1:] == ["check"]:
        sys.exit(check())
    else:
        sys.exit(__doc__)
