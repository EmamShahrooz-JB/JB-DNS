package ir.jbdns.core

import java.util.concurrent.CopyOnWriteArrayList

/** یک رویداد در لاگ زندهٔ DNS. */
data class LogEntry(
    val time: Long,
    val domain: String,
    val type: String,
    val answer: String,
    val status: Status,
    val ms: Long
) {
    enum class Status { OK, BLOCKED, ERROR, NXDOMAIN, CACHED }
}

/**
 * وضعیت زندهٔ تونل DNS. یک آبجکت سراسری است تا هم سرویس VPN به آن بنویسد
 * و هم رابط کاربری از آن بخواند (بدون نیاز به IPC).
 */
object VpnStats {

    @Volatile var active: Boolean = false
    @Volatile var startedAt: Long = 0L
    @Volatile var serverLabel: String = ""
    @Volatile var protoLabel: String = ""

    private val queries = java.util.concurrent.atomic.AtomicLong(0)
    private val blocked = java.util.concurrent.atomic.AtomicLong(0)
    private val errors = java.util.concurrent.atomic.AtomicLong(0)
    private val cached = java.util.concurrent.atomic.AtomicLong(0)

    @Volatile var lastError: String = ""

    val totalQueries: Long get() = queries.get()
    val totalBlocked: Long get() = blocked.get()
    val totalErrors: Long get() = errors.get()
    val totalCached: Long get() = cached.get()

    private const val MAX_LOG = 400
    private const val MAX_TOP = 900
    private val log = ArrayDeque<LogEntry>()
    private val listeners = CopyOnWriteArrayList<(LogEntry) -> Unit>()
    private val stateListeners = CopyOnWriteArrayList<() -> Unit>()

    fun countQuery() = queries.incrementAndGet()
    fun countBlocked() = blocked.incrementAndGet()
    fun countError() = errors.incrementAndGet()
    fun countCached() = cached.incrementAndGet()

    @Synchronized
    fun addLog(entry: LogEntry) {
        log.addFirst(entry)
        while (log.size > MAX_LOG) log.removeLast()
        recordStats(entry)
    }

    // ------------------------------------------------ آمار ۲۴ ساعته (نمودار)

    /** مدل آمار روز جاری برای نمودار صفحهٔ لاگ‌ها. */
    class DayStats(
        val queries: LongArray,      // ۲۴ خانه — پرس‌وجو در هر ساعت
        val blocked: LongArray,      // ۲۴ خانه — مسدودشده در هر ساعت
        val top: List<Pair<String, Int>>   // پربارش‌ترین دامنه‌ها
    )

    private val hourQueries = LongArray(24)
    private val hourBlocked = LongArray(24)
    private val topDomains = HashMap<String, Int>()
    private var statsDay = 0

    @Synchronized
    private fun recordStats(e: LogEntry) {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = e.time }
        val day = cal.get(java.util.Calendar.YEAR) * 1000 + cal.get(java.util.Calendar.DAY_OF_YEAR)
        if (day != statsDay) {
            java.util.Arrays.fill(hourQueries, 0)
            java.util.Arrays.fill(hourBlocked, 0)
            topDomains.clear()
            statsDay = day
        }
        val h = cal.get(java.util.Calendar.HOUR_OF_DAY)
        hourQueries[h]++
        if (e.status == LogEntry.Status.BLOCKED) hourBlocked[h]++
        topDomains.merge(e.domain, 1, Int::plus)
        if (topDomains.size > MAX_TOP) {
            // نگه‌داشتن پرتکرارترین‌ها تا حافظه رشد نکند
            val keep = topDomains.entries.sortedByDescending { it.value }.take(MAX_TOP / 3)
            topDomains.clear()
            for (k in keep) topDomains[k.key] = k.value
        }
    }

    @Synchronized
    fun dayStats(): DayStats = DayStats(
        hourQueries.copyOf(), hourBlocked.copyOf(),
        topDomains.entries.sortedByDescending { it.value }.take(8).map { it.key to it.value }
    )

    @Synchronized
    fun snapshot(): List<LogEntry> = log.toList()

    @Synchronized
    fun clearLog() = log.clear()

    fun reset() {
        queries.set(0); blocked.set(0); errors.set(0); cached.set(0)
        lastError = ""
        clearLog()
    }

    fun onLog(listener: (LogEntry) -> Unit) {
        listeners.add(listener)
    }

    fun offLog(listener: (LogEntry) -> Unit) {
        listeners.remove(listener)
    }

    fun onStateChange(listener: () -> Unit) {
        stateListeners.add(listener)
    }

    fun offStateChange(listener: () -> Unit) {
        stateListeners.remove(listener)
    }

    fun notifyStateChanged() {
        for (l in stateListeners) runCatching { l() }
    }

    fun notifyLog(entry: LogEntry) {
        for (l in listeners) runCatching { l(entry) }
    }

    fun uptimeMs(): Long = if (active && startedAt > 0) System.currentTimeMillis() - startedAt else 0

    fun formatUptime(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format("%02d:%02d:%02d", h, m, sec)
        else String.format("%02d:%02d", m, sec)
    }
}
