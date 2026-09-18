/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.gui.data.repository

import app.morphe.gui.util.FileUtils
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * Tracks which patch names have already been shown to the user for each
 * (package, source), so a bundle update can tell a genuinely new patch
 * apart from one the user previously saw and left disabled — see
 * `PatchSelectionViewModel`'s `newPatchesByBundle`.
 *
 * Hardened to match `app.morphe.engine.PatchedAppStore`'s established
 * JSON-persistence pattern: an atomic staging-file write (a crash mid-write
 * must never leave a truncated `seen-patches.json` behind), an in-memory
 * cache (avoids re-parsing the whole file on every lookup — this is called
 * once per bundle on every patch-selection load), and a schema version field
 * for future migrations. Was previously a direct, non-atomic `writeText`
 * with no cache on every call.
 *
 * Keyed by `sourceId` — a patch source's stable id, not its (renameable)
 * display name — resolved by the caller before reaching here; see
 * `PatchSelectionViewModel.resolveSourceId`.
 *
 * Reads the pre-hardening bare-map format (no version envelope, and keyed by
 * source *name*) transparently: [load] falls back to it when the new
 * enveloped format fails to parse. The next [save] rewrites the file in the
 * new format — no separate one-time migration step needed for a file this
 * small and already-lossy on rename (a bare-name-keyed entry for a
 * since-renamed source was already unreachable before this change, same as
 * every other name-keyed store this session has fixed).
 */
class SeenPatchesRepository(
    private val file: File = File(FileUtils.getAppDataDir(), "seen-patches.json"),
) {
    private val logger = java.util.logging.Logger.getLogger(SeenPatchesRepository::class.java.name)
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
    private val mutex = Mutex()

    private var cache: MutableMap<String, MutableMap<String, List<String>>>? = null

    private fun load(): MutableMap<String, MutableMap<String, List<String>>> {
        cache?.let { return it }
        val loaded = if (!file.exists()) {
            mutableMapOf()
        } else {
            val text = file.readText()
            val enveloped = runCatching { json.decodeFromString<StoreFile>(text) }.getOrNull()
            when {
                enveloped != null ->
                    enveloped.packages.mapValues { (_, bySource) -> bySource.toMutableMap() }.toMutableMap()
                else -> runCatching {
                    // Pre-hardening bare format: no "version"/"packages" envelope.
                    json.decodeFromString<Map<String, Map<String, List<String>>>>(text)
                        .mapValues { (_, v) -> v.toMutableMap() }.toMutableMap()
                }.getOrElse {
                    logger.warning("Could not read seen-patches.json, starting empty: ${it.message}")
                    mutableMapOf()
                }
            }
        }
        cache = loaded
        return loaded
    }

    suspend fun get(packageName: String, sourceId: String): Set<String>? =
        withContext(Dispatchers.IO) {
            mutex.withLock { load()[packageName]?.get(sourceId)?.toSet() }
        }

    suspend fun save(packageName: String, sourceId: String, patchNames: Set<String>) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val all = load()
                all.getOrPut(packageName) { mutableMapOf() }[sourceId] = patchNames.sorted()
                persist(all)
            }
        }

    private fun persist(all: Map<String, Map<String, List<String>>>) {
        try {
            file.parentFile?.mkdirs()
            val content = json.encodeToString(StoreFile.serializer(), StoreFile(SCHEMA_VERSION, all))
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
                // ATOMIC_MOVE isn't supported on every filesystem — fall back.
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            cache = all.mapValues { (_, v) -> v.toMutableMap() }.toMutableMap()
        } catch (e: Exception) {
            logger.warning("Could not write seen-patches.json: ${e.message}")
        }
    }

    @Serializable
    private data class StoreFile(
        val version: Int = SCHEMA_VERSION,
        // No default: a pre-hardening bare-map file has no "packages" key at
        // all, so decoding it as StoreFile must fail outright (missing
        // required field) and fall through to the bare-format parser in
        // [load] — a default here would let decodeFromString "succeed" on an
        // old file by silently defaulting packages to empty, discarding every
        // existing seen-patches record instead of reading it.
        val packages: Map<String, Map<String, List<String>>>,
    )

    companion object {
        private const val SCHEMA_VERSION = 1
    }
}
