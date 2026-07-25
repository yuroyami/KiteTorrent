# Downloading Torrents

Add a `.torrent` to the engine, watch pieces land, and control the download. The session runs a full block scheduler: dynamic per-peer request queues, end-game mode, snubbing, and tit-for-tat choking. Each of those terms is explained in [How the scheduler works](#how-the-scheduler-works).

This page covers adding a torrent from a parsed `TorrentInfo`. For magnet links (where the metadata is fetched first), see [Magnet links](magnets.md).

## Adding a torrent

You need three things: a running [`KiteTorrentEngine`](engine-settings.md), a parsed `TorrentInfo`, and a `DiskIo` to store the data.

```kotlin
import kotlinx.coroutines.Dispatchers
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.github.yuroyami.kitetorrent.torrent.TorrentInfo
import io.github.yuroyami.kitetorrent.session.engine.KiteTorrentEngine
import io.github.yuroyami.kitetorrent.session.disk.FileDiskIo
import io.github.yuroyami.kitetorrent.session.tracker.HttpTracker

val engine = KiteTorrentEngine(
    scope,
    httpTracker = HttpTracker(HttpClient(CIO)),
    enableDht = true,
)
engine.start()

val torrent = TorrentInfo.parse(bytes)
val disk = FileDiskIo(torrent.storage, "/downloads", Dispatchers.IO)
val session = engine.addTorrent(torrent, disk, resume = null)
```

The `httpTracker` argument is not optional in practice: it defaults to `null`, and announces to `http://` and `https://` trackers are then skipped without an error. See [Getting started, Step 2](getting-started.md#step-2-create-the-engine) for the details and the ktor dependencies you need.

`FileDiskIo`'s `dispatcher` parameter defaults to `Dispatchers.Default`, which runs blocking file syscalls on the CPU dispatcher. On JVM and Android pass `Dispatchers.IO`, as above.

`addTorrent` returns a `TorrentSession`: the live handle for one torrent. Pass `resume = null` for a fresh download, or an `AddTorrentParams` to resume an existing one (see [Resuming a download](#resuming-a-download) below).

!!! note "Choosing a disk backend"
    `FileDiskIo(storage, basePath, ...)` writes to real files under `basePath`. `InMemoryDiskIo(storage)` keeps everything in RAM and is meant for tests. Both implement the same `DiskIo` interface, so the session does not care which one it gets. They report existing on-disk data differently, which matters on restart. See [Resuming a download](#resuming-a-download), and [Disk backends](#disk-backends) for a comparison.

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
| `CHECKING` | Adopting resume data, or rehashing whatever the disk backend reports as present. With `FileDiskIo` that is nothing unless you call `recheck(full = true)`. |
| `DOWNLOADING` | Actively requesting blocks from peers. |
| `SEEDING` | All wanted pieces verified; the session now only uploads. |
| `PAUSED` | No new peers, no new requests. |

A session that finishes its wanted pieces transitions to `SEEDING` on its own. See [Seeding](seeding.md) for what happens after that.

## Tracking progress

### Overall progress

`progress()` is `suspend fun progress(): Float`. It returns verified pieces divided by the torrent's total piece count. That is a raw piece count, not a byte count. The denominator is every piece in the torrent, whether you asked for it or not. Pieces set to `IGNORE` priority stay in that denominator, so a torrent with any excluded file never reaches `1.0`.

```kotlin
println("${(session.progress() * 100).toInt()}% complete")
println("${session.numPeers()} peers connected")
```

### Per-piece notifications

`onPieceVerified` fires once per piece, after the data has been written to disk **and** its hash has matched the torrent's piece hash. A piece that fails verification is silently re-requested and does not fire the callback.

It is declared `var onPieceVerified: ((Int) -> Unit)?`, a plain non-suspending callback, so the suspending `progress()` cannot be called from inside it. Poll progress from a coroutine instead:

```kotlin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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

The priority integers correspond to `DownloadPriority`: `IGNORE` (0), `LOW`, `NORMAL`, `HIGH`. A file set to `IGNORE` is never requested, but its pieces are still counted by `progress()`.

!!! warning "An ignored file keeps the torrent from ever reporting complete"
    `progress()` is `picker.numHave() / numPieces` and `isSeeding()` is `numHavePieces == numPieces`. Neither subtracts filtered pieces. If any file is set to `IGNORE`, `progress()` stops short of `1.0` and the session never transitions to `TorrentState.SEEDING`, even though every piece you asked for has arrived. The picker itself tracks the correct value in `PiecePicker.isFinished()`, which is `numPieces - numFiltered <= numHave`, but the session does not call it. To detect completion of a filtered download, count the verified pieces you expect and compare that count yourself.

!!! note
    You can also fix file priorities in advance, before the session exists, by passing `filePriorities` to the `FileDiskIo` constructor. That lets the disk layer skip allocating storage for files you never intend to fetch.

### Piece priorities

Priorities can also be set at piece granularity through the session's piece-priority control, which the scheduler reads when it picks the next block. This is what powers selective and ordered downloading; the most common case (in-order delivery) has its own switch, below.

## Sequential download

By default the picker is **rarest-first**: it grabs the least-available pieces first, which is best for swarm health and overall speed. When you would rather stream a file in order (video, a large archive you unpack as it lands), flip the session to sequential:

```kotlin
session.sequentialDownload = true
```

Pieces then arrive in index order instead of rarest-first. `onPieceVerified` reports them in roughly ascending order, so a consumer can start reading the front of the file while the rest is still downloading.

!!! tip
    Sequential download trades a little swarm efficiency for in-order availability. Leave it off for plain "download the whole thing as fast as possible" jobs; turn it on for progressive playback.

## Resuming a download

There are two ways to pick a torrent back up, and they behave differently.

### Fast resume, from saved resume data

Pass an `AddTorrentParams` as `resume` and `start()` takes the `applyResumeData` branch instead of rechecking: the saved piece bitfield is adopted directly, without re-reading the files. This is the normal restart path. The pieces it claims were hash-checked when they were first written.

### Rechecking files already on disk

Without resume data, `start()` calls `recheck()`, which asks the disk backend which pieces look present via `checkExistingFiles()` and rehashes only those. `FileDiskIo.checkExistingFiles()` currently returns an all-false array, so it reports nothing present regardless of what is on disk. Point `FileDiskIo` at a directory holding a complete copy of the torrent and the session will report zero pieces and re-download everything over the top of the existing files.

`KiteTorrentEngine.addTorrent` calls `session.start()` internally, so there is no point at which you can intervene beforehand. The workaround is to force a full recheck afterwards, which rehashes every piece:

```kotlin
val session = engine.addTorrent(torrent, disk)
session.recheck(full = true)   // FileDiskIo reports nothing present without this
```

`recheck(full = true)` builds its own all-true candidate array instead of consulting the disk backend, so it hashes the whole torrent. On a large torrent that is a full read plus a full SHA-1 pass, which is why it is not the default.

`InMemoryDiskIo.checkExistingFiles()` does return a real array, so the plain `recheck()` path works there. That is also why the engine's integration tests, which use the in-memory backend, do not catch this.

### Saving and restoring resume data

To persist and restore progress, save the session's resume data and feed it back on the next launch:

```kotlin
// before shutdown: capture progress, priorities, and peer state
val params: AddTorrentParams? = session.saveResumeData()
val bytes = params?.let { ResumeData.write(it) }   // serialize to disk yourself

// next launch: rebuild the params and resume
val restored = ResumeData.read(torrent, bytes!!)
val session = engine.addTorrent(torrent, disk, resume = restored)
```

`AddTorrentParams` carries the `havePieces` bitfield, the per-piece block progress in `unfinishedPieces`, file and piece priorities, byte counters, and known peers. `applyResumeData` adopts the `havePieces` bits without rehashing them; skipping the full-disk read is the point of fast resume. The pieces were hash-checked when they were originally written, but a resume file that has drifted from the files on disk will not be caught. If you need certainty after a crash or an out-of-band edit, call `session.recheck(full = true)` and pay for the rehash.

## How the scheduler works

You do not call any of the machinery below directly: it runs inside the session. It is documented here because it decides which peer is asked for which block, and that decision drives your download speed.

### Dynamic per-peer request queues

Each peer connection keeps a pipeline of outstanding block requests so the link never goes idle waiting for a round trip. The depth of that pipeline is computed per peer, per tick:

```
depth = request_queue_time × measured_rate / block_size
```

clamped to `[2, max_out_request_queue]`. A new or slow peer starts shallow and **slow-starts**: the depth grows by one block at a time until the peer's measured download rate stops climbing. A fast peer gets a deep queue and a slow peer stays shallow, so one slow peer cannot hold blocks that the swarm needs.

The rate that feeds this formula comes from the `Stat` accounting. `request_queue_time` and `max_out_request_queue` are read from [`SettingsPack`](engine-settings.md), with libtorrent's defaults.

### Tit-for-tat choking

Upload slots are a finite resource, so the session runs periodic **choke rounds**. It unchokes the peers that are giving you the best download rate (reciprocity: you serve the peers who serve you), plus one rotating **optimistic** slot to give a fresh peer a chance to prove itself. Choked peers are not served. The number of slots and the round interval come from `unchoke_slots_limit` and `unchoke_interval` in settings.

### Snubbing

If a peer holds outstanding requests but sends no payload for `piece_timeout`, it is **snubbed**:

- its request queue collapses to a single block, so it can no longer hold a deep pipeline;
- the picker flips to **reverse** order for that peer (most-common-first), so a stalled peer no longer blocks the rare pieces that other peers need;
- its newest blocking request is cancelled, but only **after** a replacement has been requested from someone else, so the picker cannot immediately return the same block to the snubbed peer.

This is exactly how the session recovers from a peer that connects, claims pieces, and then withholds them.

### End-game mode

Near the end of a download, the last few blocks can stall on slow peers. When the swarm has no free block left for an otherwise-idle peer, the session enters **end-game mode**: it requests one already-requested block from a second peer as well.

- The **first delivery wins**: the block is written once.
- Every other peer still holding that request gets a `cancel`, so no bandwidth is wasted finishing a duplicate.

`strict_end_game_mode` (from settings) controls when this starts while untouched pieces remain. The whole mechanism is exercised by an integration test that runs against a scripted peer which deliberately withholds blocks.

### Request timeouts

Snubbing is not the only guard. A maintenance tick looks for blocks that have been outstanding past `request_timeout` on a slow peer. It returns those blocks to the picker, so a silently dead request can never strand a block forever.

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

    val disk = InMemoryDiskIo(torrent.storage)
    ```

    It allocates a `ByteArray` of the torrent's full size in advance, so keep it to small fixtures.

`StorageMode.SPARSE` (the default) lets the filesystem allocate space lazily. `ALLOCATE` reserves the full size in advance. `FileDiskIo` does its random access through a tiny `expect`/`actual` (a `RandomAccessFile` on JVM and Android, POSIX on iOS), which is why this backend lives in `:kitetorrent-session` rather than the pure core.

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
