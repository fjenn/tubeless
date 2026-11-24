package app.tubehaus.desktop

import com.sun.jna.Native
import com.sun.jna.Pointer
import kotlin.concurrent.thread

/**
 * Hands memory the native side has freed back to the system (M3, RSS on the SABR path). mpv, FFmpeg's
 * demuxers, VideoToolbox's copy-back and Node keep freed blocks in the malloc zones, so the app's resident
 * memory climbs with every video and seek and never comes down. Every [EVERY_MS] (and only when the app is
 * not idle) macOS gets `malloc_zone_pressure_relief`, Linux glibc `malloc_trim`: both just return free pages,
 * nothing that is in use moves. Windows does this on its own (the heap decommits).
 */
object MemoryTrim {
    private const val EVERY_MS = 15_000L
    private interface MacC : com.sun.jna.Library { fun malloc_zone_pressure_relief(zone: Pointer?, goal: Long): Long }
    private interface GlibC : com.sun.jna.Library { fun malloc_trim(pad: Long): Int }

    private val os = System.getProperty("os.name")
    private val trim: (() -> Unit)? = runCatching<(() -> Unit)?> {
        when {
            os.startsWith("Mac") -> Native.load("c", MacC::class.java).let { c -> { c.malloc_zone_pressure_relief(null, 0) } }
            os.startsWith("Linux") -> Native.load("c", GlibC::class.java).let { c -> { c.malloc_trim(0) } }
            else -> null
        }
    }.getOrNull()

    /** gives freed memory back to the system now (after a burst such as a BotGuard mint) */
    fun now() { trim?.takeIf { env("TUBELESS_NO_TRIM") == null }?.let { runCatching { it() } } }

    /** [busy]: true while something runs that frees a lot (a video plays) */
    fun start(busy: () -> Boolean) {
        val t = trim?.takeIf { env("TUBELESS_NO_TRIM") == null } ?: return
        thread(isDaemon = true, name = "memory-trim", priority = Thread.MIN_PRIORITY) {
            var wasBusy = false
            while (true) {
                Thread.sleep(EVERY_MS)
                val b = busy()
                // while playing, and once more after it stops (the closed video's buffers)
                if (b || wasBusy) runCatching { t() }
                wasBusy = b
            }
        }
    }
}
