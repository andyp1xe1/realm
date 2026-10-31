// What went wrong, for whoever runs the app: Ktor's logger, which is SLF4J on the JVM, so the desktop app
// prints it to the terminal. Messages and causes here never carry tokens, OAuth codes or secret values.
package realm

import io.ktor.util.logging.KtorSimpleLogger

private val log = KtorSimpleLogger("realm")

/** Logs [e] with its causes as what stopped [doing], like "Logging in with Cloudflare", at warn. */
fun logFailure(doing: String, e: Throwable) = log.warn("$doing failed: ${e.message}", e)
