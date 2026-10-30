/* NGO Management System - frontend.
   Plain JavaScript, no frameworks. Every piece of data is escaped with esc() before it goes into HTML,
   and no inline styles or scripts are used (the server's Content-Security-Policy blocks them).
   Buttons and links carry data-act="name"; one click listener runs ACT[name] (event delegation). */
'use strict';

const S = { token: null, user: null, meta: null, routeSeq: 0, route: '', query: {} };
const $ = (sel, root = document) => root.querySelector(sel);
const $$ = (sel, root = document) => Array.from(root.querySelectorAll(sel));
const view = () => $('#view');
const role = () => (S.user ? S.user.role : '');
const isAdmin = () => role() === 'ADMIN';
const canPrograms = () => role() === 'ADMIN' || role() === 'MANAGER';
const canFinance = () => role() === 'ADMIN' || role() === 'FINANCE';
const isStaff = () => role() !== 'VOLUNTEER';

// ================================================================== small helpers
const store = {
  get(k) { try { return sessionStorage.getItem(k); } catch { return null; } },
  set(k, v) { try { v == null ? sessionStorage.removeItem(k) : sessionStorage.setItem(k, v); } catch { /* ignore */ } },
  getLocal(k) { try { return localStorage.getItem(k); } catch { return null; } },
  setLocal(k, v) { try { localStorage.setItem(k, v); } catch { /* ignore */ } }
};

function esc(v) {
  return String(v ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}
/** ₹1,23,456 (Indian grouping). */
const rs = n => '₹' + Number(n || 0).toLocaleString('en-IN');
/** Short money for tiles: ₹13.5 L, ₹1.2 Cr. */
function rsShort(n) {
  n = Number(n || 0);
  const a = Math.abs(n), sign = n < 0 ? '-' : '';
  if (a >= 1e7) return sign + '₹' + (a / 1e7).toFixed(2).replace(/\.?0+$/, '') + ' Cr';
  if (a >= 1e5) return sign + '₹' + (a / 1e5).toFixed(1).replace(/\.0$/, '') + ' L';
  return rs(n);
}
const plural = (n, w, many) => n + ' ' + (n === 1 ? w : (many || w + 's'));
const label = (list, id) => esc(((S.meta[list] || []).find(x => x.id === id) || {}).label || id || '');
const programOf = id => (S.meta.programs || []).find(p => p.id === id);
const programName = id => esc((programOf(id) || {}).name || id || 'General fund');
const userName = u => { const x = (S.meta.users || []).find(a => a.username === u); return esc(x ? x.displayName : (u || '')); };
const initials = name => esc(String(name || '?').replace(/^(Dr|Md)\.?\s+/i, '').split(/\s+/).filter(Boolean).slice(0, 2).map(w => w[0]).join('').toUpperCase());
const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
const DAYS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];

function parseDate(iso) { return new Date(String(iso).length === 10 ? iso + 'T00:00:00' : iso); }
function dateText(iso) {
  if (!iso) return '-';
  const d = parseDate(iso);
  return isNaN(d) ? esc(iso) : d.getDate() + ' ' + MONTHS[d.getMonth()] + ' ' + d.getFullYear();
}
function when(iso) {
  if (!iso) return '';
  const d = parseDate(iso);
  return isNaN(d) ? esc(iso) : d.getDate() + ' ' + MONTHS[d.getMonth()] + ', ' + String(d.getHours()).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0');
}
function todayStr(offset = 0) {
  const d = new Date(); d.setDate(d.getDate() + offset);
  return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0');
}
function daysFromToday(iso) { return Math.round((parseDate(iso) - parseDate(todayStr())) / 86400000); }
function relDay(iso) {
  const n = daysFromToday(iso);
  if (n === 0) return 'today'; if (n === 1) return 'tomorrow'; if (n === -1) return 'yesterday';
  return n > 0 ? 'in ' + n + ' days' : -n + ' days ago';
}
function greet() { const h = new Date().getHours(); return h < 12 ? 'Good morning' : h < 17 ? 'Good afternoon' : 'Good evening'; }
const chip = (st, text) => `<span class="chip st-${esc(st)}">${esc(text || st)}</span>`;
const statusChip = (list, st) => chip(st, ((S.meta[list] || []).find(x => x.id === st) || {}).label || st);
const causeTag = c => `<span class="cause ${esc(c)}">${label('causes', c)}</span>`;
const clamp = v => Math.max(0, Math.min(100, Number(v) || 0));
const flower = '<svg viewBox="0 0 40 40"><use href="/static/favicon.svg#flower"/></svg>';

function toast(msg, err = false) {
  const t = document.createElement('div');
  t.className = 'toast' + (err ? ' err' : '');
  t.setAttribute('role', 'status');
  t.textContent = msg;
  document.body.appendChild(t);
  setTimeout(() => t.remove(), err ? 5000 : 2600);
}

/** Sets sizes from data-w (width %) and data-h (height %) after the HTML is placed. CSP-safe (no inline styles). */
function paint(root = document) {
  requestAnimationFrame(() => {
    $$('[data-w]', root).forEach(e => { e.style.width = clamp(e.dataset.w) + '%'; });
    $$('[data-h]', root).forEach(e => { e.style.height = clamp(e.dataset.h) + '%'; });
  });
}
const bar = (pct, cls = '') => `<div class="bar ${cls}"><i data-w="${clamp(pct)}"></i></div>`;
const budgetClass = used => used >= 100 ? 'hot' : used >= 90 ? 'warmish' : '';
const emptyBox = text => `<div class="empty">${flower}${esc(text)}</div>`;

function options(list, selected, blank) {
  let h = blank != null ? `<option value="">${esc(blank)}</option>` : '';
  for (const o of list) {
    const id = typeof o === 'object' ? (o.id ?? o.username) : o;
    const lb = typeof o === 'object' ? (o.label ?? o.name ?? o.displayName) : o;
    h += `<option value="${esc(id)}" ${String(id) === String(selected ?? '') ? 'selected' : ''}>${esc(lb)}</option>`;
  }
  return h;
}
const programOptions = (sel, blank, openOnly) => options((S.meta.programs || []).filter(p => !openOnly || p.status !== 'COMPLETED'), sel, blank);

// ================================================================== talking to the server
class StaleError extends Error {}
class ApiErr extends Error { constructor(msg, status) { super(msg); this.status = status; } }

async function api(method, path, body) {
  const seq = S.routeSeq;
  const opt = { method, headers: {} };
  if (S.token) opt.headers.Authorization = 'Bearer ' + S.token;
  if (body !== undefined) { opt.headers['Content-Type'] = 'application/json'; opt.body = JSON.stringify(body); }
  let res;
  try { res = await fetch(path, opt); } catch { throw new ApiErr('Cannot reach the server. Is it still running?', 0); }
  if (res.status === 401 && path !== '/api/auth/login') { signOut(true); throw new ApiErr('Your session ended. Please sign in again.', 401); }
  const ct = res.headers.get('content-type') || '';
  const data = ct.includes('json') ? await res.json() : await res.text();
  if (!res.ok) throw new ApiErr((data && data.error) || 'Request failed (' + res.status + ')', res.status);
  if (method === 'GET' && seq !== S.routeSeq) throw new StaleError('page changed');
  return data;
}
const qs = obj => { const p = new URLSearchParams(); for (const [k, v] of Object.entries(obj || {})) if (v !== '' && v != null) p.set(k, v); const s = p.toString(); return s ? '?' + s : ''; };

/** Runs an action with the button disabled; shows any error as a red toast. */
async function run(btn, fn) {
  if (btn) btn.disabled = true;
  try { return await fn(); } catch (e) { if (!(e instanceof StaleError)) toast(e.message, true); } finally { if (btn) btn.disabled = false; }
}

/** Downloads a file from the API (the token goes in a header, so a plain link would not work). */
async function download(path, fallbackName) {
  const res = await fetch(path, { headers: { Authorization: 'Bearer ' + S.token } });
  if (!res.ok) { let m = 'Download failed'; try { m = (await res.json()).error || m; } catch { /* not JSON */ } throw new Error(m); }
  const cd = res.headers.get('content-disposition') || '';
  const name = (cd.match(/filename="([^"]+)"/) || [])[1] || fallbackName;
  const url = URL.createObjectURL(await res.blob());
  const a = document.createElement('a');
  a.href = url; a.download = name;
  document.body.appendChild(a); a.click(); a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 2000);
}

// ================================================================== the form dialog
/**
 * Opens the dialog. onSave(data, form) is called with the form values; if it throws, the error shows
 * in the dialog and it stays open. Inputs with data-multi are collected as arrays (ticked boxes).
 */
function modal(title, bodyHtml, saveLabel, onSave, opts = {}) {
  const dlg = $('#modal');
  $('#modalTitle').textContent = title;
  $('#modalBody').innerHTML = bodyHtml;
  $('#modalErr').textContent = '';
  const foot = $('#modalFoot');
  foot.innerHTML = (opts.extra || '') + `<button type="button" class="btn quiet" data-close>${esc(opts.cancelLabel || 'Cancel')}</button>`
    + (saveLabel ? `<button type="submit" class="btn primary" id="modalSave">${esc(saveLabel)}</button>` : '');
  const form = $('#modalForm');
  form.onsubmit = async e => {
    e.preventDefault();
    if (!onSave) { dlg.close(); return; }
    const btn = $('#modalSave');
    if (btn) btn.disabled = true;
    $('#modalErr').textContent = '';
    try {
      const keep = await onSave(readForm(form), form);
      if (keep !== true) dlg.close();
    } catch (err) {
      if (!(err instanceof StaleError)) $('#modalErr').textContent = err.message;
    } finally { if (btn) btn.disabled = false; }
  };
  $$('[data-close]', foot).forEach(b => b.addEventListener('click', () => dlg.close()));
  if (!dlg.open) dlg.showModal();
  paint(dlg);
  const first = $('#modalBody input:not([type=hidden]):not([type=checkbox]), #modalBody select, #modalBody textarea');
  if (first && !opts.noFocus) first.focus();
  if (opts.after) opts.after(form);
}
$('#modalClose').addEventListener('click', () => $('#modal').close());

function readForm(form) {
  const out = {};
  for (const el of form.elements) {
    if (!el.name) continue;
    if (el.dataset.multi != null) { (out[el.name] = out[el.name] || []); if (el.checked) out[el.name].push(el.value); continue; }
    if (el.type === 'checkbox') { out[el.name] = el.checked; continue; }
    if (el.type === 'number') { out[el.name] = el.value === '' ? null : Number(el.value); continue; }
    out[el.name] = el.value.trim();
  }
  return out;
}
const field = (lbl, inner, hint) => `<label class="field">${esc(lbl)}${inner}${hint ? `<span class="hint">${esc(hint)}</span>` : ''}</label>`;
const input = (name, value, attrs = '') => `<input name="${name}" value="${esc(value ?? '')}" ${attrs}>`;

// ================================================================== sign in / out
function showLogin() {
  $('#app').classList.add('hidden');
  $('#login').classList.remove('hidden');
  setTimeout(() => $('#lu').focus(), 30);
}

