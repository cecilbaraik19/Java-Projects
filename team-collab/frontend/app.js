/* =====================================================================
   Sangam – Remote Team Collaboration Platform (website)
   Plain JavaScript, no framework. Pages are built with h() – never with
   innerHTML – so text from users can never become HTML (no XSS).

   How it fits together
     route()      reads the address (#/project/P-1 ...) and draws that page
     api()        talks to the Java backend (JSON, with the X-Sangam header)
     liveLoop()   long polling: asks /api/events "anything new?" and waits;
                  when something happens, the page that shows it is redrawn
   ===================================================================== */
(function () {
  'use strict';

  // ------------------------------------------------------------------ state
  const S = {
    me: null, meta: null, seq: -1, live: false, channels: [], unreadNotes: 0,
    view: null,          // the page now on screen: { key, refresh(), onEvent(e) }
    drawer: false, people: {}, pending: null, polling: false,
  };
  const COLORS = ['#234a9c', '#c62f2f', '#2f7d5b', '#b9831f', '#6b4c9a', '#2a7f86', '#8a4b2d', '#3d5a80', '#a23b72', '#4d7c0f'];
  const DAYS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
  const COST_LABEL = ['in hours', 'a bit outside', 'outside hours', 'well outside', 'day off', 'night'];

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
  const $ = (sel, root) => (root || document).querySelector(sel);
  function clear(el) { while (el.firstChild) el.removeChild(el.firstChild); return el; }

  // ------------------------------------------------------------------ talking to the server
  async function api(method, path, body) {
    const opts = { method, headers: { 'Accept': 'application/json' }, credentials: 'same-origin' };
    if (method !== 'GET') {
      opts.headers['Content-Type'] = 'application/json';
      opts.headers['X-Sangam'] = '1';
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

  // ------------------------------------------------------------------ time helpers (always shown in MY time zone)
  const fmtCache = {};
  function fmt(zone, opts) {
    const key = zone + JSON.stringify(opts);
    if (!fmtCache[key]) fmtCache[key] = new Intl.DateTimeFormat('en-GB', Object.assign({ timeZone: zone }, opts));
    return fmtCache[key];
  }
  const myZone = () => (S.me && S.me.zone) || 'UTC';
  function hm(ms, zone) { return fmt(zone || myZone(), { hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).format(ms); }
  function dayShort(ms, zone) { return fmt(zone || myZone(), { weekday: 'short', day: 'numeric', month: 'short' }).format(ms); }
  function dayKey(ms, zone) {
    const p = fmt(zone || myZone(), { year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts(ms);
    const g = t => p.find(x => x.type === t).value;
    return g('year') + '-' + g('month') + '-' + g('day');
  }
  function when(ms) {
    const now = Date.now();
    if (dayKey(ms) === dayKey(now)) return hm(ms);
    if (dayKey(ms) === dayKey(now - 864e5)) return 'yesterday ' + hm(ms);
    return dayShort(ms) + ', ' + hm(ms);
  }
  function dur(ms) {
    const m = Math.max(0, Math.round(ms / 60000));
    if (m < 60) return m + ' min';
    const hh = Math.floor(m / 60), mm = m % 60;
    if (hh >= 48) return Math.round(hh / 24) + ' days';
    return hh + ' h' + (mm >= 5 ? ' ' + mm + ' min' : '');
  }
  function ago(ms) { const d = Date.now() - ms; return d < 60000 ? 'just now' : d > 0 ? dur(d) + ' ago' : 'in ' + dur(-d); }
  function localParts(ms, zone) {
    const p = fmt(zone, { weekday: 'short', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).formatToParts(ms);
    const g = t => (p.find(x => x.type === t) || {}).value;
    return { dow: DAYS.indexOf(g('weekday')) + 1, min: (+g('hour') % 24) * 60 + (+g('minute')) };
  }
  /** Is this person working at this moment? (same rule as the server's WorkHours.working) */
  function isWorking(p, ms) {
    if (!p || !p.zone) return false;
    const l = localParts(ms, p.zone);
    return p.workDays.indexOf(l.dow) >= 0 && l.min >= p.workStart && l.min < p.workEnd;
  }
  function isNight(p, ms) { const l = localParts(ms, p.zone); return l.min >= 22 * 60 || l.min < 7 * 60; }
  const today = () => new Date().toISOString().slice(0, 10);

  // ------------------------------------------------------------------ small pieces
  function colorOf(id) { let n = 0; for (const c of String(id || '')) n = (n * 31 + c.charCodeAt(0)) >>> 0; return COLORS[n % COLORS.length]; }
  function stamp(p, size) {
    if (!p) return h('span', { class: 'stamp' + (size ? ' ' + size : '') }, h('span', { text: '?' }));
    const s = h('span', { class: 'stamp' + (size ? ' ' + size : ''), title: p.name + (p.city ? ' · ' + p.city : ''), style: { '--c': colorOf(p.id) } },
      h('span', { text: p.initials || (p.name || '?').split(/\s+/).map(x => x[0]).slice(0, 2).join('').toUpperCase() }));
    if (!p.presence) return s;
    return h('span', { class: 'stampwrap' }, s, h('i', { class: 'dot ' + p.presence, title: p.presence }));
  }
  function personLine(p, sub) {
    return h('div', { class: 'person' }, stamp(p, 'sm'), h('div', { class: 'who' }, h('b', { text: p ? p.name : '—' }), sub ? h('small', {}, sub) : null));
  }
  function postmark(p, ms) {
    const t = ms || Date.now();
    const cls = isWorking(p, t) ? ' work' : isNight(p, t) ? ' night' : '';
    return h('span', { class: 'postmark' + cls, title: 'Local time for ' + p.name }, (p.city || p.zone) + ' ' + hm(t, p.zone));
  }
  function tag(text, color) { return h('span', { class: 'tag' + (color ? ' ' + color : '') }, text); }
  function card(title, extra, ...kids) {
    return h('section', { class: 'card' }, title ? h('h3', {}, h('span', {}, title), extra || null) : null, kids);
  }
  function empty(text) { return h('div', { class: 'empty', text }); }
  function prioStamp(p) { return h('span', { class: 'prio ' + p, title: 'Priority: ' + p.toLowerCase() }, p === 'URGENT' ? '!!' : p === 'HIGH' ? 'HI' : p === 'LOW' ? 'LO' : 'N'); }
  function personById(id) { return (S.meta && S.meta.people || []).find(p => p.id === id); }

  /** Message text with @mentions, task keys (ORB-12) and links made clickable. */
  function rich(text) {
    const out = [];
    const re = /(@[A-Za-z][A-Za-z0-9._-]{1,30})|\b([A-Z]{2,5}-\d{1,5})\b|(https?:\/\/[^\s<>"]+)/g;
    let last = 0, m;
    while ((m = re.exec(text))) {
      if (m.index > last) out.push(text.slice(last, m.index));
      if (m[1]) {
        const name = m[1].slice(1).replace(/[._-]+$/, '').toLowerCase();
        out.push(h('span', { class: 'at' + (S.me && name === S.me.username ? ' me' : '') }, m[1]));
      } else if (m[2]) out.push(h('a', { class: 'tk', href: '#/task/' + m[2] }, m[2]));
      else out.push(h('a', { href: m[3], target: '_blank', rel: 'noopener noreferrer' }, m[3]));
      last = re.lastIndex;
    }
    if (last < text.length) out.push(text.slice(last));
    return out;
  }

  // ------------------------------------------------------------------ modal dialogs
  function modal(title, body, actions, wide) {
    const root = $('#modal-root');
    clear(root);
    const close = () => clear(root);
    const box = h('div', { class: 'modal' + (wide ? ' wide' : ''), role: 'dialog', 'aria-modal': 'true', 'aria-label': title },
      h('h2', { text: title }), body, actions ? h('div', { class: 'actions' }, actions(close)) : null);
    const bg = h('div', { class: 'modal-bg', onclick: e => { if (e.target === bg) close(); } }, box);
    bg.addEventListener('keydown', e => { if (e.key === 'Escape') close(); });
    root.appendChild(bg);
    const first = box.querySelector('input, textarea, select, button');
    if (first) first.focus();
    return close;
  }
  /** A form in a modal. fields: [{name, label, type, value, options, rows, placeholder}] */
  function formModal(title, fields, submitText, onSubmit) {
    const errBox = h('div', { class: 'err', role: 'alert' });
    const inputs = {};
    const body = h('form', { class: 'stack', onsubmit: e => { e.preventDefault(); go(); } },
      fields.map(f => {
        let input;
        if (f.type === 'select') input = h('select', { name: f.name }, f.options.map(o => h('option', { value: o[0], selected: String(o[0]) === String(f.value) }, o[1])));
        else if (f.type === 'textarea') input = h('textarea', { name: f.name, rows: f.rows || 4, placeholder: f.placeholder || '' }, f.value || '');
        else if (f.type === 'checkbox') { input = h('input', { type: 'checkbox', name: f.name, checked: !!f.value }); return h('label', { class: 'check' }, input, f.label); }
        else if (f.type === 'node') return f.node;
        else input = h('input', { type: f.type || 'text', name: f.name, value: f.value == null ? '' : f.value, placeholder: f.placeholder || '', maxlength: f.max || null });
        if (f.type === 'textarea') input.value = f.value || '';
        inputs[f.name] = input;
        return h('label', {}, f.label, input, f.help ? h('span', { class: 'muted small', style: { fontWeight: 400 } }, f.help) : null);
      }), errBox, h('button', { type: 'submit', hidden: true }));
    let closeFn;
    async function go() {
      const v = {};
      for (const k in inputs) v[k] = inputs[k].type === 'checkbox' ? inputs[k].checked : inputs[k].value;
      errBox.textContent = '';
      try { await onSubmit(v); closeFn(); } catch (e) { errBox.textContent = e.message || 'Could not save.'; }
    }
    closeFn = modal(title, body, close => [h('button', { class: 'btn-ghost btn', type: 'button', onclick: close }, 'Cancel'), h('button', { class: 'btn red', type: 'button', onclick: go }, submitText)]);
  }

  // ------------------------------------------------------------------ routing
  function parseHash() {
    const raw = location.hash.replace(/^#/, '') || '/';
    const [path, qs] = raw.split('?');
    const parts = path.split('/').filter(Boolean);
    const q = {};
    if (qs) for (const kv of qs.split('&')) { const [k, v] = kv.split('='); q[decodeURIComponent(k)] = decodeURIComponent(v || ''); }
    return { parts, q, raw };
  }
  function go(hash) { if (location.hash === hash) route(); else location.hash = hash; }

  async function route() {
    if (!S.me) return;
    const { parts, q } = parseHash();
    const p0 = parts[0] || '';
    closeDrawer();
    clear($('#modal-root'));
    const view = $('#view');
    if (!view) return;
    let fn;
    switch (p0) {
      case '': fn = () => pageDesk(); break;
      case 'projects': fn = () => pageProjects(); break;
      case 'project': fn = () => pageProject(parts[1], parts[2] || 'board'); break;
      case 'task': fn = () => pageTask(parts[1]); break;
      case 'channel': fn = () => pageChannel(parts[1], parts[2], q.m); break;
      case 'people': fn = () => pagePeople(q.date, q.project); break;
      case 'meetings': fn = () => pageMeetings(); break;
      case 'standups': fn = () => pageStandups(q.project); break;
      case 'docs': fn = () => pageDocs(); break;
      case 'doc': fn = () => pageDoc(parts[1], parts[2], q); break;
      case 'search': fn = () => pageSearch(q.q); break;
      case 'settings': fn = () => pageSettings(); break;
      case 'admin': fn = () => pageAdmin(); break;
      default: fn = () => pageDesk();
    }
    S.view = { key: p0 + '/' + (parts[1] || ''), parts, q, onEvent: null, refresh: () => paint(fn, true) };
    markNav();
    await paint(fn, false);
  }

  /** Draw a page. On a refresh, keep the scroll position. */
  async function paint(fn, isRefresh) {
    const view = $('#view');
    const y = window.scrollY;
    const token = {};
    S.pending = token;
    if (!isRefresh) { clear(view).appendChild(h('div', { class: 'muted', style: { padding: '30px 0' } }, 'Fetching the post…')); }
    try {
      const node = await fn();
      if (S.pending !== token) return;   // a newer page was asked for meanwhile
      clear(view).appendChild(node);
      if (isRefresh) window.scrollTo(0, y); else window.scrollTo(0, 0);
    } catch (e) {
      if (S.pending !== token) return;
      clear(view).appendChild(h('div', { class: 'card' }, h('h2', { text: e.status === 404 ? 'Not found' : 'Could not load this page' }),
        h('p', { class: 'muted', text: e.message || '' }), h('a', { href: '#/' }, '← Back to your desk')));
    }
  }

  /** Redraw the page unless the person is typing in it (then just show a hint). */
  let softTimer = null;
  function softRefresh() {
    clearTimeout(softTimer);
    softTimer = setTimeout(() => {
      if (!S.view) return;
      const a = document.activeElement;
      const typing = a && $('#view').contains(a) && (a.tagName === 'TEXTAREA' || (a.tagName === 'INPUT' && a.type !== 'checkbox')) && a.value;
      if (typing || $('#modal-root').firstChild) { S.view.stale = true; return; }
      S.view.refresh();
    }, 350);
  }

  // ------------------------------------------------------------------ the frame: sorting rack + top bar
  function draw() {
    const app = clear($('#app'));
    if (!S.me) { app.appendChild(pageSignIn()); return; }
    app.appendChild(h('div', { class: 'shell' },
      h('nav', { class: 'rack', id: 'rack', 'aria-label': 'Main' }),
      h('div', { class: 'main' },
        h('header', { class: 'topbar', id: 'topbar' }),
        h('main', { id: 'view', tabindex: '-1' }))));
    drawRack();
    drawTopbar();
    route();
  }

  function slot(href, ico, name, extra, cls) {
    return h('a', { class: 'slot' + (cls ? ' ' + cls : ''), href, 'data-nav': href }, h('span', { class: 'ico', 'aria-hidden': 'true' }, ico), h('span', { class: 'name' }, name), extra || null);
  }
  function markNav() {
    const { raw } = parseHash();
    document.querySelectorAll('[data-nav]').forEach(a => {
      const href = a.getAttribute('data-nav').slice(1);
      const on = href === '/' ? raw === '/' : raw === href || raw.startsWith(href + '/') || raw.startsWith(href + '?');
      a.classList.toggle('on', on);
    });
  }

  function drawRack() {
    const rack = $('#rack');
    if (!rack) return;
    clear(rack);
    const me = S.me;
    const chans = S.channels.filter(c => c.kind !== 'DIRECT');
    const dms = S.channels.filter(c => c.kind === 'DIRECT').sort((a, b) => b.lastAt - a.lastAt);
    const count = c => c.unread ? h('span', { class: 'count' + (c.mentions ? ' at' : ''), title: c.unread + ' unread' + (c.mentions ? ', ' + c.mentions + ' for you' : '') }, c.mentions ? '@' + c.mentions : c.unread) : null;
    const projects = (S.meta.projects || []);
    rack.appendChild(h('div', { class: 'brand' }, h('div', { class: 'brand-mark' }, h('span', {}, 'S')), h('div', {}, h('b', {}, 'Sangam'), h('small', {}, 'Lumen Labs · airmail'))));
    add(rack, [
      slot('#/', '✉', 'My desk'),
      slot('#/people', '◷', 'World clock'),
      slot('#/standups', '☀', 'Stand-ups'),
      slot('#/meetings', '☎', 'Calls'),
      slot('#/docs', '❡', 'Documents'),
      h('h4', {}, 'Projects', me.admin ? h('button', { title: 'New project', 'aria-label': 'New project', onclick: newProject }, '+') : null),
      projects.map(p => slot('#/project/' + p.id, h('span', { class: 'dot', style: { background: 'var(--' + p.color + ')' } }), p.name)),
      projects.length ? null : h('div', { class: 'slot muted' }, 'No projects yet'),
      h('h4', {}, 'Channels', me.guest ? null : h('button', { title: 'New channel', 'aria-label': 'New channel', onclick: newChannel }, '+')),
      chans.map(c => slot('#/channel/' + c.id, c.kind === 'PRIVATE' ? '🔒' : c.kind === 'PROJECT' ? '◆' : '#', c.name.replace(/^#/, ''), count(c), c.unread ? 'unread' : '')),
      h('h4', {}, 'Direct messages', h('button', { title: 'New direct message', 'aria-label': 'New direct message', onclick: newDM }, '+')),
      dms.map(c => slot('#/channel/' + c.id, h('span', { class: 'dot ' + (c.other ? c.other.presence : '') }), c.name, count(c), c.unread ? 'unread' : '')),
      me.admin ? h('h4', {}, 'Workspace') : null,
      me.admin ? slot('#/admin', '⚙', 'Admin desk') : null,
    ]);
    const focusing = me.focusUntil && me.focusUntil > Date.now();
    rack.appendChild(h('div', { class: 'foot' },
      h('div', { class: 'me' }, stamp(me), h('div', { class: 'who' }, h('b', {}, me.name), h('small', {}, (me.city || me.zone) + ' · ' + hm(Date.now()) + (focusing ? ' · focus' : '')))),
      h('div', { class: 'row' },
        h('button', { class: 'btn-ghost btn small', onclick: () => setFocus(focusing ? 0 : 60), title: 'Focus time: notifications wait until it ends' }, focusing ? 'End focus (' + hm(me.focusUntil) + ')' : 'Focus 1 h'),
        h('a', { class: 'btn-ghost btn small', href: '#/settings' }, 'Settings')),
      h('div', { class: 'row' },
        h('button', { class: 'btn-ghost btn small', onclick: toggleTheme, title: 'Switch light / dark' }, isDark() ? '☀ Airmail' : '☾ Night mail'),
        h('button', { class: 'btn-ghost btn small', onclick: signOut }, 'Sign out'))));
    markNav();
  }

  function drawTopbar() {
    const bar = $('#topbar');
    if (!bar) return;
    clear(bar);
    // one clock per city of the team, me first, then east → west
    const seen = new Set();
    const people = [S.me].concat((S.meta.people || []).filter(p => p.id !== S.me.id));
    const now = Date.now();
    const cities = [];
    for (const p of people) {
      const k = p.city || p.zone;
      if (seen.has(k)) continue;
      seen.add(k);
      cities.push(p);
    }
    const off = p => { const l = localParts(now, p.zone); const u = localParts(now, 'UTC'); let d = l.min - u.min; if (d > 720) d -= 1440; if (d < -720) d += 1440; return d; };
    const mine = cities.shift();
    cities.sort((a, b) => off(b) - off(a));
    cities.unshift(mine);
    const clocks = h('div', { class: 'clocks', 'aria-label': 'Team clocks' }, cities.map(p =>
      h('span', { class: 'cityclock' + (p.id === S.me.id ? ' me' : '') + (isWorking(p, now) ? ' work' : isNight(p, now) ? ' night' : ''), title: (p.city || p.zone) + (isWorking(p, now) ? ' – working hours' : '') },
        h('span', { class: 'muted' }, p.city || p.zone), h('b', {}, hm(now, p.zone)))));
    const search = h('form', { class: 'search', role: 'search', onsubmit: e => { e.preventDefault(); const v = e.target.q.value.trim(); if (v.length >= 2) go('#/search?q=' + encodeURIComponent(v)); } },
      h('input', { type: 'search', name: 'q', placeholder: 'Search tasks, chat, docs', 'aria-label': 'Search' }));
    const bell = h('button', { class: 'btn-ghost btn icon bell', title: 'Notifications', 'aria-label': 'Notifications', onclick: toggleDrawer },
      '🔔', S.unreadNotes ? h('span', { class: 'badge' }, S.unreadNotes > 99 ? '99+' : S.unreadNotes) : null);
    add(bar, [clocks, h('span', { class: 'live' + (S.live ? ' on' : ''), title: S.live ? 'Live: changes appear by themselves' : 'Reconnecting…' }, h('i'), S.live ? 'live' : 'offline'), search, bell]);
  }

  async function refreshChannels() {
    try { S.channels = await api('GET', '/api/channels'); drawRack(); } catch (e) { /* next time */ }
  }
  async function refreshMeta() {
    try {
      const r = await api('GET', '/api/me');
      if (!r.user) return;
      S.me = r.user;
      S.meta = Object.assign(S.meta || {}, r.meta);
      S.meta.projects = await api('GET', '/api/projects');
    } catch (e) { /* ignore */ }
  }

  // ------------------------------------------------------------------ notifications drawer
  async function toggleDrawer() { if ($('.drawer')) closeDrawer(); else openDrawer(); }
  function closeDrawer() { const d = $('.drawer'); if (d) d.remove(); }
  async function openDrawer() {
    let r;
    try { r = await api('GET', '/api/notes'); } catch (e) { return fail(e); }
    closeDrawer();
    const d = h('aside', { class: 'drawer', 'aria-label': 'Notifications' },
      h('header', {}, h('b', {}, 'Your post'), h('div', { class: 'row' },
        r.unread ? h('button', { class: 'linkbtn', onclick: async () => { await api('POST', '/api/notes/read', {}); S.unreadNotes = 0; drawTopbar(); closeDrawer(); } }, 'Mark all read') : null,
        h('button', { class: 'btn-ghost btn small', onclick: closeDrawer, 'aria-label': 'Close' }, '✕'))),
      r.waiting ? h('div', { class: 'note muted small' }, '🌙 ' + r.waiting + ' more will arrive when your working day starts (quiet hours).') : null,
      r.notes.length ? r.notes.map(n => h('a', { class: 'note' + (n.read ? '' : ' unread') + (n.urgent ? ' urgent' : ''), href: n.link || '#/', onclick: closeDrawer },
        h('span', {}, n.text), h('small', {}, when(n.deliverAt) + (n.held ? ' · sent ' + when(n.at) + ', held for your quiet hours' : '')))) : h('div', { class: 'note muted' }, 'Nothing yet.'));
    document.body.appendChild(d);
  }

  // ------------------------------------------------------------------ live updates (long polling)
  async function liveLoop() {
    if (S.polling) return;
    S.polling = true;
    try { await api('GET', '/api/events?since=-1'); S.live = true; drawTopbar(); } catch (e) { /* the loop below retries */ }
    while (S.me) {
      try {
        const r = await api('GET', '/api/events?since=' + S.seq);
        if (!S.live) { S.live = true; drawTopbar(); }
        const before = S.unreadNotes;
        S.unreadNotes = r.unreadNotes;
        if (r.reset) { S.seq = r.seq; await refreshChannels(); softRefresh(); continue; }
        S.seq = r.seq;
        handleEvents(r.events);
        if (before !== S.unreadNotes) drawTopbar();
      } catch (e) {
        if (S.live) { S.live = false; drawTopbar(); }
        if (!S.me) break;
        await new Promise(res => setTimeout(res, 3000));
      }
    }
    S.polling = false;
  }

  let chanTimer = null;
  function handleEvents(events) {
    if (!events.length) return;
    let chats = false, redraw = false, ticks = false;
    const v = S.view || { parts: [] };
    const page = v.parts[0] || '';
    for (const e of events) {
      if (v.onEvent && v.onEvent(e)) continue;          // the page handled it itself
      switch (e.type) {
        case 'message': case 'message.edit': case 'read': case 'channel': chats = true; if (page === '') redraw = true; break;
        case 'task': case 'project': if (page === '' || (page === 'project' && v.parts[1] === e.projectId) || page === 'task' || page === 'projects') redraw = true; break;
        case 'doc': if (page === 'docs' || (page === 'project' && v.parts[1] === e.projectId)) redraw = true; break;
        case 'handoff': if (page === '' || (page === 'project' && v.parts[1] === e.projectId)) redraw = true; break;
        case 'standup': if (page === 'standups' || page === '') redraw = true; break;
        case 'meeting': if (page === 'meetings' || page === '') redraw = true; break;
        case 'presence': if (page === 'people' || page === '') redraw = true; break;
        case 'tick': ticks = true; break;
        default: break;
      }
    }
    if (chats) { clearTimeout(chanTimer); chanTimer = setTimeout(refreshChannels, 300); }
    if (ticks) { drawTopbar(); drawRack(); }
    if (redraw) softRefresh();
  }
  // clocks move even without server news
  setInterval(() => { if (S.me) drawTopbar(); }, 30000);

  // ------------------------------------------------------------------ actions used in many places
  function isDark() {
    const t = document.documentElement.getAttribute('data-theme');
    return t ? t === 'dark' : window.matchMedia('(prefers-color-scheme: dark)').matches;
  }
  function toggleTheme() {
    const next = isDark() ? 'light' : 'dark';
    document.documentElement.setAttribute('data-theme', next);
    try { localStorage.setItem('sangam-theme', next); } catch (e) { /* fine */ }
    drawRack();
  }
  async function setFocus(minutes) {
    try { S.me = Object.assign(S.me, await api('POST', '/api/me/focus', { minutes })); drawRack(); toast(minutes ? 'Focus time on: notifications wait for ' + minutes + ' minutes.' : 'Focus time ended.'); }
    catch (e) { fail(e); }
  }
  async function signOut() {
    try { await api('POST', '/api/logout', {}); } catch (e) { /* signed out anyway */ }
    S.me = null; S.meta = null; S.seq = -1;
    location.hash = '#/';
    draw();
  }
  function newChannel() {
    const people = (S.meta.people || []).filter(p => p.id !== S.me.id && p.role !== 'GUEST');
    const picks = h('div', { class: 'chips' }, people.map(p => chipToggle(p, false)));
    formModal('New channel', [
      { name: 'name', label: 'Name', placeholder: 'release-3-2', help: 'Small letters, digits and dashes.' },
      { name: 'topic', label: 'Topic', placeholder: 'What is this channel for?' },
      { name: 'private', label: 'Private (only the people I pick)', type: 'checkbox' },
      { type: 'node', node: h('label', {}, 'People (for private channels)', picks) },
    ], 'Create', async v => {
      const ids = [...picks.querySelectorAll('input:checked')].map(i => i.value);
      const c = await api('POST', '/api/channels', { name: v.name, topic: v.topic, private: v.private, memberIds: ids });
      await refreshChannels();
      go('#/channel/' + c.id);
    });
  }
  function chipToggle(p, on) {
    const input = h('input', { type: 'checkbox', value: p.id, checked: on });
    const lab = h('label', { class: 'chip' + (on ? ' on' : '') }, input, stamp(p, 'sm'), p.name.split(' ')[0]);
    input.addEventListener('change', () => lab.classList.toggle('on', input.checked));
    return lab;
  }
  function newDM() {
    const people = (S.meta.people || []).filter(p => p.id !== S.me.id);
    const close = modal('Write to…', h('div', { class: 'list' }, people.map(p => h('button', {
      class: 'btn-ghost btn', style: { justifyContent: 'space-between', width: '100%' },
      onclick: async () => { close(); await openDM(p.id); }
    }, personLine(p, p.title || p.city), postmark(p)))));
  }
  async function openDM(userId) {
    try { const r = await api('POST', '/api/dm/' + userId, {}); await refreshChannels(); go('#/channel/' + r.id); } catch (e) { fail(e); }
  }
  function newProject() {
    const people = (S.meta.people || []).filter(p => p.id !== S.me.id);
    const picks = h('div', { class: 'chips' }, people.map(p => chipToggle(p, false)));
    formModal('New project', [
      { name: 'name', label: 'Name', placeholder: 'Partner portal' },
      { name: 'key', label: 'Code (2–5 capital letters, used in task numbers)', placeholder: 'PRT', max: 5 },
      { name: 'description', label: 'What is it about?', type: 'textarea', rows: 2 },
      { name: 'color', label: 'Stamp colour', type: 'select', value: 'violet', options: S.meta.colors.map(c => [c, c]) },
      { type: 'node', node: h('label', {}, 'Members', picks) },
    ], 'Create project', async v => {
      const ids = [...picks.querySelectorAll('input:checked')].map(i => i.value);
      const p = await api('POST', '/api/projects', Object.assign(v, { memberIds: ids }));
      await refreshMeta(); await refreshChannels();
      go('#/project/' + p.id);
    });
  }

  // ================================================================== PAGES

  // ------------------------------------------------------------------ sign in
  function globe() {
    const ns = 'http://www.w3.org/2000/svg';
    const el = (t, a) => {
      const e = document.createElementNS(ns, t);
      for (const k in a) { const v = String(a[k]); if (v.startsWith('var(')) e.style.setProperty(k, v); else e.setAttribute(k, v); }
      return e;
    };
    const svg = el('svg', { viewBox: '0 0 320 220', class: 'globe', role: 'img', 'aria-label': 'A globe with airmail routes between cities' });
    svg.appendChild(el('circle', { cx: 160, cy: 110, r: 88, fill: 'none', stroke: 'currentColor', 'stroke-width': 2, opacity: .8 }));
    for (const rx of [30, 60]) svg.appendChild(el('ellipse', { cx: 160, cy: 110, rx, ry: 88, fill: 'none', stroke: 'currentColor', 'stroke-width': 1, opacity: .35 }));
    for (const y of [-50, 0, 50]) svg.appendChild(el('ellipse', { cx: 160, cy: 110 + y, rx: Math.sqrt(88 * 88 - y * y), ry: 9, fill: 'none', stroke: 'currentColor', 'stroke-width': 1, opacity: .35 }));
    const route = (d, c) => svg.appendChild(el('path', { d, fill: 'none', stroke: c, 'stroke-width': 2.2, 'stroke-dasharray': '6 5', 'stroke-linecap': 'round' }));
    route('M70 80 C 120 20, 200 20, 250 70', 'var(--red)');
    route('M95 150 C 140 190, 220 180, 262 128', 'var(--blue)');
    route('M118 96 C 150 120, 180 120, 214 98', 'var(--green)');
    for (const [x, y] of [[70, 80], [250, 70], [95, 150], [262, 128], [118, 96], [214, 98]])
      svg.appendChild(el('circle', { cx: x, cy: y, r: 5, fill: 'var(--card)', stroke: 'currentColor', 'stroke-width': 2 }));
    const plane = el('path', { d: 'M0 -6 L14 0 L0 6 L3 0 Z', fill: 'var(--red)', transform: 'translate(198 34) rotate(18)' });
    svg.appendChild(plane);
    return svg;
  }

  function pageSignIn() {
    const err = h('div', { class: 'err', role: 'alert' });
    const user = h('input', { type: 'text', name: 'username', autocomplete: 'username', placeholder: 'username', 'aria-label': 'Username' });
    const pass = h('input', { type: 'password', name: 'password', autocomplete: 'current-password', placeholder: 'password', 'aria-label': 'Password' });
    async function submit(e) {
      if (e) e.preventDefault();
      err.textContent = '';
      try {
        const r = await api('POST', '/api/login', { username: user.value, password: pass.value });
        await start(r.user, r.meta);
      } catch (x) { err.textContent = x.message; }
    }
    const demo = [['asha', 'member123', 'Asha · Ranchi · engineering lead'], ['lena', 'member123', 'Lena · Berlin · designer'],
      ['diego', 'member123', 'Diego · São Paulo · product'], ['kwame', 'member123', 'Kwame · Accra · DevOps'],
      ['admin', 'admin123', 'Priya · workspace admin'], ['client', 'guest1234', 'Hana · San Francisco · client (guest)']];
    return h('div', { class: 'signin' }, h('div', { class: 'pcard' },
      h('div', { class: 'left' },
        h('div', { class: 'kicker', style: { font: '600 12px/1 var(--sans)', letterSpacing: '.16em', textTransform: 'uppercase', color: 'var(--red)' } }, 'Par avion · By airmail'),
        h('h1', {}, 'Sangam'),
        h('p', {}, 'Where a team spread over eleven cities meets. One board, chat that respects sleep, documents that merge, stand-ups without a call, and handoffs that follow the sun.'),
        globe(),
        h('p', { class: 'muted small' }, 'Demo workspace: Lumen Labs, a made-up company. All people are made up.')),
      h('form', { class: 'right', onsubmit: submit },
        h('span', { class: 'postage' }, h('span', { class: 'stamp lg', style: { '--c': 'var(--red)' } }, h('span', {}, 'S'))),
        h('h2', {}, 'Sign in'),
        h('div', { class: 'lines stack' }, h('label', {}, 'To', user), h('label', {}, 'Secret word', pass)),
        err,
        h('button', { class: 'btn red', type: 'submit' }, 'Sign in →'),
        h('div', { class: 'demo' }, h('b', {}, 'Demo logins (click to fill in)'),
          demo.map(d => h('button', { type: 'button', onclick: () => { user.value = d[0]; pass.value = d[1]; submit(); } }, h('span', {}, d[2]), h('span', { class: 'mono muted' }, d[0])))))));
  }

  async function start(user, meta) {
    S.me = user;
    S.meta = meta;
    S.seq = meta.seq;
    S.meta.projects = await api('GET', '/api/projects');
    S.channels = await api('GET', '/api/channels');
    try { S.unreadNotes = (await api('GET', '/api/notes')).unread; } catch (e) { /* fine */ }
    draw();
    liveLoop();
  }

  // ------------------------------------------------------------------ my desk (home)
  function greeting(p) {
    const m = localParts(Date.now(), p.zone).min;
    return m < 5 * 60 ? 'Up late' : m < 12 * 60 ? 'Good morning' : m < 17 * 60 ? 'Good afternoon' : m < 22 * 60 ? 'Good evening' : 'Up late';
  }
  function miniCard(t) {
    return h('a', { class: 'row between', href: '#/task/' + t.id, style: { textDecoration: 'none', color: 'var(--ink)' } },
      h('div', { class: 'row grow', style: { flexWrap: 'nowrap' } }, h('span', { class: 'tag', style: { borderColor: 'var(--' + t.projectColor + ')' } }, t.key),
        h('span', { class: 'grow', style: { overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' } }, t.title)),
      h('div', { class: 'row' }, t.waitingFor.length ? tag('waits for ' + t.waitingFor.join(', '), 'red') : null,
        tag(t.status === 'DOING' ? 'Doing' : t.status === 'REVIEW' ? 'Review' : 'To do', t.status === 'DOING' ? 'blue' : t.status === 'REVIEW' ? 'ochre' : ''),
        t.due ? h('span', { class: 'small ' + (t.overdue ? 'err' : 'muted') }, (t.overdue ? 'overdue ' : 'due ') + t.due.slice(5)) : null));
  }

  function teamRow(p) {
    const now = Date.now();
    const w = p.clock.working;
    const night = p.clock.night;
    const state = w ? 'working · ' + dur(p.clock.endsAt - now) + ' left'
      : night ? 'night · back in ' + dur(p.clock.nextStart - now) : 'off · back in ' + dur(p.clock.nextStart - now);
    return h('div', { class: 'teamrow' + (w ? '' : ' asleep') },
      h('div', { class: 'person' }, stamp(p, 'sm'), h('div', { class: 'who' }, h('b', {}, p.name + (p.id === S.me.id ? ' (you)' : '')),
        h('small', {}, (p.city || '') + ' · ' + state + (p.presence === 'focus' ? ' · focus' : '') + (p.statusText ? ' · “' + p.statusText + '”' : '')))),
      h('div', { class: 'when' }, h('b', {}, hm(now, p.zone)), w ? h('span', { class: 'sun', title: 'working' }) : night ? h('span', { class: 'moon', title: 'night' }) : 'off',
        p.id !== S.me.id ? h('button', { class: 'linkbtn small', style: { marginLeft: '8px' }, onclick: () => openDM(p.id) }, 'write') : null));
  }

  function standupForm(existing, onSaved) {
    const y = h('textarea', { rows: 2, placeholder: 'Yesterday I…' }); y.value = existing ? existing.yesterday : '';
    const t = h('textarea', { rows: 2, placeholder: 'Today I will…' }); t.value = existing ? existing.today : '';
    const b = h('textarea', { rows: 2, placeholder: 'Anything blocking you? Write none, or @name who can help.' }); b.value = existing ? existing.blockers : '';
    const err = h('div', { class: 'err' });
    return h('form', { class: 'stack', onsubmit: async e => {
      e.preventDefault(); err.textContent = '';
      try { await api('POST', '/api/standups', { yesterday: y.value, today: t.value, blockers: b.value }); toast('Stand-up posted. Your team reads it in their own morning.'); onSaved && onSaved(); }
      catch (x) { err.textContent = x.message; }
    } }, h('label', {}, 'Yesterday', y), h('label', {}, 'Today', t), h('label', {}, 'Blockers', b), err,
      h('div', { class: 'row' }, h('button', { class: 'btn', type: 'submit' }, existing ? 'Update stand-up' : 'Post stand-up'), h('span', { class: 'muted small' }, 'One per day, in your own date. No call needed.')));
  }

  async function pageDesk() {
    const d = await api('GET', '/api/home');
    const me = d.me;
    const now = Date.now();
    const working = me.clock.working;
    const head = h('div', { class: 'pagehead' },
      h('div', { class: 'hello' },
        h('div', {}, h('div', { class: 'kicker' }, 'Your desk · ' + dayShort(now)),
          h('h1', {}, greeting(me) + ', ' + me.name.split(' ')[0]),
          h('p', {}, working ? 'You are in your working hours (' + me.hours + ', ' + dur(me.clock.endsAt - now) + ' left). '
            : 'You are outside your working hours – messages to you wait until ' + hm(me.clock.nextStart) + '. ',
          d.workingNow + ' of ' + d.team.length + ' people are working right now. Today someone is at work for ' + d.coverageHours + ' of 24 hours.'))),
      h('div', { class: 'hello' }, h('div', { class: 'big' }, hm(now)), h('div', { class: 'muted small' }, me.city, h('br'), me.clock.offset + ' UTC')));

    const batons = d.handoffs.length ? card('🔁 The baton is yours', h('span', { class: 'muted' }, d.handoffs.length + ' handoff' + (d.handoffs.length > 1 ? 's' : '')),
      d.handoffs.map(x => h('div', { class: 'baton', style: { marginBottom: '10px' } },
        h('div', { class: 'row between' }, personLine(x.from, x.projectName + ' · ' + when(x.at)), postmark(personById(x.from.id) || { name: x.from.name, city: x.from.city, zone: 'UTC', workDays: [], workStart: 0, workEnd: 0 }, x.at)),
        h('pre', {}, x.text),
        h('button', { class: 'btn red small', onclick: async () => { try { await api('POST', '/api/handoffs/' + x.id + '/ack', {}); toast(x.from.name.split(' ')[0] + ' will see you picked it up.'); softRefresh(); } catch (e) { fail(e); } } }, 'Got it')))) : null;

    const su = me.role === 'GUEST' ? null : card('☀ Your stand-up for ' + d.standupDate, h('a', { href: '#/standups', class: 'small' }, 'see everyone →'),
      d.standup && !S.view.editStandup ? h('div', { class: 'su' }, h('div', { class: 'q' }, 'Today'), h('div', { class: 'a' }, d.standup.today),
        d.standup.blockers && d.standup.blockers.toLowerCase() !== 'none' ? h('div', { class: 'blk' }, 'Blocked: ' + d.standup.blockers) : null,
        h('button', { class: 'linkbtn', onclick: () => { S.view.editStandup = true; S.view.refresh(); } }, 'Edit'))
        : standupForm(d.standup, () => { S.view.editStandup = false; S.view.refresh(); }));

    const tasks = card('My tasks', h('span', { class: 'muted' }, d.tasks.length + ' open'),
      d.tasks.length ? h('div', { class: 'list' }, d.tasks.map(miniCard)) : empty('Nothing assigned to you. Pick something from a board.'));
    const reviews = d.reviews.length ? card('Waiting for your review', h('span', { class: 'muted' }, 'four eyes'), h('div', { class: 'list' }, d.reviews.map(t => h('div', {}, miniCard(t), h('div', { class: 'muted small' }, 'by ' + (t.assignee ? t.assignee.name : '—')))))) : null;
    const mentions = card('Mentions of you', h('span', { class: 'muted' }, 'last 7 days'),
      d.mentions.length ? h('div', { class: 'list' }, d.mentions.map(m => h('a', { class: 'mention', href: '#/channel/' + m.channelId + '?m=' + m.message.id, style: { color: 'var(--ink)', textDecoration: 'none' } },
        h('div', { class: 'where' }, stamp(m.message.author, 'sm'), h('b', {}, m.message.author.name), h('span', {}, 'in ' + m.channel + ' · ' + when(m.message.at)), m.answered ? tag('answered', 'green') : tag('waiting for you', 'red')),
        h('div', { class: 'msg', style: { display: 'block', padding: '0' } }, h('div', { class: 'text' }, rich(m.message.text)))))) : empty('Nobody needs you right now. Enjoy the quiet.'));
    const calls = card('Next calls', h('a', { href: '#/meetings', class: 'small' }, 'plan a call →'),
      d.meetings.length ? h('div', { class: 'list' }, d.meetings.map(m => h('div', { class: 'row between' },
        h('div', {}, h('b', {}, m.title), h('div', { class: 'muted small' }, dayShort(m.start) + ' · ' + hm(m.start) + '–' + hm(m.end) + ' your time' + (m.live ? ' · NOW' : ''))),
        h('div', { class: 'costs' }, m.people.filter(x => x.cost > 0).map(x => h('span', { class: 'cost k' + x.cost, title: x.label }, stamp(x.user, 'sm'), h('span', { class: 'mono' }, x.local.slice(4)))))))) : empty('No calls coming up. Async is working!'));
    const team = card('Who is around', h('a', { href: '#/people', class: 'small' }, 'world clock →'), h('div', { class: 'teamlist' }, d.team.map(teamRow)));

    return h('div', {}, head, h('div', { class: 'cols' },
      h('div', { class: 'span7 stack' }, batons, tasks, reviews, mentions),
      h('div', { class: 'span5 stack' }, su, calls, team)));
  }

  // ------------------------------------------------------------------ projects
  async function pageProjects() {
    const list = await api('GET', '/api/projects');
    return h('div', {}, h('div', { class: 'pagehead' }, h('div', {}, h('div', { class: 'kicker' }, 'Projects'), h('h1', {}, 'All projects')),
      S.me.admin ? h('button', { class: 'btn red', onclick: newProject }, '+ New project') : null),
      h('div', { class: 'doclist' }, list.map(p => h('a', { class: 'card envelope docitem', href: '#/project/' + p.id },
        h('div', { class: 'row between' }, h('h2', {}, p.name), tag(p.key)), h('p', { class: 'muted small' }, p.description),
        h('div', { class: 'row' }, ['To do', 'Doing', 'Review', 'Done'].map((l, i) => tag(l + ' ' + p.counts[i], i === 1 ? 'blue' : i === 2 ? 'ochre' : i === 3 ? 'green' : ''))),
        h('div', { class: 'row' }, p.members.map(m => stamp(m, 'sm')), h('span', { class: 'muted small' }, p.workingNow + ' working now'))))));
  }

  function projectTabs(p, tab) {
    const t = (id, label) => h('a', { href: '#/project/' + p.id + (id === 'board' ? '' : '/' + id), class: tab === id ? 'on' : '' }, label);
    return h('nav', { class: 'tabs' }, t('board', 'Board'), t('handoff', 'Handoff'), t('docs', 'Documents'), S.me.guest ? null : t('report', 'Report'),
      h('a', { href: '#/channel/' + p.channelId }, 'Chat #' + p.key.toLowerCase() + ' ↗'), h('a', { href: '#/people?project=' + p.id }, 'Time zones ↗'));
  }

  async function pageProject(id, tab) {
    if (tab === 'report') return pageReport(id);
    const b = await api('GET', '/api/projects/' + id);
    const p = b.project;
    const head = h('div', { class: 'pagehead' },
      h('div', {}, h('div', { class: 'kicker' }, 'Project · ' + p.key), h('h1', {}, p.name), h('p', {}, p.description)),
      h('div', { class: 'row' }, b.members.map(m => stamp(m, 'sm')),
        S.me.admin ? h('a', { class: 'btn-ghost btn small', href: '#/admin' }, 'Members & limits') : null));
    let body;
    if (tab === 'handoff') body = handoffTab(b);
    else if (tab === 'docs') body = docsTab(b);
    else body = boardTab(b);
    S.view.onEvent = e => {
      if ((e.type === 'task' || e.type === 'project' || e.type === 'doc' || e.type === 'handoff') && e.projectId === p.id) { softRefresh(); return true; }
      return false;
    };
    return h('div', {}, head, projectTabs(p, tab), body);
  }

  function boardTab(b) {
    const p = b.project;
    const crit = b.critical.keys.length ? h('div', { class: 'critical' }, h('b', {}, 'Critical path'), h('span', { class: 'muted small' }, '(the longest chain of unfinished work – ' + b.critical.hours + ' h):'),
      b.critical.keys.map((k, i) => [i ? h('span', { class: 'arrow' }, '→') : null, h('a', { class: 'tag', href: '#/task/' + k }, k)])) : null;
    const cols = b.columns.map(col => {
      const full = col.limit && col.count >= col.limit;
      const colEl = h('section', { class: 'column' + (full ? ' full' : ''), 'data-status': col.status, 'aria-label': col.label },
        h('div', { class: 'colhead' }, h('h3', {}, col.label), h('span', { class: 'wip', title: col.limit ? 'Work-in-progress limit' : '' }, col.limit ? col.count + ' / ' + col.limit : col.count)),
        col.tasks.map(t => postcard(t, b)),
        col.status === 'TODO' && b.canWork ? h('button', { class: 'btn-ghost btn small', onclick: () => newTask(b) }, '+ Add a task') : null,
        col.status === 'DONE' ? h('div', { class: 'muted small' }, 'Finished in the last 14 days') : null);
      if (b.canWork) {
        colEl.addEventListener('dragover', e => { e.preventDefault(); colEl.classList.add('over'); });
        colEl.addEventListener('dragleave', e => { if (!colEl.contains(e.relatedTarget)) colEl.classList.remove('over'); });
        colEl.addEventListener('drop', async e => {
          e.preventDefault();
          colEl.classList.remove('over');
          const id = e.dataTransfer.getData('text/plain');
          if (!id) return;
          // place it above the card under the pointer
          let before = null;
          for (const c of colEl.querySelectorAll('.postcard')) {
            const r = c.getBoundingClientRect();
            if (e.clientY < r.top + r.height / 2 && c.dataset.id !== id) { before = c.dataset.id; break; }
          }
          await moveTask(id, col.status, before);
        });
      }
      return colEl;
    });
    return h('div', {}, crit,
      h('div', { class: 'row muted small', style: { marginBottom: '10px' } },
        'Rules: at most ' + b.personalWip + ' tasks in Doing per person', p.wipDoing ? ' · Doing holds ' + p.wipDoing : '', p.wipReview ? ' · Review holds ' + p.wipReview : '',
        p.reviewRequired ? ' · someone else must approve the review' : '', b.canWork ? ' · drag cards to move them' : ' · guests can read and comment'),
      h('div', { class: 'board' }, cols));
  }

  function postcard(t, b) {
    const el = h('article', { class: 'postcard' + (t.waitingFor.length ? ' held' : ''), draggable: b.canWork ? 'true' : null, 'data-id': t.id },
      prioStamp(t.priority),
      h('span', { class: 'key' }, t.key),
      h('div', { class: 'title' }, h('a', { href: '#/task/' + t.id }, t.title)),
      t.waitingFor.length ? h('div', { class: 'waits' }, '⛓ waits for ' + t.waitingFor.join(', ')) : null,
      h('div', { class: 'meta' },
        h('span', { class: 'row', style: { gap: '6px' } }, t.assignee ? stamp(t.assignee, 'sm') : h('span', { class: 'muted' }, 'nobody'), t.assignee ? t.assignee.name.split(' ')[0] : ''),
        h('span', { class: 'row', style: { gap: '8px' } }, t.comments ? h('span', { title: 'comments' }, '💬 ' + t.comments) : null,
          t.due ? h('span', { class: 'due' + (t.overdue ? ' over' : t.dueToday ? ' today' : '') }, (t.overdue ? 'late ' : '') + t.due.slice(5)) : null,
          b.canWork ? h('select', { 'aria-label': 'Move ' + t.key, onchange: e => moveTask(t.id, e.target.value, null), onclick: e => e.stopPropagation() },
            ['TODO', 'DOING', 'REVIEW', 'DONE'].map(s => h('option', { value: s, selected: s === t.status }, s === 'TODO' ? 'To do' : s[0] + s.slice(1).toLowerCase()))) : null)));
    if (b.canWork) {
      el.addEventListener('dragstart', e => { e.dataTransfer.setData('text/plain', t.id); e.dataTransfer.effectAllowed = 'move'; el.classList.add('dragging'); });
      el.addEventListener('dragend', () => el.classList.remove('dragging'));
    }
    return el;
  }

  async function moveTask(id, status, before) {
    try { await api('POST', '/api/tasks/' + id + '/move', { status, beforeId: before }); S.view.refresh(); }
    catch (e) { fail(e); S.view.refresh(); }
  }

  function newTask(b) {
    const members = b.members.filter(m => m.role !== 'GUEST');
    formModal('New task in ' + b.project.name, [
      { name: 'title', label: 'Title', placeholder: 'What needs to happen?' },
      { name: 'description', label: 'Details', type: 'textarea', rows: 3 },
      { name: 'assigneeId', label: 'Who', type: 'select', value: S.me.id, options: [['', 'Nobody yet']].concat(members.map(m => [m.id, m.name + ' · ' + m.city])) },
      { name: 'priority', label: 'Priority', type: 'select', value: 'NORMAL', options: [['LOW', 'Low'], ['NORMAL', 'Normal'], ['HIGH', 'High'], ['URGENT', 'Urgent']] },
      { name: 'due', label: 'Due date', type: 'date' },
      { name: 'estimate', label: 'Estimate (hours)', type: 'number', value: 4 },
    ], 'Add task', async v => {
      await api('POST', '/api/projects/' + b.project.id + '/tasks', v);
      toast('Task added.');
      S.view.refresh();
    });
  }

  function handoffTab(b) {
    const p = b.project;
    const text = h('textarea', { rows: 5 });
    text.value = 'Done today: \nNext: \nWatch out for: ';
    let to = b.batonOrder.length ? b.batonOrder[0].user.id : '';
    const choices = h('div', { class: 'list' }, b.batonOrder.map((c, i) => {
      const r = h('input', { type: 'radio', name: 'to', value: c.user.id, checked: i === 0, onchange: () => { to = c.user.id; } });
      return h('label', { class: 'check', style: { justifyContent: 'space-between' } }, h('span', { class: 'row' }, r, personLine(c.user, c.why)), postmark(c.user));
    }));
    const err = h('div', { class: 'err' });
    const form = b.canWork ? card('Hand over at the end of your day', h('span', { class: 'muted' }, 'follow the sun'),
      h('p', { class: 'muted small' }, 'Write what is done, what is next and what to watch out for. The baton goes to the colleague who can carry on soonest – they get it at the start of their day.'),
      text,
      h('h3', { style: { marginTop: '12px' } }, 'Who gets the baton'),
      b.batonOrder.length ? choices : empty('Nobody else in this project.'),
      err,
      h('button', { class: 'btn red', style: { marginTop: '10px' }, onclick: async () => {
        err.textContent = '';
        try { await api('POST', '/api/projects/' + p.id + '/handoff', { text: text.value, toId: to }); toast('Handed over. Sleep well!'); S.view.refresh(); }
        catch (e) { err.textContent = e.message; }
      } }, 'Hand over →')) : null;
    const recent = card('Recent handoffs', null, b.handoffs.length ? h('div', { class: 'list' }, b.handoffs.map(x => h('div', { class: 'baton' },
      h('div', { class: 'row between' }, h('div', { class: 'row' }, stamp(x.from, 'sm'), h('b', {}, x.from.name.split(' ')[0]), '→', stamp(x.to, 'sm'), h('b', {}, x.to.name.split(' ')[0])),
        h('span', { class: 'muted small' }, when(x.at))),
      h('pre', {}, x.text),
      h('div', { class: 'small' }, x.ackAt ? tag('picked up ' + when(x.ackAt), 'green') : x.deliverAt > Date.now() ? tag('arrives ' + when(x.deliverAt), 'blue') : tag('not picked up yet', 'ochre'),
        !x.ackAt && x.to.id === S.me.id ? h('button', { class: 'btn small red', style: { marginLeft: '8px' }, onclick: async () => { await api('POST', '/api/handoffs/' + x.id + '/ack', {}); S.view.refresh(); } }, 'Got it') : null)))) : empty('No handoffs yet.'));
    return h('div', { class: 'cols' }, h('div', { class: 'span6' }, form || card('Handoffs', null, h('p', { class: 'muted' }, 'Guests can read handoffs but not write them.'))), h('div', { class: 'span6' }, recent));
  }

  function docsTab(b) {
    return h('div', {}, h('div', { class: 'row between', style: { marginBottom: '12px' } }, h('p', { class: 'muted' }, 'Shared documents. If two people save at the same time, Sangam combines the changes.'),
      b.canWork ? h('button', { class: 'btn red', onclick: () => newDoc(b.project.id) }, '+ New document') : null),
      b.docs.length ? h('div', { class: 'doclist' }, b.docs.map(docItem)) : empty('No documents yet.'));
  }
  function docItem(d) {
    return h('a', { class: 'card envelope docitem', href: '#/doc/' + d.id }, h('h3', {}, d.title),
      d.projectName ? h('span', { class: 'muted small' }, d.projectName) : null,
      h('div', { class: 'muted small' }, 'v' + d.version + ' · ' + d.lines + ' lines · ' + d.updatedBy + ', ' + when(d.updatedAt)),
      d.editing.length ? h('div', { class: 'editingnow' }, '✎ ' + d.editing.join(', ') + ' editing now') : null);
  }
  function newDoc(projectId) {
    const projects = S.meta.projects;
    formModal('New document', [
      { name: 'projectId', label: 'Project', type: 'select', value: projectId || (projects[0] && projects[0].id), options: projects.map(p => [p.id, p.name]) },
      { name: 'title', label: 'Title', placeholder: 'Meeting notes, plan, how-to…' },
      { name: 'body', label: 'Text', type: 'textarea', rows: 8 },
    ], 'Create', async v => {
      const d = await api('POST', '/api/projects/' + v.projectId + '/docs', { title: v.title, body: v.body });
      go('#/doc/' + d.id);
    });
  }

  // ------------------------------------------------------------------ task page
  async function pageTask(id) {
    const t = await api('GET', '/api/tasks/' + encodeURIComponent(id));
    S.view.onEvent = e => { if (e.type === 'task' && (e.data.id === t.id)) { softRefresh(); return true; } return false; };
    const statusSel = h('select', { disabled: !t.canWork, 'aria-label': 'Column', onchange: async e => {
      try { await api('POST', '/api/tasks/' + t.id + '/move', { status: e.target.value }); toast('Moved.'); S.view.refresh(); } catch (x) { fail(x); S.view.refresh(); }
    } }, ['TODO', 'DOING', 'REVIEW', 'DONE'].map(s => h('option', { value: s, selected: s === t.status }, s === 'TODO' ? 'To do' : s[0] + s.slice(1).toLowerCase())));

    const comment = h('textarea', { rows: 3, placeholder: 'Write a comment. Use @name to ask someone.' });
    const comments = card('Comments', h('span', { class: 'muted' }, t.commentList.length),
      t.commentList.length ? t.commentList.map(c => h('div', { class: 'comment' }, stamp(c.by, 'sm'), h('div', {}, h('div', { class: 'row' }, h('b', {}, c.by ? c.by.name : '—'), h('span', { class: 'muted small' }, when(c.at))), h('div', { class: 'body' }, rich(c.text))))) : empty('No comments yet.'),
      h('div', { class: 'stack', style: { marginTop: '10px' } }, comment, h('button', { class: 'btn', onclick: async () => {
        try { await api('POST', '/api/tasks/' + t.id + '/comments', { text: comment.value }); comment.value = ''; S.view.refresh(); } catch (e) { fail(e); }
      } }, 'Add comment')));

    const left = h('div', { class: 'stack' },
      h('div', { class: 'card envelope' },
        h('div', { class: 'row between' }, h('div', { class: 'row' }, h('a', { class: 'tag', href: '#/project/' + t.project.id }, t.project.name), h('span', { class: 'mono muted' }, t.key)), prioStamp(t.priority)),
        h('h1', { style: { margin: '10px 0' } }, t.title),
        t.description ? h('div', { class: 'desc' }, rich(t.description)) : h('div', { class: 'empty' }, 'No details yet.'),
        t.waitingFor.length ? h('p', { class: 'err', style: { marginTop: '10px' } }, '⛓ On hold: waits for ' + t.waitingFor.join(', ') + '. It cannot start before that is done.') : null),
      comments);

    const blockerPick = h('div', { class: 'chips' }, t.taskOptions.filter(o => o.status !== 'DONE' || t.blockedBy.some(b => b.id === o.id)).map(o => {
      const on = t.blockedBy.some(b => b.id === o.id);
      const input = h('input', { type: 'checkbox', value: o.id, checked: on, disabled: !t.canWork });
      const lab = h('label', { class: 'chip' + (on ? ' on' : ''), title: o.title, style: { paddingLeft: '10px' } }, input, o.key);
      input.addEventListener('change', () => lab.classList.toggle('on', input.checked));
      return lab;
    }));
    const right = h('div', { class: 'stack' },
      card('Details', t.canWork ? h('button', { class: 'linkbtn', onclick: () => editTask(t) }, 'Edit') : null,
        h('dl', { class: 'kv' },
          h('dt', {}, 'Column'), h('dd', {}, statusSel),
          h('dt', {}, 'Who'), h('dd', {}, t.assignee ? personLine(t.assignee, t.assignee.city) : 'nobody'),
          h('dt', {}, 'Priority'), h('dd', {}, t.priority.toLowerCase()),
          h('dt', {}, 'Due'), h('dd', { class: t.overdue ? 'err' : '' }, t.due || '—'),
          h('dt', {}, 'Estimate'), h('dd', {}, t.estimate + ' h'),
          h('dt', {}, 'Created'), h('dd', {}, (t.createdBy ? t.createdBy.name + ', ' : '') + when(t.createdAt)),
          t.startedAt ? [h('dt', {}, 'Started'), h('dd', {}, when(t.startedAt))] : null,
          t.doneAt ? [h('dt', {}, 'Done'), h('dd', {}, when(t.doneAt))] : null,
          h('dt', {}, 'Version'), h('dd', { class: 'mono' }, 'v' + t.version + (t.updatedBy ? ' · ' + t.updatedBy.name.split(' ')[0] : '')))),
      card('Waits for', null, h('p', { class: 'muted small' }, 'Tasks that must be done first. Sangam refuses loops (A waits for B waits for A).'), blockerPick,
        t.canWork ? h('button', { class: 'btn-ghost btn small', style: { marginTop: '10px' }, onclick: async () => {
          const ids = [...blockerPick.querySelectorAll('input:checked')].map(i => i.value);
          try { await api('POST', '/api/tasks/' + t.id + '/blockers', { blockedBy: ids }); toast('Saved.'); S.view.refresh(); } catch (e) { fail(e); S.view.refresh(); }
        } }, 'Save') : null,
        t.dependents.length ? h('div', { style: { marginTop: '10px' } }, h('b', { class: 'small' }, 'Waiting on this task: '), t.dependents.map(x => h('a', { class: 'tag', href: '#/task/' + x.id, style: { marginRight: '4px' } }, x.key))) : null),
      card('History', null, h('div', { class: 'history' }, t.history.map(x => h('div', {}, h('span', { class: 'mono muted' }, when(x.at)), ' ', h('b', {}, x.by), ' ', x.what)))));
    return h('div', {}, h('div', { class: 'row', style: { marginBottom: '12px' } }, h('a', { href: '#/project/' + t.project.id }, '← ' + t.project.name + ' board')), h('div', { class: 'taskpage' }, left, right));
  }

  /** Edit a task: we send the new values AND the values we started from, so the server can merge field by field. */
  function editTask(t) {
    const base = { title: t.title, description: t.description || '', priority: t.priority, assigneeId: t.assigneeId || '', due: t.due || '', estimate: String(t.estimate) };
    const fields = [
      { name: 'title', label: 'Title', value: base.title },
      { name: 'description', label: 'Details', type: 'textarea', rows: 5, value: base.description },
      { name: 'assigneeId', label: 'Who', type: 'select', value: base.assigneeId, options: [['', 'Nobody']].concat(t.members.map(m => [m.id, m.name])) },
      { name: 'priority', label: 'Priority', type: 'select', value: base.priority, options: [['LOW', 'Low'], ['NORMAL', 'Normal'], ['HIGH', 'High'], ['URGENT', 'Urgent']] },
      { name: 'due', label: 'Due date', type: 'date', value: base.due },
      { name: 'estimate', label: 'Estimate (hours)', type: 'number', value: base.estimate },
    ];
    formModal('Edit ' + t.key, fields, 'Save', async v => {
      const changes = {}, b = {};
      for (const k in v) if (String(v[k]) !== String(base[k])) { changes[k] = v[k]; b[k] = base[k]; }
      if (!Object.keys(changes).length) return;
      try {
        await api('PUT', '/api/tasks/' + t.id, { changes, base: b });
        toast('Saved.');
        S.view.refresh();
      } catch (e) {
        if (e.status === 409 && e.data && e.data.conflicts) { setTimeout(() => resolveTask(t, changes, e), 0); return; }
        throw e;
      }
    });
  }

  /** Someone changed the same field while we were editing: show both values and let the person choose. */
  function resolveTask(t, changes, e) {
    const pick = {};
    const names = { title: 'Title', description: 'Details', priority: 'Priority', assigneeId: 'Who', due: 'Due date', estimate: 'Estimate' };
    const show = (f, v) => f === 'assigneeId' ? ((t.members.find(m => m.id === v) || {}).name || 'nobody') : (v == null || v === '' ? '(empty)' : v);
    const body = h('div', {}, h('p', {}, e.message),
      e.data.conflicts.map(c => {
        pick[c.field] = 'theirs';
        const a = h('input', { type: 'radio', name: 'c-' + c.field, checked: true, onchange: () => { pick[c.field] = 'theirs'; } });
        const b = h('input', { type: 'radio', name: 'c-' + c.field, onchange: () => { pick[c.field] = 'yours'; } });
        return h('div', { class: 'conflict' }, h('b', {}, names[c.field] || c.field),
          h('div', { class: 'opt' }, h('label', { class: 'check' }, a, h('div', {}, h('div', { class: 'small muted' }, 'Their value (saved now)'), show(c.field, c.theirs))),
            h('label', { class: 'check' }, b, h('div', {}, h('div', { class: 'small muted' }, 'Your value'), show(c.field, c.yours)))));
      }));
    modal('Two edits at the same time', body, close => [h('button', { class: 'btn-ghost btn', onclick: () => { close(); S.view.refresh(); } }, 'Keep all of theirs'),
      h('button', { class: 'btn red', onclick: async () => {
        // resend: fields where I keep mine use THEIR value as the new base; non-conflicting fields stay as they were
        const ch = {}, base = {};
        const conflicted = {};
        for (const c of e.data.conflicts) conflicted[c.field] = c;
        for (const f in changes) {
          if (conflicted[f]) { if (pick[f] === 'yours') { ch[f] = changes[f]; base[f] = conflicted[f].theirs == null ? '' : conflicted[f].theirs; } }
        }
        try {
          const fresh = await api('GET', '/api/tasks/' + t.id);
          for (const f in changes) if (!conflicted[f]) { ch[f] = changes[f]; base[f] = f === 'estimate' ? String(fresh.estimate) : (f === 'assigneeId' ? (fresh.assigneeId || '') : (fresh[f] == null ? '' : fresh[f])); }
          if (Object.keys(ch).length) await api('PUT', '/api/tasks/' + t.id, { changes: ch, base });
          close(); toast('Saved with your choices.'); S.view.refresh();
        } catch (x) { fail(x); }
      } }, 'Save my choices')]);
  }

  // ------------------------------------------------------------------ chat
  async function pageChannel(id, threadId, focusMsg) {
    const d = await api('GET', '/api/channels/' + id);
    const c = d.channel;
    let lastRead = d.lastRead;
    const feed = h('div', { class: 'messages', id: 'messages', tabindex: '0', 'aria-label': 'Messages' });
    const threadBox = h('div', { class: 'feed thread' });
    const later = h('div', {});

    function msgNode(m, inThread) {
      const a = m.author || {};
      const p = personById(a.id);
      const theirTime = p && p.zone !== S.me.zone ? h('span', { class: 'theirs', title: 'their local time' }, '(' + (m.authorLocal || '') + ' in ' + (m.authorCity || p.city) + ')') : null;
      const node = h('div', { class: 'msg' + (m.urgent ? ' urgent' : '') + (m.kind === 'handoff' ? ' handoff' : '') + (m.mentionsMe ? ' me-mentioned' : ''), id: 'm-' + m.id },
        stamp(a, 'sm'),
        h('div', {}, h('div', { class: 'head' }, h('b', {}, a.name || '—'), h('time', { datetime: new Date(m.at).toISOString(), title: dayShort(m.at) }, hm(m.at)), theirTime,
          m.urgent ? tag('URGENT', 'red') : null, m.kind === 'handoff' ? tag('handoff', 'ochre') : null, m.editedAt ? h('span', { class: 'muted small' }, '(edited)') : null),
          m.deleted ? h('div', { class: 'deleted' }, 'This message was removed.') : h('div', { class: 'text' }, rich(m.text)),
          m.reactions.length ? h('div', { class: 'reacts' }, m.reactions.map(r => h('button', { class: r.mine ? 'mine' : '', title: r.names.join(', '), onclick: () => react(m.id, r.emoji) }, r.emoji + ' ' + r.count))) : null,
          !inThread && m.replies ? h('div', { class: 'replies' }, h('button', { onclick: () => go('#/channel/' + c.id + '/' + m.id) }, m.replies + (m.replies === 1 ? ' reply' : ' replies') + ' · last ' + when(m.lastReplyAt))) : null),
        m.deleted ? null : h('div', { class: 'tools' },
          d.reactions.slice(0, 4).map(e => h('button', { title: 'React ' + e, onclick: () => react(m.id, e) }, e)),
          !inThread ? h('button', { title: 'Reply in thread', onclick: () => go('#/channel/' + c.id + '/' + m.id) }, '↩') : null,
          m.canEdit ? h('button', { title: 'Edit', onclick: () => editMessage(m) }, '✎') : null,
          m.mine || S.me.admin ? h('button', { title: 'Delete', onclick: () => delMessage(m) }, '🗑') : null));
      return node;
    }
    async function react(mid, emoji) { try { await api('POST', '/api/messages/' + mid + '/react', { emoji }); } catch (e) { fail(e); } }
    function editMessage(m) {
      formModal('Edit message', [{ name: 'text', label: 'Text', type: 'textarea', rows: 5, value: m.text }], 'Save', async v => { await api('PUT', '/api/messages/' + m.id, { text: v.text }); });
    }
    async function delMessage(m) {
      modal('Delete this message?', h('p', { class: 'muted' }, 'People will see "This message was removed".'), close => [h('button', { class: 'btn-ghost btn', onclick: close }, 'Keep'),
        h('button', { class: 'btn red', onclick: async () => { close(); try { await api('POST', '/api/messages/' + m.id + '/delete', {}); } catch (e) { fail(e); } } }, 'Delete')]);
    }

    function fillFeed(messages, scrollToEnd) {
      const atEnd = feed.scrollHeight - feed.scrollTop - feed.clientHeight < 80;
      clear(feed);
      if (d.more) feed.appendChild(h('div', { class: 'muted small', style: { textAlign: 'center', padding: '6px' } }, 'Older messages are not shown.'));
      let lastDay = null, unreadShown = false;
      for (const m of messages) {
        const k = dayKey(m.at);
        if (k !== lastDay) { feed.appendChild(h('div', { class: 'daysep' }, h('span', {}, dayShort(m.at)))); lastDay = k; }
        if (!unreadShown && m.seq > lastRead && !m.mine) { feed.appendChild(h('div', { class: 'unreadline' }, 'new')); unreadShown = true; }
        feed.appendChild(msgNode(m, false));
      }
      if (!messages.length) feed.appendChild(empty('No messages yet. Say hello!'));
      if (scrollToEnd || atEnd) feed.scrollTop = feed.scrollHeight;
    }

    async function reloadFeed() {
      const fresh = await api('GET', '/api/channels/' + id);
      d.messages = fresh.messages;
      fillFeed(fresh.messages, false);
      if (threadId) await loadThread();
      await markRead();
    }
    async function markRead() {
      if (document.hidden) return;
      try { await api('POST', '/api/channels/' + id + '/read', {}); } catch (e) { /* fine */ }
    }

    async function loadThread() {
      clear(threadBox);
      let t;
      try { t = await api('GET', '/api/messages/' + threadId + '/thread'); } catch (e) { threadBox.appendChild(empty('Thread not found.')); return; }
      const list = h('div', { class: 'messages' }, msgNode(t.parent, true), h('div', { class: 'daysep' }, h('span', {}, t.replies.length + ' replies')), t.replies.map(r => msgNode(r, true)));
      add(threadBox, [h('div', { class: 'feedhead' }, h('h2', {}, 'Thread'), h('a', { class: 'btn-ghost btn small', href: '#/channel/' + c.id }, '✕')), list,
        composer(t.parent.id, 'Reply in the thread…')]);
      list.scrollTop = list.scrollHeight;
    }

    /** The text box, with @name suggestions (arrow keys + Enter) and an "urgent" switch. */
    function composer(parentId, placeholder) {
      const ta = h('textarea', { placeholder, 'aria-label': placeholder, rows: 2 });
      const urgent = h('input', { type: 'checkbox' });
      const sug = h('div', { class: 'suggest', hidden: true });
      let sel = 0, items = [];
      const people = d.members;
      function update() {
        const pos = ta.selectionStart;
        const before = ta.value.slice(0, pos);
        const m = /(^|[\s(])@([A-Za-z0-9._-]*)$/.exec(before);
        if (!m) { sug.hidden = true; return; }
        const q = m[2].toLowerCase();
        items = [{ username: 'here', name: 'Everybody working now', special: true }, { username: 'team', name: 'Everybody in this channel', special: true }]
          .concat(people).filter(p => p.username.startsWith(q) || (p.name || '').toLowerCase().includes(q)).slice(0, 8);
        if (!items.length) { sug.hidden = true; return; }
        sel = Math.min(sel, items.length - 1);
        clear(sug);
        items.forEach((p, i) => sug.appendChild(h('button', { type: 'button', class: i === sel ? 'on' : '', onmousedown: e => { e.preventDefault(); pickName(p); } },
          p.special ? h('b', {}, '@' + p.username) : stamp(p, 'sm'), h('span', { class: 'grow' }, p.special ? p.name : p.name),
          p.special ? null : h('span', { class: 'mono muted small' }, hm(Date.now(), p.zone) + (p.clock && p.clock.working ? '' : ' 🌙')))));
        sug.hidden = false;
      }
      function pickName(p) {
        const pos = ta.selectionStart;
        const before = ta.value.slice(0, pos).replace(/@([A-Za-z0-9._-]*)$/, '@' + p.username + ' ');
        ta.value = before + ta.value.slice(pos);
        ta.selectionStart = ta.selectionEnd = before.length;
        sug.hidden = true;
        ta.focus();
      }
      async function send() {
        const text = ta.value.trim();
        if (!text) return;
        try {
          const r = await api('POST', '/api/channels/' + id + '/messages', { text, parentId, urgent: urgent.checked });
          ta.value = ''; urgent.checked = false;
          clear(later);
          if (r.later.length) later.appendChild(h('div', { class: 'later', role: 'status' }, '🌙 ',
            r.later.map((x, i) => [i ? '; ' : '', h('b', {}, x.name.split(' ')[0]), ' is ' + x.why + ' (' + x.localNow + ' in ' + x.city + ') – will see it ' + x.deliverLocal + ' their time']),
            '. Need it now? Tick "urgent" – but only for real emergencies.'));
          await reloadFeed();
          feed.scrollTop = feed.scrollHeight;
        } catch (e) { fail(e); }
      }
      ta.addEventListener('input', update);
      ta.addEventListener('keydown', e => {
        if (!sug.hidden) {
          if (e.key === 'ArrowDown') { sel = (sel + 1) % items.length; update(); e.preventDefault(); return; }
          if (e.key === 'ArrowUp') { sel = (sel - 1 + items.length) % items.length; update(); e.preventDefault(); return; }
          if (e.key === 'Enter' || e.key === 'Tab') { pickName(items[sel]); e.preventDefault(); return; }
          if (e.key === 'Escape') { sug.hidden = true; return; }
        }
        if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); send(); }
      });
      return h('div', { class: 'composer' }, sug, ta,
        h('div', { class: 'row between' },
          h('label', { class: 'check small', title: 'Urgent messages ring even at night. ' + d.urgentLeft + ' left today.' }, urgent, 'Urgent (' + d.urgentLeft + ' left today)'),
          h('span', { class: 'hint' }, 'Enter to send · Shift+Enter new line · @ to mention'),
          h('button', { class: 'btn small', onclick: send }, 'Send')));
    }

    const awake = d.members.filter(m => m.clock.working).length;
    const head = h('div', { class: 'feedhead' },
      h('div', {}, h('h2', {}, c.name), h('div', { class: 'muted small' }, c.topic || (c.kind === 'DIRECT' ? 'Direct message' : ''),
        c.projectId ? [' · ', h('a', { href: '#/project/' + c.projectId }, c.projectName + ' board')] : null)),
      h('span', { class: 'tag ' + (awake ? 'green' : '') }, awake + ' of ' + d.members.length + ' working now'));
    const main = h('div', { class: 'feed' }, head, feed, later, composer(null, 'Write to ' + c.name + '…'));
    const side = threadId ? threadBox : h('aside', { class: 'side' }, card('In this channel', null, h('div', { class: 'teamlist' }, d.members.map(teamRow))));
    const page = h('div', { class: 'chat' + (threadId ? ' threaded' : '') }, main, side);
    fillFeed(d.messages, true);
    if (threadId) loadThread();
    setTimeout(() => {
      feed.scrollTop = feed.scrollHeight;
      if (focusMsg) { const el = document.getElementById('m-' + focusMsg); if (el) { el.scrollIntoView({ block: 'center' }); el.classList.add('flash'); } }
    }, 0);
    markRead().then(refreshChannels);
    // live: new messages in THIS channel are added without redrawing the page (the text box keeps what you type)
    let t = null;
    S.view.onEvent = e => {
      if ((e.type === 'message' || e.type === 'message.edit') && e.channelId === c.id) {
        clearTimeout(t); t = setTimeout(() => { lastRead = Number.MAX_SAFE_INTEGER; reloadFeed().then(refreshChannels); }, 150);
        return true;
      }
      return false;
    };
    return page;
  }

  // ------------------------------------------------------------------ world clock (time zones)
  async function pagePeople(date, projectId) {
    const d = await api('GET', '/api/zones?' + (date ? 'date=' + date + '&' : '') + (projectId ? 'project=' + projectId : ''));
    const now = Date.now();
    const isToday = now >= d.dayStart && now < d.dayStart + 864e5;
    const shift = k => { const x = new Date(d.dayStart + k * 864e5).toISOString().slice(0, 10); return '#/people?date=' + x + (projectId ? '&project=' + projectId : ''); };
    const pct = isToday ? ((now - d.dayStart) / 864e5) * 100 : -1;
    const nowLine = () => pct >= 0 ? h('i', { class: 'nowline', style: { left: pct + '%' } }) : null;
    const hours = h('div', { class: 'hours' }, Array.from({ length: 24 }, (_, i) => h('span', {}, String(i).padStart(2, '0'))));
    const myHours = h('div', { class: 'localh' }, Array.from({ length: 24 }, (_, i) => h('span', {}, hm(d.dayStart + i * 36e5).slice(0, 2))));
    const rows = d.rows.map(r => {
      const p = r.person;
      return h('tr', {}, h('th', {}, h('div', { class: 'row between', style: { flexWrap: 'nowrap' } }, personLine(p, (p.city || p.zone) + ' · ' + p.hours), h('span', { class: 'mono small' }, hm(now, p.zone)))),
        h('td', {}, h('div', { class: 'bar', title: p.name + ': local hours ' + r.localHours.join(' ') }, r.costs.map((c, i) => h('i', { class: 'c' + c, title: hm(d.dayStart + i * 9e5, p.zone) + ' for ' + p.name.split(' ')[0] + ' – ' + COST_LABEL[c] })), nowLine()),
          h('div', { class: 'localh' }, r.localHours.map(x => h('span', {}, String(x).padStart(2, '0'))))));
    });
    const max = Math.max(1, ...d.counts);
    const countRow = h('tr', {}, h('th', {}, h('b', {}, 'Working at once'), h('div', { class: 'muted small' }, 'red = the whole team')),
      h('td', {}, h('div', { class: 'count', style: { position: 'relative' } }, d.counts.map((c, i) => h('i', { class: c === d.teamSize && c > 0 ? 'all' : '', style: { height: (c / max * 100) + '%' }, title: hm(d.dayStart + i * 9e5) + ' your time: ' + c + ' working' })), nowLine())));
    const together = d.allTogether.slots > 0
      ? 'The whole team overlaps ' + d.allTogether.slots / 4 + ' h: ' + hm(d.allTogether.start) + '–' + hm(d.allTogether.start + d.allTogether.slots * 9e5) + ' your time.'
      : 'The whole team is never working at the same time on this day – that is normal for a team like this. Work async, use handoffs, and use the best overlap (' + d.most.people + ' people for ' + (d.most.slots / 4) + ' h) for calls.';
    const people = {};
    for (const r of d.rows) people[r.person.id] = r.person;
    const team = d.rows.map(r => r.person).filter(p => p.role !== 'GUEST');
    const pairOf = (a, b) => (d.pairs.find(x => (x.a === a && x.b === b) || (x.a === b && x.b === a)) || {}).hours;
    const matrix = h('table', { class: 'matrix' }, h('tr', {}, h('th', {}), team.map(p => h('th', { title: p.name }, p.initials))),
      team.map(a => h('tr', {}, h('th', { style: { textAlign: 'left' } }, a.name.split(' ')[0]), team.map(b => {
        if (a.id === b.id) return h('td', { class: 'z' }, '·');
        const v = pairOf(a.id, b.id) || 0;
        return h('td', { class: v ? '' : 'z', style: { background: v ? 'color-mix(in srgb, var(--sea) ' + Math.min(80, v * 2) + '%, transparent)' : '' }, title: a.name + ' & ' + b.name + ': ' + v + ' h a week together' }, v ? h('b', {}, v) : '0');
      }))));
    const projSel = h('select', { 'aria-label': 'Project', onchange: e => go('#/people' + (e.target.value ? '?project=' + e.target.value : '')) },
      h('option', { value: '' }, 'Everyone I can see'), S.meta.projects.map(p => h('option', { value: p.id, selected: p.id === projectId }, p.name)));
    return h('div', {},
      h('div', { class: 'pagehead' }, h('div', {}, h('div', { class: 'kicker' }, 'World clock'), h('h1', {}, 'Who is working when'),
        h('p', {}, 'Each row is one day in UTC (' + d.date + '). Blue = working hours, lighter = just outside, dark with stars = night, stripes = day off. Small numbers under each bar are that person\'s local hours.')),
        h('div', { class: 'row' }, projSel, h('a', { class: 'btn-ghost btn small', href: shift(-1) }, '← day'), h('a', { class: 'btn-ghost btn small', href: '#/people' + (projectId ? '?project=' + projectId : '') }, 'Today'), h('a', { class: 'btn-ghost btn small', href: shift(1) }, 'day →'))),
      h('div', { class: 'stats', style: { marginBottom: '16px' } },
        h('div', { class: 'stat' }, h('b', {}, d.coverageHours + ' h'), h('span', {}, 'of 24 with someone at work (follow-the-sun coverage)')),
        h('div', { class: 'stat' }, h('b', {}, (d.allTogether.slots / 4) + ' h'), h('span', {}, 'when everybody overlaps')),
        h('div', { class: 'stat' }, h('b', {}, d.most.people + ' / ' + d.teamSize), h('span', {}, 'most people at once, for ' + d.most.slots / 4 + ' h'))),
      h('div', { class: 'card', style: { marginBottom: '16px' } }, h('p', {}, together),
        h('div', { class: 'legend' }, [['c0', 'working'], ['c1', 'up to 1 h outside'], ['c2', 'up to 2 h outside'], ['c3', 'well outside'], ['c4', 'day off'], ['c5', 'night (22–07)']].map(x => h('span', {}, h('i', { class: x[0] }), x[1])),
          h('span', {}, h('i', { style: { background: 'var(--red)', width: '3px' } }), 'now'))),
      h('div', { class: 'card strip' }, h('table', {},
        h('tr', {}, h('th', {}, h('span', { class: 'muted small' }, 'UTC hours →', h('br'), 'your hours →')), h('td', {}, hours, myHours)),
        rows, countRow)),
      h('div', { class: 'card', style: { marginTop: '16px', overflowX: 'auto' } }, h('h3', {}, 'Hours a week two people are both working'), matrix,
        h('p', { class: 'muted small', style: { marginTop: '8px' } }, 'A 0 means those two never overlap: they should talk in threads, documents and handoffs.')));
  }

  // ------------------------------------------------------------------ calls (fair meeting times)
  async function pageMeetings() {
    const d = await api('GET', '/api/meetings');
    const people = (S.meta.people || []).filter(p => p.id !== S.me.id);
    const picks = h('div', { class: 'chips' }, people.map(p => chipToggle(p, false)));
    const title = h('input', { type: 'text', placeholder: 'Sprint planning', 'aria-label': 'Title' });
    const minutes = h('select', { 'aria-label': 'Length' }, [15, 30, 45, 60, 90].map(m => h('option', { value: m, selected: m === 30 }, m + ' min')));
    const days = h('select', { 'aria-label': 'Look ahead' }, [1, 2, 3, 5, 7].map(m => h('option', { value: m, selected: m === 3 }, 'next ' + m + ' day' + (m > 1 ? 's' : ''))));
    const project = h('select', { 'aria-label': 'Project' }, h('option', { value: '' }, 'No project'), S.meta.projects.map(p => h('option', { value: p.id }, p.name)));
    const agenda = h('textarea', { rows: 2, placeholder: 'Agenda – what must be decided live?' });
    const results = h('div', {});
    const ids = () => [...picks.querySelectorAll('input:checked')].map(i => i.value);
    project.addEventListener('change', () => {
      const pr = S.meta.projects.find(p => p.id === project.value);
      if (!pr) return;
      const members = new Set(pr.members.map(m => m.id));
      picks.querySelectorAll('input').forEach(i => { i.checked = members.has(i.value); i.closest('.chip').classList.toggle('on', i.checked); });
    });
    async function find() {
      clear(results);
      try {
        const r = await api('POST', '/api/meetings/suggest', { attendeeIds: ids(), minutes: +minutes.value, days: +days.value });
        add(results, [
          r.asyncHint ? h('div', { class: 'asynchint' }, h('b', {}, 'Maybe no call? '), r.asyncHint) : null,
          r.options.length ? h('div', { class: 'list', style: { marginTop: '10px' } }, r.options.map((o, i) => h('div', { class: 'option' },
            h('div', { class: 'when' }, h('b', {}, hm(o.start) + '–' + hm(o.end)), h('span', { class: 'muted small' }, dayShort(o.start) + ' · your time'), i === 0 ? h('div', {}, tag('fairest', 'green')) : null),
            h('div', { class: 'costs' }, o.people.map(x => h('span', { class: 'cost k' + x.cost, title: x.name + ': ' + x.label + (x.pain ? ' · pain so far ' + x.pain : '') },
              stamp(personById(x.userId) || { id: x.userId, name: x.name }, 'sm'), h('span', { class: 'mono' }, x.local.slice(4)), x.cost ? h('span', { class: 'small' }, COST_LABEL[x.cost]) : null))),
            h('button', { class: 'btn ' + (i === 0 ? 'red' : ''), onclick: async () => {
              try {
                await api('POST', '/api/meetings', { title: title.value, attendeeIds: ids(), minutes: +minutes.value, start: o.start, projectId: project.value, agenda: agenda.value });
                toast('Call booked. Everybody got an invitation in their own time.'); S.view.refresh();
              } catch (e) { fail(e); }
            } }, 'Book')))) : empty('No free time found.')]);
      } catch (e) { fail(e); }
    }
    const planner = card('Plan a call', h('span', { class: 'muted' }, 'fair across time zones'),
      h('p', { class: 'muted small' }, 'Sangam scores every half hour: 0 in working hours … 5 at night. People who already took many bad times count more, so the pain rotates around the team.'),
      h('div', { class: 'grid2' }, h('label', {}, 'Title', title), h('label', {}, 'Project', project)),
      h('label', { style: { marginTop: '10px' } }, 'Who (you are always in)', picks),
      h('div', { class: 'grid2', style: { marginTop: '10px' } }, h('label', {}, 'Length', minutes), h('label', {}, 'When', days)),
      h('label', { style: { marginTop: '10px' } }, 'Agenda', agenda),
      h('div', { class: 'row', style: { marginTop: '10px' } }, h('button', { class: 'btn', onclick: find }, 'Find fair times')), results);
    const meetingNode = m => h('div', { class: 'meeting' + (m.cancelled ? ' cancelled' : '') + (m.live ? ' live' : '') },
      h('div', { class: 'date' }, h('span', { class: 'small' }, dayShort(m.start).split(' ')[0]), h('b', {}, hm(m.start)), h('span', { class: 'small muted' }, dayShort(m.start).split(' ').slice(1).join(' '))),
      h('div', {}, h('b', {}, m.title), m.live ? ' ' : null, m.live ? tag('now', 'red') : null, h('div', { class: 'muted small' }, 'by ' + m.organizer.name + (m.project ? ' · ' + m.project.name : '') + ' · ' + Math.round((m.end - m.start) / 6e4) + ' min'),
        h('div', { class: 'costs', style: { marginTop: '6px' } }, m.people.map(x => h('span', { class: 'cost k' + x.cost, title: x.user.name + ' – ' + x.label }, stamp(x.user, 'sm'), h('span', { class: 'mono' }, x.local))))),
      m.mine && !m.cancelled && m.end > Date.now() ? h('button', { class: 'btn-ghost btn small', onclick: async () => { try { await api('POST', '/api/meetings/' + m.id + '/cancel', {}); toast('Cancelled – their pain points were given back.'); S.view.refresh(); } catch (e) { fail(e); } } }, 'Cancel') : h('span'));
    const maxPain = Math.max(1, ...d.ledger.map(x => x.pain));
    const ledger = card('Fairness ledger', h('span', { class: 'muted' }, 'pain points'),
      h('p', { class: 'muted small' }, 'Points each person took for calls outside their hours. High points = Sangam protects them next time.'),
      h('table', { class: 'plain' }, h('tr', {}, h('th', {}, 'Person'), h('th', {}, 'Pain'), h('th', {}, 'Calls (28 d)'), h('th', {}, 'Outside hours')),
        d.ledger.map(x => h('tr', {}, h('td', {}, personLine(x.user, x.city)), h('td', { style: { width: '35%' } }, h('div', { class: 'row', style: { flexWrap: 'nowrap' } }, h('div', { class: 'painbar grow' }, h('i', { style: { width: (x.pain / maxPain * 100) + '%' } })), h('span', { class: 'mono small' }, x.pain))),
          h('td', { class: 'mono' }, x.calls), h('td', { class: 'mono' }, x.outside)))));
    return h('div', {}, h('div', { class: 'pagehead' }, h('div', {}, h('div', { class: 'kicker' }, 'Calls'), h('h1', {}, 'Calls at fair times'),
      h('p', {}, 'For a remote team, a call is expensive: someone is often up early or late. Decide in writing when you can; call when you must.'))),
      h('div', { class: 'cols' },
        h('div', { class: 'span7 stack' }, me().guest ? null : planner, card('Coming up', null, d.upcoming.length ? h('div', {}, d.upcoming.map(meetingNode)) : empty('Nothing planned.')),
          card('Earlier', h('span', { class: 'muted' }, 'last 3 weeks'), d.past.length ? h('div', {}, d.past.map(meetingNode)) : empty('No calls yet.'))),
        h('div', { class: 'span5' }, ledger)));
  }
  const me = () => S.me;

  // ------------------------------------------------------------------ stand-ups
  async function pageStandups(projectId) {
    const d = await api('GET', '/api/standups' + (projectId ? '?project=' + projectId : ''));
    const mine = d.rows.find(r => r.user.id === S.me.id);
    const sel = h('select', { 'aria-label': 'Project', onchange: e => go('#/standups' + (e.target.value ? '?project=' + e.target.value : '')) },
      h('option', { value: '' }, 'Whole company'), S.meta.projects.map(p => h('option', { value: p.id, selected: p.id === projectId }, p.name)));
    const stateTag = { posted: ['posted', 'green'], late: ['not posted – late', 'red'], waiting: ['day just started', 'blue'], 'not-started': ['day not started yet', ''], 'day-off': ['day off', ''] };
    return h('div', {},
      h('div', { class: 'pagehead' }, h('div', {}, h('div', { class: 'kicker' }, 'Async stand-up'), h('h1', {}, 'Stand-ups in everyone\'s own morning'),
        h('p', {}, d.posted + ' of ' + d.expected + ' people whose day has started have posted. "Late" means their day began more than 2 hours ago.')), sel),
      h('div', { class: 'cols' },
        h('div', { class: 'span4 stack' },
          S.me.guest ? null : card('Yours for ' + (mine ? mine.date : 'today'), null, standupForm(mine && mine.standup, () => S.view.refresh())),
          card('Blockers', h('span', { class: 'muted' }, d.blockers.length), d.blockers.length ? h('div', { class: 'list' }, d.blockers.map(b => h('div', {}, h('b', {}, b.name), h('div', { class: 'msg', style: { display: 'block', padding: 0 } }, h('div', { class: 'text' }, rich(b.text))), h('span', { class: 'muted small' }, when(b.at))))) : empty('Nobody is blocked. 🎉'))),
        h('div', { class: 'span8' }, h('div', { class: 'standups' }, d.rows.map(r => {
          const s = r.standup, prev = r.previous;
          const st = stateTag[r.state];
          return h('div', { class: 'card su ' + r.state },
            h('div', { class: 'row between' }, personLine(r.user, r.user.city + ' · ' + r.date), postmark(r.user)),
            h('div', {}, tag(st[0], st[1])),
            s ? [h('div', { class: 'q' }, 'Yesterday'), h('div', { class: 'a' }, rich(s.yesterday || '—')), h('div', { class: 'q' }, 'Today'), h('div', { class: 'a' }, rich(s.today)),
              s.blockers && s.blockers.toLowerCase() !== 'none' ? h('div', { class: 'blk' }, rich('Blocked: ' + s.blockers)) : null, h('div', { class: 'muted small' }, 'posted ' + when(s.at))]
              : prev ? [h('div', { class: 'q' }, 'Last time (' + prev.date + ')'), h('div', { class: 'a muted' }, prev.today)] : null);
        }))))) ;
  }

  // ------------------------------------------------------------------ documents
  async function pageDocs() {
    const list = await api('GET', '/api/docs');
    return h('div', {}, h('div', { class: 'pagehead' }, h('div', {}, h('div', { class: 'kicker' }, 'Documents'), h('h1', {}, 'Written down, so nobody has to be awake'),
      h('p', {}, 'Decisions, plans and how-tos. Every save is a version; parallel saves are combined line by line.')),
      S.me.guest ? null : h('button', { class: 'btn red', onclick: () => newDoc(null) }, '+ New document')),
      list.length ? h('div', { class: 'doclist' }, list.map(docItem)) : empty('No documents yet.'));
  }

  async function pageDoc(id, mode, q) {
    const d = await api('GET', '/api/docs/' + id);
    const versions = h('div', { class: 'versions' }, d.versions.map((v, i) => h('button', {
      class: mode === 'diff' && +q.to === v.v ? 'on' : '',
      onclick: () => go('#/doc/' + d.id + '/diff?from=' + (d.versions[i + 1] ? d.versions[i + 1].v : v.v) + '&to=' + v.v)
    }, h('span', { class: 'mono' }, 'v' + v.v), h('span', {}, h('b', {}, v.by), ' · ' + when(v.at), v.note ? h('div', { class: 'muted small' }, v.note) : null))));
    const side = h('aside', { class: 'stack' },
      card('Versions', h('span', { class: 'muted' }, d.versions.length), versions),
      d.editing.length ? card('Editing now', null, h('div', { class: 'editingnow' }, '✎ ' + d.editing.join(', '))) : null);
    const head = h('div', { class: 'row between', style: { marginBottom: '12px' } }, h('a', { href: '#/project/' + d.project.id + '/docs' }, '← ' + d.project.name + ' documents'),
      h('div', { class: 'row' }, mode === 'edit' ? null : d.canEdit ? h('a', { class: 'btn red', href: '#/doc/' + d.id + '/edit' }, '✎ Edit') : null));

    if (mode === 'diff') {
      const from = +q.from, to = +q.to;
      const df = await api('GET', '/api/docs/' + d.id + '/diff?from=' + from + '&to=' + to);
      return h('div', {}, head, h('div', { class: 'docpage' }, h('div', { class: 'card' },
        h('div', { class: 'row between' }, h('h2', {}, 'Changes v' + from + ' → v' + to), h('div', { class: 'row' }, tag('+' + df.added, 'green'), tag('−' + df.removed, 'red'),
          d.canEdit && to !== d.version ? h('button', { class: 'btn-ghost btn small', onclick: async () => { try { await api('POST', '/api/docs/' + d.id + '/restore', { version: to }); toast('Version ' + to + ' is the newest again. Nothing was lost.'); go('#/doc/' + d.id); } catch (e) { fail(e); } } }, 'Restore v' + to) : null)),
        h('div', { class: 'diff', style: { marginTop: '10px' } }, df.lines.map(l => h('div', { class: l.op }, l.text || ' ')))), side));
    }

    if (mode === 'edit' && d.canEdit) {
      let base = d.version;
      const title = h('input', { type: 'text', class: 'titlein', value: d.title, 'aria-label': 'Title' });
      const body = h('textarea', { 'aria-label': 'Text' }); body.value = d.body;
      const note = h('input', { type: 'text', placeholder: 'What did you change? (optional)', 'aria-label': 'Change note' });
      const status = h('div', { class: 'muted small' }, 'Editing version ' + base + '. Others will see that you are editing.');
      const others = h('div', { class: 'editingnow' }, d.editing.length ? '✎ ' + d.editing.join(', ') + ' editing too – your saves will be combined.' : '');
      async function beat(stop) {
        try { const r = await api('POST', '/api/docs/' + d.id + '/editing', { stop: !!stop }); others.textContent = r.editing.length ? '✎ ' + r.editing.join(', ') + ' editing too – your saves will be combined.' : ''; } catch (e) { /* fine */ }
      }
      const timer = setInterval(() => { if (!document.body.contains(body)) { clearInterval(timer); beat(true); return; } beat(false); }, 20000);
      beat(false);
      async function save() {
        try {
          const r = await api('PUT', '/api/docs/' + d.id, { baseVersion: base, title: title.value, body: body.value, note: note.value });
          clearInterval(timer); beat(true);
          toast(r.merged ? 'Saved – combined with ' + r.with + '\'s changes.' : 'Saved as version ' + r.doc.version + '.');
          go('#/doc/' + d.id);
        } catch (e) {
          if (e.status === 409 && e.data && e.data.merged != null) {
            // both changed the same lines: put the text with markers in the editor and let the person choose
            base = e.data.version;
            body.value = e.data.merged;
            status.textContent = 'Now based on version ' + base + ' (by ' + e.data.by + ').';
            modal('You both changed the same lines', h('div', {}, h('p', {}, e.message),
              h('p', {}, 'In the editor you now see both versions between the markers:'),
              h('pre', { class: 'diff', style: { padding: '8px' } }, '<<<<<<< your version\n(your lines)\n=======\n(their lines)\n>>>>>>> ' + e.data.by + '\'s version'),
              h('p', { class: 'muted' }, 'Keep what is right, delete the marker lines, then press Save again.')), close => [h('button', { class: 'btn red', onclick: close }, 'OK, I will fix it')]);
          } else fail(e);
        }
      }
      S.view.onEvent = e => {
        if (e.type === 'doc' && e.data.id === d.id && e.data.by !== S.me.id) { toast('Someone saved version ' + e.data.version + '. Your save will be combined with it.'); return true; }
        return e.type === 'doc';
      };
      return h('div', {}, head, h('div', { class: 'docpage' }, h('div', { class: 'card editor stack' }, title, others, body, h('div', { class: 'row' }, h('div', { class: 'grow' }, note),
        h('a', { class: 'btn-ghost btn', href: '#/doc/' + d.id, onclick: () => { clearInterval(timer); beat(true); } }, 'Cancel'), h('button', { class: 'btn red', onclick: save }, 'Save')), status), side));
    }

    S.view.onEvent = e => { if (e.type === 'doc' && e.data.id === d.id) { softRefresh(); return true; } return false; };
    return h('div', {}, head, h('div', { class: 'docpage' }, h('article', { class: 'card envelope' },
      h('div', { class: 'muted small' }, d.project.name + ' · v' + d.version + ' · ' + d.updatedBy + ', ' + when(d.updatedAt)),
      h('h1', { style: { margin: '6px 0 14px' } }, d.title), h('div', { class: 'docread' }, rich(d.body))), side));
  }

  // ------------------------------------------------------------------ search
  async function pageSearch(q) {
    const r = await api('GET', '/api/search?q=' + encodeURIComponent(q || ''));
    return h('div', {}, h('div', { class: 'pagehead' }, h('div', {}, h('div', { class: 'kicker' }, 'Search'), h('h1', {}, '“' + q + '”'))),
      h('div', { class: 'cols' },
        h('div', { class: 'span6' }, card('Messages', h('span', { class: 'muted' }, r.messages.length), r.messages.length ? h('div', { class: 'list' }, r.messages.map(m => h('a', { href: '#/channel/' + m.channelId + '?m=' + m.message.id, style: { color: 'var(--ink)' } },
          h('div', { class: 'where small muted' }, m.message.author.name + ' in ' + m.channel + ' · ' + when(m.message.at)), h('div', {}, m.snippet)))) : empty('No messages.'))),
        h('div', { class: 'span6 stack' },
          card('Tasks', h('span', { class: 'muted' }, r.tasks.length), r.tasks.length ? h('div', { class: 'list' }, r.tasks.map(miniCard)) : empty('No tasks.')),
          card('Documents', h('span', { class: 'muted' }, r.docs.length), r.docs.length ? h('div', { class: 'list' }, r.docs.map(x => h('a', { href: '#/doc/' + x.id, style: { color: 'var(--ink)' } }, h('b', {}, x.title), h('div', { class: 'muted small' }, x.project), h('div', { class: 'small' }, x.snippet)))) : empty('No documents.')))));
  }

  // ------------------------------------------------------------------ report
  async function pageReport(id) {
    const r = await api('GET', '/api/projects/' + id + '/report');
    const p = r.project;
    const maxW = Math.max(1, ...r.flow.weeks.map(w => Math.max(w.done, w.started)));
    const stat = (v, label) => h('div', { class: 'stat' }, h('b', {}, v), h('span', {}, label));
    const proj = S.meta.projects.find(x => x.id === id) || { id, key: p.key, channelId: '' };
    return h('div', {},
      h('div', { class: 'pagehead' }, h('div', {}, h('div', { class: 'kicker' }, 'Report · ' + p.key), h('h1', {}, p.name), h('p', {}, 'How the remote team works: the flow of tasks, how fast people answer across time zones, and who pays for calls. Last 28 days.'))),
      projectTabs(Object.assign({}, proj, { id, key: p.key }), 'report'),
      h('h2', { style: { margin: '6px 0 10px' } }, 'Flow'),
      h('div', { class: 'stats' }, stat(r.flow.cycleMedianHours + ' h', 'median time Doing → Done'), stat(r.flow.cycleP85Hours + ' h', '85% of tasks finish within'),
        stat(r.flow.columns[1] + (r.flow.limits[1] ? ' / ' + r.flow.limits[1] : ''), 'in Doing (limit)'), stat(r.flow.columns[2] + (r.flow.limits[2] ? ' / ' + r.flow.limits[2] : ''), 'in Review (limit)'),
        stat(r.flow.overdue, 'overdue'), stat(r.flow.blocked, 'on hold (waiting for other tasks)')),
      h('div', { class: 'card', style: { marginTop: '14px' } }, h('h3', {}, 'Tasks per week', h('span', { class: 'muted' }, 'dark = finished, light = started')),
        h('div', { class: 'bars' }, r.flow.weeks.map(w => h('div', { class: 'b' }, h('div', { class: 'pair' }, h('i', { style: { height: (w.done / maxW * 100) + '%' }, title: w.done + ' done' }), h('i', { class: 's', style: { height: (w.started / maxW * 100) + '%' }, title: w.started + ' started' })),
          h('span', {}, w.week.slice(5)), h('span', { class: 'mono' }, w.done + '/' + w.started))))),
      h('h2', { style: { margin: '22px 0 10px' } }, 'Talking across time zones'),
      h('div', { class: 'stats' }, stat(r.talk.mentions, '@mentions in #' + p.key.toLowerCase()), stat(r.talk.offHoursShare + '%', 'sent while the person was off work'),
        stat(r.talk.wallMedianHours + ' h', 'median time to an answer (clock time)'), stat(r.talk.inHoursMedianHours + ' h', 'median time to an answer (their working hours only)'),
        stat(r.talk.unanswered, 'never answered'), stat(r.handoffs.count + ' / ' + r.handoffs.acked, 'handoffs sent / picked up'), stat(r.handoffs.ackMedianHours + ' h', 'median time to pick up a handoff')),
      h('p', { class: 'muted small', style: { marginTop: '8px' } }, 'A long clock-time answer with a short working-hours answer is healthy: people answer as soon as their day starts – they are not slow, they are asleep.'),
      h('h2', { style: { margin: '22px 0 10px' } }, 'Time and calls'),
      h('div', { class: 'stats' }, stat(r.time.coverageHours + ' h', 'of 24 someone is working'), stat(r.time.togetherHours + ' h', 'when all ' + r.time.teamSize + ' overlap'),
        stat(r.meetings.count, 'calls in 28 days'), stat(r.meetings.outsideShare + '%', 'of call seats outside working hours')),
      h('div', { class: 'card', style: { marginTop: '14px' } }, h('h3', {}, 'People'),
        h('table', { class: 'plain' }, h('tr', {}, ['Person', 'Doing now', 'Done (28 d)', 'Stand-ups', 'Answer time (work h)', 'Call pain'].map(x => h('th', {}, x))),
          r.people.map(x => h('tr', {}, h('td', {}, personLine(x.user, x.city)), h('td', { class: 'mono' }, x.doing), h('td', { class: 'mono' }, x.done),
            h('td', {}, h('div', { class: 'row', style: { flexWrap: 'nowrap' } }, h('div', { class: 'painbar grow', style: { maxWidth: '120px' } }, h('i', { style: { width: x.standups + '%', background: 'var(--green)' } })), h('span', { class: 'mono small' }, x.standups + '%'))),
            h('td', { class: 'mono' }, x.reply ? x.reply + ' h' : '—'), h('td', { class: 'mono' }, x.pain))))));
  }

  // ------------------------------------------------------------------ settings
  async function pageSettings() {
    const me = S.me;
    const zone = h('select', { 'aria-label': 'Time zone' }, S.meta.zones.concat(S.meta.zones.indexOf(me.zone) < 0 ? [me.zone] : []).map(z => h('option', { value: z, selected: z === me.zone }, z.replace('_', ' '))));
    const start = h('input', { type: 'time', value: me.hours.split('–')[0], step: 900 });
    const end = h('input', { type: 'time', value: me.hours.split('–')[1], step: 900 });
    const days = h('div', { class: 'daypick' }, DAYS.map((d, i) => h('label', {}, h('input', { type: 'checkbox', value: i + 1, checked: me.workDays.indexOf(i + 1) >= 0 }), h('span', {}, d.slice(0, 2)))));
    const title = h('input', { type: 'text', value: me.title || '' });
    const city = h('input', { type: 'text', value: me.city || '' });
    const status = h('input', { type: 'text', value: me.statusText || '', placeholder: 'e.g. On holiday until Monday' });
    const err = h('div', { class: 'err' });
    const preview = h('p', { class: 'muted small' });
    function updatePreview() { preview.textContent = 'Right now it is ' + hm(Date.now(), zone.value) + ' in ' + zone.value.split('/').pop().replace('_', ' ') + '.'; }
    zone.addEventListener('change', updatePreview); updatePreview();
    const cur = h('input', { type: 'password', autocomplete: 'current-password' }), nxt = h('input', { type: 'password', autocomplete: 'new-password' });
    const perr = h('div', { class: 'err' });
    return h('div', {}, h('div', { class: 'pagehead' }, h('div', {}, h('div', { class: 'kicker' }, 'Settings'), h('h1', {}, 'Your time and place'),
      h('p', {}, 'Sangam uses this for quiet hours (messages wait while you sleep), fair call times, stand-up dates and handoffs.'))),
      h('div', { class: 'cols' },
        h('div', { class: 'span7' }, card('Working hours', null, h('div', { class: 'stack' },
          h('label', {}, 'Time zone', zone, preview),
          h('div', { class: 'grid2' }, h('label', {}, 'Day starts', start), h('label', {}, 'Day ends', end)),
          h('label', {}, 'Working days', days),
          h('div', { class: 'grid2' }, h('label', {}, 'Job title', title), h('label', {}, 'City', city)),
          h('label', {}, 'Status', status), err,
          h('button', { class: 'btn red', onclick: async () => {
            err.textContent = '';
            try {
              const wd = [...days.querySelectorAll('input:checked')].map(i => +i.value);
              S.me = Object.assign(S.me, await api('PUT', '/api/me', { zone: zone.value, workStart: start.value, workEnd: end.value, workDays: wd, title: title.value, city: city.value, statusText: status.value }));
              await refreshMeta(); drawRack(); drawTopbar(); toast('Saved. Your colleagues now see your new hours.');
            } catch (e) { err.textContent = e.message; }
          } }, 'Save')))),
        h('div', { class: 'span5' }, card('Password', null, h('div', { class: 'stack' }, h('label', {}, 'Current password', cur), h('label', {}, 'New password (8+ characters, a letter and a number)', nxt), perr,
          h('button', { class: 'btn', onclick: async () => {
            perr.textContent = '';
            try { await api('POST', '/api/me/password', { current: cur.value, next: nxt.value }); toast('Password changed. Please sign in again.'); S.me = null; draw(); } catch (e) { perr.textContent = e.message; }
          } }, 'Change password'))))));
  }

  // ------------------------------------------------------------------ admin desk
  async function pageAdmin() {
    if (!S.me.admin) return h('div', { class: 'card' }, 'Only the workspace admin can see this page.');
    const [audit, projects] = await Promise.all([api('GET', '/api/admin/audit'), api('GET', '/api/projects')]);
    const people = await api('GET', '/api/people');
    const boards = await Promise.all(projects.map(p => api('GET', '/api/projects/' + p.id)));
    const projectCards = boards.map(b => {
      const p = b.project;
      const picks = h('div', { class: 'chips' }, people.map(x => chipToggle(x, p.memberIds.indexOf(x.id) >= 0)));
      const wd = h('input', { type: 'number', min: 0, max: 30, value: p.wipDoing });
      const wr = h('input', { type: 'number', min: 0, max: 30, value: p.wipReview });
      const rr = h('input', { type: 'checkbox', checked: p.reviewRequired });
      return card(p.name, tag(p.key), h('div', { class: 'stack' }, h('label', {}, 'Members', picks),
        h('div', { class: 'grid3' }, h('label', {}, 'Doing limit (0 = none)', wd), h('label', {}, 'Review limit', wr), h('label', { class: 'check', style: { alignSelf: 'end' } }, rr, 'Review needed')),
        h('button', { class: 'btn small', onclick: async () => {
          try {
            await api('PUT', '/api/projects/' + p.id, { memberIds: [...picks.querySelectorAll('input:checked')].map(i => i.value), wipDoing: +wd.value, wipReview: +wr.value, reviewRequired: rr.checked });
            toast('Saved.'); await refreshMeta(); await refreshChannels();
          } catch (e) { fail(e); }
        } }, 'Save')));
    });
    function addPerson() {
      formModal('Add a teammate', [
        { name: 'name', label: 'Name' }, { name: 'username', label: 'Username', placeholder: 'yuki' },
        { name: 'password', label: 'First password (they change it)', type: 'text' },
        { name: 'role', label: 'Role', type: 'select', value: 'MEMBER', options: [['MEMBER', 'Member'], ['GUEST', 'Guest (client – sees only their projects)'], ['ADMIN', 'Admin']] },
        { name: 'zone', label: 'Time zone', type: 'select', value: 'Asia/Tokyo', options: S.meta.zones.map(z => [z, z]) },
        { name: 'workStart', label: 'Day starts', type: 'time', value: '09:00' }, { name: 'workEnd', label: 'Day ends', type: 'time', value: '17:00' },
        { name: 'city', label: 'City' }, { name: 'title', label: 'Job title' },
      ], 'Add', async v => { await api('POST', '/api/admin/people', v); toast('Added. Now put them in a project.'); await refreshMeta(); S.view.refresh(); });
    }
    return h('div', {}, h('div', { class: 'pagehead' }, h('div', {}, h('div', { class: 'kicker' }, 'Admin desk'), h('h1', {}, 'Workspace'), h('p', {}, people.length + ' people in ' + new Set(people.map(p => p.city)).size + ' cities.')),
      h('div', { class: 'row' }, h('button', { class: 'btn', onclick: addPerson }, '+ Add a teammate'), h('button', { class: 'btn red', onclick: newProject }, '+ New project'))),
      h('div', { class: 'cols' }, h('div', { class: 'span6 stack' }, projectCards),
        h('div', { class: 'span6' }, card('Activity log', h('span', { class: 'muted' }, 'newest first'),
          h('div', { style: { maxHeight: '70vh', overflow: 'auto' } }, h('table', { class: 'plain' }, h('tr', {}, h('th', {}, 'When'), h('th', {}, 'Who'), h('th', {}, 'What')),
            audit.map(a => h('tr', {}, h('td', { class: 'mono small nowrap' }, when(a.at)), h('td', { class: 'small' }, a.who), h('td', { class: 'small' }, h('b', {}, a.action), ' ', a.target || '', a.detail ? h('span', { class: 'muted' }, ' – ' + a.detail) : null)))))))));
  }

  // ------------------------------------------------------------------ start
  window.addEventListener('hashchange', route);
  document.addEventListener('visibilitychange', () => { if (!document.hidden && S.view && S.view.stale) { S.view.stale = false; S.view.refresh(); } });
  document.addEventListener('click', e => { const d = $('.drawer'); if (d && !d.contains(e.target) && !e.target.closest('.bell')) closeDrawer(); });
  (async function boot() {
    try {
      const r = await api('GET', '/api/me');
      if (r.user) { await start(r.user, r.meta); return; }
    } catch (e) { /* not signed in */ }
    draw();
  })();
})();
