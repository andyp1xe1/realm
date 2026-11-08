package realm

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ConnectionTest {
    // Written by the Go CLI: `realm login http://127.0.0.1:18765/api/ s3cret`, then `realm use main`.
    private val goToken = """
        {
          "url": "http://127.0.0.1:18765",
          "token": "s3cret",
          "realm": "main"
        }
    """.trimIndent()

    // The CLI's config struct, json.MarshalIndent'ed as it saves it.
    private val goCloudflare = """
        {
          "url": "https://realm.jo.workers.dev",
          "cloudflare": {
            "access": "acc",
            "refresh": "ref",
            "expires": 1767225600,
            "account": "a1b2"
          }
        }
    """.trimIndent()
    private val goLoginOnly = """
        {
          "url": "",
          "cloudflare": {
            "access": "acc",
            "expires": 1767225600
          }
        }
    """.trimIndent()

    @Test
    fun readsTheGoCliConfigs() {
        assertEquals(Connection.ByToken("http://127.0.0.1:18765", Secret("s3cret"), realm = "main"), Connection.decode(goToken))
        assertEquals(
            Connection.ByCloudflare("https://realm.jo.workers.dev", CloudflareLogin(Secret("acc"), Secret("ref"), 1767225600, "a1b2")),
            Connection.decode(goCloudflare),
        )
        assertEquals(Connection.LoginOnly(CloudflareLogin(Secret("acc"), expires = 1767225600)), Connection.decode(goLoginOnly))
    }

    @Test
    fun writesWhatTheGoCliWrites() {
        for (go in listOf(goToken, goCloudflare, goLoginOnly)) assertEquals(go, Connection.encode(Connection.decode(go)!!))
    }

    @Test
    fun keepsACloudflareLoginBesideAToken() {
        val c = Connection.ByToken("https://realm.jo.dev", Secret("t"), cloudflare = CloudflareLogin(Secret("acc"), expires = 1))
        assertEquals(c, Connection.decode(Connection.encode(c)))
    }

    @Test
    fun nothingSavedIsNoConnection() {
        assertNull(Connection.decode("{}"))
    }

    @Test
    fun malformedConfigsAreErrors() {
        for (
        bad in listOf(
            "{",
            """{"url": 5}""",
            """{"url": "https://realm.jo.dev"}""",
            """{"url": "https://realm.jo.dev/s/abc"}""",
            """{"token": "t"}""",
        )
        ) {
            assertFailsWith<ApiException>(bad) { Connection.decode(bad) }
        }
    }

    @Test
    fun theAppFindsItsOwnerUnderItsOldPrefsKeys() {
        val prefs = MapPrefs("server" to "https://realm.jo.dev", "token" to "t")
        assertEquals(Connection.ByToken("https://realm.jo.dev", Secret("t")), prefs.own())

        val cf = Connection.ByCloudflare("https://realm.jo.workers.dev", CloudflareLogin(Secret("acc"), Secret("ref"), 5, "a1"))
        prefs.saveOwn(cf)
        assertEquals(cf, MapPrefs(*prefs.map.toList().toTypedArray()).own())

        prefs.saveOwn(null)
        assertNull(prefs.own())
    }

    @Test
    fun anOwnerKeptUnderTheOldKeysIsMovedToOne() {
        val login = CloudflareLogin(Secret("acc"), Secret("ref"), 5, "a1")
        val prefs = MapPrefs(
            "server" to "https://realm.jo.dev",
            "token" to "t",
            "cloudflare" to Json.encodeToString(login),
            "theme" to "dark",
        )
        val owner = Connection.ByToken("https://realm.jo.dev", Secret("t"), cloudflare = login)
        assertEquals(owner, prefs.own())
        assertEquals(setOf("own", "theme"), prefs.map.keys)
        assertEquals(owner, MapPrefs(*prefs.map.toList().toTypedArray()).own())
    }

    @Test
    fun aCloudflareLoginWithNothingDeployedIsKeptAndEndsWithSigningOut() {
        val prefs = MapPrefs()
        prefs.saveOwn(Connection.ByToken("https://realm.jo.dev", Secret("t")))
        val only = Connection.LoginOnly(CloudflareLogin(Secret("acc"), Secret("ref"), 5))
        prefs.saveOwn(only)
        assertEquals(only, MapPrefs(*prefs.map.toList().toTypedArray()).own())

        prefs.saveOwn(null)
        assertNull(prefs.own())
    }
}

class MapPrefs(vararg entries: Pair<String, String?>) : Prefs {
    val map = mutableMapOf(*entries)
    override fun get(key: String) = map[key]
    override fun set(key: String, value: String?) {
        if (value == null) map.remove(key) else map[key] = value
    }
}
