#!/usr/bin/env python3
"""Translation parity check. It FAILS, it does not warn.

Why this exists
---------------
A language shipped once and then forgotten does not become incomplete in the open: it does so
SILENTLY. A key added in English and nowhere else makes the screen fall back to English word by
word, and nobody sees it before the user does. A language that drifts must break the build.

Ported from App Manager Tech (itself ported from Agenda Tech and SMS Tech, each proven with its own
negative control), for the languages of docs D-033: English (the source), French, German, Italian,
Spanish. The app names its builds through a manifest placeholder (`appLabel`, app/build.gradle.kts)
that no language can override, so there is no per-language debug twin to check.

The list of languages
---------------------
The translated folders ARE the list: app/build.gradle.kts derives `localeFilters` from every
values-XX/ that carries a strings.xml, and AGP writes the Android 13+ language picker's
locales_config (`generateLocaleConfig`) - from EVERY resource folder of the app and its local
modules, though, not from the strings.xml alone: what the picker offers is read in the APK itself by
tools/check-release-apks.py. Here, the derivation is checked to be still the one in place (5), and
CLDR_QUANTITIES stands for the languages that ship: a folder removed alone, one language less with
every other check green, fails (3).

What it checks, and why each one is needed
------------------------------------------
1. KEYS        - a missing key is an English label in the middle of a German screen; an EXTRA key
                 is translation spent on a string nothing displays any more; the same name as
                 another TYPE (<plurals> for a <string>) is as missing. Every text lives in a
                 strings.xml, as <string> or <plurals>: anything else would go unchecked.
2. FORMAT ARGS - %1$s, %2$d... lost or invented CRASH the app at runtime (IllegalFormatException).
                 The ORDER may change - that is what 1$ / 2$ are for - the set may not, flags
                 included (%,d groups the thousands). In a text String.format reads, a `%` that
                 starts no argument throws: "100 %" must be "100 %%" there. A plural, branch by
                 branch: `other` and `many` carry the English `other`'s arguments, `one` may drop the
                 number only where it means exactly 1 (not in French, where it also counts 0).
3. PLURALS     - every quantity the LANGUAGE requires (CLDR), not an English floor: English only
                 has `one`/`other`, and French, Italian and Spanish also need `many`. A language
                 this script does not know FAILS: its categories must be written down here first;
                 and a language written here with no folder FAILS: it would no longer ship.
4. COPIES      - a text of three words or more (brands and links left out) left identical to the
                 English one is a key that was copied and never translated: parity of keys says
                 nothing of their meaning. An exemption holds for the English text it was given for.
5. THE DERIVATION THAT SHIPS A LANGUAGE - app/build.gradle.kts still derives localeFilters from the
                 folders (a literal list beside it would be a second list, which drifts), AGP still
                 generates the locale config, res/resources.properties names English as the
                 unqualified language, and every language folder has the plain form the derivation
                 reads (values-de, not values-de-rAT: that one would be translated and never shipped).
6. LINKS TO REPOSITORY DOCUMENTS - a string pointing at .../blob/main/PRIVACY.de.md must name a
                 file that exists, or a user of that language gets a 404 instead of the policy.
7. LINE ENDINGS and 8. STORE LISTING (fastlane, once it exists: caps in CHARACTERS, as fdroidserver
                 counts and cuts them).
9. QUOTES      - a straight " or ' without its backslash: aapt2 DROPS a bare double quote from the
                 text, with no error - `Turn on "Allow"` reads `Turn on Allow` - in any language.

What the APK ends up with is checked on the APK: tools/check-release-apks.py (its locales and the
languages its picker offers) and tools/check-manifest-shape.py (android:localeConfig).

Usage:  python3 tools/check-translations.py
"""
import os
import re
import sys
import xml.etree.ElementTree as ET

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")
GRADLE = os.path.join(ROOT, "app", "build.gradle.kts")
RESOURCES_PROPERTIES = os.path.join(RES, "resources.properties")

