package ir.jbdns.core

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject

/**
 * ویزارد باتری/اجرای خودکار برای سازندگان بستهٔ سرویس‌های پس‌زمینه را
 * می‌کشند (MIUI، EMUI، ColorOS و مانند آن).
 *
 * مسیرهای دقیق تنظیمات از الگوهای شناخته‌شدهٔ پروژه‌های متن‌باز
 * (dontkillmyapp و تجربهٔ Intra/RethinkDNS) می‌آیند؛ اگر صفحهٔ مربوطه
 * روی گوشی کاربر نبود، به فهرست عمومی بهینه‌سازی باتری برمی‌گردیم.
 */
object BatteryWizard {

    class Step(val title: String, val desc: String, val kind: String) {
        companion object {
            /** صفحهٔ خاص سازنده با component مشخص. */
            const val COMPONENT = "component"
            /** فهرست عمومی بهینه‌سازی باتری اندروید. */
            const val BATTERY_LIST = "battery"
            /** فقط توضیح — دکمه‌ای ندارد. */
            const val INFO = "info"
        }
    }

    fun manufacturer(): String = (Build.MANUFACTURER ?: "").lowercase().trim()

    fun known(): Boolean {
        val m = manufacturer()
        return m.contains("xiaomi") || m.contains("redmi") || m.contains("poco") ||
            m.contains("huawei") || m.contains("honor") ||
            m.contains("oppo") || m.contains("vivo") || m.contains("oneplus") ||
            m.contains("realme") || m.contains("samsung") || m.contains("meizu")
    }

    fun steps(): List<Step> {
        val m = manufacturer()
        return when {
            m.contains("xiaomi") || m.contains("redmi") || m.contains("poco") -> listOf(
                Step(
                    "اجرای خودکار (Autostart)", "Security Center ← Manage apps ← JB-DNS ← گزینهٔ Autostart را روشن کنید",
                    "com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity"
                ),
                Step(
                    "صرفه‌جویی در باتری", "Power Keeper ← JB-DNS ← Battery Saver را روی «No Restrictions» بگذارید",
                    "com.miui.powerkeeper/com.miui.powerkeeper.ui.HiddenAppsConfigActivity"
                ),
                Step(
                    "قفل در «Recent Apps»", "در فهرست برنامه‌های اخیر، روی آیکون JB-DNS نگه دارید و 🔒 Lock را بزنید تا بسته نشود",
                    Step.INFO
                ),
                Step(
                    "حذف از بهینه‌سازی باتری اندروید", "در فهرست، JB-DNS را پیدا کنید و روی «Unrestricted / بدون محدودیت» بگذارید",
                    Step.BATTERY_LIST
                )
            )
            m.contains("huawei") || m.contains("honor") -> listOf(
                Step(
                    "اجرای خودکار", "Settings ← Apps ← JB-DNS ← Launch manager را روشن کنید (هر سه گزینه)",
                    "com.huawei.systemmanager/com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                ),
                Step(
                    "محافظت از برنامه (Protected Apps)", "Settings ← Battery ← Protected apps ← JB-DNS را روشن کنید",
                    "com.huawei.systemmanager/com.huawei.systemmanager.optimize.process.ProtectActivity"
                ),
                Step(
                    "حذف از بهینه‌سازی باتری اندروید", "در فهرست، JB-DNS را پیدا کنید و روی «Unrestricted / بدون محدودیت» بگذارید",
                    Step.BATTERY_LIST
                )
            )
            m.contains("oppo") || m.contains("vivo") || m.contains("oneplus") || m.contains("realme") -> listOf(
                Step(
                    "اجرای خودکار", "Settings ← Battery / Security ← JB-DNS ← Allow Auto-start را روشن کنید",
                    "com.coloros.safecenter/com.coloros.safecenter.permission.startup.StartupAppListActivity"
                ),
                Step(
                    "بهینه‌سازی مصرف باتری", "Settings ← Battery ← JB-DNS ← «Allow background activity» و «Don’t optimize»",
                    Step.BATTERY_LIST
                )
            )
            else -> listOf(
                Step(
                    "حذف از بهینه‌سازی باتری", "در فهرست برنامه‌ها، JB-DNS را پیدا کنید و روی «Unrestricted / بدون محدودیت» بگذارید (در سامسونگ: Battery ← Unrestricted)",
                    Step.BATTERY_LIST
                ),
                Step(
                    "نکتهٔ اضافی", "برای اطمینان، «شروع خودکار پس از روشن شدن گوشی» را در تنظیمات JB-DNS روشن نگه دارید",
                    Step.INFO
                )
            )
        }
    }

    /** گام‌ها به‌صورت JSON برای UI وب‌ویو. */
    fun stepsJson(): String = JSONObject().apply {
        put("mfr", manufacturer().ifBlank { "unknown" })
        put("known", known())
        put("steps", JSONArray().apply {
            for (s in steps()) put(JSONObject().apply {
                put("t", s.title)
                put("d", s.desc)
                put("kind", s.kind)
            })
        })
    }.toString()

    /**
     * باز کردن صفحهٔ مربوط به یک گام؛ اگر صفحهٔ سازنده نبود، فهرست عمومی
     * بهینه‌سازی باتری باز می‌شود. خروجی: نوع صفحه‌ای که واقعاً باز شد.
     */
    fun open(context: Context, index: Int): String {
        val step = steps().getOrNull(index) ?: return "none"
        // صفحهٔ خاص سازنده
        if (step.kind != Step.INFO) {
            if (step.kind == Step.COMPONENT) {
                val parts = step.kind.split('/')
                if (parts.size == 2) {
                    val intent = Intent().setComponent(ComponentName(parts[0], parts[1]))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    // صفحهٔ HiddenApps در MIUI نام بسته را به‌صورت extra می‌خواهد
                    if (parts[0] == "com.miui.powerkeeper") {
                        intent.putExtra("package_name", context.packageName)
                        intent.putExtra("package_label", "JB-DNS")
                    }
                    if (runCatching { context.startActivity(intent) }.isSuccess) return "component"
                }
            }
            // فهرست عمومی بهینه‌سازی باتری
            val battery = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(battery) }.isSuccess) return "battery"
        }
        return "none"
    }
}
