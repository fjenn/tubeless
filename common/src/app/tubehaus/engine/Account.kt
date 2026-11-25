package app.tubehaus.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.ByteString.Companion.encodeUtf8

// The optional YouTube account (docs/PLAN.md §1.3, stream S3).
//
// Sign-in is Google's own page in a web view the platform opens (desktop: tools/signin-helper, Android:
// SignInActivity, iOS: SignIn.kt). The user types into Google, never into the app; the app only reads the
// youtube.com cookies the page ends with. Those cookies are the session: every signed call sends them plus
// Authorization: SAPISIDHASH, the header youtube.com's own web client computes from the SAPISID cookie.
//
// Signed-in rule: only WEB-family calls to www.youtube.com carry the account (feeds, next, actions,
// playback-tracking pings), see InnerTube.signs. The stream clients stay anonymous.
//
// Google rotates the short-lived cookies (__Secure-*PSIDTS) about hourly. The rotated values come back as
// Set-Cookie on signed calls (Net hands them to [Accounts.capture]); when YouTube stops accepting the jar,
// the platform's web view, which kept its own Google session, is reloaded hidden to mint a fresh one.

/** One cookie of the jar (youtube.com only). [expires] is epoch seconds, 0 for a session cookie. */
@Serializable
data class Ck(
    val name: String,
    val value: String,
    val domain: String = ".youtube.com",
    val path: String = "/",
    val expires: Long = 0,
    val secure: Boolean = true,
    val httpOnly: Boolean = false,
)

/** a YouTube session's cookies, as a browser keeps them for youtube.com */
class CookieJar(cookies: List<Ck> = emptyList()) {
    private val list = ArrayList<Ck>()

    init { cookies.forEach(::put) }

    val cookies: List<Ck> get() = list.toList()
    val isEmpty get() = list.isEmpty()

    private fun put(c: Ck) {
        if (!isYouTube(c.domain)) return
        list.removeAll { same(it, c) }
        list += c
    }

