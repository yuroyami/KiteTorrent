import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
}

/*
 * :kitetorrent is the pure-Kotlin BitTorrent core. NO external runtime deps —
 * only kotlin-stdlib is on the classpath, exactly like :kitepdf. Everything in
 * here is pure computation (no sockets, no threads, no disk): bencoding, hashing,
 * .torrent parsing, the peer wire-protocol codec, the piece picker.
 *
 * The live networking session (sockets/coroutines/disk) will live in a separate
 * module so this core stays portable to every target, including JS and WASM.
 */
kotlin {
    jvmToolchain(21)

    android {
        namespace = "io.github.yuroyami.kitetorrent"
        compileSdk = 36
        minSdk = 21
    }

    listOf(
        iosSimulatorArm64(),
        iosArm64(),
        iosX64(),
    ).forEach { target ->
        target.binaries.framework {
            baseName = "KiteTorrent"
            isStatic = false
        }
    }

    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    js(IR) {
        browser()
        nodejs()
        binaries.library()
    }

    jvm()

    sourceSets {
        all {
            languageSettings {
                optIn("kotlin.RequiresOptIn")
                optIn("kotlin.experimental.ExperimentalNativeApi")
            }
        }

        commonMain.dependencies {
            // Intentionally empty. KiteTorrent core depends on kotlin-stdlib only.
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
