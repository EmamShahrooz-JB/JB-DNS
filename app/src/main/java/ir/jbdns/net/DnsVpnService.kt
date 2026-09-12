package ir.jbdns.net

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.OsConstants
import android.media.MediaPlayer
import android.util.Log
import ir.jbdns.MainActivity
import ir.jbdns.R
import ir.jbdns.core.BlockList
import ir.jbdns.core.BlocklistCatalog
import ir.jbdns.core.IpUdp
import ir.jbdns.core.DnsStub
import ir.jbdns.core.RemoteBlocklist
import ir.jbdns.core.VpnStats
import ir.jbdns.ui.TunnelWidget
import ir.jbdns.data.Prefs
import ir.jbdns.data.Server
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.Executors

/**
 * تونل VPN فقط-DNS.
 *
 * ایدهٔ کار:
 *  ۱. تونلی با آدرس ۱۰.۲۱۰.۰.۱/۲۴ می‌سازیم و ۱۰.۲۱۰.۰.۲ را به‌عنوان DNS دستگاه
 *     اعلام می‌کنیم.
 *  ۲. تنها مسیرِ افزوده‌شده، همان /۳۲ آدرس DNS محلی است؛ بنابراین فقط بسته‌های DNS
 *     وارد تونل می‌شوند و بقیهٔ ترافیک گوشی دست‌نخورده از شبکهٔ اصلی می‌رود.
 *  ۳. خودِ برنامه از تونل مستثنا می‌ماند تا درخواست‌های DoH/DoT بالادست حلقه نزنند
 *     (در حالت عادی با addDisallowedApplication؛ در حالت تونل افتراقی به‌طور طبیعی،
 *     چون فقط برنامه‌های انتخابی کاربر addAllowedApplication می‌شوند).
 *
 * نکتهٔ مهم مسیریابی: addDnsServer به‌تنهایی هیچ مسیری برای آی‌پی DNS نمی‌سازد؛
 * بدون addRoute(LOCAL_DNS_IP, 32) بسته‌های DNS هرگز وارد تونل نمی‌شوند
 * (DNS66 و NetGuard هم دقیقاً همین مسیر /32 را برای DNS جعلی اضافه می‌کنند).
 */
class DnsVpnService : VpnService() {

    private lateinit var prefs: Prefs
    private var stub: DnsStub? = null
    private var thread: Thread? = null
    @Volatile private var pool = Executors.newFixedThreadPool(WORKERS)
    @Volatile private var running = false
    /** آیا وضعیت «آنلاین» فعلاً به کاربر اعلام شده؟ (برای جلوگیری از تکرار صدا) */
    @Volatile private var announcedOnline = false
    @Volatile private var parcel: ParcelFileDescriptor? = null
    private var upstream: UpstreamResolver? = null
    private var stopReceiver: BroadcastReceiver? = null
    private val startLock = Any()
    private val restartHandler = Handler(Looper.getMainLooper())
    private val watchdogHandler = Handler(Looper.getMainLooper())
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /** زمان آخرین ری‌استارت اجباری (برای جلوگیری از حلقهٔ ری‌استارت). */
    @Volatile private var lastForcedRestartElapsed = 0L

