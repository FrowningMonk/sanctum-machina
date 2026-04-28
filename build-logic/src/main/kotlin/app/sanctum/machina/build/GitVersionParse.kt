package app.sanctum.machina.build

/**
 * Parses captured `git describe` output into a version string.
 *
 * Returns trimmed stdout when [exitCode] is zero and stdout is non-blank;
 * returns null otherwise so callers can fall back to a hardcoded version.
 */
fun gitVersionParse(stdout: String, exitCode: Int): String? {
    if (exitCode != 0) return null
    val trimmed = stdout.trim()
    return trimmed.ifEmpty { null }
}
