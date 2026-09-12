package ir.jbdns

import ir.jbdns.core.Feedback
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * تست اعتبارسنجی و ساخت payload گزارش «مشکلی داشت بهمون بگو».
 * قواعد باید با cloudflare-worker/worker.js هم‌خوان باشد.
 */
class FeedbackTest {

    private fun diag() = Feedback.Diag(
        appVersion = "4.1",
        androidVersion = "Android 14 (API 34)",
        device = "Xiaomi Redmi Note 12",
        server = "Shecan",
        proto = "DoH",
        tunnelOn = true,
        race = false,
        blocklists = listOf("ads", "malware"),
        queries = 1234,
        blocked = 56,
        lastError = "timeout"
    )

    @Test
    fun `text validation boundaries`() {
        assertEquals("متن گزارش کوتاه است — کمی بیشتر توضیح بده", Feedback.validateText(null))
        assertEquals("متن گزارش کوتاه است — کمی بیشتر توضیح بده", Feedback.validateText("  ab  "))
        assertNull(Feedback.validateText("سایت باز نمی‌شود"))
        assertNull(Feedback.validateText("x".repeat(Feedback.MAX_TEXT)))
        assertEquals("متن گزارش بیش از حد بلند است", Feedback.validateText("x".repeat(Feedback.MAX_TEXT + 1)))
    }

    @Test
    fun `contact validation`() {
        assertNull(Feedback.validateContact(null))
        assertNull(Feedback.validateContact("  "))
        assertNull(Feedback.validateContact("me@example.com".repeat(10)))
        assertEquals("راه تماس بیش از حد بلند است", Feedback.validateContact("x".repeat(Feedback.MAX_CONTACT + 1)))
    }

    @Test
    fun `payload includes diagnostics only when provided`() {
        val withDiag = Feedback.build("مشکل نمونه", " @user ", diag(), emptyList())
        assertEquals("JB-DNS", withDiag.optString("app"))
        assertEquals("مشکل نمونه", withDiag.optString("text"))
        assertEquals("@user", withDiag.optString("contact"))   // trim می‌شود
        val d = withDiag.getJSONObject("diagnostics")
        assertEquals("4.1", d.optString("appVersion"))
        assertEquals(true, d.optBoolean("tunnelOn"))
        assertEquals(1234, d.optLong("queries"))
        assertEquals(2, d.getJSONArray("blocklists").length())
        assertFalse(withDiag.has("logs"))

        val withoutDiag = Feedback.build("مشکل نمونه", "", null, emptyList())
        assertFalse(withoutDiag.has("diagnostics"))
        assertFalse(withoutDiag.has("contact"))   // خالی → کلید حذف
        assertFalse(withoutDiag.has("logs"))
    }

    @Test
    fun `logs included and capped at 30`() {
        val logs = (1..40).map { "OK · domain$it.com · ${it}ms · 1.2.3.4" }
        val p = Feedback.build("مشکل نمونه", null, null, logs)
        assertEquals(Feedback.MAX_LOGS, p.getJSONArray("logs").length())
        assertEquals("OK · domain1.com · 1ms · 1.2.3.4", p.getJSONArray("logs").getString(0))
        // آیتم ۳۱ به بعد حذف شده است
        val last = p.getJSONArray("logs").getString(Feedback.MAX_LOGS - 1)
        assertTrue(last.contains("domain30"))
    }

    @Test
    fun `lastError capped at 300 chars`() {
        val d = diag().let { Feedback.Diag(it.appVersion, it.androidVersion, it.device, it.server, it.proto, it.tunnelOn, it.race, it.blocklists, it.queries, it.blocked, "E".repeat(500)) }
        val p = Feedback.build("t", null, d, emptyList())
        assertEquals(300, p.getJSONObject("diagnostics").optString("lastError").length)
    }

    @Test
    fun `payload serializes to valid json`() {
        val json = Feedback.build("گزارش تست", null, diag(), listOf("OK · a.com · 5ms")).toString()
        val back = JSONObject(json)   // باید بدون خطا parse شود (سمت ورکر همین کار را می‌کند)
        assertTrue(back.has("text"))
    }
}
