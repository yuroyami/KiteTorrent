# About KiteTorrent

**A pure-Kotlin BitTorrent engine for every target.** KiteTorrent is a Kotlin Multiplatform reimplementation of [libtorrent](https://github.com/arvidn/libtorrent) (Rasterbar): no JNI, no cinterop, no native blob. One Kotlin codebase handles bencoding, hashing, `.torrent` and magnet parsing, the wire protocol, the piece picker, the DHT, trackers, and the live download and seed session. It runs on Android, iOS, the JVM, and, for the pure core, the browser.

## How it is put together

The protocol stack is written in Kotlin and runs as the same `.kt` everywhere Kotlin runs. There is no native binary to build per platform and no bridge layer to keep in sync.

BitTorrent is networking, concurrency and disk I/O, and there is no stdlib-only way to open a socket. So the library splits along that line:

- **`:kitetorrent`** is the pure core: bencoding, crypto, `.torrent` parsing, the wire-protocol codec, the piece picker, the DHT data structures, settings, alerts. Its only dependency is `kotlin-stdlib`, and it targets Android, iOS, JVM, and JS.
- **`:kitetorrent-session`** is the live engine: the session loop, sockets, disk, trackers, the DHT node, and µTP. It is built on `kotlinx.coroutines`, `ktor-network`, and `kotlinx-io`, and targets Android, iOS, and JVM. There is no JS target here, because browsers have no raw TCP/UDP sockets.

The core never pretends to do something it cannot. See [Platform support](platforms.md) for the full matrix.

## Current status

KiteTorrent is **pre-1.0** and actively developed. It **downloads, seeds, and starts from magnet links, end-to-end.** The JVM path runs **567 tests**: 462 in the core's common suite and 105 in the session module. 566 pass. `Socks5UdpTest.proxiedUdpSocketWrapsAndUnwrapsThroughRelay` times out reproducibly. The suite includes loopback integration tests over real sockets:

- A TCP download from a seeder
- Two KiteTorrent engines exchanging a torrent, in plaintext and with MSE forced, then doing it again entirely over µTP
- A `ut_metadata` magnet metadata fetch, and a full magnet download between two engines
- A v2-only torrent exchanged between two engines
- SOCKS4, SOCKS5, SOCKS5-over-UDP and HTTP-CONNECT proxy runs, including a tunnelled tracker announce
- A real-file `FileDiskIo` round-trip
- Adversarial runs against a scripted peer that *withholds* blocks, proving the end-game duplication, `cancel`, and snubbing machinery recover

Correctness is checked against published reference data:

- **FIPS vectors** for SHA-1/256/512
- **RFC 8032** for ed25519
- **Real torrent files**, with info-hashes cross-checked against an independent implementation
- **BEP golden bytes** for the DHT, ut_pex, and the UDP tracker

!!! note "What end-to-end means here"
    Two engines, started cold, can hand a torrent to each other over real sockets and verify every piece against its hash. That holds for both TCP and µTP, and for both `.torrent` and magnet starts. These are local Gradle runs. `.github/workflows/` contains only `docs.yml`, so nothing runs the test suites on push.

## Known defects

These ship today and fail silently. If you are building against KiteTorrent, read this section first.

- **HTTP and HTTPS tracker announces do nothing unless you pass an `HttpTracker`.** `KiteTorrentEngine`'s `httpTracker` parameter defaults to `null`, and both announce paths are null-safe calls. Nothing throws, nothing is logged, no alert is posted. Most public torrents list HTTP trackers, so you see an engine that finds no peers and gives no reason. See [Getting started](getting-started.md).
- **`FileDiskIo` cannot resume or seed from data already on disk.** Its `checkExistingFiles()` returns an all-`false` array, and the recheck that `addTorrent` runs internally reads exactly that array. Point it at a complete copy and the session reports zero pieces and downloads the whole torrent again over the existing files. Call `session.recheck(full = true)` after `addTorrent`. See [Seeding](seeding.md).
- **`progress()` counts pieces, not bytes, and does not exclude filtered pieces.** Any file set to `IGNORE` priority means `progress()` can never reach `1.0`, and `isSeeding()` never becomes true either.
- **`FileDiskIo`'s `dispatcher` parameter defaults to `Dispatchers.Default`**, putting blocking file syscalls on the CPU dispatcher. Pass `Dispatchers.IO`.
- **`anonymous_mode` does not force a proxy.** It skips the inbound listen socket, skips UPnP and NAT-PMP, and randomizes the per-torrent peer id. Nothing refuses to dial when no proxy is configured.

## Current limits

These are not defects. They are gaps you may hit, and work that is planned:

- **No threaded disk cache.** `DiskIo` is a suspending interface and `FileDiskIo` writes straight through to the file handle. There is no batching and no write-back cache.
- **A partial alert catalogue.** 51 concrete alert classes exist, so some events have no alert you can observe.
- **No path-MTU probing for µTP.** The MSS starts at 1400 and only shrinks: 64 bytes after two consecutive timeout rounds, down to a floor of 1212. It never grows back.
- **Thin test coverage for the real-file disk layer.** `FileDiskIoTest` has one test, and every engine-level integration test uses `InMemoryDiskIo`. That is how the `checkExistingFiles` defect above went unnoticed.
- **A failing proxy test.** `Socks5UdpTest` times out after 15 seconds in `UdpSocket.receive` against its own loopback relay, on every run.
- **No CI workflow that runs the tests.**

## Reporting bugs

If a download stalls, a torrent fails to parse, or a peer interaction misbehaves, [open an issue](https://github.com/yuroyami/KiteTorrent/issues) with enough detail to reproduce it: the `.torrent` or magnet link, the engine settings, and the alerts you observed. Networking code keeps its parse and build logic in pure, testable functions. Most fixes can therefore land as a unit test plus a regression test at the engine integration level.

## Contributing

Contributions are welcome. Check the [GitHub repository](https://github.com/yuroyami/KiteTorrent) for open issues. Test additions and protocol fixes are especially valuable. The component-by-component map for contributors is [PORTING_STATUS.md](https://github.com/yuroyami/KiteTorrent/blob/main/PORTING_STATUS.md). The [API reference](https://yuroyami.github.io/KiteTorrent/api/) is the place to confirm a signature before you build against it.

## License and credits

KiteTorrent is a derivative work of **libtorrent** by Arvid Norberg and contributors, distributed under the **BSD-3-Clause** license. The original copyright notices are kept per file wherever code was translated directly. The Kotlin code and the architecture around it use the same BSD-3-Clause terms.

This is an independent reimplementation. It is not affiliated with or endorsed by the libtorrent project.

## Acknowledgements

- **libtorrent** by Arvid Norberg and contributors: the reference implementation, pinned to RC_2_0 (2.0.12)
- **The BEP authors**: the BitTorrent Enhancement Proposals that define the protocol and its extensions
- **The FIPS and RFC 8032 test vectors**, and the BEP golden bytes, that let this project check itself against published results
