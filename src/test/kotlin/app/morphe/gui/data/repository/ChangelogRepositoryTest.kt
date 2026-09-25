/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.gui.data.repository

import app.morphe.gui.data.model.PatchSource
import app.morphe.gui.data.model.PatchSourceType
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [ChangelogRepository]'s per-URL caching, concurrency, TTL/staleness and app-scoped
 * selection. Most tests drive the repository through its `(fetchText, clock)`
 * constructor directly — no network, and time is whatever the test says it is, so a
 * 10-minute TTL doesn't mean a 10-minute test. One test goes through the real
 * `HttpClient` constructor (MockEngine, as [BlocklistRepositoryTest] does) to check
 * the provider → raw-URL construction end to end.
 */
class ChangelogRepositoryTest {

    private fun source(
        id: String = "src",
        url: String = "https://github.com/someowner/somerepo",
        prerelease: Boolean = false,
    ) = PatchSource(id = id, name = id, type = PatchSourceType.GITHUB, url = url, usePreRelease = prerelease)

    private val changelogBody = """
        ## [1.1.0](https://x/compare/v1.0.0...v1.1.0) (2026-09-01)

        * **Reddit:** Something changed
    """.trimIndent()

    /** A [fetchText] double: counts calls per URL, and can fail or return arbitrary text. */
    private class FakeFetcher(private val bodies: Map<String, String>) {
        val calls = ConcurrentHashMap<String, AtomicInteger>()
        var failing = false
        var delayMs = 0L

        val fn: suspend (String) -> String = { url ->
            calls.getOrPut(url) { AtomicInteger(0) }.incrementAndGet()
            if (delayMs > 0) delay(delayMs)
            if (failing) error("simulated failure for $url")
            bodies[url] ?: error("no body stubbed for $url")
        }

        fun callCount(url: String) = calls[url]?.get() ?: 0
        fun totalCalls() = calls.values.sumOf { it.get() }
    }

    private fun mainUrl(repoPath: String = "someowner/somerepo") =
        "https://raw.githubusercontent.com/$repoPath/main/CHANGELOG.md"

    private fun devUrl(repoPath: String = "someowner/somerepo") =
        "https://raw.githubusercontent.com/$repoPath/dev/CHANGELOG.md"

    // ─── Basic fetch / selection ─────────────────────────────────────────────

    @Test
    fun `loads and parses the source's changelog`() = runBlocking {
        val fetcher = FakeFetcher(mapOf(mainUrl() to changelogBody))
        val repo = ChangelogRepository(fetcher.fn)

        val load = repo.load(source().changelogRequest())

        assertTrue(load is ChangelogLoad.Entries)
        assertEquals(listOf("1.1.0"), (load as ChangelogLoad.Entries).entries.map { it.version })
        assertFalse(load.stale)
    }

    @Test
    fun `a local source has no changelog`() = runBlocking {
        val repo = ChangelogRepository(FakeFetcher(emptyMap()).fn)
        val local = PatchSource(id = "local", name = "local", type = PatchSourceType.LOCAL)

        assertEquals(ChangelogLoad.Unavailable, repo.load(local.changelogRequest()))
    }

    @Test
    fun `an unparseable body is unavailable, not an empty entries list`() = runBlocking {
        val fetcher = FakeFetcher(mapOf(mainUrl() to "not a changelog at all"))
        val repo = ChangelogRepository(fetcher.fn)

        assertEquals(ChangelogLoad.Unavailable, repo.load(source().changelogRequest()))
        // Nothing usable was cached, so a second call tries again rather than
        // permanently remembering "empty" for this source
        repo.load(source().changelogRequest())
        assertEquals(2, fetcher.callCount(mainUrl()))
    }

    @Test
    fun `a baseline already at the newest release shows nothing new`() = runBlocking {
        val fetcher = FakeFetcher(mapOf(mainUrl() to changelogBody))
        val repo = ChangelogRepository(fetcher.fn)

        val load = repo.load(source().changelogRequest(sinceVersion = "1.1.0")) as ChangelogLoad.Entries

        assertTrue(load.entries.isEmpty(), "the latest release must never be shown as \"new\" against itself")
    }

