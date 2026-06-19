# Getting Started

Learn how to add the dependency, create the engine, and run your first download with KiteTorrent: a pure-Kotlin BitTorrent stack for Kotlin Multiplatform.

This walkthrough goes from an empty project to a working download from both a `.torrent` file and a magnet link. Every snippet is real, copy-pasteable Kotlin against the published API.

## Step 1: Add the dependencies

KiteTorrent ships as two artifacts:

| Artifact | What it is | Targets |
|---|---|---|
| `io.github.yuroyami:kitetorrent` | The pure core: bencoding, hashing, `.torrent` and magnet parsing, the wire codec, the piece picker. One dependency: `kotlin-stdlib`. | Android, iOS, JVM, JS |
| `io.github.yuroyami:kitetorrent-session` | The live engine: sockets, disk, trackers, DHT, the download/seed loop. Built on coroutines + ktor-network + kotlinx-io. | Android, iOS, JVM |

The session artifact depends on the core, so adding it pulls in both. Add it to `commonMain`:

=== "Kotlin (KMP)"

    ```gradle
    kotlin {
        sourceSets {
            commonMain.dependencies {
                implementation("io.github.yuroyami:kitetorrent-session:0.0.1-SNAPSHOT")
            }
        }
    }
    ```

=== "Android / JVM"

    ```gradle
    dependencies {
        implementation("io.github.yuroyami:kitetorrent-session:0.0.1-SNAPSHOT")
    }
    ```

!!! note "Published to mavenLocal, not Maven Central"

    KiteTorrent is pre-release. The artifacts are currently published only as `0.0.1-SNAPSHOT` to your local Maven repository, so consume them via `mavenLocal()`. Add it to your repositories block, and to the `pluginManagement` and `dependencyResolutionManagement` blocks in `settings.gradle.kts` as needed:

    ```gradle
    repositories {
        mavenLocal()
        mavenCentral()
    }
    ```

!!! tip "Only need parsing?"

    If you just want to read `.torrent` files or magnet links and never open a socket, depend on `io.github.yuroyami:kitetorrent` alone. It is stdlib-only and also runs on JS. See [The core toolkit](core-toolkit.md).

To build, you need **JDK 21** and the **Android SDK** (point `local.properties` at it with `sdk.dir`). These are build-time requirements; the library itself runs wherever your Kotlin targets run.

## Step 2: Create the engine

`KiteTorrentEngine` is the session manager. It owns the listen socket, the optional DHT node, the rate limiter, and every torrent you add. It takes a `CoroutineScope` that it uses for all of its background work, so pass one tied to your app or test lifecycle.

```kotlin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import io.github.yuroyami.kitetorrent.session.engine.KiteTorrentEngine

val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

val engine = KiteTorrentEngine(scope, enableDht = true)
engine.start()
```

`enableDht = true` brings up a live DHT node so you can find peers without a tracker. Leave it off (the default) if you only ever talk to trackers. `start()` is a `suspend` function: it binds the listen socket and starts the engine loop, so call it from a coroutine.

!!! note "The scope is the engine's lifetime"

    Everything the engine does runs in the scope you pass. Cancel that scope (or call `engine.shutdown()`) to tear it all down. There is no hidden global state.

The constructor has more knobs (`listenPort`, `enableUtp`, a `SettingsPack`, proxies). The two above are all you need to start. See [Engine settings](engine-settings.md) for the rest.

## Step 3: Download from a `.torrent` file

A download is three steps: parse the metadata, hand the engine a place to store the data, and watch the progress callback.

```kotlin
import io.github.yuroyami.kitetorrent.torrent.TorrentInfo
import io.github.yuroyami.kitetorrent.session.disk.FileDiskIo

// 1. Parse the .torrent bytes (get them from a file, the network, resources...).
val torrent = TorrentInfo.parse(bytes)

// 2. Add it, telling the engine where the files go.
val session = engine.addTorrent(
    torrent,
    FileDiskIo(torrent.storage, "/downloads"),
)

// 3. Track progress as pieces are verified against the torrent's hashes.
session.onPieceVerified = { piece ->
    println("piece $piece verified, ${session.progress() * 100}% done")
}
```

