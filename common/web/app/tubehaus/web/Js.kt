package app.tubehaus.web

import kotlinx.coroutines.await
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import org.khronos.webgl.get
import org.khronos.webgl.set
import kotlin.js.Promise

// The bridge to the extension's JavaScript side (extension/app-src/tubeless.js, window.tubeless):
// bytes stay there where they can (video pieces, downloads); Kotlin gets text and small files.

/** a fetch's answer: status, text (or bytes as an ArrayBuffer) */
class JsAnswer(val status: Int, val text: String?, val bytes: ArrayBuffer?)

suspend fun jsFetch(method: String, url: String, headers: Map<String, String>, body: String?, binary: Boolean): JsAnswer {
    val h = headers.entries.joinToString("\u0000") { it.key + "\u0000" + it.value }
    val r = jsFetchRaw(method, url, h, body, binary).await<JsAny>()
    return JsAnswer(ansStatus(r), ansText(r), ansBytes(r))
}

fun ArrayBuffer.toByteArray(): ByteArray {
    val a = Int8Array(this)
    return ByteArray(a.length) { a[it] }
}

fun ByteArray.toArrayBuffer(): ArrayBuffer {
    val a = Int8Array(size)
    for (i in indices) a[i] = this[i]
    return a.buffer
}

private fun jsFetchRaw(method: String, url: String, headers: String, body: String?, binary: Boolean): Promise<JsAny> =
    js("tubeless.net.fetch(method, url, headers, body, binary)")
private fun ansStatus(r: JsAny): Int = js("r.status")
private fun ansText(r: JsAny): String? = js("r.text === undefined ? null : r.text")
private fun ansBytes(r: JsAny): ArrayBuffer? = js("r.bytes === undefined ? null : r.bytes")

fun jsLog(s: String): Unit = js("console.log('[tubeless] ' + s)")
fun jsQuery(k: String): String? = js("new URLSearchParams(location.search).get(k)")
