/* Bijak – Invoice Management System. Plain JavaScript, no framework.
   Pages are built with h() (document.createElement) – never innerHTML – so text typed by people can never run as code.
   Every page is a function that fetches JSON from the Java backend and draws the result.
   All money from the server is in PAISE (whole numbers); rs() turns it into "12,80,500.00". */
(function () {
  'use strict';

  // ================================================================== state and helpers

  const S = { user: null, meta: null, person: {}, cust: {}, item: {} };
  const $app = document.getElementById('app');
  const $modal = document.getElementById('modal-root');
  const $toast = document.getElementById('toast');

  /** h('div', {class: 'x', onclick: f}, 'text', child, [more]) – builds DOM safely. */
  function h(tag, attrs, ...kids) {
    const el = document.createElement(tag);
    if (attrs) for (const [k, v] of Object.entries(attrs)) {
      if (v === null || v === undefined || v === false) continue;
      if (k === 'class') el.className = v;
      else if (k === 'style') for (const [sk, sv2] of Object.entries(v)) el.style.setProperty(sk, sv2);
      else if (k.startsWith('on')) el.addEventListener(k.slice(2), v);
      else if (k === 'value') el.value = v;
      else if (k === 'checked' || k === 'selected' || k === 'disabled') el[k] = !!v;
      else el.setAttribute(k, v === true ? '' : v);
    }
    add(el, kids);
    return el;
  }
  function add(el, kids) {
    for (const k of kids) {
      if (k === null || k === undefined || k === false) continue;
      if (Array.isArray(k)) add(el, k);
      else el.appendChild(k instanceof Node ? k : document.createTextNode(String(k)));
    }
    return el;
  }
  const SVGNS = 'http://www.w3.org/2000/svg';
  function sv(tag, attrs, ...kids) {
    const el = document.createElementNS(SVGNS, tag);
    if (attrs) for (const [k, v] of Object.entries(attrs)) if (v !== null && v !== undefined) el.setAttribute(k, v);
    for (const k of kids) if (k !== null && k !== undefined) el.appendChild(k instanceof Node ? k : document.createTextNode(String(k)));
    return el;
  }
  function clear(el) { while (el.firstChild) el.removeChild(el.firstChild); return el; }
  /** Empties the element and adds the children (null and false are skipped). */
  function fill(el, ...kids) { return add(clear(el), kids); }

  async function api(method, path, body) {
    const opt = { method, headers: { 'X-Bijak': '1' }, credentials: 'same-origin' };
    if (body !== undefined) { opt.headers['Content-Type'] = 'application/json'; opt.body = JSON.stringify(body); }
    else if (method !== 'GET') { opt.headers['Content-Type'] = 'application/json'; opt.body = '{}'; }
    let r;
    try { r = await fetch(path, opt); } catch (e) { throw Object.assign(new Error('Cannot reach the server. Is it still running?'), { status: 0 }); }
    let data = null;
    try { data = await r.json(); } catch (e) { /* empty */ }
    if (r.status === 401 && path !== '/api/login') { S.user = null; showLogin(); throw Object.assign(new Error('Please sign in.'), { status: 401 }); }
    if (!r.ok) throw Object.assign(new Error((data && data.error) || ('Error ' + r.status)), { status: r.status, data: data && data.data });
    return data;
  }
  const get = (p) => api('GET', p);
  const post = (p, b) => api('POST', p, b);
  const put = (p, b) => api('PUT', p, b);
  const del = (p) => api('DELETE', p);
  const qs = (o) => { const p = new URLSearchParams(); for (const [k, v] of Object.entries(o)) if (v !== undefined && v !== null && v !== '') p.set(k, v); const s = p.toString(); return s ? '?' + s : ''; };

  let toastTimer = null;
  function toast(msg, bad) {
    $toast.textContent = msg;
    $toast.className = 'toast' + (bad ? ' bad' : '');
    $toast.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => { $toast.hidden = true; }, bad ? 6000 : 3200);
  }
  /** Runs an action; shows its error as a red toast (or in `errEl`). Returns the result, or throws. */
  async function act(fn, okMsg, errEl) {
    try {
      const r = await fn();
      if (okMsg) toast(okMsg);
      if (errEl) errEl.textContent = '';
      return r;
    } catch (e) {
      if (e.status !== 401) { if (errEl) errEl.textContent = e.message; else toast(e.message, true); }
      throw e;
    }
  }

  function setMeta(meta) {
    S.meta = meta;
    S.person = {}; S.cust = {}; S.item = {};
    for (const p of meta.people) S.person[p.id] = p;
    for (const c of meta.customers) S.cust[c.id] = c;
    for (const i of meta.items) S.item[i.id] = i;
  }
  async function reloadMeta() { setMeta(await get('/api/meta')); paintCounts(); }

  const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
  function fmtDate(v) {
    if (!v) return '–';
    const d = typeof v === 'number' ? new Date(v) : new Date(v + (String(v).length === 10 ? 'T00:00:00' : ''));
    return d.getDate() + ' ' + MONTHS[d.getMonth()] + ' ' + d.getFullYear();
  }
  function fmtShort(v) {
    if (!v) return '–';
    const d = typeof v === 'number' ? new Date(v) : new Date(v + 'T00:00:00');
    return d.getDate() + ' ' + MONTHS[d.getMonth()];
  }
  function fmtTime(ms) {
    if (!ms) return '–';
    const d = new Date(ms);
    return fmtShort(ms) + ', ' + String(d.getHours()).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0');
  }
  function ago(ms) {
    const s = (Date.now() - ms) / 1000;
    if (s < 90) return 'just now';
    if (s < 3600) return Math.floor(s / 60) + ' min ago';
    if (s < 86400) return Math.floor(s / 3600) + ' h ago';
    const d = Math.floor(s / 86400);
    return d === 1 ? 'yesterday' : d < 30 ? d + ' days ago' : fmtDate(ms);
  }
  /** Indian digit groups: 1280500 -> 12,80,500 */
  function group(n) {
    const s = String(n);
    if (s.length <= 3) return s;
    return s.slice(0, -3).replace(/\B(?=(\d{2})+(?!\d))/g, ',') + ',' + s.slice(-3);
  }
  /** paise -> "12,80,500.75" */
  function rs(p) {
    if (p === null || p === undefined) return '–';
    const neg = p < 0, a = Math.abs(Math.round(p));
    return (neg ? '-' : '') + group(Math.floor(a / 100)) + '.' + String(a % 100).padStart(2, '0');
  }
  /** paise -> "₹12,80,501" (rounded, for summaries) */
  function rs0(p) {
    if (p === null || p === undefined) return '–';
    const r = Math.round(p / 100);
    return (r < 0 ? '-₹' : '₹') + group(Math.abs(r));
  }
  const plain = (p) => (p / 100).toFixed(2);                 // for input boxes: 1280500.75
  const qtyText = (m) => { const v = m / 1000; return Number.isInteger(v) ? String(v) : String(+v.toFixed(3)); };
  const pct = (bp) => { const v = bp / 100; return (Number.isInteger(v) ? v : v.toFixed(2)) + '%'; };
  const plural = (n, w) => n + ' ' + w + (n === 1 ? '' : 's');
  const name = (id) => (id === 'system' ? 'Bijak (automatic)' : S.person[id] ? S.person[id].name : 'someone');
  const first = (id) => name(id).split(' ')[0];
  const STATUS = { DRAFT: 'Draft', PENDING_APPROVAL: 'Waiting for approval', ISSUED: 'Issued', PART_PAID: 'Part paid', PAID: 'Paid',
    CANCELLED: 'Cancelled', APPLIED: 'Used up' };
  const today = () => S.meta.today;
  const money = () => S.user && S.user.money;
  const writes = () => S.user && S.user.writes;

  // ================================================================== small components

  const ICONS = {
    today: 'M4 5h16v15H4zM4 10h16M9 3v4M15 3v4',
    inv: 'M7 3h10v18l-2.5-1.5L12 21l-2.5-1.5L7 21zM10 8h4M10 12h4M10 16h2',
    plus: 'M12 5v14M5 12h14',
    cust: 'M8 11a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM2 20c0-3.3 2.7-6 6-6s6 2.7 6 6M16 11a3 3 0 1 0 0-6M22 20c0-3-2-5.4-4.6-5.9',
    pay: 'M3 7h18v10H3zM3 11h18M7 15h3',
    bank: 'M3 10l9-6 9 6M5 10v8M9.5 10v8M14.5 10v8M19 10v8M3 20h18',
    rep: 'M4 12a8 8 0 0 1 13.7-5.7L20 9M20 4v5h-5M20 12a8 8 0 0 1-13.7 5.7L4 15M4 20v-5h5',
    mail: 'M3 6h18v12H3zM3 6l9 7 9-7',
    chart: 'M4 20V10M10 20V4M16 20v-7M22 20H2',
    box: 'M3 7l9-4 9 4-9 4zM3 7v10l9 4 9-4V7M12 11v10',
    desk: 'M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM19.4 15a1.6 1.6 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.6 1.6 0 0 0-2.7 1.1V21a2 2 0 1 1-4 0v-.1a1.6 1.6 0 0 0-2.7-1.1l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1A1.6 1.6 0 0 0 3.6 15H3a2 2 0 1 1 0-4h.1a1.6 1.6 0 0 0 1.1-2.7l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1A1.6 1.6 0 0 0 9.7 4.4V3a2 2 0 1 1 4 0v.1a1.6 1.6 0 0 0 2.7 1.1l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.6 1.6 0 0 0 1.1 2.7H21a2 2 0 1 1 0 4h-.1',
    log: 'M4 6h16M4 12h16M4 18h10',
    menu: 'M4 6h16M4 12h16M4 18h16'
  };
  function icon(n) { return sv('svg', { viewBox: '0 0 24 24', 'aria-hidden': 'true' }, sv('path', { d: ICONS[n] })); }

  /** The bill book's own seal (used on the spine and the cover). */
  function seal() {
    return sv('svg', { class: 'seal', viewBox: '0 0 40 40', 'aria-hidden': 'true' },
      sv('rect', { x: 6, y: 3, width: 28, height: 34, fill: 'var(--paper)' }),
      sv('path', { d: 'M6 3v34', stroke: 'var(--cloth)', 'stroke-width': 2.5, 'stroke-dasharray': '2.5 2.5' }),
      sv('path', { d: 'M12 12h16M12 18h16M12 24h10', stroke: 'var(--ink)', 'stroke-width': 2.2 }),
      sv('circle', { cx: 28, cy: 30, r: 3.2, fill: 'var(--red)' }));
  }

  function status(r) {
    const els = [];
    if (r.overdue) els.push(h('span', { class: 'st OVERDUE', title: 'Due ' + fmtDate(r.dueDate) }, r.daysLate + ' d overdue'));
    else els.push(h('span', { class: 'st ' + r.status }, r.kind === 'CN' && r.status === 'ISSUED' ? 'Credit left' : STATUS[r.status] || r.status));
    if (r.onHold) els.push(' ', h('span', { class: 'st HOLD' }, 'On hold'));
    return els;
  }
  function billno(r) {
    if (r.number) return h('span', { class: 'billno' }, r.number);
    return h('span', { class: 'billno draft' }, 'no number yet');
  }
  function empty(title, text, action) { return h('div', { class: 'empty' }, h('b', null, title), h('div', null, text), action ? h('div', { style: { 'margin-top': '14px' } }, action) : null); }
  function field(label, input, hint) { return h('label', { class: 'f' }, h('span', null, label, hint ? h('small', null, ' – ', hint) : null), input); }
  function select(options, value, attrs) {
    return h('select', attrs || null, options.map(([v, t]) => h('option', { value: v, selected: String(v) === String(value) }, t)));
  }
  function custOptions(withBlank, onlyActive) {
    const list = Object.values(S.cust).filter((c) => !onlyActive || c.active).sort((a, b) => a.name.localeCompare(b.name));
    return (withBlank ? [['', withBlank]] : []).concat(list.map((c) => [c.id, c.name]));
  }
  function stateName(code) { return (S.meta.states[code] || code) + ' (' + code + ')'; }

  function modal(content, wide) {
    const close = () => { clear($modal); document.removeEventListener('keydown', esc); };
    const esc = (e) => { if (e.key === 'Escape') close(); };
    const box = h('div', { class: 'modal' + (wide ? ' wide' : ''), role: 'dialog', 'aria-modal': 'true' });
    const bg = h('div', { class: 'modal-bg', onmousedown: (e) => { if (e.target === bg) close(); } }, box);
    add(box, [typeof content === 'function' ? content(close) : content]);
    clear($modal).appendChild(bg);
    document.addEventListener('keydown', esc);
    const f = box.querySelector('input, textarea, select');
    if (f) f.focus();
    return close;
  }
  /** A small "why?" box: asks for one line of text and calls `fn(text)`. */
  function ask(title, text, label, button, fn, danger) {
    modal((close) => {
      const inp = h('textarea', { rows: 3 });
      const err = h('div', { class: 'err' });
      return h('div', null, h('h2', null, title), text ? h('p', { class: 'muted' }, text) : null, field(label, inp), err,
        h('div', { class: 'row-btns' }, h('button', { onclick: close }, 'Back'),
          h('button', { class: danger ? 'danger' : 'main', onclick: async () => { await act(() => fn(inp.value.trim()), null, err); close(); } }, button)));
    });
  }

  /**
   * The perforator mark: words punched as holes, like the machine that cancels cheques.
   * A 5x7 dot font, drawn as small circles in an SVG.
   */
  const DOTS = {
    P: ['11110', '10001', '10001', '11110', '10000', '10000', '10000'], A: ['01110', '10001', '10001', '11111', '10001', '10001', '10001'],
    I: ['11111', '00100', '00100', '00100', '00100', '00100', '11111'], D: ['11110', '10001', '10001', '10001', '10001', '10001', '11110'],
    C: ['01111', '10000', '10000', '10000', '10000', '10000', '01111'], N: ['10001', '11001', '10101', '10011', '10001', '10001', '10001'],
    E: ['11111', '10000', '10000', '11110', '10000', '10000', '11111'], L: ['10000', '10000', '10000', '10000', '10000', '10000', '11111'],
    R: ['11110', '10001', '10001', '11110', '10100', '10010', '10001'], F: ['11111', '10000', '10000', '11110', '10000', '10000', '10000'],
    T: ['11111', '00100', '00100', '00100', '00100', '00100', '00100'], O: ['01110', '10001', '10001', '10001', '10001', '10001', '01110'],
    H: ['10001', '10001', '10001', '11111', '10001', '10001', '10001'], U: ['10001', '10001', '10001', '10001', '10001', '10001', '01110'],
    S: ['01111', '10000', '10000', '01110', '00001', '00001', '11110'], ' ': ['000', '000', '000', '000', '000', '000', '000']
  };
  function punch(word, cls) {
    const kids = [];
    let x = 0;
    for (const ch of word) {
      const g = DOTS[ch] || DOTS[' '];
      for (let r = 0; r < 7; r++) for (let c = 0; c < g[r].length; c++) if (g[r][c] === '1') kids.push(sv('circle', { cx: x + c * 6 + 3, cy: r * 6 + 3, r: 2.3 }));
      x += g[0].length * 6 + 6;
    }
    return sv('svg', { class: 'punch ' + (cls || ''), viewBox: `0 0 ${x} 42`, role: 'img', 'aria-label': word }, ...kids);
  }

  // ================================================================== the shell (spine + top bar)

  const NAV = [
    ['today', '#/', 'Today'],
    ['inv', '#/invoices', 'Invoices'],
    ['plus', '#/new', 'New invoice', () => writes()],
    ['cust', '#/customers', 'Customers'],
    ['pay', '#/payments', 'Payments'],
    ['bank', '#/bank', 'Bank matching', () => money() || S.user.role === 'AUDITOR', 'bank'],
    ['rep', '#/repeating', 'Repeating invoices'],
    ['mail', '#/outbox', 'Outbox'],
    ['chart', '#/reports', 'Reports and GST'],
    ['box', '#/items', 'Items'],
    ['desk', '#/desk', 'Owner’s desk', () => money() || S.user.role === 'AUDITOR'],
    ['log', '#/activity', 'Activity log', () => money() || S.user.role === 'AUDITOR']
  ];
  let $nav, $view, $shell, $topTitle;

  function paintCounts() {
    if (!$nav || !S.meta) return;
    const c = S.meta.counts || {};
    for (const a of $nav.querySelectorAll('a')) {
      const old = a.querySelector('.count');
      if (old) old.remove();
      let n = 0;
      if (a.dataset.k === 'today') n = (c.approvals || 0) + (c.sentBack || 0);
      if (a.dataset.k === 'bank') n = c.bank || 0;
      if (n > 0) a.appendChild(h('span', { class: 'count', title: n + ' waiting' }, n));
    }
  }

  function themeNow() {
    const t = document.documentElement.getAttribute('data-theme');
    if (t) return t;
    return window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
  }
  function toggleTheme(btn) {
    const next = themeNow() === 'dark' ? 'light' : 'dark';
    document.documentElement.setAttribute('data-theme', next);
    try { localStorage.setItem('bijak-theme', next); } catch (e) { /* storage blocked */ }
    if (btn) btn.textContent = next === 'dark' ? 'Bill book' : 'Carbon paper';
  }

  function shell() {
    $nav = h('nav', { class: 'nav', 'aria-label': 'Main' });
    for (const [ic, href, label, show, k] of NAV) {
      if (show && !show()) continue;
      $nav.appendChild(h('a', { href, 'data-k': k || (href === '#/' ? 'today' : ''), onclick: () => $shell.classList.remove('open') }, icon(ic), label));
    }
    const u = S.user;
    const themeBtn = h('button', { class: 'quiet', onclick: (e) => toggleTheme(e.currentTarget), title: 'Switch between light and dark' },
      themeNow() === 'dark' ? 'Bill book' : 'Carbon paper');
    const spine = h('aside', { class: 'spine' },
      h('div', { class: 'brand' }, seal(), h('div', null, h('b', null, 'Bijak'), h('small', null, S.meta.company.name.replace(' Pvt. Ltd.', '')))),
      $nav,
      h('div', { class: 'me' }, h('b', null, u.name), h('small', null, u.roleLabel),
        h('div', { class: 'row-btns' }, themeBtn, h('button', { class: 'quiet', onclick: changePassword }, 'Password'),
          h('button', { class: 'quiet', onclick: logout }, 'Sign out'))));
    $topTitle = h('div', { class: 'muted' });
    const search = h('input', { class: 'search', type: 'search', placeholder: 'Find an invoice, customer or amount', 'aria-label': 'Search invoices',
      onkeydown: (e) => { if (e.key === 'Enter' && search.value.trim()) { location.hash = '#/invoices?q=' + encodeURIComponent(search.value.trim()); search.value = ''; } } });
    $view = h('main', { class: 'page', id: 'view', tabindex: '-1' });
    $shell = h('div', { class: 'shell' }, spine,
      h('div', { class: 'main' },
        h('div', { class: 'topbar' }, h('button', { class: 'quiet menu-btn', 'aria-label': 'Menu', onclick: () => $shell.classList.toggle('open') }, icon('menu')),
          $topTitle, search),
        $view));
    clear($app).appendChild($shell);
    paintCounts();
  }

  async function logout() {
    await post('/api/logout').catch(() => null);
    S.user = null;
    showLogin();
  }

  function changePassword() {
    modal((close) => {
      const cur = h('input', { type: 'password', autocomplete: 'current-password' });
      const nx = h('input', { type: 'password', autocomplete: 'new-password' });
      const err = h('div', { class: 'err' });
      return h('div', null, h('h2', null, 'Change your password'),
        h('div', { class: 'stack' }, field('Current password', cur), field('New password', nx, '8 or more, with a letter and a number')), err,
        h('div', { class: 'row-btns' }, h('button', { onclick: close }, 'Back'),
          h('button', { class: 'main', onclick: async () => {
            await act(() => post('/api/me/password', { current: cur.value, next: nx.value }), 'Password changed. Please sign in again.', err);
            close(); showLogin();
          } }, 'Change password')));
    });
  }

  // ================================================================== sign-in: the cover of the bill book

  function showLogin() {
    const user = h('input', { autocomplete: 'username', required: true });
    const pass = h('input', { type: 'password', autocomplete: 'current-password', required: true });
    const err = h('div', { class: 'err' });
    const go = async (e) => {
      if (e) e.preventDefault();
      try {
        const r = await post('/api/login', { username: user.value, password: pass.value });
        S.user = r.user;
        setMeta(r.meta);
        shell();
        route();
      } catch (ex) { err.textContent = ex.message; }
    };
    const demo = (u, p, who) => h('button', { type: 'button', onclick: () => { user.value = u; pass.value = p; go(); } }, who);
    clear($app).appendChild(h('div', { class: 'cover-wrap' },
      h('div', { class: 'cover' },
        h('div', { class: 'board' },
          h('div', { class: 'brand', style: { padding: '0' } }, seal()),
          h('div', { class: 'label-plate' }, h('div', { class: 'deva', lang: 'hi' }, 'बीजक'), h('h1', null, 'Bijak'),
            h('p', null, 'Tax invoices, repeating bills, payment reminders and bank matching – in one bill book.')),
          h('div', { class: 'bookno' }, 'Invoice book for the year ', h('b', null, '26-27'))),
        h('div', { class: 'inside' },
          h('h2', null, 'Sign in'),
          h('p', { class: 'muted' }, 'Koel Valley Packaging Pvt. Ltd. (a made-up company for the demo)'),
          h('form', { onsubmit: go }, field('Username', user), field('Password', pass), err,
            h('button', { class: 'main', type: 'submit' }, 'Sign in')),
          h('div', { class: 'demo' }, 'Try it as:',
            h('div', { class: 'row-btns' }, demo('meera', 'owner123', 'Meera – owner'), demo('arjun', 'accounts123', 'Arjun – accountant'),
              demo('salma', 'sales123', 'Salma – sales'), demo('vikram', 'audit123', 'Vikram – auditor (CA)')))))));
    user.focus();
  }

  // ================================================================== Today (the dashboard)

  async function pageToday() {
    const d = await get('/api/dashboard');
    S.meta.counts = d.counts;
    paintCounts();
    const v = [];
    v.push(h('div', { class: 'page-head' },
      h('div', null, h('h1', null, 'Good ' + (new Date().getHours() < 12 ? 'morning' : new Date().getHours() < 17 ? 'afternoon' : 'evening') + ', ' + S.user.name.split(' ')[0]),
        h('div', { class: 'sub' }, 'Here is what is owed, what came in and what needs you today, ' + fmtDate(today()) + '.')),
      h('div', { class: 'row-btns' },
        money() ? h('button', { onclick: async (e) => {
          e.currentTarget.disabled = true;
          const r = await act(() => post('/api/admin/tick'));
          toast(r.invoices.length || r.reminders ? 'Made ' + plural(r.invoices.length, 'invoice') + ', sent ' + plural(r.reminders, 'reminder') + '.' : 'Nothing was due – everything is up to date.');
          route();
        }, title: 'Runs by itself every 10 minutes' }, icon('rep'), 'Run automation now') : null,
        writes() ? h('a', { class: 'btn main', href: '#/new' }, 'New invoice') : null)));

    v.push(h('div', { class: 'stubs' },
      stub('Owed to us', rs0(d.owes), plural(d.bucketCount.reduce((a, b) => a + b, 0), 'open invoice'), '#/invoices?status=OPEN'),
      stub('Overdue', rs0(d.overdue), d.owes ? Math.round(d.overdue * 100 / d.owes) + '% of what is owed' : '', '#/invoices?status=OVERDUE', d.overdue > 0),
      stub('Invoiced this month', rs0(d.invoicedThisMonth), 'after credit notes', '#/invoices'),
      stub('Collected this month', rs0(d.collectedThisMonth), 'incl. TDS', '#/payments'),
      stub('Days to collect (DSO)', d.dso === null ? '–' : d.dso + ' days', 'what is owed ÷ last 90 days’ sales × 90', '#/reports'),
      d.advance > 0 ? stub('Advances held', rs0(d.advance), 'money not yet given to an invoice', '#/customers') : null));

    // needs you
    const needs = h('ul', { class: 'list' });
    for (const n of d.needs) {
      const r = n.invoice;
      const what = { approve: 'Waiting for your approval – made by ' + first(r.createdBy), sentBack: 'Sent back to you: ' + n.note,
        autoDraft: 'Made by a repeating invoice – check it and issue', hold: 'On hold: ' + n.note }[n.kind];
      needs.appendChild(h('li', null, h('div', { class: 'grow' }, h('a', { href: '#/invoice/' + r.id }, r.buyerName), h('small', null, what)),
        h('span', { class: 'num' }, rs0(r.total))));
    }
    if (d.bankNew > 0 && money()) needs.appendChild(h('li', null, h('div', { class: 'grow' }, h('a', { href: '#/bank' }, plural(d.bankNew, 'bank line') + ' to match'),
      h('small', null, 'Money in the bank statement that is not yet in the books'))));
    const left = h('div', { class: 'stack' },
      h('div', { class: 'paper' }, h('h2', null, 'Needs you'), needs.children.length ? needs : empty('Nothing waits for you', 'Approvals, sent-back drafts and disputes show up here.')),
      h('div', { class: 'paper' }, h('h2', null, 'What is owed, by age'), ageing(d.buckets, d.bucketNames)),
      h('div', { class: 'paper chart' }, h('h2', null, 'Invoiced and collected, last 12 months'), monthsChart(d.months)));

    const debtors = h('ul', { class: 'list' }, d.debtors.map((x) => h('li', null,
      h('div', { class: 'grow' }, h('a', { href: '#/customer/' + x.customerId }, x.name),
        h('small', null, x.overdue > 0 ? rs0(x.overdue) + ' overdue, oldest ' + x.oldest + ' days' : 'nothing overdue')),
      h('span', { class: 'num' + (x.overdue > 0 ? ' red' : '') }, rs0(x.owes)))));
    const soon = h('ul', { class: 'list' }, d.soon.map((x) => h('li', null,
      h('span', { class: 'num', style: { 'min-width': '58px' } }, fmtShort(x.date)),
      h('div', { class: 'grow' }, h('a', { href: '#/repeating' }, x.name), h('small', null, x.customer + (x.autoIssue ? '' : ' – made as a draft to check'))),
      h('span', { class: 'num' }, rs0(x.total)))));
    const mails = h('ul', { class: 'list' }, d.recentMail.map((m) => h('li', null,
      h('div', { class: 'grow' }, h('a', { href: '#/outbox?id=' + m.id }, m.subject), h('small', null, m.customer + ', ' + ago(m.at))))));
    const right = h('div', { class: 'stack' },
      h('div', { class: 'paper' }, h('h2', null, 'Who owes most'), debtors.children.length ? debtors : empty('Nobody owes anything', '')),
      h('div', { class: 'paper' }, h('h2', null, 'Repeating invoices, next 2 weeks'), soon.children.length ? soon : empty('None due', 'Set them up under Repeating invoices.')),
      h('div', { class: 'paper' }, h('h2', null, 'Last e-mails written'), mails,
        d.lastTick ? h('p', { class: 'hint', style: { 'margin-top': '8px' } }, 'Automation last ran ' + ago(d.lastTick) + '.') : null));
    v.push(h('div', { class: 'cols' }, left, right));
    return v;
  }

  function stub(k, value, s, href, alert) {
    return h('a', { class: 'stub' + (alert ? ' alert' : ''), href }, h('div', { class: 'k' }, k), h('div', { class: 'v' }, value), s ? h('div', { class: 's' }, s) : null);
  }

  function ageing(b, names) {
    const total = b.reduce((a, x) => a + x, 0);
    if (!total) return empty('Nothing is owed', '');
    return h('div', null,
      h('div', { class: 'age', role: 'img', 'aria-label': names.map((n, i) => n + ' ' + rs0(b[i])).join(', ') },
        b.map((x, i) => x > 0 ? h('div', { class: 'b' + i, style: { width: (x * 100 / total) + '%' }, title: names[i] + ': ' + rs0(x) }) : null)),
      h('div', { class: 'agekey' }, names.map((n, i) => h('div', null, h('i', { class: 'b' + i }), n, h('b', null, rs0(b[i]))))));
  }

  function monthsChart(months) {
    const W = 640, H = 200, pad = 26, bw = (W - pad) / months.length;
    const max = Math.max(1, ...months.map((m) => Math.max(m.invoiced, m.collected)));
    const svg = sv('svg', { viewBox: `0 0 ${W} ${H + 22}`, role: 'img', 'aria-label': 'Invoiced and collected per month' });
    for (let k = 0; k <= 4; k++) {
      const y = H - (H - 10) * k / 4;
      svg.appendChild(sv('line', { x1: pad, x2: W, y1: y, y2: y }));
      if (k > 0) svg.appendChild(sv('text', { x: 0, y: y + 4 }, Math.round(max * k / 4 / 1e7) + 'L'));
    }
    months.forEach((m, i) => {
      const x = pad + i * bw + 4, w = (bw - 10) / 2;
      const hi = (H - 10) * Math.max(0, m.invoiced) / max, hc = (H - 10) * m.collected / max;
      svg.appendChild(sv('rect', { class: 'inv', x, y: H - hi, width: w, height: hi }, sv('title', null, m.month + ' invoiced ' + rs0(m.invoiced))));
      svg.appendChild(sv('rect', { class: 'col', x: x + w + 1, y: H - hc, width: w, height: hc }, sv('title', null, m.month + ' collected ' + rs0(m.collected))));
      svg.appendChild(sv('text', { x: x + w, y: H + 16, 'text-anchor': 'middle' }, MONTHS[+m.month.slice(5) - 1]));
    });
    return h('div', null, svg, h('div', { class: 'legend' },
      h('span', null, h('i', { style: { background: 'var(--ink-2)' } }), 'Invoiced'), h('span', null, h('i', { style: { background: 'var(--green)' } }), 'Collected'),
      h('span', null, 'L = lakh (₹1,00,000)')));
  }
  // ================================================================== Invoices (the list)

  const FILTERS = [['ALL', 'All'], ['DRAFTS', 'Drafts and approvals'], ['OPEN', 'Unpaid'], ['OVERDUE', 'Overdue'], ['PAID', 'Paid'],
    ['HOLD', 'On hold'], ['CANCELLED', 'Cancelled'], ['CN', 'Credit notes']];

  async function pageInvoices(q) {
    const st = q.status || 'ALL';
    const params = { status: st === 'CN' ? '' : st, kind: st === 'CN' ? 'CN' : (st === 'ALL' ? '' : 'INV'), customer: q.customer, q: q.q, from: q.from, to: q.to };
    const d = await get('/api/invoices' + qs(params));
    const go = (patch) => { const n = Object.assign({}, q, patch); location.hash = '#/invoices' + qs(n); };
    const search = h('input', { type: 'search', value: q.q || '', placeholder: 'Number, customer, PO or amount', style: { width: '260px' },
      onkeydown: (e) => { if (e.key === 'Enter') go({ q: search.value.trim() }); } });
    const cust = select(custOptions('All customers'), q.customer || '', { onchange: (e) => go({ customer: e.target.value }) });
    const from = h('input', { type: 'date', value: q.from || '', onchange: (e) => go({ from: e.target.value }), 'aria-label': 'From' });
    const to = h('input', { type: 'date', value: q.to || '', onchange: (e) => go({ to: e.target.value }), 'aria-label': 'To' });
    const rows = d.rows.map((r) => h('tr', { class: 'click', onclick: () => { location.hash = '#/invoice/' + r.id; } },
      h('td', null, billno(r), r.kind === 'CN' ? h('div', null, h('small', null, 'credit note')) : null, r.recurringId ? h('div', null, h('small', null, 'repeating')) : null),
      h('td', null, h('a', { href: '#/invoice/' + r.id, onclick: (e) => e.stopPropagation() }, r.buyerName)),
      h('td', { class: 'num' }, fmtShort(r.issueDate)),
      h('td', { class: 'num' }, r.kind === 'CN' ? '' : fmtShort(r.dueDate)),
      h('td', { class: 'num r' }, (r.kind === 'CN' ? '-' : '') + rs(r.total)),
      h('td', { class: 'num r' }, r.balance ? rs(r.balance) : ''),
      h('td', null, status(r))));
    return [
      h('div', { class: 'page-head' }, h('div', null, h('h1', null, 'Invoices'),
        h('div', { class: 'sub' }, 'Every invoice and credit note. A number is given only when an invoice is issued, so the series has no gaps.')),
        h('div', { class: 'row-btns' }, h('a', { class: 'btn', href: '/api/export/invoices.csv' + qs({ from: q.from, to: q.to }) }, 'Download for Excel'),
          writes() ? h('a', { class: 'btn main', href: '#/new' }, 'New invoice') : null)),
      h('div', { class: 'tabs', role: 'tablist' }, FILTERS.map(([k, t]) => h('button', { class: st === k ? 'on' : '', role: 'tab', 'aria-selected': st === k ? 'true' : 'false', onclick: () => go({ status: k }) }, t))),
      h('div', { class: 'paper tabbed' },
        h('div', { class: 'filters' }, search, cust, h('span', { class: 'muted' }, 'from'), from, h('span', { class: 'muted' }, 'to'), to,
          (q.q || q.customer || q.from || q.to) ? h('button', { class: 'quiet', onclick: () => go({ q: '', customer: '', from: '', to: '' }) }, 'Clear') : null),
        rows.length ? h('div', { class: 'tablewrap' }, h('table', { class: 'ruled' },
          h('thead', null, h('tr', null, h('th', null, 'Number'), h('th', null, 'Customer'), h('th', null, 'Date'), h('th', null, 'Due'),
            h('th', { class: 'r' }, 'Total ₹'), h('th', { class: 'r' }, 'Still owed ₹'), h('th', null, 'Status'))),
          h('tbody', null, rows),
          h('tfoot', null, h('tr', null, h('td', { colspan: 4 }, plural(d.count, 'entry')), h('td', { class: 'num r' }, rs(d.total)), h('td', { class: 'num r' }, rs(d.balance)), h('td', null)))))
          : empty('No invoices here', st === 'ALL' ? 'Make the first one with New invoice.' : 'Try another tab or clear the filters.'))
    ];
  }

  // ================================================================== One invoice: the sheet from the bill book

  async function pageInvoice(id, q) {
    const inv = await get('/api/invoices/' + id);
    const copy = q.copy || 'o';
    const cn = inv.kind === 'CN';
    const book = h('div', null);
    const paint = (c) => {
      clear(book).appendChild(sheet(inv, c));
      for (const b of tabs.querySelectorAll('button')) b.classList.toggle('on', b.dataset.copy === c);
    };
    const tabs = h('div', { class: 'copies', role: 'tablist', 'aria-label': 'Copy' },
      [['o', 'Original'], ['d', 'Duplicate'], ['t', 'Triplicate']].map(([k, t]) =>
        h('button', { 'data-copy': k, role: 'tab', onclick: () => paint(k) }, t)));
    paint(copy);
    // for printing: all three copies, one page each
    const printAll = h('div', { class: 'print-only' });

    const head = h('div', { class: 'page-head' },
      h('div', null, h('h1', null, cn ? 'Credit note' : inv.number ? 'Invoice ' + inv.number : 'Draft invoice'),
        h('div', { class: 'sub' }, inv.buyerName, ' – ', h('a', { href: '#/customer/' + inv.customerId }, 'open customer'),
          inv.recurringName ? [' – made by the repeating invoice ', h('a', { href: '#/repeating' }, inv.recurringName)] : null)),
      h('div', { class: 'row-btns' },
        h('button', { onclick: () => { window.print(); } }, 'Print this copy'),
        inv.number ? h('button', { onclick: () => {
          clear(printAll); for (const c of ['o', 'd', 't']) printAll.appendChild(sheet(inv, c));
          book.style.display = 'none'; window.print(); book.style.display = ''; clear(printAll);
        } }, 'Print all 3 copies') : null));
    return [head, h('div', { class: 'cols inv' }, h('div', null, tabs, book, printAll), sidePanel(inv))];
  }

  const COPY = { o: 'ORIGINAL FOR RECIPIENT', d: 'DUPLICATE FOR TRANSPORTER', t: 'TRIPLICATE FOR SUPPLIER' };

  function sheet(inv, c) {
    const co = S.meta.company, cn = inv.kind === 'CN';
    const lines = inv.lines.map((l, i) => h('tr', null,
      h('td', { class: 'n' }, i + 1), h('td', null, l.description), h('td', { class: 'n' }, l.hsn),
      h('td', { class: 'n' }, qtyText(l.qtyMilli) + ' ' + l.unit), h('td', { class: 'n' }, rs(l.rate)),
      h('td', { class: 'n' }, l.discountBp ? pct(l.discountBp) : ''), h('td', { class: 'n' }, rs(l.taxable)), h('td', { class: 'n' }, pct(l.gstBp))));
    const tot = h('table', null, h('tbody', null,
      h('tr', null, h('td', null, 'Taxable value'), h('td', { class: 'n' }, rs(inv.taxable))),
      inv.intra ? [h('tr', null, h('td', null, 'CGST'), h('td', { class: 'n' }, rs(inv.cgst))), h('tr', null, h('td', null, 'SGST'), h('td', { class: 'n' }, rs(inv.sgst)))]
        : h('tr', null, h('td', null, 'IGST'), h('td', { class: 'n' }, rs(inv.igst))),
      inv.roundOff ? h('tr', null, h('td', null, 'Round off'), h('td', { class: 'n' }, (inv.roundOff > 0 ? '+' : '') + rs(inv.roundOff))) : null,
      h('tr', { class: 'grand' }, h('td', null, 'Total ₹'), h('td', { class: 'n' }, rs(inv.total)))));
    const rates = h('table', { class: 'rates' },
      h('thead', null, h('tr', null, h('th', null, 'GST rate'), h('th', null, 'Taxable'), inv.intra ? [h('th', null, 'CGST'), h('th', null, 'SGST')] : h('th', null, 'IGST'))),
      h('tbody', null, inv.byRate.map((r) => h('tr', null, h('td', null, pct(r.rate)), h('td', null, rs(r.taxable)),
        inv.intra ? [h('td', null, rs(r.cgst)), h('td', null, rs(r.sgst))] : h('td', null, rs(r.igst))))));
    let mark = null;
    if (inv.status === 'PAID') mark = punch('PAID', 'green');
    else if (inv.status === 'CANCELLED') mark = punch('CANCELLED');
    else if (inv.status === 'DRAFT' || inv.status === 'PENDING_APPROVAL') mark = punch('DRAFT', 'grey');
    else if (inv.status === 'APPLIED') mark = punch('USED', 'grey');
    const s = h('div', { class: 'sheet' },
      mark,
      h('div', { class: 'copyname' }, COPY[c]),
      h('div', { class: 'seller' }, h('div', null, h('h2', null, co.name), h('div', { class: 'addr' }, co.address),
        h('div', { class: 'addr' }, 'GSTIN ', h('span', { class: 't' }, co.gstin), '   State ', stateName(co.state)))),
      h('div', { class: 'doc-title' }, cn ? 'CREDIT NOTE' : 'TAX INVOICE'),
      h('div', { class: 'meta' },
        h('div', null, h('div', { class: 'k' }, 'Bill to'), h('b', null, inv.buyerName),
          h('div', { style: { 'white-space': 'pre-line', 'font-size': '13px' } }, inv.buyerAddress || ''),
          inv.buyerGstin ? h('div', null, 'GSTIN ', h('span', { class: 't' }, inv.buyerGstin)) : h('div', { class: 'muted' }, 'Unregistered buyer (B2C)'),
          inv.shipTo && inv.shipTo !== inv.buyerAddress ? h('div', { style: { 'margin-top': '6px' } }, h('div', { class: 'k' }, 'Ship to'), h('div', { style: { 'white-space': 'pre-line', 'font-size': '13px' } }, inv.shipTo)) : null),
        h('div', null, h('div', { class: 'k' }, cn ? 'Credit note number' : 'Invoice number'),
          h('div', { class: 'bigno' }, inv.number || '— — —'),
          h('dl', null, h('dt', null, 'Date'), h('dd', null, fmtDate(inv.issueDate)),
            !cn ? [h('dt', null, 'Due'), h('dd', null, fmtDate(inv.dueDate))] : null,
            h('dt', null, 'Place of supply'), h('dd', null, stateName(inv.placeOfSupply)),
            inv.poNumber ? [h('dt', null, 'Your PO'), h('dd', null, inv.poNumber)] : null,
            cn && inv.against ? [h('dt', null, 'Against'), h('dd', null, inv.against.number)] : null))),
      inv.status === 'DRAFT' || inv.status === 'PENDING_APPROVAL' ? h('div', { class: 'draftnote' },
        inv.status === 'DRAFT' ? 'Draft – not a tax invoice yet. The number and the date are given when it is issued.' : 'Waiting for approval – the number is given when the Accountant approves it.') : null,
      h('table', { class: 'lines' },
        h('thead', null, h('tr', null, h('th', null, '#'), h('th', null, 'Description'), h('th', null, 'HSN/SAC'), h('th', null, 'Qty'),
          h('th', null, 'Rate ₹'), h('th', null, 'Disc.'), h('th', null, 'Taxable ₹'), h('th', null, 'GST'))),
        h('tbody', null, lines)),
      h('div', { class: 'totals' },
        h('div', null, h('div', { class: 'words' }, h('b', null, 'Amount in words'), inv.words), rates,
          cn ? h('p', { style: { 'margin-top': '10px', 'font-size': '13.5px' } }, 'Reason: ', inv.reason) : null,
          inv.notes ? h('p', { style: { 'margin-top': '10px', 'font-size': '13.5px', 'white-space': 'pre-line' } }, inv.notes) : null),
        h('div', null, tot)),
      h('div', { class: 'foot' },
        h('div', null, !cn && co.bankAccount ? h('div', null, h('b', null, 'Pay to: '), co.bankName, ', A/c ', h('span', { class: 't' }, co.bankAccount), ', IFSC ', h('span', { class: 't' }, co.ifsc),
          co.upiId ? [', UPI ', h('span', { class: 't' }, co.upiId)] : null, h('br'), 'Please write the invoice number in the payment reference.') : null,
          h('div', { class: 'terms', style: { 'margin-top': '8px' } }, co.terms)),
        h('div', { class: 'sign' }, 'for ' + co.name, h('div', { class: 'hand' }, inv.number ? first(inv.approvedBy || inv.issuedBy) : ''), 'Authorised signatory')),
      inv.fingerprint ? h('div', { class: 'fp' }, 'Fingerprint (SHA-256) ', inv.fingerprint) : null);
    const foil = h('div', { class: 'counterfoil', 'aria-hidden': 'true' },
      h('span', { class: 'billno' }, inv.number ? inv.number.slice(inv.number.lastIndexOf('/') + 1) : '----'),
      h('div', null, 'Date', h('b', null, fmtDate(inv.issueDate))),
      h('div', null, 'To', h('b', null, inv.buyerName)),
      h('div', null, 'Amount', h('b', { class: 't' }, '₹' + rs(inv.total))),
      !cn ? h('div', null, 'Due', h('b', null, fmtDate(inv.dueDate))) : null,
      h('div', { style: { 'margin-top': 'auto' } }, cn ? 'Credit note' : 'Counterfoil'));
    return h('div', { class: 'billbook copy-' + c }, foil, h('div', { class: 'perf' }), s);
  }

  function sidePanel(inv) {
    const can = inv.can || {};
    const cn = inv.kind === 'CN';
    const side = h('div', { class: 'side stack' });
    // money
    if (inv.number && !cn) {
      const paidPct = inv.total ? inv.paid * 100 / inv.total : 0, credPct = inv.total ? inv.credited * 100 / inv.total : 0;
      side.appendChild(h('div', { class: 'paper' },
        h('h3', null, inv.status === 'CANCELLED' ? 'Cancelled' : inv.balance > 0 ? 'Still owed' : 'Settled'),
        inv.status !== 'CANCELLED' ? h('div', { class: 'money-left' + (inv.overdue ? ' red' : '') }, '₹' + rs(inv.balance)) : h('p', null, inv.cancelReason),
        inv.status !== 'CANCELLED' ? h('div', { class: 'bar', style: { margin: '10px 0' } }, h('i', { class: 'paid', style: { width: paidPct + '%' } }), h('i', { class: 'cred', style: { width: credPct + '%' } })) : null,
        h('dl', { class: 'facts' },
          h('dt', null, 'Paid'), h('dd', { class: 'num' }, rs(inv.paid)),
          inv.credited ? [h('dt', null, 'Credited'), h('dd', { class: 'num' }, rs(inv.credited))] : null,
          h('dt', null, 'Due'), h('dd', null, fmtDate(inv.dueDate), inv.overdue ? h('span', { class: 'red' }, ' – ' + inv.daysLate + ' days late') : null),
          inv.interest > 0 ? [h('dt', null, 'Interest so far'), h('dd', { class: 'num' }, rs(inv.interest), h('small', null, ' @' + pct(S.meta.company.interestBp) + ' a year'))] : null,
          inv.nextReminder ? [h('dt', null, 'Next reminder'), h('dd', null, inv.nextReminder.title + ', ' + fmtDate(inv.nextReminder.date))] : null,
          inv.onHold ? [h('dt', null, 'On hold'), h('dd', null, inv.holdNote)] : null,
          inv.overrideNote ? [h('dt', null, 'Credit limit'), h('dd', null, 'Went above it: ', inv.overrideNote)] : null)));
    }
    if (cn) {
      side.appendChild(h('div', { class: 'paper' }, h('h3', null, 'Where the credit went'),
        h('ul', { class: 'trail' }, (inv.usedFor || []).map((u) => h('li', null, h('span', { class: 'when' }, fmtShort(u.at)),
          h('span', { class: 'grow' }, h('a', { href: '#/invoice/' + u.invoiceId }, u.number)), h('span', { class: 'num' }, rs(u.amount))))),
        inv.balance > 0 ? h('p', { class: 'hint' }, '₹' + rs(inv.balance) + ' is kept as a credit for the customer’s next invoice.') : null));
    }
    // actions
    const btns = h('div', { class: 'row-btns' });
    if (can.edit) {
      btns.appendChild(h('button', { class: 'main', onclick: () => issue(inv) }, 'Issue invoice'));
      btns.appendChild(h('a', { class: 'btn', href: '#/edit/' + inv.id }, 'Edit'));
      btns.appendChild(h('button', { class: 'danger', onclick: async () => {
        if (!confirmBox('Delete this draft?')) return;
        await act(() => del('/api/invoices/' + inv.id), 'Draft deleted.'); location.hash = '#/invoices';
      } }, 'Delete draft'));
    }
    if (can.approve) {
      btns.appendChild(h('button', { class: 'main', onclick: async () => { const r = await act(() => post('/api/invoices/' + inv.id + '/approve'), null); toast('Approved and issued as ' + r.number + '.'); reloadMeta(); route(); } }, 'Approve and issue'));
      btns.appendChild(h('button', { onclick: () => ask('Send back to ' + first(inv.createdBy), 'It goes back to being a draft. Say what should change.', 'What should change', 'Send back',
        async (t) => { await post('/api/invoices/' + inv.id + '/sendback', { note: t }); toast('Sent back.'); reloadMeta(); route(); }) }, 'Send back'));
    }
    if (can.pay) btns.appendChild(h('button', { class: 'main', onclick: () => recordPayment(inv.customerId, inv.id) }, 'Record payment'));
    if (can.remind) btns.appendChild(h('button', { onclick: async () => { const r = await act(() => post('/api/invoices/' + inv.id + '/remind')); toast('Reminder written: ' + r.subject); route(); } }, 'Send reminder now'));
    if (can.credit) btns.appendChild(h('button', { onclick: () => creditNote(inv) }, 'Make credit note'));
    if (can.hold) btns.appendChild(inv.onHold
      ? h('button', { onclick: async () => { await act(() => post('/api/invoices/' + inv.id + '/hold', { on: false }), 'Taken off hold – reminders are back on.'); route(); } }, 'Take off hold')
      : h('button', { onclick: () => ask('Put on hold', 'Use this when the customer disputes the invoice. No reminders go out while it is on hold.', 'What does the customer say?', 'Put on hold',
        async (t) => { await post('/api/invoices/' + inv.id + '/hold', { on: true, note: t }); toast('On hold.'); route(); }) }, 'Put on hold'));
    if (can.copy) btns.appendChild(h('button', { onclick: async () => { const r = await act(() => post('/api/invoices/' + inv.id + '/copy'), 'Copied to a new draft.'); location.hash = '#/edit/' + r.id; } }, 'Copy to new draft'));
    if (can.cancel) btns.appendChild(h('button', { class: 'danger', onclick: () => ask('Cancel ' + inv.number, 'The number stays in the books, marked cancelled. Only possible while nothing is paid or credited.', 'Why is it cancelled?', 'Cancel invoice',
      async (t) => { await post('/api/invoices/' + inv.id + '/cancel', { reason: t }); toast('Cancelled.'); route(); }, true) }, 'Cancel invoice'));
    if (btns.children.length) side.appendChild(h('div', { class: 'paper' }, h('h3', null, 'What you can do'), btns,
      inv.sentBackNote ? h('p', { class: 'draftnote', style: { 'margin-top': '12px' } }, 'Sent back: ', inv.sentBackNote) : null));

    // the money trail
    const trail = [];
    for (const p of inv.payments || []) trail.push([p.date, h('span', { class: 'grow' }, p.voided ? h('s', null, 'Receipt ' + p.number) : 'Receipt ' + p.number, h('small', null, ' ' + p.mode + ' ' + (p.reference || '') + (p.voided ? ' – void' : ''))), rs(p.amount)]);
    for (const c of inv.creditNotes || []) trail.push([c.date, h('span', { class: 'grow' }, h('a', { href: '#/invoice/' + c.id }, 'Credit note ' + c.number), h('small', null, ' ' + c.reason)), rs(c.applied)]);
    if (trail.length) {
      trail.sort((a, b) => a[0].localeCompare(b[0]));
      side.appendChild(h('div', { class: 'paper' }, h('h3', null, 'Payments and credits'),
        h('ul', { class: 'trail' }, trail.map(([d, t, a]) => h('li', null, h('span', { class: 'when' }, fmtShort(d)), t, h('span', { class: 'num' }, a))))));
    }
    if ((inv.mails || []).length) side.appendChild(h('div', { class: 'paper' }, h('h3', null, 'E-mails'),
      h('ul', { class: 'trail' }, inv.mails.map((m) => h('li', null, h('span', { class: 'when' }, fmtShort(m.at)),
        h('span', { class: 'grow' }, h('a', { href: '#/outbox?id=' + m.id }, m.subject)), m.level ? h('span', { class: 'lv l' + m.level }, 'L' + m.level) : null)))));
    side.appendChild(h('div', { class: 'paper' }, h('h3', null, 'History'),
      h('ul', { class: 'trail' }, (inv.history || []).slice().reverse().map((a) => h('li', null, h('span', { class: 'when' }, fmtTime(a.at)),
        h('span', null, h('b', null, first(a.userId)), ' ', HISTORY[a.action] || a.action, a.detail ? h('small', null, ' – ' + a.detail) : null)))),
      inv.intact === false ? h('p', { class: 'err' }, 'Warning: the saved content no longer matches its fingerprint. Somebody changed the data file by hand.') : null,
      inv.intact === true ? h('p', { class: 'hint', style: { 'margin-top': '8px' } }, 'Content checked against its fingerprint: unchanged since it was issued.') : null));
    return side;
  }

  const HISTORY = { 'invoice.draft': 'made the draft', 'invoice.edit': 'changed the draft', 'invoice.issue': 'issued it', 'invoice.submit': 'sent it for approval',
    'invoice.sendback': 'sent it back', 'invoice.cancel': 'cancelled it', 'invoice.hold': 'put it on hold', 'invoice.unhold': 'took it off hold',
    'invoice.override': 'went above the credit limit', 'invoice.redate': 'moved the date', 'invoice.copy': 'made it as a copy', 'creditnote.issue': 'issued the credit note',
    'reminder.send': 'sent a reminder', 'reminder.manual': 'sent a reminder by hand', 'advance.apply': 'used an advance', 'credit.apply': 'used a credit',
    'recurring.draft': 'made it from a repeating invoice' };

  function confirmBox(text) { return window.confirm(text); }

  /** Issue a draft; if the customer goes above the credit limit, the Accountant may write why and go on. */
  async function issue(inv, override) {
    try {
      const r = await post('/api/invoices/' + inv.id + '/issue', override ? { override } : {});
      toast(r.status === 'PENDING_APPROVAL' ? 'Sent for approval – it is above ' + rs0(S.meta.company.approvalLimit) + '.' : 'Issued as ' + r.number + '. The e-mail is in the outbox.');
      reloadMeta();
      location.hash = '#/invoice/' + r.id;
      route();
    } catch (e) {
      if (e.status === 409 && e.data && e.data.needsOverride) {
        ask('Above the credit limit', e.message, 'Why is it fine to go above the limit?', 'Issue anyway', (t) => issue(inv, t));
      } else toast(e.message, true);
    }
  }

  // ------------------------------------------------------------------ record a payment

  async function recordPayment(customerId, invoiceId) {
    const cust = customerId ? await get('/api/customers/' + customerId) : null;
    modal((close) => {
      const err = h('div', { class: 'err' });
      const who = select(custOptions('Pick the customer'), customerId || '');
      const date = h('input', { type: 'date', value: today(), max: today() });
      const amount = h('input', { class: 't', inputmode: 'decimal', placeholder: '0.00' });
      const tds = h('input', { class: 't', inputmode: 'decimal', placeholder: '0.00' });
      const mode = select(S.meta.modes.map((m) => [m, m]), 'NEFT');
      const ref = h('input', { placeholder: 'UTR or cheque number' });
      const box = h('div');
      let rows = [];
      const showOpen = (c) => {
        clear(box); rows = [];
        if (!c) return;
        const open = c.openInvoices;
        if (!open.length) { box.appendChild(h('p', { class: 'hint' }, 'Nothing is open. The money will be kept as an advance for the next invoice.')); return; }
        const t = h('table', { class: 'ruled' }, h('thead', null, h('tr', null, h('th', null, ''), h('th', null, 'Invoice'), h('th', null, 'Due'), h('th', { class: 'r' }, 'Owed ₹'), h('th', { class: 'r' }, 'Give ₹'))));
        const tb = h('tbody');
        for (const r of open) {
          const give = h('input', { class: 't', style: { width: '110px', 'text-align': 'right' }, value: invoiceId === r.id ? plain(r.balance) : '' });
          const tick = h('input', { type: 'checkbox', checked: invoiceId === r.id, onchange: () => { give.value = tick.checked ? plain(r.balance) : ''; sum(); } });
          give.addEventListener('input', sum);
          rows.push({ r, give });
          tb.appendChild(h('tr', null, h('td', null, tick), h('td', null, billno(r)), h('td', { class: 'num' }, fmtShort(r.dueDate), r.overdue ? h('small', { class: 'red' }, ' late') : null),
            h('td', { class: 'num r' }, rs(r.balance)), h('td', { class: 'r' }, give)));
        }
        t.appendChild(tb);
        box.appendChild(h('p', { class: 'hint' }, 'Tick the invoices this payment is for. Leave all empty to pay the oldest first.' +
          (c.tdsBp ? ' This customer takes off ' + pct(c.tdsBp) + ' TDS – fill it in so the invoices are fully settled.' : '')));
        box.appendChild(h('div', { class: 'tablewrap' }, t));
        sum();
      };
      const totalLine = h('p', { class: 'hint' });
      function sum() {
        let s = 0;
        for (const x of rows) { const v = parseFloat(x.give.value.replace(/,/g, '')); if (v > 0) s += Math.round(v * 100); }
        if (s > 0 && !amount.dataset.touched) {
          const c = S.cust[who.value];
          let t = 0;
          if (c && c.tdsBp) for (const x of rows) { const v = parseFloat(x.give.value.replace(/,/g, '')); if (v > 0) t += Math.round(v * 100 / 1.18 * c.tdsBp / 10000); }
          amount.value = plain(s - t);
          tds.value = t ? plain(t) : '';
        }
        totalLine.textContent = s ? 'Given to invoices: ₹' + rs(s) : '';
      }
      amount.addEventListener('input', () => { amount.dataset.touched = '1'; });
      who.addEventListener('change', async () => { showOpen(who.value ? await get('/api/customers/' + who.value) : null); });
      if (cust) showOpen(cust);
      return h('div', null, h('h2', null, 'Record a payment'),
        h('div', { class: 'grid2' }, h('div', { class: 'span2' }, field('Customer', who)), field('Date received', date), field('How', mode),
          field('Amount received ₹', amount), field('TDS kept by customer ₹', tds, 'if any'), h('div', { class: 'span2' }, field('Bank reference', ref))),
        h('div', { style: { 'margin-top': '14px' } }, box), totalLine, err,
        h('div', { class: 'row-btns' }, h('button', { onclick: close }, 'Back'),
          h('button', { class: 'main', onclick: async () => {
            const alloc = rows.filter((x) => x.give.value.trim()).map((x) => ({ invoiceId: x.r.id, amount: x.give.value.trim() }));
            const p = await act(() => post('/api/payments', { customerId: who.value, date: date.value, amount: amount.value, tds: tds.value, mode: mode.value, reference: ref.value, allocations: alloc }), null, err);
            toast('Saved as receipt ' + p.number + '.'); close(); route();
          } }, 'Save payment')));
    }, true);
  }

  // ------------------------------------------------------------------ credit note

  function creditNote(inv) {
    modal((close) => {
      const err = h('div', { class: 'err' });
      const reason = h('input', { placeholder: 'For example: 20 boxes returned, crushed in transit' });
      const inputs = inv.lines.map((l, i) => ({ l, i, qty: h('input', { class: 't', style: { width: '90px' }, placeholder: '0' }), rate: h('input', { class: 't', style: { width: '110px' }, value: plain(l.rate) }) }));
      const left = inv.creditLeft || [];
      return h('div', null, h('h2', null, 'Credit note against ' + inv.number),
        h('p', { class: 'muted' }, 'For goods returned, fill the quantity. For a price cut, fill all the quantity and the rate DIFFERENCE per unit. The credit is taken off this invoice first; anything more is kept for the customer’s next invoice.'),
        field('Reason (printed on the credit note)', reason),
        h('div', { class: 'tablewrap', style: { 'margin-top': '12px' } }, h('table', { class: 'ruled' },
          h('thead', null, h('tr', null, h('th', null, 'Line'), h('th', { class: 'r' }, 'Invoiced'), h('th', { class: 'r' }, 'Left to credit ₹'), h('th', null, 'Quantity'), h('th', null, 'Rate ₹'))),
          h('tbody', null, inputs.map((x) => h('tr', null, h('td', null, x.l.description), h('td', { class: 'num r' }, qtyText(x.l.qtyMilli) + ' × ' + rs(x.l.rate)),
            h('td', { class: 'num r' }, rs(left[x.i] !== undefined ? left[x.i] : x.l.taxable)), h('td', null, x.qty), h('td', null, x.rate)))))),
        err,
        h('div', { class: 'row-btns' }, h('button', { onclick: close }, 'Back'),
          h('button', { class: 'main', onclick: async () => {
            const lines = inputs.filter((x) => x.qty.value.trim()).map((x) => ({ src: x.i, qty: x.qty.value.trim(), rate: x.rate.value.trim() }));
            const r = await act(() => post('/api/invoices/' + inv.id + '/credit', { reason: reason.value, lines }), null, err);
            toast('Credit note ' + r.number + ' issued.'); close(); location.hash = '#/invoice/' + r.id;
          } }, 'Issue credit note')));
    }, true);
  }

  // ================================================================== New / edit invoice

  async function pageEditor(id, q) {
    const inv = id ? await get('/api/invoices/' + id) : null;
    if (inv && !(inv.can && inv.can.edit)) { location.hash = '#/invoice/' + id; return []; }
    const err = h('div', { class: 'err' });
    const cust = select(custOptions('Pick the customer', true), inv ? inv.customerId : (q.customer || ''));
    const date = h('input', { type: 'date', value: inv ? inv.issueDate : today(), max: today() });
    const pos = select(Object.entries(S.meta.states).map(([k, v]) => [k, v + ' (' + k + ')']), inv ? inv.placeOfSupply : '');
    const po = h('input', { value: inv ? inv.poNumber || '' : '', placeholder: 'Optional' });
    const notes = h('textarea', { rows: 2, placeholder: 'Printed under the lines (optional)' }, inv ? inv.notes || '' : '');
    const shipTo = h('textarea', { rows: 2, placeholder: 'Only if different from the billing address' }, inv ? inv.shipTo || '' : '');
    const card = h('div', { class: 'custcard', hidden: true });
    const tbody = h('tbody');
    const live = h('div', { class: 'paper live' });
    const rows = [];

    const itemOpts = [['', 'Type a description, or pick an item']].concat(Object.values(S.item).filter((i) => i.active).map((i) => [i.id, i.name]));
    function addRow(l) {
      const it = select(itemOpts, l && l.itemId ? l.itemId : '', { 'aria-label': 'Item' });
      const desc = h('input', { value: l ? l.description : '', placeholder: 'Description', 'aria-label': 'Description' });
      const hsn = h('input', { class: 't', value: l ? l.hsn : '', 'aria-label': 'HSN/SAC' });
      const unit = h('input', { value: l ? l.unit : 'Nos', 'aria-label': 'Unit' });
      const qty = h('input', { class: 't', value: l ? qtyText(l.qtyMilli) : '', inputmode: 'decimal', 'aria-label': 'Quantity' });
      const rate = h('input', { class: 't', value: l ? plain(l.rate) : '', inputmode: 'decimal', 'aria-label': 'Rate' });
      const disc = h('input', { class: 't', value: l && l.discountBp ? String(l.discountBp / 100) : '', placeholder: '0', inputmode: 'decimal', 'aria-label': 'Discount %' });
      const gst = select(S.meta.rates.map((r) => [r, pct(r)]), l ? l.gstBp : 1800, { 'aria-label': 'GST' });
      const amt = h('td', { class: 'w-amt' });
      const row = { it, desc, hsn, unit, qty, rate, disc, gst, amt };
      it.addEventListener('change', () => {
        const i = S.item[it.value];
        if (i) { desc.value = i.name; hsn.value = i.hsn; unit.value = i.unit; rate.value = plain(i.rate); gst.value = i.gstBp; if (!qty.value) qty.value = '1'; qty.focus(); }
        later();
      });
      for (const x of [desc, hsn, unit, qty, rate, disc, gst]) x.addEventListener('input', later);
      const tr = h('tr', null, h('td', null, it, h('div', { style: { 'margin-top': '4px' } }, desc)), h('td', { class: 'w-hsn' }, hsn), h('td', { class: 'w-qty' }, qty),
        h('td', { class: 'w-u' }, unit), h('td', { class: 'w-rate' }, rate), h('td', { class: 'w-d' }, disc), h('td', { class: 'w-g' }, gst), amt,
        h('td', null, h('button', { class: 'quiet', 'aria-label': 'Remove line', title: 'Remove line', onclick: () => { tr.remove(); rows.splice(rows.indexOf(row), 1); later(); } }, '✕')));
      row.tr = tr;
      rows.push(row);
      tbody.appendChild(tr);
    }
    (inv ? inv.lines : [null]).forEach(addRow);

    const body = () => ({
      customerId: cust.value, issueDate: date.value, placeOfSupply: pos.value || undefined, poNumber: po.value, notes: notes.value, shipTo: shipTo.value,
      lines: rows.map((r) => ({ itemId: r.it.value || undefined, description: r.desc.value, hsn: r.hsn.value, unit: r.unit.value, qty: r.qty.value, rate: r.rate.value,
        discount: parseFloat(r.disc.value) || 0, gstBp: +r.gst.value }))
    });
    let timer = null, seq = 0;
    function later() { clearTimeout(timer); timer = setTimeout(preview, 250); }
    async function preview() {
      const my = ++seq;
      if (!cust.value) { clear(live).appendChild(h('p', { class: 'hint' }, 'Pick the customer to see the GST and the total.')); return; }
      try {
        const p = await post('/api/invoices/preview', body());
        if (my !== seq) return;
        err.textContent = '';
        p.lines.forEach((l, i) => { if (rows[i]) rows[i].amt.textContent = rs(l.taxable); });
        const intra = p.placeOfSupply === S.meta.company.state;
        fill(live, 
          h('h3', null, 'Total'), h('div', { class: 'grand' }, '₹' + rs(p.total)),
          h('dl', { class: 'facts' }, h('dt', null, 'Taxable'), h('dd', { class: 'num' }, rs(p.taxable)),
            intra ? [h('dt', null, 'CGST'), h('dd', { class: 'num' }, rs(p.cgst)), h('dt', null, 'SGST'), h('dd', { class: 'num' }, rs(p.sgst))]
              : [h('dt', null, 'IGST'), h('dd', { class: 'num' }, rs(p.igst))],
            p.roundOff ? [h('dt', null, 'Round off'), h('dd', { class: 'num' }, rs(p.roundOff))] : null,
            h('dt', null, 'Due'), h('dd', null, fmtDate(p.dueDate))),
          h('p', { class: 'hint', style: { 'margin-top': '10px' } }, intra ? 'Same state as us: CGST + SGST.' : 'Another state: IGST.'),
          p.total > S.meta.company.approvalLimit && S.user.role === 'SALES' ? h('p', { class: 'draftnote' }, 'Above ' + rs0(S.meta.company.approvalLimit) + ': the Accountant approves it before it gets a number.') : null);
      } catch (e) {
        if (my === seq) { err.textContent = e.message; }
      }
    }
    async function showCustomer() {
      card.hidden = !cust.value;
      if (!cust.value) return;
      const c = await get('/api/customers/' + cust.value);
      if (!pos.dataset.touched || !inv) pos.value = c.state;
      const used = c.creditLimit ? Math.min(100, (c.owes - c.advance) * 100 / c.creditLimit) : 0;
      fill(card, h('b', null, c.name), h('div', null, c.gstin ? 'GSTIN ' + c.gstin : 'No GSTIN (B2C)', ' – ', c.stateName, ' – pays in ', plural(c.termsDays, 'day')),
        h('div', null, 'Owes ', h('b', { class: 'num' }, rs0(c.owes)), c.overdue ? h('span', { class: 'red' }, ' (' + rs0(c.overdue) + ' overdue)') : null,
          c.advance ? [' – advance ', h('b', { class: 'num' }, rs0(c.advance)), ' will be used'] : null),
        c.creditLimit ? h('div', null, 'Credit limit ', rs0(c.creditLimit), h('div', { class: 'limitbar' }, h('i', { class: used > 90 ? 'over' : '', style: { width: Math.max(0, used) + '%' } }))) : null);
      later();
    }
    pos.addEventListener('change', () => { pos.dataset.touched = '1'; later(); });
    cust.addEventListener('change', () => { pos.dataset.touched = ''; showCustomer(); });
    for (const x of [date, po]) x.addEventListener('change', later);
    if (cust.value) showCustomer(); else preview();

    async function save(thenIssue) {
      const b = body();
      const r = await act(() => (inv ? put('/api/invoices/' + inv.id, b) : post('/api/invoices', b)), null, err);
      if (thenIssue) return issue(r);
      toast('Draft saved.');
      location.hash = '#/invoice/' + r.id;
    }
    return [
      h('div', { class: 'page-head' }, h('div', null, h('h1', null, inv ? 'Edit draft' : 'New invoice'),
        h('div', { class: 'sub' }, 'Pick the customer and the items. GST is worked out for you: CGST + SGST inside ' + S.meta.states[S.meta.company.state] + ', IGST for other states.'))),
      inv && inv.sentBackNote ? h('p', { class: 'draftnote' }, 'Sent back by the Accountant: ', inv.sentBackNote) : null,
      h('div', { class: 'cols' },
        h('div', { class: 'stack' },
          h('div', { class: 'paper' }, h('div', { class: 'grid2' }, h('div', { class: 'span2' }, field('Customer', cust), card),
            field('Invoice date', date, 'today, or up to 60 days back'), field('Place of supply', pos, 'the state the goods go to'),
            field('Customer’s PO number', po), field('Ship to', shipTo))),
          h('div', { class: 'paper' }, h('h3', null, 'Lines'),
            h('div', { class: 'tablewrap' }, h('table', { class: 'edit' },
              h('thead', null, h('tr', null, h('th', null, 'Item and description'), h('th', null, 'HSN/SAC'), h('th', null, 'Qty'), h('th', null, 'Unit'),
                h('th', null, 'Rate ₹'), h('th', null, 'Disc. %'), h('th', null, 'GST'), h('th', { class: 'r' }, 'Taxable ₹'), h('th', null, ''))),
              tbody)),
            h('button', { class: 'quiet', style: { 'margin-top': '8px' }, onclick: () => { addRow(null); rows[rows.length - 1].it.focus(); } }, '+ Add a line'),
            h('div', { style: { 'margin-top': '12px' } }, field('Notes', notes))),
          err,
          h('div', { class: 'row-btns' }, h('button', { class: 'main', onclick: () => save(true) }, 'Issue invoice'), h('button', { onclick: () => save(false) }, 'Save as draft'),
            h('a', { class: 'btn quiet', href: inv ? '#/invoice/' + inv.id : '#/invoices' }, 'Back'))),
        live)
    ];
  }
  // ================================================================== Customers

  async function pageCustomers(q) {
    const d = await get('/api/customers' + qs({ q: q.q }));
    const search = h('input', { type: 'search', value: q.q || '', placeholder: 'Name, GSTIN, contact', style: { width: '260px' },
      onkeydown: (e) => { if (e.key === 'Enter') location.hash = '#/customers' + qs({ q: search.value.trim() }); } });
    const rows = d.rows.map((c) => h('tr', { class: 'click', onclick: () => { location.hash = '#/customer/' + c.id; } },
      h('td', null, h('a', { href: '#/customer/' + c.id, onclick: (e) => e.stopPropagation() }, c.name), !c.active ? h('small', null, ' (inactive)') : null,
        h('div', null, h('small', null, c.contact || ''))),
      h('td', { class: 'num' }, c.gstin || h('span', { class: 'muted' }, 'B2C')),
      h('td', null, c.stateName, c.interState ? h('small', null, ' IGST') : null),
      h('td', { class: 'num r' }, c.termsDays + ' d'),
      h('td', { class: 'num r' }, c.owes ? rs0(c.owes) : ''),
      h('td', { class: 'num r' + (c.overdue ? ' red' : '') }, c.overdue ? rs0(c.overdue) : ''),
      h('td', { class: 'num r' }, c.creditLimit ? rs0(c.creditLimit) : h('span', { class: 'muted' }, 'none'))));
    return [
      h('div', { class: 'page-head' }, h('div', null, h('h1', null, 'Customers'), h('div', { class: 'sub' }, 'Who we bill. The state for GST comes from the GSTIN.')),
        h('div', { class: 'row-btns' }, writes() ? h('button', { class: 'main', onclick: () => customerForm(null) }, 'Add customer') : null)),
      h('div', { class: 'paper' }, h('div', { class: 'filters' }, search),
        rows.length ? h('div', { class: 'tablewrap' }, h('table', { class: 'ruled' },
          h('thead', null, h('tr', null, h('th', null, 'Customer'), h('th', null, 'GSTIN'), h('th', null, 'State'), h('th', { class: 'r' }, 'Terms'),
            h('th', { class: 'r' }, 'Owes'), h('th', { class: 'r' }, 'Overdue'), h('th', { class: 'r' }, 'Credit limit'))), h('tbody', null, rows)))
          : empty('No customers found', 'Add one, or try another search.'))
    ];
  }

  function customerForm(c) {
    modal((close) => {
      const err = h('div', { class: 'err' });
      const f = {
        name: h('input', { value: c ? c.name : '' }),
        gstin: h('input', { class: 't', value: c ? c.gstin || '' : '', placeholder: '15 characters, leave empty for B2C', style: { 'text-transform': 'uppercase' } }),
        state: select([['', 'Pick (only without GSTIN)']].concat(Object.entries(S.meta.states).map(([k, v]) => [k, v + ' (' + k + ')'])), c ? c.state : ''),
        contact: h('input', { value: c ? c.contact || '' : '' }), email: h('input', { type: 'email', value: c ? c.email || '' : '' }),
        phone: h('input', { value: c ? c.phone || '' : '' }),
        billingAddress: h('textarea', { rows: 2 }, c ? c.billingAddress || '' : ''), shippingAddress: h('textarea', { rows: 2 }, c ? c.shippingAddress || '' : ''),
        termsDays: h('input', { type: 'number', min: 0, max: 180, value: c ? c.termsDays : 30 }),
        creditLimit: h('input', { class: 't', value: c && c.creditLimit ? plain(c.creditLimit) : '0' }),
        tdsPct: h('input', { type: 'number', step: '0.1', min: 0, max: 10, value: c ? c.tdsBp / 100 : 0 }),
        noReminders: h('input', { type: 'checkbox', checked: c && c.noReminders }), active: h('input', { type: 'checkbox', checked: !c || c.active })
      };
      const gstMsg = h('div', { class: 'hint' });
      const checkG = async () => {
        const g = f.gstin.value.trim();
        if (!g) { gstMsg.textContent = ''; f.state.disabled = false; return; }
        const r = await post('/api/gstin', { gstin: g }).catch(() => null);
        if (!r) return;
        f.state.disabled = r.ok;
        if (r.ok) f.state.value = r.state;
        gstMsg.textContent = r.ok ? '✓ Valid. ' + r.stateName + ', PAN ' + r.pan : r.problem;
        gstMsg.className = r.ok ? 'hint good' : 'err';
      };
      f.gstin.addEventListener('input', () => { if (f.gstin.value.replace(/\s/g, '').length >= 15) checkG(); else { gstMsg.textContent = ''; f.state.disabled = false; } });
      if (c && c.gstin) checkG();
      return h('div', null, h('h2', null, c ? 'Change customer' : 'Add customer'),
        h('div', { class: 'grid2' }, h('div', { class: 'span2' }, field('Name (as on the GST certificate)', f.name)),
          h('div', null, field('GSTIN', f.gstin), gstMsg), field('State', f.state, 'comes from the GSTIN'),
          field('Contact person', f.contact), field('E-mail (for invoices and reminders)', f.email), field('Phone', f.phone),
          field('Pays in (days)', f.termsDays, 'Net 30 = 30'), field('Credit limit ₹', f.creditLimit, '0 = no limit'), field('TDS they take off %', f.tdsPct, 'usually 0, 1 or 2'),
          field('Billing address', f.billingAddress), field('Shipping address', f.shippingAddress),
          h('label', { class: 'chk' }, f.noReminders, 'No automatic reminders'), h('label', { class: 'chk' }, f.active, 'Active')),
        err,
        h('div', { class: 'row-btns' }, h('button', { onclick: close }, 'Back'),
          h('button', { class: 'main', onclick: async () => {
            const b = { name: f.name.value, gstin: f.gstin.value, state: f.state.value, contact: f.contact.value, email: f.email.value, phone: f.phone.value,
              billingAddress: f.billingAddress.value, shippingAddress: f.shippingAddress.value, termsDays: +f.termsDays.value, creditLimit: f.creditLimit.value,
              tdsPct: +f.tdsPct.value, noReminders: f.noReminders.checked, active: f.active.checked };
            const r = await act(() => (c ? put('/api/customers/' + c.id, b) : post('/api/customers', b)), c ? 'Customer saved.' : 'Customer added.', err);
            close(); await reloadMeta(); location.hash = '#/customer/' + r.id; route();
          } }, c ? 'Save customer' : 'Add customer')));
    }, true);
  }

  async function pageCustomer(id, q) {
    const c = await get('/api/customers/' + id + qs({ from: q.from, to: q.to }));
    const st = c.statement;
    const from = h('input', { type: 'date', value: st.from }), to = h('input', { type: 'date', value: st.to });
    const lines = st.lines.map((l) => h('tr', null, h('td', { class: 'num' }, fmtShort(l.date)),
      h('td', null, l.kind === 'INV' || l.kind === 'CN' ? h('a', { href: '#/invoice/' + l.id, class: 'billno' }, l.number) : h('span', { class: 'num' }, l.number)),
      h('td', null, l.text), h('td', { class: 'num r' }, l.debit ? rs(l.debit) : ''), h('td', { class: 'num r' }, l.credit ? rs(l.credit) : ''),
      h('td', { class: 'num r' }, rs(l.balance))));
    const open = c.openInvoices.map((r) => h('li', null, billno(r), h('div', { class: 'grow' }, h('a', { href: '#/invoice/' + r.id }, 'due ' + fmtDate(r.dueDate)),
      h('small', null, r.overdue ? r.daysLate + ' days late' + (r.interest ? ', interest ₹' + rs(r.interest) : '') : 'not due yet')),
      h('span', { class: 'num' + (r.overdue ? ' red' : '') }, rs0(r.balance))));
    return [
      h('div', { class: 'page-head' }, h('div', null, h('h1', null, c.name),
        h('div', { class: 'sub' }, c.gstin ? 'GSTIN ' + c.gstin : 'Unregistered (B2C)', ', ', c.stateName, c.interState ? ' – IGST' : ' – CGST + SGST', ', pays in ' + plural(c.termsDays, 'day'),
          c.tdsBp ? ', takes off ' + pct(c.tdsBp) + ' TDS' : '', c.noReminders ? ', no automatic reminders' : '')),
        h('div', { class: 'row-btns' },
          writes() ? h('a', { class: 'btn main', href: '#/new?customer=' + c.id }, 'New invoice') : null,
          money() ? h('button', { onclick: () => recordPayment(c.id) }, 'Record payment') : null,
          money() && c.advance > 0 && c.open > 0 ? h('button', { onclick: async () => { const r = await act(() => post('/api/customers/' + c.id + '/advance')); toast('Used ₹' + rs(r.used) + ' of the advance.'); route(); } }, 'Use advance') : null,
          writes() ? h('button', { onclick: () => customerForm(c) }, 'Change') : null,
          h('button', { onclick: () => window.print() }, 'Print statement'))),
      h('div', { class: 'stubs' },
        stub('Owes', rs0(c.owes), plural(c.open, 'open invoice'), '#/invoices?status=OPEN&customer=' + c.id),
        stub('Overdue', rs0(c.overdue), c.oldestLate ? 'oldest ' + c.oldestLate + ' days' : 'nothing late', '#/invoices?status=OVERDUE&customer=' + c.id, c.overdue > 0),
        stub('Advance', rs0(c.advance), 'paid but not yet used', '#/payments'),
        stub('Pays on time', c.onTimePct === null ? '–' : c.onTimePct + '%', c.medianLate === null ? '' : 'usually ' + (Math.round(c.medianLate) === 0 ? 'on the due date' : c.medianLate < 0 ? Math.abs(Math.round(c.medianLate)) + ' days early' : Math.round(c.medianLate) + ' days late'), '#/reports'),
        c.creditLimit ? stub('Credit limit', rs0(c.creditLimit), rs0(Math.max(0, c.creditLimit - c.owes + c.advance)) + ' still free', '#/customers') : null),
      h('div', { class: 'cols' },
        h('div', { class: 'paper' }, h('h2', null, 'Statement of account'),
          h('div', { class: 'filters no-print' }, 'From', from, 'to', to, h('button', { onclick: () => { location.hash = '#/customer/' + c.id + qs({ from: from.value, to: to.value }); } }, 'Show')),
          h('div', { class: 'tablewrap' }, h('table', { class: 'ruled' },
            h('thead', null, h('tr', null, h('th', null, 'Date'), h('th', null, 'Number'), h('th', null, 'Details'), h('th', { class: 'r' }, 'Debit ₹'), h('th', { class: 'r' }, 'Credit ₹'), h('th', { class: 'r' }, 'Balance ₹'))),
            h('tbody', null, h('tr', null, h('td', { class: 'num' }, fmtShort(st.from)), h('td', null), h('td', null, h('i', null, 'Opening balance')), h('td', null), h('td', null), h('td', { class: 'num r' }, rs(st.opening))), lines),
            h('tfoot', null, h('tr', null, h('td', { colspan: 3 }, 'Closing balance on ' + fmtDate(st.to)), h('td', { class: 'num r' }, rs(st.debit)), h('td', { class: 'num r' }, rs(st.credit)), h('td', { class: 'num r' }, rs(st.closing))))))),
        h('div', { class: 'stack' },
          h('div', { class: 'paper' }, h('h3', null, 'Open invoices'), open.length ? h('ul', { class: 'list' }, open) : empty('All paid', ''),
            c.interest > 0 ? h('p', { class: 'hint' }, 'Interest on late payment so far: ₹' + rs(c.interest)) : null),
          c.recurring.length ? h('div', { class: 'paper' }, h('h3', null, 'Repeating invoices'), h('ul', { class: 'list' }, c.recurring.map((r) =>
            h('li', null, h('div', { class: 'grow' }, h('a', { href: '#/repeating' }, r.name), h('small', null, r.describe + (r.active ? ', next ' + fmtDate(r.nextDate) : ', stopped'))), h('span', { class: 'num' }, rs0(r.total)))))) : null,
          h('div', { class: 'paper' }, h('h3', null, 'Contact'), h('dl', { class: 'facts' },
            h('dt', null, 'Person'), h('dd', null, c.contact || '–'), h('dt', null, 'E-mail'), h('dd', null, c.email || '–'), h('dt', null, 'Phone'), h('dd', null, c.phone || '–'),
            h('dt', null, 'Address'), h('dd', { style: { 'white-space': 'pre-line' } }, c.billingAddress || '–')),
            c.notes ? h('p', { style: { 'margin-top': '8px' } }, c.notes) : null)))
    ];
  }

  // ================================================================== Payments

  async function pagePayments(q) {
    const d = await get('/api/payments' + qs({ q: q.q }));
    const search = h('input', { type: 'search', value: q.q || '', placeholder: 'Receipt, customer, reference, amount', style: { width: '280px' },
      onkeydown: (e) => { if (e.key === 'Enter') location.hash = '#/payments' + qs({ q: search.value.trim() }); } });
    const rows = d.rows.map((p) => h('tr', null,
      h('td', null, h('span', { class: 'billno' + (p.voided ? ' draft' : '') }, p.number), p.voided ? h('div', null, h('span', { class: 'st CANCELLED' }, 'Void'), h('small', null, ' ' + p.voidReason)) : null),
      h('td', { class: 'num' }, fmtShort(p.date)),
      h('td', null, h('a', { href: '#/customer/' + p.customerId }, p.customer)),
      h('td', null, p.mode, h('div', null, h('small', { class: 'num' }, p.reference || ''))),
      h('td', null, p.allocations.map((a) => h('div', null, h('a', { href: '#/invoice/' + a.invoiceId, class: 'billno', style: { 'font-size': '12.5px' } }, a.number), ' ', h('small', { class: 'num' }, rs(a.amount)))),
        p.unapplied > 0 ? h('div', null, h('small', null, 'advance ₹' + rs(p.unapplied))) : null),
      h('td', { class: 'num r' }, rs(p.amount), p.tds ? h('div', null, h('small', null, '+ TDS ' + rs(p.tds))) : null),
      h('td', null, money() && !p.voided ? h('button', { class: 'quiet small', onclick: () => ask('Void receipt ' + p.number, 'The invoices it paid become unpaid again. The receipt number stays, marked void.',
        'Why? (for example: cheque returned unpaid)', 'Void payment', async (t) => { await post('/api/payments/' + p.id + '/void', { reason: t }); toast('Payment voided.'); route(); }, true) }, 'Void') : null)));
    return [
      h('div', { class: 'page-head' }, h('div', null, h('h1', null, 'Payments received'), h('div', { class: 'sub' }, 'Each payment gets a receipt number and is given to invoices – the ones you pick, or the oldest first.')),
        h('div', { class: 'row-btns' }, h('a', { class: 'btn', href: '/api/export/payments.csv' }, 'Download for Excel'),
          money() ? h('a', { class: 'btn', href: '#/bank' }, 'Match from bank statement') : null,
          money() ? h('button', { class: 'main', onclick: () => recordPayment(null) }, 'Record payment') : null)),
      h('div', { class: 'paper' }, h('div', { class: 'filters' }, search, h('span', { class: 'muted' }, 'Total received ', h('b', { class: 'num' }, rs0(d.total)))),
        rows.length ? h('div', { class: 'tablewrap' }, h('table', { class: 'ruled' },
          h('thead', null, h('tr', null, h('th', null, 'Receipt'), h('th', null, 'Date'), h('th', null, 'Customer'), h('th', null, 'How'), h('th', null, 'Paid for'), h('th', { class: 'r' }, 'Amount ₹'), h('th', null, ''))),
          h('tbody', null, rows))) : empty('No payments', 'Record one, or match them from the bank statement.'))
    ];
  }

  // ================================================================== Bank matching

  async function pageBank(q) {
    const st = q.state || 'NEW';
    const d = await get('/api/bank' + qs({ state: st }));
    const file = h('input', { type: 'file', accept: '.csv,text/csv', hidden: true });
    const out = h('div', { class: 'hint' });
    const load = async (f) => {
      if (!f) return;
      if (f.size > 1_500_000) { toast('That file is too big (1.5 MB at most).', true); return; }
      const text = await f.text();
      const r = await act(() => post('/api/bank/import', { csv: text }));
      toast(r.added + ' new lines' + (r.duplicates ? ', ' + r.duplicates + ' already there' : '') + (r.debits ? ', ' + r.debits + ' payments out skipped' : '') + '.');
      route();
    };
    file.addEventListener('change', () => load(file.files[0]));
    const drop = h('div', { class: 'dropzone', ondragover: (e) => { e.preventDefault(); drop.classList.add('over'); }, ondragleave: () => drop.classList.remove('over'),
      ondrop: (e) => { e.preventDefault(); drop.classList.remove('over'); load(e.dataTransfer.files[0]); } },
      'Drop the bank statement (CSV) here, or ', h('button', { class: 'small', onclick: () => file.click() }, 'choose the file'), file,
      h('div', { class: 'hint', style: { 'margin-top': '6px' } }, 'Any bank: the columns are found by their names (Date, Narration or Description, Deposit or Credit). Payments out are skipped, lines already imported too.'));
    const lines = d.rows.map((b) => {
      const left = h('div', null, h('div', { class: 'narr' }, b.narration), b.reference ? h('small', { class: 'num' }, 'Ref ' + b.reference) : null);
      if (b.state === 'NEW') {
        if (!b.suggestions.length) left.appendChild(h('p', { class: 'hint', style: { 'margin-top': '8px' } }, 'No customer fits. If it is not a customer’s money (bank interest, a refund), ignore it.'));
        for (const s of b.suggestions) {
          const bars = h('span', { class: 'score', 'aria-label': 'score ' + s.score + ' of 100' }, [0, 1, 2, 3, 4].map((k) => h('i', { class: s.score > k * 20 ? 'on' : '' })));
          left.appendChild(h('div', { class: 'sugg ' + s.label },
            h('div', null, bars, h('b', null, s.label + ' (' + s.score + ')'), ' – ', h('a', { href: '#/customer/' + s.customerId }, s.customer)),
            h('div', null, s.invoices.map((i) => h('span', { style: { 'margin-right': '10px' } }, h('a', { href: '#/invoice/' + i.invoiceId, class: 'billno' }, i.number), ' ', h('small', { class: 'num' }, rs(i.amount))))),
            s.tds ? h('div', null, h('small', null, 'with TDS of ₹' + rs(s.tds))) : null,
            h('ul', null, s.why.map((w) => h('li', null, w))),
            money() ? h('button', { class: s.label === 'Sure' ? 'main small' : 'small', onclick: async () => {
              const p = await act(() => post('/api/bank/' + b.id + '/accept', { customerId: s.customerId, tds: s.tds ? plain(s.tds) : '', allocations: s.invoices.map((i) => ({ invoiceId: i.invoiceId, amount: plain(i.amount) })) }));
              toast('Booked as receipt ' + p.number + '.'); reloadMeta(); route();
            } }, 'Accept – book receipt') : null));
        }
      } else if (b.payment) {
        left.appendChild(h('p', { class: 'hint' }, 'Booked as receipt ', h('b', null, b.payment.number), ' for ', h('a', { href: '#/customer/' + b.payment.customerId }, b.payment.customer)));
      }
      return h('div', { class: 'bankline' }, h('div', { class: 'num' }, fmtDate(b.date)), left,
        h('div', null, h('div', { class: 'amt' }, '₹' + rs(b.amount)),
          money() && b.state === 'NEW' ? h('div', { class: 'row-btns', style: { 'justify-content': 'flex-end', 'margin-top': '8px' } },
            h('button', { class: 'small', onclick: () => recordPayment(b.suggestions[0] ? b.suggestions[0].customerId : null) }, 'Book by hand'),
            h('button', { class: 'quiet small', onclick: async () => { await act(() => post('/api/bank/' + b.id + '/ignore', { ignore: true }), 'Ignored.'); route(); } }, 'Ignore')) : null,
          money() && b.state === 'IGNORED' ? h('button', { class: 'quiet small', onclick: async () => { await act(() => post('/api/bank/' + b.id + '/ignore', { ignore: false })); route(); } }, 'Bring back') : null));
    });
    return [
      h('div', { class: 'page-head' }, h('div', null, h('h1', null, 'Bank matching'),
        h('div', { class: 'sub' }, 'Upload the bank statement. For each money-in line Bijak looks for the invoice number, the customer’s name or GSTIN, and amounts that fit one or more open invoices (also less TDS). You accept; nothing is booked by itself.')),
        h('div', { class: 'row-btns' }, money() && d.new ? h('button', { class: 'main', onclick: async () => {
          const r = await act(() => post('/api/bank/auto'));
          toast(r.matched ? 'Booked ' + r.matched + (r.matched === 1 ? ' sure match. ' : ' sure matches. ') + r.left + ' left for you to check.' : 'No line is sure enough – please check them one by one.');
          reloadMeta(); route();
        } }, 'Accept all sure matches') : null)),
      money() ? h('div', { class: 'paper', style: { 'margin-bottom': '16px' } }, drop, out) : null,
      h('div', { class: 'tabs' }, [['NEW', 'To match', d.new], ['MATCHED', 'Matched', d.matched], ['IGNORED', 'Ignored', d.ignored]].map(([k, t, n]) =>
        h('button', { class: st === k ? 'on' : '', onclick: () => { location.hash = '#/bank?state=' + k; } }, t, h('span', { class: 'n' }, n)))),
      h('div', { class: 'paper tabbed' }, lines.length ? lines : empty(st === 'NEW' ? 'Nothing to match' : 'Nothing here', st === 'NEW' ? 'Upload a bank statement to start.' : ''))
    ];
  }

  // ================================================================== Repeating invoices

  async function pageRepeating() {
    const d = await get('/api/recurring');
    const cards = d.rows.map((r) => h('div', { class: 'paper' },
      h('div', { class: 'rep' },
        h('div', null, h('h3', null, r.name, !r.active ? h('span', { class: 'tag', style: { 'margin-left': '8px' } }, 'stopped') : null),
          h('div', { class: 'muted' }, h('a', { href: '#/customer/' + r.customerId }, r.customer), ' – ', r.describe, r.endDate ? ', until ' + fmtDate(r.endDate) : ''),
          h('div', { class: 'hint' }, r.autoIssue ? 'Issued and e-mailed by itself' : 'Made as a draft for the Accountant to check', ' – made ', plural(r.generated, 'invoice') + ' so far',
            r.last ? [', last ', h('a', { href: '#/invoice/' + r.last.id, class: 'billno' }, r.last.number || 'draft')] : null),
          r.active && r.upcoming.length ? h('div', { class: 'dates', 'aria-label': 'Next dates' }, r.upcoming.map((x) => h('span', null, fmtDate(x)))) : null),
        h('div', { class: 'r' }, h('div', { class: 'num', style: { 'font-size': '18px', 'font-weight': '700' } }, rs0(r.total)), h('small', null, 'each, with GST'),
          money() ? h('div', { style: { 'margin-top': '8px' } }, h('button', { class: 'small', onclick: () => repeatForm(r) }, 'Change')) : null))));
    return [
      h('div', { class: 'page-head' }, h('div', null, h('h1', null, 'Repeating invoices'),
        h('div', { class: 'sub' }, 'Bills that go out on a schedule – AMC, rent, weekly supply. Bijak makes each one on its date, issues it, e-mails it and moves to the next date. The 31st means the last day in shorter months.')),
        h('div', { class: 'row-btns' }, money() ? h('button', { class: 'main', onclick: () => repeatForm(null) }, 'New repeating invoice') : null)),
      h('p', { class: 'muted' }, 'Together about ', h('b', { class: 'num' }, rs0(d.monthly)), ' a month.'),
      h('div', { class: 'stack' }, cards.length ? cards : h('div', { class: 'paper' }, empty('None yet', 'Set up a bill that repeats, and Bijak will make it on time.')))
    ];
  }

  function repeatForm(r) {
    modal((close) => {
      const err = h('div', { class: 'err' });
      const nm = h('input', { value: r ? r.name : '', placeholder: 'Monthly AMC' });
      const cust = select(custOptions('Pick the customer', true), r ? r.customerId : '');
      const every = select([['MONTHLY', 'Every month'], ['QUARTERLY', 'Every 3 months'], ['YEARLY', 'Every year'], ['WEEKLY', 'Every week']], r ? r.every : 'MONTHLY');
      const day = h('select');
      const start = h('input', { type: 'date', value: r ? r.startDate : today(), min: r ? null : today() });
      const end = h('input', { type: 'date', value: r && r.endDate ? r.endDate : '' });
      const auto = h('input', { type: 'checkbox', checked: !r || r.autoIssue });
      const active = h('input', { type: 'checkbox', checked: !r || r.active });
      const po = h('input', { value: r ? r.poNumber || '' : '' });
      const item = select([['', 'Pick an item']].concat(Object.values(S.item).filter((i) => i.active).map((i) => [i.id, i.name])), r && r.lines[0] ? r.lines[0].itemId || '' : '');
      const qty = h('input', { class: 't', value: r && r.lines[0] ? qtyText(r.lines[0].qtyMilli) : '1' });
      const rate = h('input', { class: 't', value: r && r.lines[0] ? plain(r.lines[0].rate) : '' });
      const more = r && r.lines.length > 1 ? r.lines.slice(1) : [];
      const dates = h('div', { class: 'dates' });
      const fillDays = () => {
        const w = every.value === 'WEEKLY';
        const keep = day.value || (r ? r.anchorDay : 1);
        clear(day);
        const opts = w ? ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday'].map((n, i) => [i + 1, n])
          : Array.from({ length: 31 }, (_, i) => [i + 1, (i + 1) + (i === 30 ? ' (last day of the month)' : '')]);
        for (const [v, t] of opts) day.appendChild(h('option', { value: v, selected: String(v) === String(keep) }, t));
      };
      const showDates = async () => {
        const p = await post('/api/schedule', { every: every.value, anchorDay: +day.value, startDate: start.value, endDate: end.value }).catch(() => null);
        clear(dates);
        if (!p) return;
        if (p.problem) { dates.appendChild(h('span', { class: 'err' }, p.problem)); return; }
        add(dates, [h('div', { class: 'hint', style: { width: '100%' } }, p.describe + '. Next bills:'), p.upcoming.map((x) => h('span', null, fmtDate(x)))]);
      };
      every.addEventListener('change', () => { fillDays(); showDates(); });
      for (const x of [day, start, end]) x.addEventListener('change', showDates);
      item.addEventListener('change', () => { const i = S.item[item.value]; if (i) rate.value = plain(i.rate); });
      fillDays(); showDates();
      return h('div', null, h('h2', null, r ? 'Change repeating invoice' : 'New repeating invoice'),
        h('div', { class: 'grid2' }, field('Name', nm), field('Customer', cust), field('How often', every), field('On', day),
          field('First bill on or after', start), field('Stop after', end, 'optional'),
          h('div', { class: 'span2' }, dates),
          field('Item', item), h('div', { class: 'grid2' }, field('Quantity', qty), field('Rate ₹', rate)),
          field('Customer’s PO', po), h('div', null),
          h('label', { class: 'chk' }, auto, 'Issue and e-mail by itself'), h('label', { class: 'chk' }, active, 'Running')),
        more.length ? h('p', { class: 'hint' }, 'Plus ' + plural(more.length, 'more line') + ' kept as they are.') : null,
        err,
        h('div', { class: 'row-btns' }, h('button', { onclick: close }, 'Back'),
          h('button', { class: 'main', onclick: async () => {
            const i = S.item[item.value];
            const lines = [{ itemId: item.value, description: i ? i.name : '', hsn: i ? i.hsn : '', unit: i ? i.unit : 'Nos', qty: qty.value, rate: rate.value, gstBp: i ? i.gstBp : 1800 }]
              .concat(more.map((l) => ({ itemId: l.itemId, description: l.description, hsn: l.hsn, unit: l.unit, qty: qtyText(l.qtyMilli), rate: plain(l.rate), discount: l.discountBp / 100, gstBp: l.gstBp })));
            const b = { name: nm.value, customerId: cust.value, every: every.value, anchorDay: +day.value, startDate: start.value, endDate: end.value,
              autoIssue: auto.checked, active: active.checked, poNumber: po.value, notes: r ? r.notes : '', lines };
            await act(() => (r ? put('/api/recurring/' + r.id, b) : post('/api/recurring', b)), 'Saved.', err);
            close(); route();
          } }, 'Save')));
    }, true);
  }

  // ================================================================== Outbox

  const KINDS = { INVOICE: 'Invoices', REMINDER: 'Reminders', RECEIPT: 'Receipts', CREDIT_NOTE: 'Credit notes' };

  async function pageOutbox(q) {
    const d = await get('/api/outbox' + qs({ kind: q.kind, q: q.q }));
    const reader = h('div');
    const open = (m) => {
      fill(reader, h('div', { class: 'paper' }, h('h3', null, m.subject),
        h('p', { class: 'muted' }, 'To ', m.to || '(no e-mail address)', ' – ', fmtTime(m.at), m.by ? ' – sent by hand by ' + first(m.by) : ' – written by Bijak'),
        h('div', { class: 'letter' }, m.body),
        m.invoiceId ? h('p', { style: { 'margin-top': '10px' } }, h('a', { href: '#/invoice/' + m.invoiceId }, 'Open the invoice')) : null));
    };
    const list = h('ul', { class: 'list' }, d.rows.map((m) => h('li', null,
      m.level ? h('span', { class: 'lv l' + m.level, title: 'Reminder level ' + m.level }, 'L' + m.level) : h('span', { class: 'lv' }, m.kind === 'INVOICE' ? 'INV' : m.kind === 'RECEIPT' ? 'RCT' : 'CN'),
      h('div', { class: 'grow' }, h('a', { href: '#', onclick: (e) => { e.preventDefault(); open(m); } }, m.subject), h('small', null, m.customer + ', ' + ago(m.at))))));
    const pick = q.id ? d.rows.find((m) => m.id === q.id) : d.rows[0];
    if (pick) open(pick);
    return [
      h('div', { class: 'page-head' }, h('div', null, h('h1', null, 'Outbox'),
        h('div', { class: 'sub' }, 'Every e-mail Bijak wrote: invoices, receipts and the payment reminders. In this demo they are kept here and not really sent.'))),
      h('div', { class: 'tabs' }, h('button', { class: !q.kind ? 'on' : '', onclick: () => { location.hash = '#/outbox'; } }, 'All', h('span', { class: 'n' }, d.total)),
        Object.entries(KINDS).map(([k, t]) => h('button', { class: q.kind === k ? 'on' : '', onclick: () => { location.hash = '#/outbox?kind=' + k; } }, t, h('span', { class: 'n' }, d.kinds[k] || 0)))),
      h('div', { class: 'cols half' }, h('div', { class: 'paper tabbed', style: { 'max-height': '75vh', overflow: 'auto' } }, d.rows.length ? list : empty('No e-mails', '')), reader)
    ];
  }

  // ================================================================== Reports and GST

  async function pageReports(q) {
    const d = await get('/api/reports' + qs({ month: q.month }));
    const g = d.gst;
    const month = select(d.months.map((m) => [m, MONTHS[+m.slice(5) - 1] + ' ' + m.slice(0, 4)]), g.month, { onchange: (e) => { location.hash = '#/reports?month=' + e.target.value; } });
    const ageRows = d.ageing.map((a) => h('tr', null, h('td', null, h('a', { href: '#/customer/' + a.customerId }, a.name)),
      a.buckets.map((b, i) => h('td', { class: 'num r' + (i >= 3 && b ? ' red' : '') }, b ? rs0(b) : '')), h('td', { class: 'num r' }, rs0(a.total)),
      h('td', { class: 'num r' }, a.interest ? rs0(a.interest) : '')));
    const payers = d.payers.map((p) => h('tr', null, h('td', null, h('a', { href: '#/customer/' + p.customerId }, p.name)), h('td', { class: 'num r' }, p.count),
      h('td', { class: 'num r' + (p.median > 15 ? ' red' : '') }, Math.round(p.median) === 0 ? 'on time' : p.median < 0 ? Math.abs(Math.round(p.median)) + ' early' : Math.round(p.median) + ' late'), h('td', { class: 'num r' }, p.onTimePct + '%')));
    const tbl = (head, rows) => h('div', { class: 'tablewrap' }, h('table', { class: 'ruled' }, h('thead', null, h('tr', null, head.map(([t, r]) => h('th', { class: r ? 'r' : null }, t)))), h('tbody', null, rows)));
    const n = (v) => h('td', { class: 'num r' }, rs(v));
    return [
      h('div', { class: 'page-head' }, h('div', null, h('h1', null, 'Reports and GST'), h('div', { class: 'sub' }, 'Who owes how long, how fast customers pay, and the GST figures for the monthly return.')),
        h('div', { class: 'row-btns' }, h('a', { class: 'btn', href: '/api/export/invoices.csv' }, 'Invoice register (Excel)'))),
      h('div', { class: 'paper' }, h('h2', null, 'Ageing: what is owed, by days past the due date'),
        d.ageing.length ? tbl([['Customer']].concat(d.bucketNames.map((b) => [b, 1])).concat([['Total', 1], ['Interest', 1]]), ageRows
          .concat([h('tr', null, h('td', null, h('b', null, 'Total')), d.ageingTotal.map((b) => h('td', { class: 'num r' }, h('b', null, rs0(b)))), h('td', { class: 'num r' }, h('b', null, rs0(d.ageingTotal.reduce((a, b) => a + b, 0)))), h('td', null))]))
          : empty('Nothing is owed', '')),
      h('div', { class: 'cols', style: { 'margin-top': '16px' } },
        h('div', { class: 'paper' }, h('div', { class: 'page-head', style: { 'margin-bottom': '10px' } }, h('h2', null, 'GST for the month'), h('div', { class: 'row-btns' }, month,
          h('a', { class: 'btn', href: '/api/export/gst.csv?month=' + g.month }, 'Download for the CA'))),
          h('p', { class: 'muted' }, plural(g.count, 'document') + ' – taxable ', h('b', { class: 'num' }, rs0(g.taxable)), ', CGST ', h('b', { class: 'num' }, rs0(g.cgst)),
            ', SGST ', h('b', { class: 'num' }, rs0(g.sgst)), ', IGST ', h('b', { class: 'num' }, rs0(g.igst)), ' (credit notes taken off).'),
          h('h3', { style: { margin: '14px 0 6px' } }, 'Tax by rate'),
          tbl([['Rate'], ['Taxable ₹', 1], ['CGST ₹', 1], ['SGST ₹', 1], ['IGST ₹', 1]], g.rates.map((r) => h('tr', null, h('td', null, pct(r.rate)), n(r.taxable), n(r.cgst), n(r.sgst), n(r.igst)))),
          h('h3', { style: { margin: '16px 0 6px' } }, 'B2B invoices (buyer has a GSTIN)'),
          g.b2b.length ? tbl([['Number'], ['Buyer'], ['GSTIN'], ['Taxable ₹', 1], ['Tax ₹', 1]], g.b2b.map((r) => h('tr', null,
            h('td', null, h('a', { href: '#/invoice/' + r.id, class: 'billno' }, r.number)), h('td', null, r.buyer), h('td', { class: 'num' }, r.gstin), n(r.taxable), n(r.cgst + r.sgst + r.igst))))
            : h('p', { class: 'hint' }, 'None this month.'),
          h('h3', { style: { margin: '16px 0 6px' } }, 'B2C (no GSTIN), by state and rate'),
          g.b2c.length ? tbl([['State'], ['Rate'], ['Taxable ₹', 1], ['Tax ₹', 1]], g.b2c.map((r) => h('tr', null, h('td', null, r.posName), h('td', null, pct(r.rate)), n(r.taxable), n(r.cgst + r.sgst + r.igst))))
            : h('p', { class: 'hint' }, 'None this month.'),
          h('h3', { style: { margin: '16px 0 6px' } }, 'Credit notes'),
          g.creditNotes.length ? tbl([['Number'], ['Against'], ['Buyer'], ['Taxable ₹', 1]], g.creditNotes.map((r) => h('tr', null,
            h('td', null, h('a', { href: '#/invoice/' + r.id, class: 'billno' }, r.number)), h('td', { class: 'num' }, r.against || ''), h('td', null, r.buyer), n(r.taxable))))
            : h('p', { class: 'hint' }, 'None this month.'),
          h('h3', { style: { margin: '16px 0 6px' } }, 'HSN summary'),
          tbl([['HSN/SAC'], ['Description'], ['Qty', 1], ['Rate'], ['Taxable ₹', 1]], g.hsn.map((r) => h('tr', null, h('td', { class: 'num' }, r.hsn), h('td', null, r.description),
            h('td', { class: 'num r' }, qtyText(r.qtyMilli) + ' ' + r.unit), h('td', null, pct(r.rate)), n(r.taxable))))),
        h('div', { class: 'paper' }, h('h2', null, 'How fast customers pay'),
          h('p', { class: 'muted' }, 'Invoices paid in the last 12 months: ' + d.paidCount + '. On time: ' + (d.onTimePct === null ? '–' : d.onTimePct + '%') +
            '. Half are paid within ' + (d.medianLate === null ? '–' : Math.round(d.medianLate) + ' days') + ' of the due date, 85% within ' + (d.p85Late === null ? '–' : Math.round(d.p85Late)) + ' days.'),
          payers.length ? tbl([['Customer'], ['Paid', 1], ['Usually (days)', 1], ['On time', 1]], payers) : empty('No paid invoices yet', '')))
    ];
  }

  // ================================================================== Items

  async function pageItems() {
    const d = await get('/api/items');
    const rows = d.rows.map((i) => h('tr', { class: writes() ? 'click' : '', onclick: () => writes() && itemForm(i) },
      h('td', null, i.name, !i.active ? h('small', null, ' (not sold now)') : null), h('td', { class: 'num' }, i.hsn, h('small', null, i.service ? ' SAC' : ' HSN')),
      h('td', null, i.unit), h('td', { class: 'num r' }, rs(i.rate)), h('td', { class: 'num r' }, pct(i.gstBp)),
      h('td', { class: 'num r' }, i.soldQty ? qtyText(i.soldQty) : ''), h('td', { class: 'num r' }, i.soldValue ? rs0(i.soldValue) : '')));
    return [
      h('div', { class: 'page-head' }, h('div', null, h('h1', null, 'Items'), h('div', { class: 'sub' }, 'What we sell, with the HSN code (goods) or SAC code (services) and the GST rate. Picking an item fills the invoice line.')),
        h('div', { class: 'row-btns' }, writes() ? h('button', { class: 'main', onclick: () => itemForm(null) }, 'Add item') : null)),
      h('div', { class: 'paper' }, h('div', { class: 'tablewrap' }, h('table', { class: 'ruled' },
        h('thead', null, h('tr', null, h('th', null, 'Item'), h('th', null, 'Code'), h('th', null, 'Unit'), h('th', { class: 'r' }, 'Rate ₹'), h('th', { class: 'r' }, 'GST'),
          h('th', { class: 'r' }, 'Sold'), h('th', { class: 'r' }, 'Sold value'))), h('tbody', null, rows))))
    ];
  }

  function itemForm(i) {
    modal((close) => {
      const err = h('div', { class: 'err' });
      const nm = h('input', { value: i ? i.name : '' }), hsn = h('input', { class: 't', value: i ? i.hsn : '' }), unit = h('input', { value: i ? i.unit : 'Nos' });
      const rate = h('input', { class: 't', value: i ? plain(i.rate) : '' }), gst = select(S.meta.rates.map((r) => [r, pct(r)]), i ? i.gstBp : 1800);
      const svc = h('input', { type: 'checkbox', checked: i && i.service }), active = h('input', { type: 'checkbox', checked: !i || i.active });
      return h('div', null, h('h2', null, i ? 'Change item' : 'Add item'),
        h('div', { class: 'grid2' }, h('div', { class: 'span2' }, field('Name', nm)), field('HSN or SAC code', hsn, '4, 6 or 8 digits; services start with 99'), field('Unit', unit),
          field('Rate ₹ (before GST)', rate), field('GST', gst), h('label', { class: 'chk' }, svc, 'This is a service (SAC)'), h('label', { class: 'chk' }, active, 'Sold now')),
        err, h('div', { class: 'row-btns' }, h('button', { onclick: close }, 'Back'), h('button', { class: 'main', onclick: async () => {
          const b = { name: nm.value, hsn: hsn.value, unit: unit.value, rate: rate.value, gstBp: +gst.value, service: svc.checked, active: active.checked };
          await act(() => (i ? put('/api/items/' + i.id, b) : post('/api/items', b)), 'Item saved.', err);
          close(); await reloadMeta(); route();
        } }, 'Save item')));
    });
  }

  // ================================================================== Owner's desk

  async function pageDesk() {
    const d = await get('/api/admin');
    const s = d.settings;
    const own = S.user.owner;
    const f = {};
    const inp = (k, attrs) => (f[k] = h('input', Object.assign({ value: s[k] === undefined || s[k] === null ? '' : s[k], disabled: !own }, attrs || {})));
    const err = h('div', { class: 'err' });
    const days = s.reminderDays.map((v) => h('input', { type: 'number', value: v, style: { width: '76px' }, disabled: !own }));
    const remOn = h('input', { type: 'checkbox', checked: s.remindersOn, disabled: !own });
    const autoAdv = h('input', { type: 'checkbox', checked: s.autoApplyAdvance, disabled: !own });
    const address = h('textarea', { rows: 2, disabled: !own }, s.address);
    const terms = h('textarea', { rows: 3, disabled: !own }, s.terms);
    const people = d.people.map((p) => h('tr', { class: own ? 'click' : '', onclick: () => own && personForm(p) },
      h('td', null, p.name, h('div', null, h('small', null, p.username))), h('td', null, p.roleLabel), h('td', null, p.active ? (p.locked ? h('span', { class: 'red' }, 'locked (wrong passwords)') : 'active') : 'switched off'),
      h('td', null, p.lastSeen ? ago(p.lastSeen) : '–')));
    return [
      h('div', { class: 'page-head' }, h('div', null, h('h1', null, 'Owner’s desk'), h('div', { class: 'sub' }, own ? 'The company, the invoice series and the rules of the automation.' : 'Only the Owner can change these.'))),
      h('div', { class: 'cols' },
        h('div', { class: 'paper' }, h('h2', null, 'Company and automation'),
          h('div', { class: 'grid2' }, h('div', { class: 'span2' }, field('Company name', inp('companyName'))), field('GSTIN', inp('gstin', { class: 't' })), field('Phone', inp('phone')),
            h('div', { class: 'span2' }, field('Address', address)), field('E-mail', inp('email')), field('UPI ID', inp('upiId')),
            field('Bank', inp('bankName')), field('Account number', inp('bankAccount', { class: 't' })), field('IFSC', inp('ifsc', { class: 't' })), h('div'),
            field('Invoice prefix', inp('invoicePrefix', { class: 't' }), 'next: ' + d.nextNumbers.invoice), field('Credit note prefix', inp('creditPrefix', { class: 't' }), 'next: ' + d.nextNumbers.credit),
            field('Receipt prefix', inp('receiptPrefix', { class: 't' }), 'next: ' + d.nextNumbers.receipt),
            field('Approval limit ₹', h('input', { class: 't', value: plain(s.approvalLimit), disabled: !own, oninput: (e) => { f.approvalLimit = e.target; } }), 'Sales invoices above it need approval'),
            field('Interest on late payment, % a year', h('input', { type: 'number', step: '0.5', value: s.interestBp / 100, disabled: !own, oninput: (e) => { f.interest = e.target; } }), '0 = none'),
            h('div', null),
            h('div', { class: 'span2' }, h('label', { class: 'chk' }, remOn, 'Send payment reminders by themselves'),
              h('div', { class: 'row-btns', style: { 'margin-top': '8px' } }, days.map((x, k) => field(['Friendly', 'Due today', 'Overdue', 'Second', 'Final'][k], x))),
              h('p', { class: 'hint' }, 'Days from the due date: -3 means 3 days before. Each level goes once; after a break only the highest is sent.')),
            h('div', { class: 'span2' }, h('label', { class: 'chk' }, autoAdv, 'Use a customer’s advance as soon as a new invoice is issued')),
            h('div', { class: 'span2' }, field('Terms printed on every invoice', terms))),
          err,
          own ? h('div', { class: 'row-btns', style: { 'margin-top': '12px' } }, h('button', { class: 'main', onclick: async () => {
            const b = {};
            for (const k of ['companyName', 'gstin', 'phone', 'email', 'upiId', 'bankName', 'bankAccount', 'ifsc', 'invoicePrefix', 'creditPrefix', 'receiptPrefix']) b[k] = f[k].value;
            b.address = address.value; b.terms = terms.value; b.remindersOn = remOn.checked; b.autoApplyAdvance = autoAdv.checked;
            b.reminderDays = days.map((x) => x.value);
            if (f.approvalLimit) b.approvalLimit = f.approvalLimit.value;
            if (f.interest) b.interestPct = +f.interest.value;
            await act(() => put('/api/admin/settings', b), 'Settings saved.', err);
            await reloadMeta(); route();
          } }, 'Save settings')) : null),
        h('div', { class: 'paper' }, h('div', { class: 'page-head', style: { 'margin-bottom': '8px' } }, h('h2', null, 'People'), own ? h('div', { class: 'row-btns' }, h('button', { class: 'small', onclick: () => personForm(null) }, 'Add person')) : null),
          h('table', { class: 'ruled' }, h('thead', null, h('tr', null, h('th', null, 'Name'), h('th', null, 'Role'), h('th', null, 'State'), h('th', null, 'Last seen'))), h('tbody', null, people)),
          h('p', { class: 'hint', style: { 'margin-top': '10px' } }, 'Owner: everything. Accountant: approvals, payments, credit notes, bank. Sales: customers and invoices up to the approval limit. Auditor: reads only.')))
    ];
  }

  function personForm(p) {
    modal((close) => {
      const err = h('div', { class: 'err' });
      const un = h('input', { value: p ? p.username : '', disabled: !!p }), nm = h('input', { value: p ? p.name : '' }), em = h('input', { value: p ? p.email || '' : '' });
      const role = select([['OWNER', 'Owner'], ['ACCOUNTANT', 'Accountant'], ['SALES', 'Sales'], ['AUDITOR', 'Auditor (read only)']], p ? p.role : 'SALES');
      const pw = h('input', { type: 'password', autocomplete: 'new-password' }), active = h('input', { type: 'checkbox', checked: !p || p.active });
      return h('div', null, h('h2', null, p ? 'Change ' + p.name : 'Add person'),
        h('div', { class: 'grid2' }, field('Username', un), field('Name', nm), field('E-mail', em), field('Role', role),
          field(p ? 'New password' : 'Password', pw, p ? 'leave empty to keep' : '8+ with a letter and a number'), h('label', { class: 'chk' }, active, 'Can sign in')),
        err, h('div', { class: 'row-btns' }, h('button', { onclick: close }, 'Back'), h('button', { class: 'main', onclick: async () => {
          const b = { username: un.value, name: nm.value, email: em.value, role: role.value, password: pw.value, active: active.checked };
          await act(() => (p ? put('/api/admin/users/' + p.id, b) : post('/api/admin/users', b)), 'Saved.', err);
          close(); await reloadMeta(); route();
        } }, 'Save')));
    });
  }

  async function pageActivity() {
    const d = await get('/api/activity?limit=300');
    return [h('div', { class: 'page-head' }, h('div', null, h('h1', null, 'Activity log'), h('div', { class: 'sub' }, 'Who did what, newest first. The automation shows as Bijak (automatic).'))),
      h('div', { class: 'paper' }, h('div', { class: 'tablewrap' }, h('table', { class: 'ruled' },
        h('thead', null, h('tr', null, h('th', null, 'When'), h('th', null, 'Who'), h('th', null, 'What'), h('th', null, 'Details'))),
        h('tbody', null, d.rows.map((a) => h('tr', null, h('td', { class: 'num' }, fmtTime(a.at)), h('td', null, first(a.userId)), h('td', null, a.action),
          h('td', null, a.target && /^I-/.test(a.target) ? h('a', { href: '#/invoice/' + a.target }, a.detail || a.target) : a.detail || a.target)))))))];
  }

  // ================================================================== router

  const ROUTES = [
    [/^\/$/, () => pageToday(), 'Today'],
    [/^\/invoices$/, (m, q) => pageInvoices(q), 'Invoices'],
    [/^\/invoice\/([\w-]+)$/, (m, q) => pageInvoice(m[1], q), 'Invoice'],
    [/^\/new$/, (m, q) => pageEditor(null, q), 'New invoice'],
    [/^\/edit\/([\w-]+)$/, (m, q) => pageEditor(m[1], q), 'Edit draft'],
    [/^\/customers$/, (m, q) => pageCustomers(q), 'Customers'],
    [/^\/customer\/([\w-]+)$/, (m, q) => pageCustomer(m[1], q), 'Customer'],
    [/^\/payments$/, (m, q) => pagePayments(q), 'Payments'],
    [/^\/bank$/, (m, q) => pageBank(q), 'Bank matching'],
    [/^\/repeating$/, () => pageRepeating(), 'Repeating invoices'],
    [/^\/outbox$/, (m, q) => pageOutbox(q), 'Outbox'],
    [/^\/reports$/, (m, q) => pageReports(q), 'Reports and GST'],
    [/^\/items$/, () => pageItems(), 'Items'],
    [/^\/desk$/, () => pageDesk(), 'Owner’s desk'],
    [/^\/activity$/, () => pageActivity(), 'Activity log']
  ];
  let routing = 0;
  async function route() {
    if (!S.user) return;
    const raw = location.hash.replace(/^#/, '') || '/';
    const [path, query] = raw.split('?');
    const q = Object.fromEntries(new URLSearchParams(query || ''));
    const my = ++routing;
    for (const [re, fn, title] of ROUTES) {
      const m = path.match(re);
      if (!m) continue;
      document.title = title + ' – Bijak';
      $topTitle.textContent = S.meta.company.name;
      for (const a of $nav.querySelectorAll('a')) {
        const href = a.getAttribute('href').slice(1);
        a.classList.toggle('on', href === '/' ? path === '/' : path === href || path.startsWith(href.replace(/s$/, '') + '/') || (href === '/new' && path.startsWith('/edit')));
      }
      try {
        const content = await fn(m, q);
        if (my !== routing) return;
        clear($view);
        add($view, [content]);
        window.scrollTo(0, 0);
      } catch (e) {
        if (my !== routing || e.status === 401) return;
        clear($view).appendChild(h('div', { class: 'paper' }, empty(e.status === 404 ? 'Not found' : 'This page could not be shown', e.message, h('a', { class: 'btn', href: '#/' }, 'Go to Today'))));
      }
      return;
    }
    location.hash = '#/';
  }
  window.addEventListener('hashchange', route);

  // ================================================================== start

  (async function start() {
    try {
      const r = await get('/api/me');
      if (r.user) { S.user = r.user; setMeta(r.meta); shell(); route(); }
      else showLogin();
    } catch (e) {
      clear($app).appendChild(h('div', { class: 'cover-wrap' }, h('div', { class: 'paper' }, empty('Cannot reach Bijak', e.message))));
    }
  })();
})();
