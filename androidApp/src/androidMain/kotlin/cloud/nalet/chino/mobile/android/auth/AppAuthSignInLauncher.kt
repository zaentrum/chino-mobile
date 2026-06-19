package cloud.nalet.chino.mobile.android.auth

import android.app.Activity
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import cloud.nalet.chino.mobile.AppConfig
import cloud.nalet.chino.mobile.currentTimeMillis
import cloud.nalet.chino.mobile.data.auth.SignInLauncher
import cloud.nalet.chino.mobile.data.auth.SignInResult
import cloud.nalet.chino.mobile.data.auth.Tokens
import kotlinx.coroutines.CompletableDeferred
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues

/**
 * AppAuth-Android-driven sign-in. Mirrors what chino-web does with
 * react-oidc-context: opens Keycloak's authorize endpoint in a Chrome Custom
 * Tab, captures the redirect via the AppAuth RedirectUriReceiverActivity
 * (registered by the appAuthRedirectScheme manifestPlaceholder in
 * androidApp/build.gradle.kts), exchanges the code for tokens, returns
 * them to the suspending caller.
 *
 * Wired to the activity's ActivityResultLauncher in [installFor] so the
 * suspending [signIn] call can await the Chrome Custom Tab result without
 * a separate broadcast receiver dance.
 *
 * One launcher instance per Activity — re-create if the activity is
 * recreated. AppAuth's own state lives in the redirect-uri receiver
 * activity, not here, so nothing needs to be persisted across config
 * changes.
 */
class AppAuthSignInLauncher private constructor(
    private val activity: ComponentActivity,
    private val service: AuthorizationService,
    private val serviceConfig: AuthorizationServiceConfiguration,
    private val clientId: String,
    private val redirectUri: String,
) : SignInLauncher {

    private var launcher: ActivityResultLauncher<Intent>? = null
    private var pending: CompletableDeferred<SignInResult>? = null

    private fun registerLauncher() {
        launcher = activity.registerForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            // AppAuth puts the AuthorizationResponse (or Exception) on the
            // returned Intent extras regardless of result code — extract
            // both and surface whichever is non-null.
            val data = result.data
            val deferred = pending ?: return@registerForActivityResult
            pending = null
            if (data == null) {
                deferred.complete(SignInResult.Cancelled)
                return@registerForActivityResult
            }
            val resp = AuthorizationResponse.fromIntent(data)
            val ex = AuthorizationException.fromIntent(data)
            when {
                resp != null -> exchangeForTokens(resp, deferred)
                ex != null -> deferred.complete(
                    SignInResult.Error(ex.errorDescription ?: ex.error ?: "authorization failed"),
                )
                else -> deferred.complete(SignInResult.Cancelled)
            }
        }
    }

    private fun exchangeForTokens(
        response: AuthorizationResponse,
        deferred: CompletableDeferred<SignInResult>,
    ) {
        service.performTokenRequest(response.createTokenExchangeRequest()) { tokenResponse, ex ->
            when {
                tokenResponse?.accessToken != null -> {
                    deferred.complete(
                        SignInResult.Success(
                            Tokens(
                                accessToken = tokenResponse.accessToken!!,
                                refreshToken = tokenResponse.refreshToken,
                                expiresAtEpochMillis = tokenResponse.accessTokenExpirationTime
                                    ?: (currentTimeMillis() + DEFAULT_EXPIRES_MS),
                            ),
                        ),
                    )
                }
                ex != null -> deferred.complete(
                    SignInResult.Error(ex.errorDescription ?: ex.error ?: "token exchange failed"),
                )
                else -> deferred.complete(SignInResult.Error("token exchange returned no token"))
            }
        }
    }

    override suspend fun signIn(): SignInResult {
        val currentLauncher = launcher
            ?: return SignInResult.Error("SignInLauncher not registered with an Activity")
        val deferred = CompletableDeferred<SignInResult>()
        pending = deferred
        val request = AuthorizationRequest.Builder(
            serviceConfig,
            clientId,
            ResponseTypeValues.CODE,
            android.net.Uri.parse(redirectUri),
        )
            .setScope("openid profile email offline_access")
            .build()
        currentLauncher.launch(service.getAuthorizationRequestIntent(request))
        return deferred.await()
    }

    companion object {
        // Keycloak access tokens default to 5 min — same fallback the chino-
        // androidtv OidcDeviceClient uses when expires_in isn't echoed back.
        private const val DEFAULT_EXPIRES_MS = 5L * 60 * 1000

        /** Build a launcher bound to an [Activity]. Must be called BEFORE
         *  Activity.onStart() because ActivityResultLauncher registration
         *  requires the LifecycleOwner to still be in the INITIALIZED /
         *  CREATED state. Easiest place: MainActivity.onCreate, before
         *  setContent.
         *
         *  For the neutral self-host client the authorize + token endpoints
         *  come from OIDC discovery against the user's connected server
         *  (passed as [authEndpoint] / [tokenEndpoint]). When those are null
         *  (no discovered metadata yet — defensive only, since the boot gate
         *  routes a fresh install to Add-Server first) we fall back to the
         *  Keycloak openid-connect path layout from the issuer, matching the
         *  old hardcoded behaviour. The client id is [config.oidcClientId],
         *  which AppContainer has already resolved from the connected server.
         *
         *  The redirect URI ALWAYS stays the app's OWN scheme
         *  ([config.redirectScheme] / the manifest placeholder); we never take
         *  a redirect from the server — the operator registers the app's scheme
         *  on their OIDC client. */
        fun installFor(
            activity: ComponentActivity,
            config: AppConfig,
            authEndpoint: String? = null,
            tokenEndpoint: String? = null,
        ): AppAuthSignInLauncher {
            val authUri = authEndpoint
                ?: "${config.oidcIssuer}/protocol/openid-connect/auth"
            val tokenUri = tokenEndpoint
                ?: "${config.oidcIssuer}/protocol/openid-connect/token"
            val serviceConfig = AuthorizationServiceConfiguration(
                android.net.Uri.parse(authUri),
                android.net.Uri.parse(tokenUri),
            )
            val launcher = AppAuthSignInLauncher(
                activity = activity,
                service = AuthorizationService(activity),
                serviceConfig = serviceConfig,
                clientId = config.oidcClientId,
                redirectUri = "${config.redirectScheme.ifBlank { activity.applicationContext.packageName }}:/oauth/callback",
            )
            launcher.registerLauncher()
            return launcher
        }
    }
}
