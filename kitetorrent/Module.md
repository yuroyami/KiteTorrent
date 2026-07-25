# Module kitetorrent

The pure part of the BitTorrent protocol: everything that is computation.

Bencoding, SHA-1/256/512, ed25519, the MSE primitives, `.torrent` and magnet
parsing with v1/v2/hybrid info-hashes, torrent creation, the peer wire-protocol
codec, the piece picker, the DHT data structures and the alert catalogue. No
sockets, no disk, no dependency beyond `kotlin-stdlib`.
