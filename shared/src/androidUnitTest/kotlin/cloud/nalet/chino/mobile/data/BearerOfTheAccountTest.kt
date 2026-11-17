package cloud.nalet.chino.mobile.data

import android.app.Application
import cloud.nalet.chino.mobile.AppConfig
import cloud.nalet.chino.mobile.data.auth.Account
import cloud.nalet.chino.mobile.data.auth.PlatformAccountStoreFactory
import cloud.nalet.chino.mobile.data.auth.PlatformTokenStoreFactory
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Collections
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a request made for an account goes out with: that account's own
 * token, looked up by its id and renewed with its own refresh token where it
 * has run out - never the token of whoever else is signed in - and nothing
 * while another account is the one signed in.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BearerOfTheAccountTest {
    private val app = RuntimeEnvironment.getApplication() as Application

    /** The renewals asked for, by their form. */
    private val renewals: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private val web = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path != "/token") return MockResponse().setResponseCode(404)
                val form = request.body.readUtf8()
                renewals += form
                val who = if ("refresh-ben" in form) "ben" else "anna"
                return MockResponse().setHeader("Content-Type", "application/json")
                    .setBody("""{"access_token":"tok-$who-renewed","refresh_token":"refresh-$who","expires_in":300}""")
            }
        }
        start()
    }

    @After
    fun tearDown() = web.shutdown()

    private fun account(id: String, runOut: Boolean) = Account(
        id = id, displayName = id, email = "", accessToken = "tok-$id", refreshToken = "refresh-$id",
        expiresAtEpochMillis = if (runOut) 0 else Long.MAX_VALUE, lastUsedAt = 0,
    )

    /** Anna and Ben on the phone, [active] signed in; Ben's token has run out. */
    private fun phone(active: String): AppContainer {
        runBlocking {
            PlatformServerConfigStoreFactory(app).create().save(
                ServerConfig(
                    baseUrl = web.url("/api/").toString(), issuer = web.url("/auth").toString(), clientId = "chino",
                    tokenEndpoint = web.url("/token").toString(), userinfoEndpoint = web.url("/userinfo").toString(),
                ),
            )
        }
        return AppContainer(
            buildConfig = AppConfig(flavor = AppConfig.Flavor.PROD, apiBaseUrl = "", oidcIssuer = "", oidcClientId = "chino", displayName = "Chino"),
            accountStoreFactory = PlatformAccountStoreFactory(app),
            tokenStoreFactory = PlatformTokenStoreFactory(app),
            settingsStoreFactory = PlatformSettingsStoreFactory(app),
            serverConfigStoreFactory = PlatformServerConfigStoreFactory(app),
            deviceStaticContext = emptyMap(),
        ).also { c ->
            runBlocking {
                c.accountStore.snapshotBlocking().accounts.forEach { c.accountStore.remove(it.id) }
                c.accountStore.addOrUpdate(account("anna", runOut = false), setActive = active == "anna")
                c.accountStore.addOrUpdate(account("ben", runOut = true), setActive = active == "ben")
            }
        }
    }

    @Test
    fun anAccountsTokenIsItsOwnWhoeverIsSignedIn() {
        val c = phone(active = "anna")
        // Ben's, run out, renewed with his own refresh token while Anna is
        // signed in; Anna's untouched.
        assertEquals("tok-ben-renewed", runBlocking { c.tokenManager.validAccessTokenOf("ben") })
        assertEquals(1, renewals.size)
        assertTrue(renewals.single().contains("refresh_token=refresh-ben"), renewals.toString())
        assertEquals("tok-anna", runBlocking { c.tokenManager.validAccessTokenOf("anna") })
        assertNull(runBlocking { c.tokenManager.validAccessTokenOf("carl") })
    }

    @Test
    fun aRequestForAnAccountGoesOutWithItsTokenOnlyWhileItIsSignedIn() {
        val c = phone(active = "ben")
        // Anna asked for while Ben is signed in: nothing, and nothing of
        // Ben's is renewed for her.
        assertNull(runBlocking { c.bearerWhileActive("anna") })
        assertTrue(renewals.isEmpty(), renewals.toString())
        // Ben's own, renewed.
        assertEquals("tok-ben-renewed", runBlocking { c.bearerWhileActive("ben") })
        // Anna signed in again: her own.
        runBlocking { c.accountStore.setActive("anna") }
        assertEquals("tok-anna", runBlocking { c.bearerWhileActive("anna") })
        assertNull(runBlocking { c.bearerWhileActive("ben") })
    }
}
