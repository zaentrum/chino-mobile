package cloud.nalet.chino.mobile.ui.downloads

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.sp
import cloud.nalet.chino.mobile.data.AppContainer
import cloud.nalet.chino.mobile.data.downloads.DownloadsSummary
import cloud.nalet.chino.mobile.data.downloads.Owner
import cloud.nalet.chino.mobile.ui.theme.ChinoFg2
import cloud.nalet.chino.mobile.ui.theme.ChinoHeading
import cloud.nalet.chino.mobile.ui.theme.ChinoMono
import cloud.nalet.chino.mobile.ui.theme.ChinoRed
import cloud.nalet.chino.mobile.ui.theme.ChinoSurface

/**
 * The question before an action that deletes downloads (the design system's):
 * its title is the action, the confirming danger button repeats it, the
 * sentence names what goes - "sign out", "Your 23 downloads, 12.4 GB, are
 * deleted from this phone.", "cancel" and "sign out". Back, a tap outside
 * and "cancel" leave everything as it is.
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
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RectangleShape,
        containerColor = ChinoSurface,
        title = { Text(title, color = Color.White, style = ChinoHeading, fontSize = 18.sp) },
        text = { Text(sentence, color = ChinoFg2, fontSize = 14.sp) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirm, color = ChinoRed, style = ChinoMono) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("cancel", color = Color.White, style = ChinoMono) }
        },
    )
}

/** [accountId]'s downloads on this phone, for the question before they go;
 *  empty where the platform keeps none. */
fun downloadsOf(container: AppContainer, accountId: String): DownloadsSummary =
    container.downloads?.summaryOf(Owner(container.serverBase, accountId)) ?: DownloadsSummary(0, 0L)

/** An explicit sign-out of [accountId] on this phone: its downloads go with
 *  it - the media whose last claim it was - then the account. */
suspend fun signOutOfThisPhone(container: AppContainer, accountId: String) {
    runCatching { container.downloads?.dropOwner(Owner(container.serverBase, accountId)) }
    container.accountStore.remove(accountId)
}
