package cloud.nalet.chino.mobile.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import cloud.nalet.chino.mobile.App
import cloud.nalet.chino.mobile.android.auth.AppAuthSignInLauncher

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as ChinoMobileApplication
        val container = app.container
        // installFor MUST run before setContent — registerForActivityResult
        // requires the lifecycle to still be in INITIALIZED/CREATED state,
        // which it is during onCreate, before the first composition.
        //
        // Neutral self-host client: the authorize + token endpoints come from
        // the OIDC discovery the Add-Server probe ran against the user's
        // connected server (container.serverConfig). When null (fresh install
        // before Add-Server) installFor falls back to the Keycloak path layout
        // from the build-default issuer — but the boot gate routes a fresh
        // install to Add-Server first, so by the time the user can sign in the
        // graph has been rebuilt (see the restart hook below) with the real
        // discovered endpoints + client id. The redirect URI stays the app's
        // own scheme regardless.
        val discovered = container.serverConfig
        val signInLauncher = AppAuthSignInLauncher.installFor(
            activity = this,
            config = container.config,
            authEndpoint = discovered?.authEndpoint,
            tokenEndpoint = discovered?.tokenEndpoint,
        )
        setContent {
            App(
                container = container,
                signInLauncher = signInLauncher,
                // After a server connect/change the AppContainer's lazy clients
                // were built from the previous config; rebuild a fresh container
                // and recreate the Activity so the graph (and the AppAuth
                // launcher above) re-read the just-saved server. Mirrors chino-
                // androidtv's process-restart-on-connect.
                restart = {
                    app.rebuildContainer()
                    recreate()
                },
            )
        }
    }
}
