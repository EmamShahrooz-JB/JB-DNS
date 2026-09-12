package ir.jbdns.core

/**
 * لایهٔ نازک IPv4/UDP برای تونل VPN.
 *
 * در تونلِ بدون مسیر catch-all، پرس‌وجوهای DNS به‌صورت بستهٔ خام IP از
 * فایل‌دیسکریپتر تونل خوانده می‌شوند؛ این کلاس آن‌ها را باز و بسته‌بندی می‌کند.
 * کاملاً خالص است تا روی JVM با بسته‌های واقعی آزموده شود.
 */
object IpUdp {

    const val ETH_P_IP = 0x0800
    const val PROTO_UDP = 17
    const val IP_HEADER_MIN = 20
    const val UDP_HEADER = 8

    class UdpPayload(
        val srcIp: String,
        val dstIp: String,
        val srcPort: Int,
        val dstPort: Int,
        val payload: ByteArray,
        val payloadLength: Int
    )

    /** بستهٔ خام IP را باز می‌کند؛ اگر UDP نبود یا خراب بود null برمی‌گرداند. */
    fun parseIpv4Udp(packet: ByteArray, len: Int): UdpPayload? {
        if (len < IP_HEADER_MIN) return null
        val version = (packet[0].toInt() and 0xF0) shr 4
        if (version != 4) return null
        val ihl = (packet[0].toInt() and 0x0F) * 4
        if (ihl < IP_HEADER_MIN || len < ihl) return null
        val totalLength = u16(packet, 2)
        if (totalLength < ihl + UDP_HEADER || totalLength > len) return null
        val protocol = packet[9].toInt() and 0xFF
        if (protocol != PROTO_UDP) return null

        val srcIp = ipv4(packet, 12)
        val dstIp = ipv4(packet, 16)
        val udpStart = ihl
        val srcPort = u16(packet, udpStart)
        val dstPort = u16(packet, udpStart + 2)
        val udpLength = u16(packet, udpStart + 4)
        if (udpLength < UDP_HEADER) return null
        val payloadLength = (udpLength - UDP_HEADER).coerceAtMost(totalLength - udpStart - UDP_HEADER)
        if (udpStart + UDP_HEADER + payloadLength > len) return null

        val payload = packet.copyOfRange(udpStart + UDP_HEADER, udpStart + UDP_HEADER + payloadLength)
        return UdpPayload(srcIp, dstIp, srcPort, dstPort, payload, payloadLength)
    }

    /**
     * پاسخ UDP/IPv4 را با آدرس‌ها و پورت‌های جابه‌جا شده می‌سازد.
     * [request] بستهٔ خام درخواست است.
     */
    fun buildIpv4UdpReply(request: ByteArray, len: Int, response: ByteArray): ByteArray? {
        val parsed = parseIpv4Udp(request, len) ?: return null
        return buildIpv4Udp(
            srcIp = parsed.dstIp,
            dstIp = parsed.srcIp,
            srcPort = parsed.dstPort,
            dstPort = parsed.srcPort,
            payload = response,
            payloadLength = response.size
        )
    }

    fun buildIpv4Udp(
        srcIp: String,
        dstIp: String,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray,
        payloadLength: Int,
        ttl: Int = 64
    ): ByteArray {
        val udpLength = UDP_HEADER + payloadLength
        val totalLength = IP_HEADER_MIN + udpLength
        val out = ByteArray(totalLength)

        // ---- سرآیند IP
        out[0] = 0x45                                  // نسخه ۴، IHL=5
        out[1] = 0                                     // DSCP/ECN
        putU16(out, 2, totalLength)
        putU16(out, 4, 0)                              // شناسه
        putU16(out, 6, 0x4000)                         // Don't Fragment
        out[8] = ttl.toByte()
        out[9] = PROTO_UDP.toByte()
        putU16(out, 10, 0)                             // checksum بعداً
        putIpv4(out, 12, srcIp)
        putIpv4(out, 16, dstIp)
        putU16(out, 10, checksum(out, 0, IP_HEADER_MIN))

        // ---- سرآیند UDP
        val u = IP_HEADER_MIN
        putU16(out, u, srcPort)
        putU16(out, u + 2, dstPort)
        putU16(out, u + 4, udpLength)
        putU16(out, u + 6, 0)                          // checksum اختیاری (۰ = محاسبه‌نشده)
        System.arraycopy(payload, 0, out, u + UDP_HEADER, payloadLength)
        return out
    }

    fun u16(buf: ByteArray, off: Int): Int =
        ((buf[off].toInt() and 0xFF) shl 8) or (buf[off + 1].toInt() and 0xFF)

    fun putU16(buf: ByteArray, off: Int, value: Int) {
        buf[off] = ((value shr 8) and 0xFF).toByte()
        buf[off + 1] = (value and 0xFF).toByte()
    }

    fun ipv4(buf: ByteArray, off: Int): String =
        "${buf[off].toInt() and 0xFF}.${buf[off + 1].toInt() and 0xFF}." +
            "${buf[off + 2].toInt() and 0xFF}.${buf[off + 3].toInt() and 0xFF}"

    fun putIpv4(buf: ByteArray, off: Int, ip: String) {
        val parts = ip.split('.')
        for (i in 0..3) buf[off + i] = (parts.getOrNull(i)?.toIntOrNull() ?: 0).toByte()
    }

    /** جمع ۱۶-بیتی مکمل-یک (RFC 1071). */
    fun checksum(buf: ByteArray, off: Int, length: Int): Int {
        var sum = 0
        var i = off
        val end = off + length
        while (i + 1 < end) {
            sum += ((buf[i].toInt() and 0xFF) shl 8) or (buf[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) sum += (buf[i].toInt() and 0xFF) shl 8
        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        return sum.inv() and 0xFFFF
    }
}
