package ir.jbdns.core

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import ir.jbdns.MainActivity
import ir.jbdns.R
import ir.jbdns.data.Prefs
import ir.jbdns.net.DnsVpnService

/**
 * کاشی تنظیمات سریع:
 * - قطع: بی‌درنگ تونل را می‌بندد.
 * - وصل: اگر رضایت VPN قبلاً داده شده باشد تونل را «بدون باز کردن برنامه»
 *   روشن می‌کند؛ در غیر این صورت (اولین بار) برنامه را برای گفت‌وگوی مجوز
 *   باز می‌کند.
 */
class TunnelTile : TileService() {

    override fun onStartListening() {
        val tile = qsTile ?: return
        val running = DnsVpnService.isTunnelRunning
        val server = Prefs(this).activeServer()
        tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (running) getString(R.string.tile_on, server.name) else getString(R.string.app_name)
        tile.updateTile()
    }

    override fun onClick() {
        if (DnsVpnService.isTunnelRunning) {
            DnsVpnService.instance?.stopVpn()
        } else if (runCatching { VpnService.prepare(this) }.getOrNull() == null) {
            // رضایت VPN از قبل داده شده — شروع مستقیم سرویس foreground
            try {
                startForegroundService(Intent(this, DnsVpnService::class.java))
            } catch (_: Exception) {
                // بعضی نسخه‌های اندروید شروع FGS از پس‌زمینه را نمی‌پذیرند
                openAppToConnect()
            }
        } else {
            openAppToConnect()
        }
        onStartListening()
    }

    /** باز کردن برنامه با درخواست اتصال (برای گفت‌وگوی مجوز یا محدودیت سیستم). */
    private fun openAppToConnect() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_CONNECT, true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val pi = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            runCatching { startActivityAndCollapse(pi) }
        } else {
            @Suppress("DEPRECATION")
            runCatching { startActivityAndCollapse(intent) }
        }
    }
}
