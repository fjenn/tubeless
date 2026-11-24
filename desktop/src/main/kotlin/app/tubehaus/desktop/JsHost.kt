package app.tubehaus.desktop

import app.tubehaus.engine.JsSolver
import app.tubehaus.engine.Sabr
import app.tubehaus.engine.SabrHost
import com.caoccao.javet.enums.JSRuntimeType
import com.caoccao.javet.enums.V8AwaitMode
import com.caoccao.javet.interop.NodeRuntime
import com.caoccao.javet.interop.V8Host
import com.caoccao.javet.interop.V8Runtime
import com.caoccao.javet.interop.callback.IJavetDirectCallable
import com.caoccao.javet.interop.callback.JavetCallbackContext
import com.caoccao.javet.interop.callback.JavetCallbackType
import com.caoccao.javet.interop.loader.IJavetLibLoadingListener
import com.caoccao.javet.interop.loader.JavetLibLoader
import com.caoccao.javet.values.V8Value
import com.caoccao.javet.values.primitive.V8ValueInteger
import com.caoccao.javet.values.primitive.V8ValueString
import com.caoccao.javet.values.reference.V8ValueArrayBuffer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPInputStream

/**
 * The desktop's JavaScript engine: one Node.js runtime (Javet) inside the app's own process, on a thread of its
 * own. It runs what needed a hidden Edge or Chrome before ([BrowserSabr]): common/sabr/sabr-page.js (SABR, with
 * BotGuard's token made under JSDOM, see jshost/shim.js) and yt-dlp's EJS solver for [app.tubehaus.engine.Cipher].
 *
 * Nothing runs until a video needs it (the usual clients play most videos without it); after [IDLE_MS] unused the
 * runtime is closed, its memory given back, and it starts again when wanted. The thread stays: V8 is happiest with
 * every isolate made on the same thread.
 */
object JsHost {
    private const val IDLE_MS = 10 * 60_000L
    private const val UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36"
    val debug = env("TUBELESS_SABRLOG") != null || System.getenv("TUBEHAUS_SABRLOG") != null

    /** where Javet's native library is put the first time (a cache folder; the app sets it) */
    @Volatile var libDir: File? = null

    private val tasks = LinkedBlockingQueue<(NodeRuntime) -> Unit>()
    private val replies = ConcurrentHashMap<Int, CompletableDeferred<String?>>()
    private val ids = AtomicInteger()
    @Volatile private var rt: NodeRuntime? = null
    @Volatile private var thread: Thread? = null
    @Volatile private var lastUse = 0L
    /** when JS last called the app (pieces flowing): the loop turns quickly while it does */
    @Volatile private var activeAt = 0L
    /** why the runtime can't start here, once it couldn't */
    @Volatile var broken: String? = null
        private set
    /** listeners for the page's messages (pieces, ends, logs) */
    @Volatile var onHost: ((String) -> Unit)? = null
    @Volatile var onBin: ((ByteArray) -> Unit)? = null

    /** whether this build has Javet's library for this computer (checked without loading it) */
    val available: Boolean by lazy {
        runCatching { JsHost::class.java.getResource("/" + JavetLibLoader(JSRuntimeType.Node).resourceFileName.trimStart('/')) != null }
            .onFailure { log("no Javet library: $it") }.getOrDefault(false)
    }

    private fun log(s: String) { if (debug) System.err.println("[jshost ${System.currentTimeMillis() % 100_000}] $s") }

    // ------------------------------------------------------------------ the thread

    private fun thread(): Thread = synchronized(this) {
        thread?.takeIf { it.isAlive } ?: Thread({ loop() }, "tubeless-js").apply { isDaemon = true; start() }.also { thread = it }
    }

    /**
     * The thread waits inside Node's own event loop (so network data is handled the moment it arrives) and the
     * app wakes it for its calls with a byte on a local socket the runtime listens on ([wake]).
     */
    private fun loop() {
        while (true) {
            var t = if (rt == null) tasks.take() else tasks.poll()
            while (t != null) {
                val now = rt ?: start()
                if (now == null) { t = tasks.poll(); continue }
                try { t(now) } catch (e: Throwable) { log("task: $e") }
                runCatching { now.performMicrotaskCheckpoint() }
                t = tasks.poll()
            }
            val live = rt ?: continue
            // 20 s without a call: garbage back to the system (BotGuard's and the solver's leftovers are large)
            val now = System.currentTimeMillis()
            if (now - lastUse > 20_000 && now - gcAt > 60_000) { gcAt = now; runCatching { live.lowMemoryNotification() } }
            if (System.currentTimeMillis() - lastUse > IDLE_MS && replies.isEmpty()) { close("idle"); continue }
            try {
                // without the wake socket (it failed to open): look again every few milliseconds
                live.await(if (wakeOut != null) V8AwaitMode.RunOnce else V8AwaitMode.RunNoWait)
                live.performMicrotaskCheckpoint()
                if (wakeOut == null) Thread.sleep(2)
            } catch (e: Throwable) { log("loop: $e") }
        }
    }

