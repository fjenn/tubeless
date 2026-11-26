package app.tubeless.ios

import androidx.compose.runtime.snapshotFlow
import app.tubeless.ui.AppState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * The app side of downloads on iOS (the downloads themselves: common/ios OfflineIos.kt, S6).
 *
 * - **Woken for finished downloads.** iOS may start the app in the background, without a window,
 *   when a background download session has news (ios/app/App.swift's app delegate,
 *   `handleEventsForBackgroundURLSession`). [backgroundSessionEvents] makes the app's state and its
 *   download sessions (same identifiers, so iOS hands them the finished tasks), then gives iOS its
 *   completion handler back so it can update the app switcher snapshot and suspend the app again.
 * - **Progress outside the app (iOS 26+).** Starting downloads from the app asks the Swift side
 *   ([onStarted]) for a continued-processing task: iOS shows its progress in a system banner and on
 *   the lock screen, and the app keeps running while it lasts. [progress] and [title] feed it.
 */
object DownloadProgress {
    private var app: AppState? = null
    /** set by App.swift: downloads went from none to some while the app was in front */
    var onStarted: (() -> Unit)? = null
    /** completion handlers from iOS, per session identifier */
    private val waiting = HashMap<String, () -> Unit>()

    fun watch(state: AppState) {
        app = state
        // the downloads part (Offline) is made now rather than on first use: its sessions then exist
        // and pick up the tasks iOS kept going while the app was away (App.kt reads it at start anyway)
        state.offline.saved
        state.scope.launch {
            snapshotFlow { state.offline.pending.size }.distinctUntilChanged().collect { n ->
                if (n > 0 && last == 0) onStarted?.invoke()
                last = n
            }
        }
    }
    private var last = 0

    /** downloads waiting or running now */
    fun count(): Int = app?.offline?.pending?.size ?: 0

    /** all running downloads together, 0..1 */
    fun progress(): Double {
        val a = app ?: return 1.0
        val p = a.offline.progress.values
        return if (p.isEmpty()) 1.0 else p.sum().toDouble() / p.size
    }

    /** "Downloading 3 videos" / the one video's title */
    fun title(): String {
        val a = app ?: return ""
        val v = a.offline.pending.values.toList()
        return if (v.size == 1) v[0].title.ifEmpty { "1 video" } else "${v.size} videos"
    }

    /** iOS started the app for a background download session's events; [done] when they are handled */
    fun backgroundSessionEvents(identifier: String, done: () -> Unit) {
        val state = appState()   // makes the download sessions too (watch)
        waiting[identifier] = done
        // the session's delegate runs on the main queue: its events arrive right after the session is
        // made again. Give iOS its handler back once they had time to land (it allows about 30 s);
        // OfflineIos can call [sessionEventsFinished] from urlSessionDidFinishEvents to do it sooner.
        state.scope.launch {
            delay(5_000)
            sessionEventsFinished(identifier)
        }
    }

    fun sessionEventsFinished(identifier: String) {
        waiting.remove(identifier)?.invoke()
    }
}
