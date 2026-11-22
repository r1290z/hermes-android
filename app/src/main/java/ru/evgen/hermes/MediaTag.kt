package ru.evgen.hermes

/**
 * `MEDIA:<path>` tags in an assistant turn.
 *
 * The agent does not upload files to a store: it writes an absolute path into its
 * reply and the client fetches that file. The desktop app strips the tag and shows a
 * card; the phone showed the raw text — the complaint "файлы от тебя не приходят"
 * was literally a `MEDIA:/…apk` string sitting in the transcript.
 *
 * Paths contain spaces (`Копировать В единстве наша сила.pdf`), so a plain
 * "non-whitespace run" match truncates the name and the file can never be fetched.
 * The scanner therefore walks token boundaries from the start of the path and takes
 * the SHORTEST prefix that ends in a file extension — the first boundary that looks
 * like a real filename. That keeps spaces inside the name and still stops before the
 * prose that follows the tag.
 */
object MediaTag {

    /** One tag: the path as written plus the range it occupied in the text. */
    data class Found(val path: String, val start: Int, val end: Int)

    private val TAG = Regex("""[`"'*_]{0,3}MEDIA:[ \t]*""", RegexOption.IGNORE_CASE)

    /** A real extension: 2–5 alphanumerics (Cyrillic included), not "v1.2"'s ".2". */
    private val EXTENSION = Regex("""\.[^\s./\\]{2,5}$""")

    /** Sentence punctuation the lazy scan may have carried into the path. */
    private const val TRAILING = ".,;:)]}\"'`*_"

    fun findAll(text: String): List<Found> {
        val out = mutableListOf<Found>()
        var from = 0
        while (true) {
            val match = TAG.find(text, from) ?: break
            val start = match.range.first
            val pathStart = match.range.last + 1
            val lineEnd = text.indexOf('\n', pathStart).let { if (it < 0) text.length else it }
            val picked = pick(text.substring(pathStart, lineEnd))
            if (picked == null) {
                // Not a deliverable path (relative, or nothing after the tag):
                // leave it as prose rather than showing a card that always fails.
                from = pathStart
                continue
            }
            out += Found(picked.first, start, pathStart + picked.second)
            from = pathStart + picked.second
        }
        return out
    }

    /**
     * The path starting at index 0 of [rest] and how many characters it spans,
     * or null when this is not an absolute path.
     */
    private fun pick(rest: String): Pair<String, Int>? {
        if (rest.isEmpty()) return null
        // Quoted or backticked: the quotes are the delimiters, spaces are safe.
        val first = rest[0]
        if (first == '`' || first == '"' || first == '\'') {
            val close = rest.indexOf(first, 1)
            if (close > 1) return rest.substring(1, close) to (close + 1)
        }
        if (!rest.startsWith("/") && !rest.startsWith("~/")) return null

        // Candidate ends: every whitespace boundary, plus the end of the line.
        val boundaries = mutableListOf<Int>()
        for (i in rest.indices) {
            if (rest[i].isWhitespace()) boundaries += i
        }
        boundaries += rest.length
        for (end in boundaries) {
            val raw = rest.substring(0, end)
            val path = raw.trimEnd(*TRAILING.toCharArray())
            if (path.length > 1 && EXTENSION.containsMatchIn(path)) return path to end
        }
        // No extension anywhere (Makefile, Dockerfile, a log without one): the first
        // token is the best guess, and the gateway rejects it if it is not a file.
        val firstToken = rest.takeWhile { !it.isWhitespace() }
        return if (firstToken.length > 1) firstToken to firstToken.length else null
    }

    /** The text with every tag removed and the leftover blank lines squeezed. */
    fun strip(text: String): String {
        val found = findAll(text)
        if (found.isEmpty()) return text
        val out = StringBuilder(text)
        for (tag in found.asReversed()) out.delete(tag.start, tag.end)
        return out.toString()
            .replace(Regex("[ \t]+\n"), "\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    /** File name as a person reads it: the last path segment. */
    fun name(path: String): String = path.substringAfterLast('/').ifEmpty { path }
}