    private var gcAt = 0L
    @Volatile private var wakeOut: java.io.OutputStream? = null
    private var wakeSocket: java.net.Socket? = null

    /** wakes the JS thread out of Node's event loop: a byte to the runtime's wake socket */
    private fun wake() {
        val o = wakeOut ?: return
        try { synchronized(o) { o.write(1); o.flush() } } catch (e: Exception) { wakeOut = null }
    }

    /** runs [block] on the JS thread with the runtime (started if it isn't) */
    fun post(block: (NodeRuntime) -> Unit) { lastUse = System.currentTimeMillis(); tasks.add(block); thread(); wake() }

    private fun close(why: String) {
        val r = rt ?: return
        rt = null
        log("closing ($why)")
        runCatching { wakeSocket?.close() }; wakeSocket = null; wakeOut = null
        runCatching { r.setStopping(true); r.close() }
        replies.values.forEach { it.complete(null) }; replies.clear()
        Sabr.hostGone()
    }

    private fun resource(name: String): String? = JsHost::class.java.getResourceAsStream("/$name")?.use { it.readBytes().decodeToString() }
    private fun gzResource(name: String): String? = JsHost::class.java.getResourceAsStream("/$name")?.use { GZIPInputStream(it).readBytes().decodeToString() }

    /** Javet's library goes to [libDir] once (118 MB on a Mac), not to a new temporary folder on every start */
    private fun libSetup() {
        val dir = libDir ?: return
        dir.mkdirs()
        val loader = JavetLibLoader(JSRuntimeType.Node)
        val name = loader.libFileName
        val lib = File(dir, name)
        // older versions' copies (and Javet's own per-process folders) go
        dir.listFiles()?.filter { it.name != name }?.forEach { it.deleteRecursively() }
        if (!lib.exists()) {
            // copied whole, then renamed: a copy cut short never looks like the library
            val tmp = File(dir, "$name.part")
            (JsHost::class.java.getResourceAsStream("/" + loader.resourceFileName.trimStart('/')) ?: error("no Javet library in the app")).use { input ->
                tmp.outputStream().use { input.copyTo(it, 1 shl 20) }
            }
            if (!tmp.renameTo(lib)) error("could not place ${lib.path}")
        }
        JavetLibLoader.setLibLoadingListener(object : IJavetLibLoadingListener {
            override fun getLibPath(t: JSRuntimeType) = dir
            override fun isDeploy(t: JSRuntimeType) = false
        })
    }

    private fun fn(r: V8Runtime, name: String, f: (Array<out V8Value>) -> Unit) =
        r.createV8ValueFunction(JavetCallbackContext(name, JavetCallbackType.DirectCallNoThisAndNoResult,
            IJavetDirectCallable.NoThisAndNoResult<Exception> { args -> activeAt = System.currentTimeMillis(); f(args) }))

