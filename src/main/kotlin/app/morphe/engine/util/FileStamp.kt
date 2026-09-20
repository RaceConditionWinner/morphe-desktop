/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.engine.util

import java.io.File

/**
 * A cheap identity for a file on disk: its size and last-modified time.
 *
 * Used wherever a component needs to notice "this file changed" without paying for a
 * content hash on every check — cache invalidation, "was this artifact replaced since I
 * last looked at it", crash-loop bookkeeping ([app.morphe.engine.patches.PatchBundleLoadGuard]).
 * Two files with the same size and mtime are treated as the same file; a rename alone
 * (same bytes, same mtime, different path) is deliberately NOT tracked here — pair this
 * with the path itself when path identity also matters.
 *
 * Not a substitute for a real hash where content equality actually needs proving (e.g.
 * verifying a downloaded artifact matches a published checksum) — use [FileChecksum] for
 * that. This is for "did this on-disk file plausibly change", not "prove these bytes match".
 */
data class FileStamp(val length: Long, val lastModified: Long) {
    override fun toString(): String = "$lastModified-$length"

    companion object {
        /** Returns null if [file] doesn't exist or can't be stat'd. */
        fun of(file: File): FileStamp? =
            runCatching {
                if (!file.isFile) return null
                FileStamp(file.length(), file.lastModified())
            }.getOrNull()

        /** Parses the [toString] format back into a [FileStamp], or null if malformed. */
        fun parse(text: String): FileStamp? {
            val idx = text.indexOf('-')
            if (idx <= 0 || idx == text.lastIndex) return null
            val lastModified = text.substring(0, idx).toLongOrNull() ?: return null
            val length = text.substring(idx + 1).toLongOrNull() ?: return null
            return FileStamp(length, lastModified)
        }
    }
}
