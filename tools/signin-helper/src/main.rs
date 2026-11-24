//! Tubeless's desktop sign-in (docs/PLAN.md §1.3, stream S3). Ported from Spotless
//! `src-tauri/src/session.rs` + `crates/core/src/login.rs`, without Tauri.
//!
//! Opens Google's own sign-in page in the system web view (WKWebView, WebView2, WebKitGTK). The user
//! types into Google, never into Tubeless. The page continues through YouTube's sign-in hand-off to
//! www.youtube.com; once youtube.com holds a session (a SAPISID cookie) the youtube.com cookies are
//! printed as one JSON line on stdout and the helper exits. The app (desktop/SignIn.kt) reads that line.
//!
//! Modes:
//!   signin-helper [--add] [--profile ID] [--data-dir DIR] [--timeout SECS]
//!       visible sign-in window; --add opens Google's "add account" page (a fresh login)
//!   signin-helper --refresh [--profile ID] [--data-dir DIR]
//!       hidden: loads www.youtube.com from the profile's store so Google rotates the session
//!   signin-helper --forget [--profile ID] [--data-dir DIR]
//!       hidden: deletes everything the profile's store holds (sign-out)
//!
//! Each profile has its own persistent web view store (macOS 14+: a WKWebsiteDataStore identifier;
//! Windows / Linux: its own data directory), so every saved account can be refreshed on its own and
//! "add account" never disturbs the others.
//!
//! Output: {"ok":true,"cookies":[{name,value,domain,path,expires,secure,httpOnly}],"url":"…"}
//! or {"ok":false,"error":"cancelled"|"timeout"|"signed-out"|"…"}. Exit 0 on ok, 1 otherwise.

use std::path::PathBuf;
use std::time::{Duration, Instant};

use serde_json::{json, Value};
use tao::dpi::LogicalSize;
use tao::event::{Event, WindowEvent};
use tao::event_loop::{ControlFlow, EventLoopBuilder};
use tao::window::WindowBuilder;
use wry::{PageLoadEvent, WebContext, WebViewBuilder};

/// YouTube's own sign-in hand-off: turns the Google session into youtube.com cookies (SAPISID).
const HANDOFF_URL: &str =
    "https://www.youtube.com/signin?action_handle_signin=true&app=desktop&next=https%3A%2F%2Fwww.youtube.com%2F";
/// What youtube.com's own "Sign in" button opens: Google's login, continuing through [HANDOFF_URL].
const LOGIN_URL: &str = "https://accounts.google.com/ServiceLogin?service=youtube&uilel=3&passive=true&continue=https%3A%2F%2Fwww.youtube.com%2Fsignin%3Faction_handle_signin%3Dtrue%26app%3Ddesktop%26next%3Dhttps%253A%252F%252Fwww.youtube.com%252F";
/// Google's "add account": the fresh sign-in screen rather than straight through on the session it has.
const ADD_URL: &str = "https://accounts.google.com/AddSession?service=youtube&continue=https%3A%2F%2Fwww.youtube.com%2Fsignin%3Faction_handle_signin%3Dtrue%26app%3Ddesktop%26next%3Dhttps%253A%252F%252Fwww.youtube.com%252F";
const REFRESH_URL: &str = "https://www.youtube.com/";

/// WKWebView and WebKitGTK are WebKit: a Safari UA is the consistent spoof and the least likely to
/// get "This browser or app may not be secure". WebView2 is Chromium, whose client hints would
/// contradict any other UA, so it keeps its own (Edge).
#[cfg(not(target_os = "windows"))]
const LOGIN_UA: Option<&str> =
    Some("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.5 Safari/605.1.15");
#[cfg(target_os = "windows")]
const LOGIN_UA: Option<&str> = None;

const POLL: Duration = Duration::from_secs(1);
/// after SAPISID shows up: the rest of the hand-off's cookies land a beat later
const SETTLE: Duration = Duration::from_millis(1500);
const REFRESH_TIMEOUT: Duration = Duration::from_secs(45);

#[derive(Clone, Copy, PartialEq)]
enum Mode {
    SignIn,
    Add,
    Refresh,
    Forget,
}

struct Args {
    mode: Mode,
    profile: String,
    data_dir: Option<PathBuf>,
    timeout: Duration,
}

