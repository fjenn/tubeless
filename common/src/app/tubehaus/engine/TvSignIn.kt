package app.tubehaus.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Signing in with a code, the way YouTube's TV app does it (the website's Instant mode, where no youtube.com cookie
 * can reach YouTube). YouTube's own TV client asks Google for a code, the user enters it at google.com/device on
 * any phone or computer and allows it, and Google hands this page a token. Every endpoint here answers any web
 * origin (CORS), so no server of ours is involved. The access token rides the WEB-family InnerTube calls
 * ([InnerTube.signs], [Accounts.useToken]) as "Authorization: Bearer"; on the website, site/instant.js moves that
 * header into the `$httpHeaders` query parameter, because a real header needs a preflight YouTube refuses.
 * Only the refresh token is kept ([setup]'s store); access tokens live in memory and are renewed before they expire.
 */
object TvSignIn {
    // YouTube's TV app (youtube.com/tv), the client its own "sign in with a code" uses; both values are public in its script
    private const val CLIENT_ID = "861556708454-d6dlm3lh05idd8npek18k6be8ba3oc68.apps.googleusercontent.com"
    private const val TV_APP_SECRET = "SboVhoG9s0rNafixCSGGKXAT"
    private const val SCOPE = "http://gdata.youtube.com https://www.googleapis.com/auth/youtube-paid-content"
    private const val OAUTH = "https://oauth2.googleapis.com"
    private const val KEY = "tv-signin"

    /** what the user is shown: [user] to type at [url] */
    class Code(val user: String, val url: String, internal val device: String, internal val interval: Int, internal val expiresAt: Long)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()
    private var store: SecretStore? = null
    /** a form POST that gives the status with the answer (the website: Net.raw) */
    private var post: (suspend (url: String, form: String) -> Pair<Int, String>)? = null
    @Volatile private var refreshToken: String? = null
    @Volatile private var access: String? = null
    @Volatile private var expiresAt = 0L
    private var keeper: Job? = null

    /** whether this platform signs in this way (set by [setup]: the website's Instant mode) */
    val available: Boolean get() = store != null
    val signedIn: Boolean get() = refreshToken != null

    private fun now() = Sys.nowMs() / 1000
    private fun log(m: String) = Accounts.log("tv sign-in: $m")

    /** once at start: where the refresh token is kept and how to POST a form; signs in again with a kept token */
    fun setup(secrets: SecretStore, formPost: suspend (url: String, form: String) -> Pair<Int, String>) {
        store = secrets
        post = formPost
        refreshToken = runCatching { secrets.read(KEY) }.getOrNull()?.takeIf { it.isNotBlank() }
        if (refreshToken != null) begin()
    }

    /** step 1: a code to show */
    suspend fun start(): Code {
        val (st, text) = form("$OAUTH/device/code", "client_id" to CLIENT_ID, "scope" to SCOPE)
        val d = parse(text)
        val device = d.at("device_code").str
        if (st != 200 || device == null) throw AccountError("Google didn't give a sign-in code (${d.at("error").str ?: st})")
        return Code(d.at("user_code").str ?: "", d.at("verification_url").str ?: "https://www.google.com/device", device,
            (d.at("interval").long ?: 5).toInt().coerceAtLeast(2), now() + (d.at("expires_in").long ?: 1800))
    }

    /** step 2: waits until the code is allowed (true), refused or expired (an error); cancel the coroutine to stop */
    suspend fun await(code: Code): Boolean {
        var wait = code.interval
        while (now() < code.expiresAt) {
            delay(wait * 1000L)
            val (st, text) = form("$OAUTH/token", "client_id" to CLIENT_ID, "client_secret" to TV_APP_SECRET,
                "code" to code.device, "grant_type" to "http://oauth.net/grant_type/device/1.0")
            val d = parse(text)
            when (d.at("error").str) {
                null -> if (st == 200 && take(d)) { store?.write(KEY, refreshToken); Accounts.useToken(::token); begin(); return true }
                "authorization_pending" -> {}
                "slow_down" -> wait += 2
                "access_denied" -> throw AccountError("Sign-in was declined")
                "expired_token" -> break
                else -> throw AccountError("Google refused the sign-in (${d.at("error").str})")
            }
        }
        throw AccountError("The code expired. Try again.")
    }

    suspend fun signOut() {
        val t = refreshToken
        lock.withLock {
            keeper?.cancel(); keeper = null
            refreshToken = null; access = null; expiresAt = 0
            store?.write(KEY, null)
        }
        Accounts.useToken(null)
        // let Google forget it too (best effort)
        if (t != null) runCatching { form("$OAUTH/revoke", "token" to t) }
    }

    /** the access token for a call right now; null while there is none */
    fun token(): String? = access?.takeIf { now() < expiresAt }

    /** YouTube answered a signed call 401: get a new access token now */
    fun rejected() {
        val t = now()
        if (!signedIn || t - lastForced < 60) return
        lastForced = t
        scope.launch { renew(force = true) }
    }
    private var lastForced = 0L

    private fun begin() {
        keeper?.cancel()
        keeper = scope.launch {
            while (refreshToken != null) {
                renew()
                // renewed five minutes before it runs out; a failed try is retried in a minute
                delay(if (token() != null) ((expiresAt - now() - 300).coerceAtLeast(60)) * 1000 else 60_000L)
            }
        }
    }

    private suspend fun renew(force: Boolean = false) = lock.withLock {
        val r = refreshToken ?: return@withLock
        if (!force && token() != null && expiresAt - now() > 300) return@withLock
        val (st, text) = runCatching {
            form("$OAUTH/token", "client_id" to CLIENT_ID, "client_secret" to TV_APP_SECRET, "refresh_token" to r, "grant_type" to "refresh_token")
        }.getOrElse { log("renew: no connection"); return@withLock }
        val d = runCatching { parse(text) }.getOrElse { log("renew: $st"); return@withLock }
        if (st == 200 && take(d)) { Accounts.useToken(::token); return@withLock }
        log("renew: ${st} ${d.at("error").str}")
        // the user took the access back (or Google retired it): signed out
        if (d.at("error").str == "invalid_grant") {
            refreshToken = null; access = null; store?.write(KEY, null); keeper = null
            Accounts.useToken(null)
        }
    }

    private fun take(d: kotlinx.serialization.json.JsonElement): Boolean {
        val a = d.at("access_token").str ?: return false
        access = a
        expiresAt = now() + (d.at("expires_in").long ?: 3600)
        d.at("refresh_token").str?.let { refreshToken = it }
        return true
    }

    private suspend fun form(url: String, vararg f: Pair<String, String>): Pair<Int, String> {
        val body = f.joinToString("&") { (k, v) -> "$k=${enc(v)}" }
        return post?.invoke(url, body) ?: throw AccountError("Sign-in isn't available here")
    }

    private fun enc(s: String) = buildString {
        for (b in s.encodeToByteArray()) {
            val c = b.toInt().toChar()
            if (c.isLetterOrDigit() && c.code < 128 || c in "-._~") append(c) else append('%').append(((b.toInt() and 0xff) + 0x100).toString(16).substring(1).uppercase())
        }
    }

}
