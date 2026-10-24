#!/usr/bin/env python3
"""NEGATIVE control of check-translations.py - the proof that the instrument can go red.

Why this exists
---------------
A parity check that returned 0 whatever happened would be worse than none: it would hand out a
false guarantee at every release. So every failure mode check-translations.py claims to catch is
REPRODUCED here in a throwaway tree, and two things are verified:
  - the check returns 1, not 0;
  - its message names the RIGHT cause. Failing for another reason is a false green: the day the
    real cause disappears, the check keeps failing and nobody believes it any more.

A POSITIVE WITNESS opens the series: a perfectly healthy tree must return 0. Without it, a
check-translations.py broken into returning 1 on everything would pass the whole series. It also
holds what must NOT fail: reordered arguments, `%%`, a two-word copy, a brand, a quoted text with an
apostrophe, folders that are no language (values-night, values-car) with a strings.xml of their own.

Ported from App Manager Tech; the gestures that ship a language are this app's (the folders are the
list, app/build.gradle.kts derives localeFilters from them, AGP generates the locale config).

No dependencies, never touches the repository: everything happens in a temporary directory,
deleted at the end.

    python3 tools/check-translations-negative-control.py
"""
import importlib.util
import os
import shutil
import subprocess
import sys
import tempfile

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

HERE = os.path.dirname(os.path.abspath(__file__))
CHECK = os.path.join(HERE, "check-translations.py")

# `two_words` and `tile` are identical in every language on purpose: under three words, brands left out,
# a copy is not flagged (a brand, "OK", "PDF / A"), and the positive witness proves the threshold.
# `quoted` is aapt2's quoted form: an apostrophe inside needs no backslash.
EN = """<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name" translatable="false">Read Files Tech</string>
    <string name="policy_url" translatable="false">https://github.com/gitubpatrice/READ-FILES-TECH/blob/main/SECURITY.md</string>
    <string name="hello">Hello</string>
    <string name="promise">100% local — zero network</string>
    <string name="moved">Moved to %1$s at %2$s</string>
    <string name="percent">%1$d%% done</string>
    <string name="sentence">Open the file again</string>
    <string name="two_words">Read Files</string>
    <string name="tile">Files Tech Scanner</string>
    <string name="quoted">"It's quoted"</string>
    <string name="privacy_url">https://github.com/gitubpatrice/READ-FILES-TECH/blob/main/PRIVACY.md</string>
    <plurals name="files">
        <item quantity="one">%1$d file</item>
        <item quantity="other">%1$d files</item>
    </plurals>
</resources>
"""

# Note the REORDERING of %1$s and %2$s: it is LEGITIMATE (that is what 1$ / 2$ are for) and the
# positive witness therefore requires it to pass. French cannot be a copy of German: CLDR requires
# `many` in French and not in German. Italian and Spanish are written as copies of the French: the
# check requires every language of its CLDR table, and none is read for its words here.
FR_OK = """<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="hello">Bonjour</string>
    <string name="promise">100 % local — zéro réseau</string>
    <string name="moved">Déplacé le %2$s vers %1$s</string>
    <string name="percent">%1$d %% fait</string>
    <string name="sentence">Rouvrir l\\'original</string>
    <string name="two_words">Read Files</string>
    <string name="tile">Files Tech Scanner</string>
    <string name="quoted">"C'est cité"</string>
    <string name="privacy_url">https://github.com/gitubpatrice/READ-FILES-TECH/blob/main/PRIVACY.fr.md</string>
    <plurals name="files">
        <item quantity="one">%1$d fichier</item>
        <item quantity="many">%1$d de fichiers</item>
        <item quantity="other">%1$d fichiers</item>
    </plurals>
</resources>
"""

DE_OK = """<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="hello">Hallo</string>
    <string name="promise">100 % lokal — null Netzwerk</string>
    <string name="moved">Am %2$s nach %1$s verschoben</string>
    <string name="percent">Zu %1$d %% erledigt</string>
    <string name="sentence">Die Datei erneut öffnen</string>
    <string name="two_words">Read Files</string>
    <string name="tile">Files Tech Scanner</string>
    <string name="quoted">"Zitiert"</string>
    <string name="privacy_url">https://github.com/gitubpatrice/READ-FILES-TECH/blob/main/PRIVACY.md</string>
    <plurals name="files">
        <item quantity="one">%1$d Datei</item>
        <item quantity="other">%1$d Dateien</item>
    </plurals>
</resources>
"""

