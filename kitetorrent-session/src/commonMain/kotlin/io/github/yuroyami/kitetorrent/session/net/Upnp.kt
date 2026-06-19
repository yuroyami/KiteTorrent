package io.github.yuroyami.kitetorrent.session.net

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText

/**
 * UPnP IGD port mapping — port of the SSDP-discovery + SOAP-control half of
 * libtorrent's `upnp` (upnp.cpp). Opens an inbound port on a NAT gateway so peers can
 * reach us. SSDP runs over UDP multicast; the control step is plain SOAP-over-HTTP.
 *
 * The codec functions ([mSearchRequest], [parseLocation], [findControlUrl],
 * [resolveControlUrl], [addPortMappingBody], [deletePortMappingBody]) are total
 * functions of their arguments with no I/O. The driver functions ([discover],
 * [describe], [openIgd], [mapTcpAndUdp], [removeMapping], …) layer the UDP/HTTP
 * round-trips on top — the engine schedules them (initial map plus a lease-refresh
 * timer); this file owns the protocol.
 *
 * XML is built/scanned as strings (no XML library — the documents are tiny and fixed),
 * exactly as libtorrent does with its hand-rolled `xml_parse` / `find_control_url`.
 */
object Upnp {

    const val SSDP_HOST = "239.255.255.250"
    const val SSDP_PORT = 1900
    const val IGD_DEVICE = "urn:schemas-upnp-org:device:InternetGatewayDevice:1"

    /** WANIPConnection v1 — the default service type libtorrent emits in SOAP. */
    const val WAN_IP_SERVICE = "urn:schemas-upnp-org:service:WANIPConnection:1"

    /**
     * The IGD service types `find_control_url` accepts, in libtorrent's priority order
     * (`upnp.cpp` `find_control_url`). The first matching `<serviceType>` in the device
     * description wins.
     */
    val ACCEPTED_SERVICE_TYPES: List<String> = listOf(
        "urn:schemas-upnp-org:service:WANIPConnection:1",
        "urn:schemas-upnp-org:service:WANIPConnection:2",
        "urn:schemas-upnp-org:service:WANPPPConnection:1",
    )

    /** Default UPnP lease duration in seconds (`settings_pack::upnp_lease_duration` default). */
    const val DEFAULT_LEASE_SECONDS = 3600

    /** The SSDP `M-SEARCH` datagram that asks IGDs on the LAN to announce themselves. */
    fun mSearchRequest(searchTarget: String = IGD_DEVICE, mx: Int = 2): ByteArray = buildString {
        append("M-SEARCH * HTTP/1.1\r\n")
        append("HOST: $SSDP_HOST:$SSDP_PORT\r\n")
        append("MAN: \"ssdp:discover\"\r\n")
        append("MX: $mx\r\n")
        append("ST: $searchTarget\r\n")
        append("\r\n")
    }.encodeToByteArray()

    /**
     * Broadcast an `M-SEARCH` and collect the `LOCATION` URLs of responding gateways
     * (the device-description XML URLs). Sends a few times since UDP multicast is lossy.
     */
    suspend fun discover(udp: UdpSocket, attempts: Int = 3): List<String> {
        val req = mSearchRequest()
        repeat(attempts) { udp.send(req, SSDP_HOST, SSDP_PORT) }
        val locations = LinkedHashSet<String>()
        // best-effort: drain whatever has arrived by the time the caller stops us
        repeat(attempts * 4) {
            val pkt = runCatching { udp.receive() }.getOrNull() ?: return locations.toList()
            parseLocation(pkt.data.decodeToString())?.let { locations.add(it) }
        }
        return locations.toList()
    }

    /** Pull the `LOCATION:` header out of an SSDP response. */
    fun parseLocation(response: String): String? {
        for (line in response.split("\r\n")) {
            val idx = line.indexOf(':')
            if (idx > 0 && line.substring(0, idx).trim().equals("location", ignoreCase = true)) {
                return line.substring(idx + 1).trim()
            }
        }
        return null
    }

