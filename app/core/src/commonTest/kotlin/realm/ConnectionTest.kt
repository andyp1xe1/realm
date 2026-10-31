package realm

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
    private val goLink = """
        {
          "url": "https://realm.jo.workers.dev/s/tok123"
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
        assertEquals(Connection.ByToken("http://127.0.0.1:18765", "s3cret", realm = "main"), Connection.decode(goToken))
        assertEquals(
            Connection.ByCloudflare("https://realm.jo.workers.dev", CloudflareLogin("acc", "ref", 1767225600, "a1b2")),
            Connection.decode(goCloudflare),
        )
        assertEquals(Connection.Shared("https://realm.jo.workers.dev/s/tok123"), Connection.decode(goLink))
        assertEquals(Connection.LoginOnly(CloudflareLogin("acc", expires = 1767225600)), Connection.decode(goLoginOnly))
    }

    @Test
    fun writesWhatTheGoCliWrites() {
        for (go in listOf(goToken, goCloudflare, goLink, goLoginOnly)) assertEquals(go, Connection.encode(Connection.decode(go)!!))
    }

    @Test
    fun keepsACloudflareLoginBesideAToken() {
        val c = Connection.ByToken("https://realm.jo.dev", "t", cloudflare = CloudflareLogin("acc", expires = 1))
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
            """{"url": "https://realm.jo.dev/s/abc", "token": "t"}""",
            """{"token": "t"}""",
        )
        ) {
            assertFailsWith<ApiException>(bad) { Connection.decode(bad) }
        }
    }

    @Test
    fun theAppFindsItsOwnerUnderItsOldPrefsKeys() {
        val prefs = MapPrefs("server" to "https://realm.jo.dev", "token" to "t")
        assertEquals(Connection.ByToken("https://realm.jo.dev", "t"), prefs.owner())

        val cf = Connection.ByCloudflare("https://realm.jo.workers.dev", CloudflareLogin("acc", "ref", 5, "a1"))
        prefs.saveOwner(cf)
        assertEquals(cf, MapPrefs(*prefs.map.toList().toTypedArray()).owner())

        prefs.saveOwner(null)
        assertNull(prefs.owner())
    }
}

class MapPrefs(vararg entries: Pair<String, String?>) : Prefs {
    val map = mutableMapOf(*entries)
    override fun get(key: String) = map[key]
    override fun set(key: String, value: String?) {
        if (value == null) map.remove(key) else map[key] = value
    }
}
