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
- Android: Gradle 8.11, AGP 8.7, minSdk 24, target 35, Material 3
- iOS: deployment target 15.0, SwiftUI host, XcodeGen-generated `.xcodeproj`

## App ids / flavors

Two flavors are built from one source tree and install side-by-side on the same
device (beta + prod):

| Flavor | Android `applicationId`         | iOS bundle id                  | OIDC client (default) |
|--------|---------------------------------|--------------------------------|-----------------------|
| beta   | `io.github.zaentrum.chino.mobile.beta` | `io.github.zaentrum.chino.beta` | `chino-mobile-beta`   |
| prod   | `io.github.zaentrum.chino.mobile`      | `io.github.zaentrum.chino`      | `chino`               |

The published application id base is `io.github.zaentrum.chino`; forks override
it with `-PchinoAppId=...`. Debug builds get a further `.debug`
`applicationIdSuffix` on Android, so a beta debug build can coexist with its
release for local dev. The iOS side uses Xcode build configurations
`Beta` / `Prod` driven by the `xcconfig` files in
[`iosApp/Configuration/`](iosApp/Configuration/).

The server address, OIDC issuer, and OIDC client id are **not hardcoded** — they
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
    AppConfig.kt                flavor / urls / OIDC client (server-overlaid)
    data/AppContainer.kt        Ktor client + token store + chinoApi
    data/api/ChinoApi.kt        catalog + account API
    data/auth/OidcDiscovery.kt  OIDC discovery from the connected server
    data/auth/OidcDeviceClient.kt   RFC 8628 device flow
    data/ServerConfigStore.kt   persisted Add-Server config
    ui/onboarding/AddServerScreen.kt  bring-your-own-server entry point
    ui/...                      auth / home / detail / search / player / settings
  src/androidMain/...           DataStore-backed stores + Ktor OkHttp engine
  src/iosMain/...               NSUserDefaults stores, Darwin engine,
                                MainViewController() exposed to Swift

androidApp/                     Android host
  build.gradle.kts              product flavors: beta + prod
  src/androidMain/kotlin/cloud/nalet/chino/mobile/android/
    ChinoMobileApplication.kt   builds AppContainer from BuildConfig
    MainActivity.kt             Compose host

iosApp/                         iOS host (no Xcode project in git)
  project.yml                   XcodeGen descriptor
  Configuration/Beta.xcconfig
  Configuration/Prod.xcconfig
  iosApp/iOSApp.swift           @main
  iosApp/ContentView.swift      hosts MainViewController() from shared

.github/workflows/ci.yml        Android beta+prod debug APKs + neutrality check
```

## Run locally — Android

```bash
./gradlew :androidApp:assembleBetaDebug
adb install -r androidApp/build/outputs/apk/beta/debug/androidApp-beta-debug.apk
```

For `assembleProdDebug` swap the flavor. On first launch, use **Add Server** to
point the app at your zaentrum server.

## Run locally — iOS

Requires macOS, Xcode 16+, and [XcodeGen](https://github.com/yonaskolb/XcodeGen).

```bash
brew install xcodegen
cd iosApp
xcodegen          # materializes iosApp.xcodeproj from project.yml
open iosApp.xcodeproj
```

In Xcode pick the `iosApp` scheme, then choose the **Beta** or **Prod**
configuration in *Product → Scheme → Edit Scheme → Run → Info → Build
Configuration*. The Run script step `embedAndSignAppleFrameworkForXcode`
builds the shared Kotlin framework on demand.

## Configuration

The published build leaves the server address, OIDC issuer, and OIDC client id
empty — the app obtains them at runtime via Add-Server + `/api/config` + OIDC
discovery. If you distribute your own build and want it pre-pointed at your
server, inject the values without editing source:

- **Android** — gradle project properties (set in `gradle.properties`,
  `~/.gradle/gradle.properties`, on the command line, or via
  `ORG_GRADLE_PROJECT_*` env vars):

  ```bash
  ./gradlew :androidApp:assembleProdDebug \
    -PprodApiBaseUrl="https://media.example.com/api/" \
    -PoidcIssuer="https://id.example.com/realms/example"
  # beta backend: -PbetaApiBaseUrl="https://media-beta.example.com/api/"
  ```

- **iOS** — fill in `CHINO_API_BASE_URL` / `CHINO_OIDC_ISSUER` in
  `iosApp/Configuration/Beta.xcconfig` and `Prod.xcconfig`.

## Auth

For v0 the app uses the **OAuth 2.0 Device Authorization Grant** (RFC 8628). The
sign-in screen shows a URL and a short code that you enter in a second browser
window. A later release will switch to **Authorization Code + PKCE** via
`androidx.browser` Custom Tabs (Android) and `ASWebAuthenticationSession` (iOS).

### OIDC client setup

On your server's OpenID Connect issuer, create public clients for the app:

- `chino-mobile-beta` — for beta builds
- `chino` (or `chino-mobile`) — for prod builds

Each must be a public client with the device flow enabled, and an audience
mapper that adds the API's audience to the `aud` claim so the backend accepts
the token. The exact client ids are overridable per flavor.

## CI/CD

GitHub Actions ([`.github/workflows/ci.yml`](.github/workflows/ci.yml)) builds
the installable Android debug APKs (`assembleBetaDebug` + `assembleProdDebug`)
on every push and runs a **neutrality** check that fails the build on any
internal hostname, competitor product name, or acquisition vocabulary. A signed
release job (`bundleProdRelease` / `assembleProdRelease`) runs only when a
signing keystore secret is configured. iOS is not built in CI (the App Store
path needs a macOS runner + signing).

## Roadmap

1. **Auth v1**: Authorization Code + PKCE in a Custom Tab / `ASWebAuthenticationSession`.
2. **Token hardening**: EncryptedSharedPreferences on Android, Keychain on iOS.
3. **iOS CI**: self-hosted macOS runner + signed `.ipa`.

## License

[MPL-2.0](LICENSE).