# Any folder that names a language - values-de, values-pt-rBR, values-b+sr+Latn - and NOT values-night,
# values-v31, values-land..., which are configuration qualifiers, not languages; nor values-car, the car UI
# mode, three letters as a language would be (aapt2 reads it so).
LANGUAGE_DIR = re.compile(r"^values-(?:b\+[A-Za-z+]+|(?!car(?:-|$))[a-z]{2,3}(?:-r[A-Z]{2})?)$")
# The form app/build.gradle.kts reads (`values-([a-z]{2,3})`): a language with a region or a script
# would be translated here and never shipped.
PLAIN_LANGUAGE_DIR = re.compile(r"^values-((?!car$)[a-z]{2,3})$")
# In a text no one formats: real Java conversions only - "100% private" is not a %p argument, and the
# SPACE flag is left out on purpose: "100 % des" is not one either.
FORMAT_ARG = re.compile(r"%(\d+\$)?([-#+0,(]*\d*(?:\.\d+)?)([bBhHsScCdoxXeEfgGaAtTn])")
# In a text String.format reads (its English has an argument): Java's whole grammar, space flag and `%<s`
# included. There a `%` that starts no valid conversion THROWS - "%1$d % de" or a final "%" crash the screen.
JAVA_SPEC = re.compile(r"%(\d+\$|<)?([-#+ 0,(]*\d*(?:\.\d+)?)([tT][a-zA-Z]|[bBhHsScCdoxXeEfgGaAtTn])")
# A link to a document of THIS repository.
REPO_DOC = re.compile(r"https://github\.com/gitubpatrice/READ-FILES-TECH/blob/[A-Za-z0-9._-]+/([A-Za-z0-9._/-]+)")
# A word, for the copy check: letters only, so "%1$s", "·" or "PDF/A" do not make a sentence.
WORD = re.compile(r"[^\W\d_]{2,}")
URL = re.compile(r"https?://\S+")
# Names no language translates (TRANSLATING.md): they do not make a sentence English. Without this, the tile
# "Files Tech Scanner" counted three English words, and pushed German to a longer "Scanner von Files Tech".
BRANDS = re.compile(r"Read Files Tech|Files Tech|PDF Tech|Notes Tech|Pass Tech|kDrive|Proton Drive|Google Drive|Tesseract")
COPY_MIN_WORDS = 3
# A straight apostrophe or double quote not preceded by a backslash. aapt2 takes a bare " as the start
# of a quoted span and DROPS it from the text, without an error: `Turn on "Allow"` reads `Turn on Allow`.
# Built from chr() so no tool on the way can alter the backslash (seen with heredocs, sed and editors).
BACKSLASH, APOSTROPHE, QUOTE = chr(92), chr(39), chr(34)
UNESCAPED_QUOTE = re.compile("(?<!" + re.escape(BACKSLASH) + ")[" + APOSTROPHE + QUOTE + "]")

# The CLDR plural categories each shipped language requires. A missing one renders the wrong text
# for those quantities; an extra one is dead text, never rendered.
CLDR_QUANTITIES = {
    "en": {"one", "other"},
    "de": {"one", "other"},
    "fr": {"one", "many", "other"},
    "it": {"one", "many", "other"},
    "es": {"one", "many", "other"},
}

# The languages where the CLDR category `one` also covers 0 (French: "0 fichier"): there, a `one` that
# drops the number would be said of none.
ONE_ALSO_ZERO = {"fr"}

# Texts that are the SAME in a language as in English on purpose, by language, with the reason - each
# pinned to the English text it was granted for: when the English changes, the exemption lapses, and a
# copy of the new English would not pass for a translation.
# Keep it short: every entry is a text nobody will ever see translated.
SAME_AS_ENGLISH = {
    # "Images" is the French word too; German, Italian and Spanish have theirs (Bilder, Immagini...).
    "fr": {"tool_hint_convert": "Images/PDF, CSV/XLSX, TXT/PDF"},
}

# fdroidserver 2.4.2, common.py: char_limits - title 50, summary 80, description 4000, whatsNew 500.
FASTLANE_CAPS = {
    "title.txt": 50,
    "short_description.txt": 80,
    "full_description.txt": 4000,
}
CHANGELOG_CAP = 500

errors = []

# Keys English marks translatable="false" (product name, joiners, identifiers): no language owes
# them, and a language that carries one anyway is flagged - Android lint calls that an extra
# translation.
NOT_TRANSLATABLE = set()

# English strings String.format reads: those with an argument, unless marked formatted="false".
FORMATTED = set()


def fail(where, message):
    errors.append((where, message))


def raw_text(element):
    """The content of a string, style tags included - that is where format args live."""
    return "".join(element.itertext())


