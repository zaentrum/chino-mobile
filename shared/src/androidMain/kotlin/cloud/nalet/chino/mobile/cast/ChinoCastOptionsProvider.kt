package cloud.nalet.chino.mobile.cast

import android.content.Context
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions
import com.google.android.gms.cast.framework.media.ImageHints
import com.google.android.gms.cast.framework.media.ImagePicker
import com.google.android.gms.cast.framework.media.MediaIntentReceiver
import com.google.android.gms.cast.framework.media.NotificationOptions
import com.google.android.gms.common.images.WebImage

/**
 * The receiver the TV runs: Google's Default Media Receiver, a player that
 * needs no registration in the Cast console. The one place it is named; the
 * Cast button finds the devices that run it.
 */
const val CAST_RECEIVER_APP_ID: String = CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID

/**
 * The Cast framework's options, named in the manifest
 * (OPTIONS_PROVIDER_CLASS_NAME): the receiver; the full controls
 * ([CastExpandedControlsActivity]) the bar and the notification open; the
 * notification's and lock screen's play or pause and stop; the artwork each
 * place shows. A session the app was in when it was closed is resumed, and
 * ending one - stop casting - stops the receiver on the TV as well.
 */
class ChinoCastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions {
        val notification = NotificationOptions.Builder()
            .setActions(
                listOf(MediaIntentReceiver.ACTION_TOGGLE_PLAYBACK, MediaIntentReceiver.ACTION_STOP_CASTING),
                intArrayOf(0, 1),
            )
            .setTargetActivityClassName(CastExpandedControlsActivity::class.java.name)
            .build()
        val media = CastMediaOptions.Builder()
            .setNotificationOptions(notification)
            .setExpandedControllerActivityClassName(CastExpandedControlsActivity::class.java.name)
            .setImagePicker(CastArtworkPicker())
            .setMediaSessionEnabled(true)
            .build()
        return CastOptions.Builder()
            .setReceiverApplicationId(CAST_RECEIVER_APP_ID)
            .setCastMediaOptions(media)
            .setStopReceiverApplicationWhenEndingSession(true)
            .setResumeSavedSession(true)
            .setEnableReconnectionService(true)
            .build()
    }

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}

/** The bar's thumbnail is the poster, the full controls, the notification
 *  and the lock screen the backdrop ([pickCastImage]). */
internal class CastArtworkPicker : ImagePicker() {
    override fun onPickImage(mediaMetadata: MediaMetadata?, hints: ImageHints): WebImage? {
        val images = mediaMetadata?.images.orEmpty()
        val use = if (hints.type == IMAGE_TYPE_MINI_CONTROLLER_THUMBNAIL) CastArtworkUse.Thumbnail else CastArtworkUse.Background
        val shapes = images.map { CastImage(it.url.toString(), it.width, it.height, castImageShape(it.width, it.height)) }
        return pickCastImage(shapes, use)?.let { images[it] }
    }
}
