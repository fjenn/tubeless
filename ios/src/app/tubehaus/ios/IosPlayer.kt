package app.tubehaus.ios

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.UIKitView
import app.tubehaus.engine.Caption
import app.tubehaus.engine.Net
import app.tubehaus.engine.ServerVideo
import app.tubehaus.engine.Stream
import app.tubehaus.engine.YouTube
import app.tubehaus.ui.PlayOptions
import app.tubehaus.ui.PlayerState
import app.tubehaus.ui.VideoPlayer
import kotlinx.cinterop.CValue
import platform.AVFoundation.addOutput
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.addressOf
import androidx.compose.ui.graphics.asComposeImageBitmap
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import platform.AVFoundation.muted
import platform.AVFoundation.volume
import kotlinx.coroutines.launch
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.setActive
import platform.AVFoundation.AVLayerVideoGravityResizeAspect
import platform.AVKit.AVPictureInPictureController
import platform.AVFoundation.AVMediaCharacteristicAudible
import platform.AVFoundation.AVMediaSelectionOption
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.AVPlayerItemStatusFailed
import platform.AVFoundation.AVPlayerItemStatusReadyToPlay
import platform.AVFoundation.AVPlayerLayer
import platform.AVFoundation.AVPlayerTimeControlStatusPlaying
import platform.AVFoundation.AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate
import platform.AVFoundation.appliesMediaSelectionCriteriaAutomatically
import platform.AVFoundation.asset
import platform.AVFoundation.audioTimePitchAlgorithm
import platform.AVFoundation.currentItem
import platform.AVFoundation.currentTime
import platform.AVFoundation.duration
import platform.AVFoundation.pause
import platform.AVFoundation.playImmediatelyAtRate
import platform.AVFoundation.preferredForwardBufferDuration
import platform.AVFoundation.configuredTimeOffsetFromLive
import platform.AVFoundation.automaticallyPreservesTimeOffsetFromLive
import platform.AVFoundation.preferredMaximumResolution
import platform.AVFoundation.rate
import platform.AVFoundation.replaceCurrentItemWithPlayerItem
import platform.AVFoundation.mediaSelectionGroupForMediaCharacteristic
import platform.AVFoundation.seekToTime
import platform.AVFoundation.selectMediaOption
import platform.AVFoundation.timeControlStatus
import platform.CoreGraphics.CGRectEqualToRect
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.CoreMedia.CMTime
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMake
import platform.Foundation.NSURL
import platform.QuartzCore.CATransaction
import platform.UIKit.UIColor
import platform.UIKit.UIView

/**
 * The iPad's player: AVPlayer on YouTube's HLS stream (one address, every quality from 144p to
 * 4K, sound included). Captions are drawn here in Compose from the cleaned WebVTT, because
 * AVPlayer can't take a side-loaded track for HLS.
 */
class IosPlayer : VideoPlayer {
    private val st = MutableStateFlow(PlayerState())
    override val state: StateFlow<PlayerState> get() = st

    val av = AVPlayer()
    private val scope = MainScope()
    private var stream: Stream? = null
    private var opts = PlayOptions()
    /** where to start once the item is ready (AVPlayer ignores a seek before that); -1 = done */
    private var startAt = -1L
    /** the player's picture-in-picture button (nothing happens while AVPlayer can't yet) */
    fun startPip() {
        val pip = picture.pip
        platform.Foundation.NSLog("PiP: start (controller ${pip != null}, possible ${pip?.pictureInPicturePossible}, on screen ${picture.window != null})")
        pip?.startPictureInPicture()
    }

    /** the original audio is chosen once per video, as soon as AVPlayer has read the list */
    private var audioPicked = false
    private var heightsJob: Job? = null
    private var capsJob: Job? = null
    private var cues by mutableStateOf<List<Cue>>(emptyList())
    /** Settings → caption size (%), background (% opacity) and position (0 bottom, 1 higher, 2 top) (#54) */
    private var capStyle by mutableStateOf(Triple(100, 75, 0))

    override fun setCaptionStyle(size: Int, bg: Int, pos: Int) { capStyle = Triple(size, bg, pos) }
    private var readAhead = false