GRADLE = """val shippedLanguages: List<String> = listOf("en") + file("src/main/res").listFiles().orEmpty()
    .filter { File(it, "strings.xml").isFile }
    .mapNotNull { Regex("values-((?!car$)[a-z]{2,3})").matchEntire(it.name)?.groupValues?.get(1) }
    .sorted()
android {
    androidResources {
        localeFilters += shippedLanguages
        generateLocaleConfig = true
    }
}
"""

RESOURCES_PROPERTIES = "unqualifiedResLocale=en\n"

# A strings.xml in a folder that names no language: the check must leave it alone.
NOT_A_LANGUAGE = '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n    <string name="hello">x</string>\n</resources>\n'


def check_module():
    """check-translations.py as a module: its exemptions shape the healthy tree."""
    spec = importlib.util.spec_from_file_location("check_translations", CHECK)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


SAME_AS_ENGLISH = check_module().SAME_AS_ENGLISH

base = None


def with_lines(xml, lines):
    return xml.replace("</resources>", "".join("    %s\n" % line for line in lines) + "</resources>")


def string(name, text):
    return '<string name="%s">%s</string>' % (name, text)


def exempted_keys():
    """Every key the check exempts, by language, with the English text it is pinned to."""
    return {key: text for exempted in SAME_AS_ENGLISH.values() for key, text in exempted.items()}


def language_file(content, lang):
    """A translation with the exempted keys: the English text where this language is exempted, its own
    text elsewhere (a copy there would be flagged, and that is not what the witness tests)."""
    exempt_here = SAME_AS_ENGLISH.get(lang, {})
    return with_lines(content, [string(k, exempt_here.get(k, "[%s] %s" % (lang, k))) for k in exempted_keys()])


def write(path_, content):
    os.makedirs(os.path.dirname(path_), exist_ok=True)
    with open(path_, "w", encoding="utf-8", newline="\n") as f:
        f.write(content)


def write_bytes(path_, data):
    """Write WITHOUT line-ending translation - Windows text mode would rewrite it."""
    os.makedirs(os.path.dirname(path_), exist_ok=True)
    with open(path_, "wb") as f:
        f.write(data)


def path(*parts):
    return os.path.join(base, *parts)


def res(*parts):
    return path("app", "src", "main", "res", *parts)


def english():
    return with_lines(EN, [string(k, text) for k, text in exempted_keys().items()])


def build():
    """A HEALTHY tree: every language complete, the derivation in place, a store listing."""
    if os.path.exists(base):
        shutil.rmtree(base)
    os.makedirs(path("tools"))
    shutil.copy(CHECK, path("tools", "check-translations.py"))
    write(path("app", "build.gradle.kts"), GRADLE)
    write(res("resources.properties"), RESOURCES_PROPERTIES)
    write(res("values", "strings.xml"), english())
    write(res("values-night", "themes.xml"), "<resources />\n")
    write(res("values-night", "strings.xml"), NOT_A_LANGUAGE)
    write(res("values-car", "strings.xml"), NOT_A_LANGUAGE)
    de(DE_OK)
    for lang in ("fr", "it", "es"):
        write(res("values-" + lang, "strings.xml"), language_file(FR_OK, lang))
    # The documents the links point at.
    write(path("PRIVACY.md"), "policy\n")
    write(path("PRIVACY.fr.md"), "politique\n")
    write(path("SECURITY.md"), "security\n")
    for locale in ("en-US", "de-DE"):
        fastlane = path("fastlane", "metadata", "android", locale)
        write(os.path.join(fastlane, "title.txt"), "Read Files Tech\n")
        write(os.path.join(fastlane, "short_description.txt"), "A short description.\n")
        write(os.path.join(fastlane, "full_description.txt"), "A long description.\n")
        # 500 characters exactly, 1000 bytes: the cap counts characters, and the positive witness
        # must stay green on it.
        write(os.path.join(fastlane, "changelogs", "30000.txt"), "é" * 500 + "\n")


def de(content):
    write(res("values-de", "strings.xml"), language_file(content, "de"))


def fr(content):
    write(res("values-fr", "strings.xml"), language_file(content, "fr"))


