/* Parisar – Smart Digital Campus Platform (frontend).
   Plain JavaScript, no libraries. Pages are drawn with the h() helper (never innerHTML with data, so no XSS).
   Charts are hand-made SVG. The page talks to the Java backend through /api (JSON). */
(function () {
  'use strict';

  // ================================================================== small helpers
  const $app = document.getElementById('app');
  const SVGNS = 'http://www.w3.org/2000/svg';
  const state = { user: null, meta: null, timer: null, counts: { alerts: 0, crit: 0 } };

  function h(tag, attrs, ...kids) {
    const el = document.createElement(tag);
    for (const [k, v] of Object.entries(attrs || {})) {
      if (v == null || v === false) continue;
      if (k === 'class') el.className = v;
      else if (k === 'style') Object.assign(el.style, v);
      else if (k.startsWith('on')) el.addEventListener(k.slice(2), v);
      else if (k === 'value') el.value = v;
      else if (k === 'checked') el.checked = !!v;
      else el.setAttribute(k, v === true ? '' : v);
    }
    add(el, kids);
    return el;
  }
  function s(tag, attrs, ...kids) {
    const el = document.createElementNS(SVGNS, tag);
    for (const [k, v] of Object.entries(attrs || {})) {
      if (v == null || v === false) continue;
      if (k.startsWith('on')) el.addEventListener(k.slice(2), v);
      else if (k === 'style') Object.assign(el.style, v);
      else el.setAttribute(k, v);
    }
    add(el, kids);
    return el;
  }
  function add(el, kids) {
    for (const k of kids.flat(Infinity)) {
      if (k == null || k === false) continue;
      el.appendChild(k instanceof Node ? k : document.createTextNode(String(k)));
    }
  }
  const clear = el => { while (el.firstChild) el.removeChild(el.firstChild); return el; };
  /** Empties an element and puts new content in (skips null / false, flattens arrays). */
  const fill = (el, ...kids) => { clear(el); add(el, kids); return el; };
  const fmt = (n, d = 0) => n == null || isNaN(n) ? '–' : Number(n).toLocaleString('en-IN', { maximumFractionDigits: d, minimumFractionDigits: d });
  const pad = n => String(n).padStart(2, '0');
  const timeOf = t => { const d = new Date(t); return pad(d.getHours()) + ':' + pad(d.getMinutes()); };
  const dateOf = t => new Date(t).toLocaleDateString('en-IN', { day: 'numeric', month: 'short' });
  const whenOf = t => {
    if (!t) return '–';
    const diff = Date.now() - t;
    if (diff < 60000 && diff > -60000) return 'just now';
    if (diff > 0 && diff < 3600000) return Math.round(diff / 60000) + ' min ago';
    const d = new Date(t), today = new Date();
    if (d.toDateString() === today.toDateString()) return 'today ' + timeOf(t);
    return dateOf(t) + ', ' + timeOf(t);
  };
  const hourText = hr => (hr % 12 === 0 ? 12 : hr % 12) + (hr < 12 ? ' am' : ' pm');
  const DAYS = ['', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
  const isStaff = () => state.user && state.user.role !== 'STUDENT';
  const isAdmin = () => state.user && state.user.role === 'ADMIN';
  const zoneOf = buildingId => (buildingId || 'B-A').slice(2);
  const RULES = { HEAT: 'Too hot', CO2: 'Stale air', WATER_LOW: 'Low water', OFFLINE: 'Device silent', ENERGY_WASTE: 'Energy waste', ANOMALY: 'Unusual reading (AI)' };
  const KINDS = { CLASSROOM: 'Classroom', LAB: 'Lab', LIBRARY: 'Library', SEMINAR: 'Seminar hall', HOSTEL: 'Hostel', OFFICE: 'Office', CAFE: 'Dining', SERVER: 'Server room' };

  // ================================================================== talking to the server
  async function api(method, path, body) {
    const opt = { method, headers: {}, credentials: 'same-origin' };
    if (method !== 'GET') {
      opt.headers['Content-Type'] = 'application/json';
      opt.headers['X-Parisar'] = '1';
      opt.body = JSON.stringify(body || {});
    }
    let res;
    try { res = await fetch(path, opt); } catch (e) { throw new Error('Cannot reach the server. Is it still running?'); }
    let data = null;
    try { data = await res.json(); } catch (e) { /* empty */ }
    if (res.status === 401 && path !== '/api/login') { state.user = null; showLogin(); throw new Error('Please sign in again.'); }
    if (!res.ok) throw new Error((data && data.error) || ('Error ' + res.status));
    return data;
  }

  function toast(msg, bad) {
    const t = document.getElementById('toast');
    t.textContent = msg;
    t.className = 'toast' + (bad ? ' bad' : '');
    t.hidden = false;
    clearTimeout(toast._t);
    toast._t = setTimeout(() => { t.hidden = true; }, bad ? 5000 : 3000);
  }
  async function act(fn, okMsg) {
    try { const r = await fn(); if (okMsg) toast(okMsg); return r; } catch (e) { toast(e.message, true); return null; }
  }

  function modal(title, body, wide) {
    const root = document.getElementById('modal-root');
    const prev = document.activeElement;
    const close = () => { clear(root); document.removeEventListener('keydown', onKey); if (prev && prev.focus) prev.focus(); };
    const onKey = e => { if (e.key === 'Escape') close(); };
    const box = h('div', { class: 'modal' + (wide ? ' wide' : ''), role: 'dialog', 'aria-modal': 'true', 'aria-label': title },
      h('div', { class: 'modal-head' }, h('h2', {}, title), h('button', { class: 'modal-close', 'aria-label': 'Close', onclick: close }, '×')),
      h('div', { class: 'modal-body' }, body));
    clear(root).appendChild(h('div', { class: 'modal-back', onclick: e => { if (e.target === e.currentTarget) close(); } }, box));
    document.addEventListener('keydown', onKey);
    const f = box.querySelector('input, select, textarea, button.btn');
    if (f) f.focus();
    return close;
  }

  // ================================================================== pictograms (sign symbols)
  const P = {
    home: 'M3 11 12 4l9 7M6 10v10h12V10',
    map: 'M3 6l6-2 6 2 6-2v14l-6 2-6-2-6 2zM9 4v14M15 6v14',
    classes: 'M4 5h16v11H4zM8 20h8M12 16v4',
    students: 'M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8zM4 21c0-4 4-6 8-6s8 2 8 6',
    alerts: 'M12 3 2 20h20zM12 10v4M12 17v.5',
    devices: 'M7 7h10v10H7zM10 3v4M14 3v4M10 17v4M14 17v4M3 10h4M3 14h4M17 10h4M17 14h4',
    energy: 'M13 2 4 14h7l-1 8 9-12h-7z',
    analytics: 'M4 20V10M10 20V4M16 20v-7M22 20H2',
    cloud: 'M7 18a5 5 0 1 1 1-9.9A6 6 0 0 1 19 10a4 4 0 0 1-1 8z',
    assistant: 'M4 5h16v11H9l-5 4zM8 10h.5M12 10h.5M16 10h.5',
    notices: 'M5 3h11l3 3v15H5zM8 9h8M8 13h8M8 17h5',
    admin: 'M12 3l8 3v6c0 5-4 8-8 9-4-1-8-4-8-9V6z',
    me: 'M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8zM6 21v-1a6 6 0 0 1 12 0v1'
  };
  const pict = name => h('span', { class: 'pict', 'aria-hidden': 'true' },
    s('svg', { viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor', 'stroke-width': 2.2, 'stroke-linecap': 'round', 'stroke-linejoin': 'round' }, s('path', { d: P[name] })));

  /** The hazard sign used for alerts: triangle (warning), octagon (critical), circle (info). */
  function hazard(sev) {
    if (sev === 'CRITICAL') return s('svg', { class: 'hazard', viewBox: '0 0 34 30', role: 'img', 'aria-label': 'Critical' },
      s('path', { d: 'M11 1h12l9 9v10l-9 9H11l-9-9V10z', fill: 'var(--danger)' }),
      s('path', { d: 'M17 8v9M17 21v1', stroke: '#fff', 'stroke-width': 3.2, 'stroke-linecap': 'round' }));
    if (sev === 'WARNING') return s('svg', { class: 'hazard', viewBox: '0 0 34 30', role: 'img', 'aria-label': 'Warning' },
      s('path', { d: 'M17 2 32 28H2z', fill: 'var(--hazard)', stroke: '#1D1600', 'stroke-width': 2, 'stroke-linejoin': 'round' }),
      s('path', { d: 'M17 11v8M17 23v.5', stroke: '#1D1600', 'stroke-width': 3, 'stroke-linecap': 'round' }));
    return s('svg', { class: 'hazard', viewBox: '0 0 34 30', role: 'img', 'aria-label': 'Information' },
      s('circle', { cx: 17, cy: 15, r: 13, fill: 'var(--info)' }),
      s('path', { d: 'M17 13v8M17 9v.5', stroke: '#fff', 'stroke-width': 3, 'stroke-linecap': 'round' }));
  }

  // ================================================================== charts (hand-made SVG)
  const css = name => getComputedStyle(document.documentElement).getPropertyValue(name).trim();

  /** Line chart. series: [{name, color, points:[{t,v}], dash, band:[{t,lo,hi}]}]; opts: {height, unit, nowLine, yMin, yMax, xFmt} */
  function lineChart(series, opts = {}) {
    const W = opts.width || 640, H = opts.height || 220, L = 40, R = 10, T = 10, B = 24;
    const all = series.flatMap(x => x.points.concat((x.band || []).map(b => ({ t: b.t, v: b.hi })), (x.band || []).map(b => ({ t: b.t, v: b.lo }))));
    if (!all.length) return h('div', { class: 'empty' }, 'No data yet.');
    let t0 = Math.min(...all.map(p => p.t)), t1 = Math.max(...all.map(p => p.t));
    if (t1 === t0) t1 = t0 + 1;
    let y0 = opts.yMin != null ? opts.yMin : Math.min(...all.map(p => p.v)), y1 = opts.yMax != null ? opts.yMax : Math.max(...all.map(p => p.v));
    if (opts.yMin == null) y0 = Math.min(y0, y0 - (y1 - y0) * 0.08);
    if (y1 === y0) y1 = y0 + 1;
    y1 += (y1 - y0) * 0.06;
    const X = t => L + (t - t0) / (t1 - t0) * (W - L - R), Y = v => T + (1 - (v - y0) / (y1 - y0)) * (H - T - B);
    const g = s('svg', { class: 'chart', viewBox: `0 0 ${W} ${H}`, role: 'img', 'aria-label': opts.label || 'chart' });
    const ticks = 4;
    for (let i = 0; i <= ticks; i++) {
      const v = y0 + (y1 - y0) * i / ticks;
      g.appendChild(s('line', { class: 'gridline', x1: L, x2: W - R, y1: Y(v), y2: Y(v) }));
      g.appendChild(s('text', { class: 'lbl', x: L - 5, y: Y(v) + 3, 'text-anchor': 'end' }, fmt(v, Math.abs(y1 - y0) < 10 ? 1 : 0)));
    }
    const xf = opts.xFmt || (t => timeOf(t));
    for (let i = 0; i <= 5; i++) {
      const t = t0 + (t1 - t0) * i / 5;
      g.appendChild(s('text', { class: 'lbl', x: X(t), y: H - 6, 'text-anchor': i === 0 ? 'start' : i === 5 ? 'end' : 'middle' }, xf(t)));
    }
    for (const se of series) {
      if (se.band && se.band.length) {
        const top = se.band.map(b => `${X(b.t)},${Y(b.hi)}`).join(' '), bot = se.band.slice().reverse().map(b => `${X(b.t)},${Y(b.lo)}`).join(' ');
        g.appendChild(s('polygon', { points: top + ' ' + bot, fill: se.color, opacity: 0.14 }));
      }
      if (!se.points.length) continue;
      const d = se.points.map((p, i) => (i ? 'L' : 'M') + X(p.t).toFixed(1) + ' ' + Y(p.v).toFixed(1)).join(' ');
      g.appendChild(s('path', { d, fill: 'none', stroke: se.color, 'stroke-width': se.width || 2.2, 'stroke-dasharray': se.dash ? '6 4' : null, 'stroke-linejoin': 'round' }));
    }
    if (opts.nowLine) {
      const x = X(opts.nowLine);
      g.appendChild(s('line', { class: 'now', x1: x, x2: x, y1: T, y2: H - B }));
      g.appendChild(s('text', { class: 'lbl', x: x + 4, y: T + 9 }, 'now'));
    }
    if (opts.limit != null && opts.limit > y0 && opts.limit < y1) {
      g.appendChild(s('line', { x1: L, x2: W - R, y1: Y(opts.limit), y2: Y(opts.limit), stroke: css('--danger'), 'stroke-dasharray': '2 3' }));
      g.appendChild(s('text', { class: 'lbl', x: W - R, y: Y(opts.limit) - 4, 'text-anchor': 'end' }, opts.limitLabel || ''));
    }
    return g;
  }

  function legend(items) {
    return h('div', { class: 'legend' }, items.map(i => h('span', { style: { color: i.color } }, h('i', { class: i.dash ? 'dash' : '', style: { background: i.color } }), h('span', { style: { color: 'var(--muted)' } }, i.name))));
  }

  /** Vertical bars. items: [{label, value, color, parts:[{v,color}]}] */
  function barChart(items, opts = {}) {
    const W = opts.width || 640, H = opts.height || 200, L = 36, R = 6, T = 10, B = 30;
    const max = opts.max || Math.max(1, ...items.map(i => i.parts ? i.parts.reduce((a, p) => a + p.v, 0) : i.value || 0)) * 1.08;
    const g = s('svg', { class: 'chart', viewBox: `0 0 ${W} ${H}`, role: 'img', 'aria-label': opts.label || 'bar chart' });
    const Y = v => T + (1 - v / max) * (H - T - B);
    for (let i = 0; i <= 4; i++) {
      const v = max * i / 4;
      g.appendChild(s('line', { class: 'gridline', x1: L, x2: W - R, y1: Y(v), y2: Y(v) }));
      g.appendChild(s('text', { class: 'lbl', x: L - 5, y: Y(v) + 3, 'text-anchor': 'end' }, fmt(v)));
    }
    const bw = (W - L - R) / Math.max(1, items.length);
    items.forEach((it, i) => {
      const x = L + i * bw + bw * 0.16, w = bw * 0.68;
      let base = 0;
      const parts = it.parts || [{ v: it.value || 0, color: it.color || css('--info') }];
      for (const p of parts) {
        if (p.v > 0) g.appendChild(s('rect', { x, y: Y(base + p.v), width: w, height: Y(base) - Y(base + p.v), fill: p.color, rx: 1.5 }, s('title', {}, it.label + ': ' + fmt(p.v, 1))));
        base += p.v;
      }
      if (opts.values) g.appendChild(s('text', { class: 'lbl', x: x + w / 2, y: Y(base) - 4, 'text-anchor': 'middle' }, fmt(base, opts.decimals || 0)));
      g.appendChild(s('text', { class: 'lbl', x: x + w / 2, y: H - 12, 'text-anchor': 'middle' }, it.label));
    });
    if (opts.limit != null) {
      g.appendChild(s('line', { x1: L, x2: W - R, y1: Y(opts.limit), y2: Y(opts.limit), stroke: css('--danger'), 'stroke-dasharray': '4 3', 'stroke-width': 1.5 }));
      g.appendChild(s('text', { class: 'lbl', x: L - 5, y: Y(opts.limit) + 3, 'text-anchor': 'end', style: { fill: css('--danger'), fontWeight: 700 } }, opts.limit));
    }
    return g;
  }

  function meter(pct, cls, mark) {
    const m = h('div', { class: 'meter ' + (cls || '') }, h('span', { style: { width: Math.max(0, Math.min(100, pct)) + '%' } }));
    if (mark != null) m.appendChild(h('span', { class: 'mark', style: { left: mark + '%' } }));
    return m;
  }

  /** Half-circle dial for a risk %. */
  function riskDial(pct, band) {
    const color = band === 'HIGH' ? css('--danger') : band === 'MEDIUM' ? css('--hazard') : css('--ok');
    const a = Math.PI * (1 - pct / 100), x = 46 + 36 * Math.cos(a), y = 52 - 36 * Math.sin(a);
    return s('svg', { class: 'risk-dial', viewBox: '0 0 92 92', role: 'img', 'aria-label': 'Risk ' + pct + '%' },
      s('path', { d: 'M10 52a36 36 0 0 1 72 0', fill: 'none', stroke: css('--line'), 'stroke-width': 10 }),
      pct > 0 ? s('path', { d: `M10 52A36 36 0 0 1 ${x.toFixed(1)} ${y.toFixed(1)}`, fill: 'none', stroke: color, 'stroke-width': 10 }) : null,
      s('text', { x: 46, y: 50, 'text-anchor': 'middle', 'font-size': 20, 'font-weight': 900, fill: css('--ink'), 'font-family': 'Overpass, sans-serif' }, pct + '%'),
      s('text', { x: 46, y: 70, 'text-anchor': 'middle', 'font-size': 11, 'font-weight': 800, fill: color, 'font-family': 'Overpass, sans-serif' }, band + ' risk'));
  }

  // ================================================================== sign in
  function showLogin(msg) {
    stopTimer();
    const err = h('div', { class: 'err', role: 'alert' }, msg || '');
    const user = h('input', { type: 'text', id: 'lu', autocomplete: 'username', required: true });
    const pass = h('input', { type: 'password', id: 'lp', autocomplete: 'current-password', required: true });
    const fill = (u, p) => { user.value = u; pass.value = p; };
    const form = h('form', { onsubmit: async e => {
      e.preventDefault();
      err.textContent = '';
      try {
        const r = await api('POST', '/api/login', { username: user.value, password: pass.value });
        state.user = r.user; state.meta = r.meta;
        if (!location.hash || location.hash === '#/') location.hash = '#/overview';
        route();
      } catch (ex) { err.textContent = ex.message; }
    } },
      h('div', { class: 'field' }, h('label', { for: 'lu' }, 'Username'), user),
      h('div', { class: 'field' }, h('label', { for: 'lp' }, 'Password'), pass),
      err,
      h('button', { class: 'btn primary', type: 'submit', style: { width: '100%', justifyContent: 'center', padding: '9px' } }, 'Sign in'),
      h('div', { class: 'demo-logins' }, 'Demo: ',
        [['admin', 'admin123', 'campus admin'], ['rkumar', 'faculty123', 'teacher'], ['aditi', 'student123', 'student'], ['rohit', 'student123', 'student at risk']]
          .map(([u, p, l], i) => [i ? ' · ' : '', h('button', { type: 'button', onclick: () => fill(u, p) }, u), ' (' + l + ')'])));
    clear($app).appendChild(h('main', { class: 'login', id: 'view' },
      h('div', { class: 'login-card' },
        h('div', { class: 'login-sign' },
          h('h1', {}, 'Parisar'),
          h('p', {}, 'Smart Digital Campus Platform · Kanchan Institute of Technology'),
          h('div', { class: 'login-dirs', 'aria-hidden': 'true' },
            h('div', {}, h('span', { class: 'zone zone-A' }, 'A'), 'Live sensors & smart rooms'),
            h('div', {}, h('span', { class: 'zone zone-L' }, 'L'), 'RFID attendance & AI risk'),
            h('div', {}, h('span', { class: 'zone zone-I' }, 'I'), 'Energy forecast & cloud'))),
        h('div', { class: 'login-body' }, form))));
    user.focus();
  }

  // ================================================================== the directory board (menu)
  const PAGES = [
    { id: 'overview', label: 'Overview', icon: 'home', who: 'all' },
    { id: 'map', label: 'Live campus map', icon: 'map', who: 'all' },
    { id: 'classes', label: 'Classes', icon: 'classes', who: 'all' },
    { id: 'students', label: 'Students & AI', icon: 'students', who: 'staff' },
    { id: 'alerts', label: 'Alerts', icon: 'alerts', who: 'staff', count: true },
    { id: 'devices', label: 'IoT devices', icon: 'devices', who: 'staff' },
    { id: 'energy', label: 'Energy', icon: 'energy', who: 'staff' },
    { id: 'analytics', label: 'Analytics', icon: 'analytics', who: 'staff' },
    { id: 'cloud', label: 'Cloud', icon: 'cloud', who: 'admin' },
    { id: 'assistant', label: 'Ask Parisar', icon: 'assistant', who: 'all' },
    { id: 'notices', label: 'Notice board', icon: 'notices', who: 'all' },
    { id: 'admin', label: 'Admin', icon: 'admin', who: 'admin' }
  ];
  const allowed = p => p.who === 'all' || (p.who === 'staff' && isStaff()) || (p.who === 'admin' && isAdmin());

  function shell(pageId) {
    const board = h('nav', { class: 'board', 'aria-label': 'Main menu' },
      h('div', { class: 'brand' },
        s('svg', { class: 'brand-mark', viewBox: '0 0 64 64', 'aria-hidden': 'true' },
          s('rect', { x: 3, y: 3, width: 58, height: 58, rx: 9, fill: 'none', stroke: 'currentColor', 'stroke-width': 3 }),
          s('path', { d: 'M21 47V17h12a9 9 0 0 1 0 18H21', fill: 'none', stroke: 'currentColor', 'stroke-width': 6, 'stroke-linejoin': 'round' }),
          s('circle', { cx: 46, cy: 46, r: 5, fill: '#F2B705' })),
        h('div', {}, h('div', { class: 'brand-name' }, 'Parisar'), h('div', { class: 'brand-sub' }, 'Smart campus · KIT Ranchi'))),
      h('ul', { class: 'dir' }, PAGES.filter(allowed).map(p => h('li', {},
        h('a', { href: '#/' + p.id, 'aria-current': p.id === pageId ? 'page' : null, onclick: () => board.classList.remove('open') },
          pict(p.icon), h('span', {}, p.label),
          p.count && state.counts.alerts ? h('span', { class: 'count' + (state.counts.crit ? ' crit' : ''), 'aria-label': state.counts.alerts + ' open alerts' }, state.counts.alerts) : h('span', {}))))),
      h('div', { class: 'board-foot' },
        h('div', { class: 'who' }, h('div', { class: 'avatar', 'aria-hidden': 'true' }, state.user.initials),
          h('div', {}, h('div', { class: 'who-name' }, state.user.name), h('div', { class: 'who-role' }, state.user.roleLabel))),
        h('div', { class: 'board-btns' },
          h('button', { onclick: toggleTheme, title: 'Switch light / dark' }, isDark() ? 'Day signs' : 'Night signs'),
          h('button', { onclick: changePassword }, 'Password'),
          h('button', { onclick: async () => { await act(() => api('POST', '/api/logout')); state.user = null; location.hash = ''; showLogin(); } }, 'Sign out'))));
    const view = h('main', { class: 'main', id: 'view', tabindex: '-1' }, h('div', { class: 'loading' }, 'Loading…'));
    const menuBtn = h('button', { class: 'btn menu-toggle', onclick: () => board.classList.toggle('open'), 'aria-label': 'Open menu' }, 'Menu');
    clear($app).appendChild(h('div', { class: 'shell' }, board, view));
    return { view, menuBtn };
  }

  function isDark() {
    const t = document.documentElement.getAttribute('data-theme');
    return t ? t === 'dark' : matchMedia('(prefers-color-scheme: dark)').matches;
  }
  function toggleTheme() {
    const next = isDark() ? 'light' : 'dark';
    document.documentElement.setAttribute('data-theme', next);
    try { localStorage.setItem('parisar-theme', next); } catch (e) { /* ignore */ }
    route();
  }

  function changePassword() {
    const cur = h('input', { type: 'password', id: 'pc', autocomplete: 'current-password' });
    const nxt = h('input', { type: 'password', id: 'pn', autocomplete: 'new-password' });
    const close = modal('Change password', h('form', { onsubmit: async e => {
      e.preventDefault();
      if (await act(() => api('POST', '/api/me/password', { current: cur.value, next: nxt.value }), 'Password changed')) close();
    } },
      h('div', { class: 'field' }, h('label', { for: 'pc' }, 'Current password'), cur),
      h('div', { class: 'field' }, h('label', { for: 'pn' }, 'New password (8+ characters, a letter and a number)'), nxt),
      h('button', { class: 'btn primary', type: 'submit' }, 'Change password')));
  }

  /** The "you are here" heading of each page. */
  function here(ctx, title, sub, ...actions) {
    return h('header', { class: 'here' },
      h('div', { class: 'here-left' }, ctx.menuBtn, h('span', { class: 'here-dot', 'aria-hidden': 'true' }),
        h('div', {}, h('h1', {}, title), sub ? h('div', { class: 'sub' }, sub) : null)),
      actions.length ? h('div', { class: 'here-actions' }, actions) : null);
  }

  // ================================================================== router
  function stopTimer() { if (state.timer) { clearInterval(state.timer); state.timer = null; } }
  function every(ms, fn) { stopTimer(); state.timer = setInterval(() => { if (!document.hidden) fn(); }, ms); }

  async function route() {
    stopTimer();
    if (!state.user) {
      try {
        const me = await api('GET', '/api/me');
        if (!me.user) return showLogin();
        state.user = me.user; state.meta = me.meta;
      } catch (e) { return showLogin(); }
    }
    const [path, qs] = (location.hash || '#/overview').slice(2).split('?');
    state.query = new URLSearchParams(qs || '');
    const parts = path.split('/');
    let page = parts[0] || 'overview';
    const id = parts[1] ? decodeURIComponent(parts[1]) : null;
    const pageDef = PAGES.find(p => p.id === page);
    if (pageDef && !allowed(pageDef)) page = 'overview';
    const nav = { room: 'map', student: 'students', session: 'classes', device: 'devices' }[page] || page;
    if (isStaff()) refreshCounts();
    const ctx = shell(nav);
    const render = VIEWS[page] || VIEWS.overview;
    try {
      await render(ctx, id);
    } catch (e) {
      clear(ctx.view).appendChild(h('div', {}, here(ctx, 'Something went wrong'), h('div', { class: 'panel' }, h('p', {}, e.message), h('a', { class: 'btn', href: '#/overview' }, 'Back to the overview'))));
    }
  }

  async function refreshCounts() {
    try {
      const r = await api('GET', '/api/alerts?status=OPEN');
      const n = r.alerts.length, c = r.alerts.filter(a => a.severity === 'CRITICAL').length;
      if (n !== state.counts.alerts || c !== state.counts.crit) {
        state.counts = { alerts: n, crit: c };
        const badge = document.querySelector('.dir a[href="#/alerts"] > span:last-child');
        if (badge) { badge.className = n ? 'count' + (c ? ' crit' : '') : ''; badge.textContent = n || ''; }
      }
    } catch (e) { /* ignore */ }
  }

  const VIEWS = {};

  // ================================================================== campus map (the signboard)
  function campusMap(buildings, selected, onPick, compact) {
    const svg = s('svg', { class: 'campus-map', viewBox: '0 0 100 60', role: 'group', 'aria-label': 'Campus map' });
    svg.appendChild(s('rect', { class: 'lawn', x: 30, y: 22, width: 70, height: 6, rx: 1 }));
    svg.appendChild(s('ellipse', { class: 'lawn', cx: 18, cy: 26, rx: 10, ry: 5 }));
    // walkways
    for (const d of ['M2 26 H98', 'M32 3 V57', 'M60 22 V57', 'M17 18 V34', 'M46 20 V30', 'M80 18 V30'])
      svg.appendChild(s('path', { class: 'path', d }));
    // north arrow + "you are here" (main gate)
    svg.appendChild(s('g', { class: 'north', transform: 'translate(94 50)' }, s('path', { d: 'M0 -4 L2 2 L0 1 L-2 2 Z' }), s('text', { x: 0, y: 6.5, 'text-anchor': 'middle' }, 'N')));
    svg.appendChild(s('g', { class: 'youare' }, s('circle', { cx: 32, cy: 57, r: 1.6 }),
      s('text', { x: 34.5, y: 57.9, style: { fill: 'var(--muted)', fontSize: '2.3px', fontWeight: 700 } }, 'Main gate')));
    for (const b of buildings) {
      const z = zoneOf(b.id);
      const g = s('g', { class: 'bld' + (selected === b.id ? ' sel' : ''), tabindex: 0, role: 'button', 'aria-label': `${b.name}: ${b.people} people${b.openAlerts ? ', ' + b.openAlerts + ' alerts' : ''}`,
        onclick: () => onPick(b.id), onkeydown: e => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onPick(b.id); } } });
      g.appendChild(s('rect', { class: 'body', x: b.x, y: b.y, width: b.w, height: b.h, rx: 1.2, fill: `var(--zone-${z})` }));
      g.appendChild(s('text', { class: 'code', x: b.x + 1.8, y: b.y + 6.6 }, b.code));
      g.appendChild(s('text', { class: 'bname', x: b.x + 1.8, y: b.y + 9.9 }, shortName(b.name)));
      if (!compact) g.appendChild(s('text', { class: 'bstat', x: b.x + 1.8, y: Math.max(b.y + 12.9, b.y + b.h - 1.8) }, b.people + ' people · ' + fmt(b.powerKw) + ' kW'));
      if (!compact && b.waterPct != null) {
        const wx = b.x + b.w - 3.2, wy = b.y + 6, low = b.waterPct < 20;
        g.appendChild(s('rect', { x: wx, y: wy, width: 2, height: 7, rx: .3, fill: 'none', stroke: '#fff', 'stroke-width': .3 }));
        g.appendChild(s('rect', { x: wx + .3, y: wy + .3 + 6.4 * (1 - b.waterPct / 100), width: 1.4, height: 6.4 * b.waterPct / 100, fill: low ? 'var(--hazard)' : '#fff' }, s('title', {}, 'Water tank ' + b.waterPct + '%')));
      }
      if (b.openAlerts) g.appendChild(s('g', { transform: `translate(${b.x + b.w - 4.5} ${b.y + 1.5})` },
        s('path', { class: 'alarm', d: 'M2 0 L4 3.6 H0 Z' }), s('text', { x: 2, y: 3.2, 'text-anchor': 'middle', style: { fill: '#1D1600', fontSize: '2.4px', fontWeight: 900 } }, '!')));
      svg.appendChild(g);
    }
    return svg;
  }

  const shortName = n => n.replace('Academic Block', 'Academic').replace('Admin Block & Cafeteria', 'Admin & Café').replace('Innovation Lab Complex', 'Innovation Labs');

  function roomTile(r) {
    const crit = r.alerts.some(a => a.endsWith('CRITICAL')), warn = r.alerts.length > 0;
    const share = r.capacity ? Math.round(100 * (r.occupancy || 0) / r.capacity) : 0;
    return h('button', { class: 'room-tile' + (crit ? ' crit' : warn ? ' alarm' : ''), onclick: () => { location.hash = '#/room/' + r.id; } },
      h('div', { class: 'rt-head' }, h('span', { class: 'rt-name' }, r.name),
        r.classNow ? h('span', { class: 'chip live' }, r.classNow.courseId) : r.online === false ? h('span', { class: 'chip bad' }, 'sensor silent') : h('span', { class: 'chip dim' }, KINDS[r.kind] || r.kind)),
      h('div', { class: 'row', style: { gap: '8px' } }, h('div', { class: 'grow' }, meter(share, share > 85 ? 'bad' : share > 60 ? 'warn' : 'ok')),
        h('span', { class: 'mono small' }, fmt(r.occupancy) + '/' + r.capacity)),
      h('div', { class: 'readings' },
        h('span', {}, h('b', {}, fmt(r.temp, 1)), ' °C'), h('span', {}, 'CO₂ ', h('b', {}, fmt(r.co2))), h('span', {}, h('b', {}, fmt(r.humidity)), '%'),
        h('span', {}, r.lightsOn ? 'lights on' : 'lights off', r.hasAC ? (r.acOn ? ' · AC on' : ' · AC off') : '')),
      warn ? h('div', { class: 'small', style: { color: crit ? 'var(--danger)' : '#9A6B00', fontWeight: 700 } }, r.alerts.map(a => RULES[a.split(':')[0]] || a).join(', ')) : null);
  }

  VIEWS.map = async (ctx) => {
    let sel = sessionStorage.getItem('parisar-bld') || 'B-A';
    const view = ctx.view;
    const draw = async () => {
      const m = await api('GET', '/api/map');
      const b = m.buildings.find(x => x.id === sel) || m.buildings[0];
      fill(view, 
        here(ctx, 'Live campus map', 'Every room reports temperature, CO₂, humidity and people every 15 seconds. Updated ' + timeOf(m.at) + '.'),
        h('div', { class: 'grid g-wide' },
          h('div', { class: 'panel' }, campusMap(m.buildings, sel, id => { sel = id; try { sessionStorage.setItem('parisar-bld', id); } catch (e) { /* */ } draw(); })),
          h('div', { class: 'panel' },
            h('div', { class: 'panel-head' }, h('h2', {}, h('span', { class: 'zone zone-' + zoneOf(b.id) }, b.code), ' ', b.name)),
            h('dl', { class: 'kv' },
              h('dt', {}, 'People inside'), h('dd', {}, fmt(b.people)),
              b.powerKw != null ? [h('dt', {}, 'Power now'), h('dd', {}, fmt(b.powerKw, 1) + ' kW')] : null,
              b.waterPct != null ? [h('dt', {}, 'Water tank'), h('dd', {}, h('div', { class: 'row' }, h('div', { class: 'grow' }, meter(b.waterPct, b.waterPct < 20 ? 'bad' : b.waterPct < 35 ? 'warn' : 'ok')), b.waterPct + '%'))] : null,
              h('dt', {}, 'Open alerts'), h('dd', {}, b.openAlerts ? h('a', { href: '#/alerts' }, b.openAlerts + ' open') : 'none'),
              h('dt', {}, 'Rooms'), h('dd', {}, b.rooms.length)),
            h('p', { class: 'small muted', style: { marginTop: '12px' } }, 'Pick a building on the map to see its rooms. Pick a room for its 24-hour history and the smart switches.'))),
        h('h2', { style: { margin: '20px 0 10px' } }, 'Rooms in ' + b.name),
        h('div', { class: 'rooms' }, b.rooms.map(roomTile)));
    };
    await draw();
    every(15000, draw);
  };

  // ================================================================== one room
  VIEWS.room = async (ctx, id) => {
    const view = ctx.view;
    const draw = async () => {
      const r = await api('GET', '/api/rooms/' + encodeURIComponent(id));
      const fine = r.fine || [];
      const series = key => fine.filter(p => p.v[key] != null).map(p => ({ t: p.t + 300000, v: p.v[key] }));
      const sw = (label, key, on, disabled) => h('label', { class: 'switch' },
        h('input', { type: 'checkbox', role: 'switch', checked: on, disabled, onchange: async e => {
          const body = {}; body[key] = e.target.checked;
          if (!(await act(() => api('POST', '/api/rooms/' + r.id + '/control', body), label + ' switched ' + (e.target.checked ? 'on' : 'off')))) e.target.checked = !e.target.checked;
          draw();
        } }), label);
      fill(view, 
        here(ctx, r.name, [h('span', { class: 'zone zone-' + zoneOf(r.buildingId) }, zoneOf(r.buildingId)), ' ', r.buildingName, ' · ', KINDS[r.kind], ' · ', r.capacity, ' seats'],
          h('a', { class: 'btn', href: '#/map' }, 'Back to the map')),
        h('div', { class: 'readout', style: { marginBottom: '16px' } },
          h('div', {}, h('div', { class: 'v' }, fmt(r.temp, 1), h('small', {}, '°C')), h('div', { class: 'k' }, 'Temperature')),
          h('div', {}, h('div', { class: 'v' }, fmt(r.co2), h('small', {}, 'ppm')), h('div', { class: 'k' }, 'CO₂ (fresh air ≈ 420)')),
          h('div', {}, h('div', { class: 'v' }, fmt(r.humidity), h('small', {}, '%')), h('div', { class: 'k' }, 'Humidity')),
          h('div', {}, h('div', { class: 'v' }, fmt(r.occupancy), h('small', {}, '/ ' + r.capacity)), h('div', { class: 'k' }, 'People now')),
          h('div', {}, h('div', { class: 'v' }, r.classNow ? r.classNow.courseId : '–'), h('div', { class: 'k' }, r.classNow ? r.classNow.present + ' tapped in' : 'No class now'))),
        h('div', { class: 'grid g-wide' },
          h('div', { class: 'panel' }, h('h2', {}, 'Last 24 hours'),
            lineChart([{ name: 'Temperature', color: css('--zone-H'), points: series('temp') }], { height: 170, label: 'temperature', limit: 32, limitLabel: 'heat alert 32 °C' }),
            legend([{ name: 'Temperature °C', color: css('--zone-H') }]),
            lineChart([{ name: 'CO2', color: css('--zone-L'), points: series('co2') }], { height: 170, label: 'CO2', limit: 1200, limitLabel: 'stale air 1200 ppm' }),
            legend([{ name: 'CO₂ ppm', color: css('--zone-L') }]),
            lineChart([{ name: 'People', color: css('--info'), points: series('occupancy') }], { height: 150, yMin: 0, label: 'people' }),
            legend([{ name: 'People in the room', color: css('--info') }])),
          h('div', { class: 'grid', style: { alignContent: 'start' } },
            h('div', { class: 'panel' }, h('h2', {}, 'Smart switches'),
              h('p', { class: 'small muted' }, r.canControl ? 'Switch the room from here. The room also switches its lights on when a class starts.' : 'Only staff can switch rooms.'),
              h('div', { style: { display: 'grid', gap: '12px' } },
                sw('Lights', 'lights', r.lightsOn, !r.canControl),
                r.hasAC ? sw('Air conditioning', 'ac', r.acOn, !r.canControl || r.kind === 'SERVER') : h('span', { class: 'muted small' }, 'No AC in this room.'))),
            h('div', { class: 'panel' }, h('h2', {}, 'Weekly timetable'),
              r.timetable.length ? h('div', { class: 'tt' }, r.timetable.sort((a, b) => a.day - b.day || a.hour - b.hour).map(t =>
                h('div', { class: 'tt-row' }, h('span', { class: 'tt-time' }, DAYS[t.day] + ' ' + t.hour), h('span', {}, h('div', { class: 't1' }, t.courseId), h('div', { class: 't2' }, t.courseName)), h('span', {}))))
                : h('p', { class: 'muted' }, 'No classes are held here.')),
            r.alerts.length ? h('div', { class: 'panel' }, h('h2', {}, 'Alerts here'), r.alerts.map(a => h('div', { class: 'row' }, hazard(a.split(':')[1]), RULES[a.split(':')[0]])), h('a', { href: '#/alerts' }, 'Open the alert board')) : null)));
    };
    await draw();
    every(15000, draw);
  };

  // ================================================================== overview
  VIEWS.overview = async (ctx) => {
    if (!isStaff()) return studentHome(ctx);
    const view = ctx.view;
    const draw = async () => {
      const [o, m] = await Promise.all([api('GET', '/api/overview'), api('GET', '/api/map')]);
      const alertsOpen = o.alerts.CRITICAL + o.alerts.WARNING + o.alerts.INFO;
      const past = (o.past || []).map(p => ({ t: p.t, v: p.kw }));
      const fc = (o.forecast || []).map(p => ({ t: p.t, v: p.kw }));
      fill(view, 
        here(ctx, 'Campus right now', new Date().toLocaleDateString('en-IN', { weekday: 'long', day: 'numeric', month: 'long' }) + ' · ' + state.meta.college),
        h('div', { class: 'strip', role: 'list' },
          tile(fmt(o.people), 'people on campus', '#/map'),
          tile(o.liveClasses, 'classes running', '#/classes'),
          tile([fmt(o.powerKw), h('small', {}, 'kW')], 'power now · ' + fmt(o.kwhToday) + ' kWh today', '#/energy'),
          tile([o.devicesOnline, h('small', {}, '/' + o.devices)], 'devices online', '#/devices', o.devicesOnline < o.devices),
          tile(alertsOpen, o.alerts.CRITICAL ? o.alerts.CRITICAL + ' critical alerts' : 'open alerts', '#/alerts', o.alerts.CRITICAL > 0),
          tile(o.highRisk, 'students at high risk (AI)', '#/students?risk=HIGH', o.highRisk > 0)),
        h('div', { class: 'grid g-wide' },
          h('div', { class: 'panel' }, h('div', { class: 'panel-head' }, h('h2', {}, 'Campus map'), h('a', { href: '#/map', class: 'small' }, 'Open the live map')),
            campusMap(m.buildings, null, id => { try { sessionStorage.setItem('parisar-bld', id); } catch (e) { /* */ } location.hash = '#/map'; })),
          h('div', { class: 'panel' }, h('div', { class: 'panel-head' }, h('h2', {}, 'Needs attention'), h('a', { href: '#/alerts', class: 'small' }, 'All alerts')),
            o.recentAlerts.length ? o.recentAlerts.map(a => h('div', { class: 'alert-row' }, hazard(a.severity),
              h('div', {}, h('div', { class: 'msg' }, a.message), h('div', { class: 'meta' }, RULES[a.rule] + ' · ' + whenOf(a.at))), h('span', {})))
              : h('div', { class: 'empty' }, 'Nothing needs attention. All sensors look normal.'))),
        h('div', { class: 'grid g2', style: { marginTop: '16px' } },
          h('div', { class: 'panel' }, h('div', { class: 'panel-head' }, h('h2', {}, 'Power: last 36 h and AI forecast'), h('a', { href: '#/energy', class: 'small' }, 'Energy details')),
            lineChart([{ name: 'actual', color: css('--info'), points: past },
              { name: 'forecast', color: css('--zone-I'), points: fc, dash: true, band: (o.forecast || []).map(p => ({ t: p.t, lo: p.low, hi: p.high })) }],
              { height: 210, nowLine: Date.now(), label: 'campus power' }),
            legend([{ name: 'Measured kW (smart meters)', color: css('--info') }, { name: 'Forecast kW, next 12 h', color: css('--zone-I'), dash: true }]),
            h('p', { class: 'small muted', style: { marginTop: '6px' } }, 'Next peak about ' + fmt(o.peakKw) + ' kW at ' + hourText(new Date(o.peakAt).getHours()) + '. Today so far ₹' + fmt(o.costToday) + '.')),
          h('div', { class: 'panel' }, h('div', { class: 'panel-head' }, h('h2', {}, 'Attendance, last 6 weeks'), h('a', { href: '#/classes', class: 'small' }, 'Classes')),
            barChart(o.campusAttendance.map(w => ({ label: dateOf(w.week), value: w.pct || 0, color: (w.pct || 0) < 75 ? css('--hazard') : css('--ok') })), { max: 100, limit: 75, limitLabel: '75% rule', values: true, label: 'weekly attendance' }),
            h('p', { class: 'small muted' }, 'Marked automatically by RFID card taps at the classroom doors.' + (o.attendanceToday != null ? ' Today: ' + o.attendanceToday + '%.' : '')))));
    };
    await draw();
    every(15000, draw);
  };

  function tile(value, label, href, warn) {
    return h('div', { role: 'listitem' }, h('a', { href }, h('div', { class: 'v' + (warn ? ' warn' : '') }, value), h('div', { class: 'k' }, label)));
  }

  // ---- the student's home page: their own report
  async function studentHome(ctx) {
    const [o, c, n] = await Promise.all([api('GET', '/api/overview'), api('GET', '/api/classes'), api('GET', '/api/notices')]);
    const me = o.me;
    fill(ctx.view, 
      here(ctx, 'Hello, ' + state.user.name.split(' ')[0], me.roll + ' · ' + me.program + ' · year ' + me.year + ' · card ' + me.cardId),
      h('div', { class: 'grid g-wide' },
        h('div', { class: 'grid', style: { alignContent: 'start' } },
          h('div', { class: 'panel' }, h('div', { class: 'panel-head' }, h('h2', {}, 'My attendance'), h('span', { class: 'chip ' + (me.attendance >= 75 ? 'ok' : 'bad') }, 'overall ' + fmt(me.attendance, 1) + '%')),
            me.courses.map(attRow), h('p', { class: 'small muted', style: { marginTop: '8px' } }, 'The black line is the 75% you need to sit the exam. Tap your ID card at the classroom door to be marked present.')),
          h('div', { class: 'panel' }, h('h2', {}, 'Week by week'),
            barChart(me.weekly.map(w => ({ label: dateOf(w.week), value: w.pct || 0, color: (w.pct || 0) < 75 ? css('--hazard') : css('--ok') })), { max: 100, limit: 75, limitLabel: '75%', values: true, height: 180 }))),
        h('div', { class: 'grid', style: { alignContent: 'start' } },
          riskPanel(me.risk, true),
          h('div', { class: 'panel' }, h('h2', {}, 'My classes today'), timetable(c.today, false)),
          h('div', { class: 'panel' }, h('div', { class: 'panel-head' }, h('h2', {}, 'Latest notices'), h('a', { href: '#/notices', class: 'small' }, 'Notice board')),
            n.notices.slice(0, 3).map(x => h('div', { style: { marginBottom: '8px' } }, h('div', { style: { fontWeight: 700 } }, x.title), h('div', { class: 'small muted' }, dateOf(x.postedAt))))))));
  }

  function attRow(c) {
    return h('div', { class: 'att-row' },
      h('div', { class: 'name' }, c.courseId + ' ' + c.courseName, h('small', {}, c.attended + ' of ' + c.held + ' classes' + (c.ok ? '' : ' · attend the next ' + c.classesNeeded + ' in a row to reach 75%'))),
      meter(c.pct, c.ok ? 'ok' : 'bad', 75),
      h('span', { class: 'mono', style: { textAlign: 'right', fontWeight: 600, color: c.ok ? 'var(--ink)' : 'var(--danger)' } }, fmt(c.pct, 0) + '%'));
  }

  /** The AI risk report: dial, reasons, what each input pushes, what to do. */
  function riskPanel(r, mine) {
    return h('div', { class: 'panel' },
      h('h2', {}, mine ? 'How am I doing? (AI)' : 'AI risk report'),
      h('div', { class: 'risk-head' }, riskDial(r.riskPct, r.band),
        h('div', {}, h('div', { style: { fontWeight: 700 } }, r.band === 'HIGH' ? 'High chance of failing this semester' : r.band === 'MEDIUM' ? 'Some risk – a few changes will help' : 'On track'),
          h('ul', { class: 'plain small', style: { marginTop: '4px' } }, r.reasons.map(x => h('li', {}, x))))),
      h('details', {}, h('summary', { class: 'small', style: { cursor: 'pointer', fontWeight: 700 } }, 'What the model looked at'),
        h('div', { style: { marginTop: '8px' } }, r.factors.map(f => {
          const w = Math.min(50, Math.abs(f.push) * 22);
          return h('div', { class: 'push' }, h('span', {}, f.label + ': ', h('b', {}, fmt(f.value, 1) + f.unit), h('span', { class: 'muted small' }, ' (typical ' + fmt(f.typical, 1) + f.unit + ')')),
            h('div', { class: 'push-bar', title: f.push > 0 ? 'pushes towards fail' : 'pushes towards pass' }, h('span', { class: f.push > 0 ? 'up' : 'down', style: { width: w + '%' } })));
        }), h('p', { class: 'small muted' }, 'Red to the right = pushes towards failing, green to the left = pushes towards passing.'))),
      r.actions.length ? h('div', { style: { marginTop: '10px' } }, h('h3', { style: { marginBottom: '4px' } }, mine ? 'What helps' : 'Suggested actions'),
        h('ul', { class: 'plain small' }, (mine ? r.tips : r.actions).map(a => h('li', {}, a)))) : null);
  }

  function timetable(list, staff) {
    if (!list.length) return h('div', { class: 'empty' }, 'No classes today.');
    return h('div', { class: 'tt' }, list.map(t => h('div', { class: 'tt-row' },
      h('span', { class: 'tt-time' }, pad(t.hour) + ':00'),
      h('span', {}, h('div', { class: 't1' }, t.courseId + ' ' + t.courseName), h('div', { class: 't2' }, t.roomName + ' · ' + t.faculty + (t.sessionId ? ' · ' + t.present + '/' + t.enrolled + ' present' : ''))),
      h('span', {}, t.state === 'LIVE' ? h('span', { class: 'chip live live-pulse' }, 'Live') : t.state === 'DONE' ? h('span', { class: 'chip dim' }, 'Over') : t.state === 'MISSED' ? h('span', { class: 'chip warn' }, 'Not held') : h('span', { class: 'chip info' }, 'Later'),
        staff && t.sessionId ? [' ', h('a', { class: 'btn small', href: '#/session/' + encodeURIComponent(t.sessionId) }, 'Open')] : null))));
  }

  // ================================================================== classes
  VIEWS.classes = async (ctx) => {
    const view = ctx.view;
    const draw = async () => {
      const c = await api('GET', '/api/classes');
      const staff = isStaff();
      fill(view, 
        here(ctx, 'Classes', 'Classes open by themselves from the timetable. Students are marked present when they tap their ID card at the classroom door.'),
        c.live.length ? h('div', { class: 'panel', style: { marginBottom: '16px', borderLeft: '6px solid var(--danger)' } },
          h('h2', {}, 'Running now'),
          c.live.map(s1 => h('div', { class: 'tt-row' }, h('span', { class: 'chip live live-pulse' }, 'Live'),
            h('span', {}, h('div', { class: 't1' }, s1.courseId + ' ' + s1.courseName), h('div', { class: 't2' }, s1.roomName + ' · ' + s1.faculty + ' · started ' + timeOf(s1.startedAt))),
            h('span', { class: 'row' }, h('span', { class: 'mono' }, s1.present + '/' + s1.enrolled), staff ? h('a', { class: 'btn small primary', href: '#/session/' + encodeURIComponent(s1.id) }, 'Attendance') : null)))) : null,
        h('div', { class: 'grid g2' },
          h('div', { class: 'panel' }, h('h2', {}, 'Today'), timetable(c.today, staff)),
          h('div', { class: 'panel' }, h('h2', {}, staff ? (isAdmin() ? 'All courses' : 'My courses') : 'My courses'),
            h('div', { class: 'table-wrap' }, h('table', {},
              h('thead', {}, h('tr', {}, h('th', {}, 'Course'), h('th', {}, 'Weekly slots'), h('th', { class: 'num' }, staff ? 'Avg' : 'Mine'), staff ? h('th', { class: 'num' }, '<75%') : null, staff ? h('th', {}) : null)),
              h('tbody', {}, c.courses.map(x => h('tr', {},
                h('td', {}, h('b', {}, x.id), h('div', { class: 'small muted' }, x.name + ' · ' + x.roomName)),
                h('td', { class: 'small' }, x.slots.map(sl => DAYS[sl.day] + ' ' + sl.hour).join(', ')),
                h('td', { class: 'num' }, staff ? fmt(x.avgAttendance, 0) + '%' : (x.myHeld ? fmt(100 * x.myAttended / x.myHeld, 0) + '%' : '–')),
                staff ? h('td', { class: 'num', style: { color: x.below75 ? 'var(--danger)' : null } }, x.below75) : null,
                staff ? h('td', {}, h('button', { class: 'btn small', onclick: async () => {
                  const r = await act(() => api('POST', '/api/courses/' + x.id + '/start'), 'Extra class started');
                  if (r) location.hash = '#/session/' + encodeURIComponent(r.id);
                } }, 'Extra class')) : null))))),
            staff ? h('p', { class: 'small muted', style: { marginTop: '8px' } }, 'An extra class opens now in the course room and closes after one hour (good for make-up classes and for the demo outside timetable hours).') : null)));
    };
    await draw();
    every(15000, draw);
  };

  VIEWS.session = async (ctx, id) => {
    const view = ctx.view;
    const draw = async () => {
      const se = await api('GET', '/api/sessions/' + encodeURIComponent(id));
      const pct = se.enrolled ? Math.round(100 * se.present / se.enrolled) : 0;
      fill(view, 
        here(ctx, se.courseId + ' · ' + se.courseName, se.roomName + ' · ' + se.date + ' at ' + pad(se.hour) + ':00 · ' + se.faculty + (se.source === 'EXTRA' ? ' · extra class' : ''),
          se.open ? h('button', { class: 'btn', onclick: async () => { await act(() => api('POST', '/api/sessions/' + encodeURIComponent(id) + '/simulate'), 'Card taps are on the way'); } }, 'Simulate card taps') : null,
          se.open ? h('button', { class: 'btn danger', onclick: async () => { await act(() => api('POST', '/api/sessions/' + encodeURIComponent(id) + '/end'), 'Class ended'); draw(); } }, 'End class') : null,
          h('a', { class: 'btn', href: '#/classes' }, 'Back to classes')),
        h('div', { class: 'readout', style: { marginBottom: '16px' } },
          h('div', {}, h('div', { class: 'v' }, se.present, h('small', {}, '/ ' + se.enrolled)), h('div', { class: 'k' }, 'Present')),
          h('div', {}, h('div', { class: 'v' }, pct, h('small', {}, '%')), h('div', { class: 'k' }, 'Attendance')),
          h('div', {}, h('div', { class: 'v' }, se.students.filter(x => x.how === 'RFID').length), h('div', { class: 'k' }, 'By card tap')),
          h('div', {}, h('div', { class: 'v' }, se.students.filter(x => x.how === 'MANUAL').length), h('div', { class: 'k' }, 'Marked by teacher')),
          h('div', {}, h('div', { class: 'v' }, se.open ? h('span', { class: 'chip live live-pulse' }, 'Live') : 'Over'), h('div', { class: 'k' }, se.open ? 'Taps are counted now' : 'Ended ' + timeOf(se.endedAt)))),
        h('div', { class: 'panel' }, h('div', { class: 'panel-head' }, h('h2', {}, 'Roll call'), h('span', { class: 'small muted' }, 'Tick or untick to correct by hand (allowed for 7 days).')),
          h('div', { class: 'roll' }, se.students.map(st => h('label', { class: st.present ? 'here-now' : '' },
            h('input', { type: 'checkbox', checked: st.present, onchange: async e => {
              await act(() => api('POST', '/api/sessions/' + encodeURIComponent(id) + '/mark', { studentId: st.id, present: e.target.checked }));
              draw();
            } }),
            h('span', {}, h('div', { style: { fontWeight: 600 } }, st.name), h('div', { class: 'small muted' }, st.roll)),
            h('span', { class: 'how' }, st.present ? (st.how === 'RFID' ? 'card ' : 'hand ') + timeOf(st.at) : ''))))));
    };
    await draw();
    every(5000, draw);
  };

  // ================================================================== students + AI model
  VIEWS.students = async (ctx) => {
    const view = ctx.view;
    const q = { q: state.query.get('q') || '', risk: state.query.get('risk') || '', program: '' };
    let tab = 'list';
    const [first, model] = await Promise.all([api('GET', '/api/students'), api('GET', '/api/ai/model')]);
    const body = h('div');
    const draw = async () => {
      const params = new URLSearchParams(Object.entries(q).filter(([, v]) => v));
      const d = await api('GET', '/api/students?' + params);
      fill(body, tab === 'list' ? studentList(d) : modelCard(model));
    };
    const search = h('input', { type: 'search', placeholder: 'Search by name, roll number or card', value: q.q, 'aria-label': 'Search students', oninput: e => { q.q = e.target.value; clearTimeout(search._t); search._t = setTimeout(draw, 250); } });
    const riskSel = h('select', { 'aria-label': 'Risk band', onchange: e => { q.risk = e.target.value; draw(); } },
      h('option', { value: '' }, 'Every risk band'), ['HIGH', 'MEDIUM', 'LOW'].map(b => h('option', { value: b, selected: q.risk === b ? 'selected' : null }, b.charAt(0) + b.slice(1).toLowerCase() + ' risk')));
    const progSel = h('select', { 'aria-label': 'Programme', onchange: e => { q.program = e.target.value; draw(); } },
      h('option', { value: '' }, 'Every programme'), first.programs.map(p => h('option', { value: p }, p)));
    const tabs = h('div', { class: 'tabs', role: 'tablist' },
      [['list', 'Students'], ['model', 'How the AI model works']].map(([k, l]) => h('button', { role: 'tab', 'aria-selected': String(tab === k), onclick: e => {
        tab = k; tabs.querySelectorAll('button').forEach(b => b.setAttribute('aria-selected', 'false')); e.target.setAttribute('aria-selected', 'true');
        filters.hidden = k !== 'list'; draw();
      } }, l)));
    const filters = h('div', { class: 'filters' }, search, riskSel, progSel);
    fill(view, 
      here(ctx, 'Students & AI', isAdmin() ? 'All students, sorted by the AI risk score.' : 'Students in your courses and your mentees, sorted by the AI risk score.'),
      h('div', { class: 'strip', style: { gridTemplateColumns: 'repeat(3, minmax(0,1fr))' } },
        tile(first.bands.HIGH, 'high risk', '#/students?risk=HIGH', first.bands.HIGH > 0),
        tile(first.bands.MEDIUM, 'medium risk', '#/students?risk=MEDIUM'),
        tile(first.bands.LOW, 'low risk', '#/students?risk=LOW')),
      tabs, filters, body);
    await draw();
  };

  function studentList(d, limit = 30) {
    if (!d.students.length) return h('div', { class: 'panel empty' }, 'No students match. Clear the search or pick another risk band.');
    const box = h('div', { class: 'panel' });
    const more = d.students.length > limit ? h('div', { class: 'row', style: { marginTop: '12px', justifyContent: 'center' } },
      h('span', { class: 'small muted' }, 'Showing ' + limit + ' of ' + d.students.length + ' (highest risk first).'),
      h('button', { class: 'btn small', onclick: () => box.replaceWith(studentList(d, limit + 30)) }, 'Show 30 more')) : null;
    return fill(box, studentTable(d.students.slice(0, limit)), more);
  }

  function studentTable(rows) {
    const d = { students: rows };
    return h('div', { class: 'table-wrap' }, h('table', {},
      h('thead', {}, h('tr', {}, h('th', {}, 'Student'), h('th', {}, 'Programme'), h('th', { class: 'num' }, 'Attendance'), h('th', { class: 'num' }, 'Internal'),
        h('th', { class: 'num' }, 'Library (30 d)'), h('th', {}, 'AI risk'))),
      h('tbody', {}, d.students.map(st => h('tr', { class: 'click', tabindex: 0, onclick: () => { location.hash = '#/student/' + st.id; }, onkeydown: e => { if (e.key === 'Enter') location.hash = '#/student/' + st.id; } },
        h('td', {}, h('b', {}, st.name), h('div', { class: 'small muted' }, st.roll)),
        h('td', { class: 'small' }, st.program),
        h('td', { class: 'num', style: { color: st.attendance < 75 ? 'var(--danger)' : null } }, fmt(st.attendance, 0) + '%'),
        h('td', { class: 'num' }, fmt(st.internalPct, 0) + '%'),
        h('td', { class: 'num' }, st.libraryVisits30),
        h('td', {}, h('div', { class: 'row', style: { gap: '8px', flexWrap: 'nowrap' } },
          h('div', { style: { width: '90px' } }, meter(st.riskPct, st.band === 'HIGH' ? 'bad' : st.band === 'MEDIUM' ? 'warn' : 'ok')),
          h('span', { class: 'mono band-' + st.band, style: { fontWeight: 600 } }, st.riskPct + '%'))))))));
  }

  function modelCard(m) {
    const c = m.confusion;
    const retrain = isAdmin() ? h('button', { class: 'btn', onclick: async () => {
      const r = await act(() => api('POST', '/api/ai/retrain', { epochs: 1500, learningRate: 0.3, l2: 0.01 }), 'Model retrained');
      if (r) route();
    } }, 'Retrain the model') : null;
    return h('div', { class: 'grid g2' },
      h('div', { class: 'panel' }, h('div', { class: 'panel-head' }, h('h2', {}, 'Risk model'), retrain),
        h('p', {}, m.algorithm + '. It learned from last year’s batch (' + m.trainRows + ' students, ' + m.failRate + '% failed) and was tested on ' + m.testRows + ' students it had never seen.'),
        h('div', { class: 'stat-row' },
          h('div', {}, h('b', {}, m.accuracy + '%'), h('span', {}, 'accuracy')),
          h('div', {}, h('b', {}, m.precision + '%'), h('span', {}, 'precision')),
          h('div', {}, h('b', {}, m.recall + '%'), h('span', {}, 'recall')),
          h('div', {}, h('b', {}, (m.auc / 100).toFixed(2)), h('span', {}, 'AUC'))),
        h('h3', { style: { margin: '10px 0 6px' } }, 'Test results (confusion matrix)'),
        h('table', {}, h('thead', {}, h('tr', {}, h('th', {}), h('th', { class: 'num' }, 'Really failed'), h('th', { class: 'num' }, 'Really passed'))),
          h('tbody', {}, h('tr', {}, h('td', {}, 'Model said "fail"'), h('td', { class: 'num' }, c.tp), h('td', { class: 'num' }, c.fp)),
            h('tr', {}, h('td', {}, 'Model said "pass"'), h('td', { class: 'num' }, c.fn), h('td', { class: 'num' }, c.tn)))),
        h('p', { class: 'small muted', style: { marginTop: '8px' } }, 'Precision: of the students it flags, how many really failed. Recall: of the students who failed, how many it caught.')),
      h('div', { class: 'panel' }, h('h2', {}, 'What it learned'),
        h('p', { class: 'small muted' }, 'P(fail) = sigmoid(b + w₁·z₁ + … + w₅·z₅), z = (value − mean) ÷ std. A negative weight means more of it lowers the risk.'),
        h('table', {}, h('thead', {}, h('tr', {}, h('th', {}, 'Input'), h('th', { class: 'num' }, 'Weight'), h('th', { class: 'num' }, 'Mean'))),
          h('tbody', {}, m.weights.map(w => h('tr', {}, h('td', {}, w.label), h('td', { class: 'num', style: { color: w.weight < 0 ? 'var(--ok)' : 'var(--danger)' } }, w.weight), h('td', { class: 'num' }, w.mean))))),
        h('h3', { style: { margin: '12px 0 4px' } }, 'Training: the error going down'),
        lineChart([{ name: 'loss', color: css('--zone-L'), points: m.loss.map((v, i) => ({ t: i, v })) }], { height: 140, xFmt: t => Math.round(t / (m.loss.length - 1) * m.epochs) + '', label: 'loss curve' })));
  }

  VIEWS.student = async (ctx, id) => {
    const st = await api('GET', '/api/students/' + encodeURIComponent(id));
    fill(ctx.view, 
      here(ctx, st.name, st.roll + ' · ' + st.program + ' · year ' + st.year + ' · mentor ' + st.mentor + ' · card ' + st.cardId, h('a', { class: 'btn', href: '#/students' }, 'All students')),
      h('div', { class: 'grid g-wide' },
        h('div', { class: 'grid', style: { alignContent: 'start' } },
          h('div', { class: 'panel' }, h('div', { class: 'panel-head' }, h('h2', {}, 'Attendance by course'), h('span', { class: 'chip ' + (st.attendance >= 75 ? 'ok' : 'bad') }, 'overall ' + fmt(st.attendance, 1) + '%')), st.courses.map(attRow)),
          h('div', { class: 'panel' }, h('h2', {}, 'Week by week'),
            barChart(st.weekly.map(w => ({ label: dateOf(w.week), value: w.pct || 0, color: (w.pct || 0) < 75 ? css('--hazard') : css('--ok') })), { max: 100, limit: 75, limitLabel: '75%', values: true, height: 180 })),
          h('div', { class: 'panel' }, h('h2', {}, 'What the card readers saw'),
            st.recentTaps.length ? h('div', { class: 'tt' }, st.recentTaps.map(t => h('div', { class: 'tt-row' }, h('span', { class: 'mono small' }, dateOf(t.at)), h('span', {}, h('div', { class: 't1' }, t.where), h('div', { class: 't2' }, t.what)), h('span', { class: 'mono small' }, timeOf(t.at)))))
              : h('div', { class: 'empty' }, 'No taps yet.'))),
        h('div', { class: 'grid', style: { alignContent: 'start' } },
          riskPanel(st.risk, false),
          h('div', { class: 'panel' }, h('h2', {}, 'Academic record'),
            h('dl', { class: 'kv' }, h('dt', {}, 'Internal marks'), h('dd', {}, fmt(st.internalPct, 1) + '%'), h('dt', {}, 'Assignments handed in'), h('dd', {}, fmt(st.assignmentPct, 0) + '%'),
              h('dt', {}, 'Library visits (30 days)'), h('dd', {}, st.libraryVisits30), h('dt', {}, 'Learning portal'), h('dd', {}, fmt(st.lmsHours, 1) + ' h / week'))))));
  };

  // ================================================================== alerts
  VIEWS.alerts = async (ctx) => {
    let status = 'OPEN';
    const list = h('div', { class: 'panel' });
    const draw = async () => {
      const r = await api('GET', '/api/alerts?status=' + status);
      fill(list, r.alerts.length ? r.alerts.map(alertRow) : h('div', { class: 'empty' }, status === 'OPEN' ? 'No open alerts. All sensors look normal.' : 'No alerts here.'));
    };
    const alertRow = a => h('div', { class: 'alert-row' + (a.status === 'RESOLVED' ? ' resolved' : '') }, hazard(a.severity),
      h('div', {}, h('div', { class: 'msg' }, a.message),
        h('div', { class: 'meta' }, [RULES[a.rule], a.roomName || a.buildingName, a.deviceId, 'since ' + whenOf(a.createdAt),
          a.status === 'ACK' ? 'seen by ' + a.ackByName : null, a.status === 'RESOLVED' ? 'resolved (' + a.resolvedByName + (a.note ? ': ' + a.note : '') + ')' : null].filter(Boolean).join(' · '))),
      h('div', { class: 'acts' },
        a.status === 'OPEN' ? h('button', { class: 'btn small', onclick: async () => { await act(() => api('POST', '/api/alerts/' + a.id + '/ack'), 'Marked as seen'); draw(); refreshCounts(); } }, 'I’m on it') : null,
        a.status !== 'RESOLVED' ? h('button', { class: 'btn small primary', onclick: () => resolveDialog(a, draw) }, 'Resolve') : null,
        a.roomId ? h('a', { class: 'btn small', href: '#/room/' + a.roomId }, 'Room') : h('a', { class: 'btn small', href: '#/device/' + a.deviceId }, 'Device')));
    const tabs = h('div', { class: 'tabs', role: 'tablist' }, [['OPEN', 'Open'], ['RESOLVED', 'Resolved'], ['ALL', 'All']].map(([k, l]) =>
      h('button', { role: 'tab', 'aria-selected': String(status === k), onclick: e => { status = k; tabs.querySelectorAll('button').forEach(b => b.setAttribute('aria-selected', 'false')); e.target.setAttribute('aria-selected', 'true'); draw(); } }, l)));
    fill(ctx.view, 
      here(ctx, 'Alerts', 'Raised by fixed limits (heat 32 °C, CO₂ 1200 ppm, water 20%, silent 5 min, AC/lights on in an empty room 30 min) and by the AI anomaly check. Most close by themselves when the value is normal again.'),
      tabs, list);
    await draw();
    every(15000, draw);
  };

  function resolveDialog(a, after) {
    const note = h('input', { type: 'text', id: 'rn', placeholder: 'For example: AC repaired, pump checked' });
    const close = modal('Resolve alert', h('form', { onsubmit: async e => {
      e.preventDefault();
      if (await act(() => api('POST', '/api/alerts/' + a.id + '/resolve', { note: note.value }), 'Alert resolved')) { close(); after(); refreshCounts(); }
    } }, h('p', {}, a.message), h('div', { class: 'field' }, h('label', { for: 'rn' }, 'What was done (optional)'), note), h('button', { class: 'btn primary', type: 'submit' }, 'Resolve')));
  }

  // ================================================================== IoT devices
  const TYPE_NAMES = { ENV: 'Room sensors', ENERGY: 'Smart meters', WATER: 'Water tanks', GATE: 'RFID card readers' };
  function latestText(d) {
    const l = d.latest || {};
    if (d.type === 'ENV') return [fmt(l.temp, 1) + ' °C', 'CO₂ ' + fmt(l.co2), fmt(l.humidity) + '%', fmt(l.occupancy) + ' people'].join('  ');
    if (d.type === 'ENERGY') return fmt(l.power, 1) + ' kW';
    if (d.type === 'WATER') return fmt(l.level) + '% full';
    return fmt(l.taps) + ' taps';
  }

  VIEWS.devices = async (ctx) => {
    const view = ctx.view;
    const draw = async () => {
      const r = await api('GET', '/api/devices');
      const online = r.devices.filter(d => d.online).length;
      fill(view, 
        here(ctx, 'IoT devices', online + ' of ' + r.devices.length + ' online. A device that sends nothing for 5 minutes is reported.',
          isAdmin() ? h('button', { class: 'btn primary', onclick: registerDevice }, 'Add a device') : null),
        Object.keys(TYPE_NAMES).map(type => {
          const list = r.devices.filter(d => d.type === type);
          if (!list.length) return null;
          return h('div', { class: 'panel', style: { marginBottom: '16px' } }, h('h2', {}, TYPE_NAMES[type] + ' (' + list.length + ')'),
            h('div', { class: 'table-wrap' }, h('table', {},
              h('thead', {}, h('tr', {}, h('th', {}, 'Device'), h('th', {}, 'Where'), h('th', {}, 'Latest'), h('th', {}, 'Last message'), type === 'ENV' ? h('th', { class: 'num' }, 'Battery') : null, h('th', { class: 'num' }, 'Messages'))),
              h('tbody', {}, list.map(d => h('tr', { class: 'click', tabindex: 0, onclick: () => { location.hash = '#/device/' + d.id; }, onkeydown: e => { if (e.key === 'Enter') location.hash = '#/device/' + d.id; } },
                h('td', {}, h('div', { class: 'row', style: { gap: '8px', flexWrap: 'nowrap' } }, h('span', { class: 'dot ' + (d.online ? 'on' : 'off'), title: d.online ? 'online' : 'offline' }),
                  h('span', {}, h('b', { class: 'mono small' }, d.id), d.openAlerts ? h('span', { class: 'chip warn', style: { marginLeft: '6px' } }, d.openAlerts + ' alert') : null, !d.active ? h('span', { class: 'chip dim', style: { marginLeft: '6px' } }, 'switched off') : null))),
                h('td', { class: 'small' }, d.roomName || d.buildingName),
                h('td', { class: 'mono small' }, latestText(d)),
                h('td', { class: 'small nowrap' }, whenOf(d.lastSeen)),
                type === 'ENV' ? h('td', { class: 'num', style: { color: d.battery < 30 ? 'var(--danger)' : null } }, d.battery + '%') : null,
                h('td', { class: 'num' }, fmt(d.messages))))))));
        }));
    };
    await draw();
    every(15000, draw);
  };

  function curlFor(id, key, type) {
    const body = type === 'GATE' ? '{"cardId":"RF-XXXXXX"}' : type === 'ENERGY' ? '{"power":42.5}' : type === 'WATER' ? '{"level":76}' : '{"temp":24.5,"humidity":52,"co2":640,"occupancy":12}';
    return `curl -X POST ${location.origin}/iot/ingest -H "Content-Type: application/json" -H "X-Device-Id: ${id}" -H "X-Device-Key: ${key}" -d '${body}'`;
  }
  function showKey(r, id, type) {
    modal('Device key', h('div', {}, h('p', {}, r.note), h('p', {}, 'Device ', h('b', { class: 'mono' }, id), ' key:'), h('code', { class: 'snippet' }, r.key),
      h('p', { style: { marginTop: '12px' } }, 'Send a reading like a real device would:'), h('code', { class: 'snippet' }, curlFor(id, r.key, type))), true);
  }

  function registerDevice() {
    const type = h('select', { id: 'dt' }, Object.entries(TYPE_NAMES).map(([k, v]) => h('option', { value: k }, v.replace(/s$/, ''))));
    const name = h('input', { type: 'text', id: 'dn', placeholder: 'e.g. Room sensor · Cafeteria corner' });
    const room = h('select', { id: 'dr' }, state.meta.rooms.map(r => h('option', { value: r.id }, r.name)));
    const bld = h('select', { id: 'db' }, state.meta.buildings.map(b => h('option', { value: b.id }, b.name)));
    const roomF = h('div', { class: 'field' }, h('label', { for: 'dr' }, 'Room'), room), bldF = h('div', { class: 'field', hidden: true }, h('label', { for: 'db' }, 'Building'), bld);
    type.addEventListener('change', () => { const r = type.value === 'ENV' || type.value === 'GATE'; roomF.hidden = !r; bldF.hidden = r; });
    const close = modal('Add an IoT device', h('form', { onsubmit: async e => {
      e.preventDefault();
      const r = await act(() => api('POST', '/api/devices', { type: type.value, name: name.value, roomId: room.value, buildingId: bld.value }), 'Device added');
      if (r) { close(); showKey(r, r.device.id, r.device.type); route(); }
    } }, h('div', { class: 'field' }, h('label', { for: 'dt' }, 'Kind of device'), type), h('div', { class: 'field' }, h('label', { for: 'dn' }, 'Name'), name), roomF, bldF,
      h('button', { class: 'btn primary', type: 'submit' }, 'Add device')));
  }

  VIEWS.device = async (ctx, id) => {
    const view = ctx.view;
    const draw = async () => {
      const d = await api('GET', '/api/devices/' + encodeURIComponent(id));
      const colors = [css('--zone-H'), css('--info'), css('--zone-L'), css('--zone-B')];
      const charts = d.type === 'GATE'
        ? [barChart(d.hourly.slice(-24).map(p => ({ label: pad(new Date(p.t).getHours()), value: p.n })), { height: 200, label: 'taps per hour' }), legend([{ name: 'Card taps per hour, last 24 h', color: css('--info') }])]
        : d.metrics.map((m, i) => [lineChart([{ name: m.label, color: colors[i % 4], points: d.fine.filter(p => p.v[m.key] != null).map(p => ({ t: p.t + 300000, v: p.v[m.key] })) }], { height: 150, label: m.label }),
          legend([{ name: m.label + (m.unit ? ' (' + m.unit + ')' : '') + ', last 24 h', color: colors[i % 4] }])]);
      fill(view, 
        here(ctx, d.name, [h('span', { class: 'mono' }, d.id), ' · ', d.roomName || d.buildingName, ' · firmware ', d.firmware, ' · ', fmt(d.messages), ' messages'],
          isAdmin() ? h('button', { class: 'btn', onclick: async () => { const r = await act(() => api('POST', '/api/devices/' + d.id + '/key')); if (r) showKey(r, d.id, d.type); } }, 'New secret key') : null,
          isAdmin() ? h('button', { class: 'btn', onclick: async () => { await act(() => api('POST', '/api/devices/' + d.id + '/reboot'), 'Reboot command sent'); } }, 'Send reboot') : null,
          isAdmin() ? h('button', { class: 'btn ' + (d.active ? 'danger' : ''), onclick: async () => { await act(() => api('POST', '/api/devices/' + d.id + '/active', { active: !d.active }), d.active ? 'Device switched off' : 'Device switched on'); draw(); } }, d.active ? 'Switch off' : 'Switch on') : null,
          h('a', { class: 'btn', href: '#/devices' }, 'All devices')),
        h('div', { class: 'readout', style: { marginBottom: '16px' } },
          h('div', {}, h('div', { class: 'v' }, h('span', { class: 'dot ' + (d.online ? 'on' : 'off') }), ' ', d.online ? 'Online' : 'Offline'), h('div', { class: 'k' }, 'Last message ' + whenOf(d.lastSeen))),
          d.metrics.filter(m => d.latest[m.key] != null).map(m => h('div', {}, h('div', { class: 'v' }, fmt(d.latest[m.key], m.key === 'temp' || m.key === 'power' ? 1 : 0), h('small', {}, m.unit)), h('div', { class: 'k' }, m.label))),
          d.type === 'ENV' ? h('div', {}, h('div', { class: 'v' }, d.battery, h('small', {}, '%')), h('div', { class: 'k' }, 'Battery')) : null),
        h('div', { class: 'panel' }, charts));
    };
    await draw();
    every(15000, draw);
  };

  // ================================================================== energy
  VIEWS.energy = async (ctx) => {
    let meter = '';
    const view = ctx.view;
    const draw = async () => {
      const [e, sim] = await Promise.all([api('GET', '/api/energy' + (meter ? '?meter=' + meter : '')), isAdmin() ? api('GET', '/api/sim') : Promise.resolve(null)]);
      const nowKw = e.meters.filter(m => !meter || m.id === meter).reduce((a, m) => a + (m.latest.power || 0), 0);
      const sel = h('select', { 'aria-label': 'Meter', onchange: ev => { meter = ev.target.value; draw(); } },
        h('option', { value: '' }, 'Whole campus (all 6 meters)'), e.meters.map(m => h('option', { value: m.id, selected: m.id === meter ? 'selected' : null }, m.buildingName)));
      fill(view, 
        here(ctx, 'Energy', 'Smart meters in every building, with an AI forecast for the next 12 hours.', sel),
        h('div', { class: 'readout', style: { marginBottom: '16px' } },
          h('div', {}, h('div', { class: 'v' }, fmt(nowKw, 1), h('small', {}, 'kW')), h('div', { class: 'k' }, 'Using now')),
          h('div', {}, h('div', { class: 'v' }, fmt(e.kwhToday), h('small', {}, 'kWh')), h('div', { class: 'k' }, 'Today so far (yesterday ' + fmt(e.kwhYesterday) + ')')),
          h('div', {}, h('div', { class: 'v' }, '₹' + fmt(e.costToday)), h('div', { class: 'k' }, 'Cost today at ₹8.5/kWh')),
          h('div', {}, h('div', { class: 'v' }, fmt(e.co2Today), h('small', {}, 'kg')), h('div', { class: 'k' }, 'CO₂ today (grid 0.716 kg/kWh)')),
          h('div', {}, h('div', { class: 'v' }, fmt(e.peakKw), h('small', {}, 'kW')), h('div', { class: 'k' }, 'Next peak, ' + hourText(new Date(e.peakAt).getHours())))),
        h('div', { class: 'grid g-wide' },
          h('div', { class: 'panel' }, h('h2', {}, 'Last 36 hours and the next 12'),
            lineChart([{ name: 'actual', color: css('--info'), points: e.past.map(p => ({ t: p.t, v: p.kw })) },
              { name: 'forecast', color: css('--zone-I'), dash: true, points: e.forecast.map(p => ({ t: p.t, v: p.kw })), band: e.forecast.map(p => ({ t: p.t, lo: p.low, hi: p.high })) }],
              { height: 260, nowLine: Date.now(), yMin: 0, label: 'power and forecast' }),
            legend([{ name: 'Measured kW', color: css('--info') }, { name: 'Forecast kW (shaded = likely range)', color: css('--zone-I'), dash: true }])),
          h('div', { class: 'grid', style: { alignContent: 'start' } },
            h('div', { class: 'panel' }, h('h2', {}, 'How the forecast works'),
              h('p', { class: 'small' }, e.method + '. Right now the campus is running at ' + Math.round(e.level * 100) + '% of its usual level for this time.'),
              h('p', { class: 'small' }, 'Test on yesterday: on average the forecast was off by ', h('b', {}, e.mape == null ? '–' : e.mape + '%'), ' (MAPE).')),
            sim ? h('div', { class: 'panel' }, h('h2', {}, 'Auto energy saver'),
              h('p', { class: 'small muted' }, 'When on, lights and AC left on in an empty room for 30 minutes are switched off automatically (and written to the activity log). When off, you get an alert instead.'),
              h('label', { class: 'switch' }, h('input', { type: 'checkbox', role: 'switch', checked: sim.autoSaver, onchange: async ev => {
                await act(() => api('POST', '/api/sim/settings', { autoSaver: ev.target.checked }), 'Auto energy saver ' + (ev.target.checked ? 'on' : 'off'));
              } }), 'Auto energy saver')) : null)),
        h('div', { class: 'panel', style: { marginTop: '16px' } }, h('h2', {}, 'Back-test: yesterday, forecast against reality'),
          lineChart([{ name: 'actual', color: css('--info'), points: e.backtest.map(p => ({ t: p.t, v: p.actual })) },
            { name: 'forecast', color: css('--zone-I'), dash: true, points: e.backtest.map(p => ({ t: p.t, v: p.forecast })) }], { height: 200, yMin: 0, width: 1100, label: 'backtest' }),
          legend([{ name: 'Measured', color: css('--info') }, { name: 'What the model would have said', color: css('--zone-I'), dash: true }])));
    };
    await draw();
    every(30000, draw);
  };

  // ================================================================== analytics
  VIEWS.analytics = async (ctx) => {
    const a = await api('GET', '/api/analytics');
    const lineColors = [css('--zone-A'), css('--zone-B'), css('--zone-L'), css('--zone-I'), css('--zone-H'), css('--zone-C'), css('--info'), css('--muted')];
    const heatColor = v => `color-mix(in srgb, var(--info) ${Math.min(100, Math.round(v * 1.2))}%, var(--surface-2))`;
    const days = a.energyDaily.length ? a.energyDaily[0].days : [];
    const zoneColor = b => css('--zone-' + zoneOf(b));
    fill(ctx.view, 
      here(ctx, 'Analytics', 'Patterns from the last weeks of attendance, sensors and alerts.'),
      h('div', { class: 'grid g2' },
        h('div', { class: 'panel' }, h('h2', {}, 'Attendance by course, week by week'),
          lineChart(a.courseWeeks.map((c, i) => ({ name: c.courseId, color: lineColors[i % 8], points: c.weeks.filter(w => w.pct != null).map(w => ({ t: Date.parse(w.week), v: w.pct })) })),
            { height: 230, yMin: 40, yMax: 100, limit: 75, limitLabel: '75% rule', xFmt: t => dateOf(t), label: 'attendance by course' }),
          legend(a.courseWeeks.map((c, i) => ({ name: c.courseId, color: lineColors[i % 8] })))),
        h('div', { class: 'panel' }, h('h2', {}, 'Attendance and internal marks'),
          scatter(a.scatter),
          h('p', { class: 'small muted' }, 'Each dot is a student, coloured by AI risk. Correlation r = ' + a.pearson + (a.pearson > 0.5 ? ': students who attend more score higher.' : '.'))),
        h('div', { class: 'panel span2' }, h('h2', {}, 'How full each room is (weekdays, last 14 days)'),
          h('div', { class: 'table-wrap' }, h('div', { class: 'heat', role: 'table', 'aria-label': 'room use by hour' },
            h('div', { class: 'hrow', role: 'row' }, h('span', {}), a.heatHours.map(hr => h('span', { class: 'hh' }, hr))),
            a.heat.map(r => h('div', { class: 'hrow', role: 'row' }, h('span', { class: 'hname', title: r.room }, r.room),
              r.cells.map((v, i) => h('span', { class: 'hcell', title: r.room + ' ' + a.heatHours[i] + ':00 – ' + v + '% full', style: { background: heatColor(v) } })))))),
          h('p', { class: 'small muted', style: { marginTop: '8px' } }, 'Darker = more seats in use, from the room sensors. Pale classrooms in the afternoon are free for extra classes or self-study.')),
        h('div', { class: 'panel' }, h('h2', {}, 'Energy per day, by building'),
          barChart(days.map((d, i) => ({ label: new Date(d.day).getDate(), parts: a.energyDaily.map(b => ({ v: b.days[i].kwh, color: zoneColor(b.buildingId) })) })), { height: 220, label: 'daily kWh' }),
          legend(a.energyDaily.map(b => ({ name: b.building, color: zoneColor(b.buildingId) })))),
        h('div', { class: 'panel' }, h('h2', {}, 'AI risk by programme'),
          barChart(a.risk.map(r => ({ label: r.program, parts: [{ v: r.LOW, color: css('--ok') }, { v: r.MEDIUM, color: css('--hazard') }, { v: r.HIGH, color: css('--danger') }] })), { height: 220, values: true, label: 'risk by programme' }),
          legend([{ name: 'Low', color: css('--ok') }, { name: 'Medium', color: css('--hazard') }, { name: 'High', color: css('--danger') }])),
        h('div', { class: 'panel span2' }, h('h2', {}, 'Alerts in the last 14 days, by kind'),
          barChart(Object.entries(a.alertRules).map(([k, v]) => ({ label: RULES[k], value: v, color: k === 'ANOMALY' ? css('--zone-L') : css('--hazard') })), { height: 180, values: true, width: 1100, label: 'alerts by rule' }))));
  };

  function scatter(points) {
    const W = 640, H = 240, L = 40, R = 10, T = 10, B = 26;
    const X = v => L + (v - 20) / 80 * (W - L - R), Y = v => T + (1 - v / 100) * (H - T - B);
    const g = s('svg', { class: 'chart', viewBox: `0 0 ${W} ${H}`, role: 'img', 'aria-label': 'attendance against internal marks' });
    for (let v = 0; v <= 100; v += 25) {
      g.appendChild(s('line', { class: 'gridline', x1: L, x2: W - R, y1: Y(v), y2: Y(v) }));
      g.appendChild(s('text', { class: 'lbl', x: L - 5, y: Y(v) + 3, 'text-anchor': 'end' }, v));
    }
    for (let v = 20; v <= 100; v += 20) g.appendChild(s('text', { class: 'lbl', x: X(v), y: H - 8, 'text-anchor': 'middle' }, v + '%'));
    g.appendChild(s('line', { x1: X(75), x2: X(75), y1: T, y2: H - B, stroke: css('--danger'), 'stroke-dasharray': '3 3' }));
    const col = { HIGH: css('--danger'), MEDIUM: css('--hazard'), LOW: css('--ok') };
    for (const p of points) g.appendChild(s('circle', { cx: X(Math.max(20, p.x)), cy: Y(p.y), r: 4.2, fill: col[p.band], opacity: 0.8, style: { cursor: 'pointer' }, onclick: () => { location.hash = '#/student/' + p.id; } },
      s('title', {}, p.name + ': attendance ' + p.x + '%, internal ' + p.y + '%')));
    g.appendChild(s('text', { class: 'lbl', x: W - R, y: H - B - 4, 'text-anchor': 'end' }, 'attendance →'));
    g.appendChild(s('text', { class: 'lbl', x: L + 4, y: T + 10 }, 'internal marks'));
    return g;
  }

  // ================================================================== cloud
  VIEWS.cloud = async (ctx) => {
    const view = ctx.view;
    const draw = async () => {
      const c = await api('GET', '/api/cloud');
      const job = async (path, msg) => { const r = await act(() => api('POST', '/api/cloud/' + path), msg); if (r) { if (path === 'verify') showVerify(r); draw(); } };
      fill(view, 
        here(ctx, 'Cloud', 'Bucket ' + c.bucket + ' · region ' + c.region,
          h('button', { class: 'btn primary', onclick: () => job('sync', 'Telemetry synced') }, 'Sync now'),
          h('button', { class: 'btn', onclick: () => job('backup', 'Backup made') }, 'Back up now'),
          h('button', { class: 'btn', onclick: () => job('verify') }, 'Check integrity')),
        h('div', { class: 'readout', style: { marginBottom: '16px' } },
          h('div', {}, h('div', { class: 'v' }, c.objects.length), h('div', { class: 'k' }, 'Objects in the bucket')),
          h('div', {}, h('div', { class: 'v' }, fmt(c.bytes / 1024), h('small', {}, 'KB')), h('div', { class: 'k' }, 'Stored')),
          h('div', {}, h('div', { class: 'v' }, fmt(c.records)), h('div', { class: 'k' }, 'Sensor records uploaded')),
          h('div', {}, h('div', { class: 'v' }, fmt(c.pending)), h('div', { class: 'k' }, 'Waiting for the next sync')),
          h('div', {}, h('div', { class: 'v' }, c.lastSync ? timeOf(c.lastSync) : '–'), h('div', { class: 'k' }, 'Last sync (every 5 min)'))),
        h('div', { class: 'grid g2' },
          h('div', { class: 'panel' }, h('h2', {}, 'Services'),
            c.services.map(x => h('div', { class: 'svc' }, h('span', { class: 'dot ' + (x.state === 'OK' ? 'on' : x.state === 'IDLE' ? '' : 'off') }),
              h('span', {}, h('div', { style: { fontWeight: 700 } }, x.name), h('div', { class: 'small muted' }, x.detail)), h('span', { class: 'chip ' + (x.state === 'OK' ? 'ok' : x.state === 'IDLE' ? 'dim' : 'warn') }, x.state)))),
          h('div', { class: 'panel' }, h('h2', {}, 'Recent jobs'),
            c.jobs.length ? h('div', { class: 'tt' }, c.jobs.map(j => h('div', { class: 'tt-row' }, h('span', { class: 'mono small' }, timeOf(j.at)),
              h('span', {}, h('div', { class: 't1' }, j.kind === 'sync' ? 'Sync: ' + j.records + ' records' : j.kind === 'backup' ? 'Backup: ' + fmt(j.bytes / 1024) + ' KB' : 'Integrity check: ' + j.ok + ' fine, ' + j.bad.length + ' damaged'),
                h('div', { class: 't2' }, 'by ' + j.by)), h('span', {}))))
              : h('div', { class: 'empty' }, 'No jobs since the server started.')),
          h('div', { class: 'panel span2' }, h('h2', {}, 'Objects'),
            h('p', { class: 'small muted' }, 'Folder on this computer: ', h('span', { class: 'mono' }, c.folder), '. Each object has a SHA-256 checksum so changes or damage can be detected.'),
            h('div', { class: 'table-wrap' }, h('table', {},
              h('thead', {}, h('tr', {}, h('th', {}, 'Key'), h('th', { class: 'num' }, 'Records'), h('th', { class: 'num' }, 'Size'), h('th', {}, 'SHA-256'), h('th', {}, 'Updated'), h('th', {}))),
              h('tbody', {}, c.objects.map(o => h('tr', {}, h('td', { class: 'mono small' }, o.key), h('td', { class: 'num' }, fmt(o.records)), h('td', { class: 'num' }, fmt(o.bytes / 1024, 1) + ' KB'),
                h('td', { class: 'mono small', title: o.sha256 }, o.sha256.slice(0, 12) + '…'), h('td', { class: 'small nowrap' }, whenOf(o.updatedAt)),
                h('td', {}, h('button', { class: 'btn small', onclick: () => viewObject(o.key) }, 'View'))))))))));
    };
    await draw();
    every(30000, draw);
  };

  async function viewObject(key) {
    const r = await act(() => api('GET', '/api/cloud/object?key=' + encodeURIComponent(key)));
    if (r) modal(key, h('pre', { class: 'obj' }, r.text.length > 40000 ? r.text.slice(0, 40000) + '\n…' : r.text), true);
  }
  function showVerify(r) {
    modal('Integrity check', h('div', {}, h('p', {}, r.ok + ' objects match their checksum.'),
      r.bad.length ? h('ul', { class: 'plain' }, r.bad.map(b => h('li', {}, h('b', { class: 'mono' }, b.key), ': ' + b.problem))) : h('p', { class: 'chip ok' }, 'Everything is intact.')));
  }

  // ================================================================== assistant
  VIEWS.assistant = async (ctx) => {
    const log = h('div', { class: 'chat-log', 'aria-live': 'polite' });
    const input = h('input', { type: 'text', placeholder: 'Ask about rooms, classes, attendance, energy, rules…', 'aria-label': 'Your question', maxlength: 300 });
    const sugg = h('div', { class: 'suggest' });
    const showSuggestions = list => fill(sugg, list.map(q => h('button', { type: 'button', onclick: () => ask(q) }, q)));
    const ask = async q => {
      if (!q.trim()) return;
      log.appendChild(h('div', { class: 'bubble me' }, q));
      input.value = '';
      const r = await act(() => api('POST', '/api/assistant', { question: q }));
      if (r) {
        log.appendChild(h('div', { class: 'bubble bot' }, r.answer,
          h('span', { class: 'based' }, 'Based on: ' + r.basedOn),
          r.sources.length ? h('span', { class: 'srcs' }, r.sources.map(src => {
            const href = src.kind === 'room' ? '#/room/' + src.id : src.kind === 'student' ? (isStaff() ? '#/student/' + src.id : '#/overview') : src.kind === 'page' ? '#/' + (src.id === 'me' ? 'overview' : src.id) : src.kind === 'notice' ? '#/notices' : null;
            return href ? h('a', { class: 'chip info', href }, src.title) : h('span', { class: 'chip dim' }, src.title + (src.score ? ' · match ' + src.score : ''));
          })) : null));
        showSuggestions(r.suggestions);
      }
      log.scrollTop = log.scrollHeight;
    };
    const start = isStaff() ? ['Any open alerts?', 'Which students are at risk?', 'How much energy are we using?', 'Temperature in the server room', 'Where is a free room?', 'Water tank level']
      : ['Where can I study now?', 'What is my attendance?', 'Am I at risk of failing?', 'My classes today', 'Library timings', 'I lost my ID card'];
    log.appendChild(h('div', { class: 'bubble bot' }, 'Hello ' + state.user.name.split(' ')[0] + '. I answer from the live campus data (sensors, attendance, the AI model, the energy forecast) and from the campus FAQ and notice board. Everything runs on this server – your questions are not sent anywhere.'));
    showSuggestions(start);
    fill(ctx.view, here(ctx, 'Ask Parisar', 'The campus assistant.'),
      h('div', { class: 'panel chat' }, log,
        h('div', {}, sugg, h('form', { class: 'chat-form', onsubmit: e => { e.preventDefault(); ask(input.value); } }, input, h('button', { class: 'btn primary', type: 'submit' }, 'Ask')))));
    input.focus();
  };

  // ================================================================== notices
  VIEWS.notices = async (ctx) => {
    const r = await api('GET', '/api/notices');
    const CATS = { ACADEMIC: 'Academic', EXAM: 'Exam', EVENT: 'Event', FACILITY: 'Facility', HOSTEL: 'Hostel' };
    fill(ctx.view, 
      here(ctx, 'Notice board', 'The campus assistant reads these notices too.', isStaff() ? h('button', { class: 'btn primary', onclick: postNotice }, 'Post a notice') : null),
      r.notices.length ? h('div', { style: { maxWidth: '820px' } }, r.notices.map(n => h('article', { class: 'notice' + (n.pinned ? ' pinned' : '') },
        h('h3', {}, n.title),
        h('div', { class: 'nmeta' }, h('span', { class: 'chip dim' }, CATS[n.category] || n.category), n.pinned ? h('span', { class: 'chip warn' }, 'Pinned') : null,
          h('span', {}, n.postedByName + ' · ' + dateOf(n.postedAt)),
          isAdmin() || n.postedBy === state.user.id ? h('button', { class: 'btn small danger', onclick: async () => {
            if (confirmBox(n.title)) { await act(() => api('POST', '/api/notices/' + n.id + '/delete'), 'Notice removed'); route(); }
          } }, 'Remove') : null),
        h('p', {}, n.body)))) : h('div', { class: 'panel empty' }, 'No notices yet.'));
  };
  const confirmBox = title => window.confirm('Remove the notice "' + title + '"?');

  function postNotice() {
    const t = h('input', { type: 'text', id: 'nt', maxlength: 120 });
    const b = h('textarea', { id: 'nb', maxlength: 2000 });
    const c = h('select', { id: 'nc' }, ['ACADEMIC', 'EXAM', 'EVENT', 'FACILITY', 'HOSTEL'].map(x => h('option', { value: x }, x.charAt(0) + x.slice(1).toLowerCase())));
    const pin = h('input', { type: 'checkbox', id: 'np' });
    const close = modal('Post a notice', h('form', { onsubmit: async e => {
      e.preventDefault();
      if (await act(() => api('POST', '/api/notices', { title: t.value, body: b.value, category: c.value, pinned: pin.checked }), 'Notice posted')) { close(); route(); }
    } }, h('div', { class: 'field' }, h('label', { for: 'nt' }, 'Title'), t), h('div', { class: 'field' }, h('label', { for: 'nb' }, 'Text'), b),
      h('div', { class: 'field' }, h('label', { for: 'nc' }, 'Category'), c),
      isAdmin() ? h('label', { class: 'row small', style: { marginBottom: '10px' } }, pin, 'Pin to the top') : null,
      h('button', { class: 'btn primary', type: 'submit' }, 'Post notice')));
  }

  // ================================================================== admin: users, simulator, activity log
  VIEWS.admin = async (ctx) => {
    let tab = state.query.get('tab') || 'sim';
    const body = h('div');
    const tabs = h('div', { class: 'tabs', role: 'tablist' }, [['sim', 'IoT simulator'], ['users', 'User accounts'], ['log', 'Activity log']].map(([k, l]) =>
      h('button', { role: 'tab', 'aria-selected': String(tab === k), onclick: e => { tab = k; tabs.querySelectorAll('button').forEach(b => b.setAttribute('aria-selected', 'false')); e.target.setAttribute('aria-selected', 'true'); draw(); } }, l)));
    const draw = async () => { fill(body, tab === 'sim' ? await simPanel(draw) : tab === 'users' ? await usersPanel(draw) : await logPanel()); };
    fill(ctx.view, here(ctx, 'Admin'), tabs, body);
    await draw();
  };

  const FAULT_TEXT = { AC_FAIL: 'AC breaks down (room gets hot)', VENT: 'Ventilation fails (CO₂ rises)', LEFT_ON: 'Lights and AC left on in an empty room', LEAK: 'Water tank leaks', SILENT: 'Device stops sending', SPIKE: 'Power spike (AI anomaly)' };
  async function simPanel(redraw) {
    const [sim, dev] = await Promise.all([api('GET', '/api/sim'), api('GET', '/api/devices')]);
    const kind = h('select', { id: 'fk' }, sim.kinds.map(k => h('option', { value: k }, FAULT_TEXT[k] || k)));
    const target = h('select', { id: 'ft' });
    const mins = h('input', { type: 'number', id: 'fm', value: 30, min: 1, max: 240 });
    const fillTargets = () => {
      const k = kind.value;
      const opts = ['AC_FAIL', 'VENT', 'LEFT_ON'].includes(k) ? state.meta.rooms.filter(r => k !== 'AC_FAIL' || r.hasAC).map(r => [r.id, r.name])
        : dev.devices.filter(d => k === 'LEAK' ? d.type === 'WATER' : k === 'SPIKE' ? d.type === 'ENERGY' : true).map(d => [d.id, d.id + ' · ' + (d.roomName || d.buildingName)]);
      fill(target, opts.map(([v, l]) => h('option', { value: v }, l)));
    };
    kind.addEventListener('change', fillTargets);
    fillTargets();
    return h('div', { class: 'grid g2' },
      h('div', { class: 'panel' }, h('h2', {}, 'Made-up sensors'),
        h('p', { class: 'small' }, 'Without real hardware, the simulator plays every device: one reading per device every 15 seconds, sent through the same code a real device uses. Real devices can send at the same time (see IoT devices → New secret key).'),
        h('label', { class: 'switch' }, h('input', { type: 'checkbox', role: 'switch', checked: sim.running, onchange: async e => { await act(() => api('POST', '/api/sim/settings', { running: e.target.checked }), 'Simulator ' + (e.target.checked ? 'running' : 'paused')); } }), 'Simulator running'),
        h('p', { class: 'small muted', style: { marginTop: '10px' } }, sim.pendingTaps + ' card taps are on their way to classroom readers.')),
      h('div', { class: 'panel' }, h('h2', {}, 'Cause a problem (for demos)'),
        h('form', { onsubmit: async e => {
          e.preventDefault();
          if (await act(() => api('POST', '/api/sim/fault', { kind: kind.value, target: target.value, minutes: Number(mins.value) }), 'Fault started – watch the alert board')) redraw();
        } }, h('div', { class: 'field' }, h('label', { for: 'fk' }, 'What goes wrong'), kind), h('div', { class: 'field' }, h('label', { for: 'ft' }, 'Where'), target),
          h('div', { class: 'field' }, h('label', { for: 'fm' }, 'For how many minutes'), mins), h('button', { class: 'btn primary', type: 'submit' }, 'Start fault')),
        h('h3', { style: { margin: '14px 0 6px' } }, 'Faults now'),
        sim.faults.length ? sim.faults.map(f => h('div', { class: 'svc fault' }, hazard('WARNING'),
          h('span', {}, h('div', { style: { fontWeight: 700 } }, FAULT_TEXT[f.kind] || f.kind), h('div', { class: 'small muted' }, f.targetName + ' · until ' + timeOf(f.until))),
          h('button', { class: 'btn small', onclick: async () => { await act(() => api('POST', '/api/sim/clear', { key: f.key }), 'Fault cleared'); redraw(); } }, 'Fix now')))
          : h('p', { class: 'muted small' }, 'None.')));
  }

  async function usersPanel(redraw) {
    const r = await api('GET', '/api/users');
    return h('div', { class: 'panel' }, h('div', { class: 'panel-head' }, h('h2', {}, 'User accounts'), h('button', { class: 'btn primary', onclick: () => addUser(r.students, redraw) }, 'Add an account')),
      h('div', { class: 'table-wrap' }, h('table', {},
        h('thead', {}, h('tr', {}, h('th', {}, 'Name'), h('th', {}, 'Username'), h('th', {}, 'Role'), h('th', {}, 'Department / student'), h('th', {}, 'State'), h('th', {}))),
        h('tbody', {}, r.users.map(u => h('tr', {},
          h('td', {}, h('b', {}, u.name)), h('td', { class: 'mono small' }, u.username), h('td', {}, u.roleLabel),
          h('td', { class: 'small' }, u.department || (u.studentId ? h('a', { href: '#/student/' + u.studentId }, u.studentId) : '–')),
          h('td', {}, u.locked ? h('span', { class: 'chip bad' }, 'locked') : u.active ? h('span', { class: 'chip ok' }, 'active') : h('span', { class: 'chip dim' }, 'switched off')),
          h('td', { class: 'nowrap' },
            u.locked ? h('button', { class: 'btn small', onclick: async () => { await act(() => api('PUT', '/api/users/' + u.id, { unlock: true }), 'Unlocked'); redraw(); } }, 'Unlock') : null, ' ',
            u.id !== state.user.id ? h('button', { class: 'btn small', onclick: async () => { await act(() => api('PUT', '/api/users/' + u.id, { active: !u.active }), u.active ? 'Switched off' : 'Switched on'); redraw(); } }, u.active ? 'Switch off' : 'Switch on') : null, ' ',
            h('button', { class: 'btn small', onclick: () => resetPassword(u) }, 'New password'))))))));
  }

  function resetPassword(u) {
    const p = h('input', { type: 'text', id: 'rp' });
    const close = modal('New password for ' + u.name, h('form', { onsubmit: async e => {
      e.preventDefault();
      if (await act(() => api('PUT', '/api/users/' + u.id, { password: p.value }), 'Password changed')) close();
    } }, h('div', { class: 'field' }, h('label', { for: 'rp' }, 'New password (8+ characters, a letter and a number)'), p), h('button', { class: 'btn primary', type: 'submit' }, 'Set password')));
  }

  function addUser(students, redraw) {
    const name = h('input', { type: 'text', id: 'un' }), username = h('input', { type: 'text', id: 'uu' }), pw = h('input', { type: 'text', id: 'up' });
    const role = h('select', { id: 'ur' }, [['FACULTY', 'Faculty'], ['STUDENT', 'Student'], ['ADMIN', 'Campus admin']].map(([v, l]) => h('option', { value: v }, l)));
    const dept = h('input', { type: 'text', id: 'ud' });
    const stu = h('select', { id: 'us' }, students.map(x => h('option', { value: x.id }, x.name + ' (' + x.roll + ')')));
    const deptF = h('div', { class: 'field' }, h('label', { for: 'ud' }, 'Department'), dept), stuF = h('div', { class: 'field', hidden: true }, h('label', { for: 'us' }, 'Which student'), stu);
    role.addEventListener('change', () => { const st = role.value === 'STUDENT'; stuF.hidden = !st; deptF.hidden = st; if (st && stu.value) name.value = stu.selectedOptions[0].textContent.replace(/ \(.*/, ''); });
    stu.addEventListener('change', () => { name.value = stu.selectedOptions[0].textContent.replace(/ \(.*/, ''); });
    const close = modal('Add an account', h('form', { onsubmit: async e => {
      e.preventDefault();
      const ok = await act(() => api('POST', '/api/users', { name: name.value, username: username.value, password: pw.value, role: role.value, department: dept.value, studentId: role.value === 'STUDENT' ? stu.value : null }), 'Account added');
      if (ok) { close(); redraw(); }
    } }, h('div', { class: 'field' }, h('label', { for: 'ur' }, 'Role'), role), stuF,
      h('div', { class: 'field' }, h('label', { for: 'un' }, 'Full name'), name), h('div', { class: 'field' }, h('label', { for: 'uu' }, 'Username'), username),
      h('div', { class: 'field' }, h('label', { for: 'up' }, 'Password'), pw), deptF, h('button', { class: 'btn primary', type: 'submit' }, 'Add account')));
  }

  async function logPanel() {
    const box = h('div');
    const load = async q => {
      const r = await api('GET', '/api/audit' + (q ? '?q=' + encodeURIComponent(q) : ''));
      fill(box, r.entries.length ? h('div', { class: 'table-wrap' }, h('table', {},
        h('thead', {}, h('tr', {}, h('th', {}, 'When'), h('th', {}, 'Who'), h('th', {}, 'What'), h('th', {}, 'Details'))),
        h('tbody', {}, r.entries.map(e => h('tr', {}, h('td', { class: 'small nowrap' }, whenOf(e.at)), h('td', {}, e.who), h('td', { class: 'mono small' }, e.action), h('td', { class: 'small' }, [e.target, e.detail].filter(Boolean).join(' – ')))))))
        : h('div', { class: 'empty' }, 'Nothing in the log yet.'));
    };
    const search = h('input', { type: 'search', placeholder: 'Search the log', 'aria-label': 'Search the log', oninput: e => { clearTimeout(search._t); search._t = setTimeout(() => load(e.target.value), 250); } });
    await load('');
    return h('div', { class: 'panel' }, h('div', { class: 'filters' }, search), box);
  }

  // ================================================================== start
  window.addEventListener('hashchange', route);
  route();
})();
