package ir.jbdns

import ir.jbdns.core.HealthCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * تست تستِ سلامت داخلی (الگوی Intra) — با resolver های جعلی.
 */
class HealthCheckTest {

    private fun checkById(checks: List<HealthCheck.Check>, id: String): HealthCheck.Check =
        checks.first { it.id == id }

    @Test
    fun `clean resolver passes all key checks`() {
        val checks = HealthCheck.run(
            tunnelActive = true,
            protoLabel = "DoH",
            encrypted = true,
            privateDnsStrict = false,
            blockEnabled = true,
            blockContains = { true },
            resolve = { listOf("93.184.216.34") to 42L }
        )
        assertTrue(checkById(checks, "tunnel").ok)
        assertTrue(checkById(checks, "encrypted").ok)
        assertTrue(checkById(checks, "resolve").ok)
        assertTrue(checkById(checks, "censor").ok)
        assertTrue(checkById(checks, "block").ok)
        assertTrue(checkById(checks, "pdns").ok)
    }

    @Test
    fun `censorship ip in answer fails the censor check`() {
        val checks = HealthCheck.run(
            tunnelActive = true,
            protoLabel = "DoH",
            encrypted = true,
            privateDnsStrict = false,
            blockEnabled = true,
            blockContains = { true },
            resolve = { if (it == "twitter.com") listOf("10.10.34.36") to 10L else listOf("93.184.216.34") to 20L }
        )
        val censor = checkById(checks, "censor")
        assertFalse(censor.ok)
        assertTrue(censor.detail.contains("10.10.34.36"))
    }

    @Test
    fun `plain DNS protocol fails encryption check`() {
        val checks = HealthCheck.run(
            tunnelActive = true,
            protoLabel = "DNS",
            encrypted = false,
            privateDnsStrict = false,
            blockEnabled = true,
            blockContains = { true },
            resolve = { listOf("1.2.3.4") to 5L }
        )
        assertFalse(checkById(checks, "encrypted").ok)
    }

    @Test
    fun `resolver exception fails both resolve and censor checks`() {
        val checks = HealthCheck.run(
            tunnelActive = false,
            protoLabel = "DoT",
            encrypted = true,
            privateDnsStrict = true,
            blockEnabled = false,
            blockContains = { false },
            resolve = { throw IOException("timeout") }
        )
        assertFalse(checkById(checks, "tunnel").ok)
        assertFalse(checkById(checks, "resolve").ok)
        assertFalse(checkById(checks, "censor").ok)
        assertFalse(checkById(checks, "block").ok)
        assertFalse(checkById(checks, "pdns").ok)
        assertEquals("تعداد بررسی‌ها ثابت است", 6, checks.size)
    }
}
