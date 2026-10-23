package ir.jbdns

import ir.jbdns.core.BackupData
import ir.jbdns.core.BlockList
import ir.jbdns.core.Dns
import ir.jbdns.core.DnsStub
import ir.jbdns.core.HealthCheck
import ir.jbdns.core.SettingsBackup
import ir.jbdns.net.BootstrapDns
import ir.jbdns.net.DnscryptCrypto
import ir.jbdns.net.DnscryptResolver
import ir.jbdns.net.DotResolver
import ir.jbdns.net.PlainDnsResolver
import ir.jbdns.net.RaceResolver
import ir.jbdns.net.UpstreamResolver
import org.bouncycastle.math.ec.rfc8032.Ed25519
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.UnknownHostException
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

/**
 * مجموعه تست‌های جامع امنیت، ضد-سانسور، کارایی و صحت پروتکل‌ها (نسخهٔ ۴٫۳):
 *  - ضد-مسموم‌سازی Bootstrap در DoH و DoT (فیلترینگ 10.10.34.* / 0.0.0.0 + بازگشت DoH رمزنگاری‌شده)
 *  - پیروزی فوری در RaceResolver بدون انتظار پشت سرور مرده + رد پاسخ‌های سانسورشده
 *  - کش واقعی گواهی DNSCrypt + بازگشت خودکار DNSCrypt به TCP هنگام بسته‌بودن UDP
 *  - بازگشت خودکار PlainDnsResolver به TCP هنگام بیت TC=1
 *  - استثنای زیردامنه‌ها در allowList، Sinkhole روی IPv6، بازنویسی IPv6 و پاک‌سازی برچسب‌های مخرب HTML
 *  - کلید log در پشتیبان تنظیمات و اتصال واقعی Cloudflare DoT با Bootstrap IP
 */
class SecurityAndResilienceTest {

    @Test
    fun `bootstrap discards poisoned ISP answers and falls back to clean encrypted lookup`() {
        BootstrapDns.clearCache()
        var encryptedCalls = 0
        val addrs = BootstrapDns.resolveForDoh(
            hostname = "my-custom-doh.example",
            bootstrapIps = emptyList(),
            systemLookup = {
                listOf(
                    InetAddress.getByAddress("my-custom-doh.example", byteArrayOf(10, 10, 34, 36)),
                    InetAddress.getByAddress("my-custom-doh.example", byteArrayOf(0, 0, 0, 0))
                )
            },
            encryptedLookup = { _, _, _ ->
                encryptedCalls++
                listOf("104.21.44.88")
            }
        )
        assertEquals("باید سراغ DoH ضدسانسور برود", 1, encryptedCalls)
        assertEquals(listOf("104.21.44.88"), addrs.map { it.hostAddress })
        assertEquals("نام میزبان اصلی باید برای SNI حفظ شود", "my-custom-doh.example", addrs.first().hostName)

        // بار دوم باید از کش ۱۰ دقیقه‌ای خوانده شود بدون پرس‌وجوی دوباره
        val second = BootstrapDns.resolveForDoh(
            hostname = "my-custom-doh.example",
            systemLookup = { throw UnknownHostException("offline") },
            encryptedLookup = { _, _, _ ->
                encryptedCalls++
                listOf("104.21.44.88")
            }
        )
        assertEquals("کش Bootstrap باید مانع پرس‌وجوی تکراری شود", 1, encryptedCalls)
        assertEquals(listOf("104.21.44.88"), second.map { it.hostAddress })
    }

    @Test
    fun `bootstrap survives total international blackout when bootstrapIps exist`() {
        BootstrapDns.clearCache()
        val addrs = BootstrapDns.resolveForDoh(
            hostname = "free.shecan.ir",
            bootstrapIps = listOf("185.51.200.2", "178.22.122.100"),
            systemLookup = { throw UnknownHostException("national intranet only") },
            encryptedLookup = { _, _, _ -> emptyList() }
        )
        assertEquals(listOf("185.51.200.2", "178.22.122.100"), addrs.map { it.hostAddress })
        assertEquals("free.shecan.ir", addrs.first().hostName)
    }

