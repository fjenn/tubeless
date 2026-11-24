# Platform differences (MOB, 2026-11-24)

Rule (owner): one code base, the same experience and capabilities everywhere; only true operating-system
differences may branch. All screens live in `common/src` (Compose). The layout follows the **form factor**,
not the OS: a phone (touch, < 600 dp wide) gets YouTube's phone app, a tablet its tablet app, a computer
or browser window YouTube's website, a TV the TV app. iOS and Android phones run the same composables
with the same gestures, sheets and settings; macOS, Windows, Linux and the extension share one desktop UI.

## Removed in MOB (were per-OS UI/capability branches)
| Was | Now |
|---|---|
| Ambient mode: Android only (player menu + Settings) | everywhere but TV: `frame()` added for desktop mpv (Skia downscale of the shown frame) and iOS (`AVPlayerItemVideoOutput` → Core Image) |
| "Quality on mobile data": Android only | wherever the OS reports a metered link (`Platform.knowsMetered`): Android, iOS (new `NWPathMonitor` in `IosPlatform`), browser |
| iPad watch page laid out one way, Android tablets another (`name == "ios"`) | one tablet layout (details + comments under the video, up next beside it) on both |
| Watch page `webDesk && name != "ios"` | `webDesk` only (it already excludes touch) |
| Safe-area / status-strip handling written twice (iOS `Main.kt`, Android `MainActivity`), different backgrounds | shared `AppFrame` (`ui/MobileChrome.kt`); each OS only sets the clock/battery colour (`Platform.statusBarDark`) |
| QA driver (`ParityDriver`) desktop only | all but TV |
| iOS `-newtabs` launch hack | removed (the YouTube tab bar is the only bar) |

## Remaining differences, each an OS-level reason
| Where | Difference | Why it can't be the same |
|---|---|---|
| Player backend | ExoPlayer (Android), AVPlayer + HLS (iOS), libmpv (desktop), `<video>`+Shaka (web) | each OS's media stack; behaviour above it is shared (`VideoPlayer`) |
| `PlayerControls` Quality page | iOS hides the "higher quality in another codec" rows | AVPlayer plays YouTube's HLS (H.264, ≤1080p) only; VP9/AV1 DASH needs a player iOS doesn't allow |
| `LinkedText.SelectableInSheet` | iOS: description/comment text in sheets not selectable | Compose Multiplatform on iOS crashes on text selection inside a sheet |
| Cast button | Google Cast (Android, desktop) vs AirPlay (iOS) vs none (web) | the OS's casting protocol; same button, same place |
| PiP | Android PiP, iOS AVPictureInPicture, browser `requestPictureInPicture`, desktop own floating window | OS windowing |
| Background play | Android media service, iOS AVAudioSession + layer detach, desktop always | OS lifecycle; same Always / Headphones / Off setting |
| Swipe for brightness / volume (full screen) | brightness on Android + iOS, system volume on Android only | iOS has no public API to set the system volume |
| `VideoMenu` "Download with another app" | Android only | Android intents to YTDLnis/Seal; iOS/desktop have no such hand-off |
| Settings → "TV mode", "Hide system bars" | Android only | Android TV boxes; Android's navigation bar (iOS has none to hide) |
| Settings → wallpaper colours | Android 12+ | Material You is an Android system feature |
| Settings → "Open by default" link settings | Android 12+ | Android's app-links page |
| Comment "Translate" | iOS 18+, macOS 26+ | the OS's on-device Translation framework |
| Downloads folder picker, title bar colour, IME pass-through | desktop | desktop file system / window chrome / input methods |
| In-app updater | Android APK, Windows MSI | iOS (App Store/AltStore) and the browser update themselves |
| Edge swipe back | iOS (`EdgeBack`) vs Android system back / predictive back | each OS's back gesture; both call `AppState.back()` |
| Rotate to full screen | iOS in `App.kt`, Android in `MainActivity` | orientation APIs differ; same behaviour |
| Image memory cache cap | desktop 64 MB fixed, others % of device memory | JVM heap vs device RAM |
| Status-bar icon colour | Android `isAppearanceLightStatusBars`, iOS window `overrideUserInterfaceStyle` | OS API; driven by the shared `AppFrame` |

## Form-factor differences (deliberate, same on every OS)
Phone: bottom tab bar, watch page as a sheet that docks into the mini player, engagement panels under the
video, ⋮ and Save as bottom sheets. Tablet: side rail, video + details left, up next right. Computer/browser:
YouTube website layout (masthead search, guide, theater, floating mini player, keyboard shortcuts, hover
previews). TV: focus-driven TV app.
