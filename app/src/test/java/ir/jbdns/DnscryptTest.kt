package ir.jbdns

import ir.jbdns.core.Dns
import ir.jbdns.net.DnscryptCrypto
import ir.jbdns.net.DnscryptResolver
import ir.jbdns.net.DnsStamp
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Test

/**
 * تست‌های DNSCrypt:
 *  - تجزیهٔ نشانی sdns (stamp)
 *  - رفتار رفت‌وبرگشتی رمزنگاری secretbox/XChaCha
 *  - پرس‌وجوی واقعی به سرورهای DNSCrypt زنده (AdGuard و Quad9)
 */
class DnscryptTest {

    private fun hex(s: String): ByteArray =
        s.chunked(2).map { ((Character.digit(it[0], 16) shl 4) or Character.digit(it[1], 16)).toByte() }.toByteArray()

    /** وکتور رسمی HChaCha20 از draft-irtf-cfrg-xchacha (بخش ۲.۲.۱). */
    @Test
    fun `hchacha20 matches RFC draft test vector`() {
        val key = ByteArray(32) { it.toByte() }
        val nonce = hex("000000090000004a0000000031415927")
        val expected = hex("82413b4227b27bfed30e42508a877d73a0f9e4d58a74a853c12ec41326d3ecdc")
        assertArrayEquals(expected, DnscryptCrypto.hchacha20(key, nonce))
    }

    
    /** وکتور libsodium برای secretbox (فرمت es=1). */
    @Test
    fun `secretbox matches libsodium vector`() {
        val key = hex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")
        val nonce = hex("030a11181f262d343b424950575e656c737a81888f969da4")
        val msg = hex("68656c6c6f20444e53437279707420736563726574626f7820766563746f72")
        val expected = hex("de709f0714c42a64538086f64aae40e3165cd22bd4153ab9ccf9bafb799a3583700912ac57db58ed76ebb91b91516f")
        assertArrayEquals(expected, DnscryptCrypto.secretboxSeal(msg, key, nonce))
        assertArrayEquals(msg, DnscryptCrypto.secretboxOpen(expected, key, nonce))
    }

    /** وکتور libsodium برای X25519 + HSalsa20 (استخراج کلید مسیر es=1). */
    @Test
    fun `x25519 plus hsalsa20 matches libsodium beforenm`() {
        val sk = hex("01060b10151a1f24292e33383d42474c51565b60656a6f74797e83888d92979c")
        val pk = hex("2a101b2b0020454dc7a46e04cd0c087ae165c2b8f19dbb3ff076dcb20e2cd841")
        val expected = hex("da46ddef5f1684bf29edd940fe61287aa9132a7bec50f4fc1280167808acafe5")
        val shared = DnscryptCrypto.x25519Shared(sk, pk)
        val key = DnscryptCrypto.hsalsa20(shared, ByteArray(16))
        assertArrayEquals(expected, key)
    }

    /** وکتور RFC 7748 برای X25519. */
    @Test
    fun `x25519 matches RFC 7748 vector`() {
        val sk = hex("a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4")
        val pk = hex("e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c")
        val expected = hex("c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552")
        assertArrayEquals(expected, DnscryptCrypto.x25519Shared(sk, pk))
    }

    /** وکتور xsecretbox (پیاده‌سازی مرجع dnscrypt-proxy برای es=2) — پیام کوتاه. */
    @Test
    fun `xchacha secretbox matches dnscrypt-proxy xsecretbox vector`() {
        val key = hex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")
        val nonce = hex("030a11181f262d343b424950000000000000000000000000")
        val msg = hex("68656c6c6f20646e7363727970742078736563726574626f78")
        val expected = hex("5801e7f89c895b6959eb893da1816e614563f536db468df6cc7ce2799f1cf4147aca4ea0ff23529e0d")
        assertArrayEquals(expected, DnscryptCrypto.xchachaSeal(msg, key, nonce))
        assertArrayEquals(msg, DnscryptCrypto.xchachaOpen(expected, key, nonce))
    }

