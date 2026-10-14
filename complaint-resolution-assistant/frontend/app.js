/* Agent console for the AI Complaint Resolution Assistant.
   Plain JavaScript, no frameworks. Every piece of user data is escaped with esc() before
   it goes into HTML. */
'use strict';

const S = { token: null, user: null, meta: null, users: [], ai: null, detail: null, openRec: null };
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
const catLabel = c => esc((S.meta && S.meta.categoryLabels[c]) || label(c));
const userName = u => { const x = S.users.find(a => a.username === u); return esc(x ? x.displayName : (u || 'Unassigned')); };

function when(iso) {
  if (!iso) return '';
  const d = new Date(iso);
  return d.toLocaleString('en-IN', { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' });
}
function ago(iso) {
  if (!iso) return '';
  const s = (Date.now() - new Date(iso).getTime()) / 1000;
  const fut = s < 0; const a = Math.abs(s);
  const t = a < 60 ? 'just now' : a < 3600 ? Math.round(a / 60) + ' min' : a < 86400 ? Math.round(a / 3600) + ' h' : Math.round(a / 86400) + ' d';
  if (t === 'just now') return t;
  return fut ? 'in ' + t : t + ' ago';
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

function options(list, selected, labeler = label, blank) {
  let h = blank ? `<option value="">${esc(blank)}</option>` : '';
  for (const v of list) h += `<option value="${esc(v)}" ${v === selected ? 'selected' : ''}>${labeler(v)}</option>`;
  return h;
}

// ------------------------------------------------------------------ theme
function applyTheme(t) {
  if (t) document.documentElement.setAttribute('data-theme', t);
  const dark = t ? t === 'dark' : matchMedia('(prefers-color-scheme: dark)').matches;
  const b = $('#themeBtn'); if (b) b.textContent = dark ? 'Light mode' : 'Dark mode';
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
  const btn = e.submitter;
  btn.disabled = true;
  try {
    const r = await api('POST', '/api/auth/login', { username: $('#lu').value, password: $('#lp').value });
    S.token = r.token; S.user = r.user; store.set('token', r.token);
    await boot();
  } catch (err) { $('#loginErr').textContent = err.message; } finally { btn.disabled = false; }
});

$('#logoutBtn').addEventListener('click', async () => {
  try { await api('POST', '/api/auth/logout', {}); } catch { /* ignore */ }
  signOut();
});

function signOut(expired) {
  S.token = null; S.user = null; store.set('token', null);
  $('#app').classList.add('hidden'); $('#login').classList.remove('hidden');
  if (expired) $('#loginErr').textContent = 'Your session ended. Please sign in again.';
}

async function boot() {
  S.token = S.token || store.get('token');
  if (!S.token) { signOut(); return; }
  try {
    S.user = await api('GET', '/api/auth/me');
    [S.meta, S.users, S.ai] = await Promise.all([api('GET', '/api/meta'), api('GET', '/api/users'), api('GET', '/api/ai/status')]);
  } catch { signOut(); return; }
  $('#login').classList.add('hidden'); $('#app').classList.remove('hidden');
  $('#who').textContent = S.user.displayName + (S.user.role === 'ADMIN' ? ' (admin)' : '');
  $('#navTeam').classList.toggle('hidden', S.user.role !== 'ADMIN');
  $('#aiMode').innerHTML = S.ai.llmEnabled
    ? `AI: offline NLP + LLM replies<br><span class="small">${esc(S.ai.llmModel)}</span>`
    : `AI: offline NLP engine<br><span class="small">${S.ai.trainingExamples} training examples</span>`;
  if (!location.hash) location.hash = '#/dashboard';
  route();
}

// ------------------------------------------------------------------ router
window.addEventListener('hashchange', route);
$('#menuBtn').addEventListener('click', () => $('#side').classList.toggle('open'));
$('#globalSearch').addEventListener('keydown', e => {
  if (e.key === 'Enter') location.hash = '#/complaints?q=' + encodeURIComponent(e.target.value.trim());
});

async function route() {
  if (!S.token || !S.user) return;
  const [path, qs] = location.hash.slice(1).split('?');
  const params = new URLSearchParams(qs || '');
  const parts = path.split('/').filter(Boolean);
  const page = parts[0] || 'dashboard';
  $$('#nav a').forEach(a => a.classList.toggle('active', a.dataset.nav === page));
  $('#side').classList.remove('open');
  view().innerHTML = '<p class="muted">Loading...</p>';
  try {
    if (page === 'dashboard') await renderDashboard();
    else if (page === 'complaints' && parts[1]) await renderDetail(parts[1]);
    else if (page === 'complaints') await renderList(params);
    else if (page === 'new') renderNew();
    else if (page === 'kb') await renderKb(params);
    else if (page === 'analytics') await renderAnalytics();
    else if (page === 'team') await renderTeam();
    else view().innerHTML = '<div class="empty">Page not found. <a href="#/dashboard">Go to the dashboard</a>.</div>';
  } catch (e) {
    view().innerHTML = `<div class="banner warn">${esc(e.message)}</div>`;
  }
  view().focus({ preventScroll: true });
  window.scrollTo(0, 0);
}

// ------------------------------------------------------------------ shared pieces
function ticketRow(c) {
  const late = c.overdue ? '<span class="pill late">Overdue</span>' : '';
  const dup = c.duplicateOf ? `<span>Possible repeat of ${esc(c.duplicateOf)}</span>` : '';
  return `<a class="ticket p-${esc(c.priority)}" href="#/complaints/${esc(c.id)}">
    <span class="edge" aria-hidden="true"></span>
    <span class="body">
      <span class="title">${esc(c.subject)}</span>
      <span class="meta"><span>${esc(c.id)}</span><span>${esc(c.customerName)}</span><span>${catLabel(c.category)}</span>
        <span>${userName(c.assignedTo)}</span>${dup}</span>
    </span>
    <span class="side-info">
      <span class="row" style="gap:.35rem"><span class="pill pri p-${esc(c.priority)}">${label(c.priority)}</span>
        <span class="pill st-${esc(c.status)}">${label(c.status)}</span>${late}</span>
      <span class="muted">${c.status === 'RESOLVED' || c.status === 'CLOSED' ? 'Filed ' + ago(c.createdAt) : 'Due ' + ago(c.dueAt)}</span>
    </span>
  </a>`;
}

function barList(obj, colorFn, suffix = '') {
  const entries = Object.entries(obj);
  const max = Math.max(1, ...entries.map(e => e[1]));
  if (!entries.length) return '<p class="muted">No data yet.</p>';
  return `<div class="bars">${entries.map(([k, v]) => `
    <div class="bar-row"><span>${esc(k)}</span>
      <span class="bar-track"><i style="width:${(v / max * 100).toFixed(1)}%;${colorFn ? '--c:' + colorFn(k) : ''}"></i></span>
      <span class="num" style="text-align:right">${v}${suffix}</span></div>`).join('')}</div>`;
}

function trendChart(trend) {
  const days = Object.keys(trend); const vals = Object.values(trend);
  const W = 560, H = 170, pad = 26;
  const max = Math.max(2, ...vals);
  const x = i => pad + i * (W - pad * 2) / Math.max(1, days.length - 1);
  const y = v => H - pad - v / max * (H - pad * 2);
  const pts = vals.map((v, i) => `${x(i).toFixed(1)},${y(v).toFixed(1)}`).join(' ');
  const area = `${x(0)},${H - pad} ${pts} ${x(vals.length - 1)},${H - pad}`;
  let labels = '';
  days.forEach((d, i) => {
    if (i % 3 === 0 || i === days.length - 1) {
      const dt = new Date(d + 'T00:00:00');
      labels += `<text x="${x(i)}" y="${H - 6}" text-anchor="middle">${dt.getDate()} ${dt.toLocaleString('en', { month: 'short' })}</text>`;
    }
  });
  const dots = vals.map((v, i) => `<circle cx="${x(i)}" cy="${y(v)}" r="3" fill="var(--accent)"><title>${esc(days[i])}: ${v}</title></circle>`).join('');
  return `<div class="chart"><svg viewBox="0 0 ${W} ${H}" role="img" aria-label="Complaints per day, last 14 days">
    <line x1="${pad}" x2="${W - pad}" y1="${H - pad}" y2="${H - pad}" stroke="var(--line)"/>
    <line x1="${pad}" x2="${W - pad}" y1="${y(max)}" y2="${y(max)}" stroke="var(--line)" stroke-dasharray="3 4"/>
    <text x="${pad - 6}" y="${y(max) + 4}" text-anchor="end">${max}</text>
    <polygon points="${area}" fill="var(--accent-soft)"/>
    <polyline points="${pts}" fill="none" stroke="var(--accent)" stroke-width="2.2" stroke-linejoin="round"/>
    ${dots}${labels}</svg></div>`;
}

const PRI_COLORS = { CRITICAL: 'var(--critical)', HIGH: 'var(--high)', MEDIUM: 'var(--medium)', LOW: 'var(--low)' };
const SENT_COLORS = { 'Very negative': 'var(--critical)', Negative: 'var(--high)', Neutral: 'var(--muted)', Positive: 'var(--good)' };

function relabel(obj) {
  const out = {};
  for (const [k, v] of Object.entries(obj)) out[String(k).replace(/_/g, ' ').toLowerCase().replace(/^\w/, c => c.toUpperCase())] = v;
  return out;
}

function kpiStrip(k) {
  const pct = v => v == null ? '–' : v + '%';
  return `<div class="panel kpis">
    <div class="kpi"><div class="v num">${k.active}</div><div class="l">Open complaints</div></div>
    <div class="kpi ${k.overdue ? 'alert' : ''}"><div class="v num">${k.overdue}</div><div class="l">Past their deadline</div></div>
    <div class="kpi ${k.criticalOpen ? 'alert' : ''}"><div class="v num">${k.criticalOpen}</div><div class="l">Critical and open</div></div>
    <div class="kpi"><div class="v num">${k.avgResolutionHours}h</div><div class="l">Average time to resolve</div></div>
    <div class="kpi"><div class="v num">${pct(k.slaCompliance)}</div><div class="l">Resolved on time</div></div>
    <div class="kpi"><div class="v num">${k.avgSatisfaction == null ? '–' : k.avgSatisfaction + '/5'}</div><div class="l">Customer rating (${k.ratingsCount})</div></div>
  </div>`;
}

function updateOverdueBadge(n) {
  const b = $('#navOverdue');
  b.textContent = n; b.classList.toggle('hidden', !n);
  b.title = n + ' overdue';
}

// ------------------------------------------------------------------ dashboard
async function renderDashboard() {
  const a = await api('GET', '/api/analytics');
  updateOverdueBadge(a.kpi.overdue);
  const q = a.aiQuality;
  view().innerHTML = `
    <div class="page-head"><div><h1>Good to see you, ${esc(S.user.displayName.split(' ')[0])}</h1>
      <p>${a.kpi.active ? `${a.kpi.active} complaints need attention. Start with the ones at the top.` : 'All caught up. No open complaints.'}</p></div>
      <a class="btn primary" href="#/new">Log a complaint</a></div>
    ${kpiStrip(a.kpi)}
    <div class="dash">
      <section class="panel">
        <div class="panel-head"><h2>Handle these first</h2><a href="#/complaints?status=active" class="small">See all open</a></div>
        ${a.urgent.length ? `<div class="tickets">${a.urgent.map(ticketRow).join('')}</div>` : '<div class="empty">Nothing waiting. New complaints will appear here.</div>'}
      </section>
      <div class="stack">
        <section class="panel"><div class="panel-head"><h2>Complaints per day</h2><span class="small muted">Last 14 days</span></div>
          <div class="panel-pad">${trendChart(a.trend)}</div></section>
        <section class="panel"><div class="panel-head"><h2>What customers complain about</h2></div>
          <div class="panel-pad">${barList(a.byCategory)}</div></section>
        <section class="panel panel-pad">
          <h2 style="margin-bottom:.4rem">How good are the AI suggestions?</h2>
          <p class="muted small">${q.appliedCount ? `Agents chose the AI's first suggestion in <b>${q.top1Accuracy}%</b> of ${q.appliedCount} cases, and one of its top three in <b>${q.top3Accuracy}%</b>.` : 'Apply a suggested solution to a complaint to start measuring this.'}
          ${q.helpfulRate != null ? ` Agents rated suggestions helpful ${q.helpfulRate}% of the time.` : ''}</p>
        </section>
      </div>
    </div>`;
}

// ------------------------------------------------------------------ complaint list
async function renderList(params) {
  const f = {
    q: params.get('q') || '', status: params.get('status') || '', priority: params.get('priority') || '',
    category: params.get('category') || '', assignedTo: params.get('assignedTo') || '', overdue: params.get('overdue') || ''
  };
  const qs = new URLSearchParams();
  for (const [k, v] of Object.entries(f)) if (v && !(k === 'status' && v === 'active')) qs.set(k, v);
  let list = await api('GET', '/api/complaints?' + qs);
  if (f.status === 'active') list = list.filter(c => ['OPEN', 'IN_PROGRESS', 'ESCALATED'].includes(c.status));
  if (f.q) $('#globalSearch').value = f.q;

  view().innerHTML = `
    <div class="page-head"><div><h1>Complaints</h1><p>${list.length} shown</p></div>
      <div class="row"><button id="exportBtn">Export CSV</button><a class="btn primary" href="#/new">Log a complaint</a></div></div>
    <section class="panel">
      <form class="filters" id="filters">
        <div><label for="fq">Search</label><input id="fq" name="q" type="search" value="${esc(f.q)}" placeholder="Name, email, order, words"></div>
        <div><label for="fs">Status</label><select id="fs" name="status">
          <option value="">Any</option><option value="active" ${f.status === 'active' ? 'selected' : ''}>Open (any stage)</option>
          ${options(S.meta.statuses, f.status)}</select></div>
        <div><label for="fp">Priority</label><select id="fp" name="priority">${options(S.meta.priorities, f.priority, label, 'Any')}</select></div>
        <div><label for="fc">Category</label><select id="fc" name="category">${options(S.meta.categories, f.category, catLabel, 'Any')}</select></div>
        <div><label for="fa">Agent</label><select id="fa" name="assignedTo"><option value="">Anyone</option>
          <option value="unassigned" ${f.assignedTo === 'unassigned' ? 'selected' : ''}>Unassigned</option>
          ${S.users.map(u => `<option value="${esc(u.username)}" ${u.username === f.assignedTo ? 'selected' : ''}>${esc(u.displayName)}</option>`).join('')}</select></div>
        <div class="row" style="gap:.4rem"><label style="display:flex;gap:.35rem;align-items:center;margin:0">
          <input type="checkbox" name="overdue" value="true" style="width:auto" ${f.overdue ? 'checked' : ''}> Overdue</label>
          <button type="button" class="ghost" id="clearF">Clear</button></div>
      </form>
      ${list.length ? `<div class="tickets">${list.map(ticketRow).join('')}</div>` : '<div class="empty">No complaints match these filters. Try clearing them.</div>'}
    </section>`;

  const form = $('#filters');
  const apply = () => {
    const p = new URLSearchParams();
    for (const el of form.elements) {
      if (!el.name) continue;
      if (el.type === 'checkbox') { if (el.checked) p.set(el.name, 'true'); } else if (el.value) p.set(el.name, el.value);
    }
    location.hash = '#/complaints?' + p;
  };
  form.addEventListener('change', apply);
  form.addEventListener('submit', e => { e.preventDefault(); apply(); });
  $('#clearF').addEventListener('click', () => { $('#globalSearch').value = ''; location.hash = '#/complaints'; });
  $('#exportBtn').addEventListener('click', e => run(e.target, downloadCsv));
}

async function downloadCsv() {
  const res = await fetch('/api/export/complaints.csv', { headers: { Authorization: 'Bearer ' + S.token } });
  if (!res.ok) throw new Error('Export failed');
  const blob = await res.blob();
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = 'complaints-' + new Date().toISOString().slice(0, 10) + '.csv';
  document.body.appendChild(a); a.click(); a.remove();
  setTimeout(() => URL.revokeObjectURL(a.href), 1000);
  toast('CSV downloaded');
}

// ------------------------------------------------------------------ complaint detail
function highlight(text, words) {
  let html = esc(text);
  const clean = (words || []).filter(w => w && w.length > 2).map(w => w.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'));
  if (!clean.length) return html;
  const re = new RegExp('\\b(' + clean.join('|') + ')(\\w*)', 'gi');
  return html.replace(re, '<mark>$1$2</mark>');
}

function sentimentMeter(score, label_) {
  const pos = ((score + 1) / 2 * 100).toFixed(1);
  return `<div class="meter" role="img" aria-label="Sentiment ${esc(label_)}, score ${score}"><span class="needle" style="left:${pos}%"></span></div>
    <div class="meter-scale"><span>Very upset</span><span>Neutral</span><span>Happy</span></div>`;
}

async function renderDetail(id) {
  const c = await api('GET', '/api/complaints/' + encodeURIComponent(id));
  S.detail = c;
  drawDetail();
}

function drawDetail() {
  const c = S.detail;
  const isAdmin = S.user.role === 'ADMIN';
  const sols = c.solutionDetails || {};
  const reply = c.responseSent ? c.finalResponse : c.draftResponse;
  const banners = [];
  if (c.status === 'ESCALATED' && c.escalationReason) banners.push(`<div class="banner warn">Escalated: ${esc(c.escalationReason)}</div>`);
  if (c.overdue) banners.push(`<div class="banner warn">This complaint missed its deadline (${esc(when(c.dueAt))}).</div>`);
  if (c.duplicateOf) banners.push(`<div class="banner info">This looks like a repeat of <a href="#/complaints/${esc(c.duplicateOf)}">${esc(c.duplicateOf)}</a> from the same customer.</div>`);
  if (c.responseSent) banners.push(`<div class="banner info">Reply sent to the customer.</div>`);

  const recs = c.recommendations.map((r, i) => {
    const s = sols[r.solutionId];
    const open = S.openRec === r.solutionId || (S.openRec == null && i === 0);
    const applied = c.appliedSolutionId === r.solutionId;
    return `<div class="rec ${applied ? 'applied' : ''}">
      <div class="rec-top" data-act="toggle" data-id="${esc(r.solutionId)}" role="button" tabindex="0" aria-expanded="${open}">
        <div class="score" style="--s:${r.confidence}" title="Match score"><span>${r.confidence}%</span></div>
        <div><div class="rec-title">${esc(r.title)}${applied ? ' <span class="pill" style="color:var(--accent);border-color:var(--accent)">Applied</span>' : ''}</div>
          <div class="rec-why">${r.reasons.map(esc).join('. ')}</div></div>
      </div>
      ${open && s ? `<div class="rec-body">
        <ol>${s.steps.map(x => `<li>${esc(x)}</li>`).join('')}</ol>
        <div class="rec-actions">
          <button class="primary" data-act="apply" data-id="${esc(r.solutionId)}">${applied ? 'Re-draft reply' : 'Use this solution'}</button>
          <span class="muted small" style="margin-left:.4rem">Was this suggestion right?</span>
          <button class="vote ${r.helpful === true ? 'on-yes' : ''}" data-act="vote" data-id="${esc(r.solutionId)}" data-v="true" aria-pressed="${r.helpful === true}">Yes</button>
          <button class="vote ${r.helpful === false ? 'on-no' : ''}" data-act="vote" data-id="${esc(r.solutionId)}" data-v="false" aria-pressed="${r.helpful === false}">No</button>
        </div></div>` : ''}
    </div>`;
  }).join('');

  const appliedNotInRecs = c.appliedSolutionId && !c.recommendations.some(r => r.solutionId === c.appliedSolutionId) && sols[c.appliedSolutionId];

  view().innerHTML = `
    <div class="detail-head">
      <div><div class="crumbs"><a href="#/complaints">Complaints</a> / ${esc(c.id)}</div>
        <h1>${esc(c.subject)}</h1>
        <div class="row" style="margin-top:.5rem;gap:.4rem">
          <span class="pill pri p-${esc(c.priority)}">${label(c.priority)} priority</span>
          <span class="pill st-${esc(c.status)}">${label(c.status)}</span>
          <span class="muted small">Filed ${esc(when(c.createdAt))}, due ${esc(when(c.dueAt))} (${esc(ago(c.dueAt))})</span>
        </div></div>
      <div class="row"><button data-act="reanalyze">Re-run AI</button>${isAdmin ? '<button class="danger" data-act="delete">Delete</button>' : ''}</div>
    </div>
    ${banners.length ? `<div class="stack" style="margin-bottom:1rem">${banners.join('')}</div>` : ''}
    <div class="detail">
      <div class="stack">
        <section class="panel panel-pad">
          <h2 style="margin-bottom:.6rem">What the customer said</h2>
          <div class="complaint-text">${highlight(c.description, c.keywords)}</div>
          <dl class="facts">
            <div><dt>Customer</dt><dd>${esc(c.customerName)}</dd></div>
            <div><dt>Email</dt><dd>${esc(c.customerEmail)}</dd></div>
            <div><dt>Order</dt><dd>${esc(c.orderId || 'Not given')}</dd></div>
            <div><dt>Channel</dt><dd>${label(c.channel)}</dd></div>
            <div><dt>Customer tier</dt><dd>${label(c.customerTier)}</dd></div>
            <div><dt>Customer rating</dt><dd>${c.satisfactionRating ? c.satisfactionRating + ' / 5' : 'Not rated yet'}</dd></div>
          </dl>
        </section>

        <section class="panel reply">
          <div class="panel-head"><h2>${c.responseSent ? 'Reply sent' : 'Reply to the customer'}</h2>
            <span class="small muted">${c.appliedSolutionId ? 'Based on ' + esc(c.appliedSolutionId) : 'Drafted from the top suggestion'}</span></div>
          <div class="panel-pad">
            <label for="replyText" class="hidden">Reply</label>
            <textarea id="replyText">${esc(reply || '')}</textarea>
            <div class="row" style="margin-top:.7rem;justify-content:space-between">
              <div class="row">
                ${S.ai.llmEnabled ? `<button data-act="polish" ${c.appliedSolutionId ? '' : 'disabled title="Choose a solution first"'}>Polish with LLM</button>` : ''}
                <button data-act="copy">Copy</button>
              </div>
              <div class="row">
                <button data-act="send">Send reply</button>
                <button class="primary" data-act="sendResolve">Send and resolve</button>
              </div>
            </div>
          </div>
        </section>

        <section class="panel">
          <div class="panel-head"><h2>Notes</h2><span class="small muted">Only your team sees these</span></div>
          <div class="panel-pad stack">
            ${c.notes.length ? c.notes.map(n => `<div class="note">${esc(n.text)}<div class="by">${userName(n.author)}, ${esc(when(n.at))}</div></div>`).join('') : '<p class="muted small">No notes yet.</p>'}
            <form id="noteForm" class="row" style="align-items:flex-end">
              <div style="flex:1;min-width:200px"><label for="noteText">Add a note</label><input id="noteText" maxlength="2000" placeholder="Called the customer, waiting for photos"></div>
              <button type="submit">Add note</button></form>
          </div>
        </section>

        <section class="panel">
          <div class="panel-head"><h2>Activity</h2></div>
          <div class="panel-pad"><ul class="timeline">
            ${c.history.slice().reverse().map(h => `<li class="${h.by === 'AI assistant' ? 'ai' : ''}">${esc(h.action)}
              <div class="when">${esc(h.by === 'AI assistant' || h.by === 'system' || h.by === 'customer' ? h.by : S.users.find(u => u.username === h.by)?.displayName || h.by)}, ${esc(when(h.at))}</div></li>`).join('')}
          </ul></div>
        </section>
      </div>

      <div class="stack">
        <section class="panel">
          <div class="panel-head"><h2>AI reading</h2><span class="small muted">Highlighted words drove it</span></div>
          <div class="reading">
            <div><h3>Topic</h3>
              <div class="big">${catLabel(c.category)}</div>
              <div class="conf" title="Confidence"><i style="width:${Math.round(c.categoryConfidence * 100)}%"></i></div>
              <div class="small muted">${Math.round(c.categoryConfidence * 100)}% confident</div></div>
            <div><h3>Customer mood</h3>
              <div class="big">${label(c.sentiment)}</div>
              ${sentimentMeter(c.sentimentScore, c.sentiment)}
              ${c.emotions.length ? `<div class="chips" style="margin-top:.4rem">${c.emotions.map(e => `<span class="chip">${esc(e)}</span>`).join('')}</div>` : ''}</div>
            <div><h3>Why ${label(c.priority).toLowerCase()} priority</h3>
              <ul class="reasons">${c.priorityReasons.map(r => `<li>${esc(r)}</li>`).join('')}</ul></div>
            <div><h3>Key words</h3><div class="chips">${c.keywords.map(k => `<span class="chip">${esc(k)}</span>`).join('')}</div></div>
          </div>
        </section>

        <section class="panel">
          <div class="panel-head"><h2>Suggested solutions</h2><span class="small muted">Best match first</span></div>
          <div class="panel-pad recs">
            ${recs || '<p class="muted">No matching solutions in the knowledge base. <a href="#/kb">Add one</a> and re-run the AI.</p>'}
            ${appliedNotInRecs ? `<p class="small muted">Applied: ${esc(sols[c.appliedSolutionId].title)}</p>` : ''}
            <div class="row" style="margin-top:.3rem"><label for="otherSol" class="hidden">Use a different solution</label>
              <select id="otherSol" style="flex:1"><option value="">Use a different solution...</option></select>
              <button data-act="applyOther">Use</button></div>
          </div>
        </section>

        <section class="panel">
          <div class="panel-head"><h2>Manage</h2></div>
          <div class="panel-pad">
            <div class="grid-2">
              <div class="field"><label for="mStatus">Status</label><select id="mStatus">${options(S.meta.statuses, c.status)}</select></div>
              <div class="field"><label for="mAssign">Agent</label><select id="mAssign"><option value="">Unassigned</option>
                ${S.users.filter(u => u.active).map(u => `<option value="${esc(u.username)}" ${u.username === c.assignedTo ? 'selected' : ''}>${esc(u.displayName)}</option>`).join('')}</select></div>
              <div class="field"><label for="mPriority">Priority</label><select id="mPriority">${options(S.meta.priorities, c.priority)}</select></div>
              <div class="field"><label for="mCategory">Category (corrects the AI)</label><select id="mCategory">${options(S.meta.categories, c.category, catLabel)}</select></div>
            </div>
            <div class="field"><label for="mNote">Reason or note for the status change</label><input id="mNote" maxlength="2000" placeholder="Needed when escalating"></div>
            <button class="primary" data-act="saveStatus">Update status</button>
          </div>
        </section>
      </div>
    </div>`;

  // fill "other solution" dropdown lazily
  api('GET', '/api/solutions').then(list => {
    const sel = $('#otherSol'); if (!sel) return;
    sel.innerHTML += list.map(s => `<option value="${esc(s.id)}">${esc(s.id)}: ${esc(s.title)}</option>`).join('');
  }).catch(() => { /* optional */ });

  $('#noteForm').addEventListener('submit', e => {
    e.preventDefault();
    const text = $('#noteText').value.trim();
    if (!text) return;
    run(e.submitter, async () => { S.detail = await api('POST', `/api/complaints/${c.id}/notes`, { text }); drawDetail(); toast('Note added'); });
  });
  $('#mAssign').addEventListener('change', e => run(e.target, async () => {
    S.detail = await api('PUT', `/api/complaints/${c.id}/assign`, { assignedTo: e.target.value }); drawDetail(); toast('Agent updated');
  }));
  $('#mPriority').addEventListener('change', e => run(e.target, async () => {
    S.detail = await api('PUT', `/api/complaints/${c.id}/priority`, { priority: e.target.value }); drawDetail(); toast('Priority updated, deadline moved');
  }));
  $('#mCategory').addEventListener('change', e => run(e.target, async () => {
    S.detail = await api('PUT', `/api/complaints/${c.id}/category`, { category: e.target.value }); drawDetail(); toast('Category corrected. The AI learned from it.');
  }));
}

// one click handler for all detail-page buttons
document.addEventListener('click', e => {
  const el = e.target.closest('[data-act]');
  if (!el || !S.detail || !location.hash.startsWith('#/complaints/')) return;
  handleDetailAction(el);
});
document.addEventListener('keydown', e => {
  const el = e.target.closest && e.target.closest('[data-act="toggle"]');
  if (el && (e.key === 'Enter' || e.key === ' ')) { e.preventDefault(); handleDetailAction(el); }
});

function handleDetailAction(el) {
  const c = S.detail; const id = c.id; const act = el.dataset.act;
  const refresh = async (p, msg) => { S.detail = await p; drawDetail(); if (msg) toast(msg); };
  switch (act) {
    case 'toggle':
      S.openRec = S.openRec === el.dataset.id ? '' : el.dataset.id; drawDetail(); break;
    case 'apply':
      run(el, () => refresh(api('POST', `/api/complaints/${id}/apply`, { solutionId: el.dataset.id }), 'Solution applied. Reply re-drafted.')); break;
    case 'applyOther': {
      const v = $('#otherSol').value; if (!v) { toast('Pick a solution first', true); return; }
      run(el, () => refresh(api('POST', `/api/complaints/${id}/apply`, { solutionId: v }), 'Solution applied. Reply re-drafted.')); break;
    }
    case 'vote':
      run(el, () => refresh(api('POST', `/api/complaints/${id}/feedback`, { solutionId: el.dataset.id, helpful: el.dataset.v === 'true' }), 'Thanks. The assistant will use this next time.')); break;
    case 'polish':
      run(el, () => refresh(api('POST', `/api/complaints/${id}/apply`, { solutionId: c.appliedSolutionId, useLlm: true }), 'Reply polished')); break;
    case 'copy':
      navigator.clipboard.writeText($('#replyText').value).then(() => toast('Reply copied'), () => toast('Copy failed', true)); break;
    case 'send':
    case 'sendResolve':
      run(el, () => refresh(api('POST', `/api/complaints/${id}/respond`, { response: $('#replyText').value, resolve: act === 'sendResolve' }),
        act === 'sendResolve' ? 'Reply sent and complaint resolved' : 'Reply sent')); break;
    case 'saveStatus':
      run(el, () => refresh(api('PUT', `/api/complaints/${id}/status`, { status: $('#mStatus').value, note: $('#mNote').value }), 'Status updated')); break;
    case 'reanalyze':
      run(el, () => refresh(api('POST', `/api/complaints/${id}/reanalyze`, {}), 'AI analysis refreshed')); break;
    case 'delete':
      if (!confirm('Delete ' + id + ' permanently? This cannot be undone.')) return;
      run(el, async () => { await api('DELETE', `/api/complaints/${id}`); toast('Complaint deleted'); location.hash = '#/complaints'; }); break;
  }
}

// ------------------------------------------------------------------ new complaint (with live AI preview)
function renderNew() {
  view().innerHTML = `
    <div class="page-head"><div><h1>Log a complaint</h1><p>The AI reads it as you type and shows what it found on the right.</p></div></div>
    <div class="detail">
      <form class="panel panel-pad" id="newForm" novalidate>
        <div class="grid-2">
          <div class="field"><label for="nName">Customer name</label><input id="nName" name="customerName" required maxlength="80"></div>
          <div class="field"><label for="nEmail">Customer email</label><input id="nEmail" name="customerEmail" type="email" required maxlength="120"></div>
          <div class="field"><label for="nOrder">Order number (optional)</label><input id="nOrder" name="orderId" maxlength="40"></div>
          <div class="field"><label for="nChannel">Received by</label><select id="nChannel" name="channel">${options(S.meta.channels, 'EMAIL')}</select></div>
          <div class="field"><label for="nTier">Customer tier</label><select id="nTier" name="customerTier">${options(S.meta.tiers, 'STANDARD')}</select></div>
        </div>
        <div class="field"><label for="nSubject">Subject</label><input id="nSubject" name="subject" required maxlength="150" placeholder="Charged twice for my order"></div>
        <div class="field"><label for="nDesc">What happened, in the customer's words</label>
          <textarea id="nDesc" name="description" required maxlength="5000" rows="8"></textarea></div>
        <p id="newErr" class="small" style="color:var(--bad)"></p>
        <button class="primary" type="submit">Save and analyse</button>
      </form>
      <aside class="panel preview" id="preview" aria-live="polite">
        <div class="panel-head"><h2>Live AI reading</h2></div>
        <div class="panel-pad muted">Start typing the subject and description to see the topic, mood, priority and suggested fixes.</div>
      </aside>
    </div>`;

  let timer;
  const doPreview = async () => {
    const subject = $('#nSubject').value, description = $('#nDesc').value;
    if ((subject + description).trim().length < 8) return;
    try {
      const p = await api('POST', '/api/ai/analyze', { subject, description });
      const probs = Object.entries(p.categoryProbabilities).sort((a, b) => b[1] - a[1]).slice(0, 4);
      $('#preview').innerHTML = `<div class="panel-head"><h2>Live AI reading</h2></div>
        <div class="reading">
          <div><h3>Topic</h3>${barList(Object.fromEntries(probs.map(([k, v]) => [S.meta.categoryLabels[k] || k, Math.round(v * 100)])), null, '%')}
            ${p.evidence.length ? `<p class="small muted" style="margin-top:.4rem">Because of: ${p.evidence.map(esc).join(', ')}</p>` : ''}</div>
          <div><h3>Mood: ${label(p.sentiment)}</h3>${sentimentMeter(p.sentimentScore, p.sentiment)}</div>
          <div><h3>Priority: ${label(p.priority)}</h3><ul class="reasons">${p.priorityReasons.map(r => `<li>${esc(r)}</li>`).join('')}</ul>
            <p class="small muted">Customer tier and repeat complaints are added when you save.</p></div>
          <div><h3>Likely fixes</h3>${p.recommendations.length ? p.recommendations.map(r => `<div class="row" style="gap:.5rem;margin-bottom:.35rem">
            <span class="score" style="--s:${r.confidence};width:38px;height:38px;font-size:.75rem"><span>${r.confidence}</span></span><span>${esc(r.title)}</span></div>`).join('') : '<p class="muted small">No match yet.</p>'}</div>
        </div>`;
    } catch { /* preview is optional */ }
  };
  ['#nSubject', '#nDesc'].forEach(s => $(s).addEventListener('input', () => { clearTimeout(timer); timer = setTimeout(doPreview, 500); }));

  $('#newForm').addEventListener('submit', e => {
    e.preventDefault();
    const data = Object.fromEntries(new FormData(e.target).entries());
    $('#newErr').textContent = '';
    run(e.submitter, async () => {
      try {
        const c = await api('POST', '/api/complaints', data);
        toast(c.id + ' saved and analysed');
        location.hash = '#/complaints/' + c.id;
      } catch (err) { $('#newErr').textContent = err.message; }
    });
  });
}

// ------------------------------------------------------------------ knowledge base
async function renderKb(params) {
  const q = params.get('q') || ''; const cat = params.get('category') || '';
  const isAdmin = S.user.role === 'ADMIN';
  const list = await api('GET', `/api/solutions?all=${isAdmin}&q=${encodeURIComponent(q)}&category=${encodeURIComponent(cat)}`);
  view().innerHTML = `
    <div class="page-head"><div><h1>Knowledge base</h1><p>Known fixes the AI chooses from. Better articles mean better suggestions.</p></div>
      ${isAdmin ? '<button class="primary" id="addSol">Add a solution</button>' : ''}</div>
    <section class="panel">
      <form class="filters" id="kbF" style="grid-template-columns:2fr 1fr auto">
        <div><label for="kq">Search</label><input id="kq" name="q" type="search" value="${esc(q)}" placeholder="Title or keyword"></div>
        <div><label for="kc">Category</label><select id="kc" name="category">${options(S.meta.categories, cat, catLabel, 'Any')}</select></div>
        <div><button type="submit">Search</button></div>
      </form>
      ${list.length ? `<table class="data"><thead><tr><th>Solution</th><th>Category</th><th class="n">Suggested</th><th class="n">Used</th><th class="n">Helpful</th>${isAdmin ? '<th></th>' : ''}</tr></thead><tbody>
        ${list.map(s => `<tr${s.active ? '' : ' class="muted"'}><td><b>${esc(s.title)}</b>${s.active ? '' : ' (inactive)'}<div class="small muted">${esc(s.id)}: ${s.keywords.slice(0, 5).map(esc).join(', ')}</div></td>
          <td>${catLabel(s.category)}</td><td class="n">${s.timesRecommended}</td><td class="n">${s.timesApplied}</td>
          <td class="n">${s.helpfulCount + s.notHelpfulCount ? Math.round(s.successRate * 100) + '%' : '–'}</td>
          ${isAdmin ? `<td class="n"><button class="ghost" data-edit="${esc(s.id)}">Edit</button></td>` : ''}</tr>`).join('')}
      </tbody></table>` : '<div class="empty">No solutions match. Clear the search to see all.</div>'}
    </section>`;
  $('#kbF').addEventListener('submit', e => {
    e.preventDefault();
    location.hash = '#/kb?' + new URLSearchParams({ q: $('#kq').value, category: $('#kc').value });
  });
  if (isAdmin) {
    $('#addSol').addEventListener('click', () => solutionForm(null));
    $$('[data-edit]').forEach(b => b.addEventListener('click', () => solutionForm(list.find(s => s.id === b.dataset.edit))));
  }
}

function modal(title, bodyHtml) {
  const back = document.createElement('div');
  back.className = 'modal-back';
  back.innerHTML = `<div class="modal" role="dialog" aria-modal="true" aria-label="${esc(title)}">
    <div class="panel-head"><h2>${esc(title)}</h2><button class="ghost" data-close>Close</button></div>
    <div class="panel-pad">${bodyHtml}</div></div>`;
  const close = () => { back.remove(); document.removeEventListener('keydown', onKey); };
  const onKey = e => { if (e.key === 'Escape') close(); };
  back.addEventListener('click', e => { if (e.target === back || e.target.hasAttribute('data-close')) close(); });
  document.addEventListener('keydown', onKey);
  document.body.appendChild(back);
  const first = back.querySelector('input, textarea, select'); if (first) first.focus();
  return { el: back, close };
}

function solutionForm(s) {
  const m = modal(s ? 'Edit ' + s.id : 'Add a solution', `
    <form id="solForm">
      <div class="field"><label for="sTitle">Title</label><input id="sTitle" name="title" required maxlength="120" value="${esc(s?.title || '')}"></div>
      <div class="field"><label for="sCat">Category</label><select id="sCat" name="category">${options(S.meta.categories, s?.category || 'OTHER', catLabel)}</select></div>
      <div class="field"><label for="sKw">Keywords the complaint might contain (comma separated)</label><input id="sKw" name="keywords" value="${esc((s?.keywords || []).join(', '))}"></div>
      <div class="field"><label for="sSteps">Steps for the agent (one per line)</label><textarea id="sSteps" name="steps" rows="5">${esc((s?.steps || []).join('\n'))}</textarea></div>
      <div class="field"><label for="sTpl">Reply text for the customer. You can use {customerName}, {orderId}, {complaintId}, {agentName}.</label>
        <textarea id="sTpl" name="responseTemplate" rows="5">${esc(s?.responseTemplate || '')}</textarea></div>
      ${s ? `<div class="field"><label style="display:flex;gap:.4rem;align-items:center"><input type="checkbox" name="active" style="width:auto" ${s.active ? 'checked' : ''}> Active (the AI can suggest it)</label></div>` : ''}
      <p id="solErr" class="small" style="color:var(--bad)"></p>
      <div class="row" style="justify-content:space-between">
        ${s ? '<button type="button" class="danger" id="solDel">Delete</button>' : '<span></span>'}
        <button class="primary" type="submit">${s ? 'Save changes' : 'Add solution'}</button></div>
    </form>`);
  $('#solForm', m.el).addEventListener('submit', e => {
    e.preventDefault();
    const f = e.target;
    const body = { title: f.title.value, category: f.category.value, keywords: f.keywords.value,
      steps: f.steps.value.split('\n'), responseTemplate: f.responseTemplate.value };
    if (s) body.active = f.active.checked;
    run(e.submitter, async () => {
      try {
        await api(s ? 'PUT' : 'POST', s ? '/api/solutions/' + s.id : '/api/solutions', body);
        m.close(); toast(s ? 'Solution saved' : 'Solution added'); route();
      } catch (err) { $('#solErr', m.el).textContent = err.message; }
    });
  });
  if (s) $('#solDel', m.el).addEventListener('click', e => {
    if (!confirm('Delete ' + s.id + '? If complaints used it, it will be deactivated instead.')) return;
    run(e.target, async () => { const r = await api('DELETE', '/api/solutions/' + s.id); m.close(); toast('Solution ' + r.result); route(); });
  });
}

// ------------------------------------------------------------------ analytics
async function renderAnalytics() {
  const a = await api('GET', '/api/analytics');
  const q = a.aiQuality;
  const pct = v => v == null ? '–' : v + '%';
  view().innerHTML = `
    <div class="page-head"><div><h1>Analytics</h1><p>How complaints are trending and how well the AI is helping.</p></div>
      <button id="exp2">Export CSV</button></div>
    ${kpiStrip(a.kpi)}
    <div class="dash">
      <section class="panel"><div class="panel-head"><h2>Complaints per day</h2></div><div class="panel-pad">${trendChart(a.trend)}</div></section>
      <section class="panel"><div class="panel-head"><h2>AI suggestion quality</h2></div>
        <table class="data"><tbody>
          <tr><td>Agents used the first suggestion</td><td class="n">${pct(q.top1Accuracy)}</td></tr>
          <tr><td>Agents used one of the top three</td><td class="n">${pct(q.top3Accuracy)}</td></tr>
          <tr><td>Suggestions rated helpful</td><td class="n">${pct(q.helpfulRate)}</td></tr>
          <tr><td>Complaints with a solution applied</td><td class="n">${q.appliedCount}</td></tr>
          <tr><td>Helpful / not helpful votes</td><td class="n">${q.feedbackVotes}</td></tr>
        </tbody></table></section>
      <section class="panel"><div class="panel-head"><h2>By category</h2></div><div class="panel-pad">${barList(a.byCategory)}</div></section>
      <section class="panel"><div class="panel-head"><h2>Customer mood</h2></div><div class="panel-pad">${barList(relabel(a.bySentiment), k => SENT_COLORS[k])}</div></section>
      <section class="panel"><div class="panel-head"><h2>By priority</h2></div><div class="panel-pad">${barList(relabel(a.byPriority), k => PRI_COLORS[k.toUpperCase()])}</div></section>
      <section class="panel"><div class="panel-head"><h2>By status</h2></div><div class="panel-pad">${barList(relabel(a.byStatus))}</div></section>
      <section class="panel"><div class="panel-head"><h2>Most used solutions</h2></div>
        ${a.topSolutions.length ? `<table class="data"><thead><tr><th>Solution</th><th class="n">Suggested</th><th class="n">Used</th><th class="n">Helpful</th></tr></thead><tbody>
        ${a.topSolutions.map(s => `<tr><td>${esc(s.title)}</td><td class="n">${s.recommended}</td><td class="n">${s.applied}</td><td class="n">${s.successRate}%</td></tr>`).join('')}</tbody></table>` : '<div class="empty">No data yet.</div>'}</section>
      <section class="panel"><div class="panel-head"><h2>Team</h2></div>
        <table class="data"><thead><tr><th>Agent</th><th class="n">Open</th><th class="n">Resolved</th><th class="n">Overdue</th><th class="n">Rating</th></tr></thead><tbody>
        ${a.agents.map(g => `<tr><td>${esc(g.name)}</td><td class="n">${g.active}</td><td class="n">${g.resolved}</td><td class="n">${g.overdue}</td><td class="n">${g.avgRating ?? '–'}</td></tr>`).join('')}</tbody></table></section>
    </div>`;
  $('#exp2').addEventListener('click', e => run(e.target, downloadCsv));
}

// ------------------------------------------------------------------ team (admin)
async function renderTeam() {
  if (S.user.role !== 'ADMIN') { view().innerHTML = '<div class="banner warn">Only admins can manage the team.</div>'; return; }
  S.users = await api('GET', '/api/users');
  view().innerHTML = `
    <div class="page-head"><div><h1>Team</h1><p>People who can sign in. New complaints go to the agent with the fewest open ones.</p></div></div>
    <div class="dash">
      <section class="panel"><table class="data"><thead><tr><th>Name</th><th>Username</th><th>Role</th></tr></thead><tbody>
        ${S.users.map(u => `<tr><td>${esc(u.displayName)}</td><td>${esc(u.username)}</td><td>${label(u.role)}</td></tr>`).join('')}</tbody></table></section>
      <form class="panel panel-pad" id="userForm">
        <h2 style="margin-bottom:.8rem">Add a team member</h2>
        <div class="field"><label for="uName">Full name</label><input id="uName" name="displayName" required maxlength="60"></div>
        <div class="field"><label for="uUser">Username</label><input id="uUser" name="username" required maxlength="30" autocomplete="off"></div>
        <div class="field"><label for="uRole">Role</label><select id="uRole" name="role"><option value="AGENT">Agent</option><option value="ADMIN">Admin</option></select></div>
        <div class="field"><label for="uPass">Password (at least 6 characters)</label><input id="uPass" name="password" type="password" required minlength="6" autocomplete="new-password"></div>
        <p id="userErr" class="small" style="color:var(--bad)"></p>
        <button class="primary" type="submit">Add team member</button>
      </form>
    </div>`;
  $('#userForm').addEventListener('submit', e => {
    e.preventDefault();
    const data = Object.fromEntries(new FormData(e.target).entries());
    run(e.submitter, async () => {
      try { await api('POST', '/api/users', data); toast('Team member added'); renderTeam(); }
      catch (err) { $('#userErr').textContent = err.message; }
    });
  });
}

boot();
