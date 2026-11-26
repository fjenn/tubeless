package app.tubeless.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.material3.IconButton
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tubeless.engine.AccountError
import app.tubeless.engine.Accounts
import app.tubeless.engine.Identity
import app.tubeless.engine.SecretStore
import app.tubeless.engine.Session
import app.tubeless.engine.TvSignIn
import app.tubeless.engine.SignInLauncher
import coil3.compose.AsyncImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// The account UI (stream S3): the avatar / "Sign in" button in the top bar (Hooks.AccountSlot), its menu
// (switch account or channel, add account, sign out), the channel picker for a Google account with several
// channels, and Settings → Account (sign in, cookies.txt import, signed playback for restricted videos).

/**
 * Once per start, from the platform's main, after the AppState exists: where accounts are kept, the
 * platform's sign-in web view (null: cookies.txt import only), and the UI hooks.
 */
fun installAccounts(app: AppState, store: SecretStore, launcher: SignInLauncher?) {
    Accounts.onChange += { app.accountChanged() }
    Accounts.setup(store, launcher)
    TopBarSlots.account = { AccountButton(it) }
    SettingsSections.add(SettingsSections.Section(
        "account", Sym.Person, "Account", "Sign in to your YouTube account",
        listOf(
            SettingsSections.Row("sign in out youtube google account channel switch add") { AccountRows(it) },
            SettingsSections.Row("import cookies txt file") { ImportRow(it) },
            SettingsSections.Row("age restricted members only premium signed playback") { SignedPlaybackRow() },
        ),
    ))
}

private fun CoroutineScope.run(app: AppState, block: suspend () -> Unit) = launch {
    try { block() } catch (e: AccountError) { app.toast(e.message ?: tr("Couldn't sign in")) } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        app.toast(tr("Couldn't sign in") + ": " + (e.message ?: e::class.simpleName))
    }
}

/** sign in through the platform's web view, or (where there is none) a cookies.txt file */
/** the sign-in in progress (its web view is open); Cancel in [SigningInDialog] stops it */
private var signInJob: kotlinx.coroutines.Job? = null
/** Cancel was pressed: the dialog goes at once, while Google's window is being closed */
private var cancelled by androidx.compose.runtime.mutableStateOf(false)

/** stops the sign-in under way: its job, and the platform's page or window (the desktop helper's process) */
internal fun cancelSignIn() {
    cancelled = true
    signInJob?.cancel()
    Accounts.launcher?.cancel()
}

/** Sign in from outside the account menu (the first-start page) */
fun startSignIn(app: AppState) = signIn(app, app.scope)

private fun signIn(app: AppState, scope: CoroutineScope, add: Boolean = false) {
    // the website alone can't sign in: YouTube takes a signed-in call only with an Authorization header, which it
    // refuses to other sites (CORS preflight 403), and ignores the token anywhere else (tested 2026-11-24)
    if (app.platform.instant) { FullMode.ask(tr("Sign in")); return }
    // the extension and Tubeless on youtube.com use the browser's own YouTube session: sign in there, as on youtube.com;
    // the app notices the new session by itself (WebAuth.watch)
    if (app.platform.name == "web") { app.platform.openExternal("https://accounts.google.com/ServiceLogin?service=youtube&continue=https://www.youtube.com/"); return }
    // no sign-in window in this build (the helper wasn't built or bundled): say why a file picker opens
    if (Accounts.launcher?.available != true) {
        app.toast(tr("This build has no sign-in window. Pick a cookies.txt exported from your browser."))
        importCookies(app, scope); return
    }
    // the app's own scope: the menu that started it closes at once (its scope would cancel the sign-in)
    cancelled = false
    signInJob = app.scope.run(app) {
        if (Accounts.signIn(add) && Accounts.pending.value == null) {
            // back on Home, as YouTube's app after a sign-in (Home reloads for the account: accountChanged)
            if (app.platform.touch) app.go(Screen.Home)
            Accounts.identity?.let { app.toast(tr("Signed in as {n}").replace("{n}", it.name)) }
        }
    }
}

/** where a Sign in button can open Google's sign-in in the app (not the plain website) */
fun canSignInHere(app: AppState) = !app.platform.instant && Accounts.launcher?.available == true

