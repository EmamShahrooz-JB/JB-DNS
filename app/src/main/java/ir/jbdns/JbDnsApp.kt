package ir.jbdns

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import ir.jbdns.data.Prefs

class JbDnsApp : Application() {

    lateinit var prefs: Prefs
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        applyTheme(prefs.theme)
    }

    fun applyTheme(theme: String) {
        prefs.theme = theme
        AppCompatDelegate.setDefaultNightMode(
            when (theme) {
                "light" -> AppCompatDelegate.MODE_NIGHT_NO
                "dark" -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }

    companion object {
        lateinit var instance: JbDnsApp
            private set
    }
}
