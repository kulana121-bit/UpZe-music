# Contributing to UpZe

Thanks for helping make UpZe better! Bug reports, ideas, translations and pull requests are all welcome.

## Reporting bugs

1. Search [existing issues](https://github.com/kulana121-bit/UpZe-music/issues) first.
2. Open a **Bug report** and include your UpZe version (Settings › About), device and Android version.
3. For playback problems, attach the **Playback log** (Settings › About › Playback log › Share). It has cookies,
   tokens and URL query strings removed, but read it before posting.
4. For crashes, attach the **Last crash report** from the same screen.

Security issues: please don't open a public issue — see [SECURITY.md](SECURITY.md).

## Suggesting features

Open a **Feature request** describing the problem it solves. Features that need piracy, DRM circumvention, rip proxies
or streaming audio from Spotify will not be accepted.

## Development setup

- JDK 17+, Android SDK platform 37 and build-tools 36.0.0 (Android Studio recent enough for AGP 9.3 works).
- `./gradlew :app:assembleDebug` builds `com.ravi.musicspot.debug`, which installs next to a release build.
- Run `./gradlew :app:testDebugUnitTest :app:lintDebug` before opening a PR.

## Pull requests

- Branch from `main`, keep each PR focused on one change, and describe what you tested and on which device.
- Follow the existing style (`.editorconfig`; Kotlin official style, 4-space indents, max ~140 columns).
- **Android 8.0 (API 26) must keep working.** Guard newer APIs with `Build.VERSION.SDK_INT` checks; lint treats
  `NewApi` as an error.
- Never touch the main thread with network or disk I/O; use the existing `IoScope`/`Dispatchers.IO` patterns.
- New option surfaces use `MotionSheet` / `MotionDialog` / `MotionDropdownMenu` (ui/motion) so motion stays consistent.
- Add or update unit tests for logic changes (`app/src/test`).
- Add a line under **Unreleased** in `CHANGELOG.md`.
- Never commit keystores, `keystore.properties`, `local.properties`, API keys, cookies or personal data.

## License

UpZe is GPL-3.0-or-later. By contributing you agree that your contribution is licensed under the same terms. If you
port code from another project, it must be GPL-3.0-compatible and credited in `THIRD_PARTY_NOTICES.md`.
