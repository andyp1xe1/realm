package realm

/**
 * The server section of settings: the address the app uses and what the worker runs, and, signed in with
 * Cloudflare, what managing it shows. One value, assigned whole; null in [ServerSettings] until fetched.
 */
data class ServerPanel(
    val origin: String,
    val info: Info,
    /** The newest release seen, like v1.2.0, until checking says better; null before one was seen. */
    val release: String?,
    /** Null when signed in with a fixed token: there's no Cloudflare login to manage the worker with. */
    val managed: Managed?,
)

/** The worker as its Cloudflare login sees it: where it answers, the last update check, and what the address list is doing. */
data class Managed(val session: CloudflareSession, val addresses: Addresses, val check: Check, val task: AddressTask?)

/** Checking for a newer worker, from the button. */
sealed interface Check {
    data object NotYet : Check

    data object Checking : Check

    data class Done(val result: UpdateCheck) : Check

    data class Failed(val message: String) : Check
}

/** What's being done to the worker's addresses, one thing at a time. */
sealed interface AddressTask {
    /** The form for a domain of the account's is open. */
    data object Adding : AddressTask

    data class Attaching(val hostname: String) : AddressTask

    /** DNS records stand in the way of [conflict]'s hostname: shown, and deleted only if the user agrees. */
    data class Conflict(val conflict: DomainOutcome.Conflict) : AddressTask

    /** Taking the worker off [domain]: asked first, then [confirmed]. */
    data class Removing(val domain: Domain, val confirmed: Boolean) : AddressTask

    /** Making [origin] the address the app uses, once it answers there. */
    data class Switching(val origin: String) : AddressTask

    data class Failed(val message: String) : AddressTask
}
