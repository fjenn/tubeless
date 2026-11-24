package app.tubehaus.ios

import app.tubehaus.engine.Ck
import app.tubehaus.engine.CookieJar
import app.tubehaus.engine.SapisidHash
import app.tubehaus.engine.SecretStore
import app.tubehaus.engine.SignInLauncher
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.CoreGraphics.CGRectMake
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSHTTPCookie
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Foundation.timeIntervalSince1970
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlock
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData
import platform.UIKit.UIAction
import platform.UIKit.UIApplication
import platform.UIKit.UIBarButtonItem
import platform.UIKit.UIDevice
import platform.UIKit.UIModalPresentationFullScreen
import platform.UIKit.UINavigationController
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.navigationItem
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.WebKit.WKWebsiteDataStore
import platform.WebKit.WKContentMode
import kotlin.coroutines.resume

// iPhone / iPad sign-in (stream S3), after Spotless ios/Encore/Encore/Screens/YouTubeSignIn.swift:
// Google's own page in a WKWebView (the user types into Google, never into the app); once youtube.com holds
// a session (SAPISID) its cookies are read from WKHTTPCookieStore and handed to Accounts, which keeps them
// in the Keychain. Each Google sign-in has its own persistent data store (iOS 17+), so a hidden reload of
// youtube.com later rotates that account's cookies without a new sign-in.

private const val LOGIN_URL = "https://accounts.google.com/ServiceLogin?service=youtube&uilel=3&passive=true&continue=https%3A%2F%2Fwww.youtube.com%2Fsignin%3Faction_handle_signin%3Dtrue%26app%3Dm%26next%3Dhttps%253A%252F%252Fm.youtube.com%252F"
private const val ADD_URL = "https://accounts.google.com/AddSession?service=youtube&continue=https%3A%2F%2Fwww.youtube.com%2Fsignin%3Faction_handle_signin%3Dtrue%26app%3Dm%26next%3Dhttps%253A%252F%252Fm.youtube.com%252F"
private const val HANDOFF_URL = "https://www.youtube.com/signin?action_handle_signin=true&app=m&next=https%3A%2F%2Fm.youtube.com%2F"
/** Mobile Safari's own UA: WKWebView's default lacks the "Version/… Safari" tokens and Google refuses what it takes for an embedded browser */
private const val UA = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1"

class IosSignIn : SignInLauncher {
    private fun store(profile: String): WKWebsiteDataStore {
        val major = UIDevice.currentDevice.systemVersion.substringBefore('.').toIntOrNull() ?: 0
        if (major < 17) return WKWebsiteDataStore.defaultDataStore()
        val h = SapisidHash.sha1Hex("tubeless-signin:$profile")
        val uuid = NSUUID(uUIDString = "${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20, 32)}")
        return WKWebsiteDataStore.dataStoreForIdentifier(uuid)
    }

    private fun web(profile: String, visible: Boolean): WKWebView {
        val cfg = WKWebViewConfiguration()
        cfg.websiteDataStore = store(profile)
        cfg.defaultWebpagePreferences.preferredContentMode = WKContentMode.WKContentModeMobile
        val w = WKWebView(frame = CGRectMake(0.0, 0.0, 390.0, 844.0), configuration = cfg)
        w.customUserAgent = UA
        if (!visible) {
            // in the window (WebKit throttles a page that is nowhere), but not to be seen
            w.alpha = 0.01
            w.userInteractionEnabled = false
            (UIApplication.sharedApplication.keyWindow ?: UIApplication.sharedApplication.windows.firstOrNull() as? UIWindow)?.addSubview(w)
        }
        return w
    }

    private suspend fun cookies(w: WKWebView): List<NSHTTPCookie> = suspendCancellableCoroutine { c ->
        w.configuration.websiteDataStore.httpCookieStore.getAllCookies { list -> c.resume(list?.filterIsInstance<NSHTTPCookie>().orEmpty()) }
    }

    private fun youtube(list: List<NSHTTPCookie>): List<Ck> = list.filter { CookieJar.isYouTube(it.domain) }.map {
        Ck(it.name, it.value, it.domain.let { d -> if (d.startsWith(".") || d == "www.youtube.com") d else ".$d" }, it.path,
            it.expiresDate?.timeIntervalSince1970?.toLong() ?: 0, it.isSecure(), it.isHTTPOnly())
    }

    private fun googleSession(list: List<NSHTTPCookie>) = list.any {
        val d = it.domain.trimStart('.')
        (d == "google.com" || d.endsWith(".google.com")) && it.name in setOf("SID", "__Secure-1PSID", "__Secure-3PSID")
    }

    private fun top(): UIViewController? {
        var vc = UIApplication.sharedApplication.keyWindow?.rootViewController ?: return null
        while (vc.presentedViewController != null) vc = vc.presentedViewController!!
        return vc
    }

