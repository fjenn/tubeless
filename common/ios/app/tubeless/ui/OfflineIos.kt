package app.tubeless.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.tubeless.data.JsonFile
import app.tubeless.engine.Caption
import app.tubeless.engine.Net
import app.tubeless.engine.ServerVideo
import app.tubeless.engine.Stream
import app.tubeless.engine.Sys
import app.tubeless.engine.Video
import app.tubeless.engine.YouTube
import app.tubeless.engine.chaptersIn
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.readValue
import kotlinx.cinterop.toKString
import kotlinx.cinterop.useContents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.AVFoundation.AVAssetDownloadDelegateProtocol
import platform.AVFoundation.AVAssetDownloadTask
import platform.AVFoundation.AVAssetDownloadTaskMinimumRequiredMediaBitrateKey
import platform.AVFoundation.AVAssetDownloadURLSession
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.CMTimeRangeValue
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeRange
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionTask
import platform.Foundation.NSURLSessionTaskStateRunning
import platform.Foundation.NSURLSessionTaskStateSuspended
import platform.Foundation.NSValue
import platform.Foundation.dataWithContentsOfURL
import platform.Foundation.writeToFile
import platform.Network.nw_path_get_status
import platform.Network.nw_path_is_constrained
import platform.Network.nw_path_is_expensive
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.Network.nw_path_status_satisfied
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationWillEnterForegroundNotification
import platform.UIKit.UIBackgroundTaskIdentifier
import platform.UIKit.UIBackgroundTaskInvalid
import platform.darwin.NSObject
import platform.darwin.dispatch_get_main_queue
import platform.posix.getenv

/** an environment variable (the E2E hooks in Offline.kt) */
@OptIn(ExperimentalForeignApi::class)
/** an environment variable */
internal fun offlineEnv(name: String): String? = getenv(name)?.toKString()

/**
 * Videos saved on the iPhone and iPad to watch without internet. iOS plays YouTube's HLS stream, so
 * it is saved the way Apple does it (AVAssetDownloadURLSession: a .movpkg that AVPlayer opens like
 * the stream itself), at any height the stream has. Downloads, the index, the settings and the queue
 * are the same as elsewhere; the same members as the JVM Offline (common/jvm), so the shared screens
 * use it unchanged.
 *
 * Background: the session is a background one, so iOS itself
 * carries the downloads on while the app is suspended or closed, and they are picked up again when
 * it opens (the queue is on disk). Asking for a video's stream happens in the app: a background task
 * covers that, so a download started just before leaving the app still reaches the system. Saved
 * packages are kept out of iCloud backups (they can always be fetched again, possibly gigabytes).
 * Smart downloads and "Wi-Fi only" use a second session that never uses mobile data.
 */
@OptIn(ExperimentalForeignApi::class)
class Offline(private val app: AppState) {
    private val io = CoroutineScope(SupervisorJob() + Sys.IO)
    private val index = JsonFile(app.platform.dataDir / "offline-ios.json", OfflineIndex.serializer(), OfflineIndex(), io)
    private val files = OfflineFiles(app.platform.dataDir, io)
    val saved: StateFlow<OfflineIndex> get() = index.state
    val prefs: JsonFile<DownloadPrefs> get() = files.prefs

    val progress = mutableStateMapOf<String, Float>()
    val pending = mutableStateMapOf<String, Video>()
    val stats = mutableStateMapOf<String, DlStat>()
    var asking by mutableStateOf<Video?>(null)
    /** Apple's HLS package has its own tracks: no audio track to pick; captions are kept beside it */
    val pickTracks = false

    /** the desktop can keep downloads in a folder of its choosing; iOS keeps them where Apple puts them */
    val folder: String get() = ""
    suspend fun moveTo(path: String): Boolean = false

    private val tasks = HashMap<String, AVAssetDownloadTask>()
    /** where each finished task put its .movpkg (relative to the app's home folder) */
    private val places = HashMap<String, String>()
    private val heights = HashMap<String, Int>()

    private val delegate = Delegate(this)
    private fun config(id: String, wifi: Boolean) = NSURLSessionConfiguration.backgroundSessionConfigurationWithIdentifier(id).apply {
        sessionSendsLaunchEvents = true
        if (wifi) { allowsExpensiveNetworkAccess = false; allowsConstrainedNetworkAccess = false; allowsCellularAccess = false }
    }
    private val session: AVAssetDownloadURLSession by lazy {
        AVAssetDownloadURLSession.sessionWithConfiguration(config("dev.tubeless.app.downloads", false), assetDownloadDelegate = delegate, delegateQueue = NSOperationQueue.mainQueue)
    }
    private val wifiSession: AVAssetDownloadURLSession by lazy {
        AVAssetDownloadURLSession.sessionWithConfiguration(config("dev.tubeless.app.downloads.wifi", true), assetDownloadDelegate = delegate, delegateQueue = NSOperationQueue.mainQueue)
    }

