package cloud.nalet.chino.mobile.data.api

import io.ktor.client.engine.HttpClientEngineConfig

/** AVPlayer fetches on its own stack: nothing to share with. */
internal actual fun HttpClientEngineConfig.shareConnections() {
}
