# KiteTorrent

[![Docs](https://img.shields.io/badge/docs-kitetorrent.github.io-1f6feb)](https://yuroyami.github.io/KiteTorrent/)
[![Kotlin Multiplatform](https://img.shields.io/badge/Kotlin-Multiplatform-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/docs/multiplatform.html)
[![Targets](https://img.shields.io/badge/targets-Android%20|%20iOS%20|%20JVM%20|%20JS-success)](https://yuroyami.github.io/KiteTorrent/)
[![Core deps](https://img.shields.io/badge/core%20dependencies-kotlin--stdlib%20only-blue)](https://yuroyami.github.io/KiteTorrent/)
[![Ported from](https://img.shields.io/badge/libtorrent-RC__2__0%20(2.0.12)-orange)](https://github.com/arvidn/libtorrent)
[![License](https://img.shields.io/badge/license-BSD--3--Clause-lightgrey)](#license--credits)
[![Status](https://img.shields.io/badge/status-downloads%20end--to--end-brightgreen)](PORTING_STATUS.md)
[![Tests](https://img.shields.io/badge/tests-532%20passing-brightgreen)](PORTING_STATUS.md)

**A pure-Kotlin BitTorrent engine for Kotlin Multiplatform: a from-scratch port of [libtorrent](https://github.com/arvidn/libtorrent) (Rasterbar). The same `.kt` runs on Android, iOS, desktop JVM and the browser.**

> ## 📖 [Read the documentation →](https://yuroyami.github.io/KiteTorrent/)
> Getting started, guides, recipes, and the full API reference. **If you read one thing, read this.**

KiteTorrent reimplements a heavyweight C++ library as pure, portable Kotlin. There is no JNI, no cinterop, no native binary underneath. You call it from common code and the same source runs unchanged on Android, iOS, desktop (JVM) and the web (JS).

```kotlin
val engine = KiteTorrentEngine(scope, enableDht = true)
engine.start()

// from a .torrent
val torrent = TorrentInfo.parse(bytes)
val session = engine.addTorrent(torrent, FileDiskIo(torrent.storage, "/downloads"))
session.onPieceVerified = { piece -> println("piece $piece (${session.progress() * 100}%)") }

// ...or from a magnet link
val magnet = MagnetUri.parseMagnetUri("magnet:?xt=urn:btih:0496aa38...")
engine.addMagnet(magnet, peers, diskFactory = { t -> FileDiskIo(t.storage, "/downloads") })
```

## Why KiteTorrent

Every Kotlin Multiplatform app that touches torrents today shells out to a C++ blob: JNI on Android, a hand-rolled bridge on iOS. You inherit a native build, a native ABI, and two different surfaces to keep in sync. KiteTorrent closes that gap with one codebase, one mental model, every target.

The library is split into two artifacts so the boundary stays honest. The core is everything that is pure computation: bencoding, hashing, `.torrent` parsing, the wire-protocol codec, the piece picker. It depends on nothing but `kotlin-stdlib`. The live networking session is the only part that genuinely needs sockets, timers and disk, so it lives in a separate artifact built on `kotlinx.coroutines`, `ktor-network` and `kotlinx-io`. There is no stdlib-only way to open a socket, so the core never pretends to.

## Install

KiteTorrent is published as `0.0.1-SNAPSHOT` and consumed via `mavenLocal()` today. It is **not** on Maven Central yet. Publish the snapshot locally, then add the artifacts you need.

```bash
./gradlew publishToMavenLocal
```

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            // pure core: bencode, crypto, torrents, protocol, DHT (Android, iOS, JVM, JS)
            implementation("io.github.yuroyami:kitetorrent:0.0.1-SNAPSHOT")

            // live engine: sessions, peers, trackers, transports (Android, iOS, JVM)
            implementation("io.github.yuroyami:kitetorrent-session:0.0.1-SNAPSHOT")
        }
    }
}
```

The `kitetorrent` core targets Android, iOS, JVM and JS. The `kitetorrent-session` engine targets Android, iOS and JVM, but not JS: it needs sockets, timers and disk that the browser does not provide.

## What works today

KiteTorrent **downloads, seeds, and starts from magnet links end-to-end.** 532 tests pass, including loopback integration tests over real sockets: a TCP download from a seeder, two KiteTorrent engines exchanging a torrent (and doing it again entirely over µTP), a `ut_metadata` magnet fetch, a full magnet download between two engines, and adversarial runs against a scripted peer that withholds blocks (proving the end-game and snubbing machinery recovers). Correctness is anchored to ground truth: FIPS vectors (hashing), RFC 8032 (ed25519), real torrents from libtorrent's own test suite (info-hashes cross-checked against an independent implementation), and BEP golden bytes.

### Pure core (`kitetorrent`)

`kotlin-stdlib` only. Targets Android, iOS, JVM and JS.

- 🧮 **Bencoding**: `Entry` / `Bencode` (canonical, byte-sorted keys) / `Bdecode` to `BdecodeNode` with exact `dataSection()` for info-hashing.
- 🔑 **Crypto**: SHA-1/256/512, ed25519 (RFC-8032 validated), RC4, Diffie-Hellman, MSE, all on a from-scratch pure-Kotlin big-integer.
- 📄 **Torrents**: `TorrentInfo` / `FileStorage` (v1, v2 and hybrid, with correct info-hashes), `CreateTorrent` (byte-identical to libtorrent), `MagnetUri`, merkle trees, resume data.
- 🔌 **Protocol and algorithms**: the peer wire-protocol codec, the piece picker with libtorrent's full option set (rarest-first, reverse, sequential, prioritize-partials, plus the end-game busy tail), peer list, IP filter, client-ID parsing.
- 🌐 **DHT**: node IDs (BEP-42), k-bucket routing table, KRPC messages, storage, mutable and immutable items (BEP-44).
- 🧩 **Extensions**: ut_metadata (BEP-9), ut_pex (BEP-11), gzip/inflate, 219 error codes, settings.

### Live engine (`kitetorrent-session`)

Adds `kotlinx.coroutines`, `ktor-network` and `kotlinx-io`. Targets Android, iOS and JVM.

- 🤝 `PeerConnection`: full BitTorrent handshake plus message loop, inbound and outbound.
- 📥 `TorrentSession`: download (announce -> peers -> rarest-first pipeline -> disk -> hash-verify -> `have`) and upload/seed, driven by the real `request_blocks` scheduler. Dynamic per-peer request queues, true end-game mode, snubbing, tit-for-tat choking, verified resume, request timeouts, piece and file priorities, sequential download, pause/resume, and a lifecycle state machine.
- 🧲 **Magnet links**: `KiteTorrentEngine.addMagnet` fetches metadata via `ut_metadata` (BEP-9/10), then downloads.
- 🚀 **Transports**: TCP and µTP (BEP-29) as a first-class engine transport. Outgoing dials try µTP with TCP fallback, inbound µTP is accepted and routed by info-hash, and the DHT shares the same UDP socket.
- 🚦 **Rate limiting**: the ported `bandwidth_manager` runs live, with session-wide and per-torrent upload/download caps (`engine.setDownloadRateLimit(...)`), enforced on the send path and via receive back-pressure.
- 📣 **Trackers and discovery**: HTTP and UDP trackers, a live DHT node (bootstrap, get_peers, announce), web seeds (BEP-19), UPnP and NAT-PMP port mapping.
- 💾 `DiskIo`: in-memory plus real-file `FileDiskIo` (random access via a tiny `expect`/`actual`: `RandomAccessFile` on JVM/Android, POSIX on iOS).
- 🎛️ `KiteTorrentEngine`: multiple torrents, inbound listen socket, optional DHT and µTP, a session-wide connection cap, all configured through the ported `SettingsPack` with libtorrent's defaults (`piece_timeout`, `request_queue_time`, `strict_end_game_mode`, `connections_limit`, and the rest).

See the **[full documentation](https://yuroyami.github.io/KiteTorrent/)** for the complete API and worked examples.

## Status

The core port is functionally complete and interoperable. Downloads, seeds and magnets work end-to-end, and 532 tests pass.

Wave 5 added the download-robustness layer (choking, verified resume, priorities, pause/resume). Wave 6 replaced the scheduler's remaining approximations with the real `request_blocks.cpp` machinery (true end-game, snubbing, dynamic request queues), wired the rate limiter and µTP into the engine, and put the session behind `SettingsPack`.

What remains is LEDBAT congestion control and retransmission for µTP (the stream still runs a fixed window), the threaded disk cache, BitTorrent-v2 download (hash requests), MSE encryption wiring, proxies, and the long-tail alert catalogue. The full porting status, wave history, and remaining work are tracked in **[PORTING_STATUS.md](PORTING_STATUS.md)**.

## Building

```bash
./gradlew :kitetorrent:jvmTest              # pure-core test suite
./gradlew :kitetorrent-session:jvmTest      # engine tests incl. the loopback download
./gradlew build                             # all modules, all targets
```

Requires JDK 21 and the Android SDK (`local.properties` -> `sdk.dir`).

## License & credits

KiteTorrent is a derivative work of **libtorrent** by Arvid Norberg and contributors, distributed under the **BSD-3-Clause** license. The original copyright notices are retained per-file where code is ported directly. This port (the Kotlin translation and architecture) is provided under the same BSD-3-Clause terms.

This is an independent reimplementation and is not affiliated with or endorsed by the libtorrent project.
