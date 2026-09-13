/**
 * JB-DNS Feedback Worker — نسخهٔ ۴: چت دوطرفهٔ پشتیبانی (تلگرام‌مانند) + D1
 * ==========================================================================
 * گیرندهٔ پیام‌های بخش «گفتگو با پشتیبانی» در اپ JB-DNS (v4.2+)
 * و گزارش‌های فرمی نسخهٔ 4.1 (سازگاری با نسخه‌های قبلی).
 *
 * مسیرها:
 *   GET  /                          → سلام و بررسی زنده‌بودن
 *   ── چت (v4.2) ──
 *   POST /chat/send                 → پیام کاربر {chat,text,contact?,diagnostics?,logs?}
 *                                     (سقف ۳۰ پیام در ساعت از هر گفتگو؛ آی‌پی خام ذخیره نمی‌شود)
 *   GET  /chat/poll?c=<chatId>&after=<ISO> → پیام‌های جدید + وضعیت خوانده‌شدن
 *   GET  /chats?t=<ADMIN_TOKEN>     → فهرست گفتگوها (مدیر)
 *   GET  /chat?id=<chatId>&t=…      → یک گفتگو کامل (مدیر؛ خوانده‌شده علامت می‌خورد)
 *   POST /chat/reply?t=…            → پاسخ مدیر {chat,text}
 *   ── گزارش‌های فرمی v4.1 (سازگاری) ──
 *   POST /report                    → ثبت گزارش فرم قدیمی
 *   GET  /list?t=… / one?id=…&t=…   → گزارش‌های قدیمی (مدیر)
 *   GET  /stats?t=<ADMIN_TOKEN>     → آمار کلی (مدیر)
 *   GET  /feed?t=<ADMIN_TOKEN>      → ۴۰ پیام اخیر همهٔ گفتگوها (مدیر)
 *   GET  /admin                     → پنل مدیریت — طرح «دروازهٔ نهان»
 *
 * الزامات دیپلوی:
 *   - binding نام DB → پایگاه‌دادهٔ D1 (جدول‌های reports, rate_limit, chats, messages)
 *   - secret نام ADMIN_TOKEN
 */

const MAX_BODY = 48 * 1024;
const RATE_LIMIT_REPORT = 5;    // گزارش فرمی در ساعت از هر IP
const RATE_LIMIT_CHAT = 30;     // پیام چت در ساعت از هر گفتگو
const TTL_DAYS = 90;
const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET,POST,OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type",
  "Content-Type": "application/json; charset=utf-8",
};

const BOT_WELCOME =
  "سلام! 👋\n" +
  "این گفتگو مستقیم به سازندهٔ JB-DNS وصل است — مشکلت، پیشنهادت یا سؤالت را مثل تلگرام بفرست تا در اولین فرصت پاسخ بگیری.\n\n" +
  "📎 با دکمهٔ پیوست می‌توانی «اطلاعات فنی» و «لاگ‌های اخیر» را هم همراه پیام بفرستی (اختیاری).\n" +
  "🔒 فقط همان چیزی که خودت می‌فرستی ذخیره می‌شود.";

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.method === "OPTIONS") return new Response(null, { headers: CORS });

    try {
      if (request.method === "GET" && url.pathname === "/")
        return json({ ok: true, service: "jbdns-feedback", version: 5, storage: "d1", chat: true, time: new Date().toISOString() });

      if (request.method === "POST" && url.pathname === "/chat/send")
        return await chatSend(request, env);
      if (request.method === "GET" && url.pathname === "/chat/poll")
        return await chatPoll(url, env);

      if (request.method === "GET" && url.pathname === "/chats")
        return await adminChats(env, url);
      if (request.method === "GET" && url.pathname === "/chat")
        return await adminChat(env, url);
      if (request.method === "POST" && url.pathname === "/chat/reply")
        return await adminReply(request, env, url);
      if (request.method === "GET" && url.pathname === "/stats")
        return await adminStats(env, url);
      if (request.method === "GET" && url.pathname === "/feed")
        return await adminFeed(env, url);

      if (request.method === "POST" && url.pathname === "/report")
        return await handleReport(request, env);
      if (request.method === "GET" && url.pathname === "/list")
        return await handleList(env, url);
      if (request.method === "GET" && url.pathname === "/one")
        return await handleOne(env, url);

      if (request.method === "GET" && url.pathname === "/admin")
        return new Response(adminPage(), { headers: { "Content-Type": "text/html; charset=utf-8" } });

      return json({ ok: false, error: "مسیر ناشناخته" }, 404);
    } catch (e) {
      return json({ ok: false, error: "خطای داخلی سرور" }, 500);
    }
  },
};

/* ================= چت: ارسال پیام کاربر ================= */

async function chatSend(request, env) {
  if (!env.DB) return json({ ok: false, error: "پایگاه‌دادهٔ D1 متصل نیست" }, 500);

  let o;
  try { o = await readJson(request); } catch (e) { return json({ ok: false, error: e.message }, 400); }

  const chatId = typeof o.chat === "string" ? o.chat : "";
  if (!/^[a-f0-9]{8,64}$/.test(chatId)) return json({ ok: false, error: "شناسهٔ گفتگو نامعتبر" }, 400);

  const text = typeof o.text === "string" ? o.text.trim() : "";
  if (text.length < 5) return json({ ok: false, error: "متن گزارش کوتاه است — کمی بیشتر توضیح بده" }, 400);
  if (text.length > 4000) return json({ ok: false, error: "متن گزارش بیش از حد بلند است" }, 400);
  const contact = typeof o.contact === "string" ? o.contact.trim() : "";
  if (contact.length > 200) return json({ ok: false, error: "راه تماس بیش از حد بلند است" }, 400);

  const diagnostics = diagOrNull(o.diagnostics);       // خطا برمی‌گرداند یا null/رشتهٔ JSON
  if (diagnostics instanceof Response) return diagnostics;
  const logs = logsOrNull(o.logs);
  if (logs instanceof Response) return logs;

  // محدودیت نرخ: ۳۰ پیام در ساعت از هر گفتگو
  const hour = new Date().toISOString().slice(0, 13);
  const rlKey = `chat:${chatId}:${hour}`;
  const rl = await env.DB.prepare("SELECT count AS c FROM rate_limit WHERE key = ?").bind(rlKey).first();
  if (rl && rl.c >= RATE_LIMIT_CHAT)
    return json({ ok: false, error: "پیام‌های زیادی فرستادی — کمی صبر کن و دوباره امتحان کن" }, 429);

  const id = randomId();
  const now = new Date().toISOString();
  const ua = (request.headers.get("User-Agent") || "").slice(0, 200);
  const meta = diagnostics || logs
    ? JSON.stringify({ ...(diagnostics ? { diagnostics: JSON.parse(diagnostics) } : {}), ...(logs ? { logs: JSON.parse(logs) } : {}) })
    : null;

  const existing = await env.DB.prepare("SELECT id FROM chats WHERE id = ?").bind(chatId).first();
  const stmts = [];
  if (existing == null) {
    // خوش‌آمد ربات باید «قبل از» پیام اول کاربر باشد — زمان نسبی، نه ساعت دیوار
    const botTime = new Date(Date.parse(now) - 1).toISOString();
    stmts.push(
      env.DB.prepare(
        "INSERT INTO chats(id, created, last_activity, last_user, last_admin_view, contact, ua, unread) VALUES(?,?,?,NULL,NULL,?,?,0)"
      ).bind(chatId, botTime, now, contact || null, ua),
      env.DB.prepare(
        "INSERT INTO messages(id, chat_id, time, sender, text, meta) VALUES(?,?,?,?,?,NULL)"
      ).bind(randomId(), chatId, botTime, "bot", BOT_WELCOME)
    );
  }
  stmts.push(
    env.DB.prepare("INSERT INTO messages(id, chat_id, time, sender, text, meta) VALUES(?,?,?,?,?,?)")
      .bind(id, chatId, now, "user", text, meta),
    env.DB.prepare("INSERT INTO rate_limit(key, count) VALUES(?, 1) ON CONFLICT(key) DO UPDATE SET count = count + 1").bind(rlKey),
    env.DB.prepare("UPDATE chats SET last_activity = ?, last_user = ?, unread = unread + 1, contact = COALESCE(NULLIF(?, ''), contact), ua = COALESCE(NULLIF(?, ''), ua) WHERE id = ?")
      .bind(now, now, contact, ua, chatId)
  );
  // پاک‌سازی خودکار (۱۰٪ ارسال‌ها — هزینه را پایین نگه می‌دارد)
  if (Math.random() < 0.1) {
    const cutoff = new Date(Date.now() - TTL_DAYS * 86400 * 1000).toISOString();
    stmts.push(
      env.DB.prepare("DELETE FROM messages WHERE time < ?").bind(cutoff),
      env.DB.prepare("DELETE FROM chats WHERE last_activity < ?").bind(cutoff)
    );
  }
  await env.DB.batch(stmts);

  return json({ ok: true, id, time: now, chat: chatId });
}

/* ================= چت: دریافت پیام‌های جدید (poll) ================= */

