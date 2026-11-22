package ru.evgen.hermes

import android.content.Context

/**
 * Ruble rates per 1M tokens, and the cost arithmetic built on them.
 *
 * Why the phone does this and not the gateway: `estimated_cost` from
 * `/api/analytics/usage` is 0 for every custom-provider row, because the gateway has
 * no price table for PlusVibe. Tokens are exact; the money has to be derived.
 *
 * Rates are per lane, and the lane is the suffix of the model string the gateway
 * reports (`deepseek-v4.1-flash:purple`). They are seeded from the provider's own
 * `/v1/pricing` (fetched 22.11.2026) and are editable in the panel, because a
 * provider can reprice a lane overnight and a hardcoded number would then be a
 * confident lie.
 */
object Prices {

    /** model substring → (input, output, cache read) ₽ per 1M tokens. */
    private val DEFAULTS: List<Triple<String, Rates, String>> = listOf(
        // Exact lanes of deepseek-v4.1-flash, the model this gateway actually runs.
        Triple("deepseek-v4.1-flash:purple", Rates(4.01, 16.02, 0.08), "purple"),
        Triple("deepseek-v4.1-flash:yellow2", Rates(2.42, 3.87, 0.48), "yellow2"),
        Triple("deepseek-v4.1-flash:yellow", Rates(1.94, 2.42, 0.39), "yellow"),
        Triple("deepseek-v4.1-flash:network", Rates(2.83, 11.32, 0.28), "network"),
        Triple("deepseek-v4.1-flash:cxbc2", Rates(5.00, 20.00, 0.50), "cxbc2"),
        Triple("deepseek-v4.1-flash:cxbc", Rates(1.91, 7.63, 0.19), "cxbc"),
        Triple("deepseek-v4.1-flash:cxb", Rates(1.91, 7.63, 0.19), "cxb"),
        Triple("deepseek-v4.1-flash-1017:cxb", Rates(2.00, 8.00, 0.20), "1017 cxb"),
        Triple("deepseek-v4.1-flash-1017:network", Rates(2.34, 9.34, 0.23), "1017 network"),
        Triple("deepseek-v4-flash-0731:network", Rates(2.05, 6.14, 0.04), "0731 network"),
        Triple("deepseek-v4-flash-0731:yellow", Rates(1.94, 2.42, 0.39), "0731 yellow"),
        // Other models the gateway has actually run (kanban workers use glm).
        Triple("glm-5.3-flash:purple", Rates(1.34, 4.45, 0.27), "purple"),
        Triple("glm-5.3-flash:cxbc", Rates(2.00, 6.67, 0.20), "cxbc"),
        Triple("glm-5.3-flash", Rates(1.89, 6.29, 0.19), "default (cxb)"),
        Triple("glm-5.3:yellow", Rates(1.26, 3.96, 0.13), "yellow"),
        Triple("glm-5.3", Rates(15.49, 48.68, 2.00), "default (cxb)"),
        // Bare `deepseek-v4.1-flash` is the provider default lane: cxb.
        Triple("deepseek-v4.1-flash", Rates(1.91, 7.63, 0.19), "default (cxb)")
    )

    /** Something has to be assumed for a model nobody priced. Deliberately the
     *  cheapest plausible lane, so an unknown model under-reports rather than
     *  inventing a scary number — and the row says so. */
    private val FALLBACK = Rates(2.00, 8.00, 0.20)

    data class Rates(val input: Double, val output: Double, val cacheRead: Double) {
        /** Cost of a token bundle in rubles. */
        fun cost(inputTokens: Long, outputTokens: Long, cacheTokens: Long): Double =
            inputTokens / 1_000_000.0 * input +
                outputTokens / 1_000_000.0 * output +
                cacheTokens / 1_000_000.0 * cacheRead

        fun edit(input: Double = this.input, output: Double = this.output, cacheRead: Double = this.cacheRead) =
            Rates(input, output, cacheRead)
    }

    data class Resolved(val rates: Rates, val label: String, val exact: Boolean)

    private fun prefs(context: Context) =
        context.getSharedPreferences("hermes_prices", Context.MODE_PRIVATE)

    /** Overrides saved from the panel, keyed by the full model string. */
    private fun override(context: Context, model: String): Rates? {
        val raw = prefs(context).getString("rate:$model", null) ?: return null
        val parts = raw.split(",")
        if (parts.size != 3) return null
        val nums = parts.mapNotNull { it.toDoubleOrNull() }
        if (nums.size != 3) return null
        return Rates(nums[0], nums[1], nums[2])
    }

    fun setOverride(context: Context, model: String, rates: Rates) {
        prefs(context).edit()
            .putString("rate:$model", "${rates.input},${rates.output},${rates.cacheRead}")
            .apply()
    }

    fun clearOverride(context: Context, model: String) {
        prefs(context).edit().remove("rate:$model").apply()
    }

    fun hasOverride(context: Context, model: String): Boolean = override(context, model) != null

    /**
     * Picks the rate for a model: a saved override wins, then the longest matching
     * lane in [DEFAULTS] (so `…:purple` beats a bare `…flash`), then the fallback.
     */
    fun resolve(context: Context, model: String): Resolved {
        override(context, model)?.let { return Resolved(it, laneLabel(model), true) }
        val hit = DEFAULTS
            .filter { model.contains(it.first, ignoreCase = true) }
            .maxByOrNull { it.first.length }
        return if (hit != null) Resolved(hit.second, hit.third, true)
        else Resolved(FALLBACK, "не в тарифе", false)
    }

    private fun laneLabel(model: String): String {
        val lane = model.substringAfterLast(':', "")
        return if (lane.isEmpty()) "своя цена" else "$lane (своя цена)"
    }

    fun cost(context: Context, u: ModelUsage): Double =
        resolve(context, u.model).rates.cost(u.inputTokens, u.outputTokens, u.cacheReadTokens)

    /** Whole-period cost: every model's cost, each at its own rate. */
    fun costOf(context: Context, models: List<ModelUsage>): Double =
        models.sumOf { cost(context, it) }

    /** Money on screen: rubles, no cents above a hundred (a ₽0.4 is noise). */
    fun money(value: Double): String = when {
        value <= 0 -> "0 ₽"
        value < 1 -> String.format("%.2f ₽", value)
        value < 100 -> String.format("%.1f ₽", value)
        else -> String.format("%.0f ₽", value)
    }

    /** Token counts, as a person reads them. */
    fun tokens(value: Long): String = when {
        value >= 1_000_000_000 -> String.format("%.1f млрд", value / 1_000_000_000.0)
        value >= 1_000_000 -> String.format("%.1f млн", value / 1_000_000.0)
        value >= 1_000 -> String.format("%.0f тыс", value / 1_000.0)
        else -> value.toString()
    }
}
