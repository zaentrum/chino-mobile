package cloud.nalet.chino.mobile.feedback

/** Stub until the iOS host grows a capture path (UIGraphicsImageRenderer of
 *  the key window) — null means "no screenshot", which every caller treats
 *  as optional. */
actual suspend fun captureScreenshot(): ByteArray? = null
