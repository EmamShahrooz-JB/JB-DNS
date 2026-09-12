package ir.jbdns.core

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * فهرست مسدودسازی راه دور با قالب hosts — الگو از RethinkDNS و personalDNSfilter.
 *
 * یک فایل hosts (مثل StevenBlack، AdAway یا خروجی AdGuard) از نشانی دلخواه دانلود،
 * تجزیه و در حافظه کش می‌شود و دامنه‌هایش در سطح DNS مسدود می‌شوند —
 * یعنی یک «Pi-hole شخصی» بدون سرور.
 *
 * قالب‌های پشتیبانی‌شده در هر خط:
 *   - hosts کلاسیک:        «0.0.0.0  ads.example.com»
 *   - فهرست دامنه ساده:    «ads.example.com»
 *   - سینتکس AdGuard:      «||ads.example.com^»
 */
object RemoteBlocklist {

    private const val FILE_NAME = "remote_blocklist.txt"

    /** سقف حجم دانلود. */
    private const val MAX_BYTES = 16L * 1024 * 1024

    /** سقف تعداد دامنه‌ها (پرهیز از کمبود حافظه با فهرست‌های خراب). */
    private const val MAX_ENTRIES = 1_000_000

    class Result(val ok: Boolean, val count: Int, val error: String? = null)

    @Volatile private var cachedRules: List<String>? = null
    @Volatile private var cacheStamp = -1L

    fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    /** دامنه‌های دانلودشده؛ اگر فایلی نبود تهی است. با کش بر اساس زمان فایل. */
    fun rules(context: Context): List<String> {
        val f = file(context)
        if (!f.exists()) {
            cachedRules = emptyList()
            return emptyList()
        }
        val stamp = f.lastModified()
        val cached = cachedRules
        if (cached != null && stamp == cacheStamp) return cached
        val rules = runCatching {
            f.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.map { it.trim() }.filter { it.isNotEmpty() }.toList()
            }
        }.getOrDefault(emptyList())
        cacheStamp = stamp
        cachedRules = rules
        return rules
    }

    /** دانلود و تجزیهٔ فهرست؛ نتیجه شامل تعداد دامنه‌های ذخیره‌شده است. */
    fun download(context: Context, url: String): Result {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return clear(context)
        return try {
            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
            val request = Request.Builder()
                .url(trimmed)
                .header("User-Agent", "JB-DNS/2.5")
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return Result(false, 0, "HTTP ${resp.code}")
                val body = resp.body ?: return Result(false, 0, "پاسخ خالی بود")

                val domains = HashSet<String>(8192)
                body.byteStream().bufferedReader(Charsets.UTF_8).useLines { lines ->
                    for (raw in lines) {
                        if (domains.size >= MAX_ENTRIES) break
                        val line = raw.substringBefore('#').trim()
                        if (line.isEmpty()) continue
                        var d = line.split(Regex("\\s+")).last()
                        d = d.removePrefix("||").removeSuffix("^").lowercase().trim('.')
                        if (d.isEmpty()) continue
                        // دامنه باید دست‌کم یک نقطه و فقط نویسهٔ مجاز داشته باشد
                        if (!d.contains('.')) continue
                        if (d.startsWith(".") || d.contains('*') || d.contains('/')) continue
                        if (d == "localhost" || d.endsWith(".localhost")) continue
                        domains.add(d)
                    }
                }

                val f = file(context)
                val tmp = File(f.absolutePath + ".tmp")
                tmp.bufferedWriter(Charsets.UTF_8).use { w ->
                    for (d in domains) w.write(d + "\n")
                }
                if (!tmp.renameTo(f)) {
                    f.delete()
                    if (!tmp.renameTo(f)) return Result(false, 0, "نوشتن فایل ناموفق بود")
                }
                cacheStamp = f.lastModified()
                cachedRules = domains.toList()
                Result(true, domains.size)
            }
        } catch (e: Exception) {
            Result(false, 0, e.message ?: "خطای دانلود")
        }
    }

    /** حذف فهرست راه دور. */
    fun clear(context: Context): Result {
        file(context).delete()
        cacheStamp = -1L
        cachedRules = emptyList()
        return Result(true, 0)
    }
}