$('#loginForm').addEventListener('submit', async e => {
  e.preventDefault();
  $('#loginErr').textContent = '';
  const btn = e.submitter || $('#loginForm button[type=submit]');
  btn.disabled = true;
  try {
    const r = await api('POST', '/api/auth/login', { username: $('#lu').value.trim(), password: $('#lp').value });
    S.token = r.token; S.user = r.user;
    store.set('ngo-token', r.token);
    $('#lp').value = '';
    await startApp();
  } catch (err) { $('#loginErr').textContent = err.message; } finally { btn.disabled = false; }
});
$$('[data-demo]').forEach(b => b.addEventListener('click', () => {
  const [u, p] = b.dataset.demo.split(':');
  $('#lu').value = u; $('#lp').value = p; $('#lp').focus();
}));

function signOut(silent) {
  if (S.token && !silent) fetch('/api/auth/logout', { method: 'POST', headers: { Authorization: 'Bearer ' + S.token } }).catch(() => {});
  S.token = null; S.user = null; S.meta = null;
  store.set('ngo-token', null);
  if ($('#modal').open) $('#modal').close();
  showLogin();
}
$('#logoutBtn').addEventListener('click', () => signOut(false));

async function loadMeta() {
  S.meta = await api('GET', '/api/meta');
  const org = S.meta.settings.orgName;
  $('#orgName').textContent = org;
  $('#foot').textContent = org + (S.meta.settings.regNo ? ' · Reg. No. ' + S.meta.settings.regNo : '') + ' · NGO Management System';
  document.title = org + ' · NGO Management System';
}

async function startApp() {
  await loadMeta();
  $('#login').classList.add('hidden');
  $('#app').classList.remove('hidden');
  $('#who').textContent = S.user.displayName + ' · ' + S.user.roleLabel;
  buildNav();
  if (!location.hash || location.hash === '#/' || location.hash === '#') location.hash = '#/home';
  else route();
}

function buildNav() {
  const items = isStaff()
    ? [['home', 'Home'], ['programs', 'Programs'], ['activities', 'Activities'], ['volunteers', 'Volunteers'], ['hours', 'Hours', 'navHours'],
       ['donations', 'Donations'], ['expenses', 'Expenses', 'navExp'], ...(canPrograms() ? [['beneficiaries', 'Beneficiaries']] : []),
       ['report', 'Impact report'], ...(isAdmin() ? [['team', 'Team'], ['settings', 'Settings']] : []), ['history', 'History']]
    : [['home', 'Home'], ['activities', 'Find activities'], ['hours', 'My hours'], ['me', 'My profile']];
  $('#nav').innerHTML = items.map(([k, t, badge]) =>
    `<a href="#/${k}" data-nav="${k}">${esc(t)}${badge ? `<span class="badge hidden" id="${badge}"></span>` : ''}</a>`).join('');
}

/** Little counters on the tabs: hours and expenses waiting for this person. */
async function refreshBadges() {
  if (!isStaff()) return;
  try {
    const set = (id, n) => { const e = $('#' + id); if (!e) return; e.textContent = n; e.classList.toggle('hidden', !n); };
    if (canPrograms()) set('navHours', (await api('GET', '/api/hours?status=PENDING')).length);
    if (canFinance()) set('navExp', (await api('GET', '/api/expenses?status=PENDING')).filter(e => e.submittedBy !== S.user.username).length);
  } catch { /* badges are optional */ }
}

// ---- light / dark
function currentTheme() {
  const t = document.documentElement.getAttribute('data-theme');
  if (t) return t;
  return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
}
function paintThemeButtons() { $$('[data-theme-toggle]').forEach(b => { b.textContent = currentTheme() === 'dark' ? 'Light mode' : 'Dark mode'; }); }
$$('[data-theme-toggle]').forEach(b => b.addEventListener('click', () => {
  const next = currentTheme() === 'dark' ? 'light' : 'dark';
  document.documentElement.setAttribute('data-theme', next);
  store.setLocal('ngo-theme', next);
  paintThemeButtons();
}));
paintThemeButtons();

$('#who').addEventListener('click', () => {
  modal('Change password',
    field('Current password', '<input name="current" type="password" autocomplete="current-password" required>')
    + field('New password', '<input name="next" type="password" autocomplete="new-password" required>', 'At least 8 characters with a letter and a number'),
    'Change password', async d => { await api('POST', '/api/auth/password', d); toast('Password changed'); });
});

// ================================================================== router
const ROUTES = {
  home: () => isStaff() ? pageHome() : pageVolunteerHome(),
  programs: pagePrograms, program: pageProgram,
  activities: pageActivities, activity: pageActivity,
  volunteers: pageVolunteers, volunteer: pageVolunteer, me: () => pageVolunteer(S.user.volunteerId),
  hours: pageHours,
  donations: pageDonations, donors: pageDonors, donor: pageDonor,
  expenses: pageExpenses, beneficiaries: pageBeneficiaries,
  report: pageReport, team: pageTeam, settings: pageSettings, history: pageHistory
};

