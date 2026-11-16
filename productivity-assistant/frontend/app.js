/* =====================================================================
   Dainandini – AI Productivity Assistant (website)
   Plain JavaScript, no framework. Pages are built with h() – never with
   innerHTML – so text typed by people can never become HTML (no XSS).

   How it fits together
     route()   reads the address (#/tasks, #/notes/M-1 ...) and draws that page
     api()     talks to the Java backend (JSON, with the X-Dainandini header)
     All times are shown in India time (Asia/Kolkata), like the server uses.
   ===================================================================== */
(function () {
  'use strict';

  const ZONE = 'Asia/Kolkata';
  const S = { me: null, meta: null, cleanup: [], tasksView: 'list', notesDraft: null };
  const COLORS = ['#9b8fd1', '#e07a5f', '#6fb59a', '#f2c75c', '#6fa8d6', '#d98fb0', '#a3c26b', '#f0a35e'];

  // ------------------------------------------------------------------ building the page safely
  function h(tag, attrs, ...kids) {
    const el = document.createElement(tag);
    if (attrs) for (const k in attrs) {
      const v = attrs[k];
      if (v == null || v === false) continue;
      if (k === 'class') el.className = v;
      else if (k === 'text') el.textContent = v;
      else if (k.startsWith('on') && typeof v === 'function') el.addEventListener(k.slice(2), v);
      else if (k === 'style' && typeof v === 'object') {
        // set through the style object (allowed by the Content-Security-Policy, unlike style="..." text)
        for (const sk in v) if (v[sk] != null) { if (sk.startsWith('--')) el.style.setProperty(sk, v[sk]); else el.style[sk] = v[sk]; }
      }
      else if (k === 'value') el.value = v;
      else if (k === 'checked' || k === 'disabled' || k === 'selected' || k === 'hidden') el[k] = !!v;
      else el.setAttribute(k, v === true ? '' : String(v));
    }
    add(el, kids);
    return el;
  }
  function add(el, kids) {
    for (const k of kids) {
      if (k == null || k === false) continue;
      if (Array.isArray(k)) add(el, k);
      else if (k instanceof Node) el.appendChild(k);
      else el.appendChild(document.createTextNode(String(k)));
    }
  }
  const NS = 'http://www.w3.org/2000/svg';
  function svg(tag, attrs, ...kids) {
    const el = document.createElementNS(NS, tag);
    for (const k in attrs || {}) if (attrs[k] != null) el.setAttribute(k, String(attrs[k]));
    for (const c of kids) if (c) el.appendChild(c);
    return el;
  }
  const $ = (sel, root) => (root || document).querySelector(sel);
  function clear(el) { while (el.firstChild) el.removeChild(el.firstChild); return el; }

  // ------------------------------------------------------------------ talking to the server
  async function api(method, path, body) {
    const opts = { method, headers: { 'Accept': 'application/json' }, credentials: 'same-origin' };
    if (method !== 'GET') {
      opts.headers['Content-Type'] = 'application/json';
      opts.headers['X-Dainandini'] = '1';
      opts.body = JSON.stringify(body || {});
    }
    let res;
    try { res = await fetch(path, opts); } catch (e) { throw { status: 0, message: 'Cannot reach the server. Is it running?' }; }
    let data = null;
    try { data = await res.json(); } catch (e) { /* empty answer */ }
    if (!res.ok) {
      const err = { status: res.status, message: (data && data.error) || ('Error ' + res.status), data: data && data.data };
      if (res.status === 401 && S.me && !path.startsWith('/api/login')) { S.me = null; draw(); }
      throw err;
    }
    return data;
  }

  let toastTimer = null;
  function toast(msg, bad) {
    const t = $('#toast');
    t.textContent = msg;
    t.className = 'toast' + (bad ? ' bad' : '');
    t.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => { t.hidden = true; }, bad ? 6000 : 3500);
  }
  function fail(e) { toast((e && e.message) || 'Something went wrong.', true); }

  // ------------------------------------------------------------------ time helpers (India time)
  const fmtCache = {};
  function fmt(opts) {
    const key = JSON.stringify(opts);
    if (!fmtCache[key]) fmtCache[key] = new Intl.DateTimeFormat('en-GB', Object.assign({ timeZone: ZONE }, opts));
    return fmtCache[key];
  }
  const hm = ms => fmt({ hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).format(ms);
  const dayShort = ms => fmt({ weekday: 'short', day: 'numeric', month: 'short' }).format(ms);
  const dayLong = ms => fmt({ weekday: 'long', day: 'numeric', month: 'long' }).format(ms);
  function dateKey(ms) {
    const p = fmt({ year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts(ms);
    const g = t => p.find(x => x.type === t).value;
    return g('year') + '-' + g('month') + '-' + g('day');
  }
  const minuteOf = ms => { const p = hm(ms).split(':'); return (+p[0]) * 60 + (+p[1]); };
  const todayKey = () => dateKey(Date.now());
  /** "2026-11-20" + minutes → epoch ms (India is always UTC+05:30, no summer time). */
  function atIST(key, min) { return Date.parse(key + 'T00:00:00+05:30') + (min || 0) * 60000; }
  function addDays(key, n) { return dateKey(atIST(key, 12 * 60) + n * 864e5); }
  function when(ms, hasTime) {
    if (!ms) return '';
    const k = dateKey(ms), t = todayKey();
    const d = k === t ? 'today' : k === addDays(t, 1) ? 'tomorrow' : k === addDays(t, -1) ? 'yesterday' : dayShort(ms);
    return hasTime === false ? d : d + ' ' + hm(ms);
  }
  function dur(min) {
    min = Math.max(0, Math.round(min || 0));
    if (min < 60) return min + ' min';
    const hh = Math.floor(min / 60), mm = min % 60;
    return hh + ' h' + (mm ? ' ' + mm + ' min' : '');
  }
  const hhmm = m => String(Math.floor(m / 60)).padStart(2, '0') + ':' + String(m % 60).padStart(2, '0');
  const toMin = s => { const p = String(s).split(':'); return (+p[0]) * 60 + (+p[1] || 0); };

  // ------------------------------------------------------------------ small pieces
  function colorOf(id) { let n = 0; for (const c of String(id || '')) n = (n * 31 + c.charCodeAt(0)) >>> 0; return COLORS[n % COLORS.length]; }
  function avatar(p, sm) {
    if (!p) return h('span', { class: 'av' + (sm ? ' sm' : '') }, '?');
    return h('span', { class: 'av' + (sm ? ' sm' : ''), title: p.name + (p.title ? ' · ' + p.title : ''), style: { '--c': colorOf(p.id) } }, p.initials || '?');
  }
  function tape(text, color, extra) { return h('span', { class: 'tape ' + (color || '') + (extra ? ' ' + extra : '') }, text); }
  function card(title, color, ...kids) { return h('section', { class: 'card' }, title ? tape(title, color) : null, kids); }
  function empty(text) { return h('div', { class: 'empty', text }); }
  function pill(text, color) { return h('span', { class: 'pill ' + (color || '') }, text); }
  function personById(id) { return ((S.meta && S.meta.people) || []).find(p => p.id === id); }
  function pagehead(title, sub, right) {
    return h('div', { class: 'pagehead' }, h('div', {}, h('div', { class: 'date' }, dayLong(Date.now())), h('h1', {}, title), sub ? h('div', { class: 'sub' }, sub) : null), right || null);
  }
  const quadName = { DO: 'Do now', PLAN: 'Schedule', DELEGATE: 'Quick win or hand over', LATER: 'Later or drop' };
  const quadSub = { DO: 'urgent and important', PLAN: 'important, not urgent', DELEGATE: 'urgent, less important', LATER: 'neither – maybe drop it' };

  // ------------------------------------------------------------------ modal dialogs
  function modal(title, body, actions, wide) {
    const root = $('#modal-root');
    clear(root);
    const close = () => clear(root);
    const box = h('div', { class: 'modal' + (wide ? ' wide' : ''), role: 'dialog', 'aria-modal': 'true', 'aria-label': title },
      h('h2', {}, tape(title, 'lav')), body, actions ? h('div', { class: 'actions' }, actions(close)) : null);
    const bg = h('div', { class: 'modal-bg', onclick: e => { if (e.target === bg) close(); } }, box);
    bg.addEventListener('keydown', e => { if (e.key === 'Escape') close(); });
    root.appendChild(bg);
    const first = box.querySelector('input, textarea, select');
    if (first) first.focus();
    return close;
  }
  /** A form in a modal. fields: [{name, label, type, value, options, rows, help}] */
  function formModal(title, fields, submitText, onSubmit, wide) {
    const errBox = h('div', { class: 'err', role: 'alert' });
    const inputs = {};
    const body = h('form', { class: 'stack', onsubmit: e => { e.preventDefault(); go(); } },
      fields.map(f => {
        if (f.type === 'node') return f.node;
        let input;
        if (f.type === 'select') input = h('select', { name: f.name }, f.options.map(o => h('option', { value: o[0], selected: String(o[0]) === String(f.value) }, o[1])));
        else if (f.type === 'textarea') input = h('textarea', { name: f.name, rows: f.rows || 4 });
        else if (f.type === 'checkbox') { input = h('input', { type: 'checkbox', name: f.name, checked: !!f.value }); inputs[f.name] = input; return h('label', { class: 'check' }, input, f.label); }
        else input = h('input', { type: f.type || 'text', name: f.name, value: f.value == null ? '' : f.value, placeholder: f.placeholder || '', min: f.min, max: f.max, step: f.step });
        if (f.type === 'textarea') input.value = f.value || '';
        inputs[f.name] = input;
        return h('label', {}, f.label, input, f.help ? h('span', { class: 'muted small' }, f.help) : null);
      }), errBox, h('button', { type: 'submit', hidden: true }));
    let closeFn;
    async function go() {
      const v = {};
      for (const k in inputs) v[k] = inputs[k].type === 'checkbox' ? inputs[k].checked : inputs[k].value;
      errBox.textContent = '';
      try { await onSubmit(v); closeFn(); } catch (e) { errBox.textContent = e.message || 'Could not save.'; }
    }
    closeFn = modal(title, body, close => [h('button', { class: 'btn ghost', type: 'button', onclick: close }, 'Cancel'), h('button', { class: 'btn primary', type: 'button', onclick: go }, submitText)], wide);
  }

  // ------------------------------------------------------------------ start up, sign in, layout
  async function boot() {
    try {
      const r = await api('GET', '/api/me');
      S.me = r.user; S.meta = r.meta;
    } catch (e) { S.me = null; }
    draw();
  }

  function draw() {
    const app = clear($('#app'));
    if (!S.me) { app.appendChild(loginPage()); return; }
    app.appendChild(shell());
    route();
  }

  function loginPage() {
    const err = h('div', { class: 'err', role: 'alert' });
    const user = h('input', { type: 'text', name: 'username', autocomplete: 'username', placeholder: 'username' });
    const pass = h('input', { type: 'password', name: 'password', autocomplete: 'current-password', placeholder: 'password' });
    async function go(e) {
      if (e) e.preventDefault();
      err.textContent = '';
      try {
        const r = await api('POST', '/api/login', { username: user.value, password: pass.value });
        S.me = r.user; S.meta = r.meta;
        if (!location.hash) location.hash = '#/';
        draw();
      } catch (ex) { err.textContent = ex.message; }
    }
    const demo = (u, p, who) => h('button', { type: 'button', onclick: () => { user.value = u; pass.value = p; go(); } }, u + ' (' + who + ')');
    return h('div', { class: 'login' },
      h('div', { class: 'cover' },
        h('h1', {}, 'Dainandini'),
        h('div', { class: 'dev' }, 'दैनंदिनी · the diary that plans your day'),
        h('p', {}, 'Write tasks in plain English. Get a day plan around your meetings, a warning before deadlines slip, and honest numbers about your focus.'),
        h('form', { class: 'stack', onsubmit: go },
          h('label', {}, 'Username', user), h('label', {}, 'Password', pass), err,
          h('button', { class: 'btn', type: 'submit' }, 'Open my diary')),
        h('div', { class: 'demo' }, 'Demo office (all made up): ', demo('neha', 'member123', 'designer'), ' · ', demo('ravi', 'member123', 'developer'), ' · ',
          demo('arjun', 'lead123', 'team lead'), ' · ', demo('admin', 'admin123', 'admin'))));
  }

  function nav() {
    const items = [
      ['#/', 'Today', '•', 1, 'var(--coral)'], ['#/tasks', 'Tasks', '×', 2, 'var(--butter)'], ['#/calendar', 'Calendar', '◷', 3, 'var(--sky)'],
      ['#/focus', 'Focus', '◉', 4, 'var(--lav)'], ['#/notes', 'Meeting notes', '✎', 5, 'var(--mint)'], ['#/ask', 'Ask Dainandini', '?', 6, 'var(--coral)'],
      ['#/insights', 'Insights', '↗', 7, 'var(--butter)'],
    ];
    if (S.me.role === 'LEAD' || S.me.role === 'ADMIN') items.push(['#/team', 'Team', '⚑', 8, 'var(--sky)']);
    if (S.me.role === 'ADMIN') items.push(['#/admin', 'Admin', '⚙', 9, 'var(--lav)']);
    items.push(['#/settings', 'Settings', '≡', 10, 'var(--mint)']);
    return h('nav', { class: 'nav', 'aria-label': 'Index' }, items.map(i =>
      h('a', { href: i[0], 'data-route': i[0], style: { '--tab': i[4] } }, h('span', { class: 'sig' }, i[2]), i[1], h('span', { class: 'page' }, 'p.' + i[3]))));
  }

  function shell() {
    const index = h('aside', { class: 'index', id: 'index' },
      h('a', { class: 'brand', href: '#/' }, h('img', { src: '/static/favicon.svg', alt: '' }), h('span', {}, h('b', {}, 'Dainandini'), h('small', {}, 'AI productivity assistant'))),
      h('h4', {}, 'Index'), nav(),
      h('div', { class: 'me' },
        h('div', { class: 'who' }, avatar(S.me), h('div', {}, h('b', {}, S.me.name), h('small', {}, S.me.title || S.me.role))),
        h('div', { class: 'row' },
          h('button', { class: 'btn sm', onclick: toggleTheme, title: 'Switch light / dark' }, isDark() ? '☀ Dot-grid' : '☾ Midnight'),
          h('button', { class: 'btn sm ghost', onclick: logout }, 'Sign out'))));
    const bar = h('div', { class: 'mobilebar' },
      h('button', { class: 'btn sm', onclick: () => index.classList.toggle('open'), 'aria-label': 'Menu' }, '☰ Index'),
      h('b', { class: 'hand', style: { fontSize: '24px' } }, 'Dainandini'), avatar(S.me, true));
    return h('div', { class: 'shell' }, index, h('main', {}, bar, h('div', { class: 'journal', id: 'view', tabindex: '-1' })));
  }

  function isDark() {
    const t = document.documentElement.getAttribute('data-theme');
    if (t) return t === 'dark';
    return window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches;
  }
  function toggleTheme() {
    const next = isDark() ? 'light' : 'dark';
    document.documentElement.setAttribute('data-theme', next);
    try { localStorage.setItem('dainandini-theme', next); } catch (e) { /* storage blocked */ }
    draw();
  }
  async function logout() { try { await api('POST', '/api/logout'); } catch (e) { /* ignore */ } S.me = null; location.hash = ''; draw(); }

  // ------------------------------------------------------------------ routing
  function parseHash() {
    const raw = location.hash.replace(/^#/, '') || '/';
    const [path, qs] = raw.split('?');
    const parts = path.split('/').filter(Boolean);
    const q = {};
    if (qs) for (const kv of qs.split('&')) { const [k, v] = kv.split('='); q[decodeURIComponent(k)] = decodeURIComponent(v || ''); }
    return { parts, q };
  }
  function go(hash) { if (location.hash === hash) route(); else location.hash = hash; }

  async function route() {
    if (!S.me) return;
    for (const f of S.cleanup) try { f(); } catch (e) { /* ignore */ }
    S.cleanup = [];
    clear($('#modal-root'));
    const idx = $('#index'); if (idx) idx.classList.remove('open');
    const { parts, q } = parseHash();
    const p0 = parts[0] || '';
    for (const a of document.querySelectorAll('.nav a')) {
      const r = a.getAttribute('data-route');
      a.classList.toggle('on', r === '#/' + p0 || (r === '#/' && p0 === ''));
    }
    const view = $('#view');
    if (!view) return;
    const pages = { '': pageToday, tasks: () => pageTasks(q), calendar: () => pageCalendar(q), focus: pageFocus, notes: () => pageNotes(parts[1]),
      ask: pageAsk, insights: () => pageInsights(q), team: pageTeam, settings: pageSettings, admin: pageAdmin, task: () => { pageTasks({}); openTask(parts[1]); } };
    const fn = pages[p0] || pageToday;
    try { await fn(); } catch (e) { clear(view).appendChild(empty(e.message || 'Could not load this page.')); }
  }
  function paint(...kids) { const v = clear($('#view')); add(v, kids); return v; }
  function refresh() { route(); }

  // ------------------------------------------------------------------ quick capture (used on Today and Tasks)
  function captureBox(onAdded) {
    const chips = h('div', { class: 'chips', 'aria-live': 'polite' });
    const input = h('input', { class: 'q', type: 'text', maxlength: 300, 'aria-label': 'Write a task',
      placeholder: 'Write a task… e.g. "Budget report @meera by Fri 5pm ~45m #finance !!"' });
    let timer = null, seq = 0;
    input.addEventListener('input', () => {
      clearTimeout(timer);
      const text = input.value.trim();
      if (!text) { clear(chips); return; }
      timer = setTimeout(async () => {
        const my = ++seq;
        try {
          const r = await api('POST', '/api/capture/preview', { text });
          if (my !== seq) return;
          clear(chips);
          chips.appendChild(h('span', { class: 'chip', style: { background: 'var(--page)' } }, '“' + r.title + '”'));
          for (const c of r.chips) chips.appendChild(h('span', { class: 'chip ' + c.kind }, c.label));
          if (r.similar && r.similar.length) chips.appendChild(h('span', { class: 'muted tiny', title: r.similar.join('\n') }, 'guess from: ' + r.similar[0]));
        } catch (e) { /* preview is only a help */ }
      }, 220);
    });
    async function save() {
      const text = input.value.trim();
      if (!text) return;
      try {
        const t = await api('POST', '/api/tasks', { text });
        input.value = ''; clear(chips);
        toast('Added “' + t.title + '”' + (t.ownerId !== S.me.id ? ' for ' + t.ownerName : '') + (t.due ? ' – due ' + when(t.due, t.dueHasTime) : ''));
        if (onAdded) onAdded(t);
      } catch (e) { fail(e); }
    }
    input.addEventListener('keydown', e => { if (e.key === 'Enter') save(); });
    return h('div', { class: 'capture' },
      h('div', { class: 'line' }, input, h('button', { class: 'btn coral', onclick: save }, '+ Add')),
      chips,
      h('div', { class: 'hint' }, 'Understands: tomorrow 4pm · next Fri · 15 Dec · in 3 days · ~45m · #tag · !! or "urgent" · every Monday · @name (team leads hand the task over)'));
  }

  // ------------------------------------------------------------------ task helpers
  function sigOf(t) {
    if (t.status === 'DONE') return '×';
    if (t.status === 'DROPPED') return '–';
    if (t.doDate && t.doDate > todayKey()) return '>';
    return '•';
  }
  function scoreBadge(t) {
    if (t.score == null) return null;
    return h('span', { class: 'score' + (t.score >= 60 ? ' hot' : t.score >= 40 ? ' warm' : ''), title: 'Priority score ' + t.score + '/100: ' + (t.reasons || []).join(', ') }, t.score);
  }
  function taskRow(t, onChange, opts) {
    opts = opts || {};
    const done = t.status === 'DONE' || t.status === 'DROPPED';
    const meta = [];
    if (t.overdue) meta.push(h('span', { class: 'late' }, 'overdue · was due ' + when(t.due, t.dueHasTime)));
    else if (t.due) meta.push(h('span', {}, 'due ' + when(t.due, t.dueHasTime)));
    if (t.doDate && t.doDate > todayKey()) meta.push(h('span', {}, '> moved to ' + dayShort(atIST(t.doDate, 720))));
    if (!done) meta.push(h('span', { title: 'Estimate ' + t.estimateMin + ' min' + (t.estimateGuessed ? ' (a guess)' : '') + ', corrected ×' + t.factor + ', minus time already spent' }, '~' + dur(t.remainingMin) + ' left'));
    if (done && t.actualMin) meta.push(h('span', {}, 'took ' + dur(t.actualMin)));
    for (const tg of t.tags || []) meta.push(h('span', { class: 'tagx' }, '#' + tg));
    if (t.repeatLabel) meta.push(h('span', {}, '↻ ' + t.repeatLabel));
    if (t.createdBy && t.createdBy !== t.ownerId) meta.push(h('span', {}, 'from ' + t.createdByName));
    if (opts.owner && t.ownerId !== S.me.id) meta.push(h('span', {}, 'for ' + t.ownerName));
    const sig = h('button', { class: 'sig-btn' + (done ? ' done' : ''), title: done ? 'Open again' : 'Mark as done', 'aria-label': done ? 'Open again' : 'Mark as done',
      onclick: async () => { done ? await reopenTask(t) : await finishTask(t); if (onChange) onChange(); } }, sigOf(t));
    return h('li', { class: done ? 'done' : '' }, sig,
      h('div', {},
        h('div', { class: 'ttl', onclick: () => openTask(t.id, onChange) }, t.importance === 'HIGH' ? h('span', { class: 'star', title: 'important' }, '★') : null,
          t.importance === 'HIGH' && !done ? h('span', { class: 'hl' }, t.title) : t.title),
        h('div', { class: 'meta' }, meta)),
      h('div', { class: 'side' }, opts.noScore ? null : scoreBadge(t), !done ? h('span', { class: 'energy ' + t.energy }, t.energy === 'DEEP' ? 'deep' : 'quick') : null,
        !done && !opts.noFocus ? h('button', { class: 'btn sm icon', title: 'Start a 25-minute focus session', onclick: () => startFocus(t.id, 25) }, '▶') : null));
  }

  async function finishTask(t) {
    if (t.actualMin > 0) return doFinish(t, null);
    return new Promise(resolve => {
      formModal('Well done!', [
        { type: 'node', node: h('p', {}, '“' + t.title + '” – how long did it really take? This teaches the estimate coach.') },
        { name: 'minutes', label: 'Minutes spent', type: 'number', value: t.estimateMin || 15, min: 0, max: 6000 }],
        'Mark as done', async v => { await doFinish(t, +v.minutes); resolve(); });
      // if the dialog is closed with Cancel, nothing changes
    });
  }
  async function doFinish(t, minutes) {
    try {
      const r = await api('POST', '/api/tasks/' + t.id + '/done', minutes == null ? {} : { actualMin: minutes });
      toast('× Done: ' + t.title + (r.next ? ' – next one due ' + when(r.next.due, r.next.dueHasTime) : ''));
    } catch (e) { fail(e); }
  }
  async function reopenTask(t) { try { await api('POST', '/api/tasks/' + t.id + '/reopen'); toast('Opened again'); } catch (e) { fail(e); } }
  async function startFocus(taskId, minutes) {
    try { await api('POST', '/api/focus/start', { taskId, minutes }); go('#/focus'); } catch (e) { fail(e); }
  }

  async function openTask(id, onChange) {
    let t;
    try { t = await api('GET', '/api/tasks/' + id); } catch (e) { fail(e); return; }
    const after = () => { if (onChange) onChange(); else refresh(); };
    const open = t.status === 'TODO' || t.status === 'DOING';
    const parts = t.parts || {};
    const body = h('div', { class: 'stack' },
      h('h3', { style: { fontSize: '22px' } }, t.importance === 'HIGH' ? h('span', { class: 'star' }, '★ ') : null, t.title),
      open && t.score != null ? h('div', {},
        h('div', { class: 'spread' }, h('b', {}, 'Priority score ' + t.score + ' / 100'), pill(quadName[t.quadrant], t.quadrant === 'DO' ? 'red' : t.quadrant === 'PLAN' ? 'lav' : t.quadrant === 'DELEGATE' ? 'yellow' : '')),
        h('div', { class: 'breakdown' },
          h('div', {}, h('b', {}, parts.importance), h('span', {}, 'importance')), h('div', {}, h('b', {}, parts.urgency), h('span', {}, 'urgency')),
          h('div', {}, h('b', {}, parts.age), h('span', {}, 'waiting')), h('div', {}, h('b', {}, parts.today), h('span', {}, 'planned today'))),
        h('ul', { class: 'why-list' }, (t.reasons || []).map(r => h('li', {}, r)))) : pill(t.status.toLowerCase(), t.status === 'DONE' ? 'green' : ''),
      h('dl', { class: 'kv' },
        h('dt', {}, 'Owner'), h('dd', {}, t.ownerName + (t.createdBy !== t.ownerId ? ' (from ' + t.createdByName + ')' : '')),
        h('dt', {}, 'Due'), h('dd', {}, t.due ? when(t.due, t.dueHasTime) + (t.dueHasTime ? '' : ' (end of the working day)') : '—'),
        h('dt', {}, 'Planned day'), h('dd', {}, t.doDate ? dayShort(atIST(t.doDate, 720)) : 'any day'),
        h('dt', {}, 'Estimate'), h('dd', {}, dur(t.estimateMin) + (t.estimateGuessed ? ' (my guess from similar tasks)' : '') +
          (t.factor && t.factor !== 1 && !t.estimateGuessed ? ' → planned as ' + dur(Math.round(t.estimateMin * t.factor)) + ' (you usually need ×' + t.factor + ')' : '')),
        h('dt', {}, 'Time spent'), h('dd', {}, t.actualMin ? dur(t.actualMin) : '—'),
        h('dt', {}, 'Kind of work'), h('dd', {}, t.energy === 'DEEP' ? 'Deep work – planned in your best focus hours' : 'Quick work – fits into small gaps'),
        t.tags.length ? [h('dt', {}, 'Tags'), h('dd', {}, t.tags.map(x => '#' + x).join(' '))] : null,
        t.repeatLabel ? [h('dt', {}, 'Repeats'), h('dd', {}, t.repeatLabel)] : null,
        t.sourceNote ? [h('dt', {}, 'From meeting'), h('dd', {}, h('a', { href: '#/notes/' + t.sourceNote.id }, t.sourceNote.title))] : null),
      t.notes ? h('p', { class: 'paper-text', style: { fontSize: '19px' } }, t.notes) : null,
      t.sessions && t.sessions.length ? h('div', { class: 'small muted' }, 'Focus sessions: ' + t.sessions.map(s => dayShort(s.start) + ' ' + dur(s.minutes) + (s.interruptions ? ' (' + s.interruptions + '× interrupted)' : '')).join(' · ')) : null);
    const close = modal('Task', body, close => {
      const b = [];
      if (!t.canEdit) return [h('button', { class: 'btn', onclick: close }, 'Close')];
      if (open) {
        b.push(h('button', { class: 'btn ghost', onclick: async () => { if (!confirm('Delete this task for good?')) return; try { await api('POST', '/api/tasks/' + t.id + '/delete'); close(); toast('Deleted'); after(); } catch (e) { fail(e); } } }, 'Delete'));
        b.push(h('button', { class: 'btn ghost', onclick: async () => { try { await api('POST', '/api/tasks/' + t.id + '/drop'); close(); toast('– Dropped. Saying no is a skill too.'); after(); } catch (e) { fail(e); } } }, '– Drop'));
        b.push(h('button', { class: 'btn', onclick: () => { close(); moveTask(t, after); } }, '> Move'));
        if (S.me.role !== 'MEMBER') b.push(h('button', { class: 'btn', onclick: () => { close(); handOver(t, after); } }, 'Hand over'));
        b.push(h('button', { class: 'btn', onclick: () => { close(); editTask(t, after); } }, 'Edit'));
        b.push(h('button', { class: 'btn', onclick: () => { close(); startFocus(t.id, 25); } }, '▶ Focus'));
        b.push(h('button', { class: 'btn primary', onclick: async () => { close(); await finishTask(t); after(); } }, '× Done'));
      } else {
        b.push(h('button', { class: 'btn', onclick: async () => { close(); await reopenTask(t); after(); } }, 'Open again'));
        b.push(h('button', { class: 'btn primary', onclick: close }, 'Close'));
      }
      return b;
    }, true);
    return close;
  }

  function editTask(t, after) {
    const dueKey = t.due ? dateKey(t.due) : '';
    formModal('Edit task', [
      { name: 'title', label: 'Title', value: t.title },
      { name: 'importance', label: 'Importance', type: 'select', value: t.importance, options: [['HIGH', '★ High'], ['MEDIUM', 'Normal'], ['LOW', 'Low']] },
      { name: 'energy', label: 'Kind of work', type: 'select', value: t.energy, options: [['DEEP', 'Deep work (needs a quiet block)'], ['SHALLOW', 'Quick work']] },
      { name: 'dueDate', label: 'Due date', type: 'date', value: dueKey },
      { name: 'dueTime', label: 'Due time (empty = end of the working day)', type: 'time', value: t.due && t.dueHasTime ? hm(t.due) : '' },
      { name: 'estimateMin', label: 'Estimate (minutes)', type: 'number', value: t.estimateMin, min: 0, max: 960 },
      { name: 'tags', label: 'Tags (comma separated)', value: (t.tags || []).join(', ') },
      { name: 'recurrence', label: 'Repeats', type: 'select', value: t.recurrence, options: [['', 'No'], ['DAILY', 'Every day'], ['WEEKDAYS', 'Every weekday'],
        ['WEEKLY:1', 'Every Monday'], ['WEEKLY:2', 'Every Tuesday'], ['WEEKLY:3', 'Every Wednesday'], ['WEEKLY:4', 'Every Thursday'], ['WEEKLY:5', 'Every Friday'], ['MONTHLY', 'Every month']] },
      { name: 'notes', label: 'Notes', type: 'textarea', value: t.notes, rows: 3 }],
    'Save', async v => {
      await api('PUT', '/api/tasks/' + t.id, { title: v.title, importance: v.importance, energy: v.energy, dueDate: v.dueDate, dueTime: v.dueDate ? v.dueTime : '',
        estimateMin: +v.estimateMin, tags: v.tags.split(',').map(x => x.trim()).filter(Boolean), recurrence: v.recurrence, notes: v.notes });
      toast('Saved'); after();
    }, true);
  }

  function moveTask(t, after) {
    const t1 = addDays(todayKey(), 1);
    formModal('Move to another day', [
      { type: 'node', node: h('p', { class: 'small muted' }, 'The planner will not plan it before that day. The deadline stays the same.') },
      { name: 'doDate', label: 'Do it on', type: 'date', value: t.doDate || t1 }],
    'Move', async v => {
      await api('PUT', '/api/tasks/' + t.id, { doDate: v.doDate });
      toast('> Moved to ' + dayShort(atIST(v.doDate, 720)) + (t.due && dateKey(t.due) < v.doDate ? ' – careful, that is after the deadline!' : ''));
      after();
    });
  }

  async function handOver(t, after) {
    let list;
    try { list = await api('GET', '/api/tasks/' + t.id + '/owners'); } catch (e) { fail(e); return; }
    const body = h('div', { class: 'stack' },
      h('p', { class: 'small muted' }, 'Best match first: 60 % similar past work + 40 % free time in the next 5 working days.'),
      h('ul', { class: 'bujo' }, list.map(x => h('li', {}, avatar(x.user, true),
        h('div', {}, h('b', {}, x.user.name), h('div', { class: 'meta' }, x.why.map(w => h('span', {}, w)))),
        h('div', { class: 'side' }, h('span', { class: 'score' }, x.score), h('button', { class: 'btn sm', onclick: async () => {
          try { await api('POST', '/api/tasks/' + t.id + '/assign', { userId: x.user.id }); toast('Handed to ' + x.user.name); close(); after(); } catch (e) { fail(e); }
        } }, 'Give'))))));
    const close = modal('Hand over “' + t.title + '”', body, c => [h('button', { class: 'btn', onclick: c }, 'Cancel')], true);
  }

  // ------------------------------------------------------------------ TODAY
  async function pageToday() {
    const d = await api('GET', '/api/today');
    const now = d.now;
    const c = d.counts;
    const stats = h('div', { class: 'stats' },
      h('div', { class: 'stat' + (c.overdue ? ' warn' : '') }, h('b', {}, c.overdue), h('span', {}, 'overdue')),
      h('div', { class: 'stat' }, h('b', {}, c.dueToday), h('span', {}, 'due today')),
      h('div', { class: 'stat' }, h('b', {}, c.doneToday), h('span', {}, 'done today')),
      h('div', { class: 'stat' }, h('b', {}, dur(c.focusToday)), h('span', {}, 'focus of ' + dur(c.focusGoal) + ' goal')),
      h('div', { class: 'stat' }, h('b', {}, c.meetings), h('span', {}, 'meetings')));
    const right = h('div', { class: 'stack', style: { gap: '28px' } },
      doNowCard(d.doNow, now),
      card('Morning brief', 'butter', h('ul', { class: 'brief' }, d.brief.map(b => h('li', {}, b)))),
      radarCard(d.risks),
      card('Top of the list', 'mint', d.top.length ? h('ul', { class: 'bujo' }, d.top.map(t => taskRow(t, refresh))) : empty('Nothing open – enjoy!'),
        h('div', { class: 'row', style: { marginTop: '8px' } }, h('a', { href: '#/tasks', class: 'btn sm' }, 'All tasks →'))));
    paint(
      h('div', { class: 'hello' }, d.hello),
      pagehead('Daily log', null, h('a', { class: 'btn sm', href: '#/ask' }, '? Ask Dainandini')),
      h('div', { class: 'content' },
        captureBox(refresh),
        stats,
        h('div', { class: 'today-grid' },
          card('Today’s plan', 'lav', h('p', { class: 'small muted', style: { margin: '0 0 6px' } }, 'Made by the planner: meetings and lunch stay put, work due today goes first, deep work lands in your best focus hours.'),
            legend(), timeline(d.plan, now), unplacedList(d.plan)),
          right)));
  }

  function legend() {
    const it = (c, t) => h('span', {}, h('i', { style: { background: c } }), t);
    return h('div', { class: 'legend' }, it('var(--lav)', 'deep work'), it('var(--butter)', 'quick work'), it('var(--sky)', 'meeting'), it('var(--mint)', 'lunch / break'), it('var(--line)', 'free'));
  }

  function timeline(plan, now, compact) {
    if (!plan.workday) return empty(plan.note || 'Day off');
    if (!plan.blocks.length) return empty('Nothing planned.');
    const box = h('div', { class: 'log' });
    let nowDone = !(plan.date === todayKey());
    for (const b of plan.blocks) {
      if (!nowDone && b.start > now) {
        box.appendChild(h('div', { class: 'nowline' }, h('span', {}, hm(now)), h('i')));
        nowDone = true;
      }
      const isNow = b.start <= now && now < b.end;
      const deep = b.type === 'TASK' && b.deep ? ' deep' : '';
      const cls = 'slot ' + b.type + deep + (b.end <= now ? ' past' : '') + (isNow ? ' now' : '');
      const blk = h('div', { class: 'b', onclick: b.type === 'TASK' ? () => openTask(b.taskId, refresh) : null },
        h('span', { class: 'end' }, '→ ' + hm(b.end)),
        h('div', { class: 'title' }, b.type === 'TASK' ? '• ' : b.type === 'MEETING' ? '◷ ' : '', b.title, b.part ? h('span', { class: 'muted small' }, ' (part)') : null),
        !compact && b.reason ? h('div', { class: 'why' }, b.minutes + ' min · ' + b.reason) : null);
      box.appendChild(h('div', { class: cls }, h('div', { class: 't' }, hm(b.start)), blk));
    }
    return box;
  }
  function unplacedList(plan) {
    if (!plan.unplaced || !plan.unplaced.length) return null;
    return h('div', { style: { marginTop: '14px' } }, h('b', { class: 'hand', style: { fontSize: '22px', color: 'var(--red)' } }, 'Did not fit today:'),
      h('ul', { class: 'small', style: { margin: '4px 0 0', paddingLeft: '18px' } }, plan.unplaced.map(u => h('li', {}, u.title + ' – ' + dur(u.minutes) + ' more' + (u.due ? ', due ' + when(u.due) : '')))),
      h('div', { class: 'small muted' }, 'Move something, drop it, or ask your lead to hand it over.'));
  }

  function doNowCard(dn, now) {
    let label = 'Do now', title = dn.title, why = dn.reason, buttons = [];
    if (dn.kind === 'TASK') {
      if (dn.from && dn.from > now) label = 'Next, at ' + hm(dn.from);
      why = (dn.until ? 'until ' + hm(dn.until) + ' · ' : '') + dn.reason;
      buttons = [h('button', { class: 'btn', onclick: () => startFocus(dn.taskId, 25) }, '▶ Focus 25 min'),
        dn.task ? h('button', { class: 'btn', onclick: async () => { await finishTask(dn.task); refresh(); } }, '× Done') : null,
        h('button', { class: 'btn ghost', onclick: () => openTask(dn.taskId, refresh) }, 'Why this?')];
    } else if (dn.kind === 'FOCUS') {
      label = 'In a focus session'; why = 'ends at ' + hm(dn.until) + ' – keep going';
      buttons = [h('a', { class: 'btn', href: '#/focus' }, 'Open the timer')];
    } else if (dn.kind === 'MEETING' || dn.kind === 'BLOCK') { label = 'Right now'; why = 'until ' + hm(dn.until); }
    else if (dn.kind === 'LUNCH') { label = 'Right now'; title = 'Lunch break'; why = 'until ' + hm(dn.until) + ' – step away from the screen'; }
    return h('div', { class: 'sticky' }, h('div', { class: 'label' }, label), h('h2', {}, title), h('div', { class: 'why' }, why), buttons.length ? h('div', { class: 'row' }, buttons) : null);
  }

  function radarCard(risks) {
    return card('Deadline radar', 'coral', risks.length
      ? h('div', {}, h('p', { class: 'small muted', style: { margin: '0 0 6px' } }, 'Earliest-deadline-first check of the next 3 weeks: work due before each deadline vs. your free focus time.'),
        h('ul', { class: 'bujo' }, risks.map(r => h('li', {}, h('span', { class: 'sig-btn', style: { color: 'var(--red)' } }, '!'),
          h('div', {}, h('div', { class: 'ttl', onclick: () => openTask(r.taskId, refresh) }, r.title),
            h('div', { class: 'meta' }, h('span', { class: 'late' }, r.capacityBefore === 0 ? 'overdue' : dur(r.shortfall) + ' short'),
              h('span', {}, 'due ' + when(r.due)), r.capacityBefore ? h('span', {}, dur(r.workBefore) + ' of work, ' + dur(r.capacityBefore) + ' of focus time') : null)), h('span')))))
      : h('p', { class: 'hand', style: { fontSize: '22px', margin: '4px 0' } }, '✓ All deadlines fit in your free time.'));
  }

  // ------------------------------------------------------------------ TASKS
  async function pageTasks(q) {
    const filter = q.filter || 'open', tag = q.tag || '';
    const d = await api('GET', '/api/tasks?filter=' + encodeURIComponent(filter) + (tag ? '&tag=' + encodeURIComponent(tag) : ''));
    const link = (f, t) => '#/tasks?filter=' + f + (t ? '&tag=' + encodeURIComponent(t) : '');
    const seg = h('div', { class: 'seg', role: 'group', 'aria-label': 'Which tasks' }, [['open', 'Open'], ['today', 'Today'], ['done', 'Done'], ['all', 'All']].map(x =>
      h('button', { class: filter === x[0] ? 'on' : '', onclick: () => go(link(x[0], tag)) }, x[1])));
    const viewSeg = h('div', { class: 'seg', role: 'group', 'aria-label': 'View' }, [['list', 'List'], ['matrix', 'Eisenhower matrix']].map(x =>
      h('button', { class: S.tasksView === x[0] ? 'on' : '', onclick: () => { S.tasksView = x[0]; refresh(); } }, x[1])));
    const tags = h('div', { class: 'tags' }, Object.keys(d.tags).map(tg => h('button', { class: 'tagbtn' + (tg === tag ? ' on' : ''), onclick: () => go(link(filter, tg === tag ? '' : tg)) }, '#' + tg + ' ' + d.tags[tg])));
    let body;
    if (S.tasksView === 'matrix' && filter !== 'done') {
      // grid order: DO (urgent + important) | PLAN (important) / DELEGATE (urgent) | LATER
      body = h('div', { class: 'matrix-wrap', style: { position: 'relative', margin: '34px 0 0 34px' } },
        h('div', { class: 'matrix' }, ['DO', 'PLAN', 'DELEGATE', 'LATER'].map(k => h('div', { class: 'q' }, h('h3', {}, quadName[k]), h('div', { class: 'qsub' }, quadSub[k] + ' · ' + d.matrix[k].length),
          d.matrix[k].length ? h('ul', { class: 'bujo' }, d.matrix[k].slice(0, 12).map(t => taskRow(t, refresh, { noFocus: true }))) : empty('—')))),
        h('div', { class: 'axis top' }, h('span', {}, 'urgent'), h('span', {}, 'not urgent')),
        h('div', { class: 'axis left' }, h('span', {}, 'less important'), h('span', {}, 'important')));
    } else {
      body = d.tasks.length ? h('ul', { class: 'bujo' }, d.tasks.map(t => taskRow(t, refresh))) : empty(filter === 'done' ? 'Nothing finished yet.' : 'No tasks here.');
    }
    paint(pagehead('Tasks', 'Sorted by priority score. Click • to finish a task, the title to see why it is where it is.'),
      h('div', { class: 'content' },
        captureBox(refresh),
        h('div', { class: 'toolbar' }, seg, viewSeg, h('span', { class: 'muted small' }, d.tasks.length + ' tasks · your estimates are planned ×' + d.factor)),
        tags,
        h('div', { style: { marginTop: '14px' } }, body),
        h('p', { class: 'small muted', style: { marginTop: '14px' } }, 'Signs: • open   × done   > moved to a later day   – dropped   ★ important')));
  }
  // ------------------------------------------------------------------ CALENDAR
  const CAL_FROM = 8 * 60, CAL_TO = 20 * 60, PX_PER_MIN = 44 / 60;
  async function pageCalendar(q) {
    const sel = q.date || todayKey();
    const [w, plan] = await Promise.all([api('GET', '/api/calendar?date=' + sel), api('GET', '/api/plan?date=' + sel)]);
    const mon = w.monday;
    const days = [0, 1, 2, 3, 4, 5, 6].map(i => addDays(mon, i));
    const height = (CAL_TO - CAL_FROM) * PX_PER_MIN;
    const grid = h('div', { class: 'week' });
    grid.appendChild(h('div', { class: 'head', style: { cursor: 'default' } }, ''));
    for (const k of days) {
      const ms = atIST(k, 720);
      grid.appendChild(h('div', { class: 'head' + (k === todayKey() ? ' today' : '') + (k === sel ? ' sel' : ''), onclick: () => go('#/calendar?date=' + k), title: 'Show the plan for this day' },
        fmt({ weekday: 'short' }).format(ms), h('small', {}, fmt({ day: 'numeric', month: 'short' }).format(ms))));
    }
    const hours = h('div', { class: 'hours', style: { height: height + 'px' } });
    for (let m = CAL_FROM; m < CAL_TO; m += 60) hours.appendChild(h('div', { style: { top: ((m - CAL_FROM) * PX_PER_MIN) + 'px' } }, hhmm(m)));
    grid.appendChild(hours);
    const top = ms => Math.max(0, (minuteOf(ms) - CAL_FROM) * PX_PER_MIN);
    const hgt = (s, e) => Math.max(16, ((Math.min(e, s + (CAL_TO - CAL_FROM) * 60000) - s) / 60000) * PX_PER_MIN - 2);
    for (const k of days) {
      const dow = new Date(atIST(k, 720)).getUTCDay() || 7;
      const col = h('div', { class: 'col' + (S.me.workDays.indexOf(dow) < 0 ? ' off' : ''), style: { height: height + 'px' } });
      if (k === sel && plan.workday) for (const b of plan.blocks) {
        if (b.type !== 'TASK' && b.type !== 'LUNCH') continue;
        const deep = !!b.deep;
        col.appendChild(h('div', { class: 'ev ' + (b.type === 'LUNCH' ? 'lunch' : 'plan' + (deep ? ' deep' : '')), title: b.title + ' (planned)', style: { top: top(b.start) + 'px', height: hgt(b.start, b.end) + 'px' } },
          h('b', {}, b.title), hm(b.start) + '–' + hm(b.end)));
      }
      for (const e of w.events) {
        if (e.kind === 'LEAVE') {
          if (e.start < atIST(addDays(k, 1), 0) && e.end > atIST(k, 0)) col.appendChild(h('div', { class: 'ev LEAVE', style: { top: '0px', height: (height - 2) + 'px' }, onclick: () => showEvent(e) }, h('b', {}, e.title)));
          continue;
        }
        if (dateKey(e.start) !== k) continue;
        col.appendChild(h('div', { class: 'ev ' + e.kind, style: { top: top(e.start) + 'px', height: hgt(e.start, e.end) + 'px' }, onclick: () => showEvent(e), title: e.title },
          h('b', {}, e.title), hm(e.start) + '–' + hm(e.end)));
      }
      if (k === todayKey()) { const m = minuteOf(Date.now()); if (m > CAL_FROM && m < CAL_TO) col.appendChild(h('div', { class: 'nowbar', style: { top: ((m - CAL_FROM) * PX_PER_MIN) + 'px' } })); }
      grid.appendChild(col);
    }
    const nav = h('div', { class: 'toolbar' },
      h('button', { class: 'btn sm', onclick: () => go('#/calendar?date=' + addDays(mon, -7)) }, '‹ Week before'),
      h('button', { class: 'btn sm', onclick: () => go('#/calendar') }, 'Today'),
      h('button', { class: 'btn sm', onclick: () => go('#/calendar?date=' + addDays(mon, 7)) }, 'Next week ›'),
      h('span', { class: 'grow' }),
      h('button', { class: 'btn sm', onclick: () => findTime() }, '✦ Find a time'),
      h('button', { class: 'btn sm coral', onclick: () => newEvent({ date: sel }) }, '+ New event'));
    paint(pagehead('Calendar', 'Week of ' + dayShort(atIST(mon, 720)) + '. Dashed blocks = the planner’s plan for ' + (sel === todayKey() ? 'today' : dayShort(atIST(sel, 720))) + ' (click a day to see its plan).'),
      h('div', { class: 'content' }, nav, grid,
        h('div', { class: 'grid g2', style: { marginTop: '30px' } },
          card('Plan for ' + (sel === todayKey() ? 'today' : dayShort(atIST(sel, 720))), 'lav', timeline(plan, Date.now(), true), unplacedList(plan)),
          card('How the numbers add up', 'sky', plan.workday ? h('dl', { class: 'kv' },
            h('dt', {}, 'Meetings'), h('dd', {}, dur(plan.meetingMin)), h('dt', {}, 'Planned work'), h('dd', {}, dur(plan.focusMin)),
            h('dt', {}, 'Still free'), h('dd', {}, dur(plan.freeMin)), h('dt', {}, 'Did not fit'), h('dd', {}, plan.unplaced.length + ' task(s)')) : empty(plan.note)))));
  }

  function showEvent(e) {
    const body = h('div', { class: 'stack' },
      h('h3', {}, e.title),
      h('div', {}, e.kind === 'LEAVE' ? dayShort(e.start) + ' – ' + dayShort(e.end - 1) : dayShort(e.start) + ', ' + hm(e.start) + '–' + hm(e.end) + ' (' + dur((e.end - e.start) / 60000) + ')'),
      h('div', { class: 'row' }, h('div', { class: 'people' }, e.people.filter(Boolean).map(p => avatar(p, true))), h('span', { class: 'small muted' }, e.people.filter(Boolean).map(p => p.name).join(', '))),
      h('div', { class: 'small muted' }, 'Organised by ' + e.ownerName));
    modal(e.kind === 'MEETING' ? 'Meeting' : e.kind === 'LEAVE' ? 'Leave' : 'Blocked time', body, close => [
      h('button', { class: 'btn ghost', onclick: async () => {
        if (!confirm(e.mine ? 'Cancel this for everyone?' : 'Leave this meeting?')) return;
        try { await api('POST', '/api/events/' + e.id + '/delete'); close(); toast(e.mine ? 'Cancelled' : 'You left the meeting'); refresh(); } catch (x) { fail(x); }
      } }, e.mine ? 'Cancel event' : 'Leave meeting'),
      h('button', { class: 'btn primary', onclick: close }, 'Close')]);
  }

  function peoplePicker(selected) {
    const boxes = {};
    const list = h('div', { class: 'stack', style: { gap: '4px', maxHeight: '180px', overflowY: 'auto', border: '1.5px solid var(--line)', borderRadius: '8px', padding: '8px' } },
      (S.meta.people || []).filter(p => p.id !== S.me.id).map(p => {
        const cb = h('input', { type: 'checkbox', checked: (selected || []).indexOf(p.id) >= 0 });
        boxes[p.id] = cb;
        return h('label', { class: 'check' }, cb, avatar(p, true), p.name, h('span', { class: 'muted small' }, ' · ' + (p.team || p.title)));
      }));
    return { node: h('label', {}, 'People', list), get: () => Object.keys(boxes).filter(id => boxes[id].checked) };
  }

  function newEvent(pre) {
    pre = pre || {};
    const pick = peoplePicker(pre.attendees);
    const err = h('div', { class: 'err' });
    const f = {
      title: h('input', { type: 'text', value: pre.title || '', placeholder: 'e.g. Design review' }),
      kind: h('select', {}, [['MEETING', 'Meeting'], ['BLOCK', 'Blocked time (busy, no invite)'], ['LEAVE', 'Leave (whole days)']].map(o => h('option', { value: o[0] }, o[1]))),
      date: h('input', { type: 'date', value: pre.date || todayKey() }),
      start: h('input', { type: 'time', value: pre.start || '15:00' }),
      minutes: h('input', { type: 'number', value: pre.minutes || 30, min: 5, max: 600 }),
      days: h('input', { type: 'number', value: 1, min: 1, max: 30 }),
    };
    async function save(force) {
      err.textContent = '';
      try {
        await api('POST', '/api/events', { title: f.title.value, kind: f.kind.value, date: f.date.value, start: f.start.value, minutes: +f.minutes.value,
          days: +f.days.value, attendees: f.kind.value === 'MEETING' ? pick.get() : [], force: !!force });
        close(); toast('Added to the calendar'); refresh();
      } catch (e) {
        if (e.status === 409 && e.data && e.data.clashes) {
          clear(err).appendChild(h('div', {}, 'Clashes: ' + e.data.clashes.join('; ') + ' ', h('button', { class: 'btn sm', type: 'button', onclick: () => save(true) }, 'Book anyway')));
        } else err.textContent = e.message;
      }
    }
    const close = modal('New event', h('div', { class: 'stack' }, h('label', {}, 'Title', f.title), h('label', {}, 'Kind', f.kind),
      h('div', { class: 'grid g3', style: { gap: '10px' } }, h('label', {}, 'Date', f.date), h('label', {}, 'Start', f.start), h('label', {}, 'Minutes', f.minutes)),
      h('label', {}, 'Days (leave only)', f.days), pick.node, err),
    c => [h('button', { class: 'btn ghost', onclick: c }, 'Cancel'), h('button', { class: 'btn primary', onclick: () => save(false) }, 'Save')], true);
  }

  function findTime() {
    const pick = peoplePicker([]);
    const mins = h('select', {}, [[30, '30 minutes'], [45, '45 minutes'], [60, '1 hour'], [90, '1 h 30 min']].map(o => h('option', { value: o[0] }, o[1])));
    const out = h('div', {});
    async function search() {
      clear(out).appendChild(empty('Looking…'));
      try {
        const r = await api('POST', '/api/events/find', { attendees: pick.get(), minutes: +mins.value });
        clear(out);
        if (!r.options.length) { out.appendChild(empty('No common free time in the next 5 working days.')); return; }
        out.appendChild(h('p', { class: 'small muted' }, 'Everybody is free. Best first: times outside people’s best focus hours.'));
        out.appendChild(h('ul', { class: 'bujo' }, r.options.map(o => h('li', {}, h('span', { class: 'sig-btn' }, '◷'),
          h('div', {}, h('b', {}, dayShort(o.start) + ', ' + hm(o.start) + '–' + hm(o.end)), h('div', { class: 'meta' }, h('span', {}, o.note))),
          h('div', { class: 'side' }, h('button', { class: 'btn sm', onclick: () => { close(); newEvent({ date: dateKey(o.start), start: hm(o.start), minutes: r.minutes, attendees: pick.get() }); } }, 'Book'))))));
      } catch (e) { clear(out).appendChild(h('div', { class: 'err' }, e.message)); }
    }
    const close = modal('Find a time', h('div', { class: 'stack' }, pick.node, h('label', {}, 'How long', mins), h('button', { class: 'btn', onclick: search }, 'Find free times'), out),
      c => [h('button', { class: 'btn ghost', onclick: c }, 'Close')], true);
  }

  // ------------------------------------------------------------------ FOCUS
  async function pageFocus() {
    const [d, open] = await Promise.all([api('GET', '/api/focus'), api('GET', '/api/tasks?filter=open')]);
    const R = 130, C = 2 * Math.PI * R;
    const ring = svg('circle', { cx: 150, cy: 150, r: R, fill: 'none', stroke: 'var(--coral)', 'stroke-width': 16, 'stroke-linecap': 'round', 'stroke-dasharray': C, 'stroke-dashoffset': C });
    const dialSvg = svg('svg', { viewBox: '0 0 300 300', 'aria-hidden': 'true' },
      svg('circle', { cx: 150, cy: 150, r: R, fill: 'none', stroke: 'var(--rule)', 'stroke-width': 16 }),
      svg('circle', { cx: 150, cy: 150, r: R + 14, fill: 'none', stroke: 'var(--ink)', 'stroke-width': 1.5, 'stroke-dasharray': '2 9' }), ring);
    const big = h('div', { class: 'big' }, '25:00');
    const what = h('div', { class: 'what' });
    const ints = h('div', { class: 'ints' });
    let left;
    if (d.running) {
      const r = d.running;
      const end = r.start + r.plannedMin * 60000;
      what.textContent = r.title;
      ints.textContent = r.interruptions ? r.interruptions + '× interrupted' : 'no interruptions yet';
      let warned = false;
      const tick = () => {
        const rem = end - Date.now();
        const s = Math.max(0, Math.round(rem / 1000));
        big.textContent = String(Math.floor(s / 60)).padStart(2, '0') + ':' + String(s % 60).padStart(2, '0');
        ring.setAttribute('stroke-dashoffset', String(C * Math.max(0, rem) / (r.plannedMin * 60000)));
        if (rem <= 0 && !warned) { warned = true; big.textContent = 'Done!'; toast('Time is up – stop the session and take a short break.'); document.title = '✓ Time is up – Dainandini'; }
      };
      tick();
      const iv = setInterval(tick, 1000);
      S.cleanup.push(() => { clearInterval(iv); document.title = 'Dainandini – AI Productivity Assistant'; });
      left = h('div', {},
        h('div', { class: 'dial' }, dialSvg, h('div', { class: 'center' }, big, what, ints)),
        h('div', { class: 'mins' },
          h('button', { class: 'btn', onclick: async () => { try { await api('POST', '/api/focus/interrupt'); refresh(); } catch (e) { fail(e); } } }, '⚡ Interrupted'),
          h('button', { class: 'btn', onclick: async () => { try { await api('POST', '/api/focus/stop', { done: false }); toast('Session saved'); refresh(); } catch (e) { fail(e); } } }, '■ Stop'),
          h('button', { class: 'btn primary', onclick: async () => { try { await api('POST', '/api/focus/stop', { done: true }); toast('× Session saved and task done'); refresh(); } catch (e) { fail(e); } } }, '× Stop & done')));
    } else {
      big.textContent = '25:00';
      what.textContent = 'Pick one task';
      let minutes = 25;
      const sel = h('select', { 'aria-label': 'Task' }, open.tasks.map(t => h('option', { value: t.id }, (t.energy === 'DEEP' ? '◆ ' : '· ') + t.title)));
      const minBtns = h('div', { class: 'mins' }, [15, 25, 50, 90].map(m => h('button', { class: 'btn sm' + (m === 25 ? ' primary' : ''), onclick: e => {
        minutes = m; big.textContent = m + ':00';
        for (const b of minBtns.children) b.classList.remove('primary');
        e.target.classList.add('primary');
      } }, m + ' min')));
      left = h('div', {},
        h('div', { class: 'dial' }, dialSvg, h('div', { class: 'center' }, big, what, h('div', { class: 'ints' }, 'one task, no switching'))),
        open.tasks.length ? h('div', { class: 'stack' }, sel, minBtns, h('button', { class: 'btn coral', onclick: () => startFocus(sel.value, minutes) }, '▶ Start focusing')) : empty('Add a task first.'));
    }
    const pct = Math.min(100, Math.round(d.todayMin * 100 / Math.max(1, d.goalMin)));
    const recent = d.recent.length ? h('table', {}, h('thead', {}, h('tr', {}, h('th', {}, 'When'), h('th', {}, 'Task'), h('th', {}, 'Minutes'), h('th', {}, 'Interrupted'), h('th', {}, ''))),
      h('tbody', {}, d.recent.slice(0, 12).map(s => h('tr', {}, h('td', { class: 'mono' }, dayShort(s.start) + ' ' + hm(s.start)), h('td', {}, s.title), h('td', {}, s.minutes + ' / ' + s.plannedMin),
        h('td', {}, s.interruptions || '—'), h('td', {}, s.finished ? pill('finished', 'green') : pill('stopped early')))))) : empty('No sessions yet.');
    paint(pagehead('Focus', 'One task, a timer, no switching. Every session teaches Dainandini when you focus best and how long your work really takes.'),
      h('div', { class: 'content focus-wrap' }, card(d.running ? 'In focus' : 'Start a session', 'lav', left),
        h('div', { class: 'stack', style: { gap: '28px' } },
          card('Today', 'butter', h('div', { class: 'part' }, h('span', {}, dur(d.todayMin)), h('div', { class: 'track' }, h('i', { style: { width: pct + '%' } })), h('span', { class: 'mono' }, pct + '%')),
            h('p', { class: 'small muted', style: { marginBottom: 0 } }, 'Goal: ' + dur(d.goalMin) + ' of focused work a day (change it in Settings).')),
          card('When you focus best', 'coral', peakChart(d.peak, d.bestWindow),
            h('p', { class: 'small', style: { marginBottom: 0 } }, d.learned ? 'Learned from your sessions: ' : 'Normal office curve until you have 3 hours of sessions: ',
              h('b', {}, hhmm(d.bestWindow[0]) + '–' + hhmm(d.bestWindow[1])), '. The planner puts deep work here.')),
          card('Recent sessions', 'mint', recent))));
  }

  function peakChart(hours, best) {
    const line = h('div', { class: 'peakline' }, hours.map((v, i) => h('i', { class: i * 60 >= best[0] - 59 && i * 60 < best[1] ? 'best' : '', title: hhmm(i * 60) + ' – quality ' + v, style: { height: Math.max(3, v * 100) + '%' } })));
    const labels = h('div', { class: 'peakhours' }, hours.map((v, i) => h('span', {}, i % 3 === 0 ? String(i) : '')));
    return h('div', {}, line, labels);
  }

  // ------------------------------------------------------------------ MEETING NOTES
  async function pageNotes(id) {
    const list = await api('GET', '/api/notes');
    const side = h('div', { class: 'stack' },
      h('button', { class: 'btn coral', onclick: () => go('#/notes/new') }, '+ New meeting notes'),
      list.length ? h('ul', { class: 'note-list' }, list.map(n => h('li', { class: n.id === id ? 'on' : '', onclick: () => go('#/notes/' + n.id) },
        h('b', {}, n.title), h('span', { class: 'small muted' }, dayShort(n.at) + ' · ' + n.ownerName),
        h('div', { class: 'small' }, n.actions + ' action' + (n.actions === 1 ? '' : 's') + (n.notTasks ? ' · ' + n.notTasks + ' not yet tasks' : ' · all tasks') + ' · ' + n.decisions + ' decision' + (n.decisions === 1 ? '' : 's'))))) : empty('No notes yet.'));
    let main;
    if (id === 'new' || (!id && !list.length)) main = noteEditor();
    else if (id) main = await noteView(id);
    else main = card('How it works', 'mint', h('ol', {}, h('li', {}, 'Paste your rough notes from any meeting.'), h('li', {}, 'Dainandini finds the summary, decisions, open questions and action items – who does what by when.'),
      h('li', {}, 'One click turns an action item into a task in that person’s list.')), h('p', { class: 'small muted' }, 'It works sentence by sentence with simple language rules – no data leaves your computer.'));
    paint(pagehead('Meeting notes', 'Rough notes in, action items out.'),
      h('div', { class: 'content', style: { display: 'grid', gridTemplateColumns: 'minmax(220px, 280px) minmax(0, 1fr)', gap: '26px', alignItems: 'start' } }, side, main));
  }

  function foundView(r, actionsNode) {
    const sec = (title, items, cls) => h('div', {}, h('h4', {}, title), items.length ? h('ul', { class: cls || '' }, items.map(x => h('li', {}, x))) : h('div', { class: 'muted small' }, 'none found'));
    return h('div', { class: 'found' }, sec('Summary', r.summary), sec('Decisions', r.decisions), sec('Open questions', r.questions),
      h('h4', {}, 'Action items'), actionsNode);
  }

  function actionLine(a, extra) {
    const p = a.ownerId ? personById(a.ownerId) : null;
    return h('div', { class: 'act' }, h('span', { class: 'arrow' }, '→'),
      h('div', {}, h('b', {}, a.text), h('div', { class: 'small muted' }, (a.ownerName ? a.ownerName + (a.ownerId ? '' : ' (not a user here)') : 'nobody named') + (a.due ? ' · by ' + when(a.due) : ''))),
      extra || (p ? avatar(p, true) : h('span')));
  }

  function noteEditor() {
    const title = h('input', { type: 'text', placeholder: 'Meeting name, e.g. Sprint planning', value: (S.notesDraft && S.notesDraft.title) || '' });
    const text = h('textarea', { class: 'paper-text', rows: 12, placeholder: 'Paste or type the notes…\n\nRavi will send the API docs by Friday.\nWe agreed to drop the old login page.\nCan Meera check the colours?' });
    text.value = (S.notesDraft && S.notesDraft.text) || '';
    const preview = h('div', {});
    let timer = null;
    const analyze = () => {
      S.notesDraft = { title: title.value, text: text.value };
      clearTimeout(timer);
      if (!text.value.trim()) { clear(preview).appendChild(empty('What it finds shows up here as you type.')); return; }
      timer = setTimeout(async () => {
        try {
          const r = await api('POST', '/api/notes/analyze', { text: text.value });
          clear(preview).appendChild(foundView(r, r.actions.length ? h('div', {}, r.actions.map(a => actionLine(a))) : h('div', { class: 'muted small' }, 'none found')));
        } catch (e) { /* only a preview */ }
      }, 450);
    };
    text.addEventListener('input', analyze); title.addEventListener('input', () => { S.notesDraft = { title: title.value, text: text.value }; });
    analyze();
    return h('div', { class: 'grid g2' },
      card('Notes', 'butter', h('div', { class: 'stack' }, title, text,
        h('button', { class: 'btn primary', onclick: async () => {
          try { const n = await api('POST', '/api/notes', { title: title.value, text: text.value }); S.notesDraft = null; toast('Saved'); go('#/notes/' + n.id); } catch (e) { fail(e); }
        } }, 'Save notes'))),
      card('What Dainandini found', 'mint', preview));
  }

  async function noteView(id) {
    const n = await api('GET', '/api/notes/' + id);
    const acts = h('div', {}, n.actions.length ? n.actions.map((a, i) => {
      let extra;
      if (a.taskId) extra = pill(a.taskStatus === 'DONE' ? '× done' : '• task', a.taskStatus === 'DONE' ? 'green' : 'blue');
      else if (n.mine || a.ownerId === S.me.id) {
        const who = h('select', { 'aria-label': 'Who does it' }, [h('option', { value: '' }, a.ownerId ? 'for ' + a.ownerName : 'for me')].concat(
          (S.meta.people || []).filter(p => p.id !== a.ownerId).map(p => h('option', { value: p.id }, 'for ' + p.name))));
        if (!n.mine) who.disabled = true;
        extra = h('div', { class: 'row', style: { flexWrap: 'nowrap' } }, n.mine ? who : null, h('button', { class: 'btn sm', onclick: async () => {
          try { await api('POST', '/api/notes/' + id + '/task', { index: i, ownerId: who.value || null }); toast('Task made'); refresh(); } catch (e) { fail(e); }
        } }, '+ Task'));
      }
      return actionLine(a, extra);
    }) : h('div', { class: 'muted small' }, 'none found'));
    return h('div', { class: 'stack', style: { gap: '28px' } },
      card(n.title, 'mint', h('div', { class: 'spread' }, h('span', { class: 'muted small' }, dayLong(n.at) + ', ' + hm(n.at) + ' · written by ' + n.ownerName),
        n.mine ? h('button', { class: 'btn sm ghost', onclick: async () => { if (!confirm('Delete these notes?')) return; try { await api('POST', '/api/notes/' + id + '/delete'); go('#/notes'); } catch (e) { fail(e); } } }, 'Delete') : null),
        foundView(n, acts)),
      card('The notes', 'butter', h('div', { class: 'paper-text' }, n.text)));
  }

  // ------------------------------------------------------------------ ASK
  async function pageAsk() {
    const d = await api('GET', '/api/assistant');
    const box = h('div', { class: 'chat', 'aria-live': 'polite' });
    const draw1 = m => box.appendChild(chatMsg(m));
    if (!d.messages.length) box.appendChild(chatMsg({ fromUser: false, text: 'Hello ' + S.me.name.split(' ')[0] + '! Ask me about your day, your deadlines or your habits – or add a task in plain English.', intent: '', confidence: 0, source: 'offline', card: {} }));
    d.messages.forEach(draw1);
    const input = h('input', { type: 'text', placeholder: 'Ask anything about your day…', maxlength: 500, 'aria-label': 'Question' });
    async function send(text) {
      text = (text || input.value).trim();
      if (!text) return;
      input.value = '';
      try {
        const r = await api('POST', '/api/assistant', { text });
        r.messages.forEach(draw1);
        box.scrollTop = box.scrollHeight;
      } catch (e) { fail(e); }
    }
    input.addEventListener('keydown', e => { if (e.key === 'Enter') send(); });
    paint(pagehead('Ask Dainandini', 'Answers come from your own tasks, calendar and focus history.' + (d.claude ? ' Questions it is not sure about go to Claude.' : ' Works fully offline.'),
      h('button', { class: 'btn sm ghost', onclick: async () => { await api('POST', '/api/assistant/clear'); refresh(); } }, 'Clear chat')),
      h('div', { class: 'content' }, card(null, null, box,
        h('div', { class: 'ask' }, input, h('button', { class: 'btn coral', onclick: () => send() }, 'Ask')),
        h('div', { class: 'suggest' }, d.suggestions.map(s => h('button', { onclick: () => send(s) }, s))))));
    box.scrollTop = box.scrollHeight;
    input.focus();
  }

  const INTENT_NAME = { WHAT_NOW: 'what now', PLAN_DAY: 'plan a day', FREE_TIME: 'free time', OVERDUE: 'deadlines', ADD_TASK: 'add a task', WEEK_SUMMARY: 'weekly review',
    FOCUS: 'focus', MEETINGS: 'meetings', ESTIMATES: 'estimates', MOVE: 'move a task', DONE: 'finish a task', HELP: 'help', GREETING: 'greeting', UNKNOWN: 'not sure', CLAUDE: 'asked Claude' };
  function chatMsg(m) {
    if (m.fromUser) return h('div', { class: 'msg me' }, m.text);
    const c = m.card || {};
    let extra = null;
    if (c.type === 'tasks' && c.tasks && c.tasks.length) extra = h('ul', { class: 'bujo' }, c.tasks.map(t => taskRow(t, null, { noFocus: true })));
    else if (c.type === 'plan' && c.plan) extra = timeline(c.plan, Date.now(), true);
    else if (c.type === 'donow' && c.doNow && c.doNow.taskId) extra = h('button', { class: 'btn sm', onclick: () => startFocus(c.doNow.taskId, 25) }, '▶ Start 25 minutes on it');
    else if (c.type === 'peak') extra = peakChart(c.peak, c.bestWindow);
    else if (c.type === 'week') extra = bars(c.labels, c.focus, c.meetings, 0);
    else if (c.guesses) extra = h('div', { class: 'small muted' }, 'My best guesses: ' + c.guesses.map(g => (INTENT_NAME[g.intent] || g.intent) + ' ' + Math.round(g.p * 100) + '%').join(', '));
    return h('div', { class: 'msg bot' }, m.text, extra ? h('div', { class: 'cardbox' }, extra) : null,
      m.intent ? h('div', { class: 'meta' }, h('span', { class: 'src' }, m.source === 'claude' ? 'Claude' : 'offline'), h('span', {}, 'understood: ' + (INTENT_NAME[m.intent] || m.intent) +
        (m.source !== 'claude' && m.confidence ? ' (' + Math.round(m.confidence * 100) + '% sure)' : ''))) : null);
  }

  // ------------------------------------------------------------------ INSIGHTS
  function bars(labels, focus, meetings, goal) {
    const max = Math.max(60, ...focus.map((f, i) => f + (meetings[i] || 0)));
    return h('div', {},
      h('div', { class: 'bars' }, labels.map((l, i) => h('div', { class: 'bar' + (goal && focus[i] >= goal ? ' goal' : ''), title: l + ': focus ' + dur(focus[i]) + ', meetings ' + dur(meetings[i]) },
        h('i', { class: 'f', style: { height: (focus[i] / max * 150) + 'px' } }), h('i', { class: 'm', style: { height: (meetings[i] / max * 150) + 'px' } })))),
      h('div', { class: 'barlabels' }, labels.map((l, i) => h('span', {}, labels.length > 16 ? (i % 2 === 0 ? l.split(' ')[1] : '') : l))));
  }

  async function pageInsights(q) {
    const days = +(q.days || 14);
    const d = await api('GET', '/api/insights?days=' + days);
    const t = d.totals;
    const R = 70, C = 2 * Math.PI * R;
    const ring = h('div', { class: 'ring' }, svg('svg', { viewBox: '0 0 170 170', 'aria-hidden': 'true' },
      svg('circle', { cx: 85, cy: 85, r: R, fill: 'none', stroke: 'var(--rule)', 'stroke-width': 14 }),
      svg('circle', { cx: 85, cy: 85, r: R, fill: 'none', stroke: 'var(--mint)', 'stroke-width': 14, 'stroke-linecap': 'round', 'stroke-dasharray': C, 'stroke-dashoffset': C * (1 - d.balance / 100) })),
      h('div', { class: 'c' }, h('b', {}, d.balance), h('span', {}, 'balance')));
    const parts = h('div', { class: 'stack', style: { gap: '8px' } }, d.parts.map(p => h('div', {},
      h('div', { class: 'part' }, h('b', {}, p.name), h('div', { class: 'track' }, h('i', { style: { width: (p.points / p.max * 100) + '%' } })), h('span', { class: 'mono' }, p.points + '/' + p.max)),
      h('div', { class: 'small muted', style: { marginLeft: '120px' } }, p.text))));
    // habit tracker: one circle per day, filled when the focus goal was met
    const tracker = h('div', { class: 'tracker' }, d.labels.map((l, i) => {
      const k = addDays(todayKey(), -(d.labels.length - 1 - i));
      const dow = new Date(atIST(k, 720)).getUTCDay() || 7;
      const work = S.me.workDays.indexOf(dow) >= 0;
      const f = d.focus[i];
      return h('i', { class: !work ? 'off' : f >= S.me.focusGoalMin ? 'y' : f >= S.me.focusGoalMin / 2 ? 'half' : '', title: l + ': ' + dur(f) });
    }));
    const DOW = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
    let hmax = 1;
    d.heat.forEach(r => r.forEach(v => { hmax = Math.max(hmax, v); }));
    const heat = h('div', { class: 'heat' }, h('span'), Array.from({ length: 14 }, (_, i) => h('span', {}, String(7 + i))),
      d.heat.map((r, ri) => [h('span', {}, DOW[ri])].concat(r.map((v, ci) => h('i', { title: DOW[ri] + ' ' + (7 + ci) + ':00 – ' + v + ' min', style: { opacity: String(0.08 + 0.92 * v / hmax), transform: 'scale(' + (0.55 + 0.45 * v / hmax) + ')' } })))));
    const e = d.estimate;
    const facRow = (name, f, n) => h('div', { class: 'factor' }, h('b', {}, name), h('div', { class: 'scale' }, h('i', { class: 'one' }),
      h('i', { class: 'dot', style: { left: Math.max(0, Math.min(100, (f - 0.5) / 1.5 * 100)) + '%' } })), h('span', { class: 'mono' }, '×' + f + ' ', h('span', { class: 'muted tiny' }, '(' + n + ')')));
    const seg = h('div', { class: 'seg' }, [7, 14, 28].map(n => h('button', { class: n === days ? 'on' : '', onclick: () => go('#/insights?days=' + n) }, n + ' days')));
    paint(pagehead('Insights', 'Honest numbers from your own tasks, calendar and focus sessions – with advice you can act on.', seg),
      h('div', { class: 'content stack', style: { gap: '30px' } },
        h('div', { class: 'grid g2' },
          card('Balance score', 'mint', h('div', { class: 'row', style: { alignItems: 'center', gap: '22px', flexWrap: 'wrap' } }, ring, h('div', { class: 'grow' }, parts))),
          card('Focus goal tracker', 'lav', tracker,
            h('p', { class: 'small' }, '● goal met · ◐ half · ○ less · dotted = day off'),
            h('dl', { class: 'kv' },
              h('dt', {}, 'Goal met'), h('dd', {}, t.goalDays + ' of ' + t.workdays + ' working days (best streak ' + t.bestStreak + ')'),
              h('dt', {}, 'Focus sessions'), h('dd', {}, t.sessions + ' (' + t.finishedSessions + ' ran to the end)'),
              h('dt', {}, 'Interruptions'), h('dd', {}, t.interruptions + (t.sessions ? ' – ' + (t.interruptions / t.sessions).toFixed(1) + ' per session' : '')),
              h('dt', {}, 'Tasks finished'), h('dd', {}, t.done + ' · median ' + t.cycleDays + ' days from idea to done'),
              h('dt', {}, 'Deadlines met'), h('dd', {}, t.withDue ? t.onTimeRate + '% of ' + t.withDue : '—')))),
        card('Focus and meetings, day by day', 'butter',
          h('div', { class: 'legend' }, h('span', {}, h('i', { style: { background: 'var(--lav)' } }), 'focus ' + dur(t.focusMin)), h('span', {}, h('i', { style: { background: 'var(--sky)' } }), 'meetings ' + dur(t.meetingMin)), h('span', {}, '★ focus goal met')),
          bars(d.labels, d.focus, d.meetings, S.me.focusGoalMin)),
        h('div', { class: 'grid g2' },
          card('When you focus', 'coral', heat, h('hr', { class: 'dash' }), peakChart(d.peak, d.bestWindow),
            h('p', { class: 'small', style: { marginBottom: 0 } }, d.peakLearned ? 'Best focus time (learned): ' : 'Not enough sessions yet – normal office curve: ', h('b', {}, hhmm(d.bestWindow[0]) + '–' + hhmm(d.bestWindow[1])))),
          card('How long work really takes', 'sky',
            h('p', { class: 'small muted', style: { marginTop: 0 } }, 'Real time ÷ estimate, from finished tasks. The line is ×1 (spot on). Right of it = takes longer than you think.'),
            e.samples ? h('div', {}, facRow('All tasks', e.factor, e.samples), e.tags.map(x => facRow('#' + x.tag, x.factor, x.samples))) : empty('Finish a few tasks with estimates first.'),
            h('p', { class: 'small', style: { marginBottom: 0 } }, 'The planner multiplies your estimates by these numbers (pulled a little towards ×1 while there are few tasks).'))),
        card('What to try next', 'lav', h('ul', { class: 'tips' }, d.tips.map(x => h('li', {}, x))))));
  }

  // ------------------------------------------------------------------ TEAM
  async function pageTeam() {
    const d = await api('GET', '/api/team');
    const rows = d.members.map(m => {
      const w = Math.min(150, m.load) / 150 * 100;
      return h('div', { class: 'member' },
        h('div', { class: 'row', style: { flexWrap: 'nowrap' } }, avatar(m.user), h('div', {}, h('b', {}, m.user.name), h('div', { class: 'small muted' }, m.user.title))),
        h('div', {},
          h('div', { class: 'loadbar ' + m.state, title: 'Work due in the next week ÷ free focus time in the next ' + d.days + ' working days' },
            h('i', { style: { width: w + '%' } }), h('b', { class: 'mark', style: { left: (100 / 150 * 100) + '%' } })),
          h('div', { class: 'small', style: { marginTop: '3px' } }, h('b', { class: 'mono' }, m.load + '%'), ' · ' + dur(m.workMin) + ' of work / ' + dur(m.capacityMin) + ' free focus time'),
          h('div', { class: 'small muted', style: { marginTop: '4px' } }, m.tasks.map(t => t.title + (t.due ? ' (' + when(t.due, false) + ')' : '')).join(' · ') || 'no open tasks')),
        h('div', { class: 'small' }, h('div', {}, m.open + ' open', m.overdue ? h('span', { style: { color: 'var(--red)', fontWeight: 700 } }, ' · ' + m.overdue + ' overdue') : null),
          h('div', { class: 'muted' }, dur(m.meetingMin) + ' meetings'), h('div', { class: 'muted' }, m.doneWeek + ' done this week')));
    });
    paint(pagehead('Team – ' + d.team, 'Workload for the next ' + d.days + ' working days. The black line is 100 %: everything that is due fits exactly in the free focus time.',
      h('button', { class: 'btn coral', onclick: giveTask }, '+ Hand out a task')),
      h('div', { class: 'content' }, card('Who has room', 'sky', rows),
        h('p', { class: 'small muted', style: { marginTop: '14px' } }, 'You see titles and workload, not focus sessions, chats or notes – those stay private.')));
  }

  function giveTask() {
    const title = h('input', { type: 'text', placeholder: 'e.g. Fix the PDF export bug' });
    const tags = h('input', { type: 'text', placeholder: 'tags, e.g. bug, backend' });
    const due = h('input', { type: 'date' });
    const est = h('input', { type: 'number', value: 60, min: 0, max: 960 });
    const out = h('div', {});
    async function suggest() {
      if (!title.value.trim()) { toast('Type a title first', true); return; }
      const tg = tags.value.split(',').map(x => x.trim()).filter(Boolean);
      try {
        const list = await api('POST', '/api/team/suggest', { title: title.value, tags: tg });
        clear(out).appendChild(h('ul', { class: 'bujo' }, list.map(x => h('li', {}, avatar(x.user, true),
          h('div', {}, h('b', {}, x.user.name), h('div', { class: 'meta' }, x.why.map(w => h('span', {}, w)))),
          h('div', { class: 'side' }, h('span', { class: 'score', title: 'skill ' + x.skill + ' · room ' + x.capacity }, x.score), h('button', { class: 'btn sm', onclick: async () => {
            try {
              await api('POST', '/api/tasks', { title: title.value, tags: tg, ownerId: x.user.id, dueDate: due.value, estimateMin: +est.value });
              toast('Given to ' + x.user.name); close(); refresh();
            } catch (e) { fail(e); }
          } }, 'Give'))))));
      } catch (e) { fail(e); }
    }
    const close = modal('Hand out a task', h('div', { class: 'stack' }, h('label', {}, 'What', title), h('label', {}, 'Tags', tags),
      h('div', { class: 'grid g2', style: { gap: '10px' } }, h('label', {}, 'Due', due), h('label', {}, 'Estimate (min)', est)),
      h('button', { class: 'btn', onclick: suggest }, 'Who should do it?'), out), c => [h('button', { class: 'btn ghost', onclick: c }, 'Close')], true);
  }

  // ------------------------------------------------------------------ SETTINGS
  async function pageSettings() {
    const me = S.me;
    const f = {
      workStart: h('input', { type: 'time', value: hhmm(me.workStart) }), workEnd: h('input', { type: 'time', value: hhmm(me.workEnd) }),
      lunchStart: h('input', { type: 'time', value: hhmm(me.lunchStart) }), lunchMin: h('input', { type: 'number', value: me.lunchMin, min: 0, max: 120 }),
      focusGoalMin: h('input', { type: 'number', value: me.focusGoalMin, min: 30, max: 480, step: 15 }),
    };
    const DAYS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
    const dayBoxes = DAYS.map((d, i) => h('input', { type: 'checkbox', checked: me.workDays.indexOf(i + 1) >= 0 }));
    const cur = h('input', { type: 'password', autocomplete: 'current-password' }), next = h('input', { type: 'password', autocomplete: 'new-password' });
    paint(pagehead('Settings', 'Your working day shapes every plan.'),
      h('div', { class: 'content grid g2' },
        card('My working day', 'mint', h('div', { class: 'stack' },
          h('div', { class: 'grid g2', style: { gap: '10px' } }, h('label', {}, 'Work starts', f.workStart), h('label', {}, 'Work ends', f.workEnd),
            h('label', {}, 'Lunch at', f.lunchStart), h('label', {}, 'Lunch minutes', f.lunchMin)),
          h('label', {}, 'Focus goal (minutes a day)', f.focusGoalMin),
          h('div', {}, h('b', { class: 'small' }, 'Working days'), h('div', { class: 'row' }, DAYS.map((d, i) => h('label', { class: 'check' }, dayBoxes[i], d)))),
          h('button', { class: 'btn primary', onclick: async () => {
            try {
              S.me = await api('PUT', '/api/me', { workStart: f.workStart.value, workEnd: f.workEnd.value, lunchStart: f.lunchStart.value, lunchMin: +f.lunchMin.value,
                focusGoalMin: +f.focusGoalMin.value, workDays: dayBoxes.map((b, i) => b.checked ? i + 1 : 0).filter(Boolean) });
              toast('Saved – plans now use these hours');
            } catch (e) { fail(e); }
          } }, 'Save'))),
        h('div', { class: 'stack', style: { gap: '28px' } },
          card('Password', 'coral', h('div', { class: 'stack' }, h('label', {}, 'Current password', cur), h('label', {}, 'New password (8+ characters, a letter and a number)', next),
            h('button', { class: 'btn', onclick: async () => {
              try { await api('POST', '/api/me/password', { current: cur.value, next: next.value }); toast('Password changed – please sign in again'); S.me = null; draw(); } catch (e) { fail(e); }
            } }, 'Change password'))),
          card('Look', 'butter', h('p', {}, 'Now: ', h('b', {}, isDark() ? 'Midnight journal (dark)' : 'Dot-grid (light)')), h('button', { class: 'btn', onclick: toggleTheme }, 'Switch')),
          card('How Dainandini thinks', 'lav', h('ul', { class: 'tips' },
            h('li', {}, 'Quick capture reads dates, times, estimates, tags and importance from one line (rule-based language parsing).'),
            h('li', {}, 'Priority score = importance + urgency (slack before the deadline) + waiting time.'),
            h('li', {}, 'The day planner is greedy: deadlines today first, then best score; deep work goes to your learned peak hours.'),
            h('li', {}, 'The estimate coach multiplies estimates by your median real-time ratio; new tasks get a k-nearest-neighbour guess.'),
            h('li', {}, 'The chat uses a naive Bayes classifier to understand questions; Claude is optional.'))))));
  }

  // ------------------------------------------------------------------ ADMIN
  async function pageAdmin() {
    const [people, settings, audit] = await Promise.all([api('GET', '/api/admin/people'), api('GET', '/api/admin/settings'), api('GET', '/api/admin/audit')]);
    const roleSel = p => {
      const s = h('select', { 'aria-label': 'Role' }, ['MEMBER', 'LEAD', 'ADMIN'].map(r => h('option', { value: r, selected: p.role === r }, r.toLowerCase())));
      s.addEventListener('change', async () => { try { await api('PUT', '/api/admin/people/' + p.id, { role: s.value }); toast('Role changed'); } catch (e) { fail(e); refresh(); } });
      return s;
    };
    const teamIn = p => {
      const i = h('input', { type: 'text', value: p.team || '' });
      i.addEventListener('change', async () => { try { await api('PUT', '/api/admin/people/' + p.id, { team: i.value }); toast('Team changed'); } catch (e) { fail(e); } });
      return i;
    };
    const claudeSel = h('select', {}, [['OFF', 'Off – fully offline'], ['WHEN_UNSURE', 'Ask Claude when the offline assistant is not sure']].map(o => h('option', { value: o[0], selected: settings.claude === o[0] }, o[1])));
    paint(pagehead('Admin', 'People, settings and the activity log.', h('button', { class: 'btn coral', onclick: addPerson }, '+ Add person')),
      h('div', { class: 'content stack', style: { gap: '30px' } },
        card('People', 'mint', h('table', {}, h('thead', {}, h('tr', {}, h('th', {}, 'Name'), h('th', {}, 'Username'), h('th', {}, 'Role'), h('th', {}, 'Team'), h('th', {}, 'Account'))),
          h('tbody', {}, people.map(p => h('tr', {}, h('td', {}, h('div', { class: 'row', style: { flexWrap: 'nowrap' } }, avatar(p, true), p.name)), h('td', { class: 'mono' }, p.username),
            h('td', {}, roleSel(p)), h('td', {}, teamIn(p)),
            h('td', {}, h('button', { class: 'btn sm', onclick: async () => { try { await api('PUT', '/api/admin/people/' + p.id, { active: !p.active }); refresh(); } catch (e) { fail(e); } } }, p.active ? 'Switch off' : 'Switch on'))))))),
        card('Claude (optional)', 'lav', h('div', { class: 'stack' },
          h('p', { class: 'small', style: { margin: 0 } }, settings.claudeKey ? 'An API key was found (model ' + settings.claudeModel + ').' : 'No ANTHROPIC_API_KEY is set, so Claude cannot be switched on. Everything works offline.'),
          claudeSel, h('button', { class: 'btn', disabled: !settings.claudeKey, onclick: async () => { try { await api('PUT', '/api/admin/settings', { claude: claudeSel.value }); toast('Saved'); } catch (e) { fail(e); } } }, 'Save'))),
        card('Activity log', 'butter', h('table', {}, h('thead', {}, h('tr', {}, h('th', {}, 'When'), h('th', {}, 'Who'), h('th', {}, 'What'), h('th', {}, 'Detail'))),
          h('tbody', {}, audit.slice(0, 120).map(a => h('tr', {}, h('td', { class: 'mono' }, dayShort(a.at) + ' ' + hm(a.at)), h('td', {}, a.who), h('td', { class: 'mono' }, a.action), h('td', {}, [a.target, a.detail].filter(Boolean).join(' – ')))))))));
  }

  function addPerson() {
    formModal('Add a person', [
      { name: 'name', label: 'Full name' }, { name: 'username', label: 'Username (small letters)' },
      { name: 'password', label: 'First password', type: 'password', help: '8+ characters with a letter and a number' },
      { name: 'title', label: 'Job title' }, { name: 'team', label: 'Team' },
      { name: 'role', label: 'Role', type: 'select', value: 'MEMBER', options: [['MEMBER', 'Member'], ['LEAD', 'Team lead'], ['ADMIN', 'Admin']] }],
    'Add', async v => { await api('POST', '/api/admin/people', v); toast('Added ' + v.name); const r = await api('GET', '/api/me'); S.meta = r.meta; refresh(); });
  }

  // ------------------------------------------------------------------ go
  window.addEventListener('hashchange', route);
  boot();
})();
