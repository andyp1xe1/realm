// Which of the app's screens is up, from what's signed in or connected.
package realm

/** Which screen the app shows. */
enum class Screen {
    /** Nothing signed in or connected: log in with Cloudflare, or connect with a token. */
    Setup,

    /** Logged in with Cloudflare but not signed in to a realm: choosing an account, or deploying to one. */
    Onboarding,

    /** Your realms and links. */
    Tabs,
}

/** The screen the app shows: your server or links, else onboarding while logged in with Cloudflare only, else setup. */
val Home.screen: Screen
    get() = when {
        server != null -> Screen.Tabs
        onboarding.session != null -> Screen.Onboarding
        shared.isNotEmpty() -> Screen.Tabs
        else -> Screen.Setup
    }