    private fun start(): NodeRuntime? {
        if (broken != null) return null
        val t0 = System.currentTimeMillis()
        return try {
            libSetup()
            // V8 in its memory-saving mode, its heap capped well above what the page needs (~60-100 MB)
            runCatching {
                com.caoccao.javet.interop.options.NodeRuntimeOptions.V8_FLAGS.setMemorySaverMode(true).setMaxOldSpaceSize(384)
                    .setCustomFlags("--max-semi-space-size=2")
            }
            val host = V8Host.getNodeInstance()
            val r = host.createV8Runtime<NodeRuntime>()
            // BotGuard and YouTube's player script build code from text, as in any browser
            r.allowEval(true)
            r.globalObject.use { g ->
                g.set("__thUA", UA)
                fn(r, "__thHost") { a -> (a.getOrNull(0) as? V8ValueString)?.value?.let { m -> onHost?.invoke(m) } }.use { g.set("__thHost", it) }
                fn(r, "__thReply") { a ->
                    val id = (a.getOrNull(0) as? V8ValueInteger)?.value ?: return@fn
                    val v = (a.getOrNull(1) as? V8ValueString)?.value
                    val err = (a.getOrNull(2) as? V8ValueString)?.value
                    if (!err.isNullOrEmpty()) log("call $id failed: $err")
                    replies.remove(id)?.complete(if (err.isNullOrEmpty()) v else null)
                }.use { g.set("__thReply", it) }
                r.createV8ValueObject().use { bin ->
                    fn(r, "postMessage") { a -> (a.getOrNull(0) as? V8ValueArrayBuffer)?.toBytes()?.let { b -> onBin?.invoke(b) } }.use { bin.set("postMessage", it) }
                    g.set("__thBin", bin)
                }
            }
            fun run(src: String?, name: String) = r.getExecutor(src ?: error("$name missing")).executeVoid()  // no setResourceName: in Node mode it changes the process's working folder
            run(resource("jshost/shim.js"), "shim.js")
            run(gzResource("jshost/botguard-dom.js.gz"), "botguard-dom.js")
            run(resource("sabr-page.js"), "sabr-page.js")
            val solver = (resource("yt.solver.lib.min.js") ?: error("solver missing")) + "\n;var meriyah=lib.meriyah,astring=lib.astring;\n" +
                (resource("yt.solver.core.min.js") ?: error("solver missing"))
            r.globalObject.use { g -> g.set("__thSolverSrc", solver) }
            run("__thSabr.setSolver(globalThis.__thSolverSrc); delete globalThis.__thSolverSrc", "setSolver.js")
            // the wake socket: the runtime listens on 127.0.0.1, this side connects once
            val port = CompletableDeferred<Int>()
            r.globalObject.use { g -> fn(r, "__thWakePort") { a -> port.complete((a.getOrNull(0) as? V8ValueInteger)?.value ?: 0) }.use { g.set("__thWakePort", it) } }
            run("__thListenWake()", "wake.js")
            val t1 = System.currentTimeMillis()
            while (!port.isCompleted && System.currentTimeMillis() - t1 < 3_000) { r.await(V8AwaitMode.RunNoWait); Thread.sleep(1) }
            val p = if (port.isCompleted) port.getCompleted() else 0
            if (p > 0) runCatching {
                wakeSocket = java.net.Socket(java.net.Proxy.NO_PROXY).apply { tcpNoDelay = true; connect(java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), p), 3_000) }
                wakeOut = wakeSocket!!.getOutputStream()
            }.onFailure { log("no wake socket: $it") }
            rt = r
            lastUse = System.currentTimeMillis()
            log("Node ${r.version} up in ${System.currentTimeMillis() - t0} ms")
            r
        } catch (e: Throwable) {
            broken = e.toString()
            System.err.println("Tubeless: the JS host could not start: $e")
            replies.values.forEach { it.complete(null) }; replies.clear()
            null
        }
    }

    // ------------------------------------------------------------------ calls

    /** calls the JS function at [path] (e.g. "__thSabr.prepare") with [args]; its answer (awaited if a promise) as text, null when it failed */
    suspend fun ask(path: String, vararg args: Any, timeoutMs: Long = 60_000): String? {
        if (!available || broken != null) return null
        val id = ids.incrementAndGet()
        val d = CompletableDeferred<String?>()
        replies[id] = d
        post { r -> r.globalObject.use { g -> g.invokeVoid("__thAsk", id, path, *args) } }
        return withTimeoutOrNull(timeoutMs) { d.await() }.also {
            replies.remove(id)
            // a video's start (BotGuard's mint, the player script) leaves a heap of garbage: V8 collects it
            // at once and the freed pages go back to the system, instead of the peak lingering for a while
            if (path == "__thSabr.prepare" && env("TUBELESS_NO_TRIM") == null) post { r -> runCatching { r.lowMemoryNotification() }; MemoryTrim.now() }
        }
    }

    /** calls [path] with [args] and doesn't wait for it */
    fun fire(path: String, vararg args: Any) {
        if (!available || broken != null) return
        post { r -> r.globalObject.use { g -> g.invokeVoid("__thAsk", 0, path, *args) } }
    }

    /** the runtime up (started if it wasn't); false when it can't start here */
    suspend fun ready(): Boolean {
        if (!available || broken != null) return false
        return ask("__thSabr.ready", timeoutMs = 30_000) == "ok"
    }

    /** whether the runtime is up now (for the tests) */
    val running get() = rt != null
}

/**
 * [SabrHost] on a computer through [JsHost]: sabr-page.js in the app's own process. The page's messages come as
 * in [BrowserSabr], pieces as binary (no base64).
 */
