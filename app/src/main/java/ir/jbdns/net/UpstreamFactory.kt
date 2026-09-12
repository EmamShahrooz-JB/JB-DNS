package ir.jbdns.net

import ir.jbdns.data.Proto
import ir.jbdns.data.Prefs
import ir.jbdns.data.Server

/** سرور پیکربندی‌شده را به یک [UpstreamResolver] تبدیل می‌کند. */
object UpstreamFactory {

    /**
     * @param timeoutMs مهلت (میلی‌ثانیه) برای هر اتصال/پرس‌وجوی بالادست.
     * تهی یعنی مقادیر پیش‌فرض هر ریزالور. برای سنجش سرعت مقدار کوتاه (مثلاً ۳۰۰۰)
     * پاسخ می‌دهد تا سرورهای مرده نتیجه را عقب نیندازند.
     */
    fun create(server: Server, prefs: Prefs, timeoutMs: Int? = null): UpstreamResolver {
        val proto = prefs.effectiveProto(server)
        val verify = prefs.verifyCertificate
        // زمان‌های کوتاه‌تر (۵ ثانیه): سرورِ بلاک‌شده باید سریع شکست بخورد، نه اینکه
        // هر پرس‌وجو را تا آخر معطل کند (با کلید قطع‌کنِ FallbackResolver ترکیب می‌شود)
        val base: UpstreamResolver = when (proto) {
            Proto.DOH -> DohResolver(
                server.doh.orEmpty(),
                timeoutMs?.toLong() ?: 5_000L,
                timeoutMs?.toLong() ?: 5_000L
            )
            Proto.DOT -> DotResolver(server.dot.orEmpty(), verifyCertificate = verify, timeoutMs = timeoutMs ?: 5_000)
            Proto.DNSCRYPT -> DnscryptResolver(server.sdns.orEmpty(), timeoutMs = timeoutMs ?: 5_000)
            Proto.DNS -> PlainDnsResolver(server.dns.ifEmpty { listOf("1.1.1.1") }, timeoutMs = timeoutMs ?: 4_000)
        }
        // مسیر جایگزین (DNS سادهٔ همان سرور) فقط اگر کاربر خواسته باشد؛
        // کلید قطع‌کن همیشه فعال است تا سرورِ در دسترس‌نبودن پرس‌وجوها را معطل نکند
        val plain = if (prefs.fallbackToPlain && proto != Proto.DNS && server.dns.isNotEmpty()) {
            PlainDnsResolver(server.dns, timeoutMs = timeoutMs ?: 4_000)
        } else null
        return FallbackResolver(base, plain)
    }

    /** نام میزبان یک نشانی DoH. */
    fun hostOfUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return try {
            java.net.URI(url).host
        } catch (_: Exception) {
            null
        }
    }

    /** آی‌پی‌های یک نام میزبان (برای نمایش و عیب‌یابی). */
    fun resolveIps(host: String): List<String> = try {
        java.net.InetAddress.getAllByName(host).mapNotNull { addr ->
            val text = addr.hostAddress ?: return@mapNotNull null
            if (addr is java.net.Inet4Address) text else null
        }
    } catch (_: Exception) {
        emptyList()
    }
}
