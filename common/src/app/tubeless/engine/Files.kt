package app.tubeless.engine

import okio.FileSystem
import okio.Path

// Small files through okio, which works the same on Android, the desktop and iOS.

/** the file's text, or null when there is no such file */
fun Path.readTextOrNull(): String? = if (Sys.fs.exists(this)) Sys.fs.read(this) { readUtf8() } else null

/** writes [text], making the folder first */
fun Path.writeText(text: String) {
    parent?.let { Sys.fs.createDirectories(it) }
    Sys.fs.write(this) { writeUtf8(text) }
}
