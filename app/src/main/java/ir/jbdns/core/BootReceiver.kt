package ir.jbdns.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import ir.jbdns.data.Prefs
import ir.jbdns.net.DnsVpnService

/**
 * در صورت فعال بودن «شروع خودکار»، تونل را پس از روشن شدن دستگاه بالا می‌آورد.
 *
 * نکته (الگوی Intra/AutoStarter): اگر مجوز VPN هنوز صادر نشده باشد،
 * VpnService.prepare مقدار غیرتهی برمی‌گرداند؛ در این حالت اصلاً سرویس را
 * بالا نمی‌آوریم چون ساخت تونل شکست می‌خورد و فقط چرخهٔ بی‌فایدهٔ
 * startForeground/stop پیشِ رو است.
 *
 * MY_PACKAGE_REPLACED: پس از به‌روزرسانی برنامه هم (مثل Intra) تونل
 * دوباره بالا می‌آید تا به‌روزرسانی، اتصال کاربر را نکشد.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            action != "android.intent.action.QUICKBOOT_POWERON"
        ) return

        val prefs = Prefs(context)
        if (!prefs.autoStart) return
        if (DnsVpnService.isTunnelRunning) return

        // اگر مجوز VPN گرفته نشده باشد، کاری نمی‌کنیم (مثل Intra)
        if (VpnService.prepare(context) != null) return

        runCatching {
            context.startForegroundService(Intent(context, DnsVpnService::class.java))
        }
    }
}
