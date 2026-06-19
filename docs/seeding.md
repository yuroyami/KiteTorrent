# Seeding

Seeding is two jobs. First you turn a set of files into a `.torrent` so the rest of the swarm knows what to ask for. Then you serve the data you already have to peers who want it. This page covers both: building a torrent with `CreateTorrent` (byte-identical to libtorrent), the upload path through `TorrentSession`, web seeds, and how to get the v1/v2/hybrid info-hash right.

If you only want to download, see [Downloading](downloading.md). If you are starting from a magnet link, see [Magnets](magnets.md).

## Building a `.torrent`

A `.torrent` file is a bencoded dictionary: the file list and piece geometry in an `info` dictionary, plus trackers, web seeds, a comment and so on around it. The SHA-1 of that `info` dictionary is the torrent's v1 info-hash, and a v2 torrent hashes a merkle file tree with SHA-256 instead.

`CreateTorrent` builds that structure in pure common code. It lives in the core module, so you can construct a torrent on any target the core supports (Android, iOS, JVM, JS).

!!! note "Hashing is the caller's job"
    `CreateTorrent` deliberately does not read files off disk. The core has no filesystem in `commonMain`, so it never pretends to have one. You read and hash the bytes yourself (with [`Hasher`](core-toolkit.md) for v1 and [`Hasher256`](core-toolkit.md) for v2), then feed the hashes in. This mirrors libtorrent's `set_piece_hashes()` helper exactly.

### The four steps

libtorrent creates a torrent in four steps, and `CreateTorrent` follows the same order:

1. Describe the files and piece geometry.
2. Set torrent properties: trackers, web seeds, DHT nodes, comment.
3. Supply the pre-computed piece hashes.
4. Generate the bencoded result.

### A single-file v1 torrent

Use `CreateTorrent.Builder` to collect the files and the torrent name, then `build()` to get a `CreateTorrent`. For a v1 torrent you set one SHA-1 per piece with `setHash`, then call `generateBuffer()` for the `.torrent` bytes.

```kotlin
import io.github.yuroyami.kitetorrent.torrent.CreateTorrent
import io.github.yuroyami.kitetorrent.crypto.Hasher

val pieceLength = 256 * 1024   // a power of two; 256 KiB here

// 1. describe the files. For a single-file torrent, the one file's
//    path equals the torrent name.
val creator = CreateTorrent.Builder(name = "ubuntu.iso", pieceLength = pieceLength)
    .addFile(relPath = "ubuntu.iso", size = fileBytes.size.toLong())
    .build(v1Only = true)

// 2. set properties
creator.setComment("Built with KiteTorrent")
    .setCreator("KiteTorrent/0.0.1")
    .addTracker("udp://tracker.example.org:1337/announce", tier = 0)

// 3. hash every piece and feed the SHA-1s in
val numPieces = creator.numPieces()
for (index in 0 until numPieces) {
    val start = index.toLong() * pieceLength
    val end = minOf(start + pieceLength, fileBytes.size.toLong())
    val piece = fileBytes.copyOfRange(start.toInt(), end.toInt())
    creator.setHash(index, Hasher.hash(piece, length = piece.size))
}

// 4. generate the .torrent bytes
val torrentBytes: ByteArray = creator.generateBuffer()
```

`generateBuffer()` is just `Bencode.encode(generate())`. If you want to add custom keys before encoding, call `generate()` to get the `Entry` tree, mutate it, then encode it yourself.

!!! tip "Round-trip to verify"
    Parse what you just produced: `TorrentInfo.parse(torrentBytes)`. The resulting `infoHashV1`, file list and piece count should match what you fed in. This is the cheapest possible smoke test for a generated torrent.

### A multi-file torrent

Add more than one file. Paths are relative to the torrent root; the builder stores them as `name/<relative path>` so they round-trip through `TorrentInfo`. The leading root component is dropped in the generated `files[]` list, exactly like libtorrent.

