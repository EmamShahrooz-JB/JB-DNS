package ir.jbdns.net

import ir.jbdns.core.Dns
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** رابط مشترک همهٔ پروتکل‌های بالادست. همهٔ متدها بلاک‌کننده‌اند و باید خارج از ترد اصلی صدا زده شوند. */
interface UpstreamResolver {
    val label: String
    fun resolve(query: ByteArray): ByteArray
    fun close()
}

/** DNS-over-HTTPS با قالب باینری RFC 8484 (POST). */
class DohResolver(
    private val url: String,
    connectTimeoutMs: Long = 5_000,
    readTimeoutMs: Long = 5_000
) : UpstreamResolver {

    override val label: String get() = "DoH"

    /**
     * آدرس‌ها با اولویت IPv4. روی شبکه‌های با IPv6 ناقص ( رایج در ایران)،
     * تلاش اول روی IPv6 یعنی چند ثانیه اتلاف تا timeout؛ IPv4-اول این تأخیر
     * را حذف می‌کند و IPv6 فقط وقتی امتحان می‌شود که IPv4 نباشد.
     */
    private object Ipv4First : okhttp3.Dns {
        override fun lookup(hostname: String): List<java.net.InetAddress> {
            val all = java.net.InetAddress.getAllByName(hostname).toList()
            return all.filter { it is java.net.Inet4Address } +
                all.filter { it !is java.net.Inet4Address }
        }
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .dns(Ipv4First)
        .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
        .writeTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val bodyType = "application/dns-message".toMediaType()

    override fun resolve(query: ByteArray): ByteArray {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/dns-message")
            .header("User-Agent", "JB-DNS/1.0")
            .post(query.toRequestBody(bodyType))
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("DoH HTTP ${resp.code}")
            val bytes = resp.body?.bytes() ?: throw IOException("DoH: بدنهٔ پاسخ خالی بود")
            if (bytes.size < Dns.HEADER_SIZE) throw IOException("DoH: پاسخ کوتاه")
            return bytes
        }
    }

    override fun close() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}

/**
 * DNS-over-TLS روی پورت ۸۵۳ با فریم‌بندی TCP (۲ بایت طول).
 *
 * نکتهٔ امنیتی: تأیید گواهی با
 * SSLParameters.endpointIdentificationAlgorithm = "HTTPS"
 * انجام می‌شود؛ این تنها روش استاندارد JSSE برای تطبیق نام میزبان با گواهی است.
 * (پیاده‌سازی قبلی session.peerHost را با نام میزبان مقایسه می‌کرد که برای سوکتِ
 * بدون‌اتصال عملاً نتیجهٔ reverse-DNS بود — نه SNI ارسال می‌شد نه گواهی واقعاً
 * بررسی می‌شد.)
 */
