## What does this change?

<!-- A short description. Link the issue it fixes: "Fixes #123". -->

## How was it tested?

- Device / Android version:
- [ ] `./gradlew :app:testDebugUnitTest` passes
- [ ] `./gradlew :app:lintDebug` shows no new errors (NewApi included)
- [ ] Tried on a real device or emulator

## Checklist

- [ ] Works on Android 8.0 (API 26); newer APIs are guarded with `Build.VERSION.SDK_INT`
- [ ] No network or disk I/O on the main thread
- [ ] Added a line under **Unreleased** in `CHANGELOG.md`
- [ ] No keystores, passwords, API keys, cookies or personal data committed
- [ ] Screenshots / screen recording attached for UI changes
