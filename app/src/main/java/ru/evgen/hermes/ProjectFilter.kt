package ru.evgen.hermes

import androidx.lifecycle.ViewModel

/**
 * Shared state of the project filter: what ProjectsFragment picked and what
 * SessionsFragment should show. Lives in a ViewModel because the pager keeps
 * both fragments at arm's length — a direct reference from the activity would
 * break the moment the chat page joins and pages start being recreated.
 *
 * The matching logic itself ([matches]) is deliberately outside any Android
 * class so the JVM tests run the exact code the list runs.
 */
class ProjectFilterModel : ViewModel() {

    /** Selected project, null = «Все сеансы». */
    var selected: Project? = null

    /** Paths of every project the server knows — needed to compute the Home
     *  bucket (a session that belongs to no project with a path). */
    var knownPaths: List<String> = emptyList()

    /** True when the gateway does not send `cwd` at all: then filtering is
     *  skipped instead of showing an empty list for every project. */
    val cwdSupported: Boolean
        get() = knownPaths.isNotEmpty()

    fun matches(session: Session): Boolean = matches(session, selected, knownPaths)

    companion object {

        /** cwd belongs to a path: exact match or a real subdirectory. */
        private fun under(cwd: String, path: String): Boolean =
            cwd == path || cwd.startsWith(path + "/")

        fun matches(session: Session, selected: Project?, knownPaths: List<String>): Boolean {
            if (selected == null) return true
            val cwd = session.cwd ?: return true
            val path = selected.path ?: run {
                // Home: sessions that fall under NO project path.
                return knownPaths.none { under(cwd, it) }
            }
            // Boundary-aware prefix: /home/x/abc must not match /home/x/a.
            return under(cwd, path)
        }
    }
}
