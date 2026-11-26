package app.tubeless.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import platform.Foundation.NSBundle
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfFile
import platform.JavaScriptCore.JSContext

/**
 * yt-dlp's EJS solver (yt.solver.*.min.js from common/ejs, in the app bundle) in Apple's JavaScriptCore,
 * for [Cipher] (the TV client's locked links). The iPhone twin of Android's hidden-WebView solver: no page,
 * no network, just the solver and YouTube's player script. Dropped when it fails, made again on the next use.
 */
class JscSolver : JsSolver {
    private val lock = Mutex()
    private var ctx: JSContext? = null

    private fun bundled(name: String): String? =
        NSBundle.mainBundle.pathForResource(name, "js")?.let { NSString.stringWithContentsOfFile(it, NSUTF8StringEncoding, null) }

    /** runs [js] and gives back its value as text; null on an exception */
    private fun JSContext.text(js: String): String? {
        exception = null
        val v = evaluateScript(js)
        if (exception != null) return null
        return v?.toObject() as? String
    }

    private fun context(): JSContext? {
        ctx?.let { return it }
        val lib = bundled("yt.solver.lib.min") ?: return null
        val core = bundled("yt.solver.core.min") ?: return null
        val c = JSContext()
        val ok = c.text("$lib\n;var meriyah=lib.meriyah,astring=lib.astring;\n$core\n;'ok'") == "ok"
        return c.takeIf { ok }?.also { ctx = it }
    }

    override suspend fun load(playerJs: String): Boolean = lock.withLock {
        withContext(Dispatchers.IO) {
            val c = context() ?: return@withContext false
            val quoted = JsonPrimitive(playerJs).toString()
            val r = c.text(
                "(function(){try{var o=jsc({type:'player',player:$quoted,requests:[],output_preprocessed:true});" +
                    "globalThis.__thPre=o.preprocessed_player;return 'ok'}catch(e){return 'error: '+e}})()",
            )
            if (r != "ok") ctx = null
            r == "ok"
        }
    }

    override suspend fun solve(n: List<String>, sig: List<String>): Map<String, String>? = lock.withLock {
        val c = ctx ?: return@withLock null
        val types = buildList { if (n.isNotEmpty()) add("n"); if (sig.isNotEmpty()) add("sig") }
        if (types.isEmpty()) return@withLock emptyMap()
        val reqs = types.joinToString(",") { t ->
            "{\"type\":\"$t\",\"challenges\":" + JsonArray((if (t == "n") n else sig).map { JsonPrimitive(it) }) + "}"
        }
        withContext(Dispatchers.IO) {
            val r = c.text(
                "(function(){try{return JSON.stringify(jsc({type:'preprocessed',preprocessed_player:globalThis.__thPre,requests:[$reqs]}))}catch(e){return ''}})()",
            )?.takeIf { it.isNotEmpty() } ?: return@withContext null
            runCatching {
                val out = HashMap<String, String>()
                // the answers come in the order asked: n first (when asked), then sig
                parse(r).at("responses").arr.forEachIndexed { i, resp ->
                    val data = resp.at("data") as? JsonObject ?: return@forEachIndexed
                    for ((k, v) in data) out[types[i] + ":" + k] = v.str ?: continue
                }
                out
            }.getOrNull()
        }
    }
}
