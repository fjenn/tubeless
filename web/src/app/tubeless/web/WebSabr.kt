package app.tubeless.web

import app.tubehaus.engine.Sabr
import app.tubehaus.engine.SabrHost
import app.tubehaus.web.jsLog
import kotlinx.coroutines.await
import kotlin.js.Promise

/**
 * SABR (BotGuard + googlevideo's SABR protocol) through common/sabr/sabr-page.js in a hidden
 * https://www.youtube.com/robots.txt?tubeless frame (extension/src/sabr-frame.js): the fallback when
 * the token-free clients can't play a video. The frame starts on the first video that needs it.
 * The fetches themselves are driven from tubeless.js (the player asks for byte ranges), so the
 * run-level calls of SabrHost are not used here.
 */
class WebSabr : SabrHost {
    override suspend fun prepare(id: String, hl: String, gl: String): String? =
        runCatching { jsPrepare(id, hl, gl).await<JsString>().toString() }.onFailure { jsLog("sabr prepare: $it") }.getOrNull()
    override suspend fun caption(id: String, url: String): String? =
        runCatching { jsCaption(id, url).await<JsString>().toString() }.getOrNull()?.takeIf { it.isNotEmpty() }
    override fun start(run: Int, id: String, index: Int, startMs: Long) {}
    override fun allow(run: Int, upTo: Long) {}
    override fun stop(run: Int) {}
}

object WebSabrHost {
    fun install() { Sabr.host = WebSabr() }
}

private fun jsPrepare(id: String, hl: String, gl: String): Promise<JsString> = js("tubeless.sabr.prepare(id, hl, gl)")
private fun jsCaption(id: String, url: String): Promise<JsString> = js("tubeless.sabr.caption(id, url)")
