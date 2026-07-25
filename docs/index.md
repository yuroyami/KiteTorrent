# KiteTorrent

A BitTorrent engine written entirely in Kotlin. It is a Kotlin reimplementation of [libtorrent](https://github.com/arvidn/libtorrent) (Rasterbar), with no JNI, no cinterop and no bundled native binary. The same `.kt` runs on Android, iOS and the desktop JVM. The parts that do not need a socket also run in the browser.

```kotlin
import io.github.yuroyami.kitetorrent.session.disk.FileDiskIo
import io.github.yuroyami.kitetorrent.session.engine.KiteTorrentEngine
import io.github.yuroyami.kitetorrent.session.tracker.HttpTracker
import io.github.yuroyami.kitetorrent.torrent.TorrentInfo
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.Dispatchers

val engine = KiteTorrentEngine(
    scope,
    // Leave this null and every http:// and https:// announce is skipped, silently.
    httpTracker = HttpTracker(HttpClient(CIO)),
    enableDht = true,
)
engine.start()

val torrent = TorrentInfo.parse(bytes)
val session = engine.addTorrent(
    torrent,
    FileDiskIo(torrent.storage, "/downloads", Dispatchers.IO),
)

// onPieceVerified is a plain callback, so it cannot call the suspending progress().
session.onPieceVerified = { piece -> println("piece $piece verified") }
scope.launch {
    while (!session.isSeeding()) {
        println("${(session.progress() * 100).toInt()}%")
        delay(1_000)
    }
}
```

!!! warning "Two defaults fail silently"
    `httpTracker` defaults to `null`, and every HTTP or HTTPS announce is a null-safe call. With that default there is no exception, no alert and no log, and a torrent whose trackers are all HTTP finds no peers. `FileDiskIo` reports nothing already on disk, so it cannot resume or seed an existing copy without an explicit `session.recheck(full = true)`. Both are covered in [Getting started](getting-started.md) and [Seeding](seeding.md).

<div class="grid cards" markdown>

- :material-rocket-launch: **New here?** [Get started in a few minutes](getting-started.md)
- :material-book-open-variant: **Browse the guides** below, or jump to the [API reference](https://yuroyami.github.io/KiteTorrent/api/)

</div>

## The module split

BitTorrent is networking, concurrency and disk I/O, and none of those have a stdlib-only equivalent in Kotlin. So KiteTorrent splits along that line:

| Module | Nature | Stack | Targets |
|---|---|---|---|
| **`:kitetorrent`** | Pure computation: bencoding, hashing, parsing, codecs, the piece picker, crypto | `kotlin-stdlib` only | Android, iOS, JVM, JS |
| **`:kitetorrent-session`** | The live session: sockets, timers, disk, trackers, DHT | `+ coroutines, ktor-network, kotlinx-io` | Android, iOS, JVM |

The core never pretends to open a socket. There is no stdlib-only way to do that. Anything that genuinely needs the network lives in the session module, on coroutines and `ktor-network`, and the boundary between the two is explicit. The core gives you everything that is pure: parse a `.torrent`, decode a magnet, hash a piece, pick the next block. The session module wires that into a running download.

## Install

KiteTorrent is published as **`0.0.1-SNAPSHOT`** and consumed via **`mavenLocal`** today. It is not on Maven Central yet.

=== "Pure core only"

    ```kotlin
    kotlin {
        sourceSets {
            commonMain.dependencies {
                implementation("io.github.yuroyami:kitetorrent:0.0.1-SNAPSHOT")
            }
        }
    }
    ```

=== "Core + live engine"

    ```kotlin
    kotlin {
        sourceSets {
            commonMain.dependencies {
                // session brings :kitetorrent in transitively
                implementation("io.github.yuroyami:kitetorrent-session:0.0.1-SNAPSHOT")
            }
        }
    }
    ```

Make sure `mavenLocal()` is in your `repositories { }` block. The core's only dependency is `kotlin-stdlib`. The session module adds `kotlinx.coroutines` 1.11.0, `ktor-network` 3.5.1, `ktor-client-core` 3.5.1 and `kotlinx-io-core` 0.9.1.

`ktor-client-core` is an `implementation` dependency, so it is not on your compile classpath. To construct an `HttpTracker` you also need `io.ktor:ktor-client-core:3.5.1` plus a client engine of your own: `ktor-client-cio` on JVM and Android, `ktor-client-darwin` on iOS.

!!! warning "No browser engine"
    `:kitetorrent` targets the browser (JS), but `:kitetorrent-session` does not. Browsers have no raw TCP/UDP sockets, so the live engine cannot run there. You can still parse torrents, decode magnets and hash data in the browser with the core module alone.

## What works today

KiteTorrent **downloads, seeds and starts from magnet links, end-to-end.** The JVM path runs **567 tests**, of which 566 pass (`Socks5UdpTest` times out reproducibly). They include loopback integration tests over real sockets:

- a TCP download from a seeder
- two engines exchanging a torrent, in plaintext, with MSE forced, and again entirely over µTP
- a `ut_metadata` magnet fetch, and a full magnet download between two engines
- a v2-only torrent
- the proxy suite
- adversarial runs against a scripted peer that withholds blocks

No workflow runs these tests. `.github/workflows/` builds the docs only.

**Pure core (`:kitetorrent`):**

- **Bencoding.** `Bencode` (canonical, byte-sorted keys) and `Bdecode` to a queryable `BdecodeNode`.
- **Crypto.** SHA-1/256/512, ed25519 (RFC 8032 validated), RC4, Diffie-Hellman and MSE, all on a from-scratch pure-Kotlin big integer.
- **Torrents.** `TorrentInfo` and `FileStorage` for v1, v2 and hybrid with correct info-hashes, `CreateTorrent`, `MagnetUri`, merkle trees and resume data.
- **Protocol and algorithms.** The peer wire-protocol codec, the `PiecePicker` with its full option set, the peer list, the IP filter and client-ID parsing.
- **DHT data.** Node IDs (BEP-42), a k-bucket routing table, KRPC messages and mutable/immutable storage (BEP-44).

**Live engine (`:kitetorrent-session`):**

- **`PeerConnection`.** The full BitTorrent handshake and message loop, inbound and outbound.
- **`TorrentSession`.** Download and seed, driven by the `request_blocks` scheduler: dynamic per-peer request queues, end-game mode, snubbing, tit-for-tat choking, verified resume, request timeouts, piece and file priorities, sequential download and pause/resume. [Downloading](downloading.md) explains each of those terms.
- **Magnet links.** `addMagnet` fetches metadata via `ut_metadata` (BEP-9/10), then downloads.
- **Transports.** TCP and µTP (BEP-29, a UDP-based transport), with outgoing dials trying µTP and falling back to TCP. µTP runs LEDBAT congestion control against a 100 ms target delay, an RTO with exponential backoff, go-back-N resend, SACK, duplicate-ACK fast resend and Nagle coalescing.
- **Encryption.** MSE in both directions, with plaintext sniffing on inbound connections, selected by `out_enc_policy` and `in_enc_policy`.
- **Proxies.** SOCKS5 (including UDP ASSOCIATE, so the DHT and µTP are relayed too), SOCKS4/4a and HTTP CONNECT.
- **BitTorrent v2.** `hash_request` / `hashes` / `hash_reject` are served and consumed, with received leaves folded into per-file merkle trees.
- **Rate limiting.** Session-wide and per-torrent upload/download caps, enforced on the send path and via receive back-pressure.
- **Discovery.** HTTP and UDP trackers, a live DHT node, web seeds (BEP-19: plain HTTP servers that host the same files), and UPnP plus NAT-PMP port mapping.

See **[About and status](about.md)** for the known defects and the current limits.

## Guides

| | |
|---|---|
| **[Getting started](getting-started.md)** | Stand up an engine and run your first download, step by step. |
| **[Downloading](downloading.md)** | `addTorrent`, progress, piece verification, resume data. |
| **[Magnets](magnets.md)** | Parse a magnet, fetch metadata, then download. |
| **[Seeding](seeding.md)** | Serve a finished torrent and the choking machinery. |
| **[Core toolkit](core-toolkit.md)** | Bencode, hashing, `TorrentInfo`, `CreateTorrent` from the pure core. |
| **[Engine settings](engine-settings.md)** | `SettingsPack`, rate limits, connection caps, proxies. |
| **[Recipes](recipes.md)** | Copy-paste patterns for common tasks. |
| **[Platform support](platforms.md)** | What runs where, and why the session module skips the browser. |

## Status

KiteTorrent is pre-1.0 and actively developed. Downloading, seeding and magnet links all work end-to-end. Correctness is anchored to published reference data: FIPS vectors for hashing, RFC 8032 for ed25519, real torrents whose info-hashes are cross-checked against an independent implementation, and BEP golden bytes. The known defects and the current limits are listed in **[About and status](about.md)**: there is no threaded disk cache, the alert catalogue has 51 classes, µTP does no path-MTU probing, and `FileDiskIo` cannot report data already on disk.

This is an independent reimplementation, distributed under BSD-3-Clause. It is not affiliated with or endorsed by the libtorrent project.
