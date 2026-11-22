package ru.evgen.hermes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The money in the expenses panel is computed, not received: the gateway reports 0
 * for a custom provider, so a wrong rate or a wrong divisor shows up as a bill that
 * is off by an order of magnitude and nobody notices until the provider's own invoice.
 *
 * These fix the arithmetic and the formatting rules.
 */
class PricesTest {

    private val purple = Prices.Rates(4.01, 16.02, 0.08)

    @Test
    fun `one million input tokens at the purple rate`() {
        assertEquals(4.01, purple.cost(1_000_000, 0, 0), 0.0001)
    }

    @Test
    fun `output is priced separately from input`() {
        assertEquals(16.02, purple.cost(0, 1_000_000, 0), 0.0001)
    }

    @Test
    fun `cache reads use the cache rate, not the input rate`() {
        // 603M cached tokens are the difference between a 6 ₽ day and a 40 ₽ day.
        assertEquals(0.08, purple.cost(0, 0, 1_000_000), 0.0001)
    }

    @Test
    fun `a realistic mix matches the hand calculation`() {
        // 35 387 141 in, 2 088 659 out, 603 932 928 cache — one real week of usage.
        val cost = purple.cost(35_387_141, 2_088_659, 603_932_928)
        val expected = 35_387_141 / 1e6 * 4.01 + 2_088_659 / 1e6 * 16.02 + 603_932_928 / 1e6 * 0.08
        assertEquals(expected, cost, 0.001)
        assertTrue("стоимость должна быть около сотни рублей, а не тысячи", cost in 100.0..300.0)
    }

    @Test
    fun `zero tokens cost nothing`() {
        assertEquals(0.0, purple.cost(0, 0, 0), 0.0)
    }

    @Test
    fun `cheap cache is what makes a huge prompt affordable`() {
        // Same token count, once as input and once from cache: the ratio is the
        // entire reason the gateway is usable with 200M-token sessions.
        val asInput = purple.cost(10_000_000, 0, 0)
        val asCache = purple.cost(0, 0, 10_000_000)
        assertTrue("кэш должен быть минимум в 10 раз дешевле", asInput > asCache * 10)
    }

    @Test
    fun `money formatting drops cents where they are noise`() {
        assertEquals("0 ₽", Prices.money(0.0))
        assertEquals("0.42 ₽", Prices.money(0.42))
        assertEquals(String.format("%.1f ₽", 42.35), Prices.money(42.35))
        assertEquals(String.format("%.0f ₽", 1176.4), Prices.money(1176.4))
    }

    @Test
    fun `token formatting uses the russian scale`() {
        assertEquals("999", Prices.tokens(999))
        assertEquals(String.format("%.0f тыс", 12.3), Prices.tokens(12_300))
        assertEquals(String.format("%.1f млн", 603.9), Prices.tokens(603_932_928))
        assertEquals(String.format("%.1f млрд", 2.1), Prices.tokens(2_100_000_000))
    }
}
