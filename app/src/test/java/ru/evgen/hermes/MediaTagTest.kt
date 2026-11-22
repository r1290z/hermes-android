package ru.evgen.hermes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `MEDIA:<path>` parsing decides whether the user ever sees a file the agent sent.
 *
 * Two failures matter and both are silent: a tag left in the text (the user reads
 * `MEDIA:/…apk` instead of getting the APK — this is the bug that was reported), and
 * a tag that swallows the rest of the sentence into a path that does not exist.
 */
class MediaTagTest {

    @Test
    fun `plain tag is found and removed`() {
        val text = "Готово, файл внутри.\n\nMEDIA:/home/hermes/cache/report.pdf"
        val found = MediaTag.findAll(text)
        assertEquals(1, found.size)
        assertEquals("/home/hermes/cache/report.pdf", found[0].path)
        assertEquals("Готово, файл внутри.", MediaTag.strip(text))
    }

    @Test
    fun `tag inside backticks and bold is still a tag`() {
        // Models wrap the tag in Markdown emphasis; the desktop tolerates it, so
        // the phone must too, or the file silently never appears.
        for (text in listOf(
            "Файл: `MEDIA:/tmp/a.txt`",
            "Файл: **MEDIA:/tmp/a.txt**",
            "Файл: *MEDIA:/tmp/a.txt*"
        )) {
            val found = MediaTag.findAll(text)
            assertEquals("не найден в: $text", 1, found.size)
            assertEquals("/tmp/a.txt", found[0].path)
        }
    }

    @Test
    fun `two tags in one message both survive`() {
        val text = "Первое MEDIA:/tmp/a.txt и второе MEDIA:/tmp/b.csv"
        val paths = MediaTag.findAll(text).map { it.path }
        assertEquals(listOf("/tmp/a.txt", "/tmp/b.csv"), paths)
    }

    @Test
    fun `home relative path is accepted`() {
        val found = MediaTag.findAll("MEDIA:~/projects/report.xlsx")
        assertEquals(1, found.size)
        assertEquals("~/projects/report.xlsx", found[0].path)
    }

    @Test
    fun `trailing sentence dot is not part of the path`() {
        val found = MediaTag.findAll("Смотри MEDIA:/tmp/report.pdf.")
        assertEquals("/tmp/report.pdf", found[0].path)
    }

    @Test
    fun `cyrillic name and spaces survive`() {
        val path = "/home/hermes/.hermes/attachments/Копировать В единстве наша сила.pdf"
        val found = MediaTag.findAll("Держи MEDIA:$path")
        assertEquals(1, found.size)
        assertEquals(path, found[0].path)
    }

    @Test
    fun `relative path is not a tag`() {
        // A bare `MEDIA:report.pdf` is prose, not a deliverable: the app cannot
        // resolve it, and pretending otherwise would show a card that always fails.
        assertTrue(MediaTag.findAll("MEDIA:report.pdf").isEmpty())
    }

    @Test
    fun `text without tags is returned untouched`() {
        val text = "Обычный ответ без вложений."
        assertTrue(MediaTag.findAll(text).isEmpty())
        assertEquals(text, MediaTag.strip(text))
    }

    @Test
    fun `name is the last path segment`() {
        assertEquals("report.pdf", MediaTag.name("/home/hermes/x/report.pdf"))
        assertEquals("В единстве.pdf", MediaTag.name("/a/В единстве.pdf"))
    }
}
