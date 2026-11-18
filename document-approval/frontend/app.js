/* Swikriti – Document Approval Workflow. Plain JavaScript, no framework.
   Pages are built with h() (document.createElement) – never innerHTML – so text typed by people can never run as code.
   Every page is a function that fetches JSON from the Java backend and draws the result. */
(function () {
  'use strict';

  // ================================================================== state and helpers

  const S = { user: null, meta: null, person: {}, type: {}, unread: 0, inbox: 0 };
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

  async function api(method, path, body) {
    const opt = { method, headers: { 'X-Swikriti': '1' }, credentials: 'same-origin' };
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

  let toastTimer = null;
  function toast(msg, bad) {
    $toast.textContent = msg;
    $toast.className = 'toast' + (bad ? ' bad' : '');
    $toast.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => { $toast.hidden = true; }, bad ? 5600 : 3200);
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
  const quiet = (p) => p.catch(() => undefined);

  function setMeta(meta) {
    S.meta = meta;
    S.person = {}; S.type = {};
    for (const p of meta.people) S.person[p.id] = p;
    for (const t of meta.templates) S.type[t.code] = t;
    S.unread = meta.unread; S.inbox = meta.inbox;
  }
  async function reloadMeta() { setMeta(await get('/api/meta')); paintCounts(); }

  const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
  function fmtDate(v) {
    if (!v) return '–';
    const d = typeof v === 'number' ? new Date(v) : new Date(v + (String(v).length === 10 ? 'T00:00:00' : ''));
    return d.getDate() + ' ' + MONTHS[d.getMonth()] + ' ' + d.getFullYear();
  }
  function fmtShort(ms) { if (!ms) return '–'; const d = new Date(ms); return d.getDate() + ' ' + MONTHS[d.getMonth()]; }
  function fmtTime(ms) {
    if (!ms) return '–';
    const d = new Date(ms);
    return fmtShort(ms) + ', ' + String(d.getHours()).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0');
  }
  function ago(ms) {
    const s = (Date.now() - ms) / 1000;
    if (s < 60) return 'just now';
    if (s < 3600) return Math.floor(s / 60) + ' min ago';
    if (s < 86400) return Math.floor(s / 3600) + ' h ago';
    const d = Math.floor(s / 86400);
    return d === 1 ? 'yesterday' : d < 30 ? d + ' days ago' : fmtDate(ms);
  }
  /** Rupees in the Indian way: 12,80,000. */
  function rs(n) {
    if (n === null || n === undefined) return '–';
    const s = String(Math.round(n));
    if (s.length <= 3) return '₹' + s;
    const last3 = s.slice(-3), rest = s.slice(0, -3);
    return '₹' + rest.replace(/\B(?=(\d{2})+(?!\d))/g, ',') + ',' + last3;
  }
  function hrs(h) { if (h === null || h === undefined) return '–'; return h < 10 ? h.toFixed(1) + ' h' : Math.round(h) + ' h'; }
  const plural = (n, w) => n + ' ' + w + (n === 1 ? '' : 's');
  const name = (id) => (S.person[id] ? S.person[id].name : id === 'system' ? 'Swikriti' : 'someone');
  const first = (id) => name(id).split(' ')[0];
  const typeName = (c) => (S.type[c] ? S.type[c].name : c);
  const STATUS = { DRAFT: 'Draft', IN_REVIEW: 'In review', RETURNED: 'Sent back', APPROVED: 'Approved', REJECTED: 'Rejected', WITHDRAWN: 'Withdrawn' };

  // ================================================================== small components

  const ICONS = {
    inbox: 'M3 13h5l2 3h4l2-3h5M5 5h14l2 8v6H3v-6z',
    docs: 'M7 3h7l5 5v13H7zM14 3v5h5M10 13h6M10 17h6',
    plus: 'M12 5v14M5 12h14',
    lib: 'M4 4h5v16H4zM10 4h4v16h-4zM15 5l4-1 3 15-4 1z',
    away: 'M12 7v5l3 2M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18z',
    chart: 'M4 20V10M10 20V4M16 20v-7M22 20H2',
    desk: 'M3 21h18M5 21V8l7-5 7 5v13M9 21v-6h6v6',
    chain: 'M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1',
    check: 'M9 12l2 2 4-4M12 3l7 4v5c0 4.4-3 8-7 9-4-1-7-4.6-7-9V7z',
    bell: 'M6 16V11a6 6 0 1 1 12 0v5l2 2H4zM10 21h4',
    menu: 'M4 6h16M4 12h16M4 18h16'
  };
  function icon(n) { return sv('svg', { viewBox: '0 0 24 24', 'aria-hidden': 'true' }, sv('path', { d: ICONS[n] })); }

  function delta(letter, cls) { return h('span', { class: 'delta' + (cls ? ' ' + cls : ''), title: 'Revision ' + letter }, h('span', null, letter)); }
  function status(st, big) { return h('span', { class: 'status ' + st + (big ? ' big' : '') }, STATUS[st] || st); }
  function typeTag(c) { return h('span', { class: 'tag type', title: typeName(c) }, c); }
  function avatar(pid, cls) {
    const p = S.person[pid];
    return h('span', { class: 'avatar ' + (cls || ''), title: p ? p.name : '' }, p ? p.initials : '?');
  }
  function dueChip(dueAt, overdue, hoursLeft) {
    if (overdue) return h('span', { class: 'due late' }, 'Overdue ' + hrs(-hoursLeft));
    if (hoursLeft !== undefined && hoursLeft <= 4) return h('span', { class: 'due soon' }, hrs(hoursLeft) + ' left');
    return h('span', { class: 'due fine' }, 'Due ' + fmtTime(dueAt));
  }
  function progress(pr, st) {
    const el = h('span', { class: 'progress', 'aria-label': pr.done + ' of ' + pr.total + ' stages done' });
    for (let i = 0; i < pr.total; i++) el.appendChild(h('i', { class: i < pr.done ? 'on' : i === pr.done && st === 'IN_REVIEW' ? 'now' : '' }));
    return el;
  }
  function empty(title, text, action) { return h('div', { class: 'empty' }, h('b', null, title), h('div', null, text), action ? h('div', { style: { 'margin-top': '12px' } }, action) : null); }
  function field(label, input, hint) { return h('label', { class: 'f' }, h('span', null, label, hint ? h('small', null, ' ', hint) : null), input); }

  function modal(content, wide) {
    const close = () => { clear($modal); document.removeEventListener('keydown', esc); };
    const esc = (e) => { if (e.key === 'Escape') close(); };
    const box = h('div', { class: 'modal' + (wide ? ' wide' : ''), role: 'dialog', 'aria-modal': 'true' });
    const bg = h('div', { class: 'modal-bg', onclick: (e) => { if (e.target === bg) close(); } }, box);
    add(box, [typeof content === 'function' ? content(close) : content]);
    clear($modal).appendChild(bg);
    document.addEventListener('keydown', esc);
    const f = box.querySelector('input, textarea, select, button');
    if (f) f.focus();
    return close;
  }

  /** The revision cloud: a scalloped outline around a block, drawn in SVG to the block's real size. */
  function drawCloud(el) {
    const old = el.querySelector(':scope > svg.c');
    if (old) old.remove();
    const w = el.offsetWidth, ht = el.offsetHeight;
    if (!w || !ht) return;
    const r = 9, pts = [];
    const nx = Math.max(2, Math.round(w / (r * 2))), ny = Math.max(1, Math.round(ht / (r * 2)));
    const sx = w / nx, sy = ht / ny;
    let d = 'M0 0';
    for (let i = 1; i <= nx; i++) d += ` A${sx / 2} ${r} 0 0 1 ${i * sx} 0`;
    for (let i = 1; i <= ny; i++) d += ` A${r} ${sy / 2} 0 0 1 ${w} ${i * sy}`;
    for (let i = nx - 1; i >= 0; i--) d += ` A${sx / 2} ${r} 0 0 1 ${i * sx} ${ht}`;
    for (let i = ny - 1; i >= 0; i--) d += ` A${r} ${sy / 2} 0 0 1 0 ${i * sy}`;
    pts.push(d);
    el.insertBefore(sv('svg', { class: 'c', viewBox: `0 0 ${w} ${ht}`, preserveAspectRatio: 'none', 'aria-hidden': 'true' }, sv('path', { d })), el.firstChild);
  }
  function cloudsIn(root) { requestAnimationFrame(() => root.querySelectorAll('.cloud').forEach(drawCloud)); }
  window.addEventListener('resize', () => document.querySelectorAll('.cloud').forEach(drawCloud));

  // ================================================================== the shell (rail + top bar)

  let $view = null, $crumb = null, $rail = null;

  const NAV = [
    ['inbox', 'Inbox', '#/inbox', 'inbox'],
    ['docs', 'Documents', '#/docs', 'docs'],
    ['new', 'New document', '#/new', 'plus'],
    ['library', 'Policy library', '#/library', 'lib'],
    ['delegations', 'Out of office', '#/delegations', 'away']
  ];

  function shell() {
    clear($app);
    const me = S.user;
    $rail = h('aside', { class: 'rail', 'aria-label': 'Main menu' },
      h('a', { class: 'brand', href: '#/inbox' },
        sv('svg', { class: 'tri', viewBox: '0 0 34 30', 'aria-hidden': 'true' },
          sv('path', { d: 'M17 2 32 28H2Z', fill: 'none', stroke: 'currentColor', 'stroke-width': '2', 'stroke-linejoin': 'round' }),
          sv('path', { d: 'M11 19l4 4 8-10', fill: 'none', stroke: '#6fe3ad', 'stroke-width': '2.6', 'stroke-linecap': 'round', 'stroke-linejoin': 'round' })),
        h('div', null, h('b', null, 'SWIKRITI'), h('span', null, 'Document approvals'))),
      h('nav', { class: 'nav' },
        NAV.map(([key, label, href, ic]) => h('a', { href, 'data-k': key }, icon(ic), label, key === 'inbox' ? h('span', { class: 'count', id: 'cnt-inbox', hidden: true }) : null)),
        me.manager ? [h('div', { class: 'sep' }), h('a', { href: '#/reports', 'data-k': 'reports' }, icon('chart'), 'Reports')] : null,
        me.admin ? [h('div', { class: 'sep' }), h('div', { class: 'grp' }, 'Document Controller'),
          h('a', { href: '#/admin', 'data-k': 'admin' }, icon('desk'), 'Controller desk'),
          h('a', { href: '#/ledger', 'data-k': 'ledger' }, icon('chain'), 'Approval ledger')] : null,
        h('div', { class: 'sep' }),
        h('a', { href: '#/verify', 'data-k': 'verify' }, icon('check'), 'Check a certificate')),
      h('div', { class: 'me' },
        h('div', { class: 'who' }, avatar(me.id), h('div', null, h('b', null, me.name), h('small', null, me.title))),
        h('div', { class: 'row' },
          h('button', { onclick: toggleTheme, id: 'themebtn' }, themeLabel()),
          h('button', { onclick: changePassword }, 'Password'),
          h('button', { onclick: logout }, 'Sign out'))));
    $crumb = h('div', { class: 'crumb grow' });
    $view = h('main', { class: 'view', id: 'view', tabindex: '-1' });
    const top = h('header', { class: 'top' },
      h('button', { class: 'bell menu', 'aria-label': 'Menu', onclick: () => $rail.classList.toggle('open') }, icon('menu')),
      $crumb,
      h('button', { class: 'bell', 'aria-label': 'Notifications', onclick: showNotes }, icon('bell'), h('i', { id: 'cnt-notes', hidden: true })));
    $app.appendChild(h('div', { class: 'shell' }, $rail, h('div', { class: 'page' }, top, $view)));
    paintCounts();
  }

  function paintCounts() {
    const a = document.getElementById('cnt-inbox'), b = document.getElementById('cnt-notes');
    if (a) { a.hidden = !S.inbox; a.textContent = S.inbox; }
    if (b) { b.hidden = !S.unread; b.textContent = S.unread; }
  }

  function themeNow() {
    const t = document.documentElement.getAttribute('data-theme');
    if (t) return t;
    return window.matchMedia && matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
  }
  function themeLabel() { return themeNow() === 'dark' ? 'Whiteprint' : 'Cyanotype'; }
  function toggleTheme() {
    const next = themeNow() === 'dark' ? 'light' : 'dark';
    document.documentElement.setAttribute('data-theme', next);
    try { localStorage.setItem('swikriti-theme', next); } catch (e) { /* storage blocked */ }
    const b = document.getElementById('themebtn');
    if (b) b.textContent = themeLabel();
    document.querySelectorAll('.cloud').forEach(drawCloud);
  }

  function page(key, crumb) {
    for (const a of $rail.querySelectorAll('.nav a')) a.classList.toggle('on', a.getAttribute('data-k') === key);
    $rail.classList.remove('open');
    $crumb.textContent = crumb;
    document.title = crumb + ' – Swikriti';
    clear($view);
    $view.appendChild(h('p', { class: 'muted' }, 'Loading…'));
    return $view;
  }

  async function logout() {
    await quiet(post('/api/logout'));
    S.user = null;
    location.hash = '';
    showLogin();
  }

  function changePassword() {
    const cur = h('input', { type: 'password', autocomplete: 'current-password' });
    const nx = h('input', { type: 'password', autocomplete: 'new-password' });
    const err = h('div', { class: 'err' });
    modal((close) => h('div', null, h('h2', null, 'Change your password'),
      field('Current password', cur), field('New password', nx, '8+ characters, a letter and a number'), err,
      h('div', { class: 'foot' }, h('button', { class: 'btn', onclick: close }, 'Cancel'),
        h('button', { class: 'btn primary', onclick: async () => {
          await act(() => post('/api/me/password', { current: cur.value, next: nx.value }), null, err);
          close(); toast('Password changed. Please sign in again.'); showLogin();
        } }, 'Change password'))));
  }

  async function showNotes() {
    const data = await get('/api/notes');
    modal((close) => h('div', null,
      h('div', { class: 'row between' }, h('h2', null, 'Notifications'),
        data.unread ? h('button', { class: 'btn sm', onclick: async () => { await post('/api/notes/read', {}); S.unread = 0; paintCounts(); close(); } }, 'Mark all as read') : null),
      data.notes.length ? h('div', { class: 'notes-list' }, data.notes.map((n) => h('a', {
        class: 'note' + (n.read ? '' : ' unread'), href: n.link || '#/inbox',
        onclick: () => { close(); if (!n.read) quiet(post('/api/notes/read', { id: n.id })).then(() => { S.unread = Math.max(0, S.unread - 1); paintCounts(); }); }
      }, h('i'), h('div', null, h('div', null, n.text), h('small', null, ago(n.at))))))
        : empty('Nothing new', 'You will be told here when something waits for you or when your documents move on.')));
  }

  // ================================================================== sign in

  const DEMO = [
    ['ankit', 'member123', 'Design engineer – a drawing sent back with redlines, a purchase in review'],
    ['sunita', 'manager123', 'Projects manager – approvals waiting, one overdue'],
    ['rahul', 'manager123', 'Finance manager – signs for Farah (out of office)'],
    ['kavita', 'manager123', 'Managing Director – 2 of 3 directors, an SOP to approve'],
    ['neha', 'admin123', 'Document Controller – workflows, people, the ledger']
  ];

  function planArt() {
    // a quiet plan drawing behind the sign-in text: grid lines, footings and a dimension line
    const g = [];
    for (let i = 0; i < 6; i++) g.push(sv('line', { x1: 40 + i * 90, y1: 20, x2: 40 + i * 90, y2: 360, stroke: '#cfe1ff', 'stroke-dasharray': '14 4 3 4' }));
    for (let j = 0; j < 4; j++) g.push(sv('line', { x1: 10, y1: 50 + j * 95, x2: 520, y2: 50 + j * 95, stroke: '#cfe1ff', 'stroke-dasharray': '14 4 3 4' }));
    for (let i = 0; i < 6; i++) for (let j = 0; j < 4; j++) g.push(sv('rect', { x: 40 + i * 90 - 16, y: 50 + j * 95 - 16, width: 32, height: 32, fill: 'none', stroke: '#e8f1ff', 'stroke-width': 1.6 }));
    g.push(sv('path', { d: 'M40 400H490M40 394v12M130 394v12M220 394v12M310 394v12M400 394v12M490 394v12', stroke: '#e8f1ff' }));
    return sv('svg', { class: 'plan', viewBox: '0 0 540 420', 'aria-hidden': 'true' }, ...g);
  }

  function showLogin() {
    clear($app); clear($modal);
    document.title = 'Sign in – Swikriti';
    const user = h('input', { type: 'text', autocomplete: 'username', id: 'u' });
    const pass = h('input', { type: 'password', autocomplete: 'current-password', id: 'p' });
    const err = h('div', { class: 'err', role: 'alert' });
    const go = async (e) => {
      if (e) e.preventDefault();
      try {
        const r = await act(() => post('/api/login', { username: user.value, password: pass.value }), null, err);
        S.user = r.user; setMeta(r.meta);
        shell();
        if (!location.hash || location.hash === '#/' || location.hash === '#/verify') location.hash = '#/inbox'; else route();
      } catch (x) { pass.select(); }
    };
    $app.appendChild(h('div', { class: 'login' },
      h('section', { class: 'art' },
        planArt(),
        h('div', { style: { position: 'relative' } },
          h('div', { class: 'row', style: { 'margin-bottom': '28px' } },
            sv('svg', { viewBox: '0 0 34 30', width: 34, height: 30, 'aria-hidden': 'true' },
              sv('path', { d: 'M17 2 32 28H2Z', fill: 'none', stroke: '#e8f1ff', 'stroke-width': '2', 'stroke-linejoin': 'round' }),
              sv('path', { d: 'M11 19l4 4 8-10', fill: 'none', stroke: '#6fe3ad', 'stroke-width': '2.6', 'stroke-linecap': 'round' })),
            h('b', { style: { 'font-family': 'var(--font-head)', 'font-size': '1.4rem', 'letter-spacing': '.08em' } }, 'SWIKRITI')),
          h('h1', null, 'Every approval signed, every signature checked.'),
          h('p', null, 'Purchase requests, contracts, drawings and policies go through their approval route here – with deadlines, out-of-office cover and a ledger that shows if anything was changed later.')),
        h('div', { class: 'tb', 'aria-hidden': 'true' },
          h('div', null, h('small', null, 'Document'), 'PR/26-27/0038'), h('div', null, h('small', null, 'Rev'), 'A'), h('div', null, h('small', null, 'Status'), 'In review'),
          h('div', null, h('small', null, 'Drawn'), h('span', { class: 'sig' }, 'A. Toppo')), h('div', null, h('small', null, 'Checked'), h('span', { class: 'sig' }, 'R. Hembrom')), h('div', null, h('small', null, 'Approved'), h('span', { class: 'sig' }, 'A. Mahto'))),
        h('p', { class: 'tiny', style: { position: 'relative', margin: '14px 0 0' } }, 'Damodar Consulting Engineers is a made-up company. All people are made up.')),
      h('section', { class: 'form' },
        h('div', { class: 'inner' },
          h('h2', null, 'Sign in'),
          h('form', { onsubmit: go },
            field('Username', user), field('Password', pass), err,
            h('div', { class: 'row between' },
              h('button', { class: 'btn primary', type: 'submit' }, 'Sign in'),
              h('a', { href: '#/verify', onclick: (e) => { e.preventDefault(); showVerifyPublic(); } }, 'Check a certificate'))),
          h('div', { class: 'demo' },
            h('div', { class: 'small muted', style: { 'margin-bottom': '6px' } }, 'Demo accounts – click to fill in'),
            DEMO.map(([u, p, d]) => h('button', { type: 'button', onclick: () => { user.value = u; pass.value = p; go(); } }, h('b', null, u), h('span', null, d))))))));
    user.focus();
  }

  // ================================================================== router

  async function route() {
    if (!S.user) return;
    const parts = (location.hash || '#/inbox').slice(2).split('/');
    const [a, b, c] = parts;
    try {
      switch (a) {
        case '': case 'inbox': await pageInbox(); break;
        case 'docs': await pageDocs(); break;
        case 'new': await pageNew(b); break;
        case 'edit': await pageEdit(b); break;
        case 'doc': await pageDoc(b, c); break;
        case 'library': await pageLibrary(); break;
        case 'delegations': await pageDelegations(); break;
        case 'reports': await pageReports(); break;
        case 'admin': await pageAdmin(b); break;
        case 'ledger': await pageLedger(); break;
        case 'verify': await pageVerify(b); break;
        default: location.hash = '#/inbox';
      }
      $view.focus({ preventScroll: true });
    } catch (e) {
      if (e.status === 401) return;
      clear($view).appendChild(empty(e.status === 404 ? 'Not found' : 'Could not open this page', e.message, h('a', { class: 'btn', href: '#/inbox' }, 'Go to the inbox')));
    }
  }
  window.addEventListener('hashchange', route);

  // ================================================================== inbox

  function slip(d, extra) {
    const late = extra && extra.overdue;
    return h('a', { class: 'slip' + (late ? ' late' : ''), href: '#/doc/' + d.id },
      h('div', null, h('div', { class: 'no' }, d.number || 'Draft'), h('div', { class: 'row tight', style: { 'margin-top': '4px' } }, typeTag(d.type), d.rev ? delta(d.rev) : null)),
      h('div', { style: { 'min-width': 0 } },
        h('div', { class: 'ti ellipsis' }, d.title),
        h('div', { class: 'sub' },
          h('span', null, 'From ' + name(d.authorId)),
          d.amount ? h('span', null, rs(d.amount)) : null,
          extra && extra.stageName ? h('span', null, extra.stageName) : d.stage ? h('span', null, 'At ' + d.stage) : null,
          extra && extra.via ? h('span', { class: 'tag ' + (String(extra.via).startsWith('escalated') ? 'red' : 'blue') },
            String(extra.via).startsWith('delegate of ') ? 'For ' + name(String(extra.via).slice(12)) : String(extra.via).startsWith('escalated') ? 'Escalated to you' : 'Sent to the Controller') : null,
          d.urgent ? h('span', { class: 'tag warn' }, 'Urgent') : null)),
      h('div', { class: 'end' },
        extra && extra.dueAt ? dueChip(extra.dueAt, extra.overdue, extra.hoursLeft) : status(d.status),
        d.progress && d.progress.total ? progress(d.progress, d.status) : null));
  }

  async function pageInbox() {
    const v = page('inbox', 'Inbox');
    const data = await get('/api/inbox');
    S.inbox = data.waiting.length; paintCounts();
    const late = data.waiting.filter((w) => w.overdue).length;
    const returned = data.mine.filter((d) => d.status === 'RETURNED');
    clear(v);
    v.appendChild(h('div', { class: 'pagehead' },
      h('div', null, h('h1', null, 'Good ' + (new Date().getHours() < 12 ? 'morning' : new Date().getHours() < 17 ? 'afternoon' : 'evening') + ', ' + S.user.name.split(' ')[0]),
        h('p', null, data.waiting.length ? 'Documents wait for your signature. The oldest deadline is first.' : 'Nothing waits for your signature right now.')),
      h('a', { class: 'btn primary', href: '#/new' }, 'New document')));
    v.appendChild(h('div', { class: 'dims' },
      h('div', { class: 'dim' }, h('b', null, data.waiting.length), h('span', null, 'waiting for you')),
      h('div', { class: 'dim' + (late ? ' red' : '') }, h('b', null, late), h('span', null, 'overdue')),
      h('div', { class: 'dim' }, h('b', null, data.mine.filter((d) => d.status === 'IN_REVIEW').length), h('span', null, 'of yours in review')),
      h('div', { class: 'dim' + (returned.length ? ' red' : '') }, h('b', null, returned.length), h('span', null, 'sent back to you'))));
    if (data.delegations.length) {
      v.appendChild(h('div', { class: 'sheet', style: { 'margin-bottom': '18px' } }, data.delegations.map((g) =>
        h('div', { class: 'row' }, icon('away'),
          g.fromId === S.user.id ? h('span', null, name(g.toId) + ' approves for you from ' + fmtDate(g.start) + ' to ' + fmtDate(g.end) + '.')
            : h('span', null, 'You approve for ' + name(g.fromId) + ' from ' + fmtDate(g.start) + ' to ' + fmtDate(g.end) + '.'),
          h('a', { href: '#/delegations' }, 'Out of office')))));
    }
    v.appendChild(h('div', { class: 'cols' },
      h('section', null,
        h('h2', null, 'Waiting for your signature'),
        data.waiting.length ? h('div', { class: 'slips' }, data.waiting.map((w) => slip(w, w)))
          : empty('All signed', 'When a document reaches a stage where you sign, it appears here.')),
      h('div', { class: 'side' },
        h('section', { class: 'sheet' },
          h('div', { class: 'hd' }, h('h3', null, 'My open documents'), h('a', { href: '#/docs', onclick: () => { DOCF.scope = 'mine'; } }, 'All')),
          data.mine.length ? h('div', { class: 'slips' }, data.mine.slice(0, 6).map((d) => h('a', { class: 'row tight', href: '#/doc/' + d.id, style: { 'text-decoration': 'none', color: 'inherit', padding: '6px 0', 'border-bottom': '1px solid var(--line-2)' } },
            h('div', { class: 'grow' }, h('div', { class: 'ellipsis', style: { 'font-weight': 500 } }, d.title), h('div', { class: 'tiny muted code' }, d.number || 'not submitted yet')),
            status(d.status))))
            : h('p', { class: 'muted small' }, 'You have no open documents.')),
        h('section', { class: 'sheet' },
          h('h3', null, 'Recently signed by you'),
          data.decided.length ? data.decided.map((d) => h('a', { class: 'row tight', href: '#/doc/' + d.id, style: { 'text-decoration': 'none', color: 'inherit', padding: '5px 0' } },
            h('span', { class: 'tag ' + (d.decision === 'APPROVED' ? 'ok' : d.decision === 'REJECTED' ? 'red' : 'warn') }, d.decision === 'APPROVED' ? 'Approved' : d.decision === 'REJECTED' ? 'Rejected' : 'Sent back'),
            h('span', { class: 'grow ellipsis small' }, d.title), h('span', { class: 'tiny muted nowrap' }, ago(d.decidedAt))))
            : h('p', { class: 'muted small' }, 'Nothing yet.')))));
  }

  // ================================================================== documents register

  const DOCF = { scope: '', status: '', type: '', q: '' };

  async function pageDocs() {
    const v = page('docs', 'Documents');
    const list = h('div');
    const load = async () => {
      const qs = new URLSearchParams(DOCF).toString();
      const data = await get('/api/docs?' + qs);
      clear(list);
      if (!data.docs.length) { list.appendChild(empty('No documents found', 'Try another filter, or write a new document.', h('a', { class: 'btn', href: '#/new' }, 'New document'))); return; }
      list.appendChild(h('div', { class: 'sheet tablewrap' }, h('table', { class: 'register' },
        h('thead', null, h('tr', null, h('th', null, 'Number'), h('th', null, 'Title'), h('th', null, 'From'), h('th', { class: 'right' }, 'Amount'), h('th', null, 'Rev'), h('th', null, 'Status'), h('th', null, 'Updated'))),
        h('tbody', null, data.docs.map((d) => h('tr', { class: 'link', tabindex: '0', onclick: () => { location.hash = '#/doc/' + d.id; }, onkeydown: (e) => { if (e.key === 'Enter') location.hash = '#/doc/' + d.id; } },
          h('td', { class: 'num' }, d.number || '–'),
          h('td', null, h('div', { class: 't' }, d.title), h('div', { class: 'tiny muted' }, typeName(d.type), d.stage ? ', at ' + d.stage : '')),
          h('td', { class: 'nowrap' }, name(d.authorId)),
          h('td', { class: 'right nowrap' }, d.amount ? rs(d.amount) : '–'),
          h('td', null, d.rev ? delta(d.rev) : '–'),
          h('td', null, status(d.status)),
          h('td', { class: 'nowrap small muted' }, ago(d.updatedAt))))))),
      data.total > data.docs.length ? h('p', { class: 'muted small' }, 'Showing the newest ' + data.docs.length + ' of ' + data.total + '.') : null);
    };
    const seg = h('div', { class: 'seg', role: 'group', 'aria-label': 'Which documents' }, [['', 'All I can see'], ['mine', 'Written by me'], ['signed', 'Signed by me']].map(([k, l]) =>
      h('button', { class: DOCF.scope === k ? 'on' : '', onclick: (e) => { DOCF.scope = k; seg.querySelectorAll('button').forEach((b) => b.classList.remove('on')); e.target.classList.add('on'); load(); } }, l)));
    const stSel = h('select', { 'aria-label': 'Status', onchange: (e) => { DOCF.status = e.target.value; load(); } }, h('option', { value: '' }, 'Any status'),
      Object.entries(STATUS).map(([k, l]) => h('option', { value: k, selected: DOCF.status === k }, l)));
    const tySel = h('select', { 'aria-label': 'Type', onchange: (e) => { DOCF.type = e.target.value; load(); } }, h('option', { value: '' }, 'Any type'),
      S.meta.templates.map((t) => h('option', { value: t.code, selected: DOCF.type === t.code }, t.name)));
    let tm = null;
    const q = h('input', { type: 'search', placeholder: 'Search by title, number, vendor or author', value: DOCF.q, 'aria-label': 'Search',
      oninput: (e) => { clearTimeout(tm); tm = setTimeout(() => { DOCF.q = e.target.value; load(); }, 250); } });
    clear(v);
    v.appendChild(h('div', { class: 'pagehead' }, h('div', null, h('h1', null, 'Documents'), h('p', null, 'The register of every document you are allowed to read.')),
      S.user.admin ? h('a', { class: 'btn', href: '/api/export.csv' }, 'Download register (CSV)') : null));
    v.appendChild(h('div', { class: 'filters' }, seg, stSel, tySel, q));
    v.appendChild(list);
    await load();
  }

  // ================================================================== one document: the drawing sheet

  const ACTION = { SUBMIT: 'Submitted', APPROVE: 'Approved', REJECT: 'Rejected', RETURN: 'Sent back', WITHDRAW: 'Withdrawn', CERTIFIED: 'Certificate issued', 'CLOSED-REJECTED': 'Closed as rejected' };

  async function pageDoc(id, tab) {
    const v = page('docs', 'Document');
    const d = await get('/api/docs/' + id);
    $crumb.textContent = (d.number || 'Draft') + ' – ' + d.title;
    document.title = (d.number || 'Draft') + ' – Swikriti';
    const cur = d.revisions.length ? d.revisions[d.revisions.length - 1] : null;
    tab = tab || 'sheet';
    clear(v);

    // ---- top actions
    const actions = h('div', { class: 'row' });
    if (d.can.edit) actions.appendChild(h('a', { class: 'btn', href: '#/edit/' + d.id }, 'Edit'));
    if (d.can.edit) actions.appendChild(h('button', { class: 'btn primary', onclick: () => submitDoc(d) }, d.revisions.length ? 'Submit revision ' + nextLetter(d.revisions.length) : 'Submit for approval'));
    if (d.can.withdraw) actions.appendChild(h('button', { class: 'btn danger', onclick: () => withdrawDoc(d) }, 'Withdraw'));
    v.appendChild(h('div', { class: 'row between', style: { 'margin-bottom': '14px' } }, h('a', { href: '#/docs' }, 'All documents'), actions));

    // ---- what the reader must do now
    if (d.myTask) v.appendChild(decidePanel(d));
    if (d.status === 'RETURNED' && d.authorId === S.user.id) {
      const back = d.decisions.filter((x) => x.state === 'RETURNED').pop();
      v.appendChild(h('div', { class: 'sheet', style: { 'border-left': '4px solid var(--warn)', 'margin-bottom': '18px' } },
        h('h3', null, 'Sent back to you'),
        back ? h('p', null, name(back.by) + ' (' + back.stageName + '): ', h('em', null, back.comment)) : null,
        h('p', { class: 'muted small' }, 'Fix the redlined lines, then submit revision ' + nextLetter(d.revisions.length) + '. The route starts again from the first stage.'),
        h('div', { class: 'row' }, h('a', { class: 'btn', href: '#/edit/' + d.id }, 'Edit'), h('button', { class: 'btn primary', onclick: () => submitDoc(d) }, 'Submit revision ' + nextLetter(d.revisions.length)))));
    }
    if (d.status === 'DRAFT' && d.plan) v.appendChild(h('div', { class: 'sheet', style: { 'margin-bottom': '18px' } },
      h('h3', null, 'This draft is not submitted yet'), h('p', { class: 'muted small' }, 'Who will sign it when you submit it now:'), planList(d.plan)));

    // ---- tabs
    const tabs = [['sheet', 'Sheet'], d.revisions.length > 1 ? ['changes', 'Changes'] : null, d.ledger.length ? ['ledger', 'Ledger'] : null].filter(Boolean);
    v.appendChild(h('div', { class: 'tabs', role: 'tablist' }, tabs.map(([k, l]) => h('button', { role: 'tab', 'aria-selected': String(k === tab), class: k === tab ? 'on' : '',
      onclick: () => { location.hash = '#/doc/' + d.id + (k === 'sheet' ? '' : '/' + k); } }, l))));

    if (tab === 'changes' && d.revisions.length > 1) return v.appendChild(await changesView(d));
    if (tab === 'ledger') return v.appendChild(ledgerView(d));
    v.appendChild(sheetView(d, cur));
  }

  function nextLetter(n) { let s = '', x = n + 1; while (x > 0) { s = String.fromCharCode(65 + (x - 1) % 26) + s; x = Math.floor((x - 1) / 26); } return s; }

  function sheetView(d, cur) {
    const show = cur || d;                                      // a draft shows its working copy
    const zx = h('div', { class: 'zones-x', 'aria-hidden': 'true' }, [1, 2, 3, 4, 5, 6, 7, 8].map((n) => h('span', null, n)));
    const zy = h('div', { class: 'zones-y', 'aria-hidden': 'true' }, ['A', 'B', 'C', 'D', 'E'].map((n) => h('span', null, n)));
    const sheet = h('article', { class: 'drawing' }, zx, zy);

    // header: number, title, status and revision table
    const revRows = d.revisions.map((r, i) => h('tr', { class: i === d.revisions.length - 1 ? 'cur' : '' },
      h('td', null, delta(r.letter)), h('td', { class: 'nowrap' }, fmtShort(r.at)), h('td', null, r.note || (i === 0 ? 'First issue for approval' : '–')), h('td', null, first(d.authorId))));
    sheet.appendChild(h('div', { class: 'dhead' },
      h('div', null,
        h('div', { class: 'no' }, h('span', null, d.number || 'Not numbered yet'), typeTag(d.type), h('span', { class: 'muted' }, d.typeName), cur ? delta(cur.letter, 'lg') : null),
        h('h1', null, show.title),
        h('div', { class: 'by' }, 'Written by ', h('b', null, name(d.authorId)), ', ' + (d.dept || '') + '. ', d.submittedAt ? 'First submitted ' + fmtDate(d.submittedAt) + '.' : 'Not submitted yet.')),
      h('div', { class: 'stack', style: { 'text-align': 'right' } },
        status(d.status, true),
        d.revisions.length ? h('table', { class: 'revtable' }, h('thead', null, h('tr', null, h('th', null, 'Rev'), h('th', null, 'Date'), h('th', null, 'Description'), h('th', null, 'By'))), h('tbody', null, revRows)) : null)));

    // schedule of fields
    const sched = [];
    if (d.hasAmount) sched.push(['Amount', rs(show.amount)]);
    if (show.vendor) sched.push(['Vendor / contractor', show.vendor]);
    if (show.needBy) sched.push(['Needed by', fmtDate(show.needBy)]);
    sched.push(['Priority', show.urgent ? 'Urgent (half the time per stage)' : 'Normal']);
    if (d.cc.length) sched.push(['Copy to', d.cc.map(name).join(', ')]);
    if (cur) sched.push(['Fingerprint (SHA-256)', h('span', { class: 'code', title: cur.hash }, cur.hash.slice(0, 16) + '…')]);
    sheet.appendChild(h('div', { class: 'schedule' }, sched.map(([k, val]) => h('div', null, h('small', null, k), h('b', null, val)))));

    // attachment
    const att = show.attachment;
    if (att) {
      const rev = cur ? cur.no : 0;
      const url = '/api/docs/' + d.id + '/file?rev=' + rev;
      sheet.appendChild(h('div', { class: 'attach' + (att.mime.startsWith('image/') ? ' big' : '') },
        att.mime.startsWith('image/') ? h('a', { href: url + '&inline=1', target: '_blank', rel: 'noopener' }, h('img', { src: url + '&inline=1', alt: 'Attached drawing: ' + att.name, loading: 'lazy' })) : h('span', { class: 'fileicon' }, 'PDF'),
        h('div', { class: 'small' }, h('a', { href: url }, att.name), ' ', h('span', { class: 'muted' }, Math.round(att.size / 1024) + ' KB, fingerprint '), h('span', { class: 'code muted' }, att.sha.slice(0, 12) + '…'))));
    }

    // notes with line numbers and the redlines in the margin
    const comments = d.comments.filter((c) => cur && c.rev === cur.no);
    const byLine = {};
    comments.forEach((c, i) => { c.k = i + 1; (byLine[c.line] = byLine[c.line] || []).push(c); });
    const text = (show.body || '').split('\n');
    const lines = h('div', { class: 'lines' }, show.body ? text.map((t, i) => {
      const n = i + 1, marks = byLine[n] || [];
      return h('div', { class: 'ln' + (marks.some((c) => !c.resolved) ? ' marked' : '') },
        h('span', { class: 'n' }, n), h('span', { class: 'tx' }, t || ' '),
        marks.length ? h('span', { class: 'balloon', title: 'Redline ' + marks.map((c) => c.k).join(', ') }, marks[0].k)
          : d.can.comment ? h('button', { class: 'add', title: 'Mark up line ' + n, 'aria-label': 'Add a redline on line ' + n, onclick: () => addRedline(d, n, t) }, '+') : h('span'));
    }) : h('p', { class: 'muted', style: { padding: '10px 0' } }, 'No written details – see the attachment.'));
    const margin = h('aside', { class: 'redlines', 'aria-label': 'Redlines' },
      comments.length ? comments.map((c) => h('div', { class: 'redline' + (c.resolved ? ' done' : '') },
        h('span', { class: 'balloon' }, c.k),
        h('div', null, h('p', null, c.text),
          h('div', { class: 'meta' }, (c.line ? 'Line ' + c.line + ', ' : 'Whole document, ') + name(c.userId) + ', ' + ago(c.at),
            !c.resolved && (c.userId === S.user.id || d.authorId === S.user.id) && !['APPROVED', 'REJECTED', 'WITHDRAWN'].includes(d.status)
              ? [' ', h('button', { class: 'btn ghost sm', onclick: async () => { await act(() => post('/api/comments/' + c.id + '/resolve'), 'Redline closed'); route(); } }, 'Close')] : null))))
        : h('p', { class: 'muted small' }, d.can.comment ? 'No redlines. Use + at the end of a line to mark it up.' : 'No redlines on this revision.'),
      d.can.comment ? h('button', { class: 'btn sm', onclick: () => addRedline(d, 0, null) }, 'Redline the whole document') : null);
    sheet.appendChild(h('div', { class: 'notes' }, lines, margin));

    // the approval route
    if (d.route.length) {
      sheet.appendChild(h('h3', { style: { 'margin-top': '26px' } }, 'Approval route' + (cur ? ' for revision ' + cur.letter : '')));
      sheet.appendChild(h('div', { class: 'route' }, d.route.map((r) => stageBox(d, r))));
    }

    // the title block
    sheet.appendChild(h('div', { class: 'row', style: { 'justify-content': 'flex-end', 'margin-top': '24px' } }, h('div', { style: { width: 'min(560px, 100%)' } }, titleBlock(d, cur))));
    return sheet;
  }

  const STAGE_STATE = { WAITING: 'Waiting', ACTIVE: 'Now', DONE: 'Signed', SKIPPED: 'Not needed', FAILED: 'Rejected', STOPPED: 'Stopped' };
  const TASK_CLS = { APPROVED: 'ok', REJECTED: 'red', RETURNED: 'warn', PENDING: 'wait' };

  function stageBox(d, r) {
    const mode = r.mode === 'ALL' ? (r.seats.length > 1 ? 'All ' + r.seats.length + ' sign' : '') : r.mode === 'ANY' ? (r.seats.length > 1 ? 'Any one signs' : '') : r.quorum + ' of ' + r.seats.length + ' sign';
    return h('div', { class: 'stage ' + r.state },
      h('div', { class: 'row between tight' }, h('h4', null, r.name), h('span', { class: 'st ' + (r.state === 'DONE' ? 'tag ok' : r.state === 'ACTIVE' ? 'tag blue' : r.state === 'FAILED' ? 'tag red' : 'tag') }, STAGE_STATE[r.state])),
      h('div', { class: 'rule' }, r.seats.map(ruleText).join(' + '), mode ? ' – ' + mode : ''),
      r.state === 'SKIPPED' ? h('div', { class: 'small', style: { 'margin-top': '6px' } }, 'Only when ' + r.condition) :
        h('div', { class: 'seats' }, r.tasks.map((k) => {
          const who = k.decidedBy || null;
          if (who) return h('span', { class: 'seat', title: name(who) + (k.onBehalfOf ? ' for ' + name(k.onBehalfOf) : '') + ': ' + (k.comment || k.state.toLowerCase()) }, avatar(who, TASK_CLS[k.state] + ' sm'));
          if (k.state === 'PENDING') return h('span', { class: 'seat', title: 'Waiting for ' + k.assignees.map(name).join(' or ') }, k.assignees.slice(0, 3).map((a) => avatar(a, 'wait sm')), k.assignees.length > 3 ? h('span', { class: 'tiny' }, '+' + (k.assignees.length - 3)) : null);
          return h('span', { class: 'seat tiny muted' }, k.state === 'NOT_NEEDED' ? 'not needed' : 'cancelled');
        })),
      r.state === 'ACTIVE' && r.tasks.length ? h('div', { class: 'tiny muted' }, 'Due ' + fmtTime(r.tasks[0].dueAt)) : null,
      r.state === 'DONE' && r.doneAt ? h('div', { class: 'tiny muted' }, 'Done ' + fmtTime(r.doneAt)) : null);
  }

  function ruleText(rule) {
    if (rule === 'MANAGER') return 'line manager';
    if (rule === 'MANAGER2') return "manager's manager";
    if (rule.startsWith('GROUP:')) return rule.slice(6);
    if (rule.startsWith('USER:')) return name(rule.slice(5));
    return rule;
  }

  function titleBlock(d, cur) {
    const cells = [
      h('div', { class: 'wide' }, h('small', null, 'Company'), h('span', { class: 'firm' }, 'Damodar Consulting Engineers')),
      h('div', { class: 'wide' }, h('small', null, 'Title'), h('b', null, d.title)),
      h('div', null, h('small', null, 'Document no.'), h('span', { class: 'code' }, d.number || '–')),
      h('div', null, h('small', null, 'Revision'), cur ? cur.letter : '–'),
      h('div', null, h('small', null, d.type === 'DRG' ? 'Drawn' : 'Written'), h('div', { class: 'sig' }, name(d.authorId)), h('small', null, cur ? fmtDate(cur.at) : 'draft')),
      h('div', null, h('small', null, 'Type'), d.typeName)
    ];
    const ledgerBy = {};
    for (const e of d.ledger) if (cur && e.rev === cur.no) ledgerBy[e.action + e.userId] = e;
    for (const r of d.route) {
      if (r.state === 'SKIPPED') continue;
      const signed = r.tasks.filter((k) => k.state === 'APPROVED' || k.state === 'REJECTED' || k.state === 'RETURNED');
      cells.push(h('div', null, h('small', null, r.name),
        signed.length ? signed.map((k) => {
          const e = ledgerBy[(k.state === 'APPROVED' ? 'APPROVE' : k.state === 'REJECTED' ? 'REJECT' : 'RETURN') + k.decidedBy];
          return h('div', null,
            h('div', { class: 'sig' + (k.state === 'APPROVED' ? '' : ' no') }, (k.state === 'APPROVED' ? '' : k.state === 'REJECTED' ? 'Rejected – ' : 'Sent back – ') + name(k.decidedBy)),
            k.onBehalfOf ? h('small', null, 'for ' + name(k.onBehalfOf)) : null,
            h('small', null, fmtDate(k.decidedAt)), e ? h('div', { class: 'h', title: e.hash }, '#' + e.hash.slice(0, 14)) : null);
        }) : h('div', { class: 'sig wait' }, r.state === 'ACTIVE' ? 'Waiting' : r.state === 'STOPPED' ? 'Stopped' : '–')));
    }
    if (cells.length % 2) cells.push(h('div'));
    cells.push(h('div', { class: 'wide' + (d.certificate ? ' verify' : '') },
      d.certificate ? [h('small', null, 'Verification code – check it under "Check a certificate"'), h('b', null, d.certificate)]
        : [h('small', null, 'Status'), STATUS[d.status]]));
    return h('div', { class: 'titleblock', 'aria-label': 'Title block' }, cells);
  }

  function decidePanel(d) {
    const t = d.myTask;
    const r = d.route[t.stage];
    const note = h('textarea', { rows: 3, placeholder: 'Comment (needed when you send back or reject)', 'aria-label': 'Comment' });
    const err = h('div', { class: 'err' });
    const go = async (action) => {
      const r2 = await act(() => post('/api/tasks/' + t.id + '/decide', { action, comment: note.value }), null, err);
      const msg = { approved: 'Approved – the document is fully approved', 'next stage': 'Signed – it moves to the next stage', 'waiting for more': 'Signed – other seats still need to sign',
        returned: 'Sent back to the author', rejected: 'Rejected', 'vote recorded': 'Your “no” is recorded' }[r2.outcome] || 'Saved';
      toast(msg);
      await reloadMeta();
      route();
    };
    const via = t.via ? String(t.via) : '';
    return h('section', { class: 'decide', style: { 'margin-bottom': '18px' }, 'aria-label': 'Your decision' },
      h('div', { class: 'row between' },
        h('div', null, h('h3', null, 'Your signature: ' + r.name),
          h('div', { class: 'small muted' }, via.startsWith('delegate of ') ? 'You sign for ' + name(via.slice(12)) + ' (out of office). ' : via.startsWith('escalated') ? 'It is overdue, so it was escalated to you. ' : via ? 'Nobody else was found for this seat. ' : '',
            'Due ' + fmtTime(t.dueAt) + '. You sign revision ' + d.revisions[d.revisions.length - 1].letter + ' – its fingerprint goes into the ledger.')),
        Date.now() > t.dueAt ? h('span', { class: 'due late' }, 'Overdue') : null),
      h('div', { style: { 'margin-top': '10px' } }, note), err,
      h('div', { class: 'row' },
        h('button', { class: 'btn ok', onclick: () => go('APPROVE') }, 'Approve'),
        h('button', { class: 'btn warn', onclick: () => go('RETURN') }, 'Send back for changes'),
        h('button', { class: 'btn danger', onclick: () => go('REJECT') }, 'Reject')));
  }

  function addRedline(d, line, text) {
    const ta = h('textarea', { rows: 3, placeholder: 'What should change?' });
    const err = h('div', { class: 'err' });
    modal((close) => h('div', null, h('h2', null, line ? 'Redline on line ' + line : 'Redline on the whole document'),
      text ? h('p', { class: 'muted', style: { 'border-left': '3px solid var(--red)', 'padding-left': '10px' } }, text) : null,
      ta, err, h('div', { class: 'foot' }, h('button', { class: 'btn', onclick: close }, 'Cancel'),
        h('button', { class: 'btn primary', onclick: async () => { await act(() => post('/api/docs/' + d.id + '/comments', { line, text: ta.value }), 'Redline added', err); close(); route(); } }, 'Add redline'))));
  }

  function planList(stages) {
    return h('div', { class: 'preview' }, stages.map((s, i) => h('div', { class: 'pv' + (s.needed ? '' : ' skip') },
      h('span', { class: 'k' }, i + 1),
      h('div', null, h('b', null, s.name), s.needed ? null : h('span', { class: 'small' }, ' – not needed (only when ' + s.condition + ')'),
        s.needed ? h('div', { class: 'ppl' }, s.seats.map((x) => (x.fallback ? 'Document Controller (nobody found for ' + ruleText(x.rule) + ')' : x.people.map(name).join(' or '))).join(s.mode === 'ALL' ? ' and ' : ' / '),
          s.mode === 'QUORUM' ? ' – ' + s.needed_count + ' must sign' : '', ', ' + s.slaHours + ' office hours') : null))));
  }

  async function submitDoc(d) {
    const again = d.revisions.length > 0;
    const note = h('textarea', { rows: 3, placeholder: 'For example: F2 enlarged, founding level corrected' });
    const err = h('div', { class: 'err' });
    let plan = d.plan;
    if (!plan) { const p = await get('/api/docs/' + d.id); plan = p.plan; }
    modal((close) => h('div', null,
      h('h2', null, again ? 'Submit revision ' + nextLetter(d.revisions.length) : 'Submit for approval'),
      h('p', { class: 'muted small' }, 'The text is frozen and signed with its fingerprint. These people will be asked to sign, in this order:'),
      plan ? planList(plan) : null,
      again ? field('What did you change?', note, 'everybody sees it in the revision table') : null, err,
      h('div', { class: 'foot' }, h('button', { class: 'btn', onclick: close }, 'Cancel'),
        h('button', { class: 'btn primary', onclick: async () => {
          await act(() => post('/api/docs/' + d.id + '/submit', { note: note.value }), 'Submitted', err);
          close(); await reloadMeta(); location.hash = '#/doc/' + d.id; route();
        } }, again ? 'Submit revision ' + nextLetter(d.revisions.length) : 'Submit'))), true);
  }

  function withdrawDoc(d) {
    const why = h('input', { type: 'text', placeholder: 'For example: no longer needed' });
    const err = h('div', { class: 'err' });
    modal((close) => h('div', null, h('h2', null, 'Withdraw this document?'),
      h('p', { class: 'muted' }, 'It stops at once and cannot be submitted again. The approvers are told.'),
      d.status !== 'DRAFT' ? field('Reason', why) : null, err,
      h('div', { class: 'foot' }, h('button', { class: 'btn', onclick: close }, 'Keep it'),
        h('button', { class: 'btn danger', onclick: async () => { await act(() => post('/api/docs/' + d.id + '/withdraw', { reason: why.value }), 'Withdrawn', err); close(); await reloadMeta(); route(); } }, 'Withdraw'))));
  }

  async function changesView(d) {
    const n = d.revisions.length;
    const box = h('div');
    const aSel = h('select', { 'aria-label': 'From revision' }), bSel = h('select', { 'aria-label': 'To revision' });
    d.revisions.forEach((r) => { aSel.appendChild(h('option', { value: r.no, selected: r.no === n - 1 }, 'Revision ' + r.letter)); bSel.appendChild(h('option', { value: r.no, selected: r.no === n }, 'Revision ' + r.letter)); });
    const out = h('div');
    const load = async () => {
      const a = +aSel.value, b = +bSel.value;
      clear(out);
      if (a === b) { out.appendChild(h('p', { class: 'muted' }, 'Choose two different revisions.')); return; }
      const df = await get('/api/docs/' + d.id + '/diff?a=' + a + '&b=' + b);
      if (df.note) out.appendChild(h('p', null, h('b', null, 'Author’s note for ' + df.b + ': '), df.note));
      if (df.fields.length) out.appendChild(h('table', { class: 'fieldchanges small' }, df.fields.map((f) => h('tr', null, h('td', { class: 'muted' }, f.field), h('td', { class: 'from' }, f.from || '–'), h('td', null, f.to || '–')))));
      out.appendChild(h('p', { class: 'muted small' }, plural(df.changes, 'line') + ' changed. Changed lines are circled with a revision cloud, as on a drawing.'));
      const diff = h('div', { class: 'diff' });
      let group = null;
      for (const l of df.lines) {
        const row = h('div', { class: 'dl ' + l.kind }, h('span', null, l.old || ''), h('span', null, l.new || ''), h('span', { class: 'mk' }, l.kind === 'ADD' ? '+' : l.kind === 'DEL' ? '−' : ''), h('span', { class: 'tx' }, l.text || ' '));
        if (l.kind === 'SAME') { group = null; diff.appendChild(row); continue; }
        if (!group) { group = h('div', { class: 'cloud' }, delta(df.b, 'red')); diff.appendChild(group); }
        group.appendChild(row);
      }
      out.appendChild(diff);
      cloudsIn(out);
    };
    aSel.onchange = load; bSel.onchange = load;
    box.appendChild(h('div', { class: 'sheet framed' }, h('div', { class: 'filters' }, h('span', null, 'Compare'), aSel, h('span', null, 'with'), bSel), out));
    await load();
    return box;
  }

  function ledgerView(d) {
    return h('div', { class: 'sheet framed' },
      h('h2', null, 'Ledger entries for this document'),
      h('p', { class: 'muted small', style: { 'max-width': '70ch' } }, 'Every submit and decision is a link in one company-wide hash chain. Each link is signed with a secret key and contains the hash of the link before it, so a change to an old entry breaks every later link. The Document Controller can check the whole chain.'),
      h('div', { class: 'chain' }, d.ledger.map((e) => h('div', { class: 'link' },
        h('span', { class: 'seq' }, '#' + e.seq),
        h('div', null,
          h('div', null, h('span', { class: 'a' }, ACTION[e.action] || e.action), ' ', delta(nextLetter(e.rev - 1)), ' ', h('span', { class: 'muted small' }, (e.userId === 'system' ? 'by Swikriti' : 'by ' + name(e.userId)) + (e.onBehalfOf ? ' for ' + name(e.onBehalfOf) : '') + ', ' + fmtTime(e.at))),
          h('div', { class: 'h' }, 'document fingerprint ' + e.docHash),
          h('div', { class: 'h' }, 'previous ' + e.prev.slice(0, 24) + '…  this ' + e.hash.slice(0, 24) + '…'))))));
  }

  // ================================================================== new / edit document

  async function pageNew(code) {
    const v = page('new', 'New document');
    clear(v);
    if (!code || !S.type[code]) {
      v.appendChild(h('div', { class: 'pagehead' }, h('div', null, h('h1', null, 'New document'), h('p', null, 'Choose what you need approved. Each type has its own approval route.'))));
      v.appendChild(h('div', { class: 'types' }, S.meta.templates.filter((t) => t.active).map((t) => h('button', { class: 'typecard', onclick: () => { location.hash = '#/new/' + t.code; } },
        h('span', { class: 'code' }, t.code), h('b', null, t.name), h('span', null, t.description)))));
      return;
    }
    docForm(v, null, S.type[code]);
  }

  async function pageEdit(id) {
    const v = page('docs', 'Edit document');
    const d = await get('/api/docs/' + id);
    if (!d.can.edit) { location.hash = '#/doc/' + id; return; }
    clear(v);
    docForm(v, d, S.type[d.type]);
  }

  function docForm(v, d, t) {
    const val = d || { title: '', amount: 0, vendor: '', urgent: false, needBy: '', body: '', dept: S.user.dept, cc: [] };
    const title = h('input', { type: 'text', value: val.title, maxlength: 140, required: true });
    const amount = h('input', { type: 'number', min: 0, step: 1, value: val.amount || '', inputmode: 'numeric' });
    const amountHint = h('small', { class: 'muted' });
    const vendor = h('input', { type: 'text', value: val.vendor || '', maxlength: 80 });
    const needBy = h('input', { type: 'date', value: val.needBy || '', min: S.meta.today });
    const urgent = h('input', { type: 'checkbox', checked: val.urgent });
    const dept = h('input', { type: 'text', value: val.dept || '', maxlength: 40 });
    const body = h('textarea', { class: 'lined', rows: 12, maxlength: 20000 }, val.body || '');
    const cc = h('select', { multiple: true, size: 5, 'aria-label': 'Copy to' }, S.meta.people.filter((p) => p.active && p.id !== S.user.id).map((p) => h('option', { value: p.id, selected: val.cc.includes(p.id) }, p.name + ' – ' + (p.title || ''))));
    const err = h('div', { class: 'err', role: 'alert' });
    const preview = h('div', null, h('p', { class: 'muted small' }, 'Working out the route…'));
    const values = () => ({ type: t.code, title: title.value, amount: t.hasAmount ? Number(amount.value || 0) : 0, vendor: vendor.value, needBy: needBy.value, urgent: urgent.checked,
      dept: dept.value, body: body.value, cc: Array.from(cc.selectedOptions).map((o) => o.value) });
    let tm = null;
    const refresh = () => {
      clearTimeout(tm);
      amountHint.textContent = amount.value ? rs(Number(amount.value)) : '';
      tm = setTimeout(async () => {
        const r = await quiet(post('/api/preview', values()));
        if (r) clear(preview).appendChild(planList(r.stages));
      }, 300);
    };
    [title, amount, vendor, urgent, dept].forEach((x) => x.addEventListener('input', refresh));
    urgent.addEventListener('change', refresh);
    const save = async () => {
      const b = values();
      return d ? act(() => put('/api/docs/' + d.id, b), null, err) : act(() => post('/api/docs', b), null, err);
    };
    // attachment (only once the draft exists)
    const attBox = h('div');
    const paintAtt = (doc) => {
      clear(attBox);
      if (!doc) { attBox.appendChild(h('p', { class: 'muted small' }, 'Save the draft first, then you can attach a PDF, PNG or JPG (up to 1 MB).')); return; }
      if (doc.attachment) {
        attBox.appendChild(h('div', { class: 'attach' }, h('span', { class: 'fileicon' }, doc.attachment.mime === 'application/pdf' ? 'PDF' : 'IMG'),
          h('div', { class: 'small' }, h('a', { href: '/api/docs/' + doc.id + '/file?rev=0' }, doc.attachment.name), ' ', h('span', { class: 'muted' }, Math.round(doc.attachment.size / 1024) + ' KB'), ' ',
            h('button', { class: 'btn ghost sm', onclick: async () => { const r = await act(() => del('/api/docs/' + doc.id + '/attach'), 'File removed'); paintAtt(r); } }, 'Remove'))));
      }
      const input = h('input', { type: 'file', accept: '.pdf,.png,.jpg,.jpeg,application/pdf,image/png,image/jpeg' });
      const zone = h('label', { class: 'dropzone' }, input, doc.attachment ? 'Replace the file' : 'Choose a file (PDF, PNG or JPG, up to 1 MB)');
      input.addEventListener('change', () => upload(doc, input.files[0]));
      zone.addEventListener('dragover', (e) => { e.preventDefault(); zone.classList.add('over'); });
      zone.addEventListener('dragleave', () => zone.classList.remove('over'));
      zone.addEventListener('drop', (e) => { e.preventDefault(); zone.classList.remove('over'); if (e.dataTransfer.files[0]) upload(doc, e.dataTransfer.files[0]); });
      attBox.appendChild(zone);
    };
    const upload = (doc, file) => {
      if (!file) return;
      if (file.size > 1000000) { toast('The file is larger than 1 MB.', true); return; }
      const rd = new FileReader();
      rd.onload = async () => {
        const data = String(rd.result).split(',')[1];
        const r = await act(() => post('/api/docs/' + doc.id + '/attach', { name: file.name, data }), 'File attached');
        paintAtt(r);
      };
      rd.readAsDataURL(file);
    };
    paintAtt(d);

    v.appendChild(h('div', { class: 'pagehead' }, h('div', null,
      h('div', { class: 'row tight' }, typeTag(t.code), h('span', { class: 'muted' }, t.name), d && d.number ? h('span', { class: 'code muted' }, d.number) : null),
      h('h1', null, d ? 'Edit ' + (d.status === 'RETURNED' ? 'for revision ' + nextLetter(d.revisions.length) : 'draft') : 'New ' + t.name.toLowerCase()),
      h('p', null, t.description)), d ? h('a', { href: '#/doc/' + d.id }, 'Back to the document') : h('a', { href: '#/new' }, 'Choose another type')));
    v.appendChild(h('div', { class: 'cols' },
      h('section', { class: 'sheet framed' },
        field('Title', title),
        t.hasAmount ? h('div', { class: 'grid2' }, field('Amount (₹)', amount, amountHint), field('Vendor / contractor', vendor, 'optional')) : null,
        h('div', { class: 'grid2' }, field('Needed by', needBy, 'optional'), field('Department', dept)),
        h('label', { class: 'check' }, urgent, 'Urgent – every stage gets half the time'),
        field('Details', body, 'one point per line – reviewers can redline each line'),
        field('Copy to', cc, 'they can read it and are told the result (Ctrl+click for more than one)'),
        h('h3', { style: { 'margin-top': '8px' } }, 'Attachment'), attBox,
        err,
        h('div', { class: 'row', style: { 'margin-top': '14px' } },
          h('button', { class: 'btn', onclick: async () => { const r = await save(); toast('Draft saved'); if (!d) location.hash = '#/edit/' + r.id; } }, 'Save draft'),
          h('button', { class: 'btn primary', onclick: async () => { const r = await save(); const full = await get('/api/docs/' + r.id); location.hash = '#/doc/' + r.id; setTimeout(() => submitDoc(full), 150); } },
            d && d.revisions.length ? 'Save and submit revision ' + nextLetter(d.revisions.length) : 'Save and submit'))),
      h('aside', { class: 'side' }, h('section', { class: 'sheet' }, h('h3', null, 'Who will sign'), h('p', { class: 'muted small' }, 'This changes as you type – for example, a higher amount can add Finance or the directors.'), preview))));
    refresh();
    title.focus();
  }

  // ================================================================== library

  async function pageLibrary() {
    const v = page('library', 'Policy library');
    const data = await get('/api/library');
    clear(v);
    v.appendChild(h('div', { class: 'pagehead' }, h('div', null, h('h1', null, 'Policy library'), h('p', null, 'Approved policies and SOPs. Everybody in the company can read them.'))));
    if (!data.docs.length) { v.appendChild(empty('No policies yet', 'When a policy or SOP is approved, it appears here.')); return; }
    v.appendChild(h('div', { class: 'types' }, data.docs.map((d) => h('a', { class: 'sheet', href: '#/doc/' + d.id, style: { 'text-decoration': 'none', color: 'inherit', margin: 0 } },
      h('div', { class: 'row tight' }, h('span', { class: 'code small muted' }, d.number), delta(d.rev)),
      h('h3', { style: { 'margin-top': '6px' } }, d.title),
      h('p', { class: 'small muted', style: { 'white-space': 'pre-line' } }, d.excerpt),
      h('div', { class: 'tiny muted' }, 'Approved ' + fmtDate(d.closedAt) + ', code ' + d.certificate)))));
  }

  // ================================================================== out of office (delegations)

  async function pageDelegations() {
    const v = page('delegations', 'Out of office');
    const data = await get('/api/delegations');
    clear(v);
    const to = h('select', null, h('option', { value: '' }, 'Choose a person'), S.meta.people.filter((p) => p.active && p.id !== S.user.id).map((p) => h('option', { value: p.id }, p.name + ' – ' + (p.title || ''))));
    const start = h('input', { type: 'date', value: data.today, min: data.today });
    const end = h('input', { type: 'date', min: data.today });
    const types = h('div', null, S.meta.templates.map((t) => h('label', { class: 'check' }, h('input', { type: 'checkbox', value: t.code }), t.name)));
    const note = h('input', { type: 'text', maxlength: 200, placeholder: 'For example: on leave, call me only if urgent' });
    const err = h('div', { class: 'err' });
    const state = (g) => g.cancelled ? h('span', { class: 'tag' }, 'Cancelled') : g.end < data.today ? h('span', { class: 'tag' }, 'Ended') : g.start > data.today ? h('span', { class: 'tag blue' }, 'Planned') : h('span', { class: 'tag ok' }, 'Active now');
    const row = (g, mine) => h('tr', null,
      h('td', null, mine ? name(g.toId) : name(g.fromId)),
      h('td', { class: 'nowrap' }, fmtDate(g.start) + ' – ' + fmtDate(g.end)),
      h('td', { class: 'small' }, g.types.length ? g.types.map(typeName).join(', ') : 'All types'),
      h('td', { class: 'small muted' }, g.note || ''),
      h('td', null, state(g)),
      h('td', null, mine && !g.cancelled && g.end >= data.today ? h('button', { class: 'btn sm danger', onclick: async () => { await act(() => post('/api/delegations/' + g.id + '/cancel'), 'Cancelled'); route(); } }, 'Cancel') : null));
    v.appendChild(h('div', { class: 'pagehead' }, h('div', null, h('h1', null, 'Out of office'), h('p', null, 'Name a person who approves for you while you are away. Work that already waits for you moves to them as well. You can still sign yourself.'))));
    v.appendChild(h('div', { class: 'cols' },
      h('div', null,
        h('section', { class: 'sheet' }, h('h2', null, 'People who approve for me'),
          data.mine.length ? h('div', { class: 'tablewrap' }, h('table', { class: 'register' }, h('thead', null, h('tr', null, h('th', null, 'Who'), h('th', null, 'Dates'), h('th', null, 'For'), h('th', null, 'Note'), h('th', null, ''), h('th', null, ''))), h('tbody', null, data.mine.map((g) => row(g, true)))))
            : h('p', { class: 'muted' }, 'You have not named anybody yet.')),
        h('section', { class: 'sheet' }, h('h2', null, 'I approve for'),
          data.forMe.length ? h('div', { class: 'tablewrap' }, h('table', { class: 'register' }, h('thead', null, h('tr', null, h('th', null, 'Who'), h('th', null, 'Dates'), h('th', null, 'For'), h('th', null, 'Note'), h('th', null, ''), h('th', null, ''))), h('tbody', null, data.forMe.map((g) => row(g, false)))))
            : h('p', { class: 'muted' }, 'Nobody has asked you to approve for them.'))),
      h('aside', { class: 'side' }, h('section', { class: 'sheet framed' }, h('h3', null, 'I will be away'),
        field('Who approves for me', to), h('div', { class: 'grid2' }, field('From', start), field('To', end)),
        h('div', { class: 'small', style: { 'margin-bottom': '4px' } }, 'Only for these types ', h('span', { class: 'muted' }, '(none ticked = all)')), types,
        field('Note', note), err,
        h('button', { class: 'btn primary', onclick: async () => {
          const ty = Array.from(types.querySelectorAll('input:checked')).map((i) => i.value);
          await act(() => post('/api/delegations', { toId: to.value, start: start.value, end: end.value, types: ty, note: note.value }), 'Saved – ' + name(to.value) + ' approves for you', err);
          route();
        } }, 'Save')))));
  }

  // ================================================================== reports

  async function pageReports() {
    const v = page('reports', 'Reports');
    const r = await get('/api/reports');
    clear(v);
    v.appendChild(h('div', { class: 'pagehead' }, h('div', null, h('h1', null, 'How approvals are going'),
      h('p', null, 'Times are office hours (Monday to Friday, 09:30–18:00, without holidays), from the first submit to the final approval.'))));
    v.appendChild(h('div', { class: 'dims' },
      h('div', { class: 'dim' }, h('b', null, r.median === null ? '–' : hrs(r.median)), h('span', null, 'median time to approval')),
      h('div', { class: 'dim' }, h('b', null, r.p85 === null ? '–' : hrs(r.p85)), h('span', null, '85 % are approved within')),
      h('div', { class: 'dim' + (r.onTime !== null && r.onTime < 80 ? ' red' : ' ok') }, h('b', null, r.onTime === null ? '–' : r.onTime, h('small', null, ' %')), h('span', null, 'decisions on time (' + r.decisions + ')')),
      h('div', { class: 'dim' }, h('b', null, r.openTasks), h('span', null, 'signatures waiting')),
      h('div', { class: 'dim' + (r.overdue ? ' red' : '') }, h('b', null, r.overdue), h('span', null, 'overdue now'))));

    const types = h('section', { class: 'sheet tablewrap' }, h('h2', null, 'By document type'), h('table', { class: 'register' },
      h('thead', null, h('tr', null, h('th', null, 'Type'), h('th', { class: 'right' }, 'Submitted'), h('th', { class: 'right' }, 'Approved'), h('th', { class: 'right' }, 'Rejected'), h('th', { class: 'right' }, 'Open'),
        h('th', { class: 'right' }, 'Sent back'), h('th', { class: 'right' }, 'Approval rate'), h('th', { class: 'right' }, 'Median'), h('th', { class: 'right' }, '85th pct'))),
      h('tbody', null, r.types.map((t) => h('tr', null, h('td', null, typeTag(t.code), ' ', t.name), h('td', { class: 'right' }, t.submitted), h('td', { class: 'right' }, t.approved), h('td', { class: 'right' }, t.rejected),
        h('td', { class: 'right' }, t.open), h('td', { class: 'right' }, t.returns), h('td', { class: 'right' }, t.approvalRate === null ? '–' : t.approvalRate + ' %'),
        h('td', { class: 'right nowrap' }, hrs(t.median)), h('td', { class: 'right nowrap' }, hrs(t.p85)))))));

    const maxW = Math.max(1, ...r.stages.map((s) => s.p85));
    const stages = h('section', { class: 'sheet' }, h('div', { class: 'hd' }, h('h2', null, 'Where documents wait'),
      h('span', { class: 'legend' }, h('span', null, h('i', { style: { background: 'var(--pencil)' } }), 'median'), h('span', null, h('i', { style: { background: 'var(--ink-2)', height: '2px' } }), 'up to 85th percentile'))),
      r.bottleneck ? h('p', { class: 'small muted' }, 'The slowest stage is ', h('b', null, r.bottleneck), '.') : null,
      h('div', { class: 'bars' }, r.stages.slice(0, 12).map((s, i) => h('div', { class: 'bar' + (i === 0 ? ' top' : '') },
        h('span', { class: 'ellipsis', title: s.stage }, s.stage),
        h('span', { class: 'track' }, h('span', { class: 'fill', style: { width: (100 * s.median / maxW) + '%' } }), h('span', { class: 'whisk', style: { left: (100 * s.median / maxW) + '%', width: (100 * (s.p85 - s.median) / maxW) + '%' } })),
        h('span', { class: 'small right nowrap' }, hrs(s.median), h('span', { class: 'muted' }, ' · ' + s.onTime + '%'))))),
      h('p', { class: 'tiny muted', style: { 'margin-top': '10px' } }, 'The % after the time is the share of decisions made before the deadline.'));

    // 12 weeks: submitted (outline) and approved (filled) per week
    const W = 720, H = 200, pad = 28, bw = (W - pad * 2) / r.weeks.length;
    const max = Math.max(1, ...r.weeks.map((w) => Math.max(w.submitted, w.approved)));
    const y = (n) => H - 24 - (H - 50) * n / max;
    const chart = sv('svg', { viewBox: `0 0 ${W} ${H}`, role: 'img', 'aria-label': 'Documents submitted and approved per week, last 12 weeks' },
      sv('line', { x1: pad, x2: W - pad, y1: H - 24, y2: H - 24, stroke: 'currentColor', 'stroke-opacity': '.4' }),
      ...r.weeks.flatMap((w, i) => {
        const x = pad + i * bw;
        return [
          sv('rect', { x: x + bw * .18, y: y(w.submitted), width: bw * .3, height: H - 24 - y(w.submitted), fill: 'none', stroke: 'var(--ink-2)', 'stroke-width': 1.4 }),
          sv('rect', { x: x + bw * .52, y: y(w.approved), width: bw * .3, height: H - 24 - y(w.approved), fill: 'var(--ok)' }),
          i % 2 === 0 ? sv('text', { x: x + bw / 2, y: H - 8, 'text-anchor': 'middle' }, fmtShort(new Date(w.week + 'T00:00:00').getTime())) : null,
          sv('title', null, 'Week of ' + fmtDate(w.week) + ': ' + w.submitted + ' submitted, ' + w.approved + ' approved, ' + w.rejected + ' rejected')
        ].filter(Boolean);
      }));
    const weeks = h('section', { class: 'sheet chart' }, h('div', { class: 'hd' }, h('h2', null, 'Last 12 weeks'),
      h('span', { class: 'legend' }, h('span', null, h('i', { style: { border: '1.5px solid var(--ink-2)' } }), 'submitted'), h('span', null, h('i', { style: { background: 'var(--ok)' } }), 'approved'))), chart);

    const people = h('section', { class: 'sheet' }, h('h3', null, 'Most signatures waiting'),
      r.people.length ? r.people.map((p) => h('div', { class: 'row tight', style: { padding: '5px 0' } }, avatar(p.id), h('span', { class: 'grow' }, name(p.id)), h('b', null, p.pending)))
        : h('p', { class: 'muted small' }, 'Nothing waits.'));
    v.appendChild(h('div', { class: 'cols' }, h('div', { class: 'stack' }, types, stages, weeks), h('aside', { class: 'side' }, people)));
  }

  // ================================================================== the Document Controller's desk

  async function pageAdmin(tab) {
    const v = page('admin', 'Controller desk');
    const data = await get('/api/admin');
    tab = tab || 'workflows';
    clear(v);
    v.appendChild(h('div', { class: 'pagehead' }, h('div', null, h('h1', null, 'Controller desk'), h('p', null, 'Workflows, people and office holidays. Every change is written to the activity log.')),
      h('div', { class: 'row' },
        h('button', { class: 'btn', onclick: async () => { const r = await act(() => post('/api/admin/tick')); toast(r.reminded + ' reminders sent, ' + r.escalated + ' escalated'); } }, 'Send reminders now'),
        h('a', { class: 'btn', href: '/api/export.csv' }, 'Download register (CSV)'))));
    v.appendChild(h('div', { class: 'tabs' }, [['workflows', 'Workflows'], ['people', 'People'], ['holidays', 'Holidays'], ['log', 'Activity log']].map(([k, l]) =>
      h('button', { class: k === tab ? 'on' : '', onclick: () => { location.hash = '#/admin/' + k; } }, l))));
    if (tab === 'people') v.appendChild(adminPeople(data));
    else if (tab === 'holidays') v.appendChild(adminHolidays(data));
    else if (tab === 'log') v.appendChild(adminLog(data));
    else v.appendChild(adminWorkflows(data));
  }

  function adminWorkflows(data) {
    return h('div', null,
      h('div', { class: 'row', style: { 'margin-bottom': '14px' } }, h('button', { class: 'btn primary', onclick: () => editTemplate(null) }, 'New document type')),
      data.templates.map((t) => h('section', { class: 'sheet' },
        h('div', { class: 'hd' }, h('div', { class: 'row tight' }, typeTag(t.code), h('h3', null, t.name), t.active ? null : h('span', { class: 'tag' }, 'Switched off')),
          h('div', { class: 'row' }, h('span', { class: 'tiny muted' }, 'Version ' + t.version), h('button', { class: 'btn sm', onclick: () => editTemplate(t) }, 'Edit'))),
        h('p', { class: 'small muted' }, t.description,
          t.distinct ? ' One person signs only one stage.' : '', t.publishOnApproval ? ' Approved ones go to the library.' : ''),
        h('div', { class: 'route' }, t.stages.map((s) => stageBox({}, Object.assign({}, s, { state: s.condition ? 'WAITING' : 'WAITING', tasks: [] }))).map((el, i) => {
          const s = t.stages[i];
          el.appendChild(h('div', { class: 'tiny muted', style: { 'margin-top': '6px' } }, s.condition ? 'Only when ' + s.condition : 'Always', ', ' + s.slaHours + ' office hours'));
          el.querySelector('.st').remove();
          return el;
        })))));
  }

  function editTemplate(t) {
    const isNew = !t;
    t = t || { code: '', name: '', description: '', hasAmount: true, publishOnApproval: false, distinct: false, active: true, stages: [{ name: 'Line manager', seats: ['MANAGER'], mode: 'ANY', quorum: 1, condition: '', slaHours: 8 }] };
    const code = h('input', { type: 'text', value: t.code, maxlength: 5, disabled: !isNew, placeholder: 'e.g. PR' });
    const nm = h('input', { type: 'text', value: t.name, maxlength: 60 });
    const desc = h('input', { type: 'text', value: t.description, maxlength: 240 });
    const flags = [['hasAmount', 'Has an amount (₹)'], ['distinct', 'One person may sign only one stage'], ['publishOnApproval', 'Approved ones go to the library'], ['active', 'Switched on']]
      .map(([k, l]) => { const c = h('input', { type: 'checkbox', checked: t[k] }); c.dataset.k = k; return h('label', { class: 'check' }, c, l); });
    const list = h('div');
    const err = h('div', { class: 'err' });
    const stages = t.stages.map((s) => Object.assign({}, s, { seats: s.seats.slice() }));
    const paint = () => {
      clear(list);
      stages.forEach((s, i) => {
        const n = h('input', { type: 'text', value: s.name, oninput: (e) => { s.name = e.target.value; } });
        const seats = h('input', { type: 'text', value: s.seats.join(', '), placeholder: 'MANAGER, GROUP:Finance, USER:U-1001', oninput: (e) => { s.seats = e.target.value.split(',').map((x) => x.trim()).filter(Boolean); } });
        const mode = h('select', { onchange: (e) => { s.mode = e.target.value; } }, ['ALL', 'ANY', 'QUORUM'].map((m) => h('option', { value: m, selected: s.mode === m }, m === 'ALL' ? 'All seats' : m === 'ANY' ? 'Any one seat' : 'Quorum')));
        const q = h('input', { type: 'number', min: 1, max: 6, value: s.quorum, oninput: (e) => { s.quorum = +e.target.value; } });
        const sla = h('input', { type: 'number', min: 1, max: 200, value: s.slaHours, oninput: (e) => { s.slaHours = +e.target.value; } });
        const msg = h('div', { class: 'condok' }, s.condition ? '' : 'Always needed');
        let tm = null;
        const cond = h('input', { type: 'text', value: s.condition, placeholder: 'e.g. amount > 50k and not urgent', oninput: (e) => {
          s.condition = e.target.value;
          clearTimeout(tm);
          tm = setTimeout(async () => {
            if (!s.condition.trim()) { msg.className = 'condok'; msg.textContent = 'Always needed'; return; }
            const r = await quiet(post('/api/condition', { condition: s.condition }));
            if (!r) return;
            msg.className = r.ok ? 'condok' : 'condbad';
            msg.textContent = r.ok ? 'Condition is fine' : r.error;
          }, 250);
        } });
        list.appendChild(h('div', { class: 'stagerow' },
          h('div', { class: 'row between' }, h('b', null, 'Stage ' + (i + 1)), h('div', { class: 'row tight' },
            i > 0 ? h('button', { class: 'btn ghost sm', onclick: () => { stages.splice(i - 1, 0, stages.splice(i, 1)[0]); paint(); } }, 'Move up') : null,
            stages.length > 1 ? h('button', { class: 'btn ghost sm', onclick: () => { stages.splice(i, 1); paint(); } }, 'Remove') : null)),
          h('div', { class: 'grid2' }, field('Name', n), field('Seats', seats, 'comma between seats')),
          h('div', { class: 'grid3' }, field('How many must sign', mode), field('Quorum', q, 'for Quorum'), field('Time limit', sla, 'office hours')),
          field('Condition', cond, 'empty = always'), msg));
      });
    };
    paint();
    modal((close) => h('div', null,
      h('h2', null, isNew ? 'New document type' : 'Edit ' + t.name),
      h('p', { class: 'small muted' }, 'Documents already on their way keep the route they started with. Seats: MANAGER, MANAGER2, GROUP:<name> or USER:<id>. Conditions use amount, dept, type, vendor, title and urgent, e.g. ', h('span', { class: 'code' }, 'amount > 5L or dept = Projects'), '.'),
      h('div', { class: 'grid3' }, field('Code', code), field('Name', nm), field('Description', desc)),
      h('div', { class: 'grid2' }, flags),
      h('h3', null, 'Stages'), list,
      h('button', { class: 'btn sm', onclick: () => { stages.push({ name: 'New stage', seats: ['MANAGER'], mode: 'ANY', quorum: 1, condition: '', slaHours: 16 }); paint(); } }, 'Add a stage'),
      err,
      h('div', { class: 'foot' }, h('button', { class: 'btn', onclick: close }, 'Cancel'),
        h('button', { class: 'btn primary', onclick: async () => {
          const b = { code: code.value.trim().toUpperCase(), name: nm.value, description: desc.value, stages };
          for (const l of flags) { const c = l.querySelector('input'); b[c.dataset.k] = c.checked; }
          await act(() => (isNew ? post('/api/admin/templates', b) : put('/api/admin/templates/' + t.code, b)), 'Workflow saved', err);
          close(); await reloadMeta(); route();
        } }, 'Save workflow'))), true);
  }

  function adminPeople(data) {
    return h('section', { class: 'sheet tablewrap' },
      h('div', { class: 'hd' }, h('h2', null, plural(data.people.length, 'person')), h('button', { class: 'btn primary sm', onclick: () => editPerson(null, data) }, 'Add a person')),
      h('table', { class: 'register' }, h('thead', null, h('tr', null, h('th', null, 'Name'), h('th', null, 'Username'), h('th', null, 'Department'), h('th', null, 'Manager'), h('th', null, 'Groups'), h('th', null, 'Role'), h('th', null, 'Last seen'), h('th', null, ''))),
        h('tbody', null, data.people.map((p) => h('tr', null,
          h('td', null, h('div', { class: 'row tight' }, avatar(p.id), h('div', null, h('div', { class: 't' }, p.name), h('div', { class: 'tiny muted' }, p.title)))),
          h('td', { class: 'code small' }, p.username), h('td', null, p.dept), h('td', null, p.managerId ? name(p.managerId) : '–'),
          h('td', null, p.groups.map((g) => h('span', { class: 'tag', style: { 'margin-right': '4px' } }, g))),
          h('td', null, p.role === 'ADMIN' ? 'Controller' : p.role === 'MANAGER' ? 'Manager' : 'Employee', p.active ? null : h('span', { class: 'tag red', style: { 'margin-left': '6px' } }, 'Switched off')),
          h('td', { class: 'small muted nowrap' }, p.lastSeen ? ago(p.lastSeen) : 'never'),
          h('td', null, h('button', { class: 'btn sm', onclick: () => editPerson(p, data) }, 'Edit')))))));
  }

  function editPerson(p, data) {
    const isNew = !p;
    p = p || { username: '', name: '', title: '', dept: '', location: 'Ranchi', role: 'EMPLOYEE', managerId: '', groups: [], active: true };
    const f = {
      name: h('input', { type: 'text', value: p.name }), username: h('input', { type: 'text', value: p.username }),
      title: h('input', { type: 'text', value: p.title || '' }), dept: h('input', { type: 'text', value: p.dept || '' }), location: h('input', { type: 'text', value: p.location || '' }),
      role: h('select', null, [['EMPLOYEE', 'Employee'], ['MANAGER', 'Manager'], ['ADMIN', 'Document Controller']].map(([k, l]) => h('option', { value: k, selected: p.role === k }, l))),
      managerId: h('select', null, h('option', { value: '' }, 'Nobody (top)'), data.people.filter((x) => x.id !== p.id).map((x) => h('option', { value: x.id, selected: p.managerId === x.id }, x.name))),
      groups: h('input', { type: 'text', value: p.groups.join(', '), placeholder: 'Finance, Legal, Directors …' }),
      password: h('input', { type: 'password', autocomplete: 'new-password', placeholder: isNew ? '' : 'leave empty to keep' }),
      active: h('input', { type: 'checkbox', checked: p.active })
    };
    const err = h('div', { class: 'err' });
    modal((close) => h('div', null, h('h2', null, isNew ? 'Add a person' : 'Edit ' + p.name),
      h('div', { class: 'grid2' }, field('Name', f.name), field('Username', f.username), field('Job title', f.title), field('Department', f.dept), field('Location', f.location), field('Role', f.role), field('Line manager', f.managerId), field('Approver groups', f.groups, 'comma between groups')),
      field(isNew ? 'First password' : 'New password', f.password, '8+ characters, a letter and a number'),
      h('label', { class: 'check' }, f.active, 'Can sign in'), err,
      h('div', { class: 'foot' }, h('button', { class: 'btn', onclick: close }, 'Cancel'), h('button', { class: 'btn primary', onclick: async () => {
        const b = { name: f.name.value, username: f.username.value, title: f.title.value, dept: f.dept.value, location: f.location.value, role: f.role.value, managerId: f.managerId.value,
          groups: f.groups.value.split(',').map((x) => x.trim()).filter(Boolean), active: f.active.checked };
        if (f.password.value) b.password = f.password.value;
        await act(() => (isNew ? post('/api/admin/users', b) : put('/api/admin/users/' + p.id, b)), 'Saved', err);
        close(); await reloadMeta(); route();
      } }, 'Save'))));
  }

  function adminHolidays(data) {
    const ta = h('textarea', { rows: 12, class: 'code' }, data.holidays.join('\n'));
    const err = h('div', { class: 'err' });
    return h('section', { class: 'sheet framed', style: { 'max-width': '560px' } }, h('h2', null, 'Office holidays'),
      h('p', { class: 'small muted' }, 'One date per line (yyyy-mm-dd). Deadlines skip these days and the weekends. The demo dates are examples – put in your own.'),
      ta, err, h('button', { class: 'btn primary', onclick: async () => { await act(() => post('/api/admin/holidays', { dates: ta.value.split(/\s+/) }), 'Holidays saved', err); } }, 'Save holidays'));
  }

  function adminLog(data) {
    return h('section', { class: 'sheet tablewrap' }, h('h2', null, 'Activity log'), h('p', { class: 'small muted' }, 'The newest 150 entries. Decisions on documents are in the approval ledger.'),
      h('table', { class: 'register' }, h('thead', null, h('tr', null, h('th', null, 'When'), h('th', null, 'Who'), h('th', null, 'What'), h('th', null, 'On'), h('th', null, 'Detail'))),
        h('tbody', null, data.audit.map((a) => h('tr', null, h('td', { class: 'nowrap small' }, fmtTime(a.at)), h('td', null, a.userId ? name(a.userId) : 'Swikriti'),
          h('td', { class: 'code small' }, a.action), h('td', { class: 'small' }, a.target || ''), h('td', { class: 'small muted' }, a.detail || ''))))));
  }

  async function pageLedger() {
    const v = page('ledger', 'Approval ledger');
    const r = await get('/api/admin/ledger');
    clear(v);
    v.appendChild(h('div', { class: 'pagehead' }, h('div', null, h('h1', null, 'Approval ledger'),
      h('p', null, 'All ' + r.size + ' submits and decisions form one hash chain. Checking it recomputes every link with the secret key.')),
      h('button', { class: 'btn', onclick: route }, 'Check again')));
    v.appendChild(h('div', { class: 'chainok' + (r.ok ? '' : ' bad'), role: 'status' }, r.ok ? '✓ The chain is intact: ' + r.size + ' links checked, nothing was changed.'
      : '✗ The chain is broken at link #' + r.brokenAt + ': ' + r.reason + '. Restore the data folder from a backup.'));
    v.appendChild(h('section', { class: 'sheet framed' }, h('h2', null, 'Newest links'),
      h('div', { class: 'chain' }, r.entries.map((e) => h('div', { class: 'link' }, h('span', { class: 'seq' }, '#' + e.seq),
        h('div', null,
          h('div', null, h('span', { class: 'a' }, ACTION[e.action] || e.action), ' ', e.number ? h('a', { href: '#/doc/' + e.docId, class: 'code small' }, e.number) : null, ' ', delta(nextLetter(e.rev - 1)),
            ' ', h('span', { class: 'muted small' }, (e.userId === 'system' ? 'Swikriti' : name(e.userId)) + (e.onBehalfOf ? ' for ' + name(e.onBehalfOf) : '') + ', ' + fmtTime(e.at))),
          h('div', { class: 'h' }, 'previous ' + e.prev.slice(0, 20) + '…  this ' + e.hash.slice(0, 20) + '…')))))));
  }

  // ================================================================== checking a certificate (also without signing in)

  function verifyForm(start) {
    const code = h('input', { type: 'text', value: start || '', placeholder: '7F3A-91C2-0B4D', 'aria-label': 'Verification code', style: { 'font-family': 'var(--font-code)', 'letter-spacing': '.06em', 'max-width': '260px' } });
    const out = h('div', { style: { 'margin-top': '18px' } });
    const run = async (e) => {
      if (e) e.preventDefault();
      clear(out);
      let r;
      try { r = await get('/api/public/verify?code=' + encodeURIComponent(code.value)); } catch (x) { out.appendChild(h('p', { class: 'err' }, x.message)); return; }
      if (!r.found) { out.appendChild(h('div', { class: 'okline bad' }, '✗ No approved document has the code ' + r.code + '.')); return; }
      const fileCheck = h('div');
      const fin = h('input', { type: 'file' });
      fin.addEventListener('change', async () => {
        clear(fileCheck);
        const f = fin.files[0];
        if (!f || !window.crypto || !crypto.subtle) return;
        const buf = await f.arrayBuffer();
        const hex = Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', buf))).map((b) => b.toString(16).padStart(2, '0')).join('');
        const same = r.attachment && hex === r.attachment.sha;
        fileCheck.appendChild(h('div', { class: 'okline' + (same ? '' : ' bad'), style: { 'font-size': '1.15rem', 'margin-top': '8px' } },
          same ? '✓ This file is exactly the approved attachment.' : '✗ This file is different from the approved attachment.'));
        fileCheck.appendChild(h('div', { class: 'code tiny muted' }, 'your file: ' + hex));
      });
      out.appendChild(h('div', { class: 'sheet framed' },
        h('div', { class: 'okline' + (r.ledgerOk ? '' : ' bad') }, r.ledgerOk ? '✓ Genuine and approved' : '✗ The record was changed – do not trust it'),
        h('h2', { style: { 'margin-top': '10px' } }, r.title),
        h('div', { class: 'schedule' }, h('div', null, h('small', null, 'Document'), h('b', { class: 'code' }, r.number)), h('div', null, h('small', null, 'Type'), h('b', null, r.type)),
          h('div', null, h('small', null, 'Revision'), h('b', null, r.rev)), h('div', null, h('small', null, 'Approved'), h('b', null, fmtDate(r.approvedAt)))),
        h('h3', null, 'Signed by'),
        r.signers.map((s) => h('div', { class: 'row tight', style: { padding: '4px 0' } }, h('span', { class: 'sig', style: { 'font-family': 'var(--font-hand)', color: 'var(--ok)', 'font-size': '1.1rem' } }, s.name),
          h('span', { class: 'small muted' }, (s.title || '') + (s.onBehalfOf ? ', for ' + s.onBehalfOf : '') + ' – ' + s.stage + ', ' + fmtDate(s.at)))),
        h('p', { class: 'small muted', style: { 'margin-top': '12px' } }, 'Fingerprint of the approved text: ', h('span', { class: 'code' }, r.hash)),
        r.attachment ? h('div', null, h('p', { class: 'small' }, 'Have the attached file (' + r.attachment.name + ')? Choose it to check it is the same – it is checked here in your browser, it is not uploaded.'), fin, fileCheck) : null));
    };
    const form = h('form', { onsubmit: run, class: 'row' }, code, h('button', { class: 'btn primary', type: 'submit' }, 'Check'));
    if (start) setTimeout(run, 0);
    return h('div', null, h('p', { class: 'muted' }, 'Every approved document has a 12-character verification code in its title block. Type it to see who approved which revision.'), form, out);
  }

  async function pageVerify(code) {
    const v = page('verify', 'Check a certificate');
    clear(v);
    v.appendChild(h('div', { class: 'pagehead' }, h('div', null, h('h1', null, 'Check a certificate'))));
    v.appendChild(verifyForm(code ? decodeURIComponent(code) : ''));
  }

  function showVerifyPublic() {
    clear($app);
    document.title = 'Check a certificate – Swikriti';
    const parts = (location.hash || '').slice(2).split('/');
    $app.appendChild(h('main', { class: 'verifybox', id: 'view' },
      h('div', { class: 'row between', style: { 'margin-bottom': '20px' } }, h('b', { style: { 'font-family': 'var(--font-head)', 'font-size': '1.4rem', 'letter-spacing': '.08em' } }, 'SWIKRITI'),
        h('a', { href: '#/', onclick: (e) => { e.preventDefault(); location.hash = ''; showLogin(); } }, 'Sign in')),
      h('h1', null, 'Check a certificate'),
      verifyForm(parts[0] === 'verify' && parts[1] ? decodeURIComponent(parts[1]) : '')));
  }

  // ================================================================== start

  async function boot() {
    let me = null;
    try { me = await api('GET', '/api/me'); } catch (e) { /* server down: the login page shows the error on submit */ }
    if (me && me.user) {
      S.user = me.user; setMeta(me.meta);
      shell();
      if (location.pathname === '/verify' && !location.hash) location.hash = '#/verify';
      route();
    } else if (location.pathname === '/verify' || (location.hash || '').startsWith('#/verify')) {
      showVerifyPublic();
    } else {
      showLogin();
    }
    setInterval(async () => {   // keep the counters fresh
      if (!S.user || document.hidden) return;
      const m = await quiet(get('/api/meta'));
      if (m) { setMeta(m); paintCounts(); }
    }, 60000);
  }
  boot();
})();