```kotlin
val creator = CreateTorrent.Builder(name = "my-release", pieceLength = 512 * 1024)
    .addFile("README.md", readme.size.toLong())
    .addFile("bin/app", appBinary.size.toLong())
    .addFile("docs/guide.pdf", guide.size.toLong())
    .build(v1Only = true)
```

Pieces span the concatenated byte stream across all files in insertion order, so hash the concatenation, not each file separately. `creator.pieceSize(index)` gives the size of any individual piece (the last one is usually short).

### Properties you can set

All setters are fluent and return the `CreateTorrent`, so you can chain them.

| Method | Effect |
|---|---|
| `addTracker(url, tier)` | Adds a tracker. Lower tiers are tried first. Duplicate URLs are ignored. |
| `addUrlSeed(url)` | Adds a BEP-19 web seed (`url-list`). See [Web seeds](#web-seeds-bep-19) below. |
| `addHttpSeed(url)` | Adds an old-style HTTP seed (`httpseeds`). |
| `addNode(host, port)` | Adds a DHT bootstrap node (`nodes`). |
| `setComment(str)` | Sets the free-text comment. |
| `setCreator(str)` | Sets the `created by` string. |
| `setCreationDate(posixSeconds)` | Sets `creation date`. Omitted by default. |
| `setPrivate(true)` | Sets the BEP-27 private flag: tracker only, no DHT or PEX. |
| `addSimilarTorrent(infoHash)` / `addCollection(name)` | BEP-38 cross-seeding hints. |

!!! note "No clock in the core"
    libtorrent stamps `creation date` with the wall clock at construction. The core has no clock, so KiteTorrent omits the field by default. Set it explicitly with `setCreationDate(...)` if you want it, passing a POSIX timestamp in seconds.

## v1, v2 and hybrid

This is the part most home-grown torrent code gets wrong. The flavour of the torrent you produce is decided by which hashes you set and the flags you pass:

=== "v1 only"

    BitTorrent v1 (BEP-3). One SHA-1 per piece via `setHash`. Force it so a stray v2 hash is rejected:

    ```kotlin
    val creator = builder.build(v1Only = true)
    for (i in 0 until creator.numPieces()) {
        creator.setHash(i, Hasher.hash(piece(i), length = piece(i).size))
    }
    ```

=== "v2 only"

    BitTorrent v2 (BEP-52). One SHA-256 per 16 KiB block, per file. `CreateTorrent.DEFAULT_BLOCK_SIZE` is that 16 KiB. The core folds each file's merkle tree itself:

    ```kotlin
    val creator = builder.build(v2Only = true)
    val block = CreateTorrent.DEFAULT_BLOCK_SIZE   // 16 KiB
    files.forEachIndexed { fileIndex, fileBytes ->
        val leaves = fileBytes.toList()
            .chunked(block)
            .map { Hasher256.hash(it.toByteArray(), length = it.size) }
        creator.setFileV2Leaves(fileIndex, leaves)
    }
    ```

=== "Hybrid"

    Both at once (the modern default; v1 clients see a v1 torrent, v2 clients see a v2 one, and they share a swarm). Set **both** the v1 piece hashes and the v2 block leaves, and pass neither `v1Only` nor `v2Only`:

    ```kotlin
    val creator = builder.build()   // hybrid when both hash sets are complete
    // ... setHash(...) for every v1 piece
    // ... setFileV2Leaves(...) for every file's v2 blocks
    val bytes = creator.generateBuffer()
    ```

The rule `generate()` enforces matches `validate_v1_hashes` / `validate_v2_hashes` in libtorrent: a flavour is produced only when **every** hash for that flavour is set. An all-zero hash is the "unset" sentinel and never counts. If neither a complete v1 set nor a complete v2 set is present, `generate()` throws.

!!! warning "Use `Hasher256`, not the wrong granularity"
    v1 hashes one SHA-1 per *piece*. v2 hashes one SHA-256 per 16 KiB *block*, and the per-piece and per-file merkle roots are computed for you. Mixing those up is the classic way to produce a torrent whose info-hash nobody else agrees with. When in doubt, parse the output back and compare `infoHashV1` / `infoHashV2` against a reference client.

After parsing, `TorrentInfo` tells you what you got: `isV1`, `isV2`, `isHybrid`, plus `infoHashV1` and `infoHashV2`. `wireInfoHash()` gives the 20-byte hash used on the wire and at the tracker (the v1 hash when present, otherwise the truncated v2 hash).

## Serving a torrent

Once a torrent's data is on disk, the engine seeds it like any other torrent. There is no separate "seed" call: you add the torrent, the session rechecks the files, finds it already has every piece, and moves to the `SEEDING` state.

```kotlin
import io.github.yuroyami.kitetorrent.session.engine.KiteTorrentEngine
import io.github.yuroyami.kitetorrent.session.disk.FileDiskIo
import io.github.yuroyami.kitetorrent.session.engine.TorrentState
import io.github.yuroyami.kitetorrent.torrent.TorrentInfo

val engine = KiteTorrentEngine(scope, enableDht = true)
engine.start()

val torrent = TorrentInfo.parse(torrentBytes)
val session = engine.addTorrent(
    torrent,
    FileDiskIo(torrent.storage, basePath = "/srv/downloads", dispatcher = ioDispatcher),
    resume = null,
)

session.onStateChanged = { state ->
    if (state == TorrentState.SEEDING) println("Now seeding ${torrent.name}")
}
```

On `addTorrent` with `resume = null`, the session runs a verified recheck: it hashes the on-disk pieces and claims only the ones whose hash matches the torrent. For a complete copy, every piece verifies and the session goes straight to `SEEDING`. To skip the rehash on a known-good copy, pass saved [resume data](downloading.md) instead of `null`.

!!! tip "Recheck is honest"
    The recheck hashes the bytes; it does not trust the file sizes or the disk layer. If a file was truncated or corrupted, those pieces fail verification and the session re-downloads them instead of serving bad data. That is the same `storage_utils.cpp` recheck behaviour as libtorrent.

### The upload path

Seeding peers go through the same `PeerConnection` machinery as downloading. When a peer connects and is interested, the session may unchoke it and start answering its `request` messages with `piece` data read from `DiskIo`. As the session completes pieces (when seeding from a partial copy, or when a downloader finishes), it sends `have` messages so peers know what it can serve.

Three pieces of state govern who gets served:

- **`uploadSlots`** (libtorrent's `max_uploads`) caps how many peers you upload to at once. Choked peers are not served.
- **`maxPeers`** (`max_connections`) caps total connections for this torrent.
- The **choker** runs a periodic round (`unchoke_interval`) and decides which interested peers fill the upload slots.

```kotlin
session.uploadSlots = 8    // unchoke up to 8 peers
session.maxPeers = 80      // accept up to 80 connections for this torrent
```

### Tit-for-tat choking

You cannot upload to everyone at once, so BitTorrent picks who to serve. KiteTorrent ports libtorrent's `choker.cpp` directly. Each choke round:

1. Ranks interested peers by their recent transfer rate.
2. Unchokes the fastest peers up to `uploadSlots`.
3. Reserves one rotating **optimistic** slot for a peer chosen at random, so newcomers get a chance to prove themselves and the swarm does not ossify.

Everyone else stays choked. When you are seeding (you have nothing to download), "fastest" means the peers downloading from you fastest, which spreads your bandwidth toward the peers that propagate data best. The optimistic slot rotates on its own interval (`optimistic_unchoke_interval`).

All of this is driven by `SettingsPack`. To change the cadence or slot count globally, see [Engine settings](engine-settings.md):

```kotlin
import io.github.yuroyami.kitetorrent.settings.SettingsPack
import io.github.yuroyami.kitetorrent.settings.IntSetting

val settings = SettingsPack()
settings.setInt(IntSetting.UNCHOKE_SLOTS_LIMIT, 8)
settings.setInt(IntSetting.UNCHOKE_INTERVAL, 10)   // seconds between rounds
val engine = KiteTorrentEngine(scope, settings = settings)
```

### Watching upload progress

The session exposes its state and peer count directly:

```kotlin
println("state: ${session.state}")        // CHECKING / DOWNLOADING / SEEDING / PAUSED
println("peers: ${session.numPeers()}")
println("complete: ${session.progress() * 100}%")
```

For per-event detail (a peer connecting, a piece served), drain the engine's alert queue. Alerts like `PeerConnectAlert` and `PeerDisconnectAlert` report connection churn:

```kotlin
for (alert in engine.popAlerts()) {
    when (alert) {
        is PeerConnectAlert -> println("peer connected")
        is PeerDisconnectAlert -> println("peer left")
    }
}
```

## Web seeds (BEP-19)

A web seed is a plain HTTP(S) server that hosts the torrent's files at predictable URLs. Downloaders can pull byte ranges over HTTP in addition to (or instead of) talking to peers, which is handy when the swarm is thin or you want a guaranteed source.

You add web seeds when you **build** the torrent, with `addUrlSeed`. The URL goes into the torrent's `url-list`:

```kotlin
val creator = CreateTorrent.Builder("dataset.bin", pieceLength = 1 shl 20)
    .addFile("dataset.bin", data.size.toLong())
    .build(v1Only = true)
    .addUrlSeed("https://cdn.example.org/files/")
    .addTracker("udp://tracker.example.org:1337/announce")
```

The URLs survive parsing: `TorrentInfo.parse(...).webSeeds` returns the list back. On the download side, the session treats each web seed as a "GetRight" style HTTP source, fetching the byte ranges that cover the pieces it needs, exactly as `web_peer_connection.cpp` does. No extra wiring is required; the engine consumes the torrent's `webSeeds` automatically when it downloads.

!!! note "Web seeds are a download source"
    There is nothing to "turn on" for serving via a web seed: the web seed *is* the HTTP server hosting your files. KiteTorrent's job is to let downloaders use it. You point `addUrlSeed` at wherever those files are reachable and host them with any ordinary HTTP server that supports range requests.

## Putting it together

Build a torrent from a file, write the `.torrent` out, then seed the same data:

```kotlin
import io.github.yuroyami.kitetorrent.torrent.CreateTorrent
import io.github.yuroyami.kitetorrent.torrent.TorrentInfo
import io.github.yuroyami.kitetorrent.crypto.Hasher
import io.github.yuroyami.kitetorrent.session.engine.KiteTorrentEngine
import io.github.yuroyami.kitetorrent.session.disk.FileDiskIo

suspend fun seed(scope: CoroutineScope, fileBytes: ByteArray, savePath: String) {
    val pieceLength = 256 * 1024

    // 1. build a v1 torrent
    val creator = CreateTorrent.Builder("payload.bin", pieceLength)
        .addFile("payload.bin", fileBytes.size.toLong())
        .build(v1Only = true)
        .addTracker("udp://tracker.example.org:1337/announce")
        .setCreator("KiteTorrent/0.0.1")

    for (i in 0 until creator.numPieces()) {
        val start = i * pieceLength
        val end = minOf(start + pieceLength, fileBytes.size)
        val piece = fileBytes.copyOfRange(start, end)
        creator.setHash(i, Hasher.hash(piece, length = piece.size))
    }
    val torrentBytes = creator.generateBuffer()   // distribute this .torrent

    // 2. seed the data the torrent describes
    val engine = KiteTorrentEngine(scope, enableDht = true)
    engine.start()

    val torrent = TorrentInfo.parse(torrentBytes)
    val session = engine.addTorrent(
        torrent,
        FileDiskIo(torrent.storage, basePath = savePath, dispatcher = ioDispatcher),
        resume = null,
    )
    session.uploadSlots = 8
}
```

## See also

- [Downloading](downloading.md): the download path, progress and resume data.
- [Magnets](magnets.md): start from a magnet link instead of a `.torrent`.
- [Core toolkit](core-toolkit.md): `Bencode`, `Hasher`, `TorrentInfo` and `FileStorage` from the pure core.
- [Engine settings](engine-settings.md): choking cadence, slot counts, rate limits and connection caps.
- [API reference](https://yuroyami.github.io/KiteTorrent/api/) for the full signatures.
