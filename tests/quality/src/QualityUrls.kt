package app.tubeless.quality

import app.tubeless.engine.Clients
import app.tubeless.engine.YouTube
import kotlinx.coroutines.runBlocking

/** QUALITY_URLS=id:itag,itag;id:itag...: prints "id itag url" lines (the codec comparison downloads these) */
fun urls(spec: String) = runBlocking {
    for (part in spec.split(';')) {
        val (id, tags) = part.split(':')
        val s = YouTube.playerWith(id, Clients.VISIONOS)
        for (t in tags.split(',')) s.video.firstOrNull { it.itag == t.toInt() }?.let { println("URL $id $t ${it.url}") }
    }
}

/** QUALITY_HLS=id: prints the VISIONOS HLS master list */
fun hls(id: String) = runBlocking {
    val s = YouTube.playerWith(id, Clients.VISIONOS)
    println(app.tubeless.engine.Net.get(s.hls!!))
}
