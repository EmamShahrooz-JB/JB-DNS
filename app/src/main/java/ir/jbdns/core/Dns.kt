package ir.jbdns.core

import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * کدک خام DNS (RFC 1035) — کاملاً خالص و بدون وابستگی به اندروید،
 * تا بتوان آن را روی JVM تست واقعی گرفت.
 */
object Dns {

    const val TYPE_A = 1
    const val TYPE_NS = 2
    const val TYPE_CNAME = 5
    const val TYPE_SOA = 6
    const val TYPE_PTR = 12
    const val TYPE_MX = 15
    const val TYPE_TXT = 16
    const val TYPE_AAAA = 28
    const val TYPE_SRV = 33
    const val TYPE_HTTPS = 65
    const val TYPE_SVCB = 64
    const val TYPE_ANY = 255

    const val RCODE_NOERROR = 0
    const val RCODE_FORMERR = 1
    const val RCODE_SERVFAIL = 2
    const val RCODE_NXDOMAIN = 3
    const val RCODE_NOTIMP = 4
    const val RCODE_REFUSED = 5

    const val HEADER_SIZE = 12

    class Question(val name: String, val type: Int, val qclass: Int = 1)

    /** یک رکورد پاسخ. [data] برای A/AAAA آدرس متنی است و برای بقیه‌ها مقدار خام. */
    class Answer(
        val name: String,
        val type: Int,
        val ttl: Long,
        val rdata: ByteArray,
        val pretty: String
    )

    class Message(
        val id: Int,
        val flags: Int,
        val question: Question?,
        val answers: List<Answer>,
        val authorityCount: Int,
        val additionalCount: Int
    ) {
        val rcode: Int get() = flags and 0xF
        val isResponse: Boolean get() = (flags shr 15) and 1 == 1
        val addresses: List<String>
            get() = answers.filter { it.type == TYPE_A || it.type == TYPE_AAAA }.map { it.pretty }
    }

    // ---------------------------------------------------------------- parse

    fun parse(buf: ByteArray, len: Int = buf.size): Message {
        require(len >= HEADER_SIZE) { "بستهٔ DNS کوتاه‌تر از ۱۲ بایت است" }
        val id = u16(buf, 0)
        val flags = u16(buf, 2)
        val qd = u16(buf, 4)
        val an = u16(buf, 6)
        val ns = u16(buf, 8)
        val ar = u16(buf, 10)

        var off = HEADER_SIZE
        var question: Question? = null
        for (i in 0 until qd) {
            val r = readName(buf, off, len)
            off = r.second
            if (off + 4 > len) break
            val type = u16(buf, off)
            val qclass = u16(buf, off + 2)
            off += 4
            if (question == null) question = Question(r.first, type, qclass)
        }

        val answers = ArrayList<Answer>(an.coerceAtMost(64))
        for (i in 0 until an) {
            val start = off
            val r = runCatching { readName(buf, off, len) }.getOrNull() ?: break
            off = r.second
            if (off + 10 > len) { off = start; break }
            val type = u16(buf, off)
            val ttl = u32(buf, off + 4)
            val rdlen = u16(buf, off + 8)
            off += 10
            if (off + rdlen > len) break
            val rdata = buf.copyOfRange(off, off + rdlen)
            off += rdlen
            answers.add(Answer(r.first, type, ttl, rdata, pretty(type, rdata, buf, off - rdlen, len)))
        }

        return Message(id, flags, question, answers, ns, ar)
    }

    // ---------------------------------------------------------------- build

    /** ساخت یک پرس‌وجوی استاندارد با بازگشت‌پذیری (RD=1). */
    fun buildQuery(id: Int, name: String, type: Int, qclass: Int = 1): ByteArray {
        val out = ByteArray(512)
        var o = 0
        o = putU16(out, o, id and 0xFFFF)
        o = putU16(out, o, 0x0100)          // QR=0, opcode=0, RD=1
        o = putU16(out, o, 1)                // QDCOUNT
        putU16(out, o, 0); o += 2
        putU16(out, o, 0); o += 2
        putU16(out, o, 0); o += 2
        o = putName(out, o, name)
        o = putU16(out, o, type and 0xFFFF)
        o = putU16(out, o, qclass and 0xFFFF)
        return out.copyOf(o)
    }

