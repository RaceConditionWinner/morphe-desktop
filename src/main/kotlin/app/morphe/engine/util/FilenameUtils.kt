/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.engine.util

/**
 * Utility helpers for working with filenames.
 *
 * Ported verbatim from morphe-manager's `util/FilenameUtils.kt` — pure
 * string manipulation with no Android dependency, so nothing to adapt.
 * Currently used by [app.morphe.engine.OriginalApkRepository] to build a
 * safe archive filename from a package name and version, neither of which
 * is guaranteed free of characters unsafe for a filename (a version string
 * can contain slashes, for instance).
 */
object FilenameUtils {

    /**
     * Sanitize a string so it can safely be used as part of a filename:
     * keeps alphanumerics and `-_.`, collapses whitespace and anything else
     * to `_`, drops quote characters entirely (rather than replacing them,
     * so `"1.0"` doesn't become a stray `_1.0_`), then collapses repeated
     * `_`/`-` runs and trims them from the ends.
     */
    fun sanitize(segment: String): String {
        if (segment.isEmpty()) return ""
        val raw = buildString(segment.length) {
            segment.forEach { char ->
                val sanitized = when {
                    char in '0'..'9' || char in 'a'..'z' || char in 'A'..'Z' -> char
                    char == '-' || char == '_' || char == '.' -> char
                    char.isWhitespace() -> '_'
                    char == '\'' || char == '"' || char == '`' -> null
                    else -> '_'
                }
                sanitized?.let { append(it) }
            }
        }

        return raw
            .replace(Regex("_{2,}"), "_")
            .replace(Regex("-{2,}"), "-")
            .trim('_', '-')
    }
}
