/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.engine

import app.morphe.engine.model.PatchedAppRecord
import app.morphe.engine.util.AtomicFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.util.logging.Logger

/**
 * Persistent store for [PatchedAppRecord]s — the patched-app history shared by
 * the CLI and GUI (see `patched-app-recall-plan.md`).
 *
 * Lives in the engine layer (not GUI) so **both** pipelines can record into one
 * file: `morphe-data/patched-apps.json`. Reads are cached in memory; writes go
 * through a [Mutex] (in-process safety) and are atomic (temp file + move) so a
 * crash mid-write can't corrupt the history.
 *
 * Writes have a hard success/failure contract: [upsert] and [delete] throw
 * [IOException] when the change could not be persisted, leaving the in-memory
 * state and [changes] untouched. A return therefore always means "on disk".
 *
 * The [file] is injectable for testing; production code uses the default under
 * [MorpheData.root].
 */
class PatchedAppStore(
    private val file: File = File(MorpheData.root, FILE_NAME),
) {
    private val logger = Logger.getLogger(PatchedAppStore::class.java.name)

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val mutex = Mutex()
    private var cache: List<PatchedAppRecord>? = null

    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /**
     * Emits once whenever the history changes (upsert/delete). Observe this to
     * refresh UI the moment a patch completes or a record is forgotten, instead
     * of waiting for a screen to be re-entered.
     */
    val changes: SharedFlow<Unit> = _changes.asSharedFlow()

    /** All records, most-recently-patched first. */
    suspend fun getAll(): List<PatchedAppRecord> = withContext(Dispatchers.IO) {
        mutex.withLock { load() }
    }

    /** The record for [packageName], or null if the app was never patched. */
    suspend fun get(packageName: String): PatchedAppRecord? = withContext(Dispatchers.IO) {
        mutex.withLock { load().firstOrNull { it.packageName == packageName } }
    }

    /**
     * Insert [record], replacing any existing record for the same package.
     * @throws IOException if the history could not be persisted.
     */
    @Throws(IOException::class)
    suspend fun upsert(record: PatchedAppRecord): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            val others = load().filterNot { it.packageName == record.packageName }
            persist(listOf(record) + others)
        }
        _changes.tryEmit(Unit)
    }

    /**
     * Remove the record for [packageName] if present.
     * @throws IOException if the history could not be persisted.
     */
    @Throws(IOException::class)
    suspend fun delete(packageName: String): Unit = withContext(Dispatchers.IO) {
        val changed = mutex.withLock {
            val current = load()
            val remaining = current.filterNot { it.packageName == packageName }
            if (remaining.size != current.size) {
                persist(remaining)
                true
            } else {
                false
            }
        }
        if (changed) _changes.tryEmit(Unit)
    }

    // --- internals (call only while holding [mutex]) ---

    private fun load(): List<PatchedAppRecord> {
        cache?.let { return it }
        val records = if (file.exists()) {
            try {
                json.decodeFromString<StoreFile>(file.readText()).records
            } catch (e: Exception) {
                // Corrupt/incompatible file: keep the user able to patch by starting
                // empty, but move the bad file aside first so the next write can't
                // destroy whatever is still recoverable in it.
                val preserved = AtomicFiles.quarantine(file)
                logger.warning(
                    "Could not read patched-app history (${e.message}); " +
                        "preserved it as ${preserved.name} and starting empty"
                )
                emptyList()
            }
        } else {
            emptyList()
        }
        cache = records
        return records
    }

    /** Writes [records] durably; the cache only advances once they are on disk. */
    private fun persist(records: List<PatchedAppRecord>) {
        AtomicFiles.write(file, json.encodeToString(StoreFile.serializer(), StoreFile(SCHEMA_VERSION, records)))
        cache = records
    }

    @Serializable
    private data class StoreFile(
        val version: Int = SCHEMA_VERSION,
        val records: List<PatchedAppRecord> = emptyList(),
    )

    companion object {
        const val FILE_NAME = "patched-apps.json"

        /** Bump when the on-disk shape changes incompatibly; add a migration in [load]. */
        const val SCHEMA_VERSION = 1

        /**
         * Process-wide shared instance — use this everywhere in production so the
         * in-memory cache is coherent (two instances in one process could race
         * and drop each other's records). Tests construct their own with a
         * custom [file].
         */
        val shared: PatchedAppStore by lazy { PatchedAppStore() }
    }
}