    // --- device-description XML parsing ------------------------------------------

    /**
     * The control endpoint extracted from an IGD device description: the service type
     * to use in the SOAP namespace / SOAPAction, the (possibly relative) control URL,
     * and any `<URLBase>` the device advertised.
     */
    data class ControlInfo(
        val serviceType: String,
        val controlUrl: String,
        val urlBase: String? = null,
        val modelName: String? = null,
    )

    /**
     * Scan an IGD device-description XML document for the first WAN(IP|PPP)Connection
     * service and its `<controlURL>`, plus any `<URLBase>`. Ports `find_control_url`
     * (upnp.cpp): we walk `<service>` blocks, accept the first whose `<serviceType>`
     * is one of [ACCEPTED_SERVICE_TYPES], and take the `<controlURL>` inside that same
     * service block. Returns `null` when no usable service is present (libtorrent then
     * marks the device `disabled`).
     *
     * The parse is intentionally lenient about namespaces and whitespace, matching
     * libtorrent's tag-stack scanner: tag names are compared case-insensitively after
     * stripping any XML namespace prefix.
     */
    fun findControlUrl(xml: String): ControlInfo? {
        val tags = tokenize(xml)
        var serviceType: String? = null
        var controlUrl: String? = null
        var urlBase: String? = null
        var modelName: String? = null
        var inService = false
        // the local tag stack; we only need the immediate parent and current element
        val stack = ArrayDeque<String>()
        var i = 0
        while (i < tags.size) {
            val t = tags[i]
            when (t) {
                is Token.Start -> {
                    stack.addLast(t.name)
                    if (t.name == "service") inService = true
                }
                is Token.End -> {
                    if (t.name == "service") inService = false
                    if (stack.isNotEmpty()) stack.removeLast()
                }
                is Token.Text -> {
                    val parent = stack.lastOrNull()
                    val grandparent = if (stack.size >= 2) stack[stack.size - 2] else null
                    when {
                        // <service><serviceType>…</serviceType></service>
                        serviceType == null && parent == "servicetype" && grandparent == "service" -> {
                            val v = t.value.trim()
                            if (ACCEPTED_SERVICE_TYPES.any { it.equals(v, ignoreCase = true) }) {
                                serviceType = v
                            }
                        }
                        // <service>…<controlURL>…</controlURL></service> — only once we
                        // are inside the accepted service block, mirroring `in_service`.
                        controlUrl == null && inService && serviceType != null &&
                            parent == "controlurl" && grandparent == "service" -> {
                            val v = t.value.trim()
                            if (v.isNotEmpty()) controlUrl = v
                        }
                        modelName == null && parent == "modelname" && grandparent == "device" -> {
                            modelName = t.value.trim()
                        }
                        parent == "urlbase" -> {
                            urlBase = t.value.trim()
                        }
                    }
                }
            }
            i++
        }
        val st = serviceType ?: return null
        val cu = controlUrl ?: return null
        return ControlInfo(st, cu, urlBase?.ifEmpty { null }, modelName?.ifEmpty { null })
    }

    private sealed interface Token {
        data class Start(val name: String) : Token
        data class End(val name: String) : Token
        data class Text(val value: String) : Token
    }

    /** Strip an XML namespace prefix (`u:foo` → `foo`) and lower-case the local name. */
    private fun localName(raw: String): String {
        val noNs = raw.substringAfterLast(':')
        return noNs.trim().lowercase()
    }