    @Test
    fun `dot bootstrap prioritizes bootstrapIps then falls back to clean host ip`() {
        BootstrapDns.clearCache()
        val dotAddrs = BootstrapDns.resolveForDot(
            hostname = "p0.freedns.controld.com",
            bootstrapIps = listOf("76.76.2.0"),
            systemLookup = {
                listOf(InetAddress.getByAddress("p0.freedns.controld.com", byteArrayOf(76, 76, 2, 11)))
            }
        )
        assertEquals(listOf("76.76.2.0", "76.76.2.11"), dotAddrs.map { it.hostAddress })
    }

    @Test
    fun `race resolver returns immediately on fast winner without waiting for hung server`() {
        val hung = object : UpstreamResolver {
            override val label = "hung"
            override fun resolve(query: ByteArray): ByteArray {
                Thread.sleep(3_000)
                return Dns.buildResponseWithA(query, query.size, listOf("9.9.9.9"))
            }
            override fun close() {}
        }
        val fast = object : UpstreamResolver {
            override val label = "fast"
            override fun resolve(query: ByteArray): ByteArray {
                Thread.sleep(25)
                return Dns.buildResponseWithA(query, query.size, listOf("1.1.1.1"))
            }
            override fun close() {}
        }
        val race = RaceResolver(listOf(hung, fast))
        val q = Dns.buildQuery(0x1111, "example.com", Dns.TYPE_A)
        val t0 = System.nanoTime()
        val resp = race.resolve(q)
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000
        race.close()

        assertEquals(listOf("1.1.1.1"), Dns.parse(resp).addresses)
        assertTrue("نباید منتظر سرور ۳۰۰۰ms بماند اما ${elapsedMs}ms طول کشید", elapsedMs < 800)
    }

    @Test
    fun `race resolver rejects fast poisoned 10_10_34 answer in favor of clean encrypted answer`() {
        val poisonedFast = object : UpstreamResolver {
            override val label = "isp-hijack"
            override fun resolve(query: ByteArray): ByteArray {
                Thread.sleep(5)
                return Dns.buildResponseWithA(query, query.size, listOf("10.10.34.36"))
            }
            override fun close() {}
        }
        val cleanDoh = object : UpstreamResolver {
            override val label = "clean-doh"
            override fun resolve(query: ByteArray): ByteArray {
                Thread.sleep(55)
                return Dns.buildResponseWithA(query, query.size, listOf("104.244.42.1"))
            }
            override fun close() {}
        }
        var winner = -1
        val race = RaceResolver(listOf(poisonedFast, cleanDoh), onWinner = { winner = it })
        val q = Dns.buildQuery(0x2222, "twitter.com", Dns.TYPE_A)
        val resp = race.resolve(q)
        race.close()

        assertEquals("سرور تمیز باید برنده شود نه پاسخ فیلترینگ!", 1, winner)
        assertEquals(listOf("104.244.42.1"), Dns.parse(resp).addresses)
    }

    @Test
    fun `plain dns resolver automatically retries over TCP when UDP response has TC bit set`() {
        val udpServer = DatagramSocket(0, InetAddress.getLoopbackAddress())
        val port = udpServer.localPort
        val tcpServer = ServerSocket(port, 4, InetAddress.getLoopbackAddress())
        val tcpHandled = AtomicInteger(0)

        val udpThread = Thread {
            runCatching {
                val buf = ByteArray(512)
                val pkt = DatagramPacket(buf, buf.size)
                udpServer.receive(pkt)
                // پاسخ کوتاه‌شده با بیت TC=1 (0x8380)
                val trunc = ByteArray(Dns.HEADER_SIZE)
                Dns.putU16(trunc, 0, Dns.u16(buf, 0))
                Dns.putU16(trunc, 2, 0x8380)
                udpServer.send(DatagramPacket(trunc, trunc.size, pkt.socketAddress))
            }
        }.apply { isDaemon = true; start() }

        val tcpThread = Thread {
            runCatching {
                tcpServer.accept().use { client ->
                    tcpHandled.incrementAndGet()
                    val inp = client.inputStream
                    val len = (inp.read() shl 8) or inp.read()
                    val req = ByteArray(len)
                    var off = 0
                    while (off < len) off += inp.read(req, off, len - off)
                    val full = Dns.buildResponseWithA(req, req.size, listOf("93.184.216.34"))
                    val out = client.outputStream
                    out.write(byteArrayOf((full.size shr 8 and 0xFF).toByte(), (full.size and 0xFF).toByte()))
                    out.write(full)
                    out.flush()
                }
            }
        }.apply { isDaemon = true; start() }

        try {
            val resolver = PlainDnsResolver(listOf("127.0.0.1"), port = port, timeoutMs = 2_000)
            val q = Dns.buildQuery(0x3333, "large.example.com", Dns.TYPE_A)
            val ans = Dns.parse(resolver.resolve(q))
            assertEquals(1, tcpHandled.get())
            assertFalse(ans.isTruncated)
            assertEquals(listOf("93.184.216.34"), ans.addresses)
        } finally {
            runCatching { udpServer.close() }
            runCatching { tcpServer.close() }
            udpThread.join(500)
            tcpThread.join(500)
        }
    }

