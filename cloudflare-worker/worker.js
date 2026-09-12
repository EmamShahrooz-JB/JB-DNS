/**
 * JB-DNS Feedback Worker — نسخهٔ D1 (SQLite)
 * ============================================================
 * گیرندهٔ گزارش‌های دکمهٔ «JB-DNS مشکلی داشت بهمون بگو!» در اپ اندروید.
 *
 * مسیرها:
 *   GET  /                     → سلام و بررسی زنده‌بودن سرویس
 *   POST /report               → ثبت گزارش (محدودیت نرخ: ۵ گزارش در ساعت از هر IP)
 *   GET  /list?t=<ADMIN_TOKEN> → فهرست ۵۰ گزارش اخیر (مدیر)
 *   GET  /one?id=<id>&t=…      → یک گزارش کامل (مدیر)
 *   GET  /admin                → صفحهٔ مرور گزارش‌ها (مرورگر، فارسی/RTL)
 *
 * الزامات دیپلوی:
 *   - D1 database با binding نام DB
 *   - secret با نام ADMIN_TOKEN:  wrangler secret put ADMIN_TOKEN
 *
 * نگهداری داده:
 *   - گزارش‌های قدیمی‌تر از ۹۰ روز هنگام هر ثبتِ موفق حذف می‌شوند
 *   - IP هرگز خام ذخیره نمی‌شود؛ فقط هش SHA-256 برای محدودیت نرخ
 */

const MAX_BODY = 48 * 1024;            // سقف کل درخواست
const RATE_LIMIT = 5;                  // حداکثر گزارش در ساعت از هر IP
const TTL_DAYS = 90;
const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET,POST,OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type",
  "Content-Type": "application/json; charset=utf-8",
};

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.method === "OPTIONS") return new Response(null, { headers: CORS });

    try {
      if (request.method === "GET" && url.pathname === "/")
        return json({ ok: true, service: "jbdns-feedback", version: 3, storage: "d1", time: new Date().toISOString() });

      if (request.method === "POST" && url.pathname === "/report")
        return await handleReport(request, env);

      if (request.method === "GET" && url.pathname === "/list")
        return await handleList(request, env, url);

      if (request.method === "GET" && url.pathname === "/one")
        return await handleOne(request, env, url);

      if (request.method === "GET" && url.pathname === "/admin")
        return new Response(adminPage(), { headers: { "Content-Type": "text/html; charset=utf-8" } });

      return json({ ok: false, error: "مسیر ناشناخته" }, 404);
    } catch (e) {
      return json({ ok: false, error: "خطای داخلی سرور" }, 500);
    }
  },
};

/* ---------------- ثبت گزارش ---------------- */

async function handleReport(request, env) {
  if (!env.DB) return json({ ok: false, error: "پایگاه‌دادهٔ D1 متصل نیست" }, 500);

  // محدودیت نرخ بر اساس هش IP (ساعت جاری)
  const ipHash = await hashIp(request.headers.get("CF-Connecting-IP") || "unknown");
  const hour = new Date().toISOString().slice(0, 13); // 2026-09-12T15
  const rlKey = `${ipHash}:${hour}`;
  const rl = await env.DB
    .prepare("SELECT count AS c FROM rate_limit WHERE key = ?")
    .bind(rlKey)
    .first();
  if (rl && rl.c >= RATE_LIMIT)
    return json({ ok: false, error: "ساعتانه حداکثر ۵ گزارش — کمی بعد دوباره امتحان کن" }, 429);

  // بدنه
  if ((request.headers.get("Content-Length") || 0) > MAX_BODY)
    return json({ ok: false, error: "گزارش بیش از حد بزرگ است" }, 413);
  const raw = await request.text();
  if (raw.length > MAX_BODY) return json({ ok: false, error: "گزارش بیش از حد بزرگ است" }, 413);

  let o;
  try { o = JSON.parse(raw); } catch { return json({ ok: false, error: "قالب نامعتبر" }, 400); }

  // متن — اجباری
  const text = typeof o.text === "string" ? o.text.trim() : "";
  if (text.length < 5) return json({ ok: false, error: "متن گزارش کوتاه است" }, 400);
  if (text.length > 4000) return json({ ok: false, error: "متن گزارش بیش از حد بلند است" }, 400);

  // راه تماس — اختیاری
  let contact = typeof o.contact === "string" ? o.contact.trim() : "";
  if (contact.length > 200) return json({ ok: false, error: "راه تماس بیش از حد بلند است" }, 400);

  // اطلاعات فنی — اختیاری، سقف حجم
  let diagnostics = null;
  if (o.diagnostics != null) {
    const s = JSON.stringify(o.diagnostics);
    if (s.length > 8192) return json({ ok: false, error: "اطلاعات فنی بیش از حد بزرگ است" }, 400);
    diagnostics = s;   // به‌صورت JSON.stringify در ستون TEXT
  }

  // لاگ‌ها — اختیاری، حداکثر ۳۰ مورد
  let logs = null;
  if (Array.isArray(o.logs)) {
    if (o.logs.length > 30) return json({ ok: false, error: "تعداد لاگ بیش از حد مجاز است" }, 400);
    for (const l of o.logs) if (typeof l === "string" && l.length > 300)
      return json({ ok: false, error: "یکی از لاگ‌ها بیش از حد بلند است" }, 400);
    logs = JSON.stringify(o.logs);
  }

  const id = randomId();
  const now = new Date().toISOString();
  const ua = (request.headers.get("User-Agent") || "").slice(0, 200);
  const cutoff = new Date(Date.now() - TTL_DAYS * 86400 * 1000).toISOString();

  // ثبت + شمارش نرخ + پاک‌سازی خودکار — یکجا و اتمیک
  await env.DB.batch([
    env.DB.prepare(
      "INSERT INTO reports(id, time, app, text, contact, diagnostics, logs, ua) VALUES(?,?,?,?,?,?,?,?)"
    ).bind(id, now, typeof o.app === "string" ? o.app.slice(0, 40) : "", text, contact || null, diagnostics, logs, ua),
    env.DB.prepare(
      "INSERT INTO rate_limit(key, count) VALUES(?, 1) ON CONFLICT(key) DO UPDATE SET count = count + 1"
    ).bind(rlKey),
    env.DB.prepare("DELETE FROM reports WHERE time < ?").bind(cutoff),
    env.DB.prepare("DELETE FROM rate_limit WHERE substr(key, -13) < ?").bind(hour),
  ]);

  return json({ ok: true, id });
}