    @Test
    fun `an unusable baseline behaves as no baseline at all`() = runBlocking {
        val fetcher = FakeFetcher(mapOf(mainUrl() to changelogBody))
        val repo = ChangelogRepository(fetcher.fn)

        for (baseline in listOf(null, "", "unknown", "UNKNOWN")) {
            val load = repo.load(source().changelogRequest(sinceVersion = baseline)) as ChangelogLoad.Entries
            assertEquals(listOf("1.1.0"), load.entries.map { it.version }, "baseline=$baseline")
        }
    }

    // ─── Caching ──────────────────────────────────────────────────────────────

    @Test
    fun `a second call within the TTL reads from cache rather than refetching`() = runBlocking {
        val fetcher = FakeFetcher(mapOf(mainUrl() to changelogBody))
        val repo = ChangelogRepository(fetcher.fn)

        repo.load(source().changelogRequest())
        repo.load(source().changelogRequest())

        assertEquals(1, fetcher.callCount(mainUrl()))
    }

    @Test
    fun `a call after the TTL expires refetches`() = runBlocking {
        val fetcher = FakeFetcher(mapOf(mainUrl() to changelogBody))
        val now = AtomicLong(0L)
        val repo = ChangelogRepository(fetcher.fn, clock = { now.get() }, ttlMs = 1_000L)

        repo.load(source().changelogRequest())
        now.addAndGet(1_001L)
        repo.load(source().changelogRequest())

        assertEquals(2, fetcher.callCount(mainUrl()))
    }

    @Test
    fun `stable and prerelease channels of the same source cache independently`() = runBlocking {
        val fetcher = FakeFetcher(mapOf(mainUrl() to changelogBody, devUrl() to changelogBody))
        val repo = ChangelogRepository(fetcher.fn)

        repo.load(source(prerelease = false).changelogRequest())
        repo.load(source(prerelease = true).changelogRequest())

        assertEquals(1, fetcher.callCount(mainUrl()))
        assertEquals(1, fetcher.callCount(devUrl()))
    }

    // ─── Concurrency ────────────────────────────────────────────────────────

    @Test
    fun `concurrent requests for the same source share one fetch`() = runBlocking {
        val fetcher = FakeFetcher(mapOf(mainUrl() to changelogBody)).apply { delayMs = 30 }
        val repo = ChangelogRepository(fetcher.fn)
        val request = source().changelogRequest()

        val results = listOf(
            async { repo.load(request) },
            async { repo.load(request) },
        ).awaitAll()

        assertEquals(1, fetcher.callCount(mainUrl()), "two concurrent requests for one URL should share a single fetch")
        assertTrue(results.all { (it as? ChangelogLoad.Entries)?.entries?.map { e -> e.version } == listOf("1.1.0") })
    }

    @Test
    fun `different sources fetch concurrently rather than serializing through one lock`() = runBlocking {
        val urlA = mainUrl("owner/repo-a")
        val urlB = mainUrl("owner/repo-b")
        val concurrentInFlight = AtomicInteger(0)
        val maxObservedConcurrency = AtomicInteger(0)
        val fetcher = FakeFetcher(mapOf(urlA to changelogBody, urlB to changelogBody))
        val gatedFetch: suspend (String) -> String = { url ->
            val current = concurrentInFlight.incrementAndGet()
            maxObservedConcurrency.updateAndGet { maxOf(it, current) }
            delay(30)
            concurrentInFlight.decrementAndGet()
            fetcher.fn(url)
        }
        val repo = ChangelogRepository(gatedFetch)

        listOf(
            async { repo.load(source(id = "a", url = "https://github.com/owner/repo-a").changelogRequest()) },
            async { repo.load(source(id = "b", url = "https://github.com/owner/repo-b").changelogRequest()) },
        ).awaitAll()

        assertEquals(
            2,
            maxObservedConcurrency.get(),
            "two different sources should be able to fetch at the same time, not queue behind one lock",
        )
    }

    // ─── Failure / staleness ────────────────────────────────────────────────

