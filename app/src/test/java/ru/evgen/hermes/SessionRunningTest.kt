package ru.evgen.hermes

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `running` decides whether a row in the session list pulses, and getting it wrong
 * is immediately visible to the user: either nothing pulses while an answer is
 * being written, or a finished session keeps pulsing.
 *
 * The trap this covers: the server's `is_active` is "no ended_at AND last activity
 * within 300 s", so it is true for every session touched in the last five minutes.
 * A pulse driven by it alone would be noise. The real signal is a non-empty
 * `last_activity_description` plus a fresh `last_activity_at`.
 */
class SessionRunningTest {

    private fun row(
        isActive: Boolean = true,
        description: String? = "receiving stream response",
        activityAgoSeconds: Long = 1
    ): JSONObject {
        val now = System.currentTimeMillis() / 1000.0
        return JSONObject().apply {
            put("id", "local-test")
            put("title", "Тест")
            put("preview", "привет")
            put("message_count", 4)
            put("model", "deepseek-v4.1-flash")
            put("last_active", now - 5)
            put("is_active", isActive)
            if (description == null) put("last_activity_description", JSONObject.NULL)
            else put("last_activity_description", description)
            put("last_activity_at", now - activityAgoSeconds)
        }
    }

    @Test
    fun `fresh activity stamp with a description is a running turn`() {
        val s = Session.from(row())!!
        assertTrue(s.running)
        assertEquals("receiving stream response", s.activity)
    }

    @Test
    fun `stale activity stamp is not running`() {
        // The stamp is the only thing that ends a pulse: the description lingers in
        // the row after the turn, so freshness is the deciding factor.
        val s = Session.from(row(activityAgoSeconds = 600))!!
        assertFalse(s.running)
        assertEquals("", s.activity)
    }

    @Test
    fun `no description and no running`() {
        val s = Session.from(row(description = null))!!
        assertFalse(s.running)
        assertEquals("", s.activity)
    }

    @Test
    fun `blank description is not running`() {
        val s = Session.from(row(description = "   "))!!
        assertFalse(s.running)
    }

    @Test
    fun `is_active alone is not enough`() {
        // is_active=true means "touched in the last 5 minutes", which covers every
        // session the user chatted in recently — not a running turn.
        val s = Session.from(row(isActive = true, description = null))!!
        assertFalse(s.running)
    }

    @Test
    fun `not active is not running even with a fresh stamp`() {
        val s = Session.from(row(isActive = false))!!
        assertFalse(s.running)
    }
}
