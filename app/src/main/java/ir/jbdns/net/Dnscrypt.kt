package ir.jbdns.net

import ir.jbdns.core.Dns
import org.bouncycastle.crypto.engines.XSalsa20Engine
import org.bouncycastle.crypto.macs.Poly1305
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import org.bouncycastle.math.ec.rfc7748.X25519
import org.bouncycastle.math.ec.rfc8032.Ed25519
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom

/**
 * DNSCrypt v2 — پیاده‌سازی کامل پروتکل (RFC پیش‌نویس dnscrypt) بدون کتابخانهٔ بومی،
 * همراه با کش واقعی گواهی سرور + کلید مشترک پیش‌محاسبه‌شده و بازگشت خودکار به TCP
 * (برای شبکه‌هایی مثل ISPهای ایران که UDP پورت‌های غیرمتعارف را مسدود یا محدود می‌کنند).
 */
object DnscryptCrypto {

    /** nonce کمکی ChaCha20 در XChaCha20-Poly1305: چهار بایت صفر + هشت بایت آخر nonce اصلی */
    private fun xchachaTailNonce(nonce24: ByteArray): ByteArray {
        val n = ByteArray(12)
        nonce24.copyOfRange(16, 24).copyInto(n, 4)
        return n
    }

    /** ثابت‌های Salsa20 («expand 32-byte k»). */
    private val SIGMA = intArrayOf(
        0x61707865, 0x3320646e, 0x79622d32, 0x6b206574
    )

    private fun le32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun putLe32(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = ((v ushr 8) and 0xFF).toByte()
        b[off + 2] = ((v ushr 16) and 0xFF).toByte()
        b[off + 3] = ((v ushr 24) and 0xFF).toByte()
    }

    private fun rotl(v: Int, c: Int): Int = (v shl c) or (v ushr (32 - c))

    /** ۲۰ دور Salsa20 روی state (۱۰ دور دوتایی). */
    private fun salsaRounds(x: IntArray) {
        for (i in 0 until 10) {
            x[4] = x[4] xor rotl(x[0] + x[12], 7); x[8] = x[8] xor rotl(x[4] + x[0], 9)
            x[12] = x[12] xor rotl(x[8] + x[4], 13); x[0] = x[0] xor rotl(x[12] + x[8], 18)
            x[9] = x[9] xor rotl(x[5] + x[1], 7); x[13] = x[13] xor rotl(x[9] + x[5], 9)
            x[1] = x[1] xor rotl(x[13] + x[9], 13); x[5] = x[5] xor rotl(x[1] + x[13], 18)
            x[14] = x[14] xor rotl(x[10] + x[6], 7); x[2] = x[2] xor rotl(x[14] + x[10], 9)
            x[6] = x[6] xor rotl(x[2] + x[14], 13); x[10] = x[10] xor rotl(x[6] + x[2], 18)
            x[3] = x[3] xor rotl(x[15] + x[11], 7); x[7] = x[7] xor rotl(x[3] + x[15], 9)
            x[11] = x[11] xor rotl(x[7] + x[3], 13); x[15] = x[15] xor rotl(x[11] + x[7], 18)
            x[1] = x[1] xor rotl(x[0] + x[3], 7); x[2] = x[2] xor rotl(x[1] + x[0], 9)
            x[3] = x[3] xor rotl(x[2] + x[1], 13); x[0] = x[0] xor rotl(x[3] + x[2], 18)
            x[6] = x[6] xor rotl(x[5] + x[4], 7); x[7] = x[7] xor rotl(x[6] + x[5], 9)
            x[4] = x[4] xor rotl(x[7] + x[6], 13); x[5] = x[5] xor rotl(x[4] + x[7], 18)
            x[11] = x[11] xor rotl(x[10] + x[9], 7); x[8] = x[8] xor rotl(x[11] + x[10], 9)
            x[9] = x[9] xor rotl(x[8] + x[11], 13); x[10] = x[10] xor rotl(x[9] + x[8], 18)
            x[12] = x[12] xor rotl(x[15] + x[14], 7); x[13] = x[13] xor rotl(x[12] + x[15], 9)
            x[14] = x[14] xor rotl(x[13] + x[12], 13); x[15] = x[15] xor rotl(x[14] + x[13], 18)
        }
    }

