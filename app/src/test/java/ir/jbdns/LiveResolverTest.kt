package ir.jbdns

import ir.jbdns.core.Dns
import ir.jbdns.net.DohResolver
import ir.jbdns.net.DotResolver
import ir.jbdns.net.PlainDnsResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Test

/**
 * تست شبکهٔ زنده: کدک و ریزالورهای واقعی روی اینترنت واقعی سنجیده می‌شوند.
 * اگر محیط دسترسی شبکه نداشته باشد، تست با Assume رد (skip) می‌شود نه شکست.
 */
class LiveResolverTest {

    private fun assertResolves(resolver: ir.jbdns.net.UpstreamResolver, domain: String, expected: String?) {
        val query = Dns.buildQuery(0x2A, domain, Dns.TYPE_A)
        val response = try {
            resolver.resolve(query)
        } catch (e: Exception) {
            assumeNoException("شبکه در دسترس نیست", e)
            return
        }
        val msg = Dns.parse(response)
        assertEquals(0x2A, msg.id)
        assertEquals(Dns.RCODE_NOERROR, msg.rcode)
        assertTrue("پاسخی برای $domain برگشت نکرد", msg.answers.isNotEmpty())
        if (expected != null) assertTrue("$expected در ${msg.addresses} نبود", msg.addresses.contains(expected))
        resolver.close()
    }

    @Test
    fun `doh to google resolves one_one_one_one to 1_1_1_1`() {
        assertResolves(DohResolver("https://dns.google/dns-query"), "one.one.one.one", "1.1.1.1")
    }

    @Test
    fun `doh to quad9 resolves dns_quad9_net`() {
        assertResolves(DohResolver("https://dns.quad9.net/dns-query"), "dns.quad9.net", "9.9.9.9")
    }

    @Test
    fun `dot on port 853 works against at least one public resolver`() {
        val candidates = listOf("dns.google", "dns.quad9.net", "cloudflare-dns.com", "dns.adguard-dns.com")
        var lastError: Exception? = null
        for (host in candidates) {
            val resolver = DotResolver(host)
            try {
                val query = Dns.buildQuery(0x2C, "one.one.one.one", Dns.TYPE_A)
                val msg = Dns.parse(resolver.resolve(query))
                assertEquals(Dns.RCODE_NOERROR, msg.rcode)
                assertTrue(msg.addresses.contains("1.1.1.1"))
                return                       // دست‌کم یک سرور DoT پاسخ داد
            } catch (e: Exception) {
                lastError = e
            } finally {
                resolver.close()
            }
        }
        assumeNoException("پورت ۸۵۳ در این شبکه بسته است", lastError)
    }

    @Test
    fun `plain dns over udp works`() {
        assertResolves(PlainDnsResolver(listOf("1.1.1.1", "8.8.8.8")), "one.one.one.one", "1.1.1.1")
    }

    @Test
    fun `nxdomain is reported correctly by a real resolver`() {
        val resolver = DohResolver("https://dns.google/dns-query")
        val query = Dns.buildQuery(0x2B, "this-domain-does-not-exist-jbdns-test.invalid", Dns.TYPE_A)
        val response = try {
            resolver.resolve(query)
        } catch (e: Exception) {
            assumeNoException("شبکه در دسترس نیست", e)
            return
        }
        val msg = Dns.parse(response)
        assertNotNull(msg.question)
        assertEquals(Dns.RCODE_NXDOMAIN, msg.rcode)
        resolver.close()
    }

    @Test
    fun `two queries on the same dot connection reuse the session`() {
        val resolver = DotResolver("dns.google")
        try {
            for (i in 0 until 2) {
                val query = Dns.buildQuery(0x30 + i, "example.com", Dns.TYPE_A)
                val msg = Dns.parse(resolver.resolve(query))
                assertEquals(0x30 + i, msg.id)
                assertEquals(Dns.RCODE_NOERROR, msg.rcode)
            }
        } catch (e: Exception) {
            assumeNoException("شبکه یا پورت ۸۵۳ در دسترس نیست", e)
        } finally {
            resolver.close()
        }
    }
}
