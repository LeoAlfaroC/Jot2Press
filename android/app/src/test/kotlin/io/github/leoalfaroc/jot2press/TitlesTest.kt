package io.github.leoalfaroc.jot2press

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

class TitlesTest {
    private val day = LocalDate.of(2026, 10, 11)

    private fun title(pattern: String, language: TitleLanguage = TitleLanguage.ENGLISH, device: Locale = Locale.GERMAN) =
        Titles.build(pattern, language, day, device)

    @Test
    fun `the default pattern in each language`() {
        assertEquals("2026-10-11 · Sunday, October 11", title(Titles.DEFAULT_PATTERN))
        assertEquals("2026-10-11 · domingo, octubre 11", title(Titles.DEFAULT_PATTERN, TitleLanguage.SPANISH))
        assertEquals("2026-10-11 · dimanche, octobre 11", title(Titles.DEFAULT_PATTERN, TitleLanguage.DEVICE, Locale.FRENCH))
    }

    @Test
    fun `other patterns`() {
        assertEquals("11 Oct 2026", title("d MMM yyyy"))
        assertEquals("domingo 11 de octubre de 2026", title("EEEE d 'de' MMMM 'de' yyyy", TitleLanguage.SPANISH))
        assertEquals("Day 284", title("'Day' D"))
        assertEquals("Journal", title(" 'Journal' "))
    }

    @Test
    fun `a pattern that cannot make a title is refused`() {
        assertNull(title("yyyy-MM-dd 'unclosed"))
        assertNull(title("yyyy {MM}"))
        assertNull(title("yyyy-MM-dd HH:mm"))
        assertNull(title(""))
        assertNull(title("   "))
        assertNull(title("'" + "x".repeat(201) + "'"))
        assertEquals(200, title("'" + "x".repeat(200) + "'")!!.length)
    }
}
