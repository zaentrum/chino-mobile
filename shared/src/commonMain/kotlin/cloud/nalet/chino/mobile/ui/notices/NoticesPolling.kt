package cloud.nalet.chino.mobile.ui.notices

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import cloud.nalet.chino.mobile.LocalAppContainer
import cloud.nalet.chino.mobile.data.notices.NOTICE_POLL_MS
import kotlinx.coroutines.delay

/**
 * Asks chino-api for the person's notices now, every minute while the app
 * is in the foreground, and as soon as it is back in it — and only while
 * someone is signed in. Composed once, beside the navigator (App), so the
 * count is fresh whichever screen is shown.
 *
 * The lifecycle it follows is the app's: the activity's on Android, the
 * hosting view controller's on iOS, which Compose moves below STARTED when
 * the app goes to the background. Keyed by the account: when another one is
 * signed in, or none, what was shown for the one before is forgotten at once
 * (NoticesRepository.clear) and the poll starts over.
 */
@Composable
fun NoticesPolling() {
    val container = LocalAppContainer.current
    val accountId by container.accountStore.activeAccountId.collectAsState(initial = null)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val signedIn = accountId ?: return
    DisposableEffect(signedIn) {
        onDispose { container.notices.clear() }
    }
    LaunchedEffect(signedIn, lifecycle) {
        val notices = container.notices
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                notices.refresh()
                delay(NOTICE_POLL_MS)
            }
        }
    }
}
