package com.readfilestech.read_files_tech.data.ocr

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.readfilestech.read_files_tech.ui.tools.ocr.ocrLanguageNames
import org.junit.jupiter.api.Test
import java.text.Normalizer
import java.util.Locale

/** The OCR's five languages (docs D-033): the table, the order given to Tesseract, and a page's language told. */
class OcrLanguagesTest {
    @Test
    fun `the app's five languages, each with its trained data and its code`() {
        assertThat(OcrLanguages.ALL.map { it.iso }).containsExactly("fr", "en", "de", "it", "es").inOrder()
        assertThat(OcrLanguages.ALL.map { it.tesseract }).containsExactly("fra", "eng", "deu", "ita", "spa").inOrder()
        assertThat(OcrLanguages.ALL.map { it.tesseract }.distinct()).hasSize(OcrLanguages.ALL.size)
    }

    @Test
    fun `Tesseract gets every language, the primary first and the others in the table's order`() {
        assertThat(OcrLanguages.order(OcrLanguages.FRENCH)).isEqualTo("fra+eng+deu+ita+spa")
        assertThat(OcrLanguages.order(OcrLanguages.GERMAN)).isEqualTo("deu+fra+eng+ita+spa")
        assertThat(OcrLanguages.order(OcrLanguages.SPANISH)).isEqualTo("spa+fra+eng+deu+ita")
    }

    @Test
    fun `the app's language is the primary, and a language the OCR does not read falls back to English`() {
        assertThat(OcrLanguages.of(Locale.FRANCE)).isSameInstanceAs(OcrLanguages.FRENCH)
        assertThat(OcrLanguages.of(Locale.forLanguageTag("de-AT"))).isSameInstanceAs(OcrLanguages.GERMAN)
        assertThat(OcrLanguages.of(Locale.forLanguageTag("es-MX"))).isSameInstanceAs(OcrLanguages.SPANISH)
        assertThat(OcrLanguages.of(Locale.forLanguageTag("pt-BR"))).isSameInstanceAs(OcrLanguages.ENGLISH)
    }

    @Test
    fun `a word two languages share tells nothing, and each keeps words of its own`() {
        for (language in OcrLanguages.ALL) {
            val own = OcrLanguages.ownWords(language)
            assertWithMessage(language.tesseract).that(own.size).isAtLeast(MIN_OWN)
            for (other in OcrLanguages.ALL.filter { it !== language }) {
                assertWithMessage("${language.tesseract} / ${other.tesseract}").that(own.intersect(other.words)).isEmpty()
            }
        }
        // "la" is French, Italian and Spanish; "in" English, German and Italian: no language's own.
        assertThat(OcrLanguages.ALL.none { "la" in OcrLanguages.ownWords(it) || "in" in OcrLanguages.ownWords(it) }).isTrue()
    }

    @Test
    fun `the homographs found, each in the lists of the languages it is frequent in, are no language's own`() {
        // The French "il" is the Italian article, "en" and "se" Spanish too, "on" English, "ci" of "ci-joint"
        // and "celle-ci" Italian, "mis" Spanish; the English "an", "am", "was", "will" German, "all" Italian,
        // "per" and "plus" of an invoice Italian and French; the Spanish "bien" French, "e" Italian. A list
        // that misses one is found by the text, not by this test: the cases below are what found these.
        val shared = listOf(
            "il", "en", "se", "on", "si", "un", "le", "la", "de", "du", "qui", "ce", "lui", "ma", "me", "nos", "son",
            "entre", "y", "as", "an", "am", "was", "will", "so", "man", "war", "all", "come", "no", "i", "a", "in",
            "da", "sei", "ha", "era", "uno", "su", "cosa", "o", "es", "des", "bien", "per", "plus", "ci", "mis", "e",
        )
        for (language in OcrLanguages.ALL) {
            assertWithMessage(language.tesseract).that(OcrLanguages.ownWords(language).intersect(shared.toSet())).isEmpty()
        }
        // Found by the reviews (GPT 5.6, Claude): each was told the wrong language.
        assertThat(OcrLanguages.detect("¿Cómo estás? Bien. ¿Y el trabajo? Bien. ¿Y tu familia? Bien."))
            .isNotSameInstanceAs(OcrLanguages.FRENCH)
        assertThat(OcrLanguages.detect("Price per item\nWeight per item\nTax per item")).isNull()
        assertThat(OcrLanguages.detect("Veuillez trouver ci-joint la facture et, ci-dessous, le détail de celle-ci."))
            .isNotSameInstanceAs(OcrLanguages.ITALIAN)
        assertThat(OcrLanguages.detect("Anmeldung per E-Mail oder per Telefon")).isNotSameInstanceAs(OcrLanguages.ITALIAN)
        assertThat(OcrLanguages.detect("Mis à jour le 3 mars, mis en ligne et mis en vente.")).isNotSameInstanceAs(OcrLanguages.SPANISH)
    }

