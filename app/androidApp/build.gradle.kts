// The Android app: the shared screens in one activity, with Android's pickers, camera, and share sheet.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/** Orders every release and its candidates: 1.2.0-rc.1 < 1.2.0-rc.2 < 1.2.0 < 1.2.1-rc.1. */
fun versionCodeOf(version: String): Int {
    val (major, minor, patch, rc) =
        Regex("""(0|[1-9]\d{0,2})\.(\d{1,2})\.(\d{1,2})(?:-rc\.([1-9]\d?))?""").matchEntire(version)?.destructured
            ?: error("realm.version $version isn't MAJOR.MINOR.PATCH(-rc.N) with MAJOR < 1000, MINOR and PATCH < 100")
    require(rc != "99") { "realm.version $version: rc.99 would share the release's versionCode" }
    val release = 99
    return major.toInt() * 1_000_000 + minor.toInt() * 10_000 + patch.toInt() * 100 + (rc.toIntOrNull() ?: release)
}

val realmVersion: String? = providers.gradleProperty("realm.version").orNull
val releaseKeystore: String? = providers.environmentVariable("REALM_KEYSTORE").orNull

android {
    namespace = "realm.android"
    compileSdk = 37
    defaultConfig {
        applicationId = "dev.realm.app"
        minSdk = 29
        targetSdk = 37
        versionCode = realmVersion?.let(::versionCodeOf) ?: 1
        versionName = realmVersion ?: "0.1"
    }
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storeType = "pkcs12"
                storePassword = providers.environmentVariable("REALM_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("REALM_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("REALM_KEY_PASSWORD").get()
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName(if (releaseKeystore != null) "release" else "debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets["main"].kotlin.directories.add("../jvmCommon")
    packaging {
        resources.excludes +=
            setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/INDEX.LIST", "/META-INF/versions/9/previous-compilation-data.bin")
    }
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.kotlinx.coroutines.android)
}
