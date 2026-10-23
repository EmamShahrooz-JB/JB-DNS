package ir.jbdns.net

import ir.jbdns.data.Prefs
import ir.jbdns.data.Proto
import ir.jbdns.data.Server
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Socket

/** سرور پیکربندی‌شده را به یک [UpstreamResolver] محافظت‌شده و ضد-سانسور تبدیل می‌کند. */
object UpstreamFactory {

    fun create(server: Server, prefs: Prefs, timeoutMs: Int? = null): UpstreamResolver {
        val proto = prefs.effectiveProto(server)
        val verify = prefs.verifyCertificate
        val protectTcp: (Socket) -> Unit = { sock ->
            runCatching { DnsVpnService.instance?.protect(sock) }
        }
        val protectUdp: (DatagramSocket) -> Unit = { sock ->
            runCatching { DnsVpnService.instance?.protect(sock) }
        }

        val base: UpstreamResolver = when (proto) {
            Proto.DOH -> DohResolver(
                url = server.doh.orEmpty(),
                connectTimeoutMs = timeoutMs?.toLong() ?: 5_000L,
                readTimeoutMs = timeoutMs?.toLong() ?: 5_000L,
                bootstrapIps = server.dns,
                verifyCertificate = verify,
                protect = protectTcp
            )
            Proto.DOT -> DotResolver(
                hostname = server.dot.orEmpty(),
                verifyCertificate = verify,
                timeoutMs = timeoutMs ?: 5_000,
                bootstrapIps = server.dns,
                protect = protectTcp
            )
            Proto.DNSCRYPT -> DnscryptResolver(
                stampText = server.sdns.orEmpty(),
                timeoutMs = timeoutMs ?: 5_000,
                protectDatagram = protectUdp,
                protectSocket = protectTcp
            )
            Proto.DNS -> PlainDnsResolver(
                servers = server.dns.ifEmpty { listOf("1.1.1.1") },
                timeoutMs = timeoutMs ?: 4_000,
                protectDatagram = protectUdp,
                protectSocket = protectTcp
            )
        }
        val plain = if (prefs.fallbackToPlain && proto != Proto.DNS && server.dns.isNotEmpty()) {
            PlainDnsResolver(
                servers = server.dns,
                timeoutMs = timeoutMs ?: 4_000,
                protectDatagram = protectUdp,
                protectSocket = protectTcp
            )
        } else null
        return FallbackResolver(base, plain)
    }

    /** نام میزبان یک نشانی DoH. */
    fun hostOfUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return try {
            java.net.URI(url).host
        } catch (_: Exception) {
            null
        }
    }

    /** آی‌پی‌های تمیز یک نام میزبان (برای نمایش و عیب‌یابی). */
    fun resolveIps(host: String): List<String> = try {
        BootstrapDns.resolveForDoh(host)
            .mapNotNull { addr -> if (addr is Inet4Address) addr.hostAddress else null }
    } catch (_: Exception) {
        emptyList()
    }
}
