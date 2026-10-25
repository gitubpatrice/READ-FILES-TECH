package com.readfilestech.read_files_tech.data.ocr

import java.text.Normalizer
import java.util.Locale

/**
 * A language the OCR reads (docs D-033): [tesseract], its code for Tesseract and its trained data's name;
 * [iso], the ISO 639-1 code of the app's language it goes with; [model], its pinned trained data; [words],
 * its most frequent words as they are written — those other languages share too ("la", "in") included, which
 * then count for none ([OcrLanguages.detect]).
 */
internal class OcrLanguage(val tesseract: String, val iso: String, val model: OcrModel, val words: Set<String>)

/**
 * The OCR's languages: the app's five (docs D-033). Adding one is an entry here and its trained data in the
 * assets — no other code: the models copied ([OcrModels]), Tesseract's languages, the detection and the
 * screen's list of languages all read this table (its tests state the five, and change with it).
 *
 * Tesseract is given every language, the first one PRIMARY — and the primary imposes its letters: measured on
 * the oracle (tools/oracles/ocr_oracle.py, S9, 2026-10-23), a German page read with French first gives
 * "Zwôlf", a Spanish one with German first "murcielago", "hindü", "deträs" (92.4 % of its words), each read
 * right with its own language first (98.4 to 100 %). So a page's language is told from the words found, and
 * the page read again with that language first when it was read with another ([OcrEngine]).
 *
 * The trained data: Tesseract's `tessdata_fast` (Apache-2.0), github.com/tesseract-ocr/tessdata_fast at
 * commit 87416418657359cb625c412a48b6e1d6d41c29bd (2024-08-01, the head of its `main`): sizes and SHA-256 of
 * that commit, each file's Git blob checked against the commit's listing when taken.
 */
@Suppress("MagicNumber") // the sizes of the files, as their source lists them
internal object OcrLanguages {
    val FRENCH = OcrLanguage(
        tesseract = "fra",
        iso = "fr",
        model = OcrModel("fra.traineddata", 1_130_365, "ced037562e8c80c13122dece28dd477d399af80911a28791a66a63ac1e3445ca"),
        words = words(
            "de la le et les des en un une du que qui est pour dans par plus pas au aux sur ne se ce il ils elle " +
                "elles on son sa ses nous vous leur cette ces avec ou où mais comme très être été fait tout bien sans " +
                "entre deux même y là à ont était aussi si non nos ma mon me lui as ci mis",
        ),
    )
    val ENGLISH = OcrLanguage(
        tesseract = "eng",
        iso = "en",
        model = OcrModel("eng.traineddata", 4_113_088, "7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2"),
        words = words(
            "the of and to a in is it you that he was for on are with as i his they be at one have this from or " +
                "had by not but what all were we when your can said there an each which she do how their if will up " +
                "other about out many then them these so some her would make like him into time has two more no way " +
                "could people my than first been who its now down did get come made may over every man am war per plus",
        ),
    )
    val GERMAN = OcrLanguage(
        tesseract = "deu",
        iso = "de",
        model = OcrModel("deu.traineddata", 1_525_436, "19d219bbb6672c869d20a9636c6816a81eb9a71796cb93ebe0cb1530e2cdb22d"),
        words = words(
            "der die das und in den von zu mit sich des auf für ist im dem nicht ein eine einen einer einem als " +
                "auch es an werden aus er hat dass sie nach wird bei um am sind noch wie über so zum zur war haben nur " +
                "oder aber vor bis mehr durch man sein wurde sei ich du wir ihr was will da ab diesem dieser kann per",
        ),
    )
    val ITALIAN = OcrLanguage(
        tesseract = "ita",
        iso = "it",
        model = OcrModel("ita.traineddata", 2_701_314, "b8f89e1e785118dac4d51ae042c029a64edb5c3ee42ef73027a6d412748d8827"),
        words = words(
            "di e il la che è per un in del non una a le si i da sono con al ma lo come dei della delle gli ha più " +
                "anche nel nella alla ci questo questa se mi o ne ho io tutto essere molto era così già perché però " +
                "sul dalla tra fra quando suo sua anni qui lui ce sei uno all cosa su durante tanto",
        ),
    )
    val SPANISH = OcrLanguage(
        tesseract = "spa",
        iso = "es",
        model = OcrModel("spa.traineddata", 2_294_433, "6f2e04d02774a18f01bed44b1111f2cd7f3ba7ac9dc4373cd3f898a40ea6b464"),
        words = words(
            "de la que el en y a los se del las un por con no una su para es al lo como más o pero sus le ya fue " +
                "este ha sí porque esta está son entre cuando muy sin sobre también me hasta hay donde dónde quien " +
                "desde todo nos durante todos uno les contra ese eso esto antes yo otro otra él tanto esa estos mucho " +
                "nada mi mis era cosa qué bien e",
        ),
    )

    /** In this order after the primary; French first, the app's first language. */
    val ALL = listOf(FRENCH, ENGLISH, GERMAN, ITALIAN, SPANISH)

    /**
     * Each language's words that no other language of [ALL] has: "la" (French, Italian, Spanish) or "an"
     * (English, German) tell nothing. Computed, so that a language added takes back the words it shares.
     */
    private val OWN: Map<OcrLanguage, Set<String>> = ALL.associateWith { language ->
        language.words - ALL.filter { it !== language }.flatMap { it.words }.toSet()
    }

    /** The fewest of its own words a page must hold to be told a language. */
    const val MIN_WORDS = 3

    /** And the winner must hold more than this many times the runner-up's: a page in two languages is told none. */
    const val LEAD = 2

    /** Tesseract's languages with [primary] first, then the others in [ALL]'s order: `deu+fra+eng+ita+spa`. */
    fun order(primary: OcrLanguage): String =
        (listOf(primary) + ALL.filter { it !== primary }).joinToString("+") { it.tesseract }

    /** The language of [locale] — the app's —, English when the OCR does not read it. */
    fun of(locale: Locale): OcrLanguage = ALL.firstOrNull { it.iso == locale.language } ?: ENGLISH

    /**
     * The language [text] is written in, told by its words of its own ([OWN]): at least [MIN_WORDS] of them,
     * and more than [LEAD] times those of any other language; else null — too short, no language's words, or
     * two languages. Most words of each language's own carry no accent, so a page read with another language
     * first, whose accents came out wrong, is still told: such a word is lost, seldom another's ("però" read
     * "pero", Spanish).
     */
    fun detect(text: String): OcrLanguage? {
        val found = Normalizer.normalize(text, Normalizer.Form.NFC).lowercase(Locale.ROOT)
            .split(NOT_A_LETTER).filter { it.isNotEmpty() }
        val counts = ALL.map { language -> language to found.count { it in OWN.getValue(language) } }
            .sortedByDescending { it.second }
        val (best, most) = counts.first()
        val runnerUp = counts.getOrNull(1)?.second ?: 0
        return best.takeIf { most >= MIN_WORDS && most > LEAD * runnerUp }
    }

    /** The words of [language] that are its own, for the tests. */
    fun ownWords(language: OcrLanguage): Set<String> = OWN.getValue(language)

    private val NOT_A_LETTER = Regex("[^\\p{L}]+")

    private fun words(list: String): Set<String> = list.split(' ').filter { it.isNotEmpty() }.toSet()
}
