package app.tubeless.web

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.window.ComposeViewport
import app.tubehaus.data.Store
import app.tubehaus.engine.Video
import app.tubehaus.ui.App
import app.tubehaus.ui.AppState
import app.tubehaus.web.WebFs
import app.tubehaus.web.jsLog
import app.tubehaus.web.jsQuery
import app.tubehaus.web.toByteArray
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import okio.Path.Companion.toPath
import org.khronos.webgl.ArrayBuffer
import org.w3c.fetch.Response

/** The extension's main page: the whole shared app (common/src) in a Compose canvas. */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    MainScope().launch {
        WebFs.load()
        WebFonts.start()
        // Compose on the web has no system fonts: Roboto comes with the extension, as on the desktop,
        // and Pretendard is preloaded as the fallback for Korean (the system's fonts do that elsewhere)
        suspend fun bytes(f: String) = runCatching { window.fetch(f).await<Response>().arrayBuffer().await<ArrayBuffer>().toByteArray() }.getOrNull()
        val font = bytes("RobotoVariable.ttf")
        bytes("PretendardVariable.ttf")?.let { WebFonts.fallback("pretendard", it) }
        val platform = WebPlatform(font)
        val state = AppState(platform, Store(platform.dataDir))
        WebAuth.onChange = { state.accountChanged() }
        WebAuth.watch()
        WebAuth.refresh()
        WebSettings.register(state)
        WebSabrHost.install()
        jsLog("app state ready")
        // test hooks: ?lang=en skips the first-run language picker, ?v=<id> opens a video,
        // ?sabr=1 plays every video the SABR way, ?signin=1 turns signed-in mode on
        if (jsQuery("sabr") == "1") app.tubehaus.engine.Sabr.only = true
        if (jsQuery("signin") == "1") WebAuth.on = true
        jsQuery("lang")?.takeIf { it.isNotEmpty() }?.let { l -> state.store.settings.update { it.copy(lang = l) } }
        jsQuery("v")?.takeIf { it.isNotEmpty() }?.let { state.watch(Video(it, "", "", "", 0)) }
        ComposeViewport(document.body!!) {
            val resolver = LocalFontFamilyResolver.current
            // the app is drawn once the fallback fonts are in (Roboto has no arrows or Hangul: Pretendard fills them),
            // or text laid out before them keeps empty boxes
            var fontsIn by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
            LaunchedEffect(resolver) { WebFonts.ready(resolver); fontsIn = true }
            val settings by state.store.settings.state.collectAsState()
            LaunchedEffect(settings.lang) { WebFonts.forLanguage(settings.lang) }
            if (fontsIn) App(state)
        }
        jsLog("composed")
    }
}
