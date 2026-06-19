# About KiteTorrent

**A pure-Kotlin BitTorrent engine for every target.** KiteTorrent is a from-scratch port of [libtorrent](https://github.com/arvidn/libtorrent) (Rasterbar) to Kotlin Multiplatform: no JNI, no cinterop, no native blob. One Kotlin codebase handles bencoding, hashing, `.torrent` and magnet parsing, the wire protocol, the piece picker, the DHT, trackers, and the live download/seed session across Android, iOS, JVM, and (for the pure core) the browser.

## Philosophy

Every Kotlin Multiplatform app that touches torrents today shells out to a C++ library through JNI on Android and a hand-rolled bridge on iOS. You inherit two engines, two sets of bugs, and a build that has to ship a native binary per platform.

KiteTorrent is the opposite. The protocol stack is written in Kotlin and runs as the same `.kt` everywhere Kotlin runs. A torrent is just bytes; bugs are ours to fix.

That said, the project is honest about where the stdlib ends. BitTorrent's whole reason to exist is networking, concurrency, and disk I/O, and there is no stdlib-only way to open a socket. So the library splits along that fault line:

- **`:kitetorrent`** is the pure core: bencoding, crypto, `.torrent` parsing, the wire-protocol codec, the piece picker, the DHT data structures, settings, alerts. Its only dependency is `kotlin-stdlib`, and it targets Android, iOS, JVM, and JS.
- **`:kitetorrent-session`** is the live engine: the session loop, sockets, disk, trackers, the DHT node, and µTP. It is built on `kotlinx.coroutines`, `ktor-network`, and `kotlinx-io`, and targets Android, iOS, and JVM. There is no JS target here, because browsers have no raw TCP/UDP sockets.

The core never pretends to do something it cannot. See [Platform support](platforms.md) for the full matrix.

## Current Status

KiteTorrent is **pre-1.0** and actively developed. It **downloads, seeds, and starts from magnet links, end-to-end.** **532 tests pass**, including loopback integration tests over real sockets:

- A TCP download from a seeder
- Two KiteTorrent engines exchanging a torrent, then doing it again entirely over µTP
- A `ut_metadata` magnet metadata fetch, and a full magnet download between two engines
- A real-file `FileDiskIo` round-trip
- Adversarial runs against a scripted peer that *withholds* blocks, proving the end-game duplication, `cancel`, and snubbing machinery recover

Correctness is anchored to ground truth, not just "it compiles":

- **FIPS vectors** for SHA-1/256/512
- **RFC 8032** for ed25519
- **Real torrents from libtorrent's own test suite**, with info-hashes cross-checked against an independent implementation
- **BEP golden bytes** for the DHT, ut_pex, and the UDP tracker

!!! note "What end-to-end means here"
    Two engines, started cold, can hand a torrent to each other over real sockets and verify every piece against its hash. That path is exercised in CI for both TCP and µTP, and for both `.torrent` and magnet starts.

## What's next

The core port is functionally complete and interoperable. The full, honest map of what is done and what remains lives in [PORTING_STATUS.md](https://github.com/yuroyami/KiteTorrent/blob/main/PORTING_STATUS.md). The remaining work:

- **LEDBAT congestion control + retransmission for µTP.** The µTP stream still runs a fixed send window with no LEDBAT delay control or retransmission timer.
- **A threaded disk cache.** `DiskIo` is a suspending interface today; the cached, batched write-back path is still to come.
- **BitTorrent v2 *download* (hash requests).** v2 metadata, merkle trees, and hybrid info-hashes parse and validate; the live hash-request download path is not wired yet.
- **MSE encryption wiring.** The handshake codec and the crypto primitives (RC4, Diffie-Hellman) exist in the core; full integration into the wire protocol is pending.
- **Proxies.** SOCKS4/5 and HTTP proxy plumbing.
- **The long-tail alert catalogue.** A common subset of alerts is ported; the full ~100-type catalogue is not.

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