    /** crypto_core_hsalsa20 — ثابت‌ها در گوشه‌های ماتریس؛ خروجی: اندیس‌های ۰،۵،۱۰،۱۵،۶،۷،۸،۹. */
    fun hsalsa20(key: ByteArray, in16: ByteArray): ByteArray {
        val x = IntArray(16)
        x[0] = SIGMA[0]; x[5] = SIGMA[1]; x[10] = SIGMA[2]; x[15] = SIGMA[3]
        for (i in 0 until 4) {
            x[1 + i] = le32(key, i * 4)
            x[11 + i] = le32(key, 16 + i * 4)
            x[6 + i] = le32(in16, i * 4)
        }
        salsaRounds(x)
        val out = ByteArray(32)
        val idx = intArrayOf(0, 5, 10, 15, 6, 7, 8, 9)
        for (i in 0 until 8) putLe32(out, i * 4, x[idx[i]])
        return out
    }

    /** ۲۰ دور ChaCha20 (۱۰ دور دوتایی). */
    private fun chachaRounds(x: IntArray) {
        for (i in 0 until 10) {
            x[0] += x[4]; x[12] = rotl(x[12] xor x[0], 16); x[8] += x[12]; x[4] = rotl(x[4] xor x[8], 12)
            x[0] += x[4]; x[12] = rotl(x[12] xor x[0], 8); x[8] += x[12]; x[4] = rotl(x[4] xor x[8], 7)
            x[1] += x[5]; x[13] = rotl(x[13] xor x[1], 16); x[9] += x[13]; x[5] = rotl(x[5] xor x[9], 12)
            x[1] += x[5]; x[13] = rotl(x[13] xor x[1], 8); x[9] += x[13]; x[5] = rotl(x[5] xor x[9], 7)
            x[2] += x[6]; x[14] = rotl(x[14] xor x[2], 16); x[10] += x[14]; x[6] = rotl(x[6] xor x[10], 12)
            x[2] += x[6]; x[14] = rotl(x[14] xor x[2], 8); x[10] += x[14]; x[6] = rotl(x[6] xor x[10], 7)
            x[3] += x[7]; x[15] = rotl(x[15] xor x[3], 16); x[11] += x[15]; x[7] = rotl(x[7] xor x[11], 12)
            x[3] += x[7]; x[15] = rotl(x[15] xor x[3], 8); x[11] += x[15]; x[7] = rotl(x[7] xor x[11], 7)
            x[0] += x[5]; x[15] = rotl(x[15] xor x[0], 16); x[10] += x[15]; x[5] = rotl(x[5] xor x[10], 12)
            x[0] += x[5]; x[15] = rotl(x[15] xor x[0], 8); x[10] += x[15]; x[5] = rotl(x[5] xor x[10], 7)
            x[1] += x[6]; x[12] = rotl(x[12] xor x[1], 16); x[11] += x[12]; x[6] = rotl(x[6] xor x[11], 12)
            x[1] += x[6]; x[12] = rotl(x[12] xor x[1], 8); x[11] += x[12]; x[6] = rotl(x[6] xor x[11], 7)
            x[2] += x[7]; x[13] = rotl(x[13] xor x[2], 16); x[8] += x[13]; x[7] = rotl(x[7] xor x[8], 12)
            x[2] += x[7]; x[13] = rotl(x[13] xor x[2], 8); x[8] += x[13]; x[7] = rotl(x[7] xor x[8], 7)
            x[3] += x[4]; x[14] = rotl(x[14] xor x[3], 16); x[9] += x[14]; x[4] = rotl(x[4] xor x[9], 12)
            x[3] += x[4]; x[14] = rotl(x[14] xor x[3], 8); x[9] += x[14]; x[4] = rotl(x[4] xor x[9], 7)
        }
    }

