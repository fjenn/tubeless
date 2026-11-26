package app.tubeless.quality

import app.tubeless.engine.Clients
import app.tubeless.engine.Net
import app.tubeless.engine.YouTube
import kotlinx.coroutines.runBlocking

/** prints every client's video ladder and its HLS variant list for each id */
fun main(args: Array<String>) = runBlocking {
    app.tubeless.engine.InnerTube.identityFile = okio.Path.Companion.run { java.io.File(System.getProperty("java.io.tmpdir"), "tubeless-quality-identity.json").toOkioPath() }
    System.getenv("QUALITY_URLS")?.let { urls(it); return@runBlocking }
    if (System.getenv("QUALITY_CHECK") != null) { val bad = check(); if (bad > 0) kotlin.system.exitProcess(1); return@runBlocking }
    System.getenv("QUALITY_HLS")?.let { hls(it); return@runBlocking }
    for (id in args) {
        for (c in listOf(Clients.VISIONOS, Clients.ANDROID_VR)) {
            val s = try { YouTube.playerWith(id, c) } catch (e: Exception) { println("$id ${c.name}: FAIL $e"); continue }
            println("== $id ${c.name} '${s.title.take(40)}' video formats ${s.video.size}, hls=${s.hls != null}")
            for (f in s.video.filter { it.height >= 720 }.sortedByDescending { it.height * 100000 + it.bitrate / 1000 })
                println("   %4d %-22s %4dp%-2d %6dk idx=%s len=%dMB".format(f.itag, f.codecs, f.height, f.fps, f.bitrate / 1000, f.indexRange != null, f.contentLength shr 20))
            s.hls?.let { h ->
                val m = runCatching { Net.get(h) }.getOrNull() ?: return@let println("   hls: fetch failed")
                Regex("#EXT-X-STREAM-INF:([^\\n]*)").findAll(m).map { it.groupValues[1] }.forEach { l ->
                    val bw = Regex("(?<!AVERAGE-)BANDWIDTH=(\\d+)").find(l)?.groupValues?.get(1)?.toLong()?.div(1000)
                    val res = Regex("RESOLUTION=(\\S+?)(,|$)").find(l)?.groupValues?.get(1)
                    val codecs = Regex("CODECS=\"([^\"]+)\"").find(l)?.groupValues?.get(1)
                    val fr = Regex("FRAME-RATE=([\\d.]+)").find(l)?.groupValues?.get(1)
                    println("   hls %-10s %6sk fps=%s %s".format(res, bw, fr, codecs))
                }
            }
        }
    }
}
