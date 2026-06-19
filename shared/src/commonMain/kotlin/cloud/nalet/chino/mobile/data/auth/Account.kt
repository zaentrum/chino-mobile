package cloud.nalet.chino.mobile.data.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A single signed-in OIDC user. `id` is the Keycloak `sub` claim — stable
 * across logins for the same user so re-running the device flow on an
 * existing account updates the tokens in place rather than creating a
 * duplicate row in the picker.
 *
 * `email` is also used to derive the gravatar URL (md5 of trimmed lowercase
 * email per https://en.gravatar.com/site/implement/hash/). Empty string
 * means "no email claim" — the avatar component falls back to an
 * initial-on-coloured-circle.
 *
 * Mirrors chino-androidtv's Account so the two clients can share account
 * concepts in design discussions; the storage layer underneath is necessarily
 * platform-specific (DataStore Preferences on Android, NSUserDefaults on
 * iOS — both flagged for pre-prod hardening to EncryptedSharedPreferences /
 * Keychain respectively).
 */
@Serializable
data class Account(
    val id: String,
    val displayName: String,
    val email: String,
    val accessToken: String,
    val refreshToken: String?,
    val expiresAtEpochMillis: Long,
    val lastUsedAt: Long,
)

/** Persisted blob — public visibility because [BaseAccountStore]'s
 *  protected read/write helpers reference it, and the Android/iOS actuals
 *  live in a different file. */
@Serializable
data class AccountStoreBlob(
    @SerialName("accounts") val accounts: List<Account> = emptyList(),
    @SerialName("active_id") val activeId: String? = null,
)
