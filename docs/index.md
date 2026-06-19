# KiteTorrent

**One pure-Kotlin BitTorrent engine for Kotlin Multiplatform.** A from-scratch port of [libtorrent](https://github.com/arvidn/libtorrent) (Rasterbar) to Kotlin: no JNI, no cinterop, no platform renderers. The same `.kt` runs on Android, iOS, desktop JVM and the browser.

```kotlin
// Spin up an engine, add a .torrent or a magnet, and download.
val engine = KiteTorrentEngine(scope, enableDht = true)
engine.start()

val torrent = TorrentInfo.parse(bytes)
val session = engine.addTorrent(torrent, FileDiskIo(torrent.storage, "/downloads"), resume = null)
session.onPieceVerified = { piece -> println("piece $piece, ${session.progress() * 100}%") }

// ...or start from a magnet link
val magnet = MagnetUri.parseMagnetUri("magnet:?xt=urn:btih:0496aa38...")
engine.addMagnet(magnet, diskFactory = { t -> FileDiskIo(t.storage, "/downloads") })
```

<div class="grid cards" markdown>

- :material-rocket-launch: **New here?** [Get started in a few minutes](getting-started.md)
- :material-book-open-variant: **Browse the guides** below, or jump to the [API reference](https://yuroyami.github.io/KiteTorrent/api/)

</div>

## Why KiteTorrent

Every Kotlin Multiplatform app that touches torrents today shells out to a C++ blob: JNI on Android, a hand-rolled bridge on iOS, something else again on the web. You inherit a native dependency per platform, a build step per platform, and a set of bugs per platform that never line up.

KiteTorrent closes that gap. It reimplements a heavyweight C++ library as **pure, portable Kotlin**. One codebase, one mental model, every target. When something is wrong, it is one bug in one place.

## The honest module split

BitTorrent is not pure computation the way PDF rendering is. Its whole reason to exist is networking, concurrency and disk I/O, none of which have a stdlib-only equivalent. So KiteTorrent splits along that fault line, and the split is deliberate:

| Module | Nature | Stack | Targets |
|---|---|---|---|
| **`:kitetorrent`** | Pure computation: bencoding, hashing, parsing, codecs, the piece picker, crypto | `kotlin-stdlib` only | Android, iOS, JVM, JS |
| **`:kitetorrent-session`** | The live session: sockets, timers, disk, trackers, DHT | `+ coroutines, ktor-network, kotlinx-io` | Android, iOS, JVM |

The core never pretends to open a socket. There is no stdlib-only way to do that, so anything that genuinely needs the network lives in the session module on coroutines and `ktor-network`, and the boundary between the two is explicit. The core gives you everything that is pure: parse a `.torrent`, decode a magnet, hash a piece, pick the next block. The session module wires that into a running download.

!!! note "Boost.Asio, translated"
    The session module is a reimplementation, not a transliteration. Asio's `io_context` and strands become coroutines, Asio sockets become `ktor-network`, and libtorrent's threaded disk becomes a `suspend`-based `DiskIo` over `kotlinx-io`.

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

Make sure `mavenLocal()` is in your `repositories { }` block. The core's only dependency is `kotlin-stdlib`. The session module adds `kotlinx.coroutines`, `ktor-network` and `kotlinx-io`.

!!! warning "No browser engine"
    `:kitetorrent` targets the browser (JS), but `:kitetorrent-session` does not. Browsers have no raw TCP/UDP sockets, so the live engine cannot run there. You can still parse torrents, decode magnets and hash data in the browser with the core module alone.

## What works today

KiteTorrent **downloads, seeds and starts from magnet links, end-to-end.** **532 tests pass**, including loopback integration tests over real sockets: a TCP download from a seeder, two engines exchanging a torrent (and again entirely over µTP), a `ut_metadata` magnet fetch, a full magnet download between two engines, and adversarial runs against a scripted peer that withholds blocks, proving the end-game and snubbing machinery recovers.

**Pure core (`:kitetorrent`):**

- **Bencoding.** `Bencode` (canonical, byte-sorted keys) and `Bdecode` to a queryable `BdecodeNode`.
- **Crypto.** SHA-1/256/512, ed25519 (RFC 8032 validated), RC4, Diffie-Hellman and MSE, all on a from-scratch pure-Kotlin big integer.
- **Torrents.** `TorrentInfo` and `FileStorage` for v1, v2 and hybrid with correct info-hashes, `CreateTorrent` (byte-identical to libtorrent), `MagnetUri`, merkle trees and resume data.
- **Protocol and algorithms.** The peer wire-protocol codec, the `PiecePicker` with libtorrent's full option set, the peer list, the IP filter and client-ID parsing.
- **DHT data.** Node IDs (BEP-42), a k-bucket routing table, KRPC messages and mutable/immutable storage (BEP-44).

**Live engine (`:kitetorrent-session`):**

- **`PeerConnection`.** The full BitTorrent handshake and message loop, inbound and outbound.
- **`TorrentSession`.** Download and seed, driven by the ported `request_blocks` scheduler: dynamic per-peer request queues, true end-game mode, snubbing, tit-for-tat choking, verified resume, request timeouts, piece and file priorities, sequential download and pause/resume.
- **Magnet links.** `addMagnet` fetches metadata via `ut_metadata` (BEP-9/10), then downloads.
- **Transports.** TCP and µTP (BEP-29), with outgoing dials trying µTP and falling back to TCP.
- **Rate limiting.** Session-wide and per-torrent upload/download caps, enforced on the send path and via receive back-pressure.
- **Discovery.** HTTP and UDP trackers, a live DHT node, web seeds (BEP-19), and UPnP plus NAT-PMP port mapping.

See **[Status & roadmap](about.md)** for the full porting map and what remains.

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

KiteTorrent is pre-1.0 and actively developed. Downloading, seeding and magnet links all work end-to-end, anchored to ground truth: FIPS vectors for hashing, RFC 8032 for ed25519, real torrents from libtorrent's own test suite with info-hashes cross-checked against an independent implementation, and BEP golden bytes. What remains is tracked in **[Status & roadmap](about.md)**: LEDBAT congestion control for µTP, the threaded disk cache, BitTorrent-v2 download, MSE encryption wiring, proxies and the long-tail alert catalogue.

This is an independent reimplementation, distributed under BSD-3-Clause, and is not affiliated with or endorsed by the libtorrent project.