    @Test
    fun `dnscrypt caches server certificate across queries and falls back to TCP when UDP blocked`() {
        val rng = SecureRandom()
        val providerSk = ByteArray(32).also { rng.nextBytes(it) }
        val providerPk = ByteArray(32)
        Ed25519.generatePublicKey(providerSk, 0, providerPk, 0)
        val serverKp = DnscryptCrypto.newX25519KeyPair(rng)
        val clientMagic = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val resolverMagic = byteArrayOf(0x72, 0x36, 0x66, 0x6e, 0x76, 0x57, 0x6a, 0x38)

        // ساخت گواهی معتبر امضاشده با Ed25519
        val nowSec = (System.currentTimeMillis() / 1000L).toInt()
        val endSec = nowSec + 7200
        val signedBody = ByteArray(52)
        System.arraycopy(serverKp.second, 0, signedBody, 0, 32)
        System.arraycopy(clientMagic, 0, signedBody, 32, 8)
        signedBody[43] = 1
        signedBody[44] = (nowSec ushr 24).toByte(); signedBody[45] = (nowSec ushr 16).toByte()
        signedBody[46] = (nowSec ushr 8).toByte(); signedBody[47] = nowSec.toByte()
        signedBody[48] = (endSec ushr 24).toByte(); signedBody[49] = (endSec ushr 16).toByte()
        signedBody[50] = (endSec ushr 8).toByte(); signedBody[51] = endSec.toByte()

        val sig = ByteArray(64)
        Ed25519.sign(providerSk, 0, signedBody, 0, signedBody.size, sig, 0)

        val certBytes = ByteArray(124)
        certBytes[0] = 0x44; certBytes[1] = 0x4E; certBytes[2] = 0x53; certBytes[3] = 0x43
        certBytes[4] = 0; certBytes[5] = 2 // XChaCha20-Poly1305
        System.arraycopy(sig, 0, certBytes, 8, 64)
        System.arraycopy(signedBody, 0, certBytes, 72, 52)

        // پورتی که فقط TCP گوش می‌دهد و هیچ سوکت UDP روی آن باز نیست!
        val tcpServer = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        val port = tcpServer.localPort
        val certFetches = AtomicInteger(0)
        val encryptedQueries = AtomicInteger(0)

        val serverThread = Thread {
            while (!tcpServer.isClosed) {
                val sock = runCatching { tcpServer.accept() }.getOrNull() ?: break
                sock.use { client ->
                    val inp = client.inputStream
                    val hi = inp.read(); val lo = inp.read()
                    if (hi < 0 || lo < 0) return@use
                    val len = (hi shl 8) or lo
                    val req = ByteArray(len)
                    var off = 0
                    while (off < len) off += inp.read(req, off, len - off)

                    val replyBytes = if (req.size >= 52 && req.copyOfRange(0, 8).contentEquals(clientMagic)) {
                        encryptedQueries.incrementAndGet()
                        val cPk = req.copyOfRange(8, 40)
                        val cNonceHalf = req.copyOfRange(40, 52)
                        val qNonce = ByteArray(24).also { System.arraycopy(cNonceHalf, 0, it, 0, 12) }
                        val shared = DnscryptCrypto.x25519Shared(serverKp.first, cPk)
                        val key = DnscryptCrypto.hchacha20(shared, ByteArray(16))
                        val padded = DnscryptCrypto.xchachaOpen(req.copyOfRange(52, req.size), key, qNonce)!!
                        var end = padded.size
                        while (end > 0 && padded[end - 1].toInt() == 0) end--
                        val rawDns = padded.copyOf(end - 1)
                        val dnsAns = Dns.buildResponseWithA(rawDns, rawDns.size, listOf("1.0.0.1"))
                        val plainResp = ByteArray(dnsAns.size + 1).also {
                            System.arraycopy(dnsAns, 0, it, 0, dnsAns.size)
                            it[dnsAns.size] = 0x80.toByte()
                        }
                        val rNonce = ByteArray(24).also {
                            System.arraycopy(cNonceHalf, 0, it, 0, 12)
                            it[12] = 0x55
                        }
                        val boxed = DnscryptCrypto.xchachaSeal(plainResp, key, rNonce)
                        resolverMagic + rNonce + boxed
                    } else {
                        certFetches.incrementAndGet()
                        val qMsg = Dns.parse(req)
                        val out = ByteArray(512)
                        var o = 0
                        o = Dns.putU16(out, o, qMsg.id)
                        o = Dns.putU16(out, o, 0x8180)
                        o = Dns.putU16(out, o, 1)
                        o = Dns.putU16(out, o, 1)
                        o = Dns.putU16(out, o, 0)
                        o = Dns.putU16(out, o, 0)
                        o = Dns.putName(out, o, qMsg.question!!.name)
                        o = Dns.putU16(out, o, Dns.TYPE_TXT)
                        o = Dns.putU16(out, o, 1)
                        o = Dns.putU16(out, o, 0xC00C)
                        o = Dns.putU16(out, o, Dns.TYPE_TXT)
                        o = Dns.putU16(out, o, 1)
                        out[o] = 0; out[o + 1] = 0; out[o + 2] = 14; out[o + 3] = 16; o += 4
                        o = Dns.putU16(out, o, certBytes.size + 1)
                        out[o++] = certBytes.size.toByte()
                        System.arraycopy(certBytes, 0, out, o, certBytes.size)
                        o += certBytes.size
                        out.copyOf(o)
                    }

                    val out = client.outputStream
                    out.write(byteArrayOf((replyBytes.size shr 8 and 0xFF).toByte(), (replyBytes.size and 0xFF).toByte()))
                    out.write(replyBytes)
                    out.flush()
                }
            }
        }.apply { isDaemon = true; start() }

        // ساخت sdns:// برای 127.0.0.1:<port>
        val stampBuf = ByteArrayOutputStream().apply {
            write(0x01)
            write(ByteArray(8))
            val addr = "127.0.0.1:$port".toByteArray()
            write(addr.size); write(addr)
            write(providerPk.size); write(providerPk)
            val name = "2.dnscrypt-cert.local.test".toByteArray()
            write(name.size); write(name)
        }.toByteArray()
        val sdns = "sdns://" + Base64.getUrlEncoder().withoutPadding().encodeToString(stampBuf)

        try {
            val resolver = DnscryptResolver(sdns, timeoutMs = 400)
            val c1 = resolver.fetchCert()
            val c2 = resolver.fetchCert()
            assertSame("گواهی DNSCrypt باید در حافظه کش شود و دوباره واکشی نشود!", c1, c2)

            val r1 = Dns.parse(resolver.resolve(Dns.buildQuery(1, "a.example.com", Dns.TYPE_A)))
            val r2 = Dns.parse(resolver.resolve(Dns.buildQuery(2, "b.example.com", Dns.TYPE_A)))
            assertEquals(listOf("1.0.0.1"), r1.addresses)
            assertEquals(listOf("1.0.0.1"), r2.addresses)
            assertEquals("گواهی فقط یک‌بار باید از شبکه گرفته شود!", 1, certFetches.get())
            assertEquals("هر دو پرس‌وجو باید رمزنگاری‌شده روی TCP پاسخ بگیرند!", 2, encryptedQueries.get())
            resolver.close()
        } finally {
            runCatching { tcpServer.close() }
            serverThread.join(500)
        }
    }

