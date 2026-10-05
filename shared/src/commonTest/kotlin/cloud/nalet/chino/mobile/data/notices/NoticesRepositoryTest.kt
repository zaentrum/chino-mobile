package cloud.nalet.chino.mobile.data.notices

import cloud.nalet.chino.mobile.data.api.FakeServer
import cloud.nalet.chino.mobile.data.api.respondJson
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The notices the bell and the list share, against a fake chino-api
 * answering as its notices.go does — with the app's own client: its bearer,
 * its base URL, its check of every answer.
 */
class NoticesRepositoryTest {
    private val clock = { parseInstant("2026-10-05T08:00:00Z")!! }

    private fun noticeJson(id: String, readAt: String? = null) =
        """{"id":"$id","addon":"example","addonTitle":"Example","addonIcon":"puzzle","title":"Your title is ready",""" +
            """"body":"It is in your library now.","link":"/portal/app/example","itemId":"","createdAt":"2026-10-05T07:58:00Z",""" +
            """"readAt":${readAt?.let { "\"$it\"" } ?: "null"}}"""

    private val idA = "00000001-0000-4000-8000-00000000000a"
    private val idB = "00000001-0000-4000-8000-00000000000b"
    private val twoUnread = """{"notices":[${noticeJson(idA)},${noticeJson(idB)}],"unread":2,"available":true}"""
    private val unavailable = """{"notices":[],"unread":0,"available":false}"""

    @Test
    fun theListIsAskedForWithTheBearerAndShownWhenPortalApiAnswered() = runTest {
        val server = FakeServer(accessToken = { "tok-1" }) { respondJson(twoUnread) }
        val notices = NoticesRepository(server.api, this, clock)

        notices.refresh()

        val call = server.requests.single()
        assertEquals(HttpMethod.Get, call.method)
        assertEquals("https://media.example.com/api/v1/notices", call.url.toString())
        assertEquals("Bearer tok-1", call.headers[HttpHeaders.Authorization])
        assertEquals(listOf(idA, idB), notices.state.value.list?.notices?.map { it.id })
        assertEquals(2, notices.state.value.list?.unread)
    }

    @Test
    fun theListIsAskedForWithTheBearerOfTheAccountSignedInNow() = runTest {
        // The Auth plugin keeps the bearer it loaded first. Another account
        // signed in since (Switch account) is never shown the first one's.
        var active = "tok-first"
        val server = FakeServer(accessToken = { active }) { call ->
            if (call.url.encodedPath.endsWith("/notices")) respondJson(twoUnread) else respondJson("""{"items":[]}""")
        }
        val notices = NoticesRepository(server.api, this, clock)
        server.api.listItems()
        active = "tok-now"

        notices.refresh()

        assertEquals(
            listOf("Bearer tok-first", "Bearer tok-now"),
            server.requests.map { it.headers[HttpHeaders.Authorization] },
        )
    }

    @Test
    fun nothingIsShownWhenPortalApiDidNotAnswer() = runTest {
        var answer = unavailable
        val server = FakeServer { respondJson(answer) }
        val notices = NoticesRepository(server.api, this, clock)
        notices.refresh()
        assertNull(notices.state.value.list)

        // Shown once, then not available: the empty list says nothing of the
        // person's notices, so nothing is shown — not "no notices".
        answer = twoUnread
        notices.refresh()
        answer = unavailable
        notices.refresh()
        assertNull(notices.state.value.list)
    }

    @Test
    fun anAnswerThatDoesNotComeKeepsWhatIsShown() = runTest {
        var status = HttpStatusCode.OK
        val server = FakeServer { if (status == HttpStatusCode.OK) respondJson(twoUnread) else respond("", status) }
        val notices = NoticesRepository(server.api, this, clock)
        notices.refresh()
        val shown = notices.state.value.list

        for (failure in listOf(HttpStatusCode.Unauthorized, HttpStatusCode.NotFound, HttpStatusCode.BadGateway)) {
            status = failure
            notices.refresh()
            assertEquals(shown, notices.state.value.list, failure.toString())
        }
        // Nor does a body that is no list.
        val garbled = FakeServer { respondJson("<html>") }
        val other = NoticesRepository(garbled.api, this, clock)
        other.refresh()
        assertNull(other.state.value.list)
    }