    /**
     * Stable volume on the iPhone: HLS gives AVPlayer no way into the sound itself (an audio mix or
     * processing tap is ignored for HTTP Live Streaming), so loud videos are brought down to YouTube's
     * reference level from the loudness YouTube measured for them (playerConfig.audioConfig.loudnessDb),
     * as YouTube's own volume normalisation does; quieter ones play as they are. No voice boost here.
     */
    private var stable = false
    private val fx = object : app.tubehaus.ui.AudioFx {
        override val canVoice get() = false
        override fun apply(stable: Boolean, voice: Boolean) { this@IosPlayer.stable = stable; gain() }
    }
    init { app.tubehaus.ui.Perks.fx = fx }

    private fun gain() {
        val db = stream?.loudnessDb?.takeIf { stable && it > 0.0 } ?: 0.0
        av.volume = kotlin.math.exp(-db.coerceAtMost(20.0) / 20.0 * kotlin.math.ln(10.0)).toFloat()
    }

    /**
     * The one picture of the player (one AVPlayerLayer, one picture-in-picture controller), moved into
     * whichever place shows the video now: the video page, full screen, Shorts. A new layer and PiP
     * controller each time the video changed place piled up on the same AVPlayer and fought over it
     * (a black or misplaced picture, PiP that stopped working) after going back and forth a few times.
     */
    private val picture by lazy { PlayerView(av) }
    /** the places on screen, newest last: the newest one gets the picture */
    private val hosts = ArrayList<PlayerHost>()

    private fun place() {
        val h = hosts.lastOrNull() ?: return
        if (picture.superview !== h) h.addSubview(picture)
        if (!CGRectEqualToRect(picture.frame, h.bounds)) picture.setFrame(h.bounds)
    }

    private fun hostMoved(h: PlayerHost, onScreen: Boolean) {
        hosts.remove(h)
        if (onScreen) hosts.add(h)
        place()
    }

    init {
        // the E2E event log (scripts/e2e-m3.sh): TUBELESS_E2E_LOG in the launch environment
        app.tubehaus.ui.E2E.path = platform.Foundation.NSProcessInfo.processInfo.environment["TUBELESS_E2E_LOG"] as? String
            ?: platform.Foundation.NSProcessInfo.processInfo.environment["KESTREL_E2E_LOG"] as? String   // the old name still counts
        // sound even with the silent switch on, and the audio keeps playing with the screen locked
        AVAudioSession.sharedInstance().setCategory(AVAudioSessionCategoryPlayback, error = null)
        AVAudioSession.sharedInstance().setActive(true, error = null)
        // AVPlayer would pick the audio track in the device's language (an auto-dub in Dutch, say) over the original
        av.appliesMediaSelectionCriteriaAutomatically = false
        scope.launch {
            while (true) {
                tick()
                delay(250)
            }
        }
    }

    private fun secs(t: CValue<CMTime>): Double = CMTimeGetSeconds(t).takeIf { it.isFinite() && it >= 0 } ?: 0.0

    private fun tick() {
        val item = av.currentItem ?: return
        if (startAt >= 0 && item.status == AVPlayerItemStatusReadyToPlay) {
            if (startAt > 0) av.seekToTime(CMTimeMake(startAt, 1000))
            startAt = -1
        }
        if (!audioPicked && item.status == AVPlayerItemStatusReadyToPlay) {
            audioPicked = true
            pickOriginalAudio(item)
        }
        val dur = secs(item.duration)
        val pos = secs(av.currentTime())
        val playing = av.timeControlStatus == AVPlayerTimeControlStatusPlaying
        val waiting = av.timeControlStatus == AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate
        val ended = dur > 0 && pos >= dur - 0.4 && !playing
        if (ended && opts.loop) {
            av.seekToTime(CMTimeMake(0, 1000))
            av.playImmediatelyAtRate(opts.speed)
        }
        val failed = item.status == AVPlayerItemStatusFailed
        st.update {
            it.copy(
                playing = playing,
                buffering = waiting || (startAt >= 0 && !failed),
                positionMs = if (startAt > 0) startAt else (pos * 1000).toLong(),
                durationMs = (dur * 1000).toLong().takeIf { d -> d > 0 } ?: it.durationMs,
                ended = ended && !opts.loop,
                error = if (failed) item.error?.localizedDescription ?: "Playback failed" else null,
            )
        }
    }

