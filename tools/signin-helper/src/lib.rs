//! Tubeless's in-window sign-in (macOS): Google's own sign-in page as a WKWebView placed inside the app's
//! main window, over the content area, the way youtube.com navigates to Google's page and back. Loaded
//! into the app's own process (desktop/EmbeddedSignIn.kt, through JNA); no second window, no second app.
//!
//! The page goes Google sign-in → YouTube's sign-in hand-off → www.youtube.com. The web view hides itself
//! the moment the navigation leaves Google, so no youtube.com page is ever shown; once youtube.com holds a
//! session (SAPISID) the youtube.com cookies are the result and the web view is removed.
//!
//! C API (every call returns at once; the work runs on the main thread, AppKit's):
//!   kw_start(mode, window_title, profile, x, y, w, h) -> generation   mode 0 sign in, 1 add account,
//!                                                       2 refresh (hidden), 3 forget (hidden)
//!   kw_bounds(x, y, w, h)    the content area moved / resized (logical points, top-left origin)
//!   kw_cancel()              closes it (the result becomes {"ok":false,"error":"cancelled"})
//!   kw_status() -> char*     {"gen":n,"phase":"loading|google|finishing|done","host":"…","result":{…}|null}
//!   kw_free(char*)
//! Results have the shape of the signin-helper's output: {"ok":true,"cookies":[…]} or {"ok":false,"error":"…"}.
//! Never logs or prints a cookie value.

use std::ffi::{c_char, c_void, CStr, CString};
use std::sync::Mutex;

use serde_json::{json, Value};

struct Status {
    gen: u64,
    phase: &'static str,
    host: String,
    result: Option<Value>,
    /// the last main-frame navigation's host (set from the delegate, read by the tick)
    nav: Option<String>,
    /// what the last check saw (counts and flags, never a value)
    diag: String,
}

static STATUS: Mutex<Status> = Mutex::new(Status { gen: 0, phase: "idle", host: String::new(), result: None, nav: None, diag: String::new() });

pub const HANDOFF_URL: &str =
    "https://www.youtube.com/signin?action_handle_signin=true&app=desktop&next=https%3A%2F%2Fwww.youtube.com%2F";
pub const LOGIN_URL: &str = "https://accounts.google.com/ServiceLogin?service=youtube&uilel=3&passive=true&continue=https%3A%2F%2Fwww.youtube.com%2Fsignin%3Faction_handle_signin%3Dtrue%26app%3Ddesktop%26next%3Dhttps%253A%252F%252Fwww.youtube.com%252F";
pub const ADD_URL: &str = "https://accounts.google.com/AddSession?service=youtube&continue=https%3A%2F%2Fwww.youtube.com%2Fsignin%3Faction_handle_signin%3Dtrue%26app%3Ddesktop%26next%3Dhttps%253A%252F%252Fwww.youtube.com%252F";
pub const SAFARI_UA: &str =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15";

/// FNV-1a, 128 bit: the WKWebsiteDataStore identifier of a profile (same as the signin-helper's)
pub fn store_id(profile: &str) -> [u8; 16] {
    let mut h: u128 = 0x6c62272e07bb014262b821756295c58d;
    // the prefix predates the rename to Tubeless: changing it would orphan every saved web view store
    for b in format!("kestrel-signin:{profile}").bytes() {
        h ^= b as u128;
        h = h.wrapping_mul(0x0000000001000000000000000000013B);
    }
    h.to_be_bytes()
}

pub fn is_youtube(domain: &str) -> bool {
    let d = domain.trim_start_matches('.');
    d == "youtube.com" || d.ends_with(".youtube.com")
}

pub fn is_google(domain: &str) -> bool {
    let d = domain.trim_start_matches('.');
    d == "google.com" || d.ends_with(".google.com")
}

pub fn host_of(url: &str) -> String {
    url.split("://").nth(1).unwrap_or("").split(['/', '?', '#', ':']).next().unwrap_or("").to_lowercase()
}

