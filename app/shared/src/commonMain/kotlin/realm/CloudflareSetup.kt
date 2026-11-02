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

    /** A newer worker this app can deploy over the server you're signed in to, once one is known. */
    var update by mutableStateOf<ServerUpdate?>(null)
        private set

    private val platform get() = home.platform
    private var job: Job? = null
    private val updateChecks = UpdateChecks(home.platform.prefs, { latestRelease(engine) })

    /** The tag of the newest release seen, like v1.2.0, kept between runs. */
    var knownRelease by mutableStateOf(updateChecks.knownTag)
        private set

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
                home.onboarding.start(r.login, listOf(r.account))
            }

            is SeveralAccounts -> Snapshot.withMutableSnapshot {
                login = null
                home.onboarding.start(r.login, r.accounts)
            }
        }
        before?.let { scope.launch { Cloudflare.revoke(it.login) } }
    }

    /** Opens the plan for [update], with the Cloudflare login the server is signed in with. */
    fun startUpdate() {
        val session = home.session ?: return
        val id = session.login.account ?: return
        start {
            login = Login.Planning
            login = try {
                val release = latestRelease(engine)
                val plan = CloudflareApi(session, engine).use { it.planUpdate(it.account(id), release) }
                if (plan is Plan.Fresh) throw ApiException("There's no realm worker on that account any more.")
                Login.Confirm(session, plan, Choices(), null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logFailure("Working out an update on $id", e)
                Login.Failed(explain(e))
            }
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
            login = Login.Deploying(plan, emptyList())
            CloudflareApi(session, engine).use { cf ->
                val steps = mutableListOf<Step>()
                val done = try {
                    cf.deploy(plan, choices) {
                        steps += it
                        login = Login.Deploying(plan, steps.toList())
                    }
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
                        steps,
                        session.login,
                        platform.name,
                        home::sessionFor,
                        progress = { login = Login.Deploying(plan, it) },
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
            is Landed.Updated -> {
                login = null
                update = null
                home.message = "Your server runs realm ${landed.plan.release.manifest.version} now."
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

    internal fun checkForUpdate(signedIn: Phase.SignedIn, info: Info, force: Boolean) {
        if (!updateChecks.due(force)) return
        signedIn.scope.launch {
            try {
                val newer = updateChecks.newerFor(info, force)
                Snapshot.withMutableSnapshot {
                    update = newer?.let { ServerUpdate(info.version, it, canDeploy = signedIn.session?.login?.account != null) }
                    knownRelease = updateChecks.knownTag
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logFailure("Checking for a newer worker", e)
            }
        }
    }

    internal fun signedOut() {
        update = null
        updateChecks.reset()
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
