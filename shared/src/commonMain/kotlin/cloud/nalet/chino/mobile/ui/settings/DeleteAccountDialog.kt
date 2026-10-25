package cloud.nalet.chino.mobile.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cloud.nalet.chino.mobile.LocalAppContainer
import cloud.nalet.chino.mobile.data.account.AccountDeletion
import cloud.nalet.chino.mobile.data.account.deleteAccountThenSignOut
import cloud.nalet.chino.mobile.data.downloads.yourDownloadsGoSentence
import cloud.nalet.chino.mobile.ui.downloads.LocalDeviceKind
import cloud.nalet.chino.mobile.ui.downloads.downloadsOf
import cloud.nalet.chino.mobile.data.account.isFinal
import cloud.nalet.chino.mobile.data.account.text
import cloud.nalet.chino.mobile.data.account.title
import cloud.nalet.chino.mobile.data.auth.Account
import cloud.nalet.chino.mobile.ui.components.QuestionDialog
import cloud.nalet.chino.mobile.ui.components.QuestionText
import cloud.nalet.chino.mobile.ui.theme.ChinoFg2
import cloud.nalet.chino.mobile.ui.theme.ChinoMuted
import cloud.nalet.chino.mobile.ui.theme.ChinoRed
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What deleting an account takes with it, as the dialog lists it. */
private val WHAT_GOES = listOf(
    "your watch progress",
    "your lists",
    "your likes",
    "your watch history",
    "your sign-in: you can't sign in with this account again",
)

/**
 * Delete account, opened from Settings → Account: the one question the app
 * asks before what can't be undone ([QuestionDialog], a platform dialog, so
 * screen readers treat it as modal and the system back closes it), that
 * names the account and the server, says what goes and that it cannot be
 * undone, and deletes only from its danger button, "delete account".
 *
 * The answers (data/account): deleted → the account is signed out on this
 * phone ([cloud.nalet.chino.mobile.data.AppContainer.forgetDeletedAccount])
 * and [onSignedOut] takes the app back to the start; refused or not
 * available here → the reason, and "close"; any other → the server's
 * message, "delete account" still there. Back, a tap outside and "cancel"
 * do nothing while the request is out; the request and the sign-out finish
 * even should the dialog be gone by then.
 */
@Composable
fun DeleteAccountDialog(
    /** The account to delete: the one active when the dialog opened. */
    account: Account,
    /** The connected server's host, to name it. */
    serverHost: String?,
    onDismiss: () -> Unit,
    /** The account is deleted and signed out. [othersRemain]: other
     *  accounts are still signed in on this device. */
    onSignedOut: (othersRemain: Boolean) -> Unit,
) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    var deleting by remember { mutableStateOf(false) }
    var answer by remember { mutableStateOf<AccountDeletion?>(null) }
    val final = answer?.isFinal == true

    val delete: () -> Unit = delete@{
        if (deleting || final) return@delete
        deleting = true
        answer = null
        scope.launch {
            val result = withContext(NonCancellable) {
                // The bearer is the active account's: never send it for
                // another one than the account on screen.
                if (container.accountStore.snapshotBlocking().activeAccount?.id != account.id) {
                    AccountDeletion.Failed("Another account is signed in now. Open Delete Account again.")
                } else {
                    container.chinoApi.deleteAccountThenSignOut { container.forgetDeletedAccount(account.id) }
                }
            }
            deleting = false
            if (result == AccountDeletion.Deleted) {
                onSignedOut(container.accountStore.snapshotBlocking().accounts.isNotEmpty())
            } else {
                answer = result
            }
        }
    }

    val who = account.email.ifBlank { account.displayName }
    // Its downloads on this phone go with it: the question says so.
    val downloads = remember(account.id) { downloadsOf(container, account.id) }
    val device = LocalDeviceKind.current
    // The one question before what can't be undone: "delete account" in
    // monospace, what goes, "cancel" and the danger button - "close" alone
    // once the answer leaves nothing to do here.
    QuestionDialog(
        title = DELETE_ACCOUNT_TITLE,
        action = DELETE_ACCOUNT_TITLE.takeUnless { final },
        onAction = delete,
        onDismiss = onDismiss,
        busy = deleting,
        dismissLabel = if (final) "close" else "cancel",
    ) {
        QuestionText("This deletes the account $who on ${serverHost ?: "this server"}, and with it:")
        Column(
            modifier = Modifier.padding(start = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            WHAT_GOES.forEach { line ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(modifier = Modifier.size(5.dp).background(ChinoMuted))
                    Text(text = line, color = ChinoFg2, fontSize = 14.sp)
                }
            }
        }
        if (!downloads.isEmpty) QuestionText(yourDownloadsGoSentence(downloads, device))
        QuestionText("This can't be undone.")
        // Progress, or why the account is still there: announced.
        Column(
            modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            val shown = answer
            when {
                deleting -> Text(text = "Deleting your account…", color = ChinoMuted, fontSize = 13.sp)
                shown != null -> {
                    shown.title?.let {
                        Text(text = it, color = ChinoRed, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Text(text = shown.text, color = ChinoFg2, fontSize = 13.sp)
                }
            }
        }
    }
}

/** Delete account's title, which its danger button repeats. */
private const val DELETE_ACCOUNT_TITLE = "delete account"