    /** یک بلوک ۶۴ بایتی ChaCha20 استاندارد (با جمع state اولیه). */
    private fun chacha20Block(key: ByteArray, nonce12: ByteArray, counter: Int): ByteArray {
        val x = IntArray(16)
        for (i in 0 until 4) x[i] = SIGMA[i]
        for (i in 0 until 8) x[4 + i] = le32(key, i * 4)
        x[12] = counter
        for (i in 0 until 3) x[13 + i] = le32(nonce12, i * 4)
        val init = x.copyOf()
        chachaRounds(x)
        val out = ByteArray(64)
        for (i in 0 until 16) putLe32(out, i * 4, x[i] + init[i])
        return out
    }

    /** crypto_core_hchacha20 — خروجی: x0..x3 و x12..x15 (بدون جمع با ورودی). */
    fun hchacha20(key: ByteArray, in16: ByteArray): ByteArray {
        val x = IntArray(16)
        for (i in 0 until 4) x[i] = SIGMA[i]
        for (i in 0 until 4) x[4 + i] = le32(key, i * 4)
        for (i in 0 until 4) x[8 + i] = le32(key, 16 + i * 4)
        for (i in 0 until 4) x[12 + i] = le32(in16, i * 4)
        chachaRounds(x)
        val out = ByteArray(32)
        for (i in 0 until 4) putLe32(out, i * 4, x[i])
        for (i in 0 until 4) putLe32(out, 16 + i * 4, x[12 + i])
        return out
    }

    /** crypto_secretbox (XSalsa20-Poly1305): خروجی = mac(16) || ciphertext. */
    fun secretboxSeal(plaintext: ByteArray, key: ByteArray, nonce24: ByteArray): ByteArray {
        val engine = XSalsa20Engine()
        engine.init(true, ParametersWithIV(KeyParameter(key), nonce24))
        val stream = ByteArray(32 + plaintext.size)
        engine.processBytes(stream, 0, stream.size, stream, 0)
        val ct = ByteArray(plaintext.size)
        for (i in plaintext.indices) ct[i] = (plaintext[i].toInt() xor stream[32 + i].toInt()).toByte()
        val mac = Poly1305().apply { init(KeyParameter(stream, 0, 32)) }
        mac.update(ct, 0, ct.size)
        val out = ByteArray(16 + ct.size)
        mac.doFinal(out, 0)
        System.arraycopy(ct, 0, out, 16, ct.size)
        return out
    }

    /** بازکردن crypto_secretbox؛ تهی یعنی MAC معتبر نبود. */
    fun secretboxOpen(boxed: ByteArray, key: ByteArray, nonce24: ByteArray): ByteArray? {
        if (boxed.size < 16) return null
        val mac = boxed.copyOfRange(0, 16)
        val ct = boxed.copyOfRange(16, boxed.size)
        val engine = XSalsa20Engine()
        engine.init(false, ParametersWithIV(KeyParameter(key), nonce24))
        val stream = ByteArray(32 + ct.size)
        engine.processBytes(stream, 0, stream.size, stream, 0)
        val check = Poly1305().apply { init(KeyParameter(stream, 0, 32)) }
        check.update(ct, 0, ct.size)
        val expect = ByteArray(16)
        check.doFinal(expect, 0)
        if (!java.security.MessageDigest.isEqual(mac, expect)) return null
        val pt = ByteArray(ct.size)
        for (i in ct.indices) pt[i] = (ct[i].toInt() xor stream[32 + i].toInt()).toByte()
        return pt
    }

    fun xchachaSeal(plaintext: ByteArray, key: ByteArray, nonce24: ByteArray): ByteArray {
        val subkey = hchacha20(key, nonce24.copyOfRange(0, 16))
        val nonce12 = xchachaTailNonce(nonce24)
        val block0 = chacha20Block(subkey, nonce12, 0)
        val ct = ByteArray(plaintext.size)
        val n = minOf(32, plaintext.size)
        for (i in 0 until n) ct[i] = (plaintext[i].toInt() xor block0[32 + i].toInt()).toByte()
        var counter = 1
        var off = n
        while (off < plaintext.size) {
            val blk = chacha20Block(subkey, nonce12, counter)
            val m = minOf(64, plaintext.size - off)
            for (i in 0 until m) ct[off + i] = (plaintext[off + i].toInt() xor blk[i].toInt()).toByte()
            counter++; off += m
        }
        val poly = Poly1305()
        poly.init(KeyParameter(block0, 0, 32))
        poly.update(ct, 0, ct.size)
        val tag = ByteArray(16)
        poly.doFinal(tag, 0)
        return tag + ct
    }

