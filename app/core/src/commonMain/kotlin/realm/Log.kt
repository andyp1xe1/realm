package realm

import io.ktor.util.logging.KtorSimpleLogger

private val log = KtorSimpleLogger("realm")

/** Logs [e] as what stopped [doing], like "Logging in with Cloudflare"; never pass tokens or secrets. */
fun logFailure(doing: String, e: Throwable) = log.warn("$doing failed: ${e.message}", e)
