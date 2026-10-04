package cloud.nalet.chino.mobile.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import cloud.nalet.chino.mobile.LocalAppContainer
import cloud.nalet.chino.mobile.data.account.AccountDeletion
import cloud.nalet.chino.mobile.data.account.deleteAccountThenSignOut
import cloud.nalet.chino.mobile.data.account.isFinal
import cloud.nalet.chino.mobile.data.account.text
import cloud.nalet.chino.mobile.data.account.title
import cloud.nalet.chino.mobile.data.auth.Account
import cloud.nalet.chino.mobile.ui.theme.ChinoFg2
import cloud.nalet.chino.mobile.ui.theme.ChinoMuted
import cloud.nalet.chino.mobile.ui.theme.ChinoRed
import cloud.nalet.chino.mobile.ui.theme.ChinoSurface
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.TriangleAlert
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
 * Delete Account, opened from Settings → Account. A destructive dialog — the
 * platform's (Material3's AlertDialog, not the scrim card the other dialogs
 * draw), so screen readers treat it as modal and the system back closes it —
 * that names the account and the server, says what goes and that it cannot
 * be undone, and deletes only from its red Delete Account button.
 *
 * The answers (data/account): deleted → the account is signed out on this
 * device ([cloud.nalet.chino.mobile.data.AppContainer.forgetDeletedAccount])
 * and [onSignedOut] takes the app back to the start; refused or not
 * available here → the reason, and Close; any other → Try Again Later with
 * the server's message, Delete Account still there. Back, a tap outside and
 * Cancel do nothing while the request is out; the request and the sign-out
 * finish even should the dialog be gone by then.
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
    AlertDialog(
        onDismissRequest = { if (!deleting) onDismiss() },
        properties = DialogProperties(dismissOnBackPress = !deleting, dismissOnClickOutside = !deleting),
        shape = RectangleShape,
        containerColor = ChinoSurface,
        icon = { Icon(imageVector = Lucide.TriangleAlert, contentDescription = null, tint = ChinoRed) },
        title = {
            Text(
                text = "Delete Account",
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() },
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "This deletes the account $who on ${serverHost ?: "this server"}, and with it:",
                    color = ChinoFg2,
                    fontSize = 14.sp,
                )
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
                Text(text = "This can't be undone.", color = ChinoFg2, fontSize = 14.sp)
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
        },
        confirmButton = {
            if (!final) {
                Button(
                    onClick = delete,
                    enabled = !deleting,
                    shape = RectangleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ChinoRed,
                        contentColor = Color.White,
                        disabledContainerColor = ChinoRed.copy(alpha = 0.6f),
                        disabledContentColor = Color.White,
                    ),
                ) {
                    if (deleting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            color = Color.White,
                            strokeWidth = 2.dp,
                        )
                        Text(text = "Deleting…", modifier = Modifier.padding(start = 8.dp))
                    } else {
                        Text(text = "Delete Account")
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !deleting, shape = RectangleShape) {
                Text(text = if (final) "Close" else "Cancel", color = if (deleting) ChinoMuted else ChinoFg2)
            }
        },
    )
}
