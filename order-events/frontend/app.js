/* Sanket – Event-Driven Order Processing System (frontend).
   Plain JavaScript, no libraries. Pages are drawn with the h() helper (never innerHTML with data, so no XSS).
   The mimic panel, signals and charts are hand-made SVG. The page talks to the Java backend through /api (JSON). */
(function () {
  'use strict';

  // ================================================================== small helpers
  const $app = document.getElementById('app');
  const SVGNS = 'http://www.w3.org/2000/svg';
  const state = { user: null, meta: null, timer: null, seen: new Set(), badges: { dlq: 0, stuck: 0 } };

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
  const fill = (el, ...kids) => { clear(el); add(el, kids); return el; };
  const fmt = (n, d = 0) => n == null || isNaN(n) ? '–' : Number(n).toLocaleString('en-IN', { maximumFractionDigits: d, minimumFractionDigits: d });
  const rupees = n => n == null ? '–' : '₹' + fmt(n);
  const pad = n => String(n).padStart(2, '0');
  const timeOf = t => { const d = new Date(t); return pad(d.getHours()) + ':' + pad(d.getMinutes()); };
  const secOf = t => { const d = new Date(t); return pad(d.getHours()) + ':' + pad(d.getMinutes()) + ':' + pad(d.getSeconds()); };
  const dateOf = t => new Date(t).toLocaleDateString('en-IN', { day: 'numeric', month: 'short' });
  const whenOf = t => {
    if (!t) return '–';
    const diff = Date.now() - t;
    if (diff < 60000 && diff > -60000) return Math.max(1, Math.round(diff / 1000)) + ' s ago';
    if (diff > 0 && diff < 3600000) return Math.round(diff / 60000) + ' min ago';
    const d = new Date(t), today = new Date();
    if (d.toDateString() === today.toDateString()) return 'today ' + timeOf(t);
    return dateOf(t) + ', ' + timeOf(t);
  };
  const ms = v => v == null ? '–' : v < 1000 ? fmt(v) + ' ms' : v < 60000 ? fmt(v / 1000, 1) + ' s' : v < 3600000 ? fmt(v / 60000, 1) + ' min' : fmt(v / 3600000, 1) + ' h';
  const isStaff = () => state.user && state.user.role !== 'CUSTOMER';
  const isAdmin = () => state.user && state.user.role === 'ADMIN';
  const STATUS = { PLACED: 'Placed', RESERVED: 'Stock held', PAID: 'Paid', CONFIRMED: 'Confirmed', DISPATCHED: 'Dispatched', DELIVERED: 'Delivered', CANCEL_REQUESTED: 'Cancel asked', CANCELLED: 'Cancelled', STUCK: 'Stuck' };
  const REASON = { OUT_OF_STOCK: 'Out of stock', PAYMENT_DECLINED: 'Payment declined', CUSTOMER: 'Cancelled on request' };
  const METHOD = { UPI: 'UPI', CARD: 'Card', WALLET: 'Wallet', COD: 'Cash on delivery' };
  const ASPECT = { CLEAR: 'Clear', CAUTION: 'Caution', DANGER: 'Danger' };
  const statusChip = (st, extra) => h('span', { class: 'chip st-' + st }, h('i'), STATUS[st] || st, extra ? ' · ' + extra : null);
  const topicChip = t => h('span', { class: 'topic ' + t }, t);
  const lamp = a => h('span', { class: 'lampdot ' + a, title: ASPECT[a] || a, 'aria-label': ASPECT[a] || a });
  const css = name => getComputedStyle(document.documentElement).getPropertyValue(name).trim();
  const topicColor = t => css('--t-' + t) || '#888';

  /** One line that says what an event means. */
  function describe(e) {
    const p = e.payload || {};
    switch (e.type) {
      case 'OrderPlaced': return (p.items ? p.items.reduce((a, i) => a + (i.qty || 1), 0) : '?') + ' item(s) · ' + rupees(p.total) + ' · ' + p.city + (e.version === 1 ? ' · old app v1' : ' · ' + (METHOD[p.paymentMethod] || p.paymentMethod));
      case 'OrderCancelRequested': return 'asked by ' + p.by;
      case 'OrderConfirmed': return p.courier + ' · ' + p.awb;
      case 'OrderCancelled': return (REASON[p.reason] || p.reason) + (p.detail ? ' – ' + p.detail : '');
      case 'OrderCancelDenied': return p.detail;
      case 'StockReserved': return rupees(p.total) + ' worth held · ' + (METHOD[p.paymentMethod] || p.paymentMethod);
      case 'StockRejected': return p.sku + ': wanted ' + p.wanted + ', free ' + p.available;
      case 'StockReleased': return p.units + ' unit(s) back on the shelf';
      case 'StockCommitted': return p.units + ' unit(s) taken off the shelf';
      case 'StockReplenished': return p.sku + ' +' + p.qty + ' → ' + p.stock;
      case 'PaymentAuthorized': return (METHOD[p.method] || p.method) + ' ' + rupees(p.amount) + ' · ' + p.reference;
      case 'PaymentFailed': return p.reason;
      case 'PaymentRefunded': return rupees(p.amount) + ' back · ' + p.reference;
      case 'ShipmentCreated': return p.courier + ' · ' + p.awb;
      case 'ShipmentDispatched': case 'ShipmentDelivered': case 'ShipmentCancelled': return 'AWB ' + p.awb;
      case 'DeadLettered': return p.group + ' gave up on ' + p.eventType + ' after ' + p.attempts + ' tries';
      default: return '';
    }
  }

  // ================================================================== talking to the server
  async function api(method, path, body) {
    const opt = { method, headers: {}, credentials: 'same-origin' };
    if (method !== 'GET') {
      opt.headers['Content-Type'] = 'application/json';
      opt.headers['X-Sanket'] = '1';
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
    toast._t = setTimeout(() => { t.hidden = true; }, bad ? 5500 : 3200);
  }
  async function act(fn, okMsg) {
    try { const r = await fn(); if (okMsg) toast(typeof okMsg === 'function' ? okMsg(r) : okMsg); return r || true; } catch (e) { toast(e.message, true); return null; }
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

  function confirmBox(title, text, okLabel, danger) {
    return new Promise(resolve => {
      let done = false;
      const close = modal(title, h('div', {}, h('p', {}, text), h('div', { class: 'btns' },
        h('button', { class: 'btn ' + (danger ? 'danger' : 'primary'), onclick: () => { done = true; close(); resolve(true); } }, okLabel),
        h('button', { class: 'btn', onclick: () => { close(); resolve(false); } }, 'Back'))));
      setTimeout(() => { if (!document.querySelector('.modal') && !done) resolve(false); }, 60000);
    });
  }

  // ================================================================== pictograms
  const PICT = {
    earbuds: 'M8 5a3 3 0 0 1 3 3v7a2 2 0 0 1-4 0v-4M16 5a3 3 0 0 0-3 3v7a2 2 0 0 0 4 0v-4',
    battery: 'M5 7h12v10H5zM17 10h2v4h-2M11 9l-2 3.5h4l-2 3.5',
    plug: 'M9 3v5M15 3v5M7 8h10v3a5 5 0 0 1-10 0zM12 16v5',
    phone: 'M8 3h8v18H8zM11 18h2',
    laptop: 'M5 6h14v9H5zM3 18h18',
    bulb: 'M9 17h6M10 20h4M12 3a6 6 0 0 0-3.5 10.9V17h7v-3.1A6 6 0 0 0 12 3z',
    fan: 'M12 10a2 2 0 1 0 0 4 2 2 0 0 0 0-4zM12 10c0-4 1-6 3-6s1 4-3 6M14 12c4 0 6 1 6 3s-4 1-6-3M12 14c0 4-1 6-3 6s-1-4 3-6M10 12c-4 0-6-1-6-3s4-1 6 3',
    bottle: 'M10 2h4v3l2 3v13H8V8l2-3z',
    bag: 'M5 8h14l-1 13H6zM9 8V6a3 3 0 0 1 6 0v2',
    leaf: 'M5 19C5 9 11 4 20 4c0 9-5 15-15 15zM5 19l8-8',
    lamp: 'M8 3h8l3 8H5zM12 11v8M8 21h8',
    horse: 'M6 21l1-6 3-3 1-5 3-3 2 3 3 1-1 2-3-1-1 4 2 2v6M10 21v-5',
    mug: 'M5 6h11v11a3 3 0 0 1-3 3H8a3 3 0 0 1-3-3zM16 9h2a2 2 0 0 1 0 5h-2',
    stole: 'M6 3h5v18H6zM13 3h5v14h-5zM6 8h5M13 8h5',
    kurta: 'M9 3h6l4 4-2 3-2-1v12H9V9l-2 1-2-3z',
    backpack: 'M7 8a5 5 0 0 1 10 0v12H7zM10 4V3h4v1M9 13h6v4H9z',
    book: 'M5 4h11a2 2 0 0 1 2 2v14H7a2 2 0 0 1-2-2zM5 18a2 2 0 0 1 2-2h11',
    jar: 'M8 4h8v3H8zM7 7h10v13H7zM10 12h4',
    box: 'M4 7l8-4 8 4v10l-8 4-8-4zM4 7l8 4 8-4M12 11v10'
  };
  const pict = name => s('svg', { viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor', 'stroke-width': 1.8, 'stroke-linecap': 'round', 'stroke-linejoin': 'round', 'aria-hidden': 'true' }, s('path', { d: PICT[name] || PICT.box }));

  /** A three-lamp signal post (vertical). aspect: CLEAR / CAUTION / DANGER */
  const signalPost = (aspect, pole = true) => h('div', { class: 'post', role: 'img', 'aria-label': 'Signal: ' + (ASPECT[aspect] || aspect) },
    h('div', { class: 'head' },
      h('span', { class: 'l r' + (aspect === 'DANGER' ? ' on' : '') }),
      h('span', { class: 'l y' + (aspect === 'CAUTION' ? ' on' : '') }),
      h('span', { class: 'l g' + (aspect === 'CLEAR' ? ' on' : '') })),
    pole ? h('div', { class: 'pole' }) : null);

  // ================================================================== charts (hand-made SVG)
  /** Line chart. series: [{name, color, points:[{t,v}]}]; opts: {height, xFmt, label, yMin} */
  function lineChart(series, opts = {}) {
    const W = opts.width || 640, H = opts.height || 220, L = 40, R = 10, T = 10, B = 24;
    const all = series.flatMap(x => x.points);
    if (!all.length) return h('div', { class: 'empty' }, 'No data yet.');
    let t0 = Math.min(...all.map(p => p.t)), t1 = Math.max(...all.map(p => p.t));
    if (t1 === t0) t1 = t0 + 1;
    const y0 = opts.yMin != null ? opts.yMin : 0;
    let y1 = Math.max(...all.map(p => p.v));
    if (y1 <= y0) y1 = y0 + 1;
    y1 += (y1 - y0) * 0.08;
    const X = t => L + (t - t0) / (t1 - t0) * (W - L - R), Y = v => T + (1 - (v - y0) / (y1 - y0)) * (H - T - B);
    const g = s('svg', { class: 'chart', viewBox: `0 0 ${W} ${H}`, role: 'img', 'aria-label': opts.label || 'chart' });
    for (let i = 0; i <= 4; i++) {
      const v = y0 + (y1 - y0) * i / 4;
      g.appendChild(s('line', { class: 'gridline', x1: L, x2: W - R, y1: Y(v), y2: Y(v) }));
      g.appendChild(s('text', { class: 'lbl', x: L - 5, y: Y(v) + 3, 'text-anchor': 'end' }, fmt(v, y1 < 10 ? 1 : 0)));
    }
    const xf = opts.xFmt || timeOf;
    for (let i = 0; i <= 5; i++) {
      const t = t0 + (t1 - t0) * i / 5;
      g.appendChild(s('text', { class: 'lbl', x: X(t), y: H - 6, 'text-anchor': i === 0 ? 'start' : i === 5 ? 'end' : 'middle' }, xf(t)));
    }
    for (const se of series) {
      if (!se.points.length) continue;
      const d = se.points.map((p, i) => (i ? 'L' : 'M') + X(p.t).toFixed(1) + ' ' + Y(p.v).toFixed(1)).join(' ');
      g.appendChild(s('path', { d, fill: 'none', stroke: se.color, 'stroke-width': se.width || 2, 'stroke-linejoin': 'round' }));
    }
    return g;
  }
  function legend(items) {
    return h('div', { class: 'legend' }, items.map(i => h('span', {}, h('i', { style: { background: i.color } }), h('span', { style: { color: 'var(--muted)' } }, i.name))));
  }
  /** Vertical bars. items: [{label, parts:[{v,color,name}]}] */
  function barChart(items, opts = {}) {
    const W = opts.width || 640, H = opts.height || 200, L = 44, R = 6, T = 12, B = 30;
    const max = Math.max(1, ...items.map(i => i.parts.reduce((a, p) => a + p.v, 0))) * 1.1;
    const g = s('svg', { class: 'chart', viewBox: `0 0 ${W} ${H}`, role: 'img', 'aria-label': opts.label || 'bar chart' });
    const Y = v => T + (1 - v / max) * (H - T - B);
    for (let i = 0; i <= 4; i++) {
      const v = max * i / 4;
      g.appendChild(s('line', { class: 'gridline', x1: L, x2: W - R, y1: Y(v), y2: Y(v) }));
      g.appendChild(s('text', { class: 'lbl', x: L - 5, y: Y(v) + 3, 'text-anchor': 'end' }, opts.yFmt ? opts.yFmt(v) : fmt(v)));
    }
    const bw = (W - L - R) / Math.max(1, items.length);
    const every = Math.max(1, Math.ceil(items.length / (opts.maxLabels || 14)));
    items.forEach((it, i) => {
      const x = L + i * bw + bw * 0.14, w = Math.max(1, bw * 0.72);
      let base = 0;
      for (const p of it.parts) {
        if (p.v > 0) g.appendChild(s('rect', { x, y: Y(base + p.v), width: w, height: Y(base) - Y(base + p.v), fill: p.color },
          s('title', {}, it.label + ' – ' + (p.name ? p.name + ': ' : '') + (opts.yFmt ? opts.yFmt(p.v) : fmt(p.v)))));
        base += p.v;
      }
      if (opts.values) g.appendChild(s('text', { class: 'lbl', x: x + w / 2, y: Y(base) - 4, 'text-anchor': 'middle' }, opts.yFmt ? opts.yFmt(base) : fmt(base)));
      if (i % every === 0) g.appendChild(s('text', { class: 'lbl', x: x + w / 2, y: H - 12, 'text-anchor': 'middle' }, it.label));
    });
    return g;
  }
  /** Horizontal bars for small breakdowns. */
  function hbars(rows, color) {
    const max = Math.max(1, ...rows.map(r => r.v));
    return h('div', {}, rows.map(r => h('div', { class: 'hbar' }, h('span', {}, r.label),
      h('div', { class: 'bar' }, h('span', { style: { width: (100 * r.v / max) + '%', background: r.color || color || 'var(--ink)' } })),
      h('span', { class: 'right mono' }, r.text || fmt(r.v)))));
  }

  // ================================================================== sign in: the booking office
  function showLogin(msg) {
    stopTimer();
    const err = h('div', { class: 'err', role: 'alert' }, msg || '');
    const user = h('input', { type: 'text', id: 'lu', autocomplete: 'username', required: true });
    const pass = h('input', { type: 'password', id: 'lp', autocomplete: 'current-password', required: true });
    const put = (u, p) => { user.value = u; pass.value = p; };
    const form = h('form', { onsubmit: async e => {
      e.preventDefault();
      err.textContent = '';
      try {
        const r = await api('POST', '/api/login', { username: user.value, password: pass.value });
        state.user = r.user; state.meta = r.meta;
        location.hash = isStaff() ? '#/overview' : '#/shop';
        route();
      } catch (ex) { err.textContent = ex.message; }
    } },
      h('h2', {}, 'Sign in'),
      h('div', { class: 'field' }, h('label', { for: 'lu' }, 'Username'), user),
      h('div', { class: 'field' }, h('label', { for: 'lp' }, 'Password'), pass),
      err,
      h('button', { class: 'btn primary', type: 'submit', style: { width: '100%', justifyContent: 'center', padding: '9px' } }, 'Sign in'),
      h('div', { class: 'demo-logins' }, 'Demo: ',
        [['admin', 'admin123', 'platform admin'], ['priya', 'staff123', 'operations'], ['neha', 'customer123', 'customer']]
          .map(([u, p, l], i) => [i ? ' · ' : '', h('button', { type: 'button', onclick: () => put(u, p) }, u), ' (' + l + ')'])));
    clear($app).appendChild(h('main', { class: 'login', id: 'view' },
      h('div', { class: 'login-card' },
        h('div', { class: 'login-side' },
          h('div', { class: 'login-signal' }, signalPost('CLEAR'),
            h('div', { class: 'board' }, h('h1', {}, 'Sanket'), h('div', { class: 'hi' }, 'संकेत'))),
          h('div', {},
            h('p', {}, 'Event-Driven Order Processing System'),
            h('p', { class: 'small' }, 'Jharkhand Haat – a made-up online shop. Every order travels as events between independent services.')),
          h('ul', {},
            h('li', {}, lamp('CLEAR'), 'Event broker with topics, partitions and offsets'),
            h('li', {}, lamp('CLEAR'), 'Saga with compensation, transactional outbox'),
            h('li', {}, lamp('CAUTION'), 'Retries, dead letters, replay and fault drills'),
            h('li', {}, lamp('CLEAR'), 'Live tracing of every order, end to end'))),
        h('div', { class: 'login-body' }, form))));
    user.focus();
  }

  // ================================================================== the lever frame (menu)
  const PAGES = [
    { id: 'overview', label: 'Signal box', hi: 'संकेत कक्ष', lever: 'red', who: 'staff' },
    { id: 'orders', label: 'Orders', hi: 'ऑर्डर', lever: 'black', who: 'staff', badge: 'stuck' },
    { id: 'events', label: 'Event register', hi: 'घटना रजिस्टर', lever: 'black', who: 'staff' },
    { id: 'consumers', label: 'Consumer cabins', hi: 'उपभोक्ता केबिन', lever: 'red', who: 'staff' },
    { id: 'dead-letters', label: 'Lost property', hi: 'खोया-पाया', lever: 'red', who: 'staff', badge: 'dlq' },
    { id: 'outbox', label: 'Parcel office', hi: 'पार्सल घर', lever: 'black', who: 'staff' },
    { id: 'inventory', label: 'Goods shed', hi: 'माल गोदाम', lever: 'black', who: 'staff' },
    { id: 'analytics', label: 'Traffic charts', hi: 'यातायात चार्ट', lever: 'yellow', who: 'staff' },
    { id: 'schemas', label: 'Rule book', hi: 'नियम पुस्तिका', lever: 'white', who: 'staff' },
    { id: 'shop', label: 'Booking counter', hi: 'बुकिंग काउंटर', lever: 'black', who: 'all' },
    { id: 'my-orders', label: 'My orders', hi: 'मेरे ऑर्डर', lever: 'black', who: 'customer' },
    { id: 'messages', label: 'Messages', hi: 'संदेश', lever: 'yellow', who: 'customer' },
    { id: 'faults', label: 'Fault drill', hi: 'आपात अभ्यास', lever: 'blue', who: 'admin' },
    { id: 'users', label: 'Staff & customers', hi: 'कर्मचारी व ग्राहक', lever: 'blue', who: 'admin' },
    { id: 'audit', label: 'Activity log', hi: 'गतिविधि', lever: 'blue', who: 'admin' }
  ];
  const allowed = p => p.who === 'all' || (p.who === 'staff' && isStaff()) || (p.who === 'admin' && isAdmin()) || (p.who === 'customer' && !isStaff());

  function shell(pageId) {
    let n = 0;
    const frame = h('nav', { class: 'frame', 'aria-label': 'Main menu' },
      h('div', { class: 'brand' },
        h('div', { class: 'brand-plate' }, 'SANKET'),
        h('div', { class: 'brand-sub' }, h('span', { class: 'deva' }, 'संकेत'), h('br'), 'Event-driven orders')),
      h('ul', { class: 'levers' }, PAGES.filter(allowed).map(p => {
        n++;
        const b = p.badge ? state.badges[p.badge] : 0;
        return h('li', {}, h('a', { href: '#/' + p.id, 'aria-current': p.id === pageId ? 'page' : null, onclick: () => frame.classList.remove('open') },
          h('span', { class: 'lever ' + p.lever, 'aria-hidden': 'true' }, h('span', { class: 'rod' }), h('span', { class: 'grip' })),
          h('span', { class: 'plate', 'aria-hidden': 'true' }, n),
          h('span', {}, p.label),
          h('span', { class: b ? 'count' + (p.badge === 'stuck' ? ' warn' : '') : '', 'data-badge': p.badge || '' }, b || '')));
      })),
      h('div', { class: 'frame-foot' },
        h('div', { class: 'who' }, h('div', { class: 'avatar', 'aria-hidden': 'true' }, state.user.initials),
          h('div', {}, h('div', { class: 'who-name' }, state.user.name), h('div', { class: 'who-role' }, state.user.roleLabel))),
        h('div', { class: 'frame-btns' },
          h('button', { onclick: toggleTheme, title: 'Switch light / dark' }, isDark() ? 'Day shift' : 'Night shift'),
          h('button', { onclick: changePassword }, 'Password'),
          h('button', { onclick: async () => { await act(() => api('POST', '/api/logout')); state.user = null; location.hash = ''; showLogin(); } }, 'Sign out'))));
    const view = h('main', { class: 'main', id: 'view', tabindex: '-1' }, h('div', { class: 'loading' }, 'Loading…'));
    const menuBtn = h('button', { class: 'btn menu-toggle', onclick: () => frame.classList.toggle('open'), 'aria-label': 'Open menu' }, 'Menu');
    clear($app).appendChild(h('div', { class: 'shell' }, frame, view));
    return { view, menuBtn };
  }

  function isDark() {
    const t = document.documentElement.getAttribute('data-theme');
    return t ? t === 'dark' : matchMedia('(prefers-color-scheme: dark)').matches;
  }
  function toggleTheme() {
    const next = isDark() ? 'light' : 'dark';
    document.documentElement.setAttribute('data-theme', next);
    try { localStorage.setItem('sanket-theme', next); } catch (e) { /* ignore */ }
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

  /** The station name board at the top of each page. */
  function station(ctx, pageId, sub, ...actions) {
    const p = PAGES.find(x => x.id === pageId) || { label: pageId, hi: '' };
    return h('header', { class: 'station' },
      h('div', { class: 'station-left' }, ctx.menuBtn,
        h('div', { class: 'board' }, h('h1', {}, p.label), p.hi ? h('div', { class: 'hi', lang: 'hi' }, p.hi) : null),
        sub ? h('div', { class: 'sub' }, sub) : null),
      actions.length ? h('div', { class: 'station-actions' }, actions) : null);
  }

  // ================================================================== router
  function stopTimer() { if (state.timer) { clearInterval(state.timer); state.timer = null; } }
  function every(msec, fn) { stopTimer(); state.timer = setInterval(() => { if (!document.hidden) fn(); }, msec); }

  async function route() {
    stopTimer();
    if (!state.user) {
      try {
        const me = await api('GET', '/api/me');
        if (!me.user) return showLogin();
        state.user = me.user; state.meta = me.meta;
      } catch (e) { return showLogin(); }
    }
    const home = isStaff() ? 'overview' : 'shop';
    const [path, qs] = (location.hash || '#/' + home).slice(2).split('?');
    state.query = new URLSearchParams(qs || '');
    const parts = path.split('/');
    let page = parts[0] || home;
    const id = parts[1] ? decodeURIComponent(parts[1]) : null;
    if (page === 'order') page = 'orders';
    if (page === 'orders' && id && !isStaff()) page = 'my-orders';
    let pageDef = PAGES.find(p => p.id === page);
    if (!pageDef || !allowed(pageDef)) { page = home; pageDef = PAGES.find(p => p.id === page); }
    const navId = !isStaff() && page === 'orders' ? 'my-orders' : page;
    const ctx = shell(navId);
    if (isStaff()) refreshBadges();
    let render = VIEWS[page];
    if (id && (page === 'orders' || page === 'my-orders')) render = VIEWS.order;
    try {
      await render(ctx, id);
    } catch (e) {
      fill(ctx.view, station(ctx, page), h('div', { class: 'panel' }, h('p', {}, e.message), h('a', { class: 'btn', href: '#/' + home }, 'Back')));
    }
  }

  async function refreshBadges(ov) {
    try {
      const k = ov ? ov.kpi : (await api('GET', '/api/overview')).kpi;
      state.badges = { dlq: k.openDeadLetters, stuck: k.stuck };
      document.querySelectorAll('[data-badge]').forEach(el => {
        const key = el.getAttribute('data-badge');
        if (!key) return;
        const n = state.badges[key];
        el.textContent = n || '';
        el.className = n ? 'count' + (key === 'stuck' ? ' warn' : '') : '';
      });
    } catch (e) { /* ignore */ }
  }

  const VIEWS = {};

  // ================================================================== the signal box (overview)
  /** The mimic panel: topics are tracks, recent events are trains, consumer groups are signal cabins. */
  function mimic(ov) {
    const W = 980, H = 300, x0 = 170, x1 = 640, cx = 770;
    const topics = ov.topics, groups = ov.groups;
    const ty = i => 46 + i * 50, gy = j => 24 + j * 44;
    const svg = s('svg', { class: 'mimic', viewBox: `0 0 ${W} ${H}`, role: 'img', 'aria-label': 'Mimic panel: topics as tracks, consumer groups as signal cabins' });
    svg.appendChild(s('text', { x: 14, y: 18, 'font-size': 11, 'letter-spacing': '.18em', opacity: .7 }, 'TOPICS'));
    svg.appendChild(s('text', { x: x0 + 10, y: 18, 'font-size': 11, 'letter-spacing': '.12em', opacity: .7 }, '◀ NEW'));
    svg.appendChild(s('text', { x: (x0 + x1) / 2, y: 18, 'font-size': 11, 'letter-spacing': '.12em', opacity: .7, 'text-anchor': 'middle' }, 'TRAINS = EVENTS OF THE LAST MINUTE'));
    svg.appendChild(s('text', { x: x1, y: 18, 'font-size': 11, 'letter-spacing': '.12em', opacity: .7, 'text-anchor': 'end' }, '1 MIN ▶'));
    svg.appendChild(s('text', { x: cx, y: 14, 'font-size': 11, 'letter-spacing': '.18em', opacity: .7 }, 'CONSUMER CABINS · LAG'));
    // feeds (drawn first, under everything)
    groups.forEach((g, j) => g.topics.forEach(t => {
      const i = topics.findIndex(x => x.name === t);
      if (i < 0) return;
      const y1 = ty(i), y2 = gy(j) + 17;
      svg.appendChild(s('path', { class: 'feed', d: `M${x1} ${y1} C ${x1 + 70} ${y1}, ${cx - 70} ${y2}, ${cx} ${y2}`, stroke: topicColor(t) }));
    }));
    topics.forEach((t, i) => {
      const y = ty(i);
      for (let x = x0; x <= x1; x += 14) svg.appendChild(s('line', { class: 'trk-thin', x1: x, x2: x, y1: y - 5, y2: y + 5 }));
      svg.appendChild(s('line', { class: 'trk', x1: x0, x2: x1, y1: y, y2: y, stroke: topicColor(t.name), style: { stroke: topicColor(t.name) } }));
      svg.appendChild(s('rect', { x: 10, y: y - 15, width: 150, height: 30, rx: 3, fill: topicColor(t.name) }));
      svg.appendChild(s('text', { class: 'tlabel', x: 18, y: y - 1, 'font-size': 15, 'font-weight': 800 }, t.name.toUpperCase()));
      svg.appendChild(s('text', { class: 'tlabel', x: 18, y: y + 11, 'font-size': 10.5, opacity: .9 }, t.partitions + ' part · ' + fmt(t.events) + ' ev · ' + t.lastMinute + '/min'));
    });
    // trains: events of the last 60 seconds, moving right as they get older
    const now = ov.now;
    for (const e of ov.trains) {
      const age = now - e.at;
      if (age > 60000 || age < 0) continue;
      const i = topics.findIndex(x => x.name === e.topic);
      if (i < 0) continue;
      const x = x0 + 8 + (age / 60000) * (x1 - x0 - 30);
      svg.appendChild(s('rect', { class: 'train', x, y: ty(i) - 4 + (e.partition - 1) * 3, width: 16, height: 7, rx: 2, fill: topicColor(e.topic) },
        s('title', {}, e.type + ' ' + e.key + ' (' + Math.round(age / 1000) + ' s ago)')));
    }
    groups.forEach((g, j) => {
      const y = gy(j);
      const a = g.aspect;
      const gEl = s('g', { style: { cursor: 'pointer' }, onclick: () => { location.hash = '#/consumers'; } });
      gEl.appendChild(s('rect', { class: 'cabin', x: cx, y, width: 200, height: 34, rx: 4 }));
      [['r', 'DANGER'], ['y', 'CAUTION'], ['g', 'CLEAR']].forEach(([c, asp], k) =>
        gEl.appendChild(s('circle', { cx: cx + 13 + k * 13, cy: y + 17, r: 5, class: a === asp ? 'lamp ' + c + (asp === 'DANGER' ? ' blink' : '') : 'lamp-off' })));
      gEl.appendChild(s('text', { x: cx + 52, y: y + 15, 'font-size': 14, 'font-weight': 700 }, g.name));
      gEl.appendChild(s('text', { x: cx + 52, y: y + 28, 'font-size': 10, opacity: .7 }, g.paused ? 'STOPPED' : g.retrying ? 'RETRYING' : g.slow ? 'RUNNING SLOW' : 'running'));
      gEl.appendChild(s('text', { class: 'seg', x: cx + 192, y: y + 23, 'font-size': 15, 'text-anchor': 'end' }, String(g.lag).padStart(3, ' ')));
      gEl.appendChild(s('title', {}, g.name + ' (' + g.service + '): ' + (ASPECT[a] || a) + ', lag ' + g.lag + (g.lastError ? '\nLast error: ' + g.lastError : '')));
      svg.appendChild(gEl);
    });
    if (ov.brokerDown) {
      svg.appendChild(s('rect', { x: x0 + 90, y: 120, width: 300, height: 46, rx: 4, fill: '#000', opacity: .85 }));
      svg.appendChild(s('text', { x: x0 + 240, y: 150, 'text-anchor': 'middle', 'font-size': 22, 'font-weight': 800, class: 'blink', style: { fill: 'var(--lamp-red)' } }, 'BROKER DOWN'));
    }
    return h('div', { class: 'mimic-wrap' }, svg);
  }

  /** The split-flap departure board of the newest events. */
  function departures(events, title) {
    const rows = events.map(e => {
      const isNew = !state.seen.has(e.id);
      state.seen.add(e.id);
      return h('div', { class: 'fb-row' + (isNew ? ' new' : ''), role: 'row', tabindex: 0, title: 'Open the event', onclick: () => showEvent(e.id),
        onkeydown: ev => { if (ev.key === 'Enter') showEvent(e.id); } },
        h('span', { class: 'w' }, secOf(e.at)), h('span', {}, e.type), h('span', { class: 'w' }, e.key || '–'),
        h('span', {}, e.topic + ' ' + e.partition + '@' + e.offset), h('span', { class: 'w' }, describe(e)));
    });
    if (state.seen.size > 3000) state.seen = new Set([...state.seen].slice(-1000));
    return h('div', { class: 'flapboard', role: 'table', 'aria-label': 'Latest events' },
      h('div', { class: 'fb-title' }, h('span', {}, title || 'Departures – latest events'), h('span', { class: 'clock' }, secOf(Date.now()))),
      h('div', { class: 'fb-row head', role: 'row' }, ['Time', 'Event', 'Order', 'Topic P@offset', 'Details'].map(x => h('span', {}, x))),
      rows.length ? rows : h('div', { class: 'empty' }, 'No events yet.'));
  }

  function faultBanner(faults) {
    if (!faults || !faults.length) return null;
    return h('div', {}, faults.map(f => h('div', { class: 'active-fault', role: 'alert' },
      h('span', {}, '⚠ Fault drill: ' + f.label + (f.secondsLeft != null ? ' · ' + Math.floor(f.secondsLeft / 60) + ':' + pad(f.secondsLeft % 60) + ' left' : '') + (f.extra ? ' · ' + f.extra : '')),
      isAdmin() ? h('button', { class: 'btn small', onclick: async () => { if (await act(() => api('POST', '/api/sim/clear', { key: f.key }), 'Fault cleared')) route(); } }, 'Clear') : null)));
  }

  function kpiPlates(k) {
    const plate = (key, v, sub, cls, href) => h(href ? 'a' : 'div', { class: 'enamel ' + (cls || ''), href, style: href ? { textDecoration: 'none', color: 'inherit' } : null },
      h('div', { class: 'k' }, key), h('div', { class: 'v' }, v), sub ? h('div', { class: 's' }, sub) : null);
    return h('div', { class: 'plates' },
      plate('Orders today', fmt(k.ordersToday), k.cancelledToday + ' cancelled', '', '#/orders'),
      plate('Revenue today', rupees(k.revenueToday), 'confirmed orders'),
      plate('Confirmed (24 h)', k.confirmedPct == null ? '–' : k.confirmedPct + '%', 'of orders placed', k.confirmedPct != null && k.confirmedPct < 85 ? 'warn' : 'good'),
      plate('Placed → confirmed', ms(k.p95), 'p95 · median ' + ms(k.p50)),
      plate('Events / min', fmt(k.eventsPerMin, 1), fmt(k.events) + ' in the log', '', '#/events'),
      plate('Consumer lag', fmt(k.totalLag), 'events not read yet', k.totalLag > 50 ? 'bad' : k.totalLag > 10 ? 'warn' : 'good', '#/consumers'),
      plate('Lost property', fmt(k.openDeadLetters), 'open dead letters', k.openDeadLetters ? 'bad' : 'good', '#/dead-letters'),
      plate('Outbox waiting', fmt(k.outboxPending), 'not yet published', k.outboxPending > 5 ? 'warn' : '', '#/outbox'),
      plate('Stuck orders', fmt(k.stuck), 'no move for 2 min', k.stuck ? 'warn' : 'good', '#/orders?status=STUCK'));
  }

  VIEWS.overview = async ctx => {
    const body = h('div', {}, h('div', { class: 'loading' }, 'Loading…'));
    fill(ctx.view, station(ctx, 'overview', 'Every service, every topic and every event – live. Updates every 2 seconds.'), body);
    const draw = async () => {
      const ov = await api('GET', '/api/overview');
      refreshBadges(ov);
      fill(body,
        faultBanner(ov.faults),
        kpiPlates(ov.kpi),
        mimic(ov),
        departures(ov.latest.slice(0, 12)),
        ov.stuck.length ? h('div', { class: 'panel' }, h('h2', {}, 'Stuck orders'),
          h('p', { class: 'muted small' }, 'Open orders that have not moved for 2 minutes before being confirmed. Usually a consumer is stopped or an event is in Lost property.'),
          orderTable(ov.stuck)) : null);
    };
    await draw();
    every(2000, () => draw().catch(() => { }));
  };

  // ================================================================== orders
  function orderTable(rows, mine) {
    if (!rows.length) return h('div', { class: 'empty' }, 'No orders here.');
    return h('div', { class: 'table-wrap' }, h('table', {},
      h('thead', {}, h('tr', {}, ['Order', mine ? null : 'Customer', 'City', 'Items', 'Total', 'Pay', 'Status', 'Last event', 'Placed'].filter(Boolean).map(x => h('th', {}, x)))),
      h('tbody', {}, rows.map(o => h('tr', { class: 'click', tabindex: 0, onclick: () => { location.hash = '#/orders/' + o.orderId; },
        onkeydown: e => { if (e.key === 'Enter') location.hash = '#/orders/' + o.orderId; } },
        h('td', { class: 'mono' }, o.orderId), mine ? null : h('td', {}, o.customer), h('td', {}, o.city || '–'),
        h('td', { class: 'right' }, o.units), h('td', { class: 'right nowrap' }, rupees(o.total)), h('td', { class: 'small' }, METHOD[o.paymentMethod] || o.paymentMethod || '–'),
        h('td', {}, statusChip(o.status, o.status === 'CANCELLED' && o.reason ? REASON[o.reason] : null), o.stuck ? [' ', statusChip('STUCK')] : null),
        h('td', { class: 'small mono' }, o.lastEvent || '–'), h('td', { class: 'small nowrap' }, whenOf(o.placedAt)))))));
  }

  VIEWS.orders = async ctx => {
    let status = state.query.get('status') || '', q = '';
    const list = h('div', {}, h('div', { class: 'loading' }, 'Loading…'));
    const tabs = h('div', { class: 'tabs', role: 'group', 'aria-label': 'Filter by status' });
    const search = h('input', { type: 'search', placeholder: 'Order id, customer, city or AWB…', 'aria-label': 'Search orders', style: { maxWidth: '320px' },
      oninput: () => { q = search.value; clearTimeout(search._t); search._t = setTimeout(draw, 250); } });
    fill(ctx.view, station(ctx, 'orders', 'The read model: built only from events by the order-view consumer.', search,
      h('a', { class: 'btn primary', href: '#/shop' }, 'Place a test order')), h('div', { class: 'panel' }, tabs, list));
    async function draw() {
      const r = await api('GET', '/api/orders?status=' + encodeURIComponent(status) + '&q=' + encodeURIComponent(q));
      const total = Object.entries(r.counts).filter(([k]) => k !== 'STUCK').reduce((a, [, v]) => a + v, 0);
      fill(tabs, [['', 'All', total]].concat(Object.entries(r.counts).map(([k, v]) => [k, STATUS[k] || k, v])).map(([k, l, n]) =>
        h('button', { 'aria-pressed': String(status === k), onclick: () => { status = k; draw(); } }, l, h('span', { class: 'n' }, n))));
      fill(list, orderTable(r.orders), r.orders.length >= 150 ? h('p', { class: 'muted small' }, 'Showing the newest 150. Use the filters or search to find older ones.') : null);
    }
    await draw();
    every(4000, () => draw().catch(() => { }));
  };

  const STAGES = [['placed', 'Placed', 'OrderPlaced – the order service saved it (outbox)'], ['reserved', 'Stock held', 'StockReserved – inventory service'],
    ['paid', 'Paid', 'PaymentAuthorized – payment service'], ['confirmed', 'Confirmed', 'OrderConfirmed – order saga (after ShipmentCreated)'],
    ['dispatched', 'Dispatched', 'ShipmentDispatched – warehouse'], ['delivered', 'Delivered', 'ShipmentDelivered – courier']];

  function routeStrip(d) {
    const st = d.stages || {};
    const placed = st.placed;
    const items = [];
    let prev = placed, lastDone = -1;
    STAGES.forEach(([k], i) => { if (st[k]) lastDone = i; });
    STAGES.forEach(([k, name, why], i) => {
      const t = st[k];
      if (st.cancelled && !t && i > lastDone) return;
      items.push(h('li', { class: t ? 'done' + (i === lastDone && !st.cancelled && i < 5 ? ' now' : '') : '' },
        h('span', { class: 'stop' }), h('div', { class: 'name' }, name),
        h('div', { class: 'when' }, t ? [secOf(t) + ' · ' + dateOf(t), prev && t !== placed ? h('span', { class: 'delta' }, '+' + ms(t - prev)) : null] : why)));
      if (t) prev = t;
    });
    if (st.cancelRequested) items.push(h('li', { class: 'done' }, h('span', { class: 'stop' }), h('div', { class: 'name' }, 'Cancel asked'), h('div', { class: 'when' }, secOf(st.cancelRequested) + (st.cancelDenied ? ' · denied, the parcel had left' : ''))));
    if (st.cancelled) items.push(h('li', { class: 'bad' }, h('span', { class: 'stop' }), h('div', { class: 'name' }, 'Cancelled'),
      h('div', { class: 'when' }, secOf(st.cancelled) + ' · ' + (REASON[d.view && d.view.reason] || '') + (st.refunded ? ' · money refunded' : ''))));
    return h('ol', { class: 'route' }, items);
  }

  function ticket(o, small) {
    return h('div', { class: 'ticket' + (small ? ' small' : '') },
      h('div', { class: 't-co' }, 'Jharkhand Haat · Order ticket'),
      h('div', { class: 't-no' }, o.orderId),
      h('div', { class: 't-route' }, h('span', {}, 'Ranchi'), h('span', { class: 'arr' }, '▶▶'), h('span', {}, o.city || '–')),
      h('div', { class: 't-grid' },
        h('div', {}, h('b', {}, 'Fare'), h('span', { class: 't-total' }, rupees(o.total))),
        h('div', {}, h('b', {}, 'Items'), o.units),
        h('div', {}, h('b', {}, 'Paid by'), METHOD[o.paymentMethod] || o.paymentMethod || '–'),
        h('div', {}, h('b', {}, 'Booked'), o.placedAt ? dateOf(o.placedAt) + ' ' + timeOf(o.placedAt) : '–'),
        o.awb ? h('div', { style: { gridColumn: '1 / -1' } }, h('b', {}, 'Courier'), o.courier + ' · ' + o.awb) : null),
      h('div', { style: { marginTop: '8px' } }, statusChip(o.status, o.status === 'CANCELLED' && o.reason ? REASON[o.reason] : null)));
  }

  function traceTable(trace) {
    if (!trace.length) return h('div', { class: 'empty' }, 'No events yet. If the broker is down, the order is waiting in the outbox.');
    const t0 = trace[0].at;
    return h('div', { class: 'table-wrap' }, h('table', {},
      h('thead', {}, h('tr', {}, ['+ time', 'Event', 'Topic P@offset', 'Written by', 'Caused by', 'Read by'].map(x => h('th', {}, x)))),
      h('tbody', {}, trace.map(e => h('tr', { class: 'click', tabindex: 0, onclick: () => showEvent(e.id), onkeydown: ev => { if (ev.key === 'Enter') showEvent(e.id); } },
        h('td', { class: 'mono small nowrap' }, '+' + ms(e.at - t0)),
        h('td', {}, h('div', { style: { fontWeight: 600 } }, e.type, h('span', { class: 'muted small' }, ' v' + e.version)), h('div', { class: 'small muted' }, describe(e))),
        h('td', { class: 'nowrap' }, topicChip(e.topic), ' ', h('span', { class: 'mono small' }, e.partition + '@' + e.offset)),
        h('td', { class: 'small' }, e.source),
        h('td', { class: 'mono small' }, e.causationId || '–'),
        h('td', {}, e.consumers.length ? h('span', { class: 'cons' }, e.consumers.map(c => h('span', {}, lamp(c.state), c.group))) : h('span', { class: 'muted small' }, 'nobody needs it')))))));
  }

  function record(title, obj, keys) {
    if (!obj) return h('div', { class: 'panel' }, h('h2', {}, title), h('div', { class: 'muted small' }, 'No record yet.'));
    return h('div', { class: 'panel' }, h('h2', {}, title), h('dl', { class: 'kv' }, keys.filter(([k]) => obj[k] != null && obj[k] !== '' && obj[k] !== 0)
      .map(([k, l, f]) => [h('dt', {}, l), h('dd', {}, f ? f(obj[k]) : String(obj[k]))])));
  }

  VIEWS.order = async (ctx, id) => {
    const body = h('div', {}, h('div', { class: 'loading' }, 'Loading…'));
    fill(ctx.view, station(ctx, isStaff() ? 'orders' : 'my-orders', 'Order ' + id + ' – its whole journey through the services.',
      h('a', { class: 'btn', href: isStaff() ? '#/orders' : '#/my-orders' }, '◀ All orders')), body);
    const draw = async () => {
      const d = await api('GET', '/api/orders/' + encodeURIComponent(id));
      const v = d.view || { orderId: id, status: 'PLACED', total: d.order ? d.order.total : 0, units: 0, city: d.order && d.order.city, paymentMethod: d.order && d.order.paymentMethod, placedAt: d.order && d.order.placedAt };
      const acts = [];
      if (['PLACED', 'RESERVED', 'PAID', 'CONFIRMED'].includes(v.status) && !d.cancelAsked)
        acts.push(h('button', { class: 'btn danger', onclick: async () => {
          if (await confirmBox('Cancel ' + id + '?', 'This sends an OrderCancelRequested event. The saga answers with OrderCancelled, and the other services give back stock and money.', 'Ask to cancel', true))
            if (await act(() => api('POST', '/api/orders/' + id + '/cancel'), 'Cancel request sent')) draw();
        } }, 'Cancel order'));
      if (isStaff() && v.status === 'CONFIRMED') acts.push(h('button', { class: 'btn go', onclick: async () => { if (await act(() => api('POST', '/api/orders/' + id + '/dispatch'), 'Dispatched')) draw(); } }, 'Dispatch parcel'));
      if (isStaff() && v.status === 'DISPATCHED') acts.push(h('button', { class: 'btn go', onclick: async () => { if (await act(() => api('POST', '/api/orders/' + id + '/deliver'), 'Delivered')) draw(); } }, 'Mark delivered'));
      const lines = (d.lines || []).map(l => h('div', { class: 'cart-line' }, h('span', {}, l.name), h('span', { class: 'mono' }, '×' + l.qty), h('span', { class: 'mono' }, rupees(l.price * l.qty))));
      fill(body,
        h('div', { class: 'cols-detail' },
          h('div', {},
            ticket(Object.assign({}, v, { orderId: id })),
            h('div', { class: 'panel', style: { marginTop: '16px' } }, h('h2', {}, 'Items'), lines.length ? lines : h('div', { class: 'muted small' }, '–'),
              d.order && d.order.giftNote ? h('p', { class: 'small', style: { marginTop: '8px' } }, 'Gift note: “', d.order.giftNote, '”') : null,
              d.customer ? h('p', { class: 'small muted', style: { marginTop: '8px' } }, d.customer.name + ' · ' + d.customer.city + (isStaff() && d.order ? ' · channel ' + d.order.channel : '')) : null,
              acts.length ? h('div', { class: 'btns', style: { marginTop: '10px' } }, acts) : null,
              d.cancelAsked && v.status !== 'CANCELLED' ? h('p', { class: 'note warn small', style: { marginTop: '10px' } }, 'Cancel requested – waiting for the saga to answer.') : null)),
          h('div', {},
            h('div', { class: 'panel' }, h('h2', {}, 'Route'), routeStrip(d)),
            isStaff() && d.deadLetters && d.deadLetters.length ? h('div', { class: 'note bad' }, 'This order has ' + d.deadLetters.length + ' event(s) in Lost property: ',
              d.deadLetters.map(x => x.group + ' / ' + x.type + ' (' + x.state.toLowerCase() + ')').join(', '), '. ', h('a', { href: '#/dead-letters' }, 'Open lost property')) : null)),
        h('div', { class: 'panel' }, h('h2', {}, 'Train movements – the trace'),
          h('p', { class: 'muted small' }, 'Every event with correlation id ' + id + ', from all topics, in time order. Lamps show which consumer groups have handled each event (green), are still to read it (yellow) or gave up (red).'),
          traceTable(d.trace)),
        isStaff() ? h('div', { class: 'grid3' },
          record('Inventory record', d.reservation, [['state', 'State'], ['at', 'Time', whenOf]]),
          record('Payment record', d.payment, [['id', 'Payment'], ['state', 'State'], ['method', 'Method', x => METHOD[x] || x], ['amount', 'Amount', rupees], ['reference', 'Reference'], ['reason', 'Note']]),
          record('Shipment record', d.shipment, [['id', 'Shipment'], ['state', 'State'], ['courier', 'Courier'], ['awb', 'AWB'], ['dispatchedAt', 'Left', whenOf], ['deliveredAt', 'Delivered', whenOf]])) : null,
        isStaff() ? h('p', { class: 'muted small' }, 'Each service keeps its own record. Saga stage in the order service: ', h('b', {}, d.saga || '–'), '.') : null,
        h('div', { class: 'panel' }, h('h2', {}, 'Messages sent to the customer'),
          d.messages.length ? d.messages.map(m => h('div', { class: 'telegram' }, h('div', { class: 'tg-head' }, h('span', {}, m.kind), h('span', {}, whenOf(m.at))), m.text))
            : h('div', { class: 'muted small' }, 'None yet.')));
    };
    await draw();
    every(2000, () => draw().catch(() => { }));
  };

  async function showEvent(id) {
    const d = await act(() => api('GET', '/api/events/' + encodeURIComponent(id)));
    if (!d || d === true) return;
    const e = d.event;
    modal(e.type + ' v' + e.version, h('div', {},
      h('dl', { class: 'kv', style: { marginBottom: '12px' } },
        h('dt', {}, 'Event id'), h('dd', { class: 'mono' }, e.id),
        h('dt', {}, 'Topic'), h('dd', {}, topicChip(e.topic), ' partition ', h('b', {}, e.partition), ' · offset ', h('b', {}, e.offset)),
        h('dt', {}, 'Key'), h('dd', { class: 'mono' }, e.key || '–'),
        h('dt', {}, 'Time'), h('dd', {}, secOf(e.at) + ' · ' + dateOf(e.at)),
        h('dt', {}, 'Written by'), h('dd', {}, e.source),
        h('dt', {}, 'Correlation'), h('dd', {}, e.correlationId ? h('a', { href: '#/orders/' + e.correlationId, onclick: () => clear(document.getElementById('modal-root')) }, e.correlationId) : '–'),
        h('dt', {}, 'Caused by'), h('dd', {}, d.causedBy ? d.causedBy.type + ' (' + d.causedBy.id + ')' : 'a command (from the outbox)')),
      h('div', { class: 'lbl-s' }, 'Payload'),
      h('pre', { class: 'json' }, JSON.stringify(e.payload, null, 2))), true);
  }

  // ================================================================== the event register (log browser)
  VIEWS.events = async ctx => {
    let topic = state.query.get('topic') || 'orders', partition = 0, type = '', before = -1;
    const tabs = h('div', { class: 'tabs', role: 'group', 'aria-label': 'Topic' });
    const ptabs = h('div', { class: 'tabs', role: 'group', 'aria-label': 'Partition' });
    const typeSel = h('select', { 'aria-label': 'Event type', style: { maxWidth: '240px' }, onchange: () => { type = typeSel.value; before = -1; draw(); } });
    const list = h('div', {}, h('div', { class: 'loading' }, 'Loading…'));
    const info = h('p', { class: 'muted small' });
    fill(ctx.view, station(ctx, 'events', 'The log itself: append-only, one register per partition. Offsets count from 0 and never change.'),
      h('div', { class: 'panel' }, tabs, h('div', { class: 'btns', style: { marginBottom: '8px' } }, ptabs, typeSel), info, list));
    async function draw() {
      const r = await api('GET', `/api/events?topic=${topic}&partition=${partition}&before=${before}&type=${encodeURIComponent(type)}`);
      fill(tabs, state.meta.topics.map(t => h('button', { 'aria-pressed': String(t === topic), onclick: () => { topic = t; partition = 0; type = ''; before = -1; draw(); } }, t)));
      fill(ptabs, r.partitions.map(p => h('button', { 'aria-pressed': String(p.partition === partition), onclick: () => { partition = p.partition; before = -1; draw(); } },
        'Partition ' + p.partition, h('span', { class: 'n' }, p.end))));
      fill(typeSel, h('option', { value: '' }, 'All event types'), r.types.map(t => h('option', { value: t, selected: t === type }, t)));
      fill(info, 'Partition ', h('b', {}, topic + '-' + partition), ' holds ', h('b', {}, fmt(r.end)), ' events. Blue lines show where each consumer group will read next (its committed offset). ',
        'The partition is chosen from the key: hash(order id) mod ' + r.partitions.length + ', so one order always stays in one partition.');
      const markers = {};
      for (const g of r.readers) (markers[g.offset] = markers[g.offset] || []).push(g.group);
      const rows = [];
      for (const e of r.events) {
        if (markers[e.offset + 1] && !type) rows.push(h('tr', { class: 'marker' }, h('td', { colspan: 6 }, '▲ next to read: ' + markers[e.offset + 1].join(', '))));
        rows.push(h('tr', { class: 'click', tabindex: 0, onclick: () => showEvent(e.id), onkeydown: ev => { if (ev.key === 'Enter') showEvent(e.id); } },
          h('td', {}, e.offset), h('td', { class: 'small nowrap' }, secOf(e.at), h('div', { class: 'muted' }, dateOf(e.at))),
          h('td', {}, h('b', {}, e.type), h('span', { class: 'muted small' }, ' v' + e.version)),
          h('td', { class: 'mono small' }, e.key),
          h('td', { class: 'small' }, describe(e)),
          h('td', { class: 'small muted' }, e.source)));
      }
      fill(list, rows.length ? h('div', { class: 'table-wrap' }, h('table', { class: 'register' },
        h('thead', {}, h('tr', {}, ['Offset', 'Time', 'Event', 'Key', 'What happened', 'Written by'].map(x => h('th', {}, x)))), h('tbody', {}, rows))) : h('div', { class: 'empty' }, 'Nothing here.'),
        h('div', { class: 'btns', style: { marginTop: '10px' } },
          before !== -1 ? h('button', { class: 'btn small', onclick: () => { before = -1; draw(); } }, '▲ Newest') : null,
          r.events.length && r.events[r.events.length - 1].offset > 0 ? h('button', { class: 'btn small', onclick: () => { before = r.events[r.events.length - 1].offset; draw(); } }, 'Older ▼') : null));
    }
    await draw();
  };

  // ================================================================== consumer cabins
  VIEWS.consumers = async ctx => {
    const body = h('div', {}, h('div', { class: 'loading' }, 'Loading…'));
    fill(ctx.view, station(ctx, 'consumers', 'Each service is a consumer group. It remembers its own offset in every partition it reads. Lag = events written but not read yet.'), body);
    const draw = async () => {
      const r = await api('GET', '/api/consumers');
      fill(body,
        r.brokerDown ? h('div', { class: 'note bad' }, 'The broker is down: nobody can read. Consumers continue from their offsets when it is back.') : null,
        h('div', { class: 'cabins' }, r.groups.map(g => cabin(g, r.maxAttempts, draw))));
    };
    await draw();
    every(2000, () => draw().catch(() => { }));
  };

  function cabin(g, maxAttempts, redraw) {
    const maxEnd = Math.max(1, ...g.partitions.map(p => p.end));
    const ctl = [];
    if (isAdmin()) {
      ctl.push(g.paused
        ? h('button', { class: 'btn go small', onclick: async () => { if (await act(() => api('POST', '/api/consumers/' + g.name + '/resume'), g.name + ' restarted')) redraw(); } }, 'Restart')
        : h('button', { class: 'btn small', onclick: async () => { if (await act(() => api('POST', '/api/consumers/' + g.name + '/pause'), g.name + ' stopped')) redraw(); } }, 'Stop'));
      if (g.name === 'order-view') ctl.push(h('button', { class: 'btn small', onclick: async () => {
        if (await confirmBox('Rebuild the read model?', 'All order screens are thrown away and rebuilt by replaying every event from offset 0. Watch the lag go up and come back to 0.', 'Rebuild'))
          await act(() => api('POST', '/api/consumers/order-view/reset', { to: 'earliest' }), r => r.note);
      } }, 'Rebuild from log'));
      else ctl.push(h('button', { class: 'btn small', onclick: async () => {
        if (await confirmBox('Replay ' + g.name + '?', 'The offsets go back to 0 and the whole log is delivered again. The service checks its own records first, so nothing is charged, reserved or booked twice.', 'Replay'))
          await act(() => api('POST', '/api/consumers/' + g.name + '/reset', { to: 'earliest' }), r => r.note);
      } }, 'Replay from 0'));
      ctl.push(h('button', { class: 'btn small', onclick: async () => {
        if (await confirmBox('Skip to the newest?', 'Everything ' + g.name + ' has not read yet is skipped and never handled. Use with care.', 'Skip', true))
          await act(() => api('POST', '/api/consumers/' + g.name + '/reset', { to: 'latest' }), r => r.note);
      } }, 'Skip to latest'));
    }
    return h('div', { class: 'panel cabin' },
      signalPost(g.aspect),
      h('div', { style: { minWidth: 0 } },
        h('div', { class: 'panel-head' }, h('div', {}, h('h3', {}, g.name), h('div', { class: 'muted small' }, g.service + ' · ' + g.job)),
          h('span', { class: 'chip', style: { color: g.aspect === 'CLEAR' ? 'var(--green)' : g.aspect === 'CAUTION' ? 'var(--yellow)' : 'var(--red)' } }, h('i'), ASPECT[g.aspect])),
        h('div', { class: 'small', style: { margin: '4px 0' } }, 'Reads ', g.topics.map(t => [topicChip(t), ' ']), h('span', { class: 'muted' }, ' · reacts to ' + g.handles.length + ' event types')),
        h('div', { class: 'stats' },
          h('div', {}, h('b', {}, fmt(g.lag)), 'lag'),
          h('div', {}, h('b', {}, fmt(g.processed)), 'handled'),
          h('div', {}, h('b', {}, fmt(g.skipped)), 'not for me'),
          h('div', {}, h('b', {}, fmt(g.duplicates)), 'duplicates skipped'),
          h('div', {}, h('b', {}, fmt(g.retries)), 'retries'),
          h('div', {}, h('b', { style: g.openDeadLetters ? { color: 'var(--red)' } : null }, fmt(g.deadLettered)), 'dead-lettered')),
        g.paused ? h('div', { class: 'note bad small' }, '■ ' + (g.pausedReason || 'Stopped')) : null,
        g.slow ? h('div', { class: 'note warn small' }, 'Running slow: one event every 3 seconds (fault drill).') : null,
        g.lastError && Date.now() - g.lastErrorAt < 10 * 60000 ? h('div', { class: 'note bad small mono' }, 'Last error ' + whenOf(g.lastErrorAt) + ': ' + g.lastError) : null,
        h('table', { class: 'small' }, h('thead', {}, h('tr', {}, ['Partition', 'Track', 'Offset', 'End', 'Lag', ''].map(x => h('th', {}, x)))),
          h('tbody', {}, g.partitions.map(p => h('tr', {},
            h('td', { class: 'nowrap' }, topicChip(p.topic), ' ' + p.partition),
            h('td', { style: { width: '35%' } }, h('div', { class: 'occ', title: 'read ' + p.offset + ' of ' + p.end },
              h('span', { style: { width: (100 * p.offset / maxEnd) + '%' } }),
              p.lag ? h('span', { class: 'lag', style: { left: (100 * p.offset / maxEnd) + '%', width: Math.max(1.5, 100 * p.lag / maxEnd) + '%' } }) : null)),
            h('td', { class: 'mono' }, p.offset), h('td', { class: 'mono' }, p.end),
            h('td', { class: 'mono', style: p.lag ? { color: 'var(--yellow)', fontWeight: 700 } : null }, p.lag),
            h('td', { class: 'small' }, p.attempts ? 'try ' + (p.attempts + 1) + '/' + maxAttempts + ' in ' + ms(p.retryInMs) : ''))))),
        ctl.length ? h('div', { class: 'btns', style: { marginTop: '10px' } }, ctl) : null));
  }

  // ================================================================== lost property (dead letters)
  VIEWS['dead-letters'] = async ctx => {
    let st = 'OPEN';
    const tabs = h('div', { class: 'tabs', role: 'group', 'aria-label': 'State' });
    const hint = h('div', {});
    const list = h('div', {}, h('div', { class: 'loading' }, 'Loading…'));
    fill(ctx.view, station(ctx, 'dead-letters', 'Events a consumer gave up on after ' + 4 + ' tries (1 s, 2 s, 4 s apart). The group moved on so one bad event cannot block a partition for ever.'),
      hint, h('div', {}, tabs, list));
    async function draw() {
      const r = await api('GET', '/api/dead-letters?state=' + st);
      refreshBadges();
      fill(tabs, [['OPEN', 'Waiting'], ['REDRIVEN', 'Redriven'], ['DISCARDED', 'Discarded'], ['ALL', 'All']].map(([k, l]) =>
        h('button', { 'aria-pressed': String(st === k), onclick: () => { st = k; draw(); } }, l, k !== 'ALL' ? h('span', { class: 'n' }, r.counts[k] || 0) : null)));
      const tips = [];
      if (r.deadLetters.some(d => d.group === 'notifier' && d.state === 'OPEN'))
        tips.push(h('div', { class: 'note ' + (r.safeTemplates ? '' : 'warn') }, r.safeTemplates
          ? 'The notifier template fix is deployed. Redriving the notifier dead letters will work now.'
          : ['The notifier fails on gift notes with “{{”. Redrive will fail again until the fix is deployed ', isAdmin() ? h('a', { href: '#/faults' }, '(Fault drill → Deploy notifier fix)') : '(ask the admin)', '.']));
      if (r.deadLetters.some(d => d.group === 'payment' && d.state === 'OPEN'))
        tips.push(h('div', { class: 'note ' + (r.gatewayDown ? 'bad' : '') }, r.gatewayDown ? 'The payment gateway is still down. Redrive will fail until it is back.'
          : 'The payment gateway is working again. Redrive the payment dead letters and their orders will continue: paid → courier booked → confirmed.'));
      fill(hint, tips);
      fill(list, r.deadLetters.length ? h('div', { class: 'tags' }, r.deadLetters.map(d => h('div', { class: 'tag ' + d.state },
        h('div', { class: 'tag-id' }, d.id + ' · ' + d.group),
        h('h3', {}, d.type),
        h('div', { class: 'small' }, 'Order ', d.correlationId ? h('a', { href: '#/orders/' + d.correlationId }, d.correlationId) : '–',
          d.orderStatus ? ' · now ' + (STATUS[d.orderStatus] || d.orderStatus) : '', ' · ', h('a', { href: '#', onclick: e => { e.preventDefault(); showEvent(d.eventId); } }, d.eventId)),
        h('div', { class: 'small' }, d.topic + '-' + d.partition + ' @ ' + d.offset + ' · ' + d.attempts + ' tries · ' + whenOf(d.at)),
        h('div', { class: 'err-line' }, d.error),
        d.note ? h('div', { class: 'small' }, d.note) : null,
        d.state === 'OPEN' && isAdmin() ? h('div', { class: 'btns', style: { marginTop: '6px' } },
          h('button', { class: 'btn small primary', onclick: async () => { if (await act(() => api('POST', '/api/dead-letters/' + d.id + '/redrive'), 'Redriven: delivered to ' + d.group + ' again')) draw(); } }, 'Redrive'),
          h('button', { class: 'btn small', onclick: async () => { if (await confirmBox('Discard ' + d.id + '?', 'The event stays in the log, but ' + d.group + ' will never handle it.', 'Discard', true)) if (await act(() => api('POST', '/api/dead-letters/' + d.id + '/discard', {}), 'Discarded')) draw(); } }, 'Discard')) : null)))
        : h('div', { class: 'panel empty' }, st === 'OPEN' ? 'Nothing waiting. All consumers are handling their events.' : 'Nothing here.'));
    }
    await draw();
    every(4000, () => draw().catch(() => { }));
  };

  // ================================================================== parcel office (outbox)
  VIEWS.outbox = async ctx => {
    let pendingOnly = false;
    const body = h('div', {}, h('div', { class: 'loading' }, 'Loading…'));
    const toggle = h('label', { class: 'check' }, h('input', { type: 'checkbox', onchange: e => { pendingOnly = e.target.checked; draw(); } }), 'Only waiting rows');
    fill(ctx.view, station(ctx, 'outbox', 'Transactional outbox: a command saves its change and the event in one step. The relay publishes waiting rows every 200 ms.', toggle), body);
    async function draw() {
      const r = await api('GET', '/api/outbox' + (pendingOnly ? '?pending=1' : ''));
      fill(body,
        h('div', { class: 'plates' },
          h('div', { class: 'enamel ' + (r.pending ? 'warn' : 'good') }, h('div', { class: 'k' }, 'Waiting'), h('div', { class: 'v' }, fmt(r.pending)), h('div', { class: 's' }, 'rows not published yet')),
          h('div', { class: 'enamel' }, h('div', { class: 'k' }, 'Published'), h('div', { class: 'v' }, fmt(r.published)), h('div', { class: 's' }, 'since the app started')),
          h('div', { class: 'enamel ' + (r.brokerDown ? 'bad' : 'good') }, h('div', { class: 'k' }, 'Broker'), h('div', { class: 'v' }, r.brokerDown ? 'DOWN' : 'UP'), h('div', { class: 's' }, r.brokerDown ? 'rows wait here, nothing lost' : 'rows go out at once'))),
        h('div', { class: 'panel' }, h('h2', {}, 'Outbox rows (newest first)'),
          r.rows.length ? h('div', { class: 'table-wrap' }, h('table', {},
            h('thead', {}, h('tr', {}, ['#', 'Event', 'Topic', 'Key', 'Written', 'Published', 'Tries'].map(x => h('th', {}, x)))),
            h('tbody', {}, r.rows.map(o => h('tr', {},
              h('td', { class: 'mono' }, o.seq), h('td', {}, h('b', {}, o.type), h('span', { class: 'muted small' }, ' v' + o.version)),
              h('td', {}, topicChip(o.topic)), h('td', { class: 'mono small' }, o.key),
              h('td', { class: 'small nowrap' }, secOf(o.createdAt)),
              h('td', { class: 'small' }, o.sentAt ? [h('a', { href: '#', onclick: e => { e.preventDefault(); showEvent(o.eventId); } }, o.eventId), ' after ' + ms(o.sentAt - o.createdAt)]
                : h('span', { style: { color: 'var(--yellow)', fontWeight: 700 } }, 'waiting', o.lastError ? h('div', { class: 'muted small' }, o.lastError) : null)),
              h('td', { class: 'mono' }, o.attempts)))))) : h('div', { class: 'empty' }, 'No rows.')));
    }
    await draw();
    every(2000, () => draw().catch(() => { }));
  };

  // ================================================================== goods shed (inventory)
  VIEWS.inventory = async ctx => {
    const body = h('div', {}, h('div', { class: 'loading' }, 'Loading…'));
    fill(ctx.view, station(ctx, 'inventory', 'Owned by the inventory service. "Held" = reserved for orders that have not left yet. Available = on the shelf − held.'), body);
    async function draw() {
      const r = await api('GET', '/api/inventory');
      fill(body, h('div', { class: 'panel' }, h('div', { class: 'table-wrap' }, h('table', {},
        h('thead', {}, h('tr', {}, ['', 'Product', 'SKU', 'Price', 'On shelf', 'Held', 'Available', '', ''].map(x => h('th', {}, x)))),
        h('tbody', {}, r.products.map(p => h('tr', {},
          h('td', { style: { width: '34px' } }, h('span', { style: { display: 'inline-block', width: '26px', height: '26px' } }, pict(p.icon))),
          h('td', {}, h('b', {}, p.name), h('div', { class: 'muted small' }, p.category)),
          h('td', { class: 'mono small' }, p.sku), h('td', { class: 'right' }, rupees(p.price)),
          h('td', { class: 'right mono' }, p.stock), h('td', { class: 'right mono' }, p.reserved),
          h('td', { class: 'right mono', style: { fontWeight: 700, color: p.available === 0 ? 'var(--red)' : p.low ? 'var(--yellow)' : null } }, p.available),
          h('td', { style: { width: '18%' } }, h('div', { class: 'occ' }, h('span', { style: { width: Math.min(100, 100 * p.available / 80) + '%', background: p.low ? 'var(--yellow)' : 'var(--green)' } }))),
          h('td', {}, h('button', { class: 'btn small', onclick: () => restock(p, draw) }, 'Restock')))))))));
    }
    await draw();
    every(5000, () => draw().catch(() => { }));
  };

  function restock(p, done) {
    const qty = h('input', { type: 'number', id: 'rq', min: 1, max: 500, value: 20 });
    const close = modal('Restock ' + p.name, h('form', { onsubmit: async e => {
      e.preventDefault();
      if (await act(() => api('POST', '/api/inventory/' + p.sku + '/restock', { qty: Number(qty.value) }), 'Stock added – StockReplenished sent through the outbox')) { close(); done(); }
    } }, h('div', { class: 'field' }, h('label', { for: 'rq' }, 'Units arrived'), qty), h('button', { class: 'btn primary', type: 'submit' }, 'Add to shelf')));
  }

  // ================================================================== the rule book (schemas)
  VIEWS.schemas = async ctx => {
    const r = await api('GET', '/api/schemas');
    let n = 0;
    fill(ctx.view, station(ctx, 'schemas', 'The schema registry. The broker checks every event against its rule before it is written. Extra fields are allowed; missing ones are refused.'),
      h('div', { class: 'panel' }, h('h2', {}, 'Topics'), h('div', { class: 'btns' }, r.topics.map(t => h('span', {}, topicChip(t.name), h('span', { class: 'small muted' }, ' ' + t.partitions + ' partition(s) · ' + fmt(t.events) + ' events'))))),
      h('div', { class: 'note' }, 'Versions: the old mobile app still sends OrderPlaced v1 (no paymentMethod). Consumers “upcast” v1 to the v2 shape (paymentMethod = COD) before using it, so both versions can live in the same log.'),
      state.meta.topics.map(t => h('div', { class: 'panel' }, h('div', { class: 'panel-head' }, h('h2', {}, 'Topic ' + t)),
        r.schemas.filter(x => x.topic === t).map(x => { n++; return h('div', { class: 'rule' },
          h('div', { class: 'rule-head' }, h('span', { class: 'rule-no' }, 'Rule ' + n), h('h3', {}, x.type + ' v' + x.version), !x.latest ? h('span', { class: 'chip st-PLACED' }, 'older version') : null),
          h('div', { class: 'small' }, x.meaning),
          h('div', { class: 'small muted' }, 'Written by ', h('b', {}, x.producer), ' · read by ', x.consumers.length ? x.consumers.join(', ') : 'nobody (for people and tools)'),
          h('table', {}, h('thead', {}, h('tr', {}, h('th', {}, 'Field'), h('th', {}, 'Kind'), h('th', {}, 'Meaning'))),
            h('tbody', {}, x.fields.map(f => h('tr', {}, h('td', { class: 'mono' }, f.name), h('td', { class: 'small' }, f.kind), h('td', { class: 'small' }, f.meaning)))))); }))));
  };

  // ================================================================== traffic charts (analytics)
  VIEWS.analytics = async ctx => {
    const r = await api('GET', '/api/analytics');
    const tColors = state.meta.topics.map(t => ({ name: t, color: topicColor(t) }));
    const throughput = lineChart(state.meta.topics.map(t => ({ name: t, color: topicColor(t), points: r.minutes.map((m, i) => ({ t: m, v: r.throughput[t][i] })) })), { label: 'Events per minute by topic', width: 1100, height: 240 });
    const hours = barChart(r.hours.map(x => ({ label: new Date(x.at).getHours() === 0 ? dateOf(x.at) : timeOf(x.at),
      parts: [{ v: x.placed - x.cancelled, color: css('--blue'), name: 'went through' }, { v: x.cancelled, color: css('--red'), name: 'cancelled' }] })), { label: 'Orders per hour', maxLabels: 12 });
    const lat = r.latency;
    const latBars = barChart(lat.histogram.map(b => ({ label: b.label, parts: [{ v: b.count, color: css('--green') }] })), { values: true, height: 190, label: 'Placed to confirmed time' });
    const outcomes = Object.entries(r.outcomes);
    const OUT = { DELIVERED: ['Delivered', '--green'], DISPATCHED: ['On the way', '--t-shipments'], CONFIRMED: ['Confirmed', '--blue'], IN_PROGRESS: ['In progress', '--muted'],
      OUT_OF_STOCK: ['Cancelled: out of stock', '--red'], PAYMENT_DECLINED: ['Cancelled: payment declined', '--red'], CUSTOMER: ['Cancelled by customer', '--yellow'] };
    const days = barChart(r.days.map(d => ({ label: dateOf(new Date(d.day + 'T12:00:00').getTime()), parts: [{ v: d.revenue - d.refunds, color: css('--green'), name: 'kept' }, { v: d.refunds, color: css('--yellow'), name: 'refunded' }] })),
      { values: true, height: 190, yFmt: v => '₹' + fmt(v / 1000, 0) + 'k', label: 'Revenue per day' });
    const lagColors = ['--t-orders', '--t-inventory', '--t-payments', '--t-shipments', '--red', '--yellow'];
    const lag = r.lag.length > 1 ? lineChart(r.groups.map((g, i) => ({ name: g, color: css(lagColors[i % lagColors.length]), points: r.lag.map(x => ({ t: x.at, v: x[g] || 0 })) })), { height: 190, label: 'Consumer lag' })
      : h('div', { class: 'empty' }, 'Lag is sampled every 10 seconds while the app runs. Come back in a minute.');
    fill(ctx.view, station(ctx, 'analytics', 'Worked out from the event log and the read model.'),
      h('div', { class: 'panel' }, h('h2', {}, 'Events per minute – last hour'), throughput, legend(tColors)),
      h('div', { class: 'grid2' },
        h('div', { class: 'panel' }, h('h2', {}, 'Orders per hour – last 3 days'), hours, legend([{ name: 'went through', color: css('--blue') }, { name: 'cancelled', color: css('--red') }])),
        h('div', { class: 'panel' }, h('h2', {}, 'Placed → confirmed (end-to-end)'), latBars,
          h('p', { class: 'small' }, 'Median ', h('b', {}, ms(lat.p50)), ' · p95 ', h('b', {}, ms(lat.p95)), ' · p99 ', h('b', {}, ms(lat.p99)), ' over ' + fmt(lat.count) + ' orders. The slow ones waited in Lost property during the payment outage.'),
          h('table', { class: 'small' }, h('thead', {}, h('tr', {}, h('th', {}, 'Hop'), h('th', { class: 'right' }, 'Median'), h('th', { class: 'right' }, 'p95'))),
            h('tbody', {}, lat.hops.map(x => h('tr', {}, h('td', {}, x.label), h('td', { class: 'right mono' }, ms(x.median)), h('td', { class: 'right mono' }, ms(x.p95)))))))),
      h('div', { class: 'grid2' },
        h('div', { class: 'panel' }, h('h2', {}, 'How orders ended'), hbars(outcomes.map(([k, v]) => ({ label: OUT[k] ? OUT[k][0] : k, v, color: css(OUT[k] ? OUT[k][1] : '--ink') })))),
        h('div', { class: 'panel' }, h('h2', {}, 'Revenue per day'), days)),
      h('div', { class: 'grid3' },
        h('div', { class: 'panel' }, h('h2', {}, 'Payment methods'), hbars(Object.entries(r.methods).map(([k, v]) => ({ label: METHOD[k] || k, v }))),
          h('p', { class: 'small muted' }, r.oldAppShare + '% of orders came from the old app (OrderPlaced v1).')),
        h('div', { class: 'panel' }, h('h2', {}, 'Cities'), hbars(Object.entries(r.cities).sort((a, b) => b[1] - a[1]).map(([k, v]) => ({ label: k, v })), css('--t-shipments'))),
        h('div', { class: 'panel' }, h('h2', {}, 'Best sellers (units)'), hbars(r.top.map(t => ({ label: t.name, v: t.units })), css('--t-inventory')))),
      h('div', { class: 'panel' }, h('h2', {}, 'Consumer lag – last 30 minutes'), lag, r.lag.length > 1 ? legend(r.groups.map((g, i) => ({ name: g, color: css(lagColors[i % lagColors.length]) }))) : null));
  };

  // ================================================================== fault drill (simulator)
  VIEWS.faults = async ctx => {
    const banner = h('div', {});
    const body = h('div', {}, h('div', { class: 'loading' }, 'Loading…'));
    fill(ctx.view, station(ctx, 'faults', 'Cause a fault on purpose and watch how the system copes: the Signal box, Consumer cabins and Lost property show it live.'), banner, body);
    async function draw() {
      const r = await api('GET', '/api/sim');
      fill(banner, faultBanner(r.faults));
      const st = r.settings;
      const rate = h('input', { type: 'range', min: 0, max: 60, value: st.ordersPerMin, id: 'rate' });
      const rateOut = h('b', {}, st.ordersPerMin);
      rate.oninput = () => { rateOut.textContent = rate.value; };
      const dec = h('input', { type: 'number', min: 0, max: 50, value: st.declinePct, id: 'dec' });
      const old = h('input', { type: 'number', min: 0, max: 100, value: st.oldAppPct, id: 'old' });
      const ship = h('input', { type: 'checkbox', checked: st.autoShip });
      const fix = h('input', { type: 'checkbox', checked: st.safeTemplates });
      fill(body,
        h('div', { class: 'faults' }, r.catalogue.map(f => {
          const minutes = f.minutes ? h('input', { type: 'number', min: 1, max: 30, value: 2, 'aria-label': 'Minutes' }) : null;
          const group = f.group ? h('select', { 'aria-label': 'Consumer group' }, state.meta.groups.map(g => h('option', { value: g, selected: g === (f.kind === 'DUPLICATES' ? 'payment' : 'shipping') }, g))) : null;
          const count = f.count ? h('input', { type: 'number', min: 1, max: 20, value: 5, 'aria-label': 'How many' }) : null;
          return h('div', { class: 'fault' }, h('h3', {}, f.label), h('p', {}, f.explain),
            h('div', { class: 'row' },
              group ? h('div', { class: 'field' }, h('label', {}, 'Group'), group) : null,
              minutes ? h('div', { class: 'field', style: { maxWidth: '90px' } }, h('label', {}, 'Minutes'), minutes) : null,
              count ? h('div', { class: 'field', style: { maxWidth: '90px' } }, h('label', {}, 'Events'), count) : null,
              h('button', { class: 'btn danger', onclick: async () => {
                const res = await act(() => api('POST', '/api/sim/fault', { kind: f.kind, group: group && group.value, minutes: minutes ? Number(minutes.value) : 0, count: count ? Number(count.value) : 0 }),
                  x => x && x.orderId ? 'Poison order ' + x.orderId + ' placed' : 'Fault started: ' + f.label);
                if (res) draw();
              } }, 'Pull lever')));
        })),
        h('div', { class: 'grid2', style: { marginTop: '18px' } },
          h('div', { class: 'panel' }, h('h2', {}, 'Traffic'),
            h('div', { class: 'field' }, h('label', { for: 'rate' }, 'Orders per minute: ', rateOut), rate),
            h('div', { class: 'grid2' }, h('div', { class: 'field' }, h('label', { for: 'dec' }, 'Bank declines %'), dec), h('div', { class: 'field' }, h('label', { for: 'old' }, 'Old app (v1) %'), old)),
            h('label', { class: 'check', style: { marginBottom: '10px' } }, ship, 'Warehouse dispatches and courier delivers by itself'),
            h('div', { class: 'btns' },
              h('button', { class: 'btn primary', onclick: async () => { if (await act(() => api('POST', '/api/sim/settings', { ordersPerMin: Number(rate.value), declinePct: Number(dec.value), oldAppPct: Number(old.value), autoShip: ship.checked }), 'Saved')) draw(); } }, 'Save'),
              h('button', { class: 'btn', onclick: () => act(() => api('POST', '/api/sim/order'), x => 'Order ' + x.order.id + ' placed') }, 'Place one random order'))),
          h('div', { class: 'panel' }, h('h2', {}, 'Deploy a fix'),
            h('p', { class: 'small' }, 'The notifier has a bug: a gift note with “{{” breaks its template code (the Poison message drill). Deploy the fix, then redrive its dead letters in Lost property.'),
            h('label', { class: 'check', style: { marginBottom: '10px' } }, fix, 'Notifier template fix deployed'),
            h('button', { class: 'btn primary', onclick: async () => { if (await act(() => api('POST', '/api/sim/settings', { safeTemplates: fix.checked }), fix.checked ? 'Fix deployed' : 'Fix rolled back')) draw(); } }, 'Apply'))));
    }
    await draw();
    every(2000, async () => {
      const r = await api('GET', '/api/sim').catch(() => null);
      if (r) fill(banner, faultBanner(r.faults));
    });
  };

  // ================================================================== users and activity log
  VIEWS.users = async ctx => {
    const r = await api('GET', '/api/users');
    fill(ctx.view, station(ctx, 'users', 'Sign-in accounts. Customers can also be created here (they get a customer record with a city).', h('button', { class: 'btn primary', onclick: newUser }, 'New account')),
      h('div', { class: 'panel' }, h('div', { class: 'table-wrap' }, h('table', {},
        h('thead', {}, h('tr', {}, ['Name', 'Username', 'Role', 'City', 'State', ''].map(x => h('th', {}, x)))),
        h('tbody', {}, r.users.map(u => h('tr', {},
          h('td', {}, h('b', {}, u.name)), h('td', { class: 'mono' }, u.username), h('td', {}, u.roleLabel), h('td', {}, u.city || '–'),
          h('td', {}, u.locked ? h('span', { class: 'chip st-CANCELLED' }, 'locked') : u.active ? h('span', { class: 'chip st-DELIVERED' }, 'active') : h('span', { class: 'chip st-PLACED' }, 'switched off')),
          h('td', { class: 'btns' },
            u.locked ? h('button', { class: 'btn small', onclick: async () => { if (await act(() => api('PUT', '/api/users/' + u.id, { unlock: true }), 'Unlocked')) route(); } }, 'Unlock') : null,
            u.id !== state.user.id ? h('button', { class: 'btn small', onclick: async () => { if (await act(() => api('PUT', '/api/users/' + u.id, { active: !u.active }), 'Saved')) route(); } }, u.active ? 'Switch off' : 'Switch on') : null,
            h('button', { class: 'btn small', onclick: () => resetPw(u) }, 'New password')))))))));
  };
  function newUser() {
    const f = { name: h('input', { type: 'text', id: 'nn' }), username: h('input', { type: 'text', id: 'nu' }), password: h('input', { type: 'password', id: 'np', autocomplete: 'new-password' }),
      role: h('select', { id: 'nr' }, h('option', { value: 'CUSTOMER' }, 'Customer'), h('option', { value: 'STAFF' }, 'Operations staff'), h('option', { value: 'ADMIN' }, 'Platform admin')),
      city: h('select', { id: 'nc' }, state.meta.cities.map(c => h('option', { value: c }, c))) };
    const close = modal('New account', h('form', { onsubmit: async e => {
      e.preventDefault();
      if (await act(() => api('POST', '/api/users', { name: f.name.value, username: f.username.value, password: f.password.value, role: f.role.value, city: f.city.value }), 'Account created')) { close(); route(); }
    } },
      h('div', { class: 'field' }, h('label', { for: 'nn' }, 'Full name'), f.name),
      h('div', { class: 'field' }, h('label', { for: 'nu' }, 'Username'), f.username),
      h('div', { class: 'field' }, h('label', { for: 'np' }, 'Password (8+ characters, a letter and a number)'), f.password),
      h('div', { class: 'grid2' }, h('div', { class: 'field' }, h('label', { for: 'nr' }, 'Role'), f.role), h('div', { class: 'field' }, h('label', { for: 'nc' }, 'City (customers)'), f.city)),
      h('button', { class: 'btn primary', type: 'submit' }, 'Create')));
  }
  function resetPw(u) {
    const pw = h('input', { type: 'password', id: 'rp', autocomplete: 'new-password' });
    const close = modal('New password for ' + u.name, h('form', { onsubmit: async e => {
      e.preventDefault();
      if (await act(() => api('PUT', '/api/users/' + u.id, { password: pw.value }), 'Password set')) close();
    } }, h('div', { class: 'field' }, h('label', { for: 'rp' }, 'New password'), pw), h('button', { class: 'btn primary', type: 'submit' }, 'Save')));
  }

  VIEWS.audit = async ctx => {
    let q = '';
    const list = h('div', {});
    const search = h('input', { type: 'search', placeholder: 'Search…', 'aria-label': 'Search the log', style: { maxWidth: '260px' }, oninput: () => { q = search.value; clearTimeout(search._t); search._t = setTimeout(draw, 250); } });
    fill(ctx.view, station(ctx, 'audit', 'Who did what: sign-ins, faults, redrives, replays, restocks, dispatches.', search), h('div', { class: 'panel' }, list));
    async function draw() {
      const r = await api('GET', '/api/audit?q=' + encodeURIComponent(q));
      fill(list, r.entries.length ? h('div', { class: 'table-wrap' }, h('table', {},
        h('thead', {}, h('tr', {}, ['When', 'Who', 'Action', 'Target', 'Detail'].map(x => h('th', {}, x)))),
        h('tbody', {}, r.entries.map(a => h('tr', {}, h('td', { class: 'small nowrap' }, whenOf(a.at)), h('td', {}, a.who), h('td', { class: 'mono small' }, a.action),
          h('td', { class: 'mono small' }, a.target), h('td', { class: 'small' }, a.detail || '')))))) : h('div', { class: 'empty' }, 'Nothing found.'));
    }
    await draw();
  };

  // ================================================================== booking counter (shop)
  const cart = new Map();
  VIEWS.shop = async ctx => {
    const r = await api('GET', '/api/shop');
    const cartBox = h('div', { class: 'ticket cart' });
    const grid = h('div', { class: 'products' });
    let method = 'UPI';
    const custSel = isStaff() ? h('select', { 'aria-label': 'Customer' }, (r.customers || []).map(c => h('option', { value: c.id }, c.name + ' · ' + c.city))) : null;
    const note = h('input', { type: 'text', maxlength: 120, placeholder: 'Gift note (optional)', 'aria-label': 'Gift note' });
    function drawProducts() {
      fill(grid, r.products.map(p => {
        const inCart = cart.get(p.sku) || 0;
        return h('div', { class: 'prod' },
          h('div', { class: 'pic' }, pict(p.icon)),
          h('div', { class: 'cat' }, p.category), h('div', { class: 'nm' }, p.name),
          h('div', { class: 'pr' }, rupees(p.price)),
          h('div', { class: 'av' + (p.available === 0 ? ' out' : p.low ? ' low' : '') }, p.available === 0 ? 'Sold out right now' : p.low ? 'Only ' + p.available + ' left' : p.available + ' available'),
          h('button', { class: 'btn small' + (inCart ? ' primary' : ''), disabled: inCart >= 5, onclick: () => { cart.set(p.sku, inCart + 1); drawProducts(); drawCart(); } }, inCart ? 'In cart: ' + inCart + ' · add' : 'Add to cart'));
      }));
    }
    function drawCart() {
      const lines = [...cart.entries()].map(([sku, q]) => ({ p: r.products.find(x => x.sku === sku), q })).filter(x => x.p);
      const total = lines.reduce((a, x) => a + x.p.price * x.q, 0);
      const pay = h('div', { class: 'methods', role: 'radiogroup', 'aria-label': 'Payment method' }, Object.entries(METHOD).map(([k, l]) =>
        h('label', {}, h('input', { type: 'radio', name: 'pm', value: k, checked: method === k, onchange: () => { method = k; } }), l)));
      fill(cartBox,
        h('div', { class: 't-co' }, 'Booking slip'),
        h('div', { class: 't-route' }, h('span', {}, 'Ranchi'), h('span', { class: 'arr' }, '▶▶'), h('span', {}, isStaff() ? 'Customer' : (r.customer ? r.customer.city : '–'))),
        custSel ? h('div', { class: 'field' }, custSel) : null,
        lines.length ? lines.map(x => h('div', { class: 'cart-line' }, h('span', {}, x.p.name),
          h('span', { class: 'qty' }, h('button', { 'aria-label': 'One less', onclick: () => { x.q > 1 ? cart.set(x.p.sku, x.q - 1) : cart.delete(x.p.sku); drawProducts(); drawCart(); } }, '−'),
            h('span', { class: 'mono' }, x.q), h('button', { 'aria-label': 'One more', disabled: x.q >= 5, onclick: () => { cart.set(x.p.sku, x.q + 1); drawProducts(); drawCart(); } }, '+')),
          h('span', { class: 'mono' }, rupees(x.p.price * x.q)))) : h('p', { class: 'small', style: { opacity: .7, margin: '8px 0' } }, 'Your cart is empty. Add something from the shelf.'),
        h('div', { class: 't-grid' }, h('div', {}, h('b', {}, 'Fare'), h('span', { class: 't-total' }, rupees(total))), h('div', {}, h('b', {}, 'Items'), lines.reduce((a, x) => a + x.q, 0))),
        h('div', { style: { marginTop: '8px' } }, h('b', { style: { fontSize: '11px', letterSpacing: '.12em', textTransform: 'uppercase', opacity: .7 } }, 'Pay by'), pay),
        h('div', { class: 'field' }, note),
        h('button', { class: 'btn primary', style: { width: '100%', justifyContent: 'center' }, disabled: !lines.length, onclick: async () => {
          const res = await act(() => api('POST', '/api/orders', { customerId: custSel ? custSel.value : null, paymentMethod: method, giftNote: note.value || null,
            items: lines.map(x => ({ sku: x.p.sku, qty: x.q })) }));
          if (res && res.order) {
            cart.clear();
            toast('Order ' + res.order.id + ' booked – now follow its journey');
            location.hash = '#/orders/' + res.order.id;
          }
        } }, 'Book order'),
        h('p', { class: 'small', style: { opacity: .75, marginTop: '8px' } }, 'You get an answer at once: the order is saved with an OrderPlaced event in the outbox. Stock, payment and the courier are handled a moment later by other services.'));
    }
    drawProducts();
    drawCart();
    fill(ctx.view, station(ctx, 'shop', isStaff() ? 'Place a test order for any customer.' : 'Jharkhand Haat – crafts and everyday things, delivered across Jharkhand and nearby.'),
      h('div', { class: 'shop' }, grid, cartBox));
  };

  // ================================================================== my orders and messages (customers)
  VIEWS['my-orders'] = async ctx => {
    const body = h('div', {}, h('div', { class: 'loading' }, 'Loading…'));
    fill(ctx.view, station(ctx, 'my-orders', 'Your order tickets. Click one to follow its journey.', h('a', { class: 'btn primary', href: '#/shop' }, 'Shop more')), body);
    const draw = async () => {
      const r = await api('GET', '/api/orders');
      fill(body, r.orders.length ? h('div', { class: 'ticket-list' }, r.orders.map(o => h('a', { href: '#/orders/' + o.orderId }, ticket(o, true))))
        : h('div', { class: 'panel empty' }, 'No orders yet. ', h('a', { href: '#/shop' }, 'Visit the booking counter.')));
    };
    await draw();
    every(4000, () => draw().catch(() => { }));
  };

  VIEWS.messages = async ctx => {
    const r = await api('GET', '/api/notifications');
    fill(ctx.view, station(ctx, 'messages', 'Sent by the notification service when your orders move.'),
      h('div', { class: 'panel', style: { maxWidth: '760px' } }, r.messages.length ? r.messages.map(m => h('div', { class: 'telegram' + (m.read ? '' : ' unread') },
        h('div', { class: 'tg-head' }, h('span', {}, 'Telegram · ' + m.kind), h('span', {}, whenOf(m.at))), m.text, ' ',
        h('a', { href: '#/orders/' + m.orderId, class: 'small' }, 'open order'))) : h('div', { class: 'empty' }, 'No messages yet.')));
    if (r.messages.some(m => !m.read)) api('POST', '/api/notifications/read').catch(() => { });
  };

  // ================================================================== start
  window.addEventListener('hashchange', route);
  route();
})();