fn args() -> Args {
    let mut a = Args { mode: Mode::SignIn, profile: "default".into(), data_dir: None, timeout: Duration::from_secs(10 * 60) };
    let mut it = std::env::args().skip(1);
    while let Some(x) = it.next() {
        match x.as_str() {
            "--add" => a.mode = Mode::Add,
            "--refresh" => a.mode = Mode::Refresh,
            "--forget" => a.mode = Mode::Forget,
            "--profile" => a.profile = it.next().unwrap_or_default(),
            "--data-dir" => a.data_dir = it.next().map(PathBuf::from),
            "--timeout" => a.timeout = Duration::from_secs(it.next().and_then(|s| s.parse().ok()).unwrap_or(600)),
            "-h" | "--help" => {
                println!("signin-helper [--add|--refresh|--forget] [--profile ID] [--data-dir DIR] [--timeout SECS]");
                std::process::exit(0);
            }
            _ => {}
        }
    }
    a.profile.retain(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_');
    if a.profile.is_empty() {
        a.profile = "default".into();
    }
    a
}

/// FNV-1a, 128 bit: a stable 16-byte store identifier for a profile name (macOS)
#[allow(dead_code)]
fn store_id(profile: &str) -> [u8; 16] {
    let mut h: u128 = 0x6c62272e07bb014262b821756295c58d;
    for b in format!("tubeless-signin:{profile}").bytes() {
        h ^= b as u128;
        h = h.wrapping_mul(0x0000000001000000000000000000013B);
    }
    h.to_be_bytes()
}

fn is_youtube(domain: &str) -> bool {
    let d = domain.trim_start_matches('.');
    d == "youtube.com" || d.ends_with(".youtube.com")
}

fn is_google(domain: &str) -> bool {
    let d = domain.trim_start_matches('.');
    d == "google.com" || d.ends_with(".google.com")
}

fn host_of(url: &str) -> String {
    url.split("://").nth(1).unwrap_or("").split(['/', '?', '#', ':']).next().unwrap_or("").to_lowercase()
}

/// The youtube.com cookies, as Ck JSON (engine/Account.kt). Everything else (google.com) stays in the store.
fn youtube_cookies(cookies: &[wry::cookie::Cookie<'static>]) -> Vec<Value> {
    cookies
        .iter()
        .filter(|c| is_youtube(c.domain().unwrap_or_default()))
        .map(|c| {
            let domain = c.domain().unwrap_or_default();
            json!({
                "name": c.name(),
                "value": c.value(),
                // wry drops the leading dot; YouTube's session cookies are domain cookies
                "domain": if domain.starts_with('.') || domain == "www.youtube.com" { domain.to_string() } else { format!(".{domain}") },
                "path": c.path().unwrap_or("/"),
                "expires": c.expires_datetime().map(|t| t.unix_timestamp()).unwrap_or(0),
                "secure": c.secure().unwrap_or(false),
                "httpOnly": c.http_only().unwrap_or(false),
            })
        })
        .collect()
}

fn has_sapisid(cookies: &[wry::cookie::Cookie<'static>]) -> bool {
    cookies.iter().any(|c| {
        is_youtube(c.domain().unwrap_or_default())
            && matches!(c.name(), "SAPISID" | "__Secure-3PAPISID" | "__Secure-1PAPISID")
            && !c.value().is_empty()
    })
}

fn google_session(cookies: &[wry::cookie::Cookie<'static>]) -> bool {
    cookies
        .iter()
        .any(|c| is_google(c.domain().unwrap_or_default()) && matches!(c.name(), "SID" | "__Secure-1PSID" | "__Secure-3PSID"))
}

/// TUBELESS_SIGNIN_DEBUG=1: page loads and the polled URL on stderr (never cookie values)
fn debug() -> bool {
    std::env::var_os("TUBELESS_SIGNIN_DEBUG").is_some() || std::env::var_os("KESTREL_SIGNIN_DEBUG").is_some() // old name still counts
}

fn print(v: Value) {
    println!("{v}");
}

#[derive(Debug)]
enum Nudge {
    Loaded(String),
}

fn main() {
    let a = args();
    let event_loop = EventLoopBuilder::<Nudge>::with_user_event().build();
    let proxy = event_loop.create_proxy();
    let visible = matches!(a.mode, Mode::SignIn | Mode::Add);

    let window = WindowBuilder::new()
        .with_title("Sign in to YouTube · Tubeless")
        .with_inner_size(LogicalSize::new(480.0, 700.0))
        .with_visible(visible)
        .build(&event_loop)
        .expect("window");

    // per-profile store: a data directory on Windows / Linux
    let dir = a.data_dir.clone().map(|d| d.join(&a.profile));
    let mut ctx = WebContext::new(dir.clone());
    let start = match a.mode {
        Mode::SignIn => LOGIN_URL,
        Mode::Add => ADD_URL,
        Mode::Refresh => REFRESH_URL,
        Mode::Forget => "about:blank",
    };
    let mut builder = WebViewBuilder::new_with_web_context(&mut ctx)
        .with_url(start)
        .with_on_page_load_handler(move |ev, url| {
            if debug() {
                eprintln!("page {:?} {url}", matches!(ev, PageLoadEvent::Finished));
            }
            if matches!(ev, PageLoadEvent::Finished) {
                let _ = proxy.send_event(Nudge::Loaded(url));
            }
        });
    if let Some(ua) = LOGIN_UA {
        builder = builder.with_user_agent(ua);
    }
    #[cfg(target_os = "macos")]
    {
        use wry::WebViewBuilderExtDarwin;
        builder = builder.with_data_store_identifier(store_id(&a.profile));
    }
    #[cfg(any(target_os = "linux", target_os = "dragonfly", target_os = "freebsd", target_os = "openbsd", target_os = "netbsd"))]
    let webview = {
        use tao::platform::unix::WindowExtUnix;
        use wry::WebViewBuilderExtUnix;
        builder.build_gtk(window.default_vbox().expect("vbox")).expect("webview")
    };
    #[cfg(not(any(target_os = "linux", target_os = "dragonfly", target_os = "freebsd", target_os = "openbsd", target_os = "netbsd")))]
    let webview = builder.build(&window).expect("webview");

    if a.mode == Mode::Forget {
        let ok = webview.clear_all_browsing_data().is_ok();
        drop(webview);
        if let Some(d) = &dir {
            let _ = std::fs::remove_dir_all(d);
        }
        print(json!({ "ok": ok }));
        std::process::exit(if ok { 0 } else { 1 });
    }

    let started = Instant::now();
    let timeout = if a.mode == Mode::Refresh { REFRESH_TIMEOUT } else { a.timeout };
    let mut handed_off = false;
    let mut seen_at: Option<Instant> = None;
    let mut refresh_loaded: Option<Instant> = None;
    let mut done = false;
    let mut last_debug = String::new();

    // one fixed poll schedule: other events (mouse moves, redraws) must not push the next check
    // further out, or the check never runs while the user moves the mouse over the page
    let mut next_poll = Instant::now();
    event_loop.run(move |event, _, flow| {
        let now = Instant::now();
        let due = now >= next_poll;
        if due {
            next_poll = now + POLL;
        }
        *flow = ControlFlow::WaitUntil(next_poll);
        let mut finish = |v: Value, code: i32, flow: &mut ControlFlow| {
            if !done {
                done = true;
                print(v);
                // a clean exit code without waiting for the loop to wind down
                std::process::exit(code);
            }
            *flow = ControlFlow::Exit;
        };
        match event {
            Event::WindowEvent { event: WindowEvent::CloseRequested, .. } => {
                finish(json!({ "ok": false, "error": "cancelled" }), 1, flow);
                return;
            }
            Event::UserEvent(Nudge::Loaded(url)) => {
                if a.mode == Mode::Refresh && is_youtube(&host_of(&url)) && refresh_loaded.is_none() {
                    refresh_loaded = Some(Instant::now());
                }
            }
            _ if due => {}
            _ => return,
        }
        if started.elapsed() > timeout {
            finish(json!({ "ok": false, "error": "timeout" }), 1, flow);
            return;
        }
        let url = webview.url().unwrap_or_default();
        let host = host_of(&url);
        let cookies = match webview.cookies() {
            Ok(c) => c,
            Err(e) => {
                if debug() {
                    eprintln!("cookies() failed: {e}");
                }
                return;
            }
        };
        if debug() {
            // names and domains only, never values
            let names: Vec<String> = cookies
                .iter()
                .filter(|c| is_youtube(c.domain().unwrap_or_default()) || is_google(c.domain().unwrap_or_default()))
                .map(|c| format!("{}@{}", c.name(), c.domain().unwrap_or_default()))
                .collect();
            let line = format!("at {host}; cookies {} total; yt/google: {}", cookies.len(), names.join(" "));
            // only what changed (the poll runs every second)
            if line != last_debug {
                eprintln!("{line}");
                last_debug = line;
            }
        }
        match a.mode {
            Mode::Refresh => {
                // the page and the rotation are not the same instant: give YouTube's startup a moment
                if let Some(t) = refresh_loaded {
                    if t.elapsed() >= Duration::from_secs(2) {
                        if has_sapisid(&cookies) {
                            finish(json!({ "ok": true, "cookies": youtube_cookies(&cookies), "url": url }), 0, flow);
                        } else {
                            finish(json!({ "ok": false, "error": "signed-out" }), 1, flow);
                        }
                    }
                }
            }
            _ => {
                if has_sapisid(&cookies) && is_youtube(&host) {
                    match seen_at {
                        None => seen_at = Some(Instant::now()),
                        Some(t) if t.elapsed() >= SETTLE => {
                            finish(json!({ "ok": true, "cookies": youtube_cookies(&cookies), "url": url }), 0, flow);
                        }
                        _ => {}
                    }
                } else if is_youtube(&host) && google_session(&cookies) && !has_sapisid(&cookies) && !handed_off {
                    // signed in to Google, but the flow skipped YouTube's hand-off: send it through once
                    handed_off = true;
                    let _ = webview.load_url(HANDOFF_URL);
                }
            }
        }
    });
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn store_ids_are_stable_and_distinct() {
        assert_eq!(store_id("acct-1"), store_id("acct-1"));
        assert_ne!(store_id("acct-1"), store_id("acct-2"));
    }

    #[test]
    fn the_login_continues_through_youtubes_hand_off() {
        for u in [LOGIN_URL, ADD_URL] {
            assert!(u.contains("continue=https%3A%2F%2Fwww.youtube.com%2Fsignin%3Faction_handle_signin%3Dtrue"));
        }
        assert_eq!(host_of(HANDOFF_URL), "www.youtube.com");
    }

    #[test]
    fn domains() {
        assert!(is_youtube(".youtube.com") && is_youtube("www.youtube.com") && !is_youtube("notyoutube.com"));
        assert!(is_google("accounts.google.com") && !is_google("google.com.evil.example"));
        assert_eq!(host_of("https://accounts.google.com/x?y"), "accounts.google.com");
    }
}