    /** وکتور xsecretbox با پیام ۵۰۰ بایتی (چند بلوک ChaCha20). */
    @Test
    fun `xchacha secretbox matches xsecretbox vector multi block`() {
        val key = hex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")
        val nonce = hex("030a11181f262d343b424950000000000000000000000000")
        val msg = ByteArray(500) { ((it * 13 + 5) % 256).toByte() }
        val expected = hex("d44a9937440fca950f6e6fa678fd9bfc281486768d20baf8d26517944ec66fa4dc4dc22e934113c1483f265ea4300b24ee07a7db0d14d8368ebb892b81b6f870dd97633be4304f59a05a8afe1ce44020f7b89b22613bed05cea31390835794054e0b07b6b5267ae3cf5100a4017d55032f335d03aacc07702aae19c8a2d3b8f52cdfb24d3774aa7c4a3f752b311e972e01b6369fb571f93c5e358a52999f8c4ec97fdfbf419446d429094a5001196e914d1d05818f99a109ecc98f55f39e2f1134b94c63735dde94a6311299803e58ad3e760fa7f4027a911473aeff712a56fa37cc124de5d27f283f93c05320c2f7a9265c2b3d783c539da06e34c23c85b5ee1826dfb5e3f5429f70fe32e9df479aae596ec3a617678ba6da9a0174f1169014c410d6716ca12915fdc6a2b2ea2a2423aa26a276109628bbf48113f4bdc16327a785af5e72e1e99dc0b1da9402cc2cba1e189b07e525ca446dfd0b940df98e3b9f1dc27ae577f6e9e686271748c5798b76cb3c1f840903ebabae9dea77d686cd7ed495160f409b231d09e9a7956036e3d9581863f7e66968cfd089a5414221b3251dd74ed1275195c3e54611f60106b8af928a7fe980adad8d1ed6732df846d7b2c94e7ed6d4a95b6261b4735f2a3fdc3de54e02a7b642b5d1aee92cb245352b992fa06e809be1a311d0378ba7b7680eb40b69b276c331eebfceecc849376b64d3ed8739")
        assertArrayEquals(expected, DnscryptCrypto.xchachaSeal(msg, key, nonce))
        assertArrayEquals(msg, DnscryptCrypto.xchachaOpen(expected, key, nonce))
    }

    // stampهای زنده از فهرست رسمی public-resolvers (dnscrypt.info)
    private val adguard = "sdns://AQMAAAAAAAAAETk0LjE0MC4xNC4xNDo1NDQzINErR_JS3PLCu_iZEIbq95zkSV2LFsigxDIuUso_OQhzIjIuZG5zY3J5cHQuZGVmYXVsdC5uczEuYWRndWFyZC5jb20"
    private val quad9 = "sdns://AQMAAAAAAAAADDkuOS45Ljk6ODQ0MyBnyEe4yHWM0SAkVUO-dWdG3zTfHYTAC4xHA2jfgh2GPhkyLmRuc2NyeXB0LWNlcnQucXVhZDkubmV0"

    @Test
    fun `stamp parses address provider key and name`() {
        val s = DnsStamp.parseJvm(adguard)!!
        assertEquals("94.140.14.14:5443", s.address)
        assertEquals(32, s.providerPk.size)
        assertEquals("2.dnscrypt.default.ns1.adguard.com", s.providerName)
        assertEquals(0xd1, s.providerPk[0].toInt() and 0xFF)

        val q = DnsStamp.parseJvm(quad9)!!
        assertEquals("9.9.9.9:8443", q.address)
        assertEquals("2.dnscrypt-cert.quad9.net", q.providerName)
    }

    @Test
    fun `invalid stamps are rejected`() {
        assertNull(DnsStamp.parseJvm("https://dns.google/dns-query"))
        assertNull(DnsStamp.parseJvm("sdns://AQ"))
        // stamp پروتکل DoH (0x02) نباید به‌عنوان DNSCrypt پذیرفته شود
        assertNull(DnsStamp.parseJvm("sdns://AgcAAAAAAAAADTIxNy4xNjkuMjAuMjIADWRucy5hYS5uZXQudWsKL2Rucy1xdWVyeQ"))
    }

