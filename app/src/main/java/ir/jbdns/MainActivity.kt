package ir.jbdns

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import ir.jbdns.core.BackupData
import ir.jbdns.core.BatteryWizard
import ir.jbdns.core.BenchResult
import ir.jbdns.core.Benchmark
import ir.jbdns.core.BlockList
import ir.jbdns.core.BlocklistCatalog
import ir.jbdns.core.Dns
import ir.jbdns.core.Feedback
import ir.jbdns.core.HealthCheck
import ir.jbdns.core.RemoteBlocklist
import ir.jbdns.core.SettingsBackup
import ir.jbdns.core.VpnStats
import ir.jbdns.data.BuiltInServers
import ir.jbdns.data.Prefs
import ir.jbdns.data.Proto
import ir.jbdns.data.Server
import ir.jbdns.data.ServerGroup
import ir.jbdns.net.DnsVpnService
import ir.jbdns.net.ProtectedSocketFactory
import ir.jbdns.net.UpstreamFactory
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CONNECT = "extra_connect"
        private const val BENCH_THREADS = 4
    }

    private lateinit var wv: WebView
    private lateinit var prefs: Prefs

    @Volatile private var destroyed = false
    @Volatile private var benchingAll = false
    private val iconCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    @Volatile private var packageCache: JSONArray? = null
    private val benchPool = Executors.newFixedThreadPool(BENCH_THREADS) { r ->
        Thread(r, "jb-dns-bench").apply { isDaemon = true }
    }

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .socketFactory(ProtectedSocketFactory { sock ->
                runCatching { DnsVpnService.instance?.protect(sock) }
            })
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) startVpn() else pushState()
    }

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** انتخاب فایل پشتیبان تنظیمات برای بازیابی. */
    private val importFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: throw IOException("خواندن فایل ممکن نشد")
            val data = SettingsBackup.import(text)
            val applied = applyBackup(data)
            DnsVpnService.instance?.applySettings()
            pushState()
            wv.postDelayed({
                runCatching { wv.reload() }
                wv.postDelayed({ pushToJs("__imported", "$applied") }, 800)
            }, 100)
        }.onFailure {
            runOnUiThread {
                android.widget.Toast.makeText(
                    this,
                    "بازیابی ناموفق: ${it.message}",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        wv = WebView(this)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.allowFileAccess = false
        wv.webViewClient = WebViewClient()
        wv.setBackgroundColor(0xFF02040C.toInt())
        wv.addJavascriptInterface(Bridge(), "Android")
        setContentView(wv)
        wv.loadUrl("file:///android_asset/index.html")

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (destroyed) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    return
                }
                wv.evaluateJavascript("(function(){return window.__handleBack?!!window.__handleBack():false})()") { res ->
                    if (res == "true") return@evaluateJavascript
                    if (wv.canGoBack()) {
                        wv.goBack()
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            }
        })

        wv.postDelayed({ pushState() }, 900)
        maybePickDefaultServer()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (intent?.getBooleanExtra(EXTRA_CONNECT, false) == true) {
            wv.postDelayed({ maybeConnectFromTile() }, 500)
        }
    }

    private fun maybePickDefaultServer() {
        if (prefs.initialProbed) return
        prefs.initialProbed = true
        if (prefs.serverId != BuiltInServers.DEFAULT_ID) return
        if (DnsVpnService.isTunnelRunning) return
        Thread({
            val shecanAlive = probeUdp("178.22.122.100")
            if (shecanAlive || destroyed) return@Thread
            val cloudflareAlive = probeUdp("1.1.1.1")
            if (cloudflareAlive && !destroyed && prefs.serverId == BuiltInServers.DEFAULT_ID) {
                prefs.serverId = "cloudflare"
                pushState()
            }
        }, "jb-default-probe").apply { isDaemon = true; start() }
    }

    private fun probeUdp(ip: String): Boolean = runCatching {
        java.net.DatagramSocket().use { s ->
            s.soTimeout = 1500
            val q = Dns.buildQuery(0x4A4A, "www.google.com", Dns.TYPE_A)
            s.send(java.net.DatagramPacket(q, q.size, java.net.InetSocketAddress(ip, 53)))
            val buf = ByteArray(512)
            s.receive(java.net.DatagramPacket(buf, buf.size))
            buf.size >= Dns.HEADER_SIZE
        }
    }.getOrDefault(false)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_CONNECT, false)) {
            wv.postDelayed({ maybeConnectFromTile() }, 300)
        }
    }

    private fun maybeConnectFromTile() {
        if (destroyed || DnsVpnService.isTunnelRunning) return
        val prepare = VpnService.prepare(this)
        if (prepare == null) startVpn() else vpnPermission.launch(prepare)
    }

    private fun startVpn() {
        ContextCompat.startForegroundService(this, Intent(this, DnsVpnService::class.java))
        wv.postDelayed({ pushState() }, 700)
        wv.postDelayed({ pushState() }, 1600)
    }

    private fun stopVpn() {
        DnsVpnService.instance?.stopVpn()
        runCatching { stopService(Intent(this, DnsVpnService::class.java)) }
        VpnStats.active = false
        wv.postDelayed({ pushState() }, 250)
        wv.postDelayed({ pushState() }, 900)
    }

    private fun stateJson(): String = JSONObject().apply {
        put("on", DnsVpnService.isTunnelRunning)
        put("up", VpnStats.uptimeMs())
        put("q", VpnStats.totalQueries)
        put("b", VpnStats.totalBlocked)
        put("server", VpnStats.serverLabel)
        put("proto", VpnStats.protoLabel)
        put("err", VpnStats.lastError)
        put("sid", prefs.serverId)
    }.toString()

    private fun pushState() = runOnUiThread {
        if (!destroyed) wv.evaluateJavascript("window.__vpn && window.__vpn(${stateJson()})", null)
    }

    private fun pushToJs(fn: String, jsonArg: String) {
        if (destroyed) return
        runOnUiThread {
            if (!destroyed) wv.evaluateJavascript("window.$fn && window.$fn($jsonArg)", null)
        }
    }

    private fun benchJson(r: BenchResult, server: Server): String = JSONObject().apply {
        put("id", r.serverId)
        put("ok", r.ok)
        put("medianMs", r.medianMs)
        put("bestMs", r.bestMs)
        put("worstMs", r.worstMs)
        put("answered", r.answered)
        put("total", r.total)
        put("error", if (!r.ok && server.iranOnly) "فقط از داخل ایران پاسخ می‌دهد" else (r.error ?: ""))
    }.toString()

    private fun serverJson(s: Server): JSONObject = JSONObject().apply {
        put("id", s.id)
        put("n", s.name)
        put("g", s.group.name)
        if (s.iranOnly) put("ir", 1)
        if (s.custom) put("c", 1)
        s.doh?.let { put("doh", it) }
        s.dot?.let { put("dot", it) }
        s.sdns?.let { put("sdns", it) }
        put("sup", JSONArray().apply { s.supports.forEach { put(it.name) } })
        put("dns", JSONArray(s.dns))
        put("t", s.note.ifBlank { if (s.custom) "سفارشی" else "" })
        put("proto", s.proto.name)
    }

    private inner class Bridge {
        @JavascriptInterface
        fun connect(serverId: String) {
            prefs.serverId = serverId
            runOnUiThread {
                if (destroyed) return@runOnUiThread
                val prepare = VpnService.prepare(this@MainActivity)
                if (prepare == null) startVpn() else vpnPermission.launch(prepare)
            }
        }

        @JavascriptInterface
        fun disconnect() {
            stopVpn()
        }

        @JavascriptInterface
        fun state(): String = stateJson()

        @JavascriptInterface
        fun logs(): String = JSONArray().apply {
            for (e in VpnStats.snapshot().take(120)) put(JSONObject().apply {
                put("t", e.time); put("d", e.domain); put("ty", e.type)
                put("a", e.answer); put("s", e.status.name); put("ms", e.ms)
            })
        }.toString()

        @JavascriptInterface
        fun clearLogs() {
            VpnStats.clearLog()
        }

        @JavascriptInterface
        fun setServer(id: String) {
            prefs.serverId = id
            DnsVpnService.instance?.applySettings()
        }

        @JavascriptInterface
        fun builtInServers(): String = JSONArray().apply {
            for (s in BuiltInServers.ALL) put(serverJson(s))
        }.toString()

        @JavascriptInterface
        fun addServer(json: String): String {
            val id = "c" + System.currentTimeMillis()
            runCatching {
                val o = JSONObject(json)
                val dnsArr = o.optJSONArray("dns")
                val proto = Proto.fromId(o.optString("proto"))
                val server = Server(
                    id = id,
                    name = o.optString("name").trim().take(80),
                    group = ServerGroup.CUSTOM,
                    doh = o.optString("doh").trim().ifBlank { null },
                    dot = o.optString("dot").trim().ifBlank { null },
                    sdns = o.optString("sdns").trim().ifBlank { null },
                    dns = (0 until (dnsArr?.length() ?: 0))
                        .map { dnsArr!!.getString(it).trim() }
                        .filter { it.isNotBlank() },
                    note = "سفارشی",
                    custom = true,
                    proto = proto
                )
                prefs.saveCustomServer(server)
                prefs.setProtoFor(id, proto)
            }
            return id
        }

        @JavascriptInterface
        fun deleteServer(id: String): Boolean {
            val existing = prefs.findServer(id) ?: return false
            if (!existing.custom && existing.group != ServerGroup.CUSTOM) return false
            prefs.deleteCustomServer(id)
            prefs.toggleBackup(id, false)
            if (prefs.serverId == id) {
                prefs.serverId = BuiltInServers.DEFAULT_ID
            }
            DnsVpnService.instance?.applySettings()
            pushState()
            return true
        }

        @JavascriptInterface
        fun customServers(): String = JSONArray().apply {
            for (s in prefs.customServers()) put(serverJson(s))
        }.toString()

        @JavascriptInterface
        fun getPackages(): String {
            packageCache?.let { return it.toString() }
            val pm = packageManager
            val arr = JSONArray()
            val seen = HashSet<String>()

            val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val apps = runCatching { pm.queryIntentActivities(launcherIntent, 0) }.getOrDefault(emptyList())
            for (info in apps.sortedBy { it.loadLabel(pm).toString() }) {
                val pkg = info.activityInfo?.packageName ?: continue
                if (pkg == packageName || !seen.add(pkg)) continue
                arr.put(JSONObject().apply {
                    put("p", pkg)
                    put("n", info.loadLabel(pm).toString())
                })
            }

            if (arr.length() == 0) {
                val pkgs = runCatching { pm.getInstalledPackages(0) }.getOrDefault(emptyList())
                for (pi in pkgs) {
                    val pkg = pi.packageName ?: continue
                    if (pkg == packageName || !seen.add(pkg)) continue
                    runCatching { pm.getLaunchIntentForPackage(pkg) }.getOrNull() ?: continue
                    val label = runCatching {
                        pi.applicationInfo?.let { pm.getApplicationLabel(it).toString() }
                    }.getOrNull() ?: pkg
                    arr.put(JSONObject().apply {
                        put("p", pkg)
                        put("n", label)
                    })
                }
            }

            packageCache = arr
            return arr.toString()
        }

        @JavascriptInterface
        fun getTunneled(): String = JSONArray(prefs.tunneledPackages()).toString()

        @JavascriptInterface
        fun getBackups(): String = JSONArray(prefs.backupIds()).toString()

        @JavascriptInterface
        fun setBackup(id: String, on: Boolean) {
            prefs.toggleBackup(id, on)
            DnsVpnService.instance?.applySettings()
        }

        @JavascriptInterface
        fun getCustomRules(): String = JSONObject().apply {
            put("blocked", JSONArray(prefs.extraBlockedDomains()))
            put("allowed", JSONArray(prefs.allowedDomains().sorted()))
        }.toString()

        @JavascriptInterface
        fun addBlockedDomain(domain: String): Int {
            val d = Dns.sanitizeLabel(domain.trim().lowercase().removeSuffix("."))
            if (d.isNotEmpty() && prefs.extraBlockedDomains().none { it == d }) {
                prefs.setExtraBlockedDomains(prefs.extraBlockedDomains() + d)
                prefs.setAllowed(d, false)
                DnsVpnService.instance?.applySettings()
            }
            return prefs.extraBlockedDomains().size
        }

        @JavascriptInterface
        fun removeBlockedDomain(domain: String): Int {
            val d = domain.trim().lowercase().removeSuffix(".")
            prefs.setExtraBlockedDomains(prefs.extraBlockedDomains().filterNot { it == d })
            DnsVpnService.instance?.applySettings()
            return prefs.extraBlockedDomains().size
        }

        @JavascriptInterface
        fun addAllowDomain(domain: String, allow: Boolean) {
            val d = Dns.sanitizeLabel(domain.trim().lowercase().removeSuffix("."))
            if (d.isEmpty()) return
            prefs.setAllowed(d, allow)
            if (allow) {
                prefs.setExtraBlockedDomains(prefs.extraBlockedDomains().filterNot { it == d })
            }
            DnsVpnService.instance?.applySettings()
        }

        @JavascriptInterface
        fun getAppIcon(pkg: String): String {
            if (pkg.isBlank()) return ""
            iconCache[pkg]?.let { return it }
            val b64 = runCatching {
                val dr = packageManager.getApplicationIcon(pkg)
                val size = 96
                val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bmp)
                dr.setBounds(0, 0, size, size)
                dr.draw(canvas)
                val bos = java.io.ByteArrayOutputStream()
                @Suppress("DEPRECATION")
                bmp.compress(android.graphics.Bitmap.CompressFormat.WEBP, 70, bos)
                android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP)
            }.getOrDefault("")
            if (b64.isNotEmpty()) iconCache[pkg] = b64
            return b64
        }

        @JavascriptInterface
        fun getBlocklist(): String = JSONObject().apply {
            put("url", prefs.blocklistUrl)
            put("count", prefs.blocklistCount)
            put("updated", prefs.blocklistUpdatedMs)
        }.toString()

        @JavascriptInterface
        fun setBlocklistUrl(url: String): Boolean {
            val clean = url.trim()
            prefs.blocklistUrl = clean
            Thread({
                val r = RemoteBlocklist.download(applicationContext, clean)
                if (r.ok) {
                    prefs.blocklistCount = r.count
                    prefs.blocklistUpdatedMs = if (clean.isEmpty()) 0L else System.currentTimeMillis()
                    DnsVpnService.instance?.applySettings()
                }
                pushToJs("__blocklist", JSONObject().apply {
                    put("ok", r.ok)
                    put("count", r.count)
                    put("error", r.error ?: "")
                    put("url", clean)
                }.toString())
            }, "jb-blocklist-dl").start()
            return true
        }

        @JavascriptInterface
        fun setTunneled(pkg: String, tunneled: Boolean) {
            prefs.setTunneled(pkg, tunneled)
            if (DnsVpnService.isTunnelRunning) DnsVpnService.instance?.restartTunnel()
        }

        @JavascriptInterface
        fun getSettings(): String = JSONObject().apply {
            put("srv", prefs.serverId)
            put("theme", prefs.theme)
            put("block", prefs.blockDomains)
            put("sinkhole", prefs.sinkhole)
            put("ipv6", prefs.blockIpv6)
            put("verify", prefs.verifyCertificate)
            put("fallback", prefs.fallbackToPlain)
            put("autostart", prefs.autoStart)
            put("log", prefs.logQueries)
            put("split", prefs.splitTunnelingEnabled)
            put("sound", prefs.soundNotify)
            put("race", prefs.raceMode)
            put("backups", JSONArray(prefs.backupIds()))
            put("pdns", runCatching {
                val mode = android.provider.Settings.Global.getString(
                    contentResolver, "private_dns_mode"
                )
                "strict".equals(mode, ignoreCase = true)
            }.getOrDefault(false))
            val proto = JSONObject()
            for (s in prefs.allServers()) prefs.protoFor(s.id)?.let { proto.put(s.id, it.name) }
            put("proto", proto)
        }.toString()

        @JavascriptInterface
        fun setSetting(key: String, value: String) {
            val on = value == "1" || value.equals("true", ignoreCase = true)
            when (key) {
                "block" -> prefs.blockDomains = on
                "sinkhole" -> prefs.sinkhole = on
                "ipv6" -> prefs.blockIpv6 = on
                "verify" -> prefs.verifyCertificate = on
                "fallback" -> prefs.fallbackToPlain = on
                "autostart" -> prefs.autoStart = on
                "log" -> prefs.logQueries = on
                "split" -> prefs.splitTunnelingEnabled = on
                "sound" -> prefs.soundNotify = on
                "race" -> prefs.raceMode = on
                else -> return
            }
            DnsVpnService.instance?.applySettings()
            if (key == "split" && DnsVpnService.isTunnelRunning) {
                DnsVpnService.instance?.restartTunnel()
            }
        }

        @JavascriptInterface
        fun setProto(serverId: String, protoId: String) {
            if (prefs.findServer(serverId) == null) return
            prefs.setProtoFor(serverId, Proto.fromId(protoId))
            DnsVpnService.instance?.applySettings()
        }

        @JavascriptInterface
        fun setCustomBlocked(text: String): Int {
            val list = text.split('\n', ',', ' ')
                .map { Dns.sanitizeLabel(it.trim().lowercase().removeSuffix(".")) }
                .filter { it.isNotBlank() }
                .distinct()
            prefs.setExtraBlockedDomains(list)
            DnsVpnService.instance?.applySettings()
            return list.size
        }

        @JavascriptInterface
        fun setTheme(theme: String) {
            val normalized = when (theme) {
                "dark", "light", "system" -> theme
                else -> "system"
            }
            prefs.theme = normalized
            runOnUiThread { (application as? JbDnsApp)?.applyTheme(normalized) }
        }

        @JavascriptInterface
        fun getCatalog(): String = JSONArray().apply {
            for (e in BlocklistCatalog.LISTS) put(JSONObject().apply {
                put("id", e.id); put("t", e.title); put("d", e.desc)
                put("count", BlocklistCatalog.count(this@MainActivity, e.id))
                put("on", prefs.catalogEnabled().contains(e.id))
            })
        }.toString()

        @JavascriptInterface
        fun setCatalog(id: String, on: Boolean) {
            prefs.setCatalog(id, on)
            DnsVpnService.instance?.applySettings()
        }

        @JavascriptInterface
        fun getRewrites(): String = JSONArray().apply {
            prefs.rewrites().forEach { (d, ip) ->
                put(JSONObject().apply { put("d", d); put("ip", ip) })
            }
        }.toString()

        @JavascriptInterface
        fun setRewrite(domain: String, ip: String, on: Boolean) {
            val cleanDomain = Dns.sanitizeLabel(domain.trim().lowercase().removeSuffix("."))
            if (cleanDomain.isBlank()) return
            prefs.setRewrite(cleanDomain, if (on) ip.trim() else null)
            DnsVpnService.instance?.applySettings()
        }

        @JavascriptInterface
        fun runHealthCheck(): Boolean {
            return try {
                benchPool.execute {
                    val server = prefs.activeServer()
                    val proto = prefs.effectiveProto(server)
                    val resolver = UpstreamFactory.create(server, prefs)
                    try {
                        val resolve: (String) -> Pair<List<String>, Long> = { domain ->
                            val started = System.nanoTime()
                            val query = Dns.buildQuery((Math.random() * 65535.0).toInt(), domain, Dns.TYPE_A)
                            val resp = resolver.resolve(query)
                            val msg = runCatching { Dns.parse(resp) }.getOrNull()
                                ?: throw IOException("پاسخ نامعتبر")
                            if (msg.rcode != Dns.RCODE_NOERROR) throw IOException(Dns.rcodeLabel(msg.rcode))
                            if (msg.addresses.isEmpty()) throw IOException("پاسخ بدون آدرس")
                            msg.addresses to ((System.nanoTime() - started) / 1_000_000)
                        }
                        val blockList = DnsVpnService.instance?.blockListNow()
                            ?: BlockList(
                                BlockList.DEFAULT_RULES +
                                    BlocklistCatalog.enabledRules(this@MainActivity, prefs.catalogEnabled()) +
                                    RemoteBlocklist.rules(this@MainActivity) +
                                    prefs.extraBlockedDomains()
                            )
                        val privateDnsStrict = runCatching {
                            val mode = android.provider.Settings.Global.getString(contentResolver, "private_dns_mode")
                            "strict".equals(mode, ignoreCase = true)
                        }.getOrDefault(false)
                        val checks = HealthCheck.run(
                            tunnelActive = DnsVpnService.isTunnelRunning,
                            protoLabel = proto.short,
                            encrypted = proto != Proto.DNS,
                            privateDnsStrict = privateDnsStrict,
                            blockEnabled = prefs.blockDomains,
                            blockContains = { blockList.contains(it) },
                            resolve = resolve
                        )
                        pushToJs("__health", JSONArray().apply {
                            for (c in checks) put(JSONObject().apply {
                                put("id", c.id); put("t", c.title)
                                put("ok", c.ok); put("d", c.detail)
                            })
                        }.toString())
                    } finally {
                        runCatching { resolver.close() }
                    }
                }
                true
            } catch (_: RejectedExecutionException) {
                false
            }
        }

        @JavascriptInterface
        fun stats24(): String {
            val s = VpnStats.dayStats()
            return JSONObject().apply {
                put("q", JSONArray().apply { s.queries.forEach { put(it) } })
                put("b", JSONArray().apply { s.blocked.forEach { put(it) } })
                put("top", JSONArray().apply {
                    s.top.forEach { (d, n) -> put(JSONObject().apply { put("d", d); put("n", n) }) }
                })
            }.toString()
        }

        @JavascriptInterface
        fun getBatterySteps(): String = BatteryWizard.stepsJson()

        @JavascriptInterface
        fun openBatteryStep(index: Int): String = BatteryWizard.open(this@MainActivity, index)

        @JavascriptInterface
        fun exportSettings(): String {
            val data = BackupData(
                serverId = prefs.serverId,
                theme = prefs.theme,
                toggles = mapOf(
                    "block" to prefs.blockDomains,
                    "sinkhole" to prefs.sinkhole,
                    "ipv6" to prefs.blockIpv6,
                    "verify" to prefs.verifyCertificate,
                    "fallback" to prefs.fallbackToPlain,
                    "autostart" to prefs.autoStart,
                    "sound" to prefs.soundNotify,
                    "log" to prefs.logQueries
                ),
                protos = prefs.allServers()
                    .mapNotNull { s -> prefs.protoFor(s.id)?.let { s.id to it.name } }
                    .toMap(),
                customServers = prefs.customServers().map { it.encode() },
                backups = prefs.backupIds(),
                allowed = prefs.allowedDomains().toList(),
                blockedExtra = prefs.extraBlockedDomains(),
                rewrites = prefs.rewrites(),
                catalog = prefs.catalogEnabled().toList(),
                raceMode = prefs.raceMode,
                splitTunneling = prefs.splitTunnelingEnabled,
                tunneledApps = prefs.tunneledPackages().toList(),
                blocklistUrl = prefs.blocklistUrl
            )
            val json = SettingsBackup.export(data)
            val dir = getExternalFilesDir(null) ?: filesDir
            val file = File(dir, "jbdns-backup.json")
            file.writeText(json)
            runOnUiThread {
                runCatching {
                    val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.fileprovider", file)
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "application/json"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(send, "پشتیبان تنظیمات JB-DNS"))
                }
            }
            return file.absolutePath
        }

        @JavascriptInterface
        fun pickBackupFile() {
            runCatching { importFile.launch(arrayOf("*/*")) }
        }

        private fun feedbackError(msg: String): String =
            JSONObject().apply { put("ok", false); put("error", msg) }.toString()

        @JavascriptInterface
        fun chatSend(json: String): Boolean {
            val o = runCatching { JSONObject(json) }.getOrNull() ?: run {
                pushToJs("__chatSend", feedbackError("قالب درخواست نامعتبر"))
                return true
            }
            val text = o.optString("text")
            Feedback.validateText(text)?.let { pushToJs("__chatSend", feedbackError(it)); return true }
            val contact = o.optString("contact")
            Feedback.validateContact(contact)?.let { pushToJs("__chatSend", feedbackError(it)); return true }

            val server = prefs.activeServer()
            val diag = if (o.optBoolean("diag", true)) Feedback.Diag(
                appVersion = BuildConfig.VERSION_NAME,
                androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                device = "${Build.MANUFACTURER} ${Build.MODEL}",
                server = server.name,
                proto = prefs.effectiveProto(server).short,
                tunnelOn = DnsVpnService.isTunnelRunning,
                race = prefs.raceMode,
                blocklists = prefs.catalogEnabled().toList(),
                queries = VpnStats.totalQueries,
                blocked = VpnStats.totalBlocked,
                lastError = VpnStats.lastError
            ) else null
            val logs = if (o.optBoolean("logs", false))
                VpnStats.snapshot().take(20)
                    .map { "${it.status.name} · ${it.domain} · ${it.ms}ms · ${it.answer.take(60)}" }
            else emptyList()

            val payload = Feedback.buildChatSend(o.optString("chat"), text, contact, diag, logs).toString()
            Thread({
                try {
                    val req = Request.Builder()
                        .url(Feedback.CHAT_SEND)
                        .header("User-Agent", "JB-DNS/${BuildConfig.VERSION_NAME}")
                        .post(payload.toRequestBody("application/json".toMediaType()))
                        .build()
                    httpClient.newCall(req).execute().use { resp ->
                        val body = resp.body?.string() ?: ""
                        val rr = runCatching { JSONObject(body) }.getOrNull()
                        if (resp.isSuccessful && rr?.optBoolean("ok") == true) {
                            pushToJs("__chatSend", JSONObject().apply {
                                put("ok", true); put("id", rr.optString("id")); put("time", rr.optString("time"))
                            }.toString())
                        } else {
                            pushToJs(
                                "__chatSend",
                                feedbackError(rr?.optString("error") ?: "خطای سرور (HTTP ${resp.code})")
                            )
                        }
                    }
                } catch (e: Exception) {
                    pushToJs("__chatSend", feedbackError("ارسال نشد: ${e.message ?: "خطای شبکه"}"))
                }
            }, "jb-dns-feedback").start()
            return true
        }

        @JavascriptInterface
        fun chatPoll(chat: String, after: String): Boolean {
            if (chat.isBlank()) return false
            val url = Feedback.CHAT_POLL +
                "?c=" + android.net.Uri.encode(chat) +
                "&after=" + android.net.Uri.encode(after)
            Thread({
                try {
                    val req = Request.Builder()
                        .url(url)
                        .header("User-Agent", "JB-DNS/${BuildConfig.VERSION_NAME}")
                        .get()
                        .build()
                    httpClient.newCall(req).execute().use { resp ->
                        val body = resp.body?.string() ?: ""
                        val rr = runCatching { JSONObject(body) }.getOrNull()
                        pushToJs("__chatPoll", rr?.toString() ?: """{"ok":false,"error":"پاسخ سرور نامعتبر"}""")
                    }
                } catch (e: Exception) {
                    pushToJs("__chatPoll", feedbackError("دریافت نشد: ${e.message ?: "خطای شبکه"}"))
                }
            }, "jb-dns-feedback").start()
            return true
        }

        @JavascriptInterface
        fun bench(serverId: String): Boolean {
            val server = prefs.findServer(serverId) ?: return false
            return try {
                benchPool.execute {
                    val result = Benchmark.run(server, prefs, rounds = 2)
                    pushToJs("__bench", benchJson(result, server))
                }
                true
            } catch (_: RejectedExecutionException) {
                false
            }
        }

        @JavascriptInterface
        fun benchAll(): Boolean {
            if (benchingAll) return false
            val servers = prefs.allServers()
            if (servers.isEmpty()) return false
            benchingAll = true
            Thread({
                try {
                    val latch = CountDownLatch(servers.size)
                    for (s in servers) {
                        try {
                            benchPool.execute {
                                try {
                                    val r = Benchmark.run(s, prefs, rounds = 1, timeoutMs = 3_000)
                                    pushToJs("__bench", benchJson(r, s))
                                } finally {
                                    latch.countDown()
                                }
                            }
                        } catch (_: RejectedExecutionException) {
                            latch.countDown()
                        }
                    }
                    latch.await(180, TimeUnit.SECONDS)
                } finally {
                    benchingAll = false
                    pushToJs("__benchDone", "1")
                }
            }, "jb-dns-bench-all").start()
            return true
        }
    }

    /**
     * اعمال یک پشتیبان روی تنظیمات؛ سرورهای سفارشی «پیش از» serverId / protos / backups
     * بازیابی می‌شوند تا ارجاع به سرورهای سفارشی کاربر از دست نرود!
     */
    private fun applyBackup(d: BackupData): Int {
        var applied = 0
        // ۱) ابتدا سرورهای سفارشی ذخیره شوند تا findServer آن‌ها را بشناسد
        d.customServers.forEach { raw ->
            Server.decode(raw)?.let { prefs.saveCustomServer(it); applied++ }
        }
        // ۲) سرور فعال و پروتکل‌ها و زنجیرهٔ پشتیبان
        d.serverId?.let { if (prefs.findServer(it) != null) { prefs.serverId = it; applied++ } }
        for ((sid, pname) in d.protos) {
            if (prefs.findServer(sid) != null) {
                prefs.setProtoFor(sid, Proto.fromId(pname)); applied++
            }
        }
        if (d.backups.isNotEmpty()) {
            prefs.setBackups(d.backups.filter { prefs.findServer(it) != null })
            applied++
        }
        // ۳) پوسته و سوییچ‌ها
        d.theme?.let {
            prefs.theme = it
            runOnUiThread { (application as? JbDnsApp)?.applyTheme(it) }
            applied++
        }
        for ((k, v) in d.toggles) {
            when (k) {
                "block" -> prefs.blockDomains = v
                "sinkhole" -> prefs.sinkhole = v
                "ipv6" -> prefs.blockIpv6 = v
                "verify" -> prefs.verifyCertificate = v
                "fallback" -> prefs.fallbackToPlain = v
                "autostart" -> prefs.autoStart = v
                "sound" -> prefs.soundNotify = v
                "log" -> prefs.logQueries = v
                else -> continue
            }
            applied++
        }
        // ۴) قواعد دامنه، بازنویسی، کاتالوگ و تونل افتراقی
        if (d.allowed.isNotEmpty()) {
            d.allowed.forEach { prefs.setAllowed(it, true) }
            applied++
        }
        if (d.blockedExtra.isNotEmpty()) {
            prefs.setExtraBlockedDomains(d.blockedExtra)
            applied++
        }
        if (d.rewrites.isNotEmpty()) {
            prefs.clearRewrites()
            d.rewrites.forEach { (dom, ip) -> prefs.setRewrite(dom, ip) }
            applied++
        }
        if (d.catalog.isNotEmpty()) {
            for (id in d.catalog) prefs.setCatalog(id, true)
            for (e in BlocklistCatalog.LISTS) if (e.id !in d.catalog) prefs.setCatalog(e.id, false)
            applied++
        }
        d.raceMode?.let { prefs.raceMode = it; applied++ }
        d.splitTunneling?.let { prefs.splitTunnelingEnabled = it; applied++ }
        if (d.tunneledApps.isNotEmpty()) {
            d.tunneledApps.forEach { prefs.setTunneled(it, true) }
            applied++
        }
        d.blocklistUrl?.let { url ->
            prefs.blocklistUrl = url
            applied++
            if (url.isNotBlank()) {
                Thread({
                    val r = RemoteBlocklist.download(applicationContext, url)
                    if (r.ok) {
                        prefs.blocklistCount = r.count
                        prefs.blocklistUpdatedMs = System.currentTimeMillis()
                        DnsVpnService.instance?.applySettings()
                    }
                }, "jb-backup-bl-dl").start()
            }
        }
        return applied
    }

    override fun onDestroy() {
        destroyed = true
        benchPool.shutdownNow()
        super.onDestroy()
    }
}
