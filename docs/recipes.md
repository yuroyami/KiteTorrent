# Recipes

Short, working solutions to common KiteTorrent tasks. Each snippet is copy-pasteable and uses only real APIs from KiteTorrent 0.0.1-SNAPSHOT.

!!! note "Two modules"
    Parsing a `.torrent`, reading metadata, and bencoding live in the pure-core `:kitetorrent` artifact. Anything that touches the network or disk (the engine, sessions, `FileDiskIo`) lives in `:kitetorrent-session`. The recipes below say which one they need. See [Engine & settings](engine-settings.md) for the split.

## Download a .torrent to a folder with progress

```kotlin
import io.github.yuroyami.kitetorrent.torrent.TorrentInfo
import io.github.yuroyami.kitetorrent.session.engine.KiteTorrentEngine
import io.github.yuroyami.kitetorrent.session.disk.FileDiskIo
import io.github.yuroyami.kitetorrent.session.tracker.HttpTracker
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

val engine = KiteTorrentEngine(
    scope,
    httpTracker = HttpTracker(HttpClient(CIO)),
    enableDht = true,
)
engine.start()

val torrent = TorrentInfo.parse(torrentBytes)
val disk = FileDiskIo(torrent.storage, "/downloads", Dispatchers.IO, filePriorities = null)

val session = engine.addTorrent(torrent, disk, resume = null)

// onPieceVerified is a plain callback, so it cannot call the suspending progress().
session.onPieceVerified = { piece -> println("piece $piece verified") }

// progress() suspends, so read it from a coroutine.
scope.launch {
    while (!session.isSeeding()) {
        println("${(session.progress() * 100).toInt()}%")
        delay(1_000)
    }
}
```

`onPieceVerified` fires after a piece passes its hash check and lands on disk. It is a plain `((Int) -> Unit)?`, so the suspending `progress()` has to be polled from a coroutine instead. `progress()` returns a `Float` from `0f` to `1f`, counting verified pieces over the torrent's total piece count.

