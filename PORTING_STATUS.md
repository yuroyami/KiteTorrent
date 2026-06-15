# KiteTorrent — Porting Status

Porting **libtorrent RC_2_0 (2.0.12)** — ~95k lines of C++ source + ~56k of headers — to pure Kotlin Multiplatform. This is the honest map: what's done, what's next, where the architecture diverges.

## The fundamental shape of the work

libtorrent is **not** like MuPDF/KitePDF. PDF rendering is pure computation, so KitePDF could be 100% stdlib-only `commonMain`. BitTorrent's whole reason to exist is **networking + concurrency + disk I/O**, which have no stdlib-only equivalent. So KiteTorrent splits along that fault line:

| Tier | Nature | Module | Stack |
|------|--------|--------|-------|
| **1. Pure computation** | bencoding, hashing, parsing, codecs, algorithms, crypto | `:kitetorrent` | kotlin-stdlib only · android/ios/jvm/js |
| **2. I/O & concurrency** | session loop, sockets, disk, trackers, DHT | `:kitetorrent-session` | + coroutines / ktor / kotlinx-io · android/ios/jvm |
| **3. Platform syscalls** | NIC enumeration, true mmap | `expect`/`actual` | per-target (minimal) |

Boost.Asio's `io_context`/strands → **coroutines**; Asio sockets → **ktor-network**; threaded disk → **a `suspend` DiskIo + kotlinx-io**. A cleaner model than the C++ callback soup — but a *reimplementation against Kotlin concurrency*, not a transliteration.

Legend: ✅ done & tested · 🟡 partial/under test · 🔜 in progress · ⬜ planned

---

## Tier 1 — Pure core (`:kitetorrent`) — **✅ essentially complete · 460 tests passing**

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
| Settings/errors/alerts | `settings/` (consumed live by the engine — see Wave 6), `error/LibtorrentError` (219), `alert/` (~30) | `settings_pack/error_code/alert.cpp` | 🟡 common subset |
| Utils | `util/Base32·UrlEscape·StringUtil·BloomFilter`, `bandwidth/`, `stat/` | various | ✅ |

Remaining Tier-1 odds & ends (low priority): full alert catalogue (~100 types; ~30 done), `peer_class`, port-mapping message codecs, a few string/url helpers.

---

## Tier 2 — Live session (`:kitetorrent-session`) — **✅ downloads, seeds, and magnets, end-to-end**

On coroutines 1.10.2 / ktor 3.2.3 / kotlinx-io 0.7.0 (Android · iOS · JVM). Validated by real loopback integration tests, not just unit tests.

| Component | KiteTorrent | libtorrent source | Status |
|---|---|---|---|
| Socket layer | `net/Sockets` (TCP + UDP over ktor-network) | Asio sockets | ✅ |
| Disk | `disk/DiskIo` + `InMemoryDiskIo` + `FileDiskIo` | `disk_interface.hpp`, `mmap_storage.cpp` | ✅ |
| Peer connection | `peer/PeerConnection` (handshake, msg loop, in+outbound) | `peer_connection.cpp`, `bt_peer_connection.cpp` | ✅ |
| HTTP + UDP trackers | `tracker/HttpTracker`, `UdpTracker` (+codecs) | `http/udp_tracker_connection.cpp` | ✅ |
| Live DHT node | `dht/DhtNode·DhtRpc·DhtTraversal·DhtTokens` | `kademlia/node·rpc_manager·traversal` | ✅ |
| Download/upload engine | `engine/TorrentSession` (rarest-first dl + serve/seed, the full `request_blocks` scheduler — see Wave 6) | `torrent.cpp`, `request_blocks.cpp` | ✅ |
| Session manager | `engine/KiteTorrentEngine` (multi-torrent, listen, DHT, magnet, rate limits, connection cap, `SettingsPack`-driven) | `session_impl.cpp` | ✅ |
| Magnet metadata | `peer/MetadataExchange` + `extensions/ExtensionHandshake` (BEP-9/10) | `ut_metadata.cpp` | ✅ |
| uTP transport | `net/UtpStream·UtpPacket·UtpSocketManager` (active+passive open, connection-id demux, **wired as an engine transport** with TCP fallback) | `utp_stream.cpp`, `utp_socket_manager.cpp` | ✅ |
| Web seeds | `peer/WebSeed` (BEP-19) | `web_peer_connection.cpp` | ✅ |
| UPnP / NAT-PMP | `net/Upnp`, `net/NatPmp` | `upnp.cpp`, `natpmp.cpp` | ✅ |

Integration tests (loopback, real sockets): TCP download from a seeder · **two engines exchanging a torrent** · **two engines exchanging a torrent over µTP** · **magnet metadata fetch** · **full magnet download between two engines** · real-file `FileDiskIo` · a scripted withholding peer proving **end-game duplication + cancel** and **snubbing** · a **rate-limited** download · the **connection cap**.

### Wave 5 — download robustness ✅