def read(path):
    """Return ({key: text}, {key: {quantity: text}})."""
    root = ET.parse(path).getroot()
    label = os.path.basename(os.path.dirname(path))
    strings, plurals = {}, {}
    for e in root:
        if e.tag not in ("string", "plurals"):
            fail(label, "unexpected <%s name=%r>: this check reads only <string> and <plurals> - a "
                        "<string-array> or any other text would go unchecked" % (e.tag, e.get("name")))
    for e in root.findall("string"):
        name = e.get("name")
        if name in strings:
            fail(label, "DUPLICATE key: %s" % name)
        strings[name] = raw_text(e)
    for e in root.findall("plurals"):
        name = e.get("name")
        if name in plurals:
            fail(label, "DUPLICATE plural: %s" % name)
        plurals[name] = {i.get("quantity"): raw_text(i) for i in e.findall("item")}
    return strings, plurals


def format_args(text):
    """The set of format arguments, whatever their order, each with its flags, width and precision:
    `%,d` groups the thousands and `%.1f` keeps one decimal - a translation that drops them shows
    1572864 or 1.4999. %% is a literal percent sign."""
    return frozenset((p or "", spec, t) for p, spec, t in FORMAT_ARG.findall(text.replace("%%", "\x00")))


def java_args(text):
    """For a text String.format reads: its arguments (as format_args), and how many `%` start none - each
    one throws (UnknownFormatConversionException) when the screen is drawn."""
    t = text.replace("%%", "\x00\x00")
    args, bare = set(), 0
    i = t.find("%")
    while i >= 0:
        m = JAVA_SPEC.match(t, i)
        if m:
            args.add((m.group(1) or "", m.group(2), m.group(3)))
            i = t.find("%", m.end())
        else:
            bare += 1
            i = t.find("%", i + 1)
    return frozenset(args), bare


def pretty(args):
    return "{" + ", ".join(sorted("%%%s%s%s" % arg for arg in args)) + "}"


def tag(directory):
    """values-pt-rBR -> pt-rBR."""
    return directory[len("values-"):]


def check_derivation():
    """5. The one list: the folders, read by app/build.gradle.kts, and the locale config generated."""
    with open(GRADLE, encoding="utf-8") as f:
        # Comments out: a derivation left in a comment while a list does the work must not pass.
        content = re.sub(r"/\*.*?\*/|//[^\n]*", "", f.read(), flags=re.S)
    if not re.search(r'file\("src/main/res"\)\.listFiles\(\)', content) \
            or "localeFilters += shippedLanguages" not in content:
        fail("build.gradle.kts", "localeFilters is no longer derived from the values-XX folders "
                                 "(`localeFilters += shippedLanguages`) - this check would be blind")
    if len(re.findall(r"\blocaleFilters\b", content)) > 1:
        fail("build.gradle.kts", "localeFilters is touched beside the folders (a literal list, addAll, "
                                 "clear...): a second list, which drifts - it ships the libraries' "
                                 "languages with no translation behind, or strips one")
    if not re.search(r"generateLocaleConfig\s*=\s*true", content):
        fail("build.gradle.kts", "generateLocaleConfig = true missing: no per-app language picker "
                                 "on Android 13+")
    try:
        with open(RESOURCES_PROPERTIES, encoding="utf-8") as f:
            properties = f.read()
    except OSError:
        properties = ""
    if not re.search(r"^unqualifiedResLocale=en\s*$", properties, re.M):
        fail("resources.properties", "unqualifiedResLocale=en missing from res/resources.properties: "
                                     "AGP cannot write the locale config, and English is the source")


def check_texts_elsewhere():
    """Texts only in strings.xml: in another values*/ file, this check would not read them."""
    for directory in sorted(os.listdir(RES)):
        if not directory.startswith("values"):
            continue
        for name in sorted(os.listdir(os.path.join(RES, directory))):
            if name == "strings.xml" or not name.endswith(".xml"):
                continue
            content = open(os.path.join(RES, directory, name), encoding="utf-8").read()
            if re.search(r"<(string|plurals|string-array)\b", content):
                fail(directory, "%s/%s holds texts: move them to strings.xml, the one file this check "
                                "and the translators read" % (directory, name))


def check_repository_links(directory, strings):
    """6. Every link to a document of this repository must name a file that exists."""
    label = "en" if directory == "values" else tag(directory)
    for key, text in sorted(strings.items()):
        for relative in REPO_DOC.findall(text):
            if not os.path.isfile(os.path.join(ROOT, *relative.split("/"))):
                fail(label, "%s links to %s, which does not exist in the repository (404)"
                            % (key, relative))


