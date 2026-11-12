/* Sikho-Sikhao – the website. Plain JavaScript, no libraries.
   Every page is built with h() (document.createElement + textContent), never innerHTML, so text that people type
   can never run as code (no XSS). The server's API is under /api; every change sends the X-Sikho header (CSRF). */
(function () {
  'use strict';

  const state = { me: null, meta: null, route: '', loading: 0 };
  const app = document.getElementById('app');
  const modalRoot = document.getElementById('modal-root');
  const SVGNS = 'http://www.w3.org/2000/svg';

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

  async function api(path, method, body) {
    const opts = { method: method || 'GET', headers: {}, credentials: 'same-origin' };
    if (body !== undefined || (method && method !== 'GET')) {
      opts.headers['Content-Type'] = 'application/json';
      opts.headers['X-Sikho'] = '1';
      opts.body = JSON.stringify(body || {});
    }
    let res;
    try { res = await fetch(path, opts); } catch (e) { throw new Error('Cannot reach the server. Is it still running?'); }
    let data = null;
    try { data = await res.json(); } catch (e) { /* empty answer */ }
    if (res.status === 401 && path !== '/api/login' && path !== '/api/join') {
      state.me = null;
      render();
      throw new Error('Please sign in again.');
    }
    if (!res.ok) throw new Error((data && data.error) || ('Error ' + res.status));
    return data;
  }

  const post = (path, body) => api(path, 'POST', body || {});
  const put = (path, body) => api(path, 'PUT', body || {});

  /** Runs an action from a button: shows errors as a toast, and calls then() with the answer. */
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
    toastTimer = setTimeout(() => { t.hidden = true; }, bad ? 5200 : 3200);
  }

  /** A modal patch. Returns close(). */
  function modal(title, body, actions) {
    clear(modalRoot);
    const close = () => clear(modalRoot);
    const box = h('div', { class: 'modal patch', role: 'dialog', 'aria-modal': 'true', 'aria-label': title },
      h('h2', { text: title }), body, actions ? h('div', { class: 'actions' }, actions) : null);
    const back = h('div', { class: 'modal-back', onclick: (e) => { if (e.target === back) close(); } }, box);
    modalRoot.appendChild(back);
    const first = box.querySelector('input, select, textarea, button');
    if (first) first.focus();
    back.addEventListener('keydown', (e) => { if (e.key === 'Escape') close(); });
    return close;
  }

  function confirmBox(title, text, okLabel, onOk, withReason) {
    const reason = withReason ? h('textarea', { placeholder: withReason, maxlength: 200 }) : null;
    let close;
    const ok = h('button', { class: 'btn primary', text: okLabel, onclick: async () => {
      const r = await act(ok, () => onOk(reason ? reason.value : undefined));
      if (r !== undefined) close();
    } });
    close = modal(title, h('div', null, h('p', { text }), reason), [h('button', { class: 'btn ghost', text: 'Back', onclick: () => close() }), ok]);
  }

  // ------------------------------------------------------------------ formatting

  const TZ = 'Asia/Kolkata';
  const fmt = (ms, o) => new Intl.DateTimeFormat('en-IN', Object.assign({ timeZone: TZ }, o)).format(new Date(ms));
  const dateTime = (ms) => fmt(ms, { weekday: 'short', day: 'numeric', month: 'short', hour: 'numeric', minute: '2-digit' });
  const dateOnly = (ms) => fmt(ms, { day: 'numeric', month: 'short', year: 'numeric' });
  const dayNum = (ms) => fmt(ms, { day: 'numeric' });
  const monShort = (ms) => fmt(ms, { month: 'short' });
  const timeOnly = (ms) => fmt(ms, { hour: 'numeric', minute: '2-digit' });

  function ago(ms) {
    const d = Date.now() - ms, m = Math.round(d / 60000);
    if (Math.abs(m) < 1) return 'just now';
    const fut = m < 0, a = Math.abs(m);
    const txt = a < 60 ? a + ' min' : a < 1440 ? Math.round(a / 60) + ' h' : Math.round(a / 1440) + ' day' + (Math.round(a / 1440) === 1 ? '' : 's');
    return fut ? 'in ' + txt : txt + ' ago';
  }

  const plural = (n, w) => n + ' ' + w + (n === 1 ? '' : 's');

  const CAT_CLASS = {
    'Music': 'c-music', 'Languages': 'c-languages', 'Cooking': 'c-cooking', 'Tech & Computers': 'c-tech',
    'Arts & Crafts': 'c-arts', 'Fitness & Yoga': 'c-fitness', 'Home & Repair': 'c-home', 'Study & Careers': 'c-study',
    'Garden & Nature': 'c-garden', 'Life Skills': 'c-life'
  };
  const catClass = (c) => CAT_CLASS[c] || 'c-study';
  const PATCHES = ['--madder', '--indigo', '--turmeric', '--leaf', '--rose', '--teal', '--plum', '--clay'];
  function personColour(id) {
    let n = 0;
    for (const ch of String(id || '')) n = (n * 31 + ch.charCodeAt(0)) >>> 0;
    return 'var(' + PATCHES[n % PATCHES.length] + ')';
  }

  const LEVEL = { BEGINNER: 'Beginner', INTERMEDIATE: 'Some experience', EXPERT: 'Expert' };
  const MODE = { IN_PERSON: 'In person', ONLINE: 'Online', EITHER: 'In person or online' };
  const PLACE = { MY_PLACE: "At the owner's place", YOUR_PLACE: 'At your place', PUBLIC: 'Public place' };
  const STATUS = {
    REQUESTED: 'Waiting for answer', ACCEPTED: 'Accepted', COMPLETED: 'Completed', DECLINED: 'Declined',
    CANCELLED: 'Cancelled', EXPIRED: 'Expired', DISPUTED: 'With a moderator', NOT_CONFIRMED: 'Not confirmed'
  };
  const REASONS = { NO_SHOW: 'Did not turn up / cancelled late', MONEY: 'Asked for money', RUDE: 'Rude or disrespectful',
    UNSAFE: 'Made me feel unsafe', FAKE: 'Fake profile', SPAM: 'Spam or advertising', OTHER: 'Something else' };
  const DAYS = ['MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT', 'SUN'];
  const PARTS = [['MORN', 'Morning'], ['AFT', 'Afternoon'], ['EVE', 'Evening']];
  const slotName = (sl) => { const [d, p] = sl.split('-'); return d.charAt(0) + d.slice(1).toLowerCase() + ' ' + (PARTS.find((x) => x[0] === p) || ['', p])[1].toLowerCase(); };

  // ------------------------------------------------------------------ small pictures

  function avatar(person, size) {
    return h('span', { class: 'avatar' + (size ? ' ' + size : ''), style: { '--c': personColour(person && person.id) }, 'aria-hidden': 'true',
      text: (person && person.initials) || '?' });
  }

  function stars(r, count) {
    const full = Math.round(r || 0);
    return h('span', { class: 'stars', title: (r || 0).toFixed(1) + ' out of 5' },
      '★'.repeat(full), h('span', { class: 'off', text: '★'.repeat(5 - full) }),
      count !== undefined ? h('span', { class: 'tiny', text: ' ' + (r || 0).toFixed(1) + ' (' + count + ')' }) : null);
  }

  /** One sewing button = one time credit. */
  function sewButton(held) {
    return s('svg', { class: 'sew' + (held ? ' held' : ''), viewBox: '0 0 24 24', 'aria-hidden': 'true' },
      s('circle', { cx: 12, cy: 12, r: 10.5, fill: 'var(--turmeric)', stroke: 'rgba(0,0,0,.25)', 'stroke-width': 1 }),
      s('circle', { cx: 12, cy: 12, r: 7.5, fill: 'none', stroke: 'rgba(0,0,0,.18)', 'stroke-width': 1 }),
      ...[[9.5, 9.5], [14.5, 9.5], [9.5, 14.5], [14.5, 14.5]].map(([x, y]) => s('circle', { cx: x, cy: y, r: 1.3, fill: 'rgba(40,20,10,.7)' })),
      s('path', { d: 'M9.5 9.5 L14.5 14.5 M14.5 9.5 L9.5 14.5', stroke: 'var(--madder)', 'stroke-width': 1, opacity: .8 }));
  }

  function buttonsRow(n, held, max) {
    const row = h('span', { class: 'buttons-row', 'aria-label': n + ' credits' + (held ? ', ' + held + ' held' : '') });
    const show = Math.min(n, max || 14);
    for (let i = 0; i < show; i++) row.appendChild(sewButton(false));
    for (let i = 0; i < Math.min(held || 0, 6); i++) row.appendChild(sewButton(true));
    if (n > show) row.appendChild(h('span', { class: 'tiny', text: '+' + (n - show) }));
    return row;
  }

  /** The match score drawn as an embroidery hoop: the thread goes round as far as the score. */
  function hoop(score, size) {
    const r = 25, c = 2 * Math.PI * r, v = Math.max(0, Math.min(100, score || 0));
    const col = v >= 75 ? 'var(--leaf)' : v >= 55 ? 'var(--turmeric)' : 'var(--madder)';
    return s('svg', { class: 'hoop', viewBox: '0 0 64 64', width: size || 64, height: size || 64, role: 'img', 'aria-label': 'Match ' + Math.round(v) + ' out of 100' },
      s('circle', { cx: 32, cy: 32, r: 30, fill: 'none', stroke: 'var(--clay)', 'stroke-width': 3.5 }),
      s('rect', { x: 28, y: 0, width: 8, height: 5, rx: 1.5, fill: 'var(--clay)' }),
      s('circle', { cx: 32, cy: 32, r: 25, fill: 'var(--patch-2)', stroke: 'var(--stitch)', 'stroke-width': 1.5, 'stroke-dasharray': '3 3' }),
      s('circle', { cx: 32, cy: 32, r, fill: 'none', stroke: col, 'stroke-width': 4, 'stroke-linecap': 'round',
        'stroke-dasharray': (c * v / 100) + ' ' + c, transform: 'rotate(-90 32 32)' }),
      s('text', { x: 32, y: 37, 'text-anchor': 'middle', 'font-size': 16 }, String(Math.round(v))));
  }

  function bar(pct, colourClass) {
    return h('div', { class: 'bar ' + (colourClass || '') }, h('i', { style: { width: Math.max(0, Math.min(100, pct)) + '%' } }));
  }

  function whyBox(parts) {
    if (!parts) return null;
    const row = (label, v, note, cls) => [h('span', { class: 'lbl', text: label }), bar(v, cls), h('span', { class: 'num', text: v + '%' }),
      note ? h('span', { class: 'note', text: note }) : null];
    return h('div', { class: 'why' },
      row('Skill fit (40%)', parts.skill, parts.skillWhy, 'c-study'),
      row('Nearness (25%)', parts.near, parts.nearWhy, 'c-garden'),
      row('Free time (15%)', parts.time, parts.timeWhy, 'c-cooking'),
      row('Trust (15%)', parts.trust, parts.trustWhy, 'c-languages'),
      row('Level (5%)', parts.level, null, 'c-arts'));
  }

  function personLine(p, extra) {
    if (!p) return null;
    const t = p.trust || {};
    return h('div', { class: 'person' }, avatar(p),
      h('div', { style: { 'min-width': '0' } },
        h('a', { class: 'name', href: '#member/' + p.id, text: p.name }),
        h('div', { class: 'meta' }, p.locality, p.km !== undefined ? ' · ' + p.km + ' km' : '', ' · ',
          t.reviews ? stars(t.rating) : h('span', { text: 'new' }), t.reviews ? ' ' + t.rating.toFixed(1) : '',
          extra ? [' · ', extra] : null)));
  }

  function dateTag(ms, cls) {
    return h('span', { class: 'tag-date ' + (cls || '') }, h('span', { class: 'd', text: dayNum(ms) }), h('span', { class: 'm', text: monShort(ms) }));
  }

  function chip(text, cls) { return h('span', { class: 'chip ' + (cls || ''), text }); }

  function empty(text, extra) { return h('div', { class: 'empty' }, h('div', { text }), extra || null); }

  function sectionTitle(text, extra) { return h('div', { class: 'section-title' }, h('h2', { text }), extra || null); }

  /** The 7 × 3 grid of free times. editable: clicking toggles. shared: slots both people have. */
  function slotGrid(selected, opts) {
    opts = opts || {};
    const sel = new Set(selected || []), shared = new Set(opts.shared || []);
    const grid = h('div', { class: 'slots', role: opts.editable ? 'group' : null, 'aria-label': 'Free times' },
      h('span'), DAYS.map((d) => h('span', { class: 'h', text: d.charAt(0) + d.slice(1).toLowerCase() })));
    for (const [p, label] of PARTS) {
      grid.appendChild(h('span', { class: 'p', text: label }));
      for (const d of DAYS) {
        const key = d + '-' + p;
        const cls = 'slot' + (shared.has(key) ? ' shared' : sel.has(key) ? ' on' : '');
        if (opts.editable) {
          const b = h('button', { type: 'button', class: cls, 'aria-pressed': String(sel.has(key)), 'aria-label': slotName(key), text: '✕' });
          b.addEventListener('click', () => {
            if (sel.has(key)) sel.delete(key); else sel.add(key);
            b.className = 'slot' + (sel.has(key) ? ' on' : '');
            b.setAttribute('aria-pressed', String(sel.has(key)));
            if (opts.onchange) opts.onchange([...sel]);
          });
          grid.appendChild(b);
        } else grid.appendChild(h('span', { class: cls, title: slotName(key), text: '✕' }));
      }
    }
    grid.value = () => [...sel];
    return grid;
  }

  function field(label, input, hint) {
    return h('label', { class: 'field' }, h('span', null, label, hint ? h('span', { class: 'tiny', text: ' ' + hint }) : null), input);
  }

  function select(options, value, props) {
    const el = h('select', props || null);
    for (const o of options) {
      if (o.group) {
        const g = h('optgroup', { label: o.group });
        for (const x of o.items) g.appendChild(h('option', { value: x.value, text: x.label, selected: x.value === value }));
        el.appendChild(g);
      } else el.appendChild(h('option', { value: o.value, text: o.label, selected: o.value === value }));
    }
    return el;
  }

  function skillOptions(withEmpty) {
    const groups = {};
    for (const sk of state.meta.skills) (groups[sk.category] = groups[sk.category] || []).push({ value: sk.id, label: sk.name });
    const out = withEmpty ? [{ value: '', label: withEmpty }] : [];
    for (const [g, items] of Object.entries(groups)) out.push({ group: g, items });
    return out;
  }

  const localityOptions = () => state.meta.localities.map((l) => ({ value: l.id, label: l.name }));

  /** "2026-11-14T18:00" in India time → epoch millis. */
  function localToMillis(v) {
    if (!v) return 0;
    const [d, t] = v.split('T');
    const [y, mo, da] = d.split('-').map(Number);
    const [hh, mm] = (t || '00:00').split(':').map(Number);
    return Date.UTC(y, mo - 1, da, hh, mm) - 330 * 60000;   // India is UTC+5:30 all year
  }

  /** "days from today at hh:00" in India time, as the value for a datetime-local box. */
  function defaultWhen(days, hour) {
    const today = millisToLocal(Date.now()).slice(0, 10);
    const base = localToMillis(today + 'T00:00') + days * 86400000;
    return millisToLocal(base + hour * 3600000);
  }

  function millisToLocal(ms) {
    const d = new Date(ms + 330 * 60000);
    const p = (n) => String(n).padStart(2, '0');
    return d.getUTCFullYear() + '-' + p(d.getUTCMonth() + 1) + '-' + p(d.getUTCDate()) + 'T' + p(d.getUTCHours()) + ':' + p(d.getUTCMinutes());
  }

  // ------------------------------------------------------------------ icons (simple line drawings)

  const ICONS = {
    home: 'M3 11l9-7 9 7v9a1 1 0 0 1-1 1h-5v-6H9v6H4a1 1 0 0 1-1-1z',
    search: 'M11 4a7 7 0 1 1 0 14 7 7 0 0 1 0-14zM16.5 16.5L21 21',
    cards: 'M4 5h11v14H4zM8 3h12v14',
    swap: 'M7 7h11l-3-3M17 17H6l3 3',
    chat: 'M4 5h16v11H9l-5 4z',
    circle: 'M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM12 8a2 2 0 1 0 0 4 2 2 0 0 0 0-4zM7 17c1-2 3-3 5-3s4 1 5 3',
    wallet: 'M3 7h16a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H3zM3 7l12-4v4M16 13h2',
    map: 'M3 6l6-3 6 3 6-3v15l-6 3-6-3-6 3zM9 3v15M15 6v15',
    chart: 'M4 20V10M10 20V4M16 20v-7M22 20H2',
    user: 'M12 3a4 4 0 1 1 0 8 4 4 0 0 1 0-8zM4 21c1-4 4-6 8-6s7 2 8 6',
    shield: 'M12 3l8 3v6c0 5-3.5 8-8 9-4.5-1-8-4-8-9V6z',
    ledger: 'M5 3h12l2 2v16H5zM9 8h6M9 12h6M9 16h4',
    log: 'M4 6h16M4 12h16M4 18h10',
    bell: 'M6 16V11a6 6 0 0 1 12 0v5l2 2H4zM10 20a2 2 0 0 0 4 0',
    menu: 'M3 6h18M3 12h18M3 18h18'
  };
  function icon(name) {
    return s('svg', { viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor', 'stroke-width': 1.8, 'stroke-linecap': 'round', 'stroke-linejoin': 'round', 'aria-hidden': 'true' },
      s('path', { d: ICONS[name] || ICONS.home }));
  }

  /** The brand mark: four cloth patches joined by a running stitch, with a button in the middle. */
  function brandMark() {
    return s('svg', { class: 'brand-mark', viewBox: '0 0 64 64', 'aria-hidden': 'true' },
      s('rect', { x: 2, y: 2, width: 29, height: 29, rx: 6, fill: '#b5432f' }),
      s('rect', { x: 33, y: 2, width: 29, height: 29, rx: 6, fill: '#c98a12' }),
      s('rect', { x: 2, y: 33, width: 29, height: 29, rx: 6, fill: '#4c7d3d' }),
      s('rect', { x: 33, y: 33, width: 29, height: 29, rx: 6, fill: '#e2694f' }),
      s('path', { d: 'M6 32h52M32 6v52', stroke: '#fffaf0', 'stroke-width': 3, 'stroke-dasharray': '5 4', 'stroke-linecap': 'round' }),
      s('circle', { cx: 32, cy: 32, r: 7, fill: '#fffaf0' }),
      ...[[30, 30], [34, 30], [30, 34], [34, 34]].map(([x, y]) => s('circle', { cx: x, cy: y, r: 1.3, fill: '#2b2433' })));
  }

  // ------------------------------------------------------------------ the frame and the router

  const NAV = [
    { group: 'Me' },
    { route: 'home', label: 'My patch', icon: 'home', colour: '--madder' },
    { route: 'explore', label: 'Find skills', icon: 'search', colour: '--teal' },
    { route: 'mine', label: 'My cards', icon: 'cards', colour: '--turmeric' },
    { route: 'exchanges', label: 'Exchanges', icon: 'chat', colour: '--indigo', count: 'needs' },
    { route: 'wallet', label: 'Time wallet', icon: 'wallet', colour: '--clay' },
    { group: 'Neighbourhood' },
    { route: 'swaps', label: 'Swap circles', icon: 'swap', colour: '--rose' },
    { route: 'circles', label: 'Skill circles', icon: 'circle', colour: '--leaf' },
    { route: 'map', label: 'Locality map', icon: 'map', colour: '--plum' },
    { route: 'community', label: 'Community', icon: 'chart', colour: '--teal' },
    { group: 'Moderators', mod: true },
    { route: 'mod', label: 'Reports & disputes', icon: 'shield', colour: '--madder', mod: true, count: 'mod' },
    { route: 'members', label: 'Members', icon: 'user', colour: '--indigo', mod: true },
    { route: 'ledger', label: 'Ledger check', icon: 'ledger', colour: '--turmeric', mod: true },
    { route: 'audit', label: 'Activity log', icon: 'log', colour: '--leaf', mod: true }
  ];

  const counts = { needs: 0, mod: 0 };

  function themeNow() {
    const t = document.documentElement.getAttribute('data-theme');
    if (t) return t;
    return window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
  }

  function toggleTheme() {
    const next = themeNow() === 'dark' ? 'light' : 'dark';
    document.documentElement.setAttribute('data-theme', next);
    try { localStorage.setItem('sikho-theme', next); } catch (e) { /* storage blocked */ }
    render();
  }

  function shell(view) {
    const me = state.me;
    const page = state.route.split('/')[0] || 'home';
    const side = h('nav', { class: 'side', 'aria-label': 'Main' },
      h('div', { class: 'brand' }, brandMark(), h('div', null, h('div', { class: 'brand-name', text: 'Sikho-Sikhao' }),
        h('div', { class: 'brand-hi', text: 'सीखो-सिखाओ · Ranchi' }))),
      h('div', { class: 'nav' }, NAV.filter((n) => !n.mod || me.role === 'MODERATOR').map((n) => n.group
        ? h('div', { class: 'nav-group', text: n.group })
        : h('a', { href: '#' + n.route, class: page === n.route ? 'on' : '', style: { '--nav-patch': 'var(' + n.colour + ')' },
          'aria-current': page === n.route ? 'page' : null },
          icon(n.icon), n.label, n.count && counts[n.count] ? h('span', { class: 'count', text: counts[n.count] }) : null))),
      h('a', { class: 'side-wallet', href: '#wallet' },
        h('div', { class: 'row between' }, h('span', { class: 'hand', text: 'Time credits' }), h('span', { class: 'big', text: me.balance })),
        buttonsRow(me.balance, 0, 12)),
      h('div', { class: 'side-foot' },
        h('button', { type: 'button', onclick: toggleTheme, text: themeNow() === 'dark' ? '☀ Day quilt' : '☾ Lantern night' }),
        h('button', { type: 'button', text: 'Sign out', onclick: async () => {
          try { await post('/api/logout'); } catch (e) { /* ignore */ }
          state.me = null; location.hash = ''; render();
        } })));
    const q = h('input', { type: 'search', placeholder: 'Search skills: guitar, khana, coding, yoga…', 'aria-label': 'Search skills' });
    const top = h('header', { class: 'top' },
      h('button', { class: 'btn ghost small menu-btn', type: 'button', 'aria-label': 'Menu', onclick: () => side.classList.toggle('open') }, icon('menu')),
      h('form', { role: 'search', onsubmit: (e) => { e.preventDefault(); location.hash = 'explore?q=' + encodeURIComponent(q.value.trim()); } }, q),
      h('a', { class: 'bell', href: '#notes', 'aria-label': 'Notifications' }, icon('bell'), me.unread ? h('span', { class: 'dot', text: me.unread }) : null),
      h('a', { class: 'who', href: '#settings' }, avatar(me, 'sm'), h('span', { text: me.name.split(' ')[0] })));
    return h('div', { class: 'shell' }, side, h('div', { class: 'main' }, top, h('main', { id: 'view', tabindex: '-1' }, view)));
  }

  const ROUTES = {};

  async function render() {
    clear(modalRoot);
    if (!state.me) { clear(app).appendChild(welcomePage()); return; }
    state.route = decodeURIComponent(location.hash.replace(/^#/, '')) || 'home';
    const [page, rest] = splitRoute(state.route);
    const fn = ROUTES[page] || ROUTES.home;
    const view = h('div', null, h('p', { class: 'hand muted', text: 'Threading the needle…' }));
    clear(app).appendChild(shell(view));
    try {
      const node = await fn(rest);
      clear(view).appendChild(node);
    } catch (e) {
      clear(view).appendChild(h('div', { class: 'note-banner bad' }, h('div', null, h('strong', { text: 'Could not open this page. ' }), e.message)));
    }
    window.scrollTo(0, 0);
    refreshCounts();
  }

  function splitRoute(r) {
    const [path, query] = r.split('?');
    const parts = path.split('/');
    const params = {};
    if (query) for (const kv of query.split('&')) { const [k, v] = kv.split('='); params[k] = decodeURIComponent(v || ''); }
    return [parts[0], { id: parts[1], sub: parts[2], params }];
  }

  let countsBusy = false;
  async function refreshCounts() {
    if (countsBusy) return;
    countsBusy = true;
    try {
      const me = await api('/api/me');
      if (me.user) {
        const changed = me.user.balance !== state.me.balance || me.user.unread !== state.me.unread;
        state.me = me.user;
        const ex = await api('/api/exchanges?tab=requests');
        const act = await api('/api/exchanges?tab=active');
        const needs = ex.items.concat(act.items).filter((x) => x.needsMe).length;
        let mod = 0;
        if (state.me.role === 'MODERATOR') { const m = await api('/api/mod'); mod = m.stats.openReports + m.stats.disputes; }
        if (changed || needs !== counts.needs || mod !== counts.mod) {
          counts.needs = needs; counts.mod = mod;
          const side = document.querySelector('.side');
          if (side) {
            for (const a of side.querySelectorAll('.nav a')) {
              const n = NAV.find((x) => '#' + x.route === a.getAttribute('href'));
              const old = a.querySelector('.count');
              if (old) old.remove();
              if (n && n.count && counts[n.count]) a.appendChild(h('span', { class: 'count', text: counts[n.count] }));
            }
            const big = side.querySelector('.side-wallet .big');
            if (big) big.textContent = state.me.balance;
            const row = side.querySelector('.side-wallet .buttons-row');
            if (row) row.replaceWith(buttonsRow(state.me.balance, 0, 12));
          }
          const bell = document.querySelector('.bell');
          if (bell) { const d = bell.querySelector('.dot'); if (d) d.remove(); if (state.me.unread) bell.appendChild(h('span', { class: 'dot', text: state.me.unread })); }
        }
      }
    } catch (e) { /* not important */ } finally { countsBusy = false; }
  }

  window.addEventListener('hashchange', render);

  // ------------------------------------------------------------------ sign in / join

  function welcomePage() {
    const how = [
      ['1 · Teach', 'Share what you know — guitar, Excel, dhuska, yoga, Sohrai painting.', '--madder'],
      ['2 · Earn time', 'Every hour you teach earns one time credit. Everyone’s hour is worth the same.', '--turmeric'],
      ['3 · Learn', 'Spend credits to learn from neighbours within walking distance.', '--leaf']
    ];
    const signIn = h('div');
    const join = h('div', { class: 'hidden' });
    const tabs = h('div', { class: 'tabs', role: 'tablist' });
    const tabA = h('button', { type: 'button', class: 'on', role: 'tab', text: 'Sign in' });
    const tabB = h('button', { type: 'button', role: 'tab', text: 'Join the community' });
    const show = (a) => { tabA.className = a ? 'on' : ''; tabB.className = a ? '' : 'on'; signIn.classList.toggle('hidden', !a); join.classList.toggle('hidden', a); };
    tabA.onclick = () => show(true);
    tabB.onclick = () => show(false);
    tabs.append(tabA, tabB);

    const u = h('input', { type: 'text', autocomplete: 'username', required: true });
    const p = h('input', { type: 'password', autocomplete: 'current-password', required: true });
    const go = h('button', { class: 'btn primary', type: 'submit', text: 'Sign in' });
    signIn.appendChild(h('form', { onsubmit: async (e) => {
      e.preventDefault();
      const r = await act(go, () => post('/api/login', { username: u.value.trim(), password: p.value }));
      if (r) { state.me = r.user; state.meta = r.meta; if (!location.hash) location.hash = 'home'; render(); }
    } }, field('Username', u), field('Password', p), h('div', { class: 'row between' }, go,
      h('span', { class: 'tiny', text: 'Locked for 5 minutes after 5 wrong tries.' }))));
    const demo = [['ananya', 'member123', 'teaches guitar'], ['ravi', 'member123', 'Excel, wants guitar'], ['meera', 'member123', 'new member'], ['admin', 'admin123', 'moderator']];
    signIn.appendChild(h('div', { class: 'demo-logins stack', style: { 'margin-top': '16px' } },
      h('div', { class: 'tiny', text: 'Demo accounts (all people are made up) — click to fill:' }),
      h('div', { class: 'chips' }, demo.map(([n, pw, what]) => h('button', { type: 'button', onclick: () => { u.value = n; p.value = pw; go.focus(); } },
        h('strong', { text: n }), ' · ' + what)))));

    const jn = h('input', { type: 'text', maxlength: 60, autocomplete: 'name' });
    const ju = h('input', { type: 'text', maxlength: 30, autocomplete: 'username' });
    const jp = h('input', { type: 'password', autocomplete: 'new-password' });
    const jl = select([{ value: '', label: 'Choose your locality…' }].concat(state.meta ? localityOptions() : []), '');
    const jph = h('input', { type: 'tel', placeholder: 'Optional – shown only after you accept an exchange' });
    const agree = h('input', { type: 'checkbox' });
    const jb = h('button', { class: 'btn primary', type: 'submit', text: 'Join and get ' + ((state.meta && state.meta.welcome) || 3) + ' free credits' });
    join.appendChild(h('form', { onsubmit: async (e) => {
      e.preventDefault();
      const r = await act(jb, () => post('/api/join', { name: jn.value, username: ju.value, password: jp.value, localityId: jl.value, phone: jph.value, agree: agree.checked }));
      if (r) { state.me = r.user; state.meta = r.meta; location.hash = 'mine'; render(); toast('Welcome to Sikho-Sikhao!'); }
    } }, field('Your name', jn), field('Username', ju, '3–30 letters or digits'), field('Password', jp, '8+ characters with a letter and a number'),
      field('Locality', jl), field('Mobile number', jph),
      h('label', { class: 'check' }, agree, h('span', null, 'I will be kind, turn up on time, meet in public places first, and never ask for money. ',
        h('span', { class: 'tiny', text: 'Lessons are paid only with time credits.' }))),
      h('div', { style: { 'margin-top': '12px' } }, jb)));

    return h('div', { class: 'welcome' },
      h('section', { class: 'quilt' },
        h('div', { class: 'row' }, brandMark(), h('div', null, h('div', { class: 'brand-name', text: 'Sikho-Sikhao' }), h('div', { class: 'hand', text: 'सीखो-सिखाओ · learn & teach' }))),
        h('h1', { text: 'Exchange skills with your neighbours.' }),
        h('p', { class: 'lead', text: 'A time bank for Ranchi: teach what you know, learn what you love, and pay in time — never in money. One hour of anybody’s teaching is worth one time credit.' }),
        h('div', { class: 'quilt-blocks' }, how.map(([t, d, c]) => h('div', { class: 'patch colour', style: { '--c': 'var(' + c + ')' } }, h('h3', { text: t }), h('p', { class: 'small', text: d })))),
        h('p', { class: 'hand', text: 'Like a kantha quilt: many small pieces from many homes, stitched into something warm.' })),
      h('section', { class: 'form-side' }, h('div', { class: 'patch' }, tabs, signIn, join)));
  }

  // ------------------------------------------------------------------ home ("My patch")

  ROUTES.home = async () => {
    const d = await api('/api/home');
    const me = d.me;
    const out = h('div');
    out.appendChild(h('div', { class: 'page-head' },
      h('div', null, h('div', { class: 'hand', text: 'Namaste,' }), h('h1', { text: me.name }),
        h('div', { class: 'muted' }, me.locality + ' · travels up to ' + me.maxKm + ' km · ', d.trust.reviews ? [stars(d.trust.rating), ' ' + d.trust.rating.toFixed(1) + ' from ' + plural(d.trust.reviews, 'review')] : 'no reviews yet')),
      h('div', { class: 'patch tint', style: { '--c': 'var(--turmeric)', 'min-width': '260px' } },
        h('div', { class: 'row between' }, h('span', { class: 'hand', text: 'Your time credits' }), h('a', { href: '#wallet', class: 'small', text: 'Wallet →' })),
        h('div', { class: 'row' }, h('span', { class: 'stat' }, h('span', { class: 'v', text: me.balance })), buttonsRow(me.balance, d.held, 10)),
        d.held ? h('div', { class: 'tiny', text: d.held + ' more waiting in escrow for sessions you booked' }) : null)));

    // needs you
    const needs = [];
    for (const x of d.waiting) needs.push(exchangeItem(x, 'Wants your answer', [
      h('button', { class: 'btn green small', text: 'Accept', onclick: async (e) => { e.preventDefault(); if (await act(e.target, () => post('/api/exchanges/' + x.id + '/accept'), 'Accepted! You can now see each other’s number.')) render(); } }),
      h('a', { class: 'btn ghost small', href: '#exchange/' + x.id, text: 'Open' })]));
    for (const x of d.toConfirm) needs.push(exchangeItem(x, 'Did it happen?', [
      h('button', { class: 'btn green small', text: 'Yes, done', onclick: async (e) => { e.preventDefault(); if (await act(e.target, () => post('/api/exchanges/' + x.id + '/done'), 'Thank you!')) render(); } }),
      h('a', { class: 'btn ghost small', href: '#exchange/' + x.id, text: 'Open' })]));
    for (const x of d.toReview) needs.push(exchangeItem(x, 'Leave a review', [h('a', { class: 'btn small primary', href: '#exchange/' + x.id, text: 'Review' })]));
    if (needs.length) { out.appendChild(sectionTitle('Needs you')); out.appendChild(h('div', { class: 'list' }, needs)); }

    const left = h('div'), right = h('div');
    out.appendChild(h('div', { class: 'split', style: { 'margin-top': '6px' } }, left, right));

    left.appendChild(sectionTitle('Coming up'));
    left.appendChild(d.upcoming.length ? h('div', { class: 'list' }, d.upcoming.map((x) => exchangeItem(x))) :
      empty('Nothing booked yet.', h('a', { href: '#explore', text: 'Find someone to learn from →' })));

    left.appendChild(sectionTitle('Matches for what you want to learn', h('a', { href: '#mine', class: 'small', text: 'Edit wants' })));
    if (!d.suggestions.length) left.appendChild(empty('Add an “I want to learn” card and we will find neighbours who teach it.', h('a', { href: '#mine', text: 'Add a card →' })));
    for (const sg of d.suggestions) {
      left.appendChild(h('h3', { class: 'hand', style: { 'margin-top': '10px' }, text: 'For “' + sg.want.skill + '”' }));
      left.appendChild(sg.matches.length ? h('div', { class: 'cards' }, sg.matches.map(listingCard)) :
        empty('No good match nearby yet. Try a bigger travel distance or ask in a skill circle.'));
    }

    right.appendChild(sectionTitle('Neighbours who want what you teach'));
    if (!d.demand.length) right.appendChild(empty('Add an “I can teach” card — everybody knows something.', h('a', { href: '#mine', text: 'Add a card →' })));
    for (const dm of d.demand) {
      right.appendChild(h('div', { class: 'patch', style: { 'margin-bottom': '12px' } },
        h('div', { class: 'row between' }, h('h3', { text: dm.skill }), chip(plural(dm.count, 'learner') + ' nearby', dm.count ? 'ok' : '')),
        dm.wants.length ? h('div', { class: 'list' }, dm.wants.map((w) => h('a', { class: 'item', href: '#listing/' + w.id },
          avatar(w.owner, 'sm'), h('div', { class: 'body' }, h('div', { class: 'title', text: w.owner.name }), h('div', { class: 'sub', text: (w.details || w.title) + ' · ' + w.owner.km + ' km' }))))) :
          h('p', { class: 'small muted', text: 'Nobody nearby has asked yet. Hosting a skill circle is a great way to start.' }),
        dm.count > 3 ? h('a', { class: 'small', href: '#explore?type=WANT&skillId=' + encodeURIComponent((state.meta.skills.find((x) => x.name === dm.skill) || {}).id || ''), text: 'See all ' + dm.count + ' →' }) : null));
    }
    const sw = d.swaps;
    right.appendChild(h('a', { class: 'patch colour', href: '#swaps', style: { '--c': 'var(--rose)', display: 'block', 'text-decoration': 'none', 'margin-top': '16px' } },
      h('h3', { text: 'Swaps for you' }),
      h('p', { class: 'small', text: sw.pairCount + sw.circleCount === 0 ? 'No swaps yet — add more cards to find one.'
        : 'You are in ' + plural(sw.pairCount, 'direct swap') + ' and ' + plural(sw.circleCount, 'swap circle') + '. Teach and learn without spending credits →' })));

    right.appendChild(sectionTitle('Skill circles near you'));
    right.appendChild(d.circles.length ? h('div', { class: 'list' }, d.circles.map((c) => h('a', { class: 'item', href: '#circles' },
      dateTag(c.at, catClass(c.skill.category)), h('div', { class: 'body' }, h('div', { class: 'title', text: c.title }),
        h('div', { class: 'sub', text: timeOnly(c.at) + ' · ' + c.place + ' · ' + c.taken + '/' + c.seats + ' seats' })))))
      : empty('No circles planned nearby.', h('a', { href: '#circles', text: 'Host one →' })));

    right.appendChild(sectionTitle('Latest news', h('a', { href: '#notes', class: 'small', text: 'All' })));
    right.appendChild(d.notes.length ? h('div', { class: 'list' }, d.notes.slice(0, 5).map(noteItem)) : empty('Nothing new.'));
    out.appendChild(h('p', { class: 'tiny center', style: { 'margin-top': '30px' }, text: d.community.members + ' neighbours · ' + d.community.hoursMonth + ' hours shared in the last 30 days' }));
    return out;
  };

  function noteItem(n) {
    return h('a', { class: 'item' + (n.read ? '' : ' needs'), href: '#' + (n.link || 'home') },
      h('div', { class: 'body' }, h('div', { text: n.text }), h('div', { class: 'tiny', text: ago(n.at) })));
  }

  function exchangeItem(x, label, actions) {
    const iTeach = x.myRole === 'TEACHER';
    const partner = iTeach ? x.learner : x.teacher;
    const cat = x.skill ? catClass(x.skill.category) : '';
    return h('a', { class: 'item' + (label || x.needsMe ? ' needs' : ''), href: '#exchange/' + x.id },
      dateTag(x.at, cat),
      h('div', { class: 'body' },
        h('div', { class: 'title' }, (iTeach ? 'Teaching ' : 'Learning ') + (x.skill ? x.skill.name : ''), ' ', chip(STATUS[x.status] || x.status, 'st-' + x.status)),
        h('div', { class: 'sub', text: (iTeach ? 'to ' : 'from ') + (partner ? partner.name : '?') + ' · ' + timeOnly(x.at) + ' · ' + plural(x.hours, 'hour') + (x.place ? ' · ' + x.place : '') }),
        label ? h('div', { class: 'hand', style: { color: 'var(--thread)' }, text: label }) : (x.last ? h('div', { class: 'sub tiny', text: '“' + x.last + '”' }) : null)),
      actions ? h('div', { class: 'row', onclick: (e) => { if (e.target.tagName === 'BUTTON') e.preventDefault(); } }, actions) : null);
  }

  /** A card on the board as a cloth patch. */
  function listingCard(l) {
    const cat = l.skill ? catClass(l.skill.category) : '';
    const why = l.parts ? h('details', { class: 'small' }, h('summary', { text: 'Why this match?' }), whyBox(l.parts)) : null;
    return h('article', { class: 'patch ' + cat },
      h('span', { class: 'strip' }),
      h('div', { class: 'row between', style: { 'align-items': 'flex-start', 'margin-top': '6px' } },
        h('div', { style: { 'min-width': '0', flex: '1' } },
          h('div', { class: 'chips' }, chip(l.skill ? l.skill.name : '', 'solid'), chip(l.type === 'OFFER' ? 'Teaches' : 'Wants to learn')),
          h('h3', { style: { 'margin-top': '8px' } }, h('a', { href: '#listing/' + l.id, text: l.title, style: { color: 'var(--ink)' } }))),
        l.score !== undefined ? hoop(l.score, 58) : null),
      l.details ? h('p', { class: 'small muted', text: l.details.length > 120 ? l.details.slice(0, 117) + '…' : l.details }) : null,
      h('div', { class: 'chips', style: { 'margin-bottom': '10px' } }, chip(LEVEL[l.level]), chip(MODE[l.mode])),
      l.owner ? personLine(l.owner) : null,
      why);
  }

  // ------------------------------------------------------------------ explore

  ROUTES.explore = async (r) => {
    const p = r.params;
    const type = p.type === 'WANT' ? 'WANT' : 'OFFER';
    const q = h('input', { type: 'search', value: p.q || '', placeholder: 'What do you want to ' + (type === 'OFFER' ? 'learn' : 'teach') + '?' });
    const cat = select([{ value: '', label: 'All categories' }].concat(state.meta.categories.map((c) => ({ value: c, label: c }))), p.category || '');
    const skill = select(skillOptions('Any skill'), p.skillId || '');
    const km = h('input', { type: 'range', min: 1, max: 15, step: 1, value: p.km || state.me.maxKm });
    const kmLabel = h('strong', { text: (p.km || state.me.maxKm) + ' km' });
    km.addEventListener('input', () => { kmLabel.textContent = km.value + ' km'; });
    const mode = select([{ value: '', label: 'In person or online' }, { value: 'IN_PERSON', label: 'In person only' }, { value: 'ONLINE', label: 'Online only' }], p.mode || '');
    const level = select([{ value: '', label: 'Any level' }, { value: 'BEGINNER', label: 'I am a beginner' }, { value: 'INTERMEDIATE', label: 'I know some' }, { value: 'EXPERT', label: 'I am advanced' }], p.level || '');
    const results = h('div', null, h('p', { class: 'hand muted', text: 'Looking through the quilt…' }));

    const go = () => {
      const parts = ['type=' + type];
      if (q.value.trim()) parts.push('q=' + encodeURIComponent(q.value.trim()));
      if (cat.value) parts.push('category=' + encodeURIComponent(cat.value));
      if (skill.value) parts.push('skillId=' + skill.value);
      if (Number(km.value) !== state.me.maxKm) parts.push('km=' + km.value);
      if (mode.value) parts.push('mode=' + mode.value);
      if (level.value && type === 'OFFER') parts.push('level=' + level.value);
      location.hash = 'explore?' + parts.join('&');
    };
    for (const el of [cat, skill, mode, level, km]) el.addEventListener('change', go);

    const query = new URLSearchParams();
    query.set('type', type);
    for (const k of ['q', 'category', 'skillId', 'km', 'mode', 'level']) if (p[k]) query.set(k, p[k]);
    api('/api/search?' + query.toString()).then((d) => {
      clear(results);
      const fixes = Object.entries(d.corrected || {});
      if (fixes.length) results.appendChild(h('p', { class: 'hand', style: { color: 'var(--thread)' }, text: 'Showing results for: ' + fixes.map(([a, b]) => a + ' → ' + b).join(', ') }));
      results.appendChild(h('p', { class: 'small muted', text: plural(d.results.length, type === 'OFFER' ? 'teacher card' : 'learner card') + ' within ' + d.km + ' km (online cards from anywhere), best match first.' }));
      results.appendChild(d.results.length ? h('div', { class: 'cards' }, d.results.map(listingCard))
        : empty('No cards match. Try fewer words, another category or a bigger distance.'));
    }).catch((e) => { clear(results).appendChild(h('div', { class: 'note-banner bad', text: e.message })); });

    return h('div', null,
      h('div', { class: 'page-head' }, h('div', null, h('div', { class: 'hand', text: 'Find skills' }), h('h1', { text: type === 'OFFER' ? 'Learn from a neighbour' : 'Teach a neighbour' }))),
      h('div', { class: 'tabs' },
        h('a', { href: '#explore?type=OFFER', class: type === 'OFFER' ? 'on' : '', text: 'People who teach' }),
        h('a', { href: '#explore?type=WANT', class: type === 'WANT' ? 'on' : '', text: 'People who want to learn' })),
      h('form', { class: 'patch flat', style: { 'margin-bottom': '18px' }, onsubmit: (e) => { e.preventDefault(); go(); } },
        h('div', { class: 'grid g4' }, field('Search', q, 'Hindi words work too'), field('Category', cat), field('Skill', skill),
          type === 'OFFER' ? field('My level', level) : field('How', mode)),
        h('div', { class: 'grid g4' }, h('label', { class: 'field' }, h('span', null, 'Distance: ', kmLabel), km), type === 'OFFER' ? field('How', mode) : h('span'), h('span'),
          h('div', { class: 'row', style: { 'align-items': 'flex-end' } }, h('button', { class: 'btn primary', type: 'submit', text: 'Search' })))),
      results);
  };

  // ------------------------------------------------------------------ one card

  ROUTES.listing = async (r) => {
    const l = await api('/api/listings/' + encodeURIComponent(r.id));
    const cat = l.skill ? catClass(l.skill.category) : '';
    const o = l.owner;
    const isOffer = l.type === 'OFFER';
    const head = h('div', { class: 'patch ' + cat },
      h('span', { class: 'strip' }),
      h('div', { class: 'row between', style: { 'align-items': 'flex-start', 'margin-top': '8px' } },
        h('div', { style: { flex: '1', 'min-width': '0' } },
          h('div', { class: 'chips' }, chip(l.skill.name, 'solid'), chip(l.skill.category), chip(isOffer ? 'Teaches' : 'Wants to learn'), l.active ? null : chip('Paused', 'warn')),
          h('h1', { style: { 'margin-top': '10px' }, text: l.title }),
          l.details ? h('p', { text: l.details }) : null,
          h('div', { class: 'chips' }, chip((isOffer ? 'Level: ' : 'Now: ') + LEVEL[l.level]), chip(MODE[l.mode]), chip(PLACE[l.place]))),
        l.score !== undefined ? h('div', { class: 'center' }, hoop(l.score, 90), h('div', { class: 'tiny', text: 'match for you' })) : null));

    const side = h('div', { class: 'stack' });
    side.appendChild(h('div', { class: 'patch' }, h('h3', { text: isOffer ? 'Teacher' : 'Learner' }), personLine(o),
      o.bio ? h('p', { class: 'small', style: { 'margin-top': '10px' }, text: o.bio }) : null,
      h('div', { class: 'row small muted' }, o.trust.completed + ' sessions · ' + o.trust.reliability + '% reliable', o.languages ? ' · ' + o.languages : '')));

    if (l.mine) {
      side.appendChild(h('div', { class: 'note-banner info' }, h('div', null, 'This is your card. ', h('a', { href: '#mine', text: 'Edit it in My cards →' }))));
    } else {
      side.appendChild(requestForm(l));
    }

    const main = h('div', { class: 'stack' }, head);
    if (l.parts) main.appendChild(h('div', { class: 'patch' }, h('h3', { text: 'Why this match' }), whyBox(l.parts)));
    main.appendChild(h('div', { class: 'patch' }, h('h3', { text: o.firstName + '’s free times' }),
      h('p', { class: 'tiny', text: 'Red = you are both free. Green = only ' + o.firstName + '.' }),
      slotGrid(l.ownerAvailability, { shared: l.sharedSlots })));
    if (l.myOpen && l.myOpen.length) main.appendChild(h('div', { class: 'note-banner info' }, h('div', null, 'You already have ',
      l.myOpen.map((x, i) => [i ? ', ' : '', h('a', { href: '#exchange/' + x.id, text: x.id + ' (' + (STATUS[x.status] || x.status).toLowerCase() + ')' })]), ' on this card.')));
    if (l.similar && l.similar.length) {
      main.appendChild(sectionTitle('Similar cards nearby'));
      main.appendChild(h('div', { class: 'cards' }, l.similar.map(listingCard)));
    }
    return h('div', null, h('p', null, h('button', { type: 'button', class: 'btn ghost small', text: '← Back', onclick: () => history.back() })), h('div', { class: 'split' }, main, side));
  };

  function requestForm(l) {
    const isOffer = l.type === 'OFFER';
    const hours = select([1, 2, 3].map((n) => ({ value: String(n), label: plural(n, 'hour') + ' = ' + plural(n, 'credit') })), '1');
    const when = h('input', { type: 'datetime-local', value: defaultWhen(2, 18) });
    const place = h('input', { type: 'text', maxlength: 120, placeholder: l.mode === 'ONLINE' ? 'Online (video call)' : 'Leave empty for the usual place' });
    const msg = h('textarea', { maxlength: 1000, placeholder: isOffer ? 'Say hello: what you know already and what you hope to learn.' : 'Say hello: what you can teach and how.' });
    const btn = h('button', { class: 'btn primary', type: 'submit', text: isOffer ? 'Ask to learn' : 'Offer to teach' });
    const balanceNote = h('p', { class: 'tiny' });
    const update = () => {
      const n = Number(hours.value);
      balanceNote.textContent = isOffer
        ? 'You have ' + l.balance + ' credits. ' + plural(n, 'credit') + ' will wait safely in escrow and go to ' + l.owner.firstName + ' only after you both say the session happened.'
        : l.owner.firstName + ' pays ' + plural(n, 'credit') + ' to you when you both confirm the session.';
      btn.disabled = isOffer && n > l.balance;
    };
    hours.addEventListener('change', update);
    update();
    return h('form', { class: 'patch tint', style: { '--c': 'var(--turmeric)' }, onsubmit: async (e) => {
      e.preventDefault();
      const r = await act(btn, () => post('/api/listings/' + l.id + '/request', { hours: Number(hours.value), at: localToMillis(when.value), place: place.value, message: msg.value }));
      if (r) { toast('Request sent to ' + l.owner.firstName + '!'); location.hash = 'exchange/' + r.id; }
    } }, h('h3', { text: isOffer ? 'Ask ' + l.owner.firstName + ' to teach you' : 'Offer to teach ' + l.owner.firstName }),
      field('How long', hours), field('When', when, 'India time'), field('Where', place), field('Message', msg), balanceNote, btn,
      h('p', { class: 'tiny', style: { 'margin-top': '8px' }, text: 'Safety: phone numbers stay hidden until the request is accepted. Meet in a public place the first time.' }));
  }

  // ------------------------------------------------------------------ member profile

  ROUTES.member = async (r) => {
    const m = await api('/api/members/' + encodeURIComponent(r.id));
    const t = m.trust;
    const BADGE_COLOURS = { 'Neighbourhood hero': '--madder', 'Helping hand': '--leaf', 'Lifelong learner': '--indigo', 'Circle host': '--teal', 'Trusted teacher': '--turmeric', 'New neighbour': '--rose' };
    const head = h('div', { class: 'patch' },
      h('div', { class: 'row', style: { gap: '22px', 'align-items': 'flex-start' } }, avatar(m, 'big'),
        h('div', { class: 'grow' },
          h('h1', { text: m.name }),
          h('div', { class: 'muted' }, m.locality, m.km !== undefined ? ' · ' + m.km + ' km from you' : '', ' · joined ' + dateOnly(m.joined), m.role === 'MODERATOR' ? [' · ', chip('Moderator', 'ok')] : null),
          m.bio ? h('p', { style: { 'margin-top': '8px' }, text: m.bio }) : null,
          m.languages ? h('div', { class: 'small muted', text: 'Speaks ' + m.languages }) : null,
          m.badges.length ? h('div', { class: 'chips', style: { 'margin-top': '10px' } }, m.badges.map((b) => h('span', { class: 'badge-patch', title: b.why, style: { '--c': 'var(' + (BADGE_COLOURS[b.name] || '--turmeric') + ')' } }, '✿ ', b.name))) : null),
        h('div', { class: 'grid g2', style: { 'min-width': '240px' } },
          h('div', { class: 'stat' }, h('span', { class: 'v', text: t.reviews ? t.rating.toFixed(1) : '–' }), h('span', { class: 'k' }, t.reviews ? stars(t.rating) : 'no reviews', ' ' + t.reviews)),
          h('div', { class: 'stat' }, h('span', { class: 'v', text: t.reliability + '%' }), h('span', { class: 'k', text: 'turns up (' + t.completed + ' done)' })),
          h('div', { class: 'stat' }, h('span', { class: 'v', text: m.stats.taught }), h('span', { class: 'k', text: 'hours taught' })),
          h('div', { class: 'stat' }, h('span', { class: 'v', text: m.stats.learned }), h('span', { class: 'k', text: 'hours learned' })))));

    const actions = h('div', { class: 'row' });
    if (!m.isMe) {
      actions.appendChild(h('button', { class: 'btn ghost small', text: m.blockedByMe ? 'Unblock' : 'Block', onclick: (e) => {
        if (m.blockedByMe) { act(e.target, () => post('/api/members/' + m.id + '/block', { block: false }), 'Unblocked').then((x) => { if (x) render(); }); return; }
        confirmBox('Block ' + m.firstName + '?', 'You will not see each other’s cards and cannot send each other requests or messages. They are not told.', 'Block',
          () => post('/api/members/' + m.id + '/block', { block: true }).then((x) => { render(); return x; }));
      } }));
      actions.appendChild(h('button', { class: 'btn danger small', text: 'Report', onclick: () => reportBox(m.id, m.firstName, null) }));
    } else actions.appendChild(h('a', { class: 'btn small', href: '#settings', text: 'Edit my profile' }));

    const out = h('div', { class: 'stack' }, head, actions);
    if (m.mod) out.appendChild(modPanel(m));
    const left = h('div', { class: 'stack' }), right = h('div', { class: 'stack' });
    out.appendChild(h('div', { class: 'split' }, left, right));
    left.appendChild(sectionTitle('Can teach'));
    left.appendChild(m.offers.length ? h('div', { class: 'cards' }, m.offers.map(listingCard)) : empty(m.firstName + ' has not added any yet.'));
    left.appendChild(sectionTitle('Wants to learn'));
    left.appendChild(m.wants.length ? h('div', { class: 'cards' }, m.wants.map(listingCard)) : empty('Nothing yet.'));
    right.appendChild(h('div', { class: 'patch' }, h('h3', { text: 'Free times' }), slotGrid(m.availability), h('p', { class: 'tiny', style: { 'margin-top': '8px' }, text: 'Travels up to ' + m.maxKm + ' km.' })));
    right.appendChild(sectionTitle('Reviews'));
    right.appendChild(m.reviews.length ? h('div', { class: 'list' }, m.reviews.map(reviewItem)) : empty('No reviews yet.'));
    return out;
  };

  function reviewItem(rv) {
    return h('div', { class: 'item' }, h('div', { class: 'body' },
      h('div', { class: 'row between' }, stars(rv.stars), h('span', { class: 'tiny', text: ago(rv.at) })),
      rv.text ? h('div', { text: '“' + rv.text + '”' }) : null,
      h('div', { class: 'tiny' }, '— ', h('a', { href: '#member/' + rv.fromId, text: rv.from }), ', ' + (rv.aboutTeacher ? 'learned ' : 'taught ') + (rv.skill || '')),
      rv.tags && rv.tags.length ? h('div', { class: 'chips', style: { 'margin-top': '4px' } }, rv.tags.map((x) => chip(x))) : null));
  }

  function reportBox(targetId, name, exchangeId) {
    const reason = select(Object.entries(REASONS).map(([v, l]) => ({ value: v, label: l })), 'NO_SHOW');
    const text = h('textarea', { maxlength: 800, placeholder: 'What happened? Only moderators see this.' });
    let close;
    const send = h('button', { class: 'btn primary', text: 'Send to moderators', onclick: async () => {
      const r = await act(send, () => post('/api/reports', { targetId, exchangeId, reason: reason.value, text: text.value }), 'Thank you. A moderator will look at it.');
      if (r) close();
    } });
    close = modal('Report ' + name, h('div', null, field('Reason', reason), field('Details', text),
      h('p', { class: 'tiny', text: 'If you are in danger, call 112 first.' })), [h('button', { class: 'btn ghost', text: 'Cancel', onclick: () => close() }), send]);
  }

  function modPanel(m) {
    const d = m.mod;
    const box = h('div', { class: 'patch tint', style: { '--c': 'var(--indigo)' } },
      h('h3', { text: 'Moderator tools' }),
      h('div', { class: 'row small' }, 'Username: ', h('strong', { text: d.username }), ' · balance ' + d.balance + ' · ' + plural(d.warnings, 'warning'),
        d.suspended ? [' · ', chip('Paused: ' + (d.suspendReason || ''), 'bad')] : null));
    const row = h('div', { class: 'row', style: { 'margin-top': '10px' } });
    if (d.suspended) row.appendChild(h('button', { class: 'btn small green', text: 'Reinstate', onclick: (e) => act(e.target, () => post('/api/members/' + m.id + '/reinstate'), 'Account active again').then((x) => { if (x) render(); }) }));
    else if (!m.isMe) row.appendChild(h('button', { class: 'btn small danger', text: 'Pause account', onclick: () => confirmBox('Pause ' + m.name + '?',
      'They are signed out, their cards are hidden, and their open exchanges and circles are cancelled with credits returned. They will see your reason.', 'Pause',
      (reason) => post('/api/members/' + m.id + '/suspend', { reason }).then((x) => { render(); return x; }), 'Reason (the member will see it)') }));
    row.appendChild(h('button', { class: 'btn small', text: 'Correct credits', onclick: () => {
      const amt = h('input', { type: 'number', min: -10, max: 10, value: 1 });
      const why = h('input', { type: 'text', maxlength: 200, placeholder: 'Reason (kept in the ledger for ever)' });
      let close;
      const ok = h('button', { class: 'btn primary', text: 'Save', onclick: async () => { const x = await act(ok, () => post('/api/members/' + m.id + '/adjust', { amount: Number(amt.value), reason: why.value }), 'Corrected'); if (x) { close(); render(); } } });
      close = modal('Correct ' + m.firstName + '’s credits', h('div', null, field('Amount (+ adds, − removes)', amt), field('Reason', why)), [h('button', { class: 'btn ghost', text: 'Cancel', onclick: () => close() }), ok]);
    } }));
    if (!m.isMe && !d.suspended) row.appendChild(h('button', { class: 'btn small ghost', text: m.role === 'MODERATOR' ? 'Make member' : 'Make moderator',
      onclick: (e) => act(e.target, () => post('/api/members/' + m.id + '/role', { role: m.role === 'MODERATOR' ? 'MEMBER' : 'MODERATOR' }), 'Role changed').then((x) => { if (x) render(); }) }));
    box.appendChild(row);
    return box;
  }

  // ------------------------------------------------------------------ my cards

  ROUTES.mine = async () => {
    const d = await api('/api/listings/mine');
    const col = (title, type, items, colour) => h('div', null,
      h('div', { class: 'section-title' }, h('h2', { text: title }), h('button', { class: 'btn small primary', text: '+ Add', onclick: () => cardForm(type) })),
      items.length ? h('div', { class: 'stack' }, items.map((l) => myCard(l))) :
        empty(type === 'OFFER' ? 'Everybody can teach something: a recipe, a language, fixing a cycle puncture…' : 'What have you always wanted to learn?'));
    return h('div', null,
      h('div', { class: 'page-head' }, h('div', null, h('div', { class: 'hand', text: 'My cards on the community board' }), h('h1', { text: 'What I teach & what I want to learn' })),
        h('span', { class: 'tiny', text: 'Up to ' + d.max + ' active cards' })),
      h('div', { class: 'grid g2' }, col('I can teach', 'OFFER', d.offers), col('I want to learn', 'WANT', d.wants)));
  };

  function myCard(l) {
    const cat = l.skill ? catClass(l.skill.category) : '';
    return h('div', { class: 'patch ' + cat, style: l.active ? null : { opacity: '.65' } },
      h('span', { class: 'strip' }),
      h('div', { class: 'row between', style: { 'margin-top': '6px' } }, h('div', { class: 'chips' }, chip(l.skill.name, 'solid'), chip(LEVEL[l.level]), chip(MODE[l.mode]), l.active ? null : chip('Paused', 'warn')),
        l.open ? chip(plural(l.open, 'open exchange'), 'ok') : null),
      h('h3', { style: { 'margin-top': '8px' } }, h('a', { href: '#listing/' + l.id, text: l.title, style: { color: 'var(--ink)' } })),
      l.details ? h('p', { class: 'small muted', text: l.details }) : null,
      h('div', { class: 'row' },
        h('button', { class: 'btn small', text: 'Edit', onclick: () => cardForm(l.type, l) }),
        h('button', { class: 'btn small ghost', text: l.active ? 'Pause' : 'Show again', onclick: (e) => act(e.target, () => put('/api/listings/' + l.id, { active: !l.active }), l.active ? 'Paused' : 'Back on the board').then((x) => { if (x) render(); }) }),
        h('button', { class: 'btn small ghost danger', text: 'Remove', onclick: () => confirmBox('Remove this card?', 'Cards that were used in exchanges are paused instead, so the history stays complete.', 'Remove',
          () => post('/api/listings/' + l.id + '/delete').then((x) => { toast(x.result === 'removed' ? 'Removed' : 'Paused (it has exchanges)'); render(); return x; })) })));
  }

  function cardForm(type, l) {
    const isOffer = type === 'OFFER';
    const skill = select(skillOptions('Choose a skill…'), l ? l.skill.id : '', l ? { disabled: true } : null);
    const title = h('input', { type: 'text', maxlength: 80, value: l ? l.title : '', placeholder: isOffer ? 'e.g. Guitar from zero to your first song' : 'Leave empty for “I want to learn …”' });
    const details = h('textarea', { maxlength: 600, value: l ? (l.details || '') : '', placeholder: isOffer ? 'What will people learn? What should they bring?' : 'What do you know already? What is your goal?' });
    const level = select(Object.entries(LEVEL).map(([v, x]) => ({ value: v, label: x })), l ? l.level : isOffer ? 'INTERMEDIATE' : 'BEGINNER');
    const mode = select(Object.entries(MODE).map(([v, x]) => ({ value: v, label: x })), l ? l.mode : 'IN_PERSON');
    const place = select([{ value: 'PUBLIC', label: 'A public place (library, park, hall)' }, { value: 'MY_PLACE', label: 'At my place' }, { value: 'YOUR_PLACE', label: 'At the other person’s place' }], l ? l.place : 'PUBLIC');
    let close;
    const save = h('button', { class: 'btn primary', text: l ? 'Save' : 'Add card', onclick: async () => {
      const body = { type, skillId: skill.value, title: title.value, details: details.value, level: level.value, mode: mode.value, place: place.value };
      const r = await act(save, () => l ? put('/api/listings/' + l.id, body) : post('/api/listings', body), l ? 'Saved' : 'Your card is on the board!');
      if (r) { close(); render(); }
    } });
    close = modal(l ? 'Edit card' : isOffer ? 'I can teach…' : 'I want to learn…', h('div', null,
      field('Skill', skill, l ? '(cannot change)' : 'missing? ask a moderator'), field('Title', title), field('Details', details),
      h('div', { class: 'grid g2' }, field(isOffer ? 'How well I know it' : 'My level now', level), field('How', mode)), field('Where', place)),
      [h('button', { class: 'btn ghost', text: 'Cancel', onclick: () => close() }), save]);
  }

  // ------------------------------------------------------------------ exchanges

  ROUTES.exchanges = async (r) => {
    const tab = r.id || 'active';
    const d = await api('/api/exchanges?tab=' + tab);
    const tabs = [['active', 'Accepted'], ['requests', 'Requests'], ['past', 'Past']];
    return h('div', null,
      h('div', { class: 'page-head' }, h('div', null, h('div', { class: 'hand', text: 'Sessions with neighbours' }), h('h1', { text: 'Exchanges' }))),
      h('div', { class: 'tabs' }, tabs.map(([k, label]) => h('a', { href: '#exchanges/' + k, class: tab === k ? 'on' : '', text: label + ' (' + d.counts[k] + ')' }))),
      d.items.length ? h('div', { class: 'list' }, d.items.map((x) => exchangeItem(x))) : empty(tab === 'requests' ? 'No open requests.' : tab === 'active' ? 'Nothing accepted yet.' : 'No past exchanges yet.',
        h('a', { href: '#explore', text: 'Find skills →' })));
  };

  ROUTES.exchange = async (r) => {
    const x = await api('/api/exchanges/' + encodeURIComponent(r.id));
    const iTeach = x.myRole === 'TEACHER';
    const partner = iTeach ? x.learner : x.myRole === 'LEARNER' ? x.teacher : null;
    const cat = x.skill ? catClass(x.skill.category) : '';
    const reload = () => render();

    // the thread with knots: requested → accepted → session → completed
    const bad = ['DECLINED', 'CANCELLED', 'EXPIRED', 'NOT_CONFIRMED'].includes(x.status);
    const steps = [['Requested', true], ['Accepted', x.acceptedAt > 0], ['Session', x.started && !bad && x.acceptedAt > 0], ['Completed', x.status === 'COMPLETED']];
    const knots = h('div', { class: 'knots' }, steps.map(([label, done]) => h('div', { class: 'knot' + (done ? ' done' : '') }, h('i'), label)),
      bad ? h('div', { class: 'knot bad' }, h('i'), STATUS[x.status]) : null,
      x.status === 'DISPUTED' ? h('div', { class: 'knot bad' }, h('i'), 'Disputed') : null);

    const info = h('div', { class: 'patch ' + cat },
      h('span', { class: 'strip' }),
      h('div', { class: 'row between', style: { 'margin-top': '8px' } },
        h('div', null, h('div', { class: 'hand', text: x.id + ' · ' + (iTeach ? 'you teach' : x.myRole === 'LEARNER' ? 'you learn' : 'moderator view') }),
          h('h1', { text: x.skill.name + ' · ' + plural(x.hours, 'hour') })),
        chip(STATUS[x.status], 'st-' + x.status)),
      knots,
      h('div', { class: 'grid g2', style: { 'margin-top': '10px' } },
        h('div', null, h('div', { class: 'tiny', text: 'Teacher' }), personLine(x.teacher)),
        h('div', null, h('div', { class: 'tiny', text: 'Learner' }), personLine(x.learner))),
      h('div', { class: 'grid g2', style: { 'margin-top': '14px' } },
        h('div', null, h('div', { class: 'tiny', text: 'When' }), h('strong', { text: dateTime(x.at) }), h('div', { class: 'tiny', text: ago(x.at) })),
        h('div', null, h('div', { class: 'tiny', text: 'Where' }), h('strong', { text: x.place || '—' }))),
      h('div', { class: 'row', style: { 'margin-top': '12px' } }, buttonsRow(x.hours, 0), h('span', { class: 'small', text: creditLine(x) })),
      x.partnerPhone ? h('div', { class: 'note-banner ok', style: { 'margin-top': '12px' } }, h('div', null, partner.firstName + '’s phone: ', h('a', { href: 'tel:' + x.partnerPhone, text: x.partnerPhone }))) : null,
      x.reason ? h('p', { class: 'small muted', style: { 'margin-top': '10px' }, text: 'Note: ' + x.reason + (x.lateCancel ? ' (cancelled less than 24 hours before)' : '') }) : null);

    const buttons = h('div', { class: 'row' });
    const A = x.actions;
    if (A.includes('accept')) buttons.appendChild(h('button', { class: 'btn green', text: x.myRole === 'LEARNER' ? 'Accept (' + plural(x.hours, 'credit') + ' to escrow)' : 'Accept', onclick: (e) => act(e.target, () => post('/api/exchanges/' + x.id + '/accept'), 'Accepted!').then((v) => v && reload()) }));
    if (A.includes('decline')) buttons.appendChild(h('button', { class: 'btn ghost', text: 'Decline', onclick: () => confirmBox('Decline?', 'Any held credits go straight back.', 'Decline', (why) => post('/api/exchanges/' + x.id + '/decline', { reason: why }).then((v) => { reload(); return v; }), 'A kind word for ' + partner.firstName + ' (optional)') }));
    if (A.includes('done')) buttons.appendChild(h('button', { class: 'btn green', text: 'It happened — Done', onclick: (e) => act(e.target, () => post('/api/exchanges/' + x.id + '/done'), 'Thank you!').then((v) => v && reload()) }));
    if (A.includes('cancel')) buttons.appendChild(h('button', { class: 'btn ghost', text: 'Cancel', onclick: () => confirmBox('Cancel this session?',
      x.status === 'ACCEPTED' && x.at - Date.now() < 86400000 ? 'It is less than 24 hours away: this counts as a late cancellation on your reliability.' : 'Any held credits go back to the learner.', 'Cancel session',
      (why) => post('/api/exchanges/' + x.id + '/cancel', { reason: why }).then((v) => { reload(); return v; }), 'Why? (optional)') }));
    if (A.includes('dispute')) buttons.appendChild(h('button', { class: 'btn ghost danger', text: 'It did not happen', onclick: () => {
      const t = h('textarea', { maxlength: 600, placeholder: 'What happened? The moderators and ' + partner.firstName + ' will see this.' });
      let close;
      const ok = h('button', { class: 'btn primary', text: 'Ask a moderator', onclick: async () => { const v = await act(ok, () => post('/api/exchanges/' + x.id + '/dispute', { text: t.value })); if (v) { close(); reload(); } } });
      close = modal('The session did not happen', h('div', null, h('p', { class: 'small', text: 'The credits stay in escrow until a moderator decides. Try talking first — misunderstandings happen!' }), t),
        [h('button', { class: 'btn ghost', text: 'Back', onclick: () => close() }), ok]);
    } }));
    if (A.includes('report')) buttons.appendChild(h('button', { class: 'btn ghost small', text: 'Report', onclick: () => reportBox(partner.id, partner.firstName, x.id) }));

    const thread = h('div', { class: 'thread' }, x.messages.map((m) => h('div', { class: 'msg' + (m.from === null ? ' sys' : m.mine ? ' mine' : '') + (m.flag ? ' flag-' + m.flag : '') },
      m.from !== null ? h('span', { class: 'who', text: m.fromName + ' · ' + ago(m.at) }) : null, m.text,
      m.advice ? h('div', { class: 'advice', text: '⚑ ' + m.advice }) : null)));
    const chat = h('div', { class: 'patch' }, h('h3', { text: 'Messages' }), thread);
    if (A.includes('message')) {
      const box = h('textarea', { maxlength: 1000, placeholder: 'Write to ' + (partner ? partner.firstName : '') + '…', style: { 'min-height': '60px' } });
      const send = h('button', { class: 'btn primary', type: 'submit', text: 'Send' });
      chat.appendChild(h('form', { class: 'stack', style: { 'margin-top': '12px' }, onsubmit: async (e) => {
        e.preventDefault();
        const v = await act(send, () => post('/api/exchanges/' + x.id + '/message', { text: box.value }));
        if (v) reload();
      } }, box, h('div', { class: 'row between' }, h('span', { class: 'tiny', text: 'Never send money. Phone numbers stay hidden until accepted.' }), send)));
    }
    setTimeout(() => { thread.scrollTop = thread.scrollHeight; }, 0);

    const right = h('div', { class: 'stack' });
    if (buttons.childNodes.length) right.appendChild(h('div', { class: 'patch tint', style: { '--c': 'var(--turmeric)' } }, h('h3', { text: 'What next?' }), h('p', { class: 'small', text: nextHint(x) }), buttons));
    if (A.includes('review')) right.appendChild(reviewForm(x, partner, reload));
    if (x.reviews.length) right.appendChild(h('div', { class: 'patch' }, h('h3', { text: 'Reviews' }), h('div', { class: 'list' }, x.reviews.map(reviewItem))));
    if (A.includes('resolve')) right.appendChild(resolveForm(x, reload));
    return h('div', null, h('p', null, h('a', { href: '#exchanges', text: '← All exchanges' })), h('div', { class: 'split' }, h('div', { class: 'stack' }, info, chat), right));
  };

  function creditLine(x) {
    switch (x.status) {
      case 'REQUESTED': return x.initiatorId === x.learner.id ? plural(x.hours, 'credit') + ' held in escrow' : 'credits are held when the learner accepts';
      case 'ACCEPTED': return plural(x.hours, 'credit') + ' held in escrow until you both confirm';
      case 'DISPUTED': return 'credits held until a moderator decides';
      case 'COMPLETED': return x.disputeResult === 'SPLIT' ? 'credits split by a moderator' : plural(x.hours, 'credit') + ' paid to ' + x.teacher.firstName;
      default: return 'credits went back to ' + x.learner.firstName;
    }
  }

  function nextHint(x) {
    const A = x.actions;
    if (A.includes('accept')) return 'Look at the message and the time. Accept to confirm — then you can see each other’s phone number.';
    if (A.includes('done')) return 'After the session, press Done. When you both do, the credits move. If only one presses Done, it completes by itself after 3 days.';
    if (x.status === 'REQUESTED') return 'Waiting for ' + (x.myRole === 'TEACHER' ? x.learner.firstName : x.teacher.firstName) + ' to answer. If nobody answers before the session time, the request expires and credits come back.';
    if (x.status === 'ACCEPTED') return 'All set! Message each other to agree on the details. Cancelling less than 24 hours before counts against reliability.';
    if (x.status === 'DISPUTED') return 'A moderator will look at the messages and decide.';
    return 'This exchange is closed.';
  }

  function reviewForm(x, partner, reload) {
    let starsVal = 5;
    const starRow = h('div', { class: 'row', role: 'radiogroup', 'aria-label': 'Stars' });
    const draw = () => {
      clear(starRow);
      for (let i = 1; i <= 5; i++) starRow.appendChild(h('button', { type: 'button', class: 'btn ghost small', role: 'radio', 'aria-checked': String(i === starsVal),
        style: { color: i <= starsVal ? 'var(--turmeric)' : 'var(--ink-3)', 'font-size': '1.2rem' }, text: '★', 'aria-label': i + ' stars', onclick: () => { starsVal = i; draw(); } }));
    };
    draw();
    const chosen = new Set();
    const tags = h('div', { class: 'chips filter-chips' }, state.meta.reviewTags.map((t) => {
      const b = h('button', { type: 'button', text: t });
      b.onclick = () => { if (chosen.has(t)) chosen.delete(t); else chosen.add(t); b.classList.toggle('on'); };
      return b;
    }));
    const text = h('textarea', { maxlength: 400, placeholder: 'A few words for ' + partner.firstName + ' and the community' });
    const send = h('button', { class: 'btn primary', type: 'submit', text: 'Post review' });
    return h('form', { class: 'patch', onsubmit: async (e) => {
      e.preventDefault();
      const v = await act(send, () => post('/api/exchanges/' + x.id + '/review', { stars: starsVal, text: text.value, tags: [...chosen] }), 'Thank you for the review!');
      if (v) reload();
    } }, h('h3', { text: 'How was it with ' + partner.firstName + '?' }), starRow, h('div', { style: { margin: '10px 0' } }, tags), text, h('div', { style: { 'margin-top': '10px' } }, send));
  }

  function resolveForm(x, reload) {
    const result = select([{ value: 'TEACHER', label: 'The session happened (or the learner did not come): pay the teacher' },
      { value: 'LEARNER', label: 'It did not happen because of the teacher: refund the learner' },
      { value: 'SPLIT', label: 'Both partly: split the credits' }], 'LEARNER');
    const note = h('textarea', { maxlength: 300, placeholder: 'Your reason — both people will see it' });
    const ok = h('button', { class: 'btn primary', type: 'submit', text: 'Decide' });
    return h('form', { class: 'patch tint', style: { '--c': 'var(--indigo)' }, onsubmit: async (e) => {
      e.preventDefault();
      const v = await act(ok, () => post('/api/exchanges/' + x.id + '/resolve', { result: result.value, note: note.value }), 'Decision saved');
      if (v) reload();
    } }, h('h3', { text: 'Moderator decision' }), h('p', { class: 'small', text: 'Read both sides in the messages. The person a decision goes against gets a let-down on their reliability.' }),
      field('Decision', result), field('Note', note), ok);
  }

  // ------------------------------------------------------------------ swaps

  /** Draws a swap as threads between embroidery hoops: 2 people side by side, 3 in a triangle. */
  function swapDrawing(steps) {
    const n = steps.length, W = 360, H = n === 2 ? 170 : 250;
    const pos = n === 2 ? [[80, 85], [280, 85]] : [[180, 46], [300, 200], [60, 200]];
    const svg = s('svg', { viewBox: '0 0 ' + W + ' ' + H, role: 'img', 'aria-label': steps.map((st) => st.teacher + ' teaches ' + st.learner + ' ' + st.skill).join('; ') },
      s('defs', null, s('marker', { id: 'arrow', viewBox: '0 0 10 10', refX: 8, refY: 5, markerWidth: 7, markerHeight: 7, orient: 'auto-start-reverse' },
        s('path', { d: 'M0 0 L10 5 L0 10 z', fill: 'var(--thread)' }))));
    steps.forEach((st, i) => {
      const [x1, y1] = pos[i], [x2, y2] = pos[(i + 1) % n];
      const mx = (x1 + x2) / 2, my = (y1 + y2) / 2;
      const dx = x2 - x1, dy = y2 - y1, len = Math.hypot(dx, dy);
      const bend = n === 2 ? 46 : 26;
      const cx = mx - dy / len * bend, cy = my + dx / len * bend;
      const sx = x1 + dx / len * 34, sy = y1 + dy / len * 34, ex = x2 - dx / len * 38, ey = y2 - dy / len * 38;
      svg.appendChild(s('path', { d: 'M' + sx + ' ' + sy + ' Q' + cx + ' ' + cy + ' ' + ex + ' ' + ey, fill: 'none', stroke: 'var(--thread)', 'stroke-width': 2.5,
        'stroke-dasharray': '7 5', 'stroke-linecap': 'round', 'marker-end': 'url(#arrow)' }));
      const lx = (mx + cx) / 2, ly = (my + cy) / 2;
      svg.appendChild(s('rect', { x: lx - 50, y: ly - 11, width: 100, height: 20, rx: 6, fill: 'var(--patch)', stroke: 'var(--stitch)', 'stroke-dasharray': '3 2' }));
      svg.appendChild(s('text', { x: lx, y: ly + 4, 'text-anchor': 'middle', 'font-size': 11, 'font-weight': 700 }, st.skill.length > 15 ? st.skill.slice(0, 14) + '…' : st.skill));
    });
    steps.forEach((st, i) => {
      const [x, y] = pos[i];
      const me = st.teacherId === state.me.id;
      svg.appendChild(s('circle', { cx: x, cy: y, r: 30, fill: 'none', stroke: 'var(--clay)', 'stroke-width': 4 }));
      svg.appendChild(s('circle', { cx: x, cy: y, r: 26, fill: me ? 'var(--madder)' : personColour(st.teacherId) }));
      svg.appendChild(s('text', { x, y: y + 6, 'text-anchor': 'middle', 'font-size': 16, 'font-weight': 800, fill: '#fffaf0' }, initialsOf(st.teacher)));
      svg.appendChild(s('text', { x, y: y + 48, 'text-anchor': 'middle', 'font-size': 12, 'font-weight': 600 }, me ? 'You' : st.teacher.split(' ')[0]));
    });
    return svg;
  }

  function initialsOf(name) {
    const w = String(name || '?').trim().split(/\s+/);
    return (w[0][0] + (w.length > 1 ? w[w.length - 1][0] : '')).toUpperCase();
  }

  ROUTES.swaps = async () => {
    const d = await api('/api/swaps');
    const card = (sw, kind) => {
      const mine = sw.steps.find((st) => st.learnerId === state.me.id);   // the step where I learn
      return h('div', { class: 'patch swap' },
        h('div', { class: 'row between' }, h('h3', { text: kind }), chip('loop ' + sw.loopKm + ' km')),
        swapDrawing(sw.steps),
        h('ol', { class: 'small' }, sw.steps.map((st) => h('li', null, h('a', { href: '#member/' + st.teacherId, text: st.teacherId === state.me.id ? 'You' : st.teacher }),
          ' teach' + (st.teacherId === state.me.id ? ' ' : 'es ') , h('a', { href: '#member/' + st.learnerId, text: st.learnerId === state.me.id ? 'you' : st.learner }), ' ' + st.skill))),
        mine ? h('a', { class: 'btn primary small', href: '#listing/' + mine.offerId, text: 'Ask ' + mine.teacher.split(' ')[0] + ' to teach you ' + mine.skill }) : null);
    };
    return h('div', null,
      h('div', { class: 'page-head' }, h('div', null, h('div', { class: 'hand', text: 'Teach and learn without spending' }), h('h1', { text: 'Swap circles' }))),
      h('div', { class: 'note-banner info' }, h('div', { class: 'small' },
        h('strong', { text: 'How swaps are found: ' }), 'we draw an arrow from every teacher to every neighbour who wants their skill (if they live close enough). ',
        'Two arrows that go both ways are a ', h('strong', { text: 'direct swap' }), '. Three arrows that go round in a loop are a ', h('strong', { text: 'swap circle' }),
        ' — nobody is paired up, but everyone teaches once and learns once, so the credits just go round. (' + d.arrows + ' arrows in the community right now.)')),
      sectionTitle('Direct swaps (' + d.pairCount + ')'),
      d.pairs.length ? h('div', { class: 'cards' }, d.pairs.map((x) => card(x, 'Direct swap'))) : empty('No direct swap yet. Add more “I want to learn” cards!'),
      sectionTitle('Swap circles of three (' + d.circleCount + ')'),
      d.circles.length ? h('div', { class: 'cards' }, d.circles.map((x) => card(x, 'Swap circle'))) : empty('No circle yet.'));
  };

  // ------------------------------------------------------------------ skill circles

  ROUTES.circles = async () => {
    const d = await api('/api/circles');
    const card = (c) => {
      const cat = c.skill ? catClass(c.skill.category) : '';
      const seats = h('div', { class: 'row', 'aria-label': c.taken + ' of ' + c.seats + ' seats taken', style: { gap: '4px' } });
      for (let i = 0; i < c.seats; i++) seats.appendChild(h('span', { style: { width: '12px', height: '12px', 'border-radius': '50%',
        background: i < c.taken ? 'var(--c, var(--thread))' : 'transparent', border: '2px solid var(--c, var(--thread))', display: 'inline-block' } }));
      const btns = h('div', { class: 'row' });
      if (c.status === 'OPEN') {
        if (c.iHost && c.started) btns.appendChild(h('button', { class: 'btn green small', text: 'It happened — collect ' + plural(c.taken, 'credit'), onclick: (e) => act(e.target, () => post('/api/circles/' + c.id + '/done'), 'Thank you for hosting!').then((v) => v && render()) }));
        if (c.iHost || state.me.role === 'MODERATOR') btns.appendChild(h('button', { class: 'btn ghost small', text: 'Cancel circle', onclick: () => confirmBox('Cancel “' + c.title + '”?', 'Everyone who joined gets their credit back.', 'Cancel circle',
          (why) => post('/api/circles/' + c.id + '/cancel', { reason: why }).then((v) => { render(); return v; }), 'Reason (shown to people who joined)') }));
        if (!c.iHost && !c.started) {
          if (c.iJoined) btns.appendChild(h('button', { class: 'btn ghost small', text: 'Leave (credit back)', onclick: (e) => act(e.target, () => post('/api/circles/' + c.id + '/leave'), 'You left the circle').then((v) => v && render()) }));
          else btns.appendChild(h('button', { class: 'btn primary small', disabled: c.taken >= c.seats || d.balance < 1, text: c.taken >= c.seats ? 'Full' : 'Join for 1 credit',
            onclick: (e) => act(e.target, () => post('/api/circles/' + c.id + '/join'), 'You are in! See you there.').then((v) => v && render()) }));
        }
      }
      return h('div', { class: 'patch ' + cat },
        h('span', { class: 'strip' }),
        h('div', { class: 'row', style: { 'align-items': 'flex-start', 'margin-top': '8px' } }, dateTag(c.at, cat),
          h('div', { class: 'grow' }, h('div', { class: 'chips' }, chip(c.skill.name, 'solid'), c.status !== 'OPEN' ? chip(c.status === 'DONE' ? 'Happened' : 'Cancelled', c.status === 'DONE' ? 'ok' : '') : null,
            c.iJoined ? chip('You joined', 'ok') : null, c.iHost ? chip('You host', 'ok') : null),
          h('h3', { style: { 'margin-top': '6px' }, text: c.title }),
          h('div', { class: 'small muted', text: timeOnly(c.at) + ' · ' + plural(c.hours, 'hour') + ' · ' + c.place + ', ' + c.locality }))),
        c.details ? h('p', { class: 'small', text: c.details }) : null,
        h('div', { class: 'row between' }, personLine(c.host), h('div', { class: 'tiny', text: c.taken + '/' + c.seats + ' seats' })),
        h('div', { style: { margin: '10px 0' } }, seats),
        btns);
    };
    return h('div', null,
      h('div', { class: 'page-head' }, h('div', null, h('div', { class: 'hand', text: 'Learn together in a public place' }), h('h1', { text: 'Skill circles' })),
        h('button', { class: 'btn primary', text: '+ Host a circle', onclick: circleForm })),
      h('p', { class: 'muted', text: 'A circle is a small group session in a hall, park or library. Joining costs 1 credit, held in escrow until the host says it happened. Leave before it starts and your credit comes back.' }),
      sectionTitle('Coming up'),
      d.upcoming.length ? h('div', { class: 'cards' }, d.upcoming.map(card)) : empty('No circles planned. Why not host one?'),
      sectionTitle('Recently'),
      d.past.length ? h('div', { class: 'cards' }, d.past.map(card)) : empty('Nothing yet.'));
  };

  function circleForm() {
    const skill = select(skillOptions('Choose a skill…'), '');
    const title = h('input', { type: 'text', maxlength: 80 });
    const details = h('textarea', { maxlength: 600, placeholder: 'What will you do? What should people bring?' });
    const place = h('input', { type: 'text', maxlength: 120, placeholder: 'e.g. Lalpur community hall' });
    const loc = select(localityOptions(), state.me.localityId);
    const when = h('input', { type: 'datetime-local', value: defaultWhen(5, 10) });
    const hours = select([1, 2, 3, 4].map((n) => ({ value: String(n), label: plural(n, 'hour') })), '2');
    const seats = h('input', { type: 'number', min: 2, max: 20, value: 8 });
    let close;
    const ok = h('button', { class: 'btn primary', text: 'Post circle', onclick: async () => {
      const v = await act(ok, () => post('/api/circles', { skillId: skill.value, title: title.value, details: details.value, place: place.value, localityId: loc.value,
        at: localToMillis(when.value), hours: Number(hours.value), seats: Number(seats.value) }), 'Your circle is posted!');
      if (v) { close(); render(); }
    } });
    close = modal('Host a skill circle', h('div', null, field('Skill', skill), field('Title', title), field('Details', details),
      h('div', { class: 'grid g2' }, field('Public place', place), field('Locality', loc)),
      h('div', { class: 'grid g3' }, field('When', when), field('How long', hours), field('Seats', seats))),
      [h('button', { class: 'btn ghost', text: 'Cancel', onclick: () => close() }), ok]);
  }

  // ------------------------------------------------------------------ wallet

  const KIND = { WELCOME: 'Welcome credits', HOLD: 'Held in escrow', RELEASE: 'Paid for teaching', REFUND: 'Came back', GIFT: 'Gift', ADJUST: 'Moderator correction' };

  ROUTES.wallet = async () => {
    const w = await api('/api/wallet');
    const who = h('input', { type: 'search', placeholder: 'Type a name…' });
    const found = h('div', { class: 'list' });
    let target = null;
    const targetBox = h('div', { class: 'small' });
    const amount = select([1, 2, 3, 4, 5].map((n) => ({ value: String(n), label: plural(n, 'credit') })), '1');
    const note = h('input', { type: 'text', maxlength: 120, placeholder: 'e.g. Thanks for fixing my cycle!' });
    const give = h('button', { class: 'btn primary', type: 'submit', text: 'Give', disabled: true });
    let timer = null;
    who.addEventListener('input', () => {
      clearTimeout(timer);
      timer = setTimeout(async () => {
        const d = await api('/api/members?q=' + encodeURIComponent(who.value)).catch(() => ({ members: [] }));
        clear(found);
        for (const m of d.members) found.appendChild(h('button', { type: 'button', class: 'item', style: { cursor: 'pointer', 'text-align': 'left' }, onclick: () => {
          target = m; clear(found); clear(targetBox).append('To: ', h('strong', { text: m.name }), ' (' + m.locality + ')'); give.disabled = false;
        } }, avatar(m, 'sm'), h('div', { class: 'body' }, h('div', { class: 'title', text: m.name }), h('div', { class: 'sub', text: m.locality }))));
      }, 250);
    });
    const giftForm = h('form', { class: 'patch', onsubmit: async (e) => {
      e.preventDefault();
      if (!target) return;
      const v = await act(give, () => post('/api/wallet/gift', { to: target.id, amount: Number(amount.value), note: note.value }), 'Gift sent to ' + target.name.split(' ')[0]);
      if (v) render();
    } }, h('h3', { text: 'Give credits to a neighbour' }), h('p', { class: 'tiny', text: 'For a favour, or to help a new member start. Up to ' + w.giftLimit + ' a day (' + w.giftsToday + ' given today).' }),
      field('Who', who), found, targetBox, h('div', { class: 'grid g2' }, field('How many', amount), field('Why', note)), give);

    return h('div', null,
      h('div', { class: 'page-head' }, h('div', null, h('div', { class: 'hand', text: 'One hour = one credit, for everybody' }), h('h1', { text: 'Time wallet' }))),
      h('div', { class: 'grid g4' },
        h('div', { class: 'patch colour', style: { '--c': 'var(--turmeric)' } }, h('div', { class: 'stat' }, h('span', { class: 'v', text: w.balance }), h('span', { class: 'k', style: { color: '#fffaf0' }, text: 'credits to spend' }))),
        h('div', { class: 'patch' }, h('div', { class: 'stat' }, h('span', { class: 'v', text: w.held }), h('span', { class: 'k', text: 'waiting in escrow' }))),
        h('div', { class: 'patch' }, h('div', { class: 'stat' }, h('span', { class: 'v', text: w.earned }), h('span', { class: 'k', text: 'earned by teaching' }))),
        h('div', { class: 'patch' }, h('div', { class: 'stat' }, h('span', { class: 'v', text: w.spent }), h('span', { class: 'k', text: 'spent on learning' })))),
      h('div', { class: 'patch flat', style: { 'margin-top': '16px' } }, buttonsRow(w.balance, w.held, 30)),
      h('div', { class: 'split', style: { 'margin-top': '18px' } },
        h('div', null, sectionTitle('Statement'),
          w.statement.length ? h('div', { class: 'patch scroll' }, h('table', { class: 'cloth' },
            h('thead', null, h('tr', null, ['When', 'What', 'With', 'Change', 'Balance'].map((t) => h('th', { text: t })))),
            h('tbody', null, w.statement.map((e) => h('tr', null,
              h('td', { class: 'nowrap', text: dateOnly(e.at) }),
              h('td', null, KIND[e.kind] || e.kind, e.note ? h('div', { class: 'tiny', text: e.note }) : null, e.ref && e.ref.startsWith('X-') ? h('a', { class: 'tiny', href: '#exchange/' + e.ref, text: ' ' + e.ref }) : null),
              h('td', { class: 'small', text: e.otherName }),
              h('td', { class: 'num ' + (e.change > 0 ? 'plus' : 'minus'), text: (e.change > 0 ? '+' : '') + e.change }),
              h('td', { class: 'num', text: e.balance })))))) : empty('No credits moved yet.')),
        h('div', { class: 'stack' }, giftForm,
          h('div', { class: 'patch tint', style: { '--c': 'var(--indigo)' } }, h('h3', { text: 'How the time bank works' }),
            h('ul', { class: 'small' },
              h('li', { text: 'Every new member gets ' + w.welcome + ' welcome credits.' }),
              h('li', { text: 'Asking for a session moves the credits to escrow — a safe box nobody can spend.' }),
              h('li', { text: 'When you both say “done”, escrow pays the teacher. Cancel, decline or expire: they come back to you.' }),
              h('li', { text: 'Your balance can never go below zero. Credits are never created by sessions, only moved.' }),
              h('li', { text: 'Every movement is a line in a hash-chained ledger that moderators can check.' }))))));
  };

  // ------------------------------------------------------------------ map

  ROUTES.map = async (r) => {
    const cat = r.params.category || '';
    const d = await api('/api/map' + (cat ? '?category=' + encodeURIComponent(cat) : ''));
    const L = d.localities;
    const lats = L.map((x) => x.lat).concat([d.me.lat]), lngs = L.map((x) => x.lng).concat([d.me.lng]);
    const minLat = Math.min(...lats) - 0.012, maxLat = Math.max(...lats) + 0.012, minLng = Math.min(...lngs) - 0.014, maxLng = Math.max(...lngs) + 0.014;
    const W = 760, H = Math.round(W * (maxLat - minLat) / ((maxLng - minLng) * Math.cos(23.37 * Math.PI / 180)));
    const X = (lng) => (lng - minLng) / (maxLng - minLng) * W, Y = (lat) => H - (lat - minLat) / (maxLat - minLat) * H;
    const kmPx = W / ((maxLng - minLng) * 111 * Math.cos(23.37 * Math.PI / 180));
    const detail = h('div', { class: 'patch' }, h('p', { class: 'hand muted', text: 'Tap a locality patch to see who is there.' }));
    const svg = s('svg', { viewBox: '0 0 ' + W + ' ' + H, role: 'img', 'aria-label': 'Map of localities with the number of cards in each' });
    // a faint quilt grid
    for (let gx = 0; gx < W; gx += 40) svg.appendChild(s('line', { x1: gx, y1: 0, x2: gx, y2: H, stroke: 'var(--stitch)', 'stroke-width': .5, 'stroke-dasharray': '2 6' }));
    for (let gy = 0; gy < H; gy += 40) svg.appendChild(s('line', { x1: 0, y1: gy, x2: W, y2: gy, stroke: 'var(--stitch)', 'stroke-width': .5, 'stroke-dasharray': '2 6' }));
    // my travel distance: a stitched circle
    svg.appendChild(s('circle', { cx: X(d.me.lng), cy: Y(d.me.lat), r: d.me.maxKm * kmPx, fill: 'var(--thread-soft)', stroke: 'var(--thread)', 'stroke-width': 2, 'stroke-dasharray': '8 6' }));
    const maxCards = Math.max(1, ...L.map((x) => x.offers + x.wants));
    for (const l of L) {
      const size = 16 + 22 * Math.sqrt((l.offers + l.wants) / maxCards);
      const cx = X(l.lng), cy = Y(l.lat);
      const g = s('g', { tabindex: 0, role: 'button', 'aria-label': l.name + ': ' + l.offers + ' teach, ' + l.wants + ' want to learn' });
      const share = (l.offers + l.wants) ? l.offers / (l.offers + l.wants) : .5;
      g.appendChild(s('rect', { x: cx - size, y: cy - size, width: size * 2, height: size * 2, rx: 8, fill: 'var(--leaf)', transform: 'rotate(' + ((l.name.length % 5) * 3 - 6) + ' ' + cx + ' ' + cy + ')' }));
      g.appendChild(s('rect', { x: cx - size, y: cy - size, width: size * 2 * (1 - share), height: size * 2, rx: 8, fill: 'var(--madder)', transform: 'rotate(' + ((l.name.length % 5) * 3 - 6) + ' ' + cx + ' ' + cy + ')' }));
      g.appendChild(s('rect', { x: cx - size + 4, y: cy - size + 4, width: size * 2 - 8, height: size * 2 - 8, rx: 6, fill: 'none', stroke: '#fffaf0', 'stroke-width': 1.5, 'stroke-dasharray': '4 3',
        transform: 'rotate(' + ((l.name.length % 5) * 3 - 6) + ' ' + cx + ' ' + cy + ')' }));
      g.appendChild(s('text', { x: cx, y: cy + 5, 'text-anchor': 'middle', 'font-size': 14, 'font-weight': 800, fill: '#fffaf0' }, String(l.offers + l.wants)));
      g.appendChild(s('text', { x: cx, y: cy + size + 16, 'text-anchor': 'middle', 'font-size': 13, 'font-weight': l.mine ? 800 : 600 }, l.name + (l.mine ? ' (you)' : '')));
      const show = () => {
        clear(detail).append(h('h3', { text: l.name }), h('p', { class: 'small muted', text: l.km + ' km from you · ' + plural(l.members, 'member') }),
          h('div', { class: 'grid g2' }, h('div', { class: 'stat' }, h('span', { class: 'v', style: { color: 'var(--leaf)' }, text: l.offers }), h('span', { class: 'k', text: 'teach' })),
            h('div', { class: 'stat' }, h('span', { class: 'v', style: { color: 'var(--madder)' }, text: l.wants }), h('span', { class: 'k', text: 'want to learn' }))),
          h('a', { class: 'btn small', style: { 'margin-top': '10px' }, href: '#explore?type=OFFER&km=' + Math.max(1, Math.ceil(l.km + 1)) + (cat ? '&category=' + encodeURIComponent(cat) : ''), text: 'Search within ' + Math.ceil(l.km + 1) + ' km' }));
      };
      g.addEventListener('click', show);
      g.addEventListener('keydown', (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); show(); } });
      svg.appendChild(g);
    }
    svg.appendChild(s('circle', { cx: X(d.me.lng), cy: Y(d.me.lat), r: 6, fill: 'var(--ink)', stroke: 'var(--patch)', 'stroke-width': 2 }));
    const cats = h('div', { class: 'chips filter-chips' }, [''].concat(state.meta.categories).map((c) => h('button', { type: 'button', class: (c === cat ? 'on ' : '') + (c ? catClass(c) : ''),
      text: c || 'All skills', onclick: () => { location.hash = 'map' + (c ? '?category=' + encodeURIComponent(c) : ''); } })));
    return h('div', null,
      h('div', { class: 'page-head' }, h('div', null, h('div', { class: 'hand', text: 'Where the skills are' }), h('h1', { text: 'Locality map' }))),
      cats,
      h('div', { class: 'split', style: { 'margin-top': '14px' } },
        h('div', { class: 'patch map' }, svg, h('p', { class: 'tiny', style: { 'margin-top': '8px' } },
          'Each patch is a locality: green part = cards from people who teach, red part = people who want to learn; the number is all cards. ',
          'The stitched circle is how far you travel (' + d.me.maxKm + ' km). For privacy nobody’s home is shown — only localities.')),
        detail));
  };

  // ------------------------------------------------------------------ community numbers

  function weekChart(weeks) {
    const W = 640, H = 220, pad = 30, max = Math.max(4, ...weeks.map((w) => w.hours));
    const bw = (W - pad - 10) / weeks.length;
    const svg = s('svg', { class: 'chart', viewBox: '0 0 ' + W + ' ' + H, role: 'img', 'aria-label': 'Hours exchanged per week, last 12 weeks' });
    for (let i = 0; i <= 4; i++) {
      const y = H - 24 - (H - 40) * i / 4;
      svg.appendChild(s('line', { x1: pad, y1: y, x2: W, y2: y, stroke: 'var(--stitch)', 'stroke-dasharray': '3 4' }));
      svg.appendChild(s('text', { x: pad - 6, y: y + 4, 'text-anchor': 'end' }, String(Math.round(max * i / 4))));
    }
    weeks.forEach((w, i) => {
      const bh = (H - 40) * w.hours / max, x = pad + 6 + i * bw, y = H - 24 - bh;
      svg.appendChild(s('rect', { x, y, width: bw - 10, height: Math.max(0, bh), rx: 4, fill: i === weeks.length - 1 ? 'var(--turmeric)' : 'var(--indigo)' }));
      if (bh > 8) svg.appendChild(s('rect', { x: x + 3, y: y + 3, width: bw - 16, height: bh - 6, rx: 3, fill: 'none', stroke: 'rgba(255,250,235,.6)', 'stroke-dasharray': '3 3' }));
      const label = new Date(w.week + 'T00:00:00').toLocaleDateString('en-IN', { day: 'numeric', month: 'short' });
      svg.appendChild(s('text', { x: x + (bw - 10) / 2, y: H - 8, 'text-anchor': 'middle' }, label));
      svg.appendChild(s('title', null, w.hours + ' hours in ' + w.sessions + ' sessions, week of ' + label));
    });
    return svg;
  }

  ROUTES.community = async () => {
    const d = await api('/api/community');
    const t = d.totals, c = d.credits;
    const tile = (v, k, colour) => h('div', { class: 'patch' + (colour ? ' colour' : ''), style: colour ? { '--c': 'var(' + colour + ')' } : null },
      h('div', { class: 'stat' }, h('span', { class: 'v', text: v }), h('span', { class: 'k', style: colour ? { color: '#fffaf0' } : null, text: k })));
    const catMax = Math.max(1, ...Object.values(d.byCategory));
    const needMax = Math.max(1, ...d.needed.map((n) => Math.max(n.wants, n.offers)));
    return h('div', null,
      h('div', { class: 'page-head' }, h('div', null, h('div', { class: 'hand', text: 'Our quilt in numbers' }), h('h1', { text: 'Community' }))),
      h('div', { class: 'grid g4' }, tile(t.members, 'neighbours', '--indigo'), tile(t.hours, 'hours shared', '--madder'), tile(t.sessions, 'sessions completed'),
        tile(t.rating.toFixed(2) + '★', 'average review')),
      h('div', { class: 'grid g4', style: { 'margin-top': '16px' } }, tile(t.offers, 'cards: can teach'), tile(t.wants, 'cards: want to learn'),
        tile(t.circles + ' · ' + t.circlePeople, 'circles held · people'), tile(t.completionRate + '%', 'requests that ended well')),
      h('div', { class: 'split', style: { 'margin-top': '10px' } },
        h('div', null,
          sectionTitle('Hours shared per week'), h('div', { class: 'patch' }, weekChart(d.weeks)),
          sectionTitle('Skills our neighbourhood needs'),
          h('div', { class: 'patch' }, h('p', { class: 'tiny', text: 'Red = people who want to learn it, green = people who teach it. If you know one of these, you are needed!' }),
            d.needed.map((n) => h('div', { class: 'hbar' }, h('span', { class: 'small', text: n.skill }),
              h('div', { class: 'pair-bars' }, bar(100 * n.wants / needMax, 'c-study'), bar(100 * n.offers / needMax, 'c-garden')),
              h('span', { class: 'small num', text: n.wants + ' / ' + n.offers })))),
          sectionTitle('Hours by category'),
          h('div', { class: 'patch' }, Object.entries(d.byCategory).sort((a, b) => b[1] - a[1]).map(([k, v]) => h('div', { class: 'hbar' },
            h('span', { class: 'small', text: k }), bar(100 * v / catMax, catClass(k)), h('span', { class: 'small num', text: v }))))),
        h('div', { class: 'stack' },
          sectionTitle('Neighbourhood heroes'),
          h('div', { class: 'list' }, d.heroes.map((x, i) => h('a', { class: 'item', href: '#member/' + x.id }, h('span', { class: 'hand', style: { 'font-size': '1.4rem', width: '22px' }, text: String(i + 1) }),
            avatar(x, 'sm'), h('div', { class: 'body' }, h('div', { class: 'title', text: x.name }), h('div', { class: 'sub', text: x.locality + ' · ' + x.rating + '★' })),
            h('strong', { text: x.hours + ' h' })))),
          sectionTitle('Credit health'),
          h('div', { class: 'patch' }, h('table', { class: 'cloth' }, h('tbody', null,
            h('tr', null, h('td', { text: 'Credits given out as welcome / corrections' }), h('td', { class: 'num', text: c.issued })),
            h('tr', null, h('td', { text: 'With members now' }), h('td', { class: 'num', text: c.inMembers })),
            h('tr', null, h('td', { text: 'Waiting in escrow' }), h('td', { class: 'num', text: c.inEscrow })),
            h('tr', null, h('td', { text: 'Members with 0 credits (should teach something!)' }), h('td', { class: 'num', text: c.membersAtZero })),
            h('tr', null, h('td', { text: 'Velocity: hours in 30 days ÷ credits' }), h('td', { class: 'num', text: c.velocity })))),
            h('p', { class: 'tiny', style: { 'margin-top': '8px' }, text: 'Given out = with members + in escrow, always. A busy time bank has a velocity near 1.' })),
          sectionTitle('Swaps'),
          h('div', { class: 'patch' }, h('p', { class: 'small', text: d.swaps.pairs + ' direct swaps and ' + d.swaps.circles + ' swap circles of three are possible right now (' + d.swaps.arrows + ' teacher → learner arrows).' })),
          sectionTitle('Plenty of teachers'),
          h('div', { class: 'patch' }, d.plenty.length ? h('div', { class: 'chips' }, d.plenty.map((p) => chip(p.skill + ' (' + p.offers + ' teach)', 'ok'))) : h('p', { class: 'small', text: 'No skill has more teachers than learners.' })))),
      sectionTitle('Localities'),
      h('div', { class: 'patch scroll' }, h('table', { class: 'cloth' },
        h('thead', null, h('tr', null, ['Locality', 'Members', 'Teach cards', 'Want cards', 'Hours taught'].map((x) => h('th', { text: x })))),
        h('tbody', null, d.localities.map((l) => h('tr', null, h('td', { text: l.name }), h('td', { class: 'num', text: l.members }), h('td', { class: 'num', text: l.offers }),
          h('td', { class: 'num', text: l.wants }), h('td', { class: 'num', text: l.hours })))))));
  };

  // ------------------------------------------------------------------ settings

  ROUTES.settings = async () => {
    const me = (await api('/api/me')).user;
    state.me = me;
    const name = h('input', { type: 'text', maxlength: 60, value: me.name });
    const bio = h('textarea', { maxlength: 300, value: me.bio || '' });
    const langs = h('input', { type: 'text', maxlength: 80, value: me.languages || '', placeholder: 'e.g. Hindi, English, Nagpuri' });
    const phone = h('input', { type: 'tel', value: me.phone || '', placeholder: '10-digit mobile' });
    const loc = select(localityOptions(), me.localityId);
    const km = h('input', { type: 'range', min: 1, max: 25, value: me.maxKm });
    const kmL = h('strong', { text: me.maxKm + ' km' });
    km.addEventListener('input', () => { kmL.textContent = km.value + ' km'; });
    const grid = slotGrid(me.availability, { editable: true });
    const save = h('button', { class: 'btn primary', type: 'submit', text: 'Save profile' });
    const cur = h('input', { type: 'password', autocomplete: 'current-password' });
    const next = h('input', { type: 'password', autocomplete: 'new-password' });
    const pw = h('button', { class: 'btn', type: 'submit', text: 'Change password' });
    const blocked = h('div', { class: 'list' });
    for (const id of me.blocked) {
      const m = await api('/api/members/' + id).catch(() => null);
      if (m) blocked.appendChild(h('div', { class: 'item' }, avatar(m, 'sm'), h('div', { class: 'body', text: m.name }),
        h('button', { class: 'btn small ghost', text: 'Unblock', onclick: (e) => act(e.target, () => post('/api/members/' + id + '/block', { block: false }), 'Unblocked').then((v) => v && render()) })));
    }
    return h('div', null,
      h('div', { class: 'page-head' }, h('div', null, h('div', { class: 'hand', text: '@' + me.username }), h('h1', { text: 'My profile' })), h('a', { class: 'btn ghost', href: '#member/' + me.id, text: 'See how others see me' })),
      h('div', { class: 'split' },
        h('form', { class: 'patch', onsubmit: async (e) => {
          e.preventDefault();
          const v = await act(save, () => put('/api/me/profile', { name: name.value, bio: bio.value, languages: langs.value, phone: phone.value, localityId: loc.value, maxKm: Number(km.value), availability: grid.value() }), 'Saved');
          if (v) { state.me = v; render(); }
        } }, field('Name', name), field('About me', bio), h('div', { class: 'grid g2' }, field('Languages', langs), field('Mobile', phone, 'only partners see it, after accepting')),
          h('div', { class: 'grid g2' }, field('Locality', loc), h('label', { class: 'field' }, h('span', null, 'I travel up to ', kmL), km)),
          h('h3', { style: { 'margin-top': '10px' }, text: 'When I am free' }), h('p', { class: 'tiny', text: 'Tap to stitch a cross in the times that suit you. Matches with shared free times rank higher.' }), grid,
          h('div', { style: { 'margin-top': '14px' } }, save)),
        h('div', { class: 'stack' },
          h('form', { class: 'patch', onsubmit: async (e) => {
            e.preventDefault();
            const v = await act(pw, () => post('/api/me/password', { current: cur.value, next: next.value }), 'Password changed');
            if (v) { cur.value = ''; next.value = ''; }
          } }, h('h3', { text: 'Password' }), field('Current password', cur), field('New password', next, '8+ with a letter and a number'), pw),
          h('div', { class: 'patch' }, h('h3', { text: 'Blocked members' }), blocked.childNodes.length ? blocked : h('p', { class: 'small muted', text: 'Nobody. You can block someone from their profile.' })))));
  };

  // ------------------------------------------------------------------ notifications

  ROUTES.notes = async () => {
    const d = await api('/api/notes');
    const markBtn = h('button', { class: 'btn small ghost', text: 'Mark all as read', onclick: (e) => act(e.target, () => post('/api/notes/read')).then((v) => v && render()) });
    return h('div', null,
      h('div', { class: 'page-head' }, h('div', null, h('div', { class: 'hand', text: 'News from your neighbours' }), h('h1', { text: 'Notifications' })), markBtn),
      d.notes.length ? h('div', { class: 'list' }, d.notes.map(noteItem)) : empty('Nothing yet.'));
  };

  // ------------------------------------------------------------------ moderators

  ROUTES.mod = async () => {
    const d = await api('/api/mod');
    const st = d.stats;
    const reportCard = (r) => {
      const action = select([{ value: 'WARN', label: 'Send a warning' }, { value: 'SUSPEND', label: 'Pause the account' }, { value: 'DISMISS', label: 'Dismiss (nothing wrong)' }], r.reason === 'MONEY' ? 'WARN' : 'WARN');
      const note = h('input', { type: 'text', maxlength: 300, placeholder: 'Note (the member sees it if warned or paused)' });
      const ok = h('button', { class: 'btn primary small', type: 'submit', text: 'Decide' });
      return h('form', { class: 'patch', onsubmit: async (e) => {
        e.preventDefault();
        const v = await act(ok, () => post('/api/reports/' + r.id + '/resolve', { action: action.value, note: note.value }), 'Report closed');
        if (v) render();
      } },
        h('div', { class: 'row between' }, h('div', { class: 'chips' }, chip(REASONS[r.reason] || r.reason, r.reason === 'MONEY' || r.reason === 'UNSAFE' ? 'bad' : 'warn'), chip(r.id)), h('span', { class: 'tiny', text: ago(r.createdAt) })),
        h('h3', { style: { 'margin-top': '8px' } }, h('a', { href: '#member/' + r.reporterId, text: r.reporter }), ' reported ', h('a', { href: '#member/' + r.targetId, text: r.target })),
        h('p', { text: '“' + r.text + '”' }),
        h('div', { class: 'small muted' }, r.target + ': ' + plural(r.targetWarnings, 'earlier warning') + ', ' + plural(r.otherReports, 'other report'),
          r.exchangeId ? [' · ', h('a', { href: '#exchange/' + r.exchangeId, text: 'open exchange ' + r.exchangeId })] : null),
        h('div', { class: 'grid g3', style: { 'margin-top': '10px', 'align-items': 'end' } }, field('Action', action), h('div', { style: { 'grid-column': 'span 1' } }, field('Note', note)), h('div', { style: { 'padding-bottom': '10px' } }, ok)));
    };
    const skillName = h('input', { type: 'text', maxlength: 40 });
    const skillCat = select(state.meta.categories.map((c) => ({ value: c, label: c })), state.meta.categories[0]);
    const skillTags = h('input', { type: 'text', placeholder: 'other words, comma separated' });
    const addSkill = h('button', { class: 'btn small', type: 'submit', text: 'Add to catalogue' });
    return h('div', null,
      h('div', { class: 'page-head' }, h('div', null, h('div', { class: 'hand', text: 'Keeping the quilt safe' }), h('h1', { text: 'Reports & disputes' }))),
      h('div', { class: 'grid g4' },
        h('div', { class: 'patch colour', style: { '--c': 'var(--madder)' } }, h('div', { class: 'stat' }, h('span', { class: 'v', text: st.openReports }), h('span', { class: 'k', style: { color: '#fffaf0' }, text: 'open reports' }))),
        h('div', { class: 'patch colour', style: { '--c': 'var(--indigo)' } }, h('div', { class: 'stat' }, h('span', { class: 'v', text: st.disputes }), h('span', { class: 'k', style: { color: '#fffaf0' }, text: 'disputes' }))),
        h('div', { class: 'patch' }, h('div', { class: 'stat' }, h('span', { class: 'v', text: st.moneyFlags }), h('span', { class: 'k', text: 'messages about money (30 days)' }))),
        h('div', { class: 'patch' }, h('div', { class: 'stat' }, h('span', { class: 'v', text: st.paused }), h('span', { class: 'k', text: 'paused accounts' })))),
      sectionTitle('Disputes'),
      d.disputes.length ? h('div', { class: 'list' }, d.disputes.map((x) => h('a', { class: 'item needs', href: '#exchange/' + x.id }, dateTag(x.at, catClass(x.skill.category)),
        h('div', { class: 'body' }, h('div', { class: 'title', text: x.skill.name + ': ' + x.teacher.name + ' → ' + x.learner.name }),
          h('div', { class: 'sub', text: (x.messages.filter((m) => m.flag === 'DISPUTE').map((m) => m.text)[0] || '') })),
        chip('Decide →', 'bad')))) : empty('No disputes. Lovely.'),
      sectionTitle('Open reports'),
      d.open.length ? h('div', { class: 'grid g2' }, d.open.map(reportCard)) : empty('No open reports.'),
      sectionTitle('Recently closed'),
      d.closed.length ? h('div', { class: 'patch scroll' }, h('table', { class: 'cloth' },
        h('thead', null, h('tr', null, ['When', 'About', 'Reason', 'Decision', 'By', 'Note'].map((x) => h('th', { text: x })))),
        h('tbody', null, d.closed.map((r) => h('tr', null, h('td', { class: 'nowrap', text: dateOnly(r.resolvedAt) }), h('td', null, h('a', { href: '#member/' + r.targetId, text: r.target })),
          h('td', { text: REASONS[r.reason] || r.reason }), h('td', null, chip(r.action, r.action === 'SUSPEND' ? 'bad' : r.action === 'WARN' ? 'warn' : '')), h('td', { text: r.resolver }), h('td', { class: 'small', text: r.note })))))) : empty('Nothing yet.'),
      sectionTitle('Skill catalogue'),
      h('form', { class: 'patch', onsubmit: async (e) => {
        e.preventDefault();
        const v = await act(addSkill, () => post('/api/skills', { name: skillName.value, category: skillCat.value, tags: skillTags.value }), 'Skill added');
        if (v) { const m = await api('/api/me'); state.meta = m.meta; skillName.value = ''; skillTags.value = ''; }
      } }, h('p', { class: 'small muted', text: 'Members choose skills from this list, so search and swaps work. ' + state.meta.skills.length + ' skills now.' }),
        h('div', { class: 'grid g4', style: { 'align-items': 'end' } }, field('Name', skillName), field('Category', skillCat), field('Other words', skillTags), h('div', { style: { 'padding-bottom': '10px' } }, addSkill))));
  };

  ROUTES.members = async (r) => {
    const q = r.params.q || '';
    const d = await api('/api/mod/members?q=' + encodeURIComponent(q));
    const box = h('input', { type: 'search', value: q, placeholder: 'Name, username or locality' });
    return h('div', null,
      h('div', { class: 'page-head' }, h('div', null, h('div', { class: 'hand', text: 'Moderators' }), h('h1', { text: 'Members' })),
        h('form', { class: 'row', onsubmit: (e) => { e.preventDefault(); location.hash = 'members?q=' + encodeURIComponent(box.value); } }, box, h('button', { class: 'btn', type: 'submit', text: 'Find' }))),
      h('div', { class: 'patch scroll' }, h('table', { class: 'cloth' },
        h('thead', null, h('tr', null, ['Member', 'Locality', 'Credits', 'Rating', 'Reliable', 'Done', 'Warnings', 'Reports', 'Status'].map((x) => h('th', { text: x })))),
        h('tbody', null, d.members.map((m) => h('tr', null,
          h('td', null, h('div', { class: 'person' }, avatar(m, 'sm'), h('div', null, h('a', { class: 'name', href: '#member/' + m.id, text: m.name }), h('div', { class: 'tiny', text: '@' + m.username })))),
          h('td', { text: m.locality }), h('td', { class: 'num', text: m.balance }), h('td', { class: 'num', text: m.reviews ? m.rating + '★ (' + m.reviews + ')' : '–' }),
          h('td', { class: 'num', text: m.reliability + '%' }), h('td', { class: 'num', text: m.completed }), h('td', { class: 'num', text: m.warnings }), h('td', { class: 'num', text: m.reports }),
          h('td', null, m.suspended ? chip('Paused', 'bad') : m.role === 'MODERATOR' ? chip('Moderator', 'ok') : chip('Active'))))))));
  };

  ROUTES.ledger = async () => {
    const d = await api('/api/ledger');
    const KINDS = Object.entries(d.byKind);
    return h('div', null,
      h('div', { class: 'page-head' }, h('div', null, h('div', { class: 'hand', text: 'Every credit, every move' }), h('h1', { text: 'Ledger check' })),
        h('button', { class: 'btn', text: 'Check again', onclick: () => render() })),
      h('div', { class: 'note-banner ' + (d.ok ? 'ok' : 'bad') }, h('div', null,
        h('strong', { text: d.ok ? 'All good. ' : plural(d.problems.length, 'problem') + ' found. ' }),
        d.ok ? 'All ' + d.lines + ' lines are linked by their SHA-256 hashes, no member ever went below zero, all balances add up to zero, and escrow holds exactly what the open exchanges and circles need.' : null,
        d.problems.length ? h('ul', null, d.problems.map((p) => h('li', { text: p }))) : null)),
      h('div', { class: 'grid g4', style: { 'margin-top': '16px' } },
        h('div', { class: 'patch' }, h('div', { class: 'stat' }, h('span', { class: 'v', text: d.issued }), h('span', { class: 'k', text: 'credits given out by the bank' }))),
        h('div', { class: 'patch' }, h('div', { class: 'stat' }, h('span', { class: 'v', text: d.inMembers }), h('span', { class: 'k', text: 'with members' }))),
        h('div', { class: 'patch' }, h('div', { class: 'stat' }, h('span', { class: 'v', text: d.inEscrow }), h('span', { class: 'k', text: 'in escrow' }))),
        h('div', { class: 'patch' }, h('div', { class: 'stat' }, h('span', { class: 'v mono', style: { 'font-size': '1rem' }, text: d.lastHash.slice(0, 16) + '…' }), h('span', { class: 'k', text: 'latest hash (the chain’s fingerprint)' })))),
      h('div', { class: 'patch flat', style: { 'margin-top': '14px' } }, h('div', { class: 'chips' }, KINDS.map(([k, v]) => chip((KIND[k] || k) + ': ' + v)))),
      sectionTitle('Latest 200 lines'),
      h('div', { class: 'patch scroll' }, h('table', { class: 'cloth' },
        h('thead', null, h('tr', null, ['#', 'When', 'From', 'To', 'Credits', 'Kind', 'For', 'Hash'].map((x) => h('th', { text: x })))),
        h('tbody', null, d.entries.map((e) => h('tr', null, h('td', { class: 'num', text: e.seq }), h('td', { class: 'nowrap small', text: dateTime(e.at) }),
          h('td', { class: 'small', text: e.fromName }), h('td', { class: 'small', text: e.toName }), h('td', { class: 'num', text: e.amount }), h('td', { class: 'small', text: KIND[e.kind] || e.kind }),
          h('td', { class: 'small' }, e.ref && e.ref.startsWith('X-') ? h('a', { href: '#exchange/' + e.ref, text: e.ref }) : (e.ref || '')),
          h('td', { class: 'mono', title: 'previous: ' + e.prevHash, text: e.hash.slice(0, 12) + '…' })))))));
  };

  ROUTES.audit = async (r) => {
    const q = r.params.q || '';
    const d = await api('/api/audit?q=' + encodeURIComponent(q));
    const box = h('input', { type: 'search', value: q, placeholder: 'Filter: login, exchange, report, a name…' });
    return h('div', null,
      h('div', { class: 'page-head' }, h('div', null, h('div', { class: 'hand', text: 'Who did what, when' }), h('h1', { text: 'Activity log' })),
        h('form', { class: 'row', onsubmit: (e) => { e.preventDefault(); location.hash = 'audit?q=' + encodeURIComponent(box.value); } }, box, h('button', { class: 'btn', type: 'submit', text: 'Filter' }))),
      h('div', { class: 'patch scroll' }, h('table', { class: 'cloth' },
        h('thead', null, h('tr', null, ['When', 'Who', 'Action', 'On', 'Details'].map((x) => h('th', { text: x })))),
        h('tbody', null, d.entries.map((a) => h('tr', null, h('td', { class: 'nowrap small', text: dateTime(a.at) }), h('td', { text: a.who }), h('td', null, chip(a.action)),
          h('td', { class: 'small mono', text: a.target || '' }), h('td', { class: 'small', text: a.detail || '' })))))));
  };

  // ------------------------------------------------------------------ start

  async function boot() {
    try {
      const d = await api('/api/me');
      state.meta = d.meta;
      state.me = d.user;
    } catch (e) {
      clear(app).appendChild(h('div', { class: 'patch', style: { margin: '40px auto', 'max-width': '480px' } }, h('h2', { text: 'Cannot reach the server' }), h('p', { text: e.message })));
      return;
    }
    render();
    setInterval(() => { if (state.me && !document.hidden) refreshCounts(); }, 30000);
  }

  boot();
})();
