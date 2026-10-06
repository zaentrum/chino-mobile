package cloud.nalet.chino.mobile.ui.player

/**
 * What the player plays, and so what it asks the server for and tells it —
 * the same on Android and iOS: pure, so commonTest pins each mode's answers.
 *
 * A title (a movie, an episode) has the whole of it: its play info, its saved
 * position to resume from and the progress and watched mark written back —
 * what Continue Watching lists — its segments (Skip Intro, Skip Credits), its
 * scrub previews, its sidecar subtitles, the next episode (previous / next,
 * the up-next countdown and its prewarm) and the player's events.
 *
 * One of a title's extras — its trailer — plays in the same player, under the
 * same controls and menus, from its own master and nothing else
 * ([loadExtraPlayback]): chino-api keeps none of that for an extra, and
 * playing one never touches the title's place. It starts at the head, sends
 * one trailer_play, and the screen closes at its end.
 */
enum class PlayerMode(
    /** GET /items/{id}/play/info. An extra's is read off its master instead
     *  ([extraPlayInfo]). */
    val playInfo: Boolean,
    /** GET /items/{id}/progress, where playback resumes, and the position
     *  POSTed back as it plays. Without it playback starts at the head. */
    val progress: Boolean,
    /** POST /me/items/{id}/watched, in the credits or past 95 %. */
    val watched: Boolean,
    /** GET /items/{id}/segments: the skip pills and the bands on the bar. */
    val segments: Boolean,
    /** GET /items/{id}/play/trickplay/: the scrub previews. */
    val trickplay: Boolean,
    /** GET /items/{id}/subtitles: the title's sidecar subtitles. An extra's
     *  subtitles are the ones its master lists. */
    val sidecarSubtitles: Boolean,
    /** The series' episodes: previous and next, the up-next countdown, the
     *  next one's prewarm. */
    val episodes: Boolean,
    /** The player's events: retry, quality_switch, mark_watched,
     *  skip_segment, binge_prewarm, … */
    val playbackEvents: Boolean,
    /** One trailer_play, at the first playback. */
    val trailerPlay: Boolean,
    /** Playback reaching the end closes the screen. */
    val closesAtEnd: Boolean,
) {
    Title(
        playInfo = true,
        progress = true,
        watched = true,
        segments = true,
        trickplay = true,
        sidecarSubtitles = true,
        episodes = true,
        playbackEvents = true,
        trailerPlay = false,
        closesAtEnd = false,
    ),
    Extra(
        playInfo = false,
        progress = false,
        watched = false,
        segments = false,
        trickplay = false,
        sidecarSubtitles = false,
        episodes = false,
        playbackEvents = false,
        trailerPlay = true,
        closesAtEnd = true,
    ),
    ;

    companion object {
        /** The mode of a player opened on [extraId] of a title, or on the
         *  title itself (null). */
        fun of(extraId: String?): PlayerMode = if (extraId.isNullOrBlank()) Title else Extra
    }
}