    /**
     * A minimal, allocation-light XML tokenizer producing start/end-tag and text
     * tokens. Self-closing tags emit a Start immediately followed by an End. Comments,
     * processing instructions and the XML declaration are skipped. Sufficient for the
     * fixed-shape IGD descriptions (the same scope as libtorrent's `xml_parse`).
     */
    private fun tokenize(xml: String): List<Token> {
        val out = ArrayList<Token>()
        var i = 0
        val n = xml.length
        while (i < n) {
            val lt = xml.indexOf('<', i)
            if (lt < 0) break
            // any character data between the previous '>' and this '<'
            if (lt > i) {
                val text = xml.substring(i, lt)
                if (text.isNotBlank()) out.add(Token.Text(decodeEntities(text)))
            }
            val gt = xml.indexOf('>', lt + 1)
            if (gt < 0) break
            var inner = xml.substring(lt + 1, gt).trim()
            i = gt + 1
            when {
                inner.startsWith("!--") -> { /* comment */ }
                inner.startsWith("!") -> { /* DOCTYPE / CDATA marker */ }
                inner.startsWith("?") -> { /* XML decl / PI */ }
                inner.startsWith("/") -> {
                    out.add(Token.End(localName(inner.substring(1))))
                }
                else -> {
                    val selfClosing = inner.endsWith("/")
                    if (selfClosing) inner = inner.dropLast(1).trim()
                    // tag name is the leading token up to the first whitespace
                    val nameRaw = inner.substringBefore(' ').substringBefore('\t')
                        .substringBefore('\n').substringBefore('\r')
                    val name = localName(nameRaw)
                    out.add(Token.Start(name))
                    if (selfClosing) out.add(Token.End(name))
                }
            }
        }
        return out
    }

    private fun decodeEntities(s: String): String {
        if ('&' !in s) return s
        return s.replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
    }

    private fun encodeEntities(s: String): String =
        s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

    // --- control-URL resolution --------------------------------------------------

    /**
     * Resolve the (possibly relative) `<controlURL>` against the device-description
     * [locationUrl] and any advertised `<URLBase>`, exactly as `on_upnp_xml` does:
     *
     *  - If a `<URLBase>` is present and the control URL is not already absolute
     *    (`http://…`), the two are concatenated, collapsing a double slash.
     *  - If the control URL is host-absolute (`/path`), it is rebased onto the
     *    scheme+host+port of [locationUrl].
     *  - Otherwise the control URL is taken as-is (already absolute).
     */
    fun resolveControlUrl(locationUrl: String, info: ControlInfo): String {
        val control = info.controlUrl
        if (control.startsWith("http://", ignoreCase = true) ||
            control.startsWith("https://", ignoreCase = true)
        ) {
            return control
        }
        val base = info.urlBase
        if (!base.isNullOrEmpty()) {
            var b = base
            if (b.endsWith('/') && control.startsWith('/')) b = b.dropLast(1)
            // url_base may itself be relative-less; if it is absolute use it directly
            if (b.startsWith("http://", ignoreCase = true) ||
                b.startsWith("https://", ignoreCase = true)
            ) {
                return b + control
            }
        }
        // rebase a host-absolute (or bare) path onto the LOCATION's scheme/host/port
        val origin = originOf(locationUrl)
        return if (control.startsWith('/')) origin + control
        else "$origin/$control"
    }

    /** Extract `scheme://host[:port]` from an absolute URL, defaulting to http. */
    private fun originOf(url: String): String {
        val schemeIdx = url.indexOf("://")
        if (schemeIdx < 0) return url.substringBefore('/')
        val scheme = url.substring(0, schemeIdx)
        val rest = url.substring(schemeIdx + 3)
        val authority = rest.substringBefore('/')
        return "$scheme://$authority"
    }

    // --- SOAP bodies -------------------------------------------------------------

    /** The SOAP body for `AddPortMapping` (upnp.cpp `create_port_mapping`). */
    fun addPortMappingBody(
        externalPort: Int,
        internalPort: Int,
        internalClient: String,
        tcp: Boolean,
        leaseSeconds: Int,
        description: String,
        serviceType: String = WAN_IP_SERVICE,
    ): String {
        val proto = if (tcp) "TCP" else "UDP"
        return "<?xml version=\"1.0\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body>" +
            "<u:AddPortMapping xmlns:u=\"$serviceType\">" +
            "<NewRemoteHost></NewRemoteHost>" +
            "<NewExternalPort>$externalPort</NewExternalPort>" +
            "<NewProtocol>$proto</NewProtocol>" +
            "<NewInternalPort>$internalPort</NewInternalPort>" +
            "<NewInternalClient>$internalClient</NewInternalClient>" +
            "<NewEnabled>1</NewEnabled>" +
            "<NewPortMappingDescription>${encodeEntities(description)}</NewPortMappingDescription>" +
            "<NewLeaseDuration>$leaseSeconds</NewLeaseDuration>" +
            "</u:AddPortMapping>" +
            "</s:Body>" +
            "</s:Envelope>"
    }

