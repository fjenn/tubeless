package app.tubehaus.desktop

import app.tubehaus.ui.AppState
import app.tubehaus.ui.AudioFx
import app.tubehaus.ui.E2E
import app.tubehaus.ui.Perks
import kotlin.concurrent.thread

/**
 * Stable volume and voice boost on the desktop: FFmpeg filters in mpv's audio chain, under a label of
 * their own (@kfx) so nothing else in the chain is touched.
 * - stable volume: dynaudnorm, a gentle dynamic normaliser (quiet speech up, loud music down, no pumping)
 * - voice boost: rumble cut, the speech band (1.5-4 kHz) lifted, low mud lowered, then a limiter
 */
class MpvFx(private val mpv: MpvPlayer) : AudioFx {
    private var last: String? = null

    override fun apply(stable: Boolean, voice: Boolean) {
        val chain = buildList {
            if (voice) add(VOICE)
            if (stable) add(STABLE)
            if (voice) add("alimiter=limit=0.95:level=disabled")
        }.joinToString(",")
        if (chain == last) return
        last = chain
        // mpv may be starting: never on the UI thread
        thread(isDaemon = true, name = "mpv-fx") {
            runCatching {
                mpv.command("af", "remove", "@kfx")
                if (chain.isNotEmpty()) mpv.command("af", "add", "@kfx:lavfi=[$chain]")
            }
        }
    }

    companion object {
        const val STABLE = "dynaudnorm=f=250:g=21:p=0.9:m=8"
        const val VOICE = "highpass=f=90,equalizer=f=220:t=q:w=1:g=-3,equalizer=f=2500:t=q:w=1.1:g=5,equalizer=f=5000:t=q:w=1:g=2"
    }
}

/** the desktop's share of S5: sound effects, the E2E log, the picture-in-picture window */
object DesktopPerks {
    fun install(state: AppState, platform: DesktopPlatform) {
        E2E.path = env("TUBELESS_E2E_LOG")
        if (platform.mpv.available) Perks.fx = MpvFx(platform.mpv)
        PipWindow.app = state
        PipWindow.mainFrame = { platform.frame }
    }
}
