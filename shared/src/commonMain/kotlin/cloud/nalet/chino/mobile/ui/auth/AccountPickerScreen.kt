package cloud.nalet.chino.mobile.ui.auth

import androidx.compose.ui.graphics.RectangleShape

import cloud.nalet.chino.mobile.ui.theme.ChinoBg
import cloud.nalet.chino.mobile.ui.theme.ChinoBg2
import cloud.nalet.chino.mobile.ui.theme.ChinoBorder
import cloud.nalet.chino.mobile.ui.theme.ChinoCloudBlue
import cloud.nalet.chino.mobile.ui.theme.ChinoDim
import cloud.nalet.chino.mobile.ui.theme.ChinoFg
import cloud.nalet.chino.mobile.ui.theme.ChinoFg2
import cloud.nalet.chino.mobile.ui.theme.ChinoGreen
import cloud.nalet.chino.mobile.ui.theme.ChinoHeading
import cloud.nalet.chino.mobile.ui.theme.ChinoMuted
import cloud.nalet.chino.mobile.ui.theme.ChinoSurfaceHi

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.screen.ScreenKey
import cafe.adriel.voyager.core.screen.uniqueScreenKey
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cloud.nalet.chino.mobile.LocalAppContainer
import cloud.nalet.chino.mobile.data.auth.Account
import cloud.nalet.chino.mobile.data.auth.LocalSignInLauncher
import cloud.nalet.chino.mobile.ui.components.Avatar
import cloud.nalet.chino.mobile.ui.shell.MainShellScreen
import cloud.nalet.chino.mobile.ui.shell.Section
import cloud.nalet.chino.mobile.ui.downloads.LocalDeviceKind
import cloud.nalet.chino.mobile.data.downloads.DownloadsSummary
import cloud.nalet.chino.mobile.data.downloads.Owner
import cloud.nalet.chino.mobile.data.downloads.REMOVE_ACCOUNT_LABEL
import cloud.nalet.chino.mobile.data.downloads.REMOVE_ACCOUNT_TITLE
import cloud.nalet.chino.mobile.data.downloads.accountDownloadsGoSentence
import cloud.nalet.chino.mobile.data.downloads.accountRemovedSentence
import cloud.nalet.chino.mobile.data.downloads.castingStopsSentence
import cloud.nalet.chino.mobile.data.downloads.pickerHintLine
import cloud.nalet.chino.mobile.ui.downloads.removeFromThisPhone
import cloud.nalet.chino.mobile.ui.downloads.switchAccount
import cloud.nalet.chino.mobile.ui.components.QuestionDialog
import cloud.nalet.chino.mobile.ui.components.SystemBackHandler
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.X
import kotlinx.coroutines.launch

/**
 * "Who's watching?" account picker — phone/tablet touch adaptation of
 * chino-androidtv's AccountPickerScreen. Lists every saved account (avatar +
 * name, the active one highlighted), an "Add account" tile that re-runs the
 * AppAuth sign-in flow and persists a new account, and a per-account remove.
 *
 * Tapping an account makes it active and lands on the main shell. The
 * AppContainer's activeAccountId watcher invalidates the stream token on the
 * switch so the new user's poster/player URLs are signed for them
 * ([StreamTokenManager.invalidate]); the authenticated Ktor client also reads
 * the new account's bearer on its next call, so data refreshes for the new
 * user automatically.
 *
 * Accounts arrive pre-sorted lastUsedAt DESC from [AccountStore.accounts], so
 * the tile order matches the TV's last-used-first layout. We seed the cold-
 * emitting flow with [AccountStore.snapshotBlocking] so the grid renders its
 * real tiles on the first frame rather than flashing the Add-account tile
 * alone.
 *
 * Reached from: the boot gate / sign-out (≥1 account remaining), and the
 * Profile screen's Sign out (it routes here when other accounts remain).
 */