    override fun load(s: Stream, o: PlayOptions) {
        stream = s
        opts = o
        val hls = s.hls
        if (hls == null) {
            st.value = PlayerState(videoId = s.id, error = "This video has no stream the iPad can play")
            return
        }
        // from the connected server, the list and every piece need its login
        val headers = ServerVideo.headersFor(hls)
        val item = if (headers == null) AVPlayerItem(uRL = NSURL(string = hls))
            else AVPlayerItem(asset = AVURLAsset(uRL = NSURL(string = hls), options = mapOf<Any?, Any>("AVURLAssetHTTPHeaderFieldsKey" to headers)))
        // a download plays the quality that was saved: a cap (e.g. the mobile-data one, which applies as soon as Wi-Fi is
        // gone) would make AVPlayer want a variant that isn't in the package and go to the network for it
        if (s.client != "offline") limit(item, o.maxHeight)
        // speeds up to 4x (S5): the spectral time-pitch keeps voices natural over the whole range (the default stops sounding right past 2x)
        item.audioTimePitchAlgorithm = platform.AVFoundation.AVAudioTimePitchAlgorithmSpectral
        if (readAhead) item.preferredForwardBufferDuration = 1200.0
        // live: 8 s behind the edge (two segments), kept there
        if (s.live) { item.configuredTimeOffsetFromLive = CMTimeMake(8_000, 1000); item.automaticallyPreservesTimeOffsetFromLive = true }
        // ambient mode: small copies of the picture are read from here (frame())
        val out = platform.AVFoundation.AVPlayerItemVideoOutput(pixelBufferAttributes = null)
        item.addOutput(out)
        frames = out
        av.replaceCurrentItemWithPlayerItem(item)
        gain()
        audioPicked = false
        startAt = o.startMs
        st.value = PlayerState(videoId = s.id, buffering = true, positionMs = o.startMs, durationMs = s.lengthSec * 1000L,
            speed = o.speed, captions = o.captions)
        if (o.autoplay) av.playImmediatelyAtRate(o.speed) else av.pause()
        // the qualities on offer, from the HLS master list
        heightsJob?.cancel()
        heightsJob = scope.launch {
            if (s.client == "offline") return@launch
            val text = runCatching { Net.get(hls) }.getOrNull() ?: return@launch
            val heights = Regex("RESOLUTION=\\d+x(\\d+)").findAll(text).mapNotNull { it.groupValues[1].toIntOrNull() }.distinct().sorted().toList()
            st.update { it.copy(heights = heights) }
        }
        val translate = if (o.captions != null && o.captionLang != null && o.captions.lang.substringBefore('-') != o.captionLang) o.captionLang else null
        captions(o.captions, translate)
    }

    /** the original language's audio track (from the stream's formats), else the list's default */
    private fun pickOriginalAudio(item: AVPlayerItem) {
        val group = item.asset.mediaSelectionGroupForMediaCharacteristic(AVMediaCharacteristicAudible) ?: return
        val options = group.options.filterIsInstance<AVMediaSelectionOption>()
        if (options.size < 2) return
        val audio = stream?.audio.orEmpty()
        val lang = (audio.firstOrNull { it.audioContent == "original" } ?: audio.firstOrNull { it.audioDefault && it.audioLang != null })
            ?.audioLang?.substringBefore('-')?.lowercase()
        val pick = lang?.let { l -> options.firstOrNull { it.extendedLanguageTag?.substringBefore('-')?.lowercase() == l } }
            ?: group.defaultOption ?: return
        item.selectMediaOption(pick, inMediaSelectionGroup = group)
    }

    /** the highest quality AVPlayer may pick; 0 = no limit (it adapts to the connection) */
    private fun limit(item: AVPlayerItem, h: Int) {
        item.preferredMaximumResolution = if (h in 1..4319) CGSizeMake(h * 16.0 / 9.0, h.toDouble()) else CGSizeMake(0.0, 0.0)
    }

    private var frames: platform.AVFoundation.AVPlayerItemVideoOutput? = null
    private val ci by lazy { platform.CoreImage.CIContext.contextWithOptions(null) }

