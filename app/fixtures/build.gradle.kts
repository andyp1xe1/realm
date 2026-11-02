import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Cloudflare's and GitHub's replies as they really sent them, and a fake that answers with them: one source
// for core's tests and the desktop previews. Only tests depend on it.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
}

kotlin {
    android {
        namespace = "realm.fixtures"
        compileSdk = 37
        minSdk = 29
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    jvm("desktop") { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

    sourceSets {
        commonMain.dependencies {
            api(project(":core"))
            api(libs.ktor.client.mock)
            api(libs.kotlinx.serialization.json)
        }
    }
}
