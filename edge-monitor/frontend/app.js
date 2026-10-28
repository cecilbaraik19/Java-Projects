/* Edge Monitor - frontend.
   Plain JavaScript, no frameworks and no build step. Every piece of data is escaped with esc() before it goes
   into HTML, and no inline styles or scripts are used (the server's Content-Security-Policy blocks them).
   Sizes that depend on data (bar widths) are set through element.style from JavaScript, which the policy allows. */
'use strict';

const S = {
  token: null, user: null, meta: null, users: [], live: null,
  routeSeq: 0, refresh: null, lastAlertId: null, liveTimer: null,
  nodeFilter: { site: '', status: '', q: '' },
  sensorFilter: { node: '', type: '', state: '', q: '' },
  alertFilter: { status: 'ACTIVE', severity: '', node: '', q: '' },
  cmp: {}, reportDays: 7, pipeNode: 'N-101'
};
const $ = (sel, root = document) => root.querySelector(sel);
const $$ = (sel, root = document) => Array.from(root.querySelectorAll(sel));
const view = () => $('#view');
const canEdit = () => S.user && (S.user.role === 'ADMIN' || S.user.role === 'ENGINEER');
const isAdmin = () => S.user && S.user.role === 'ADMIN';

// ================================================================== helpers

const store = {
  get(k) { try { return sessionStorage.getItem(k); } catch { return null; } },
  set(k, v) { try { v == null ? sessionStorage.removeItem(k) : sessionStorage.setItem(k, v); } catch { /* ignore */ } },
  getLocal(k) { try { return localStorage.getItem(k); } catch { return null; } },
  setLocal(k, v) { try { localStorage.setItem(k, v); } catch { /* ignore */ } }
};