    /** name → value of what would be sent to [host] (the most specific domain wins a clash, as in a browser) */
    fun values(host: String = "www.youtube.com", nowS: Long = Sys.nowMs() / 1000): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (c in list.filter { matches(it.domain, host) && (it.expires == 0L || it.expires > nowS) }.sortedBy { it.domain.trimStart('.').length })
            out[c.name] = c.value
        return out
    }

    /** the Cookie header for [host] */
    fun header(host: String = "www.youtube.com", nowS: Long = Sys.nowMs() / 1000): String =
        values(host, nowS).entries.joinToString("; ") { "${it.key}=${it.value}" }

    /** the SAPISID the auth hash is made from; null = not a signed-in session */
    fun sapisid(nowS: Long = Sys.nowMs() / 1000): String? = values(nowS = nowS).let { v ->
        v["SAPISID"] ?: v["__Secure-3PAPISID"] ?: v["__Secure-1PAPISID"]
    }?.takeIf { it.isNotEmpty() }

    /**
     * Folds Set-Cookie lines from a response of [host] into the jar. Answers whether anything changed.
     * Deletions (Max-Age=0, an Expires in the past) remove the cookie.
     */
    fun apply(setCookies: List<String>, host: String, nowS: Long = Sys.nowMs() / 1000): Boolean {
        var changed = false
        for (line in setCookies) {
            val c = parseSetCookie(line, host, nowS) ?: continue
            val old = list.firstOrNull { same(it, c) }
            if (c.expires in 1..nowS) {
                if (old != null) { list.remove(old); changed = true }
                continue
            }
            if (old == c) continue
            put(c); changed = true
        }
        return changed
    }

    /** same cookies, whatever order (a re-read jar is not a fresher one) */
    fun sameAs(other: CookieJar): Boolean = list.toSet() == other.list.toSet()

    companion object {
        fun isYouTube(domain: String): Boolean {
            val d = domain.trimStart('.').lowercase()
            return d == "youtube.com" || d.endsWith(".youtube.com")
        }

        fun matches(domain: String, host: String): Boolean {
            val d = domain.trimStart('.').lowercase()
            val h = host.lowercase()
            return h == d || h.endsWith(".$d")
        }

        private fun same(a: Ck, b: Ck) = a.name == b.name && a.domain.trimStart('.') == b.domain.trimStart('.') && a.path == b.path

        /** a `Cookie:` header ("a=1; b=2"), taken as youtube.com cookies */
        fun fromHeader(header: String): CookieJar = CookieJar(header.split(';').mapNotNull { kv ->
            val i = kv.indexOf('=')
            if (i <= 0) null else Ck(kv.substring(0, i).trim(), kv.substring(i + 1).trim())
        })

        /** a Netscape cookies.txt (browser extensions, yt-dlp); its youtube.com lines only */
        fun fromNetscape(text: String): CookieJar = CookieJar(text.lineSequence().mapNotNull { raw ->
            val http = raw.startsWith("#HttpOnly_")
            val line = raw.removePrefix("#HttpOnly_").trimEnd('\r')
            if (line.isBlank() || line.startsWith("#")) return@mapNotNull null
            val f = line.split('\t')
            if (f.size < 7) return@mapNotNull null
            Ck(f[5], f[6], f[0], f[2].ifEmpty { "/" }, f[4].toLongOrNull() ?: 0, f[3].equals("TRUE", true), http)
        }.toList())

        /** cookies.txt or a bare Cookie header, whichever [text] is */
        fun fromText(text: String): CookieJar =
            if (text.contains('\t')) fromNetscape(text) else fromHeader(text.trim().removePrefix("Cookie:").trim())

        /** one Set-Cookie line; null when it isn't for youtube.com or doesn't parse */
        fun parseSetCookie(line: String, host: String, nowS: Long): Ck? {
            val parts = line.split(';')
            val nv = parts[0]
            val i = nv.indexOf('=')
            if (i <= 0) return null
            val name = nv.substring(0, i).trim()
            val value = nv.substring(i + 1).trim().removeSurrounding("\"")
            var domain: String? = null
            var path = "/"
            var expires = 0L
            var maxAge: Long? = null
            var secure = false
            var http = false
            for (p in parts.drop(1)) {
                val k = p.substringBefore('=').trim().lowercase()
                val v = p.substringAfter('=', "").trim()
                when (k) {
                    "domain" -> domain = v.lowercase()
                    "path" -> path = v.ifEmpty { "/" }
                    "expires" -> expires = httpDate(v) ?: expires
                    "max-age" -> maxAge = v.toLongOrNull()
                    "secure" -> secure = true
                    "httponly" -> http = true
                }
            }
            if (maxAge != null) expires = if (maxAge!! <= 0) 1 else nowS + maxAge!!
            val d = domain?.let { if (it.startsWith(".")) it else ".$it" } ?: host.lowercase()
            // a server can only set cookies for its own domain
            if (!isYouTube(d) || !matches(d, host)) return null
            return Ck(name, value, d, path, expires, secure, http)
        }

        private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

        /** "Wed, 21 Oct 2026 07:28:00 GMT" (or with dashes) → epoch seconds */
        fun httpDate(s: String): Long? {
            val t = s.replace('-', ' ').split(' ', ',').filter { it.isNotBlank() }
            val day = t.firstOrNull { it.length <= 2 && it.all(Char::isDigit) }?.toInt() ?: return null
            val mon = t.firstNotNullOfOrNull { w -> MONTHS.indexOf(w.take(3).lowercase()).takeIf { it >= 0 && w.length >= 3 && w[0].isLetter() } } ?: return null
            var year = t.firstOrNull { it.length == 4 && it.all(Char::isDigit) }?.toInt()
                ?: t.firstOrNull { it.length == 2 && it.all(Char::isDigit) && it.toInt() != day }?.toInt()?.let { if (it < 70) 2000 + it else 1900 + it }
                ?: return null
            if (year < 100) year += 2000
            val hms = t.firstOrNull { it.count { c -> c == ':' } == 2 }?.split(':')?.map { it.toIntOrNull() ?: 0 } ?: listOf(0, 0, 0)
            return daysFromCivil(year, mon + 1, day) * 86400 + hms[0] * 3600L + hms[1] * 60L + hms[2]
        }

        private fun daysFromCivil(y0: Int, m: Int, d: Int): Long {
            val y = if (m <= 2) y0 - 1 else y0
            val era = (if (y >= 0) y else y - 399) / 400
            val yoe = y - era * 400
            val doy = (153 * (m + (if (m > 2) -3 else 9)) + 2) / 5 + d - 1
            val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
            return era * 146097L + doe - 719468
        }

        /**
         * Splits a Set-Cookie value that a client joined with commas (Apple's URL loading does) back into lines.
         * Commas inside an Expires date ("Wed, 21 Oct") are not followed by "name=", so they stay.
         */
        fun splitJoined(v: String): List<String> = v.split(Regex(""",\s*(?=[^;,\s=]+=)""")).map { it.trim() }.filter { it.isNotEmpty() }
    }
}

