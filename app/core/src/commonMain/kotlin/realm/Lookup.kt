package realm

/** The realm [query] names: its id, its name, or the start of just one name, in any case; else an [ApiException]. */
fun findRealm(realms: List<Realm>, query: String): Realm {
    val q = query.lowercase()
    realms.firstOrNull { it.id == query || it.name.lowercase() == q }?.let { return it }
    val starts = realms.filter { it.name.lowercase().startsWith(q) }
    return when (starts.size) {
        1 -> starts[0]
        0 -> throw ApiException("No realm called “$query”")
        else -> throw ApiException("“$query” could be ${starts.joinToString(", ") { it.name }}")
    }
}

/** The realm item commands go to: the one in use by [id], or the first when none is chosen. */
fun currentRealm(realms: List<Realm>, id: String?): Realm? = if (id == null) realms.firstOrNull() else realms.firstOrNull { it.id == id }

/** The item [ref] names: `last` for the newest, else its id. */
suspend fun Api.resolve(ref: String): Item =
    if (ref == "last") list(limit = 1).items.firstOrNull() ?: throw ApiException("This realm is empty") else item(ref)

/** The token of the link [target] names: the token itself, or the link's URL. */
fun linkToken(target: String): String = target.trim().trimEnd('/').substringAfterLast('/')
