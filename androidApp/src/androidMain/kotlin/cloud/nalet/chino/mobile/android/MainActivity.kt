package cloud.nalet.chino.mobile.android

import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.fragment.app.FragmentActivity
import cloud.nalet.chino.mobile.App
import cloud.nalet.chino.mobile.android.auth.AppAuthSignInLauncher

/** A FragmentActivity: the Cast button's device chooser and the bar while
 *  casting are fragments (Google's Cast widgets). */
class MainActivity : FragmentActivity() {
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
        // The Activity starts by asking the system for no orientation of its
        // own. Android keeps what an Activity last asked on the Activity's
        // record in the system, not in the app's process, and nothing resets
        // it when the process dies: after the system has killed the app with
        // the player open, the Activity it brings back still asks for the
        // player's sideways. The player's hold would take that for what the
        // app asked before the player (PlayerStandHold) and give it back on
        // leaving, so the whole app stayed sideways; Zap's portrait hold the
        // same way, upright. Whatever holds the screen asks again as it is
        // drawn.
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
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
