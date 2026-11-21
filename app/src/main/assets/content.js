// ============================================================
// CEIR Checker App — content.js (v106b-based, headless)
// Turnstile per-action tokens → CEIR API → result HTML overlay
// Bridge: Android.onResult / onError / onProgress / copyText / goBack / exportJpg / nativeApi
// ============================================================
(function () {
'use strict';

var API_BASE = 'https://ceir.gov.mm';

var TS_ACTIONS = {
  'VerifyNonInteractive': 'verify-device',
  'IMEI/Verify': 'verify-imei',
  'personal-device-info': 'device-data',
  'same-device': 'same-device'
};
function actionForEndpoint(path) {
  // longest key wins: 'VerifyNonInteractive' must beat 'IMEI/Verify' on the VNI path
  var best = null, bestLen = -1;
  for (var k in TS_ACTIONS) {
    if (path.indexOf(k) !== -1 && k.length > bestLen) { best = TS_ACTIONS[k]; bestLen = k.length; }
  }
  return best || 'verify-device';
}

// ---------------- sitekey discovery ----------------
// CEIR uses a DIFFERENT Turnstile sitekey per action (verified live 2026-09-30
// via turnstile.render hook on PC Chrome):
//   verify-imei -> key captured 2026-09-30 (console [TS-CAPTURED])
//   device-data -> 0x4AAAAAADmoQuDsFEizt-Hn (captured 2026-09-28, re-verified 2026-09-30)
// A token minted under the wrong action's key is rejected with "Captcha failed".
var SITEKEYS = {
  'verify-imei': '0x4AAAAAADmotCU2bSBwXlRk',
  'device-data': '0x4AAAAAADmoQuDsFEizt-Hn'
};
var DEFAULT_SITEKEY = SITEKEYS['device-data'];
// live keys captured from the site's own renders, keyed by action
var HOOKED_KEYS = {};
function hookTurnstile() {
  try {
    var tries = 0;
    var iv = setInterval(function () {
      tries++;
      if (window.turnstile && window.turnstile.render && !window.turnstile.__ceir_hooked) {
        var origRender = window.turnstile.render;
        window.turnstile.__ceir_hooked = true;
        window.turnstile.render = function (el, opts) {
          try {
            if (opts && opts.sitekey) {
              var ha = String(opts.action || '_any');
              HOOKED_KEYS[ha] = String(opts.sitekey);
            }
          } catch (e) {}
          return origRender.call(window.turnstile, el, opts);
        };
      }
      if (tries > 100) clearInterval(iv);
    }, 300);
  } catch (e) {}
}
function findSitekeySync() {
  try {
    var scripts = document.querySelectorAll('script');
    for (var i = 0; i < scripts.length; i++) {
      var t = scripts[i].textContent || '';
      var m = t.match(/0x4AAAA[A-Za-z0-9_-]{10,}/);
      if (m) return m[0];
      var s = scripts[i].src || '';
      var m2 = s.match(/0x4AAAA[A-Za-z0-9_-]{10,}/);
      if (m2) return m2[0];
    }
  } catch (e) {}
  try {
    var h = document.documentElement.innerHTML || '';
    var m3 = h.match(/0x4AAAA[A-Za-z0-9_-]{10,}/);
    if (m3) return m3[0];
  } catch (e) {}
  return null;
}
function findSitekeyAsync(action) {
  // 1) live key captured from the site's own render for this exact action — always correct
  var hk = (action && HOOKED_KEYS[action]) || null;
  if (hk) {
    console.log('[CEIR] sitekey src=hooked:' + action + ' key=' + String(hk).slice(0, 12) + '...');
    return Promise.resolve(hk);
  }
  // 2) verified per-action key (captured live 2026-09-30; device-data re-verified same day)
  var hc = (action && SITEKEYS[action]) || DEFAULT_SITEKEY;
  if (hc) {
    console.log('[CEIR] sitekey src=hardcoded:' + (action || '?') + ' key=' + String(hc).slice(0, 12) + '...');
    return Promise.resolve(hc);
  }
  // 3) last resort: page scan / chunk scan (single key, cannot be attributed per-action)
  var k = findSitekeySync();
  if (k) {
    console.log('[CEIR] sitekey src=page-scan key=' + String(k).slice(0, 12) + '...');
    return Promise.resolve(k);
  }
  // sitekey may live inside external Angular chunks: fetch same-origin scripts and scan
  var urls = [];
  try {
    var scripts = document.querySelectorAll('script[src]');
    for (var i = 0; i < scripts.length; i++) {
      var src = scripts[i].src || '';
      if (src.indexOf(location.origin) === 0 && urls.indexOf(src) === -1) urls.push(src);
    }
  } catch (e) {}
  var chain = Promise.resolve(null);
  urls.forEach(function (u) {
    chain = chain.then(function (found) {
      if (found) return found;
      return fetch(u).then(function (r) { return r.text(); }).then(function (t) {
        var m = t.match(/0x4AAAA[A-Za-z0-9_-]{10,}/);
        return m ? m[0] : null;
      }).catch(function () { return null; });
    });
  });
  return chain.then(function (found) {
    if (found) {
      console.log('[CEIR] sitekey src=chunk-fetch key=' + String(found).slice(0, 12) + '...');
      return found;
    }
    console.log('[CEIR] sitekey src=NONE');
    return null;
  });
}
function waitTurnstile(timeoutMs) {
  return new Promise(function (resolve) {
    var t0 = Date.now();
    var iv = setInterval(function () {
      if (window.turnstile && window.turnstile.render) { clearInterval(iv); resolve(true); }
      else if (Date.now() - t0 > timeoutMs) { clearInterval(iv); resolve(false); }
    }, 400);
  });
}
// Mobile: the site loads Turnstile late (or never). Inject api.js ourselves.
var TS_API_URL = 'https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit';
var tsApiInjected = false;
function injectTurnstileApi() {
  return new Promise(function (resolve) {
    if (window.turnstile && window.turnstile.render) { resolve(true); return; }
    if (tsApiInjected) { resolve(!!(window.turnstile && window.turnstile.render)); return; }
    tsApiInjected = true;
    var done = false;
    function fin(v) { if (!done) { done = true; resolve(v); } }
    try {
      var s = document.createElement('script');
      s.src = TS_API_URL;
      s.async = true;
      s.onload = function () { fin(true); };
      s.onerror = function () { fin(false); };
      document.head.appendChild(s);
      setTimeout(function () { fin(!!(window.turnstile && window.turnstile.render)); }, 25000);
    } catch (e) { fin(false); }
  });
}
function ensureTurnstile() {
  // v2 (2026-09-30, fix B): inject immediately — don't burn 8s waiting for the
  // site's own script on mobile. injectTurnstileApi is idempotent; if the site
  // loads its copy later it just re-defines the same API (harmless).
  try { progress('Turnstile loading...'); } catch (e) {}
  try { setLoadMsg('Turnstile loading...'); } catch (e) {}
  console.log('[CEIR] stage: turnstile-inject');
  return injectTurnstileApi().then(function () {
    return waitTurnstile(25000);
  }).then(function (ok) {
    console.log('[CEIR] stage: turnstile-ready=' + ok);
    return ok;
  });
}

// ---------------- widgets & tokens ----------------
// Widgets are keyed action#slot so parallel calls (e.g. the 2 IMEI Verifies)
// each own a widget + pending slot and can mint tokens concurrently.
// The sitekey still depends on the ACTION only — never the slot — so the
// per-action key fix (2026-09-30) is untouched.
var tsWidgets = {};
var tsHolders = {};
var tsPendingByKey = {};
var tsHealRefresh = {};
var tokenQueue = Promise.resolve();

function wkey(action, slot) { return action + '#' + (slot || 0); }
// Extension-port (2026-11-21, token-timeout fix): forget a wedged widget so
// the next mint re-renders fresh. Holder is removed too — a stale holder
// would otherwise keep the old widget's DOM on the page.
function forgetWidgetSlot(action, slot) {
  var k = wkey(action, slot);
  try {
    var wid = tsWidgets[k];
    if (wid != null && window.turnstile && window.turnstile.remove) {
      try { window.turnstile.remove(wid); } catch (e) {}
    }
  } catch (e) {}
  try {
    var h = tsHolders[k];
    if (h && h.parentNode) h.parentNode.removeChild(h);
  } catch (e) {}
  delete tsWidgets[k];
  delete tsHolders[k];
  tsPendingByKey[k] = null;
}
// Extension-port: the key that produced repeated token timeouts is suspect.
// Drop the hooked key for this action and mark the slot for a fresh-hook
// wait on re-render — the next find falls back to the verified hardcoded
// per-action key if no fresh hook arrives.
function healWidgetSlot(action, slot) {
  try {
    console.log('[CEIR] stage: self-heal ' + wkey(action, slot));
    if (HOOKED_KEYS[action]) delete HOOKED_KEYS[action];
    forgetWidgetSlot(action, slot);
    tsHealRefresh[wkey(action, slot)] = true;
  } catch (e) {}
}
function waitHookedKey(action, ms) {
  return new Promise(function (resolve) {
    var t0 = Date.now();
    var iv = setInterval(function () {
      if (HOOKED_KEYS[action]) { clearInterval(iv); resolve(true); }
      else if (Date.now() - t0 > ms) { clearInterval(iv); resolve(false); }
    }, 500);
  });
}
function ensureWidgetSlot(action, slot) {
  var k = wkey(action, slot);
  return new Promise(function (resolve) {
    if (tsWidgets[k] != null) { resolve(true); return; }
    // After a self-heal, give the site up to 5s to render a fresh hooked
    // key before falling back to findSitekeyAsync (hardcoded per-action).
    var start = tsHealRefresh[k]
      ? waitHookedKey(action, 5000).then(function () { tsHealRefresh[k] = false; })
      : Promise.resolve();
    start.then(function () { return findSitekeyAsync(action); }).then(function (key) {
      if (!key) { resolve(false); return; }
      var holder = document.createElement('div');
      holder.style.cssText = 'position:fixed;width:2px;height:2px;left:-20px;top:-20px;overflow:hidden;opacity:0.01;pointer-events:none;';
      document.body.appendChild(holder);
      tsHolders[k] = holder;
      try {
        var wid = window.turnstile.render(holder, {
          sitekey: key, action: action,
          theme: 'auto', appearance: 'interaction-only', execution: 'execute', size: 'invisible',
          callback: function (t) { var r = tsPendingByKey[k]; tsPendingByKey[k] = null; if (r) r(t); },
          'error-callback': function () { var r = tsPendingByKey[k]; tsPendingByKey[k] = null; if (r) r(null); },
          'expired-callback': function () {}
        });
        tsWidgets[k] = wid;
        resolve(true);
      } catch (e) { resolve(false); }
    });
  });
}
function tokenForSlot(action, slot) {
  var k = wkey(action, slot);
  return new Promise(function (resolve, reject) {
    var wid = tsWidgets[k];
    if (wid == null) { reject(new Error('no widget')); return; }
    var done = false;
    // Phase 1: 60s -> 25s timeout; reset the widget on timeout so the next mint starts clean.
    var to = setTimeout(function () {
      try { window.turnstile.reset(wid); } catch (e) {}
      finish(null, new Error('token timeout (' + k + ')'));
    }, 25000);
    // Extension-port: poll getResponse() — catches a token whose callback
    // never fired (the exact token-timeout symptom, 2026-11-21).
    var iv = setInterval(function () {
      if (done) return;
      try {
        var g = (window.turnstile && window.turnstile.getResponse)
          ? window.turnstile.getResponse(wid) : '';
        if (g && g.length > 20) {
          console.log('[CEIR] token-poll-hit action=' + action + ' slot=' + slot);
          finish(g);
        }
      } catch (e) {}
    }, 500);
    function finish(t, err) {
      if (done) return; done = true; clearTimeout(to); clearInterval(iv);
      tsPendingByKey[k] = null;
      if (err) reject(err); else resolve(t);
    }
    tsPendingByKey[k] = function (t) {
      if (t) finish(t); else finish(null, new Error('turnstile error'));
    };
    setTimeout(function () {
      if (done) return;
      try { window.turnstile.reset(wid); } catch (e) {}
      setTimeout(function () {
        if (done) return;
        try { window.turnstile.execute(wid); } catch (e) { finish(null, e); }
      }, 300);
    }, 100);
  });
}
function freshTokenSlot(action, slot) {
  // Direct (unqueued): safe because each slot owns its widget + pending slot.
  return ensureWidgetSlot(action, slot).then(function (ok) {
    if (!ok) throw new Error('widget မရ (' + wkey(action, slot) + ')');
    return tokenForSlot(action, slot);
  });
}
function freshTokenQueued(action) {
  // Serialized path, kept for retries / single-shot calls (slot 0).
  var p = tokenQueue.then(function () { return freshTokenSlot(action, 0); });
  tokenQueue = p.catch(function () {});
  return p;
}
function sleep(ms) { return new Promise(function (r) { setTimeout(r, ms); }); }
// Phase 1: exponential backoff + jitter (attempt is 1-based): 800, 1600, 3200ms + 0..400ms
function backoffMs(attempt) {
  return Math.floor(800 * Math.pow(2, attempt - 1) + Math.random() * 400);
}

// CEIR API over the native OkHttp bridge (separate TLS stack from WebView,
// browser-like headers, Chrome-like UA without "; wv").
// The fresh single-use Turnstile token is still solved here per call and passed
// as an argument — it is never logged. Returns the same shape apiCall expects.
function nativeApiCall(method, path, token, body) {
  return new Promise(function (resolve) {
    var out = null;
    try {
      out = JSON.parse(Android.nativeApi(method, path, token, body ? JSON.stringify(body) : ''));
    } catch (e) {
      resolve({ status: 0, ok: false, statusText: 'bridge error',
        text: 'nativeApi failed: ' + ((e && e.message) || e), path: path });
      return;
    }
    resolve({ status: out.status, ok: out.ok, statusText: '',
      text: (out && out.text) || '', path: path });
  });
}

function apiCall(method, path, body, preTokenP) {
  var act = actionForEndpoint(path);
  function attempt(n) {
    // preTokenP: a token minted in parallel earlier (fix D). If it failed,
    // fall back to a queued mint. Retries always mint fresh via the queue.
    var tokP = (n === 1 && preTokenP)
      ? preTokenP.then(function (t) { return t ? t : freshTokenQueued(act); })
      : freshTokenQueued(act);
    return tokP.then(function (token) {
      if (!token) throw new Error('token empty (' + act + ')');
      console.log('[CEIR] token ok action=' + act + ' len=' + String(token).length);
      return nativeApiCall(method, path, token, body);
    }, function (te) {
      // Extension-port: a token failure (timeout/empty/widget) is retried
      // like a captcha failure — previously it killed the whole check.
      // Heal (forget widget + drop hooked key) before the final attempt.
      var msg = ((te && te.message) || te);
      if (n < 3) {
        console.log('[CEIR] stage: api-token-retry action=' + act + ' n=' + n + ' err=' + msg);
        if (n === 2) { try { healWidgetSlot(act, 0); } catch (e) {} }
        return sleep(backoffMs(n)).then(function () { return attempt(n + 1); });
      }
      throw new Error('token fail (' + act + '): ' + msg);
    }).then(function (res) {
      // Phase 1: 3 attempts, exponential backoff + jitter
      if (/captcha failed/i.test(res.text) && n < 3) return sleep(backoffMs(n)).then(function () { return attempt(n + 1); });
      return res;
    });
  }
  return attempt(1);
}
function apiJSON(method, path, body, preTokenP) {
  return apiCall(method, path, body, preTokenP).then(function (r) {
    var j = null;
    try { j = JSON.parse(r.text); } catch (e) {}
    r.json = j;
    return r;
  });
}

// ---------------- TAC ----------------
// Fix E (2026-09-30): tac.json (13MB) is parsed ONCE natively in MainActivity
// and served via the Android.tacLookup(imei) bridge — no more 13MB
// evaluateJavascript injection on every check.
function tacLookup(imei) {
  try {
    if (typeof Android !== 'undefined' && Android.tacLookup) {
      var j = JSON.parse(Android.tacLookup(String(imei || '')));
      if (j && (j.brand || j.model)) return { brand: j.brand || '', model: j.model || '' };
    }
  } catch (e) {
    console.log('[CEIR] tacLookup bridge failed: ' + ((e && e.message) || e));
  }
  return null;
}

// ---------------- helpers ----------------
function esc(s) {
  return String(s == null ? '' : s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}
function badge(txt, color) {
  return '<span class="cbadge cb-' + color + '">' + esc(txt) + '</span>';
}
// CEIR paymentState values (verified live 2026-09-30):
//   paid device  → "ACCUMULATION" (NOT "PAID"), canPay=false
//   unpaid device → "UNPAID", canPay=true
function paidStateOf(ps) {
  var s = String(ps || '').toLowerCase();
  if (s.indexOf('accumulation') !== -1) return 'paid';
  if (s.indexOf('paid') !== -1 && s.indexOf('unpaid') === -1 && s.indexOf('not') === -1) return 'paid';
  if (s.indexOf('unpaid') !== -1) return 'unpaid';
  return 'unknown';
}
function payBadge(ps) {
  var st = paidStateOf(ps);
  if (st === 'paid') return badge('✓ Paid', 'green');
  if (st === 'unpaid') return badge('✗ Unpaid', 'red');
  return badge(String(ps || '?'), 'blue'); // unknown → show raw value, never mislabel
}
function blockBadge(bs) {
  var s = String(bs || '').toLowerCase();
  if (s.indexOf('unblock') !== -1 || s.indexOf('allow') !== -1) return badge('✓ Allowed', 'green');
  return badge('✗ Blocked', 'red');
}
function isPaidResult(r) {
  // Paid-status ground truth is paymentState from single IMEI/Verify.
  // Paid devices return "ACCUMULATION" (verified 2026-09-30), not "PAID".
  // blockState UNBLOCKED alone must NOT classify as paid.
  return paidStateOf(r.paymentState) === 'paid';
}
// ---------------- Verify 403 diagnostics (instrumentation only; no behavior change) ----------------
// path carries NO query string: the Turnstile token is single-use and must never be logged.
// Cookie NAMES only, never values.
function buildVerifyDiag(r) {
  var lines = ['=== CEIR Verify Diagnostics ==='];
  try { lines.push('time: ' + new Date().toISOString()); } catch (e) {}
  lines.push('status: ' + r.status + ' ' + (r.statusText || ''));
  lines.push('path: ' + (r.path || ''));
  var cf = 'unknown';
  try {
    var names = String(document.cookie || '').split(';').map(function (c) { return String(c).split('=')[0].trim(); });
    cf = (names.indexOf('cf_clearance') !== -1) ? 'yes' : 'no';
  } catch (e) {}
  lines.push('cf_clearance cookie: ' + cf);
  try { lines.push('userAgent: ' + navigator.userAgent); } catch (e) {}
  var body = '';
  try { body = String(r.text || '').slice(0, 1500); } catch (e) {}
  lines.push('body (' + body.length + ' chars):');
  lines.push(body);
  return lines.join('\n');
}

// Single IMEI/Verify per IMEI is the paid-status ground truth
// (VerifyNonInteractive misreports paid state for some devices).
// v2 (2026-09-30, fix C): IMEIs verify IN PARALLEL — each gets its own widget
// slot ('verify-imei#0', 'verify-imei#1') so token minting overlaps instead of
// running strictly one-after-another.
function verifyOne(imei, idx, attemptNo) {
  return freshTokenSlot('verify-imei', idx).then(function (token) {
    if (!token) throw new Error('token empty (verify-imei#' + idx + ')');
    console.log('[CEIR] token ok action=verify-imei slot=' + idx + ' len=' + String(token).length);
    return nativeApiCall('POST', '/openapi/API/IMEI/Verify', token, [String(imei)]);
  }).then(function (r) {
    // Phase 1: 3 attempts, exponential backoff + jitter
    if (/captcha failed/i.test(r.text) && attemptNo < 3) {
      console.log('[CEIR] stage: verify-retry idx=' + idx + ' n=' + attemptNo);
      return sleep(backoffMs(attemptNo)).then(function () { return verifyOne(imei, idx, attemptNo + 1); });
    }
    if (!r.ok) {
      var t = '';
      try { t = String(r.text || '').slice(0, 100); } catch (e) {}
      var err = new Error('API ' + r.status + ' ' + t);
      try { err.diagText = buildVerifyDiag(r); } catch (e) {}
      throw err;
    }
    var data = null;
    try { data = JSON.parse(r.text); } catch (e) {}
    var list = (data && data.IMEI_CHECK_LIST) || [];
    if (!list.length) throw new Error('IMEI မတွေ့ (empty list)');
    console.log('[CEIR] stage: verify-ok idx=' + idx + ' paymentState=' + ((list[0] && list[0].paymentState) || '?'));
    return list[0];
  }).catch(function (e) {
    // Extension-port: token timeout/empty/widget errors are retried up to
    // 3 attempts total — previously ONE timeout killed the whole check
    // (field error 2026-11-21: token timeout (verify-imei#0)).
    // Non-token errors (API !ok, empty list) are NOT retried here.
    var msg = (e && e.message) || '';
    var isTokenErr = /token timeout|token empty|widget|turnstile error|no widget|token fail/.test(msg);
    // _tokenExhausted: an inner verifyOne already used up its 3 attempts —
    // do NOT let an outer (captcha-retry) catch restart the count.
    if (isTokenErr && attemptNo < 3 && !e._tokenExhausted) {
      console.log('[CEIR] stage: verify-token-retry idx=' + idx + ' n=' + attemptNo + ' err=' + msg);
      if (attemptNo === 2) { try { healWidgetSlot('verify-imei', idx); } catch (err) {} }
      return sleep(backoffMs(attemptNo)).then(function () { return verifyOne(imei, idx, attemptNo + 1); });
    }
    if (isTokenErr) { try { e._tokenExhausted = true; } catch (err) {} }
    throw e;
  });
}
function verifyEachSingle(imeis) {
  progress('Verifying ' + imeis.length + ' IMEI(s)...');
  setLoadMsg('Verifying IMEI(s)...');
  console.log('[CEIR] stage: verify-start n=' + imeis.length);
  var jobs = imeis.map(function (imei, idx) {
    return verifyOne(imei, idx, 1).then(function (one) {
      return { idx: idx, one: one };
    }, function (e) {
      // FIX: keep diagText through the wrapper so the Diagnostics card renders
      var ne = new Error('IMEI ' + (idx + 1) + ' (' + shortImei(imei) + '): ' + ((e && e.message) || e));
      try { if (e && e.diagText) ne.diagText = e.diagText; } catch (x) {}
      throw ne;
    });
  });
  return Promise.all(jobs).then(function (pairs) {
    pairs.sort(function (a, b) { return a.idx - b.idx; });
    console.log('[CEIR] stage: verify-done');
    return pairs.map(function (p) { return p.one; });
  });
}
function progress(m) { try { Android.onProgress(m); } catch (e) {} }
function shortImei(im) {
  return im && im.length >= 15 ? im.slice(0, 8) + ' ' + im.slice(8) : (im || '');
}

// ---------------- overlay UI ----------------
var CSS = ''
+ '.cxr{position:fixed;inset:0;z-index:2147483647;background:#0A0A0B;overflow-y:auto;font-family:-apple-system,"Segoe UI",sans-serif;color:#F1F5F9;}'
+ '.cxr-in{max-width:480px;margin:0 auto;padding:18px 14px 26px;}'
+ '.cxr-h{text-align:center;margin-bottom:14px;}'
+ '.cxr-h h1{font-size:20px;font-weight:800;margin:0;color:#FFFFFF;}'
+ '.cxr-h h1 span{color:#FFC400;}'
+ '.cxr-h p{font-size:11px;color:#94A3B8;margin:2px 0 0;}'
+ '.cxr-card{background:#141416;border:1px solid #26262B;border-left:3px solid #FFC400;border-radius:16px;padding:14px;margin-bottom:12px;}'
+ '.cxr-card h3{font-size:14px;font-weight:700;margin:0 0 10px;color:#F1F5F9;}'
+ '.cxr-chead{display:flex;justify-content:space-between;align-items:center;margin:0 0 10px;}'
+ '.cxr-chead h3{margin:0;}'
+ '.cxr-kv{display:flex;justify-content:space-between;align-items:center;padding:7px 0;border-bottom:1px solid #26262B;font-size:13px;}'
+ '.cxr-kv:last-child{border-bottom:none;}'
+ '.cxr-kv .k{color:#94A3B8;font-weight:600;white-space:nowrap;}'
+ '.cxr-kv .v{font-weight:700;text-align:right;color:#F1F5F9;}'
+ '.cbadge{font-size:11px;padding:3px 10px;border-radius:999px;font-weight:700;white-space:nowrap;}'
+ '.cb-green{background:rgba(34,197,94,.14);color:#4ADE80;}'
+ '.cb-red{background:rgba(239,68,68,.14);color:#F87171;}'
+ '.cxr-grid2{display:grid;grid-template-columns:1fr 1fr;}'
+ '.cxr-grid2>div{padding:0 8px;min-width:0;}'
+ '.cxr-grid2>div:first-child{border-right:1px dashed #26262B;}'
+ '.cxr-srow{padding:7px 2px;border-bottom:1px solid #26262B;}'
+ '.cxr-srow:last-child{border-bottom:none;}'
+ '.cxr-srow .sk{color:#94A3B8;font-weight:600;font-size:12px;margin-bottom:5px;white-space:nowrap;}'
+ '.cxr-imei{font-size:13px;margin-bottom:6px;font-weight:700;color:#F1F5F9;white-space:nowrap;}'
+ '.cxr-imei code{font-size:12px;color:#F1F5F9;font-weight:400;white-space:nowrap;}'
+ '.cxr-paid{background:#052E16;border:2px solid #16A34A;text-align:center;}'
+ '.cxr-paid .t{font-size:18px;font-weight:800;color:#4ADE80;margin-bottom:8px;}'
+ '.cxr-btn{width:100%;padding:13px;font-size:15px;font-weight:700;border:none;border-radius:12px;cursor:pointer;margin-top:8px;}'
+ '.cxr-bp{background:#FFC400;color:#0A0A0B;}'
+ '.cxr-bd{background:#26262B;color:#F1F5F9;}'
+ '.cxr-row2{display:flex;gap:8px;}'
+ '.cxr-row2 .cxr-btn{margin-top:8px;}'
+ '.cxr-copy{font-size:12px;padding:6px 12px;border-radius:8px;border:1px solid #26262B;background:transparent;cursor:pointer;font-weight:700;color:#94A3B8;}'
+ '.cxr-imeirow{display:flex;align-items:center;justify-content:space-between;gap:8px;}'
+ '.cxr-load{text-align:center;padding:60px 20px;color:#94A3B8;font-size:14px;}'
+ '.cxr-spin{width:36px;height:36px;border:4px solid #26262B;border-top-color:#FFC400;border-radius:50%;margin:0 auto 16px;animation:cxrspin 1s linear infinite;}'
+ '@keyframes cxrspin{to{transform:rotate(360deg);}}'
+ '.cxr-err{background:rgba(239,68,68,.10);border:1px solid #7F1D1D;color:#FCA5A5;border-radius:12px;padding:14px;font-size:14px;}'
+ '.cxr-diag{background:#0A0A0B;color:#CBD5E1;border:1px solid #26262B;font-family:monospace;font-size:11px;line-height:1.5;border-radius:8px;padding:10px;white-space:pre-wrap;word-break:break-word;max-height:320px;overflow:auto;margin:10px 0;}';

function ensureOverlay() {
  var root = document.getElementById('cxr-root');
  if (root) return root;
  var st = document.createElement('style');
  st.textContent = CSS;
  document.head.appendChild(st);
  root = document.createElement('div');
  root.id = 'cxr-root';
  root.className = 'cxr';
  root.innerHTML = '<div class="cxr-in" id="cxr-in"></div>';
  document.body.appendChild(root);
  return root;
}
function setHtml(html) {
  ensureOverlay();
  document.getElementById('cxr-in').innerHTML = html;
}
function headerHtml(sub) {
  return '<div class="cxr-h"><h1>CEIR <span>Checker</span></h1><p>' + esc(sub || '') + '</p></div>';
}
function showLoading(msg) {
  setHtml(headerHtml('checking...')
    + '<div class="cxr-load"><div class="cxr-spin"></div><div id="cxr-loadmsg">' + esc(msg || 'Checking...') + '</div></div>');
}
function setLoadMsg(m) {
  var el = document.getElementById('cxr-loadmsg');
  if (el) el.textContent = m;
}
window.__cxr_setLoadMsg = setLoadMsg;

function copyBtnHtml(imei) {
  return '<button class="cxr-copy" data-imei="' + esc(imei) + '" onclick="__cxr_copy(this)">📋 Copy</button>';
}
window.__cxr_copy = function (btn) {
  var imei = btn.getAttribute('data-imei') || '';
  try { Android.copyText(imei); } catch (e) {}
  btn.textContent = '✓ Copied';
  setTimeout(function () { btn.textContent = '📋 Copy'; }, 1500);
};
window.__cxr_copyBoth = function (btn, a, b) {
  try { Android.copyText(a + '\n' + b); } catch (e) {}
  btn.textContent = '✓ Copied';
  setTimeout(function () { btn.textContent = '📋 Copy both'; }, 1500);
};
var __cxr_lastDiag = '';
window.__cxr_copyDiag = function (btn) {
  var t = __cxr_lastDiag || '';
  var done = function () { btn.textContent = '✓ Copied'; setTimeout(function () { btn.textContent = '📋 Copy diagnostics'; }, 1500); };
  try { Android.copyText(t); done(); return; } catch (e) {}
  try {
    if (navigator.clipboard && navigator.clipboard.writeText) { navigator.clipboard.writeText(t).then(done, function () {}); }
  } catch (e) {}
};
window.__cxr_back = function () { try { Android.goBack(); } catch (e) {} };
window.__cxr_export = function () {
  try {
    // Hide the Back / Export JPG buttons for the screenshot, restore afterwards.
    var rows = document.querySelectorAll('.cxr-row2');
    for (var i = 0; i < rows.length; i++) rows[i].style.display = 'none';
    setTimeout(function () {
      try { Android.exportJpg(); } catch (e) {}
      setTimeout(function () {
        for (var j = 0; j < rows.length; j++) rows[j].style.display = '';
      }, 600);
    }, 180);
  } catch (e) {}
};

function buttonsHtml() {
  return '<div class="cxr-row2">'
    + '<button class="cxr-btn cxr-bd" onclick="__cxr_back()">← Back</button>'
    + '<button class="cxr-btn cxr-bd" onclick="__cxr_export()">↓ Export JPG</button>'
    + '</div>';
}

function creditHtml() {
  return '<div style="text-align:center;color:#64748B;font-size:12px;margin:14px 0 4px">Developed by ဖီးနစ် (ထူးကြီး)</div>';
}

function deviceCardHtml(info, brand, model) {
  function row(k, v) {
    return '<div class="cxr-kv"><span class="k">' + esc(k) + '</span><span class="v">' + esc(v || '—') + '</span></div>';
  }
  return '<div class="cxr-card"><h3>📱 Device</h3>'
    + row('Manufacturer', info.gsmaManufacturer)
    + row('Brand', brand)
    + row('Model', model)
    + row('Allocation date', info.gsmaAllocationDate)
    + row('Operating system', info.gsmaOperatingSystem)
    + row('Device type', info.gsmaDeviceType)
    + '</div>';
}

function statusColHtml(r, idx, showTax) {
  var im = r.IMEI || '';
  var h = '<div><div class="cxr-imei">IMEI ' + (idx + 1) + '<br><code>' + esc(shortImei(im)) + '</code></div>'
    + '<div class="cxr-srow"><div class="sk">IMEI Status</div><div>' + badge('✓ Correct', 'green') + '</div></div>';
  if (showTax) h += '<div class="cxr-srow"><div class="sk">Tax Payment</div><div>' + payBadge(r.paymentState) + '</div></div>';
  h += '<div class="cxr-srow"><div class="sk">Blocking</div><div>' + blockBadge(r.blockState) + '</div></div></div>';
  return h;
}

function statusHeadHtml(list) {
  var a = ((list[0] || {}).IMEI) || '';
  var b = ((list[1] || {}).IMEI) || '';
  return '<div class="cxr-chead"><h3>📊 Status</h3>'
    + '<button class="cxr-copy" onclick="__cxr_copyBoth(this,\'' + esc(a) + '\',\'' + esc(b) + '\')">📋 Copy both</button></div>';
}

function renderPaid(list) {
  var first = list[0] || {};
  var t = tacLookup(first.IMEI || '');
  var b = (t && t.brand) || '—';
  var m = (t && t.model) || '—';
  var cols = list.map(function (r, i) { return statusColHtml(r, i, true); }).join('');
  setHtml(headerHtml('check result')
    + '<div class="cxr-card cxr-paid"><div class="t">✅ Paid — Done</div>'
    + '<div style="font-size:15px"><span style="color:#94A3B8;font-size:13px;font-weight:600">Brand</span> '
    + '<span style="color:#EA580C;font-weight:800">' + esc(b) + '</span>'
    + '&nbsp;&nbsp;<span style="color:#94A3B8;font-size:13px;font-weight:600">Model</span> '
    + '<span style="color:#EA580C;font-weight:800">' + esc(m) + '</span></div></div>'
    + '<div class="cxr-card">' + statusHeadHtml(list) + '<div class="cxr-grid2">' + cols + '</div></div>'
    + buttonsHtml() + creditHtml());
}

function renderUnpaid(list, info, brand, model) {
  var cols = list.map(function (r, i) { return statusColHtml(r, i, true); }).join('');
  setHtml(headerHtml('check result')
    + deviceCardHtml(info, brand, model)
    + '<div class="cxr-card">' + statusHeadHtml(list) + '<div class="cxr-grid2">' + cols + '</div></div>'
    + buttonsHtml() + creditHtml());
}

function renderError(msg, diagText) {
  __cxr_lastDiag = diagText || '';
  var d = '';
  if (diagText) {
    d = '<div class="cxr-card"><h3>🔍 Diagnostics</h3>'
      + '<pre class="cxr-diag">' + esc(diagText) + '</pre>'
      + '<button class="cxr-copy" onclick="__cxr_copyDiag(this)">📋 Copy diagnostics</button></div>';
  }
  setHtml(headerHtml('error')
    + '<div class="cxr-err">⚠️ ' + esc(msg) + '</div>'
    + d
    + buttonsHtml());
}

// ---------------- main check flow ----------------
var checkRunning = false;
window.receiveImeis = function (imei1, imei2) {
  if (checkRunning) return;
  checkRunning = true;
  doCheck(imei1, imei2).catch(function (e) {
    var msg = (e && e.message) || String(e);
    progress('Error: ' + msg);
    renderError(msg, e && e.diagText);
    try { Android.onError(msg); } catch (err) {}
    checkRunning = false;
  });
};

function doCheck(imei1, imei2) {
  var imeis = [];
  if (imei1) imeis.push(String(imei1));
  if (imei2) imeis.push(String(imei2));
  if (!imeis.length) return Promise.reject(new Error('IMEI မရှိ'));

  showLoading('Checking IMEI...');
  progress('Checking IMEI...');
  console.log('[CEIR] stage: check-start');

  return ensureTurnstile().then(function (okTs) {
    if (!okTs) throw new Error('Turnstile မပွင့် (network စစ်ပါ)');
    hookTurnstile();
    // Fix D: pre-mint the device-data token NOW, in parallel with the Verifies,
    // so the unpaid path doesn't pay another full token round-trip afterwards.
    // If the pre-mint fails, finishUnpaid falls back to a queued mint.
    var deviceTokenP = freshTokenSlot('device-data', 0).then(function (t) {
      console.log('[CEIR] stage: device-token-preminted len=' + (t ? String(t.length) : 'null'));
      return t;
    }, function (e) {
      console.log('[CEIR] stage: device-token-premint failed: ' + ((e && e.message) || e));
      return null;
    });
    // Paid-status ground truth: single IMEI/Verify per IMEI (VNI paymentState is unreliable).
    return verifyEachSingle(imeis).then(function (resolved) {
      return { resolved: resolved, deviceTokenP: deviceTokenP };
    });
  }).then(function (out) {
    var allPaid = out.resolved.every(isPaidResult);
    if (allPaid) {
      return finishPaid(out.resolved);
    }
    return finishUnpaid(out.resolved, imeis, out.deviceTokenP);
  });
}

function finishPaid(list) {
  progress('Paid ✓');
  // TAC brand/model must be saved too, otherwise History shows "Unknown Device"
  // (renderPaid does its own tacLookup for display only).
  var first = (list && list[0]) || {};
  var t = tacLookup(first.IMEI || '');
  var brand = (t && t.brand) ? t.brand : '';
  var model = (t && t.model) ? t.model : '';
  var out = buildResultJson(list, {}, brand, model);
  try { Android.onResult(JSON.stringify(out)); } catch (e) {}
  renderPaid(list);
  checkRunning = false;
}

function finishUnpaid(list, imeis, deviceTokenP) {
  progress('Loading device info...');
  setLoadMsg('Loading device info...');
  console.log('[CEIR] stage: device-info-start');
  return apiJSON('GET', '/openapi/API/Device/personal-device-info?imei=' + encodeURIComponent(imeis[0]), null, deviceTokenP)
    .then(function (r) {
      console.log('[CEIR] stage: device-info-done ok=' + r.ok);
      var info = r.json || {};
      var t = tacLookup(imeis[0]);
      var brand = (t && t.brand) ? t.brand : (info.gsmaBrandName || '');
      var model = (t && t.model) ? t.model : (info.gsmaModelName || '');
      progress('Done ✓');
      var out = buildResultJson(list, info, brand, model);
      try { Android.onResult(JSON.stringify(out)); } catch (e) {}
      renderUnpaid(list, info, brand, model);
      checkRunning = false;
    }, function (e) {
      // device-info fail → status only
      progress('Done ✓');
      var out = buildResultJson(list, {}, '', '');
      try { Android.onResult(JSON.stringify(out)); } catch (e2) {}
      renderUnpaid(list, {}, '', '');
      checkRunning = false;
    });
}

function buildResultJson(list, info, brand, model) {
  return {
    deviceInfo: {
      manufacturer: info.gsmaManufacturer || '',
      brand: brand || info.gsmaBrandName || '',
      model: model || info.gsmaModelName || '',
      allocationDate: info.gsmaAllocationDate || '',
      operatingSystem: info.gsmaOperatingSystem || '',
      deviceType: info.gsmaDeviceType || ''
    },
    imeiResults: list.map(function (r) {
      var bs = String(r.blockState || '').toLowerCase();
      var st = paidStateOf(r.paymentState);
      var tax = st === 'paid' ? 'Paid' : (st === 'unpaid' ? 'Unpaid' : String(r.paymentState || 'Unknown'));
      var blk = (bs.indexOf('unblock') !== -1 || bs.indexOf('allow') !== -1) ? 'Allowed' : 'Blocked';
      return {
        imei: r.IMEI || '',
        // Item 7: CEIR flags bad IMEIs — surface it instead of hardcoding 'Correct'
        imeiStatus: r.Incorrect === true ? 'IMEI မမှန်ဘူး' : 'Correct',
        taxPayment: tax,
        blockingStatus: blk
      };
    })
  };
}

// ---------------- boot ----------------
hookTurnstile();
ensureOverlay();
showLoading('Loading...');

})();
