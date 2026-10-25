package cloud.nalet.chino.mobile.data.downloads

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Whether the connected server answers. */
enum class Reach {
    /** Not asked yet. */
    Unknown,
    Reachable,

    /** It did not answer: the phone has no connection, or the server is
     *  down. The app opens on the downloads then. */
    Unreachable,
}

/** What the server's /api/config says it offers (`features`). A server
 *  from before a feature leaves it out: [downloads] is on unless the server
 *  says false, as its own default is. */
@Serializable
data class ServerFeatures(
    val downloads: Boolean = true,
    val feedback: Boolean = false,
)

@Serializable
private data class ConfigDoc(val product: String? = null, val features: ServerFeatures? = null)

private val configJson = Json { ignoreUnknownKeys = true; coerceInputValues = true }

/**
 * Whether the connected server can be reached, and what it offers. [fetch]
 * asks its unauthenticated /api/config - the one request every chino
 * server answers without a sign-in - and gives its text, or throws when no
 * answer came; [networkUp] is the platform's word on whether the phone has
 * a connection at all (null where it cannot tell), so a phone in airplane
 * mode is unreachable at once, without a request that has to time out.
 */
class ServerReach(
    private val fetch: suspend () -> String?,
    private val networkUp: () -> Boolean? = { null },
) {
    private val _state = MutableStateFlow(Reach.Unknown)
    val state: StateFlow<Reach> = _state.asStateFlow()

    private val _features = MutableStateFlow<ServerFeatures?>(null)

    /** What the server said it offers the last time it answered; null
     *  before it has. */
    val features: StateFlow<ServerFeatures?> = _features.asStateFlow()

    /** Asks the server now. */
    suspend fun check(): Reach {
        if (networkUp() == false) return set(Reach.Unreachable)
        val doc = try {
            fetch()?.let { configJson.decodeFromString(ConfigDoc.serializer(), it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (doc == null) return set(Reach.Unreachable)
        doc.features?.let { _features.value = it } ?: run { _features.value = ServerFeatures() }
        return set(Reach.Reachable)
    }

    /** What another request found: it was answered, or not. */
    fun report(answered: Boolean) {
        set(if (answered) Reach.Reachable else Reach.Unreachable)
    }

    /**
     * A request of the app's to the server had its answer (a 2xx): the
     * server can be reached, from now on - the downloads, "check needed" and
     * the bell follow at once, not only at the next look at /api/config.
     * True where it was out of reach until now: what waited for it can go.
     */
    fun requestAnswered(): Boolean {
        val was = _state.value
        if (was == Reach.Reachable) return false
        set(Reach.Reachable)
        return was == Reach.Unreachable
    }

    private fun set(r: Reach): Reach {
        _state.value = r
        return r
    }
}

/** Where the app opens. */
enum class StartScreen {
    AddServer,
    SignIn,
    AccountPicker,
    Home,

    /** The shell, on its downloads: the server cannot be reached. */
    Downloads,

    /** "Who's watching?", then the shell on its downloads: the server
     *  cannot be reached and the phone has several accounts - each sees its
     *  own downloads, and the picker needs no server. */
    AccountPickerThenDownloads,
}

/**
 * Where the app opens: without a server, where one is added; with one that
 * cannot be reached, on the account's downloads - which play without it -
 * after "Who's watching?" where the phone has several accounts. Downloads
 * are an account's: nobody signed in sees none, and the sign-in waits for
 * the server. Else as before: the sign-in, the account picker for several
 * accounts, the shell. [downloadsSupported]: the platform keeps downloads
 * (Android); without them the server's reach changes nothing.
 */
fun startScreen(
    serverConfigured: Boolean,
    accounts: Int,
    hasActiveAccount: Boolean,
    reach: Reach,
    downloadsSupported: Boolean,
): StartScreen {
    val offline = reach == Reach.Unreachable && downloadsSupported
    return when {
        !serverConfigured -> StartScreen.AddServer
        offline && accounts >= 2 -> StartScreen.AccountPickerThenDownloads
        offline && hasActiveAccount -> StartScreen.Downloads
        !hasActiveAccount -> StartScreen.SignIn
        accounts >= 2 -> StartScreen.AccountPicker
        else -> StartScreen.Home
    }
}
