# JB-DNS

<p align="center"><img src="branding/logo.png" width="110" alt="JB-DNS logo"></p>

دی‌ان‌اس امن و پرسرعت برای اندروید — با پالت **Ocean Blue Serenity**.

> **English:** A fast, secure DNS client for Android — DoH · DoT · DNSCrypt
> with built-in ad/tracker/malware blocking, automatic server failover, race
> mode and a DNS-only tunnel that never slows the rest of your traffic.
> Built for Iran 🇮🇷, useful everywhere.

JB-DNS پرس‌وجوهای DNS گوشی را از طریق یک تونل محلی به سرور رمزنگاری‌شدهٔ دلخواه شما
می‌فرستد. **بقیهٔ ترافیک گوشی دست‌نخورده از مسیر اصلی عبور می‌کند** — یعنی برخلاف
VPNهای معمولی، سرعت اینترنت شما تغییر نمی‌کند.

---

## ✨ قابلیت‌ها

| قابلیت | توضیح |
|---|---|
| 🔐 DNS رمزنگاری‌شده | **DoH** (RFC 8484)، **DoT** (پورت ۸۵۳)، **DNSCrypt v2** و DNS کلاسیک |
| 🌍 سرورهای آماده | ۲۰+ سرور ایرانی (شکن، ۴۰۳، رادار گیم، الکترو، بگذر و…) و جهانی (Cloudflare، Google، Quad9، AdGuard، Mullvad، Control D…) + سرور سفارشی |
| 🔗 زنجیرهٔ سرور پشتیبان | قطعی سرور فعال؟ پرس‌وجوی بعدی خودکار از پشتیبان پاسخ می‌گیرد + کاوش بازیابی هر ۳۰ ثانیه |
| ⚡ حالت مسابقه (Race) | پرس‌وجوی هم‌زمان از کل زنجیره — سریع‌ترین پاسخ برنده (الگوی `lb_strategy` در dnscrypt-proxy) |
| 🗂 فهرست‌های مسدودسازی آماده | ۴ فهرست داخل برنامه (~۷٬۰۰۰ دامنه): تبلیغات، ردیاب‌ها، بدافزار، خانواده — + فهرست hosts راه دور دلخواه (Pi-hole شخصی) |
| ✏️ بازنویسی DNS | قواعد «دامنه → IP ثابت» با پاسخ فوری آفلاین (Cloaking) + بلاک وایلدکارد زیردامنه‌ها |
| 🛡 ضد-نشت DNS | رهگیری DNSهای هاردکدشدهٔ برنامه‌ها و DNSهای عمومی + هشدار حالت Strict اندروید |
| 🧪 تست سلامت داخلی | ۶ بررسی واقعی: رمزنگاری، پاسخ‌گویی، تشخیص IP صفحهٔ فیلترینگ، مسدودسازی و… |
| 📊 آمار و نمودار | نمودار ۲۴ ساعتهٔ پرس‌وجو/مسدودشده + پربارش‌ترین دامنه‌ها + لاگ زنده |
| 🚫 مسدودسازی از لاگ | مسدود/استثنا کردن هر دامنه با یک ضربه از روی لاگ زنده |
| 🔀 تونل افتراقی | عبور DNS فقط از برنامه‌های انتخابی (Split Tunneling) با آیکون واقعی اپ‌ها |
| 🔋 ویزارد باتری | گام‌به‌گام برای شیائومی/هواوی/OPPO/vivo تا سرویس بسته نشود + میان‌بر تنظیمات |
| 💾 پشتیبان‌گیری تنظیمات | خروجی/ورودی JSON همهٔ تنظیمات — انتقال به گوشی جدید |
| 💬 گفتگوی پشتیبانی | چت تلگرام‌مانند در تنظیمات — دوطرفه، با تیک ارسال/خوانده‌شدن، پیام خودکار ربات و پیوست اختیاری اطلاعات فنی/لاگ ([cloudflare-worker/](cloudflare-worker/)) |
| 📲 کاشو و ویجت | کاشوی Quick Settings (وصل بدون باز کردن برنامه) + ویجت صفحهٔ اصلی |
| 🔊 اعلان صوتی | ویس «DNS Online / DNS Offline» (قابل خاموش‌کردن) |
| 🎨 پوستهٔ تیره/روشن | رابط فارسی RTL، فونت وزیرمتن، پالت Ocean Blue Serenity |

