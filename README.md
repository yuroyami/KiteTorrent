# KiteTorrent

**A pure-Kotlin BitTorrent engine — a from-scratch port of [libtorrent](https://github.com/arvidn/libtorrent) (Rasterbar) to Kotlin Multiplatform.**

[![Kotlin Multiplatform](https://img.shields.io/badge/Kotlin-Multiplatform-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/docs/multiplatform.html)
[![Targets](https://img.shields.io/badge/targets-Android%20·%20iOS%20·%20JVM%20·%20JS-success)](#)
[![Core deps](https://img.shields.io/badge/core%20dependencies-kotlin--stdlib%20only-blue)](#)
[![Ported from](https://img.shields.io/badge/libtorrent-RC__2__0%20(2.0.12)-orange)](https://github.com/arvidn/libtorrent)
[![License](https://img.shields.io/badge/license-BSD--3--Clause-lightgrey)](#license--credits)
[![Status](https://img.shields.io/badge/status-downloads%20end--to--end-brightgreen)](PORTING_STATUS.md)
[![Tests](https://img.shields.io/badge/tests-532%20passing-brightgreen)](#)

KiteTorrent reimplements a heavyweight C++ library as **pure, portable Kotlin** — no JNI, no cinterop, no platform renderers. The same `.kt` runs on Android, iOS, desktop JVM, and the browser.

---

## Why

Every Kotlin Multiplatform app that touches torrents today shells out to a C++ blob through JNI on Android and a hand-rolled bridge on iOS. KiteTorrent closes that gap: one codebase, one mental model, every target.

The library is split so the **core stays stdlib-pure** (everything that is pure computation — bencoding, hashing, `.torrent` parsing, the wire-protocol codec, the piece picker) while the **live networking session** — the only part that genuinely needs sockets, timers, and disk — lives in a separate module built on `kotlinx.coroutines` + `ktor-network` + `kotlinx-io`. That boundary is deliberate and honest: there is no stdlib-only way to open a socket, so the core never pretends to.

## What works today

KiteTorrent **downloads, seeds, and starts from magnet links — end-to-end.** **532 tests pass**, including loopback integration tests over real sockets: a TCP download from a seeder, **two KiteTorrent engines exchanging a torrent** (and doing it again **entirely over µTP**), a **`ut_metadata` magnet fetch**, a **full magnet download between two engines**, and adversarial runs against a scripted peer that *withholds* blocks — proving the end-game and snubbing machinery recovers. Correctness is anchored to ground truth — FIPS vectors (hashing), RFC 8032 (ed25519), real torrents from libtorrent's own test suite (info-hashes cross-checked against an independent implementation), and BEP golden bytes.

**Pure core (`:kitetorrent`, kotlin-stdlib only — Android · iOS · JVM · JS):**

- 🧮 **Bencoding** — `Entry` / `Bencode` (canonical, byte-sorted keys) / `Bdecode` → `BdecodeNode` with exact `dataSection()` for info-hashing.
- 🔑 **Crypto** — SHA-1/256/512, **ed25519** (RFC-8032 validated), RC4, Diffie-Hellman, MSE — all on a from-scratch pure-Kotlin big-integer.
- 📄 **Torrents** — `TorrentInfo`/`FileStorage` (v1 + v2 + hybrid, correct info-hashes), `CreateTorrent` (byte-identical to libtorrent), `MagnetUri`, merkle trees, resume data.
- 🔌 **Protocol & algorithms** — the peer wire-protocol codec, the **piece picker** with libtorrent's full option set (rarest-first / reverse / sequential / prioritize-partials + the end-game busy tail), peer list, IP filter, client-ID parsing.
- 🌐 **DHT** — node IDs (BEP-42), k-bucket routing table, KRPC messages, storage, mutable/immutable items (BEP-44).
- 🧩 **Extensions** — ut_metadata (BEP-9), ut_pex (BEP-11); gzip/inflate; 219 error codes; settings.

**Live engine (`:kitetorrent-session`, + coroutines / ktor-network / kotlinx-io — Android · iOS · JVM):**

- 🤝 `PeerConnection` — full BitTorrent handshake + message loop, inbound and outbound.
- 📥📤 `TorrentSession` — download (announce → peers → rarest-first pipeline → disk → hash-verify → `have`) **and** upload/seed, driven by the real `request_blocks` scheduler: **dynamic per-peer request queues** (slow-start + rate-derived depth), **true end-game mode** (busy-block duplication, first delivery wins, losers get `cancel`), **snubbing** (queue collapse + reverse picking), **tit-for-tat choking**, **verified resume**, request timeouts, piece/file priorities, **sequential download**, pause/resume, and a lifecycle state machine.
- 🧲 **Magnet links** — `KiteTorrentEngine.addMagnet` fetches metadata via `ut_metadata` (BEP-9/10), then downloads.
- 🚀 **Transports** — TCP and **µTP** (BEP-29) as a first-class engine transport: outgoing dials try uTP with TCP fallback, inbound uTP is accepted and routed by info-hash, and the DHT shares the same UDP socket through the `utp_socket_manager` port.
- 🚦 **Rate limiting** — the ported `bandwidth_manager` runs live: session-wide + per-torrent upload/download caps (`engine.setDownloadRateLimit(…)`), enforced on the send path and via receive back-pressure.
- 📣 HTTP + UDP **trackers**, a live **DHT node** (bootstrap / get_peers / announce), **web seeds** (BEP-19), **UPnP + NAT-PMP** port mapping.
- 💾 `DiskIo` — in-memory plus real-file `FileDiskIo` (random access via a tiny `expect`/`actual`: `RandomAccessFile` on JVM/Android, POSIX on iOS).
- 🎛️ `KiteTorrentEngine` — multiple torrents, inbound listen socket, optional DHT + µTP, a session-wide **connection cap**, all configured through the ported **`SettingsPack`** with libtorrent's defaults (`piece_timeout`, `request_queue_time`, `strict_end_game_mode`, `connections_limit`, …).

```kotlin
val engine = KiteTorrentEngine(scope, enableDht = true)
engine.start()

// from a .torrent
val torrent = TorrentInfo.parse(bytes)
val session = engine.addTorrent(torrent, FileDiskIo(torrent.storage, "/downloads"))
session.onPieceVerified = { piece -> println("piece $piece — ${session.progress() * 100}%") }

// …or from a magnet link
val magnet = MagnetUri.parseMagnetUri("magnet:?xt=urn:btih:0496aa38…")
engine.addMagnet(magnet, peers, diskFactory = { t -> FileDiskIo(t.storage, "/downloads") })
```

## What's next

The core port is functionally complete and interoperable. Wave 5 added the download-robustness layer (choking, verified resume, priorities, pause/resume); Wave 6 replaced the scheduler's remaining approximations with the real `request_blocks.cpp` machinery (true end-game, snubbing, dynamic request queues), wired the rate limiter and µTP into the engine, and put the session behind `SettingsPack`. What remains — tracked in **[PORTING_STATUS.md](PORTING_STATUS.md)** — is: LEDBAT congestion control + retransmission for µTP (the stream still runs a fixed window), the threaded disk cache, BitTorrent-v2 *download* (hash requests), MSE encryption wiring, proxies, and the long-tail alert catalogue.

## Building

```bash
./gradlew :kitetorrent:jvmTest              # pure-core test suite
./gradlew :kitetorrent-session:jvmTest      # engine tests incl. the loopback download
./gradlew build                             # all modules, all targets
```

Requires JDK 21 and the Android SDK (`local.properties` → `sdk.dir`). Modules: `io.github.yuroyami:kitetorrent` (pure core) and `io.github.yuroyami:kitetorrent-session` (live engine).

## License & credits

KiteTorrent is a derivative work of **libtorrent** by Arvid Norberg and contributors, distributed under the **BSD-3-Clause** license. The original copyright notices are retained per-file where code is ported directly. This port — the Kotlin translation and architecture — is provided under the same BSD-3-Clause terms.

This is an independent reimplementation and is not affiliated with or endorsed by the libtorrent project.
