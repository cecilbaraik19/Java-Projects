/* Smart Energy Saver - frontend.
   Plain JavaScript, no frameworks. Every piece of data is escaped with esc() before it goes into HTML,
   and no inline styles or scripts are used (the server's Content-Security-Policy blocks them). */
'use strict';

const S = {
  token: null, user: null, meta: null, users: [], settings: null,
  liveRoom: '', applFilter: { room: '', type: '', q: '', sort: 'kwh' },
  usageFilter: { appliance: '', room: '', from: '', to: '', source: '' }, usageTab: 'records',
  tipEffort: '', reportMonths: 6, plan: [], timer: null, routeSeq: 0
};
const $ = (sel, root = document) => root.querySelector(sel);
const $$ = (sel, root = document) => Array.from(root.querySelectorAll(sel));
const view = () => $('#view');
const canManage = () => S.user && (S.user.role === 'ADMIN' || S.user.role === 'MANAGER');

// ------------------------------------------------------------------ helpers
const store = {
  get(k) { try { return sessionStorage.getItem(k); } catch { return null; } },
  set(k, v) { try { v == null ? sessionStorage.removeItem(k) : sessionStorage.setItem(k, v); } catch { /* ignore */ } },
  getLocal(k) { try { return localStorage.getItem(k); } catch { return null; } },
  setLocal(k, v) { try { localStorage.setItem(k, v); } catch { /* ignore */ } }
};

