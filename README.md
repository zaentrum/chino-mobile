# chino-mobile

Kotlin Multiplatform + Compose Multiplatform mobile client for **chino**, the
films-and-series experience of the [zaentrum](https://github.com/zaentrum/zaentrum)
self-hosted media platform. One source tree, two targets: Android phone/tablet
and iOS.

This is a **neutral, bring-your-own-server client**. It ships with no built-in
server address. On first launch you add your own zaentrum server through the
in-app **Add-Server** flow; the app then reads that server's `/api/config`,
discovers its OpenID Connect issuer, and signs you in against *your* server's
identity provider. Nothing about a particular operator is baked into the
published build.

## Stack

- Kotlin 2.1 / Compose Multiplatform 1.7
- Ktor 3 client + kotlinx.serialization for the API
- Voyager for navigation
- Coil 3 for image loading
- Android: Gradle 8.11, AGP 8.9, minSdk 24, target 36, Material 3, Media3 player
- iOS: deployment target 15.0, SwiftUI host, XcodeGen-generated `.xcodeproj`

## App ids

One app id, no product flavors:

| Build   | Android `applicationId`          | iOS bundle id                    |
|---------|----------------------------------|----------------------------------|
| Release | `io.github.zaentrum.chino`       | `io.github.zaentrum.chino`       |
| Debug   | `io.github.zaentrum.chino.debug` | `io.github.zaentrum.chino.debug` |

The debug id lets a dev build install next to a release one. Forks override
the base with `-PchinoAppId=...` (Android) and `CHINO_BUNDLE_ID` in
[`iosApp/Configuration/`](iosApp/Configuration/) (iOS). The OAuth redirect URI
is not derived from the app id and is the same on every build — see
[Sign-in](#sign-in).

The server address, OIDC issuer and OIDC client id are **not hardcoded** — they
come from the Add-Server flow at runtime. The build-time defaults are empty.
Operators who distribute their own pre-pointed build can inject values without
code changes (see [Configuration](#configuration)).

## Layout

```
build.gradle.kts                root build
settings.gradle.kts             :shared, :androidApp
gradle/libs.versions.toml       version catalog

shared/                         KMP library
  src/commonMain/kotlin/cloud/nalet/chino/mobile/
    App.kt                      root composable
    AppConfig.kt                build defaults, overlaid with the connected server
    data/AppContainer.kt        Ktor client + token store + chinoApi
    data/api/ChinoApi.kt        catalogue + account API (chino-api's JSON shapes)
    data/auth/OAuthRedirect.kt  the app's one OAuth redirect URI
    data/auth/OidcDiscovery.kt  OIDC discovery from the connected server
    data/ServerConfigStore.kt   persisted Add-Server config
    data/slots/, data/notices/  what addons add: slot rows and notices (see Addons)
    ui/onboarding/AddServerScreen.kt  bring-your-own-server entry point
    ui/...                      auth / home / browse / detail / person / search / player / trailer / settings / notices
  src/commonTest/...            shared unit tests (JVM + iOS simulator)
  src/androidUnitTest/...       Android-only unit tests on the JVM (Media3's track selection)
  src/androidMain/...           DataStore-backed stores, Ktor OkHttp engine, Media3 player
  src/iosMain/...               NSUserDefaults stores, Darwin engine, AVPlayer
                                player, MainViewController() exposed to Swift

androidApp/                     Android host
  build.gradle.kts              app id, signing, OAuth redirect scheme
  src/androidMain/kotlin/cloud/nalet/chino/mobile/android/
    ChinoMobileApplication.kt   builds AppContainer from BuildConfig
    MainActivity.kt             Compose host
    auth/AppAuthSignInLauncher.kt   Authorization Code + PKCE via AppAuth

iosApp/                         iOS host (no Xcode project in git)
  project.yml                   XcodeGen descriptor
  Configuration/Debug.xcconfig
  Configuration/Release.xcconfig
  iosApp/iOSApp.swift           @main
  iosApp/ContentView.swift      hosts MainViewController() from shared

.github/workflows/ci.yml        neutrality check, unit tests, Android debug APK
scripts/check-neutrality.sh     the neutrality guard CI runs, with its self-test
```

## Run locally — Android

```bash
./gradlew :androidApp:assembleDebug
adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

On first launch, use **Add Server** to point the app at your zaentrum server.

## Run locally — iOS

Requires macOS, Xcode 16+, and [XcodeGen](https://github.com/yonaskolb/XcodeGen).

```bash
brew install xcodegen
cd iosApp
xcodegen          # materializes iosApp.xcodeproj from project.yml
open iosApp.xcodeproj
```

In Xcode pick the `iosApp` scheme; the Debug and Release configurations come
from the `xcconfig` files in [`iosApp/Configuration/`](iosApp/Configuration/).
The Run script step `embedAndSignAppleFrameworkForXcode` builds the shared
Kotlin framework on demand.

The iOS app talks to servers over **HTTPS only**: App Transport Security
refuses plain http, and Add Server keeps to the same rule. The one exception is
a server on the Mac itself — `http://localhost` or `http://127.0.0.1` — for
running a development server next to the simulator.

## Player

Android plays with Media3, iOS with AVPlayer. Both open the same HLS master
(`/api/v1/items/{id}/play/master.m3u8`) with the stream token, the device's
codec caps and the chosen quality, and play by the same rules in
`ui/player/`:

- **Resume** — the saved position, unless the title is barely started (30 s
  or less) or finished (its last minute): then from the start. The detail
  page offers "Resume" only where the player resumes. Progress is saved
  every 10 seconds while playing, on pause and on exit, and never a position
  the player has not played: nothing before the resume seek lands, nothing
  when the saved position could not be read. In the credits or past 95 % the
  title is marked watched.
- **Subtitles** — on by default only when the audio is in another language
  than the preferred subtitle language, as on the web; a full track before a
  forced one, and a file's own default flag counts for nothing.
- **Audio, quality, segments** — audio tracks, the quality ladder from
  `/play/info`, intro/recap/credits skipping and the next-episode countdown.
  Without play info (it failed, or took more than 8 s) playback starts at
  Auto, the master's own pick, never the top rung.
- **5.1** — the caps say `eac3` (and `ac3`) only where the device plays
  them: a Dolby decoder, or on Android an output that takes the bitstream as
  it is (a receiver on HDMI), to which Media3 passes it through. With them
  chino-stream serves a package's 5.1 companions next to their stereo
  twins, and the audio menu lists both as `/play/info` describes them:
  "English", E-AC-3 · 5.1, and "English", AAC · Stereo. Android finds each
  rendition by its group and name and picks it with Media3, keeps the pick
  when the player is built again, and greys one the output no longer takes.
  It reads each rendition's codec off `/play/info`, so Media3 starts from the
  master alone instead of loading every rendition first. Zap keeps to the
  stereo group.

The iOS player draws subtitles itself — sidecar files and the stream's text
tracks, named by language ("English · SDH", "German (forced)"); image-based
subtitles (PGS) are listed as not available — and keeps playing in the
background, with Picture in Picture, AirPlay, the lock screen's Now Playing
controls and landscape for full screen. A failure offers Try again, where it
was.

The Android player side-loads the sidecars into Media3 and lists a package's
HLS subtitle renditions only where no sidecar carries them. It recovers a
failing stream by itself: a packaged title is retried in place, where it
was; only a title played on the fly steps down its quality ladder.

Zap's cards start on the master's first variant and the audio it starts
with — what chino-stream lists first for the device's caps, and warms — and
on Android the cards ahead are prefetched to exactly those bytes.

On an Android phone Zap stays in portrait. On a large screen (smallest width
600 dp and up) it turns with the device — Android 16 ignores an orientation
lock there for an app targeting API 36. Each card plays its clip whole in the
middle, over the clip's own ambient light — a small copy of the frame on
screen, blown up, blurred and dimmed (Android; iOS shows the backdrop) — with
the title, the year and rating and three lines of the overview small at its
foot, and Save and Watch beside them at the right edge. Zap plays with
sound: the phone's volume is the mute.

## Trailers

A title's trailer plays in the app when the server has one — one of the
title's extras, packaged for streaming (`extras` in the item detail; a
trailer before a teaser, the title's own before a season's: `ui/trailer/`).
The detail page's Trailer button, on movies and series alike, opens it in
the player movies and episodes play in, in its extra mode
(`ui/player/PlayerMode.kt`): the same controls, menus and look — quality,
audio, subtitles, speed, Playback info, and on iOS Picture in Picture and
AirPlay — under "Sintel · Trailer". It plays the extra's `play_path` with
the stream token and the device's caps from the start, with sound, and
closes at its end or on Back. After it closes at its end, the screen it
returns to ignores Back, taps and accessibility actions for a second
(`ui/player/AutoCloseGuard.kt`), so a press meant for the player does
nothing there. chino-api has no play info for an extra, so
the quality ladder and the codecs are read off its master, as chino-stream
reads a packaged title's; its subtitles are the ones its master lists. A
trailer reads and writes nothing of the title's — no progress, watched mark,
segments, scrub previews, next episode or prewarm, so Continue Watching is
left alone — and of the telemetry sends one `trailer_play`. One the server
no longer has says "Trailer not available" and offers the title's YouTube
link when there is one. Without a trailer of its own, the button opens that
link outside the app, as before. The Home hero takes titles with either,
those with a trailer of their own first, and has the same Trailer button
after Play and More Info.

## Addons

An addon installed on the server adds to the app through two generic seams.
The app names no addon, and what a seam shows is in the addon's words.

- **Slots** — buttons an addon contributes to a named place. The app draws
  one, `search.empty`: a search that finds no titles and no people shows its
  buttons under the headline, as the app's own
  (`GET /api/v1/extensions?slot=search.empty`, the query carried as `{q}`). A
  link opens in the system browser, and only when it is a page of the server
  the app is signed in to; an action is a POST with the person's bearer to the
  portal's app proxy on that server (`/api/portal/apps/<addon>/…`) and nowhere
  else. A row that leads anywhere else is not drawn.
- **Notices** — what an addon tells one person ("your title is ready"). The
  bell in the top bar counts the unread ones and opens the list, newest first:
  whom each is from, how long ago it came, its title and text as plain text.
  Opening one reads it and opens the title it is about, else its link — a
  page of the same server only — in the system browser; Mark All Read and
  delete are there too. The app asks `GET /api/v1/notices` every minute while
  it is in the foreground and as it comes back to it, only while someone is
  signed in. A server whose notices are not available (`available: false`)
  shows no bell.

Both follow the platform's rules for
[slots](https://github.com/zaentrum/zaentrum/blob/main/docs/extending/slots.md)
and [notices](https://github.com/zaentrum/zaentrum/blob/main/docs/extending/notices.md),
checked again on the device by the shared code (`data/slots`, `data/notices`,
`data/ServerLinks.kt`) that Android and iOS run alike.

## Tests

```bash
./gradlew :shared:testDebugUnitTest       # shared tests, plus Android-only ones, on the JVM (what CI runs)
./gradlew :shared:iosSimulatorArm64Test   # the same shared tests, plus iOS-only ones, on a simulator (macOS)
scripts/check-neutrality.sh               # the neutrality guard CI runs
```

## Configuration

The published build leaves the server address, OIDC issuer, and OIDC client id
empty — the app obtains them at runtime via Add-Server + `/api/config` + OIDC
discovery. If you distribute your own build and want it pre-pointed at your
server, inject the values without editing source:

- **Android** — gradle project properties (set in `gradle.properties`,
  `~/.gradle/gradle.properties`, on the command line, or via
  `ORG_GRADLE_PROJECT_*` env vars):

  ```bash
  ./gradlew :androidApp:assembleDebug \
    -PapiBaseUrl="https://media.example.com/api/" \
    -PoidcIssuer="https://id.example.com/realms/example"
  ```

- **iOS** — fill in `CHINO_API_BASE_URL` / `CHINO_OIDC_ISSUER` in
  `iosApp/Configuration/Debug.xcconfig` and `Release.xcconfig`.

## Sign-in

The app signs in with the OAuth 2.0 **Authorization Code flow with PKCE**
(S256) in the system browser: AppAuth and a Custom Tab on Android,
`ASWebAuthenticationSession` on iOS. The authorize and token endpoints come
from OIDC discovery of the issuer the server's `/api/config` names, and the
client id is that document's `oidcClientId.mobile`. No client secret ships in
the app.

### Redirect URI

Every build — debug and release, Android and iOS — uses this one redirect URI:

```
cloud.nalet.chino:/oauth/callback
```

Register exactly this string on the app's client in your identity provider.
A wildcard entry does not cover it: a client that lists only `*` answers the
authorize request with `400 Invalid parameter: redirect_uri`. Other spellings
— `cloud.nalet.chino.debug:/oauth/callback`, a trailing slash,
`cloud.nalet.chino://oauth/callback` — are different URIs and are refused the
same way. The scheme is a private-use one (RFC 8252) and is independent of
the app id.

In the source the URI lives in `OAuthRedirect` (shared) and its scheme in the
`appAuthRedirectScheme` manifest placeholder (`androidApp/build.gradle.kts`),
which routes the callback back to the app on Android; keep the two equal. As
debug and release share the scheme, an Android device with both installed
asks which app should finish the sign-in.

### OIDC client setup

On your server's OpenID Connect issuer, create one **public** client (no
secret) for the app. Its id is what the server advertises as
`oidcClientId.mobile` in `/api/config` (chino-api's `OIDC_CLIENT_ID_MOBILE`).

- Authorization Code (standard) flow enabled, PKCE method `S256`.
- Valid redirect URI: `cloud.nalet.chino:/oauth/callback` — exactly.
- `offline_access` available, so the app can keep a refresh token.
- An audience mapper that puts the API's audience (`oidcAudience` in
  `/api/config`) into the access token's `aud`, so the backend accepts it.

### Delete Account

Settings → Account → **Delete Account** deletes the signed-in account on the
server — its watch progress, lists, likes and watch history there, and the
sign-in itself — with `DELETE /api/v1/me` and the account's bearer, after a
destructive dialog that says so. Only a `200` signs the account out on the
device (to the account picker, or to sign-in when it was the last one); a
`409` shows the server's reason, a `501` that deleting isn't available on that
server, any other answer to try again later. A server deletes accounts only
when its account deletion is set up (chino-api's `ACCOUNT_DELETION_TOKEN`).

## CI/CD

GitHub Actions ([`.github/workflows/ci.yml`](.github/workflows/ci.yml)) runs
a **neutrality** check that fails the build on any internal hostname,
competitor product name, or acquisition vocabulary, runs the shared unit tests
and builds the installable Android debug APK (`assembleDebug`) on every push.
The neutrality check is
[`scripts/check-neutrality.sh`](scripts/check-neutrality.sh): it matches a
name wherever no letter or digit stands next to it — so a name joined by `_`
in an environment variable counts, and for product names a camelCase one
too — tests its own patterns before it scans, and runs the same way locally.
A signed release job (`bundleRelease` / `assembleRelease`) runs only when a
signing keystore secret is configured. iOS is not built in CI (the App Store
path needs a macOS runner + signing).

## Roadmap

1. **Token hardening**: EncryptedSharedPreferences on Android, Keychain on iOS.
2. **iOS CI**: self-hosted macOS runner + signed `.ipa`.
3. **Image subtitles on iOS**: draw PGS tracks (today they show as not
   available).

## License

[MPL-2.0](LICENSE).
