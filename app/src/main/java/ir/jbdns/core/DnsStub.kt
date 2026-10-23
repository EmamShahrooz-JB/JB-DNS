package ir.jbdns.core

import ir.jbdns.net.UpstreamResolver

/** نتیجهٔ پردازش یک پرس‌وجو. */
class StubResult(
    val packet: ByteArray,
    val length: Int,
    val entry: LogEntry?,
    val blocked: Boolean
)

/**
 * پاسخ‌دهندهٔ محلی DNS: بسته را می‌گیرد، فهرست مسدودسازی و حافظهٔ نهان را می‌سنجد،
 * در صورت نیاز به سرور بالادست می‌فرستد و آمار را ثبت می‌کند.
 * کاملاً مستقل از اندروید است تا قابل تست باشد.
 *
 * نکتهٔ همزمانی: handle عمداً synchronized نیست. سرور بالادست روی شبکه بلاک می‌شود؛
 * اگر کل handle قفل می‌شد (مثل نسخهٔ قبلی)، یک سرورِ کند همهٔ پرس‌وجوهای دستگاه را
 * پشت هم می‌صف کرد — برخلاف فلسفهٔ Intra (Race) که کارها موازی‌اند. حالت مشترکِ
 * حساس فقط «حافظهٔ نهان» است که با synchronized(cache) محافظت می‌شود.
 */