| Feature | KiteTorrent | libtorrent source |
|---|---|---|
| **Choking / tit-for-tat** | `engine/Choker` (pure, unit-tested) + periodic choke rounds in `TorrentSession`: unchoke the fastest interested peers + a rotating optimistic slot; choked peers aren't served | `choker.cpp` |
| **Verified resume** | `TorrentSession.recheck()` *hashes* on-disk pieces and claims only the ones matching the torrent's piece hashes (`recheck(full=true)` rehashes everything); no longer blindly trusts the disk layer | `storage_utils.cpp` recheck |
| **Request timeouts** | a maintenance tick recycles blocks stuck past `request_timeout` on slow peers | `peer_connection.cpp` |
| **State machine + control** | `TorrentState` (CHECKING/DOWNLOADING/SEEDING/PAUSED) + `onStateChanged`, `pause()`/`resume()`, `setPiecePriority`/`setFilePriority` (0 = don't download) | `torrent.cpp` lifecycle |

### Wave 6 — the real request scheduler ✅ (the `request_blocks.cpp` engine)

Everything "still partial after Wave 5" is now ported for real, plus the two transport/throughput items from the roadmap:

| Feature | KiteTorrent | libtorrent source |
|---|---|---|
| **True end-game mode** | when the swarm has no free block left for an idle peer, *one* busy block is double-requested (`strict_end_game_mode` gates it while untouched pieces remain); first delivery wins, every other holder gets a `cancel` — validated by a scripted withholding peer | `request_blocks.cpp` `request_a_block`, `torrent::cancel_block` |
| **Snubbing — consumed** | a peer with outstanding requests and no payload for `piece_timeout` is snubbed: queue collapses to 1, the picker flips to **reverse** (most-common-first, so snubbed peers stop holding rare pieces hostage), and its newest request is cancelled when it blocks a piece — after a replacement is requested, so the picker can't hand the block straight back | `peer_connection::snub_peer`, `picker_options()` |
| **Picker option modes** | `pickPieces(options)`: `rarest_first` / `reverse` / `sequential` / `prioritize_partials` / `on_parole`, the partial-sprawl cap (force partials-first past `3/2 × peers` or 2048 blocks), random-walk mode below `initial_picker_threshold`, and the end-game busy tail — same bit values as upstream | `piece_picker::pick_pieces`, `add_blocks_downloading` |
| **Dynamic request queue** | per-peer pipeline depth = `request_queue_time × rate / block-size`, clamped `[2, max_out_request_queue]`, slow-start (+1 per block until the rate plateaus), snubbed/end-game → 1; rates come from the ported `Stat` | `peer_connection::update_desired_queue_size` |
| **Rate limiting — wired** | the Tier-1 `BandwidthManager`/`BandwidthChannel` port now runs live: a suspending `RateLimiter` (global + per-torrent channels) gates uploads before the send and downloads via receive-loop back-pressure; `upload_rate_limit`/`download_rate_limit` settable on the engine | `bandwidth_manager.cpp` + `session_impl` wiring |
| **µTP in-engine** | new `UtpSocketManager` (connection-id demux, passive opens, DHT sharing the same UDP socket by packet sniff); outgoing connections dial uTP first with TCP fallback; inbound uTP routed by info-hash like TCP — two engines exchange a torrent **over uTP** in tests | `utp_socket_manager.cpp`, `session_impl::on_udp_packet` |
| **Connection limits** | a session-wide `ConnectionBudget` (`connections_limit`, default 200) shared by all torrents, enforced on dial and accept | `session_impl` connection caps |
| **Sequential download** | `session.sequentialDownload = true` → in-order pieces (verified in-order completion in tests) | `torrent_flags::sequential_download` |
| **settings_pack — consumed** | the engine reads its knobs (`piece_timeout`, `request_timeout`, `request_queue_time`, `max_out_request_queue`, `initial_picker_threshold`, `strict_end_game_mode`, `unchoke_interval`, `unchoke_slots_limit`, `connections_limit`, rate limits) from the ported `SettingsPack`, with libtorrent's defaults | `settings_pack.cpp` |

Known simplification kept from the standalone µTP work: `UtpStream` still uses a fixed send window (no LEDBAT congestion control or retransmission timer yet).

## Tier 3 — Platform `expect`/`actual` — ✅ (core paths)

| Component | KiteTorrent | Status |
|---|---|---|
| Random-access file | `disk/RandomAccessStorage` — `RandomAccessFile` (JVM/Android), POSIX (iOS) | ✅ |
| Interface enumeration | `net/LocalInterfaces` — `NetworkInterface` (JVM/Android) | ✅ JVM/Android; iOS returns loopback (needs an `<ifaddrs.h>` cinterop) |

---

## Testing philosophy

Every pure module is validated against ground truth, not just "compiles": FIPS vectors (hashing), RFC 8032 (ed25519), real torrents from libtorrent's own `test/` tree (info-hashes cross-checked against an independent SHA-1), and BEP golden bytes (DHT, ut_pex, UDP tracker). Networking code keeps its parse/build logic in pure, testable functions; live-socket behaviour is validated at the engine integration level.
