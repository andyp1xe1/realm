// Filmstrips of logging in with Cloudflare and deploying the worker, against stand-ins for Cloudflare, GitHub
// and the worker it deploys, all on one MockEngine. Nothing real is called.
package realm.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Density
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import org.jetbrains.skia.EncodedImageFormat
import realm.Choices
import realm.Home
import realm.Login
import realm.screen
import realm.ui.CloudflareSheet
import realm.ui.INDICATOR_DELAY_MILLIS
import realm.ui.Indicators
import realm.ui.LocalIndicators
import realm.ui.RealmTheme
import realm.ui.SettingsDialog
import realm.ui.SetupOrTabs
import realm.value
import java.io.File

/**
 * Jo's Cloudflare account and everything deploying talks to. [worker] is what the realm worker answers its
 * owner, null while there's none; deploying makes it version 3. [r2] off answers bucket writes as R2 does
 * when it's not turned on. notes.jo.dev has an A record in the way until it's deleted. With [accounts] 3,
 * the login also has Studio's two accounts, a2 and a3, neither with realm. [databases] other D1 databases
 * are on each account; [subdomain] is Jo's workers.dev subdomain, null for none; the upload fails while
 * [uploadFails].
 */
internal class FakeCloud(
    var worker: String? = null,
    val r2: Boolean = true,
    val accounts: Int = 1,
    var release: Int = 3,
    val databases: Int = 0,
    var subdomain: String? = "jo",
    var uploadFails: Boolean = false,
    /** How much longer than usual each read takes, as on a slow network. */
    var slowness: Long = 0,
) {
    private var record = true
    private var workersDev = true
    private var hostname = ""
    private val domains = mutableListOf<String>()
    private val gh = "github.com/andyp1xe1/realm/releases/download/v1.2.0"

    val engine = MockEngine { req ->
        val path = "${req.method.value} ${req.url.host}${req.url.encodedPath}".replace("api.cloudflare.com/client/v4", "cf")
        // Every account's routes are a1's, with what only Jo's has told apart below.
        val account = Regex("cf/accounts/(a[0-9])").find(path)?.groupValues?.get(1)
        val jo = account == null || account == "a1"
        val route = if (account == null) path else path.replace("cf/accounts/$account", "cf/accounts/a1")
        val sub = if (jo) subdomain else "studio$account"
        // Each write takes a moment, as Cloudflare's do, so every step is on screen for a few frames.
        delay(if (req.method.value == "GET") 40 + slowness else 450)
        when {
            route == "POST dash.cloudflare.com/oauth2/token" -> json("""{"access_token":"cf","refresh_token":"r","expires_in":3600}""")

            route == "GET api.github.com/repos/andyp1xe1/realm/releases" -> json(
                """[{"tag_name":"v1.2.0","assets":[{"name":"worker.json","browser_download_url":"https://$gh/worker.json"},
                    {"name":"worker.js","browser_download_url":"https://$gh/worker.js"}]}]""",
            )

            route == "GET $gh/worker.json" -> json(
                """{"version":$release,"name":"realm","main_module":"worker.js","compatibility_date":"2026-10-01",
                    "sha256":"ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                    "r2_buckets":[{"binding":"BUCKET","bucket_name":"realm"}],
                    "d1_databases":[{"binding":"DB","database_name":"realm"}],"crons":["17 4 * * *"]}""",
            )

            route == "GET $gh/worker.js" -> respond("abc")

            route == "GET cf/accounts" ->
                cf(
                    if (accounts == 1) {
                        """[{"id":"a1","name":"Jo's account"}]"""
                    } else {
                        """[{"id":"a1","name":"Jo's account"},{"id":"a2","name":"Studio"},{"id":"a3","name":"Studio"}]"""
                    },
                )

            route.endsWith("/workers/scripts") -> cf(if (worker != null && jo) """[{"id":"realm"}]""" else "[]")

            route == "GET cf/accounts/a1/workers/subdomain" ->
                sub?.let { cf("""{"subdomain":"$it"}""") }
                    ?: cfError(10007, "This account has no workers.dev subdomain", HttpStatusCode.NotFound)

            route == "PUT cf/accounts/a1/workers/subdomain" -> {
                subdomain = Regex(""""subdomain":"([^"]+)"""").find((req.body as TextContent).text)!!.groupValues[1]
                cf("""{"subdomain":"$subdomain"}""")
            }

            route == "GET cf/accounts/a1/workers/domains" -> cf(if (jo) "[${domains.joinToString(",")}]" else "[]")

            route.startsWith("DELETE cf/accounts/a1/workers/domains/") -> {
                domains.removeAll { "\"id\":\"${route.substringAfterLast('/')}\"" in it }
                cf("null")
            }

            route == "GET cf/accounts/a1/workers/scripts/realm/subdomain" -> cf("""{"enabled":$workersDev}""")

            route == "POST cf/accounts/a1/workers/scripts/realm/subdomain" -> {
                workersDev = """"enabled":true""" in (req.body as TextContent).text
                cf("""{"enabled":$workersDev}""")
            }

            route == "GET cf/accounts/a1/workers/scripts/realm/secrets" -> cf("""[{"name":"REALM_ACCOUNT"}]""")

            // A worker that's there has its storage too.
            route == "GET cf/accounts/a1/r2/buckets" ->
                if (r2) {
                    cf(
                        if (worker != null &&
                            jo
                        ) {
                            """{"buckets":[{"name":"realm"}]}"""
                        } else {
                            """{"buckets":[]}"""
                        },
                    )
                } else {
                    cfError(10042, "Please enable R2")
                }

            route == "GET cf/accounts/a1/d1/database" -> {
                val realm = if (worker != null && jo) listOf("""{"uuid":"db1","name":"realm"}""") else emptyList()
                val others = if (req.url.parameters["name"] !=
                    null
                ) {
                    emptyList()
                } else {
                    (1..databases).map { """{"uuid":"o$it","name":"notes-$it"}""" }
                }
                cf((realm + others).joinToString(",", "[", "]"))
            }

            route == "GET cf/zones" -> cf(
                if (req.url.parameters["account.id"] ==
                    "a1"
                ) {
                    """[{"id":"z1","name":"jo.dev","status":"active"}]"""
                } else {
                    "[]"
                },
            )

            route == "POST cf/accounts/a1/r2/buckets" -> if (r2) cf("""{"name":"realm"}""") else cfError(10042, "Please enable R2")

            route == "POST cf/accounts/a1/d1/database" ->
                if (databases >=
                    10
                ) {
                    cfError(7406, "You have reached the maximum number of databases")
                } else {
                    cf("""{"uuid":"db1","name":"realm"}""")
                }

            route == "PUT cf/accounts/a1/workers/scripts/realm" ->
                if (uploadFails) {
                    cfError(10021, "Uploading the script failed. Try again in a minute.", HttpStatusCode.InternalServerError)
                } else {
                    worker = """{"version":3,"scope":"owner"}"""
                    cf("""{"id":"realm"}""")
                }

            route == "PUT cf/accounts/a1/workers/domains" -> {
                hostname = Regex(""""hostname":"([^"]+)"""").find((req.body as TextContent).text)!!.groupValues[1]
                if (record) {
                    cfError(100117, "Hostname already has DNS records")
                } else {
                    val d = """{"id":"d-$hostname","hostname":"$hostname","zone_id":"z1","service":"realm"}"""
                    domains += d
                    cf(d)
                }
            }

            route == "GET cf/zones/z1/dns_records" ->
                cf(if (record) """[{"id":"r1","type":"A","name":"$hostname","content":"192.0.2.1"}]""" else "[]")

            route == "DELETE cf/zones/z1/dns_records/r1" -> {
                record = false
                cf("""{"id":"r1"}""")
            }

            " cf/" in route -> cf("{}")

            // the schedule

            route.endsWith("/api/info") -> worker?.let { json(it) } ?: json("""{"error":"not found"}""", HttpStatusCode.NotFound)

            route.endsWith("/api/realms") -> json("""{"realms":[{"id":"main","name":"Main","created":1,"count":0}]}""")

            route.endsWith("/links") -> json("""{"links":[]}""")

            else -> json("""{"items":[],"next":null}""")
        }
    }

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun MockRequestHandleScope.cf(result: String) = json("""{"success":true,"errors":[],"result":$result}""")

    private fun MockRequestHandleScope.cfError(code: Int, message: String, status: HttpStatusCode = HttpStatusCode.Conflict) =
        json("""{"success":false,"errors":[{"code":$code,"message":"$message"}],"result":null}""", status)
}

/** A scene of the app's first screen with the Cloudflare sheet over it, and frames taken as real time passes. */
internal class Film(val home: Home, settings: Boolean = false) {
    var settings by mutableStateOf(settings)
    private val indicators = Indicators()
    val scene = ImageComposeScene(400 * 2, 820 * 2, Density(2f)) {
        RealmTheme {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                CompositionLocalProvider(LocalIndicators provides indicators) {
                    Box {
                        SetupOrTabs(home)
                        if (this@Film.settings) {
                            SettingsDialog(
                                home,
                                onDismiss = { this@Film.settings = false },
                                Modifier.onSizeChanged {
                                    sheetHeight =
                                        it.height
                                },
                            )
                        }
                        CloudflareSheet(home.cloudflare)
                    }
                }
            }
        }
    }

    @Volatile private var t = 0L
    private var n = 0

    /** The clock animations run on, in ms: 16 more each frame. */
    val ms: Long get() = t / 1_000_000
    private var sheetHeight = 0

    /** The settings sheet's height in pixels as each frame drew it; 0 before it was laid out. */
    val sheetHeights = mutableListOf<Int>()

    /** What each frame drew from: the step of the sheet, and whether a server was signed in to. */
    val drawn = mutableListOf<Pair<String, Boolean>>()

    /** What each frame drew from as screen/sheet step, like Onboarding/Deploying. */
    val screens = mutableListOf<String>()

    /** Whether each frame showed a skeleton or spinner, however faintly. */
    val indicated = mutableListOf<Boolean>()

    fun frame() = scene.renderOnUi(t).also {
        t += 16_000_000L
        val step = home.cloudflare.login?.let { it::class.simpleName } ?: "-"
        drawn += step to (home.server != null)
        screens += "${home.screen}/$step"
        sheetHeights += sheetHeight
        indicated += indicators.showing
    }

    /**
     * Checks the frames from [from] on showed a skeleton or spinner only if what they waited for took longer than
     * the delay before one shows: it was there by frame [known], each frame 16ms of the clock animations run on.
     */
    fun checkIndicator(name: String, from: Int, known: Int) {
        val shown = indicated.drop(from).count { it }
        val tookMs = (known - from) * 16
        println("$name: known after ${tookMs}ms, ${indicated.size - from} frames, $shown with a skeleton or spinner")
        if (tookMs < INDICATOR_DELAY_MILLIS) check(shown == 0) { "$name: a skeleton showed for what came in ${tookMs}ms" }
        if (tookMs > INDICATOR_DELAY_MILLIS + 100) check(shown > 2) { "$name: nothing showed it coming for ${tookMs}ms" }
    }

    /** Renders frames, ~16ms of real time apart, until [done] or [max]; saves every [every]th as [name]-N. */
    fun roll(name: String, every: Int = 4, max: Int = 400, done: () -> Boolean = { false }) {
        repeat(max) {
            val img = frame()
            if (n % every == 0) save("$name-$n", img)
            n++
            if (done()) return
            Ui.pause(16)
        }
    }

    /** Lets things settle, unsaved, until [done]. */
    fun until(done: () -> Boolean) {
        repeat(600) {
            if (done()) return
            frame()
            Ui.pause(10)
        }
        error("never got there")
    }

    /** Half a second on, settled, as [name]. */
    fun still(name: String) {
        repeat(30) { frame() }
        save(name, frame())
    }

    /** A tap at [x], [y] in the 400×820 window. */
    fun tap(x: Int, y: Int) {
        val at = Offset(x * 2f, y * 2f)
        scene.sendPointerEvent(PointerEventType.Press, at)
        scene.sendPointerEvent(PointerEventType.Release, at)
    }

    /**
     * Checks what the frames drew: setup, then [steps] of the sheet in order, then signed in. Every frame is a
     * step or signed in, never neither (setup again) and never both.
     */
    fun drew(name: String, vararg steps: String) {
        val seen = drawn.map { it.first }.fold(listOf<String>()) { acc, s -> if (acc.lastOrNull() == s) acc else acc + s }
        println("$name drew: ${seen.joinToString(" → ")}")
        check(seen == listOf("-") + steps + "-") { "$name drew $seen" }
        check(
            drawn.dropWhile {
                it.first == "-"
            }.dropWhile { it.first != "-" }.all { it.second },
        ) { "$name: a frame with neither sheet nor server" }
        check(drawn.none { it.first != "-" && it.second }) { "$name: a frame with both the sheet and a server" }
    }

    /** Checks the frames drew [steps], screen/sheet step, in order and nothing else: no frame of another screen between. */
    fun went(name: String, vararg steps: String) {
        val seen = screens.fold(listOf<String>()) { acc, s -> if (acc.lastOrNull() == s) acc else acc + s }
        println("$name went: ${seen.joinToString(" → ")}")
        check(seen == steps.toList()) { "$name went $seen" }
    }

    private fun save(name: String, img: org.jetbrains.skia.Image) =
        File(out, "$name.png").writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)

    fun close() = scene.close()
}

