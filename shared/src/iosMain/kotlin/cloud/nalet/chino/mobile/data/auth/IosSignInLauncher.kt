package cloud.nalet.chino.mobile.data.auth

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.AuthenticationServices.ASPresentationAnchor
import platform.AuthenticationServices.ASWebAuthenticationPresentationContextProvidingProtocol
import platform.AuthenticationServices.ASWebAuthenticationSession
import platform.AuthenticationServices.ASWebAuthenticationSessionErrorDomain
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.Foundation.NSURL
import platform.Foundation.NSURLComponents
import platform.Foundation.NSURLQueryItem
import platform.UIKit.UIApplication
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.darwin.NSObject
import platform.posix.arc4random_uniform
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * iOS Authorization Code + PKCE sign-in via `ASWebAuthenticationSession`.
 *
 * Counterpart to Android's [AppAuthSignInLauncher]: opens the connected
 * server's OIDC authorize endpoint in the system in-app browser (a sheet that
 * shares Safari's SSO cookies), captures the redirect on the app's own scheme,
 * then trades the returned `code` for tokens through the shared
 * [OidcDeviceClient.exchangeAuthorizationCode] (the same discovered token
 * endpoint + client id both platforms use).
 *
 * PKCE (RFC 7636, S256) is done in-process: a random verifier, its SHA-256
 * challenge sent on the authorize request, the verifier replayed on the token
 * exchange — so no client secret ships in the app. `state` is generated and
 * verified against the redirect to defend against CSRF / mixed-up responses.
 *
 * The endpoints, client id and token exchange are supplied as lambdas resolved
 * at sign-in time (not construction) so the neutral self-host client reads the
 * server the user connected to via Add-Server, not a build-time default. The
 * [redirectScheme] is the app's OWN scheme (`cloud.nalet.chino`, `+.debug` on a
 * debug build) which the operator registers on their OIDC client — we never
 * take a redirect target from the server.
 */
@OptIn(ExperimentalForeignApi::class)
class IosSignInLauncher(
    private val authEndpoint: () -> String,
    private val clientId: () -> String,
    private val redirectScheme: String,
    private val exchange: suspend (code: String, verifier: String, redirectUri: String) -> Tokens,
) : SignInLauncher {

    // ASWebAuthenticationSession.presentationContextProvider is a WEAK property.
    // Held here for the launcher's lifetime (which spans the whole App
    // composition via LocalSignInLauncher) so it isn't collected mid-present,
    // which would silently drop the sheet.
    private val anchorProvider = PresentationAnchorProvider()

    // Strong ref to the in-flight session for the same reason — cleared when the
    // flow completes.
    private var session: ASWebAuthenticationSession? = null

    override suspend fun signIn(): SignInResult {
        val verifier = randomUrlSafe(64)
        val challenge = s256Challenge(verifier)
        val state = randomUrlSafe(24)
        val redirectUri = "$redirectScheme:/oauth/callback"
        val authorizeUrl = buildAuthorizeUrl(
            base = authEndpoint(),
            clientId = clientId(),
            redirectUri = redirectUri,
            challenge = challenge,
            state = state,
        )

        val callback = try {
            presentWebAuth(authorizeUrl)
        } catch (e: CancelledException) {
            return SignInResult.Cancelled
        } catch (e: Exception) {
            return SignInResult.Error(e.message ?: "Authentication failed")
        }

        val components = NSURLComponents(uRL = NSURL(string = callback), resolvingAgainstBaseURL = false)
        val items = components?.queryItems.orEmpty().filterIsInstance<NSURLQueryItem>()
        fun q(name: String): String? = items.firstOrNull { it.name == name }?.value

        q("error")?.let { err ->
            return SignInResult.Error(q("error_description")?.takeIf { it.isNotBlank() } ?: err)
        }
        // CSRF guard: the state we sent must round-trip unchanged.
        if (q("state") != state) {
            return SignInResult.Error("Sign-in state mismatch — please try again.")
        }
        val code = q("code")
            ?: return SignInResult.Error("No authorization code returned.")

        return try {
            SignInResult.Success(exchange(code, verifier, redirectUri))
        } catch (e: Exception) {
            SignInResult.Error(e.message ?: "Token exchange failed")
        }
    }

    /**
     * Presents the auth sheet and suspends until the system hands back the
     * redirect URL (or the user cancels / it errors). Runs the UIKit calls on
     * the main dispatcher — ASWebAuthenticationSession must be created and
     * started on the main thread.
     */
    private suspend fun presentWebAuth(url: String): String = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val s = ASWebAuthenticationSession(
                uRL = NSURL(string = url),
                callbackURLScheme = redirectScheme,
            ) { callbackURL, error ->
                session = null
                when {
                    callbackURL != null -> cont.resume(callbackURL.absoluteString ?: "")
                    // ASWebAuthenticationSessionErrorCodeCanceledLogin == 1: the
                    // user dismissed the sheet. Referenced by numeric value so we
                    // don't depend on how K/N exposes the NS_ERROR_ENUM symbol.
                    error != null && error.domain == ASWebAuthenticationSessionErrorDomain &&
                        error.code == CANCELED_LOGIN_CODE ->
                        cont.resumeWithException(CancelledException())
                    else ->
                        cont.resumeWithException(
                            RuntimeException(error?.localizedDescription ?: "Authentication failed"),
                        )
                }
            }
            s.presentationContextProvider = anchorProvider
            // Reuse Safari's session so an already-signed-in Keycloak SSO cookie
            // logs the user straight through (matches the web client).
            s.prefersEphemeralWebBrowserSession = false
            session = s
            cont.invokeOnCancellation {
                session = null
                s.cancel()
            }
            if (!s.start()) {
                session = null
                cont.resumeWithException(RuntimeException("Couldn't open the sign-in browser."))
            }
        }
    }

    /** Marker to translate a user-cancelled sheet into [SignInResult.Cancelled]. */
    private class CancelledException : RuntimeException()

    /**
     * Anchors the auth sheet to the app's active window. Iterates the connected
     * scenes (keyWindow / UIApplication.windows are deprecated on iOS 15+) and
     * returns the key window; falls back to a bare UIWindow so the cast never
     * crashes even in the degenerate no-scene case.
     */
    @OptIn(BetaInteropApi::class)
    private class PresentationAnchorProvider :
        NSObject(),
        ASWebAuthenticationPresentationContextProvidingProtocol {
        override fun presentationAnchorForWebAuthenticationSession(
            session: ASWebAuthenticationSession,
        ): ASPresentationAnchor {
            val windows = UIApplication.sharedApplication.connectedScenes
                .filterIsInstance<UIWindowScene>()
                .flatMap { it.windows.filterIsInstance<UIWindow>() }
            return windows.firstOrNull { it.isKeyWindow() } ?: windows.firstOrNull() ?: UIWindow()
        }
    }

    private fun buildAuthorizeUrl(
        base: String,
        clientId: String,
        redirectUri: String,
        challenge: String,
        state: String,
    ): String {
        val params = listOf(
            "client_id" to clientId,
            "response_type" to "code",
            "redirect_uri" to redirectUri,
            "scope" to "openid profile email offline_access",
            "code_challenge" to challenge,
            "code_challenge_method" to "S256",
            "state" to state,
        )
        val query = params.joinToString("&") { (k, v) -> "$k=${urlEncode(v)}" }
        val sep = if ('?' in base) '&' else '?'
        return "$base$sep$query"
    }

    private companion object {
        const val CANCELED_LOGIN_CODE = 1L
    }
}