/** The Authorization header youtube.com's web client sends on signed calls. */
object SapisidHash {
    fun sha1Hex(s: String): String = s.encodeUtf8().sha1().hex()

    /**
     * "SAPISIDHASH <ts>_<sha1(ts SAPISID origin)>" followed, when the jar has them, by the 1P and 3P
     * variants (yt-dlp `_get_sid_authorization_header`). With a [userSessionId] (datasync id) every part
     * becomes "<ts>_<sha1(u ts sid origin)>_u". Null when the jar has no SAPISID.
     */
    fun authorization(cookies: Map<String, String>, origin: String, ts: Long, userSessionId: String? = null): String? {
        val schemes = listOf(
            "SAPISIDHASH" to (cookies["SAPISID"] ?: cookies["__Secure-3PAPISID"]),
            "SAPISID1PHASH" to cookies["__Secure-1PAPISID"],
            "SAPISID3PHASH" to cookies["__Secure-3PAPISID"],
        )
        val out = schemes.mapNotNull { (scheme, sid) ->
            if (sid.isNullOrEmpty()) return@mapNotNull null
            val hashed = listOfNotNull(userSessionId, ts.toString(), sid, origin).joinToString(" ")
            val parts = listOfNotNull(ts.toString(), sha1Hex(hashed), userSessionId?.let { "u" })
            "$scheme ${parts.joinToString("_")}"
        }
        return out.takeIf { it.isNotEmpty() }?.joinToString(" ")
    }
}

/** One channel the signed-in Google account can act as (its own, or a brand channel), from account/accounts_list. */
@Serializable
data class Identity(
    val name: String,
    val handle: String? = null,
    val email: String? = null,
    val photo: String? = null,
    /** a brand channel's page id: sent as X-Goog-PageId / onBehalfOfUser */
    val pageId: String? = null,
    val gaiaId: String? = null,
    val datasyncId: String? = null,
    val hasChannel: Boolean = true,
    val selected: Boolean = false,
) {
    val key: String get() = pageId ?: gaiaId ?: handle ?: name
}

/** One Google sign-in: its cookies, its web view profile (for the refresh) and its channels. */
@Serializable
data class Session(
    val key: String,
    val cookies: List<Ck>,
    val identities: List<Identity> = emptyList(),
    /** the identity in use; null while a multi-channel account waits for its pick */
    val active: String? = null,
    val addedAt: Long = 0,
    /** where it came from: "webview" (refreshable) or "import" (cookies.txt) */
    val source: String = "webview",
) {
    val identity: Identity? get() = identities.firstOrNull { it.key == active }
}

@Serializable
data class AccountsData(
    val sessions: List<Session> = emptyList(),
    val current: String? = null,
    /** age-restricted, members-only and Premium-HLS streams may be asked for signed in (PLAN §1.3 exceptions) */
    val signedPlayback: Boolean = false,
)

/** Where the accounts are kept: the system's secret store (Keychain, Android Keystore, libsecret, DPAPI). */
interface SecretStore {
    fun read(name: String): String?
    /** null deletes */
    fun write(name: String, value: String?)
}

/** what a secret store does in tests and where there is none: memory only */
class MemorySecrets : SecretStore {
    private val m = HashMap<String, String>()
    override fun read(name: String) = m[name]
    override fun write(name: String, value: String?) { if (value == null) m.remove(name) else m[name] = value }
}

/** The platform's sign-in web view. */
interface SignInLauncher {
    /** whether this platform can open it (else only cookies.txt import is offered) */
    val available: Boolean get() = true
    /**
     * Google's sign-in page in a web view with its own cookie store [profile], until youtube.com holds a
     * session (SAPISID). [add]: Google's "add account" page (a fresh login, not the one already in the store).
     * Answers the youtube.com cookies; null when the user closed it. Throws with a message on failure.
     */
    suspend fun signIn(profile: String, add: Boolean): List<Ck>?
    /** loads youtube.com hidden in the [profile] store so Google rotates the session; its cookies, or null */
    suspend fun refresh(profile: String): List<Ck>? = null
    /** forgets the web view store of [profile] (sign-out) */
    suspend fun forget(profile: String) {}
    /** closes a sign-in page that is open (the in-app page's back button) */
    fun cancel() {}
}

