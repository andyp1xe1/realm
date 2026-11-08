// The command-line client: core as one native binary.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    linuxX64 {
        binaries.executable {
            baseName = "realm"
            entryPoint = "realm.cli.main"
        }
    }

    sourceSets {
        linuxX64Main.dependencies {
            implementation(project(":core"))
            implementation(libs.ktor.client.curl)
        }
    }
}