    @Test
    fun `a page's language is told by its words, accents read wrong or not`() {
        assertThat(OcrLanguages.detect(GERMAN)).isSameInstanceAs(OcrLanguages.GERMAN)
        assertThat(OcrLanguages.detect(ITALIAN)).isSameInstanceAs(OcrLanguages.ITALIAN)
        assertThat(OcrLanguages.detect(SPANISH)).isSameInstanceAs(OcrLanguages.SPANISH)
        assertThat(OcrLanguages.detect(FRENCH)).isSameInstanceAs(OcrLanguages.FRENCH)
        assertThat(OcrLanguages.detect(ENGLISH)).isSameInstanceAs(OcrLanguages.ENGLISH)
        // The first readings of the oracle's pages, the primary's letters in another language: still told.
        assertThat(OcrLanguages.detect(GERMAN_READ_ENGLISH_FIRST)).isSameInstanceAs(OcrLanguages.GERMAN)
        assertThat(OcrLanguages.detect(SPANISH_READ_GERMAN_FIRST)).isSameInstanceAs(OcrLanguages.SPANISH)
        // Punctuation and line breaks part words, as spaces do.
        assertThat(OcrLanguages.detect("Der,\ndie.\n(das)")).isSameInstanceAs(OcrLanguages.GERMAN)
        // Upper case and decomposed accents count as the words they are.
        assertThat(OcrLanguages.detect("DER BÄCKER ÖFFNET UND DIE TÜR IST GRÜN")).isSameInstanceAs(OcrLanguages.GERMAN)
        assertThat(OcrLanguages.detect("Lunedì la qualità è più bella, però già così")).isSameInstanceAs(OcrLanguages.ITALIAN)
    }

    @Test
    fun `a page whose every accent came out wrong is still told`() {
        val pages = mapOf(
            GERMAN to OcrLanguages.GERMAN,
            ITALIAN to OcrLanguages.ITALIAN,
            SPANISH to OcrLanguages.SPANISH,
            FRENCH to OcrLanguages.FRENCH,
            ENGLISH to OcrLanguages.ENGLISH,
        )
        for ((page, language) in pages) {
            val bare = Normalizer.normalize(page, Normalizer.Form.NFD).replace(COMBINING, "")
            assertWithMessage(bare).that(OcrLanguages.detect(bare)).isSameInstanceAs(language)
        }
    }

    @Test
    fun `too few words, no language's own, or two languages, so none told and the app's language read`() {
        assertThat(OcrLanguages.detect("")).isNull()
        assertThat(OcrLanguages.detect("Total 1234,56 EUR")).isNull()
        assertThat(OcrLanguages.detect("der und ist")).isSameInstanceAs(OcrLanguages.GERMAN)
        assertThat(OcrLanguages.detect("der und")).isNull()
        // Lines of the oracle's first page, as many words of English as of French: neither leads by twice.
        val mixed = "The quick brown fox jumps over the lazy dog. Read Files Tech reads every file offline. " +
            "Portez ce vieux whisky au juge blond qui fume. Une lettre envoyée à Marseille en été."
        assertThat(OcrLanguages.detect(mixed)).isNull()
        assertThat(OcrLanguages.detect(GERMAN + " " + SPANISH)).isNull()
    }

    @Test
    fun `the screen names the five languages in the app's language`() {
        assertThat(ocrLanguageNames(Locale.FRENCH)).isEqualTo("français, anglais, allemand, italien, espagnol")
        assertThat(ocrLanguageNames(Locale.ENGLISH)).isEqualTo("French, English, German, Italian, Spanish")
    }

    private companion object {
        const val MIN_OWN = 20
        val COMBINING = Regex("\\p{Mn}+")

        const val GERMAN = "Der Bäcker öffnet um sieben Uhr, die Tür ist grün. Österreich und die Schweiz liegen südlich von München."
        const val ITALIAN = "Perché la città è più bella di sera, così dicono. Il caffè è già pronto, però manca lo zucchero " +
            "che ho comprato nella bottega della piazza."
        const val SPANISH = "El niño comió más piña y jamón en el almuerzo. ¿Dónde está la estación? " +
            "Mañana iré a la reunión con mis compañeros."
        const val FRENCH = "Une lettre envoyée à Marseille en été, avec ce qui est dans les colis pour nous et vous."
        const val ENGLISH = "The quick brown fox jumps over the lazy dog, and this is what they have been waiting for."

        /** What Tesseract gave on the S9 (2026-10-23) for the oracle's German page, English first. */
        val GERMAN_READ_ENGLISH_FIRST = """
            Zwôlf Boxkampfer jagen Viktor quer Uber den Sylter Deich
            Die Größe der Straße hängt vom Maß des FuRwegs ab
            Osterreich und die Schweiz liegen südlich von München
            Der Bäcker öffnet um sieben Uhr, die Tür ist grün
            Rechnung Nummer 4821 vom 22. Oktober 2026
            Gesamtbetrag 1234,56 EUR fällig am Freitag
            Übermäßige Ärgernisse führen zu schlechter Laune
            Nichts auf diesem Bild verlässt das Telefon
        """.trimIndent()

        /** And for its Spanish page, German first. */
        val SPANISH_READ_GERMAN_FIRST = """
            El veloz murcielago hindü comia feliz cardillo y kiwi
            La cigüeña tocaba el saxofön deträs del palenque
            ¿Dónde está la estación? ¡Qué lástima, ya salió!
            Factura número 4821 del 22 de octubre de 2026
            Importe total 1234,56 EUR antes del viernes

            El niño comió más piña y jamón en el almuerzo
            Mañana iré a la reunión con mis compañeros
            Nada de esta imagen sale del teléfono
        """.trimIndent()
    }
}
