package cloud.nalet.chino.mobile.data

import cloud.nalet.chino.mobile.data.api.AccountSettingsChange
import cloud.nalet.chino.mobile.data.api.ChinoApi
import cloud.nalet.chino.mobile.data.api.LoadFailure
import cloud.nalet.chino.mobile.data.api.loadFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Separate movies and shows of [accountId], as the switch shows it: the
 * account's choice as the server answered it, or, until it has in this
 * process, the choice last known on the device for that account - off where
 * there is none. [reading]: it is being read again.
 */
data class AccountSetting(
    val accountId: String,
    val watchlistByKind: Boolean,
    val reading: Boolean = false,
)

/** How a save of Separate movies and shows went. */
sealed interface SettingSave {
    data object Saved : SettingSave

    /** Another account was chosen after the switch showed the one it was
     *  pressed for: nothing was sent. */
    data object NotSent : SettingSave

    /** Refused, not answered, or no bearer to be had for the account - its
     *  sign-in couldn't be renewed: what was shown stays. [failure] says why
     *  by the answer's status, never by the server's words or codes. */
    data class Failed(val failure: LoadFailure) : SettingSave
}

/**
 * The choice last known on the device for each account, by its id: what the
 * server answered last, read or saved. A copy of the account's choice, never
 * a choice of the device's own.
 */
interface KnownChoices {
    fun watchlistByKind(accountId: String): Boolean?

    suspend fun keepWatchlistByKind(accountId: String, on: Boolean)

    /** [accountId]'s copy gone: the account left the device. */
    suspend fun forgetWatchlistByKind(accountId: String)
}

/** [KnownChoices] held in memory only, gone with the process. */
class MemoryKnownChoices : KnownChoices {
    private val kept = MutableStateFlow<Map<String, Boolean>>(emptyMap())

    override fun watchlistByKind(accountId: String): Boolean? = kept.value[accountId]

    override suspend fun keepWatchlistByKind(accountId: String, on: Boolean) {
        kept.update { it + (accountId to on) }
    }

    override suspend fun forgetWatchlistByKind(accountId: String) {
        kept.update { it - accountId }
    }
}

/**
 * Every list split into its movies and its shows for [accountId] (section
 * 8.8.1 of the specification): the server fills lists by kind and that
 * account's Separate movies and shows is on. Each request goes with
 * [bearer], that account's own, which is null once another account is
 * signed in; [isActive] says whether it still is, so an answer that comes
 * after a change is not drawn.
 */
class KindSplit(
    val accountId: String,
    val bearer: suspend () -> String?,
    val isActive: suspend () -> Boolean,
)

/**
 * The settings the account keeps on the server, the same on every device it
 * signs in on (change 16.C25 of the specification): here Separate movies and
 * shows. Read and written through GET and PATCH /api/v1/me/settings only;
 * the device keeps a copy of each account's last answer ([device]), shown
 * while the choice is read again and where it couldn't be.
 *
 * Everything is keyed by the account's id: [setting] is the active
 * account's alone ([activeAccountId]), so another account chosen shows its
 * own at once, never the one before's. A read goes out with the bearer of
 * the account active when it starts ([bearerOf]: that account's token, while
 * it is still the one signed in), and its answer is that account's: written
 * under its id, followed only where it is still the active one. A save goes
 * out with the bearer of the account whose choice the switch showed, and not
 * at all where another account has been chosen since.
 *
 * A save's answer is the account's choice from then on: a read that was out
 * while it was saved keeps nothing of its answer, which may be the choice
 * before. An account that has left the phone ([forget]) keeps nothing
 * of a read or a save that was still out for it.
 *
 * A bearer that can't be had for the account signed in - its sign-in
 * couldn't be renewed, or the renewal got no answer - is a read that failed,
 * or a save that failed with its cause; nothing is sent only where another
 * account is signed in.
 */
