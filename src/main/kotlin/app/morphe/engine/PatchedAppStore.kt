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
 * Records are keyed by [PatchedAppRecord.trackingKey], not by package name, so
 * several builds of one app — the app's own install and any copies of it — can
 * be tracked side by side instead of overwriting each other.
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

    /** The record filed under [trackingKey], or null when there is none. */
    suspend fun get(trackingKey: String): PatchedAppRecord? = withContext(Dispatchers.IO) {
        mutex.withLock { load().firstOrNull { it.trackingKey == trackingKey } }
    }

    /**
     * Insert [record], replacing any existing record with the same tracking key.
     * @throws IOException if the history could not be persisted.
     */
    @Throws(IOException::class)
    suspend fun upsert(record: PatchedAppRecord): Unit = withContext(Dispatchers.IO) {
        val stored = record.withResolvedId()
        mutex.withLock {
            val others = load().filterNot { it.trackingKey == stored.trackingKey }
            persist(listOf(stored) + others)
        }
        _changes.tryEmit(Unit)
    }

    /**
     * Remove the record filed under [trackingKey] if present.
     * @throws IOException if the history could not be persisted.
     */
    @Throws(IOException::class)
    suspend fun delete(trackingKey: String): Unit = withContext(Dispatchers.IO) {
        val changed = mutex.withLock {
            val current = load()
            val remaining = current.filterNot { it.trackingKey == trackingKey }
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
                val stored = json.decodeFromString<StoreFile>(file.readText())
                migrate(stored)
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

    /**
     * Brings a stored file up to [SCHEMA_VERSION] in memory. Nothing is written
     * here: the migrated shape lands on disk with the next [persist], so a read
     * alone can never lose a record it failed to understand.
     *
     * v1 → v2 keyed records by package name, which allowed only one build per
     * app. Each record is filed under the package it installs as, which is the
     * identity v1 records already had, so no v1 history is dropped or merged.
     * Two v1 records could only collide here if the same file already held
     * duplicates, and the first (most recent) wins, matching what v1 itself
     * returned from `get`.
     */
    private fun migrate(stored: StoreFile): List<PatchedAppRecord> {
        if (stored.version >= SCHEMA_VERSION && stored.records.all { it.id.isNotBlank() }) {
            return stored.records
        }
        val migrated = stored.records
            .map { it.withResolvedId() }
            .distinctBy { it.trackingKey }
        val dropped = stored.records.size - migrated.size
        if (dropped > 0) {
            logger.warning("Patched-app history held $dropped duplicate record(s); kept the newest of each")
        }
        logger.info("Migrated patched-app history from schema v${stored.version} to v$SCHEMA_VERSION")
        return migrated
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

        /** Bump when the on-disk shape changes incompatibly; add a migration in [migrate]. */
        const val SCHEMA_VERSION = 2

        /**
         * Process-wide shared instance — use this everywhere in production so the
         * in-memory cache is coherent (two instances in one process could race
         * and drop each other's records). Tests construct their own with a
         * custom [file].
         */
        val shared: PatchedAppStore by lazy { PatchedAppStore() }

        /** [this] with [PatchedAppRecord.id] filled in, which is what the store files it under. */
        private fun PatchedAppRecord.withResolvedId(): PatchedAppRecord =
            if (id.isNotBlank()) this else copy(id = installedPackageName)
    }
}