def check_line_endings(directories):
    """7. No strings.xml may carry \\r\\r\\n, nor mix CRLF and LF.

    Not a demand for LF: on Windows `core.autocrlf` gives a CRLF working copy while the repository
    stores LF, so requiring either one would fail the other. What is refused is wrong in BOTH:
    the doubled carriage return (seen on SMS Tech, commit 2e8991b), and two conventions in a file.
    """
    for directory in ["values"] + list(directories):
        path = os.path.join(RES, directory, "strings.xml")
        if not os.path.exists(path):
            continue
        label = "en" if directory == "values" else tag(directory)
        data = open(path, "rb").read()
        if b"\r\r\n" in data:
            fail(label, "strings.xml carries \\r\\r\\n line endings (%d): a tool added one "
                        "carriage return too many" % data.count(b"\r\r\n"))
            continue
        crlf, lf = data.count(b"\r\n"), data.count(b"\n")
        if crlf and crlf != lf:
            fail(label, "strings.xml mixes CRLF (%d) and LF (%d): one convention per file"
                        % (crlf, lf - crlf))


def characters(path):
    """The length fdroidserver measures: CHARACTERS after UTF-8 decoding, trailing whitespace off.

    None if the file is not valid UTF-8: fdroidserver would decode it with replacement characters,
    and the published listing would be garbled.
    """
    try:
        with open(path, encoding="utf-8") as f:
            return len(f.read().rstrip())
    except UnicodeDecodeError:
        return None


def check_cap(locale, path, label, cap):
    length = characters(path)
    if length is None:
        fail(locale, "fastlane/%s is not valid UTF-8" % label)
    elif length > cap:
        fail(locale, "fastlane/%s: %d characters for a cap of %d (over by %d) - "
                     "F-Droid cuts the text there" % (label, length, cap, length - cap))


def check_fastlane():
    """8. Store-listing caps in CHARACTERS, files present, every changelog within 500.

    What fdroidserver does with these files (read in its source, 2.4.2, on 2026-10-04 for SMS Tech):
    `common.py` sets char_limits - title 50, summary 80, description 4000, whatsNew 500 - and
    `update.py` CUTS each text at `text[:limit]`, with no error and no warning. Python characters,
    never bytes. The port has no store listing yet (phase 8); which changelog is "current" is decided
    with it, so here every changelog present is held to the cap.
    """
    root = os.path.join(ROOT, "fastlane", "metadata", "android")
    if not os.path.isdir(root):
        return
    for locale in sorted(os.listdir(root)):
        directory = os.path.join(root, locale)
        if not os.path.isdir(directory):
            continue
        for name, cap in sorted(FASTLANE_CAPS.items()):
            path = os.path.join(directory, name)
            # A MISSING file must fail: skipping it would be the easiest hole not to see, since
            # it prints nothing.
            if not os.path.isfile(path):
                fail(locale, "fastlane/%s MISSING" % name)
                continue
            check_cap(locale, path, name, cap)
        changelogs = os.path.join(directory, "changelogs")
        if os.path.isdir(changelogs):
            for name in sorted(os.listdir(changelogs)):
                check_cap(locale, os.path.join(changelogs, name), "changelogs/" + name, CHANGELOG_CAP)


def check_quotes(label, strings, plurals):
    """9. Every straight ' and " escaped: a bare " vanishes from the screen without an error.

    A text wrapped whole in double quotes is aapt2's quoted form: apostrophes inside are literal.
    """
    texts = [(k, t) for k, t in strings.items()]
    texts += [("%s[%s]" % (k, q), t) for k, items in plurals.items() for q, t in items.items()]
    for key, text in sorted(texts):
        inner = text
        if len(text) >= 2 and text.startswith(QUOTE) and text.endswith(QUOTE):
            inner = text[1:-1].replace(APOSTROPHE, "")
        if UNESCAPED_QUOTE.search(inner):
            fail(label, "%s has a straight quote or apostrophe without a backslash: aapt2 drops a bare "
                        "double quote from the text, without an error - %r" % (key, text[:60]))


