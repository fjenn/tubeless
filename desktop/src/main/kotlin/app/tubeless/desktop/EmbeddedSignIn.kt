package app.tubeless.desktop

import app.tubeless.engine.AccountError
import app.tubeless.engine.Accounts
import app.tubeless.engine.Ck
import app.tubeless.engine.SignInLauncher
import app.tubeless.engine.at
import app.tubeless.engine.json
import app.tubeless.engine.parse
import app.tubeless.engine.str
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/**
 * macOS: Google's sign-in inside the app's own window (tools/signin-helper's libtubeless_webview, a WKWebView
 * put over the content area through JNA). The app draws the page's frame (AccountScreens' InAppSignIn) and
 * reports where the page goes ([Accounts.signInArea]); the page hides itself while YouTube's hand-off runs
 * and is removed once youtube.com holds the session. The refresh and the sign-out use the same library,
 * hidden. A profile kept only by the older separate sign-in window ([helper]) is refreshed through it.
 */
class EmbeddedSignIn(private val lib: Kw, private val windowTitle: String, private val helper: HelperSignIn?, private val storeTag: String = "") : SignInLauncher, app.tubeless.ui.InAppLauncherMarker {
    interface Kw : Library {
        fun kw_start(mode: Int, title: String, profile: String, x: Double, y: Double, w: Double, h: Double): Long
        fun kw_bounds(x: Double, y: Double, w: Double, h: Double)
        fun kw_cancel()
        fun kw_status(): Pointer?
        fun kw_free(p: Pointer?)
    }

    companion object {
        /** the library next to the app's resources, or the repo's build of tools/signin-helper */
        fun load(windowTitle: String, helper: HelperSignIn?, storeTag: String = ""): EmbeddedSignIn? {
            if (!System.getProperty("os.name").startsWith("Mac")) return null
            val name = "libtubeless_webview.dylib"
            val f = sequence {
                env("TUBELESS_WEBVIEW_LIB")?.let { yield(File(it)) }
                System.getProperty("compose.application.resources.dir")?.let { yield(File(it, name)) }
                var d: File? = File("").absoluteFile
                while (d != null) { yield(File(d, "tools/signin-helper/target/release/$name")); d = d.parentFile }
            }.firstOrNull { it.isFile } ?: return null
            return runCatching { EmbeddedSignIn(Native.load(f.path, Kw::class.java), windowTitle, helper, storeTag) }
                .onFailure { Accounts.log("in-app sign-in library didn't load: ${it.message}") }.getOrNull()
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    /** one page at a time (a refresh waits for a sign-in to end) */
    private val one = Mutex()

    private fun status(): kotlinx.serialization.json.JsonElement? {
        val p = lib.kw_status() ?: return null
        return try { parse(p.getString(0, "UTF-8")) } finally { lib.kw_free(p) }
    }

    /** starts [mode] and waits for its result line */
    private suspend fun run(mode: Int, profile: String, area: List<Float>?): kotlinx.serialization.json.JsonElement? = one.withLock {
        val a = area ?: listOf(0f, 0f, 1f, 1f)
        val gen = lib.kw_start(mode, windowTitle, storeTag + profile, a[0].toDouble(), a[1].toDouble(), a[2].toDouble(), a[3].toDouble())
        var lastPhase: String? = null
        var result: kotlinx.serialization.json.JsonElement? = null
        try {
            while (result == null) {
                delay(200)
                val s = status() ?: continue
                if (s.at("gen").str?.toLongOrNull() != gen) break
                val phase = s.at("phase").str
                if (mode <= 1 && phase != lastPhase) {
                    lastPhase = phase
                    Accounts.log("in-app page: $phase (page ${s.at("host").str ?: ""}, going to ${s.at("nav").str ?: ""}; ${s.at("diag").str ?: ""})")
                    if (phase != "done") Accounts.signInScreen.value = phase
                }
                result = s.at("result")?.takeIf { it.toString() != "null" }
            }
        } finally {
            // a cancelled wait (Cancel, leaving the page) closes the page too
            if (result == null) lib.kw_cancel()
        }
        result
    }

    private fun cookies(r: kotlinx.serialization.json.JsonElement?): List<Ck>? {
        if (r == null) return null
        if (r.at("ok").str != "true") {
            return when (val e = r.at("error").str) {
                "cancelled", "signed-out", null -> null
                "timeout" -> throw AccountError("Sign-in took too long. Try again.")
                else -> throw AccountError(e)
            }
        }
        return json.decodeFromString(ListSerializer(Ck.serializer()), r.at("cookies").toString())
    }

    override suspend fun signIn(profile: String, add: Boolean): List<Ck>? {
        Accounts.signInScreen.value = "loading"
        try {
            // the app lays out the page's frame first: its area is where the page goes
            val area = withTimeoutOrNull(3000) { Accounts.signInArea.first { it != null } }
            val follow = scope.launch {
                Accounts.signInArea.collect { a -> if (a != null) lib.kw_bounds(a[0].toDouble(), a[1].toDouble(), a[2].toDouble(), a[3].toDouble()) }
            }
            try {
                return cookies(run(if (add) 1 else 0, profile, area))
            } finally { follow.cancel() }
        } finally {
            Accounts.signInScreen.value = null
            Accounts.signInArea.value = null
        }
    }

    override fun cancel() { lib.kw_cancel() }

    override suspend fun refresh(profile: String): List<Ck>? {
        val mine = runCatching { cookies(run(2, profile, null)) }.getOrNull()
        if (mine != null) return mine
        // a sign-in made by the older separate window lives in that program's web view store
        return helper?.refresh(profile)
    }

    override suspend fun forget(profile: String) {
        runCatching { run(3, profile, null) }
        helper?.forget(profile)
    }
}

/** the sign-in log: ~/Library/Logs/tubeless/signin.log (macOS) or the data folder; names and states only, never cookie values */
fun signInLog(dataDir: File): (String) -> Unit {
    val dir = if (System.getProperty("os.name").startsWith("Mac")) File(System.getProperty("user.home"), "Library/Logs/tubeless") else File(dataDir, "logs")
    val f = File(dir, "signin.log")
    val fmt = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    return { line ->
        runCatching {
            dir.mkdirs()
            if (f.length() > 512 * 1024) f.renameTo(File(dir, "signin.log.1"))
            f.appendText("${java.time.LocalDateTime.now().format(fmt)} $line\n")
        }
    }
}

