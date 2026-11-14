/* Kaksh – Meeting Room Booking. The website. Plain JavaScript, no libraries.
   Every page is built with h() (document.createElement + textContent), never innerHTML, so text that people type
   can never run as code (no XSS). The API is under /api; every change sends the X-Kaksh header (CSRF). */
(function () {
  'use strict';

  const state = { me: null, meta: null, unread: 0 };
  const app = document.getElementById('app');
  const modalRoot = document.getElementById('modal-root');
  const SVGNS = 'http://www.w3.org/2000/svg';
  let refreshTimer = null;

  // ------------------------------------------------------------------ building blocks

  /** h('div', {class: 'x', onclick: fn}, 'text', child, [more children]) */
  function h(tag, props, ...kids) {
    const el = document.createElement(tag);
    setProps(el, props);
    add(el, kids);
    return el;
  }

  function s(tag, attrs, ...kids) {
    const el = document.createElementNS(SVGNS, tag);
    if (attrs) for (const [k, v] of Object.entries(attrs)) if (v !== null && v !== undefined && v !== false) el.setAttribute(k, String(v));
    add(el, kids);
    return el;
  }

  function setProps(el, props) {
    if (!props) return;
    for (const [k, v] of Object.entries(props)) {
      if (v === null || v === undefined || v === false) continue;
      if (k === 'class') el.className = v;
      else if (k === 'text') el.textContent = v;
      else if (k === 'style') for (const [sk, sv] of Object.entries(v)) el.style.setProperty(sk, sv);
      else if (k.startsWith('on') && typeof v === 'function') el.addEventListener(k.slice(2), v);
      else if (k === 'value') el.value = v;
      else if (k === 'checked' || k === 'disabled' || k === 'selected' || k === 'required') el[k] = !!v;
      else el.setAttribute(k, v === true ? '' : String(v));
    }
  }

  function add(el, kids) {
    for (const k of kids.flat(Infinity)) {
      if (k === null || k === undefined || k === false) continue;
      el.appendChild(k instanceof Node ? k : document.createTextNode(String(k)));
    }
  }

  function clear(el) { while (el.firstChild) el.removeChild(el.firstChild); return el; }

  // ------------------------------------------------------------------ talking to the server

  class ApiError extends Error {
    constructor(message, status, data) { super(message); this.status = status; this.data = data; }
  }

  async function api(path, method, body) {
    const opts = { method: method || 'GET', headers: {}, credentials: 'same-origin' };
    if (method && method !== 'GET') {
      opts.headers['Content-Type'] = 'application/json';
      opts.headers['X-Kaksh'] = '1';
      opts.body = JSON.stringify(body || {});
    }
    let res;
    try { res = await fetch(path, opts); } catch (e) { throw new ApiError('Cannot reach the server. Is it still running?', 0); }
    let data = null;
    try { data = await res.json(); } catch (e) { /* empty answer */ }
    if (res.status === 401 && path !== '/api/login') {
      state.me = null;
      route();
      throw new ApiError('Please sign in again.', 401);
    }
    if (!res.ok) throw new ApiError((data && data.error) || ('Error ' + res.status), res.status, data && data.data);
    return data;
  }

  const post = (path, body) => api(path, 'POST', body || {});
  const put = (path, body) => api(path, 'PUT', body || {});

  /** Runs an action from a button: shows errors as a toast. Returns the answer, or undefined on error. */
  async function act(btn, fn, okText) {
    if (btn) btn.disabled = true;
    try {
      const r = await fn();
      if (okText) toast(okText);
      return r;
    } catch (e) {
      toast(e.message, true);
      return undefined;
    } finally {
      if (btn) btn.disabled = false;
    }
  }

  let toastTimer = null;
  function toast(text, bad) {
    const t = document.getElementById('toast');
    t.textContent = text;
    t.className = 'toast' + (bad ? ' bad' : '');
    t.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => { t.hidden = true; }, bad ? 5200 : 3000);
  }

  function modal(title, body, actions) {
    clear(modalRoot);
    const close = () => clear(modalRoot);
    const box = h('div', { class: 'modal', role: 'dialog', 'aria-modal': 'true', 'aria-label': title },
      h('h2', { text: title }), body, actions ? h('div', { class: 'actions' }, actions) : null);
    const back = h('div', { class: 'modal-back', onclick: (e) => { if (e.target === back) close(); } }, box);
    modalRoot.appendChild(back);
    const first = box.querySelector('input, select, textarea, button');
    if (first) first.focus();
    back.addEventListener('keydown', (e) => { if (e.key === 'Escape') close(); });
    return close;
  }

  function confirmBox(title, text, okLabel, onOk, withReason) {
    const reason = withReason ? h('textarea', { placeholder: withReason, maxlength: 160, rows: 2 }) : null;
    let close;
    const ok = h('button', { class: 'btn primary', text: okLabel, onclick: async () => {
      const r = await act(ok, () => onOk(reason ? reason.value : undefined));
      if (r !== undefined) close();
    } });
    close = modal(title, h('div', null, h('p', { text }), reason), [h('button', { class: 'btn ghost', text: 'Back', onclick: () => close() }), ok]);
  }

  // ------------------------------------------------------------------ time (always India time)

  const TZ = 'Asia/Kolkata';
  const MIN = 60000;
  const fmt = (ms, o) => new Intl.DateTimeFormat('en-IN', Object.assign({ timeZone: TZ }, o)).format(new Date(ms));
  const time = (ms) => fmt(ms, { hour: '2-digit', minute: '2-digit', hourCycle: 'h23' });
  const dayLong = (ms) => fmt(ms, { weekday: 'long', day: 'numeric', month: 'long' });
  const dayShort = (ms) => fmt(ms, { weekday: 'short', day: 'numeric', month: 'short' });
  const span = (a, b) => time(a) + '–' + time(b);
  const when = (a, b) => dayShort(a) + ', ' + span(a, b);
  /** "2026-11-16" for a moment, in India time. */
  const isoDay = (ms) => fmt(ms, { year: 'numeric', month: '2-digit', day: '2-digit' }).split('/').reverse().join('-');
  /** The moment for a date (yyyy-mm-dd) and a time (hh:mm), India time. */
  const at = (day, hm) => Date.parse(day + 'T' + hm + ':00+05:30');
  const addDays = (day, n) => isoDay(at(day, '12:00') + n * 86400000);
  const hm = (ms) => time(ms);
  const mins = (n) => n < 60 ? n + ' min' : (n % 60 === 0 ? (n / 60) + ' h' : Math.floor(n / 60) + ' h ' + (n % 60) + ' min');

  function untilText(ms) {
    const d = Math.round((ms - Date.now()) / MIN);
    if (d <= 0) return 'now';
    if (d < 60) return 'in ' + d + ' min';
    return 'at ' + time(ms);
  }

  function ago(ms) {
    const d = Math.round((Date.now() - ms) / MIN);
    if (d < 1) return 'just now';
    if (d < 60) return d + ' min ago';
    if (d < 1440) return Math.round(d / 60) + ' h ago';
    return dayShort(ms);
  }

  // ------------------------------------------------------------------ small pieces

  const EQUIP = {
    SCREEN: 'Screen', PROJECTOR: 'Projector', VIDEO_CALL: 'Video call', WHITEBOARD: 'Whiteboard',
    SPEAKERPHONE: 'Speakerphone', WHEELCHAIR: 'Step-free'
  };

  const STATUS = {
    PENDING: 'Waiting for approval', CONFIRMED: 'Booked', CHECKED_IN: 'In use', COMPLETED: 'Done',
    CANCELLED: 'Cancelled', DECLINED: 'Not approved', RELEASED: 'No-show, given back', EXPIRED: 'Not approved in time'
  };

  function icon(name) {
    const paths = {
      desk: 'M3 10h18M5 10v9M19 10v9M8 6h8l1 4H7z',
      register: 'M5 4h12a2 2 0 0 1 2 2v14H7a2 2 0 0 1-2-2zM9 8h6M9 12h6M9 16h4',
      plus: 'M12 5v14M5 12h14',
      people: 'M8 11a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM2 20c0-3 3-5 6-5s6 2 6 5M16 11a3 3 0 1 0 0-6M18 15c2 .5 4 2 4 5',
      key: 'M14 10a4 4 0 1 1-1.2-2.8L21 15v3h-3v-2h-2v-2h-2z',
      bell: 'M6 16V11a6 6 0 1 1 12 0v5l2 2H4zM10 20a2 2 0 0 0 4 0',
      check: 'M5 12l5 5 9-10',
      chart: 'M4 20V10M10 20V4M16 20v-7M22 20H2',
      tool: 'M14 6a4 4 0 0 0 5 5l-9 9-3-3 9-9a4 4 0 0 1-2-2z',
      list: 'M8 6h12M8 12h12M8 18h12M4 6h.01M4 12h.01M4 18h.01',
      out: 'M15 4h4v16h-4M10 8l-4 4 4 4M6 12h10',
      moon: 'M20 15a8 8 0 1 1-11-11 7 7 0 0 0 11 11z',
      clock: 'M12 7v5l3 2M12 21a9 9 0 1 1 0-18 9 9 0 0 1 0 18z'
    };
    return s('svg', { class: 'ic', viewBox: '0 0 24 24', 'aria-hidden': 'true' }, s('path', { d: paths[name] || '' }));
  }

  function chip(text, cls) { return h('span', { class: 'chip ' + (cls || ''), text }); }
  function empty(text, extra) { return h('div', { class: 'empty' }, h('p', { text }), extra || null); }
  function field(label, input, hint) { return h('label', { class: 'field' }, h('span', { class: 'lab', text: label }), input, hint ? h('small', { text: hint }) : null); }

  function select(options, value, props) {
    const el = h('select', props);
    for (const o of options) {
      const [v, t] = Array.isArray(o) ? o : [o, o];
      el.appendChild(h('option', { value: v, text: t, selected: String(v) === String(value) }));
    }
    return el;
  }

  function statusChip(st) { return chip(STATUS[st] || st, 'st-' + st.toLowerCase()); }

  function roomById(id) { return (state.meta.rooms || []).find((r) => r.id === id); }
  function personName(id) { const p = state.meta.people.find((x) => x.id === id); return p ? p.name : '—'; }

  function initials(name) {
    const parts = String(name || '?').split(/\s+/).filter(Boolean);
    return ((parts[0] || '?')[0] + (parts.length > 1 ? parts[parts.length - 1][0] : '')).toUpperCase();
  }

  /** The brass key fob: room number engraved, name below. */
  function fob(room, small) {
    return h('span', { class: 'fob' + (small ? ' small' : '') }, h('span', { class: 'fob-ring' }),
      h('span', { class: 'fob-body' }, h('span', { class: 'fob-no', text: room.code }), small ? null : h('span', { class: 'fob-name', text: String(room.name).split(' ')[0] })));
  }

  function equipList(list) { return h('span', { class: 'equip' }, list.map((e) => chip(EQUIP[e] || e, 'eq'))); }

  // ------------------------------------------------------------------ theme

  function themeNow() {
    const set = document.documentElement.getAttribute('data-theme');
    if (set) return set;
    return window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
  }

  function toggleTheme() {
    const next = themeNow() === 'dark' ? 'light' : 'dark';
    document.documentElement.setAttribute('data-theme', next);
    try { localStorage.setItem('kaksh-theme', next); } catch (e) { /* storage blocked */ }
    route();
  }

  // ------------------------------------------------------------------ the frame: key cabinet menu + page

  function shell(view, active) {
    const me = state.me;
    const links = [
      ['', 'Front desk', 'desk'],
      ['board', 'Day register', 'register'],
      ['book', 'Book a room', 'plus'],
      ['find', 'Find a time', 'people'],
      ['mine', 'My bookings', 'key'],
      ['notes', 'Messages', 'bell']
    ];
    if (me.admin) links.push(['admin', 'Facility desk', 'tool']);
    const nav = h('nav', { class: 'cabinet', 'aria-label': 'Main' },
      h('a', { class: 'plate', href: '#' },
        h('span', { class: 'plate-mark', text: 'क' }),
        h('span', null, h('b', { text: 'Kaksh' }), h('small', { text: 'Nimbus Works, Ranchi' }))),
      h('ul', null, links.map(([href, label, ic]) => h('li', null,
        h('a', { href: '#' + href, class: active === href ? 'on' : null, 'aria-current': active === href ? 'page' : null },
          icon(ic), h('span', { text: label }),
          href === 'notes' && state.unread > 0 ? h('span', { class: 'count', text: String(state.unread) }) : null)))),
      h('div', { class: 'cabinet-foot' },
        h('div', { class: 'who' }, h('span', { class: 'mono-av', text: initials(me.name) }),
          h('span', null, h('b', { text: me.name }), h('small', { text: me.admin ? 'Facility manager' : me.team + ', floor ' + me.floor }))),
        h('div', { class: 'foot-btns' },
          h('button', { class: 'btn link', onclick: toggleTheme, title: 'Switch day / night' }, icon('moon'), themeNow() === 'dark' ? 'Day desk' : 'Night porter'),
          h('button', { class: 'btn link', onclick: logout }, icon('out'), 'Sign out'))));
    clear(app);
    app.appendChild(h('div', { class: 'frame' }, nav, h('main', { id: 'view', tabindex: '-1' }, view)));
  }

  async function logout() {
    await act(null, () => post('/api/logout'));
    state.me = null;
    location.hash = '';
    route();
  }

  function page(title, sub, ...kids) {
    return h('div', { class: 'page' }, h('header', { class: 'page-head' }, h('h1', { text: title }), sub ? h('p', { class: 'sub', text: sub }) : null), kids);
  }

  // ------------------------------------------------------------------ sign in

  function signIn() {
    clear(app);
    const user = h('input', { autocomplete: 'username', required: true, value: '' });
    const pass = h('input', { type: 'password', autocomplete: 'current-password', required: true });
    const btn = h('button', { class: 'btn primary wide', type: 'submit', text: 'Sign in' });
    const form = h('form', { class: 'signin-form', onsubmit: async (e) => {
      e.preventDefault();
      const r = await act(btn, () => post('/api/login', { username: user.value, password: pass.value }));
      if (r) { state.me = r.user; state.meta = r.meta; route(); }
    } },
      h('h1', { text: 'Front desk' }),
      h('p', { class: 'sub', text: 'Sign in to book a meeting room.' }),
      field('Username', user), field('Password', pass), btn,
      h('details', { class: 'demo' }, h('summary', { text: 'Demo accounts' }),
        h('table', null, h('tbody', null,
          [['priya', 'member123', 'Engineering lead, a meeting about to start'], ['arjun', 'member123', 'Sales, a request for the boardroom'],
            ['neha', 'member123', 'Design'], ['admin', 'admin123', 'Facility manager']].map(([u, p, w]) =>
            h('tr', null, h('td', null, h('button', { type: 'button', class: 'btn link', text: u, onclick: () => { user.value = u; pass.value = p; pass.focus(); } })),
              h('td', { text: p }), h('td', { text: w })))))));
    // a small decorative key rack beside the form
    const rack = h('div', { class: 'signin-rack', 'aria-hidden': 'true' },
      ['1.01', '1.02', '1.03', '1.04', '2.01', '2.02', '2.03', '2.04', '3.01', '3.02', '3.03', '3.04'].map((c, i) =>
        h('div', { class: 'hole' + ([2, 5, 7, 10].includes(i) ? ' taken' : '') }, h('span', { class: 'hook' }),
          [2, 5, 7, 10].includes(i) ? h('span', { class: 'slip' }) : fob({ code: c, name: '' }, true))));
    app.appendChild(h('div', { class: 'signin' },
      h('section', { class: 'signin-side' },
        h('div', { class: 'plate big' }, h('span', { class: 'plate-mark', text: 'क' }), h('span', null, h('b', { text: 'Kaksh' }), h('small', { text: 'Meeting rooms, Nimbus Works' }))),
        rack,
        h('p', { class: 'signin-note', text: 'A key on its hook means the room is free. No key: someone has it. One room, one meeting at a time – Kaksh checks every booking before it is saved.' })),
      h('section', { class: 'signin-main' }, form)));
    user.focus();
  }

  // ------------------------------------------------------------------ front desk (home)

  async function homePage() {
    const d = await api('/api/home');
    state.unread = d.unread;
    const rack = h('div', { class: 'rack' }, d.rack.map((x) => rackHole(x)));
    const free = d.rack.filter((x) => x.state === 'FREE').length;
    const nextList = d.upcoming.length ? h('div', { class: 'slips' }, d.upcoming.map((b) => slip(b))) :
      empty('Nothing booked for the next two weeks.', h('a', { class: 'btn primary', href: '#book', text: 'Book a room' }));
    const side = h('aside', { class: 'desk-side' },
      h('section', { class: 'card' }, h('h2', { text: 'Your next meetings' }), nextList,
        d.upcomingTotal > d.upcoming.length ? h('a', { href: '#mine', class: 'more', text: 'All ' + d.upcomingTotal + ' upcoming' }) : null),
      d.waiting.length ? h('section', { class: 'card' }, h('h2', { text: 'Waiting for a room' }),
        h('ul', { class: 'plain' }, d.waiting.map((w) => h('li', null, h('b', { text: w.roomName }), ' ', when(w.start, w.end),
          h('small', { text: ' – place ' + w.place + ' in the queue. You get it automatically if it frees up.' }))))) : null,
      h('section', { class: 'card quiet' }, h('h2', { text: 'This week' }), quotaBar(d.quota),
        h('p', { class: 'small', text: 'Clashes stopped so far: ' + d.stats.clashesStopped + '. Hours given back by no-shows and early ends: ' + Math.round(d.stats.minutesGivenBack / 60) + '.' })),
      d.admin ? h('section', { class: 'card admin-card' }, h('h2', { text: 'Facility desk' }),
        h('p', null, h('a', { href: '#admin', text: d.admin.pending + ' request' + (d.admin.pending === 1 ? '' : 's') + ' to approve' })),
        h('p', { class: 'small', text: d.admin.noShowToday + ' no-show' + (d.admin.noShowToday === 1 ? '' : 's') + ' today, ' + d.admin.closings + ' room closing' + (d.admin.closings === 1 ? '' : 's') + ' planned' })) : null);
    shell(page('Good ' + partOfDay() + ', ' + state.me.name.split(' ')[0], dayLong(d.now) + '. ' + free + ' of ' + d.rack.length + ' rooms free right now',
      h('div', { class: 'desk' }, h('section', { class: 'rack-wrap' }, h('h2', { class: 'vh', text: 'Key rack' }), rack,
        h('p', { class: 'legend' }, h('span', { class: 'lg free' }), 'Key on the hook: free', h('span', { class: 'lg busy' }), 'Slip on the hook: in use', h('span', { class: 'lg closed' }), 'Closed')), side)), '');
    schedule(homePage, 60000);
  }

  function partOfDay() {
    const hr = Number(fmt(Date.now(), { hour: 'numeric', hourCycle: 'h23' }));
    return hr < 12 ? 'morning' : hr < 17 ? 'afternoon' : 'evening';
  }

  function quotaBar(q) {
    if (q.exempt) return h('p', { class: 'small', text: 'No weekly limit for the facility manager.' });
    const pct = Math.min(100, Math.round(q.usedMin * 100 / q.limitMin));
    return h('div', { class: 'quota' }, h('div', { class: 'bar' }, h('i', { style: { width: pct + '%' } })),
      h('p', { class: 'small', text: mins(q.usedMin) + ' of your ' + (q.limitMin / 60) + ' h fair-use limit organised this week.' }));
  }

  function rackHole(x) {
    const r = x.room;
    const go = () => { location.hash = 'room/' + r.id; };
    let body;
    if (x.state === 'FREE' || x.state === 'AFTER_HOURS') {
      body = [fob(r), h('span', { class: 'hole-note', text: x.state === 'FREE' ? (x.until ? 'Free until ' + time(x.until) : 'Free') : 'Office closed' })];
    } else if (x.state === 'BUSY') {
      const b = x.booking;
      body = [h('span', { class: 'slip' }, h('b', { text: r.code + ' ' + r.name }), h('span', { text: b ? b.title : 'Booked' }),
        h('small', { text: (b && b.status === 'CHECKED_IN' ? 'In use' : 'Booked') + ' until ' + time(x.until) }))];
    } else {
      body = [h('span', { class: 'slip closed' }, h('b', { text: r.code + ' ' + r.name }), h('span', { text: x.state === 'OFF' ? 'Not bookable' : 'Closed' }),
        h('small', { text: x.reason || (x.until ? 'until ' + time(x.until) : '') }))];
    }
    return h('button', { class: 'hole s-' + x.state.toLowerCase(), onclick: go, 'aria-label': r.name + ', ' + (x.state === 'FREE' ? 'free' : x.state.toLowerCase()) },
      h('span', { class: 'hook' }), body,
      h('span', { class: 'hole-meta', text: r.capacity + ' seats' + (r.needsApproval ? ', approval' : '') }));
  }

  /** A meeting as a guest slip from the register. */
  function slip(b) {
    const actions = [];
    if (b.can.checkin) actions.push(h('button', { class: 'btn primary small', text: 'Check in', onclick: async (e) => {
      if (await act(e.currentTarget, () => post('/api/bookings/' + b.id + '/checkin'), 'Checked in. Enjoy the meeting.')) route();
    } }));
    if (b.can.end) actions.push(h('button', { class: 'btn small', text: 'End now', onclick: async (e) => {
      if (await act(e.currentTarget, () => post('/api/bookings/' + b.id + '/end'), 'Ended. The rest of the time is free for others.')) route();
    } }));
    return h('article', { class: 'slip-card st-' + b.status.toLowerCase() },
      h('div', { class: 'slip-time' }, h('b', { text: time(b.start) }), h('small', { text: dayShort(b.start) })),
      h('div', { class: 'slip-body' },
        h('a', { href: '#booking/' + b.id, class: 'slip-title', text: b.title }),
        h('span', { class: 'slip-room' }, b.roomCode + ' ' + b.roomName + ', until ' + time(b.end)),
        h('span', { class: 'slip-meta' }, b.status !== 'CONFIRMED' ? statusChip(b.status) : null,
          b.invited ? chip('Invited by ' + b.organiser.name, 'soft') : null,
          b.can.checkin ? chip('Check in by ' + time(b.checkinUntil), 'warn') : null)),
      actions.length ? h('div', { class: 'slip-act' }, actions) : null);
  }

  // ------------------------------------------------------------------ the day register (board)

  async function boardPage(params) {
    const day = params.date || isoDay(Date.now());
    const d = await api('/api/board?date=' + day);
    const hours = [];
    for (let t = d.from; t < d.to; t += 60 * MIN) hours.push(t);
    const total = d.to - d.from;
    const pct = (ms) => ((ms - d.from) / total * 100);
    const nav = h('div', { class: 'daynav' },
      h('a', { class: 'btn ghost', href: '#board?date=' + addDays(day, -1), text: 'Previous day' }),
      h('input', { type: 'date', value: day, 'aria-label': 'Day', onchange: (e) => { location.hash = 'board?date=' + e.target.value; } }),
      h('a', { class: 'btn ghost', href: '#board?date=' + isoDay(Date.now()), text: 'Today' }),
      h('a', { class: 'btn ghost', href: '#board?date=' + addDays(day, 1), text: 'Next day' }));
    let grid;
    if (!d.open) grid = empty('The office is closed on Sunday.');
    else {
      const head = h('div', { class: 'reg-row reg-head' }, h('div', { class: 'reg-room', text: 'Room' }),
        h('div', { class: 'reg-lane' }, hours.map((t) => h('span', { class: 'hr', style: { left: pct(t) + '%' }, text: time(t) })),
          d.now > d.from && d.now < d.to ? h('span', { class: 'now', style: { left: pct(d.now) + '%' }, title: 'Now, ' + time(d.now) }) : null));
      const rows = d.rows.map((row) => {
        const r = row.room;
        const lane = h('div', { class: 'reg-lane', title: 'Click an empty part to book ' + r.name, onclick: (e) => {
          if (e.target !== lane) return;
          const rect = lane.getBoundingClientRect();
          const t = d.from + Math.floor((e.clientX - rect.left) / rect.width * total / (15 * MIN)) * 15 * MIN;
          location.hash = 'book?room=' + r.id + '&start=' + t + '&end=' + (t + 60 * MIN);
        } });
        for (const t of hours) lane.appendChild(h('span', { class: 'gl', style: { left: pct(t) + '%' } }));
        for (const it of row.items) {
          const left = Math.max(0, pct(it.start)), right = Math.min(100, pct(it.end));
          const w = right - left;
          if (w <= 0) continue;
          if (it.kind === 'BLOCK') {
            lane.appendChild(h('span', { class: 'entry block', style: { left: left + '%', width: w + '%' }, title: 'Closed: ' + it.reason }, h('span', { text: 'Closed: ' + it.reason })));
          } else {
            const cls = 'entry ' + (it.kind === 'GHOST' ? 'ghost' : 'st-' + it.status.toLowerCase()) + (it.mine || it.invited ? ' me' : '');
            lane.appendChild(h('a', { class: cls, href: '#booking/' + it.id, style: { left: left + '%', width: w + '%' },
              title: it.title + ', ' + span(it.start, it.end) + ', ' + it.organiser.name + (it.kind === 'GHOST' ? ' (nobody came – given back)' : '') },
              h('b', { text: it.title }), h('small', { text: span(it.start, it.end) })));
            if (it.kind === 'BOOKING' && r.bufferMin > 0 && it.end < d.to) {
              const bl = pct(it.end), bw = Math.min(100, pct(it.end + r.bufferMin * MIN)) - bl;
              lane.appendChild(h('span', { class: 'buffer', style: { left: bl + '%', width: bw + '%' }, title: r.bufferMin + ' min to get the room ready' }));
            }
          }
        }
        if (d.now > d.from && d.now < d.to) lane.appendChild(h('span', { class: 'now', style: { left: pct(d.now) + '%' } }));
        return h('div', { class: 'reg-row' + (r.active ? '' : ' off') },
          h('a', { class: 'reg-room', href: '#room/' + r.id }, h('b', { text: r.code }), h('span', { text: r.name }), h('small', { text: r.capacity + ' seats' + (r.bufferMin ? ', ' + r.bufferMin + ' min buffer' : '') })),
          lane);
      });
      grid = h('div', { class: 'register', role: 'table', 'aria-label': 'Bookings on ' + day }, head, rows);
    }
    shell(page('Day register', dayLong(at(day, '12:00')) + '. Click an empty part of a row to book that room.',
      nav, grid,
      h('p', { class: 'legend' }, h('span', { class: 'lg mine' }), 'Yours', h('span', { class: 'lg booked' }), 'Booked', h('span', { class: 'lg pending' }), 'Waiting for approval',
        h('span', { class: 'lg ghost' }), 'No-show (given back)', h('span', { class: 'lg buf' }), 'Buffer (getting ready)', h('span', { class: 'lg closed' }), 'Closed')), 'board');
    if (day === isoDay(Date.now())) schedule(() => boardPage(params), 60000);
  }

  // ------------------------------------------------------------------ book / change a room

  function quarterOptions() {
    const out = [];
    for (let m = 8 * 60; m < 20 * 60; m += 15) out.push(String(Math.floor(m / 60)).padStart(2, '0') + ':' + String(m % 60).padStart(2, '0'));
    return out;
  }

  function defaultStart() {
    let t = Math.ceil((Date.now() + 10 * MIN) / (15 * MIN)) * 15 * MIN;
    const day = isoDay(t);
    if (t >= at(day, '19:00') || new Date(t).getUTCDay() === 0) t = at(addDays(day, 1), '10:00');
    if (t < at(isoDay(t), '08:00')) t = at(isoDay(t), '10:00');
    if (new Date(at(isoDay(t), '12:00')).getUTCDay() === 0) t = at(addDays(isoDay(t), 1), '10:00');
    return t;
  }

  /** A picker for colleagues: type a name, pick from the list; chosen people show as removable chips. */
  function peoplePicker(chosen, onChange) {
    const box = h('div', { class: 'picker' });
    const list = h('div', { class: 'chips' });
    const input = h('input', { placeholder: 'Type a name…', 'aria-label': 'Invite people', list: 'people-list' });
    const dl = h('datalist', { id: 'people-list' }, state.meta.people.filter((p) => p.id !== state.me.id).map((p) => h('option', { value: p.name + ' (' + p.team + ')' })));
    function draw() {
      clear(list);
      for (const id of chosen) list.appendChild(h('span', { class: 'chip person' }, personName(id),
        h('button', { type: 'button', 'aria-label': 'Remove ' + personName(id), text: '×', onclick: () => { chosen.splice(chosen.indexOf(id), 1); draw(); onChange(); } })));
    }
    input.addEventListener('change', () => {
      const p = state.meta.people.find((x) => (x.name + ' (' + x.team + ')') === input.value || x.name.toLowerCase() === input.value.toLowerCase());
      if (p && p.id !== state.me.id && !chosen.includes(p.id)) { chosen.push(p.id); draw(); onChange(); }
      input.value = '';
    });
    const teamBtn = h('button', { type: 'button', class: 'btn link small', text: 'Add my team', onclick: () => {
      for (const p of state.meta.people) if (p.team === state.me.team && p.id !== state.me.id && !chosen.includes(p.id)) chosen.push(p.id);
      draw(); onChange();
    } });
    draw();
    add(box, [list, h('div', { class: 'row' }, input, teamBtn), dl]);
    return box;
  }

  async function bookPage(params, editId) {
    let old = null;
    if (editId) old = await api('/api/bookings/' + editId);
    const st = old ? old.start : (params.start ? Number(params.start) : defaultStart());
    const en = old ? old.end : (params.end ? Number(params.end) : st + 60 * MIN);
    const f = {
      roomId: old ? old.roomId : (params.room || ''),
      title: old ? old.title : (params.title || ''),
      day: isoDay(st), start: hm(st), len: Math.round((en - st) / MIN),
      people: old ? old.people.map((p) => p.id) : (params.people ? params.people.split(',') : []),
      guests: old ? old.guests : 0, needs: old ? old.needs.slice() : [], privateTitle: old ? old.private : false,
      repeat: 'NONE', count: 5
    };
    const title = h('input', { value: f.title, maxlength: 80, placeholder: 'e.g. Sprint planning', required: true });
    const dayIn = h('input', { type: 'date', value: f.day, min: isoDay(Date.now()) });
    const startIn = select(quarterOptions(), f.start);
    const lens = [15, 30, 45, 60, 90, 120, 150, 180, 240].concat(state.me.admin ? [300, 360, 480, 600] : []);
    if (!lens.includes(f.len)) lens.push(f.len);
    const lenIn = select(lens.sort((a, b) => a - b).map((m) => [m, mins(m)]), f.len);
    const guestsIn = h('input', { type: 'number', min: 0, max: 40, value: f.guests });
    const privIn = h('input', { type: 'checkbox', checked: f.privateTitle });
    const repeatIn = select([['NONE', 'Does not repeat'], ['DAILY', 'Every day (Mon–Sat)'], ['WEEKDAYS', 'Every weekday (Mon–Fri)'], ['WEEKLY', 'Every week']], f.repeat);
    const countIn = h('input', { type: 'number', min: 2, max: 26, value: f.count });
    const countWrap = field('How many dates', countIn);
    countWrap.hidden = true;
    const needBoxes = state.meta.equipment.map((e) => {
      const cb = h('input', { type: 'checkbox', checked: f.needs.includes(e), value: e });
      cb.addEventListener('change', () => { f.needs = needBoxes.map((x) => x.cb).filter((c) => c.checked).map((c) => c.value); refresh(); });
      return { cb, el: h('label', { class: 'tick' }, cb, EQUIP[e]) };
    });
    const roomSel = select([['', 'Choose a room…']].concat(state.meta.rooms.filter((r) => r.active || r.id === f.roomId).map((r) => [r.id, r.code + ' ' + r.name + ' (' + r.capacity + ')'])), f.roomId);
    const roomsBox = h('div', { class: 'room-options' });
    const checkBox = h('div', { class: 'check', 'aria-live': 'polite' });
    const saveBtn = h('button', { class: 'btn primary wide', type: 'submit', text: old ? 'Save changes' : 'Book this room' });
    let lastCheck = null;

    function read() {
      f.title = title.value; f.day = dayIn.value; f.start = startIn.value; f.len = Number(lenIn.value);
      f.guests = Number(guestsIn.value) || 0; f.privateTitle = privIn.checked; f.roomId = roomSel.value;
      f.repeat = old ? 'NONE' : repeatIn.value; f.count = Number(countIn.value) || 2;
      countWrap.hidden = f.repeat === 'NONE';
      const s0 = at(f.day, f.start);
      return { roomId: f.roomId, title: f.title, start: s0, end: s0 + f.len * MIN, people: f.people, guests: f.guests,
        needs: f.needs, privateTitle: f.privateTitle, repeat: f.repeat, count: f.repeat === 'NONE' ? 1 : f.count, bookingId: old ? old.id : undefined };
    }

    let timer = null;
    function refresh() { clearTimeout(timer); timer = setTimeout(runCheck, 300); }

    async function runCheck() {
      const body = read();
      if (!f.day || isNaN(body.start)) return;
      // the room list for this time
      try {
        const r = await post('/api/find', body);
        drawRooms(r.rooms);
      } catch (e) { clear(roomsBox).appendChild(h('p', { class: 'small bad', text: e.message })); }
      if (!body.roomId) { clear(checkBox).appendChild(h('p', { class: 'small', text: 'Choose a room above to see if the time is free.' })); lastCheck = null; return; }
      try {
        lastCheck = await post('/api/check', body);
        drawCheck(lastCheck);
      } catch (e) { clear(checkBox).appendChild(h('div', { class: 'verdict bad' }, h('b', { text: e.message }))); lastCheck = null; }
    }

    let autoPicked = !!f.roomId;
    function drawRooms(rooms) {
      clear(roomsBox);
      if (!autoPicked && rooms.length && rooms[0].usable) { autoPicked = true; roomSel.value = rooms[0].roomId; f.roomId = rooms[0].roomId; refresh(); }
      const usable = rooms.filter((r) => r.usable);
      roomsBox.appendChild(h('p', { class: 'small', text: usable.length + ' of ' + rooms.length + ' rooms are free and fit at this time. Best fit first.' }));
      const list = h('div', { class: 'opt-list' });
      for (const r of rooms) {
        const on = r.roomId === f.roomId;
        list.appendChild(h('button', { type: 'button', class: 'opt' + (r.usable ? '' : ' no') + (on ? ' on' : ''), 'aria-pressed': on ? 'true' : 'false',
          onclick: () => { roomSel.value = r.roomId; refresh(); } },
          fob({ code: r.code, name: r.name }, true),
          h('span', { class: 'opt-text' }, h('b', { text: r.name }), h('small', { text: r.usable ? r.capacity + ' seats, floor ' + r.floor + (r.needsApproval ? ', needs approval' : '') : r.why.join(', ') })),
          r.usable ? h('span', { class: 'score', title: r.parts.map((p) => p.name + ' ' + p.points + '/' + p.max + ': ' + p.why).join('\n') }, String(r.score)) : null));
      }
      roomsBox.appendChild(list);
    }

    function drawCheck(c) {
      clear(checkBox);
      const occ = c.occurrences;
      if (c.ok && c.quota.ok) {
        checkBox.appendChild(h('div', { class: 'verdict ok' }, h('b', { text: occ.length > 1 ? 'All ' + occ.length + ' dates are free in ' + c.roomName + '.' : c.roomName + ' is free then.' }),
          c.approval ? h('span', { text: ' This room needs the facility manager\'s approval – it is held for you until they decide.' }) : null));
      } else {
        const msg = c.problems.length ? c.problems[0] : occ.length > 1 ? (occ.length - c.freeCount) + ' of ' + occ.length + ' dates cannot be booked.' :
          occ[0].rule ? occ[0].rule : 'Taken: ' + occ[0].clash.label + (occ[0].clash.by ? ' (' + occ[0].clash.by + ')' : '') + ', ' + span(occ[0].clash.start, occ[0].clash.end);
        checkBox.appendChild(h('div', { class: 'verdict bad' }, h('b', { text: msg }),
          !c.quota.ok ? h('p', { text: 'This also goes over the fair-use limit of ' + (c.quota.limitMin / 60) + ' hours a week (' + mins(c.quota.usedMin) + ' used).' }) : null));
      }
      if (occ.length > 1) {
        checkBox.appendChild(h('ul', { class: 'dates' }, occ.map((o) => h('li', { class: o.free ? 'ok' : 'bad' },
          h('span', { text: dayShort(o.start) }), h('small', { text: o.free ? 'free' : (o.rule || (o.clash ? 'taken: ' + o.clash.label : 'not possible')) })))));
        if (c.freeCount > 0 && c.freeCount < occ.length) checkBox.appendChild(h('p', { class: 'small', text: 'You can book only the free dates – the taken ones will be skipped.' }));
      }
      if (c.peopleClashes.length) {
        const seen = new Set();
        checkBox.appendChild(h('div', { class: 'people-warn' }, h('b', { text: 'Already busy then:' }),
          h('ul', { class: 'plain' }, c.peopleClashes.filter((p) => { const k = p.userId + p.start; if (seen.has(k)) return false; seen.add(k); return true; }).slice(0, 8)
            .map((p) => h('li', { text: (p.userId === state.me.id ? 'You' : p.name) + ' – ' + when(p.start, p.end) + ' in ' + p.room })))));
      }
      if (c.alternatives) {
        const alt = c.alternatives;
        if (alt.times.length) checkBox.appendChild(h('div', { class: 'alts' }, h('h3', { text: 'Free times in ' + c.roomName }),
          h('div', { class: 'alt-row' }, alt.times.map((t) => h('button', { type: 'button', class: 'alt' + (t.peopleFree ? '' : ' warn'),
            title: t.peopleFree ? 'Everybody is free' : 'Someone invited is busy then',
            onclick: () => { dayIn.value = isoDay(t.start); startIn.value = hm(t.start); refresh(); } },
            h('b', { text: span(t.start, t.end) }), h('small', { text: isoDay(t.start) === f.day ? (t.shiftMin > 0 ? mins(t.shiftMin) + ' later' : mins(-t.shiftMin) + ' earlier') : dayShort(t.start) }))))));
        if (alt.rooms.length) checkBox.appendChild(h('div', { class: 'alts' }, h('h3', { text: 'Other rooms free at this time' }),
          h('div', { class: 'alt-row' }, alt.rooms.map((r) => h('button', { type: 'button', class: 'alt', onclick: () => { roomSel.value = r.roomId; refresh(); } },
            h('b', { text: r.code + ' ' + r.name }), h('small', { text: r.capacity + ' seats, fit ' + r.score }))))));
        const first = occ.find((o) => !o.free);
        if (!old && first && first.clash && first.clash.kind === 'BOOKING' && occ.length === 1) {
          checkBox.appendChild(h('p', { class: 'small' }, 'Want this exact time anyway? ',
            h('button', { type: 'button', class: 'btn link small', text: 'Join the waitlist', onclick: async (e) => {
              const w = await act(e.currentTarget, () => post('/api/waitlist', read()));
              if (w) toast('You are number ' + w.place + ' in the queue. If ' + c.roomName + ' frees up, it is booked for you.');
            } })));
        }
      }
    }

    async function save(e, extra) {
      e && e.preventDefault && e.preventDefault();
      const body = Object.assign(read(), extra || {});
      if (!body.roomId) { toast('Choose a room first.', true); return; }
      if (old) body.version = old.version;
      saveBtn.disabled = true;
      try {
        const r = old ? await put('/api/bookings/' + old.id, body) : await post('/api/bookings', body);
        const first = old ? r : r.bookings[0];
        toast(old ? 'Saved.' : (r.bookings.length > 1 ? r.bookings.length + ' dates booked.' : first.status === 'PENDING' ? 'Requested. The facility manager will decide.' : 'Booked. ' + first.roomName + ', ' + when(first.start, first.end) + '.'));
        location.hash = 'booking/' + first.id;
      } catch (err) {
        const data = err.data || {};
        if (data.check) drawCheck(data.check);
        if (data.kind === 'PEOPLE') {
          let close;
          const yes = h('button', { class: 'btn primary', text: 'Book anyway', onclick: () => { close(); save(null, Object.assign({}, extra, { peopleOk: true })); } });
          close = modal('Some people are busy', h('p', { text: 'Some of the people you invited are already in another meeting then (see the list). Book anyway?' }),
            [h('button', { class: 'btn ghost', text: 'Change the time', onclick: () => close() }), yes]);
        } else if (data.kind === 'ROOM' && data.check && data.check.occurrences.length > 1 && data.check.freeCount > 0) {
          let close;
          const yes = h('button', { class: 'btn primary', text: 'Book the ' + data.check.freeCount + ' free dates', onclick: () => { close(); save(null, Object.assign({}, extra, { skipTaken: true })); } });
          close = modal('Some dates are taken', h('p', { text: err.message }), [h('button', { class: 'btn ghost', text: 'Back', onclick: () => close() }), yes]);
        } else if (data.kind === 'VERSION') {
          toast(err.message, true);
          setTimeout(() => route(), 1200);
        } else toast(err.message, true);
      } finally { saveBtn.disabled = false; }
    }

    for (const el of [title, dayIn, startIn, lenIn, guestsIn, privIn, repeatIn, countIn, roomSel]) {
      el.addEventListener('change', refresh);
      el.addEventListener('input', refresh);
    }
    const picker = peoplePicker(f.people, refresh);
    const form = h('form', { class: 'book', onsubmit: save },
      h('section', { class: 'card form-card' },
        field('What is the meeting?', title),
        h('div', { class: 'grid3' }, field('Day', dayIn), field('Starts', startIn), field('Length', lenIn)),
        old ? null : h('div', { class: 'grid2' }, field('Repeat', repeatIn), countWrap),
        field('Invite colleagues', picker, 'They get a message and see it on their front desk.'),
        h('div', { class: 'grid2' }, field('Visitors from outside', guestsIn), h('label', { class: 'tick big' }, privIn, 'Private (others see "Private meeting")')),
        h('fieldset', { class: 'needs' }, h('legend', { text: 'The room must have' }), needBoxes.map((x) => x.el)),
        field('Room', roomSel)),
      h('section', { class: 'card pick-card' }, h('h2', { text: 'Rooms at this time' }), roomsBox),
      h('section', { class: 'card check-card' }, h('h2', { text: 'Is it free?' }), checkBox, saveBtn,
        old ? h('a', { class: 'btn ghost wide', href: '#booking/' + old.id, text: 'Back without saving' }) : null));
    shell(page(old ? 'Change booking' : 'Book a room', old ? old.title + ', ' + when(old.start, old.end) : 'Kaksh checks the room, the people and the rules while you type.', form), old ? null : 'book');
    runCheck();
  }

  // ------------------------------------------------------------------ find a time for everybody

  function findPage(params) {
    const chosen = params.people ? params.people.split(',') : [];
    const dayIn = h('input', { type: 'date', value: isoDay(defaultStart()), min: isoDay(Date.now()) });
    const lenIn = select([30, 45, 60, 90, 120].map((m) => [m, mins(m)]), 60);
    const daysIn = select([[1, 'just that day'], [3, 'and the next 2 working days'], [5, 'and the next 4 working days']], 3);
    const out = h('div', { class: 'found' });
    const go = async (e) => {
      if (e) e.preventDefault();
      const r = await act(btn, () => post('/api/common', { people: chosen, date: dayIn.value, minutes: Number(lenIn.value), days: Number(daysIn.value) }));
      if (!r) return;
      clear(out);
      if (!r.slots.length) { out.appendChild(empty('No time found when everybody is free and a room fits. Try fewer people or more days.')); return; }
      let lastDay = '';
      for (const sl of r.slots) {
        const d = isoDay(sl.start);
        if (d !== lastDay) { out.appendChild(h('h3', { class: 'found-day', text: dayLong(sl.start) })); lastDay = d; }
        out.appendChild(h('div', { class: 'found-row' },
          h('b', { class: 'found-time', text: span(sl.start, sl.end) }),
          fob({ code: sl.room.code, name: sl.room.name }, true),
          h('span', null, sl.room.name, h('small', { text: ' ' + sl.room.capacity + ' seats, fit ' + sl.room.score })),
          h('a', { class: 'btn small', href: '#book?room=' + sl.room.roomId + '&start=' + sl.start + '&end=' + sl.end + '&people=' + chosen.join(','), text: 'Book this' })));
      }
    };
    const btn = h('button', { class: 'btn primary', type: 'submit', text: 'Find free times' });
    shell(page('Find a time', 'Pick the people. Kaksh looks at everybody\'s meetings and finds times when all of you – and a room that fits – are free.',
      h('form', { class: 'card form-card', onsubmit: go },
        field('Who must be there (you are included)', peoplePicker(chosen, () => {})),
        h('div', { class: 'grid3' }, field('From', dayIn), field('Look at', daysIn), field('Length', lenIn)), btn),
      out), 'find');
  }

  // ------------------------------------------------------------------ my bookings

  async function minePage(params) {
    const tab = params.tab || 'upcoming';
    const d = await api('/api/bookings?tab=' + tab);
    const tabs = h('div', { class: 'tabs', role: 'tablist' }, [['upcoming', 'Coming up'], ['past', 'Past'], ['cancelled', 'Cancelled']].map(([k, t]) =>
      h('a', { href: '#mine?tab=' + k, class: k === tab ? 'on' : null, role: 'tab', 'aria-selected': k === tab ? 'true' : 'false', text: t })));
    const list = d.bookings.length ? h('div', { class: 'ledger' }, d.bookings.map((b) => h('a', { class: 'ledger-row', href: '#booking/' + b.id },
      h('span', { class: 'l-day', text: dayShort(b.start) }), h('span', { class: 'l-time', text: span(b.start, b.end) }),
      h('span', { class: 'l-title' }, b.title, b.seriesId ? h('small', { text: ' (repeats)' }) : null),
      h('span', { class: 'l-room', text: b.roomCode + ' ' + b.roomName }),
      h('span', { class: 'l-st' }, b.invited ? chip('Invited', 'soft') : null, statusChip(b.status))))) :
      empty(tab === 'upcoming' ? 'No meetings coming up.' : 'Nothing here.');
    const waits = d.waiting.length ? h('section', { class: 'card' }, h('h2', { text: 'Waitlist' }),
      h('ul', { class: 'plain' }, d.waiting.map((w) => h('li', { class: 'wait-row' },
        h('span', null, h('b', { text: w.roomName }), ' ' + when(w.start, w.end) + ' – ' + w.title),
        w.status === 'WAITING' ? h('span', null, chip('Place ' + w.place), h('button', { class: 'btn link small', text: 'Leave', onclick: async (e) => {
          if (await act(e.currentTarget, () => post('/api/waitlist/' + w.id + '/leave'), 'You left the waitlist.')) route();
        } })) : w.status === 'BOOKED' ? h('a', { href: '#booking/' + w.bookingId, text: 'Got it – open booking' }) : chip(w.status === 'EXPIRED' ? 'Did not free up' : 'Left', 'soft'))))) : null;
    shell(page('My bookings', 'Meetings you organise or are invited to.',
      h('div', { class: 'row between' }, tabs, h('a', { class: 'btn ghost small', href: '/api/calendar.ics', download: 'my-meetings.ics', text: 'Add all to my calendar (.ics)' })),
      list, d.total > d.bookings.length ? h('p', { class: 'small', text: 'Showing ' + d.bookings.length + ' of ' + d.total + '.' }) : null, waits), 'mine');
  }

  // ------------------------------------------------------------------ one booking

  async function bookingPage(id) {
    const b = await api('/api/bookings/' + id);
    const acts = [];
    const reload = () => bookingPage(id);
    if (b.can.checkin) acts.push(h('button', { class: 'btn primary', text: 'Check in', onclick: async (e) => { if (await act(e.currentTarget, () => post('/api/bookings/' + id + '/checkin'), 'Checked in.')) reload(); } }));
    if (b.can.end) acts.push(h('button', { class: 'btn', text: 'End now and free the room', onclick: async (e) => { if (await act(e.currentTarget, () => post('/api/bookings/' + id + '/end'), 'Ended. The room is free again.')) reload(); } }));
    if (b.can.extend) acts.push(h('span', { class: 'ext' }, 'Need more time?', [15, 30].map((m) => h('button', { class: 'btn small', text: '+' + m + ' min', onclick: async (e) => {
      if (await act(e.currentTarget, () => post('/api/bookings/' + id + '/extend', { minutes: m }), 'Extended by ' + m + ' minutes.')) reload();
    } }))));
    if (b.can.edit) acts.push(h('a', { class: 'btn', href: '#edit/' + id, text: 'Change' }));
    if (b.can.cancel) acts.push(h('button', { class: 'btn danger', text: 'Cancel booking', onclick: () => {
      if (b.seriesId) {
        let close;
        const one = h('button', { class: 'btn', text: 'Only this date', onclick: async (e) => { if (await act(e.currentTarget, () => post('/api/bookings/' + id + '/cancel', { scope: 'one' }), 'Cancelled.')) { close(); reload(); } } });
        const all = h('button', { class: 'btn danger', text: 'This and all later dates', onclick: async (e) => { if (await act(e.currentTarget, () => post('/api/bookings/' + id + '/cancel', { scope: 'series' }), 'Cancelled.')) { close(); reload(); } } });
        close = modal('Cancel a repeating meeting', h('p', { text: 'Which dates do you want to cancel?' }), [h('button', { class: 'btn ghost', text: 'Back', onclick: () => close() }), one, all]);
      } else confirmBox('Cancel this booking?', 'The room becomes free for others, and the people invited are told.', 'Cancel booking',
        (reason) => post('/api/bookings/' + id + '/cancel', { scope: 'one', reason }).then((r) => { toast('Cancelled.'); reload(); return r; }), 'Reason (optional)');
    } }));
    if (b.can.approve) {
      acts.push(h('button', { class: 'btn primary', text: 'Approve', onclick: async (e) => { if (await act(e.currentTarget, () => post('/api/bookings/' + id + '/approve', {}), 'Approved.')) reload(); } }));
      acts.push(h('button', { class: 'btn danger', text: 'Decline', onclick: () => confirmBox('Decline this request?', 'The organiser is told and the room becomes free.', 'Decline',
        (note) => post('/api/bookings/' + id + '/decline', { note }).then((r) => { reload(); return r; }), 'Why? (the organiser sees this)') }));
    }
    const room = b.room;
    const card = h('article', { class: 'guestcard' },
      h('div', { class: 'gc-top' }, fob({ code: b.roomCode, name: b.roomName }),
        h('div', null, h('h1', { text: b.title }), h('p', { class: 'gc-when', text: dayLong(b.start) + ', ' + span(b.start, b.end) + ' (' + mins(Math.round((b.end - b.start) / MIN)) + ')' }),
          h('p', null, statusChip(b.status), b.private ? chip('Private', 'soft') : null, b.seriesId ? chip('Repeats', 'soft') : null))),
      h('dl', { class: 'gc-facts' },
        h('dt', { text: 'Room' }), h('dd', null, h('a', { href: '#room/' + b.roomId, text: b.roomCode + ' ' + b.roomName }), room ? ', floor ' + room.floor + ', ' + room.capacity + ' seats' : ''),
        h('dt', { text: 'Organiser' }), h('dd', { text: b.organiser.name }),
        h('dt', { text: 'People' }), h('dd', { text: b.headcount + ' in all' + (b.people.length ? ': ' + b.people.map((p) => p.name).join(', ') : '') + (b.guests ? ' + ' + b.guests + ' visitor' + (b.guests === 1 ? '' : 's') : '') }),
        b.needs.length ? [h('dt', { text: 'Needs' }), h('dd', null, equipList(b.needs))] : null,
        b.status === 'CONFIRMED' ? [h('dt', { text: 'Check in' }), h('dd', { text: 'between ' + time(b.checkinFrom) + ' and ' + time(b.checkinUntil) + ' – otherwise the room is given back' })] : null,
        b.reason ? [h('dt', { text: 'Note' }), h('dd', { text: b.reason })] : null),
      acts.length ? h('div', { class: 'gc-actions' }, acts) : null,
      h('p', { class: 'small' }, h('a', { href: '/api/bookings/' + id + '/ics', download: id + '.ics', text: 'Add to my calendar (.ics)' })));
    const series = b.series ? h('section', { class: 'card' }, h('h2', { text: 'All dates of this meeting' }),
      h('ul', { class: 'dates' }, b.series.dates.map((x) => h('li', { class: x.id === b.id ? 'this' : (['CANCELLED', 'DECLINED', 'EXPIRED'].includes(x.status) ? 'bad' : 'ok') },
        h('a', { href: '#booking/' + x.id, text: dayShort(x.start) }), h('small', { text: STATUS[x.status] })))),
      b.series.skipped.length ? h('p', { class: 'small', text: 'Skipped because the room was taken: ' + b.series.skipped.join(', ') }) : null) : null;
    const hist = b.history && b.history.length ? h('section', { class: 'card' }, h('h2', { text: 'History' }),
      h('ol', { class: 'hist' }, b.history.map((x) => h('li', null, h('time', { text: ago(x.at) }), ' ', h('b', { text: x.by }), ' – ' + x.text)))) : null;
    shell(page('Booking ' + b.id, null, h('div', { class: 'two' }, card, h('div', null, series, hist))), null);
  }

  // ------------------------------------------------------------------ one room

  async function roomPage(id, params) {
    const day = params.date || isoDay(Date.now());
    const d = await api('/api/rooms/' + id + '?date=' + day);
    const r = d.room;
    const now = d.now;
    const nowText = now.state === 'FREE' ? 'Free now' + (now.until ? ', until ' + time(now.until) : '') :
      now.state === 'BUSY' ? 'In use until ' + time(now.until) : now.state === 'CLOSED' ? 'Closed now: ' + (now.reason || '') : now.state === 'OFF' ? 'Not bookable at the moment' : 'Office closed now';
    const week = h('div', { class: 'week' }, d.week.map((w) => {
      const total = w.to - w.from;
      const col = h('div', { class: 'wk-col' + (w.date === isoDay(Date.now()) ? ' today' : '') }, h('b', { class: 'wk-day', text: dayShort(at(w.date, '12:00')) }));
      const lane = h('div', { class: 'wk-lane', onclick: (e) => {
        if (e.target !== lane) return;
        const rect = lane.getBoundingClientRect();
        const t = w.from + Math.floor((e.clientY - rect.top) / rect.height * total / (15 * MIN)) * 15 * MIN;
        location.hash = 'book?room=' + r.id + '&start=' + t + '&end=' + (t + 60 * MIN);
      } });
      for (const it of w.items) {
        const top = (Math.max(it.start, w.from) - w.from) / total * 100, ht = (Math.min(it.end, w.to) - Math.max(it.start, w.from)) / total * 100;
        if (it.kind === 'BLOCK') lane.appendChild(h('span', { class: 'entry block', style: { top: top + '%', height: ht + '%' }, title: 'Closed: ' + it.reason }, h('small', { text: 'Closed' })));
        else lane.appendChild(h('a', { class: 'entry st-' + it.status.toLowerCase() + (it.mine || it.invited ? ' me' : ''), href: '#booking/' + it.id, style: { top: top + '%', height: ht + '%' }, title: it.title + ', ' + span(it.start, it.end) },
          h('small', { text: time(it.start) + ' ' + it.title })));
      }
      col.appendChild(lane);
      return col;
    }));
    const u = d.use;
    shell(page(r.name, 'Room ' + r.code + ', floor ' + r.floor + '. ' + nowText + '.',
      h('div', { class: 'two' },
        h('section', { class: 'card roomplate' }, fob(r),
          h('dl', { class: 'gc-facts' }, h('dt', { text: 'Seats' }), h('dd', { text: String(r.capacity) }),
            h('dt', { text: 'Has' }), h('dd', null, equipList(r.equipment)),
            h('dt', { text: 'Buffer' }), h('dd', { text: r.bufferMin ? r.bufferMin + ' min kept free after each meeting' : 'none' }),
            h('dt', { text: 'Booking' }), h('dd', { text: r.needsApproval ? 'needs the facility manager\'s approval' : 'confirmed at once' }),
            r.note ? [h('dt', { text: 'Note' }), h('dd', { text: r.note })] : null,
            h('dt', { text: 'Last 4 weeks' }), h('dd', { text: u.percent + '% of open hours used, ' + u.meetings + ' meetings, ' + u.noShows + ' no-shows, ' + u.avgPeople + ' people on average' })),
          h('a', { class: 'btn primary', href: '#book?room=' + r.id, text: 'Book ' + r.name })),
        h('section', { class: 'card' }, h('h2', { text: 'Free today' }),
          d.freeToday.length ? h('div', { class: 'alt-row' }, d.freeToday.map((g) => h('a', { class: 'alt', href: '#book?room=' + r.id + '&start=' + g.start + '&end=' + Math.min(g.end, g.start + 60 * MIN) },
            h('b', { text: span(g.start, g.end) }), h('small', { text: mins(Math.round((g.end - g.start) / MIN)) })))) : h('p', { class: 'small', text: 'No free time left today.' }),
          d.blocks.length ? [h('h3', { text: 'Planned closings' }), h('ul', { class: 'plain' }, d.blocks.map((b) => h('li', { text: when(b.start, b.end) + ': ' + b.reason })))] : null)),
      h('section', { class: 'card' }, h('div', { class: 'row between' }, h('h2', { text: 'Week' }),
        h('div', { class: 'daynav' }, h('a', { class: 'btn ghost small', href: '#room/' + id + '?date=' + addDays(day, -7), text: 'Week before' }),
          h('a', { class: 'btn ghost small', href: '#room/' + id + '?date=' + addDays(day, 7), text: 'Week after' }))),
        h('p', { class: 'small', text: 'Click an empty spot to book it.' }), week)), null);
  }

  // ------------------------------------------------------------------ messages

  async function notesPage() {
    const list = await api('/api/notes');
    if (state.unread) { await post('/api/notes/read'); state.unread = 0; }
    shell(page('Messages', 'Invitations, approvals, rooms that freed up for you.',
      list.length ? h('ul', { class: 'notes' }, list.map((n) => h('li', { class: n.read ? '' : 'new' },
        h('time', { text: ago(n.at) }), n.link ? h('a', { href: '#' + n.link, text: n.text }) : h('span', { text: n.text })))) : empty('No messages yet.')), 'notes');
  }

  // ------------------------------------------------------------------ facility desk (admin)

  async function adminPage(params) {
    const tab = params.tab || 'requests';
    const tabs = h('div', { class: 'tabs', role: 'tablist' }, [['requests', 'Requests & closings'], ['numbers', 'How rooms are used'], ['rooms', 'Room settings'], ['log', 'Activity log']].map(([k, t]) =>
      h('a', { href: '#admin?tab=' + k, class: k === tab ? 'on' : null, role: 'tab', 'aria-selected': k === tab ? 'true' : 'false', text: t })));
    let body;
    if (tab === 'numbers') body = await numbersTab();
    else if (tab === 'rooms') body = roomsTab();
    else if (tab === 'log') body = await logTab();
    else body = await requestsTab();
    shell(page('Facility desk', 'Approvals, closings, room settings and numbers.', tabs, body), 'admin');
  }

  async function requestsTab() {
    const d = await api('/api/admin/approvals');
    const pending = d.pending.length ? h('div', { class: 'ledger' }, d.pending.map((b) => h('div', { class: 'ledger-row' },
      h('span', { class: 'l-day', text: dayShort(b.start) }), h('span', { class: 'l-time', text: span(b.start, b.end) }),
      h('a', { class: 'l-title', href: '#booking/' + b.id }, b.title, h('small', { text: ' by ' + b.organiser.name + ', ' + b.headcount + ' people' })),
      h('span', { class: 'l-room', text: b.roomCode + ' ' + b.roomName }),
      h('span', { class: 'l-st' },
        h('button', { class: 'btn primary small', text: 'Approve', onclick: async (e) => { if (await act(e.currentTarget, () => post('/api/bookings/' + b.id + '/approve', {}), 'Approved.')) route(); } }),
        h('button', { class: 'btn small danger', text: 'Decline', onclick: () => confirmBox('Decline ' + b.title + '?', 'The organiser is told and the room becomes free.', 'Decline',
          (note) => post('/api/bookings/' + b.id + '/decline', { note }).then((r) => { route(); return r; }), 'Why? (the organiser sees this)') }))))) :
      empty('No requests waiting.');
    // close a room
    const roomIn = select(state.meta.rooms.map((r) => [r.id, r.code + ' ' + r.name]), '');
    const st = defaultStart();
    const dayIn = h('input', { type: 'date', value: isoDay(st), min: isoDay(Date.now()) });
    const fromIn = select(quarterOptions(), '14:00'), toIn = select(quarterOptions().concat(['20:00']), '18:00');
    const reasonIn = h('input', { maxlength: 100, placeholder: 'e.g. AC repair' });
    const planBox = h('div', { class: 'plan' });
    const body = () => ({ roomId: roomIn.value, start: at(dayIn.value, fromIn.value), end: at(dayIn.value, toIn.value), reason: reasonIn.value });
    const preview = h('button', { class: 'btn', type: 'button', text: 'Show what happens', onclick: async (e) => {
      const r = await act(e.currentTarget, () => post('/api/blocks?dry=1', body()));
      if (!r) return;
      clear(planBox);
      planBox.appendChild(r.plan.length ? h('div', null, h('p', { text: r.plan.length + ' booking' + (r.plan.length === 1 ? '' : 's') + ' in that time:' }),
        h('ul', { class: 'plain' }, r.plan.map((p) => h('li', { class: p.action === 'MOVE' ? 'ok' : 'bad' },
          span(p.start, p.end) + ' ' + p.title + ' (' + p.organiser + ') – ' + (p.action === 'MOVE' ? 'moves to ' + p.to : 'cancelled, no other room free'))))) :
        h('p', { text: 'No bookings in that time. Nobody is affected.' }));
      planBox.appendChild(h('button', { class: 'btn danger', type: 'button', text: 'Close ' + r.room, onclick: async (e2) => {
        if (await act(e2.currentTarget, () => post('/api/blocks', body()), r.room + ' closed. Everybody affected was told.')) route();
      } }));
    } });
    const blocks = d.blocks.length ? h('ul', { class: 'plain' }, d.blocks.map((b) => h('li', { class: 'wait-row' },
      h('span', null, h('b', { text: b.roomName }), ' ' + when(b.start, b.end) + ': ' + b.reason),
      h('button', { class: 'btn link small', text: 'Open again', onclick: async (e) => { if (await act(e.currentTarget, () => post('/api/blocks/' + b.id + '/delete'), 'Room open again.')) route(); } })))) :
      h('p', { class: 'small', text: 'No closings planned.' });
    return h('div', null, h('section', { class: 'card' }, h('h2', { text: 'Requests for the boardroom and hall' }), pending),
      h('div', { class: 'two' },
        h('section', { class: 'card form-card' }, h('h2', { text: 'Close a room' }),
          h('p', { class: 'small', text: 'Bookings in that time are moved to the best free room that fits, or cancelled if none is free. Everyone is told.' }),
          field('Room', roomIn), h('div', { class: 'grid3' }, field('Day', dayIn), field('From', fromIn), field('To', toIn)), field('Why', reasonIn), preview, planBox),
        h('section', { class: 'card' }, h('h2', { text: 'Planned closings' }), blocks)));
  }

  async function numbersTab() {
    const d = await api('/api/admin/insights?weeks=4');
    const t = d.totals;
    const facts = h('div', { class: 'facts' }, [
      [t.hours + ' h', 'of meetings held (last 4 weeks)'], [t.noShowPercent + '%', 'of bookings were no-shows'],
      [String(t.clashesStopped), 'double bookings stopped'], [Math.round(t.minutesGivenBack / 60) + ' h', 'given back by no-shows and early ends'],
      [String(t.waitlistWins), 'rooms handed on from the waitlist']].map(([n, l]) => h('div', { class: 'fact' }, h('b', { text: n }), h('span', { text: l }))));
    const rows = d.rooms.slice().sort((a, b) => b.percent - a.percent).map((r) => h('div', { class: 'use-row' },
      h('span', { class: 'use-name' }, h('b', { text: r.code }), ' ' + r.name),
      h('span', { class: 'use-bar' }, h('i', { style: { width: Math.min(100, r.percent) + '%' } }), h('em', { text: r.percent + '%' })),
      h('span', { class: 'use-num', text: r.meetings + (r.meetings === 1 ? ' meeting, ' : ' meetings, ') + r.noShowPercent + '% no-show, ' + r.avgPeople + ' of ' + r.capacity + ' seats' }),
      h('span', { class: 'use-adv', text: r.advice })));
    const max = Math.max(0.1, ...d.heat.flat());
    const days = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
    const heat = h('table', { class: 'heat' }, h('thead', null, h('tr', null, h('th'), Array.from({ length: 12 }, (_, i) => h('th', { text: String(8 + i) })))),
      h('tbody', null, d.heat.map((row, i) => h('tr', null, h('th', { text: days[i] }), row.map((v) => h('td', { style: { '--v': (v / max).toFixed(2) }, title: v + ' rooms in use on average', text: v ? v.toFixed(1) : '' }))))));
    const lens = ['up to 30 min', '31–60 min', '61–90 min', '91–120 min', 'longer'];
    const lmax = Math.max(1, ...d.lengths);
    return h('div', null, facts,
      h('section', { class: 'card' }, h('h2', { text: 'Rooms, busiest first' }), h('p', { class: 'small', text: 'Share of open hours (Mon–Sat, 08:00–20:00) with a meeting that took place.' }), h('div', { class: 'use' }, rows)),
      h('div', { class: 'two' },
        h('section', { class: 'card' }, h('h2', { text: 'When rooms are busy' }), h('p', { class: 'small', text: 'Rooms in use on average, by weekday and hour.' }), heat),
        h('section', { class: 'card' }, h('h2', { text: 'How long meetings are' }),
          h('div', { class: 'use' }, d.lengths.map((n, i) => h('div', { class: 'use-row slim' }, h('span', { class: 'use-name', text: lens[i] }),
            h('span', { class: 'use-bar' }, h('i', { style: { width: (n * 100 / lmax) + '%' } }), h('em', { text: String(n) }))))),
          h('h3', { text: 'Who books most' }), h('ol', { class: 'plain small' }, d.top.map((x) => h('li', { text: x.name + ' – ' + x.count + ' meetings' }))))));
  }

  function roomsTab() {
    return h('div', { class: 'room-settings' }, state.meta.rooms.map((r) => {
      const cap = h('input', { type: 'number', min: 1, max: 200, value: r.capacity });
      const buf = select([0, 5, 10, 15, 20, 30].map((m) => [m, m + ' min']), r.bufferMin);
      const appr = h('input', { type: 'checkbox', checked: r.needsApproval });
      const act2 = h('input', { type: 'checkbox', checked: r.active });
      const eq = state.meta.equipment.map((e) => ({ e, cb: h('input', { type: 'checkbox', checked: r.equipment.includes(e) }) }));
      const save = h('button', { class: 'btn small', text: 'Save', onclick: async (e) => {
        const nr = await act(e.currentTarget, () => put('/api/rooms/' + r.id, { capacity: Number(cap.value), bufferMin: Number(buf.value), needsApproval: appr.checked, active: act2.checked,
          equipment: eq.filter((x) => x.cb.checked).map((x) => x.e) }), r.name + ' saved.');
        if (nr) Object.assign(r, nr);
      } });
      return h('section', { class: 'card rs' }, h('div', { class: 'rs-head' }, fob(r, true), h('b', { text: r.name })),
        h('div', { class: 'grid2' }, field('Seats', cap), field('Buffer after meetings', buf)),
        h('div', { class: 'ticks' }, eq.map((x) => h('label', { class: 'tick' }, x.cb, EQUIP[x.e]))),
        h('label', { class: 'tick' }, appr, 'Needs approval'), h('label', { class: 'tick' }, act2, 'Open for booking'), save);
    }));
  }

  async function logTab() {
    const list = await api('/api/admin/audit');
    return h('section', { class: 'card' }, h('table', { class: 'log' }, h('thead', null, h('tr', null, ['When', 'Who', 'What', 'Detail'].map((t) => h('th', { text: t })))),
      h('tbody', null, list.map((a) => h('tr', null, h('td', { text: dayShort(a.at) + ' ' + time(a.at) }), h('td', { text: a.who }), h('td', { text: a.action }),
        h('td', { text: (a.target || '') + (a.detail ? ' – ' + a.detail : '') }))))));
  }

  // ------------------------------------------------------------------ routing

  function schedule(fn, ms) {
    clearTimeout(refreshTimer);
    const hash = location.hash;
    refreshTimer = setTimeout(() => { if (location.hash === hash && !modalRoot.firstChild && !document.querySelector('form.book')) fn(); }, ms);
  }

  function parse() {
    const raw = location.hash.replace(/^#/, '');
    const [path, q] = raw.split('?');
    const params = {};
    if (q) for (const part of q.split('&')) { const [k, v] = part.split('='); params[decodeURIComponent(k)] = decodeURIComponent(v || ''); }
    return { parts: path.split('/'), params };
  }

  async function route() {
    clearTimeout(refreshTimer);
    clear(modalRoot);
    if (!state.me) {
      try {
        const r = await api('/api/me');
        if (r.user) { state.me = r.user; state.meta = r.meta; }
      } catch (e) { /* not signed in */ }
    }
    if (!state.me) { signIn(); return; }
    const { parts, params } = parse();
    try {
      switch (parts[0]) {
        case '': await homePage(); break;
        case 'board': await boardPage(params); break;
        case 'book': await bookPage(params); break;
        case 'edit': await bookPage(params, parts[1]); break;
        case 'find': findPage(params); break;
        case 'mine': await minePage(params); break;
        case 'booking': await bookingPage(parts[1]); break;
        case 'room': await roomPage(parts[1], params); break;
        case 'notes': await notesPage(); break;
        case 'approvals': location.hash = 'admin'; return;
        case 'admin': await adminPage(params); break;
        default: await homePage();
      }
      const v = document.getElementById('view');
      if (v && route.moved) v.focus({ preventScroll: true });
      route.moved = true;
    } catch (e) {
      if (e.status !== 401) shell(page('Something is not right', null, empty(e.message, h('a', { class: 'btn', href: '#', text: 'Go to the front desk' }))), '');
    }
  }

  window.addEventListener('hashchange', () => { window.scrollTo(0, 0); route(); });
  route();
})();
