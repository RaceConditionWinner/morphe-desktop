/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.gui.data.repository

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Covers the two behaviors ported from morphe-manager's `BlocklistRepository`:
 * key derivation for GitHub/GitLab bundle URLs, and "keep the previous value
 * on a failed refresh" so a transient network blip never silently unblocks a
 * source. Uses [MockEngine] — no real network access.
 *
 * [BlocklistRepository.refresh] does write a small cache file under
 * [app.morphe.engine.MorpheData.blocklistCacheFile] as a side effect (wrapped
 * in `runCatching`, so it never fails the test) — these tests don't assert on
 * that file directly, matching how [HttpServiceTest] already exercises real
 * temp files rather than a mocked filesystem in this codebase.
 */
class BlocklistRepositoryTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `derives a github key from a github url`() {
        val repo = BlocklistRepository(HttpClient(MockEngine { respond("") }), json)
        assertEquals(
            "github=someowner/somerepo",
            repo.toBlocklistKey("https://github.com/SomeOwner/SomeRepo"),
        )
    }

    @Test
    fun `derives a gitlab key from a gitlab url`() {
        val repo = BlocklistRepository(HttpClient(MockEngine { respond("") }), json)
        assertEquals(
            "gitlab=someowner/somerepo",
            repo.toBlocklistKey("https://gitlab.com/SomeOwner/SomeRepo"),
        )
    }

    @Test
    fun `returns null for a host that isn't github or gitlab`() {
        val repo = BlocklistRepository(HttpClient(MockEngine { respond("") }), json)
        assertNull(repo.toBlocklistKey("https://example.test/owner/repo"))
    }

    @Test
    fun `returns null for a malformed url`() {
        val repo = BlocklistRepository(HttpClient(MockEngine { respond("") }), json)
        assertNull(repo.toBlocklistKey("not a url"))
    }

    @Test
    fun `refresh populates entries from the network response`() = runBlocking {
        val body = """{"version":1,"blocked":[{"provider":"github","repo":"bad/actor","reason":"malware"}]}"""
        val engine = MockEngine { respond(body, HttpStatusCode.OK, headersOf("Content-Type", "application/json")) }
        val repo = BlocklistRepository(HttpClient(engine), json)

        repo.refresh()

        assertTrue(repo.isBlocked("github=bad/actor"))
        assertFalse(repo.isBlocked("github=good/actor"))
    }

    @Test
    fun `a failed refresh keeps the previously known entries`() = runBlocking {
        val goodBody = """{"version":1,"blocked":[{"provider":"github","repo":"bad/actor"}]}"""
        var shouldFail = false
        val engine = MockEngine {
            if (shouldFail) respond("", HttpStatusCode.InternalServerError)
            else respond(goodBody, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }
        val repo = BlocklistRepository(HttpClient(engine), json)

        repo.refresh() // succeeds, populates the blocklist
        assertTrue(repo.isBlocked("github=bad/actor"))

        shouldFail = true
        repo.refresh() // fails after retries — must not clear what's already known

        assertTrue(repo.isBlocked("github=bad/actor"))
    }
}