    @Test
    fun `allowList covers subdomains and ipv6 sinkhole and rewrites work`() {
        val upstream = object : UpstreamResolver {
            override val label = "fake"
            override fun resolve(query: ByteArray) =
                Dns.buildResponseWithA(query, query.size, listOf("93.184.216.34"))
            override fun close() {}
        }
        val stub = DnsStub(
            upstream = upstream,
            blockList = BlockList(listOf("example.com", "ads.net")),
            blockEnabled = true,
            allowList = setOf("example.com"),
            rewriteMap = mapOf("v6.local" to "2001:db8::42"),
            sinkholeEnabled = true
        )

        // زیردامنهٔ دامنهٔ استثناشده نباید مسدود شود!
        val sub = Dns.buildQuery(10, "api.sub.example.com", Dns.TYPE_A)
        val subRes = stub.handle(sub, sub.size)
        assertFalse(subRes.blocked)
        assertEquals(listOf("93.184.216.34"), Dns.parse(subRes.packet).addresses)

        // Sinkhole روی AAAA باید پاسخ «::» برگرداند
        val v6Blocked = Dns.buildQuery(11, "track.ads.net", Dns.TYPE_AAAA)
        val v6BlockedRes = stub.handle(v6Blocked, v6Blocked.size)
        assertTrue(v6BlockedRes.blocked)
        assertEquals(Dns.RCODE_NOERROR, Dns.parse(v6BlockedRes.packet).rcode)
        assertNotNull(Dns.parse(v6BlockedRes.packet).addresses.firstOrNull())

        // بازنویسی IPv6 روی دامنهٔ محلی و زیردامنهٔ آن
        val v6Rw = Dns.buildQuery(12, "node1.v6.local", Dns.TYPE_AAAA)
        val v6RwRes = stub.handle(v6Rw, v6Rw.size)
        assertFalse(v6RwRes.blocked)
        assertTrue(Dns.parse(v6RwRes.packet).addresses.first().contains("2001:db8"))
    }

