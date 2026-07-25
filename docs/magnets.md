# Magnet Links

Start a download from a magnet URI: no `.torrent` file required. A magnet carries the info-hash but not the metadata, so KiteTorrent first fetches the `.torrent` info section from peers over BEP-9 `ut_metadata`, builds a `TorrentInfo`, and then runs an ordinary download. Peers come from the magnet's trackers and, when you enable it, the DHT.

This page covers parsing a magnet, the convenience add (engine finds its own peers), the metadata fetch, and the download that follows. For downloading from a `.torrent` you already have, see [Downloading](downloading.md).

## Parsing a magnet

A magnet is a URI string of the form `magnet:?xt=urn:btih:<infohash>&dn=<name>&tr=<tracker>`. Parse it with `MagnetUri.parseMagnetUri`, which lives in the pure core module:

```kotlin
import io.github.yuroyami.kitetorrent.torrent.MagnetUri

val magnet = MagnetUri.parseMagnetUri(
    "magnet:?xt=urn:btih:0496aa389a5b0d9b8f4e8f0c1d2e3f405162738a&dn=example"
)

println("info-hash: ${magnet.infoHashHex()}")
println("name: ${magnet.displayName}")          // from the dn= parameter, nullable
println("trackers: ${magnet.trackers}")         // from tr= parameters
```

The returned `MagnetLink` exposes everything the URI encoded:

- `infoHashV1`: the BEP-3 SHA-1 info-hash (`Sha1Hash?`), present for `urn:btih:` magnets
- `infoHashV2`: the BEP-52 SHA-256 info-hash (`Sha256Hash?`), present for `urn:btmh:` magnets
- `displayName`: the suggested name (`dn=`), nullable
- `trackers`: tracker URLs (`tr=`)
- `webSeeds`: web-seed URLs (`ws=`, BEP-19)
- `peers`: peer addresses embedded in the magnet (`x.pe=`)
- `dhtNodes`: DHT bootstrap nodes from the magnet (`dht=`), as `Pair<String, Int>`
- `hasV1`, `hasV2`: convenience flags for which info-hash is present

`infoHashHex()` returns the hex of the v2 hash if present, otherwise the v1 hash.

!!! note "v1, v2, and hybrid magnets"
    A `urn:btih:` magnet is BitTorrent v1 (SHA-1). A `urn:btmh:` magnet is v2 (SHA-256). A hybrid magnet carries both. KiteTorrent uses the 20-byte SHA-1 form on the wire and for DHT lookups; for a v2-only magnet it derives that wire hash from the SHA-256 info-hash per BEP-52. Both `xt` parameters and base32-encoded info-hashes are accepted.

### Parsing without exceptions

`parseMagnetUri` throws on a malformed URI. When the input comes from a user or another process, use `parseMagnetUriOrNull`, which returns a `Result` you can inspect:

```kotlin
val result = MagnetUri.parseMagnetUriOrNull(userInput)
if (result.isSuccess) {
    val magnet = result.link!!
    // proceed
} else {
    println("bad magnet: ${result.error}")
}
```

`Result` carries `link: MagnetLink?`, `error: LibtorrentError?`, and `isSuccess: Boolean`.

## Adding a magnet to the engine

`KiteTorrentEngine.addMagnet` does the whole sequence for you: find peers, fetch metadata via `ut_metadata`, build the `TorrentInfo`, and start downloading. It takes a `diskFactory` rather than a `DiskIo` directly, because the storage layout (file names, sizes, piece count) is unknown until the metadata arrives. The factory receives the parsed `TorrentInfo` and returns the `DiskIo` to write into.

The convenience overload discovers peers itself:

```kotlin
import io.github.yuroyami.kitetorrent.session.engine.KiteTorrentEngine
import io.github.yuroyami.kitetorrent.session.disk.FileDiskIo
import io.github.yuroyami.kitetorrent.session.tracker.HttpTracker
import io.github.yuroyami.kitetorrent.torrent.MagnetUri
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

val magnet = MagnetUri.parseMagnetUri("magnet:?xt=urn:btih:0496aa38...")

val session = engine.addMagnet(magnet) { torrent ->
    FileDiskIo(torrent.storage, basePath = "/downloads", dispatcher = Dispatchers.IO, filePriorities = null)
}

if (session == null) {
    println("no peer could supply the metadata")
} else {
    // onPieceVerified is a plain callback, so it cannot call the suspending progress().
    session.onPieceVerified = { piece -> println("piece $piece verified") }
    session.onStateChanged = { state -> println("state: $state") }

    // progress() suspends, so read it from a coroutine.
    scope.launch {
        while (!session.isSeeding()) {
            println("${(session.progress() * 100).toInt()}%")
            delay(1_000)
        }
    }
}
```

