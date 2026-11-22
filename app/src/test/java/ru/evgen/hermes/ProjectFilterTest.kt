package ru.evgen.hermes

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ProjectFilterModel.matches: path prefixes, Home bucket, missing cwd. */
class ProjectFilterTest {

    private fun project(path: String?): Project = Project.from(
        JSONObject(
            if (path == null) """{"id":"home","label":"Home","path":null,"isNoProject":true}"""
            else """{"id":"p1","label":"P1","path":"$path"}"""
        )
    )!!

    private fun session(cwd: String?): Session {
        val json = JSONObject("""{"id":"s1","title":"t","preview":"","message_count":1}""")
        if (cwd == null) json.put("cwd", JSONObject.NULL) else json.put("cwd", cwd)
        return Session.from(json)!!
    }

    private val knownPaths = listOf("/home/x/a", "/home/x/b")

    @Test
    fun `null filter shows everything`() {
        val s = session("/elsewhere")
        assertTrue(ProjectFilterModel.matches(s, null, knownPaths))
    }

    @Test
    fun `session belongs by cwd prefix`() {
        val s = session("/home/x/a/sub/dir")
        assertTrue(ProjectFilterModel.matches(s, project("/home/x/a"), knownPaths))
        assertFalse(ProjectFilterModel.matches(s, project("/home/x/b"), knownPaths))
    }

    @Test
    fun `prefix does not cross path boundaries`() {
        // /home/x/abc must NOT match /home/x/a — otherwise sibling projects leak.
        val s = session("/home/x/abc")
        assertFalse(ProjectFilterModel.matches(s, project("/home/x/a"), knownPaths))
    }

    @Test
    fun `home bucket is sessions outside every path`() {
        val home = session("/tmp")
        val inProject = session("/home/x/a")
        val homeProject = Project.from(
            JSONObject("""{"id":"home","label":"Home","path":null,"isNoProject":true}""")
        )!!
        assertTrue(ProjectFilterModel.matches(home, homeProject, knownPaths))
        assertFalse(ProjectFilterModel.matches(inProject, homeProject, knownPaths))
    }

    @Test
    fun `missing cwd means no filtering`() {
        val s = session(null)
        assertTrue(ProjectFilterModel.matches(s, project("/home/x/a"), knownPaths))
        // Home with no cwd on the wire would swallow everything — do not filter
        // in that case either; the card says: no field, no filter.
        val homeProject = Project.from(
            JSONObject("""{"id":"home","label":"Home","path":null,"isNoProject":true}""")
        )!!
        assertTrue(ProjectFilterModel.matches(s, homeProject, knownPaths))
    }

    @Test
    fun `empty list sorts home first`() {
        val tree = JSONObject(
            """{"projects":[
                {"id":"a","label":"A","path":"/a","lastActive":5},
                {"id":"home","label":"Home","path":null,"isNoProject":true,"lastActive":1},
                {"id":"b","label":"B","path":"/b","lastActive":9}
            ]}"""
        )
        val list = Project.listFrom(tree)
        assertEquals(listOf("home", "b", "a"), list.map { it.id })
    }
}
