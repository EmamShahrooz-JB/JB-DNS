package ir.jbdns.core

/**
 * تطبیق دامنه با فهرست مسدودسازی. از زیردامنه‌ها هم پشتیبانی می‌کند:
 * قاعدهٔ «example.com» دامنه‌های «a.example.com» و «b.c.example.com» را هم می‌گیرد.
 * قاعده‌ای که با «*» شروع شود فقط همان دامنه را دقیقاً مطابقت می‌دهد.
 */
class BlockList(rules: Collection<String>) {

    private val exact: MutableSet<String> = HashSet()
    private val suffix: MutableSet<String> = HashSet()

    val size: Int get() = exact.size + suffix.size

    init {
        for (raw in rules) {
            val r = raw.trim().lowercase().removeSuffix(".")
            if (r.isEmpty() || r.startsWith("#")) continue
            if (r.startsWith("*")) {
                exact.add(r.removePrefix("*").removePrefix("."))
            } else {
                suffix.add(r)
            }
        }
    }

    fun contains(domain: String): Boolean {
        val d = domain.trim().lowercase().removeSuffix(".")
        if (d.isEmpty()) return false
        if (exact.contains(d)) return true
        if (suffix.contains(d)) return true
        var idx = d.indexOf('.')
        while (idx >= 0 && idx < d.length - 1) {
            if (suffix.contains(d.substring(idx + 1))) return true
            idx = d.indexOf('.', idx + 1)
        }
        return false
    }

    companion object {
        /** دامنه‌های تبلیغاتی و ردیاب پرکاربرد — فهرست داخلی و سبک. */
        val DEFAULT_RULES: List<String> = listOf(
            // تبلیغات موبایل و وب
            "doubleclick.net",
            "googlesyndication.com",
            "googleadservices.com",
            "googletagservices.com",
            "adservice.google.com",
            "admob.com",
            "ads.google.com",
            "pagead2.googlesyndication.com",
            "facebook.net",
            "an.facebook.com",
            "ads-twitter.com",
            "static.ads-twitter.com",
            "amazon-adsystem.com",
            "applovin.com",
            "unityads.unity3d.com",
            "vungle.com",
            "chartboost.com",
            "ironsrc.com",
            "appsflyer.com",
            "adjust.com",
            "branch.io",
            "crashlytics.com",
            "moatads.com",
            "taboola.com",
            "outbrain.com",
            "criteo.com",
            "criteo.net",
            "taboola.com",
            "smartadserver.com",
            "pubmatic.com",
            "rubiconproject.com",
            "openx.net",
            "casalemedia.com",
            "scorecardresearch.com",
            "quantserve.com",
            "adnxs.com",
            "adsafeprotected.com",
            "doubleverify.com",
            "hotjar.com",
            "mixpanel.com",
            "amplitude.com",
            "segment.io",
            "segment.com",
            "flurry.com",
            "kochava.com",
            "singular.net",
            "mobileapptracking.com",
            "startappservice.com",
            "inmobi.com",
            "mopub.com",
            "adcolony.com",
            "fyber.com",
            "smaato.net",
            "inner-active.mobi",
            "yandexadexchange.net",
            "yadro.ru",
            "mail.ru.ads",
            "popads.net",
            "popcash.net",
            "propellerads.com",
            "adcash.com",
            "zedo.com",
            "yieldmo.com",
            "bidswitch.net",
            "exoclick.com",
            "juicyads.com",
            "trafficjunky.net",
            "ero-advertising.com",
            "clickadu.com",
            "revcontent.com",
            "mgid.com",
            "nativeads.com",
            "bannersnack.com",
            "pushwoosh.com",
            "onesignal.com",
            "airpush.com",
            "tapjoy.com",
            "kiip.me",
            "adform.net",
            "mathtag.com",
            "serving-sys.com",
            "media.net",
            "contextweb.com",
            "3lift.com",
            "adroll.com",
            "bluekai.com",
            "demdex.net",
            "krxd.net",
            "rlcdn.com",
            "liadm.com",
            "tapad.com",
            "exelator.com",
            "agkn.com",
            "turn.com",
            "adsymptotic.com",
            "bttrack.com",
            "pippio.com",
            "id5-sync.com",
            "adsco.re",
            "adition.com",
            "bannerflow.com",
            "adskeeper.com",
            "adsterra.com",
            "hilltopads.net",
            "popmyads.com",
            "revenuehits.com",
            "zergnet.com",
            "sharethrough.com",
            "spotxchange.com",
            "springserve.com",
            "videoplaza.tv",
            "teads.tv",
            "imrworldwide.com",
            "nielsencollections.com"
        )
    }
}
