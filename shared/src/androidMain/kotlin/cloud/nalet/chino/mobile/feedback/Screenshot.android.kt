package cloud.nalet.chino.mobile.feedback

import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * PixelCopy the resumed Activity's window into a Bitmap, then JPEG-compress
 * at quality 80. PixelCopy (API 26+) reads the composited window surface, so
 * Compose chrome comes through; the PlayerView's SurfaceView video plane may
 * come out black — accepted, the screenshot is best-effort context only.
 * Returns null on any failure: no resumed activity, pre-O device, zero-sized
 * decor (before first layout), or a non-SUCCESS copy result.
 */
actual suspend fun captureScreenshot(): ByteArray? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
    val activity = CurrentActivity.get() ?: return null
    return runCatching {
        val window = activity.window ?: return null
        val decor = window.decorView
        if (decor.width <= 0 || decor.height <= 0) return null
        val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        val copied = suspendCancellableCoroutine { cont ->
            // PixelCopy delivers its result on the supplied handler — use the
            // main looper (the window's surface is owned by the UI thread).
            PixelCopy.request(
                window,
                bitmap,
                { result -> cont.resume(result == PixelCopy.SUCCESS) },
                Handler(Looper.getMainLooper()),
            )
        }
        if (!copied) return null
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
        bitmap.recycle()
        out.toByteArray()
    }.getOrNull()
}
