package app.tubeless.web

import kotlinx.coroutines.await
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.Sink
import okio.fakefilesystem.FakeFileSystem
import kotlin.js.Promise

/**
 * The app's files in the browser. okio has no disk here, so the files live in memory (FakeFileSystem)
 * and every file under [KEEP] (the settings, library, history: small JSON files) is also written to
 * IndexedDB when it is closed, and read back by [load] before the app starts. The image cache stays
 * in memory only.
 */
object WebFs {
    const val KEEP = "/tubeless/data"
    private val mem = FakeFileSystem().apply { allowSymlinks = false; emulateUnix() }

    val fs: FileSystem = object : ForwardingFileSystem(mem) {
        override fun sink(file: Path, mustCreate: Boolean): Sink = kept(file, super.sink(file, mustCreate))
        override fun appendingSink(file: Path, mustExist: Boolean): Sink = kept(file, super.appendingSink(file, mustExist))
        override fun atomicMove(source: Path, target: Path) {
            super.atomicMove(source, target)
            forget(source); keep(target)
        }
        override fun delete(path: Path, mustExist: Boolean) {
            super.delete(path, mustExist)
            forget(path)
        }
    }

    private fun kept(file: Path, s: Sink): Sink = if (!file.toString().startsWith(KEEP)) s else object : Sink by s {
        override fun close() { s.close(); keep(file) }
    }

    private fun keep(p: Path) {
        if (!p.toString().startsWith(KEEP) || !mem.exists(p) || mem.metadata(p).isDirectory) return
        jsPut(p.toString(), mem.read(p) { readUtf8() })
    }

    private fun forget(p: Path) { if (p.toString().startsWith(KEEP)) jsDel(p.toString()) }

    /** reads the kept files back into memory; call once before the app starts */
    suspend fun load() {
        val all = runCatching { jsLoadAll().await<JsAny>() }.getOrNull() ?: return
        for (i in 0 until jsLen(all)) {
            val path = jsPath(all, i).toPath()
            path.parent?.let { mem.createDirectories(it) }
            mem.write(path) { writeUtf8(jsText(all, i)) }
        }
        jsLog("files: ${jsLen(all)} read back")
    }
}

private fun jsPut(path: String, text: String): Unit = js("tubeless.fs.put(path, text)")
private fun jsDel(path: String): Unit = js("tubeless.fs.del(path)")
private fun jsLoadAll(): Promise<JsAny> = js("tubeless.fs.loadAll()")
private fun jsLen(a: JsAny): Int = js("a.length")
private fun jsPath(a: JsAny, i: Int): String = js("a[i][0]")
private fun jsText(a: JsAny, i: Int): String = js("a[i][1]")