    @Test
    fun `a failed refresh after the TTL falls back to the previously cached entries, marked stale`() = runBlocking {
        val fetcher = FakeFetcher(mapOf(mainUrl() to changelogBody))
        val now = AtomicLong(0L)
        val repo = ChangelogRepository(fetcher.fn, clock = { now.get() }, ttlMs = 1_000L)
        val request = source().changelogRequest()

        val first = repo.load(request) as ChangelogLoad.Entries
        assertFalse(first.stale)

        now.addAndGet(1_001L) // expire the cache
        fetcher.failing = true
        val afterFailure = repo.load(request) as ChangelogLoad.Entries

        assertEquals(listOf("1.1.0"), afterFailure.entries.map { it.version })
        assertTrue(afterFailure.stale)
    }

    @Test
    fun `a failed fetch with nothing cached is reported as failed`() = runBlocking {
        val fetcher = FakeFetcher(mapOf(mainUrl() to changelogBody)).apply { failing = true }
        val repo = ChangelogRepository(fetcher.fn)

        assertTrue(repo.load(source().changelogRequest()) is ChangelogLoad.Failed)
    }

    // ─── Invalidation ───────────────────────────────────────────────────────

    @Test
    fun `invalidate drops the cache so the next call refetches`() = runBlocking {
        val fetcher = FakeFetcher(mapOf(mainUrl() to changelogBody))
        val repo = ChangelogRepository(fetcher.fn)
        val src = source()

        repo.load(src.changelogRequest())
        repo.invalidate(src)
        repo.load(src.changelogRequest())

        assertEquals(2, fetcher.callCount(mainUrl()))
    }

    @Test
    fun `invalidating one source does not affect another`() = runBlocking {
        val urlA = mainUrl("owner/repo-a")
        val urlB = mainUrl("owner/repo-b")
        val fetcher = FakeFetcher(mapOf(urlA to changelogBody, urlB to changelogBody))
        val repo = ChangelogRepository(fetcher.fn)
        val a = source(id = "a", url = "https://github.com/owner/repo-a")
        val b = source(id = "b", url = "https://github.com/owner/repo-b")

        repo.load(a.changelogRequest())
        repo.load(b.changelogRequest())
        repo.invalidate(a)
        repo.load(a.changelogRequest()) // refetches
        repo.load(b.changelogRequest()) // still cached

        assertEquals(2, fetcher.callCount(urlA))
        assertEquals(1, fetcher.callCount(urlB))
    }

    @Test
    fun `invalidate with dropStale removes the stale fallback entirely`() = runBlocking {
        val fetcher = FakeFetcher(mapOf(mainUrl() to changelogBody))
        val repo = ChangelogRepository(fetcher.fn)
        val src = source()

        repo.load(src.changelogRequest()) // populates the cache
        repo.invalidate(src, dropStale = true)
        fetcher.failing = true

        assertTrue(repo.load(src.changelogRequest()) is ChangelogLoad.Failed)
    }

    @Test
    fun `invalidateAll drops every source`() = runBlocking {
        val urlA = mainUrl("owner/repo-a")
        val urlB = mainUrl("owner/repo-b")
        val fetcher = FakeFetcher(mapOf(urlA to changelogBody, urlB to changelogBody))
        val repo = ChangelogRepository(fetcher.fn)
        val a = source(id = "a", url = "https://github.com/owner/repo-a")
        val b = source(id = "b", url = "https://github.com/owner/repo-b")

        repo.load(a.changelogRequest())
        repo.load(b.changelogRequest())
        repo.invalidateAll()
        repo.load(a.changelogRequest())
        repo.load(b.changelogRequest())

        assertEquals(2, fetcher.callCount(urlA))
        assertEquals(2, fetcher.callCount(urlB))
    }

    // ─── App-scoped relevance (the update badge question) ──────────────────

    @Test
    fun `hasRelevantChanges is true with no app names to check`() = runBlocking {
        val repo = ChangelogRepository(FakeFetcher(emptyMap()).fn)
        val request = source().changelogRequest(sinceVersion = "1.0.0")

        assertTrue(repo.hasRelevantChanges(request))
    }

    @Test
    fun `hasRelevantChanges fails open when the changelog can't be read`() = runBlocking {
        val fetcher = FakeFetcher(mapOf(mainUrl() to changelogBody)).apply { failing = true }
        val repo = ChangelogRepository(fetcher.fn)
        val request = source().changelogRequest(sinceVersion = "1.0.0", appNames = setOf("Reddit"))

        assertTrue(repo.hasRelevantChanges(request), "an unreadable changelog must never suppress a real badge")
    }

