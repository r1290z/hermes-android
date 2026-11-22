package ru.evgen.hermes

import org.json.JSONObject

/**
 * One day of the gateway's usage report (`GET /api/analytics/usage`).
 *
 * The server sends `estimated_cost` / `actual_cost`, but on a custom provider
 * (PlusVibe) both are always 0 — the gateway has no price table for it. So the cost
 * shown here is computed on the phone from tokens and ruble rates (see [Prices]);
 * the tokens themselves are the server's own accounting and are exact.
 */
data class UsageDay(
    val day: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheReadTokens: Long,
    val reasoningTokens: Long,
    val sessions: Int,
    val apiCalls: Int
) {
    val totalTokens: Long get() = inputTokens + outputTokens + cacheReadTokens
    /** Share of the prompt that came from cache — the number that actually
     *  explains a cheap day versus an expensive one. */
    val cacheShare: Double get() = if (inputTokens + cacheReadTokens == 0L) 0.0
    else cacheReadTokens.toDouble() / (inputTokens + cacheReadTokens)

    companion object {
        fun from(o: JSONObject) = UsageDay(
            day = o.optString("day"),
            inputTokens = o.optLong("input_tokens", 0),
            outputTokens = o.optLong("output_tokens", 0),
            cacheReadTokens = o.optLong("cache_read_tokens", 0),
            reasoningTokens = o.optLong("reasoning_tokens", 0),
            sessions = o.optInt("sessions", 0),
            apiCalls = o.optInt("api_calls", 0)
        )
    }
}

/** One model's totals over the period (`GET /api/analytics/models`). */
data class ModelUsage(
    val model: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheReadTokens: Long,
    val sessions: Int,
    val apiCalls: Int,
    val toolCalls: Int,
    val lastUsedAt: Long
) {
    companion object {
        fun from(o: JSONObject) = ModelUsage(
            model = o.optString("model").ifEmpty { "?" },
            inputTokens = o.optLong("input_tokens", 0),
            outputTokens = o.optLong("output_tokens", 0),
            cacheReadTokens = o.optLong("cache_read_tokens", 0),
            sessions = o.optInt("sessions", 0),
            apiCalls = o.optInt("api_calls", 0),
            toolCalls = o.optInt("tool_calls", 0),
            lastUsedAt = (o.optDouble("last_used_at", 0.0) * 1000).toLong()
        )
    }
}
