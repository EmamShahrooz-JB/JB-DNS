package ir.jbdns.net

import ir.jbdns.core.Dns
import ir.jbdns.core.HealthCheck
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.UnknownHostException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.SocketFactory
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import okhttp3.ConnectionPool
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** رابط مشترک همهٔ پروتکل‌های بالادست. همهٔ متدها بلاک‌کننده‌اند و باید خارج از ترد اصلی صدا زده شوند. */
interface UpstreamResolver {
    val label: String
    fun resolve(query: ByteArray): ByteArray
    fun close()
}

/**
 * کارخانهٔ سوکت محافظت‌شده با [android.net.VpnService.protect] تا سوکت‌های بالادست
 * حتی در حضور مسیرهای ضد-نشت (/32 برای 1.1.1.1، 8.8.8.8 و…) یا تونل افتراقی
 * هرگز به داخل خودِ تونل حلقه نزنند.
 */
internal class ProtectedSocketFactory(
    private val protect: (Socket) -> Unit
) : SocketFactory() {
    override fun createSocket(): Socket = Socket().also { runCatching { protect(it) } }
    override fun createSocket(host: String?, port: Int): Socket =
        createSocket().apply { connect(InetSocketAddress(host, port)) }
    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
        createSocket().apply { bind(InetSocketAddress(localHost, localPort)); connect(InetSocketAddress(host, port)) }
    override fun createSocket(host: InetAddress?, port: Int): Socket =
        createSocket().apply { connect(InetSocketAddress(host, port)) }
    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
        createSocket().apply { bind(InetSocketAddress(localAddress, localPort)); connect(InetSocketAddress(address, port)) }
}

/** TrustManager مشترک برای حالت «بدون بررسی گواهی» (سرورهای خصوصی خودامضا). */
internal object TrustAllX509 : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    fun socketFactory(): SSLSocketFactory =
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(this@TrustAllX509), SecureRandom())
        }.socketFactory
}

/**
 * ضد-مسموم‌سازی و Bootstrap برای DoH و DoT در شبکه‌های سانسورگر (به‌ویژه ISPهای ایران):
 *
 *  ۱. آدرس‌های متنی IP (bootstrapIps) مستقیم و بدون هیچ پرس‌وجوی شبکه با نام میزبانِ اصلی
 *     گره می‌خورند (`InetAddress.getByAddress(hostname, rawBytes)`) تا SNI و بررسی گواهی
 *     TLS کامل کار کند.
 *  ۲. هر پاسخی از DNS سیستم که IP فیلترینگ/لوپ‌بک (`10.10.34.*`، `0.0.0.0`، `127.*`) باشد
 *     بلافاصله دور ریخته می‌شود.
 *  ۳. اگر DNS سیستم مسموم یا قطع بود، نام میزبان از طریق DoH رمزنگاری‌شدهٔ مستقیم به IP
 *     (`1.1.1.1`، `8.8.8.8`، `9.9.9.9`) بدون تماس با پورت ۵۳ حل و ۱۰ دقیقه کش می‌شود.
 *  ۴. برای DoH ترتیب نامزدها «آدرس‌های تمیز نام میزبان + bootstrapIps» است (سازگار با CDNهایی
 *     مثل `cloudflare-dns.com` → `104.16.248.249` و `free.shecan.ir` → `185.51.200.2`)؛
 *     برای DoT ترتیب نامزدها «bootstrapIps + آدرس‌های تمیز نام میزبان» است (سازگار با پورت ۸۵۳
 *     روی `1.1.1.1` و هم‌زمان پشتیبان `76.76.2.11` برای Control D).
 */
object BootstrapDns {

    private class CacheEntry(val ips: List<String>, val expiresAtMs: Long)

    private const val CACHE_TTL_MS = 10 * 60_000L
    private val cache = ConcurrentHashMap<String, CacheEntry>()

    private val BOOTSTRAP_DOH_ENDPOINTS = listOf(
        "1.1.1.1" to "https://1.1.1.1/dns-query",
        "8.8.8.8" to "https://8.8.8.8/dns-query",
        "9.9.9.9" to "https://9.9.9.9/dns-query"
    )

