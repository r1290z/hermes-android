package ru.evgen.hermes

/**
 * A card that lives outside the transcript: the agent is waiting on it.
 *
 * The chat reloads history constantly — on attach, on every poll that sees a new
 * message, after a compaction. A plain "clear and rebuild" drops anything that came
 * from an event rather than from `GET /messages`, and a clarify card is exactly that:
 * it exists only until it is answered, and the server's transcript never contains it.
 * That is why the question used to flash for a second and vanish — the reload right
 * after it arrived wiped it.
 */
interface PendingCard {
    val cardRequestId: String
    val cardAnswered: Boolean
}

object Reload {

    /**
     * The cards a reload must put back after rebuilding the transcript.
     *
     * Answered cards are dropped on purpose: the answer is already with the server,
     * and re-showing a question that has been settled invites a second answer that
     * the gateway would reject.
     */
    fun <T : PendingCard> keep(cards: List<T>): List<T> = cards.filter { !it.cardAnswered }
}
