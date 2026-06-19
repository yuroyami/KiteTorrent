# Engine settings

Tune the KiteTorrent engine the way libtorrent does: through a ported `SettingsPack` plus a handful of direct engine knobs. This page covers the settings model, rate limiting, the TCP and uTP transports, the DHT node, trackers, and port mapping. Everything here is configured from shared Kotlin code and behaves the same on every target the session module ships for.

## Where settings live

KiteTorrent has two kinds of configuration, and the split matters:

| Kind | Set where | Examples |
|---|---|---|
| **Engine-level** | `KiteTorrentEngine(...)` constructor or direct setters | `listenPort`, `enableDht`, `enableUtp`, rate limits, connection cap |
| **`SettingsPack`** | a `SettingsPack` passed at construction or applied later | `piece_timeout`, `request_queue_time`, `strict_end_game_mode`, `connections_limit` |

The `SettingsPack` is the ported libtorrent knob bag. The engine reads its values (and falls back to libtorrent's own defaults when a key is unset). The constructor parameters that are not in `SettingsPack`: `peerId`, `listenPort`, `enableDht`, `enableUtp`, `httpTracker`, `clock`, `httpClient`, `gateway`, are engine wiring, not settings.

```kotlin
val engine = KiteTorrentEngine(
    scope        = scope,
    listenPort   = 6881,
    enableDht    = true,
    enableUtp    = true,
    settings     = SettingsPack(),   // libtorrent defaults
)
engine.start()
```

!!! note
    The session engine lives in `:kitetorrent-session`. It targets Android, iOS, and the JVM. There is no JS target: browsers have no raw TCP/UDP sockets, so a live engine cannot run there. The pure `:kitetorrent` core (which is where `SettingsPack` is defined) does compile for JS, so you can build and inspect a settings pack anywhere.

## SettingsPack

`SettingsPack` is a typed map keyed by integer setting IDs. You read and write through three primitive accessors, one per value type:

```kotlin
import io.github.yuroyami.kitetorrent.settings.SettingsPack
import io.github.yuroyami.kitetorrent.settings.IntSetting
import io.github.yuroyami.kitetorrent.settings.BoolSetting

val pack = SettingsPack()

pack.setInt(IntSetting.piece_timeout, 15)
pack.setInt(IntSetting.request_queue_time, 3)
pack.setInt(IntSetting.connections_limit, 200)
pack.setBool(BoolSetting.strict_end_game_mode, true)

val timeout = pack.getInt(IntSetting.piece_timeout)
val strict  = pack.getBool(BoolSetting.strict_end_game_mode)
```

The setting keys are grouped by value type into three objects:

- `StringSetting`: string-valued keys
- `IntSetting`: integer-valued keys
- `BoolSetting`: boolean-valued keys

Other members let you query and clear entries:

```kotlin
pack.hasVal(IntSetting.piece_timeout)   // is this key set?
pack.clear(IntSetting.piece_timeout)    // unset one key
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
    setInt(IntSetting.connections_limit, 400)
    setBool(BoolSetting.strict_end_game_mode, false)
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

!!! warning
    uTP currently runs a **fixed send window**. LEDBAT congestion control and the retransmission timer are not yet ported, so uTP does not yet yield to other traffic the way upstream libtorrent does. This is tracked in [About & status](about.md). TCP is unaffected.

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

- **HTTP/HTTPS** trackers via `HttpTracker`.
- **UDP** trackers via `UdpTracker` (BEP-15).

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

## Port mapping

To accept inbound connections behind a home router, the listen port needs to be forwarded. KiteTorrent ports both mapping protocols:

- **UPnP**: `io.github.yuroyami.kitetorrent.session.net.Upnp`
- **NAT-PMP**: `io.github.yuroyami.kitetorrent.session.net.NatPmp`

Pass the router address through the `gateway` constructor parameter so the engine can map its listen port:

```kotlin
val engine = KiteTorrentEngine(
    scope      = scope,
    listenPort = 6881,
    gateway    = "192.168.1.1",
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
import io.github.yuroyami.kitetorrent.settings.SettingsPack
import io.github.yuroyami.kitetorrent.settings.IntSetting
import io.github.yuroyami.kitetorrent.settings.BoolSetting

val settings = SettingsPack().apply {
    setInt(IntSetting.connections_limit, 200)
    setInt(IntSetting.piece_timeout, 15)
    setInt(IntSetting.request_queue_time, 3)
    setBool(BoolSetting.strict_end_game_mode, true)
}

val engine = KiteTorrentEngine(
    scope      = scope,
    listenPort = 6881,
    enableDht  = true,
    enableUtp  = true,
    gateway    = "192.168.1.1",
    settings   = settings,
)
engine.start()

engine.setDownloadRateLimit(2_000_000)
engine.setUploadRateLimit(500_000)

engine.bootstrapDht(
    listOf("router.bittorrent.com" to 6881)
)
```

## Status

The settings model, rate limiter, both transports, the DHT node, both tracker types, and port mapping all run today and are exercised by the loopback integration suite (a rate-limited download and the connection cap both have dedicated tests). The known gap is uTP congestion control: the stream uses a fixed window without LEDBAT or retransmission yet. Proxy support (`setSocks5Proxy` and friends) and MSE encryption wiring are follow-up work. The honest, detailed map is in [About & status](about.md).

## Related

- [Getting started](getting-started.md): your first download in a few lines.
- [Downloading](downloading.md): add a `.torrent`, watch progress, save resume data.
- [Magnet links](magnets.md): fetch metadata over DHT and trackers, then download.
- [Seeding](seeding.md): serve pieces, choking, and upload slots.
- [API reference](https://yuroyami.github.io/KiteTorrent/api/): every type and signature.
