# The core toolkit

The `:kitetorrent` module is a usable BitTorrent toolkit on its own. It depends on `kotlin-stdlib` and nothing else, so everything on this page runs unchanged on Android, iOS, the desktop JVM, and the browser (`js(IR)`). No sockets, no disk, no coroutines: just the pure computation that BitTorrent is built from.

This page covers what the core gives you: bencoding, hashing and crypto, `.torrent` and magnet parsing, the piece picker and wire codec, and the DHT primitives. When you want a live download, the [session engine](getting-started.md) builds on top of all of this.

!!! note "Two modules, one boundary"
    `:kitetorrent` is pure and portable. The networking session lives in a separate artifact, `:kitetorrent-session`, because there is no stdlib-only way to open a socket. The split is deliberate: the core never pretends to do I/O. See [Platform support](platforms.md) for what runs where.

## Bencoding

BitTorrent's serialization format is bencode. KiteTorrent gives you three layers: `Entry` (an in-memory tree you build and mutate), `Bencode` (the canonical encoder), and `Bdecode` (a parser that produces a queryable `BdecodeNode`).

### Building and encoding

`Entry` is a sealed class with one case per bencode type. Build a tree, then encode it to bytes:

```kotlin
import io.github.yuroyami.kitetorrent.bencode.Entry
import io.github.yuroyami.kitetorrent.bencode.Bencode

val dict = Entry.Dict(mutableMapOf())
dict["announce"] = Entry.Str("http://tracker.example/announce".encodeToByteArray())
dict["creation date"] = Entry.Int(1700000000L)

val bytes: ByteArray = Bencode.encode(dict)
```

`Bencode.encode` is canonical: dictionary keys are emitted byte-sorted, the way the spec and every interoperable client expect. That property is what makes an info-dictionary hash to the same value everywhere, so you never have to think about key order yourself.

The `Entry` cases are:

| Case | Holds | Accessor |
|---|---|---|
| `Entry.Int` | `val value: Long` | `integer()` |
| `Entry.Str` | `val bytes: ByteArray` | `string()` / `stringBytes()` / `text` |
| `Entry.ListEntry` | `val items: MutableList<Entry>` | `list()` |
| `Entry.Dict` | `val map: MutableMap<String, Entry>` | `dict()` |
| `Entry.Preformatted` | `val bytes: ByteArray` (raw, emitted verbatim) | |
| `Entry.Undefined` | nothing (absent value) | |

Convenience accessors read typed values without a manual cast, and `get` / `set` / `findKey` work on dictionaries:

```kotlin
val name: String = dict["info"].dict()["name"]?.string() ?: "unknown"
val announce = dict.findKey("announce")?.string()
```

!!! tip "Preformatted is for round-tripping"
    `Entry.Preformatted` holds bytes that are written through untouched. Use it when you need a sub-section (for example a raw `info` dictionary) to survive a re-encode bit-for-bit, instead of decoding and re-encoding it.

### Decoding

`Bdecode.decode` parses bytes into a `BdecodeNode`, a read-only tree you query in place:

```kotlin
import io.github.yuroyami.kitetorrent.bencode.Bdecode

val root = Bdecode.decode(bytes)
```

The decoder is bounded against hostile input. `depthLimit` (default 100) caps nesting and `tokenLimit` (default 2,000,000) caps the number of tokens, so a malformed or adversarial blob cannot exhaust memory:

```kotlin
val root = Bdecode.decode(bytes, depthLimit = 50, tokenLimit = 100_000)
```

When you would rather branch than catch, use the nullable variant:

```kotlin
val root = Bdecode.decodeOrNull(bytes) ?: return  // not valid bencode
```

### dataSection() and info-hashing

The reason `BdecodeNode` matters for torrents is `dataSection()`. It hands you the exact byte range a node occupies in the original buffer, with no re-encoding. That is precisely what an info-hash needs: the v1 info-hash is the SHA-1 of the raw `info` dictionary bytes exactly as they appeared on disk, not of a re-serialized copy.