private fun importCookies(app: AppState, scope: CoroutineScope) {
    app.platform.openTextFile { text ->
        if (text != null) scope.run(app) { if (Accounts.import(text) && Accounts.pending.value == null) app.toast(tr("Signed in")) }
    }
}

@Composable
fun Avatar(url: String?, name: String, size: Dp) {
    Box(Modifier.size(size).clip(CircleShape).background(accent), contentAlignment = Alignment.Center) {
        Text(name.trim().take(1).uppercase(), color = Color.White, fontSize = (size.value * 0.45f).sp, fontWeight = FontWeight.Medium)
        if (url != null) AsyncImage(url, name, Modifier.size(size), contentScale = ContentScale.Crop)
    }
}

/** while Google's sign-in window is open: says where to look, and Cancel closes that window */
@Composable
private fun SigningInDialog() {
    AlertDialog(
        onDismissRequest = {},
        icon = { Wordmark(height = 22.dp) },
        title = { Text(tr("Sign in to YouTube")) },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(14.dp))
                Text(tr("Finish signing in with Google in the window that opened. It closes on its own when you're done."),
                    fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { cancelSignIn() }) { Text(tr("Cancel")) } },
    )
}

/** a launcher that shows Google's page inside the app (no "look at the other window" dialog) */
interface InAppLauncherMarker

/**
 * Google's sign-in inside the app window, as youtube.com navigates to Google's page and back: the app's
 * own bar with a back arrow, and under it the area the platform's web view is put in (desktop: a WKWebView
 * in the same window). While YouTube's hand-off runs the web view is hidden and this says "Signing you in";
 * when it's done the page goes and Home loads signed in.
 */
@Composable
fun InAppSignIn(app: AppState) {
    val phase by Accounts.signInScreen.collectAsState()
    val p = phase ?: return
    val c = MaterialTheme.colorScheme
    val density = androidx.compose.ui.platform.LocalDensity.current
    DisposableEffect(Unit) { onDispose { Accounts.signInArea.value = null } }
    Column(Modifier.fillMaxSize().background(c.background)
        .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } }) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { cancelSignIn() }) { Icon(Sym.ArrowBack.outline, tr("Back")) }
            Text(tr("Sign in to YouTube"), fontSize = 18.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 8.dp))
        }
        HorizontalDivider()
        Box(
            Modifier.fillMaxSize().onGloballyPositioned { lc ->
                val r = lc.boundsInWindow()
                val k = density.density
                Accounts.signInArea.value = listOf(r.left / k, r.top / k, r.width / k, r.height / k)
            },
            contentAlignment = Alignment.Center,
        ) {
            if (p != "google") Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 3.dp)
                Text(if (p == "finishing") tr("Signing you in…") else tr("Opening Google sign-in…"),
                    color = c.onSurfaceVariant, fontSize = 14.sp, modifier = Modifier.padding(top = 14.dp))
            }
        }
    }
}

