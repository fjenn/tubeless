package app.tubeless.engine

import kotlin.math.max
import kotlin.math.min

/**
 * What "Auto" quality means on every platform (QUALITY): the smallest height that fills this screen without being
 * stretched (so full screen is as sharp as the screen allows: a 3456×2234 MacBook gets 2160p, a 1920×1080
 * monitor 1080p, a 2560×1600 laptop 1440p), never a fixed cap below it; at that height the codec this device
 * decodes in hardware, then the higher frame rate, then the higher bitrate.
 */
object Quality {
    /** PlayOptions.maxHeight when nothing was picked: the player fits the screen itself */
    const val AUTO = Int.MAX_VALUE

    /**
     * The height to play for a screen of [screenW]×[screenH] pixels, from the video's [sizes] (width to height of
     * each format): the smallest whose picture, fitted to the screen, needs at most 10 % enlarging; the top one
     * when none reaches that. 0 when there are no sizes.
     */
    fun fit(screenW: Int, screenH: Int, sizes: Collection<Pair<Int, Int>>): Int {
        val ok = sizes.filter { it.first > 0 && it.second > 0 }.sortedBy { it.second }
        if (ok.isEmpty()) return 0
        if (screenW <= 0 || screenH <= 0) return ok.last().second
        val long = max(screenW, screenH).toDouble()
        val short = min(screenW, screenH).toDouble()
        // shown fitted: enlarged by the smaller of the two ratios. A wide video fills the screen turned
        // landscape (a phone is turned for it); an upright one (a Short) the screen as it stands
        return ok.firstOrNull { (fw, fh) ->
            val (sw, sh) = if (fh > fw) screenW.toDouble() to screenH.toDouble() else long to short
            min(sw / fw, sh / fh) <= 1.1
        }?.second ?: ok.last().second
    }

    /** [fit] for a stream's video formats; [cap] is the user's pick ([AUTO]: none) */
    fun height(video: List<Format>, cap: Int, screenW: Int, screenH: Int): Int {
        if (cap != AUTO && cap > 0) return cap
        return fit(screenW, screenH, video.map { it.width to it.height }.distinct()).takeIf { it > 0 } ?: AUTO
    }

    /**
     * The format to play at the highest height up to [cap] (the lowest there is when none fits). At that height:
     * [prefer] (Settings' codec) when the height has it; else among the codec families this device decodes in
     * hardware ([hardware], e.g. h264, vp9, av1 on a recent Mac) the higher frame rate, then the most bits per second
     * on average (the file's size over its length; YouTube's peak figure when a size is missing). Measured on 1080p
     * frames against the 4K/1440p stream scaled down (VMAF, docs/BUILD-STATUS.md QUALITY): the fuller stream wins
     * or ties, whatever its codec; YouTube's 1080p VP9 is often the leanest and scored lowest.
     */
    fun best(video: List<Format>, cap: Int, prefer: String = "", hardware: Set<String> = setOf("h264", "vp9", "av1"), lengthSec: Int = 0, sdrOnly: Boolean = false): Format? {
        val all = if (sdrOnly) video.filter { !hdr(it) }.ifEmpty { video } else video
        val ok = all.filter { it.height <= cap }.ifEmpty { all.sortedBy { it.height }.take(1) }
        val top = ok.maxOfOrNull { it.height } ?: return null
        val at = ok.filter { it.height == top }
        if (prefer.isNotEmpty()) at.filter { it.codecFamily == prefer }.let { l -> if (l.isNotEmpty()) return l.maxBy { rate(it, l, lengthSec) } }
        val pool = at.filter { it.codecFamily in hardware }.ifEmpty { at }
        return pool.sortedWith(compareBy<Format>({ -it.fps }, { -rate(it, pool, lengthSec) })).first()
    }

    /**
     * an HDR stream (VP9 profile 2, AV1 with the PQ or HLG transfer): a player that draws 8-bit SDR pictures
     * (the desktop's mpv on its CPU path) would show it washed out
     */
    fun hdr(f: Format): Boolean {
        val c = f.codecs
        if (c.startsWith("vp9.2") || c.startsWith("vp09.02")) return true
        if (!c.startsWith("av01")) return false
        val p = c.split('.')
        return p.getOrNull(7) == "16" || p.getOrNull(7) == "18"
    }

    /** average bits per second when every format in [all] has its size, else the peak YouTube gives */
    private fun rate(f: Format, all: List<Format>, lengthSec: Int): Long =
        if (lengthSec > 0 && all.all { it.contentLength > 0 }) f.contentLength * 8 / lengthSec else f.bitrate.toLong()

    /** "1080p60" */
    fun label(height: Int, fps: Int = 0): String = if (height <= 0) "" else "${height}p" + if (fps > 30) "$fps" else ""
}