class DotResolver(
    private val hostname: String,
    private val port: Int = 853,
    private val verifyCertificate: Boolean = true,
    private val timeoutMs: Int = 8_000
) : UpstreamResolver {

    override val label: String get() = "DoT"

    /**
     * یک اتصال TLS ماندگار. مرورگرها ده‌ها پرس‌وجوی DNS را هم‌زمان می‌فرستند؛
     * اگر همه روی «یک» اتصال قفل شوند (پیاده‌سازی قبلی با @Synchronized)،
     * هر پرس‌وجو یک رفت‌وبرگشت کامل صبر می‌کند و بارگذاری صفحات سنگین می‌شود.
     * اینجا تا ۴ اتصال ماندگار در استخر نگه داشته می‌شوند و پرس‌وجوها موازی‌اند.
     */
    private class Conn(val socket: SSLSocket, val input: DataInputStream, val output: OutputStream)

    private val pool = ArrayDeque<Conn>()

    override fun resolve(query: ByteArray): ByteArray {
        var conn = borrow()
        try {
            writeFrame(conn.output, query)
            val resp = readFrame(conn.input)
            release(conn)
            return resp
        } catch (e: IOException) {
            closeConn(conn)
            // یک‌بار تلاش مجدد با اتصال تازه (اتصالات بی‌کار اغلب بسته می‌شوند)
            conn = borrow()
            try {
                writeFrame(conn.output, query)
                val resp = readFrame(conn.input)
                release(conn)
                return resp
            } catch (e2: IOException) {
                closeConn(conn)
                throw e2
            }
        }
    }

    /** اتصال آزاد از استخر؛ اگر نبود، اتصال تازه (بیرون قفل تا دست‌دارها نچسبند). */
    private fun borrow(): Conn = takePooled() ?: createConn()

    @Synchronized
    private fun takePooled(): Conn? = pool.removeFirstOrNull()

    /** برگرداندن اتصال سالم به استخر (تا سقف ۴؛ اضافه‌ها بسته می‌شوند). */
    @Synchronized
    private fun release(c: Conn) {
        if (pool.size < 4 && !c.socket.isClosed) pool.addLast(c) else closeConn(c)
    }

    private fun createConn(): Conn {
        // ۱) اتصال TCP خام با مهلت اتصال مشخص
        val raw = Socket()
        try {
            raw.connect(InetSocketAddress(hostname, port), timeoutMs)
            raw.soTimeout = timeoutMs
            raw.tcpNoDelay = true
        } catch (e: Exception) {
            runCatching { raw.close() }
            throw e
        }

        // ۲) لایه‌گذاری TLS روی سوکت موجود «با نام میزبان» — با این کار نام میزبان در
        //    دسترس JSSE قرار می‌گیرد و SNI درست ارسال می‌شود.
        //    نکتهٔ امنیتی: تأیید گواهی با endpointIdentificationAlgorithm="HTTPS"
        //    انجام می‌شود (تنها روش استاندارد JSSE برای تطبیق نام میزبان).
        val factory: SSLSocketFactory = if (verifyCertificate) {
            SSLContext.getInstance("TLS").apply { init(null, null, null) }.socketFactory
        } else {
            SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(TrustAll), null) }.socketFactory
        }
        val s = try {
            factory.createSocket(raw, hostname, port, true) as SSLSocket
        } catch (e: Exception) {
            runCatching { raw.close() }
            throw e
        }
        s.soTimeout = timeoutMs

        if (verifyCertificate) {
            // ۳) تأیید واقعی زنجیرهٔ گواهی و نام میزبان توسط JSSE
            val params: SSLParameters = s.sslParameters
            params.endpointIdentificationAlgorithm = "HTTPS"
            if (!isIpLiteral(hostname)) {
                runCatching { params.serverNames = listOf(SNIHostName(hostname)) }
            }
            s.sslParameters = params
        }

        try {
            s.startHandshake()
        } catch (e: Exception) {
            runCatching { s.close() }
            throw e
        }
        return Conn(s, DataInputStream(s.getInputStream()), s.getOutputStream())
    }

    /** آیا ورودی، آی‌پی است نه نام میزبان؟ (برای SNI نباید آی‌پی فرستاد) */
    private fun isIpLiteral(host: String): Boolean =
        host.matches(Regex("""^\d{1,3}(\.\d{1,3}){3}$""")) || host.contains(':')

    private fun writeFrame(out: OutputStream, data: ByteArray) {
        val header = byteArrayOf((data.size shr 8 and 0xFF).toByte(), (data.size and 0xFF).toByte())
        out.write(header)
        out.write(data)
        out.flush()
    }

    private fun readFrame(input: InputStream): ByteArray {
        val hi = input.read()
        if (hi < 0) throw IOException("DoT: اتصال بسته شد")
        val lo = input.read()
        if (lo < 0) throw IOException("DoT: اتصال بسته شد")
        val len = (hi shl 8) or lo
        if (len < Dns.HEADER_SIZE || len > 65535) throw IOException("DoT: طول نامعتبر $len")
        val buf = ByteArray(len)
        var off = 0
        while (off < len) {
            val n = input.read(buf, off, len - off)
            if (n < 0) throw IOException("DoT: پاسخ ناقص")
            off += n
        }
        return buf
    }

    override fun close() {
        val conns = synchronized(pool) {
            val copy = pool.toList()
            pool.clear()
            copy
        }
        conns.forEach { closeConn(it) }
    }

    private fun closeConn(c: Conn) {
        runCatching { c.input.close() }
        runCatching { c.output.close() }
        runCatching { c.socket.close() }
    }

    private object TrustAll : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<out java.security.cert.X509Certificate> = arrayOf()
    }
}

