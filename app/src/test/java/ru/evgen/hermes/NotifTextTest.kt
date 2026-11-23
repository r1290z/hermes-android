package ru.evgen.hermes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Notification text and ids.
 *
 * Both mistakes are invisible until the shade is on screen: a `MEDIA:` path takes
 * three lines to say nothing, and an id that is not stable per session turns two
 * answers into two cards the user has to dismiss separately.
 */
class NotifTextTest {

    @Test
    fun `media tags do not reach the shade`() {
        val text = "Файл готов.\nMEDIA:/home/hermes/.hermes/cache/report.pdf"
        assertEquals("Файл готов.", NotifText.compact(text))
    }

    @Test
    fun `newlines collapse into one line`() {
        assertEquals("Первая строка Вторая", NotifText.compact("Первая строка\n\nВторая"))
    }

    @Test
    fun `long answers are cut with an ellipsis`() {
        val long = "слово ".repeat(80)
        val out = NotifText.compact(long, limit = 40)
        assertTrue("нет многоточия: $out", out.endsWith("…"))
        assertEquals(41, out.length)
    }

    @Test
    fun `short text is left alone`() {
        assertEquals("Ок", NotifText.compact("Ок"))
        assertEquals("", NotifText.compact(""))
    }

    @Test
    fun `answer id is stable per session`() {
        assertEquals(
            NotifText.answerId("local-abc"),
            NotifText.answerId("local-abc")
        )
    }

    @Test
    fun `different sessions get different ids`() {
        assertNotEquals(
            NotifText.answerId("local-abc"),
            NotifText.answerId("local-def")
        )
    }

    @Test
    fun `answer ids cannot collide with the fixed notification ids`() {
        // The working card is 1001 and the summary 1000; answer ids live in 5000…5999.
        for (id in listOf("a", "b", "local-1", "", "20261123_090115_bf71b727")) {
            val n = NotifText.answerId(id)
            assertTrue("$id → $n", n in 5_000..5_999)
        }
    }
}
