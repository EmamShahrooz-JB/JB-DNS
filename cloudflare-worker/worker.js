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
 *   GET  /admin                     → پنل مدیریت (فارسی/RTL، شبیه تلگرام)
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
        return json({ ok: true, service: "jbdns-feedback", version: 4, storage: "d1", chat: true, time: new Date().toISOString() });

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

/* ================= پنل مدیریت (تلگرام‌مانند) ================= */

function adminPage() {
  return `<!doctype html><html lang="fa" dir="rtl"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>گفتگوهای JB-DNS</title>
<style>
  *{box-sizing:border-box}
  body{font-family:Vazirmatn,Tahoma,sans-serif;background:#030820;color:#E8F6FC;margin:0;height:100vh;display:flex;flex-direction:column}
  .top{padding:14px 18px 10px;border-bottom:1px solid #12284A;background:#0A1836}
  h1{font-size:16px;margin:0 0 10px}
  .tabs{display:flex;gap:8px}
  .tab{padding:7px 16px;border-radius:10px;border:1px solid #1B3358;background:none;color:#8FB2C7;font-family:inherit;font-size:12px;cursor:pointer}
  .tab.on{background:#0096C7;border-color:#0096C7;color:#fff;font-weight:700}
  .auth{padding:12px 18px;display:flex;gap:8px;border-bottom:1px solid #12284A;background:#0A1836}
  input,textarea{font-family:inherit}
  .auth input{flex:1;padding:9px 12px;border-radius:10px;border:1px solid #1B3358;background:#0D2044;color:inherit;font-size:13px}
  .auth button{background:#0077B6;color:#fff;border:none;border-radius:10px;padding:9px 18px;font-family:inherit;font-weight:700;cursor:pointer}
  .main{flex:1;display:flex;min-height:0}
  .side{width:320px;border-inline-end:1px solid #12284A;overflow-y:auto;flex:0 0 auto}
  .citem{padding:11px 14px;cursor:pointer;border-bottom:1px solid #0D1B33}
  .citem:hover{background:#0D2044}
  .citem.on{background:#0D2044}
  .cid{display:flex;justify-content:space-between;align-items:center;font-size:10px;color:#8FB2C7;direction:ltr}
  .prev{font-size:12px;margin-top:4px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
  .badge{background:#E5484D;color:#fff;border-radius:9px;font-size:10px;font-weight:800;padding:1px 7px}
  .thread{flex:1;display:flex;flex-direction:column;min-width:0}
  .thd{padding:10px 16px;border-bottom:1px solid #12284A;background:#0A1836;font-size:12px}
  .tbody{flex:1;overflow-y:auto;padding:16px;display:flex;flex-direction:column;gap:4px}
  .msg{max-width:75%;padding:8px 12px 5px;border-radius:14px;font-size:13px;line-height:1.9;white-space:pre-wrap;word-break:break-word}
  .madmin{align-self:flex-start;background:#123059;border-bottom-right-radius:4px}
  .muser{align-self:flex-end;background:linear-gradient(135deg,#0077B6,#0096C7);border-bottom-left-radius:4px}
  .mbot{align-self:center;background:none;border:1px dashed #1B3358;color:#8FB2C7;font-size:11px}
  .mtime{font-size:9px;opacity:.7;margin-top:3px;direction:ltr;text-align:left}
  .att{background:rgba(0,0,0,.22);border-radius:9px;padding:6px 9px;margin-top:6px;font-size:11px}
  .att summary{cursor:pointer;font-weight:700}
  .cmp{display:flex;gap:8px;padding:10px;border-top:1px solid #12284A;background:#0A1836}
  .cmp textarea{flex:1;background:#0D2044;border:1px solid #1B3358;border-radius:12px;color:inherit;padding:9px 12px;font-size:13px;resize:none;height:44px;outline:none}
  .cmp button{background:#0096C7;border:none;color:#fff;border-radius:12px;padding:0 20px;font-family:inherit;font-weight:800;cursor:pointer}
  .empty{padding:30px;text-align:center;color:#8FB2C7;font-size:12px;line-height:2.2}
  .bk{display:none}
  @media(max-width:760px){.side{width:100%}.thread{display:none}.showth .side{display:none}.showth .thread{display:flex}.bk{display:inline-block;background:none;border:none;color:#8FB2C7;font-family:inherit;font-size:16px;cursor:pointer;padding:4px 10px}}
</style></head><body>
<div class="top">
  <h1>📮 گفتگوهای پشتیبانی JB-DNS <span style="font-size:10px;color:#8FB2C7">(D1 · چت زنده)</span></h1>
  <div class="tabs">
    <button class="tab on" id="tabChat" onclick="switchTab('chat')">💬 گفتگوها</button>
    <button class="tab" id="tabRep" onclick="switchTab('rep')">📮 گزارش‌های قدیمی</button>
  </div>
</div>
<div class="auth">
  <input id="tk" type="password" placeholder="توکن مدیریت (ADMIN_TOKEN)">
  <button onclick="reload()">بارگذاری</button>
</div>
<div class="main" id="wrap">
  <div class="side" id="side"><div class="empty">توکن را وارد کن و «بارگذاری» را بزن</div></div>
  <div class="thread">
    <div class="thd" id="thd" style="display:none"><button class="bk" onclick="back()">→</button><span id="tht"></span></div>
    <div class="tbody" id="tbody"><div class="empty">یک گفتگو را از فهرست انتخاب کن</div></div>
    <div class="cmp" id="cmp" style="display:none">
      <textarea id="rp" placeholder="پاسخ خود را بنویس…"></textarea>
      <button onclick="reply()">ارسال</button>
    </div>
  </div>
</div>
<div class="main" id="wrapRep" style="display:none">
  <div class="side" id="sideRep"><div class="empty">—</div></div>
  <div class="thread">
    <div class="tbody" id="repBody" style="padding:10px"><div class="empty">یک گزارش را انتخاب کن</div></div>
  </div>
</div>
<script>
var T='',CUR=null,TIMER=null;
function tk(){T=document.getElementById('tk').value.trim();sessionStorage.setItem('t',T);return T}
function esc(s){return String(s==null?'':s).replace(/[&<>"]/g,function(c){return{'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]})}
function faTime(iso){try{return new Date(iso).toLocaleString('fa-IR')}catch(e){return iso}}
function switchTab(w){
  document.getElementById('tabChat').className='tab'+(w=='chat'?' on':'');
  document.getElementById('tabRep').className='tab'+(w=='rep'?' on':'');
  document.getElementById('wrap').style.display=w=='chat'?'flex':'none';
  document.getElementById('wrapRep').style.display=w=='rep'?'flex':'none';
  if(w=='rep')loadReports();
}
function reload(){if(!tk())return;loadChats();if(document.getElementById('wrapRep').style.display!=='none')loadReports()}
function loadChats(){
  fetch('chats?t='+encodeURIComponent(T)).then(function(r){return r.json()}).then(function(r){
    var s=document.getElementById('side');
    if(!r.ok){s.innerHTML='<div class="empty">'+esc(r.error)+'</div>';return}
    if(!r.count){s.innerHTML='<div class="empty">هنوز گفتگویی شروع نشده 🌱<br>پیام اول کاربران اینجا می‌آید</div>';return}
    var h='';
    for(var i=0;i<r.chats.length;i++){var c=r.chats[i];
      h+='<div class="citem'+(CUR==c.id?' on':'')+'" onclick="openChat(\\''+c.id+'\\')">'
        +'<div class="cid"><span>'+esc(c.id)+'</span>'+(c.unread?'<span class="badge">'+c.unread+' جدید</span>':'')+'</div>'
        +'<div class="prev">'+(c.contact?'👤 '+esc(c.contact)+' · ':'')+esc((c.preview||'').slice(0,70))+'</div>'
        +'<div class="cid"><span></span><span>'+faTime(c.last_activity)+'</span></div></div>'}
    s.innerHTML=h;
  }).catch(function(){document.getElementById('side').innerHTML='<div class="empty">خطای شبکه</div>'});
}
function openChat(id){
  CUR=id;document.getElementById('wrap').className='main showth';
  loadChats();
  if(TIMER)clearInterval(TIMER);
  openChatNow(id);TIMER=setInterval(function(){if(CUR)openChatNow(CUR)},8000);
}
function back(){CUR=null;if(TIMER){clearInterval(TIMER);TIMER=null}document.getElementById('wrap').className='main';loadChats()}
function openChatNow(id){
  fetch('chat?t='+encodeURIComponent(T)+'&id='+encodeURIComponent(id)).then(function(r){return r.json()}).then(function(r){
    if(!r.ok){return}
    document.getElementById('thd').style.display='block';
    document.getElementById('cmp').style.display='flex';
    document.getElementById('tht').innerHTML='گفتگو <b dir="ltr">'+esc(r.chat.id)+'</b>'
      +(r.chat.contact?' · 👤 '+esc(r.chat.contact):'')+' · '+faTime(r.chat.created);
    var b=document.getElementById('tbody'),h='';
    for(var i=0;i<r.messages.length;i++){var m=r.messages[i];
      var cls=m.sender=='admin'?'madmin':(m.sender=='user'?'muser':'mbot');
      var att='';
      if(m.meta){
        if(m.meta.diagnostics)att+='<div class="att"><details><summary>📊 اطلاعات فنی</summary><div dir="ltr" style="font-size:10px;text-align:left;white-space:pre-wrap">'+esc(JSON.stringify(m.meta.diagnostics,null,1))+'</div></details></div>';
        if(m.meta.logs)att+='<div class="att"><details><summary>📜 لاگ‌ها</summary><div dir="ltr" style="font-size:10px;text-align:left;white-space:pre-wrap">'+esc(m.meta.logs.join('\\n'))+'</div></details></div>'}
      h+='<div class="msg '+cls+'">'+esc(m.text)+att+'<div class="mtime">'+faTime(m.time)+'</div></div>'}
    b.innerHTML=h||'<div class="empty">خالی</div>';
    b.scrollTop=b.scrollHeight;
  });
}
function reply(){
  if(!CUR)return;var ta=document.getElementById('rp');var t=ta.value.trim();
  if(!t)return;ta.value='';
  fetch('chat/reply?t='+encodeURIComponent(T),{method:'POST',headers:{'Content-Type':'application/json'},
    body:JSON.stringify({chat:CUR,text:t})}).then(function(r){return r.json()}).then(function(r){
    if(!r.ok)alert(r.error||'خطا');else openChatNow(CUR)});
}
function loadReports(){
  fetch('list?t='+encodeURIComponent(T)).then(function(r){return r.json()}).then(function(r){
    var s=document.getElementById('sideRep');
    if(!r.ok){s.innerHTML='<div class="empty">'+esc(r.error)+'</div>';return}
    if(!r.count){s.innerHTML='<div class="empty">گزارشی نیست</div>';return}
    var h='';
    for(var i=0;i<r.reports.length;i++){var x=r.reports[i];
      h+='<div class="citem" onclick="oneRep(\\''+x.id+'\\')"><div class="cid"><span>'+esc(x.id)+'</span><span>'+faTime(x.time)+'</span></div><div class="prev">'+esc(x.preview)+'</div></div>'}
    s.innerHTML=h;
  });
}
function oneRep(id){
  fetch('one?t='+encodeURIComponent(T)+'&id='+encodeURIComponent(id)).then(function(r){return r.json()}).then(function(r){
    document.getElementById('repBody').innerHTML=r.ok
      ?'<pre style="direction:ltr;text-align:left;font-size:11px;white-space:pre-wrap">'+esc(JSON.stringify(r,null,1))+'</pre>'
      :'<div class="empty">'+esc(r.error)+'</div>';
  });
}
var st=sessionStorage.getItem('t');if(st){document.getElementById('tk').value=st;reload()}
setInterval(function(){if(T&&CUR==null)loadChats()},15000);
</script></body></html>`;
}
