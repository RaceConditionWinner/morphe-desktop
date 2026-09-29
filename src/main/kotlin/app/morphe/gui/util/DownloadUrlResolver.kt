/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.gui.util

import app.morphe.gui.data.constants.AppConstants.MORPHE_API_URL
import io.ktor.http.encodeURLParameter
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object DownloadUrlResolver {

    fun getWebSearchDownloadLink(packageName: String, version: String, architecture: String? = null): String {
        val architectureString = architecture ?: "all"
        val query = "$packageName~$version~$architectureString".encodeURLParameter()
        return "$MORPHE_API_URL/v2/web-search/$query"
    }

    /**
     * Resolves [url]'s redirect chain off the calling thread, then calls [handleResolvedUrl]
     * back on whatever dispatcher the caller resumes on (Main, for every current caller,
     * since they all launch this from a Composable's own `rememberCoroutineScope()`).
     *
     * A plain function here used to open its own `CoroutineScope(Dispatchers.Main)` per
     * call and launch into it — a scope nothing ever held onto or could cancel, so
     * navigating away mid-resolution couldn't stop it, and every call allocated a new
     * Job for work the caller already has a properly-scoped, lifecycle-tied coroutine
     * scope to run this in instead.
     */
    suspend fun openUrlAndFollowRedirects(url: String, handleResolvedUrl: (String) -> Unit) {
        val result = withContext(Dispatchers.IO) {
            resolveRedirects(url)
        }
        handleResolvedUrl(result)
    }

    fun resolveRedirects(url: String, maxRedirectsToFollow : Int = 5): String {
        if (maxRedirectsToFollow <= 0) return url

        var connection: HttpURLConnection? = null
        try {
            val originalUrl = URI(url).toURL()
            connection = originalUrl.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.requestMethod = "HEAD"
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000

            val responseCode = connection.responseCode
            if (responseCode in 300..399) {
                val location = connection.getHeaderField("Location")

                if (location.isNullOrBlank()) {
                    Logger.info("Location tag is blank: ${connection.responseMessage}")
                    return url
                }

                val resolved =
                    if (location.startsWith("http://") || location.startsWith("https://")) {
                        location
                    } else {
                        val prefix = "${originalUrl.protocol}://${originalUrl.host}"
                        if (location.startsWith("/")) "$prefix$location" else "$prefix/$location"
                    }

                if (!resolved.startsWith(MORPHE_API_URL)) {
                    return resolved
                }

                return resolveRedirects(resolved, maxRedirectsToFollow - 1)
            }

            //Log.d("Unexpected response code: $responseCode")
        } catch (ex: SocketTimeoutException) {
            Logger.info("Timeout while resolving search redirect: $ex")
        } catch (ex: Exception) {
            Logger.info("Exception while resolving search redirect: $ex")
        } finally {
            // A HEAD request per URL, potentially several per resolution (redirect
            // chains recurse) and called once per source — never disconnecting
            // left every one of those connections to whatever the JVM's own
            // keep-alive cache timeout happens to be, rather than released
            // immediately once this function is done with it.
            connection?.disconnect()
        }

        return url
    }
}
