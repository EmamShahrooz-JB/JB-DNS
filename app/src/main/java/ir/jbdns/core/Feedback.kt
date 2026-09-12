package ir.jbdns.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * چت پشتیبانی «گفتگو با پشتیبانی» (v4.2).
 *
 * ساخت و اعتبارسنجی پیام‌های چت تلگرام‌مانند. این کلاس کاملاً خالص است
 * (بدون اندروید) تا با تست واحد بررسی شود؛ ارسال شبکه‌ای در MainActivity
 * انجام می‌گیرد. قواعد اعتبارسنجی در دو طرف (اپ ↔ worker.js) یکسان‌اند.
 *
 * شناسهٔ گفتگو در UI ساخته و در localStorage نگه داشته می‌شود؛ همان
 * شناسه «کد پیگیری» کاربر است و کلید ماندگاری گفتگو در D1.
 */
object Feedback {

    /** ریشهٔ سرویس پشتیبانی (Cloudflare Worker + D1). */
    const val BASE = "https://jbdns-feedback.emam-shahrooz.workers.dev"
    const val CHAT_SEND = "$BASE/chat/send"
    const val CHAT_POLL = "$BASE/chat/poll"

    const val MIN_TEXT = 5
    const val MAX_TEXT = 4000
    const val MAX_CONTACT = 200
    const val MAX_LOGS = 30

    /** خطای اعتبارسنجی متن یا null اگر درست است. */
    fun validateText(text: String?): String? {
        val t = (text ?: "").trim()
        if (t.length < MIN_TEXT) return "متن گزارش کوتاه است — کمی بیشتر توضیح بده"
        if (t.length > MAX_TEXT) return "متن گزارش بیش از حد بلند است"
        return null
    }

    /** خطای اعتبارسنجی راه تماس یا null. */
    fun validateContact(contact: String?): String? {
        val c = (contact ?: "").trim()
        if (c.length > MAX_CONTACT) return "راه تماس بیش از حد بلند است"
        return null
    }

    /** اطلاعات فنی همراه پیام — فقط وقتی کاربر خواسته باشد. */
    class Diag(
        val appVersion: String,
        val androidVersion: String,
        val device: String,
        val server: String,
        val proto: String,
        val tunnelOn: Boolean,
        val race: Boolean,
        val blocklists: List<String>,
        val queries: Long,
        val blocked: Long,
        val lastError: String
    )

    /**
     * ساخت payload پیام چت برای POST /chat/send.
     *
     * @param chatId شناسهٔ گفتگو (۱۶ کاراکتر hex، ماندگار در localStorage)
     * @param logs رشته‌های ازپیش‌قالب‌بندی‌شدهٔ لاگ — سقف [MAX_LOGS]
     */
    fun buildChatSend(
        chatId: String,
        text: String,
        contact: String?,
        diag: Diag?,
        logs: List<String>
    ): JSONObject {
        val o = JSONObject()
        o.put("app", "JB-DNS")
        o.put("chat", chatId)
        o.put("text", text.trim())
        val c = (contact ?: "").trim()
        if (c.isNotEmpty()) o.put("contact", c)
        if (diag != null) o.put("diagnostics", JSONObject().apply {
            put("appVersion", diag.appVersion)
            put("android", diag.androidVersion)
            put("device", diag.device)
            put("server", diag.server)
            put("proto", diag.proto)
            put("tunnelOn", diag.tunnelOn)
            put("race", diag.race)
            put("blocklists", JSONArray(diag.blocklists))
            put("queries", diag.queries)
            put("blocked", diag.blocked)
            if (diag.lastError.isNotBlank()) put("lastError", diag.lastError.take(300))
        })
        if (logs.isNotEmpty()) o.put("logs", JSONArray(logs.take(MAX_LOGS)))
        return o
    }
}
