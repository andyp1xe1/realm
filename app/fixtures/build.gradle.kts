import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Cloudflare's and GitHub's real replies and a fake server that answers with them, for tests and previews.
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
