package ir.jbdns

import ir.jbdns.net.FallbackResolver
import ir.jbdns.net.UpstreamResolver
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * تست کلید قطع‌کن (circuit breaker) در FallbackResolver:
 * بعد از ۳ شکست پیاپی، سرور اصلی ۳۰ ثانیه دور زده می‌شود تا پرس‌وجوها
 * منتظر کامل timeout نمانند؛ سپس دوباره آزمایش می‌شود.
 */
class CircuitBreakerTest {

    private class Fake(var ok: Boolean) : UpstreamResolver {
        override val label = "fake"
        var calls = 0
        override fun resolve(query: ByteArray): ByteArray {
            calls++
            if (!ok) throw IOException("down")
            return byteArrayOf(7)
        }
        override fun close() {}
    }

    @Test
    fun `breaker opens after three failures and skips primary while open`() {
        var now = 0L
        val primary = Fake(ok = false)
        val fallback = Fake(ok = true)
        val r = FallbackResolver(primary, fallback, nowMs = { now })

        repeat(3) { assertArrayEquals(byteArrayOf(7), r.resolve(byteArrayOf(0))) }
        assertEquals(3, primary.calls)

        // کلید باز شده: primary دیگر صدا زده نمی‌شود ولی fallback پاسخ می‌دهد
        assertArrayEquals(byteArrayOf(7), r.resolve(byteArrayOf(0)))
        assertEquals("primary نباید دوباره صدا زده شود", 3, primary.calls)
        assertEquals(4, fallback.calls)

        // پس از پایان مهلت ۳۰ ثانیه، دوباره primary آزمایش می‌شود
        now = 31_000
        primary.ok = true
        assertArrayEquals(byteArrayOf(7), r.resolve(byteArrayOf(0)))
        assertEquals(4, primary.calls)
    }

    @Test
    fun `breaker without fallback fails fast when open`() {
        var now = 0L
        val primary = Fake(ok = false)
        val r = FallbackResolver(primary, null, nowMs = { now })

        repeat(3) { runCatching { r.resolve(byteArrayOf(0)) } }
        // سه شکست اول primary را صدا زد
        assertEquals(3, primary.calls)

        now = 1_000
        val err = runCatching { r.resolve(byteArrayOf(0)) }.exceptionOrNull()
        assertTrue("باید IOException فوری باشد", err is IOException)
        assertEquals("در حالت باز primary صدا زده نمی‌شود", 3, primary.calls)
    }

    @Test
    fun `success resets the failure counter`() {
        var now = 0L
        val primary = Fake(ok = true)
        val fallback = Fake(ok = true)
        val r = FallbackResolver(primary, fallback, nowMs = { now })

        // دو شکست، یک موفقیت، دو شکست → هنوز نبازیر باز شود (شمارنده صفر شده)
        repeat(2) { runCatching { primary.ok = false; r.resolve(byteArrayOf(0)) } }
        primary.ok = true
        r.resolve(byteArrayOf(0))
        repeat(2) { runCatching { primary.ok = false; r.resolve(byteArrayOf(0)) } }
        assertEquals(5, primary.calls)
        assertEquals("fallback فقط برای شکست‌ها", 4, fallback.calls)
    }
}
