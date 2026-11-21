package ru.evgen.hermes

import org.json.JSONObject

/**
 * A server→client request (JSON-RPC call the gateway makes to us).
 *
 * These only arrive after the client announces `client.capabilities
 * {"server_requests": true}`; without that handshake the gateway never sends
 * them and the agent's question dies on a timeout (clarify 300 s). The frame
 * carries a string id `srq-<hex>` and one of two methods:
 *
 *  - `clarify` — one or more questions; each is answered separately via the
 *    `clarify.lock` RPC (`answer: null` = skip), and the whole batch can be
 *    dismissed by replying to the srq id itself with an empty result.
 *  - `approval` — permission for a command; the reply to the srq id carries
 *    `{"choice": "once|session|always|deny"}`.
 */
sealed class ServerRequest {
    abstract val requestId: String

    /** Generation the prompt belongs to; canonical responses are fenced by it. */
    open val generation: Int? = null

    /** The `srq-…` frame id on the srq wire. The reply frame must carry it —
     *  `resolve_response` only accepts ids of open requests (`_open`), and
     *  `params.request_id` (the approval queue id) is not one of them. */
    open val srqId: String? = null

    /** Canonical /api/ws projection answers via `clarify.respond` /
     *  `approval.respond` instead of a frame reply to the srq id. */
    open val canonical: Boolean = false

    data class Question(
        val qid: String,
        val question: String,
        val choices: List<String>,
        val multiSelect: Boolean
    )

    data class Clarify(
        override val requestId: String,
        val questions: List<Question>,
        override val generation: Int? = null,
        override val canonical: Boolean = false,
        override val srqId: String? = null
    ) : ServerRequest()

    data class Approval(
        override val requestId: String,
        val command: String,
        val description: String,
        val choices: List<String>,
        override val generation: Int? = null,
        override val canonical: Boolean = false,
        override val srqId: String? = null
    ) : ServerRequest() {
        /** First line of the description is the human-facing prompt; the raw
         *  command is shown too (the server already redacts secrets from it)
         *  but must never be logged. */
        val title: String get() = description.lineSequence().firstOrNull { it.isNotBlank() }
            ?: "Разрешить команду?"
    }

    companion object {
        fun from(frame: JSONObject): ServerRequest? {
            val id = frame.optString("id").ifEmpty { return null }
            return when (frame.optString("method")) {
                "clarify" -> {
                    val params = frame.optJSONObject("params") ?: return null
                    val arr = params.optJSONArray("questions") ?: return null
                    val questions = mutableListOf<Question>()
                    for (i in 0 until arr.length()) {
                        val q = arr.optJSONObject(i) ?: continue
                        val qid = q.optString("qid").ifEmpty { "q$i" }
                        questions += Question(
                            qid = qid,
                            question = q.optString("question"),
                            choices = q.optJSONArray("choices")?.let { a ->
                                (0 until a.length()).mapNotNull { a.optString(it).ifEmpty { null } }
                            } ?: emptyList(),
                            multiSelect = q.optBoolean("multi_select", false)
                        )
                    }
                    if (questions.isEmpty()) null else Clarify(id, questions, srqId = id)
                }
                "approval" -> {
                    val params = frame.optJSONObject("params") ?: return null
                    Approval(
                        // Reply frames are matched by the srq id (see srqId);
                        // params.request_id is the approval queue id and must
                        // not leak into the reply's `id`.
                        requestId = id,
                        command = params.optString("command"),
                        description = params.optString("description"),
                        choices = params.optJSONArray("choices")?.let { a ->
                            (0 until a.length()).mapNotNull { a.optString(it).ifEmpty { null } }
                        } ?: listOf("once", "session", "always", "deny"),
                        srqId = id
                    )
                }
                else -> null
            }
        }

        /** `session.resume` returns `open_requests` — the same frames that were
         *  pending before the reconnect, replayed as plain objects. */
        fun fromResumeEntry(o: JSONObject): ServerRequest? =
            from(JSONObject()
                .put("id", o.optString("id"))
                .put("method", o.optString("method"))
                .put("params", o.optJSONObject("params") ?: o))

        /** Canonical projection (`/api/ws` events `clarify.request` /
         *  `approval.request`, and the `prompts` array of a resume result):
         *  one prompt per entry, answered by `clarify.respond`/`approval.respond`
         *  with `{session_id, execution_generation, prompt_id}`. An empty
         *  answer string is a documented skip, never a timeout. */
        fun fromPrompt(payload: JSONObject): ServerRequest? {
            val promptId = payload.optString("prompt_id").ifEmpty { return null }
            val generation = if (payload.isNull("execution_generation")) null
            else payload.optInt("execution_generation")
            return when (payload.optString("kind")) {
                "clarify" -> {
                    val question = payload.optString("question").ifEmpty { return null }
                    Clarify(
                        requestId = promptId,
                        questions = listOf(Question(
                            qid = promptId,
                            question = question,
                            choices = payload.optJSONArray("choices")?.let { a ->
                                (0 until a.length()).mapNotNull { a.optString(it).ifEmpty { null } }
                            } ?: emptyList(),
                            multiSelect = payload.optBoolean("multi_select", false)
                        )),
                        generation = generation,
                        canonical = true
                    )
                }
                "approval" -> Approval(
                    requestId = promptId,
                    command = payload.optString("command"),
                    description = payload.optString("description"),
                    choices = payload.optJSONArray("choices")?.let { a ->
                        (0 until a.length()).mapNotNull { a.optString(it).ifEmpty { null } }
                    } ?: listOf("once", "session", "always", "deny"),
                    generation = generation,
                    canonical = true
                )
                else -> null
            }
        }
    }
}