function esc(v) {
  return String(v ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}
const typeLabel = t => esc((S.meta.applianceTypes.find(x => x.id === t) || {}).label || t);
const roomName = id => esc((S.meta.rooms.find(r => r.id === id) || {}).name || '-');
const userName = u => { const x = S.users.find(a => a.username === u); return esc(x ? x.displayName : (u || '')); };

/** ₹1,23,456 */
function money(n) {
  if (n == null || isNaN(n)) return '-';
  return '₹' + Math.round(Number(n)).toLocaleString('en-IN');
}
function money2(n) {
  if (n == null || isNaN(n)) return '-';
  return '₹' + Number(n).toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}
/** 12.3 (one decimal, Indian grouping) */
function num(n, d = 1) {
  if (n == null || isNaN(n)) return '-';
  return Number(n).toLocaleString('en-IN', { maximumFractionDigits: d });
}
const kwh = (n, d = 1) => num(n, d) + ' kWh';
const watts = n => n >= 1000 ? num(n / 1000, 2) + ' kW' : num(n, 0) + ' W';
const pct = (a, b) => b > 0 ? Math.round(a / b * 100) : 0;
const hourText = h => String(((h % 24) + 24) % 24).padStart(2, '0') + ':00';

function dateOnly(iso) {
  if (!iso) return '-';
  const d = new Date(iso.length === 10 ? iso + 'T00:00:00' : iso);
  return d.toLocaleDateString('en-IN', { day: 'numeric', month: 'short', year: 'numeric' });
}
function when(iso) {
  if (!iso) return '';
  return new Date(iso).toLocaleString('en-IN', { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' });
}
function todayStr(offset = 0) {
  const d = new Date(); d.setDate(d.getDate() + offset);
  return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0');
}
function minutesText(m) {
  m = Math.max(0, Math.round(m));
  if (m < 60) return m + ' min';
  return Math.floor(m / 60) + ' h ' + (m % 60) + ' min';
}
function stars(n) {
  if (!n) return '<span class="muted small">Not rated</span>';
  return `<span class="stars" title="${n} star BEE rating" aria-label="${n} star">${'★'.repeat(n)}<span class="off">${'★'.repeat(5 - n)}</span></span>`;
}
function greet() {
  const h = new Date().getHours();
  return h < 12 ? 'Good morning' : h < 17 ? 'Good afternoon' : 'Good evening';
}

function toast(msg, err = false) {
  const t = document.createElement('div');
  t.className = 'toast' + (err ? ' err' : '');
  t.setAttribute('role', 'status');
  t.textContent = msg;
  document.body.appendChild(t);
  setTimeout(() => t.remove(), err ? 4500 : 2600);
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
  try { return await fn(); } catch (e) { if (!(e instanceof StaleError)) toast(e.message, true); } finally { if (btn) btn.disabled = false; }
}

function options(list, selected, blank) {
  let h = blank != null ? `<option value="">${esc(blank)}</option>` : '';
  for (const o of list) {
    const id = typeof o === 'object' ? o.id : o;
    const lb = typeof o === 'object' ? (o.label ?? o.name) : o;
    h += `<option value="${esc(id)}" ${String(id) === String(selected ?? '') ? 'selected' : ''}>${esc(lb)}</option>`;
  }
  return h;
}
const hourOptions = sel => options(Array.from({ length: 24 }, (_, h) => ({ id: h, label: hourText(h) })), sel);
const roomOptions = (sel, blank) => options(S.meta.rooms.map(r => ({ id: r.id, label: r.name })), sel, blank);
const typeOptions = (sel, blank) => options(S.meta.applianceTypes, sel, blank);

/** Sets bar sizes from data-w (width %), data-h (height %) and data-b (bottom %) after HTML is placed. */
function paintBars(root = document) {
  requestAnimationFrame(() => {
    $$('[data-w]', root).forEach(e => { e.style.width = clamp(e.dataset.w) + '%'; });
    $$('[data-h]', root).forEach(e => { e.style.height = clamp(e.dataset.h) + '%'; });
    $$('[data-b]', root).forEach(e => { e.style.bottom = clamp(e.dataset.b) + '%'; });
  });
}
const clamp = v => Math.max(0, Math.min(100, Number(v) || 0));

/** A plain progress bar. */
function bar(value, max, cls = '') {
  return `<div class="bar ${cls}" role="img" aria-label="${pct(value, max)}%"><span data-w="${max > 0 ? value / max * 100 : 0}"></span></div>`;
}

/** Horizontal bar list: items [{label, value, text}] */
function hbars(items, cls = '') {
  if (!items.length) return '<div class="empty">No data yet.</div>';
  const max = Math.max(0.0001, ...items.map(i => i.value));
  return '<div class="hbars">' + items.map(i => `
    <div class="hbar">
      <div class="top"><span>${i.link ? `<a href="${esc(i.link)}">${esc(i.label)}</a>` : esc(i.label)}</span><span class="num muted">${esc(i.text)}</span></div>
      ${bar(i.value, max, cls)}
    </div>`).join('') + '</div>';
}

/**
 * Vertical bar chart. cols: [{label, parts:[{v, cls}], title, cls, forecast}]
 * opts: {max, target, targetText, showVals, valText, wide, labelEvery}
 */
function vchart(cols, opts = {}) {
  const totals = cols.map(c => c.parts.reduce((a, p) => a + p.v, 0));
  const max = opts.max || Math.max(0.0001, ...totals, ...cols.map(c => c.forecast || 0), opts.target || 0) * 1.08;
  const every = opts.labelEvery || 1;
  const body = cols.map((c, i) => {
    const t = totals[i];
    const parts = c.parts.map(p => `<span class="${esc(p.cls)}" data-h="${t > 0 ? p.v / t * 100 : 0}"></span>`).join('');
    return `<div class="col ${esc(c.cls || '')}" title="${esc(c.title || '')}">
      ${opts.showVals ? `<div class="val">${esc(opts.valText ? opts.valText(c, t) : num(t))}</div>` : ''}
      ${c.forecast ? `<div class="forecast-cap" data-h="${c.forecast / max * 100}"></div>` : ''}
      <div class="stack-bars" data-h="${t / max * 100}">${parts}</div>
    </div>`;
  }).join('');
  const target = opts.target ? `<div class="target-line" data-b="${opts.target / max * 100}"><span>${esc(opts.targetText || '')}</span></div>` : '';
  const labels = cols.map((c, i) => `<span class="${i % every === 0 ? '' : 'blank'}">${esc(c.label)}</span>`).join('');
  return `<div class="vchart ${opts.wide ? 'wide-gap' : ''} ${opts.cls || ''}" role="img" aria-label="${esc(opts.aria || 'Bar chart')}">${body}${target}</div>
    <div class="vlabels ${opts.wide ? 'wide-gap' : ''}">${labels}</div>`;
}

const scoreRing = (sc, small) => `<div class="score-ring ${esc(sc.label)} ${small ? 'small' : ''}" role="img" aria-label="Energy score ${esc(sc.score)} of 100">${esc(sc.score)}</div>`;
const scoreWord = l => ({ EXCELLENT: 'Excellent', GOOD: 'Good', FAIR: 'Fair', POOR: 'Needs work' }[l] || l);

/** Downloads a file from an API address that needs the sign-in token. */
async function downloadFile(path, fallbackName) {
  const res = await fetch(path, { headers: { Authorization: 'Bearer ' + S.token } });
  if (!res.ok) throw new Error('Download failed (' + res.status + ')');
  const blob = await res.blob();
  const cd = res.headers.get('content-disposition') || '';
  const m = /filename="([^"]+)"/.exec(cd);
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = m ? m[1] : fallbackName;
  document.body.appendChild(a); a.click(); a.remove();
  setTimeout(() => URL.revokeObjectURL(a.href), 2000);
}

// ------------------------------------------------------------------ modal
const modal = {
  el: $('#modal'),
  submit: null,
  open({ title, body, submitText = 'Save', danger = false, onSubmit, onOpen, wide = false }) {
    $('#modalTitle').textContent = title;
    $('#modalBody').innerHTML = body;
    $('#modalErr').textContent = '';
    $('#modalFoot').innerHTML = `<button type="button" id="modalCancel">${onSubmit ? 'Cancel' : 'Close'}</button>` +
      (onSubmit ? `<button type="submit" class="${danger ? 'danger' : 'primary'}" id="modalOk">${esc(submitText)}</button>` : '');
    $('#modalCancel').addEventListener('click', () => modal.close());
    this.submit = onSubmit;
    this.el.classList.toggle('wide', wide);
    if (!this.el.open) this.el.showModal();
    if (onOpen) onOpen($('#modalBody'));
    const first = $('#modalBody input:not([type=hidden]):not([disabled]), #modalBody select, #modalBody textarea');
    if (first) first.focus();
  },
  close() { if (this.el.open) this.el.close(); this.submit = null; }
};
$('#modalClose').addEventListener('click', () => modal.close());
$('#modalForm').addEventListener('submit', async e => {
  e.preventDefault();
  if (!modal.submit) { modal.close(); return; }
  const btn = $('#modalOk');
  btn.disabled = true;
  $('#modalErr').textContent = '';
  try {
    const data = Object.fromEntries(new FormData($('#modalForm')).entries());
    $$('#modalForm input[type=checkbox]').forEach(c => { data[c.name] = c.checked; });
    await modal.submit(data);
    modal.close();
  } catch (err) {
    $('#modalErr').textContent = err.message;
  } finally { btn.disabled = false; }
});

function confirmBox(title, text, okText, fn) {
  modal.open({ title, body: `<p>${esc(text)}</p>`, submitText: okText, danger: true, onSubmit: fn });
}

// ------------------------------------------------------------------ light / dark mode
function isDark() {
  const t = document.documentElement.getAttribute('data-theme');
  return t ? t === 'dark' : matchMedia('(prefers-color-scheme: dark)').matches;
}
function showThemeButtons() {
  const dark = isDark();
  $$('[data-theme-toggle]').forEach(b => {
    b.textContent = dark ? 'Light mode' : 'Dark mode';
    b.setAttribute('aria-pressed', String(dark));
    b.title = dark ? 'Switch to light mode' : 'Switch to dark mode';
  });
}
$$('[data-theme-toggle]').forEach(b => b.addEventListener('click', () => {
  const next = isDark() ? 'light' : 'dark';
  document.documentElement.setAttribute('data-theme', next);
  store.setLocal('theme', next);
  showThemeButtons();
}));
// follow the computer's setting while the person has not chosen one
matchMedia('(prefers-color-scheme: dark)').addEventListener('change', showThemeButtons);
showThemeButtons();

// ------------------------------------------------------------------ auth
$('#loginForm').addEventListener('submit', async e => {
  e.preventDefault();
  $('#loginErr').textContent = '';
  const btn = e.submitter || $('#loginForm button[type=submit]');
  btn.disabled = true;
  try {
    const r = await api('POST', '/api/auth/login', { username: $('#lu').value.trim(), password: $('#lp').value });
    S.token = r.token; S.user = r.user; store.set('token', r.token);
    $('#lp').value = '';
    await boot();
  } catch (err) { $('#loginErr').textContent = err.message; } finally { btn.disabled = false; }
});
$$('[data-demo]').forEach(b => b.addEventListener('click', () => {
  const [u, p] = b.dataset.demo.split(':');
  $('#lu').value = u; $('#lp').value = p;
  $('#loginForm').requestSubmit();
}));
$('#logoutBtn').addEventListener('click', async () => {
  try { await api('POST', '/api/auth/logout', {}); } catch { /* ignore */ }
  signOut();
});

function signOut(expired) {
  S.token = null; S.user = null; store.set('token', null);
  clearTimer();
  modal.close();
  $('#app').classList.add('hidden'); $('#login').classList.remove('hidden');
  $('#loginErr').textContent = expired ? 'Your session ended. Please sign in again.' : '';
}

async function loadMeta() {
  [S.meta, S.users, S.settings] = await Promise.all([api('GET', '/api/meta'), api('GET', '/api/users'), api('GET', '/api/settings')]);
  $('#homeName').textContent = S.settings.homeName;
}

async function boot() {
  S.token = S.token || store.get('token');
  if (!S.token) { signOut(); return; }
  try {
    S.user = await api('GET', '/api/auth/me');
    await loadMeta();
  } catch { signOut(); return; }
  $('#login').classList.add('hidden'); $('#app').classList.remove('hidden');
  const roleText = { ADMIN: 'Admin', MANAGER: 'Energy manager', MEMBER: 'Member' }[S.user.role] || S.user.role;
  $('#who').textContent = S.user.displayName + ' · ' + roleText;
  $('#navTeam').classList.toggle('hidden', S.user.role !== 'ADMIN');
  if (!location.hash || location.hash === '#/') location.hash = '#/dashboard';
  route();
  refreshBadges();
}

/** Small numbers in the menu: appliances on now, open tips. Also the peak / off-peak chip. */
async function refreshBadges() {
  try {
    const [list, tips] = await Promise.all([api('GET', '/api/appliances'), api('GET', '/api/tips')]);
    const on = list.filter(a => a.on).length;
    $('#navOn').textContent = on; $('#navOn').classList.toggle('hidden', on === 0); $('#navOn').title = on + ' on now';
    $('#navTips').textContent = tips.open.length; $('#navTips').classList.toggle('hidden', tips.open.length === 0);
  } catch { /* ignore */ }
  rateChip();
}

/** Shows whether power is costly right now (peak), cheap (off-peak) or normal. */
function rateChip() {
  const s = S.settings; if (!s) return;
  const h = new Date().getHours();
  const inR = (x, a, b) => a === b ? false : a < b ? x >= a && x < b : x >= a || x < b;
  const c = $('#rateChip');
  c.classList.remove('hidden', 'peak', 'offpeak', 'normal');
  if (inR(h, s.peakStart, s.peakEnd) && s.peakSurchargePercent > 0) {
    c.classList.add('peak'); c.textContent = 'Peak hours: +' + num(s.peakSurchargePercent, 0) + '% till ' + hourText(s.peakEnd);
  } else if (inR(h, s.offPeakStart, s.offPeakEnd) && s.offPeakRebatePercent > 0) {
    c.classList.add('offpeak'); c.textContent = 'Off-peak: -' + num(s.offPeakRebatePercent, 0) + '% till ' + hourText(s.offPeakEnd);
  } else {
    c.classList.add('normal'); c.textContent = 'Normal rate';
  }
}

$('#who').addEventListener('click', () => {
  modal.open({
    title: 'Change your password',
    body: `<div class="field"><label for="pc">Current password</label><input id="pc" name="current" type="password" autocomplete="current-password" required></div>
      <div class="field"><label for="pn">New password</label><input id="pn" name="next" type="password" autocomplete="new-password" minlength="8" required>
      <div class="hint">At least 8 characters. You stay signed in here; other devices are signed out.</div></div>`,
    submitText: 'Change password',
    onSubmit: async d => { await api('POST', '/api/auth/password', d); toast('Password changed'); }
  });
});

$('#leaveBtn').addEventListener('click', () => {
  confirmBox('Leaving home?', 'Switch off every appliance that is on, except the fridge and Wi-Fi router?', 'Switch all off', async () => {
    const r = await api('POST', '/api/appliances/all-off', { keepAlwaysOn: true });
    toast(r.switchedOff ? r.switchedOff + ' appliance(s) switched off' : 'Nothing was on');
    refresh();
  });
});

// ------------------------------------------------------------------ router
window.addEventListener('hashchange', route);
function closeMenu() { $('#side').classList.remove('open'); $('#sideShade').classList.add('hidden'); }
$('#menuBtn').addEventListener('click', () => {
  const open = !$('#side').classList.contains('open');
  $('#side').classList.toggle('open', open); $('#sideShade').classList.toggle('hidden', !open);
});
$('#sideShade').addEventListener('click', closeMenu);
$('#searchForm').addEventListener('submit', e => {
  e.preventDefault();
  S.applFilter = { ...S.applFilter, q: $('#globalSearch').value.trim(), room: '', type: '' };
  if (location.hash === '#/appliances') route(); else location.hash = '#/appliances';
});

function clearTimer() { if (S.timer) { clearInterval(S.timer); S.timer = null; } }
/** Re-draws the current page every few seconds (only while that page is open and the tab is visible). */
function autoRefresh(fn, ms) {
  clearTimer();
  S.timer = setInterval(() => { if (!document.hidden && !$('#modal').open) fn().catch(() => { /* ignore */ }); }, ms);
}

async function route() {
  if (!S.user) return;
  S.routeSeq++;
  closeMenu();
  clearTimer();
  const parts = location.hash.replace(/^#\/?/, '').split('/').map(decodeURIComponent);
  const page = parts[0] || 'dashboard';
  $$('#nav a').forEach(a => a.classList.toggle('active', a.dataset.nav === page));
  const pages = {
    dashboard: pageDashboard, live: pageLive,
    appliances: parts[1] ? () => pageAppliance(parts[1]) : pageAppliances,
    usage: pageUsage, tips: pageTips, planner: () => pagePlanner(parts[1]), reports: pageReports,
    settings: pageSettings, team: pageTeam
  };
  const fn = pages[page];
  view().innerHTML = '<div class="empty">Loading...</div>';
  if (!fn) { view().innerHTML = '<div class="empty">Page not found. <a href="#/dashboard">Go to the dashboard</a></div>'; return; }
  try { await fn(); } catch (e) { if (e instanceof StaleError) return; view().innerHTML = `<div class="card card-pad"><p class="bad-text">${esc(e.message)}</p><a href="#/dashboard">Back to dashboard</a></div>`; }
  view().focus({ preventScroll: true });
}

/** Re-draws the current page (after a change). */
const refresh = () => { route(); refreshBadges(); };

// ------------------------------------------------------------------ shared bits
function alertsHtml(list) {
  if (!list.length) return '';
  return '<div class="alerts" aria-label="Alerts">' + list.map(a => `
    <div class="alert ${esc(a.level)}" role="${a.level === 'bad' ? 'alert' : 'status'}">
      <span class="dot" aria-hidden="true"></span>
      <div class="grow"><b>${esc(a.title)}</b><span class="small">${esc(a.text)}</span></div>
      ${a.applianceId ? `<a class="btn small" href="#/appliances/${esc(a.applianceId)}">Open</a>` : ''}
      ${a.kind === 'BUDGET' || a.kind === 'TARGET' ? '<a class="btn small" href="#/tips">See tips</a>' : ''}
      ${a.kind === 'READ_METER' ? '<a class="btn small" href="#/usage" data-meter-tab>Add reading</a>' : ''}
    </div>`).join('') + '</div>';
}

function tipCard(t, compact) {
  const pay = t.paybackMonths == null ? 'Does not pay back' : t.paybackMonths === 0 ? 'Free' :
    t.paybackMonths < 12 ? 'Pays back in ' + Math.max(1, Math.round(t.paybackMonths)) + ' month' + (Math.round(t.paybackMonths) > 1 ? 's' : '') :
    'Pays back in ' + num(t.paybackMonths / 12, 1) + (Math.round(t.paybackMonths / 12 * 10) === 10 ? ' year' : ' years');
  return `<article class="tip ${compact ? 'compact' : ''}">
    <div>
      <h3>${esc(t.title)}</h3>
      <div class="t-meta"><span class="badge e-${esc(t.effort)}">${esc(t.effortLabel)}</span>
        <a href="#/appliances/${esc(t.applianceId)}">${esc(t.applianceName)}</a><span>· ${esc(t.roomName)}</span></div>
      ${compact ? '' : `<p class="why">${esc(t.why)}</p><p class="do"><b>What to do:</b> ${esc(t.action)}</p>`}
    </div>
    <div class="nums">
      <span class="big">${money(t.rupeesPerMonth)}<small class="muted small">/month</small></span>
      <span class="s">${t.kwhPerMonth > 0 ? kwh(t.kwhPerMonth) + ' less · ' + num(t.co2PerMonth) + ' kg CO₂' : 'Same units, cheaper time'}</span>
      ${compact ? '' : `<span class="s">${t.cost > 0 ? 'Cost ' + money(t.cost) + ' · ' : ''}${esc(pay)}</span>`}
    </div>
    ${compact ? '' : `<div class="actions">
      ${canManage() ? `<button class="primary small" data-apply="${esc(t.key)}">${t.changesSettings ? 'Apply' : 'Mark as done'}</button>
        <button class="small" data-dismiss="${esc(t.key)}">Not now</button>` : '<span class="small muted">Ask the energy manager to apply this tip.</span>'}
      <a class="btn small ghost" href="#/planner/${esc(t.applianceId)}">Try in planner</a>
    </div>`}
  </article>`;
}

function wireTips(root) {
  $$('[data-apply]', root).forEach(b => b.addEventListener('click', () => run(b, async () => {
    const r = await api('POST', `/api/tips/${encodeURIComponent(b.dataset.apply)}/apply`, {});
    toast('Done: ' + r.change + '. Saves about ' + money(r.rupeesPerMonth) + '/month');
    refresh();
  })));
  $$('[data-dismiss]', root).forEach(b => b.addEventListener('click', () => run(b, async () => {
    await api('POST', `/api/tips/${encodeURIComponent(b.dataset.dismiss)}/dismiss`, {});
    toast('Tip hidden. You can bring it back from "Done and hidden".');
    refresh();
  })));
}

function powerSwitch(a) {
  return `<button class="switch" role="switch" aria-checked="${a.on}" data-power="${esc(a.id)}" aria-label="${esc(a.name)} ${a.on ? 'on' : 'off'}" title="${a.on ? 'Switch off' : 'Switch on'}"></button>`;
}
function wirePower(root) {
  $$('[data-power]', root).forEach(b => b.addEventListener('click', () => run(b, async () => {
    const on = b.getAttribute('aria-checked') !== 'true';
    const a = await api('POST', `/api/appliances/${b.dataset.power}/power`, { on });
    toast(a.name + (on ? ' switched on' : ' switched off'));
    refresh();
  })));
}

function onState(a) {
  if (!a.on) return a.standbyWatts > 0 ? `Off · standby ${num(a.standbyWatts)} W` : 'Off';
  let t = 'On for ' + minutesText(a.onMinutes || 0);
  if (a.autoOffIn != null) t += ' · auto-off in ' + minutesText(a.autoOffIn);
  return t;
}

function eventsHtml(list) {
  if (!list.length) return '<div class="empty">Nothing yet.</div>';
  return '<ul class="timeline">' + list.map(e => `<li class="k-${esc(e.kind)}">${esc(e.text)}<div class="when">${esc(when(e.at))}</div></li>`).join('') + '</ul>';
}

// ------------------------------------------------------------------ dashboard
async function pageDashboard() {
  const d = await api('GET', '/api/dashboard');
  const k = d.kpis;
  const monthPct = pct(k.forecastKwh, k.targetKwh), billPct = pct(k.forecastBill, k.budget);
  const ys = k.yesterdayKwh > 0 ? Math.round((k.todayKwh - k.yesterdayKwh) / k.yesterdayKwh * 100) : 0;
  const dailyCols = d.daily.map(x => ({
    label: x.label.split(' ')[0], cls: x.weekend ? 'dim' : '',
    title: `${x.label}: ${num(x.kwh)} kWh (peak ${num(x.peakKwh)}, standby ${num(x.standbyKwh)})`,
    parts: [{ v: x.standbyKwh, cls: 'c-standby' }, { v: Math.max(0, x.kwh - x.peakKwh - x.standbyKwh), cls: 'c-normal' }, { v: x.peakKwh, cls: 'c-peak' }]
  }));
  const sp = d.split;
  view().innerHTML = `
    <div class="page-head">
      <div><h1>${greet()}, ${esc(S.user.displayName.split(' ')[0])}</h1>
        <p>${esc(d.homeName)} · day ${k.dayOfMonth} of ${k.daysInMonth}</p></div>
      <div class="spacer"></div>
      <a class="btn" href="#/live">Live control</a>
      <a class="btn primary" href="#/tips">Savings tips (${d.tipCount})</a>
    </div>
    ${alertsHtml(d.alerts)}
    <section class="kpis" aria-label="Key numbers">
      <div class="card kpi"><div class="k-label">Today so far</div>
        <div class="k-value">${num(k.todayKwh)}<small>kWh</small></div>
        <div class="k-sub">Yesterday: ${num(k.yesterdayKwh)} kWh</div></div>
      <div class="card kpi"><div class="k-label">This month</div>
        <div class="k-value">${num(k.monthKwh, 0)}<small>kWh</small></div>
        <div class="k-sub">Expected ${num(k.forecastKwh, 0)} of ${num(k.targetKwh, 0)} target</div>
        ${bar(k.forecastKwh, Math.max(k.forecastKwh, k.targetKwh), monthPct > 100 ? 'warn' : '')}</div>
      <div class="card kpi"><div class="k-label">Expected bill</div>
        <div class="k-value ${billPct > 100 ? 'bad-text' : ''}">${money(k.forecastBill)}</div>
        <div class="k-sub">Budget ${money(k.budget)} (${billPct}%)</div>
        ${bar(k.forecastBill, Math.max(k.forecastBill, k.budget), billPct > 100 ? 'bad' : billPct > 90 ? 'warn' : '')}</div>
      <div class="card kpi energy"><div class="k-label">Using now</div>
        <div class="k-value">${watts(k.loadWatts)}</div>
        <div class="k-sub">${money2(k.costPerHourNow)} per hour · ${k.peakNow ? '<span class="peak-text">peak rate</span>' : k.offPeakNow ? '<span class="offpeak-text">off-peak rate</span>' : 'normal rate'}</div></div>
      <div class="card kpi"><div class="k-label">CO₂ this month</div>
        <div class="k-value">${num(k.co2Month, 0)}<small>kg</small></div>
        <div class="k-sub">≈ ${num(k.co2Month * 12 / 21, 0)} trees to absorb a year of this</div></div>
    </section>

    <div class="split">
      <section class="card">
        <div class="card-head"><h2>Daily use, last 30 days</h2><span class="sub">kWh per day</span></div>
        <div class="card-body">
          ${vchart(dailyCols, { target: d.dailyTarget, targetText: 'target ' + num(d.dailyTarget) + '/day', labelEvery: 3, aria: 'Daily units for the last 30 days' })}
          <div class="legend"><span><i class="c-peak"></i>Peak hours</span><span><i class="c-normal"></i>Other hours</span>
            <span><i class="c-standby"></i>Standby</span><span><i class="l-target"></i>Daily target</span><span>Lighter bars = weekend</span></div>
        </div>
      </section>
      <section class="card">
        <div class="card-head"><h2>Energy score</h2><span class="spacer"></span><a class="small" href="#/reports">Reports</a></div>
        <div class="card-body">
          <div class="score-wrap">${scoreRing(d.score)}<div><b>${esc(scoreWord(d.score.label))}</b>
            <p class="small muted">Out of 100. Applying savings tips raises it.</p></div></div>
          <ul class="factors">${d.score.factors.map(f => `<li><span>${esc(f.name)}</span><span class="pts">${f.points} / ${f.max}</span><span class="why">${esc(f.why)}</span></li>`).join('')}</ul>
        </div>
      </section>
    </div>

    <div class="cards cols-3">
      <section class="card">
        <div class="card-head"><h2>Where the power goes</h2><span class="sub">last 30 days</span></div>
        <div class="card-body">${hbars(d.byRoom.filter(r => r.kwh > 0).map(r => ({ label: r.name, value: r.kwh, text: kwh(r.kwh, 0) + ' · ' + r.share + '%' })))}</div>
      </section>
      <section class="card">
        <div class="card-head"><h2>When it is used</h2><span class="sub">time-of-day rates</span></div>
        <div class="card-body">
          <div class="stackbar" role="img" aria-label="Peak ${pct(sp.peak, sp.total)}%, normal ${pct(sp.normal, sp.total)}%, off-peak ${pct(sp.offPeak, sp.total)}%, standby ${pct(sp.standby, sp.total)}%">
            <span class="c-peak" data-w="${pct(sp.peak, sp.total)}"></span><span class="c-normal" data-w="${pct(sp.normal, sp.total)}"></span>
            <span class="c-offpeak" data-w="${pct(sp.offPeak, sp.total)}"></span><span class="c-standby" data-w="${pct(sp.standby, sp.total)}"></span></div>
          <div class="legend"><span><i class="c-peak"></i>Peak ${pct(sp.peak, sp.total)}% (${hourText(d.peakStart)}-${hourText(d.peakEnd)}, costs more)</span>
            <span><i class="c-normal"></i>Normal ${pct(sp.normal, sp.total)}%</span><span><i class="c-offpeak"></i>Off-peak ${pct(sp.offPeak, sp.total)}% (cheaper)</span>
            <span><i class="c-standby"></i>Standby ${pct(sp.standby, sp.total)}% (wasted)</span></div>
          <p class="small muted">Moving washing, pumping and charging to night hours lowers the bill without using less.</p>
        </div>
      </section>
      <section class="card">
        <div class="card-head"><h2>Biggest users</h2><span class="spacer"></span><a class="small" href="#/appliances">All</a></div>
        <div class="card-body">${hbars(d.topAppliances.map(a => ({ label: a.name, link: '#/appliances/' + a.id, value: a.kwh, text: money(a.cost) + '/mo' })), 'energy')}</div>
      </section>
    </div>

    <div class="cards cols-3">
      <section class="card">
        <div class="card-head"><h2>Top savings tips</h2><span class="spacer"></span><a class="small" href="#/tips">All ${d.tipCount}</a></div>
        <div>${d.tips.length ? d.tips.map(t => tipCard(t, true)).join('') : '<div class="empty">No open tips. Well done!</div>'}</div>
        <div class="card-body small muted">All tips together could save <b class="good-text">${money(d.potentialRupees)}/month</b> (${kwh(d.potentialKwh, 0)}).</div>
      </section>
      <section class="card">
        <div class="card-head"><h2>On right now</h2><span class="sub">${d.onNow.length}</span><span class="spacer"></span><a class="small" href="#/live">Control</a></div>
        ${d.onNow.length ? '<ul class="list">' + d.onNow.map(a => `<li>
          <div class="grow"><a class="title" href="#/appliances/${esc(a.id)}">${esc(a.name)}</a>
          <div class="meta">${esc(a.roomName)} · ${watts(a.watts)} · ${esc(onState(a))}</div></div>${powerSwitch(a)}</li>`).join('') + '</ul>'
        : '<div class="empty">Everything is off.</div>'}
      </section>
      <section class="card">
        <div class="card-head"><h2>Recent activity</h2></div>
        ${eventsHtml(d.events)}
      </section>
    </div>`;
  paintBars(view());
  wirePower(view());
  wireMeterLinks();
  autoRefresh(async () => { if (location.hash.startsWith('#/dashboard')) { const y = window.scrollY; await pageDashboard(); window.scrollTo(0, y); } }, 60000);
}

function wireMeterLinks() {
  $$('[data-meter-tab]').forEach(a => a.addEventListener('click', () => { S.usageTab = 'meter'; }));
}

// ------------------------------------------------------------------ live control
async function pageLive() {
  const [list, d] = await Promise.all([api('GET', '/api/appliances'), api('GET', '/api/dashboard')]);
  const k = d.kpis;
  const rooms = S.meta.rooms.filter(r => !S.liveRoom || r.id === S.liveRoom);
  const onCount = list.filter(a => a.on).length;
  view().innerHTML = `
    <div class="page-head">
      <div><h1>Live control</h1><p>Switch appliances on and off. The app measures how long they run, like a smart plug.</p></div>
      <div class="spacer"></div>
      <button class="energy" id="allOff" type="button">Switch all off</button>
    </div>
    <section class="live-head">
      <div class="card kpi energy"><div class="k-label">Using now</div><div class="k-value">${watts(k.loadWatts)}</div>
        <div class="k-sub">${watts(k.runningWatts)} running + ${watts(k.standbyWatts)} standby</div></div>
      <div class="card kpi"><div class="k-label">Cost per hour</div><div class="k-value">${money2(k.costPerHourNow)}</div>
        <div class="k-sub">${money2(k.rateNow)} per unit now ${k.peakNow ? '<span class="peak-text">(peak)</span>' : k.offPeakNow ? '<span class="offpeak-text">(off-peak)</span>' : ''}</div></div>
      <div class="card kpi"><div class="k-label">Appliances on</div><div class="k-value">${onCount}<small>of ${list.length}</small></div>
        <div class="k-sub">Today so far: ${kwh(k.todayKwh)}</div></div>
    </section>
    <div class="row" role="group" aria-label="Filter by room">
      <div class="tabs" id="roomTabs">
        <button type="button" data-room="" class="${S.liveRoom ? '' : 'on'}">All rooms</button>
        ${S.meta.rooms.map(r => `<button type="button" data-room="${esc(r.id)}" class="${S.liveRoom === r.id ? 'on' : ''}">${esc(r.name)}</button>`).join('')}
      </div>
    </div>
    <div class="spacer-v"></div>
    ${rooms.map(r => {
      const items = list.filter(a => a.roomId === r.id);
      if (!items.length) return '';
      const load = items.filter(a => a.on).reduce((s, a) => s + a.watts, 0);
      return `<section class="room-block"><h2>${esc(r.name)} <span>${load ? watts(load) + ' now' : 'all off'}</span></h2>
        <div class="tiles">${items.map(a => `
          <div class="tile ${a.on ? 'is-on' : ''}">
            <div class="t-top"><div class="grow"><a class="t-name" href="#/appliances/${esc(a.id)}">${esc(a.name)}</a>
              <div class="t-meta">${typeLabel(a.type)} · ${watts(a.watts)}${a.quantity > 1 ? ' (' + a.quantity + ')' : ''}</div></div>
              ${powerSwitch(a)}</div>
            <div class="t-state">${esc(onState(a))}</div>
            ${a.on && a.shiftable && k.peakNow ? '<span class="badge peak">Peak hours: costs more now</span>' : ''}
            ${a.on && a.onByName ? `<div class="tiny muted">Switched on by ${esc(a.onByName)}</div>` : ''}
          </div>`).join('')}</div></section>`;
    }).join('')}`;
  $$('#roomTabs [data-room]').forEach(b => b.addEventListener('click', () => { S.liveRoom = b.dataset.room; pageLive(); }));
  $('#allOff').addEventListener('click', () => $('#leaveBtn').click());
  paintBars(view());
  wirePower(view());
  autoRefresh(async () => { if (location.hash.startsWith('#/live')) { const y = window.scrollY; await pageLive(); window.scrollTo(0, y); } }, 20000);
}

// ------------------------------------------------------------------ appliances list
async function pageAppliances() {
  const f = S.applFilter;
  const qs = new URLSearchParams({ room: f.room, type: f.type, q: f.q });
  const [list, rep] = await Promise.all([api('GET', '/api/appliances?' + qs), api('GET', '/api/reports?months=3')]);
  const stats = Object.fromEntries(rep.topAppliances.map(a => [a.id, a]));
  const sorters = {
    kwh: (a, b) => (stats[b.id]?.kwh || 0) - (stats[a.id]?.kwh || 0),
    name: (a, b) => a.name.localeCompare(b.name),
    room: (a, b) => a.roomName.localeCompare(b.roomName) || a.name.localeCompare(b.name),
    watts: (a, b) => b.watts - a.watts
  };
  list.sort(sorters[f.sort] || sorters.kwh);
  const total = list.reduce((s, a) => s + (stats[a.id]?.kwh || 0), 0);
  const sortBtn = (key, text) => `<button class="sort ${f.sort === key ? 'on' : ''}" data-sort="${key}" type="button">${text}${f.sort === key ? ' ▾' : ''}</button>`;
  view().innerHTML = `
    <div class="page-head">
      <div><h1>Appliances</h1><p>${list.length} shown · ${kwh(total, 0)} in the last 30 days · ${money(total * rep.ratePerUnit)}</p></div>
      <div class="spacer"></div>
      ${canManage() ? '<button class="primary" id="addAppl" type="button">+ Add appliance</button>' : ''}
    </div>
    <section class="card">
      <form class="filters" id="applFilters">
        <input type="search" name="q" value="${esc(f.q)}" placeholder="Search name, type or room" aria-label="Search">
        <select name="room" aria-label="Room">${roomOptions(f.room, 'All rooms')}</select>
        <select name="type" aria-label="Type">${typeOptions(f.type, 'All types')}</select>
        <button type="button" class="ghost" id="clearF">Clear</button>
      </form>
      <div class="table-wrap"><table>
        <thead><tr><th>${sortBtn('name', 'Appliance')}</th><th>${sortBtn('room', 'Room')}</th><th class="num">${sortBtn('watts', 'Power')}</th>
          <th>Rating</th><th class="num">Hours / day</th><th class="num">${sortBtn('kwh', 'Units, 30 days')}</th><th class="num">Cost, 30 days</th><th>Share</th><th>Status</th></tr></thead>
        <tbody>${list.map(a => {
          const st = stats[a.id] || {};
          return `<tr>
            <td><a href="#/appliances/${esc(a.id)}">${esc(a.name)}</a><div class="tiny muted">${typeLabel(a.type)}${a.quantity > 1 ? ' × ' + a.quantity : ''}${st.spike ? ' · <span class="warn-text">using more than usual</span>' : ''}</div></td>
            <td>${esc(a.roomName)}</td>
            <td class="num">${watts(a.watts)}${a.standbyWatts ? `<div class="tiny muted">${num(a.standbyWatts)} W standby</div>` : ''}</td>
            <td>${stars(a.starRating)}</td>
            <td class="num">${num(st.avgHours ?? a.hoursPerDay)}</td>
            <td class="num">${num(st.kwh)}</td>
            <td class="num">${money(st.cost)}</td>
            <td>${bar(st.kwh || 0, Math.max(1, ...rep.topAppliances.map(x => x.kwh)), 'energy')}</td>
            <td>${powerSwitch(a)}</td></tr>`;
        }).join('') || '<tr><td colspan="9" class="empty">No appliances match.</td></tr>'}</tbody>
      </table></div>
    </section>
    <section class="card" id="roomsCard">
      <div class="card-head"><h2>Rooms</h2><span class="sub">${S.meta.rooms.length}</span><span class="spacer"></span>
        ${canManage() ? '<button class="small" id="addRoom" type="button">+ Add room</button>' : ''}</div>
      <ul class="list">${S.meta.rooms.map(r => `<li><div class="grow"><span class="title">${esc(r.name)}</span>
        <div class="meta">${r.applianceCount} appliance(s)</div></div>
        ${canManage() ? `<button class="small ghost" data-room-edit="${esc(r.id)}">Rename</button><button class="small ghost danger" data-room-del="${esc(r.id)}">Delete</button>` : ''}</li>`).join('')}</ul>
    </section>`;
  const fm = $('#applFilters');
  fm.addEventListener('change', () => { S.applFilter = { ...f, ...Object.fromEntries(new FormData(fm)) }; pageAppliances(); });
  fm.addEventListener('submit', e => { e.preventDefault(); S.applFilter = { ...f, ...Object.fromEntries(new FormData(fm)) }; pageAppliances(); });
  $('#clearF').addEventListener('click', () => { S.applFilter = { room: '', type: '', q: '', sort: f.sort }; $('#globalSearch').value = ''; pageAppliances(); });
  $$('[data-sort]').forEach(b => b.addEventListener('click', () => { S.applFilter.sort = b.dataset.sort; pageAppliances(); }));
  if ($('#addAppl')) $('#addAppl').addEventListener('click', () => applianceForm(null));
  if ($('#addRoom')) $('#addRoom').addEventListener('click', () => roomForm(null));
  $$('[data-room-edit]').forEach(b => b.addEventListener('click', () => roomForm(S.meta.rooms.find(r => r.id === b.dataset.roomEdit))));
  $$('[data-room-del]').forEach(b => b.addEventListener('click', () => {
    const r = S.meta.rooms.find(x => x.id === b.dataset.roomDel);
    confirmBox('Delete room', `Delete "${r.name}"? It must have no appliances.`, 'Delete', async () => {
      await api('DELETE', '/api/rooms/' + r.id); await loadMeta(); toast('Room deleted'); refresh();
    });
  }));
  paintBars(view());
  wirePower(view());
}

function roomForm(r) {
  modal.open({
    title: r ? 'Rename room' : 'Add a room',
    body: `<div class="field"><label for="rn">Room name</label><input id="rn" name="name" maxlength="40" required value="${esc(r ? r.name : '')}"></div>`,
    onSubmit: async d => {
      if (r) await api('PUT', '/api/rooms/' + r.id, d); else await api('POST', '/api/rooms', d);
      await loadMeta(); toast('Saved'); refresh();
    }
  });
}

function applianceForm(a, after) {
  const x = a || { type: 'OTHER', roomId: S.meta.rooms[0]?.id, quantity: 1, starRating: 0, hoursPerDay: 2, usualStartHour: 19, standbyWatts: 0, autoOffMinutes: 0, setTemp: 24, watts: '' };
  modal.open({
    title: a ? 'Edit ' + a.name : 'Add an appliance',
    wide: true,
    body: `
      <div class="grid2">
        <div class="field"><label for="fn">Name</label><input id="fn" name="name" maxlength="60" required value="${esc(x.name || '')}" placeholder="e.g. Bedroom AC"></div>
        <div class="field"><label for="ft">Type</label><select id="ft" name="type">${typeOptions(x.type)}</select></div>
        <div class="field"><label for="fr">Room</label><select id="fr" name="roomId">${roomOptions(x.roomId)}</select></div>
        <div class="field"><label for="fq">How many (same kind)</label><input id="fq" name="quantity" type="number" min="1" max="100" value="${esc(x.quantity)}">
          <div class="hint">e.g. 4 bulbs. Power below is for all of them together.</div></div>
        <div class="field"><label for="fw">Power when running (watts)</label><input id="fw" name="watts" type="number" min="1" max="20000" step="any" required value="${esc(x.watts)}">
          <div class="hint">On the label at the back. 1.5 ton AC ≈ 1500 W, LED bulb ≈ 9 W.</div></div>
        <div class="field"><label for="fs">Standby power (watts)</label><input id="fs" name="standbyWatts" type="number" min="0" max="200" step="any" value="${esc(x.standbyWatts)}">
          <div class="hint">Power used when "off" but plugged in (red light). 0 if none.</div></div>
        <div class="field"><label for="fh">Normal hours per day</label><input id="fh" name="hoursPerDay" type="number" min="0" max="24" step="0.05" value="${esc(x.hoursPerDay)}"></div>
        <div class="field"><label for="fsh">Usually starts at</label><select id="fsh" name="usualStartHour">${hourOptions(x.usualStartHour)}</select>
          <div class="hint">Peak hours are ${hourText(S.settings.peakStart)}-${hourText(S.settings.peakEnd)}.</div></div>
        <div class="field"><label for="fst">BEE star rating</label><select id="fst" name="starRating">${options([{ id: 0, label: 'Not rated' }, 1, 2, 3, 4, 5].map(v => typeof v === 'object' ? v : { id: v, label: v + ' star' }), x.starRating)}</select></div>
        <div class="field" id="tempField"><label for="ftemp">AC temperature (°C)</label><input id="ftemp" name="setTemp" type="number" min="16" max="30" value="${esc(x.setTemp || 24)}"></div>
        <div class="field"><label for="fa">Auto-off after (minutes)</label><input id="fa" name="autoOffMinutes" type="number" min="0" max="1440" value="${esc(x.autoOffMinutes)}">
          <div class="hint">0 = never. Like a smart plug timer (good for geysers and chargers).</div></div>
      </div>
      <div class="field"><label for="fno">Notes</label><textarea id="fno" name="notes" maxlength="300">${esc(x.notes || '')}</textarea></div>`,
    onOpen: body => {
      const showTemp = () => $('#tempField', body).classList.toggle('hidden', $('#ft', body).value !== 'AIR_CONDITIONER');
      $('#ft', body).addEventListener('change', showTemp); showTemp();
    },
    onSubmit: async d => {
      if (d.type !== 'AIR_CONDITIONER') d.setTemp = 0;
      const saved = a ? await api('PUT', '/api/appliances/' + a.id, d) : await api('POST', '/api/appliances', d);
      await loadMeta();
      toast(a ? 'Saved' : 'Appliance added');
      if (after) after(saved); else location.hash = '#/appliances/' + saved.id;
      refreshBadges();
    }
  });
}

// ------------------------------------------------------------------ one appliance
async function pageAppliance(id) {
  const a = await api('GET', '/api/appliances/' + encodeURIComponent(id));
  const st = a.stats, p = a.profile;
  const cols = a.daily.map(x => ({ label: x.label.split(' ')[0], title: x.label + ': ' + num(x.kwh, 2) + ' kWh', parts: [{ v: x.kwh, cls: 'seg-single' }] }));
  view().innerHTML = `
    <a class="back" href="#/appliances">← All appliances</a>
    <div class="page-head">
      <div><h1>${esc(a.name)}</h1><p>${typeLabel(a.type)} · ${esc(a.roomName)} · ${stars(a.starRating)}</p></div>
      <div class="spacer"></div>
      <div class="row"><span class="small muted">${a.on ? 'On' : 'Off'}</span>${powerSwitch(a)}</div>
      ${canManage() ? '<button id="editA" type="button">Edit</button><button id="delA" class="danger" type="button">Delete</button>' : ''}
    </div>
    ${st.spike ? `<div class="alerts"><div class="alert warn"><span class="dot"></span><div class="grow"><b>Using more than usual</b>
      <span class="small">${num(st.last7Avg, 2)} kWh a day this week vs ${num(st.prev28Avg, 2)} in the 4 weeks before.</span></div></div></div>` : ''}
    <section class="kpis">
      <div class="card kpi"><div class="k-label">Units per month</div><div class="k-value">${num(st.monthlyKwh)}<small>kWh</small></div><div class="k-sub">${st.share}% of the home</div></div>
      <div class="card kpi"><div class="k-label">Cost per month</div><div class="k-value">${money(st.monthlyCost)}</div><div class="k-sub">${money(st.yearlyCost)} a year</div></div>
      <div class="card kpi"><div class="k-label">Average use</div><div class="k-value">${num(st.avgHours)}<small>h/day</small></div><div class="k-sub">Normal: ${num(a.hoursPerDay)} h from ${hourText(a.usualStartHour)}</div></div>
      <div class="card kpi"><div class="k-label">Standby waste</div><div class="k-value">${num(st.standbyKwh30)}<small>kWh</small></div><div class="k-sub">last 30 days</div></div>
      <div class="card kpi"><div class="k-label">CO₂ per month</div><div class="k-value">${num(st.co2Month)}<small>kg</small></div><div class="k-sub">from grid power</div></div>
    </section>
    <div class="split">
      <section class="card">
        <div class="card-head"><h2>Last 30 days</h2><span class="sub">kWh per day</span></div>
        <div class="card-body">${vchart(cols, { labelEvery: 3, cls: 'short', aria: 'Daily units for ' + a.name })}</div>
      </section>
      <section class="card">
        <div class="card-head"><h2>Details</h2></div>
        <div class="card-body"><dl class="facts">
          <div><dt>Power</dt><dd>${watts(a.watts)}${a.quantity > 1 ? ' (' + a.quantity + ' × ' + watts(a.watts / a.quantity) + ')' : ''}</dd></div>
          <div><dt>Standby</dt><dd>${num(a.standbyWatts)} W</dd></div>
          ${a.type === 'AIR_CONDITIONER' ? `<div><dt>Set to</dt><dd>${a.setTemp} °C</dd></div>` : ''}
          <div><dt>Auto-off</dt><dd>${a.autoOffMinutes ? a.autoOffMinutes + ' min' : 'Never'}</dd></div>
          <div><dt>Normal day</dt><dd>${num(p.kwh + p.standbyKwh, 2)} kWh</dd></div>
          <div><dt>In peak hours</dt><dd>${num(p.peakHours)} h of ${num(a.hoursPerDay)} h</dd></div>
          <div><dt>Rate</dt><dd>${money2(a.ratePerUnit)} / unit</dd></div>
        </dl>${a.notes ? `<p class="small muted">${esc(a.notes)}</p>` : ''}
        <p class="small"><a href="#/planner/${esc(a.id)}">What if I use it differently? Open the planner →</a></p></div>
      </section>
    </div>
    <section class="card">
      <div class="card-head"><h2>Savings tips for this appliance</h2></div>
      <div class="card-body tip-list">${a.tips.filter(t => t.status === 'NEW').map(t => tipCard(t)).join('') || '<div class="empty">No tips. It is used efficiently.</div>'}
        ${a.tips.filter(t => t.status !== 'NEW').map(t => `<p class="small muted">${t.status === 'APPLIED' ? 'Applied' : 'Hidden'}: ${esc(t.title)}</p>`).join('')}</div>
    </section>
    <div class="cards cols-2">
      <section class="card">
        <div class="card-head"><h2>Recent usage records</h2><span class="spacer"></span><button class="small" id="logA" type="button">+ Add record</button></div>
        <div class="table-wrap"><table><thead><tr><th>Date</th><th class="num">Hours</th><th class="num">kWh</th><th class="num">Cost</th><th>Source</th></tr></thead>
        <tbody>${a.logs.map(l => `<tr><td class="nowrap">${esc(dateOnly(l.date))}</td><td class="num">${num(l.hours, 2)}</td><td class="num">${num(l.totalKwh, 2)}</td>
          <td class="num">${money2(l.cost)}</td><td><span class="badge src-${esc(l.source)}">${esc(l.source.toLowerCase())}</span></td></tr>`).join('') || '<tr><td colspan="5" class="empty">No records yet.</td></tr>'}</tbody></table></div>
      </section>
      <section class="card">
        <div class="card-head"><h2>History</h2></div>
        ${eventsHtml(a.events)}
      </section>
    </div>`;
  paintBars(view());
  wirePower(view());
  wireTips(view());
  $('#logA').addEventListener('click', () => usageForm(null, a.id));
  if ($('#editA')) $('#editA').addEventListener('click', () => applianceForm(a, () => refresh()));
  if ($('#delA')) $('#delA').addEventListener('click', () => confirmBox('Delete appliance',
    `Delete "${a.name}" and all its usage history? This cannot be undone.`, 'Delete', async () => {
      await api('DELETE', '/api/appliances/' + a.id); await loadMeta(); toast('Deleted'); location.hash = '#/appliances';
    }));
}

// ------------------------------------------------------------------ usage & meter
async function pageUsage() {
  view().innerHTML = `
    <div class="page-head">
      <div><h1>Usage &amp; meter</h1><p>Daily records for each appliance, and readings from the main electricity meter.</p></div>
      <div class="spacer"></div>
      <div class="seg" role="tablist">
        <button type="button" role="tab" data-utab="records" class="${S.usageTab === 'records' ? 'on' : ''}" aria-selected="${S.usageTab === 'records'}">Daily records</button>
        <button type="button" role="tab" data-utab="meter" class="${S.usageTab === 'meter' ? 'on' : ''}" aria-selected="${S.usageTab === 'meter'}">Meter readings</button>
      </div>
    </div>
    <div id="usageBody"><div class="empty">Loading...</div></div>`;
  $$('[data-utab]').forEach(b => b.addEventListener('click', () => { S.usageTab = b.dataset.utab; pageUsage(); }));
  if (S.usageTab === 'meter') await meterTab(); else await recordsTab();
}

async function recordsTab() {
  const f = S.usageFilter;
  const qs = new URLSearchParams({ ...f, limit: 150 });
  const [rows, appl] = await Promise.all([api('GET', '/api/usage?' + qs), api('GET', '/api/appliances')]);
  appl.sort((a, b) => a.name.localeCompare(b.name));
  const total = rows.reduce((s, r) => s + r.totalKwh, 0), cost = rows.reduce((s, r) => s + r.cost, 0);
  $('#usageBody').innerHTML = `
    <section class="card">
      <form class="filters" id="uFilters">
        <select name="appliance" aria-label="Appliance">${options(appl.map(a => ({ id: a.id, label: a.name + ' (' + a.roomName + ')' })), f.appliance, 'All appliances')}</select>
        <select name="room" aria-label="Room">${roomOptions(f.room, 'All rooms')}</select>
        <label class="check small">From <input type="date" name="from" value="${esc(f.from)}"></label>
        <label class="check small">To <input type="date" name="to" value="${esc(f.to)}"></label>
        <select name="source" aria-label="Source">${options(S.meta.sources, f.source, 'Any source')}</select>
        <button type="button" class="ghost" id="uClear">Clear</button>
        <span class="spacer"></span>
        <button type="button" id="uCsv">Export to CSV</button>
        ${canManage() ? '<button type="button" id="uFill" title="Fill days with no record using each appliance\'s normal hours">Fill missing days</button>' : ''}
        <button type="button" class="primary" id="uAdd">+ Add record</button>
      </form>
      <div class="card-body small muted">${rows.length >= 150 ? 'Showing the newest 150 records. Use the filters to narrow down. ' : rows.length + ' records. '}
        Total ${kwh(total)} · about ${money(cost)}. <b>Live</b> = measured by the on/off switch, <b>manual</b> = typed in, <b>estimate</b> = from normal hours.</div>
      <div class="table-wrap"><table>
        <thead><tr><th>Date</th><th>Appliance</th><th class="num">Hours on</th><th class="num">In peak</th><th class="num">Running kWh</th><th class="num">Standby kWh</th><th class="num">Cost</th><th>Source</th><th></th></tr></thead>
        <tbody>${rows.map(r => `<tr>
          <td class="nowrap">${esc(dateOnly(r.date))}</td>
          <td><a href="#/appliances/${esc(r.applianceId)}">${esc(r.applianceName)}</a><div class="tiny muted">${esc(r.roomName)}${r.note ? ' · ' + esc(r.note) : ''}</div></td>
          <td class="num">${num(r.hours, 2)}</td><td class="num">${r.peakHours ? '<span class="peak-text">' + num(r.peakHours, 2) + '</span>' : '0'}</td>
          <td class="num">${num(r.kwh, 3)}</td><td class="num">${num(r.standbyKwh, 3)}</td><td class="num">${money2(r.cost)}</td>
          <td><span class="badge src-${esc(r.source)}">${esc(r.source.toLowerCase())}</span></td>
          <td class="nowrap">${canManage() || r.by === S.user.username ? `<button class="small ghost" data-uedit="${esc(r.id)}">Edit</button>` : ''}
            ${canManage() ? `<button class="small ghost danger" data-udel="${esc(r.id)}">Delete</button>` : ''}</td></tr>`).join('') || '<tr><td colspan="9" class="empty">No records match.</td></tr>'}</tbody>
      </table></div>
    </section>`;
  const fm = $('#uFilters');
  const apply = () => { const d = Object.fromEntries(new FormData(fm)); S.usageFilter = { appliance: d.appliance, room: d.room, from: d.from, to: d.to, source: d.source }; recordsTab(); };
  $$('select, input', fm).forEach(i => i.addEventListener('change', apply));
  fm.addEventListener('submit', e => { e.preventDefault(); apply(); });
  $('#uClear').addEventListener('click', () => { S.usageFilter = { appliance: '', room: '', from: '', to: '', source: '' }; recordsTab(); });
  $('#uCsv').addEventListener('click', e => run(e.currentTarget, () => downloadFile('/api/usage/export.csv?' + new URLSearchParams(S.usageFilter), 'energy-usage.csv')));
  $('#uAdd').addEventListener('click', () => usageForm(null, f.appliance));
  if ($('#uFill')) $('#uFill').addEventListener('click', () => modal.open({
    title: 'Fill missing days',
    body: `<p class="small">For days with no record, the app adds an <b>estimate</b> from each appliance's normal hours. Real records are never changed.</p>
      <div class="grid2"><div class="field"><label for="ff">From</label><input id="ff" type="date" name="from" value="${todayStr(-7)}" max="${todayStr(-1)}"></div>
      <div class="field"><label for="ft2">To</label><input id="ft2" type="date" name="to" value="${todayStr(-1)}" max="${todayStr(-1)}"></div></div>`,
    submitText: 'Fill days',
    onSubmit: async d => { const r = await api('POST', '/api/usage/autofill', d); toast(r.created + ' estimate record(s) added'); recordsTab(); }
  }));
  $$('[data-uedit]').forEach(b => b.addEventListener('click', () => usageForm(rows.find(r => r.id === b.dataset.uedit))));
  $$('[data-udel]').forEach(b => b.addEventListener('click', () => confirmBox('Delete record', 'Delete this usage record?', 'Delete', async () => {
    await api('DELETE', '/api/usage/' + b.dataset.udel); toast('Deleted'); recordsTab();
  })));
}

async function usageForm(r, applianceId) {
  const appl = await api('GET', '/api/appliances');
  appl.sort((a, b) => a.name.localeCompare(b.name));
  const sel = r ? r.applianceId : applianceId || appl[0]?.id;
  const ap = appl.find(a => a.id === sel);
  modal.open({
    title: r ? 'Edit usage record' : 'Add a usage record',
    body: `
      <div class="field"><label for="ua">Appliance</label><select id="ua" name="applianceId" ${r ? 'disabled' : ''}>${options(appl.map(a => ({ id: a.id, label: a.name + ' (' + a.roomName + ')' })), sel)}</select></div>
      <div class="grid3">
        <div class="field"><label for="ud">Date</label><input id="ud" type="date" name="date" max="${todayStr()}" required value="${esc(r ? r.date : todayStr(-1))}"></div>
        <div class="field"><label for="uh">Hours it ran</label><input id="uh" type="number" name="hours" min="0" max="24" step="0.05" required value="${esc(r ? r.hours : ap ? ap.hoursPerDay : 1)}"></div>
        <div class="field"><label for="us">Started at</label><select id="us" name="startHour">${hourOptions(ap ? ap.usualStartHour : 19)}</select></div>
      </div>
      <div class="field"><label for="un">Note (optional)</label><input id="un" name="note" maxlength="200" value="${esc(r ? r.note : '')}"></div>
      <p class="small muted" id="uPrev"></p>
      <p class="hint">One record per appliance per day: saving replaces any record already there for that day.</p>`,
    onOpen: body => {
      const prev = () => {
        const a = appl.find(x => x.id === $('#ua', body).value); const h = Number($('#uh', body).value) || 0;
        if (a) $('#uPrev', body).textContent = 'About ' + num(a.watts * h / 1000 + a.standbyWatts * (24 - h) / 1000, 2) + ' kWh (' + num(a.watts * h / 1000, 2) + ' running + ' + num(a.standbyWatts * (24 - h) / 1000, 2) + ' standby).';
      };
      $('#ua', body).addEventListener('change', () => { const a = appl.find(x => x.id === $('#ua', body).value); if (a) { $('#uh', body).value = a.hoursPerDay; $('#us', body).value = a.usualStartHour; } prev(); });
      $('#uh', body).addEventListener('input', prev); prev();
    },
    onSubmit: async d => {
      if (r) await api('PUT', '/api/usage/' + r.id, d); else await api('POST', '/api/usage', { ...d, applianceId: d.applianceId || sel });
      toast('Saved'); refresh();
    }
  });
}

async function meterTab() {
  const [list, rep] = await Promise.all([api('GET', '/api/meter'), api('GET', '/api/reports?months=3')]);
  const g = rep.meterGap || {};
  $('#usageBody').innerHTML = `
    <div class="split">
      <section class="card">
        <div class="card-head"><h2>Meter readings</h2><span class="sub">${list.length}</span><span class="spacer"></span>
          <button class="primary small" id="mAdd" type="button">+ Add reading</button></div>
        <div class="table-wrap"><table>
          <thead><tr><th>Date</th><th class="num">Reading (kWh)</th><th class="num">Units since last</th><th class="num">Per day</th><th>By</th><th></th></tr></thead>
          <tbody>${list.map(m => `<tr><td class="nowrap">${esc(dateOnly(m.date))}</td><td class="num">${num(m.reading)}</td>
            <td class="num">${m.unitsSincePrevious != null ? num(m.unitsSincePrevious) + ' <span class="tiny muted">(' + m.days + ' days)</span>' : '-'}</td>
            <td class="num">${m.perDay != null ? num(m.perDay, 2) : '-'}</td><td>${esc(m.byName)}</td>
            <td>${canManage() ? `<button class="small ghost danger" data-mdel="${esc(m.id)}">Delete</button>` : ''}</td></tr>`).join('') || '<tr><td colspan="6" class="empty">No readings yet.</td></tr>'}</tbody>
        </table></div>
      </section>
      <section class="card">
        <div class="card-head"><h2>Meter check</h2></div>
        <div class="card-body">${g.meterUnits != null ? `
          <p class="small muted">From ${esc(dateOnly(g.from))} to ${esc(dateOnly(g.to))}:</p>
          ${hbars([{ label: 'Main meter', value: g.meterUnits, text: kwh(g.meterUnits) }, { label: 'Your appliances (tracked)', value: g.trackedUnits, text: kwh(g.trackedUnits) }])}
          <p class="small">${g.gapPercent > 15 ? `<b class="warn-text">${num(g.gapPercent)}% is not tracked.</b> Some appliances may be missing from the list.`
            : `<b class="good-text">Only ${num(g.gapPercent)}% untracked.</b> Almost everything that uses power is in the app.`}</p>`
          : '<div class="empty">Add at least two readings, about a week apart.</div>'}
          <p class="hint">Read the big number on your electricity meter (kWh) once a week, at about the same time of day.</p>
        </div>
      </section>
    </div>`;
  paintBars($('#usageBody'));
  const last = list[0];
  $('#mAdd').addEventListener('click', () => modal.open({
    title: 'Add a meter reading',
    body: `<div class="grid2"><div class="field"><label for="md">Date</label><input id="md" type="date" name="date" max="${todayStr()}" value="${todayStr()}" required></div>
      <div class="field"><label for="mr">Meter reading (kWh)</label><input id="mr" type="number" name="reading" min="0" step="0.1" required ${last ? `placeholder="last: ${esc(last.reading)}"` : ''}></div></div>
      <div class="field"><label for="mn">Note (optional)</label><input id="mn" name="note" maxlength="200"></div>`,
    onSubmit: async d => { await api('POST', '/api/meter', d); toast('Reading saved'); pageUsage(); }
  }));
  $$('[data-mdel]').forEach(b => b.addEventListener('click', () => confirmBox('Delete reading', 'Delete this meter reading?', 'Delete', async () => {
    await api('DELETE', '/api/meter/' + b.dataset.mdel); toast('Deleted'); pageUsage();
  })));
}

// ------------------------------------------------------------------ savings tips
async function pageTips() {
  const d = await api('GET', '/api/tips');
  const list = d.open.filter(t => !S.tipEffort || t.effort === S.tipEffort);
  view().innerHTML = `
    <div class="page-head">
      <div><h1>Savings tips</h1><p>Worked out from your own appliances and usage. Biggest rupee saving first.</p></div>
    </div>
    <section class="kpis">
      <div class="card kpi energy"><div class="k-label">Could save</div><div class="k-value">${money(d.potentialRupees)}<small>/month</small></div><div class="k-sub">${money(d.potentialRupeesYear)} a year</div></div>
      <div class="card kpi"><div class="k-label">Free tips alone</div><div class="k-value">${money(d.freeRupees)}<small>/month</small></div><div class="k-sub">just change a setting or a habit</div></div>
      <div class="card kpi"><div class="k-label">Units saved</div><div class="k-value">${num(d.potentialKwh, 0)}<small>kWh/mo</small></div><div class="k-sub">${num(d.potentialCo2, 0)} kg CO₂ less</div></div>
      <div class="card kpi"><div class="k-label">Already saving</div><div class="k-value good-text">${money(d.savedRupees)}<small>/month</small></div><div class="k-sub">from ${d.done.filter(x => x.status === 'APPLIED').length} applied tip(s)</div></div>
    </section>
    <div class="row"><div class="tabs" id="effTabs">
      <button type="button" data-eff="" class="${S.tipEffort ? '' : 'on'}">All (${d.open.length})</button>
      ${S.meta.efforts.map(e => `<button type="button" data-eff="${esc(e.id)}" class="${S.tipEffort === e.id ? 'on' : ''}">${esc(e.label)} (${d.open.filter(t => t.effort === e.id).length})</button>`).join('')}
    </div></div>
    <p></p>
    <div class="tip-list">${list.map(t => tipCard(t)).join('') || '<div class="card empty">No tips here.</div>'}</div>
    <p></p>
    <section class="card">
      <div class="card-head"><h2>Done and hidden</h2><span class="sub">${d.done.length}</span></div>
      <ul class="list">${d.done.map(r => `<li><div class="grow"><span class="title">${esc(r.title)}</span>
        <div class="meta">${r.status === 'APPLIED' ? '<span class="good-text">Applied</span> · saves about ' + money(r.rupeesPerMonth) + '/month' : 'Hidden'} · ${esc(r.byName)} · ${esc(when(r.at))}</div></div>
        ${canManage() ? `<button class="small ghost" data-restore="${esc(r.key)}" title="${r.status === 'APPLIED' ? 'Remove from the done list (settings are not changed back)' : 'Show this tip again'}">${r.status === 'APPLIED' ? 'Remove' : 'Show again'}</button>` : ''}</li>`).join('') || '<li class="empty">Nothing yet.</li>'}</ul>
    </section>`;
  $$('#effTabs [data-eff]').forEach(b => b.addEventListener('click', () => { S.tipEffort = b.dataset.eff; pageTips(); }));
  wireTips(view());
  $$('[data-restore]').forEach(b => b.addEventListener('click', () => run(b, async () => {
    await api('POST', `/api/tips/${encodeURIComponent(b.dataset.restore)}/restore`, {}); toast('Done'); refresh();
  })));
}

// ------------------------------------------------------------------ savings planner
async function pagePlanner(preselect) {
  const appl = await api('GET', '/api/appliances');
  appl.sort((a, b) => a.roomName.localeCompare(b.roomName) || a.name.localeCompare(b.name));
  if (preselect && appl.find(a => a.id === preselect) && !S.plan.find(p => p.applianceId === preselect)) addPlanRow(appl.find(a => a.id === preselect));
  S.plan = S.plan.filter(p => appl.find(a => a.id === p.applianceId));
  view().innerHTML = `
    <div class="page-head">
      <div><h1>Savings planner</h1><p>"What if?" Change how you use appliances and see the units, bill and CO₂ change. Nothing is saved until you choose to.</p></div>
    </div>
    <div class="split">
      <section class="card">
        <div class="card-head"><h2>Changes to try</h2><span class="spacer"></span>
          <select id="planAdd" aria-label="Add an appliance">${options(appl.filter(a => !S.plan.find(p => p.applianceId === a.id)).map(a => ({ id: a.id, label: a.name + ' (' + a.roomName + ')' })), '', '+ Add an appliance...')}</select></div>
        <div id="planRows">${S.plan.length ? S.plan.map(p => planRow(p, appl.find(a => a.id === p.applianceId))).join('') :
          '<div class="empty">Add an appliance to start. Try the AC, geyser or EV charger.</div>'}</div>
      </section>
      <section class="card">
        <div class="card-head"><h2>Result per month</h2></div>
        <div class="card-body" id="planResult"><div class="empty">Your savings will show here.</div></div>
      </section>
    </div>`;
  $('#planAdd').addEventListener('change', e => {
    const a = appl.find(x => x.id === e.target.value); if (!a) return;
    addPlanRow(a); pagePlanner();
  });
  $$('[data-plan]').forEach(inp => inp.addEventListener('input', () => {
    const p = S.plan.find(x => x.applianceId === inp.dataset.plan);
    const k = inp.dataset.key;
    p[k] = inp.type === 'checkbox' ? inp.checked : Number(inp.value);
    const out = $(`[data-out="${p.applianceId}-${k}"]`); if (out) out.textContent = k === 'hoursPerDay' ? num(p[k]) + ' h' : p[k];
    planCalc();
  }));
  $$('[data-plan-del]').forEach(b => b.addEventListener('click', () => { S.plan = S.plan.filter(p => p.applianceId !== b.dataset.planDel); pagePlanner(); }));
  $$('[data-plan-save]').forEach(b => b.addEventListener('click', () => {
    const p = S.plan.find(x => x.applianceId === b.dataset.planSave); const a = appl.find(x => x.id === p.applianceId);
    confirmBox('Save as the new normal?', `Save ${num(p.hoursPerDay)} hours a day from ${hourText(p.startHour)}${a.type === 'AIR_CONDITIONER' ? ' at ' + p.setTemp + ' °C' : ''} as the normal use of ${a.name}?`, 'Save', async () => {
      await api('PUT', '/api/appliances/' + a.id, { hoursPerDay: p.hoursPerDay, usualStartHour: p.startHour, ...(a.type === 'AIR_CONDITIONER' ? { setTemp: p.setTemp } : {}) });
      S.plan = S.plan.filter(x => x.applianceId !== a.id); toast('Saved. Tips and estimates now use the new normal.'); refresh();
    });
  }));
  planCalc();
}

function addPlanRow(a) {
  S.plan.push({ applianceId: a.id, hoursPerDay: a.hoursPerDay, startHour: a.usualStartHour, setTemp: a.setTemp || 24, starRating: a.starRating, standbyOff: false });
}

function planRow(p, a) {
  const id = esc(a.id);
  return `<div class="plan-row">
    <div class="name"><b>${esc(a.name)}</b><span>${esc(a.roomName)} · ${watts(a.watts)} · now ${num(a.hoursPerDay)} h from ${hourText(a.usualStartHour)}</span></div>
    <div><label for="h-${id}">Hours a day: <b data-out="${id}-hoursPerDay">${num(p.hoursPerDay)} h</b></label>
      <input id="h-${id}" type="range" min="0" max="${a.hoursPerDay >= 20 ? 24 : Math.max(12, Math.ceil(a.hoursPerDay * 2))}" step="0.25" value="${esc(p.hoursPerDay)}" data-plan="${id}" data-key="hoursPerDay"></div>
    <div><label for="s-${id}">Start at</label><select id="s-${id}" data-plan="${id}" data-key="startHour">${hourOptions(p.startHour)}</select></div>
    <div>${a.type === 'AIR_CONDITIONER' ? `<label for="t-${id}">AC temp: <b data-out="${id}-setTemp">${esc(p.setTemp)}</b> °C</label>
      <input id="t-${id}" type="range" min="16" max="30" step="1" value="${esc(p.setTemp)}" data-plan="${id}" data-key="setTemp">` :
      a.starRating ? `<label for="r-${id}">Star rating</label><select id="r-${id}" data-plan="${id}" data-key="starRating">${options([1, 2, 3, 4, 5].map(v => ({ id: v, label: v + ' star' })), p.starRating)}</select>` : '<span class="tiny muted">No rating</span>'}</div>
    <div>${a.type === 'AIR_CONDITIONER' && a.starRating ? `<label for="r-${id}">Star rating</label><select id="r-${id}" data-plan="${id}" data-key="starRating">${options([1, 2, 3, 4, 5].map(v => ({ id: v, label: v + ' star' })), p.starRating)}</select>` :
      a.standbyWatts > 0 ? `<label class="check"><input type="checkbox" data-plan="${id}" data-key="standbyOff" ${p.standbyOff ? 'checked' : ''}> Cut ${num(a.standbyWatts)} W standby</label>` : ''}</div>
    <div class="row">${canManage() ? `<button class="small" data-plan-save="${id}" type="button">Save as normal</button>` : ''}
      <button class="small ghost" data-plan-del="${id}" type="button" aria-label="Remove ${esc(a.name)}">Remove</button></div>
    <div class="out" data-row-out="${id}"></div>
  </div>`;
}

let planSeq = 0;
async function planCalc() {
  const box = $('#planResult'); if (!box) return;
  if (!S.plan.length) { box.innerHTML = '<div class="empty">Your savings will show here.</div>'; return; }
  const seq = ++planSeq;
  await new Promise(r => setTimeout(r, 150)); // wait for the slider to settle
  if (seq !== planSeq) return;
  let r;
  try { r = await api('POST', '/api/planner', { changes: S.plan }); } catch (e) { box.innerHTML = `<p class="bad-text">${esc(e.message)}</p>`; return; }
  if (seq !== planSeq || !$('#planResult')) return;
  for (const row of r.rows) {
    const o = $(`[data-row-out="${row.applianceId}"]`);
    if (o) o.innerHTML = `${kwh(row.beforeKwh)} → <b>${kwh(row.afterKwh)}</b> a month` +
      (row.beforePeakKwh !== row.afterPeakKwh ? ` · peak ${num(row.beforePeakKwh)} → ${num(row.afterPeakKwh)} kWh` : '');
  }
  const good = r.savedRupees >= 0;
  box.innerHTML = `
    <div class="k-label small muted">${good ? 'You would save' : 'This would cost more'}</div>
    <div class="result-big ${good ? '' : 'bad-text'}">${money(Math.abs(r.savedRupees))}<small class="muted small"> /month</small></div>
    <p class="small muted">${money(Math.abs(r.savedRupeesYear))} a year · ${kwh(Math.abs(r.savedKwh))} · ${num(Math.abs(r.savedCo2))} kg CO₂ ${good ? 'less' : 'more'}</p>
    <div class="compare">
      <div><div class="v">${money(r.before.total)}</div><div class="l">bill now<br>${num(r.before.units, 0)} kWh</div></div>
      <div class="arrow" aria-hidden="true">→</div>
      <div><div class="v ${good ? 'good-text' : 'bad-text'}">${money(r.after.total)}</div><div class="l">bill after<br>${num(r.after.units, 0)} kWh</div></div>
    </div>
    <table class="bill-lines"><tbody>
      <tr><td>Energy charge (slabs)</td><td class="num">${money(r.before.energyCharge)}</td><td class="num">${money(r.after.energyCharge)}</td></tr>
      <tr><td>Peak-hour extra</td><td class="num">${money(r.before.peakExtra)}</td><td class="num">${money(r.after.peakExtra)}</td></tr>
      <tr><td>Off-peak discount</td><td class="num">-${money(r.before.offPeakDiscount)}</td><td class="num">-${money(r.after.offPeakDiscount)}</td></tr>
      <tr><td>Duty</td><td class="num">${money(r.before.duty)}</td><td class="num">${money(r.after.duty)}</td></tr>
      <tr><td>Fixed charge</td><td class="num">${money(r.before.fixedCharge)}</td><td class="num">${money(r.after.fixedCharge)}</td></tr>
    </tbody></table>
    <p class="hint">Based on the last 30 days of the whole home. The bill uses your slab rates, so cutting units also moves you to cheaper slabs.</p>`;
}

// ------------------------------------------------------------------ reports
async function pageReports() {
  const d = await api('GET', '/api/reports?months=' + S.reportMonths);
  const monthCols = d.monthly.map(m => ({
    label: m.label, cls: m.current ? 'forecast' : '', forecast: m.current ? m.forecastKwh : 0,
    title: `${m.label}: ${num(m.kwh)} kWh, bill ${money(m.bill)}${m.current ? ' (so far; expected ' + num(m.forecastKwh) + ' kWh, ' + money(m.forecastBill) + ')' : ''}`,
    parts: [{ v: m.standbyKwh, cls: 'c-standby' }, { v: Math.max(0, m.kwh - m.peakKwh - m.standbyKwh), cls: 'c-normal' }, { v: m.peakKwh, cls: 'c-peak' }]
  }));
  const billCols = d.monthly.map(m => ({ label: m.label, title: m.label + ': ' + money(m.current ? m.forecastBill : m.bill), cls: m.current ? 'forecast' : '',
    parts: [{ v: m.current ? m.forecastBill : m.bill, cls: 'seg-bill' }] }));
  const hourCols = d.hourly.map(h => ({ label: String(h.hour), cls: h.peak ? 'peak' : h.offPeak ? 'offpeak' : 'normal',
    title: hourText(h.hour) + ': about ' + num(h.kwh, 2) + ' kWh', parts: [{ v: h.kwh, cls: '' }] }));
  const l30 = d.last30, b = l30.bill;
  const cmp = d.compare;
  const change = cmp.lastMonth > 0 ? Math.round((cmp.thisMonth - cmp.lastMonth) / cmp.lastMonth * 100) : 0;
  view().innerHTML = `
    <div class="page-head">
      <div><h1>Reports</h1><p>Monthly units and bills, where and when power is used, and what your savings add up to.</p></div>
      <div class="spacer"></div>
      <label class="check small">Show <select id="repMonths">${options([{ id: 6, label: '6 months' }, { id: 12, label: '12 months' }], S.reportMonths)}</select></label>
    </div>
    <section class="kpis">
      <div class="card kpi"><div class="k-label">Last 30 days</div><div class="k-value">${num(l30.kwh, 0)}<small>kWh</small></div><div class="k-sub">Bill about ${money(b.total)}</div></div>
      <div class="card kpi"><div class="k-label">Average price</div><div class="k-value">${money2(d.ratePerUnit)}</div><div class="k-sub">per unit, with duty</div></div>
      <div class="card kpi"><div class="k-label">This month vs last</div><div class="k-value ${change > 0 ? 'bad-text' : 'good-text'}">${change > 0 ? '+' : ''}${change}%</div>
        <div class="k-sub">first ${cmp.days} days: ${num(cmp.thisMonth, 0)} vs ${num(cmp.lastMonth, 0)} kWh</div></div>
      <div class="card kpi"><div class="k-label">CO₂, last 30 days</div><div class="k-value">${num(l30.co2, 0)}<small>kg</small></div><div class="k-sub">≈ ${l30.trees} trees to absorb a year of this</div></div>
      <div class="card kpi energy"><div class="k-label">Saved by tips</div><div class="k-value">${money(d.savedRupees)}<small>/month</small></div><div class="k-sub">${kwh(d.savedKwh)} · ${num(d.savedCo2)} kg CO₂</div></div>
    </section>
    <div class="cards cols-2">
      <section class="card">
        <div class="card-head"><h2>Units per month</h2></div>
        <div class="card-body">${vchart(monthCols, { wide: true, showVals: true, valText: (c, t) => num(t, 0), aria: 'Units per month' })}
          <div class="legend"><span><i class="c-peak"></i>Peak</span><span><i class="c-normal"></i>Other hours</span><span><i class="c-standby"></i>Standby</span><span><i class="l-forecast"></i>This month expected</span></div></div>
      </section>
      <section class="card">
        <div class="card-head"><h2>Bill per month</h2><span class="sub">with slab rates, duty and fixed charge</span></div>
        <div class="card-body">${vchart(billCols, { wide: true, showVals: true, valText: (c, t) => money(t), target: S.settings.monthlyBudget, targetText: 'budget', aria: 'Bill per month' })}
          <div class="legend"><span><i class="seg-bill"></i>Bill</span><span><i class="l-target"></i>Monthly budget ${money(S.settings.monthlyBudget)}</span><span>Faded = this month (expected)</span></div></div>
      </section>
    </div>
    <div class="cards cols-2">
      <section class="card">
        <div class="card-head"><h2>When in the day power is used</h2><span class="sub">a normal day</span></div>
        <div class="card-body">${vchart(hourCols, { cls: 'hours-chart short', labelEvery: 3, aria: 'Units by hour of day' })}
          <div class="legend"><span><i class="c-peak"></i>Peak (costs more)</span><span><i class="c-normal"></i>Normal</span><span><i class="c-offpeak"></i>Off-peak (cheaper)</span></div>
          <p class="small muted">Weekday average ${kwh(d.weekdayAvg, 1)} a day · weekend ${kwh(d.weekendAvg, 1)} a day.</p></div>
      </section>
      <section class="card">
        <div class="card-head"><h2>By type of appliance</h2><span class="sub">last 30 days</span></div>
        <div class="card-body">${hbars(d.byType.filter(t => t.kwh > 0).slice(0, 9).map(t => ({ label: t.label, value: t.kwh, text: kwh(t.kwh, 0) + ' · ' + money(t.cost) })), 'energy')}</div>
      </section>
    </div>
    <div class="cards cols-2">
      <section class="card">
        <div class="card-head"><h2>Bill for the last 30 days, step by step</h2></div>
        <div class="card-body"><table class="bill-lines"><tbody>
          <tr><td>Units used</td><td class="num">${num(b.units)} kWh</td></tr>
          <tr><td>Energy charge (slab rates)</td><td class="num">${money2(b.energyCharge)}</td></tr>
          <tr><td>Peak-hour extra</td><td class="num">+ ${money2(b.peakExtra)}</td></tr>
          <tr><td>Off-peak discount</td><td class="num">- ${money2(b.offPeakDiscount)}</td></tr>
          <tr><td>Electricity duty (${num(S.settings.dutyPercent)}%)</td><td class="num">+ ${money2(b.duty)}</td></tr>
          <tr><td>Fixed charge</td><td class="num">+ ${money2(b.fixedCharge)}</td></tr>
          <tr class="total"><td>Total</td><td class="num">${money2(b.total)}</td></tr></tbody></table>
          <p class="hint">Change the rates on the <a href="#/settings">Rates &amp; goals</a> page to match your electricity bill.</p></div>
      </section>
      <section class="card">
        <div class="card-head"><h2>Savings from applied tips</h2></div>
        <ul class="list">${d.applied.map(t => `<li><div class="grow"><span class="title">${esc(t.title)}</span><div class="meta">${esc(t.byName)} · ${esc(dateOnly(t.at))}</div></div>
          <div class="right good-text num"><b>${money(t.rupeesPerMonth)}</b>/mo</div></li>`).join('') || '<li class="empty">No tips applied yet. <a href="#/tips">See tips</a></li>'}</ul>
      </section>
    </div>
    <section class="card">
      <div class="card-head"><h2>All appliances, last 30 days</h2><span class="spacer"></span><button class="small" id="repCsv" type="button">Export usage to CSV</button></div>
      <div class="table-wrap"><table><thead><tr><th>Appliance</th><th>Room</th><th class="num">Units</th><th class="num">Standby</th><th class="num">Cost</th><th class="num">Hours/day</th><th>Share</th></tr></thead>
        <tbody>${d.topAppliances.map(a => `<tr><td><a href="#/appliances/${esc(a.id)}">${esc(a.name)}</a></td><td>${esc(a.roomName)}</td>
          <td class="num">${num(a.kwh)}</td><td class="num">${num(a.standbyKwh)}</td><td class="num">${money(a.cost)}</td><td class="num">${num(a.avgHours)}</td>
          <td>${bar(a.share, d.topAppliances[0]?.share || 1, 'energy')} <span class="tiny muted">${a.share}%</span></td></tr>`).join('')}</tbody></table></div>
    </section>`;
  $('#repMonths').addEventListener('change', e => { S.reportMonths = Number(e.target.value); pageReports(); });
  $('#repCsv').addEventListener('click', e => run(e.currentTarget, () => downloadFile('/api/usage/export.csv?from=' + todayStr(-30), 'energy-usage.csv')));
  paintBars(view());
}

// ------------------------------------------------------------------ rates & goals
async function pageSettings() {
  const s = await api('GET', '/api/settings');
  S.settings = s;
  const ro = canManage() ? '' : 'disabled';
  view().innerHTML = `
    <div class="page-head">
      <div><h1>Rates &amp; goals</h1><p>Electricity rates (tariff), peak hours and your monthly targets. ${canManage() ? '' : 'Only the energy manager or admin can change these.'}</p></div>
    </div>
    <div class="split">
      <form class="card card-pad" id="setForm" novalidate>
        <fieldset><legend>Home and goals</legend>
          <div class="grid3">
            <div class="field"><label for="sh">Home / office name</label><input id="sh" name="homeName" maxlength="60" value="${esc(s.homeName)}" ${ro}></div>
            <div class="field"><label for="st">Monthly units target (kWh)</label><input id="st" name="monthlyTargetKwh" type="number" min="0" value="${esc(s.monthlyTargetKwh)}" ${ro}></div>
            <div class="field"><label for="sb">Monthly bill budget (₹)</label><input id="sb" name="monthlyBudget" type="number" min="0" value="${esc(s.monthlyBudget)}" ${ro}></div>
          </div>
        </fieldset>
        <fieldset><legend>Slab rates (₹ per unit)</legend>
          <p class="hint">The first units are cheapest. Example: 0-100 units at ₹5.50, 101-200 at ₹6.50 ... The last slab is for everything above.</p>
          <table class="slab-table"><thead><tr><th>Units from</th><th>Up to</th><th>Rate ₹/unit</th><th></th></tr></thead><tbody id="slabRows"></tbody></table>
          ${canManage() ? '<button type="button" class="small" id="addSlab">+ Add slab</button>' : ''}
          <div class="grid2"><div class="field"><label for="sf">Fixed charge per month (₹)</label><input id="sf" name="fixedCharge" type="number" min="0" step="any" value="${esc(s.fixedCharge)}" ${ro}></div>
            <div class="field"><label for="sd">Electricity duty (% of energy charge)</label><input id="sd" name="dutyPercent" type="number" min="0" max="50" step="any" value="${esc(s.dutyPercent)}" ${ro}></div></div>
        </fieldset>
        <fieldset><legend>Time of day (ToD) rates</legend>
          <div class="grid3">
            <div class="field"><label for="ps">Peak from</label><select id="ps" name="peakStart" ${ro}>${hourOptions(s.peakStart)}</select></div>
            <div class="field"><label for="pe">Peak until</label><select id="pe" name="peakEnd" ${ro}>${hourOptions(s.peakEnd)}</select></div>
            <div class="field"><label for="pp">Peak extra (%)</label><input id="pp" name="peakSurchargePercent" type="number" min="0" max="100" step="any" value="${esc(s.peakSurchargePercent)}" ${ro}></div>
            <div class="field"><label for="os">Off-peak from</label><select id="os" name="offPeakStart" ${ro}>${hourOptions(s.offPeakStart)}</select></div>
            <div class="field"><label for="oe">Off-peak until</label><select id="oe" name="offPeakEnd" ${ro}>${hourOptions(s.offPeakEnd)}</select></div>
            <div class="field"><label for="op">Off-peak discount (%)</label><input id="op" name="offPeakRebatePercent" type="number" min="0" max="100" step="any" value="${esc(s.offPeakRebatePercent)}" ${ro}></div>
          </div>
          <p class="hint">Set both percentages to 0 if your connection has no time-of-day rates.</p>
        </fieldset>
        <fieldset><legend>Environment</legend>
          <div class="field"><label for="sc">kg of CO₂ per unit from the grid</label><input id="sc" name="co2PerKwh" type="number" min="0" max="2" step="0.01" value="${esc(s.co2PerKwh)}" ${ro}>
          <div class="hint">India's grid average is about 0.72 kg CO₂ per kWh (Central Electricity Authority).</div></div>
        </fieldset>
        <p class="err-text small" id="setErr" role="alert"></p>
        ${canManage() ? '<button class="primary" type="submit">Save rates and goals</button>' : ''}
      </form>
      <section class="card">
        <div class="card-head"><h2>Bill calculator</h2></div>
        <div class="card-body">
          <div class="grid2"><div class="field"><label for="bu">Units in a month</label><input id="bu" type="number" min="0" value="${esc(Math.round(s.monthlyTargetKwh))}"></div>
            <div class="field"><label for="bp">% used in peak hours</label><input id="bp" type="number" min="0" max="100" value="25"></div></div>
          <div id="billOut"></div>
          <p class="hint">Uses the saved rates. Save first after changing them.</p>
        </div>
      </section>
    </div>`;
  const slabs = s.slabs.map(x => ({ ...x }));
  const drawSlabs = () => {
    let from = 0;
    $('#slabRows').innerHTML = slabs.map((x, i) => {
      const last = i === slabs.length - 1;
      const row = `<tr><td class="num">${num(from + (i ? 1 : 0), 0)}</td>
        <td>${last ? '<span class="muted small">and above</span>' : `<input type="number" min="1" data-slab="${i}" data-k="upTo" value="${esc(x.upTo)}" aria-label="Slab ${i + 1} up to" ${ro}>`}</td>
        <td><input type="number" min="0" step="0.01" data-slab="${i}" data-k="rate" value="${esc(x.rate)}" aria-label="Slab ${i + 1} rate" ${ro}></td>
        <td>${canManage() && slabs.length > 1 ? `<button type="button" class="small ghost danger" data-slab-del="${i}" aria-label="Remove slab ${i + 1}">Remove</button>` : ''}</td></tr>`;
      if (!last) from = Number(x.upTo) || from;
      return row;
    }).join('');
    $$('[data-slab]').forEach(inp => inp.addEventListener('input', () => { slabs[inp.dataset.slab][inp.dataset.k] = Number(inp.value); }));
    $$('[data-slab]').forEach(inp => inp.addEventListener('change', drawSlabs));
    $$('[data-slab-del]').forEach(b => b.addEventListener('click', () => { slabs.splice(Number(b.dataset.slabDel), 1); drawSlabs(); }));
  };
  drawSlabs();
  if ($('#addSlab')) $('#addSlab').addEventListener('click', () => {
    const prev = slabs.length > 1 ? Number(slabs[slabs.length - 2].upTo) : 0;
    slabs.splice(slabs.length - 1, 0, { upTo: prev + 100, rate: slabs[slabs.length - 1].rate });
    drawSlabs();
  });
  $('#setForm').addEventListener('submit', e => {
    e.preventDefault();
    const btn = e.submitter;
    run(btn, async () => {
      $('#setErr').textContent = '';
      const d = Object.fromEntries(new FormData($('#setForm')));
      d.slabs = slabs.map((x, i) => ({ upTo: i === slabs.length - 1 ? 0 : Number(x.upTo), rate: Number(x.rate) }));
      try { S.settings = await api('PUT', '/api/settings', d); } catch (err) { $('#setErr').textContent = err.message; throw err; }
      $('#homeName').textContent = S.settings.homeName;
      toast('Rates and goals saved'); billCalc(); rateChip();
    });
  });
  const billCalc = async () => {
    const r = await api('GET', `/api/settings/bill?units=${Number($('#bu').value) || 0}&peak=${Number($('#bp').value) || 0}`);
    $('#billOut').innerHTML = `<table class="bill-lines"><tbody>
      ${r.lines.map(l => `<tr><td>${num(l.units)} units × ${money2(l.rate)} <span class="tiny muted">(${l.to == null ? 'above ' + num(l.from, 0) : num(l.from, 0) + '-' + num(l.to, 0)})</span></td><td class="num">${money2(l.amount)}</td></tr>`).join('')}
      <tr><td>Peak-hour extra</td><td class="num">+ ${money2(r.peakExtra)}</td></tr>
      <tr><td>Duty</td><td class="num">+ ${money2(r.duty)}</td></tr>
      <tr><td>Fixed charge</td><td class="num">+ ${money2(r.fixedCharge)}</td></tr>
      <tr class="total"><td>Bill</td><td class="num">${money2(r.total)}</td></tr></tbody></table>
      <p class="small muted">Average ${money2(r.units ? (r.total - r.fixedCharge) / r.units : 0)} per unit.</p>`;
  };
  $('#bu').addEventListener('input', billCalc); $('#bp').addEventListener('input', billCalc);
  billCalc();
}

// ------------------------------------------------------------------ team (admin)
async function pageTeam() {
  if (S.user.role !== 'ADMIN') { view().innerHTML = '<div class="card card-pad">Only the admin can manage the team.</div>'; return; }
  S.users = await api('GET', '/api/users');
  view().innerHTML = `
    <div class="page-head"><div><h1>Team</h1><p>People who can use the app. Members switch appliances and log usage; energy managers also change appliances, rates and apply tips.</p></div>
      <div class="spacer"></div><button class="primary" id="addUser" type="button">+ Add person</button></div>
    <section class="card"><div class="table-wrap"><table>
      <thead><tr><th>Name</th><th>Username</th><th>Role</th><th>Status</th><th></th></tr></thead>
      <tbody>${S.users.map(u => `<tr><td>${esc(u.displayName)}</td><td>${esc(u.username)}</td><td>${esc(u.roleLabel)}</td>
        <td>${u.active ? '<span class="badge good">Active</span>' : '<span class="badge">Turned off</span>'}</td>
        <td><button class="small" data-user="${esc(u.username)}">Edit</button></td></tr>`).join('')}</tbody></table></div></section>`;
  $('#addUser').addEventListener('click', () => userForm(null));
  $$('[data-user]').forEach(b => b.addEventListener('click', () => userForm(S.users.find(u => u.username === b.dataset.user))));
}

function userForm(u) {
  modal.open({
    title: u ? 'Edit ' + u.displayName : 'Add a person',
    body: `
      <div class="grid2">
        <div class="field"><label for="tn">Name</label><input id="tn" name="displayName" maxlength="60" required value="${esc(u ? u.displayName : '')}"></div>
        <div class="field"><label for="tu">Username</label><input id="tu" name="username" maxlength="30" ${u ? 'disabled' : 'required'} value="${esc(u ? u.username : '')}" autocomplete="off">
          ${u ? '' : '<div class="hint">3-30 small letters, numbers, . _ -</div>'}</div>
        <div class="field"><label for="tr">Role</label><select id="tr" name="role">${options(S.meta.roles, u ? u.role : 'MEMBER')}</select></div>
        <div class="field"><label for="tp">${u ? 'New password (leave empty to keep)' : 'Password'}</label><input id="tp" name="password" type="password" minlength="8" autocomplete="new-password" ${u ? '' : 'required'}></div>
      </div>
      ${u ? `<label class="check"><input type="checkbox" name="active" ${u.active ? 'checked' : ''}> Account is active (can sign in)</label>` : ''}`,
    onSubmit: async d => {
      if (u) await api('PUT', '/api/users/' + encodeURIComponent(u.username), d); else await api('POST', '/api/users', d);
      toast('Saved'); pageTeam();
    }
  });
}

// ------------------------------------------------------------------ start
// Re-drawing a page that the person already left is simply ignored; other surprises are shown as a message.
window.addEventListener('unhandledrejection', e => {
  if (e.reason instanceof StaleError) { e.preventDefault(); return; }
  if (e.reason && e.reason.message) toast(e.reason.message, true);
});
document.addEventListener('visibilitychange', () => { if (!document.hidden && S.user) rateChip(); });
boot();
