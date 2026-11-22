package ru.evgen.hermes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Slash parsing decides whether a typed command reaches the server, the app, or the
 * agent as plain text. All three wrong outcomes are silent: a command sent as a prompt
 * makes the model answer prose, and a prompt swallowed as a command disappears.
 */
class SlashTest {

    @Test
    fun `command with arguments splits on the first space`() {
        val parsed = Slash.parse("/title Мой сеанс про файлы")
        assertEquals("title", parsed?.name)
        assertEquals("Мой сеанс про файлы", parsed?.args)
    }

    @Test
    fun `bare command has empty args`() {
        assertEquals("status", Slash.parse("/status")?.name)
        assertEquals("", Slash.parse("/status")?.args)
    }

    @Test
    fun `leading spaces before the slash are tolerated`() {
        assertEquals("model", Slash.parse("   /model")?.name)
    }

    @Test
    fun `plain text is not a command`() {
        assertNull(Slash.parse("Привет, как дела?"))
        assertNull(Slash.parse("2/3 от числа"))
    }

    @Test
    fun `a lone slash is not a command`() {
        assertNull(Slash.parse("/"))
        assertNull(Slash.parse("/  "))
    }

    @Test
    fun `name is case insensitive`() {
        assertEquals("status", Slash.parse("/STATUS")?.name)
    }

    @Test
    fun `palette shows while the name is typed`() {
        assertEquals("/sta", Slash.paletteQuery("/sta"))
        assertEquals("/", Slash.paletteQuery("/"))
    }

    @Test
    fun `palette hides once arguments begin`() {
        // Otherwise the list keeps filtering on the argument text and covers the field.
        assertNull(Slash.paletteQuery("/title Мой"))
        assertNull(Slash.paletteQuery("обычный текст"))
    }

    @Test
    fun `catalog description marks argument taking commands`() {
        val withArgs = SlashItem("title", "Задать название сеанса (использование: /title [name])", "command")
        val withoutArgs = SlashItem("status", "Показать сведения о сеансе, модели, токенах", "command")
        assertTrue(Slash.takesArgs(withArgs))
        assertFalse(Slash.takesArgs(withoutArgs))
    }

    @Test
    fun `stop means the running turn only while a turn streams`() {
        assertTrue(Slash.stopMeansTurn(true))
        assertFalse(Slash.stopMeansTurn(false))
    }

    @Test
    fun `commands that change the transcript ask for a reload`() {
        assertTrue(Slash.refreshesHistory("/compress"))
        assertTrue(Slash.refreshesHistory("/undo 2"))
        assertFalse(Slash.refreshesHistory("/status"))
        assertFalse(Slash.refreshesHistory("не команда"))
    }
}