class AccountError(msg: String) : Exception(msg)

object Accounts {
    private const val SECRET = "accounts"
    private const val REFRESH_COOLDOWN_S = 5 * 60L

    @Volatile var secrets: SecretStore = MemorySecrets()
        private set
    @Volatile var launcher: SignInLauncher? = null
    private val _state = MutableStateFlow(AccountsData())
    val state: StateFlow<AccountsData> = _state
    /** a fresh sign-in whose Google account has several channels: the UI asks which one */
    private val _pending = MutableStateFlow<Session?>(null)
    val pending: StateFlow<Session?> = _pending
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    /**
     * The in-app sign-in page (desktop: Google's page inside the main window): null when none is open, else
     * "loading" | "google" (Google's page shows) | "finishing" (YouTube's hand-off, the page hidden). The UI
     * draws the page's frame while it is set and reports the area the page goes in through [signInArea].
     */
    val signInScreen = MutableStateFlow<String?>(null)
    /** where the in-app sign-in page goes: x, y, width, height in the window, in dp (= points) */
    val signInArea = MutableStateFlow<List<Float>?>(null)

    /** a line for the app's sign-in log (desktop: ~/Library/Logs/tubeless/signin.log). Never gets cookie values. */
    @Volatile var log: (String) -> Unit = {}

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()
    @Volatile private var jar: CookieJar = CookieJar()
    @Volatile private var saveJob: Job? = null
    @Volatile private var lastRefresh = 0L
    /** the clock, in epoch seconds (tests set it) */
    var now: () -> Long = { Sys.nowMs() / 1000 }

    val current: Session? get() = _state.value.let { d -> d.sessions.firstOrNull { it.key == d.current } }
    val identity: Identity? get() = current?.identity
    val signedIn: Boolean get() = identity != null && !jar.isEmpty

    /** once at start: where accounts are kept and the web view that signs in */
    fun setup(store: SecretStore, signIn: SignInLauncher?) {
        secrets = store
        launcher = signIn
        val raw = runCatching { store.read(SECRET) }.onFailure { log("secret read failed: ${it::class.simpleName}") }.getOrNull()
        val d = raw?.let { r -> runCatching { json.decodeFromString<AccountsData>(r) }.getOrNull() } ?: AccountsData()
        log("setup: secret ${if (raw == null) "absent" else "${raw.length} chars"}, sessions ${d.sessions.size}, signed in ${d.sessions.any { it.identity != null }}")
        _state.value = d
        activate()
        // a saved sign-in that can't be read back (older builds cut it off in the Keychain): the web view
        // still holds the Google session, so it is minted afresh from there instead of asking to sign in again
        if (raw != null && d.sessions.isEmpty()) {
            val profiles = Regex("acct-[0-9a-f]{16}").findAll(raw).map { it.value }.distinct().toList()
            log("setup: saved sign-in unreadable; trying ${profiles.size} web view profile(s)")
            if (profiles.isNotEmpty()) scope.launch { salvage(profiles) }
        }
    }

    private suspend fun salvage(profiles: List<String>) {
        val l = launcher ?: return
        for (p in profiles) {
            val cookies = runCatching { l.refresh(p) }.getOrNull()
            log("salvage $p: ${if (cookies == null) "no session" else "${cookies.size} cookies"}")
            if (cookies == null) continue
            val ok = runCatching { finish(p, CookieJar(cookies), "webview") }.onFailure { log("salvage $p failed: ${it.message}") }.getOrDefault(false)
            if (ok) return
        }
    }

    /**
     * Called after every sign-in, sign-out, channel or account switch (and a browser session turning on or
     * off): the UI hooks AppState.accountChanged here (S4: feeds, mirror, subscriptions follow the account).
     */
    val onChange = mutableListOf<() -> Unit>()
    @Volatile private var lastSignature: String? = "init"

