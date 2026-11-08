package realm.cli

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.runBlocking
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import platform.posix.exit
import platform.posix.fprintf
import platform.posix.getenv
import platform.posix.stderr
import realm.Api
import realm.ApiException
import realm.CloudflareSession
import realm.Connection

fun main(args: Array<String>) = runBlocking {
    val path = configPath()
    val connection = try {
        if (SystemFileSystem.exists(path)) Connection.decode(SystemFileSystem.source(path).buffered().use { it.readString() }) else null
    } catch (e: ApiException) {
        fail(e.message ?: "The saved connection isn't readable")
    } ?: fail("Not connected. Run the Go CLI's `realm login` first.")
    val api = when (connection) {
        is Connection.Shared -> Api(connection.url, null, "cli")

        is Connection.ByToken -> Api.server(connection.server, { connection.token.value }, "cli")

        is Connection.ByCloudflare -> Api.server(
            connection.server,
            CloudflareSession(connection.login) { next -> save(path, next?.let { connection.copy(login = it) }) },
            "cli",
        )

        is Connection.LoginOnly -> fail("Logged in with Cloudflare, but there's no realm worker to connect to.")
    }
    try {
        when (args.firstOrNull() ?: "ls") {
            "realms" -> api.realms().forEach { println("${it.id}\t${it.count}\t${it.name}") }

            "ls" -> api.list(limit = 20).items.forEach {
                println("${it.id}\t${it.kind}\t${it.name ?: it.text?.lines()?.first().orEmpty()}")
            }

            else -> fail("Usage: realm [ls|realms]")
        }
    } catch (e: ApiException) {
        fail(e.message ?: "The server failed")
    } finally {
        api.close()
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun configPath(): Path {
    val dir = getenv("XDG_CONFIG_HOME")?.toKString()?.ifEmpty { null } ?: "${getenv("HOME")?.toKString()}/.config"
    return Path(dir, "realm", "config.json")
}

private fun save(path: Path, connection: Connection?) {
    if (connection == null) return SystemFileSystem.delete(path, mustExist = false)
    SystemFileSystem.sink(path).buffered().use { it.writeString(Connection.encode(connection)) }
}

@OptIn(ExperimentalForeignApi::class)
private fun fail(message: String): Nothing {
    fprintf(stderr, "realm: %s\n", message)
    exit(1)
    throw IllegalStateException()
}
