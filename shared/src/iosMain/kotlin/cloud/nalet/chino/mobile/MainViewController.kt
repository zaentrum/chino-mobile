package cloud.nalet.chino.mobile

import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.ComposeUIViewController
import cloud.nalet.chino.mobile.data.AppContainer
import cloud.nalet.chino.mobile.data.PlatformServerConfigStoreFactory
import cloud.nalet.chino.mobile.data.PlatformSettingsStoreFactory
import cloud.nalet.chino.mobile.data.isLocalDevelopmentHost
import cloud.nalet.chino.mobile.data.auth.IosSignInLauncher
import cloud.nalet.chino.mobile.data.auth.PlatformAccountStoreFactory
import cloud.nalet.chino.mobile.data.auth.PlatformTokenStoreFactory
import platform.UIKit.UIDevice
import platform.UIKit.UIViewController

/**
 * Entry point called by Swift's ContentView.swift via UIViewControllerRepresentable.
 *
 * The Swift side passes per-flavor config (read from Info.plist, populated
 * from Configuration/Debug.xcconfig or Configuration/Release.xcconfig) — this
 * keeps the build flavors honest across the language boundary.
 *
 * Connecting to (or changing) a server rebuilds the app graph and remounts
 * the App, as Android recreates its activity: the container's clients are
 * lazy, and some are built at launch — before Add Server has saved anything —
 * so without a rebuild the first sign-in went to the empty build default (an
 * authorize URL without a scheme, which made ASWebAuthenticationSession throw).
 */
fun MainViewController(
    flavor: String,
    apiBaseUrl: String,
    oidcIssuer: String,
    oidcClientId: String,
    displayName: String,
    appVersion: String,
    appBuild: String,
): UIViewController = ComposeUIViewController {
    var generation by remember { mutableStateOf(0) }
    val container = remember(generation) {
        buildContainer(flavor, apiBaseUrl, oidcIssuer, oidcClientId, displayName, appVersion, appBuild)
    }
    // The redirect is the app's own OAuthRedirect.URI on Debug and Release
    // alike (IosSignInLauncher), never one taken from the server: the
    // operator's OIDC client registers exactly that one URI.
    val signIn = remember(container) {
        IosSignInLauncher(
            // Prefer the endpoint OIDC discovery found on the connected server;
            // fall back to the Keycloak path layout from the issuer. Resolved
            // lazily at sign-in time so the neutral client follows the server
            // the user connected to via Add-Server.
            authEndpoint = {
                container.serverConfig?.authEndpoint
                    ?: "${container.config.oidcIssuer}/protocol/openid-connect/auth"
            },
            clientId = { container.config.oidcClientId },
            exchange = { code, verifier, redirectUri ->
                container.oidcDeviceClient.exchangeAuthorizationCode(code, verifier, redirectUri)
            },
        )
    }
    key(generation) {
        App(container = container, signInLauncher = signIn, restart = { generation += 1 })
    }
}

private fun buildContainer(
    flavor: String,
    apiBaseUrl: String,
    oidcIssuer: String,
    oidcClientId: String,
    displayName: String,
    appVersion: String,
    appBuild: String,
): AppContainer {
    val cfg = AppConfig(
        flavor = if (flavor.equals("prod", ignoreCase = true)) AppConfig.Flavor.PROD else AppConfig.Flavor.BETA,
        apiBaseUrl = apiBaseUrl,
        oidcIssuer = oidcIssuer,
        oidcClientId = oidcClientId,
        displayName = displayName,
        // No iOS xcconfig key for this yet — ship neutral (empty) so the
        // Add-Server field starts blank with a generic placeholder and suggests
        // no operator URL. Wire a Bundle key here if beta-iOS wants the prefill.
        serverPreset = "",
    )
    val suite = "cloud.nalet.chino.mobile.${flavor.lowercase()}"
    val device = UIDevice.currentDevice
    return AppContainer(
        buildConfig = cfg,
        accountStoreFactory = PlatformAccountStoreFactory(suiteName = suite),
        tokenStoreFactory = PlatformTokenStoreFactory(suiteName = suite),
        settingsStoreFactory = PlatformSettingsStoreFactory(suiteName = suite),
        serverConfigStoreFactory = PlatformServerConfigStoreFactory(suiteName = suite),
        deviceStaticContext = mapOf(
            "device_model" to device.model,
            "device_manufacturer" to "Apple",
            "device_name" to device.name,
            "os_version" to device.systemVersion,
            "os_name" to device.systemName,
            "app_version" to appVersion,
            "app_version_code" to appBuild,
            "app_flavor" to flavor.lowercase(),
            "client" to "chino-mobile-ios",
        ),
        // https only; plain http to this device alone (App Transport
        // Security leaves IP-address hosts to the app).
        allowPlainHttp = ::isLocalDevelopmentHost,
    )
}
