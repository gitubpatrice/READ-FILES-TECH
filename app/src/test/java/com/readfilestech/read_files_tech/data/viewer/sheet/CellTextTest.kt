package com.readfilestech.read_files_tech.data.viewer.sheet

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * What a cell shows in each language of the app (docs D-033): Excel's short date for the built-in formats 14
 * and 22, and Excel's words for the booleans. Before the five languages, every language but French read
 * American dates (3/5/2026) and TRUE/FALSE.
 */
class CellTextTest {
    /** 5 March 2026 at 14:30, as a serial of the 1900 calendar. */
    private val march5 = ChronoUnit.DAYS.between(LocalDate.of(1899, 12, 30), LocalDate.of(2026, 3, 5)) + 14.5 / 24

    private fun date(locale: Locale) = CellText(locale).formatNumber(march5, 14, "m/d/yy", date1904 = false)

    private fun dateTime(locale: Locale) = CellText(locale).formatNumber(march5, 22, "m/d/yy h:mm", date1904 = false)

    @Test
    fun `the short date of formats 14 and 22 is the language's, with four digits for the year`() {
        assertThat(date(Locale.US)).isEqualTo("3/5/2026")
        assertThat(date(Locale.FRANCE)).isEqualTo("05/03/2026")
        assertThat(date(Locale.GERMANY)).isEqualTo("05.03.2026")
        assertThat(date(Locale.ITALY)).isEqualTo("05/03/2026")
        assertThat(date(Locale.forLanguageTag("es-ES"))).isEqualTo("05/03/2026")
        assertThat(date(Locale.UK)).isEqualTo("05/03/2026")
    }

    @Test
    fun `format 22 adds the time to the same date`() {
        assertThat(dateTime(Locale.GERMANY)).isEqualTo("05.03.2026 14:30")
        assertThat(dateTime(Locale.US)).isEqualTo("3/5/2026 14:30")
    }

    @Test
    fun `the booleans are Excel's words in each language, English for any other`() {
        val spanish = Locale.forLanguageTag("es-ES")
        val locales = listOf(Locale.US, Locale.FRANCE, Locale.GERMANY, Locale.ITALY, spanish, Locale.forLanguageTag("pt-BR"))
        val shown = locales.map { CellText(it).booleanText(true) + "/" + CellText(it).booleanText(false) }
        assertThat(shown).containsExactly("TRUE/FALSE", "VRAI/FAUX", "WAHR/FALSCH", "VERO/FALSO", "VERDADERO/FALSO", "TRUE/FALSE").inOrder()
    }
}