```kotlin
import io.github.yuroyami.kitetorrent.crypto.Hasher

val root = Bdecode.decode(torrentFileBytes)
val info = root.dictFind("info")
val infoBytes = info.dataSection()           // the original bytes, verbatim
val infoHash = Hasher.hash(infoBytes)        // SHA-1 → the v1 info-hash
```

!!! note
    Re-encoding the `info` dictionary and hashing that would silently change the hash whenever a producer used non-canonical key order. `dataSection()` sidesteps the whole problem by hashing what was actually there. In practice you rarely call it by hand: [`TorrentInfo`](#torrent-metadata) does the info-hashing for you.

## Hashing and crypto

The core ships a from-scratch crypto stack. There is no platform `MessageDigest`, no BouncyCastle, no native bindings: the algorithms are pure Kotlin so they are identical on every target, and they are validated against published vectors (FIPS for the hashes, RFC 8032 for ed25519).

### Digests

A hash value is a `Digest32`, a fixed-size byte vector with the bit operations BitTorrent needs. `Sha1Hash` and `Sha256Hash` are type aliases for it, so a 20-byte info-hash and a 32-byte one share the same API:

```kotlin
import io.github.yuroyami.kitetorrent.Digest32

val a = Digest32.sha1("hello".encodeToByteArray())
val b = Digest32.sha256("hello".encodeToByteArray())

println(a.toHex())                  // lowercase hex
val distance = a xor a              // XOR metric (used by the DHT)
println(distance.isAllZeros())      // true
```

`Digest32` carries the operations the rest of the library leans on:

- Bytes: `size`, `get(i)`, `toByteArray()`, `toHex()`, `isAllZeros()`
- Bitwise: `xor`, `and`, `or`, `inv()`
- Bit math: `countLeadingZeroes()`, `shl(bits)`, `shr(bits)`
- Construction: `Digest32.of(bytes, offset, length)`, `Digest32.fromHex(hex)`, `Digest32.zeros(sizeBytes)`, `Digest32.max(sizeBytes)`, `Digest32.sha1(bytes)`, `Digest32.sha256(bytes)`

`countLeadingZeroes()` and `xor` are exactly what the [DHT routing table](#dht-primitives) uses to measure Kademlia distance, which is why they live on the digest type itself.

### Hashers

For one-shot hashing, the companion `hash` is the shortest path. For streaming (hashing a piece as data arrives, without buffering it whole) build up a `Hasher` and call `final()`:

```kotlin
import io.github.yuroyami.kitetorrent.crypto.Hasher
import io.github.yuroyami.kitetorrent.crypto.Hasher256

// one-shot
val sha1: Sha1Hash = Hasher.hash(data)
val sha256: Sha256Hash = Hasher256.hash(data)

// streaming
val h = Hasher()
h.update(block1, 0, block1.size)
h.update(block2, 0, block2.size)
val pieceHash = h.final()
```

`Hasher` produces SHA-1 (v1 pieces and info-hashes); `Hasher256` produces SHA-256 (v2 merkle leaves and info-hashes).

### The wider crypto set

Beyond the digests, the `crypto` package holds the primitives the Message Stream Encryption (MSE) and DHT layers need, each a pure-Kotlin implementation on a from-scratch big-integer:

| Type | What it is |
|---|---|
| `Sha1`, `Sha256`, `Sha512` | the raw hash cores |
| `Ed25519` | RFC 8032 signing and verification (BEP-44 mutable items) |
| `Rc4` | the stream cipher MSE encrypts the wire with |
| `DiffieHellman` | the MSE key exchange |
| `MseCrypto` | the MSE handshake glue |

```kotlin
import io.github.yuroyami.kitetorrent.crypto.Ed25519

val keypair = Ed25519.generateKeyPair()
val sig = Ed25519.sign(message, keypair.privateKey)
val ok = Ed25519.verify(sig, message, keypair.publicKey)
```

!!! note "These primitives drive the live wire"
    The session module runs the MSE handshake in both directions on top of these types: `Mse.initiate` for outgoing connections, `Mse.accept` for incoming ones, with `Mse.looksLikePlaintextHandshake` deciding which path an inbound connection takes. Whether encryption is forced, preferred, or refused comes from the `OUT_ENC_POLICY` and `IN_ENC_POLICY` settings. See [Engine settings](engine-settings.md).

## Torrent metadata

`TorrentInfo` is the parsed form of a `.torrent` file. It understands BitTorrent v1, v2, and hybrid torrents, and computes the correct info-hashes for each.

### Parsing a .torrent

```kotlin
import io.github.yuroyami.kitetorrent.torrent.TorrentInfo

val torrent = TorrentInfo.parse(torrentFileBytes)

println(torrent.name)                  // display name
println("${torrent.numPieces} pieces of ${torrent.pieceLength} bytes")
println("total ${torrent.totalSize} bytes across ${torrent.numFiles} files")
println("info-hash ${torrent.infoHashHex()}")
```

`TorrentInfo.parse` takes the whole file. If you already hold a decoded tree, `TorrentInfo.fromRoot(root)` takes a `BdecodeNode` instead.

### Format detection and info-hashes

A torrent can be v1, v2, or both at once. The flags tell you which, and the hashes are nullable accordingly:

```kotlin
when {
    torrent.isHybrid -> println("v1 + v2 hybrid")
    torrent.isV2     -> println("v2 only")
    torrent.isV1     -> println("v1 only")
}

val v1: Sha1Hash?   = torrent.infoHashV1   // present on v1 and hybrid
val v2: Sha256Hash? = torrent.infoHashV2   // present on v2 and hybrid
println("meta version ${torrent.metaVersion}")
```

`wireInfoHash()` returns the hash to put on the wire (the v1 hash for v1/hybrid, otherwise the truncated v2 hash), and `isV2LayoutValid()` checks that a v2 file tree is well-formed.

### Trackers, web seeds, and the rest

`TorrentInfo` surfaces everything the file carries:

```kotlin
torrent.trackers          // List<List<String>>, tiered announce lists
torrent.announceTiers     // same, named per the BEP-12 model
torrent.webSeeds          // List<String>, BEP-19 URL seeds
torrent.dhtNodes          // List<Pair<String, Int>>, host/port bootstrap nodes
torrent.isPrivate         // BEP-27 private flag
torrent.comment           // String?
torrent.createdBy         // String?
torrent.creationDate      // Long? (unix seconds)
torrent.allTrackers()     // flattened List<String> across all tiers
```

### Files and per-piece hashes

The `storage` is a `FileStorage` and the `files` list is `FileEntry` records:

```kotlin
import io.github.yuroyami.kitetorrent.torrent.FileStorage

for (file in torrent.files) {
    println("${file.path}  ${file.size} bytes @ offset ${file.offset}")
    if (file.isPadFile) println("  (padding)")
    file.symlinkTarget?.let { println("  -> $it") }
}

// per-piece hashes (nullable: a v2-only torrent has no v1 piece hashes)
val pieceV1: Sha1Hash?   = torrent.pieceHashV1(0)
val pieceV2: Sha256Hash? = torrent.pieceHashV2(0)
```

`FileEntry` carries `path`, `size`, `offset`, `name`, `piecesRoot` (the v2 per-file merkle root), `isPadFile`, `symlinkTarget`, `isExecutable`, and `isHidden`.

`FileStorage` is the geometry of the torrent, independent of any download. It maps between files and pieces:

```kotlin
val storage: FileStorage = torrent.storage
storage.isValid()                 // structural sanity check
storage.fileForPiece(piece = 5)   // which file index a piece lands in
storage.firstPieceOf(fileIndex = 0)
storage.fileNumPieces(fileIndex = 0)
storage.isSingleFile              // single-file vs multi-file layout
```

The same `FileStorage` is what the session's `FileDiskIo` uses to lay files out on disk, so the geometry you read here is exactly the geometry that gets written.

### Creating a torrent

`CreateTorrent` builds a `.torrent` from a file layout, producing bytes that are byte-identical to what libtorrent's `create_torrent` would emit for the same input. See [Seeding](seeding.md) for the end-to-end flow.

## Magnet links

`MagnetUri` parses and builds `magnet:` URIs. A parsed link is a `MagnetLink`, which may carry a v1 hash, a v2 hash, or both.

```kotlin
import io.github.yuroyami.kitetorrent.torrent.MagnetUri

val link = MagnetUri.parseMagnetUri(
    "magnet:?xt=urn:btih:0496aa38a8...&dn=example&tr=http://tracker.example/announce"
)

println(link.displayName)        // "example", from dn=
println(link.infoHashHex())
link.infoHashV1                  // Sha1Hash?  (from btih)
link.infoHashV2                  // Sha256Hash? (from btmh)
link.trackers                    // List<String>, from tr=
link.webSeeds                    // from ws=
link.peers                       // from x.pe=
link.dhtNodes                    // from dht= entries
link.selectOnly                  // Set<Int>? selected file indices (so=)
```

When the input might be malformed and you want a reason rather than an exception, use the result-typed overload:

```kotlin
val result = MagnetUri.parseMagnetUriOrNull(userInput)
if (result.isSuccess) {
    val link = result.link!!
} else {
    println("bad magnet: ${result.error}")
}
```

Going the other way, build a magnet from a parsed torrent or from raw parts:

```kotlin
val uri = MagnetUri.makeMagnetUri(torrent)

val custom = MagnetUri.makeMagnetUri(
    infoHashV1 = torrent.infoHashV1,
    infoHashV2 = null,
    displayName = torrent.name,
    trackers = torrent.allTrackers(),
    webSeeds = emptyList(),
    dhtNodes = emptyList(),
    peers = emptyList(),
)
```

`MagnetUri.isValidTrackerUrl(url)` validates a tracker URL before you put it in a link. To hand a parsed `MagnetLink` to the engine and actually download it, see [Magnet links](magnets.md).

## Piece picking and algorithms

The piece picker decides which block to ask which peer for, and it is the part of BitTorrent that most affects swarm health. KiteTorrent ports libtorrent's `piece_picker` whole, with the full option set, as a pure component you can drive yourself.

`PiecePicker` supports rarest-first, reverse (most-common-first, for snubbed peers), sequential, prioritize-partials, and the end-game busy tail, with the same bit values as upstream. A request is a `PieceBlock`:

```kotlin
import io.github.yuroyami.kitetorrent.picker.PieceBlock
import io.github.yuroyami.kitetorrent.picker.DownloadPriority
import io.github.yuroyami.kitetorrent.picker.PieceState

// PieceBlock: which piece, which 16 KiB block within it
val block = PieceBlock(piece = 12, offset = 0, length = 16384)
```

`DownloadPriority` is the per-piece (or per-file) priority knob: `IGNORE`, `LOW`, `NORMAL`, `HIGH`. `IGNORE` means "do not download this", which is how file selection works. `PieceState` tracks a piece through `NONE`, `REQUESTED`, `PARTIAL`, `FINISHED`.

!!! tip
    You rarely instantiate `PiecePicker` directly: the [`TorrentSession`](getting-started.md) owns one and feeds it peer bitfields, request timeouts, and choke state. The types here are useful when you want to reason about (or test) picking in isolation.

The `Bitfield` type backs all of this: an efficient bit vector that tracks which pieces a peer (or you) has. It is what a `have` message and a peer's initial bitfield decode into.

## Protocol and wire

The peer wire protocol is fully codified in the core, separate from any socket. You can build and parse every message without a connection, which is what makes it testable.

`Handshake` is the BitTorrent handshake codec: the 68-byte exchange that opens every peer connection, carrying the protocol string, reserved-extension bits, info-hash, and peer ID. `PeerMessage` is a sealed class covering the post-handshake message stream (choke, unchoke, interested, have, bitfield, request, piece, cancel, and the extension messages).

```kotlin
import io.github.yuroyami.kitetorrent.protocol.Handshake
import io.github.yuroyami.kitetorrent.protocol.PeerMessage
```

Because these are pure encode/decode types, the session module ([`PeerConnection`](getting-started.md)) is a thin loop on top: read bytes, decode a `PeerMessage`, act, encode a reply. Round-tripping every message type is covered by the core test suite.

### Peer management and IP filtering

The core also holds the bookkeeping side of peers, independent of live sockets:

- `TorrentPeer`: one peer's record (`host`, `port`, `address`), with the trust counters libtorrent uses to decide who to dial and who to drop (`connectable`, `failcount` which saturates at 31, `trust_points`, `source`).
- `PeerList`: a sorted repository of peers for a torrent.
- `PeerAddress`: an IPv4 or IPv6 address representation.
- `IpFilter`: range-based access control.

```kotlin
import io.github.yuroyami.kitetorrent.peer.IpFilter

val filter = IpFilter()
filter.addRule("10.0.0.0", "10.255.255.255", flags = 1)  // block a range
val verdict = filter.access("10.1.2.3")                  // nonzero = blocked
```

### Identifying clients

`Fingerprint` parses a peer ID into a client name and version, so you can tell a µTorrent from a Transmission from a libtorrent. `Hex` and `util.Base32` handle the encodings BitTorrent uses (hex info-hashes, base32 magnet hashes), and `util.UrlEscape` handles tracker query encoding.

## DHT primitives

The distributed hash table lets clients find peers without a tracker. The core holds the pure half of the DHT: the data structures and message codecs, with the live node living in the session module.

A `NodeId` is a 160-bit identifier (a `Digest32` alias), and BEP-42 ties a node's ID to its external IP so the table resists poisoning. `RoutingTable` is the k-bucket structure that organizes known nodes by XOR distance from your own ID, the same distance metric `Digest32.xor` and `countLeadingZeroes()` compute.

```kotlin
import io.github.yuroyami.kitetorrent.dht.NodeId
import io.github.yuroyami.kitetorrent.dht.RoutingTable
```

`DhtMessage` is a sealed class over the KRPC message types (`ping`, `find_node`, `get_peers`, `announce_peer`, and the BEP-44 `get`/`put`). `DhtStorage` holds what a node serves to others: immutable items (keyed by their own hash) and mutable items (signed with ed25519, BEP-44).

!!! note "Primitives, not a running node"
    Everything here is data and codecs. Bootstrapping, traversal, and token management need UDP and timers, so the live `DhtNode` lives in `:kitetorrent-session`. The core gives you the pieces to build, parse, and reason about DHT traffic offline.

## Where to go next

<div class="grid cards" markdown>

- :material-rocket-launch: **Want a real download?** [Get started with the engine](getting-started.md)
- :material-book-open-variant: **Every symbol** is in the [API reference](https://yuroyami.github.io/KiteTorrent/api/)

</div>

| | |
|---|---|
| **[Getting started](getting-started.md)** | Add a torrent and watch it complete. |
| **[Downloading](downloading.md)** | The session engine, peers, and progress. |
| **[Magnet links](magnets.md)** | Fetch metadata from a magnet and download. |
| **[Seeding](seeding.md)** | Create a torrent and serve it. |
| **[Engine settings](engine-settings.md)** | `SettingsPack`, rate limits, the connection cap. |
| **[Platform support](platforms.md)** | What runs where, and why. |
| **[About / status](about.md)** | What is ported, what is not, and what is next. |

## Install

The core is one dependency with no transitive runtime deps beyond `kotlin-stdlib`. Today it is published as `0.0.1-SNAPSHOT` and consumed from your local Maven repository (`mavenLocal()`); it is not on Maven Central yet.

=== "Kotlin (KMP)"

    ```kotlin
    repositories {
        mavenLocal()
        mavenCentral()
    }

    kotlin {
        sourceSets {
            commonMain.dependencies {
                implementation("io.github.yuroyami:kitetorrent:0.0.1-SNAPSHOT")
            }
        }
    }
    ```

=== "Android / JVM"

    ```kotlin
    repositories {
        mavenLocal()
        mavenCentral()
    }

    dependencies {
        implementation("io.github.yuroyami:kitetorrent:0.0.1-SNAPSHOT")
    }
    ```

The core targets Android, iOS (arm64, simulator-arm64, x64), the JVM, and `js(IR)`. The live engine (`:kitetorrent-session`) drops the JS target, because browsers have no raw sockets. See [Platform support](platforms.md).
