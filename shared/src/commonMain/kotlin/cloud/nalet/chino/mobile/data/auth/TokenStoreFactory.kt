package cloud.nalet.chino.mobile.data.auth

/**
 * The host app passes its platform-specific TokenStore implementation through
 * here at startup (see ChinoMobileApp on Android, iOSApp on iOS). We deliberately
 * avoid `expect class TokenStore` because the constructor signatures differ
 * (Android wants a Context, iOS wants a service-name string) — an expect class
 * would force one of them to carry an awkward parameter list.
 */
expect class PlatformTokenStoreFactory {
    fun create(): TokenStore
}
