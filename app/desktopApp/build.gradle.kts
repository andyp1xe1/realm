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

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.material3)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.kotlinx.serialization.json)
    // Ktor logs through SLF4J; this prints warnings to the terminal (src/main/resources/simplelogger.properties).
    runtimeOnly(libs.slf4j.simple)
    testImplementation(libs.ktor.client.mock)
    testImplementation(kotlin("test"))
}

compose.desktop {
    application {
        mainClass = "realm.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.AppImage, TargetFormat.Dmg, TargetFormat.Msi)
            packageName = "realm"
            packageVersion = "1.0.0"
            modules("java.desktop", "jdk.unsupported")
            linux {
                // Releases pass the tag without its v: `-Prealm.version=1.2.0` or `1.2.0-rc.1`. Only Linux takes it:
                // the AppImage and the uber jar accept any version, but Dmg and Msi need MAJOR > 0 and no -rc
                // suffix, and Compose checks every listed format, so they keep the placeholder above.
                packageVersion = providers.gradleProperty("realm.version").getOrElse("1.0.0")
                iconFile.set(file("packaging/realm.png"))
            }
        }
    }
}

// `./gradlew :desktopApp:dev` runs the app with Compose Hot Reload: save a file and the window updates in place.
// REALM_LOG=debug ./gradlew :desktopApp:run logs more than the warnings simplelogger.properties allows.
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

// Offscreen screenshots of the UI with fake data, for eyeballing layout: build/preview/*.png
tasks.register<JavaExec>("preview") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("realm.desktop.PreviewKt")
    args(layout.buildDirectory.dir("preview").get().asFile.path)
    // Its own JVM: capped, so it and Gradle fit in the memory a build is given.
    maxHeapSize = "1536m"
}
