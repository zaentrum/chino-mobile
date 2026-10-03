package cloud.nalet.chino.mobile.data.auth

import kotlin.test.Test
import kotlin.test.assertEquals

/** The redirect URI is a contract with the operator's OIDC client (README,
 *  "Sign-in"): the realm registers this exact string, so it must not drift —
 *  not per build type, not by a character. */
class OAuthRedirectTest {
    @Test
    fun theAppSignsInWithExactlyTheRegisteredRedirectUri() {
        assertEquals("cloud.nalet.chino:/oauth/callback", OAuthRedirect.URI)
    }

    @Test
    fun theSchemeIsTheUrisOwn() {
        // androidApp/build.gradle.kts's appAuthRedirectScheme must be this too.
        assertEquals("cloud.nalet.chino", OAuthRedirect.SCHEME)
        assertEquals(OAuthRedirect.SCHEME, OAuthRedirect.URI.substringBefore(':'))
    }
}