---

## 📥 دانلود و نصب

آخرین APK امضاشده را از **[Releases](../../releases)** بگیرید — حداقل اندروید **۷٫۰ (API 24)**.

> اولین بار که دکمهٔ «اتصال» را بزنید، اندروید پنجرهٔ تأیید VPN نشان می‌دهد —
> این الزام سیستم‌عامل است و فقط برای ساخت تونل محلی DNS است.

---

## 🏗 معماری

```
برنامه‌های گوشی ──پرس‌وجوی DNS──▶ ۱۰.۲۱۰.۰.۲:۵۳ (پاسخ‌دهندهٔ محلی JB-DNS)
                                          │
                       ┌──────────────────┼──────────────────┐
                       ▼                  ▼                  ▼
                 بازنویسی DNS      فهرست مسدودسازی     حافظهٔ نهان
                 (Cloaking)        (NXDOMAIN/Sinkhole)  (با TTL)
                                          │
                                          ▼
                              سرور بالادست: DoH / DoT / DNSCrypt / DNS
                              (تکی، زنجیرهٔ پشتیبان، یا حالت مسابقه)
```

**نکتهٔ طراحی مهم:** تونل *هیچ* مسیر catch-all (`0.0.0.0/0`) اضافه نمی‌کند.
فقط آدرس پاسخ‌دهندهٔ محلی در تونل مسیر دارد، پس تنها بسته‌های DNS وارد تونل می‌شوند
و بقیهٔ ترافیک گوشی از شبکهٔ اصلی می‌رود. خودِ برنامه هم با
`addDisallowedApplication` از تونل مستثناست تا درخواست‌های بالادست حلقه نزنند.
مسیرهای ضد-نشت (الگوی NetGuard) پرس‌وجوهای مستقیم به DNSهای عمومی را هم به تونل
برمی‌گردانند.

### ساختار کد

```
app/src/main/java/ir/jbdns/
├── core/
│   ├── Dns.kt               کدک خام DNS (RFC 1035) — خالص و قابل تست روی JVM
│   ├── IpUdp.kt             لایهٔ IPv4/UDP تونل + checksum RFC 1071
│   ├── DnsStub.kt           پاسخ‌دهندهٔ محلی: بازنویسی + مسدودسازی + کش + آمار
│   ├── BlockList.kt         تطبیق دامنه/زیردامنه/وایلدکارد
│   ├── BlocklistCatalog.kt  فهرست‌های آمادهٔ داخل APK (کش درون‌فرایندی)
│   ├── Benchmark.kt         سرعت‌سنجی واقعی سرورها
│   ├── HealthCheck.kt       تست سلامت داخلی (۶ بررسی)
│   ├── BatteryWizard.kt     ویزارد باتری/اجرای خودکار سازندگان
│   ├── SettingsBackup.kt    مدل پشتیبان JSON — خالص و تست‌پذیر
│   ├── RemoteBlocklist.kt   فهرست hosts راه دور
│   └── VpnStats.kt          وضعیت، لاگ زنده و آمار ۲۴ ساعته
├── net/
│   ├── UpstreamResolvers.kt DoH • DoT • DNSCrypt • زنجیره (Chain) • مسابقه (Race) • Fallback با کلید قطع‌کن
│   ├── UpstreamFactory.kt   ساخت ریزالور از تنظیمات
│   └── DnsVpnService.kt     سرویس VPN، ضد-نشت، watchdog، اعلان پایدار
├── data/                    Server، سرورهای آماده، Prefs
└── ui/                      TunnelWidget (ویجت) — رابط کاربری: WebView + پل JS
```

