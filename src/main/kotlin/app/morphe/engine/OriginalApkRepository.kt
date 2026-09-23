/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.engine

import app.morphe.engine.model.PatchedAppRecord
import app.morphe.engine.util.AtomicFiles
import app.morphe.engine.util.BundleFormats
import app.morphe.engine.util.FileChecksum
import app.morphe.engine.util.FilenameUtils
import java.io.File
import java.io.IOException
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
 * Owns the pre-patch (original) APK of each app: after a successful patch the
 * file the user picked is archived under [archiveDir], and that managed copy
 * becomes the canonical input for every later repatch/update, so the user's own
 * copy (a Downloads folder that gets cleaned, a temp dir) is no longer needed.
 *
 * Lifecycle, in the order callers must follow:
 * 1. [saveOriginalApk] copies the source through a staging file, verifies it,
 *    and durably registers it. The source is left untouched.
 * 2. The caller persists whatever references the archive (the patched-app record).
 * 3. [discardSource] removes the user's copy. Only now is it redundant.
 *
 * If anything before step 3 fails, the user's file is still where it was.
 * [resolveInputApk] is the single place that decides which original a repatch reads.
 *
 * One row per package, replaced when a different version (or different content
 * for the same version, by SHA-256) is archived. Persisted as one JSON file with
 * the same atomic-write, in-memory cache, mutex and `changes` flow pattern as
 * [PatchedAppStore]. Persistence failures throw [IOException]; nothing here
 * reports success for something that isn't on disk.
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

    // replay = 1 so a collector that subscribes after a save still sees it.
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
     * Archives [sourceFile] as the original APK for ([packageName], [version]) and
     * returns the managed file, replacing any archive for this package that it
     * supersedes. Returns null — "nothing was archived, and that is fine" — when
     * retention is disabled (see [isRetentionEnabled]) or [sourceFile] is not a file.
     * A non-null result guarantees the file at that path holds exactly [sourceFile]'s
     * bytes (verified, flushed to disk) and is registered durably.
     *
     * [sourceFile] is never modified or deleted here; see [discardSource].
     *
     * Same package + version only counts as "already archived" when the content
     * matches too (size, then SHA-256): a different APK carrying the same versionName
     * replaces the managed copy. Re-saving the managed copy itself, which is what
     * repatching from it does, just refreshes [OriginalApk.lastUsed].
     *
     * @throws IOException (or another exception) if archiving or persisting failed.
     *   The source is untouched, and a fresh archive that could not be registered
     *   is removed again.
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
        if (!sourceFile.isFile) {
            logger.warning("Source for $packageName is not a file, skipping original APK save")
            return@withContext null
        }
        mutex.withLock { saveLocked(packageName, version, sourceFile) }
    }

    private fun saveLocked(packageName: String, version: String, source: File): File {
        val all = load()
        // The archived record, only when it is for this exact version.
        val current = all[packageName]?.takeIf { it.version == version }
        val currentFile = current?.let { File(it.filePath) }
        // canonicalFile resolves symlinks / relative-vs-absolute spelling, so "same file via
        // a different path string" is recognized instead of causing a self-copy.
        val sourceCanonical = source.canonicalFile
        val target = File(archiveDir, archiveFileName(packageName, version, source))
        val inPlace = sourceCanonical == target.canonicalFile

        if (current != null && currentFile != null) {
            // Repatching from the managed copy itself: nothing to copy or re-hash.
            if (currentFile.canonicalFile == sourceCanonical && current.fileSize == source.length()) {
                return touch(all, current)
            }
            // The exact same APK (same version, same bytes) is already archived.
            if (!inPlace && currentFile.isFile && hasSameContent(current, currentFile, source)) {
                return touch(all, current)
            }
        }

        archiveDir.mkdirs()
        val targetExisted = target.exists()
        val (sha256, size) = if (inPlace) {
            FileChecksum.sha256(target) to target.length()
        } else {
            copyThroughStaging(source, target)
        }
        val record = OriginalApk(
            packageName = packageName,
            version = version,
            filePath = target.absolutePath,
            fileSize = size,
            sha256 = sha256,
            lastUsed = System.currentTimeMillis(),
        )
        try {
            persist(all + (packageName to record))
        } catch (e: Exception) {
            // Not registered, so don't leave an orphan behind. (A copy that replaced an
            // archive of the same name can't be rolled back, but it is still a complete,
            // valid original.)
            if (!inPlace && !targetExisted) target.delete()
            throw e
        }

        // The archive this replaces goes last, so the package is never left without one
        // if the delete somehow fails. The file just written and the caller's own source
        // are excluded (by canonical path) so a same-file case never deletes either.
        // Looked up by package, not by version: this is the previous *version's* archive.
        all[packageName]?.let { File(it.filePath) }?.let { old ->
            val oldCanonical = old.canonicalFile
            if (old.exists() && oldCanonical != target.canonicalFile && oldCanonical != sourceCanonical) {
                if (old.delete()) logger.fine("Deleted superseded original APK for $packageName")
            }
        }

        _changes.tryEmit(Unit)
        logger.info("Saved original APK for $packageName v$version")
        return target
    }

    /** Refreshes [OriginalApk.lastUsed] on an unchanged archive and returns its file. */
    private fun touch(all: Map<String, OriginalApk>, record: OriginalApk): File {
        persist(all + (record.packageName to record.copy(lastUsed = System.currentTimeMillis())))
        _changes.tryEmit(Unit)
        return File(record.filePath)
    }

    private fun hasSameContent(record: OriginalApk, archived: File, source: File): Boolean =
        archived.length() == source.length() &&
            FileChecksum.sha256(source) == (record.sha256 ?: FileChecksum.sha256(archived))

    /**
     * Removes the user's own [source] file, after [saveOriginalApk] returned [archived] for it
     * AND everything that references [archived] has been persisted. This is the "move" half of
     * archive-and-move; until it runs, the source is untouched.
     *
     * Best-effort and deliberately non-throwing: a source that can't be removed (locked,
     * read-only, already gone) is merely left where it is. Refuses to touch the archive itself,
     * any registered archive, or a source whose size no longer matches [archived].
     */
    suspend fun discardSource(source: File, archived: File): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                if (!source.exists()) return@withLock
                val sourceCanonical = source.canonicalFile
                val isManaged = sourceCanonical == archived.canonicalFile ||
                    load().values.any { File(it.filePath).canonicalFile == sourceCanonical }
                if (isManaged) return@withLock
                if (!archived.isFile || archived.length() != source.length()) {
                    logger.warning("Keeping ${source.name}: the archived copy could not be confirmed")
                    return@withLock
                }
                Files.delete(source.toPath())
                logger.info("Moved original APK ${source.name} into Morphe's managed storage")
            } catch (e: Exception) {
                logger.warning("Could not remove ${source.name} after archiving it: ${e.message}")
            }
        }
    }

    /**
     * The original APK a repatch of [record] should read, or null when none is usable and the
     * user has to pick one. In priority order:
     * 1. The Morphe-managed archive for the package, when it belongs to [record]'s version. An
     *    archive of a different version is skipped (retention was switched off, or archiving
     *    that patch failed), never used silently.
     * 2. [PatchedAppRecord.inputApkPath], which points into the managed archive for every
     *    record written since archives became canonical, even if this repository's own index
     *    was lost or quarantined.
     * 3. [PatchedAppRecord.inputApkPath] as a user-owned file: records written before that, or
     *    patched with retention disabled, or whose archiving failed.
     * (2 and 3 are the same lookup: the record's path, if it still exists.)
     *
     * This is also what keeps a clone safe: the archive is keyed by package, one per version,
     * so a clone patched at a newer app version supersedes the app's own archived original —
     * but the version check above means the app's own record, still tracking the older version,
     * never silently resolves to the clone's (wrong-version) replacement. It falls through to
     * its own recorded path instead, exactly as if no archive had ever existed for it.
     */
    suspend fun resolveInputApk(record: PatchedAppRecord): File? = withContext(Dispatchers.IO) {
        val archived = mutex.withLock {
            load()[record.packageName]
                ?.takeIf { it.version == record.apkVersion }
                ?.let { File(it.filePath) }
                ?.takeIf { it.isFile }
        }
        archived ?: File(record.inputApkPath).takeIf { it.isFile }
    }

    /**
     * Drops records whose archived file is gone (nothing left to describe,
     * and reporting a stale size or offering a no-op action would be worse
     * than removing the record), and deletes any `.part` staging file left
     * behind by a save that never finished — see [copyThroughStaging].
     *
     * @throws IOException if the pruned index could not be persisted.
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

    /**
     * Remove the archived original for [packageName]: both the file and its record.
     * The record goes first, so a failure to persist leaves file and record intact.
     *
     * @throws IOException if the index could not be persisted.
     */
    suspend fun delete(packageName: String): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            val all = load()
            val existing = all[packageName] ?: return@withLock
            persist(all - packageName)
            if (!File(existing.filePath).delete()) {
                logger.warning("Removed the record but could not delete ${existing.filePath}")
            }
            _changes.tryEmit(Unit)
        }
    }

    /** `<package>_<version>_original.<ext>`. Bundles keep their extension: the engine tells a
     *  split-APK bundle from a plain APK by it, so an `.apkm` saved as `.apk` couldn't be patched. */
    private fun archiveFileName(packageName: String, version: String, source: File): String {
        val safePackage = FilenameUtils.sanitize(packageName)
        val safeVersion = FilenameUtils.sanitize(version.ifBlank { "unspecified" })
        val extension = if (BundleFormats.isBundle(source)) source.extension.lowercase() else "apk"
        return "${safePackage}_${safeVersion}_original.$extension"
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
                // Unreadable: start empty, but set the bad file aside first so the next save
                // can't destroy whatever is still recoverable in it. If it can't be preserved
                // this throws rather than let a save overwrite it.
                val preserved = AtomicFiles.quarantine(file)
                logger.warning(
                    "Could not read original-apks.json (${e.message}); " +
                        "preserved it as ${preserved.name} and starting empty"
                )
                emptyMap()
            }
        }
        cache = loaded
        return loaded
    }

    /** Writes [all] durably; the cache only advances once it is on disk. */
    private fun persist(all: Map<String, OriginalApk>) {
        val content = json.encodeToString(StoreFile.serializer(), StoreFile(SCHEMA_VERSION, all.values.toList()))
        AtomicFiles.write(file, content)
        cache = all
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
         * Copies [source] over [target] through a staging file, so an archive is only ever
         * replaced by a copy that's already written in full and flushed. Returns the copy's
         * `(sha256, size)`. A failure drops the staged copy and leaves whatever sits at
         * [target] as it was. The staging file sits next to [target], so the final step is a
         * same-directory rename; the source is only ever read, so nothing here depends on
         * source and archive sharing a filesystem.
         */
        internal fun copyThroughStaging(source: File, target: File): Pair<String, Long> {
            val staging = File(target.path + STAGING_SUFFIX)
            try {
                val fingerprint = FileChecksum.copyWithSha256(source, staging)
                Files.move(staging.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                return fingerprint
            } catch (e: Exception) {
                staging.delete()
                throw e
            }
        }
    }
}