/** DNS کلاسیک روی پورت ۵۳ (بدون رمزنگاری) — برای سرورهای ایرانی و حالت اضطراری. */
class PlainDnsResolver(
    private val servers: List<String>,
    private val port: Int = 53,
    private val timeoutMs: Int = 4_000
) : UpstreamResolver {

    override val label: String get() = "DNS"

    override fun resolve(query: ByteArray): ByteArray {
        var lastError: IOException? = null
        for (server in servers) {
            try {
                DatagramSocket().use { sock ->
                    sock.soTimeout = timeoutMs
                    val target = InetSocketAddress(server, port)
                    sock.send(DatagramPacket(query, query.size, target))
                    val buf = ByteArray(4096)
                    val reply = DatagramPacket(buf, buf.size)
                    sock.receive(reply)
                    if (reply.length < Dns.HEADER_SIZE) throw IOException("پاسخ کوتاه از $server")
                    return buf.copyOf(reply.length)
                }
            } catch (e: IOException) {
                lastError = e
            }
        }
        throw lastError ?: IOException("سرور DNS در دسترس نیست")
    }

    override fun close() {}
}

/**
 * زنجیرهٔ سرورهای پشتیبان (الگوی dnscrypt-proxy): پرس‌وجو همیشه از آخرین
 * سرورِ سالم شروع می‌شود؛ اگر شکست خورد به بعدي می‌رود و سرور سالمِ جدید
 * می‌شود — یعنی قطعی یک سرور بی‌دست‌زدنِ کاربر جبران می‌شود.
 */
