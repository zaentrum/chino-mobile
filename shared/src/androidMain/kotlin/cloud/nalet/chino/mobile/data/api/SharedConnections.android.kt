package cloud.nalet.chino.mobile.data.api

import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.okhttp.OkHttpConfig
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient

/**
 * The process's one OkHttp connection pool: the API client's, the player's
 * and Zap's. Each takes [client]'s newBuilder() for its own timeouts - the
 * pool, TLS and DNS stay this one's - so a stream request reuses a
 * connection to the server the API calls keep open instead of opening a new
 * one: the player's first one used to cost about a second, every time it
 * opened.
 */
internal object SharedConnections {
    val client: OkHttpClient by lazy { OkHttpClient.Builder().connectionPool(ConnectionPool()).build() }
}

internal actual fun HttpClientEngineConfig.shareConnections() {
    // Its own dispatcher: Ktor's engine shuts its clients' dispatchers down
    // when it closes, and the player's must go on.
    (this as? OkHttpConfig)?.preconfigured = SharedConnections.client.newBuilder().dispatcher(Dispatcher()).build()
}
