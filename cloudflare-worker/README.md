# 💬 JB-DNS Support Chat Worker (D1)

پشتیبان سرورِ **«گفتگو با پشتیبانی»** اپ JB-DNS (v4.2+) — چت دوطرفهٔ
تلگرام‌مانند روی یک Cloudflare Worker با پایگاه‌دادهٔ **D1 (SQLite)**،
به‌همراه پذیرش گزارش‌های فرمی نسخهٔ 4.1 (سازگاری با APKهای قدیمی).

## مسیرها

| متد | مسیر | کار |
|---|---|---|
| `GET` | `/` | بررسی زنده‌بودن |
| `POST` | `/chat/send` | پیام کاربر `{chat,text,contact?,diagnostics?,logs?}` (۳۰ پیام/ساعت/گفتگو) |
| `GET` | `/chat/poll?c=…&after=…` | پیام‌های جدید کاربر + وضعیت خوانده‌شدن |
| `GET` | `/chats?t=TOKEN` | فهرست گفتگوها (مدیر) |
| `GET` | `/chat?id=…&t=TOKEN` | یک گفتگو کامل (مدیر — ✓✓ کاربر فعال می‌شود) |
| `POST` | `/chat/reply?t=TOKEN` | پاسخ مدیر `{chat,text}` |
| `POST` | `/report` | گزارش فرمی قدیمی v4.1 (۵ در ساعت از هر IP) |
| `GET` | `/list?t=TOKEN` · `/one?id=…&t=TOKEN` | گزارش‌های قدیمی (مدیر) |
| `GET` | `/stats?t=TOKEN` | آمار کلی: گفتگوها/پیام‌ها/خوانده‌نشده/گزارش‌ها (مدیر) |
| `GET` | `/feed?t=TOKEN` | ۴۰ پیام اخیر همهٔ گفتگوها (مدیر) |
| `GET` | `/admin` | پنل مدیریت — طرح «دروازهٔ نهان»: ورود با کلید، سایدبار، کارت‌های آمار، ۵ تم رنگی، فارسی/انگلیسی |

## مدل داده (D1)

```sql
CREATE TABLE reports (  -- فرم v4.1
  id TEXT PRIMARY KEY, time TEXT NOT NULL, app TEXT, text TEXT NOT NULL,
  contact TEXT, diagnostics TEXT, logs TEXT, ua TEXT
);
CREATE INDEX idx_reports_time ON reports(time);
CREATE TABLE rate_limit (key TEXT PRIMARY KEY, count INTEGER NOT NULL DEFAULT 0);
CREATE TABLE chats (     -- چت v4.2
  id TEXT PRIMARY KEY, created TEXT NOT NULL, last_activity TEXT NOT NULL,
  last_user TEXT, last_admin_view TEXT, contact TEXT, ua TEXT,
  unread INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE messages (
  id TEXT PRIMARY KEY, chat_id TEXT NOT NULL, time TEXT NOT NULL,
  sender TEXT NOT NULL,   -- 'user' | 'admin' | 'bot'
  text TEXT NOT NULL, meta TEXT
);
CREATE INDEX idx_messages_chat ON messages(chat_id, time);
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
