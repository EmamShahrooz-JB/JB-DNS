package ir.jbdns

import ir.jbdns.net.RaceResolver
import ir.jbdns.net.UpstreamResolver
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/**
 * تست حالت مسابقه (Race): پرس‌وجوی هم‌زمان از همهٔ سرورهای زنجیره؛
 * نخستین پاسخ موفق برنده است (الگوی lb_strategy در dnscrypt-proxy).
 */
class RaceResolverTest {

    private class Fake(
        var ok: Boolean = true,
        val tag: Byte = 1,
        private val delayMs: Long = 0
    ) : UpstreamResolver {
        override val label = "fake"
        var calls = 0
        override fun resolve(query: ByteArray): ByteArray {
            calls++
            if (delayMs > 0) Thread.sleep(delayMs)
            if (!ok) throw IOException("down")
            return byteArrayOf(tag)
        }
        override fun close() {}
    }

    @Test
    fun `fastest successful resolver wins the race`() {
        val slow = Fake(ok = true, tag = 1, delayMs = 300)
        val fast = Fake(ok = true, tag = 2, delayMs = 30)
        var winner = -1
        val race = RaceResolver(listOf(slow, fast), onWinner = { winner = it })

        assertArrayEquals(byteArrayOf(2), race.resolve(byteArrayOf(0)))
        assertEquals("برندهٔ مسابقه باید عضو سریع باشد", 1, winner)
        assertEquals(1, slow.calls)
        assertEquals(1, fast.calls)
    }

    @Test
    fun `failed members do not lose the race for others`() {
        val dead = Fake(ok = false, tag = 1)
        val alive = Fake(ok = true, tag = 2, delayMs = 40)
        val race = RaceResolver(listOf(dead, alive))

        assertArrayEquals(byteArrayOf(2), race.resolve(byteArrayOf(0)))
        assertEquals(1, dead.calls)
        assertEquals(1, alive.calls)
    }

    @Test
    fun `all members dead throws chain error`() {
        val race = RaceResolver(listOf(Fake(ok = false), Fake(ok = false)))
        try {
            race.resolve(byteArrayOf(0))
            fail("باید IOException می‌داد")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("مسابقه"))
        }
    }

    @Test
    fun `single member passes through without race`() {
        val only = Fake(ok = true, tag = 7)
        val race = RaceResolver(listOf(only))
        assertArrayEquals(byteArrayOf(7), race.resolve(byteArrayOf(0)))
        assertEquals(1, only.calls)
    }
}