async function route() {
  if (!S.token) return;
  const raw = location.hash.replace(/^#\/?/, '');
  const [path, query] = raw.split('?');
  const [name, id] = path.split('/');
  S.route = name || 'home';
  S.query = Object.fromEntries(new URLSearchParams(query || ''));
  S.routeSeq++;
  $$('#nav a').forEach(a => a.classList.toggle('on', a.dataset.nav === S.route
    || (S.route === 'program' && a.dataset.nav === 'programs') || (S.route === 'activity' && a.dataset.nav === 'activities')
    || (S.route === 'volunteer' && a.dataset.nav === (isStaff() ? 'volunteers' : 'me'))
    || ((S.route === 'donor' || S.route === 'donors') && a.dataset.nav === 'donations')));
  const fn = ROUTES[S.route];
  view().innerHTML = '<p class="muted">Loading…</p>';
  if (!fn) { view().innerHTML = emptyBox('Page not found.'); return; }
  try {
    await fn(id ? decodeURIComponent(id) : undefined);
    paint(view());
    window.scrollTo(0, 0);
  } catch (e) {
    if (e instanceof StaleError) return;
    view().innerHTML = `<div class="card">${emptyBox(e.message)}</div>`;
  }
  refreshBadges();
}
window.addEventListener('hashchange', route);
const go = h => { if (location.hash === h) route(); else location.hash = h; };

// ================================================================== click actions (event delegation)
const ACT = {};
document.addEventListener('click', e => {
  const el = e.target.closest('[data-act]');
  if (!el || !ACT[el.dataset.act]) return;
  e.preventDefault();
  ACT[el.dataset.act](el);
});
document.addEventListener('click', e => {
  const row = e.target.closest('tr[data-href]');
  if (row && !e.target.closest('button, a')) location.hash = row.dataset.href;
});

// ================================================================== HOME (staff)
async function pageHome() {
  const d = await api('GET', '/api/dashboard');
  const k = d.kpis;
  const maxM = Math.max(1, ...d.months.map(m => Math.max(m.in, m.out)));
  view().innerHTML = `
    <div class="hello"><span class="mark">${flower}</span>
      <div><h1>${greet()}, ${esc(S.user.displayName.split(' ')[0])}</h1>
      <p class="muted m0">${esc(S.user.roleLabel)} · financial year ${esc(k.financialYear)}</p></div></div>
    <div class="kpis">
      <div class="kpi k-in"><div class="lbl">Raised this year</div><div class="val">${rsShort(k.raisedFy)}</div><div class="note">donations since 1 April</div></div>
      <div class="kpi k-out"><div class="lbl">Spent this year</div><div class="val">${rsShort(k.spentFy)}</div><div class="note">approved expenses</div></div>
      <div class="kpi k-bal"><div class="lbl">Funds in hand</div><div class="val">${rsShort(k.balance)}</div><div class="note">all money in − all money out</div></div>
      <div class="kpi k-ppl"><div class="lbl">People reached</div><div class="val">${Number(k.reached).toLocaleString('en-IN')}</div><div class="note">at ${plural(k.activePrograms, 'active program')}</div></div>
      <div class="kpi k-vol"><div class="lbl">Volunteer hours</div><div class="val">${k.hoursMonth}</div><div class="note">this month · ${k.activeVolunteers} active volunteers</div></div>
    </div>
    <div class="cols">
      <div class="stack">
        <section class="card">
          <div class="card-head"><h2>Coming up</h2><a class="btn sm" href="#/activities">All activities</a></div>
          ${d.upcoming.length ? `<div class="agenda">${d.upcoming.map(ticket).join('')}</div>` : emptyBox('Nothing planned yet.')}
        </section>
        <section class="card">
          <div class="card-head"><h2>Money in and out</h2><div class="legend"><span><i class="in"></i>Donations</span><span><i class="out"></i>Approved spending</span></div></div>
          <div class="months">${d.months.map(m => {
            const mm = Number(m.month.slice(5)) - 1;
            return `<div class="m" title="${esc(MONTHS[mm] + ' ' + m.month.slice(0, 4))}: in ${rs(m.in)}, out ${rs(m.out)}">
              <div class="pair"><i class="in" data-h="${m.in / maxM * 100}"></i><i class="out" data-h="${m.out / maxM * 100}"></i></div>
              <span>${MONTHS[mm].slice(0, 1)}${MONTHS[mm].slice(1, 3)}</span></div>`; }).join('')}</div>
        </section>
        <section class="card">
          <div class="card-head"><h2>Active programs</h2><a class="btn sm" href="#/programs">All programs</a></div>
          <div class="programs">${d.programs.map(programCard).join('')}</div>
        </section>
      </div>
      <div class="stack">
        <section class="card">
          <div class="card-head"><h2>Needs attention</h2></div>
          ${d.todo.length ? `<ul class="todo">${d.todo.map(t => `<li><a href="${esc(t.link)}"><span class="dot ${esc(t.kind)}">${t.kind === 'budget' ? '!' : esc(t.count)}</span><span>${esc(t.text)}</span></a></li>`).join('')}</ul>`
            : emptyBox('All clear. Nothing is waiting for you.')}
        </section>
        <section class="card">
          <div class="card-head"><h2>Quick add</h2></div>
          <div class="actions">
            ${canPrograms() ? '<button class="btn" data-act="newActivity">Plan activity</button><button class="btn" data-act="newVolunteer">Add volunteer</button>' : ''}
            ${canFinance() ? '<button class="btn" data-act="newDonation">Record donation</button>' : ''}
            <button class="btn" data-act="newExpense">Submit expense</button>
          </div>
        </section>
        <section class="card">
          <div class="card-head"><h2>Recent activity</h2><a class="btn sm quiet" href="#/history">History</a></div>
          ${feed(d.recent)}
        </section>
      </div>
    </div>`;
}

function feed(list) {
  if (!list.length) return emptyBox('No history yet.');
  return `<ul class="feed">${list.map(e => `<li><span class="avatar">${initials(e.userName)}</span>
    <div><b>${esc(e.userName)}</b> ${esc(e.action)}<div class="muted small">${esc(e.detail)}</div><div class="when">${when(e.at)}</div></div></li>`).join('')}</ul>`;
}

function programCard(p) {
  return `<a class="pcard ${esc(p.cause)}" href="#/program/${esc(p.id)}">
    <div class="top">${causeTag(p.cause)}${statusChip('programStatuses', p.status)}</div>
    <h3>${esc(p.name)}</h3><div class="where">${esc(p.location)}</div>
    <div class="meter"><div class="row"><span>Budget used</span><b>${rs(p.spent)} of ${rs(p.budget)}</b></div>${bar(p.used, budgetClass(p.used))}</div>
    <div class="meter"><div class="row"><span>People reached</span><b>${Number(p.reached).toLocaleString('en-IN')} / ${Number(p.targetBeneficiaries).toLocaleString('en-IN')}</b></div>${bar(p.reachPct, 'reach')}</div>
    <div class="facts"><span><b>${p.done}</b> done</span><span><b>${p.planned}</b> planned</span><span><b>${p.hours}</b> vol. hours</span></div>
  </a>`;
}

function seatsDots(a) {
  const n = Math.min(a.volunteersNeeded, 12);
  let h = '';
  for (let i = 0; i < n; i++) h += `<i class="${i < a.signedUp ? 'f' : ''}"></i>`;
  return `<span class="seats" title="${a.signedUp} of ${a.volunteersNeeded} volunteers">${h}</span>`;
}

/** One activity as a "ticket" with a coloured date stub. */
function ticket(a) {
  const d = parseDate(a.date);
  const vol = !isStaff();
  let side = '';
  if (a.status === 'PLANNED') {
    side = `<span class="small muted">${a.seatsLeft ? plural(a.seatsLeft, 'seat') + ' left' : 'full'}</span>${seatsDots(a)}`;
    if (vol) side += a.iAmIn ? `<button class="btn sm" data-act="leave" data-id="${esc(a.id)}">Leave</button>`
      : a.seatsLeft ? `<button class="btn sm primary" data-act="join" data-id="${esc(a.id)}">Sign up</button>` : '';
  } else {
    side = statusChip('activityStatuses', a.status) + (a.status === 'DONE' ? `<span class="small muted">${Number(a.reached).toLocaleString('en-IN')} reached</span>` : '');
  }
  if (vol && a.iAmIn) side = '<span class="chip ok">You are in</span>' + side;
  return `<div class="ticket ${esc(a.cause)} is-${esc(a.status)}">
    <div class="date"><span>${DAYS[d.getDay()]}</span><b>${d.getDate()}</b><small>${MONTHS[d.getMonth()]}</small></div>
    <div><h3><a href="#/activity/${esc(a.id)}">${esc(a.title)}</a></h3>
      <div class="meta">${causeTag(a.cause)}<span>${esc(a.programName)}</span><span>${esc(a.place)}</span>${a.time ? `<span>${esc(a.time)}</span>` : ''}<span class="muted">${relDay(a.date)}</span></div></div>
    <div class="side">${side}</div></div>`;
}

// ================================================================== HOME (volunteer)
async function pageVolunteerHome() {
  const d = await api('GET', '/api/dashboard');
  const v = d.volunteer || { name: S.user.displayName };
  view().innerHTML = `
    <div class="hello"><span class="mark">${flower}</span>
      <div><h1>${greet()}, ${esc(v.name.split(' ')[0])}</h1><p class="muted m0">Thank you for giving your time.</p></div></div>
    <div class="kpis">
      <div class="kpi k-vol"><div class="lbl">Hours given</div><div class="val">${d.hours}</div><div class="note">approved${d.pendingHours ? ` · ${d.pendingHours} waiting` : ''}</div></div>
      <div class="kpi k-ppl"><div class="lbl">Activities done</div><div class="val">${d.activitiesDone}</div><div class="note">since you joined</div></div>
      <div class="kpi"><div class="lbl">Coming up</div><div class="val">${d.mine.length}</div><div class="note">activities you signed up for</div></div>
    </div>
    ${d.toLog.length ? `<div class="callout info"><b>Log your hours:</b> ${d.toLog.map(a => esc(a.title) + ' (' + dateText(a.date) + ')').join(', ')}.
      <button class="btn sm" data-act="logHours">Log hours</button></div>` : ''}
    <div class="cols even">
      <section class="card"><div class="card-head"><h2>My upcoming activities</h2></div>
        ${d.mine.length ? `<div class="agenda">${d.mine.map(ticket).join('')}</div>` : emptyBox('You have not signed up for anything yet.')}</section>
      <section class="card"><div class="card-head"><h2>Volunteers needed</h2><a class="btn sm" href="#/activities">See all</a></div>
        ${d.open.length ? `<div class="agenda">${d.open.map(ticket).join('')}</div>` : emptyBox('No open activities right now.')}</section>
    </div>`;
}

ACT.join = el => run(el, async () => { await api('POST', `/api/activities/${el.dataset.id}/signup`, {}); toast('You are signed up. See you there!'); route(); });
ACT.leave = el => run(el, async () => {
  if (!confirm('Leave this activity?')) return;
  await api('POST', `/api/activities/${el.dataset.id}/withdraw`, {}); toast('You have left the activity'); route();
});

// ================================================================== PROGRAMS
async function pagePrograms() {
  const q = { status: S.query.status || '', cause: S.query.cause || '', q: S.query.q || '' };
  const list = await api('GET', '/api/programs' + qs(q));
  view().innerHTML = `
    <div class="page-head"><div><h1>Programs</h1><p class="sub">Each program has a budget, a target number of people and its own activities.</p></div>
      <div class="actions">${canPrograms() ? '<button class="btn primary" data-act="newProgram">New program</button>' : ''}</div></div>
    <div class="filters" id="pf">
      <input type="search" name="q" placeholder="Search programs" value="${esc(q.q)}">
      <select name="cause">${options(S.meta.causes, q.cause, 'All causes')}</select>
      <select name="status">${options(S.meta.programStatuses, q.status, 'Any status')}</select>
    </div>
    ${list.length ? `<div class="programs">${list.map(programCard).join('')}</div>` : `<div class="card">${emptyBox('No programs match.')}</div>`}`;
  filterBar('#pf', 'programs');
}

/** Filter inputs update the address (so Back works and the view can be shared). */
function filterBar(sel, page, extra = {}) {
  const box = $(sel);
  if (!box) return;
  const apply = () => {
    const q = { ...extra };
    $$('input, select', box).forEach(el => { if (el.value) q[el.name] = el.value; });
    go('#/' + page + qs(q));
  };
  let t;
  $$('select, input[type=date]', box).forEach(el => el.addEventListener('change', apply));
  $$('input[type=search]', box).forEach(el => el.addEventListener('input', () => { clearTimeout(t); t = setTimeout(apply, 350); }));
  const s = $('input[type=search]', box);
  if (s && document.activeElement !== s && S.query.q) { s.focus(); s.setSelectionRange(s.value.length, s.value.length); }
}

async function pageProgram(id) {
  const p = await api('GET', '/api/programs/' + encodeURIComponent(id));
  const cats = Object.entries(p.byCategory);
  const maxCat = Math.max(1, ...cats.map(c => c[1]));
  view().innerHTML = `
    <div class="crumb"><a href="#/programs">Programs</a> / ${esc(p.id)}</div>
    <div class="page-head"><div><div class="actions">${causeTag(p.cause)}${statusChip('programStatuses', p.status)}</div>
      <h1>${esc(p.name)}</h1><p class="sub">${esc(p.location)} · ${dateText(p.startDate)} to ${p.endDate ? dateText(p.endDate) : 'ongoing'} · lead ${userName(p.lead)}</p></div>
      <div class="actions">
        ${canPrograms() && p.status !== 'COMPLETED' ? `<button class="btn primary" data-act="newActivity" data-program="${esc(p.id)}">Plan activity</button>` : ''}
        ${p.status !== 'COMPLETED' ? `<button class="btn" data-act="newExpense" data-program="${esc(p.id)}">Submit expense</button>` : ''}
        ${canPrograms() ? `<button class="btn" data-act="editProgram" data-id="${esc(p.id)}">Edit</button>` : ''}
        ${isAdmin() ? `<button class="btn danger" data-act="deleteProgram" data-id="${esc(p.id)}">Delete</button>` : ''}
      </div></div>
    ${p.description ? `<p class="card">${esc(p.description)}</p>` : ''}
    <div class="kpis">
      <div class="kpi k-out"><div class="lbl">Spent</div><div class="val">${rsShort(p.spent)}</div><div class="note">${p.used}% of ${rs(p.budget)}${p.pendingSpend ? ` · ${rs(p.pendingSpend)} waiting` : ''}</div></div>
      <div class="kpi k-bal"><div class="lbl">Budget left</div><div class="val">${rsShort(p.left)}</div><div class="note">${p.left < 0 ? 'over budget' : 'can still be spent'}</div></div>
      <div class="kpi k-in"><div class="lbl">Donations for it</div><div class="val">${rsShort(p.raised)}</div><div class="note">earmarked gifts</div></div>
      <div class="kpi k-ppl"><div class="lbl">People reached</div><div class="val">${Number(p.reached).toLocaleString('en-IN')}</div><div class="note">${p.reachPct}% of target ${Number(p.targetBeneficiaries).toLocaleString('en-IN')} · ${p.enrolled} enrolled</div></div>
      <div class="kpi k-vol"><div class="lbl">Volunteer hours</div><div class="val">${p.hours}</div><div class="note">${p.done} done · ${p.planned} planned</div></div>
    </div>
    <div class="cols">
      <section class="card"><div class="card-head"><h2>Activities</h2></div>
        ${p.activities.length ? `<div class="agenda">${p.activities.map(ticket).join('')}</div>` : emptyBox('No activities yet.')}</section>
      <div class="stack">
        <section class="card"><div class="card-head"><h3>Budget use</h3></div>${bar(p.used, budgetClass(p.used))}
          <p class="small muted mt">${rs(p.spent)} spent, ${rs(p.pendingSpend)} waiting for approval, ${rs(Math.max(0, p.left))} left.</p></section>
        <section class="card"><div class="card-head"><h3>Spending by category</h3></div>
          ${cats.length ? `<div class="barlist">${cats.map(([c, v]) => `<div class="item"><span>${label('expenseCategories', c)}</span>${bar(v / maxCat * 100, 'thin')}<span class="v">${rsShort(v)}</span></div>`).join('')}</div>` : emptyBox('No approved spending yet.')}</section>
        <section class="card"><div class="card-head"><h3>Latest expenses</h3><a class="btn sm quiet" href="#/expenses?program=${esc(p.id)}">All</a></div>
          ${p.expenses.length ? `<div class="table-wrap"><table><tbody>${p.expenses.map(e => `<tr><td>${esc(e.description)}<span class="sub">${dateText(e.date)}</span></td><td>${statusChip('reviews', e.status)}</td><td class="r amt out">${rs(e.amount)}</td></tr>`).join('')}</tbody></table></div>` : emptyBox('No expenses yet.')}</section>
      </div>
    </div>`;
}

function programForm(p) {
  p = p || { status: 'PLANNED', startDate: todayStr(), lead: canPrograms() && role() === 'MANAGER' ? S.user.username : '' };
  const leads = (S.meta.users || []).filter(u => u.active && (u.role === 'MANAGER' || u.role === 'ADMIN'));
  return field('Program name', input('name', p.name, 'required maxlength="100"'))
    + `<div class="grid2">${field('Cause', `<select name="cause" required>${options(S.meta.causes, p.cause, 'Choose…')}</select>`)}
       ${field('Status', `<select name="status">${options(S.meta.programStatuses, p.status)}</select>`)}</div>`
    + field('Where', input('location', p.location, 'required maxlength="100" placeholder="District / block"'))
    + `<div class="grid2">${field('Start date', input('startDate', p.startDate, 'type="date" required'))}${field('End date', input('endDate', p.endDate, 'type="date"'), 'Leave empty if ongoing')}</div>`
    + `<div class="grid3">${field('Budget (₹)', input('budget', p.budget, 'type="number" min="0" step="1" required'))}
       ${field('Target people', input('targetBeneficiaries', p.targetBeneficiaries, 'type="number" min="0" required'))}
       ${field('Lead', `<select name="lead" required>${options(leads.map(u => ({ id: u.username, label: u.displayName })), p.lead, 'Choose…')}</select>`)}</div>`
    + field('Description', `<textarea name="description" maxlength="1000">${esc(p.description || '')}</textarea>`);
}
ACT.newProgram = () => modal('New program', programForm(), 'Create program', async d => {
  const p = await api('POST', '/api/programs', d); await loadMeta(); toast('Program created'); go('#/program/' + p.id);
});
ACT.editProgram = el => run(el, async () => {
  const p = await api('GET', '/api/programs/' + el.dataset.id);
  modal('Edit program', programForm(p), 'Save', async d => { await api('PUT', '/api/programs/' + p.id, d); await loadMeta(); toast('Saved'); route(); });
});
ACT.deleteProgram = el => run(el, async () => {
  if (!confirm('Delete this program? This cannot be undone.')) return;
  await api('DELETE', '/api/programs/' + el.dataset.id); await loadMeta(); toast('Program deleted'); go('#/programs');
});

// ================================================================== ACTIVITIES
async function pageActivities() {
  const vol = !isStaff();
  const when = S.query.when || 'upcoming';
  const q = { program: S.query.program || '', status: S.query.status || '', q: S.query.q || '' };
  if (vol) { if (when === 'mine') q.mine = 1; else q.open = 1; }
  else if (when === 'upcoming') q.from = todayStr();
  else if (when === 'past') q.to = todayStr(-1);
  const list = await api('GET', '/api/activities' + qs(q));
  const shown = when === 'past' ? list.slice().reverse() : list;
  let lastMonth = '';
  const groups = shown.map(a => {
    const d = parseDate(a.date), m = MONTHS[d.getMonth()] + ' ' + d.getFullYear();
    const head = m !== lastMonth ? `<div class="month-label">${m}</div>` : '';
    lastMonth = m;
    return head + ticket(a);
  }).join('');
  const segs = vol ? [['upcoming', 'Open to join'], ['mine', 'Mine']] : [['upcoming', 'Upcoming'], ['past', 'Past'], ['all', 'All']];
  view().innerHTML = `
    <div class="page-head"><div><h1>${vol ? 'Find activities' : 'Activities'}</h1>
      <p class="sub">${vol ? 'Pick something that suits you and sign up. You cannot be on two activities on the same day.' : 'Camps, classes, drives and trainings on the ground.'}</p></div>
      <div class="actions">${canPrograms() ? '<button class="btn primary" data-act="newActivity">Plan activity</button>' : ''}</div></div>
    <div class="filters" id="af">
      <div class="seg">${segs.map(([k, t]) => `<button type="button" data-when="${k}" class="${k === when ? 'on' : ''}">${t}</button>`).join('')}</div>
      <input type="search" name="q" placeholder="Search title or place" value="${esc(q.q)}">
      <select name="program">${programOptions(q.program, 'All programs')}</select>
      ${vol ? '' : `<select name="status">${options(S.meta.activityStatuses, q.status, 'Any status')}</select>`}
      <input type="hidden" name="when" value="${esc(when)}">
    </div>
    ${shown.length ? `<div class="agenda">${groups}</div>` : `<div class="card">${emptyBox('No activities here.')}</div>`}`;
  filterBar('#af', 'activities');
  $$('#af [data-when]').forEach(b => b.addEventListener('click', () => { $('#af input[name=when]').value = b.dataset.when; $('#af select').dispatchEvent(new Event('change')); }));
}

async function pageActivity(id) {
  const a = await api('GET', '/api/activities/' + encodeURIComponent(id));
  const staff = isStaff(), mgr = canPrograms();
  const past = daysFromToday(a.date) < 0, isToday = daysFromToday(a.date) === 0;
  let actions = '';
  if (a.status === 'PLANNED') {
    if (mgr) {
      if (past || isToday) actions += `<button class="btn primary" data-act="completeActivity" data-id="${esc(a.id)}">Mark done</button>`;
      if (!past) actions += `<button class="btn" data-act="assignVolunteer" data-id="${esc(a.id)}">Add volunteer</button><button class="btn" data-act="editActivity" data-id="${esc(a.id)}">Edit</button>`;
      actions += `<button class="btn danger" data-act="cancelActivity" data-id="${esc(a.id)}">Cancel</button>`;
    }
    if (!staff && !past) actions += a.iAmIn ? `<button class="btn" data-act="leave" data-id="${esc(a.id)}">Leave</button>`
      : a.seatsLeft ? `<button class="btn primary" data-act="join" data-id="${esc(a.id)}">Sign up</button>` : '';
  }
  if (a.status === 'DONE' && mgr) actions += `<button class="btn" data-act="logHours" data-activity="${esc(a.id)}">Log hours</button>`;
  if (a.status === 'DONE' && !staff && a.iAmIn) actions += `<button class="btn primary" data-act="logHours">Log my hours</button>`;
  view().innerHTML = `
    <div class="crumb"><a href="#/activities">Activities</a> / ${esc(a.id)}</div>
    <div class="page-head"><div><div class="actions">${causeTag(a.cause)}${statusChip('activityStatuses', a.status)}<span class="chip">${label('activityKinds', a.kind)}</span></div>
      <h1>${esc(a.title)}</h1><p class="sub">${dateText(a.date)}${a.time ? ' at ' + esc(a.time) : ''} (${relDay(a.date)}) · ${esc(a.place)}</p></div>
      <div class="actions">${actions}</div></div>
    ${a.status === 'PLANNED' && past && mgr ? '<div class="callout">This date has passed. Mark it done (with how many people came) or cancel it.</div>' : ''}
    <div class="cols">
      <section class="card">
        <dl class="dl">
          <dt>Program</dt><dd>${staff ? `<a href="#/program/${esc(a.programId)}">${esc(a.programName)}</a>` : esc(a.programName)}</dd>
          <dt>Volunteers</dt><dd>${a.signedUp} of ${a.volunteersNeeded} ${seatsDots(a)}</dd>
          ${a.status === 'DONE' ? `<dt>People reached</dt><dd><b>${Number(a.reached).toLocaleString('en-IN')}</b></dd>` : ''}
          ${a.outcome ? `<dt>Notes</dt><dd>${esc(a.outcome)}</dd>` : ''}
          ${staff ? `<dt>Planned by</dt><dd>${userName(a.createdBy)}</dd>` : ''}
        </dl>
      </section>
      ${staff ? `<section class="card"><div class="card-head"><h3>Team</h3></div>
        ${a.volunteers.length ? `<ul class="feed">${a.volunteers.map(v => {
          const h = (a.hours || []).find(x => x.volunteerId === v.id);
          return `<li><span class="avatar">${initials(v.name)}</span><div class="actions"><a href="#/volunteer/${esc(v.id)}"><b>${esc(v.name)}</b></a>
            ${h ? `<span class="chip st-${esc(h.status)}">${h.hours} h · ${label('reviews', h.status)}</span>` : ''}
            ${mgr && a.status === 'PLANNED' ? `<button class="btn sm quiet" data-act="removeVolunteer" data-id="${esc(a.id)}" data-vol="${esc(v.id)}">Remove</button>` : ''}</div></li>`; }).join('')}</ul>`
          : emptyBox('Nobody has signed up yet.')}</section>` : ''}
    </div>`;
}

function activityForm(a) {
  a = a || { date: todayStr(1), volunteersNeeded: 3 };
  return field('Program', `<select name="programId" required>${programOptions(a.programId, 'Choose…', true)}</select>`)
    + field('Title', input('title', a.title, 'required maxlength="100" placeholder="e.g. General health camp"'))
    + `<div class="grid3">${field('Kind', `<select name="kind" required>${options(S.meta.activityKinds, a.kind, 'Choose…')}</select>`)}
       ${field('Date', input('date', a.date, `type="date" min="${todayStr()}" required`))}${field('Time', input('time', a.time, 'type="time"'))}</div>`
    + `<div class="grid2">${field('Place', input('place', a.place, 'required maxlength="120"'))}${field('Volunteers needed', input('volunteersNeeded', a.volunteersNeeded, 'type="number" min="0" max="200" required'))}</div>`;
}
ACT.newActivity = el => modal('Plan an activity', activityForm(el.dataset.program ? { programId: el.dataset.program, date: todayStr(1), volunteersNeeded: 3 } : null),
  'Plan it', async d => { const a = await api('POST', '/api/activities', d); toast('Activity planned'); go('#/activity/' + a.id); });
ACT.editActivity = el => run(el, async () => {
  const a = await api('GET', '/api/activities/' + el.dataset.id);
  modal('Edit activity', activityForm(a), 'Save', async d => { await api('PUT', '/api/activities/' + a.id, d); toast('Saved'); route(); });
});
ACT.completeActivity = el => modal('Mark activity done',
  field('How many people did it reach?', '<input name="reached" type="number" min="0" max="100000" required>')
  + field('What happened', '<textarea name="outcome" maxlength="1000" placeholder="Short notes for the report"></textarea>'),
  'Mark done', async d => { await api('POST', `/api/activities/${el.dataset.id}/complete`, d); toast('Marked done. Volunteers can now log their hours.'); route(); });
ACT.cancelActivity = el => modal('Cancel activity', field('Why is it cancelled?', '<input name="reason" required maxlength="300" placeholder="e.g. heavy rain">'),
  'Cancel activity', async d => { await api('POST', `/api/activities/${el.dataset.id}/cancel`, d); toast('Activity cancelled'); route(); }, { cancelLabel: 'Keep it' });
ACT.assignVolunteer = el => run(el, async () => {
  const vols = await api('GET', '/api/volunteers?state=active');
  modal('Add a volunteer', field('Volunteer', `<select name="volunteerId" required>${options(vols.map(v => ({ id: v.id, label: v.name + ' · ' + (v.skills.join(', ') || v.city) })), '', 'Choose…')}</select>`,
    'They must not be on another activity that day.'),
  'Add', async d => { await api('POST', `/api/activities/${el.dataset.id}/signup`, d); toast('Volunteer added'); route(); });
});
ACT.removeVolunteer = el => run(el, async () => {
  if (!confirm('Remove this volunteer from the activity?')) return;
  await api('POST', `/api/activities/${el.dataset.id}/withdraw`, { volunteerId: el.dataset.vol }); toast('Removed'); route();
});

// ================================================================== VOLUNTEERS
async function pageVolunteers() {
  const q = { q: S.query.q || '', skill: S.query.skill || '', state: S.query.state || 'active' };
  const list = await api('GET', '/api/volunteers' + qs(q));
  view().innerHTML = `
    <div class="page-head"><div><h1>Volunteers</h1><p class="sub">${plural(list.length, 'person', 'people')} · sorted by approved hours</p></div>
      <div class="actions"><button class="btn" data-act="exportVolunteers">Export CSV</button>${canPrograms() ? '<button class="btn primary" data-act="newVolunteer">Add volunteer</button>' : ''}</div></div>
    <div class="filters" id="vf">
      <input type="search" name="q" placeholder="Search name, city or email" value="${esc(q.q)}">
      <input type="search" name="skill" placeholder="Skill (e.g. first aid)" value="${esc(q.skill)}">
      <select name="state">${options([{ id: 'active', label: 'Active' }, { id: 'inactive', label: 'Not active' }, { id: 'all', label: 'Everyone' }], q.state)}</select>
    </div>
    ${list.length ? `<div class="people">${list.map(v => `<a class="person ${v.active ? '' : 'off'}" href="#/volunteer/${esc(v.id)}">
      <span class="avatar">${initials(v.name)}</span>
      <div><h3>${esc(v.name)}</h3><div class="sub">${esc(v.city || '')} · <b>${v.hours} h</b> · ${v.activitiesDone} done${v.upcoming ? ` · ${v.upcoming} coming` : ''}</div>
      <div class="tags">${v.skills.map(s => `<span>${esc(s)}</span>`).join('')}</div></div></a>`).join('')}</div>`
      : `<div class="card">${emptyBox('No volunteers match.')}</div>`}`;
  filterBar('#vf', 'volunteers');
}
ACT.exportVolunteers = el => run(el, () => download('/api/volunteers/export.csv', 'volunteers.csv'));

const AVAIL = [{ id: 'ANY', label: 'Any day' }, { id: 'WEEKDAYS', label: 'Weekdays' }, { id: 'WEEKENDS', label: 'Weekends' }];
async function pageVolunteer(id) {
  if (!id) { view().innerHTML = `<div class="card">${emptyBox('Your login is not linked to a volunteer record.')}</div>`; return; }
  const v = await api('GET', '/api/volunteers/' + encodeURIComponent(id));
  const self = id === S.user.volunteerId;
  view().innerHTML = `
    ${isStaff() ? `<div class="crumb"><a href="#/volunteers">Volunteers</a> / ${esc(v.id)}</div>` : ''}
    <section class="card"><div class="profile"><span class="avatar lg">${initials(v.name)}</span>
      <div class="spacer"><h1 class="m0">${esc(v.name)} ${v.active ? '' : '<span class="chip bad">not active</span>'}</h1>
        <div class="muted">${esc(v.city || '')}${v.phone ? ' · ' + esc(v.phone) : ''}${v.email ? ' · ' + esc(v.email) : ''} · ${esc((AVAIL.find(x => x.id === v.availability) || {}).label || '')}</div>
        <div class="tags">${v.skills.map(s => `<span>${esc(s)}</span>`).join('')}</div></div>
      <div class="actions">${canPrograms() || self ? `<button class="btn" data-act="editVolunteer" data-id="${esc(v.id)}">Edit</button>` : ''}
        ${canPrograms() ? `<button class="btn" data-act="logHours" data-vol="${esc(v.id)}">Log hours</button>` : ''}
        ${canPrograms() && !v.activitiesDone && !v.upcoming && !v.login ? `<button class="btn danger" data-act="deleteVolunteer" data-id="${esc(v.id)}">Delete</button>` : ''}</div></div>
      <div class="statline"><div><b>${v.hours}</b>approved hours</div><div><b>${v.pendingHours}</b>waiting</div><div><b>${v.activitiesDone}</b>activities done</div>
        <div><b>${v.upcoming}</b>coming up</div><div><b>${dateText(v.joinedOn)}</b>joined</div>${isStaff() ? `<div><b>${esc(v.login || '—')}</b>login</div>` : ''}</div></section>
    <div class="cols even">
      <section class="card"><div class="card-head"><h2>Activities</h2></div>
        ${v.activities.length ? `<div class="table-wrap"><table><tbody>${v.activities.map(a => `<tr class="click" data-href="#/activity/${esc(a.id)}"><td>${esc(a.title)}<span class="sub">${esc(a.programName)}</span></td><td>${dateText(a.date)}</td><td>${statusChip('activityStatuses', a.status)}</td></tr>`).join('')}</tbody></table></div>`
          : emptyBox('No activities yet.')}</section>
      <section class="card"><div class="card-head"><h2>Hours log</h2></div>
        ${v.hoursLog.length ? `<div class="table-wrap"><table><tbody>${v.hoursLog.map(h => `<tr><td>${esc(h.activityTitle)}<span class="sub">${dateText(h.date)}</span></td><td class="r num">${h.hours} h</td><td>${statusChip('reviews', h.status)}</td></tr>`).join('')}</tbody></table></div>`
          : emptyBox('No hours logged yet.')}</section>
    </div>`;
}

function volunteerForm(v) {
  v = v || { availability: 'ANY', joinedOn: todayStr(), skills: [] };
  const mgr = canPrograms();
  return (mgr ? field('Full name', input('name', v.name, 'required maxlength="80"')) : '')
    + `<div class="grid2">${field('Mobile', input('phone', v.phone, 'inputmode="tel" maxlength="14" placeholder="10 digits"'))}${field('Email', input('email', v.email, 'type="email" maxlength="120"'))}</div>`
    + `<div class="grid2">${field('City', input('city', v.city, 'maxlength="60"'))}${field('Available on', `<select name="availability">${options(AVAIL, v.availability)}</select>`)}</div>`
    + field('Skills', input('skillsText', (v.skills || []).join(', '), 'maxlength="300" placeholder="Teaching, First aid, Driving"'), 'Separate with commas')
    + (mgr ? `<div class="grid2">${field('Joined on', input('joinedOn', v.joinedOn, `type="date" max="${todayStr()}"`))}
        ${v.id ? `<label class="tick"><input type="checkbox" name="active" ${v.active ? 'checked' : ''}> Active volunteer</label>` : ''}</div>` : '');
}
const withSkills = d => { d.skills = (d.skillsText || '').split(',').map(s => s.trim()).filter(Boolean); delete d.skillsText; return d; };
ACT.newVolunteer = () => modal('Add a volunteer', volunteerForm(), 'Add volunteer', async d => {
  const v = await api('POST', '/api/volunteers', withSkills(d)); toast('Volunteer added'); go('#/volunteer/' + v.id);
});
ACT.editVolunteer = el => run(el, async () => {
  const v = await api('GET', '/api/volunteers/' + el.dataset.id);
  modal('Edit volunteer', volunteerForm(v), 'Save', async d => { await api('PUT', '/api/volunteers/' + v.id, withSkills(d)); toast('Saved'); route(); });
});
ACT.deleteVolunteer = el => run(el, async () => {
  if (!confirm('Delete this volunteer?')) return;
  await api('DELETE', '/api/volunteers/' + el.dataset.id); toast('Deleted'); go('#/volunteers');
});

// ================================================================== HOURS
async function pageHours() {
  const status = S.query.status === undefined ? (canPrograms() ? 'PENDING' : 'ALL') : S.query.status;
  const list = await api('GET', '/api/hours' + qs({ status: status === 'ALL' ? '' : status }));
  const mgr = canPrograms();
  const total = list.filter(h => h.status === 'APPROVED').reduce((s, h) => s + h.hours, 0);
  view().innerHTML = `
    <div class="page-head"><div><h1>${isStaff() ? 'Volunteer hours' : 'My hours'}</h1>
      <p class="sub">${mgr ? 'Hours that volunteers log themselves wait here for a program manager to approve.' : 'Hours count once a program manager approves them.'}</p></div>
      <div class="actions">${mgr || !isStaff() ? '<button class="btn primary" data-act="logHours">Log hours</button>' : ''}</div></div>
    <div class="filters" id="hf">
      <select name="status">${options([{ id: 'ALL', label: 'All' }, ...S.meta.reviews], status)}</select>
      ${status === 'APPROVED' || status === 'ALL' ? `<span class="chip ok">${Math.round(total * 10) / 10} approved hours shown</span>` : ''}
    </div>
    ${list.length ? `<div class="table-wrap"><table><thead><tr><th>Volunteer</th><th>Activity</th><th>Date</th><th class="r">Hours</th><th>Status</th><th>Note</th>${mgr ? '<th></th>' : ''}</tr></thead><tbody>
      ${list.map(h => `<tr><td>${isStaff() ? `<a href="#/volunteer/${esc(h.volunteerId)}">${esc(h.volunteerName)}</a>` : esc(h.volunteerName)}</td>
        <td><a href="#/activity/${esc(h.activityId)}">${esc(h.activityTitle)}</a></td><td>${dateText(h.date)}</td><td class="r num">${h.hours}</td>
        <td>${statusChip('reviews', h.status)}${h.reviewedBy ? `<span class="sub">by ${userName(h.reviewedBy)}</span>` : ''}</td><td class="small">${esc(h.note)}</td>
        ${mgr ? `<td class="r">${h.status === 'PENDING' ? `<span class="actions"><button class="btn sm good" data-act="approveHours" data-id="${esc(h.id)}">Approve</button><button class="btn sm danger" data-act="rejectHours" data-id="${esc(h.id)}">Reject</button></span>` : ''}</td>` : ''}</tr>`).join('')}
      </tbody></table></div>` : `<div class="card">${emptyBox(status === 'PENDING' ? 'No hours waiting for approval.' : 'No hours here.')}</div>`}`;
  filterBar('#hf', 'hours');
}
ACT.approveHours = el => run(el, async () => { await api('POST', `/api/hours/${el.dataset.id}/approve`); toast('Approved'); route(); });
ACT.rejectHours = el => run(el, async () => { if (!confirm('Reject these hours?')) return; await api('POST', `/api/hours/${el.dataset.id}/reject`); toast('Rejected'); route(); });

ACT.logHours = el => run(el, async () => {
  const mgr = canPrograms();
  let vols = [], volId = el.dataset.vol || '';
  if (mgr) vols = await api('GET', '/api/volunteers?state=all');
  const actFixed = el.dataset.activity;
  if (mgr && actFixed) {
    const a = await api('GET', '/api/activities/' + actFixed);
    vols = vols.filter(v => a.volunteers.some(x => x.id === v.id));
  }
  const loadActs = async vid => (vid || !mgr) ? api('GET', '/api/hours/unlogged' + qs({ volunteer: vid })) : [];
  const acts = await loadActs(mgr ? volId : '');
  const actSelect = list => options(list.filter(a => !actFixed || a.id === actFixed).map(a => ({ id: a.id, label: a.title + ' · ' + dateText(a.date) })), actFixed || '', list.length ? 'Choose…' : 'Nothing to log');
  modal(mgr ? 'Log volunteer hours' : 'Log my hours',
    (mgr ? field('Volunteer', `<select name="volunteerId" id="lhVol" required>${options(vols.map(v => ({ id: v.id, label: v.name })), volId, 'Choose…')}</select>`) : '')
    + field('Activity', `<select name="activityId" id="lhAct" required>${actSelect(acts)}</select>`, 'Only done activities you were signed up for, not logged yet.')
    + `<div class="grid2">${field('Hours', '<input name="hours" type="number" min="0.5" max="12" step="0.5" required>', 'Half-hour steps, up to 12')}${field('Note', '<input name="note" maxlength="300">')}</div>`
    + (mgr ? '<p class="small muted">Hours you log are approved at once.</p>' : '<p class="small muted">A program manager will approve them.</p>'),
    'Log hours', async d => { await api('POST', '/api/hours', d); toast('Hours logged'); route(); },
    { after: () => { const s = $('#lhVol'); if (s) s.addEventListener('change', async () => { $('#lhAct').innerHTML = actSelect(await loadActs(s.value)); }); } });
});

// ================================================================== DONATIONS & DONORS
const fundTabs = on => `<div class="seg"><button type="button" data-act="goto" data-href="#/donations" class="${on === 'donations' ? 'on' : ''}">Donations</button>
  <button type="button" data-act="goto" data-href="#/donors" class="${on === 'donors' ? 'on' : ''}">Donors</button></div>`;
ACT.goto = el => go(el.dataset.href);

async function pageDonations() {
  const q = { from: S.query.from || '', to: S.query.to || '', program: S.query.program || '', mode: S.query.mode || '', q: S.query.q || '' };
  const [list, fund] = await Promise.all([api('GET', '/api/donations' + qs(q)), api('GET', '/api/funds')]);
  const total = list.reduce((s, d) => s + d.amount, 0);
  const modes = Object.entries(fund.byMode).filter(m => m[1] > 0);
  const modeTotal = modes.reduce((s, m) => s + m[1], 0) || 1;
  view().innerHTML = `
    <div class="page-head"><div><h1>Donations</h1><p class="sub">Every gift gets a receipt number for its financial year (April to March).</p></div>
      <div class="actions">${fundTabs('donations')}${canFinance() ? '<button class="btn" data-act="exportDonations">Export CSV</button><button class="btn primary" data-act="newDonation">Record donation</button>' : ''}</div></div>
    <div class="kpis">
      <div class="kpi k-in"><div class="lbl">Received (all time)</div><div class="val">${rsShort(fund.received)}</div><div class="note">${rsShort(fund.general)} to the general fund</div></div>
      <div class="kpi k-out"><div class="lbl">Spent (approved)</div><div class="val">${rsShort(fund.spent)}</div><div class="note">${rs(fund.waiting)} waiting for approval</div></div>
      <div class="kpi k-bal"><div class="lbl">Balance</div><div class="val">${rsShort(fund.balance)}</div><div class="note">money in hand</div></div>
      <div class="kpi"><div class="lbl">How people pay</div><div class="stack-bar">${modes.map((m, i) => `<i class="sb-${i % 5}" data-w="${m[1] / modeTotal * 100}" title="${label('payModes', m[0])}: ${rs(m[1])}"></i>`).join('')}</div>
        <div class="key">${modes.map((m, i) => `<span><i class="sb-${i % 5}"></i>${label('payModes', m[0])}</span>`).join('')}</div></div>
    </div>
    <div class="filters" id="df">
      <input type="search" name="q" placeholder="Donor, receipt or reference" value="${esc(q.q)}">
      <select name="program"><option value="">Any purpose</option><option value="GENERAL" ${q.program === 'GENERAL' ? 'selected' : ''}>General fund</option>${programOptions(q.program)}</select>
      <select name="mode">${options(S.meta.payModes, q.mode, 'Any mode')}</select>
      <input type="date" name="from" value="${esc(q.from)}" aria-label="From"><input type="date" name="to" value="${esc(q.to)}" aria-label="To">
    </div>
    ${list.length ? `<div class="table-wrap"><table><thead><tr><th>Receipt</th><th>Date</th><th>Donor</th><th>For</th><th>Mode</th><th class="r">Amount</th><th></th></tr></thead><tbody>
      ${list.map(d => `<tr><td class="mono">${esc(d.receiptNo)}</td><td>${dateText(d.date)}</td><td><a href="#/donor/${esc(d.donorId)}">${esc(d.donorName)}</a><span class="sub">${label('donorTypes', d.donorType)}</span></td>
        <td>${esc(d.programName)}</td><td>${esc(d.modeLabel)}<span class="sub mono">${esc(d.reference)}</span></td><td class="r amt in">${rs(d.amount)}</td>
        <td class="r"><span class="actions"><button class="btn sm" data-act="receipt" data-id="${esc(d.id)}">Receipt</button>${isAdmin() ? `<button class="btn sm quiet" data-act="deleteDonation" data-id="${esc(d.id)}" title="Delete wrong entry">✕</button>` : ''}</span></td></tr>`).join('')}
      </tbody><tfoot><tr><td colspan="5">${plural(list.length, 'donation')}</td><td class="r">${rs(total)}</td><td></td></tr></tfoot></table></div>`
      : `<div class="card">${emptyBox('No donations match.')}</div>`}`;
  filterBar('#df', 'donations');
}
ACT.exportDonations = el => run(el, () => download('/api/donations/export.csv' + qs(S.query), 'donations.csv'));
ACT.deleteDonation = el => run(el, async () => {
  if (!confirm('Delete this donation? Use this only for a wrong entry. The receipt number will not be reused.')) return;
  await api('DELETE', '/api/donations/' + el.dataset.id); toast('Donation deleted'); route();
});

ACT.newDonation = el => run(el, async () => {
  const donors = await api('GET', '/api/donors');
  modal('Record a donation',
    field('Donor', `<select name="donorId" required>${options(donors.map(d => ({ id: d.id, label: d.name + ' · ' + (d.city || d.typeLabel) })), el.dataset.donor || '', 'Choose…')}</select>`,
      'Not in the list? Add the donor first on the Donors tab.')
    + `<div class="grid2">${field('Amount (₹)', '<input name="amount" type="number" min="1" step="1" required>')}${field('Date', `<input name="date" type="date" value="${todayStr()}" max="${todayStr()}" required>`)}</div>`
    + `<div class="grid2">${field('Paid by', `<select name="mode" required>${options(S.meta.payModes, 'UPI')}</select>`, 'Cash only up to ₹2,000')}${field('UTR / cheque no.', '<input name="reference" maxlength="60">')}</div>`
    + field('For', `<select name="programId"><option value="">General fund (any program)</option>${programOptions('', null, true)}</select>`)
    + field('Note', '<input name="note" maxlength="300">'),
    'Save and make receipt', async d => { const x = await api('POST', '/api/donations', d); toast('Saved as ' + x.receiptNo); showReceipt(x.id); return true; });
});

async function showReceipt(id) {
  const r = await api('GET', `/api/donations/${id}/receipt`);
  const o = r.org, d = r.donor || {};
  const html = `<div class="sheet">
    <div class="sh-head"><div class="org"><svg viewBox="0 0 40 40"><use href="/static/favicon.svg#flower"/></svg><div><b>${esc(o.orgName)}</b>
      <span class="small">${esc(o.address)}<br>Reg. No. ${esc(o.regNo)}${o.email ? ' · ' + esc(o.email) : ''}</span></div></div>
      <div class="tr"><b>DONATION RECEIPT</b><br><span class="small">No. ${esc(r.receiptNo)}<br>Date ${dateText(r.date)}<br>FY ${esc(r.financialYear)}</span></div></div>
    <p>Received with thanks from <b>${esc(d.name)}</b>${d.city ? ', ' + esc(d.city) : ''} the sum of</p>
    <p class="big-amt">${esc(r.amountText)}</p><p class="words">(${esc(r.amountWords)})</p>
    <table><tbody><tr><th>Paid by</th><td>${esc(r.modeLabel)}${r.reference ? ' · ' + esc(r.reference) : ''}</td></tr>
      <tr><th>Towards</th><td>${esc(r.programName)}</td></tr>${r.note ? `<tr><th>Note</th><td>${esc(r.note)}</td></tr>` : ''}</tbody></table>
    <p class="small mt2">${esc(o.receiptNote)}</p>
    <div class="sign"><span>Donor</span><span>For ${esc(o.orgName)}</span></div></div>`;
  modal('Receipt ' + r.receiptNo, `<div class="sheet-preview">${html}</div>`, null, null,
    { extra: '<button type="button" class="btn primary" id="printBtn">Print receipt</button>', cancelLabel: 'Close', noFocus: true,
      after: () => $('#printBtn').addEventListener('click', () => printSheet(html)) });
  $('#modal').addEventListener('close', () => { if (S.route === 'donations' || S.route === 'donor') route(); }, { once: true });
}
ACT.receipt = el => run(el, () => showReceipt(el.dataset.id));

/** Prints only one sheet: it is copied into #printArea, which is the only thing @media print shows. */
function printSheet(html) {
  $('#printArea').innerHTML = html;
  window.print();
}

async function pageDonors() {
  const q = { q: S.query.q || '', type: S.query.type || '' };
  const list = await api('GET', '/api/donors' + qs(q));
  view().innerHTML = `
    <div class="page-head"><div><h1>Donors</h1><p class="sub">People and organisations who support the work, biggest givers first.</p></div>
      <div class="actions">${fundTabs('donors')}${canFinance() ? '<button class="btn primary" data-act="newDonor">Add donor</button>' : ''}</div></div>
    <div class="filters" id="dnf">
      <input type="search" name="q" placeholder="Search donors" value="${esc(q.q)}">
      <select name="type">${options(S.meta.donorTypes, q.type, 'All types')}</select></div>
    ${list.length ? `<div class="table-wrap"><table><thead><tr><th>Donor</th><th>Type</th><th>City</th><th class="r">Gifts</th><th>Last gift</th><th class="r">Total given</th></tr></thead><tbody>
      ${list.map(d => `<tr class="click" data-href="#/donor/${esc(d.id)}"><td><b>${esc(d.name)}</b><span class="sub">${esc(d.email || d.phone || '')}</span></td><td>${esc(d.typeLabel)}</td><td>${esc(d.city)}</td>
        <td class="r">${d.gifts}</td><td>${d.lastGift ? dateText(d.lastGift) : '—'}</td><td class="r amt in">${rs(d.total)}</td></tr>`).join('')}
      </tbody></table></div>` : `<div class="card">${emptyBox('No donors match.')}</div>`}`;
  filterBar('#dnf', 'donors');
}

async function pageDonor(id) {
  const d = await api('GET', '/api/donors/' + encodeURIComponent(id));
  view().innerHTML = `
    <div class="crumb"><a href="#/donors">Donors</a> / ${esc(d.id)}</div>
    <section class="card"><div class="profile"><span class="avatar lg">${initials(d.name)}</span>
      <div class="spacer"><h1 class="m0">${esc(d.name)}</h1><div class="muted">${esc(d.typeLabel)}${d.city ? ' · ' + esc(d.city) : ''}${d.email ? ' · ' + esc(d.email) : ''}${d.phone ? ' · ' + esc(d.phone) : ''}</div></div>
      <div class="actions">${canFinance() ? `<button class="btn primary" data-act="newDonation" data-donor="${esc(d.id)}">Record donation</button><button class="btn" data-act="editDonor" data-id="${esc(d.id)}">Edit</button>
        ${!d.gifts ? `<button class="btn danger" data-act="deleteDonor" data-id="${esc(d.id)}">Delete</button>` : ''}` : ''}</div></div>
      <div class="statline"><div><b>${rs(d.total)}</b>given in total</div><div><b>${d.gifts}</b>gifts</div><div><b>${d.lastGift ? dateText(d.lastGift) : '—'}</b>last gift</div></div></section>
    <section class="card"><div class="card-head"><h2>Gifts</h2></div>
      ${d.donations.length ? `<div class="table-wrap"><table><thead><tr><th>Receipt</th><th>Date</th><th>For</th><th>Mode</th><th class="r">Amount</th><th></th></tr></thead><tbody>
        ${d.donations.map(x => `<tr><td class="mono">${esc(x.receiptNo)}</td><td>${dateText(x.date)}</td><td>${esc(x.programName)}</td><td>${esc(x.modeLabel)}</td><td class="r amt in">${rs(x.amount)}</td>
          <td class="r"><button class="btn sm" data-act="receipt" data-id="${esc(x.id)}">Receipt</button></td></tr>`).join('')}</tbody></table></div>` : emptyBox('No gifts yet.')}</section>`;
}

function donorForm(d) {
  d = d || { type: 'INDIVIDUAL' };
  return field('Name', input('name', d.name, 'required maxlength="100"'))
    + `<div class="grid2">${field('Type', `<select name="type">${options(S.meta.donorTypes, d.type)}</select>`)}${field('City', input('city', d.city, 'maxlength="60"'))}</div>`
    + `<div class="grid2">${field('Email', input('email', d.email, 'type="email" maxlength="120"'))}${field('Mobile', input('phone', d.phone, 'maxlength="14"'))}</div>`;
}
ACT.newDonor = () => modal('Add donor', donorForm(), 'Add donor', async d => { const x = await api('POST', '/api/donors', d); toast('Donor added'); go('#/donor/' + x.id); });
ACT.editDonor = el => run(el, async () => {
  const d = await api('GET', '/api/donors/' + el.dataset.id);
  modal('Edit donor', donorForm(d), 'Save', async x => { await api('PUT', '/api/donors/' + d.id, x); toast('Saved'); route(); });
});
ACT.deleteDonor = el => run(el, async () => { if (!confirm('Delete this donor?')) return; await api('DELETE', '/api/donors/' + el.dataset.id); toast('Deleted'); go('#/donors'); });

// ================================================================== EXPENSES
async function pageExpenses() {
  const q = { status: S.query.status ?? '', program: S.query.program || '', category: S.query.category || '', mine: S.query.mine || '' };
  const list = await api('GET', '/api/expenses' + qs(q));
  const total = list.reduce((s, e) => s + e.amount, 0);
  view().innerHTML = `
    <div class="page-head"><div><h1>Expenses</h1><p class="sub">One person submits, a different person approves. Spending over a program's budget needs the admin.</p></div>
      <div class="actions"><button class="btn" data-act="exportExpenses">Export CSV</button><button class="btn primary" data-act="newExpense">Submit expense</button></div></div>
    <div class="filters" id="ef">
      <select name="status">${options(S.meta.reviews, q.status, 'Any status')}</select>
      <select name="program">${programOptions(q.program, 'All programs')}</select>
      <select name="category">${options(S.meta.expenseCategories, q.category, 'All categories')}</select>
      <select name="mine">${options([{ id: '1', label: 'Submitted by me' }], q.mine, 'Everyone')}</select>
    </div>
    ${list.length ? `<div class="table-wrap"><table><thead><tr><th>Date</th><th>What for</th><th>Program</th><th>Category</th><th class="r">Amount</th><th>Status</th><th></th></tr></thead><tbody>
      ${list.map(e => `<tr><td>${dateText(e.date)}<span class="sub mono">${esc(e.id)}</span></td><td>${esc(e.description)}<span class="sub">paid to ${esc(e.vendor)} · by ${userName(e.submittedBy)}</span></td>
        <td><a href="#/program/${esc(e.programId)}">${esc(e.programName)}</a><span class="sub">${rs(e.budgetLeft)} left</span></td><td>${esc(e.categoryLabel)}</td>
        <td class="r amt out">${rs(e.amount)}${e.overBudget ? '<span class="sub over">over budget</span>' : ''}</td>
        <td>${statusChip('reviews', e.status)}${e.reviewedBy ? `<span class="sub">${userName(e.reviewedBy)}${e.reviewNote ? ': ' + esc(e.reviewNote) : ''}</span>` : ''}</td>
        <td class="r">${e.status === 'PENDING' ? `<span class="actions">
          ${canFinance() && e.submittedBy !== S.user.username ? `<button class="btn sm good" data-act="reviewExpense" data-id="${esc(e.id)}" data-ok="1" data-over="${e.overBudget ? 1 : ''}">Approve</button><button class="btn sm danger" data-act="reviewExpense" data-id="${esc(e.id)}">Reject</button>` : ''}
          ${e.submittedBy === S.user.username || isAdmin() ? `<button class="btn sm quiet" data-act="withdrawExpense" data-id="${esc(e.id)}">Withdraw</button>` : ''}</span>` : ''}</td></tr>`).join('')}
      </tbody><tfoot><tr><td colspan="4">${plural(list.length, 'expense')}</td><td class="r">${rs(total)}</td><td colspan="2"></td></tr></tfoot></table></div>`
      : `<div class="card">${emptyBox('No expenses match.')}</div>`}`;
  filterBar('#ef', 'expenses');
}
ACT.exportExpenses = el => run(el, () => download('/api/expenses/export.csv' + qs(S.query), 'expenses.csv'));
ACT.newExpense = el => modal('Submit an expense',
  field('Program', `<select name="programId" required>${programOptions(el.dataset.program || '', 'Choose…', true)}</select>`)
  + `<div class="grid2">${field('Category', `<select name="category" required>${options(S.meta.expenseCategories, '', 'Choose…')}</select>`)}${field('Amount (₹)', '<input name="amount" type="number" min="1" step="1" required>')}</div>`
  + `<div class="grid2">${field('Date', `<input name="date" type="date" value="${todayStr()}" max="${todayStr()}" required>`)}${field('Paid to', '<input name="vendor" required maxlength="100">')}</div>`
  + field('What for', '<input name="description" required maxlength="300">')
  + '<p class="small muted">Someone else (the finance officer) will check and approve it.</p>',
  'Submit', async d => { await api('POST', '/api/expenses', d); toast('Expense submitted for approval'); if (S.route === 'expenses' || S.route === 'program') route(); else go('#/expenses?mine=1'); });
ACT.reviewExpense = el => {
  const ok = !!el.dataset.ok, over = !!el.dataset.over;
  modal(ok ? 'Approve expense' : 'Reject expense',
    (ok && over ? `<div class="callout bad">This will take the program over its budget. ${isAdmin() ? 'As admin you can still approve it, but write why.' : 'Only the admin can approve this.'}</div>` : '')
    + field(ok ? 'Note' + (over ? ' (required)' : ' (optional)') : 'Reason (required)', '<input name="note" maxlength="300">'),
    ok ? 'Approve' : 'Reject', async d => { await api('POST', `/api/expenses/${el.dataset.id}/review`, { approve: ok, note: d.note }); toast(ok ? 'Approved' : 'Rejected'); route(); });
};
ACT.withdrawExpense = el => run(el, async () => { if (!confirm('Withdraw this expense?')) return; await api('DELETE', '/api/expenses/' + el.dataset.id); toast('Withdrawn'); route(); });

// ================================================================== BENEFICIARIES
async function pageBeneficiaries() {
  const q = { program: S.query.program || '', gender: S.query.gender || '', age: S.query.age || '', q: S.query.q || '' };
  const d = await api('GET', '/api/beneficiaries' + qs(q));
  const max = o => Math.max(1, ...Object.values(o));
  const breakdown = (obj, list) => `<div class="barlist">${Object.entries(obj).map(([k, v]) => `<div class="item"><span>${list ? label(list, k) : esc(k)}</span>${bar(v / max(obj) * 100, 'reach thin')}<span class="v">${v}</span></div>`).join('')}</div>`;
  const villages = Object.fromEntries(Object.entries(d.byVillage).sort((a, b) => b[1] - a[1]).slice(0, 6));
  view().innerHTML = `
    <div class="page-head"><div><h1>Beneficiaries</h1><p class="sub">People enrolled in programs. Only program managers and the admin can see this list.</p></div>
      <div class="actions"><button class="btn" data-act="exportBeneficiaries">Export CSV</button><button class="btn primary" data-act="newBeneficiary">Enrol person</button></div></div>
    <div class="cols">
      <div><div class="filters" id="bf">
        <input type="search" name="q" placeholder="Name, id or village" value="${esc(q.q)}">
        <select name="program">${programOptions(q.program, 'All programs')}</select>
        <select name="gender">${options(S.meta.genders, q.gender, 'Any gender')}</select>
        <select name="age">${options(S.meta.ageGroups, q.age, 'Any age')}</select></div>
      ${d.rows.length ? `<div class="table-wrap"><table><thead><tr><th>Name</th><th>Village</th><th>Group</th><th>Programs</th><th>Enrolled</th><th></th></tr></thead><tbody>
        ${d.rows.map(b => `<tr><td><b>${esc(b.name)}</b><span class="sub mono">${esc(b.id)}</span></td><td>${esc(b.village)}</td><td>${esc(b.genderLabel)}<span class="sub">${esc(b.ageLabel)}</span></td>
          <td class="small">${b.programNames.map(esc).join('<br>')}</td><td>${dateText(b.enrolledOn)}</td>
          <td class="r"><button class="btn sm" data-act="editBeneficiary" data-id="${esc(b.id)}">Edit</button></td></tr>`).join('')}</tbody></table></div>`
        : `<div class="card">${emptyBox('Nobody matches.')}</div>`}</div>
      <div class="stack">
        <section class="card"><div class="card-head"><h3>${plural(d.total, 'person', 'people')}</h3></div><h4 class="small muted">By gender</h4>${breakdown(d.byGender, 'genders')}
          <h4 class="small muted mt2">By age group</h4>${breakdown(d.byAge, 'ageGroups')}</section>
        <section class="card"><div class="card-head"><h3>Top villages</h3></div>${Object.keys(villages).length ? breakdown(villages) : emptyBox('—')}</section>
      </div>
    </div>`;
  S.benRows = d.rows;
  filterBar('#bf', 'beneficiaries');
}
ACT.exportBeneficiaries = el => run(el, () => download('/api/beneficiaries/export.csv' + qs(S.query), 'beneficiaries.csv'));

function beneficiaryForm(b) {
  b = b || { enrolledOn: todayStr(), programIds: S.query.program ? [S.query.program] : [] };
  return field('Full name', input('name', b.name, 'required maxlength="80"'))
    + `<div class="grid3">${field('Gender', `<select name="gender" required>${options(S.meta.genders, b.gender, 'Choose…')}</select>`)}
       ${field('Age group', `<select name="ageGroup" required>${options(S.meta.ageGroups, b.ageGroup, 'Choose…')}</select>`)}
       ${field('Village / area', input('village', b.village, 'required maxlength="60"'))}</div>`
    + `<div class="field">Programs<div class="checks">${(S.meta.programs || []).map(p => `<label><input type="checkbox" name="programIds" data-multi value="${esc(p.id)}" ${b.programIds.includes(p.id) ? 'checked' : ''}>${esc(p.name)}</label>`).join('')}</div></div>`
    + field('Enrolled on', input('enrolledOn', b.enrolledOn, `type="date" max="${todayStr()}"`))
    + field('Notes', `<textarea name="notes" maxlength="500">${esc(b.notes || '')}</textarea>`)
    + '<div id="dupBox" class="hidden"><label class="tick"><input type="checkbox" name="allowDuplicate"> Different person (save anyway)</label></div>';
}
async function saveBeneficiary(id, d) {
  try { await api(id ? 'PUT' : 'POST', '/api/beneficiaries' + (id ? '/' + id : ''), d); }
  catch (e) { if (e.status === 409) $('#dupBox').classList.remove('hidden'); throw e; }
  toast(id ? 'Saved' : 'Enrolled'); route();
}
ACT.newBeneficiary = () => modal('Enrol a person', beneficiaryForm(), 'Enrol', d => saveBeneficiary(null, d));
ACT.editBeneficiary = el => {
  const b = (S.benRows || []).find(x => x.id === el.dataset.id);
  if (!b) return;
  modal('Edit ' + b.name, beneficiaryForm(b), 'Save', d => saveBeneficiary(b.id, d),
    { extra: `<button type="button" class="btn danger" id="benDel">Remove</button>`,
      after: () => $('#benDel').addEventListener('click', () => run($('#benDel'), async () => {
        if (!confirm('Remove ' + b.name + ' from the list?')) return;
        await api('DELETE', '/api/beneficiaries/' + b.id); $('#modal').close(); toast('Removed'); route();
      })) });
};

// ================================================================== IMPACT REPORT
async function pageReport() {
  const q = { from: S.query.from || '', to: S.query.to || '' };
  const r = await api('GET', '/api/report' + qs(q));
  view().innerHTML = `
    <div class="page-head"><div><h1>Impact report</h1><p class="sub">What the NGO did with its money and time, ${dateText(r.from)} to ${dateText(r.to)}.</p></div>
      <div class="actions"><button class="btn primary" data-act="printReport">Print report</button></div></div>
    <div class="filters" id="rf"><span class="small muted">From</span><input type="date" name="from" value="${esc(r.from)}"><span class="small muted">to</span><input type="date" name="to" value="${esc(r.to)}">
      <button class="btn sm quiet" data-act="goto" data-href="#/report">This financial year</button></div>
    <div class="sheet-preview">${reportSheet(r)}</div>`;
  S.report = r;
  filterBar('#rf', 'report');
}
ACT.printReport = () => printSheet(reportSheet(S.report));

function reportSheet(r) {
  const t = r.totals;
  const cats = Object.entries(r.spending.byCategory).filter(c => c[1] > 0);
  const types = Object.entries(r.income.byType).filter(c => c[1] > 0);
  return `<div class="sheet">
    <div class="sh-head"><div class="org"><svg viewBox="0 0 40 40"><use href="/static/favicon.svg#flower"/></svg><div><b>${esc(r.org.orgName)}</b>
      <span class="small">${esc(r.org.address)} · Reg. No. ${esc(r.org.regNo)}</span></div></div>
      <div class="tr"><b>IMPACT REPORT</b><br><span class="small">${dateText(r.from)} – ${dateText(r.to)}</span></div></div>
    <p>In this period we received <b>${rs(r.income.total)}</b> in donations and spent <b>${rs(r.spending.total)}</b> on our programs.
      We completed <b>${t.done}</b> activities that reached <b>${Number(t.reached).toLocaleString('en-IN')}</b> people,
      with <b>${r.volunteersActive}</b> volunteers giving <b>${t.hours}</b> hours.</p>
    <h3>Programs</h3>
    <table><thead><tr><th>Program</th><th class="r">Budget</th><th class="r">Received</th><th class="r">Spent</th><th class="r">Activities</th><th class="r">Reached</th><th class="r">Vol. hours</th></tr></thead><tbody>
      ${r.programs.map(p => `<tr><td>${esc(p.name)}<br><span class="small">${label('causes', p.cause)} · ${label('programStatuses', p.status)} · ${p.used}% of budget used</span></td>
        <td class="r">${rs(p.budget)}</td><td class="r">${rs(p.raised)}</td><td class="r">${rs(p.spent)}</td><td class="r">${p.done}</td><td class="r">${Number(p.reached).toLocaleString('en-IN')}</td><td class="r">${p.hours}</td></tr>`).join('')}
    </tbody><tfoot><tr><td>Total</td><td class="r">${rs(t.budget)}</td><td class="r">${rs(t.raised)}</td><td class="r">${rs(t.spent)}</td><td class="r">${t.done}</td><td class="r">${Number(t.reached).toLocaleString('en-IN')}</td><td class="r">${t.hours}</td></tr></tfoot></table>
    <p class="small">"Received" counts gifts given for a program. A further ${rs(r.income.general)} went to the general fund.</p>
    <div class="cols even">
      <div><h3>Where the money came from</h3><table><tbody>${types.map(([k, v]) => `<tr><td>${label('donorTypes', k)}</td><td class="r">${rs(v)}</td></tr>`).join('') || '<tr><td>—</td></tr>'}</tbody></table></div>
      <div><h3>How it was spent</h3><table><tbody>${cats.map(([k, v]) => `<tr><td>${label('expenseCategories', k)}</td><td class="r">${rs(v)}</td></tr>`).join('') || '<tr><td>—</td></tr>'}</tbody></table></div>
    </div>
    <div class="cols even">
      <div><h3>Top donors</h3><table><tbody>${r.topDonors.map(d => `<tr><td>${esc(d.name)}</td><td class="r">${rs(d.total)}</td></tr>`).join('') || '<tr><td>—</td></tr>'}</tbody></table></div>
      <div><h3>Volunteers of the period</h3><table><tbody>${r.topVolunteers.map(v => `<tr><td>${esc(v.name)}</td><td class="r">${v.hours} h</td></tr>`).join('') || '<tr><td>—</td></tr>'}</tbody></table></div>
    </div>
    <div class="sign"><span>Prepared by</span><span>Director</span></div></div>`;
}

// ================================================================== TEAM, SETTINGS, HISTORY (admin)
async function pageTeam() {
  const [users, vols] = await Promise.all([api('GET', '/api/users'), api('GET', '/api/volunteers?state=all')]);
  S.vols = vols;
  view().innerHTML = `
    <div class="page-head"><div><h1>Team logins</h1><p class="sub">Who can sign in and what they can do.</p></div>
      <div class="actions"><button class="btn primary" data-act="newUser">Add login</button></div></div>
    <div class="cols">
      <div class="table-wrap"><table><thead><tr><th>Name</th><th>Username</th><th>Role</th><th>Status</th><th></th></tr></thead><tbody>
        ${users.map(u => `<tr><td><b>${esc(u.displayName)}</b>${u.volunteerId ? `<span class="sub">volunteer ${esc(u.volunteerId)}</span>` : ''}</td><td class="mono">${esc(u.username)}</td><td>${esc(u.roleLabel)}</td>
          <td>${u.active ? '<span class="chip ok">active</span>' : '<span class="chip bad">off</span>'}</td><td class="r"><button class="btn sm" data-act="editUser" data-u="${esc(u.username)}">Change</button></td></tr>`).join('')}
      </tbody></table></div>
      <section class="card"><h3>What each role can do</h3><dl class="dl small">
        <dt>Director / Admin</dt><dd>Everything, including logins, NGO details, deleting records and approving over-budget spending.</dd>
        <dt>Program Manager</dt><dd>Programs, activities, volunteers, hours and beneficiaries. Can submit expenses.</dd>
        <dt>Finance Officer</dt><dd>Donors, donations and receipts. Approves or rejects other people's expenses.</dd>
        <dt>Volunteer</dt><dd>Sees open activities, signs up, logs own hours. Sees no money or beneficiary data.</dd></dl></section>
    </div>`;
  S.users = users;
}
function userForm(u) {
  const isNew = !u;
  u = u || { role: 'MANAGER', active: true };
  const linked = new Set((S.users || []).filter(x => x.volunteerId && x.username !== u.username).map(x => x.volunteerId));
  return (isNew ? field('Username', '<input name="username" required maxlength="20" pattern="[a-z][a-z0-9._]{2,19}" autocomplete="off">', 'Small letters and digits, e.g. neha.e') : '')
    + field('Name', input('displayName', u.displayName, 'required maxlength="60"'))
    + `<div class="grid2">${field('Role', `<select name="role" id="ufRole">${options(S.meta.roles, u.role)}</select>`)}
       ${field('Volunteer record', `<select name="volunteerId">${options((S.vols || []).filter(v => !linked.has(v.id)).map(v => ({ id: v.id, label: v.name })), u.volunteerId, '—')}</select>`, 'Only for the Volunteer role')}</div>`
    + field(isNew ? 'Password' : 'New password', `<input name="password" type="password" autocomplete="new-password" ${isNew ? 'required' : ''}>`, isNew ? '8+ characters with a letter and a number' : 'Leave empty to keep the current one')
    + (isNew ? '' : `<label class="tick"><input type="checkbox" name="active" ${u.active ? 'checked' : ''}> Login is active</label>`);
}
ACT.newUser = () => modal('Add login', userForm(), 'Add', async d => { await api('POST', '/api/users', d); await loadMeta(); toast('Login added'); route(); });
ACT.editUser = el => {
  const u = (S.users || []).find(x => x.username === el.dataset.u);
  modal('Change ' + u.username, userForm(u), 'Save', async d => { await api('PUT', '/api/users/' + u.username, d); await loadMeta(); toast('Saved'); route(); });
};

async function pageSettings() {
  const s = await api('GET', '/api/settings');
  view().innerHTML = `<div class="page-head"><div><h1>NGO details</h1><p class="sub">Printed on every receipt and report.</p></div></div>
    <form class="card" id="setForm">
      ${field('NGO name', input('orgName', s.orgName, 'required maxlength="100"'))}
      <div class="grid2">${field('Registration number', input('regNo', s.regNo, 'maxlength="40"'))}${field('Email', input('email', s.email, 'type="email" maxlength="120"'))}</div>
      ${field('Address', input('address', s.address, 'maxlength="200"'))}
      ${field('Line printed on receipts', `<textarea name="receiptNote" maxlength="300">${esc(s.receiptNote)}</textarea>`)}
      <p class="err-text" id="setErr"></p><button class="btn primary" type="submit">Save</button></form>`;
  $('#setForm').addEventListener('submit', e => {
    e.preventDefault();
    run(e.submitter, async () => {
      $('#setErr').textContent = '';
      try { await api('PUT', '/api/settings', readForm($('#setForm'))); await loadMeta(); toast('Saved'); }
      catch (err) { $('#setErr').textContent = err.message; }
    });
  });
}

async function pageHistory() {
  const list = await api('GET', '/api/events?limit=200');
  view().innerHTML = `<div class="page-head"><div><h1>History</h1><p class="sub">The last ${list.length} things people did.</p></div></div>
    <section class="card">${feed(list)}</section>`;
}

// ================================================================== start
(async function boot() {
  const t = store.get('ngo-token');
  if (!t) { showLogin(); return; }
  S.token = t;
  try { S.user = await api('GET', '/api/auth/me'); await startApp(); }
  catch { signOut(true); }
})();