    /**
     * Ambient mode (as Android and the desktop): the picture now, 32×18 and blurred, from the item's
     * video output through Core Image; null before the first frame.
     */
    @OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
    override suspend fun frame(): androidx.compose.ui.graphics.ImageBitmap? {
        val out = frames ?: return null
        val t = av.currentTime()
        val buf = out.copyPixelBufferForItemTime(t, itemTimeForDisplay = null) ?: return null
        try {
            val img = platform.CoreImage.CIImage.imageWithCVPixelBuffer(buf)
            val ext = img.extent
            val w = ext.useContents { size.width }
            val h = ext.useContents { size.height }
            if (w <= 0.0 || h <= 0.0) return null
            val small = img.imageByApplyingTransform(platform.CoreGraphics.CGAffineTransformMakeScale(32.0 / w, 18.0 / h))
                .imageByApplyingGaussianBlurWithSigma(1.5)
            val px = ByteArray(32 * 18 * 4)
            px.usePinned { pin ->
                ci.render(small, toBitmap = pin.addressOf(0), rowBytes = 32 * 4,
                    bounds = platform.CoreGraphics.CGRectMake(0.0, 0.0, 32.0, 18.0),
                    format = platform.CoreImage.kCIFormatRGBA8, colorSpace = null)
            }
            val bmp = org.jetbrains.skia.Bitmap()
            bmp.allocPixels(org.jetbrains.skia.ImageInfo(32, 18, org.jetbrains.skia.ColorType.RGBA_8888, org.jetbrains.skia.ColorAlphaType.PREMUL))
            bmp.installPixels(px)
            return bmp.asComposeImageBitmap()
        } finally {
            platform.CoreVideo.CVPixelBufferRelease(buf)
        }
    }

    override fun play() {
        if (st.value.ended) av.seekToTime(CMTimeMake(0, 1000))
        av.playImmediatelyAtRate(opts.speed)
        st.update { it.copy(playing = true, ended = false) }
    }

    override fun pause() {
        av.pause()
        st.update { it.copy(playing = false) }
    }

    override fun seekTo(ms: Long) {
        val to = ms.coerceAtLeast(0)
        if (startAt >= 0) startAt = to else av.seekToTime(CMTimeMake(to, 1000))
        st.update { it.copy(positionMs = to, ended = false) }
    }

    override fun setSpeed(x: Float) {
        opts = opts.copy(speed = x)
        if (av.rate > 0f) av.rate = x
        st.update { it.copy(speed = x) }
    }

    override fun setHeight(h: Int) {
        if (stream?.client != "offline") av.currentItem?.let { limit(it, if (h == 0) opts.maxHeight else h) }
        st.update { it.copy(height = h) }
    }

    override fun setCaptions(c: Caption?, translateTo: String?) {
        st.update { it.copy(captions = c) }
        captions(c, translateTo)
    }

    private fun captions(c: Caption?, translateTo: String?) {
        capsJob?.cancel()
        cues = emptyList()
        if (c == null) return
        capsJob = scope.launch {
            val text = runCatching { Net.get(YouTube.captionUrl(c, translateTo)) }.getOrNull() ?: return@launch
            cues = parseVtt(text)
        }
    }

    override fun setLoop(on: Boolean) { opts = opts.copy(loop = on) }

    /** a feed card's muted autoplay (FeedPlay); the phone's own volume stays the system's */
    override fun setMuted(on: Boolean) {
        av.muted = on
        st.update { it.copy(muted = on) }
    }

    override fun setReadAhead(long: Boolean) {
        // 0 lets AVPlayer choose (a few seconds to a minute)
        readAhead = long
        av.currentItem?.preferredForwardBufferDuration = if (long) 1200.0 else 0.0
    }

    override fun stop() {
        av.pause()
        av.replaceCurrentItemWithPlayerItem(null)
        heightsJob?.cancel(); capsJob?.cancel()
        stream = null
        startAt = -1
        cues = emptyList()
        st.value = PlayerState()
    }