# --- One mutation per claimed failure mode ---------------------------------------------------

def missing_key():
    de(DE_OK.replace('    <string name="hello">Hallo</string>\n', ""))
    return "MISSING key: hello"


def extra_key():
    de(DE_OK.replace("</resources>", '    <string name="unknown">x</string>\n</resources>'))
    return "EXTRA key"


def non_translatable_key_translated():
    """English marks app_name translatable="false"; German carries it anyway. (Its ABSENCE from
    German and French is the positive witness: no language owes it.)"""
    de(DE_OK.replace("</resources>", '    <string name="app_name">Dateien lesen</string>\n</resources>'))
    return 'translates a key English marks translatable="false": app_name'


def duplicate_key():
    de(DE_OK.replace('    <string name="hello">Hallo</string>\n',
                     '    <string name="hello">Hallo</string>\n'
                     '    <string name="hello">Servus</string>\n'))
    return "DUPLICATE key: hello"


def duplicate_plural():
    block = DE_OK[DE_OK.index("    <plurals"):DE_OK.index("</resources>")]
    de(DE_OK.replace("</resources>", block + "</resources>"))
    return "DUPLICATE plural: files"


def string_became_plurals():
    """R.string.hello asks for a string: a plurals of that name leaves the English on screen."""
    de(DE_OK.replace('    <string name="hello">Hallo</string>\n',
                     '    <plurals name="hello">\n        <item quantity="one">Hallo</item>\n'
                     '        <item quantity="other">Hallo</item>\n    </plurals>\n'))
    return "hello is a <string> in English and a <plurals> here"


def plurals_became_string():
    block = DE_OK[DE_OK.index("    <plurals"):DE_OK.index("</resources>")]
    de(DE_OK.replace(block, '    <string name="files">Dateien</string>\n'))
    return "files is a <plurals> in English and a <string> here"


def string_array():
    de(DE_OK.replace("</resources>", '    <string-array name="modes"><item>a</item></string-array>\n</resources>'))
    return "unexpected <string-array name='modes'>"


def texts_in_another_file():
    write(res("values", "more_strings.xml"), NOT_A_LANGUAGE)
    return "values/more_strings.xml holds texts"


def lost_argument():
    de(DE_OK.replace("Am %2$s nach %1$s verschoben", "Nach %1$s verschoben"))
    return "format arguments differ on moved"


def invented_argument():
    de(DE_OK.replace("Am %2$s nach %1$s verschoben", "Am %2$s nach %1$s via %3$s verschoben"))
    return "format arguments differ on moved"


def malformed_argument():
    """A %1$p is not a Java conversion: the argument is LOST, not translated - and, the text being
    formatted, String.format throws on it."""
    de(DE_OK.replace("Am %2$s nach %1$s verschoben", "Am %2$s nach %1$p verschoben"))
    return "format arguments differ on moved"


def bare_percent_at_the_end():
    """Formatted text: a final "%" throws UnknownFormatConversionException when the screen is drawn."""
    de(DE_OK.replace("Zu %1$d %% erledigt", "Erledigt: %1$d %"))
    return "percent has a % that starts no argument"


def percent_read_as_an_argument():
    """"%1$d % de": with the space flag "% d" IS a conversion - a second, invented argument."""
    fr(FR_OK.replace("%1$d %% fait", "%1$d % de fait"))
    return "format arguments differ on percent"


def bare_percent_in_english():
    write(res("values", "strings.xml"), english().replace("%1$d%% done", "%1$d done, 100%"))
    for lang in ("fr", "it", "es"):
        write(res("values-" + lang, "strings.xml"), language_file(FR_OK.replace("%1$d %% fait", "%1$d fait, 100 %%"), lang))
    de(DE_OK.replace("Zu %1$d %% erledigt", "%1$d erledigt, 100 %%"))
    return "percent has a % that starts no argument"


def argument_flags_lost():
    """%,d groups the thousands: a translation with %d shows 1572864 instead of 1.572.864."""
    write(res("values", "strings.xml"), english().replace("%1$d file</item>", "%1$,d file</item>")
          .replace("%1$d files</item>", "%1$,d files</item>"))
    for lang in ("fr", "it", "es"):
        write(res("values-" + lang, "strings.xml"), language_file(FR_OK.replace("%1$d ", "%1$,d "), lang))
    de(DE_OK.replace("%1$d Datei</item>", "%1$,d Datei</item>"))
    return "format arguments differ on plural files[other]: English={%1$,d} translation={%1$d}"


