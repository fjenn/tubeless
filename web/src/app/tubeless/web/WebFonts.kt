package app.tubeless.web

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import app.tubehaus.web.jsLog
import app.tubehaus.web.toByteArray
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import org.khronos.webgl.ArrayBuffer

/**
 * Compose on the web has no system fonts: Roboto (built in) covers Latin, Cyrillic and Greek,
 * Pretendard (built in, preloaded as a fallback) Korean. Other scripts get a Noto family the first time they appear, in a YouTube answer
 * (tubeless.js scans them) or as the app's language: fetched once (the browser caches it) and
 * preloaded into Compose's font resolver, which then falls back to it for those characters.
 */
object WebFonts {
    var resolver: FontFamily.Resolver? = null
    private val pending = ArrayList<Pair<String, ByteArray>>()

    fun start() = jsOnNeed { name, buf -> got(name, buf) }

    /** the app's language needs its script now, not when the first answer shows it */
    fun forLanguage(lang: String) {
        when (lang.substringBefore('-')) {
            "ja" -> jsNeed("jp"); "zh" -> jsNeed("sc"); "hi", "mr", "ne" -> jsNeed("deva"); "ar", "fa", "ur" -> jsNeed("arab")
            "th" -> jsNeed("thai"); "he" -> jsNeed("hebr"); "bn" -> jsNeed("beng"); "ta" -> jsNeed("taml")
        }
    }

    private fun got(name: String, buf: ArrayBuffer) {
        val bytes = buf.toByteArray()
        jsLog("font $name: ${bytes.size} bytes")
        val r = resolver ?: run { pending += name to bytes; return }
        preload(r, name, bytes)
    }

    /** a built-in font Compose falls back to for the characters the app's font lacks */
    fun fallback(name: String, bytes: ByteArray) = resolver?.let { preload(it, name, bytes) } ?: run { pending += name to bytes }

    /** the fonts waiting so far are preloaded before this returns (the first screen is laid out with its fallbacks) */
    suspend fun ready(r: FontFamily.Resolver) {
        resolver = r
        val now = pending.toList()
        pending.clear()
        for ((n, b) in now) runCatching { r.preload(FontFamily(Font("noto-$n", b))) }.onFailure { jsLog("font $n: $it") }
    }

    private fun preload(r: FontFamily.Resolver, name: String, bytes: ByteArray) {
        MainScope().launch { runCatching { r.preload(FontFamily(Font("noto-$name", bytes))) }.onFailure { jsLog("font $name: $it") } }
    }
}

private fun jsOnNeed(f: (String, ArrayBuffer) -> Unit): Unit = js("{ tubeless.fonts.onNeed = f }")
private fun jsNeed(k: String): Unit = js("tubeless.fonts.need(k)")
