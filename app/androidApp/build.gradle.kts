import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// A release build gets its version from the tag: `-Prealm.version=1.2.0`, or `-Prealm.version=1.2.0-rc.1`.
// versionCode grows with every release: MAJOR*1_000_000 + MINOR*10_000 + PATCH*100, plus N for rc.N and 99
// for the release itself, so 1.2.0-rc.1 < 1.2.0-rc.2 < 1.2.0 < 1.2.1-rc.1. Local builds stay 0.1, code 1.
val realmVersion: String? = providers.gradleProperty("realm.version").orNull
val realmVersionCode: Int? =
    realmVersion?.let { v ->
        val parts =
            Regex("""(0|[1-9]\d{0,2})\.(\d{1,2})\.(\d{1,2})(?:-rc\.([1-9]\d?))?""").matchEntire(v)?.destructured
                ?: error("realm.version $v isn't MAJOR.MINOR.PATCH(-rc.N) with MAJOR < 1000, MINOR and PATCH < 100")
        val (major, minor, patch, rc) = parts
        require(rc != "99") { "realm.version $v: rc.99 would share the release's versionCode" }
        major.toInt() * 1_000_000 + minor.toInt() * 10_000 + patch.toInt() * 100 + (rc.toIntOrNull() ?: 99)
    }

// The release key comes from the environment, as in CI: REALM_KEYSTORE (a PKCS12 file), REALM_KEYSTORE_PASSWORD,
// REALM_KEY_ALIAS, REALM_KEY_PASSWORD. Without REALM_KEYSTORE, release builds are signed with the debug key,
// so `assembleRelease` works anywhere.
val releaseKeystore: String? = providers.environmentVariable("REALM_KEYSTORE").orNull

android {
    namespace = "realm.android"
    compileSdk = 37
    defaultConfig {
        applicationId = "dev.realm.app"
        minSdk = 29
        targetSdk = 37
        versionCode = realmVersionCode ?: 1
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
            // R8 takes the APK from 52 MB to 4 MB, mostly unused icons. Ktor, kotlinx.serialization, coroutines,
            // and OkHttp ship their own keep rules, so the app needs none of its own.
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
