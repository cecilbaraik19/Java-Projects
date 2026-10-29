/* Question Paper Generator - frontend.
   Plain JavaScript, no frameworks. Every piece of data is escaped with esc() before it goes into HTML,
   and no inline styles or scripts are used (the server's Content-Security-Policy blocks them). */
'use strict';

const S = {
  token: null, user: null, meta: null, routeSeq: 0,
  bank: { subject: '', unit: '', type: '', difficulty: '', bloom: '', q: '', archived: 'no', sort: 'id', limit: 30 },
  papersFilter: { status: '', subject: '', q: '' },
  coverageSubject: '',
  paperView: { key: false, tags: false },
  bpDraft: null
};
const $ = (sel, root = document) => root.querySelector(sel);
const $$ = (sel, root = document) => Array.from(root.querySelectorAll(sel));
const view = () => $('#view');
const isHod = () => S.user && (S.user.role === 'HOD' || S.user.role === 'ADMIN');
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
const typeLabel = id => esc((S.meta.types.find(t => t.id === id) || {}).label || id);
const diffLabel = id => esc((S.meta.difficulties.find(t => t.id === id) || {}).label || id);
const bloomOf = id => S.meta.blooms.find(b => b.id === id) || { label: id, level: '' };
const subjectOf = code => S.meta.subjects.find(s => s.code === code);
const subjectName = code => esc((subjectOf(code) || {}).name || code);
const userName = u => { const x = (S.meta.users || []).find(a => a.username === u); return esc(x ? x.displayName : (u || '')); };
const unitTitle = (code, no) => { const s = subjectOf(code); const u = s && s.units.find(x => x.no === no); return esc(u ? u.title : 'Unit ' + no); };
const isObjective = t => (S.meta.types.find(x => x.id === t) || {}).objective;