/// the pages the user has to see: Google's own sign-in (accounts.google.com and its helpers)
pub fn shows(host: &str) -> bool {
    is_google(host) || host.is_empty() || host == "about:blank"
}

fn set_result(gen: u64, v: Value) {
    let mut s = STATUS.lock().unwrap();
    if s.gen == gen && s.result.is_none() {
        s.result = Some(v);
        s.phase = "done";
    }
}

#[no_mangle]
pub extern "C" fn kw_status() -> *mut c_char {
    let s = STATUS.lock().unwrap();
    let v = json!({ "gen": s.gen, "phase": s.phase, "host": s.host, "nav": s.nav, "diag": s.diag, "result": s.result });
    CString::new(v.to_string()).unwrap_or_default().into_raw()
}

#[no_mangle]
pub extern "C" fn kw_free(p: *mut c_char) {
    if !p.is_null() {
        unsafe { drop(CString::from_raw(p)) };
    }
}

fn cstr(p: *const c_char) -> String {
    if p.is_null() {
        return String::new();
    }
    unsafe { CStr::from_ptr(p) }.to_string_lossy().into_owned()
}

#[cfg(target_os = "macos")]
mod mac {
    use super::*;
    use std::cell::RefCell;
    use std::ptr::NonNull;
    use std::time::{Duration, Instant};

    use objc2::rc::Retained;
    use objc2::MainThreadMarker;
    use objc2_app_kit::{NSApplication, NSView};
    use raw_window_handle::{AppKitWindowHandle, HandleError, HasWindowHandle, RawWindowHandle, WindowHandle};
    use wry::dpi::{LogicalPosition, LogicalSize};
    use wry::{PageLoadEvent, Rect, WebView, WebViewBuilder, WebViewBuilderExtDarwin, WebViewExtMacOS};

    #[allow(non_camel_case_types)]
    type dispatch_function_t = extern "C" fn(*mut c_void);
    extern "C" {
        static _dispatch_main_q: c_void;
        fn dispatch_async_f(queue: *const c_void, context: *mut c_void, work: dispatch_function_t);
        fn dispatch_after_f(when: u64, queue: *const c_void, context: *mut c_void, work: dispatch_function_t);
        fn dispatch_time(when: u64, delta: i64) -> u64;
    }

