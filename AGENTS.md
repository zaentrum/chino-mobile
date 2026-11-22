# chino-mobile

chino's app for phones and tablets, on Android and iOS: one Kotlin
Multiplatform and Compose Multiplatform tree, `shared/`, with a host app for
each platform, `androidApp/` and `iosApp/`. A person browses a zaentrum
server's films and series, plays them (Media3 on Android, AVPlayer on iOS),
downloads them on Android and casts them to a TV (Google Cast, AirPlay). The
app carries no server address: the person adds one, and the app talks to that
server's chino-api under `/api/`, signs in with the OpenID Connect issuer the
server names, and sends an addon's actions to the server's portal.
How a change is proposed and what a pull request has to pass is in the
[guide to contributing](https://github.com/zaentrum/.github/blob/main/CONTRIBUTING.md);
what may be built is in [building from the specification](https://github.com/zaentrum/zaentrum/blob/main/AGENTS.md).

## Build

```sh
./gradlew :androidApp:assembleDebug   # androidApp/build/outputs/apk/debug/androidApp-debug.apk
cd iosApp && xcodegen && open iosApp.xcodeproj   # macOS with Xcode and XcodeGen
```

Android needs JDK 17 and an Android SDK with platform 36 and build-tools
35.0.0 (`ANDROID_HOME` or `local.properties`); the wrapper brings Gradle 8.11.1,
and `gradle/libs.versions.toml` pins Kotlin 2.1.0 and AGP 8.9.1. The debug app
id ends in `.debug`, so it installs beside a release. The Xcode project is
generated, not committed; its first build step runs
`./gradlew :shared:embedAndSignAppleFrameworkForXcode`, and the signing team is
each developer's own. `tools/gen_lucide.py` writes the vendored Lucide icons.

A release (`:androidApp:bundleRelease`) stops without `VERSION_CODE`, and it is
signed only with `SIGNING_KEYSTORE_FILE`, `SIGNING_KEYSTORE_PASSWORD`,
`SIGNING_KEY_ALIAS` and `SIGNING_KEY_PASSWORD` set. The `android-release` job
builds one when started by hand, with the keystore held as repository secrets.

## Test

```sh
./gradlew :shared:testDebugUnitTest
```

`.github/workflows/ci.yml` runs it on every push and pull request (job
`android-build`), then builds the debug APK. The Gradle task
runs `shared/src/commonTest` and `shared/src/androidUnitTest` on the JVM, with
no device and no server: chino-api's answers come from a Ktor `MockEngine`
(`FakeServer`), the download tests stand up an OkHttp `MockWebServer`, and most
Android-only tests run under Robolectric, which downloads its Android runtime
on the first run. Two suites run only by hand:

```sh
./gradlew :shared:iosSimulatorArm64Test          # macOS: the shared tests and shared/src/iosTest on a simulator
./gradlew :androidApp:connectedDebugAndroidTest   # a device or an emulator attached: androidApp/src/androidInstrumentedTest
```

## Run it

Install the debug APK on an emulator (an Android Virtual Device) or a phone
with USB debugging on, or run the `iosApp` scheme on a simulator from Xcode:

```sh
adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

On first launch, Add Server asks for the server's address: the reference demo
([how it is deployed](https://github.com/zaentrum/zaentrum/blob/main/docs/reference-demo.md))
or your own instance, such as `https://media.example.org`. The app reads
`/api/healthz` and `/api/config`, discovers the issuer that `/api/config`
names, and signs in through the system browser (Authorization Code with PKCE)
as the client that `/api/config` gives as `oidcClientId.mobile`. That client has to
list the app's one redirect URI exactly, the one in
`shared/src/commonMain/kotlin/…/data/auth/OAuthRedirect.kt`.

The apps talk https only, but for development a debug build on Android, and
the iOS app, also reach `http://localhost` and `http://127.0.0.1`: from an
emulator or a phone through `adb reverse tcp:<port> tcp:<port>`, from the iOS
simulator directly. There is no harness of recorded answers to run the app
against; `FakeServer` serves the tests only.

## What the tests leave unchecked

- iOS, in the workflow: `shared/src/iosMain`, `shared/src/iosTest` and the
  Swift host in `iosApp/` are neither built nor run there.
- The instrumented tests: not in the workflow, and `MainShellInstrumentedTest`
  expects a device that is signed in already.
- The sign-in: `AppAuthSignInLauncher` and `IosSignInLauncher` against a real
  identity provider; the tests start from tokens the app already holds.
- Playback on a device: a real stream, the device's codecs, 5.1 passed through
  to a receiver, Picture in Picture, AirPlay. The tests check what the player
  asks the server for and which tracks it picks.
- Casting: the tests drive fakes of the Cast SDK's client; no TV is asked.
- Downloads under Android's background limits: only as Robolectric models them.
- The signed release build: by hand only, with the keystore secrets.

## Before a pull request

The organisation's shared check, the workflow's `neutrality-shared` job, is the
one neutrality check.