/** the top bar's account button */
@Composable
fun AccountButton(app: AppState) {
    val data by Accounts.state.collectAsState()
    val busy by Accounts.busy.collectAsState()
    val pending by Accounts.pending.collectAsState()
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    val id = data.sessions.firstOrNull { it.key == data.current }?.identity
    // app.account changes on every sign-in and sign-out (the browser's and the code's sessions are no Compose state)
    val browser = app.account >= 0 && Accounts.browserSession
    val browserId by Accounts.browserIdentity.collectAsState()
    LaunchedEffect(browser, app.account) { if (browser && id == null && !Accounts.tokenSession) runCatching { Accounts.loadBrowserIdentity() } }
    if (Parity.on("acctmenu") && !open) androidx.compose.runtime.LaunchedEffect(Unit) { open = true; Parity.flags.remove("acctmenu") }
    pending?.let { ChannelPicker(app, it) }
    val inApp by Accounts.signInScreen.collectAsState()
    // the separate sign-in window (Windows, Linux): say where to look; the in-app page needs no dialog
    if (busy && !cancelled && inApp == null && signInJob?.isActive == true && Accounts.launcher !is InAppLauncherMarker) SigningInDialog()
    Box(Modifier.padding(horizontal = 6.dp)) {
        when {
            busy -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            id != null -> Box(Modifier.clip(CircleShape).clickable { open = true }.padding(4.dp)) { Avatar(id.photo, id.name, 30.dp) }
            browser -> Box(Modifier.clip(CircleShape).clickable { open = true }.padding(4.dp)) { Avatar(browserId?.photo, browserId?.name ?: "Y", 30.dp) }
            // a phone's top bar has no room for the pill (it wrapped to "Sig / n in"): the person icon alone, as YouTube's app
            app.platform.touch -> Box(Modifier.size(40.dp).clip(CircleShape).clickable { signIn(app, scope) }, contentAlignment = Alignment.Center) {
                Icon(Sym.Person.outline, tr("Sign in"), Modifier.size(24.dp), tint = accent)
            }
            else -> {
                // the web's outlined "Sign in" pill
                val c = MaterialTheme.colorScheme
                Row(
                    Modifier.height(36.dp).clip(RoundedCornerShape(18.dp)).border(1.dp, c.outlineVariant, RoundedCornerShape(18.dp))
                        .clickable { signIn(app, scope) }.padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Sym.Person.outline, null, Modifier.size(20.dp), tint = accent)
                    Spacer(Modifier.width(6.dp))
                    Text(tr("Sign in"), color = accent, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false)
                }
            }
        }
        DropdownMenu(open, { open = false }) {
            AccountMenu(app, data.sessions, data.current) { open = false }
        }
    }
}

@Composable
private fun AccountMenu(app: AppState, sessions: List<Session>, current: String?, close: () -> Unit) {
    val scope = rememberCoroutineScope()
    val s = sessions.firstOrNull { it.key == current }
    // the browser's own session (extension, Tubeless on youtube.com): its name and picture from YouTube
    val id = s?.identity ?: Accounts.browserIdentity.collectAsState().value?.takeIf { s == null && Accounts.browserSession && !Accounts.tokenSession }
    Column(Modifier.widthIn(min = 260.dp, max = 340.dp)) {
        if (id != null) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(id.photo, id.name, 40.dp)
                Column(Modifier.padding(start = 12.dp)) {
                    Text(id.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    (id.handle ?: id.email)?.let { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
                }
            }
            HorizontalDivider()
        } else if (Accounts.tokenSession) {
            MenuLine(tr("Signed in with your Google account"), null) {}
            HorizontalDivider()
        } else if (Accounts.browserSession) {
            MenuLine(tr("Signed in with this browser's YouTube session"), null) {}
            HorizontalDivider()
        }
        // every other channel and account to switch to
        val others = sessions.flatMap { ss -> ss.identities.map { ss to it } }.filter { (ss, i) -> !(ss.key == current && i.key == ss.active) }
        // YouTube's menu always has "Switch account"; with one account and channel it lists just that one
        var listMe by remember { mutableStateOf(false) }
        if (others.isEmpty() && id != null) {
            MenuLine(tr("Switch account"), Sym.Person) { listMe = !listMe }
            if (listMe) { IdentityLine(id) { close() }; HorizontalDivider() }
        }
        if (others.isNotEmpty()) {
            Text(tr("Switch account"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 2.dp))
            for ((ss, i) in others) IdentityLine(i) { close(); app.scope.run(app) { Accounts.choose(ss.key, i.key) } }
            HorizontalDivider()
        }
        if (Accounts.tokenSession) MenuLine(tr("Sign out"), Sym.Close) { close(); app.scope.run(app) { TvSignIn.signOut() } }
        else MenuLine(tr("Add account"), Sym.Add) { close(); signIn(app, scope, add = true) }
        if (s != null) MenuLine(tr("Sign out"), Sym.Close) { close(); app.scope.run(app) { Accounts.signOut() } }
        MenuLine(tr("Settings"), Sym.Settings) { close(); app.go(Screen.Settings) }
    }
}

@Composable
private fun MenuLine(text: String, icon: Symbol?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) { Icon(icon.outline, null, Modifier.size(22.dp)); Spacer(Modifier.width(14.dp)) }
        Text(text, fontSize = 14.sp)
    }
}