class ChainResolver(
    private val resolvers: List<UpstreamResolver>,
    private val onActiveChanged: ((index: Int) -> Unit)? = null,
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) : UpstreamResolver {

    @Volatile private var active = 0
    @Volatile private var lastProbeMs = 0L

    override val label: String get() = resolvers.getOrElse(active) { resolvers.first() }.label

    override fun resolve(query: ByteArray): ByteArray {
        // هر ۳۰ ثانیه یک‌بار از اولویت بالای زنجیره دوباره آزمایش می‌شود تا
        // سرور اصلیِ بازیابی‌شده به‌طور خودکار برگردد (کشفِ بازیابی ارزان است
        // چون کلید قطع‌کن هر سرورِ مرده فوری خطا می‌دهد)
        val now = nowMs()
        val probe = now - lastProbeMs >= RECOVERY_PROBE_MS
        if (probe) lastProbeMs = now
        val start = if (probe) 0 else active
        var last: Exception? = null
        for (k in resolvers.indices) {
            val i = (start + k) % resolvers.size
            try {
                val r = resolvers[i].resolve(query)
                if (i != active) {
                    active = i
                    onActiveChanged?.invoke(i)
                }
                return r
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IOException("هیچ سروری در زنجیره پاسخ نداد")
    }

    override fun close() {
        resolvers.forEach { runCatching { it.close() } }
    }

    companion object {
        const val RECOVERY_PROBE_MS = 30_000L
    }
}

/**
 * اگر پروتکل اصلی پاسخ نداد به DNS ساده برمی‌گردیم (روی شبکه‌های محدودکننده کارگشاست).
 *
 * کلید قطع‌کن (الگوی circuit breaker — تجربهٔ Intra در شبکه‌های سانسارگر):
 * بعد از ۳ شکست پیاپی، primary برای ۳۰ ثانیه دور زده می‌شود تا هر پرس‌وجو
 * منتظر کاملِ timeout نماند؛ پس از پایان مهلت، اولین پرس‌وجو دوباره primary
 * را می‌آزماید (half-open). اگر fallback تهی باشد، در حالت باز خطای فوری
 * داده می‌شود — خطای سریع بهتر از پرس‌وجوی ۵ ثانیه‌ای معطل است.
 */
class FallbackResolver(
    private val primary: UpstreamResolver,
    private val fallback: UpstreamResolver?,
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) : UpstreamResolver {

    override val label: String get() = primary.label

    @Volatile private var consecutiveFailures = 0
    @Volatile private var openUntilMs = 0L

    override fun resolve(query: ByteArray): ByteArray {
        if (nowMs() < openUntilMs) {
            // کلید باز است: بدون معطلی مسیر جایگزین (یا خطای فوری)
            return fallback?.resolve(query) ?: throw IOException("سرور اصلی موقتاً پاسخ نمی‌دهد")
        }
        try {
            val r = primary.resolve(query)
            consecutiveFailures = 0
            return r
        } catch (_: Exception) {
            if (++consecutiveFailures >= FAILURE_THRESHOLD) {
                openUntilMs = nowMs() + OPEN_MS
                consecutiveFailures = 0
            }
            return fallback?.resolve(query)
                ?: throw IOException("سرور اصلی پاسخ نمی‌دهد (۳ شکست پیاپی)")
        }
    }

    override fun close() {
        runCatching { primary.close() }
        runCatching { fallback?.close() }
    }

    companion object {
        const val FAILURE_THRESHOLD = 3
        const val OPEN_MS = 30_000L
    }
}

/**
 * حالت مسابقه (الگوی lb_strategy در dnscrypt-proxy و فلسفهٔ Race در Intra):
 * پرس‌وجو هم‌زمان به همهٔ سرورهای زنجیره می‌رود و «نخستین پاسخ موفق» برنده است.
 *
 * هزینه: چند بستهٔ اضافه در ازای حذف کاملِ «انتظار پشت سرور مرده» — سرعت
 * کاربر دیگر تابع کندترین عضو زنجیره نیست. اگر همه شکست بخورند، همان خطای
 * زنجیره صادر می‌شود (و DnsStub پاسخ SERVFAIL می‌دهد).
 */
class RaceResolver(
    private val resolvers: List<UpstreamResolver>,
    private val onWinner: ((index: Int) -> Unit)? = null
) : UpstreamResolver {

    override val label: String get() = "Race"

    private val pool: java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newFixedThreadPool(resolvers.size.coerceAtLeast(1)) { r ->
            Thread(r, "jb-race").apply { isDaemon = true }
        }

    override fun resolve(query: ByteArray): ByteArray {
        if (resolvers.isEmpty()) throw IOException("زنجیرهٔ مسابقه خالی است")
        if (resolvers.size == 1) return resolvers[0].resolve(query)

        val result = java.util.concurrent.atomic.AtomicReference<ByteArray?>(null)
        val latch = java.util.concurrent.CountDownLatch(resolvers.size)
        for ((i, r) in resolvers.withIndex()) {
            pool.execute {
                try {
                    val ans = r.resolve(query)
                    // فقط نخستین پاسخ موقّت برنده است؛ بقیه دور ریخته می‌شوند
                    if (result.compareAndSet(null, ans)) {
                        onWinner?.invoke(i)
                    }
                } catch (_: Exception) {
                    // شکست یک عضو عادی است؛ مسابقه ادامه دارد
                } finally {
                    latch.countDown()
                }
            }
        }
        latch.await()
        return result.get() ?: throw IOException("هیچ سروری در مسابقه پاسخ نداد")
    }

    override fun close() {
        pool.shutdownNow()
        resolvers.forEach { runCatching { it.close() } }
    }
}