    @Composable
    override fun View(modifier: Modifier) {
        val s by st.collectAsState()
        BoxWithConstraints(modifier.background(Color.Black)) {
            // the picture takes no touches: they go to the Compose behind and over it (a feed card's tap opens the
            // video it plays muted; in the default cooperative mode the interop view swallowed that tap)
            @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
            UIKitView(factory = { PlayerHost(::hostMoved) }, modifier = Modifier.fillMaxSize(),
                properties = androidx.compose.ui.viewinterop.UIKitInteropProperties(interactionMode = null))
            // none in the mini player, as YouTube: at its size a line of captions covers the whole picture
            val line = if (s.captions != null && maxHeight >= 150.dp) cues.lastOrNull { s.positionMs >= it.start && s.positionMs < it.end }?.text else null
            // sized to the player like Android's (about 1/19 of its height): a fixed size was tiny on an iPad in full screen
            val (pct, bg, pos) = capStyle
            val base = (maxHeight.value / 19f).coerceIn(16f, 44f)
            val size = base * pct / 100f
            // no box: a soft shadow keeps white text readable on a bright picture
            val style = if (bg == 0) TextStyle(shadow = Shadow(Color.Black, Offset(0f, 1f), 6f)) else TextStyle.Default
            val place = when (pos) {
                2 -> Modifier.align(Alignment.TopCenter).padding(start = 24.dp, end = 24.dp, top = (base * 1.4f).dp)
                1 -> Modifier.align(Alignment.BottomCenter).padding(start = 24.dp, end = 24.dp, bottom = (maxHeight.value * 0.2f).dp)
                else -> Modifier.align(Alignment.BottomCenter).padding(start = 24.dp, end = 24.dp, bottom = (base * 1.4f).dp)
            }
            if (line != null) Text(
                line, color = Color.White, fontSize = size.sp, lineHeight = (size * 1.25f).sp, textAlign = TextAlign.Center, style = style,
                modifier = place.background(Color.Black.copy(alpha = bg / 100f)).padding(horizontal = (size / 2).dp, vertical = 2.dp),
            )
        }
    }
}

/** a place for the player's picture; it tells the player when it comes onto the screen or leaves it, and when it changes size */
private class PlayerHost(private val moved: (PlayerHost, Boolean) -> Unit) : UIView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0)) {
    init {
        backgroundColor = UIColor.blackColor
        userInteractionEnabled = false
    }

    override fun didMoveToWindow() {
        super.didMoveToWindow()
        moved(this, window != null)
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        // only when the size really changed: setting a frame lays things out again
        subviews.forEach { v -> (v as? UIView)?.takeIf { !CGRectEqualToRect(it.frame, bounds) }?.setFrame(bounds) }
    }
}

/**
 * The picture: an AVPlayerLayer that follows the view's size; taps go through to the controls.
 * Going to the home screen while it plays moves the video into a picture-in-picture window.
 */
private class PlayerView(player: AVPlayer) : UIView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0)) {
    private val picture = AVPlayerLayer.playerLayerWithPlayer(player)
    val pip: AVPictureInPictureController? =
        if (AVPictureInPictureController.isPictureInPictureSupported()) AVPictureInPictureController(playerLayer = picture) else null

    init {
        backgroundColor = UIColor.blackColor
        userInteractionEnabled = false
        picture.videoGravity = AVLayerVideoGravityResizeAspect
        layer.addSublayer(picture)
        pip?.canStartPictureInPictureAutomaticallyFromInline = true
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        if (CGRectEqualToRect(picture.frame, bounds)) return
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        picture.frame = bounds
        CATransaction.commit()
    }
}

private class Cue(val start: Long, val end: Long, val text: String)

/** cues of a cleaned WebVTT (Vtt.clean: no tags, no overlaps) */
private fun parseVtt(src: String): List<Cue> {
    val out = ArrayList<Cue>()
    val rows = src.replace("\r\n", "\n").split('\n')
    var i = 0
    fun ms(t: String): Long {
        val p = t.trim().split(':')
        val (h, m, s) = if (p.size == 3) Triple(p[0].toLong(), p[1].toLong(), p[2]) else Triple(0L, p[0].toLong(), p[1])
        return (h * 3600 + m * 60) * 1000 + (s.toDouble() * 1000).toLong()
    }
    while (i < rows.size) {
        val row = rows[i]
        if ("-->" !in row) { i++; continue }
        val a = row.substringBefore("-->")
        val b = row.substringAfter("-->").trim().substringBefore(' ')
        i++
        val text = StringBuilder()
        while (i < rows.size && rows[i].isNotBlank()) { if (text.isNotEmpty()) text.append('\n'); text.append(rows[i].trim()); i++ }
        runCatching { out += Cue(ms(a), ms(b), text.toString()) }
    }
    return out
}
