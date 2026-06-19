package cloud.nalet.chino.mobile.data

import kotlinx.coroutines.flow.Flow

/**
 * Per-device playback ergonomics — mirrors chino-androidtv's SettingsStore +
 * chino-web's lib/settings.ts binge group (intro/credits skip, auto-play
 * next, countdown). Stored locally on each device because these are touch /
 * remote behaviour preferences, not user-account state.
 */
data class AppSettings(
    val autoSkipIntro: Boolean = true,
    val autoSkipCredits: Boolean = true,
    val autoPlayNext: Boolean = true,
    val countdownSec: Int = 5,
    /** ISO 639 code (eng, deu, …) or "orig" to leave the source default. The
     *  player feeds this to ExoPlayer's preferredAudioLanguage so the closest
     *  matching audio track is auto-selected on first load. Mirrors chino-
     *  web's audio.preferredLang (default 'eng'). */
    val preferredAudioLang: String = "eng",
    /** ISO 639 code (eng, deu, …) or "off" to disable subtitle auto-pick. */
    val preferredSubLang: String = "off",
)

interface SettingsStore {
    val flow: Flow<AppSettings>
    suspend fun setAutoSkipIntro(v: Boolean)
    suspend fun setAutoSkipCredits(v: Boolean)
    suspend fun setAutoPlayNext(v: Boolean)
    suspend fun setCountdownSec(v: Int)
    suspend fun setPreferredAudioLang(v: String)
    suspend fun setPreferredSubLang(v: String)
}

expect class PlatformSettingsStoreFactory {
    fun create(): SettingsStore
}
