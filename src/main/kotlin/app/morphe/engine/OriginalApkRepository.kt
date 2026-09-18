/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.engine

import app.morphe.engine.util.FileChecksum
import app.morphe.engine.util.FilenameUtils
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One retained original APK record. */
@Serializable
data class OriginalApk(
    val packageName: String,
    val version: String,
    val filePath: String,
    val fileSize: Long,
    val sha256: String? = null,
    val lastUsed: Long,
)

/**
 * Retains a copy of each app's pre-patch (original) APK, so a later repatch
 * can reuse it instead of requiring the user to re-supply the file — the
 * file the user originally picked may since have been moved, renamed, or
 * deleted (browser download folders get cleaned, temp dirs get wiped).
 *
 * Ported concept from morphe-manager's Room-backed `OriginalApkRepository` —
 * same fields, same "one row per package, replaced on a newer version"
 * semantics, same atomic-staging write pattern for the archived file itself
 * ([copyThroughStaging] is Manager's own approach, ported near-verbatim: it
 * already didn't depend on anything Android-specific). Persisted here as a
 * single JSON file rather than a database row, matching this project's
 * `app.morphe.engine.PatchedAppStore` pattern exactly: one in-memory cache,
 * one atomic staging-file write for the *metadata* file (a separate,
 * smaller write from the APK archival copy above — a crash between the two
 * leaves an archived file with no record of it, recovered by [pruneMissingApks]
 * simply not knowing about it yet rather than by any special-cased recovery
 * logic), a schema version field, and a `changes: SharedFlow<Unit>` with
 * `replay = 1` (see [PatchedAppStore]'s class doc for why replay = 1, not the
 * `replay = 0` this project used before that fix).
 */