class AccountPickerScreen(
    /** Where the shell opens once an account is picked: its downloads, for
     *  an offline start ("Who's watching?" works without the server). */
    private val startSection: Section = Section.Home,
) : Screen {
    override val key: ScreenKey = uniqueScreenKey

    @Composable
    override fun Content() {
        val nav = LocalNavigator.currentOrThrow
        val container = LocalAppContainer.current
        val launcher = LocalSignInLauncher.current
        val scope = rememberCoroutineScope()
        val store = container.accountStore

        val seed = remember { store.snapshotBlocking().accounts }
        val accounts by store.accounts.collectAsState(initial = seed)
        val activeId by store.activeAccountId.collectAsState(initial = store.snapshotBlocking().activeAccount?.id)

        // Drives the "Add account" sign-in. Reuses AuthScreenModel so the
        // userinfo lookup + Account persist is identical to first-run sign-in.
        val authModel = remember { AuthScreenModel(container) }
        val authState by authModel.state.collectAsState()
        var pendingRemoval by remember { mutableStateOf<Account?>(null) }
        // A switch or a removal on its way - it waits for the TV's last
        // position while the account casts: the account it is for, its tile
        // busy, no tile takes a tap, and Back waits for it.
        var leaving by remember { mutableStateOf<String?>(null) }
        SystemBackHandler(enabled = leaving != null) { }

        // When the Add-account sign-in completes, the new account is already
        // persisted + set active by AuthScreenModel; jump to the shell.
        LaunchedEffect(authState) {
            if (authState is AuthUiState.Authenticated) {
                nav.replaceAll(MainShellScreen())
            }
        }

        val signing = authState is AuthUiState.Signing
        val busy = signing || leaving != null

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            val isWide = maxWidth >= 600.dp
            // Tablet shows wider tiles in more columns; phone packs ~3-up.
            val minTile = if (isWide) 160.dp else 110.dp
            val outerPad = if (isWide) 48.dp else 24.dp

            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = minTile),
                contentPadding = PaddingValues(outerPad),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
                // Edge to edge, the heading starts below the status bar (and
                // App's accent strip over it), as on Profile.
                modifier = Modifier.fillMaxSize().statusBarsPadding(),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column {
                        // A page title, in the heading face.
                        Text(
                            text = "Who's watching?",
                            color = Color.White,
                            fontSize = if (isWide) 34.sp else 26.sp,
                            fontWeight = FontWeight.SemiBold,
                            style = ChinoHeading,
                        )
                        Text(
                            text = pickerHintLine(LocalDeviceKind.current),
                            color = ChinoMuted,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(top = 6.dp, bottom = 8.dp),
                        )
                    }
                }

                items(accounts, key = { it.id }) { account ->
                    AccountTile(
                        account = account,
                        isActive = account.id == activeId,
                        enabled = !busy,
                        switching = leaving == account.id && pendingRemoval == null,
                        onSelect = {
                            if (leaving == null) {
                                leaving = account.id
                                scope.launch {
                                    // Another account in its place: the one
                                    // casting stops, its last position written
                                    // first, and a toast says so - the switch
                                    // asks nothing.
                                    switchAccount(container, from = activeId, to = account.id)
                                    nav.replaceAll(MainShellScreen(startSection = startSection))
                                }
                            }
                        },
                        onRemove = { pendingRemoval = account },
                    )
                }

                item(key = "__add_account__") {
                    AddAccountTile(
                        signing = signing,
                        enabled = !busy,
                        onClick = { if (!busy) scope.launch { authModel.signIn(launcher) } },
                    )
                }
            }
        }

        pendingRemoval?.let { acct ->
            // The account's downloads on this phone, which go with it.
            val owner = Owner(container.serverBase, acct.id)
            val summary = remember(acct.id) { container.downloads?.summaryOf(owner) ?: DownloadsSummary(0, 0L) }
            // While it casts: casting stops too, the question says.
            val castingTo = remember(acct.id) { container.castingDeviceOf(acct.id) }
            ConfirmRemoveDialog(
                account = acct,
                downloads = summary,
                castingTo = castingTo,
                busy = leaving == acct.id,
                onConfirm = {
                    if (leaving == null) {
                        leaving = acct.id
                        scope.launch {
                            // An explicit removal on this phone: casting
                            // stops, its downloads go, as the question said.
                            removeFromThisPhone(container, acct.id, castingSaid = castingTo != null)
                            val remaining = store.snapshotBlocking().accounts
                            pendingRemoval = null
                            leaving = null
                            // Removing the last account leaves nobody to
                            // pick — fall through to Auth, matching App.kt's
                            // boot gate.
                            if (remaining.isEmpty()) nav.replaceAll(AuthScreen())
                        }
                    }
                },
                onDismiss = { pendingRemoval = null },
            )
        }
    }
}

