package realm

enum class Screen {
    /** Nothing signed in or connected. */
    Setup,

    /** Logged in with Cloudflare but not signed in to a realm. */
    Onboarding,

    Tabs,
}

val Home.screen: Screen
    get() = when {
        server != null -> Screen.Tabs
        onboarding.session != null -> Screen.Onboarding
        shared.isNotEmpty() -> Screen.Tabs
        else -> Screen.Setup
    }
