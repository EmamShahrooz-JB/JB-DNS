package ir.jbdns.core

import android.content.Context

/**
 * کاتالوگ فهرست‌های مسدودسازی آمادهٔ داخل برنامه (الگوی RethinkDNS).
 *
 * فایل‌ها در `assets/blocklists/` قرار دارند و یک‌بار در هر فرایند خوانده
 * و کش می‌شوند. منابع:
 *  - تبلیغات: Disconnect (simple_ad) + شبکه‌های تبلیغاتی ایرانی
 *  - ردیاب‌ها: Disconnect (simple_tracking) + تحلیلگرهای شناخته‌شده
 *  - بدافزار: URLhaus (abuse.ch) — دامنه‌های فعال مخرب
 *  - خانواده: سایت‌های بزرگسال پرکاربرد + TLDهای xxx/porn/adult
 */
object BlocklistCatalog {

    class Entry(val id: String, val title: String, val desc: String, val file: String)

    val LISTS: List<Entry> = listOf(
        Entry("ads", "تبلیغات", "شبکه‌های تبلیغاتی جهانی و ایرانی (Disconnect)", "blocklists/ads.txt"),
        Entry("trackers", "ردیاب‌ها", "تحلیلگرها و فینگرپرینت‌ها (Analytics/SDK)", "blocklists/trackers.txt"),
        Entry("malware", "بدافزار", "دامنه‌های فعال مخرب (URLhaus)", "blocklists/malware.txt"),
        Entry("family", "خانواده", "محتوای بزرگسال و TLDهای ویژه", "blocklists/family.txt")
    )

    fun byId(id: String): Entry? = LISTS.firstOrNull { it.id == id }

    /** کش درون‌فرایندی — فایل‌های asset در زمان اجرا تغییر نمی‌کنند. */
    @Volatile private var cache: MutableMap<String, List<String>> = HashMap()

    /** دامنه‌های یک فهرست؛ در خطا فهرست تهی برمی‌گردد (اپ هرگز به‌خاطر فهرست‌ها نمی‌میرد). */
    fun rules(context: Context, id: String): List<String> {
        val entry = byId(id) ?: return emptyList()
        cache[id]?.let { return it }
        val rules = runCatching {
            context.assets.open(entry.file).bufferedReader().useLines { lines ->
                lines.map { it.substringBefore('#').trim() }
                    .filter { it.isNotEmpty() && it.contains('.') }
                    .toList()
            }
        }.getOrDefault(emptyList())
        synchronized(this) { cache[id] = rules }
        return rules
    }

    fun count(context: Context, id: String): Int = rules(context, id).size

    /** همهٔ دامنه‌های فهرست‌های فعال — BlockList خودش هش‌گذاری و ادغام می‌کند. */
    fun enabledRules(context: Context, enabled: Set<String>): List<String> {
        val ids = enabled.filter { byId(it) != null }
        val out = ArrayList<String>()
        for (id in ids) out += rules(context, id)
        return out
    }
}
