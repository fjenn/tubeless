package app.tubeless.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.tubeless.data.JsonFile
import app.tubeless.engine.Caption
import app.tubeless.engine.Format
import app.tubeless.engine.Net
import app.tubeless.engine.Stream
import app.tubeless.engine.Sys
import app.tubeless.engine.Video
import app.tubeless.engine.Vtt
import app.tubeless.engine.YouTube
import app.tubeless.engine.chaptersIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.await
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.js.Promise

/** the browser has no environment: the E2E hooks of Offline.kt stay off */
internal fun offlineEnv(name: String): String? = null

/**
 * Videos saved in the browser to watch without internet, as on the desktop (common/jvm OfflineJvm.kt,
 * same queue, settings and resume): the video and the audio as YouTube serves them, kept in the
 * origin private file system (OPFS, under offline/<id>/) by tubeless.dl, and played by Shaka through
 * the same DASH description it uses online (opfs:// addresses). Export joins picture and sound into
 * one MP4/WebM with Mediabunny (no re-encoding) and hands it to the browser's downloads; captions,
 * chapters and the cover stay beside it (not muxed in, unlike the desktop's Mux).
 */
class Offline(private val app: AppState) {
    private val home = app.platform.dataDir / "offline"
    private val io = CoroutineScope(SupervisorJob() + Sys.IO)
    private val index = JsonFile(home / "index.json", OfflineIndex.serializer(), OfflineIndex(), io)
    private val files = OfflineFiles(home, io)
    /** the browser keeps them in its own storage: no folder to choose */
    val folder: String get() = ""
    val prefs: JsonFile<DownloadPrefs> get() = files.prefs
    suspend fun moveTo(path: String): Boolean = false
    val saved: StateFlow<OfflineIndex> get() = index.state

    val progress = mutableStateMapOf<String, Float>()
    val pending = mutableStateMapOf<String, Video>()
    val stats = mutableStateMapOf<String, DlStat>()
    var asking by mutableStateOf<Video?>(null)
    val pickTracks = true

    private val jobs = HashMap<String, Job>()
    private val pausing = HashSet<String>()
    private var running = 0

    init {
        for (q in files.queue.value.items) {
            if (q.id in index.value.items) { files.drop(q.id); continue }
            pending[q.id] = q.video(); progress[q.id] = 0f; stats[q.id] = DlStat(paused = q.paused)
            if (!q.paused) launch(q)
        }
        attachShared(app)
    }

    fun has(id: String) = id in index.value.items
    fun downloading(id: String) = id in progress
    fun queuedSmart(id: String) = files.get(id)?.smart == true
    fun metered() = app.platform.metered()

    fun thumbUrl(s: SavedVideo): String? = s.thumb.takeIf { it.isNotEmpty() }?.let { "opfs://$it" }

    suspend fun choices(id: String): DownloadChoices {
        val s = YouTube.stream(id)
        if (s.live) error(tr("Live streams can't be downloaded"))
        return DownloadChoices(qualitiesOf(s.video, s.audio), audioChoices(s.audio), s.captions)
    }

    fun stream(id: String): Stream? {
        val s = index.value.items[id] ?: return null
        return Stream(
            id = s.id, title = s.title, author = s.author, channelId = s.channelId, lengthSec = s.lengthSec, views = 0,
            description = s.description, keywords = emptyList(), live = false, hls = null,
            video = listOf(s.video.format("opfs://" + s.video.file)), audio = listOf(s.audio.format("opfs://" + s.audio.file)),
            captions = s.allCaptions.map { c -> Caption(c.lang, c.name, "opfs://" + c.file, c.auto) },
            expiresAt = Long.MAX_VALUE, client = "offline",
        )
    }

    fun start(
        v: Video, maxHeight: Int, track: String? = null, caption: Caption? = null,
        codec: String? = null, captions: String? = null, smart: Boolean = false,
    ) {
        if (has(v.id) || downloading(v.id)) return
        val q = QueuedDownload(
            id = v.id, title = v.title, author = v.author, channelId = v.channelId, lengthSec = v.lengthSec, short = v.short,
            height = maxHeight, codec = codec ?: prefs.value.codec, track = track,
            captions = captions ?: caption?.let { it.lang + "|" + it.name }, smart = smart, at = Sys.nowMs() / 1000,
        )
        files.put(q)
        progress[v.id] = 0f; pending[v.id] = v; stats[v.id] = DlStat(waiting = tr("Queued"))
        if (!smart) app.toast(tr("Downloading: {t}").replace("{t}", v.title))
        OfflineEvents.log("download_queued", "videoId" to v.id, "height" to maxHeight, "codec" to q.codec, "smart" to smart)
        launch(q)
    }

