/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.gui.data.repository

import app.morphe.engine.network.HttpService
import app.morphe.engine.patches.PatchProvider
import app.morphe.engine.patches.RemotePatchSourceFactory
import app.morphe.gui.data.model.PatchSource
import app.morphe.gui.data.model.PatchSourceType
import app.morphe.gui.util.ChangelogEntry
import app.morphe.gui.util.ChangelogParser
import app.morphe.gui.util.Logger
import io.ktor.client.HttpClient
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/**
 * What every changelog consumer asks for: the Home update badge, Home / Installed App Info
 * "What's New", the patch-source dialog and the source-details dialog all describe their
 * question with this one model, so none of them invents its own release filtering.
 *
 * @property sourceId stable id of the patch source (attribution key, not a display name)
 * @property sourceUrl the source's repository URL; null for a local source, which has no changelog
 * @property prerelease the channel the source follows (dev branch) or not (main branch)
 * @property sinceVersion the caller's own baseline: only releases strictly newer than it are
 *   "new". Blank, "unknown" or non-version values count as no baseline at all.
 * @property currentVersion the source's own current version, the baseline a stable-channel view
 *   falls back to (shown inclusive) when the caller supplied no [sinceVersion]
 * @property appNames names one app is known by; non-empty narrows every entry to that app
 * @property generalHeading heading for unscoped changes kept next to an app's own; null drops them
 */
data class ChangelogRequest(
    val sourceId: String,
    val sourceUrl: String?,
    val prerelease: Boolean,
    val sinceVersion: String? = null,
    val currentVersion: String? = null,
    val appNames: Set<String> = emptySet(),
    val generalHeading: String? = null,
)

fun PatchSource.changelogRequest(
    sinceVersion: String? = null,
    currentVersion: String? = null,
    appNames: Set<String> = emptySet(),
    generalHeading: String? = null,
) = ChangelogRequest(
    sourceId = id,
    sourceUrl = url.takeUnless { type == PatchSourceType.LOCAL },
    prerelease = usePreRelease,
    sinceVersion = sinceVersion,
    currentVersion = currentVersion,
    appNames = appNames,
    generalHeading = generalHeading,
)

/** Outcome of a changelog read. Nothing is ever silently turned into an empty list. */
sealed interface ChangelogLoad {
    /**
     * The entries to show; empty means the changelog was read and has nothing to show
     * (e.g. nothing newer than the baseline). [stale] is true when a refresh failed and
     * this comes from an expired cache entry.
     */
    data class Entries(val entries: List<ChangelogEntry>, val stale: Boolean = false) : ChangelogLoad

    /** There is no CHANGELOG.md to read (local source, no URL, or it parsed to nothing). */
    data object Unavailable : ChangelogLoad

    /** The read failed and nothing usable was cached. Retryable. */
    data class Failed(val error: Throwable) : ChangelogLoad
}

/**
 * Fetches, caches and selects changelog content for patch sources.
 *
 * **Cache.** One slot per resolved changelog URL, holding the parsed entries with their fetch
 * time and at most one in-flight fetch. Slots are never removed while the process lives, so
 * invalidation cannot orphan a lock or an in-flight request: it only bumps the slot's
 * generation and expires its entry. Entries are trusted for [ttlMs] (Manager's 10 minutes).
 *
 * **In-flight sharing.** Concurrent requests for the same slot share one fetch; different
 * URLs never wait on each other. The fetch runs in a repository-owned scope so one caller
 * leaving does not cancel it for the others, but when the *last* waiter leaves, the fetch is
 * cancelled: a closed dialog does not keep a request alive.
 *
 * **Invalidation.** A fetch that started before an invalidation never becomes a fresh cache
 * entry (its generation is stale), and a caller that waited on it fetches again once, after it
 * finished, so an explicit refresh is never answered with pre-refresh data and there is still
 * never more than one request in flight per URL.
 *
 * **Failure.** A failed fetch returns the previous entry, even expired, marked stale; only with
 * nothing cached is the failure reported.
 *
 * [fetchText] and [clock] are injectable so TTL and concurrency are testable without network
 * or real time.
 */
