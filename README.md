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
- Android: Gradle 8.11, AGP 8.9, minSdk 24, target 36, Material 3, Media3 player,
  Google Cast sender (Cast framework, MediaRouter)
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
    cast/                       casting: what the TV plays, the hand-over, the positions written, the warning
  src/commonTest/...            shared unit tests (JVM + iOS simulator)
  src/androidUnitTest/...       Android-only unit tests on the JVM (Media3's track selection, the Cast SDK's load request)
  src/androidMain/...           DataStore-backed stores, Ktor OkHttp engine, Media3 player,
                                Google Cast sender (cast/) and its themes (res/)
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
  forced one, and a file's own default flag counts for nothing. Where none
  comes on that way — the audio in the viewer's own language, or subtitles
  off in Settings — the forced track in the audio's language does, a text
  one before a picture one: the lines a film shows in another language. The
  choice follows the audio, made again when its language changes, until the
  viewer picks in the subtitles menu; from then on a track picked stays on,
  and an Off stays off, for the session. A sidecar is forced where chino-api
  says so (`forced`), or its label does.
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
  master alone instead of loading every rendition first; its Zap keeps to
  the stereo group. The iOS app adds `native` to its caps on the master,
  `/play/info` and `/prewarm`: chino-stream serves AVPlayer Apple's shape, a
  5.1 group of the stereo group's members under the same names. The menu
  shows AVPlayer's options, one per track, matched to `/play/info`'s by
  name ("AAC · Stereo or E-AC-3 · 5.1" under one a companion plays), and
  which group plays is AVPlayer's own pick, by the output.

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
middle, over the clip's own ambient light — its colours, followed slowly: a
copy of the frame once a second, averaged into a 16 x 9 grid and with the
light before, eased to over 1.8 s, blown up, blurred and dimmed (Android; iOS
shows the backdrop); a card's first light takes over from its backdrop in
0.6 s — with the title, the year and rating and three lines of the overview
small at its foot, and Save and Watch beside them at the right edge. Zap
plays with sound: the phone's volume is the mute.

## Downloads

On Android a film or an episode can be downloaded and watched without a
connection (`data/downloads/` shared, `downloads/` in androidMain). iOS shows
no download control yet: its host passes no `DownloadsPlatform`.

