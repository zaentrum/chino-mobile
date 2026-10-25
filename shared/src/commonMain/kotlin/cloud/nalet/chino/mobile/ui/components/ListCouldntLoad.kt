package cloud.nalet.chino.mobile.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cloud.nalet.chino.mobile.data.api.LoadFailure
import cloud.nalet.chino.mobile.data.api.NO_ACCESS_TITLE
import cloud.nalet.chino.mobile.data.api.sentence
import cloud.nalet.chino.mobile.ui.auth.AuthScreen

/**
 * What a screen of the shell that lists things - Movies, Shows, Search,
 * the Watchlist - shows when it couldn't load, by the design system's case
 * ([failure], by the answer's status), as a title's page does:
 *  - signed out (a 401 the app's renewal did not mend): no empty state, the
 *    sign-in;
 *  - no access (403): "you don't have access to this", nothing under it -
 *    the shell's tabs are the way on;
 *  - else what couldn't load ([title], in the screen's own words), the
 *    reason, and "try again"; the status under details.
 */
@Composable
fun ListCouldntLoad(
    title: String,
    failure: LoadFailure,
    details: String?,
    onTryAgain: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (failure) {
        LoadFailure.SignedOut -> {
            val nav = LocalNavigator.currentOrThrow
            LaunchedEffect(Unit) { nav.replaceAll(AuthScreen()) }
        }
        LoadFailure.NoAccess -> CouldntLoad(
            title = NO_ACCESS_TITLE,
            sentence = null,
            action = null,
            onAction = {},
            modifier = modifier,
            details = details,
        )
        else -> CouldntLoad(
            title = title,
            sentence = failure.sentence(),
            action = "try again",
            onAction = onTryAgain,
            modifier = modifier,
            details = details,
        )
    }
}