class ChangelogRepository(
    private val fetchText: suspend (url: String) -> String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = CACHE_TTL_MS,
) {
    constructor(httpClient: HttpClient) : this(httpFetcher(HttpService(httpClient)))

    private class CacheEntry(val entries: List<ChangelogEntry>, val fetchedAtMs: Long)

    private class Flight(val generation: Long, val job: Deferred<List<ChangelogEntry>>) {
        var waiters = 0
    }

    private class Slot {
        var entry: CacheEntry? = null
        var generation = 0L
        var flight: Flight? = null
    }

    private class Read(val entries: List<ChangelogEntry>, val stale: Boolean)

    private val slots = ConcurrentHashMap<String, Slot>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ─── Public API ──────────────────────────────────────────────────────────

    /** The entries [request] should display: baseline-selected, then narrowed to its apps. */
    suspend fun load(request: ChangelogRequest): ChangelogLoad {
        val url = channelUrl(request) ?: return ChangelogLoad.Unavailable
        return when (val read = read(url, stopAfterFirstStable = request.prerelease)) {
            is ReadResult.Ok -> {
                if (read.value.entries.isEmpty()) ChangelogLoad.Unavailable
                else ChangelogLoad.Entries(select(read.value.entries, request), read.value.stale)
            }
            is ReadResult.Error -> ChangelogLoad.Failed(read.error)
        }
    }

    /**
     * Older stable history: the full changelog of the stable branch, minus what is [shownVersions]
     * already and any pre-release leftovers, narrowed to [request]'s apps. Never fetched by
     * [load]; a caller asks for it when the user does. Cached separately from the recent view,
     * so asking twice costs one request and loaded entries are never refetched.
     */
    suspend fun loadOlder(request: ChangelogRequest, shownVersions: Set<String>): ChangelogLoad {
        val url = stableUrl(request) ?: return ChangelogLoad.Unavailable
        val shown = shownVersions.map { it.trim().removePrefix("v") }.toSet()
        return when (val read = read(url, stopAfterFirstStable = false)) {
            is ReadResult.Ok -> {
                val older = read.value.entries.filter {
                    it.version.trim().removePrefix("v") !in shown && !it.version.contains('-')
                }
                ChangelogLoad.Entries(
                    ChangelogParser.entriesFor(older, request.appNames, request.generalHeading),
                    read.value.stale,
                )
            }
            is ReadResult.Error -> ChangelogLoad.Failed(read.error)
        }
    }

    /**
     * Whether a release newer than [ChangelogRequest.sinceVersion] says anything about the request's
     * apps: the one rule behind every update / rebuild badge.
     *
     * Everything that leaves the question open answers **yes**, exactly as Manager does: no
     * changelog to read (local source, unreadable, empty), no app name to match its scopes
     * against, or no usable baseline. Staying quiet about a real update is the worse failure,
     * and that choice lives here rather than in each caller.
     */
    suspend fun hasRelevantChanges(request: ChangelogRequest): Boolean {
        if (request.appNames.isEmpty()) return true
        val url = channelUrl(request) ?: return true
        val read = (read(url, stopAfterFirstStable = request.prerelease) as? ReadResult.Ok)?.value
            ?: return true
        if (read.entries.isEmpty()) return true
        if (ChangelogParser.usableBaseline(request.sinceVersion) == null) return true
        return ChangelogParser.hasChangesFor(read.entries, request.sinceVersion, request.appNames)
    }

    /**
     * Expires everything cached for [source], on both channels and the full history, so the next
     * read goes to the network. The expired entries stay as a stale fallback for a failed
     * fetch unless [dropStale] (a removed source has nothing left worth showing).
     */
    fun invalidate(source: PatchSource, dropStale: Boolean = false) {
        for (key in keysFor(source)) invalidateKey(key, dropStale)
    }

    /** Expires every cached changelog, e.g. on an explicit refresh of all patch sources. */
    fun invalidateAll(dropStale: Boolean = false) {
        for (key in slots.keys.toList()) invalidateKey(key, dropStale)
    }

    // ─── Selection (pure) ────────────────────────────────────────────────────

    private fun select(all: List<ChangelogEntry>, request: ChangelogRequest): List<ChangelogEntry> {
        val baseline = ChangelogParser.usableBaseline(request.sinceVersion)
        val shown = when {
            // The caller's baseline asks what changed since it, not including it. A baseline
            // that is already the newest release yields nothing; the latest is never "new".
            baseline != null -> ChangelogParser.entriesNewerThan(all, baseline)

            // Pre-release channel: from the last stable release onwards
            request.prerelease -> {
                val lastStable = all.firstOrNull { !it.version.contains('-') }
                if (lastStable != null) {
                    ChangelogParser.entriesNewerThan(all, lastStable.version) + lastStable
                } else all.take(NO_STABLE_FALLBACK_COUNT)
            }

            // Stable channel: the source's current version and everything newer
            else -> {
                val current = ChangelogParser.usableBaseline(request.currentVersion)
                if (current == null) all else {
                    val currentEntry = ChangelogParser.findVersion(all, current)
                    ChangelogParser.entriesNewerThan(all, current) + listOfNotNull(currentEntry)
                }
            }
        }
        return ChangelogParser.entriesFor(shown, request.appNames, request.generalHeading)
    }

    // ─── Cache / in-flight machinery ─────────────────────────────────────────

    private sealed interface ReadResult {
        data class Ok(val value: Read) : ReadResult
        data class Error(val error: Throwable) : ReadResult
    }

    private suspend fun read(url: String, stopAfterFirstStable: Boolean): ReadResult {
        val key = keyOf(url, stopAfterFirstStable)
        val slot = slots.computeIfAbsent(key) { Slot() }

        var attempts = 0
        while (true) {
            val flight: Flight
            synchronized(slot) {
                slot.entry?.takeIf { clock() - it.fetchedAtMs < ttlMs }?.let {
                    return ReadResult.Ok(Read(it.entries, stale = false))
                }
                flight = slot.flight ?: startFlight(slot, url, stopAfterFirstStable)
                flight.waiters++
            }

            val outcome: Result<List<ChangelogEntry>> = try {
                Result.success(flight.job.await())
            } catch (e: CancellationException) {
                // Only propagate this caller's own cancellation; a cancelled shared fetch
                // (every other waiter left) reads as a failure to whoever is still here
                if (!flight.job.isCancelled || !currentCoroutineIsActive()) throw e
                Result.failure(e)
            } catch (t: Throwable) {
                Result.failure(t)
            } finally {
                leave(slot, flight)
            }

            val invalidatedMeanwhile = synchronized(slot) { slot.generation != flight.generation }
            if (invalidatedMeanwhile && attempts++ < MAX_REFETCH_AFTER_INVALIDATION) continue

            return synchronized(slot) {
                outcome.fold(
                    onSuccess = { entries ->
                        if (entries.isNotEmpty()) ReadResult.Ok(Read(entries, stale = invalidatedMeanwhile))
                        else slot.entry?.let { ReadResult.Ok(Read(it.entries, stale = true)) }
                            ?: ReadResult.Ok(Read(emptyList(), stale = false))
                    },
                    onFailure = { e ->
                        Logger.debug("Changelog unavailable at $url: ${e.message}")
                        slot.entry?.let { ReadResult.Ok(Read(it.entries, stale = true)) }
                            ?: ReadResult.Error(e)
                    },
                )
            }
        }
    }

    /** Starts the one fetch for [slot]. Caller holds the slot's monitor. */
    private fun startFlight(slot: Slot, url: String, stopAfterFirstStable: Boolean): Flight {
        val generation = slot.generation
        val job = scope.async(start = CoroutineStart.LAZY) {
            val entries = ChangelogParser.parse(fetchText(url), stopAfterFirstStable)
            synchronized(slot) {
                // A fetch that began before an invalidation must not become a fresh entry
                if (slot.generation == generation && entries.isNotEmpty()) {
                    slot.entry = CacheEntry(entries, clock())
                }
            }
            entries
        }
        val flight = Flight(generation, job)
        slot.flight = flight
        job.invokeOnCompletion {
            synchronized(slot) { if (slot.flight === flight) slot.flight = null }
        }
        job.start()
        return flight
    }

    /** A waiter is done; the last one out cancels a fetch nobody is waiting for. */
    private fun leave(slot: Slot, flight: Flight) {
        synchronized(slot) {
            flight.waiters--
            if (flight.waiters == 0 && flight.job.isActive) {
                flight.job.cancel()
                if (slot.flight === flight) slot.flight = null
            }
        }
    }

    private fun invalidateKey(key: String, dropStale: Boolean) {
        val slot = slots[key] ?: return
        synchronized(slot) {
            slot.generation++
            if (dropStale) slot.entry = null
            else slot.entry?.let { slot.entry = CacheEntry(it.entries, fetchedAtMs = Long.MIN_VALUE / 2) }
        }
    }

    private suspend fun currentCoroutineIsActive(): Boolean =
        kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]?.isActive != false

    // ─── URLs / keys ─────────────────────────────────────────────────────────

    private fun keyOf(url: String, stopAfterFirstStable: Boolean) =
        "$url|${if (stopAfterFirstStable) "recent" else "full"}"

    /** Every cache key a source can occupy: both channels' files, recent and full. */
    private fun keysFor(source: PatchSource): List<String> {
        val request = source.changelogRequest()
        return listOfNotNull(
            urlFor(request.sourceUrl, prerelease = true)?.let { keyOf(it, true) },
            urlFor(request.sourceUrl, prerelease = false)?.let { keyOf(it, true) },
            urlFor(request.sourceUrl, prerelease = false)?.let { keyOf(it, false) },
            urlFor(request.sourceUrl, prerelease = true)?.let { keyOf(it, false) },
        )
    }

    private fun channelUrl(request: ChangelogRequest) = urlFor(request.sourceUrl, request.prerelease)

    /** Older history always comes from the stable branch. */
    private fun stableUrl(request: ChangelogRequest) = urlFor(request.sourceUrl, prerelease = false)

    private fun urlFor(sourceUrl: String?, prerelease: Boolean): String? {
        val url = sourceUrl?.takeIf { it.isNotBlank() } ?: return null
        val parsed = RemotePatchSourceFactory.parse(url) ?: return null
        val branch = if (prerelease) "dev" else "main"
        return when (parsed.provider) {
            PatchProvider.GITHUB -> "$GITHUB_RAW_BASE/${parsed.repoPath}/$branch/$CHANGELOG_FILE"
            PatchProvider.GITLAB -> "$GITLAB_BASE/${parsed.repoPath}/-/raw/$branch/$CHANGELOG_FILE"
        }
    }

    companion object {
        private fun httpFetcher(http: HttpService): suspend (String) -> String =
            { url -> http.request<String>(url) }

        private const val GITHUB_RAW_BASE = "https://raw.githubusercontent.com"
        private const val GITLAB_BASE = "https://gitlab.com"
        private const val CHANGELOG_FILE = "CHANGELOG.md"

        /** Manager's changelog freshness: trusted for 10 minutes, then fetched again. */
        const val CACHE_TTL_MS = 10 * 60_000L

        /** A pre-release changelog with no stable release in it shows this many entries. */
        private const val NO_STABLE_FALLBACK_COUNT = 30

        /** After an invalidation mid-flight a caller refetches once, never in a loop. */
        private const val MAX_REFETCH_AFTER_INVALIDATION = 1
    }
}