class JsSabr : SabrHost {
    init {
        JsHost.onHost = { text ->
            val m = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
            if (m != null) when (m["t"]?.jsonPrimitive?.content) {
                "piece" -> Sabr.piece(m["run"]?.jsonPrimitive?.intOrNull ?: -1, m["at"]?.jsonPrimitive?.longOrNull ?: -1,
                    m["init"]?.jsonPrimitive?.booleanOrNull == true, m["ms"]?.jsonPrimitive?.longOrNull ?: -1,
                    java.util.Base64.getDecoder().decode(m["data"]?.jsonPrimitive?.content ?: ""))
                "log" -> if (JsHost.debug) System.err.println("sabr page: " + m["msg"]?.jsonPrimitive?.contentOrNull)
                "end" -> {
                    if (JsHost.debug) System.err.println("sabr end: $m")
                    m["run"]?.jsonPrimitive?.intOrNull?.let { Sabr.end(it, m["bad"]?.jsonPrimitive?.booleanOrNull == true, m["error"]?.jsonPrimitive?.contentOrNull) }
                }
            }
        }
        // [u32 head length][head JSON][data]
        JsHost.onBin = { b ->
            if (b.size >= 4) {
                val n = ((b[0].toInt() and 0xff) shl 24) or ((b[1].toInt() and 0xff) shl 16) or ((b[2].toInt() and 0xff) shl 8) or (b[3].toInt() and 0xff)
                val m = runCatching { Json.parseToJsonElement(b.decodeToString(4, 4 + n)).jsonObject }.getOrNull()
                if (m != null) Sabr.piece(m["run"]?.jsonPrimitive?.intOrNull ?: -1, m["at"]?.jsonPrimitive?.longOrNull ?: -1,
                    m["init"]?.jsonPrimitive?.booleanOrNull == true, m["ms"]?.jsonPrimitive?.longOrNull ?: -1, b.copyOfRange(4 + n, b.size))
            }
        }
    }

    // numbers as doubles: a Kotlin Long would arrive as a BigInt
    override suspend fun prepare(id: String, hl: String, gl: String) = JsHost.ask("__thSabr.prepare", id, hl, gl)?.takeIf { it.isNotEmpty() }
    override suspend fun caption(id: String, url: String) = JsHost.ask("__thSabr.caption", id, url)?.takeIf { it.isNotEmpty() }
    override fun start(run: Int, id: String, index: Int, startMs: Long) {
        if (JsHost.debug) System.err.println("sabr start $run $id #$index from $startMs ms")
        JsHost.fire("__thSabr.start", run, id, index, startMs.toDouble())
    }
    override fun allow(run: Int, upTo: Long) = JsHost.fire("__thSabr.allow", run, upTo.toDouble())
    override fun stop(run: Int) = JsHost.fire("__thSabr.stop", run)
    override val live get() = true
    override fun startLive(runV: Int, runA: Int, id: String, index: Int) = JsHost.fire("__thSabr.liveStart", runV, runA, id, index)
    override suspend fun warm() { JsHost.ready() }
}

/** [JsSolver] on a computer: yt-dlp's EJS solver in [JsHost] (the TV client's locked links) */
class JsCipher : JsSolver {
    private fun res(name: String) = JsCipher::class.java.getResourceAsStream("/$name")?.use { it.readBytes().decodeToString() }

    override suspend fun load(playerJs: String): Boolean {
        val solver = (res("yt.solver.lib.min.js") ?: return false) + "\n;var meriyah=lib.meriyah,astring=lib.astring;\n" + (res("yt.solver.core.min.js") ?: return false)
        return JsHost.ask("__thCipher.load", solver, playerJs, timeoutMs = 60_000) == "ok"
    }

    override suspend fun solve(n: List<String>, sig: List<String>): Map<String, String>? {
        val types = buildList { if (n.isNotEmpty()) add("n"); if (sig.isNotEmpty()) add("sig") }
        if (types.isEmpty()) return emptyMap()
        val reqs = JsonArray(types.map { t ->
            JsonObject(mapOf("type" to JsonPrimitive(t), "challenges" to JsonArray((if (t == "n") n else sig).map { JsonPrimitive(it) })))
        })
        val r = JsHost.ask("__thCipher.solve", reqs.toString())?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching {
            val out = HashMap<String, String>()
            // the answers come in the order asked: n first (when asked), then sig
            Json.parseToJsonElement(r).jsonObject["responses"]!!.jsonArray.forEachIndexed { i, resp ->
                val data = resp.jsonObject["data"] as? JsonObject ?: return@forEachIndexed
                for ((k, v) in data) out[types[i] + ":" + k] = v.jsonPrimitive.content
            }
            out
        }.getOrNull()
    }
}
