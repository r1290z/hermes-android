package ru.evgen.hermes

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parse of `projects.tree` — pure JVM, same class the screen uses.
 *
 * The fixture mirrors the live answer: `path: null` for Home, real paths for
 * projects. org.json's optString turns a JSON null into the literal "null", so
 * the null handling is exactly what these tests pin down.
 */
class ProjectTest {

    private val tree = JSONObject(
        """
        {"projects":[
          {"id":"p_9806f8d7","label":"family-kpi","path":"/home/hermes/projects/family-kpi",
           "isAuto":false,"isNoProject":false,"sessionCount":3,"lastActive":1795300000.0,
           "totalTokens":120000,"repos":[],"previewSessions":[]},
          {"id":"__no_project__","label":"Home","path":null,
           "isAuto":false,"isNoProject":true,"sessionCount":51,"lastActive":1795354130.6,
           "totalTokens":56544042,"repos":[{"id":"/mnt/ai/projects/hermes-app","label":"hermes-app",
           "path":"/mnt/ai/projects/hermes-app","sessionCount":0}],
           "previewSessions":[{"id":"s1"},{"id":"s2"},{"id":"s3"}]},
          {"id":"p_d5f17052","label":"hermes-kanban-webui","path":"/home/hermes/projects/hermes-kanban-webui",
           "isAuto":false,"isNoProject":false,"sessionCount":2,"lastActive":1795340000.0,
           "totalTokens":80000,"repos":[],"previewSessions":[]}
        ],
        "active_id":null,"scoped_session_ids":[1,2,3]}
        """.trimIndent()
    )

    @Test
    fun `home is parsed and flagged isNoProject`() {
        val projects = Project.listFrom(tree)
        val home = projects.first { it.id == "__no_project__" }
        assertTrue(home.isNoProject)
        assertEquals("Home", home.label)
    }

    @Test
    fun `path null stays null, never the string null`() {
        val home = Project.listFrom(tree).first { it.isNoProject }
        assertNull(home.path)
        // And a real project keeps its real path.
        assertEquals(
            "/home/hermes/projects/family-kpi",
            Project.listFrom(tree).first { it.label == "family-kpi" }.path
        )
    }

    @Test
    fun `home sorts first even when it is not the newest`() {
        // fixture: family-kpi 1795300000 < kanban 1795340000 < home 1795354130 —
        // home is ALSO newest here, so re-check ordering against a tree where it is not.
        val shuffled = JSONObject(
            """
            {"projects":[
              {"id":"__no_project__","label":"Home","path":null,"isAuto":false,"isNoProject":true,
               "sessionCount":1,"lastActive":100.0,"totalTokens":0,"previewSessions":[]},
              {"id":"p_a","label":"a","path":"/a","isAuto":false,"isNoProject":false,
               "sessionCount":1,"lastActive":300.0,"totalTokens":0,"previewSessions":[]},
              {"id":"p_b","label":"b","path":"/b","isAuto":false,"isNoProject":false,
               "sessionCount":1,"lastActive":200.0,"totalTokens":0,"previewSessions":[]}
            ]}
            """.trimIndent()
        )
        val ordered = Project.listFrom(shuffled).map { it.id }
        assertEquals(listOf("__no_project__", "p_a", "p_b"), ordered)
    }

    @Test
    fun `rest sorted by lastActive descending`() {
        val ids = Project.listFrom(tree)
            .filterNot { it.isNoProject }
            .map { it.id }
        assertEquals(listOf("p_d5f17052", "p_9806f8d7"), ids)
    }

    @Test
    fun `empty response yields an empty list`() {
        assertTrue(Project.listFrom(JSONObject("""{"projects":[]}""")).isEmpty())
        assertTrue(Project.listFrom(null).isEmpty())
    }

    @Test
    fun `previewCount counts the preview sessions`() {
        val home = Project.listFrom(tree).first { it.isNoProject }
        assertEquals(3, home.previewCount)
        assertEquals(51, home.sessionCount)
        assertEquals(56544042L, home.totalTokens)
    }
}
