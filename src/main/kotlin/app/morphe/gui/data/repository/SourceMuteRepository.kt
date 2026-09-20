/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.gui.data.repository

import app.morphe.engine.util.AtomicFiles
import app.morphe.gui.util.FileUtils
import app.morphe.gui.util.Logger
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Which sources each app is kept from being patched by.
 *
 * A source switched off in [PatchSourceManager] is off for everything; this narrows one app
 * instead. Someone who patches one app from one source and another app from a second source
 * stops being asked which to use for the first — its selection screen simply doesn't offer
 * the muted source's patches. Patches added to the source they turned down never join that
 * app's selection behind their back.
 *
 * Apps are keyed by package name, not by a particular patched build: a repatch of the same
 * app is offered the same sources its earlier patches were.
 *
 * Ported from `app.morphe.manager.domain.repository.SourceMuteRepository`. Bundle identity
 * uses Desktop's stable [app.morphe.gui.data.model.PatchSource.id] string rather than
 * Manager's Room-assigned integer UID — Desktop already has a stable per-source id for
 * exactly this purpose (see [PatchSourceManager]), so there's no reason to introduce a
 * second identity scheme just to mirror Manager's Android storage layer.
 *
 * Persistence follows the same atomic-JSON-file pattern as [SeenPatchesRepository] and
 * [app.morphe.engine.PatchedAppStore]: writes go through [AtomicFiles] (crash mid-write
 * never leaves a truncated file), reads are cached in memory, and the file carries a
 * schema version for future migrations.
 */
class SourceMuteRepository(
    private val file: File = File(FileUtils.getAppDataDir(), "source-mutes.json"),
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
    private val mutex = Mutex()

    /** packageName -> muted source ids. Packages that rule out nothing are absent. */
    private var cache: MutableMap<String, MutableSet<String>>? = null

    // Bumped on every mutation so Compose call sites can key off it the same way
    // PatchSourceManager.sourceVersion drives reloads on source list changes.
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    @Serializable
    private data class StoreFile(
        val version: Int = 1,
        val mutes: Map<String, Set<String>> = emptyMap(),
    )

    private fun load(): MutableMap<String, MutableSet<String>> {
        cache?.let { return it }
        val loaded = if (!file.isFile) {
            mutableMapOf()
        } else {
            runCatching {
                json.decodeFromString<StoreFile>(file.readText())
                    .mutes.mapValues { (_, v) -> v.toMutableSet() }.toMutableMap()
            }.getOrElse {
                Logger.error("Failed to read source-mutes.json, starting fresh", it)
                mutableMapOf()
            }
        }
        cache = loaded
        return loaded
    }

    private fun persist(map: Map<String, Set<String>>) {
        try {
            AtomicFiles.write(file, json.encodeToString(StoreFile(mutes = map)))
        } catch (e: Exception) {
            Logger.error("Failed to write source-mutes.json", e)
        }
    }

    /** The source ids [packageName] is currently kept from. */
    suspend fun getMutedFor(packageName: String): Set<String> = withContext(Dispatchers.IO) {
        mutex.withLock { load()[packageName]?.toSet() ?: emptySet() }
    }

    /** Full snapshot: packageName -> muted source ids. */
    suspend fun mutedSources(): Map<String, Set<String>> = withContext(Dispatchers.IO) {
        mutex.withLock { load().mapValues { (_, v) -> v.toSet() } }
    }

    /** The same exclusions read the other way round: apps kept from each source. */
    suspend fun mutedApps(): Map<String, Set<String>> = withContext(Dispatchers.IO) {
        mutex.withLock {
            load().entries
                .flatMap { (pkg, sources) -> sources.map { it to pkg } }
                .groupBy({ it.first }, { it.second })
                .mapValues { it.value.toSet() }
        }
    }

    /** Rules every source out for [packageName] except [keepId], out of [candidates]. */
    suspend fun keepOnly(packageName: String, keepId: String, candidates: Set<String>) =
        replaceForPackage(packageName, candidates - keepId)

    suspend fun mute(packageName: String, sourceId: String) =
        replaceForPackage(packageName, getMutedFor(packageName) + sourceId)

    /** Offers every source to [packageName] again. */
    suspend fun unmuteAll(packageName: String) = replaceForPackage(packageName, emptySet())

    suspend fun unmute(packageName: String, sourceId: String) =
        replaceForPackage(packageName, getMutedFor(packageName) - sourceId)

    suspend fun resetForSource(sourceId: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val all = load()
            var changed = false
            val toDrop = mutableListOf<String>()
            for ((pkg, sources) in all) {
                if (sources.remove(sourceId)) changed = true
                if (sources.isEmpty()) toDrop += pkg
            }
            toDrop.forEach { all.remove(it) }
            if (changed) {
                persist(all.mapValues { it.value.toSet() })
                _version.value++
            }
        }
    }

    /** Sources at least one app is kept from — the other half of what a backup has to cover. */
    suspend fun getAllMutedSourceIds(): Set<String> = withContext(Dispatchers.IO) {
        mutex.withLock { load().values.flatten().toSet() }
    }

    /** The apps kept from [sourceId], which a backup carries alongside that source's selection. */
    suspend fun exportForSource(sourceId: String): Set<String> = mutedApps()[sourceId].orEmpty()

    /** Replaces what a backup says about [sourceId], for a restore that starts from a clean slate. */
    suspend fun importForSource(sourceId: String, packageNames: Collection<String>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val all = load()
            // Clear sourceId from every package first (clean-slate restore), then reapply.
            val toDrop = mutableListOf<String>()
            for ((pkg, sources) in all) {
                sources.remove(sourceId)
                if (sources.isEmpty()) toDrop += pkg
            }
            toDrop.forEach { all.remove(it) }
            for (pkg in packageNames) {
                all.getOrPut(pkg) { mutableSetOf() }.add(sourceId)
            }
            persist(all.mapValues { it.value.toSet() })
            _version.value++
        }
    }

    /** Adds what a backup says about [sourceId] to what this install already rules out. */
    suspend fun mergeForSource(sourceId: String, packageNames: Collection<String>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val all = load()
            for (pkg in packageNames) {
                all.getOrPut(pkg) { mutableSetOf() }.add(sourceId)
            }
            persist(all.mapValues { it.value.toSet() })
            _version.value++
        }
    }

    suspend fun reset() = withContext(Dispatchers.IO) {
        mutex.withLock {
            cache = mutableMapOf()
            persist(emptyMap())
            _version.value++
        }
    }

    private suspend fun replaceForPackage(packageName: String, sourceIds: Set<String>) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val all = load()
                if (sourceIds.isEmpty()) {
                    if (all.remove(packageName) == null) return@withContext
                } else {
                    all[packageName] = sourceIds.toMutableSet()
                }
                persist(all.mapValues { it.value.toSet() })
                _version.value++
            }
        }
}