    @Test
    fun readingShowsAtOnceThenSendsIt() = runTest {
        val server = FakeServer(accessToken = { "tok-1" }) { call ->
            if (call.method == HttpMethod.Get) respondJson(twoUnread) else respondJson("""{"unread":1}""")
        }
        val notices = NoticesRepository(server.api, this, clock)
        notices.refresh()
        val a = notices.state.value.list!!.notices.first()

        val sent = notices.read(a)

        // Read before chino-api answers.
        assertEquals("2026-10-05T08:00:00Z", notices.state.value.list?.notices?.first()?.readAt)
        assertEquals(1, notices.state.value.list?.unread)
        sent!!.join()
        val post = server.requests.last()
        assertEquals(HttpMethod.Post, post.method)
        assertEquals("https://media.example.com/api/v1/notices/$idA/read", post.url.toString())
        assertEquals("Bearer tok-1", post.headers[HttpHeaders.Authorization])
        // Reading one read already sends nothing.
        assertNull(notices.read(notices.state.value.list!!.notices.first()))
    }

    @Test
    fun readingAllAndDeletingAreSentToo() = runTest {
        val server = FakeServer { call ->
            when (call.method) {
                HttpMethod.Get -> respondJson(twoUnread)
                HttpMethod.Delete -> respond("", HttpStatusCode.NoContent)
                else -> respondJson("""{"read":2,"unread":0}""")
            }
        }
        val notices = NoticesRepository(server.api, this, clock)
        notices.refresh()

        notices.readAll()!!.join()
        assertEquals(0, notices.state.value.list?.unread)
        assertEquals("https://media.example.com/api/v1/notices/read-all", server.requests.last().url.toString())
        assertEquals(HttpMethod.Post, server.requests.last().method)

        notices.delete(notices.state.value.list!!.notices.first())!!.join()
        assertEquals(listOf(idB), notices.state.value.list?.notices?.map { it.id })
        assertEquals("https://media.example.com/api/v1/notices/$idA", server.requests.last().url.toString())
        assertEquals(HttpMethod.Delete, server.requests.last().method)
    }

    @Test
    fun aChangeChinoApiDoesNotMakeIsUndoneByAskingAgain() = runTest {
        // 502: portal-api did not change the notice; the app keeps it as it was.
        val server = FakeServer { call ->
            if (call.method == HttpMethod.Get) {
                respondJson(twoUnread)
            } else {
                respondJson("""{"error":"notices_unavailable","message":"Try again later."}""", HttpStatusCode.BadGateway)
            }
        }
        val notices = NoticesRepository(server.api, this, clock)
        notices.refresh()

        notices.delete(notices.state.value.list!!.notices.first())!!.join()

        assertEquals(listOf(idA, idB), notices.state.value.list?.notices?.map { it.id })
        assertEquals(listOf(HttpMethod.Get, HttpMethod.Delete, HttpMethod.Get), server.requests.map { it.method })
    }

    @Test
    fun anIdThatIsNoNoticeIdIsNeverSent() = runTest {
        val odd = """{"notices":[${noticeJson("../../me")}],"unread":1,"available":true}"""
        val server = FakeServer { respondJson(odd) }
        val notices = NoticesRepository(server.api, this, clock)
        notices.refresh()

        notices.delete(notices.state.value.list!!.notices.first())!!.join()

        assertEquals(listOf(HttpMethod.Get, HttpMethod.Get), server.requests.map { it.method })
    }

    @Test
    fun nothingIsChangedOrSentWhileNothingIsShown() = runTest {
        val server = FakeServer { respondJson(unavailable) }
        val notices = NoticesRepository(server.api, this, clock)
        notices.refresh()
        val stray = noticeListOf(Json.parseToJsonElement(twoUnread)).notices.first()

        assertNull(notices.read(stray))
        assertNull(notices.readAll())
        assertNull(notices.delete(stray))
        assertEquals(1, server.requests.size)
    }

    @Test
    fun anAnswerForAnAccountSignedOutSinceIsDropped() = runTest {
        val asked = CompletableDeferred<Unit>()
        val answer = CompletableDeferred<Unit>()
        val server = FakeServer {
            asked.complete(Unit)
            answer.await()
            respondJson(twoUnread)
        }
        val notices = NoticesRepository(server.api, this, clock)

        val pending = launch { notices.refresh() }
        asked.await()
        notices.clear()
        answer.complete(Unit)
        pending.join()

        assertNull(notices.state.value.list)
        // The next account's own are shown.
        notices.refresh()
        assertEquals(2, notices.state.value.list?.unread)
    }
}
