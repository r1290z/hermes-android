package ru.evgen.hermes

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The channel badge and the session the chat page opens with.
 *
 * Both are pure decisions, and both failed silently before: an unmarked Telegram
 * chat looks like any other, and a session handed over at the wrong moment opens as
 * an empty «Новый сеанс» — no error, just the wrong screen.
 */
class ChannelTest {

    @Test
    fun `telegram is its own channel`() {
        assertEquals(Channel.Kind.TELEGRAM, Channel.kind("telegram"))
        assertEquals("Telegram", Channel.label("telegram"))
    }

    @Test
    fun `desktop web and local sessions share the computer channel`() {
        for (source in listOf("desktop", "gui", "local", "DESKTOP")) {
            assertEquals("неверно для $source", Channel.Kind.DESKTOP, Channel.kind(source))
        }
        assertEquals("ПК", Channel.label("gui"))
    }

    @Test
    fun `terminals and API are labelled by kind`() {
        assertEquals("CLI", Channel.label("cli"))
        assertEquals("CLI", Channel.label("tui"))
        assertEquals("API", Channel.label("api_server"))
        assertEquals("Фон", Channel.label("oneshot"))
    }

    @Test
    fun `unknown source gets no badge`() {
        // A guessed badge is worse than none: the user cannot check it.
        assertEquals(Channel.Kind.NONE, Channel.kind("quantum-tunnel"))
        assertEquals("", Channel.label("quantum-tunnel"))
        assertEquals("", Channel.label(null))
        assertEquals("", Channel.label(""))
    }

    @Test
    fun `screen reader text is a phrase not a tag`() {
        assertEquals("из Telegram", Channel.describe("telegram"))
        assertEquals("с компьютера", Channel.describe("desktop"))
    }
}

class ChatStartTest {

    @Test
    fun `parked handoff wins over launch arguments`() {
        // The reported bug: args were written when the fragment was created, the tap
        // that named a session came later, and only the args were read.
        val (id, title) = ChatFragment.resolveStart(
            parked = "sess-2" to "Второй",
            argId = "sess-1",
            argTitle = "Первый"
        )
        assertEquals("sess-2", id)
        assertEquals("Второй", title)
    }

    @Test
    fun `launch arguments are used when nothing is parked`() {
        val (id, title) = ChatFragment.resolveStart(null, "sess-1", "Первый")
        assertEquals("sess-1", id)
        assertEquals("Первый", title)
    }

    @Test
    fun `nothing at all means a new session`() {
        val (id, title) = ChatFragment.resolveStart(null, null, null)
        assertEquals(null, id)
        assertEquals(null, title)
    }

    @Test
    fun `a parked session without a title still opens it`() {
        val (id, title) = ChatFragment.resolveStart("sess-3" to null, null, null)
        assertEquals("sess-3", id)
        assertEquals(null, title)
    }
}