    /// runs [f] on the main thread, later (never blocks the caller)
    pub fn on_main<F: FnOnce() + Send + 'static>(f: F) {
        extern "C" fn run(ctx: *mut c_void) {
            let f: Box<Box<dyn FnOnce()>> = unsafe { Box::from_raw(ctx as *mut Box<dyn FnOnce()>) };
            f();
        }
        let b: Box<Box<dyn FnOnce()>> = Box::new(Box::new(f));
        unsafe { dispatch_async_f(&_dispatch_main_q, Box::into_raw(b) as *mut c_void, run) };
    }

    fn after(ms: i64, gen: u64) {
        extern "C" fn run(ctx: *mut c_void) {
            tick(ctx as u64);
        }
        unsafe { dispatch_after_f(dispatch_time(0, ms * 1_000_000), &_dispatch_main_q, gen as *mut c_void, run) };
    }

    struct Parent(NonNull<c_void>);
    impl HasWindowHandle for Parent {
        fn window_handle(&self) -> Result<WindowHandle<'_>, HandleError> {
            let h = AppKitWindowHandle::new(self.0);
            Ok(unsafe { WindowHandle::borrow_raw(RawWindowHandle::AppKit(h)) })
        }
    }

    struct Embedded {
        gen: u64,
        mode: i32,
        webview: WebView,
        started: Instant,
        seen_at: Option<Instant>,
        handed_off: bool,
        loaded_at: Option<Instant>,
        hidden_since: Option<Instant>,
        shown: bool,
    }

    thread_local! {
        static VIEW: RefCell<Option<Embedded>> = const { RefCell::new(None) };
        /// the WKWebView itself, for hiding it from inside a navigation callback
        static RAW: RefCell<Option<Retained<NSView>>> = const { RefCell::new(None) };
    }

    const SETTLE: Duration = Duration::from_millis(1200);
    const SIGNIN_TIMEOUT: Duration = Duration::from_secs(15 * 60);
    const HIDDEN_TIMEOUT: Duration = Duration::from_secs(45);
    /// hidden on YouTube's hand-off but no session after this: shown again (a page that wants the user)
    const HIDDEN_GRACE: Duration = Duration::from_secs(20);

    fn rect(x: f64, y: f64, w: f64, h: f64) -> Rect {
        Rect { position: LogicalPosition::new(x, y).into(), size: LogicalSize::new(w.max(1.0), h.max(1.0)).into() }
    }

    /// see-through rather than hidden: WebKit throttles a hidden view's timers and loads, and YouTube's
    /// hand-off (redirects run by script) would stall behind them
    fn set_hidden(hidden: bool) {
        RAW.with(|r| {
            if let Some(v) = r.borrow().as_ref() {
                v.setAlphaValue(if hidden { 0.0 } else { 1.0 });
            }
        });
    }

    fn close() {
        RAW.with(|r| r.borrow_mut().take());
        // dropping the WebView removes it from the window
        let v = VIEW.with(|v| v.borrow_mut().take());
        drop(v);
    }

    fn main_view(title: &str) -> Option<Retained<NSView>> {
        let mtm = MainThreadMarker::new()?;
        let app = NSApplication::sharedApplication(mtm);
        let windows = app.windows();
        let mut pick = None;
        for w in windows.iter() {
            if w.title().to_string() == title {
                pick = Some(w);
                break;
            }
        }
        let w = pick.or_else(|| app.mainWindow()).or_else(|| app.keyWindow())?;
        w.contentView()
    }

    pub fn start(gen: u64, mode: i32, title: String, profile: String, r: (f64, f64, f64, f64)) {
        close();
        let Some(parent) = main_view(&title) else {
            set_result(gen, json!({ "ok": false, "error": "no window" }));
            return;
        };
        let visible_mode = mode == 0 || mode == 1;
        let url = match mode {
            0 => LOGIN_URL,
            1 => ADD_URL,
            2 => "https://www.youtube.com/",
            _ => "about:blank",
        };
        let builder = WebViewBuilder::new()
            .with_url(url)
            .with_user_agent(SAFARI_UA)
            .with_bounds(rect(r.0, r.1, r.2, r.3))
            .with_data_store_identifier(store_id(&profile))
            .with_navigation_handler(move |u| {
                let mut s = STATUS.lock().unwrap();
                if s.gen == gen {
                    s.nav = Some(host_of(&u));
                }
                drop(s);
                // leaving Google: hidden at once, before YouTube's page can paint
                if visible_mode && !shows(&host_of(&u)) {
                    set_hidden(true);
                }
                true
            })
            .with_on_page_load_handler(move |ev, u| {
                if matches!(ev, PageLoadEvent::Finished) {
                    let mut s = STATUS.lock().unwrap();
                    if s.gen == gen {
                        s.host = host_of(&u);
                    }
                }
            });
        let p = Parent(NonNull::new(Retained::as_ptr(&parent) as *mut c_void).unwrap());
        let webview = match builder.build_as_child(&p) {
            Ok(w) => w,
            Err(e) => {
                set_result(gen, json!({ "ok": false, "error": format!("web view: {e}") }));
                return;
            }
        };
        if mode == 3 {
            let ok = webview.clear_all_browsing_data().is_ok();
            drop(webview);
            set_result(gen, json!({ "ok": ok }));
            return;
        }
        let raw: Retained<NSView> = Retained::into_super(Retained::into_super(webview.webview()));
        RAW.with(|r| *r.borrow_mut() = Some(raw));
        // nothing shows until Google's page has painted (and never in the hidden modes)
        set_hidden(true);
        VIEW.with(|v| {
            *v.borrow_mut() = Some(Embedded {
                gen,
                mode,
                webview,
                started: Instant::now(),
                seen_at: None,
                handed_off: false,
                loaded_at: None,
                hidden_since: None,
                shown: false,
            })
        });
        STATUS.lock().unwrap().phase = if visible_mode { "loading" } else { "finishing" };
        after(250, gen);
    }

    pub fn bounds(x: f64, y: f64, w: f64, h: f64) {
        VIEW.with(|v| {
            if let Some(e) = v.borrow().as_ref() {
                let _ = e.webview.set_bounds(rect(x, y, w, h));
            }
        });
    }

    pub fn cancel() {
        let gen = VIEW.with(|v| v.borrow().as_ref().map(|e| e.gen));
        close();
        if let Some(g) = gen {
            set_result(g, json!({ "ok": false, "error": "cancelled" }));
        }
    }

    fn finish(gen: u64, v: Value) {
        close();
        set_result(gen, v);
    }

    fn tick(gen: u64) {
        // what to do is worked out with the view borrowed; anything that runs the run loop (cookies())
        // happens with only a short borrow, so callbacks fired meanwhile never meet a held borrow
        let (mode, started) = match VIEW.with(|v| v.borrow().as_ref().filter(|e| e.gen == gen).map(|e| (e.mode, e.started))) {
            Some(x) => x,
            None => return,
        };
        let visible_mode = mode == 0 || mode == 1;
        let timeout = if visible_mode { SIGNIN_TIMEOUT } else { HIDDEN_TIMEOUT };
        if started.elapsed() > timeout {
            finish(gen, json!({ "ok": false, "error": "timeout" }));
            return;
        }
        let (nav, page) = {
            let s = STATUS.lock().unwrap();
            (s.nav.clone(), s.host.clone())
        };
        let cookies = VIEW.with(|v| v.borrow().as_ref().map(|e| e.webview.cookies()));
        let cookies = match cookies {
            Some(Ok(c)) => c,
            Some(Err(_)) => {
                after(500, gen);
                return;
            }
            None => return,
        };
        // the view may have been closed (cancel) while cookies() ran the run loop
        if VIEW.with(|v| v.borrow().as_ref().map(|e| e.gen) != Some(gen)) {
            return;
        }
        let sapisid = cookies.iter().any(|c| {
            is_youtube(c.domain().unwrap_or_default())
                && matches!(c.name(), "SAPISID" | "__Secure-3PAPISID" | "__Secure-1PAPISID")
                && !c.value().is_empty()
        });
        let google = cookies.iter().any(|c| is_google(c.domain().unwrap_or_default()) && matches!(c.name(), "SID" | "__Secure-1PSID" | "__Secure-3PSID"));
        let here = nav.clone().unwrap_or_default();
        let yt = cookies.iter().filter(|c| is_youtube(c.domain().unwrap_or_default())).count();
        STATUS.lock().unwrap().diag = format!("yt cookies {yt}, SAPISID {sapisid}, google session {google}");
        let mut out: Option<Value> = None;
        VIEW.with(|v| {
            let mut b = v.borrow_mut();
            let Some(e) = b.as_mut() else { return };
            if visible_mode {
                // Google's page: shown once it has painted; anything else: hidden (YouTube's hand-off)
                if shows(&here) {
                    if !e.shown && is_google(&page) {
                        e.shown = true;
                    }
                    if e.shown {
                        set_hidden(false);
                    }
                    e.hidden_since = None;
                    STATUS.lock().unwrap().phase = if e.shown { "google" } else { "loading" };
                } else {
                    set_hidden(true);
                    let since = *e.hidden_since.get_or_insert_with(Instant::now);
                    STATUS.lock().unwrap().phase = "finishing";
                    if since.elapsed() > HIDDEN_GRACE && !sapisid {
                        // something on the way wants the user after all: show it rather than hang
                        set_hidden(false);
                        STATUS.lock().unwrap().phase = "google";
                    }
                }
                if sapisid && is_youtube(&here) {
                    match e.seen_at {
                        None => e.seen_at = Some(Instant::now()),
                        Some(t) if t.elapsed() >= SETTLE => {
                            out = Some(json!({ "ok": true, "cookies": youtube_cookies(&cookies) }));
                        }
                        _ => {}
                    }
                } else if is_youtube(&here) && google && !sapisid && !e.handed_off {
                    e.handed_off = true;
                    // through Google's ServiceLogin (the session is there, so it hands straight back with the
                    // auth YouTube needs); youtube.com/signin opened bare shows "There was an issue signing you in"
                    let _ = e.webview.load_url(LOGIN_URL);
                }
            } else {
                if is_youtube(&page) && e.loaded_at.is_none() {
                    e.loaded_at = Some(Instant::now());
                }
                if let Some(t) = e.loaded_at {
                    if t.elapsed() >= Duration::from_secs(2) {
                        out = Some(if sapisid {
                            json!({ "ok": true, "cookies": youtube_cookies(&cookies) })
                        } else {
                            json!({ "ok": false, "error": "signed-out" })
                        });
                    }
                }
            }
        });
        match out {
            Some(v) => finish(gen, v),
            None => after(400, gen),
        }
    }

    fn youtube_cookies(cookies: &[wry::cookie::Cookie<'static>]) -> Vec<Value> {
        cookies
            .iter()
            .filter(|c| is_youtube(c.domain().unwrap_or_default()))
            .map(|c| {
                let domain = c.domain().unwrap_or_default();
                json!({
                    "name": c.name(),
                    "value": c.value(),
                    "domain": if domain.starts_with('.') || domain == "www.youtube.com" { domain.to_string() } else { format!(".{domain}") },
                    "path": c.path().unwrap_or("/"),
                    "expires": c.expires_datetime().map(|t| t.unix_timestamp()).unwrap_or(0),
                    "secure": c.secure().unwrap_or(false),
                    "httpOnly": c.http_only().unwrap_or(false),
                })
            })
            .collect()
    }
}

