# KiteTorrent porting status

This page tracks the port of **libtorrent RC_2_0 (2.0.12)** to pure Kotlin Multiplatform. The upstream source is about 95k lines of C++ plus about 56k lines of headers. Each table lists a component, where it lives in Kotlin, which upstream file it comes from, and its status.

## How the work is split

BitTorrent is networking, concurrency and disk I/O, and none of those have a stdlib-only equivalent in Kotlin. So KiteTorrent splits into three layers:

| Tier | Nature | Module | Stack |
|------|--------|--------|-------|
| **1. Pure computation** | bencoding, hashing, parsing, codecs, algorithms, crypto | `:kitetorrent` | kotlin-stdlib only · android/ios/jvm/js |
| **2. I/O & concurrency** | session loop, sockets, disk, trackers, DHT | `:kitetorrent-session` | + coroutines / ktor / kotlinx-io · android/ios/jvm |
| **3. Platform syscalls** | NIC enumeration, true mmap | `expect`/`actual` | per-target (minimal) |

Boost.Asio's `io_context` and strands become **coroutines**. Asio sockets become **ktor-network**. The threaded disk becomes a `suspend` **DiskIo** over **kotlinx-io**. This is a reimplementation against Kotlin concurrency, not a transliteration.

Legend: ✅ done & tested · 🟡 partial/under test · 🔜 in progress · ⬜ planned

---

## Tier 1: pure core (`:kitetorrent`) · **✅ essentially complete · 462 tests passing**

| Area | KiteTorrent | libtorrent source | Validation |
|---|---|---|---|
| Hashing | `crypto/Sha1·Sha256·Sha512·Hasher·Hasher256` | `sha1/sha256.cpp` | ✅ FIPS vectors |
| Digests | `Digest32` (`Sha1Hash`/`Sha256Hash`) | `sha1_hash.hpp` | ✅ cmp/XOR/CLZ |
| Bitfield, Hex | `Bitfield`, `Hex` | `bitfield.hpp`, `hex.cpp` | ✅ wire round-trip |
| Bencode | `bencode/Entry·Bencode·Bdecode·BdecodeNode` | `entry/bencode/bdecode.cpp` | ✅ canonical + errors |
| Torrent parse | `torrent/TorrentInfo·FileStorage` | `torrent_info.cpp`, `file_storage.cpp` | ✅ real torrents, info-hash vs ground truth |
| Torrent build | `torrent/CreateTorrent` | `create_torrent.cpp` | ✅ byte-identical to libtorrent |
| Merkle (v2) | `torrent/Merkle·MerkleTree` | `merkle*.cpp` | ✅ |
| Magnet/load | `torrent/MagnetUri`, `LoadTorrent` | `magnet_uri.cpp` | ✅ btih + btmh + base32 |
| Resume data | `torrent/resume/AddTorrentParams·ResumeData` | `read/write_resume_data.cpp` | ✅ round-trip |
| Wire protocol | `protocol/Handshake·PeerMessage` | `bt_peer_connection.cpp` | ✅ all message round-trips |
| Piece picker | `picker/PiecePicker` | `piece_picker.cpp` | ✅ rarest-first + option modes (reverse/sequential/partials) + end-game busy tail |
| Peers | `peer/PeerList·TorrentPeer·IpFilter·IdentifyClient·Crc32c` | `peer_list/ip_filter/identify_client.cpp` | ✅ |
| Crypto (MSE) | `crypto/Rc4·DiffieHellman·MseCrypto`, `math/BigInt` | `pe_crypto.cpp` | ✅ RC4 vectors, DH agreement |
| Crypto (ed25519) | `crypto/Ed25519` | `ed25519/*.cpp` | ✅ **RFC 8032 vectors** |
| DHT (data) | `dht/NodeId·NodeEntry·RoutingTable·DhtMessage·DhtStorage·DhtItem·CompactNodes` | `kademlia/*` (pure half) | ✅ BEP-42/44/51 |
| Extensions | `extensions/UtMetadata·UtPex` | `ut_metadata/ut_pex.cpp` | ✅ codecs |
| Compression | `compression/Inflate·Gzip` | `puff/gzip.cpp` | ✅ vs zlib |
| Settings/errors/alerts | `settings/` (consumed live by the engine, see Wave 6), `error/LibtorrentError` (219), `alert/` (51 concrete classes) | `settings_pack/error_code/alert.cpp` | 🟡 common subset |
| Hash picker (v2) | `torrent/HashPicker` | `hash_picker.cpp` | ✅ builds/validates `hash_request` + `hashes` |
| Utils | `util/Base32·UrlEscape·StringUtil·BloomFilter`, `bandwidth/`, `stat/` | various | ✅ |

