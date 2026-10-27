/* Sales Pipeline Manager - frontend.
   Plain JavaScript, no frameworks. Every piece of data is escaped with esc() before it goes into HTML,
   and no inline styles or scripts are used (the server's Content-Security-Policy blocks them). */
'use strict';

const S = {
  token: null, user: null, meta: null, users: [],
  dashScope: 'me', boardOwner: '', reportOwner: '',
  dealFilter: { status: 'open', stage: '', owner: '', health: '', closing: '', q: '', sort: 'updated' },
  actTab: 'open', actOwner: 'me'
};
const $ = (sel, root = document) => root.querySelector(sel);
const $$ = (sel, root = document) => Array.from(root.querySelectorAll(sel));
const view = () => $('#view');

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
const label = v => esc(String(v ?? '').replace(/_/g, ' ').toLowerCase().replace(/^\w/, c => c.toUpperCase()));
const stageLabel = s => esc((S.meta.stages.find(x => x.id === s) || {}).label || s);
const sourceLabel = s => esc((S.meta.sources.find(x => x.id === s) || {}).label || s);
const typeLabel = t => esc((S.meta.activityTypes.find(x => x.id === t) || {}).label || t);
const userName = u => { const x = S.users.find(a => a.username === u); return esc(x ? x.displayName : (u || '')); };
const initials = name => esc(String(name || '?').split(/\s+/).map(p => p[0]).join('').slice(0, 2).toUpperCase());

/** ₹12,50,000 */
function money(n) {
  if (n == null) return '-';
  return '₹' + Number(n).toLocaleString('en-IN', { maximumFractionDigits: 0 });
}
/** ₹12.5L, ₹1.2Cr - short form for cards and charts */
function moneyShort(n) {
  if (n == null) return '-';
  const a = Math.abs(n);
  const f = (x, s) => '₹' + (Math.round(x * 10) / 10).toLocaleString('en-IN') + s;
  if (a >= 1e7) return f(n / 1e7, 'Cr');
  if (a >= 1e5) return f(n / 1e5, 'L');
  if (a >= 1e3) return f(n / 1e3, 'K');
  return '₹' + n;
}
const pct = v => v == null ? '-' : (Math.round(v * 10) / 10) + '%';

