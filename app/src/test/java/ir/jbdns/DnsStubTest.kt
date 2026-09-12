package ir.jbdns

import ir.jbdns.core.BlockList
import ir.jbdns.core.Dns
import ir.jbdns.core.DnsStub
import ir.jbdns.core.LogEntry
import ir.jbdns.core.VpnStats
import ir.jbdns.net.UpstreamResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DnsStubTest {

    private class FakeUpstream(val answer: String = "93.184.216.34") : UpstreamResolver {
        var calls = 0
        override val label = "fake"
        override fun resolve(query: ByteArray): ByteArray {
            calls++
            return Dns.buildResponseWithA(query, query.size, listOf(answer), ttl = 300)
        }
        override fun close() {}
    }

    @Before
    fun resetStats() = VpnStats.reset()

    @Test
    fun `normal query is forwarded and logged`() {
        val upstream = FakeUpstream()
        val stub = DnsStub(upstream, blockList = BlockList(emptyList()))
        val q = Dns.buildQuery(1, "example.com", Dns.TYPE_A)

        val result = stub.handle(q, q.size)

        assertEquals(1, upstream.calls)
        assertEquals(false, result.blocked)
        val msg = Dns.parse(result.packet, result.length)
        assertEquals(listOf("93.184.216.34"), msg.addresses)
        assertEquals(LogEntry.Status.OK, result.entry!!.status)
        assertEquals(1, VpnStats.totalQueries)
    }

    @Test
    fun `blocked domain never reaches upstream and returns nxdomain`() {
        val upstream = FakeUpstream()
        val stub = DnsStub(upstream, blockList = BlockList(listOf("doubleclick.net")), blockEnabled = true)
        val q = Dns.buildQuery(2, "ads.doubleclick.net", Dns.TYPE_A)

        val result = stub.handle(q, q.size)

        assertEquals(0, upstream.calls)
        assertTrue(result.blocked)
        assertEquals(Dns.RCODE_NXDOMAIN, Dns.parse(result.packet, result.length).rcode)
        assertEquals(1, VpnStats.totalBlocked)
    }

    @Test
    fun `sinkhole mode answers with the configured address`() {
        val upstream = FakeUpstream()
        val stub = DnsStub(
            upstream,
            blockList = BlockList(listOf("doubleclick.net")),
            blockEnabled = true,
            sinkholeEnabled = true,
            sinkholeIp = "0.0.0.0"
        )
        val q = Dns.buildQuery(3, "doubleclick.net", Dns.TYPE_A)

        val result = stub.handle(q, q.size)

        assertEquals(Dns.RCODE_NOERROR, Dns.parse(result.packet, result.length).rcode)
        assertEquals(listOf("0.0.0.0"), Dns.parse(result.packet, result.length).addresses)
    }

    @Test
    fun `second identical query is served from cache`() {
        val upstream = FakeUpstream()
        val stub = DnsStub(upstream, blockList = BlockList(emptyList()))
        val q1 = Dns.buildQuery(10, "cached.example", Dns.TYPE_A)
        val q2 = Dns.buildQuery(11, "cached.example", Dns.TYPE_A)

        stub.handle(q1, q1.size)
        val second = stub.handle(q2, q2.size)

        assertEquals(1, upstream.calls)
        assertEquals(LogEntry.Status.CACHED, second.entry!!.status)
        // شناسهٔ پاسخ باید با شناسهٔ درخواست دوم یکی شود
        assertEquals(11, Dns.parse(second.packet, second.length).id)
        assertEquals(1, VpnStats.totalCached)
    }

    @Test
    fun `upstream failure becomes servfail and counts as error`() {
        val failing = object : UpstreamResolver {
            override val label = "fail"
            override fun resolve(query: ByteArray): ByteArray = throw java.io.IOException("no route")
            override fun close() {}
        }
        val stub = DnsStub(failing, blockList = BlockList(emptyList()))
        val q = Dns.buildQuery(4, "example.com", Dns.TYPE_A)

        val result = stub.handle(q, q.size)

        assertEquals(Dns.RCODE_SERVFAIL, Dns.parse(result.packet, result.length).rcode)
        assertEquals(LogEntry.Status.ERROR, result.entry!!.status)
        assertEquals(1, VpnStats.totalErrors)
        assertTrue(VpnStats.lastError.isNotBlank())
    }

    @Test
    fun `ipv6 blocking answers without touching upstream`() {
        val upstream = FakeUpstream()
        val stub = DnsStub(upstream, blockList = BlockList(emptyList()), blockIpv6 = true)
        val q = Dns.buildQuery(5, "example.com", Dns.TYPE_AAAA)

        val result = stub.handle(q, q.size)

        assertEquals(0, upstream.calls)
        assertTrue(result.blocked)
    }

    @Test
    fun `logging can be turned off without affecting resolution`() {
        val upstream = FakeUpstream()
        val stub = DnsStub(upstream, blockList = BlockList(emptyList()), logEnabled = false)
        val q = Dns.buildQuery(6, "example.com", Dns.TYPE_A)

        val result = stub.handle(q, q.size)

        assertEquals(1, upstream.calls)
        assertEquals(null, result.entry)
        assertEquals(0, VpnStats.snapshot().size)
    }

    @Test
    fun `garbage packet is answered with formerr instead of crashing`() {
        val stub = DnsStub(FakeUpstream(), blockList = BlockList(emptyList()))
        val result = stub.handle(ByteArray(3), 3)
        assertEquals(Dns.RCODE_FORMERR, Dns.parse(result.packet, result.length).rcode)
    }
}
