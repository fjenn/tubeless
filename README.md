<div align="center">

# Tubeless

**YouTube without the ads: one app for macOS, Windows, Linux, Android, iPhone / iPad and the browser.**

</div>

Tubeless is a free, open-source YouTube client. Sign in with your Google account and you get your own
Home feed, subscriptions, history, Watch later, playlists and notifications, or use it signed out with
a local library. Ads are left out (in videos, lists and Shorts), sponsor segments are skipped, and
background play, picture-in-picture and offline downloads work on every platform.

**Download:** [macOS (Apple silicon and Intel)](https://github.com/fjenn/tubeless/releases/latest/download/Tubeless-mac-universal.dmg) ·
[Linux AppImage](https://github.com/fjenn/tubeless/releases/latest/download/Tubeless-1.0.1-linux-x86_64.AppImage) ·
[Linux .deb](https://github.com/fjenn/tubeless/releases/latest/download/Tubeless-1.0.1-linux-amd64.deb) ·
[all files](https://github.com/fjenn/tubeless/releases/latest)

**Try it in your browser, nothing to install:** <https://fjenn.github.io/tubeless/> (Instant mode:
signed out, no downloads; add the extension or get the app for everything else).

> [!NOTE]
> **Unofficial.** Tubeless is independent software and is not affiliated with, endorsed by or
> sponsored by YouTube or Google. "YouTube" is a trademark of Google LLC. Tubeless does not host or
> distribute any videos; they stream from YouTube straight to your device. It is provided as is,
> without warranty; you are responsible for using it in line with the laws where you live and the
> terms of the services you connect to.

## Features

- **Signed in or not**: in-app Google sign-in, YouTube's own feeds (Home, Subscriptions, History,
  Watch later, Liked, playlists, notifications) and writes (like, Watch later, playlists) that sync
  back to your account; or signed out with subscriptions and history kept on the device.
- **No ads**: ad slots and promoted items are dropped from feeds, search, Shorts and the player.
- **SponsorBlock** segment skipping, **DeArrow** titles and thumbnails, **Return YouTube Dislike** counts.
- **A real player**: adaptive quality up to 4K/8K (AV1, VP9, H.264 by what the hardware decodes),
  captions with auto-translate, chapters, speed, resume, background play, picture-in-picture, casting.
- **Shorts** with one swipe per Short, or switched off entirely.
- **Offline downloads** in the quality you pick.
- **One code base**: every screen is shared Compose Multiplatform code; the layout follows the form
  factor (phone, tablet, desktop window, TV), not the operating system.

## Platforms

| Platform | Download | Player |
|---|---|---|
| macOS 15+ (Apple silicon, Intel) | `.dmg`, signed and notarized | libmpv |
| Linux (x86_64, aarch64) | AppImage, `.deb` | libmpv |
| Chrome / Firefox | extension (`.zip` / `.xpi`) | `<video>` + Shaka Player |
| Any browser | [website](https://fjenn.github.io/tubeless/) (Instant mode) | `<video>` + `<audio>` in step, native HLS on Safari |
| Windows (x64) | coming soon (code signing pending); build it yourself meanwhile | libmpv |
| Android (phone, tablet, TV) | coming soon; build it yourself meanwhile | Media3 / ExoPlayer |
| iPhone / iPad | coming soon; build it yourself meanwhile | AVPlayer |

Release files come from [`.github/workflows/release.yml`](.github/workflows/release.yml) and are
published on the [Releases](https://github.com/fjenn/tubeless/releases) page. The app tells you when a
new version is out; it never installs anything by itself.

**Linux:** `sudo apt install ./Tubeless-1.0.1-linux-amd64.deb` (pulls in libmpv), or make the AppImage
executable (`chmod +x`) and run it.

## Build

You need JDK 21+. Android builds also need the Android SDK; the iOS app needs a Mac with Xcode and
[XcodeGen](https://github.com/yonaskolb/XcodeGen).

```sh
./gradlew :desktop:run                      # run the desktop app (libmpv from your system / Homebrew)
./gradlew :desktop:packageDmg               # macOS .dmg   (packageMsi on Windows, packageDeb / packageAppImage on Linux)
./gradlew :android:assembleDebug            # android/build/outputs/apk/debug
./gradlew :web:compileProductionExecutableKotlinWasmJs   # the web (wasm) build
extension/build.sh                          # Chrome / Firefox extension zips in extension/dist
node site/build.mjs                         # the website in site/dist (after the web build), any static host
```

iPhone / iPad:

```sh
./gradlew :ios:linkReleaseFrameworkIosArm64          # Kotlin → TubelessKit.framework
mkdir -p ios/build/kit && cp -R ios/build/bin/iosArm64/releaseFramework/TubelessKit.framework ios/build/kit/
cd ios/app && xcodegen generate                      # makes Tubeless.xcodeproj
APPLE_TEAM_ID=<your team id> xcodebuild -project Tubeless.xcodeproj -scheme Tubeless ...
```

The Xcode project reads its signing team from the `APPLE_TEAM_ID` build setting / environment
variable; unsigned builds (`CODE_SIGNING_ALLOWED=NO`, as CI does) need none. Windows' `libmpv-2.dll`
comes from [shinchiro's mpv builds](https://github.com/shinchiro/mpv-winbuild-cmake/releases)
(`packaging/windows/fetch-libmpv.ps1`). The CI workflows ([`ci.yml`](.github/workflows/ci.yml),
[`smoke.yml`](.github/workflows/smoke.yml)) show the exact steps for every platform.

Release signing is optional: without the signing secrets, `release.yml` builds ad-hoc signed macOS
apps, unsigned Windows / iOS files and a debug-key APK.

## Credits

See [ACKNOWLEDGEMENTS.md](ACKNOWLEDGEMENTS.md) for every project Tubeless builds on and its license.

Tubeless is a fork of [TubeHaus](https://github.com/biolend83/tubehaus) by biolend83 and its
contributors (GPL-3.0); its engine, player integrations and much of the UI come from there. It also
builds on:

- [SponsorBlock](https://sponsor.ajay.app), [DeArrow](https://dearrow.ajay.app) and
  [Return YouTube Dislike](https://returnyoutubedislike.com): their public APIs
- [YouTube.js](https://github.com/LuanRT/YouTube.js), [googlevideo](https://github.com/LuanRT/googlevideo)
  and [BgUtils](https://github.com/LuanRT/BgUtils) by LuanRT (MIT): SABR streaming, attestation and protocol reference
- [yt-dlp](https://github.com/yt-dlp/yt-dlp) and its [EJS solver](https://github.com/yt-dlp/ejs)
  (Unlicense)
- [mpv](https://mpv.io) / libmpv, [Media3 / ExoPlayer](https://github.com/androidx/media),
  [Shaka Player](https://github.com/shaka-project/shaka-player),
  [Mediabunny](https://github.com/Vanilagy/mediabunny)
- [Kotlin](https://kotlinlang.org) and [Compose Multiplatform](https://www.jetbrains.com/compose-multiplatform/)
- Fonts: [Roboto](https://github.com/googlefonts/roboto-flex) and
  [Pretendard](https://github.com/orioncactus/pretendard) (SIL Open Font License 1.1)

Third-party license texts ship next to the bundled code (`common/sabr/LICENSES.txt`,
`extension/vendor/LICENSES.txt`, `desktop/src/main/resources/jshost/LICENSES.txt`, `common/fonts/`).

## License

[GPL-3.0](LICENSE), as upstream TubeHaus.
