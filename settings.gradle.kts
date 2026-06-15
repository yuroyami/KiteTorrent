enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "KiteTorrent-KMP"

// :kitetorrent — the pure-Kotlin BitTorrent core (stdlib only). Everything that is
// pure computation — bencoding, hashing, .torrent parsing, the wire-protocol codec,
// the piece picker — lives here and ports cleanly to every KMP target.
include(":kitetorrent")

// :kitetorrent-session — the live networking layer (Tier 2). Depends on the core plus
// coroutines + ktor-network + kotlinx-io for the things the core deliberately can't do:
// open sockets, run an event loop, touch the disk. No JS target (browsers have no raw sockets).
include(":kitetorrent-session")
