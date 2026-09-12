package ir.jbdns

import ir.jbdns.core.Feedback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * تست‌های چت پشتیبانی (v4.2) — قواعد یکسان با cloudflare-worker/worker.js.
 */
class FeedbackTest {

    private fun diag() = Feedback.Diag(
        appVersion = "4.2", androidVersion = "Android 13 (API 33)", device = "Xiaomi Redmi Note 12",
        server = "Shecan", proto = "DoH", tunnelOn = true, race = false,
        blocklists = listOf("ads", "trackers"), queries = 245, blocked = 18, lastError = ""
    )

    @Test
    fun validateText_shortRejected() {
        assertNotNull(Feedback.validateText("سلا"))
    }

    @Test
    fun validateText_blankRejected() {
        assertNotNull(Feedback.validateText("   "))
    }

    @Test
    fun validateText_tooLongRejected() {
        assertNotNull(Feedback.validateText("x".repeat(4001)))
    }

    @Test
    fun validateText_validPasses() {
        assertNull(Feedback.validateText("این یک متن تستی معتبر است"))
    }

    @Test
    fun validateContact_tooLongRejected() {
        assertNotNull(Feedback.validateContact("x".repeat(201)))
    }

    @Test
    fun validateContact_emptyPasses() {
        assertNull(Feedback.validateContact(""))
    }

    @Test
    fun chatPayload_minimal() {
        val o = Feedback.buildChatSend("aa11223344556677", "  مشکل تست  ", "", null, emptyList())
        assertEquals("aa11223344556677", o.getString("chat"))
        assertEquals("مشکل تست", o.getString("text"))
        assertEquals("JB-DNS", o.getString("app"))
        assertFalse(o.has("contact"))
        assertFalse(o.has("diagnostics"))
        assertFalse(o.has("logs"))
    }

    @Test
    fun chatPayload_fullWithDiagAndLogs() {
        val logs = (1..40).map { "OK · domain$it.com · 12ms · 1.2.3.4" }
        val o = Feedback.buildChatSend("bb22334455667788", "متن پیام", " @emam ", diag(), logs)
        assertEquals("@emam", o.getString("contact"))
        val d = o.getJSONObject("diagnostics")
        assertEquals("4.2", d.getString("appVersion"))
        assertEquals(true, d.getBoolean("tunnelOn"))
        assertEquals(245L, d.getLong("queries"))
        // سقف لاگ‌ها ۳۰ است (سمت سرور هم همین قاعده)
        assertEquals(Feedback.MAX_LOGS, o.getJSONArray("logs").length())
        assertEquals("ads", d.getJSONArray("blocklists").getString(0))
    }

    @Test
    fun chatPayload_lastErrorBlankOmitted() {
        val o = Feedback.buildChatSend("cc11223344556677", "متن", null, diag(), emptyList())
        assertFalse(o.getJSONObject("diagnostics").has("lastError"))
    }
}
