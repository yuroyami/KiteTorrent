# Downloading Torrents

Add a `.torrent` to the engine, watch pieces land, and control the download. KiteTorrent runs the same `request_blocks` scheduler libtorrent does: dynamic per-peer request queues, a true end-game, snubbing, tit-for-tat choking, and verified resume.

This page covers adding a torrent from a parsed `TorrentInfo`. For magnet links (where the metadata is fetched first), see [Magnet links](magnets.md).

## Adding a torrent

You need three things: a running [`KiteTorrentEngine`](engine-settings.md), a parsed `TorrentInfo`, and a `DiskIo` to store the data.

```kotlin
import io.github.yuroyami.kitetorrent.torrent.TorrentInfo
import io.github.yuroyami.kitetorrent.session.engine.KiteTorrentEngine
import io.github.yuroyami.kitetorrent.session.disk.FileDiskIo

val engine = KiteTorrentEngine(scope, enableDht = true)
engine.start()

val torrent = TorrentInfo.parse(bytes)
val disk = FileDiskIo(torrent.storage, "/downloads")
val session = engine.addTorrent(torrent, disk, resume = null)
```

`addTorrent` returns a `TorrentSession`: the live handle for one torrent. Pass `resume = null` for a fresh download, or an `AddTorrentParams` to resume an existing one (see [Verified resume](#verified-resume) below).

!!! note "Choosing a disk backend"
    `FileDiskIo(storage, basePath, ...)` writes to real files under `basePath`. `InMemoryDiskIo()` keeps everything in RAM and is meant for tests. Both implement the same `DiskIo` interface, so the session does not care which one it gets. See [Disk backends](#disk-backends).

### Finding peers

A fresh session has metadata but no peers yet. If you enabled DHT and the torrent carries trackers, ask the engine to discover some, then hand them to the session:

```kotlin
import io.github.yuroyami.kitetorrent.session.tracker.PeerEndpoint

val peers: List<PeerEndpoint> = engine.discoverPeers(
    infoHash = torrent.infoHashV1!!,
    trackers = torrent.allTrackers(),
)
session.connect(peers)
```

`discoverPeers` combines tracker announces and DHT lookups. You can also feed `connect` a peer list you obtained elsewhere (a known seed, a `peers` entry from a magnet, etc.). `PeerEndpoint` is just a `host` plus a `port`.

## The session lifecycle

Every torrent moves through a small state machine. The current state is `session.state`, and you can subscribe to transitions:

```kotlin
import io.github.yuroyami.kitetorrent.session.engine.TorrentState

session.onStateChanged = { state ->
    when (state) {
        TorrentState.CHECKING    -> println("hashing on-disk pieces")
        TorrentState.DOWNLOADING -> println("downloading")
        TorrentState.SEEDING     -> println("complete, now seeding")
        TorrentState.PAUSED      -> println("paused")
    }
}
```

| State | Meaning |
|---|---|
| `CHECKING` | Rehashing existing on-disk data before download starts (verified resume). |
| `DOWNLOADING` | Actively requesting blocks from peers. |
| `SEEDING` | All wanted pieces verified; the session now only uploads. |
| `PAUSED` | No new peers, no new requests. |

A session that finishes its wanted pieces transitions to `SEEDING` on its own. See [Seeding](seeding.md) for what happens after that.

## Tracking progress

### Overall progress

`progress()` returns a `Float` from `0.0` to `1.0` over the bytes you actually want (pieces set to `IGNORE` priority do not count against you):

```kotlin
println("${(session.progress() * 100).toInt()}% complete")
println("${session.numPeers()} peers connected")
```

### Per-piece notifications

`onPieceVerified` fires once per piece, after the data has been written to disk **and** its hash has matched the torrent's piece hash. A piece that fails verification is silently re-requested and does not fire the callback.

```kotlin
session.onPieceVerified = { piece ->
    println("piece $piece verified: ${(session.progress() * 100).toInt()}%")
}
```

!!! tip
    Because the callback only fires on a verified piece, it is a safe trigger for "this part of the file is now readable". For sequential downloads (below), the pieces arrive roughly in order, so you can begin consuming a file before the whole torrent finishes.

### Alerts

Lower-level events (peer connects, tracker replies, hash failures, errors) arrive as alerts. Drain them from the engine, or attach a per-session handler:

```kotlin
import io.github.yuroyami.kitetorrent.alert.PieceFinishedAlert
import io.github.yuroyami.kitetorrent.alert.HashFailedAlert

session.onAlert = { alert ->
    when (alert) {
        is HashFailedAlert   -> println("a piece failed its hash check")
        is PieceFinishedAlert -> { /* ... */ }
        else -> {}
    }
}

// or pull the whole session-wide queue:
val pending = engine.popAlerts()
```

See [Engine & settings](engine-settings.md) for the full alert catalogue and categories.

## Pause and resume

Pause stops new connections and new requests without tearing the session down; resume picks back up:

```kotlin
session.pause()
// ... later ...
session.resume()
```

Pausing a single torrent is independent of the engine. You can also pause and resume the **entire** engine at once with `engine.pauseSession()` / `engine.resumeSession()`, which affects every torrent it holds.

## Priorities

### File priorities

For multi-file torrents, you can choose which files to download. Pass one integer per file, in the torrent's file order. `0` means "do not download this file"; higher values raise its priority.

```kotlin
// download only files 0 and 2; skip file 1
session.setFilePriorities(intArrayOf(1, 0, 1))
```

The priority integers correspond to `DownloadPriority`: `IGNORE` (0), `LOW`, `NORMAL`, `HIGH`. A file set to `IGNORE` is excluded from `progress()` and never requested.

!!! note
    You can also fix file priorities up front, before the session exists, by passing `filePriorities` to the `FileDiskIo` constructor. That lets the disk layer skip allocating storage for files you never intend to fetch.

### Piece priorities

Priorities can also be set at piece granularity through the session's piece-priority control, which the scheduler reads when it picks the next block. This is what powers selective and ordered downloading; the most common case (in-order delivery) has its own switch, below.

## Sequential download

By default the picker is **rarest-first**: it grabs the least-available pieces first, which is best for swarm health and overall speed. When you would rather stream a file in order (video, a large archive you unpack as it lands), flip the session to sequential:

```kotlin
session.sequentialDownload = true
```

Pieces then arrive in index order instead of rarest-first. `onPieceVerified` will report them roughly ascending, so a consumer can start reading the front of the file while the tail is still in flight.

!!! tip
    Sequential download trades a little swarm efficiency for in-order availability. Leave it off for plain "download the whole thing as fast as possible" jobs; turn it on for progressive playback.

## Verified resume

KiteTorrent never blindly trusts whatever bytes are already on disk. When you restart a partially-downloaded torrent, the session enters `CHECKING` and **rehashes** the on-disk pieces, claiming only the ones whose hashes match the torrent. Corrupt or truncated pieces are simply re-downloaded.

To persist and restore progress, save the session's resume data and feed it back on the next launch:

```kotlin
// before shutdown: capture progress, priorities, and peer state
val params: AddTorrentParams? = session.saveResumeData()
val bytes = params?.let { ResumeData.write(it) }   // serialize to disk yourself

// next launch: rebuild the params and resume
val restored = ResumeData.read(torrent, bytes!!)
val session = engine.addTorrent(torrent, disk, resume = restored)
```

`AddTorrentParams` carries the `haveBlocks` bitfield, file priorities, byte counters, and known peers. On resume the session uses it as a hint and still verifies by hash before counting a piece as complete, so a resume file can never make the session believe it has data it does not.

## How the scheduler works

You do not call any of the machinery below directly: it runs inside the session. It is documented here because it is the heart of the download path, and a faithful port of libtorrent's `request_blocks.cpp`.

### Dynamic per-peer request queues

Each peer connection keeps a pipeline of outstanding block requests so the link never goes idle waiting for a round trip. The depth of that pipeline is computed per peer, per tick:

```
depth = request_queue_time × measured_rate / block_size
```

clamped to `[2, max_out_request_queue]`. A new or slow peer starts shallow and **slow-starts**: the depth grows by one block at a time until the peer's measured download rate stops climbing. A fast peer ends up with a deep queue; a slow one stays shallow, so a single laggard cannot tie up blocks the swarm needs.

The rate that feeds this formula comes from the ported `Stat` accounting. `request_queue_time` and `max_out_request_queue` are read from [`SettingsPack`](engine-settings.md), with libtorrent's defaults.

### Tit-for-tat choking

Upload slots are a finite resource, so the session runs periodic **choke rounds**. It unchokes the peers that are giving you the best download rate (reciprocity: you serve the peers who serve you), plus one rotating **optimistic** slot to give a fresh peer a chance to prove itself. Choked peers are not served. The number of slots and the round interval come from `unchoke_slots_limit` and `unchoke_interval` in settings.

### Snubbing

If a peer holds outstanding requests but sends no payload for `piece_timeout`, it is **snubbed**:

- its request queue collapses to a single block, so it can no longer hoard a deep pipeline;
- the picker flips to **reverse** order for that peer (most-common-first), so a stalled peer stops sitting on the rare pieces everyone else needs;
- its newest blocking request is cancelled, but only **after** a replacement has been requested from someone else, so the picker cannot immediately hand the same block straight back to the snubbed peer.

This is exactly how the session recovers from a peer that connects, claims pieces, and then withholds them.

### True end-game

Near the finish line a download can stall on the last few blocks held by slow peers. When the swarm has no free block left for an otherwise-idle peer, the session enters **end-game**: one busy block is requested from a second peer as well.

- The **first delivery wins**: the block is written once.
- Every other peer still holding that request gets a `cancel`, so no bandwidth is wasted finishing a duplicate.

`strict_end_game_mode` (from settings) gates how aggressively this kicks in while there are still untouched pieces. The whole mechanism is exercised by an integration test that runs against a scripted peer which deliberately withholds blocks.

### Request timeouts

Independently of snubbing, a maintenance tick sweeps for blocks that have been outstanding past `request_timeout` on a slow peer and recycles them back into the picker, so a quietly-dead request can never permanently strand a block.

## Disk backends

The session talks to storage only through the `DiskIo` interface, so you choose where bytes go.

=== "Real files"

    ```kotlin
    import io.github.yuroyami.kitetorrent.session.disk.FileDiskIo
    import io.github.yuroyami.kitetorrent.session.disk.StorageMode
    import kotlinx.coroutines.Dispatchers

    val disk = FileDiskIo(
        storage = torrent.storage,
        basePath = "/downloads",
        dispatcher = Dispatchers.IO,
        filePriorities = null,            // or an IntArray to pre-skip files
        storageMode = StorageMode.SPARSE, // or ALLOCATE
    )
    ```

=== "In-memory (tests)"

    ```kotlin
    import io.github.yuroyami.kitetorrent.session.disk.InMemoryDiskIo

    val disk = InMemoryDiskIo()
    ```

`StorageMode.SPARSE` (the default) lets the filesystem allocate space lazily; `ALLOCATE` reserves the full size up front. `FileDiskIo` does its random access through a tiny `expect`/`actual` (a `RandomAccessFile` on JVM and Android, POSIX on iOS), which is why this backend lives in `:kitetorrent-session` rather than the pure core.

## Shutting down

When you are done with a torrent, shut the session down so its connections and file handles are released:

```kotlin
session.shutdown()
```

To stop everything and capture DHT state for next time, shut the whole engine down gracefully:

```kotlin
val dhtState: ByteArray? = engine.shutdownGraceful()
```

---

For the full type list and signatures, see the [API reference](https://yuroyami.github.io/KiteTorrent/api/).

Next: [Magnet links](magnets.md): start from a magnet URI when you do not have the `.torrent` yet. Or learn what happens once a download completes in [Seeding](seeding.md).