def invented_argument_in_plural():
    de(DE_OK.replace('<item quantity="other">%1$d Dateien</item>',
                     '<item quantity="other">%1$d von %2$d Dateien</item>'))
    return "format arguments differ on plural files[other]"


def invented_argument_in_plural_one():
    de(DE_OK.replace('<item quantity="one">%1$d Datei</item>', '<item quantity="one">%1$d von %2$d Datei</item>'))
    return "INVENTED argument in plural files[one]: {%2$d}"


def argument_lost_in_plural():
    """The count gone from `other`: "Dateien" for 1 572 864 files."""
    de(DE_OK.replace('<item quantity="other">%1$d Dateien</item>', '<item quantity="other">Dateien</item>'))
    return "format arguments differ on plural files[other]: English={%1$d} translation={}"


def french_one_without_the_number():
    """In French `one` also counts 0: "un fichier" would be said of none."""
    fr(FR_OK.replace('<item quantity="one">%1$d fichier</item>', '<item quantity="one">un fichier</item>'))
    return "plural files[one] lost {%1$d} - in this language `one` also counts 0"


def missing_quantity():
    de(DE_OK.replace('        <item quantity="one">%1$d Datei</item>\n', ""))
    return "MISSING quantity in plural files: one"


def cldr_category_of_the_language_missing():
    """French without `many`, which ENGLISH does not have - the defect App Manager Tech had."""
    fr(FR_OK.replace('        <item quantity="many">%1$d de fichiers</item>\n', ""))
    return "CLDR category MISSING for this language: many"


def useless_cldr_category():
    de(DE_OK.replace('        <item quantity="other">%1$d Dateien</item>',
                     '        <item quantity="two">%1$d Dateien</item>\n'
                     '        <item quantity="other">%1$d Dateien</item>'))
    return "CLDR category USELESS in de"


def language_unknown_to_the_check():
    """A sixth language added without its plural rules: the check must not pass it unchecked."""
    write(res("values-pt", "strings.xml"), language_file(DE_OK, "pt"))
    return "no CLDR plural categories known for pt"


def language_removed():
    """Its folder gone, nothing else: one language less, every other check green."""
    shutil.rmtree(res("values-de"))
    return "CLDR_QUANTITIES names de, but values-de/strings.xml is missing"


def english_sentence_left_untranslated():
    de(DE_OK.replace("Die Datei erneut öffnen", "Open the file again"))
    return "sentence is the English text, untranslated"


def english_plural_left_untranslated():
    """Three words in a plural item, copied from English."""
    write(res("values", "strings.xml"), english().replace("%1$d files</item>", "%1$d files were kept</item>"))
    de(DE_OK.replace("%1$d Dateien</item>", "%1$d files were kept</item>"))
    return "plural files[other] is the English text, untranslated"


def exemption_outlived_its_english():
    """The English of an exempted key changed: its old copy must not pass for a translation."""
    key, text = next(iter(exempted_keys().items()))
    write(res("values", "strings.xml"), english().replace(text, text + " and more of the same"))
    return "SAME_AS_ENGLISH exempts %s for an English text it no longer has" % key


def exemption_for_a_language_not_shipped():
    script = path("tools", "check-translations.py")
    content = open(script, encoding="utf-8").read()
    write(script, content.replace("SAME_AS_ENGLISH = {\n", 'SAME_AS_ENGLISH = {\n    "pt": {"x": "y"},\n', 1))
    return "SAME_AS_ENGLISH names a language that has no values-pt"


def derivation_gone():
    write(path("app", "build.gradle.kts"), GRADLE.replace(
        'file("src/main/res").listFiles().orEmpty()', 'emptyList<File>()'))
    return "localeFilters is no longer derived from the values-XX folders"


def a_second_list():
    write(path("app", "build.gradle.kts"), GRADLE.replace(
        "        localeFilters += shippedLanguages\n",
        '        localeFilters += shippedLanguages\n        localeFilters += listOf("en", "fr")\n'))
    return "localeFilters is touched beside the folders"


