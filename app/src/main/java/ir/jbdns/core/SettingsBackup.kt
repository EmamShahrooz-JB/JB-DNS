package ir.jbdns.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * مدل خام پشتیبان تنظیمات — عمداً مستقل از اندروید تا با تست واحد
 * رفتار خروجی/ورودی‌اش بررسی شود. MainActivity این مدل را به Prefs وصل می‌کند.
 */
data class BackupData(
    val serverId: String? = null,
    val theme: String? = null,
    val toggles: Map<String, Boolean> = emptyMap(),
    val protos: Map<String, String> = emptyMap(),
    val customServers: List<String> = emptyList(),
    val backups: List<String> = emptyList(),
    val allowed: List<String> = emptyList(),
    val blockedExtra: List<String> = emptyList(),
    val rewrites: Map<String, String> = emptyMap(),
    val catalog: List<String> = emptyList(),
    val raceMode: Boolean? = null,
    val splitTunneling: Boolean? = null,
    val tunneledApps: List<String> = emptyList(),
    val blocklistUrl: String? = null
)

object SettingsBackup {

    const val VERSION = 4

    /** کلیدهای مجاز toggle — هر کلید دیگری نادیده گرفته می‌شود. */
    val TOGGLE_KEYS = setOf(
        "block", "sinkhole", "ipv6", "verify", "fallback", "autostart", "sound"
    )

    fun export(d: BackupData): String = JSONObject().apply {
        put("app", "JB-DNS")
        put("v", VERSION)
        d.serverId?.let { put("serverId", it) }
        d.theme?.let { put("theme", it) }
        if (d.toggles.isNotEmpty()) put("toggles", JSONObject(d.toggles))
        if (d.protos.isNotEmpty()) put("protos", JSONObject(d.protos))
        if (d.customServers.isNotEmpty()) put("customServers", JSONArray(d.customServers))
        if (d.backups.isNotEmpty()) put("backups", JSONArray(d.backups))
        if (d.allowed.isNotEmpty()) put("allowed", JSONArray(d.allowed))
        if (d.blockedExtra.isNotEmpty()) put("blockedExtra", JSONArray(d.blockedExtra))
        if (d.rewrites.isNotEmpty()) put("rewrites", JSONObject(d.rewrites))
        if (d.catalog.isNotEmpty()) put("catalog", JSONArray(d.catalog))
        d.raceMode?.let { put("raceMode", it) }
        d.splitTunneling?.let { put("splitTunneling", it) }
        if (d.tunneledApps.isNotEmpty()) put("tunneledApps", JSONArray(d.tunneledApps))
        d.blocklistUrl?.let { put("blocklistUrl", it) }
    }.toString()

    /**
     * خواندن پشتیبان؛ روی فایل ناقص یا غیر-JB-DNS استثنا می‌دهد
     * تا بازیابی ناخواسته نیمه‌کاره انجام نشود.
     */
    fun import(json: String): BackupData {
        val o = JSONObject(json)   // JSONException برای فایل خراب
        if (o.optString("app") != "JB-DNS") throw IllegalArgumentException("این فایل پشتیبان JB-DNS نیست")
        val v = o.optInt("v", -1)
        if (v < 1 || v > VERSION) throw IllegalArgumentException("نسخهٔ پشتیبان پشتیبانی نمی‌شود ($v)")

        fun arr(key: String): List<String> {
            val a = o.optJSONArray(key) ?: return emptyList()
            return (0 until a.length()).mapNotNull { runCatching { a.getString(it) }.getOrNull() }
        }

        fun obj(key: String): Map<String, String> {
            val j = o.optJSONObject(key) ?: return emptyMap()
            val out = HashMap<String, String>()
            for (k in j.keys()) out[k] = j.optString(k)
            return out
        }

        val toggles = obj("toggles").mapNotNull { (k, vRaw) ->
            if (k in TOGGLE_KEYS && (vRaw == "true" || vRaw == "false")) k to (vRaw == "true") else null
        }.toMap()

        return BackupData(
            serverId = o.optString("serverId").ifBlank { null },
            theme = o.optString("theme").ifBlank { null },
            toggles = toggles,
            protos = obj("protos"),
            customServers = arr("customServers"),
            backups = arr("backups"),
            allowed = arr("allowed"),
            blockedExtra = arr("blockedExtra"),
            rewrites = obj("rewrites"),
            catalog = arr("catalog"),
            raceMode = if (o.has("raceMode")) o.optBoolean("raceMode") else null,
            splitTunneling = if (o.has("splitTunneling")) o.optBoolean("splitTunneling") else null,
            tunneledApps = arr("tunneledApps"),
            blocklistUrl = o.optString("blocklistUrl").ifBlank { null }
        )
    }
}
