package realm

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SignInTest {
    @Test
    fun signsInWithATokenAndFetchesTheRealms() = runTest {
        val server = FakeServer { path ->
            when (path) {
                "/api/info" -> json(OWNER_INFO)
                "/api/realms" -> json(TWO_REALMS)
                else -> notFound()
            }
        }
        val s = signInWithToken(" realm.jo.dev/api/ ", "s3cret", "test", server.engine)

        assertEquals(listOf("Main", "Lisbon trip"), s.realms.map { it.name })
        assertEquals("owner", s.info.scope)
        assertEquals(Connection.ByToken("https://realm.jo.dev", "s3cret"), s.connection)
        assertEquals("https://realm.jo.dev/api/info", server.requests.first().url.toString())
        assertTrue(server.requests.all { it.headers[HttpHeaders.Authorization] == "Bearer s3cret" })
    }

    @Test
    fun aTokenThatIsNotTheOwnersDoesNotSignIn() = runTest {
        val server = FakeServer { path -> if (path == "/api/info") json("""{"version":3,"scope":"read"}""") else json(TWO_REALMS) }
        assertFailsWith<ApiException> { signInWithToken("https://realm.jo.dev", "t", "test", server.engine) }
    }

    @Test
    fun aWrongTokenSaysSo() = runTest {
        val server = FakeServer { json("""{"error":"bad token"}""", HttpStatusCode.Unauthorized) }
        val e = assertFailsWith<ApiException> { signInWithToken("https://realm.jo.dev", "t", "test", server.engine) }
        assertTrue("token" in e.message!!, e.message)
    }

    @Test
    fun anAddressWithNoRealmServerSaysSo() = runTest {
        val server = FakeServer { bare404() }
        val e = assertFailsWith<ApiException> { signInWithToken("https://realm.jo.dev", "t", "test", server.engine) }
        assertTrue("no realm server" in e.message!!, e.message)
    }

    @Test
    fun aShareLinkIsNotAServer() = runTest {
        val server = FakeServer { json(OWNER_INFO) }
        assertFailsWith<ApiException> { signInWithToken("https://realm.jo.dev/s/abc", "t", "test", server.engine) }
        assertEquals(0, server.requests.size)
    }

    @Test
    fun connectsAShareLinkWithoutAToken() = runTest {
        val server = FakeServer { path ->
            if (path == "/s/abc/info") json("""{"version":3,"scope":"drop","uses_left":2,"bytes_left":1000}""") else notFound()
        }
        val c = connectLink("realm.jo.dev/s/abc/", "test", server.engine)

        assertEquals("https://realm.jo.dev/s/abc", c.url)
        assertEquals("drop", c.info.scope)
        assertEquals(2, c.info.usesLeft)
        assertEquals(1000, c.info.bytesLeft)
        assertEquals(null, server.requests.single().headers[HttpHeaders.Authorization])
    }

    @Test
    fun aServerIsNotAShareLink() = runTest {
        assertFailsWith<ApiException> { connectLink("https://realm.jo.dev", "test", FakeServer { json(OWNER_INFO) }.engine) }
    }

    @Test
    fun tellsLinksFromServers() {
        assertTrue(isLink("realm.jo.dev/s/abc"))
        assertEquals("https://realm.jo.dev", serverOrigin("realm.jo.dev/api"))
        assertEquals("http://127.0.0.1:8787", serverOrigin("http://127.0.0.1:8787/"))
        assertEquals("https://realm.jo.dev/s/abc", linkUrl(" https://realm.jo.dev/s/abc/ "))
        assertEquals(null, linkUrl("realm.jo.dev"))
    }
}