    /**
     * ساخت پاسخ خطا بدون نیاز به سرور بالادست (برای دامنه‌های مسدود یا خرابی).
     * بخش پرسش از بستهٔ ورودی عیناً بازگردانده می‌شود.
     */
    fun buildError(request: ByteArray, len: Int, rcode: Int, authoritative: Boolean = true): ByteArray {
        // بستهٔ ناقص: فقط یک هدر SERVFAIL/rcode برمی‌گردانیم
        if (len < HEADER_SIZE) {
            val minimal = ByteArray(HEADER_SIZE)
            putU16(minimal, 2, 0x8000 or (rcode and 0xF))
            return minimal
        }
        val out = ByteArray(512)
        var o = 0
        o = putU16(out, o, u16(request, 0))                  // همان ID
        var flags = 0x8000 or (rcode and 0xF)                // QR=1
        if (authoritative) flags = flags or 0x0400           // AA=1
        flags = flags or (u16(request, 2) and 0x0100)        // RD را بازتاب می‌دهیم
        o = putU16(out, o, flags)
        o = putU16(out, o, 1)                                // QDCOUNT
        putU16(out, o, 0); o += 2
        putU16(out, o, 0); o += 2
        putU16(out, o, 0); o += 2
        // بخش پرسش را از درخواست کپی می‌کنیم (نام + QTYPE + QCLASS)
        val qStart = HEADER_SIZE
        val qEnd = findQuestionEnd(request, len)
        val qLen = (qEnd - qStart).coerceIn(0, out.size - o)
        System.arraycopy(request, qStart, out, o, qLen)
        o += qLen
        return out.copyOf(o)
    }

    // ------------------------------------------------------------- helpers

    fun findQuestionEnd(buf: ByteArray, len: Int): Int {
        var off = HEADER_SIZE
        val r = runCatching { readName(buf, off, len) }.getOrNull() ?: return len.coerceAtMost(HEADER_SIZE + 1)
        off = r.second
        return (off + 4).coerceAtMost(len)
    }

    fun putU16(out: ByteArray, off: Int, value: Int): Int {
        out[off] = ((value shr 8) and 0xFF).toByte()
        out[off + 1] = (value and 0xFF).toByte()
        return off + 2
    }

    fun u16(buf: ByteArray, off: Int): Int =
        ((buf[off].toInt() and 0xFF) shl 8) or (buf[off + 1].toInt() and 0xFF)

    fun u32(buf: ByteArray, off: Int): Long =
        ((u16(buf, off).toLong()) shl 16) or (u16(buf, off + 2).toLong())

    fun putName(out: ByteArray, off: Int, name: String): Int {
        var o = off
        if (name.isBlank()) {
            out[o] = 0
            return o + 1
        }
        for (label in name.trimEnd('.').split('.')) {
            val bytes = label.toByteArray(Charsets.UTF_8)
            require(bytes.size in 1..63) { "برچسب نامعتبر در نام دامنه: $label" }
            out[o] = bytes.size.toByte()
            System.arraycopy(bytes, 0, out, o + 1, bytes.size)
            o += bytes.size + 1
        }
        out[o] = 0
        return o + 1
    }

    /** خواندن نام با پشتیبانی از فشرده‌سازی اشاره‌گر (0xC0). */
    fun readName(buf: ByteArray, startOff: Int, len: Int): Pair<String, Int> {
        val sb = StringBuilder()
        var off = startOff
        var jumped = false
        var resume = -1
        var jumps = 0
        while (true) {
            if (off >= len) break
            val b = buf[off].toInt() and 0xFF
            if (b == 0) {
                off++
                break
            }
            if (b and 0xC0 == 0xC0) {
                if (off + 1 >= len) break
                val ptr = ((b and 0x3F) shl 8) or (buf[off + 1].toInt() and 0xFF)
                if (!jumped) resume = off + 2
                jumped = true
                off = ptr
                if (++jumps > 32) break   // محافظت در برابر حلقهٔ بی‌پایان
                continue
            }
            if (off + 1 + b > len) break
            if (sb.isNotEmpty()) sb.append('.')
            sb.append(String(buf, off + 1, b, Charsets.UTF_8))
            off += b + 1
        }
        return sb.toString() to (if (resume > 0) resume else off)
    }

