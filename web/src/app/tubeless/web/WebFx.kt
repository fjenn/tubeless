package app.tubeless.web

import app.tubehaus.ui.AudioFx
import app.tubehaus.ui.Perks

/**
 * Stable volume and voice boost on the web: Web Audio on the video's sound (tubeless.js `player.fx`). It works where
 * the page reads the video's bytes itself: the extension and Tubeless on youtube.com (the bookmark). The plain
 * website (Instant mode) has no `fx`: its media is cross-origin, so its Web Audio would be silence.
 */
object WebFx : AudioFx {
    override fun apply(stable: Boolean, voice: Boolean) = jsFx(stable, voice)
    fun install() { if (jsHasFx()) Perks.fx = this }
}

private fun jsHasFx(): Boolean = js("!!(window.tubeless && tubeless.player && tubeless.player.fx)")
private fun jsFx(stable: Boolean, voice: Boolean): Unit = js("tubeless.player.fx(stable, voice)")
