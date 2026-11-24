package ru.evgen.hermes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.json.JSONObject
import org.junit.Test

/**
 * Children of a delegation, as the gateway reports them.
 *
 * The screen's whole value is that it does not lie: a child shown as "работает" when
 * it has already failed is worse than no screen at all, because the user then waits
 * for something that will never finish.
 */
class SubagentTest {

    private fun json(vararg pairs: Pair<String, Any?>): JSONObject =
        JSONObject().apply { for ((k, v) in pairs) put(k, v) }

    @Test
    fun `a live child carries goal, model and progress`() {
        val a = Subagent.from(
            json(
                "subagent_id" to "sa-1",
                "goal" to "Проверить схему БД",
                "model" to "deepseek/deepseek-v4.1-flash",
                "status" to "running",
                "tool_count" to 7,
                "last_tool" to "terminal",
                "accepting_steer" to true,
                "started_at" to (System.currentTimeMillis() / 1000.0 - 120)
            )
        )
        assertNotNull(a)
        assertEquals("Проверить схему БД", a!!.goal)
        // The provider prefix is noise on a phone-sized row.
        assertEquals("deepseek-v4.1-flash", a.model)
        assertEquals(7, a.toolCount)
        assertEquals("terminal", a.lastTool)
        assertTrue(a.acceptingSteer)
        assertFalse(a.finished)
        assertTrue("прошло ${a.elapsed}с", a.elapsed in 118..125)
    }

    @Test
    fun `a finished child is marked finished`() {
        for (status in listOf("done", "completed", "failed", "error", "cancelled")) {
            val a = Subagent.from(json("subagent_id" to "sa-2", "status" to status))
            assertTrue("статус $status", a!!.finished)
        }
    }

    @Test
    fun `unknown status is not treated as finished`() {
        // Guessing "готово" for a word we do not know would hide a failure.
        val a = Subagent.from(json("subagent_id" to "sa-3", "status" to "reticulating"))
        assertFalse(a!!.finished)
        assertEquals("reticulating", AgentStatus.label("reticulating"))
    }

    @Test
    fun `status words are translated for the row`() {
        assertEquals("работает", AgentStatus.label("running"))
        assertEquals("готово", AgentStatus.label("done"))
        assertEquals("ошибка", AgentStatus.label("failed"))
        assertEquals("остановлен", AgentStatus.label("cancelled"))
    }

    @Test
    fun `a child without an id is dropped`() {
        assertNull(Subagent.from(json("goal" to "без id")))
    }

    @Test
    fun `missing started_at does not produce a negative clock`() {
        val a = Subagent.from(json("subagent_id" to "sa-4"))
        assertEquals(0L, a!!.elapsed)
    }

    @Test
    fun `child session is optional`() {
        val a = Subagent.from(json("subagent_id" to "sa-5", "child_session_id" to JSONObject.NULL))
        assertNull(a!!.childSessionId)
    }
}