    private fun launch(q: QueuedDownload) {
        jobs[q.id]?.cancel()
        var self: Job? = null
        self = app.scope.launch {
            var mine = false
            var keep = false
            try {
                while (true) {
                    val p = prefs.value
                    if ((p.wifiOnly || q.smart) && metered()) { stats[q.id] = DlStat(waiting = tr("Waiting for Wi-Fi")); delay(15_000); continue }
                    // one thread: no lock needed to count the running ones
                    if (running < p.parallel.coerceIn(1, 16)) { running++; mine = true; break }
                    if (stats[q.id]?.waiting.isNullOrEmpty()) stats[q.id] = DlStat(waiting = tr("Queued"))
                    delay(500)
                }
                stats[q.id] = DlStat()
                val got = download(files.get(q.id) ?: q)
                index.update { it.copy(items = it.items + (q.id to got)) }
                index.flushNow()
                files.drop(q.id)
                OfflineEvents.log("download_done", "videoId" to q.id, "height" to got.video.height, "bytes" to got.size, "smart" to q.smart)
                if (!q.smart) app.toast(tr("Saved for offline: {t}").replace("{t}", got.title))
            } catch (e: CancellationException) {
                if (q.id in pausing) keep = true
                else withContext(NonCancellable) { jsRemove("offline/${q.id}"); files.drop(q.id) }
                throw e
            } catch (e: Exception) {
                OfflineEvents.log("download_failed", "videoId" to q.id, "error" to (e.message ?: e.toString()))
                if (q.smart) { jsRemove("offline/${q.id}"); files.drop(q.id) }
                else {
                    keep = true
                    files.edit(q.id) { it.copy(paused = true) }
                    stats[q.id] = DlStat(paused = true, waiting = tr("Stopped: {e}").replace("{e}", e.message ?: e.toString()))
                    app.toast(tr("Couldn't download: {e}").replace("{e}", e.message ?: e.toString()))
                }
            } finally {
                if (mine) running--
                pausing.remove(q.id)
                if (jobs[q.id] === self) jobs.remove(q.id)
                if (!keep) { progress.remove(q.id); pending.remove(q.id); stats.remove(q.id); speed.remove(q.id) }
            }
        }
        jobs[q.id] = self
    }

    fun pause(id: String) {
        files.edit(id) { it.copy(paused = true) }
        stats[id] = (stats[id] ?: DlStat()).copy(paused = true, bps = 0)
        jobs[id]?.let { pausing += id; it.cancel() }
    }

    fun resume(id: String) {
        val q = files.get(id) ?: return
        files.edit(id) { it.copy(paused = false) }
        stats[id] = DlStat(waiting = tr("Queued"))
        launch(q.copy(paused = false))
    }

    fun cancel(id: String) {
        val j = jobs[id]
        if (j != null) { pausing -= id; j.cancel(); return }
        files.drop(id)
        progress.remove(id); pending.remove(id); stats.remove(id)
        jsRemove("offline/$id")
    }

    fun export(id: String, done: (String?) -> Unit) {
        val s = index.value.items[id] ?: return done(null)
        app.scope.launch {
            val name = s.title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().take(120).ifEmpty { id }
            done(runCatching {
                val bytes = jsExport(s.video.file, s.audio.file, name).await<JsNumber>().toDouble()
                OfflineEvents.log("download_exported", "videoId" to id, "bytes" to bytes)
                name
            }.getOrNull())
        }
    }

    fun remove(id: String) {
        if (id !in index.value.items) { if (downloading(id)) cancel(id); return }
        index.update { it.copy(items = it.items - id) }
        jsRemove("offline/$id")
    }

    fun removeAll() {
        for (id in pending.keys.toList()) cancel(id)
        val ids = index.value.items.keys.toList()
        index.update { OfflineIndex() }
        for (id in ids) jsRemove("offline/$id")
    }

    // ------------------------------------------------------------------ downloading

