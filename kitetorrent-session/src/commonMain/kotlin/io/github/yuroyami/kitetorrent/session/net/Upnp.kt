package io.github.yuroyami.kitetorrent.session.net

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText

/**
 * UPnP IGD port mapping — port of the SSDP-discovery + SOAP-control half of
 * libtorrent's `upnp` (upnp.cpp). Opens an inbound port on a NAT gateway so peers can
 * reach us. SSDP runs over UDP multicast; the control step is plain SOAP-over-HTTP.
 *
 * XML is built/scanned as strings (no XML library — the documents are tiny and fixed),
 * exactly as libtorrent does with its hand-rolled `xml_parse`.
 */
object Upnp {

    const val SSDP_HOST = "239.255.255.250"
    const val SSDP_PORT = 1900
    const val IGD_DEVICE = "urn:schemas-upnp-org:device:InternetGatewayDevice:1"
    const val WAN_IP_SERVICE = "urn:schemas-upnp-org:service:WANIPConnection:1"

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

    /** The SOAP body for `AddPortMapping` (upnp.cpp `soap_action`). */
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
            "<NewPortMappingDescription>$description</NewPortMappingDescription>" +
            "<NewLeaseDuration>$leaseSeconds</NewLeaseDuration>" +
            "</u:AddPortMapping>" +
            "</s:Body>" +
            "</s:Envelope>"
    }

    /** POST an `AddPortMapping` SOAP action to the gateway's control URL. Returns true on HTTP 200. */
    suspend fun addPortMapping(
        client: HttpClient,
        controlUrl: String,
        externalPort: Int,
        internalPort: Int,
        internalClient: String,
        tcp: Boolean,
        leaseSeconds: Int = 3600,
        description: String = "KiteTorrent",
        serviceType: String = WAN_IP_SERVICE,
    ): Boolean {
        val body = addPortMappingBody(externalPort, internalPort, internalClient, tcp, leaseSeconds, description, serviceType)
        val resp = client.post(controlUrl) {
            header("Content-Type", "text/xml; charset=\"utf-8\"")
            header("SOAPAction", "\"$serviceType#AddPortMapping\"")
            setBody(body)
        }
        val text = resp.bodyAsText()
        return !text.contains("UPnPError", ignoreCase = true)
    }
}