`addMagnet` returns `TorrentSession?`. It is `null` when no peer was reachable or no peer would serve the metadata. Once you have a non-null session, it behaves exactly like one created from a `.torrent`: see [Downloading](downloading.md) for progress, piece verification, and resume data.

!!! warning "Without an `HttpTracker`, HTTP announces are skipped"
    `KiteTorrentEngine`'s `httpTracker` parameter defaults to `null`, and every announce path calls it null-safely. An engine built without one skips every `http://` and `https://` tracker announce: no exception, no alert, no log entry. `addMagnet` finds its peers through `announceForPeers`, so a magnet whose trackers are all HTTP discovers nothing and `addMagnet` returns `null` with no indication of why. UDP trackers and the DHT still work.

    Pass `httpTracker = HttpTracker(HttpClient(CIO))` as shown above. The session module depends on `ktor-client-core` as `implementation` rather than `api`, so add `io.ktor:ktor-client-core` and a client engine to your own build: `ktor-client-cio` on JVM and Android, `ktor-client-darwin` on iOS. Ktor version 3.5.1.

!!! tip "Inside the factory"
    The `torrent` passed to your factory is the fully parsed `TorrentInfo` reconstructed from the fetched metadata. You can read `torrent.name`, `torrent.files`, and `torrent.totalSize` to decide the save path or set per-file priorities before returning the `DiskIo`.

### Supplying your own peers