    private fun activate() {
        val s = current
        jar = CookieJar(s?.cookies ?: emptyList())
        val browserOn = browserSession
        when {
            browserOn -> {
                InnerTube.authHeaders = ::browserHeaders
                InnerTube.onBehalfOf = null
            }
            s?.identity != null && jar.sapisid(now()) != null -> {
                InnerTube.authHeaders = ::headers
                InnerTube.onBehalfOf = { identity?.pageId }
            }
            else -> {
                InnerTube.authHeaders = null
                InnerTube.onBehalfOf = null
            }
        }
        // listeners only when who is signed in really changed (not on a cookie rotation)
        val sig = if (browserOn) "browser" else s?.let { "${it.key}/${it.active}" }?.takeIf { InnerTube.authHeaders != null }
        if (sig != lastSignature) {
            log("session: ${if (sig == null) "signed out (visitor)" else "signed in"}")
            accountVisitor = null
            val first = lastSignature == "init"
            lastSignature = sig
            if (!first || sig != null) onChange.toList().forEach { runCatching { it() } }
        }
    }

    // ------------------------------------------------------------------ the extension: the browser's own session

    /** S8 (web/extension): reads the browser's youtube.com SAPISID; the browser sends the cookies itself */
    @Volatile private var browserSapisid: (() -> String?)? = null
    val browserSession: Boolean get() = browserSapisid?.invoke() != null || tokenSession

    /** Instant mode (the website): signed in with a code ([TvSignIn]); [token] is its current access token */
    @Volatile private var token: (() -> String?)? = null
    val tokenSession: Boolean get() = token != null

    /** [TvSignIn]'s session on (its access token) or off (null); the same signed-in rule as the browser's session */
    fun useToken(t: (() -> String?)?) {
        if ((t == null) == (token == null) && t != null) { token = t; return }
        token = t
        activate()
    }

    /**
     * The extension's sign-in (PLAN §1.3 web column): no web view and no stored cookies, the user is signed in
     * on youtube.com in this browser. [sapisid] reads that cookie (chrome.cookies); null turns it off. Same
     * signed-in rule and SAPISIDHASH as the app; only Authorization goes on the call (no Cookie header).
     * Call again (or [browserCookiesChanged]) when the browser's cookies change.
     */
    fun useBrowserSession(sapisid: (() -> String?)?) {
        browserSapisid = sapisid
        activate()
    }

    fun browserCookiesChanged() = activate()

    /** the browser session's own name, @handle and picture (account/accounts_list), for the You header and the menu */
    private val _browserIdentity = MutableStateFlow<Identity?>(null)
    val browserIdentity: StateFlow<Identity?> = _browserIdentity

    suspend fun loadBrowserIdentity() {
        if (browserSapisid?.invoke() == null || tokenSession) { _browserIdentity.value = null; return }
        val d = InnerTube.call("account/accounts_list", mapOf(
            "requestType" to "ACCOUNTS_LIST_REQUEST_TYPE_CHANNEL_SWITCHER", "callCircumstance" to "SWITCHING_USERS_FULL"), auth = true)
        _browserIdentity.value = parseAccountsList(d).let { l -> l.firstOrNull { it.selected } ?: l.firstOrNull() }
    }

    private fun browserHeaders(origin: String): Map<String, String> {
        token?.let { t -> return t()?.let { mapOf("Authorization" to "Bearer $it") } ?: emptyMap() }
        val sid = browserSapisid?.invoke() ?: return emptyMap()
        val auth = SapisidHash.authorization(mapOf("SAPISID" to sid), origin, now()) ?: return emptyMap()
        return mapOf("Authorization" to auth, "X-Goog-AuthUser" to "0", "X-Origin" to origin)
    }

    private fun persist(d: AccountsData) {
        _state.value = d
        runCatching { secrets.write(SECRET, if (d.sessions.isEmpty() && !d.signedPlayback) null else json.encodeToString(d)) }
            .onSuccess { log("saved: sessions ${d.sessions.size}") }
            .onFailure { log("SAVE FAILED: ${it::class.simpleName}: ${it.message}") }
    }

    /** The headers of a signed call from [origin] ("https://www.youtube.com"); empty when signed out. */
    fun headers(origin: String): Map<String, String> {
        val id = identity ?: return emptyMap()
        val t = now()
        val values = jar.values(origin.substringAfter("://").substringBefore('/'), t)
        val auth = SapisidHash.authorization(values, origin, t) ?: return emptyMap()
        return buildMap {
            put("Cookie", values.entries.joinToString("; ") { "${it.key}=${it.value}" })
            put("Authorization", auth)
            put("X-Goog-AuthUser", "0")
            put("X-Origin", origin)
            put("X-Youtube-Bootstrap-Logged-In", "true")
            id.pageId?.let { put("X-Goog-PageId", it) }
        }
    }