    /** بازکردن secretbox_xchacha20poly1305 (فرمت es=2 در DNSCrypt: MAC جلو). */
    fun xchachaOpen(boxed: ByteArray, key: ByteArray, nonce24: ByteArray): ByteArray? {
        if (boxed.size < 16) return null
        val tag = boxed.copyOfRange(0, 16)
        val ct = boxed.copyOfRange(16, boxed.size)
        val subkey = hchacha20(key, nonce24.copyOfRange(0, 16))
        val nonce12 = xchachaTailNonce(nonce24)
        val block0 = chacha20Block(subkey, nonce12, 0)
        val poly = Poly1305()
        poly.init(KeyParameter(block0, 0, 32))
        poly.update(ct, 0, ct.size)
        val expect = ByteArray(16)
        poly.doFinal(expect, 0)
        if (!java.security.MessageDigest.isEqual(tag, expect)) return null
        val pt = ByteArray(ct.size)
        val n = minOf(32, ct.size)
        for (i in 0 until n) pt[i] = (ct[i].toInt() xor block0[32 + i].toInt()).toByte()
        var counter = 1
        var off = n
        while (off < ct.size) {
            val blk = chacha20Block(subkey, nonce12, counter)
            val m = minOf(64, ct.size - off)
            for (i in 0 until m) pt[off + i] = (ct[off + i].toInt() xor blk[i].toInt()).toByte()
            counter++; off += m
        }
        return pt
    }

    /** تولید کلید خصوصی X25519 (با clamp استاندارد). */
    fun newX25519KeyPair(random: SecureRandom): Pair<ByteArray, ByteArray> {
        val sk = ByteArray(32)
        random.nextBytes(sk)
        sk[0] = (sk[0].toInt() and 248).toByte()
        sk[31] = ((sk[31].toInt() and 127) or 64).toByte()
        val pk = ByteArray(32)
        X25519.generatePublicKey(sk, 0, pk, 0)
        return sk to pk
    }

    /** توافق کلید X25519. */
    fun x25519Shared(sk: ByteArray, peerPk: ByteArray): ByteArray {
        val out = ByteArray(32)
        if (!X25519.calculateAgreement(sk, 0, peerPk, 0, out, 0)) {
            throw IOException("X25519: نقطهٔ نامعتبر")
        }
        return out
    }

    /** تأیید امضای Ed25519. */
    fun ed25519Verify(providerPk: ByteArray, message: ByteArray, sig: ByteArray): Boolean = try {
        Ed25519.verify(sig, 0, providerPk, 0, message, 0, message.size)
    } catch (_: Exception) {
        false
    }
}

