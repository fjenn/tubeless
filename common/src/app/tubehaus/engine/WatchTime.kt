package app.tubehaus.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * Signed-in watch-time pings, so what is watched here lands in the account's YouTube history with a
 * resume bar, and keeps its recommendations learning (MECHANICS §4.2). The streams themselves stay
 * anonymous; this asks the signed-in WEB `player` only for its `playbackTracking` URLs, then:
 *  - `videostatsPlaybackUrl` once when the video starts,
 *  - `videostatsWatchtimeUrl` every 10 s while it plays (and once more on pause and at the end),
 * each with `ver=2`, `cpn` (16-char playback nonce), `cmt` (position), `st`/`et` (the segment watched
 * since the last ping), `len`, `state`, `rt`/`rtn` (seconds since start). Driven by Playback's hooks
 * (AppState wires them): [start], [tick], [end]. Nothing is sent signed out.
 */
object WatchTime {
    const val INTERVAL_MS = 10_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private const val CPN_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_"

    private class Session(val id: String, val cpn: String, val startedAt: Long) {
        var urls: Pair<String, String>? = null
        var len = 0.0
        var segStart = 0.0
        var lastPos = 0.0
        var lastPing = 0L
        var playing = false
        var started = false
        var rtn = 0
    }

    private var cur: Session? = null

    /**
     * Instant mode (the website without the extension): the pings go without cookies (the browser sends them as
     * no-cors requests), and their URLs come from the player answer that gave the streams, since the WEB player
     * answers such a page "unplayable" without them.
     */
    private val cookieless get() = Accounts.cookielessVisitor
    private val known = LinkedHashMap<String, Pair<String, String>>()

    /** a player answer with streams for [id] (YouTube.parse): its tracking URLs, kept for Instant mode */
    fun seen(id: String, d: kotlinx.serialization.json.JsonElement) {
        if (!cookieless) return
        AccountParse.tracking(d)?.let { known[id] = it; if (known.size > 30) known.remove(known.keys.first()) }
    }

    /** a [cpn]: 16 random characters of the URL-safe alphabet */
    fun cpn(rnd: Random = Random): String = buildString { repeat(16) { append(CPN_CHARS[rnd.nextInt(64)]) } }

    /** The ping URL: [base] plus the playback fields (any of them already in [base] are replaced). */
    fun url(base: String, cpn: String, cmt: Double, st: Double, et: Double, len: Double, state: String?, rt: Double, rtn: Int, final: Boolean = false): String {
        val drop = setOf("ver", "cpn", "cmt", "st", "et", "len", "state", "rt", "rtn", "final", "c", "cver")
        // the surface YouTube put in the URL stays (el=shortspage for a Short); detailpage when there is none
        val el = if (Regex("(^|&)el=").containsMatchIn(base.substringAfter('?', ""))) null else "detailpage"
        val (path, query) = base.substringBefore('?') to base.substringAfter('?', "")
        val kept = query.split('&').filter { it.isNotEmpty() && it.substringBefore('=') !in drop }
        fun f(x: Double): String { val ms = (x * 1000).toLong(); return if (ms % 1000 == 0L) (ms / 1000).toString() else (ms / 1000.0).toString() }
        val add = buildList {
            add("ver=2"); add("cpn=$cpn"); if (el != null) add("el=$el")
            add("c=WEB"); add("cver=${Clients.WEB.version}")
            add("cmt=${f(cmt)}"); add("st=${f(st)}"); add("et=${f(et)}"); add("len=${f(len)}")
            if (state != null) add("state=$state")
            add("rt=${f(rt)}"); add("rtn=$rtn")
            if (final) add("final=1")
        }
        return path + "?" + (kept + add).joinToString("&")
    }

    /**
     * A video started playing ([lenSec] when known). Signed in or not: signed out the pings carry the
     * visitor session's cookies, as a logged-out browser's do, so YouTube's signed-out suggestions learn too.
     * [params]: the reel's playerParams for a Short (YouTube then marks the pings el=shortspage).
     */
    fun start(videoId: String, lenSec: Double, params: String? = null) {
        if (!cookieless && !YouTubeAccount.signedIn && !Accounts.visitorSession) { cur = null; return }
        if (cur?.id == videoId) return
        cur?.let { end(it.id) }
        val s = Session(videoId, cpn(), Sys.nowMs()).also { it.len = lenSec }
        cur = s
        scope.launch {
            val urls = (if (cookieless) known[videoId] else null) ?: try {
                AccountParse.tracking(InnerTube.call("player", mapOf(
                    "videoId" to videoId,
                    "cpn" to s.cpn,
                    "params" to params,
                    "contentCheckOk" to true,
                    "racyCheckOk" to true,
                    "playbackContext" to kotlinx.serialization.json.buildJsonObject {
                        put("contentPlaybackContext", kotlinx.serialization.json.buildJsonObject {
                            put("html5Preference", kotlinx.serialization.json.JsonPrimitive("HTML5_PREF_WANTS"))
                        })
                    },
                )))
            } catch (e: Exception) { null } ?: return@launch
            s.urls = urls
            if (cur !== s) return@launch
            s.started = true
            get(url(urls.first, s.cpn, s.lastPos, s.lastPos, s.lastPos, s.len, null, rt(s), s.rtn++))
        }
    }

    private fun rt(s: Session) = (Sys.nowMs() - s.startedAt) / 1000.0

    /** every player state change of the current video: position, playing or not */
    fun tick(videoId: String, posSec: Double, lenSec: Double, playing: Boolean) {
        val s = cur?.takeIf { it.id == videoId } ?: return
        if (lenSec > 0) s.len = lenSec
        val now = Sys.nowMs()
        // a seek starts a new segment
        if (kotlin.math.abs(posSec - s.lastPos) > 15 && s.playing) { flush(s, "playing"); s.segStart = posSec }
        if (!s.playing && playing) { s.segStart = posSec; s.lastPing = now }
        val paused = s.playing && !playing
        s.lastPos = posSec
        s.playing = playing
        when {
            paused -> flush(s, "paused")
            playing && now - s.lastPing >= INTERVAL_MS -> flush(s, "playing")
        }
    }

    /** the video ended or another one was opened */
    fun end(videoId: String) {
        val s = cur?.takeIf { it.id == videoId } ?: return
        flush(s, "paused", final = true)
        cur = null
    }

    /** ends whatever session is open (history switched off) */
    fun stop() { cur?.let { end(it.id) } }

    private fun flush(s: Session, state: String, final: Boolean = false) {
        val urls = s.urls ?: return
        if (!s.started) return
        val st = s.segStart; val et = s.lastPos
        s.segStart = et
        s.lastPing = Sys.nowMs()
        get(url(urls.second, s.cpn, et, st, et, s.len, state, rt(s), s.rtn++, final))
    }

    /** pings sent (for checks); the last few */
    val sent = ArrayList<String>()

    private fun get(u: String) {
        sent += u.substringAfter('?'); if (sent.size > 20) sent.removeAt(0)
        scope.launch {
            if (cookieless) { runCatching { Net.get(u) }; return@launch }
            try {
                // the web player's pings carry the session's cookies only (no Authorization), as a browser's fetch does
                val cookie = InnerTube.authHeaders?.invoke("https://www.youtube.com")?.get("Cookie") ?: Accounts.visitorCookie() ?: return@launch
                val h = mapOf("Cookie" to cookie, "User-Agent" to Clients.WEB.userAgent, "Referer" to "https://www.youtube.com/",
                    "Origin" to "https://www.youtube.com", InnerTube.SESSION_MARK to "1")
                Net.get(u, h)
            } catch (_: Exception) {}
        }
    }
}
