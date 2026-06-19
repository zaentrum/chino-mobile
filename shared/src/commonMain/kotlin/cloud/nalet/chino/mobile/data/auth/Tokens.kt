package cloud.nalet.chino.mobile.data.auth

data class Tokens(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAtEpochMillis: Long,
)