    private suspend fun download(q: QueuedDownload): SavedVideo {
        val s = YouTube.stream(q.id)
        if (s.live) error(tr("Live streams can't be downloaded"))
        val video = s.video.firstOrNull { q.videoItag != 0 && it.itag == q.videoItag && it.indexRange != null }
            ?: pickVideoFormat(s.video, q.height, q.codec) ?: error(tr("This video can't be downloaded"))
        val audio = s.audio.firstOrNull { q.audioItag != 0 && it.itag == q.audioItag && it.indexRange != null }
            ?: pickAudioFormat(s.audio, video, q.track) ?: error(tr("This video can't be downloaded"))
        if (video.url.startsWith("sabr:")) error(tr("This video can't be downloaded"))
        if (video.itag != q.videoItag || audio.itag != q.audioItag) {
            jsRemove("offline/${q.id}")
            files.edit(q.id) { it.copy(videoItag = video.itag, audioItag = audio.itag) }
        }
        val title = s.title.ifEmpty { q.title }
        pending[q.id]?.let { if (it.title != title) pending[q.id] = it.copy(title = title) }
        val caps = captionsFor(s, q.captions).mapIndexedNotNull { i, c ->
            runCatching {
                val text = Vtt.clean(Net.get(YouTube.captionUrl(c)))
                require("-->" in text)
                val name = "offline/${q.id}/captions.$i.${c.lang.replace(Regex("[^A-Za-z0-9-]"), "_")}${if (c.auto) ".auto" else ""}.vtt"
                jsWriteText(name, text).await<JsAny?>()
                SavedCaption(c.lang, c.name, name, c.auto)
            }.getOrNull()
        }
        val thumb = runCatching {
            val name = "offline/${q.id}/thumb.jpg"
            val ok = jsSaveFirst(name, listOf("maxresdefault", "sddefault", "hqdefault").joinToString("\n") { "https://i.ytimg.com/vi/${q.id}/$it.jpg" })
                .await<JsBoolean>().toBoolean()
            if (!ok) error("no thumbnail")
            name
        }.getOrDefault("")
        val chapters = chaptersIn(s.description).map { SavedChapter(it.title, it.startMs) }
        val vName = "offline/${q.id}/video.${ext(video)}"
        val aName = "offline/${q.id}/audio.${ext(audio)}"
        val total = (video.contentLength + audio.contentLength).coerceAtLeast(1)
        val vSize = fetch(q.id, video, vName) { got -> report(q.id, got, total) }
        val aSize = fetch(q.id, audio, aName) { got -> report(q.id, vSize + got, total) }
        return SavedVideo(
            id = q.id, title = title, author = s.author.ifEmpty { q.author },
            channelId = s.channelId.ifEmpty { q.channelId }, lengthSec = s.lengthSec.takeIf { it > 0 } ?: q.lengthSec,
            video = saved(video, vSize, vName), audio = saved(audio, aSize, aName), at = Sys.nowMs() / 1000,
            audioName = if (q.track != null && audio.audioTrack == q.track) audio.audioTrackName ?: audio.audioLang ?: "" else "",
            caption = caps.firstOrNull(), captions = caps, chapters = chapters, description = s.description, thumb = thumb,
            smart = q.smart, short = q.short,
        )
    }

