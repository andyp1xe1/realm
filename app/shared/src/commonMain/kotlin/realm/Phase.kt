package realm

/** What the app is signed in to, as one value that changes whole. */
sealed interface Phase {
    /** Nothing of your own: setup, or share links only. */
    data object Setup : Phase

    /** Logged in with Cloudflare with no realm worker yet. */
    data class Onboarding(val at: realm.Onboarding.State) : Phase

    /** Signed in to [server]; [session] is the Cloudflare login it was signed in with, if it was, and [facts] what settings shows of it. */
    class SignedIn(val server: Api, val session: CloudflareSession?, val facts: Fetched<ServerFacts>) : Phase
}

enum class Screen {
    /** Nothing signed in or connected. */
    Setup,

    /** Logged in with Cloudflare but not signed in to a realm. */
    Onboarding,

    Tabs,
}

val Home.screen: Screen
    get() = when (phase) {
        is Phase.SignedIn -> Screen.Tabs
        is Phase.Onboarding -> Screen.Onboarding
        Phase.Setup -> if (shared.isNotEmpty()) Screen.Tabs else Screen.Setup
    }
