package cloud.nalet.chino.mobile.ui.settings

import androidx.compose.ui.graphics.RectangleShape

import cloud.nalet.chino.mobile.ui.theme.ChinoBg
import cloud.nalet.chino.mobile.ui.theme.ChinoBg2
import cloud.nalet.chino.mobile.ui.theme.ChinoBorder
import cloud.nalet.chino.mobile.ui.theme.ChinoBorder2
import cloud.nalet.chino.mobile.ui.theme.ChinoCloudBlue
import cloud.nalet.chino.mobile.ui.theme.ChinoDim
import cloud.nalet.chino.mobile.ui.theme.ChinoFg
import cloud.nalet.chino.mobile.ui.theme.ChinoFg2
import cloud.nalet.chino.mobile.ui.theme.ChinoGreen
import cloud.nalet.chino.mobile.ui.theme.ChinoMuted
import cloud.nalet.chino.mobile.ui.theme.ChinoRed
import cloud.nalet.chino.mobile.ui.theme.ChinoSurface
import cloud.nalet.chino.mobile.ui.theme.ChinoSurfaceHi

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cloud.nalet.chino.mobile.LocalAppContainer
import cloud.nalet.chino.mobile.data.AppSettings
import cloud.nalet.chino.mobile.feedback.captureScreenshot
import cloud.nalet.chino.mobile.ui.auth.AccountPickerScreen
import cloud.nalet.chino.mobile.ui.auth.AuthScreen
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
            Text(
                text = "Settings",
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            SectionHeading("Account")
            // Profile entry — name/email + watch history, mirrors the TopBar avatar.
            ActionRow(
                label = "Profile",
                subtitle = activeAccount?.let { it.email.ifBlank { it.displayName } }
                    ?: "View your profile and watch history",
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
                onClick = {
                    val id = activeAccount?.id
                    if (id != null) scope.launch {
                        container.accountStore.remove(id)
                        val remaining = container.accountStore.snapshotBlocking().accounts
                        if (remaining.isEmpty()) nav.replaceAll(AuthScreen())
                        else nav.replaceAll(AccountPickerScreen())
                    }
                },
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
            Box(modifier = Modifier.padding(top = 12.dp))
            SectionHeading("Server")
            // Change-server entry — re-opens Add-Server prefilled with the current
            // server. On connect to a DIFFERENT server the screen clears existing
            // accounts (their tokens are for the old issuer) and asks the host to
            // rebuild the app graph, then routes back to sign-in. Mirrors the TV's
            // Settings "change server" intent.
            ActionRow(
                label = "Change server",
                subtitle = serverHost ?: "Connect to a different Chino server",
                onClick = { nav.push(AddServerScreen(changeServer = true)) },
            )

            Box(modifier = Modifier.padding(top = 12.dp))
            SectionHeading("Support")
            // Report a bug — capture the screenshot FIRST (the dialog would
            // only ever photograph itself), then mount the dialog with the
            // bytes. captureScreenshot is best-effort; null just means no
            // preview row + a text-only report.
            ActionRow(
                label = "Report a bug",
                subtitle = "Send a description + screenshot to the dev backlog",
                onClick = {
                    scope.launch {
                        bugReportDraft = BugReportDraft(screenshot = captureScreenshot())
                    }
                },
            )

            Box(modifier = Modifier.padding(top = 12.dp))
            // Audio sits ABOVE Subtitles (web SettingsPage order). Default
            // audio language; the player auto-picks the closest matching
            // track on first load. "Original" follows the item's source
            // language. Manual switches in the player menu only affect the
            // current playback. Mirrors chino-web's Audio section.
            SectionHeading("Audio")
            Text(
                text = "Default audio language. Picks the closest matching track on each item. " +
                    "Choose Original to follow the item's source language. Manual switches in the " +
                    "player only affect the current playback.",
                color = ChinoMuted,
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            LangPickerRow(
                value = s.preferredAudioLang,
                options = AUDIO_LANGS,
                onSelect = { code -> scope.launch { container.settings.setPreferredAudioLang(code) } },
            )

            Box(modifier = Modifier.padding(top = 12.dp))
            SectionHeading("Subtitles")
            Text(
                text = "Default subtitle language. Picks the closest matching track on each item. " +
                    "Choose Off to keep subtitles disabled by default.",
                color = ChinoMuted,
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            LangPickerRow(
                value = s.preferredSubLang,
                options = SUB_LANGS,
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
    }
}

/** Holder for the pre-captured screenshot while the dialog is open. Plain
 *  class on purpose — a data class would lint on ByteArray equals/hashCode. */
private class BugReportDraft(val screenshot: ByteArray?)

@Composable
private fun SectionHeading(text: String) {
    Text(
        text = text,
        color = Color.White,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    )
}

@Composable
private fun ActionRow(label: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RectangleShape)
            .background(ChinoSurface)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, color = Color.White, fontSize = 14.sp)
            Text(text = subtitle, color = ChinoMuted, fontSize = 12.sp)
        }
        Text(text = ">", color = ChinoMuted, fontSize = 16.sp)
    }
}

@Composable
private fun ToggleRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
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

/** Offered audio languages — mirrors chino-web's SettingsPage Audio
 *  options (Original first, then the same code/label pairs). 3-letter ISO
 *  codes match the web settings model; ExoPlayer's preferredAudioLanguage
 *  fuzzy-matches them against each item's track.language. */
private val AUDIO_LANGS = listOf(
    "orig" to "Original",
    "eng" to "English",
    "deu" to "German",
    "fra" to "French",
    "spa" to "Spanish",
    "ita" to "Italian",
    "jpn" to "Japanese",
    "por" to "Portuguese",
    "nld" to "Dutch",
)

/** Offered subtitle languages — same list as Audio but with Off in place
 *  of Original (web SettingsPage Subtitles options). */
private val SUB_LANGS = listOf(
    "off" to "Off",
    "eng" to "English",
    "deu" to "German",
    "fra" to "French",
    "spa" to "Spanish",
    "ita" to "Italian",
    "jpn" to "Japanese",
    "por" to "Portuguese",
    "nld" to "Dutch",
)

/** Wrapping chip-row language picker — same dark-card idiom as the other
 *  Settings rows. The selected code's chip fills blue; tapping a chip
 *  writes the preference. Used by both the Audio and Subtitles sections
 *  (the offered list differs: Original vs Off as the first entry). */
@Composable
private fun LangPickerRow(
    value: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RectangleShape)
            .background(ChinoSurface)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { (code, name) ->
                val on = value.equals(code, ignoreCase = true)
                Box(
                    modifier = Modifier
                        .clip(RectangleShape)
                        .background(if (on) ChinoCloudBlue else ChinoBorder)
                        .clickable { onSelect(code) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = name,
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

/** Pill toggle. 40×22 with an 18×18 thumb that shifts left/right. Used by
 *  the settings ToggleRows (the bug-report dialog, now in ui/feedback,
 *  carries its own per-file copy — same idiom as the DialogButtons). */
@Composable
private fun TogglePill(value: Boolean, onChange: (Boolean) -> Unit) {
    Box(
        modifier = Modifier
            .width(40.dp)
            .height(22.dp)
            .clip(RectangleShape)
            .background(if (value) ChinoCloudBlue else ChinoBorder)
            .clickable { onChange(!value) },
        contentAlignment = if (value) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .padding(2.dp)
                .size(18.dp)
                .clip(RectangleShape)
                .background(Color.White),
        )
    }
}

