package ru.evgen.hermes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What survives a transcript reload.
 *
 * The reported bug: a clarify card was visible for about a second and then vanished,
 * and its options could not be pressed. The cause is here — `submit()` rebuilt the
 * list from `GET /messages`, and a question is not in that list: the server keeps it
 * as an open request, not a message. A poll two seconds later therefore deleted the
 * card the user was about to answer.
 */
class ReloadTest {

    private data class Card(
        override val cardRequestId: String,
        override val cardAnswered: Boolean
    ) : PendingCard

    @Test
    fun `unanswered cards survive a reload`() {
        val kept = Reload.keep(listOf(Card("srq-1", false)))
        assertEquals(1, kept.size)
        assertEquals("srq-1", kept[0].cardRequestId)
    }

    @Test
    fun `answered cards are dropped`() {
        // The answer is already with the server; re-showing the card invites a second
        // answer the gateway rejects.
        assertTrue(Reload.keep(listOf(Card("srq-1", true))).isEmpty())
    }

    @Test
    fun `a mix keeps only what is still pending`() {
        val kept = Reload.keep(
            listOf(Card("srq-1", true), Card("srq-2", false), Card("srq-3", true))
        )
        assertEquals(listOf("srq-2"), kept.map { it.cardRequestId })
    }

    @Test
    fun `no cards means nothing to do`() {
        assertTrue(Reload.keep(emptyList<Card>()).isEmpty())
    }
}
