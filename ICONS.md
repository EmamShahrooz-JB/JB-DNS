# آیکون‌ها — همه از مخزن رسمی Google Material Design Icons

**منبع:** https://github.com/google/material-design-icons
**مجوز:** Apache License 2.0
**واریانت:** `materialiconsround` (گوشه‌گرد) در `24px`

هیچ آیکونی در این پروژه دستی طراحی نشده است. هر فایل در
`app/src/main/res/drawable/ic_*.xml` یک VectorDrawable است که `pathData` آن
**بایت‌به‌بایت** از SVG رسمی همان آیکون کپی شده و در سرآیند فایل، نشانی دقیق
منبع درج شده است.

## جدول نگاشت

| فایل پروژه | آیکون رسمی | دسته |
|---|---|---|
| `ic_power.xml` | `power_settings_new` | action |
| `ic_globe.xml` | `language` | action |
| `ic_list.xml` | `view_list` | action |
| `ic_doc.xml` | `article` | action |
| `ic_gear.xml` | `settings` | action |
| `ic_plus.xml` | `add` | content |
| `ic_delete.xml` | `delete` | action |
| `ic_check.xml` | `check` | navigation |
| `ic_bolt.xml` | `bolt` | content |
| `ic_shield.xml` | `shield` | content |
| `ic_clock.xml` | `schedule` | action |
| `ic_search.xml` | `search` | action |
| `ic_close.xml` | `close` | navigation |
| `ic_edit.xml` | `edit` | image |
| `ic_arrow_left.xml` | `arrow_back` | navigation |
| `ic_link.xml` | `link` | content |
| `ic_stat_dns.xml` | `dns` | action |

مسیر هر منبع به این شکل است:

```
src/<دسته>/<نام>/materialiconsround/24px.svg
```

مثلاً: `src/action/power_settings_new/materialiconsround/24px.svg`

## آیکون لانچر

| لایه | منبع |
|---|---|
| پس‌زمینه | گرادیان پالت Ocean Blue Serenity (رنگ، نه آیکون) |
| رویه (`ic_launcher_foreground.xml`) | گلیف رسمی `action/dns` |
| تک‌رنگ (`ic_launcher_monochrome.xml`) | گلیف رسمی `action/dns` (برای آیکون tematized اندروید ۱۳+) |
| `mipmap-*/ic_launcher.png` و `ic_launcher_round.png` | رندر همان گلیف `action/dns` برای اندروید ۷ و ۸ |

## رنگ‌آمیزی

آیکون‌ها با `android:tint="?attr/colorOnSurface"` رنگ می‌گیرند تا در هر دو
پوستهٔ تیره و روشن درست دیده شوند. تنها استثنا `ic_stat_dns.xml` است که برای
نوار اعلان با `#FFFFFFFF` ثابت است (الزام آیکون‌های status bar).

## بازتولید

برای بازسازی همهٔ آیکون‌ها از مخزن رسمی، اسکریپت تبدیل را با همان جدول
بالا اجرا کنید؛ الگوریتم به این صورت است:

1. دانلود `src/<دسته>/<نام>/materialiconsround/24px.svg` (اگر واریانت round
   نبود، `materialicons`).
2. حذف `path` مستطیل نامرئی `M0 0h24v24H0V0z` که در همهٔ آیکون‌های متریال هست.
3. نوشتن `VectorDrawable` با `viewportWidth/Height = 24` و `tint` مناسب.

## اعتبارسنجی

پس از هر تغییر، این دو بررسی انجام شود:

- `pathData` هر فایل با SVG رسمی **یکسان** باشد.
- در APK نهایی هم همان `pathData` رسمی یافت شود و هیچ مسیر دست‌سازی باقی نمانده باشد.