def check_plural_args(lang, key, english, translated):
    """2. A plural's arguments, branch by branch. `other` and `many` cover many numbers: exactly the
    arguments of the English `other` - one lost is a count no longer shown (`octets` for "1 572 864
    octets"). `one` may drop the number where it means exactly 1, as English does, but not in a
    language where `one` also covers 0 (French): "Un fichier" would be said of none. Nothing invented."""
    reference = java_args(english.get("other", ""))[0]
    english_one = java_args(english.get("one", ""))[0]
    formatted = bool(reference or english_one)
    for q, text in sorted(translated.items()):
        found, bare = java_args(text)
        if bare and formatted:
            fail(lang, "plural %s[%s] has a %% that starts no argument: String.format throws - write %%%% "
                       "for a percent sign" % (key, q))
        if q != "one":
            if found != reference:
                fail(lang, "format arguments differ on plural %s[%s]: English=%s translation=%s"
                           % (key, q, pretty(reference), pretty(found)))
            continue
        if found - reference - english_one:
            fail(lang, "INVENTED argument in plural %s[one]: %s" % (key, pretty(found - reference - english_one)))
        lost = (reference if lang in ONE_ALSO_ZERO else english_one) - found
        if lost:
            fail(lang, "plural %s[one] lost %s%s" % (key, pretty(lost), " - in this language `one` also "
                                                     "counts 0" if lang in ONE_ALSO_ZERO else ""))


def words(text):
    """The words of a text, its links and brands left out: they are the same in every language."""
    return WORD.findall(BRANDS.sub(" ", URL.sub(" ", text)))


def check_language(directory, ref_strings, ref_plurals):
    lang = tag(directory)
    before = len(errors)
    strings, plurals = read(os.path.join(RES, directory, "strings.xml"))

    all_ref = set(ref_strings) | set(ref_plurals)
    all_here = set(strings) | set(plurals)

    # 1. KEYS
    for k in sorted(all_ref - all_here):
        fail(lang, "MISSING key: %s" % k)
    for k in sorted(all_here & NOT_TRANSLATABLE):
        fail(lang, "translates a key English marks translatable=\"false\": %s" % k)
    for k in sorted(all_here - all_ref - NOT_TRANSLATABLE):
        fail(lang, "EXTRA key (absent from English): %s" % k)
    # The same name, another type: R.string asks for one, the translation has the other - English shows.
    for k in sorted(set(ref_strings) & set(plurals)):
        fail(lang, "%s is a <string> in English and a <plurals> here" % k)
    for k in sorted(set(ref_plurals) & set(strings)):
        fail(lang, "%s is a <plurals> in English and a <string> here" % k)

    # 2. FORMAT ARGUMENTS - the one defect that crashes the app
    for k, expected in ref_strings.items():
        if k in strings:
            if k in FORMATTED:
                (a, _), (b, bare) = java_args(expected), java_args(strings[k])
                if bare:
                    fail(lang, "%s has a %% that starts no argument: String.format throws - write %%%% "
                               "for a percent sign: %r" % (k, strings[k][:60]))
            else:
                a, b = format_args(expected), format_args(strings[k])
            if a != b:
                fail(lang, "format arguments differ on %s: English=%s translation=%s"
                           % (k, pretty(a), pretty(b)))
    for k, items in ref_plurals.items():
        if k in plurals:
            check_plural_args(lang, k, items, plurals[k])

    # 3. PLURALS - the categories of THE LANGUAGE
    for k, items in ref_plurals.items():
        if k in plurals:
            for q in sorted(set(items) - set(plurals[k])):
                if q in CLDR_QUANTITIES.get(lang, set()):
                    fail(lang, "MISSING quantity in plural %s: %s" % (k, q))
    required = CLDR_QUANTITIES.get(lang)
    if required is None:
        fail(lang, "no CLDR plural categories known for %s: add them to CLDR_QUANTITIES in this "
                   "script (https://cldr.unicode.org, Language Plural Rules)" % lang)
    else:
        for k in sorted(plurals):
            missing = required - set(plurals[k])
            if missing:
                fail(lang, "plural %s: CLDR category MISSING for this language: %s"
                           % (k, ", ".join(sorted(missing))))
            useless = set(plurals[k]) - required
            if useless:
                fail(lang, "plural %s: CLDR category USELESS in %s (never rendered): %s"
                           % (k, lang, ", ".join(sorted(useless))))

    # 4. COPIES - a sentence left in English; an exemption holds for the English text it was given for
    allowed = {k for k, text in SAME_AS_ENGLISH.get(lang, {}).items() if ref_strings.get(k) == text}
    for k in sorted(set(ref_strings) & set(strings)):
        if strings[k] == ref_strings[k] and len(words(strings[k])) >= COPY_MIN_WORDS and k not in allowed:
            fail(lang, "%s is the English text, untranslated: %r" % (k, strings[k][:60]))
    for k in sorted(set(ref_plurals) & set(plurals)):
        for q, t in sorted(plurals[k].items()):
            if t == ref_plurals[k].get(q, ref_plurals[k].get("other")) \
                    and len(words(t)) >= COPY_MIN_WORDS and k not in allowed:
                fail(lang, "plural %s[%s] is the English text, untranslated: %r" % (k, q, t[:60]))

    # 6. LINKS
    check_repository_links(directory, strings)
    # 9. QUOTES
    check_quotes(lang, strings, plurals)

    state = "complete" if len(errors) == before else "%d issue(s)" % (len(errors) - before)
    print("  %-8s %4d keys - %s" % (lang, len(all_here), state))