The `httpTracker` argument matters. Leave it at its `null` default and announces to `http://` and `https://` trackers are skipped without an error. [Getting started, Step 2](getting-started.md#step-2-create-the-engine) explains that and lists the ktor dependencies `HttpTracker` needs.

## Magnet to a folder

A magnet link has no metadata, so you supply a `diskFactory` that builds the `DiskIo` once the engine has fetched the `.torrent` over `ut_metadata` (BEP-9). The single-argument overload auto-discovers peers via DHT and trackers.

```kotlin
import io.github.yuroyami.kitetorrent.torrent.MagnetUri

val magnet = MagnetUri.parseMagnetUri("magnet:?xt=urn:btih:0496aa38…")

val session = engine.addMagnet(magnet) { ti ->
    FileDiskIo(ti.storage, "/downloads", Dispatchers.IO, filePriorities = null)
}

session?.onPieceVerified = { piece -> println("piece $piece") }
```

!!! tip "Already know some peers?"
    Pass them explicitly with the three-argument overload: `engine.addMagnet(magnet, peers, diskFactory)`, where `peers` is a `List<PeerEndpoint>`. The engine fetches the metadata from those peers instead of discovering peers itself. Once the download starts, the session still announces to the torrent's trackers and asks the DHT for more peers.

`addMagnet` returns `null` when the magnet carries no usable info-hash, when no peer is reachable, or when no peer supplies the metadata.

## Create a torrent, then seed it

`CreateTorrent` produces the `.torrent` bytes from a `FileStorage`. Parse those bytes back into a `TorrentInfo`, then add it to the engine pointing at the source folder and force a full recheck so the existing files are claimed.

```kotlin
import io.github.yuroyami.kitetorrent.torrent.CreateTorrent

// 1. Build the .torrent. The core has no disk access, so you supply one
//    Sha1Hash per piece yourself (compute them from the file bytes).
val creator = CreateTorrent(storage).addTracker("udp://tracker.example:6969")
pieceHashes.forEachIndexed { i, h -> creator.setHash(i, h) }
val torrentBytes = creator.generateBuffer()   // generate() returns the bencode tree

// 2. Parse it back and seed
val torrent = TorrentInfo.parse(torrentBytes)
val disk = FileDiskIo(torrent.storage, "/path/to/source", Dispatchers.IO, filePriorities = null)
val session = engine.addTorrent(torrent, disk, resume = null)

// 3. FileDiskIo reports nothing present, so ask for a full rehash of the
//    source files. Without this the engine claims zero pieces and
//    re-downloads the data it is sitting on.
session.recheck(full = true)

session.onStateChanged = { state -> println("state: $state") }
```

`recheck(full = true)` hashes every piece against the torrent and claims the ones that match, after which the session moves to `TorrentState.SEEDING`. It is a full read plus a full SHA-1 pass over the source folder, so it costs real time on a large torrent. `addTorrent` calls `session.start()` internally, so the recheck has to come afterwards. See [Resuming a download](downloading.md#resuming-a-download) for why `FileDiskIo` needs this. Save `torrentBytes` to a file or hand them to peers.

## Cap the download rate

Rate limits are set on the engine in bytes per second and apply session-wide. They take effect immediately on running torrents.

```kotlin
engine.setDownloadRateLimit(2 * 1024 * 1024)  // 2 MiB/s down
engine.setUploadRateLimit(512 * 1024)         // 512 KiB/s up
```

Pass `0` to remove a limit. The same caps are also reachable through `SettingsPack` (`download_rate_limit` / `upload_rate_limit`) if you configure the engine declaratively. See [Engine & settings](engine-settings.md).

## Parse a .torrent and list its files

This is pure core. No engine, no coroutines, no `:kitetorrent-session`. Just the `:kitetorrent` artifact.

```kotlin
import io.github.yuroyami.kitetorrent.torrent.TorrentInfo

val torrent = TorrentInfo.parse(torrentBytes)

println("name:    ${torrent.name}")
println("hash:    ${torrent.infoHashHex()}")
println("size:    ${torrent.totalSize} bytes")
println("pieces:  ${torrent.numPieces} × ${torrent.pieceLength}")

for (file in torrent.files) {
    println("  ${file.path}  (${file.size} bytes)")
}
```

`TorrentInfo` exposes `trackers`, `webSeeds`, `comment`, `createdBy`, `creationDate`, and the v1/v2 flags (`isV1`, `isV2`, `isHybrid`) too. See the [Core toolkit](core-toolkit.md) for the full surface.

!!! tip "Inspect a magnet without metadata"
    A magnet link carries no file list, only an info-hash. Use `MagnetUri.parseMagnetUri(uri)` to read its `infoHashHex()`, `displayName`, and `trackers`. The file list is only known once the engine fetches metadata over the wire.

## Resume an interrupted download

Before shutting down, ask the session for its resume data and persist the bytes. Next launch, read them back into an `AddTorrentParams` and pass it as the `resume` argument. The session adopts the saved piece bitfield and continues from there.

=== "Save on shutdown"

    ```kotlin
    import io.github.yuroyami.kitetorrent.torrent.resume.ResumeData

    val params = session.saveResumeData()           // AddTorrentParams?
    if (params != null) {
        val bytes = ResumeData.write(params)         // ByteArray
        writeFile("download.resume", bytes)
    }
    ```

=== "Restore on next launch"

    ```kotlin
    val torrent = TorrentInfo.parse(torrentBytes)
    val resumeBytes = readFile("download.resume")
    val resume = ResumeData.read(torrent, resumeBytes)   // AddTorrentParams?

    val disk = FileDiskIo(torrent.storage, "/downloads", Dispatchers.IO, filePriorities = null)
    val session = engine.addTorrent(torrent, disk, resume = resume)
    ```

`saveResumeData()` captures verified pieces, the partial-block progress of unfinished pieces, byte counters, and file priorities. On restore the session takes the saved bitfield as given and skips the disk read, which is what makes fast resume fast. Those pieces were hash-checked when they were first written, but the resume file is trusted on its word: if the files changed underneath it, call `session.recheck(full = true)` after `addTorrent` to rehash everything.

!!! note "Save the DHT table too"
    The DHT routing table is engine-wide, not per-torrent. Persist it with `engine.saveDhtState()` and feed it back via `engine.restoreDhtState(bytes)` so the next session bootstraps faster.

## Where to go next

<div class="grid cards" markdown>

- :material-download: **[Downloading](downloading.md)**: the full add-torrent flow, alerts, and lifecycle.
- :material-magnet: **[Magnets](magnets.md)**: metadata fetch, peer discovery, and magnet construction.
- :material-upload: **[Seeding](seeding.md)**: creating torrents and serving pieces.
- :material-wrench: **[Core toolkit](core-toolkit.md)**: bencode, hashing, and parsing without the engine.
- :material-cog: **[Engine & settings](engine-settings.md)**: `SettingsPack`, rate limits, DHT, and µTP.
- :material-api: **[API reference](https://yuroyami.github.io/KiteTorrent/api/)**: every public symbol.

</div>