/**
 * deploy-N: logging in finds no worker, onboarding fades in and checks what deploying needs, Deploy runs its
 * steps, and the app signs in. existing-N: logging in finds a worker and signs in to it, straight from setup
 * into realm/Main. conflict-N: the same onto a domain of the account's, whose A record is shown and replaced.
 */
internal fun deploying() {
    run {
        val home = Home(FakePlatform(), CoroutineScope(Ui), FakeCloud().engine)
        val film = Film(home)
        film.roll("deploy", max = 8)
        home.cloudflare.logIn()
        film.roll("deploy", max = 200) { home.onboarding.deployment != null }
        film.roll("deploy", max = 30)
        home.onboarding.deploy()
        film.roll("deploy", every = 6, max = 600) { home.server != null }
        film.roll("deploy", max = 40)
        film.close()
        film.went("deploy", "Setup/-", "Setup/LoggingIn", "Onboarding/-", "Onboarding/Deploying", "Tabs/-")
    }
    run {
        // A worker there already: logging in signs in to it, as before, with nothing to deploy.
        val home = Home(FakePlatform(), CoroutineScope(Ui), FakeCloud(worker = """{"version":3,"scope":"owner"}""").engine)
        val film = Film(home)
        film.roll("existing", every = 3, max = 8)
        home.cloudflare.logIn()
        film.roll("existing", every = 3, max = 300) { home.server != null }
        film.roll("existing", every = 3, max = 30)
        film.close()
        film.drew("existing", "LoggingIn")
        film.went("existing", "Setup/-", "Setup/LoggingIn", "Tabs/-")
    }
    run {
        val home = Home(FakePlatform(), CoroutineScope(Ui), FakeCloud().engine)
        val film = Film(home)
        film.frame()
        home.cloudflare.logIn()
        film.until { home.onboarding.deployment != null }
        film.roll("conflict", max = 20)
        home.onboarding.zone = home.onboarding.requirements.state.value!!.zones.single() // jo.dev, realm.jo.dev
        film.roll("conflict", max = 30)
        home.onboarding.deploy()
        film.roll("conflict", every = 6, max = 600) { home.cloudflare.login is Login.Replace }
        film.roll("conflict", max = 30)
        (home.cloudflare.login as Login.Replace).answer(agree = true)
        film.roll("conflict", every = 6, max = 600) { home.server != null }
        film.roll("conflict", max = 40)
        println("signed in to ${home.server?.base}")
        check(home.server?.base == "https://realm.jo.dev/api") { "signed in to ${home.server?.base}, not realm.jo.dev" }
        film.close()
        film.went(
            "conflict",
            "Setup/-",
            "Setup/LoggingIn",
            "Onboarding/-",
            "Onboarding/Deploying",
            "Onboarding/Replace",
            "Onboarding/Deploying",
            "Tabs/-",
        )
    }
    println("${out.path}/deploy-*.png, existing-*.png, conflict-*.png")
    onboarding()
}

