/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.gui.data.repository

import app.morphe.desktop.command.model.PatchBundle
import app.morphe.desktop.command.model.PatchBundleMeta
import app.morphe.desktop.command.model.PatchEntry
import app.morphe.engine.util.AtomicFiles
import app.morphe.gui.util.FileUtils
import app.morphe.gui.util.Logger
import java.io.File
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
/**
 * Stores per-(source, package) patch selections and option values across sessions.
 *
 * On disk: <app-data>/patch-preferences.json
 * Schema:  Map<sourceName, Map<packageName, PatchBundle>>
 *
 * The on-disk shape reuses [PatchBundle] / [PatchEntry] from the CLI options file
 * format, so prefs files are interchangeable with `patch --options-file` input/output.
 */
class PatchPreferencesRepository {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val mutex = Mutex()
    private var cache: MutableMap<String, MutableMap<String, PatchBundle>>? = null

    private fun prefsFile(): File = File(FileUtils.getAppDataDir(), "patch-preferences.json")

    private fun load(): MutableMap<String, MutableMap<String, PatchBundle>> {
        cache?.let { return it }
        val file = prefsFile()
        val parsed = try {
            if (file.exists()) {
                json.decodeFromString<Map<String, Map<String, PatchBundle>>>(file.readText())
                    .mapValues { (_, byPkg) -> byPkg.toMutableMap() }
                    .toMutableMap()
            } else {
                mutableMapOf()
            }
        } catch (e: Exception) {
            Logger.error("Failed to load patch preferences, starting fresh", e)
            mutableMapOf()
        }
        cache = parsed
        return parsed
    }

    /**
     * Full snapshot: sourceId -> packageName -> saved selection. Used to build cross-bundle
     * "copy selection from another app/source" candidates ([loadCopySelectionCandidates]) —
     * the one place this repository needs to be enumerated rather than looked up by key.
     */
    suspend fun allSelections(): Map<String, Map<String, PatchBundle>> = withContext(Dispatchers.IO) {
        mutex.withLock { load().mapValues { (_, byPkg) -> byPkg.toMap() } }
    }

    /** Alias of [allSelections] for backup/export call sites, where the name reads clearer
     *  next to [importAll]/[mergeAll]. Same data — this repository has nothing more granular
     *  worth exporting per source, since a source's rows already come out keyed by package. */
    suspend fun exportAll(): Map<String, Map<String, PatchBundle>> = allSelections()

    /**
     * Replaces every saved selection with [data] — a clean-slate restore. Rejects (throws,
     * nothing is written) a payload containing an empty patch map for some package, since
     * that's never something a legitimate export produces and more likely means the backup
     * file was hand-edited or corrupted; a restore should fail loudly rather than silently
     * install a bundle's worth of "no selection" entries.
     */
    suspend fun importAll(data: Map<String, Map<String, PatchBundle>>) = withContext(Dispatchers.IO) {
        require(data.values.all { byPkg -> byPkg.values.none { it.patches.isEmpty() } }) {
            "Refusing to import patch preferences containing an empty selection entry"
        }
        mutex.withLock {
            cache = data.mapValues { (_, byPkg) -> byPkg.toMutableMap() }.toMutableMap()
            persist(data)
            Logger.info("Imported patch preferences for ${data.values.sumOf { it.size }} app(s)")
        }
    }

