package ir.jbdns

import ir.jbdns.core.BlockList
import ir.jbdns.core.Dns
import ir.jbdns.core.DnsStub
import ir.jbdns.net.ChainResolver
import ir.jbdns.net.UpstreamResolver
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * تست زنجیرهٔ سرورهای پشتیبان و فهرست دامنه‌های مجاز (استثنا).
 */
class FailoverAndAllowTest {

    private class Fake(var ok: Boolean, val tag: Byte = 1) : UpstreamResolver {
        override val label = "fake"
        var calls = 0
        override fun resolve(query: ByteArray): ByteArray {
            calls++
            if (!ok) throw IOException("down")
            return byteArrayOf(tag)
        }
        override fun close() {}
    }

    @Test
    fun `chain falls over to backup and sticks with it`() {
        var now = 100_000L
        val primary = Fake(ok = false, tag = 1)
        val backup = Fake(ok = true, tag = 2)
        var switchedTo = -1
        val chain = ChainResolver(listOf(primary, backup), { switchedTo = it }, nowMs = { now })

        assertArrayEquals(byteArrayOf(2), chain.resolve(byteArrayOf(0)))
        now += 1_000   // داخل پنجرهٔ probe نیست → مستقیم از backup
        assertArrayEquals(byteArrayOf(2), chain.resolve(byteArrayOf(0)))
        assertEquals("primary فقط یک‌بار (در probe اول) آزموده شد", 1, primary.calls)
        assertEquals(2, backup.calls)
        assertEquals(1, switchedTo)
    }

    @Test
    fun `chain returns to earlier server after it recovers`() {
        var now = 100_000L
        val primary = Fake(ok = true, tag = 1)
        val backup = Fake(ok = true, tag = 2)
        val chain = ChainResolver(listOf(primary, backup), nowMs = { now })

        // primary سالم است و همان می‌ماند
        assertArrayEquals(byteArrayOf(1), chain.resolve(byteArrayOf(0)))
        now += 1_000
        primary.ok = false
        assertArrayEquals(byteArrayOf(2), chain.resolve(byteArrayOf(0)))
        // هنوز داخل پنجرهٔ ۳۰ ثانیه → با backup ادامه می‌دهد
        now += 1_000
        assertArrayEquals(byteArrayOf(2), chain.resolve(byteArrayOf(0)))
        // پس از پنجرهٔ probe، primary بازیابی‌شده دوباره برمی‌گردد
        now += 31_000
        primary.ok = true
        assertArrayEquals(byteArrayOf(1), chain.resolve(byteArrayOf(0)))
    }

    @Test
    fun `chain throws when everyone is down`() {
        val chain = ChainResolver(listOf(Fake(false), Fake(false)))
        val err = runCatching { chain.resolve(byteArrayOf(0)) }.exceptionOrNull()
        assertTrue(err is IOException)
    }

    @Test
    fun `allowed domain is never blocked even if in blocklist`() {
        val upstream = object : UpstreamResolver {
            override val label = "fake"
            var calls = 0
            override fun resolve(query: ByteArray): ByteArray {
                calls++
                return Dns.buildResponseWithA(query, query.size, listOf("93.184.216.34"), ttl = 300)
            }
            override fun close() {}
        }
        val stub = DnsStub(
            upstream,
            blockList = BlockList(listOf("ads.example.com", "tracker.example.com")),
            blockEnabled = true,
            allowList = setOf("ads.example.com")
        )

        // در فهرست مسدودسازی هست ولی استثنا شده → پاسخ واقعی
        val q1 = Dns.buildQuery(1, "ads.example.com", Dns.TYPE_A)
        val r1 = stub.handle(q1, q1.size)
        assertEquals(false, r1.blocked)
        assertEquals(1, upstream.calls)

        // مسدود عادی هنوز کار می‌کند
        val q2 = Dns.buildQuery(2, "tracker.example.com", Dns.TYPE_A)
        val r2 = stub.handle(q2, q2.size)
        assertEquals(true, r2.blocked)
    }
}
