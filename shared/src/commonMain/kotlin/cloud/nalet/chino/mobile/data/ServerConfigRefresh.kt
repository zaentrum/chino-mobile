package cloud.nalet.chino.mobile.data

/** What a look at the connected server's settings found. */
enum class ServerRefresh {
    /** The server says what the app has stored. */
    Unchanged,

    /** The server says something else now; it is stored, and the graph has to
     *  be rebuilt for it to apply (LocalAppRestart). */
    Changed,

    /** The server could not be asked, or none is connected: nothing changed. */
    Unknown,
}

/**
 * Reads the connected server's settings again and keeps what it says now.
 *
 * A server names the sign-in the app uses - the issuer, the client, the
 * endpoints - in /api/config, and the app stored that when it connected. A
 * server may name another later: an operator sets up a client for the app, or
 * corrects one whose tokens the server itself refused. Kept as stored, the app
 * went on signing in through the old one, and only "Change server" to the same
 * address mended it. So before a sign-in the stored settings are held against
 * the server's: [probe] is [ServerBootstrap.probe], asked for the stored
 * server's origin.
 */
suspend fun refreshServerConfig(
    store: ServerConfigStore,
    probe: suspend (String) -> BootstrapResult,
): ServerRefresh {
    val stored = store.current()?.takeIf { it.isConfigured } ?: return ServerRefresh.Unknown
    val origin = stored.baseUrl.removeSuffix("/").removeSuffix("/api")
    val now = (runCatching { probe(origin) }.getOrNull() as? BootstrapResult.Ok)?.config
        ?: return ServerRefresh.Unknown
    if (now == stored) return ServerRefresh.Unchanged
    store.save(now)
    return ServerRefresh.Changed
}
