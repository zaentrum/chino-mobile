package cloud.nalet.chino.mobile.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import cloud.nalet.chino.mobile.ui.theme.ChinoBorderControl
import androidx.compose.ui.graphics.RectangleShape

import cloud.nalet.chino.mobile.ui.theme.ChinoBg
import cloud.nalet.chino.mobile.ui.theme.ChinoOnFill
import cloud.nalet.chino.mobile.ui.theme.ChinoBg2
import cloud.nalet.chino.mobile.ui.theme.ChinoBorder
import cloud.nalet.chino.mobile.ui.theme.ChinoBorder2
import cloud.nalet.chino.mobile.ui.theme.ChinoCloudBlue
import cloud.nalet.chino.mobile.ui.theme.ChinoDim
import cloud.nalet.chino.mobile.ui.theme.ChinoFg
import cloud.nalet.chino.mobile.ui.theme.ChinoFg2
import cloud.nalet.chino.mobile.ui.theme.ChinoGreen
import cloud.nalet.chino.mobile.ui.theme.ChinoHeading
import cloud.nalet.chino.mobile.ui.theme.ChinoMuted
import cloud.nalet.chino.mobile.ui.theme.ChinoRed
import cloud.nalet.chino.mobile.ui.theme.ChinoSurface
import cloud.nalet.chino.mobile.ui.theme.ChinoSurfaceHi

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.ChevronUp
import com.composables.icons.lucide.Lucide
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cloud.nalet.chino.mobile.LocalAppContainer
import cloud.nalet.chino.mobile.data.AppSettings
import cloud.nalet.chino.mobile.data.auth.Account
import cloud.nalet.chino.mobile.feedback.captureScreenshot
import cloud.nalet.chino.mobile.ui.auth.AccountPickerScreen
import cloud.nalet.chino.mobile.ui.auth.AuthScreen
import cloud.nalet.chino.mobile.ui.downloads.DownloadSettings
import cloud.nalet.chino.mobile.ui.downloads.DownloadsGoQuestion
import cloud.nalet.chino.mobile.ui.downloads.LocalDeviceKind
import cloud.nalet.chino.mobile.ui.downloads.downloadsOf
import cloud.nalet.chino.mobile.ui.downloads.signOutOfThisPhone
import cloud.nalet.chino.mobile.ui.downloads.asksBeforeLeaving
import cloud.nalet.chino.mobile.ui.downloads.withCastingSentence
import cloud.nalet.chino.mobile.data.downloads.DownloadsSummary
import cloud.nalet.chino.mobile.data.downloads.SIGN_OUT_TITLE
import cloud.nalet.chino.mobile.data.downloads.yourDownloadsGoSentence
import cloud.nalet.chino.mobile.ui.feedback.BugReportDialog
import cloud.nalet.chino.mobile.ui.onboarding.AddServerScreen
import cloud.nalet.chino.mobile.ui.profile.ProfileScreen
import kotlinx.coroutines.launch

/**
 * Settings tab — binge ergonomics + display preferences. Mirrors chino-
 * web's SettingsPage section. Stored in SettingsStore (DataStore on
 * Android, NSUserDefaults on iOS). Lives inline in the bottom-nav so
 * users don't lose home context to reach it.
 */