class OriginalApkRepository(
    private val file: File = File(MorpheData.root, "original-apks.json"),
    private val archiveDir: File = MorpheData.originalApksDir,
    private val isRetentionEnabled: suspend () -> Boolean = { true },
) {
    private val logger = Logger.getLogger(OriginalApkRepository::class.java.name)
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    private val mutex = Mutex()
    private var cache: Map<String, OriginalApk>? = null

    private val _changes = MutableSharedFlow<Unit>(replay = 1, extraBufferCapacity = 1)

    /** Emits whenever a record is saved, its use is marked, pruned, or deleted —
     *  and to a new collector immediately if that happened before it subscribed. */
    val changes: SharedFlow<Unit> = _changes.asSharedFlow()

    suspend fun getAll(): List<OriginalApk> = withContext(Dispatchers.IO) {
        mutex.withLock { load().values.toList() }
    }

    suspend fun get(packageName: String): OriginalApk? = withContext(Dispatchers.IO) {
        mutex.withLock { load()[packageName] }
    }

    /**
     * Archives [sourceFile] as the original APK for (packageName, version),
     * replacing any previously-archived version for this package. Returns the
     * archived file, or null if retention is disabled (see
     * [isRetentionEnabled]), [sourceFile] doesn't exist, or the save failed —
     * callers should treat null as "couldn't retain a copy," never as an
     * error worth failing the patch that's already succeeded by the time
     * this is called.
     *
     * A no-op beyond refreshing [OriginalApk.lastUsed] when the exact same
     * package+version is already archived with its file still present —
     * repatching the same version repeatedly shouldn't re-copy the same
     * bytes every time.
     */
    suspend fun saveOriginalApk(
        packageName: String,
        version: String,
        sourceFile: File,
    ): File? = withContext(Dispatchers.IO) {
        if (!isRetentionEnabled()) {
            logger.fine("Original APK retention disabled, skipping save for $packageName")
            return@withContext null
        }
        if (!sourceFile.exists()) {
            logger.warning("Source file for $packageName does not exist, skipping original APK save")
            return@withContext null
        }

        mutex.withLock {
            try {
                val safePackage = FilenameUtils.sanitize(packageName)
                val safeVersion = FilenameUtils.sanitize(version.ifBlank { "unspecified" })
                val targetFile = File(archiveDir, "${safePackage}_${safeVersion}_original.apk")

                val all = load()
                val existing = all[packageName]
                if (existing != null && existing.version == version && File(existing.filePath).exists()) {
                    logger.fine("Original APK already archived for $packageName v$version, skipping duplicate save")
                    persist(all + (packageName to existing.copy(lastUsed = System.currentTimeMillis())))
                    _changes.tryEmit(Unit)
                    return@withContext File(existing.filePath)
                }

                // canonicalFile resolves symlinks/relative-vs-absolute spelling, so
                // "same file via a different path string" is correctly recognized as
                // the same file rather than triggering a needless self-copy or, worse,
                // a self-delete below.
                if (sourceFile.canonicalFile != targetFile.canonicalFile) {
                    copyThroughStaging(sourceFile, targetFile)
                }

                val (sha256, fileSize) = FileChecksum.fingerprintOrNull(targetFile.absolutePath)
                val record = OriginalApk(
                    packageName = packageName,
                    version = version,
                    filePath = targetFile.absolutePath,
                    fileSize = fileSize,
                    sha256 = sha256,
                    lastUsed = System.currentTimeMillis(),
                )
                persist(all + (packageName to record))

                // The archive this replaces goes last, so the package is never left
                // without one if the delete somehow fails. The file just written and
                // the caller's own source file are excluded (by canonical path) so a
                // same-file case never deletes either.
                existing?.let {
                    val oldFile = File(it.filePath)
                    if (oldFile.exists() &&
                        oldFile.canonicalFile != sourceFile.canonicalFile &&
                        oldFile.canonicalFile != targetFile.canonicalFile
                    ) {
                        oldFile.delete()
                        logger.fine("Deleted superseded original APK for $packageName")
                    }
                }

                _changes.tryEmit(Unit)
                logger.info("Saved original APK for $packageName v$version")
                targetFile
            } catch (e: Exception) {
                logger.warning("Failed to save original APK for $packageName: ${e.message}")
                null
            }
        }
    }

    /** Refresh [OriginalApk.lastUsed] to now — call when a repatch reuses an archived original. */
    suspend fun markUsed(packageName: String): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            val all = load()
            val existing = all[packageName] ?: return@withLock
            persist(all + (packageName to existing.copy(lastUsed = System.currentTimeMillis())))
            _changes.tryEmit(Unit)
        }
    }

    /**
     * Drops records whose archived file is gone (nothing left to describe,
     * and reporting a stale size or offering a no-op action would be worse
     * than removing the record), and deletes any `.part` staging file left
     * behind by a save that never finished — see [copyThroughStaging].
     */
    suspend fun pruneMissingApks(): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            val all = load()
            val remaining = all.filterValues { File(it.filePath).exists() }
            if (remaining.size != all.size) {
                persist(remaining)
                logger.info("Pruned ${all.size - remaining.size} missing original-APK record(s)")
                _changes.tryEmit(Unit)
            }

            val stagedFiles = archiveDir.listFiles { f -> f.name.endsWith(STAGING_SUFFIX) }.orEmpty()
            for (staged in stagedFiles) {
                staged.delete()
                logger.fine("Dropped staged original APK left by an interrupted save: ${staged.name}")
            }
        }
    }

    /** Remove the archived original for [packageName]: both the file and its record. */
    suspend fun delete(packageName: String): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            val all = load()
            val existing = all[packageName] ?: return@withLock
            try {
                File(existing.filePath).delete()
                persist(all - packageName)
                _changes.tryEmit(Unit)
            } catch (e: Exception) {
                logger.warning("Failed to delete original APK for $packageName: ${e.message}")
            }
        }
    }

    private fun load(): Map<String, OriginalApk> {
        cache?.let { return it }
        val loaded = if (!file.exists()) {
            emptyMap()
        } else {
            try {
                json.decodeFromString(StoreFile.serializer(), file.readText())
                    .apks.associateBy { it.packageName }
            } catch (e: Exception) {
                logger.warning("Could not read original-apks.json, starting empty: ${e.message}")
                emptyMap()
            }
        }
        cache = loaded
        return loaded
    }

    private fun persist(all: Map<String, OriginalApk>) {
        try {
            file.parentFile?.mkdirs()
            val content = json.encodeToString(StoreFile.serializer(), StoreFile(SCHEMA_VERSION, all.values.toList()))
            // Atomic write: stage, then move over the target — same pattern as
            // PatchedAppStore, so a crash never leaves a half-written file behind.
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(content)
            try {
                Files.move(
                    tmp.toPath(), file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: Exception) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            cache = all
        } catch (e: Exception) {
            logger.warning("Failed to write original-apks.json: ${e.message}")
        }
    }

    @Serializable
    private data class StoreFile(
        val version: Int = SCHEMA_VERSION,
        val apks: List<OriginalApk> = emptyList(),
    )

    companion object {
        private const val SCHEMA_VERSION = 1

        /** Marks a copy still being written, so an interrupted save is never mistaken for an archive. */
        internal const val STAGING_SUFFIX = ".part"

        /**
         * Copies [source] over [target] through a staging file, so an archive
         * is only ever replaced by a copy that's already written in full. A
         * failure drops the staged copy and leaves whatever sits at [target]
         * as it was. Ported verbatim from morphe-manager's
         * `OriginalApkRepository` — plain `java.io`/`java.nio.file`, nothing
         * Android-specific to adapt.
         */
        internal fun copyThroughStaging(source: File, target: File) {
            val staging = File(target.path + STAGING_SUFFIX)
            try {
                source.copyTo(staging, overwrite = true)
                Files.move(staging.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } catch (e: Exception) {
                staging.delete()
                throw e
            }
        }
    }
}
