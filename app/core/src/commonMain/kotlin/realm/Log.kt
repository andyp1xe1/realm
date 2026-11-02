package realm

import io.ktor.util.logging.KtorSimpleLogger

private val log = KtorSimpleLogger("realm")

/** Logs [e] as what stopped [doing], like "Logging in with Cloudflare". Keep secrets in [Secret], which prints none of them. */
fun logFailure(doing: String, e: Throwable) = log.warn("$doing failed: ${e.message}", e)

/** Logs that [doing] failed, saying only [why]: for an error whose message or causes would quote what it read. */
fun logFailure(doing: String, why: String) = log.warn("$doing failed: $why")
