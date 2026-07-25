# About KiteTorrent

**A pure-Kotlin BitTorrent engine for every target.** KiteTorrent is a from-scratch port of [libtorrent](https://github.com/arvidn/libtorrent) (Rasterbar) to Kotlin Multiplatform: no JNI, no cinterop, no native blob. One Kotlin codebase handles bencoding, hashing, `.torrent` and magnet parsing, the wire protocol, the piece picker, the DHT, trackers, and the live download/seed session across Android, iOS, JVM, and (for the pure core) the browser.

## How it is put together

The protocol stack is written in Kotlin and runs as the same `.kt` everywhere Kotlin runs. There is no native binary to build per platform and no bridge layer to keep in sync.

BitTorrent is networking, concurrency and disk I/O, and there is no stdlib-only way to open a socket. So the library splits along that line:

- **`:kitetorrent`** is the pure core: bencoding, crypto, `.torrent` parsing, the wire-protocol codec, the piece picker, the DHT data structures, settings, alerts. Its only dependency is `kotlin-stdlib`, and it targets Android, iOS, JVM, and JS.
- **`:kitetorrent-session`** is the live engine: the session loop, sockets, disk, trackers, the DHT node, and µTP. It is built on `kotlinx.coroutines`, `ktor-network`, and `kotlinx-io`, and targets Android, iOS, and JVM. There is no JS target here, because browsers have no raw TCP/UDP sockets.

The core never pretends to do something it cannot. See [Platform support](platforms.md) for the full matrix.

## Current Status

KiteTorrent is **pre-1.0** and actively developed. It **downloads, seeds, and starts from magnet links, end-to-end.** The JVM path runs **567 tests** — 462 in the core's common suite, 105 in the session module — of which 566 pass; `Socks5UdpTest.proxiedUdpSocketWrapsAndUnwrapsThroughRelay` times out reproducibly. The suite includes loopback integration tests over real sockets:

- A TCP download from a seeder
- Two KiteTorrent engines exchanging a torrent, in plaintext and with MSE forced, then doing it again entirely over µTP
- A `ut_metadata` magnet metadata fetch, and a full magnet download between two engines
- A v2-only torrent exchanged between two engines
- SOCKS4, SOCKS5, SOCKS5-over-UDP and HTTP-CONNECT proxy runs, including a tunnelled tracker announce
- A real-file `FileDiskIo` round-trip
- Adversarial runs against a scripted peer that *withholds* blocks, proving the end-game duplication, `cancel`, and snubbing machinery recover

Correctness is anchored to ground truth, not just "it compiles":

- **FIPS vectors** for SHA-1/256/512
- **RFC 8032** for ed25519
- **Real torrents from libtorrent's own test suite**, with info-hashes cross-checked against an independent implementation
- **BEP golden bytes** for the DHT, ut_pex, and the UDP tracker

!!! note "What end-to-end means here"
    Two engines, started cold, can hand a torrent to each other over real sockets and verify every piece against its hash, for both TCP and µTP and for both `.torrent` and magnet starts. These are local Gradle runs. `.github/workflows/` contains only `docs.yml`, so nothing runs the test suites on push.

## Known defects

These ship today and fail silently. If you are building against KiteTorrent, read this section first.

- **HTTP and HTTPS tracker announces do nothing unless you pass an `HttpTracker`.** `KiteTorrentEngine`'s `httpTracker` parameter defaults to `null`, and both announce paths are null-safe calls. Nothing throws, nothing is logged, no alert is posted. Most public torrents list HTTP trackers, so the engine finds no peers and gives no reason. See [Getting started](getting-started.md).
- **`FileDiskIo` cannot resume or seed from data already on disk.** Its `checkExistingFiles()` returns an all-`false` array, and the recheck that `addTorrent` runs internally consults exactly that array. Point it at a complete copy and the session reports zero pieces and re-downloads the whole torrent over the existing files. Call `session.recheck(full = true)` after `addTorrent`. See [Seeding](seeding.md).
- **`progress()` counts pieces, not bytes, and does not exclude filtered pieces.** Any file set to `IGNORE` priority means `progress()` can never reach `1.0`, and `isSeeding()` never becomes true either.
- **`FileDiskIo`'s `dispatcher` parameter defaults to `Dispatchers.Default`**, putting blocking file syscalls on the CPU dispatcher. Pass `Dispatchers.IO`.
- **`anonymous_mode` does not force a proxy.** It skips the inbound listen socket, skips UPnP and NAT-PMP, and randomises the per-torrent peer id. Nothing refuses to dial when no proxy is configured.

## What's next

The core port is functionally complete and interoperable. The component-by-component map of what is done and what remains lives in [PORTING_STATUS.md](https://github.com/yuroyami/KiteTorrent/blob/main/PORTING_STATUS.md). The remaining work:

- **A threaded disk cache.** `DiskIo` is a suspending interface and `FileDiskIo` writes straight through to the file handle. There is no batching or write-back. libtorrent 2.0 removed its own disk cache in favour of mmap, so this is a smaller gap than it sounds.
- **The long-tail alert catalogue.** 51 alert classes are ported against libtorrent's roughly 100.
- **Path-MTU probing for µTP.** The MSS starts at 1400 and only shrinks — 64 bytes after two consecutive timeout rounds, floor 1212 — and never grows back. libtorrent runs a full `[mtu_floor, mtu_ceiling]` search.
- **Test coverage for the real-file disk layer.** `FileDiskIoTest` has one test, and every engine-level integration test uses `InMemoryDiskIo`. That is how the `checkExistingFiles` defect above shipped.
- **A fix for `Socks5UdpTest`.** It times out after 15 seconds in `UdpSocket.receive` against its own loopback relay, on every run.
- **A CI workflow that runs the tests.**

Four items that earlier versions of this page listed as outstanding have since landed: LEDBAT congestion control and retransmission for µTP, BitTorrent v2 hash requests, MSE encryption in both directions, and SOCKS4/SOCKS5/HTTP proxies.

## Reporting bugs

If a download stalls, a torrent fails to parse, or a peer interaction misbehaves, [open an issue](https://github.com/yuroyami/KiteTorrent/issues) with enough detail to reproduce: the `.torrent` or magnet link, the engine settings, and the alerts you observed. Networking code keeps its parse and build logic in pure, testable functions, so most fixes can land as a unit test plus a regression at the engine integration level.

## Contributing

Contributions are welcome. Check the [GitHub repository](https://github.com/yuroyami/KiteTorrent) for open issues and the porting status. Test additions, protocol fixes, and porting the remaining libtorrent surface are especially valuable. The [API reference](https://yuroyami.github.io/KiteTorrent/api/) is the place to confirm a signature before you build against it.

## License & credits

KiteTorrent is a derivative work of **libtorrent** by Arvid Norberg and contributors, distributed under the **BSD-3-Clause** license. The original copyright notices are retained per-file wherever code is ported directly. This port, meaning the Kotlin translation and the architecture around it, is provided under the same BSD-3-Clause terms.

This is an independent reimplementation. It is not affiliated with or endorsed by the libtorrent project.

## Acknowledgements

- **libtorrent** by Arvid Norberg and contributors: the reference implementation this project ports from, pinned to RC_2_0 (2.0.12)
- **The BEP authors**: the BitTorrent Enhancement Proposals that define the protocol and its extensions
- **The FIPS and RFC 8032 test vectors**, and the BEP golden bytes, that let this port check itself against ground truth
