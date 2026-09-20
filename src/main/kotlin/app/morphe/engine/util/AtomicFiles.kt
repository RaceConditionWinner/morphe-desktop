/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.engine.util

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Durable write + corrupt-file quarantine for the small JSON state files under
 * `morphe-data/` (`patched-apps.json`, `original-apks.json`). Shared so every
 * store persists with the same guarantees instead of carrying its own copy.
 */
object AtomicFiles {

    /**
     * Writes [content] to [target] so a crash never leaves a half-written file: the bytes are
     * flushed to a sibling `.tmp` file first, then moved over [target].
     *
     * Throws [IOException] on any failure, leaving [target] as it was. Callers must treat
     * a throw as "not persisted".
     */
    @Throws(IOException::class)
    fun write(target: File, content: String) {
        val dir = target.absoluteFile.parentFile
        Files.createDirectories(dir.toPath())
        val tmp = File(dir, "${target.name}.tmp")
        try {
            FileOutputStream(tmp).use { out ->
                out.write(content.toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            try {
                Files.move(
                    tmp.toPath(), target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: IOException) {
                // ATOMIC_MOVE isn't supported on every filesystem, so fall back.
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }

    /**
     * Moves an unreadable state file aside (`<name>.corrupt-<timestamp>`) instead of
     * letting the next save silently overwrite it, so whatever is still recoverable in
     * it survives. Returns the preserved file.
     *
     * Throws [IOException] if it can't be preserved. The caller must not proceed to
     * overwrite it in that case.
     */
    @Throws(IOException::class)
    fun quarantine(file: File): File {
        val dest = File(file.absoluteFile.parentFile, "${file.name}.corrupt-${System.currentTimeMillis()}")
        Files.move(file.toPath(), dest.toPath())
        return dest
    }
}
