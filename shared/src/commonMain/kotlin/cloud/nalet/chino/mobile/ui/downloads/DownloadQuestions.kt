package cloud.nalet.chino.mobile.ui.downloads

import androidx.compose.runtime.Composable
import cloud.nalet.chino.mobile.data.AppContainer
import cloud.nalet.chino.mobile.data.downloads.DownloadsSummary
import cloud.nalet.chino.mobile.data.downloads.Owner
import cloud.nalet.chino.mobile.data.downloads.castingStopsSentence
import cloud.nalet.chino.mobile.data.downloads.stoppedCastingToast
import cloud.nalet.chino.mobile.ui.components.QuestionDialog
import cloud.nalet.chino.mobile.ui.components.Toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * The question before an action that deletes downloads (the design system's
 * confirm, [QuestionDialog]): its title is the action, the confirming danger
 * button repeats it, the sentence names what goes - "sign out", "Your 23
 * downloads, 12.4 GB, are deleted from this phone.", "cancel" and "sign
 * out". Back, a tap outside and "cancel" leave everything as it is. [busy]:
 * the action runs, and the question stays until it is done.
 */
@Composable
fun DownloadsGoQuestion(
    title: String,
    sentence: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    /** The danger button: the action the title names ("delete all" for
     *  "delete all downloads"). */
    confirm: String = title,
    busy: Boolean = false,
) {
    QuestionDialog(title = title, body = sentence, action = confirm, onAction = onConfirm, onDismiss = onDismiss, busy = busy)
}

/** [accountId]'s downloads on this phone, for the question before they go;
 *  empty where the platform keeps none. */
fun downloadsOf(container: AppContainer, accountId: String): DownloadsSummary =
    container.downloads?.summaryOf(Owner(container.serverBase, accountId)) ?: DownloadsSummary(0, 0L)

/*
 * Signing out, removing an account and switching to another wait for the
 * TV's last position to be written while the account casts - up to a few
 * seconds on a slow server. They run to their end once started, whatever
 * becomes of the screen that started them (Back, another tab): never an
 * account half gone, its downloads deleted and still signed in, or casting
 * on. The screens show them busy meanwhile, and take no second tap.
 */

/** An explicit sign-out of [accountId] on this phone: casting stops - what
 *  the TV plays of it written as it first - its downloads go with it (the
 *  media whose last claim it was), then the account. Where no question said
 *  that casting stops ([castingSaid]), a toast does. Runs to its end once
 *  started. */
suspend fun signOutOfThisPhone(container: AppContainer, accountId: String, castingSaid: Boolean = false) = toItsEnd {
    leaveCastingOf(container, accountId, castingSaid)
    dropDownloadsOf(container, accountId)
    container.accountStore.remove(accountId)
}

/** [accountId] removed from this phone in the account picker: as a
 *  sign-out - casting stops, its downloads go, then the account. Runs to its
 *  end once started. */
suspend fun removeFromThisPhone(container: AppContainer, accountId: String, castingSaid: Boolean) =
    signOutOfThisPhone(container, accountId, castingSaid)

/** [to] made the account signed in, in [from]'s place where another was:
 *  the one casting stops, its last position written first, and a toast says
 *  so - a switch asks nothing. Runs to its end once started. */
suspend fun switchAccount(container: AppContainer, from: String?, to: String) = toItsEnd {
    from?.takeIf { it != to }?.let { leaveCastingOf(container, it) }
    container.accountStore.setActive(to)
}

/** [accountId] leaves the phone - signed out, removed, another account in
 *  its place, deleted - while it casts: its last position on the TV is
 *  written as it, and casting stops; "Stopped casting on {device}." where
 *  no question said so ([castingSaid]). A cast that will not stop does not
 *  hold the account here. */
suspend fun leaveCastingOf(container: AppContainer, accountId: String, castingSaid: Boolean = false) {
    val device = try {
        container.leaveCasting(accountId, true)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    } ?: return
    if (!castingSaid) container.toasts.tryEmit(Toast(stoppedCastingToast(device)))
}

/** [accountId]'s downloads on this phone deleted - the media whose last
 *  claim it was; one that will not go does not hold the account here. */
private suspend fun dropDownloadsOf(container: AppContainer, accountId: String) {
    try {
        container.downloads?.dropOwner(Owner(container.serverBase, accountId))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Unit
    }
}

/** [block] to its end once started, whatever becomes of the caller's scope
 *  meanwhile - the screen that started it gone (Back, another tab). */
internal suspend fun <T> toItsEnd(block: suspend () -> T): T = withContext(NonCancellable) { block() }

/** The question's [sentence] before [accountId] signs out or goes from the
 *  phone, and - while it casts - that casting stops: "Casting on Living
 *  Room TV stops." The device by the name it goes by, as the session has it.
 *  No downloads (a blank [sentence]): the casting sentence alone. */
fun withCastingSentence(sentence: String, container: AppContainer, accountId: String): String =
    listOfNotNull(sentence.takeIf { it.isNotBlank() }, container.castingDeviceOf(accountId)?.let(::castingStopsSentence)).joinToString(" ")

/** Whether the question is asked before [accountId] signs out, goes or
 *  its server changes: where its downloads go ([summary]), or while it
 *  casts, which stops. */
fun asksBeforeLeaving(summary: DownloadsSummary?, container: AppContainer, accountId: String?): Boolean =
    (summary != null && !summary.isEmpty) || (accountId != null && container.castingDeviceOf(accountId) != null)