If you have already discovered peers (from your own tracker logic, a cached peer list, or the magnet's embedded `x.pe=` peers), pass them to the explicit overload and skip the engine's discovery:

```kotlin
import io.github.yuroyami.kitetorrent.session.tracker.PeerEndpoint

val peers = listOf(
    PeerEndpoint("203.0.113.7", 51413),
    PeerEndpoint("198.51.100.22", 6881),
)

val session = engine.addMagnet(magnet, peers) { torrent ->
    FileDiskIo(torrent.storage, "/downloads", Dispatchers.IO, filePriorities = null)
}
```

Peers are `PeerEndpoint(host, port)` from the session module. The engine tries each peer in turn, fetches the metadata from the first one that cooperates, then connects to the whole list to download the data.

## How the metadata fetch works

When you call `addMagnet`, the engine performs these steps, all driven by the info-hash the magnet carries:

1. **Find peers.** The convenience overload calls `discoverPeers`, which unions a DHT `get_peers` lookup (when the DHT is up) with announces to the magnet's trackers. The explicit overload uses the peers you provide.
2. **Fetch metadata.** The engine opens a peer connection, completes the BitTorrent handshake with the extension bit set, exchanges an extended handshake (BEP-10), and requests the info dictionary in pieces via `ut_metadata` (BEP-9). It verifies the assembled bytes against the magnet's info-hash before trusting them.
3. **Build the torrent.** The verified info section is spliced verbatim into a bencoded dictionary (preserving the info-hash exactly), the magnet's trackers are attached, and `TorrentInfo.parse` produces the `TorrentInfo` your factory then receives.
4. **Download.** The engine connects to the peer set and runs the normal rarest-first download pipeline: request blocks, write to disk, hash-verify each piece, and announce `have`.

!!! note "Verified before trusted"
    The metadata a peer sends is never trusted blindly. KiteTorrent hashes the reconstructed info section and checks it against the info-hash from the magnet (the SHA-1 wire hash, or the SHA-256 hash for a v2 magnet). A peer that serves the wrong bytes is skipped, and the engine moves on to the next one. This is exercised by the `ut_metadata` magnet-fetch integration test.

## DHT for peer discovery

A magnet often arrives with no peers and only a thin tracker list, or no trackers at all. The DHT closes that gap: it is a distributed key-value store keyed by info-hash, so any peer can look up who else is sharing a torrent without a central tracker.

Enable the DHT on the engine, then bootstrap it into the network before adding the magnet:

```kotlin
val engine = KiteTorrentEngine(
    scope,
    httpTracker = HttpTracker(HttpClient(CIO)),
    enableDht = true,
)
engine.start()

// Join the DHT via well-known router nodes.
engine.bootstrapDht(
    listOf(
        "router.bittorrent.com" to 6881,
        "dht.libtorrent.org" to 25401,
    )
)

val session = engine.addMagnet(magnet) { torrent ->
    FileDiskIo(torrent.storage, "/downloads", Dispatchers.IO, filePriorities = null)
}
```

With `enableDht = true` and a successful bootstrap, the convenience `addMagnet` pools DHT-discovered peers together with any tracker peers automatically. You do not call the DHT yourself, because `discoverPeers` issues the `get_peers` lookup for you.

If you need the peer list directly, for example to inspect it or feed it to the explicit `addMagnet` overload, call `discoverPeers`:

```kotlin
val peers = engine.discoverPeers(magnet.infoHashV1!!, magnet.trackers)
println("found ${peers.size} peers")
```

`discoverPeers(infoHash, trackers)` returns a deduplicated `List<PeerEndpoint>` from the DHT and the given trackers. A failure in any single source is ignored, so you get the union of whatever responded.

!!! warning "Bootstrap before you discover"
    A freshly constructed DHT node knows no other nodes, so a lookup against it returns nothing. Always `bootstrapDht(...)` (and give it a moment to populate its routing table) before relying on the DHT for discovery. Without `enableDht = true`, the engine has no DHT at all and peer discovery falls back to trackers only.

To carry DHT routing state across runs (so you do not bootstrap from cold every time), save and restore it:

```kotlin
val state: ByteArray? = engine.saveDhtState()   // persist on shutdown
// ...next launch:
if (state != null) engine.restoreDhtState(state)
```

## Putting it together

A complete magnet download, from URI to verified pieces, using the DHT for discovery:

```kotlin
import io.github.yuroyami.kitetorrent.session.engine.KiteTorrentEngine
import io.github.yuroyami.kitetorrent.session.disk.FileDiskIo
import io.github.yuroyami.kitetorrent.session.tracker.HttpTracker
import io.github.yuroyami.kitetorrent.torrent.MagnetUri
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

suspend fun fetch(scope: CoroutineScope, uri: String, saveDir: String) {
    val engine = KiteTorrentEngine(
        scope,
        httpTracker = HttpTracker(HttpClient(CIO)),
        enableDht = true,
    )
    engine.start()
    engine.bootstrapDht(listOf("router.bittorrent.com" to 6881))

    val magnet = MagnetUri.parseMagnetUri(uri)

    val session = engine.addMagnet(magnet) { torrent ->
        println("metadata in: ${torrent.name}, ${torrent.numFiles} files, ${torrent.totalSize} bytes")
        FileDiskIo(torrent.storage, saveDir, Dispatchers.IO, filePriorities = null)
    } ?: run {
        println("could not fetch metadata for ${magnet.infoHashHex()}")
        return
    }

    // onPieceVerified is a plain callback, so it cannot call the suspending progress().
    session.onPieceVerified = { piece -> println("piece $piece verified") }
    session.onStateChanged = { state -> println("-> $state") }

    // progress() suspends, so read it from a coroutine.
    scope.launch {
        while (!session.isSeeding()) {
            println("${(session.progress() * 100).toInt()}%")
            delay(1_000)
        }
    }
}
```

## Next steps

<div class="grid cards" markdown>

- :material-download: **[Downloading](downloading.md)** picks up where the metadata fetch leaves off: progress, piece verification, file priorities, and resume data.
- :material-upload: **[Seeding](seeding.md)** serves a finished torrent and explains the choking machinery.
- :material-cog: **[Engine settings](engine-settings.md)** covers `SettingsPack`, rate limits, connection caps, and proxies.
- :material-book-open-variant: Full signatures live in the [API reference](https://yuroyami.github.io/KiteTorrent/api/).

</div>