    @Test
    fun `hasRelevantChanges reflects the app's own scoped bullets`() = runBlocking {
        val fetcher = FakeFetcher(mapOf(mainUrl() to changelogBody))
        val repo = ChangelogRepository(fetcher.fn)

        assertTrue(repo.hasRelevantChanges(source().changelogRequest(sinceVersion = "1.0.0", appNames = setOf("Reddit"))))
        assertFalse(repo.hasRelevantChanges(source().changelogRequest(sinceVersion = "1.0.0", appNames = setOf("Instagram"))))
        // Nothing newer than the baseline at all
        assertFalse(repo.hasRelevantChanges(source().changelogRequest(sinceVersion = "1.1.0", appNames = setOf("Reddit"))))
    }

    // ─── Older history ──────────────────────────────────────────────────────

    private val multiVersionBody = """
        ## [2.0.0-dev.1](https://x/compare/v1.2.0...v2.0.0-dev.1) (2026-09-10)

        * **Reddit:** dev change

        ## [1.2.0](https://x/compare/v1.1.0...v1.2.0) (2026-09-01)

        * **Reddit:** newest stable change

        ## [1.1.0](https://x/compare/v1.0.0...v1.1.0) (2026-08-01)

        * **Reddit:** older stable change

        ## [1.0.0](https://x/compare/v0.9.0...v1.0.0) (2026-07-01)

        * **Reddit:** oldest stable change
    """.trimIndent()

    @Test
    fun `loadOlder excludes what is already shown and any prerelease entries`() = runBlocking {
        val fetcher = FakeFetcher(mapOf(mainUrl() to multiVersionBody))
        val repo = ChangelogRepository(fetcher.fn)
        val request = source().changelogRequest()

        val older = repo.loadOlder(request, shownVersions = setOf("1.2.0")) as ChangelogLoad.Entries

        assertEquals(listOf("1.1.0", "1.0.0"), older.entries.map { it.version })
    }

    @Test
    fun `loadOlder for a prerelease source reads the stable branch separately from the dev view`() = runBlocking {
        val fetcher = FakeFetcher(mapOf(mainUrl() to multiVersionBody, devUrl() to multiVersionBody))
        val repo = ChangelogRepository(fetcher.fn)
        val request = source(prerelease = true).changelogRequest()

        repo.load(request) // dev branch — "recent" slot
        repo.loadOlder(request, shownVersions = emptySet()) // stable branch — a distinct slot and URL
        repo.loadOlder(request, shownVersions = emptySet()) // reuses that slot, no second stable fetch

        assertEquals(1, fetcher.callCount(devUrl()))
        assertEquals(1, fetcher.callCount(mainUrl()))
    }

    @Test
    fun `for a stable-channel source, loadOlder reuses the same full history load already fetched`() = runBlocking {
        val fetcher = FakeFetcher(mapOf(mainUrl() to multiVersionBody))
        val repo = ChangelogRepository(fetcher.fn)
        val request = source(prerelease = false).changelogRequest()

        // A stable source's "recent" view already needs its complete history (to compute
        // what's newer than the current version), so it and "older" share the one fetch.
        repo.load(request)
        repo.loadOlder(request, shownVersions = emptySet())

        assertEquals(1, fetcher.callCount(mainUrl()))
    }

    // ─── End-to-end URL construction (real HttpClient) ──────────────────────

    @Test
    fun `fetches over a real HttpClient using the provider's raw-file URL`() = runBlocking {
        var requestedUrl: String? = null
        val engine = MockEngine { request ->
            requestedUrl = request.url.toString()
            respond(changelogBody, HttpStatusCode.OK, headersOf("Content-Type", "text/plain"))
        }
        val repo = ChangelogRepository(HttpClient(engine))

        val load = repo.load(source().changelogRequest()) as ChangelogLoad.Entries

        assertEquals(listOf("1.1.0"), load.entries.map { it.version })
        assertEquals(mainUrl(), requestedUrl)
    }
}