/* ---------------- مسیرهای مدیریتی ---------------- */

async function handleList(request, env, url) {
  const guardErr = requireAdmin(env, url);
  if (guardErr) return guardErr;
  const { results } = await env.DB
    .prepare("SELECT id, time, substr(text, 1, 90) AS preview FROM reports ORDER BY time DESC LIMIT 50")
    .all();
  return json({ ok: true, count: results.length, reports: results });
}

async function handleOne(request, env, url) {
  const guardErr = requireAdmin(env, url);
  if (guardErr) return guardErr;
  const id = (url.searchParams.get("id") || "").replace(/[^a-z0-9]/gi, "");
  if (!id) return json({ ok: false, error: "شناسه لازم است" }, 400);
  const row = await env.DB.prepare("SELECT * FROM reports WHERE id = ?").bind(id).first();
  if (row == null) return json({ ok: false, error: "یافت نشد یا منقضی شده" }, 404);
  return json({
    id: row.id,
    time: row.time,
    app: row.app,
    text: row.text,
    contact: row.contact,
    diagnostics: row.diagnostics ? JSON.parse(row.diagnostics) : null,
    logs: row.logs ? JSON.parse(row.logs) : null,
    ua: row.ua,
  });
}

function requireAdmin(env, url) {
  const t = url.searchParams.get("t") || "";
  if (!env.ADMIN_TOKEN || !t || t !== env.ADMIN_TOKEN)
    return json({ ok: false, error: "دسترسی مدیریتی لازم است" }, 401);
  return null;
}

/* ---------------- صفحهٔ مدیریت (مرورگر) ---------------- */

function adminPage() {
  return `<!doctype html><html lang="fa" dir="rtl"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>گزارش‌های JB-DNS</title>
<style>
  body{font-family:Vazirmatn,Tahoma,sans-serif;background:#030820;color:#E8F6FC;margin:0;padding:24px}
  .w{max-width:720px;margin:0 auto}
  h1{font-size:18px}
  input{width:100%;box-sizing:border-box;padding:10px;border-radius:10px;border:1px solid #1B3358;background:#0A1836;color:inherit}
  .r{background:#0A1836;border-radius:14px;padding:12px 14px;margin:8px 0;cursor:pointer;border:1px solid #12284A}
  .r:hover{border-color:#48CAE4}
  .t{font-size:11px;color:#8FB2C7}.p{font-size:13px;margin-top:4px}
  pre{background:#061029;border-radius:10px;padding:12px;overflow:auto;font-size:11px;direction:ltr;text-align:left}
  button{background:#0077B6;color:#fff;border:none;border-radius:10px;padding:9px 22px;font-family:inherit;font-weight:700;cursor:pointer;margin-top:8px}
</style></head><body><div class="w">
<h1>📮 گزارش‌های JB-DNS <span style="font-size:11px;color:#8FB2C7">(D1)</span></h1>
<input id="t" type="password" placeholder="توکن مدیریت (ADMIN_TOKEN)">
<button onclick="load()">نمایش گزارش‌ها</button>
<div id="out"></div>
<script>
async function load(){
  const t=document.getElementById('t').value.trim();
  const out=document.getElementById('out');
  out.innerHTML='در حال بارگذاری…';
  const r=await fetch('list?t='+encodeURIComponent(t)).then(x=>x.json()).catch(()=>null);
  if(!r||!r.ok){out.innerHTML='<pre>'+(r&&r.error||'خطا')+'</pre>';return}
  if(!r.count){out.innerHTML='<pre>هنوز گزارشی ثبت نشده</pre>';return}
  out.innerHTML=r.reports.map(x=>'<div class="r" onclick="one(\\''+x.id+'\\')"><div class="t">'+x.time+' · '+x.id+'</div><div class="p">'+escapeHtml(x.preview)+'</div></div>').join('');
}
async function one(id){
  const t=document.getElementById('t').value.trim();
  const out=document.getElementById('out');
  out.innerHTML='در حال بارگذاری…';
  const r=await fetch('one?t='+encodeURIComponent(t)+'&id='+encodeURIComponent(id)).then(x=>x.json()).catch(()=>null);
  if(!r){out.innerHTML='<pre>خطا</pre>';return}
  out.innerHTML='<button onclick="load()">← بازگشت</button><pre>'+escapeHtml(JSON.stringify(r,null,2))+'</pre>';
}
function escapeHtml(s){return String(s||'').replace(/[&<>"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]))}
</script></div></body></html>`;
}

/* ---------------- ابزارها ---------------- */

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
