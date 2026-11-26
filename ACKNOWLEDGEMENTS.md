# Acknowledgements

Tubeless is free software under the [GNU General Public License v3.0](LICENSE). It stands on the work
of the projects below; thank you to everyone who built them. Their license texts ship next to the code
that uses them (`common/sabr/LICENSES.txt`, `extension/vendor/LICENSES.txt`,
`desktop/src/main/resources/jshost/LICENSES.txt`, `common/fonts/`).

## Upstream

- **[TubeHaus](https://github.com/biolend83/tubehaus)** by biolend83 and contributors, GPL-3.0.
  Tubeless is a fork of TubeHaus: its engine, player integrations and much of the UI come from there.
  The original copyright and license notices are kept; the `upstream` remote of the development
  repository points at it. A few internal names from TubeHaus remain only so older installs and
  backups keep working (`common/src/app/tubeless/Legacy.kt`).

## Libraries and tools in the app

| Project | Used for | License |
|---|---|---|
| [Kotlin](https://kotlinlang.org), [Compose Multiplatform](https://www.jetbrains.com/compose-multiplatform/), kotlinx.serialization / coroutines / datetime | the whole app | Apache-2.0 |
| [mpv](https://mpv.io) / libmpv | desktop player | LGPL-2.1+ / GPL-2.0+ |
| [Media3 / ExoPlayer](https://github.com/androidx/media) | Android player | Apache-2.0 |
| [Shaka Player](https://github.com/shaka-project/shaka-player) | browser player | Apache-2.0 |
| [Mediabunny](https://github.com/Vanilagy/mediabunny) | browser downloads | MPL-2.0 |
| [@js-joda/core](https://github.com/js-joda/js-joda) | browser date handling | BSD-3-Clause |
| [OkHttp](https://square.github.io/okhttp/), [Okio](https://square.github.io/okio/), [Coil](https://coil-kt.github.io/coil/) | networking, files, images | Apache-2.0 |
| [JNA](https://github.com/java-native-access/jna) | desktop native calls | Apache-2.0 / LGPL-2.1 |
| [Javet](https://github.com/caoccao/Javet) | desktop JavaScript engine (Node.js) | MIT |
| [wry](https://github.com/tauri-apps/wry), [tao](https://github.com/tauri-apps/tao) | desktop Google sign-in window | Apache-2.0 / MIT |
| [YouTube.js](https://github.com/LuanRT/YouTube.js), [googlevideo](https://github.com/LuanRT/googlevideo), [BgUtils](https://github.com/LuanRT/BgUtils) by LuanRT | SABR streaming, attestation, protocol reference | MIT |
| [rustypipe-botguard](https://codeberg.org/ThetaDev/rustypipe-botguard) by ThetaDev | attestation under JSDOM | MIT |
| [yt-dlp](https://github.com/yt-dlp/yt-dlp) and its [EJS solver](https://github.com/yt-dlp/ejs) | stream signature solving | Unlicense |
| [Roboto](https://github.com/googlefonts/roboto-flex), [Pretendard](https://github.com/orioncactus/pretendard) | fonts | SIL OFL 1.1 |

## Services

- [SponsorBlock](https://sponsor.ajay.app) and [DeArrow](https://dearrow.ajay.app) by Ajay Ramachandran
  and contributors: segment and title data (CC BY-NC-SA 4.0), through their public APIs.
- [Return YouTube Dislike](https://returnyoutubedislike.com): dislike counts, through its public API.

Tubeless is not affiliated with, endorsed by or sponsored by YouTube or Google. "YouTube" is a trademark
of Google LLC.
