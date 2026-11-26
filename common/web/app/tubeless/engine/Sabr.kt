package app.tubeless.engine

import kotlinx.serialization.json.JsonElement

/**
 * SABR in the browser: the fallback for videos the token-free clients can't play. The JVM twin's
 * members; the work is in extension/src/tubeless.js (tubeless.sabr): common/sabr/sabr-page.js runs in a
 * hidden youtube.com frame, its pieces are kept there, and Shaka reads a format as sabr://<id>/<index>.
 * Captions that want a token go through it as sabrcap://<id>/<url>.
 */
object Sabr {
    @Volatile var host: SabrHost? = null
    /** unused here (no cache folder: the pieces stay in memory) */
    @Volatile var dir: String? = null
    @Volatile var only = false
    @Volatile var okAt = 0L
    @Volatile var first = false
    val available get() = host != null
    val warm get() = available && Sys.nowMs() - okAt < 30 * 60_000L
    fun hostGone() { cool() }
    fun cool() { okAt = 0 }

    private val sessions = HashMap<String, Pair<JsonElement, Long>>()

    suspend fun prepare(id: String): JsonElement? {
        val h = host ?: return null
        sessions[id]?.takeIf { Sys.nowMs() - it.second < 4 * 3600_000L }?.let { return it.first }
        val text = h.prepare(id, InnerTube.hl, InnerTube.gl) ?: return null
        val d = parse(text)
        if (d.at("playabilityStatus.status").str == "OK") okAt = Sys.nowMs()
        sessions[id] = d to Sys.nowMs()
        if (sessions.size > 40) sessions.entries.minByOrNull { it.value.second }?.let { sessions.remove(it.key) }
        return d
    }

    fun url(id: String, index: Int) = "sabr://$id/$index"
    fun captionUrl(id: String, url: String) = "sabrcap://$id/" + urlEncode(url)
    fun piece(run: Int, at: Long, init: Boolean, ms: Long, data: ByteArray) {}
    fun end(run: Int, bad: Boolean, error: String?) {}
}

internal object SabrLive {
    suspend fun master(id: String): String? = null
    fun hostGone() {}
}