- **Whose** — a download is an account's: its owner is the account's subject
  (the sign-in's `sub`) on its server, never its token. Each account sees,
  plays and is asked about its own downloads only, and nobody signed in sees
  none. One title of one server is one Media3 download whoever claims it: a
  second account adds its claim, and the media goes with the last one
  (`DownloadBook`). Only the signed-in account's downloads run; the others
  wait, stopped, and fetch nothing. An expired or refused sign-in hides
  nothing and deletes nothing: signing in again as the same account brings
  it all back. An explicit sign-out or removal of the account on the phone,
  or a change of server, takes its downloads - asked first.
- **What can be downloaded** — one rule, `downloadability`: a title that
  plays from its package (`/play/info` mode `packaged`); anything else says
  "can't be downloaded". The server has the last word: `features.downloads`
  of its `/api/config` (chino-api's `DOWNLOADS_ENABLED`, on by default);
  while it is false no download is offered and unfinished ones stop.
- **What the library decides** — `POST /api/v1/me/available` (chino-api) says
  in one request which of the account's downloads it may watch now: as the
  app starts and comes to the front while the server answers, and before a
  download plays. What it leaves out (the title left the library, or a
  parent lowered the account's cap) is deleted only when it says so twice,
  at least an hour apart (`GONE_CONFIRM_MS`): the first answer marks the
  title (`gone_at`) and changes nothing else - it still plays - and an
  answer that the title is the account's again clears the mark; the second
  deletes it, says so once on the downloads screen, and stops it playing.
  One wrong answer of a server having a bad moment never takes downloads off
  the phone. No answer, a 5xx, a 401 or a 429 decides nothing. A server
  from before the route is asked title by title. Without the server, a
  download checked within 30 days plays; past them it waits for the server
  ("downloaded · check needed").
- **With casting** — while a device is connected, Play on a downloaded title
  plays it on the TV as a streamed one: the server's master and subtitles,
  signed with the TV's own token (`playsOnAnotherScreen`, set by casting);
  casting started while a download plays here sends the TV the same,
  never a file of the phone's. The TV's positions are written as of now; a
  position the phone kept of the title from playing it without the server is
  older and goes unsent, and the download resumes here where the TV stopped
  (`writeCastProgress`).
- **What a download keeps** — one video rendition, picked by the download
  quality from the master as the server serves it when the download starts
  (Settings: what an hour takes at each quality, the same sample of titles
  for every quality, none where too few titles have it); the audio in the
  viewer's language and the original; the sidecar subtitles in the viewer's
  languages; the poster, the backdrop and the metadata the detail page, the
  downloads screen and the player need.
- **How** — Media3's `DownloadManager` and `DownloadService` (a `dataSync`
  foreground service with a notification, only while a download is on its
  way), one title at a time, into app-specific storage (no storage
  permission), Wi-Fi only by default. Waiting for a connection, the service
  stops: the downloads go on when it comes back - in front with the service
  again; in the background from Android 14 by a user-initiated data transfer
  job planned as they start to wait (`DownloadResumeJob`, with a
  notification of its own; from Android 15 an app in the background has no
  network without one); on Android 12 and 13 while the app's process lives;
  below 12 by WorkManager. Android 15's six
  hours a day for data sync services pause them until the app is opened.
  Before the disk is full they stop, "Not enough space on this phone".
- **The stream token** — never on the disk: each playlist is kept without
  the tokens chino-stream writes into it, each piece under its URL without
  one, and every request is signed as it goes out - only one to the
  connected server, for a title of the signed-in account. A refused token is
  forgotten and a new one minted on the next attempt. Playing a finished
  download reads the phone alone.
- **Storage** — `files/downloads/media/` (Media3's cache, never evicted),
  `files/downloads/titles/<key>/` (`title.json` with the owners' claims,
  `poster.jpg`, `backdrop.jpg`, `subs/`), `databases/chino_downloads.db` (the
  download index and the cache's own). The title files are written off the
  main thread; a write the disk refuses stays in memory and is tried again.
  Nothing of the app goes into a backup or a transfer to another phone
  (`data_extraction_rules.xml`).
- **Without the server** — the app opens on the account's downloads, after
  "Who's watching?" where the phone has several accounts, with the banner
  "Can't reach the server. Your downloads still play." A downloaded title's
  page is built from what is on the phone. Positions played without the
  server are kept (`progress-book.json`, the last position only for titles
  on the phone) and sent once it answers - each with its age
  (`played_ms_ago`), so the server writes it only over an older one, and
  with the bearer of the account that played it. Watched marks wait the
  same way.

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

## Casting

On Android the app casts to a TV on the same network with Google Cast
(`cast/`), as the design system's casting pattern draws it: Google's Cast
button, bar, full controls and notification, in the app's colours and Inter.
The TV runs Google's Default Media Receiver, so no receiver is registered
anywhere; its id is one constant (`CAST_RECEIVER_APP_ID`). iOS plays to
AirPlay instead.

- **The Cast button** — Google's icon and states, in the top bar of the
  player and of a title's page, on the right — shows only while a device
  that can play is on the network. Its device chooser is Google's.
- **Loading** — the TV is sent the title's master as the phone's player asks
  for it, with the receiver's caps in place of the phone's and no rung
  (`RECEIVER_CAPS`, `avc:1080,aac`: H.264 up to 1080 lines and AAC, which
  every Cast device with a screen plays; chino-stream serves an H.264
  package where there is one, else an H.264 rung of the live encode). Every
  link the TV gets — the master, the subtitles, the artwork — is signed with
  a stream token minted for it, which the media and artwork routes accept
  without the bearer. The phone reads the master first: a subtitle the
  master carries itself is not side-loaded again, and a server that refuses
  the title says why before the TV is asked. HLS in fMP4, buffered, the
  title's length; the title, an episode's series and numbers, the backdrop
  and the poster; the other text sidecars as side-loaded WebVTT tracks, named
  as the app's menus name them. Once the TV has the title, the phone turns on
  the audio the viewer listened to (else the Settings language) and the
  subtitle it showed, or the default subtitle rule's pick for that audio.
- **Handing over** — casting started while the phone plays continues on the
  TV where the phone was, playing or paused as it was, and the phone's player
  closes once the TV plays. Play, Resume or an episode while a device is
  connected starts the title on the TV where the player would have started
  it: the resume point, from the head for a title chino-api calls finished.
  A title the TV plays for this phone already plays on; only Play from start
  loads it again. Where the TV does not take over — it can't, or casting
  stops first — the title stands on the phone where it was, paused.
- **While casting** — the bar (Google's mini controller: the title, "playing
  on" and the device's own name, play or pause) sits above the bottom
  navigation, part of the layout; it opens the full controls (Google's
  expanded controller: the artwork, the title, the seek bar, play or pause,
  back and forward 10 seconds, subtitles and audio when the TV offers them,
  stop casting). The notification and the lock screen show the title, the
  device, play or pause and stop. Stop casting ends the session and stops the
  receiver; a session the app was in when it was closed is resumed. Casting
  starts with the app (`attachCasting` in the Application), not with a
  screen.
- **Positions** — the phone writes the position the TV plays to chino-api
  as its own player does: every 10 seconds while playing, at a pause, and as
  the title ends or casting stops — the last position the TV confirmed, the
  one the phone keeps, so Resume continues from there. chino-api decides
  from it whether the title is finished. While casting needs it - a load, a
  recovery or a signing of the links anew under way, the TV's media the
  phone's, a position on its way - a foreground service of the media
  playback kind keeps the app running (`CastPlaybackService`), so the writes
  go on with the phone locked or in another app. Android lets the app start
  it only from the front: as a title is loaded, as a session is found with
  the app open, as the app comes back with a title on the TV. A session the
  app resumes in the background has it once the app is opened again; till
  then the writes go on only while Android lets the app run. Only the phone
  that cast a title writes it, and only for the account it was cast for: the
  media carries a marker with this phone's owner id of the account
  (`CastTag`), made at random and kept on the phone - never in a backup or a
  transfer to a new phone - which names neither the account nor the server;
  a second phone signed in to the same account writes nothing, nor does an
  extra. A position the phone kept of a title from playing its download
  without the server never goes over the TV's: it is older, and goes unsent
  (Downloads, with casting); one a phone sends late goes with its age, and
  chino-api writes it only over an older one.
- **Links that run out** — where the TV plays on after a long pause, or in a
  resumed session, with less life left in its links than it still has to
  play - and a new token makes them last meaningfully longer - the phone
  loads the title again where it is, its links signed anew, its tracks kept,
  playing or paused as it is meant to. Should that fail, it is a title that
  failed (below).
- **When the TV can't play a title** — the phone first loads it once more,
  its links signed anew, where it stood - the last position the TV
  confirmed, never an earlier one written over it - unless the TV cannot
  decode it or has no screen. Then "Living Room TV can't play this title."
  in the design system's warning strip, with the reason under details: the
  status the server gave the phone for the TV's master, read as the design
  system reads a status (the server's problem, busy, no stream of the title,
  no access, the device's link turned down, no answer), else the receiver's
  code (media it does not play, a stream it could not load from the server
  or not read, a device that plays sound only). Casting stops, and the title
  stays where it was on the phone, paused, ready to play there. Where the
  phone finds out before it asks the TV anything - the server refuses the
  title, or gives the phone no token for the TV's links (its own sign-in
  refused, say) - the strip says so and the TV plays on as it did.

Google's Cast screens are in English, as the app is: the app ships the
English resources only (`localeFilters`), until it has translations of its
own. Dates and numbers follow the phone's locale.

The receiver is a web player on the TV that loads the master, the renditions,
the segments and the subtitles from the server itself: the server's stream
routes must answer it with CORS headers (`Access-Control-Allow-Origin`).

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
