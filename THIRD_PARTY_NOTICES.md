# Third-party notices

UpZe (formerly MusicSpot) is Copyright (C) 2026 Ravi and is licensed under the GNU General Public License, version 3 or (at your option)
any later version. The full license text is in `LICENSE`.

UpZe includes or links the following third-party software. Their licenses apply to their code.

## Source code ported into UpZe

| Project | License | Used for |
|---|---|---|
| [OpenTune](https://github.com/Arturo254/OpenTune) (OpenTune contributors) | GPL-3.0-or-later | Innertube parser and models, player clients, lyrics providers (LrcParser, KuGou, NetEase, LyricsPlus, romaniser, track-name cleaner), player panes, pill notifications, sign-in WebView flow; ideas for bit-perfect USB output and AutoEq fetching. Files ported directly (yt/InnertubeParser, yt/Models, yt/PlayerClient, lyrics/*, ui/account/SignIn, ui/Overlays, ui/glass/Glass) carry a header naming OpenTune and the modification; the player panes and pill notifications are reimplementations modelled on OpenTune. |
| [NewPipe Extractor](https://github.com/TeamNewPipe/NewPipeExtractor) — `org.schabi.newpipe.extractor.utils.Utils` | GPL-3.0-or-later | A patched copy (via OpenTune) in `app/src/main/java` that avoids an API 33+ method; the library's own copy is stripped from its jar at build time. |

## Libraries

| Library | License |
|---|---|
| [NewPipe Extractor](https://github.com/TeamNewPipe/NewPipeExtractor) v0.26.3 (YouTube stream fallback, SoundCloud) | GPL-3.0-or-later |
| [nanojson](https://github.com/TeamNewPipe/nanojson) (TeamNewPipe fork of mmastrac/nanojson) | MIT / Apache-2.0 (dual) |
| [Mozilla Rhino](https://github.com/mozilla/rhino) 1.8.1 and rhino-engine | MPL-2.0 |
| [jsoup](https://jsoup.org) 1.22.2 | MIT |
| [Protocol Buffers (javalite)](https://github.com/protocolbuffers/protobuf) | BSD-3-Clause |
| [JSR-305 annotations](https://github.com/findbugsproject/findbugs) | BSD-3-Clause |
| [Kyant Backdrop](https://github.com/Kyant0/AndroidLiquidGlass) (`io.github.kyant0:backdrop`) | Apache-2.0 |
| [Haze](https://github.com/chrisbanes/haze) (Chris Banes) | Apache-2.0 |
| [AndroidX Media3 / ExoPlayer](https://github.com/androidx/media) | Apache-2.0 |
| AndroidX (Core, Activity, Lifecycle, Navigation, DataStore, WorkManager, Palette) and Jetpack Compose / Material 3 / Material Icons | Apache-2.0 |
| [Guava](https://github.com/google/guava) | Apache-2.0 |
| [Kotlin standard library, kotlinx.coroutines, kotlinx.serialization](https://github.com/Kotlin) | Apache-2.0 |
| [OkHttp](https://square.github.io/okhttp/) and Okio | Apache-2.0 |
| [Coil 3](https://github.com/coil-kt/coil) | Apache-2.0 |
| JUnit 4 (tests only) | EPL-1.0 |

## Data and services

| Source | Terms |
|---|---|
| [AutoEq](https://github.com/jaakkopasanen/AutoEq) (Jaakko Pasanen) headphone measurements/ParametricEQ profiles, fetched on demand | MIT |
| [LRCLIB](https://lrclib.net) synced lyrics | Open, free API; lyrics remain the property of their rights holders |
| YouTube Music, JioSaavn, Audius, SoundCloud, Jamendo, Internet Archive, KuGou, NetEase, LyricsPlus, Spotify | Accessed over their public/web endpoints; content belongs to its owners. UpZe is not affiliated with or endorsed by any of these services. |

## License texts

- GPL-3.0: `LICENSE`
- Apache-2.0: https://www.apache.org/licenses/LICENSE-2.0
- MIT: https://opensource.org/license/mit
- MPL-2.0: https://www.mozilla.org/MPL/2.0/
- BSD-3-Clause: https://opensource.org/license/bsd-3-clause
- EPL-1.0: https://www.eclipse.org/legal/epl-v10.html
