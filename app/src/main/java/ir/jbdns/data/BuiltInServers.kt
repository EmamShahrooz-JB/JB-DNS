package ir.jbdns.data

import ir.jbdns.data.Proto.DNS
import ir.jbdns.data.Proto.DOH
import ir.jbdns.data.Proto.DOT

/**
 * سرورهای پیش‌فرض JB-DNS.
 * نشانی‌های DoH/DoT از مستندات رسمی هر سرویس گرفته شده‌اند.
 */
object BuiltInServers {

    val ALL: List<Server> = listOf(
        // ---------------------------------------------------------- ایرانی
        Server(
            id = "shecan", name = "شکن (Shecan)", group = ServerGroup.IRAN,
            doh = "https://free.shecan.ir/dns-query",
            dot = "free.shecan.ir",
            dns = listOf("178.22.122.100", "185.51.200.2"),
            note = "رفع تحریم سرویس‌های خارجی؛ بهترین گزینهٔ عمومی برای ایران"
        ),
        Server(
            id = "403", name = "۴۰۳ آنلاین", group = ServerGroup.IRAN,
            doh = "https://dns.403.online/dns-query",
            dot = "dns.403.online",
            dns = listOf("10.202.10.202", "10.202.10.102"),
            note = "سرویس رسمی، رایگان و بدون لاگ"
        ),
        Server(
            id = "radar", name = "رادار گیم", group = ServerGroup.IRAN,
            dns = listOf("10.202.10.10", "10.202.10.11"),
            note = "بهینه‌شده برای سرورهای بازی و کاهش پینگ"
        ),
        Server(
            id = "begzar", name = "بگذر (Begzar)", group = ServerGroup.IRAN,
            dns = listOf("185.55.226.26", "185.55.225.25"),
            note = "پوشش خوب دامنه‌های دانشگاهی و خدماتی"
        ),
        Server(
            id = "beshkan", name = "بشکن (Beshkan)", group = ServerGroup.IRAN,
            dot = "free.beshkanapp.ir",
            dns = listOf("181.41.194.177", "181.41.194.186"),
            note = "رفع تحریم با پشتیبانی DNS-over-TLS"
        ),
        Server(
            id = "electro", name = "الکترو (Electro)", group = ServerGroup.IRAN,
            dns = listOf("78.157.42.100", "78.157.42.101"),
            note = "مناسب گیمینگ و کنسول"
        ),
        Server(
            id = "pishgaman", name = "پیشگامان", group = ServerGroup.IRAN,
            dns = listOf("5.202.100.100", "5.202.100.101"),
            note = "سرور داخل کشور"
        ),
        Server(
            id = "shatel", name = "شاتل", group = ServerGroup.IRAN,
            dns = listOf("85.15.1.14", "85.15.1.15"),
            note = "فقط برای کاربران اینترنت شاتل"
        ),
        Server(
            id = "vanilla", name = "وانیلا (Vanilla)", group = ServerGroup.IRAN,
            dns = listOf("10.139.177.21", "10.139.177.22"),
            note = "سرعت بالا برای گیم"
        ),
        Server(
            id = "zeus", name = "زئوس (Zeus)", group = ServerGroup.IRAN,
            dns = listOf("37.32.5.60", "37.32.5.61"),
            note = "کاهش پینگ بازی"
        ),
        Server(
            id = "shelter", name = "شلتر (Shelter)", group = ServerGroup.IRAN,
            dns = listOf("94.103.125.157", "94.103.125.158"),
            note = "بازی‌های حساس به تحریم"
        ),
        Server(
            id = "lagzero", name = "لگ زیرو (Lag Zero)", group = ServerGroup.IRAN,
            dns = listOf("95.38.132.152", "95.38.132.153"),
            note = "حذف لگ و کاهش پینگ"
        ),
        Server(
            id = "lagslayer", name = "لگ اسلیر (Lag Slayer)", group = ServerGroup.IRAN,
            dns = listOf("5.160.132.222", "5.160.132.221"),
            note = "بهینه برای گیم"
        ),

        // --------------------------------------------------------- سراسری
        Server(
            id = "cloudflare", name = "Cloudflare", group = ServerGroup.GLOBAL,
            doh = "https://cloudflare-dns.com/dns-query",
            dot = "cloudflare-dns.com",
            dns = listOf("1.1.1.1", "1.0.0.1"),
            note = "سریع‌ترین پاسخ‌دهی جهانی، بدون ذخیرهٔ لاگ"
        ),
        Server(
            id = "google", name = "Google", group = ServerGroup.GLOBAL,
            doh = "https://dns.google/dns-query",
            dot = "dns.google",
            dns = listOf("8.8.8.8", "8.8.4.4"),
            note = "پایدار و سازگار با همه‌چیز"
        ),
        Server(
            id = "opendns", name = "OpenDNS", group = ServerGroup.GLOBAL,
            dns = listOf("208.67.222.222", "208.67.220.220"),
            note = "فیلتر محتوا و کنترل خانواده"
        ),
        Server(
            id = "yandex", name = "Yandex", group = ServerGroup.GLOBAL,
            dns = listOf("77.88.8.8", "77.88.8.1"),
            note = "گزینهٔ جایگزین با پاسخ‌دهی خوب در منطقه"
        ),

        // -------------------------------------------- حریم خصوصی و امنیت
        Server(
            id = "quad9", name = "Quad9", group = ServerGroup.PRIVACY,
            doh = "https://dns.quad9.net/dns-query",
            dot = "dns.quad9.net",
            dns = listOf("9.9.9.9", "149.112.112.112"),
            note = "مسدودسازی دامنه‌های بدافزاری، بدون لاگ آی‌پی"
        ),
        Server(
            id = "mullvad", name = "Mullvad", group = ServerGroup.PRIVACY,
            doh = "https://dns.mullvad.net/dns-query",
            dot = "dns.mullvad.net",
            dns = listOf("194.242.2.2"),
            note = "مسدودسازی تبلیغات و ردیاب‌ها در سطح DNS"
        ),
        Server(
            id = "controld", name = "Control D", group = ServerGroup.PRIVACY,
            doh = "https://freedns.controld.com/p0",
            dot = "p0.freedns.controld.com",
            dns = listOf("76.76.2.0", "76.76.10.0"),
            note = "پروفایل p0 بدون فیلتر"
        ),

        // ------------------------------------------------ مسدودساز تبلیغات
        Server(
            id = "adguard", name = "AdGuard DNS", group = ServerGroup.BLOCK,
            doh = "https://dns.adguard-dns.com/dns-query",
            dot = "dns.adguard-dns.com",
            dns = listOf("94.140.14.14", "94.140.15.15"),
            note = "حذف تبلیغات و ردیاب‌ها"
        ),
        Server(
            id = "adguard-family", name = "AdGuard خانواده", group = ServerGroup.BLOCK,
            doh = "https://family.adguard-dns.com/dns-query",
            dot = "family.adguard-dns.com",
            dns = listOf("94.140.14.15", "94.140.15.16"),
            note = "تبلیغات + محتوای بزرگسال + جست‌وجوی امن"
        ),
        Server(
            id = "adguard-unfiltered", name = "AdGuard بدون فیلتر", group = ServerGroup.BLOCK,
            doh = "https://unfiltered.adguard-dns.com/dns-query",
            dot = "unfiltered.adguard-dns.com",
            dns = listOf("94.140.14.140", "94.140.14.141"),
            note = "وقتی سایتی با فیلتر باز نمی‌شود"
        ),
        Server(
            id = "adguard-dnscrypt", name = "AdGuard DNSCrypt", group = ServerGroup.BLOCK,
            sdns = "sdns://AQMAAAAAAAAAETk0LjE0MC4xNC4xNDo1NDQzINErR_JS3PLCu_iZEIbq95zkSV2LFsigxDIuUso_OQhzIjIuZG5zY3J5cHQuZGVmYXVsdC5uczEuYWRndWFyZC5jb20",
            dns = listOf("94.140.14.14"),
            note = "رمزنگاری سرتاسری DNSCrypt — حذف تبلیغات و ردیاب"
        ),
        Server(
            id = "quad9-dnscrypt", name = "Quad9 DNSCrypt", group = ServerGroup.PRIVACY,
            sdns = "sdns://AQMAAAAAAAAADDkuOS45Ljk6ODQ0MyBnyEe4yHWM0SAkVUO-dWdG3zTfHYTAC4xHA2jfgh2GPhkyLmRuc2NyeXB0LWNlcnQucXVhZDkubmV0",
            dns = listOf("9.9.9.9"),
            note = "DNSCrypt + مسدودسازی بدافزار، بدون لاگ آی‌پی"
        ),
        Server(
            id = "cleanbrowsing", name = "CleanBrowsing", group = ServerGroup.BLOCK,
            doh = "https://doh.cleanbrowsing.org/doh/family-filter/",
            dot = "family-filter-dns.cleanbrowsing.org",
            dns = listOf("185.228.168.168", "185.228.169.168"),
            note = "فیلتر خانواده و بدافزار"
        )
    )

    val DEFAULT_ID = "shecan"

    fun byId(id: String?): Server =
        ALL.firstOrNull { it.id == id } ?: ALL.first { it.id == DEFAULT_ID }

    fun grouped(): List<Pair<ServerGroup, List<Server>>> =
        ServerGroup.values().mapNotNull { g ->
            val list = ALL.filter { it.group == g }
            if (list.isEmpty()) null else g to list
        }
}
