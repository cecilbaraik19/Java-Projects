/* =====================================================================
   Sambandh CRM – the browser side (plain JavaScript, no framework).

   How it works:
   - The page has one <div id="app">. Each screen ("view") is a function that asks the Java server for
     JSON (fetch) and builds HTML text. All text from the server goes through esc() first, so a customer
     called <script> is shown as text and never runs.
   - The address after # decides the screen: #/customers, #/c/C-1001, #/followups ... (router()).
   - Clicks and form submits are handled in one place per screen (event delegation), because the
     Content-Security-Policy does not allow onclick="..." in the HTML.
   ===================================================================== */
'use strict';
(function () {
  // ------------------------------------------------------------------ small helpers
  const $ = (sel, root) => (root || document).querySelector(sel);
  const $$ = (sel, root) => Array.from((root || document).querySelectorAll(sel));
  const ESC = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' };
  const esc = (v) => String(v == null ? '' : v).replace(/[&<>"']/g, (c) => ESC[c]);
  const state = { me: null, meta: null, staff: [], counts: {}, list: null, selected: new Set(), detailTab: 'log' };

  const inr = new Intl.NumberFormat('en-IN', { maximumFractionDigits: 0 });
  const money = (paise) => '₹' + inr.format(Math.round((paise || 0) / 100));
  /** ₹3.16 Cr / ₹4.5 L / ₹12,000 – short Indian style for counters */
  function moneyShort(paise) {
    const r = (paise || 0) / 100;
    if (r >= 1e7) return '₹' + (r / 1e7).toFixed(2) + ' Cr';
    if (r >= 1e5) return '₹' + (r / 1e5).toFixed(1) + ' L';
    return '₹' + inr.format(Math.round(r));
  }
  const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
  function fmtDate(v) {
    if (!v) return '—';
    const d = typeof v === 'number' ? new Date(v) : new Date(v + 'T00:00:00');
    return d.getDate() + ' ' + MONTHS[d.getMonth()] + ' ' + d.getFullYear();
  }
  function fmtDateTime(ms) {
    const d = new Date(ms);
    return fmtDate(ms) + ', ' + String(d.getHours()).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0');
  }
  function ago(ms) {
    if (!ms) return 'never';
    const s = (Date.now() - ms) / 1000;
    if (s < 90) return 'just now';
    if (s < 3600) return Math.round(s / 60) + ' min ago';
    if (s < 86400) return Math.round(s / 3600) + ' h ago';
    const days = Math.round(s / 86400);
    if (days === 1) return 'yesterday';
    if (days < 60) return days + ' days ago';
    return Math.round(days / 30) + ' months ago';
  }
  /** +919835123456 -> +91 98351 23456 */
  const phone = (p) => (/^\+91\d{10}$/.test(p || '') ? '+91 ' + p.slice(3, 8) + ' ' + p.slice(8) : (p || ''));
  const todayStr = () => (state.meta && state.meta.today) || new Date().toISOString().slice(0, 10);
  function localNow() {
    const d = new Date(); d.setMinutes(d.getMinutes() - d.getTimezoneOffset());
    return d.toISOString().slice(0, 16);
  }
  const isManager = () => state.me && (state.me.role === 'MANAGER' || state.me.role === 'ADMIN');
  const isAdmin = () => state.me && state.me.role === 'ADMIN';
  const lampFor = (band) => (band === 'healthy' ? 'g' : band === 'watch' ? 'a' : 'r');
  const stageTag = (id, label) => `<span class="stage-tag sc-${esc(id)}">${esc(label || id)}</span>`;
  const stageLabel = (id) => ((state.meta && state.meta.stages.find((s) => s.id === id)) || { label: id }).label;
  const nice = (s) => String(s || '').toLowerCase().replace(/_/g, ' ').replace(/^./, (c) => c.toUpperCase());

  /** Odometer counter: each digit on its own little dark tile. */
  function odo(text) {
    return '<span class="odometer" aria-hidden="true">' + String(text).split('').map((ch) =>
      /[0-9]/.test(ch) ? `<span>${ch}</span>` : `<span class="sep">${esc(ch)}</span>`).join('') + '</span>' +
      `<span class="sr">${esc(text)}</span>`;
  }

  /** Bars get their width from data-w after the HTML is in the page (inline style="" is blocked by the CSP). */
  function applyWidths(root) {
    $$('[data-w]', root).forEach((el) => { el.style.width = Math.max(0, Math.min(100, Number(el.dataset.w))) + '%'; });
  }

  const ICON = {
    CALL: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M5 4h4l2 5-2.5 1.5a11 11 0 0 0 5 5L15 13l5 2v4a2 2 0 0 1-2 2A16 16 0 0 1 3 6a2 2 0 0 1 2-2"/></svg>',
    EMAIL: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="5" width="18" height="14" rx="2"/><path d="m3 7 9 6 9-6"/></svg>',
    MEETING: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="9" cy="8" r="3"/><circle cx="17" cy="9" r="2.5"/><path d="M3 19c0-3.3 2.7-5 6-5s6 1.7 6 5M15 14.5c2.8 0 5 1.3 5 4.5"/></svg>',
    WHATSAPP: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M4 20l1.3-3.9A8 8 0 1 1 8 19z"/><path d="M9 9.5c.5 2 2 3.6 4.5 4.5"/></svg>',
    NOTE: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M5 4h10l4 4v12H5z"/><path d="M14 4v5h5M8 13h8M8 17h5"/></svg>',
    search: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><circle cx="11" cy="11" r="7"/><path d="m20 20-3.5-3.5"/></svg>',
    menu: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M4 7h16M4 12h16M4 17h16"/></svg>',
    plus: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round"><path d="M12 5v14M5 12h14"/></svg>',
    download: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 4v11m-5-5 5 5 5-5M5 20h14"/></svg>'
  };
  const BRAND = '<svg class="brand-mark" viewBox="0 0 64 64" aria-hidden="true"><circle cx="20" cy="22" r="10" fill="#1c120b" stroke="#d6b066" stroke-width="4"/><circle cx="44" cy="44" r="10" fill="#1c120b" stroke="#d6b066" stroke-width="4"/><path d="M20 22 C 20 52, 44 12, 44 44" fill="none" stroke="#c63a28" stroke-width="6" stroke-linecap="round"/><circle cx="48" cy="16" r="7" fill="#f0b43a"/></svg>';

  // ------------------------------------------------------------------ talking to the server
  async function api(method, path, body) {
    const opts = { method, headers: { 'Accept': 'application/json' }, credentials: 'same-origin' };
    if (body !== undefined) {
      opts.headers['Content-Type'] = 'application/json';
      opts.headers['X-Sambandh'] = '1';           // the server's CSRF check needs this header
      opts.body = JSON.stringify(body);
    } else if (method !== 'GET') {
      opts.headers['Content-Type'] = 'application/json';
      opts.headers['X-Sambandh'] = '1';
      opts.body = '{}';
    }
    let res;
    try { res = await fetch(path, opts); } catch (e) {
      const err = new Error('Cannot reach the server. Is it still running?'); err.status = 0; throw err;
    }
    let data = null;
    try { data = await res.json(); } catch (e) { /* not JSON */ }
    if (!res.ok) {
      if (res.status === 401 && state.me && path !== '/api/login') { state.me = null; renderLogin('Your session ended. Please sign in again.'); }
      const err = new Error((data && data.error) || ('Error ' + res.status));
      err.status = res.status; err.data = data && data.data; throw err;
    }
    return data;
  }

  let toastTimer = null;
  function toast(msg, bad) {
    const t = $('#toast');
    t.textContent = msg; t.className = 'toast' + (bad ? ' bad' : ''); t.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => { t.hidden = true; }, bad ? 6000 : 3200);
  }

  // ------------------------------------------------------------------ theme (Day shift / Night shift)
  function isDark() {
    const t = document.documentElement.getAttribute('data-theme');
    if (t) return t === 'dark';
    return window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches;
  }
  function leverHtml() {
    const d = isDark();
    return `<button type="button" class="lever" id="lever" aria-pressed="${d}" title="Switch between Day shift (light) and Night shift (dark)">
      <span class="track"><span class="knob"></span></span><span class="txt">${d ? 'Night shift' : 'Day shift'}</span></button>`;
  }
  function toggleTheme() {
    const next = isDark() ? 'light' : 'dark';
    document.documentElement.setAttribute('data-theme', next);
    try { localStorage.setItem('sambandh-theme', next); } catch (e) { /* ignore */ }
    const l = $('#lever');
    if (l) l.outerHTML = leverHtml();
  }

  // ------------------------------------------------------------------ modal dialogs
  let modalClose = null;
  function openModal(title, html, onSubmit, opts) {
    closeModal();
    const root = $('#modal-root');
    root.innerHTML = `<div class="modal-back" data-close="1"><div class="modal plate screws" role="dialog" aria-modal="true" aria-labelledby="mtitle">
      <div class="plate-head"><h2 class="label-strip" id="mtitle">${esc(title)}</h2>
      <button type="button" class="btn ghost sm" data-close="1" aria-label="Close">✕</button></div>
      <form class="plate-body form" id="mform" novalidate>${html}
      <div class="error-box" id="merr" hidden></div>
      <div class="modal-foot"><button type="button" class="btn" data-close="1">Cancel</button>
      <button type="submit" class="btn ${opts && opts.danger ? 'danger' : 'primary'}">${esc((opts && opts.submit) || 'Save')}</button></div></form></div></div>`;
    const back = root.firstElementChild;
    const last = document.activeElement;
    back.addEventListener('click', (e) => { if (e.target.dataset.close) closeModal(); });
    const onKey = (e) => { if (e.key === 'Escape') closeModal(); };
    document.addEventListener('keydown', onKey);
    const form = $('#mform');
    form.addEventListener('submit', async (e) => {
      e.preventDefault();
      const btn = form.querySelector('button[type=submit]');
      btn.disabled = true;
      $('#merr').hidden = true;
      try {
        await onSubmit(formData(form), form);
        closeModal();
      } catch (err) {
        $('#merr').textContent = err.message; $('#merr').hidden = false;
      } finally { btn.disabled = false; }
    });
    if (opts && opts.onOpen) opts.onOpen(form);
    const first = form.querySelector('input:not([type=hidden]):not([type=radio]), select, textarea, input[type=radio]:checked');
    if (first) first.focus();
    modalClose = () => { document.removeEventListener('keydown', onKey); root.innerHTML = ''; if (last && last.focus) last.focus(); };
  }
  function closeModal() { if (modalClose) { const f = modalClose; modalClose = null; f(); } }

  function formData(form) {
    const out = {};
    for (const el of form.elements) {
      if (!el.name) continue;
      if (el.type === 'checkbox') out[el.name] = el.checked;
      else if (el.type === 'radio') { if (el.checked) out[el.name] = el.value; }
      else out[el.name] = el.value;
    }
    return out;
  }
  const seg = (name, options, checked) => '<div class="seg" role="radiogroup">' + options.map(([v, l]) =>
    `<label><input type="radio" name="${name}" value="${esc(v)}" ${v === checked ? 'checked' : ''}><span>${esc(l)}</span></label>`).join('') + '</div>';
  const staffOptions = (sel, onlyActive) => state.staff.filter((u) => !onlyActive || u.active)
    .map((u) => `<option value="${esc(u.id)}" ${u.id === sel ? 'selected' : ''}>${esc(u.name)} (${esc(u.roleLabel)})</option>`).join('');

  // ------------------------------------------------------------------ login screen
  function renderLogin(message) {
    closeModal();
    document.title = 'Sign in – Sambandh CRM';
    $('#app').innerHTML = `<div class="login-wrap"><div class="login-card">
      <div class="login-art">
        <div class="eyebrow">Customer relationship exchange</div>
        <h1>Samb&shy;andh</h1>
        <p><b>सम्बन्ध</b> means “relationship”. Every customer, every call, every order and complaint – connected in one place, like an old telephone switchboard.</p>
        <svg class="board-art" viewBox="0 0 360 150" aria-hidden="true">
          ${[0, 1, 2, 3, 4, 5].map((i) => `<circle class="s" cx="${30 + i * 60}" cy="40" r="13"/><circle class="s" cx="${30 + i * 60}" cy="112" r="13"/>
            <circle class="${['lg', 'la', 'lo', 'lg', 'lo', 'la'][i]}" cx="${30 + i * 60}" cy="12" r="6"/>`).join('')}
          <path class="c1" d="M30 40 C 30 100, 150 60, 150 112" fill="none" stroke-width="5" stroke-linecap="round"/>
          <path class="c2" d="M210 40 C 210 90, 90 70, 90 112" fill="none" stroke-width="5" stroke-linecap="round"/>
          <path class="c3" d="M330 40 C 330 100, 270 70, 270 112" fill="none" stroke-width="5" stroke-linecap="round"/>
        </svg>
      </div>
      <form class="login-form" id="loginForm" novalidate>
        <h2>Operator sign-in</h2>
        ${message ? `<div class="note-box">${esc(message)}</div>` : ''}
        <label class="field"><span>Username</span><input type="text" name="username" autocomplete="username" required></label>
        <label class="field"><span>Password</span><input type="password" name="password" autocomplete="current-password" required></label>
        <div class="error-box" id="loginErr" hidden></div>
        <button class="btn primary" type="submit">Connect</button>
        <div><div class="small muted mb6">Demo accounts (all data is made up):</div>
        <div class="demo">
          <button type="button" class="chip" data-u="admin" data-p="admin123">admin · Admin</button>
          <button type="button" class="chip" data-u="ritu" data-p="manager123">ritu · Manager</button>
          <button type="button" class="chip" data-u="arjun" data-p="agent123">arjun · Agent</button>
          <button type="button" class="chip" data-u="kavya" data-p="agent123">kavya · Agent</button>
        </div></div>
        <div>${leverHtml()}</div>
      </form></div></div>`;
    const form = $('#loginForm');
    form.addEventListener('click', (e) => {
      const b = e.target.closest('[data-u]');
      if (b) { form.username.value = b.dataset.u; form.password.value = b.dataset.p; form.querySelector('[type=submit]').focus(); }
      if (e.target.closest('#lever')) toggleTheme();
    });
    form.addEventListener('submit', async (e) => {
      e.preventDefault();
      const err = $('#loginErr'); err.hidden = true;
      try {
        const r = await api('POST', '/api/login', { username: form.username.value, password: form.password.value });
        state.me = r.user; state.meta = r.meta;
        await startApp();
      } catch (ex) { err.textContent = ex.message; err.hidden = false; }
    });
    form.username.focus();
  }

  // ------------------------------------------------------------------ the shell (cabinet + top bar)
  const NAV = [
    { group: 'Exchange' },
    { id: 'home', href: '#/', label: 'Switchboard' },
    { id: 'customers', href: '#/customers', label: 'Customers' },
    { id: 'followups', href: '#/followups', label: 'Follow-ups', count: 'followups' },
    { id: 'tickets', href: '#/tickets', label: 'Support tickets', count: 'tickets' },
    { group: 'Manager', manager: true },
    { id: 'duplicates', href: '#/duplicates', label: 'Duplicates', manager: true },
    { id: 'import', href: '#/import', label: 'Import CSV', manager: true },
    { id: 'activity', href: '#/activity', label: 'Activity log', manager: true },
    { group: 'Admin', admin: true },
    { id: 'team', href: '#/team', label: 'Staff accounts', admin: true }
  ];

  function renderShell() {
    const me = state.me;
    $('#app').innerHTML = `<div class="shell" id="shell">
      <aside class="cabinet" aria-label="Main menu">
        <a class="brand" href="#/">${BRAND}<span><div class="brand-name">Sambandh</div><div class="brand-sub">Customer exchange</div></span></a>
        <div class="brass-rule"></div>
        <nav><ul class="jacks" id="nav">${NAV.filter((n) => (!n.manager || isManager()) && (!n.admin || isAdmin())).map((n) => n.group
          ? `<li class="group">${esc(n.group)}</li>`
          : `<li><a class="jack" href="${n.href}" data-nav="${n.id}"><span class="hole" aria-hidden="true"></span><span>${esc(n.label)}</span>${n.count ? `<span class="count" data-count="${n.count}" hidden></span>` : ''}</a></li>`).join('')}</ul></nav>
        <div class="cabinet-foot">Suryodaya Solar · Ranchi<br>Demo company – all data made up</div>
      </aside>
      <div class="main">
        <header class="topbar">
          <button type="button" class="btn ghost menu-btn" id="menuBtn" aria-label="Open menu">${ICON.menu}</button>
          <form class="dial" id="dial" role="search"><label class="sr" for="dialq">Find a customer</label>${ICON.search}
            <input id="dialq" type="search" placeholder="Dial a customer – name, phone, e-mail, city, ID…" autocomplete="off"></form>
          <div class="top-right">${leverHtml()}
            <a class="operator" href="#/account" title="My account"><span class="who"><b>${esc(me.name)}</b><span>${esc(me.roleLabel)}</span></span>
            <span class="badge-ini">${esc(me.initials)}</span></a>
            <button type="button" class="btn sm" id="logoutBtn">Sign out</button></div>
        </header>
        <main id="view" tabindex="-1"></main>
      </div></div>`;
    $('#menuBtn').addEventListener('click', () => $('#shell').classList.toggle('open'));
    $('#shell').addEventListener('click', (e) => {
      if (e.target.id === 'shell' || e.target.closest('.jack')) $('#shell').classList.remove('open');
    });
    $('#dial').addEventListener('submit', (e) => {
      e.preventDefault();
      const q = $('#dialq').value.trim();
      location.hash = '#/customers' + (q ? '?q=' + encodeURIComponent(q) : '');
    });
    $('.topbar').addEventListener('click', (e) => { if (e.target.closest('#lever')) toggleTheme(); });
    $('#logoutBtn').addEventListener('click', async () => {
      try { await api('POST', '/api/logout'); } catch (e) { /* ignore */ }
      state.me = null; location.hash = '#/'; renderLogin('You have signed out.');
    });
  }

  function setCounts(d) {
    state.counts = { followups: (d.overdue || 0) + (d.dueToday || 0), followupsHot: d.overdue > 0, tickets: d.breachedTickets || 0 };
    $$('[data-count]').forEach((el) => {
      const n = el.dataset.count === 'followups' ? state.counts.followups : state.counts.tickets;
      el.hidden = !n; el.textContent = n;
      el.classList.toggle('hot', el.dataset.count === 'tickets' ? n > 0 : state.counts.followupsHot);
      el.title = el.dataset.count === 'tickets' ? n + ' past the deadline' : n + ' due today or overdue';
    });
  }
  async function refreshCounts() {
    try { setCounts(await api('GET', '/api/dashboard')); } catch (e) { /* not important */ }
  }

  // ------------------------------------------------------------------ router
  function parseHash() {
    const h = location.hash.replace(/^#/, '') || '/';
    const [path, qs] = h.split('?');
    const params = {};
    new URLSearchParams(qs || '').forEach((v, k) => { params[k] = v; });
    return { parts: path.split('/').filter(Boolean), params };
  }

  /** A brand-new empty #view, so event listeners of the previous screen are thrown away. */
  function freshView() {
    const old = $('#view');
    const fresh = old.cloneNode(false);
    old.replaceWith(fresh);
    return fresh;
  }

  async function router() {
    if (!state.me) return;
    closeModal();
    const { parts, params } = parseHash();
    const view = freshView();
    let navId = 'home';
    try {
      view.innerHTML = '<div class="loading"><span class="spinner"></span> Connecting…</div>';
      if (parts.length === 0) await viewHome(view);
      else if (parts[0] === 'customers' && parts[1] === 'new') { navId = 'customers'; await viewCustomerForm(view, null); }
      else if (parts[0] === 'customers') { navId = 'customers'; await viewCustomers(view, params); }
      else if (parts[0] === 'c' && parts[2] === 'edit') { navId = 'customers'; await viewCustomerForm(view, parts[1]); }
      else if (parts[0] === 'c') { navId = 'customers'; await viewCustomer(view, parts[1]); }
      else if (parts[0] === 'followups') { navId = 'followups'; await viewFollowUps(view, params); }
      else if (parts[0] === 'tickets') { navId = 'tickets'; await viewTickets(view, params); }
      else if (parts[0] === 'duplicates') { navId = 'duplicates'; await viewDuplicates(view); }
      else if (parts[0] === 'import') { navId = 'import'; await viewImport(view); }
      else if (parts[0] === 'activity') { navId = 'activity'; await viewActivity(view, params); }
      else if (parts[0] === 'team') { navId = 'team'; await viewTeam(view); }
      else if (parts[0] === 'account') { navId = ''; await viewAccount(view); }
      else view.innerHTML = '<div class="empty plate">No such page. <a href="#/">Back to the switchboard</a></div>';
    } catch (e) {
      if (e.status === 401) return;
      view.innerHTML = `<div class="plate"><div class="empty"><span class="lamp r"></span>${esc(e.message)}<br><br><a class="btn" href="#/">Back to the switchboard</a></div></div>`;
    }
    $$('#nav [data-nav]').forEach((a) => {
      if (a.dataset.nav === navId) a.setAttribute('aria-current', 'page'); else a.removeAttribute('aria-current');
    });
    applyWidths(view);
  }

  // ================================================================== SWITCHBOARD (home)
  function greeting() {
    const h = new Date().getHours();
    return h < 12 ? 'Good morning' : h < 17 ? 'Good afternoon' : 'Good evening';
  }

  function revenueChart(months) {
    const W = 560, H = 190, pad = 26, bw = 48;
    const max = Math.max(1, ...months.map((m) => m.paise));
    const step = (W - pad * 2) / months.length;
    return `<svg viewBox="0 0 ${W} ${H}" role="img" aria-label="Money earned per month">
      <line class="base" x1="${pad}" y1="${H - 28}" x2="${W - pad}" y2="${H - 28}"/>
      ${months.map((m, i) => {
        const h = Math.round((H - 64) * m.paise / max);
        const x = pad + i * step + (step - bw) / 2;
        return `<rect class="col ${i === months.length - 1 ? 'now' : ''}" x="${x}" y="${H - 28 - h}" width="${bw}" height="${Math.max(h, 1)}" rx="4"/>
          <text class="val" x="${x + bw / 2}" y="${H - 34 - h}" text-anchor="middle">${esc(moneyShort(m.paise))}</text>
          <text class="axis" x="${x + bw / 2}" y="${H - 9}" text-anchor="middle">${esc(m.label)}</text>`;
      }).join('')}</svg>`;
  }

  async function viewHome(view) {
    const d = await api('GET', '/api/dashboard');
    setCounts(d);
    document.title = 'Switchboard – Sambandh CRM';
    const trend = d.revenuePrev30 ? Math.round((d.revenue30 - d.revenuePrev30) * 100 / d.revenuePrev30) : null;
    const h = d.health, hTotal = Math.max(1, h.healthy + h.watch + h.risk);
    const segHot = ["Can't lose them", 'At risk'];
    view.innerHTML = `
      <div class="page-head"><div class="grow"><div class="eyebrow">${esc(fmtDate(d.today))} · ${d.scope === 'all' ? 'whole team' : 'your customers'}</div>
        <h1>${esc(greeting())}, ${esc(d.greetingName)}</h1>
        <p>${d.overdue ? `<b>${d.overdue}</b> follow-up${d.overdue > 1 ? 's are' : ' is'} overdue. ` : ''}${d.breachedTickets ? `<b>${d.breachedTickets}</b> ticket${d.breachedTickets > 1 ? 's are' : ' is'} past the deadline. ` : ''}${d.atRiskCount ? `<b>${d.atRiskCount}</b> paying customer${d.atRiskCount > 1 ? 's are' : ' is'} slipping away.` : 'All lines are quiet.'}</p></div>
        <a class="btn primary" href="#/customers/new">${ICON.plus} New customer</a></div>

      <div class="counters">
        <a class="plate screws counter nolink" href="#/customers"><div class="label-strip">Customers</div>${odo(String(d.customers))}<div class="sub">+${d.newThisMonth} new this month</div></a>
        <div class="plate screws counter"><div class="label-strip">Earned · 30 days</div>${odo(moneyShort(d.revenue30).replace('₹', ''))}
          <div class="sub">${trend === null ? 'No sales in the 30 days before' : `<span class="${trend >= 0 ? 'trend-up' : 'trend-down'}">${trend >= 0 ? '▲' : '▼'} ${Math.abs(trend)}%</span> vs. the 30 days before`}</div></div>
        <a class="plate screws counter nolink" href="#/tickets?status=open"><div class="label-strip">Open tickets</div>${odo(String(d.openTickets))}
          <div class="sub">${d.breachedTickets ? `<span class="lamp r blink"></span> ${d.breachedTickets} past the deadline` : '<span class="lamp g"></span> all within deadline'}</div></a>
        <a class="plate screws counter nolink" href="#/followups"><div class="label-strip">My follow-ups</div>${odo(String(d.overdue + d.dueToday))}
          <div class="sub">${d.overdue ? `<span class="lamp r"></span> ${d.overdue} overdue · ` : ''}${d.dueToday} due today</div></a>
      </div>

      <div class="grid g-main">
        <div class="stack">
          <section class="plate screws"><div class="plate-head"><h2 class="label-strip">Lifecycle lines</h2><a class="small" href="#/customers">All customers →</a></div>
            <div class="plate-body"><div class="stage-board">${state.meta.stages.map((s) =>
              `<a class="stage-cell sc-${s.id}" href="#/customers?stage=${s.id}" title="${esc(s.meaning)}"><span class="socket"></span><b>${d.stages[s.id] || 0}</b><span>${esc(s.label)}</span></a>`).join('')}</div></div></section>

          <section class="plate screws"><div class="plate-head"><h2 class="label-strip">Money earned per month</h2><span class="small muted">paid orders, refunds left out</span></div>
            <div class="plate-body chart">${revenueChart(d.months)}</div></section>

          <section class="plate screws"><div class="plate-head"><h2 class="label-strip">Slipping away</h2><a class="small" href="#/customers?health=atrisk&sort=value">See all ${d.atRiskCount} →</a></div>
            ${d.atRisk.length ? `<ul class="list">${d.atRisk.map((c) => `<li><span class="lamp r"></span><div class="grow">
              <a class="title" href="#/c/${esc(c.id)}">${esc(c.name)}</a>${c.company ? ` <span class="muted small">· ${esc(c.company)}</span>` : ''}
              <div class="meta">${esc(c.reason)} · owner ${esc(c.ownerName)}</div></div>
              <div class="mono small nowrap" title="Health score">${c.health}/100</div><div class="mono small nowrap hide-sm" title="Lifetime value">${money(c.ltvPaise)}</div></li>`).join('')}</ul>`
              : '<div class="empty"><span class="lamp g"></span>No paying customer is at risk.</div>'}</section>
        </div>

        <div class="stack">
          <section class="plate screws"><div class="plate-head"><h2 class="label-strip">Due today</h2><a class="small" href="#/followups">Queue →</a></div>
            ${d.myFollowUps.length ? `<ul class="list">${d.myFollowUps.map((f) => `<li><span class="lamp ${f.overdue ? 'r' : 'a'}"></span>
              <div class="grow"><div class="title">${esc(f.title)}</div><div class="meta"><a href="#/c/${esc(f.customerId)}">${esc(f.customerName)}</a> · ${f.overdue ? 'was due ' + esc(fmtDate(f.due)) : 'today'}</div></div>
              <span class="prio ${esc(f.priority)}">${esc(f.priority)}</span></li>`).join('')}</ul>`
              : '<div class="empty"><span class="lamp g"></span>Nothing due. Well done.</div>'}</section>

          <section class="plate screws"><div class="plate-head"><h2 class="label-strip">Health lamps</h2></div>
            <div class="plate-body">
              ${[['healthy', 'g', 'Healthy (70+)'], ['watch', 'a', 'Watch (40–69)'], ['risk', 'r', 'At risk (below 40)']].map(([k, l, t]) =>
                `<a class="legend-row nolink" href="#/customers?health=${k}"><span><span class="lamp ${l}"></span>&nbsp; ${t}</span><b class="mono">${h[k]}</b>
                <div class="bar ${l}"><i data-w="${h[k] * 100 / hTotal}"></i></div></a>`).join('')}
              <div class="small muted">Not counting churned customers.</div></div></section>

          <section class="plate screws"><div class="plate-head"><h2 class="label-strip">RFM segments</h2><span class="small muted" title="Recency, Frequency, Monetary">buyers only</span></div>
            <div class="plate-body"><div class="rfm-grid">${Object.entries(d.segments).map(([k, v]) =>
              `<a class="rfm-cell ${segHot.includes(k) && v ? 'hot' : ''}" href="#/customers?segment=${encodeURIComponent(k)}"><b>${v}</b><span>${esc(k)}</span></a>`).join('')}</div></div></section>

          <section class="plate screws"><div class="plate-head"><h2 class="label-strip">Latest on the line</h2></div>
            <ul class="list">${d.recent.map((i) => `<li><span class="plug plug-sm ${esc(i.outcome)}" aria-hidden="true">${ICON[i.type] || ''}</span>
              <div class="grow"><a class="title" href="#/c/${esc(i.customerId)}">${esc(i.customerName)}</a>
              <div class="meta">${esc(nice(i.type))}${i.direction ? (i.direction === 'IN' ? ' from them' : ' by us') : ''} · ${esc(ago(i.at))} · ${esc(i.byName)}</div></div></li>`).join('') || '<li class="empty">No conversations yet.</li>'}</ul></section>
        </div>
      </div>

      ${d.team.length ? `<section class="plate screws mt18"><div class="plate-head"><h2 class="label-strip">Operators on duty</h2><span class="small muted">last 7 days · money from their customers in 30 days</span></div>
        <div class="table-wrap"><table class="directory"><thead><tr><th>Staff</th><th class="num">Customers</th><th class="num">Conversations</th><th class="num">Follow-ups done</th><th class="num">Overdue</th><th class="num">Earned (30 d)</th></tr></thead>
        <tbody>${d.team.map((t) => `<tr><td><span class="person"><span class="badge-ini">${esc(t.initials)}</span><span><b>${esc(t.name)}</b><div class="sub">${esc(t.roleLabel)}</div></span></span></td>
          <td class="num"><a href="#/customers?owner=${esc(t.id)}">${t.customers}</a></td><td class="num">${t.talks7}</td><td class="num">${t.done7}</td>
          <td class="num">${t.overdue ? `<span class="lamp r"></span> ${t.overdue}` : '0'}</td><td class="num">${money(t.revenue30)}</td></tr>`).join('')}</tbody></table></div></section>` : ''}`;
  }

  // ================================================================== CUSTOMERS LIST
  async function loadStaff() {
    if (state.staff.length) return;
    const r = await api('GET', '/api/users');
    state.staff = r.users;
  }

  async function viewCustomers(view, params) {
    await loadStaff();
    const q = Object.assign({ sort: 'recent', page: '1' }, params);
    const qs = new URLSearchParams(Object.entries(q).filter(([, v]) => v !== '' && v != null)).toString();
    const data = await api('GET', '/api/customers?' + qs);
    state.selected = new Set([...state.selected].filter(() => false));
    document.title = 'Customers – Sambandh CRM';
    const go = (change) => {
      const next = Object.assign({}, q, change);
      if (!('page' in change)) next.page = '1';
      const s = new URLSearchParams(Object.entries(next).filter(([, v]) => v !== '' && v != null && !(v === '1' && false))).toString();
      location.hash = '#/customers' + (s ? '?' + s : '');
    };
    const chip = (val, label) => `<button type="button" class="chip" data-stage="${val}" aria-pressed="${(q.stage || '') === val}">${esc(label)}</button>`;
    const exportQs = new URLSearchParams(Object.entries(q).filter(([k, v]) => v && k !== 'page')).toString();
    view.innerHTML = `
      <div class="page-head"><div class="grow"><div class="eyebrow">Directory</div><h1>Customers</h1>
        <p>${data.total} customer${data.total === 1 ? '' : 's'}${isManager() ? '' : ' that you own'}${q.q ? ` matching “${esc(q.q)}”` : ''}.</p></div>
        <div class="row-actions"><a class="btn" href="/api/customers/export.csv?${esc(exportQs)}">${ICON.download} Export CSV</a>
        <a class="btn primary" href="#/customers/new">${ICON.plus} New customer</a></div></div>
      <section class="plate">
        <div class="filters">
          <div class="chips" role="group" aria-label="Stage">${chip('', 'All')}${state.meta.stages.map((s) => chip(s.id, s.label)).join('')}</div>
        </div>
        <div class="filters">
          <label class="sr" for="fq">Search</label><input id="fq" type="search" value="${esc(q.q || '')}" placeholder="Search…" class="w220">
          <label class="small muted">Health <select class="compact" id="fhealth">
            ${[['', 'Any'], ['healthy', 'Healthy'], ['watch', 'Watch'], ['risk', 'At risk'], ['atrisk', 'Paying & slipping']].map(([v, l]) => `<option value="${v}" ${q.health === v ? 'selected' : ''}>${l}</option>`).join('')}</select></label>
          <label class="small muted">RFM <select class="compact" id="fseg"><option value="">Any</option>
            ${state.meta.segments.map((s) => `<option ${q.segment === s ? 'selected' : ''}>${esc(s)}</option>`).join('')}</select></label>
          ${isManager() ? `<label class="small muted">Owner <select class="compact" id="fowner"><option value="">Anyone</option>${staffOptions(q.owner)}</select></label>` : ''}
          <label class="small muted">Consent <select class="compact" id="fconsent">
            ${[['', 'Any'], ['email', 'Agreed to e-mails'], ['dnc', 'Do-Not-Call']].map(([v, l]) => `<option value="${v}" ${q.consent === v ? 'selected' : ''}>${l}</option>`).join('')}</select></label>
          <label class="small muted">Sort <select class="compact" id="fsort">
            ${[['recent', 'Last contact'], ['name', 'Name'], ['health', 'Health (worst first)'], ['health-desc', 'Health (best first)'], ['value', 'Lifetime value'], ['new', 'Newest']].map(([v, l]) => `<option value="${v}" ${q.sort === v ? 'selected' : ''}>${l}</option>`).join('')}</select></label>
          ${q.tag ? `<button type="button" class="chip" aria-pressed="true" id="ftag">tag: ${esc(q.tag)} ✕</button>` : ''}
        </div>
        ${isManager() ? `<div class="bulkbar" id="bulk" hidden><b id="bulkN">0 selected</b>
          <label class="small">Give to <select class="compact" id="bulkOwner">${staffOptions('', true)}</select></label>
          <button type="button" class="btn sm brass" id="bulkGo">Re-assign</button><button type="button" class="btn sm ghost" id="bulkClear">Clear</button></div>` : ''}
        <div class="table-wrap"><table class="directory"><thead><tr>
          ${isManager() ? '<th><input type="checkbox" id="selAll" aria-label="Select all on this page"></th>' : ''}
          <th>Customer</th><th>Stage</th><th>Health</th><th class="hide-sm">Owner</th><th class="hide-sm">Last contact</th><th class="num">Lifetime value</th></tr></thead>
          <tbody>${data.items.map((c) => `<tr data-id="${esc(c.id)}">
            ${isManager() ? `<td><input type="checkbox" class="sel" value="${esc(c.id)}" aria-label="Select ${esc(c.name)}"></td>` : ''}
            <td><a class="cname" href="#/c/${esc(c.id)}">${esc(c.name)}</a>
              <div class="sub">${esc(c.id)}${c.company ? ' · ' + esc(c.company) : ''}${c.city ? ' · ' + esc(c.city) : ''}
              ${c.doNotCall ? ' <span class="flag dnc">DND</span>' : ''}${c.erased ? ' <span class="flag erased">erased</span>' : ''}
              ${c.tags.map((t) => `<span class="tag">${esc(t)}</span>`).join('')}</div></td>
            <td>${stageTag(c.stage, c.stageLabel)}${c.segment ? `<div class="sub">${esc(c.segment)}</div>` : ''}</td>
            <td><span class="healthchip"><span class="lamp ${lampFor(c.band)}"></span>${c.health}</span>${c.openTickets ? `<div class="sub">${c.openTickets} open ticket${c.openTickets > 1 ? 's' : ''}</div>` : ''}</td>
            <td class="hide-sm">${esc(c.ownerName)}</td>
            <td class="hide-sm">${esc(ago(c.lastContact))}</td>
            <td class="num">${c.ltvPaise ? money(c.ltvPaise) : '—'}</td></tr>`).join('')}</tbody></table>
          ${data.items.length ? '' : '<div class="empty"><span class="lamp"></span>No customers match. Try fewer filters.</div>'}</div>
        <div class="pager"><span class="small muted">Page ${data.page} of ${data.pages}</span><span class="row-actions">
          <button type="button" class="btn sm" id="prev" ${data.page <= 1 ? 'disabled' : ''}>← Previous</button>
          <button type="button" class="btn sm" id="next" ${data.page >= data.pages ? 'disabled' : ''}>Next →</button></span></div>
      </section>`;

    view.querySelector('.plate').addEventListener('click', (e) => {
      const s = e.target.closest('[data-stage]');
      if (s) go({ stage: s.dataset.stage });
    });
    let t = null;
    $('#fq').addEventListener('input', (e) => { clearTimeout(t); t = setTimeout(() => go({ q: e.target.value.trim() }), 450); });
    $('#fhealth').addEventListener('change', (e) => go({ health: e.target.value }));
    $('#fseg').addEventListener('change', (e) => go({ segment: e.target.value }));
    $('#fconsent').addEventListener('change', (e) => go({ consent: e.target.value }));
    $('#fsort').addEventListener('change', (e) => go({ sort: e.target.value }));
    if ($('#fowner')) $('#fowner').addEventListener('change', (e) => go({ owner: e.target.value }));
    if ($('#ftag')) $('#ftag').addEventListener('click', () => go({ tag: '' }));
    $('#prev').addEventListener('click', () => go({ page: String(data.page - 1) }));
    $('#next').addEventListener('click', () => go({ page: String(data.page + 1) }));
    if (q.q && document.activeElement !== $('#dialq')) { const f = $('#fq'); f.focus(); f.setSelectionRange(f.value.length, f.value.length); }

    if (isManager()) {
      const update = () => {
        $$('.sel', view).forEach((b) => b.closest('tr').classList.toggle('sel', b.checked));
        const n = $$('.sel:checked', view).length;
        $('#bulk').hidden = n === 0; $('#bulkN').textContent = n + ' selected';
      };
      view.addEventListener('change', (e) => {
        if (e.target.id === 'selAll') $$('.sel', view).forEach((b) => { b.checked = e.target.checked; });
        if (e.target.id === 'selAll' || e.target.classList.contains('sel')) update();
      });
      $('#bulkClear').addEventListener('click', () => { $$('.sel', view).forEach((b) => { b.checked = false; }); $('#selAll').checked = false; update(); });
      $('#bulkGo').addEventListener('click', async () => {
        const ids = $$('.sel:checked', view).map((b) => b.value);
        try {
          const r = await api('POST', '/api/customers/assign', { ids, ownerId: $('#bulkOwner').value });
          toast(r.moved + ' customer' + (r.moved === 1 ? '' : 's') + ' re-assigned.');
          router();
        } catch (ex) { toast(ex.message, true); }
      });
    }
  }

  // ================================================================== NEW / EDIT CUSTOMER
  async function viewCustomerForm(view, id) {
    await loadStaff();
    const c = id ? await api('GET', '/api/customers/' + encodeURIComponent(id)) : {
      type: 'INDIVIDUAL', stage: 'LEAD', ownerId: state.me.id, tags: [], consentEmail: false, doNotCall: false };
    if (c.erased) { view.innerHTML = '<div class="plate"><div class="empty">This customer\'s data was erased and cannot be edited.</div></div>'; return; }
    document.title = (id ? 'Edit ' + c.name : 'New customer') + ' – Sambandh CRM';
    const v = (k) => esc(c[k] || '');
    view.innerHTML = `
      <div class="page-head"><div class="grow"><div class="eyebrow">${id ? esc(c.id) : 'New connection'}</div>
        <h1>${id ? 'Edit customer' : 'New customer'}</h1><p>Give at least a phone number or an e-mail. Phone numbers can be typed any way – “98351 23456”, “+91-9835123456” or “09835123456”.</p></div></div>
      <div class="grid g-detail">
        <form class="plate screws" id="cform" novalidate><div class="plate-body form">
          <div class="field"><span>Kind of customer</span>${seg('type', [['INDIVIDUAL', 'Person / household'], ['BUSINESS', 'Business']], c.type)}</div>
          <div class="two"><label class="field"><span>Name *</span><input type="text" name="name" value="${v('name')}" maxlength="80" required autocomplete="off"></label>
            <label class="field"><span>Company <small id="compHint">${c.type === 'BUSINESS' ? '(needed for a business)' : '(optional)'}</small></span><input type="text" name="company" value="${v('company')}" maxlength="80" autocomplete="off"></label></div>
          <div class="two"><label class="field"><span>Phone</span><input type="tel" name="phone" value="${v('phone')}" placeholder="98351 23456" autocomplete="off"></label>
            <label class="field"><span>E-mail</span><input type="email" name="email" value="${v('email')}" placeholder="name@example.in" autocomplete="off"></label></div>
          <div id="dupes"></div>
          <div class="three"><label class="field"><span>City</span><input type="text" name="city" value="${v('city')}" maxlength="40" list="cities"></label>
            <label class="field"><span>Came from</span><select name="source"><option value="">—</option>${state.meta.sources.map((s) => `<option ${c.source === s ? 'selected' : ''}>${esc(s)}</option>`).join('')}</select></label>
            <label class="field"><span>Stage</span><select name="stage">${state.meta.stages.map((s) => `<option value="${s.id}" ${c.stage === s.id ? 'selected' : ''}>${esc(s.label)} – ${esc(s.meaning)}</option>`).join('')}</select></label></div>
          <datalist id="cities">${['Ranchi', 'Jamshedpur', 'Dhanbad', 'Bokaro', 'Hazaribagh', 'Deoghar', 'Giridih', 'Ramgarh', 'Dumka', 'Chaibasa', 'Khunti'].map((x) => `<option value="${x}">`).join('')}</datalist>
          <label class="field"><span>Tags <small>(comma separated, e.g. vip, commercial)</small></span><input type="text" name="tags" value="${esc((c.tags || []).join(', '))}"></label>
          ${isManager() ? `<label class="field"><span>Owner (responsible staff member)</span><select name="ownerId">${staffOptions(c.ownerId, true)}</select></label>` : ''}
          <label class="field"><span>Notes</span><textarea name="notes" maxlength="1000">${v('notes')}</textarea></label>
          <fieldset class="note-box consent-box"><legend class="label-strip legend-sm">Consent</legend>
            <label class="check"><input type="checkbox" name="consentEmail" ${c.consentEmail ? 'checked' : ''}><span><b>Agreed to e-mails</b><br><span class="small muted">Tick only if they said yes. Without this, outgoing e-mails cannot be logged.</span></span></label>
            <label class="check"><input type="checkbox" name="doNotCall" ${c.doNotCall ? 'checked' : ''}><span><b>Do-Not-Call</b><br><span class="small muted">They asked us not to phone them (TRAI DND). Outgoing calls will be blocked.</span></span></label>
          </fieldset>
          <div class="error-box" id="cerr" hidden></div>
          <div class="row-actions"><button class="btn primary" type="submit">${id ? 'Save changes' : 'Save customer'}</button>
            <a class="btn" href="${id ? '#/c/' + esc(id) : '#/customers'}">Cancel</a></div>
        </div></form>
        <aside class="plate screws"><div class="plate-head"><h2 class="label-strip">How duplicates are caught</h2></div>
          <div class="plate-body small"><p class="mt0">While you type, Sambandh compares this customer with everyone already saved:</p>
          <ul class="ul-tight mb10"><li><b>Same e-mail or phone</b> – blocked, it is the same person.</li>
          <li><b>Very similar name</b> (typing mistakes, words swapped) plus the same company or city – you get a warning.</li></ul>
          <p class="muted m0">Names are compared word by word with the Jaro-Winkler method, so “Kujur Ravi” matches “Ravi Kujur” and “Hussai” matches “Hussain”.</p></div></aside>
      </div>`;
    const form = $('#cform');
    form.addEventListener('change', (e) => { if (e.target.name === 'type') $('#compHint').textContent = e.target.value === 'BUSINESS' ? '(needed for a business)' : '(optional)'; });
    let t = null;
    const check = async () => {
      const f = formData(form);
      if (!f.name && !f.email && !f.phone) { $('#dupes').innerHTML = ''; return; }
      try {
        const r = await api('POST', '/api/customers/check', { name: f.name, email: f.email, phone: f.phone, company: f.company, city: f.city, excludeId: id || null });
        $('#dupes').innerHTML = r.matches.length ? `<div class="dupe-warn" role="status"><b><span class="lamp a"></span> Is this someone already saved?</b><ul>${r.matches.map((m) =>
          `<li>${m.id ? `<a href="#/c/${esc(m.id)}">${esc(m.id)} – ${esc(m.name)}</a>` : esc(m.name)}${m.company ? ', ' + esc(m.company) : ''}${m.city ? ', ' + esc(m.city) : ''}
          <span class="muted">(${esc(m.reasons.join(', '))} · match ${m.score}%)</span></li>`).join('')}</ul></div>` : '';
      } catch (e) { /* ignore while typing */ }
    };
    form.addEventListener('input', (e) => {
      if (['name', 'email', 'phone', 'company', 'city'].includes(e.target.name)) { clearTimeout(t); t = setTimeout(check, 400); }
    });
    if (id) check();
    form.addEventListener('submit', async (e) => {
      e.preventDefault();
      const f = formData(form);
      f.tags = f.tags.split(',').map((x) => x.trim()).filter(Boolean);
      const err = $('#cerr'); err.hidden = true;
      const btn = form.querySelector('[type=submit]'); btn.disabled = true;
      try {
        const saved = await api(id ? 'PUT' : 'POST', id ? '/api/customers/' + id : '/api/customers', f);
        toast(id ? 'Changes saved.' : 'Customer ' + saved.id + ' saved.');
        location.hash = '#/c/' + saved.id;
      } catch (ex) {
        err.innerHTML = esc(ex.message) + (ex.data && ex.data.existingId ? ` <a href="#/c/${esc(ex.data.existingId)}">Open ${esc(ex.data.existingId)} →</a>` : '');
        err.hidden = false; err.scrollIntoView({ block: 'center', behavior: 'smooth' });
      } finally { btn.disabled = false; }
    });
    form.name.focus();
  }

  // ================================================================== ONE CUSTOMER
  /** Health meter: a half-circle dial with red, amber and green zones, like a VU meter on the switchboard. */
  function gauge(score) {
    const cx = 100, cy = 100, r = 78;
    const pt = (deg, rad) => [cx + rad * Math.cos(deg * Math.PI / 180), cy - rad * Math.sin(deg * Math.PI / 180)];
    const arc = (from, to, cls) => {
      const a = pt(180 - from * 1.8, r), b = pt(180 - to * 1.8, r);
      return `<path class="${cls}" d="M${a[0].toFixed(1)} ${a[1].toFixed(1)} A${r} ${r} 0 0 1 ${b[0].toFixed(1)} ${b[1].toFixed(1)}" fill="none" stroke-width="12"/>`;
    };
    const ticks = [0, 20, 40, 60, 80, 100].map((v) => {
      const a = pt(180 - v * 1.8, r - 12), b = pt(180 - v * 1.8, r - 20);
      return `<line class="tick" x1="${a[0].toFixed(1)}" y1="${a[1].toFixed(1)}" x2="${b[0].toFixed(1)}" y2="${b[1].toFixed(1)}"/>`;
    }).join('');
    const n = pt(180 - score * 1.8, r - 16);
    return `<svg viewBox="0 0 200 128" role="img" aria-label="Health ${score} out of 100">
      <path class="face" d="M10 104 A90 90 0 0 1 190 104 Z"/>
      ${arc(0, 40, 'zone-r')}${arc(40, 70, 'zone-a')}${arc(70, 100, 'zone-g')}${ticks}
      <line class="needle" x1="${cx}" y1="${cy}" x2="${n[0].toFixed(1)}" y2="${n[1].toFixed(1)}"/>
      <circle class="hub" cx="${cx}" cy="${cy}" r="7"/>
      <text class="num" x="${cx}" y="${cy + 26}" text-anchor="middle">${score}</text></svg>`;
  }

  async function viewCustomer(view, id, keepTab) {
    const c = await api('GET', '/api/customers/' + encodeURIComponent(id));
    await loadStaff();
    if (!keepTab) state.detailTab = state.detailTab || 'log';
    document.title = c.name + ' – Sambandh CRM';
    const hl = c.health;
    const open = c.tickets.filter((t) => t.status !== 'RESOLVED').length;
    const todo = c.followUps.filter((f) => !f.done).length;
    const tabs = [['log', 'Call log', c.timeline.length], ['followups', 'Follow-ups', todo], ['orders', 'Orders', c.orders.length],
      ['tickets', 'Tickets', open], ['details', 'Details & privacy', '']];
    const ro = c.erased;
    view.innerHTML = `
      <section class="plate screws nameplate mb18">
        <span class="badge-ini big-ini">${esc(initials(c.name))}</span>
        <div><div class="mono small muted">${esc(c.id)} · ${c.type === 'BUSINESS' ? 'Business' : 'Person'}${c.source ? ' · from ' + esc(c.source) : ''}</div>
          <h1>${esc(c.name)}</h1>
          <div class="line">${stageTag(c.stage, c.stageLabel)}
            ${c.company ? `<span>${esc(c.company)}</span>` : ''}${c.city ? `<span>${esc(c.city)}</span>` : ''}
            ${c.phone ? (c.doNotCall ? `<span class="mono">${esc(phone(c.phone))}</span>` : `<a class="mono" href="tel:${esc(c.phone)}">${esc(phone(c.phone))}</a>`) : ''}
            ${c.email ? `<a href="mailto:${esc(c.email)}">${esc(c.email)}</a>` : ''}
            ${c.doNotCall ? '<span class="flag dnc">Do-Not-Call</span>' : ''}${c.consentEmail ? '<span class="flag mail">E-mails OK</span>' : ''}
            ${c.erased ? '<span class="flag erased">Personal data erased</span>' : ''}
            <span>Owner: <b>${esc(c.ownerName)}</b></span>
            ${c.tags.map((t) => `<a class="tag" href="#/customers?tag=${encodeURIComponent(t)}">${esc(t)}</a>`).join('')}</div></div>
        <div class="row-actions np-actions">${ro ? '' : `<button type="button" class="btn primary" data-act="log">${ICON.CALL} Log contact</button>
          <a class="btn" href="#/c/${esc(c.id)}/edit">Edit</a>`}</div>
      </section>

      <div class="grid g-detail">
        <section class="plate">
          <div class="tabs" role="tablist">${tabs.map(([k, l, n]) => `<button type="button" class="tab" role="tab" data-tab="${k}" aria-selected="${state.detailTab === k}">${l}${n !== '' ? `<span class="n">${n}</span>` : ''}</button>`).join('')}</div>
          <div id="tabBody">${tabBody(c)}</div>
        </section>

        <div class="stack">
          ${ro ? '<section class="plate screws"><div class="plate-head"><h2 class="label-strip">Health meter</h2></div><div class="empty"><span class="lamp"></span>Not tracked – this customer\'s personal data was erased on request.</div></section>' : ''}
          <section class="plate screws" ${ro ? 'hidden' : ''}><div class="plate-head"><h2 class="label-strip">Health meter</h2><span class="healthchip"><span class="lamp ${lampFor(hl.band)} ${hl.band === 'risk' ? 'blink' : ''}"></span>${esc(hl.bandLabel)}</span></div>
            <div class="plate-body gauge">${gauge(hl.score)}
              <div class="parts">${hl.parts.map((p) => `<div class="part"><span>${esc(p.label)}</span><div class="bar ${p.points / p.max >= .7 ? 'g' : p.points / p.max >= .4 ? 'a' : 'r'}"><i data-w="${p.points * 100 / p.max}"></i></div>
                <span class="pts">${p.points}/${p.max}</span><span class="why">${esc(p.note)}</span></div>`).join('')}</div></div></section>

          <section class="plate screws" ${ro ? 'hidden' : ''}><div class="plate-head"><h2 class="label-strip">Next best action</h2></div>
            <div class="plate-body"><ul class="actions-list">${c.nextActions.map((a) => `<li><span class="lamp ${a.kind === 'ok' ? 'g' : ['ticket', 'followup', 'repair'].includes(a.kind) ? 'r' : 'a'}"></span>
              <div><b>${esc(a.title)}</b><span>${esc(a.why)}</span></div></li>`).join('') || '<li class="muted">—</li>'}</ul></div></section>

          <section class="plate screws"><div class="plate-head"><h2 class="label-strip">Value & RFM</h2></div>
            <div class="plate-body"><dl class="kv"><dt>Lifetime value</dt><dd class="mono">${money(c.ltvPaise)}</dd>
              <dt>Last contact</dt><dd>${esc(ago(c.lastContact))}</dd>
              ${c.rfm ? `<dt>Segment</dt><dd><b>${esc(c.rfm.segment)}</b><br><span class="small muted">${esc(c.rfm.segmentNote)}</span></dd>
              <dt>R · F · M</dt><dd class="mono">${c.rfm.r} · ${c.rfm.f} · ${c.rfm.m} <span class="small muted">(1 = lowest, 5 = best)</span></dd>
              <dt>Last bought</dt><dd>${c.rfm.recencyDays} days ago</dd><dt>Last 12 months</dt><dd>${c.rfm.frequency} order${c.rfm.frequency === 1 ? '' : 's'}, ${money(c.rfm.monetaryPaise)}</dd>`
              : '<dt>Segment</dt><dd class="muted">No purchases yet</dd>'}</dl></div></section>
        </div>
      </div>`;
    applyWidths(view);
    bindCustomer(view, c);
  }

  function initials(name) {
    const w = String(name || '?').trim().split(/\s+/);
    return ((w[0] || '?')[0] + (w.length > 1 ? w[w.length - 1][0] : '')).toUpperCase();
  }

  function tabBody(c) {
    const ro = c.erased;
    switch (state.detailTab) {
      case 'followups':
        return `<div class="plate-body flush">${ro ? '' : '<div class="filters"><button type="button" class="btn sm brass" data-act="followup">' + ICON.plus + ' Add follow-up</button></div>'}
          ${c.followUps.length ? `<ul class="list">${c.followUps.map((f) => `<li><input type="checkbox" class="fdone tick-box" data-id="${esc(f.id)}" ${f.done ? 'checked' : ''} ${ro ? 'disabled' : ''} aria-label="Done: ${esc(f.title)}">
            <div class="grow"><div class="title${f.done ? ' struck' : ''}">${esc(f.title)}</div>
            <div class="meta">${f.done ? 'Done ' + esc(ago(f.doneAt)) : (f.overdue ? '<span class="lamp r"></span> overdue – was due ' : 'Due ') + esc(fmtDate(f.due))} · ${esc(f.assigneeName)}</div></div>
            <span class="prio ${esc(f.priority)}">${esc(f.priority)}</span></li>`).join('')}</ul>` : '<div class="empty">No follow-ups.</div>'}</div>`;
      case 'orders':
        return `<div class="plate-body flush">${ro ? '' : '<div class="filters"><button type="button" class="btn sm brass" data-act="order">' + ICON.plus + ' Add order</button></div>'}
          ${c.orders.length ? `<div class="table-wrap"><table class="directory"><thead><tr><th>Order</th><th>Date</th><th>Status</th><th class="num">Amount</th></tr></thead><tbody>
          ${c.orders.map((o) => `<tr><td><b>${esc(o.item)}</b><div class="sub">${esc(o.id)}</div></td><td class="nowrap">${esc(fmtDate(o.date))}</td>
            <td>${o.status === 'REFUNDED' || ro ? `<span class="small">${esc(nice(o.status))}</span>` : `<select class="compact ostatus" data-id="${esc(o.id)}" aria-label="Status of ${esc(o.id)}">${['PAID', 'PENDING', 'REFUNDED'].map((s) => `<option value="${s}" ${o.status === s ? 'selected' : ''}>${nice(s)}</option>`).join('')}</select>`}</td>
            <td class="num${o.status === 'REFUNDED' ? ' struck' : ''}">${money(o.amountPaise)}</td></tr>`).join('')}</tbody></table></div>` : '<div class="empty">No orders yet.</div>'}</div>`;
      case 'tickets':
        return `<div class="plate-body flush">${ro ? '' : '<div class="filters"><button type="button" class="btn sm brass" data-act="ticket">' + ICON.plus + ' Open ticket</button></div>'}
          ${c.tickets.length ? `<ul class="list">${c.tickets.map((t) => `<li><span class="lamp ${t.status === 'RESOLVED' ? 'g' : t.breached ? 'r blink' : 'a'}"></span>
            <div class="grow"><div class="title">${esc(t.subject)}</div><div class="meta">${esc(t.id)} · ${esc(nice(t.priority))} priority · opened ${esc(ago(t.createdAt))} ·
            ${t.status === 'RESOLVED' ? 'solved ' + esc(ago(t.resolvedAt)) : t.breached ? '<b class="txt-danger">past the deadline</b>' : 'deadline ' + esc(fmtDateTime(t.deadline))}</div></div>
            ${ro ? '' : `<select class="compact tstatus" data-id="${esc(t.id)}" aria-label="Status of ${esc(t.id)}">${['OPEN', 'IN_PROGRESS', 'RESOLVED'].map((s) => `<option value="${s}" ${t.status === s ? 'selected' : ''}>${nice(s)}</option>`).join('')}</select>`}</li>`).join('')}</ul>` : '<div class="empty">No support tickets.</div>'}</div>`;
      case 'details':
        return `<div class="plate-body"><dl class="kv">
          <dt>Customer ID</dt><dd class="mono">${esc(c.id)}</dd><dt>Name</dt><dd>${esc(c.name)}</dd><dt>Company</dt><dd>${esc(c.company || '—')}</dd>
          <dt>Phone</dt><dd class="mono">${esc(phone(c.phone) || '—')}</dd><dt>E-mail</dt><dd>${esc(c.email || '—')}</dd><dt>City</dt><dd>${esc(c.city || '—')}</dd>
          <dt>Came from</dt><dd>${esc(c.source || '—')}</dd><dt>Stage</dt><dd>${esc(c.stageLabel)}</dd>
          <dt>Consent</dt><dd>${c.consentEmail ? 'Agreed to e-mails' : 'No e-mail consent'} · ${c.doNotCall ? 'Do-Not-Call' : 'Calls allowed'}</dd>
          <dt>Notes</dt><dd class="prewrap">${esc(c.notes || '—')}</dd>
          <dt>Added</dt><dd>${esc(fmtDateTime(c.createdAt))}</dd><dt>Last change</dt><dd>${esc(fmtDateTime(c.updatedAt))}</dd>
          ${c.mergedFrom && c.mergedFrom.length ? `<dt>Merged from</dt><dd class="mono">${c.mergedFrom.map(esc).join(', ')}</dd>` : ''}</dl>
          <hr class="dash-rule">
          <h3 class="label-strip h-sm">Privacy – Digital Personal Data Protection Act, 2023</h3>
          <p class="small muted mt0">A customer may ask what we hold about them (right to access) or ask us to delete it (right to erasure).</p>
          <div class="row-actions"><a class="btn" href="/api/customers/${esc(c.id)}/privacy-export">${ICON.download} Download their data (JSON)</a>
          ${c.canErase ? '<button type="button" class="btn danger" data-act="erase">Erase personal data…</button>' : ''}</div></div>`;
      default:
        return `<div class="plate-body flush">${ro ? '' : '<div class="filters"><button type="button" class="btn sm brass" data-act="log">' + ICON.plus + ' Log contact</button></div>'}
          ${c.timeline.length ? `<ol class="calllog">${c.timeline.map((i) => `<li><span class="plug ${i.type === 'NOTE' ? 'NOTE' : esc(i.outcome)}" title="${esc(nice(i.type))}">${ICON[i.type] || ''}</span>
            <div class="slip"><div class="hdr"><b>${esc(nice(i.type))}${i.direction ? (i.direction === 'IN' ? ' · from customer' : ' · by us') : ' · internal'}</b>
            <span>${esc(fmtDateTime(i.at))}</span>${i.minutes ? `<span>${i.minutes} min</span>` : ''}<span>${esc(i.byName)}</span>
            ${i.type !== 'NOTE' ? `<span class="outcome ${esc(i.outcome)}">${esc(nice(i.outcome))}</span>` : ''}</div><p>${esc(i.summary)}</p></div></li>`).join('')}</ol>`
            : '<div class="empty">Nobody has talked to this customer yet.</div>'}</div>`;
    }
  }

  function bindCustomer(view, c) {
    const reload = async (msg) => { if (msg) toast(msg); await viewCustomer(freshView(), c.id, true); refreshCounts(); };
    view.addEventListener('click', async (e) => {
      const tab = e.target.closest('[data-tab]');
      if (tab) {
        state.detailTab = tab.dataset.tab;
        $$('[data-tab]', view).forEach((b) => b.setAttribute('aria-selected', b === tab));
        $('#tabBody').innerHTML = tabBody(c);
        return;
      }
      const act = e.target.closest('[data-act]');
      if (!act) return;
      const a = act.dataset.act;
      if (a === 'log') logContact(c, reload);
      if (a === 'followup') openModal('Add follow-up', `
          <label class="field"><span>What needs to be done?</span><input type="text" name="title" maxlength="120" required placeholder="Send revised quote"></label>
          <div class="two"><label class="field"><span>Due date</span><input type="date" name="due" value="${esc(todayStr())}" min="${esc(todayStr())}"></label>
          <div class="field"><span>Priority</span>${seg('priority', [['HIGH', 'High'], ['MEDIUM', 'Medium'], ['LOW', 'Low']], 'MEDIUM')}</div></div>
          ${isManager() ? `<label class="field"><span>Who will do it</span><select name="assigneeId">${staffOptions(c.ownerId, true)}</select></label>` : ''}`,
        async (f) => { await api('POST', `/api/customers/${c.id}/followups`, f); state.detailTab = 'followups'; await reload('Follow-up added.'); });
      if (a === 'order') openModal('Add order', `
          <label class="field"><span>What did they buy?</span><input type="text" name="item" maxlength="80" required list="items" placeholder="Rooftop solar 3 kW"></label>
          <datalist id="items">${['Rooftop solar 3 kW', 'Rooftop solar 5 kW', 'Inverter 1.5 kVA', 'Lithium battery 150 Ah', 'Annual maintenance (AMC)', 'Solar water heater 200 L', 'Panel cleaning visit', 'Net-meter installation'].map((x) => `<option value="${x}">`).join('')}</datalist>
          <div class="two"><label class="field"><span>Amount (₹)</span><input type="text" name="amount" inputmode="decimal" required placeholder="1,65,000"></label>
          <label class="field"><span>Date</span><input type="date" name="date" value="${esc(todayStr())}" max="${esc(todayStr())}"></label></div>
          <div class="field"><span>Status</span>${seg('status', [['PAID', 'Paid'], ['PENDING', 'Payment pending']], 'PAID')}</div>
          <div class="small muted">A lead or prospect who buys becomes a Customer automatically; 4 purchases in a year make them Loyal.</div>`,
        async (f) => { await api('POST', `/api/customers/${c.id}/orders`, f); state.detailTab = 'orders'; await reload('Order saved.'); });
      if (a === 'ticket') openModal('Open support ticket', `
          <label class="field"><span>What is the problem?</span><input type="text" name="subject" maxlength="120" required placeholder="Inverter showing error E-04"></label>
          <div class="field"><span>Priority</span>${seg('priority', [['HIGH', 'High – 24 h'], ['MEDIUM', 'Medium – 72 h'], ['LOW', 'Low – 5 days']], 'MEDIUM')}</div>
          <div class="small muted">The deadline (SLA) starts now. Tickets still open after it light a red lamp.</div>`,
        async (f) => { await api('POST', `/api/customers/${c.id}/tickets`, f); state.detailTab = 'tickets'; await reload('Ticket opened.'); });
      if (a === 'erase') openModal('Erase personal data', `
          <div class="error-box">This removes ${esc(c.name)}'s name, phone, e-mail, city, notes and the text of every conversation, follow-up and ticket.
          Orders are kept (amounts only) because the business must keep accounts. <b>This cannot be undone.</b></div>
          <label class="field"><span>Type the customer ID <b class="mono">${esc(c.id)}</b> to confirm</span><input type="text" name="confirm" autocomplete="off" required></label>`,
        async (f) => { await api('POST', `/api/customers/${c.id}/erase`, f); state.detailTab = 'details'; await reload('Personal data erased.'); },
        { submit: 'Erase for ever', danger: true });
    });
    view.addEventListener('change', async (e) => {
      try {
        if (e.target.classList.contains('fdone')) {
          await api('POST', `/api/followups/${e.target.dataset.id}/done`, { done: e.target.checked });
          await reload(e.target.checked ? 'Marked done.' : 'Opened again.');
        } else if (e.target.classList.contains('tstatus')) {
          await api('POST', `/api/tickets/${e.target.dataset.id}/status`, { status: e.target.value });
          await reload('Ticket updated.');
        } else if (e.target.classList.contains('ostatus')) {
          await api('POST', `/api/orders/${e.target.dataset.id}/status`, { status: e.target.value });
          await reload('Order updated.');
        }
      } catch (ex) { toast(ex.message, true); await reload(); }
    });
  }

  function logContact(c, reload) {
    openModal('Log a contact with ' + c.name, `
      <div class="field"><span>What kind?</span>${seg('type', [['CALL', 'Call'], ['EMAIL', 'E-mail'], ['MEETING', 'Meeting'], ['WHATSAPP', 'WhatsApp'], ['NOTE', 'Internal note']], 'CALL')}</div>
      <div class="field" id="dirField"><span>Who started it?</span>${seg('direction', [['OUT', 'We contacted them'], ['IN', 'They contacted us']], c.doNotCall ? 'IN' : 'OUT')}</div>
      <div class="note-box" id="consentHint" hidden></div>
      <label class="field"><span>What was said?</span><textarea name="summary" maxlength="1000" required placeholder="Asked about adding a battery; will send a quote by Friday."></textarea></label>
      <div class="field" id="outField"><span>How did it go?</span>${seg('outcome', [['POSITIVE', 'Well'], ['NEUTRAL', 'Okay'], ['NEGATIVE', 'Badly']], 'NEUTRAL')}</div>
      <div class="two"><label class="field"><span>When</span><input type="datetime-local" name="at" value="${esc(localNow())}" max="${esc(localNow())}"></label>
      <label class="field"><span>Minutes</span><input type="number" name="minutes" min="0" max="600" value="5"></label></div>`,
    async (f) => {
      if (f.type === 'NOTE') { delete f.direction; f.minutes = 0; }
      await api('POST', `/api/customers/${c.id}/interactions`, f);
      state.detailTab = 'log';
      await reload('Saved to the call log.');
    }, {
      onOpen: (form) => {
        const sync = () => {
          const f = formData(form);
          $('#dirField').hidden = f.type === 'NOTE'; $('#outField').hidden = f.type === 'NOTE';
          let hint = '';
          if (f.type === 'CALL' && f.direction === 'OUT' && c.doNotCall) hint = '⛔ ' + c.name + ' is on Do-Not-Call. An outgoing call cannot be logged.';
          if (f.type === 'EMAIL' && f.direction === 'OUT' && !c.consentEmail) hint = '⛔ ' + c.name + ' has not agreed to e-mails.';
          $('#consentHint').textContent = hint; $('#consentHint').hidden = !hint;
        };
        form.addEventListener('change', sync); sync();
      }
    });
  }

  // ================================================================== FOLLOW-UPS
  async function viewFollowUps(view, params) {
    const scope = params.scope === 'all' && isManager() ? 'all' : 'mine';
    const d = await api('GET', '/api/followups?scope=' + scope);
    document.title = 'Follow-ups – Sambandh CRM';
    const item = (f, kind) => `<li class="queue-item">
      <input type="checkbox" class="fdone tick-box lg" data-id="${esc(f.id)}" ${f.done ? 'checked' : ''} aria-label="Done: ${esc(f.title)}">
      <div class="grow"><div class="title${f.done ? ' struck' : ''}">${esc(f.title)}</div>
      <div class="meta"><a href="#/c/${esc(f.customerId)}">${esc(f.customerName)}</a>${f.company ? ' · ' + esc(f.company) : ''} ·
      ${kind === 'done' ? 'done ' + esc(ago(f.doneAt)) : kind === 'today' ? 'today' : esc(fmtDate(f.due))}${scope === 'all' ? ' · ' + esc(f.assigneeName) : ''}
      ${f.phone && !f.doNotCall ? ` · <a class="mono" href="tel:${esc(f.phone)}">${esc(phone(f.phone))}</a>` : f.doNotCall ? ' · <span class="flag dnc">DND</span>' : ''}</div></div>
      <span class="prio ${esc(f.priority)}">${esc(f.priority)}</span></li>`;
    const block = (title, lamp, list, kind, emptyText) => `<section class="plate screws queue"><div class="plate-head"><h2><span class="lamp ${lamp}"></span>${title}</h2><span class="mono">${list.length}</span></div>
      ${list.length ? `<ul class="list">${list.map((f) => item(f, kind)).join('')}</ul>` : `<div class="empty">${emptyText}</div>`}</section>`;
    view.innerHTML = `
      <div class="page-head"><div class="grow"><div class="eyebrow">Call-back queue</div><h1>Follow-ups</h1>
        <p>Tick a follow-up when it is done. Overdue ones lower the customer's health score.</p></div>
        ${isManager() ? `<div class="seg" role="group"><label><input type="radio" name="scope" value="mine" ${scope === 'mine' ? 'checked' : ''}><span>Mine</span></label><label><input type="radio" name="scope" value="all" ${scope === 'all' ? 'checked' : ''}><span>Whole team</span></label></div>` : ''}</div>
      <div class="grid g-2">
        <div class="stack">${block('Overdue', d.overdue.length ? 'r blink' : 'g', d.overdue, 'overdue', 'Nothing overdue.')}${block('Today', 'a', d.today, 'today', 'Nothing due today.')}</div>
        <div class="stack">${block('Coming up', '', d.upcoming, 'up', 'Nothing planned. Add follow-ups from a customer page.')}${block('Done this week', 'g', d.doneThisWeek, 'done', 'Nothing finished this week yet.')}</div>
      </div>`;
    view.addEventListener('change', async (e) => {
      if (e.target.name === 'scope') { location.hash = '#/followups' + (e.target.value === 'all' ? '?scope=all' : ''); return; }
      if (!e.target.classList.contains('fdone')) return;
      try {
        await api('POST', `/api/followups/${e.target.dataset.id}/done`, { done: e.target.checked });
        toast(e.target.checked ? 'Done – nice work.' : 'Opened again.');
        refreshCounts(); router();
      } catch (ex) { toast(ex.message, true); }
    });
  }

  // ================================================================== TICKETS
  async function viewTickets(view, params) {
    const status = params.status || 'open';
    const d = await api('GET', '/api/tickets?status=' + encodeURIComponent(status));
    document.title = 'Support tickets – Sambandh CRM';
    const left = (t) => {
      if (t.status === 'RESOLVED') return 'solved ' + ago(t.resolvedAt);
      const ms = t.deadline - Date.now();
      const h = Math.round(Math.abs(ms) / 3600000);
      const txt = h >= 48 ? Math.round(h / 24) + ' days' : h + ' h';
      return ms < 0 ? 'late by ' + txt : txt + ' left';
    };
    view.innerHTML = `
      <div class="page-head"><div class="grow"><div class="eyebrow">Complaints & service</div><h1>Support tickets</h1>
        <p>Deadlines (SLA): high priority 24 hours, medium 72 hours, low 5 days. Late tickets come first.</p></div></div>
      <section class="plate">
        <div class="filters"><div class="chips">${[['open', 'Open'], ['breached', 'Past deadline'], ['resolved', 'Solved'], ['all', 'All']].map(([v, l]) =>
          `<a class="chip nolink" href="#/tickets?status=${v}" aria-pressed="${status === v}">${l}</a>`).join('')}</div></div>
        ${d.tickets.length ? `<div class="table-wrap"><table class="directory"><thead><tr><th></th><th>Ticket</th><th>Customer</th><th>Priority</th><th>Deadline</th><th>Status</th></tr></thead><tbody>
        ${d.tickets.map((t) => `<tr><td><span class="lamp ${t.status === 'RESOLVED' ? 'g' : t.breached ? 'r blink' : 'a'}"></span></td>
          <td><b>${esc(t.subject)}</b><div class="sub">${esc(t.id)} · opened ${esc(ago(t.createdAt))}</div></td>
          <td><a href="#/c/${esc(t.customerId)}">${esc(t.customerName)}</a><div class="sub">${esc(t.ownerName)}</div></td>
          <td><span class="prio ${esc(t.priority)}">${esc(t.priority)}</span></td>
          <td class="nowrap ${t.breached ? 'trend-down' : ''}">${esc(left(t))}</td>
          <td><select class="compact tstatus" data-id="${esc(t.id)}" aria-label="Status of ${esc(t.id)}">${['OPEN', 'IN_PROGRESS', 'RESOLVED'].map((s) => `<option value="${s}" ${t.status === s ? 'selected' : ''}>${nice(s)}</option>`).join('')}</select></td></tr>`).join('')}
        </tbody></table></div>` : '<div class="empty"><span class="lamp g"></span>No tickets here.</div>'}
      </section>`;
    view.addEventListener('change', async (e) => {
      if (!e.target.classList.contains('tstatus')) return;
      try { await api('POST', `/api/tickets/${e.target.dataset.id}/status`, { status: e.target.value }); toast('Ticket updated.'); refreshCounts(); router(); }
      catch (ex) { toast(ex.message, true); }
    });
  }

  // ================================================================== DUPLICATES
  async function viewDuplicates(view) {
    const d = await api('GET', '/api/duplicates');
    document.title = 'Duplicates – Sambandh CRM';
    const card = (c, keep, idx) => `<div class="card ${keep ? 'keep' : ''}"><div class="small muted mono">${esc(c.id)} · ${keep ? 'KEEP' : 'merge into the other'}</div>
      <b><a href="#/c/${esc(c.id)}">${esc(c.name)}</a></b><div>${esc(c.company || '')}${c.company && c.city ? ' · ' : ''}${esc(c.city || '')}</div>
      <div class="mono small">${esc(phone(c.phone) || '—')}</div><div class="small">${esc(c.email || '—')}</div>
      <div class="small muted">${esc(c.stageLabel)} · ${esc(c.ownerName)} · ${c.records} record${c.records === 1 ? '' : 's'} · added ${esc(fmtDate(c.createdAt))}</div></div>`;
    view.innerHTML = `
      <div class="page-head"><div class="grow"><div class="eyebrow">Crossed lines</div><h1>Possible duplicates</h1>
        <p>The same customer saved twice splits their history in two. Merging moves every conversation, order, ticket and follow-up into one record.
        Consent takes the safer choice: e-mails only if <i>both</i> agreed, Do-Not-Call if <i>either</i> asked.</p></div></div>
      <section class="plate">${d.pairs.length ? d.pairs.map((p, i) => `<div class="pair" data-i="${i}">
        ${card(p.keep, true)}<div class="cord-link"><svg viewBox="0 0 70 40" aria-hidden="true"><path d="M2 20 C 20 0, 50 40, 68 20" fill="none" stroke="var(--cord)" stroke-width="4" stroke-linecap="round"/></svg>
        <span class="score">${p.score}%</span></div>${card(p.other, false)}
        <div class="pair-foot"><span class="small">${p.reasons.map((r) => `<span class="tag">${esc(r)}</span>`).join(' ')}</span>
        <span class="row-actions"><button type="button" class="btn sm" data-swap="${i}">Swap which to keep</button><button type="button" class="btn sm brass" data-merge="${i}">Merge…</button></span></div></div>`).join('')
        : '<div class="empty"><span class="lamp g"></span>No duplicates found. Every line is connected once.</div>'}</section>`;
    view.addEventListener('click', (e) => {
      const sw = e.target.closest('[data-swap]');
      if (sw) { const p = d.pairs[sw.dataset.swap]; [p.keep, p.other] = [p.other, p.keep]; viewDuplicatesRedraw(); return; }
      const m = e.target.closest('[data-merge]');
      if (!m) return;
      const p = d.pairs[m.dataset.merge];
      openModal('Merge duplicates', `<p class="m0">Merge <b>${esc(p.other.id)} – ${esc(p.other.name)}</b> into <b>${esc(p.keep.id)} – ${esc(p.keep.name)}</b>?</p>
        <ul class="small ul-tight"><li>${p.other.records} record(s) move to ${esc(p.keep.id)}.</li><li>Empty fields are filled in; a different phone or e-mail is kept in the notes.</li>
        <li>${esc(p.other.id)} is hidden afterwards (kept in the history).</li></ul>`,
      async () => {
        const r = await api('POST', '/api/merge', { keepId: p.keep.id, otherId: p.other.id });
        toast('Merged into ' + r.id + '.');
        location.hash = '#/c/' + r.id;
      }, { submit: 'Merge' });
    });
    function viewDuplicatesRedraw() {
      $$('.pair', view).forEach((el, i) => {
        const p = d.pairs[i];
        const cards = $$('.card', el);
        cards[0].outerHTML = card(p.keep, true);
        cards[1].outerHTML = card(p.other, false);
      });
    }
  }

  // ================================================================== IMPORT
  async function viewImport(view) {
    document.title = 'Import CSV – Sambandh CRM';
    view.innerHTML = `
      <div class="page-head"><div class="grow"><div class="eyebrow">Bulk connections</div><h1>Import customers</h1>
        <p>Save your Excel or Google Sheet as CSV, then choose the file or paste it below. First you see a preview – nothing is saved until you press Import.</p></div></div>
      <div class="grid g-detail">
        <section class="plate screws"><div class="plate-body form">
          <div class="row-actions"><label class="btn"><input type="file" id="file" accept=".csv,text/csv" class="sr">Choose CSV file…</label>
            <button type="button" class="btn ghost" id="sample">Use example</button></div>
          <label class="field"><span>CSV text</span><textarea id="csv" rows="9" class="mono" spellcheck="false" placeholder="name,company,email,phone,city,..."></textarea></label>
          <label class="check"><input type="checkbox" id="skip" checked><span>Skip rows that look like an existing customer (possible duplicates)</span></label>
          <div class="row-actions"><button type="button" class="btn brass" id="preview">Preview</button><button type="button" class="btn primary" id="commit" disabled>Import</button></div>
        </div></section>
        <aside class="plate screws"><div class="plate-head"><h2 class="label-strip">Columns understood</h2></div>
          <div class="plate-body small"><p class="mt0"><b>name</b> (needed), company, email, phone, city, type (Individual / Business), source, stage (Lead, Prospect, Customer, Loyal, Churned), tags (separate with ;), owner (username), notes, consent_email (yes/no), do_not_call (yes/no).</p>
          <p class="muted m0">Up to 2,000 rows (1 MB). Rows with mistakes are skipped and the reason is shown.</p></div></aside>
      </div>
      <section class="plate mt18" id="result" hidden></section>`;
    const csv = $('#csv');
    $('#sample').addEventListener('click', async () => { csv.value = (await api('GET', '/api/import/sample')).csv; $('#commit').disabled = true; });
    $('#file').addEventListener('change', (e) => {
      const f = e.target.files[0];
      if (!f) return;
      if (f.size > 1000000) { toast('That file is bigger than 1 MB.', true); return; }
      const r = new FileReader();
      r.onload = () => { csv.value = r.result; $('#commit').disabled = true; toast('Loaded ' + f.name + '. Press Preview.'); };
      r.readAsText(f);
    });
    csv.addEventListener('input', () => { $('#commit').disabled = true; });
    $('#preview').addEventListener('click', async () => {
      try {
        const d = await api('POST', '/api/import/preview', { csv: csv.value });
        const res = $('#result'); res.hidden = false;
        res.innerHTML = `<div class="plate-head"><h2 class="label-strip">Preview · ${d.total} rows</h2>
          <span class="small"><span class="status-dot ok">${d.ok} ready</span> · <span class="status-dot warning">${d.warnings} check</span> · <span class="status-dot error">${d.errors} skipped</span></span></div>
          <div class="table-wrap"><table class="directory"><thead><tr><th>Line</th><th>Status</th><th>Name</th><th class="hide-sm">Phone / e-mail</th><th>Stage</th><th>Messages</th></tr></thead><tbody>
          ${d.rows.map((r) => `<tr><td class="mono">${r.line}</td><td><span class="status-dot ${r.status}">${r.status === 'ok' ? 'ready' : r.status === 'warning' ? 'check' : 'skip'}</span></td>
            <td><b>${esc(r.name || '—')}</b><div class="sub">${esc(r.company || '')} ${esc(r.city || '')}</div></td>
            <td class="hide-sm small">${esc(r.phone || '')}<br>${esc(r.email || '')}</td><td>${esc(r.stage || 'Lead')}</td>
            <td>${r.messages.length ? `<ul class="msgs">${r.messages.map((m) => `<li>${esc(m)}</li>`).join('')}</ul>` : '<span class="muted small">—</span>'}</td></tr>`).join('')}</tbody></table></div>`;
        $('#commit').disabled = d.ok + d.warnings === 0;
      } catch (ex) { toast(ex.message, true); }
    });
    $('#commit').addEventListener('click', async () => {
      try {
        const r = await api('POST', '/api/import/commit', { csv: csv.value, skipDuplicates: $('#skip').checked });
        toast(`Imported ${r.added}, skipped ${r.skipped}.`);
        location.hash = '#/customers?sort=new';
      } catch (ex) { toast(ex.message, true); }
    });
  }

  // ================================================================== ACTIVITY LOG
  async function viewActivity(view, params) {
    const d = await api('GET', '/api/audit?q=' + encodeURIComponent(params.q || ''));
    document.title = 'Activity log – Sambandh CRM';
    view.innerHTML = `
      <div class="page-head"><div class="grow"><div class="eyebrow">Exchange ledger</div><h1>Activity log</h1>
        <p>Who did what, and when – sign-ins, changes, exports, merges and erasures. Newest first.</p></div>
        <form id="af" class="dial w320">${ICON.search}<input type="search" name="q" value="${esc(params.q || '')}" placeholder="Filter: merge, C-1004, Kavya…"></form></div>
      <section class="plate"><div class="table-wrap"><table class="directory"><thead><tr><th>When</th><th>Who</th><th>Action</th><th>On</th><th>Detail</th></tr></thead><tbody>
      ${d.entries.map((a) => `<tr><td class="nowrap small">${esc(fmtDateTime(a.at))}</td><td>${esc(a.userName)}</td><td class="mono small">${esc(a.action)}</td>
        <td>${/^C-\d+$/.test(a.target || '') ? `<a href="#/c/${esc(a.target)}">${esc(a.target)}</a>` : esc(a.target || '')}</td><td class="small">${esc(a.detail || '')}</td></tr>`).join('')}
      </tbody></table>${d.entries.length ? '' : '<div class="empty">Nothing found.</div>'}</div></section>`;
    $('#af').addEventListener('submit', (e) => { e.preventDefault(); location.hash = '#/activity?q=' + encodeURIComponent(e.target.q.value.trim()); });
  }

  // ================================================================== STAFF (admin)
  async function viewTeam(view) {
    const d = await api('GET', '/api/users');
    state.staff = d.users;
    document.title = 'Staff accounts – Sambandh CRM';
    view.innerHTML = `
      <div class="page-head"><div class="grow"><div class="eyebrow">Operators</div><h1>Staff accounts</h1>
        <p>Agents see only their own customers. Managers see everyone, re-assign, import and merge. Admins also manage accounts and erase personal data.</p></div>
        <button type="button" class="btn primary" id="addUser">${ICON.plus} Add staff member</button></div>
      <section class="plate"><div class="table-wrap"><table class="directory"><thead><tr><th>Name</th><th>Role</th><th class="num">Customers</th><th>Status</th><th></th></tr></thead><tbody>
      ${d.users.map((u) => `<tr><td><span class="person"><span class="badge-ini">${esc(u.initials)}</span><span><b>${esc(u.name)}</b><div class="sub mono">${esc(u.username)} · ${esc(u.id)}</div></span></span></td>
        <td><select class="compact urole" data-id="${esc(u.id)}" ${u.id === state.me.id ? 'disabled' : ''} aria-label="Role of ${esc(u.name)}">${['AGENT', 'MANAGER', 'ADMIN'].map((r) => `<option value="${r}" ${u.role === r ? 'selected' : ''}>${r === 'AGENT' ? 'Sales agent' : nice(r)}</option>`).join('')}</select></td>
        <td class="num">${u.customers}</td>
        <td>${u.locked ? '<span class="flag dnc">Locked</span>' : u.active ? '<span class="flag mail">Active</span>' : '<span class="flag erased">Switched off</span>'}</td>
        <td><span class="row-actions">${u.locked ? `<button type="button" class="btn sm" data-unlock="${esc(u.id)}">Unlock</button>` : ''}
          <button type="button" class="btn sm" data-pw="${esc(u.id)}">New password</button>
          ${u.id === state.me.id ? '' : `<button type="button" class="btn sm ${u.active ? 'danger' : ''}" data-active="${esc(u.id)}" data-to="${!u.active}">${u.active ? 'Switch off' : 'Switch on'}</button>`}</span></td></tr>`).join('')}
      </tbody></table></div></section>`;
    const upd = async (id, body, msg) => { try { await api('PUT', '/api/users/' + id, body); toast(msg); state.staff = []; router(); } catch (ex) { toast(ex.message, true); router(); } };
    view.addEventListener('change', (e) => { if (e.target.classList.contains('urole')) upd(e.target.dataset.id, { role: e.target.value }, 'Role changed.'); });
    view.addEventListener('click', (e) => {
      const t = e.target.closest('button');
      if (!t) return;
      if (t.id === 'addUser') openModal('Add staff member', `
        <div class="two"><label class="field"><span>Full name</span><input type="text" name="name" required maxlength="60"></label>
        <label class="field"><span>Username</span><input type="text" name="username" required maxlength="30" autocomplete="off"></label></div>
        <div class="field"><span>Role</span>${seg('role', [['AGENT', 'Sales agent'], ['MANAGER', 'Manager'], ['ADMIN', 'Admin']], 'AGENT')}</div>
        <label class="field"><span>First password <small>(8+ characters, a letter and a number)</small></span><input type="password" name="password" required autocomplete="new-password"></label>`,
      async (f) => { await api('POST', '/api/users', f); toast('Account created.'); state.staff = []; router(); });
      if (t.dataset.unlock) upd(t.dataset.unlock, { unlock: true }, 'Account unlocked.');
      if (t.dataset.active) upd(t.dataset.active, { active: t.dataset.to === 'true' }, t.dataset.to === 'true' ? 'Switched on.' : 'Switched off – they are signed out.');
      if (t.dataset.pw) openModal('Set a new password', `<label class="field"><span>New password</span><input type="password" name="password" required autocomplete="new-password"></label>
        <div class="small muted">They will be signed out everywhere and must use the new password.</div>`,
      async (f) => { await api('PUT', '/api/users/' + t.dataset.pw, f); toast('Password changed.'); });
    });
  }

  // ================================================================== MY ACCOUNT
  async function viewAccount(view) {
    document.title = 'My account – Sambandh CRM';
    view.innerHTML = `
      <div class="page-head"><div class="grow"><div class="eyebrow">Operator</div><h1>${esc(state.me.name)}</h1><p>${esc(state.me.roleLabel)} · username <span class="mono">${esc(state.me.username)}</span></p></div></div>
      <form class="plate screws w520" id="pwf"><div class="plate-head"><h2 class="label-strip">Change my password</h2></div>
        <div class="plate-body form">
        <label class="field"><span>Current password</span><input type="password" name="current" autocomplete="current-password" required></label>
        <label class="field"><span>New password <small>(8+ characters, a letter and a number)</small></span><input type="password" name="next" autocomplete="new-password" required></label>
        <div class="error-box" id="pwerr" hidden></div><div><button class="btn primary" type="submit">Change password</button></div></div></form>`;
    $('#pwf').addEventListener('submit', async (e) => {
      e.preventDefault();
      try { await api('POST', '/api/me/password', formData(e.target)); e.target.reset(); toast('Password changed.'); $('#pwerr').hidden = true; }
      catch (ex) { $('#pwerr').textContent = ex.message; $('#pwerr').hidden = false; }
    });
  }

  // ------------------------------------------------------------------ start
  async function startApp() {
    renderShell();
    state.staff = [];
    if (!location.hash) location.hash = '#/';
    await router();
    if (location.hash !== '#/') refreshCounts();
  }

  window.addEventListener('hashchange', () => { if (state.me) { router().then(() => { const v = $('#view'); if (v) v.focus({ preventScroll: true }); window.scrollTo(0, 0); }); } });

  (async function boot() {
    try {
      const r = await api('GET', '/api/me');
      state.meta = r.meta;
      if (r.user) { state.me = r.user; await startApp(); } else renderLogin();
    } catch (e) { renderLogin(e.message); }
  })();
})();
