# KiteTorrent

A BitTorrent engine written entirely in Kotlin. Use it to download or seed torrents
from shared Kotlin Multiplatform code. It is a Kotlin reimplementation of
[libtorrent](https://github.com/arvidn/libtorrent) 2.0.12, and it covers downloads,
seeding, magnet links, a DHT node, µTP, encryption and proxies.

[![Docs](https://img.shields.io/badge/docs-yuroyami.github.io-1f6feb)](https://yuroyami.github.io/KiteTorrent/)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.10-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Targets](https://img.shields.io/badge/targets-Android%20|%20iOS%20|%20JVM%20|%20JS-success)](#targets)
[![License](https://img.shields.io/badge/license-BSD--3--Clause-lightgrey)](#license-and-credits)

**[Documentation](https://yuroyami.github.io/KiteTorrent/)** · a guide per task, plus
the generated API reference.

## What you get

There is no JNI, no cinterop and no bundled native binary. The protocol stack, the
piece picker, the DHT, the trackers and the session loop are all `.kt` files. They
compile for Android, iOS and the JVM. The parts that do not need a socket also compile
for the browser.

KiteTorrent ships as two artifacts. `kitetorrent` is everything that is pure
computation: bencoding, hashing, `.torrent` and magnet parsing, the wire-protocol
codec, the piece picker, the DHT data structures. Its only dependency is
`kotlin-stdlib`. `kitetorrent-session` is the live engine: sockets, disk, trackers and
a DHT node. It adds coroutines, `ktor-network` and `kotlinx-io`. There is no
stdlib-only way to open a socket, so the core does not have one.

Downloads, seeding and magnet links all work end to end between two real engines over
real sockets. Two defaults fail silently, with no exception, no alert and no log entry.
Read [Limits](#limits) before you build against it.

```kotlin
import io.github.yuroyami.kitetorrent.session.disk.FileDiskIo
import io.github.yuroyami.kitetorrent.session.engine.KiteTorrentEngine
import io.github.yuroyami.kitetorrent.session.tracker.HttpTracker
import io.github.yuroyami.kitetorrent.torrent.TorrentInfo
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

val engine = KiteTorrentEngine(
    scope,
    // Leave this null and every http:// and https:// announce is skipped, silently.
    httpTracker = HttpTracker(HttpClient(CIO)),
    enableDht = true,
    enableUtp = true,
)
engine.start()

val torrent = TorrentInfo.parse(bytes)
val session = engine.addTorrent(
    torrent,
    // The dispatcher argument defaults to Dispatchers.Default. Pass Dispatchers.IO.
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

## Install

KiteTorrent is not on Maven Central. It is versioned `0.0.1-SNAPSHOT` and consumed
through `mavenLocal()`, so publish it yourself first:

```bash
./gradlew publishToMavenLocal
```

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            // Pure core: bencode, crypto, torrents, protocol, DHT data structures.
            implementation("io.github.yuroyami:kitetorrent:0.0.1-SNAPSHOT")

            // Live engine: sessions, peers, trackers, transports, disk.
            implementation("io.github.yuroyami:kitetorrent-session:0.0.1-SNAPSHOT")

            // Only needed to construct an HttpTracker: the session module depends on
            // ktor-client-core with `implementation`, so it is not on your classpath.
            implementation("io.ktor:ktor-client-core:3.5.1")
        }
        jvmMain.dependencies { implementation("io.ktor:ktor-client-cio:3.5.1") }
        iosMain.dependencies { implementation("io.ktor:ktor-client-darwin:3.5.1") }
    }
}
```

`mavenLocal()` has to be in your `repositories { }` block. Building KiteTorrent
from source needs JDK 21 and the Android SDK, with `sdk.dir` set in
`local.properties`.

## What it does

### Download from a magnet link

`addMagnet` pools peers from the DHT and the magnet's trackers. It fetches the metadata
over `ut_metadata` (BEP-9/10), then starts a normal download. It returns `null` when no
peer will supply the metadata.

```kotlin
val magnet = MagnetUri.parseMagnetUri("magnet:?xt=urn:btih:0496aa38...")
val session = engine.addMagnet(magnet) { info ->
    FileDiskIo(info.storage, "/downloads", Dispatchers.IO)
}
```

There is a second overload, `addMagnet(magnet, peers, diskFactory)`. It skips peer
discovery and fetches the metadata from the `List<PeerEndpoint>` you pass.

### Seed a torrent you already have

Add the torrent with a disk pointed at the existing files, then rehash them.
The `full = true` argument is required with `FileDiskIo`. See [Limits](#limits).

```kotlin
val session = engine.addTorrent(torrent, FileDiskIo(torrent.storage, "/srv/data", Dispatchers.IO))
session.recheck(full = true)   // hashes every piece; claims the ones that match
session.onStateChanged = { state -> println(state) }   // reaches TorrentState.SEEDING
```

Uploads run on periodic choke rounds. Each round ranks the interested peers by transfer
rate and fills `uploadSlots` with the fastest ones. One extra slot rotates between the
remaining peers, so a new peer can show what speed it offers.

### Encrypt peer connections

Message Stream Encryption works in both directions. Two settings drive it. The engine
inspects each inbound connection first. A connection that opens with a plaintext
BitTorrent handshake stays unencrypted, unless the policy forbids plaintext. Every
other inbound connection goes through the encrypted handshake.

```kotlin
val settings = SettingsPack().apply {
    setInt(IntSetting.OUT_ENC_POLICY, EncPolicy.PE_FORCED)
    setInt(IntSetting.IN_ENC_POLICY, EncPolicy.PE_FORCED)
}
val engine = KiteTorrentEngine(scope, settings = settings)
```

`PE_ENABLED` (the default) prefers encryption and accepts plaintext when the peer
cannot encrypt. `PE_FORCED` refuses plaintext. `PE_DISABLED` refuses encryption.

### Go through a proxy

SOCKS5, SOCKS4/4a and HTTP CONNECT are all implemented. Configure them through
`SettingsPack` before `start()`. The engine also negotiates a SOCKS5 UDP ASSOCIATE
relay for the shared UDP socket, so the DHT and µTP travel through the proxy as well.
The imperative setters (`engine.setSocks5Proxy(...)` and the equivalents for the other
kinds) only affect outbound TCP dials.

```kotlin
val settings = SettingsPack().apply {
    setInt(IntSetting.PROXY_TYPE, ProxyType.SOCKS5)
    setString(StringSetting.PROXY_HOSTNAME, "127.0.0.1")
    setInt(IntSetting.PROXY_PORT, 1080)
}
val engine = KiteTorrentEngine(scope, settings = settings, enableDht = true, enableUtp = true)
```

HTTP tracker announces are tunnelled only for `http://` URLs. An `https://`
announce always goes out directly on the ktor client you injected.

### Read and build torrents without an engine

The core artifact alone parses `.torrent` files and magnet URIs, hashes data and
produces v1, v2 and hybrid torrents. This is the part that runs in the browser.

```kotlin
val info = TorrentInfo.parse(bytes)
println("${info.name}: ${info.numFiles} files, ${info.totalSize} bytes")
println(info.infoHashHex())

val creator = CreateTorrent(storage).addTracker("udp://tracker.example:6969")
pieceHashes.forEachIndexed { i, h -> creator.setHash(i, h) }
val torrentBytes = creator.generateBuffer()
```

## Targets

| Target | `kitetorrent` | `kitetorrent-session` |
| --- | :-: | :-: |
| Android (minSdk 21) | yes | yes |
| iOS arm64 | yes | yes |
| iOS simulator arm64 | yes | yes |
| iOS x64 | yes | yes |
| JVM (JDK 21 toolchain) | yes | yes |
| JS (IR, browser and Node) | yes | no |

The session module has no JS target. Browsers expose no raw TCP or UDP socket, so
a BitTorrent session cannot run there. The core still compiles for JS, so parsing,
hashing and torrent creation work in the browser.

## Limits

**HTTP and HTTPS tracker announces do nothing unless you pass an `HttpTracker`.**
The `httpTracker` constructor parameter defaults to `null`. Both announce paths are
null-safe calls (`TorrentSession.kt:685`, `KiteTorrentEngine.kt:656`), so the announce
never happens. Nothing throws, nothing is logged and no alert is posted. Most public
torrents list HTTP trackers. You see an engine that finds no peers and reports no
error. Construct an `HttpTracker` as in the example above.

**`FileDiskIo` cannot resume or seed from data already on disk.** Point it at a
complete copy and the session reports zero pieces. It then downloads the whole torrent
again, over the files that are already there. Its `checkExistingFiles()` returns an
all-`false` array (`FileDiskIo.kt:216`), and `recheck()` defaults to `full = false`,
which reads exactly that array. `addTorrent` calls `start()`, which calls `recheck()`,
so there is no point where you can intervene. Call `session.recheck(full = true)` after
`addTorrent` to hash every piece. Saved resume data, passed as the third `addTorrent`
argument, avoids the problem completely, because it takes a different branch.
`InMemoryDiskIo` returns a real array, so no integration test caught this.

- `progress()` is `numHave / numPieces` over every piece in the torrent
  (`TorrentSession.kt:316`). It is not byte-weighted, and it does not subtract
  filtered pieces. Any file set to `IGNORE` priority means it can never reach
  `1.0`. `isSeeding()` has the same problem and never becomes true for such a
  torrent. The picker exposes the correct predicate, `isFinished()`, but the
  session does not call it.
- `FileDiskIo`'s `dispatcher` parameter defaults to `Dispatchers.Default`, which
  puts blocking file syscalls on the CPU dispatcher. Pass `Dispatchers.IO`.
- There is no threaded disk cache. Writes go straight through to the file handle
  under a per-file mutex. There is no batching either.
- The alert catalogue has 51 concrete classes, so some events have no alert you can
  observe.
- µTP does no path-MTU probing. The MSS starts at 1400 and only shrinks, by 64
  bytes after two consecutive timeout rounds, down to 1212. It never grows back.
- `anonymous_mode` does not force a proxy. It skips the inbound listen socket,
  skips UPnP and NAT-PMP, and gives each torrent a fresh random peer id. Nothing
  refuses to dial when no proxy is configured.
- `LocalInterfaces` returns loopback only on iOS; it needs an `<ifaddrs.h>`
  cinterop.
- Nothing is published to Maven Central, and no CI runs the tests.

## Testing

The JVM path runs 567 tests: 462 in the core's common suite and 105 in the session
module. 566 pass. `Socks5UdpTest.proxiedUdpSocketWrapsAndUnwrapsThroughRelay` times
out after 15 seconds against its own loopback relay. That one failure repeats on every
local run, and nothing else fails.

The suites check their results against published reference data: FIPS vectors for
SHA-1/256/512, RFC 8032 for ed25519, real `.torrent` files whose info-hashes are
cross-checked against an independent implementation, and BEP golden bytes for the DHT,
ut_pex and the UDP tracker.

```bash
./gradlew :kitetorrent:jvmTest              # pure-core suite
./gradlew :kitetorrent-session:jvmTest      # engine suite incl. the loopback runs
```

The loopback tests run over real sockets:

| Test | What it exercises |
| --- | --- |
| `LoopbackDownloadTest` | a TCP download from a seeder |
| `TwoEngineExchangeTest` | two engines exchanging a torrent, in plaintext and with MSE forced |
| `UtpEngineExchangeTest` | the same exchange entirely over µTP |
| `MagnetEndToEndTest` | a `ut_metadata` fetch and a full magnet download |
| `V2ExchangeTest` | a v2-only torrent |
| `Wave5RobustnessTest`, `Wave6RobustnessTest` | a scripted peer that withholds blocks |
| `RateLimiterTest` | rate limiting |
| the proxy suite | SOCKS4, SOCKS5 and HTTP CONNECT |

Two gaps are worth knowing. `FileDiskIoTest` contains a single test, and every
engine-level integration test uses `InMemoryDiskIo`. That is how the
`checkExistingFiles` defect above went unnoticed. `.github/workflows/` contains only
`docs.yml`, so no workflow runs these tests on push.

## License and credits

KiteTorrent is a derivative work of **libtorrent** by Arvid Norberg and contributors,
distributed under the **BSD-3-Clause** license. The original copyright notices are kept
per file wherever code was translated directly. The Kotlin code and the architecture
around it use the same terms. This is an independent reimplementation. It is not
affiliated with or endorsed by the original project.

Part of the Kite family: [KiteCore](https://github.com/yuroyami/KiteCore),
[KitePDF](https://github.com/yuroyami/KitePDF).
