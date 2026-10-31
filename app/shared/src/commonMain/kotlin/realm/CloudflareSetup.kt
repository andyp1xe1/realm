// Logging in with Cloudflare from the app, and deploying or updating the realm worker with that login. Every
// step is published as one whole Login for the sheet to draw; nothing is written before the user says Deploy.
package realm

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.days
import kotlin.time.TimeSource

class CloudflareSetup internal constructor(
    private val home: Home,
    private val scope: CoroutineScope,
    private val engine: HttpClientEngine?,
) {
    /** Where logging in or deploying has got to; null when neither is under way. */
    var login by mutableStateOf<Login?>(null)
        private set

    /** A newer worker this app can deploy over the server you're signed in to, once one is known. */
    var update by mutableStateOf<ServerUpdate?>(null)
        private set

    private val platform get() = home.platform
    private var job: Job? = null
    private var lastCheck: TimeSource.Monotonic.ValueTimeMark? = null

    /**
     * Logs in with Cloudflare in the browser and signs in to the realm worker on that account, as `realm login`
     * does; with no worker there yet, offers to deploy one. Tapping again starts over.
     */
    fun logIn() = start {
        login = Login.LoggingIn
        when (val r = logInWithCloudflare(platform, platform.name, ::askWhich, home::sessionFor, engine)) {
            null -> login = null

            is SignedIn -> signedIn(r)

            is NoWorker -> {
                // Not saved anywhere until it's signed in, so its renewals needn't be either.
                val session = CloudflareSession(r.login) {}
                val only = r.accounts.singleOrNull()
                if (only != null) plan(session, only, updating = false) else login = Login.PickAccount(session, r.accounts)
            }
        }
    }

    private suspend fun askWhich(found: List<Worker>): Worker? {
        val answer = CompletableDeferred<Worker?>()
        login = Login.PickWorker(found, answer)
        return answer.await().also { login = Login.LoggingIn }
    }

    /** Answers [Login.PickAccount]: works out what deploying to [account] would do. */
    fun deployTo(account: Account) {
        val picking = login as? Login.PickAccount ?: return
        start { plan(picking.session, account, updating = false) }
    }

    /** Opens the plan for [update], with the Cloudflare login the server is signed in with. */
    fun startUpdate() {
        val session = home.session ?: return
        val id = session.login.account ?: return
        start {
            login = Login.Planning(updating = true)
            val account = CloudflareApi(session, engine).use { cf -> cf.accounts().firstOrNull { it.id == id } } ?: Account(id)
            plan(session, account, updating = true)
        }
    }

    private suspend fun plan(session: CloudflareSession, account: Account, updating: Boolean) {
        login = Login.Planning(updating)
        login = try {
            val release = latestRelease(engine)
            val plan = CloudflareApi(session, engine).use { it.plan(account, release) }
            // Deploying would stop at the bucket: say so before, not after.
            Login.Confirm(session, plan, Choices(), R2NotEnabled().takeIf { !plan.found.r2Enabled })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Login.Failed(explain(e))
        }
    }

    /** Deploys the plan being confirmed, with what the user picked, and signs in to it once it answers. */
    fun deploy(choices: Choices) {
        val confirm = login as? Login.Confirm ?: return
        val plan = confirm.plan as? Plan.Ready ?: return
        start {
            val steps = mutableListOf<Step>()
            login = Login.Deploying(plan, steps.toList())
            val done = try {
                CloudflareApi(confirm.session, engine).use { cf ->
                    cf.deploy(plan, choices) {
                        steps += it
                        login = Login.Deploying(plan, steps.toList())
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                login = Login.Confirm(confirm.session, plan, choices, e as? ApiException ?: ApiException(explain(e)))
                return@start
            }
            val conflict = done.domain as? DomainOutcome.Conflict
            if (conflict != null) {
                // Up to the domain: putting it there starts again once the user has answered.
                login = Login.Replace(confirm.session, plan, done, conflict, steps.takeWhile { it !is Step.Domain }, null)
            } else {
                land(confirm.session, plan, done)
            }
        }
    }

    /** Answers [Login.Replace]: deletes the records shown and puts the worker on the domain, or keeps workers.dev. */
    fun replace(agree: Boolean) {
        val asked = login as? Login.Replace ?: return
        start {
            if (!agree) return@start land(asked.session, asked.plan, asked.deployed)
            val steps = asked.steps + Step.Domain(asked.conflict.choice.hostname)
            login = Login.Deploying(asked.plan, steps)
            try {
                CloudflareApi(asked.session, engine).use { cf ->
                    when (val out = cf.attachDomain(asked.deployed.account, asked.conflict.choice, asked.conflict)) {
                        is DomainOutcome.Conflict ->
                            login =
                                Login.Replace(asked.session, asked.plan, asked.deployed, out, asked.steps, null)

                        is DomainOutcome.Attached -> {
                            val origin = "https://${out.domain.hostname}"
                            login = Login.Deploying(asked.plan, steps + Step.Waiting(origin))
                            land(
                                asked.session,
                                asked.plan,
                                asked.deployed.copy(origin = origin, info = cf.waitForOwner(origin), domain = out),
                            )
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                login = Login.Replace(asked.session, asked.plan, asked.deployed, asked.conflict, asked.steps, ApiException(explain(e)))
            }
        }
    }

    /** A finished deploy: signed in to, as logging in would; an update needs no new sign-in. */
    private suspend fun land(session: CloudflareSession, plan: Plan.Ready, done: Deployed) {
        val login = session.login.copy(account = done.account.id)
        when {
            plan is Plan.Update -> {
                this.login = null
                update = null
                home.message = "Your server runs realm ${plan.release.manifest.version} now."
            }

            done.info == null -> Snapshot.withMutableSnapshot {
                this.login = null
                home.signedInBeforeItAnswers(Connection.ByCloudflare(done.origin, login))
                home.message = "realm is deployed. Its address may take a few minutes to reach you."
            }

            else -> signedIn(signInWithCloudflare(done.origin, login, platform.name, home::sessionFor, engine))
        }
    }

    /** The sheet goes as the server arrives: one snapshot, so no frame has neither, whichever thread draws. */
    private fun signedIn(s: SignedIn) = Snapshot.withMutableSnapshot {
        login = null
        home.signedIn(s)
    }

    /** Puts the sheet away, giving up on what it asks; a deploy under way runs to the end. */
    fun dismiss() {
        when (val l = login) {
            is Login.PickWorker -> l.pick(null)

            is Login.Deploying, is Login.Replace, null -> {}

            else -> {
                job?.cancel()
                login = null
            }
        }
    }

    /**
     * Looks for a newer worker for [server]: on every sign-in, else at most once a day. Asks GitHub only when
     * the last release it saw is a day old, and offers it only over an older worker of this app's fork.
     */
    internal fun checkForUpdate(server: Api, canDeploy: Boolean, force: Boolean) {
        if (!force && lastCheck?.let { it.elapsedNow() < 1.days } == true) return
        lastCheck = TimeSource.Monotonic.markNow()
        scope.launch {
            try {
                val info = server.info()
                val seen = home.platform.prefs.get(RELEASE)?.split(' ')
                val fresh = seen?.takeIf { !force && Cloudflare.now() - it[0].toLong() < 1.days.inWholeSeconds }
                val latest = fresh?.get(1)?.toInt() ?: latestRelease(engine).manifest.version.also {
                    home.platform.prefs.set(RELEASE, "${Cloudflare.now()} $it")
                }
                if (home.server !== server) return@launch // signed out meanwhile
                update = ServerUpdate(info.version, latest, canDeploy).takeIf { info.fork == Api.FORK && info.version < latest }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // no notice: GitHub or the server can't say, and it's asked again next time
            }
        }
    }

    internal fun signedOut() {
        update = null
        lastCheck = null
    }

    /** Runs [block] instead of whatever this was doing; what goes wrong goes to the snackbar. */
    private fun start(block: suspend () -> Unit) {
        val previous = job
        job = scope.launch {
            previous?.cancelAndJoin()
            try {
                block()
            } catch (e: CancellationException) {
                // Cancelled from here, by starting over or putting the sheet away; anything else stopped it and says why.
                if (!isActive) throw e
                login = null
                home.message = "Logging in stopped: ${e.message}"
            } catch (e: Exception) {
                login = null
                home.message = explain(e)
            }
        }
    }

    private companion object {
        /** When the newest release was last asked for, and its API version: "<unix seconds> <version>". */
        const val RELEASE = "release"
    }
}
