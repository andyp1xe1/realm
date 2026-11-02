package realm

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SigningInTest {
    @Test
    fun signingInAsksTheServerWhatItIsOnce() = runTest {
        var asked = 0
        val engine = MockEngine(
            MockEngineConfig().apply { dispatcher = StandardTestDispatcher(testScheduler) }.also {
                it.addHandler { req ->
                    val body = when (req.url.host + req.url.encodedPath) {
                        "realm.jo.dev/api/info" -> """{"version":4,"scope":"owner"}""".also { asked++ }
                        "realm.jo.dev/api/realms" -> """{"realms":[{"id":"main","name":"Main"}]}"""
                        else -> """{"items":[],"links":[]}"""
                    }
                    respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                }
            },
        )
        val home = Home(TestPlatform(MapPrefs()), this, engine)

        assertNull(home.signIn("realm.jo.dev", "t"))
        advanceUntilIdle()
        assertEquals(1, asked)
        assertEquals(listOf("Main"), home.realms.map { it.name })
    }
}