    /**
     * Set-Cookie lines from a signed response (Net calls this): the rotated cookies are kept, so the
     * session lives on without a new sign-in. Saved a few seconds later, once a burst has settled.
     */
    fun capture(url: String, setCookies: List<String>) {
        if (setCookies.isEmpty()) return
        val host = url.substringAfter("://").substringBefore('/').substringBefore(':')
        if (!CookieJar.isYouTube(host)) return
        if (InnerTube.authHeaders == null) { captureVisitor(host, setCookies); return }
        if (current == null) return
        val j = CookieJar(jar.cookies)
        if (!j.apply(setCookies, host, now())) return
        jar = j
        // cookie names only, never values
        log("rotation: ${setCookies.map { it.substringBefore('=').trim() }.distinct().joinToString(",")} from ${url.substringAfter(host).substringBefore('?')}")
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(3_000)
            lock.withLock { saveJar(jar) }
        }
    }

    private fun saveJar(j: CookieJar) {
        val d = _state.value
        val key = d.current ?: return
        persist(d.copy(sessions = d.sessions.map { if (it.key == key) it.copy(cookies = j.cookies) else it }))
    }

    // ------------------------------------------------------------------ the signed-out session (a visitor)

    /**
     * Signed out, the app is a logged-out browser that remembers you: one visitor cookie jar (VISITOR_INFO1_LIVE,
     * YSC, VISITOR_PRIVACY_METADATA…, as youtube.com sets them) kept across restarts and sent on every
     * WEB-family call and watch-time ping, so YouTube's signed-out recommendations follow what is watched here.
     * Kept in [sessionFile] (the app's data folder); not set (the browser extension) = the browser's own cookies.
     */
    @Volatile var sessionFile: okio.Path? = null
        set(v) { field = v; visitorJar = runCatching { v?.readTextOrNull()?.let { CookieJar(json.decodeFromString<List<Ck>>(it)) } }.getOrNull() ?: CookieJar() }
    @Volatile private var visitorJar = CookieJar()
    @Volatile private var visitorSave: Job? = null
    /** the visitorData YouTube gives the signed-in session (its responses' responseContext.visitorData) */
    @Volatile var accountVisitor: String? = null

    /** whether the signed-out session is on (the app, not the extension) */
    val visitorSession: Boolean get() = sessionFile != null

    /**
     * Instant mode (the website without the extension): a visitor without cookies. YouTube's signed-out Home and the
     * watch-time pings still go out, tied to the visitorData in each request's context (no cookie can be sent from there).
     */
    @Volatile var cookielessVisitor = false

    /** the signed-out session's Cookie header for www.youtube.com; null when empty or off */
    fun visitorCookie(): String? = if (!visitorSession) null else visitorJar.header(nowS = now()).ifEmpty { null }

    /**
     * The visitor identity in use (InnerTube's visitorData) is the jar's VISITOR_INFO1_LIVE too, so the
     * header, the request context and the cookie all name the same visitor (a reroll replaces all three).
     */
    fun visitorChanged(visitorData: String) {
        if (!visitorSession) return
        val id = visitorIdOf(visitorData) ?: return
        if (visitorJar.values()["VISITOR_INFO1_LIVE"] == id) return
        val j = CookieJar(visitorJar.cookies.filter { it.name != "VISITOR_INFO1_LIVE" && it.name != "VISITOR_PRIVACY_METADATA" } +
            Ck("VISITOR_INFO1_LIVE", id, ".youtube.com", "/", now() + 180 * 86400L, secure = true, httpOnly = true))
        visitorJar = j
        saveVisitorSoon()
    }

    private fun captureVisitor(host: String, setCookies: List<String>) {
        if (!visitorSession) return
        val j = CookieJar(visitorJar.cookies)
        if (!j.apply(setCookies, host, now())) return
        // a stray account cookie never turns the visitor into a half-signed-in session
        visitorJar = CookieJar(j.cookies.filter { it.name !in ACCOUNT_COOKIES })
        saveVisitorSoon()
    }

    private val ACCOUNT_COOKIES = setOf("SID", "HSID", "SSID", "APISID", "SAPISID", "LOGIN_INFO", "__Secure-1PSID", "__Secure-3PSID",
        "__Secure-1PAPISID", "__Secure-3PAPISID", "__Secure-1PSIDTS", "__Secure-3PSIDTS", "__Secure-1PSIDCC", "__Secure-3PSIDCC", "SIDCC")

    private fun saveVisitorSoon() {
        visitorSave?.cancel()
        visitorSave = scope.launch {
            delay(2_000)
            runCatching { sessionFile?.writeText(json.encodeToString(visitorJar.cookies)) }
        }
    }

    /** VISITOR_INFO1_LIVE inside a visitorData (protobuf field 1, base64url) */
    fun visitorIdOf(visitorData: String): String? = runCatching {
        val b = okio.ByteString.Companion.run { urlDecode(visitorData).decodeBase64() } ?: return null
        if (b.size < 3 || b[0].toInt() != 0x0A) return null
        val n = b[1].toInt()
        if (n <= 0 || n + 2 > b.size) return null
        b.substring(2, 2 + n).utf8().takeIf { s -> s.all { it.isLetterOrDigit() || it == '-' || it == '_' } }
    }.getOrNull()

    /** The cookies of the session in use (S4's eval tools, the extension bridge); empty when signed out. */
    fun cookies(): List<Ck> = jar.cookies

    // ------------------------------------------------------------------ signing in

    /** a new web view profile name: each Google sign-in keeps its own cookie store */
    private fun newProfile(): String = "acct-" + SapisidHash.sha1Hex("${Sys.nowMs()}-${kotlin.random.Random.nextLong()}").take(16)

    /**
     * Opens the platform's sign-in page and signs in with what it ends with. [add]: another Google
     * account next to the ones saved. Answers false when the user closed the page. A Google account with
     * several channels ends in [pending] until [choose] is called.
     */
    suspend fun signIn(add: Boolean = false): Boolean {
        val l = launcher ?: throw AccountError("Sign-in isn't available here. Import a cookies.txt instead.")
        // a sign-in again to the account in use reuses its profile (Google remembers it, one click)
        val profile = if (add || current?.source != "webview") newProfile() else current!!.key
        _busy.value = true
        log("sign-in: start (${if (add) "add" else "sign in"}, ${l::class.simpleName})")
        try {
            val cookies = l.signIn(profile, add)
            log("sign-in: page answered ${if (cookies == null) "cancelled" else "${cookies.size} cookies, SAPISID ${CookieJar(cookies).sapisid(now()) != null}"}")
            if (cookies == null) return false
            return finish(profile, CookieJar(cookies), "webview")
        } catch (e: Exception) {
            log("sign-in: failed ${e::class.simpleName}: ${e.message}")
            throw e
        } finally { _busy.value = false }
    }

    /** the fallback: a cookies.txt (Netscape format) or a Cookie header the user exported themselves */
    suspend fun import(text: String): Boolean {
        val j = CookieJar.fromText(text)
        if (j.sapisid(now()) == null) throw AccountError("No YouTube sign-in in that file (it has no SAPISID cookie for youtube.com).")
        _busy.value = true
        try { return finish(newProfile(), j, "import") } finally { _busy.value = false }
    }

    private suspend fun finish(profile: String, j: CookieJar, source: String): Boolean {
        if (j.sapisid(now()) == null) throw AccountError("Google signed you in, but YouTube didn't hand over a session. Try again.")
        val ids = listIdentities(j)
        log("sign-in: accounts_list gave ${ids.size} channel(s)")
        if (ids.isEmpty()) throw AccountError("YouTube didn't accept the session. Try again in a moment.")
        val chosen = when {
            ids.size == 1 -> ids[0]
            else -> null
        }
        val s = Session(profile, j.cookies, ids, chosen?.key, now(), source)
        if (chosen == null) { _pending.value = s; return true }
        save(s)
        return true
    }

    private suspend fun save(s: Session) = lock.withLock {
        val d = _state.value
        // the same Google account signed in again: its old session goes
        val ids = s.identities.mapNotNull { it.gaiaId }.toSet()
        val rest = d.sessions.filter { o -> o.key != s.key && (ids.isEmpty() || o.identities.none { it.gaiaId in ids }) }
        persist(d.copy(sessions = rest + s, current = s.key))
        activate()
    }

    /** the channel picked for a [pending] sign-in, or another channel of a saved session */
    suspend fun choose(sessionKey: String, identityKey: String) {
        val p = _pending.value
        if (p != null && p.key == sessionKey) {
            _pending.value = null
            save(p.copy(active = identityKey))
            return
        }
        lock.withLock {
            val d = _state.value
            persist(d.copy(current = sessionKey, sessions = d.sessions.map { if (it.key == sessionKey) it.copy(active = identityKey) else it }))
            activate()
        }
    }

    fun cancelPending() { _pending.value = null }

    /** signs out of [sessionKey] (the one in use when null): its cookies and its web view store go */
    suspend fun signOut(sessionKey: String? = null) {
        val key = sessionKey ?: _state.value.current ?: return
        lock.withLock {
            val d = _state.value
            val rest = d.sessions.filter { it.key != key }
            persist(d.copy(sessions = rest, current = if (d.current == key) rest.lastOrNull()?.key else d.current))
            activate()
        }
        runCatching { launcher?.forget(key) }
    }

    fun setSignedPlayback(on: Boolean) = scope.launch { lock.withLock { persist(_state.value.copy(signedPlayback = on)) } }

    /** S2: whether a restricted stream (age, members, Premium HLS) may be asked for signed in */
    val signedPlayback: Boolean get() = _state.value.signedPlayback && signedIn

    /**
     * YouTube answered a signed call as signed out (401, or loggedOut in its response): reload youtube.com
     * in the session's own web view so Google rotates the cookies, at most once every five minutes.
     */
    fun sessionRejected() {
        if (tokenSession) { TvSignIn.rejected(); return }
        val s = current ?: return
        if (s.source != "webview") return
        val t = now()
        if (t - lastRefresh < REFRESH_COOLDOWN_S) return
        lastRefresh = t
        scope.launch { runCatching { refresh(s) } }
    }

    private suspend fun refresh(s: Session) {
        val fresh = CookieJar(launcher?.refresh(s.key) ?: return)
        val sid = fresh.sapisid(now()) ?: return
        // only the same Google account (the store could hold another one by now)
        if (sid != CookieJar(s.cookies).sapisid(now())) return
        if (fresh.sameAs(jar)) return
        lock.withLock {
            if (_state.value.current != s.key) return
            jar = fresh
            saveJar(fresh)
        }
    }

    // ------------------------------------------------------------------ account/accounts_list

    /** the channels this jar can act as (YouTube's channel switcher) */
    suspend fun listIdentities(j: CookieJar): List<Identity> {
        val origin = "https://www.youtube.com"
        val t = now()
        val values = j.values(nowS = t)
        val auth = SapisidHash.authorization(values, origin, t) ?: return emptyList()
        val h = mapOf(
            "Cookie" to values.entries.joinToString("; ") { "${it.key}=${it.value}" },
            "Authorization" to auth, "X-Goog-AuthUser" to "0", "X-Origin" to origin,
        )
        val body = buildJsonObject {
            put("requestType", "ACCOUNTS_LIST_REQUEST_TYPE_CHANNEL_SWITCHER")
            put("callCircumstance", "SWITCHING_USERS_FULL")
        }
        val d = InnerTube.callWith("account/accounts_list", body, h)
        return parseAccountsList(d)
    }

    /** account/accounts_list → the channels (lenient: YouTube nests these differently over time) */
    fun parseAccountsList(d: JsonElement): List<Identity> {
        val email = d.findAll("googleAccountHeaderRenderer", skip = emptySet()).firstNotNullOfOrNull { it.at("email").text }
        return d.findAll("accountItem", skip = emptySet()).plus(d.findAll("accountItemRenderer", skip = emptySet())).mapNotNull { a ->
            val name = a.at("accountName").text ?: return@mapNotNull null
            val tokens = a.at("serviceEndpoint.selectActiveIdentityEndpoint.supportedTokens").arr
            fun tok(path: String) = tokens.firstNotNullOfOrNull { it.at(path).str }
            val handle = a.at("channelHandle").text ?: a.at("accountByline").text?.takeIf { it.startsWith("@") }
            Identity(
                name = name,
                handle = handle,
                email = a.at("accountByline").text?.takeIf { '@' in it && !it.startsWith("@") } ?: email,
                photo = a.at("accountPhoto.thumbnails").arr.lastOrNull()?.at("url").str?.let { if (it.startsWith("//")) "https:$it" else it },
                pageId = tok("pageIdToken.pageId"),
                gaiaId = tok("accountStateToken.obfuscatedGaiaId"),
                datasyncId = tok("datasyncIdToken.datasyncIdToken"),
                hasChannel = a.at("hasChannel").str?.let { it == "true" } ?: true,
                selected = a.at("isSelected").bool,
            )
        }.distinctBy { it.key }
    }
}
