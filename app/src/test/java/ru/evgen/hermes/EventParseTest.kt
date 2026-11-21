package ru.evgen.hermes

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the pure event parse — the regressions of the last two weeks
 * (`null` instead of the answer, lost deltas, foreign-session noise) all lived
 * here and needed no phone to reproduce.
 */
class EventParseTest {

    private fun parse(type: String, payloadJson: String): ChatEvent? =
        EventParser.parseEvent(type, JSONObject(payloadJson))

    @Test
    fun deltaWithTextIsDelta() {
        val e = parse("message.delta", """{"text":"при"}""")
        assertEquals(ChatEvent.Delta("при"), e)
    }

    @Test
    fun deltaWithNullTextIsDropped_notLiteralNull() {
        // org.json's optString turns JSON null into "null"; the parse must not.
        val e = parse("message.delta", """{"text":null}""")
        assertNull(e)
    }

    @Test
    fun deltaWithEmptyTextIsDropped() {
        assertNull(parse("message.delta", """{"text":""}"""))
    }

    @Test
    fun completeWithNullTextIsCompletedEmpty() {
        val e = parse("message.complete", """{"text":null}""")
        assertEquals(ChatEvent.Completed(""), e)
    }

    @Test
    fun completeWithTextIsCompleted() {
        assertEquals(ChatEvent.Completed("готово"), parse("message.complete", """{"text":"готово"}"""))
    }

    @Test
    fun thinkingDeltaIsReasoning() {
        assertEquals(ChatEvent.Reasoning("думаю"), parse("thinking.delta", """{"text":"думаю"}"""))
        assertNull(parse("thinking.delta", """{"text":null}"""))
    }

    @Test
    fun toolStartCarriesNameAndArgs() {
        val e = parse("tool.start", """{"name":"terminal","context":"ls","args":{"command":"ls"}}""")
        assertTrue(e is ChatEvent.ToolStarted)
        e as ChatEvent.ToolStarted
        assertEquals("terminal", e.name)
        assertEquals("ls", e.preview)
        assertEquals("""{"command":"ls"}""", e.args)
    }

    @Test
    fun toolCompleteCarriesNameAndErrorFlag() {
        val ok = parse("tool.complete", """{"name":"terminal","context":"ls","is_error":false}""")
        assertTrue(ok is ChatEvent.ToolFinished && !ok.failed)
        val err = parse("tool.complete", """{"name":"rpc","context":"x","is_error":true}""")
        assertTrue(err is ChatEvent.ToolFinished && err.failed)
    }

    @Test
    fun unknownTypeIsNull() {
        assertNull(parse("session.updated", """{"x":1}"""))
    }

    // ── session filter ──────────────────────────────────────────────────────

    @Test
    fun foreignSessionEventIsRejected() {
        val frame = JSONObject("""{"type":"message.delta","session_id":"other","payload":{"text":"x"}}""")
        assertFalse(EventParser.belongsToSession(frame, listOf("mine")))
    }

    @Test
    fun ownSessionEventIsAccepted() {
        val frame = JSONObject("""{"type":"message.delta","session_id":"mine","payload":{"text":"x"}}""")
        assertTrue(EventParser.belongsToSession(frame, listOf("stored-row-id", "mine")))
    }

    @Test
    fun sessionlessEventIsAcceptedWhenBound() {
        val frame = JSONObject("""{"type":"message.delta","payload":{"text":"x"}}""")
        assertTrue(EventParser.belongsToSession(frame, listOf("mine")))
    }

    @Test
    fun unboundAcceptsEverything() {
        val frame = JSONObject("""{"type":"message.delta","session_id":"whatever"}""")
        assertTrue(EventParser.belongsToSession(frame, emptyList()))
    }
}