@Composable
fun SettingsSection() {
    val container = LocalAppContainer.current
    val nav = LocalNavigator.currentOrThrow
    val scope = rememberCoroutineScope()
    val s: AppSettings by container.settings.flow.collectAsState(initial = AppSettings())
    val activeAccount by container.accountStore.activeAccount.collectAsState(initial = null)
    // Current connected-server origin for the "Change server" row subtitle.
    val serverHost = container.serverConfig?.baseUrl
        ?.substringAfter("://")
        ?.substringBefore("/")
        ?.takeIf { it.isNotBlank() }

    // Manual bug-report dialog. The screenshot is captured BEFORE the dialog
    // mounts (a dialog over the screen would only ever show itself); non-null
    // draft = dialog open. Plain holder class, not data — ByteArray equality
    // is identity anyway.
    var bugReportDraft by remember { mutableStateOf<BugReportDraft?>(null) }

    // Delete Account dialog: the account it deletes is the one active when
    // it opened, held here so the dialog keeps naming it while it is signed
    // out underneath. Non-null = dialog open.
    var deleteTarget by remember { mutableStateOf<Account?>(null) }

    // The question before a sign-out that deletes the account's downloads:
    // what goes, non-null while it is open.
    var signOutDownloads by remember { mutableStateOf<DownloadsSummary?>(null) }
    // A sign-out runs to its end once started (the TV's last position
    // written first, up to a few seconds): busy until the screen goes, and
    // no second one meanwhile.
    var signingOut by remember { mutableStateOf(false) }
    val device = LocalDeviceKind.current
    // What the server offers: a bug report goes only where it takes them.
    val features by container.serverReach.features.collectAsState()
    // The server's subtitle languages (GET /subtitles/languages), asked once
    // a session as the player's menu asks; null until it answers - and
    // while it doesn't (offline, a 5xx), never taken for a server that
    // names none: asked again as the server answers again.
    val reach by container.serverReach.state.collectAsState()
    val serverSubtitleLanguages by produceState<List<String>?>(initialValue = null, container, reach) {
        container.subtitleLanguages.answered()?.let { value = it }
    }
    val signOut: (castingSaid: Boolean) -> Unit = { castingSaid ->
        val id = activeAccount?.id
        if (id != null && !signingOut) {
            signingOut = true
            scope.launch {
                signOutOfThisPhone(container, id, castingSaid)
                val remaining = container.accountStore.snapshotBlocking().accounts
                if (remaining.isEmpty()) nav.replaceAll(AuthScreen())
                else nav.replaceAll(AccountPickerScreen())
            }
        }
    }

    // Outer Box so the bug-report dialog can scrim the whole content area
    // (same overlay pattern as AccountPicker's ConfirmRemoveDialog).
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                // The section list outgrew one phone-portrait screen when the
                // Feedback section landed — without this the overflow is
                // silently clipped (no scrollbar, no crash).
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // The web's h1, in the heading face.
            Text(
                text = "Settings",
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                style = ChinoHeading,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            SectionHeading("Account")
            // Profile entry — name/email + watch history, mirrors the TopBar avatar.
            ActionRow(
                // "account", the word for who is watching (docs/chino words).
                label = "Your account",
                subtitle = activeAccount?.let { it.email.ifBlank { it.displayName } }
                    ?: "Your account and watch history",
                onClick = { nav.push(ProfileScreen()) },
            )
            // Switch account — the "Who's watching?" picker (also used to add a
            // second account on this device).
            ActionRow(
                label = "Switch account",
                subtitle = "Switch users or add another account",
                onClick = { nav.push(AccountPickerScreen()) },
            )
            // Sign out of the active account. Removing it promotes the next
            // account to active (or none); route per the boot gate — picker when
            // others remain, Auth when this was the last one.
            ActionRow(
                label = "Sign out",
                subtitle = activeAccount?.displayName ?: "Sign out of this account",
                enabled = !signingOut,
                onClick = {
                    // Its downloads go with it: asked first. None: as before.
                    val summary = activeAccount?.id?.let { downloadsOf(container, it) }
                    if (asksBeforeLeaving(summary, container, activeAccount?.id)) signOutDownloads = summary ?: DownloadsSummary(0, 0L) else signOut(false)
                },
            )
            // Delete the active account on the server — asked first, in a
            // destructive dialog; signed out only once the server deleted it.
            ActionRow(
                label = "Delete Account",
                subtitle = "Delete your account and what this server keeps of it",
                destructive = true,
                onClick = { deleteTarget = activeAccount },
            )

            Box(modifier = Modifier.padding(top = 12.dp))
            SectionHeading("Binge watching")
            ToggleRow(
                label = "Auto-skip intros & recaps",
                value = s.autoSkipIntro,
                onChange = { v -> scope.launch { container.settings.setAutoSkipIntro(v) } },
            )
            ToggleRow(
                label = "Auto-skip credits",
                value = s.autoSkipCredits,
                onChange = { v -> scope.launch { container.settings.setAutoSkipCredits(v) } },
            )
            ToggleRow(
                label = "Auto-play next episode",
                value = s.autoPlayNext,
                onChange = { v -> scope.launch { container.settings.setAutoPlayNext(v) } },
            )
            // Downloads: their quality and whether they use mobile data -
            // where the platform keeps downloads and the server offers them.
            DownloadSettings()

            Box(modifier = Modifier.padding(top = 12.dp))
            SectionHeading("Server")
            // Change-server entry — re-opens Add-Server prefilled with the current
            // server. On connect to a DIFFERENT server the screen clears existing
            // accounts (their tokens are for the old issuer) and asks the host to
            // rebuild the app graph, then routes back to sign-in. Mirrors the TV's
            // Settings "change server" intent.
            // A different server deletes this one's downloads: asked once the
            // new server answers (AddServerScreen).
            ActionRow(
                label = "Change server",
                subtitle = serverHost ?: "Connect to a different Chino server",
                onClick = { nav.push(AddServerScreen(changeServer = true)) },
            )

            // Only where the server takes reports (its features.feedback, as
            // chino-web reads it): the row promises a recipient.
            if (features?.feedback == true) {
                Box(modifier = Modifier.padding(top = 12.dp))
                SectionHeading("Support")
                // Report a bug — capture the screenshot FIRST (the dialog would
                // only ever photograph itself), then mount the dialog with the
                // bytes. captureScreenshot is best-effort; null just means no
                // preview row + a text-only report.
                ActionRow(
                    label = "Report a bug",
                    subtitle = "Tell the people who run this server. The app's and the device's details go with your report.",
                    onClick = {
                        scope.launch {
                            bugReportDraft = BugReportDraft(screenshot = captureScreenshot())
                        }
                    },
                )
            }

            Box(modifier = Modifier.padding(top = 12.dp))
            // Audio sits ABOVE Subtitles (web SettingsPage order). Default
            // audio language; the player auto-picks the closest matching
            // track on first load. "Original" follows the item's source
            // language. Manual switches in the player menu only affect the
            // current playback.
            SectionHeading("Audio")
            Text(
                // docs/chino playback, "The audio setting": the same in every app.
                text = AUDIO_SETTING_TEXT,
                color = ChinoMuted,
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            LanguageSetting(
                name = "audio language",
                value = s.preferredAudioLang,
                choices = audioSettingChoices(s.preferredAudioLang),
                onSelect = { code -> scope.launch { container.settings.setPreferredAudioLang(code) } },
            )

            Box(modifier = Modifier.padding(top = 12.dp))
            SectionHeading("Subtitles")
            Text(
                // docs/chino subtitles, "The setting": the same in every app.
                text = SUBTITLE_SETTING_TEXT,
                color = ChinoMuted,
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            // The server's subtitle languages, read from its configuration as
            // the player's menu reads them; every language where it names
            // none, the one set and the phone's first.
            LanguageSetting(
                name = "subtitle language",
                value = s.preferredSubLang,
                choices = subtitleSettingChoices(serverSubtitleLanguages, s.preferredSubLang, cloud.nalet.chino.mobile.preferredLanguageTags().firstOrNull()),
                onSelect = { code -> scope.launch { container.settings.setPreferredSubLang(code) } },
            )
        }

        bugReportDraft?.let { draft ->
            BugReportDialog(
                screenshot = draft.screenshot,
                context = mapOf("screen" to "settings"),
                onDismiss = { bugReportDraft = null },
            )
        }

        signOutDownloads?.let { summary ->
            // While it casts: casting stops too, the question says.
            val id = activeAccount?.id.orEmpty()
            val casting = container.castingDeviceOf(id) != null
            DownloadsGoQuestion(
                title = SIGN_OUT_TITLE,
                sentence = withCastingSentence(if (summary.isEmpty) "" else yourDownloadsGoSentence(summary, device), container, id),
                // The question stays, its danger button busy, until the
                // sign-out is done and the screen goes.
                onConfirm = { signOut(casting) },
                onDismiss = { signOutDownloads = null },
                busy = signingOut,
            )
        }
        deleteTarget?.let { account ->
            DeleteAccountDialog(
                account = account,
                serverHost = serverHost,
                onDismiss = { deleteTarget = null },
                // Deleted and signed out: back to the start, as Sign out
                // goes — the picker when others remain, Auth when this was
                // the last one.
                onSignedOut = { othersRemain ->
                    deleteTarget = null
                    if (othersRemain) nav.replaceAll(AccountPickerScreen()) else nav.replaceAll(AuthScreen())
                },
            )
        }
    }
}

/** The text under the audio setting, word for word as docs/chino playback
 *  ("The audio setting") gives it for every app. */
internal const val AUDIO_SETTING_TEXT =
    "Titles play with audio in the language you pick here, where they have it. " +
        "With Original, each title plays in the language it was made in. " +
        "A choice in the player applies only to what's playing now."

/** The text under the subtitle setting, word for word as docs/chino
 *  subtitles ("The setting") gives it for every app. */
internal const val SUBTITLE_SETTING_TEXT =
    "Subtitles in the language you pick here come on when a title's audio is in another language. " +
        "Otherwise, only the foreign-language parts get subtitles, where a title has them. " +
        "A choice in the player applies only to what's playing now."

/** Holder for the pre-captured screenshot while the dialog is open. Plain
 *  class on purpose — a data class would lint on ByteArray equals/hashCode. */
private class BugReportDraft(val screenshot: ByteArray?)

/** A section's title: the web's Section h2, in the heading face. */
@Composable
internal fun SectionHeading(text: String) {
    Text(
        text = text,
        color = Color.White,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        style = ChinoHeading,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    )
}

/** A row that opens or does something: its label, a line under it, and
 *  the chevron icon every screen draws. */
@Composable
internal fun ActionRow(
    label: String,
    subtitle: String,
    /** A row that deletes something: its label in the destructive red. */
    destructive: Boolean = false,
    /** False while its action runs: a second tap does nothing. */
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RectangleShape)
            .background(ChinoSurface)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, color = if (destructive) ChinoRed else Color.White, fontSize = 14.sp)
            Text(text = subtitle, color = ChinoMuted, fontSize = 12.sp)
        }
        // The chevron every other screen draws, an icon - not a typed ">".
        Icon(imageVector = Lucide.ChevronRight, contentDescription = null, tint = ChinoMuted, modifier = Modifier.size(20.dp))
    }
}

