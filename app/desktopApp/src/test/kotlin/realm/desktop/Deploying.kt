// Filmstrips of logging in with Cloudflare and deploying the worker, against [FakeCloud]. Nothing real is called.
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
import kotlinx.coroutines.CoroutineScope
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
                        CloudflareSheet(home.cloudflare, Modifier.onSizeChanged { cloudflareHeight = it.height })
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
    private var cloudflareHeight = 0

    /** The Cloudflare sheet's height in pixels as each frame drew it. */
    val cloudflareHeights = mutableListOf<Int>()

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
        cloudflareHeights += cloudflareHeight
        indicated += indicators.showing
        check(!indicators.overlapping) { "frame ${drawn.size}: two steps of one part show at once" }
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

    /**
     * Checks the Cloudflare sheet kept one height through each run of frames showing [step], once the switch to
     * it, [SWITCH_FRAMES] frames, is over.
     */
    fun steady(name: String, step: String) {
        val runs = mutableListOf<MutableList<Int>>()
        for (i in drawn.indices.filter { drawn[it].first == step }) {
            if (runs.lastOrNull()?.last() == i - 1) runs.last() += i else runs += mutableListOf(i)
        }
        check(runs.isNotEmpty()) { "$name never showed $step" }
        for (run in runs) {
            val after = run.drop(SWITCH_FRAMES).map { cloudflareHeights[it] }
            println("$name $step: ${run.size} frames, then ${after.distinct().joinToString("/")}px")
            check(after.distinct().size <= 1) { "$name: the sheet changed height while showing $step: ${after.distinct()}" }
        }
    }

    private fun save(name: String, img: org.jetbrains.skia.Image) =
        File(out, "$name.png").writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)

    fun close() = scene.close()
}

/** Frames the sheet takes to switch from one step to the next: its 300ms crossfade and size change, and a little more. */
private const val SWITCH_FRAMES = 24

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
        film.steady("deploy", "Deploying")
    }
    run {
        // A worker there already: logging in signs in to it, as before, with nothing to deploy.
        val home = Home(FakePlatform(), CoroutineScope(Ui), FakeCloud(worker = """{"version":4,"scope":"owner"}""").engine)
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
        home.onboarding.zone = home.onboarding.requirements.state.value!!.zones.first { it.name == "jo.dev" }
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
        film.steady("conflict", "Deploying")
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

    cloud.worker = """{"version":5,"scope":"owner"}"""
    val refused = Film(home)
    home.cloudflare.startUpdate()
    refused.roll("refused", max = 200) { home.cloudflare.login is Login.Confirm }
    refused.roll("refused", max = 30)
    refused.close()

    val off = Home(FakePlatform(), CoroutineScope(Ui), FakeCloud(worker = """{"version":2,"scope":"owner"}""", r2 = false).engine)
    off.cloudflare.logIn()
    val r2 = Film(off)
    r2.until { off.cloudflare.update != null }
    off.cloudflare.startUpdate()
    r2.roll("update-r2-off", max = 200) { off.cloudflare.login is Login.Confirm }
    r2.roll("update-r2-off", max = 30)
    r2.close()
    val confirm = off.cloudflare.login as Login.Confirm
    check(confirm.problem == null) { "update-r2-off: R2 being off shows as a failure: ${confirm.problem}" }
    check(!confirm.canDeploy) { "update-r2-off: Update is on with R2 off" }
    println("${out.path}/update-*.png, refused-*.png, update-r2-off-*.png")
    managing()
}
