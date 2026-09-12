package ir.jbdns.data

/** پروتکل‌های پشتیبانی‌شدهٔ بالادست. */
enum class Proto(val title: String, val short: String) {
    DOH("DNS over HTTPS", "DoH"),
    DOT("DNS over TLS", "DoT"),
    DNSCRYPT("DNSCrypt", "DNSCrypt"),
    DNS("DNS کلاسیک", "DNS");

    companion object {
        fun fromId(id: String?): Proto = values().firstOrNull { it.name.equals(id, true) } ?: DOH
    }
}

/** دسته‌بندی سرورها برای نمایش گروهی. */
enum class ServerGroup(val title: String) {
    GLOBAL("سراسری"),
    IRAN("ایرانی"),
    PRIVACY("حریم خصوصی و امنیت"),
    BLOCK("مسدودساز تبلیغات"),
    CUSTOM("سفارشی")
}

/**
 * یک سرور DNS. یک سرور می‌تواند هم‌زمان چند پروتکل داشته باشد؛
 * [doh] نشانی کامل HTTPS است، [dot] فقط نام میزبان و [dns] فهرست آی‌پی‌ها.
 */
data class Server(
    val id: String,
    val name: String,
    val group: ServerGroup,
    val doh: String? = null,
    val dot: String? = null,
    val sdns: String? = null,
    val dns: List<String> = emptyList(),
    val note: String = "",
    val custom: Boolean = false,
    val proto: Proto = Proto.DOH
) {
    val supports: List<Proto>
        get() = listOfNotNull(
            Proto.DOH.takeIf { !doh.isNullOrBlank() },
            Proto.DOT.takeIf { !dot.isNullOrBlank() },
            Proto.DNSCRYPT.takeIf { !sdns.isNullOrBlank() },
            Proto.DNS.takeIf { dns.isNotEmpty() }
        )

    val defaultProto: Proto get() = supports.firstOrNull() ?: Proto.DNS

    /** سرورهای گروه ایرانی فقط از داخل شبکهٔ ایران پاسخ می‌دهند. */
    val iranOnly: Boolean get() = group == ServerGroup.IRAN

    /** آدرسی که برای پروتکل انتخاب‌شده به کاربر نشان داده می‌شود. */
    fun addressFor(p: Proto): String = when (p) {
        Proto.DOH -> doh.orEmpty()
        Proto.DOT -> dot.orEmpty()
        Proto.DNSCRYPT -> sdns.orEmpty()
        Proto.DNS -> dns.joinToString("، ")
    }

    /** آی‌پی‌های بالادست که VPN باید مسیرشان را باز بگذارد (برای DNS ساده). */
    fun plainIps(): List<String> = dns

    fun withProto(p: Proto): Server = copy(proto = if (supports.contains(p)) p else defaultProto)

    fun encode(): String = listOf(
        id, name, group.name, proto.name,
        doh.orEmpty(), dot.orEmpty(), dns.joinToString(","),
        if (custom) "1" else "0", note, sdns.orEmpty()
    ).joinToString("|") { StoreCodec.escape(it) }

    companion object {
        fun decode(line: String): Server? {
            val f = StoreCodec.split(line)
            if (f.size < 8) return null
            return Server(
                id = f[0],
                name = f[1],
                group = ServerGroup.values().firstOrNull { it.name == f[2] } ?: ServerGroup.CUSTOM,
                proto = Proto.fromId(f[3]),
                doh = f[4].ifBlank { null },
                dot = f[5].ifBlank { null },
                dns = f[6].split(',').filter { it.isNotBlank() },
                custom = f[7] == "1",
                note = f.getOrNull(8).orEmpty(),
                sdns = f.getOrNull(9).orEmpty().ifBlank { null }
            )
        }
    }
}

/** رمزگذاری/رمزگشایی رکوردهای متنی برای SharedPreferences (بدون وابستگی خارجی). */
object StoreCodec {
    fun escape(s: String): String = s
        .replace("\\", "\\\\")
        .replace("|", "\\p")
        .replace("\n", "\\n")

    fun split(line: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == '\\' && i + 1 < line.length) {
                when (val n = line[i + 1]) {
                    'p' -> sb.append('|')
                    'n' -> sb.append('\n')
                    '\\' -> sb.append('\\')
                    else -> sb.append(n)
                }
                i += 2
            } else if (c == '|') {
                out.add(sb.toString()); sb.setLength(0); i++
            } else {
                sb.append(c); i++
            }
        }
        out.add(sb.toString())
        return out
    }
}