    fun clearCache() = cache.clear()

    /** آیا رشته یک آدرس IP متنی (IPv4 یا IPv6) است نه نام دامنه؟ */
    fun isIpLiteral(host: String): Boolean {
        val h = host.trim().removePrefix("[").removeSuffix("]")
        if (h.contains(':')) return true
        val parts = h.split('.')
        return parts.size == 4 && parts.all { p -> p.toIntOrNull()?.let { it in 0..255 } == true }
    }

    /** ساخت InetAddress با نام میزبان دلخواه از روی متن IP «بدون هیچ فراخوانی DNS شبکه». */
    fun literalAddress(hostname: String, ipText: String): InetAddress? {
        val cleanIp = ipText.trim().removePrefix("[").removeSuffix("]")
        val cleanHost = hostname.trim().removePrefix("[").removeSuffix("]")
        val v4 = cleanIp.split('.')
        if (v4.size == 4) {
            val raw = ByteArray(4)
            for (i in 0..3) {
                val n = v4[i].toIntOrNull() ?: return null
                if (n !in 0..255) return null
                raw[i] = n.toByte()
            }
            return runCatching { InetAddress.getByAddress(cleanHost, raw) }.getOrNull()
        }
        if (cleanIp.contains(':')) {
            return runCatching {
                val parsed = InetAddress.getByName(cleanIp)
                InetAddress.getByAddress(cleanHost, parsed.address)
            }.getOrNull()
        }
        return null
    }

    /** حل نامزدهای اتصال برای DoH (پورت ۴۴۳): ابتدا IPهای تمیز نام میزبان، سپس bootstrapIps. */
    fun resolveForDoh(
        hostname: String,
        bootstrapIps: List<String> = emptyList(),
        protect: (Socket) -> Unit = {},
        timeoutMs: Int = 4_000,
        systemLookup: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
        encryptedLookup: ((String, (Socket) -> Unit, Int) -> List<String>)? = null
    ): List<InetAddress> = resolveCandidates(
        hostname = hostname,
        bootstrapIps = bootstrapIps,
        bootstrapFirst = false,
        protect = protect,
        timeoutMs = timeoutMs,
        systemLookup = systemLookup,
        encryptedLookup = encryptedLookup ?: ::queryBootstrapDoh
    )

    /** حل نامزدهای اتصال برای DoT (پورت ۸۵۳): ابتدا bootstrapIps، سپس IPهای تمیز نام میزبان. */
    fun resolveForDot(
        hostname: String,
        bootstrapIps: List<String> = emptyList(),
        protect: (Socket) -> Unit = {},
        timeoutMs: Int = 4_000,
        systemLookup: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
        encryptedLookup: ((String, (Socket) -> Unit, Int) -> List<String>)? = null
    ): List<InetAddress> = resolveCandidates(
        hostname = hostname,
        bootstrapIps = bootstrapIps,
        bootstrapFirst = true,
        protect = protect,
        timeoutMs = timeoutMs,
        systemLookup = systemLookup,
        encryptedLookup = encryptedLookup ?: ::queryBootstrapDoh
    )

    private fun resolveCandidates(
        hostname: String,
        bootstrapIps: List<String>,
        bootstrapFirst: Boolean,
        protect: (Socket) -> Unit,
        timeoutMs: Int,
        systemLookup: (String) -> List<InetAddress>,
        encryptedLookup: (String, (Socket) -> Unit, Int) -> List<String>
    ): List<InetAddress> {
        val cleanHost = hostname.trim().removePrefix("[").removeSuffix("]")
        if (isIpLiteral(cleanHost)) {
            literalAddress(cleanHost, cleanHost)?.let { return listOf(it) }
        }

        val bootClean = bootstrapIps.filter { it.isNotBlank() && !HealthCheck.isCensorshipIp(it) }
        val resolvedStrings = resolveCleanHostIps(
            hostname = cleanHost,
            hasBootstrapFallback = bootClean.isNotEmpty(),
            protect = protect,
            timeoutMs = timeoutMs,
            systemLookup = systemLookup,
            encryptedLookup = encryptedLookup
        )

        val orderedIps = LinkedHashSet<String>()
        if (bootstrapFirst) {
            orderedIps.addAll(bootClean)
            orderedIps.addAll(resolvedStrings)
        } else {
            orderedIps.addAll(resolvedStrings)
            orderedIps.addAll(bootClean)
        }

        val addrs = orderedIps.mapNotNull { literalAddress(cleanHost, it) }
        if (addrs.isEmpty()) throw UnknownHostException("نام میزبان حل نشد یا مسموم بود: $cleanHost")
        // اولویت با IPv4 برای جلوگیری از تأخیر روی شبکه‌های IPv6 ناقص ایران
        return addrs.filter { it is Inet4Address } + addrs.filter { it !is Inet4Address }
    }