def derivation_left_in_a_comment():
    """The derived line commented out, a list doing the work: the text is still there to be found."""
    write(path("app", "build.gradle.kts"), GRADLE.replace(
        "        localeFilters += shippedLanguages\n",
        '        // localeFilters += shippedLanguages\n        localeFilters.addAll(listOf("en", "fr"))\n'))
    return "localeFilters is no longer derived from the values-XX folders"


def locale_config_not_generated():
    write(path("app", "build.gradle.kts"), GRADLE.replace("        generateLocaleConfig = true\n", ""))
    return "generateLocaleConfig = true missing"


def resources_properties_gone():
    os.remove(res("resources.properties"))
    return "unqualifiedResLocale=en missing"


def resources_properties_names_another_language():
    write(res("resources.properties"), "unqualifiedResLocale=fr\n")
    return "unqualifiedResLocale=en missing"


def language_folder_with_a_region():
    """Translated, but the derivation reads values-xx only: it would never reach a phone."""
    os.rename(res("values-de"), res("values-de-rAT"))
    return "values-de-rAT/ is not a plain language folder"


def bare_double_quote():
    """aapt2 drops it from the screen: `Die "Datei"` would read `Die Datei`."""
    de(DE_OK.replace("Die Datei erneut öffnen", 'Die ' + chr(34) + "Datei" + chr(34) + " erneut öffnen"))
    return "sentence has a straight quote or apostrophe without a backslash"


def bare_apostrophe_in_english():
    write(res("values", "strings.xml"), english().replace("Open the file again", "Open the file" + chr(39) + "s copy"))
    return "sentence has a straight quote or apostrophe without a backslash"


def double_quote_inside_the_quoted_form():
    """In aapt2's quoted form an apostrophe is literal, a double quote still ends the span."""
    de(DE_OK.replace('"Zitiert"', '"Zi"tiert"'))
    return "quoted has a straight quote or apostrophe without a backslash"


def link_to_a_missing_document():
    """PRIVACY.de.md named in a string, but never written: a German user gets a 404."""
    de(DE_OK.replace("blob/main/PRIVACY.md", "blob/main/PRIVACY.de.md"))
    return "links to PRIVACY.de.md, which does not exist"


def english_untranslated_link_to_a_missing_document():
    """A translatable="false" link is checked too: it is the one every language shows."""
    os.remove(path("SECURITY.md"))
    return "policy_url links to SECURITY.md, which does not exist"


def store_listing_too_long():
    """81 characters for a cap of 80. (The positive witness's changelog is 500 characters and 1000
    bytes: the cap counts characters.)"""
    write(path("fastlane", "metadata", "android", "de-DE", "short_description.txt"),
          "a" * 81 + "\n")
    return "fastlane/short_description.txt: 81 characters for a cap of 80"


def changelog_too_long():
    """F-Droid cuts the changelog at 500 characters, mid-word, without a warning."""
    write(path("fastlane", "metadata", "android", "de-DE", "changelogs", "30000.txt"), "é" * 501 + "\n")
    return "fastlane/changelogs/30000.txt: 501 characters for a cap of 500"


def store_file_missing():
    os.remove(path("fastlane", "metadata", "android", "de-DE", "title.txt"))
    return "fastlane/title.txt MISSING"


def doubled_carriage_return():
    data = open(res("values-de", "strings.xml"), encoding="utf-8").read().encode("utf-8")
    write_bytes(res("values-de", "strings.xml"), data.replace(b"\n", b"\r\r\n"))
    return "carries \\r\\r\\n line endings"