/**
 * The apps a "stop offering this source" actually reaches, out of [apps].
 *
 * An app is left out when the source has no patches for it, or when it is already kept from
 * it. The case that matters is the third: this is the last source still offered to it. An app
 * with nothing left to patch from would read as one Morphe has no patches for, so the
 * exclusion is never recorded rather than recorded and then ignored — see
 * [List.withoutMutedSources] for the read-time half of the same invariant.
 */
fun appsToKeepFrom(
    sourceId: String,
    apps: Set<String>,
    coveredBy: Map<String, Set<String>>,
    keptFrom: Map<String, Set<String>>,
): Set<String> = apps.filterTo(mutableSetOf()) { app ->
    val covering = coveredBy[app].orEmpty()
    val alreadyKeptFrom = keptFrom[app].orEmpty()

    sourceId in covering &&
        sourceId !in alreadyKeptFrom &&
        (covering - alreadyKeptFrom - sourceId).isNotEmpty()
}

/**
 * Drops the sources [muted] rules out, unless that would leave nothing behind.
 *
 * Every source of an app can end up muted: the last one was removed, or a stale entry
 * outlived the patches it pointed at. Such an app would otherwise read as one Morphe has no
 * patches for at all. Ignoring the exclusions is the recoverable half of that — the user sees
 * more sources than they asked for rather than a dead end they can't open to fix.
 */
fun <T> List<T>.withoutMutedSources(muted: Set<String>, idOf: (T) -> String): List<T> {
    if (muted.isEmpty()) return this
    return filterNot { idOf(it) in muted }.ifEmpty { this }
}