async function chatPoll(url, env) {
  if (!env.DB) return json({ ok: false, error: "پایگاه‌دادهٔ D1 متصل نیست" }, 500);
  const chatId = (url.searchParams.get("c") || "").toLowerCase();
  if (!/^[a-f0-9]{8,64}$/.test(chatId)) return json({ ok: false, error: "شناسهٔ گفتگو نامعتبر" }, 400);
  const after = url.searchParams.get("after") || "1970-01-01T00:00:00.000Z";

  const chat = await env.DB
    .prepare("SELECT last_user, last_admin_view FROM chats WHERE id = ?").bind(chatId).first();
  if (chat == null) return json({ ok: true, messages: [], read: false, fresh: true });

  const { results } = await env.DB
    .prepare("SELECT id, time, sender, text, meta FROM messages WHERE chat_id = ? AND time > ? ORDER BY time LIMIT 200")
    .bind(chatId, after).all();

  const read = !!(chat.last_admin_view && chat.last_user && chat.last_admin_view >= chat.last_user);
  return json({
    ok: true,
    read,
    messages: results.map((m) => ({
      id: m.id, time: m.time, sender: m.sender, text: m.text,
      meta: m.meta ? JSON.parse(m.meta) : null,
    })),
  });
}

/* ================= چت: مسیرهای مدیر ================= */

async function adminChats(env, url) {
  const err = requireAdmin(env, url); if (err) return err;
  const { results } = await env.DB.prepare(
    "SELECT c.id, c.created, c.last_activity, c.contact, c.unread," +
    " (SELECT text FROM messages m WHERE m.chat_id = c.id ORDER BY m.time DESC LIMIT 1) AS preview" +
    " FROM chats c ORDER BY c.last_activity DESC LIMIT 50"
  ).all();
  return json({ ok: true, count: results.length, chats: results });
}

async function adminChat(env, url) {
  const err = requireAdmin(env, url); if (err) return err;
  const id = (url.searchParams.get("id") || "").toLowerCase();
  if (!/^[a-f0-9]{8,64}$/.test(id)) return json({ ok: false, error: "شناسهٔ گفتگو نامعتبر" }, 400);
  const chat = await env.DB.prepare("SELECT * FROM chats WHERE id = ?").bind(id).first();
  if (chat == null) return json({ ok: false, error: "گفتگو یافت نشد" }, 404);
  const { results } = await env.DB
    .prepare("SELECT id, time, sender, text, meta FROM messages WHERE chat_id = ? ORDER BY time LIMIT 500")
    .bind(id).all();
  await env.DB.prepare("UPDATE chats SET unread = 0, last_admin_view = ? WHERE id = ?")
    .bind(new Date().toISOString(), id).run();
  return json({
    ok: true,
    chat: { id: chat.id, created: chat.created, contact: chat.contact, ua: chat.ua },
    messages: results.map((m) => ({
      id: m.id, time: m.time, sender: m.sender, text: m.text,
      meta: m.meta ? JSON.parse(m.meta) : null,
    })),
  });
}

async function adminReply(request, env, url) {
  const err = requireAdmin(env, url); if (err) return err;
  let o;
  try { o = await readJson(request); } catch (e) { return json({ ok: false, error: e.message }, 400); }
  const chatId = typeof o.chat === "string" ? o.chat.toLowerCase() : "";
  if (!/^[a-f0-9]{8,64}$/.test(chatId)) return json({ ok: false, error: "شناسهٔ گفتگو نامعتبر" }, 400);
  const text = typeof o.text === "string" ? o.text.trim() : "";
  if (!text) return json({ ok: false, error: "متن پاسخ خالی است" }, 400);
  if (text.length > 4000) return json({ ok: false, error: "متن پاسخ بیش از حد بلند است" }, 400);

  const chat = await env.DB.prepare("SELECT id FROM chats WHERE id = ?").bind(chatId).first();
  if (chat == null) return json({ ok: false, error: "گفتگو یافت نشد" }, 404);

  const id = randomId();
  const now = new Date().toISOString();
  await env.DB.batch([
    env.DB.prepare("INSERT INTO messages(id, chat_id, time, sender, text, meta) VALUES(?,?,?,?,?,NULL)")
      .bind(id, chatId, now, "admin", text),
    // پاسخ دادن یعنی گفتگو دیده شده — تیک ✓✓ سمت کاربر فعال می‌شود
    env.DB.prepare("UPDATE chats SET last_activity = ?, unread = 0, last_admin_view = ? WHERE id = ?")
      .bind(now, now, chatId),
  ]);
  return json({ ok: true, id, time: now });
}

/* ================= چت: آمار و جریان پیام (پنل مدیریت) ================= */

async function adminStats(env, url) {
  const err = requireAdmin(env, url); if (err) return err;
  const chats = await env.DB.prepare("SELECT COUNT(*) AS c FROM chats").first();
  const msgs = await env.DB.prepare("SELECT COUNT(*) AS c FROM messages").first();
  const unread = await env.DB.prepare("SELECT COALESCE(SUM(unread),0) AS c FROM chats").first();
  const reps = await env.DB.prepare("SELECT COUNT(*) AS c FROM reports").first();
  const today = new Date(); today.setUTCHours(0, 0, 0, 0);
  const todayMsgs = await env.DB.prepare("SELECT COUNT(*) AS c FROM messages WHERE time >= ?")
    .bind(today.toISOString()).first();
  const { results: recent } = await env.DB.prepare(
    "SELECT c.id, c.last_activity, c.contact, c.unread," +
    " (SELECT text FROM messages m WHERE m.chat_id = c.id AND m.sender != 'bot' ORDER BY m.time DESC LIMIT 1) AS preview" +
    " FROM chats c ORDER BY c.last_activity DESC LIMIT 6"
  ).all();
  return json({ ok: true, chats: chats.c, messages: msgs.c, unread: unread.c,
    reports: reps.c, todayMessages: todayMsgs.c, recent });
}

async function adminFeed(env, url) {
  const err = requireAdmin(env, url); if (err) return err;
  const { results } = await env.DB.prepare(
    "SELECT id, chat_id, time, sender, text FROM messages ORDER BY time DESC LIMIT 40"
  ).all();
  return json({ ok: true, feed: results.reverse() });
}

/* ================= گزارش‌های فرمی v4.1 (سازگاری با APKهای قدیمی) ================= */

async function handleReport(request, env) {
  if (!env.DB) return json({ ok: false, error: "پایگاه‌دادهٔ D1 متصل نیست" }, 500);

  const ipHash = await hashIp(request.headers.get("CF-Connecting-IP") || "unknown");
  const hour = new Date().toISOString().slice(0, 13);
  const rlKey = `${ipHash}:${hour}`;
  const rl = await env.DB.prepare("SELECT count AS c FROM rate_limit WHERE key = ?").bind(rlKey).first();
  if (rl && rl.c >= RATE_LIMIT_REPORT)
    return json({ ok: false, error: "ساعتانه حداکثر ۵ گزارش — کمی بعد دوباره امتحان کن" }, 429);

  let o;
  try { o = await readJson(request); } catch (e) { return json({ ok: false, error: e.message }, 400); }

  const text = typeof o.text === "string" ? o.text.trim() : "";
  if (text.length < 5) return json({ ok: false, error: "متن گزارش کوتاه است" }, 400);
  if (text.length > 4000) return json({ ok: false, error: "متن گزارش بیش از حد بلند است" }, 400);
  let contact = typeof o.contact === "string" ? o.contact.trim() : "";
  if (contact.length > 200) return json({ ok: false, error: "راه تماس بیش از حد بلند است" }, 400);
  const diagnostics = diagOrNull(o.diagnostics);
  if (diagnostics instanceof Response) return diagnostics;
  const logs = logsOrNull(o.logs);
  if (logs instanceof Response) return logs;

  const id = randomId();
  const now = new Date().toISOString();
  const ua = (request.headers.get("User-Agent") || "").slice(0, 200);
  const cutoff = new Date(Date.now() - TTL_DAYS * 86400 * 1000).toISOString();

  await env.DB.batch([
    env.DB.prepare("INSERT INTO reports(id, time, app, text, contact, diagnostics, logs, ua) VALUES(?,?,?,?,?,?,?,?)")
      .bind(id, now, typeof o.app === "string" ? o.app.slice(0, 40) : "", text, contact || null, diagnostics, logs, ua),
    env.DB.prepare("INSERT INTO rate_limit(key, count) VALUES(?, 1) ON CONFLICT(key) DO UPDATE SET count = count + 1").bind(rlKey),
    env.DB.prepare("DELETE FROM reports WHERE time < ?").bind(cutoff),
    env.DB.prepare("DELETE FROM rate_limit WHERE substr(key, -13) < ?").bind(hour),
  ]);
  return json({ ok: true, id });
}

async function handleList(env, url) {
  const err = requireAdmin(env, url); if (err) return err;
  const { results } = await env.DB
    .prepare("SELECT id, time, substr(text, 1, 90) AS preview FROM reports ORDER BY time DESC LIMIT 50")
    .all();
  return json({ ok: true, count: results.length, reports: results });
}

async function handleOne(env, url) {
  const err = requireAdmin(env, url); if (err) return err;
  const id = (url.searchParams.get("id") || "").replace(/[^a-z0-9]/gi, "");
  if (!id) return json({ ok: false, error: "شناسه لازم است" }, 400);
  const row = await env.DB.prepare("SELECT * FROM reports WHERE id = ?").bind(id).first();
  if (row == null) return json({ ok: false, error: "یافت نشد یا منقضی شده" }, 404);
  return json({
    id: row.id, time: row.time, app: row.app, text: row.text, contact: row.contact,
    diagnostics: row.diagnostics ? JSON.parse(row.diagnostics) : null,
    logs: row.logs ? JSON.parse(row.logs) : null, ua: row.ua,
  });
}

/* ================= ابزار مشترک ================= */

async function readJson(request) {
  if ((request.headers.get("Content-Length") || 0) > MAX_BODY) throw new Error("گزارش بیش از حد بزرگ است");
  const raw = await request.text();
  if (raw.length > MAX_BODY) throw new Error("گزارش بیش از حد بزرگ است");
  try { return JSON.parse(raw); } catch { throw new Error("قالب نامعتبر"); }
}