    override suspend fun signIn(profile: String, add: Boolean): List<Ck>? = withContext(Dispatchers.Main) {
        val w = web(profile, visible = true)
        var cancelled = false
        val page = UIViewController(nibName = null, bundle = null)
        page.view = w
        page.title = "Sign in to YouTube"
        page.navigationItem.leftBarButtonItem = UIBarButtonItem(primaryAction = UIAction.actionWithTitle("Cancel", null, null) { _ -> cancelled = true })
        val nav = UINavigationController(rootViewController = page)
        nav.modalPresentationStyle = UIModalPresentationFullScreen
        top()?.presentViewController(nav, animated = true, completion = null) ?: return@withContext null
        w.loadRequest(NSURLRequest(uRL = NSURL(string = if (add) ADD_URL else LOGIN_URL)))
        var handedOff = false
        var seen = 0
        try {
            withTimeoutOrNull<List<Ck>?>(10 * 60_000L) {
                while (!cancelled) {
                    delay(300)
                    val host = w.URL?.host ?: ""
                    // only Google's own pages show: YouTube's hand-off runs hidden, then the app is signed in
                    w.hidden = !(host.isEmpty() || host == "google.com" || host.endsWith(".google.com"))
                    val list = cookies(w)
                    val yt = CookieJar.isYouTube(host)
                    val signed = CookieJar(youtube(list)).sapisid() != null
                    if (signed && yt) {
                        // the rest of the hand-off's cookies land a beat later
                        if (++seen >= 5) return@withTimeoutOrNull youtube(cookies(w))
                    } else if (yt && googleSession(list) && !signed && !handedOff) {
                        handedOff = true
                        w.loadRequest(NSURLRequest(uRL = NSURL(string = HANDOFF_URL)))
                    }
                }
                null
            }
        } finally {
            nav.dismissViewControllerAnimated(true, completion = null)
        }
    }

    override suspend fun refresh(profile: String): List<Ck>? = withContext(Dispatchers.Main) {
        val w = web(profile, visible = false)
        try {
            w.loadRequest(NSURLRequest(uRL = NSURL(string = "https://www.youtube.com/")))
            withTimeoutOrNull(45_000) { while (w.loading || w.URL == null) delay(250) } ?: return@withContext null
            delay(2_000)
            youtube(cookies(w)).takeIf { CookieJar(it).sapisid() != null }
        } finally {
            w.removeFromSuperview()
        }
    }

    override suspend fun forget(profile: String) = withContext(Dispatchers.Main) {
        val s = store(profile)
        suspendCancellableCoroutine { c ->
            s.removeDataOfTypes(WKWebsiteDataStore.allWebsiteDataTypes(), modifiedSince = NSDate(timeIntervalSinceReferenceDate = -1.0e9)) { c.resume(Unit) }
        }
    }
}

/** the iOS Keychain (a generic password per name, this device only after the first unlock) */
class Keychain : SecretStore {
    private val service = "dev.tubeless.app"

    private fun <T> query(name: String, extra: Map<CFTypeRef?, CFTypeRef?>, f: (platform.CoreFoundation.CFMutableDictionaryRef?) -> T): T {
        val retained = listOf(CFBridgingRetain(service), CFBridgingRetain(name))
        val q = CFDictionaryCreateMutable(null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
        CFDictionaryAddValue(q, kSecClass, kSecClassGenericPassword)
        CFDictionaryAddValue(q, kSecAttrService, retained[0])
        CFDictionaryAddValue(q, kSecAttrAccount, retained[1])
        for ((k, v) in extra) CFDictionaryAddValue(q, k, v)
        try { return f(q) } finally { CFRelease(q); retained.forEach { CFRelease(it) } }
    }

    override fun read(name: String): String? = query(name, mapOf(kSecReturnData to kCFBooleanTrue, kSecMatchLimit to kSecMatchLimitOne)) { q ->
        memScoped {
            val out = alloc<CFTypeRefVar>()
            if (SecItemCopyMatching(q, out.ptr) != errSecSuccess) return@memScoped null
            val data = CFBridgingRelease(out.value) as? NSData ?: return@memScoped null
            NSString.create(data = data, encoding = NSUTF8StringEncoding)?.toString()
        }
    }

    override fun write(name: String, value: String?) {
        query(name, emptyMap()) { SecItemDelete(it) }
        if (value == null) return
        val data = NSString.create(string = value).dataUsingEncoding(NSUTF8StringEncoding) ?: return
        val d = CFBridgingRetain(data)
        try {
            query(name, mapOf(kSecValueData to d, kSecAttrAccessible to kSecAttrAccessibleAfterFirstUnlock)) { SecItemAdd(it, null) }
        } finally { CFRelease(d) }
    }
}