def main():
    source = os.path.join(RES, "values", "strings.xml")
    if not os.path.exists(source):
        print("ERROR: English source not found (%s)" % source, file=sys.stderr)
        return 2

    ref_strings, ref_plurals = read(source)
    # Links and quotes are checked on every English text, those no language translates included.
    every_english = dict(ref_strings)
    for e in ET.parse(source).getroot().findall("string"):
        if e.get("translatable") == "false":
            NOT_TRANSLATABLE.add(e.get("name"))
            ref_strings.pop(e.get("name"), None)
        elif e.get("formatted") != "false" and format_args(raw_text(e)):
            FORMATTED.add(e.get("name"))
    for k in sorted(FORMATTED):
        if java_args(ref_strings[k])[1]:
            fail("en", "%s has a %% that starts no argument: String.format throws" % k)
    for k, items in sorted(ref_plurals.items()):
        for q, text in sorted(items.items()):
            if java_args(items.get("other", ""))[0] and java_args(text)[1]:
                fail("en", "plural %s[%s] has a %% that starts no argument: String.format throws" % (k, q))
    total = len(ref_strings) + len(ref_plurals)
    print("English source: %d keys to translate (%d strings + %d plurals), %d not translatable"
          % (total, len(ref_strings), len(ref_plurals), len(NOT_TRANSLATABLE)))

    directories = sorted(d for d in os.listdir(RES)
                         if LANGUAGE_DIR.match(d)
                         and os.path.exists(os.path.join(RES, d, "strings.xml")))
    for directory in directories:
        if not PLAIN_LANGUAGE_DIR.match(directory):
            fail(tag(directory), "%s/ is not a plain language folder (values-xx): app/build.gradle.kts "
                                 "would not ship it - the translation would never reach a phone" % directory)
    check_derivation()
    check_texts_elsewhere()
    check_repository_links("values", every_english)
    check_quotes("en", every_english, ref_plurals)
    shipped = {tag(d) for d in directories}
    for lang in sorted(set(SAME_AS_ENGLISH) - shipped):
        fail(lang, "SAME_AS_ENGLISH names a language that has no values-%s/strings.xml" % lang)
    for lang, exempted in sorted(SAME_AS_ENGLISH.items()):
        for k in sorted(k for k, text in exempted.items() if ref_strings.get(k) != text):
            fail(lang, "SAME_AS_ENGLISH exempts %s for an English text it no longer has: review the "
                       "translation, then the exemption" % k)
    # A language is removed on purpose, in two places: its folder and its plural rules. A folder lost
    # alone would ship one language less, every other check green.
    for lang in sorted(set(CLDR_QUANTITIES) - shipped - {"en"}):
        fail(lang, "CLDR_QUANTITIES names %s, but values-%s/strings.xml is missing: the language would "
                   "no longer ship. Removed on purpose? Remove it from CLDR_QUANTITIES too" % (lang, lang))

    for directory in directories:
        check_language(directory, ref_strings, ref_plurals)

    check_line_endings(directories)
    check_fastlane()

    print()
    if errors:
        print("TRANSLATIONS: %d ISSUE(S)" % len(errors))
        current = None
        for where, message in errors:
            if where != current:
                print("\n  [%s]" % where)
                current = where
            print("    - %s" % message)
        print("\nThe build stops here. See TRANSLATING.md.")
        return 1

    print("TRANSLATIONS: %d language(s) aligned on the %d English keys."
          % (len(directories), total))
    return 0


if __name__ == "__main__":
    sys.exit(main())