رابط کاربری کامل (فارسی/RTL) یک فایل خودکفاست: `app/src/main/assets/index.html` —
بدون وابستگی خارجی (فونت و آیکون درون‌سازی‌شده) و با پل `Android.*` به کاتلین.

---

## 🔨 ساخت از سورس

### با Android Studio
پروژه را باز کنید و `Build > Build APK(s)` را بزنید.

### با خط فرمان
```bash
./setup-tools.sh                 # نصب خودکار JDK 17 + Android SDK 34 + Gradle 8.7 (یک‌بار)
./build.sh :app:testReleaseUnitTest   # اجرای تست‌ها
./build.sh :app:assembleRelease       # ساخت APK
```
یا با Gradle wrapper:
```bash
./gradlew :app:assembleDebug
```

### امضای release
فایل‌های `jbdns.keystore` و `keystore.properties` **عمداً در مخزن نیستند** (کلید امضا
هرگز عمومی نمی‌شود). برای ساخت release امضاشده، کلید خودتان را بسازید:

```bash
keytool -genkeypair -keystore jbdns.keystore -alias jbdns -keyalg RSA -keysize 2048 -validity 12000
```

و فایل `keystore.properties` را کنار `build.gradle.kts` بسازید:

```properties
storeFile=jbdns.keystore
storePassword=رمز-شما
keyAlias=jbdns
keyPassword=رمز-شما
```

بدون این فایل‌ها، بیلد release **بدون امضا** تولید می‌شود و بیلد debug همیشه کار می‌کند.

---

## ✅ تست‌ها

**۷۵ تست خودکار**، شامل تست روی شبکهٔ واقعی:

| مجموعه | تعداد | چه چیزی را می‌سنجد |
|---|---|---|
| `DnsCodecTest` | ۱۱ | ساخت/تجزیهٔ بستهٔ DNS، فشرده‌سازی اشاره‌گر، بستهٔ ناقص |
| `DnsStubTest` | ۸ | مسدودسازی، Sinkhole، کش، SERVFAIL، مسدودسازی IPv6 |
| `IpUdpTest` | ۶ | بستهٔ خام تونل، checksum، جابه‌جایی آدرس/پورت |
| `BlockListTest` | ۷ | تطبیق زیردامنه، قواعد دقیق، دامنه‌های شبیه |
| `DnscryptTest` | ۱۳ | رمزنگاری DNSCrypt v2 (X25519، XSalsa20-Poly1305) با برداشت مرجع dnscrypt-proxy |
| `LiveResolverTest` | ۶ | **DoH واقعی به Google/Quad9، DoT واقعی پورت ۸۵۳، DNS ساده، NXDOMAIN** |
| `CircuitBreakerTest` | ۳ | کلید قطع‌کن پس از شکست‌های پیاپی |
| `FailoverAndAllowTest` | ۴ | زنجیرهٔ پشتیبان + بازیابی + دامنه‌های استثنا |
| `RaceResolverTest` | ۴ | حالت مسابقه: سریع‌ترین برنده، شکست اعضا، خطای کل |
| `HealthCheckTest` | ۴ | تست سلامت: تشخیص IP فیلترینگ، رمزنگاری، خطاها |
| `SettingsBackupTest` | ۴ | رفت‌وبرگشت پشتیبان، رد فایل خراب/غیره |
| `RewriteAndWildcardTest` | ۵ | بازنویسی DNS، اولویت بر مسدودسازی، وایلدکارد |

```bash
./build.sh :app:testReleaseUnitTest
```

---

## ⚠️ نکته‌ها و محدودیت‌ها

- **DoQ (DNS-over-QUIC) پیاده‌سازی نشده** — به پشتهٔ QUIC کامل نیاز دارد؛ DoH و DoT
  هر دو رمزنگاری‌شده‌اند.
