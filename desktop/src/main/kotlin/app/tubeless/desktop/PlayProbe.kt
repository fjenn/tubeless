package app.tubeless.desktop

import app.tubeless.engine.Video
import app.tubeless.ui.qualityText
import app.tubeless.ui.AppState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * A developer check of real playback in the running app (S2): TUBELESS_AUTOPLAY=id1,id2,... opens each video in turn
 * as a click would, and writes one line per video: the time from opening to the first frame on screen, the client,
 * the formats mpv got, the app's memory (RSS, JVM + JS host + mpv: one process) and how many Edge/Chrome processes
 * run. TUBELESS_AUTOPLAY_SEC (default 25) is how long each video plays; TUBELESS_AUTOPLAY_EXIT=1 quits afterwards.
 */
object PlayProbe {
    fun start(state: AppState, ids: String, log: File) {
        val list = ids.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val each = (env("TUBELESS_AUTOPLAY_SEC")?.toLongOrNull() ?: 25L) * 1000
        val mpv = state.platform.player as? MpvPlayer ?: return
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            delay(4_000)
            fun out(s: String) { System.err.println("PLAYPROBE $s"); runCatching { log.appendText(s + "\n") } }
            out("start ${java.time.LocalDateTime.now()} pid ${ProcessHandle.current().pid()} hardware=${mpv.hardware}")
            // TUBELESS_AUTOPLAY_FULL=1: each video in full screen (the largest picture, as when watching)
            val full = env("TUBELESS_AUTOPLAY_FULL") == "1"

            for (id in list) {
                val t0 = System.currentTimeMillis()
                val cpu0 = ProcessHandle.current().info().totalCpuDuration().map { it.toMillis() }.orElse(0L); val d0 = mpv.framesDrawn; val k0 = mpv.framesSkipped; val n0 = mpv.drawNanos
                withContext(Dispatchers.Main) { state.watch(Video(id, "")); if (full) { delay(1500); state.setFull(true) } }
                var first = 0L
                var maxRss = 0L
                var browsers = 0
                while (System.currentTimeMillis() - t0 < each) {
                    delay(250)
                    if (first == 0L && mpv.firstFrameAt >= t0 && mpv.state.value.positionMs > 0) first = mpv.firstFrameAt - t0
                    if ((System.currentTimeMillis() - t0) % 2000 < 250) { maxRss = maxOf(maxRss, rssMb()); browsers = maxOf(browsers, browserCount()) }
                    if (first == 0L && System.currentTimeMillis() - t0 > 60_000) break
                }
                val jsMem = if (JsHost.running) kotlinx.coroutines.runBlocking { JsHost.ask("__thMem", timeoutMs = 5_000) } else null
                val rt = Runtime.getRuntime()
                val jvm = (rt.totalMemory() - rt.freeMemory()) / 1048576
                val st = mpv.state.value
                val s = state.playback.stream
                val v = mpv.openedVideo
                val a = mpv.openedAudio
                val drawn = mpv.framesDrawn - d0
                // TUBELESS_AUTOPLAY_SHOT=<dir>: the frame on screen at the end, as <id>-<itag>.png
                env("TUBELESS_AUTOPLAY_SHOT")?.let { dir -> mpv.snapshot()?.let { File(dir, "$id-${mpv.openedVideo?.itag}.png").writeBytes(it) } }
                fun pr(n: String) = mpv.prop(n) ?: "-"
                out("$id quality: itag=${v?.itag} ${pr("video-codec")} ${pr("video-params/w")}x${pr("video-params/h")} hwdec=${pr("hwdec-current")} " +
                    "fps=${pr("container-fps")} est-vf-fps=${pr("estimated-vf-fps")} video-bitrate=${pr("video-bitrate")} drops(vo/dec)=${pr("frame-drop-count")}/${pr("decoder-frame-drop-count")} " +
                    "drawn=$drawn skipped=${mpv.framesSkipped - k0} draw=${mpv.drawSize.first}x${mpv.drawSize.second} avgDraw=${if (drawn > 0) (mpv.drawNanos - n0) / drawn / 1000 else 0}us " +
                    "cpu=${(ProcessHandle.current().info().totalCpuDuration().map { it.toMillis() }.orElse(0L) - cpu0) * 100 / (System.currentTimeMillis() - t0)}% label=${st.qualityText("Auto")} dropped=${st.dropped}")
                out("$id first-frame=${if (first > 0) "${first}ms" else "NONE"} client=${s?.client} video=${v?.let { "${it.height}p/${it.itag}" }} " +
                    "audio=${a?.let { "${it.itag}/${it.codecs}/${it.bitrate / 1000}k" }} pos=${st.positionMs / 1000}s err=${st.error} sound=${st.soundIssue} " +
                    "rss=${maxRss}MB browsers=$browsers jshost=${if (JsHost.running) "up(heap used/total/ext/ab MB: ${jsMem?.substringAfter('/')})" else "down"} jvmHeap=${jvm}MB")
            }
            out("done")
            if (env("TUBELESS_AUTOPLAY_EXIT") == "1") {
                withContext(Dispatchers.Main) { state.playback.close(); state.store.flush(); mpv.shutdown() }
                kotlin.system.exitProcess(0)
            }
        }
    }

    /** this process's resident memory in MB */
    fun rssMb(): Long = runCatching {
        ProcessBuilder("ps", "-o", "rss=", "-p", ProcessHandle.current().pid().toString()).start().inputStream.bufferedReader().readText().trim().toLong() / 1024
    }.getOrDefault(-1)

    /** Edge or Chrome processes this app started (its descendants, and any on the old host's profile) */
    fun browserCount(): Int = runCatching {
        val mine = ProcessHandle.current().descendants().filter { p ->
            p.info().command().orElse("").let { c -> c.contains("Chrome") || c.contains("Edge") || c.contains("chromium", true) || c.contains("msedge") }
        }.count().toInt()
        val profile = ProcessBuilder("ps", "-axo", "command").start().inputStream.bufferedReader().readLines().count { it.contains("Tubeless/sabr-browser") }
        mine + profile
    }.getOrDefault(-1)
}