    /**
     * Adds [data] on top of whatever is already saved — an app+source pair present in both
     * is overwritten by [data]'s entry (the imported backup wins for anything it names),
     * but every existing entry [data] doesn't mention is left alone. Use this over
     * [importAll] when restoring onto an install that already has its own selections, so
     * the restore can't wipe out choices the backup simply never captured.
     */
    suspend fun mergeAll(data: Map<String, Map<String, PatchBundle>>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val all = load()
            for ((sourceId, byPkg) in data) {
                val target = all.getOrPut(sourceId) { mutableMapOf() }
                for ((packageName, bundle) in byPkg) {
                    if (bundle.patches.isNotEmpty()) target[packageName] = bundle
                }
            }
            persist(all)
            Logger.info("Merged patch preferences for ${data.values.sumOf { it.size }} app(s)")
        }
    }

    /**
     * Returns the saved [PatchBundle] for ([sourceName], [packageName]), or null if none.
     */
    suspend fun get(sourceName: String, packageName: String): PatchBundle? = withContext(Dispatchers.IO) {
        mutex.withLock {
            load()[sourceName]?.get(packageName)
        }
    }

    /**
     * Returns true if a saved selection exists for ([sourceName], [packageName]).
     */
    suspend fun has(sourceName: String, packageName: String): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            load()[sourceName]?.containsKey(packageName) == true
        }
    }

    /**
     * Saves the given enabled-set + options for ([sourceName], [packageName]).
     *
     * @param enabledPatchNames patches the user has enabled (those not listed are treated as disabled)
     * @param disabledPatchNames patches the user has explicitly disabled (must include the patches in this set so we can later distinguish "user opted out" from "patch removed from .mpp")
     * @param options optional per-patch option values keyed by patch name
     */
    suspend fun save(
        sourceName: String,
        packageName: String,
        enabledPatchNames: Set<String>,
        disabledPatchNames: Set<String>,
        options: Map<String, Map<String, JsonElement>> = emptyMap(),
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val all = load()
            val byPkg = all.getOrPut(sourceName) { mutableMapOf() }
            val existing = byPkg[packageName]

            val patches = mutableMapOf<String, PatchEntry>()
            for (name in enabledPatchNames) {
                patches[name] = PatchEntry(
                    enabled = true,
                    options = options[name] ?: emptyMap(),
                )
            }
            for (name in disabledPatchNames) {
                patches[name] = PatchEntry(
                    enabled = false,
                    options = options[name] ?: emptyMap(),
                )
            }

            val now = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
            val bundle = PatchBundle(
                meta = PatchBundleMeta(
                    createdAt = existing?.meta?.createdAt ?: now,
                    updatedAt = if (existing != null) now else null,
                    source = sourceName,
                    sha256 = null,
                ),
                patches = patches,
            )
            byPkg[packageName] = bundle
            persist(all)
            Logger.info("Saved patch preferences for $sourceName / $packageName (${patches.size} entries)")
        }
    }

    /** Clears the saved selection for ([sourceId], [packageName]) only — leaves every other
     *  source's and every other app's entries untouched, matching Manager's "selection update
     *  scope" invariant: this never reaches into a disabled/out-of-scope bundle's data. */
    suspend fun resetForAppAndSource(sourceId: String, packageName: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val all = load()
            val byPkg = all[sourceId] ?: return@withLock
            if (byPkg.remove(packageName) == null) return@withLock
            if (byPkg.isEmpty()) all.remove(sourceId)
            persist(all)
            Logger.info("Reset patch preferences for $sourceId / $packageName")
        }
    }

    /** Clears [packageName]'s saved selection across every source — used when an app's
     *  choices should start clean regardless of which bundle they came from. */
    suspend fun resetForApp(packageName: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val all = load()
            var changed = false
            val emptied = mutableListOf<String>()
            for ((sourceId, byPkg) in all) {
                if (byPkg.remove(packageName) != null) changed = true
                if (byPkg.isEmpty()) emptied += sourceId
            }
            emptied.forEach { all.remove(it) }
            if (changed) {
                persist(all)
                Logger.info("Reset patch preferences for $packageName across all sources")
            }
        }
    }

    /** Clears every saved selection for [sourceId] (every app) — used when a source is
     *  removed entirely, so a future, unrelated source that happens to reuse the id doesn't
     *  inherit stale choices. */
    suspend fun resetForSource(sourceId: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val all = load()
            if (all.remove(sourceId) == null) return@withLock
            persist(all)
            Logger.info("Reset patch preferences for source $sourceId")
        }
    }

    /** Clears every saved selection, for every app and every source. */
    suspend fun resetAll() = withContext(Dispatchers.IO) {
        mutex.withLock {
            cache = mutableMapOf()
            persist(mutableMapOf())
            Logger.info("Reset all patch preferences")
        }
    }

    private fun persist(all: Map<String, Map<String, PatchBundle>>) {
        try {
            val file = prefsFile()
            file.parentFile?.mkdirs()
            AtomicFiles.write(file, json.encodeToString(all))
        } catch (e: Exception) {
            Logger.error("Failed to write patch preferences", e)
        }
    }
}
