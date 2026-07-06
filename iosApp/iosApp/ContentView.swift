import SwiftUI
import UIKit
import shared

/// Hosts the Compose Multiplatform UI inside SwiftUI by wrapping the
/// `MainViewController` factory function exposed from the shared Kotlin
/// framework. Flavor-specific values are read out of Info.plist (which is
/// itself populated from Configuration/Beta.xcconfig or Prod.xcconfig).
struct ContentView: View {
    var body: some View {
        ComposeViewControllerRepresentable()
            .ignoresSafeArea(.keyboard)
    }
}

private struct ComposeViewControllerRepresentable: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        let info = Bundle.main.infoDictionary ?? [:]
        return MainViewControllerKt.MainViewController(
            // Build-flavor fallbacks default to empty: this is a neutral
            // self-host client that resolves its live server (API base + OIDC
            // issuer/client) from the in-app Add-Server flow. An operator can
            // inject real values via the Configuration/*.xcconfig files.
            flavor: info["ChinoFlavor"] as? String ?? "prod",
            apiBaseUrl: info["ChinoApiBaseUrl"] as? String ?? "",
            oidcIssuer: info["ChinoOidcIssuer"] as? String ?? "",
            oidcClientId: info["ChinoOidcClientId"] as? String ?? "chino",
            displayName: Bundle.main.object(forInfoDictionaryKey: "CFBundleDisplayName") as? String ?? "Chino",
            appVersion: Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0.0.0",
            appBuild: Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "0"
        )
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
