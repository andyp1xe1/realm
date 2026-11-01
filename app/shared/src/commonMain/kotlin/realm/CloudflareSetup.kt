// Logging in with Cloudflare from the app, and deploying or updating the realm worker with that login. Every
// step is published as one whole Login for the sheet to draw; nothing is written before the user says Deploy.
// With no worker yet, the login goes on to Onboarding, which deploys through here.
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

    /** The tag of the newest release seen, like v1.2.0, kept from run to run; null before one was. */
    var knownRelease by mutableStateOf(updateChecks.knownTag)
        private set

    /**
     * Logs in with Cloudflare in the browser, as `realm login` does. With one account, signs in to the realm
     * worker there, or, with none yet, keeps the login and opens onboarding to deploy one; with several, the
     * login is kept for the user to choose one. Logging in again, to grant more accounts, replaces the login
     * kept before, which is ended. Tapping again starts over.
     */
    fun logIn() = start {
        login = Login.LoggingIn
        val before = home.onboarding.session
        val r = logInWithCloudflare(platform, platform.name, home::sessionFor, engine)
        // Setup fades through to onboarding or the realms: no frame has neither.
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
                val plan = CloudflareApi(session, engine).use { it.plan(it.account(id), release) }
                if (plan is Plan.Fresh) throw ApiException("There's no realm worker on that account any more.")
                // Deploying would stop at the bucket: say so before, not after.
                Login.Confirm(session, plan, Choices(), R2NotEnabled().takeIf { !plan.found.r2Enabled })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logFailure("Working out an update on $id", e)
                Login.Failed(explain(e))
            }
        }
    }

    /** Deploys the update being confirmed; a failure says why in the plan, to try again. */
    fun deploy(choices: Choices) {
        val confirm = login as? Login.Confirm ?: return
        val plan = confirm.plan as? Plan.Ready ?: return
        run(confirm.session, plan, choices) { e -> login = Login.Confirm(confirm.session, plan, choices, e) }
    }

    /**
     * Deploys [deployment] from onboarding with its login, showing the steps in the sheet, and signs in to it
     * once it answers. A failure puts the sheet away and tells [failed] why; the login stays.
     */
    internal fun deployFresh(session: CloudflareSession, deployment: Deployment, failed: (String) -> Unit) =
        run(session, deployment.plan, deployment.choices) { e ->
            login = null
            failed(e.message ?: "Deploying stopped")
        }

    /** Deploys [plan] with [choices], then lands it: signed in to, or the update noted. [failed] gets why it stopped. */
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

    /** A finished deploy: signed in to, as logging in would; an update needs no new sign-in. */
    private fun finish(landed: Landed) {
        when (landed) {
            is Landed.Updated -> {
                login = null
                update = null
                home.message = "Your server runs realm ${landed.plan.release.manifest.version} now."
            }

            // Onboarding keeps the login, says the address is coming up, and signs in once it answers.
            is Landed.NotAnswering -> Snapshot.withMutableSnapshot {
                login = null
                home.onboarding.comingUp(landed.connection)
            }

            is Landed.SignedInTo -> signedIn(landed.signedIn)
        }
    }

    /** The sheet goes as the server arrives: one snapshot, so no frame has neither, whichever thread draws. */
    private fun signedIn(s: SignedIn) = Snapshot.withMutableSnapshot {
        login = null
        home.signedIn(s)
    }

    /** Puts the sheet away, giving up on what it asks; a deploy under way runs to the end. */
    fun dismiss() {
        when (login) {
            is Login.Deploying, is Login.Replace, null -> {}

            else -> {
                job?.cancel()
                login = null
            }
        }
    }

    /**
     * Looks for a newer worker for [server]: on every sign-in, else at most once a day. Offers it only over an
     * older worker of this app's fork.
     */
    internal fun checkForUpdate(server: Api, canDeploy: Boolean, force: Boolean) {
        if (!updateChecks.due(force)) return
        scope.launch {
            try {
                val info = server.info()
                val newer = updateChecks.newerFor(info, force)
                if (home.server !== server) return@launch // signed out meanwhile
                Snapshot.withMutableSnapshot {
                    update = newer?.let { ServerUpdate(info.version, it, canDeploy) }
                    knownRelease = updateChecks.knownTag
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // no notice: GitHub or the server can't say, and it's asked again next time
                logFailure("Checking for a newer worker", e)
            }
        }
    }

    internal fun signedOut() {
        update = null
        updateChecks.reset()
    }

    /**
     * Runs [block] instead of whatever this was doing; what goes wrong goes to [failed], given, or else to the
     * snackbar.
     */
    private fun start(failed: ((ApiException) -> Unit)? = null, block: suspend () -> Unit) {
        val previous = job
        job = scope.launch {
            previous?.cancelAndJoin()
            try {
                block()
            } catch (e: CancellationException) {
                // Cancelled from here, by starting over or putting the sheet away; anything else stopped it and says why.
                if (!isActive) throw e
                logFailure("Logging in with Cloudflare", e)
                login = null
                home.message = "Logging in stopped: ${e.message}"
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
