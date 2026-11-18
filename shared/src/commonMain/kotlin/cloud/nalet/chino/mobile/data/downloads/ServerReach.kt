package cloud.nalet.chino.mobile.data.downloads

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerialName
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
    /** The account keeps Separate movies and shows, and a list is answered
     *  by kind (changes 8.C18 and 16.C25 of the specification); off where
     *  the server doesn't say. */
    @SerialName("watchlist_kinds") val watchlistKinds: Boolean = false,
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
 *
 * Whether the server fills lists by kind is known from the start
 * ([watchlistKinds]): [keptWatchlistKinds] is what the server said the last
 * time it answered, kept on the device with the server, and every answer is
 * kept again through [keepWatchlistKinds].
 */
class ServerReach(
    private val fetch: suspend () -> String?,
    private val networkUp: () -> Boolean? = { null },
    /** features.watchlist_kinds as the server said it the last time it
     *  answered, kept on the device with the server; null where it never
     *  did. */
    keptWatchlistKinds: Boolean?,
    /** Keeps features.watchlist_kinds as the server says it now. */
    private val keepWatchlistKinds: suspend (Boolean) -> Unit,
) {
    private val _state = MutableStateFlow(Reach.Unknown)
    val state: StateFlow<Reach> = _state.asStateFlow()

    private val _features = MutableStateFlow<ServerFeatures?>(null)

    /** What the server said it offers the last time it answered; null
     *  before it has. */
    val features: StateFlow<ServerFeatures?> = _features.asStateFlow()

    /** What is kept on the device of features.watchlist_kinds. */
    private var kept: Boolean? = keptWatchlistKinds

    private val _watchlistKinds = MutableStateFlow(keptWatchlistKinds ?: false)

    /** Whether the server keeps Separate movies and shows and fills a list
     *  by kind (features.watchlist_kinds): as it said the last time it
     *  answered - in this process, else before, as kept on the device - so
     *  known from the start, before the server answers and where it
     *  doesn't; off where it never said. */
    val watchlistKinds: StateFlow<Boolean> = _watchlistKinds.asStateFlow()

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
        val features = doc.features ?: ServerFeatures()
        _features.value = features
        _watchlistKinds.value = features.watchlistKinds
        keep(features.watchlistKinds)
        return set(Reach.Reachable)
    }

    /** [on] kept on the device where it isn't yet. The copy is a
     *  convenience: the answer stands without it. */
    private suspend fun keep(on: Boolean) {
        if (kept == on) return
        try {
            keepWatchlistKinds(on)
            kept = on
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Unit
        }
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

/** How often the shell looks at the server while it shows, the server
 *  answering or not: a server that stops is noticed within one look, and
 *  one that is back again too. */
const val SERVER_LOOK_MS = 15_000L

/**
 * Looks at the server every [everyMs] for as long as it is let run - while
 * the shell shows, the app in front: Home, Movies and Shows hear at once
 * that it stopped - the banner over what they show, the bell gone - or that
 * it answers again, wherever the person is, without a tab change.
 */
suspend fun ServerReach.keepLooking(everyMs: Long = SERVER_LOOK_MS) {
    while (true) {
        delay(everyMs)
        check()
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
 * accounts, the shell. Accounts on the phone with none of them signed in -
 * the one signed in deleted - open on the account picker too.
 * [downloadsSupported]: the platform keeps downloads (Android); without them
 * the server's reach changes nothing.
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
        accounts == 0 -> StartScreen.SignIn
        offline && (accounts >= 2 || !hasActiveAccount) -> StartScreen.AccountPickerThenDownloads
        offline -> StartScreen.Downloads
        accounts >= 2 || !hasActiveAccount -> StartScreen.AccountPicker
        else -> StartScreen.Home
    }
}