@Composable
private fun AccountTile(
    account: Account,
    isActive: Boolean,
    enabled: Boolean,
    /** The switch to it is on its way: a spinner over its avatar. */
    switching: Boolean,
    onSelect: () -> Unit,
    onRemove: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RectangleShape)
            .clickable(enabled = enabled, onClick = onSelect)
            .semantics { if (switching) stateDescription = "busy" }
            .padding(8.dp),
    ) {
        Box(contentAlignment = Alignment.TopEnd) {
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(RectangleShape)
                    .then(
                        if (isActive) {
                            Modifier.border(width = 3.dp, color = ChinoCloudBlue, shape = RectangleShape)
                        } else Modifier,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Avatar(displayName = account.displayName, email = account.email, size = 88.dp)
                if (switching) {
                    Box(modifier = Modifier.size(88.dp).background(Color(0x99000000)), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(36.dp), color = Color.White)
                    }
                }
            }
            // The remove badge: a ✕ on the tile's corner, "remove account"
            // to a screen reader - removing an account, not signing out of
            // it (the TV uses long-press; a phone gets a tap target, with no
            // remote's long press to offer).
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RectangleShape)
                    .background(ChinoBg2)
                    .border(width = 1.dp, color = ChinoBorder, shape = RectangleShape)
                    .clickable(enabled = enabled, onClickLabel = REMOVE_ACCOUNT_LABEL, role = Role.Button, onClick = onRemove)
                    .semantics { contentDescription = REMOVE_ACCOUNT_LABEL },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Lucide.X,
                    contentDescription = null,
                    tint = ChinoFg2,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        Text(
            text = account.displayName,
            color = if (isActive) Color.White else ChinoFg2,
            fontSize = 14.sp,
            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun AddAccountTile(signing: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RectangleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(RectangleShape)
                .background(Color(0x1AFFFFFF)),
            contentAlignment = Alignment.Center,
        ) {
            if (signing) {
                CircularProgressIndicator(modifier = Modifier.size(36.dp), color = Color.White)
            } else {
                Icon(
                    imageVector = Lucide.Plus,
                    contentDescription = "Add account",
                    tint = Color.White,
                    modifier = Modifier.size(40.dp),
                )
            }
        }
        Text(
            text = if (signing) "Signing in…" else "Add account",
            color = ChinoFg2,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The question before an account goes from the phone: the one question the
 * app asks before what can't be undone ([QuestionDialog]) - "remove
 * account", what goes ("The 23 downloads of Anna, 12.4 GB, are deleted from
 * this phone.", or with none "This removes Anna from this phone. ..."), that
 * casting stops while it casts, "cancel" and "remove account". [busy]: the
 * removal runs, and the question stays until it is done.
 */
@Composable
private fun ConfirmRemoveDialog(
    account: Account,
    downloads: DownloadsSummary,
    /** The device the account casts to now, if it does: casting stops. */
    castingTo: String?,
    busy: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val device = LocalDeviceKind.current
    val goes = if (downloads.isEmpty) {
        accountRemovedSentence(account.displayName, device)
    } else {
        accountDownloadsGoSentence(downloads, account.displayName, device)
    }
    QuestionDialog(
        title = REMOVE_ACCOUNT_TITLE,
        body = listOfNotNull(goes, castingTo?.let(::castingStopsSentence)).joinToString(" "),
        action = REMOVE_ACCOUNT_TITLE,
        onAction = onConfirm,
        onDismiss = onDismiss,
        busy = busy,
    )
}
