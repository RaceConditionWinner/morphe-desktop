/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.engine.patches

import app.morphe.engine.util.AtomicFiles
import app.morphe.engine.util.FileStamp
import java.io.File
import java.util.logging.Level
import java.util.logging.Logger
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Keeps a patch bundle that cannot be read from taking the whole app down with it, on every
 * launch, forever.
 *
 * A `.mpp` is a jar full of third-party code, and loading one runs that code inside this
 * process ([app.morphe.patcher.patch.loadPatchesFromJar] executes static initializers,
 * instantiates every `Patch` it finds, and may load native libraries a patch bundles). A
 * `Throwable` from that is already handled where it's caught (see the isolation in
 * [MultiSourceLoader]); what neither that nor a try/catch can reach is the process
 * disappearing outright — a hard JVM crash, `System.exit`, or the OS/user killing a hung
 * load. Nothing runs afterward to record what happened, so without this class the app would
 * retry the same fatal load on every subsequent launch and never get anywhere.
 *
 * Two mechanisms, ported from `app.morphe.manager.domain.repository.PatchBundleLoadGuard`:
 * an in-flight marker written before a load starts and removed after it finishes, so a
 * marker still present on the next launch means the previous process died mid-read; and a
 * strike ledger that holds a bundle back once the same file has done that twice in a row.
 * The Android-only dex-cache purge (stale ART quickening cache after a manager update) has
 * no JVM equivalent and is dropped — the class loading this uses doesn't leave that kind of
 * cache behind.
 *
 * One [PatchBundleLoadGuard] instance is shared across all sources (see [AppModule]); state
 * is keyed per [sourceId] + file [FileStamp] internally.
 */
class PatchBundleLoadGuard(
    private val stateDir: File,
) {
    private val ledgerFile = File(stateDir, LEDGER_FILE)

    private val lock = Any()
    private val strikes = mutableMapOf<String, Strike>()
    private var prepared = false

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    /**
     * Settles marker files left by a previous run. Runs once, lazily, before the first
     * [read] — callers don't need to remember to invoke this at startup.
     */
    private fun prepare() = synchronized(lock) {
        if (prepared) return@synchronized
        prepared = true

        strikes.putAll(readLedger())
        recordInterruptedLoads()
    }

    /**
     * Reads a bundle attributed to [sourceId] on disk, so a fatal death inside [load] is
     * traced back to it on the next launch. Throws [PatchBundleHeldBackException] once this
     * exact file has already done that twice.
     */
    fun <T> read(sourceId: String, patchesJar: File, load: () -> T): T {
        prepare()

        val stamp = FileStamp.of(patchesJar)?.toString() ?: "unknown"

        synchronized(lock) {
            val strike = strikes[sourceId]
            if (strike != null) {
                if (strike.stamp != stamp) {
                    // A replaced/updated bundle ships different bytes and starts with a
                    // clean record — whatever the old file did says nothing about this one.
                    strikes.remove(sourceId)
                    writeLedger()
                } else if (strike.count >= HELD_BACK_AFTER) {
                    throw PatchBundleHeldBackException(sourceId)
                }
            }
        }

        val marker = inFlightFileFor(sourceId)
        runCatching { AtomicFiles.write(marker, stamp) }
            .onFailure { logger.log(Level.WARNING, "Could not mark source '$sourceId' as loading", it) }

        try {
            return load().also {
                synchronized(lock) {
                    if (strikes.remove(sourceId) != null) writeLedger()
                }
            }
        } finally {
            runCatching { marker.delete() }
        }
    }

    /** Drops what is remembered about [sourceId] — call when a source is removed entirely. */
    fun forget(sourceId: String) = synchronized(lock) {
        strikes.remove(sourceId)
        runCatching { inFlightFileFor(sourceId).delete() }
        writeLedger()
    }

    private fun recordInterruptedLoads() {
        val markers = stateDir.listFiles { f -> f.name.startsWith(IN_FLIGHT_PREFIX) } ?: return
        if (markers.isEmpty()) return

        for (marker in markers) {
            val sourceId = marker.name.removePrefix(IN_FLIGHT_PREFIX)
            val stamp = runCatching { marker.readText() }.getOrNull()?.trim().orEmpty()
            runCatching { marker.delete() }
            if (sourceId.isBlank()) continue

            val previous = strikes[sourceId]?.takeIf { it.stamp == stamp }
            val count = (previous?.count ?: 0) + 1
            strikes[sourceId] = Strike(count, stamp)

            // A process can also die mid-read because the OS reclaimed it or the user quit
            // the app, so one interrupted read alone is not yet a verdict on the bundle.
            logger.warning(
                "Source '$sourceId' was still loading when the previous process ended " +
                    "($count of $HELD_BACK_AFTER before it's held back)"
            )
        }

        writeLedger()
    }

    private fun readLedger(): Map<String, Strike> {
        val text = runCatching { ledgerFile.takeIf { it.isFile }?.readText() }.getOrNull() ?: return emptyMap()
        return runCatching { json.decodeFromString<LedgerFile>(text).strikes }
            .getOrElse {
                logger.log(Level.WARNING, "Could not read bundle load ledger, starting fresh", it)
                emptyMap()
            }
    }

    private fun writeLedger() {
        runCatching {
            if (strikes.isEmpty()) {
                ledgerFile.delete()
            } else {
                AtomicFiles.write(ledgerFile, json.encodeToString(LedgerFile(strikes = strikes.toMap())))
            }
        }.onFailure { logger.log(Level.WARNING, "Could not record bundle load failures", it) }
    }

    private fun inFlightFileFor(sourceId: String) = File(stateDir, "$IN_FLIGHT_PREFIX$sourceId")

    @Serializable
    private data class Strike(val count: Int, val stamp: String)

    @Serializable
    private data class LedgerFile(val version: Int = 1, val strikes: Map<String, Strike> = emptyMap())

    private companion object {
        val logger: Logger = Logger.getLogger(PatchBundleLoadGuard::class.java.name)

        const val IN_FLIGHT_PREFIX = "reading-"
        const val LEDGER_FILE = "bundle-load-failures.json"

        /** Interrupted reads of the same file tolerated before the bundle is held back. */
        const val HELD_BACK_AFTER = 2
    }
}

/**
 * Thrown in place of reading a bundle that has already ended the process while loading, from
 * the exact same file, more than once in a row.
 */
class PatchBundleHeldBackException(val sourceId: String) : Exception(
    "Source '$sourceId' previously crashed the app while loading and will not be read again " +
        "until its file changes"
)
