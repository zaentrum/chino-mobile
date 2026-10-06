package cloud.nalet.chino.mobile.ui.player

import cloud.nalet.chino.mobile.data.auth.StreamTokenManager

/*
 * The stream token a playback session builds its links with. They are built
 * once, as the title or extra starts - its master, its sidecar subtitles, its
 * scrub previews - and asked for until it closes, so the token has to outlive
 * the session: what is left to play, and half an hour more.
 */

/** Added to what is left to play: pauses, seeks back, a slow start. */
const val SESSION_MARGIN_MS = 30L * 60 * 1000

/** What is taken to be left to play when the title's length is not known. */
const val UNKNOWN_RUNTIME_MS = 4L * 60 * 60 * 1000

/**
 * How long a session's links must stay signed: what is left of [durationMs]
 * from [startMs], and [SESSION_MARGIN_MS]. Without a length,
 * [UNKNOWN_RUNTIME_MS] is taken to be left.
 */
fun sessionLifeMs(durationMs: Long?, startMs: Long): Long {
    val left = durationMs?.takeIf { it > 0 }?.let { (it - startMs).coerceAtLeast(0L) } ?: UNKNOWN_RUNTIME_MS
    return left + SESSION_MARGIN_MS
}

/** The token a session of a title [durationMs] long, started at [startMs],
 *  builds its links with ([StreamTokenManager.validFor]): the current one
 *  when it lives as long as [sessionLifeMs], else a new one, minted first. */
suspend fun StreamTokenManager.forSession(durationMs: Long?, startMs: Long): String =
    validFor(sessionLifeMs(durationMs, startMs))