/**
 * RFC 3986 percent-encoding over the unreserved set `[A-Za-z0-9-._~]`. Pure
 * Kotlin (no Foundation) so it can't trip on K/N's NSCharacterSet class-property
 * binding — the values we encode (`redirect_uri`, spaced `scope`) only need
 * `:` `/` and space escaped anyway.
 */
private fun urlEncode(value: String): String {
    val unreserved = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
    val sb = StringBuilder(value.length)
    for (b in value.encodeToByteArray()) {
        val c = b.toInt() and 0xFF
        if (c < 0x80 && c.toChar() in unreserved) {
            sb.append(c.toChar())
        } else {
            sb.append('%').append(c.toString(16).uppercase().padStart(2, '0'))
        }
    }
    return sb.toString()
}

/**
 * Cryptographically-random URL-safe token from `arc4random_uniform` (Darwin's
 * CSPRNG). Drawn from the PKCE unreserved alphabet so the result is a valid
 * `code_verifier` / `state` with no post-processing.
 */
private fun randomUrlSafe(chars: Int): String {
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
    val sb = StringBuilder(chars)
    repeat(chars) {
        sb.append(alphabet[arc4random_uniform(alphabet.length.toUInt()).toInt()])
    }
    return sb.toString()
}

/** base64url(SHA-256(ascii(verifier))) — the PKCE S256 code challenge. */
@OptIn(ExperimentalForeignApi::class)
private fun s256Challenge(verifier: String): String {
    val bytes = verifier.encodeToByteArray()
    val digest = UByteArray(CC_SHA256_DIGEST_LENGTH)
    bytes.usePinned { input ->
        digest.usePinned { out ->
            CC_SHA256(input.addressOf(0), bytes.size.toUInt(), out.addressOf(0))
        }
    }
    return base64Url(digest.asByteArray())
}

/** RFC 4648 §5 base64url, no padding — hand-rolled to avoid the NSData /
 *  NSDataBase64EncodingOptions interop surface. */
private fun base64Url(bytes: ByteArray): String {
    val table = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    val sb = StringBuilder((bytes.size + 2) / 3 * 4)
    var i = 0
    while (i < bytes.size) {
        val b0 = bytes[i].toInt() and 0xFF
        val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else -1
        val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else -1
        sb.append(table[b0 ushr 2])
        when {
            b1 == -1 -> sb.append(table[(b0 and 0x03) shl 4])
            b2 == -1 -> {
                sb.append(table[((b0 and 0x03) shl 4) or (b1 ushr 4)])
                sb.append(table[(b1 and 0x0F) shl 2])
            }
            else -> {
                sb.append(table[((b0 and 0x03) shl 4) or (b1 ushr 4)])
                sb.append(table[((b1 and 0x0F) shl 2) or (b2 ushr 6)])
                sb.append(table[b2 and 0x3F])
            }
        }
        i += 3
    }
    return sb.toString()
}
