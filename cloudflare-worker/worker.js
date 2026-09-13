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
        return json({ ok: true, service: "jbdns-feedback", version: 7, storage: "d1", chat: true, time: new Date().toISOString() });

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
      if (request.method === "POST" && url.pathname === "/admin/pass")
        return await adminChangePass(request, env);

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
  const err = await requireAdmin(env, url); if (err) return err;
  const { results } = await env.DB.prepare(
    "SELECT c.id, c.created, c.last_activity, c.contact, c.unread," +
    " (SELECT text FROM messages m WHERE m.chat_id = c.id ORDER BY m.time DESC LIMIT 1) AS preview" +
    " FROM chats c ORDER BY c.last_activity DESC LIMIT 50"
  ).all();
  return json({ ok: true, count: results.length, chats: results });
}

async function adminChat(env, url) {
  const err = await requireAdmin(env, url); if (err) return err;
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
  const err = await requireAdmin(env, url); if (err) return err;
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
  const err = await requireAdmin(env, url); if (err) return err;
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
  const err = await requireAdmin(env, url); if (err) return err;
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
  const err = await requireAdmin(env, url); if (err) return err;
  const { results } = await env.DB
    .prepare("SELECT id, time, substr(text, 1, 90) AS preview FROM reports ORDER BY time DESC LIMIT 50")
    .all();
  return json({ ok: true, count: results.length, reports: results });
}

async function handleOne(env, url) {
  const err = await requireAdmin(env, url); if (err) return err;
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

async function requireAdmin(env, url) {
  const t = url.searchParams.get("t") || "";
  if (!t) return json({ ok: false, error: "دسترسی مدیریتی لازم است" }, 401);
  // کلید سفارشی (در D1، به‌صورت هش) بر کلید محیطی مقدم است
  let custom = null;
  try {
    const row = await env.DB.prepare("SELECT value FROM settings WHERE key = 'admin_token_hash'").first();
    custom = row ? row.value : null;
  } catch (e) { /* جدول settings هنوز ساخته نشده */ }
  if (custom) {
    if ((await sha256Hex(t)) === custom) return null;
    return json({ ok: false, error: "دسترسی مدیریتی لازم است" }, 401);
  }
  if (env.ADMIN_TOKEN && t === env.ADMIN_TOKEN) return null;
  return json({ ok: false, error: "دسترسی مدیریتی لازم است" }, 401);
}

/* تغییر کلید مدیریت از پنل — هش SHA-256 در جدول settings */
async function adminChangePass(request, env) {
  if (!env.DB) return json({ ok: false, error: "پایگاه‌دادهٔ D1 متصل نیست" }, 500);
  let o;
  try { o = await readJson(request); } catch (e) { return json({ ok: false, error: e.message }, 400); }
  const current = typeof o.current === "string" ? o.current : "";
  const next = typeof o.next === "string" ? o.next : "";
  if (next.length < 8 || next.length > 64)
    return json({ ok: false, error: "کلید جدید باید ۸ تا ۶۴ نویسه باشد" }, 400);
  if (!/[a-zA-Z0-9]/.test(next))
    return json({ ok: false, error: "کلید جدید باید حرف یا عدد هم داشته باشد" }, 400);
  // سد تلاش: ۵ تلاش ناموفق در ساعت از هر IP
  const ipHash = await hashIp(request.headers.get("CF-Connecting-IP") || "unknown");
  const hour = new Date().toISOString().slice(0, 13);
  const rlKey = `pass:${ipHash}:${hour}`;
  const rl = await env.DB.prepare("SELECT count AS c FROM rate_limit WHERE key = ?").bind(rlKey).first();
  if (rl && rl.c >= 5) return json({ ok: false, error: "تلاش‌های زیاد — یک ساعت صبر کن" }, 429);
  // بررسی کلید فعلی
  let ok = false;
  try {
    const row = await env.DB.prepare("SELECT value FROM settings WHERE key = 'admin_token_hash'").first();
    ok = row ? (await sha256Hex(current)) === row.value
             : !!(env.ADMIN_TOKEN && current === env.ADMIN_TOKEN);
  } catch (e) { ok = !!(env.ADMIN_TOKEN && current === env.ADMIN_TOKEN); }
  if (!ok) {
    await env.DB.prepare("INSERT INTO rate_limit(key, count) VALUES(?, 1) ON CONFLICT(key) DO UPDATE SET count = count + 1")
      .bind(rlKey).run();
    return json({ ok: false, error: "کلید فعلی درست نیست" }, 401);
  }
  await env.DB.prepare(
    "INSERT INTO settings(key, value) VALUES('admin_token_hash', ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value"
  ).bind(await sha256Hex(next)).run();
  return json({ ok: true });
}

async function sha256Hex(s) {
  const b = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s));
  return [...new Uint8Array(b)].map((x) => x.toString(16).padStart(2, "0")).join("");
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
<link rel="preconnect" href="https://cdn.jsdelivr.net" crossorigin>
<link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/vazirmatn@33.0.3/Vazirmatn-font-face.css">
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
/* ───── دراپ‌داون سفارشی ───── */
.dd{position:relative}
.dd-trigger{display:flex;align-items:center;gap:10px;width:100%;padding:12px 16px;border-radius:14px;
  border:1.5px solid var(--color-border);background:var(--color-surface2);color:var(--color-text);
  font-family:inherit;font-size:13.5px;font-weight:800;cursor:pointer;transition:.2s;text-align:start}
.dd-trigger:hover{border-color:var(--color-primary)}
.dd.open .dd-trigger{border-color:var(--color-primary);
  box-shadow:0 0 0 3px color-mix(in srgb,var(--color-primary) 18%,transparent)}
