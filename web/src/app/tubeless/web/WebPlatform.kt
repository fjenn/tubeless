package app.tubeless.web

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import app.tubehaus.ui.Platform
import app.tubehaus.ui.Speech
import app.tubehaus.ui.VideoPlayer
import app.tubehaus.web.WebFs
import app.tubehaus.web.toArrayBuffer
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import okio.Path
import okio.Path.Companion.toPath
import org.khronos.webgl.ArrayBuffer
import kotlin.js.Promise

/** What the browser supplies to the shared app. */
class WebPlatform(fontData: ByteArray?) : Platform {
    override val name = "web"
    override val dataDir: Path = WebFs.KEEP.toPath()
    override val cacheDir: Path = "/tubeless/cache".toPath()
    override val touch = jsTouch()
    override val instant = jsInstant()
    private val web = WebPlayer()
    override val player: VideoPlayer = web
    override val font: FontFamily? = fontData?.let { data ->
        runCatching {
            FontFamily(listOf(400, 500, 600, 700, 800).map { w ->
                Font("Roboto-$w", data, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
            })
        }.getOrNull()
    }
    override val canPip get() = true
    override fun startPip() = web.pip()

    override fun share(title: String, url: String): Boolean = jsShare(title, url)
    override fun openExternal(url: String) = jsOpen(url)
    override fun saveFile(name: String, mime: String, bytes: ByteArray, done: (Boolean) -> Unit) { jsSave(name, mime, bytes.toArrayBuffer()); done(true) }
    override fun openTextFile(done: (String?) -> Unit) { MainScope().launch { done(jsPick(false).await<JsAny?>()?.let { jsFirstText(it) }) } }
    override fun openTextFiles(done: (List<Pair<String, String>>) -> Unit) {
        MainScope().launch {
            val all = jsPick(true).await<JsAny?>() ?: return@launch done(emptyList())
            done((0 until jsLen(all)).map { jsName(all, it) to jsText(all, it) })
        }
    }
    override fun setFullscreen(on: Boolean) = jsFullscreen(on)
    override fun webSavePrepare(id: String) = jsSavePrepare(id)
    override fun webSave(id: String, title: String, done: (String) -> Unit) {
        val p = jsWebSave(id, title)   // called now: the browser counts the click only for a moment
        MainScope().launch { done(runCatching { p.await<JsString>().toString() }.getOrElse { "error:" + (it.message ?: "") }) }
    }
    override val knowsMetered = true
    override fun metered(): Boolean = jsMetered()
    /** the browser's Web Speech API (Chrome, Edge, Safari); none in Firefox: no mic button there, as on youtube.com */
    override val speech: Speech? = if (jsCanListen()) WebSpeech else null
}

private object WebSpeech : Speech {
    override fun start(lang: String, partial: (String) -> Unit, ended: (text: String?, error: String?) -> Unit) {
        jsListen(lang, { p -> partial(p) }) { t, e -> ended(t.ifEmpty { null }, e.ifEmpty { null }) }
    }
    override fun stop() = jsListenStop()
}

private fun jsCanListen(): Boolean = js("!!(window.SpeechRecognition || window.webkitSpeechRecognition)")
private fun jsListen(lang: String, partial: (String) -> Unit, ended: (String, String) -> Unit): Unit = js("""{
  var R = window.SpeechRecognition || window.webkitSpeechRecognition; var r = new R(); window.__tlRec = r;
  r.lang = lang; r.interimResults = true; r.continuous = false; var last = ''; var err = ''; var done = false;
  r.onresult = function(e){ var t = ''; for (var i = 0; i < e.results.length; i++) t += e.results[i][0].transcript; last = t; partial(t) };
  r.onerror = function(e){ err = (e.error == 'not-allowed' || e.error == 'service-not-allowed') ? 'The microphone is blocked for this page.' : (e.error == 'no-speech' ? '' : 'Voice search failed: ' + e.error) };
  r.onstart = function(){ partial('') };
  r.onend = function(){ if (!done) { done = true; ended(last, err) } };
  try { r.start() } catch (x) { done = true; ended('', 'Voice search failed.') }
}""")
private fun jsListenStop(): Unit = js("{ if (window.__tlRec) window.__tlRec.stop() }")

/** the website without the extension (site/instant.js sets tubeless.instant) */
fun jsInstant(): Boolean = js("!!tubeless.instant")
private fun jsTouch(): Boolean = js("matchMedia('(pointer: coarse)').matches")
private fun jsShare(title: String, url: String): Boolean =
    js("(function(){ if (navigator.share) { navigator.share({ title: title, url: url }).catch(function(){}); return true } navigator.clipboard && navigator.clipboard.writeText(url); return false })()")
private fun jsOpen(url: String): Unit = js("{ window.open(url, '_blank') }")
private fun jsFullscreen(on: Boolean): Unit = js("{ if (on) document.documentElement.requestFullscreen().catch(function(){}); else if (document.fullscreenElement) document.exitFullscreen() }")
private fun jsMetered(): Boolean = js("!!(navigator.connection && navigator.connection.saveData)")
private fun jsSave(name: String, mime: String, b: ArrayBuffer): Unit =
    js("{ var a = document.createElement('a'); a.href = URL.createObjectURL(new Blob([b], { type: mime })); a.download = name; a.click(); setTimeout(function(){ URL.revokeObjectURL(a.href) }, 10000) }")
private fun jsPick(many: Boolean): Promise<JsAny?> = js("""new Promise(function(ok){ var i = document.createElement('input'); i.type = 'file'; i.multiple = many;
  i.onchange = function(){ Promise.all(Array.from(i.files).map(function(f){ return f.text().then(function(t){ return [f.name, t] }) })).then(ok) };
  i.oncancel = function(){ ok(null) }; i.click() })""")
private fun jsSavePrepare(id: String): Unit = js("{ if (tubeless.save) tubeless.save.prepare(id) }")
private fun jsWebSave(id: String, title: String): Promise<JsString> =
    js("tubeless.save ? tubeless.save.go(id, title).catch(function(e){ return 'error:' + ((e && e.message) || e) }) : Promise.resolve('error:')")
private fun jsLen(a: JsAny): Int = js("a.length")
private fun jsName(a: JsAny, i: Int): String = js("a[i][0]")
private fun jsText(a: JsAny, i: Int): String = js("a[i][1]")
private fun jsFirstText(a: JsAny): String? = js("a.length ? a[0][1] : null")
