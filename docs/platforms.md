# Platform support

KiteTorrent ships as two artifacts. The pure core (`kitetorrent`) is computation only and compiles for every Kotlin target including the browser. The live session engine (`kitetorrent-session`) does real networking and disk I/O, so it runs everywhere a raw socket exists: Android, iOS, and the JVM, but not JS.

## Target matrix

The split follows one hard line: anything that is pure computation lives in the core; anything that needs a socket, a timer, or a file lives in the session. That line decides where each artifact can run.

| Platform | Core (`kitetorrent`) | Session (`kitetorrent-session`) |
|---|:-:|:-:|
| **Android** | ✓ | ✓ |
| **iOS arm64** | ✓ | ✓ |
| **iOS Simulator** (arm64) | ✓ | ✓ |
| **iOS x64** (Simulator) | ✓ | ✓ |
| **JVM / Desktop** | ✓ | ✓ |
| **JavaScript** (IR, Browser / Node) | ✓ | – |

The core depends on `kotlin-stdlib` and nothing else. The session adds `kotlinx.coroutines`, `ktor-network`, and `kotlinx-io`.

## Why the boundary exists

libtorrent is not like a PDF engine. A PDF library is pure computation, so it can be 100% stdlib-only common Kotlin. BitTorrent's whole reason to exist is networking, concurrency, and disk I/O, and the Kotlin standard library has no equivalent for any of those. There is no stdlib-only way to open a socket.

So KiteTorrent does not pretend otherwise. The two artifacts draw the line honestly:

`kitetorrent` (the core)
: Bencoding, hashing, `.torrent` and magnet parsing, the wire-protocol codec, the piece picker, DHT data structures, settings, alerts, and crypto. All pure computation. No sockets, no files, no coroutines. This is why it can target JS: the browser has no raw TCP or UDP, but it does not need any.

`kitetorrent-session` (the engine)
: The live session loop, peer connections, trackers, the DHT node, µTP, port mapping, and disk I/O. Built on coroutines for concurrency, `ktor-network` for sockets, and `kotlinx-io` for buffers. None of that maps onto a browser, so the session has no JS target.

!!! note "No JS or WASM for the session"
    Browsers do not expose raw TCP or UDP sockets, so a BitTorrent session cannot run there. The core compiles for `js(IR)` so you can parse `.torrent` files, decode magnet links, hash data, and build torrents in the browser. The engine that talks to peers stays on Android, iOS, and the JVM.

## What each artifact gives you

### `kitetorrent`: the pure core

No external runtime dependencies beyond `kotlin-stdlib`. No JNI, no cinterop, no platform-specific code in `commonMain`. Use it when you need to read or produce torrent metadata without connecting to anything:

```kotlin
import io.github.yuroyami.kitetorrent.torrent.TorrentInfo
import io.github.yuroyami.kitetorrent.torrent.MagnetUri

val info = TorrentInfo.parse(bytes)
println("${info.name}: ${info.numFiles} files, ${info.totalSize} bytes")

val magnet = MagnetUri.parseMagnetUri("magnet:?xt=urn:btih:0496aa38...")
println(magnet.infoHashHex())
```

Bencoding, SHA-1/256/512, ed25519, the piece picker, DHT routing tables, the wire codec, and settings all live here. This artifact runs on every target in the matrix, browser included.

### `kitetorrent-session`: the live engine

Add this when you actually want to download or seed. It depends on the core (transitively, via `api`) plus coroutines, `ktor-network`, and `kotlinx-io`. You drive it from a `CoroutineScope`:

```kotlin
import io.github.yuroyami.kitetorrent.session.engine.KiteTorrentEngine
import io.github.yuroyami.kitetorrent.session.disk.FileDiskIo
import io.github.yuroyami.kitetorrent.torrent.TorrentInfo

val engine = KiteTorrentEngine(scope, enableDht = true)
engine.start()

val torrent = TorrentInfo.parse(bytes)
val session = engine.addTorrent(
    torrent,
    FileDiskIo(torrent.storage, "/downloads", dispatcher = Dispatchers.IO, filePriorities = null),
    resume = null,
)
session.onPieceVerified = { piece -> println("piece $piece: ${session.progress() * 100}%") }
```

