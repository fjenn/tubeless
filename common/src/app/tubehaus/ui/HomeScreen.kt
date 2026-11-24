package app.tubehaus.ui

import app.tubehaus.engine.YtError

import app.tubehaus.engine.YouTubeAccount

import app.tubehaus.engine.Sys
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.collectAsState
import app.tubehaus.engine.InnerTube
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.tubehaus.engine.Item
import app.tubehaus.engine.Video
import app.tubehaus.rec.Learn
import app.tubehaus.rec.Profiles
import kotlinx.coroutines.launch

/** Remembered across visits to the tab so going back to Home doesn't reshuffle it. */
internal object HomeCache {
    val items = mutableStateListOf<Item>()
    var at = 0L
}

/** what the chips over the home feed (drawn with the top tabs, one row) ask the feed to do */
object HomeCtl {
    var show: (Boolean) -> Unit = {}
    var fresh: () -> Unit = {}
    var changed: () -> Unit = {}
}

@Composable
fun HomeScreen(app: AppState) {
    // kids mode: the new videos of the picked channels
    if (app.store.settings.value.kids) { SubsScreen(app); return }
    if (app.store.settings.value.homeOff) {
        FeedOff(tr("The home feed is off"), tr("Search for what you came for, or open your subscriptions.")) {
            Button(onClick = { app.go(Screen.Search()) }) { Text(tr("Search")) }
            TextButton(onClick = { app.go(Screen.Subs) }) { Text(tr("Subscriptions")) }
        }
        return
    }
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    var errorCode by remember { mutableStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    suspend fun load(more: Boolean) {
        if (loading) return
        loading = true; error = null
        try {
            val skip = if (more) HomeCache.items.map { it.id }.toSet() else emptySet()
            val got = app.rec.home(40, skip)
            if (!more) {
                HomeCache.items.clear()
                // M3 E2E: Home loaded; signed out it is YouTube's Home for the remembered visitor (FIX1)
                val signed = YouTubeAccount.signedIn
                E2E.log("feed_loaded", "loggedOut" to !signed, "items" to got.size)
                if (!signed) E2E.log("feed_loaded_local", "items" to got.size, "source" to "visitor")
            }
            HomeCache.items.addAll(got.filter { g -> HomeCache.items.none { it.id == g.id } })
            HomeCache.at = Sys.nowMs()
        } catch (e: Exception) {
            errorCode = (e as? YtError)?.http ?: 0
            error = e.message ?: tr("Something went wrong")
        } finally { loading = false }
    }
    LaunchedEffect(Unit) {
        if (HomeCache.items.isEmpty() || Sys.nowMs() - HomeCache.at > 30 * 60_000) load(false)
    }
    // the chip already chosen tapped again, or a Refresh button: to the top, a spinner, a fresh feed from YouTube
    HomeCtl.fresh = {
        if (HomeCache.items.isEmpty()) { app.rec.clearCache(); scope.launch { load(false) } } else app.refreshPage()
        Unit
    }
    HomeCtl.changed = { HomeCache.items.clear(); app.rec.clearCache(); scope.launch { load(false) } }
    when {
        HomeCache.items.isEmpty() && loading -> Skeleton()
        // YouTube answered, but refused (a sign-in it won't take, a bad request): say so, don't claim "offline"
        HomeCache.items.isEmpty() && error != null && errorCode > 0 -> FeedOff(tr("YouTube didn't load your Home"), tr("YouTube answered %1\$s. Try again, or sign out and back in.").replace("%1\$s", errorCode.toString())) {
            Button(onClick = { scope.launch { load(false) } }) { Text(tr("Retry")) }
        }
        HomeCache.items.isEmpty() && error != null -> YouAreOffline(app) { scope.launch { load(false) } }
        // YouTube's own empty Home (a new visitor, history paused): what youtube.com says
        HomeCache.items.isEmpty() -> FeedOff(tr("Try searching to get started"), tr("Start watching videos to help us build a feed of videos you'll love.")) {
            Button(onClick = { app.go(Screen.Search()) }) { Text(tr("Search")) }
            TextButton(onClick = { HomeCtl.fresh() }) { Text(tr("Refresh")) }
        }
        else -> HomeFeed(
            if (app.noShorts) HomeCache.items.filter { !(it is Video && it.short) } else HomeCache.items, app, loading = loading,
            onEnd = { load(true) },
            hero = LocalLook.current == 1,
            // a new request to YouTube in the same session (nothing cached), the chosen chip kept
            refresh = { while (loading) kotlinx.coroutines.delay(50); app.rec.clearCache(); load(false) },
            header = {
                // the chips (All, Discover, the region...) are in the one row of tabs above the page
                if (LocalLook.current == 1) Column { ContinueRow(app) }
                NearbyBanner(app)
            },
        )
    }
}

fun profileLabel(name: String) = name.ifEmpty { tr("Main") }

/** Imports a profile file; shows what it was added as, or returns null (nothing said) when it isn't one. */
fun importProfile(app: AppState, text: String): String? =
    Profiles.import(app.store, text, tr("Imported"))?.also { n ->
        app.toast(tr("Imported the profile “{p}”").replace("{p}", n))
    }

/** the home feed is built again next time (after a profile changed elsewhere) */
fun clearHome() = HomeCache.items.clear()

/**
 * Taste profiles (rec/Profiles): a chip with the one in use, the others and a new one in its menu.
 * With only the main profile it is just the icon.
 */
@Composable
fun ProfilePicker(app: AppState, changed: () -> Unit) {
    val s by app.store.settings.state.collectAsState()
    val parked by app.store.profiles.state.collectAsState()
    val names = remember(s.profile, parked) { Profiles.names(app.store) }
    var open by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }
    Box {
        Chip(false, onClick = { open = true }, label = if (names.size > 1) profileLabel(s.profile) else "", icon = Sym.Psychology.outline)
        DropdownMenu(open, onDismissRequest = { open = false }) {
            for (n in names) DropdownMenuItem(
                text = { Text(profileLabel(n)) },
                leadingIcon = { if (n == s.profile) androidx.compose.material3.Icon(Sym.Check.outline, null) },
                onClick = { open = false; if (n != s.profile) { Profiles.switch(app.store, n); changed() } },
            )
            DropdownMenuItem(text = { Text(tr("New profile")) }, leadingIcon = { androidx.compose.material3.Icon(Sym.Add.outline, null) },
                onClick = { open = false; naming = true })
            DropdownMenuItem(text = { Text(tr("Manage profiles")) }, leadingIcon = { androidx.compose.material3.Icon(Sym.Settings.outline, null) },
                onClick = { open = false; app.go(Screen.Learned) })
        }
    }
    if (naming) NameDialog(tr("New profile"), "", { naming = false }) { name ->
        if (name in names) app.toast(tr("That name is already used"))
        else { Profiles.switch(app.store, name); changed() }
    }
}

