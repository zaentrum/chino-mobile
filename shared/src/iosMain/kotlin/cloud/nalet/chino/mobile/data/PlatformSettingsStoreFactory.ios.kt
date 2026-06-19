package cloud.nalet.chino.mobile.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.Foundation.NSUserDefaults

/**
 * NSUserDefaults-backed settings store. NSUserDefaults doesn't ship a clean
 * change-observation hook (KVO requires Objective-C and is messy via cinterop),
 * so we keep a MutableStateFlow as the source of truth in memory and treat
 * NSUserDefaults as the persistence layer. Reads on launch hydrate the flow;
 * writes update both.
 */
actual class PlatformSettingsStoreFactory(private val suiteName: String) {
    actual fun create(): SettingsStore = IosSettingsStore(NSUserDefaults(suiteName = suiteName))
}

private class IosSettingsStore(private val defaults: NSUserDefaults) : SettingsStore {
    private val state = MutableStateFlow(readSnapshot())
    override val flow: Flow<AppSettings> = state.asStateFlow()

    override suspend fun setAutoSkipIntro(v: Boolean) = update { it.copy(autoSkipIntro = v) }
    override suspend fun setAutoSkipCredits(v: Boolean) = update { it.copy(autoSkipCredits = v) }
    override suspend fun setAutoPlayNext(v: Boolean) = update { it.copy(autoPlayNext = v) }
    override suspend fun setCountdownSec(v: Int) = update { it.copy(countdownSec = v.coerceIn(1, 15)) }
    override suspend fun setPreferredAudioLang(v: String) = update { it.copy(preferredAudioLang = v) }
    override suspend fun setPreferredSubLang(v: String) = update { it.copy(preferredSubLang = v) }

    private fun update(block: (AppSettings) -> AppSettings) {
        val next = block(state.value)
        writeSnapshot(next)
        state.value = next
    }

    private fun readSnapshot(): AppSettings {
        // NSUserDefaults.boolForKey returns false for unset keys, which is
        // wrong for our defaults (most are true). Use objectForKey to detect
        // absence and fall back to the data-class default.
        fun bool(k: String, fallback: Boolean): Boolean =
            defaults.objectForKey(k)?.let { defaults.boolForKey(k) } ?: fallback
        fun int(k: String, fallback: Int): Int =
            defaults.objectForKey(k)?.let { defaults.integerForKey(k).toInt() } ?: fallback
        fun str(k: String, fallback: String): String =
            defaults.stringForKey(k) ?: fallback
        return AppSettings(
            autoSkipIntro = bool(KEY_SKIP_INTRO, true),
            autoSkipCredits = bool(KEY_SKIP_CREDITS, true),
            autoPlayNext = bool(KEY_AUTOPLAY_NEXT, true),
            countdownSec = int(KEY_COUNTDOWN_SEC, 5),
            preferredAudioLang = str(KEY_AUDIO_LANG, "eng"),
            preferredSubLang = str(KEY_SUB_LANG, "off"),
        )
    }

    private fun writeSnapshot(s: AppSettings) {
        defaults.setBool(s.autoSkipIntro, KEY_SKIP_INTRO)
        defaults.setBool(s.autoSkipCredits, KEY_SKIP_CREDITS)
        defaults.setBool(s.autoPlayNext, KEY_AUTOPLAY_NEXT)
        defaults.setInteger(s.countdownSec.toLong(), KEY_COUNTDOWN_SEC)
        defaults.setObject(s.preferredAudioLang, KEY_AUDIO_LANG)
        defaults.setObject(s.preferredSubLang, KEY_SUB_LANG)
    }

    private companion object {
        const val KEY_SKIP_INTRO = "binge_auto_skip_intro"
        const val KEY_SKIP_CREDITS = "binge_auto_skip_credits"
        const val KEY_AUTOPLAY_NEXT = "binge_auto_play_next"
        const val KEY_COUNTDOWN_SEC = "binge_countdown_sec"
        const val KEY_AUDIO_LANG = "audio_preferred_lang"
        const val KEY_SUB_LANG = "subtitles_preferred_lang"
    }
}
