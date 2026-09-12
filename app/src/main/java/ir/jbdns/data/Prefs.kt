package ir.jbdns.data

import android.content.Context
import android.content.SharedPreferences

/** همهٔ تنظیمات برنامه در یک SharedPreferences. */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("jb_dns_prefs", Context.MODE_PRIVATE)

    var theme: String
        get() = sp.getString(K_THEME, "system") ?: "system"
        set(v) = sp.edit().putString(K_THEME, v).apply()

    var serverId: String
        get() = sp.getString(K_SERVER, BuiltInServers.DEFAULT_ID) ?: BuiltInServers.DEFAULT_ID
        set(v) = sp.edit().putString(K_SERVER, v).apply()

    /** پروتکل انتخاب‌شده برای هر سرور: serverId -> proto */
    fun protoFor(serverId: String): Proto? {
        val raw = sp.getString("$K_PROTO_PREFIX$serverId", null) ?: return null
        return Proto.values().firstOrNull { it.name == raw }
    }

    fun setProtoFor(serverId: String, proto: Proto) =
        sp.edit().putString("$K_PROTO_PREFIX$serverId", proto.name).apply()

    var blockDomains: Boolean
        get() = sp.getBoolean(K_BLOCK, true)
        set(v) = sp.edit().putBoolean(K_BLOCK, v).apply()

    var sinkhole: Boolean
        get() = sp.getBoolean(K_SINKHOLE, false)
        set(v) = sp.edit().putBoolean(K_SINKHOLE, v).apply()

    var sinkholeIp: String
        get() = sp.getString(K_SINKHOLE_IP, "0.0.0.0") ?: "0.0.0.0"
        set(v) = sp.edit().putString(K_SINKHOLE_IP, v).apply()

    var verifyCertificate: Boolean
        get() = sp.getBoolean(K_VERIFY, true)
        set(v) = sp.edit().putBoolean(K_VERIFY, v).apply()

    var fallbackToPlain: Boolean
        // پیش‌فرض خاموش: در ایران پورت ۵۳ معمولاً توسط ISP های‌جک می‌شود؛
        // fallback ساده یعنی هم پاسخ نادرست هم نشت DNS
        get() = sp.getBoolean(K_FALLBACK, false)
        set(v) = sp.edit().putBoolean(K_FALLBACK, v).apply()

    var logQueries: Boolean
        get() = sp.getBoolean(K_LOG, true)
        set(v) = sp.edit().putBoolean(K_LOG, v).apply()

    var blockIpv6: Boolean
        get() = sp.getBoolean(K_BLOCK_V6, true)
        set(v) = sp.edit().putBoolean(K_BLOCK_V6, v).apply()

    var autoStart: Boolean
        get() = sp.getBoolean(K_AUTOSTART, false)
        set(v) = sp.edit().putBoolean(K_AUTOSTART, v).apply()

    var totalQueries: Long
        get() = sp.getLong(K_TOTAL, 0)
        set(v) = sp.edit().putLong(K_TOTAL, v).apply()

    var totalBlocked: Long
        get() = sp.getLong(K_TOTAL_BLOCKED, 0)
        set(v) = sp.edit().putLong(K_TOTAL_BLOCKED, v).apply()

    // ---------------------------------------------------------- سرور سفارشی

    fun customServers(): List<Server> =
        sp.getStringSet(K_CUSTOM, emptySet()).orEmpty()
            .sorted()
            .mapNotNull { Server.decode(it) }

    fun saveCustomServer(server: Server) {
        val set = sp.getStringSet(K_CUSTOM, emptySet())!!.toMutableSet()
        set.removeAll { it.startsWith("${StoreCodec.escape(server.id)}|") }
        set.add(server.encode())
        sp.edit().putStringSet(K_CUSTOM, set).apply()
    }

    fun deleteCustomServer(id: String) {
        val set = sp.getStringSet(K_CUSTOM, emptySet())!!.toMutableSet()
        set.removeAll { it.startsWith("${StoreCodec.escape(id)}|") }
        sp.edit().putStringSet(K_CUSTOM, set).apply()
    }

    /** همهٔ سرورها: داخلی‌ها + سفارشی‌ها. */
    fun allServers(): List<Server> {
        val custom = customServers()
        val chosen = serverId
        return (BuiltInServers.ALL + custom).map { if (it.id == chosen) it.withProto(effectiveProto(it)) else it }
    }

    fun findServer(id: String?): Server? = allServers().firstOrNull { it.id == id }

    /** سرور فعال با پروتکل انتخاب‌شدهٔ کاربر. */
    fun activeServer(): Server {
        val s = findServer(serverId) ?: BuiltInServers.byId(serverId)
        return s.withProto(effectiveProto(s))
    }

    fun effectiveProto(s: Server): Proto {
        val saved = protoFor(s.id) ?: s.defaultProto
        return if (s.supports.contains(saved)) saved else s.defaultProto
    }

    // ---------------------------------------------------------- تونل افتراقی

    /** اعلام صوتی روشن/خاموش شدن DNS (پخش ویس Online/Offline). */
    var soundNotify: Boolean
        get() = sp.getBoolean(K_SOUND, true)
        set(v) = sp.edit().putBoolean(K_SOUND, v).apply()

    /** روشن/خاموش بودن تونل افتراقی (Split Tunneling). */
    var splitTunnelingEnabled: Boolean
        get() = sp.getBoolean(K_SPLIT_ON, false)
        set(v) = sp.edit().putBoolean(K_SPLIT_ON, v).apply()

    /** بسته‌هایی که وقتی تونل افتراقی روشن است از تونل VPN عبور می‌کنند. */
    fun tunneledPackages(): Set<String> =
        sp.getStringSet(K_TUNNELED, emptySet()).orEmpty().toSet()

    fun setTunneled(pkg: String, tunneled: Boolean) {
        val set = tunneledPackages().toMutableSet()
        if (tunneled) set.add(pkg) else set.remove(pkg)
        sp.edit().putStringSet(K_TUNNELED, set).apply()
    }

    // ------------------------------------------------------ فهرست مسدودسازی راه دور

    /** آدرس فایل hosts برای مسدودسازی راه دور (الگوی RethinkDNS/personalDNSfilter). */
    var blocklistUrl: String
        get() = sp.getString(K_BL_URL, "") ?: ""
        set(v) = sp.edit().putString(K_BL_URL, v).apply()

    var blocklistCount: Int
        get() = sp.getInt(K_BL_COUNT, 0)
        set(v) = sp.edit().putInt(K_BL_COUNT, v).apply()

    var blocklistUpdatedMs: Long
        get() = sp.getLong(K_BL_UPDATED, 0L)
        set(v) = sp.edit().putLong(K_BL_UPDATED, v).apply()

    // ------------------------------------------------------------- مسدودسازی

    fun extraBlockedDomains(): List<String> =
        sp.getString(K_EXTRA_BLOCK, "").orEmpty()
            .split('\n', ',', ' ').map { it.trim().lowercase() }.filter { it.isNotBlank() }

    fun setExtraBlockedDomains(domains: List<String>) =
        sp.edit().putString(K_EXTRA_BLOCK, domains.joinToString("\n")).apply()

    fun resetStats() = sp.edit().putLong(K_TOTAL, 0).putLong(K_TOTAL_BLOCKED, 0).apply()

    // ---------------------------------------------------------- زنجیرهٔ پشتیبان

    /** شناسهٔ سرورهای پشتیبان به‌ترتیب اولویت (بعد از سرور اصلی). */
    fun backupIds(): List<String> =
        sp.getString(K_BACKUPS, "").orEmpty().split(',').map { it.trim() }.filter { it.isNotBlank() }

    fun setBackups(ids: List<String>) =
        sp.edit().putString(K_BACKUPS, ids.joinToString(",")).apply()

    fun toggleBackup(id: String, on: Boolean) {
        val list = backupIds().toMutableList()
        if (on) { if (id !in list) list.add(id) } else list.remove(id)
        setBackups(list)
    }

    // ---------------------------------------------------------- کاتالوگ فهرست‌های آماده

    /** فهرست‌های آمادهٔ فعال (پیش‌فرض: تبلیغات + ردیاب‌ها + بدافزار). */
    fun catalogEnabled(): Set<String> =
        (sp.getStringSet(K_CATALOG, DEFAULT_CATALOG) ?: DEFAULT_CATALOG).toSet()

    fun setCatalog(id: String, on: Boolean) {
        val set = catalogEnabled().toMutableSet()
        if (on) set.add(id) else set.remove(id)
        sp.edit().putStringSet(K_CATALOG, set).apply()
    }

    // ---------------------------------------------------------- حالت مسابقه

    /** پرس‌وجوی هم‌زمان از همهٔ سرورهای زنجیره؛ سریع‌ترین پاسخ برنده (الگوی dnscrypt-proxy). */
    var raceMode: Boolean
        get() = sp.getBoolean(K_RACE, false)
        set(v) = sp.edit().putBoolean(K_RACE, v).apply()

    // ---------------------------------------------------------- بازنویسی DNS

    /** قواعد Cloaking: دامنه → IP ثابت (پاسخ محلی بدون رفتن به بالادست). */
    fun rewrites(): Map<String, String> =
        sp.getStringSet(K_REWRITES, emptySet()).orEmpty()
            .mapNotNull { raw -> raw.split('=', limit = 2).takeIf { it.size == 2 }?.let { (d, ip) ->
                d.trim().lowercase().removeSuffix(".") to ip.trim()
            } }
            .toMap()

    fun setRewrite(domain: String, ip: String?) {
        val d = domain.trim().lowercase().removeSuffix(".")
        val map = rewrites().toMutableMap()
        if (ip.isNullOrBlank()) map.remove(d) else map[d] = ip.trim()
        sp.edit().putStringSet(K_REWRITES, map.entries.map { "${it.key}=${it.value}" }.toSet()).apply()
    }

    fun clearRewrites() = sp.edit().putStringSet(K_REWRITES, emptySet()).apply()

    // ---------------------------------------------------------- دامنه‌های مجاز

    /** دامنه‌هایی که هرگز مسدود نمی‌شوند (حتی اگر در فهرست‌های مسدودسازی باشند). */
    fun allowedDomains(): Set<String> =
        sp.getStringSet(K_ALLOWED, emptySet()).orEmpty().map { it.trim().lowercase() }.toSet()

    fun setAllowed(domain: String, allowed: Boolean) {
        val set = allowedDomains().toMutableSet()
        val d = domain.trim().lowercase()
        if (allowed) set.add(d) else set.remove(d)
        sp.edit().putStringSet(K_ALLOWED, set).apply()
    }

    companion object {
        const val K_THEME = "theme"
        const val K_SERVER = "server"
        const val K_PROTO_PREFIX = "proto_"
        const val K_BLOCK = "block_domains"
        const val K_SINKHOLE = "sinkhole"
        const val K_SINKHOLE_IP = "sinkhole_ip"
        const val K_VERIFY = "verify_cert"
        const val K_FALLBACK = "fallback_plain"
        const val K_LOG = "log_queries"
        const val K_BLOCK_V6 = "block_ipv6"
        const val K_AUTOSTART = "auto_start"
        const val K_TOTAL = "total_queries"
        const val K_TOTAL_BLOCKED = "total_blocked"
        const val K_CUSTOM = "custom_servers"
        const val K_EXTRA_BLOCK = "extra_block"
        const val K_BACKUPS = "backup_servers"
        const val K_ALLOWED = "allowed_domains"
        const val K_SOUND = "sound_notify"
        const val K_SPLIT_ON = "split_tunneling_on"
        const val K_TUNNELED = "tunneled_apps"
        const val K_CATALOG = "catalog_lists"
        const val K_RACE = "race_mode"
        const val K_REWRITES = "dns_rewrites"
        const val K_BL_URL = "blocklist_url"
        const val K_BL_COUNT = "blocklist_count"
        const val K_BL_UPDATED = "blocklist_updated"

        /** پیش‌فرض کاتالوگ: تبلیغات + ردیاب‌ها + بدافزار روشن؛ خانواده خاموش. */
        val DEFAULT_CATALOG: Set<String> = setOf("ads", "trackers", "malware")
    }
}