The session module covers the engine, the per-torrent session, peer connections, HTTP and UDP trackers, a live DHT node, µTP (BEP-29), UPnP, and NAT-PMP. It targets Android, iOS, and the JVM.

## Disk I/O

The session reads and writes piece data through the `DiskIo` interface. It is a suspending contract: `read`, `write`, `hashPiece`, `flush`, `close`, and friends. Two implementations ship in the session module.

`InMemoryDiskIo`
: Keeps everything in memory. No files touched. Useful for tests and for transient transfers where you never want to persist data.

`FileDiskIo`
: The real backend, writing to actual files on disk. Construct it with the torrent's `FileStorage`, a base path, a dispatcher, and optional per-file priorities:

```kotlin
import io.github.yuroyami.kitetorrent.session.disk.FileDiskIo

val disk = FileDiskIo(
    storage = torrent.storage,
    basePath = "/downloads",
    dispatcher = Dispatchers.IO,
    filePriorities = null,
)
```

Random-access file I/O is the one place the session reaches for a platform syscall, and it does so through a tiny `expect`/`actual`: `RandomAccessFile` on JVM and Android, POSIX file calls on iOS. Everything above that line, including the hashing and the piece bookkeeping, stays in common code.

!!! tip "Pick the disk per torrent for magnets"
    `addMagnet` does not know the file layout until metadata arrives, so it takes a factory instead of a ready disk: `addMagnet(magnet) { info -> FileDiskIo(info.storage, "/downloads", Dispatchers.IO, null) }`. The engine calls your factory once the `TorrentInfo` is fetched.

## Build requirements

KiteTorrent builds with Gradle and the Kotlin Multiplatform plugin.

- **JDK 21** or newer.
- **Android SDK**, with its location in `local.properties` (`sdk.dir`).

Common Gradle tasks:

=== "Test the core"

    ```bash
    ./gradlew :kitetorrent:jvmTest
    ```

=== "Test the engine"

    ```bash
    ./gradlew :kitetorrent-session:jvmTest
    ```

=== "Build everything"

    ```bash
    ./gradlew build
    ```

The engine tests include loopback integration runs over real sockets: a TCP download from a seeder, two engines exchanging a torrent (and again entirely over µTP), a `ut_metadata` magnet fetch, and a full magnet download between two engines.

## Installation

KiteTorrent is published as `0.0.1-SNAPSHOT` and is consumed today through `mavenLocal`. It is not on Maven Central yet. Publish the snapshot to your local repository, then depend on it:

```bash
./gradlew publishToMavenLocal
```

=== "Kotlin (KMP)"

    Make sure `mavenLocal()` is in your repositories, then add the artifacts to `commonMain`:

    ```kotlin
    kotlin {
        sourceSets {
            commonMain.dependencies {
                // The pure core (Android, iOS, JVM, JS)
                implementation("io.github.yuroyami:kitetorrent:0.0.1-SNAPSHOT")

                // The live engine (Android, iOS, JVM; NOT JS)
                implementation("io.github.yuroyami:kitetorrent-session:0.0.1-SNAPSHOT")
            }
        }
    }
    ```

=== "Android / JVM only"

    Add to your regular `dependencies { }` block:

    ```kotlin
    implementation("io.github.yuroyami:kitetorrent:0.0.1-SNAPSHOT")
    implementation("io.github.yuroyami:kitetorrent-session:0.0.1-SNAPSHOT")
    ```

If you only parse torrents or build them (no peers), depend on `kitetorrent` alone and skip the session entirely. That is also the only way to use KiteTorrent from browser code.

## Related

- [Getting started](getting-started.md): wire up an engine and start a download
- [Downloading](downloading.md): the full download flow, piece by piece
- [Magnets](magnets.md): fetch metadata from a magnet link, then download
- [Core toolkit](core-toolkit.md): parsing, hashing, and bencoding without the engine
- [About the port](about.md): what is done, what is next
- [API reference](https://yuroyami.github.io/KiteTorrent/api/)
