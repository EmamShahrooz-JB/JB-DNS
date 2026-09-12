package ir.jbdns.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * ساخت و اعتبارسنجی گزارش «JB-DNS مشکلی داشت بهمون بگو!».
 *
 * این کلاس کاملاً خالص است (بدون اندروید) تا اعتبارسنجی و ساخت payload
 * با تست واحد بررسی شود؛ ارسال شبکه‌ای در MainActivity انجام می‌گیرد.
 *
 * سمت سرور: cloudflare-worker/worker.js (D1 — قواعد یکسان در دو طرف).
 */
object Feedback {

    /**
     * نشانی ورکر دریافت گزارش (Cloudflare Worker + D1).
     */
    const val ENDPOINT = "https://jbdns-feedback.emam-shahrooz.workers.dev/report"

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

    /** اطلاعات فنی همراه گزارش — فقط وقتی کاربر خواسته باشد. */
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
     * ساخت payload نهایی.
     * @param logs رشته‌های ازپیش‌قالب‌بندی‌شدهٔ لاگ (دامنه/وضعیت/زمان) — سقف [MAX_LOGS]
     */
    fun build(text: String, contact: String?, diag: Diag?, logs: List<String>): JSONObject {
        val o = JSONObject()
        o.put("app", "JB-DNS")
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
