package app.tubeless.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tubeless.engine.Heat
import app.tubeless.engine.SponsorBlock
import app.tubeless.engine.Sys
import kotlinx.coroutines.delay

/**
 * Premium's "Jump ahead": after a double tap forward (or → / L on a keyboard) a button offers to skip
 * to where most viewers skip to. The spot comes from YouTube's "Most replayed" wave: the next place
 * ahead where it rises well over the part playing now, taken from the start of that rise. Without a
 * wave: the SponsorBlock highlight ahead, else the next chapter.
 */
object JumpAhead {
    class Offer(val videoId: String, val toMs: Long, val why: String, val untilMs: Long)

    var offer by mutableStateOf<Offer?>(null)
        private set

    /** where Jump ahead would go from [posMs]; null: nowhere worth it */
    fun target(heat: Heat?, chapters: List<app.tubeless.engine.Chapter>, segments: List<SponsorBlock.Segment>, posMs: Long, durMs: Long): Pair<Long, String>? {
        val minAhead = posMs + 8_000
        val end = if (durMs > 0) durMs - 5_000 else Long.MAX_VALUE
        fun clean(ms: Long): Long {
            // never into a part SponsorBlock marks: past it
            var t = ms
            for (s in segments) if (s.category != SponsorBlock.HIGHLIGHT && t >= s.startMs && t < s.endMs) t = s.endMs
            return t
        }
        heatTarget(heat, posMs, minAhead)?.let { t -> clean(t).takeIf { it in minAhead..end }?.let { return it to "heat" } }
        segments.firstOrNull { it.category == SponsorBlock.HIGHLIGHT && it.startMs >= minAhead && it.startMs < end }?.let { return it.startMs to "highlight" }
        chapters.firstOrNull { it.startMs >= minAhead && it.startMs < end }?.let { return clean(it.startMs) to "chapter" }
        return null
    }

    /** the start of the next clear rise in the wave after [minAhead] */
    internal fun heatTarget(heat: Heat?, posMs: Long, minAhead: Long): Long? {
        val p = heat?.points ?: return null
        if (p.size < 10 || heat.spanMs <= 0) return null
        val n = p.size
        val slice = heat.spanMs.toDouble() / n
        val ci = (posMs / slice).toInt().coerceIn(0, n - 1)
        val here = (ci..minOf(n - 1, ci + 2)).map { p[it] }.average().toFloat()
        val first = (minAhead / slice).toInt().coerceAtLeast(ci + 1)
        if (first >= n) return null
        val mean = p.average().toFloat()
        // a peak: at least as high as its neighbours, well above what plays now and above the video's usual
        val bar = maxOf(here + 0.15f, mean + 0.1f, 0.35f)
        var peak = -1
        for (i in first until n) {
            val l = p.getOrElse(i - 1) { 0f }; val r = p.getOrElse(i + 1) { 0f }
            if (p[i] >= bar && p[i] >= l && p[i] >= r) { peak = i; break }
        }
        if (peak < 0) return null
        // back to where the rise begins: half way between the valley before it and the peak
        val half = (p[peak] + here) / 2
        var j = peak
        while (j > first && p[j - 1] < p[j] && p[j - 1] > half) j--
        return (j * slice).toLong()
    }

    /** the button for a few seconds, when there is somewhere to go */
    fun prompt(app: AppState) {
        if (!Perks.prefs.jumpAhead) return
        val pb = app.playback
        val v = pb.video ?: return
        if (v.live || pb.music) return
        val st = app.player.state.value
        val heat = pb.details?.heat
        val t = target(heat, pb.chapters, pb.segments, st.positionMs, st.durationMs)
        offer = t?.let { (to, why) -> Offer(v.id, to, why, Sys.nowMs() + 5_000) }
    }

    fun take(app: AppState) {
        val o = offer ?: return
        offer = null
        val from = app.player.state.value.positionMs
        app.player.seekTo(o.toMs)
        E2E.log("jump", "videoId" to o.videoId, "fromMs" to from, "toMs" to o.toMs, "why" to o.why)
    }

    fun dismiss() { offer = null }
}

/** "Jump ahead ⏭" over the video's lower right while it is offered */
@Composable
internal fun BoxScope.JumpAheadPill(app: AppState, lifted: Boolean) {
    val o = JumpAhead.offer
    val id = app.playback.video?.id
    val shown = o != null && o.videoId == id
    LaunchedEffect(o) {
        if (o != null) { delay((o.untilMs - Sys.nowMs()).coerceAtLeast(0)); if (JumpAhead.offer === o) JumpAhead.dismiss() }
    }
    // above SponsorBlock's button when that is up too
    val sb = app.playback.skippable != null || app.playback.justSkipped != null
    var held by remember { mutableStateOf(o) }
    if (o != null) held = o
    AnimatedVisibility(
        shown, enter = fadeIn(tween(Motion.SHORT)) + slideInHorizontally(tween(Motion.SHORT)) { it / 3 }, exit = fadeOut(tween(Motion.SHORT)),
        modifier = Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = (if (lifted) 76.dp else 16.dp) + (if (sb) 46.dp else 0.dp)),
    ) {
        Row(
            Modifier.clip(RoundedCornerShape(18.dp)).background(Color(0xE61F1F1F)).border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(18.dp))
                .clickable { JumpAhead.take(app) }.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(tr("Jump ahead"), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.width(6.dp))
            Icon(Sym.SkipNext.fill, null, tint = Color.White, modifier = Modifier.size(18.dp))
            held?.let { h -> Text(durationMs(h.toMs), color = Color(0xB3FFFFFF), fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp)) }
        }
    }
}
