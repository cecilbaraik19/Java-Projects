/* Kaushal – Employee Skill Management. Plain JavaScript, no framework.
   Pages are built with h() (document.createElement) – never innerHTML – so text from people can never run as code.
   Every page is a function that fetches JSON from the Java backend and draws the result. */
(function () {
  'use strict';

  // ================================================================== state and helpers

  const S = { user: null, meta: null, skill: {}, cat: {}, role: {}, person: {}, unread: 0, queue: 0, colorMode: 'group' };
  const $app = document.getElementById('app');
  const $modal = document.getElementById('modal-root');
  const $toast = document.getElementById('toast');
  const NAMES = ['None', 'Aware', 'Working', 'Practitioner', 'Advanced', 'Expert'];

  /** h('div', {class: 'x', onclick: f}, 'text', child, [more]) – builds DOM safely. */
  function h(tag, attrs, ...kids) {
    const el = document.createElement(tag);
    if (attrs) for (const [k, v] of Object.entries(attrs)) {
      if (v === null || v === undefined || v === false) continue;
      if (k === 'class') el.className = v;
      else if (k === 'style') for (const [sk, sv] of Object.entries(v)) el.style.setProperty(sk, sv);
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
    const opt = { method, headers: { 'X-Kaushal': '1' }, credentials: 'same-origin' };
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
    toastTimer = setTimeout(() => { $toast.hidden = true; }, bad ? 5200 : 3200);
  }
  /** Runs an action; shows its error as a red toast (or in `errEl`). Returns the result or undefined. */
  async function act(fn, okMsg, errEl) {
    try {
      const r = await fn();
      if (okMsg) toast(okMsg);
      if (errEl) errEl.textContent = '';
      return r;
    } catch (e) {
      if (e.status === 401) return undefined;
      if (errEl) errEl.textContent = e.message; else toast(e.message, true);
      throw e;
    }
  }
  const quiet = (p) => p.catch(() => undefined);

  function setMeta(meta) {
    S.meta = meta;
    S.skill = {}; S.cat = {}; S.role = {}; S.person = {};
    for (const c of meta.categories) S.cat[c.id] = c;
    for (const s of meta.skills) S.skill[s.id] = s;
    for (const r of meta.roles) S.role[r.id] = r;
    for (const p of meta.people) S.person[p.id] = p;
  }
  async function reloadMeta() { setMeta(await get('/api/meta')); }

  const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
  function fmtDate(v) {
    if (!v) return '–';
    const d = typeof v === 'number' ? new Date(v) : new Date(v + (String(v).length === 10 ? 'T00:00:00' : ''));
    return d.getDate() + ' ' + MONTHS[d.getMonth()] + ' ' + d.getFullYear();
  }
  function fmtMonth(ym) { if (!ym) return '–'; const [y, m] = ym.split('-'); return MONTHS[+m - 1] + ' ' + y; }
  function ago(ms) {
    const s = (Date.now() - ms) / 1000;
    if (s < 60) return 'just now';
    if (s < 3600) return Math.floor(s / 60) + ' min ago';
    if (s < 86400) return Math.floor(s / 3600) + ' h ago';
    const d = Math.floor(s / 86400);
    return d === 1 ? 'yesterday' : d < 60 ? d + ' days ago' : fmtDate(ms);
  }
  function hours(hr) { if (hr === null || hr === undefined || isNaN(hr)) return '–'; return hr < 48 ? Math.round(hr) + ' h' : Math.round(hr / 24) + ' days'; }
  const thisMonth = () => (S.meta ? S.meta.today : new Date().toISOString()).slice(0, 7);
  const first = (name) => (name || '').split(' ')[0];
  const plural = (n, w) => n + ' ' + w + (n === 1 ? '' : 's');
  const sortBy = (arr, f) => arr.slice().sort((a, b) => { const x = f(a), y = f(b); return x < y ? -1 : x > y ? 1 : 0; });

  // ================================================================== small components

  function group(skillId) { const s = S.skill[skillId]; return s ? s.group : 'method'; }

  /** Level as electron shells: ●●●○○. lost = levels lost by decay (striped), want = a claimed level still waiting (dashed). */
  function shells(level, opts) {
    opts = opts || {};
    const lost = opts.lost || 0, want = opts.want || 0;
    const el = h('span', { class: 'shells' + (opts.lg ? ' lg' : ''), role: 'img', 'aria-label': 'level ' + level + ' of 5 (' + NAMES[level] + ')' });
    for (let i = 1; i <= 5; i++) {
      let c = '';
      if (i <= level) c = 'on';
      else if (i <= level + lost) c = 'lost';
      else if (i <= want) c = 'want';
      el.appendChild(h('i', { class: c }));
    }
    return el;
  }

  /** An element tile for a skill. o: {level, lost, want, flag, flagClass, cert, meta, size, state, onclick, href, title} */
  function tile(skillId, o) {
    o = o || {};
    const s = S.skill[skillId] || { number: 0, symbol: '?', name: 'Unknown', group: 'method' };
    const cls = ['el', s.group, o.size || '', o.state || ''].join(' ').trim();
    const tag = o.href ? 'a' : o.onclick ? 'button' : 'div';
    const t = h(tag, {
      class: cls, href: o.href, onclick: o.onclick, type: tag === 'button' ? 'button' : null,
      title: o.title || (s.name + (o.level ? ' · level ' + o.level + ' ' + NAMES[o.level] : '')),
      style: o.heat !== undefined ? { '--heat': String(o.heat) } : null
    },
      h('span', { class: 'n' }, h('span', null, s.number), o.size === 'mini' ? null : h('span', null, o.topRight || '')),
      h('span', { class: 's' }, s.symbol),
      o.size === 'mini' ? null : h('span', { class: 'nm' }, s.name),
      o.size === 'mini' ? null : (o.level !== undefined || o.want ? shells(o.level || 0, { lost: o.lost, want: o.want }) : null),
      o.meta && o.size !== 'mini' ? h('span', { class: 'm' }, o.meta) : null,
      o.flag ? h('span', { class: 'flag ' + (o.flagClass || '') }, o.flag) : null,
      o.cert ? h('span', { class: 'cert', title: 'certified' }, '◆') : null
    );
    return t;
  }

  function avatar(pid, big) {
    const p = S.person[pid];
    return h('span', { class: 'avatar' + (big ? ' big' : ''), 'aria-hidden': 'true' }, p ? p.initials : '?');
  }
  function personLink(pid) {
    const p = S.person[pid];
    if (!p) return h('span', null, 'someone');
    return h('a', { href: '#/people/' + pid }, p.name);
  }
  function who(pid, sub) {
    const p = S.person[pid] || {};
    return h('div', { class: 'row tight' }, avatar(pid),
      h('div', { class: 'grow' }, h('div', null, personLink(pid)), h('div', { class: 'muted small' }, sub !== undefined ? sub : (p.title || ''))));
  }
  function roleName(id) { return S.role[id] ? S.role[id].name : '–'; }
  function skillName(id) { return S.skill[id] ? S.skill[id].name : id; }

  function gauge(pct, label, small) {
    const r = 46, c = 2 * Math.PI * r;
    const color = pct >= (S.meta ? S.meta.readyAt : 80) ? 'var(--ok)' : pct >= 50 ? 'var(--warn)' : 'var(--bad)';
    return h('div', { class: 'gauge' + (small ? ' sm' : ''), role: 'img', 'aria-label': pct + ' percent ' + (label || '') },
      (() => {
        const svgEl = sv('svg', { viewBox: '0 0 100 100' },
          sv('circle', { cx: 50, cy: 50, r, fill: 'none', stroke: 'var(--line)', 'stroke-width': 9 }),
          sv('circle', { cx: 50, cy: 50, r, fill: 'none', stroke: color, 'stroke-width': 9, 'stroke-linecap': 'round',
            'stroke-dasharray': (c * pct / 100) + ' ' + c }));
        return svgEl;
      })(),
      h('div', { class: 'v' }, h('div', null, h('b', null, pct + '%'), small ? null : h('small', null, label || 'ready'))));
  }
  function bar(pct, cls) { return h('div', { class: 'bar ' + (cls || '') }, h('i', { style: { width: Math.max(0, Math.min(100, pct)) + '%' } })); }
  function chip(text, cls, title) { return h('span', { class: 'chip ' + (cls || ''), title }, text); }
  function statusChip(st) {
    if (st === 'VERIFIED') return chip('verified', 'ok');
    if (st === 'CLAIMED') return chip('waiting for a check', 'warn');
    if (st === 'RETURNED') return chip('sent back', 'bad');
    return null;
  }
  function certChip(c) {
    if (c.status === 'EXPIRED') return chip('expired ' + fmtDate(c.expires), 'bad');
    if (c.status === 'EXPIRING') return chip(c.daysLeft + ' days left', 'warn');
    return chip(c.expires ? 'valid to ' + fmtDate(c.expires) : 'no expiry', 'ok');
  }
  function reqChips(reqs, levels) {
    return h('div', { class: 'reqs' }, reqs.map(q => {
      const have = levels ? (levels[q.skillId] || 0) : null;
      const miss = levels && have < q.level;
      return h('span', { class: 'req' + (q.must ? ' must' : '') + (miss ? ' miss' : ''), title: (q.must ? 'must have · ' : 'nice to have · ') + 'weight ' + q.weight },
        (S.skill[q.skillId] || {}).symbol || '?', ' ', q.level, q.must ? '!' : '');
    }));
  }
  function empty(text) { return h('div', { class: 'empty' }, text); }
  function card(title, ...kids) { return h('section', { class: 'card' }, title ? h('div', { class: 'card-head' }, typeof title === 'string' ? h('h2', null, title) : title) : null, kids); }
  function pageHead(kicker, title, sub, ...actions) {
    return h('div', { class: 'page-head' }, h('div', { class: 'grow' }, kicker ? h('div', { class: 'kicker' }, kicker) : null, h('h1', null, title), sub ? h('p', null, sub) : null), actions);
  }
  function loading() { return h('div', { class: 'loading' }, 'Weighing the elements…'); }

  // ------------------------------------------------------------------ modal and side panel

  function modal(title, body, opts) {
    opts = opts || {};
    const close = () => { clear($modal); document.removeEventListener('keydown', esc); if (opts.onclose) opts.onclose(); };
    const esc = (e) => { if (e.key === 'Escape') close(); };
    document.addEventListener('keydown', esc);
    const box = h('div', { class: 'modal' + (opts.wide ? ' wide' : ''), role: 'dialog', 'aria-modal': 'true', 'aria-label': title },
      h('button', { class: 'btn ghost small close', onclick: close, 'aria-label': 'Close' }, '✕'),
      h('h2', null, title), body);
    clear($modal).appendChild(h('div', { class: 'modal-back', onclick: (e) => { if (e.target === e.currentTarget) close(); } }, box));
    const f = box.querySelector('input, select, textarea, button:not(.close)');
    if (f) setTimeout(() => f.focus(), 30);
    return close;
  }
  function panel(body) {
    const close = () => { clear($modal); document.removeEventListener('keydown', esc); };
    const esc = (e) => { if (e.key === 'Escape') close(); };
    document.addEventListener('keydown', esc);
    const p = h('aside', { class: 'panel', role: 'dialog', 'aria-modal': 'true' }, h('button', { class: 'btn ghost small close', onclick: close, 'aria-label': 'Close' }, '✕'), body);
    clear($modal).appendChild(h('div', null, h('div', { class: 'panel-back', onclick: close }), p));
    setTimeout(() => p.focus && p.querySelector('button') && p.querySelector('button').focus(), 30);
    return close;
  }
  const closeAll = () => clear($modal);

  function levelRadios(name, chosen, max, min) {
    const wrap = h('div', { class: 'levels', role: 'radiogroup' });
    for (let i = (min || 1); i <= (max || 5); i++) {
      wrap.appendChild(h('label', null, h('input', { type: 'radio', name, value: String(i), checked: i === chosen }),
        h('span', { class: 'row tight' }, shells(i), h('b', null, i + ' · ' + NAMES[i])), h('small', null, S.meta.meaning[i])));
    }
    return wrap;
  }
  const radioValue = (root, name) => { const r = root.querySelector('input[name="' + name + '"]:checked'); return r ? +r.value : 0; };

  /** A searchable grid of skill tiles; calls onpick(skillId). */
  function skillPicker(selected, onpick, filter) {
    let sel = selected;
    const q = h('input', { type: 'search', placeholder: 'Type to filter: java, k8s, figma…', 'aria-label': 'Filter skills' });
    const grid = h('div', { class: 'picker' });
    const draw = () => {
      clear(grid);
      const k = q.value.trim().toLowerCase();
      for (const s of sortBy(S.meta.skills.filter(s => s.active && (!filter || filter(s))), s => s.number)) {
        if (k && !(s.name.toLowerCase().includes(k) || s.symbol.toLowerCase().includes(k) || s.synonyms.some(x => x.toLowerCase().includes(k)))) continue;
        const t = tile(s.id, { onclick: () => { sel = s.id; onpick(s.id); draw(); }, state: s.id === sel ? 'sel' : '' });
        grid.appendChild(t);
      }
      if (!grid.firstChild) grid.appendChild(empty('No skill matches.'));
    };
    q.addEventListener('input', draw);
    draw();
    return h('div', { class: 'stack' }, q, grid);
  }

  // ================================================================== sign in

  function showLogin(msg) {
    clear($modal);
    const u = h('input', { type: 'text', id: 'u', autocomplete: 'username', required: true });
    const p = h('input', { type: 'password', id: 'p', autocomplete: 'current-password', required: true });
    const err = h('div', { class: 'error', role: 'alert' }, msg || '');
    const btn = h('button', { class: 'btn primary', type: 'submit' }, 'Sign in');
    const form = h('form', {
      onsubmit: async (e) => {
        e.preventDefault();
        btn.disabled = true;
        try {
          const r = await act(() => post('/api/login', { username: u.value, password: p.value }), null, err);
          S.user = r.user;
          setMeta(r.meta);
          if (!location.hash || location.hash === '#/login') location.hash = '#/';
          route();
        } catch (e2) { /* shown */ } finally { btn.disabled = false; }
      }
    },
      h('div', null, h('div', { class: 'kicker' }, 'Sign in'), h('h2', null, 'Open your lab bench')),
      h('label', { class: 'f', for: 'u' }, 'Username', u),
      h('label', { class: 'f', for: 'p' }, 'Password', p),
      err, btn,
      h('div', { class: 'demo card flat' }, h('b', null, 'Demo people (all made up)'), h('div', { class: 'muted' }, 'Click to fill in:'),
        [['asha', 'member123', 'Asha · backend engineer'], ['vikram', 'member123', 'Vikram · senior, assessor'],
          ['ravi', 'manager123', 'Ravi · manager'], ['priya', 'manager123', 'Priya · manager'], ['meera', 'admin123', 'Meera · HR admin']]
          .map(([n, pw, label]) => h('button', { type: 'button', class: 'btn small', onclick: () => { u.value = n; p.value = pw; btn.focus(); } }, label))));
    const sample = ['Jv', 'K8s', 'Ux', 'Cm', 'As', 'Pg', 'Re', 'Tf', 'Ml', 'Sc'];
    const groups = ['lang', 'cloud', 'design', 'soft', 'sec', 'data', 'web', 'cloud', 'data', 'method'];
    const names = ['Java', 'Kubernetes', 'UX research', 'Communication', 'App security', 'PostgreSQL', 'React', 'Terraform', 'Machine learning', 'Agile & Scrum'];
    const art = h('div', { class: 'tiles' }, sample.map((s, i) => h('div', { class: 'el ' + groups[i] },
      h('span', { class: 'n' }, h('span', null, [1, 18, 37, 41, 33, 28, 8, 21, 31, 39][i]), h('span', null)),
      h('span', { class: 's' }, s), h('span', { class: 'nm' }, names[i]), shells([4, 3, 2, 5, 3, 4, 3, 2, 1, 4][i]))));
    clear($app).appendChild(h('div', { class: 'login' },
      h('div', { class: 'login-art' },
        h('div', { class: 'kicker' }, 'Kaushal · कौशल · "skill"'),
        h('h1', null, 'The periodic table of your team\'s skills'),
        h('p', { class: 'muted', style: { 'max-width': '52ch' } },
          'Every skill is an element. Claim your level, get it checked by someone else, watch skills you stop using fade, see the gaps to the role you want – and staff projects with the best possible team.'),
        art,
        h('p', { class: 'small muted' }, '●●●○○ = level 3 of 5 · dashed tile = waiting for a check · striped tile = fading (t½)')),
      h('div', { class: 'login-box' }, form)));
    setTimeout(() => u.focus(), 30);
  }

  // ================================================================== the shell (rail + top bar)

  const NAV = [
    ['#/', 'Bn', 'Bench', null],
    ['#/me', 'Me', 'My skills', null],
    ['#/table', 'Pt', 'Periodic table', null],
    ['#/search', 'Fp', 'Find people', null],
    ['#/growth', 'Gr', 'Growth', null],
    ['#/projects', 'Pj', 'Projects', null],
    ['#/verify', 'Vf', 'Verify', 'queue'],
    ['#/inbox', 'In', 'Inbox', 'unread'],
  ];
  const NAV_TEAM = [['#/team', 'Tm', 'Team matrix'], ['#/reports', 'Rp', 'Reports']];

  function isDark() {
    const t = document.documentElement.getAttribute('data-theme');
    return t ? t === 'dark' : window.matchMedia('(prefers-color-scheme: dark)').matches;
  }
  function toggleTheme() {
    const next = isDark() ? 'light' : 'dark';
    document.documentElement.setAttribute('data-theme', next);
    try { localStorage.setItem('kaushal-theme', next); } catch (e) { /* ignore */ }
    route();
  }

  function shell(current, content) {
    const link = ([href, sym, label, count]) => h('a', { href, 'aria-current': current === href ? 'page' : null },
      h('span', { class: 'sym', 'aria-hidden': 'true' }, sym), label,
      count && S[count] > 0 ? h('span', { class: 'count', 'aria-label': S[count] + ' new' }, S[count]) : null);
    const u = S.user;
    const rail = h('nav', { class: 'rail', 'aria-label': 'Main' },
      h('a', { class: 'brand', href: '#/' },
        h('span', { class: 'el lang' }, h('span', { class: 'n' }, h('span', null, '108'), h('span', null)), h('span', { class: 's' }, 'Ks'), h('span', { class: 'nm' }, '')),
        h('span', null, h('b', null, 'Kaushal'), h('small', null, 'Tarang Technologies'))),
      h('div', { class: 'nav' }, NAV.map(link)),
      u.manager ? [h('div', { class: 'nav-label' }, 'Team'), h('div', { class: 'nav' }, NAV_TEAM.map(link))] : null,
      u.admin ? [h('div', { class: 'nav-label' }, 'HR'), h('div', { class: 'nav' }, link(['#/hr', 'Hr', 'HR desk']))] : null,
      h('div', { class: 'rail-foot' },
        h('div', { class: 'who' }, avatar(u.id), h('div', null, h('div', null, h('b', null, u.name)), h('div', { class: 'tiny', style: { color: 'var(--rail-muted)' } }, u.title || u.role))),
        h('button', { class: 'rail-btn', onclick: toggleTheme }, isDark() ? '☀ Lab paper (light)' : '✦ Spectrum (dark)'),
        h('button', { class: 'rail-btn', onclick: changePassword }, 'Change password'),
        h('button', { class: 'rail-btn', onclick: async () => { await quiet(post('/api/logout')); S.user = null; location.hash = '#/login'; showLogin(); } }, 'Sign out')));
    const q = h('input', { type: 'search', placeholder: 'Find people:  java >= 3 and aws', 'aria-label': 'Find people by skills' });
    const top = h('header', { class: 'top' },
      h('form', { role: 'search', onsubmit: (e) => { e.preventDefault(); if (q.value.trim()) location.hash = '#/search?q=' + encodeURIComponent(q.value.trim()); } }, q),
      h('span', { class: 'grow' }),
      h('span', { class: 'muted small mono' }, fmtDate(S.meta.today)));
    clear($app).appendChild(h('div', { class: 'shell' }, rail, h('main', null, top, h('div', { class: 'view', id: 'view', tabindex: '-1' }, content))));
  }

  async function refreshCounts() {
    if (!S.user) return;
    const [notes, q] = await Promise.all([quiet(get('/api/notes')), quiet(get('/api/verify'))]);
    if (!notes || !q) return;
    const unread = notes.filter(n => !n.read).length, queue = q.items.length;
    if (unread !== S.unread || queue !== S.queue) {
      S.unread = unread; S.queue = queue;
      document.querySelectorAll('.rail .count').forEach(x => x.remove());
      for (const [href, , , key] of NAV) {
        if (!key || !S[key]) continue;
        const a = document.querySelector('.rail a[href="' + href + '"]');
        if (a) a.appendChild(h('span', { class: 'count' }, S[key]));
      }
    }
  }

  function changePassword() {
    const cur = h('input', { type: 'password', autocomplete: 'current-password' });
    const next = h('input', { type: 'password', autocomplete: 'new-password' });
    const err = h('div', { class: 'error' });
    let close;
    const f = h('form', { class: 'form', onsubmit: async (e) => {
      e.preventDefault();
      try { await act(() => post('/api/me/password', { current: cur.value, next: next.value }), 'Password changed. Please sign in again.', err); close(); showLogin(); } catch (x) { /* shown */ }
    } }, h('label', { class: 'f' }, 'Current password', cur), h('label', { class: 'f' }, 'New password', h('span', { class: 'hint' }, '8+ characters, a letter and a number'), next), err,
      h('div', { class: 'row' }, h('button', { class: 'btn primary', type: 'submit' }, 'Change')));
    close = modal('Change password', f);
  }

  // ================================================================== router

  const ROUTES = [
    [/^#\/?$/, homePage],
    [/^#\/me$/, () => profilePage(S.user.id)],
    [/^#\/people\/([\w-]+)$/, (m) => profilePage(m[1])],
    [/^#\/table$/, tablePage],
    [/^#\/search$/, searchPage],
    [/^#\/growth$/, growthPage],
    [/^#\/projects$/, projectsPage],
    [/^#\/projects\/([\w-]+)$/, (m) => projectPage(m[1])],
    [/^#\/verify$/, verifyPage],
    [/^#\/team$/, teamPage],
    [/^#\/reports$/, reportsPage],
    [/^#\/hr$/, hrPage],
    [/^#\/inbox$/, inboxPage],
  ];
  function params() { const i = location.hash.indexOf('?'); return new URLSearchParams(i < 0 ? '' : location.hash.slice(i + 1)); }

  async function route() {
    if (!S.user) return showLogin();
    const hash = location.hash || '#/';
    const path = hash.split('?')[0];
    closeAll();
    for (const [re, page] of ROUTES) {
      const m = path.match(re);
      if (!m) continue;
      const navKey = '#/' + (path.split('/')[1] || '');
      shell(navKey === '#/people' ? '' : navKey === '#/' ? '#/' : navKey, loading());
      try {
        const content = await page(m);
        const v = document.getElementById('view');
        if (v && content) { clear(v).appendChild(content); }
      } catch (e) {
        if (e.status === 401) return;
        const v = document.getElementById('view');
        if (v) clear(v).appendChild(h('div', { class: 'card' }, h('h2', null, 'Could not open this page'), h('p', null, e.message), h('a', { class: 'btn', href: '#/' }, 'Back to the bench')));
      }
      quiet(refreshCounts());
      return;
    }
    location.hash = '#/';
  }
  window.addEventListener('hashchange', route);
  const rerender = () => route();

  // ================================================================== BENCH (home)

  async function homePage() {
    const d = await get('/api/home');
    const me = d.me;
    const hr = new Date().getHours();
    const hello = hr < 12 ? 'Good morning' : hr < 17 ? 'Good afternoon' : 'Good evening';
    const cards = [];

    cards.push(card('Your elements',
      h('div', { class: 'grid g3' },
        h('div', { class: 'kpi' }, h('b', null, d.verified), h('span', null, 'verified skills')),
        h('div', { class: 'kpi' }, h('b', null, d.waiting), h('span', null, 'waiting for a check')),
        h('div', { class: 'kpi' }, h('b', { style: { color: d.returned ? 'var(--bad)' : null } }, d.returned), h('span', null, 'sent back to you'))),
      h('div', { class: 'row', style: { 'margin-top': '14px' } },
        h('button', { class: 'btn primary', onclick: () => claimModal(null, null, rerender) }, '+ Add a skill'),
        h('a', { class: 'btn', href: '#/me' }, 'Open my table'))));

    const target = d.target;
    cards.push(card(target ? 'On the way to ' + roleName(target.roleId) : 'Your next role',
      target ? h('div', { class: 'row', style: { 'align-items': 'flex-start' } }, gauge(target.percent, target.ready ? 'ready' : 'ready'),
        h('div', { class: 'grow' },
          target.gaps.length ? target.gaps.slice(0, 4).map(g => gapLine(g)) : h('p', null, 'No gaps – you meet every skill this role needs.'),
          h('a', { class: 'btn small', href: '#/growth', style: { 'margin-top': '8px' } }, 'Career page →')))
        : h('p', { class: 'muted' }, 'Pick the role you want next on the ', h('a', { href: '#/growth' }, 'Growth'), ' page.')));

    cards.push(card(h('h2', null, 'Fading ', h('span', { class: 'chip warn' }, 't½')),
      d.fading.length ? h('div', { class: 'list' }, d.fading.map(c => h('div', { class: 'item' },
        tile(c.skillId, { size: 'mini' }),
        h('div', { class: 'grow' }, h('b', null, skillName(c.skillId)), h('div', { class: 'small muted' }, 'Last used ' + fmtMonth(c.lastUsed) + ' · counts as ' + c.effective + ' (verified ' + c.verified + ')')),
        h('button', { class: 'btn small', onclick: async () => { await act(() => post('/api/claims/' + c.id + '/used'), 'Marked as used this month.'); rerender(); } }, 'I used it this month'))))
        : h('p', { class: 'muted' }, 'Nothing is fading. Skills lose a level after a "half-life" without use (12–60 months by group).')));

    if (d.certs.length) cards.push(card('Certificates to renew', h('div', { class: 'list' }, d.certs.map(c => h('div', { class: 'item' },
      h('div', { class: 'grow' }, h('b', null, c.name), h('div', { class: 'small muted' }, c.issuer || '')), certChip(c))))));

    cards.push(card('Learning goals', d.goals.length ? h('div', { class: 'list' }, d.goals.map(g => h('div', { class: 'item' },
      tile(g.skillId, { size: 'mini' }),
      h('div', { class: 'grow' }, h('div', { class: 'row between' }, h('b', null, skillName(g.skillId) + ' → ' + g.target), g.overdue ? chip('overdue', 'bad') : h('span', { class: 'small muted' }, 'due ' + fmtDate(g.due))),
        bar(g.steps.length ? 100 * g.done / g.steps.length : 0, 'accent'), h('div', { class: 'tiny muted' }, g.done + ' of ' + g.steps.length + ' steps' + (g.pending ? ' · claim waiting for a check' : ''))))))
      : h('p', { class: 'muted' }, 'No open goals. Make a plan from the gaps on the ', h('a', { href: '#/growth' }, 'Growth'), ' page.')));

    cards.push(card('Projects and free time', h('div', { class: 'row' }, h('div', { class: 'grow' }, bar(100 - d.free, d.free < 20 ? 'accent' : '')), h('b', { class: 'mono' }, d.free + '% free')),
      d.slots.length ? h('div', { class: 'list', style: { 'margin-top': '8px' } }, d.slots.map(s => h('div', { class: 'item' },
        h('div', { class: 'grow' }, h('a', { href: '#/projects/' + s.projectId }, h('b', null, s.project)), h('div', { class: 'small muted' }, s.title + ' · ' + s.pct + '%' + (s.stretch ? ' · stretch role' : ''))),
        chip(s.status.toLowerCase(), s.status === 'ACTIVE' ? 'ok' : '')))) : h('p', { class: 'muted', style: { 'margin-top': '8px' } }, 'Not staffed on a project right now.')));

    if (d.endorsed.length) cards.push(card('Endorsed by colleagues', h('div', { class: 'list' }, d.endorsed.map(e => h('div', { class: 'item' },
      avatar(e.fromId), h('div', { class: 'grow' }, h('div', null, personLink(e.fromId), ' · ', h('b', null, skillName(e.skillId))), e.comment ? h('div', { class: 'small' }, '“' + e.comment + '”') : null,
        h('div', { class: 'tiny muted' }, ago(e.at))))))));

    if (d.team) {
      const t = d.team;
      cards.unshift(card('Your team',
        h('div', { class: 'grid g3' },
          h('div', { class: 'kpi' }, h('b', null, t.size), h('span', null, 'people')),
          h('div', { class: 'kpi' }, h('b', null, t.pending), h('span', null, h('a', { href: '#/verify' }, 'claims to check'))),
          h('div', { class: 'kpi' }, h('b', null, t.readyInRole + '/' + t.withRole), h('span', null, 'ready in their role'))),
        h('h3', { style: { 'margin-top': '14px' } }, 'Bus-factor risks'),
        t.risks.length ? t.risks.map(r => h('div', { class: 'gap-line' }, tile(r.skillId, { size: 'mini' }),
          h('div', null, h('b', null, skillName(r.skillId)), h('div', { class: 'small muted' }, r.count === 0 ? 'Nobody at level 3+' : 'Only ' + S.person[r.holders[0]].name + ' at level 3+')),
          chip('needed by ' + r.neededBy, r.count === 0 ? 'bad' : 'warn')))
          : h('p', { class: 'muted' }, 'Every skill your team needs has 2+ people at level 3.'),
        h('a', { class: 'btn small', href: '#/team' }, 'Team matrix →')));
    }

    return h('div', null,
      pageHead('Your lab bench', hello + ', ' + first(me.name), [me.title, me.dept, me.location].filter(Boolean).join(' · ')),
      h('div', { class: 'grid g3' }, cards));
  }

  function gapLine(g) {
    return h('div', { class: 'gap-line' }, tile(g.skillId, { size: 'mini' }),
      h('div', null, h('b', null, skillName(g.skillId)), g.must ? h('span', { class: 'chip bad', style: { 'margin-left': '6px' } }, 'must') : null,
        h('div', { class: 'small muted' }, 'have ' + g.have + ' · need ' + g.need + (g.pending ? ' · claim ' + g.pending + ' waiting' : ''))),
      shells(g.have, { want: g.need }));
  }

  // ================================================================== PROFILE (my skills / someone's skills)

  async function profilePage(userId) {
    const d = await get('/api/people/' + userId);
    const p = d.person;
    const self = d.self;
    const canVerify = {};
    for (const c of (d.canVerify || [])) canVerify[c.claimId] = c.max;
    const byCat = {};
    for (const c of d.skills) { const s = S.skill[c.skillId]; if (!s) continue; (byCat[s.categoryId] = byCat[s.categoryId] || []).push(c); }
    const reload = () => route();

    const tileFor = (c) => {
      const lost = c.verified > 0 ? c.verified - c.effective : 0;
      let flag = null, flagClass = '', state = '';
      if (c.status === 'CLAIMED') { flag = '?'; flagClass = 'wait'; state = 'pending'; }
      if (c.status === 'RETURNED') { flag = '!'; flagClass = 'back'; state = 'returned'; }
      if (c.fading && !flag) { flag = 't½'; flagClass = 'half'; }
      if (c.fading) state += ' fade';
      if (!c.verified && c.status !== 'VERIFIED') state += ' none';
      return tile(c.skillId, {
        level: c.effective, lost, want: c.status === 'CLAIMED' ? c.claimed : 0, flag, flagClass, cert: c.certified, state: state.trim(),
        meta: [h('span', null, c.endorsements ? '+' + c.endorsements : ''), h('span', null, c.verified ? NAMES[c.effective].slice(0, 5) : 'claim ' + (c.claimed || ''))],
        onclick: () => claimPanel(c, p, self, canVerify[c.id], reload)
      });
    };
    const cats = sortBy(Object.values(S.cat), c => c.order).filter(c => byCat[c.id]);
    const tables = cats.length ? cats.map(c => h('div', null,
      h('div', { class: 'group-title', style: { '--gb': 'var(--' + c.group + ')' } }, c.name, h('span', { class: 'muted' }, '· half-life ' + c.halfLife + ' months')),
      h('div', { class: 'tiles' }, sortBy(byCat[c.id], x => S.skill[x.skillId].number).map(tileFor))))
      : [empty(self ? 'Your table is empty. Add your first skill!' : 'No verified skills yet.')];

    const certRows = d.certs.length ? h('table', { class: 't' }, h('thead', null, h('tr', null, h('th', null, 'Certificate'), h('th', null, 'Skill'), h('th', null, 'Issued'), h('th', null, 'Status'), h('th', null))),
      h('tbody', null, d.certs.map(c => h('tr', null, h('td', null, h('b', null, c.name), h('div', { class: 'small muted' }, c.issuer || '')),
        h('td', null, c.skillId ? skillName(c.skillId) : '–'), h('td', { class: 'nowrap' }, fmtDate(c.issued)), h('td', null, certChip(c)),
        h('td', { class: 'right nowrap' }, (self || S.user.admin) ? [h('button', { class: 'btn small ghost', onclick: () => certModal(c, p.id, reload) }, 'Edit'),
          h('button', { class: 'btn small ghost', onclick: async () => { if (confirm('Delete this certificate?')) { await act(() => del('/api/certs/' + c.id), 'Deleted.'); reload(); } } }, 'Delete')] : null)))))
      : empty('No certificates.');

    const head = h('div', { class: 'card', style: { 'margin-bottom': '18px' } }, h('div', { class: 'row', style: { 'align-items': 'flex-start' } },
      avatar(p.id, true),
      h('div', { class: 'grow' },
        h('div', { class: 'kicker' }, self ? 'My skills' : (d.full ? 'Full profile (your team)' : 'Skill profile')),
        h('h1', { style: { 'margin-bottom': '2px' } }, p.name),
        h('div', { class: 'muted' }, [p.title, p.dept, p.location].filter(Boolean).join(' · ')),
        h('div', { class: 'row small', style: { 'margin-top': '6px' } },
          p.managerId ? h('span', null, 'Manager: ', personLink(p.managerId)) : null,
          p.jobRoleId ? h('span', null, 'Role: ', h('b', null, roleName(p.jobRoleId))) : null,
          h('span', null, 'Free time: ', h('b', null, d.free + '%')),
          d.current ? h('span', null, 'Ready in role: ', h('b', null, d.current.percent + '%')) : null)),
      h('div', { class: 'row' },
        self ? h('button', { class: 'btn primary', onclick: () => claimModal(null, null, reload) }, '+ Add a skill') : null,
        self ? h('button', { class: 'btn', onclick: () => certModal(null, p.id, reload) }, '+ Certificate') : null,
        d.full ? h('a', { class: 'btn', href: '#/growth' + (self ? '' : '?user=' + p.id) }, 'Growth →') : null)));

    const legend = h('div', { class: 'legend small muted', style: { margin: '4px 0 6px' } },
      h('span', { class: 'k' }, shells(3), 'level (verified, after decay)'),
      h('span', { class: 'k' }, h('span', { class: 'shells' }, h('i', { class: 'on' }), h('i', { class: 'on' }), h('i', { class: 'lost' }), h('i'), h('i')), 'lost by decay'),
      d.full ? h('span', { class: 'k' }, h('span', { class: 'shells' }, h('i', { class: 'on' }), h('i', { class: 'want' }), h('i', { class: 'want' }), h('i'), h('i')), 'claimed, waiting') : null,
      h('span', { class: 'k' }, '◆ certified'), h('span', { class: 'k' }, '+n endorsements'), h('span', { class: 'k' }, 't½ fading'));

    return h('div', null, head, legend, tables,
      h('div', { class: 'grid g2', style: { 'margin-top': '22px' } },
        card('Certificates', certRows),
        card('Endorsements', d.endorsements.length ? h('div', { class: 'list' }, d.endorsements.slice(0, 12).map(e => h('div', { class: 'item' }, avatar(e.fromId),
          h('div', { class: 'grow' }, h('div', null, personLink(e.fromId), ' → ', h('b', null, skillName(e.skillId))), e.comment ? h('div', { class: 'small' }, '“' + e.comment + '”') : null),
          h('span', { class: 'tiny muted' }, ago(e.at))))) : empty('No endorsements yet.')),
        d.slots.length ? card('Projects', h('div', { class: 'list' }, d.slots.map(s => h('div', { class: 'item' }, h('div', { class: 'grow' },
          h('a', { href: '#/projects/' + s.projectId }, h('b', null, s.project)), h('div', { class: 'small muted' }, s.title + ' · ' + s.pct + '%')), chip(s.status.toLowerCase()))))) : null));
  }

  function claimPanel(c, person, self, verifyMax, reload) {
    const s = S.skill[c.skillId];
    const cat = S.cat[s.categoryId];
    const hl = s.halfLifeUsed;
    const err = h('div', { class: 'error' });
    const decay = c.verified && c.effective < c.verified
      ? 'Not used for ' + c.monthsUnused + ' months; the half-life is ' + hl + ' months, so it counts ' + (c.verified - c.effective) + ' level' + (c.verified - c.effective > 1 ? 's' : '') + ' lower.'
      : c.fading ? 'Last used ' + c.monthsUnused + ' months ago – it loses a level at ' + (Math.floor(c.monthsUnused / hl) + 1) * hl + ' months.' : null;
    const body = h('div', { class: 'stack' },
      h('div', { class: 'row', style: { 'align-items': 'flex-start' } }, tile(c.skillId, { size: 'big', level: c.effective, lost: c.verified - c.effective, cert: c.certified }),
        h('div', { class: 'grow' }, h('div', { class: 'kicker' }, cat.name), h('h2', null, s.name), h('p', { class: 'small muted' }, s.description),
          h('div', { class: 'small' }, person.name))),
      h('table', { class: 't' }, h('tbody', null,
        c.claimed !== undefined ? h('tr', null, h('th', null, 'Claimed'), h('td', null, shells(c.claimed), ' ', c.claimed + ' · ' + NAMES[c.claimed]), h('td', null, statusChip(c.status))) : null,
        h('tr', null, h('th', null, 'Verified'), h('td', { colspan: 2 }, c.verified ? [shells(c.verified), ' ' + c.verified + ' · ' + NAMES[c.verified] + ' by ', personLink(c.verifiedBy), ' · ' + fmtDate(c.verifiedAt)] : 'not yet')),
        h('tr', null, h('th', null, 'Counts today'), h('td', { colspan: 2 }, h('b', null, c.effective + ' · ' + NAMES[c.effective]))),
        h('tr', null, h('th', null, 'Last used'), h('td', { colspan: 2 }, fmtMonth(c.lastUsed), c.fading ? ' ' : null, c.fading ? chip('t½ fading', 'warn') : null)))),
      decay ? h('p', { class: 'note' }, decay) : null,
      c.note ? h('div', null, h('div', { class: 'small muted' }, 'Note from the checker'), h('p', { class: 'note' }, c.note)) : null,
      c.evidence ? h('div', null, h('div', { class: 'small muted' }, 'Evidence'), h('p', null, c.evidence)) : null,
      h('div', { class: 'small' }, c.endorsements ? plural(c.endorsements, 'endorsement') + ' (weighted ' + c.endorseWeight + ' – an endorsement from someone good at it counts more)' : 'No endorsements yet.'),
      err,
      h('div', { class: 'row' },
        self ? h('button', { class: 'btn', onclick: async () => { await act(() => post('/api/claims/' + c.id + '/used'), 'Marked as used this month.', err); closeAll(); reload(); } }, 'I used it this month') : null,
        self ? h('button', { class: 'btn', onclick: () => claimModal(c.skillId, c, reload) }, c.status === 'RETURNED' ? 'Fix and claim again' : 'Change level') : null,
        self ? h('button', { class: 'btn danger', onclick: async () => { if (confirm('Remove ' + s.name + ' from your table?')) { await act(() => del('/api/claims/' + c.id), 'Removed.', err); closeAll(); reload(); } } }, 'Remove') : null,
        !self && c.verified ? (c.myEndorsement
          ? h('button', { class: 'btn', onclick: async () => { await act(() => del('/api/endorsements/' + c.myEndorsement), 'Endorsement withdrawn.', err); closeAll(); reload(); } }, 'Withdraw endorsement')
          : h('button', { class: 'btn primary', onclick: () => endorseModal(person, c.skillId, reload) }, '+ Endorse')) : null),
      verifyMax ? verifyBox(c, verifyMax, () => { closeAll(); reload(); }) : null,
      c.history && c.history.length ? h('div', null, h('h3', null, 'History'), h('div', { class: 'history' }, c.history.slice().reverse().map(e =>
        h('div', null, h('b', null, e.action), ' ', e.level ? 'level ' + e.level : '', ' · ', personLink(e.by), ' · ', h('span', { class: 'muted' }, fmtDate(e.at)), e.note ? h('div', { class: 'muted' }, e.note) : null)))) : null);
    panel(body);
  }

  function endorseModal(person, skillId, done) {
    const cm = h('input', { type: 'text', maxlength: '160', placeholder: 'e.g. Helped me fix a hard production bug' });
    const err = h('div', { class: 'error' });
    let close;
    close = modal('Endorse ' + first(person.name) + ' for ' + skillName(skillId), h('form', { class: 'form', onsubmit: async (e) => {
      e.preventDefault();
      try { await act(() => post('/api/endorse', { toId: person.id, skillId, comment: cm.value }), 'Endorsed.', err); close(); done(); } catch (x) { /* shown */ }
    } }, h('p', { class: 'muted small' }, 'Only endorse what you have seen them do. At most 15 endorsements a week.'),
      h('label', { class: 'f' }, 'What did you see? (optional)', cm), err, h('div', { class: 'row' }, h('button', { class: 'btn primary', type: 'submit' }, 'Endorse'))));
  }

  /** Add or change a claim. */
  function claimModal(skillId, existing, done) {
    let sid = skillId;
    const err = h('div', { class: 'error' });
    const used = h('input', { type: 'month', value: existing && existing.lastUsed ? existing.lastUsed : thisMonth(), max: thisMonth() });
    const ev = h('textarea', { maxlength: '400', placeholder: 'An example of your work: a project, a pull request, a talk, a link…' }, existing && existing.evidence ? existing.evidence : '');
    const levels = h('div');
    const title = h('div', { class: 'row' });
    const drawLevels = () => {
      clear(levels).appendChild(levelRadios('lvl', existing ? existing.claimed : 2));
      clear(title);
      if (sid) title.appendChild(add(h('div', { class: 'row' }), [tile(sid, { size: 'mini' }), h('b', null, skillName(sid)), h('span', { class: 'small muted' }, S.skill[sid].description)]));
    };
    drawLevels();
    let close;
    const form = h('form', { class: 'form', onsubmit: async (e) => {
      e.preventDefault();
      if (!sid) { err.textContent = 'Pick a skill first.'; return; }
      try {
        await act(() => post('/api/claims', { skillId: sid, level: radioValue(form, 'lvl'), lastUsed: used.value, evidence: ev.value }), 'Claimed – your manager or an assessor will check it.', err);
        close(); done && done();
      } catch (x) { /* shown */ }
    } },
      skillId ? null : h('div', null, h('div', { class: 'f' }, 'Skill'), skillPicker(null, (id) => { sid = id; drawLevels(); })),
      title,
      h('div', null, h('div', { class: 'f', style: { 'margin-bottom': '6px' } }, 'Your level'), levels),
      h('div', { class: 'two' },
        h('label', { class: 'f' }, 'Last used', h('span', { class: 'hint' }, 'month you last used it at work'), used),
        h('div', { class: 'small muted', style: { 'align-self': 'end' } }, 'Someone else checks every claim (four eyes). Until then your old verified level counts.')),
      h('label', { class: 'f' }, 'Evidence', h('span', { class: 'hint' }, 'needed for level 4 and 5'), ev),
      err, h('div', { class: 'row' }, h('button', { class: 'btn primary', type: 'submit' }, existing ? 'Claim again' : 'Claim')));
    close = modal(existing ? 'Change your level' : 'Add a skill', form, { wide: !skillId });
  }

  function certModal(c, userId, done) {
    const name = h('input', { type: 'text', value: c ? c.name : '', required: true, maxlength: '80' });
    const issuer = h('input', { type: 'text', value: c ? c.issuer || '' : '', maxlength: '60' });
    const skill = h('select', null, h('option', { value: '' }, '– none –'), sortBy(S.meta.skills.filter(s => s.active), s => s.name).map(s => h('option', { value: s.id, selected: c && c.skillId === s.id }, s.name)));
    const issued = h('input', { type: 'date', value: c ? c.issued : '', max: S.meta.today, required: true });
    const expires = h('input', { type: 'date', value: c && c.expires ? c.expires : '' });
    const err = h('div', { class: 'error' });
    let close;
    close = modal(c ? 'Edit certificate' : 'Add a certificate', h('form', { class: 'form', onsubmit: async (e) => {
      e.preventDefault();
      const b = { name: name.value, issuer: issuer.value, skillId: skill.value, issued: issued.value, expires: expires.value || null, userId };
      try { await act(() => c ? put('/api/certs/' + c.id, b) : post('/api/certs', b), 'Saved.', err); close(); done(); } catch (x) { /* shown */ }
    } }, h('label', { class: 'f' }, 'Name', name), h('label', { class: 'f' }, 'Issued by', issuer), h('label', { class: 'f' }, 'Skill it proves', skill),
      h('div', { class: 'two' }, h('label', { class: 'f' }, 'Issued', issued), h('label', { class: 'f' }, 'Expires', h('span', { class: 'hint' }, 'empty = never'), expires)),
      h('p', { class: 'small muted' }, 'You get a reminder 60 and 30 days before it expires.'), err,
      h('div', { class: 'row' }, h('button', { class: 'btn primary', type: 'submit' }, 'Save'))));
  }

  /** The verification form (in the verify page and in the claim panel). */
  function verifyBox(c, max, done) {
    const note = h('textarea', { placeholder: 'A short note for them (needed when you choose a different level, or send it back)', maxlength: '300' });
    const err = h('div', { class: 'error' });
    const box = h('div', { class: 'card flat stack' }, h('h3', null, 'Check this claim'),
      levelRadios('v-' + c.id, Math.min(c.claimed, max), max), note, err,
      h('div', { class: 'row' },
        h('button', { class: 'btn primary', onclick: async () => {
          const lv = radioValue(box, 'v-' + c.id);
          try { await act(() => post('/api/claims/' + c.id + '/verify', { level: lv, note: note.value }), 'Verified at level ' + lv + '.', err); done(); } catch (x) { /* shown */ }
        } }, 'Verify at this level'),
        h('button', { class: 'btn', onclick: async () => {
          try { await act(() => post('/api/claims/' + c.id + '/return', { note: note.value }), 'Sent back with your question.', err); done(); } catch (x) { /* shown */ }
        } }, 'Send back with a question')),
      max < 5 ? h('p', { class: 'tiny muted' }, 'As an assessor you can confirm up to level ' + max + ' (your own level).') : null);
    return box;
  }

  // ================================================================== PERIODIC TABLE

  async function tablePage() {
    const d = await get('/api/table');
    const stat = {};
    for (const x of d.skills) stat[x.skillId] = x;
    const maxSupply = Math.max(1, ...d.skills.map(x => x.practitioners));
    const maxDemand = Math.max(1, ...d.skills.map(x => x.demand));
    const holder = h('div');
    const modes = [['group', 'Group colours'], ['mine', 'My levels'], ['supply', 'Supply (people at 3+)'], ['demand', 'Demand (gaps + open slots)']];
    const modeBar = h('div', { class: 'row tight', role: 'group', 'aria-label': 'Colour the table by' });
    const draw = () => {
      clear(modeBar);
      for (const [k, label] of modes) modeBar.appendChild(h('button', { class: 'btn small' + (S.colorMode === k ? ' on' : ''), 'aria-pressed': String(S.colorMode === k), onclick: () => { S.colorMode = k; draw(); } }, label));
      const cols = sortBy(Object.values(S.cat), c => c.order).map(c => {
        const skills = sortBy(S.meta.skills.filter(s => s.active && s.categoryId === c.id), s => s.number);
        return h('div', { class: 'pcol ' + c.group }, h('h4', null, c.name), skills.map(s => {
          const st = stat[s.id] || {};
          const o = { onclick: () => skillPanel(s.id), topRight: st.practitioners ? String(st.practitioners) : '',
            meta: [h('span', null, (st.people || 0) + ' ppl'), h('span', null, st.demand ? 'need ' + st.demand : '')] };
          if (S.colorMode === 'mine') { o.level = st.mine || 0; if (!st.mine) o.state = 'none'; if (st.myStatus === 'CLAIMED') o.flag = '?', o.flagClass = 'wait'; }
          if (S.colorMode === 'supply') { o.state = 'heat'; o.heat = Math.round(10 + 85 * (st.practitioners || 0) / maxSupply); }
          if (S.colorMode === 'demand') { o.state = 'heat'; o.heat = Math.round(8 + 87 * (st.demand || 0) / maxDemand); }
          o.title = s.name + ' · ' + (st.people || 0) + ' people · ' + (st.practitioners || 0) + ' at level 3+' + (st.demand ? ' · needed ' + st.demand + '×' : '');
          return tile(s.id, o);
        }));
      });
      clear(holder).appendChild(h('div', { class: 'table-wrap' }, h('div', { class: 'ptable' }, cols)));
    };
    draw();
    const legend = h('div', { class: 'legend', style: { margin: '10px 0 16px' } }, sortBy(Object.values(S.cat), c => c.order).map(c =>
      h('span', { class: 'k' }, h('span', { class: 'sw ' + c.group }), c.name)));
    return h('div', null,
      pageHead('Catalogue', 'The periodic table of skills',
        S.meta.skills.filter(s => s.active).length + ' skills in ' + S.meta.categories.length + ' groups. The small number top-right counts people at level 3 or more. Click an element for its details.',
        modeBar),
      legend, holder);
  }

  async function skillPanel(id) {
    const s = S.skill[id];
    const close = panel(loading());
    let d;
    try { d = await get('/api/skills/' + id); } catch (e) { close(); toast(e.message, true); return; }
    const dist = [0, 0, 0, 0, 0];
    for (const p of d.people) dist[p.effective - 1]++;
    const maxD = Math.max(1, ...dist);
    const body = h('div', { class: 'stack' },
      h('div', { class: 'row', style: { 'align-items': 'flex-start' } }, tile(id, { size: 'big', topRight: String(d.people.filter(p => p.effective >= 3).length) }),
        h('div', { class: 'grow' }, h('div', { class: 'kicker' }, S.cat[s.categoryId].name), h('h2', null, s.name), h('p', { class: 'small' }, s.description),
          s.synonyms.length ? h('div', { class: 'small muted' }, 'Also called: ' + s.synonyms.join(', ')) : null,
          h('div', { class: 'small muted' }, 'Half-life: ' + s.halfLifeUsed + ' months without use = one level lower'))),
      Object.keys(s.prereqs).length ? h('div', null, h('h3', null, 'Learn first'), h('div', { class: 'tiles' }, Object.entries(s.prereqs).map(([pid, lv]) =>
        h('div', { class: 'row tight' }, tile(pid, { size: 'mini', onclick: () => skillPanel(pid) }), h('span', { class: 'small' }, skillName(pid) + ' ' + lv))))) : null,
      d.neededBy.length ? h('div', null, h('h3', null, 'Needed first by'), h('div', { class: 'tiles' }, d.neededBy.map(pid => tile(pid, { size: 'mini', onclick: () => skillPanel(pid) })))) : null,
      h('div', null, h('h3', null, 'People at each level'), h('div', { class: 'dist' }, dist.map(n => h('i', { style: { height: (100 * n / maxD) + '%' }, title: n + ' people' }))),
        h('div', { class: 'dist-l' }, dist.map((n, i) => h('span', null, (i + 1) + ': ' + n)))),
      d.trend.length > 1 ? h('div', null, h('h3', null, 'People at level 3+ over the year'), lineChart(d.trend.map(t => t.month), [{ name: s.name, values: d.trend.map(t => t.n) }], 120)) : null,
      d.roles.length ? h('div', null, h('h3', null, 'Job roles that need it'), h('div', { class: 'list' }, d.roles.map(r => h('div', { class: 'row between small' }, h('span', null, roleName(r.roleId)), h('span', null, 'level ' + r.level + (r.must ? ' · must' : '')))))) : null,
      h('div', null, h('h3', null, plural(d.people.length, 'person') + ' (verified)'), d.people.length ? h('div', { class: 'list' }, d.people.map(p => h('div', { class: 'row between' },
        who(p.userId), h('span', { class: 'row tight' }, p.fading ? chip('t½', 'warn', 'fading') : null, shells(p.effective, { lost: p.verified - p.effective }))))) : empty('Nobody yet.')),
      h('div', { class: 'row' }, h('button', { class: 'btn primary', onclick: () => { close(); claimModal(id, null, rerender); } }, 'Claim this skill'),
        h('a', { class: 'btn', href: '#/search?q=' + encodeURIComponent('"' + s.name + '" >= 3'), onclick: () => close() }, 'Find people at 3+')));
    const p = document.querySelector('.panel');
    if (p) { while (p.children.length > 1) p.removeChild(p.lastChild); p.appendChild(body); }
  }

  // ================================================================== SEARCH

  async function searchPage() {
    const ps = params();
    const q0 = ps.get('q') || '';
    const claimed = ps.get('claimed') === '1';
    const input = h('input', { type: 'search', value: q0, placeholder: 'java >= 3 and (aws or azure)', 'aria-label': 'Search query', style: { flex: '1', 'font-family': 'var(--font-mono)' } });
    const inc = h('input', { type: 'checkbox', checked: claimed });
    const go = (q, c) => { location.hash = '#/search?q=' + encodeURIComponent(q) + (c ? '&claimed=1' : ''); };
    const form = h('form', { class: 'row', onsubmit: (e) => { e.preventDefault(); go(input.value.trim(), inc.checked); } },
      input, h('button', { class: 'btn primary', type: 'submit' }, 'Search'));
    const examples = ['java >= 4 and spring boot >= 3', 'kubernetes:3 or terraform:3', '"machine learning" and python >= 3', 'react >= 3 and not typescript', 'figma >= expert', 'communication >= 4 and mentoring >= 3'];
    const help = card('How to search',
      h('p', { class: 'small' }, 'Write skills with a level: ', h('code', null, 'java >= 3'), ', ', h('code', null, 'aws:2'), ' (":" means ">="), ', h('code', null, 'python > 2'), ', ', h('code', null, 'go < 2'), '. Join with ', h('b', null, 'and'), ', ', h('b', null, 'or'), ', ', h('b', null, 'not'), ' and brackets. Two skills side by side mean "and". Levels can be numbers or names (', h('i', null, 'Practitioner'), '). Names, symbols and other names all work: ', h('code', null, 'k8s'), ', ', h('code', null, 'js'), ', ', h('code', null, 'postgres'), '.'),
      h('div', { class: 'examples' }, examples.map(x => h('button', { class: 'btn small', onclick: () => go(x, inc.checked) }, x))));
    const results = h('div');
    if (q0) {
      try {
        const r = await get('/api/search?q=' + encodeURIComponent(q0) + (claimed ? '&claimed=1' : ''));
        results.appendChild(card(h('div', { class: 'row' }, h('h2', null, plural(r.hits.length, 'person') + ' found'), h('span', { class: 'muted small mono' }, r.understood)),
          r.hits.length ? h('div', null, r.hits.map(x => {
            const p = S.person[x.userId] || {};
            return h('div', { class: 'hit' }, avatar(x.userId),
              h('div', null, h('div', { class: 'row tight' }, personLink(x.userId), x.unverified ? chip('includes a claim not checked yet', 'warn') : null),
                h('div', { class: 'small muted' }, [p.title, p.location].filter(Boolean).join(' · '))),
              h('div', { class: 'row tight' }, r.skills.map(sid => {
                const [lv, sure] = x.levels[sid] || [0, 0];
                return tile(sid, { level: lv, lost: 0, want: lv > sure ? lv : 0, state: lv ? (lv > sure ? 'pending' : '') : 'none', meta: [h('span', null, NAMES[lv].slice(0, 5)), h('span', null)] });
              }), h('span', { class: 'chip', title: 'free time' }, x.free + '% free')));
          })) : empty('Nobody matches. Try "include claims not checked yet", or lower a level.')));
      } catch (e) {
        const sug = (e.data && e.data.suggestions) || [];
        const bad = (e.message.match(/'([^']+)'/) || [])[1];
        results.appendChild(h('div', { class: 'card' }, h('p', { class: 'error' }, e.message),
          sug.length && bad ? h('div', { class: 'row' }, 'Try: ', sug.map(sname => h('button', { class: 'btn small', onclick: () => go(q0.replace(bad, sname.includes(' ') ? '"' + sname + '"' : sname), inc.checked) }, sname))) : null));
      }
    }
    return h('div', null, pageHead('Talent search', 'Find people by skills', 'Only verified levels count (after decay) – unless you tick the box.'),
      h('div', { class: 'card stack', style: { 'margin-bottom': '16px' } }, form,
        h('label', { class: 'check small' }, inc, 'Include claims that are not checked yet')),
      h('div', { class: 'grid', style: { 'grid-template-columns': 'minmax(0,1fr) 340px' } }, results, help));
  }

  // ================================================================== TEAM MATRIX

  async function teamPage() {
    const ps = params();
    const scope = ps.get('scope') || 'team';
    const category = ps.get('category') || '';
    const d = await get('/api/matrix?scope=' + scope + '&category=' + encodeURIComponent(category));
    const nav = (sc, ct) => { location.hash = '#/team?scope=' + sc + (ct ? '&category=' + ct : ''); };
    const tabs = h('div', { class: 'tabs', role: 'tablist' }, [['team', S.user.manager ? 'My team' : 'My team'], ['dept', 'My department'], ['all', 'Everyone']].map(([k, label]) =>
      h('button', { role: 'tab', 'aria-selected': String(scope === k), onclick: () => nav(k, category) }, label)));
    const catSel = h('select', { 'aria-label': 'Group', onchange: (e) => nav(scope, e.target.value) }, h('option', { value: '' }, 'All groups'),
      sortBy(Object.values(S.cat), c => c.order).map(c => h('option', { value: c.id, selected: c.id === category }, c.name)));
    const summary = {};
    for (const s of d.summary) summary[s.skillId] = s.practitioners;
    const table = h('table', { class: 'matrix' },
      h('thead', null, h('tr', null, h('th', { class: 'p' }, plural(d.rows.length, 'person')),
        d.columns.map(sid => h('th', null, h('div', { class: 'colh' }, tile(sid, { size: 'mini', onclick: () => skillPanel(sid) })))))),
      h('tbody', null, d.rows.map(r => h('tr', null,
        h('th', { class: 'p' }, h('div', { class: 'row tight' }, avatar(r.userId), h('div', null, personLink(r.userId), h('div', { class: 'tiny muted' }, (S.person[r.userId] || {}).title || '')))),
        d.columns.map(sid => {
          const cell = r.cells[sid];
          const g = group(sid);
          if (!cell) return h('td', { class: 'c ' + g });
          const [lv, pend] = cell;
          const cls = 'c ' + g + (lv ? ' l' + lv : '') + (pend && pend !== lv ? ' pend' : '');
          return h('td', { class: cls, title: skillName(sid) + ': ' + (lv ? 'level ' + lv : 'not verified') + (pend ? ' · claim ' + pend + ' waiting' : '') },
            h('span', null, lv || (pend ? pend + '?' : '')));
        })))),
      h('tfoot', null, h('tr', null, h('td', { class: 'p', style: { 'text-align': 'left', padding: '4px 10px' } }, 'people at 3+'),
        d.columns.map(sid => h('td', { class: summary[sid] < S.meta.busSafe ? 'risk' : '' }, summary[sid])))));
    const risks = card('Bus-factor risks', h('p', { class: 'small muted' }, 'Skills this group needs (must-haves of their roles and their project slots) with fewer than ' + S.meta.busSafe + ' people at level 3+. If that one person is away, the work stops.'),
      d.risks.length ? h('div', null, d.risks.map(r => h('div', { class: 'gap-line' }, tile(r.skillId, { size: 'mini', onclick: () => skillPanel(r.skillId) }),
        h('div', null, h('b', null, skillName(r.skillId)), h('div', { class: 'small muted' }, r.count === 0 ? 'Nobody at level 3+' : 'Only ', r.holders.map(personLink), r.count ? ' at level 3+' : '')),
        chip('needed by ' + r.neededBy, r.count === 0 ? 'bad' : 'warn')))) : empty('No risks: every needed skill has ' + S.meta.busSafe + '+ people at level 3.'));
    return h('div', null,
      pageHead('Team', 'Skill matrix', 'Effective (verified, after decay) levels. A dashed cell is a claim still waiting for a check.',
        catSel, S.user.manager ? h('a', { class: 'btn', href: '/api/export.csv', download: 'kaushal-skill-matrix.csv' }, 'Export CSV') : null),
      tabs, h('div', { class: 'grid', style: { 'grid-template-columns': 'minmax(0,1fr)' } }, d.columns.length ? h('div', { class: 'matrix-wrap' }, table) : empty('No skills here yet.'), risks));
  }

  // ================================================================== VERIFY

  async function verifyPage() {
    const d = await get('/api/verify');
    const list = h('div', { class: 'grid g2' });
    const draw = (items) => {
      clear(list);
      if (!items.length) { list.appendChild(empty('Nothing to check. Nice!')); return; }
      for (const c of items) {
        const s = S.skill[c.skillId];
        const cardEl = h('section', { class: 'card stack' },
          h('div', { class: 'row', style: { 'align-items': 'flex-start' } }, tile(c.skillId, { level: c.verified ? c.effective : 0, want: c.claimed, state: 'pending' }),
            h('div', { class: 'grow' }, who(c.userId), h('p', { style: { margin: '8px 0 4px' } }, 'Claims ', h('b', null, s.name + ' at level ' + c.claimed + ' (' + NAMES[c.claimed] + ')')),
              h('div', { class: 'row tight small' }, chip(c.why), chip('waiting ' + hours(c.ageHours), c.ageHours > 72 ? 'warn' : ''),
                c.verified ? chip('verified now: ' + c.verified) : chip('new skill'), c.endorsements ? chip('+' + c.endorsements + ' endorsements') : null,
                c.myLevel ? chip('your level: ' + c.myLevel) : null))),
          c.evidence ? h('p', { class: 'note' }, c.evidence) : h('p', { class: 'muted small' }, 'No evidence given.'),
          h('div', { class: 'small muted' }, 'Last used ' + fmtMonth(c.lastUsed)),
          verifyBox(c, c.maxLevel, () => draw(items.filter(x => x.id !== c.id))));
        list.appendChild(cardEl);
      }
      S.queue = items.length;
    };
    draw(d.items);
    return h('div', null, pageHead('Four eyes', 'Claims to check', 'You can check claims of your team (and theirs), or – as an assessor at level ' + S.meta.assessorLevel + '+ in a skill – claims up to your own level. Nobody checks their own.'), list);
  }

  // ================================================================== GROWTH

  async function growthPage() {
    const uid = params().get('user');
    const d = await get('/api/growth' + (uid ? '?user=' + uid : ''));
    const p = d.person;
    const self = p.id === S.user.id;
    const reload = () => route();
    const roleBlock = (title, r, extra) => card(title, r ? h('div', null,
      h('div', { class: 'row', style: { 'align-items': 'flex-start' } }, gauge(r.percent), h('div', { class: 'grow' },
        h('h3', null, roleName(r.roleId)), h('p', { class: 'small muted' }, S.role[r.roleId].description),
        r.ready ? chip('ready', 'ok') : r.mustMissing ? chip(plural(r.mustMissing, 'must-have') + ' missing', 'bad') : chip('almost', 'warn'))),
      r.gaps.length ? h('div', { style: { 'margin-top': '10px' } }, r.gaps.map(gapLine)) : h('p', { class: 'muted' }, 'Every skill met.'),
      r.met.length ? h('details', { style: { 'margin-top': '8px' } }, h('summary', { class: 'small' }, plural(r.met.length, 'skill') + ' already met'), r.met.map(gapLine)) : null,
      extra) : h('p', { class: 'muted' }, '–'));

    const target = d.target || d.next;
    const planBtn = (r) => r && r.gaps.length ? h('div', { class: 'row', style: { 'margin-top': '12px' } },
      h('button', { class: 'btn primary', onclick: async () => {
        const res = await act(() => post('/api/goals/plan', { userId: p.id, roleId: r.roleId }));
        toast(res.goals.length ? plural(res.goals.length, 'learning goal') + ' added (prerequisites first).' : 'All the gaps already have open goals.');
        reload();
      } }, 'Make a learning plan'),
      h('span', { class: 'small muted' }, 'One goal per gap, prerequisites first, one every 6 weeks.')) : null;

    const sel = h('select', { 'aria-label': 'Role I want next' }, h('option', { value: '' }, '– choose –'),
      sortBy(Object.values(S.role), r => r.ladder + r.step).map(r => h('option', { value: r.id, selected: p.targetRoleId === r.id }, r.ladder + ' · ' + r.name)));
    const targetPick = self ? h('div', { class: 'row', style: { 'margin-top': '12px' } }, sel,
      h('button', { class: 'btn small', onclick: async () => { await act(() => post('/api/me/target', { roleId: sel.value || null }), 'Goal role saved.'); S.user.targetRoleId = sel.value || null; await reloadMeta(); reload(); } }, 'Set as my goal role')) : null;

    const matches = card('How ready for every role', h('table', { class: 't' }, h('thead', null, h('tr', null, h('th', null, 'Role'), h('th', null, 'Ladder'), h('th', { style: { width: '40%' } }, 'Readiness'), h('th', null))),
      h('tbody', null, d.matches.map(m => h('tr', null, h('td', null, h('b', null, roleName(m.roleId))), h('td', { class: 'small muted' }, S.role[m.roleId].ladder + ' ' + S.role[m.roleId].step),
        h('td', null, h('div', { class: 'row tight' }, h('div', { class: 'grow' }, bar(m.percent, m.ready ? 'ok' : '')), h('span', { class: 'mono small' }, m.percent + '%'))),
        h('td', null, m.ready ? chip('ready', 'ok') : m.mustMissing ? chip(m.mustMissing + ' must', 'bad') : null,
          h('button', { class: 'btn small ghost', onclick: () => panel(roleBlock('Gaps for ' + roleName(m.roleId), d.roles[m.roleId], planBtn(d.roles[m.roleId]))) }, 'Gaps')))))));

    const open = d.goals.filter(g => g.status === 'OPEN'), closed = d.goals.filter(g => g.status !== 'OPEN');
    const goals = card(h('div', { class: 'row between', style: { width: '100%' } }, h('h2', null, 'Learning goals'),
      h('button', { class: 'btn small', onclick: () => goalModal(p, d.levels, reload) }, '+ Goal')),
      open.length ? h('div', { class: 'grid g2' }, open.map(g => goalCard(g, self, reload))) : empty('No open goals.'),
      closed.length ? h('details', { style: { 'margin-top': '12px' } }, h('summary', null, plural(closed.length, 'closed goal')),
        h('div', { class: 'list' }, closed.map(g => h('div', { class: 'item' }, tile(g.skillId, { size: 'mini' }), h('div', { class: 'grow' }, h('b', null, skillName(g.skillId) + ' ' + g.fromLevel + ' → ' + g.target),
          h('div', { class: 'small muted' }, (g.reason || '') + ' · closed ' + fmtDate(g.doneAt))), chip(g.status.toLowerCase(), g.status === 'DONE' ? 'ok' : ''))))) : null);

    return h('div', null,
      pageHead(self ? 'Your career' : 'Career of ' + p.name, 'Growth', 'Readiness % = how much of each role\'s skills you have (weighted). Ready = all must-haves and ' + S.meta.readyAt + '%+.'),
      h('div', { class: 'grid g2' },
        roleBlock(d.target ? 'Goal role' : 'Next on your ladder', target, [planBtn(target), targetPick]),
        roleBlock('Current role', d.current, null),
        h('div', { class: 'span2' }, goals),
        h('div', { class: 'span2' }, matches)));
  }

  function goalCard(g, self, reload) {
    const steps = g.steps.map((st, i) => h('label', { class: 'step' + (st.done ? ' done' : '') },
      h('input', { type: 'checkbox', checked: st.done, onchange: async (e) => { await act(() => post('/api/goals/' + g.id + '/step', { index: i, done: e.target.checked })); reload(); } }),
      h('span', null, st.text)));
    return h('div', { class: 'goal' + (g.overdue ? ' overdue' : '') },
      h('div', { class: 'row', style: { 'align-items': 'flex-start' } }, tile(g.skillId, { size: 'mini' }),
        h('div', { class: 'grow' }, h('b', null, skillName(g.skillId) + ' ' + g.fromLevel + ' → ' + g.target), h('div', { class: 'small muted' }, g.reason || ''),
          h('div', { class: 'small' }, g.overdue ? chip('overdue since ' + fmtDate(g.due), 'bad') : 'due ' + fmtDate(g.due), ' · now ', h('b', null, g.now))),
        shells(g.now, { want: g.target })),
      bar(g.steps.length ? 100 * g.done / g.steps.length : 0, 'accent'),
      h('div', { style: { 'margin-top': '8px' } }, steps),
      g.pending ? h('p', { class: 'small note' }, 'Your claim for level ' + g.target + ' is waiting for a check – the goal closes when it is verified.') : null,
      h('div', { class: 'row', style: { 'margin-top': '8px' } },
        self && !g.pending ? h('button', { class: 'btn small primary', onclick: async () => { await act(() => post('/api/goals/' + g.id + '/finish'), 'Done! Your claim goes to your manager for a check.'); reload(); } }, 'I reached it – claim level ' + g.target) : null,
        h('button', { class: 'btn small ghost', onclick: async () => { if (confirm('Drop this goal?')) { await act(() => post('/api/goals/' + g.id + '/drop'), 'Dropped.'); reload(); } } }, 'Drop')));
  }

  function goalModal(p, levels, done) {
    let sid = null;
    const due = h('input', { type: 'date', min: S.meta.today, required: true });
    const target = h('select', null, [1, 2, 3, 4, 5].map(i => h('option', { value: i, selected: i === 3 }, i + ' · ' + NAMES[i])));
    const reason = h('input', { type: 'text', maxlength: '80', placeholder: 'Why? e.g. Needed for the e-Mandi project' });
    const err = h('div', { class: 'error' });
    const now = h('div', { class: 'small muted' });
    let close;
    close = modal('New learning goal for ' + first(p.name), h('form', { class: 'form', onsubmit: async (e) => {
      e.preventDefault();
      if (!sid) { err.textContent = 'Pick a skill.'; return; }
      try { await act(() => post('/api/goals', { userId: p.id, skillId: sid, target: +target.value, due: due.value, reason: reason.value }), 'Goal added.', err); close(); done(); } catch (x) { /* shown */ }
    } }, skillPicker(null, (id) => { sid = id; now.textContent = skillName(id) + ': now at level ' + (levels[id] || 0); }), now,
      h('div', { class: 'two' }, h('label', { class: 'f' }, 'Target level', target), h('label', { class: 'f' }, 'Due', due)),
      h('label', { class: 'f' }, 'Reason', reason), h('p', { class: 'small muted' }, 'Four default steps are added: learn, use it at work, get a review, claim the level.'),
      err, h('div', { class: 'row' }, h('button', { class: 'btn primary', type: 'submit' }, 'Add goal'))), { wide: true });
  }

  // ================================================================== PROJECTS

  async function projectsPage() {
    const d = await get('/api/projects');
    return h('div', null,
      pageHead('Staffing', 'Projects', 'Each slot needs skills and part of someone\'s week. "Suggest a team" finds the best people for all open slots at once.',
        S.user.manager ? h('button', { class: 'btn primary', onclick: () => projectModal(null, (p) => { location.hash = '#/projects/' + p.id; }) }, '+ New project') : null),
      h('div', { class: 'grid g3' }, d.projects.map(p => h('a', { class: 'card', href: '#/projects/' + p.id, style: { 'text-decoration': 'none' } },
        h('div', { class: 'row between' }, h('h2', { style: { margin: 0 } }, p.name), chip(p.status.toLowerCase(), p.status === 'ACTIVE' ? 'ok' : p.status === 'PLANNED' ? 'warn' : '')),
        h('div', { class: 'small muted' }, (p.client || '') + ' · ' + fmtDate(p.start) + ' → ' + fmtDate(p.end)),
        h('div', { class: 'row', style: { margin: '12px 0 6px' } }, h('div', { class: 'grow' }, bar(p.slots.length ? 100 * p.filled / p.slots.length : 0, p.filled === p.slots.length ? 'ok' : 'accent')),
          h('span', { class: 'mono small' }, p.filled + '/' + p.slots.length + ' slots')),
        h('div', { class: 'row tight' }, p.slots.filter(s => s.assigneeId).map(s => avatar(s.assigneeId)), h('span', { class: 'small muted grow right' }, 'Owner: ' + ((S.person[p.ownerId] || {}).name || '–')))))),
      reload ? null : null);
  }

  function projectModal(p, done) {
    const name = h('input', { type: 'text', value: p ? p.name : '', required: true, maxlength: '60' });
    const client = h('input', { type: 'text', value: p ? p.client || '' : '', maxlength: '60' });
    const start = h('input', { type: 'date', value: p ? p.start : S.meta.today, required: true });
    const end = h('input', { type: 'date', value: p ? p.end : '', required: true });
    const status = h('select', null, ['PLANNED', 'ACTIVE', 'DONE'].map(s => h('option', { value: s, selected: p ? p.status === s : s === 'PLANNED' }, s.toLowerCase())));
    const err = h('div', { class: 'error' });
    let close;
    close = modal(p ? 'Edit project' : 'New project', h('form', { class: 'form', onsubmit: async (e) => {
      e.preventDefault();
      const b = { name: name.value, client: client.value, start: start.value, end: end.value, status: status.value };
      try { const r = await act(() => p ? put('/api/projects/' + p.id, b) : post('/api/projects', b), 'Saved.', err); close(); done(r); } catch (x) { /* shown */ }
    } }, h('label', { class: 'f' }, 'Name', name), h('label', { class: 'f' }, 'Client', client),
      h('div', { class: 'two' }, h('label', { class: 'f' }, 'Start', start), h('label', { class: 'f' }, 'End', end)),
      h('label', { class: 'f' }, 'Status', h('span', { class: 'hint' }, 'planned and active projects use people\'s time'), status), err,
      h('div', { class: 'row' }, h('button', { class: 'btn primary', type: 'submit' }, 'Save'))));
  }

  /** Editor for a list of skill requirements: [{skillId, level, must, weight}] */
  function reqEditor(reqs) {
    const rows = h('div', { class: 'stack' });
    const list = reqs.map(r => Object.assign({}, r));
    const opts = (sel) => sortBy(S.meta.skills.filter(s => s.active), s => s.name).map(s => h('option', { value: s.id, selected: s.id === sel }, s.name));
    const draw = () => {
      clear(rows);
      list.forEach((r, i) => rows.appendChild(h('div', { class: 'row tight' },
        h('select', { 'aria-label': 'Skill', onchange: (e) => { r.skillId = e.target.value; } }, opts(r.skillId)),
        h('select', { 'aria-label': 'Level', onchange: (e) => { r.level = +e.target.value; } }, [1, 2, 3, 4, 5].map(l => h('option', { value: l, selected: l === r.level }, 'level ' + l))),
        h('select', { 'aria-label': 'Weight', onchange: (e) => { r.weight = +e.target.value; } }, [1, 2, 3, 4, 5].map(l => h('option', { value: l, selected: l === r.weight }, 'weight ' + l))),
        h('label', { class: 'check small' }, h('input', { type: 'checkbox', checked: r.must, onchange: (e) => { r.must = e.target.checked; } }), 'must'),
        h('button', { class: 'btn small ghost', type: 'button', onclick: () => { list.splice(i, 1); draw(); }, 'aria-label': 'Remove' }, '✕'))));
      rows.appendChild(h('button', { class: 'btn small', type: 'button', onclick: () => { list.push({ skillId: S.meta.skills.find(s => s.active).id, level: 3, must: false, weight: 1 }); draw(); } }, '+ Skill'));
    };
    draw();
    return { el: rows, value: () => list };
  }

  function slotModal(p, slot, done) {
    const title = h('input', { type: 'text', value: slot ? slot.title : '', required: true, maxlength: '50', placeholder: 'e.g. Backend developer' });
    const pct = h('select', null, [10, 20, 30, 40, 50, 60, 70, 80, 90, 100].map(v => h('option', { value: v, selected: v === (slot ? slot.pct : 100) }, v + '%')));
    const re = reqEditor(slot ? slot.reqs : [{ skillId: S.meta.skills[0].id, level: 3, must: true, weight: 3 }]);
    const err = h('div', { class: 'error' });
    let close;
    close = modal(slot ? 'Edit slot' : 'New slot', h('form', { class: 'form', onsubmit: async (e) => {
      e.preventDefault();
      const b = { title: title.value, pct: +pct.value, reqs: re.value() };
      try { await act(() => slot ? put('/api/projects/' + p.id + '/slots/' + slot.id, b) : post('/api/projects/' + p.id + '/slots', b), 'Saved.', err); close(); done(); } catch (x) { /* shown */ }
    } }, h('div', { class: 'two' }, h('label', { class: 'f' }, 'Title', title), h('label', { class: 'f' }, 'Share of the week', pct)),
      h('div', null, h('div', { class: 'f' }, 'Skills needed'), re.el), err,
      h('div', { class: 'row' }, h('button', { class: 'btn primary', type: 'submit' }, 'Save'))), { wide: true });
  }

  async function assign(p, slotId, userId, done) {
    try {
      await post('/api/projects/' + p.id + '/slots/' + slotId + '/assign', { userId, stretch: false });
      toast('Staffed.');
      done();
    } catch (e) {
      if (e.data && e.data.stretchPossible) {
        if (confirm(e.message)) { await act(() => post('/api/projects/' + p.id + '/slots/' + slotId + '/assign', { userId, stretch: true }), 'Staffed as a stretch role.'); done(); }
      } else toast(e.message, true);
    }
  }

  async function projectPage(id) {
    const p = await get('/api/projects/' + id);
    const reload = () => route();
    const sugBox = h('div');
    const slots = p.slots.map(s => h('div', { class: 'slot' },
      h('div', null, h('div', { class: 'row tight' }, h('b', null, s.title), chip(s.pct + '%'), s.stretch ? chip('stretch role', 'warn') : null),
        reqChips(s.reqs),
        s.assigneeId ? h('div', { class: 'row tight', style: { 'margin-top': '8px' } }, who(s.assigneeId), chip('fit ' + s.fit + '%', s.mustMissing ? 'warn' : 'ok'))
          : h('div', { class: 'muted small', style: { 'margin-top': '6px' } }, 'Open – nobody yet')),
      p.canEdit ? h('div', { class: 'row tight', style: { 'align-self': 'start' } },
        h('button', { class: 'btn small ghost', onclick: () => slotModal(p, s, reload) }, 'Edit'),
        s.assigneeId ? h('button', { class: 'btn small ghost', onclick: async () => { await act(() => post('/api/projects/' + p.id + '/slots/' + s.id + '/unassign'), 'Unassigned.'); reload(); } }, 'Unassign') : null,
        h('button', { class: 'btn small ghost', onclick: async () => { if (confirm('Delete this slot?')) { await act(() => del('/api/projects/' + p.id + '/slots/' + s.id), 'Deleted.'); reload(); } } }, 'Delete')) : h('span')));

    const suggest = async () => {
      clear(sugBox).appendChild(loading());
      const r = await act(() => get('/api/projects/' + p.id + '/suggest'));
      const open = r.slots.length;
      clear(sugBox).appendChild(card('Suggested team',
        open ? h('p', null, 'The best team fills ', h('b', null, r.filled + ' of ' + open), ' open slots with a total fit of ', h('b', null, r.total), '. ',
          r.greedyFilled < r.filled || r.greedyTotal < r.total ? h('span', null, 'Picking the best person one slot at a time would fill only ', h('b', null, r.greedyFilled), ' (total ' + r.greedyTotal + ') – the ', h('i', null, 'Hungarian algorithm'), ' looks at all slots together.') : 'The same as picking one slot at a time.',
          h('span', { class: 'muted small' }, ' (' + r.candidates + ' people looked at; only people with enough free time and every must-have skill can be picked.)')) : empty('All slots are filled.'),
        r.slots.map(sl => h('div', { class: 'slot' },
          h('div', null, h('div', { class: 'row tight' }, h('b', null, sl.title), chip(sl.pct + '%')),
            sl.pick ? h('div', { class: 'pick', style: { 'margin-top': '8px' } }, h('div', { class: 'row' }, who(sl.pick.userId, sl.pick.title), chip('fit ' + sl.pick.fit + '%', 'ok'), chip(sl.pick.free + '% free')),
              sl.pick.gaps.length ? h('div', { class: 'small muted', style: { 'margin-top': '4px' } }, 'Gaps: ' + sl.pick.gaps.map(g => skillName(g.skillId) + ' ' + g.have + '/' + g.need).join(', ')) : null)
              : h('p', { class: 'error small' }, 'Nobody has the must-have skills and the free time.'),
            sl.others.length ? h('details', { style: { 'margin-top': '6px' } }, h('summary', { class: 'small' }, 'Other people'), sl.others.map(o => h('div', { class: 'row between', style: { padding: '4px 0' } },
              who(o.userId, o.eligible ? o.free + '% free' : (o.mustMissing ? 'missing a must-have' : 'not enough free time')),
              h('span', { class: 'row tight' }, chip('fit ' + o.fit + '%', o.eligible ? '' : 'warn'), p.canEdit ? h('button', { class: 'btn small', onclick: () => assign(p, sl.slotId, o.userId, reload) }, 'Assign') : null)))) : null),
          sl.pick && p.canEdit ? h('button', { class: 'btn small primary', style: { 'align-self': 'start' }, onclick: () => assign(p, sl.slotId, sl.pick.userId, reload) }, 'Assign') : h('span'))),
        open && p.canEdit && r.filled ? h('button', { class: 'btn primary', onclick: async () => {
          for (const sl of r.slots) if (sl.pick) await act(() => post('/api/projects/' + p.id + '/slots/' + sl.slotId + '/assign', { userId: sl.pick.userId, stretch: false }));
          toast('Team staffed.'); reload();
        } }, 'Assign the whole team') : null));
    };

    return h('div', null,
      pageHead((p.client || 'Project') + ' · ' + p.status.toLowerCase(), p.name, fmtDate(p.start) + ' → ' + fmtDate(p.end) + ' · owner ' + ((S.person[p.ownerId] || {}).name || '–'),
        p.canEdit ? h('button', { class: 'btn', onclick: () => projectModal(p, reload) }, 'Edit') : null,
        p.canEdit ? h('button', { class: 'btn', onclick: () => slotModal(p, null, reload) }, '+ Slot') : null,
        p.canEdit && p.slots.some(s => !s.assigneeId) ? h('button', { class: 'btn primary', onclick: suggest }, 'Suggest a team') : null),
      h('div', { class: 'grid g2' }, card(plural(p.slots.length, 'slot') + ' · ' + p.filled + ' filled', p.slots.length ? slots : empty('No slots yet.')), sugBox));
  }

  // ================================================================== REPORTS

  function lineChart(labels, series, height, total) {
    const W = 640, H = height || 220, L = 34, R = 10, T = 12, B = 26;
    const all = series.flatMap(s => s.values).concat(total ? total.values : []);
    const max = Math.max(1, ...all);
    const nice = max <= 5 ? 5 : Math.ceil(max / 5) * 5;
    const x = (i) => L + (labels.length < 2 ? 0 : i * (W - L - R) / (labels.length - 1));
    const y = (v) => T + (H - T - B) * (1 - v / nice);
    const g = sv('svg', { viewBox: '0 0 ' + W + ' ' + H, role: 'img', 'aria-label': 'line chart' });
    for (let k = 0; k <= 5; k++) {
      const v = nice * k / 5;
      g.appendChild(sv('line', { x1: L, x2: W - R, y1: y(v), y2: y(v), class: 'axis', 'stroke-width': k ? 0.5 : 1 }));
      g.appendChild(sv('text', { x: L - 6, y: y(v) + 3, 'text-anchor': 'end', class: 'lbl' }, Math.round(v)));
    }
    labels.forEach((l, i) => { if (labels.length <= 13 || i % 2 === 0) g.appendChild(sv('text', { x: x(i), y: H - 8, 'text-anchor': 'middle', class: 'lbl' }, fmtMonth(l).split(' ')[0])); });
    const line = (vals, cls, w) => {
      g.appendChild(sv('polyline', { points: vals.map((v, i) => x(i) + ',' + y(v)).join(' '), fill: 'none', class: cls, 'stroke-width': w || 2.5, 'stroke-linejoin': 'round' }));
    };
    series.forEach((s, i) => {
      line(s.values, 's' + i);
      const last = s.values.length - 1;
      g.appendChild(sv('circle', { cx: x(last), cy: y(s.values[last]), r: 3.5, class: 'f' + i }));
    });
    if (total) line(total.values, 'total', 1.5);
    return h('div', { class: 'chart' }, g,
      series.length > 1 || total ? h('div', { class: 'legend small' }, series.map((s, i) => h('span', { class: 'k' }, h('span', { class: 'keyline s' + i }), s.name)),
        total ? h('span', { class: 'k' }, h('span', { class: 'keyline total' }), total.name) : null) : null);
  }

  async function reportsPage() {
    const r = await get('/api/reports');
    const kpi = (v, label, cls) => h('div', { class: 'card kpi' }, h('b', { style: { color: cls ? 'var(--' + cls + ')' : null } }, v), h('span', null, label));
    const labels = r.trend.map(t => t.month);
    const trend = card('Skills over the year', h('p', { class: 'small muted' }, 'People at level 3+ on the first day of each month, for the skills most needed now.'),
      labels.length > 1 ? lineChart(labels, r.trendSkills.map(sid => ({ name: skillName(sid), values: r.trend.map(t => t.values[sid] || 0) }))) : empty('The trend starts after the first monthly snapshot.'));
    const totalTrend = card('All verified skills at level 3+', labels.length > 1 ? lineChart(labels, [], 160, { name: 'skill levels at 3+, whole scope', values: r.trend.map(t => t.total) }) : empty('–'));
    const maxInv = Math.max(1, ...r.inventory.map(i => i.practitioners));
    const inventory = card('Inventory by group', h('div', { class: 'list' }, r.inventory.map(i => {
      const c = S.cat[i.categoryId];
      return h('div', { class: 'gap-line', style: { 'grid-template-columns': '130px 1fr 60px' } }, h('span', { class: 'row tight small' }, h('span', { class: 'sw ' + c.group, style: { width: '12px', height: '12px', border: '1.5px solid var(--ink)', display: 'inline-block' } }), c.name),
        bar(100 * i.practitioners / maxInv), h('span', { class: 'mono small right' }, i.practitioners));
    })), h('p', { class: 'tiny muted' }, 'Number of (person, skill) pairs at level 3+.'));
    const shortages = card('Shortages', h('table', { class: 't' },
      h('thead', null, h('tr', null, h('th', null, 'Skill'), h('th', { class: 'right' }, 'Gaps in current roles'), h('th', { class: 'right' }, 'Open slots'), h('th', { class: 'right' }, 'People at 3+'))),
      h('tbody', null, r.shortages.map(s => h('tr', null, h('td', null, h('div', { class: 'row tight', style: { 'flex-wrap': 'nowrap' } }, tile(s.skillId, { size: 'mini', onclick: () => skillPanel(s.skillId) }), skillName(s.skillId))),
        h('td', { class: 'right mono' }, s.roleGaps), h('td', { class: 'right mono' }, s.openSlots), h('td', { class: 'right mono' }, s.supply))))));
    const risks = card('Bus-factor risks', r.risks.length ? h('div', null, r.risks.map(x => h('div', { class: 'gap-line' }, tile(x.skillId, { size: 'mini' }),
      h('div', null, h('b', null, skillName(x.skillId)), h('div', { class: 'small muted' }, x.count ? ['Only ', x.holders.map(personLink), ' at 3+'] : 'Nobody at level 3+')),
      chip('needed by ' + x.neededBy, x.count ? 'warn' : 'bad')))) : empty('No risks.'));
    const certs = card('Certificates', h('div', { class: 'row', style: { 'margin-bottom': '10px' } }, chip(r.certs.valid + ' valid', 'ok'), chip(r.certs.expiring + ' expiring', 'warn'), chip(r.certs.expired + ' expired', 'bad')),
      r.certs.soon.length ? h('table', { class: 't' }, h('tbody', null, r.certs.soon.map(c => h('tr', null, h('td', null, personLink(c.userId)), h('td', null, c.name), h('td', { class: 'nowrap' }, certChip(c)))))) : empty('Nothing expires in the next 90 days.'));
    return h('div', null,
      pageHead(r.scope === 'company' ? 'Whole company' : 'Your team', 'Reports', plural(r.people, 'person') + ' · levels are verified and after decay'),
      h('div', { class: 'grid g4', style: { 'margin-bottom': '16px' } },
        kpi(r.readyInRole + '/' + r.withRole, 'ready in their current role'),
        kpi((isNaN(r.readinessMedian) || r.readinessMedian === null ? '–' : r.readinessMedian + '%'), 'median readiness for their role'),
        kpi(hours(r.verify.medianHours), 'median time to check a claim (85%: ' + hours(r.verify.p85Hours) + ')'),
        kpi(r.verify.pending, 'claims waiting · oldest ' + hours(r.verify.oldestHours), r.verify.oldestHours > 168 ? 'warn' : null),
        kpi(r.goals.open, 'open learning goals'),
        kpi(r.goals.overdue, 'goals overdue', r.goals.overdue ? 'bad' : null),
        kpi(r.goals.done, 'goals done in 90 days', 'ok'),
        kpi(r.certs.expiring + r.certs.expired, 'certificates to renew', r.certs.expired ? 'bad' : null)),
      h('div', { class: 'grid g2' }, h('div', { class: 'span2' }, trend), shortages, risks, inventory, certs, h('div', { class: 'span2' }, totalTrend)));
  }

  // ================================================================== HR DESK

  async function hrPage() {
    const tab = params().get('tab') || 'people';
    const tabs = h('div', { class: 'tabs', role: 'tablist' }, [['people', 'People'], ['skills', 'Skill catalogue'], ['roles', 'Job roles'], ['import', 'Import CSV'], ['log', 'Activity log']].map(([k, label]) =>
      h('button', { role: 'tab', 'aria-selected': String(tab === k), onclick: () => { location.hash = '#/hr?tab=' + k; } }, label)));
    const reload = async () => { await reloadMeta(); route(); };
    let body;
    if (tab === 'people' || tab === 'log') {
      const d = await get('/api/admin');
      if (tab === 'people') {
        body = card(h('div', { class: 'row between', style: { width: '100%' } }, h('h2', null, plural(d.people.length, 'person')), h('button', { class: 'btn primary', onclick: () => userModal(null, reload) }, '+ Person')),
          h('table', { class: 't' }, h('thead', null, h('tr', null, ['Name', 'Username', 'Access', 'Job role', 'Manager', 'Last seen', ''].map(x => h('th', null, x)))),
            h('tbody', null, sortBy(d.people, p => p.name).map(p => h('tr', null,
              h('td', null, h('div', { class: 'row tight' }, h('span', { class: 'avatar' }, p.initials), h('div', null, h('a', { href: '#/people/' + p.id }, p.name), h('div', { class: 'tiny muted' }, p.title || '')))),
              h('td', { class: 'mono small' }, p.username), h('td', null, chip(p.role.toLowerCase(), p.role === 'ADMIN' ? 'ink' : ''), !p.active ? chip('off', 'bad') : null, p.locked ? chip('locked', 'warn') : null),
              h('td', { class: 'small' }, roleName(p.jobRoleId)), h('td', { class: 'small' }, p.managerId ? (S.person[p.managerId] || {}).name : '–'),
              h('td', { class: 'small muted' }, p.lastSeen ? ago(p.lastSeen) : 'never'),
              h('td', { class: 'right' }, h('button', { class: 'btn small ghost', onclick: () => userModal(p, reload) }, 'Edit')))))));
      } else {
        body = card('Activity log (newest first)', h('table', { class: 't' }, h('thead', null, h('tr', null, ['When', 'Who', 'What', 'Detail'].map(x => h('th', null, x)))),
          h('tbody', null, d.audit.map(a => h('tr', null, h('td', { class: 'small nowrap' }, ago(a.at)), h('td', { class: 'small' }, a.userId ? (S.person[a.userId] || {}).name || a.userId : '–'),
            h('td', { class: 'mono small' }, a.action), h('td', { class: 'small' }, [a.target, a.detail].filter(Boolean).join(' · ')))))));
      }
    } else if (tab === 'skills') {
      const catForm = (() => {
        const n = h('input', { type: 'text', placeholder: 'New group name', maxlength: '40' });
        const g = h('select', null, S.meta.groups.map(x => h('option', { value: x }, x)));
        const hl = h('input', { type: 'number', min: '3', max: '120', value: '18', style: { width: '80px' }, 'aria-label': 'half-life months' });
        return h('form', { class: 'row', onsubmit: async (e) => { e.preventDefault(); await act(() => post('/api/catalog/categories', { name: n.value, group: g.value, halfLife: +hl.value }), 'Group added.'); reload(); } },
          n, g, hl, h('span', { class: 'small muted' }, 'months half-life'), h('button', { class: 'btn small', type: 'submit' }, '+ Group'));
      })();
      body = card(h('div', { class: 'row between', style: { width: '100%' } }, h('h2', null, plural(S.meta.skills.length, 'skill')), h('button', { class: 'btn primary', onclick: () => skillModal(null, reload) }, '+ Skill')),
        catForm,
        h('table', { class: 't', style: { 'margin-top': '12px' } }, h('thead', null, h('tr', null, ['', 'Skill', 'Group', 'Other names', 'Half-life', 'Learn first', ''].map(x => h('th', null, x)))),
          h('tbody', null, sortBy(S.meta.skills, s => s.number).map(s => h('tr', { class: s.active ? '' : 'muted' },
            h('td', null, tile(s.id, { size: 'mini' })), h('td', null, h('b', null, s.name), h('div', { class: 'tiny muted' }, s.description)),
            h('td', { class: 'small' }, S.cat[s.categoryId].name), h('td', { class: 'small' }, s.synonyms.join(', ')),
            h('td', { class: 'small mono' }, s.halfLifeUsed + ' mo' + (s.halfLife ? '' : ' (group)')),
            h('td', { class: 'small' }, Object.entries(s.prereqs).map(([k, v]) => S.skill[k].symbol + ' ' + v).join(', ')),
            h('td', { class: 'right nowrap' }, h('button', { class: 'btn small ghost', onclick: () => skillModal(s, reload) }, 'Edit'),
              h('button', { class: 'btn small ghost', onclick: async () => { await act(() => post('/api/catalog/skills/' + s.id + '/active', { active: !s.active }), s.active ? 'Retired.' : 'Back in the catalogue.'); reload(); } }, s.active ? 'Retire' : 'Restore')))))));
    } else if (tab === 'roles') {
      body = card(h('div', { class: 'row between', style: { width: '100%' } }, h('h2', null, plural(S.meta.roles.length, 'job role')), h('button', { class: 'btn primary', onclick: () => roleModal(null, reload) }, '+ Role')),
        h('table', { class: 't' }, h('thead', null, h('tr', null, ['Role', 'Ladder', 'Skills needed (! = must)', ''].map(x => h('th', null, x)))),
          h('tbody', null, sortBy(S.meta.roles, r => r.ladder + r.step).map(r => h('tr', null, h('td', null, h('b', null, r.name), h('div', { class: 'tiny muted' }, r.description)),
            h('td', { class: 'small nowrap' }, r.ladder + ' · step ' + r.step), h('td', null, reqChips(r.reqs)),
            h('td', { class: 'right' }, h('button', { class: 'btn small ghost', onclick: () => roleModal(r, reload) }, 'Edit')))))));
    } else {
      const ta = h('textarea', { style: { 'min-height': '180px', 'font-family': 'var(--font-mono)', 'font-size': '12.5px' } },
        'username,skill,level,last used,verified\nsanjay,Kafka,3,2026-10,no\nkaran,react,Practitioner,2026-11,yes\nnisha,Power BI,3,,no\nnobody,Java,3\n');
      const out = h('div');
      const runIt = async (dry) => {
        const r = await act(() => post('/api/admin/import', { csv: ta.value, dryRun: dry }));
        clear(out).appendChild(h('div', { class: 'stack' }, h('div', { class: 'row' }, chip(r.ok + ' good', 'ok'), chip(r.bad + ' refused', r.bad ? 'bad' : ''), chip(r.verified + ' verified by HR'), dry ? chip('dry run – nothing changed', 'warn') : chip('imported', 'ink')),
          h('table', { class: 't' }, h('tbody', null, r.lines.map(l => h('tr', null, h('td', { class: 'mono small' }, 'line ' + l.line), h('td', null, l.ok ? '✓' : '✕'), h('td', { class: l.ok ? 'small' : 'small error' }, l.message)))))));
        if (!dry) await reloadMeta();
      };
      body = card('Import skills from a spreadsheet', h('p', { class: 'small muted' }, 'Columns: username, skill (name, symbol or other name), level (1-5 or Aware…Expert), last used (yyyy-mm, optional), verified (yes/no). Lines marked "yes" count as checked by HR; the others wait for the manager. Always check first.'),
        ta, h('div', { class: 'row', style: { margin: '10px 0' } }, h('button', { class: 'btn', onclick: () => runIt(true) }, 'Check (dry run)'), h('button', { class: 'btn primary', onclick: () => { if (confirm('Import these lines?')) runIt(false); } }, 'Import')), out);
    }
    return h('div', null, pageHead('HR desk', 'People, catalogue and roles', 'Only HR (admin) sees this page. Every change goes to the activity log.'), tabs, body);
  }

  function userModal(p, done) {
    const f = {
      username: h('input', { type: 'text', value: p ? p.username : '', disabled: !!p, maxlength: '30' }),
      name: h('input', { type: 'text', value: p ? p.name : '', required: true, maxlength: '60' }),
      title: h('input', { type: 'text', value: p ? p.title || '' : '', maxlength: '60' }),
      dept: h('input', { type: 'text', value: p ? p.dept || '' : '', maxlength: '40' }),
      location: h('input', { type: 'text', value: p ? p.location || '' : '', maxlength: '40' }),
      role: h('select', null, ['EMPLOYEE', 'MANAGER', 'ADMIN'].map(r => h('option', { value: r, selected: p ? p.role === r : r === 'EMPLOYEE' }, r.toLowerCase()))),
      managerId: h('select', null, h('option', { value: '' }, '– nobody –'), sortBy(Object.values(S.person), x => x.name).filter(x => !p || x.id !== p.id).map(x => h('option', { value: x.id, selected: p && p.managerId === x.id }, x.name))),
      jobRoleId: h('select', null, h('option', { value: '' }, '– none –'), sortBy(Object.values(S.role), r => r.name).map(r => h('option', { value: r.id, selected: p && p.jobRoleId === r.id }, r.name))),
      password: h('input', { type: 'password', autocomplete: 'new-password', placeholder: p ? 'leave empty to keep' : '8+ characters' }),
      active: h('input', { type: 'checkbox', checked: p ? p.active : true }),
    };
    const err = h('div', { class: 'error' });
    let close;
    close = modal(p ? 'Edit ' + p.name : 'Add a person', h('form', { class: 'form', onsubmit: async (e) => {
      e.preventDefault();
      const b = {};
      for (const [k, el] of Object.entries(f)) b[k] = el.type === 'checkbox' ? el.checked : el.value;
      if (p) delete b.username;
      if (!b.password) delete b.password;
      try { await act(() => p ? put('/api/admin/users/' + p.id, b) : post('/api/admin/users', b), 'Saved.', err); close(); done(); } catch (x) { /* shown */ }
    } }, h('div', { class: 'two' }, h('label', { class: 'f' }, 'Username', f.username), h('label', { class: 'f' }, 'Full name', f.name)),
      h('div', { class: 'two' }, h('label', { class: 'f' }, 'Title', f.title), h('label', { class: 'f' }, 'Department', f.dept)),
      h('div', { class: 'two' }, h('label', { class: 'f' }, 'Location', f.location), h('label', { class: 'f' }, 'Access', f.role)),
      h('div', { class: 'two' }, h('label', { class: 'f' }, 'Manager', h('span', { class: 'hint' }, 'checks their skills first'), f.managerId), h('label', { class: 'f' }, 'Job role', f.jobRoleId)),
      h('div', { class: 'two' }, h('label', { class: 'f' }, 'Password', f.password), h('label', { class: 'check' }, f.active, 'Account is on')),
      err, h('div', { class: 'row' }, h('button', { class: 'btn primary', type: 'submit' }, 'Save'))), { wide: true });
  }

  function skillModal(s, done) {
    const f = {
      name: h('input', { type: 'text', value: s ? s.name : '', required: true, maxlength: '40' }),
      symbol: h('input', { type: 'text', value: s ? s.symbol : '', required: true, maxlength: '3', placeholder: 'Jv', style: { 'font-family': 'var(--font-mono)' } }),
      categoryId: h('select', null, sortBy(Object.values(S.cat), c => c.order).map(c => h('option', { value: c.id, selected: s && s.categoryId === c.id }, c.name))),
      description: h('input', { type: 'text', value: s ? s.description || '' : '', maxlength: '300' }),
      synonyms: h('input', { type: 'text', value: s ? s.synonyms.join(', ') : '', placeholder: 'other names, comma separated' }),
      halfLife: h('input', { type: 'number', min: '0', max: '120', value: s ? s.halfLife : 0 }),
    };
    const pre = reqEditor(s ? Object.entries(s.prereqs).map(([k, v]) => ({ skillId: k, level: v, must: true, weight: 1 })) : []);
    const err = h('div', { class: 'error' });
    let close;
    close = modal(s ? 'Edit ' + s.name : 'Add a skill', h('form', { class: 'form', onsubmit: async (e) => {
      e.preventDefault();
      const prereqs = {};
      for (const r of pre.value()) prereqs[r.skillId] = r.level;
      const b = { name: f.name.value, symbol: f.symbol.value, categoryId: f.categoryId.value, description: f.description.value,
        synonyms: f.synonyms.value.split(',').map(x => x.trim()).filter(Boolean), halfLife: +f.halfLife.value, prereqs };
      try { await act(() => s ? put('/api/catalog/skills/' + s.id, b) : post('/api/catalog/skills', b), 'Saved.', err); close(); done(); } catch (x) { /* shown */ }
    } }, h('div', { class: 'two' }, h('label', { class: 'f' }, 'Name', f.name), h('label', { class: 'f' }, 'Symbol', h('span', { class: 'hint' }, '1–3 characters, capital first'), f.symbol)),
      h('div', { class: 'two' }, h('label', { class: 'f' }, 'Group', f.categoryId), h('label', { class: 'f' }, 'Half-life (months)', h('span', { class: 'hint' }, '0 = use the group\'s'), f.halfLife)),
      h('label', { class: 'f' }, 'Description', f.description), h('label', { class: 'f' }, 'Other names', h('span', { class: 'hint' }, 'used by search and import, e.g. "js"'), f.synonyms),
      h('div', null, h('div', { class: 'f' }, 'Learn first (prerequisites)', h('span', { class: 'hint' }, ' – loops are refused; the "must" and "weight" boxes are ignored here')), pre.el),
      err, h('div', { class: 'row' }, h('button', { class: 'btn primary', type: 'submit' }, 'Save'))), { wide: true });
  }

  function roleModal(r, done) {
    const name = h('input', { type: 'text', value: r ? r.name : '', required: true, maxlength: '60' });
    const ladder = h('input', { type: 'text', value: r ? r.ladder || '' : '', maxlength: '30', placeholder: 'e.g. Backend' });
    const step = h('input', { type: 'number', min: '1', max: '6', value: r ? r.step : 1 });
    const desc = h('input', { type: 'text', value: r ? r.description || '' : '', maxlength: '300' });
    const re = reqEditor(r ? r.reqs : []);
    const err = h('div', { class: 'error' });
    let close;
    close = modal(r ? 'Edit ' + r.name : 'Add a job role', h('form', { class: 'form', onsubmit: async (e) => {
      e.preventDefault();
      const b = { name: name.value, ladder: ladder.value, step: +step.value, description: desc.value, reqs: re.value() };
      try { await act(() => r ? put('/api/roles/' + r.id, b) : post('/api/roles', b), 'Saved.', err); close(); done(); } catch (x) { /* shown */ }
    } }, h('label', { class: 'f' }, 'Name', name), h('div', { class: 'two' }, h('label', { class: 'f' }, 'Career ladder', ladder), h('label', { class: 'f' }, 'Step on the ladder', step)),
      h('label', { class: 'f' }, 'Description', desc), h('div', null, h('div', { class: 'f' }, 'Skills needed'), re.el),
      err, h('div', { class: 'row' }, h('button', { class: 'btn primary', type: 'submit' }, 'Save'))), { wide: true });
  }

  // ================================================================== INBOX

  async function inboxPage() {
    const notes = await get('/api/notes');
    const page = h('div', null,
      pageHead('Notifications', 'Inbox', null, notes.some(n => !n.read) ? h('button', { class: 'btn', onclick: async () => { await act(() => post('/api/notes/read', {}), 'All marked as read.'); S.unread = 0; route(); } }, 'Mark all as read') : null),
      card(null, notes.length ? h('div', { class: 'list' }, notes.map(n => h('div', { class: 'item' },
        h('div', { class: 'grow' }, h('div', { class: n.read ? '' : 'unread' }, n.text), h('div', { class: 'tiny muted' }, ago(n.at))),
        n.link ? h('a', { class: 'btn small', href: n.link, onclick: () => { if (!n.read) quiet(post('/api/notes/read', { id: n.id })); } }, 'Open') : null))) : empty('No notifications.')));
    return page;
  }

  // ================================================================== start

  async function start() {
    try {
      const r = await get('/api/me');
      if (r.user) { S.user = r.user; setMeta(r.meta); route(); } else showLogin();
    } catch (e) {
      if (e.status !== 401) showLogin(e.message);
    }
    setInterval(() => quiet(refreshCounts()), 60000);
  }
  start();
})();
