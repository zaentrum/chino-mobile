package cloud.nalet.chino.mobile.cast

import cloud.nalet.chino.mobile.sha256Hex

/*
 * This phone's owner id of an account on a server - the [CastTag.owner] on
 * what it casts. Made at random the first time, kept on the phone, the same
 * from then on: a session the app resumes after a restart is still its own,
 * and it writes the positions the TV plays. Another phone signed in to the
 * same account has an id of its own and leaves what this one cast alone. The
 * id names neither the account nor the server to the other senders on the
 * network, who all read it.
 */

/** Where the phone keeps its owner ids, on the device. */
interface CastOwnerStore {
    fun read(key: String): String?
    fun write(key: String, value: String)
}

/** The key an account's owner id is kept under: the server and the account,
 *  hashed - the store holds neither as it is. */
fun castOwnerStoreKey(apiBase: String, accountId: String): String =
    sha256Hex(apiBase.trimEnd('/') + "|" + accountId)

/** This phone's owner id of [accountId] on [apiBase]: the one [store] keeps,
 *  else a new one [mint] makes at random, kept from now on. */
fun castOwnerId(store: CastOwnerStore, apiBase: String, accountId: String, mint: () -> String): String {
    val key = castOwnerStoreKey(apiBase, accountId)
    store.read(key)?.takeIf { it.isNotBlank() }?.let { return it }
    return mint().also { store.write(key, it) }
}

/** Who writes the positions the TV plays: the account signed in on the
 *  phone, and the phone's owner id of it ([castOwnerId]). */
data class CastWriter(val accountId: String, val owner: String)