function diagOrNull(d) {
  if (d == null) return null;
  const s = JSON.stringify(d);
  if (s.length > 8192) return json({ ok: false, error: "اطلاعات فنی بیش از حد بزرگ است" }, 400);
  return s;
}

function logsOrNull(arr) {
  if (arr == null) return null;
  if (!Array.isArray(arr)) return null;
  if (arr.length > 30) return json({ ok: false, error: "تعداد لاگ بیش از حد مجاز است" }, 400);
  for (const l of arr) if (typeof l === "string" && l.length > 300)
    return json({ ok: false, error: "یکی از لاگ‌ها بیش از حد بلند است" }, 400);
  return JSON.stringify(arr);
}

function requireAdmin(env, url) {
  const t = url.searchParams.get("t") || "";
  if (!env.ADMIN_TOKEN || !t || t !== env.ADMIN_TOKEN)
    return json({ ok: false, error: "دسترسی مدیریتی لازم است" }, 401);
  return null;
}

async function hashIp(ip) {
  const buf = await crypto.subtle.digest("SHA-256", new TextEncoder().encode("jbdns:" + ip));
  return [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, "0")).join("").slice(0, 32);
}

function randomId() {
  const a = new Uint8Array(6);
  crypto.getRandomValues(a);
  return [...a].map((b) => b.toString(16).padStart(2, "0")).join("");
}

function json(obj, status = 200) {
  return new Response(JSON.stringify(obj), { status, headers: CORS });
}

/* ================= پنل مدیریت (طرح «دروازهٔ نهان») ================= */

