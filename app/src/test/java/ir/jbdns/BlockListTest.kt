package ir.jbdns

import ir.jbdns.core.BlockList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockListTest {

    private val list = BlockList(
        listOf("doubleclick.net", "*exact.only", "#comment", "  Sub.Example.COM  ", "")
    )

    @Test
    fun `matches the domain itself`() {
        assertTrue(list.contains("doubleclick.net"))
        assertTrue(list.contains("DOUBLECLICK.NET"))
        assertTrue(list.contains("doubleclick.net."))
    }

    @Test
    fun `matches subdomains recursively`() {
        assertTrue(list.contains("pagead2.googlesyndication.doubleclick.net"))
        assertTrue(list.contains("a.b.c.doubleclick.net"))
    }

    @Test
    fun `does not match unrelated or lookalike domains`() {
        assertFalse(list.contains("notdoubleclick.net"))
        assertFalse(list.contains("doubleclick.net.evil.com"))
        assertFalse(list.contains("example.org"))
    }

    @Test
    fun `exact rules ignore subdomains`() {
        assertTrue(list.contains("exact.only"))
        assertFalse(list.contains("www.exact.only"))
    }

    @Test
    fun `rules are normalised`() {
        assertTrue(list.contains("sub.example.com"))
        assertTrue(list.contains("deep.sub.example.com"))
        assertFalse(list.contains("example.com"))
    }

    @Test
    fun `default rules contain well known trackers`() {
        val defaults = BlockList(BlockList.DEFAULT_RULES)
        assertTrue(defaults.contains("pagead2.googlesyndication.com"))
        assertTrue(defaults.contains("ssl.google-analytics.com") || defaults.contains("doubleclick.net"))
        assertTrue(defaults.size > 50)
    }

    @Test
    fun `empty and whitespace input is ignored`() {
        assertEquals(0, BlockList(emptyList()).size)
        assertEquals(0, BlockList(listOf("", "   ", "#only comment")).size)
        assertFalse(BlockList(emptyList()).contains("anything.com"))
    }
}
