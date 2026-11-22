package ru.evgen.hermes

import org.json.JSONObject

/**
 * One node of `projects.tree`: a project (or the auto "Home" bucket) with its
 * session totals.
 *
 * Parsing lives here, not in ApiClient, so the JVM tests exercise the exact
 * code the screen runs. `path` is null for Home — org.json's optString turns a
 * JSON null into the literal "null", so every nullable string goes through
 * isNull() first (same lesson as EventParser's `text: null`).
 */
data class Project(
    val id: String,
    val label: String,
    val path: String?,
    val sessionCount: Int,
    val lastActive: Double,
    val totalTokens: Long,
    val isAuto: Boolean,
    val isNoProject: Boolean,
    val previewCount: Int
) {
    companion object {
        fun from(json: JSONObject): Project? {
            val id = json.optString("id")
            if (id.isEmpty()) return null
            val path = if (json.isNull("path")) null
            else json.optString("path").ifEmpty { null }
            return Project(
                id = id,
                label = json.optString("label").ifEmpty { id },
                path = path,
                sessionCount = json.optInt("sessionCount", 0),
                lastActive = json.optDouble("lastActive", 0.0),
                totalTokens = json.optLong("totalTokens", 0L),
                isAuto = json.optBoolean("isAuto", false),
                isNoProject = json.optBoolean("isNoProject", false),
                previewCount = json.optJSONArray("previewSessions")?.length() ?: 0
            )
        }

        /** The whole tree, Home first, the rest by lastActive descending. */
        fun listFrom(result: JSONObject?): List<Project> {
            val arr = result?.optJSONArray("projects") ?: return emptyList()
            val out = mutableListOf<Project>()
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { from(it)?.let(out::add) }
            }
            return out.sortedWith(
                compareByDescending<Project> { it.isNoProject }
                    .thenByDescending { it.lastActive }
            )
        }
    }
}
