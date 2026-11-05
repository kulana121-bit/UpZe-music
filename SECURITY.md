# Security Policy

## Supported versions

Only the latest release receives security fixes.

| Version | Supported |
|---|---|
| 1.0.x (latest) | ✅ |
| MusicSpot 2.0.x and older | ❌ — update to UpZe |

## Reporting a vulnerability

Please **do not** open a public issue for security problems.

Report it privately through GitHub: open the repository's **Security** tab and choose
**[Report a vulnerability](https://github.com/kulana121-bit/UpZe-music/security/advisories/new)**. Include what is
affected, how to reproduce it, and the impact you expect. You'll get a reply as soon as possible, and credit in the
release notes if you'd like.

## Scope

Of particular interest: leaks of the stored sign-in cookies/tokens (Google, Spotify, Jellyfin, Subsonic), the in-app
updater (download and install of release APKs), exported components (the media session service, the widget receiver),
and WebView sign-in pages.

## Release integrity

Official APKs are only published on [GitHub Releases](https://github.com/kulana121-bit/UpZe-music/releases) and are
all signed with the same key. Android will refuse to install an update signed with a different key over an existing
install — if that happens, the APK did not come from this project.