class AccountSettings(
    private val api: ChinoApi,
    /** The id of the account signed in, as the account store has it. */
    private val activeAccountId: Flow<String?>,
    /** The same, read now. */
    private val activeAccountNow: () -> String?,
    /** [accountId]'s bearer while it is the account signed in; else null. */
    private val bearerOf: suspend (accountId: String) -> String?,
    /** Where the saves run, so leaving the screen doesn't stop one. */
    private val scope: CoroutineScope,
    /** The choice last known on the device, per account. */
    private val device: KnownChoices,
) {
    /** What the server answered in this process, per account id. */
    private val answered = MutableStateFlow<Map<String, Boolean>>(emptyMap())

    /** Counts the reads and saves begun. */
    private val asks = MutableStateFlow(0L)

    /** Per account, the count at the save kept last, or at its leaving: a
     *  read begun before it keeps nothing. Under [keeps]. */
    private val readsFrom = mutableMapOf<String, Long>()

    /** Per account, the count at its leaving: a save begun before it keeps
     *  nothing. Under [keeps]. */
    private val savesFrom = mutableMapOf<String, Long>()

    /** One keep, or one leaving, at a time. */
    private val keeps = Mutex()

    private fun nextAsk(): Long = asks.updateAndGet { it + 1 }

    /** The accounts whose choice is being read, once per read. */
    private val reading = MutableStateFlow<List<String>>(emptyList())

    private fun choiceOf(accountId: String, answered: Map<String, Boolean> = this.answered.value): Boolean =
        answered[accountId] ?: device.watchlistByKind(accountId) ?: false

    private fun shown(active: String?, answered: Map<String, Boolean>, reading: List<String>): AccountSetting? =
        active?.let { AccountSetting(it, choiceOf(it, answered), reading = it in reading) }

    /** Separate movies and shows of the account signed in; null while
     *  nobody is. */
    val setting: StateFlow<AccountSetting?> = combine(answered, reading, activeAccountId) { answered, reading, active ->
        shown(active, answered, reading)
    }.stateIn(scope, SharingStarted.Eagerly, shown(activeAccountNow(), emptyMap(), emptyList()))

    /** Separate movies and shows of the account signed in now. */
    fun current(): AccountSetting? = shown(activeAccountNow(), answered.value, reading.value)

    private val _saving = MutableStateFlow<Map<String, Boolean>>(emptyMap())

    /** The saves the switch started ([press]) that are still out: the choice
     *  being saved, by the account it is saved for - one at a time for each
     *  account, any number of accounts. Held here, not by the screen: a
     *  switch drawn again after settings were left shows it. */
    val saving: StateFlow<Map<String, Boolean>> = _saving

    private val _saveFailed = MutableStateFlow<Map<String, LoadFailure>>(emptyMap())

    /** Why the last save the switch started for an account failed, by the
     *  account, until the switch is used again for it or it leaves the
     *  phone. Held here: settings left before it failed say so when they
     *  open again. */
    val saveFailed: StateFlow<Map<String, LoadFailure>> = _saveFailed

    /**
     * Reads Separate movies and shows of the account signed in again - it
     * may have been changed on another device - and keeps it for that
     * account. Meanwhile [setting] is [AccountSetting.reading]. Where the
     * read fails, the choice last known stays and is followed: it is what
     * this returns. Null where another account was chosen before the answer
     * came, or nobody is signed in: that answer is followed by no one.
     */
    suspend fun read(): AccountSetting? {
        val account = activeAccountNow() ?: return null
        val ask = nextAsk()
        reading.update { it + account }
        try {
            // No bearer to be had - another account chosen, or a sign-in
            // that couldn't be renewed - and no answer are a read that failed.
            val answer = try {
                bearerOf(account)?.let { api.accountSettings(it).watchlistByKind }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            // The account's own answer, kept under its id whoever is active -
            // unless a save was kept since the read began.
            answer?.let { keep(account, it, ask, saved = false) }
            if (activeAccountNow() != account) return null
            return AccountSetting(account, choiceOf(account))
        } finally {
            reading.update { it - account }
        }
    }

    /** Home opened: the choice of the account signed in read where it wasn't
     *  in this process yet, so that settings and the watchlist have the
     *  account's own from their first frame. */
    suspend fun warm() {
        val account = activeAccountNow() ?: return
        if (account !in answered.value) read()
    }

    /**
     * Saves Separate movies and shows as [on] for [under], the account whose
     * choice the switch showed: with its bearer, and only while it is the
     * account signed in - after a change of account nothing is sent. Kept,
     * the answer is that account's choice; refused or not answered, the
     * choice stays as it was.
     */
    suspend fun save(on: Boolean, under: String): SettingSave {
        if (activeAccountNow() != under) return SettingSave.NotSent
        val ask = nextAsk()
        val bearer = try {
            bearerOf(under)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The sign-in's renewal got no answer, or failed.
            return SettingSave.Failed(e.loadFailure(singleThing = false))
        }
        if (bearer == null) {
            // Nothing sent for an account that is no longer the one signed
            // in; for the one that still is, its sign-in can't be renewed.
            return if (activeAccountNow() != under) SettingSave.NotSent else SettingSave.Failed(LoadFailure.SignedOut)
        }
        val kept = try {
            api.changeAccountSettings(AccountSettingsChange(watchlistByKind = on), bearer)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return SettingSave.Failed(e.loadFailure(singleThing = false))
        }
        keep(under, kept.watchlistByKind, ask, saved = true)
        return SettingSave.Saved
    }

    /**
     * The switch pressed, to [on], for [under] - the account whose choice it
     * showed: [save]d on the account settings' own scope, so leaving the
     * screen stops nothing, with what it is doing held here ([saving], then
     * [saveFailed] where it failed). A press while a save for the same
     * account is out does nothing, and says so: false; one for another
     * account, chosen meanwhile, is saved beside it. A save that failed after
     * its account left the phone says nothing.
     */
    fun press(on: Boolean, under: String): Boolean {
        while (true) {
            val out = _saving.value
            if (under in out) return false
            if (_saving.compareAndSet(out, out + (under to on))) break
        }
        // The banner of a save before goes as the switch is used again.
        _saveFailed.update { it - under }
        // Where the account leaves the phone after this, its banner stays away.
        val began = asks.value
        scope.launch {
            val result = try {
                save(on, under)
            } finally {
                _saving.update { it - under }
            }
            if (result is SettingSave.Failed) {
                keeps.withLock {
                    if ((savesFrom[under] ?: 0L) <= began) _saveFailed.update { it + (under to result.failure) }
                }
            }
        }
        return true
    }

    /**
     * Section 8.8.1: for whom every list is split by kind now - the account
     * signed in, where the server fills lists by kind ([offered]) and that
     * account's Separate movies and shows is on. [fresh]: read again, as when
     * the watchlist opens - where that read fails, the choice last known is
     * followed; else the choice as it stands. Null draws every list whole, as
     * with the setting off.
     */
    suspend fun split(offered: Boolean, fresh: Boolean): KindSplit? {
        if (!offered) return null
        return splitFor((if (fresh) read() else current()) ?: return null)
    }

    /** [split] as far as is known now, asking nothing: the choice held for
     *  the account signed in - the one last known on the phone where it
     *  wasn't answered in this process. */
    fun splitNow(offered: Boolean): KindSplit? {
        if (!offered) return null
        return splitFor(current() ?: return null)
    }

    private fun splitFor(setting: AccountSetting): KindSplit? {
        if (!setting.watchlistByKind) return null
        val account = setting.accountId
        return KindSplit(
            accountId = account,
            bearer = { bearerOf(account) },
            isActive = { activeAccountNow() == account },
        )
    }

    /** [accountId] left the phone - signed out, removed from it, deleted:
     *  its choice, as answered here and as kept on the phone, goes with it.
     *  A copy that will not go does not hold the account here. */
    suspend fun forget(accountId: String) = keeps.withLock {
        val now = nextAsk()
        readsFrom[accountId] = now
        savesFrom[accountId] = now
        answered.update { it - accountId }
        _saveFailed.update { it - accountId }
        try {
            device.forgetWatchlistByKind(accountId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Unit
        }
    }

    /** [on], answered to the read or the save begun at [ask], kept as
     *  [accountId]'s - unless a save was kept for it since a read began, or
     *  it left the phone since either began. */
    private suspend fun keep(accountId: String, on: Boolean, ask: Long, saved: Boolean) = keeps.withLock {
        val from = (if (saved) savesFrom else readsFrom)[accountId] ?: 0L
        if (ask <= from) return@withLock
        answered.update { it + (accountId to on) }
        try {
            device.keepWatchlistByKind(accountId, on)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The device's copy is a convenience: the answer stands without it.
        }
        // A read still out began before this save was kept: its answer may
        // be the choice before.
        if (saved) readsFrom[accountId] = nextAsk()
    }
}