function dateOnly(iso) {
  if (!iso) return '-';
  const d = new Date(iso.length === 10 ? iso + 'T00:00:00' : iso);
  return d.toLocaleDateString('en-IN', { day: 'numeric', month: 'short', year: 'numeric' });
}
function when(iso) {
  if (!iso) return '';
  return new Date(iso).toLocaleString('en-IN', { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' });
}
function todayStr() {
  const d = new Date();
  return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0');
}
function addDays(n) {
  const d = new Date(); d.setDate(d.getDate() + n);
  return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0');
}
/** "in 5 days", "3 days late", "today" for a yyyy-mm-dd date */
function relDay(day) {
  if (!day) return '';
  const ms = new Date(day + 'T00:00:00') - new Date(todayStr() + 'T00:00:00');
  const n = Math.round(ms / 86400000);
  if (n === 0) return 'today';
  if (n === 1) return 'tomorrow';
  if (n === -1) return 'yesterday';
  return n > 0 ? 'in ' + n + ' days' : (-n) + ' days late';
}

function toast(msg, err = false) {
  const t = document.createElement('div');
  t.className = 'toast' + (err ? ' err' : '');
  t.setAttribute('role', 'status');
  t.textContent = msg;
  document.body.appendChild(t);
  setTimeout(() => t.remove(), err ? 4500 : 2600);
}

async function api(method, path, body) {
  const opt = { method, headers: {} };
  if (S.token) opt.headers.Authorization = 'Bearer ' + S.token;
  if (body !== undefined) { opt.headers['Content-Type'] = 'application/json'; opt.body = JSON.stringify(body); }
  const res = await fetch(path, opt);
  if (res.status === 401 && path !== '/api/auth/login') { signOut(true); throw new Error('Your session ended. Please sign in again.'); }
  const ct = res.headers.get('content-type') || '';
  const data = ct.includes('json') ? await res.json() : await res.text();
  if (!res.ok) throw new Error((data && data.error) || 'Request failed (' + res.status + ')');
  return data;
}

async function run(btn, fn) {
  if (btn) btn.disabled = true;
  try { return await fn(); } catch (e) { toast(e.message, true); } finally { if (btn) btn.disabled = false; }
}

function options(list, selected, blank) {
  // list: array of {id,label} or plain strings
  let h = blank != null ? `<option value="">${esc(blank)}</option>` : '';
  for (const o of list) {
    const id = typeof o === 'string' ? o : o.id;
    const lb = typeof o === 'string' ? label(o) : esc(o.label);
    h += `<option value="${esc(id)}" ${String(id) === String(selected ?? '') ? 'selected' : ''}>${lb}</option>`;
  }
  return h;
}
const userOptions = (sel, blank) => options(S.users.map(u => ({ id: u.username, label: u.displayName })), sel, blank);
const sellerList = () => S.users.filter(u => u.role !== 'ADMIN');

/** Sets bar widths / heights from data-w / data-h after HTML is placed (no inline styles allowed). */
function paintBars(root = document) {
  requestAnimationFrame(() => {
    $$('[data-w]', root).forEach(e => { e.style.width = Math.max(0, Math.min(100, Number(e.dataset.w))) + '%'; });
    $$('[data-h]', root).forEach(e => { e.style.height = Math.max(0, Math.min(100, Number(e.dataset.h))) + '%'; });
  });
}

const stageBadge = s => `<span class="badge s-${esc(s)}">${stageLabel(s)}</span>`;
const healthTag = d => `<span class="health ${esc(d.healthLabel)}" title="Deal health score">${esc(d.health)}</span>`;

// ------------------------------------------------------------------ modal
const modal = {
  el: $('#modal'),
  submit: null,
  open({ title, body, submitText = 'Save', danger = false, onSubmit, onOpen, wide = false }) {
    $('#modalTitle').textContent = title;
    $('#modalBody').innerHTML = body;
    $('#modalErr').textContent = '';
    $('#modalFoot').innerHTML = `<button type="button" id="modalCancel">Cancel</button>` +
      (onSubmit ? `<button type="submit" class="${danger ? 'danger' : 'primary'}" id="modalOk">${esc(submitText)}</button>` : '');
    $('#modalCancel').addEventListener('click', () => modal.close());
    this.submit = onSubmit;
    this.el.classList.toggle('wide', wide);
    if (!this.el.open) this.el.showModal();
    if (onOpen) onOpen($('#modalBody'));
    const first = $('#modalBody input:not([type=hidden]), #modalBody select, #modalBody textarea');
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

// ------------------------------------------------------------------ theme
function applyTheme(t) {
  if (t) document.documentElement.setAttribute('data-theme', t);
  const dark = t ? t === 'dark' : matchMedia('(prefers-color-scheme: dark)').matches;
  $('#themeBtn').textContent = dark ? 'Light mode' : 'Dark mode';
}
applyTheme(store.getLocal('theme'));
$('#themeBtn').addEventListener('click', () => {
  const cur = document.documentElement.getAttribute('data-theme') || (matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light');
  const next = cur === 'dark' ? 'light' : 'dark';
  store.setLocal('theme', next); applyTheme(next);
});

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
  $('#lu').value = u; $('#lp').value = p; $('#loginForm').requestSubmit();
}));

$('#logoutBtn').addEventListener('click', async () => {
  try { await api('POST', '/api/auth/logout', {}); } catch { /* ignore */ }
  signOut();
});

function signOut(expired) {
  S.token = null; S.user = null; store.set('token', null);
  modal.close();
  $('#app').classList.add('hidden'); $('#login').classList.remove('hidden');
  $('#loginErr').textContent = expired ? 'Your session ended. Please sign in again.' : '';
}

async function boot() {
  S.token = S.token || store.get('token');
  if (!S.token) { signOut(); return; }
  try {
    S.user = await api('GET', '/api/auth/me');
    [S.meta, S.users] = await Promise.all([api('GET', '/api/meta'), api('GET', '/api/users')]);
  } catch { signOut(); return; }
  $('#login').classList.add('hidden'); $('#app').classList.remove('hidden');
  const roleText = { ADMIN: 'Admin', MANAGER: 'Manager', SALES_REP: 'Sales rep' }[S.user.role] || S.user.role;
  $('#who').textContent = S.user.displayName === roleText ? S.user.displayName : S.user.displayName + ' · ' + roleText;
  $('#navTeam').classList.toggle('hidden', S.user.role !== 'ADMIN');
  S.dashScope = S.user.role === 'SALES_REP' ? 'me' : 'team';
  if (!location.hash || location.hash === '#/') location.hash = '#/dashboard';
  route();
  refreshBadge();
}

async function refreshBadge() {
  try {
    const list = await api('GET', '/api/activities?owner=me&status=overdue');
    const b = $('#navOverdue');
    b.textContent = list.length; b.classList.toggle('hidden', list.length === 0);
    b.title = list.length + ' overdue';
  } catch { /* ignore */ }
}

$('#who').addEventListener('click', () => {
  modal.open({
    title: 'Change your password',
    body: `<div class="field"><label for="pc">Current password</label><input id="pc" name="current" type="password" autocomplete="current-password" required></div>
      <div class="field"><label for="pn">New password</label><input id="pn" name="next" type="password" autocomplete="new-password" minlength="8" required>
      <div class="hint">At least 8 characters. You will stay signed in here; other devices are signed out.</div></div>`,
    submitText: 'Change password',
    onSubmit: async d => { await api('POST', '/api/auth/password', d); toast('Password changed'); }
  });
});

// ------------------------------------------------------------------ router
window.addEventListener('hashchange', route);
$('#menuBtn').addEventListener('click', () => $('#side').classList.toggle('open'));
$('#quickDeal').addEventListener('click', () => dealForm(null));
$('#searchForm').addEventListener('submit', e => {
  e.preventDefault();
  S.dealFilter = { ...S.dealFilter, q: $('#globalSearch').value.trim(), status: 'all' };
  if (location.hash === '#/deals') route(); else location.hash = '#/deals';
});

async function route() {
  if (!S.user) return;
  $('#side').classList.remove('open');
  const parts = location.hash.replace(/^#\/?/, '').split('/');
  const page = parts[0] || 'dashboard';
  $$('#nav a').forEach(a => a.classList.toggle('active', a.dataset.nav === page));
  const pages = { dashboard: pageDashboard, board: pageBoard, deals: parts[1] ? () => pageDeal(parts[1]) : pageDeals,
    activities: pageActivities, accounts: parts[1] ? () => pageAccount(parts[1]) : pageAccounts, reports: pageReports, team: pageTeam };
  const fn = pages[page];
  view().innerHTML = '<div class="empty">Loading...</div>';
  if (!fn) { view().innerHTML = '<div class="empty">Page not found. <a href="#/dashboard">Go to the dashboard</a></div>'; return; }
  try { await fn(); } catch (e) { view().innerHTML = `<div class="card card-pad"><p class="bad-text">${esc(e.message)}</p><a href="#/dashboard">Back to dashboard</a></div>`; }
  view().focus({ preventScroll: true });
}

/** Re-renders the current page (after a change). */
const refresh = () => { route(); refreshBadge(); };

// ------------------------------------------------------------------ shared bits
function dealListItems(list, extra) {
  if (!list.length) return '<div class="empty">Nothing here right now.</div>';
  return '<ul class="list">' + list.map(d => `
    <li>
      <div class="grow">
        <a class="title" href="#/deals/${esc(d.id)}">${esc(d.title)}</a>
        <div class="meta">${esc(d.accountName)} · ${userName(d.owner)} ${extra ? '· ' + extra(d) : ''}</div>
      </div>
      <div class="right"><div class="num"><b>${moneyShort(d.value)}</b></div>${stageBadge(d.stage)}</div>
    </li>`).join('') + '</ul>';
}

function activityItems(list, opts = {}) {
  if (!list.length) return `<div class="empty">${esc(opts.empty || 'No activities.')}</div>`;
  return '<ul class="list">' + list.map(a => `
    <li class="act-row ${a.done ? 'done' : ''}">
      <input type="checkbox" data-done="${esc(a.id)}" ${a.done ? 'checked' : ''} aria-label="Mark ${esc(a.subject)} as done">
      <div class="grow">
        <span class="title">${typeLabel(a.type)}: ${esc(a.subject)}</span>
        <div class="meta">
          ${a.dealId ? `<a href="#/deals/${esc(a.dealId)}">${esc(a.dealTitle)}</a> · ` : ''}
          ${opts.showOwner ? esc(a.ownerName) + ' · ' : ''}
          ${a.done ? 'Done ' + esc(when(a.doneAt)) + (a.outcome ? ' - ' + esc(a.outcome) : '')
            : a.dueDate ? `<span class="${a.overdue ? 'bad-text' : a.dueToday ? 'warn-text' : ''}">Due ${esc(dateOnly(a.dueDate))} (${esc(relDay(a.dueDate))})</span>` : 'No due date'}
        </div>
      </div>
      <button class="ghost small" data-edit-act="${esc(a.id)}">Edit</button>
    </li>`).join('') + '</ul>';
}

/** Hooks up checkboxes and edit buttons made by activityItems(). */
function wireActivities(root, list) {
  $$('[data-done]', root).forEach(cb => cb.addEventListener('change', () => run(cb, async () => {
    await api('POST', `/api/activities/${cb.dataset.done}/complete`, { done: cb.checked });
    toast(cb.checked ? 'Marked as done' : 'Marked as not done');
    refresh();
  })));
  $$('[data-edit-act]', root).forEach(b => b.addEventListener('click', () => {
    activityForm(list.find(a => a.id === b.dataset.editAct));
  }));
}

// ------------------------------------------------------------------ dashboard
async function pageDashboard() {
  const scope = S.dashScope;
  const d = await api('GET', '/api/dashboard?owner=' + (scope === 'team' ? 'team' : 'me'));
  const k = d.kpis;
  const att = k.attainment;
  const maxStage = Math.max(1, ...d.stages.map(s => s.value));
  view().innerHTML = `
    <div class="page-head">
      <div><h1>Good to see you, ${esc(S.user.displayName.split(' ')[0])}</h1>
        <p>${scope === 'team' ? 'Whole team' : 'Your deals'} · ${esc(d.month)}</p></div>
      <div class="spacer"></div>
      <div class="seg" role="group" aria-label="Whose numbers">
        <button data-scope="me" class="${scope === 'me' ? 'on' : ''}">My numbers</button>
        <button data-scope="team" class="${scope === 'team' ? 'on' : ''}">Team</button>
      </div>
    </div>
    <div class="kpis">
      <div class="card kpi"><div class="k-label">Open pipeline</div><div class="k-value">${moneyShort(k.openValue)}</div>
        <div class="k-sub">${k.openCount} open deals</div></div>
      <div class="card kpi"><div class="k-label">Weighted forecast</div><div class="k-value">${moneyShort(k.weightedValue)}</div>
        <div class="k-sub">Value × chance of winning</div></div>
      <div class="card kpi"><div class="k-label">Won this month</div><div class="k-value">${moneyShort(k.wonThisMonth)}</div>
        <div class="k-sub">${k.target ? `${pct(att)} of ${moneyShort(k.target)} target` : k.wonThisMonthCount + ' deals won'}</div>
        ${k.target ? `<div class="bar gold tall" title="${pct(att)} of target"><span data-w="${esc(att)}"></span></div>` : ''}</div>
      <div class="card kpi"><div class="k-label">Win rate (90 days)</div><div class="k-value">${pct(k.winRate)}</div>
        <div class="k-sub">Won ÷ (won + lost)</div></div>
      <div class="card kpi"><div class="k-label">Average won deal</div><div class="k-value">${moneyShort(k.avgWonValue)}</div>
        <div class="k-sub">${k.avgCycleDays == null ? 'No wins yet' : 'Takes about ' + Math.round(k.avgCycleDays) + ' days to win'}</div></div>
      <div class="card kpi"><div class="k-label">Your tasks</div><div class="k-value ${k.overdueTasks ? 'bad-text' : ''}">${k.overdueTasks} overdue</div>
        <div class="k-sub">${k.todayTasks} due today</div></div>
    </div>
    <div class="split">
      <div class="stack">
        <section class="card">
          <div class="card-head"><h2>Pipeline by stage</h2><div class="spacer"></div><a href="#/board" class="small">Open board</a></div>
          <div class="card-body stage-bars">
            ${d.stages.map(s => `<div class="stage-bar">
              <div class="top"><span>${esc(s.label)} <span class="muted">· ${s.count}</span></span><span class="num"><b>${moneyShort(s.value)}</b> <span class="muted small">(${moneyShort(s.weighted)} weighted)</span></span></div>
              <div class="bar tall"><span class="s-${esc(s.stage)}" data-w="${s.value / maxStage * 100}"></span></div></div>`).join('')}
          </div>
        </section>
        <section class="card">
          <div class="card-head"><h2>Needs attention</h2><span class="muted small">Not worked on for ${S.meta.staleDays}+ days, or close date has passed</span></div>
          ${dealListItems(d.attention, x => x.closeOverdue ? `<span class="bad-text">close date passed</span>` : `<span class="warn-text">${x.daysIdle} days quiet</span>`)}
        </section>
        <section class="card">
          <div class="card-head"><h2>Closing in the next 30 days</h2></div>
          ${dealListItems(d.closingSoon, x => esc(dateOnly(x.expectedClose)) + ' · ' + x.effectiveProbability + '%')}
        </section>
      </div>
      <div class="stack">
        <section class="card">
          <div class="card-head"><h2>Tasks due soon</h2><div class="spacer"></div><button class="small" id="dashAddTask">+ Task</button></div>
          <div id="dashTasks">${activityItems(d.tasks, { empty: 'Nothing due in the next 2 days.' })}</div>
        </section>
        <section class="card">
          <div class="card-head"><h2>Healthiest deals</h2><span class="muted small">Work these to close</span></div>
          ${dealListItems(d.hotDeals, x => healthTag(x))}
        </section>
        <section class="card">
          <div class="card-head"><h2>Recent wins</h2></div>
          ${dealListItems(d.recentWins, x => 'won ' + esc(dateOnly(x.closedAt)))}
        </section>
      </div>
    </div>`;
  $$('[data-scope]').forEach(b => b.addEventListener('click', () => { S.dashScope = b.dataset.scope; route(); }));
  $('#dashAddTask').addEventListener('click', () => activityForm(null));
  wireActivities($('#dashTasks'), d.tasks);
  paintBars(view());
}

// ------------------------------------------------------------------ board
async function pageBoard() {
  const q = new URLSearchParams({ status: 'open', sort: 'score' });
  if (S.boardOwner) q.set('owner', S.boardOwner);
  const deals = await api('GET', '/api/deals?' + q);
  const open = S.meta.stages.filter(s => s.open);
  view().innerHTML = `
    <div class="page-head">
      <div><h1>Pipeline board</h1><p>Drag a deal to another stage, or onto Won / Lost. You can also use the menu on each card.</p></div>
      <div class="spacer"></div>
      <select id="boardOwner" aria-label="Show deals of">${options([{ id: 'me', label: 'My deals' }, ...sellerList().map(u => ({ id: u.username, label: u.displayName }))], S.boardOwner, 'Everyone')}</select>
      <button class="primary" id="boardNew">+ New deal</button>
    </div>
    <div class="board" id="board">
      ${open.map(st => {
        const list = deals.filter(d => d.stage === st.id);
        const total = list.reduce((a, d) => a + d.value, 0);
        return `<section class="column c-${esc(st.id)}" data-stage="${esc(st.id)}" aria-label="${esc(st.label)}">
          <div class="column-head"><div class="row"><h3>${esc(st.label)} <span class="muted">(${list.length})</span></h3><span class="tot">${moneyShort(total)}</span></div>
            <div class="tiny muted">Default chance ${st.probability}%</div></div>
          <div class="column-body">${list.map(cardHtml).join('') || '<div class="empty small">Drop deals here</div>'}</div>
        </section>`;
      }).join('')}
    </div>
    <div class="drop-zones">
      <div class="drop-zone won" data-stage="WON">Drop here to mark as Won</div>
      <div class="drop-zone lost" data-stage="LOST">Drop here to mark as Lost</div>
    </div>`;

  $('#boardOwner').addEventListener('change', e => { S.boardOwner = e.target.value; route(); });
  $('#boardNew').addEventListener('click', () => dealForm(null));

  // drag and drop
  $$('.deal-card[draggable=true]').forEach(c => {
    c.addEventListener('dragstart', e => { e.dataTransfer.setData('text/plain', c.dataset.id); e.dataTransfer.effectAllowed = 'move'; c.classList.add('dragging'); });
    c.addEventListener('dragend', () => c.classList.remove('dragging'));
  });
  $$('[data-stage]', view()).forEach(zone => {
    zone.addEventListener('dragover', e => { e.preventDefault(); zone.classList.add('drop-over'); });
    zone.addEventListener('dragleave', e => { if (!zone.contains(e.relatedTarget)) zone.classList.remove('drop-over'); });
    zone.addEventListener('drop', e => {
      e.preventDefault(); zone.classList.remove('drop-over');
      const id = e.dataTransfer.getData('text/plain');
      const d = deals.find(x => x.id === id);
      if (d && d.stage !== zone.dataset.stage) moveDeal(d, zone.dataset.stage);
    });
  });
  // keyboard / touch friendly move menu
  $$('[data-move]').forEach(sel => sel.addEventListener('change', () => {
    const d = deals.find(x => x.id === sel.dataset.move);
    if (sel.value) moveDeal(d, sel.value);
  }));
}

function cardHtml(d) {
  const flags = [];
  if (d.closeOverdue) flags.push('<span class="badge bad">Close date passed</span>');
  else if (d.stale) flags.push(`<span class="badge warn">${d.daysIdle}d quiet</span>`);
  if (d.priority === 'HIGH') flags.push('<span class="badge p-HIGH">High</span>');
  if (d.nextActivity && d.nextActivity.overdue) flags.push('<span class="badge bad">Task overdue</span>');
  const moves = S.meta.stages.filter(s => s.id !== d.stage);
  return `<article class="deal-card" data-id="${esc(d.id)}" draggable="${d.canEdit ? 'true' : 'false'}">
    <a class="t" href="#/deals/${esc(d.id)}">${esc(d.title)}</a>
    <div class="a">${esc(d.accountName)}</div>
    <div class="b"><span class="v">${moneyShort(d.value)}</span><span class="muted">${d.effectiveProbability}%</span>
      <span class="spacer"></span>${healthTag(d)}<span class="avatar" title="${esc(d.ownerName)}">${initials(d.ownerName)}</span></div>
    <div class="tiny muted">${d.expectedClose ? 'Close ' + esc(dateOnly(d.expectedClose)) : 'No close date'}</div>
    ${flags.length ? `<div class="flags">${flags.join('')}</div>` : ''}
    ${d.canEdit ? `<select data-move="${esc(d.id)}" aria-label="Move ${esc(d.title)} to stage">${options(moves.map(s => ({ id: s.id, label: s.label })), '', 'Move to...')}</select>` : ''}
  </article>`;
}

/** Moves a deal; asks for a reason when it is lost. */
function moveDeal(d, stage) {
  if (stage === 'LOST') {
    lostForm(d);
    return;
  }
  run(null, async () => {
    await api('POST', `/api/deals/${d.id}/stage`, { stage });
    toast(stage === 'WON' ? 'Deal won. Nice work!' : 'Moved to ' + stageLabel(stage).replace(/&amp;/g, '&'));
    refresh();
  });
}

function lostForm(d) {
  modal.open({
    title: 'Mark "' + d.title + '" as lost',
    body: `<p class="muted small">Knowing why deals are lost helps the team win the next one.</p>
      <div class="field"><label for="lr">Reason</label><select id="lr" name="reasonPick" required>${options(S.meta.lostReasons, '', 'Choose a reason')}</select></div>
      <div class="field"><label for="lrx">Details (optional)</label><input id="lrx" name="details" maxlength="150" placeholder="e.g. Went with a cheaper local vendor"></div>`,
    submitText: 'Mark as lost', danger: true,
    onSubmit: async f => {
      if (!f.reasonPick) throw new Error('Please choose a reason');
      const reason = f.details ? f.reasonPick + ': ' + f.details : f.reasonPick;
      await api('POST', `/api/deals/${d.id}/stage`, { stage: 'LOST', lostReason: reason });
      toast('Deal marked as lost');
      refresh();
    }
  });
}

// ------------------------------------------------------------------ deals list
async function pageDeals() {
  const f = S.dealFilter;
  const q = new URLSearchParams();
  Object.entries(f).forEach(([k, v]) => { if (v) q.set(k, v); });
  const deals = await api('GET', '/api/deals?' + q);
  const total = deals.reduce((a, d) => a + d.value, 0);
  const weighted = deals.reduce((a, d) => a + d.weightedValue, 0);
  const sortBtn = (key, text) => `<button class="sort ${f.sort === key ? 'on' : ''}" data-sort="${key}">${text}${f.sort === key ? ' ↓' : ''}</button>`;
  view().innerHTML = `
    <div class="page-head">
      <div><h1>Deals</h1><p>${deals.length} deal${deals.length === 1 ? '' : 's'} · ${money(total)} total · ${money(weighted)} weighted</p></div>
      <div class="spacer"></div>
      <button id="exportCsv">Export to CSV</button>
      <button class="primary" id="newDeal">+ New deal</button>
    </div>
    <section class="card">
      <form class="filters" id="dealFilters">
        <input type="search" name="q" value="${esc(f.q)}" placeholder="Search deal or company" aria-label="Search">
        <select name="status" aria-label="Status">${options([{ id: 'open', label: 'Open' }, { id: 'won', label: 'Won' }, { id: 'lost', label: 'Lost' }, { id: 'closed', label: 'Won or lost' }], f.status, 'All deals')}</select>
        <select name="stage" aria-label="Stage">${options(S.meta.stages.map(s => ({ id: s.id, label: s.label })), f.stage, 'Any stage')}</select>
        <select name="owner" aria-label="Owner">${options([{ id: 'me', label: 'My deals' }, ...sellerList().map(u => ({ id: u.username, label: u.displayName }))], f.owner, 'Any owner')}</select>
        <select name="health" aria-label="Health">${options([{ id: 'HOT', label: 'Hot (70+)' }, { id: 'WARM', label: 'Warm (45-69)' }, { id: 'COLD', label: 'Cold (under 45)' }], f.health, 'Any health')}</select>
        <select name="closing" aria-label="Closing">${options([{ id: 'this-month', label: 'Closing this month' }, { id: 'next-30', label: 'Closing in 30 days' }, { id: 'overdue', label: 'Close date passed' }], f.closing, 'Any close date')}</select>
        <button type="button" class="ghost small" id="clearFilters">Clear</button>
      </form>
      <div class="table-wrap">
        <table>
          <thead><tr>
            <th>${sortBtn('title', 'Deal')}</th><th>Stage</th><th class="num">${sortBtn('value', 'Value')}</th>
            <th class="num">${sortBtn('weighted', 'Weighted')}</th><th>${sortBtn('close', 'Close date')}</th>
            <th>${sortBtn('score', 'Health')}</th><th>Owner</th><th>Next step</th><th>${sortBtn('updated', 'Updated')}</th>
          </tr></thead>
          <tbody>
          ${deals.map(d => `<tr>
            <td><a href="#/deals/${esc(d.id)}">${esc(d.title)}</a><div class="small muted">${esc(d.accountName)} · ${esc(d.id)}</div></td>
            <td>${stageBadge(d.stage)}</td>
            <td class="num">${money(d.value)}</td>
            <td class="num muted">${money(d.weightedValue)}</td>
            <td class="nowrap ${d.closeOverdue ? 'bad-text' : ''}">${esc(dateOnly(d.expectedClose))}</td>
            <td>${healthTag(d)}</td>
            <td class="nowrap">${esc(d.ownerName)}</td>
            <td class="small">${d.nextActivity ? `<span class="${d.nextActivity.overdue ? 'bad-text' : ''}">${esc(d.nextActivity.subject)} · ${esc(relDay(d.nextActivity.dueDate))}</span>` : d.stage === 'WON' || d.stage === 'LOST' ? '' : '<span class="warn-text">None planned</span>'}</td>
            <td class="small muted nowrap">${esc(dateOnly(d.updatedAt))}</td>
          </tr>`).join('') || '<tr><td colspan="9"><div class="empty">No deals match these filters.</div></td></tr>'}
          </tbody>
        </table>
      </div>
    </section>`;
  const form = $('#dealFilters');
  form.addEventListener('change', () => { S.dealFilter = { ...S.dealFilter, ...Object.fromEntries(new FormData(form)) }; route(); });
  form.addEventListener('submit', e => { e.preventDefault(); S.dealFilter = { ...S.dealFilter, ...Object.fromEntries(new FormData(form)) }; route(); });
  $('#clearFilters').addEventListener('click', () => { S.dealFilter = { status: '', stage: '', owner: '', health: '', closing: '', q: '', sort: 'updated' }; $('#globalSearch').value = ''; route(); });
  $$('[data-sort]').forEach(b => b.addEventListener('click', () => { S.dealFilter.sort = b.dataset.sort; route(); }));
  $('#newDeal').addEventListener('click', () => dealForm(null));
  $('#exportCsv').addEventListener('click', e => run(e.currentTarget, async () => {
    const res = await fetch('/api/deals/export.csv?' + q, { headers: { Authorization: 'Bearer ' + S.token } });
    if (!res.ok) throw new Error('Export failed');
    const blob = await res.blob();
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = 'deals-' + todayStr() + '.csv';
    document.body.appendChild(a); a.click(); a.remove();
    setTimeout(() => URL.revokeObjectURL(a.href), 2000);
  }));
}

// ------------------------------------------------------------------ deal form
async function dealForm(deal, presetAccount) {
  const accounts = await api('GET', '/api/accounts');
  const isNew = !deal || !!deal._new; // _new: re-opened after adding a company from inside this form
  const d = deal || { stage: 'LEAD', priority: 'MEDIUM', source: 'WEBSITE', owner: S.user.username, accountId: presetAccount || '', expectedClose: addDays(30) };
  const canAssign = S.user.role !== 'SALES_REP';
  const stageDefault = s => (S.meta.stages.find(x => x.id === s) || {}).probability;
  modal.open({
    title: isNew ? 'New deal' : 'Edit deal',
    body: `
      <div class="field"><label for="dt">Deal name</label><input id="dt" name="title" maxlength="120" required value="${esc(d.title)}" placeholder="e.g. CRM rollout for 40 users"></div>
      <div class="grid2">
        <div class="field"><label for="da">Company</label>
          <select id="da" name="accountId" required>${options(accounts.map(a => ({ id: a.id, label: a.name })), d.accountId, 'Choose a company')}</select>
          <div class="hint"><button type="button" class="link" id="newAccInline">Add a new company</button></div></div>
        <div class="field"><label for="dc">Contact person</label><select id="dc" name="contactId"></select></div>
      </div>
      <div class="grid2">
        <div class="field"><label for="dv">Value (₹)</label><input id="dv" name="value" type="number" min="0" step="1000" required value="${esc(d.value ?? '')}" placeholder="500000"></div>
        <div class="field"><label for="dd">Expected close date</label><input id="dd" name="expectedClose" type="date" value="${esc(d.expectedClose || '')}"></div>
      </div>
      <div class="grid2">
        ${isNew ? `<div class="field"><label for="ds">Stage</label><select id="ds" name="stage">${options(S.meta.stages.filter(s => s.open).map(s => ({ id: s.id, label: s.label })), d.stage)}</select></div>` : ''}
        <div class="field"><label for="dp">Chance of winning (%)</label><input id="dp" name="probability" type="number" min="0" max="100" value="${esc(d.probability ?? '')}" placeholder="Stage default: ${stageDefault(d.stage)}%">
          <div class="hint">Leave empty to use the stage default.</div></div>
        <div class="field"><label for="dsrc">Lead source</label><select id="dsrc" name="source">${options(S.meta.sources, d.source)}</select></div>
        <div class="field"><label for="dpr">Priority</label><select id="dpr" name="priority">${options(S.meta.priorities, d.priority)}</select></div>
        ${canAssign ? `<div class="field"><label for="dow">Owner</label><select id="dow" name="owner">${options(sellerList().map(u => ({ id: u.username, label: u.displayName })), d.owner)}</select></div>` : ''}
      </div>
      <div class="field"><label for="ddesc">Description</label><textarea id="ddesc" name="description" maxlength="4000" placeholder="What do they need? Who is involved?">${esc(d.description)}</textarea></div>`,
    submitText: isNew ? 'Create deal' : 'Save changes',
    onOpen: async root => {
      const accSel = $('#da', root), conSel = $('#dc', root);
      const loadContacts = async () => {
        conSel.innerHTML = '<option value="">No contact yet</option>';
        if (!accSel.value) return;
        const cs = await api('GET', '/api/contacts?accountId=' + encodeURIComponent(accSel.value));
        conSel.innerHTML = options(cs.map(c => ({ id: c.id, label: c.name + (c.title ? ' - ' + c.title : '') })), d.accountId === accSel.value ? d.contactId : '', 'No contact yet');
      };
      accSel.addEventListener('change', loadContacts);
      await loadContacts();
      const st = $('#ds', root);
      if (st) st.addEventListener('change', () => { $('#dp', root).placeholder = 'Stage default: ' + stageDefault(st.value) + '%'; });
      $('#newAccInline', root).addEventListener('click', () => {
        const typed = Object.fromEntries(new FormData($('#modalForm')).entries());
        const keep = isNew ? { ...d, ...typed, _new: true } : { ...deal, ...typed };
        accountForm(null, acc => dealForm({ ...keep, accountId: acc.id, contactId: '' }, acc.id));
      });
    },
    onSubmit: async f => {
      const body = { ...f, value: f.value === '' ? null : Number(f.value), probability: f.probability === '' ? null : Number(f.probability) };
      if (!body.contactId) body.contactId = null;
      if (isNew) {
        const created = await api('POST', '/api/deals', body);
        toast('Deal created');
        location.hash = '#/deals/' + created.id;
      } else {
        delete body.stage;
        await api('PUT', '/api/deals/' + deal.id, body);
        toast('Deal saved');
        refresh();
      }
    }
  });
}

// ------------------------------------------------------------------ deal page
async function pageDeal(id) {
  const d = await api('GET', '/api/deals/' + encodeURIComponent(id));
  const hd = d.healthDetail;
  const order = ['LEAD', 'QUALIFIED', 'PROPOSAL', 'NEGOTIATION'];
  const curIdx = order.indexOf(d.stage);
  const stepClass = s => {
    if (s === d.stage) return 'current';
    if (d.stage === 'WON') return 'done';
    if (curIdx >= 0 && order.indexOf(s) >= 0 && order.indexOf(s) < curIdx) return 'done';
    return '';
  };
  view().innerHTML = `
    <div class="page-head">
      <div>
        <div class="small muted"><a href="#/deals">Deals</a> / ${esc(d.id)}</div>
        <h1>${esc(d.title)}</h1>
        <p><a href="#/accounts/${esc(d.accountId)}">${esc(d.accountName)}</a> · owned by ${esc(d.ownerName)} · ${stageBadge(d.stage)}
          ${d.priority === 'HIGH' ? '<span class="badge p-HIGH">High priority</span>' : ''}</p>
      </div>
      <div class="spacer"></div>
      ${d.canEdit ? `<button id="editDeal">Edit</button><button class="danger" id="delDeal">Delete</button>` : '<span class="muted small">Only the owner or a manager can change this deal</span>'}
    </div>
    <section class="card card-pad">
      <dl class="facts">
        <div><dt>Value</dt><dd class="num">${money(d.value)}</dd></div>
        <div><dt>Chance of winning</dt><dd>${d.effectiveProbability}%${d.probability != null && d.stage !== 'WON' && d.stage !== 'LOST' ? ' <span class="muted small">(set by hand)</span>' : ''}</dd></div>
        <div><dt>Weighted value</dt><dd class="num">${money(d.weightedValue)}</dd></div>
        <div><dt>${d.stage === 'WON' || d.stage === 'LOST' ? 'Closed' : 'Expected close'}</dt>
          <dd class="${d.closeOverdue ? 'bad-text' : ''}">${esc(dateOnly(d.closedAt || d.expectedClose))}${d.expectedClose && !d.closedAt ? ' <span class="muted small">(' + esc(relDay(d.expectedClose)) + ')</span>' : ''}</dd></div>
        <div><dt>Source</dt><dd>${sourceLabel(d.source)}</dd></div>
        <div><dt>Contact</dt><dd>${d.contact ? esc(d.contact.name) + (d.contact.decisionMaker ? ' <span class="badge">Decision maker</span>' : '') : '<span class="muted">None</span>'}</dd></div>
        <div><dt>In this stage</dt><dd>${d.daysInStage} days</dd></div>
        <div><dt>Last worked on</dt><dd class="${d.stale ? 'warn-text' : ''}">${d.daysIdle === 0 ? 'Today' : d.daysIdle + ' days ago'}</dd></div>
      </dl>
      ${d.lostReason ? `<p class="bad-text"><b>Lost because:</b> ${esc(d.lostReason)}</p>` : ''}
      ${d.description ? `<p class="muted">${esc(d.description)}</p>` : ''}
      ${d.canEdit ? `<div class="stepper" role="group" aria-label="Move to stage">
        ${order.map(s => `<button class="${stepClass(s)}" data-step="${s}" ${s === d.stage ? 'aria-current="step"' : ''}>${stageLabel(s)}</button>`).join('')}
        <button class="won ${d.stage === 'WON' ? 'current' : ''}" data-step="WON">Won</button>
        <button class="lost ${d.stage === 'LOST' ? 'current' : ''}" data-step="LOST">Lost</button>
      </div>` : ''}
    </section>
    <div class="split" id="dealSplit">
      <div class="stack">
        <section class="card">
          <div class="card-head"><h2>Activities</h2><div class="spacer"></div><button class="small primary" id="addAct">+ Plan activity</button><button class="small" id="logAct">Log done</button></div>
          <div id="dealActs">${activityItems(d.activities, { showOwner: true, empty: 'No activities yet. Plan the next step so this deal keeps moving.' })}</div>
        </section>
        <section class="card">
          <div class="card-head"><h2>Notes &amp; history</h2></div>
          <form class="card-body" id="noteForm">
            <label for="noteText">Add a note</label>
            <textarea id="noteText" name="text" maxlength="4000" placeholder="What did you learn? What did they say?" required></textarea>
            <div class="row"><div class="spacer"></div><button class="primary small" type="submit">Save note</button></div>
          </form>
          <ul class="timeline">
            ${d.timeline.map(t => `<li class="${esc(t.kind)}"><div>${esc(t.text)}</div>
              <div class="when">${esc(when(t.at))} · ${esc(t.by)}
              ${t.kind === 'note' && t.id ? ` · <button class="link small" data-del-note="${esc(t.id)}">Delete</button>` : ''}</div></li>`).join('')}
          </ul>
        </section>
      </div>
      <div class="stack">
        <section class="card card-pad">
          <div class="row"><div class="score-ring ${esc(hd.label)}">${esc(hd.score)}</div>
            <div><h2>Deal health: ${label(hd.label)}</h2><p class="small muted">A 0-100 score from stage, recent activity, contact, close date and next steps.</p></div></div>
          <ul class="factors">
            ${hd.factors.map(x => `<li><span class="pts ${x.points > 0 ? 'pos' : x.points < 0 ? 'neg' : 'zero'}">${x.points > 0 ? '+' : ''}${x.points}</span><span>${esc(x.reason)}</span></li>`).join('')}
          </ul>
        </section>
        ${d.account ? `<section class="card card-pad">
          <h2><a href="#/accounts/${esc(d.account.id)}">${esc(d.account.name)}</a></h2>
          <p class="small muted">${esc(d.account.industry)}${d.account.city ? ' · ' + esc(d.account.city) : ''}${d.account.size ? ' · ' + esc(d.account.size) + ' people' : ''}</p>
          ${d.contact ? `<p class="small"><b>${esc(d.contact.name)}</b>${d.contact.title ? ', ' + esc(d.contact.title) : ''}<br>
            ${d.contact.email ? `<a href="mailto:${esc(d.contact.email)}">${esc(d.contact.email)}</a><br>` : ''}${esc(d.contact.phone)}</p>` : ''}
        </section>` : ''}
      </div>
    </div>`;

  if (d.canEdit) {
    $('#editDeal').addEventListener('click', () => dealForm(d));
    $('#delDeal').addEventListener('click', () => confirmBox('Delete this deal?', 'This removes "' + d.title + '" and its activities for good. This cannot be undone.', 'Delete deal', async () => {
      await api('DELETE', '/api/deals/' + d.id); toast('Deal deleted'); location.hash = '#/deals';
    }));
    $$('[data-step]').forEach(b => b.addEventListener('click', () => { if (b.dataset.step !== d.stage) moveDeal(d, b.dataset.step); }));
  }
  $('#addAct').addEventListener('click', () => activityForm(null, d.id));
  $('#logAct').addEventListener('click', () => activityForm(null, d.id, true));
  wireActivities($('#dealActs'), d.activities);
  $('#noteForm').addEventListener('submit', e => {
    e.preventDefault();
    run(e.submitter, async () => {
      await api('POST', `/api/deals/${d.id}/notes`, { text: $('#noteText').value });
      toast('Note saved'); refresh();
    });
  });
  $$('[data-del-note]').forEach(b => b.addEventListener('click', () => confirmBox('Delete note?', 'This note will be removed.', 'Delete', async () => {
    await api('DELETE', `/api/deals/${d.id}/notes/${b.dataset.delNote}`); refresh();
  })));
}

// ------------------------------------------------------------------ activity form
async function activityForm(act, dealId, logDone) {
  const isNew = !act;
  const a = act || { type: logDone ? 'CALL' : 'CALL', dueDate: logDone ? todayStr() : addDays(1), owner: S.user.username, dealId: dealId || '' };
  const deals = dealId || (act && act.dealId) ? null : await api('GET', '/api/deals?status=open&sort=title');
  const canAssign = S.user.role !== 'SALES_REP';
  modal.open({
    title: isNew ? (logDone ? 'Log a finished activity' : 'Plan an activity') : 'Edit activity',
    body: `
      <div class="grid2">
        <div class="field"><label for="at">Type</label><select id="at" name="type">${options(S.meta.activityTypes, a.type)}</select></div>
        <div class="field"><label for="ad">${logDone ? 'Date' : 'Due date'}</label><input id="ad" name="dueDate" type="date" value="${esc(a.dueDate || '')}"></div>
      </div>
      <div class="field"><label for="as">What</label><input id="as" name="subject" maxlength="160" required value="${esc(a.subject)}" placeholder="e.g. Call to discuss pricing"></div>
      ${deals ? `<div class="field"><label for="adeal">Deal (optional)</label><select id="adeal" name="dealId">${options(deals.map(x => ({ id: x.id, label: x.title + ' - ' + x.accountName })), a.dealId, 'Not linked to a deal')}</select></div>`
        : `<input type="hidden" name="dealId" value="${esc(a.dealId || dealId || '')}">`}
      ${canAssign ? `<div class="field"><label for="aow">Who does it</label><select id="aow" name="owner">${userOptions(a.owner)}</select></div>` : ''}
      <div class="field"><label for="an">Notes</label><textarea id="an" name="notes" maxlength="2000">${esc(a.notes)}</textarea></div>
      ${logDone || (act && act.done) ? `<div class="field"><label for="ao">Outcome</label><input id="ao" name="outcome" maxlength="1000" value="${esc(a.outcome)}" placeholder="What happened?"></div>` : ''}
      ${!isNew ? `<div class="row"><button type="button" class="danger small" id="delAct">Delete activity</button></div>` : ''}`,
    submitText: isNew ? (logDone ? 'Log activity' : 'Plan activity') : 'Save',
    onOpen: root => {
      const del = $('#delAct', root);
      if (del) del.addEventListener('click', () => confirmBox('Delete activity?', '"' + a.subject + '" will be removed.', 'Delete', async () => {
        await api('DELETE', '/api/activities/' + a.id); toast('Activity deleted'); refresh();
      }));
    },
    onSubmit: async f => {
      if (isNew) {
        await api('POST', '/api/activities', { ...f, done: !!logDone });
        toast(logDone ? 'Activity logged' : 'Activity planned');
      } else {
        delete f.dealId;
        await api('PUT', '/api/activities/' + a.id, f);
        toast('Activity saved');
      }
      refresh();
    }
  });
}

// ------------------------------------------------------------------ activities page
async function pageActivities() {
  const tabs = [['overdue', 'Overdue'], ['today', 'Today'], ['upcoming', 'Upcoming'], ['open', 'All open'], ['done', 'Done']];
  const owner = S.actOwner === 'team' ? '' : S.actOwner;
  const list = await api('GET', `/api/activities?status=${S.actTab}&owner=${encodeURIComponent(owner)}`);
  view().innerHTML = `
    <div class="page-head">
      <div><h1>Activities</h1><p>Calls, emails, meetings and to-dos. Tick one off when it is done.</p></div>
      <div class="spacer"></div>
      <select id="actOwner" aria-label="Whose activities">${options([{ id: 'me', label: 'Mine' }, { id: 'team', label: 'Whole team' }, ...S.users.filter(u => u.username !== S.user.username).map(u => ({ id: u.username, label: u.displayName }))], S.actOwner)}</select>
      <button class="primary" id="newAct">+ Plan activity</button>
    </div>
    <section class="card">
      <div class="filters"><div class="tabs" role="tablist">
        ${tabs.map(([k, t]) => `<button role="tab" aria-selected="${S.actTab === k}" class="${S.actTab === k ? 'on' : ''}" data-tab="${k}">${t}</button>`).join('')}
      </div><span class="spacer"></span><span class="muted small">${list.length} item${list.length === 1 ? '' : 's'}</span></div>
      <div id="actList">${activityItems(list, { showOwner: S.actOwner !== 'me', empty: S.actTab === 'overdue' ? 'Nothing overdue. Well done.' : 'No activities here.' })}</div>
    </section>`;
  $$('[data-tab]').forEach(b => b.addEventListener('click', () => { S.actTab = b.dataset.tab; route(); }));
  $('#actOwner').addEventListener('change', e => { S.actOwner = e.target.value; route(); });
  $('#newAct').addEventListener('click', () => activityForm(null));
  wireActivities($('#actList'), list);
}

// ------------------------------------------------------------------ accounts
async function pageAccounts() {
  const list = await api('GET', '/api/accounts');
  view().innerHTML = `
    <div class="page-head">
      <div><h1>Accounts</h1><p>${list.length} companies you sell to</p></div>
      <div class="spacer"></div>
      <input type="search" id="accSearch" placeholder="Filter companies" aria-label="Filter companies">
      <button class="primary" id="newAcc">+ New company</button>
    </div>
    <section class="card"><div class="table-wrap"><table>
      <thead><tr><th>Company</th><th>Industry</th><th>City</th><th class="num">Contacts</th><th class="num">Open deals</th><th class="num">Open value</th><th class="num">Won so far</th><th>Owner</th></tr></thead>
      <tbody id="accRows">
        ${list.map(a => `<tr data-name="${esc((a.name + ' ' + a.city + ' ' + a.industry).toLowerCase())}">
          <td><a href="#/accounts/${esc(a.id)}">${esc(a.name)}</a>${a.website ? `<div class="small muted">${esc(a.website)}</div>` : ''}</td>
          <td>${esc(a.industry)}</td><td>${esc(a.city)}</td>
          <td class="num">${a.contactCount}</td><td class="num">${a.openDeals}</td>
          <td class="num">${money(a.openValue)}</td><td class="num good-text">${money(a.wonValue)}</td>
          <td>${esc(a.ownerName || '')}</td></tr>`).join('') || '<tr><td colspan="8"><div class="empty">No companies yet.</div></td></tr>'}
      </tbody></table></div></section>`;
  $('#accSearch').addEventListener('input', e => {
    const v = e.target.value.trim().toLowerCase();
    $$('#accRows tr[data-name]').forEach(r => r.classList.toggle('hidden', !!v && !r.dataset.name.includes(v)));
  });
  $('#newAcc').addEventListener('click', () => accountForm(null, a => { location.hash = '#/accounts/' + a.id; }));
}

function accountForm(acc, after) {
  const isNew = !acc;
  const a = acc || { industry: 'Technology', owner: S.user.username };
  modal.open({
    title: isNew ? 'New company' : 'Edit company',
    body: `
      <div class="field"><label for="cn">Company name</label><input id="cn" name="name" maxlength="100" required value="${esc(a.name)}"></div>
      <div class="grid2">
        <div class="field"><label for="ci">Industry</label><select id="ci" name="industry">${options(S.meta.industries.map(x => ({ id: x, label: x })), a.industry)}</select></div>
        <div class="field"><label for="csz">Company size</label><select id="csz" name="size">${options(S.meta.sizes.map(x => ({ id: x, label: x + ' people' })), a.size, 'Not known')}</select></div>
        <div class="field"><label for="cc">City</label><input id="cc" name="city" maxlength="60" value="${esc(a.city)}"></div>
        <div class="field"><label for="cp">Phone</label><input id="cp" name="phone" maxlength="20" value="${esc(a.phone)}"></div>
        <div class="field"><label for="cw">Website</label><input id="cw" name="website" maxlength="120" value="${esc(a.website)}" placeholder="example.com"></div>
        <div class="field"><label for="co">Account owner</label><select id="co" name="owner">${options(sellerList().map(u => ({ id: u.username, label: u.displayName })), a.owner)}</select></div>
      </div>
      <div class="field"><label for="cnotes">Notes</label><textarea id="cnotes" name="notes" maxlength="2000">${esc(a.notes)}</textarea></div>`,
    submitText: isNew ? 'Add company' : 'Save',
    onSubmit: async f => {
      const saved = isNew ? await api('POST', '/api/accounts', f) : await api('PUT', '/api/accounts/' + a.id, f);
      toast(isNew ? 'Company added' : 'Company saved');
      if (after) setTimeout(() => after(saved), 0); else refresh();
    }
  });
}

function contactForm(accountId, c) {
  const isNew = !c;
  const x = c || {};
  modal.open({
    title: isNew ? 'New contact' : 'Edit contact',
    body: `
      <input type="hidden" name="accountId" value="${esc(accountId)}">
      <div class="grid2">
        <div class="field"><label for="pn1">Name</label><input id="pn1" name="name" maxlength="80" required value="${esc(x.name)}"></div>
        <div class="field"><label for="pt">Job title</label><input id="pt" name="title" maxlength="80" value="${esc(x.title)}"></div>
        <div class="field"><label for="pe">Email</label><input id="pe" name="email" type="email" maxlength="120" value="${esc(x.email)}"></div>
        <div class="field"><label for="pph">Phone</label><input id="pph" name="phone" maxlength="20" value="${esc(x.phone)}"></div>
      </div>
      <label class="check"><input type="checkbox" name="decisionMaker" ${x.decisionMaker ? 'checked' : ''}> This person can approve the purchase (decision maker)</label>`,
    submitText: isNew ? 'Add contact' : 'Save',
    onSubmit: async f => {
      if (isNew) await api('POST', '/api/contacts', f); else await api('PUT', '/api/contacts/' + x.id, f);
      toast(isNew ? 'Contact added' : 'Contact saved'); refresh();
    }
  });
}

async function pageAccount(id) {
  const [a, deals] = await Promise.all([api('GET', '/api/accounts/' + encodeURIComponent(id)), api('GET', '/api/deals?accountId=' + encodeURIComponent(id) + '&sort=created')]);
  const canDelete = S.user.role !== 'SALES_REP';
  view().innerHTML = `
    <div class="page-head">
      <div><div class="small muted"><a href="#/accounts">Accounts</a> / ${esc(a.id)}</div><h1>${esc(a.name)}</h1>
        <p>${esc(a.industry)}${a.city ? ' · ' + esc(a.city) : ''}${a.size ? ' · ' + esc(a.size) + ' people' : ''} · looked after by ${esc(a.ownerName || '-')}</p></div>
      <div class="spacer"></div>
      <button id="accEdit">Edit</button>${canDelete ? '<button class="danger" id="accDel">Delete</button>' : ''}
    </div>
    <div class="kpis">
      <div class="card kpi"><div class="k-label">Open deals</div><div class="k-value">${a.openDeals}</div><div class="k-sub">${money(a.openValue)}</div></div>
      <div class="card kpi"><div class="k-label">Won so far</div><div class="k-value good-text">${moneyShort(a.wonValue)}</div><div class="k-sub">Lifetime value</div></div>
      <div class="card kpi"><div class="k-label">Phone</div><div class="k-value small">${esc(a.phone || '-')}</div><div class="k-sub">${a.website ? esc(a.website) : 'No website'}</div></div>
    </div>
    ${a.notes ? `<section class="card card-pad"><p>${esc(a.notes)}</p></section><br>` : ''}
    <div class="split">
      <section class="card">
        <div class="card-head"><h2>Deals</h2><div class="spacer"></div><button class="small primary" id="accDeal">+ New deal</button></div>
        <div class="table-wrap"><table><thead><tr><th>Deal</th><th>Stage</th><th class="num">Value</th><th>Close</th><th>Owner</th></tr></thead><tbody>
        ${deals.map(d => `<tr><td><a href="#/deals/${esc(d.id)}">${esc(d.title)}</a></td><td>${stageBadge(d.stage)}</td>
          <td class="num">${money(d.value)}</td><td>${esc(dateOnly(d.closedAt || d.expectedClose))}</td><td>${esc(d.ownerName)}</td></tr>`).join('')
          || '<tr><td colspan="5"><div class="empty">No deals with this company yet.</div></td></tr>'}
        </tbody></table></div>
      </section>
      <section class="card">
        <div class="card-head"><h2>Contacts</h2><div class="spacer"></div><button class="small" id="addContact">+ Contact</button></div>
        ${a.contacts.length ? '<ul class="list">' + a.contacts.map(c => `<li>
          <span class="avatar">${initials(c.name)}</span>
          <div class="grow"><span class="title">${esc(c.name)} ${c.decisionMaker ? '<span class="badge">Decision maker</span>' : ''}</span>
            <div class="meta">${esc(c.title)}${c.email ? ` · <a href="mailto:${esc(c.email)}">${esc(c.email)}</a>` : ''}${c.phone ? ' · ' + esc(c.phone) : ''}</div></div>
          <button class="ghost small" data-edit-contact="${esc(c.id)}">Edit</button>
          <button class="ghost small danger" data-del-contact="${esc(c.id)}">Remove</button></li>`).join('') + '</ul>'
          : '<div class="empty">No contacts yet.</div>'}
      </section>
    </div>`;
  $('#accEdit').addEventListener('click', () => accountForm(a));
  if (canDelete) $('#accDel').addEventListener('click', () => confirmBox('Delete company?', 'This removes ' + a.name + ' and its contacts. Companies with deals cannot be deleted.', 'Delete', async () => {
    await api('DELETE', '/api/accounts/' + a.id); toast('Company deleted'); location.hash = '#/accounts';
  }));
  $('#accDeal').addEventListener('click', () => dealForm(null, a.id));
  $('#addContact').addEventListener('click', () => contactForm(a.id, null));
  $$('[data-edit-contact]').forEach(b => b.addEventListener('click', () => contactForm(a.id, a.contacts.find(c => c.id === b.dataset.editContact))));
  $$('[data-del-contact]').forEach(b => b.addEventListener('click', () => {
    const c = a.contacts.find(x => x.id === b.dataset.delContact);
    confirmBox('Remove contact?', c.name + ' will be removed. Deals linked to them are kept.', 'Remove', async () => {
      await api('DELETE', '/api/contacts/' + c.id); toast('Contact removed'); refresh();
    });
  }));
}

// ------------------------------------------------------------------ reports
async function pageReports() {
  const owner = S.reportOwner;
  const r = await api('GET', '/api/reports?months=6' + (owner ? '&owner=' + encodeURIComponent(owner) : ''));
  const fmax = Math.max(1, ...r.forecast.map(m => Math.max(m.pipeline + m.won, m.won + m.weighted)));
  const mmax = Math.max(1, ...r.monthly.map(m => m.wonValue));
  const funnelTop = Math.max(1, r.funnel[0].count);
  const lmax = Math.max(1, ...r.lostReasons.map(x => x.count));
  const v = r.velocity;
  const stageColor = { LEAD: 's-LEAD', QUALIFIED: 's-QUALIFIED', PROPOSAL: 's-PROPOSAL', NEGOTIATION: 's-NEGOTIATION', WON: 's-WON' };
  view().innerHTML = `
    <div class="page-head">
      <div><h1>Forecast &amp; reports</h1><p>Where the money will come from, and how the team is doing.</p></div>
      <div class="spacer"></div>
      <select id="repOwner" aria-label="Report for">${options(sellerList().map(u => ({ id: u.username, label: u.displayName })), owner, 'Whole team')}</select>
    </div>
    <div class="kpis">
      <div class="card kpi"><div class="k-label">Sales velocity</div><div class="k-value">${v.perMonth == null ? '-' : moneyShort(v.perMonth)}</div>
        <div class="k-sub">Expected per month from today's pipeline</div></div>
      <div class="card kpi"><div class="k-label">Win rate (all time)</div><div class="k-value">${pct(r.allTime.winRate)}</div>
        <div class="k-sub">${r.allTime.won} won · ${r.allTime.lost} lost</div></div>
      <div class="card kpi"><div class="k-label">Days to win</div><div class="k-value">${r.allTime.avgCycleDays == null ? '-' : Math.round(r.allTime.avgCycleDays)}</div>
        <div class="k-sub">Average, from created to won</div></div>
      <div class="card kpi"><div class="k-label">Average won deal</div><div class="k-value">${moneyShort(r.allTime.avgWonValue)}</div><div class="k-sub">All time</div></div>
    </div>
    <div class="cards cols-2">
      <section class="card">
        <div class="card-head"><h2>Revenue forecast, next 6 months</h2></div>
        <div class="card-body">
          <div class="vchart">${r.forecast.map(m => `<div class="col" title="${esc(m.label)}: won ${money(m.won)}, weighted ${money(m.weighted)}, pipeline ${money(m.pipeline)}">
            <span class="val">${moneyShort(m.expected)}</span>
            <div class="stack-bars">
              <span class="seg-pipe" data-h="${(m.pipeline + m.won) / fmax * 100}"></span>
              <span class="seg-won" data-h="${m.won / fmax * 100}"></span>
              <span class="seg-weighted" data-h="${m.weighted / fmax * 100}"></span>
            </div></div>`).join('')}</div>
          <div class="vlabels">${r.forecast.map(m => `<span>${esc(m.label)}</span>`).join('')}</div>
          <div class="legend"><span><i class="l-won"></i>Already won</span><span><i class="l-weighted"></i>Weighted (value × chance)</span><span><i class="l-pipe"></i>Full pipeline</span></div>
          <p class="small muted">Open deals whose close date has passed are counted in this month. The number on top is won + weighted.</p>
        </div>
      </section>
      <section class="card">
        <div class="card-head"><h2>Conversion funnel</h2><span class="muted small">All deals ever</span></div>
        <div class="card-body funnel">
          ${r.funnel.map(f => `<div class="f-row"><span>${esc(f.label)}</span>
            <div class="f-bar"><span class="${stageColor[f.stage]}" data-w="${f.count / funnelTop * 100}">${f.count}</span></div>
            <span class="small ${f.conversion != null && f.conversion < 50 ? 'warn-text' : 'muted'}">${f.conversion == null ? '' : pct(f.conversion) + ' moved on'}</span></div>`).join('')}
        </div>
      </section>
      <section class="card">
        <div class="card-head"><h2>Won per month</h2></div>
        <div class="card-body">
          <div class="vchart">${r.monthly.map(m => `<div class="col" title="${esc(m.label)}: ${m.wonCount} won, ${m.lostCount} lost, ${m.newCount} new">
            <span class="val">${moneyShort(m.wonValue)}</span>
            <div class="stack-bars"><span class="seg-single" data-h="${m.wonValue / mmax * 100}"></span></div></div>`).join('')}</div>
          <div class="vlabels">${r.monthly.map(m => `<span>${esc(m.label)}<br><span class="tiny">${m.wonCount}W / ${m.lostCount}L</span></span>`).join('')}</div>
        </div>
      </section>
      <section class="card">
        <div class="card-head"><h2>Average days in each stage</h2></div>
        <div class="card-body stage-bars">
          ${(() => { const mx = Math.max(1, ...r.stageTime.map(s => s.avgDays || 0)); return r.stageTime.map(s => `<div class="stage-bar">
            <div class="top"><span>${esc(s.label)}</span><span class="num">${s.avgDays == null ? '-' : s.avgDays + ' days'}</span></div>
            <div class="bar tall"><span class="s-${esc(s.stage)}" data-w="${(s.avgDays || 0) / mx * 100}"></span></div></div>`).join(''); })()}
          <p class="small muted">The longest bar is where deals slow down the most.</p>
        </div>
      </section>
    </div>
    <br>
    <section class="card">
      <div class="card-head"><h2>Team leaderboard, this month</h2></div>
      <div class="table-wrap"><table>
        <thead><tr><th>#</th><th>Sales person</th><th class="num">Won this month</th><th>Target</th><th class="num">Open pipeline</th><th class="num">Weighted</th><th class="num">Win rate</th><th class="num">Activities (30 days)</th></tr></thead>
        <tbody>${r.leaderboard.map((u, i) => `<tr>
          <td>${i + 1}</td><td><span class="row"><span class="avatar">${initials(u.displayName)}</span>${esc(u.displayName)}</span></td>
          <td class="num"><b>${money(u.wonThisMonth)}</b> <span class="muted small">(${u.wonThisMonthCount})</span></td>
          <td>${u.target ? `<div class="small">${pct(u.attainment)} of ${moneyShort(u.target)}</div><div class="bar gold"><span data-w="${u.attainment}"></span></div>` : '<span class="muted">No target</span>'}</td>
          <td class="num">${money(u.openValue)} <span class="muted small">(${u.openCount})</span></td><td class="num">${money(u.weightedValue)}</td>
          <td class="num">${pct(u.winRate)}</td><td class="num">${u.activities30d}</td></tr>`).join('')}</tbody>
      </table></div>
    </section>
    <br>
    <div class="cards cols-2">
      <section class="card">
        <div class="card-head"><h2>Where deals come from</h2></div>
        <div class="table-wrap"><table><thead><tr><th>Source</th><th class="num">Deals</th><th class="num">Open</th><th class="num">Won</th><th class="num">Win rate</th><th class="num">Won value</th></tr></thead>
        <tbody>${r.bySource.map(s => `<tr><td>${esc(s.label)}</td><td class="num">${s.count}</td><td class="num">${s.open}</td><td class="num">${s.won}</td>
          <td class="num">${pct(s.winRate)}</td><td class="num">${money(s.wonValue)}</td></tr>`).join('') || '<tr><td colspan="6"><div class="empty">No deals yet.</div></td></tr>'}</tbody></table></div>
      </section>
      <section class="card">
        <div class="card-head"><h2>Why deals are lost</h2></div>
        <div class="card-body stage-bars">
          ${r.lostReasons.map(x => `<div class="stage-bar"><div class="top"><span>${esc(x.reason)}</span><span class="num">${x.count} · ${moneyShort(x.value)}</span></div>
            <div class="bar bad tall"><span data-w="${x.count / lmax * 100}"></span></div></div>`).join('') || '<div class="empty">No lost deals. Great!</div>'}
        </div>
      </section>
    </div>`;
  $('#repOwner').addEventListener('change', e => { S.reportOwner = e.target.value; route(); });
  paintBars(view());
}

// ------------------------------------------------------------------ team (admin)
async function pageTeam() {
  if (S.user.role !== 'ADMIN') { view().innerHTML = '<div class="empty">Only an admin can manage the team.</div>'; return; }
  const users = await api('GET', '/api/users?all=1');
  view().innerHTML = `
    <div class="page-head"><div><h1>Team</h1><p>Who can sign in, their role and their monthly sales target.</p></div>
      <div class="spacer"></div><button class="primary" id="addUser">+ Add person</button></div>
    <section class="card"><div class="table-wrap"><table>
      <thead><tr><th>Name</th><th>Username</th><th>Role</th><th class="num">Monthly target</th><th>Status</th><th></th></tr></thead>
      <tbody>${users.map(u => `<tr>
        <td><span class="row"><span class="avatar">${initials(u.displayName)}</span><span>${esc(u.displayName)}<div class="small muted">${esc(u.email)}</div></span></span></td>
        <td>${esc(u.username)}</td><td>${label(u.role)}</td><td class="num">${u.monthlyTarget ? money(u.monthlyTarget) : '-'}</td>
        <td>${u.active ? '<span class="badge">Active</span>' : '<span class="badge bad">Turned off</span>'}</td>
        <td class="right"><button class="small" data-edit-user="${esc(u.username)}">Edit</button></td></tr>`).join('')}</tbody>
    </table></div></section>
    <p class="small muted">Roles: <b>Admin</b> manages the team. <b>Manager</b> can edit every deal and assign deals. <b>Sales rep</b> edits their own deals and can see the team's pipeline.</p>`;
  $('#addUser').addEventListener('click', () => userForm(null));
  $$('[data-edit-user]').forEach(b => b.addEventListener('click', () => userForm(users.find(u => u.username === b.dataset.editUser))));
}

function userForm(u) {
  const isNew = !u;
  const x = u || { role: 'SALES_REP', monthlyTarget: 1000000, active: true };
  modal.open({
    title: isNew ? 'Add a person' : 'Edit ' + x.displayName,
    body: `
      <div class="grid2">
        <div class="field"><label for="ud">Full name</label><input id="ud" name="displayName" maxlength="60" required value="${esc(x.displayName)}"></div>
        <div class="field"><label for="uu">Username</label><input id="uu" name="username" maxlength="24" ${isNew ? 'required pattern="[a-z][a-z0-9._-]{2,23}"' : 'disabled'} value="${esc(x.username)}" autocomplete="off">
          ${isNew ? '<div class="hint">Small letters and numbers, e.g. amit.s</div>' : ''}</div>
        <div class="field"><label for="ue">Email</label><input id="ue" name="email" type="email" maxlength="120" value="${esc(x.email)}"></div>
        <div class="field"><label for="ur">Role</label><select id="ur" name="role">${options(S.meta.roles, x.role)}</select></div>
        <div class="field"><label for="ut">Monthly target (₹)</label><input id="ut" name="monthlyTarget" type="number" min="0" step="10000" value="${esc(x.monthlyTarget)}"></div>
        <div class="field"><label for="upw">${isNew ? 'Password' : 'New password'}</label><input id="upw" name="password" type="password" minlength="8" autocomplete="new-password" ${isNew ? 'required' : 'placeholder="Leave empty to keep"'}></div>
      </div>
      ${isNew ? '' : `<label class="check"><input type="checkbox" name="active" ${x.active ? 'checked' : ''}> Can sign in (untick to turn this account off)</label>`}`,
    submitText: isNew ? 'Add person' : 'Save',
    onSubmit: async f => {
      const body = { ...f, monthlyTarget: Number(f.monthlyTarget || 0) };
      if (isNew) await api('POST', '/api/users', body);
      else { if (!body.password) delete body.password; await api('PUT', '/api/users/' + x.username, body); }
      S.users = await api('GET', '/api/users');
      toast(isNew ? 'Person added' : 'Saved'); refresh();
    }
  });
}

// ------------------------------------------------------------------ start
boot();
