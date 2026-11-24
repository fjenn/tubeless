package app.tubehaus.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.tubehaus.engine.Caption
import app.tubehaus.engine.Net
import app.tubehaus.engine.Stream
import app.tubehaus.engine.Sys
import app.tubehaus.engine.Vtt
import app.tubehaus.engine.YouTube
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Premium's "Auto speed", on every platform: the speed follows how fast people speak. The captions'
 * words over the 40 seconds around the playing point give words per minute; slow, pausing speech
 * plays faster (up to 2x), quick speech at normal speed. Changes are gentle (0.05 steps, at most every
 * few seconds) and never while a press holds the video at 2x. Picking a speed by hand turns it off.
 */
object AutoSpeed {
    /** words per minute that play as they are; slower speech is brought up to about this */
    const val TARGET_WPM = 200f
    const val MAX = 2f

    private class Line(val start: Long, val end: Long, val words: Float)

    private var forId: String? = null
    private var lines: List<Line> = emptyList()
    private var job: Job? = null
    private var lastAt = 0L
    /** the speed auto speed set last (shown in the speed menu) */
    var now by mutableStateOf(1f)
        private set
    /** the words per minute it heard last; 0 when it has nothing to go on */
    var wpm by mutableStateOf(0f)
        private set

    val on get() = Perks.prefs.autoSpeed

    fun set(app: AppState, enable: Boolean) {
        Perks.update { it.copy(autoSpeed = enable) }
        E2E.log("auto_speed", "on" to enable)
        val pb = app.playback
        if (enable) {
            pb.stream?.let { load(app, it) }
            if (pb.stream?.captions.isNullOrEmpty()) app.toast(tr("Auto speed works on videos with captions"))
        } else {
            val cid = pb.video?.channelId.orEmpty()
            app.player.setSpeed(if (pb.music) 1f else pb.speedFor(cid))
        }
    }

    /** a video started: its captions are read for the speech rate */
    fun onStart(app: AppState, s: Stream) {
        if (!on || app.playback.music) return
        if (forId != s.id) load(app, s)
    }

    private fun load(app: AppState, s: Stream) {
        forId = s.id; lines = emptyList(); wpm = 0f; lastAt = 0L
        job?.cancel()
        // the track in the spoken language: YouTube's automatic one is made from the speech itself
        val c: Caption = s.captions.firstOrNull { it.auto } ?: s.captions.firstOrNull() ?: return
        job = app.scope.launch {
            val text = runCatching {
                val u = YouTube.captionUrl(c, null)
                if (u.startsWith("file:")) Sys.fs.read(u.removePrefix("file://").removePrefix("file:").toPath()) { readUtf8() }
                else Net.get(u)
            }.getOrNull() ?: return@launch
            if (forId != s.id) return@launch
            lines = Vtt.lines(text).map { Line(it.startMs, it.endMs, words(it.text)) }
        }
    }

    /** words in [t]; for writing without spaces (Japanese, Chinese, Thai) about one word per 2.5 characters */
    internal fun words(t: String): Float {
        val clean = t.replace(Regex("""\[[^\]]*]|\([^)]*\)|♪"""), " ").trim()
        if (clean.isEmpty()) return 0f
        val spaced = clean.split(Regex("\\s+")).count { it.any(Char::isLetterOrDigit) }
        val letters = clean.count { it.isLetterOrDigit() }
        return if (spaced * 12 < letters) letters / 2.5f else spaced.toFloat()
    }

    /** words per minute in the window around [posMs]; 0 when too little is said to tell */
    internal fun rate(posMs: Long, durMs: Long): Float {
        val a = (posMs - 15_000).coerceAtLeast(0)
        val b = (posMs + 25_000).let { if (durMs > 0) minOf(it, durMs) else it }
        if (b - a < 10_000) return 0f
        var w = 0f
        for (l in lines) {
            if (l.end <= a || l.start >= b) continue
            val len = (l.end - l.start).coerceAtLeast(1)
            val inside = (minOf(l.end, b) - maxOf(l.start, a)).toFloat() / len
            w += l.words * inside.coerceIn(0f, 1f)
        }
        return w / ((b - a) / 60_000f)
    }

    /** the player's state changed: now and then the speed is brought towards the speech's */
    fun tick(app: AppState, st: PlayerState) {
        if (!on || lines.isEmpty() || !st.playing || Perks.holding || app.playback.music) return
        if (st.videoId != forId) return
        val t = Sys.nowMs()
        if (t - lastAt < 3_000) return
        lastAt = t
        val r = rate(st.positionMs, st.durationMs)
        wpm = r
        // music, silence or a few words over a long stretch: leave the speed as it is
        if (r < 40f) return
        val want = (TARGET_WPM / r).coerceIn(1f, MAX)
        val cur = st.speed
        val next = ((cur + (want - cur) * 0.5f) * 20).roundToInt() / 20f
        if (abs(next - cur) < 0.05f) return
        now = next
        app.player.setSpeed(next)
        E2E.log("auto_speed", "x" to next, "wpm" to r.roundToInt())
    }
}
