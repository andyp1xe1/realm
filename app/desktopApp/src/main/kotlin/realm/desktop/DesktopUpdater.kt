package realm.desktop

import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import realm.ApiException
import realm.AppUpdate
import realm.AppVersion
import realm.Semver
import realm.Updater
import realm.download
import realm.newerApp
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlin.system.exitProcess

/** Replaces the AppImage or jar this app runs from with a release's, in place, so launchers and shortcuts keep working. */
internal class DesktopUpdater(
    override val version: AppVersion,
    private val target: File,
    private val kind: Kind,
    private val engine: HttpClientEngine? = null,
) : Updater {
    enum class Kind(val suffix: String) { AppImage("-x86_64.AppImage"), Jar("-linux-x64.jar") }

    /** This copy's file in the release tagged [tag]. */
    fun file(tag: Semver) = "realm-$tag${kind.suffix}"

    override suspend fun newer() = newerApp(version, ::file, engine)

    override suspend fun install(update: AppUpdate, progress: (Float) -> Unit, installing: () -> Unit): Boolean {
        val dir = target.absoluteFile.parentFile
        if (!dir.canWrite()) throw ApiException("realm can't replace itself: you can't write to ${dir.path}.")
        val staged = File(dir, ".${target.name}.update")
        update.download(staged, engine, progress)
        installing()
        withContext(Dispatchers.IO) {
            staged.setExecutable(true)
            Files.move(staged.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        }
        return true
    }

    override fun restart() {
        val command = when (kind) {
            Kind.AppImage -> listOf(target.path)
            Kind.Jar -> listOf(File(System.getProperty("java.home"), "bin/java").path, "-jar", target.path)
        }
        ProcessBuilder(command).inheritIO().start()
        exitProcess(0)
    }

    companion object {
        /** The updater for the release file this app runs from, if it was built for a release and runs from one. */
        fun find(): DesktopUpdater? {
            val version =
                DesktopUpdater::class.java.getResource("/realm/version")?.readText()?.trim()?.let(AppVersion::parse) ?: return null
            System.getenv("APPIMAGE")?.let(::File)?.takeIf { it.isFile }?.let { return DesktopUpdater(version, it, Kind.AppImage) }
            return uberJar()?.let { DesktopUpdater(version, it, Kind.Jar) }
        }

        private fun uberJar(): File? {
            val own = DesktopUpdater::class.java.protectionDomain.codeSource?.location ?: return null
            if (own != KotlinVersion::class.java.protectionDomain.codeSource?.location) return null
            return File(own.toURI()).takeIf { it.isFile && it.extension == "jar" }
        }
    }
}
