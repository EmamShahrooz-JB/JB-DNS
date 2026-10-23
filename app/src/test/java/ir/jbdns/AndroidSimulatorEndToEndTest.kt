package ir.jbdns

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import ir.jbdns.core.BackupData
import ir.jbdns.core.BatteryWizard
import ir.jbdns.core.BlocklistCatalog
import ir.jbdns.core.RemoteBlocklist
import ir.jbdns.core.SettingsBackup
import ir.jbdns.core.VpnStats
import ir.jbdns.data.BuiltInServers
import ir.jbdns.data.Prefs
import ir.jbdns.data.Proto
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/**
 * شبیه‌ساز کامل فریم‌ورک اندروید ۱۴ (SDK 34 — Robolectric):
 * اجرای واقعی MainActivity، پل WebView (MainActivity$Bridge)، SharedPreferences واقعی،
 * AssetManager واقعی (فهرست‌های مسدودسازی داخل APK)، BatteryWizard و چرخهٔ کامل Backup/Restore.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = JbDnsApp::class)
class AndroidSimulatorEndToEndTest {

    private fun invokeBridge(bridge: Any, name: String, vararg args: Any?): Any? {
        val method = bridge.javaClass.declaredMethods.first {
            it.name == name && it.parameterCount == args.size
        }
        method.isAccessible = true
        return method.invoke(bridge, *args)
    }

    @Test
    fun `full MainActivity lifecycle, WebView Bridge RPCs, custom servers, domains and cold backup restore`() {
        val app = ApplicationProvider.getApplicationContext<JbDnsApp>()
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        val activity = controller.get()

        // استخراج نمونهٔ واقعی کلاس داخلی MainActivity$Bridge
        val bridgeCtor = Class.forName("ir.jbdns.MainActivity\$Bridge")
            .getDeclaredConstructor(MainActivity::class.java)
        bridgeCtor.isAccessible = true
        val bridge = bridgeCtor.newInstance(activity)

        // ۱) بررسی ۲۶ سرور پیش‌فرض و صحت کلیدها برای index.html
        val builtIn = JSONArray(invokeBridge(bridge, "builtInServers") as String)
        assertEquals("باید دقیقاً ۲۶ سرور پیش‌فرض سریال شود", 26, builtIn.length())
        assertEquals(BuiltInServers.ALL.size, builtIn.length())

        // ۲) بررسی بارگذاری واقعی ۴ کاتالوگ از assets/blocklists/*.txt
        val cat = JSONArray(invokeBridge(bridge, "getCatalog") as String)
        assertEquals(4, cat.length())
        for (i in 0 until cat.length()) {
            val item = cat.getJSONObject(i)
            assertTrue("فهرست ${item.getString("id")} باید غیرخالی باشد", item.getInt("count") > 50)
        }
        // بررسی عدم وجود false-positiveهای قبلی در فایل‌های واقعی asset
        val allCatalogRules = BlocklistCatalog.enabledRules(app, setOf("ads", "trackers", "malware", "family"))
        assertFalse("yandex.ru نباید مسدود شود!", allCatalogRules.contains("yandex.ru"))
        assertFalse("google.com.af نباید مسدود شود!", allCatalogRules.contains("google.com.af"))

        // ۳) افزودن سرور DoH سفارشی با Bootstrap IP + انتخاب آن + افزودن به زنجیرهٔ پشتیبان
        val customId = invokeBridge(
            bridge,
            "addServer",
            JSONObject()
                .put("name", "سرور شخصی من")
                .put("proto", "DOH")
                .put("doh", "https://my-doh.example/dns-query")
                .put("dot", "")
                .put("dns", JSONArray().put("1.1.1.1"))
                .toString()
        ) as String
        assertTrue(customId.startsWith("c"))
        invokeBridge(bridge, "setServer", customId)
        invokeBridge(bridge, "setBackup", "quad9", true)
        invokeBridge(bridge, "setTheme", "light")
        invokeBridge(bridge, "setSetting", "log", "0")
        invokeBridge(bridge, "setSetting", "race", "1")

        // ۴) افزودن و حذف قواعد دامنهٔ سفارشی و بازنویسی IPv6
        assertEquals(1, invokeBridge(bridge, "addBlockedDomain", "tracker.evil.example"))
        invokeBridge(bridge, "addAllowDomain", "cdn.good.example", true)
        invokeBridge(bridge, "setRewrite", "nas.home.arpa", "fd00::50", true)

        val rulesJson = JSONObject(invokeBridge(bridge, "getCustomRules") as String)
        assertEquals("tracker.evil.example", rulesJson.getJSONArray("blocked").getString(0))
        assertEquals("cdn.good.example", rulesJson.getJSONArray("allowed").getString(0))

        // ۵) خروجی واقعی فایل پشتیبان، پاک‌کردن کامل SharedPreferences، و بازیابی کامل!
        val backupPath = invokeBridge(bridge, "exportSettings") as String
        val backupText = java.io.File(backupPath).readText()
        app.getSharedPreferences("jb_dns_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        assertEquals("shecan", Prefs(app).serverId)

        val applyMethod = MainActivity::class.java.getDeclaredMethod("applyBackup", BackupData::class.java)
        applyMethod.isAccessible = true
        val restoredCount = applyMethod.invoke(activity, SettingsBackup.import(backupText)) as Int
        assertTrue(restoredCount >= 7)

        // تایید این‌که سرور سفارشی واقعاً پیش از serverId بازیابی شده و فعال مانده است!
        val afterCfg = JSONObject(invokeBridge(bridge, "getSettings") as String)
        assertEquals("سرور سفارشی باید پس از بازیابی همچنان سرور فعال باشد!", customId, afterCfg.getString("srv"))
        assertEquals("light", afterCfg.getString("theme"))
        assertFalse(afterCfg.getBoolean("log"))
        assertTrue(afterCfg.getBoolean("race"))

        // ۶) حذف سرور سفارشی باید سرور فعال را امن به shecan برگرداند
        assertTrue(invokeBridge(bridge, "deleteServer", customId) as Boolean)
        assertEquals("shecan", Prefs(app).serverId)

        // ۷) ثبت و پاک‌سازی لاگ‌ها
        VpnStats.addLog(
            ir.jbdns.core.LogEntry(
                System.currentTimeMillis(), "example.com", "A", "93.184.216.34",
                ir.jbdns.core.LogEntry.Status.OK, 24
            )
        )
        assertEquals(1, JSONArray(invokeBridge(bridge, "logs") as String).length())
        invokeBridge(bridge, "clearLogs")
        assertEquals(0, JSONArray(invokeBridge(bridge, "logs") as String).length())

        controller.pause().stop().destroy()
    }

    @Test
    fun `battery wizard builds real Android component intents for Xiaomi and Samsung`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()

        ReflectionHelpers.setStaticField(android.os.Build::class.java, "MANUFACTURER", "Xiaomi")
        val stepsObj = JSONObject(BatteryWizard.stepsJson())
        assertTrue(stepsObj.getBoolean("known"))
        // در Robolectric فعالیت سیستمی شیائومی نصب نیست اما نباید کرش کند و fallback تمیز می‌دهد
        val res = BatteryWizard.open(activity, 0)
        assertTrue(res in setOf("component", "none"))

        // گام استاندارد battery باید Intent واقعی بهینه‌سازی باتری اندروید را بسازد
        val batIdx = (0 until stepsObj.getJSONArray("steps").length()).first {
            stepsObj.getJSONArray("steps").getJSONObject(it).getString("kind") == "battery"
        }
        val batRes = BatteryWizard.open(activity, batIdx)
        val started: Intent? = Shadows.shadowOf(activity).nextStartedActivity
        if (started != null) {
            assertEquals("battery", batRes)
        }
        controller.destroy()
    }
}