    private fun resolveCleanHostIps(
        hostname: String,
        hasBootstrapFallback: Boolean,
        protect: (Socket) -> Unit,
        timeoutMs: Int,
        systemLookup: (String) -> List<InetAddress>,
        encryptedLookup: (String, (Socket) -> Unit, Int) -> List<String>
    ): List<String> {
        val now = System.currentTimeMillis()
        cache[hostname]?.let { cached ->
            if (cached.expiresAtMs > now && cached.ips.isNotEmpty()) return cached.ips
        }

        // گام ۱: DNS سیستم با پالایش سخت‌گیرانهٔ IPهای فیلترینگ/لوپ‌بک
        val sysClean = runCatching {
            val all = systemLookup(hostname)
            val ordered = all.filter { it is Inet4Address } + all.filter { it !is Inet4Address }
            ordered.mapNotNull { it.hostAddress }.filter { !HealthCheck.isCensorshipIp(it) }.distinct()
        }.getOrDefault(emptyList())

        if (sysClean.isNotEmpty()) {
            cache[hostname] = CacheEntry(sysClean, now + CACHE_TTL_MS)
            return sysClean
        }

        // گام ۲: DNS سیستم مسموم یا قطع بود → پرس‌وجوی رمزنگاری‌شدهٔ مستقیم به IP (بدون پورت ۵۳)
        val probeBudget = if (hasBootstrapFallback) minOf(timeoutMs, 1_800) else minOf(timeoutMs, 3_500)
        val encClean = runCatching {
            encryptedLookup(hostname, protect, probeBudget)
                .filter { it.isNotBlank() && !HealthCheck.isCensorshipIp(it) }
                .distinct()
        }.getOrDefault(emptyList())

        if (encClean.isNotEmpty()) {
            cache[hostname] = CacheEntry(encClean, now + CACHE_TTL_MS)
        }
        return encClean
    }

    /** پرس‌وجوی DoH مستقیم با آدرس IP تا هیچ وابستگی به پورت ۵۳ باقی نماند. */
    private fun queryBootstrapDoh(hostname: String, protect: (Socket) -> Unit, timeoutMs: Int): List<String> {
        val query = Dns.buildQuery(0x4254, hostname, Dns.TYPE_A)
        val bodyType = "application/dns-message".toMediaType()
        val client = OkHttpClient.Builder()
            .socketFactory(ProtectedSocketFactory(protect))
            .dns(object : okhttp3.Dns {
                override fun lookup(hostname: String): List<InetAddress> =
                    literalAddress(hostname, hostname)?.let { listOf(it) }
                        ?: throw UnknownHostException(hostname)
            })
            .connectTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .writeTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .connectionPool(DohResolver.SHARED_POOL)
            .build()

        for ((_, endpoint) in BOOTSTRAP_DOH_ENDPOINTS) {
            try {
                val req = Request.Builder()
                    .url(endpoint)
                    .header("Accept", "application/dns-message")
                    .header("User-Agent", "JB-DNS/4.3")
                    .post(query.toRequestBody(bodyType))
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val bytes = resp.body?.bytes() ?: return@use
                    if (bytes.size < Dns.HEADER_SIZE) return@use
                    val parsed = Dns.parse(bytes)
                    if (parsed.rcode == Dns.RCODE_NOERROR) {
                        val clean = parsed.addresses.filter { !HealthCheck.isCensorshipIp(it) }
                        if (clean.isNotEmpty()) return clean
                    }
                }
            } catch (_: Exception) {
            }
        }
        return emptyList()
    }
}

