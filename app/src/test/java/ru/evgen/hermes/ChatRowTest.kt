package ru.evgen.hermes

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * JVM tests for the transcript-merge half of the same regressions: a blank or
 * literal-"null" completion must never wipe the streamed deltas it follows,
 * and a stored message must keep its server timestamp when flattened to a row.
 *
 * toRow is private on ChatAdapter (an Android RecyclerView.Adapter), so these
 * tests go through the public API the way the chat screen does: appendDelta /
 * finishTurn for the streaming turn, submit for history flattening. Robolectric
 * supplies the Android runtime the notify* calls need.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatRowTest {

    private fun adapter() = ChatAdapter(
        androidx.test.core.app.ApplicationProvider.getApplicationContext(),
        {}
    )

    @Test
    fun emptyCompletionDoesNotWipeStreamedText() {
        val a = adapter()
        a.appendDelta("ответ ")
        a.appendDelta("модели")
        a.finishTurn("")
        assertEquals("ответ модели", a.currentAssistantText())
    }

    @Test
    fun literalNullCompletionDoesNotWipeStreamedText() {
        val a = adapter()
        a.appendDelta("накопленный")
        a.finishTurn("null")
        assertEquals("накопленный", a.currentAssistantText())
    }

    @Test
    fun realCompletionReplacesStreamedText() {
        val a = adapter()
        a.appendDelta("черновик")
        a.finishTurn("готово")
        assertEquals("готово", a.currentAssistantText())
    }

    @Test
    fun toRowCarriesTimestampFromChatMessage() {
        val a = adapter()
        // timestamp is seconds-since-epoch on the wire, rows hold milliseconds
        a.submit(
            listOf(
                ChatMessage(role = "user", content = "вопрос", toolName = null, reasoning = null, timestamp = 1763800000.0),
                ChatMessage(role = "assistant", content = "ответ", toolName = null, reasoning = null, timestamp = 1763800060.5),
            )
        )
        assertEquals(1763800000000L, a.timeAt(0))
        assertEquals(1763800060500L, a.timeAt(1))
    }

    @Test
    fun noRowsMeansZeroTime() {
        val a = adapter()
        assertEquals(0L, a.timeAt(0))
    }
}
