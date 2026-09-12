package ir.jbdns.core

/**
 * تست سلامت داخلی (الگوی Intra): چند بررسی ساده که به کاربر نشان می‌دهد
 * DNS او رمزنگاری شده، بدون دستکاری پاسخ می‌دهد و مسدودسازی کار می‌کند.
 *
 * کاملاً خالص (Pure) و مستقل از اندروید تا با resolver های جعلی قابل تست باشد.
 */
object HealthCheck {

    class Check(val id: String, val title: String, val ok: Boolean, val detail: String)

    /** بازهٔ IPهای «صفحهٔ فیلترینگ» که ISPهای ایرانی به‌جای پاسخ واقعی برمی‌گردانند. */
    fun isCensorshipIp(ip: String): Boolean = ip.startsWith("10.10.34.")

    /**
     * @param resolve نام دامنه را از سرور فعال می‌پرسد؛ خروجی: (آدرس‌ها، میلی‌ثانیه).
     *   در خطا استثنا پرتاب می‌کند.
     */
    fun run(
        tunnelActive: Boolean,
        protoLabel: String,
        encrypted: Boolean,
        privateDnsStrict: Boolean,
        blockEnabled: Boolean,
        blockContains: (String) -> Boolean,
        resolve: (String) -> Pair<List<String>, Long>
    ): List<Check> {
        val checks = ArrayList<Check>()

        checks += Check(
            "tunnel", "تونل DNS فعال است", tunnelActive,
            if (tunnelActive) "پرس‌وجوهای دستگاه از تونل عبور می‌کنند"
            else "ابتدا دکمهٔ اتصال را بزنید"
        )

        val encOk = encrypted && protoLabel != "DNS"
        checks += Check(
            "encrypted", "رمزنگاری سرتاسر", encOk,
            if (encOk) "پرس‌وجوها با $protoLabel رمزنگاری می‌شوند"
            else "پروتکل فعلی DNS ساده است؛ در تنظیمات سرور، DoH یا DoT یا DNSCrypt انتخاب کنید"
        )

        checks += runCatching { resolve("example.com") }.fold(
            { (addrs, ms) ->
                Check("resolve", "پاسخ‌گویی سرور DNS", true,
                    "پاسخ در ${ms}ms ← ${addrs.firstOrNull() ?: "—"}")
            },
            { e ->
                Check("resolve", "پاسخ‌گویی سرور DNS", false,
                    "سرور پاسخ نداد: ${e.message ?: "خطای نامشخص"}")
            }
        )

        checks += runCatching { resolve("twitter.com") }.fold(
            { (addrs, _) ->
                val bad = addrs.firstOrNull { isCensorshipIp(it) }
                if (bad != null) Check("censor", "عدم دستکاری پاسخ‌ها", false,
                    "سرور، IP صفحهٔ فیلترینگ برمی‌گرداند ($bad) — پاسخ‌ها سانسور می‌شوند؛ سرور دیگری انتخاب کنید")
                else Check("censor", "عدم دستکاری پاسخ‌ها", true,
                    "پاسخ دامنهٔ تست، تمیز و بدون IP فیلترینگ بود")
            },
            { e ->
                Check("censor", "عدم دستکاری پاسخ‌ها", false,
                    "دامنهٔ تست پاسخ نداد: ${e.message ?: "—"}")
            }
        )

        val blockOk = blockEnabled && blockContains("doubleclick.net")
        checks += Check(
            "block", "مسدودسازی تبلیغات کار می‌کند", blockOk,
            if (blockOk) "دامنهٔ تست تبلیغاتی (doubleclick.net) مسدود شد"
            else "فهرست مسدودسازی خاموش است یا دامنهٔ تست در آن نیست"
        )

        checks += Check(
            "pdns", "«Private DNS» اندروید در حالت عادی", !privateDnsStrict,
            if (privateDnsStrict)
                "حالت Strict باعث می‌شود پرس‌وجوهای سیستم تونل را دور بزنند (نشت DNS) — آن را روی Auto یا Off بگذارید"
            else "پرس‌وجوهای سیستم هم از تونل می‌گذرند"
        )

        return checks
    }
}