    @Test
    fun `dns label sanitizer strips xss payload characters from wire packets`() {
        val evil = Dns.buildQuery(0x99, "bad<img/src=x/onerror=alert(1)>.example.com", Dns.TYPE_A)
        val parsed = Dns.parse(evil)
        val qName = parsed.question!!.name
        assertFalse(qName.contains('<'))
        assertFalse(qName.contains('>'))
        assertFalse(qName.contains('"'))
        assertFalse(qName.contains('\''))
    }

    @Test
    fun `backup preserves log toggle and censorship detector catches loopback and zero ip`() {
        val back = SettingsBackup.import(
            SettingsBackup.export(BackupData(toggles = mapOf("log" to false, "block" to true)))
        )
        assertEquals(false, back.toggles["log"])
        assertTrue(HealthCheck.isCensorshipIp("10.10.34.35"))
        assertTrue(HealthCheck.isCensorshipIp("0.0.0.0"))
        assertTrue(HealthCheck.isCensorshipIp("127.0.0.1"))
        assertTrue(HealthCheck.isCensorshipIp("::"))
        assertFalse(HealthCheck.isCensorshipIp("10.202.10.10"))
    }

    @Test
    fun `live cloudflare DoT succeeds immediately when bootstrapIps 1_1_1_1 provided`() {
        val dot = DotResolver(
            hostname = "cloudflare-dns.com",
            bootstrapIps = listOf("1.1.1.1", "1.0.0.1"),
            timeoutMs = 4_000
        )
        try {
            val q = Dns.buildQuery(0x77, "example.com", Dns.TYPE_A)
            val ans = try {
                Dns.parse(dot.resolve(q))
            } catch (e: Exception) {
                assumeNoException("پورت ۸۵۳ در محیط تست بسته است", e)
                return
            }
            assertEquals(Dns.RCODE_NOERROR, ans.rcode)
            assertTrue(ans.addresses.isNotEmpty())
        } finally {
            dot.close()
        }
    }
}
