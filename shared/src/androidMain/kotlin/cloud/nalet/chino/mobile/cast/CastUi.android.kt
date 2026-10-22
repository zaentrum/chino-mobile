package cloud.nalet.chino.mobile.cast

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.compose.AndroidFragment
import androidx.mediarouter.app.MediaRouteButton
import cloud.nalet.chino.mobile.ui.theme.ChinoBg
import cloud.nalet.chino.mobile.ui.theme.ChinoSurface
import com.google.android.gms.cast.framework.CastButtonFactory

/**
 * Google's MediaRouteButton - its icon and states, its device chooser - in
 * the fg colour (Widget.Chino.MediaRouteButton), while a device is on the
 * network, connecting or connected. Its name to a screen reader is Google's
 * ("Cast", "Cast. Connected").
 */
@Composable
actual fun CastButton(modifier: Modifier) {
    // Casting starts with the app; where the framework failed to start so
    // far, it is tried again as a screen with a Cast button opens.
    LaunchedEffect(Unit) { ChinoCast.ensureStarted() }
    val cast by ChinoCast.state.collectAsState()
    if (!cast.available && !cast.connecting && !cast.connected) return
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { ctx ->
                MediaRouteButton(ctx).also { button -> runCatching { CastButtonFactory.setUpMediaRouteButton(ctx, button) } }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * The bar's place: Google's mini controller on the surface while the TV has
 * media ([ChinoMiniControllerFragment]), else the warning of a title the TV
 * couldn't play, else nothing.
 */
@Composable
actual fun CastBar(padNavigationBar: Boolean, modifier: Modifier) {
    val cast by ChinoCast.state.collectAsState()
    val failure by ChinoCast.failure.collectAsState()
    val inset = if (padNavigationBar) Modifier.windowInsetsPadding(WindowInsets.navigationBars) else Modifier
    val warning = failure
    when {
        warning != null -> Box(modifier = modifier.fillMaxWidth().background(ChinoBg).then(inset)) {
            CastFailureStrip(failure = warning, onDismiss = ChinoCast::dismissFailure)
        }
        cast.connected && cast.hasMedia -> Box(modifier = modifier.fillMaxWidth().background(ChinoSurface).then(inset)) {
            AndroidFragment<ChinoMiniControllerFragment>(modifier = Modifier.fillMaxWidth())
        }
    }
}