/**
 * update-N: signed in with Cloudflare to a version 2 worker, the overview's settings has a dot (update-grid),
 * settings offers the update, and Update runs it. refused-N: the worker turned out to be newer meanwhile, so
 * the plan says why it stays.
 */
internal fun updating() {
    val cloud = FakeCloud(worker = """{"version":2,"scope":"owner"}""")
    val home = Home(FakePlatform(), CoroutineScope(Ui), cloud.engine)
    home.cloudflare.logIn()
    Film(home).apply {
        until { home.cloudflare.update != null }
        home.showTabs()
        still("update-grid")
        home.hideTabs()
    }.close()
    val film = Film(home, settings = true)
    film.roll("update", max = 20)
    film.tap(88, 436) // Update, in settings
    film.roll("update", max = 200) { home.cloudflare.login is Login.Confirm }
    film.roll("update", max = 30)
    home.cloudflare.deploy(Choices())
    film.roll("update", every = 6, max = 600) { home.cloudflare.update == null }
    film.roll("update", max = 20)
    film.close()

    cloud.worker = """{"version":4,"scope":"owner"}"""
    val refused = Film(home)
    home.cloudflare.startUpdate()
    refused.roll("refused", max = 200) { home.cloudflare.login is Login.Confirm }
    refused.roll("refused", max = 30)
    refused.close()
    println("${out.path}/update-*.png, refused-*.png")
    managing()
}