#[no_mangle]
pub extern "C" fn kw_start(mode: i32, title: *const c_char, profile: *const c_char, x: f64, y: f64, w: f64, h: f64) -> u64 {
    let title = cstr(title);
    let mut profile = cstr(profile);
    profile.retain(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_');
    let gen = {
        let mut s = STATUS.lock().unwrap();
        // a run still open ends as cancelled
        if s.result.is_none() && s.gen > 0 {
            s.result = Some(json!({ "ok": false, "error": "cancelled" }));
        }
        s.gen += 1;
        s.phase = "loading";
        s.host.clear();
        s.nav = None;
        s.result = None;
        s.gen
    };
    #[cfg(target_os = "macos")]
    mac::on_main(move || mac::start(gen, mode, title, profile, (x, y, w, h)));
    #[cfg(not(target_os = "macos"))]
    {
        let _ = (mode, title, profile, x, y, w, h);
        set_result(gen, json!({ "ok": false, "error": "unsupported" }));
    }
    gen
}

#[no_mangle]
pub extern "C" fn kw_bounds(x: f64, y: f64, w: f64, h: f64) {
    #[cfg(target_os = "macos")]
    mac::on_main(move || mac::bounds(x, y, w, h));
    #[cfg(not(target_os = "macos"))]
    let _ = (x, y, w, h);
}

#[no_mangle]
pub extern "C" fn kw_cancel() {
    #[cfg(target_os = "macos")]
    mac::on_main(mac::cancel);
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn google_pages_show_youtube_pages_dont() {
        assert!(shows("accounts.google.com") && shows("myaccount.google.com"));
        assert!(!shows("www.youtube.com") && !shows("accounts.youtube.com") && !shows("consent.youtube.com"));
    }

    #[test]
    fn same_store_ids_as_the_helper() {
        assert_eq!(store_id("acct-1"), store_id("acct-1"));
        assert_ne!(store_id("acct-1"), store_id("acct-2"));
    }

    #[test]
    fn status_starts_idle_and_a_start_bumps_the_generation() {
        let p = kw_status();
        let v: Value = serde_json::from_str(&cstr(p)).unwrap();
        kw_free(p);
        assert!(v["result"].is_null());
    }
}