/** The region the feeds come from, switched from Home: a few common ones, or any two letters in Settings. */
@Composable
fun RegionPicker(app: AppState, changed: () -> Unit) {
    val s by app.store.settings.state.collectAsState()
    var open by remember { mutableStateOf(false) }
    val now = s.region.ifEmpty { app.platform.deviceRegion() }.uppercase()
    Box {
        // a short chip: the globe and the two letters (the full name is in the menu)
        Chip(false, onClick = { open = true }, label = now, icon = Sym.Public.outline)
        DropdownMenu(open, onDismissRequest = { open = false }) {
            val common = listOf("US", "GB", "CA", "AU", "IN", "DE", "FR", "ES", "IT", "NL", "TR", "BR", "MX", "JP", "KR", "ID")
            DropdownMenuItem(text = { Text(tr("This device") + " (" + app.platform.deviceRegion().uppercase() + ")") }, onClick = {
                open = false; app.store.settings.update { it.copy(region = "") }; changed()
            })
            for (c in common) DropdownMenuItem(
                text = { Text(Sys.countryName(c, InnerTube.hl) + "  ·  " + c) },
                onClick = { open = false; app.store.settings.update { it.copy(region = c) }; changed() },
            )
            DropdownMenuItem(text = { Text(tr("Other...")) }, onClick = { open = false; app.go(Screen.Settings) })
        }
    }
}

/** What a page shows when its feed is switched off in Settings → Focus. */
@Composable
fun FeedOff(title: String, sub: String, actions: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 420.dp).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp, bottom = 20.dp))
            actions()
        }
    }
}

/** YouTube can't be reached: nothing streams, but the downloads play (and a retry) */
@Composable
fun YouAreOffline(app: AppState, retry: () -> Unit) {
    FeedOff(tr("You're offline"), tr("Check your connection. Your downloads still play.")) {
        Button(onClick = { app.go(Screen.Downloads) }) { Text(tr("Go to downloads")) }
        TextButton(onClick = retry) { Text(tr("Retry")) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LanguagePicker(app: AppState) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 480.dp).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Logo(Modifier.padding(bottom = 24.dp))
            Text("Language", style = MaterialTheme.typography.titleLarge)
            FlowRow(
                Modifier.padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for ((code, name) in LANGS) {
                    OutlinedButton(onClick = { app.store.settings.update { it.copy(lang = code) } }) { Text(name) }
                }
            }
        }
    }
}

/**
 * First start, right after the language: bring the subscriptions over from YouTube or another app.
 * Each source says in one line where its export is; one button opens the file, whatever it came from.
 */
@Composable
fun Welcome(app: AppState) {
    fun finish() = app.store.settings.update { it.copy(welcomed = true) }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 560.dp).verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Logo(Modifier.padding(top = 16.dp, bottom = 24.dp))
            Text(tr("Bring your subscriptions"), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Text(
                tr("Your channels are kept on this device, no account needed. Pick the file you exported from another app:"),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
            )
            for ((name, how) in listOf(
                "YouTube" to "takeout.google.com → YouTube → subscriptions.csv",
                "NewPipe" to tr("Subscriptions → ⋮ → Export to → File"),
                "FreeTube" to tr("Settings → Data settings → Export subscriptions"),
                "Invidious" to tr("Settings → Import/Export → Export subscriptions"),
                "TubeHaus · OPML" to tr("a TubeHaus backup, or any feed reader's OPML"),
            )) {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Text(name, style = MaterialTheme.typography.titleSmall)
                    Text(how, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Button(onClick = { importFile(app) { ok -> if (ok) finish() } }, modifier = Modifier.padding(top = 20.dp).fillMaxWidth()) {
                Text(tr("Choose a file"))
            }
            TextButton(onClick = { finish() }, modifier = Modifier.padding(top = 4.dp)) { Text(tr("Skip for now")) }
        }
    }
}