- روی بعضی شبکه‌های محدودکننده، پورت ۸۵۳ (DoT) بسته است؛ در این حالت DoH کارگشاست.
- این برنامه **پروکسی/فیلترشکن نیست**؛ فقط مسیر DNS را امن می‌کند. سرورهای ایرانی
  (شکن، ۴۰۳، …) خودشان برای رفع تحریم طراحی شده‌اند.
- سرورهای ایرانی فقط از داخل شبکهٔ ایران پاسخ می‌دهند (در فهرست برچسب خورده‌اند).

---

## ⚖️ لایسنس و اعتبارها

- کد پروژه تحت **GPL-3.0** منتشر می‌شود — فایل [LICENSE](LICENSE).
- **چرا GPL؟** فهرست‌های مسدودسازی [Disconnect](https://disconnect.me) که در
  `assets/blocklists/` بسته‌بندی شده‌اند زیر مجوز GPLv3 هستند؛ برای توزیع سازگار،
  کل پروژه هم‌مجوز آن‌ها شد (همان مسیری که NetGuard رفته است).
- منابع فهرست‌های مسدودسازی: **Disconnect** (simple_ad / simple_tracking — GPLv3) ·
  **[URLhaus](https://urlhaus.abuse.ch) by abuse.ch** (CC0) · فهرست «خانواده» دست‌گردآوری خودمان.
- آیکون‌ها: [Material Icons](https://github.com/google/material-design-icons)
  (Apache 2.0) — جزئیات کامل در **[ICONS.md](ICONS.md)**.
- الگوهای معماری با ذکر منبع در کد، از پروژه‌های
  **[Intra](https://github.com/Jigsaw-Code/intra)** (Apache 2.0) ·
  **[NetGuard](https://github.com/M66B/NetGuard)** (GPL-3.0) ·
  **[dnscrypt-proxy](https://github.com/DNSCrypt/dnscrypt-proxy)** (ISC) ·
  **[RethinkDNS](https://github.com/celzero/rethink-app)** (MPL-2.0) ·
  **[DNS66](https://github.com/julian-klode/dns66)** (GPL-3.0) الهام گرفته‌اند.

---

## 🎨 پالت رنگ

| رنگ | کد |
|---|---|
| `ocean_900` | `#03045E` |
| `ocean_800` | `#023E8A` |
| `ocean_700` | `#0077B6` |
| `ocean_600` | `#0096C7` |
| `ocean_500` | `#00B4D8` |
| `ocean_400` | `#48CAE4` |
| `ocean_300` | `#90E0EF` |
| `ocean_200` | `#ADE8F4` |
| `ocean_100` | `#CAF0F8` |

---

## 📄 مجوزهای اندروید

`INTERNET` • `ACCESS_NETWORK_STATE` • `FOREGROUND_SERVICE` • `POST_NOTIFICATIONS` • `RECEIVE_BOOT_COMPLETED`

هیچ داده‌ای از دستگاه خارج نمی‌شود؛ لاگ DNS فقط در حافظهٔ موقت است و با بستن
برنامه از بین می‌رود.

---

## 🔄 تاریخچهٔ نسخه‌ها

تغییرات کامل در **[CHANGES.md](CHANGES.md)** —
v4.0 (کاتالوگ بلاک‌لیست، نمودار، تست سلامت، مسابقه، بازنویسی، ویزارد باتری، پشتیبان‌گیری، ویجت) ·
v3.5 (زنجیرهٔ پشتیبان، کاشو، کنترل از لاگ) ·
v3.4 (ضد-نشت DNS، سرورهای مرده) ·
v3.3 (سرعت) ·
v3.2 (ویس‌ها) ·
v3.1 (تونل افتراقی) ·
v3.0 (DNSCrypt) ·
v1–2 (پایه‌گذاری).
