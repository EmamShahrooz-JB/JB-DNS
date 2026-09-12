package ir.jbdns

import ir.jbdns.core.BlockList
import ir.jbdns.core.Dns
import ir.jbdns.core.DnsStub
import ir.jbdns.net.UpstreamResolver
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * تست بازنویسی DNS (Cloaking) و معنای قواعد وایلدکارد در فهرست مسدودسازی.
 */
class RewriteAndWildcardTest {

    private class Fake : UpstreamResolver {
        override val label = "fake"
        var calls = 0
        override fun resolve(query: ByteArray): ByteArray {
            calls++
            return Dns.buildResponseWithA(query, query.size, listOf("93.184.216.34"))
        }
        override fun close() {}
    }

    private fun queryFor(domain: String, type: Int = Dns.TYPE_A): ByteArray {
        val q = Dns.buildQuery(0x4242, domain, type)
        return q
    }

    @Test
    fun `rewritten domain answers locally with the configured ip`() {
        val fake = Fake()
        val stub = DnsStub(
            upstream = fake,
            rewriteMap = mapOf("mydevice.local" to "192.168.1.50")
        )
        val res = stub.handle(queryFor("mydevice.local"), queryFor("mydevice.local").size)
        val msg = Dns.parse(res.packet)
        assertEquals(Dns.RCODE_NOERROR, msg.rcode)
        assertEquals(listOf("192.168.1.50"), msg.addresses)
        assertEquals("بازنویسی نباید به بالادست برود", 0, fake.calls)
        assertNotNull(res.entry)
        assertTrue(res.entry!!.answer.contains("192.168.1.50"))
    }

    @Test
    fun `non-rewritten domains still go upstream`() {
        val fake = Fake()
        val stub = DnsStub(
            upstream = fake,
            rewriteMap = mapOf("mydevice.local" to "192.168.1.50")
        )
        val q = queryFor("example.com")
        val res = stub.handle(q, q.size)
        assertEquals(1, fake.calls)
        assertEquals(listOf("93.184.216.34"), Dns.parse(res.packet).addresses)
    }

    @Test
    fun `suffix rule blocks the domain and all its subdomains`() {
        val bl = BlockList(listOf("example.com"))
        assertTrue(bl.contains("example.com"))
        assertTrue(bl.contains("a.example.com"))
        assertTrue(bl.contains("x.y.example.com"))
        assertTrue(!bl.contains("notexample.com"))
        assertTrue(!bl.contains("example.org"))
    }

    @Test
    fun `star rule matches only the exact domain`() {
        val bl = BlockList(listOf("*.strict.example"))
        assertTrue(bl.contains("strict.example"))
        assertTrue(!bl.contains("sub.strict.example"))
    }

    @Test
    fun `rewritten domain is served even when listed in the block list`() {
        val fake = Fake()
        val stub = DnsStub(
            upstream = fake,
            blockList = BlockList(listOf("locked.example")),
            blockEnabled = true,
            rewriteMap = mapOf("locked.example" to "10.0.0.9")
        )
        val q = queryFor("locked.example")
        val res = stub.handle(q, q.size)
        // قاعدهٔ صریح کاربر (بازنویسی) بر فهرست مسدودسازی اولویت دارد
        assertEquals(listOf("10.0.0.9"), Dns.parse(res.packet).addresses)
        assertEquals(false, res.blocked)
    }
}
