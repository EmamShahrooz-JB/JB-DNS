package ir.jbdns

import ir.jbdns.core.Dns
import ir.jbdns.core.IpUdp
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IpUdpTest {

    private fun udpChecksum(srcIp: String, dstIp: String, udp: ByteArray): Int {
        val src = srcIp.split('.').map { it.toInt() }
        val dst = dstIp.split('.').map { it.toInt() }
        val pseudo = ByteArray(12 + udp.size)
        for (i in 0..3) { pseudo[i] = src[i].toByte(); pseudo[4 + i] = dst[i].toByte() }
        pseudo[9] = 17
        IpUdp.putU16(pseudo, 10, udp.size)
        System.arraycopy(udp, 0, pseudo, 12, udp.size)
        return IpUdp.checksum(pseudo, 0, pseudo.size)
    }

    /** بسته‌ای دقیقاً مثل چیزی که کرنل از fd تونل تحویل می‌دهد. */
    private fun tunnelPacket(domain: String, srcIp: String = "10.210.0.1", srcPort: Int = 41234): ByteArray {
        val dns = Dns.buildQuery(0xBEEF, domain, Dns.TYPE_A)
        val udp = ByteArray(IpUdp.UDP_HEADER + dns.size)
        IpUdp.putU16(udp, 0, srcPort)
        IpUdp.putU16(udp, 2, 53)
        IpUdp.putU16(udp, 4, udp.size)
        IpUdp.putU16(udp, 6, udpChecksum(srcIp, "10.210.0.2", udp))
        System.arraycopy(dns, 0, udp, IpUdp.UDP_HEADER, dns.size)

        val ip = ByteArray(IpUdp.IP_HEADER_MIN + udp.size)
        ip[0] = 0x45
        IpUdp.putU16(ip, 2, ip.size)
        IpUdp.putU16(ip, 4, 0x1234)
        ip[8] = 64
        ip[9] = 17
        IpUdp.putIpv4(ip, 12, srcIp)
        IpUdp.putIpv4(ip, 16, "10.210.0.2")
        IpUdp.putU16(ip, 10, IpUdp.checksum(ip, 0, IpUdp.IP_HEADER_MIN))
        System.arraycopy(udp, 0, ip, IpUdp.IP_HEADER_MIN, udp.size)
        return ip
    }

    @Test
    fun `tunnel packet is parsed into its dns payload`() {
        val packet = tunnelPacket("example.com")
        val udp = IpUdp.parseIpv4Udp(packet, packet.size)
        assertNotNull(udp)
        udp!!
        assertEquals("10.210.0.1", udp.srcIp)
        assertEquals("10.210.0.2", udp.dstIp)
        assertEquals(41234, udp.srcPort)
        assertEquals(53, udp.dstPort)

        val msg = Dns.parse(udp.payload, udp.payloadLength)
        assertEquals(0xBEEF, msg.id)
        assertEquals("example.com", msg.question!!.name)
        assertEquals(Dns.TYPE_A, msg.question!!.type)
    }

    @Test
    fun `header checksum of a generated packet verifies to zero`() {
        val packet = tunnelPacket("example.com")
        // بازخوانی جمع با سرآیند معتبر باید صفر شود
        assertEquals(0, IpUdp.checksum(packet, 0, IpUdp.IP_HEADER_MIN))
    }

    @Test
    fun `reply swaps addresses and ports and carries the dns response`() {
        val request = tunnelPacket("one.one.one.one")
        val udp = IpUdp.parseIpv4Udp(request, request.size)!!
        val dnsResponse = Dns.buildResponseWithA(udp.payload, udp.payloadLength, listOf("1.1.1.1"))

        val reply = IpUdp.buildIpv4UdpReply(request, request.size, dnsResponse)
        assertNotNull(reply)
        reply!!

        // سرآیند IP معتبر است
        assertEquals(0, IpUdp.checksum(reply, 0, IpUdp.IP_HEADER_MIN))
        assertEquals(0x45, reply[0].toInt() and 0xFF)
        assertEquals(64, reply[8].toInt() and 0xFF)          // TTL
        assertEquals(17, reply[9].toInt() and 0xFF)          // UDP

        val parsed = IpUdp.parseIpv4Udp(reply, reply.size)
        assertNotNull(parsed)
        parsed!!
        assertEquals("10.210.0.2", parsed.srcIp)             // جابه‌جا شده
        assertEquals("10.210.0.1", parsed.dstIp)
        assertEquals(53, parsed.srcPort)
        assertEquals(41234, parsed.dstPort)

        val msg = Dns.parse(parsed.payload, parsed.payloadLength)
        assertEquals(0xBEEF, msg.id)                         // همان شناسهٔ درخواست
        assertEquals(Dns.RCODE_NOERROR, msg.rcode)
        assertEquals(listOf("1.1.1.1"), msg.addresses)
    }

    @Test
    fun `non udp and malformed packets are rejected`() {
        assertNull(IpUdp.parseIpv4Udp(ByteArray(10), 10))            // کوتاه
        val notUdp = tunnelPacket("example.com").also { it[9] = 6 }  // TCP
        assertNull(IpUdp.parseIpv4Udp(notUdp, notUdp.size))
        val ipv6 = ByteArray(60).also { it[0] = 0x60 }
        assertNull(IpUdp.parseIpv4Udp(ipv6, ipv6.size))
    }

    @Test
    fun `declared lengths longer than the buffer are rejected`() {
        val packet = tunnelPacket("example.com")
        IpUdp.putU16(packet, 2, packet.size + 100)                   // طول کل دروغین
        assertNull(IpUdp.parseIpv4Udp(packet, packet.size))
    }

    @Test
    fun `full loop keeps the dns payload byte identical`() {
        val request = tunnelPacket("github.com")
        val udp = IpUdp.parseIpv4Udp(request, request.size)!!
        val dnsResponse = Dns.buildResponseWithA(udp.payload, udp.payloadLength, listOf("140.82.121.4"))
        val reply = IpUdp.buildIpv4UdpReply(request, request.size, dnsResponse)!!
        val back = IpUdp.parseIpv4Udp(reply, reply.size)!!
        assertArrayEquals(dnsResponse, back.payload.copyOf(back.payloadLength))
        assertTrue(back.payloadLength == dnsResponse.size)
    }
}
