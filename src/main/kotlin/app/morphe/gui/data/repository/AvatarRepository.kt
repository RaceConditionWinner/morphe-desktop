/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.gui.data.repository

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import app.morphe.engine.MorpheData
import app.morphe.engine.network.HttpService
import app.morphe.gui.data.model.PatchSource
import app.morphe.gui.data.model.PatchSourceType
import app.morphe.gui.util.Logger
import io.ktor.client.HttpClient
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image

/**
 * Resolves a [PatchSource] to an owner avatar and loads it off the UI thread, with a
 * two-tier (memory + disk) cache and a failure backoff so a broken URL is never retried
 * every recomposition. Designed to be called from a `LaunchedEffect`, never from a
 * composable body directly (see [SourceAvatar][app.morphe.gui.ui.components.SourceAvatar]).
 *
 * The actual download reuses [HttpService.downloadToFile] — the same retrying,
 * atomic-write downloader every `.mpp` already goes through — instead of hand-rolling a
 * second HTTP path, so an avatar fetch gets the same transient-failure/429 handling for
 * free and always lands through one atomic file swap.
 *
 * Only GitHub and GitLab sources have a network avatar. The built-in default source uses
 * the bundled Morphe wordmark, and local sources have no "owner" to fetch an avatar for
 * — both are handled entirely in the UI layer via deterministic fallbacks, so this
 * repository is never even consulted for them (see [avatarUrl]).
 */
class AvatarRepository(httpClient: HttpClient) {

    private val http = HttpService(httpClient)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val memoryCache = ConcurrentHashMap<String, ImageBitmap>()
    private val inFlight = ConcurrentHashMap<String, Deferred<ImageBitmap?>>()

    /** url → epoch millis of the last failed attempt. Skipped on retry within [FAILURE_BACKOFF_MS]. */
    private val recentFailures = ConcurrentHashMap<String, Long>()

    /**
     * Owner-avatar URL for [source], or null when the source has no fetchable avatar
     * (default/local sources, or a URL that doesn't parse to an owner).
     *
     * GitHub serves a stable, unauthenticated redirect at `github.com/{owner}.png` —
     * no API call, no rate limit. GitLab has no equivalent first-party shortcut (its
     * avatar lives behind the Users API and needs a resolved user/group id), so this
     * uses unavatar.io's public GitLab proxy instead, the same source morphe-manager
     * already relies on for the same purpose. Both are single, cacheable image GETs.
     */
    fun avatarUrl(source: PatchSource): String? {
        val url = source.url?.takeIf { it.isNotBlank() } ?: return null
        val owner = url
            .removePrefix("https://").removePrefix("http://")
            .removePrefix("github.com/").removePrefix("gitlab.com/")
            .substringBefore('/')
            .takeIf { it.isNotBlank() } ?: return null
        return when (source.type) {
            PatchSourceType.GITHUB -> "https://github.com/$owner.png?size=96"
            PatchSourceType.GITLAB -> "https://unavatar.io/gitlab/$owner"
            PatchSourceType.DEFAULT, PatchSourceType.LOCAL -> null
        }
    }

    /**
     * Load the avatar for [source], or null if unavailable (no avatar URL, offline,
     * 404, malformed image, or a recent failure still within backoff). Safe to call
     * repeatedly/concurrently for the same source — concurrent callers share one
     * in-flight fetch, and a resolved result is served from memory thereafter.
     */
    suspend fun load(source: PatchSource): ImageBitmap? {
        val url = avatarUrl(source) ?: return null
        memoryCache[url]?.let { return it }

        val lastFailure = recentFailures[url]
        if (lastFailure != null && System.currentTimeMillis() - lastFailure < FAILURE_BACKOFF_MS) {
            return null
        }

        // computeIfAbsent + await outside the lambda: two concurrent cards for the
        // same owner (e.g. two sources in the same repo) share one fetch instead of
        // racing two downloads. Bookkeeping (memory cache / failure backoff / map
        // cleanup) hangs off the job's own completion, not off some caller reaching a
        // line after await() — if every card watching this url gets recomposed away
        // before the fetch finishes, the fetch still finishes cleanly and still
        // records its result; nothing is left orphaned in `inFlight`.
        val deferred = inFlight.computeIfAbsent(url) {
            scope.async { fetchAndDecode(url) }.also { job ->
                job.invokeOnCompletion { cause ->
                    inFlight.remove(url, job)
                    val result = if (cause == null) job.getCompleted() else null
                    if (result != null) {
                        memoryCache[url] = result
                        recentFailures.remove(url)
                    } else {
                        recentFailures[url] = System.currentTimeMillis()
                    }
                }
            }
        }
        return deferred.await()
    }

    private suspend fun fetchAndDecode(url: String): ImageBitmap? = withContext(Dispatchers.IO) {
        val cacheFile = diskCacheFile(url) ?: return@withContext null
        val isFresh = cacheFile.exists() &&
            System.currentTimeMillis() - cacheFile.lastModified() < DISK_CACHE_MAX_AGE_MS

        if (!isFresh) {
            // maxBytes is enforced during the download itself (Content-Length check +
            // running streaming total in HttpService), not after the fact — an
            // oversized response is aborted mid-stream and never fully written to
            // disk in the first place.
            val downloaded = runCatching {
                http.downloadToFile(url, cacheFile, maxBytes = MAX_AVATAR_BYTES)
            }
            if (downloaded.isFailure) {
                Logger.debug("Avatar fetch failed for $url: ${downloaded.exceptionOrNull()?.message}")
                if (!cacheFile.exists()) return@withContext null
                // Stale-but-present beats nothing: fall through and decode what we
                // already had cached rather than treat a transient offline blip (or a
                // response that turned out too large) as "this source has no avatar."
            }
        }

        decode(cacheFile).also {
            if (it == null) {
                Logger.debug("Avatar at $url decoded to nothing, treating as unavailable")
                cacheFile.delete()
            }
        }
    }

    private fun decode(file: File): ImageBitmap? =
        runCatching { Image.makeFromEncoded(file.readBytes()).toComposeImageBitmap() }.getOrNull()

    private fun diskCacheFile(url: String): File? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
        val name = digest.joinToString("") { "%02x".format(it) }
        File(MorpheData.avatarsDir, "$name.avatar")
    }.getOrNull()

    private companion object {
        const val MAX_AVATAR_BYTES = 2L * 1024 * 1024
        const val FAILURE_BACKOFF_MS = 10 * 60 * 1000L
        const val DISK_CACHE_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000L
    }
}