`TorrentInfo.parse(ByteArray)` decodes the bencoded `.torrent` and computes its info-hash. The resulting `torrent.storage` is a `FileStorage` describing every file and piece, which is exactly what `FileDiskIo` needs to lay the data out on disk under `/downloads`.

`addTorrent` registers the torrent, starts announcing to its trackers (and the DHT, if enabled), and begins the rarest-first download pipeline. It returns a `TorrentSession` you use to observe and control that one torrent.

`onPieceVerified` fires once per piece, after the piece has been downloaded **and** its hash checked against the torrent. `session.progress()` returns a `Float` from `0f` to `1f`.

!!! tip "Disk back-ends"

    `FileDiskIo` writes real files. For tests or in-memory pipelines, use `InMemoryDiskIo()` instead, which implements the same `DiskIo` interface with no filesystem. See [Downloading](downloading.md) for file priorities, sequential mode, and resume data.

## Step 4: Download from a magnet link

A magnet link carries no metadata, only an info-hash and some hints. The engine fetches the metadata from peers (via BEP-9 `ut_metadata`), then downloads normally. Because there is no `TorrentInfo` up front, you give `addMagnet` a factory that builds the disk back-end once the metadata arrives.

```kotlin
import io.github.yuroyami.kitetorrent.torrent.MagnetUri
import io.github.yuroyami.kitetorrent.session.disk.FileDiskIo

val magnet = MagnetUri.parseMagnetUri("magnet:?xt=urn:btih:0496aa38...")

val session = engine.addMagnet(magnet) { torrent ->
    FileDiskIo(torrent.storage, "/downloads")
}
```

`MagnetUri.parseMagnetUri(String)` returns a `MagnetLink` holding the info-hash, display name, trackers, and any embedded peers. The single-argument-plus-factory overload of `addMagnet` lets the engine discover peers itself from the magnet's trackers and the DHT, then fetch the metadata and start the download. It returns `TorrentSession?`: `null` if no peer could supply the metadata.

Once it returns non-null, the same `TorrentSession` API applies. Attach `onPieceVerified` and call `progress()` exactly as in Step 3.

!!! note "Supplying your own peers"

    If you already have peer endpoints (for example from your own discovery), there is a second overload: `addMagnet(magnet, peers, diskFactory)`, where `peers` is a `List<PeerEndpoint>`. The engine fetches the metadata from those peers directly. See [Magnet links](magnets.md).

## Step 5: Where files land, and running the tests

The data goes wherever you pointed `FileDiskIo`. With `FileDiskIo(torrent.storage, "/downloads")`, a single-file torrent named `ubuntu.iso` lands at `/downloads/ubuntu.iso`; a multi-file torrent lands under `/downloads/<torrent-name>/...`, following the layout in `torrent.storage`. Files are sparse by default, so unfinished downloads do not reserve their full size up front.

To verify the library on your machine, run the test suites. KiteTorrent has 532 passing tests, including loopback integration tests that download between two real engines over real sockets:

```bash
./gradlew :kitetorrent:jvmTest          # pure-core suite (parsing, hashing, picker, DHT data)
./gradlew :kitetorrent-session:jvmTest  # engine suite incl. the loopback download + magnet fetch
```

The `:kitetorrent-session:jvmTest` run is the interesting one: it exercises a TCP download from a seeder, two engines exchanging a torrent (and again over µTP), a `ut_metadata` magnet fetch, and a full magnet download between two engines.

## Where to next?

<div class="grid cards" markdown>

- :material-download: **[Downloading](downloading.md)**: file priorities, sequential mode, pause/resume, and saving resume data.
- :material-magnet: **[Magnet links](magnets.md)**: discovery, the peer-supplying overload, and v1/v2 magnets.
- :material-upload: **[Seeding](seeding.md)**: serve a torrent you already have, choking, and upload limits.
- :material-toolbox: **[The core toolkit](core-toolkit.md)**: parse, hash, and build torrents with the stdlib-only core.
- :material-tune: **[Engine settings](engine-settings.md)**: ports, µTP, rate limits, proxies, and `SettingsPack`.
- :material-book-open-variant: **[API reference](https://yuroyami.github.io/KiteTorrent/api/)**: every public type and signature.

</div>