class DnsStub(
    @Volatile var upstream: UpstreamResolver,
    @Volatile var blockList: BlockList? = null,
    @Volatile var blockEnabled: Boolean = true,
    /** دامنه‌های استثنا (خود دامنه و همهٔ زیردامنه‌هایش): حتی اگر در فهرست مسدودسازی باشند پاسخ می‌گیرند. */
    @Volatile var allowList: Set<String>? = null,
    /** بازنویسی DNS (Cloaking — الگوی dnscrypt-proxy): دامنه → IP ثابت (IPv4 یا IPv6). */
    @Volatile var rewriteMap: Map<String, String>? = null,
    @Volatile var sinkholeEnabled: Boolean = false,
    @Volatile var sinkholeIp: String = "0.0.0.0",
    @Volatile var logEnabled: Boolean = true,
    @Volatile var blockIpv6: Boolean = false,
    private val cacheTtlSeconds: Long = 60,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    private class CacheEntry(val packet: ByteArray, val expiresAt: Long)

    private val cache = object : LinkedHashMap<String, CacheEntry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?) = size > MAX_CACHE
    }

    fun handle(data: ByteArray, len: Int): StubResult {
        VpnStats.countQuery()

        val msg = runCatching { Dns.parse(data, len) }.getOrNull()
            ?: return result(Dns.buildError(data, len, Dns.RCODE_FORMERR), false, null)

        val q = msg.question
            ?: return result(Dns.buildError(data, len, Dns.RCODE_FORMERR), false, null)

        val name = q.name.lowercase().removeSuffix(".")
        val typeLabel = Dns.typeLabel(q.type)

        // ۱) بازنویسی DNS (Cloaking): قاعدهٔ صریح کاربر بالاترین اولویت را دارد
        // (پشتیبانی از تطبیق دقیق/زیردامنه و هر دو خانوادهٔ IPv4 و IPv6)
        findRewrite(name)?.let { targetIp ->
            val isV6Target = targetIp.contains(':')
            val answer = when {
                !isV6Target && q.type == Dns.TYPE_A -> Dns.buildSyntheticA(data, len, targetIp)
                isV6Target && q.type == Dns.TYPE_AAAA -> Dns.buildSyntheticAAAA(data, len, targetIp)
                else -> Dns.buildError(data, len, Dns.RCODE_NOERROR, authoritative = true)
            }
            return result(answer, false, log(name, typeLabel, "بازنویسی ← $targetIp", LogEntry.Status.OK, 0))
        }

        // ۲) مسدودسازی IPv6 در صورت درخواست کاربر
        if (blockIpv6 && q.type == Dns.TYPE_AAAA) {
            val noData = Dns.buildError(data, len, Dns.RCODE_NOERROR, authoritative = true)
            VpnStats.countBlocked()
            return result(noData, true, log(name, typeLabel, "—", LogEntry.Status.BLOCKED, 0))
        }

        // ۳) فهرست مسدودسازی — دامنه‌های استثنا (و زیردامنه‌هایشان) هرگز مسدود نمی‌شوند
        if (!BlockList.matchesDomainOrParent(name, allowList) &&
            blockEnabled && blockList?.contains(name) == true
        ) {
            VpnStats.countBlocked()
            val answer = when {
                sinkholeEnabled && q.type == Dns.TYPE_A -> Dns.buildSyntheticA(data, len, sinkholeIp)
                sinkholeEnabled && q.type == Dns.TYPE_AAAA -> Dns.buildSyntheticAAAA(data, len, "::")
                else -> Dns.buildError(data, len, Dns.RCODE_NXDOMAIN)
            }
            return result(answer, true, log(name, typeLabel, "مسدود", LogEntry.Status.BLOCKED, 0))
        }

        // ۴) حافظهٔ نهان
        val ck = cacheKey(name, q.type)
        val hit = synchronized(cache) {
            val e = cache[ck]
            if (e != null && e.expiresAt > clock()) {
                rebind(e.packet, msg.id)
            } else {
                if (e != null) cache.remove(ck)
                null
            }
        }
        if (hit != null) {
            VpnStats.countCached()
            return result(hit, false, log(name, typeLabel, "حافظهٔ نهان", LogEntry.Status.CACHED, 0))
        }

        // ۵) ارسال به سرور بالادست
        val startedAt = clock()
        return try {
            val response = upstream.resolve(data.copyOf(len))
            val parsed = runCatching { Dns.parse(response) }.getOrNull()
            val pretty = parsed?.let { summarize(it) } ?: "${response.size} بایت"
            val status = when (parsed?.rcode) {
                Dns.RCODE_NOERROR -> LogEntry.Status.OK
                Dns.RCODE_NXDOMAIN -> LogEntry.Status.NXDOMAIN
                else -> LogEntry.Status.ERROR
            }
            if (parsed != null && parsed.rcode == Dns.RCODE_NOERROR && parsed.answers.isNotEmpty()) {
                putCache(name, q.type, response)
            }
            result(response, false, log(name, typeLabel, pretty, status, clock() - startedAt))
        } catch (e: Exception) {
            VpnStats.countError()
            VpnStats.lastError = e.message ?: e.javaClass.simpleName
            val err = Dns.buildError(data, len, Dns.RCODE_SERVFAIL)
            result(err, false, log(name, typeLabel, "خطا", LogEntry.Status.ERROR, clock() - startedAt))
        }
    }

    private fun findRewrite(domain: String): String? {
        val map = rewriteMap ?: return null
        if (map.isEmpty()) return null
        map[domain]?.let { return it }
        var idx = domain.indexOf('.')
        while (idx >= 0 && idx < domain.length - 1) {
            map[domain.substring(idx + 1)]?.let { return it }
            idx = domain.indexOf('.', idx + 1)
        }
        return null
    }

    private fun summarize(msg: Dns.Message): String {
        val addrs = msg.addresses
        if (addrs.isNotEmpty()) return addrs.take(3).joinToString("، ")
        val first = msg.answers.firstOrNull()
        return if (first != null) "${Dns.typeLabel(first.type)}: ${first.pretty}" else "بدون پاسخ"
    }

    private fun putCache(name: String, type: Int, response: ByteArray) {
        val ttl = runCatching {
            Dns.parse(response).answers.minOfOrNull { it.ttl }?.coerceIn(5, 3600) ?: cacheTtlSeconds
        }.getOrDefault(cacheTtlSeconds)
        val effective = if (ttl > cacheTtlSeconds) cacheTtlSeconds else ttl
        synchronized(cache) {
            cache[cacheKey(name, type)] = CacheEntry(response.copyOf(), clock() + effective * 1000)
        }
    }

    /** ID پاسخ نهان را با ID درخواست جاری جایگزین می‌کند. */
    private fun rebind(packet: ByteArray, id: Int): ByteArray {
        val out = packet.copyOf()
        if (out.size >= 2) Dns.putU16(out, 0, id)
        return out
    }

    private fun cacheKey(name: String, type: Int) = "$name|$type"

    private fun log(
        name: String,
        type: String,
        answer: String,
        status: LogEntry.Status,
        ms: Long
    ): LogEntry? {
        if (!logEnabled) return null
        val entry = LogEntry(clock(), name, type, answer, status, ms)
        VpnStats.addLog(entry)
        VpnStats.notifyLog(entry)
        return entry
    }

    private fun result(packet: ByteArray, blocked: Boolean, entry: LogEntry?): StubResult =
        StubResult(packet, packet.size, entry, blocked)

    fun clearCache() = synchronized(cache) { cache.clear() }

    companion object {
        const val MAX_CACHE = 512
    }
}
