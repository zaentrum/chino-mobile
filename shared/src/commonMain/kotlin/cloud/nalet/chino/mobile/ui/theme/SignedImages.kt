package cloud.nalet.chino.mobile.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import cloud.nalet.chino.mobile.data.AppContainer
import cloud.nalet.chino.mobile.data.auth.streamTokenOf
import cloud.nalet.chino.mobile.data.auth.withStreamToken
import cloud.nalet.chino.mobile.data.auth.withoutStreamToken
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.intercept.Interceptor
import coil3.request.ImageResult
import kotlin.concurrent.Volatile

/**
 * The container whose stream token signs the app's images: the image loader
 * is the process's one and outlives a server change, the container does not.
 * [SignedAppImages] keeps it current.
 */
internal object ImageSigning {
    @Volatile
    var container: AppContainer? = null
}

/**
 * Re-signs the app's own images as they are fetched: an image of the server
 * the app is on, signed with a stream token (`?stream=`), goes out with the
 * one valid now ([cloud.nalet.chino.mobile.data.auth.StreamTokenManager.valid]),
 * whatever token its link was built with. Screens build their links once - a
 * grid when it loads, and the grid lives as long as the shell - and a token
 * lives 6 h: a link older than that answered 401. The disk cache keeps them
 * without the token, so after the token turns over an image is the one
 * already on disk, not a new download. Links of other hosts, and links
 * without a token, go out as they are.
 */
internal class StreamTokenImageInterceptor(private val container: () -> AppContainer?) : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        val url = request.data as? String ?: return chain.proceed()
        val c = container() ?: return chain.proceed()
        if (streamTokenOf(url) == null || !url.startsWith(c.config.apiBaseUrl.trimEnd('/'))) return chain.proceed()
        val token = runCatching { c.streamTokenManager.valid() }.getOrNull() ?: return chain.proceed()
        val signed = request.newBuilder()
            .data(withStreamToken(url, token))
            .diskCacheKey(withoutStreamToken(url))
            .build()
        return chain.withRequest(signed).proceed()
    }
}

/** The app's images through [StreamTokenImageInterceptor], signed by
 *  [container]'s stream token. App() calls it before anything draws one. */
@Composable
fun SignedAppImages(container: AppContainer) {
    remember(container) { ImageSigning.container = container }
    setSingletonImageLoaderFactory { context ->
        ImageLoader.Builder(context)
            .components { add(StreamTokenImageInterceptor { ImageSigning.container }) }
            .build()
    }
}
