import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.vanniktech.publish)
}

/*
 * :kitetorrent-session is the live BitTorrent engine (Tier 2). It takes the pure
 * :kitetorrent core and drives it over real sockets and disk:
 *   - kotlinx.coroutines  → replaces Boost.Asio's io_context / strands (one event loop,
 *     structured concurrency instead of a callback soup)
 *   - ktor-network        → TCP + UDP sockets (peer connections, uTP, trackers, DHT)
 *   - ktor-client         → HTTP(S) for HTTP trackers and web seeds
 *   - kotlinx-io          → the disk subsystem (mmap_storage / mmap_disk_io)
 *
 * No JS/WASM target: browsers can't open raw TCP/UDP sockets.
 */
kotlin {
    jvmToolchain(21)

    android {
        namespace = "io.github.yuroyami.kitetorrent.session"
        compileSdk = 36
        minSdk = 21
    }

    listOf(
        iosSimulatorArm64(),
        iosArm64(),
        iosX64(),
    ).forEach { target ->
        target.binaries.framework {
            baseName = "KiteTorrentSession"
            isStatic = false
        }
    }

    jvm()

    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    sourceSets {
        all {
            languageSettings {
                optIn("kotlin.RequiresOptIn")
                optIn("kotlin.experimental.ExperimentalNativeApi")
                optIn("kotlinx.coroutines.ExperimentalCoroutinesApi")
            }
        }

        commonMain.dependencies {
            api(projects.kitetorrent)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.ktor.network)
            implementation(libs.ktor.client.core)
            implementation(libs.kotlinx.io.core)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.core)
        }

        jvmMain.dependencies {
            implementation(libs.ktor.client.cio)
        }
    }
}
