package ir.jbdns

import ir.jbdns.core.BackupData
import ir.jbdns.core.SettingsBackup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * تست خروجی/ورودی پشتیبان تنظیمات — رفت‌وبرگشت کامل و رد فایل‌های خراب.
 */
class SettingsBackupTest {

    private val sample = BackupData(
        serverId = "shecan",
        theme = "dark",
        toggles = mapOf("block" to true, "fallback" to false, "sound" to true),
        protos = mapOf("shecan" to "DOH", "cloudflare" to "DOT"),
        customServers = listOf("c123|My DNS|CUSTOM||||1.2.3.4|note"),
        backups = listOf("cloudflare", "quad9"),
        allowed = listOf("example.com"),
        blockedExtra = listOf("ads.example.com"),
        rewrites = mapOf("dev.local" to "192.168.1.10"),
        catalog = listOf("ads", "malware"),
        raceMode = true,
        splitTunneling = false,
        tunneledApps = listOf("com.tencent.ig"),
        blocklistUrl = "https://example.com/hosts"
    )

    @Test
    fun `round trip preserves every field`() {
        val json = SettingsBackup.export(sample)
        val back = SettingsBackup.import(json)
        assertEquals("shecan", back.serverId)
        assertEquals("dark", back.theme)
        assertEquals(sample.toggles, back.toggles)
        assertEquals(sample.protos, back.protos)
        assertEquals(sample.customServers, back.customServers)
        assertEquals(sample.backups, back.backups)
        assertEquals(sample.allowed, back.allowed)
        assertEquals(sample.blockedExtra, back.blockedExtra)
        assertEquals(sample.rewrites, back.rewrites)
        assertEquals(sample.catalog, back.catalog)
        assertEquals(true, back.raceMode)
        assertEquals(false, back.splitTunneling)
        assertEquals(sample.tunneledApps, back.tunneledApps)
        assertEquals("https://example.com/hosts", back.blocklistUrl)
    }

    @Test
    fun `empty export imports as all defaults`() {
        val back = SettingsBackup.import(SettingsBackup.export(BackupData()))
        assertNull(back.serverId)
        assertNull(back.theme)
        assertTrue(back.toggles.isEmpty())
        assertTrue(back.protos.isEmpty())
        assertNull(back.raceMode)
        assertNull(back.splitTunneling)
    }

    @Test
    fun `unknown toggle keys and bad values are ignored`() {
        val json = """{"app":"JB-DNS","v":4,"toggles":{"hackme":"yes","block":"true","x":"1"}}"""
        val back = SettingsBackup.import(json)
        assertEquals(mapOf("block" to true), back.toggles)
    }

    @Test
    fun `non-jbdns and future-version files are rejected`() {
        try {
            SettingsBackup.import("""{"app":"OtherApp","v":4}""")
            fail("باید رد می‌شد")
        } catch (_: IllegalArgumentException) {
        }
        try {
            SettingsBackup.import("""{"app":"JB-DNS","v":99}""")
            fail("نسخهٔ آینده باید رد شود")
        } catch (_: IllegalArgumentException) {
        }
        try {
            SettingsBackup.import("not json at all {{{")
            fail("JSON خراب باید رد شود")
        } catch (_: Exception) {
        }
    }
}