/** DNS-over-HTTPS با قالب باینری RFC 8484 (POST)، Bootstrap ضد-سانسور و استخر مشترک HTTP/2. */
class DohResolver(
    private val url: String,
    connectTimeoutMs: Long = 5_000,
    readTimeoutMs: Long = 5_000,
    private val bootstrapIps: List<String> = emptyList(),
    private val verifyCertificate: Boolean = true,
    private val protect: (Socket) -> Unit = {}
) : UpstreamResolver {

    override val label: String get() = "DoH"

    private val targetHost: String = url.toHttpUrlOrNull()?.host.orEmpty()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectionPool(SHARED_POOL)
        .socketFactory(ProtectedSocketFactory(protect))
        .dns(object : okhttp3.Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                val boot = if (hostname.equals(targetHost, ignoreCase = true)) bootstrapIps else emptyList()
                return BootstrapDns.resolveForDoh(
                    hostname = hostname,
                    bootstrapIps = boot,
                    protect = protect,
                    timeoutMs = connectTimeoutMs.toInt()
                )
            }
        })
        .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
        .writeTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .apply {
            if (!verifyCertificate) {
                sslSocketFactory(TrustAllX509.socketFactory(), TrustAllX509)
                hostnameVerifier { _, _ -> true }
            }
        }
        .build()

    private val bodyType = "application/dns-message".toMediaType()

    override fun resolve(query: ByteArray): ByteArray {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/dns-message")
            .header("User-Agent", "JB-DNS/4.3")
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
        // استخر مشترک با سایر نمونه‌ها شریک است؛ در تعویض شبکه از evictAll استفاده می‌شود
    }

    companion object {
        internal val SHARED_POOL = ConnectionPool(12, 2, TimeUnit.MINUTES)

        /** تخلیهٔ فوری همهٔ اتصالات HTTP/2 کهنه هنگام تعویض Wi-Fi ↔ دادهٔ همراه. */
        fun evictAll() {
            runCatching { SHARED_POOL.evictAll() }
        }
    }
}

/**
 * DNS-over-TLS روی پورت ۸۵۳ با فریم‌بندی TCP (۲ بایت طول)، Bootstrap ضد-سانسور،
 * SNI صریح و استخر ۴ اتصال ماندگار موازی.
 */
class DotResolver(
    private val hostname: String,
    private val port: Int = 853,
    private val verifyCertificate: Boolean = true,
    private val timeoutMs: Int = 8_000,
    private val bootstrapIps: List<String> = emptyList(),
    private val protect: (Socket) -> Unit = {}
) : UpstreamResolver {

    override val label: String get() = "DoT"

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

    private fun borrow(): Conn = takePooled() ?: createConn()

    @Synchronized
    private fun takePooled(): Conn? = pool.removeFirstOrNull()

    @Synchronized
    private fun release(c: Conn) {
        if (pool.size < 4 && !c.socket.isClosed && c.socket.isConnected) pool.addLast(c) else closeConn(c)
    }

    private fun createConn(): Conn {
        val candidates = BootstrapDns.resolveForDot(
            hostname = hostname,
            bootstrapIps = bootstrapIps,
            protect = protect,
            timeoutMs = timeoutMs
        )
        val factory: SSLSocketFactory = if (verifyCertificate) {
            SSLContext.getInstance("TLS").apply { init(null, null, null) }.socketFactory
        } else {
            TrustAllX509.socketFactory()
        }

        // اگر چند IP نامزد داریم (مثلاً Control D: 76.76.2.0 سپس 76.76.2.11)، مهلت اتصال TCP
        // بین نامزدها تقسیم می‌شود تا IP اول کل مهلت را هدر ندهد
        val perConnectMs = if (candidates.size > 1) {
            (timeoutMs / candidates.size).coerceAtLeast(1_800)
        } else {
            timeoutMs
        }

        var lastErr: Exception? = null
        for (addr in candidates) {
            val raw = Socket()
            try {
                runCatching { protect(raw) }
                raw.tcpNoDelay = true
                raw.soTimeout = timeoutMs
                raw.connect(InetSocketAddress(addr, port), perConnectMs)

                val s = factory.createSocket(raw, hostname, port, true) as SSLSocket
                s.soTimeout = timeoutMs
                val params: SSLParameters = s.sslParameters
                if (verifyCertificate) {
                    params.endpointIdentificationAlgorithm = "HTTPS"
                }
                if (!BootstrapDns.isIpLiteral(hostname)) {
                    runCatching { params.serverNames = listOf(SNIHostName(hostname)) }
                }
                s.sslParameters = params
                s.startHandshake()
                return Conn(s, DataInputStream(s.inputStream), s.outputStream)
            } catch (e: Exception) {
                lastErr = e
                runCatching { raw.close() }
            }
        }
        throw (lastErr as? IOException) ?: IOException(lastErr?.message ?: "اتصال DoT برقرار نشد", lastErr)
    }

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
}