function adminPage() {
  return `<!doctype html><html lang="fa" dir="rtl" class="dark">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no, viewport-fit=cover">
<meta name="theme-color" content="#090d16">
<meta name="apple-mobile-web-app-capable" content="yes">
<meta name="apple-mobile-web-app-status-bar-style" content="black-translucent">
<meta name="apple-mobile-web-app-title" content="JB-DNS">
<meta name="format-detection" content="telephone=no">
<title>پنل پشتیبانی JB-DNS</title>
<style>
:root{--color-text:#0f172a;--color-text-muted:#475569;--color-primary:#6366f1;--color-primary-hover:#4f46e5;
  --color-bg:#f8fafc;--color-surface:#ffffff;--color-surface2:#f1f5f9;--color-border:#e2e8f0;
  --color-good:#16a34a;--color-bad:#e11d48;--color-amber:#d97706}
html.dark{--color-text:#f1f5f9;--color-text-muted:#cbd5e1;--color-primary:#818cf8;--color-primary-hover:#6366f1;
  --color-bg:#090d16;--color-surface:#0f172a;--color-surface2:#151b26;--color-border:#222b3c}
html[data-theme="ocean"]{--color-primary:#0ea5e9;--color-primary-hover:#0284c7;--color-bg:#f0f9ff;--color-surface:#fff;
  --color-surface2:#e0f2fe;--color-border:#bae6fd;--color-text:#082f49;--color-text-muted:#0369a1}
html[data-theme="ocean"].dark{--color-primary:#38bdf8;--color-primary-hover:#0ea5e9;--color-bg:#031e30;
  --color-surface:#072f49;--color-surface2:#0c4a6e;--color-border:#0c4a6e;--color-text:#e0f2fe;--color-text-muted:#7dd3fc}
html[data-theme="forest"]{--color-primary:#10b981;--color-primary-hover:#059669;--color-bg:#f0fdf4;--color-surface:#fff;
  --color-surface2:#dcfce7;--color-border:#bbf7d0;--color-text:#022c22;--color-text-muted:#15803d}
html[data-theme="forest"].dark{--color-primary:#34d399;--color-primary-hover:#10b981;--color-bg:#021a11;
  --color-surface:#042f1d;--color-surface2:#064e3b;--color-border:#064e3b;--color-text:#dcfce7;--color-text-muted:#86efac}
html[data-theme="sunset"]{--color-primary:#f43f5e;--color-primary-hover:#e11d48;--color-bg:#fff5f5;--color-surface:#fff;
  --color-surface2:#ffe3e3;--color-border:#ffc9c9;--color-text:#4c0519;--color-text-muted:#be123c}
html[data-theme="sunset"].dark{--color-primary:#fb7185;--color-primary-hover:#f43f5e;--color-bg:#2a0a14;
  --color-surface:#3d1122;--color-surface2:#55182d;--color-border:#5c1a2b;--color-text:#ffe4e6;--color-text-muted:#fda4af}
html[data-theme="dracula"],html[data-theme="dracula"].dark{--color-primary:#bd93f9;--color-primary-hover:#b197f7;
  --color-bg:#282a36;--color-surface:#343746;--color-surface2:#3b3d4f;--color-border:#44475a;
  --color-text:#f8f8f2;--color-text-muted:#b6b8c8;--color-good:#50fa7b;--color-bad:#ff5555}
*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
html,body{height:100%}
body{margin:0;font-family:Vazirmatn,'Segoe UI',Tahoma,sans-serif;background:var(--color-bg);color:var(--color-text);
  overflow:hidden}
::-webkit-scrollbar{width:6px;height:6px}
::-webkit-scrollbar-thumb{background:var(--color-border);border-radius:3px}
.fade-in{animation:fadeIn .3s ease-out}
@keyframes fadeIn{from{opacity:0;transform:translateY(8px)}to{opacity:1;transform:none}}
/* ───── login ───── */
.login-box{position:absolute;inset:0;display:flex;align-items:center;justify-content:center;padding:16px;z-index:40;overflow:hidden;
  background:linear-gradient(135deg,var(--color-bg) 0%,var(--color-surface2) 50%,var(--color-bg) 100%)}
.glow{position:absolute;pointer-events-none;border-radius:9999px;width:500px;height:500px;top:-100px;left:-150px;
  background:radial-gradient(circle,var(--color-primary) 0%,transparent 65%);opacity:.12}
.glow.b{width:400px;height:400px;top:auto;left:auto;bottom:-80px;right:-100px;opacity:.1}
.login-card{position:relative;width:100%;max-width:384px;text-align:center}
.lock-pulse{position:relative;width:80px;height:80px;border-radius:24px;margin:0 auto 20px;display:flex;align-items:center;justify-content:center;
  background:linear-gradient(145deg,color-mix(in srgb,var(--color-primary) 25%,transparent),color-mix(in srgb,var(--color-primary) 8%,transparent));
  border:1px solid color-mix(in srgb,var(--color-primary) 45%,transparent);
  box-shadow:0 0 40px color-mix(in srgb,var(--color-primary) 25%,transparent),inset 0 1px 0 rgba(255,255,255,.08);
  animation:lockpulse 2.4s ease-in-out infinite}
@keyframes lockpulse{0%,100%{box-shadow:0 0 24px color-mix(in srgb,var(--color-primary) 18%,transparent)}50%{box-shadow:0 0 52px color-mix(in srgb,var(--color-primary) 34%,transparent)}}
.login-card h1{font-size:26px;font-weight:900;margin:0 0 6px}
.login-sub{display:flex;align-items:center;justify-content:center;gap:8px;color:var(--color-text-muted);font-size:12.5px;font-weight:700;margin-bottom:26px}
.dotg{width:8px;height:8px;border-radius:50%;background:var(--color-good);box-shadow:0 0 8px var(--color-good)}
.err-msg{color:var(--color-bad);font-size:12px;font-weight:700;min-height:18px;margin-top:12px}
/* ───── inputs/buttons ───── */
.inp{width:100%;padding:13px 16px;border-radius:14px;border:1.5px solid var(--color-border);background:var(--color-surface);
  color:var(--color-text);font-family:inherit;font-size:14px;outline:none;transition:border .2s;text-align:start}
.inp:focus{border-color:var(--color-primary)}
.btn{display:inline-flex;align-items:center;justify-content:center;gap:8px;padding:12px 22px;border-radius:14px;border:none;
  font-family:inherit;font-size:13.5px;font-weight:800;cursor:pointer;transition:.2s;background:var(--color-surface2);color:var(--color-text)}
.btn:hover{filter:brightness(1.08)}
.btn:active{transform:scale(.97)}
.btn-primary{background:var(--color-primary);color:#fff;box-shadow:0 6px 18px color-mix(in srgb,var(--color-primary) 35%,transparent)}
.btn-danger{background:color-mix(in srgb,var(--color-bad) 12%,transparent);color:var(--color-bad)}
.btn-block{width:100%}
.sel{appearance:none;width:100%;padding:12px 16px;border-radius:14px;border:1.5px solid var(--color-border);
  background:var(--color-surface2);color:var(--color-text);font-family:inherit;font-size:13.5px;font-weight:700;outline:none;cursor:pointer}
.lbl{display:block;font-size:13px;font-weight:800;color:var(--color-text-muted);margin:0 4px 7px}
.field{margin-bottom:18px}
/* ───── layout ───── */
.app{display:none;height:100vh;height:100dvh;width:100%}
.app.on{display:flex}
.sidebar{display:flex;width:256px;background:var(--color-surface);border-inline-end:1px solid var(--color-border);
  flex-direction:column;z-index:20;flex-shrink:0}
.side-head{display:flex;align-items:center;gap:11px;padding:20px 18px 16px}
.logo{width:42px;height:42px;border-radius:14px;flex-shrink:0;display:flex;align-items:center;justify-content:center;
  color:#fff;font-weight:900;font-size:19px;background:linear-gradient(145deg,var(--color-primary),var(--color-primary-hover));
  box-shadow:0 8px 20px color-mix(in srgb,var(--color-primary) 38%,transparent)}
.side-name{font-size:14.5px;font-weight:900;line-height:1.3}
.badge-ver{display:inline-block;font-size:9.5px;font-weight:800;color:var(--color-primary);background:color-mix(in srgb,var(--color-primary) 12%,transparent);
  border:1px solid color-mix(in srgb,var(--color-primary) 30%,transparent);border-radius:99px;padding:1px 9px;margin-top:3px}
.nav{flex:1;padding:8px;overflow-y:auto;display:flex;flex-direction:column;gap:2px}
.nav-item{display:flex;align-items:center;gap:12px;width:100%;padding:12px 16px;border:none;border-radius:12px;background:none;
  color:var(--color-text-muted);font-family:inherit;font-size:13.5px;font-weight:700;cursor:pointer;transition:.18s;text-align:start}
.nav-item svg{width:22px;height:22px;flex-shrink:0}
.nav-item:hover{color:var(--color-text);background:var(--color-surface2)}
.nav-item.active{color:var(--color-primary);background:color-mix(in srgb,var(--color-primary) 11%,transparent);
  box-shadow:inset 0 0 0 1px color-mix(in srgb,var(--color-primary) 22%,transparent)}
.side-foot{padding:12px;border-top:1px solid var(--color-border);display:flex;flex-direction:column;gap:2px}
.side-btn{display:flex;align-items:center;gap:12px;width:100%;padding:10px 14px;border:none;border-radius:12px;background:none;
  color:var(--color-text-muted);font-family:inherit;font-size:12.5px;font-weight:800;cursor:pointer;transition:.18s;text-align:start}
.side-btn svg{width:19px;height:19px}
.side-btn:hover{color:var(--color-text);background:var(--color-surface2)}
.side-btn.out:hover{color:var(--color-bad);background:color-mix(in srgb,var(--color-bad) 10%,transparent)}
.main{flex:1;display:flex;flex-direction:column;height:100%;min-width:0;overflow:hidden}
.hdr{height:56px;flex-shrink:0;display:flex;align-items:center;padding:0 16px;z-index:10;background:var(--color-surface);
  backdrop-filter:blur(12px);border-bottom:1px solid var(--color-border)}
@media(min-width:768px){.hdr{height:88px;padding:0 38px}}
.hdr h2{font-size:18px;font-weight:900;margin:0;color:var(--color-text)}
@media(min-width:768px){.hdr h2{font-size:29px}}
.scroll-content{flex:1;overflow-y:auto;padding:16px}
@media(min-width:768px){.scroll-content{padding:38px}}
.inner{max-width:896px;margin:0 auto}
.view{display:none}
.view.on{display:block}
/* ───── stat cards ───── */
.grid-stats{display:grid;grid-template-columns:repeat(2,1fr);gap:10px}
@media(min-width:640px){.grid-stats{grid-template-columns:repeat(3,1fr)}}
@media(min-width:1024px){.grid-stats{grid-template-columns:repeat(5,1fr);gap:15px}}
.stat-card{background:var(--color-surface);border-radius:16px;padding:14px;border:1px solid var(--color-border);
  box-shadow:0 1px 3px rgba(0,0,0,.06);cursor:pointer;transition:.18s}
.stat-card:hover{transform:translateY(-2px);box-shadow:0 8px 22px color-mix(in srgb,var(--color-primary) 14%,transparent)}
.stat-top{display:flex;align-items:center;justify-content:space-between;margin-bottom:8px}
.stat-label{font-size:9.5px;font-weight:800;color:var(--color-text-muted);text-transform:uppercase;letter-spacing:.6px}
.stat-ico{padding:7px;border-radius:10px;background:color-mix(in srgb,var(--color-primary) 11%,transparent);color:var(--color-primary);
  display:flex;align-items:center;justify-content:center}
.stat-ico svg{width:16px;height:16px}
.stat-num{font-size:22px;font-weight:900;margin:0}
@media(min-width:768px){.stat-num{font-size:26px}}
/* ───── list cards ───── */
.card{background:var(--color-surface);border-radius:20px;border:1px solid var(--color-border);overflow:hidden;margin-top:22px}
.card-hd{display:flex;align-items:center;justify-content:space-between;padding:15px 18px;border-bottom:1px solid var(--color-border)}
.card-hd h3{margin:0;font-size:14px;font-weight:900}
.card-hd .cnt{font-size:11px;font-weight:800;color:var(--color-primary);background:color-mix(in srgb,var(--color-primary) 11%,transparent);
  padding:2px 10px;border-radius:99px}
.lrow{display:flex;align-items:center;gap:12px;padding:13px 18px;border:none;border-bottom:1px solid var(--color-border);
  background:none;cursor:pointer;font-family:inherit;text-align:start;width:100%;transition:.15s}
.lrow:last-child{border-bottom:none}
.lrow:hover{background:var(--color-surface2)}
.lrow-av{width:38px;height:38px;border-radius:12px;flex-shrink:0;display:flex;align-items:center;justify-content:center;font-size:16px;
  background:color-mix(in srgb,var(--color-primary) 11%,transparent)}
.lrow-main{flex:1;min-width:0}
.lrow-t{font-size:12.5px;font-weight:800;direction:ltr;text-align:start;unicode-bidi:embed}
.lrow-p{font-size:11.5px;color:var(--color-text-muted);margin-top:2px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.lrow-time{font-size:9.5px;color:var(--color-text-muted);flex-shrink:0;direction:ltr}
.badge-unread{background:var(--color-bad);color:#fff;border-radius:99px;font-size:10px;font-weight:800;padding:2px 8px;flex-shrink:0}
.empty{padding:36px 20px;text-align:center;color:var(--color-text-muted);font-size:12.5px;line-height:2.2}
.searchbar{margin-top:22px}
/* ───── chat modal ───── */
.modal-back{position:fixed;inset:0;z-index:50;background:rgba(2,6,20,.6);backdrop-filter:blur(6px);
  display:none;align-items:center;justify-content:center;padding:14px}
.modal-back.on{display:flex}
.modal{width:100%;max-width:560px;max-height:88vh;display:flex;flex-direction:column;background:var(--color-surface);
  border-radius:24px;border:1px solid var(--color-border);box-shadow:0 30px 90px rgba(0,0,0,.4);overflow:hidden;animation:fadeIn .25s ease-out}
.modal-hd{display:flex;align-items:center;gap:11px;padding:14px 18px;border-bottom:1px solid var(--color-border);flex-shrink:0}
.modal-hd .x{margin-inline-start:auto}
.modal-bd{flex:1;overflow-y:auto;padding:16px;display:flex;flex-direction:column;gap:4px;min-height:180px}
.modal-ft{display:flex;gap:9px;padding:12px 14px;border-top:1px solid var(--color-border);flex-shrink:0;
  padding-bottom:calc(12px + env(safe-area-inset-bottom,0px))}
.modal-ft textarea{flex:1;resize:none;max-height:90px;min-height:46px;border-radius:14px;border:1.5px solid var(--color-border);
  background:var(--color-surface2);color:var(--color-text);padding:12px 14px;font-family:inherit;font-size:13px;outline:none;line-height:1.8}
.modal-ft textarea:focus{border-color:var(--color-primary)}
.msg{max-width:80%;padding:9px 13px 5px;border-radius:15px;font-size:13px;line-height:1.95;white-space:pre-wrap;word-break:break-word}
.msg-user{align-self:flex-start;background:var(--color-surface2);border-bottom-right-radius:5px}
.msg-admin{align-self:flex-end;color:#fff;background:linear-gradient(135deg,var(--color-primary),var(--color-primary-hover));border-bottom-left-radius:5px}
.msg-bot{align-self:center;background:none;border:1.5px dashed var(--color-border);color:var(--color-text-muted);font-size:11px}
.msg-foot{font-size:8.5px;opacity:.75;margin-top:3px;direction:ltr;text-align:left}
.chatatt{background:rgba(0,0,0,.16);border-radius:10px;padding:6px 9px;margin-top:6px;font-size:10.5px}
.chatatt summary{cursor:pointer;font-weight:800}
.chatatt .d{direction:ltr;text-align:left;font-size:8.5px;white-space:pre-wrap;margin-top:5px;opacity:.85}
.pre{direction:ltr;text-align:left;font-size:11px;white-space:pre-wrap;background:var(--color-surface2);
  border-radius:14px;padding:14px;margin:0;line-height:1.9;font-family:ui-monospace,monospace}
/* ───── feed/help/health ───── */
.frow{display:flex;gap:11px;padding:11px 18px;border-bottom:1px solid var(--color-border);font-size:12px;align-items:flex-start}
.frow:last-child{border-bottom:none}
.chip{font-size:9.5px;font-weight:800;border-radius:99px;padding:2px 9px;flex-shrink:0}
.chip-user{background:color-mix(in srgb,var(--color-primary) 12%,transparent);color:var(--color-primary)}
.chip-admin{background:color-mix(in srgb,var(--color-good) 13%,transparent);color:var(--color-good)}
.chip-bot{background:var(--color-surface2);color:var(--color-text-muted)}
.frow-time{font-size:9px;color:var(--color-text-muted);direction:ltr;flex-shrink:0;margin-top:2px}
.hcard{background:var(--color-surface);border-radius:20px;border:1px solid var(--color-border);padding:20px;margin-top:16px}
.hcard h4{margin:0 0 8px;font-size:14px;font-weight:900}
.hcard p{margin:0;font-size:12.5px;line-height:2.1;color:var(--color-text-muted)}
.hcard code{background:var(--color-surface2);border-radius:7px;padding:1px 7px;font-size:11px;direction:ltr;unicode-bidi:embed}
.kv{display:flex;justify-content:space-between;align-items:center;padding:12px 18px;border-bottom:1px solid var(--color-border);font-size:12.5px}
.kv:last-child{border-bottom:none}
.kv b{font-weight:900}
.ok-badge{color:var(--color-good);font-weight:800;font-size:12px;display:flex;align-items:center;gap:6px}
/* ───── mobile nav ───── */
.mob-nav{display:none;position:fixed;bottom:0;left:0;right:0;z-index:30;height:calc(60px + env(safe-area-inset-bottom,0px));
  padding-bottom:env(safe-area-inset-bottom,0px);background:var(--color-surface);border-top:1px solid var(--color-border);
  justify-content:space-around;align-items:center}
.mob-item{display:flex;flex-direction:column;align-items:center;gap:3px;border:none;background:none;color:var(--color-text-muted);
  font-family:inherit;font-size:9px;font-weight:800;cursor:pointer;padding:7px 12px;border-radius:12px}
.mob-item svg{width:21px;height:21px}
.mob-item.active{color:var(--color-primary)}
@media(max-width:767px){.sidebar{display:none}.mob-nav{display:flex}.scroll-content{padding-bottom:76px}}
/* ───── toast ───── */
.toast{position:fixed;bottom:22px;left:50%;transform:translateX(-50%) translateY(80px);z-index:60;background:var(--color-surface);
  border:1px solid var(--color-border);color:var(--color-text);border-radius:14px;padding:11px 20px;font-size:12.5px;font-weight:800;
  box-shadow:0 14px 44px rgba(0,0,0,.3);transition:transform .3s;max-width:88vw;text-align:center}
.toast.on{transform:translateX(-50%) translateY(0)}
</style>
</head>
<body>

<!-- ═══════════ ورود ═══════════ -->
<div class="login-box" id="login-box">
  <div class="glow"></div><div class="glow b"></div>
  <div class="login-card fade-in">
    <div class="lock-pulse">
      <svg width="34" height="34" viewBox="0 0 24 24" fill="none" stroke="var(--color-primary)" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="11" width="18" height="11" rx="2" ry="2"></rect><path d="M7 11V7a5 5 0 0 1 10 0v4"></path></svg>
    </div>
    <h1 id="t-title">پنل پشتیبانی JB-DNS</h1>
    <div class="login-sub"><span class="dotg"></span><span id="t-online">سیستم فعال است</span></div>
    <input class="inp" type="password" id="pwd" style="text-align:center;direction:ltr" placeholder="کلید مدیریت" autocomplete="off">
    <div style="height:14px"></div>
    <button class="btn btn-primary btn-block" onclick="doLogin()" id="login-btn">ورود به سیستم</button>
    <div class="err-msg" id="err-msg"></div>
  </div>
</div>

<!-- ═══════════ داشبورد ═══════════ -->
<div class="app" id="dash-box">
  <aside class="sidebar">
    <div class="side-head">
      <div class="logo">J</div>
      <div>
        <div class="side-name">پنل پشتیبانی</div>
        <span class="badge-ver">JB-DNS · v5</span>
      </div>
    </div>
    <nav class="nav">
      <button class="nav-item active" id="tab-overview" onclick="switchTab('overview')">
        <svg fill="none" stroke="currentColor" viewBox="0 0 24 24" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 12l2-2m0 0l7-7 7 7M5 10v10a1 1 0 001 1h3m10-11l2 2m-2-2v10a1 1 0 01-1 1h-3m-6 0a1 1 0 001-1v-4a1 1 0 011-1h2a1 1 0 011 1v4a1 1 0 001 1m-6 0h6"></path></svg>
        <span data-i18n="tab_overview">نمای کلی</span></button>
      <button class="nav-item" id="tab-chats" onclick="switchTab('chats')">
        <svg fill="none" stroke="currentColor" viewBox="0 0 24 24" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15a2 2 0 01-2 2H7l-4 4V5a2 2 0 012-2h14a2 2 0 012 2z"></path></svg>
        <span data-i18n="tab_chats">گفتگوها</span></button>
      <button class="nav-item" id="tab-reports" onclick="switchTab('reports')">
        <svg fill="none" stroke="currentColor" viewBox="0 0 24 24" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z"></path></svg>
        <span data-i18n="tab_reports">گزارش‌های قدیمی</span></button>
      <button class="nav-item" id="tab-network" onclick="switchTab('network')">
        <svg fill="none" stroke="currentColor" viewBox="0 0 24 24" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M9 19v-6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2a2 2 0 002-2zm0 0V9a2 2 0 012-2h2a2 2 0 012 2v10m-6 0a2 2 0 002 2h2a2 2 0 002-2m0 0V5a2 2 0 012-2h2a2 2 0 012 2v14a2 2 0 01-2 2h-2a2 2 0 01-2-2z"></path></svg>
        <span data-i18n="tab_network">سلامت سرویس</span></button>
      <button class="nav-item" id="tab-logs" onclick="switchTab('logs')">
        <svg fill="none" stroke="currentColor" viewBox="0 0 24 24" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M8 7h12m0 0l-4-4m4 4l-4 4m0 6H4m0 0l4 4m-4-4l4-4"></path></svg>
        <span data-i18n="tab_logs">جریان پیام‌ها</span></button>
      <button class="nav-item" id="tab-settings" onclick="switchTab('settings')">
        <svg fill="none" stroke="currentColor" viewBox="0 0 24 24" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.065 2.572c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.572 1.065c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.065-2.572c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z"></path><path d="M15 12a3 3 0 11-6 0 3 3 0 016 0z"></path></svg>
        <span data-i18n="tab_settings">تنظیمات</span></button>
      <button class="nav-item" id="tab-help" onclick="switchTab('help')">
        <svg fill="none" stroke="currentColor" viewBox="0 0 24 24" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M8.228 9c.549-1.165 2.03-2 3.772-2 2.21 0 4 1.343 4 3 0 1.4-1.278 2.575-3.006 2.907-.542.104-.994.59-.994 1.093m0 3h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z"></path></svg>
        <span data-i18n="tab_help">راهنما</span></button>
    </nav>
    <div class="side-foot">
      <a class="side-btn" href="https://github.com/EmamShahrooz-JB/JB-DNS" target="_blank" rel="noopener">
        <svg fill="none" stroke="currentColor" viewBox="0 0 24 24" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M10 6H6a2 2 0 00-2 2v10a2 2 0 002 2h10a2 2 0 002-2v-4M14 4h6m0 0v6m0-6L10 14"></path></svg>
        <span>GitHub</span></a>
      <button class="side-btn" onclick="toggleLang()">
        <svg fill="none" stroke="currentColor" viewBox="0 0 24 24" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 5h12M9 3v2m1.048 9.5A18.022 18.022 0 016.412 9m6.088 9h7M11 21l5-10 5 10M12.751 5C11.783 10.77 8.07 15.61 3 18.129"></path></svg>
        <span id="lang-label">English</span></button>
      <button class="side-btn out" onclick="logout()">
        <svg fill="none" stroke="currentColor" viewBox="0 0 24 24" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M17 16l4-4m0 0l-4-4m4 4H7m6 4v1a3 3 0 01-3 3H6a3 3 0 01-3-3V7a3 3 0 013-3h4a3 3 0 013 3v1"></path></svg>
        <span data-i18n="logout">خروج</span></button>
    </div>
  </aside>

  <div class="main">
    <header class="hdr"><h2 id="view-title">نمای کلی</h2></header>
    <div class="scroll-content"><div class="inner">

      <section class="view on fade-in" id="view-overview">
        <div class="grid-stats">
          <div class="stat-card" onclick="switchTab('chats')">
            <div class="stat-top"><span class="stat-label" data-i18n="ov_chats">گفتگوها</span>
              <span class="stat-ico"><svg fill="none" stroke="currentColor" viewBox="0 0 24 24" stroke-width="2"><path d="M21 15a2 2 0 01-2 2H7l-4 4V5a2 2 0 012-2h14a2 2 0 012 2z"></path></svg></span></div>
            <p class="stat-num" id="ov-chats">-</p></div>
          <div class="stat-card">
            <div class="stat-top"><span class="stat-label" data-i18n="ov_today">پیام امروز</span>
              <span class="stat-ico"><svg fill="none" stroke="currentColor" viewBox="0 0 24 24" stroke-width="2"><path d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z"></path></svg></span></div>
            <p class="stat-num" id="ov-today">-</p></div>
          <div class="stat-card" onclick="switchTab('chats')">
            <div class="stat-top"><span class="stat-label" data-i18n="ov_unread">خوانده‌نشده</span>
              <span class="stat-ico"><svg fill="none" stroke="currentColor" viewBox="0 0 24 24" stroke-width="2"><path d="M3 8l4.589-.829M21 3l-8.03 8.03M12.999 12.5l-1 4L8 21l-1-4-4-1 4.5-3.999M9 5l1 4"></path></svg></span></div>
            <p class="stat-num" id="ov-unread" style="color:var(--color-bad)">-</p></div>
          <div class="stat-card">
            <div class="stat-top"><span class="stat-label" data-i18n="ov_msgs">مجموع پیام‌ها</span>
              <span class="stat-ico"><svg fill="none" stroke="currentColor" viewBox="0 0 24 24" stroke-width="2"><path d="M8 10h.01M12 10h.01M16 10h.01M9 16H5a2 2 0 01-2-2V6a2 2 0 012-2h14a2 2 0 012 2v8a2 2 0 01-2 2h-5l-5 5v-5z"></path></svg></span></div>
            <p class="stat-num" id="ov-msgs">-</p></div>
          <div class="stat-card" onclick="switchTab('reports')">
            <div class="stat-top"><span class="stat-label" data-i18n="ov_reports">گزارش‌های قدیمی</span>
              <span class="stat-ico"><svg fill="none" stroke="currentColor" viewBox="0 0 24 24" stroke-width="2"><path d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z"></path></svg></span></div>
            <p class="stat-num" id="ov-reports">-</p></div>
        </div>
        <div class="card">
          <div class="card-hd"><h3 data-i18n="recent_chats">آخرین گفتگوها</h3><span class="cnt" id="ov-recent-cnt"></span></div>
          <div id="ov-activity-list"><div class="empty">…</div></div>
        </div>
      </section>

      <section class="view fade-in" id="view-chats">
        <div class="searchbar"><input class="inp" id="chat-search" placeholder="جستجوی شناسه/متن…" oninput="renderChats()"></div>
        <div class="card">
          <div class="card-hd"><h3 data-i18n="tab_chats">گفتگوها</h3><span class="cnt" id="chats-cnt"></span></div>
          <div id="chats-list"><div class="empty">…</div></div>
        </div>
      </section>

      <section class="view fade-in" id="view-reports">
        <div class="card">
          <div class="card-hd"><h3 data-i18n="tab_reports">گزارش‌های قدیمی</h3><span class="cnt" id="rep-cnt"></span></div>
          <div id="rep-list"><div class="empty">…</div></div>
        </div>
      </section>

      <section class="view fade-in" id="view-network">
        <div class="grid-stats" style="grid-template-columns:repeat(2,1fr)">
          <div class="stat-card"><div class="stat-top"><span class="stat-label" data-i18n="h_status">وضعیت</span></div>
            <p class="stat-num" id="net-status" style="font-size:16px">…</p></div>
          <div class="stat-card"><div class="stat-top"><span class="stat-label" data-i18n="h_version">نسخهٔ ورکر</span></div>
            <p class="stat-num" id="net-version" style="font-size:16px">…</p></div>
          <div class="stat-card"><div class="stat-top"><span class="stat-label" data-i18n="h_storage">پایگاه‌داده</span></div>
            <p class="stat-num" id="net-storage" style="font-size:16px">…</p></div>
          <div class="stat-card"><div class="stat-top"><span class="stat-label" data-i18n="h_time">زمان سرور</span></div>
            <p class="stat-num" id="net-time" style="font-size:14px;direction:ltr">…</p></div>
        </div>
        <div style="margin-top:18px"><button class="btn btn-primary" onclick="loadHealth()" data-i18n="h_recheck">بررسی مجدد</button></div>
      </section>

      <section class="view fade-in" id="view-logs">
        <div class="card">
          <div class="card-hd"><h3 data-i18n="tab_logs">جریان پیام‌ها</h3><span class="cnt">40</span></div>
          <div id="feed-list"><div class="empty">…</div></div>
        </div>
      </section>

      <section class="view fade-in" id="view-settings">
        <div class="card" style="margin-top:0">
          <div class="card-hd"><h3 data-i18n="tab_settings">تنظیمات</h3></div>
          <div style="padding:18px">
            <div class="field"><label class="lbl" data-i18n="s_theme">قالب رنگی</label>
              <select class="sel" id="theme-selector" onchange="setThemeVariant(this.value)">
                <option value="default">Default (Indigo)</option>
                <option value="ocean">Ocean Blue</option>
                <option value="forest">Forest Green</option>
                <option value="sunset">Sunset Rose</option>
                <option value="dracula">Dracula (Dark)</option>
              </select></div>
            <div class="field"><label class="lbl" data-i18n="s_mode">حالت نمایش</label>
              <select class="sel" id="mode-selector" onchange="setMode(this.value)">
                <option value="dark">🌙 Dark</option>
                <option value="light">☀️ Light</option>
              </select></div>
            <div class="field"><label class="lbl" data-i18n="s_lang">زبان</label>
              <select class="sel" id="lang-selector" onchange="setLang(this.value)">
                <option value="fa">فارسی</option>
                <option value="en">English</option>
              </select></div>
          </div>
        </div>
        <div class="card">
          <div class="card-hd"><h3 data-i18n="s_session">نشست مدیریت</h3></div>
          <div class="kv"><span data-i18n="s_key">کلید فعلی</span><b id="s-key" style="direction:ltr">…</b></div>
          <div class="kv"><span data-i18n="s_expiry">اعتبار تا</span><b id="s-expiry" style="direction:ltr">…</b></div>
        </div>
      </section>

      <section class="view fade-in" id="view-help">
        <div class="hcard"><h4 data-i18n="hp_what_t">این پنل چیست؟</h4><p data-i18n="hp_what">مرکز مدیریت «گفتگو با پشتیبانی» اپ JB-DNS است — پیام‌های کاربران، پاسخ‌های شما و گزارش‌های نسخهٔ 4.1 همگی همین‌جا هستند.</p></div>
        <div class="hcard"><h4 data-i18n="hp_track_t">کد پیگیری</h4><p data-i18n="hp_track">شناسهٔ هر گفتگو (مثل a1b2c3d4e5f60718) همان کد پیگیری کاربر است؛ با جستجوی آن، کل گفتگو را می‌بینید.</p></div>
        <div class="hcard"><h4 data-i18n="hp_priv_t">حریم خصوصی</h4><p data-i18n="hp_priv">IP کاربران هرگز خام ذخیره نمی‌شود (فقط هش برای محدودیت نرخ)؛ گفتگوها و گزارش‌ها پس از ۹۰ روز خودکار حذف می‌شوند.</p></div>
        <div class="hcard"><h4>API</h4><p><code>/chat/send</code> · <code>/chat/poll</code> · <code>/chats</code> · <code>/chat</code> · <code>/chat/reply</code> · <code>/stats</code> · <code>/feed</code> · <code>/report</code> · <code>/list</code> · <code>/one</code></p></div>
      </section>

    </div></div>
  </div>
</div>

<!-- ═══════════ نویگیشن موبایل ═══════════ -->
<nav class="mob-nav">
  <button class="mob-item active" id="mtab-overview" onclick="switchTab('overview')">
    <svg fill="none" stroke="currentColor" viewBox="0 0 24 24" stroke-width="2"><path d="M3 12l2-2m0 0l7-7 7 7M5 10v10a1 1 0 001 1h3m10-11l2 2m-2-2v10a1 1 0 01-1 1h-3m-6 0a1 1 0 001-1v-4a1 1 0 011-1h2a1 1 0 011 1v4a1 1 0 001 1m-6 0h6"></path></svg>
    <span data-i18n="tab_overview">نمای کلی</span></button>
  <button class="mob-item" id="mtab-chats" onclick="switchTab('chats')">
    <svg fill="none" stroke="currentColor" viewBox="0 0 24 24" stroke-width="2"><path d="M21 15a2 2 0 01-2 2H7l-4 4V5a2 2 0 012-2h14a2 2 0 012 2z"></path></svg>
    <span data-i18n="tab_chats">گفتگوها</span></button>
  <button class="mob-item" id="mtab-reports" onclick="switchTab('reports')">
    <svg fill="none" stroke="currentColor" viewBox="0 0 24 24" stroke-width="2"><path d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z"></path></svg>
    <span data-i18n="tab_reports">گزارش‌ها</span></button>
  <button class="mob-item" id="mtab-network" onclick="switchTab('network')">
    <svg fill="none" stroke="currentColor" viewBox="0 0 24 24" stroke-width="2"><path d="M9 19v-6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2a2 2 0 002-2zm0 0V9a2 2 0 012-2h2a2 2 0 012 2v10m-6 0a2 2 0 002 2h2a2 2 0 002-2m0 0V5a2 2 0 012-2h2a2 2 0 012 2v14a2 2 0 01-2 2h-2a2 2 0 01-2-2z"></path></svg>
    <span data-i18n="tab_network">سلامت</span></button>
  <button class="mob-item" id="mtab-settings" onclick="switchTab('settings')">
    <svg fill="none" stroke="currentColor" viewBox="0 0 24 24" stroke-width="2"><path d="M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.065 2.572c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.572 1.065c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.065-2.572c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z"></path><path d="M15 12a3 3 0 11-6 0 3 3 0 016 0z"></path></svg>
    <span data-i18n="tab_settings">تنظیمات</span></button>
</nav>

<!-- ═══════════ مودال گفتگو ═══════════ -->
<div class="modal-back" id="chat-modal">
  <div class="modal">
    <div class="modal-hd">
      <div class="lrow-av">💬</div>
      <div style="min-width:0">
        <div style="font-size:13px;font-weight:900" id="cm-id"></div>
        <div style="font-size:10px;color:var(--color-text-muted)" id="cm-sub"></div>
      </div>
      <button class="btn x" style="padding:8px 13px" onclick="closeChat()" data-i18n="close">بستن</button>
    </div>
    <div class="modal-bd" id="cm-body"></div>
    <div class="modal-ft">
      <textarea id="cm-in" rows="1" placeholder="پاسخ خود را بنویس…"></textarea>
      <button class="btn btn-primary" style="padding:12px 20px" onclick="sendReply()" data-i18n="send">ارسال</button>
    </div>
  </div>
</div>

<!-- ═══════════ مودال گزارش ═══════════ -->
<div class="modal-back" id="rep-modal">
  <div class="modal" style="max-width:620px">
    <div class="modal-hd">
      <div class="lrow-av">📮</div>
      <div style="font-size:13px;font-weight:900" id="rm-id"></div>
      <button class="btn x" style="margin-inline-start:auto;padding:8px 13px" onclick="document.getElementById('rep-modal').classList.remove('on')" data-i18n="close">بستن</button>
    </div>
    <div class="modal-bd"><pre class="pre" id="rm-body"></pre></div>
  </div>
</div>

<div class="toast" id="toast"></div>

<script>
/* حافظهٔ امن — در iframeهای sandbox شده localStorage خطا می‌دهد؛ فال‌بک حافظه‌ای */
var LS = { _m:{},
  get: function(k){ try { return localStorage.getItem(k); } catch(e){ return (k in this._m) ? this._m[k] : null; } },
  set: function(k,v){ try { localStorage.setItem(k,v); } catch(e){ this._m[k] = String(v); } },
  del: function(k){ try { localStorage.removeItem(k); } catch(e){ delete this._m[k]; } } };
/* ---------- i18n ---------- */
var I18N = {
  fa: { title:"پنل پشتیبانی JB-DNS", online:"سیستم فعال است", pass_ph:"کلید مدیریت", login_btn:"ورود به سیستم",
    logout:"خروج", tab_overview:"نمای کلی", tab_chats:"گفتگوها", tab_reports:"گزارش‌های قدیمی",
    tab_network:"سلامت سرویس", tab_settings:"تنظیمات", tab_logs:"جریان پیام‌ها", tab_help:"راهنما",
    ov_chats:"گفتگوها", ov_today:"پیام امروز", ov_unread:"خوانده‌نشده", ov_msgs:"مجموع پیام‌ها", ov_reports:"گزارش‌های قدیمی",
    recent_chats:"آخرین گفتگوها", h_status:"وضعیت", h_version:"نسخهٔ ورکر", h_storage:"پایگاه‌داده", h_time:"زمان سرور",
    h_recheck:"بررسی مجدد", s_theme:"قالب رنگی", s_mode:"حالت نمایش", s_lang:"زبان", s_session:"نشست مدیریت",
    s_key:"کلید فعلی", s_expiry:"اعتبار تا", close:"بستن", send:"ارسال", search_ph:"جستجوی شناسه/متن…",
    reply_ph:"پاسخ خود را بنویس…", empty_chats:"هنوز گفتگویی شروع نشده", empty_rep:"گزارشی ثبت نشده",
    online_lbl:"زنده", sent:"پاسخ ارسال شد ✓", err:"خطا", wrong:"دسترسی مسدود شد",
    hp_what_t:"این پنل چیست؟",
    hp_what:"مرکز مدیریت «گفتگو با پشتیبانی» اپ JB-DNS است — پیام‌های کاربران، پاسخ‌های شما و گزارش‌های نسخهٔ 4.1 همگی همین‌جا هستند.",
    hp_track_t:"کد پیگیری",
    hp_track:"شناسهٔ هر گفتگو (مثل a1b2c3d4e5f60718) همان کد پیگیری کاربر است؛ با جستجوی آن، کل گفتگو را می‌بینید.",
    hp_priv_t:"حریم خصوصی",
    hp_priv:"IP کاربران هرگز خام ذخیره نمی‌شود (فقط هش برای محدودیت نرخ)؛ گفتگوها و گزارش‌ها پس از ۹۰ روز خودکار حذف می‌شوند." },
  en: { title:"JB-DNS Support Panel", online:"System online", pass_ph:"Admin key", login_btn:"Authenticate",
    logout:"Disconnect", tab_overview:"Dashboard", tab_chats:"Chats", tab_reports:"Legacy Reports",
    tab_network:"Service Health", tab_settings:"Settings", tab_logs:"Message Feed", tab_help:"Help & FAQ",
    ov_chats:"Chats", ov_today:"Msgs Today", ov_unread:"Unread", ov_msgs:"Total Messages", ov_reports:"Legacy Reports",
    recent_chats:"Recent Conversations", h_status:"Status", h_version:"Worker Version", h_storage:"Storage", h_time:"Server Time",
    h_recheck:"Re-check", s_theme:"Display Theme", s_mode:"Appearance", s_lang:"Language", s_session:"Admin Session",
    s_key:"Current key", s_expiry:"Valid until", close:"Close", send:"Send", search_ph:"Search id/text…",
    reply_ph:"Type your reply…", empty_chats:"No conversations yet", empty_rep:"No reports",
    online_lbl:"Online", sent:"Reply sent ✓", err:"Error", wrong:"Access Denied",
    hp_what_t:"What is this panel?",
    hp_what:"The management hub for JB-DNS in-app support chat — user messages, your replies, and legacy 4.1 reports all live here.",
    hp_track_t:"Tracking code",
    hp_track:"Each chat id (like a1b2c3d4e5f60718) is the user tracking code — search it to open the whole conversation.",
    hp_priv_t:"Privacy",
    hp_priv:"User IPs are never stored raw (only a hash for rate-limiting); chats and reports auto-delete after 90 days." }
};
var LANG = LS.get("jb_admin_lang") || "fa";
var TABS = ["overview","chats","reports","network","logs","settings","help"];
var CUR = "overview", CHATS = [], CURCHAT = null, MODAL_TIMER = null, REFRESH_TIMER = null;

/* ---------- helpers ---------- */
function $(id){ return document.getElementById(id); }
function esc(s){ return String(s == null ? "" : s).replace(/[&<>"]/g, function(c){
  return {"&":"&amp;","<":"&lt;",">":"&gt;","\\"":"&quot;"}[c]; }); }
function faTime(iso){ try { return new Date(iso).toLocaleString(LANG === "fa" ? "fa-IR" : "en-US"); } catch(e){ return iso; } }
function t(k){ return (I18N[LANG] && I18N[LANG][k]) || I18N.fa[k] || k; }
function toast(msg){ var el = $("toast"); el.textContent = msg; el.classList.add("on");
  setTimeout(function(){ el.classList.remove("on"); }, 2600); }
var sessionKey = "";

function api(path, cb){
  fetch(path + (path.indexOf("?") >= 0 ? "&" : "?") + "t=" + encodeURIComponent(sessionKey))
    .then(function(r){ return r.json().then(function(d){ return { s: r.status, d: d }; }); })
    .then(function(x){
      if (x.s === 401) { logout(); return; }
      cb(x.d);
    })
    .catch(function(){ cb(null); });
}

/* ---------- i18n apply ---------- */
function applyI18n(){
  document.documentElement.lang = LANG;
  document.documentElement.dir = LANG === "fa" ? "rtl" : "ltr";
  $("t-title").textContent = t("title");
  $("t-online").textContent = t("online");
  $("pwd").placeholder = t("pass_ph");
  $("login-btn").textContent = t("login_btn");
  $("lang-label").textContent = LANG === "fa" ? "English" : "فارسی";
  $("chat-search").placeholder = t("search_ph");
  $("cm-in").placeholder = t("reply_ph");
  var els = document.querySelectorAll("[data-i18n]");
  for (var i = 0; i < els.length; i++) els[i].textContent = t(els[i].getAttribute("data-i18n"));
  $("view-title").textContent = t("tab_" + CUR);
  $("lang-selector").value = LANG;
}
function toggleLang(){ setLang(LANG === "fa" ? "en" : "fa"); }
function setLang(l){ LANG = l; LS.set("jb_admin_lang", l); applyI18n(); renderChats(); renderStats(); }

/* ---------- theme ---------- */
function setThemeVariant(v){
  if (v === "default") document.documentElement.removeAttribute("data-theme");
  else document.documentElement.setAttribute("data-theme", v);
  LS.set("jb_admin_theme", v);
  if (v === "dracula") { setMode("dark"); $("mode-selector").value = "dark"; }
}
function setMode(m){
  document.documentElement.classList.toggle("dark", m === "dark");
  LS.set("jb_admin_mode", m);
  var mt = document.querySelector("meta[name=theme-color]");
  if (mt) mt.setAttribute("content", m === "dark" ? "#090d16" : "#f8fafc");
}
function loadThemePrefs(){
  var th = LS.get("jb_admin_theme") || "default";
  var md = LS.get("jb_admin_mode") || "dark";
  $("theme-selector").value = th; $("mode-selector").value = md;
  setThemeVariant(th); setMode(md);
}

/* ---------- auth ---------- */
function doLogin(silent){
  var pass = silent ? sessionKey : $("pwd").value;
  if (!pass) return;
  var btn = $("login-btn"); var orig = btn.textContent;
  if (!silent) btn.textContent = "...";
  fetch("chats?t=" + encodeURIComponent(pass))
    .then(function(r){ return r.json().then(function(d){ return { s: r.status, d: d }; }); })
    .then(function(x){
      if (!silent) btn.textContent = orig;
      if (x.s === 200 && x.d.ok) {
        sessionKey = pass;
        LS.set("jb_admin", JSON.stringify({ key: pass, expiry: Date.now() + 30 * 60 * 1000 }));
        enterDash();
      } else {
        if (!silent) $("err-msg").textContent = t("wrong");
      }
    })
    .catch(function(){ if (!silent) { btn.textContent = orig; $("err-msg").textContent = t("err"); } });
}
function enterDash(){
  $("err-msg").textContent = "";
  $("login-box").style.display = "none";
  $("dash-box").classList.add("on");
  var sess = JSON.parse(LS.get("jb_admin") || "{}");
  $("s-key").textContent = sess.key ? sess.key.slice(0, 4) + "••••••••" + sess.key.slice(-4) : "—";
  $("s-expiry").textContent = sess.expiry ? new Date(sess.expiry).toLocaleString(LANG === "fa" ? "fa-IR" : "en-US") : "—";
  loadStats(); loadChats(); loadReports(); loadHealth(); loadFeed();
  startAutoRefresh();
}
function logout(){
  LS.del("jb_admin");
  sessionKey = "";
  stopAutoRefresh();
  $("dash-box").classList.remove("on");
  $("login-box").style.display = "flex";
  $("pwd").value = "";
}
function startAutoRefresh(){
  stopAutoRefresh();
  REFRESH_TIMER = setInterval(function(){
    if (CUR === "overview") loadStats();
    if (CUR === "chats" || CUR === "overview") loadChats();
  }, 12000);
}
function stopAutoRefresh(){
  if (REFRESH_TIMER) clearInterval(REFRESH_TIMER);
  if (MODAL_TIMER) clearInterval(MODAL_TIMER);
  REFRESH_TIMER = null; MODAL_TIMER = null;
}

/* ---------- tabs ---------- */
function switchTab(tab){
  CUR = tab;
  for (var i = 0; i < TABS.length; i++) {
    var v = $("view-" + TABS[i]); if (v) v.classList.toggle("on", TABS[i] === tab);
    var d = $("tab-" + TABS[i]); if (d) d.classList.toggle("active", TABS[i] === tab);
    var m = $("mtab-" + TABS[i]); if (m) m.classList.toggle("active", TABS[i] === tab);
  }
  $("view-title").textContent = t("tab_" + tab);
  if (tab === "overview") loadStats();
  if (tab === "chats" || tab === "overview") loadChats();
  if (tab === "reports") loadReports();
  if (tab === "network") loadHealth();
  if (tab === "logs") loadFeed();
}

/* ---------- data: stats ---------- */
function loadStats(){
  api("stats", function(d){
    if (!d || !d.ok) return;
    $("ov-chats").textContent = d.chats;
    $("ov-today").textContent = d.todayMessages;
    $("ov-unread").textContent = d.unread;
    $("ov-msgs").textContent = d.messages;
    $("ov-reports").textContent = d.reports;
    $("ov-recent-cnt").textContent = d.recent.length;
    var h = "";
    for (var i = 0; i < d.recent.length; i++) {
      var c = d.recent[i];
      h += '<button class="lrow" onclick="openChat(\\'' + c.id + '\\')">' +
        '<div class="lrow-av">💬</div><div class="lrow-main"><div class="lrow-t">' + esc(c.id) +
        (c.contact ? ' · ' + esc(c.contact) : '') + '</div><div class="lrow-p">' + esc((c.preview || "").slice(0, 80)) + '</div></div>' +
        (c.unread ? '<span class="badge-unread">' + c.unread + '</span>' : '') +
        '<span class="lrow-time">' + esc(faTime(c.last_activity)) + '</span></button>';
    }
    $("ov-activity-list").innerHTML = h || '<div class="empty">' + esc(t("empty_chats")) + '</div>';
  });
}
function renderStats(){ if (CUR === "overview") loadStats(); }

/* ---------- data: chats ---------- */
function loadChats(){
  api("chats", function(d){
    if (!d || !d.ok) return;
    CHATS = d.chats || [];
    $("chats-cnt").textContent = d.count;
    renderChats();
  });
}
function renderChats(){
  if (!$("chats-list")) return;
  var q = ($("chat-search").value || "").trim().toLowerCase();
  var list = CHATS;
  if (q) list = CHATS.filter(function(c){
    return (c.id || "").indexOf(q) >= 0 || ((c.contact || "") + (c.preview || "")).toLowerCase().indexOf(q) >= 0; });
  var h = "";
  for (var i = 0; i < list.length; i++) {
    var c = list[i];
    h += '<button class="lrow" onclick="openChat(\\'' + c.id + '\\')">' +
      '<div class="lrow-av">💬</div><div class="lrow-main"><div class="lrow-t">' + esc(c.id) +
      (c.contact ? ' · ' + esc(c.contact) : '') + '</div><div class="lrow-p">' + esc((c.preview || "").slice(0, 90)) + '</div></div>' +
      (c.unread ? '<span class="badge-unread">' + c.unread + '</span>' : '') +
      '<span class="lrow-time">' + esc(faTime(c.last_activity)) + '</span></button>';
  }
  $("chats-list").innerHTML = h || '<div class="empty">' + esc(t("empty_chats")) + '</div>';
}

/* ---------- data: chat modal ---------- */
function openChat(id){
  CURCHAT = id;
  $("chat-modal").classList.add("on");
  $("cm-id").textContent = id;
  loadThread();
  if (MODAL_TIMER) clearInterval(MODAL_TIMER);
  MODAL_TIMER = setInterval(function(){ if (CURCHAT) loadThread(); }, 8000);
}
function closeChat(){
  CURCHAT = null;
  $("chat-modal").classList.remove("on");
  if (MODAL_TIMER) { clearInterval(MODAL_TIMER); MODAL_TIMER = null; }
  loadChats(); loadStats();
}
function loadThread(){
  if (!CURCHAT) return;
  api("chat?id=" + encodeURIComponent(CURCHAT), function(d){
    if (!d || !d.ok) return;
    $("cm-sub").textContent = (d.chat.contact || "") + " · " + faTime(d.chat.created);
    var h = "";
    for (var i = 0; i < d.messages.length; i++) {
      var m = d.messages[i];
      var cls = m.sender === "admin" ? "msg-admin" : (m.sender === "user" ? "msg-user" : "msg-bot");
      var att = "";
      if (m.meta) {
        if (m.meta.diagnostics) att += '<details class="chatatt"><summary>📊 ' + (LANG === "fa" ? "اطلاعات فنی" : "Diagnostics") + '</summary><div class="d">' + esc(JSON.stringify(m.meta.diagnostics, null, 1)) + '</div></details>';
        if (m.meta.logs) att += '<details class="chatatt"><summary>📜 ' + (LANG === "fa" ? "لاگ‌ها" : "Logs") + '</summary><div class="d">' + esc(m.meta.logs.join("\\n")) + '</div></details>';
      }
      h += '<div class="msg ' + cls + '">' + esc(m.text) + att + '<div class="msg-foot">' + esc(faTime(m.time)) + '</div></div>';
    }
    var bd = $("cm-body");
    bd.innerHTML = h || '<div class="empty">—</div>';
    bd.scrollTop = bd.scrollHeight;
  });
}
function sendReply(){
  if (!CURCHAT) return;
  var ta = $("cm-in"); var txt = ta.value.trim();
  if (!txt) return;
  ta.value = "";
  fetch("chat/reply?t=" + encodeURIComponent(sessionKey), {
    method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ chat: CURCHAT, text: txt })
  }).then(function(r){ return r.json(); }).then(function(d){
    if (d && d.ok) { toast(t("sent")); loadThread(); }
    else toast((d && d.error) || t("err"));
  }).catch(function(){ toast(t("err")); });
}

/* ---------- data: reports ---------- */
function loadReports(){
  api("list", function(d){
    if (!d || !d.ok) return;
    $("rep-cnt").textContent = d.count;
    var h = "";
    for (var i = 0; i < d.reports.length; i++) {
      var r = d.reports[i];
      h += '<button class="lrow" onclick="openReport(\\'' + r.id + '\\')">' +
        '<div class="lrow-av">📮</div><div class="lrow-main"><div class="lrow-t">' + esc(r.id) + '</div>' +
        '<div class="lrow-p">' + esc((r.preview || "").slice(0, 90)) + '</div></div>' +
        '<span class="lrow-time">' + esc(faTime(r.time)) + '</span></button>';
    }
    $("rep-list").innerHTML = h || '<div class="empty">' + esc(t("empty_rep")) + '</div>';
  });
}
function openReport(id){
  api("one?id=" + encodeURIComponent(id), function(d){
    if (!d) return;
    $("rm-id").textContent = id;
    $("rm-body").textContent = JSON.stringify(d, null, 2);
    $("rep-modal").classList.add("on");
  });
}

/* ---------- data: health ---------- */
function loadHealth(){
  fetch("/").then(function(r){ return r.json(); }).then(function(d){
    $("net-status").innerHTML = '<span class="ok-badge"><span class="dotg"></span>' + esc(t("online_lbl")) + '</span>';
    $("net-version").textContent = "v" + (d.version || "?");
    $("net-storage").textContent = (d.storage || "?").toUpperCase() + " · D1";
    $("net-time").textContent = d.time || "—";
  }).catch(function(){
    $("net-status").textContent = "⚠ " + t("err");
  });
}

/* ---------- data: feed ---------- */
function loadFeed(){
  api("feed", function(d){
    if (!d || !d.ok) return;
    var h = "";
    for (var i = d.feed.length - 1; i >= 0; i--) {
      var m = d.feed[i];
      var chipCls = m.sender === "admin" ? "chip-admin" : (m.sender === "user" ? "chip-user" : "chip-bot");
      h += '<div class="frow"><span class="frow-time">' + esc(m.time.slice(11, 16)) + '</span>' +
        '<span class="chip ' + chipCls + '">' + esc(m.sender) + '</span>' +
        '<div style="flex:1;min-width:0"><div style="font-size:9px;color:var(--color-text-muted);direction:ltr;text-align:start">' + esc(m.chat_id) + '</div>' +
        '<div style="font-size:12px;margin-top:2px">' + esc(m.text.slice(0, 140)) + '</div></div></div>';
    }
    $("feed-list").innerHTML = h || '<div class="empty">—</div>';
  });
}

/* ---------- boot ---------- */
loadThemePrefs();
applyI18n();
$("pwd").addEventListener("keydown", function(e){ if (e.key === "Enter") doLogin(false); });
$("cm-in").addEventListener("keydown", function(e){ if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); sendReply(); } });
document.getElementById("chat-modal").addEventListener("click", function(e){ if (e.target === this) closeChat(); });
document.getElementById("rep-modal").addEventListener("click", function(e){ if (e.target === this) e.target.classList.remove("on"); });
var sess = null;
try { sess = JSON.parse(LS.get("jb_admin") || "null"); } catch(e){}
if (sess && sess.key && sess.expiry > Date.now()) { sessionKey = sess.key; doLogin(true); }
</script>
</body></html>`;
}