    /** آخرین آماری که روی اعلان نوشته شده‌ایم. */
    private var lastNotifQ = -1L
    private var lastNotifB = -1L

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        VpnStats.active = true
        VpnStats.startedAt = System.currentTimeMillis()
        instance = this
        registerStopReceiver()
        registerNetworkCallback()
        VpnStats.notifyStateChanged()
    }

    /**
     * گوش دادن به تغییر شبکهٔ پیش‌فرض — الگوی NetworkManager در Intra:
     * با هر شبکهٔ جدید، سوکت‌های بالادست به شبکهٔ فعال گره می‌خورند
     * (setUnderlyingNetworks) و حافظهٔ نهان DNS خالی می‌شود؛ در غیر این صورت
     * پس از تعویض WiFi↔دادهٔ همراه، پرس‌وجوها مدت‌ها به شبکهٔ مرده می‌رفتند.
     */
    private fun registerNetworkCallback() {
        runCatching {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    runCatching { setUnderlyingNetworks(arrayOf(network)) }
                    // پاسخ‌های کش‌شدهٔ شبکهٔ قبلی دیگر معتبر نیستند
                    runCatching { stub?.clearCache() }
                    VpnStats.notifyStateChanged()
                }

                override fun onLost(network: Network) {
                    runCatching { setUnderlyingNetworks(null) }
                }
            }
            cm.registerDefaultNetworkCallback(cb)
            networkCallback = cb
        }.onFailure { Log.w(TAG, "network callback ثبت نشد: ${it.message}") }
    }

    private fun unregisterNetworkCallback() {
        networkCallback?.let { nc ->
            runCatching {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                cm.unregisterNetworkCallback(nc)
            }
        }
        networkCallback = null
    }

    // ---------------------------------------------------------- نگهبان تونل

    /**
     * نگهبان زنده‌مانی — الگوی VpnWatchdog در DNS66:
     * هر ۳۰ ثانیه بررسی می‌کند نخِ تونل هنوز زنده است؛ اگر مرده باشد
     * (کرش خاموش حلقهٔ خواندن و مانند آن) تونل خودکار بازسازی می‌شود.
     * ضمناً آمار پرس‌وجوها روی اعلان پایدار به‌روز می‌شود (الگوی Intra).
     */
    private val watchdog = object : Runnable {
        override fun run() {
            try {
                if (running) {
                    val t = thread
                    if (t == null || !t.isAlive) {
                        Log.w(TAG, "watchdog: نخ تونل زنده نیست — بازسازی خودکار")
                        restartTunnelNow()
                    } else {
                        updateNotificationStats()
                    }
                }
            } finally {
                watchdogHandler.postDelayed(this, WATCHDOG_INTERVAL_MS)
            }
        }
    }

    private fun startWatchdog() {
        watchdogHandler.removeCallbacks(watchdog)
        watchdogHandler.postDelayed(watchdog, WATCHDOG_INTERVAL_MS)
    }

    private fun stopWatchdog() {
        watchdogHandler.removeCallbacks(watchdog)
    }

    /** ری‌استارت بی‌درنگ با خنک‌کردن ۳۰ ثانیه‌ای تا حلقهٔ ری‌استارت شکل نگیرد. */
    private fun restartTunnelNow() {
        val now = SystemClock.elapsedRealtime()
        synchronized(startLock) {
            if (now - lastForcedRestartElapsed < 30_000L) return
            lastForcedRestartElapsed = now
        }
        Thread({ doRestartTunnel() }, "jb-dns-watchdog-restart").start()
    }

    /** به‌روزرسانی آمار روی اعلان پایدار (فقط وقتی اعداد عوض شده‌اند). */
    private fun updateNotificationStats() {
        val q = VpnStats.totalQueries
        val b = VpnStats.totalBlocked
        if (q == lastNotifQ && b == lastNotifB) return
        lastNotifQ = q
        lastNotifB = b
        runCatching {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(
                NOTIF_ID,
                buildNotification(VpnStats.serverLabel, VpnStats.protoLabel, statsText = getString(R.string.notif_text_stats, VpnStats.serverLabel, VpnStats.protoLabel, q, b))
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // قطع از طریق دکمهٔ اعلان — مستقیماً روی خود سرویس (مسیر مطمئن، بدون broadcast)
        if (intent?.action == ACTION_STOP) {
            stopVpn()
            return START_NOT_STICKY
        }

        val server = prefs.activeServer()
        val proto = prefs.effectiveProto(server)

        // ۱) بلافاصله Foreground می‌شویم. طبق قرارداد Android 8+ هر
        //    startForegroundService باید در پایان به startForeground برسد وگرنه
        //    «did not then call Service.startForeground» کرش می‌کند — یعنی این فراخوانی
        //    باید «قبل از» هر کاری که ممکن است شکست بخورد انجام شود (الگوی Intra).
        //    در اندروید ۱۴+ تعیین foregroundServiceType نیز الزامی است.
        startForegroundWithChannel(buildNotification(server.name, proto.short))

        // ۲) اگر تونل از قبل بالا است، فقط تنظیمات تازه اعمال می‌شود.
        //    (قبلاً هر فراخوانی دوبارهٔ onStartCommand یک fd تونل و یک نخ جدید
        //    می‌ساخت و fd قبلی نشت می‌کرد.)
        synchronized(startLock) {
            if (thread != null) {
                applySettings()
                return START_STICKY
            }
            // استخر نخ‌های قبلی در stopVpn خاموش شده؛ برای این دورِ تازه دوباره می‌سازیم
            pool = Executors.newFixedThreadPool(WORKERS)
            val t = Thread({ startTunnel() }, "jb-dns-tunnel")
            thread = t
            t.isDaemon = true
            t.start()
            startWatchdog()
        }
        return START_STICKY
    }

    /**
     * ساخت تونل روی نخ پس‌زمینه — establish() هرگز نباید روی نخ اصلی باشد
     * (الگوی Intra: startVpn در نخ جداگانه اجرا می‌شود).
     */
    /** کاشوی تنظیمات سریع و ویجت صفحهٔ اصلی را وادار به تازه‌سازی وضعیت می‌کند. */
    private fun refreshTile() {
        runCatching {
            android.service.quicksettings.TileService.requestListeningState(
                this, android.content.ComponentName(this, ir.jbdns.core.TunnelTile::class.java)
            )
        }
        runCatching { TunnelWidget.push(this) }
    }

    /**
     * اعلام صوتی وضعیت DNS با ویس‌های «DNS Online / DNS Offline».
     * فقط در تغییر واقعی وضعیت پخش می‌شود (نه در بازسازی‌های داخلی تونل)
     * و با سوییچ «اعلان صوتی» در تنظیمات قابل خاموش کردن است.
     */
    private fun announce(online: Boolean) {
        if (announcedOnline == online) return
        announcedOnline = online
        if (!prefs.soundNotify) return
        val res = if (online) R.raw.dns_online else R.raw.dns_offline
        runCatching {
            val mp = MediaPlayer.create(this, res)
            mp.setOnCompletionListener { it.release() }
            mp.setOnErrorListener { p, _, _ -> p.release(); true }
            mp.start()
        }.onFailure { Log.w(TAG, "پخش صدای وضعیت ناموفق: ${it.message}") }
    }

    private fun startTunnel() {
        try {
            val pfd = setupTunnel()
            if (pfd == null) {
                VpnStats.lastError = "ساخت تونل ممکن نشد"
                stopVpn()
                return
            }

            val server = prefs.activeServer()
            val proto = prefs.effectiveProto(server)
            // رفع باگ v3.5: زنجیرهٔ پشتیبان/مسابقه باید از همان اتصالِ تازه فعال
            // شود؛ قبلاً فقط پس از اولین تغییر تنظیمات اعمال می‌شد
            val resolver = buildResolver(server)
            upstream = resolver
            stub = DnsStub(
                upstream = resolver,
                blockList = buildBlockList(),
                blockEnabled = prefs.blockDomains,
                allowList = prefs.allowedDomains(),
                rewriteMap = prefs.rewrites(),
                sinkholeEnabled = prefs.sinkhole,
                sinkholeIp = prefs.sinkholeIp,
                logEnabled = prefs.logQueries,
                blockIpv6 = prefs.blockIpv6
            )

            VpnStats.serverLabel = server.name
            VpnStats.protoLabel = proto.short
            running = true
            VpnStats.notifyStateChanged()

            announce(online = true)
            refreshTile()

            runTunnel(pfd)

            // اگر حلقهٔ تونل به دلخواست ما تمام نشده بود (خطای غیرمنتظره)،
            // سرویسِ foregroundِ بی‌تونل باقی نماند
            if (running && thread === Thread.currentThread()) stopVpn()
        } catch (e: Exception) {
            Log.e(TAG, "startTunnel", e)
            VpnStats.lastError = e.message ?: e.javaClass.simpleName
            stopVpn()
        }
    }

    private fun setupTunnel(): ParcelFileDescriptor? {
        val b = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(1500)
            // /24 تا آدرس پاسخ‌دهندهٔ محلی در همان زیرشبکه و قابل‌مسیریابی باشد
            .addAddress(LOCAL_TUNNEL_IP, 24)
            .addDnsServer(LOCAL_DNS_IP)

        // 🔴 حیاتی: بدون این مسیر، بسته‌های مقصدِ ۱۰.۲۱۰.۰.۲ وارد تونل نمی‌شوند.
        // addAddress/addDnsServer مسیری برای DNS جعلی اضافه نمی‌کنند (DNS66 همین کار را
        // با addRoute(alias, 32) انجام می‌دهد). فقط همین /۳۲ افزوده می‌شود؛ نه مسیر
        // catch-all — پس فقط DNS وارد تونل می‌شود و بقیهٔ ترافیک از شبکهٔ اصلی می‌رود.
        b.addRoute(LOCAL_DNS_IP, 32)

        // 🛡️ ضد-نشت DNS (الگوی NetGuard/Intra): پرس‌وجوهای مستقیم به DNSهای
        // واقعی شبکه یا DNSهای عمومیِ هاردکدشده در بعضی برنامه‌ها نیز وارد تونل
        // می‌شوند و توسط همین استاب پاسخ می‌گیرند (handlePacket به هر مقصدِ
        // پورت ۵۳ پاسخ می‌دهد). بدون این مسیرها، آن پرس‌وجوها بی‌رمز از شبکهٔ
        // اصلی می‌گذشتند و در تست‌های نشت DNS دیده می‌شدند.
        val dnsTargets = LinkedHashSet<String>()
        runCatching {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            cm?.activeNetwork?.let { an ->
                cm.getLinkProperties(an)?.dnsServers?.forEach { ia ->
                    val t = ia.hostAddress
                    // فقط IPv4 — TUN ما IPv4 است و بستهٔ IPv6 قابل تفسیر نیست
                    if (t != null && !t.contains(':')) dnsTargets.add(t)
                }
            }
        }
        dnsTargets += COMMON_PUBLIC_DNS
        for (ip in dnsTargets) {
            if (ip == LOCAL_DNS_IP) continue
            runCatching { b.addRoute(ip, 32) }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // VPN نباید «متریک» تلقی شود وگرنه برنامه‌ها دادهٔ پس‌زمینه را محدود
            // می‌کنند (الگوی Intra و DNS66)
            b.setMetered(false)
        }

        // هر دو خانوادهٔ آدرس صریحاً مجاز بمانند تا ترافیک IPv6 شبکهٔ اصلی مسدود نشود
        // (تجربهٔ DNS66 — issue 129)
        runCatching { b.allowFamily(OsConstants.AF_INET) }
        runCatching { b.allowFamily(OsConstants.AF_INET6) }

        // تونل افتراقی (Split Tunneling): وقتی روشن است «فقط» برنامه‌های انتخاب‌شدهٔ
        // کاربر از تونل عبور می‌کنند (addAllowedApplication) و DNS سایر برنامه‌ها
        // مستقیم از شبکهٔ اصلی پاسخ می‌گیرد. خودِ برنامه هرگز در فهرست نیست تا
        // درخواست‌های بالادست DoH/DoT حلقه نزنند (allowed و disallowed با هم نمی‌آیند).
        val tunneled = if (prefs.splitTunnelingEnabled)
            prefs.tunneledPackages().filterTo(LinkedHashSet()) { it != packageName }
        else LinkedHashSet<String>()

        if (tunneled.isNotEmpty()) {
            for (pkg in tunneled) {
                runCatching { b.addAllowedApplication(pkg) }
                    .onFailure { Log.w(TAG, "برنامهٔ عبوری در دسترس نیست: $pkg") }
            }
        } else {
            // حالت عادی: همهٔ برنامه‌ها از تونل؛ فقط خودِ برنامه مستثنا می‌شود
            runCatching { b.addDisallowedApplication(packageName) }
        }

        runCatching { b.allowBypass() }

        return try {
            // fd مسدودکننده (الگوی DNS66): نخ خواندن تا رسیدن بسته خواب است و
            // CPU مصرف نمی‌کند؛ برای توقف، fd بسته می‌شود و read بیدار می‌شود.
            b.setBlocking(true).establish()
        } catch (e: Exception) {
            Log.e(TAG, "establish failed", e)
            null
        }
    }

    /**
     * پاسخ‌دهندهٔ محلی DNS.
     *
     * بسته‌های خام IP از فایل‌دیسکریپتر تونل خوانده می‌شوند (تونل فقط
     * پرس‌وجوهای DNS را به ما می‌دهد)، بارِ UDP بیرون کشیده می‌شود و پاسخ
     * دوباره به‌صورت بستهٔ IP در همان تونل نوشته می‌شود.
     *
     * fd مسدودکننده است: read تا رسیدن بسته می‌خوابد (صفر CPU در بیکاری).
     * برای توقف، stopVpn فایل‌دیسکریپتر را می‌بندد و readِ در انتظار، بیدار
     * و خاتمه می‌یابد — دقیقاً همان الگوی DNS66.
     */
    private fun runTunnel(tunnel: ParcelFileDescriptor) {
        parcel = tunnel
        val input = FileInputStream(tunnel.fileDescriptor)
        val output = FileOutputStream(tunnel.fileDescriptor)
        val me = Thread.currentThread()
        val buf = ByteArray(32 * 1024)
        try {
            while (running && thread === me) {
                val len = try {
                    input.read(buf)
                } catch (e: Exception) {
                    // fd بسته شده یا خطای گذرا؛ اگر هنوز باید کار کنیم ادامه می‌دهیم
                    if (!running || thread !== me) break
                    Thread.sleep(20)
                    continue
                }
                if (len <= 0) {
                    if (!running || thread !== me) break
                    Thread.sleep(20)
                    continue
                }
                val packet = buf.copyOf(len)
                runCatching { pool.execute { handlePacket(packet, len, output) } }
            }
        } catch (e: Exception) {
            Log.e(TAG, "tunnel loop", e)
            VpnStats.lastError = e.message ?: e.javaClass.simpleName
        } finally {
            runCatching { input.close() }
            runCatching { output.close() }
        }
    }

    private fun handlePacket(packet: ByteArray, len: Int, output: FileOutputStream) {
        val udp = IpUdp.parseIpv4Udp(packet, len) ?: return
        if (udp.dstPort != LOCAL_DNS_PORT) return
        val s = stub ?: return
        val result = runCatching { s.handle(udp.payload, udp.payloadLength) }.getOrNull() ?: return
        val reply = IpUdp.buildIpv4UdpReply(packet, len, result.packet.copyOf(result.length)) ?: return
        synchronized(output) {
            runCatching { output.write(reply) }
        }
    }

    /**
     * فهرست مسدودسازی = قواعد داخلی + کاتالوگ فهرست‌های آماده + فهرست راه دور (hosts)
     * + دامنه‌های دلخواه کاربر.
     */
    private fun buildBlockList(): BlockList =
        BlockList(
            BlockList.DEFAULT_RULES +
                BlocklistCatalog.enabledRules(this, prefs.catalogEnabled()) +
                RemoteBlocklist.rules(this) +
                prefs.extraBlockedDomains()
        )

    /** فهرست مسدودسازی جاری برای تست سلامت داخلی. */
    fun blockListNow(): BlockList = buildBlockList()

    /**
     * ساخت ریزالور بالادست: سرور فعال + پشتیبان‌ها.
     * - حالت مسابقه (الگوی lb_strategy در dnscrypt-proxy): همه با هم؛ سریع‌ترین پاسخ برنده.
     * - در غیر این صورت زنجیرهٔ پشتیبان با کاوش بازیابی ۳۰ ثانیه‌ای.
     */
    private fun buildResolver(server: Server): UpstreamResolver {
        val chain = ArrayList<Server>()
        chain.add(server)
        for (id in prefs.backupIds()) {
            if (id == server.id) continue
            prefs.findServer(id)?.let { s -> if (s !in chain) chain.add(s) }
        }
        if (chain.size == 1) return UpstreamFactory.create(server, prefs)

        val parts = chain.map { UpstreamFactory.create(it, prefs) }
        val onActive = fun(idx: Int) {
            val s = chain[idx]
            val p = prefs.effectiveProto(s)
            VpnStats.serverLabel = s.name
            VpnStats.protoLabel = p.short
            VpnStats.notifyStateChanged()
            runCatching {
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(NOTIF_ID, buildNotification(s.name, p.short))
            }
        }
        return if (prefs.raceMode) {
            RaceResolver(parts, onWinner = onActive)
        } else {
            ChainResolver(parts, onActiveChanged = onActive)
        }
    }

    /** اعمال تنظیمات جدید بدون قطع اتصال. */
    fun applySettings() {
        val server = prefs.activeServer()
        val proto = prefs.effectiveProto(server)
        stub?.blockList = buildBlockList()
        stub?.allowList = prefs.allowedDomains()
        stub?.blockEnabled = prefs.blockDomains
        stub?.sinkholeEnabled = prefs.sinkhole
        stub?.sinkholeIp = prefs.sinkholeIp
        stub?.logEnabled = prefs.logQueries
        stub?.blockIpv6 = prefs.blockIpv6
        stub?.clearCache()

        stub?.rewriteMap = prefs.rewrites()

        runCatching { upstream?.close() }

        // زنجیرهٔ پشتیبان یا حالت مسابقه — سرور اصلی + پشتیبان‌های کاربر؛
        // قطعی هرکدام بدون دخالت کاربر توسط بعدی جبران می‌شود (الگوی dnscrypt-proxy)
        val resolver = buildResolver(server)
        if (stub != null) {
            upstream = resolver
            stub?.upstream = resolver
        } else {
            // تونل بالا نیست؛ ریزالور بی‌استفاده را ببند
            runCatching { resolver.close() }
        }
        VpnStats.serverLabel = server.name
        VpnStats.protoLabel = proto.short
        VpnStats.notifyStateChanged()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(server.name, proto.short))
    }

    /**
     * بازسازی تونل پس از تغییر اپلیکیشن‌های مستثنا (Split Tunneling).
     * — الگوی Intra: تغییر APPS_KEY بلافاصله VPN را با پیکربندی تازه restart می‌کند.
     * تغییرات با تأخیر کوتاه ادغام می‌شوند تا چند تیک پشت‌سرهم، چند ری‌استارت نسازد.
     * خودِ بازسازی روی نخ پس‌زمینه انجام می‌شود تا نخ اصلی (و ANR) درگیر نشود.
     */
    fun restartTunnel() {
        val h = restartHandler
        synchronized(h) {
            h.removeCallbacksAndMessages(null)
            h.postDelayed({
                Thread({ doRestartTunnel() }, "jb-dns-restart").start()
            }, RESTART_DEBOUNCE_MS)
        }
    }

    private fun doRestartTunnel() {
        synchronized(startLock) {
            if (thread == null) return   // تونل روشن نیست؛ تنظیم در اتصال بعدی اعمال می‌شود
            val old = thread
            running = false
            thread = null
            // بستن fd قدیمی حلقهٔ خواندن نخ کهنه را آزاد می‌کند
            runCatching { parcel?.close() }
            runCatching { old?.join(1500) }
            runCatching { pool.shutdownNow() }
            pool = Executors.newFixedThreadPool(WORKERS)
            val t = Thread({ startTunnel() }, "jb-dns-tunnel")
            thread = t
            t.isDaemon = true
            t.start()
        }
    }

    // ------------------------------------------------------- notification

    /** startForeground با نوع صحیح سرویس برای اندروید ۱۳ به بالا (الگوی Intra). */
    private fun startForegroundWithChannel(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun buildNotification(serverName: String, proto: String, statsText: String? = null): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
                description = getString(R.string.notif_channel_desc)
            }
            nm.createNotificationChannel(channel)
        }

        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, DnsVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }

        val stopAction = Notification.Action.Builder(
            null, getString(R.string.action_disconnect), stopIntent
        ).build()

        return builder
            .setSmallIcon(R.drawable.ic_stat_dns)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(statsText ?: getString(R.string.notif_text, serverName, proto))
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setShowWhen(false)
            .setColor(getColor(R.color.ocean_700))
            .addAction(stopAction)
            .build()
    }

    private fun registerStopReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == ACTION_STOP) stopVpn()
            }
        }
        val filter = IntentFilter(ACTION_STOP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
        stopReceiver = receiver
    }

    fun stopVpn() {
        val wasOnline = running || announcedOnline
        running = false
        if (wasOnline) announce(online = false)
        refreshTile()
        stopWatchdog()
        synchronized(startLock) { thread = null }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
            else @Suppress("DEPRECATION") stopForeground(true)
        } catch (_: Exception) {
        }
        runCatching { upstream?.close() }
        runCatching { stub?.clearCache() }
        runCatching { pool.shutdownNow() }
        runCatching { parcel?.close() }
        stopSelf()
    }

    override fun onRevoke() {
        // کاربر مجوز VPN را (مثلاً از Always-on) پس گرفته است؛ مانند Intra
        // شروع خودکار را خاموش می‌کنیم تا BootReceiver در هر بوت بی‌فایده تلاش نکند.
        runCatching { if (this::prefs.isInitialized) prefs.autoStart = false }
        stopVpn()
        super.onRevoke()
    }

    /**
     * بستن برنامه از فهرست Recentها نباید تونل DNS را قطع کند.
     * سرویسِ foreground مستقل از اکتیویتی به کار خود ادامه می‌دهد
     * (مانند Intra)؛ این متد عمداً فقط برای مستندسازی override شده است.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        running = false
        stopWatchdog()
        synchronized(startLock) { thread = null }
        unregisterNetworkCallback()
        runCatching { parcel?.close() }
        runCatching { stopReceiver?.let { unregisterReceiver(it) } }
        runCatching { upstream?.close() }
        runCatching { pool.shutdownNow() }
        VpnStats.active = false
        VpnStats.notifyStateChanged()
        instance = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "DnsVpnService"
        const val CHANNEL_ID = "jb_dns_tunnel"
        const val NOTIF_ID = 1001
        const val ACTION_STOP = "ir.jbdns.action.STOP_TUNNEL"

        /** آدرس داخلی تونل. */
        const val LOCAL_TUNNEL_IP = "10.210.0.1"

        /** آدرسی که به‌عنوان DNS دستگاه معرفی می‌شود. */
        const val LOCAL_DNS_IP = "10.210.0.2"
        const val LOCAL_DNS_PORT = 53
        private const val WORKERS = 8

        /** DNSهای عمومی معروفی که بعضی برنامه‌ها هاردکد می‌کنند؛ مسیرشان به
         *  تونل می‌رود تا پاسخ را از ما بگیرند نه از شبکهٔ بی‌رمز. */
        private val COMMON_PUBLIC_DNS = listOf(
            "8.8.8.8", "8.8.4.4",             // Google
            "1.1.1.1", "1.0.0.1",             // Cloudflare
            "9.9.9.9", "149.112.112.112",     // Quad9
            "208.67.222.222", "208.67.220.220", // OpenDNS
            "77.88.8.8", "77.88.8.1",         // Yandex
            "94.140.14.14", "94.140.15.15",   // AdGuard
            "185.228.168.168", "185.228.169.168" // CleanBrowsing
        )

        /** فاصلهٔ بررسی نگهبان زنده‌مانی تونل. */
        private const val WATCHDOG_INTERVAL_MS = 30_000L

        /** ادغام تغییرات پیاپیِ فهرست اپلیکیشن‌های مستثنا پیش از ری‌استارت تونل. */
        private const val RESTART_DEBOUNCE_MS = 400L

        @Volatile
        var instance: DnsVpnService? = null
            private set

        val isTunnelRunning: Boolean get() = instance != null
    }
}