/**
 * DNS کلاسیک روی پورت ۵۳ (UDP با بازگشت خودکار به TCP در پاسخ کوتاه‌شده TC=1 یا مسدودی UDP).
 */
class PlainDnsResolver(
    private val servers: List<String>,
    private val port: Int = 53,
    private val timeoutMs: Int = 4_000,
    private val protectDatagram: (DatagramSocket) -> Unit = {},
    private val protectSocket: (Socket) -> Unit = {}
) : UpstreamResolver {

    override val label: String get() = "DNS"

    override fun resolve(query: ByteArray): ByteArray {
        var lastError: IOException? = null
        for (server in servers) {
            try {
                val udpReply = runCatching { queryUdp(server, query) }.getOrNull()
                if (udpReply != null) {
                    val truncated = runCatching { Dns.parse(udpReply).isTruncated }.getOrDefault(false)
                    if (!truncated) return udpReply
                    // پاسخ بزرگ‌تر از سقف UDP بود (TC=1) ← بازپرس‌وجوی استاندارد RFC 1035 روی TCP
                    return runCatching { queryTcp(server, query) }.getOrDefault(udpReply)
                }
                return queryTcp(server, query)
            } catch (e: IOException) {
                lastError = e
            }
        }
        throw lastError ?: IOException("سرور DNS در دسترس نیست")
    }

    internal fun queryUdp(server: String, query: ByteArray): ByteArray {
        DatagramSocket().use { sock ->
            runCatching { protectDatagram(sock) }
            sock.soTimeout = timeoutMs
            val target = InetSocketAddress(server, port)
            sock.send(DatagramPacket(query, query.size, target))
            val buf = ByteArray(4096)
            val reply = DatagramPacket(buf, buf.size)
            sock.receive(reply)
            if (reply.length < Dns.HEADER_SIZE) throw IOException("پاسخ کوتاه از $server")
            return buf.copyOf(reply.length)
        }
    }

    internal fun queryTcp(server: String, query: ByteArray): ByteArray {
        Socket().use { sock ->
            runCatching { protectSocket(sock) }
            sock.tcpNoDelay = true
            sock.soTimeout = timeoutMs
            sock.connect(InetSocketAddress(server, port), timeoutMs)
            val out = sock.outputStream
            out.write(byteArrayOf((query.size shr 8 and 0xFF).toByte(), (query.size and 0xFF).toByte()))
            out.write(query)
            out.flush()
            val input = sock.inputStream
            val hi = input.read()
            val lo = input.read()
            if (hi < 0 || lo < 0) throw IOException("DNS/TCP: اتصال بسته شد ($server)")
            val len = (hi shl 8) or lo
            if (len < Dns.HEADER_SIZE || len > 65535) throw IOException("DNS/TCP: طول نامعتبر $len")
            val buf = ByteArray(len)
            var off = 0
            while (off < len) {
                val n = input.read(buf, off, len - off)
                if (n < 0) throw IOException("DNS/TCP: پاسخ ناقص")
                off += n
            }
            return buf
        }
    }

    override fun close() {}
}

