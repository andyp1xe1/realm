package realm

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

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
        // A link to picked items is still a realm, only filtered to them: it's named like one.
        override val name: String
            get() = info?.realm?.name ?: url.substringAfter("://").substringBefore('/')
        override val canRead get() = error == null && info?.scope in setOf("read", "full")
        override val canWrite get() = error == null && info?.scope in setOf("drop", "full")
        override val limited get() = info?.usesLeft != null
    }
}

internal fun ownKey(id: String) = "realm:$id"
internal fun sharedKey(url: String) = "link:$url"
