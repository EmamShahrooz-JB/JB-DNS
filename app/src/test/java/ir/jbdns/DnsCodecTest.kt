package ir.jbdns

import ir.jbdns.core.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsCodecTest {

    @Test
    fun `query roundtrip preserves id name and type`() {
        val q = Dns.buildQuery(0x4321, "www.example.com", Dns.TYPE_A)
        val msg = Dns.parse(q)
        assertEquals(0x4321, msg.id)
        assertEquals(false, msg.isResponse)
        assertNotNull(msg.question)
        assertEquals("www.example.com", msg.question!!.name)
        assertEquals(Dns.TYPE_A, msg.question!!.type)
        assertEquals(1, msg.question!!.qclass)
    }

    @Test
    fun `aaaa and unusual types survive roundtrip`() {
        for (type in intArrayOf(Dns.TYPE_AAAA, Dns.TYPE_MX, Dns.TYPE_TXT, Dns.TYPE_HTTPS)) {
            val q = Dns.buildQuery(7, "one.one.one.one", type)
            val msg = Dns.parse(q)
            assertEquals("type $type", type, msg.question!!.type)
        }
    }

    @Test
    fun `single label and root names are handled`() {
        assertEquals("localhost", Dns.parse(Dns.buildQuery(1, "localhost", Dns.TYPE_A)).question!!.name)
        assertEquals("", Dns.parse(Dns.buildQuery(1, "", Dns.TYPE_A)).question!!.name)
    }

    @Test
    fun `response with compressed answer name is parsed`() {
        val q = Dns.buildQuery(0x1234, "example.com", Dns.TYPE_A)
        val response = Dns.buildResponseWithA(q, q.size, listOf("93.184.216.34", "93.184.216.35"), ttl = 300)
        val msg = Dns.parse(response)
        assertTrue(msg.isResponse)
        assertEquals(0x1234, msg.id)
        assertEquals(Dns.RCODE_NOERROR, msg.rcode)
        assertEquals(listOf("93.184.216.34", "93.184.216.35"), msg.addresses)
        assertEquals(300L, msg.answers.first().ttl)
    }

    @Test
    fun `error response keeps question and sets rcode`() {
        val q = Dns.buildQuery(0x99, "blocked.example", Dns.TYPE_A)
        val nxd = Dns.buildError(q, q.size, Dns.RCODE_NXDOMAIN)
        val msg = Dns.parse(nxd)
        assertTrue(msg.isResponse)
        assertEquals(Dns.RCODE_NXDOMAIN, msg.rcode)
        assertEquals("blocked.example", msg.question!!.name)
        assertEquals(0, msg.answers.size)
        // RD باید بازتاب داده شود
        assertEquals(0x0100, msg.flags and 0x0100)
    }

    @Test
    fun `sinkhole answer returns the configured address`() {
        val q = Dns.buildQuery(5, "ads.example.com", Dns.TYPE_A)
        val sink = Dns.buildSyntheticA(q, q.size, "0.0.0.0")
        val msg = Dns.parse(sink)
        assertEquals(Dns.RCODE_NOERROR, msg.rcode)
        assertEquals(listOf("0.0.0.0"), msg.addresses)
    }

    @Test
    fun `ipv4 text converts to bytes without lookup`() {
        val bytes = Dns.addressToBytes("185.51.200.2")
        assertEquals(listOf(185, 51, 200, 2), bytes.map { it.toInt() and 0xFF })
    }

    @Test
    fun `buildError tolerates a truncated packet`() {
        val minimal = Dns.buildError(ByteArray(3), 3, Dns.RCODE_FORMERR)
        assertEquals(Dns.HEADER_SIZE, minimal.size)
        assertEquals(Dns.RCODE_FORMERR, Dns.parse(minimal).rcode)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `too short packet throws`() {
        Dns.parse(ByteArray(4))
    }

    @Test
    fun `pointer loop is not followed forever`() {
        // هدر معتبر + نامی که به خودش اشاره می‌کند
        val pkt = ByteArray(16)
        Dns.putU16(pkt, 0, 1)      // id
        Dns.putU16(pkt, 2, 0x8180) // flags
        Dns.putU16(pkt, 4, 1)      // qdcount
        pkt[12] = 0xC0.toByte()    // اشاره‌گر
        pkt[13] = 0x0C             // به آفست ۱۲ (خودش)
        val name = Dns.readName(pkt, 12, pkt.size)
        // نامی خوانده نمی‌شود ولی پیمایش باید بعد از دو بایتِ اشاره‌گر متوقف شود
        assertEquals("", name.first)
        assertEquals(14, name.second)
    }

    @Test
    fun `type and rcode labels are readable`() {
        assertEquals("AAAA", Dns.typeLabel(Dns.TYPE_AAAA))
        assertEquals("NXDOMAIN", Dns.rcodeLabel(Dns.RCODE_NXDOMAIN))
        assertEquals("TYPE9999", Dns.typeLabel(9999))
    }
}
