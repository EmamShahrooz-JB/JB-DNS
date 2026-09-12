# JB-DNS: keep the VPN service and receivers reachable by the framework
-keep class ir.jbdns.net.DnsVpnService { *; }
-keep class ir.jbdns.core.BootReceiver { *; }
-keep class ir.jbdns.core.TileServiceQuick { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