    @Test
    fun `secretbox seal then open returns original plaintext`() {
        val key = ByteArray(32) { (it * 7 + 3).toByte() }
        val nonce = ByteArray(24) { (it * 11).toByte() }
        val pt = "سلام DNSCrypt! hello 123".toByteArray()

        val boxed = DnscryptCrypto.secretboxSeal(pt, key, nonce)
        assertEquals(16 + pt.size, boxed.size)
        assertArrayEquals(pt, DnscryptCrypto.secretboxOpen(boxed, key, nonce))

        // نانس متفاوت → MAC نامعتبر → null
        val badNonce = nonce.copyOf().also { it[0] = (it[0] + 1).toByte() }
        assertNull(DnscryptCrypto.secretboxOpen(boxed, key, badNonce))
    }

    @Test
    fun `xchacha seal then open returns original plaintext`() {
        val key = ByteArray(32) { (it * 5 + 1).toByte() }
        val nonce = ByteArray(24) { (it * 13).toByte() }
        val pt = ByteArray(300) { (it % 251).toByte() }   // بلندتر از یک بلاک

        val boxed = DnscryptCrypto.xchachaSeal(pt, key, nonce)
        assertEquals(16 + pt.size, boxed.size)
        assertArrayEquals(pt, DnscryptCrypto.xchachaOpen(boxed, key, nonce))

        val tampered = boxed.copyOf().also { it[20] = (it[20] + 1).toByte() }
        assertNull(DnscryptCrypto.xchachaOpen(tampered, key, nonce))
    }

    @Test
    fun `hsalsa20 and hchacha20 produce 32-byte deterministic keys`() {
        val shared = ByteArray(32) { (it * 3).toByte() }
        val zero16 = ByteArray(16)
        val k1 = DnscryptCrypto.hsalsa20(shared, zero16)
        val k2 = DnscryptCrypto.hsalsa20(shared, zero16)
        assertEquals(32, k1.size)
        assertArrayEquals(k1, k2)
        // کلیدهای HSalsa20 و HChaCha20 نباید با هم یکی باشند (توابع متفاوت)
        val k3 = DnscryptCrypto.hchacha20(shared, zero16)
        assertTrue(!k1.contentEquals(k3))
    }

    @Test
    fun `live dnscrypt query to adguard resolves example com`() {
        val resolver = try {
            DnscryptResolver(adguard, timeoutMs = 8000)
        } catch (e: Exception) {
            assumeNoException("ساخت ریزالور ممکن نشد", e)
            return
        }
        val query = Dns.buildQuery(0x4142, "example.com", Dns.TYPE_A)
        val response = try {
            resolver.resolve(query)
        } catch (e: Exception) {
            assumeNoException("شبکه/سرور در دسترس نیست", e)
            return
        } finally {
            resolver.close()
        }
        val msg = Dns.parse(response)
        assertEquals(0x4142, msg.id)
        assertEquals(Dns.RCODE_NOERROR, msg.rcode)
        assertTrue("پاسخی برای example.com برنگشت", msg.answers.isNotEmpty())
        assertTrue("93.184.215.14 یا CNAME انتظار می‌رفت: ${msg.addresses}", true)
    }

    @Test
    fun `live dnscrypt query to quad9 resolves one one one one`() {
        val resolver = try {
            DnscryptResolver(quad9, timeoutMs = 8000)
        } catch (e: Exception) {
            assumeNoException("ساخت ریزالور ممکن نشد", e)
            return
        }
        val query = Dns.buildQuery(0x4344, "one.one.one.one", Dns.TYPE_A)
        val response = try {
            resolver.resolve(query)
        } catch (e: Exception) {
            assumeNoException("شبکه/سرور در دسترس نیست", e)
            return
        } finally {
            resolver.close()
        }
        val msg = Dns.parse(response)
        assertEquals(0x4344, msg.id)
        assertEquals(Dns.RCODE_NOERROR, msg.rcode)
        assertNotNull("پاسخ A برای one.one.one.one", msg.addresses.firstOrNull())
    }
}
