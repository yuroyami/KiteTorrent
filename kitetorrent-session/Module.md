# Module kitetorrent-session

The live engine: the part that actually opens sockets and writes files.

`KiteTorrentEngine` and `TorrentSession`, peer connections over TCP and µTP,
HTTP and UDP trackers, a DHT node, web seeds, UPnP and NAT-PMP, SOCKS and HTTP
proxies, MSE encryption, rate limiting and `DiskIo`. Built on coroutines,
ktor-network and kotlinx-io. Not available on JS, which has no sockets.
