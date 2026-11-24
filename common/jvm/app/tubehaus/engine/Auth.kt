package app.tubehaus.engine

import java.io.File

/**
 * EVAL path (`:desktop:homeCheck`): signed-in requests from a Netscape cookies.txt the user exported.
 * Uses the same jar and SAPISIDHASH as the app's accounts ([Accounts], engine/Account.kt), without
 * saving anything. In the app, cookies.txt import is Settings → Account → "Import cookies".
 */
object Auth {
    private var jar = CookieJar()

    fun load(file: File): Boolean {
        if (!file.exists()) return false
        jar = CookieJar.fromNetscape(file.readText())
        if (jar.sapisid() == null) return false
        InnerTube.authHeaders = ::headers
        return true
    }

    fun headers(origin: String): Map<String, String> {
        val ts = System.currentTimeMillis() / 1000
        val values = jar.values(origin.substringAfter("://"), ts)
        val auth = SapisidHash.authorization(values, origin, ts) ?: return emptyMap()
        return mapOf(
            "Cookie" to values.entries.joinToString("; ") { "${it.key}=${it.value}" },
            "Authorization" to auth,
            "X-Goog-AuthUser" to "0",
            "X-Origin" to origin,
        )
    }
}