    private fun pretty(type: Int, rdata: ByteArray, buf: ByteArray, rdStart: Int, len: Int): String =
        when (type) {
            TYPE_A -> if (rdata.size == 4) rdata.joinToString(".") { (it.toInt() and 0xFF).toString() } else ""
            TYPE_AAAA -> if (rdata.size == 16) runCatching {
                Inet6Address.getByAddress(null, rdata, null).hostAddress ?: ""
            }.getOrDefault("") else ""
            TYPE_CNAME, TYPE_NS, TYPE_PTR -> runCatching { readName(buf, rdStart, len).first }.getOrDefault("")
            TYPE_MX -> if (rdata.size >= 3) "${u16(rdata, 0)} ${
                runCatching { readName(buf, rdStart + 2, len).first }.getOrDefault("")
            }" else ""
            TYPE_TXT -> {
                val parts = ArrayList<String>()
                var i = 0
                while (i < rdata.size) {
                    val n = rdata[i].toInt() and 0xFF
                    if (i + 1 + n > rdata.size) break
                    parts.add(String(rdata, i + 1, n, Charsets.UTF_8))
                    i += n + 1
                }
                parts.joinToString(" ")
            }
            else -> rdata.joinToString("") { "%02x".format(it) }
        }

    /** آدرس متنی → بایت (برای ساخت پاسخ مصنوعی). بدون جست‌وجوی شبکه برای IPv4. */
    fun addressToBytes(address: String): ByteArray {
        val parts = address.split('.')
        if (parts.size == 4) {
            val out = ByteArray(4)
            var ok = true
            for (i in 0..3) {
                val v = parts[i].toIntOrNull()
                if (v == null || v < 0 || v > 255) { ok = false; break }
                out[i] = v.toByte()
            }
            if (ok) return out
        }
        return try {
            InetAddress.getByName(address).address
        } catch (_: UnknownHostException) {
            byteArrayOf(0, 0, 0, 0)
        }
    }

    /** ساخت پاسخ A مصنوعی (برای مسدودسازی با sinkhole). */
    fun buildSyntheticA(request: ByteArray, len: Int, sinkholeIp: String): ByteArray =
        buildResponseWithA(request, len, listOf(sinkholeIp), ttl = 30)

    /** ساخت پاسخ A واقعی از روی یک پرس‌وجو (برای تست و پاسخ‌های مصنوعی). */
    fun buildResponseWithA(request: ByteArray, len: Int, addresses: List<String>, ttl: Int = 60): ByteArray {
        val msg = parse(request, len)
        val q = msg.question ?: return buildError(request, len, RCODE_FORMERR)
        val out = ByteArray(1024)
        var o = 0
        o = putU16(out, o, msg.id)
        o = putU16(out, o, 0x8180)                    // QR=1, RD=1, RA=1, NOERROR
        o = putU16(out, o, 1)                         // QDCOUNT
        o = putU16(out, o, addresses.size)            // ANCOUNT
        o = putU16(out, o, 0)                         // NSCOUNT
        o = putU16(out, o, 0)                         // ARCOUNT
        o = putName(out, o, q.name)
        o = putU16(out, o, q.type)
        o = putU16(out, o, q.qclass)
        for (address in addresses) {
            val ip = addressToBytes(address)
            o = putU16(out, o, 0xC00C)                // اشاره‌گر به نام پرسش
            o = putU16(out, o, if (ip.size == 16) TYPE_AAAA else TYPE_A)
            o = putU16(out, o, 1)                     // CLASS = IN
            out[o] = (ttl shr 24 and 0xFF).toByte()
            out[o + 1] = (ttl shr 16 and 0xFF).toByte()
            out[o + 2] = (ttl shr 8 and 0xFF).toByte()
            out[o + 3] = (ttl and 0xFF).toByte()
            o += 4
            o = putU16(out, o, ip.size)
            System.arraycopy(ip, 0, out, o, ip.size)
            o += ip.size
        }
        return out.copyOf(o)
    }

    fun typeLabel(type: Int): String = when (type) {
        TYPE_A -> "A"
        TYPE_NS -> "NS"
        TYPE_CNAME -> "CNAME"
        TYPE_SOA -> "SOA"
        TYPE_PTR -> "PTR"
        TYPE_MX -> "MX"
        TYPE_TXT -> "TXT"
        TYPE_AAAA -> "AAAA"
        TYPE_SRV -> "SRV"
        TYPE_HTTPS -> "HTTPS"
        TYPE_SVCB -> "SVCB"
        TYPE_ANY -> "ANY"
        else -> "TYPE$type"
    }

    fun rcodeLabel(code: Int): String = when (code) {
        RCODE_NOERROR -> "NOERROR"
        RCODE_FORMERR -> "FORMERR"
        RCODE_SERVFAIL -> "SERVFAIL"
        RCODE_NXDOMAIN -> "NXDOMAIN"
        RCODE_NOTIMP -> "NOTIMP"
        RCODE_REFUSED -> "REFUSED"
        else -> "RCODE$code"
    }
}
