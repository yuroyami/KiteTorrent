# Engine settings

Tune the KiteTorrent engine the way libtorrent does: through a ported `SettingsPack` plus a handful of direct engine knobs. This page covers the settings model, rate limiting, the TCP and uTP transports, the DHT node, trackers, proxies, encryption, and port mapping. Everything here is configured from shared Kotlin code and behaves the same on every target the session module ships for.

## Where settings live

KiteTorrent has two kinds of configuration, and the split matters:

| Kind | Set where | Examples |
|---|---|---|
| **Engine-level** | `KiteTorrentEngine(...)` constructor or direct setters | `listenPort`, `enableDht`, `enableUtp`, `httpTracker`, rate limits, connection cap |
| **`SettingsPack`** | a `SettingsPack` passed at construction or applied later | `piece_timeout`, `request_queue_time`, `strict_end_game_mode`, `connections_limit` |

The `SettingsPack` is the ported libtorrent knob bag. The engine reads its values (and falls back to libtorrent's own defaults when a key is unset). The constructor parameters that are not in `SettingsPack` — `peerId`, `listenPort`, `httpTracker`, `enableDht`, `enableUtp`, `clock`, `httpClient`, `gateway` — are engine wiring, not settings.

```kotlin
import io.github.yuroyami.kitetorrent.session.engine.KiteTorrentEngine
import io.github.yuroyami.kitetorrent.session.tracker.HttpTracker
import io.github.yuroyami.kitetorrent.settings.SettingsPack
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO

val engine = KiteTorrentEngine(
    scope        = scope,
    listenPort   = 6881,
    httpTracker  = HttpTracker(HttpClient(CIO)),
    enableDht    = true,
    enableUtp    = true,
    settings     = SettingsPack(),   // libtorrent defaults
)
engine.start()
```

### Constructor parameters

| Parameter | Default | What it does |
|---|---|---|
| `scope` | — | The `CoroutineScope` every engine coroutine runs in. |
| `peerId` | random | The 20-byte peer ID advertised in handshakes. |
| `listenPort` | `6881` | Requested TCP/UDP listen port. The actual port lands in `boundListenPort`. |
| `httpTracker` | `null` | The `HttpTracker` used for `http://` and `https://` announces. Left null, those announces are skipped silently (see [Trackers](#trackers)). |
| `enableDht` | `false` | Brings up the live DHT node. |
| `enableUtp` | `false` | Enables the uTP transport (BEP-29). |
| `clock` | `{ 0L }` | Epoch-seconds provider used for DHT expiry. |
| `settings` | `SettingsPack()` | The settings pack the engine reads live. |
| `httpClient` | `null` | ktor client for the UPnP SOAP flow. Null skips UPnP; NAT-PMP still runs when `gateway` is set. |
| `gateway` | `null` | LAN default-gateway IPv4 literal for NAT-PMP. Null disables NAT-PMP. |

!!! note
    The session engine lives in `:kitetorrent-session`. It targets Android, iOS, and the JVM. There is no JS target: browsers have no raw TCP/UDP sockets, so a live engine cannot run there. The pure `:kitetorrent` core (which is where `SettingsPack` is defined) does compile for JS, so you can build and inspect a settings pack anywhere.

## SettingsPack

`SettingsPack` is a typed map keyed by integer setting IDs. You read and write through three primitive accessors, one per value type:

```kotlin
import io.github.yuroyami.kitetorrent.settings.SettingsPack
import io.github.yuroyami.kitetorrent.settings.IntSetting
import io.github.yuroyami.kitetorrent.settings.BoolSetting

val pack = SettingsPack()

pack.setInt(IntSetting.PIECE_TIMEOUT, 15)
pack.setInt(IntSetting.REQUEST_QUEUE_TIME, 3)
pack.setInt(IntSetting.CONNECTIONS_LIMIT, 200)
pack.setBool(BoolSetting.STRICT_END_GAME_MODE, true)

val timeout = pack.getInt(IntSetting.PIECE_TIMEOUT)
val strict  = pack.getBool(BoolSetting.STRICT_END_GAME_MODE)
```

The Kotlin constants are the upper-case form of libtorrent's setting names: `piece_timeout` is `IntSetting.PIECE_TIMEOUT`, `strict_end_game_mode` is `BoolSetting.STRICT_END_GAME_MODE`, and so on. This page uses the libtorrent names in prose and the Kotlin constants in code.

The setting keys are grouped by value type into three objects:

- `StringSetting`: string-valued keys
- `IntSetting`: integer-valued keys
- `BoolSetting`: boolean-valued keys

Other members let you query and clear entries:

```kotlin
pack.hasVal(IntSetting.PIECE_TIMEOUT)   // is this key set?
pack.clear(IntSetting.PIECE_TIMEOUT)    // unset one key
pack.clear()                            // unset everything
```

!!! tip
    A `SettingsPack` only holds the keys you set. Anything you leave untouched falls through to libtorrent's compiled-in default, so you only have to override what you actually want to change.

### The knobs the engine reads

The scheduler and session loop consume these settings live. The defaults are libtorrent's own.

| Setting | What it controls |
|---|---|
| `piece_timeout` | Seconds a peer may hold an outstanding request before it counts as stalled (drives snubbing). |
| `request_timeout` | When a block stuck on a slow peer is recycled to another. |
| `request_queue_time` | Target seconds of work in a peer's pipeline; the dynamic queue depth is derived from this times the measured rate. |
| `max_out_request_queue` | Hard cap on per-peer pipeline depth. |
| `initial_picker_threshold` | Below this many owned pieces, the picker uses random-walk selection instead of strict rarest-first. |
| `strict_end_game_mode` | Gates end-game double-requests while untouched pieces remain. |
| `unchoke_interval` | Seconds between choke rounds. |
| `unchoke_slots_limit` | How many peers get unchoked per round. |
| `connections_limit` | Session-wide cap on simultaneous peer connections (default 200). |
| `download_rate_limit` / `upload_rate_limit` | Session-wide byte-per-second caps (see [Rate limiting](#rate-limiting)). |

### Applying settings after start

Pass a pack at construction for the initial configuration, or apply a new one at runtime:

```kotlin
val newPack = SettingsPack().apply {
    setInt(IntSetting.CONNECTIONS_LIMIT, 400)
    setBool(BoolSetting.STRICT_END_GAME_MODE, false)
}
engine.applySettings(newPack)
```

`applySettings` is a `suspend` function: call it from a coroutine.

## Rate limiting

KiteTorrent ports libtorrent's `bandwidth_manager` and runs it live. There are two scopes:

- **Session-wide**: one global cap shared across every torrent.
- **Per-torrent**: each torrent draws from its own channel under the global one.

Enforcement happens on both sides of the wire. Uploads are gated before the send; downloads apply back-pressure on the receive loop, so a capped download actually slows the peer rather than buffering without bound.

Set the session-wide caps directly on the engine, in bytes per second:

```kotlin
engine.setDownloadRateLimit(2_000_000)   // 2 MB/s down
engine.setUploadRateLimit(500_000)       // 500 KB/s up
```

Pass `0` to mean unlimited. The same limits are also reachable as `download_rate_limit` / `upload_rate_limit` in `SettingsPack`; the direct setters are the convenient path for the common case.

!!! note
    Rate limits feed back into the scheduler. The per-peer request queue depth is derived from the measured rate, so a tighter cap naturally shrinks each peer's pipeline rather than fighting it.

## Transports

KiteTorrent speaks two peer transports, and they coexist on the same engine.

### TCP

The default. Standard BitTorrent over TCP, inbound and outbound, on the engine's listen port.

### uTP (BEP-29)

Enable uTP at construction with `enableUtp = true`. With it on:

- **Outgoing** dials try uTP first and fall back to TCP if the uTP handshake does not take.
- **Inbound** uTP connections are accepted and routed to the right torrent by info-hash, the same way inbound TCP is.
- **The DHT shares the same UDP socket.** uTP and DHT packets arrive on one UDP port and are demultiplexed by inspecting the packet; you do not run a second socket for the DHT.

```kotlin
val engine = KiteTorrentEngine(
    scope     = scope,
    enableUtp = true,
    enableDht = true,   // shares the uTP UDP socket
)
engine.start()
```

You can observe how many connections are using each transport:

```kotlin
val total = engine.numConnections()   // suspend: all peer connections
val utp   = engine.numUtpStreams()    // suspend: just the uTP streams
```

!!! note "Congestion control"
    The uTP stream runs LEDBAT: it tracks a rolling-minimum base delay, measures queuing delay against a 100 ms target, and scales the send window by how far the measurement sits from that target, so uTP backs off when the link starts queuing. Slow-start exits either at `ssthresh` or as soon as queuing delay crosses the target, and the window only grows while the writer is actually saturating it. Around that sit a retransmission timer with exponential backoff, go-back-N resend, selective ACK in both directions, duplicate-ACK fast resend, and Nagle coalescing.

## DHT

Set `enableDht = true` to bring up the live DHT node. Once started, it bootstraps into the network, runs `get_peers` to find swarms, and `announce`s your presence for torrents you are sharing.

Bootstrap into the public routers (or your own):

```kotlin
engine.bootstrapDht(
    listOf(
        "router.bittorrent.com" to 6881,
        "dht.transmissionbt.com" to 6881,
        "router.utorrent.com" to 6881,
    )
)
```

`bootstrapDht` is a `suspend` function.

### Persisting DHT state

A warm routing table makes the next launch faster. Serialize it on shutdown and restore it on the next start:

```kotlin
val state: ByteArray? = engine.saveDhtState()
// ...persist `state` to disk...

// next launch:
engine.restoreDhtState(state)
```

`shutdownGraceful()` returns the serialized DHT state as well, so a clean shutdown gives you the snapshot in one call. See [Magnet links](magnets.md) for how the DHT feeds peer discovery when you start from a magnet.

## Trackers

The engine announces over both tracker transports:

- **HTTP/HTTPS** trackers via `HttpTracker`, which you pass to the constructor.
- **UDP** trackers via `UdpTracker` (BEP-15), which the engine builds itself.

!!! warning "`httpTracker` defaults to null"
    Both announce paths — the per-session one and `announceForPeers`, which backs `discoverPeers` and the single-argument `addMagnet` — call the tracker null-safely. An engine constructed without an `HttpTracker` therefore skips every `http://` and `https://` announce: no exception, no alert, no log entry, and no peers from those trackers. A magnet whose tracker list is entirely HTTP then finds nothing and `addMagnet` returns `null`.

```kotlin
import io.github.yuroyami.kitetorrent.session.tracker.HttpTracker
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO

val engine = KiteTorrentEngine(
    scope,
    httpTracker = HttpTracker(HttpClient(CIO)),
    enableDht = true,
)
```

`HttpTracker(client, maxResponseBytes, network)` takes the ktor `HttpClient` from you so the platform engine is your choice: `ktor-client-cio` on JVM and Android, `ktor-client-darwin` on iOS. The session module depends on `ktor-client-core` as `implementation` rather than `api`, so add `io.ktor:ktor-client-core` and a client engine to your own build (Ktor 3.5.1). `maxResponseBytes` bounds a tracker reply body at 2 MiB by default and rejects anything larger with `PACKET_TOO_LARGE`. `network` is what lets a plain `http://` announce go through a proxy; see [Proxies](#proxies).

For most flows you do not touch the tracker classes directly; you add a torrent and the engine announces to the trackers in its metadata. When you do want to drive discovery by hand, the engine exposes it:

```kotlin
// Announce and collect peers (DHT + the given trackers)
val peers: List<PeerEndpoint> =
    engine.discoverPeers(infoHash, trackers = listOf("udp://tracker.example:1337"))

// Scrape seeder/leecher counts without announcing
val counts: ScrapeResponse? =
    engine.scrape(infoHash, trackers = listOf("https://tracker.example/announce"))
```

Both are `suspend` functions. `ScrapeResponse` carries `seeders`, `leechers`, and `completed`.

## Proxies

Outbound TCP dials, both peers and trackers, can go through a proxy. Three kinds are implemented in `session.net`: SOCKS5 (RFC 1928, with RFC 1929 username/password auth), SOCKS4/4a, and HTTP CONNECT.

The declarative path is the settings pack, which the engine maps onto its runtime proxy in `applyProxyFromSettings()`:

```kotlin
import io.github.yuroyami.kitetorrent.settings.ProxyType
import io.github.yuroyami.kitetorrent.settings.StringSetting

val pack = SettingsPack().apply {
    setInt(IntSetting.PROXY_TYPE, ProxyType.SOCKS5_PW)
    setString(StringSetting.PROXY_HOSTNAME, "127.0.0.1")
    setInt(IntSetting.PROXY_PORT, 1080)
    setString(StringSetting.PROXY_USERNAME, "user")
    setString(StringSetting.PROXY_PASSWORD, "secret")
}
```

`ProxyType` values are `NONE`, `SOCKS4`, `SOCKS5`, `SOCKS5_PW`, `HTTP`, `HTTP_PW` and `I2P_PROXY`. `NONE` clears any proxy; the I2P type is not wired and leaves the current proxy untouched, as does a blank hostname.

There are imperative setters as well:

```kotlin
engine.setSocks5Proxy("127.0.0.1", 1080, username = "user", password = "secret")
engine.setSocks4Proxy("127.0.0.1", 1080, username = "user")
engine.setHttpProxy("proxy.example", 3128)
```

!!! warning "The setters lose to the settings pack"
    `start()` and `applySettings()` both call `applyProxyFromSettings()`, and `proxy_type` defaults to `NONE`, which clears the proxy. A proxy set with `setSocks5Proxy` before `start()`, or before an `applySettings` call whose pack does not carry `PROXY_TYPE`, is discarded. Either set the proxy through the settings pack, or call the setter after `start()` and after every `applySettings`.

With a SOCKS5 proxy the engine also opens a UDP ASSOCIATE relay and routes the shared UDP socket through it, so DHT traffic and uTP travel over the proxy on the same physical socket. SOCKS4 and HTTP CONNECT have no UDP mode, so UDP stays direct with those.

`HttpTracker` tunnels an announce through the proxy only for plain `http://` URLs, and only when it was constructed with a `NetworkRuntime` as its third argument. An `https://` announce always goes out directly on the injected ktor client, because TLS is not run over the raw tunnel. The engine builds its own `NetworkRuntime` (`engine.network`) during construction, so an `HttpTracker` handed to the constructor has `network = null` and every announce takes the ktor client's own path.

### anonymous_mode

`BoolSetting.ANONYMOUS_MODE` does three things and no more:

- `start()` opens no listen socket, so there are no inbound connections and no identifying listen port.
- UPnP and NAT-PMP are skipped.
- Each torrent gets a freshly generated random peer ID instead of the engine-wide `peerId`.

It does not require or force a proxy. With no proxy configured, the engine still dials out directly; nothing refuses to connect. Configure a proxy yourself if that is what you are after.

## Encryption (MSE)

Message Stream Encryption runs on live connections in both directions. Outgoing dials go through `Mse.initiate`; inbound connections are sniffed with `Mse.looksLikePlaintextHandshake` and, when they are not plaintext, handed to `Mse.accept`. Two settings decide the policy, using libtorrent's `enc_policy` values (`EncPolicy.PE_FORCED`, `PE_ENABLED`, `PE_DISABLED`):

| Setting | Effect |
|---|---|
| `IntSetting.OUT_ENC_POLICY` | Whether outgoing connections are encrypted, attempt encryption with a plaintext fallback, or stay plaintext. |
| `IntSetting.IN_ENC_POLICY` | `PE_FORCED` drops an inbound plaintext handshake; `PE_DISABLED` drops an inbound encrypted one. |

`IntSetting.ALLOWED_ENC_LEVEL` and `BoolSetting.PREFER_RC4` are declared in the settings pack but nothing in the session module reads them.

## Port mapping

To accept inbound connections behind a home router, the listen port needs to be forwarded. KiteTorrent ports both mapping protocols:

- **UPnP**: `io.github.yuroyami.kitetorrent.session.net.Upnp`
- **NAT-PMP**: `io.github.yuroyami.kitetorrent.session.net.NatPmp`

Both are gated on settings (`enable_upnp`, `enable_natpmp`) and on constructor wiring. UPnP needs an `httpClient` to drive the IGD SOAP flow, and is skipped when that is null. NAT-PMP needs the LAN gateway address, which common Kotlin cannot look up, so you pass it as `gateway`; null disables NAT-PMP.

```kotlin
val engine = KiteTorrentEngine(
    scope       = scope,
    listenPort  = 6881,
    httpTracker = HttpTracker(HttpClient(CIO)),
    httpClient  = HttpClient(CIO),   // UPnP is skipped without this
    gateway     = "192.168.1.1",     // NAT-PMP is skipped without this
)
```

After `start()`, the actual bound port is available as `boundListenPort` (it may differ from the requested `listenPort` if that port was taken). The engine's discovered public address, when known, is `externalIp`.

## The connection cap

The session enforces a single connection budget shared by every torrent. It is `connections_limit` in the settings pack (default 200) and is checked on both dial and accept, so neither outbound dials nor inbound accepts can push the session over its budget.

Set it through the pack, or use the direct engine setter:

```kotlin
engine.setConnectionsLimit(300)
```

Per-torrent limits sit underneath the session cap. Each `TorrentSession` carries its own `maxPeers` and `uploadSlots`:

```kotlin
val session = engine.addTorrent(torrent, disk)
session.maxPeers    = 60
session.uploadSlots = 8
```

A torrent never exceeds its own `maxPeers`, and the whole session never exceeds `connections_limit`.

## Putting it together

A fully configured engine, start to finish:

```kotlin
import io.github.yuroyami.kitetorrent.session.engine.KiteTorrentEngine
import io.github.yuroyami.kitetorrent.session.tracker.HttpTracker
import io.github.yuroyami.kitetorrent.settings.SettingsPack
import io.github.yuroyami.kitetorrent.settings.IntSetting
import io.github.yuroyami.kitetorrent.settings.BoolSetting
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO

val settings = SettingsPack().apply {
    setInt(IntSetting.CONNECTIONS_LIMIT, 200)
    setInt(IntSetting.PIECE_TIMEOUT, 15)
    setInt(IntSetting.REQUEST_QUEUE_TIME, 3)
    setBool(BoolSetting.STRICT_END_GAME_MODE, true)
}

val engine = KiteTorrentEngine(
    scope       = scope,
    listenPort  = 6881,
    httpTracker = HttpTracker(HttpClient(CIO)),
    enableDht   = true,
    enableUtp   = true,
    gateway     = "192.168.1.1",
    settings    = settings,
)
engine.start()

engine.setDownloadRateLimit(2_000_000)
engine.setUploadRateLimit(500_000)

engine.bootstrapDht(
    listOf("router.bittorrent.com" to 6881)
)
```

## Status

The settings model, rate limiter, both transports, the DHT node, both tracker types, and port mapping all run today and are exercised by the loopback integration suite, with dedicated tests for a rate-limited download and for the connection cap.

uTP congestion control is in: LEDBAT against a 100 ms target delay, a retransmission timer with exponential backoff, go-back-N resend, selective ACK both ways, and duplicate-ACK fast resend, covered by `UtpLedbatTest`, `UtpCongestionTest`, `UtpRetransmitTest`, `UtpFastResendTest` and `UtpFlowControlTest`. Proxies are in: SOCKS5 including UDP ASSOCIATE, SOCKS4/4a and HTTP CONNECT, covered by `Socks5Test`, `Socks5UdpTest`, `Socks4Test`, `HttpProxyTest` and `HttpTrackerProxyTest`. MSE runs on live connections in both directions with plaintext sniffing on the inbound path, covered by `MseHandshakeTest` and `TwoEngineExchangeTest.twoEnginesExchangeWithEncryptionForced`.

What is not here: there is no threaded disk cache (libtorrent 2.0 removed its own disk cache in favour of mmap, so the gap is narrower than it sounds), and the alert catalogue is 51 classes against libtorrent's roughly 100. The detailed map is in [About & status](about.md).

## Related

- [Getting started](getting-started.md): your first download in a few lines.
- [Downloading](downloading.md): add a `.torrent`, watch progress, save resume data.
- [Magnet links](magnets.md): fetch metadata over DHT and trackers, then download.
- [Seeding](seeding.md): serve pieces, choking, and upload slots.
- [API reference](https://yuroyami.github.io/KiteTorrent/api/): every type and signature.
