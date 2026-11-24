package app.tubeless.web

import app.tubehaus.engine.InnerTube
import app.tubehaus.engine.Sys
import kotlinx.coroutines.await
import okio.ByteString.Companion.encodeUtf8
import kotlin.js.Promise

/**
 * Signed-in mode: the browser's own youtube.com session. Nothing to sign in to inside the app: the
 * user is signed in on youtube.com in this browser, the extension reads the SAPISID cookie
 * (cookies permission) and InnerTube.authHeaders adds SAPISIDHASH to www.youtube.com calls only;
 * tubeless.js then sends those calls with the browser's cookies. Stream clients stay anonymous.
 * Off by default (Settings → Account), kept in localStorage.
 */
object WebAuth {
    var on: Boolean
        get() = jsGetFlag()
        set(v) { jsSetFlag(v); apply() }

    /** whether the browser has a youtube.com session at all */
    val browserSignedIn: Boolean get() = jsSapisid() != null

    suspend fun refresh() { runCatching { jsRefresh().await<JsAny?>() }; apply() }

    /** called when signed-in mode turns on or off (Main: AppState.accountChanged, S4's switch for Home and the feeds) */
    var onChange: (() -> Unit)? = null
    private var applied = false

    fun apply() {
        val now = on && browserSignedIn
        InnerTube.authHeaders = if (now) ::headers else null
        if (now != applied) { applied = now; onChange?.invoke() }
    }

    /** the browser's youtube.com cookies changed (signed in or out there) */
    fun watch() = jsOnCookies { apply() }

    private fun headers(origin: String): Map<String, String> {
        val sapisid = jsSapisid() ?: return emptyMap()
        val ts = Sys.nowMs() / 1000
        return mapOf(
            "Authorization" to "SAPISIDHASH ${ts}_${"$ts $sapisid $origin".encodeUtf8().sha1().hex()}",
            "X-Goog-AuthUser" to "0",
            "X-Origin" to origin,
        )
    }

    fun openSignIn() = jsOpenSignIn()
}

private fun jsSapisid(): String? = js("tubeless.auth.sapisid")
private fun jsRefresh(): Promise<JsAny?> = js("tubeless.auth.refresh()")
private fun jsOpenSignIn(): Unit = js("tubeless.auth.openSignIn()")
private fun jsGetFlag(): Boolean = js("(function(){ try { return localStorage.getItem('tubeless.signedIn') === '1' } catch (e) { return false } })()")
private fun jsSetFlag(v: Boolean): Unit = js("{ try { localStorage.setItem('tubeless.signedIn', v ? '1' : '0') } catch (e) {} }")
private fun jsOnCookies(f: () -> Unit): Unit = js("{ tubeless.auth.onChange = f }")
