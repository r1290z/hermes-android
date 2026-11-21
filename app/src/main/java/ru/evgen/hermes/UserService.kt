package ru.evgen.hermes

/**
 * The service Shizuku starts in a shell-owned process.
 *
 * Nothing here is called by our own app: Shizuku binds it as the shell user and
 * forwards [run]. That is the whole point — `pm install` executed here carries
 * the shell's privileges, so the update installs without the per-source sideload
 * permission and without the system installer screen.
 *
 * Keep it free of app state: it runs in a different process, possibly long after
 * the activity that started it is gone.
 */
class UserService : IUserService.Stub() {

    /**
     * Runs one shell command and returns `"<exit code>\n<output>"`.
     *
     * The exit code travels with the output on purpose: the caller has to tell
     * `pm install` reporting `Success` from it failing silently, and a bare
     * string would blur the two.
     */
    override fun run(command: String): String = try {
        val process = ProcessBuilder("sh", "-c", command)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val code = process.waitFor()
        "$code\n$output"
    } catch (e: Exception) {
        "1\n${e.javaClass.simpleName}: ${e.message}"
    }

    /** Shizuku's lifecycle hooks: stop the process, never linger. */
    override fun destroy() = System.exit(0)

    override fun exit() = System.exit(0)
}
