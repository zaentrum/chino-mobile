package cloud.nalet.chino.mobile.feedback

/**
 * Best-effort screenshot of whatever is currently on screen, as JPEG bytes
 * (quality 80) sized for the feedback endpoint's 3 MB screenshot cap.
 *
 * Android: PixelCopy of the resumed Activity's window — note the player's
 * PlayerView renders video into a SurfaceView, so the video region may come
 * out black; that's accepted (the chrome + error state are what matter).
 * iOS: stub returning null until the native client grows a capture path.
 *
 * Returns null on ANY failure (no resumed activity, copy failure, API level
 * too old) — callers treat the screenshot as strictly optional.
 */
expect suspend fun captureScreenshot(): ByteArray?
