package realm

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/** A tile in the overview: one of your realms, or a share link you connected to. */
sealed class Space(val key: String, val api: Api) {
    abstract val name: String
    abstract val canRead: Boolean
    abstract val canWrite: Boolean

    /** Every download spends one of the link's uses, so nothing is fetched just to show it. */
    open val limited: Boolean get() = false

    class Own(val realm: Realm, api: Api) : Space(ownKey(realm.id), api) {
        override val name get() = realm.name
        override val canRead get() = true
        override val canWrite get() = true
    }

    /** [info] is null until the link has answered once; [error] is why it last didn't. */
    class Shared(val url: String, val info: Info?, val error: String?, api: Api) : Space(sharedKey(url), api) {
        override val name: String
            get() = info?.realm?.name ?: url.substringAfter("://").substringBefore('/')
        override val canRead get() = error == null && info?.scope in setOf("read", "full")
        override val canWrite get() = error == null && info?.scope in setOf("drop", "full")
        override val limited get() = info?.usesLeft != null
    }
}

internal fun ownKey(id: String) = "realm:$id"
internal fun sharedKey(url: String) = "link:$url"