    /** the network: on a metered path (mobile data, Low Data Mode) Wi-Fi-only downloads wait */
    private var expensive = false
    private var online = true
    private val monitor = nw_path_monitor_create().also { m ->
        nw_path_monitor_set_update_handler(m) { path ->
            expensive = nw_path_is_expensive(path) || nw_path_is_constrained(path)
            online = nw_path_get_status(path) == nw_path_status_satisfied
        }
        nw_path_monitor_set_queue(m, dispatch_get_main_queue())
        nw_path_monitor_start(m)
    }

    private var bgTask: UIBackgroundTaskIdentifier = UIBackgroundTaskInvalid

    init {
        // the queue on disk: tasks the system kept going are picked up, the rest start again
        for (q in files.queue.value.items) {
            if (q.id in index.value.items) { files.drop(q.id); continue }
            pending[q.id] = q.video(); progress[q.id] = 0f; stats[q.id] = DlStat(paused = q.paused)
            heights[q.id] = q.height
        }
        for (s in listOf(session, wifiSession)) s.getAllTasksWithCompletionHandler { list ->
            list?.forEach { t ->
                val task = t as? AVAssetDownloadTask ?: return@forEach
                val id = task.taskDescription
                if (id == null || files.get(id) == null) task.cancel() else tasks[id] = task
            }
        }
        app.scope.launch {
            kotlinx.coroutines.delay(1500)
            for (q in files.queue.value.items) if (!q.paused && q.id !in tasks && q.id in pending) launch(q)
        }
        val nc = NSNotificationCenter.defaultCenter
        nc.addObserverForName(UIApplicationDidEnterBackgroundNotification, null, NSOperationQueue.mainQueue) { _ -> enteredBackground() }
        nc.addObserverForName(UIApplicationWillEnterForegroundNotification, null, NSOperationQueue.mainQueue) { _ -> endBackgroundTask() }
        attachShared(app)
    }

    fun has(id: String) = id in index.value.items
    fun downloading(id: String) = id in progress
    fun queuedSmart(id: String) = files.get(id)?.smart == true
    fun metered() = expensive || !online

    private fun absolute(rel: String) = NSHomeDirectory() + "/" + rel
    private fun thumbRel(id: String) = "Library/Application Support/offline-extras/$id.jpg"

    fun thumbUrl(s: SavedVideo): String? = s.thumb.takeIf { it.isNotEmpty() }?.let { absolute(it) }
        ?.takeIf { NSFileManager.defaultManager.fileExistsAtPath(it) }?.let { NSURL.fileURLWithPath(it).absoluteString }

    /** the heights YouTube's HLS list has (AVPlayer plays these), with their codecs and a size estimate */
    suspend fun choices(id: String): DownloadChoices {
        val s = YouTube.stream(id)
        if (s.live) error(tr("Live streams can't be downloaded"))
        val hls = s.hls ?: error(tr("This video can't be downloaded"))
        val qs = variants(Net.get(hls)).groupBy { it.height }.map { (h, vs) ->
            val top = vs.maxBy { it.bandwidth }
            QualityOption(h, vs.maxOf { it.fps }, vs.map { it.codec }.distinct(), top.bandwidth * s.lengthSec / 8)
        }.sortedByDescending { it.height }
        return DownloadChoices(qs, emptyList(), s.captions)
    }

    private class Variant(val height: Int, val bandwidth: Long, val fps: Int, val codec: String)

    private fun variants(master: String): List<Variant> = master.lines().filter { it.startsWith("#EXT-X-STREAM-INF") }.mapNotNull { l ->
        val h = Regex("RESOLUTION=\\d+x(\\d+)").find(l)?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
        val bw = Regex("[^-]BANDWIDTH=(\\d+)").find(l)?.groupValues?.get(1)?.toLongOrNull() ?: return@mapNotNull null
        val fps = Regex("FRAME-RATE=([\\d.]+)").find(l)?.groupValues?.get(1)?.toDoubleOrNull()?.toInt() ?: 30
        val codecs = Regex("CODECS=\"([^\"]+)\"").find(l)?.groupValues?.get(1) ?: ""
        val c = when { "av01" in codecs -> "av1"; "vp09" in codecs || "vp9" in codecs -> "vp9"; else -> "h264" }
        Variant(h, bw, fps, c)
    }

