package app.tubeless.ios

import app.tubeless.ui.AppState
import app.tubeless.ui.MobilePrefs
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionPortAirPlay
import platform.AVFAudio.AVAudioSessionPortBluetoothA2DP
import platform.AVFAudio.AVAudioSessionPortBluetoothHFP
import platform.AVFAudio.AVAudioSessionPortBluetoothLE
import platform.AVFAudio.AVAudioSessionPortCarAudio
import platform.AVFAudio.AVAudioSessionPortDescription
import platform.AVFAudio.AVAudioSessionPortHDMI
import platform.AVFAudio.AVAudioSessionPortHeadphones
import platform.AVFAudio.AVAudioSessionPortLineOut
import platform.AVFAudio.AVAudioSessionPortUSBAudio
import platform.AVFAudio.currentRoute
import platform.AVFoundation.AVPlayerLayer
import platform.AVFoundation.play
import platform.AVFoundation.rate
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.QuartzCore.CALayer
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationWillEnterForegroundNotification
import platform.UIKit.UIApplicationWillResignActiveNotification

/**
 * Background play on the iPhone and iPad (Settings → Background & picture-in-picture: Always,
 * Headphones, Off), and the picture-in-picture switch.
 *
 * iOS pauses an AVPlayer that still has a picture on screen (an AVPlayerLayer) when the app goes to
 * the background or the screen locks, unless picture-in-picture took it (Apple QA1668). So when the
 * setting allows sound in the background, the player's layer is let go of the player and the sound
 * started again; the layer gets it back when the app returns. Picture-in-picture, when it started,
 * keeps the player playing: then nothing is done here.
 *
 * Picture-in-picture off: the layer is let go of already when the app is about to leave the front,
 * before iOS would start the small window from it.
 */
@OptIn(ExperimentalForeignApi::class)
class BackgroundAudio(private val app: AppState, private val player: IosPlayer) {
    private var wasPlaying = false
    /** layers let go of the player, to get it back in the foreground */
    private val detached = ArrayList<AVPlayerLayer>()

    fun start() {
        val c = NSNotificationCenter.defaultCenter
        val q = NSOperationQueue.mainQueue
        c.addObserverForName(UIApplicationWillResignActiveNotification, null, q) { _ ->
            wasPlaying = player.state.value.playing
            if (wasPlaying && !MobilePrefs.value.autoPip) detach()
        }
        c.addObserverForName(UIApplicationDidBecomeActiveNotification, null, q) { _ -> attach() }
        c.addObserverForName(UIApplicationWillEnterForegroundNotification, null, q) { _ -> attach() }
        c.addObserverForName(UIApplicationDidEnterBackgroundNotification, null, q) { _ ->
            if (!wasPlaying) return@addObserverForName
            // a few seconds of guaranteed running time to see what iOS does
            val task = UIApplication.sharedApplication.beginBackgroundTaskWithName("Tubeless background play") {}
            app.scope.launch {
              try {
                // iOS stops the picture's track and pauses the player a moment after the app left (about
                // 1.5 s on the simulator); picture-in-picture keeps it going. Watch for that pause for 4 s.
                val av = player.av
                var paused = false
                for (i in 0 until 16) {
                    delay(250)
                    if (UIApplication.sharedApplication.applicationState == platform.UIKit.UIApplicationState.UIApplicationStateActive) return@launch
                    if (av.rate == 0f) { paused = true; break }
                }
                if (!paused && detached.isEmpty()) return@launch   // picture-in-picture took it
                if (!MobilePrefs.mayPlayInBackground(app, externalAudio())) {
                    if (av.rate > 0f) player.pause()
                    return@launch
                }
                detach()
                player.play()
                platform.Foundation.NSLog("BackgroundPlay: sound goes on in the background (${MobilePrefs.mode})")
              } finally {
                UIApplication.sharedApplication.endBackgroundTask(task)
              }
            }
        }
    }

    /** the sound goes to headphones, a Bluetooth or AirPlay speaker, the car, a cable */
    private fun externalAudio(): Boolean {
        val outs = AVAudioSession.sharedInstance().currentRoute.outputs.filterIsInstance<AVAudioSessionPortDescription>()
        val external = setOf(AVAudioSessionPortHeadphones, AVAudioSessionPortBluetoothA2DP, AVAudioSessionPortBluetoothHFP,
            AVAudioSessionPortBluetoothLE, AVAudioSessionPortAirPlay, AVAudioSessionPortCarAudio, AVAudioSessionPortUSBAudio,
            AVAudioSessionPortLineOut, AVAudioSessionPortHDMI)
        return outs.any { it.portType in external }
    }

    private fun detach() {
        val av = player.av
        for (l in playerLayers()) if (l.player === av) { l.player = null; detached += l }
    }

    private fun attach() {
        if (detached.isEmpty()) return
        val av = player.av
        for (l in detached) if (l.player == null) l.player = av
        detached.clear()
    }

    /** the player's picture layers in the app's windows (IosPlayer keeps one, wherever it shows) */
    private fun playerLayers(): List<AVPlayerLayer> {
        val out = ArrayList<AVPlayerLayer>()
        fun walk(l: CALayer) {
            if (l is AVPlayerLayer) out += l
            l.sublayers?.forEach { (it as? CALayer)?.let(::walk) }
        }
        UIApplication.sharedApplication.windows.forEach { w -> (w as? platform.UIKit.UIWindow)?.layer?.let(::walk) }
        return out
    }

}
