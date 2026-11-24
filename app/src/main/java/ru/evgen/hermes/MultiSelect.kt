package ru.evgen.hermes

import org.json.JSONArray

/**
 * Multi-select answers for a clarify question.
 *
 * Kept out of the adapter so the wire format is testable: the gateway decodes a
 * multi-select reply as a JSON array of labels and rejects anything else, so a joined
 * string ("Первый, Третий") is a silently lost answer, not a formatting preference.
 */
object MultiSelect {

    /** The answer string for the gateway, or null when nothing is ticked. */
    fun payload(picked: Collection<String>): String? {
        if (picked.isEmpty()) return null
        return JSONArray(picked.toList()).toString()
    }

    /** Adds or removes one label. A set, because a duplicate label fails decoding. */
    fun toggle(picked: Set<String>, label: String, on: Boolean): Set<String> =
        if (on) picked + label else picked - label
}
