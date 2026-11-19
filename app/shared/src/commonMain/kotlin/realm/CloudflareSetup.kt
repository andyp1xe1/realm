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
import kotlinx.coroutines.launch

class CloudflareSetup internal constructor(
    private val home: Home,
    private val scope: CoroutineScope,
    private val engine: HttpClientEngine?,
) {
    /** Where logging in or deploying has got to; null when neither is under way. */
    var login by mutableStateOf<Login?>(null)
        private set

    private val platform get() = home.platform
    private var job: Job? = null

    /**
     * Logs in with Cloudflare in the browser. One account signs in to its worker, or opens onboarding to deploy
     * one; several are kept to choose from. Ends the login kept before.
     */
    fun logIn() = start {
        login = Login.LoggingIn
        val before = home.onboarding.session
        val r = logInWithCloudflare(platform, platform.name, home::sessionFor, engine)
        when (r) {
            is SignedIn -> signedIn(r)

            is NoWorker -> Snapshot.withMutableSnapshot {
                login = null
                home.chooseAccount(r.login, listOf(r.account))
            }

            is SeveralAccounts -> Snapshot.withMutableSnapshot {
                login = null
                home.chooseAccount(r.login, r.accounts)
            }
        }
        before?.let { scope.launch { it.revoke() } }
    }

    /**
     * Logs in with Cloudflare in the browser to manage the server you're signed in to, then goes on to [then]. The
     * sign-in you have stays until the login is shown to run that server.
     */
    fun manage(then: (() -> Unit)? = null) {
        val origin = home.server?.base?.removeSuffix("/api") ?: return
        start {
            login = Login.LoggingIn
            val s = manageWithCloudflare(platform, origin, platform.name, home::sessionFor, engine)
            Snapshot.withMutableSnapshot {
                login = null
                home.managedWith(s)
            }
            then?.invoke()
        }
    }

    /**
     * Opens the plan for updating the server to the newest release this app can deploy, with the Cloudflare login it's
     * signed in with. When [asked] already, a plan with nothing to decide runs at once.
     */
    fun startUpdate(asked: Boolean = false) {
        if (home.session?.login?.account == null) return
        start {
            login = Login.Planning
            val planned = planned()
            login = planned
            if (asked && planned is Login.Confirm && planned.decided) deploy(planned.choices)
        }
    }

    private suspend fun planned(): Login? {
        val p = home.phase as? Phase.SignedIn ?: return null
        val session = p.session ?: return null
        val id = session.login.account ?: return null
        val origin = p.server.base.removeSuffix("/api")
        return try {
            val release = home.releases.get(force = false).worker(engine)
            val plan = CloudflareApi(session, engine).use { cf ->
                val account = cf.account(id)
                if (origin !in cf.addresses(account).origins) throw NotOnThisAccount(origin.removePrefix("https://"))
                cf.planUpdate(account, release)
            }
            if (plan is Plan.Fresh) throw ApiException("There's no realm worker on that account any more.")
            (plan.found.script as? Existing.Answered)?.let { a -> p.facts.update { it.copy(info = a.info) } }
            Login.Confirm(session, plan, Choices(), null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logFailure("Working out an update on $id", e)
            Login.Failed(explain(e))
        }
    }

    /** Deploys the update being confirmed; a failure says why in the plan. */
    fun deploy(choices: Choices) {
        val confirm = login as? Login.Confirm ?: return
        val plan = confirm.plan as? Plan.Ready ?: return
        if (!confirm.canDeploy) return
        run(confirm.session, plan, choices) { e -> login = Login.Confirm(confirm.session, plan, choices, e) }
    }

    internal fun deployFresh(session: CloudflareSession, deployment: Deployment, failed: (String) -> Unit) =
        run(session, deployment.plan, deployment.choices) { e ->
            login = null
            failed(e.message ?: "Deploying stopped")
        }

    private fun run(session: CloudflareSession, plan: Plan.Ready, choices: Choices, failed: (ApiException) -> Unit) {
        start(failed) {
            var deploying = Login.Deploying(plan, plan.steps(choices), 0)
            login = deploying
            val on: (Step) -> Unit = {
                deploying = deploying.at(it)
                login = deploying
            }
            CloudflareApi(session, engine).use { cf ->
                val done = try {
                    cf.deploy(plan, choices, on)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logFailure("Deploying to ${plan.found.account.id}", e)
                    Snapshot.withMutableSnapshot { failed(e as? ApiException ?: ApiException(explain(e))) }
                    return@start
                }
                finish(
                    cf.land(
                        plan,
                        done,
                        session.login,
                        platform.name,
                        home::sessionFor,
                        progress = on,
                        agrees = { q ->
                            val answer = CompletableDeferred<Boolean>()
                            login = Login.Replace(plan, q, answer)
                            answer.await()
                        },
                    ),
                )
            }
        }
    }

    private fun finish(landed: Landed) {
        when (landed) {
            is Landed.Updated -> Snapshot.withMutableSnapshot {
                login = null
                (home.phase as? Phase.SignedIn)?.facts?.update { it.copy(info = landed.info) }
                home.message = "Your server runs ${landed.plan.release.tag} now."
            }

            is Landed.Uploaded -> Snapshot.withMutableSnapshot {
                login = null
                home.message = "${landed.plan.release.tag} is uploaded, but your server doesn't run it yet. Check again in a minute."
            }

            is Landed.NotAnswering -> Snapshot.withMutableSnapshot {
                login = null
                home.onboarding.comingUp(landed.connection)
            }

            is Landed.SignedInTo -> signedIn(landed.signedIn)
        }
    }

    private fun signedIn(s: SignedIn) = Snapshot.withMutableSnapshot {
        login = null
        home.signedIn(s)
    }

    /** Puts the sheet away; a deploy under way runs to the end. */
    fun dismiss() {
        when (login) {
            is Login.Deploying, is Login.Replace, null -> {}

            else -> {
                job?.cancel()
                login = null
            }
        }
    }

    private fun start(failed: ((ApiException) -> Unit)? = null, block: suspend () -> Unit) {
        val previous = job
        job = scope.launch {
            previous?.cancelAndJoin()
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logFailure("Logging in with Cloudflare", e)
                Snapshot.withMutableSnapshot {
                    login = null
                    if (failed != null) failed(e as? ApiException ?: ApiException(explain(e))) else home.message = explain(e)
                }
            }
        }
    }
}
