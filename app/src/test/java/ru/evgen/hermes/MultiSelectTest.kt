package ru.evgen.hermes

import org.junit.Assert.assertEquals
import org.json.JSONArray
import org.junit.Test

/**
 * Multi-select answers.
 *
 * The gateway decodes a multi-select reply as a JSON array of labels
 * (`clarify_gateway._coerce_multi_select_text`), so a joined string like
 * "Первый, Третий" is rejected outright. The wire format is therefore not a detail —
 * it is the difference between an answer and a silently dropped tap.
 */
class MultiSelectTest {

    @Test
    fun `selected labels go out as a json array`() {
        val payload = MultiSelect.payload(listOf("Первый", "Третий"))
        assertEquals("[\"Первый\",\"Третий\"]", payload)
        assertEquals(listOf("Первый", "Третий"), JSONArray(payload).let { a ->
            (0 until a.length()).map { a.getString(it) }
        })
    }

    @Test
    fun `a single pick is still an array`() {
        // Not "Первый": the server reads arrays for multi-select and prose otherwise.
        assertEquals("[\"Первый\"]", MultiSelect.payload(listOf("Первый")))
    }

    @Test
    fun `cyrillic and quotes survive encoding`() {
        val payload = MultiSelect.payload(listOf("Свернуть в трей", "Не сворачивать \"вообще\""))
        assertEquals(2, JSONArray(payload).length())
        assertEquals("Свернуть в трей", JSONArray(payload).getString(0))
    }

    @Test
    fun `empty selection is not an answer`() {
        assertEquals(null, MultiSelect.payload(emptyList()))
    }

    @Test
    fun `toggling adds and removes without duplicates`() {
        var picked = MultiSelect.toggle(emptySet(), "A", true)
        picked = MultiSelect.toggle(picked, "B", true)
        picked = MultiSelect.toggle(picked, "A", false)
        assertEquals(setOf("B"), picked)
        // Ticking twice must not produce ["B","B"] — the server would decode a
        // duplicate label and reject the whole reply.
        assertEquals(setOf("B"), MultiSelect.toggle(picked, "B", true))
    }
}
