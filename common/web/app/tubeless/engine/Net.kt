package app.tubeless.engine

import app.tubeless.web.jsFetch
import app.tubeless.web.toByteArray

/**
 * The one HTTP client of the extension: the browser's fetch (extension/app-src/tubeless.js), from an
 * extension page whose host_permissions cover YouTube's hosts, so there is no CORS. The JS side:
 * - sends the user's youtube.com cookies only with a signed-in call (one carrying SAPISIDHASH from
 *   InnerTube.authHeaders), every other request is anonymous (credentials "omit")
 * - sets what a page can't (User-Agent) through declarativeNetRequest session rules that match
 *   only this extension's own requests; Origin and Referer for youtube.com are set the same way
 */
object Net {
    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): String {
        val text = call("GET", url, null, headers)
        return if (Vtt.isCaption(url)) Vtt.clean(text) else text
    }

    suspend fun post(url: String, body: String, headers: Map<String, String> = emptyMap()): String = call("POST", url, body, headers)

    /** any status comes back with its body; only a failed connection throws */
    suspend fun raw(method: String, url: String, headers: Map<String, String>, body: String?): Pair<Int, String> {
        val r = wrap { jsFetch(method.uppercase(), url, headers, body, false) }
        return r.status to (r.text ?: "")
    }

    /** a GET whose answer is bytes (a picture, a small range); only a failed connection throws */
    suspend fun bytes(url: String, headers: Map<String, String>): Pair<Int, ByteArray> {
        val r = wrap { jsFetch("GET", url, headers, null, true) }
        return r.status to (r.bytes?.toByteArray() ?: ByteArray(0))
    }

    private suspend fun call(method: String, url: String, body: String?, headers: Map<String, String>): String {
        val h = buildMap {
            ServerVideo.authFor(url)?.takeIf { headers.keys.none { it.equals("Authorization", true) } }?.let { put("Authorization", it) }
            putAll(headers.filter { it.value.isNotEmpty() && it.key != InnerTube.SESSION_MARK })
            if (body != null && keys.none { it.equals("Content-Type", true) }) put("Content-Type", "application/json")
        }
        val r = wrap { jsFetch(method, url, h, body, false) }
        if (r.status !in 200..299) throw HttpError(r.status, "HTTP ${r.status}")
        return r.text ?: ""
    }

    private inline fun <T> wrap(block: () -> T): T = try { block() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) {
        throw okio.IOException(e.message ?: "network error")
    }
}