@Composable
private fun IdentityLine(i: Identity, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Avatar(i.photo, i.name, 32.dp)
        Column(Modifier.padding(start = 12.dp)) {
            Text(i.name, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            (i.handle ?: i.email)?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
        }
    }
}

/** a Google account with several channels: which one the app acts as */
@Composable
private fun ChannelPicker(app: AppState, s: Session) {
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { Accounts.cancelPending() },
        title = { Text(tr("Choose a channel")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(tr("This Google account has more than one YouTube channel. Pick the one to use."), fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
                for (i in s.identities) IdentityLine(i) { scope.run(app) { Accounts.choose(s.key, i.key) } }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { Accounts.cancelPending() }) { Text(tr("Cancel")) } },
    )
}

// ------------------------------------------------------------------ Settings → Account

@Composable
private fun AccountRows(app: AppState) {
    val data by Accounts.state.collectAsState()
    val busy by Accounts.busy.collectAsState()
    val scope = rememberCoroutineScope()
    val id = data.sessions.firstOrNull { it.key == data.current }?.identity
    Column(Modifier.fillMaxWidth()) {
        if (id != null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(id.photo, id.name, 40.dp)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(id.name, style = MaterialTheme.typography.titleSmall)
                    Text(listOfNotNull(id.handle, id.email).joinToString(" · ").ifEmpty { tr("Signed in") }, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { scope.run(app) { Accounts.signOut() } }) { Text(tr("Sign out")) }
            }
            val others = data.sessions.flatMap { ss -> ss.identities.map { ss to it } }.filter { (ss, i) -> !(ss.key == data.current && i.key == ss.active) }
            for ((ss, i) in others) IdentityLine(i) { scope.run(app) { Accounts.choose(ss.key, i.key) } }
            Line(tr("Add account"), tr("Another Google account, kept next to this one")) { signIn(app, scope, add = true) }
        } else {
            Line(tr("Sign in"), tr("Your Home, subscriptions, history, Watch Later and likes from your YouTube account. Videos still play without it, and nothing is signed in on the video streams themselves.")) {
                if (!busy) signIn(app, scope)
            }
        }
    }
}

@Composable
private fun ImportRow(app: AppState) {
    val scope = rememberCoroutineScope()
    Line(tr("Import cookies"), tr("Sign in with a cookies.txt file exported from a browser where you're signed in to YouTube")) { importCookies(app, scope) }
}

@Composable
private fun SignedPlaybackRow() {
    val data by Accounts.state.collectAsState()
    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable { Accounts.setSignedPlayback(!data.signedPlayback) }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(tr("Use my account for restricted videos"), style = MaterialTheme.typography.titleSmall)
            Text(tr("Age-restricted and members-only videos, and Premium's higher bitrate, are asked for signed in. Everything else plays anonymously."),
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 3.dp))
        }
        Switch(data.signedPlayback, { Accounts.setSignedPlayback(it) })
    }
}

@Composable
private fun Line(title: String, sub: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.Center) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        if (sub.isNotEmpty()) Text(sub, fontSize = 13.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 3.dp))
    }
}

/**
 * The top of the You tab, as YouTube's app: signed in, the picture, the name and "@handle", and the
 * pills (Switch account, Add account, Sign out); signed out, the person icon, a line and Sign in.
 */