function dateText(iso) {
  if (!iso) return '-';
  const d = new Date(iso.length === 10 ? iso + 'T00:00:00' : iso);
  if (isNaN(d)) return esc(iso);
  return d.toLocaleDateString('en-IN', { day: 'numeric', month: 'short', year: 'numeric' });
}
function when(iso) {
  if (!iso) return '';
  const d = new Date(iso);
  return isNaN(d) ? esc(iso) : d.toLocaleString('en-IN', { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' });
}
function todayStr(offset = 0) {
  const d = new Date(); d.setDate(d.getDate() + offset);
  return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0');
}
function duration(min) {
  const h = Math.floor(min / 60), m = min % 60;
  if (!h) return m + ' minutes';
  return h + (h === 1 ? ' hour' : ' hours') + (m ? ' ' + m + ' minutes' : '');
}
function greet() {
  const h = new Date().getHours();
  return h < 12 ? 'Good morning' : h < 17 ? 'Good afternoon' : 'Good evening';
}
const plural = (n, w) => n + ' ' + w + (n === 1 ? '' : 's');
const statusChip = (st, label) => `<span class="chip st-${esc(st)}">${esc(label || (S.meta.statuses.find(x => x.id === st) || {}).label || st)}</span>`;
const diffChip = d => `<span class="chip ${esc(d)}">${diffLabel(d)}</span>`;
const bloomChip = b => { const x = bloomOf(b); return `<span class="chip" title="Bloom level: ${esc(x.label)}">${esc(x.level)} ${esc(x.label)}</span>`; };

function toast(msg, err = false) {
  const t = document.createElement('div');
  t.className = 'toast' + (err ? ' err' : '');
  t.setAttribute('role', 'status');
  t.textContent = msg;
  document.body.appendChild(t);
  setTimeout(() => t.remove(), err ? 4800 : 2600);
}

/** Thrown when an answer arrives for a page the person has already left (it is then ignored). */
class StaleError extends Error {}
/** An error from the server, with its HTTP status. */
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

/** Runs an action with the button disabled; shows any error as a red toast. */
async function run(btn, fn) {
  if (btn) btn.disabled = true;
  try { return await fn(); } catch (e) { if (!(e instanceof StaleError)) toast(e.message, true); } finally { if (btn) btn.disabled = false; }
}

/** Downloads a file from the API (the token is sent in a header, so a plain link would not work). */
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

function options(list, selected, blank) {
  let h = blank != null ? `<option value="">${esc(blank)}</option>` : '';
  for (const o of list) {
    const id = typeof o === 'object' ? (o.id ?? o.code ?? o.no) : o;
    const lb = typeof o === 'object' ? (o.label ?? o.name ?? o.title) : o;
    h += `<option value="${esc(id)}" ${String(id) === String(selected ?? '') ? 'selected' : ''}>${esc(lb)}</option>`;
  }
  return h;
}
const subjectOptions = (sel, blank) => options(S.meta.subjects.map(s => ({ id: s.code, label: s.code + ' - ' + s.name })), sel, blank);
const unitOptions = (code, sel, blank) => { const s = subjectOf(code); return options(s ? s.units.map(u => ({ id: u.no, label: 'Unit ' + u.no + ': ' + u.title })) : [], sel, blank); };
const typeOptions = (sel, blank) => options(S.meta.types, sel, blank);
const diffOptions = (sel, blank) => options(S.meta.difficulties, sel, blank);
const bloomOptions = (sel, blank) => options(S.meta.blooms.map(b => ({ id: b.id, label: b.level + ' ' + b.label })), sel, blank);

/** Sets bar sizes from data-w (width %) and data-l (left %) after the HTML is placed. CSP-safe (no inline styles). */
function paintBars(root = document) {
  requestAnimationFrame(() => {
    $$('[data-w]', root).forEach(e => { e.style.width = clamp(e.dataset.w) + '%'; });
    $$('[data-l]', root).forEach(e => { e.style.left = clamp(e.dataset.l) + '%'; });
    $$('[data-dash]', root).forEach(e => { e.style.strokeDasharray = e.dataset.dash; });
  });
}
const clamp = v => Math.max(0, Math.min(100, Number(v) || 0));

function dbar(e, m, h) {
  const t = e + m + h || 1;
  return `<div class="dbar" title="${e} easy, ${m} medium, ${h} hard"><span class="E" data-w="${e / t * 100}"></span><span class="M" data-w="${m / t * 100}"></span><span class="H" data-w="${h / t * 100}"></span></div>`;
}
const legendEMH = () => '<div class="legend"><span><i class="E"></i>Easy</span><span><i class="M"></i>Medium</span><span><i class="H"></i>Hard</span></div>';

function debounce(fn, ms) {
  let t;
  return (...a) => { clearTimeout(t); t = setTimeout(() => fn(...a), ms); };
}

// ================================================================== modal
let modalSubmit = null;

/**
 * Opens the dialog. buttons: [{label, cls, value}] - the one with value 'ok' submits the form.
 * onOk(form) may return false to keep the dialog open; throwing shows the error inside the dialog.
 */
function openModal({ title, body, buttons, onOk, wide = false, onOpen }) {
  const dlg = $('#modal');
  dlg.classList.toggle('wide', wide);
  $('#modalTitle').textContent = title;
  $('#modalBody').innerHTML = body;
  $('#modalErr').textContent = '';
  const btns = buttons || [{ label: 'Cancel', value: 'cancel' }, { label: 'Save', cls: 'primary', value: 'ok' }];
  $('#modalFoot').innerHTML = btns.map(b => `<button type="${b.value === 'ok' ? 'submit' : 'button'}" class="${esc(b.cls || '')}" data-v="${esc(b.value)}">${esc(b.label)}</button>`).join('');
  modalSubmit = onOk || null;
  $$('#modalFoot button[type=button]').forEach(b => b.addEventListener('click', () => {
    if (b.dataset.v === 'cancel') closeModal();
    else if (typeof b._handler === 'function') b._handler();
  }));
  if (!dlg.open) dlg.showModal();
  paintBars(dlg);
  if (onOpen) onOpen(dlg);
  const first = $('#modalBody input:not([type=hidden]):not([type=checkbox]):not([type=radio]), #modalBody select, #modalBody textarea');
  if (first) first.focus();
}
function closeModal() { const d = $('#modal'); if (d.open) d.close(); modalSubmit = null; }
function modalButton(value, handler) { const b = $(`#modalFoot button[data-v="${value}"]`); if (b) b._handler = handler; }

function confirmBox(title, text, okLabel = 'Yes', danger = false) {
  return new Promise(resolve => {
    openModal({
      title, body: `<p>${esc(text)}</p>`,
      buttons: [{ label: 'Cancel', value: 'cancel' }, { label: okLabel, cls: danger ? 'danger' : 'primary', value: 'ok' }],
      onOk: () => { resolve(true); closeModal(); return true; }
    });
    $('#modal').addEventListener('close', () => resolve(false), { once: true });
  });
}

function formData(form) {
  const o = {};
  for (const el of form.elements) {
    if (!el.name) continue;
    if (el.type === 'checkbox') { if (el.name.endsWith('[]')) { (o[el.name] ||= []); if (el.checked) o[el.name].push(el.value); } else o[el.name] = el.checked; }
    else if (el.type === 'radio') { if (el.checked) o[el.name] = el.value; }
    else o[el.name] = el.value;
  }
  return o;
}

// ================================================================== theme
function syncThemeButtons() {
  const dark = document.documentElement.getAttribute('data-theme') === 'dark' ||
    (!document.documentElement.getAttribute('data-theme') && matchMedia('(prefers-color-scheme: dark)').matches);
  $$('[data-theme-toggle]').forEach(b => { b.textContent = dark ? 'Light mode' : 'Dark mode'; });
  return dark;
}
function toggleTheme() {
  const dark = syncThemeButtons();
  const next = dark ? 'light' : 'dark';
  document.documentElement.setAttribute('data-theme', next);
  store.setLocal('qpg-theme', next);
  syncThemeButtons();
}

// ================================================================== sign in / out
async function signIn(e) {
  e.preventDefault();
  const f = e.target, btn = $('button[type=submit]', f);
  $('#loginErr').textContent = '';
  btn.disabled = true;
  try {
    const r = await api('POST', '/api/auth/login', { username: f.username.value.trim(), password: f.password.value });
    S.token = r.token; S.user = r.user;
    store.set('qpg-token', r.token);
    f.password.value = '';
    await boot();
  } catch (err) {
    $('#loginErr').textContent = err.message;
  } finally { btn.disabled = false; }
}

function signOut(silent) {
  if (S.token && !silent) fetch('/api/auth/logout', { method: 'POST', headers: { Authorization: 'Bearer ' + S.token, 'Content-Type': 'application/json' }, body: '{}' }).catch(() => {});
  S.token = null; S.user = null; S.meta = null;
  store.set('qpg-token', null);
  closeModal();
  $('#app').classList.add('hidden');
  $('#login').classList.remove('hidden');
  if (silent) $('#loginErr').textContent = 'Your session ended. Please sign in again.';
}

async function loadMeta() {
  S.meta = await api('GET', '/api/meta');
}

async function boot() {
  try {
    S.user = await api('GET', '/api/auth/me');
    await loadMeta();
  } catch (e) {
    if (!(e instanceof StaleError)) { signOut(true); $('#loginErr').textContent = ''; }
    return;
  }
  $('#login').classList.add('hidden');
  $('#app').classList.remove('hidden');
  $('#who').textContent = S.user.displayName;
  $('#who').title = S.user.roleLabel + ' - click to change your password';
  $('#navTeam').classList.toggle('hidden', !isAdmin());
  $('#navSettings').classList.toggle('hidden', !isHod());
  $('#railInstitute').textContent = S.meta.settings.institute;
  if (!location.hash || location.hash === '#/') location.hash = '#/dashboard';
  else route();
}

// ================================================================== router
const routes = [
  [/^#\/dashboard$/, pageDashboard, 'dashboard'],
  [/^#\/bank$/, pageBank, 'bank'],
  [/^#\/coverage$/, pageCoverage, 'coverage'],
  [/^#\/blueprints$/, pageBlueprints, 'blueprints'],
  [/^#\/blueprints\/new$/, () => pageBlueprintEdit(null), 'blueprints'],
  [/^#\/blueprints\/([\w.-]+)$/, m => pageBlueprintEdit(m[1]), 'blueprints'],
  [/^#\/generate(?:\/([\w.-]+))?$/, m => pageGenerate(m[1]), 'generate'],
  [/^#\/papers$/, pagePapers, 'papers'],
  [/^#\/papers\/([\w.-]+)$/, m => pagePaper(m[1]), 'papers'],
  [/^#\/subjects$/, pageSubjects, 'subjects'],
  [/^#\/settings$/, pageSettings, 'settings'],
  [/^#\/team$/, pageTeam, 'team']
];

async function route() {
  if (!S.token || !S.meta) return;
  S.routeSeq++;
  document.body.classList.remove('print-key');
  closeRail();
  const hash = location.hash || '#/dashboard';
  for (const [re, fn, nav] of routes) {
    const m = hash.match(re);
    if (!m) continue;
    $$('#nav a').forEach(a => a.classList.toggle('active', a.dataset.nav === nav));
    view().innerHTML = '<p class="muted">Loading...</p>';
    try {
      await fn(m);
      paintBars(view());
      updateReviewCount();
    } catch (e) {
      if (e instanceof StaleError) return;
      view().innerHTML = `<div class="card"><h2>Something went wrong</h2><p>${esc(e.message)}</p><a href="#/dashboard">Back to the dashboard</a></div>`;
    }
    view().focus({ preventScroll: true });
    window.scrollTo(0, 0);
    return;
  }
  location.hash = '#/dashboard';
}

async function updateReviewCount() {
  try {
    const list = await fetch('/api/papers?status=SUBMITTED', { headers: { Authorization: 'Bearer ' + S.token } }).then(r => r.ok ? r.json() : []);
    const n = Array.isArray(list) ? list.length : 0;
    const el = $('#navReview');
    el.textContent = n;
    el.classList.toggle('hidden', n === 0 || !isHod());
  } catch { /* not important */ }
}

function openRail() { $('#rail').classList.add('open'); $('#railShade').classList.remove('hidden'); }
function closeRail() { $('#rail').classList.remove('open'); $('#railShade').classList.add('hidden'); }

// ================================================================== dashboard
async function pageDashboard() {
  const [d, cov] = await Promise.all([api('GET', '/api/dashboard'), api('GET', '/api/coverage')]);
  const covBy = {};
  for (const s of cov.subjects) {
    const t = { EASY: 0, MEDIUM: 0, HARD: 0 };
    for (const u of s.units) for (const k in t) t[k] += u.byDifficulty[k];
    covBy[s.code] = { ...t, gaps: s.gaps };
  }
  const st = d.byStatus;
  const reviewCard = isHod() ? `
    <div class="card">
      <div class="card-head"><h2>Waiting for your review</h2><span class="spacer"></span><a href="#/papers" class="small">All papers</a></div>
      ${d.review.length ? `<ul class="list">${d.review.map(p => `
        <li><div class="grow"><a class="title" href="#/papers/${esc(p.id)}">${esc(p.title)}</a>
          <div class="tiny muted">${esc(p.subjectCode)} · ${p.totalMarks} marks · by ${userName(p.createdBy)} · exam ${dateText(p.examDate)}</div></div>
          ${p.warningCount ? `<span class="chip warn">${plural(p.warningCount, 'note')}</span>` : ''}
          <a class="btn sm" href="#/papers/${esc(p.id)}">Review</a></li>`).join('')}</ul>`
      : '<p class="empty">Nothing to review right now.</p>'}
    </div>` : '';
  view().innerHTML = `
    <div class="page-head">
      <div><h1>${greet()}, ${esc(S.user.displayName)}</h1>
        <p class="sub">${esc(S.user.roleLabel)} · ${esc(S.meta.settings.institute)} · ${new Date().toLocaleDateString('en-IN', { weekday: 'long', day: 'numeric', month: 'long' })}</p></div>
      <div class="actions"><a class="btn" href="#/bank" id="dAdd">Add a question</a><a class="btn primary" href="#/generate">Generate a paper</a></div>
    </div>
    <div class="stats">
      <div class="card stat"><span class="lbl">Questions in the bank</span><div class="big">${d.questions}</div>
        <div class="note">${d.mine} added by you · ${d.archived} archived</div></div>
      <div class="card stat"><span class="lbl">Never used yet</span><div class="big">${d.neverUsed}</div>
        <div class="note">fresh questions for the next paper</div></div>
      <div class="card stat"><span class="lbl">Papers</span><div class="big">${d.papers}</div>
        <div class="note">${st.DRAFT} draft · ${st.APPROVED} approved · ${st.RELEASED} released</div></div>
      <div class="card stat"><span class="lbl">Waiting for review</span><div class="big ${st.SUBMITTED ? 'warn-text' : ''}">${st.SUBMITTED}</div>
        <div class="note">${d.blueprints} blueprints ready to use</div></div>
    </div>
    <div class="dash">
      <div>
        ${reviewCard}
        <div class="card">
          <div class="card-head"><h2>${isHod() ? 'Drafts in progress' : 'Your drafts'}</h2></div>
          ${d.drafts.length ? `<ul class="list">${d.drafts.map(p => `
            <li><div class="grow"><a class="title" href="#/papers/${esc(p.id)}">${esc(p.title)}</a>
              <div class="tiny muted">${esc(p.subjectCode)} · ${p.questionCount} questions · ${p.totalMarks} marks · by ${userName(p.createdBy)}</div>
              ${p.reviewNote ? `<div class="note-box bad"><b>Sent back:</b> ${esc(p.reviewNote)}</div>` : ''}</div>
              <a class="btn sm" href="#/papers/${esc(p.id)}">Open</a></li>`).join('')}</ul>`
          : '<p class="empty">No drafts. <a href="#/generate">Generate a paper</a> to start one.</p>'}
        </div>
        <div class="card">
          <div class="card-head"><h2>Bank strength by subject</h2><span class="spacer"></span>${legendEMH()}</div>
          ${d.subjects.map(s => { const c = covBy[s.code] || { EASY: 0, MEDIUM: 0, HARD: 0, gaps: [] }; return `
            <div class="subject-row"><span class="code" title="${esc(s.name)}">${esc(s.code)}</span>
              <div><div class="small">${esc(s.name)}</div>${dbar(c.EASY, c.MEDIUM, c.HARD)}
                ${c.gaps.length ? `<div class="tiny warn-text">${esc(c.gaps[0])}${c.gaps.length > 1 ? ' (+' + (c.gaps.length - 1) + ' more)' : ''}</div>` : ''}</div>
              <span class="right"><b>${s.questions}</b><span class="tiny muted"> Qs</span></span></div>`; }).join('')}
          <p class="small" ><a href="#/coverage">See unit-by-unit coverage</a></p>
        </div>
      </div>
      <div>
        <div class="card">
          <div class="card-head"><h2>Coming exams</h2></div>
          ${d.upcoming.length ? `<ul class="list">${d.upcoming.map(p => `
            <li><div class="grow"><a class="title" href="#/papers/${esc(p.id)}">${esc(p.title)}</a>
              <div class="tiny muted">${dateText(p.examDate)} · ${duration(p.durationMin)}</div></div>${statusChip(p.status, p.statusLabel)}</li>`).join('')}</ul>`
          : '<p class="empty">No exams with an approved paper yet.</p>'}
        </div>
        <div class="card">
          <div class="card-head"><h2>Recent activity</h2></div>
          ${activityList(d.recent)}
        </div>
      </div>
    </div>`;
  $('#dAdd').addEventListener('click', e => { e.preventDefault(); questionForm(null); });
}

function activityList(events) {
  if (!events.length) return '<p class="empty">Nothing yet.</p>';
  return `<ul class="list feed">${events.map(e => {
    const link = e.ref && e.ref.startsWith('paper:') ? `#/papers/${esc(e.ref.slice(6))}` : null;
    return `<li><span class="when">${when(e.at)}</span><div class="grow small"><b>${userName(e.user)}</b> ${link ? `<a href="${link}">${esc(e.detail)}</a>` : esc(e.detail)}</div></li>`;
  }).join('')}</ul>`;
}

// ================================================================== question bank
async function pageBank() {
  const f = S.bank;
  view().innerHTML = `
    <div class="page-head">
      <div><h1>Question bank</h1><p class="sub">Every question, sorted by subject and unit. Click a card to see or edit it.</p></div>
      <div class="actions">
        <button type="button" id="bImport">Import CSV</button>
        <button type="button" id="bExport">Export CSV</button>
        <button type="button" class="primary" id="bAdd">Add question</button>
      </div>
    </div>
    <form class="toolbar" id="bankFilters">
      <select name="subject" aria-label="Subject">${subjectOptions(f.subject, 'All subjects')}</select>
      <select name="unit" aria-label="Unit" ${f.subject ? '' : 'disabled'}>${unitOptions(f.subject, f.unit, 'All units')}</select>
      <select name="type" aria-label="Kind">${typeOptions(f.type, 'All kinds')}</select>
      <select name="difficulty" aria-label="Difficulty">${diffOptions(f.difficulty, 'Any difficulty')}</select>
      <select name="bloom" aria-label="Bloom level">${bloomOptions(f.bloom, 'Any Bloom level')}</select>
      <input type="search" name="q" placeholder="Search text, id or tag" value="${esc(f.q)}" aria-label="Search">
      <select name="archived" aria-label="Archived">${options([{ id: 'no', label: 'In use' }, { id: 'only', label: 'Archived' }, { id: 'all', label: 'In use + archived' }], f.archived)}</select>
      <select name="sort" aria-label="Sort">${options([{ id: 'id', label: 'Sort: id' }, { id: 'unit', label: 'Sort: unit' }, { id: 'new', label: 'Sort: recently changed' }, { id: 'used', label: 'Sort: most used' }, { id: 'unused', label: 'Sort: least used' }, { id: 'marks', label: 'Sort: marks' }], f.sort)}</select>
      <button type="button" class="ghost" id="bClear">Clear</button>
    </form>
    <p class="small muted" id="bCount"></p>
    <div class="qlist" id="qlist"></div>
    <div class="more"><button type="button" id="bMore" class="hidden">Show more</button></div>`;
  const form = $('#bankFilters');
  const reload = () => loadBank(false);
  form.addEventListener('change', e => {
    Object.assign(S.bank, formData(form));
    if (e.target.name === 'subject') { S.bank.unit = ''; form.unit.innerHTML = unitOptions(S.bank.subject, '', 'All units'); form.unit.disabled = !S.bank.subject; }
    reload();
  });
  form.q.addEventListener('input', debounce(() => { S.bank.q = form.q.value; reload(); }, 300));
  form.addEventListener('submit', e => e.preventDefault());
  $('#bClear').addEventListener('click', () => { S.bank = { subject: '', unit: '', type: '', difficulty: '', bloom: '', q: '', archived: 'no', sort: 'id', limit: 30 }; pageBank(); });
  $('#bAdd').addEventListener('click', () => questionForm(null));
  $('#bImport').addEventListener('click', importForm);
  $('#bExport').addEventListener('click', e => run(e.currentTarget, () => download('/api/questions/export.csv?' + bankQuery(true), 'question-bank.csv')));
  $('#bMore').addEventListener('click', () => loadBank(true));
  await loadBank(false);
}

function bankQuery(forExport) {
  const p = new URLSearchParams();
  for (const k of ['subject', 'unit', 'type', 'difficulty', 'bloom', 'q', 'archived', 'sort']) if (S.bank[k]) p.set(k, S.bank[k]);
  if (!forExport) p.set('limit', S.bank.limit);
  return p.toString();
}

let bankOffset = 0;
async function loadBank(more) {
  bankOffset = more ? bankOffset : 0;
  const r = await api('GET', '/api/questions?' + bankQuery(false) + '&offset=' + bankOffset);
  const list = $('#qlist');
  if (!list) return;
  const html = r.items.map(qCard).join('');
  if (more) list.insertAdjacentHTML('beforeend', html); else list.innerHTML = html || '<div class="card"><p class="empty">No questions match. Try clearing the filters.</p></div>';
  bankOffset += r.items.length;
  $('#bCount').textContent = r.total ? `Showing ${bankOffset} of ${plural(r.total, 'question')}` : '';
  $('#bMore').classList.toggle('hidden', bankOffset >= r.total);
  $$('.qcard', list).forEach(c => { if (!c._wired) { c._wired = true; c.addEventListener('click', () => questionDetail(c.dataset.id)); c.addEventListener('keydown', e => { if (e.key === 'Enter') questionDetail(c.dataset.id); }); } });
}

function qCard(q) {
  const opts = q.type === 'MCQ' ? `<ul class="qopts">${q.options.map((o, i) => `<li class="${o === q.answer ? 'right' : ''}">(${String.fromCharCode(97 + i)}) ${esc(o)}</li>`).join('')}</ul>` : '';
  return `<article class="card qcard ${q.archived ? 'archived' : ''}" data-id="${esc(q.id)}" tabindex="0" aria-label="Question ${esc(q.id)}">
    <div class="qtop"><span class="qid">${esc(q.id)}</span><span class="chip pen">${esc(q.subjectCode)} · U${q.unit}</span>
      <span class="chip">${typeLabel(q.type)}</span>${diffChip(q.difficulty)}${bloomChip(q.bloom)}
      ${q.archived ? '<span class="chip bad">Archived</span>' : ''}<span class="spacer"></span><span class="marks" title="Marks">${q.marks}</span></div>
    <p class="qtext">${esc(q.text)}</p>${opts}
    <div class="qfoot"><span>by ${userName(q.createdBy)}</span><span>${q.timesUsed ? 'used ' + plural(q.timesUsed, 'time') + (q.lastUsedOn ? ', last ' + dateText(q.lastUsedOn) : '') : 'never used'}</span>
      ${q.tags.length ? `<span>tags: ${q.tags.map(esc).join(', ')}</span>` : ''}</div>
  </article>`;
}

async function questionDetail(id) {
  const q = await api('GET', '/api/questions/' + encodeURIComponent(id));
  const canEdit = isHod() || q.createdBy === S.user.username;
  const answer = q.type === 'MCQ'
    ? `<ul class="qopts">${q.options.map((o, i) => `<li class="${o === q.answer ? 'right' : ''}">(${String.fromCharCode(97 + i)}) ${esc(o)}</li>`).join('')}</ul>`
    : `<p class="qtext">${q.answer ? esc(q.answer) : '<span class="muted">No model answer yet.</span>'}</p>`;
  openModal({
    title: q.id + ' · ' + q.subjectCode, wide: true,
    body: `
      <div class="qtop">${diffChip(q.difficulty)}${bloomChip(q.bloom)}<span class="chip">${typeLabel(q.type)}</span>
        ${q.archived ? '<span class="chip bad">Archived</span>' : ''}<span class="spacer"></span><span class="marks">${q.marks}</span></div>
      <p class="qtext"><b>${esc(q.text)}</b></p>
      <h3>${q.type === 'MCQ' ? 'Options (correct one ticked)' : q.type === 'SHORT' || q.type === 'LONG' || q.type === 'NUMERICAL' ? 'Model answer / key points' : 'Answer'}</h3>
      ${answer}
      <dl class="dl">
        <dt>Subject / unit</dt><dd>${subjectName(q.subjectCode)} · Unit ${q.unit}: ${unitTitle(q.subjectCode, q.unit)}</dd>
        <dt>Added by</dt><dd>${userName(q.createdBy)} on ${dateText(q.createdAt)}${q.updatedAt !== q.createdAt ? ' · changed ' + dateText(q.updatedAt) : ''}</dd>
        <dt>Used in exams</dt><dd>${q.timesUsed ? plural(q.timesUsed, 'time') + ', last on ' + dateText(q.lastUsedOn) : 'Never'}</dd>
        <dt>Tags</dt><dd>${q.tags.length ? q.tags.map(esc).join(', ') : '-'}</dd>
      </dl>
      <h3>In papers</h3>
      ${q.papers.length ? `<ul class="list">${q.papers.map(p => `<li><a class="title grow" href="#/papers/${esc(p.id)}">${esc(p.title)}</a>${statusChip(p.status, p.statusLabel)}</li>`).join('')}</ul>` : '<p class="empty">Not in any paper.</p>'}
      ${q.similar.length ? `<h3>Looks similar to</h3><ul class="list">${q.similar.map(s => `<li><span class="qid">${esc(s.id)}</span><span class="grow small">${esc(s.text)}</span><span class="chip ${s.duplicate ? 'bad' : 'warn'}">${s.similarity}% alike</span></li>`).join('')}</ul>` : ''}`,
    buttons: [
      ...(canEdit && !q.inPapers ? [{ label: 'Delete', cls: 'danger', value: 'del' }] : []),
      ...(canEdit ? [{ label: q.archived ? 'Restore' : 'Archive', value: 'arch' }] : []),
      { label: 'Close', value: 'cancel' },
      ...(canEdit ? [{ label: 'Edit', cls: 'primary', value: 'edit' }] : [])
    ]
  });
  modalButton('edit', () => questionForm(q));
  modalButton('arch', () => run(null, async () => {
    await api('POST', `/api/questions/${encodeURIComponent(q.id)}/${q.archived ? 'restore' : 'archive'}`);
    closeModal(); toast(q.archived ? 'Restored' : 'Archived - it will not be used in new papers'); refreshBank();
  }));
  modalButton('del', async () => {
    if (!await confirmBox('Delete question', `Delete ${q.id} for good? This cannot be undone.`, 'Delete', true)) return;
    await run(null, async () => { await api('DELETE', '/api/questions/' + encodeURIComponent(q.id)); toast('Deleted'); refreshBank(); });
  });
}

function refreshBank() { if ($('#qlist')) loadBank(false); }

function questionForm(q) {
  const isNew = !q;
  const d = q || { subjectCode: S.bank.subject || (S.meta.subjects[0] || {}).code, unit: Number(S.bank.unit) || 1, type: 'SHORT', marks: 5, difficulty: 'MEDIUM', bloom: 'UNDERSTAND', text: '', options: ['', '', '', ''], answer: '', tags: [] };
  openModal({
    title: isNew ? 'Add a question' : 'Edit ' + q.id, wide: true,
    body: `
      <div class="grid3">
        <div class="field"><label for="qs">Subject</label><select id="qs" name="subjectCode">${subjectOptions(d.subjectCode)}</select></div>
        <div class="field"><label for="qu">Unit</label><select id="qu" name="unit">${unitOptions(d.subjectCode, d.unit)}</select></div>
        <div class="field"><label for="qt">Kind</label><select id="qt" name="type">${typeOptions(d.type)}</select></div>
      </div>
      <div class="grid3">
        <div class="field"><label for="qm">Marks</label><input id="qm" name="marks" type="number" min="1" max="50" value="${esc(d.marks)}" required></div>
        <div class="field"><label for="qd">Difficulty</label><select id="qd" name="difficulty">${diffOptions(d.difficulty)}</select></div>
        <div class="field"><label for="qb">Bloom level</label><select id="qb" name="bloom">${bloomOptions(d.bloom)}</select>
          <span class="hint">What thinking it tests: remember → create</span></div>
      </div>
      <div class="field"><label for="qx">Question</label><textarea id="qx" name="text" rows="4" maxlength="2000" required>${esc(d.text)}</textarea>
        <span class="hint" id="qxHint"></span></div>
      <div class="similar-box" id="simBox"></div>
      <div id="answerArea"></div>
      <div class="field"><label for="qg">Tags (optional, comma separated)</label><input id="qg" name="tags" value="${esc(d.tags.join(', '))}" placeholder="paging, memory"></div>`,
    onOk: async form => {
      const v = collectQuestion(form);
      try {
        await saveQuestion(isNew, q, v, false);
      } catch (e) {
        if (e.status === 409 && await confirmBox('Looks like a duplicate', e.message, 'Save anyway')) {
          await saveQuestion(isNew, q, v, true);
          return true;
        }
        throw e;
      }
      return true;
    },
    onOpen: dlg => {
      const form = $('#modalForm');
      const drawAnswer = () => drawAnswerArea(form.type.value, d);
      form.subjectCode.addEventListener('change', () => { form.unit.innerHTML = unitOptions(form.subjectCode.value, 1); checkSimilar(form, q); });
      form.type.addEventListener('change', () => {
        if (isObjective(form.type.value) && Number(form.marks.value) > 5) form.marks.value = 1;
        if (form.type.value === 'LONG' && Number(form.marks.value) < 6) form.marks.value = 10;
        drawAnswer();
      });
      form.text.addEventListener('input', debounce(() => checkSimilar(form, q), 450));
      drawAnswer();
      if (!isNew) checkSimilar(form, q);
    }
  });
}

/** Shows the answer inputs that fit the kind of question. */
function drawAnswerArea(type, d) {
  const area = $('#answerArea');
  const hint = $('#qxHint');
  hint.textContent = type === 'FILL_BLANK' ? 'Show the blank with three or more underscores, like: The kernel is the ________ of the OS.' : '';
  if (type === 'MCQ') {
    const opts = d.type === 'MCQ' && d.options.length ? [...d.options] : ['', '', '', ''];
    while (opts.length < 4) opts.push('');
    area.innerHTML = `<label class="lbl">Options - tick the correct one</label><div id="optRows">${opts.map((o, i) => optRow(i, o, o && o === d.answer)).join('')}</div>
      <button type="button" class="sm" id="addOpt">Add option</button><div class="field"></div>`;
    $('#addOpt').addEventListener('click', () => {
      const n = $$('#optRows .opt-row').length;
      if (n >= 6) return toast('At most 6 options', true);
      $('#optRows').insertAdjacentHTML('beforeend', optRow(n, '', false));
      wireOptRows();
    });
    wireOptRows();
  } else if (type === 'TRUE_FALSE') {
    area.innerHTML = `<div class="field"><span class="lbl">Correct answer</span><div class="row">
      <label class="check"><input type="radio" name="tf" value="True" ${d.answer === 'True' ? 'checked' : ''}> True</label>
      <label class="check"><input type="radio" name="tf" value="False" ${d.answer === 'False' ? 'checked' : ''}> False</label></div></div>`;
  } else if (type === 'FILL_BLANK') {
    area.innerHTML = `<div class="field"><label for="qa">Answer (word for the blank)</label><input id="qa" name="answer" value="${esc(d.type === 'FILL_BLANK' ? d.answer : '')}" required></div>`;
  } else {
    area.innerHTML = `<div class="field"><label for="qa">Model answer / key points (for the answer key)</label><textarea id="qa" name="answer" rows="3" maxlength="3000">${esc(['SHORT', 'LONG', 'NUMERICAL'].includes(d.type) ? d.answer : '')}</textarea></div>`;
  }
}
function optRow(i, val, right) {
  return `<div class="opt-row"><span class="letter">${String.fromCharCode(97 + i)}</span>
    <input name="opt" value="${esc(val)}" maxlength="300" aria-label="Option ${String.fromCharCode(97 + i)}">
    <label class="check"><input type="radio" name="right" value="${i}" ${right ? 'checked' : ''}> correct</label></div>`;
}
function wireOptRows() {
  $$('#optRows .opt-row').forEach((r, i) => { $('.letter', r).textContent = String.fromCharCode(97 + i); $('input[type=radio]', r).value = i; });
}
function collectQuestion(form) {
  const type = form.type.value;
  const v = {
    subjectCode: form.subjectCode.value, unit: Number(form.unit.value), type, marks: Number(form.marks.value),
    difficulty: form.difficulty.value, bloom: form.bloom.value, text: form.text.value, tags: form.tags.value
  };
  if (type === 'MCQ') {
    const rows = $$('#optRows .opt-row');
    v.options = rows.map(r => $('input[name=opt]', r).value.trim());
    const r = rows.findIndex(x => $('input[type=radio]', x).checked);
    if (r < 0) throw new Error('Tick the correct option');
    v.answer = v.options[r];
    if (!v.answer) throw new Error('The ticked option is empty');
    v.options = v.options.filter(Boolean);
  } else if (type === 'TRUE_FALSE') {
    const c = $('input[name=tf]:checked', form);
    if (!c) throw new Error('Choose True or False');
    v.answer = c.value;
  } else v.answer = form.answer ? form.answer.value : '';
  return v;
}
async function saveQuestion(isNew, q, v, force) {
  const saved = isNew ? await api('POST', '/api/questions', { ...v, force }) : await api('PUT', '/api/questions/' + encodeURIComponent(q.id), v);
  closeModal();
  toast(isNew ? 'Added ' + saved.id : 'Saved');
  await loadMeta();
  refreshBank();
}
async function checkSimilar(form, q) {
  const box = $('#simBox');
  if (!box) return;
  const text = form.text.value.trim();
  if (text.length < 12) { box.innerHTML = ''; return; }
  try {
    const list = await api('POST', '/api/questions/similar', { subjectCode: form.subjectCode.value, text, exceptId: q ? q.id : '' });
    if (!$('#simBox')) return;
    box.innerHTML = list.length ? `<div class="note-box ${list[0].duplicate ? 'bad' : ''}"><b>${list[0].duplicate ? 'Possible duplicate' : 'Similar questions already in the bank'}:</b>
      ${list.slice(0, 3).map(s => `<div class="small"><span class="qid">${esc(s.id)}</span> ${esc(s.text)} <b>(${s.similarity}% alike)</b></div>`).join('')}</div>` : '';
  } catch { box.innerHTML = ''; }
}

function importForm() {
  openModal({
    title: 'Import questions from CSV', wide: true,
    body: `
      <p class="small">Columns: <span class="mono">subject, unit, type, difficulty, bloom, marks, question, options, answer, tags</span>.
        Put MCQ options in one cell separated by <b>|</b>. Excel: <i>File → Save As → CSV UTF-8</i>.
        <button type="button" class="link" id="tplBtn">Download a sample file</button></p>
      <div class="field"><label for="csvFile">Choose a .csv file</label><input id="csvFile" type="file" accept=".csv,text/csv"></div>
      <div class="field"><label for="csvText">...or paste the CSV here</label><textarea id="csvText" name="csv" rows="7" class="mono"></textarea></div>
      <div id="impResult"></div>`,
    buttons: [{ label: 'Cancel', value: 'cancel' }, { label: 'Check file', value: 'check' }, { label: 'Import', cls: 'primary', value: 'ok' }],
    onOk: async form => {
      const r = await api('POST', '/api/questions/import', { csv: form.csv.value, dryRun: false });
      closeModal();
      toast(`Imported ${plural(r.ok, 'question')}` + (r.errors.length ? `, skipped ${r.errors.length}` : ''));
      await loadMeta();
      refreshBank();
      return true;
    },
    onOpen: () => {
      $('#tplBtn').addEventListener('click', () => run(null, () => download('/api/questions/template.csv', 'question-template.csv')));
      $('#csvFile').addEventListener('change', e => {
        const f = e.target.files[0];
        if (!f) return;
        if (f.size > 900 * 1024) { toast('That file is too big (max about 900 KB)', true); return; }
        const rd = new FileReader();
        rd.onload = () => { $('#csvText').value = rd.result; };
        rd.readAsText(f, 'utf-8');
      });
    }
  });
  modalButton('check', () => run(null, async () => {
    const r = await api('POST', '/api/questions/import', { csv: $('#csvText').value, dryRun: true });
    $('#impResult').innerHTML = `
      <div class="note-box ${r.ok ? 'info' : 'bad'}"><b>${plural(r.ok, 'question')} ready to import</b> out of ${plural(r.rows, 'row')}${r.duplicates ? ` · ${r.duplicates} already in the bank` : ''}.</div>
      ${r.errors.length ? `<h3>Rows that will be skipped</h3><div class="table-wrap"><table><thead><tr><th>Row</th><th>Why</th></tr></thead><tbody>
        ${r.errors.slice(0, 30).map(x => `<tr><td class="num">${x.row}</td><td>${esc(x.error)}</td></tr>`).join('')}</tbody></table></div>` : ''}
      ${r.preview.length ? `<h3>First rows</h3><ul class="list">${r.preview.map(p => `<li><span class="chip pen">${esc(p.subjectCode)} U${p.unit}</span><span class="chip">${typeLabel(p.type)}</span><span class="grow small">${esc(p.text)}</span><span class="marks">${p.marks}</span></li>`).join('')}</ul>` : ''}`;
    paintBars($('#impResult'));
  }));
}

// ================================================================== coverage
async function pageCoverage() {
  const cov = await api('GET', '/api/coverage');
  if (!S.coverageSubject || !cov.subjects.find(s => s.code === S.coverageSubject)) S.coverageSubject = (cov.subjects[0] || {}).code || '';
  const s = cov.subjects.find(x => x.code === S.coverageSubject);
  const level = n => n === 0 ? 'c0' : n <= 1 ? 'c1' : n <= 3 ? 'c2' : n <= 6 ? 'c3' : 'c4';
  const types = S.meta.types;
  view().innerHTML = `
    <div class="page-head"><div><h1>Coverage</h1><p class="sub">How many questions each unit has. Red boxes are empty: the generator cannot pick from them.</p></div></div>
    <div class="tabs" role="tablist">${cov.subjects.map(x => `<button type="button" role="tab" data-code="${esc(x.code)}" class="${x.code === S.coverageSubject ? 'on' : ''}">${esc(x.code)} <span class="n">${x.total}</span></button>`).join('')}</div>
    ${s ? `
    <div class="dash">
      <div class="card">
        <div class="card-head"><h2>${esc(s.name)}: units × difficulty</h2></div>
        <div class="heat" id="heat1"></div>
      </div>
      <div class="card">
        <div class="card-head"><h2>Things to fix</h2></div>
        ${s.gaps.length ? `<ul class="list">${s.gaps.map(g => `<li><span class="dot warn"></span><span class="grow">${esc(g)}</span></li>`).join('')}</ul>` : '<p class="good-text">Every unit has at least 5 questions and some hard ones. Good.</p>'}
        <h3>By kind of question</h3>
        <div class="hbars">${types.map(t => `<div class="hbar"><span class="name">${esc(t.label)}</span><div class="track"><div class="fill" data-w="${s.total ? s.byType[t.id] / s.total * 100 : 0}"></div></div><span class="val">${s.byType[t.id]}</span></div>`).join('')}</div>
      </div>
    </div>
    <div class="card" >
      <div class="card-head"><h2>Units × kind of question</h2></div>
      <div class="table-wrap"><div class="heat" id="heat2"></div></div>
    </div>` : '<div class="card"><p class="empty">No subjects yet.</p></div>'}`;
  $$('.tabs button').forEach(b => b.addEventListener('click', () => { S.coverageSubject = b.dataset.code; pageCoverage().then(() => paintBars(view())); }));
  if (!s) return;
  const h1 = $('#heat1');
  h1.style.gridTemplateColumns = 'minmax(10rem, 2fr) repeat(4, minmax(3.5rem, 1fr))';
  h1.innerHTML = '<div class="h">Unit</div><div class="h">Easy</div><div class="h">Medium</div><div class="h">Hard</div><div class="h">Total</div>' +
    s.units.map(u => `<div class="u" title="${esc(u.title)}">${u.no}. ${esc(u.title)}</div>` +
      ['EASY', 'MEDIUM', 'HARD'].map(k => `<div class="c ${level(u.byDifficulty[k])}">${u.byDifficulty[k]}</div>`).join('') +
      `<div class="c ${level(u.total / 3)}">${u.total}</div>`).join('');
  const h2 = $('#heat2');
  h2.style.gridTemplateColumns = `minmax(10rem, 2fr) repeat(${types.length}, minmax(4.2rem, 1fr))`;
  h2.innerHTML = '<div class="h">Unit</div>' + types.map(t => `<div class="h">${esc(t.label)}</div>`).join('') +
    s.units.map(u => `<div class="u">${u.no}. ${esc(u.title)}</div>` + types.map(t => `<div class="c ${level(u.byType[t.id])}">${u.byType[t.id]}</div>`).join('')).join('');
  $$('.c', view()).forEach(c => { c.title = c.textContent + ' questions'; });
}

// ================================================================== blueprints
async function pageBlueprints() {
  const list = await api('GET', '/api/blueprints');
  view().innerHTML = `
    <div class="page-head"><div><h1>Blueprints</h1><p class="sub">A blueprint is the pattern of a paper: its sections, marks and the easy / medium / hard mix.</p></div>
      <div class="actions"><a class="btn primary" href="#/blueprints/new">New blueprint</a></div></div>
    <div class="bp-grid">${list.map(b => {
      const c = b.check;
      return `<article class="card bp-card">
        <div class="card-head"><h3>${esc(b.name)}</h3><span class="spacer"></span><span class="marks" title="Total marks">${b.totalMarks}</span></div>
        <div class="bp-meta"><span class="chip pen">${esc(b.subjectCode)}</span><span class="chip">${esc(b.examType)}</span><span class="chip">${duration(b.durationMin)}</span>
          ${b.units.length ? `<span class="chip">Units ${b.units.join(', ')}</span>` : '<span class="chip">All units</span>'}</div>
        <ul class="bp-sections">${b.sections.map(s => `<li><span>${esc(s.title)}: ${typeLabel(s.type)}</span><span class="nowrap">${s.attempt < s.count ? `any ${s.attempt} of ${s.count}` : s.count} × ${s.marksEach} = <b>${s.marks}</b></span></li>`).join('')}</ul>
        ${dbar(b.mix.EASY, b.mix.MEDIUM, b.mix.HARD)}
        <div class="tiny muted">${b.mix.EASY}% easy · ${b.mix.MEDIUM}% medium · ${b.mix.HARD}% hard</div>
        <p class="small ${c.ok ? 'good-text' : 'warn-text'}">${c.ok ? `Bank is ready · up to ${plural(c.setsWithoutRepeats, 'set')} with no repeats` : 'The bank does not have enough questions for this blueprint'}</p>
        <div class="tiny muted">${plural(b.papersMade, 'paper')} made · by ${userName(b.createdBy)}</div>
        <div class="bp-foot">
          <a class="btn sm primary" href="#/generate/${esc(b.id)}">Generate</a>
          <a class="btn sm" href="#/blueprints/${esc(b.id)}">${isHod() || b.createdBy === S.user.username ? 'Edit' : 'View'}</a>
          <button type="button" class="sm" data-copy="${esc(b.id)}">Copy</button>
          ${isHod() || b.createdBy === S.user.username ? `<button type="button" class="sm danger" data-del="${esc(b.id)}">Delete</button>` : ''}
        </div></article>`;
    }).join('') || '<div class="card"><p class="empty">No blueprints yet.</p></div>'}</div>`;
  $$('[data-copy]').forEach(b => b.addEventListener('click', () => run(b, async () => {
    const c = await api('POST', `/api/blueprints/${b.dataset.copy}/copy`);
    toast('Copied'); location.hash = '#/blueprints/' + c.id;
  })));
  $$('[data-del]').forEach(b => b.addEventListener('click', async () => {
    if (!await confirmBox('Delete blueprint', 'Delete this blueprint? Papers already made from it are kept.', 'Delete', true)) return;
    await run(b, async () => { await api('DELETE', '/api/blueprints/' + b.dataset.del); toast('Deleted'); route(); });
  }));
}

function blankSection(i) {
  const presets = [
    { title: 'Section A', instructions: 'Choose the correct option.', type: 'MCQ', count: 10, attempt: 10, marksEach: 1 },
    { title: 'Section B', instructions: 'Answer any four questions.', type: 'SHORT', count: 5, attempt: 4, marksEach: 5 },
    { title: 'Section C', instructions: 'Answer any two questions.', type: 'LONG', count: 3, attempt: 2, marksEach: 10 }
  ];
  return { ...(presets[i] || { title: 'Section ' + String.fromCharCode(65 + i), instructions: '', type: 'SHORT', count: 3, attempt: 3, marksEach: 5 }) };
}

async function pageBlueprintEdit(id) {
  const b = id ? await api('GET', '/api/blueprints/' + encodeURIComponent(id)) : {
    name: '', subjectCode: (S.meta.subjects[0] || {}).code, examType: 'Mid-Term Examination', durationMin: 90,
    instructions: ['Answer all questions in Section A.', 'Figures on the right show full marks.'],
    sections: [blankSection(0), blankSection(1)], mix: { EASY: 30, MEDIUM: 50, HARD: 20 }, units: [], avoidRecentDays: 60
  };
  const canEdit = !id || isHod() || b.createdBy === S.user.username;
  S.bpDraft = JSON.parse(JSON.stringify(b));
  view().innerHTML = `
    <div class="crumbs"><a href="#/blueprints">← Blueprints</a></div>
    <div class="page-head"><div><h1>${id ? esc(b.name) : 'New blueprint'}</h1><p class="sub">${canEdit ? 'Describe the paper. The panel on the right checks the bank as you type.' : 'Only the person who made it (or the HOD) can change this blueprint. You can copy it.'}</p></div></div>
    <div class="editor">
      <form id="bpForm" class="stack" novalidate>
        <fieldset class="card" ${canEdit ? '' : 'disabled'}>
          <div class="card-head"><h2>About the paper</h2></div>
          <div class="grid2">
            <div class="field"><label for="bn">Blueprint name</label><input id="bn" name="name" maxlength="80" value="${esc(b.name)}" placeholder="OS Mid-Term (30 marks)" required></div>
            <div class="field"><label for="bs">Subject</label><select id="bs" name="subjectCode">${subjectOptions(b.subjectCode)}</select></div>
            <div class="field"><label for="be">Exam name (printed on the paper)</label><input id="be" name="examType" maxlength="60" value="${esc(b.examType)}" required></div>
            <div class="field"><label for="bd">Time allowed (minutes)</label><input id="bd" name="durationMin" type="number" min="10" max="360" value="${esc(b.durationMin)}"></div>
          </div>
          <div class="field"><span class="lbl">Units to include</span><div class="units-pick" id="unitPick"></div><span class="hint">Tick none or all for the whole syllabus.</span></div>
          <div class="field"><label for="bi">General instructions (one per line)</label><textarea id="bi" name="generalInstructions" rows="3">${esc(b.instructions.join('\n'))}</textarea></div>
        </fieldset>
        <fieldset class="card" ${canEdit ? '' : 'disabled'}>
          <div class="card-head"><h2>Sections</h2><span class="spacer"></span><button type="button" class="sm" id="addSec">Add section</button></div>
          <div id="secRows"></div>
          <p class="hint small muted">"Ask" = questions printed. "Attempt" = how many the student must answer (choice). Marks count only the attempted ones.</p>
        </fieldset>
        <fieldset class="card" ${canEdit ? '' : 'disabled'}>
          <div class="card-head"><h2>Difficulty mix</h2><span class="spacer"></span><span id="mixSum" class="chip"></span></div>
          <div class="mix-inputs">
            <div class="field E"><label for="me">Easy %</label><input id="me" name="mixE" type="number" min="0" max="100" value="${b.mix.EASY}"></div>
            <div class="field M"><label for="mm">Medium %</label><input id="mm" name="mixM" type="number" min="0" max="100" value="${b.mix.MEDIUM}"></div>
            <div class="field H"><label for="mh">Hard %</label><input id="mh" name="mixH" type="number" min="0" max="100" value="${b.mix.HARD}"></div>
          </div>
          <div id="mixBar"></div>
          <div class="field"><label for="ba">Avoid questions used in a released paper in the last ... days</label>
            <input id="ba" name="avoidRecentDays" type="number" min="0" max="730" value="${esc(b.avoidRecentDays)}"><span class="hint">0 = off. Helps stop last exam's questions coming again.</span></div>
        </fieldset>
      </form>
      <aside class="side">
        <div class="card">
          <div class="card-head"><h2>Summary</h2></div>
          <div class="row"><div><div class="big-total" id="sumMarks">0</div><div class="tiny muted">total marks</div></div>
            <div><div class="big-total" id="sumQs">0</div><div class="tiny muted">questions printed</div></div></div>
        </div>
        <div class="card">
          <div class="card-head"><h2>Bank check</h2></div>
          <div id="checkBox"><p class="muted small">Checking...</p></div>
        </div>
        ${canEdit ? `<div class="card plain"><div class="action-list">
          <button type="button" class="primary" id="bpSave">Save blueprint</button>
          <button type="button" id="bpSaveGen">Save and generate a paper</button>
          <a class="btn" href="#/blueprints">Cancel</a></div><p class="err-text small" id="bpErr"></p></div>` :
          `<div class="card plain"><div class="action-list"><a class="btn primary" href="#/generate/${esc(id)}">Generate from this</a></div></div>`}
      </aside>
    </div>`;
  const form = $('#bpForm');
  const drawUnits = () => {
    const s = subjectOf(form.subjectCode.value);
    $('#unitPick').innerHTML = s ? s.units.map(u => `<label class="check"><input type="checkbox" name="units[]" value="${u.no}" ${S.bpDraft.units.includes(u.no) ? 'checked' : ''}> ${u.no}. ${esc(u.title)}</label>`).join('') : '';
  };
  const drawSections = () => {
    $('#secRows').innerHTML = S.bpDraft.sections.map((s, i) => `
      <div class="sec-row" data-i="${i}">
        <div class="field"><label>Title</label><input name="title" maxlength="40" value="${esc(s.title)}"></div>
        <div class="field"><label>Kind of question</label><select name="type">${typeOptions(s.type)}</select></div>
        <div class="field"><label title="Questions printed">Ask</label><input name="count" type="number" min="1" max="50" value="${s.count}"></div>
        <div class="field"><label title="Questions to answer">Attempt</label><input name="attempt" type="number" min="1" max="50" value="${s.attempt}"></div>
        <div class="field"><label title="Marks for each question">Marks</label><input name="marksEach" type="number" min="1" max="50" value="${s.marksEach}"></div>
        <div class="sec-tools">
          <button type="button" class="sm ghost" data-up title="Move up" aria-label="Move up" ${i === 0 ? 'disabled' : ''}>↑</button>
          <button type="button" class="sm ghost" data-down title="Move down" aria-label="Move down" ${i === S.bpDraft.sections.length - 1 ? 'disabled' : ''}>↓</button>
          <button type="button" class="sm danger" data-rm title="Remove section" aria-label="Remove section" ${S.bpDraft.sections.length === 1 ? 'disabled' : ''}>✕</button></div>
        <div class="field wide-row"><input name="instructions" maxlength="200" value="${esc(s.instructions)}" placeholder="Instructions for this section, e.g. Answer any four questions." aria-label="Section instructions"></div>
      </div>`).join('');
    $$('#secRows .sec-row').forEach(row => {
      const i = Number(row.dataset.i);
      $('[data-up]', row).addEventListener('click', () => { const a = S.bpDraft.sections; [a[i - 1], a[i]] = [a[i], a[i - 1]]; drawSections(); changed(); });
      $('[data-down]', row).addEventListener('click', () => { const a = S.bpDraft.sections; [a[i + 1], a[i]] = [a[i], a[i + 1]]; drawSections(); changed(); });
      $('[data-rm]', row).addEventListener('click', () => { S.bpDraft.sections.splice(i, 1); drawSections(); changed(); });
    });
  };
  const readForm = () => {
    const d = S.bpDraft;
    d.name = form.name.value; d.subjectCode = form.subjectCode.value; d.examType = form.examType.value;
    d.durationMin = Number(form.durationMin.value);
    d.instructions = form.generalInstructions.value.split('\n').map(x => x.trim()).filter(Boolean);
    d.units = $$('#unitPick input:checked').map(x => Number(x.value));
    d.mix = { EASY: Number(form.mixE.value) || 0, MEDIUM: Number(form.mixM.value) || 0, HARD: Number(form.mixH.value) || 0 };
    d.avoidRecentDays = Number(form.avoidRecentDays.value) || 0;
    $$('#secRows .sec-row').forEach((row, i) => {
      const s = d.sections[i];
      s.title = $('[name=title]', row).value; s.type = $('[name=type]', row).value;
      s.count = Number($('[name=count]', row).value); s.attempt = Number($('[name=attempt]', row).value);
      s.marksEach = Number($('[name=marksEach]', row).value); s.instructions = $('[name=instructions]', row).value;
    });
    return d;
  };
  const doCheck = debounce(async () => {
    const d = readForm();
    try {
      const c = await api('POST', '/api/blueprints/check', d);
      if (!$('#checkBox')) return;
      $('#checkBox').innerHTML = `
        ${c.sections.map(s => {
          const cls = !s.enough ? 'bad' : !s.mixOk ? 'warn' : 'ok';
          return `<div class="check-row"><span class="dot ${cls}"></span><div class="grow"><b>${esc(s.title)}</b>: ${esc(s.typeLabel)}, ${s.marksEach} mark${s.marksEach === 1 ? '' : 's'}<br>
            <span class="tiny muted">need ${s.count} · bank has ${s.available} (${s.byDifficulty.EASY} E / ${s.byDifficulty.MEDIUM} M / ${s.byDifficulty.HARD} H)</span>
            ${!s.enough ? '<br><span class="tiny err-text">Not enough questions. Add more or ask fewer.</span>' : !s.mixOk ? `<br><span class="tiny warn-text">Mix wants ${s.need.EASY} E / ${s.need.MEDIUM} M / ${s.need.HARD} H; the generator will use the nearest level.</span>` : ''}</div></div>`;
        }).join('')}
        <p class="small ${c.ok ? 'good-text' : 'err-text'}">${c.ok ? `Ready. Up to ${plural(c.setsWithoutRepeats, 'set')} can be made with no repeated questions.` : 'Fix the red sections before generating.'}</p>`;
      $('#bpErr') && ($('#bpErr').textContent = '');
    } catch (e) {
      if (e instanceof StaleError || !$('#checkBox')) return;
      $('#checkBox').innerHTML = `<p class="err-text small">${esc(e.message)}</p>`;
    }
  }, 350);
  const changed = () => {
    const d = readForm();
    const total = d.sections.reduce((t, s) => t + (Number(s.attempt) || 0) * (Number(s.marksEach) || 0), 0);
    $('#sumMarks').textContent = total;
    $('#sumQs').textContent = d.sections.reduce((t, s) => t + (Number(s.count) || 0), 0);
    const sum = d.mix.EASY + d.mix.MEDIUM + d.mix.HARD;
    $('#mixSum').textContent = sum + '%';
    $('#mixSum').className = 'chip ' + (sum === 100 ? 'E' : 'bad');
    $('#mixBar').innerHTML = dbar(d.mix.EASY, d.mix.MEDIUM, d.mix.HARD);
    paintBars($('#mixBar'));
    doCheck();
  };
  drawUnits();
  drawSections();
  form.subjectCode.addEventListener('change', () => { S.bpDraft.units = []; drawUnits(); changed(); });
  form.addEventListener('input', changed);
  form.addEventListener('change', changed);
  form.addEventListener('submit', e => e.preventDefault());
  if (canEdit) {
    $('#addSec').addEventListener('click', () => {
      if (S.bpDraft.sections.length >= 8) return toast('At most 8 sections', true);
      readForm(); S.bpDraft.sections.push(blankSection(S.bpDraft.sections.length)); drawSections(); changed();
    });
    const save = async (gen, btn) => {
      $('#bpErr').textContent = '';
      btn.disabled = true;
      try {
        const d = readForm();
        const saved = id ? await api('PUT', '/api/blueprints/' + encodeURIComponent(id), d) : await api('POST', '/api/blueprints', d);
        toast('Blueprint saved');
        location.hash = gen ? '#/generate/' + saved.id : '#/blueprints';
      } catch (e) { $('#bpErr').textContent = e.message; } finally { btn.disabled = false; }
    };
    $('#bpSave').addEventListener('click', e => save(false, e.currentTarget));
    $('#bpSaveGen').addEventListener('click', e => save(true, e.currentTarget));
  }
  changed();
}

// ================================================================== generate
async function pageGenerate(bpId) {
  const list = await api('GET', '/api/blueprints');
  if (!list.length) {
    view().innerHTML = '<div class="card"><h2>No blueprints yet</h2><p>Make a blueprint first: it tells the generator what the paper looks like.</p><a class="btn primary" href="#/blueprints/new">New blueprint</a></div>';
    return;
  }
  const chosen = list.find(b => b.id === bpId) || list[0];
  view().innerHTML = `
    <div class="page-head"><div><h1>Generate a paper</h1><p class="sub">Pick a blueprint. The generator fills it from the bank with a fair mix, covering every unit.</p></div></div>
    <div class="editor">
      <form class="card" id="genForm" novalidate>
        <div class="card-head"><h2>Paper details</h2></div>
        <div class="field"><label for="gb">Blueprint</label><select id="gb" name="blueprintId">${options(list.map(b => ({ id: b.id, label: b.name + ' · ' + b.subjectCode })), chosen.id)}</select></div>
        <div class="grid3">
          <div class="field"><label for="gs">How many sets</label><select id="gs" name="sets">${options([1, 2, 3, 4].map(n => ({ id: n, label: n === 1 ? '1 (one paper)' : n + ' sets (A' + (n > 1 ? '-' + String.fromCharCode(64 + n) : '') + ')' })), 1)}</select>
            <span class="hint">Sets get different questions where the bank allows.</span></div>
          <div class="field"><label for="gd">Exam date</label><input id="gd" name="examDate" type="date" value="${todayStr(14)}"></div>
          <div class="field"><label for="gn">Seed (optional)</label><input id="gn" name="seed" type="number" min="0" placeholder="random"><span class="hint">Same seed + same bank = same paper.</span></div>
        </div>
        <div class="field"><label for="gt">Title (optional)</label><input id="gt" name="title" maxlength="100" placeholder="Left empty: exam name - subject"></div>
        <p class="err-text small" id="genErr"></p>
        <div class="row"><button type="submit" class="primary" id="genBtn">Generate</button><a class="btn" href="#/blueprints/${esc(chosen.id)}" id="genEdit">Open blueprint</a></div>
      </form>
      <aside class="side"><div class="card"><div class="card-head"><h2>Blueprint</h2></div><div id="genPreview"></div></div></aside>
    </div>`;
  const form = $('#genForm');
  const preview = () => {
    const b = list.find(x => x.id === form.blueprintId.value);
    $('#genEdit').href = '#/blueprints/' + b.id;
    const c = b.check;
    $('#genPreview').innerHTML = `
      <p><b>${esc(b.name)}</b><br><span class="small muted">${subjectName(b.subjectCode)} · ${esc(b.examType)} · ${duration(b.durationMin)}</span></p>
      <ul class="bp-sections">${b.sections.map(s => `<li><span>${esc(s.title)}: ${typeLabel(s.type)}</span><span class="nowrap">${s.attempt < s.count ? `any ${s.attempt}/${s.count}` : s.count} × ${s.marksEach} = <b>${s.marks}</b></span></li>`).join('')}
        <li><span><b>Total</b></span><span><b>${b.totalMarks} marks</b></span></li></ul>
      ${dbar(b.mix.EASY, b.mix.MEDIUM, b.mix.HARD)}<div class="tiny muted">${b.mix.EASY}% easy · ${b.mix.MEDIUM}% medium · ${b.mix.HARD}% hard</div>
      <p class="small ${c.ok ? 'good-text' : 'err-text'}">${c.ok ? `Bank ready · ${plural(c.setsWithoutRepeats, 'set')} possible with no repeats` : 'Not enough questions in the bank for some sections'}</p>`;
    paintBars($('#genPreview'));
  };
  form.blueprintId.addEventListener('change', preview);
  preview();
  form.addEventListener('submit', async e => {
    e.preventDefault();
    const btn = $('#genBtn');
    btn.disabled = true; btn.textContent = 'Generating...';
    $('#genErr').textContent = '';
    try {
      const v = formData(form);
      const body = { blueprintId: v.blueprintId, sets: Number(v.sets), examDate: v.examDate || null, title: v.title };
      if (v.seed !== '') body.seed = Number(v.seed);
      const made = await api('POST', '/api/papers/generate', body);
      toast(made.length === 1 ? 'Paper ready' : made.length + ' sets ready');
      location.hash = '#/papers/' + made[0].id;
    } catch (err) { $('#genErr').textContent = err.message; } finally { btn.disabled = false; btn.textContent = 'Generate'; }
  });
}

// ================================================================== papers list
async function pagePapers() {
  const f = S.papersFilter;
  const all = await api('GET', '/api/papers');
  const counts = { '': all.length };
  for (const p of all) counts[p.status] = (counts[p.status] || 0) + 1;
  const tabs = [{ id: '', label: 'All' }, ...S.meta.statuses];
  view().innerHTML = `
    <div class="page-head"><div><h1>Papers</h1><p class="sub">Every generated paper. Sets made together are grouped.</p></div>
      <div class="actions"><a class="btn primary" href="#/generate">Generate paper</a></div></div>
    <div class="tabs" role="tablist">${tabs.map(t => `<button type="button" data-st="${esc(t.id)}" class="${f.status === t.id ? 'on' : ''}">${esc(t.label)} <span class="n">${counts[t.id] || 0}</span></button>`).join('')}</div>
    <form class="toolbar" id="pFilters">
      <select name="subject" aria-label="Subject">${subjectOptions(f.subject, 'All subjects')}</select>
      <input type="search" name="q" placeholder="Search title or id" value="${esc(f.q)}" aria-label="Search papers">
    </form>
    <div class="card plain"><div class="table-wrap" id="pTable"></div></div>`;
  const draw = () => {
    const q = f.q.toLowerCase();
    const rows = all.filter(p => (!f.status || p.status === f.status) && (!f.subject || p.subjectCode === f.subject) && (!q || (p.title + ' ' + p.id).toLowerCase().includes(q)));
    let lastGroup = null;
    $('#pTable').innerHTML = rows.length ? `<table><thead><tr><th>Paper</th><th>Set</th><th>Subject</th><th>Exam date</th><th class="num">Marks</th><th>Status</th><th>Made by</th></tr></thead><tbody>
      ${rows.map(p => { const start = p.groupId !== lastGroup; lastGroup = p.groupId; return `
        <tr class="click ${start ? 'group-start' : ''}" data-id="${esc(p.id)}"><td><a href="#/papers/${esc(p.id)}">${esc(p.title)}</a><div class="tiny muted">${esc(p.id)} · ${p.questionCount} questions${p.warningCount ? ` · <span class="warn-text">${plural(p.warningCount, 'note')}</span>` : ''}</div></td>
        <td>${esc(p.setLabel)}</td><td><span class="chip pen">${esc(p.subjectCode)}</span></td><td class="nowrap">${dateText(p.examDate)}</td>
        <td class="num">${p.totalMarks}</td><td>${statusChip(p.status, p.statusLabel)}</td><td>${userName(p.createdBy)}</td></tr>`; }).join('')}
      </tbody></table>` : '<p class="empty">No papers match.</p>';
    $$('#pTable tr.click').forEach(tr => tr.addEventListener('click', e => { if (e.target.tagName !== 'A') location.hash = '#/papers/' + tr.dataset.id; }));
  };
  $$('.tabs button').forEach(b => b.addEventListener('click', () => { f.status = b.dataset.st; $$('.tabs button').forEach(x => x.classList.toggle('on', x === b)); draw(); }));
  const pf = $('#pFilters');
  pf.addEventListener('change', () => { f.subject = pf.subject.value; draw(); });
  pf.q.addEventListener('input', () => { f.q = pf.q.value; draw(); });
  pf.addEventListener('submit', e => e.preventDefault());
  draw();
}

// ================================================================== one paper
async function pagePaper(id) {
  const [p, history] = await Promise.all([api('GET', '/api/papers/' + encodeURIComponent(id)), api('GET', '/api/events?ref=' + encodeURIComponent('paper:' + id) + '&limit=20')]);
  const mine = p.createdBy === S.user.username;
  const canEdit = p.status === 'DRAFT' && (mine || isHod());
  const a = p.analysis;
  const subj = p.subject || { name: p.subjectCode, units: [] };
  let n = 0;
  const sheet = `
    <article class="sheet ${canEdit ? 'editing' : ''} ${S.paperView.key ? 'show-key' : ''} ${S.paperView.tags ? 'show-tags' : ''}" id="sheet">
      <span class="roll">Roll No. ____________</span>
      ${p.setLabel ? `<span class="set-tag">SET ${esc(p.setLabel)}</span>` : ''}
      <header>
        <p class="inst">${esc(p.institute || S.meta.settings.institute)}</p>
        ${p.department ? `<p class="dept">${esc(p.department)}</p>` : ''}
        <p class="exam">${esc(p.examType)}</p>
        <p class="subj">${esc(p.subjectCode)} : ${esc(subj.name)}${subj.course ? ' · ' + esc(subj.course) + ', Semester ' + esc(subj.semester) : ''}</p>
        <p class="key-title">ANSWER KEY - FOR EXAMINERS ONLY</p>
        <div class="meta"><span>Time: ${duration(p.durationMin)}</span>${p.examDate ? `<span>Date: ${dateText(p.examDate)}</span>` : ''}<span>Maximum Marks: ${p.totalMarks}</span></div>
      </header>
      ${p.instructions.length ? `<div class="instructions"><b>General instructions:</b><ol>${p.instructions.map(i => `<li>${esc(i)}</li>`).join('')}</ol></div>` : ''}
      ${p.sections.map((s, si) => `
        <section class="sec">
          <div class="sec-title">${esc(s.title)}<span>(${s.attempt} × ${s.marksEach} = ${s.marks} marks)</span></div>
          ${s.instructions ? `<p class="sec-ins">${esc(s.instructions)}</p>` : ''}
          ${s.items.map((it, ii) => {
            n++;
            const longOpts = it.options.some(o => o.length > 32);
            return `<div class="q" data-s="${si}" data-i="${ii}">
              <span class="n">Q${n}.</span><span class="qt">${esc(it.text)}</span><span class="qm">[${it.marks}]</span>
              ${it.type === 'MCQ' ? `<ol class="opts ${longOpts ? 'long' : ''}">${it.options.map((o, k) => `<li>(${String.fromCharCode(97 + k)}) ${esc(o)}</li>`).join('')}</ol>` : ''}
              <div class="ans">${it.type === 'MCQ' ? `<b>(${esc(it.answerLetter)})</b> ${esc(it.answer)}` : it.answer ? esc(it.answer) : '<i>No model answer in the bank.</i>'}</div>
              <div class="tags"><span class="chip">${esc(it.questionId)}</span><span class="chip">Unit ${it.unit}</span><span class="chip ${esc(it.difficulty)}">${diffLabel(it.difficulty)}</span><span class="chip">${esc(bloomOf(it.bloom).level)} ${esc(bloomOf(it.bloom).label)}</span></div>
              ${canEdit ? `<div class="tools no-print"><button type="button" data-act="swap">Swap (auto)</button><button type="button" data-act="pick">Choose…</button>
                <button type="button" data-act="up" ${ii === 0 ? 'disabled' : ''}>↑</button><button type="button" data-act="down" ${ii === s.items.length - 1 ? 'disabled' : ''}>↓</button></div>` : ''}
            </div>`;
          }).join('')}
          ${s.items.length === 0 ? '<p class="missing">No questions found for this section. Add questions to the bank and make the paper again.</p>' : ''}
        </section>`).join('')}
      <p class="end">*** END OF PAPER ***</p>
    </article>`;

  const actions = [];
  if (canEdit) {
    actions.push('<button type="button" class="primary" data-a="submit">Send for review</button>');
    actions.push('<button type="button" data-a="header">Edit title, date &amp; instructions</button>');
    actions.push('<button type="button" data-a="regen">Make again (new random pick)</button>');
  }
  if (p.status === 'SUBMITTED' && isHod()) {
    actions.push('<button type="button" class="primary" data-a="approve">Approve</button>');
    actions.push('<button type="button" data-a="back">Send back with a note</button>');
  }
  if (p.status === 'APPROVED' && isHod()) {
    actions.push('<button type="button" class="primary" data-a="release">Release for the exam</button>');
    actions.push('<button type="button" data-a="back">Send back with a note</button>');
  }
  actions.push('<button type="button" data-a="print">Print paper</button>');
  actions.push('<button type="button" data-a="printkey">Print answer key</button>');
  actions.push('<button type="button" data-a="txt">Download as text</button>');
  actions.push('<button type="button" data-a="txtkey">Download answer key (text)</button>');
  if ((p.status === 'DRAFT' && (mine || isHod())) || (['SUBMITTED', 'APPROVED'].includes(p.status) && isHod())) actions.push('<button type="button" class="danger" data-a="delete">Delete paper</button>');

  const statusHelp = {
    DRAFT: canEdit ? 'Check the paper, swap any question you do not like, then send it for review.' : 'The teacher is still working on this paper.',
    SUBMITTED: isHod() ? 'Read the paper and the answer key, then approve it or send it back.' : 'Waiting for the HOD to review.',
    APPROVED: isHod() ? 'Approved. Release it on exam day: it becomes locked and its questions are marked as used.' : 'Approved by ' + userName(p.approvedBy) + '.',
    RELEASED: 'Released on ' + dateText(p.releasedAt) + '. Locked as a record.'
  }[p.status];

  const ring = a.balanceScore;
  const C = 2 * Math.PI * 30;
  view().innerHTML = `
    <div class="crumbs"><a href="#/papers">← Papers</a></div>
    <div class="page-head">
      <div><h1>${esc(p.title)}</h1>
        <p class="sub">${esc(p.id)} · ${subjectName(p.subjectCode)} · made by ${userName(p.createdBy)} on ${dateText(p.createdAt)}${p.blueprintName ? ' from “' + esc(p.blueprintName) + '”' : ''} · seed ${esc(p.seed)}</p></div>
      <div class="actions">${statusChip(p.status, p.statusLabel)}</div>
    </div>
    ${p.reviewNote && p.status === 'DRAFT' ? `<div class="note-box bad"><b>Sent back by ${userName(p.reviewedBy)}:</b> ${esc(p.reviewNote)}</div><p></p>` : ''}
    ${p.otherSets.length ? `<div class="sets"><span class="small muted">Other sets of this exam:</span>${p.otherSets.map(o => `<a href="#/papers/${esc(o.id)}" class="chip pen">Set ${esc(o.setLabel)} · ${esc(o.statusLabel)} · ${o.shared ? plural(o.shared, 'shared question') : 'no shared questions'}</a>`).join('')}</div><p></p>` : ''}
    <div class="paper-layout">
      <div>
        <div class="view-toggles">
          <label class="check"><input type="checkbox" id="tKey" ${S.paperView.key ? 'checked' : ''}> Show answer key</label>
          <label class="check"><input type="checkbox" id="tTags" ${S.paperView.tags ? 'checked' : ''}> Show unit, difficulty &amp; Bloom tags</label>
          ${canEdit ? '<span class="small muted">Point at a question to swap or move it.</span>' : ''}
        </div>
        ${sheet}
      </div>
      <aside class="paper-side">
        <div class="card"><div class="card-head"><h2>Next step</h2></div><p class="small">${statusHelp}</p><div class="action-list">${actions.join('')}</div></div>
        <div class="card">
          <div class="card-head"><h2>Balance check</h2></div>
          <div class="score">
            <svg class="score-ring" viewBox="0 0 70 70" aria-label="Balance score ${ring} out of 100"><circle class="bg" cx="35" cy="35" r="30"/><circle class="fg" cx="35" cy="35" r="30" data-dash="${C * ring / 100} ${C}"/><text x="35" y="36">${ring}</text></svg>
            <div class="small">out of 100<br><span class="muted">mix ${a.balanceParts.mix}/50 · units ${a.balanceParts.units}/30 · thinking ${a.balanceParts.thinking}/20</span></div>
          </div>
          <h3>Difficulty: planned vs this paper</h3>
          <div class="hbars">${a.difficulty.map(d => `<div class="hbar"><span class="name">${esc(d.label)} <span class="tiny muted">(${d.count})</span></span>
            <div class="track"><div class="fill ${d.id[0]}" data-w="${d.pct}"></div><div class="target" data-l="${d.target}" title="Planned ${d.target}%"></div></div><span class="val">${d.pct}%</span></div>`).join('')}</div>
          <p class="tiny muted">The black line is the plan from the blueprint.</p>
          <h3>Marks by unit</h3>
          <div class="hbars">${a.units.map(u => `<div class="hbar"><span class="name" title="${esc(u.title)}">${u.unit}. ${esc(u.title)}</span>
            <div class="track"><div class="fill" data-w="${a.printedMarks ? u.marks / a.printedMarks * 100 : 0}"></div></div><span class="val">${u.marks}</span></div>`).join('')}</div>
          ${a.missingUnits.length ? `<p class="small warn-text">Not covered: ${a.missingUnits.map(esc).join(', ')}</p>` : '<p class="small good-text">Every unit has at least one question.</p>'}
          <h3>Thinking level (Bloom)</h3>
          <div class="hbars">${a.bloom.filter(b => b.count).map(b => `<div class="hbar"><span class="name">${esc(b.level)} ${esc(b.label)}</span>
            <div class="track"><div class="fill" data-w="${a.printedMarks ? b.marks / a.printedMarks * 100 : 0}"></div></div><span class="val">${b.marks}</span></div>`).join('')}</div>
          <p class="tiny muted">${a.higherOrderPct}% of marks test applying, analysing or higher (L3+). Marks shown are for all printed questions (${a.printedMarks}).</p>
        </div>
        ${p.warnings.length ? `<div class="card"><div class="card-head"><h2>Generator notes</h2></div><ul class="warn-list">${p.warnings.map(w => `<li>${esc(w)}</li>`).join('')}</ul></div>` : ''}
        <div class="card"><div class="card-head"><h2>History</h2></div>${activityList(history)}</div>
      </aside>
    </div>`;

  $('#tKey').addEventListener('change', e => { S.paperView.key = e.target.checked; $('#sheet').classList.toggle('show-key', S.paperView.key); });
  $('#tTags').addEventListener('change', e => { S.paperView.tags = e.target.checked; $('#sheet').classList.toggle('show-tags', S.paperView.tags); });
  const reload = () => pagePaper(id).then(() => paintBars(view()));
  if (canEdit) {
    $$('#sheet .q').forEach(q => {
      const section = Number(q.dataset.s), item = Number(q.dataset.i);
      $$('[data-act]', q).forEach(b => b.addEventListener('click', () => {
        const act = b.dataset.act;
        if (act === 'pick') return pickQuestion(p, section, item, reload);
        run(b, async () => {
          if (act === 'swap') { await api('POST', `/api/papers/${p.id}/swap`, { section, item }); toast('Question swapped'); }
          else await api('POST', `/api/papers/${p.id}/move`, { section, item, dir: act === 'up' ? -1 : 1 });
          await reload();
        });
      }));
    });
  }
  $$('.action-list [data-a]').forEach(b => b.addEventListener('click', () => paperAction(b, b.dataset.a, p, reload)));
}

async function paperAction(btn, a, p, reload) {
  const post = (path, body) => api('POST', `/api/papers/${p.id}/${path}`, body);
  switch (a) {
    case 'submit':
      if (!await confirmBox('Send for review', 'Send this paper to the HOD? You cannot change it while it is being reviewed.', 'Send')) return;
      return run(btn, async () => { await post('submit'); toast('Sent for review'); await reload(); });
    case 'approve':
      return run(btn, async () => { await post('approve'); toast('Approved'); await reload(); });
    case 'release':
      if (!await confirmBox('Release paper', 'Release this paper for the exam? It will be locked, and its questions will be marked as used today.', 'Release')) return;
      return run(btn, async () => { await post('release'); toast('Released'); await reload(); });
    case 'regen':
      if (!await confirmBox('Make again', 'Replace every question with a new random pick from the same blueprint? Your swaps will be lost.', 'Make again')) return;
      return run(btn, async () => { await post('regenerate'); toast('New paper made'); await reload(); });
    case 'back':
      openModal({
        title: 'Send back to the teacher',
        body: '<div class="field"><label for="bnote">What should be changed?</label><textarea id="bnote" name="note" rows="4" maxlength="500" required placeholder="e.g. Q14 is repeated from last year - please swap it."></textarea></div>',
        buttons: [{ label: 'Cancel', value: 'cancel' }, { label: 'Send back', cls: 'primary', value: 'ok' }],
        onOk: async form => { await post('send-back', { note: form.note.value }); closeModal(); toast('Sent back'); await reload(); return true; }
      });
      return;
    case 'header': return headerForm(p, reload);
    case 'print': case 'printkey':
      document.body.classList.toggle('print-key', a === 'printkey');
      setTimeout(() => { window.print(); setTimeout(() => document.body.classList.remove('print-key'), 500); }, 50);
      return;
    case 'txt': return run(btn, () => download(`/api/papers/${p.id}/export.txt`, p.id + '.txt'));
    case 'txtkey': return run(btn, () => download(`/api/papers/${p.id}/export.txt?key=1`, p.id + '-answer-key.txt'));
    case 'delete':
      if (!await confirmBox('Delete paper', `Delete “${p.title}”? This cannot be undone.`, 'Delete', true)) return;
      return run(btn, async () => { await api('DELETE', '/api/papers/' + p.id); toast('Deleted'); location.hash = '#/papers'; });
  }
}

function headerForm(p, reload) {
  openModal({
    title: 'Edit paper details', wide: true,
    body: `
      <div class="field"><label for="ht">Title (for your lists)</label><input id="ht" name="title" maxlength="100" value="${esc(p.title)}" required></div>
      <div class="grid3">
        <div class="field"><label for="hd">Exam date</label><input id="hd" name="examDate" type="date" value="${esc(p.examDate || '')}"></div>
        <div class="field"><label for="hm">Time (minutes)</label><input id="hm" name="durationMin" type="number" min="10" max="360" value="${p.durationMin}"></div>
        <div class="field"><label for="hi">Institute (printed)</label><input id="hi" name="institute" maxlength="100" value="${esc(p.institute || '')}"></div>
      </div>
      <div class="field"><label for="hn">General instructions (one per line)</label><textarea id="hn" name="instructions" rows="4">${esc(p.instructions.join('\n'))}</textarea></div>
      ${p.sections.map((s, i) => `<div class="field"><label for="hs${i}">${esc(s.title)} instructions</label><input id="hs${i}" name="sec${i}" maxlength="200" value="${esc(s.instructions || '')}"></div>`).join('')}`,
    onOk: async form => {
      await api('PUT', '/api/papers/' + p.id, {
        title: form.title.value, examDate: form.examDate.value || null, durationMin: Number(form.durationMin.value), institute: form.institute.value,
        instructions: form.instructions.value.split('\n'), sectionInstructions: p.sections.map((s, i) => form['sec' + i].value)
      });
      closeModal(); toast('Saved'); await reload(); return true;
    }
  });
}

async function pickQuestion(p, section, item, reload) {
  const list = await api('GET', `/api/papers/${p.id}/candidates?section=${section}&item=${item}`);
  const cur = p.sections[section].items[item];
  openModal({
    title: 'Choose a replacement', wide: true,
    body: `<p class="small">Replacing: <b>${esc(cur.text)}</b> <span class="chip ${esc(cur.difficulty)}">${diffLabel(cur.difficulty)}</span> <span class="chip">Unit ${cur.unit}</span></p>
      <p class="small muted">Same kind and marks only. Same difficulty and unit are listed first.</p>
      ${list.length ? list.map(c => `<div class="cand"><div class="grow"><div class="row"><span class="qid">${esc(c.id)}</span>${diffChip(c.difficulty)}<span class="chip">Unit ${c.unit}</span>${bloomChip(c.bloom)}
        ${c.timesUsed ? `<span class="chip">used ${c.timesUsed}×</span>` : ''}${c.inOtherSet ? '<span class="chip warn">in another set</span>' : ''}</div><p>${esc(c.text)}</p></div>
        <button type="button" class="sm primary" data-pick="${esc(c.id)}">Use this</button></div>`).join('') : '<p class="empty">No other questions of this kind and marks in the bank.</p>'}`,
    buttons: [{ label: 'Close', value: 'cancel' }],
    onOpen: () => $$('[data-pick]').forEach(b => b.addEventListener('click', () => run(b, async () => {
      await api('POST', `/api/papers/${p.id}/swap`, { section, item, questionId: b.dataset.pick });
      closeModal(); toast('Question replaced'); await reload();
    })))
  });
}

// ================================================================== subjects
async function pageSubjects() {
  await loadMeta();
  const subs = S.meta.subjects;
  view().innerHTML = `
    <div class="page-head"><div><h1>Subjects &amp; units</h1><p class="sub">Every question belongs to a subject and a unit (chapter) of its syllabus.</p></div>
      ${isHod() ? '<div class="actions"><button type="button" class="primary" id="sAdd">Add subject</button></div>' : ''}</div>
    <div class="bp-grid">${subs.map(s => `
      <article class="card">
        <div class="card-head"><h3>${esc(s.code)} · ${esc(s.name)}</h3></div>
        <div class="bp-meta"><span class="chip">${esc(s.course || '-')}</span><span class="chip">Semester ${s.semester}</span><span class="chip pen">${plural(s.questionCount, 'question')}</span></div>
        <ol class="small">${s.units.map(u => `<li>${esc(u.title)}</li>`).join('')}</ol>
        <div class="bp-foot"><a class="btn sm" href="#/bank" data-bank="${esc(s.code)}">Open in bank</a>
          ${isHod() ? `<button type="button" class="sm" data-edit="${esc(s.code)}">Edit</button>${s.questionCount === 0 ? `<button type="button" class="sm danger" data-del="${esc(s.code)}">Delete</button>` : ''}` : ''}</div>
      </article>`).join('')}</div>`;
  $$('[data-bank]').forEach(a => a.addEventListener('click', () => { S.bank.subject = a.dataset.bank; S.bank.unit = ''; }));
  if (!isHod()) return;
  $('#sAdd').addEventListener('click', () => subjectForm(null));
  $$('[data-edit]').forEach(b => b.addEventListener('click', () => subjectForm(subs.find(s => s.code === b.dataset.edit))));
  $$('[data-del]').forEach(b => b.addEventListener('click', async () => {
    if (!await confirmBox('Delete subject', 'Delete ' + b.dataset.del + '?', 'Delete', true)) return;
    run(b, async () => { await api('DELETE', '/api/subjects/' + encodeURIComponent(b.dataset.del)); toast('Deleted'); route(); });
  }));
}

function subjectForm(s) {
  openModal({
    title: s ? 'Edit ' + s.code : 'Add a subject',
    body: `
      <div class="grid2">
        <div class="field"><label for="sc">Subject code</label><input id="sc" name="code" maxlength="12" value="${esc(s ? s.code : '')}" ${s ? 'disabled' : ''} required placeholder="CSIT135"></div>
        <div class="field"><label for="ss">Semester</label><input id="ss" name="semester" type="number" min="1" max="12" value="${esc(s ? s.semester : 1)}"></div>
      </div>
      <div class="field"><label for="sn">Subject name</label><input id="sn" name="name" maxlength="80" value="${esc(s ? s.name : '')}" required></div>
      <div class="field"><label for="so">Course</label><input id="so" name="course" maxlength="40" value="${esc(s ? s.course : 'B.Sc. IT')}"></div>
      <div class="field"><label for="su">Units, one per line, in order</label><textarea id="su" name="units" rows="6" required>${esc(s ? s.units.map(u => u.title).join('\n') : '')}</textarea>
        <span class="hint">A unit that already has questions cannot be removed.</span></div>`,
    onOk: async form => {
      const body = { code: form.code.value, name: form.name.value, course: form.course.value, semester: Number(form.semester.value),
        units: form.units.value.split('\n').map(x => x.trim()).filter(Boolean) };
      if (s) await api('PUT', '/api/subjects/' + encodeURIComponent(s.code), body); else await api('POST', '/api/subjects', body);
      closeModal(); toast('Saved'); await loadMeta(); route(); return true;
    }
  });
}

// ================================================================== settings
async function pageSettings() {
  if (!isHod()) { location.hash = '#/dashboard'; return; }
  const st = await api('GET', '/api/settings');
  view().innerHTML = `
    <div class="page-head"><div><h1>Paper settings</h1><p class="sub">Printed on top of every new paper, and how strict the duplicate check is.</p></div></div>
    <form class="card" id="setForm" novalidate>
      <div class="field"><label for="xi">Institute name</label><input id="xi" name="institute" maxlength="100" value="${esc(st.institute)}" required></div>
      <div class="field"><label for="xd">Department / school</label><input id="xd" name="department" maxlength="100" value="${esc(st.department)}"></div>
      <div class="field"><label for="xt">Duplicate limit: questions this alike or more count as near-duplicates (%)</label>
        <input id="xt" name="duplicateThreshold" type="number" min="40" max="95" value="${Math.round(st.duplicateThreshold * 100)}">
        <span class="hint">Alike = shared important words ÷ all important words (Jaccard similarity). 60% is a good start.</span></div>
      <p class="err-text small" id="setErr"></p>
      <button type="submit" class="primary">Save settings</button>
    </form>`;
  $('#setForm').addEventListener('submit', async e => {
    e.preventDefault();
    const f = e.target, btn = $('button[type=submit]', f);
    btn.disabled = true; $('#setErr').textContent = '';
    try {
      await api('PUT', '/api/settings', { institute: f.institute.value, department: f.department.value, duplicateThreshold: Number(f.duplicateThreshold.value) / 100 });
      await loadMeta(); $('#railInstitute').textContent = S.meta.settings.institute; toast('Settings saved');
    } catch (err) { $('#setErr').textContent = err.message; } finally { btn.disabled = false; }
  });
}

// ================================================================== team
async function pageTeam() {
  if (!isAdmin()) { location.hash = '#/dashboard'; return; }
  const users = await api('GET', '/api/users');
  view().innerHTML = `
    <div class="page-head"><div><h1>Team</h1><p class="sub">Faculty add questions and make papers. The HOD reviews, approves and releases. The admin manages people.</p></div>
      <div class="actions"><button type="button" class="primary" id="uAdd">Add person</button></div></div>
    <div class="card plain"><div class="table-wrap"><table><thead><tr><th>Name</th><th>Username</th><th>Department</th><th>Role</th><th>Status</th><th></th></tr></thead><tbody>
      ${users.map(u => `<tr><td><b>${esc(u.displayName)}</b></td><td class="mono">${esc(u.username)}</td><td>${esc(u.department || '-')}</td>
        <td>${esc(u.roleLabel)}</td><td>${u.active ? '<span class="chip E">Active</span>' : '<span class="chip bad">Turned off</span>'}</td>
        <td class="right"><button type="button" class="sm" data-u="${esc(u.username)}">Edit</button></td></tr>`).join('')}
    </tbody></table></div></div>`;
  $('#uAdd').addEventListener('click', () => userForm(null));
  $$('[data-u]').forEach(b => b.addEventListener('click', () => userForm(users.find(u => u.username === b.dataset.u))));
}

function userForm(u) {
  openModal({
    title: u ? 'Edit ' + u.displayName : 'Add a person',
    body: `
      <div class="grid2">
        <div class="field"><label for="uu">Username</label><input id="uu" name="username" maxlength="30" value="${esc(u ? u.username : '')}" ${u ? 'disabled' : ''} required autocomplete="off"></div>
        <div class="field"><label for="ur">Role</label><select id="ur" name="role">${options(S.meta.roles, u ? u.role : 'FACULTY')}</select></div>
      </div>
      <div class="field"><label for="un">Full name</label><input id="un" name="displayName" maxlength="60" value="${esc(u ? u.displayName : '')}" required></div>
      <div class="field"><label for="ud">Department</label><input id="ud" name="department" maxlength="80" value="${esc(u ? u.department : '')}"></div>
      <div class="field"><label for="up">${u ? 'New password (leave empty to keep)' : 'Password'}</label><input id="up" name="password" type="password" autocomplete="new-password" ${u ? '' : 'required'}>
        <span class="hint">At least 8 characters with a letter and a number.</span></div>
      ${u ? `<label class="check"><input type="checkbox" name="active" ${u.active ? 'checked' : ''}> Account is active (can sign in)</label>` : ''}`,
    onOk: async form => {
      const v = formData(form);
      if (u) {
        const body = { displayName: v.displayName, department: v.department, role: v.role, active: v.active };
        if (v.password) body.password = v.password;
        await api('PUT', '/api/users/' + encodeURIComponent(u.username), body);
      } else await api('POST', '/api/users', v);
      closeModal(); toast('Saved'); await loadMeta(); route(); return true;
    }
  });
}

function passwordForm() {
  openModal({
    title: 'Change your password',
    body: `
      <div class="field"><label for="pc">Current password</label><input id="pc" name="current" type="password" autocomplete="current-password" required></div>
      <div class="field"><label for="pn">New password</label><input id="pn" name="next" type="password" autocomplete="new-password" required>
        <span class="hint">At least 8 characters with a letter and a number.</span></div>
      <div class="field"><label for="pr">New password again</label><input id="pr" name="again" type="password" autocomplete="new-password" required></div>`,
    onOk: async form => {
      if (form.next.value !== form.again.value) throw new Error('The two new passwords are not the same');
      await api('POST', '/api/auth/password', { current: form.current.value, next: form.next.value });
      closeModal(); toast('Password changed'); return true;
    }
  });
}

// ================================================================== start
function wire() {
  $('#loginForm').addEventListener('submit', signIn);
  $$('[data-demo]').forEach(b => b.addEventListener('click', () => {
    const [u, p] = b.dataset.demo.split(':');
    $('#lu').value = u; $('#lp').value = p; $('#lp').focus();
  }));
  $$('[data-theme-toggle]').forEach(b => b.addEventListener('click', toggleTheme));
  matchMedia('(prefers-color-scheme: dark)').addEventListener('change', syncThemeButtons);
  syncThemeButtons();
  $('#logoutBtn').addEventListener('click', () => signOut(false));
  $('#who').addEventListener('click', passwordForm);
  $('#menuBtn').addEventListener('click', openRail);
  $('#railShade').addEventListener('click', closeRail);
  $('#searchForm').addEventListener('submit', e => {
    e.preventDefault();
    S.bank = { subject: '', unit: '', type: '', difficulty: '', bloom: '', q: $('#globalSearch').value.trim(), archived: 'all', sort: 'id', limit: 30 };
    if (location.hash === '#/bank') route(); else location.hash = '#/bank';
  });
  $('#modalClose').addEventListener('click', closeModal);
  $('#modalForm').addEventListener('submit', async e => {
    e.preventDefault();
    if (!modalSubmit) { closeModal(); return; }
    const btn = $('#modalFoot button[type=submit]');
    $('#modalErr').textContent = '';
    if (btn) btn.disabled = true;
    try {
      const keep = await modalSubmit(e.target);
      if (keep === true && $('#modal').open && !$('#modalErr').textContent) { /* the handler closed or reopened it */ }
    } catch (err) {
      if (!(err instanceof StaleError)) $('#modalErr').textContent = err.message;
    } finally { if (btn) btn.disabled = false; }
  });
  window.addEventListener('hashchange', route);
  window.addEventListener('afterprint', () => document.body.classList.remove('print-key'));
}

wire();
S.token = store.get('qpg-token');
if (S.token) boot(); else $('#login').classList.remove('hidden');
