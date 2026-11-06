/* Pramaan – frontend (plain JavaScript, no libraries).
   Every piece of text from the server is put on the page with textContent, never as HTML,
   so a name like <script> is shown as text and cannot run. The only HTML we insert is the QR-code SVG,
   which the server builds itself from fixed shapes. */
(function () {
  'use strict';

  const S = { user: null, info: null, pendingFile: null, samples: null, view: null };
  const view = document.getElementById('view');

  // ------------------------------------------------------------------ small helpers

  /** h('div', {class: 'x', onclick: fn}, child, 'text') – builds DOM safely. */
  function h(tag, attrs, ...kids) {
    const el = document.createElement(tag);
    if (attrs) for (const [k, v] of Object.entries(attrs)) setAttr(el, k, v);
    append(el, kids);
    return el;
  }
  function setAttr(el, k, v) {
    if (v === null || v === undefined || v === false) return;
    if (k === 'class') el.className = v;
    else if (k === 'text') el.textContent = v;
    else if (k.startsWith('on') && typeof v === 'function') el.addEventListener(k.slice(2), v);
    else if (k === 'value') el.value = v;
    else if (k === 'checked' || k === 'selected' || k === 'disabled') el[k] = !!v;
    else el.setAttribute(k, v === true ? '' : String(v));
  }
  function append(el, kids) {
    for (const k of kids.flat(Infinity)) {
      if (k === null || k === undefined || k === false) continue;
      el.appendChild(k instanceof Node ? k : document.createTextNode(String(k)));
    }
  }
  const SVGNS = 'http://www.w3.org/2000/svg';
  function s(tag, attrs, ...kids) {
    const el = document.createElementNS(SVGNS, tag);
    if (attrs) for (const [k, v] of Object.entries(attrs)) if (v !== null && v !== undefined) el.setAttribute(k, String(v));
    for (const k of kids.flat()) if (k) el.appendChild(k);
    return el;
  }
  function clear(el) { while (el.firstChild) el.removeChild(el.firstChild); return el; }
  function $(sel, root) { return (root || document).querySelector(sel); }

  async function api(method, path, body) {
    const opt = { method, headers: {}, credentials: 'same-origin' };
    if (body !== undefined) { opt.headers['Content-Type'] = 'application/json'; opt.body = JSON.stringify(body); }
    let res;
    try { res = await fetch(path, opt); } catch (e) { throw new Error('Cannot reach the Pramaan server. Is it running?'); }
    let data = null;
    try { data = await res.json(); } catch (e) { data = null; }
    if (!res.ok) {
      const err = new Error((data && data.error) || ('Error ' + res.status));
      err.status = res.status;
      if (res.status === 401 && S.user) { S.user = null; buildNav(); }
      throw err;
    }
    return data;
  }

  let toastTimer = null;
  function toast(msg, bad) {
    const t = document.getElementById('toast');
    t.textContent = msg;
    t.className = 'toast' + (bad ? ' bad' : '');
    t.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => { t.hidden = true; }, 3800);
  }

  const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
  function fmtDate(ms) {
    if (!ms) return '–';
    const d = new Date(ms);
    return d.getDate() + ' ' + MONTHS[d.getMonth()] + ' ' + d.getFullYear();
  }
  function fmtTime(ms) {
    if (!ms) return '–';
    const d = new Date(ms);
    return fmtDate(ms) + ', ' + String(d.getHours()).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0');
  }
  function fmtIso(iso) {
    if (!iso || !/^\d{4}-\d{2}-\d{2}$/.test(iso)) return iso || '';
    const [y, m, d] = iso.split('-').map(Number);
    return d + ' ' + MONTHS[m - 1] + ' ' + y;
  }
  function ago(ms) {
    const s = Math.round((Date.now() - ms) / 1000);
    if (s < 60) return 'just now';
    if (s < 3600) return Math.round(s / 60) + ' min ago';
    if (s < 86400) return Math.round(s / 3600) + ' h ago';
    return Math.round(s / 86400) + ' days ago';
  }
  /** A hash with its proof-of-work zeros in red. */
  function hashEl(hash, cut) {
    if (!hash) return h('span', { class: 'hash', text: '–' });
    const text = cut ? hash.slice(0, cut) + '…' : hash;
    const m = text.match(/^0*/)[0];
    return h('span', { class: 'hash', title: hash }, h('span', { class: 'z', text: m }), text.slice(m.length));
  }
  function copy(text) {
    if (navigator.clipboard) navigator.clipboard.writeText(text).then(() => toast('Copied'), () => toast('Select the text and copy it', true));
    else toast('Select the text and copy it', true);
  }
  function download(name, text, type) {
    const url = URL.createObjectURL(new Blob([text], { type: type || 'application/json' }));
    const a = h('a', { href: url, download: name });
    document.body.appendChild(a);
    a.click();
    setTimeout(() => { URL.revokeObjectURL(url); a.remove(); }, 500);
  }
  function busy(btn, on, label) {
    if (!btn) return;
    if (on) { btn.dataset.label = btn.textContent; btn.textContent = label || 'Working…'; btn.disabled = true; }
    else { btn.textContent = btn.dataset.label || btn.textContent; btn.disabled = false; }
  }
  function showError(el, e) {
    clear(el).appendChild(h('p', { class: 'error', role: 'alert', text: e.message || String(e) }));
  }

  // ------------------------------------------------------------------ guilloché art (drawn, not images)

  function gcd(a, b) { return b ? gcd(b, a % b) : a; }
  /** A hypotrochoid "rosette" – the looping pattern printed on bank notes. */
  function rosettePath(R, r, d, cx, cy, scale, steps) {
    const turns = r / gcd(R, r);
    const n = steps || 900;
    let p = '';
    for (let i = 0; i <= n; i++) {
      const t = (i / n) * 2 * Math.PI * turns;
      const x = (R - r) * Math.cos(t) + d * Math.cos(((R - r) / r) * t);
      const y = (R - r) * Math.sin(t) - d * Math.sin(((R - r) / r) * t);
      p += (i ? 'L' : 'M') + (cx + x * scale).toFixed(2) + ',' + (cy + y * scale).toFixed(2);
    }
    return p + 'Z';
  }
  function rosette(size, variant) {
    const c = size / 2;
    const v = variant || 0;
    const sets = [[96, 11, 34], [96, 13, 40], [90, 17, 28]];
    const g = s('svg', { viewBox: `0 0 ${size} ${size}`, 'aria-hidden': 'true' });
    sets.forEach(([R, r, d], i) => {
      g.appendChild(s('path', {
        d: rosettePath(R, r + v, d, c, c, (size / 2 - 2) / (R - r + v + d), 700),
        fill: 'none', stroke: 'currentColor', 'stroke-width': i === 0 ? 0.55 : 0.4, opacity: i === 2 ? 0.55 : 0.9
      }));
    });
    g.appendChild(s('circle', { cx: c, cy: c, r: size * 0.16, fill: 'none', stroke: 'currentColor', 'stroke-width': 0.8 }));
    return g;
  }
  /** Wavy guilloché bands along the four edges of the certificate. */
  function certFrame() {
    const W = 1414, H = 1000;
    const svg = s('svg', { class: 'cert-layer cert-frame', viewBox: `0 0 ${W} ${H}`, preserveAspectRatio: 'none', 'aria-hidden': 'true' });
    const inset = 34;
    for (let k = 0; k < 6; k++) {
      const amp = 9, freq = 0.055, ph = k * 0.9;
      let d = '';
      const edge = (x0, y0, x1, y1, nx, ny) => {
        const len = Math.hypot(x1 - x0, y1 - y0);
        const steps = Math.ceil(len / 6);
        for (let i = 0; i <= steps; i++) {
          const t = i / steps, sx = t * len;
          const o = amp * Math.sin(sx * freq + ph) + 3 * Math.sin(sx * freq * 3.1 + ph * 2);
          const x = x0 + (x1 - x0) * t + nx * o, y = y0 + (y1 - y0) * t + ny * o;
          d += (d ? 'L' : 'M') + x.toFixed(1) + ',' + y.toFixed(1);
        }
      };
      const a = inset + k * 1.2;
      edge(a, a, W - a, a, 0, 1);
      edge(W - a, a, W - a, H - a, -1, 0);
      edge(W - a, H - a, a, H - a, 0, -1);
      edge(a, H - a, a, a, 1, 0);
      svg.appendChild(s('path', { d: d + 'Z', fill: 'none', stroke: 'currentColor', 'stroke-width': 0.9, opacity: 0.75, class: k % 2 ? 'g2' : '' }));
    }
    svg.appendChild(s('rect', { x: 62, y: 62, width: W - 124, height: H - 124, fill: 'none', stroke: 'currentColor', 'stroke-width': 1.4, opacity: 0.6 }));
    svg.appendChild(s('rect', { x: 68, y: 68, width: W - 136, height: H - 136, fill: 'none', stroke: 'currentColor', 'stroke-width': 0.6, opacity: 0.5, class: 'g2' }));
    // corner rosettes
    // rosettes in the two corners that do not hold the red serial numbers
    [[W - 110, 110], [110, H - 110]].forEach(([x, y], i) => {
      svg.appendChild(s('path', { d: rosettePath(60, 7 + i % 2, 22, x, y, 0.62, 500), fill: 'none', stroke: 'currentColor', 'stroke-width': 0.7, opacity: 0.8, class: i % 2 ? 'g2' : '' }));
    });
    // big faint centre rosette (like a watermark)
    svg.appendChild(s('path', { d: rosettePath(120, 23, 58, W / 2, H / 2 + 20, 1.9, 1400), fill: 'none', stroke: 'currentColor', 'stroke-width': 0.5, opacity: 0.12 }));
    return svg;
  }
  /** Coloured security fibres in the paper. Faint in daylight, glowing under the UV lamp. */
  function fibres(seedText) {
    let seed = 0;
    for (const ch of seedText) seed = (seed * 31 + ch.charCodeAt(0)) >>> 0;
    const rnd = () => ((seed = (seed * 1664525 + 1013904223) >>> 0) / 4294967296);
    const svg = s('svg', { class: 'cert-layer cert-fibres', viewBox: '0 0 1414 1000', preserveAspectRatio: 'none', 'aria-hidden': 'true' });
    for (let i = 0; i < 46; i++) {
      const x = 80 + rnd() * 1250, y = 80 + rnd() * 840, a = rnd() * Math.PI, L = 8 + rnd() * 16;
      svg.appendChild(s('line', { x1: x.toFixed(1), y1: y.toFixed(1), x2: (x + L * Math.cos(a)).toFixed(1), y2: (y + L * Math.sin(a)).toFixed(1), class: i % 2 ? 'a' : 'b' }));
    }
    return svg;
  }

  /**
   * The certificate, drawn like security paper.
   * c = { certId, issuerName, issuerCity, values: {holder, rollNo, ...}, qrSvg?, revoked? }
   */
  function renderCertificate(c, opts) {
    const o = opts || {};
    const v = c.values || {};
    const cert = h('div', { class: 'cert' + (o.small ? ' small-cert' : ''), role: 'img', 'aria-label': 'Certificate ' + (c.certId || '') + ' for ' + (v.holder || '') });
    cert.appendChild(fibres(c.certId || v.holder || 'x'));
    cert.appendChild(certFrame());
    cert.appendChild(h('div', { class: 'cert-uv', 'aria-hidden': 'true', text: 'PRAMAAN · ' + (c.issuerId || 'GENUINE') }));
    const inner = h('div', { class: 'cert-inner' },
      h('div', { class: 'cert-issuer', text: c.issuerName || v.issuer || '' }),
      h('div', { class: 'cert-city', text: c.issuerCity || '' }),
      h('div', { class: 'cert-kicker', text: 'This is to certify that' }),
      h('div', { class: 'cert-name', text: v.holder || '—' }),
      h('div', { class: 'cert-rule' }),
      h('div', { class: 'cert-kicker', text: 'has been awarded' }),
      h('div', { class: 'cert-prog', text: v.programme || '—' }),
      h('div', { class: 'cert-result', text: v.result || '' }),
      h('div', { class: 'cert-meta' },
        h('div', null, h('b', { text: 'Roll No.' }), v.rollNo || '—'),
        h('div', null, h('b', { text: 'Session' }), v.session || '—'),
        h('div', null, h('b', { text: 'Date of issue' }), fmtIso(v.issueDate) || '—'))
    );
    cert.appendChild(inner);
    if (c.certId) {
      cert.appendChild(h('div', { class: 'cert-serial tl', text: c.certId }));
      cert.appendChild(h('div', { class: 'cert-serial br', text: c.certId }));
    }
    const seal = h('div', { class: 'cert-seal', 'aria-hidden': 'true' }, h('div', { class: 'cert-holo' }));
    seal.appendChild(rosette(120, 2));
    cert.appendChild(seal);
    if (c.qrSvg) {
      const q = h('div', { class: 'cert-qr' });
      q.innerHTML = c.qrSvg; // built by our own server from fixed shapes only
      cert.appendChild(q);
      cert.appendChild(h('div', { class: 'cert-qr-cap', text: 'Scan to verify' }));
    }
    const micro = ('SEALED ON THE PRAMAAN BLOCKCHAIN · ' + (c.certId || '') + ' · ').repeat(8);
    cert.appendChild(h('div', { class: 'cert-micro', 'aria-hidden': 'true', text: micro }));
    if (c.revoked) cert.appendChild(h('div', { class: 'cert-void', text: 'REVOKED' }));
    return cert;
  }

  // ------------------------------------------------------------------ theme (Daylight / UV lamp)

  function isDark() {
    const t = document.documentElement.getAttribute('data-theme');
    if (t) return t === 'dark';
    return window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches;
  }
  function paintLamp() {
    const b = document.getElementById('lampBtn');
    const dark = isDark();
    b.setAttribute('aria-pressed', dark ? 'true' : 'false');
    document.getElementById('lampText').textContent = dark ? 'Daylight' : 'UV lamp';
    b.title = dark ? 'Switch to daylight (light mode)' : 'Look under the UV lamp (dark mode) – hidden ink lights up';
  }
  document.getElementById('lampBtn').addEventListener('click', () => {
    const next = isDark() ? 'light' : 'dark';
    document.documentElement.setAttribute('data-theme', next);
    try { localStorage.setItem('pramaan-theme', next); } catch (e) { /* storage blocked */ }
    paintLamp();
  });
  if (window.matchMedia) window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', paintLamp);

  // ------------------------------------------------------------------ navigation

  const NAV = {
    public: [['#/verify', 'Verify'], ['#/ledger', 'Ledger']],
    STUDENT: [['#/my', 'My certificates']],
    ISSUER: [['#/dash', 'Desk'], ['#/issue', 'Issue'], ['#/batch', 'Batch issue'], ['#/certs', 'Register']],
    REGISTRAR: [['#/nodes', 'Nodes & lab'], ['#/inst', 'Institutions'], ['#/bench', 'Forgery test'], ['#/activity', 'Activity'], ['#/people', 'People']]
  };

  function buildNav() {
    const tabs = clear(document.getElementById('tabs'));
    const cur = location.hash.split('/').slice(0, 2).join('/') || '#/verify';
    const add = ([href, label]) => tabs.appendChild(h('a', { href, text: label, 'aria-current': href === cur ? 'page' : null }));
    NAV.public.forEach(add);
    if (S.user) {
      tabs.appendChild(h('span', { class: 'sep', 'aria-hidden': 'true' }));
      NAV[S.user.role].forEach(add);
    }
    const who = clear(document.getElementById('who'));
    if (S.user) {
      who.appendChild(h('span', { class: 'role', text: S.user.role === 'ISSUER' ? (S.user.issuerId || 'ISSUER') : S.user.role }));
      who.appendChild(h('span', { class: 'small', text: S.user.displayName.split(' (')[0] }));
      who.appendChild(h('button', { class: 'btn small', type: 'button', text: 'Sign out', onclick: logout }));
    } else {
      who.appendChild(h('a', { class: 'btn small', href: '#/login', text: 'Sign in' }));
    }
  }

  async function logout() {
    try { await api('POST', '/api/logout', {}); } catch (e) { /* ignore */ }
    S.user = null;
    buildNav();
    location.hash = '#/verify';
    toast('Signed out');
  }

  const ROUTES = {
    verify: viewVerify, v: viewVerify, ledger: viewLedger, login: viewLogin,
    my: viewMy, cert: viewCert,
    dash: viewDash, issue: viewIssue, batch: viewBatch, certs: viewCerts,
    nodes: viewNodes, inst: viewInst, bench: viewBench, activity: viewActivity, people: viewPeople
  };
  const NEEDS = { my: 'STUDENT', dash: 'ISSUER', issue: 'ISSUER', batch: 'ISSUER', certs: 'ISSUER', cert: 'ANY',
    nodes: 'REGISTRAR', inst: 'REGISTRAR', bench: 'REGISTRAR', activity: 'REGISTRAR', people: 'REGISTRAR' };

  function homeFor(u) {
    if (!u) return '#/verify';
    return { STUDENT: '#/my', ISSUER: '#/dash', REGISTRAR: '#/nodes' }[u.role] || '#/verify';
  }

  async function render() {
    const parts = (location.hash || '#/verify').slice(2).split('/').map(decodeURIComponent);
    const name = parts[0] || 'verify';
    const fn = ROUTES[name];
    buildNav();
    if (!fn) { location.hash = '#/verify'; return; }
    const need = NEEDS[name];
    if (need && (!S.user || (need !== 'ANY' && S.user.role !== need))) {
      if (!S.user) { S.after = location.hash; location.hash = '#/login'; return; }
      location.hash = homeFor(S.user);
      return;
    }
    document.querySelectorAll('.drawer, .drawer-back').forEach((x) => x.remove());
    clear(view);
    S.view = name;
    try {
      await fn(parts.slice(1));
    } catch (e) {
      clear(view).appendChild(h('div', { class: 'panel' }, h('h2', { text: 'Something went wrong' }), h('p', { class: 'error', text: e.message })));
    }
    view.focus({ preventScroll: true });
    window.scrollTo(0, 0);
  }
  window.addEventListener('hashchange', render);

  function pageHead(eyebrow, title, text, ...right) {
    return h('div', { class: 'page-head' },
      h('div', null, h('span', { class: 'eyebrow', text: eyebrow }), h('h1', { text: title }), text ? h('p', { text }) : null),
      right.length ? h('div', { class: 'row' }, right) : null);
  }

  // ================================================================== VERIFY (public)

  const VERDICT = {
    GENUINE: { word: 'GENUINE', sub: 'VERIFIED ON CHAIN', cls: '' },
    TAMPERED: { word: 'FORGED', sub: 'DETAILS ALTERED', cls: 'bad' },
    NOT_FOUND: { word: 'FAKE', sub: 'NEVER ISSUED', cls: 'bad' },
    REVOKED: { word: 'REVOKED', sub: 'NO LONGER VALID', cls: 'warn' },
    UNTRUSTED_ISSUER: { word: 'UNTRUSTED', sub: 'ISSUER NOT APPROVED', cls: 'bad' },
    NETWORK_UNSAFE: { word: 'NO CONSENSUS', sub: 'NODES DISAGREE', cls: 'grey' },
    BAD_INPUT: { word: 'CHECK INPUT', sub: 'NOTHING VERIFIED', cls: 'grey' }
  };

  async function viewVerify(args) {
    if (!S.info) { try { S.info = await api('GET', '/api/public/info'); } catch (e) { /* shown later */ } }
    const fields = (S.info && S.info.fields) || [];
    let method = 'FILE';
    let loadedFile = null;

    const report = h('section', { class: 'panel framed report', 'aria-live': 'polite', 'aria-label': 'Inspection report' });
    emptyReport();

    function emptyReport() {
      const seal = h('div', { class: 'seal-big' });
      seal.appendChild(rosette(150, 0));
      clear(report).appendChild(h('div', { class: 'report-empty' }, seal,
        h('h2', { text: 'Inspection report' }),
        h('p', { text: 'Choose a way to check on the left. The result appears here with every security check explained.' })));
    }

    // ---- method tabs
    const tabsEl = h('div', { class: 'methods', role: 'tablist', 'aria-label': 'How do you want to check?' });
    const body = h('div');
    const M = [['FILE', '▤', 'Certificate file'], ['TYPED', '✎', 'Type from paper'], ['LINK', '▦', 'QR code / link']];
    M.forEach(([key, ic, label]) => {
      tabsEl.appendChild(h('button', { type: 'button', role: 'tab', 'aria-selected': key === method ? 'true' : 'false',
        onclick: () => { method = key; [...tabsEl.children].forEach((b, i) => b.setAttribute('aria-selected', M[i][0] === key ? 'true' : 'false')); drawBody(); } },
        h('span', { class: 'ic', 'aria-hidden': 'true', text: ic }), label));
    });

    const fileInput = h('input', { type: 'file', accept: '.json,application/json', class: 'sr', id: 'certFile' });
    fileInput.addEventListener('change', () => { if (fileInput.files[0]) readFile(fileInput.files[0]); });

    function readFile(f) {
      if (f.size > 200000) { toast('That file is too big to be a certificate file', true); return; }
      const r = new FileReader();
      r.onload = () => {
        try { loadedFile = JSON.parse(r.result); drawBody(); }
        catch (e) { toast('That file is not a Pramaan certificate (.pramaan.json)', true); }
      };
      r.readAsText(f);
    }

    function drawBody() {
      clear(body);
      if (method === 'FILE') {
        const drop = h('div', { class: 'drop' },
          h('p', null, h('strong', { text: 'Drop the certificate file here' }), h('br'), 'the .pramaan.json file the student received'),
          h('label', { for: 'certFile', class: 'btn small', text: 'Choose file…' }), fileInput);
        drop.addEventListener('dragover', (e) => { e.preventDefault(); drop.classList.add('over'); });
        drop.addEventListener('dragleave', () => drop.classList.remove('over'));
        drop.addEventListener('drop', (e) => { e.preventDefault(); drop.classList.remove('over'); if (e.dataTransfer.files[0]) readFile(e.dataTransfer.files[0]); });
        body.appendChild(drop);
        if (loadedFile && loadedFile.fields) {
          const inputs = {};
          const box = h('div', { class: 'panel', style: null },
            h('div', { class: 'row' }, h('h3', { text: 'Loaded: ' }), h('span', { class: 'mono', text: String(loadedFile.certId || '?') }), h('span', { class: 'spacer' }),
              h('button', { class: 'btn-link small', type: 'button', text: 'clear', onclick: () => { loadedFile = null; drawBody(); emptyReport(); } })),
            h('p', { class: 'small muted', text: 'Values from the file. Try changing one (for example the result) and check again – that is exactly what a forger would do.' }));
          fields.forEach((f) => {
            const inp = h('input', { type: 'text', id: 'ff-' + f.name, value: String(loadedFile.fields[f.name] ?? '') });
            inputs[f.name] = inp;
            box.appendChild(h('div', { class: 'field' }, h('label', { for: 'ff-' + f.name, text: f.label }), inp));
          });
          const btn = h('button', { class: 'btn primary', type: 'button', text: 'Check this certificate' });
          btn.addEventListener('click', () => {
            const file = JSON.parse(JSON.stringify(loadedFile));
            fields.forEach((f) => { file.fields[f.name] = inputs[f.name].value; });
            run({ method: 'FILE', file }, btn);
          });
          box.appendChild(btn);
          body.appendChild(h('div', { class: 'stack' }, h('div'), box));
        }
      } else if (method === 'TYPED') {
        const idIn = h('input', { type: 'text', id: 'tid', class: 'id-input', placeholder: 'PRM-XXXX-XXXX-X', autocomplete: 'off', spellcheck: 'false' });
        const form = h('form', { class: 'stack', novalidate: true },
          h('p', { class: 'small muted', text: 'Type what is printed on the paper. The certificate ID is in red in two corners. Fill in at least the name and one more detail – the more, the better.' }),
          h('div', { class: 'field' }, h('label', { for: 'tid', text: 'Certificate ID' }), idIn));
        const inputs = {};
        fields.forEach((f) => {
          if (f.name === 'issuer') return;
          const inp = h('input', { type: 'text', id: 'tf-' + f.name, placeholder: f.name === 'issueDate' ? 'yyyy-mm-dd' : '' });
          inputs[f.name] = inp;
          form.appendChild(h('div', { class: 'field' }, h('label', { for: 'tf-' + f.name, text: f.label }), inp));
        });
        const btn = h('button', { class: 'btn primary', type: 'submit', text: 'Check these details' });
        form.appendChild(btn);
        form.addEventListener('submit', (e) => {
          e.preventDefault();
          const vals = {};
          Object.keys(inputs).forEach((k) => { vals[k] = inputs[k].value; });
          run({ method: 'TYPED', certId: idIn.value, fields: vals }, btn);
        });
        body.appendChild(form);
      } else {
        const inp = h('input', { type: 'text', id: 'lnk', placeholder: 'http://…/#/v/PRM-…/…', autocomplete: 'off', spellcheck: 'false' });
        const form = h('form', { class: 'stack', novalidate: true },
          h('p', { class: 'small muted', text: 'Scan the QR code on the certificate with your phone camera, or paste the link the student sent you.' }),
          h('div', { class: 'field' }, h('label', { for: 'lnk', text: 'Verification link' }), inp),
          h('button', { class: 'btn primary', type: 'submit', text: 'Open link' }));
        form.addEventListener('submit', (e) => {
          e.preventDefault();
          const m = inp.value.trim().match(/#\/v\/([^/\s]+)\/([A-Za-z0-9_-]+)/);
          if (!m) { toast('That does not look like a Pramaan link', true); return; }
          location.hash = '#/v/' + m[1] + '/' + m[2];
        });
        body.appendChild(form);
      }
    }

    async function run(payload, btn) {
      busy(btn, true, 'Checking the chain…');
      try {
        const r = await api('POST', '/api/public/verify', payload);
        drawReport(report, r);
        if (window.innerWidth < 900) report.scrollIntoView({ behavior: 'smooth' });
      } catch (e) {
        showError(report, e);
      } finally {
        busy(btn, false);
      }
    }

    // ---- examples
    const examples = h('div', { class: 'chips', 'aria-label': 'Examples' });
    try {
      if (!S.samples) S.samples = (await api('GET', '/api/public/samples')).samples;
      S.samples.forEach((smp) => examples.appendChild(h('button', { class: 'chip', type: 'button', title: smp.hint,
        onclick: () => { method = 'FILE'; [...tabsEl.children].forEach((b, i) => b.setAttribute('aria-selected', i === 0 ? 'true' : 'false')); loadedFile = smp.file; drawBody(); run({ method: 'FILE', file: smp.file }, null); } },
        h('b', { text: smp.label }), ' · ', smp.hint)));
    } catch (e) { /* no examples */ }

    const left = h('section', { class: 'panel framed' },
      h('span', { class: 'eyebrow', text: 'Verify a certificate' }),
      h('h1', { text: 'Is this certificate real?' }),
      h('p', { class: 'muted', text: 'Every genuine certificate was sealed on the Pramaan blockchain by its institution the day it was issued. We check the details against that seal – change even one letter and it no longer matches.' }),
      examples.childElementCount ? h('div', { class: 'examples' }, h('h3', { text: 'Try an example' }), examples) : null,
      tabsEl, body);

    view.appendChild(h('div', { class: 'grid-2' }, left, report));
    drawBody();

    // a forged file handed over from the Tamper lab
    if (S.pendingFile) { loadedFile = S.pendingFile; S.pendingFile = null; drawBody(); run({ method: 'FILE', file: loadedFile }, null); }
    // opened from a QR code or share link: #/v/<id>/<token>
    if (args.length >= 2 && location.hash.startsWith('#/v/')) {
      method = 'LINK';
      [...tabsEl.children].forEach((b, i) => b.setAttribute('aria-selected', M[i][0] === 'LINK' ? 'true' : 'false'));
      drawBody();
      run({ method: 'LINK', certId: args[0], token: args[1] }, null);
    }
  }

  function drawReport(el, r) {
    clear(el);
    const v = VERDICT[r.verdict] || VERDICT.BAD_INPUT;
    el.appendChild(h('div', { class: 'stamp-wrap' },
      h('div', { class: 'stamp ' + v.cls, role: 'img', 'aria-label': 'Verdict: ' + v.word }, v.word, h('small', { text: v.sub })),
      h('div', { class: 'verdict-text' }, h('h2', { text: r.headline }), h('p', { text: r.explanation }))));

    if (r.record) {
      el.appendChild(h('p', { class: 'small muted', text: 'The institution\'s own record (shown because the holder shared this link). Compare it with the paper in front of you:' }));
      el.appendChild(renderCertificate({ certId: r.certId, issuerId: r.issuer && r.issuer.id, issuerName: r.issuer && r.issuer.name, values: r.record, revoked: r.verdict === 'REVOKED' }, { small: false }));
    }

    if (r.checks && r.checks.length) {
      el.appendChild(h('h3', { text: 'Security checks' }));
      const ul = h('ul', { class: 'checks' });
      const marks = { pass: '✓', fail: '✕', warn: '!', info: 'i' };
      r.checks.forEach((c) => ul.appendChild(h('li', { class: c.status },
        h('span', { class: 'mark', 'aria-hidden': 'true', text: marks[c.status] || '·' }),
        h('div', null, h('b', null, c.label, h('span', { class: 'sr', text: ' – ' + c.status })), h('span', { class: 'd', text: c.detail })))));
      el.appendChild(ul);
    }

    if (r.fields && r.fields.some((f) => f.status !== 'unchecked' || f.value)) {
      const showValues = r.method !== 'LINK';
      const t = h('table', { class: 'fieldcheck' }, h('thead', null, h('tr', null, h('th', { text: 'Detail' }), showValues ? h('th', { text: 'Given' }) : null, h('th', { text: 'Fingerprint' }))));
      const tb = h('tbody');
      r.fields.forEach((f) => {
        const tag = f.status === 'match' ? h('span', { class: 'tag ok', text: 'matches' })
          : f.status === 'changed' ? h('span', { class: 'tag bad', text: 'changed' }) : h('span', { class: 'tag grey', text: 'not checked' });
        tb.appendChild(h('tr', { class: f.status }, h('td', { text: f.label }), showValues ? h('td', { text: f.value || '' }) : null, h('td', null, tag)));
      });
      t.appendChild(tb);
      el.appendChild(h('h3', { text: 'Detail by detail' }));
      el.appendChild(h('div', { class: 'table-wrap' }, t));
    }

    if (r.chain) {
      const c = r.chain;
      el.appendChild(h('h3', { text: 'Proof on the chain' }));
      el.appendChild(h('div', { class: 'proof' },
        h('div', null, h('b', { text: 'Block ' }), h('a', { href: '#/ledger/' + c.block, text: '#' + c.block }), ' · sealed ', fmtTime(c.issuedAt)),
        h('div', null, h('b', { text: 'Block hash ' }), hashEl(c.blockHash)),
        h('div', null, h('b', { text: 'Record ' }), hashEl(c.tx, 24)),
        h('div', null, h('b', { text: 'Found on ' }), c.nodesHolding.length + ' of 3 nodes (' + c.nodesHolding.join(', ') + ') · ', h('b', { text: String(c.confirmations) }), ' later blocks on top'),
        r.issuer ? h('div', null, h('b', { text: 'Signed by ' }), r.issuer.name + ' · key ' + r.issuer.keyFingerprint) : null));
    }
    el.appendChild(h('p', { class: 'tiny faint', text: 'Checked ' + fmtTime(r.checkedAt) + (r.agreeing && r.agreeing.length ? ' · nodes in agreement: ' + r.agreeing.join(', ') : '') }));
  }

  // ================================================================== LEDGER (public)

  async function viewLedger(args) {
    const d = await api('GET', '/api/public/ledger?limit=15');
    const info = S.info || (S.info = await api('GET', '/api/public/info'));
    view.appendChild(pageHead('Public ledger', 'The blockchain',
      'Every block holds signed records: institutions approved, certificates issued (as fingerprints only – no names or marks) and revocations. Anyone can inspect it.'));
    view.appendChild(h('div', { class: 'stat-row' },
      stat(d.height + 1, 'blocks', 'violet'), stat(info.certificates, 'certificates sealed', 'good'),
      stat(info.revoked, 'revoked', 'bad'), stat(d.agreeing.length + ' / 3', 'nodes agree', d.consensus ? 'good' : 'bad'),
      stat('0'.repeat(d.difficulty) + '…', 'proof of work', '')));
    view.appendChild(nodesStrip(d.nodes));
    if (!d.consensus) view.appendChild(h('div', { class: 'notice bad', text: d.consensusMessage + '. Showing Node A\'s copy.' }));

    const list = h('ol', { class: 'chain', reversed: true, 'aria-label': 'Blocks, newest first' });
    const more = h('button', { class: 'btn', type: 'button', text: 'Show older blocks' });
    let oldest = d.height + 1;
    function addBlocks(blocks) {
      blocks.forEach((b) => {
        const kinds = h('div', { class: 'kinds' });
        Object.entries(b.kinds).forEach(([k, n]) => kinds.appendChild(h('span', { class: 'tag ' + kindClass(k), text: n + ' × ' + kindLabel(k) })));
        const li = h('li', { class: 'block', tabindex: '0', role: 'button', 'aria-label': 'Block ' + b.index },
          h('div', { class: 'band' }, 'BLOCK', h('span', { class: 'num', text: String(b.index) })),
          h('div', { class: 'info' }, kinds, hashEl(b.hash), h('div', { class: 'tiny faint' }, 'previous ', hashEl(b.previousHash, 18))),
          h('div', { class: 'meta' }, fmtTime(b.time), h('br'), 'nonce ' + b.nonce.toLocaleString(), h('br'), 'sealed by Node ' + b.sealedBy));
        const open = () => { location.hash = '#/ledger/' + b.index; };
        li.addEventListener('click', open);
        li.addEventListener('keydown', (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); open(); } });
        list.appendChild(li);
        oldest = Math.min(oldest, b.index);
      });
      more.hidden = oldest <= 0;
    }
    addBlocks(d.blocks);
    more.addEventListener('click', async () => {
      busy(more, true);
      try { addBlocks((await api('GET', '/api/public/ledger?limit=15&before=' + oldest)).blocks); } catch (e) { toast(e.message, true); }
      busy(more, false);
    });
    view.appendChild(list);
    view.appendChild(h('div', { class: 'row' }, more));
    if (args[0] !== undefined && /^\d+$/.test(args[0])) openBlock(Number(args[0]));
  }

  function kindLabel(k) {
    return { GENESIS: 'network created', REGISTER_ISSUER: 'institution approved', SUSPEND_ISSUER: 'institution suspended',
      ISSUE_CERT: 'certificate', REVOKE_CERT: 'revocation' }[k] || k;
  }
  function kindClass(k) {
    return { ISSUE_CERT: 'ok', REVOKE_CERT: 'bad', SUSPEND_ISSUER: 'warn', REGISTER_ISSUER: 'violet', GENESIS: 'violet' }[k] || 'grey';
  }
  function stat(n, label, cls) {
    return h('div', { class: 'stat ' + (cls || '') }, h('span', { class: 'n', text: String(n) }), h('span', { class: 'l', text: label }));
  }
  function nodesStrip(nodes) {
    const wrap = h('div', { class: 'nodes', 'aria-label': 'Validator nodes' });
    nodes.forEach((n) => wrap.appendChild(h('div', { class: 'node ' + n.state },
      h('span', { class: 'lampx', 'aria-hidden': 'true' }),
      h('b', null, n.name + ' ', h('span', { class: 'tag ' + (n.state === 'AGREES' ? 'ok' : n.state === 'BROKEN' ? 'bad' : 'warn'), text: n.state === 'AGREES' ? 'agrees' : n.state === 'BROKEN' ? 'broken' : 'out-voted' })),
      h('span', { class: 'k', text: n.keeper }),
      h('span', { class: 'd', text: n.state === 'AGREES' ? (n.height + 1) + ' blocks · tip ' + n.tipHash.slice(0, 14) + '…' : n.detail }))));
    return wrap;
  }

  async function openBlock(n) {
    let b;
    try { b = await api('GET', '/api/public/block/' + n); } catch (e) { toast(e.message, true); return; }
    const back = h('div', { class: 'drawer-back' });
    const close = () => { back.remove(); dr.remove(); if (location.hash.startsWith('#/ledger/')) history.replaceState(null, '', '#/ledger'); };
    back.addEventListener('click', close);
    const closeBtn = h('button', { class: 'btn small', type: 'button', text: 'Close', onclick: close });
    const dr = h('aside', { class: 'drawer', role: 'dialog', 'aria-modal': 'true', 'aria-label': 'Block ' + b.index },
      h('div', { class: 'row' }, h('span', { class: 'eyebrow', text: 'Block ' + b.index + ' of ' + b.height }), h('span', { class: 'spacer' }), closeBtn),
      h('h2', { text: 'Block #' + b.index }),
      h('div', { class: 'proof' },
        h('div', null, h('b', { text: 'Hash ' }), hashEl(b.hash)),
        h('div', null, h('b', { text: 'Previous ' }), hashEl(b.previousHash)),
        h('div', null, h('b', { text: 'Merkle root ' }), hashEl(b.merkleRoot)),
        h('div', null, h('b', { text: 'Nonce ' }), b.nonce.toLocaleString(), ' · difficulty ' + b.difficulty + ' · sealed by Node ' + b.sealedBy + ' in ' + b.mineMillis + ' ms'),
        h('div', null, h('b', { text: 'Time ' }), fmtTime(b.time))),
      h('p', { class: 'small muted' }, 'hash = SHA-256( ', h('code', { text: b.header }), ' )'),
      h('h3', { text: b.transactions.length + ' record' + (b.transactions.length === 1 ? '' : 's') }));
    b.transactions.forEach((t) => {
      const rows = [];
      if (t.certId) rows.push(h('div', null, h('b', { text: 'Certificate ' }), h('span', { class: 'mono', text: t.certId })));
      if (t.name) rows.push(h('div', null, h('b', { text: 'Institution ' }), t.name + ' (' + t.issuerId + ')'));
      if (t.network) rows.push(h('div', null, h('b', { text: 'Network ' }), t.network));
      if (t.keyFingerprint) rows.push(h('div', null, h('b', { text: 'Public key ' }), t.keyFingerprint));
      if (t.root) rows.push(h('div', null, h('b', { text: 'Fingerprint (Merkle root of 7 salted fields) ' }), hashEl(t.root)));
      if (t.reason) rows.push(h('div', null, h('b', { text: 'Reason ' }), t.reason));
      rows.push(h('div', null, h('b', { text: 'Signature ' }), h('span', { class: 'mono', text: t.signature })));
      dr.appendChild(h('div', { class: 'tx' }, h('div', { class: 'head' }, h('span', { class: 'tag ' + kindClass(t.type), text: t.label }), h('span', { class: 'small', text: 'signed by ' + t.signer }), h('span', { class: 'spacer' }), h('span', { class: 'tiny faint', text: fmtTime(t.time) })),
        h('div', { class: 'proof' }, rows)));
    });
    document.body.appendChild(back);
    document.body.appendChild(dr);
    closeBtn.focus();
    dr.addEventListener('keydown', (e) => { if (e.key === 'Escape') close(); });
  }

  // ================================================================== SIGN IN

  async function viewLogin() {
    if (S.user) { location.hash = homeFor(S.user); return; }
    const u = h('input', { type: 'text', id: 'u', autocomplete: 'username', autocapitalize: 'none', spellcheck: 'false' });
    const p = h('input', { type: 'password', id: 'p', autocomplete: 'current-password' });
    const err = h('div');
    const btn = h('button', { class: 'btn primary', type: 'submit', text: 'Sign in' });
    const form = h('form', { class: 'panel pass-card', novalidate: true },
      h('div', { class: 'strip', 'aria-hidden': 'true' }),
      h('h2', { text: 'Sign in' }),
      h('div', { class: 'field' }, h('label', { for: 'u', text: 'Username' }), u),
      h('div', { class: 'field' }, h('label', { for: 'p', text: 'Password' }), p),
      btn, err,
      h('p', { class: 'small muted', text: 'Demo accounts (all made up) – click one to fill it in:' }));
    const demo = h('div', { class: 'demo' });
    [['meera', 'issuer123', 'KVIT staff', 'Issue & revoke for Koel Valley IT'], ['priya', 'student123', 'Student', 'Holds a B.Tech + a skill certificate'],
      ['admin', 'admin123', 'Registrar', 'Nodes, Tamper lab, Forgery test'], ['farhan', 'issuer123', 'SUBU staff', 'Subarnarekha University']].forEach(([un, pw, r, d]) =>
      demo.appendChild(h('button', { type: 'button', onclick: () => { u.value = un; p.value = pw; btn.focus(); } }, h('span', { class: 'r', text: r }), h('span', null, h('b', { text: un }), ' · ' + d), h('span', { class: 'mono tiny', text: pw }))));
    form.appendChild(demo);
    form.addEventListener('submit', async (e) => {
      e.preventDefault();
      clear(err);
      busy(btn, true, 'Checking…');
      try {
        const r = await api('POST', '/api/login', { username: u.value, password: p.value });
        S.user = r.user;
        const next = S.after || homeFor(S.user);
        S.after = null;
        location.hash = next;
        toast('Welcome, ' + S.user.displayName.split(' (')[0]);
      } catch (ex) { showError(err, ex); } finally { busy(btn, false); }
    });
    const seal = h('div', { class: 'seal-big' });
    seal.appendChild(rosette(160, 1));
    view.appendChild(h('div', { class: 'login' },
      h('div', null, h('span', { class: 'eyebrow', text: 'Pramaan network' }), h('h1', { text: 'Fake certificates stop here.' }),
        h('p', { class: 'muted', text: 'Institutions seal each certificate on a three-node blockchain the moment they issue it. Students get a digital copy and a QR code. Employers check it in seconds – no login, no phone calls, no waiting.' }),
        h('p', { class: 'muted', text: 'Signing in is only for institutions, students and the registrar. Anyone can verify.' }),
        h('a', { class: 'btn', href: '#/verify', text: 'Verify a certificate →' })),
      form));
    u.focus();
  }

  // ================================================================== STUDENT

  async function viewMy() {
    const d = await api('GET', '/api/my/certificates');
    view.appendChild(pageHead('My certificates', 'Your certificates',
      'Download the digital copy, print it, or make a private link for an employer. Every link can be switched off at any time.'));
    if (!d.certificates.length) { view.appendChild(h('div', { class: 'panel', text: 'No certificates yet. When your institution issues one to your account it appears here.' })); return; }
    const cards = h('div', { class: 'cards' });
    d.certificates.forEach((c) => cards.appendChild(h('a', { class: 'cert-card', href: '#/cert/' + c.certId },
      renderCertificate(c, { small: true }),
      h('div', { class: 'cap' }, h('span', null, h('b', { text: c.issuerName })), c.revoked ? h('span', { class: 'tag bad', text: 'revoked' }) : h('span', { class: 'tag ok', text: 'on chain' })),
      h('div', { class: 'tiny faint', text: c.certId + ' · checked ' + c.timesChecked + ' time' + (c.timesChecked === 1 ? '' : 's') }))));
    view.appendChild(cards);
  }

  async function viewCert(args) {
    const c = await api('GET', '/api/certificates/' + encodeURIComponent(args[0] || ''));
    const isHolder = S.user.role === 'STUDENT';
    view.appendChild(h('div', { class: 'no-print' }, pageHead(isHolder ? 'My certificate' : 'Certificate register', c.values.holder,
      c.values.programme,
      h('a', { class: 'btn', href: isHolder ? '#/my' : '#/certs', text: '← Back' }),
      h('button', { class: 'btn', type: 'button', text: 'Print', onclick: () => window.print() }),
      h('a', { class: 'btn primary', href: '/api/certificates/' + c.certId + '/file', text: 'Download file' }))));
    if (c.revoked) view.appendChild(h('div', { class: 'notice bad no-print', text: 'Revoked on ' + fmtDate(c.revokedAt) + ': ' + c.revokeReason }));
    const left = h('div', { class: 'stack' }, h('div', { class: 'print-area cert-wrap' }, renderCertificate(c)));
    const side = h('div', { class: 'stack no-print' });
    side.appendChild(h('section', { class: 'panel framed' },
      h('h3', { text: 'On the blockchain' }),
      h('div', { class: 'proof' },
        h('div', null, h('b', { text: 'ID ' }), h('span', { class: 'mono', text: c.certId })),
        h('div', null, h('b', { text: 'Block ' }), h('a', { href: '#/ledger/' + c.block, text: '#' + c.block }), ' · ' + c.confirmations + ' later blocks'),
        h('div', null, h('b', { text: 'Fingerprint ' }), hashEl(c.root, 32)),
        h('div', null, h('b', { text: 'Signed by ' }), c.issuerId + ' · key ' + c.keyFingerprint),
        h('div', null, h('b', { text: 'Issued ' }), fmtTime(c.issuedAt) + (c.issuedBy ? ' by ' + c.issuedBy : '')),
        h('div', null, h('b', { text: 'Checked ' }), c.timesChecked + ' time' + (c.timesChecked === 1 ? '' : 's') + ' by verifiers')),
      h('p', { class: 'small muted', text: 'The QR code opens the verify page with this certificate\'s secret token. Without the token, the ID alone shows nobody your details.' }),
      h('div', { class: 'copybox' }, h('input', { type: 'text', readonly: true, value: c.verifyUrl, 'aria-label': 'Verify link' }), h('button', { class: 'btn small', type: 'button', text: 'Copy', onclick: () => copy(c.verifyUrl) }))));
    if (!isHolder && !c.revoked) {
      side.appendChild(h('section', { class: 'panel' }, h('h3', { text: 'Revoke' }),
        h('p', { class: 'small muted', text: 'Cancels the certificate for ever. The revocation and its reason are written on the chain and every verifier will see it.' }),
        h('button', { class: 'btn danger', type: 'button', text: 'Revoke this certificate…', onclick: () => revokeDialog(c) })));
    }
    if (isHolder) side.appendChild(shareLinksPanel(c));
    view.appendChild(h('div', { class: 'split' }, left, h('div', { class: 'sticky' }, side)));
  }

  function shareLinksPanel(c) {
    const box = h('section', { class: 'panel' }, h('h3', { text: 'Share with an employer' }),
      h('p', { class: 'small muted', text: 'Make a private link that shows your certificate to whoever opens it. It stops working after the days you choose, or when you switch it off.' }));
    const label = h('input', { type: 'text', id: 'sl-label', placeholder: 'e.g. Job application – Tata Steel', maxlength: '60' });
    const days = h('select', { id: 'sl-days' }, [7, 30, 90, 365].map((n) => h('option', { value: String(n), text: n + ' days', selected: n === 30 })));
    const out = h('div');
    const btn = h('button', { class: 'btn primary', type: 'button', text: 'Make link' });
    btn.addEventListener('click', async () => {
      busy(btn, true);
      try {
        const r = await api('POST', '/api/my/certificates/' + c.certId + '/links', { label: label.value, days: Number(days.value) });
        clear(out).appendChild(h('div', { class: 'notice' }, h('p', null, h('b', { text: 'Copy this link now' }), ' – for your safety it is shown only once.'),
          h('div', { class: 'copybox' }, h('input', { type: 'text', readonly: true, value: r.url, 'aria-label': 'New link' }), h('button', { class: 'btn small', type: 'button', text: 'Copy', onclick: () => copy(r.url) }))));
        c.shareLinks.push({ id: r.id, label: r.label, createdAt: Date.now(), expiresAt: r.expiresAt, views: 0, active: true });
        drawLinks();
      } catch (e) { showError(out, e); } finally { busy(btn, false); }
    });
    box.appendChild(h('div', { class: 'field-row' }, h('div', { class: 'field' }, h('label', { for: 'sl-label', text: 'Who is it for?' }), label), h('div', { class: 'field' }, h('label', { for: 'sl-days', text: 'Works for' }), days)));
    box.appendChild(btn);
    box.appendChild(out);
    const list = h('div');
    box.appendChild(list);
    function drawLinks() {
      clear(list);
      if (!c.shareLinks.length) return;
      const t = h('table', { class: 'list' }, h('thead', null, h('tr', null, h('th', { text: 'Link' }), h('th', { text: 'Opened' }), h('th', { text: 'Until' }), h('th'))));
      const tb = h('tbody');
      c.shareLinks.slice().reverse().forEach((l) => tb.appendChild(h('tr', null,
        h('td', { text: l.label || '(no name)' }), h('td', { text: l.views + '×' }), h('td', { text: fmtDate(l.expiresAt) }),
        h('td', null, l.active ? h('button', { class: 'btn small danger', type: 'button', text: 'Switch off', onclick: async () => {
          try { await api('POST', '/api/my/certificates/' + c.certId + '/links/' + l.id + '/off', {}); l.active = false; drawLinks(); toast('Link switched off'); } catch (e) { toast(e.message, true); }
        } }) : h('span', { class: 'tag grey', text: 'off' })))));
      t.appendChild(tb);
      list.appendChild(h('div', { class: 'table-wrap' }, t));
    }
    drawLinks();
    return box;
  }

  function revokeDialog(c) {
    const reason = h('textarea', { id: 'rv-reason', maxlength: '200', placeholder: 'e.g. Printed with wrong CGPA – replaced by a corrected certificate' });
    reason.style.minHeight = '90px';
    const err = h('div');
    const ok = h('button', { class: 'btn danger', type: 'submit', text: 'Revoke on the chain' });
    const dlg = h('dialog', null, h('form', { method: 'dialog' },
      h('h2', { text: 'Revoke ' + c.certId + '?' }),
      h('p', { class: 'muted', text: c.values.holder + ' – ' + c.values.programme }),
      h('div', { class: 'field' }, h('label', { for: 'rv-reason', text: 'Reason (public, written on the chain)' }), reason),
      h('div', { class: 'row' }, ok, h('button', { class: 'btn', type: 'button', text: 'Cancel', onclick: () => dlg.close() })), err));
    dlg.querySelector('form').addEventListener('submit', async (e) => {
      e.preventDefault();
      busy(ok, true, 'Sealing…');
      try {
        await api('POST', '/api/issuer/revoke', { certId: c.certId, reason: reason.value });
        dlg.close();
        toast('Revoked. The revocation is on the chain.');
        render();
      } catch (ex) { showError(err, ex); } finally { busy(ok, false); }
    });
    dlg.addEventListener('close', () => dlg.remove());
    document.body.appendChild(dlg);
    dlg.showModal();
  }

  // ================================================================== INSTITUTION STAFF

  async function viewDash() {
    const d = await api('GET', '/api/issuer/overview');
    S.overview = d;
    const i = d.issuer;
    view.appendChild(pageHead(i.id + ' · issuing desk', i.name,
      'Approved by the registrar in block ' + i.approvedInBlock + '. Your signing key fingerprint: ' + i.keyFingerprint + '.',
      h('a', { class: 'btn primary', href: '#/issue', text: 'Issue a certificate' }), h('a', { class: 'btn', href: '#/batch', text: 'Batch issue' })));
    if (i.suspended) view.appendChild(h('div', { class: 'notice bad', text: 'Your institution was suspended by the registrar: ' + i.suspendReason + '. You cannot issue new certificates. Certificates issued before the suspension stay valid.' }));
    const c30 = d.checks30;
    const bad = c30.TAMPERED + c30.NOT_FOUND;
    view.appendChild(h('div', { class: 'stat-row' },
      stat(d.issuedCount, 'issued', 'good'), stat(d.revokedCount, 'revoked', 'bad'),
      stat(Object.values(c30).reduce((a, b) => a + b, 0), 'checks · 30 days', 'violet'), stat(c30.TAMPERED, 'forgeries caught', 'bad')));
    view.appendChild(h('div', { class: 'grid-2' }, activityPanel(d, 'Checks of your certificates'), recentChecksPanel(d.recentChecks)));
  }

  function activityPanel(d, title) {
    const max = Math.max(1, ...d.perDay);
    const bars = h('div', { class: 'bars', role: 'img', 'aria-label': title + ': ' + d.perDay.reduce((a, b) => a + b, 0) + ' checks in 30 days' });
    d.perDay.forEach((n, i) => {
      const b = h('div', { class: 'bar', title: n + ' checks, ' + d.badPerDay[i] + ' not genuine' });
      b.style.height = (n / max * 100) + '%';
      const red = h('i');
      red.style.height = (n ? d.badPerDay[i] / n * 100 : 0) + '%';
      b.appendChild(red);
      bars.appendChild(b);
    });
    const c30 = d.checks30;
    return h('section', { class: 'panel framed' }, h('h3', { text: title }), bars,
      h('div', { class: 'bars-x' }, h('span', { text: '30 days ago' }), h('span', { text: 'today' })),
      h('div', { class: 'legend' }, h('span', { text: 'all checks' }), h('span', { class: 'r', text: 'not genuine' })),
      h('div', { class: 'chips', style: null },
        ...Object.entries(c30).filter(([, n]) => n > 0).map(([k, n]) => h('span', { class: 'tag ' + (k === 'GENUINE' ? 'ok' : k === 'REVOKED' ? 'warn' : 'bad'), text: n + ' ' + (VERDICT[k] ? VERDICT[k].word.toLowerCase() : k) }))));
  }

  function recentChecksPanel(list) {
    const t = h('table', { class: 'list' }, h('thead', null, h('tr', null, h('th', { text: 'When' }), h('th', { text: 'Certificate' }), h('th', { text: 'How' }), h('th', { text: 'Result' }))));
    const tb = h('tbody');
    list.slice(0, 14).forEach((v) => tb.appendChild(h('tr', null,
      h('td', { class: 'nowrap', text: ago(v.time) }), h('td', { class: 'id', text: v.certId || '–' }),
      h('td', { text: { LINK: 'QR / link', FILE: 'file', TYPED: 'typed' }[v.method] || v.method }),
      h('td', null, h('span', { class: 'tag ' + (v.verdict === 'GENUINE' ? 'ok' : v.verdict === 'REVOKED' ? 'warn' : 'bad'), text: (VERDICT[v.verdict] || {}).word || v.verdict }),
        v.changedFields ? h('div', { class: 'tiny faint', text: 'changed: ' + v.changedFields }) : null))));
    t.appendChild(tb);
    return h('section', { class: 'panel' }, h('h3', { text: 'Latest checks' }), list.length ? h('div', { class: 'table-wrap' }, t) : h('p', { class: 'muted', text: 'Nobody has checked a certificate yet.' }));
  }

  async function viewIssue() {
    const d = S.overview || (S.overview = await api('GET', '/api/issuer/overview'));
    const i = d.issuer;
    view.appendChild(pageHead('Issue', 'Issue a certificate',
      'Fill in the details. When you press Sign & seal, your institution\'s private key signs the fingerprint, and the network seals it into a new block with proof of work.'));
    if (i.suspended) { view.appendChild(h('div', { class: 'notice bad', text: 'Your institution is suspended, so it cannot issue.' })); return; }
    const vals = { issuer: i.name, issueDate: d.today };
    const inputs = {};
    const preview = h('div');
    const drawPreview = () => clear(preview).appendChild(renderCertificate({ certId: 'PRM-····-····-·', issuerId: i.id, issuerName: i.name, issuerCity: i.city, values: vals }));
    const form = h('form', { class: 'panel framed', novalidate: true });
    const ph = { holder: 'e.g. Priya Oraon', rollNo: 'e.g. KVIT/CSE/22/031', programme: 'e.g. Bachelor of Technology - Computer Science and Engineering', result: 'e.g. CGPA 8.46 / 10 - First Division', session: 'e.g. 2022-2026' };
    d.fields.forEach((f) => {
      if (f.name === 'issuer') return;
      const inp = h('input', { type: f.name === 'issueDate' ? 'date' : 'text', id: 'is-' + f.name, maxlength: String(f.max), placeholder: ph[f.name] || '', value: vals[f.name] || '', max: f.name === 'issueDate' ? d.today : null, required: true });
      inp.addEventListener('input', () => { vals[f.name] = inp.value; drawPreview(); });
      inputs[f.name] = inp;
      form.appendChild(h('div', { class: 'field' }, h('label', { for: 'is-' + f.name, text: f.label }), inp));
    });
    const who = h('select', { id: 'is-user' }, h('option', { value: '', text: '— not linked to an account —' }), d.students.map((st) => h('option', { value: st.username, text: st.displayName + ' (' + st.username + ')' })));
    form.appendChild(h('div', { class: 'field' }, h('label', { for: 'is-user', text: 'Student account (optional)' }), who, h('div', { class: 'hint', text: 'Linked certificates appear in the student\'s own Pramaan page.' })));
    const btn = h('button', { class: 'btn primary', type: 'submit', text: 'Sign & seal on the chain' });
    const out = h('div');
    form.appendChild(btn);
    form.appendChild(out);
    form.addEventListener('submit', async (e) => {
      e.preventDefault();
      clear(out);
      const fields = {};
      Object.keys(inputs).forEach((k) => { fields[k] = inputs[k].value; });
      busy(btn, true, 'Sealing…');
      const anim = miningAnimation();
      out.appendChild(anim.el);
      try {
        const r = await api('POST', '/api/issuer/issue', { fields, username: who.value });
        await anim.finish(r.block);
        S.overview = null;
        clear(out).appendChild(h('div', { class: 'notice' }, h('b', { text: 'Sealed in block #' + r.block + '. ' }), 'Certificate ', h('span', { class: 'mono', text: r.certId }), ' is now verifiable by anyone.'));
        clear(preview).appendChild(renderCertificate(r));
        preview.appendChild(h('div', { class: 'row', style: null }, h('a', { class: 'btn primary', href: '#/cert/' + r.certId, text: 'Open, print or download' }),
          h('button', { class: 'btn', type: 'button', text: 'Issue another', onclick: () => render() })));
      } catch (ex) { anim.stop(); showError(out, ex); } finally { busy(btn, false); }
    });
    drawPreview();
    view.appendChild(h('div', { class: 'split' }, form, h('div', { class: 'sticky' }, h('p', { class: 'small muted', text: 'Live preview' }), preview)));
    inputs.holder.focus();
  }

  /** A short "proof of work" show while the server seals the block (the real work happens on the server). */
  function miningAnimation() {
    const el = h('div', { class: 'mining', 'aria-hidden': 'true' });
    let n = 0, stopped = false;
    const hex = '0123456789abcdef';
    const tick = () => {
      if (stopped) return;
      let s = '';
      for (let i = 0; i < 40; i++) s += hex[(Math.random() * 16) | 0];
      el.insertBefore(h('div', { text: 'nonce ' + String(n += 1 + ((Math.random() * 900) | 0)).padStart(7, ' ') + '  ' + s + '…' }), el.firstChild);
      while (el.childElementCount > 7) el.lastChild.remove();
      setTimeout(tick, 45);
    };
    tick();
    return {
      el,
      stop() { stopped = true; },
      finish(block) {
        return new Promise((res) => setTimeout(() => {
          stopped = true;
          el.insertBefore(h('div', { class: 'hit' }, '✓ block #' + block + ' sealed  ', h('span', { class: 'z', text: '0000' }), '… meets the difficulty'), el.firstChild);
          setTimeout(res, 650);
        }, 500));
      }
    };
  }

  async function viewBatch() {
    view.appendChild(pageHead('Batch issue', 'Issue a whole batch',
      'Paste or upload a CSV file. Every row becomes a certificate, and all of them are sealed together in ONE block. If any row has a mistake, nothing is issued and every mistake is listed.'));
    const ta = h('textarea', { id: 'csv', spellcheck: 'false', placeholder: 'holder,rollNo,programme,result,session,issueDate,username' });
    ta.style.minHeight = '260px';
    const fileIn = h('input', { type: 'file', accept: '.csv,text/csv', id: 'csvFile', class: 'sr' });
    fileIn.addEventListener('change', () => {
      const f = fileIn.files[0];
      if (!f) return;
      if (f.size > 1500000) { toast('File is too big', true); return; }
      const r = new FileReader();
      r.onload = () => { ta.value = r.result; };
      r.readAsText(f);
    });
    const today = (S.overview && S.overview.today) || new Date().toISOString().slice(0, 10);
    const template = 'holder,rollNo,programme,result,session,issueDate,username\n'
      + '"Ankita Bara","KVIT/CSE/23/011","Bachelor of Technology - Computer Science and Engineering","CGPA 8.12 / 10 - First Division","2023-2027","' + today + '",\n'
      + '"Rohan Ekka","KVIT/ME/23/044","Bachelor of Technology - Mechanical Engineering","CGPA 7.05 / 10 - First Division","2023-2027","' + today + '",\n';
    const out = h('div');
    const btn = h('button', { class: 'btn primary', type: 'button', text: 'Sign & seal all' });
    btn.addEventListener('click', async () => {
      clear(out);
      busy(btn, true, 'Sealing…');
      const anim = miningAnimation();
      out.appendChild(anim.el);
      try {
        const r = await api('POST', '/api/issuer/batch', { csv: ta.value });
        if (r.errors.length) {
          anim.stop();
          clear(out).appendChild(h('div', { class: 'notice bad' }, h('b', { text: 'Nothing was issued. Please fix these rows:' }), h('ul', null, r.errors.map((x) => h('li', { text: x })))));
          return;
        }
        await anim.finish(r.block);
        S.overview = null;
        const t = h('table', { class: 'list' }, h('thead', null, h('tr', null, h('th', { text: 'ID' }), h('th', { text: 'Name' }), h('th', { text: 'Roll no.' }), h('th', { text: 'Account' }))));
        const tb = h('tbody');
        r.issued.forEach((c) => tb.appendChild(h('tr', { class: 'clickable', onclick: () => { location.hash = '#/cert/' + c.certId; } },
          h('td', { class: 'id', text: c.certId }), h('td', { text: c.holder }), h('td', { text: c.rollNo }), h('td', { text: c.holderUsername || '–' }))));
        t.appendChild(tb);
        clear(out).appendChild(h('div', { class: 'notice' }, h('b', { text: r.issued.length + ' certificates sealed together in block #' + r.block }), ' (proof of work took ' + r.mineMillis + ' ms).'));
        out.appendChild(h('div', { class: 'table-wrap' }, t));
      } catch (e) { anim.stop(); showError(out, e); } finally { busy(btn, false); }
    });
    view.appendChild(h('div', { class: 'grid-2' },
      h('section', { class: 'panel framed' },
        h('div', { class: 'field' }, h('label', { for: 'csv', text: 'CSV text' }), ta),
        h('div', { class: 'row' }, btn, h('label', { for: 'csvFile', class: 'btn', text: 'Upload CSV…' }), fileIn,
          h('button', { class: 'btn', type: 'button', text: 'Use example', onclick: () => { ta.value = template; } }),
          h('button', { class: 'btn-link', type: 'button', text: 'download template', onclick: () => download('pramaan-batch-template.csv', template, 'text/csv') })),
        out),
      h('section', { class: 'panel' }, h('h3', { text: 'Columns' }),
        h('table', { class: 'list' }, h('tbody', null,
          [['holder', 'Full name, as printed'], ['rollNo', 'Roll / enrolment number (no repeats in one file)'], ['programme', 'Degree, diploma or course name'],
            ['result', 'Grade, CGPA or division'], ['session', 'e.g. 2022-2026'], ['issueDate', 'yyyy-mm-dd, optional (today if empty)'], ['username', 'Student account, optional']]
            .map(([k, d]) => h('tr', null, h('td', { class: 'mono', text: k }), h('td', { text: d }))))),
        h('p', { class: 'small muted', text: 'Up to 500 rows per batch. The institution name is added automatically from your account, so nobody can issue in another institution\'s name.' }))));
  }

  async function viewCerts() {
    const d = await api('GET', '/api/issuer/overview');
    S.overview = d;
    view.appendChild(pageHead('Register', 'Issued certificates', d.issuedCount + ' issued by ' + d.issuer.name + ', newest first.'));
    const q = h('input', { type: 'text', id: 'q', placeholder: 'Search name, roll no., ID or programme…' });
    const only = h('select', { id: 'only', 'aria-label': 'Show' }, h('option', { value: 'all', text: 'All' }), h('option', { value: 'live', text: 'Valid only' }), h('option', { value: 'rev', text: 'Revoked only' }));
    const tb = h('tbody');
    const count = h('span', { class: 'small muted' });
    const draw = () => {
      clear(tb);
      const term = q.value.trim().toLowerCase();
      let n = 0;
      d.certificates.forEach((c) => {
        if (only.value === 'live' && c.revoked) return;
        if (only.value === 'rev' && !c.revoked) return;
        if (term && ![c.holder, c.rollNo, c.certId, c.programme].some((x) => (x || '').toLowerCase().includes(term))) return;
        n++;
        if (n > 300) return;
        tb.appendChild(h('tr', { class: 'clickable' + (c.revoked ? ' revoked' : ''), tabindex: '0', onclick: () => { location.hash = '#/cert/' + c.certId; },
          onkeydown: (e) => { if (e.key === 'Enter') location.hash = '#/cert/' + c.certId; } },
          h('td', { class: 'id', text: c.certId }), h('td', null, h('b', { text: c.holder }), h('div', { class: 'tiny faint', text: c.rollNo })),
          h('td', { text: c.programme }), h('td', { text: c.result }), h('td', { class: 'nowrap', text: fmtIso(c.issueDate) }),
          h('td', null, c.revoked ? h('span', { class: 'tag bad', title: c.revokeReason, text: 'revoked' }) : h('span', { class: 'tag ok', text: 'block ' + c.block }))));
      });
      count.textContent = n + ' shown';
    };
    q.addEventListener('input', draw);
    only.addEventListener('change', draw);
    view.appendChild(h('section', { class: 'panel' },
      h('div', { class: 'row' }, h('div', { class: 'spacer' }, h('label', { for: 'q', class: 'sr', text: 'Search' }), q), only, count),
      h('div', { class: 'table-wrap' }, h('table', { class: 'list' },
        h('thead', null, h('tr', null, ['ID', 'Name', 'Programme', 'Result', 'Issued', 'Status'].map((x) => h('th', { text: x })))), tb))));
    draw();
  }

  // ================================================================== REGISTRAR

  async function viewNodes() {
    let d = await api('GET', '/api/admin/network');
    view.appendChild(pageHead('Registrar · nodes', 'Nodes & Tamper lab',
      'Three nodes each keep their own copy of the chain. Here you play a dishonest insider who controls ONE node\'s files – and watch the network catch you.'));
    const nodesBox = h('div');
    const msg = h('div');
    view.appendChild(nodesBox);
    view.appendChild(msg);

    function drawNodes() {
      clear(nodesBox);
      nodesBox.appendChild(h('div', { class: 'notice' + (d.consensus ? '' : ' bad') }, h('b', { text: d.consensus ? 'Consensus: ' : 'No consensus: ' }), d.consensusMessage + (d.consensus ? ' – answers come from nodes ' + d.agreeing.join(', ') + '.' : ' – verification is refused until nodes are repaired.')));
      const cols = h('div', { class: 'lab-nodes' });
      d.nodes.forEach((n) => {
        const chain = h('div', { class: 'minichain', role: 'list', 'aria-label': n.name + ' blocks' });
        n.blocks.forEach((b) => chain.appendChild(h('span', { class: 'mb ' + b.state, role: 'listitem', title: 'Block ' + b.index + ' · ' + b.hash.slice(0, 16) + '… · ' + b.state, text: String(b.index) })));
        cols.appendChild(h('div', { class: 'lab-node ' + n.state },
          h('div', { class: 'row' }, h('b', { text: n.name }), h('span', { class: 'tag ' + (n.state === 'AGREES' ? 'ok' : n.state === 'BROKEN' ? 'bad' : 'warn'), text: n.state === 'AGREES' ? 'agrees' : n.state === 'BROKEN' ? 'broken' : 'out-voted' })),
          h('div', { class: 'tiny faint', text: n.keeper }), chain,
          h('p', { class: 'small', text: n.detail }),
          n.state !== 'AGREES' ? h('button', { class: 'btn small', type: 'button', text: 'Repair from majority', onclick: (e) => act(e.target, '/api/admin/repair', { node: n.id }) }) : null));
      });
      nodesBox.appendChild(cols);
      nodesBox.appendChild(h('div', { class: 'legend' }, h('span', { class: 'g', text: 'block OK' }), h('span', { class: 'r', text: 'first bad block and the blocks after it' }), h('span', { class: 'a', text: 'different history (out-voted)' })));
    }

    async function act(btn, path, body) {
      busy(btn, true);
      try {
        d = await api('POST', path, body);
        drawNodes();
        clear(msg).appendChild(h('div', { class: 'lab-msg ok', text: d.message }));
      } catch (e) { toast(e.message, true); } finally { busy(btn, false); }
    }
    drawNodes();

    // ---- attack form
    const attacks = [
      ['EDIT', 'Edit a record', 'Change a certificate\'s fingerprint inside a block on one node, as if editing the database file. Nothing else.'],
      ['RESEAL', 'Edit + redo the proof of work', 'Change it, then re-mine that block and every block after it so all hashes and links look right again.'],
      ['DELETE_REVOCATION', 'Delete a revocation + re-seal', 'Remove a "revoked" record (to make a cancelled certificate look valid) and re-mine. Every remaining record is genuinely signed.']
    ];
    const atkBox = h('div', { class: 'attacks', role: 'radiogroup', 'aria-label': 'Attack' });
    attacks.forEach(([k, t, x], i) => atkBox.appendChild(h('label', { class: 'attack' }, h('input', { type: 'radio', name: 'atk', value: k, checked: i === 0 }), h('div', null, h('b', { text: t }), h('span', { text: x })))));
    const node = h('select', { id: 'lab-node' }, ['A', 'B', 'C'].map((x) => h('option', { value: x, text: 'Node ' + x, selected: x === 'B' })));
    const cert = h('select', { id: 'lab-cert' });
    const field = h('select', { id: 'lab-field' }, d.fields.map((f) => h('option', { value: f.name, text: f.label, selected: f.name === 'result' })));
    const value = h('input', { type: 'text', id: 'lab-value', value: 'CGPA 9.80 / 10 - First Division with Distinction' });
    const forgeRow = h('div', { class: 'field-row' }, h('div', { class: 'field' }, h('label', { for: 'lab-field', text: 'Detail to forge' }), field), h('div', { class: 'field' }, h('label', { for: 'lab-value', text: 'Forged value' }), value));
    const fillCerts = () => {
      const k = atkBox.querySelector('input:checked').value;
      const list = k === 'DELETE_REVOCATION' ? d.revokedCertificates : d.certificates;
      clear(cert);
      list.forEach((c) => cert.appendChild(h('option', { value: c.certId, text: c.certId + ' – ' + c.holder + ' (' + c.issuerId + ')' })));
      forgeRow.hidden = k === 'DELETE_REVOCATION';
      syncValue();
    };
    const syncValue = () => {
      const all = d.certificates.concat(d.revokedCertificates);
      const c = all.find((x) => x.certId === cert.value);
      if (c && field.value !== 'result') value.value = (c.values[field.value] || '') + (field.value === 'holder' ? ' Kumar' : ' (edited)');
      else if (c) value.value = 'CGPA 9.80 / 10 - First Division with Distinction';
    };
    atkBox.addEventListener('change', fillCerts);
    field.addEventListener('change', syncValue);
    cert.addEventListener('change', syncValue);
    const run = h('button', { class: 'btn danger', type: 'button', text: 'Run the attack' });
    const after = h('div');
    run.addEventListener('click', async () => {
      busy(run, true, 'Attacking…');
      try {
        d = await api('POST', '/api/admin/tamper', { attack: atkBox.querySelector('input:checked').value, node: node.value, certId: cert.value, field: field.value, value: value.value });
        drawNodes();
        clear(msg).appendChild(h('div', { class: 'lab-msg', text: d.message }));
        clear(after);
        if (d.forgedFile) {
          const f = d.forgedFile;
          after.appendChild(h('div', { class: 'row' },
            h('button', { class: 'btn primary', type: 'button', text: 'Now try the forged certificate on the Verify page →', onclick: () => { S.pendingFile = f; location.hash = '#/verify'; } })));
        }
        msg.scrollIntoView({ behavior: 'smooth', block: 'center' });
      } catch (e) { toast(e.message, true); } finally { busy(run, false); }
    });
    view.appendChild(h('div', { class: 'grid-2' },
      h('section', { class: 'panel framed' }, h('h2', { text: 'Attack one node' }), atkBox,
        h('div', { class: 'field-row' }, h('div', { class: 'field' }, h('label', { for: 'lab-node', text: 'Node to attack' }), node), h('div', { class: 'field' }, h('label', { for: 'lab-cert', text: 'Certificate' }), cert)),
        forgeRow, run, after),
      h('section', { class: 'panel' }, h('h2', { text: 'What to look for' }),
        h('ol', { class: 'small' },
          h('li', null, h('b', { text: 'Edit: ' }), 'the node turns red – its block\'s Merkle root no longer matches the records inside.'),
          h('li', null, h('b', { text: 'Edit + redo work: ' }), 'hashes and links are fine again, but the institution\'s digital signature does not match – red again. Only the institution\'s private key could fix that.'),
          h('li', null, h('b', { text: 'Delete revocation: ' }), 'the node is valid on its own, but it disagrees with the other two, so it is out-voted (amber).'),
          h('li', null, h('b', { text: 'Do the same to two nodes: ' }), 'the attacker becomes the majority – a "51% attack". That is why real networks need many independent nodes.')),
        h('p', { class: 'small muted', text: 'You can also open data/nodes/node-b.json in Notepad, change any character, and press "Re-read files".' }),
        h('div', { class: 'row' },
          h('button', { class: 'btn', type: 'button', text: 'Re-read files from disk', onclick: (e) => act(e.target, '/api/admin/reload', {}) }),
          h('button', { class: 'btn', type: 'button', text: 'Undo all attacks', onclick: (e) => act(e.target, '/api/admin/undo', {}) })))));
    fillCerts();
  }

  async function viewInst() {
    const d = await api('GET', '/api/admin/institutions');
    view.appendChild(pageHead('Registrar', 'Approved institutions',
      'Only institutions approved here can write certificates. Approving one creates its key pair and records the public key on the chain.'));
    const tb = h('tbody');
    d.institutions.forEach((i) => tb.appendChild(h('tr', { class: i.suspended ? 'revoked' : '' },
      h('td', { class: 'mono', text: i.id }), h('td', null, h('b', { text: i.name }), h('div', { class: 'tiny faint', text: i.city })),
      h('td', { class: 'mono small', text: i.keyFingerprint }), h('td', { text: '#' + i.approvedInBlock }),
      h('td', { text: String(i.issued) }), h('td', { text: String(i.staff) }),
      h('td', null, i.suspended ? h('span', { class: 'tag bad', title: i.suspendReason, text: 'suspended' })
        : h('button', { class: 'btn small danger', type: 'button', text: 'Suspend…', onclick: () => suspendDialog(i) })))));
    const code = h('input', { type: 'text', id: 'in-code', maxlength: '8', placeholder: 'e.g. BITM' });
    const name = h('input', { type: 'text', id: 'in-name', maxlength: '120', placeholder: 'Full name' });
    const city = h('input', { type: 'text', id: 'in-city', maxlength: '60' });
    const out = h('div');
    const btn = h('button', { class: 'btn primary', type: 'submit', text: 'Approve & record on chain' });
    const form = h('form', { class: 'panel framed', novalidate: true }, h('h2', { text: 'Approve an institution' }),
      h('div', { class: 'field-row' }, h('div', { class: 'field' }, h('label', { for: 'in-code', text: 'Short code' }), code), h('div', { class: 'field' }, h('label', { for: 'in-city', text: 'City' }), city)),
      h('div', { class: 'field' }, h('label', { for: 'in-name', text: 'Name' }), name), btn, out,
      h('p', { class: 'small muted', text: 'Then add a staff account for them on the People page.' }));
    form.addEventListener('submit', async (e) => {
      e.preventDefault();
      busy(btn, true, 'Sealing…');
      try {
        const r = await api('POST', '/api/admin/institutions', { code: code.value, name: name.value, city: city.value });
        toast(r.id + ' approved. Key ' + r.keyFingerprint);
        render();
      } catch (ex) { showError(out, ex); } finally { busy(btn, false); }
    });
    view.appendChild(h('div', { class: 'grid-2' },
      h('section', { class: 'panel' }, h('div', { class: 'table-wrap' }, h('table', { class: 'list' },
        h('thead', null, h('tr', null, ['Code', 'Institution', 'Key', 'Approved', 'Issued', 'Staff', ''].map((x) => h('th', { text: x })))), tb))),
      form));
  }

  function suspendDialog(i) {
    const reason = h('input', { type: 'text', id: 'sp-reason', maxlength: '200', placeholder: 'e.g. Recognition withdrawn by the university council' });
    const err = h('div');
    const ok = h('button', { class: 'btn danger', type: 'submit', text: 'Suspend on the chain' });
    const dlg = h('dialog', null, h('form', null, h('h2', { text: 'Suspend ' + i.id + '?' }),
      h('p', { class: 'muted', text: i.name + ' will not be able to issue new certificates. Certificates it issued before stay valid, with a warning.' }),
      h('div', { class: 'field' }, h('label', { for: 'sp-reason', text: 'Reason (public)' }), reason),
      h('div', { class: 'row' }, ok, h('button', { class: 'btn', type: 'button', text: 'Cancel', onclick: () => dlg.close() })), err));
    dlg.querySelector('form').addEventListener('submit', async (e) => {
      e.preventDefault();
      busy(ok, true);
      try { await api('POST', '/api/admin/institutions/' + i.id + '/suspend', { reason: reason.value }); dlg.close(); toast(i.id + ' suspended'); render(); }
      catch (ex) { showError(err, ex); } finally { busy(ok, false); }
    });
    dlg.addEventListener('close', () => dlg.remove());
    document.body.appendChild(dlg);
    dlg.showModal();
  }

  async function viewBench() {
    view.appendChild(pageHead('Registrar · testing', 'Forgery test bench',
      'Runs hundreds of realistic forgeries against the live network – edited marks, swapped names, made-up IDs, typos, revoked certificates, fake institutions – and counts how many are caught. Nothing is written to the chain.'));
    const out = h('div');
    const btn = h('button', { class: 'btn primary', type: 'button', text: 'Run the test' });
    btn.addEventListener('click', async () => {
      busy(btn, true, 'Forging…');
      try {
        const r = await api('POST', '/api/admin/bench', { seed: Date.now() % 100000 });
        clear(out);
        out.appendChild(h('div', { class: 'stat-row' },
          stat(r.caught + ' / ' + r.forgeries, 'forgeries caught', r.caught === r.forgeries ? 'good' : 'bad'),
          stat(r.falseAlarms, 'false alarms (of ' + r.genuineTried + ' genuine)', r.falseAlarms ? 'bad' : 'good'),
          stat(r.total, 'checks run', 'violet'), stat(r.millis + ' ms', 'time', '')));
        const tb = h('tbody');
        r.rows.forEach((x) => {
          const pct = x.tried ? Math.round(x.correct / x.tried * 100) : 0;
          const meter = h('div', { class: 'meter' + (pct < 100 ? ' bad' : '') }, h('i'));
          meter.firstChild.style.width = pct + '%';
          tb.appendChild(h('tr', null, h('td', null, h('b', { text: x.label }), h('div', { class: 'tiny faint', text: x.how })),
            h('td', null, h('span', { class: 'tag ' + (x.forgery ? 'bad' : 'ok'), text: x.expected.toLowerCase().replace('_', ' ') })),
            h('td', { class: 'nowrap' }, x.correct + ' / ' + x.tried, meter),
            h('td', { class: 'small muted', text: x.example })));
        });
        out.appendChild(h('section', { class: 'panel' }, h('div', { class: 'table-wrap' }, h('table', { class: 'list' },
          h('thead', null, h('tr', null, ['Attempt', 'Should be', 'Right', 'Example'].map((t) => h('th', { text: t })))), tb))));
        out.appendChild(h('p', { class: 'small muted', text: 'Honest note: these forgeries are made by the same person who wrote the checks. A 100% score shows the maths works as designed – not that nothing can ever go wrong. The biggest real risks are outside the chain: a stolen institution key, a careless staff member typing wrong marks, or a fake website that looks like Pramaan.' }));
      } catch (e) { showError(out, e); } finally { busy(btn, false); }
    });
    view.appendChild(h('div', { class: 'row' }, btn));
    view.appendChild(h('div', { class: 'stack' }, h('div'), out));
  }

  async function viewActivity() {
    const d = await api('GET', '/api/admin/activity');
    view.appendChild(pageHead('Registrar', 'Verification activity', 'Every check made on the Verify page, across all institutions.'));
    view.appendChild(h('div', { class: 'grid-2' }, activityPanel(d, 'Checks in the last 30 days'), recentChecksPanel(d.recentChecks)));
  }

  async function viewPeople() {
    const d = await api('GET', '/api/admin/users');
    const inst = await api('GET', '/api/admin/institutions');
    view.appendChild(pageHead('Registrar', 'People', d.users.length + ' accounts · ' + d.activeSessions + ' signed in now'));
    const tb = h('tbody');
    d.users.forEach((u) => tb.appendChild(h('tr', null, h('td', { class: 'mono', text: u.username }), h('td', { text: u.displayName }),
      h('td', null, h('span', { class: 'tag violet', text: u.role === 'ISSUER' ? 'staff · ' + u.issuerId : u.role.toLowerCase() })),
      h('td', { text: u.role === 'STUDENT' ? String(u.certificates) : '' }), h('td', null, u.locked ? h('span', { class: 'tag bad', text: 'locked' }) : null))));
    const un = h('input', { type: 'text', id: 'pu-u', autocomplete: 'off' });
    const dn = h('input', { type: 'text', id: 'pu-d' });
    const role = h('select', { id: 'pu-r' }, h('option', { value: 'STUDENT', text: 'Student' }), h('option', { value: 'ISSUER', text: 'Institution staff' }), h('option', { value: 'REGISTRAR', text: 'Registrar' }));
    const iss = h('select', { id: 'pu-i' }, inst.institutions.filter((i) => !i.suspended).map((i) => h('option', { value: i.id, text: i.id + ' – ' + i.name })));
    const issField = h('div', { class: 'field' }, h('label', { for: 'pu-i', text: 'Institution' }), iss);
    issField.hidden = true;
    role.addEventListener('change', () => { issField.hidden = role.value !== 'ISSUER'; });
    const pw = h('input', { type: 'password', id: 'pu-p', autocomplete: 'new-password' });
    const out = h('div');
    const btn = h('button', { class: 'btn primary', type: 'submit', text: 'Create account' });
    const form = h('form', { class: 'panel framed', novalidate: true }, h('h2', { text: 'New account' }),
      h('div', { class: 'field-row' }, h('div', { class: 'field' }, h('label', { for: 'pu-u', text: 'Username' }), un), h('div', { class: 'field' }, h('label', { for: 'pu-d', text: 'Display name' }), dn)),
      h('div', { class: 'field' }, h('label', { for: 'pu-r', text: 'Role' }), role), issField,
      h('div', { class: 'field' }, h('label', { for: 'pu-p', text: 'Password (8+ characters)' }), pw), btn, out);
    form.addEventListener('submit', async (e) => {
      e.preventDefault();
      busy(btn, true);
      try {
        await api('POST', '/api/admin/users', { username: un.value, displayName: dn.value, role: role.value, issuerId: iss.value, password: pw.value });
        toast('Account created');
        render();
      } catch (ex) { showError(out, ex); } finally { busy(btn, false); }
    });
    view.appendChild(h('div', { class: 'grid-2' }, h('section', { class: 'panel' }, h('div', { class: 'table-wrap' }, h('table', { class: 'list' },
      h('thead', null, h('tr', null, ['Username', 'Name', 'Role', 'Certificates', ''].map((x) => h('th', { text: x })))), tb))), form));
  }

  // ------------------------------------------------------------------ start

  function decorate() {
    const seal = document.getElementById('brandSeal');
    seal.appendChild(rosette(46, 0));
    document.getElementById('threadText').textContent = 'PRAMAAN ✦ GENUINE ✦ SEALED ON CHAIN ✦ '.repeat(30);
    document.getElementById('footMicro').textContent = 'PRAMAAN·CERTIFICATE·NETWORK·'.repeat(40);
    paintLamp();
  }

  async function start() {
    decorate();
    try {
      const me = await api('GET', '/api/me');
      S.user = me.user;
    } catch (e) { S.user = null; }
    if (!location.hash) location.hash = S.user ? homeFor(S.user) : '#/verify';
    else render();
  }
  start();
})();
