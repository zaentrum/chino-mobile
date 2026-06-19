package cloud.nalet.chino.mobile.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "chino_settings")

actual class PlatformSettingsStoreFactory(private val context: Context) {
    actual fun create(): SettingsStore = AndroidSettingsStore(context.applicationContext)
}

private class AndroidSettingsStore(context: Context) : SettingsStore {
    private val ds = context.settingsDataStore

    override val flow: Flow<AppSettings> = ds.data.map { p ->
        AppSettings(
            autoSkipIntro = p[KEY_SKIP_INTRO] ?: true,
            autoSkipCredits = p[KEY_SKIP_CREDITS] ?: true,
            autoPlayNext = p[KEY_AUTOPLAY_NEXT] ?: true,
            countdownSec = p[KEY_COUNTDOWN_SEC] ?: 5,
            preferredAudioLang = p[KEY_AUDIO_LANG] ?: "eng",
            preferredSubLang = p[KEY_SUB_LANG] ?: "off",
        )
    }

    override suspend fun setAutoSkipIntro(v: Boolean) { ds.edit { it[KEY_SKIP_INTRO] = v } }
    override suspend fun setAutoSkipCredits(v: Boolean) { ds.edit { it[KEY_SKIP_CREDITS] = v } }
    override suspend fun setAutoPlayNext(v: Boolean) { ds.edit { it[KEY_AUTOPLAY_NEXT] = v } }
    override suspend fun setCountdownSec(v: Int) { ds.edit { it[KEY_COUNTDOWN_SEC] = v.coerceIn(1, 15) } }
    override suspend fun setPreferredAudioLang(v: String) { ds.edit { it[KEY_AUDIO_LANG] = v } }
    override suspend fun setPreferredSubLang(v: String) { ds.edit { it[KEY_SUB_LANG] = v } }

    private companion object {
        val KEY_SKIP_INTRO = booleanPreferencesKey("binge_auto_skip_intro")
        val KEY_SKIP_CREDITS = booleanPreferencesKey("binge_auto_skip_credits")
        val KEY_AUTOPLAY_NEXT = booleanPreferencesKey("binge_auto_play_next")
        val KEY_COUNTDOWN_SEC = intPreferencesKey("binge_countdown_sec")
        val KEY_AUDIO_LANG = stringPreferencesKey("audio_preferred_lang")
        val KEY_SUB_LANG = stringPreferencesKey("subtitles_preferred_lang")
    }
}
