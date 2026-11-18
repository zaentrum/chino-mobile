package cloud.nalet.chino.mobile.ui.auth

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import cafe.adriel.voyager.navigator.Navigator
import cloud.nalet.chino.mobile.LocalAppContainer

/**
 * An account the server deleted, signed out on this phone where it was the
 * account signed in ([cloud.nalet.chino.mobile.data.AppContainer.startOver]):
 * the app starts over on [navigator] as after Delete account - who's watching
 * where another account is left, sign in where none is - whichever screen
 * was open, and the start-over is taken. Where no screen was drawn as it was
 * signed out, the next one drawn starts over. The toast "Account deleted"
 * says so. An account that was not the one signed in goes from who's
 * watching alone.
 */
@Composable
fun StartOverWhenAccountDeleted(navigator: Navigator) {
    val container = LocalAppContainer.current
    val left by container.startOver.collectAsState()
    LaunchedEffect(left, navigator) {
        val gone = left ?: return@LaunchedEffect
        navigator.replaceAll(if (gone.othersRemain) AccountPickerScreen() else AuthScreen())
        container.startedOver(gone)
    }
}