Remaining Tier-1 items (low priority): full alert catalogue (~100 types; 51 done), `peer_class`, port-mapping message codecs, a few string/url helpers.

---

## Tier 2: live session (`:kitetorrent-session`) · **✅ downloads, seeds and magnets, end to end · 105 tests, 104 passing**

On coroutines 1.11.0 / ktor 3.5.1 / kotlinx-io 0.9.1 (Android · iOS · JVM). Validated by real loopback integration tests, not just unit tests.

| Component | KiteTorrent | libtorrent source | Status |
|---|---|---|---|
| Socket layer | `net/Sockets` (TCP + UDP over ktor-network) | Asio sockets | ✅ |
| Disk | `disk/DiskIo` + `InMemoryDiskIo` + `FileDiskIo` | `disk_interface.hpp`, `mmap_storage.cpp` | ✅ |
| Peer connection | `peer/PeerConnection` (handshake, msg loop, in+outbound) | `peer_connection.cpp`, `bt_peer_connection.cpp` | ✅ |
| HTTP + UDP trackers | `tracker/HttpTracker`, `UdpTracker` (+codecs) | `http/udp_tracker_connection.cpp` | ✅ |
| Live DHT node | `dht/DhtNode·DhtRpc·DhtTraversal·DhtTokens` | `kademlia/node·rpc_manager·traversal` | ✅ |
| Download/upload engine | `engine/TorrentSession` (rarest-first dl + serve/seed, the full `request_blocks` scheduler, see Wave 6) | `torrent.cpp`, `request_blocks.cpp` | ✅ |
| Session manager | `engine/KiteTorrentEngine` (multi-torrent, listen, DHT, magnet, rate limits, connection cap, `SettingsPack`-driven) | `session_impl.cpp` | ✅ |
| Magnet metadata | `peer/MetadataExchange` + `extensions/ExtensionHandshake` (BEP-9/10) | `ut_metadata.cpp` | ✅ |
| uTP transport | `net/UtpStream·UtpPacket·UtpSocketManager` (active+passive open, connection-id demux, LEDBAT, RTO, SACK, fast resend, Nagle; **wired as an engine transport** with TCP fallback) | `utp_stream.cpp`, `utp_socket_manager.cpp` | ✅ |
| Web seeds | `peer/WebSeed` (BEP-19) | `web_peer_connection.cpp` | ✅ |
| UPnP / NAT-PMP | `net/Upnp`, `net/NatPmp` | `upnp.cpp`, `natpmp.cpp` | ✅ |
| MSE encryption | `peer/Mse` (`initiate`/`accept`, plaintext sniff on inbound), gated by `out_enc_policy`/`in_enc_policy` | `pe_crypto.cpp`, `bt_peer_connection.cpp` | ✅ both directions |
| Proxies | `net/Socks5` (SOCKS5 incl. UDP ASSOCIATE, SOCKS4/4a, HTTP CONNECT) + `KiteTorrentEngine.applyProxyFromSettings()` | `proxy_settings`, `socks5_stream.cpp` | ✅ |
| BitTorrent v2 hash exchange | `hash_request`/`hashes`/`hash_reject` (ids 21/22/23) codec + `HashPicker` driven from `TorrentSession` (serves and consumes) | `bt_peer_connection.cpp`, `hash_picker.cpp` | ✅ |

Integration tests (loopback, real sockets): TCP download from a seeder · **two engines exchanging a torrent**, in plaintext and with MSE forced · **two engines exchanging a torrent over µTP** · **magnet metadata fetch** · **full magnet download between two engines** · a **v2-only** torrent · a scripted withholding peer proving **end-game duplication + cancel** and **snubbing** · a **rate-limited** download · the **connection cap** · SOCKS4, SOCKS5, SOCKS5-UDP and HTTP-CONNECT proxies.

Two caveats on that list. `FileDiskIoTest` contains a single test, and every engine-level integration test uses `InMemoryDiskIo`. That is how the `checkExistingFiles` defect below went unnoticed. `.github/workflows/` contains only `docs.yml`, so none of these run on push. The counts above come from local runs.

### Wave 5: download robustness ✅

