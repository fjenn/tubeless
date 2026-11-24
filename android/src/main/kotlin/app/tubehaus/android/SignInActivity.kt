package app.tubehaus.android

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import app.tubehaus.engine.Ck
import app.tubehaus.engine.CookieJar
import app.tubehaus.engine.SecretStore
import app.tubehaus.engine.SignInLauncher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

// Android sign-in (stream S3): Google's own page in a WebView (the user types into Google, never into the app).
// Once youtube.com holds a session (SAPISID) its cookies are read from the WebView's CookieManager and handed
// to Accounts, which keeps them encrypted with an Android Keystore key. Where the WebView supports profiles
// (androidx.webkit MULTI_PROFILE) each Google sign-in has its own cookie store, so it can be refreshed later
// without disturbing the others; elsewhere the default store is shared (and cleared for "add account").

private const val LOGIN_URL = "https://accounts.google.com/ServiceLogin?service=youtube&uilel=3&passive=true&continue=https%3A%2F%2Fwww.youtube.com%2Fsignin%3Faction_handle_signin%3Dtrue%26app%3Dm%26next%3Dhttps%253A%252F%252Fm.youtube.com%252F"
private const val ADD_URL = "https://accounts.google.com/AddSession?service=youtube&continue=https%3A%2F%2Fwww.youtube.com%2Fsignin%3Faction_handle_signin%3Dtrue%26app%3Dm%26next%3Dhttps%253A%252F%252Fm.youtube.com%252F"
private const val HANDOFF_URL = "https://www.youtube.com/signin?action_handle_signin=true&app=m&next=https%3A%2F%2Fm.youtube.com%2F"

private fun profiles() = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)

/** the cookie store of [profile] (its own where WebView has profiles) */
private fun cookieManager(profile: String): CookieManager =
    if (profiles()) ProfileStore.getInstance().getOrCreateProfile(profile).cookieManager else CookieManager.getInstance()

@SuppressLint("SetJavaScriptEnabled")
private fun setUp(web: WebView, profile: String) {
    if (profiles()) WebViewCompat.setProfile(web, profile)
    web.settings.javaScriptEnabled = true
    web.settings.domStorageEnabled = true
    // Google refuses sign-in in what it can tell is an embedded WebView ("; wv" in the user agent)
    web.settings.userAgentString = web.settings.userAgentString.replace("; wv", "").replace(Regex("""Version/\d+\.\d+ """), "")
    cookieManager(profile).setAcceptThirdPartyCookies(web, true)
}

private fun youtubeCookies(cm: CookieManager): List<Ck> =
    CookieJar.fromHeader(cm.getCookie("https://www.youtube.com") ?: "").cookies

private fun googleSession(cm: CookieManager): Boolean =
    (cm.getCookie("https://accounts.google.com") ?: "").split(';').any { it.trim().substringBefore('=') in setOf("SID", "__Secure-1PSID", "__Secure-3PSID") }

class SignInActivity : Activity() {
    companion object {
        /** the waiting [AndroidSignIn.signIn]; one sign-in at a time */
        @Volatile var result: CompletableDeferred<List<Ck>?>? = null
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var web: WebView
    private lateinit var profile: String
    private var handedOff = false
    private var seen = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        profile = intent.getStringExtra("profile") ?: "default"
        val add = intent.getBooleanExtra("add", false)
        web = WebView(this)
        setUp(web, profile)
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) { poll() }
            // only Google's own pages show: YouTube's hand-off runs hidden, then the app is signed in
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                val h = Uri.parse(url).host ?: ""
                view.visibility = if (h.isEmpty() || h == "google.com" || h.endsWith(".google.com")) android.view.View.VISIBLE else android.view.View.INVISIBLE
            }
        }
        val bar = Button(this).apply { text = "Cancel"; setOnClickListener { finish() } }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        })
        // no profiles: "add account" starts from an empty store, so Google shows a fresh sign-in
        if (add && !profiles()) CookieManager.getInstance().removeAllCookies(null)
        web.loadUrl(if (add) ADD_URL else LOGIN_URL)
        tick()
    }

    private fun tick() { main.postDelayed({ if (!isFinishing) { poll(); tick() } }, 1_000) }

    private fun poll() {
        val cm = cookieManager(profile)
        val host = Uri.parse(web.url ?: "").host ?: ""
        val yt = CookieJar.isYouTube(host)
        val cookies = youtubeCookies(cm)
        val signed = CookieJar(cookies).sapisid() != null
        if (signed && yt) {
            // the rest of the hand-off's cookies land a beat later
            if (++seen >= 2) { cm.flush(); result?.complete(youtubeCookies(cm)); finish() }
        } else if (yt && !signed && !handedOff && googleSession(cm)) {
            handedOff = true
            web.loadUrl(HANDOFF_URL)
        }
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        result?.complete(null)
        web.destroy()
        super.onDestroy()
    }
}

class AndroidSignIn(private val context: Context) : SignInLauncher {
    override suspend fun signIn(profile: String, add: Boolean): List<Ck>? {
        val d = CompletableDeferred<List<Ck>?>()
        SignInActivity.result?.complete(null)
        SignInActivity.result = d
        context.startActivity(Intent(context, SignInActivity::class.java)
            .putExtra("profile", profile).putExtra("add", add).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return d.await()
    }

    /** youtube.com loaded in a WebView nobody sees, from the profile's store: Google rotates the cookies */
    override suspend fun refresh(profile: String): List<Ck>? = withContext(Dispatchers.Main) {
        val web = WebView(context)
        try {
            setUp(web, profile)
            val loaded = CompletableDeferred<Unit>()
            web.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) { if (CookieJar.isYouTube(Uri.parse(url).host ?: "")) loaded.complete(Unit) }
            }
            web.loadUrl("https://www.youtube.com/")
            withTimeoutOrNull(45_000) { loaded.await() } ?: return@withContext null
            delay(2_000)
            youtubeCookies(cookieManager(profile)).takeIf { CookieJar(it).sapisid() != null }
        } finally {
            web.destroy()
        }
    }

    override suspend fun forget(profile: String) = withContext(Dispatchers.Main) {
        if (profiles()) {
            runCatching { ProfileStore.getInstance().deleteProfile(profile) }
                .onFailure { cookieManager(profile).removeAllCookies(null) }
        } else {
            CookieManager.getInstance().removeAllCookies(null)
        }
        Unit
    }
}

/** the accounts, encrypted with an AES key that never leaves the Android Keystore */
class KeystoreSecrets(context: Context) : SecretStore {
    private val dir = File(context.filesDir, "secrets")
    private val alias = "tubeless-accounts"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        return g.generateKey()
    }

    override fun read(name: String): String? = runCatching {
        val b = File(dir, "$name.enc").takeIf { it.isFile }?.readBytes() ?: return null
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, b, 0, 12))
        String(c.doFinal(b, 12, b.size - 12))
    }.getOrNull()

    override fun write(name: String, value: String?) {
        val f = File(dir, "$name.enc")
        if (value == null) { f.delete(); return }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        dir.mkdirs()
        f.writeBytes(c.iv + c.doFinal(value.toByteArray()))
    }
}
