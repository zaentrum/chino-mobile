package cloud.nalet.chino.mobile.data.auth

/**
 * The app's one OAuth 2.0 redirect URI — `cloud.nalet.chino:/oauth/callback`
 * — the same on every build (debug and release) and on Android and iOS.
 *
 * The operator's OIDC provider must list exactly this URI on the public
 * mobile client (the `oidcClientId.mobile` the server's /api/config names).
 * A wildcard entry is not enough: against a realm whose mobile client listed
 * only `*`, authorize answered 400 "Invalid parameter: redirect_uri" for this
 * URI. Any other spelling (a `.debug` scheme, a trailing slash) is a
 * different URI and is refused the same way. It is a private-use scheme
 * (RFC 8252 §7.1) in the reverse-DNS form of the project's original domain;
 * it stays this value although the published app id is
 * io.github.zaentrum.chino, because it is what the clients are registered
 * with. The README's "Sign-in" section documents it for operators.
 *
 * Android: androidApp/build.gradle.kts sets the `appAuthRedirectScheme`
 * manifest placeholder to [SCHEME] so AppAuth's RedirectUriReceiverActivity
 * receives the callback — keep the two equal. iOS: ASWebAuthenticationSession
 * listens for [SCHEME] itself; nothing is declared in Info.plist.
 */
object OAuthRedirect {
    /** The URI's custom scheme. */
    const val SCHEME: String = "cloud.nalet.chino"

    /** `redirect_uri` of the authorize request and the code exchange. */
    const val URI: String = "$SCHEME:/oauth/callback"
}
