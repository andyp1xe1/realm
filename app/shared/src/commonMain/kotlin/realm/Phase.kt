package realm

import kotlinx.coroutines.CoroutineScope

/** What the app is signed in to, as one value that changes whole. */
sealed interface Phase {
    /** Nothing chosen yet. */
    data object Setup : Phase

    /** No account: only realms shared with you. */
    data object NoAccount : Phase

    /** Logged in with Cloudflare with no realm worker yet. */
    data class Onboarding(val at: realm.Onboarding.State) : Phase

    /**
     * Signed in to [server]; [session] is the Cloudflare login it was signed in with, if it was, [facts] what
     * settings shows of it, and [scope] runs the work for it, ended with it.
     */
    class SignedIn(val server: Api, val session: CloudflareSession?, val facts: Fetched<ServerFacts>, val scope: CoroutineScope) : Phase
}

enum class Screen {
    /** Nothing chosen yet. */
    Setup,

    /** Logged in with Cloudflare but not signed in to a realm. */
    Onboarding,

    Tabs,
}

val Home.screen: Screen
    get() = when (phase) {
        is Phase.SignedIn, Phase.NoAccount -> Screen.Tabs
        is Phase.Onboarding -> Screen.Onboarding
        Phase.Setup -> Screen.Setup
    }
