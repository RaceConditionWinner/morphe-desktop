/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.gui.data.repository

import app.morphe.engine.MorpheData
import app.morphe.engine.network.HttpService
import app.morphe.gui.data.constants.AppConstants
import app.morphe.gui.util.Logger
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.util.Locale

/**
 * Remote blocklist of patch sources, cached to disk so an offline launch
 * still enforces the last known state. Entries are keyed by
 * `provider=owner/repo` (lower-case) and carry an optional reason for
 * display.
 *
 * Ported from morphe-manager's `BlocklistRepository`
 * (`domain/repository/BlocklistRepository.kt`, commit `8f1494ef`, "Route
 * add-source through the website and enforce a remote blocklist"). Behavior
 * is preserved exactly: same endpoint shape
 * (`{MORPHE_API_URL}/v2/blocked-sources`), same key format
 * (`provider=owner/repo`, lower-cased), same "keep the previous value on a
 * failed refresh" semantics — a network hiccup must never silently unblock
 * a source that was blocked a minute ago.
 *
 * Two desktop-appropriate adaptations, not behavioral changes:
 *  - Manager persists the cache via a DataStore-backed preference
 *    (`PreferencesManager.blocklistCache`). Desktop has no DataStore, so
 *    this writes a small JSON file at [MorpheData.blocklistCacheFile]
 *    instead — the same pattern [ConfigRepository] already uses for
 *    `config.json`.
 *  - The refresh call goes through this project's [HttpService] (shared
 *    retry + host-circuit-breaker layer, see `engine/network/`) instead of
 *    a bare `HttpClient.get()`, so a transient failure against the Morphe
 *    API gets the same retry treatment every other network call in this
 *    codebase gets, rather than giving up on the very first blip.
 */
class BlocklistRepository(
    httpClient: HttpClient,
    private val json: Json,
) {
    private val http = HttpService(httpClient, json)

    private val _entries = MutableStateFlow<Map<String, BlockedEntry>>(emptyMap())
    val entries: StateFlow<Map<String, BlockedEntry>> = _entries.asStateFlow()

    private var loadedFromCache = false

    /**
     * Loads the last cached blocklist from disk, if any. Cheap and
     * side-effect-free beyond the flow update — safe to call once at
     * startup before the first [refresh] completes, so "add source" is
     * gated even before a network round trip finishes.
     */
    suspend fun loadFromCache() = withContext(Dispatchers.IO) {
        if (loadedFromCache) return@withContext
        loadedFromCache = true
        val file = MorpheData.blocklistCacheFile
        if (!file.exists()) return@withContext
        _entries.value = decode(runCatching { file.readText() }.getOrDefault(""))
    }

    /** Refreshes from the network. Keeps the previous value on failure so offline callers keep working. */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        val response = try {
            http.request<BlocklistResponse>("${AppConstants.MORPHE_API_URL}/v2/blocked-sources")
        } catch (e: Exception) {
            Logger.debug("BlocklistRepository: refresh failed, keeping previous state (${e.message})")
            return@withContext
        }
        val fresh = response.blocked
            .asSequence()
            .filter { it.provider.isNotBlank() && it.repo.isNotBlank() }
            .associateBy { key(it.provider, it.repo) }
        _entries.value = fresh
        runCatching {
            val file = MorpheData.blocklistCacheFile
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(fresh.values.toList()))
        }.onFailure { Logger.warn("BlocklistRepository: failed to persist cache (${it.message})") }
    }

    /** Whether [key] (as produced by [toBlocklistKey]) is currently on the blocklist. */
    fun isBlocked(key: String): Boolean = _entries.value.containsKey(key.lowercase(Locale.US))

    /**
     * Returns the blocklist key for a GitHub/GitLab bundle URL, or null for
     * any other host (local `.mpp` files and unrecognized hosts are never
     * blockable by definition — there is nothing remote to have blocked).
     */
    fun toBlocklistKey(url: String): String? = try {
        val parsed = URI(url)
        val segments = (parsed.path ?: "").trim('/').split('/').filter { it.isNotBlank() }
        val host = parsed.host?.lowercase(Locale.US)
        when {
            segments.size < 2 -> null
            host == "raw.githubusercontent.com" || host == "github.com" ->
                "github=${segments[0]}/${segments[1]}".lowercase(Locale.US)
            host == "gitlab.com" ->
                "gitlab=${segments[0]}/${segments[1]}".lowercase(Locale.US)
            else -> null
        }
    } catch (_: Exception) {
        null
    }

    private fun decode(cached: String): Map<String, BlockedEntry> {
        if (cached.isBlank()) return emptyMap()
        return try {
            json.decodeFromString<List<BlockedEntry>>(cached)
                .filter { it.provider.isNotBlank() && it.repo.isNotBlank() }
                .associateBy { key(it.provider, it.repo) }
        } catch (e: Exception) {
            Logger.warn("BlocklistRepository: failed to decode cached blocklist (${e.message})")
            emptyMap()
        }
    }

    private fun key(provider: String, repo: String): String = "$provider=$repo".lowercase(Locale.US)

    @Serializable
    private data class BlocklistResponse(
        val version: Int = 1,
        val blocked: List<BlockedEntry> = emptyList(),
    )

    @Serializable
    data class BlockedEntry(
        val provider: String,
        val repo: String,
        val reason: String? = null,
    )
}