/**
 * زنجیرهٔ سرورهای پشتیبان (الگوی dnscrypt-proxy): پرس‌وجو همیشه از آخرین
 * سرورِ سالم شروع می‌شود؛ اگر شکست خورد به بعدی می‌رود و سرور سالمِ جدید
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
 * کلید قطع‌کن (الگوی circuit breaker — تجربهٔ Intra در شبکه‌های سانسورگر):
 * بعد از ۳ شکست پیاپی، primary برای ۳۰ ثانیه دور زده می‌شود تا هر پرس‌وجو
 * منتظر کاملِ timeout نماند؛ پس از پایان مهلت، اولین پرس‌وجو دوباره primary
 * را می‌آزماید (half-open).
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
 * حالت مسابقهٔ واقعی (Fast-Win Race — الگوی lb_strategy در dnscrypt-proxy):
 *  - به‌محض رسیدن نخستین پاسخ «سالم و غیرسانسورشده» بلافاصله برمی‌گردد و منتظر
 *    تایم‌اوت سرورهای کند یا مسدود نمی‌ماند!
 *  - پاسخ‌های مسموم (`10.10.34.*` / `0.0.0.0`) یا خطای بالادست (`SERVFAIL`/`REFUSED`)
 *    پیروزی را از سرورهای سالم نمی‌ربایند.
 */
class RaceResolver(
    private val resolvers: List<UpstreamResolver>,
    private val onWinner: ((index: Int) -> Unit)? = null,
    private val timeoutMs: Long = 5_500L
) : UpstreamResolver {

    override val label: String get() = "Race"

    private val pool: ExecutorService =
        Executors.newCachedThreadPool { r ->
            Thread(r, "jb-race").apply { isDaemon = true }
        }

    override fun resolve(query: ByteArray): ByteArray {
        if (resolvers.isEmpty()) throw IOException("زنجیرهٔ مسابقه خالی است")
        if (resolvers.size == 1) return resolvers[0].resolve(query)

        val winner = AtomicReference<ByteArray?>(null)
        val fallback = AtomicReference<ByteArray?>(null)
        val remaining = AtomicInteger(resolvers.size)
        val started = CountDownLatch(resolvers.size)
        val done = CountDownLatch(1)

        for ((i, r) in resolvers.withIndex()) {
            pool.execute {
                started.countDown()
                try {
                    val ans = r.resolve(query)
                    if (isCleanWinner(ans)) {
                        if (winner.compareAndSet(null, ans)) {
                            onWinner?.invoke(i)
                            done.countDown()
                        }
                    } else {
                        fallback.compareAndSet(null, ans)
                    }
                } catch (_: Exception) {
                    // شکست یک عضو عادی است؛ سایر اعضا در حال مسابقه‌اند
                } finally {
                    if (remaining.decrementAndGet() == 0) {
                        done.countDown()
                    }
                }
            }
        }

        // تضمین شروع همهٔ نخ‌ها (زیر ۱ میلی‌ثانیه) + خروج فوری به محض اولین برنده
        started.await(15, TimeUnit.MILLISECONDS)
        done.await(timeoutMs, TimeUnit.MILLISECONDS)
        winner.get()?.let { return it }
        fallback.get()?.let { return it }
        throw IOException("هیچ سروری در مسابقه پاسخ نداد")
    }

    private fun isCleanWinner(packet: ByteArray): Boolean {
        if (packet.size < Dns.HEADER_SIZE) return true
        val msg = runCatching { Dns.parse(packet) }.getOrNull() ?: return false
        if (msg.rcode != Dns.RCODE_NOERROR && msg.rcode != Dns.RCODE_NXDOMAIN) return false
        if (msg.addresses.any { HealthCheck.isCensorshipIp(it) }) return false
        return true
    }

    override fun close() {
        pool.shutdownNow()
        resolvers.forEach { runCatching { it.close() } }
    }
}
