// The desktop app: the shared screens in a window, released as an AppImage and a jar.
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.reload.gradle.ComposeHotRun
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    sourceSets["main"].kotlin.srcDir("../jvmCommon")
}

val realmVersion: String? = providers.gradleProperty("realm.version").orNull

val versionResource = tasks.register("versionResource") {
    val version = realmVersion
    val out = layout.buildDirectory.dir("generated/version")
    inputs.property("version", version.orEmpty())
    outputs.dir(out)
    doLast {
        val dir = out.get().asFile.resolve("realm")
        dir.deleteRecursively()
        if (version != null) dir.apply { mkdirs() }.resolve("version").writeText(version)
    }
}
sourceSets["main"].resources.srcDir(versionResource)

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.material3)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.kotlinx.serialization.json)
    runtimeOnly(libs.slf4j.simple)
    testImplementation(project(":fixtures"))
    testImplementation(kotlin("test"))
}

compose.desktop {
    application {
        mainClass = "realm.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.AppImage)
            packageName = "realm"
            packageVersion = realmVersion ?: "0.1.0"
            modules("java.desktop", "jdk.unsupported")
            linux { iconFile.set(file("packaging/realm.png")) }
        }
    }
}

tasks.withType<JavaExec>().matching { it.name == "run" }.configureEach {
    System.getenv("REALM_LOG")?.let { systemProperty("org.slf4j.simpleLogger.defaultLogLevel", it) }
}

tasks.withType<ComposeHotRun>().configureEach {
    mainClass.set("realm.desktop.MainKt")
    isAutoReloadEnabled.set(true)
}
tasks.register("dev") {
    group = "application"
    description = "Run the desktop app with hot reload (recompiles and reloads on save)"
    dependsOn("hotRun")
}

tasks.register<JavaExec>("preview") {
    group = "verification"
    description = "Render the screens with fake data into build/preview, and check each filmstrip"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("realm.desktop.PreviewKt")
    args(layout.buildDirectory.dir("preview").get().asFile.path)
    maxHeapSize = "1536m"
}
