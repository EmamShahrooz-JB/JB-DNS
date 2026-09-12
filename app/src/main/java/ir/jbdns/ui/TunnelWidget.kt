package ir.jbdns.ui

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.VpnService
import ir.jbdns.MainActivity
import ir.jbdns.R
import ir.jbdns.net.DnsVpnService

/**
 * ویجت صفحهٔ اصلی: دکمهٔ وصل/قطع با یک ضربه — همان منطق کاشوی تنظیمات سریع.
 * وضعیت (وصل/قطع) هر بار که تونل روشن یا خاموش می‌شود تازه می‌شود.
 */
class TunnelWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        push(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent?.action == ACTION_TOGGLE) {
            toggle(context)
        } else {
            super.onReceive(context, intent)
        }
    }

    companion object {
        const val ACTION_TOGGLE = "ir.jbdns.WIDGET_TOGGLE"

        /** منطق یک‌ضربه — قرینهٔ TunnelTile.onClick. */
        fun toggle(context: Context) {
            if (DnsVpnService.isTunnelRunning) {
                DnsVpnService.instance?.stopVpn()
            } else if (runCatching { VpnService.prepare(context) }.getOrNull() == null) {
                runCatching {
                    context.startForegroundService(Intent(context, DnsVpnService::class.java))
                }.onFailure { openApp(context) }
            } else {
                openApp(context)
            }
            // وضعیت تازه روی ویجت‌ها (تغییر واقعی ممکن است کمی بعد برسد؛
            // سرویس هم در تغییر وضعیت push می‌کند)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ push(context) }, 600)
        }

        private fun openApp(context: Context) {
            val intent = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(MainActivity.EXTRA_CONNECT, true)
            runCatching { context.startActivity(intent) }
        }

        /** تازه‌سازی ظاهر همهٔ نمونه‌های ویجت. */
        fun push(context: Context) {
            runCatching {
                val on = DnsVpnService.isTunnelRunning
                val rv = android.widget.RemoteViews(context.packageName, R.layout.widget_tunnel)
                rv.setTextViewText(R.id.wgState, if (on) "وصل" else "قطع")
                rv.setInt(R.id.wgRoot, "setBackgroundResource", if (on) R.drawable.widget_on else R.drawable.widget_off)
                val pi = PendingIntent.getBroadcast(
                    context, 7,
                    Intent(context, TunnelWidget::class.java).setAction(ACTION_TOGGLE),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                rv.setOnClickPendingIntent(R.id.wgRoot, pi)
                AppWidgetManager.getInstance(context)
                    .updateAppWidget(ComponentName(context, TunnelWidget::class.java), rv)
            }
        }
    }
}
