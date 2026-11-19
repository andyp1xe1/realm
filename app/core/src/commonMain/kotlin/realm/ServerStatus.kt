package realm

/** How a server of this app's fork stands against the fork's releases and this app, from what it says it runs. */
sealed interface ServerStatus {
    /** It runs another fork, which this app neither updates nor knows the releases of. */
    data class OtherFork(val fork: String?) : ServerStatus

    /** It speaks [protocol], newer than this app's: the app needs updating, not the server. */
    data class NewerThanApp(val protocol: Int) : ServerStatus

    /** Release [to] is newer than what it runs, and this app can deploy it. */
    data class Behind(val to: Semver) : ServerStatus

    /** It runs the newest release this app can deploy, but release [newest] speaks [protocol], so it needs a newer app first. */
    data class AppFirst(val newest: Semver, val protocol: Int) : ServerStatus

    /** Nothing newer than what it runs is out. */
    data object Current : ServerStatus
}

/** Where the server that says [info] stands against [releases]; null while they aren't known, for this fork's server. */
fun serverStatus(info: Info, releases: Releases?): ServerStatus? {
    if (info.fork != Api.FORK) return ServerStatus.OtherFork(info.fork)
    if (info.version > Api.VERSION) return ServerStatus.NewerThanApp(info.version)
    if (releases == null) return null
    val target = releases.deployable
    val newest = releases.needsNewerApp
    return when {
        target != null && info.against(target.tag, target.manifest.version) < 0 -> ServerStatus.Behind(target.tag)
        newest?.worker != null -> ServerStatus.AppFirst(newest.tag, newest.worker.version)
        else -> ServerStatus.Current
    }
}

internal fun Info.against(tag: Semver, protocol: Int): Int {
    if (version > protocol) return 1
    val r = release ?: return -1
    return when {
        r.olderThan(tag) -> -1
        r.release == tag -> 0
        else -> 1
    }
}

/** A warning for a server this app speaks to only in part, as [status] says what can be done about it, or null. */
fun warning(info: Info, status: ServerStatus?): String? = when {
    info.fork != Api.FORK -> "That server runs ${info.running}. Some things may not work."
    info.version > Api.VERSION -> "That server is newer than this app. Update the app."
    info.version == Api.VERSION -> null
    status is ServerStatus.Behind -> "That server needs updating: update it in Settings."
    else -> "That server is older than this app. Some things may not work."
}
