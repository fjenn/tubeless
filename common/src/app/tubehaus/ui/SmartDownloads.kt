package app.tubehaus.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.tubehaus.engine.Sys
import app.tubehaus.engine.Video
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * Smart downloads (S6): videos and Shorts you'd likely watch next are saved by themselves, so there
 * is always something to watch offline. As YouTube's, but with your settings instead of its caps:
 * - where they come from: signed in, the account's Watch later and Home; signed out, the on-device
 *   Watch later and the local recommender (rec/Recommend.kt). Shorts from the Shorts recommender.
 * - only on Wi-Fi (never on a metered connection), at Settings → Downloads → Smart downloads quality;
 * - how many and how much space they may take are settings (no limit by default);
 * - a smart download you watched to the end, or removed, makes room for a new one and isn't saved again.
 * Your own downloads are never touched by it.
 */
object SmartDownloads {
    /** what it did last, for the Downloads page */
    var status by mutableStateOf("")
        private set

    private var job: Job? = null
    private var hooked = false

    fun attach(app: AppState) {
        if (!app.platform.canDownload || app.platform.instant || job != null) return
        if (!hooked) {
            hooked = true
            // a smart download watched to the end has done its job: it goes at the next round
            Playback.onEnd += { _, v -> watched(app, v.id) }
        }
        job = app.scope.launch {
            delay(30_000)            // after the app has settled
            var lastSmart = app.offline.prefs.value.smart
            var waited = 0L
            while (true) {
                val p = app.offline.prefs.value
                if (p.smart) runCatching { once(app) }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                // every 30 minutes; switching it on starts a round within a few seconds
                waited = 0
                while (waited < 30 * 60_000L) {
                    delay(5_000); waited += 5_000
                    val now = app.offline.prefs.value.smart
                    if (now && !lastSmart) { lastSmart = true; break }
                    lastSmart = now
                }
            }
        }
    }

    private fun watched(app: AppState, id: String) {
        val s = app.offline.saved.value.items[id] ?: return
        if (!s.smart) return
        app.offline.prefs.update { it.copy(smartDone = (it.smartDone + id).takeLast(2000)) }
        app.offline.remove(id)
    }

    /** one round: make room, then fill up to the count */
    suspend fun once(app: AppState) {
        val off = app.offline
        val p = off.prefs.value
        if (!p.smart) return
        if (off.metered()) { status = tr("Waiting for Wi-Fi"); return }
        val saved = off.saved.value.items.values
        // what the smart ones may take
        var smartBytes = saved.filter { it.smart }.sumOf { it.size }
        val cap = if (p.smartCapMb > 0) p.smartCapMb shl 20 else Long.MAX_VALUE
        // the oldest smart ones go first when over the limit
        for (s in saved.filter { it.smart }.sortedBy { it.at }) {
            if (smartBytes <= cap) break
            off.remove(s.id); smartBytes -= s.size
        }
        val have = off.saved.value.items.values.count { it.smart } + off.pending.keys.count { off.queuedSmart(it) }
        val want = p.smartCount - have
        if (want <= 0) { status = tr("{n} smart downloads ready").replace("{n}", "$have"); return }
        if (smartBytes >= cap) { status = tr("Smart downloads have used their storage"); return }
        val watched = app.store.lib.value.hist.map { it.id }.toSet()
        val skip = watched + p.smartDone + off.saved.value.items.keys + off.pending.keys
        val picks = candidates(app, p.smartShorts, skip).take(want)
        for (v in picks) off.start(v, p.smartQuality, codec = p.codec, smart = true)
        status = if (picks.isEmpty()) tr("Nothing new to save right now")
            else tr("Saving {n} videos for you").replace("{n}", "${picks.size}")
        OfflineEvents.log("smart_round", "queued" to picks.size, "have" to have, "at" to Sys.nowMs())
    }

    /** Watch later first (you asked for those), then YouTube's Home for this session; a few Shorts mixed in */
    private suspend fun candidates(app: AppState, shorts: Boolean, skip: Set<String>): List<Video> {
        val src = app.feedSource
        val wl = runCatching { if (src.signedIn) src.page(FeedKind.WATCH_LATER)?.items else null }.getOrNull()
            ?: app.store.lib.value.wl.map { it.video() }
        val home = runCatching { app.rec.home(40, skip) }.getOrDefault(emptyList())
        val sh = if (!shorts || app.noShorts) emptyList() else
            (home.filter { it.short } + runCatching { app.rec.shorts(10, skip) }.getOrDefault(emptyList()))
        fun ok(v: Video) = v.id !in skip && !v.live && !v.members && v.id.length == 11
        val long = (wl + home).filter { ok(it) && !it.short }.distinctBy { it.id }
        val s = sh.filter { ok(it) }.distinctBy { it.id }
        // about one in four a Short, when there are Shorts
        val out = ArrayList<Video>()
        var i = 0; var j = 0
        while (i < long.size || j < s.size) {
            if (j < s.size && (out.size % 4 == 3 || i >= long.size)) out += s[j++] else if (i < long.size) out += long[i++]
        }
        return out
    }
}