    /** The SOAP body for `DeletePortMapping` (upnp.cpp `delete_port_mapping`). */
    fun deletePortMappingBody(
        externalPort: Int,
        tcp: Boolean,
        serviceType: String = WAN_IP_SERVICE,
    ): String {
        val proto = if (tcp) "TCP" else "UDP"
        return "<?xml version=\"1.0\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body>" +
            "<u:DeletePortMapping xmlns:u=\"$serviceType\">" +
            "<NewRemoteHost></NewRemoteHost>" +
            "<NewExternalPort>$externalPort</NewExternalPort>" +
            "<NewProtocol>$proto</NewProtocol>" +
            "</u:DeletePortMapping>" +
            "</s:Body>" +
            "</s:Envelope>"
    }

    // --- HTTP drivers ------------------------------------------------------------

    /**
     * GET the device-description XML from a `LOCATION` URL discovered via SSDP and parse
     * out the WAN(IP|PPP)Connection control endpoint. Returns a fully-resolved
     * [IgdControl] (absolute control URL + service type) or `null` if the device has no
     * usable IGD service. Ports the GET + `on_upnp_xml` step of `upnp.cpp`.
     */
    suspend fun describe(client: HttpClient, locationUrl: String): IgdControl? {
        val xml = runCatching { client.get(locationUrl).bodyAsText() }.getOrNull() ?: return null
        val info = findControlUrl(xml) ?: return null
        val controlUrl = resolveControlUrl(locationUrl, info)
        return IgdControl(controlUrl, info.serviceType, info.modelName)
    }

    /**
     * Run the full IGD flow against a single discovered LOCATION: GET + parse the device
     * description, returning a usable control handle. Convenience over [describe].
     */
    suspend fun openIgd(client: HttpClient, locationUrl: String): IgdControl? =
        describe(client, locationUrl)

    /**
     * Discover gateways via SSDP on [udp] and return the control handle of the first one
     * that exposes a WAN(IP|PPP)Connection service. The engine typically calls this once,
     * caches the [IgdControl], then drives [mapTcpAndUdp]/[removeMapping] against it.
     */
    suspend fun discoverIgd(client: HttpClient, udp: UdpSocket, attempts: Int = 3): IgdControl? {
        for (loc in discover(udp, attempts)) {
            describe(client, loc)?.let { return it }
        }
        return null
    }

    /** POST an `AddPortMapping` SOAP action to the gateway's control URL. Returns true on success. */
    suspend fun addPortMapping(
        client: HttpClient,
        controlUrl: String,
        externalPort: Int,
        internalPort: Int,
        internalClient: String,
        tcp: Boolean,
        leaseSeconds: Int = DEFAULT_LEASE_SECONDS,
        description: String = "KiteTorrent",
        serviceType: String = WAN_IP_SERVICE,
    ): Boolean {
        val body = addPortMappingBody(externalPort, internalPort, internalClient, tcp, leaseSeconds, description, serviceType)
        return runCatching {
            val resp = client.post(controlUrl) {
                header("Content-Type", "text/xml; charset=\"utf-8\"")
                header("SOAPAction", "\"$serviceType#AddPortMapping\"")
                setBody(body)
            }
            !resp.bodyAsText().contains("UPnPError", ignoreCase = true)
        }.getOrDefault(false)
    }