    private fun captionsFor(s: Stream, want: String?): List<Caption> {
        val lang = app.store.settings.value.captionLang.ifEmpty { app.store.settings.value.lang.ifEmpty { "en" } }
        fun mine() = listOfNotNull(
            s.captions.firstOrNull { it.lang.substringBefore('-') == lang && !it.auto } ?: s.captions.firstOrNull { it.lang.substringBefore('-') == lang })
        return when (want) {
            null -> when (prefs.value.captions) { "all" -> s.captions; "mine" -> mine(); else -> emptyList() }
            "" -> emptyList()
            "*" -> s.captions
            else -> {
                val (l, n) = want.split('|', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                listOfNotNull(s.captions.firstOrNull { it.lang == l && it.name == n } ?: s.captions.firstOrNull { it.lang == l })
            }
        }
    }

    private class Speed(var at: Long, var bytes: Long, var bps: Long)
    private val speed = HashMap<String, Speed>()
    private var lastReport = 0L

    private fun report(id: String, done: Long, total: Long) {
        val now = Sys.nowMs()
        if (now - lastReport < 250 && done < total) return
        lastReport = now
        val s = speed.getOrPut(id) { Speed(now, done, 0) }
        if (now - s.at >= 1000) { s.bps = ((done - s.bytes) * 1000 / (now - s.at)).coerceAtLeast(0); s.at = now; s.bytes = done }
        if (id in progress) { progress[id] = (done.toFloat() / total).coerceIn(0f, 1f); stats[id] = DlStat(done, total, s.bps) }
    }

    /**
     * One stream file into OPFS in 10 MB ranges (tubeless.dl.fetchTo), carrying on from the bytes a pause
     * or a restart left. A refused or expired link is renewed; a dropped connection is tried again.
     */
    private suspend fun fetch(id: String, f: Format, path: String, progress: (Long) -> Unit): Long {
        var have = jsSize(path).await<JsNumber>().toDouble().toLong()
        if (f.contentLength in 1..<have) { jsRemove(path); have = 0 }
        if (f.contentLength > 0 && have == f.contentLength) { progress(have); return have }
        var url = f.url
        var renewed = 0
        var failures = 0
        progress(have)
        while (true) {
            val r = try {
                jsFetchTo(path, url, have.toDouble(), f.contentLength.toDouble()) { got -> progress(got.toLong()) }.await<JsAny>()
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                have = jsSize(path).await<JsNumber>().toDouble().toLong()
                if (++failures > 8) throw okio.IOException(e.message ?: "network error")
                delay(1000L * failures * failures)
                // the same link failing again: often googlevideo moving it to another server, which a page may not
                // follow (the redirect's Origin turns "null", refused by CORS; youtube.com page, 2026-11-25), or a
                // server that stopped sending: a fresh link from a new player answer goes to another server
                if (failures >= 2 && renewed < 5) {
                    runCatching { YouTube.stream(id).let { s -> (s.video + s.audio).firstOrNull { it.itag == f.itag }?.url } }.getOrNull()?.let { url = it; renewed++ }
                }
                continue
            }
            val status = jsStatus(r)
            have = jsHave(r).toLong()
            if (status == 200) break
            if ((status == 403 || status == 410) && renewed++ < 5) {
                url = YouTube.stream(id).let { s -> (s.video + s.audio).firstOrNull { it.itag == f.itag }?.url } ?: error("HTTP $status")
                continue
            }
            error("HTTP $status")
        }
        if (f.contentLength > 0 && have < f.contentLength) error(tr("The download stopped early"))
        return have
    }

    private fun saved(f: Format, size: Long, name: String) = SavedFile(
        itag = f.itag, mime = f.mime, codecs = f.codecs, width = f.width, height = f.height, fps = f.fps, bitrate = f.bitrate,
        size = size, init = f.initRange?.let { listOf(it.first, it.last) }, index = f.indexRange?.let { listOf(it.first, it.last) },
        sampleRate = f.sampleRate, channels = f.channels, file = name,
    )

    private fun ext(f: Format) = if (f.mime.endsWith("webm")) "webm" else if (f.isVideo) "mp4" else "m4a"

    companion object {
        fun pickVideo(list: List<Format>, maxHeight: Int, codec: String = "auto"): Format? = pickVideoFormat(list, maxHeight, codec)
        fun pickAudio(list: List<Format>, video: Format, track: String? = null): Format? = pickAudioFormat(list, video, track)
    }
}

/** the saved file as a format whose address is its OPFS path */
private fun SavedFile.format(url: String) = Format(
    itag = itag, url = url, mime = mime, codecs = codecs, width = width, height = height, fps = fps,
    bitrate = bitrate, contentLength = size, initRange = init?.let { it[0]..it[1] }, indexRange = index?.let { it[0]..it[1] },
    sampleRate = sampleRate, channels = channels,
)

private fun jsFetchTo(path: String, url: String, from: Double, len: Double, progress: (Double) -> Unit): Promise<JsAny> =
    js("tubeless.dl.fetchTo(path, url, from, len, progress)")
private fun jsStatus(r: JsAny): Int = js("r.status")
private fun jsHave(r: JsAny): Double = js("r.have")
private fun jsSize(path: String): Promise<JsNumber> = js("tubeless.dl.size(path)")
private fun jsWriteText(path: String, text: String): Promise<JsAny?> = js("tubeless.dl.writeText(path, text)")
private fun jsSaveFirst(path: String, urls: String): Promise<JsBoolean> = js("tubeless.dl.saveFirst(path, urls.split('\\n'))")
private fun jsRemove(path: String): Unit = js("{ tubeless.dl.remove(path) }")
private fun jsExport(v: String, a: String, name: String): Promise<JsNumber> = js("tubeless.dl.exportJoined(v, a, name)")
