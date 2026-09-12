# 📮 JB-DNS Feedback Worker (D1)

گیرندهٔ گزارش‌های دکمهٔ **«JB-DNS مشکلی داشت بهمون بگو!»** در اپ اندروید.
یک Cloudflare Worker با پایگاه‌دادهٔ **D1 (SQLite)** و صفحهٔ مدیریت فارسی.

## مسیرها

| متد | مسیر | کار |
|---|---|---|
| `GET` | `/` | بررسی زنده‌بودن |
| `POST` | `/report` | ثبت گزارش (محدودیت: ۵ گزارش در ساعت از هر IP) |
| `GET` | `/list?t=TOKEN` | فهرست ۵۰ گزارش اخیر (مدیر) |
| `GET` | `/one?id=…&t=TOKEN` | یک گزارش کامل (مدیر) |
| `GET` | `/admin` | صفحهٔ مرور گزارش‌ها در مرورگر (توکن می‌پرسد) |

## مدل داده (D1)

```sql
CREATE TABLE reports (
  id TEXT PRIMARY KEY, time TEXT NOT NULL, app TEXT, text TEXT NOT NULL,
  contact TEXT, diagnostics TEXT, logs TEXT, ua TEXT
);
CREATE INDEX idx_reports_time ON reports(time);
CREATE TABLE rate_limit (key TEXT PRIMARY KEY, count INTEGER NOT NULL DEFAULT 0);
```

- گزارش‌های قدیمی‌تر از **۹۰ روز** هنگام هر ثبت، خودکار حذف می‌شوند.
- محدودیت نرخ: ۵ گزارش در ساعت از هر IP — IP فقط به‌صورت **هش SHA-256**.

## دیپلوی با wrangler

```bash
npm install -g wrangler
wrangler login
cd cloudflare-worker

wrangler d1 create jbdns-feedback          # شناسه را در wrangler.toml بگذارید
wrangler d1 execute jbdns-feedback --remote --command "CREATE TABLE reports (id TEXT PRIMARY KEY, time TEXT NOT NULL, app TEXT, text TEXT NOT NULL, contact TEXT, diagnostics TEXT, logs TEXT, ua TEXT); CREATE INDEX idx_reports_time ON reports(time); CREATE TABLE rate_limit (key TEXT PRIMARY KEY, count INTEGER NOT NULL DEFAULT 0);"

wrangler secret put ADMIN_TOKEN            # رمز پنل مدیریت
wrangler deploy
```

## دیپلوی بدون wrangler (داشبورد)

1. داشبورد Cloudflare → **Workers & Pages** → **Create Worker** → نام `jbdns-feedback`
2. محتوای `worker.js` را جای‌گذاری و Deploy کنید
3. **Storage & Databases → D1** → یک database با نام `jbdns-feedback` بسازید و
   دو جدول بالا را از کنسول SQL آن اجرا کنید
4. در تنظیمات Worker: **Bindings → Add → D1** → نام متغیر `DB` → انتخاب database
5. **Settings → Variables and Secrets** → Secret با نام `ADMIN_TOKEN`
6. آدرس `https://jbdns-feedback.<زیردامنه>.workers.dev` آماده است

## نمونهٔ deployed

`https://jbdns-feedback.emam-shahrooz.workers.dev` — پنل: `/admin`

## تست

```bash
curl -X POST https://jbdns-feedback.emam-shahrooz.workers.dev/report \
  -H "Content-Type: application/json" \
  -d '{"app":"JB-DNS","text":"گزارش نمونه","diagnostics":{"appVersion":"4.1"}}'

curl "https://jbdns-feedback.emam-shahrooz.workers.dev/list?t=ADMIN_TOKEN"
```

## حریم خصوصی

- متن گزارش و راه تماس فقط با خودِ کاربر است؛ IP خام هرگز ذخیره نمی‌شود.
- اطلاعات فنی و لاگ‌ها فقط با تیک صریح کاربر در اپ ارسال می‌شوند
  (لاگ‌ها پیش‌فرض خاموش‌اند).
