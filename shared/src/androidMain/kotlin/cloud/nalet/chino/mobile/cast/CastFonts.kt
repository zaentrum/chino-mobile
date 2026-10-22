package cloud.nalet.chino.mobile.cast

import android.content.Context
import android.graphics.Typeface
import chino_mobile.shared.generated.resources.Res
import org.jetbrains.compose.resources.ExperimentalResourceApi

/**
 * The app's body face for the bar's lines, which are Android views (Google's
 * mini controller): the Inter the Compose screens use, read from the same
 * bundled file - no second copy of it in the app. Null where it can't be read;
 * the views keep the system's face then.
 */
internal object CastFonts {
    private const val ANDROID_ASSET = "file:///android_asset/"
    private var inter: Typeface? = null
    private var tried = false

    @OptIn(ExperimentalResourceApi::class)
    fun inter(context: Context): Typeface? {
        if (tried) return inter
        tried = true
        inter = runCatching {
            val uri = Res.getUri("font/Inter-Regular.ttf")
            if (uri.startsWith(ANDROID_ASSET)) Typeface.createFromAsset(context.assets, uri.removePrefix(ANDROID_ASSET)) else null
        }.getOrNull()
        return inter
    }
}