| Feature | KiteTorrent | libtorrent source |
|---|---|---|
| **Choking / tit-for-tat** | `engine/Choker` (pure, unit-tested) + periodic choke rounds in `TorrentSession`: unchoke the fastest interested peers + a rotating optimistic slot; choked peers aren't served | `choker.cpp` |
| **Verified resume** | `TorrentSession.recheck()` *hashes* on-disk pieces and claims only the ones matching the torrent's piece hashes (`recheck(full=true)` rehashes everything); no longer blindly trusts the disk layer | `storage_utils.cpp` recheck |
| **Request timeouts** | a maintenance tick recycles blocks stuck past `request_timeout` on slow peers | `peer_connection.cpp` |
| **State machine + control** | `TorrentState` (CHECKING/DOWNLOADING/SEEDING/PAUSED) + `onStateChanged`, `pause()`/`resume()`, `setPiecePriority`/`setFilePriority` (0 = don't download) | `torrent.cpp` lifecycle |

### Wave 6: the request scheduler ✅ (the `request_blocks.cpp` engine)

Everything that was still partial after Wave 5 is now ported in full, plus the two transport and throughput items from the roadmap:

| Feature | KiteTorrent | libtorrent source |
|---|---|---|
| **True end-game mode** | when the swarm has no free block left for an idle peer, *one* busy block is double-requested (`strict_end_game_mode` gates it while untouched pieces remain); first delivery wins, every other holder gets a `cancel`, validated by a scripted withholding peer | `request_blocks.cpp` `request_a_block`, `torrent::cancel_block` |
| **Snubbing, consumed** | a peer with outstanding requests and no payload for `piece_timeout` is snubbed: queue collapses to 1, the picker flips to **reverse** (most-common-first, so a snubbed peer stops blocking the rare pieces), and its newest request is cancelled when it blocks a piece, but only after a replacement is requested, so the picker cannot hand the block straight back | `peer_connection::snub_peer`, `picker_options()` |
| **Picker option modes** | `pickPieces(options)`: `rarest_first` / `reverse` / `sequential` / `prioritize_partials` / `on_parole`, the partial-sprawl cap (force partials-first past `3/2 × peers` or 2048 blocks), random-walk mode below `initial_picker_threshold`, and the end-game busy tail, with the same bit values as upstream | `piece_picker::pick_pieces`, `add_blocks_downloading` |
| **Dynamic request queue** | per-peer pipeline depth = `request_queue_time × rate / block-size`, clamped `[2, max_out_request_queue]`, slow-start (+1 per block until the rate plateaus), snubbed/end-game → 1; rates come from the ported `Stat` | `peer_connection::update_desired_queue_size` |
| **Rate limiting, wired** | the Tier-1 `BandwidthManager`/`BandwidthChannel` port now runs live: a suspending `RateLimiter` (global + per-torrent channels) gates uploads before the send and downloads via receive-loop back-pressure; `upload_rate_limit`/`download_rate_limit` settable on the engine | `bandwidth_manager.cpp` + `session_impl` wiring |
| **µTP in-engine** | new `UtpSocketManager` (connection-id demux, passive opens, DHT sharing the same UDP socket by packet sniff); outgoing connections dial uTP first with TCP fallback; inbound uTP routed by info-hash like TCP; two engines exchange a torrent **over uTP** in tests | `utp_socket_manager.cpp`, `session_impl::on_udp_packet` |
| **Connection limits** | a session-wide `ConnectionBudget` (`connections_limit`, default 200) shared by all torrents, enforced on dial and accept | `session_impl` connection caps |
| **Sequential download** | `session.sequentialDownload = true` → in-order pieces (verified in-order completion in tests) | `torrent_flags::sequential_download` |
| **settings_pack, consumed** | the engine reads its knobs (`piece_timeout`, `request_timeout`, `request_queue_time`, `max_out_request_queue`, `initial_picker_threshold`, `strict_end_game_mode`, `unchoke_interval`, `unchoke_slots_limit`, `connections_limit`, rate limits) from the ported `SettingsPack`, with libtorrent's defaults | `settings_pack.cpp` |

### Wave 7: µTP congestion control, encryption, proxies, v2 ✅

| Feature | KiteTorrent | libtorrent source |
|---|---|---|
| **LEDBAT** | `UtpStream.growCwndLocked` (UtpStream.kt:848-876) runs real `do_ledbat`: a 100 ms target queuing delay, a `cwnd_saturated` gate so an idle writer cannot inflate the window for free, a delay-driven slow-start exit that halves `ssthresh`, and an off-target gain bounded to ~1 MSS/RTT. Base delay is a time-bucketed rolling minimum fed from inbound `timestamp_difference`. | `utp_stream.cpp` `do_ledbat` |
| **Retransmission** | RTO with Jacobson/Karels RTT, Karn's algorithm, exponential backoff to 60 s, go-back-N resend of every un-SACKed packet, socket failure after 6 timeouts | `utp_stream.cpp` timeout path |
| **SACK + fast resend** | selective-ack extension emitted and consumed (256-bit window), duplicate-ACK / SACK-above threshold of 3 triggers a fast resend with `cwnd = ssthresh = max(cwnd/2, 2·mss)` | `utp_stream.cpp` |
| **Nagle** | sub-MSS writes are held and coalesced into the next frame, drained on tick and on the ack path, with a force-flush path | `utp_stream.cpp` |
| **MSE / PE** | `Mse.initiate` and `Mse.accept` over the ported RC4 / Diffie-Hellman / BigInt; inbound connections are sniffed for a plaintext BitTorrent handshake and routed accordingly; `out_enc_policy` / `in_enc_policy` select forced, enabled or disabled | `pe_crypto.cpp` |
| **Proxies** | SOCKS5 (with UDP ASSOCIATE, so the shared UDP socket carrying the DHT and µTP is relayed too), SOCKS4/4a, HTTP CONNECT; configured from the `proxy_*` settings at `start()` or imperatively for TCP dials | `socks5_stream.cpp`, `session_impl::update_proxy` |
| **v2 hash requests** | `hash_request` / `hashes` / `hash_reject` encode and decode; `HashPicker` builds requests and validates the merkle proofs; `TorrentSession` both answers incoming requests from its own trees and folds received leaves into the per-file `MerkleTree`s | `hash_picker.cpp` |

Remaining µTP simplification: there is no path-MTU probing. The MSS starts at 1400 and only shrinks: 64 bytes after two consecutive timeout rounds, down to a floor of 1212. It never grows back.

## Known defects

These ship today and fail silently. They are the first things to fix.

| Defect | Where | Effect |
|---|---|---|
| `FileDiskIo.checkExistingFiles()` always returns all-`false` | `FileDiskIo.kt:216` | `recheck()` defaults to `full = false` and consults that array, and `addTorrent` → `start()` → `recheck()` gives no interception point. A `FileDiskIo` pointed at a complete copy reports zero pieces and re-downloads everything over it. Workaround: `session.recheck(full = true)`. Masked in testing because every engine integration test uses `InMemoryDiskIo`, whose implementation is real. |
| `httpTracker` defaults to `null` | `KiteTorrentEngine.kt:86` | Both announce paths are `httpTracker?.announce(...)` (`TorrentSession.kt:679`, `KiteTorrentEngine.kt:655`). No exception, no alert, no log. HTTP and HTTPS announces are skipped, and a torrent whose trackers are all HTTP finds no peers. |
| `progress()` divides by every piece | `TorrentSession.kt:311` | Filtered (priority 0) pieces stay in the denominator, so `progress()` cannot reach `1.0` and `isSeeding()` cannot become true for a torrent with an ignored file. `PiecePicker.isFinished()` is the correct predicate and is never called. |
| `FileDiskIo` dispatcher defaults to `Dispatchers.Default` | `FileDiskIo.kt:49` | Blocking file syscalls land on the CPU dispatcher unless the caller passes `Dispatchers.IO`. |
| `anonymous_mode` does not force a proxy | `KiteTorrentEngine.kt:164` KDoc vs. lines 171/196/435 | It skips the listen socket, skips UPnP/NAT-PMP and randomizes the per-torrent peer id. Nothing refuses to dial without a proxy, so an unconfigured engine leaks direct. |
| `allowed_enc_level` and `prefer_rc4` are inert | `IntSettings.kt:328`, `BoolSettings.kt:139` | Declared in the settings model, never read by the session module. |

## Tier 3: platform `expect`/`actual` ✅ (core paths)

| Component | KiteTorrent | Status |
|---|---|---|
| Random-access file | `disk/RandomAccessStorage`: `RandomAccessFile` (JVM/Android), POSIX (iOS) | ✅ |
| Interface enumeration | `net/LocalInterfaces`: `NetworkInterface` (JVM/Android) | ✅ JVM/Android; iOS returns loopback (needs an `<ifaddrs.h>` cinterop) |

---

## How correctness is checked

Every pure module is validated against published reference data: FIPS vectors (hashing), RFC 8032 (ed25519), real torrents from libtorrent's own `test/` tree (info-hashes cross-checked against an independent SHA-1), and BEP golden bytes (DHT, ut_pex, UDP tracker). Networking code keeps its parse and build logic in pure, testable functions. Live-socket behaviour is validated at the engine integration level.

567 tests run on the JVM path: 462 in the core's common suite, 105 in the session module (60 common, 45 JVM-only). 566 pass. `Socks5UdpTest.proxiedUdpSocketWrapsAndUnwrapsThroughRelay` times out after 15 s waiting on `UdpSocket.receive` against its own loopback relay, reproducibly. Two more weak spots: the real-file disk layer has one test, and no workflow runs any of this.