@Composable
fun YouHeader(app: AppState) {
    val data by Accounts.state.collectAsState()
    val busy by Accounts.busy.collectAsState()
    val scope = rememberCoroutineScope()
    val s = data.sessions.firstOrNull { it.key == data.current }
    // the browser's own YouTube session (the extension, Tubeless on youtube.com): its name and picture from YouTube
    val browser = s == null && app.account >= 0 && Accounts.browserSession && !Accounts.tokenSession
    val browserId by Accounts.browserIdentity.collectAsState()
    LaunchedEffect(browser, app.account) { if (browser) runCatching { Accounts.loadBrowserIdentity() } }
    val id = s?.identity ?: browserId?.takeIf { browser }
    val c = MaterialTheme.colorScheme
    if (id == null && !(app.account >= 0 && Accounts.browserSession)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(56.dp).clip(CircleShape).background(c.surfaceVariant), contentAlignment = Alignment.Center) {
                Icon(Sym.Person.outline, null, Modifier.size(32.dp))
            }
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(tr("Enjoy your favorite videos"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(tr("Sign in to access videos that you've liked or saved"), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            }
        }
        Row(Modifier.padding(start = 86.dp, bottom = 8.dp)) {
            if (busy) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            else Row(
                Modifier.height(36.dp).clip(RoundedCornerShape(18.dp)).border(1.dp, c.outlineVariant, RoundedCornerShape(18.dp))
                    .clickable { signIn(app, scope) }.padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Sym.Person.outline, null, Modifier.size(20.dp), tint = accent)
                Spacer(Modifier.width(6.dp))
                Text(tr("Sign in"), color = accent, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false)
            }
        }
        return
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Avatar(id?.photo, id?.name ?: "Y", 64.dp)
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text(id?.name ?: tr(if (Accounts.tokenSession) "Signed in with your Google account" else "Signed in with this browser's YouTube session"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            (id?.handle ?: id?.email)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, maxLines = 1) }
        }
    }
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (browser) {
            // signed in by the browser itself: switching and the account live on YouTube and Google, as there
            YouPill(Sym.Person, tr("Switch account")) { app.platform.openExternal("https://www.youtube.com/channel_switcher") }
            YouPill(Sym.Settings, tr("Google Account")) { app.platform.openExternal("https://myaccount.google.com/") }
            return@Row
        }
        Box {
            YouPill(Sym.Person, tr("Switch account")) { menu = true }
            DropdownMenu(menu, { menu = false }) { AccountMenu(app, data.sessions, data.current) { menu = false } }
        }
        if (Accounts.tokenSession) YouPill(Sym.Close, tr("Sign out")) { app.scope.run(app) { TvSignIn.signOut() } }
        else YouPill(Sym.Add, tr("Add account")) { signIn(app, scope, add = true) }
        if (s != null) YouPill(Sym.Close, tr("Sign out")) { app.scope.run(app) { Accounts.signOut() } }
    }
}

@Composable
private fun YouPill(icon: Symbol, label: String, onClick: () -> Unit) {
    Row(
        Modifier.height(32.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon.outline, null, Modifier.size(18.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 6.dp), maxLines = 1)
    }
}

// ------------------------------------------------------------------ Instant mode: sign in with a code (TvSignIn)

/**
 * The website's sign-in (no cookies can reach YouTube from a page): a code from Google, entered at
 * google.com/device on any phone or computer, as on a TV. The dialog waits for it and closes signed in.
 */
object CodeSignIn {
    var code by mutableStateOf<TvSignIn.Code?>(null)
    var error by mutableStateOf<String?>(null)
    private var job: kotlinx.coroutines.Job? = null

    fun begin(app: AppState) {
        job?.cancel()
        error = null
        job = app.scope.launch {
            try {
                val c = TvSignIn.start()
                code = c
                if (TvSignIn.await(c)) { code = null; app.toast(tr("Signed in")) }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                error = e.message ?: tr("Couldn't sign in")
            }
        }
    }

    fun cancel() { job?.cancel(); job = null; code = null; error = null }
}

@Composable
fun CodeSignInDialog(app: AppState) {
    val c = CodeSignIn.code
    val err = CodeSignIn.error
    if (c == null && err == null) return
    AlertDialog(
        onDismissRequest = {},
        title = { Text(tr("Sign in")) },
        text = {
            Column {
                if (err != null) Text(err, style = MaterialTheme.typography.bodyMedium)
                else if (c != null) {
                    Text(tr("On this or any other device, go to {u} and enter this code:").replace("{u}", c.url.removePrefix("https://")), style = MaterialTheme.typography.bodyMedium)
                    Text(c.user, Modifier.padding(vertical = 16.dp), fontSize = 32.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(tr("Waiting for you to allow it…"), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = {
            if (err != null) TextButton(onClick = { CodeSignIn.begin(app) }) { Text(tr("Try again")) }
            else if (c != null) TextButton(onClick = { app.platform.openExternal(c.url) }) { Text(tr("Open {u}").replace("{u}", c.url.removePrefix("https://"))) }
        },
        dismissButton = { TextButton(onClick = { CodeSignIn.cancel() }) { Text(tr("Cancel")) } },
    )
}
