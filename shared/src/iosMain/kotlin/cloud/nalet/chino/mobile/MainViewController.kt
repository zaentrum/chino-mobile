package cloud.nalet.chino.mobile

import androidx.compose.ui.window.ComposeUIViewController
import cloud.nalet.chino.mobile.data.AppContainer
import cloud.nalet.chino.mobile.data.PlatformServerConfigStoreFactory
import cloud.nalet.chino.mobile.data.PlatformSettingsStoreFactory
import cloud.nalet.chino.mobile.data.auth.IosSignInLauncher
import cloud.nalet.chino.mobile.data.auth.PlatformAccountStoreFactory
import cloud.nalet.chino.mobile.data.auth.PlatformTokenStoreFactory
import platform.UIKit.UIDevice
import platform.UIKit.UIViewController

/**
 * Entry point called by Swift's ContentView.swift via UIViewControllerRepresentable.
 *
 * The Swift side passes per-flavor config (read from Info.plist, populated
 * from Configuration/Beta.xcconfig or Configuration/Prod.xcconfig) — this
 * keeps the build flavors honest across the language boundary.
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
    val container = AppContainer(
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
    )
    App(container = container, signInLauncher = IosSignInLauncher())
}