def mixed_line_endings():
    data = open(res("values-de", "strings.xml"), encoding="utf-8").read().encode("utf-8")
    cut = data.index(b"\n", len(data) // 2) + 1
    write_bytes(res("values-de", "strings.xml"), data[:cut].replace(b"\n", b"\r\n") + data[cut:])
    return "mixes CRLF"


CASES = [
    ("missing key", missing_key),
    ("extra key", extra_key),
    ("translatable=false key translated", non_translatable_key_translated),
    ("duplicate key", duplicate_key),
    ("duplicate plural", duplicate_plural),
    ("type: a string became a plurals", string_became_plurals),
    ("type: a plurals became a string", plurals_became_string),
    ("a <string-array>, which nothing reads", string_array),
    ("texts in another values file", texts_in_another_file),
    ("format argument LOST", lost_argument),
    ("format argument INVENTED", invented_argument),
    ("format argument MALFORMED (%1$p)", malformed_argument),
    ("formatted text: a bare % at the end", bare_percent_at_the_end),
    ("formatted text: '% d' read as an argument", percent_read_as_an_argument),
    ("formatted text: a bare % in the English", bare_percent_in_english),
    ("format argument FLAGS lost (%,d -> %d)", argument_flags_lost),
    ("plural: argument invented in other", invented_argument_in_plural),
    ("plural: argument invented in one", invented_argument_in_plural_one),
    ("plural: the count lost in other", argument_lost_in_plural),
    ("plural: French one without the number", french_one_without_the_number),
    ("plural quantity missing", missing_quantity),
    ("plural: CLDR category of the LANGUAGE missing", cldr_category_of_the_language_missing),
    ("plural: useless CLDR category", useless_cldr_category),
    ("plural: a language the check knows nothing of", language_unknown_to_the_check),
    ("a language's folder removed", language_removed),
    ("copy: an English sentence left as it is", english_sentence_left_untranslated),
    ("copy: an English plural left as it is", english_plural_left_untranslated),
    ("copy: an exemption outlived its English", exemption_outlived_its_english),
    ("copy: an exemption for a language not shipped", exemption_for_a_language_not_shipped),
    ("derivation: localeFilters no longer from the folders", derivation_gone),
    ("derivation: a literal list beside it", a_second_list),
    ("derivation: left in a comment, a list doing the work", derivation_left_in_a_comment),
    ("derivation: locale config not generated", locale_config_not_generated),
    ("derivation: resources.properties gone", resources_properties_gone),
    ("derivation: resources.properties not English", resources_properties_names_another_language),
    ("derivation: a folder with a region (never shipped)", language_folder_with_a_region),
    ("quotes: a bare double quote in a translation", bare_double_quote),
    ("quotes: a bare apostrophe in the English source", bare_apostrophe_in_english),
    ("quotes: a double quote inside the quoted form", double_quote_inside_the_quoted_form),
    ("link to a repository document that is missing", link_to_a_missing_document),
    ("link: an untranslatable English link that is missing", english_untranslated_link_to_a_missing_document),
    ("store listing: too long (characters)", store_listing_too_long),
    ("store listing: a changelog over 500 characters", changelog_too_long),
    ("store listing: a file MISSING", store_file_missing),
    ("line endings: one carriage return too many", doubled_carriage_return),
    ("line endings: two conventions in one file", mixed_line_endings),
]


def run():
    r = subprocess.run([sys.executable, path("tools", "check-translations.py")],
                       capture_output=True, text=True, encoding="utf-8", errors="replace")
    return r.returncode, (r.stdout or "") + (r.stderr or "")


def main():
    global base
    if not os.path.exists(CHECK):
        print("ERROR: %s not found." % CHECK, file=sys.stderr)
        return 2

    temp_root = tempfile.mkdtemp(prefix="check-translations-")
    base = os.path.join(temp_root, "tree")
    failures = []
    try:
        build()
        code, output = run()
        if code == 0:
            print("  OK    POSITIVE WITNESS: healthy tree (reordered arguments, %%, copies, quoted form, "
                  "values-night and values-car) -> 0")
        else:
            print("  FAIL  POSITIVE WITNESS: healthy tree -> %d" % code)
            print("        " + output.strip().replace("\n", "\n        "))
            failures.append("positive witness")

        for name, mutate in CASES:
            build()
            expected = mutate()
            code, output = run()
            if code != 1:
                print("  FAIL  %-56s code=%d (expected 1) NOT DETECTED" % (name, code))
                failures.append(name)
            elif expected not in output:
                print("  FAIL  %-56s fails, but for ANOTHER reason" % name)
                print("        expected: %s" % expected)
                print("        " + output.strip().replace("\n", "\n        "))
                failures.append(name)
            else:
                print("  OK    %-56s detected, right cause" % name)
    finally:
        shutil.rmtree(temp_root, ignore_errors=True)

    total = len(CASES) + 1
    print()
    if failures:
        print("NEGATIVE CONTROL: %d/%d -- FAILED: %s"
              % (total - len(failures), total, ", ".join(failures)))
        return 1
    print("NEGATIVE CONTROL: %d/%d -- every defect is detected, and for the right reason."
          % (total, total))
    return 0


if __name__ == "__main__":
    sys.exit(main())