@Composable
internal fun ToggleRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RectangleShape)
            .background(ChinoSurface)
            .clickable { onChange(!value) }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
        TogglePill(value = value, onChange = onChange)
    }
}

/**
 * A language setting, one control whatever the server says (docs/chino: a
 * menu of one choice, as on the TV): a field with the language set ("off",
 * "Original", "German") that opens its menu under it, inside the page - as
 * wide as the field, never over the top bar - [choices] by name in their
 * order (LanguageSettings.kt: the person's own first where every language is
 * offered), the one set in the accent blue behind a small filled square.
 * Picking one writes the preference and closes the menu; a screen reader
 * hears the field as a dropdown with what is set, and each row as a radio
 * button, picked or not. A long menu scrolls within its height. Opened low
 * on the page, the page scrolls so the menu shows whole, above the bottom
 * bar, never under it.
 */
@Composable
internal fun LanguageSetting(
    /** What the menu is for, to a screen reader: "subtitle language". */
    name: String,
    value: String,
    choices: List<LanguageChoice>,
    onSelect: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val picked = choices.firstOrNull { isChosen(value, it.code) } ?: choices.first()
    // The open menu scrolled into the page's view, once it is laid out.
    val menuInView = remember { BringIntoViewRequester() }
    LaunchedEffect(open) {
        if (!open) return@LaunchedEffect
        withFrameNanos { }
        menuInView.bringIntoView()
    }
    Column(modifier = Modifier.fillMaxWidth().clip(RectangleShape).background(ChinoSurface)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(role = Role.DropdownList, onClick = { open = !open })
                .semantics {
                    contentDescription = name
                    stateDescription = picked.name
                }
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = picked.name, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Icon(
                imageVector = if (open) Lucide.ChevronUp else Lucide.ChevronDown,
                contentDescription = null,
                tint = ChinoMuted,
                modifier = Modifier.size(18.dp),
            )
        }
        if (open) {
            val scroll = rememberScrollState()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .bringIntoViewRequester(menuInView)
                    .border(BorderStroke(1.dp, ChinoBorder), RectangleShape)
                    .heightIn(max = LANGUAGE_MENU_MAX_HEIGHT)
                    .verticalScroll(scroll)
                    .selectableGroup()
                    .padding(vertical = 4.dp),
            ) {
                choices.forEach { choice ->
                    val on = choice === picked
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(selected = on, role = Role.RadioButton) {
                                open = false
                                onSelect(choice.code)
                            }
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        // The picked row's square; every row keeps its column.
                        Box(modifier = Modifier.size(6.dp).background(if (on) ChinoCloudBlue else Color.Transparent))
                        Text(
                            text = choice.name,
                            color = if (on) ChinoCloudBlue else Color.White,
                            fontSize = 14.sp,
                            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
    }
}

/** How tall a language setting's open menu is at most: a long one - every
 *  language - scrolls within it. */
private val LANGUAGE_MENU_MAX_HEIGHT = 336.dp

/** Pill toggle. 40×22 with a 16×16 knob that shifts left/right. Used by
 *  the settings ToggleRows (the bug-report dialog, now in ui/feedback,
 *  carries its own per-file copy — same idiom as the DialogButtons). */
@Composable
private fun TogglePill(value: Boolean, onChange: (Boolean) -> Unit) {
    Box(
        modifier = Modifier
            .width(40.dp)
            .height(22.dp)
            .clip(RectangleShape)
            // The design system's switch: on, the blue track with the dark
            // knob; off, the dark track, its edge, the dim knob.
            .background(if (value) ChinoCloudBlue else ChinoBg2)
            .border(1.dp, if (value) ChinoCloudBlue else ChinoBorderControl, RectangleShape)
            .clickable { onChange(!value) },
        contentAlignment = if (value) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        // Inside the track's 1dp edge and 2dp of room, as the design
        // system's switch has it.
        Box(
            modifier = Modifier
                .padding(3.dp)
                .size(16.dp)
                .clip(RectangleShape)
                .background(if (value) ChinoOnFill else ChinoDim),
        )
    }
}