    /** POST a `DeletePortMapping` SOAP action to the gateway's control URL. Returns true on success. */
    suspend fun deletePortMapping(
        client: HttpClient,
        controlUrl: String,
        externalPort: Int,
        tcp: Boolean,
        serviceType: String = WAN_IP_SERVICE,
    ): Boolean {
        val body = deletePortMappingBody(externalPort, tcp, serviceType)
        return runCatching {
            val resp = client.post(controlUrl) {
                header("Content-Type", "text/xml; charset=\"utf-8\"")
                header("SOAPAction", "\"$serviceType#DeletePortMapping\"")
                setBody(body)
            }
            !resp.bodyAsText().contains("UPnPError", ignoreCase = true)
        }.getOrDefault(false)
    }

    /**
     * (Re)map both the TCP and UDP halves of a peer port on the gateway described by
     * [igd]. Idempotent — re-issuing the same AddPortMapping refreshes the lease (this is
     * exactly how the engine's lease-refresh timer keeps the mapping alive, mirroring
     * `upnp::update_map` re-adding before expiry).
     *
     * Returns an [UpnpMapping] handle recording which protocols succeeded; the engine
     * keeps it to drive [removeMapping] on shutdown and to schedule the next refresh
     * after [leaseSeconds].
     */
    suspend fun mapTcpAndUdp(
        client: HttpClient,
        igd: IgdControl,
        externalPort: Int,
        internalPort: Int,
        internalClient: String,
        leaseSeconds: Int = DEFAULT_LEASE_SECONDS,
        description: String = "KiteTorrent",
    ): UpnpMapping {
        val tcpOk = addPortMapping(
            client, igd.controlUrl, externalPort, internalPort, internalClient,
            tcp = true, leaseSeconds = leaseSeconds, description = description,
            serviceType = igd.serviceType,
        )
        val udpOk = addPortMapping(
            client, igd.controlUrl, externalPort, internalPort, internalClient,
            tcp = false, leaseSeconds = leaseSeconds, description = description,
            serviceType = igd.serviceType,
        )
        return UpnpMapping(
            igd = igd,
            externalPort = externalPort,
            internalPort = internalPort,
            internalClient = internalClient,
            leaseSeconds = leaseSeconds,
            tcpMapped = tcpOk,
            udpMapped = udpOk,
        )
    }

    /**
     * Remove a mapping previously created by [mapTcpAndUdp], deleting whichever of the
     * TCP/UDP halves were established. Returns true if every previously-mapped protocol
     * was successfully deleted.
     */
    suspend fun removeMapping(client: HttpClient, mapping: UpnpMapping): Boolean {
        var ok = true
        if (mapping.tcpMapped) {
            ok = deletePortMapping(
                client, mapping.igd.controlUrl, mapping.externalPort,
                tcp = true, serviceType = mapping.igd.serviceType,
            ) && ok
        }
        if (mapping.udpMapped) {
            ok = deletePortMapping(
                client, mapping.igd.controlUrl, mapping.externalPort,
                tcp = false, serviceType = mapping.igd.serviceType,
            ) && ok
        }
        return ok
    }
}

/**
 * A resolved IGD control endpoint: the absolute SOAP control URL and the service type
 * (`WANIPConnection`/`WANPPPConnection`) to use as the SOAP namespace and SOAPAction
 * prefix. The engine caches this after [Upnp.discoverIgd] / [Upnp.describe].
 */
data class IgdControl(
    val controlUrl: String,
    val serviceType: String,
    val modelName: String? = null,
)

/**
 * The handle returned by [Upnp.mapTcpAndUdp]: enough state for the engine to refresh
 * the lease (re-call `mapTcpAndUdp` before [leaseSeconds] elapses) and to tear the
 * mapping down ([Upnp.removeMapping]) on shutdown.
 */
data class UpnpMapping(
    val igd: IgdControl,
    val externalPort: Int,
    val internalPort: Int,
    val internalClient: String,
    val leaseSeconds: Int,
    val tcpMapped: Boolean,
    val udpMapped: Boolean,
) {
    /** True when at least one protocol half was successfully mapped. */
    val anyMapped: Boolean get() = tcpMapped || udpMapped
}