    /** a saved video as a stream: the .movpkg in place of YouTube's HLS address */
    fun stream(id: String): Stream? {
        val s = index.value.items[id] ?: return null
        val path = absolute(s.video.file)
        if (!NSFileManager.defaultManager.fileExistsAtPath(path)) return null
        return Stream(
            id = s.id, title = s.title, author = s.author, channelId = s.channelId, lengthSec = s.lengthSec, views = 0,
            description = s.description, keywords = emptyList(), live = false, hls = NSURL.fileURLWithPath(path).absoluteString,
            video = emptyList(), audio = emptyList(),
            captions = s.allCaptions.filter { NSFileManager.defaultManager.fileExistsAtPath(absolute(it.file)) }
                .map { Caption(it.lang, it.name, NSURL.fileURLWithPath(absolute(it.file)).absoluteString ?: "", it.auto) },
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
        OfflineEvents.log("download_queued", "videoId" to v.id, "height" to maxHeight, "smart" to smart)
        launch(q)
    }

    /** asks for the stream and hands the download to the system (under a background task, as DownloadKeeper) */
    private fun launch(q: QueuedDownload) {
        val bg = UIApplication.sharedApplication.beginBackgroundTaskWithName("Tubeless download") {}
        app.scope.launch {
            try {
                val s = YouTube.stream(q.id)
                if (s.live) error(tr("Live streams can't be downloaded"))
                val hls = s.hls ?: error(tr("This video can't be downloaded"))
                // the variant for the chosen height (and format, when it has it): its bitrate is the floor AVFoundation saves at
                val vs = variants(Net.get(hls))
                val fit = vs.filter { it.height <= q.height }.ifEmpty { vs.sortedBy { it.height }.take(1) }
                val top = fit.maxOfOrNull { it.height }
                val pick = fit.filter { it.height == top }.let { at -> at.filter { it.codec == q.codec }.ifEmpty { at } }.maxByOrNull { it.bandwidth }
                val asset = AVURLAsset(uRL = NSURL(string = hls), options = ServerVideo.headersFor(hls)?.let { mapOf<Any?, Any>("AVURLAssetHTTPHeaderFieldsKey" to it) })
                val options = pick?.let { mapOf<Any?, Any>(AVAssetDownloadTaskMinimumRequiredMediaBitrateKey to NSNumber(longLong = it.bandwidth)) }
                val wifi = q.smart || prefs.value.wifiOnly
                val task = (if (wifi) wifiSession else session).assetDownloadTaskWithURLAsset(asset, assetTitle = s.title.ifEmpty { q.title }, assetArtworkData = null, options = options)
                    ?: error(tr("This video can't be downloaded"))
                task.taskDescription = q.id
                tasks[q.id] = task
                heights[q.id] = pick?.height ?: 0
                pending[q.id] = Video(q.id, s.title.ifEmpty { q.title }, s.author.ifEmpty { q.author }, s.channelId.ifEmpty { q.channelId },
                    s.lengthSec.takeIf { it > 0 } ?: q.lengthSec, short = q.short)
                files.edit(q.id) { it.copy(title = s.title.ifEmpty { q.title }, author = s.author.ifEmpty { q.author }, lengthSec = s.lengthSec) }
                stats[q.id] = if (wifi && metered()) DlStat(waiting = tr("Waiting for Wi-Fi")) else DlStat()
                task.resume()
                // the extras beside the package: thumbnail, captions, chapters (from the description)
                extras(q, s)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                OfflineEvents.log("download_failed", "videoId" to q.id, "error" to (e.message ?: e.toString()))
                if (q.smart) { files.drop(q.id); finish(q.id) }
                else {
                    files.edit(q.id) { it.copy(paused = true) }
                    stats[q.id] = DlStat(paused = true, waiting = tr("Stopped: {e}").replace("{e}", e.message ?: e.toString()))
                    app.toast(tr("Couldn't download: {e}").replace("{e}", e.message ?: e.toString()))
                }
            } finally {
                UIApplication.sharedApplication.endBackgroundTask(bg)
            }
        }
    }

    private val extraData = HashMap<String, Triple<String, List<SavedCaption>, Pair<List<SavedChapter>, String>>>()

    private suspend fun extras(q: QueuedDownload, s: Stream) {
        val fm = NSFileManager.defaultManager
        fm.createDirectoryAtPath(absolute("Library/Application Support/offline-extras"), true, null, null)
        val thumb = runCatching {
            val rel = thumbRel(q.id)
            val data = listOf("maxresdefault", "sddefault", "hqdefault").firstNotNullOfOrNull { k ->
                NSData.dataWithContentsOfURL(NSURL(string = "https://i.ytimg.com/vi/${q.id}/$k.jpg"))?.takeIf { it.length.toLong() > 2000 }
            } ?: error("no thumbnail")
            data.writeToFile(absolute(rel), true)
            rel
        }.getOrDefault("")
        val lang = app.store.settings.value.captionLang.ifEmpty { app.store.settings.value.lang.ifEmpty { "en" } }
        val want = q.captions ?: when (prefs.value.captions) { "all" -> "*"; "mine" -> "mine"; else -> "" }
        val list = when (want) {
            "" -> emptyList()
            "*" -> s.captions
            "mine" -> listOfNotNull(s.captions.firstOrNull { it.lang.substringBefore('-') == lang && !it.auto } ?: s.captions.firstOrNull { it.lang.substringBefore('-') == lang })
            else -> listOfNotNull(s.captions.firstOrNull { it.lang + "|" + it.name == want })
        }
        val caps = list.mapIndexedNotNull { i, c ->
            runCatching {
                val text = Net.get(YouTube.captionUrl(c))
                require("-->" in text)
                val rel = "Library/Application Support/offline-extras/${q.id}.$i.vtt"
                FileSystem.SYSTEM.write(absolute(rel).toPath()) { writeUtf8(text) }
                SavedCaption(c.lang, c.name, rel, c.auto)
            }.getOrNull()
        }
        extraData[q.id] = Triple(thumb, caps, chaptersIn(s.description).map { SavedChapter(it.title, it.startMs) } to s.description)
    }

    fun pause(id: String) {
        files.edit(id) { it.copy(paused = true) }
        stats[id] = (stats[id] ?: DlStat()).copy(paused = true, bps = 0)
        tasks[id]?.suspend()
    }

    fun resume(id: String) {
        val q = files.get(id) ?: return
        files.edit(id) { it.copy(paused = false) }
        stats[id] = DlStat()
        val t = tasks[id]
        if (t != null && (t.state == NSURLSessionTaskStateSuspended || t.state == NSURLSessionTaskStateRunning)) t.resume() else launch(q.copy(paused = false))
    }

    fun cancel(id: String) {
        files.drop(id)
        tasks[id]?.cancel() ?: finish(id)
    }

    /** one file of picture and sound: the desktop does it; iOS keeps Apple's package (it plays as HLS) */
    fun export(id: String, done: (String?) -> Unit) = done(null)

    fun remove(id: String) {
        val s = index.value.items[id] ?: run { if (downloading(id)) cancel(id); return }
        index.update { it.copy(items = it.items - id) }
        val fm = NSFileManager.defaultManager
        fm.removeItemAtPath(absolute(s.video.file), error = null)
        if (s.thumb.isNotEmpty()) fm.removeItemAtPath(absolute(s.thumb), error = null)
        for (c in s.allCaptions) fm.removeItemAtPath(absolute(c.file), error = null)
    }

    fun removeAll() {
        for (id in pending.keys.toList()) cancel(id)
        for (id in index.value.items.keys.toList()) remove(id)
    }

    private fun finish(id: String) {
        progress.remove(id); pending.remove(id); stats.remove(id); tasks.remove(id); places.remove(id); heights.remove(id); extraData.remove(id)
    }

    // ---------------------------------------------------------------- background (DownloadKeeper)

    /** leaving the app with downloads still being prepared: keep running as long as iOS allows */
    private fun enteredBackground() {
        if (pending.isEmpty() || bgTask != UIBackgroundTaskInvalid) return
        bgTask = UIApplication.sharedApplication.beginBackgroundTaskWithName("Tubeless downloads") { endBackgroundTask() }
    }

    private fun endBackgroundTask() {
        if (bgTask == UIBackgroundTaskInvalid) return
        UIApplication.sharedApplication.endBackgroundTask(bgTask)
        bgTask = UIBackgroundTaskInvalid
    }

    /** a saved package can always be fetched again: not in iCloud backups */
    private fun excludeFromBackup(path: String) {
        NSURL.fileURLWithPath(path).setResourceValue(true, forKey = NSURLIsExcludedFromBackupKey, error = null)
    }

    // ---------------------------------------------------------------- from the delegate (main queue)

    internal fun loaded(id: String, f: Double) {
        if (id !in progress) return
        progress[id] = f.toFloat().coerceIn(0f, 1f)
        if (stats[id]?.paused != true && stats[id]?.waiting?.isNotEmpty() == true) stats[id] = DlStat()
    }

    internal fun placed(id: String, location: NSURL) {
        location.relativePath?.let { places[id] = it }
    }

    internal fun done(id: String, error: NSError?) {
        val v = pending[id]
        val rel = places[id]
        if (error == null && v != null && rel != null) {
            val size = folderSize(absolute(rel))
            excludeFromBackup(absolute(rel))
            val q = files.get(id)
            val (thumb, caps, chap) = extraData[id] ?: Triple("", emptyList(), emptyList<SavedChapter>() to "")
            val file = SavedFile(itag = 0, mime = "application/x-mpegURL", codecs = "", height = heights[id] ?: 0, size = size, file = rel)
            index.update {
                it.copy(items = it.items + (id to SavedVideo(id, v.title, v.author, v.channelId, v.lengthSec, file, file.copy(size = 0, file = ""),
                    Sys.nowMs() / 1000, caption = caps.firstOrNull(), captions = caps, chapters = chap.first, description = chap.second,
                    thumb = thumb, smart = q?.smart == true, short = v.short)))
            }
            index.flushNow()
            files.drop(id)
            OfflineEvents.log("download_done", "videoId" to id, "height" to (heights[id] ?: 0), "bytes" to size, "file" to absolute(rel),
                "captions" to caps.size, "chapters" to chap.first.size, "thumb" to thumb.isNotEmpty(), "smart" to (q?.smart == true))
            if (q?.smart != true) app.toast(tr("Saved for offline: {t}").replace("{t}", v.title))
            finish(id)
        } else if (error != null && error.code != -999L) {      // -999: cancelled
            rel?.let { NSFileManager.defaultManager.removeItemAtPath(absolute(it), error = null) }
            OfflineEvents.log("download_failed", "videoId" to id, "error" to error.localizedDescription)
            if (files.get(id)?.smart == true) { files.drop(id); finish(id) }
            else {
                // kept, paused: Resume asks for it again
                tasks.remove(id)
                files.edit(id) { it.copy(paused = true) }
                stats[id] = DlStat(paused = true, waiting = tr("Stopped: {e}").replace("{e}", error.localizedDescription))
                app.toast(tr("Couldn't download: {e}").replace("{e}", error.localizedDescription))
            }
        } else if (error != null) {
            // cancelled: by Cancel (the queue entry is gone) or by the system; a queued one starts again later
            if (files.get(id) == null) finish(id) else tasks.remove(id)
        } else finish(id)
    }

    private fun folderSize(path: String): Long {
        val fm = NSFileManager.defaultManager
        val subs = fm.subpathsOfDirectoryAtPath(path, error = null) ?: return 0
        return subs.sumOf { sub -> ((fm.attributesOfItemAtPath("$path/$sub", error = null)?.get(NSFileSize) as? NSNumber)?.longLongValue ?: 0L) }
    }
}

/** AVFoundation's callbacks for the download sessions; the task's description is the video id */
@OptIn(ExperimentalForeignApi::class)
private class Delegate(private val offline: Offline) : NSObject(), AVAssetDownloadDelegateProtocol {
    @ObjCSignatureOverride
    override fun URLSession(
        session: NSURLSession, assetDownloadTask: AVAssetDownloadTask,
        didLoadTimeRange: CValue<CMTimeRange>, totalTimeRangesLoaded: List<*>, timeRangeExpectedToLoad: CValue<CMTimeRange>,
    ) {
        val id = assetDownloadTask.taskDescription ?: return
        val expected = timeRangeExpectedToLoad.useContents { CMTimeGetSeconds(duration.readValue()) }
        if (!(expected > 0)) return
        val loaded = totalTimeRangesLoaded.sumOf { r ->
            (r as? NSValue)?.CMTimeRangeValue?.useContents { CMTimeGetSeconds(duration.readValue()) } ?: 0.0
        }
        offline.loaded(id, loaded / expected)
    }

    @ObjCSignatureOverride
    override fun URLSession(session: NSURLSession, assetDownloadTask: AVAssetDownloadTask, didFinishDownloadingToURL: NSURL) {
        assetDownloadTask.taskDescription?.let { offline.placed(it, didFinishDownloadingToURL) }
    }

    @ObjCSignatureOverride
    override fun URLSession(session: NSURLSession, task: NSURLSessionTask, didCompleteWithError: NSError?) {
        task.taskDescription?.let { offline.done(it, didCompleteWithError) }
    }
}
