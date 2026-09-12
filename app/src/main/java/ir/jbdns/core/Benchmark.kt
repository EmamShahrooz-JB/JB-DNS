package ir.jbdns.core

import ir.jbdns.data.Prefs
import ir.jbdns.data.Server
import ir.jbdns.net.UpstreamFactory
import ir.jbdns.net.UpstreamResolver

/** نتیجهٔ سرعت‌سنجی یک سرور. */
data class BenchResult(
    val serverId: String,
    val ok: Boolean,
    val medianMs: Long,
    val bestMs: Long,
    val worstMs: Long,
    val answered: Int,
    val total: Int,
    val error: String? = null
) {
    val successRate: Int get() = if (total == 0) 0 else (answered * 100) / total
}

/**
 * سرعت‌سنجی واقعی: برای هر سرور چند پرس‌وجوی A و AAAA روی همان پروتکل
 * انتخاب‌شدهٔ کاربر ارسال می‌شود و میانهٔ زمان پاسخ گزارش می‌گردد.
 *
 * @param timeoutMs مهلت هر اتصال/پرس‌وجو (برای سنجش همهٔ سرورها مقدار کوتاه بدهید).
 */
object Benchmark {

    private val DOMAINS = listOf("one.one.one.one", "www.google.com", "github.com", "cdn.jsdelivr.net")

    fun run(server: Server, prefs: Prefs, rounds: Int = 3, timeoutMs: Int? = null): BenchResult {
        var resolver: UpstreamResolver? = null
        return try {
            resolver = UpstreamFactory.create(server, prefs, timeoutMs)
            measure(server.id, resolver, rounds)
        } catch (e: Exception) {
            BenchResult(server.id, false, -1, -1, -1, 0, rounds * DOMAINS.size * 2, e.message ?: "خطا")
        } finally {
            runCatching { resolver?.close() }
        }
    }

    private fun measure(id: String, resolver: UpstreamResolver, rounds: Int): BenchResult {
        val samples = ArrayList<Long>()
        var answered = 0
        var attempts = 0
        var firstError: String? = null

        for (domain in DOMAINS) {
            for (type in intArrayOf(Dns.TYPE_A, Dns.TYPE_AAAA)) {
                for (round in 0 until rounds) {
                    val query = Dns.buildQuery(0x1234 + attempts, domain, type)
                    attempts++
                    val startedAt = System.nanoTime()
                    try {
                        val response = resolver.resolve(query)
                        val msg = runCatching { Dns.parse(response) }.getOrNull()
                        val ms = (System.nanoTime() - startedAt) / 1_000_000
                        if (msg != null && msg.rcode == Dns.RCODE_NOERROR) {
                            samples.add(ms)
                            if (msg.answers.isNotEmpty()) answered++
                        } else if (firstError == null) {
                            firstError = msg?.let { Dns.rcodeLabel(it.rcode) } ?: "پاسخ نامعتبر"
                        }
                    } catch (e: Exception) {
                        if (firstError == null) firstError = e.message ?: e.javaClass.simpleName
                    }
                }
            }
        }

        if (samples.isEmpty()) {
            return BenchResult(id, false, -1, -1, -1, 0, attempts, firstError ?: "بدون پاسخ")
        }
        samples.sort()
        val median = samples[samples.size / 2]
        return BenchResult(
            serverId = id,
            ok = true,
            medianMs = median,
            bestMs = samples.first(),
            worstMs = samples.last(),
            answered = answered,
            total = attempts,
            error = firstError
        )
    }
}
