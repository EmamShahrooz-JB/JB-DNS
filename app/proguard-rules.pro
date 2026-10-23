# JB-DNS: keep Android framework entrypoints & WebView JS bridge intact under R8
-keep class ir.jbdns.net.DnsVpnService { *; }
-keep class ir.jbdns.core.BootReceiver { *; }
-keep class ir.jbdns.core.TunnelTile { *; }
-keep class ir.jbdns.ui.TunnelWidget { *; }

-keepattributes JavascriptInterface,*Annotation*
-keepclassmembers class ir.jbdns.MainActivity$Bridge {
    @android.webkit.JavascriptInterface <methods>;
}

# OkHttp & BouncyCastle
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
