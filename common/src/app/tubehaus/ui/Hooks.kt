package app.tubehaus.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import app.tubehaus.engine.Video

// Extension points added at M0 (docs/PLAN.md §3) so each stream adds its feature in files of its own
// instead of editing shared screens. All are empty by default: the app behaves exactly as before.
// Register once at start (e.g. from AppState.init or a platform's main), before the UI is shown.
// This file belongs to S0; ask for a new hook rather than editing a screen another stream owns.

/** Top bar slots (App.kt TopBar), right of search/settings. */
object TopBarSlots {
    /** the account avatar / "Sign in" button (S3) */
    var account: (@Composable (AppState) -> Unit)? = null
    /** the notifications bell (S4 data, S7 panel) */
    var bell: (@Composable (AppState) -> Unit)? = null
}

@Composable fun AccountSlot(app: AppState) { TopBarSlots.account?.invoke(app) }
@Composable fun BellSlot(app: AppState) { TopBarSlots.bell?.invoke(app) }

/**
 * Settings categories added by other files (Account, Downloads, …). Each shows as a card on
 * Settings' first page (before "Your data") and its rows join the settings search.
 */
object SettingsSections {
    class Row(val words: String, val content: @Composable (AppState) -> Unit)
    class Section(val key: String, val icon: Symbol, val title: String, val sub: String, val rows: List<Row>, val group: String? = null)
    val sections = mutableListOf<Section>()
    fun add(s: Section) { sections.removeAll { it.key == s.key }; sections += s }
    fun of(key: String) = sections.firstOrNull { it.key == key }
}

/**
 * Rows added to the player's settings menu (PlayerControls.SettingsPages, "Playback" group).
 * [row] draws one line (use [PlayerMenuRow] to match the others); it can open its own [page].
 */
object PlayerMenuExtras {
    class Extra(
        val key: String,
        val row: @Composable (app: AppState, open: () -> Unit, dismiss: () -> Unit) -> Unit,
        val page: (@Composable ColumnScope.(app: AppState, dismiss: () -> Unit) -> Unit)? = null,
    )
    val extras = mutableListOf<Extra>()
    fun add(e: Extra) { extras.removeAll { it.key == e.key }; extras += e }
    /** the page key an extra opens in the menu */
    fun pageKey(key: String) = "x:$key"
}

/** What a [FeedSource] can be asked for. */
enum class FeedKind { HOME, SUBSCRIPTIONS, HISTORY, WATCH_LATER, LIKED }

/** one page of a feed and the token of the next one */
data class FeedPage(val items: List<Video>, val next: String? = null)

/**
 * Where the personal feeds come from (AppState.feedSource). The default is the local, signed-out
 * app (RSS subscriptions, on-device history, the local recommender); S4 sets a signed-in source.
 * A null answer means "not from me": the screen falls back to the local behaviour.
 */
interface FeedSource {
    val signedIn: Boolean get() = false
    suspend fun page(kind: FeedKind): FeedPage? = null
    suspend fun more(kind: FeedKind, token: String): FeedPage? = null
}

/** the signed-out source: answers nothing, so every screen keeps its local behaviour */
object LocalFeedSource : FeedSource
