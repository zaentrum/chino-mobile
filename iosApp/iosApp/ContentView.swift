import SwiftUI
import UIKit
import shared

/// Hosts the Compose Multiplatform UI inside SwiftUI by wrapping the
/// `MainViewController` factory function exposed from the shared Kotlin
/// framework. Flavor-specific values are read out of Info.plist (which is
/// itself populated from Configuration/Debug.xcconfig or Release.xcconfig).
///
/// The Compose UI draws edge to edge, as on Android: every screen pads itself
/// for the status bar and the home indicator, and the player fills the
/// screen. While a film plays full screen the player asks for the status bar
/// and the home indicator to go (IosSystemChrome) — SwiftUI owns the root
/// view controller, so that is done here.
struct ContentView: View {
    @StateObject private var chrome = SystemChrome()

    var body: some View {
        ComposeViewControllerRepresentable()
            .ignoresSafeArea()
            .statusBarHidden(chrome.immersive)
            .modifier(HidesHomeIndicator(hidden: chrome.immersive))
    }
}

/// The player's full-screen request, as SwiftUI state.
private final class SystemChrome: ObservableObject {
    @Published var immersive = false

    init() {
        IosSystemChrome.shared.onImmersiveChanged = { [weak self] on in
            self?.immersive = on.boolValue
        }
    }
}

/// Hides the home indicator (iOS 16 and later) while [hidden].
private struct HidesHomeIndicator: ViewModifier {
    let hidden: Bool

    func body(content: Content) -> some View {
        if #available(iOS 16.0, *) {
            content.persistentSystemOverlays(hidden ? .hidden : .automatic)
        } else {
            content
        }
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
