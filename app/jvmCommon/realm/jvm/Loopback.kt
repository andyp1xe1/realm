// The browser's way back after an OAuth login: one request to http://localhost:<port><path>.
package realm.jvm

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlin.time.Duration.Companion.minutes

/**
 * Runs [open] (which sends the browser off to log in), then waits for it to come back to [path] on [port]
 * and returns the query parameters it brought. The browser gets a page saying how it went; [back], if
 * given, is a link on it back to the app.
 */
suspend fun awaitRedirect(port: Int, path: String, back: String? = null, open: () -> Unit): Map<String, String> = coroutineScope {
    // "localhost" may resolve to either address family; listen on both.
    val servers = listOf("127.0.0.1", "::1").mapNotNull { host ->
        runCatching { ServerSocket(port, 8, InetAddress.getByName(host)) }.getOrNull()
    }
    if (servers.isEmpty()) throw IOException("Port $port is busy. Is another realm logging in?")
    val got = CompletableDeferred<Map<String, String>>()
    try {
        servers.forEach { server ->
            launch(Dispatchers.IO) {
                while (!got.isCompleted) {
                    val client = runCatching { server.accept() }.getOrNull() ?: break
                    client.use { runCatching { answer(it, path, back) }.getOrNull()?.let(got::complete) }
                }
            }
        }
        open()
        withTimeoutOrNull(5.minutes) { got.await() } ?: throw IOException("Timed out waiting for the browser")
    } finally {
        servers.forEach { runCatching { it.close() } } // ends the accept loops
    }
}

/** Answers one request; returns its query parameters if it was the one waited for. */
private fun answer(client: Socket, path: String, back: String?): Map<String, String>? {
    client.soTimeout = 10_000
    val input = client.getInputStream().bufferedReader()
    val target = runCatching { input.readLine()?.split(' ')?.getOrNull(1) }.getOrNull()
    while (runCatching { input.readLine() }.getOrNull()?.isNotEmpty() == true) Unit // the headers
    val out = client.getOutputStream()
    if (target == null || target.substringBefore('?') != path) {
        out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
        return null
    }
    val params = target.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.associate {
        URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
    }
    val msg = when {
        params["error"] != null -> "That didn't work: ${params["error_description"] ?: params["error"]}"
        back != null -> "Logged in. You can go back to realm."
        else -> "Logged in. You can close this tab and go back to realm."
    }
    val link = back?.let { """<p><a href="$it" style="color:#f38020">Back to realm</a>""" }.orEmpty()
    val page = """<!doctype html><meta charset=utf-8><meta name=viewport content="width=device-width"><title>realm</title>""" +
        """<body style="font:16px system-ui;padding:40px">${escape(msg)}$link</body>"""
    val body = page.toByteArray()
    out.write(
        (
            "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
                "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
            ).toByteArray(),
    )
    out.write(body)
    out.flush()
    return params
}

private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