function esc(v) {
  return String(v ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}
/** 12,34,567 (Indian grouping) with up to d decimals */
function num(n, d = 1) {
  if (n == null || isNaN(n)) return '-';
  return Number(n).toLocaleString('en-IN', { maximumFractionDigits: d });
}
function fixed(n, d = 1) {
  if (n == null || isNaN(n)) return '-';
  return Number(n).toLocaleString('en-IN', { minimumFractionDigits: d, maximumFractionDigits: d });
}
/** Smart number for a sensor value: fewer decimals for big numbers */
function val(n) {
  if (n == null || isNaN(n)) return '-';
  const a = Math.abs(n);
  return num(n, a >= 100 ? 0 : a >= 10 ? 1 : 2);
}
function bytes(b) {
  if (b == null || isNaN(b)) return '-';
  const u = ['B', 'KB', 'MB', 'GB', 'TB'];
  let i = 0; let v = Number(b);
  while (Math.abs(v) >= 1000 && i < u.length - 1) { v /= 1000; i++; }
  return (i === 0 ? num(v, 0) : fixed(v, v >= 100 ? 0 : 1)) + ' ' + u[i];
}
function money(n) {
  if (n == null || isNaN(n)) return '-';
  return '₹' + Number(n).toLocaleString('en-IN', { minimumFractionDigits: n < 100 ? 2 : 0, maximumFractionDigits: n < 100 ? 2 : 0 });
}
const pct = (a, b) => b > 0 ? a * 100 / b : 0;
function hm(ms) {
  return ms ? new Date(ms).toLocaleTimeString('en-IN', { hour: '2-digit', minute: '2-digit', hour12: false }) : '';
}
function hms(ms) {
  return ms ? new Date(ms).toLocaleTimeString('en-IN', { hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false }) : '';
}
function when(ms) {
  if (!ms) return '-';
  return new Date(ms).toLocaleString('en-IN', { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit', hour12: false });
}
function dayLabel(iso) {
  const d = new Date(iso + 'T00:00:00');
  return d.toLocaleDateString('en-IN', { day: 'numeric', month: 'short' });
}
function dur(sec) {
  sec = Math.max(0, Math.round(sec));
  if (sec < 60) return sec + ' s';
  const m = Math.floor(sec / 60);
  if (m < 60) return m + ' min' + (sec % 60 && m < 10 ? ' ' + (sec % 60) + ' s' : '');
  const h = Math.floor(m / 60);
  if (h < 48) return h + ' h ' + (m % 60) + ' min';
  return Math.floor(h / 24) + ' d ' + (h % 24) + ' h';
}
function ago(ms) {
  if (!ms) return 'never';
  const s = (Date.now() - ms) / 1000;
  if (s < 5) return 'just now';
  return dur(s) + ' ago';
}
const modeLabel = m => esc(((S.meta && S.meta.modes.find(x => x.id === m)) || {}).label || m);
const typeLabel = t => esc(((S.meta && S.meta.sensorTypes.find(x => x.id === t)) || {}).label || t);
const userName = u => { const x = S.users.find(a => a.username === u); return esc(x ? x.displayName : (u || '')); };
const led = (cls, blink) => `<span class="led ${esc(cls)}${blink ? ' blink' : ''}" aria-hidden="true"></span>`;
const tag = (text, cls = '') => `<span class="tag ${esc(cls)}">${esc(text)}</span>`;
const modeTag = m => `<span class="tag mode ${esc(m)}">${esc(m)}</span>`;
const STATE_TEXT = { ok: 'Normal', out: 'Out of range', alert: 'Alert', silent: 'No data', off: 'Off' };
const STATUS_TEXT = { ONLINE: 'Online', DEGRADED: 'Degraded', OFFLINE: 'Offline', DISABLED: 'Off' };

function toast(msg, kind = '', title = '') {
  const t = document.createElement('div');
  t.className = 'toast ' + kind;
  t.setAttribute('role', 'status');
  if (title) { const b = document.createElement('b'); b.textContent = title; t.appendChild(b); }
  t.appendChild(document.createTextNode(msg));
  $('#toasts').appendChild(t);
  setTimeout(() => t.remove(), kind === 'err' || kind === 'CRITICAL' ? 6000 : 3200);
}

/** Thrown when an answer arrives for a page the person has already left (it is then ignored). */
class StaleError extends Error {}

async function api(method, path, body) {
  const seq = S.routeSeq;
  const opt = { method, headers: {} };
  if (S.token) opt.headers.Authorization = 'Bearer ' + S.token;
  if (body !== undefined) { opt.headers['Content-Type'] = 'application/json'; opt.body = JSON.stringify(body); }
  const res = await fetch(path, opt);
  if (res.status === 401 && path !== '/api/auth/login') { signOut(true); throw new Error('Your session ended. Please sign in again.'); }
  const ct = res.headers.get('content-type') || '';
  const data = ct.includes('json') ? await res.json() : await res.text();
  if (!res.ok) throw new Error((data && data.error) || 'Request failed (' + res.status + ')');
  if (method === 'GET' && seq !== S.routeSeq) throw new StaleError('page changed');
  return data;
}

async function run(btn, fn) {
  if (btn) btn.disabled = true;
  try { return await fn(); } catch (e) { if (!(e instanceof StaleError)) toast(e.message, 'err', 'Problem'); } finally { if (btn) btn.disabled = false; }
}

/** Sets widths that come from data (allowed by the page's security policy, unlike style="" in HTML). */
function applySizes(root = document) {
  $$('[data-w]', root).forEach(el => { el.style.width = Math.max(0, Math.min(100, Number(el.dataset.w))) + '%'; });
  $$('[data-l]', root).forEach(el => { el.style.left = Math.max(0, Math.min(100, Number(el.dataset.l))) + '%'; });
}

function meter(p, cls = '') {
  return `<div class="meter"><i class="${esc(cls)}" data-w="${Number(p) || 0}"></i></div>`;
}
function levelCls(v, warn, bad) { return v >= bad ? 'bad' : v >= warn ? 'warn' : 'ok'; }

/** Where a value sits in its normal range (with some room either side). */
function rangeBar(v, low, high) {
  const span = high - low || 1;
  const min = low - span * 0.35;
  const max = high + span * 0.35;
  const p = x => (x - min) * 100 / (max - min);
  const out = v != null && (v < low || v > high);
  return `<div class="range" title="Normal ${val(low)} to ${val(high)}"><div class="track"></div>
    <div class="ok-zone" data-l="${p(low)}" data-w="${p(high) - p(low)}"></div>
    ${v == null ? '' : `<div class="pin${out ? ' out' : ''}" data-l="${p(Math.max(min, Math.min(max, v)))}"></div>`}</div>`;
}

// ================================================================== charts (hand-made SVG)

/** Drawing widths that match the panel sizes on a desktop screen, so chart text stays readable. */
const CW = { full: 1100, wide: 740, half: 540, third: 360 };

function niceTicks(min, max, count = 4) {
  if (!(max > min)) { max = min + 1; }
  const raw = (max - min) / count;
  const mag = Math.pow(10, Math.floor(Math.log10(raw)));
  const step = [1, 2, 2.5, 5, 10].map(s => s * mag).find(s => raw <= s) || raw;
  const start = Math.floor(min / step) * step;
  const ticks = [];
  for (let t = start; t <= max + step * 0.001; t += step) ticks.push(Number(t.toFixed(10)));
  return ticks;
}

/**
 * Draws a chart from layers. x and y are plain numbers (time in ms, or an index).
 * layers: {kind:'line'|'area'|'band'|'dots'|'segs'|'bars', ...}
 */
function chart(o) {
  const W = o.w || 720, H = o.h || 230;
  const L = o.padL ?? 50, R = o.padR ?? 14, T = o.padT ?? 12, B = o.padB ?? 26;
  const xs = [], ys = [];
  for (const l of o.layers) {
    if (l.kind === 'band') l.pts.forEach(p => { xs.push(p[0]); ys.push(p[1], p[2]); });
    else if (l.kind === 'segs') l.segs.forEach(s => { xs.push(s[0], s[1]); ys.push(s[2]); });
    else if (l.kind === 'bars') l.bars.forEach(b => { xs.push(b[0]); ys.push(b[1], b[2]); });
    else l.pts.forEach(p => { if (p[1] != null) { xs.push(p[0]); ys.push(p[1]); } });
  }
  (o.hlines || []).forEach(h => ys.push(h.y));
  let xMin = o.xMin ?? Math.min(...xs), xMax = o.xMax ?? Math.max(...xs);
  if (!isFinite(xMin)) { xMin = 0; xMax = 1; }
  if (xMax === xMin) xMax = xMin + 1;
  let yMin = o.yMin ?? Math.min(...ys), yMax = o.yMax ?? Math.max(...ys);
  if (!isFinite(yMin)) { yMin = 0; yMax = 1; }
  if (yMax === yMin) { yMax += 1; yMin -= 1; }
  if (o.yMin == null || o.yMax == null) {
    const padY = (yMax - yMin) * 0.08;
    if (o.yMin == null) yMin -= padY;
    if (o.yMax == null) yMax += padY;
  }
  const ticks = niceTicks(yMin, yMax, o.yTicks || 4).filter(t => t >= yMin - 1e-9 && t <= yMax + 1e-9);
  const sx = x => L + (x - xMin) * (W - L - R) / (xMax - xMin);
  const sy = y => T + (yMax - y) * (H - T - B) / (yMax - yMin);
  const yf = o.yFmt || (v => num(v, 2));
  const xf = o.xFmt || (v => hm(v));
  const f = n => n.toFixed(1);
  let s = `<svg class="chart" viewBox="0 0 ${W} ${H}" role="img" aria-label="${esc(o.label || 'Chart')}">`;
  for (const t of ticks) {
    s += `<line class="gridl" x1="${L}" x2="${W - R}" y1="${f(sy(t))}" y2="${f(sy(t))}"/>`;
    s += `<text x="${L - 6}" y="${f(sy(t) + 3.5)}" text-anchor="end">${esc(yf(t))}</text>`;
  }
  s += `<line class="axis" x1="${L}" x2="${W - R}" y1="${H - B}" y2="${H - B}"/>`;
  const nx = o.xTicks ?? 6;
  if (o.xLabels) {
    const every = Math.max(1, Math.ceil(o.xLabels.length / nx));
    o.xLabels.forEach((lab, i) => { if (i % every === 0) s += `<text x="${f(sx(i))}" y="${H - 8}" text-anchor="middle">${esc(lab)}</text>`; });
  } else {
    for (let i = 0; i <= nx; i++) {
      const x = xMin + (xMax - xMin) * i / nx;
      s += `<text x="${f(sx(x))}" y="${H - 8}" text-anchor="${i === 0 ? 'start' : i === nx ? 'end' : 'middle'}">${esc(xf(x))}</text>`;
    }
  }
  for (const b of (o.bands || [])) {
    const y1 = sy(Math.min(yMax, b.hi)), y2 = sy(Math.max(yMin, b.lo));
    if (y2 > y1) s += `<rect class="${esc(b.cls || 'okband')}" x="${L}" width="${W - L - R}" y="${f(y1)}" height="${f(y2 - y1)}"/>`;
  }
  const path = pts => {
    let d = ''; let pen = false;
    for (const p of pts) {
      if (p[1] == null || isNaN(p[1])) { pen = false; continue; }
      d += (pen ? 'L' : 'M') + f(sx(p[0])) + ' ' + f(sy(Math.max(yMin, Math.min(yMax, p[1]))));
      pen = true;
    }
    return d;
  };
  for (const l of o.layers) {
    if (l.kind === 'line' && l.pts.length) s += `<path class="ln ${esc(l.cls || 'cyan')}" d="${path(l.pts)}"/>`;
    if (l.kind === 'area' && l.pts.length) {
      const ok = l.pts.filter(p => p[1] != null);
      if (ok.length > 1) {
        const base = sy(Math.max(yMin, l.base ?? yMin));
        s += `<path class="area ${esc(l.cls || 'cyan')}" d="${path(ok)} L${f(sx(ok[ok.length - 1][0]))} ${f(base)} L${f(sx(ok[0][0]))} ${f(base)} Z"/>`;
      }
    }
    if (l.kind === 'band' && l.pts.length > 1) {
      const top = l.pts.map(p => [p[0], p[2]]);
      const bot = l.pts.slice().reverse().map(p => [p[0], p[1]]);
      s += `<path class="${esc(l.cls || 'band')}" d="${path(top)} ${path(bot).replace(/^M/, 'L')} Z"/>`;
    }
    if (l.kind === 'dots') {
      for (const p of l.pts) {
        if (p[1] == null) continue;
        const y = Math.max(yMin, Math.min(yMax, p[1]));
        s += `<circle class="dot ${esc(p[2] || '')}" cx="${f(sx(p[0]))}" cy="${f(sy(y))}" r="${l.r || 2.6}">${p[3] ? `<title>${esc(p[3])}</title>` : ''}</circle>`;
      }
    }
    if (l.kind === 'segs') {
      for (const g of l.segs) {
        s += `<line class="seg" x1="${f(sx(g[0]))}" x2="${f(sx(g[1]))}" y1="${f(sy(g[2]))}" y2="${f(sy(g[2]))}">${g[3] ? `<title>${esc(g[3])}</title>` : ''}</line>`;
      }
    }
    if (l.kind === 'bars') {
      const bw = l.bw || 10;
      for (const b of l.bars) {
        const y1 = sy(Math.max(b[1], b[2])), y2 = sy(Math.min(b[1], b[2]));
        s += `<rect class="bar ${esc(b[3] || 'cyan')}" x="${f(sx(b[0]) - bw / 2 + (b[5] || 0))}" width="${f(bw)}" y="${f(y1)}" height="${f(Math.max(0.5, y2 - y1))}">${b[4] ? `<title>${esc(b[4])}</title>` : ''}</rect>`;
      }
    }
  }
  for (const h of (o.hlines || [])) {
    if (h.y < yMin || h.y > yMax) continue;
    s += `<line class="hl ${esc(h.cls || 'muted')}" x1="${L}" x2="${W - R}" y1="${f(sy(h.y))}" y2="${f(sy(h.y))}"/>`;
    if (h.label) s += `<text class="hl-t ${esc(h.cls || '')}" x="${W - R - 4}" y="${f(sy(h.y) - 4)}" text-anchor="end">${esc(h.label)}</text>`;
  }
  return s + '</svg>';
}

function spark(values, cls = '') {
  const v = (values || []).filter(x => x != null);
  if (v.length < 2) return '<span class="muted tiny">-</span>';
  const min = Math.min(...v), max = Math.max(...v);
  const span = max - min || 1;
  const pts = v.map((y, i) => (i * 108 / (v.length - 1) + 1).toFixed(1) + ' ' + (26 - (y - min) * 24 / span).toFixed(1));
  return `<svg class="spark ${esc(cls)}" viewBox="0 0 110 28" aria-hidden="true"><path class="ln" d="M${pts.join(' L')}"/></svg>`;
}

function legend(items) {
  return `<div class="legend">${items.map(([cls, text]) => `<span><i class="${esc(cls)}"></i>${esc(text)}</span>`).join('')}</div>`;
}

// ================================================================== theme

function currentTheme() {
  const set = document.documentElement.getAttribute('data-theme');
  if (set) return set;
  return window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
}
function paintThemeButtons() {
  const dark = currentTheme() === 'dark';
  $$('[data-theme-toggle]').forEach(b => { b.textContent = dark ? 'Light mode' : 'Dark mode'; b.setAttribute('aria-pressed', String(dark)); });
}
function toggleTheme() {
  const next = currentTheme() === 'dark' ? 'light' : 'dark';
  document.documentElement.setAttribute('data-theme', next);
  store.setLocal('edge-theme', next);
  paintThemeButtons();
}

// ================================================================== modal

let modalSubmit = null;
function openModal({ title, body, buttons = [], wide = false, onSubmit = null }) {
  const m = $('#modal');
  m.classList.toggle('wide', wide);
  $('#modalTitle').textContent = title;
  $('#modalBody').innerHTML = body;
  $('#modalErr').textContent = '';
  const foot = $('#modalFoot');
  foot.innerHTML = '';
  for (const b of buttons) {
    const el = document.createElement('button');
    el.type = b.submit ? 'submit' : 'button';
    el.className = 'btn ' + (b.cls || '');
    el.textContent = b.label;
    if (b.id) el.id = b.id;
    if (b.onClick) el.addEventListener('click', () => b.onClick(el));
    foot.appendChild(el);
  }
  modalSubmit = onSubmit;
  applySizes(m);
  if (!m.open) m.showModal();
  const first = $('#modalBody input:not([type=hidden]), #modalBody select, #modalBody textarea');
  if (first) first.focus();
}
function closeModal() { const m = $('#modal'); if (m.open) m.close(); modalSubmit = null; }
function modalError(msg) { $('#modalErr').textContent = msg; }
function formData() {
  const out = {};
  $$('#modalBody [name]').forEach(el => {
    if (el.type === 'checkbox') out[el.name] = el.checked;
    else if (el.type === 'number' || el.dataset.num) out[el.name] = el.value === '' ? null : Number(el.value);
    else out[el.name] = el.value === '' ? null : el.value;
  });
  return out;
}
function opt(value, label, selected) {
  return `<option value="${esc(value)}"${String(value) === String(selected ?? '') ? ' selected' : ''}>${esc(label)}</option>`;
}
function field(label, inner, hint = '') {
  return `<div class="field"><label>${esc(label)}</label>${inner}${hint ? `<div class="hint">${esc(hint)}</div>` : ''}</div>`;
}
function input(name, value, attrs = '') {
  return `<input name="${esc(name)}" value="${esc(value ?? '')}" ${attrs}>`;
}

// ================================================================== sign in / out

function showLogin() {
  $('#app').classList.add('hidden');
  $('#login').classList.remove('hidden');
  setTimeout(() => $('#lu').focus(), 30);
}

async function signIn(e) {
  e.preventDefault();
  const btn = e.submitter || $('#loginForm button[type=submit]');
  $('#loginErr').textContent = '';
  btn.disabled = true;
  try {
    const r = await api('POST', '/api/auth/login', { username: $('#lu').value, password: $('#lp').value });
    S.token = r.token;
    S.user = r.user;
    store.set('edge-token', r.token);
    $('#lp').value = '';
    await enterApp();
  } catch (err) {
    $('#loginErr').textContent = err.message;
  } finally { btn.disabled = false; }
}

function signOut(expired) {
  if (S.token && !expired) fetch('/api/auth/logout', { method: 'POST', headers: { Authorization: 'Bearer ' + S.token } }).catch(() => {});
  S.token = null; S.user = null; S.lastAlertId = null;
  store.set('edge-token', null);
  clearInterval(S.liveTimer);
  closeModal();
  showLogin();
  if (expired) $('#loginErr').textContent = 'Your session ended. Please sign in again.';
}

async function enterApp() {
  S.routeSeq++;
  const [meta, users] = await Promise.all([api('GET', '/api/meta'), api('GET', '/api/users')]);
  S.meta = meta; S.users = users;
  $('#login').classList.add('hidden');
  $('#app').classList.remove('hidden');
  $('#who').textContent = S.user.displayName + ' · ' + S.user.roleLabel;
  $('#navTeam').classList.toggle('hidden', !isAdmin());
  clearInterval(S.liveTimer);
  S.liveTimer = setInterval(pollLive, 5000);
  pollLive();
  if (!location.hash || location.hash === '#' || location.hash === '#/') location.hash = '#/overview';
  else route();
}

async function reloadMeta() {
  try { S.meta = await api('GET', '/api/meta'); } catch { /* keep old */ }
}

// ================================================================== live ticker (every 5 s)

async function pollLive() {
  if (!S.token) return;
  let L;
  try {
    const res = await fetch('/api/live', { headers: { Authorization: 'Bearer ' + S.token } });
    if (res.status === 401) { signOut(true); return; }
    L = await res.json();
  } catch { $('#ticker').innerHTML = '<span class="t-bad"><b>Server not reachable</b></span>'; return; }
  S.live = L;
  const allUp = L.nodesOnline === L.nodesTotal;
  $('#ticker').innerHTML = `
    <span class="${allUp ? 't-ok' : 't-bad'}">NODES <b>${L.nodesOnline}/${L.nodesTotal}</b></span>
    <span>IN <b>${num(L.msgsPerMin, 0)}</b>/min</span>
    <span>OUT <b>${num(L.fwdPerMin, 0)}</b>/min</span>
    <span>DATA <b>−${fixed(L.reductionPct, 0)}%</b></span>
    <span class="${L.critical ? 't-bad' : ''}">ALERTS <b>${L.openAlerts}</b>${L.critical ? ` · <b>${L.critical} critical</b>` : ''}</span>`;
  const na = $('#navAlerts');
  na.textContent = L.openAlerts; na.classList.toggle('hidden', !L.openAlerts);
  const nf = $('#navFaults');
  nf.textContent = L.faults; nf.classList.toggle('hidden', !L.faults);
  const nn = $('#navNodes');
  const down = L.nodesTotal - L.nodesOnline;
  nn.textContent = down + ' down'; nn.classList.toggle('hidden', !down); nn.classList.toggle('bad', down > 0);
  $('#simState').innerHTML = `${led(L.simulatorOn ? 'on' : 'off', L.simulatorOn)}<span>Simulator ${L.simulatorOn ? 'running' : 'paused'}</span>`;

  const a = L.newestAlert;
  if (a && S.lastAlertId && a.id !== S.lastAlertId && a.severity !== 'INFO') {
    toast(a.message, a.severity, a.severity + ' alert');
  }
  if (a) S.lastAlertId = a.id;

  // refresh the open page, unless the person is typing or a form is open
  const busy = $('#modal').open || (document.activeElement && view().contains(document.activeElement)
    && /INPUT|SELECT|TEXTAREA/.test(document.activeElement.tagName));
  if (S.refresh && !busy && !document.hidden) S.refresh().catch(() => {});
}

function tickClock() {
  const c = $('#clock');
  if (c) c.textContent = new Date().toLocaleString('en-IN', { weekday: 'short', day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false });
}

// ================================================================== router

const ROUTES = {
  overview: pageOverview, nodes: pageNodes, node: pageNode, sensors: pageSensors, sensor: pageSensor,
  alerts: pageAlerts, pipeline: pagePipeline, rules: pageRules, lab: pageLab, reports: pageReports,
  settings: pageSettings, team: pageTeam
};

async function route() {
  if (!S.token) return;
  const parts = location.hash.replace(/^#\/?/, '').split('/');
  const name = ROUTES[parts[0]] ? parts[0] : 'overview';
  const arg = parts[1] ? decodeURIComponent(parts[1]) : null;
  S.routeSeq++;
  S.refresh = null;
  const navKey = name === 'node' ? 'nodes' : name === 'sensor' ? 'sensors' : name;
  $$('#nav a').forEach(a => a.classList.toggle('active', a.dataset.nav === navKey));
  $('#rail').classList.remove('open');
  $('#railShade').classList.add('hidden');
  view().innerHTML = '<div class="empty">Loading…</div>';
  try {
    await ROUTES[name](arg);
    applySizes(view());
  } catch (e) {
    if (e instanceof StaleError) return;
    view().innerHTML = `<div class="panel"><div class="empty">${esc(e.message)}<br><br><a href="#/overview">Back to overview</a></div></div>`;
  }
}

/** Runs a page's draw function now and again on every live update. */
async function live(draw) {
  const seq = S.routeSeq;
  const go = async () => {
    if (seq !== S.routeSeq) return;
    try { await draw(); applySizes(view()); } catch (e) { if (!(e instanceof StaleError)) throw e; }
  };
  S.refresh = go;
  await draw();
}

function head(title, sub, actions = '', crumbs = '') {
  return `<div class="page-head"><div>${crumbs ? `<div class="crumbs">${crumbs}</div>` : ''}<h1>${title}</h1>${sub ? `<p class="sub">${sub}</p>` : ''}</div>
    <div class="btn-row">${actions}</div></div>`;
}
function panel(title, body, meta = '', cls = '') {
  return `<section class="panel ${cls}"><div class="panel-head"><h3>${esc(title)}</h3>${meta ? `<span class="meta">${meta}</span>` : ''}</div>${body}</section>`;
}
function readout(k, v, unit, sub, cls = '', extra = '') {
  return `<div class="readout ${cls}"><div class="k">${esc(k)}</div><div class="v">${v}${unit ? `<small>${esc(unit)}</small>` : ''}</div>${sub ? `<div class="s">${sub}</div>` : ''}${extra}</div>`;
}

// ================================================================== shared bits

function alertItem(a, withNode = true) {
  const cleared = a.clearedAt && a.status !== 'RESOLVED';
  return `<li class="${cleared ? 'cleared' : ''}">
    <div>${tag(a.severity, a.severity)}</div>
    <div class="msg"><a href="#/alerts" data-alert="${esc(a.id)}" class="title">${esc(a.message)}</a>
      <span class="sub">${withNode ? '<span class="mono">' + esc(a.nodeName) + '</span> · ' : ''}${esc(a.ruleName || '')}${a.action ? ' · edge action: ' + esc(a.action) : ''}</span>
      <div class="metaline">${tag(a.status, a.status)}<span title="${esc(when(a.openedAt))}">${esc(ago(a.openedAt))}</span>${cleared ? '<span>condition cleared</span>' : ''}
      ${a.edgeMs ? `<span class="lat">edge <b>${fixed(a.edgeMs, 1)} ms</b> vs cloud ${num(a.cloudMs, 0)} ms</span>` : ''}</div></div></li>`;
}

function feed(events) {
  if (!events.length) return '<div class="empty">Nothing yet.</div>';
  return `<ul class="list feed">${events.map(e => `<li><span class="when" title="${esc(when(e.at))}">${esc(hm(e.at))}</span>
    <div class="msg">${esc(e.text)}<span class="sub"><span class="who-tag">${esc(e.user === 'system' || e.user === 'edge' || e.user === 'simulator' ? e.user : userName(e.user))}</span>${e.nodeName ? ' · ' + esc(e.nodeName) : ''} · ${esc(ago(e.at))}</span></div></li>`).join('')}</ul>`;
}

function bindAlertLinks(root) {
  $$('[data-alert]', root).forEach(a => a.addEventListener('click', e => { e.preventDefault(); showAlert(a.dataset.alert); }));
}

// ================================================================== 01 overview

async function pageOverview() {
  await live(async () => {
    const d = await api('GET', '/api/dashboard');
    const t = d.today;
    const statusSegs = [];
    d.topology.forEach(s => s.nodes.forEach(n => { if (n.status !== 'DISABLED') statusSegs.push(n.status); }));
    const segs = `<div class="segs">${statusSegs.map(s => `<i class="${s === 'ONLINE' ? 'on' : s === 'DEGRADED' ? 'warn' : 'bad'}"></i>`).join('')}</div>`;
    const speed = t.edgeMs > 0 ? Math.round(t.cloudMs / t.edgeMs) : 0;
    const msgCut = d.msgsPerMin ? Math.round(100 - pct(d.fwdPerMin, d.msgsPerMin)) : 0;
    const traffic = d.traffic.slice(0, -1); // the current minute is not finished yet
    const h = new Date().getHours();
    const greet = h < 12 ? 'Good morning' : h < 17 ? 'Good afternoon' : 'Good evening';

    view().innerHTML = head('Control room',
      `${esc(greet)}, ${esc(S.user.displayName.split(' ')[0])}. ${d.nodes.total} edge nodes across ${d.topology.length} sites, live every 5 seconds.`,
      `<a class="btn" href="#/pipeline">How the edge works</a><a class="btn primary" href="#/lab">Open fault lab</a>`) +
      `<div class="readouts">
        ${readout('Edge nodes online', `${d.nodes.online}<small>/ ${d.nodes.total}</small>`, '', `${d.nodes.degraded} degraded · <b>${d.nodes.offline}</b> offline`, d.nodes.offline ? 'bad' : 'ok', segs)}
        ${readout('Readings in', num(d.msgsPerMin, 0), '/min', `from ${d.sensors.total} sensors at the edge`)}
        ${readout('Sent to cloud', num(d.fwdPerMin, 0), '/min', `<b>${msgCut}%</b> fewer messages`, 'cyan')}
        ${readout('Data cut today', fixed(t.reductionPct, 1), '%', `saved <b>${bytes(t.savedBytes)}</b> · ${money(t.savedCost)}`, 'amber')}
        ${readout('Decision time', fixed(t.edgeMs, 1), 'ms', `cloud round trip ${num(t.cloudMs, 0)} ms · <b>${speed}× faster</b>`, 'cyan')}
        ${readout('Active alerts', d.alerts.total, '', `<b>${d.alerts.critical}</b> critical · ${d.alerts.warning} warning · ${d.alerts.info} info`, d.alerts.critical ? 'bad' : '')}
      </div>
      <div class="row c21">
        ${panel('Traffic · last 60 minutes', `<div class="panel-body">${chart({
          label: 'Bytes per minute that came in at the edge versus bytes sent to the cloud',
          w: CW.wide, h: 250, yMin: 0, yFmt: v => bytes(v),
          layers: [
            { kind: 'area', cls: 'rust', pts: traffic.map(r => [r[0], r[3]]), base: 0 },
            { kind: 'line', cls: 'rust', pts: traffic.map(r => [r[0], r[3]]) },
            { kind: 'area', cls: 'cyan', pts: traffic.map(r => [r[0], r[4]]), base: 0 },
            { kind: 'line', cls: 'cyan', pts: traffic.map(r => [r[0], r[4]]) }
          ]
        })}${legend([['rust', 'Would go to the cloud without edge (raw)'], ['cyan', 'Actually sent after edge processing']])}</div>`, 'bytes / minute')}
        ${panel('Edge pipeline · today', `<div class="panel-body">${funnel(t)}
          <hr><dl class="kv"><dt>Raw size</dt><dd class="mono">${bytes(t.rawBytes)} · ${money(t.rawCost)}</dd>
          <dt>Sent size</dt><dd class="mono">${bytes(t.fwdBytes)} · ${money(t.fwdCost)}</dd>
          <dt>Uptime</dt><dd class="mono">${fixed(t.uptimePct, 2)}%</dd></dl></div>`)}
      </div>
      ${panel('Site map', `<div class="topo">${d.topology.map(siteBlock).join('')}</div>`,
        `${led('ok')} normal ${led('out')} out of range ${led('alert')} alert ${led('silent')} no data`)}
      <div class="row c3">
        ${panel('Active alerts', d.activeAlerts.length ? `<ul class="list alerts">${d.activeAlerts.map(a => alertItem(a)).join('')}</ul>`
          : '<div class="empty">All clear. Nothing needs attention.</div>', `<a href="#/alerts">all alerts</a>`)}
        ${panel('Data per node · today', `<div class="panel-body">${nodeBars(d.byNode)}</div>`, 'raw vs sent')}
        ${panel('Activity', feed(d.events))}
      </div>`;
    bindAlertLinks(view());
  });
}

function funnel(t) {
  const rows = [
    ['Readings in (raw)', t.raw, t.raw, 'rust', num(t.raw, 0)],
    ['Dropped as invalid', t.invalid, t.raw, 'bad', num(t.invalid, 0)],
    ['Checked by rules', t.valid, t.raw, 'amber', num(t.valid, 0)],
    ['Messages to cloud', t.forwarded, t.raw, 'cyan', num(t.forwarded, 0)],
    ['Held in buffer', t.buffered, t.raw, 'warn', num(t.buffered, 0)]
  ];
  return `<div class="funnel">${rows.map(r => `<div class="f"><span>${esc(r[0])}</span>
    <div class="meter"><i class="${r[3]}" data-w="${pct(r[1], r[2])}"></i></div><span class="r">${r[4]}</span></div>`).join('')}</div>`;
}

function siteBlock(s) {
  return `<div class="site"><div class="site-head"><b>${esc(s.name)}</b><span>${esc(s.kind || '')} · ${esc(s.location || '')}</span></div>
    ${s.nodes.map(n => `<a class="unit ${esc(n.status)}" href="#/node/${esc(n.id)}">
      <div class="unit-top">${led(n.status, n.status === 'OFFLINE')}${esc(n.name)}${modeTag(n.mode)}</div>
      <div class="chips">${n.sensors.map(c => `<span class="chip ${esc(c.state)}" title="${esc(c.name)}: ${c.value == null ? 'no data' : esc(val(c.value) + ' ' + c.unit)} (${esc(STATE_TEXT[c.state] || c.state)})"></span>`).join('')}</div>
      <div class="unit-flags"><span>${esc(STATUS_TEXT[n.status])}</span>${n.status !== 'OFFLINE' && n.status !== 'DISABLED' ? `<span>CPU ${num(n.cpu, 0)}%</span>` : ''}
        ${!n.uplinkUp && n.status !== 'OFFLINE' ? `<span class="warn">link down · ${num(n.buffered, 0)} buffered</span>` : ''}</div></a>`).join('')}</div>`;
}

function nodeBars(list) {
  if (!list.length) return '<div class="empty">No data yet today.</div>';
  const max = Math.max(...list.map(x => x.rawBytes), 1);
  return `<div class="hbars">${list.map(x => `<div class="hbar"><a class="name" href="#/node/${esc(x.id)}" title="${esc(x.name)}">${esc(x.name)}</a>
    <div class="stack"><i class="rust" data-w="${pct(x.rawBytes, max)}"></i><i class="cyan" data-w="${pct(x.fwdBytes, max)}"></i></div>
    <span class="r">${x.mode === 'RAW' ? '0%' : '−' + fixed(x.reductionPct, 0) + '%'}</span></div>`).join('')}</div>
    <p class="tiny muted">Pale bar = raw data that came in. Solid bar = what was sent on. compressor-gw-03 runs in RAW mode for comparison.</p>`;
}

// ================================================================== 02 nodes

async function pageNodes() {
  const f = S.nodeFilter;
  view().innerHTML = head('Edge nodes', 'Gateways on site that process sensor data before it goes to the cloud.',
    canEdit() ? '<button class="btn primary" id="addNode" type="button">Add edge node</button>' : '') +
    `<div class="toolbar">
      <select id="fSite" aria-label="Site"><option value="">All sites</option>${S.meta.sites.map(s => opt(s.id, s.name, f.site)).join('')}</select>
      <select id="fStatus" aria-label="Status"><option value="">Any status</option>${['ONLINE', 'DEGRADED', 'OFFLINE', 'DISABLED'].map(s => opt(s, STATUS_TEXT[s], f.status)).join('')}</select>
      <input type="search" id="fQ" placeholder="Search name or hardware" value="${esc(f.q)}" aria-label="Search">
    </div><div id="results"></div><div id="sites"></div>`;
  $('#fSite').addEventListener('change', e => { f.site = e.target.value; S.refresh(); });
  $('#fStatus').addEventListener('change', e => { f.status = e.target.value; S.refresh(); });
  $('#fQ').addEventListener('input', debounce(e => { f.q = e.target.value; S.refresh(); }, 250));
  if (canEdit()) $('#addNode').addEventListener('click', () => nodeForm(null));
  await live(async () => {
    const qs = new URLSearchParams({ site: f.site, status: f.status, q: f.q });
    const [nodes, sites] = await Promise.all([api('GET', '/api/nodes?' + qs), api('GET', '/api/sites')]);
    $('#results').innerHTML = nodes.length ? `<div class="cards">${nodes.map(nodeCard).join('')}</div>` : '<div class="panel"><div class="empty">No nodes match.</div></div>';
    $('#sites').innerHTML = `<div class="row">${panel('Sites', `<div class="table-wrap"><table><thead><tr><th>Site</th><th>Kind</th><th>Location</th><th class="num">Nodes</th><th class="num">Online</th>${canEdit() ? '<th></th>' : ''}</tr></thead>
      <tbody>${sites.map(s => `<tr><td><b>${esc(s.name)}</b><span class="sub mono">${esc(s.id)}</span></td><td>${esc(s.kind || '')}</td><td>${esc(s.location || '')}</td>
      <td class="num">${s.nodes}</td><td class="num">${s.online}</td>${canEdit() ? `<td class="right"><button class="btn small" data-site="${esc(s.id)}" type="button">Edit</button></td>` : ''}</tr>`).join('')}</tbody></table></div>`,
      canEdit() ? '<button class="btn small" id="addSite" type="button">Add site</button>' : '')}</div>`;
    if (canEdit()) {
      $('#addSite').addEventListener('click', () => siteForm(null));
      $$('[data-site]').forEach(b => b.addEventListener('click', () => siteForm(sites.find(s => s.id === b.dataset.site))));
    }
  });
}

function nodeCard(n) {
  const c = S.meta.settings;
  const online = n.status !== 'OFFLINE' && n.status !== 'DISABLED';
  return `<a class="panel ncard" href="#/node/${esc(n.id)}">
    <div class="top">${led(n.status, n.status === 'OFFLINE')}<b>${esc(n.name)}</b>${tag(STATUS_TEXT[n.status], n.status)}</div>
    <div class="body">
      <div class="facts"><span>${esc(n.siteName)}</span><span>${modeTag(n.mode)} ${n.simulated ? '' : tag('real device', 'info')}</span></div>
      <div class="facts"><span>${esc(n.hardware || '')}</span><span class="mono">${esc(n.ip || '')}</span></div>
      ${online ? `
      <div class="mrow"><span class="l">CPU</span>${meter(n.cpu, levelCls(n.cpu, c.cpuWarnPct * 0.8, c.cpuWarnPct))}<span class="r">${num(n.cpu, 0)}%</span></div>
      <div class="mrow"><span class="l">Memory</span>${meter(n.mem, levelCls(n.mem, 80, 92))}<span class="r">${num(n.mem, 0)}%</span></div>
      <div class="mrow"><span class="l">Temp</span>${meter(n.temp, levelCls(n.temp, c.tempWarnC - 8, c.tempWarnC))}<span class="r">${num(n.temp, 0)} °C</span></div>
      <div class="mrow"><span class="l">Buffer</span>${meter(n.bufferPct, levelCls(n.bufferPct, 50, c.bufferWarnPct))}<span class="r">${num(n.buffered, 0)}</span></div>`
      : `<div class="note">${n.status === 'DISABLED' ? 'Turned off in the app.' : 'No heartbeat since ' + esc(ago(n.lastSeen)) + '.'}</div>`}
      <div class="facts"><span>Data cut today</span><b>${!n.todayRawBytes ? '-' : n.mode === 'RAW' ? '0%' : '−' + fixed(n.reductionPct, 1) + '%'}</b></div>
    </div>
    <div class="foot">${tag(n.sensorCount + ' sensors')}${n.openAlerts ? tag(n.openAlerts + ' alerts', 'bad') : ''}${!n.uplinkUp && online ? tag('link down', 'warn') : ''}${n.pendingCommands ? tag(n.pendingCommands + ' cmd waiting', 'PENDING') : ''}</div>
  </a>`;
}

function siteForm(s) {
  openModal({
    title: s ? 'Edit site' : 'Add site',
    body: field('Name', input('name', s && s.name, 'required maxlength="60"')) +
      `<div class="grid2">${field('Kind', input('kind', s && s.kind, 'maxlength="40" placeholder="Factory, Farm, Warehouse…"'))}${field('Location', input('location', s && s.location, 'maxlength="80"'))}</div>`,
    buttons: [
      ...(s && isAdmin() ? [{ label: 'Delete', cls: 'danger', onClick: b => run(b, async () => {
        await api('DELETE', '/api/sites/' + s.id); closeModal(); toast('Site deleted'); await reloadMeta(); route();
      }) }] : []),
      { label: 'Cancel', onClick: closeModal }, { label: 'Save', cls: 'primary', submit: true }
    ],
    onSubmit: async () => {
      const b = formData();
      await api(s ? 'PUT' : 'POST', '/api/sites' + (s ? '/' + s.id : ''), b);
      closeModal(); toast('Site saved'); await reloadMeta(); route();
    }
  });
}

function nodeForm(n) {
  const m = S.meta;
  openModal({
    title: n ? 'Edit ' + n.name : 'Add edge node',
    wide: true,
    body: `<div class="grid2">${field('Node name', input('name', n && n.name, 'required maxlength="40" placeholder="e.g. boiler-gw-04"'))}
      ${field('Site', `<select name="siteId" required>${m.sites.map(s => opt(s.id, s.name, n && n.siteId)).join('')}</select>`)}</div>
      <div class="grid2">${field('Hardware', input('hardware', n && n.hardware, 'maxlength="60" placeholder="Raspberry Pi 4, Jetson Nano…"'))}
      ${field('IP address', input('ip', n && n.ip, 'maxlength="45"'))}</div>
      ${field('Processing mode', `<select name="mode" id="fMode">${m.modes.map(x => opt(x.id, x.label, n ? n.mode : 'SMART')).join('')}</select>`, '')}
      <p class="note small" id="modeHelp"></p>
      <div class="grid3">${field('Window (seconds)', input('windowSec', n ? n.windowSec : 60, 'type="number" min="10" max="3600"'), 'Aggregate / Smart: one summary per window')}
      ${field('Dead-band (% of range)', input('deadbandPct', n ? n.deadbandPct : 2, 'type="number" min="0" max="50" step="0.1"'), 'Filter / Smart: ignore smaller changes')}
      ${field('Buffer size (messages)', input('bufferCapacity', n ? n.bufferCapacity : 5000, 'type="number" min="100" max="1000000"'), 'Kept while the internet is down')}</div>
      ${field('Notes', `<textarea name="notes" maxlength="300">${esc(n && n.notes || '')}</textarea>`)}
      <div class="btn-row"><label class="check"><input type="checkbox" name="enabled" ${!n || n.enabled ? 'checked' : ''}> Node is on</label>
      <label class="check"><input type="checkbox" name="simulated" ${!n || n.simulated ? 'checked' : ''}> Simulated (untick for a real device that posts to /api/ingest)</label></div>`,
    buttons: [{ label: 'Cancel', onClick: closeModal }, { label: n ? 'Save' : 'Add node', cls: 'primary', submit: true }],
    onSubmit: async () => {
      const b = formData();
      const r = await api(n ? 'PUT' : 'POST', '/api/nodes' + (n ? '/' + n.id : ''), b);
      await reloadMeta();
      if (!n) { showKey(r.name, r.apiKey, r.id); location.hash = '#/node/' + r.id; } else { closeModal(); toast('Node saved'); route(); }
    }
  });
  const help = () => { const x = m.modes.find(y => y.id === $('#fMode').value); $('#modeHelp').textContent = x ? x.help : ''; };
  $('#fMode').addEventListener('change', help); help();
}

function showKey(name, key, id) {
  openModal({
    title: 'API key for ' + name,
    body: `<p>Copy this key now. It is shown <b>only once</b>; the app keeps just a hash of it.
      A real device sends it in the <code>X-Node-Key</code> header when it posts readings to <code>/api/ingest</code>.</p>
      <div class="keybox" id="keyText">${esc(key)}</div>`,
    buttons: [{ label: 'Copy', onClick: () => { navigator.clipboard && navigator.clipboard.writeText(key).then(() => toast('Copied')).catch(() => {}); } },
      { label: 'Done', cls: 'primary', onClick: () => { closeModal(); if (id && location.hash !== '#/node/' + id) location.hash = '#/node/' + id; else route(); } }]
  });
}

// ================================================================== node detail

async function pageNode(id) {
  if (!S.cmp[id]) S.cmp[id] = { deadband: null, window: null };
  await live(async () => {
    const d = await api('GET', '/api/nodes/' + encodeURIComponent(id));
    const n = d.node;
    const c = S.meta.settings;
    const online = n.status !== 'OFFLINE' && n.status !== 'DISABLED';
    const ed = canEdit();
    const acts = ed ? `
      <button class="btn" data-cmd="PING" type="button">Ping</button>
      <button class="btn" data-cmd="REBOOT" type="button">Reboot</button>
      <button class="btn" data-cmd="FLUSH_BUFFER" type="button">Send buffer</button>
      <button class="btn" id="editNode" type="button">Edit</button>
      <button class="btn" id="newKey" type="button">New API key</button>
      ${isAdmin() ? '<button class="btn danger" id="delNode" type="button">Delete</button>' : ''}` : '';
    const health = d.health;
    const html = head(`${led(n.status, n.status === 'OFFLINE')} <span class="mono">${esc(n.name)}</span>`,
      `${tag(STATUS_TEXT[n.status], n.status)} ${modeTag(n.mode)} ${n.simulated ? tag('simulated', 'sim') : tag('real device', 'info')}
       &nbsp;${esc(n.siteName)} · ${esc(n.hardware || '')} · <span class="mono">${esc(n.ip || '')}</span> · firmware ${esc(n.firmware)}`,
      acts, `<a href="#/nodes">Edge nodes</a> / ${esc(n.id)}`) +
      `<div class="readouts">
        ${readout('CPU', online ? num(n.cpu, 0) : '-', '%', `warn at ${num(c.cpuWarnPct, 0)}%`, online && n.cpu >= c.cpuWarnPct ? 'bad' : '')}
        ${readout('Memory', online ? num(n.mem, 0) : '-', '%', `disk ${num(n.disk, 0)}% used`)}
        ${readout('Board temp', online ? num(n.temp, 0) : '-', '°C', `warn at ${num(c.tempWarnC, 0)} °C`, online && n.temp >= c.tempWarnC ? 'bad' : '')}
        ${readout('Uptime', online ? esc(dur(n.uptimeSec)) : 'down', '', `last heartbeat ${esc(ago(n.lastSeen))}`)}
        ${readout('Cloud link', n.uplinkUp ? 'UP' : 'DOWN', '', `buffer ${num(n.buffered, 0)} / ${num(n.bufferCapacity, 0)}${n.lost ? ` · <b>${num(n.lost, 0)} lost</b>` : ''}`, n.uplinkUp ? 'ok' : 'bad')}
        ${readout('Decision time', fixed(n.edgeMs, 2), 'ms', 'per reading, on the node', 'cyan')}
        ${readout('Data cut today', n.mode === 'RAW' ? '0' : fixed(n.reductionPct, 1), '%', `${bytes(n.todayFwdBytes)} sent of ${bytes(n.todayRawBytes)}`, 'amber')}
      </div>
      ${n.notes ? `<div class="row"><p class="note amber">${esc(n.notes)}</p></div>` : ''}
      <div class="row c2">
        ${panel('Health · last 2 hours', `<div class="panel-body">${health.length > 1 ? chart({
          label: 'CPU and board temperature per minute', w: CW.half, h: 220, yMin: 0, yMax: 100, yFmt: v => num(v, 0),
          layers: [{ kind: 'line', cls: 'amber', pts: health.map(r => [r[0], r[1]]) }, { kind: 'line', cls: 'rust thin', pts: health.map(r => [r[0], r[3]]) },
            { kind: 'line', cls: 'ink thin dash', pts: health.map(r => [r[0], r[2]]) }],
          hlines: [{ y: c.cpuWarnPct, cls: 'bad', label: 'CPU warn' }]
        }) : '<div class="empty">Waiting for heartbeats…</div>'}${legend([['amber', 'CPU %'], ['rust', 'Board °C'], ['', 'Memory %']])}</div>`)}
        ${panel('Traffic · last 60 minutes', `<div class="panel-body">${chart({
          label: 'Messages per minute in and out', w: CW.half, h: 220, yMin: 0, yFmt: v => num(v, 0),
          layers: [{ kind: 'area', cls: 'rust', pts: d.traffic.slice(0, -1).map(r => [r[0], r[1]]), base: 0 }, { kind: 'line', cls: 'rust', pts: d.traffic.slice(0, -1).map(r => [r[0], r[1]]) },
            { kind: 'line', cls: 'cyan', pts: d.traffic.slice(0, -1).map(r => [r[0], r[2]]) }]
        })}${legend([['rust', 'Readings in'], ['cyan', 'Messages sent to cloud']])}</div>`, 'messages / minute')}
      </div>
      ${panel('Sensors on this node', sensorTable(d.sensors, false), ed ? `<button class="btn small" id="addSensorHere" type="button">Add sensor</button>` : '')}
      <section class="panel" id="cmpPanel"></section>
      <div class="row c2">
        ${panel('Alerts on this node', d.alerts.length ? `<ul class="list alerts">${d.alerts.map(a => alertItem(a, false)).join('')}</ul>` : '<div class="empty">No active alerts.</div>')}
        ${panel('Commands', d.commands.length ? `<div class="table-wrap"><table><thead><tr><th>Command</th><th>By</th><th>Sent</th><th>Status</th><th>Result</th></tr></thead><tbody>
          ${d.commands.map(x => `<tr><td>${esc(x.typeLabel)}${x.arg ? ' <span class="mono small">' + esc(x.arg) + '</span>' : ''}</td><td>${userName(x.by)}</td>
          <td class="nowrap">${esc(when(x.createdAt))}</td><td>${tag(x.status, x.status)}</td><td class="small">${esc(x.result || 'waiting for the node')}</td></tr>`).join('')}</tbody></table></div>`
          : '<div class="empty">No commands sent yet.</div>', 'picked up on the next heartbeat')}
      </div>
      ${d.faults.length ? `<div class="row">${panel('Faults running here (fault lab)', faultTable(d.faults))}</div>` : ''}
      ${!n.simulated ? `<div class="row">${panel('Connect a real device', ingestHelp(n, d.sensors))}</div>` : ''}
      <div class="row c2">
        ${panel('Last 7 days', `<div class="table-wrap"><table><thead><tr><th>Day</th><th class="num">Readings</th><th class="num">Sent</th><th class="num">Raw</th><th class="num">Sent size</th><th class="num">Cut</th><th class="num">Uptime</th></tr></thead><tbody>
          ${d.days.map(x => `<tr><td>${esc(dayLabel(x.date))}</td><td class="num">${num(x.raw, 0)}</td><td class="num">${num(x.forwarded, 0)}</td><td class="num">${bytes(x.rawBytes)}</td>
          <td class="num">${bytes(x.fwdBytes)}</td><td class="num">${x.rawBytes ? fixed(100 - pct(x.fwdBytes, x.rawBytes), 1) + '%' : '-'}</td><td class="num">${x.uptimePct == null ? '-' : fixed(x.uptimePct, 1) + '%'}</td></tr>`).join('')}</tbody></table></div>`)}
        ${panel('Activity on this node', feed(d.events))}
      </div>`;
    view().innerHTML = `<div id="nodePage" data-id="${esc(id)}">${html}</div>`;
    bindAlertLinks(view());
    bindFaultStops();
    if (ed) {
      $$('[data-cmd]').forEach(b => b.addEventListener('click', () => run(b, async () => {
        const r = await api('POST', `/api/nodes/${id}/commands`, { type: b.dataset.cmd });
        toast(r.typeLabel + ' queued for ' + n.name + (online ? '' : ' (runs when it is back online)'));
        setTimeout(() => S.refresh && S.refresh(), 5200);
      })));
      $('#editNode').addEventListener('click', () => nodeForm(n));
      $('#newKey').addEventListener('click', () => confirmBox('Make a new API key for ' + n.name + '? The old key stops working at once.', async () => {
        const r = await api('POST', `/api/nodes/${id}/key`); showKey(n.name, r.apiKey, null);
      }));
      const del = $('#delNode');
      if (del) del.addEventListener('click', () => confirmBox(`Delete ${n.name} and its ${n.sensorCount} sensors? Its history stays in the reports.`, async () => {
        await api('DELETE', '/api/nodes/' + id); closeModal(); toast('Node deleted'); await reloadMeta(); location.hash = '#/nodes';
      }, 'Delete'));
      $('#addSensorHere').addEventListener('click', () => sensorForm(null, id));
    }
    await drawCompare(id, n);
  });
}

function confirmBox(text, action, label = 'Yes') {
  openModal({
    title: 'Please confirm', body: `<p>${esc(text)}</p>`,
    buttons: [{ label: 'Cancel', onClick: closeModal }, { label, cls: label === 'Delete' ? 'danger' : 'primary', onClick: b => run(b, async () => { await action(); if ($('#modalTitle').textContent === 'Please confirm') closeModal(); }) }]
  });
  return true;
}

/** "What if" panel: replays the last hour of this node's readings through all four modes. */
async function drawCompare(id, n) {
  const st = S.cmp[id];
  const qs = new URLSearchParams();
  if (st.deadband != null) qs.set('deadband', st.deadband);
  if (st.window != null) qs.set('window', st.window);
  const r = await api('GET', `/api/nodes/${id}/compare?` + qs);
  const box = $('#cmpPanel');
  if (!box) return;
  box.innerHTML = `<div class="panel-head"><h3>What if? Compare processing modes</h3><span class="meta">replays the last ${num(r.minutes, 0)} min · ${num(r.readings, 0)} readings</span></div>
    <div class="panel-body"><div class="sliders">
      <div><label class="lbl" for="sDb">Dead-band <output id="oDb">${fixed(r.deadbandPct, 1)}% of range</output></label>
        <input type="range" id="sDb" min="0.5" max="20" step="0.5" value="${esc(r.deadbandPct)}"></div>
      <div><label class="lbl" for="sWin">Window <output id="oWin">${esc(r.windowSec)} s</output></label>
        <input type="range" id="sWin" min="10" max="600" step="10" value="${esc(r.windowSec)}"></div>
      <button class="btn small" id="cmpReset" type="button">Use node settings</button>
    </div></div>
    ${compareCards(r, n)}`;
  applySizes(box);
  const redraw = debounce(() => drawCompare(id, n).catch(e => toast(e.message, 'err')), 200);
  $('#sDb').addEventListener('input', e => { st.deadband = Number(e.target.value); $('#oDb').textContent = fixed(st.deadband, 1) + '% of range'; redraw(); });
  $('#sWin').addEventListener('input', e => { st.window = Number(e.target.value); $('#oWin').textContent = st.window + ' s'; redraw(); });
  $('#cmpReset').addEventListener('click', () => { st.deadband = null; st.window = null; redraw(); });
  $$('[data-setmode]', box).forEach(b => b.addEventListener('click', () => run(b, async () => {
    const body = { mode: b.dataset.setmode };
    if (st.deadband != null) body.deadbandPct = st.deadband;
    if (st.window != null) body.windowSec = st.window;
    await api('PUT', '/api/nodes/' + id, body);
    st.deadband = null; st.window = null;
    toast(n.name + ' now uses ' + b.dataset.setmode + ' mode');
    S.refresh();
  })));
}

function compareCards(r, n) {
  return `<div class="modes">${r.modes.map(m => `<div class="mode-card ${esc(m.mode)}${m.current ? ' current' : ''}">
    <h4>${esc(m.label)}</h4>
    <div class="pct">${m.mode === 'RAW' ? '0' : '−' + fixed(m.reductionPct, 1)}%</div>
    ${meter(100 - m.reductionPct, m.mode === 'RAW' ? 'rust' : '')}
    <p>${esc(m.help)}</p>
    <dl><dt>Messages</dt><dd>${num(m.messages, 0)}</dd><dt>Bytes</dt><dd>${bytes(m.bytes)}</dd>
    <dt>Per day</dt><dd>${bytes(m.perDayBytes)}</dd><dt>Per month</dt><dd>${money(m.perMonthCost)}</dd></dl>
    ${m.current ? `<p class="small"><b>Current mode</b></p>` : canEdit() && n ? `<p><button class="btn small" data-setmode="${esc(m.mode)}" type="button">Use this mode</button></p>` : ''}
  </div>`).join('')}</div>`;
}

function ingestHelp(n, sensors) {
  const ex = sensors.slice(0, 2).map((s, i) => `{"sensorId":"${s.id}","value":${i ? 21.5 : 5.4}}`).join(',');
  return `<div class="panel-body"><p>This node is a slot for a <b>real device</b> (for example a Raspberry Pi). Steps:</p>
    <ol class="small"><li>Turn the node on (Edit → "Node is on").</li><li>Click <b>New API key</b> and copy it (key ends with <span class="mono">…${esc(n.keyHint || '????')}</span> now).</li>
    <li>From the device, post readings every few seconds. The app runs the same edge pipeline for this node.</li></ol>
    <p class="small muted">Windows PowerShell:</p>
    <pre class="code">$body = '{"health":{"cpu":21,"mem":44,"disk":31,"temp":49},"readings":[${esc(ex)}]}'
Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/ingest \`
  -Headers @{ "X-Node-Key" = "PASTE-KEY-HERE" } -ContentType "application/json" -Body $body</pre>
    <p class="small muted">Linux / Mac / Raspberry Pi:</p>
    <pre class="code">curl -X POST http://localhost:8080/api/ingest -H "X-Node-Key: PASTE-KEY-HERE" \\
  -H "Content-Type: application/json" \\
  -d '{"health":{"cpu":21,"mem":44,"disk":31,"temp":49},"readings":[${esc(ex)}]}'</pre>
    <p class="small muted">The answer says how many readings were accepted, which alerts fired, and hands over any waiting commands.</p></div>`;
}

// ================================================================== 03 sensors

function sensorTable(list, withNode = true) {
  if (!list.length) return '<div class="empty">No sensors.</div>';
  return `<div class="table-wrap"><table><thead><tr><th>Sensor</th>${withNode ? '<th>Edge node</th>' : ''}<th>Type</th><th class="num">Live value</th>
    <th>Normal range</th><th>Last 30 min</th><th>State</th><th class="num">Every</th></tr></thead><tbody>
    ${list.map(s => `<tr class="click" data-href="#/sensor/${esc(s.id)}"><td><a href="#/sensor/${esc(s.id)}"><b>${esc(s.name)}</b></a><span class="sub mono">${esc(s.id)}</span></td>
      ${withNode ? `<td class="mono small">${esc(s.nodeName)}</td>` : ''}<td>${esc(s.typeLabel)}</td>
      <td class="num"><b>${s.lastValue == null ? '-' : esc(val(s.lastValue))}</b> <span class="muted">${esc(s.unit)}</span></td>
      <td>${rangeBar(s.lastValue, s.low, s.high)}<span class="sub mono">${esc(val(s.low))} – ${esc(val(s.high))}</span></td>
      <td>${spark(s.spark, s.state)}</td>
      <td><span class="nowrap">${led(s.state)} ${esc(STATE_TEXT[s.state] || s.state)}</span></td>
      <td class="num">${esc(s.sampleSec)} s</td></tr>`).join('')}</tbody></table></div>`;
}

function bindRowLinks(root) {
  $$('tr[data-href]', root).forEach(tr => tr.addEventListener('click', e => { if (!e.target.closest('a,button')) location.hash = tr.dataset.href; }));
}

async function pageSensors() {
  const f = S.sensorFilter;
  view().innerHTML = head('Sensors', 'Every IoT sensor and its latest reading, as seen by its edge node.',
    canEdit() ? '<button class="btn primary" id="addSensor" type="button">Add sensor</button>' : '') +
    `<div class="toolbar">
      <select id="fNode" aria-label="Edge node"><option value="">All edge nodes</option>${S.meta.nodes.map(n => opt(n.id, n.name, f.node)).join('')}</select>
      <select id="fType" aria-label="Type"><option value="">All types</option>${S.meta.sensorTypes.map(t => opt(t.id, t.label, f.type)).join('')}</select>
      <select id="fState" aria-label="State"><option value="">Any state</option>${Object.entries(STATE_TEXT).map(([k, v]) => opt(k, v, f.state)).join('')}</select>
      <input type="search" id="fQ" placeholder="Search sensors" value="${esc(f.q)}" aria-label="Search">
    </div><section class="panel" id="results"></section>`;
  $('#fNode').addEventListener('change', e => { f.node = e.target.value; S.refresh(); });
  $('#fType').addEventListener('change', e => { f.type = e.target.value; S.refresh(); });
  $('#fState').addEventListener('change', e => { f.state = e.target.value; S.refresh(); });
  $('#fQ').addEventListener('input', debounce(e => { f.q = e.target.value; S.refresh(); }, 250));
  if (canEdit()) $('#addSensor').addEventListener('click', () => sensorForm(null, f.node || null));
  await live(async () => {
    const list = await api('GET', '/api/sensors?' + new URLSearchParams(f));
    $('#results').innerHTML = `<div class="panel-head"><h3>${list.length} sensors</h3><span class="meta">bar = normal range · pin = live value</span></div>` + sensorTable(list);
    bindRowLinks($('#results'));
  });
}

function sensorForm(s, nodeId) {
  const m = S.meta;
  openModal({
    title: s ? 'Edit ' + s.name : 'Add sensor',
    wide: true,
    body: `<div class="grid2">${field('Sensor name', input('name', s && s.name, 'required maxlength="60"'))}
      ${field('Edge node', `<select name="nodeId">${m.nodes.map(n => opt(n.id, n.name, s ? s.nodeId : nodeId)).join('')}</select>`)}</div>
      <div class="grid3">${field('Type', `<select name="type" id="fType2">${m.sensorTypes.map(t => opt(t.id, t.label + ' (' + t.unit + ')', s ? s.type : 'TEMPERATURE')).join('')}</select>`)}
      ${field('Normal low', input('low', s ? s.low : '', 'type="number" step="any" required'))}
      ${field('Normal high', input('high', s ? s.high : '', 'type="number" step="any" required'))}</div>
      <p class="hint small muted" id="typeLimits"></p>
      <div class="grid3">${field('Reading every (s)', input('sampleSec', s ? s.sampleSec : 5, 'type="number" min="1" max="3600"'))}
      ${field('Simulated average', input('base', s ? s.base : '', 'type="number" step="any"'), 'Blank = middle of the normal range')}
      ${field('Daily swing', input('swing', s ? s.swing : '', 'type="number" step="any" min="0"'))}</div>
      ${field('Noise', input('noise', s ? s.noise : '', 'type="number" step="any" min="0"'), 'Simulator only: random wobble on each reading')}
      <label class="check"><input type="checkbox" name="enabled" ${!s || s.enabled ? 'checked' : ''}> Sensor is on</label>`,
    buttons: [
      ...(s ? [{ label: 'Delete', cls: 'danger', onClick: () => confirmBox(`Delete ${s.name}? Its rules and hourly history go too.`, async () => {
        await api('DELETE', '/api/sensors/' + s.id); closeModal(); toast('Sensor deleted'); await reloadMeta(); location.hash = '#/node/' + s.nodeId;
      }, 'Delete') }] : []),
      { label: 'Cancel', onClick: closeModal }, { label: 'Save', cls: 'primary', submit: true }],
    onSubmit: async () => {
      const b = formData();
      ['base', 'swing', 'noise'].forEach(k => { if (b[k] == null) delete b[k]; });
      const r = await api(s ? 'PUT' : 'POST', '/api/sensors' + (s ? '/' + s.id : ''), b);
      closeModal(); toast('Sensor saved'); await reloadMeta();
      if (!s) location.hash = '#/sensor/' + r.id; else route();
    }
  });
  const lim = () => { const t = m.sensorTypes.find(x => x.id === $('#fType2').value); $('#typeLimits').textContent = t ? `Sensor limits: ${t.lo} to ${t.hi} ${t.unit}. Readings outside these are dropped at the edge as faulty.` : ''; };
  $('#fType2').addEventListener('change', lim); lim();
}

// ================================================================== sensor detail

async function pageSensor(id) {
  await live(async () => {
    const d = await api('GET', '/api/sensors/' + encodeURIComponent(id));
    const s = d.sensor;
    const n = d.node || {};
    const lh = d.lastHour;
    const now = Date.now();
    const raw = d.raw;
    const valid = raw.filter(r => r[2] !== 2);
    const sentN = raw.filter(r => r[2] === 1).length;
    const bad = raw.filter(r => r[2] === 2);
    // zoom on the data (not the whole normal range), so small changes and the dead-band are visible
    const vs = valid.map(r => r[1]);
    const dMin = vs.length ? Math.min(...vs) : s.low, dMax = vs.length ? Math.max(...vs) : s.high;
    const padY = Math.max((dMax - dMin) * 0.25, (n.deadband || 0) * 1.5, Math.abs(dMax) * 0.002, 0.01);
    const yMin = dMin - padY, yMax = dMax + padY;
    const xMin = now - 15 * 60000;
    const sums = d.summaries.filter(r => r[0] + n.windowSec * 1000 >= xMin);
    const showSums = n.mode === 'AGGREGATE' || n.mode === 'SMART';
    const edgeChart = valid.length ? chart({
      label: 'Raw readings in the last 15 minutes; filled dots were sent to the cloud, hollow ones stayed on the edge node',
      w: CW.full, h: 300, xMin, xMax: now, yFmt: v => val(v), yMin, yMax,
      bands: [{ lo: s.low, hi: s.high, cls: 'okband' }],
      hlines: [{ y: s.low, cls: 'ok', label: 'normal low' }, { y: s.high, cls: 'ok', label: 'normal high' }],
      layers: [
        { kind: 'line', cls: 'ink thin', pts: valid.map(r => [r[0], r[1]]) },
        { kind: 'dots', r: valid.length > 150 ? 2.3 : 3.2, pts: valid.map(r => [r[0], r[1], r[2] === 1 ? 'sent' : 'kept', `${hms(r[0])}  ${val(r[1])} ${s.unit} · ${r[2] === 1 ? 'sent to cloud' : 'kept at edge'}`]) },
        ...(showSums ? [{ kind: 'segs', segs: sums.map(r => [Math.max(xMin, r[0]), Math.min(now, r[0] + n.windowSec * 1000), r[3], `Summary sent: avg ${val(r[3])}, min ${val(r[1])}, max ${val(r[2])}, ${r[4]} readings`]) }] : []),
        { kind: 'dots', r: 4.5, pts: bad.map(r => [r[0], yMax, 'bad', `${hms(r[0])}  impossible value ${val(r[1])} dropped`]) }
      ]
    }) : '<div class="empty">No readings in the last 15 minutes.</div>';
    const mins = d.minutes;
    const hours = d.hours;
    const ed = canEdit();
    view().innerHTML = head(`${led(s.state, s.state === 'alert')} ${esc(s.name)}`,
      `${tag(s.typeLabel)} on <a href="#/node/${esc(s.nodeId)}" class="mono">${esc(s.nodeName)}</a> · a reading every ${esc(s.sampleSec)} s ·
       normal ${esc(val(s.low))} – ${esc(val(s.high))} ${esc(s.unit)} · node mode ${modeTag(n.mode)}`,
      ed ? `<a class="btn" href="#/lab" id="faultHere">Inject a fault</a><button class="btn" id="editSensor" type="button">Edit</button>` : '',
      `<a href="#/sensors">Sensors</a> / <a href="#/node/${esc(s.nodeId)}">${esc(s.nodeName)}</a> / ${esc(s.id)}`) +
      `<div class="readouts">
        ${readout('Live value', s.lastValue == null ? '-' : esc(val(s.lastValue)), s.unit, `${esc(STATE_TEXT[s.state])} · ${esc(ago(s.lastAt))}`, s.state === 'alert' ? 'bad' : s.state === 'out' ? 'amber' : 'cyan')}
        ${readout('Readings · last hour', num(lh.readings, 0), '', `mean ${esc(val(lh.mean))} · std ${esc(val(lh.std))}`)}
        ${readout('Sent as raw', num(lh.sentRaw, 0), '', `${fixed(pct(lh.sentRaw, lh.readings), 1)}% of readings`, 'amber')}
        ${readout('Summaries sent', num(lh.summaries, 0), '', showSums ? `one per ${n.windowSec} s window` : 'mode does not use windows')}
        ${readout('Kept at the edge', num(lh.keptAtEdge, 0), '', 'checked, never sent', 'ok')}
        ${readout('Dropped as invalid', num(lh.invalid, 0), '', 'impossible values', lh.invalid ? 'bad' : '')}
      </div>
      ${panel('At the edge · last 15 minutes', `<div class="panel-body">${edgeChart}
        ${legend([['dot sent', 'Sent to cloud as raw'], ['dot kept', 'Kept at edge (checked, not sent)'], ...(showSums ? [['cyan', 'Window summary sent (average)']] : []), ['dot bad', 'Impossible value dropped'], ['ok', 'Normal range']])}
        <p class="small muted">In the last 15 minutes ${num(valid.length, 0)} readings arrived; only <b>${num(sentN, 0)}</b> were sent on as raw${showSums ? ` plus ${num(sums.length, 0)} summaries` : ''}.
        Dead-band for this sensor: ${esc(val(n.deadband))} ${esc(s.unit)} (${esc(n.deadbandPct)}% of its normal range).</p></div>`, esc(n.modeLabel || ''))}
      <div class="row c2">
        ${panel('Last 6 hours · per minute', `<div class="panel-body">${mins.length > 1 ? chart({
          label: 'Per minute minimum to maximum band with average line', w: CW.half, h: 230, yFmt: v => val(v),
          bands: [{ lo: s.low, hi: s.high, cls: 'okband' }],
          layers: [{ kind: 'band', pts: mins.map(r => [r[0], r[1], r[2]]) }, { kind: 'line', cls: 'cyan', pts: mins.map(r => [r[0], r[3]]) }]
        }) : '<div class="empty">Not enough data yet.</div>'}${legend([['band', 'min – max'], ['cyan', 'average'], ['ok', 'normal range']])}</div>`)}
        ${panel('Last 7 days · per hour', `<div class="panel-body">${hours.length > 1 ? chart({
          label: 'Hourly minimum to maximum band with average line', w: CW.half, h: 230, yFmt: v => val(v), xFmt: v => new Date(v).toLocaleDateString('en-IN', { day: 'numeric', month: 'short' }),
          bands: [{ lo: s.low, hi: s.high, cls: 'okband' }],
          layers: [{ kind: 'band', pts: hours.map(r => [r[0], r[1], r[2]]) }, { kind: 'line', cls: 'cyan', pts: hours.map(r => [r[0], r[3]]) }]
        }) : '<div class="empty">Not enough data yet.</div>'}${legend([['band', 'min – max'], ['cyan', 'average']])}</div>`, 'kept as hourly summaries')}
      </div>
      <div class="row c2">
        ${panel('Rules checking this sensor', d.rules.length ? `<div class="table-wrap"><table><thead><tr><th>Rule</th><th>When</th><th>Severity</th><th class="num">Fired</th></tr></thead><tbody>
          ${d.rules.map(r => `<tr><td><b>${esc(r.name)}</b>${r.enabled ? '' : ' ' + tag('off')}<span class="sub">${esc(r.action ? 'edge action: ' + r.action : '')}</span></td>
          <td class="small">${esc(ruleText(r))}</td><td>${tag(r.severity, r.severity)}</td><td class="num">${num(r.hits, 0)}</td></tr>`).join('')}</tbody></table></div>`
          : '<div class="empty">No rules check this sensor.</div>', '<a href="#/rules">manage rules</a>')}
        ${panel('Alert history', d.alerts.length ? `<ul class="list alerts">${d.alerts.map(a => alertItem(a, false)).join('')}</ul>` : '<div class="empty">No alerts for this sensor.</div>')}
      </div>
      ${d.faults.length ? `<div class="row">${panel('Faults running on this sensor', faultTable(d.faults))}</div>` : ''}`;
    bindAlertLinks(view());
    bindFaultStops();
    if (ed) {
      $('#editSensor').addEventListener('click', () => sensorForm(s, s.nodeId));
      $('#faultHere').addEventListener('click', () => { S.labPick = { nodeId: s.nodeId, sensorId: s.id }; });
    }
  });
}

function ruleText(r) {
  const u = r.unit ? ' ' + r.unit : '';
  const t = r.threshold;
  const lasting = r.durationSec ? ` for ${dur(r.durationSec)}` : '';
  switch (r.condition) {
    case 'ABOVE': return `above ${val(t)}${u}${lasting}`;
    case 'BELOW': return `below ${val(t)}${u}${lasting}`;
    case 'RATE_OF_CHANGE': return `changes more than ${val(t)}${u} per minute${lasting}`;
    case 'ANOMALY': return `unusual (|z| ≥ ${val(t)})${lasting}`;
    case 'NO_DATA': return `no data for ${dur(t)}`;
    default: return r.conditionLabel;
  }
}

// ================================================================== 04 alerts

async function pageAlerts() {
  const f = S.alertFilter;
  view().innerHTML = head('Alerts', 'Raised by rules running on the edge nodes and by the built-in health checks.',
    `<button class="btn" id="ackAll" type="button">Acknowledge all open</button><button class="btn" id="csv" type="button">Export CSV</button>`) +
    `<div class="toolbar"><div class="tabs" id="aTabs" role="tablist"></div>
      <select id="fSev" aria-label="Severity"><option value="">Any severity</option>${['CRITICAL', 'WARNING', 'INFO'].map(x => opt(x, x[0] + x.slice(1).toLowerCase(), f.severity)).join('')}</select>
      <select id="fNode" aria-label="Edge node"><option value="">All edge nodes</option>${S.meta.nodes.map(n => opt(n.id, n.name, f.node)).join('')}</select>
      <input type="search" id="fQ" placeholder="Search alerts" value="${esc(f.q)}" aria-label="Search">
    </div><section class="panel" id="results"></section>`;
  $('#fSev').addEventListener('change', e => { f.severity = e.target.value; S.refresh(); });
  $('#fNode').addEventListener('change', e => { f.node = e.target.value; S.refresh(); });
  $('#fQ').addEventListener('input', debounce(e => { f.q = e.target.value; S.refresh(); }, 250));
  $('#ackAll').addEventListener('click', e => run(e.target, async () => {
    const r = await api('POST', '/api/alerts/ack-all' + (f.node ? '?node=' + encodeURIComponent(f.node) : ''));
    toast(r.acknowledged + ' alerts acknowledged'); S.refresh();
  }));
  $('#csv').addEventListener('click', e => run(e.target, async () => {
    const qs = new URLSearchParams({ status: f.status === 'ALL' || f.status === 'ACTIVE' ? '' : f.status, severity: f.severity, node: f.node });
    const res = await fetch('/api/alerts/export.csv?' + qs, { headers: { Authorization: 'Bearer ' + S.token } });
    if (!res.ok) throw new Error('Export failed');
    const blob = await res.blob();
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob); a.download = 'edge-alerts.csv';
    document.body.appendChild(a); a.click(); a.remove();
    setTimeout(() => URL.revokeObjectURL(a.href), 2000);
  }));
  await live(async () => {
    const qs = new URLSearchParams({ status: f.status === 'ALL' ? '' : f.status, severity: f.severity, node: f.node, q: f.q, limit: 300 });
    const d = await api('GET', '/api/alerts?' + qs);
    const tabs = [['ACTIVE', 'Active', d.open + d.acknowledged], ['OPEN', 'Open', d.open], ['ACKNOWLEDGED', 'Acknowledged', d.acknowledged], ['RESOLVED', 'Resolved', null], ['ALL', 'All', null]];
    $('#aTabs').innerHTML = tabs.map(([k, l, c]) => `<button type="button" class="${f.status === k ? 'on' : ''}" data-tab="${k}">${l}${c != null ? `<span class="c">${c}</span>` : ''}</button>`).join('');
    $$('#aTabs button').forEach(b => b.addEventListener('click', () => { f.status = b.dataset.tab; S.refresh(); }));
    const ed = canEdit();
    $('#results').innerHTML = `<div class="panel-head"><h3>${num(d.total, 0)} alerts</h3><span class="meta">edge decision vs cloud round trip</span></div>` + (d.alerts.length ? `<div class="table-wrap"><table>
      <thead><tr><th>Severity</th><th>Alert</th><th>Opened</th><th>Decision</th><th>Edge action</th><th>Status</th><th></th></tr></thead><tbody>
      ${d.alerts.map(a => `<tr class="click" data-id="${esc(a.id)}"><td>${tag(a.severity, a.severity)}</td>
        <td><b>${esc(a.message)}</b><span class="sub">${esc(a.ruleName || '')} · <span class="mono">${esc(a.nodeName)}</span>${a.sensorName ? ' / ' + esc(a.sensorName) : ''}${a.repeats ? ' · repeated ' + a.repeats + '×' : ''}</span></td>
        <td class="nowrap">${esc(ago(a.openedAt))}<span class="sub">${esc(when(a.openedAt))}</span></td>
        <td class="nowrap">${a.edgeMs ? `<span class="lat"><b>${fixed(a.edgeMs, 1)} ms</b><br>cloud ${num(a.cloudMs, 0)} ms</span>` : '<span class="muted small">health check</span>'}</td>
        <td class="small">${esc(a.action || '-')}</td>
        <td>${tag(a.status, a.status)}${a.clearedAt && a.status !== 'RESOLVED' ? '<span class="sub">condition cleared</span>' : ''}</td>
        <td class="right nowrap">${a.status === 'OPEN' ? `<button class="btn small" data-ack="${esc(a.id)}" type="button">Ack</button>` : ''}
          ${ed && a.status !== 'RESOLVED' ? `<button class="btn small" data-resolve="${esc(a.id)}" type="button">Resolve</button>` : ''}</td></tr>`).join('')}
      </tbody></table></div>` : '<div class="empty">No alerts here.</div>');
    $$('#results tr[data-id]').forEach(tr => tr.addEventListener('click', e => { if (!e.target.closest('button')) showAlert(tr.dataset.id, d.alerts.find(a => a.id === tr.dataset.id)); }));
    $$('[data-ack]').forEach(b => b.addEventListener('click', () => run(b, async () => { await api('POST', `/api/alerts/${b.dataset.ack}/ack`); toast('Acknowledged'); S.refresh(); })));
    $$('[data-resolve]').forEach(b => b.addEventListener('click', () => resolveForm(b.dataset.resolve)));
  });
}

async function showAlert(id, a) {
  if (!a) {
    try {
      a = await api('GET', '/api/alerts/' + encodeURIComponent(id));
    } catch (e) { if (!(e instanceof StaleError)) toast(e.message, 'err'); return; }
  }
  const timeline = [
    ['Opened', a.openedAt, 'by ' + (a.system ? 'health check' : 'edge rule')],
    ['Last seen', a.lastSeenAt, a.repeats ? `repeated ${a.repeats}×` : ''],
    ['Condition cleared', a.clearedAt, ''],
    ['Acknowledged', a.ackAt, a.ackBy ? 'by ' + userName(a.ackBy) : ''],
    ['Resolved', a.resolvedAt, a.resolvedBy ? 'by ' + userName(a.resolvedBy) : '']
  ].filter(x => x[1]);
  openModal({
    title: a.id + ' · ' + (a.ruleName || 'Alert'),
    body: `<p>${tag(a.severity, a.severity)} ${tag(a.status, a.status)}</p><h3>${esc(a.message)}</h3><br>
      <dl class="kv"><dt>Edge node</dt><dd><a href="#/node/${esc(a.nodeId)}" class="mono">${esc(a.nodeName)}</a></dd>
      ${a.sensorName ? `<dt>Sensor</dt><dd><a href="#/sensor/${esc(a.sensorId)}">${esc(a.sensorName)}</a></dd>` : ''}
      <dt>Value</dt><dd class="mono">${esc(val(a.value))} ${esc(a.unit || '')} (threshold ${esc(val(a.threshold))})</dd>
      ${a.edgeMs ? `<dt>Decision</dt><dd>Decided on the edge node in <b>${fixed(a.edgeMs, 2)} ms</b>. Sending the reading to the cloud and waiting for an answer would take about <b>${num(a.cloudMs, 0)} ms</b>.</dd>` : ''}
      ${a.action ? `<dt>Edge action</dt><dd>${esc(a.action)} <span class="muted small">(done locally, even if the internet is down)</span></dd>` : ''}
      ${timeline.map(t => `<dt>${esc(t[0])}</dt><dd>${esc(when(t[1]))} <span class="muted small">${esc(t[2])}</span></dd>`).join('')}
      ${a.note ? `<dt>Note</dt><dd>${esc(a.note)}</dd>` : ''}</dl>`,
    buttons: [
      ...(a.status === 'OPEN' ? [{ label: 'Acknowledge', onClick: b => run(b, async () => { await api('POST', `/api/alerts/${a.id}/ack`); closeModal(); toast('Acknowledged'); S.refresh && S.refresh(); }) }] : []),
      ...(canEdit() && a.status !== 'RESOLVED' ? [{ label: 'Resolve…', cls: 'primary', onClick: () => resolveForm(a.id) }] : []),
      { label: 'Close', onClick: closeModal }]
  });
}

function resolveForm(id) {
  openModal({
    title: 'Resolve alert ' + id,
    body: field('What was done? (optional)', '<textarea name="note" maxlength="300" placeholder="e.g. Bearing re-greased, temperature back to normal"></textarea>'),
    buttons: [{ label: 'Cancel', onClick: closeModal }, { label: 'Resolve', cls: 'primary', submit: true }],
    onSubmit: async () => { await api('POST', `/api/alerts/${id}/resolve`, formData()); closeModal(); toast('Resolved'); S.refresh && S.refresh(); }
  });
}

// ================================================================== 05 pipeline

async function pagePipeline() {
  await live(async () => {
    const d = await api('GET', '/api/dashboard');
    const t = d.today;
    const nodes = [];
    d.topology.forEach(s => s.nodes.forEach(n => nodes.push(n)));
    if (!nodes.find(n => n.id === S.pipeNode) && nodes.length) S.pipeNode = nodes[0].id;
    const stages = [
      ['Ingest', num(t.raw, 0), 'readings', 'Sensors send readings to the node over the local network (Modbus, MQTT, LoRa…).'],
      ['Validate', num(t.invalid, 0), 'dropped', 'Impossible values (outside the sensor\'s limits) are thrown away here, not paid for in the cloud.'],
      ['Analyse', num(t.valid, 0), 'checked', 'Rolling average and standard deviation, rate of change, minute and hour summaries.'],
      ['Detect', num(t.alerts, 0), 'alerts', `Rules run on the node and act in about ${fixed(t.edgeMs, 1)} ms. No round trip to the cloud.`],
      ['Reduce', fixed(t.reductionPct, 1) + '%', 'less data', 'The node\'s mode decides what to send: everything, only changes, summaries, or summaries + events.'],
      ['Forward', num(t.forwarded, 0), 'messages', `Sent to the cloud, or kept in the buffer when the internet is down (${num(t.buffered, 0)} buffered today).`]
    ];
    const modeUse = {};
    nodes.forEach(n => { (modeUse[n.mode] = modeUse[n.mode] || []).push(n.name); });
    const firstDraw = !$('#pipeCmp');
    const html = head('Pipeline & modes', 'What happens to every reading inside an edge node, and how much each processing mode saves.') +
      panel('The six steps on every edge node · today', `<div class="pipe">${stages.map((s, i) => `<div class="stage"><span class="n">0${i + 1}</span><h4>${esc(s[0])}</h4>
        <div class="big">${esc(s[1])}</div><span class="muted small">${esc(s[2])}</span><p>${esc(s[3])}</p></div>`).join('')}</div>`) +
      `<div class="row c2">
        ${panel('Why decide at the edge?', `<div class="panel-body">
          <div class="readouts">${readout('At the edge', fixed(t.edgeMs, 1), 'ms', 'local network hop + processing', 'cyan')}
          ${readout('Via the cloud', num(t.cloudMs, 0), 'ms', 'send, wait in queue, get answer back', 'bad')}</div><br>
          <p>Think of a bearing that is overheating. The edge node sees the reading, the rule fires and it slows the press line in a few milliseconds.
          If every reading had to travel to a cloud data centre first, the answer would come back about <b>${t.edgeMs > 0 ? Math.round(t.cloudMs / t.edgeMs) : '-'}× later</b>,
          and not at all when the internet is down.</p>
          <p class="small muted">Edge computing also keeps raw data on site (privacy) and cuts mobile-data and cloud costs.</p></div>`)}
        ${panel('The four modes', `<div class="table-wrap"><table><thead><tr><th>Mode</th><th>What leaves the node</th><th>Used by</th></tr></thead><tbody>
          ${S.meta.modes.map(m => `<tr><td>${modeTag(m.id)}</td><td class="small">${esc(m.help)}</td><td class="small mono">${esc((modeUse[m.id] || []).join(', ') || '-')}</td></tr>`).join('')}</tbody></table></div>`)}
      </div>
      <section class="panel" id="pipeCmp"><div class="panel-head"><h3>Try it on real data</h3>
        <select id="pNode" aria-label="Edge node">${nodes.filter(n => n.status !== 'DISABLED').map(n => opt(n.id, n.name, S.pipeNode)).join('')}</select></div><div id="pipeCmpBody"></div></section>`;
    if (firstDraw) {
      view().innerHTML = html;
      $('#pNode').addEventListener('change', e => { S.pipeNode = e.target.value; drawPipeCompare(); });
      await drawPipeCompare();
    } else {
      // keep the comparison panel (and its sliders) as it is; only refresh the numbers above it
      const keep = $('#pipeCmp');
      const tmp = document.createElement('div');
      tmp.innerHTML = html;
      tmp.querySelector('#pipeCmp').replaceWith(keep);
      view().replaceChildren(...tmp.childNodes);
    }
  });
}

async function drawPipeCompare() {
  const id = S.pipeNode;
  if (!S.cmp[id]) S.cmp[id] = { deadband: null, window: null };
  const holder = $('#pipeCmpBody');
  holder.innerHTML = '<section class="panel" id="cmpPanel"></section>';
  await drawCompare(id, { id, name: (S.meta.nodes.find(n => n.id === id) || {}).name });
  // inside the pipeline page the inner panel needs no extra border
  $('#cmpPanel').classList.remove('panel');
}

// ================================================================== 06 rules

async function pageRules() {
  view().innerHTML = head('Rules', 'Checked by the edge node on every reading. When a rule fires, the node raises an alert and does its edge action at once.',
    canEdit() ? '<button class="btn primary" id="addRule" type="button">Add rule</button>' : '') + '<section class="panel" id="results"></section>' +
    `<div class="row">${panel('Built-in health checks (always on)', `<div class="table-wrap"><table><thead><tr><th>Check</th><th>Fires when</th><th>Severity</th></tr></thead><tbody>
      <tr><td><b>Node offline</b></td><td>No heartbeat for ${esc(S.meta.settings.heartbeatTimeoutSec)} s</td><td>${tag('CRITICAL', 'CRITICAL')}</td></tr>
      <tr><td><b>Cloud link down</b></td><td>The node cannot reach the cloud and starts buffering</td><td>${tag('WARNING', 'WARNING')}</td></tr>
      <tr><td><b>High CPU</b></td><td>CPU at or above ${esc(S.meta.settings.cpuWarnPct)}%</td><td>${tag('WARNING', 'WARNING')}</td></tr>
      <tr><td><b>Node overheating</b></td><td>Board at or above ${esc(S.meta.settings.tempWarnC)} °C</td><td>${tag('WARNING', 'WARNING')}</td></tr>
      <tr><td><b>Buffer almost full</b></td><td>Buffer at or above ${esc(S.meta.settings.bufferWarnPct)}% (critical when full)</td><td>${tag('WARNING', 'WARNING')}</td></tr>
      <tr><td><b>Garbage readings</b></td><td>3+ impossible values from one sensor in a minute</td><td>${tag('WARNING', 'WARNING')}</td></tr>
      </tbody></table></div>`, 'change limits in Settings')}</div>`;
  if (canEdit()) $('#addRule').addEventListener('click', () => ruleForm(null));
  await live(async () => {
    const rules = await api('GET', '/api/rules');
    const ed = canEdit();
    $('#results').innerHTML = `<div class="panel-head"><h3>${rules.length} rules</h3></div>` + (rules.length ? `<div class="table-wrap"><table><thead><tr>
      <th>On</th><th>Rule</th><th>Checks</th><th>Fires when</th><th>Severity</th><th>Edge action</th><th class="num">Fired</th><th>Last fired</th><th></th></tr></thead><tbody>
      ${rules.map(r => `<tr><td>${ed ? `<input type="checkbox" data-toggle="${esc(r.id)}" ${r.enabled ? 'checked' : ''} aria-label="Rule on">` : (r.enabled ? 'on' : 'off')}</td>
        <td><b>${esc(r.name)}</b><span class="sub mono">${esc(r.id)}</span></td>
        <td class="small">${esc(r.target)}<span class="sub">${r.sensorCount} sensors</span></td><td class="small">${esc(ruleText(r))}</td>
        <td>${tag(r.severity, r.severity)}</td><td class="small">${esc(r.action || '-')}</td>
        <td class="num">${num(r.hits, 0)}${r.activeAlerts ? `<span class="sub">${r.activeAlerts} active</span>` : ''}</td>
        <td class="small nowrap">${r.lastHit ? esc(ago(r.lastHit)) : 'never'}</td>
        <td class="right">${ed ? `<button class="btn small" data-edit="${esc(r.id)}" type="button">Edit</button>` : ''}</td></tr>`).join('')}</tbody></table></div>` : '<div class="empty">No rules yet.</div>');
    $$('[data-edit]').forEach(b => b.addEventListener('click', () => ruleForm(rules.find(r => r.id === b.dataset.edit))));
    $$('[data-toggle]').forEach(c => c.addEventListener('change', () => run(c, async () => {
      await api('PUT', '/api/rules/' + c.dataset.toggle, { enabled: c.checked }); toast(c.checked ? 'Rule on' : 'Rule off'); S.refresh();
    })));
  });
}

function ruleForm(r) {
  const m = S.meta;
  const target = r ? (r.sensorId ? 'sensor' : 'type') : 'type';
  openModal({
    title: r ? 'Edit rule' : 'Add rule',
    wide: true,
    body: `${field('Rule name', input('name', r && r.name, 'required maxlength="60" placeholder="e.g. Motor too hot"'))}
      <div class="grid2">${field('Checks', `<select id="rTarget"><option value="type"${target === 'type' ? ' selected' : ''}>A type of sensor</option><option value="sensor"${target === 'sensor' ? ' selected' : ''}>One sensor</option></select>`)}
      <div id="rTargetBox"></div></div>
      <div class="grid3">${field('Condition', `<select name="condition" id="rCond">${m.conditions.map(c => opt(c.id, c.label, r ? r.condition : 'ABOVE')).join('')}</select>`)}
      ${field('Threshold', input('threshold', r ? r.threshold : '', 'type="number" step="any" required id="rTh"'), '')}
      ${field('Must last (seconds)', input('durationSec', r ? r.durationSec : 0, 'type="number" min="0" max="86400" id="rDur"'), '0 = fire at once')}</div>
      <p class="hint small muted" id="rHelp"></p>
      <div class="grid2">${field('Severity', `<select name="severity">${['INFO', 'WARNING', 'CRITICAL'].map(s => opt(s, s, r ? r.severity : 'WARNING')).join('')}</select>`)}
      ${field('Edge action (optional)', input('action', r && r.action, 'maxlength="80" placeholder="e.g. Stop pump"'), 'What the node does by itself when the rule fires')}</div>
      <label class="check"><input type="checkbox" name="enabled" ${!r || r.enabled ? 'checked' : ''}> Rule is on</label>
      <hr><div id="testOut" class="small muted">Use <b>Test</b> to see how often this rule would have fired in the last hour, before saving it.</div>`,
    buttons: [
      ...(r ? [{ label: 'Delete', cls: 'danger', onClick: () => confirmBox(`Delete rule "${r.name}"?`, async () => { await api('DELETE', '/api/rules/' + r.id); closeModal(); toast('Rule deleted'); route(); }, 'Delete') }] : []),
      { label: 'Test', onClick: b => run(b, async () => {
        const res = await api('POST', '/api/rules/test', ruleBody());
        $('#testOut').innerHTML = `<b>Would have fired ${res.fires} time${res.fires === 1 ? '' : 's'}</b> on ${res.sensors} sensor${res.sensors === 1 ? '' : 's'} (${num(res.readings, 0)} readings).
          ${res.note ? esc(res.note) : ''}${res.samples.length ? '<ul>' + res.samples.map(s => `<li>${esc(hm(s.at))} · ${esc(s.message)}</li>`).join('') + '</ul>' : ''}`;
      }) },
      { label: 'Cancel', onClick: closeModal }, { label: 'Save', cls: 'primary', submit: true }],
    onSubmit: async () => { await api(r ? 'PUT' : 'POST', '/api/rules' + (r ? '/' + r.id : ''), ruleBody()); closeModal(); toast('Rule saved'); route(); }
  });
  const box = () => {
    const kind = $('#rTarget').value;
    $('#rTargetBox').innerHTML = kind === 'sensor'
      ? field('Sensor', `<select name="sensorId">${m.sensors.map(s => opt(s.id, s.name + ' (' + ((m.nodes.find(n => n.id === s.nodeId) || {}).name || '') + ')', r && r.sensorId)).join('')}</select>`)
      : `<div class="grid2">${field('Sensor type', `<select name="sensorType"><option value="">Any type</option>${m.sensorTypes.map(t => opt(t.id, t.label, r && !r.sensorId ? r.sensorType : '')).join('')}</select>`)}
         ${field('Only on node', `<select name="nodeId"><option value="">All nodes</option>${m.nodes.map(n => opt(n.id, n.name, r && !r.sensorId ? r.nodeId : '')).join('')}</select>`)}</div>`;
  };
  const help = () => {
    const c = $('#rCond').value;
    $('#rHelp').textContent = {
      ABOVE: 'Fires when the reading is higher than the threshold.',
      BELOW: 'Fires when the reading is lower than the threshold.',
      RATE_OF_CHANGE: 'Threshold = change per minute, compared with the reading about one minute earlier. Catches sudden jumps.',
      ANOMALY: 'Threshold = z-score (1 to 10). z = (value − recent average) ÷ recent standard deviation. 3 is rare, 4.5 is very rare.',
      NO_DATA: 'Threshold = seconds without any reading from the sensor (10 to 86400).'
    }[c] || '';
    $('#rDur').disabled = c === 'NO_DATA';
  };
  $('#rTarget').addEventListener('change', box);
  $('#rCond').addEventListener('change', help);
  box(); help();
}

function ruleBody() {
  const b = formData();
  if ($('#rTarget').value === 'sensor') { b.sensorType = null; b.nodeId = null; } else { b.sensorId = null; }
  if (b.condition === 'NO_DATA') b.durationSec = 0;
  return b;
}

// ================================================================== 07 fault lab

const PRESETS = [
  { title: 'Overheating bearing', text: 'Press line bearing jumps to ~80 °C. Rule fires after 20 s; edge slows the line.', nodeId: 'N-101', sensorId: 'S-102', type: 'SPIKE', magnitude: 22, minutes: 3 },
  { title: 'Internet outage at cold store', text: 'Cloud link drops for 3 min. Watch the buffer fill, then sync.', nodeId: 'N-104', type: 'UPLINK_DOWN', minutes: 3 },
  { title: 'Loose humidity sensor', text: 'Dock humidity sends garbage. The edge drops it and flags the sensor.', nodeId: 'N-105', sensorId: 'S-117', type: 'BAD_DATA', minutes: 2 },
  { title: 'Field gateway power loss', text: 'Solar node goes dark. Offline alert after the heartbeat timeout.', nodeId: 'N-106', type: 'NODE_DOWN', minutes: 2 },
  { title: 'Pump pressure falling', text: 'Outlet pressure drifts down 0.6 bar/min until the standby pump rule fires.', nodeId: 'N-107', sensorId: 'S-125', type: 'DRIFT', magnitude: -0.6, minutes: 5 },
  { title: 'Freezer door left open', text: 'Freezer warms 1.5 °C a minute. Critical once it passes −15 °C for 60 s.', nodeId: 'N-104', sensorId: 'S-112', type: 'DRIFT', magnitude: 1.5, minutes: 6 },
  { title: 'CPU overload', text: 'A runaway process on the RAW-mode Jetson. High-CPU and heat alerts.', nodeId: 'N-103', type: 'CPU_HOG', minutes: 2 },
  { title: 'Stuck vibration sensor', text: 'Pump vibration freezes at one value. Spot the flat line on its page.', nodeId: 'N-107', sensorId: 'S-126', type: 'STUCK', minutes: 3 }
];

async function pageLab() {
  const m = S.meta;
  const ed = canEdit();
  const pick = S.labPick || {};
  S.labPick = null;
  const simNodes = m.nodes.filter(n => n.simulated);
  view().innerHTML = head('Fault lab', 'Break things on purpose and watch the edge react. Faults end by themselves.',
    ed ? `<label class="switch"><input type="checkbox" id="simOn" ${m.settings.simulatorOn ? 'checked' : ''}> Simulator</label>` : '') +
    (ed ? panel('Quick scenarios', `<div class="panel-body"><div class="presets">${PRESETS.map((p, i) => `<button class="preset" type="button" data-preset="${i}"><b>${esc(p.title)}</b><span>${esc(p.text)}</span></button>`).join('')}</div></div>`)
      : '<p class="note">Only edge engineers and admins can inject faults. You can watch them here.</p>') +
    `<div class="row c12">
      ${ed ? panel('Custom fault', `<form class="panel-body" id="faultForm">
        ${field('Edge node', `<select name="nodeId" id="lNode">${simNodes.map(n => opt(n.id, n.name, pick.nodeId)).join('')}</select>`)}
        ${field('Fault', `<select name="type" id="lType">${m.faultTypes.map(f => opt(f.id, f.label, pick.sensorId ? 'SPIKE' : '')).join('')}</select>`)}
        <p class="note small" id="lHelp"></p>
        <div id="lSensorBox">${field('Sensor', '<select name="sensorId" id="lSensor"></select>')}</div>
        <div class="grid2">${field('Minutes', '<input name="minutes" type="number" min="1" max="120" value="3">')}
        <div id="lMagBox">${field('Size', '<input name="magnitude" type="number" step="any" id="lMag">', 'Blank = a sensible default')}</div></div>
        <button class="btn primary wide" type="submit">Inject fault</button></form>`) : ''}
      <section class="panel" id="running"></section>
    </div>
    <div class="row" id="labFeed"></div>`;
  if (ed) {
    $('#simOn').addEventListener('change', e => run(e.target, async () => {
      await api('POST', '/api/simulator', { on: e.target.checked }); m.settings.simulatorOn = e.target.checked;
      toast(e.target.checked ? 'Simulator running' : 'Simulator paused: simulated nodes will go offline'); pollLive();
    }));
    $$('[data-preset]').forEach(b => b.addEventListener('click', () => run(b, async () => {
      const p = PRESETS[Number(b.dataset.preset)];
      const body = { nodeId: p.nodeId, type: p.type, minutes: p.minutes };
      if (p.sensorId) body.sensorId = p.sensorId;
      if (p.magnitude != null) body.magnitude = p.magnitude;
      const f = await api('POST', '/api/faults', body);
      toast(`${f.typeLabel} on ${f.nodeName}${f.sensorName ? ' / ' + f.sensorName : ''} for ${p.minutes} min`, '', 'Fault injected');
      S.refresh();
    })));
    const sensorsFor = () => {
      const nid = $('#lNode').value;
      $('#lSensor').innerHTML = m.sensors.filter(s => s.nodeId === nid).map(s => opt(s.id, s.name + ' (' + s.unit + ')', pick.sensorId)).join('');
    };
    const typeHelp = () => {
      const ft = m.faultTypes.find(f => f.id === $('#lType').value);
      $('#lHelp').textContent = ft ? ft.help : '';
      $('#lSensorBox').classList.toggle('hidden', !ft || !ft.forSensor);
      $('#lMagBox').classList.toggle('hidden', !ft || !['SPIKE', 'DRIFT', 'NOISE'].includes(ft.id));
      const s = m.sensors.find(x => x.id === $('#lSensor').value);
      $('#lMag').placeholder = ft && s ? (ft.id === 'SPIKE' ? 'jump in ' + s.unit : ft.id === 'DRIFT' ? s.unit + ' per minute' : 'times normal noise') : '';
    };
    $('#lNode').addEventListener('change', () => { sensorsFor(); typeHelp(); });
    $('#lType').addEventListener('change', typeHelp);
    $('#lSensor').addEventListener('change', typeHelp);
    sensorsFor(); typeHelp();
    $('#faultForm').addEventListener('submit', e => {
      e.preventDefault();
      run(e.submitter, async () => {
        const fd = new FormData(e.target);
        const ft = m.faultTypes.find(f => f.id === fd.get('type'));
        const body = { nodeId: fd.get('nodeId'), type: fd.get('type'), minutes: Number(fd.get('minutes')) };
        if (ft.forSensor) body.sensorId = fd.get('sensorId');
        if (fd.get('magnitude') !== '' && fd.get('magnitude') != null && !$('#lMagBox').classList.contains('hidden')) body.magnitude = Number(fd.get('magnitude'));
        const f = await api('POST', '/api/faults', body);
        toast(`${f.typeLabel} on ${f.nodeName}${f.sensorName ? ' / ' + f.sensorName : ''}`, '', 'Fault injected');
        S.refresh();
      });
    });
  }
  await live(async () => {
    const [faults, events] = await Promise.all([api('GET', '/api/faults'), api('GET', '/api/events?limit=14&kind=alert')]);
    $('#running').innerHTML = `<div class="panel-head"><h3>Running now</h3><span class="meta">${faults.length} faults</span></div>` +
      (faults.length ? faultTable(faults) : '<div class="empty">No faults running. Everything behaves normally.</div>');
    $('#labFeed').innerHTML = panel('Latest alerts raised', feed(events), 'watch the edge react');
    bindFaultStops();
  });
}

function faultTable(faults) {
  const ed = canEdit();
  return `<div class="table-wrap"><table><thead><tr><th>Fault</th><th>Where</th><th>Size</th><th>Ends in</th><th>By</th>${ed ? '<th></th>' : ''}</tr></thead><tbody>
    ${faults.map(f => `<tr><td><b>${esc(f.typeLabel)}</b><span class="sub mono">${esc(f.id)}</span></td>
      <td><a class="mono" href="#/node/${esc(f.nodeId)}">${esc(f.nodeName)}</a>${f.sensorName ? `<span class="sub"><a href="#/sensor/${esc(f.sensorId)}">${esc(f.sensorName)}</a></span>` : ''}</td>
      <td class="mono small">${f.magnitude ? esc(val(f.magnitude)) + (f.type === 'DRIFT' ? ' ' + esc(f.unit || '') + '/min' : f.type === 'NOISE' ? '×' : ' ' + esc(f.unit || '')) : '-'}</td>
      <td><span class="countdown">${esc(dur((f.endsAt - Date.now()) / 1000))}</span></td><td>${userName(f.by)}</td>
      ${ed ? `<td class="right"><button class="btn small" data-stop="${esc(f.id)}" type="button">Stop</button></td>` : ''}</tr>`).join('')}</tbody></table></div>`;
}

function bindFaultStops() {
  $$('[data-stop]').forEach(b => b.addEventListener('click', () => run(b, async () => {
    await api('DELETE', '/api/faults/' + b.dataset.stop); toast('Fault stopped'); S.refresh && S.refresh();
  })));
}

// ================================================================== 08 reports

async function pageReports() {
  await live(async () => {
    const d = await api('GET', '/api/reports?days=' + S.reportDays);
    const t = d.totals;
    const days = d.perDay;
    const labels = days.map(x => dayLabel(x.date));
    const bw = Math.max(4, Math.min(26, 640 / days.length / 2.6));
    const dataChart = chart({
      label: 'Raw data versus data sent to the cloud per day', w: CW.wide, h: 260, yMin: 0, yFmt: v => bytes(v), xLabels: labels,
      xMin: -0.6, xMax: days.length - 0.4,
      layers: [{ kind: 'bars', bw, bars: days.flatMap((x, i) => [[i, 0, x.rawBytes, 'rust', `${x.date}: raw ${bytes(x.rawBytes)}`, -bw / 2 - 1],
        [i, 0, x.fwdBytes, 'cyan', `${x.date}: sent ${bytes(x.fwdBytes)} (−${fixed(x.reductionPct, 1)}%)`, bw / 2 + 1]]) }]
    });
    const alertChart = chart({
      label: 'Alerts per day by severity', w: CW.third, h: 260, yMin: 0, yFmt: v => num(v, 0), xLabels: labels, xMin: -0.6, xMax: days.length - 0.4, yTicks: 3,
      layers: [{ kind: 'bars', bw: Math.max(5, Math.min(22, 300 / days.length / 1.6)), bars: days.flatMap((x, i) => {
        const c = x.critical, w = x.warning, n = x.info;
        return [[i, 0, c, 'bad', `${x.date}: ${c} critical`], [i, c, c + w, 'warn', `${x.date}: ${w} warning`], [i, c + w, c + w + n, 'info', `${x.date}: ${n} info`]].filter(b => b[2] > b[1]);
      }) }]
    });
    view().innerHTML = head('Reports', `${esc(dayLabel(d.from))} to ${esc(dayLabel(d.to))}. Everything the edge saved, and how fast problems were handled.`,
      `<div class="tabs" id="rDays">${[7, 14].map(n => `<button type="button" data-days="${n}" class="${S.reportDays === n ? 'on' : ''}">${n} days</button>`).join('')}</div>
       <button class="btn" id="printBtn" type="button">Print</button>`) +
      `<div class="readouts">
        ${readout('Raw data at the edge', bytes(t.rawBytes), '', `${num(t.raw, 0)} readings`, '')}
        ${readout('Sent to the cloud', bytes(t.fwdBytes), '', `${num(t.forwarded, 0)} messages`, 'cyan')}
        ${readout('Data cut', fixed(t.reductionPct, 1), '%', `saved ${bytes(t.savedBytes)}`, 'amber')}
        ${readout('Money saved', money(t.savedCost), '', `at ${money(d.costPerGb)} per GB`, 'ok')}
        ${readout('Node uptime', fixed(t.uptimePct, 2), '%', 'all nodes, time online')}
        ${readout('Alerts', num(d.alerts.total, 0), '', `${d.alerts.critical} critical · ${d.alerts.warning} warning`)}
        ${readout('Time to acknowledge', fixed(d.alerts.mttaMin, 1), 'min', 'average (MTTA)')}
        ${readout('Time to resolve', fixed(d.alerts.mttrMin, 1), 'min', 'average (MTTR)')}
      </div>
      <div class="row c21">
        ${panel('Data per day', `<div class="panel-body">${dataChart}${legend([['rust', 'Raw (without edge)'], ['cyan', 'Sent after edge processing']])}</div>`)}
        ${panel('Alerts per day', `<div class="panel-body">${alertChart}${legend([['bad', 'Critical'], ['warn', 'Warning'], ['info', 'Info']])}</div>`)}
      </div>
      ${panel('Per edge node', `<div class="table-wrap"><table><thead><tr><th>Node</th><th>Mode</th><th class="num">Readings</th><th class="num">Invalid</th><th class="num">Raw</th><th class="num">Sent</th>
        <th class="num">Cut</th><th class="num">Saved</th><th class="num">Uptime</th><th class="num">Edge ms</th><th class="num">Alerts</th></tr></thead><tbody>
        ${d.nodes.map(n => `<tr class="click" data-href="#/node/${esc(n.id)}"><td class="mono"><b>${esc(n.name)}</b></td><td>${modeTag(n.mode)}</td><td class="num">${num(n.raw, 0)}</td>
          <td class="num">${num(n.invalid, 0)}</td><td class="num">${bytes(n.rawBytes)}</td><td class="num">${bytes(n.fwdBytes)}</td>
          <td class="num">${fixed(n.reductionPct, 1)}%</td><td class="num">${money(n.savedCost)}</td><td class="num">${fixed(n.uptimePct, 2)}%</td>
          <td class="num">${fixed(n.edgeMs, 2)}</td><td class="num">${n.alerts}</td></tr>`).join('')}</tbody></table></div>`)}
      <div class="row c2">
        ${panel('Rules that fired most', `<div class="panel-body">${hbarList(d.topRules.map(r => [r.name, r.count]))}</div>`)}
        ${panel('Sensors with most alerts', `<div class="panel-body">${hbarList(d.topSensors.map(s => [s.name + ' · ' + s.node, s.count, '#/sensor/' + s.id]))}</div>`)}
      </div>`;
    $$('#rDays button').forEach(b => b.addEventListener('click', () => { S.reportDays = Number(b.dataset.days); S.refresh(); }));
    $('#printBtn').addEventListener('click', () => window.print());
    bindRowLinks(view());
  });
}

function hbarList(rows) {
  if (!rows.length) return '<div class="empty">Nothing in this period.</div>';
  const max = Math.max(...rows.map(r => r[1]), 1);
  return `<div class="hbars">${rows.map(r => `<div class="hbar">${r[2] ? `<a class="name" href="${esc(r[2])}" title="${esc(r[0])}">${esc(r[0])}</a>` : `<span class="name" title="${esc(r[0])}">${esc(r[0])}</span>`}
    <div class="stack"><i class="cyan" data-w="${pct(r[1], max)}"></i></div><span class="r">${num(r[1], 0)}</span></div>`).join('')}</div>`;
}

// ================================================================== 09 settings

async function pageSettings() {
  const s = await api('GET', '/api/settings');
  const admin = isAdmin();
  const F = (name, label, hint, attrs = '') => field(label, `<input name="${name}" type="number" step="any" value="${esc(s[name])}" ${admin ? '' : 'disabled'} ${attrs}>`, hint);
  view().innerHTML = head('Settings', admin ? 'Limits and numbers used by the edge pipeline and the health checks.' : 'Only an admin can change these.') +
    `<form id="setForm"><div class="row c2">
      ${panel('Health checks', `<div class="panel-body">
        ${F('heartbeatTimeoutSec', 'Heartbeat timeout (seconds)', 'A node with no heartbeat for this long is shown as OFFLINE.')}
        <div class="grid3">${F('cpuWarnPct', 'CPU warning (%)', '')}${F('tempWarnC', 'Board temp warning (°C)', '')}${F('bufferWarnPct', 'Buffer warning (%)', '')}</div></div>`)}
      ${panel('Network and cost', `<div class="panel-body">
        <div class="grid2">${F('cloudRttMs', 'Cloud round trip (ms)', 'Used to compare with deciding at the edge.')}${F('localHopMs', 'Sensor to node hop (ms)', 'Local network time.')}</div>
        <div class="grid2">${F('bytesRaw', 'Raw message size (bytes)', 'JSON + MQTT/HTTP headers + TLS.')}${F('bytesSummary', 'Summary message size (bytes)', 'min, max, average, count.')}</div>
        ${F('costPerGb', 'Cost per GB sent to the cloud (₹)', 'Mobile data + cloud ingestion.')}</div>`)}
    </div>
    <div class="row">${panel('Simulator', `<div class="panel-body"><div class="grid2">
      ${F('tickSec', 'Simulator step (seconds)', 'How often simulated nodes send heartbeats and readings.', 'min="1" max="60"')}
      <div class="field"><label>Simulator</label><label class="check"><input type="checkbox" name="simulatorOn" ${s.simulatorOn ? 'checked' : ''} ${admin ? '' : 'disabled'}> Running</label></div></div>
      <p class="small muted">History kept: ${esc(s.historyDays)} days of hourly sensor summaries and daily traffic numbers. Raw readings stay in memory for one hour only.</p></div>`)}</div>
    ${admin ? '<div class="row"><div class="btn-row"><button class="btn primary" type="submit">Save settings</button></div></div>' : ''}</form>`;
  if (admin) $('#setForm').addEventListener('submit', e => {
    e.preventDefault();
    run(e.submitter, async () => {
      const b = {};
      $$('#setForm [name]').forEach(el => { b[el.name] = el.type === 'checkbox' ? el.checked : Number(el.value); });
      await api('PUT', '/api/settings', b);
      await reloadMeta();
      toast('Settings saved');
    });
  });
}

// ================================================================== 10 team

async function pageTeam() {
  if (!isAdmin()) { view().innerHTML = head('Team', '') + '<p class="note">Only admins can manage the team.</p>'; return; }
  const users = await api('GET', '/api/users');
  S.users = users;
  view().innerHTML = head('Team', 'Who can sign in, and what they can do.', '<button class="btn primary" id="addUser" type="button">Add person</button>') +
    panel('People', `<div class="table-wrap"><table><thead><tr><th>Name</th><th>Username</th><th>Role</th><th>Status</th><th>Last sign in</th><th></th></tr></thead><tbody>
      ${users.map(u => `<tr><td><b>${esc(u.displayName)}</b></td><td class="mono">${esc(u.username)}</td><td>${esc(u.roleLabel)}</td>
      <td>${u.active ? tag('active', 'ok') : tag('turned off')}</td><td class="small">${u.lastLogin ? esc(when(u.lastLogin)) : 'never'}</td>
      <td class="right"><button class="btn small" data-user="${esc(u.username)}" type="button">Edit</button></td></tr>`).join('')}</tbody></table></div>`) +
    `<div class="row">${panel('Who can do what', `<div class="table-wrap"><table><thead><tr><th></th><th>Viewer</th><th>Edge engineer</th><th>Admin</th></tr></thead><tbody>
      <tr><td>See everything, acknowledge alerts</td><td>✔</td><td>✔</td><td>✔</td></tr>
      <tr><td>Add / edit nodes, sensors, sites and rules; send commands; make API keys</td><td></td><td>✔</td><td>✔</td></tr>
      <tr><td>Resolve alerts, inject faults, start / pause the simulator</td><td></td><td>✔</td><td>✔</td></tr>
      <tr><td>Delete nodes and sites, change settings, manage the team</td><td></td><td></td><td>✔</td></tr></tbody></table></div>`)}</div>`;
  $('#addUser').addEventListener('click', () => userForm(null));
  $$('[data-user]').forEach(b => b.addEventListener('click', () => userForm(users.find(u => u.username === b.dataset.user))));
}

function userForm(u) {
  openModal({
    title: u ? 'Edit ' + u.displayName : 'Add person',
    body: `${u ? '' : field('Username', input('username', '', 'required maxlength="32" autocomplete="off"'), '3-32 letters or numbers, starting with a letter')}
      ${field('Name', input('displayName', u && u.displayName, 'required maxlength="60"'))}
      ${field('Role', `<select name="role">${S.meta.roles.map(r => opt(r.id, r.label, u ? u.role : 'VIEWER')).join('')}</select>`)}
      ${field(u ? 'New password (leave blank to keep)' : 'Password', '<input name="password" type="password" minlength="8" autocomplete="new-password">', 'At least 8 characters')}
      ${u ? `<label class="check"><input type="checkbox" name="active" ${u.active ? 'checked' : ''}> Account is on</label>` : ''}`,
    buttons: [{ label: 'Cancel', onClick: closeModal }, { label: 'Save', cls: 'primary', submit: true }],
    onSubmit: async () => {
      const b = formData();
      if (u && !b.password) delete b.password;
      await api(u ? 'PUT' : 'POST', '/api/users' + (u ? '/' + encodeURIComponent(u.username) : ''), b);
      closeModal(); toast('Saved'); route();
    }
  });
}

function passwordForm() {
  openModal({
    title: 'Change your password',
    body: field('Current password', '<input name="current" type="password" autocomplete="current-password" required>') +
      field('New password', '<input name="next" type="password" minlength="8" autocomplete="new-password" required>', 'At least 8 characters'),
    buttons: [{ label: 'Cancel', onClick: closeModal }, { label: 'Change password', cls: 'primary', submit: true }],
    onSubmit: async () => { await api('POST', '/api/auth/password', formData()); closeModal(); toast('Password changed'); }
  });
}

// ================================================================== start

function debounce(fn, ms) {
  let t;
  return (...a) => { clearTimeout(t); t = setTimeout(() => fn(...a), ms); };
}

document.addEventListener('DOMContentLoaded', async () => {
  paintThemeButtons();
  $$('[data-theme-toggle]').forEach(b => b.addEventListener('click', toggleTheme));
  if (window.matchMedia) window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', paintThemeButtons);
  $('#loginForm').addEventListener('submit', signIn);
  $$('[data-demo]').forEach(b => b.addEventListener('click', () => {
    const [u, p] = b.dataset.demo.split(':');
    $('#lu').value = u; $('#lp').value = p; $('#lp').focus();
  }));
  $('#logoutBtn').addEventListener('click', () => signOut(false));
  $('#who').addEventListener('click', passwordForm);
  $('#menuBtn').addEventListener('click', () => { $('#rail').classList.add('open'); $('#railShade').classList.remove('hidden'); });
  $('#railShade').addEventListener('click', () => { $('#rail').classList.remove('open'); $('#railShade').classList.add('hidden'); });
  $('#modalClose').addEventListener('click', closeModal);
  $('#modalForm').addEventListener('submit', async e => {
    e.preventDefault();
    if (!modalSubmit) return;
    const btn = e.submitter;
    if (btn) btn.disabled = true;
    try { await modalSubmit(); } catch (err) { if (!(err instanceof StaleError)) modalError(err.message); } finally { if (btn) btn.disabled = false; }
  });
  $('#modal').addEventListener('close', () => { modalSubmit = null; });
  window.addEventListener('hashchange', route);
  document.addEventListener('visibilitychange', () => { if (!document.hidden && S.token) pollLive(); });
  tickClock(); setInterval(tickClock, 1000);

  const saved = store.get('edge-token');
  if (saved) {
    S.token = saved;
    try {
      S.user = await api('GET', '/api/auth/me');
      await enterApp();
      return;
    } catch { S.token = null; store.set('edge-token', null); }
  }
  showLogin();
});