.dd-label{flex:1;min-width:0;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.dd-chev{width:17px;height:17px;flex-shrink:0;color:var(--color-text-muted);transition:transform .25s}
.dd.open .dd-chev{transform:rotate(180deg)}
.dd-swatch{width:19px;height:19px;border-radius:7px;flex-shrink:0;
  background:linear-gradient(135deg,var(--sw,#6366f1),color-mix(in srgb,var(--sw,#6366f1) 55%,#000));
  box-shadow:0 2px 8px color-mix(in srgb,var(--sw,#6366f1) 45%,transparent)}
.dd-ico{width:19px;height:19px;border-radius:7px;flex-shrink:0;display:flex;align-items:center;justify-content:center;
  font-size:11px;font-weight:900;background:var(--color-surface);border:1px solid var(--color-border)}
.dd-menu{position:absolute;top:calc(100% + 8px);inset-inline:0;z-index:45;padding:6px;
  background:color-mix(in srgb,var(--color-surface) 88%,transparent);backdrop-filter:blur(16px);
  border:1px solid var(--color-border);border-radius:16px;
  box-shadow:0 18px 50px rgba(0,0,0,.30),0 2px 8px rgba(0,0,0,.14);
  display:none;transform-origin:top center}
.dd-item{display:flex;align-items:center;gap:10px;width:100%;padding:10px 12px;border:none;border-radius:11px;
  background:none;color:var(--color-text);font-family:inherit;font-size:13px;font-weight:700;cursor:pointer;
  text-align:start;transition:background .15s}
.dd-item:hover{background:color-mix(in srgb,var(--color-primary) 10%,transparent)}
.dd-item .dd-txt{flex:1;min-width:0}
.dd-check{width:15px;height:15px;flex-shrink:0;color:var(--color-primary);opacity:0;transform:scale(.4);transition:.2s}
.dd-item.on{color:var(--color-primary)}
.dd-item.on .dd-check{opacity:1;transform:none}
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
.toast{position:fixed;bottom:-80px;left:50%;transform:translateX(-50%);opacity:0;z-index:60;background:var(--color-surface);
  border:1px solid var(--color-border);color:var(--color-text);border-radius:14px;padding:11px 20px;font-size:12.5px;font-weight:800;
  box-shadow:0 14px 44px rgba(0,0,0,.3);max-width:88vw;text-align:center}
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
              <div class="dd" id="dd-theme">
                <button type="button" class="dd-trigger" data-dd="theme" aria-haspopup="listbox">
                  <span class="dd-swatch" id="dd-theme-dot"></span>
                  <span class="dd-label" id="dd-theme-label">—</span>
                  <svg class="dd-chev" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M6 9l6 6 6-6"></path></svg>
                </button>
                <div class="dd-menu" id="dd-theme-menu" role="listbox"></div>
              </div></div>
            <div class="field"><label class="lbl" data-i18n="s_mode">حالت نمایش</label>
              <div class="dd" id="dd-mode">
                <button type="button" class="dd-trigger" data-dd="mode" aria-haspopup="listbox">
                  <span class="dd-ico" id="dd-mode-ico">🌙</span>
                  <span class="dd-label" id="dd-mode-label">—</span>
                  <svg class="dd-chev" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M6 9l6 6 6-6"></path></svg>
                </button>
                <div class="dd-menu" id="dd-mode-menu" role="listbox"></div>
              </div></div>
            <div class="field"><label class="lbl" data-i18n="s_lang">زبان</label>
              <div class="dd" id="dd-lang">
                <button type="button" class="dd-trigger" data-dd="lang" aria-haspopup="listbox">
                  <span class="dd-ico" id="dd-lang-ico">فا</span>
                  <span class="dd-label" id="dd-lang-label">—</span>
                  <svg class="dd-chev" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M6 9l6 6 6-6"></path></svg>
                </button>
                <div class="dd-menu" id="dd-lang-menu" role="listbox"></div>
              </div></div>
          </div>
        </div>
        <div class="card">
          <div class="card-hd"><h3 data-i18n="s_session">نشست مدیریت</h3></div>
          <div class="kv"><span data-i18n="s_key">کلید فعلی</span><b id="s-key" style="direction:ltr">…</b></div>
          <div class="kv"><span data-i18n="s_expiry">اعتبار تا</span><b id="s-expiry" style="direction:ltr">…</b></div>
        </div>
        <div class="card">
          <div class="card-hd"><h3 data-i18n="s_pass_t">🔑 تغییر کلید مدیریت</h3></div>
          <div style="padding:18px">
            <div class="field"><label class="lbl" data-i18n="s_pass_cur">کلید فعلی</label>
              <input class="inp" type="password" id="pass-cur" style="direction:ltr;text-align:left" autocomplete="off"></div>
            <div class="field"><label class="lbl" data-i18n="s_pass_new">کلید جدید (حداقل ۸ نویسه)</label>
              <input class="inp" type="password" id="pass-new" style="direction:ltr;text-align:left" autocomplete="off"></div>
            <div class="field"><label class="lbl" data-i18n="s_pass_new2">تکرار کلید جدید</label>
              <input class="inp" type="password" id="pass-new2" style="direction:ltr;text-align:left" autocomplete="off"></div>
            <button class="btn btn-primary btn-block" id="pass-btn" onclick="changePass()" data-i18n="s_pass_btn">تغییر کلید</button>
            <p style="font-size:10.5px;color:var(--color-text-muted);line-height:2.1;margin:13px 2px 0" data-i18n="s_pass_hint">پس از تغییر، فقط کلید جدید معتبر است — آن را در جای امن نگه دارید. کلید به‌صورت هش SHA-256 ذخیره می‌شود.</p>
          </div>
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
/*
 * anime.js v3.2.2
 * (c) 2023 Julian Garnier
 * Released under the MIT license
 * animejs.com
 */

!function(n,e){"object"==typeof exports&&"undefined"!=typeof module?module.exports=e():"function"==typeof define&&define.amd?define(e):n.anime=e()}(this,function(){"use strict";var i={update:null,begin:null,loopBegin:null,changeBegin:null,change:null,changeComplete:null,loopComplete:null,complete:null,loop:1,direction:"normal",autoplay:!0,timelineOffset:0},M={duration:1e3,delay:0,endDelay:0,easing:"easeOutElastic(1, .5)",round:0},j=["translateX","translateY","translateZ","rotate","rotateX","rotateY","rotateZ","scale","scaleX","scaleY","scaleZ","skew","skewX","skewY","perspective","matrix","matrix3d"],l={CSS:{},springs:{}};function C(n,e,t){return Math.min(Math.max(n,e),t)}function u(n,e){return-1<n.indexOf(e)}function o(n,e){return n.apply(null,e)}var w={arr:function(n){return Array.isArray(n)},obj:function(n){return u(Object.prototype.toString.call(n),"Object")},pth:function(n){return w.obj(n)&&n.hasOwnProperty("totalLength")},svg:function(n){return n instanceof SVGElement},inp:function(n){return n instanceof HTMLInputElement},dom:function(n){return n.nodeType||w.svg(n)},str:function(n){return"string"==typeof n},fnc:function(n){return"function"==typeof n},und:function(n){return void 0===n},nil:function(n){return w.und(n)||null===n},hex:function(n){return/(^#[0-9A-F]{6}$)|(^#[0-9A-F]{3}$)/i.test(n)},rgb:function(n){return/^rgb/.test(n)},hsl:function(n){return/^hsl/.test(n)},col:function(n){return w.hex(n)||w.rgb(n)||w.hsl(n)},key:function(n){return!i.hasOwnProperty(n)&&!M.hasOwnProperty(n)&&"targets"!==n&&"keyframes"!==n}};function d(n){n=/\\(([^)]+)\\)/.exec(n);return n?n[1].split(",").map(function(n){return parseFloat(n)}):[]}function c(r,t){var n=d(r),e=C(w.und(n[0])?1:n[0],.1,100),a=C(w.und(n[1])?100:n[1],.1,100),o=C(w.und(n[2])?10:n[2],.1,100),n=C(w.und(n[3])?0:n[3],.1,100),u=Math.sqrt(a/e),i=o/(2*Math.sqrt(a*e)),c=i<1?u*Math.sqrt(1-i*i):0,s=i<1?(i*u-n)/c:-n+u;function f(n){var e=t?t*n/1e3:n,e=i<1?Math.exp(-e*i*u)*(+Math.cos(c*e)+s*Math.sin(c*e)):(1+s*e)*Math.exp(-e*u);return 0===n||1===n?n:1-e}return t?f:function(){var n=l.springs[r];if(n)return n;for(var e=0,t=0;;)if(1===f(e+=1/6)){if(16<=++t)break}else t=0;return n=e*(1/6)*1e3,l.springs[r]=n}}function q(e){return void 0===e&&(e=10),function(n){return Math.ceil(C(n,1e-6,1)*e)*(1/e)}}var H=function(b,e,M,t){if(0<=b&&b<=1&&0<=M&&M<=1){var x=new Float32Array(11);if(b!==e||M!==t)for(var n=0;n<11;++n)x[n]=k(.1*n,b,M);return function(n){return b===e&&M===t||0===n||1===n?n:k(r(n),e,t)}}function r(n){for(var e=0,t=1;10!==t&&x[t]<=n;++t)e+=.1;var r=e+.1*((n-x[--t])/(x[t+1]-x[t])),a=O(r,b,M);if(.001<=a){for(var o=n,u=r,i=b,c=M,s=0;s<4;++s){var f=O(u,i,c);if(0===f)return u;u-=(k(u,i,c)-o)/f}return u}if(0===a)return r;for(var l,d,p=n,h=e,g=e+.1,m=b,v=M,y=0;0<(l=k(d=h+(g-h)/2,m,v)-p)?g=d:h=d,1e-7<Math.abs(l)&&++y<10;);return d}};function r(n,e){return 1-3*e+3*n}function k(n,e,t){return((r(e,t)*n+(3*t-6*e))*n+3*e)*n}function O(n,e,t){return 3*r(e,t)*n*n+2*(3*t-6*e)*n+3*e}e={linear:function(){return function(n){return n}}},t={Sine:function(){return function(n){return 1-Math.cos(n*Math.PI/2)}},Expo:function(){return function(n){return n?Math.pow(2,10*n-10):0}},Circ:function(){return function(n){return 1-Math.sqrt(1-n*n)}},Back:function(){return function(n){return n*n*(3*n-2)}},Bounce:function(){return function(n){for(var e,t=4;n<((e=Math.pow(2,--t))-1)/11;);return 1/Math.pow(4,3-t)-7.5625*Math.pow((3*e-2)/22-n,2)}},Elastic:function(n,e){void 0===e&&(e=.5);var t=C(n=void 0===n?1:n,1,10),r=C(e,.1,2);return function(n){return 0===n||1===n?n:-t*Math.pow(2,10*(n-1))*Math.sin((n-1-r/(2*Math.PI)*Math.asin(1/t))*(2*Math.PI)/r)}}},["Quad","Cubic","Quart","Quint"].forEach(function(n,e){t[n]=function(){return function(n){return Math.pow(n,e+2)}}}),Object.keys(t).forEach(function(n){var r=t[n];e["easeIn"+n]=r,e["easeOut"+n]=function(e,t){return function(n){return 1-r(e,t)(1-n)}},e["easeInOut"+n]=function(e,t){return function(n){return n<.5?r(e,t)(2*n)/2:1-r(e,t)(-2*n+2)/2}},e["easeOutIn"+n]=function(e,t){return function(n){return n<.5?(1-r(e,t)(1-2*n))/2:(r(e,t)(2*n-1)+1)/2}}});var e,t,s=e;function P(n,e){if(w.fnc(n))return n;var t=n.split("(")[0],r=s[t],a=d(n);switch(t){case"spring":return c(n,e);case"cubicBezier":return o(H,a);case"steps":return o(q,a);default:return o(r,a)}}function a(n){try{return document.querySelectorAll(n)}catch(n){}}function I(n,e){for(var t,r=n.length,a=2<=arguments.length?e:void 0,o=[],u=0;u<r;u++)u in n&&(t=n[u],e.call(a,t,u,n))&&o.push(t);return o}function f(n){return n.reduce(function(n,e){return n.concat(w.arr(e)?f(e):e)},[])}function p(n){return w.arr(n)?n:(n=w.str(n)?a(n)||n:n)instanceof NodeList||n instanceof HTMLCollection?[].slice.call(n):[n]}function h(n,e){return n.some(function(n){return n===e})}function g(n){var e,t={};for(e in n)t[e]=n[e];return t}function x(n,e){var t,r=g(n);for(t in n)r[t]=(e.hasOwnProperty(t)?e:n)[t];return r}function D(n,e){var t,r=g(n);for(t in e)r[t]=(w.und(n[t])?e:n)[t];return r}function V(n){var e,t,r,a,o,u,i;return w.rgb(n)?(e=/rgb\\((\\d+,\\s*[\\d]+,\\s*[\\d]+)\\)/g.exec(t=n))?"rgba("+e[1]+",1)":t:w.hex(n)?(e=(e=n).replace(/^#?([a-f\\d])([a-f\\d])([a-f\\d])$/i,function(n,e,t,r){return e+e+t+t+r+r}),e=/^#?([a-f\\d]{2})([a-f\\d]{2})([a-f\\d]{2})$/i.exec(e),"rgba("+parseInt(e[1],16)+","+parseInt(e[2],16)+","+parseInt(e[3],16)+",1)"):w.hsl(n)?(t=/hsl\\((\\d+),\\s*([\\d.]+)%,\\s*([\\d.]+)%\\)/g.exec(t=n)||/hsla\\((\\d+),\\s*([\\d.]+)%,\\s*([\\d.]+)%,\\s*([\\d.]+)\\)/g.exec(t),n=parseInt(t[1],10)/360,u=parseInt(t[2],10)/100,i=parseInt(t[3],10)/100,t=t[4]||1,0==u?r=a=o=i:(r=c(u=2*i-(i=i<.5?i*(1+u):i+u-i*u),i,n+1/3),a=c(u,i,n),o=c(u,i,n-1/3)),"rgba("+255*r+","+255*a+","+255*o+","+t+")"):void 0;function c(n,e,t){return t<0&&(t+=1),1<t&&--t,t<1/6?n+6*(e-n)*t:t<.5?e:t<2/3?n+(e-n)*(2/3-t)*6:n}}function B(n){n=/[+-]?\\d*\\.?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?(%|px|pt|em|rem|in|cm|mm|ex|ch|pc|vw|vh|vmin|vmax|deg|rad|turn)?$/.exec(n);if(n)return n[1]}function m(n,e){return w.fnc(n)?n(e.target,e.id,e.total):n}function v(n,e){return n.getAttribute(e)}function y(n,e,t){var r,a,o;return h([t,"deg","rad","turn"],B(e))?e:(r=l.CSS[e+t],w.und(r)?(a=document.createElement(n.tagName),(n=n.parentNode&&n.parentNode!==document?n.parentNode:document.body).appendChild(a),a.style.position="absolute",a.style.width=100+t,o=100/a.offsetWidth,n.removeChild(a),n=o*parseFloat(e),l.CSS[e+t]=n):r)}function $(n,e,t){var r;if(e in n.style)return r=e.replace(/([a-z])([A-Z])/g,"$1-$2").toLowerCase(),e=n.style[e]||getComputedStyle(n).getPropertyValue(r)||"0",t?y(n,e,t):e}function b(n,e){return w.dom(n)&&!w.inp(n)&&(!w.nil(v(n,e))||w.svg(n)&&n[e])?"attribute":w.dom(n)&&h(j,e)?"transform":w.dom(n)&&"transform"!==e&&$(n,e)?"css":null!=n[e]?"object":void 0}function W(n){if(w.dom(n)){for(var e,t=n.style.transform||"",r=/(\\w+)\\(([^)]*)\\)/g,a=new Map;e=r.exec(t);)a.set(e[1],e[2]);return a}}function X(n,e,t,r){var a=u(e,"scale")?1:0+(u(a=e,"translate")||"perspective"===a?"px":u(a,"rotate")||u(a,"skew")?"deg":void 0),o=W(n).get(e)||a;return t&&(t.transforms.list.set(e,o),t.transforms.last=e),r?y(n,o,r):o}function T(n,e,t,r){switch(b(n,e)){case"transform":return X(n,e,r,t);case"css":return $(n,e,t);case"attribute":return v(n,e);default:return n[e]||0}}function E(n,e){var t=/^(\\*=|\\+=|-=)/.exec(n);if(!t)return n;var r=B(n)||0,a=parseFloat(e),o=parseFloat(n.replace(t[0],""));switch(t[0][0]){case"+":return a+o+r;case"-":return a-o+r;case"*":return a*o+r}}function Y(n,e){var t;return w.col(n)?V(n):/\\s/g.test(n)?n:(t=(t=B(n))?n.substr(0,n.length-t.length):n,e?t+e:t)}function F(n,e){return Math.sqrt(Math.pow(e.x-n.x,2)+Math.pow(e.y-n.y,2))}function Z(n){for(var e,t=n.points,r=0,a=0;a<t.numberOfItems;a++){var o=t.getItem(a);0<a&&(r+=F(e,o)),e=o}return r}function G(n){if(n.getTotalLength)return n.getTotalLength();switch(n.tagName.toLowerCase()){case"circle":return 2*Math.PI*v(n,"r");case"rect":return 2*v(t=n,"width")+2*v(t,"height");case"line":return F({x:v(t=n,"x1"),y:v(t,"y1")},{x:v(t,"x2"),y:v(t,"y2")});case"polyline":return Z(n);case"polygon":return e=n.points,Z(n)+F(e.getItem(e.numberOfItems-1),e.getItem(0))}var e,t}function Q(n,e){var e=e||{},n=e.el||function(n){for(var e=n.parentNode;w.svg(e)&&w.svg(e.parentNode);)e=e.parentNode;return e}(n),t=n.getBoundingClientRect(),r=v(n,"viewBox"),a=t.width,t=t.height,e=e.viewBox||(r?r.split(" "):[0,0,a,t]);return{el:n,viewBox:e,x:+e[0],y:+e[1],w:a,h:t,vW:e[2],vH:e[3]}}function z(n,e){var t=/[+-]?\\d*\\.?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?/g,r=Y(w.pth(n)?n.totalLength:n,e)+"";return{original:r,numbers:r.match(t)?r.match(t).map(Number):[0],strings:w.str(n)||e?r.split(t):[]}}function A(n){return I(n?f(w.arr(n)?n.map(p):p(n)):[],function(n,e,t){return t.indexOf(n)===e})}function _(n){var t=A(n);return t.map(function(n,e){return{target:n,id:e,total:t.length,transforms:{list:W(n)}}})}function R(e){for(var t=I(f(e.map(function(n){return Object.keys(n)})),function(n){return w.key(n)}).reduce(function(n,e){return n.indexOf(e)<0&&n.push(e),n},[]),a={},n=0;n<t.length;n++)!function(n){var r=t[n];a[r]=e.map(function(n){var e,t={};for(e in n)w.key(e)?e==r&&(t.value=n[e]):t[e]=n[e];return t})}(n);return a}function J(n,e){var t,r=[],a=e.keyframes;for(t in e=a?D(R(a),e):e)w.key(t)&&r.push({name:t,tweens:function(n,t){var e,r=g(t),a=(/^spring/.test(r.easing)&&(r.duration=c(r.easing)),w.arr(n)&&(2===(e=n.length)&&!w.obj(n[0])?n={value:n}:w.fnc(t.duration)||(r.duration=t.duration/e)),w.arr(n)?n:[n]);return a.map(function(n,e){n=w.obj(n)&&!w.pth(n)?n:{value:n};return w.und(n.delay)&&(n.delay=e?0:t.delay),w.und(n.endDelay)&&(n.endDelay=e===a.length-1?t.endDelay:0),n}).map(function(n){return D(n,r)})}(e[t],n)});return r}function K(i,c){var s;return i.tweens.map(function(n){var n=function(n,e){var t,r={};for(t in n){var a=m(n[t],e);w.arr(a)&&1===(a=a.map(function(n){return m(n,e)})).length&&(a=a[0]),r[t]=a}return r.duration=parseFloat(r.duration),r.delay=parseFloat(r.delay),r}(n,c),e=n.value,t=w.arr(e)?e[1]:e,r=B(t),a=T(c.target,i.name,r,c),o=s?s.to.original:a,u=w.arr(e)?e[0]:o,a=B(u)||B(a),r=r||a;return w.und(t)&&(t=o),n.from=z(u,r),n.to=z(E(t,u),r),n.start=s?s.end:0,n.end=n.start+n.delay+n.duration+n.endDelay,n.easing=P(n.easing,n.duration),n.isPath=w.pth(e),n.isPathTargetInsideSVG=n.isPath&&w.svg(c.target),n.isColor=w.col(n.from.original),n.isColor&&(n.round=1),s=n})}var U={css:function(n,e,t){return n.style[e]=t},attribute:function(n,e,t){return n.setAttribute(e,t)},object:function(n,e,t){return n[e]=t},transform:function(n,e,t,r,a){var o;r.list.set(e,t),e!==r.last&&!a||(o="",r.list.forEach(function(n,e){o+=e+"("+n+") "}),n.style.transform=o)}};function nn(n,u){_(n).forEach(function(n){for(var e in u){var t=m(u[e],n),r=n.target,a=B(t),o=T(r,e,a,n),t=E(Y(t,a||B(o)),o),a=b(r,e);U[a](r,e,t,n.transforms,!0)}})}function en(n,e){return I(f(n.map(function(o){return e.map(function(n){var e,t,r=o,a=b(r.target,n.name);if(a)return t=(e=K(n,r))[e.length-1],{type:a,property:n.name,animatable:r,tweens:e,duration:t.end,delay:e[0].delay,endDelay:t.endDelay}})})),function(n){return!w.und(n)})}function tn(n,e){function t(n){return n.timelineOffset||0}var r=n.length,a={};return a.duration=r?Math.max.apply(Math,n.map(function(n){return t(n)+n.duration})):e.duration,a.delay=r?Math.min.apply(Math,n.map(function(n){return t(n)+n.delay})):e.delay,a.endDelay=r?a.duration-Math.max.apply(Math,n.map(function(n){return t(n)+n.duration-n.endDelay})):e.endDelay,a}var rn=0;var N,S=[],an=("undefined"!=typeof document&&document.addEventListener("visibilitychange",function(){L.suspendWhenDocumentHidden&&(n()?N=cancelAnimationFrame(N):(S.forEach(function(n){return n._onDocumentVisibility()}),an()))}),function(){!(N||n()&&L.suspendWhenDocumentHidden)&&0<S.length&&(N=requestAnimationFrame(on))});function on(n){for(var e=S.length,t=0;t<e;){var r=S[t];r.paused?(S.splice(t,1),e--):(r.tick(n),t++)}N=0<t?requestAnimationFrame(on):void 0}function n(){return document&&document.hidden}function L(n){var c,s=0,f=0,l=0,d=0,p=null;function h(n){var e=window.Promise&&new Promise(function(n){return p=n});return n.finished=e}e=x(i,n=n=void 0===n?{}:n),t=J(r=x(M,n),n),n=_(n.targets),r=tn(t=en(n,t),r),a=rn,rn++;var e,t,r,a,k=D(e,{id:a,children:[],animatables:n,animations:t,duration:r.duration,delay:r.delay,endDelay:r.endDelay});h(k);function g(){var n=k.direction;"alternate"!==n&&(k.direction="normal"!==n?"normal":"reverse"),k.reversed=!k.reversed,c.forEach(function(n){return n.reversed=k.reversed})}function m(n){return k.reversed?k.duration-n:n}function o(){s=0,f=m(k.currentTime)*(1/L.speed)}function v(n,e){e&&e.seek(n-e.timelineOffset)}function y(e){for(var n=0,t=k.animations,r=t.length;n<r;){for(var a=t[n],o=a.animatable,u=a.tweens,i=u.length-1,c=u[i],i=(i&&(c=I(u,function(n){return e<n.end})[0]||c),C(e-c.start-c.delay,0,c.duration)/c.duration),s=isNaN(i)?1:c.easing(i),f=c.to.strings,l=c.round,d=[],p=c.to.numbers.length,h=void 0,g=0;g<p;g++){var m=void 0,v=c.to.numbers[g],y=c.from.numbers[g]||0,m=c.isPath?function(e,t,n){function r(n){return e.el.getPointAtLength(1<=t+(n=void 0===n?0:n)?t+n:0)}var a=Q(e.el,e.svg),o=r(),u=r(-1),i=r(1),c=n?1:a.w/a.vW,s=n?1:a.h/a.vH;switch(e.property){case"x":return(o.x-a.x)*c;case"y":return(o.y-a.y)*s;case"angle":return 180*Math.atan2(i.y-u.y,i.x-u.x)/Math.PI}}(c.value,s*v,c.isPathTargetInsideSVG):y+s*(v-y);!l||c.isColor&&2<g||(m=Math.round(m*l)/l),d.push(m)}var b=f.length;if(b)for(var h=f[0],M=0;M<b;M++){f[M];var x=f[M+1],w=d[M];isNaN(w)||(h+=x?w+x:w+" ")}else h=d[0];U[a.type](o.target,a.property,h,o.transforms),a.currentValue=h,n++}}function b(n){k[n]&&!k.passThrough&&k[n](k)}function u(n){var e=k.duration,t=k.delay,r=e-k.endDelay,a=m(n);if(k.progress=C(a/e*100,0,100),k.reversePlayback=a<k.currentTime,c){var o=a;if(k.reversePlayback)for(var u=d;u--;)v(o,c[u]);else for(var i=0;i<d;i++)v(o,c[i])}!k.began&&0<k.currentTime&&(k.began=!0,b("begin")),!k.loopBegan&&0<k.currentTime&&(k.loopBegan=!0,b("loopBegin")),a<=t&&0!==k.currentTime&&y(0),(r<=a&&k.currentTime!==e||!e)&&y(e),t<a&&a<r?(k.changeBegan||(k.changeBegan=!0,k.changeCompleted=!1,b("changeBegin")),b("change"),y(a)):k.changeBegan&&(k.changeCompleted=!0,k.changeBegan=!1,b("changeComplete")),k.currentTime=C(a,0,e),k.began&&b("update"),e<=n&&(f=0,k.remaining&&!0!==k.remaining&&k.remaining--,k.remaining?(s=l,b("loopComplete"),k.loopBegan=!1,"alternate"===k.direction&&g()):(k.paused=!0,k.completed||(k.completed=!0,b("loopComplete"),b("complete"),!k.passThrough&&"Promise"in window&&(p(),h(k)))))}return k.reset=function(){var n=k.direction;k.passThrough=!1,k.currentTime=0,k.progress=0,k.paused=!0,k.began=!1,k.loopBegan=!1,k.changeBegan=!1,k.completed=!1,k.changeCompleted=!1,k.reversePlayback=!1,k.reversed="reverse"===n,k.remaining=k.loop,c=k.children;for(var e=d=c.length;e--;)k.children[e].reset();(k.reversed&&!0!==k.loop||"alternate"===n&&1===k.loop)&&k.remaining++,y(k.reversed?k.duration:0)},k._onDocumentVisibility=o,k.set=function(n,e){return nn(n,e),k},k.tick=function(n){u(((l=n)+(f-(s=s||l)))*L.speed)},k.seek=function(n){u(m(n))},k.pause=function(){k.paused=!0,o()},k.play=function(){k.paused&&(k.completed&&k.reset(),k.paused=!1,S.push(k),o(),an())},k.reverse=function(){g(),k.completed=!k.reversed,o()},k.restart=function(){k.reset(),k.play()},k.remove=function(n){cn(A(n),k)},k.reset(),k.autoplay&&k.play(),k}function un(n,e){for(var t=e.length;t--;)h(n,e[t].animatable.target)&&e.splice(t,1)}function cn(n,e){var t=e.animations,r=e.children;un(n,t);for(var a=r.length;a--;){var o=r[a],u=o.animations;un(n,u),u.length||o.children.length||r.splice(a,1)}t.length||r.length||e.pause()}return L.version="3.2.1",L.speed=1,L.suspendWhenDocumentHidden=!0,L.running=S,L.remove=function(n){for(var e=A(n),t=S.length;t--;)cn(e,S[t])},L.get=T,L.set=nn,L.convertPx=y,L.path=function(n,e){var t=w.str(n)?a(n)[0]:n,r=e||100;return function(n){return{property:n,el:t,svg:Q(t),totalLength:G(t)*(r/100)}}},L.setDashoffset=function(n){var e=G(n);return n.setAttribute("stroke-dasharray",e),e},L.stagger=function(n,e){var i=(e=void 0===e?{}:e).direction||"normal",c=e.easing?P(e.easing):null,s=e.grid,f=e.axis,l=e.from||0,d="first"===l,p="center"===l,h="last"===l,g=w.arr(n),m=g?parseFloat(n[0]):parseFloat(n),v=g?parseFloat(n[1]):0,y=B(g?n[1]:n)||0,b=e.start||0+(g?m:0),M=[],x=0;return function(n,e,t){if(d&&(l=0),p&&(l=(t-1)/2),h&&(l=t-1),!M.length){for(var r,a,o,u=0;u<t;u++)s?(r=p?(s[0]-1)/2:l%s[0],a=p?(s[1]-1)/2:Math.floor(l/s[0]),r=r-u%s[0],a=a-Math.floor(u/s[0]),o=Math.sqrt(r*r+a*a),"x"===f&&(o=-r),M.push(o="y"===f?-a:o)):M.push(Math.abs(l-u)),x=Math.max.apply(Math,M);c&&(M=M.map(function(n){return c(n/x)*x})),"reverse"===i&&(M=M.map(function(n){return f?n<0?-1*n:-n:Math.abs(x-n)}))}return b+(g?(v-m)/x:m)*(Math.round(100*M[e])/100)+y}},L.timeline=function(u){var i=L(u=void 0===u?{}:u);return i.duration=0,i.add=function(n,e){var t=S.indexOf(i),r=i.children;function a(n){n.passThrough=!0}-1<t&&S.splice(t,1);for(var o=0;o<r.length;o++)a(r[o]);t=D(n,x(M,u)),t.targets=t.targets||u.targets,n=i.duration,t.autoplay=!1,t.direction=i.direction,t.timelineOffset=w.und(e)?n:E(e,n),a(i),i.seek(t.timelineOffset),e=L(t),a(e),r.push(e),n=tn(r,u);return i.delay=n.delay,i.endDelay=n.endDelay,i.duration=n.duration,i.seek(0),i.reset(),i.autoplay&&i.play(),i},i},L.easing=P,L.penner=s,L.random=function(n,e){return Math.floor(Math.random()*(e-n+1))+n},L});
</script>
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
    s_key:"کلید فعلی", s_expiry:"اعتبار تا", close:"بستن", send:"ارسال", applied:"اعمال شد ✓", s_pass_t:"🔑 تغییر کلید مدیریت", s_pass_cur:"کلید فعلی", s_pass_new:"کلید جدید (حداقل ۸ نویسه)",
    s_pass_new2:"تکرار کلید جدید", s_pass_btn:"تغییر کلید",
    s_pass_hint:"پس از تغییر، فقط کلید جدید معتبر است — آن را در جای امن نگه دارید. کلید به‌صورت هش SHA-256 ذخیره می‌شود.",
    pass_changed:"کلید تغییر کرد ✓", pass_mismatch:"کلیدهای جدید یکسان نیستند", pass_len:"کلید جدید باید ۸ تا ۶۴ نویسه باشد", search_ph:"جستجوی شناسه/متن…",
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
    s_key:"Current key", s_expiry:"Valid until", close:"Close", send:"Send", applied:"Applied ✓", s_pass_t:"🔑 Change Admin Key", s_pass_cur:"Current key", s_pass_new:"New key (min 8 chars)",
    s_pass_new2:"Repeat new key", s_pass_btn:"Change key",
    s_pass_hint:"After changing, only the new key works — keep it safe. The key is stored as a SHA-256 hash.",
    pass_changed:"Key changed ✓", pass_mismatch:"New keys do not match", pass_len:"New key must be 8–64 characters", search_ph:"Search id/text…",
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
  if (typeof anime === "function") anime.remove(el);
  AN({ targets: el, bottom: [-80, 22], opacity: [0, 1], duration: 420, easing: "spring(1, 95, 12, 0)" });
  if (el._t) clearTimeout(el._t);
  el._t = setTimeout(function(){
    var a = AN({ targets: el, bottom: -80, opacity: 0, duration: 220, easing: "easeInCubic",
                 complete: function(){ el.classList.remove("on"); el.style.bottom = ""; } });
    if (!a) { el.classList.remove("on"); el.style.bottom = ""; }
  }, 2600); }
var sessionKey = "";
/* ---------- Anime.js helpers ---------- */
function AN(o){ if (typeof anime === "function") { try { return anime(o); } catch(e){} }
  else if (o && o.complete) { try { o.complete(); } catch(e){} } return null; }
function STAG(ms){ return (typeof anime === "function" && anime.stagger) ? anime.stagger(ms) : 0; }
function loginEntrance(){
  AN({ targets: ".login-card > *", translateY: [22, 0], opacity: [0, 1], delay: STAG(70), duration: 520, easing: "easeOutCubic" });
  AN({ targets: ".lock-pulse", scale: [0.6, 1], duration: 620, easing: "spring(1, 80, 10, 0)" });
}

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
  syncDD();
}
function toggleLang(){ setLang(LANG === "fa" ? "en" : "fa"); }
function setLang(l){ LANG = l; LS.set("jb_admin_lang", l); applyI18n(); renderChats(); renderStats(); }

/* ---------- theme ---------- */
function setThemeVariant(v){
  if (v === "default") document.documentElement.removeAttribute("data-theme");
  else document.documentElement.setAttribute("data-theme", v);
  LS.set("jb_admin_theme", v);
  if (v === "dracula") setMode("dark");
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
        if (!silent) { $("err-msg").textContent = t("wrong");
          AN({ targets: ".login-card", translateX: [0, -10, 10, -6, 6, 0], duration: 400, easing: "easeInOutSine" }); }
      }
    })
    .catch(function(){ if (!silent) { btn.textContent = orig; $("err-msg").textContent = t("err"); } });
}
function enterDash(){
  $("err-msg").textContent = "";
  var a = AN({ targets: "#login-box", opacity: [1, 0], scale: [1, .95], duration: 240, easing: "easeInCubic",
               complete: function(){ $("login-box").style.display = "none"; } });
  if (!a) $("login-box").style.display = "none";
  $("dash-box").classList.add("on");
  AN({ targets: ".side-head, .nav .nav-item, .side-foot .side-btn", translateX: [16, 0], opacity: [0, 1],
       delay: STAG(35), duration: 420, easing: "easeOutCubic" });
  AN({ targets: ".hdr", translateY: [-12, 0], opacity: [0, 1], duration: 420, easing: "easeOutCubic" });
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
  var lb = $("login-box");
  lb.style.display = "flex"; lb.style.opacity = ""; lb.style.transform = "";
  $("pwd").value = "";
  loginEntrance();
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
  var vw = $("view-" + tab);
  if (vw) AN({ targets: vw, opacity: [0, 1], translateY: [12, 0], duration: 300, easing: "easeOutCubic" });
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
    if (!loadStats.animated) {
      loadStats.animated = true;
      AN({ targets: "#view-overview .stat-card", translateY: [18, 0], opacity: [0, 1],
           delay: STAG(70), duration: 460, easing: "easeOutCubic" });
      [["ov-chats", d.chats], ["ov-today", d.todayMessages], ["ov-unread", d.unread],
       ["ov-msgs", d.messages], ["ov-reports", d.reports]].forEach(function(p, i) {
        var el = $(p[0]); if (!el) return;
        var o = { v: 0 }; el.textContent = "0";
        AN({ targets: o, v: p[1], round: 1, duration: 480, delay: i * 70, easing: "easeOutCubic",
             update: function() { el.textContent = o.v; },
             complete: function() { el.textContent = p[1]; } });
      });
      AN({ targets: "#ov-activity-list .lrow", translateX: [22, 0], opacity: [0, 1],
           delay: STAG(50), duration: 380, easing: "easeOutCubic" });
    }
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
  AN({ targets: "#chat-modal .modal", scale: [.9, 1], opacity: [0, 1],
       duration: 300, easing: "spring(1, 90, 11, 0)" });
  $("cm-id").textContent = id;
  openChat.threadAnim = false;
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
    if (!openChat.threadAnim) {
      openChat.threadAnim = true;
      AN({ targets: "#cm-body .msg", translateY: [10, 0], opacity: [0, 1],
           delay: STAG(45), duration: 320, easing: "easeOutCubic" });
    }
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

/* ---------- تغییر کلید مدیریت ---------- */
function changePass(){
  var cur = $("pass-cur").value, nw = $("pass-new").value, nw2 = $("pass-new2").value;
  if (!cur || !nw || !nw2) { toast(t("err")); return; }
  if (nw !== nw2) { toast(t("pass_mismatch")); return; }
  if (nw.length < 8 || nw.length > 64) { toast(t("pass_len")); return; }
  var btn = $("pass-btn"); btn.disabled = true;
  fetch("admin/pass", { method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ current: cur, next: nw }) })
    .then(function(r){ return r.json(); })
    .then(function(d){
      btn.disabled = false;
      if (d && d.ok) {
        sessionKey = nw;
        LS.set("jb_admin", JSON.stringify({ key: nw, expiry: Date.now() + 30 * 60 * 1000 }));
        $("s-key").textContent = nw.slice(0, 4) + "••••••••" + nw.slice(-4);
        $("s-expiry").textContent = new Date(Date.now() + 30 * 60 * 1000)
          .toLocaleString(LANG === "fa" ? "fa-IR" : "en-US");
        $("pass-cur").value = ""; $("pass-new").value = ""; $("pass-new2").value = "";
        toast(t("pass_changed"));
      } else { toast((d && d.error) || t("err")); }
    })
    .catch(function(){ btn.disabled = false; toast(t("err")); });
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

/* ---------- دراپ‌داون‌های سفارشی ---------- */
var DD_DATA = {
  theme: {
    items: [
      { v: "default", sw: "#6366f1", en: "Default (Indigo)" },
      { v: "ocean", sw: "#0ea5e9", en: "Ocean Blue" },
      { v: "forest", sw: "#10b981", en: "Forest Green" },
      { v: "sunset", sw: "#f43f5e", en: "Sunset Rose" },
      { v: "dracula", sw: "#bd93f9", en: "Dracula (Dark)" }
    ],
    get: function(){ return LS.get("jb_admin_theme") || "default"; },
    set: function(v){ setThemeVariant(v); }
  },
  mode: {
    items: [
      { v: "dark", ic: "🌙", fa: "تاریک", en: "Dark" },
      { v: "light", ic: "☀️", fa: "روشن", en: "Light" }
    ],
    get: function(){ return LS.get("jb_admin_mode") || "dark"; },
    set: function(v){ setMode(v); }
  },
  lang: {
    items: [
      { v: "fa", ic: "فا", fa: "فارسی", en: "فارسی" },
      { v: "en", ic: "En", fa: "English", en: "English" }
    ],
    get: function(){ return LANG; },
    set: function(v){ setLang(v); }
  }
};
function ddLabel(id, it){ return id === "theme" ? it.en : (LANG === "fa" ? it.fa : it.en); }
function buildDD(){
  var hosts = { theme: "dd-theme-menu", mode: "dd-mode-menu", lang: "dd-lang-menu" };
  Object.keys(DD_DATA).forEach(function(id){
    var host = $(hosts[id]); if (!host) return;
    var h = "";
    DD_DATA[id].items.forEach(function(it){
      var ic = it.sw
        ? '<span class="dd-swatch" style="--sw:' + it.sw + '"></span>'
        : '<span class="dd-ico">' + it.ic + '</span>';
      h += '<button type="button" class="dd-item" role="option" id="dd-' + id + '-item-' + it.v + '" data-dd="' + id + '" data-v="' + it.v + '">'
        + ic + '<span class="dd-txt">' + ddLabel(id, it) + '</span>'
        + '<svg class="dd-check" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"><path d="M20 6L9 17l-5-5"></path></svg>'
        + '</button>';
    });
    host.innerHTML = h;
  });
}
function syncDD(){
  Object.keys(DD_DATA).forEach(function(id){
    var v = DD_DATA[id].get();
    var cur = null;
    for (var i = 0; i < DD_DATA[id].items.length; i++)
      if (DD_DATA[id].items[i].v === v) cur = DD_DATA[id].items[i];
    if (!cur) return;
    var lbl = $("dd-" + id + "-label"); if (lbl) lbl.textContent = ddLabel(id, cur);
    if (id === "theme") { var dot = $("dd-theme-dot"); if (dot) dot.style.setProperty("--sw", cur.sw); }
    if (id === "mode") { var mi = $("dd-mode-ico"); if (mi) mi.textContent = cur.ic; }
    if (id === "lang") { var li = $("dd-lang-ico"); if (li) li.textContent = cur.ic; }
    for (var j = 0; j < DD_DATA[id].items.length; j++) {
      var el = $("dd-" + id + "-item-" + DD_DATA[id].items[j].v);
      if (el) el.classList.toggle("on", DD_DATA[id].items[j].v === v);
    }
  });
}
function ddOpen(id){
  var w = $("dd-" + id), menu = $("dd-" + id + "-menu");
  if (!w || !menu) return;
  w.classList.add("open");
  if (typeof anime === "function") anime.remove(menu);
  menu.style.display = "block"; menu.style.opacity = ""; menu.style.transform = "";
  AN({ targets: menu, opacity: [0, 1], scaleY: [.82, 1], translateY: [-10, 0],
       duration: 340, easing: "spring(1, 85, 11, 0)" });
  AN({ targets: menu.querySelectorAll(".dd-item"), opacity: [0, 1], translateX: [14, 0],
       delay: STAG(35), duration: 260, easing: "easeOutCubic" });
}
function ddClose(id){
  var w = $("dd-" + id), menu = $("dd-" + id + "-menu");
  if (!w) return;
  w.classList.remove("open");
  if (!menu) return;
  var a = AN({ targets: menu, opacity: 0, scaleY: .85, translateY: -6, duration: 160, easing: "easeInCubic",
               complete: function(){ menu.style.display = "none"; menu.style.opacity = ""; menu.style.transform = ""; } });
  if (!a) { menu.style.display = "none"; menu.style.opacity = ""; menu.style.transform = ""; }
}
function closeAllDD(except){
  ["theme", "mode", "lang"].forEach(function(id){ if (id !== except) ddClose(id); });
}
function toggleDD(id){
  var w = $("dd-" + id);
  var open = w.classList.contains("open");
  closeAllDD();
  if (!open) ddOpen(id);
}
function pickDD(id, v){
  closeAllDD();
  DD_DATA[id].set(v);
  syncDD();
  AN({ targets: ".logo", scale: [1, 1.15, 1], duration: 450, easing: "easeOutCubic" });
  toast(t("applied"));
}

/* ---------- boot ---------- */
buildDD();
loadThemePrefs();
applyI18n();
loginEntrance();
$("pwd").addEventListener("keydown", function(e){ if (e.key === "Enter") doLogin(false); });
$("cm-in").addEventListener("keydown", function(e){ if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); sendReply(); } });
document.getElementById("chat-modal").addEventListener("click", function(e){ if (e.target === this) closeChat(); });
document.getElementById("rep-modal").addEventListener("click", function(e){ if (e.target === this) e.target.classList.remove("on"); });
document.addEventListener("click", function(e){
  var el = e.target;
  if (!el || !el.closest) return;
  var item = el.closest(".dd-item");
  if (item) { pickDD(item.getAttribute("data-dd"), item.getAttribute("data-v")); return; }
  var trig = el.closest(".dd-trigger");
  if (trig) { toggleDD(trig.getAttribute("data-dd")); return; }
  if (!el.closest(".dd")) closeAllDD();
});
document.addEventListener("keydown", function(e){ if (e.key === "Escape") closeAllDD(); });
var sess = null;
try { sess = JSON.parse(LS.get("jb_admin") || "null"); } catch(e){}
if (sess && sess.key && sess.expiry > Date.now()) { sessionKey = sess.key; doLogin(true); }
</script>
</body></html>`;
}