/** نشانی رمزگذاری‌شدهٔ DNS Stamp (طرح sdns:// برای پروتکل DNSCrypt). */
class DnsStamp(
    val address: String,
    val providerPk: ByteArray,
    val providerName: String
) {
    companion object {
        fun parse(stamp: String): DnsStamp? {
            val s = stamp.trim()
            if (!s.startsWith("sdns://")) return null
            val b64 = s.removePrefix("sdns://")
            val b = try {
                android.util.Base64.decode(b64, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
            } catch (_: Exception) {
                null
            } ?: return null
            return decodeStampBytes(b)
        }

        fun parseJvm(stamp: String): DnsStamp? {
            val s = stamp.trim()
            if (!s.startsWith("sdns://")) return null
            val b = try {
                java.util.Base64.getUrlDecoder().decode(s.removePrefix("sdns://"))
            } catch (_: Exception) {
                null
            } ?: return null
            return decodeStampBytes(b)
        }

        private fun decodeStampBytes(b: ByteArray): DnsStamp? {
            if (b.size < 11 || b[0] != 0x01.toByte()) return null
            var off = 9
            fun lp(): ByteArray? {
                if (off >= b.size) return null
                val len = b[off].toInt() and 0xFF
                if (off + 1 + len > b.size) return null
                val v = b.copyOfRange(off + 1, off + 1 + len)
                off += 1 + len
                return v
            }
            val addr = lp()?.toString(Charsets.UTF_8) ?: return null
            val pk = lp() ?: return null
            if (pk.size != 32) return null
            val name = lp()?.toString(Charsets.UTF_8) ?: return null
            if (addr.isEmpty() || name.isEmpty()) return null
            return DnsStamp(addr, pk, name)
        }
    }
}

/** گواهی رمزنگاری سرور + کلید مشترک پیش‌محاسبه‌شدهٔ مسیر. */
class DnscryptCert(
    val esVersion: Int,
    val resolverPk: ByteArray,
    val clientMagic: ByteArray,
    val serial: Int,
    val tsEnd: Long,
    val sharedKey: ByteArray
)

/**
 * ریزالور DNSCrypt با کش واقعی گواهی سرور و بازگشت خودکار از UDP به TCP
 * (چه هنگام قطع بودن UDP روی ISP و چه هنگام پاسخ کوتاه‌شدهٔ TC=1).
 */
class DnscryptResolver(
    stampText: String,
    private val timeoutMs: Int = 8_000,
    private val protectDatagram: (DatagramSocket) -> Unit = {},
    private val protectSocket: (Socket) -> Unit = {}
) : UpstreamResolver {

    override val label: String get() = "DNSCrypt"

    private val stamp: DnsStamp = DnsStamp.parse(stampText) ?: DnsStamp.parseJvm(stampText)
        ?: throw IOException("نشانی sdns نامعتبر است")

    private val random = SecureRandom()
    private val clientKey = DnscryptCrypto.newX25519KeyPair(random)
    @Volatile private var cert: DnscryptCert? = null
    @Volatile private var preferTcp: Boolean = false

    private fun hostPort(): Pair<String, Int> {
        val idx = stamp.address.lastIndexOf(':')
        if (idx <= 0) throw IOException("نشانی بدون پورت: ${stamp.address}")
        val host = stamp.address.substring(0, idx).removePrefix("[").removeSuffix("]")
        val port = stamp.address.substring(idx + 1).toIntOrNull() ?: 443
        return host to port
    }

    /** ارسال و دریافت یک بستهٔ خام روی UDP یا TCP (با فریم‌بندی ۲ بایتی استاندارد DNSCrypt/TCP). */
    internal fun exchangePacket(host: String, port: Int, payload: ByteArray, forceTcp: Boolean = false): ByteArray {
        if (!forceTcp && !preferTcp) {
            try {
                return sendUdp(host, port, payload)
            } catch (_: IOException) {
                // UDP در بسیاری از شبکه‌ها بسته است؛ بی‌درنگ روی TCP همان پورت امتحان می‌کنیم
                val tcpReply = sendTcp(host, port, payload)
                preferTcp = true
                return tcpReply
            }
        }
        return try {
            sendTcp(host, port, payload)
        } catch (e: IOException) {
            if (!forceTcp) {
                preferTcp = false
                sendUdp(host, port, payload)
            } else throw e
        }
    }

    private fun sendUdp(host: String, port: Int, payload: ByteArray): ByteArray {
        DatagramSocket().use { sock ->
            runCatching { protectDatagram(sock) }
            sock.soTimeout = timeoutMs
            sock.send(DatagramPacket(payload, payload.size, InetSocketAddress(host, port)))
            val buf = ByteArray(4096)
            val reply = DatagramPacket(buf, buf.size)
            sock.receive(reply)
            return buf.copyOf(reply.length)
        }
    }

    private fun sendTcp(host: String, port: Int, payload: ByteArray): ByteArray {
        Socket().use { sock ->
            runCatching { protectSocket(sock) }
            sock.tcpNoDelay = true
            sock.soTimeout = timeoutMs
            sock.connect(InetSocketAddress(host, port), timeoutMs)
            val out = sock.outputStream
            out.write(byteArrayOf((payload.size shr 8 and 0xFF).toByte(), (payload.size and 0xFF).toByte()))
            out.write(payload)
            out.flush()
            val input = sock.inputStream
            val hi = input.read()
            val lo = input.read()
            if (hi < 0 || lo < 0) throw IOException("DNSCrypt/TCP: اتصال بسته شد")
            val len = (hi shl 8) or lo
            if (len < Dns.HEADER_SIZE || len > 65535) throw IOException("DNSCrypt/TCP: طول نامعتبر $len")
            val buf = ByteArray(len)
            var off = 0
            while (off < len) {
                val n = input.read(buf, off, len - off)
                if (n < 0) throw IOException("DNSCrypt/TCP: پاسخ ناقص")
                off += n
            }
            return buf
        }
    }

    /** واکشی، تأیید امضای Ed25519 و ذخیرهٔ واقعی بهترین گواهی سرور در حافظه! */
    @Synchronized
    internal fun fetchCert(): DnscryptCert {
        val nowSec = System.currentTimeMillis() / 1000L
        cert?.let { c ->
            if (nowSec + 60 < c.tsEnd) return c
        }

        val (host, port) = hostPort()
        val q = Dns.buildQuery(0x5345, stamp.providerName, Dns.TYPE_TXT, qclass = 1)
        val response = exchangePacket(host, port, q)
        val msg = runCatching { Dns.parse(response) }.getOrNull()
            ?: throw IOException("DNSCrypt: پاسخ گواهی ناخوانا بود")

        var best: DnscryptCert? = null
        for (answer in msg.answers) {
            if (answer.type != Dns.TYPE_TXT) continue
            val rdata = unpackTxt(answer.rdata) ?: continue
            if (rdata.size < 124) continue
            if (rdata[0] != 0x44.toByte() || rdata[1] != 0x4E.toByte() ||
                rdata[2] != 0x53.toByte() || rdata[3] != 0x43.toByte()
            ) continue
            val esVersion = ((rdata[4].toInt() and 0xFF) shl 8) or (rdata[5].toInt() and 0xFF)
            if (esVersion != 1 && esVersion != 2) continue
            val sig = rdata.copyOfRange(8, 72)
            val signed = rdata.copyOfRange(72, rdata.size)
            if (!DnscryptCrypto.ed25519Verify(stamp.providerPk, signed, sig)) continue
            val resolverPk = rdata.copyOfRange(72, 104)
            val clientMagic = rdata.copyOfRange(104, 112)
            val serial = ((rdata[112].toInt() and 0xFF) shl 24) or ((rdata[113].toInt() and 0xFF) shl 16) or
                ((rdata[114].toInt() and 0xFF) shl 8) or (rdata[115].toInt() and 0xFF)
            val tsStart = ((rdata[116].toLong() and 0xFF) shl 24) or ((rdata[117].toLong() and 0xFF) shl 16) or
                ((rdata[118].toLong() and 0xFF) shl 8) or (rdata[119].toLong() and 0xFF)
            val tsEnd = ((rdata[120].toLong() and 0xFF) shl 24) or ((rdata[121].toLong() and 0xFF) shl 16) or
                ((rdata[122].toLong() and 0xFF) shl 8) or (rdata[123].toLong() and 0xFF)
            if (nowSec < tsStart || nowSec > tsEnd) continue
            val shared = DnscryptCrypto.x25519Shared(clientKey.first, resolverPk)
            val derivedKey = if (esVersion == 2) {
                DnscryptCrypto.hchacha20(shared, ByteArray(16))
            } else {
                DnscryptCrypto.hsalsa20(shared, ByteArray(16))
            }
            val candidate = DnscryptCert(esVersion, resolverPk, clientMagic, serial, tsEnd, derivedKey)
            val currentBest = best
            if (currentBest == null || candidate.serial > currentBest.serial) {
                best = candidate
            }
        }
        val chosen = best ?: throw IOException("DNSCrypt: گواهی معتبری از سرور نگرفتیم")
        cert = chosen
        return chosen
    }

    override fun resolve(query: ByteArray): ByteArray {
        val c = fetchCert()
        val dns = sendEncryptedQuery(c, query, forceTcp = false)
        // اگر پاسخ روی UDP کوتاه شده بود (TC=1)، طبق مشخصات DNSCrypt v2 روی TCP تکرار می‌شود
        if (!preferTcp && runCatching { Dns.parse(dns).isTruncated }.getOrDefault(false)) {
            return runCatching { sendEncryptedQuery(c, query, forceTcp = true) }.getOrDefault(dns)
        }
        return dns
    }

    private fun sendEncryptedQuery(c: DnscryptCert, query: ByteArray, forceTcp: Boolean): ByteArray {
        val key = c.sharedKey
        val queryOverhead = 8 + 32 + 12 + 16
        var totalLen = maxOf(512, queryOverhead + query.size)
        totalLen = (totalLen + 1 + 63) / 64 * 64
        var paddedLen = totalLen - queryOverhead
        val rem = paddedLen % 64
        if (rem != 0) paddedLen += 64 - rem
        val padded = ByteArray(paddedLen)
        System.arraycopy(query, 0, padded, 0, query.size)
        padded[query.size] = 0x80.toByte()

        val nonce = ByteArray(24)
        val half = ByteArray(12)
        random.nextBytes(half)
        System.arraycopy(half, 0, nonce, 0, 12)

        val encrypted = if (c.esVersion == 2) {
            DnscryptCrypto.xchachaSeal(padded, key, nonce)
        } else {
            DnscryptCrypto.secretboxSeal(padded, key, nonce)
        }

        val packet = ByteArray(8 + 32 + 12 + encrypted.size)
        System.arraycopy(c.clientMagic, 0, packet, 0, 8)
        System.arraycopy(clientKey.second, 0, packet, 8, 32)
        System.arraycopy(nonce, 0, packet, 40, 12)
        System.arraycopy(encrypted, 0, packet, 52, encrypted.size)

        val (host, port) = hostPort()
        val response = try {
            exchangePacket(host, port, packet, forceTcp = forceTcp)
        } catch (e: IOException) {
            cert = null
            throw e
        }

        if (response.size < 8 + 24 + 16) throw IOException("DNSCrypt: پاسخ کوتاه")
        for (i in 0 until 8) {
            if (response[i] != RESOLVER_MAGIC[i]) throw IOException("DNSCrypt: پاسخ ناهمخوان")
        }
        for (i in 0 until 12) {
            if (response[8 + i] != nonce[i]) throw IOException("DNSCrypt: نانس پاسخ نامعتبر")
        }
        val respNonce = response.copyOfRange(8, 32)
        val boxed = response.copyOfRange(32, response.size)
        val plain = (if (c.esVersion == 2) {
            DnscryptCrypto.xchachaOpen(boxed, key, respNonce)
        } else {
            DnscryptCrypto.secretboxOpen(boxed, key, respNonce)
        }) ?: throw IOException("DNSCrypt: تأیید MAC پاسخ ناموفق")

        var end = plain.size
        while (end > 0 && plain[end - 1].toInt() == 0) end--
        if (end == 0 || plain[end - 1] != 0x80.toByte()) throw IOException("DNSCrypt: پدینگ نامعتبر")
        val dns = plain.copyOf(end - 1)
        if (dns.size < Dns.HEADER_SIZE) throw IOException("DNSCrypt: پاسخ DNS خالی")
        return dns
    }

    @Synchronized
    override fun close() {
        cert = null
    }

    companion object {
        private val RESOLVER_MAGIC = byteArrayOf(
            0x72, 0x36, 0x66, 0x6e, 0x76, 0x57, 0x6a, 0x38
        )

        internal fun unpackTxt(rdata: ByteArray): ByteArray? {
            val out = java.io.ByteArrayOutputStream()
            var i = 0
            while (i < rdata.size) {
                val len = rdata[i].toInt() and 0xFF
                if (i + 1 + len > rdata.size) return null
                out.write(rdata, i + 1, len)
                i += 1 + len
            }
            return out.toByteArray()
        }
    }
}
