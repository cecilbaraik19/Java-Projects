/* ==========================================================================
   Smart Learning Difficulty Detector - frontend
   Plain JavaScript, no libraries. Talks to the Java backend at /api/...
   All text from the server is escaped with esc() before it goes into the page.
   No inline scripts or styles (the server's Content-Security-Policy blocks them):
   bar widths are set from data-w attributes after each page is drawn.
   ========================================================================== */
(function () {
  'use strict';

  // ------------------------------------------------------------------ helpers
  var $ = function (s, r) { return (r || document).querySelector(s); };
  var $$ = function (s, r) { return Array.prototype.slice.call((r || document).querySelectorAll(s)); };

  function esc(v) {
    return String(v == null ? '' : v)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }
  function pct(r) { return r == null || r < 0 ? '—' : Math.round(r * 100) + '%'; }
  function di(v) { return v == null || v < 0 ? '—' : (Math.round(v * 10) / 10).toFixed(0); }
  var MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
  function niceDate(s) {
    if (!s) return '';
    var d = s.slice(0, 10).split('-');
    return parseInt(d[2], 10) + ' ' + MONTHS[parseInt(d[1], 10) - 1] + ' ' + d[0];
  }
  function niceTime(s) {
    if (!s) return '';
    return niceDate(s) + (s.length > 10 ? ', ' + s.slice(11, 16) : '');
  }
  function shortDate(s) {
    if (!s) return '';
    var d = s.slice(0, 10).split('-');
    return parseInt(d[2], 10) + ' ' + MONTHS[parseInt(d[1], 10) - 1];
  }
  function plural(n, w) { return n + ' ' + w + (n === 1 ? '' : 's'); }

  var BANDS = {
    NO_DATA: { label: 'Not enough data', zone: 'Not surveyed' },
    EASY: { label: 'Easy', zone: 'Valley' },
    MODERATE: { label: 'Moderate', zone: 'Foothills' },
    DIFFICULT: { label: 'Difficult', zone: 'Highlands' },
    CRITICAL: { label: 'Very difficult', zone: 'Summit' }
  };
  var BAND_ORDER = ['EASY', 'MODERATE', 'DIFFICULT', 'CRITICAL'];
  function bandOf(index) {
    var l = META ? META.limits : [30, 50, 70];
    if (index == null || index < 0) return 'NO_DATA';
    if (index >= l[2]) return 'CRITICAL';
    if (index >= l[1]) return 'DIFFICULT';
    if (index >= l[0]) return 'MODERATE';
    return 'EASY';
  }
  function zoneTag(band, index) {
    var b = BANDS[band] || BANDS.NO_DATA;
    return '<span class="zone z-' + band + '">' + (index != null && index >= 0 ? '<span class="di">' + di(index) + '</span> ' : '') + esc(b.label) + '</span>';
  }
  function subj(code, color) { return '<span class="subj c-' + esc(color || 'moss') + '">' + esc(code) + '</span>'; }
  function bar(value, max, cls) {
    var w = max > 0 ? Math.max(0, Math.min(100, value / max * 100)) : 0;
    return '<div class="bar"><i class="' + (cls || '') + '" data-w="' + w.toFixed(1) + '"></i></div>';
  }
  var CAUSES = {
    WRONG: 'Many wrong answers', SLOW: 'Slow answers', HINTS: 'Needs hints', SURE_WRONG: 'Wrong while sure',
    SELF: 'Feels hard', DECLINE: 'Getting worse', PREREQ: 'Weak prerequisite'
  };
  var SIGNAL_NAMES = {
    WRONG: 'Wrong answers', SLOW: 'Slow answers', HINTS: 'Hints needed', SURE_WRONG: 'Wrong while sure',
    SELF: 'Says it is hard', DECLINE: 'Getting worse'
  };
  var CONF = { 1: 'Guessing', 2: 'Not sure', 3: 'Sure' };
  var WEIGHT_NAMES = {
    wWrong: 'Wrong answers', wSlow: 'Slow answers', wHints: 'Hints needed', wSureWrong: 'Wrong while sure',
    wSelf: 'Says it is hard', wDecline: 'Getting worse'
  };

  function toast(msg) {
    var t = $('#toast');
    t.textContent = msg;
    t.classList.add('show');
    clearTimeout(toast._t);
    toast._t = setTimeout(function () { t.classList.remove('show'); }, 2600);
  }

  // ------------------------------------------------------------------ API
  function api(method, url, body) {
    var opt = { method: method, credentials: 'same-origin', headers: {} };
    if (body !== undefined) {
      opt.headers['Content-Type'] = 'application/json';
      opt.body = JSON.stringify(body);
    }
    return fetch(url, opt).then(function (res) {
      return res.text().then(function (txt) {
        var data = null;
        try { data = txt ? JSON.parse(txt) : null; } catch (e) { data = null; }
        if (res.status === 401 && url !== '/api/login') { showLogin(); throw new Error('Please sign in.'); }
        if (!res.ok) throw new Error((data && data.error) || ('Request failed (' + res.status + ')'));
        return data;
      });
    });
  }

  // ------------------------------------------------------------------ theme
  function currentTheme() {
    var t = document.documentElement.getAttribute('data-theme');
    if (t) return t;
    return window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
  }
  function paintThemeButtons() {
    $$('[data-theme-toggle]').forEach(function (b) { b.textContent = currentTheme() === 'dark' ? 'Day map' : 'Night map'; });
  }
  function toggleTheme() {
    var next = currentTheme() === 'dark' ? 'light' : 'dark';
    document.documentElement.setAttribute('data-theme', next);
    try { localStorage.setItem('sld-theme', next); } catch (e) { /* ignore */ }
    paintThemeButtons();
  }

  // ------------------------------------------------------------------ state
  var ME = null;          // signed-in user
  var META = null;        // /api/me
  var state = {
    batch: '',
    subject: null,        // selected subject id on pages with subject tabs
    quiz: null,           // quiz in progress
    result: null,         // last quiz result
    planStatus: 'OPEN',
    bankTopic: ''
  };
  function prefs(key, value) {
    try {
      if (value === undefined) return localStorage.getItem('sld-' + key);
      localStorage.setItem('sld-' + key, value);
    } catch (e) { return null; }
    return null;
  }

  // ------------------------------------------------------------------ sign in
  function showLogin() {
    ME = null;
    $('#app').classList.add('hidden');
    $('#login').classList.remove('hidden');
    paintThemeButtons();
    setTimeout(function () { $('#lu').focus(); }, 30);
  }

  $('#loginForm').addEventListener('submit', function (e) {
    e.preventDefault();
    $('#loginErr').textContent = '';
    api('POST', '/api/login', { username: $('#lu').value.trim(), password: $('#lp').value })
      .then(function () { $('#lp').value = ''; return boot(); })
      .catch(function (err) { $('#loginErr').textContent = err.message; });
  });
  $$('.demo-row').forEach(function (b) {
    b.addEventListener('click', function () {
      var p = b.getAttribute('data-demo').split(':');
      $('#lu').value = p[0];
      $('#lp').value = p[1];
      $('#lp').focus();
    });
  });
  $('#logoutBtn').addEventListener('click', function () {
    api('POST', '/api/logout').catch(function () {}).then(function () { location.hash = ''; showLogin(); });
  });
  $('#who').addEventListener('click', changePassword);
  $('#menuBtn').addEventListener('click', function () {
    var open = $('#rail').classList.toggle('open');
    $('#menuBtn').setAttribute('aria-expanded', open ? 'true' : 'false');
  });

  function boot() {
    return api('GET', '/api/me').then(function (m) {
      META = m;
      ME = m.user;
      state.batch = prefs('batch') || '';
      if (state.batch && META.batches.indexOf(state.batch) < 0) state.batch = '';
      $('#login').classList.add('hidden');
      $('#app').classList.remove('hidden');
      $('#instName').textContent = m.institution;
      $('#who').textContent = ME.name.replace(/^(Dr\.|Prof\.|Mr\.|Ms\.|Mrs\.)\s+/, '').split(' ')[0] + ' · ' + ME.roleLabel;
      $('#foot').textContent = m.institution + ' · ' + m.department + ' · Survey date ' + niceDate(m.today);
      paintKey();
      paintNav();
      paintThemeButtons();
      route();
    });
  }

  function paintKey() {
    var l = META.limits;
    var rows = [
      ['EASY', 'Valley · Easy', '0–' + (l[0] - 1)],
      ['MODERATE', 'Foothills · Moderate', l[0] + '–' + (l[1] - 1)],
      ['DIFFICULT', 'Highlands · Difficult', l[1] + '–' + (l[2] - 1)],
      ['CRITICAL', 'Summit · Very difficult', l[2] + '+'],
      ['NO_DATA', 'Not surveyed yet', '']
    ];
    $('#keyList').innerHTML = rows.map(function (r) {
      return '<li><span class="sw z-' + r[0] + '"></span>' + esc(r[1]) + '<span class="rng">' + r[2] + '</span></li>';
    }).join('');
  }

  // ------------------------------------------------------------------ navigation
  var NAV = {
    STUDENT: [
      ['', [['home', 'A1', 'My map'], ['survey', 'A2', 'Take a survey'], ['route', 'A3', 'Study route'], ['history', 'B1', 'My attempts'], ['feel', 'B2', 'How it feels']]]
    ],
    TEACHER: [
      ['Class', [['home', 'A1', 'Overview'], ['heatmap', 'A2', 'Heat map'], ['topics', 'A3', 'Topics'], ['students', 'B1', 'Students']]],
      ['Help', [['plans', 'B2', 'Support plans'], ['attempts', 'B3', 'Attempts']]],
      ['Content', [['bank', 'C1', 'Question bank']]]
    ],
    ADMIN: [
      ['Class', [['home', 'A1', 'Overview'], ['heatmap', 'A2', 'Heat map'], ['topics', 'A3', 'Topics'], ['students', 'B1', 'Students']]],
      ['Help', [['plans', 'B2', 'Support plans'], ['attempts', 'B3', 'Attempts']]],
      ['Content', [['bank', 'C1', 'Question bank']]],
      ['Admin', [['users', 'D1', 'Logins'], ['settings', 'D2', 'Detector settings'], ['log', 'D3', 'History']]]
    ]
  };
  function paintNav() {
    $('#nav').innerHTML = NAV[ME.role].map(function (g) {
      return (g[0] ? '<div class="nav-group">' + esc(g[0]) + '</div>' : '') + g[1].map(function (i) {
        return '<a href="#/' + i[0] + '" data-nav="' + i[0] + '"><span class="grid">' + i[1] + '</span>' + esc(i[2]) + '</a>';
      }).join('');
    }).join('');
  }
  function markNav(name) {
    $$('#nav a').forEach(function (a) { a.classList.toggle('on', a.getAttribute('data-nav') === name); });
  }

  // ------------------------------------------------------------------ router
  var ROUTES = {};
  function route() {
    if (!ME) return;
    var h = location.hash.replace(/^#\/?/, '');
    var parts = h.split('/');
    var name = parts[0] || 'home';
    var fn = ROUTES[name];
    if (!fn || (fn.roles && fn.roles.indexOf(ME.role) < 0)) { name = 'home'; fn = ROUTES.home; }
    $('#rail').classList.remove('open');
    markNav(name === 'student' ? 'students' : name === 'topic' ? 'topics' : name === 'attempt' ? (ME.role === 'STUDENT' ? 'history' : 'attempts') : name === 'quiz' || name === 'result' ? 'survey' : name);
    $('#topTools').innerHTML = '';
    $('#view').innerHTML = '<p class="muted mono">Reading the survey…</p>';
    Promise.resolve(fn.apply(null, parts.slice(1))).catch(function (err) {
      $('#view').innerHTML = '<div class="empty">' + esc(err.message) + '</div>';
    });
  }
  function on(name, roles, fn) { fn.roles = roles; ROUTES[name] = fn; }
  window.addEventListener('hashchange', route);

  function draw(crumbs, html) {
    $('#crumbs').textContent = crumbs;
    var v = $('#view');
    v.innerHTML = html;
    $$('[data-w]', v).forEach(function (el) { el.style.width = el.getAttribute('data-w') + '%'; });
    v.focus({ preventScroll: true });
  }

  function batchPicker() {
    if (ME.role === 'STUDENT' || !META.batches.length) return '';
    return '<select id="batchSel" aria-label="Batch"><option value="">All batches</option>' + META.batches.map(function (b) {
      return '<option' + (b === state.batch ? ' selected' : '') + '>' + esc(b) + '</option>';
    }).join('') + '</select>';
  }
  function wireBatch() {
    var s = $('#batchSel');
    if (!s) return;
    s.addEventListener('change', function () { state.batch = s.value; prefs('batch', s.value); route(); });
  }
  function bq() { return state.batch ? 'batch=' + encodeURIComponent(state.batch) : ''; }

  function subjectTabs(id) {
    return '<div class="seg" role="tablist">' + META.subjects.map(function (s) {
      return '<button type="button" role="tab" data-subject="' + esc(s.id) + '" class="' + (s.id === id ? 'on' : '') + '" aria-selected="' + (s.id === id) + '">' + esc(s.code) + '</button>';
    }).join('') + '</div>';
  }
  function pickSubject() {
    if (!state.subject || !META.subjects.some(function (s) { return s.id === state.subject; })) {
      state.subject = prefs('subject') || (META.subjects[0] && META.subjects[0].id);
      if (!META.subjects.some(function (s) { return s.id === state.subject; })) state.subject = META.subjects[0] && META.subjects[0].id;
    }
    return state.subject;
  }
  function wireSubjectTabs(cb) {
    $$('[data-subject]').forEach(function (b) {
      b.addEventListener('click', function () {
        state.subject = b.getAttribute('data-subject');
        prefs('subject', state.subject);
        cb ? cb() : route();
      });
    });
  }
  function subjectById(id) {
    for (var i = 0; i < META.subjects.length; i++) if (META.subjects[i].id === id) return META.subjects[i];
    return null;
  }

  // ------------------------------------------------------------------ terrain map (SVG)
  function hash(str) {
    var h = 2166136261;
    for (var i = 0; i < str.length; i++) { h ^= str.charCodeAt(i); h = Math.imul(h, 16777619); }
    return h >>> 0;
  }
  function blob(cx, cy, r, seed) {
    // a slightly irregular closed ring, like a contour line
    var pts = [], n = 36, a1 = (seed % 628) / 100, a2 = ((seed >> 8) % 628) / 100;
    for (var i = 0; i <= n; i++) {
      var a = i / n * Math.PI * 2;
      var rr = r * (1 + 0.07 * Math.sin(3 * a + a1) + 0.05 * Math.sin(5 * a + a2));
      pts.push((cx + rr * Math.cos(a)).toFixed(1) + ' ' + (cy + rr * 0.78 * Math.sin(a)).toFixed(1));
    }
    return 'M' + pts.join(' L') + 'Z';
  }

  /**
   * Draw topics as hills. Height (number of contour rings) = Difficulty Index.
   * Each ring is coloured by the height it stands for, so a summit shows every
   * zone from green valley to dark summit. Dashed trails link a prerequisite to
   * the topic that needs it; red trails mark a weak prerequisite (root cause).
   */
  function terrain(items, opts) {
    opts = opts || {};
    if (!items.length) return '<div class="empty">No topics in this subject yet.</div>';
    var units = {};
    items.forEach(function (t) { (units[t.unit] = units[t.unit] || []).push(t); });
    var unitKeys = Object.keys(units).map(Number).sort(function (a, b) { return a - b; });
    var maxK = 1;
    unitKeys.forEach(function (u) { maxK = Math.max(maxK, units[u].length); });
    var W = Math.max(640, unitKeys.length * 200), H = 70 + maxK * 150, padX = 30;
    var colW = (W - padX * 2) / unitKeys.length;
    var pos = {};
    unitKeys.forEach(function (u, ui) {
      var list = units[u], k = list.length;
      list.forEach(function (t, i) {
        var x = padX + colW * (ui + 0.5) + (k > 1 ? (i % 2 ? 34 : -34) : 0);
        var y = 52 + (H - 70) * (i + 0.5) / k;
        pos[t.topicId] = { x: x, y: y, t: t };
      });
    });
    var svg = '<svg viewBox="0 0 ' + W + ' ' + H + '" role="group" aria-label="' + esc(opts.label || 'Topic map') + '">';
    // grid
    svg += '<g class="t-grid">';
    unitKeys.forEach(function (u, ui) {
      var x = padX + colW * ui;
      if (ui > 0) svg += '<line x1="' + x + '" y1="26" x2="' + x + '" y2="' + (H - 6) + '"/>';
      svg += '<text class="t-gridlbl" x="' + (x + colW / 2) + '" y="18" text-anchor="middle">UNIT ' + u + '</text>';
    });
    svg += '</g>';
    // trails
    items.forEach(function (t) {
      (t.prerequisiteIds || []).forEach(function (pid) {
        var a = pos[pid], b = pos[t.topicId];
        if (!a || !b) return;
        var hot = (t.rootCauseIds || []).indexOf(pid) >= 0;
        var mx = (a.x + b.x) / 2, my = (a.y + b.y) / 2 - 30;
        svg += '<path class="t-trail' + (hot ? ' hot' : '') + '" d="M' + a.x.toFixed(1) + ' ' + a.y.toFixed(1) + ' Q' + mx.toFixed(1) + ' ' + my.toFixed(1) + ' ' + b.x.toFixed(1) + ' ' + b.y.toFixed(1) + '"/>';
      });
    });
    // peaks
    Object.keys(pos).forEach(function (id) {
      var p = pos[id], t = p.t, idx = t.index, seed = hash(id);
      var hasData = idx != null && idx >= 0;
      var r = hasData ? 34 + Math.min(100, idx) * 0.3 : 32;
      var rings = hasData ? 1 + Math.floor(Math.min(100, idx) / 12) : 1;
      var g = '<g class="peak" tabindex="0" role="button" data-peak="' + esc(id) + '" aria-label="' + esc(t.topic + ': ' + (hasData ? 'Difficulty ' + di(idx) + ', ' + BANDS[t.band].label : 'not enough data')) + '">';
      g += '<title>' + esc(t.topic + ' — ' + (hasData ? 'DI ' + di(idx) + ' · ' + BANDS[t.band].label : 'not surveyed yet')) + '</title>';
      if (!hasData) {
        g += '<path class="ring r-NO_DATA" d="' + blob(p.x, p.y, r, seed) + '"/>';
      } else {
        for (var j = 0; j < rings; j++) {
          var level = rings === 1 ? idx : (j / (rings - 1)) * idx;     // height this ring stands for
          var band = bandOf(j === 0 ? Math.min(level, idx) : level);
          if (j === 0) band = 'EASY';
          if (j === rings - 1) band = t.band;
          var rr = r * (1 - j / (rings + 0.6));
          g += '<path class="ring r-' + band + '" d="' + blob(p.x, p.y - j * 2.2, rr, seed + j * 97) + '"/>';
        }
        if (t.band === 'CRITICAL') {
          var top = p.y - (rings - 1) * 2.2;
          g += '<path class="t-summit" d="M' + (p.x - 5) + ' ' + (top + 3) + ' L' + p.x + ' ' + (top - 6) + ' L' + (p.x + 5) + ' ' + (top + 3) + 'Z"/>';
        }
      }
      var ly = p.y + r * 0.78 + 15;
      g += '<text class="t-name" x="' + p.x.toFixed(1) + '" y="' + ly.toFixed(1) + '" text-anchor="middle">' + esc(t.topic.length > 30 ? t.topic.slice(0, 29) + '…' : t.topic) + '</text>';
      g += '<text class="t-di" x="' + p.x.toFixed(1) + '" y="' + (ly + 14).toFixed(1) + '" text-anchor="middle">' + (hasData ? 'DI ' + di(idx) + ' · ' + esc(BANDS[t.band].zone) : 'not surveyed') + (opts.countKey && t[opts.countKey] ? ' · ' + t[opts.countKey] + ' ⚑' : '') + '</text>';
      g += '</g>';
      svg += g;
    });
    svg += '</svg>';
    return '<div class="terrain">' + svg + '</div>' +
      '<div class="terrain-legend"><span><svg viewBox="0 0 24 8"><path d="M0 4h24" stroke="currentColor" stroke-dasharray="4 3" stroke-width="1.5"/></svg>trail = learn this first</span>' +
      '<span class="bad"><svg viewBox="0 0 24 8"><path d="M0 4h24" stroke="currentColor" stroke-dasharray="4 3" stroke-width="2"/></svg>weak prerequisite (root cause)</span>' +
      '<span>more rings = higher difficulty' + (opts.countKey ? ' · ⚑ = students finding it difficult' : '') + '</span></div>';
  }
  function wirePeaks(handler) {
    $$('.peak').forEach(function (g) {
      var go = function () { handler(g.getAttribute('data-peak')); };
      g.addEventListener('click', go);
      g.addEventListener('keydown', function (e) { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); go(); } });
    });
  }

  /** A cross-section ("elevation profile") of right answers per week. */
  function profile(weeks) {
    var W = 560, H = 170, pl = 34, pr = 10, pt = 12, pb = 26;
    var n = weeks.length;
    var x = function (i) { return pl + (W - pl - pr) * (n === 1 ? 0.5 : i / (n - 1)); };
    var y = function (v) { return pt + (H - pt - pb) * (1 - v); };
    var s = '<svg viewBox="0 0 ' + W + ' ' + H + '" role="img" aria-label="Share of right answers per week">';
    [0, 0.5, 1].forEach(function (v) {
      s += '<line class="p-axis" x1="' + pl + '" x2="' + (W - pr) + '" y1="' + y(v) + '" y2="' + y(v) + '" stroke-dasharray="' + (v === 0 ? '' : '2 4') + '"/>';
      s += '<text class="p-lbl" x="' + (pl - 6) + '" y="' + (y(v) + 3) + '" text-anchor="end">' + Math.round(v * 100) + '%</text>';
    });
    var segs = [], cur = [];
    weeks.forEach(function (w, i) {
      if (w.rightRate == null) { if (cur.length) segs.push(cur); cur = []; } else cur.push([x(i), y(w.rightRate), w]);
    });
    if (cur.length) segs.push(cur);
    segs.forEach(function (sg) {
      var line = sg.map(function (p, i) { return (i ? 'L' : 'M') + p[0].toFixed(1) + ' ' + p[1].toFixed(1); }).join(' ');
      if (sg.length > 1) s += '<path class="p-area" d="' + line + ' L' + sg[sg.length - 1][0].toFixed(1) + ' ' + y(0) + ' L' + sg[0][0].toFixed(1) + ' ' + y(0) + 'Z"/>';
      s += '<path class="p-line" d="' + line + '"/>';
      sg.forEach(function (p) {
        s += '<circle class="p-dot" cx="' + p[0].toFixed(1) + '" cy="' + p[1].toFixed(1) + '" r="3.5"><title>Week to ' + esc(niceDate(p[2].weekEnding)) + ': ' + pct(p[2].rightRate) + ' right of ' + p[2].answers + '</title></circle>';
      });
    });
    weeks.forEach(function (w, i) {
      if (i % 2 === (n - 1) % 2) s += '<text class="p-lbl" x="' + x(i) + '" y="' + (H - 8) + '" text-anchor="middle">' + esc(shortDate(w.weekEnding)) + '</text>';
    });
    s += '</svg>';
    return '<div class="profile">' + s + '</div>';
  }

  function readings(list) {
    return '<div class="readings">' + list.map(function (r) {
      return '<div class="reading' + (r.alert ? ' alert' : '') + '"><div class="lbl">' + esc(r.label) + '</div><div class="val' + (typeof r.value === 'string' && r.value.length > 9 ? ' text' : '') + '">' + esc(r.value) + '</div>' + (r.sub ? '<div class="sub">' + esc(r.sub) + '</div>' : '') + '</div>';
    }).join('') + '</div>';
  }

  function reasonsList(r) {
    var rootLine = r.rootCauses && r.rootCauses.length ? 'Needs ' + r.rootCauses.join(', ') + ' first' : null;
    return '<ul class="reasons">' + r.reasons.map(function (x) {
      return '<li' + (rootLine && x.indexOf(rootLine) === 0 ? ' class="root"' : '') + '>' + esc(x) + '</li>';
    }).join('') + '</ul>';
  }

  function topicRows(results, link) {
    if (!results.length) return '<div class="empty">Nothing here.</div>';
    return results.map(function (r) {
      return '<a class="topic-row" href="#" data-topic-open="' + esc(r.topicId) + '">' +
        '<div class="elev z-' + r.band + '">' + di(r.index) + '</div>' +
        '<div><div class="t-title">' + esc(r.topic) + '</div>' + reasonsList({ reasons: r.reasons.slice(0, 3), rootCauses: r.rootCauses }) + '</div>' +
        '<div>' + zoneTag(r.band) + '</div></a>';
    }).join('');
  }

  // ------------------------------------------------------------------ modal
  var modalActions = [];
  function modal(title, body, buttons, onOpen) {
    $('#modalTitle').textContent = title;
    $('#modalBody').innerHTML = body;
    $('#modalErr').textContent = '';
    modalActions = buttons || [];
    $('#modalFoot').innerHTML = modalActions.map(function (b, i) {
      return '<button type="button" class="btn' + (b.primary ? ' primary' : '') + (b.danger ? ' danger' : '') + '" data-mbtn="' + i + '">' + esc(b.label) + '</button>';
    }).join('') + '<button type="button" class="btn" data-mclose>Close</button>';
    $$('[data-w]', $('#modalBody')).forEach(function (el) { el.style.width = el.getAttribute('data-w') + '%'; });
    var d = $('#modal');
    if (!d.open) d.showModal();
    if (onOpen) onOpen($('#modalBody'));
  }
  function closeModal() { var d = $('#modal'); if (d.open) d.close(); }
  $('#modalClose').addEventListener('click', closeModal);
  $('#modalFoot').addEventListener('click', function (e) {
    var b = e.target.closest('button');
    if (!b) return;
    if (b.hasAttribute('data-mclose')) { closeModal(); return; }
    var a = modalActions[+b.getAttribute('data-mbtn')];
    if (!a) return;
    $('#modalErr').textContent = '';
    b.disabled = true;
    Promise.resolve().then(function () { return a.run(formData($('#modalBody'))); })
      .then(function (msg) { if (msg !== false) { closeModal(); if (typeof msg === 'string') toast(msg); } })
      .catch(function (err) { $('#modalErr').textContent = err.message; })
      .then(function () { b.disabled = false; });
  });
  $('#modalForm').addEventListener('submit', function (e) { e.preventDefault(); });
  function formData(root) {
    var o = {};
    $$('[name]', root).forEach(function (el) {
      if (el.type === 'checkbox') {
        if (el.hasAttribute('data-multi')) { (o[el.name] = o[el.name] || []); if (el.checked) o[el.name].push(el.value); }
        else o[el.name] = el.checked;
      } else if (el.type === 'radio') { if (el.checked) o[el.name] = el.value; }
      else o[el.name] = el.value;
    });
    return o;
  }
  function field(label, html, help) {
    return '<label class="field">' + esc(label) + html + (help ? '<span class="help">' + esc(help) + '</span>' : '') + '</label>';
  }
  function input(name, value, attrs) { return '<input name="' + name + '" value="' + esc(value == null ? '' : value) + '" ' + (attrs || '') + '>'; }
  function select(name, options, value) {
    return '<select name="' + name + '">' + options.map(function (o) {
      var v = Array.isArray(o) ? o[0] : o, l = Array.isArray(o) ? o[1] : o;
      return '<option value="' + esc(v) + '"' + (String(v) === String(value) ? ' selected' : '') + '>' + esc(l) + '</option>';
    }).join('') + '</select>';
  }

  function changePassword() {
    modal('Change your password',
      field('Current password', '<input type="password" name="current" autocomplete="current-password">') +
      field('New password', '<input type="password" name="next" autocomplete="new-password">', 'At least 8 characters with a letter and a number.'),
      [{ label: 'Change password', primary: true, run: function (f) {
        return api('POST', '/api/me/password', f).then(function () { return 'Password changed.'; });
      } }]);
  }

  /** Detail of one topic for one student: signals, reasons, advice (+ plan button for teachers). */
  function topicModal(r, student) {
    var signals = Object.keys(r.signals || {}).map(function (k) {
      var v = r.signals[k];
      var band = v >= 0.66 ? 'CRITICAL' : v >= 0.4 ? 'DIFFICULT' : v >= 0.2 ? 'MODERATE' : 'EASY';
      return '<div class="signal"><span>' + esc(SIGNAL_NAMES[k] || k) + '</span>' + bar(v, 1, 'z-' + band) + '<span class="v">' + Math.round(v * 100) + '</span></div>';
    }).join('');
    var body = '<p>' + zoneTag(r.band, r.index) + ' <span class="muted small">' + esc(BANDS[r.band].zone) + (r.lastPractised ? ' · last practised ' + esc(niceDate(r.lastPractised)) : '') + '</span></p>' +
      readingsMini(r) +
      (signals ? '<h3 class="mt small mono muted">SIGNALS (0 = none, 100 = strong)</h3><div class="signals">' + signals + '</div>' : '') +
      '<h3 class="mt small mono muted">WHY</h3>' + reasonsList(r) +
      (r.band !== 'EASY' && r.band !== 'NO_DATA' && r.advice ? '<h3 class="mt small mono muted">WHAT TO DO</h3><p>' + esc(r.advice) + '</p>' : '');
    var buttons = [];
    if (student && ME.role !== 'STUDENT' && r.band !== 'NO_DATA')
      buttons.push({ label: 'Make a support plan', primary: true, run: function () { setTimeout(function () { planForm(student, r); }, 10); } });
    if (ME.role === 'STUDENT')
      buttons.push({ label: 'Practise my peaks', primary: true, run: function () { startQuiz(r.subjectId, 'PRACTICE'); } });
    modal(r.topic + (student ? ' · ' + student.name : ''), body, buttons);
  }
  function readingsMini(r) {
    if (r.answered === 0) return '';
    return '<p class="small">' + esc(r.correct + ' right of ' + r.answered) +
      (r.timeRatio ? ' · ' + r.timeRatio + '× expected time' : '') +
      (r.hintRate ? ' · hints ' + pct(r.hintRate) : '') +
      (r.sureWrong ? ' · wrong-while-sure ' + r.sureWrong : '') +
      (r.selfRating ? ' · feels ' + r.selfRating + '/5' : '') + '</p>';
  }

  // =================================================================== STUDENT PAGES

  on('home', null, function () {
    if (ME.role === 'STUDENT') return studentHome();
    return teacherHome();
  });

  function studentHome() {
    return api('GET', '/api/dashboard').then(function (d) {
      var sid = pickSubject();
      var hard = d.bands.DIFFICULT + d.bands.CRITICAL;
      var surveyed = d.bands.EASY + d.bands.MODERATE + d.bands.DIFFICULT + d.bands.CRITICAL;
      var total = surveyed + d.bands.NO_DATA;
      var sub = d.subjects.filter(function (s) { return s.id === sid; })[0] || d.subjects[0];
      var html = '<div class="page-head"><div><p class="eyebrow mono">Survey of ' + esc(ME.name) + ' · ' + esc(ME.rollNo) + '</p><h1>My learning map</h1>' +
        '<p>Each hill is a topic. The higher the hill, the harder that topic is for you right now. Take tests and practice sets to keep the map up to date.</p></div>' +
        '<div class="actions"><a class="btn primary" href="#/survey">Take a survey</a></div></div>';
      html += readings([
        { label: 'Attempts', value: d.tests, sub: 'tests and practice sets' },
        { label: 'Right answers', value: pct(d.avgScore), sub: 'all attempts' },
        { label: 'Topics surveyed', value: surveyed + ' / ' + total, sub: d.bands.NO_DATA ? d.bands.NO_DATA + ' need more answers' : 'every topic has data' },
        { label: 'Peaks to climb', value: hard, sub: d.bands.CRITICAL + ' very difficult', alert: hard > 0 }
      ]);
      html += '<div class="grid-main"><div class="stack"><section class="panel"><div class="terrain-head"><h3>' + esc(sub ? sub.name : '') + '</h3>' + subjectTabs(sid) + '</div>' +
        (sub ? terrain(sub.topics, { label: sub.name + ' topic map' }) : '<div class="empty">No subjects.</div>') + '</section>' +
        '<section class="panel"><h2>Right answers by week <span class="mono">elevation profile</span></h2>' + profile(d.weekly) + '</section></div>';
      html += '<div class="stack"><section class="panel"><h2>Next steps <span class="mono">study route</span><a class="right small link-btn" href="#/route">Full route (' + d.planTotal + ')</a></h2>' + routeList(d.plan, true) + '</section>';
      if (d.supportPlans.length) {
        html += '<section class="panel"><h2>Help from teachers</h2><ul class="list">' + d.supportPlans.map(function (p) {
          return '<li><span class="grow"><b>' + esc(p.action) + '</b> · ' + esc(p.topic) + '<br><span class="small muted">by ' + esc(p.createdBy) + ' · due ' + esc(niceDate(p.dueDate)) + '</span></span>' + subj(p.subject, p.color) + '</li>';
        }).join('') + '</ul></section>';
      }
      html += '<section class="panel"><h2>Recent attempts</h2>' + attemptList(d.recent, false) + '</section></div></div>';
      draw('Sheet A1 · My map', html);
      wireSubjectTabs();
      var byId = {};
      d.subjects.forEach(function (s) { s.topics.forEach(function (t) { byId[t.topicId] = t; }); });
      wirePeaks(function (id) {
        var r = byId[id];
        var step = d.plan.filter(function (p) { return p.result.topicId === id; })[0];
        r.advice = step ? step.advice : '';
        topicModal(r, null);
      });
    });
  }

  function routeList(plan, compact) {
    if (!plan.length) return '<div class="empty">No difficult topics right now — well done! Keep practising to stay in the valleys.</div>';
    return '<ol class="route">' + plan.map(function (p) {
      var r = p.result;
      return '<li><span class="stepno z-' + r.band + '">' + p.step + '</span><div><div class="r-head"><b>' + esc(r.topic) + '</b>' + subj(p.subject, (subjectById(r.subjectId) || {}).color) + zoneTag(r.band, r.index) + '</div>' +
        (r.mainCause ? '<p class="small"><span class="chip">' + esc(CAUSES[r.mainCause] || r.mainCause) + '</span></p>' : '') +
        '<p>' + esc(p.advice) + '</p>' + (compact ? '' : reasonsList(r)) + '</div></li>';
    }).join('') + '</ol>';
  }

  function attemptList(list, showStudent) {
    if (!list.length) return '<div class="empty">No attempts yet.</div>';
    return '<ul class="list">' + list.map(function (a) {
      return '<li><a class="grow" href="#/attempt/' + esc(a.id) + '"><b>' + (a.kind === 'TEST' ? 'Test' : 'Practice') + '</b> ' + subj(a.subject, a.color) +
        (showStudent ? ' · ' + esc(a.student) : '') + '<br><span class="small muted">' + esc(niceTime(a.finishedAt)) + ' · ' + a.minutes + ' min' + (a.hints ? ' · ' + plural(a.hints, 'hint') : '') + '</span></a>' +
        '<span class="mono"><b>' + a.score + '</b>/' + a.total + '</span></li>';
    }).join('') + '</ul>';
  }

  on('survey', ['STUDENT'], function () {
    return api('GET', '/api/subjects').then(function (subs) {
      var html = '<div class="page-head"><div><p class="eyebrow mono">Sheet A2</p><h1>Take a survey</h1><p>A <b>test</b> asks 2 questions from every topic and keeps your whole map fresh. ' +
        '<b>Practice</b> picks most questions from your difficult topics (easier ones first) so your time goes where it helps most.</p></div></div>';
      html += '<div class="subject-cards">' + subs.map(function (s) {
        return '<section class="panel subject-card c-' + esc(s.color) + '"><span class="subj c-' + esc(s.color) + '">' + esc(s.code) + '</span><h3>' + esc(s.name) + '</h3><p>' + esc(s.description) + '</p>' +
          '<p class="small muted mono">' + s.topics + ' topics · ' + s.questions + ' questions</p><div class="row">' +
          '<button class="btn primary" data-start="' + esc(s.id) + '" data-kind="TEST">Full test</button>' +
          '<button class="btn" data-start="' + esc(s.id) + '" data-kind="PRACTICE">Practise my peaks</button></div></section>';
      }).join('') + '</div>';
      html += '<section class="panel mt"><h2>How your answers are read</h2><div class="grid3 small">' +
        '<p><b>Right or wrong</b><br>A wrong answer on an easy question raises the hill more than one on a hard question.</p>' +
        '<p><b>Time and hints</b><br>Taking much longer than expected, or opening hints, means the topic is not yet comfortable.</p>' +
        '<p><b>How sure you were</b><br>Being <i>sure</i> but wrong shows a misunderstanding — the detector points these out so you can fix the idea, not just practise more.</p></div></section>';
      draw('Sheet A2 · Take a survey', html);
      $$('[data-start]').forEach(function (b) {
        b.addEventListener('click', function () { startQuiz(b.getAttribute('data-start'), b.getAttribute('data-kind')); });
      });
    });
  });

  function startQuiz(subjectId, kind) {
    closeModal();
    if (state.quiz) clearInterval(state.quiz.timer);
    api('POST', '/api/quiz/start', { subjectId: subjectId, kind: kind, size: 10 }).then(function (q) {
      state.quiz = { data: q, idx: 0, answers: q.questions.map(function () { return { chosen: -1, confidence: 0, seconds: 0 }; }), hints: {}, shownAt: Date.now() };
      location.hash = '#/quiz';
      if (location.hash === '#/quiz') route();
    }).catch(function (e) { toast(e.message); });
  }

  on('quiz', ['STUDENT'], function () {
    var Q = state.quiz;
    if (!Q) { location.hash = '#/survey'; return; }
    var q = Q.data.questions[Q.idx], a = Q.answers[Q.idx], n = Q.data.questions.length;
    Q.shownAt = Date.now();
    var letters = 'ABCDE';
    var html = '<div class="quiz"><div class="page-head"><div><p class="eyebrow mono">' + (Q.data.kind === 'TEST' ? 'Full test' : 'Practice') + ' · ' + esc(Q.data.subject.code) + '</p><h1>' + esc(Q.data.subject.name) + '</h1></div>' +
      '<div class="actions"><button class="btn danger small" id="quitQuiz" type="button">Leave survey</button></div></div>';
    html += '<div class="trail" aria-label="Progress">' + Q.data.questions.map(function (_, i) {
      return '<i class="' + (i === Q.idx ? 'now' : Q.answers[i].confidence ? 'done' : '') + '" title="Question ' + (i + 1) + '"></i>';
    }).join('') + '<span class="mono small muted">&nbsp;' + (Q.idx + 1) + ' / ' + n + '</span></div>';
    html += '<section class="panel qcard"><div class="qmeta"><span class="chip">' + esc(q.topic) + '</span><span class="mono">' + ['', 'easy', 'medium', 'hard'][q.level] + '</span><span class="spacer"></span><span class="timer" id="timer"></span></div>' +
      '<p class="qtext">' + esc(q.text) + '</p><div class="opts" role="radiogroup">' + q.options.map(function (o, i) {
        return '<button type="button" class="opt' + (a.chosen === i ? ' on' : '') + '" data-opt="' + i + '" role="radio" aria-checked="' + (a.chosen === i) + '"><span class="letter">' + letters[i] + '</span><span>' + esc(o) + '</span></button>';
      }).join('') + '</div>' +
      (Q.hints[q.id] ? '<div class="hint-box"><b>Hint:</b> ' + esc(Q.hints[q.id]) + '</div>' : '') +
      '<div class="sure"><p>How sure are you?</p><div class="seg" id="sureSeg">' + [1, 2, 3].map(function (c) {
        return '<button type="button" data-conf="' + c + '" class="' + (a.confidence === c ? 'on' : '') + '">' + CONF[c] + '</button>';
      }).join('') + '</div></div>' +
      '<div class="qfoot">' + (Q.idx > 0 ? '<button class="btn" id="prevQ" type="button">Back</button>' : '') +
      (q.hasHint && !Q.hints[q.id] ? '<button class="btn" id="hintQ" type="button">Show a hint</button>' : '') +
      '<span class="spacer"></span><span class="small muted" id="needMsg"></span>' +
      '<button class="btn" id="skipQ" type="button">Skip</button>' +
      '<button class="btn primary" id="nextQ" type="button">' + (Q.idx === n - 1 ? 'Finish' : 'Next') + '</button></div></section></div>';
    draw('Field survey · question ' + (Q.idx + 1), html);

    var started = Date.now(), base = a.seconds;
    var tick = function () {
      var t = $('#timer');
      if (!t || state.quiz !== Q) { clearInterval(Q.timer); return; }
      var s = base + Math.round((Date.now() - started) / 1000);
      t.textContent = Math.floor(s / 60) + ':' + ('0' + (s % 60)).slice(-2) + ' · expected ~' + q.expectedSeconds + 's';
    };
    clearInterval(Q.timer);
    Q.timer = setInterval(tick, 1000);
    tick();
    var save = function () { a.seconds = base + Math.max(1, Math.round((Date.now() - started) / 1000)); };

    $$('[data-opt]').forEach(function (b) {
      b.addEventListener('click', function () {
        a.chosen = +b.getAttribute('data-opt');
        $$('[data-opt]').forEach(function (x) { x.classList.toggle('on', x === b); x.setAttribute('aria-checked', x === b); });
      });
    });
    $$('[data-conf]').forEach(function (b) {
      b.addEventListener('click', function () {
        a.confidence = +b.getAttribute('data-conf');
        $$('[data-conf]').forEach(function (x) { x.classList.toggle('on', x === b); });
      });
    });
    var hb = $('#hintQ');
    if (hb) hb.addEventListener('click', function () {
      api('POST', '/api/quiz/' + Q.data.quizId + '/hint', { questionId: q.id }).then(function (r) {
        save(); Q.hints[q.id] = r.hint; route();
      }).catch(function (e) { toast(e.message); });
    });
    var pb = $('#prevQ');
    if (pb) pb.addEventListener('click', function () { save(); Q.idx--; route(); });
    $('#skipQ').addEventListener('click', function () { save(); a.chosen = -1; a.confidence = a.confidence || 1; next(); });
    $('#nextQ').addEventListener('click', function () {
      if (a.chosen < 0) { $('#needMsg').textContent = 'Choose an answer (or Skip).'; return; }
      if (!a.confidence) { $('#needMsg').textContent = 'Tell us how sure you are.'; return; }
      save(); next();
    });
    $('#quitQuiz').addEventListener('click', function () {
      modal('Leave this survey?', '<p>Your answers in this survey will not be saved.</p>', [{ label: 'Leave', danger: true, run: function () {
        clearInterval(Q.timer); state.quiz = null; location.hash = '#/survey';
      } }]);
    });
    function next() {
      if (Q.idx < n - 1) { Q.idx++; route(); return; }
      clearInterval(Q.timer);
      var answers = Q.data.questions.map(function (qq, i) {
        var x = Q.answers[i];
        return { questionId: qq.id, chosen: x.chosen, seconds: Math.max(1, x.seconds), confidence: x.confidence || 1 };
      });
      $('#nextQ').disabled = true;
      api('POST', '/api/quiz/' + Q.data.quizId + '/submit', { answers: answers }).then(function (res) {
        res.subject = Q.data.subject;
        res.kind = Q.data.kind;
        state.result = res;
        state.quiz = null;
        location.hash = '#/result';
      }).catch(function (e) { toast(e.message); $('#nextQ').disabled = false; });
    }
  });

  on('result', ['STUDENT'], function () {
    var R = state.result;
    if (!R) { location.hash = '#/survey'; return; }
    var ratio = R.total ? R.score / R.total : 0, C = 2 * Math.PI * 48;
    var ring = '<svg class="score-ring" viewBox="0 0 120 120" role="img" aria-label="' + R.score + ' of ' + R.total + '"><circle class="bg" cx="60" cy="60" r="48"/>' +
      '<circle class="fg" cx="60" cy="60" r="48" stroke-dasharray="' + (C * ratio).toFixed(1) + ' ' + C.toFixed(1) + '"/><text x="60" y="68" text-anchor="middle">' + R.score + '/' + R.total + '</text></svg>';
    var changes = R.changes.map(function (c) {
      var d = c.before >= 0 && c.after >= 0 ? c.after - c.before : null;
      return '<li><span class="grow">' + esc(c.topic) + '</span>' + zoneTag(c.band, c.after) +
        (d == null ? '<span class="change muted">first reading</span>' : '<span class="change ' + (d < -0.5 ? 'good' : d > 0.5 ? 'bad' : 'muted') + '">' + (d < -0.5 ? '▼ ' : d > 0.5 ? '▲ ' : '= ') + Math.abs(Math.round(d)) + '</span>') + '</li>';
    }).join('');
    var html = '<div class="page-head"><div><p class="eyebrow mono">' + (R.kind === 'TEST' ? 'Test' : 'Practice') + ' finished · ' + esc(R.subject.code) + '</p><h1>Survey results</h1></div>' +
      '<div class="actions"><a class="btn" href="#/home">See my map</a><button class="btn primary" type="button" id="again">Practise my peaks</button></div></div>';
    html += '<div class="grid-main"><section class="panel"><div class="result-head">' + ring + '<div><h2>' + (ratio >= 0.8 ? 'Strong survey.' : ratio >= 0.5 ? 'Good progress.' : 'Some steep ground here.') + '</h2>' +
      '<p class="muted">Read the explanation for each question you missed — especially the ones where you said you were <b>sure</b>.</p></div></div>' +
      '<div class="review">' + reviewItems(R.items) + '</div></section>' +
      '<section class="panel"><h2>How your map changed <span class="mono">▼ = easier</span></h2><ul class="list">' + changes + '</ul></section></div>';
    draw('Survey results', html);
    $('#again').addEventListener('click', function () { startQuiz(R.subject.id, 'PRACTICE'); });
  });

  function reviewItems(items) {
    var letters = 'ABCDE';
    return items.map(function (it, i) {
      var chosen = it.chosen >= 0 ? letters[it.chosen] + '. ' + it.options[it.chosen] : 'Skipped';
      return '<div class="ritem"><div class="rq">' + (i + 1) + '. ' + esc(it.text) + '</div>' +
        '<div class="ra"><span class="' + (it.correct ? 'good' : 'bad') + '"><b>' + (it.correct ? 'Right' : 'Wrong') + '</b></span> · You: ' + esc(chosen) +
        (it.correct ? '' : ' · Right answer: <b>' + esc(letters[it.answer] + '. ' + it.options[it.answer]) + '</b>') +
        ' · <span class="chip">' + esc(CONF[it.confidence]) + '</span>' + (it.hintUsed ? ' <span class="chip">hint used</span>' : '') +
        (it.topic ? ' <span class="muted small">' + esc(it.topic) + '</span>' : '') + ' <span class="mono small muted">' + it.seconds + 's</span>' +
        (!it.correct && it.confidence === 3 ? ' <span class="chip bad">misunderstanding?</span>' : '') + '</div>' +
        (it.explanation ? '<div class="ex">' + esc(it.explanation) + '</div>' : '') + '</div>';
    }).join('');
  }

  on('route', ['STUDENT'], function () {
    return api('GET', '/api/students/' + ME.id + '/map').then(function (m) {
      var html = '<div class="page-head"><div><p class="eyebrow mono">Sheet A3</p><h1>My study route</h1><p>Start at step 1. If a topic needs another topic first, that one comes earlier in the route — a weak base makes everything built on it hard.</p></div>' +
        '<div class="actions"><a class="btn primary" href="#/survey">Practise now</a></div></div>';
      html += '<section class="panel">' + routeList(m.plan, false) + '</section>';
      draw('Sheet A3 · Study route', html);
    });
  });

  on('history', ['STUDENT'], function () {
    return api('GET', '/api/attempts').then(function (list) {
      draw('Sheet B1 · My attempts', '<div class="page-head"><div><p class="eyebrow mono">Sheet B1</p><h1>My attempts</h1></div></div><section class="panel">' + attemptTable(list, false) + '</section>');
      wireRows();
    });
  });

  function attemptTable(list, showStudent) {
    if (!list.length) return '<div class="empty">No attempts yet.</div>';
    return '<div class="table-wrap"><table class="tbl"><thead><tr><th>When</th>' + (showStudent ? '<th>Student</th>' : '') + '<th>Subject</th><th>Kind</th><th class="num">Score</th><th class="num">Minutes</th><th class="num">Hints</th><th class="num">Wrong while sure</th></tr></thead><tbody>' +
      list.map(function (a) {
        return '<tr class="click" data-href="#/attempt/' + esc(a.id) + '"><td class="nowrap">' + esc(niceTime(a.finishedAt)) + '</td>' + (showStudent ? '<td>' + esc(a.student) + '</td>' : '') +
          '<td>' + subj(a.subject, a.color) + '</td><td>' + (a.kind === 'TEST' ? 'Test' : 'Practice') + '</td><td class="num"><b>' + a.score + '</b>/' + a.total + '</td><td class="num">' + a.minutes + '</td><td class="num">' + a.hints + '</td><td class="num">' + (a.sureWrong ? '<span class="bad">' + a.sureWrong + '</span>' : 0) + '</td></tr>';
      }).join('') + '</tbody></table></div>';
  }
  function wireRows() {
    $$('tr[data-href]').forEach(function (tr) {
      tr.addEventListener('click', function () { location.hash = tr.getAttribute('data-href'); });
    });
  }

  on('attempt', null, function (id) {
    return api('GET', '/api/attempts/' + encodeURIComponent(id)).then(function (a) {
      var html = '<div class="page-head"><div><p class="eyebrow mono">' + (a.kind === 'TEST' ? 'Test' : 'Practice') + ' · ' + esc(niceTime(a.finishedAt)) + '</p><h1>' + esc(a.student) + ' — ' + a.score + '/' + a.total + '</h1>' +
        '<p>' + subj(a.subject, a.color) + ' · ' + a.minutes + ' minutes · ' + plural(a.hints, 'hint') + ' · ' + a.sureWrong + ' wrong while sure</p></div>' +
        '<div class="actions">' + (ME.role !== 'STUDENT' ? '<a class="btn" href="#/student/' + esc(a.studentId) + '">Student map</a>' : '') + '<button class="btn" type="button" id="backBtn">Back</button></div></div>';
      html += '<section class="panel review">' + reviewItems(a.items) + '</section>';
      draw('Attempt ' + a.id, html);
      $('#backBtn').addEventListener('click', function () { history.back(); });
    });
  });

  on('feel', ['STUDENT'], function () {
    return Promise.all([api('GET', '/api/ratings')].concat(META.subjects.map(function (s) { return api('GET', '/api/topics?subject=' + s.id); }))).then(function (res) {
      var ratings = {};
      res[0].forEach(function (r) { ratings[r.topicId] = r; });
      var html = '<div class="page-head"><div><p class="eyebrow mono">Sheet B2</p><h1>How does each topic feel?</h1><p>1 = easy, 5 = very hard. Your feeling is one of the signals the detector uses (a small one — your answers count more). Change it any time.</p></div></div>';
      html += '<div class="grid2">' + META.subjects.map(function (s, i) {
        var topics = res[i + 1].filter(function (x) { return x.topic.active; });
        return '<section class="panel"><h2>' + subj(s.code, s.color) + ' ' + esc(s.name) + '</h2><div class="rate-grid">' + topics.map(function (x) {
          var cur = ratings[x.topic.id];
          return '<div class="rate-row"><div><b>' + esc(x.topic.name) + '</b>' + (cur ? '<br><span class="small muted">rated ' + esc(niceDate(cur.date)) + '</span>' : '') + '</div><div class="rate-btns" data-topic="' + esc(x.topic.id) + '">' +
            [1, 2, 3, 4, 5].map(function (v) { return '<button type="button" data-rate="' + v + '" class="lv' + v + (cur && cur.rating === v ? ' on' : '') + '" aria-label="' + v + ' of 5">' + v + '</button>'; }).join('') + '</div></div>';
        }).join('') + '</div></section>';
      }).join('') + '</div>';
      draw('Sheet B2 · How it feels', html);
      $$('[data-rate]').forEach(function (b) {
        b.addEventListener('click', function () {
          var box = b.parentNode, v = +b.getAttribute('data-rate');
          api('POST', '/api/ratings', { topicId: box.getAttribute('data-topic'), rating: v }).then(function () {
            $$('button', box).forEach(function (x) { x.classList.toggle('on', x === b); });
            toast('Saved. Thank you!');
          }).catch(function (e) { toast(e.message); });
        });
      });
    });
  });

  // =================================================================== TEACHER / ADMIN PAGES

  function teacherHome() {
    return api('GET', '/api/dashboard' + (bq() ? '?' + bq() : '')).then(function (d) {
      var k = d.kpi;
      var sid = pickSubject();
      var sub = d.subjects.filter(function (s) { return s.id === sid; })[0] || d.subjects[0];
      var html = '<div class="page-head"><div><p class="eyebrow mono">Class survey · ' + esc(state.batch || 'all batches') + '</p><h1>Where is the class getting stuck?</h1>' +
        '<p>Every topic is surveyed from students\' answers. High ground = many students find it difficult.</p></div><div class="actions toolbar">' + batchPicker() + '</div></div>';
      html += readings([
        { label: 'Students', value: k.students, sub: 'active' },
        { label: 'Attempts · 30 days', value: k.tests30, sub: 'right answers ' + pct(k.avgScore30) },
        { label: 'Difficult readings', value: k.flagged, sub: k.critical + ' very difficult (student × topic)', alert: k.critical > 0 },
        { label: 'Open support plans', value: k.openPlans, sub: k.overduePlans ? k.overduePlans + ' overdue' : 'none overdue', alert: k.overduePlans > 0 }
      ]);
      html += '<div class="grid-main"><div class="stack"><section class="panel"><div class="terrain-head"><h3>Class map · ' + esc(sub ? sub.name : '') + '</h3>' + subjectTabs(sid) + '<span class="spacer"></span><a class="small link-btn" href="#/heatmap">Heat map →</a></div>' +
        (sub ? terrain(sub.topics, { label: 'Class map', countKey: 'struggling' }) : '') + '</section>' +
        '<section class="panel"><h2>Class right answers by week <span class="mono">elevation profile</span></h2>' + profile(d.weekly) + '</section>' +
        '<section class="panel"><h2>Recent attempts<a class="right small link-btn" href="#/attempts">All</a></h2>' + attemptList(d.recent, true) + '</section></div>';
      html += '<div class="stack"><section class="panel"><h2>Highest peaks <span class="mono">class average</span></h2><ul class="list">' + (d.hardest.length ? d.hardest.map(function (t) {
        return '<li><a class="grow" href="#/topic/' + esc(t.topicId) + '"><b>' + esc(t.topic) + '</b><br><span class="small muted">' + t.struggling + ' of ' + t.students + ' students find it difficult · ' + pct(t.rightRate) + ' right</span></a>' + subj(t.subject, t.color) + zoneTag(t.band, t.index) + '</li>';
      }).join('') : '<li class="muted">No data yet.</li>') + '</ul></section>';
      if (d.slipping.length) html += '<section class="panel"><h2>Slipping <span class="mono">last 14 days</span></h2><ul class="list">' + d.slipping.map(function (s) {
        return '<li><a class="grow" href="#/topic/' + esc(s.topicId) + '">' + esc(s.topic) + '</a><span class="mono small">' + pct(s.before) + ' → <b class="bad">' + pct(s.now) + '</b></span></li>';
      }).join('') + '</ul></section>';
      html += '<section class="panel"><h2>Students needing help<a class="right small link-btn" href="#/students">All</a></h2><ul class="list">' + (d.needHelp.length ? d.needHelp.map(function (s) {
        return '<li><a class="grow" href="#/student/' + esc(s.studentId) + '"><b>' + esc(s.name) + '</b><br><span class="small muted">' + esc(s.rollNo) + ' · highest: ' + esc(s.worst) + '</span></a><span class="zone z-DIFFICULT">' + plural(s.hard, 'peak') + '</span></li>';
      }).join('') : '<li class="muted">Nobody is flagged.</li>') + '</ul></section>';
      html += '<section class="panel"><h2>Support plans due<a class="right small link-btn" href="#/plans">All</a></h2><ul class="list">' + (d.plans.length ? d.plans.map(function (p) {
        return '<li><a class="grow" href="#/student/' + esc(p.studentId) + '"><b>' + esc(p.student) + '</b> · ' + esc(p.topic) + '<br><span class="small muted">' + esc(p.action) + '</span></a><span class="small mono ' + (p.overdue ? 'bad' : '') + '">' + (p.overdue ? 'overdue ' : 'due ') + esc(shortDate(p.dueDate)) + '</span></li>';
      }).join('') : '<li class="muted">No open plans.</li>') + '</ul></section></div></div>';
      draw('Sheet A1 · Class overview', html);
      wireBatch();
      wireSubjectTabs();
      wirePeaks(function (id) { location.hash = '#/topic/' + id; });
    });
  }

  on('heatmap', ['TEACHER', 'ADMIN'], function () {
    var sid = pickSubject();
    return api('GET', '/api/heatmap?subject=' + encodeURIComponent(sid) + (bq() ? '&' + bq() : '')).then(function (hm) {
      var s = subjectById(sid);
      var html = '<div class="page-head"><div><p class="eyebrow mono">Sheet A2</p><h1>Heat map · ' + esc(s.name) + '</h1>' +
        '<p>Every student against every topic. Each square is that student\'s Difficulty Index. A small corner mark means a weak prerequisite is the likely cause. Click a square for the reasons.</p></div>' +
        '<div class="actions"><a class="btn" href="/api/export/heatmap.csv?subject=' + encodeURIComponent(sid) + (bq() ? '&' + bq() : '') + '">Export CSV</a></div></div>';
      html += '<div class="toolbar">' + subjectTabs(sid) + batchPicker() + '</div>';
      if (!hm.rows.length) html += '<div class="empty">No students in this batch.</div>';
      else {
        var colStats = hm.topics.map(function () { return { sum: 0, n: 0, hard: 0 }; });
        var body = hm.rows.map(function (r) {
          return '<tr><th scope="row"><a href="#/student/' + esc(r.studentId) + '">' + esc(r.name) + '</a><small>' + esc(r.rollNo) + '</small></th>' + r.cells.map(function (c, i) {
            if (c.index >= 0) { colStats[i].sum += c.index; colStats[i].n++; if (c.band === 'DIFFICULT' || c.band === 'CRITICAL') colStats[i].hard++; }
            return '<td class="cell z-' + c.band + (c.root ? ' root' : '') + '" data-cell="' + esc(r.studentId) + '|' + esc(hm.topics[i].id) + '" title="' + esc(r.name + ' · ' + hm.topics[i].name + ' · ' + (c.index >= 0 ? 'DI ' + di(c.index) : 'not enough data')) + '">' + di(c.index) + '</td>';
          }).join('') + '<td class="cnt">' + (r.hard ? '<b class="bad">' + r.hard + '</b>' : '0') + '</td></tr>';
        }).join('');
        html += '<div class="heat-wrap"><table class="heat"><thead><tr><th class="who">Student</th>' + hm.topics.map(function (t) {
          return '<th class="tname"><a href="#/topic/' + esc(t.id) + '">' + esc(t.name) + '</a></th>';
        }).join('') + '<th class="who">Peaks</th></tr></thead><tbody>' + body + '</tbody><tfoot><tr><th>Class average</th>' + colStats.map(function (c) {
          var avg = c.n ? c.sum / c.n : -1;
          return '<td class="z-' + bandOf(avg) + '">' + di(avg) + '</td>';
        }).join('') + '<td></td></tr></tfoot></table></div>';
      }
      draw('Sheet A2 · Heat map', html);
      wireSubjectTabs();
      wireBatch();
      $$('[data-cell]').forEach(function (td) {
        td.addEventListener('click', function () {
          var p = td.getAttribute('data-cell').split('|');
          openStudentTopic(p[0], p[1]);
        });
      });
    });
  });

  function openStudentTopic(studentId, topicId) {
    var t = null;
    api('GET', '/api/students/' + studentId + '/map').then(function (m) {
      m.results.forEach(function (r) { if (r.topicId === topicId) t = r; });
      if (!t) throw new Error('Topic not found.');
      var step = m.plan.filter(function (p) { return p.result.topicId === topicId; })[0];
      t.advice = step ? step.advice : '';
      topicModal(t, m.student);
    }).catch(function (e) { toast(e.message); });
  }

  on('topics', ['TEACHER', 'ADMIN'], function () {
    var sid = pickSubject();
    return api('GET', '/api/class-topics?subject=' + encodeURIComponent(sid) + (bq() ? '&' + bq() : '')).then(function (list) {
      var s = subjectById(sid);
      var html = '<div class="page-head"><div><p class="eyebrow mono">Sheet A3</p><h1>Topics · ' + esc(s.name) + '</h1><p>Class average Difficulty Index for each topic (only students with enough answers are counted).</p></div></div>';
      html += '<div class="toolbar">' + subjectTabs(sid) + batchPicker() + '</div>';
      html += '<section class="panel"><div class="table-wrap"><table class="tbl"><thead><tr><th>Unit</th><th>Topic</th><th>Class difficulty</th><th></th><th class="num">Right answers</th><th class="num">Finding it difficult</th></tr></thead><tbody>' +
        list.map(function (t) {
          return '<tr class="click" data-href="#/topic/' + esc(t.topicId) + '"><td class="mono">U' + t.unit + '</td><td><b>' + esc(t.topic) + '</b></td><td>' + zoneTag(t.band, t.index) + '</td><td class="nowrap">' + bar(Math.max(0, t.index), 100, 'z-' + t.band) + '</td>' +
            '<td class="num">' + pct(t.rightRate) + '</td><td class="num">' + t.struggling + ' / ' + t.students + '</td></tr>';
        }).join('') + '</tbody></table></div></section>';
      draw('Sheet A3 · Topics', html);
      wireSubjectTabs();
      wireBatch();
      wireRows();
    });
  });

  on('topic', ['TEACHER', 'ADMIN'], function (id) {
    return api('GET', '/api/topics/' + encodeURIComponent(id) + '/report' + (bq() ? '?' + bq() : '')).then(function (rep) {
      var t = rep.topic, s = rep.subject, total = 0;
      Object.keys(rep.bands).forEach(function (b) { total += rep.bands[b]; });
      var stacked = '<div class="stackbar" role="img" aria-label="Students per zone">' + BAND_ORDER.concat(['NO_DATA']).map(function (b) {
        return rep.bands[b] ? '<i class="z-' + b + '" data-w="' + (rep.bands[b] / total * 100).toFixed(1) + '" title="' + esc(BANDS[b].label + ': ' + rep.bands[b]) + '"></i>' : '';
      }).join('') + '</div><p class="small muted">' + BAND_ORDER.concat(['NO_DATA']).map(function (b) { return esc(BANDS[b].label) + ' <b>' + rep.bands[b] + '</b>'; }).join(' · ') + '</p>';
      var causes = Object.keys(rep.causes).sort(function (a, b) { return rep.causes[b] - rep.causes[a]; });
      var html = '<div class="page-head"><div><p class="eyebrow mono">' + esc(s.code) + ' · Unit ' + t.unit + '</p><h1>' + esc(t.name) + '</h1>' +
        '<p>' + (rep.prerequisites.length ? 'Learn first: <b>' + esc(rep.prerequisites.join(', ')) + '</b>. ' : 'No prerequisites. ') + 'Expected time ' + t.expectedSeconds + 's per question.</p></div>' +
        '<div class="actions">' + batchPicker() + '</div></div>';
      html += readings([
        { label: 'Class difficulty', value: di(rep.classIndex), sub: BANDS[rep.classBand].label + ' · ' + BANDS[rep.classBand].zone },
        { label: 'Students with data', value: rep.withData + ' / ' + total },
        { label: 'Finding it difficult', value: (rep.bands.DIFFICULT + rep.bands.CRITICAL), sub: rep.bands.CRITICAL + ' very difficult', alert: rep.bands.CRITICAL > 0 },
        { label: 'Main cause', value: causes.length ? (CAUSES[causes[0]] || causes[0]) : '—', sub: causes.length ? rep.causes[causes[0]] + ' students' : '' }
      ]);
      html += '<div class="grid-main"><div class="stack"><section class="panel"><h2>Questions that trip students up <span class="mono">lowest right % first</span></h2><div class="table-wrap"><table class="tbl"><thead><tr><th>Question</th><th class="num">Level</th><th class="num">Right</th><th class="num">Avg time</th><th class="num">Sure but wrong</th><th>Most chosen wrong answer</th></tr></thead><tbody>' +
        rep.questions.map(function (q) {
          return '<tr><td>' + esc(q.text) + (q.active ? '' : ' <span class="chip">off</span>') + '</td><td class="num">' + ['', 'easy', 'med', 'hard'][q.level] + '</td><td class="num">' + (q.answered ? '<b class="' + (q.rightRate < 0.4 ? 'bad' : '') + '">' + pct(q.rightRate) + '</b><br><span class="small muted">of ' + q.answered + '</span>' : '—') + '</td>' +
            '<td class="num">' + (q.answered ? q.avgSeconds + 's' : '—') + '</td><td class="num">' + (q.sureWrong ? '<span class="bad">' + q.sureWrong + '</span>' : '0') + '</td><td class="small">' + (q.commonWrong ? esc(q.commonWrong) + ' <span class="muted">(' + pct(q.commonWrongShare) + ' of wrong)</span>' : '—') + '</td></tr>';
        }).join('') + '</tbody></table></div></section></div>';
      html += '<div class="stack"><section class="panel"><h2>Spread of the class</h2>' + stacked +
        (causes.length ? '<h3 class="mt small mono muted">MAIN CAUSE FOR STUDENTS WHO FIND IT DIFFICULT</h3><p>' + causes.map(function (c) { return '<span class="chip">' + esc(CAUSES[c] || c) + ' · ' + rep.causes[c] + '</span>'; }).join(' ') + '</p>' : '') + '</section>' +
        '<section class="panel"><h2>Students <span class="mono">highest first</span></h2><ul class="list">' + rep.students.map(function (x) {
          var r = x.result;
          return '<li><a class="grow" href="#" data-st="' + esc(x.studentId) + '"><b>' + esc(x.name) + '</b><br><span class="small muted">' + esc(x.rollNo) + (r.mainCause ? ' · ' + esc(CAUSES[r.mainCause] || '') : '') + '</span></a>' + zoneTag(r.band, r.index) + '</li>';
        }).join('') + '</ul></section></div></div>';
      draw(s.code + ' · ' + t.name, html);
      wireBatch();
      $$('[data-st]').forEach(function (a) {
        a.addEventListener('click', function (e) { e.preventDefault(); openStudentTopic(a.getAttribute('data-st'), id); });
      });
    });
  });

  on('students', ['TEACHER', 'ADMIN'], function () {
    return api('GET', '/api/students' + (bq() ? '?' + bq() : '')).then(function (list) {
      var html = '<div class="page-head"><div><p class="eyebrow mono">Sheet B1</p><h1>Students</h1><p>Sorted by the number of difficult topics (peaks).</p></div><div class="actions toolbar"><input type="search" id="stSearch" placeholder="Search name or roll no" aria-label="Search">' + batchPicker() + '</div></div>';
      list.sort(function (a, b) { return b.hard - a.hard || b.critical - a.critical || a.rollNo.localeCompare(b.rollNo); });
      html += '<section class="panel"><div class="table-wrap"><table class="tbl" id="stTable"><thead><tr><th>Roll no</th><th>Name</th><th>Batch</th><th class="num">Attempts</th><th class="num">Right</th><th class="num">Surveyed</th><th>Peaks</th><th>Last active</th></tr></thead><tbody>' +
        list.map(function (s) {
          return '<tr class="click" data-href="#/student/' + esc(s.id) + '" data-q="' + esc((s.name + ' ' + s.rollNo).toLowerCase()) + '"><td class="mono">' + esc(s.rollNo) + '</td><td><b>' + esc(s.name) + '</b>' + (s.active ? '' : ' <span class="chip">off</span>') + '</td><td>' + esc(s.batch) + '</td>' +
            '<td class="num">' + s.tests + '</td><td class="num">' + pct(s.avgScore) + '</td><td class="num">' + s.surveyed + '/' + s.topics + '</td>' +
            '<td>' + (s.hard ? '<span class="zone z-' + (s.critical ? 'CRITICAL' : 'DIFFICULT') + '">' + s.hard + (s.critical ? ' · ' + s.critical + ' summit' : '') + '</span>' : '<span class="zone z-EASY">0</span>') + '</td><td class="small nowrap">' + esc(s.lastActive ? niceDate(s.lastActive) : '—') + '</td></tr>';
        }).join('') + '</tbody></table></div></section>';
      draw('Sheet B1 · Students', html);
      wireBatch();
      wireRows();
      $('#stSearch').addEventListener('input', function () {
        var q = this.value.trim().toLowerCase();
        $$('#stTable tbody tr').forEach(function (tr) { tr.classList.toggle('hidden', q && tr.getAttribute('data-q').indexOf(q) < 0); });
      });
    });
  });

  on('student', ['TEACHER', 'ADMIN'], function (id) {
    var sid = pickSubject();
    return api('GET', '/api/students/' + encodeURIComponent(id) + '/map').then(function (m) {
      var st = m.student, s = subjectById(sid);
      var results = m.results.filter(function (r) { return r.subjectId === sid; });
      var hard = m.results.filter(function (r) { return r.band === 'DIFFICULT' || r.band === 'CRITICAL'; });
      var html = '<div class="page-head"><div><p class="eyebrow mono">' + esc(st.rollNo) + ' · ' + esc(st.batch) + '</p><h1>' + esc(st.name) + '</h1>' +
        '<p>' + plural(hard.length, 'difficult topic') + ' across all subjects · ' + plural(m.attempts.length, 'recent attempt') + '</p></div>' +
        '<div class="actions"><button class="btn primary" type="button" id="newPlan">Make a support plan</button></div></div>';
      html += '<div class="grid-main"><div class="stack"><section class="panel"><div class="terrain-head"><h3>' + esc(s.name) + '</h3>' + subjectTabs(sid) + '</div>' + terrain(results, { label: st.name + ' map' }) + '</section>' +
        '<section class="panel"><h2>Topic readings · ' + esc(s.code) + '</h2>' + topicRows(results.slice().sort(function (a, b) { return b.index - a.index; })) + '</section></div>';
      html += '<div class="stack"><section class="panel"><h2>Study route <span class="mono">all subjects</span></h2>' + routeList(m.plan.slice(0, 6), true) + '</section>' +
        '<section class="panel"><h2>Support plans</h2>' + planList(m.supportPlans) + '</section>' +
        '<section class="panel"><h2>Recent attempts</h2>' + attemptList(m.attempts, false) + '</section></div></div>';
      draw('Student · ' + st.name, html);
      wireSubjectTabs();
      var byId = {};
      m.results.forEach(function (r) { byId[r.topicId] = r; });
      var open = function (tid) {
        var r = byId[tid], step = m.plan.filter(function (p) { return p.result.topicId === tid; })[0];
        r.advice = step ? step.advice : '';
        topicModal(r, st);
      };
      wirePeaks(open);
      $$('[data-topic-open]').forEach(function (a) { a.addEventListener('click', function (e) { e.preventDefault(); open(a.getAttribute('data-topic-open')); }); });
      $('#newPlan').addEventListener('click', function () { planForm(st, hard[0] || null, m.results); });
      wirePlanButtons();
    });
  });

  function planList(list) {
    if (!list.length) return '<div class="empty">No support plans.</div>';
    return '<ul class="list">' + list.map(function (p) {
      var change = p.indexAtStart >= 0 && p.indexNow >= 0 ? p.indexNow - p.indexAtStart : null;
      return '<li><span class="grow"><b>' + esc(p.action) + '</b> · ' + esc(p.topic) + ' ' + subj(p.subject, p.color) + '<br><span class="small muted">' + esc(p.createdBy) + ' · ' +
        (p.status === 'DONE' ? 'done ' + esc(niceDate(p.doneAt)) : (p.overdue ? '<span class="bad">overdue</span> ' : 'due ') + esc(niceDate(p.dueDate))) + '</span>' +
        (p.note ? '<br><span class="small">' + esc(p.note) + '</span>' : '') + '</span>' +
        '<span class="change mono small">DI ' + di(p.indexAtStart) + ' → ' + di(p.indexNow) + (change != null ? ' <b class="' + (change < -0.5 ? 'good' : change > 0.5 ? 'bad' : 'muted') + '">' + (change < -0.5 ? '▼' : change > 0.5 ? '▲' : '=') + '</b>' : '') + '</span>' +
        (ME.role !== 'STUDENT' ? '<span><button class="btn small" type="button" data-plan-status="' + esc(p.id) + '" data-to="' + (p.status === 'OPEN' ? 'DONE' : 'OPEN') + '">' + (p.status === 'OPEN' ? 'Mark done' : 'Re-open') + '</button> ' +
          '<button class="btn small danger" type="button" data-plan-del="' + esc(p.id) + '">Delete</button></span>' : '') + '</li>';
    }).join('') + '</ul>';
  }
  function wirePlanButtons() {
    $$('[data-plan-status]').forEach(function (b) {
      b.addEventListener('click', function () {
        api('POST', '/api/plans/' + b.getAttribute('data-plan-status') + '/status', { status: b.getAttribute('data-to') })
          .then(function () { toast('Plan updated.'); route(); }).catch(function (e) { toast(e.message); });
      });
    });
    $$('[data-plan-del]').forEach(function (b) {
      b.addEventListener('click', function () {
        modal('Delete this support plan?', '<p>This cannot be undone.</p>', [{ label: 'Delete', danger: true, run: function () {
          return api('DELETE', '/api/plans/' + b.getAttribute('data-plan-del')).then(function () { route(); return 'Plan deleted.'; });
        } }]);
      });
    });
  }

  function planForm(student, topicResult, allResults) {
    var studentsP = student ? Promise.resolve([student]) : api('GET', '/api/students');
    studentsP.then(function (studs) {
      var due = new Date(Date.now() + 7 * 864e5).toISOString().slice(0, 10);
      var topicsOpts = [];
      if (allResults) {
        allResults.slice().sort(function (a, b) { return b.index - a.index; }).forEach(function (r) {
          topicsOpts.push([r.topicId, r.topic + ' — ' + (r.index >= 0 ? 'DI ' + di(r.index) : 'no data')]);
        });
      } else if (topicResult) topicsOpts.push([topicResult.topicId, topicResult.topic + ' — DI ' + di(topicResult.index)]);
      var body = (student ? '<p><b>' + esc(student.name) + '</b> · ' + esc(student.rollNo || '') + '</p><input type="hidden" name="studentId" value="' + esc(student.id) + '">'
          : field('Student', select('studentId', studs.map(function (s) { return [s.id, s.rollNo + ' · ' + s.name]; }), ''))) +
        (topicsOpts.length ? field('Topic', select('topicId', topicsOpts, topicResult ? topicResult.topicId : '')) : '') +
        field('Kind of help', select('action', META.actions, META.actions[0])) +
        field('Due date', '<input type="date" name="dueDate" value="' + due + '">') +
        field('Note (optional)', '<textarea name="note" maxlength="400" placeholder="What exactly will be done?"></textarea>') +
        '<p class="small muted">The current Difficulty Index is saved with the plan, so you can see later whether it helped.</p>';
      modal('New support plan', body, [{ label: 'Save plan', primary: true, run: function (f) {
        return api('POST', '/api/plans', f).then(function () { route(); return 'Support plan saved.'; });
      } }]);
    }).catch(function (e) { toast(e.message); });
  }

  on('plans', ['TEACHER', 'ADMIN'], function () {
    return api('GET', '/api/plans?status=' + state.planStatus).then(function (list) {
      var html = '<div class="page-head"><div><p class="eyebrow mono">Sheet B2</p><h1>Support plans</h1><p>Help for one student on one difficult topic. The arrow shows how the Difficulty Index has moved since the plan began.</p></div>' +
        '<div class="actions"><button class="btn primary" type="button" id="newPlan2">New plan</button></div></div>';
      html += '<div class="toolbar"><div class="seg">' + [['OPEN', 'Open'], ['DONE', 'Done'], ['', 'All']].map(function (s) {
        return '<button type="button" data-ps="' + s[0] + '" class="' + (state.planStatus === s[0] ? 'on' : '') + '">' + s[1] + '</button>';
      }).join('') + '</div></div>';
      html += '<section class="panel">' + planList(list) + '</section>';
      draw('Sheet B2 · Support plans', html);
      $$('[data-ps]').forEach(function (b) { b.addEventListener('click', function () { state.planStatus = b.getAttribute('data-ps'); route(); }); });
      wirePlanButtons();
      $('#newPlan2').addEventListener('click', function () {
        // choose a student first, then their topics
        api('GET', '/api/students').then(function (studs) {
          modal('New support plan', field('Student', select('studentId', studs.map(function (s) { return [s.id, s.rollNo + ' · ' + s.name + (s.hard ? ' (' + s.hard + ' peaks)' : '')]; }), '')),
            [{ label: 'Next', primary: true, run: function (f) {
              return api('GET', '/api/students/' + f.studentId + '/map').then(function (m) {
                setTimeout(function () { planForm(m.student, null, m.results); }, 10);
              });
            } }]);
        });
      });
    });
  });

  on('attempts', ['TEACHER', 'ADMIN'], function () {
    return api('GET', '/api/attempts?limit=300').then(function (list) {
      draw('Sheet B3 · Attempts', '<div class="page-head"><div><p class="eyebrow mono">Sheet B3</p><h1>Attempts</h1><p>Newest 300 tests and practice sets.</p></div><div class="actions"><a class="btn" href="/api/export/attempts.csv">Export CSV</a></div></div><section class="panel">' + attemptTable(list, true) + '</section>');
      wireRows();
    });
  });

  // ------------------------------------------------------------------ question bank
  on('bank', ['TEACHER', 'ADMIN'], function () {
    var sid = pickSubject();
    return Promise.all([api('GET', '/api/topics?subject=' + sid), api('GET', '/api/questions?subject=' + sid), api('GET', '/api/subjects?all=1')]).then(function (res) {
      var topics = res[0], qs = res[1], subs = res[2];
      var s = subs.filter(function (x) { return x.id === sid; })[0];
      var topicName = {};
      topics.forEach(function (t) { topicName[t.topic.id] = t.topic.name; });
      if (state.bankTopic && !topicName[state.bankTopic]) state.bankTopic = '';
      var shown = qs.filter(function (q) { return !state.bankTopic || q.topicId === state.bankTopic; });
      var html = '<div class="page-head"><div><p class="eyebrow mono">Sheet C1</p><h1>Question bank · ' + esc(s.name) + '</h1><p>Topics with their prerequisites (the trails on the map) and the questions used in tests and practice.</p></div>' +
        '<div class="actions">' + (ME.role === 'ADMIN' ? '<button class="btn" type="button" id="editSubject">Edit subject</button><button class="btn" type="button" id="addSubject">New subject</button>' : '') + '</div></div>';
      html += '<div class="toolbar">' + subjectTabs(sid) + '</div>';
      html += '<div class="grid-main"><section class="panel"><h2>Questions <span class="mono">' + shown.length + '</span><span class="right"><button class="btn primary small" type="button" id="addQ">Add question</button></span></h2>' +
        '<div class="toolbar"><select id="topicFilter" aria-label="Topic"><option value="">All topics</option>' + topics.map(function (t) {
          return '<option value="' + esc(t.topic.id) + '"' + (t.topic.id === state.bankTopic ? ' selected' : '') + '>' + esc(t.topic.name) + '</option>';
        }).join('') + '</select></div>' +
        '<div class="table-wrap"><table class="tbl"><thead><tr><th>Question</th><th>Topic</th><th class="num">Level</th><th></th></tr></thead><tbody>' + shown.map(function (q) {
          return '<tr><td>' + esc(q.text) + (q.active ? '' : ' <span class="chip">off</span>') + '<br><span class="small muted">✓ ' + esc(q.options[q.answer]) + '</span></td><td class="small">' + esc(topicName[q.topicId] || '') + '</td><td class="num">' + ['', 'easy', 'med', 'hard'][q.level] + '</td>' +
            '<td class="nowrap"><button class="btn small" type="button" data-editq="' + esc(q.id) + '">Edit</button> <button class="btn small danger" type="button" data-delq="' + esc(q.id) + '">Delete</button></td></tr>';
        }).join('') + '</tbody></table></div></section>';
      html += '<section class="panel"><h2>Topics <span class="right"><button class="btn small" type="button" id="addT">Add topic</button></span></h2><ul class="list">' + topics.map(function (t) {
        return '<li><span class="grow"><span class="mono small">U' + t.topic.unit + '.' + t.topic.order + '</span> <b>' + esc(t.topic.name) + '</b>' + (t.topic.active ? '' : ' <span class="chip">off</span>') +
          '<br><span class="small muted">' + plural(t.questions, 'question') + ' · ' + t.topic.expectedSeconds + 's each' + (t.prerequisites.length ? ' · needs ' + esc(t.prerequisites.join(', ')) : '') + '</span></span>' +
          '<button class="btn small" type="button" data-editt="' + esc(t.topic.id) + '">Edit</button><button class="btn small danger" type="button" data-delt="' + esc(t.topic.id) + '">Delete</button></li>';
      }).join('') + '</ul></section></div>';
      draw('Sheet C1 · Question bank', html);
      wireSubjectTabs(function () { state.bankTopic = ''; route(); });
      $('#topicFilter').addEventListener('change', function () { state.bankTopic = this.value; route(); });
      $('#addQ').addEventListener('click', function () { questionForm(null, topics); });
      $$('[data-editq]').forEach(function (b) { b.addEventListener('click', function () { questionForm(qs.filter(function (q) { return q.id === b.getAttribute('data-editq'); })[0], topics); }); });
      $$('[data-delq]').forEach(function (b) {
        b.addEventListener('click', function () {
          modal('Delete this question?', '<p>If students have already answered it, it will be turned off instead (their answers stay).</p>', [{ label: 'Delete', danger: true, run: function () {
            return api('DELETE', '/api/questions/' + b.getAttribute('data-delq')).then(function (r) { route(); return r.message; });
          } }]);
        });
      });
      $('#addT').addEventListener('click', function () { topicForm(null, topics, sid); });
      $$('[data-editt]').forEach(function (b) { b.addEventListener('click', function () { topicForm(topics.filter(function (t) { return t.topic.id === b.getAttribute('data-editt'); })[0].topic, topics, sid); }); });
      $$('[data-delt]').forEach(function (b) {
        b.addEventListener('click', function () {
          modal('Delete this topic?', '<p>A topic with questions, answers or that other topics need is turned off instead.</p>', [{ label: 'Delete', danger: true, run: function () {
            return api('DELETE', '/api/topics/' + b.getAttribute('data-delt')).then(function (r) { route(); return r.message; });
          } }]);
        });
      });
      if ($('#editSubject')) $('#editSubject').addEventListener('click', function () { subjectForm(s); });
      if ($('#addSubject')) $('#addSubject').addEventListener('click', function () { subjectForm(null); });
    });
  });

  function reloadMeta() { return api('GET', '/api/me').then(function (m) { META = m; }); }

  function subjectForm(s) {
    s = s || { code: '', name: '', description: '', color: 'moss', active: true };
    modal(s.id ? 'Edit subject' : 'New subject',
      '<div class="form-row">' + field('Code', input('code', s.code, 'maxlength="12" placeholder="CSIT135"')) + field('Map colour', select('color', ['moss', 'river', 'clay', 'heather', 'ochre', 'slate'], s.color)) + '</div>' +
      field('Name', input('name', s.name, 'maxlength="80"')) + field('Description', '<textarea name="description" maxlength="300">' + esc(s.description) + '</textarea>') +
      (s.id ? '<label class="checks"><label><input type="checkbox" name="active"' + (s.active ? ' checked' : '') + '> Active (students can see it)</label></label>' : ''),
      [{ label: 'Save', primary: true, run: function (f) {
        if (!s.id) f.active = true;
        return api(s.id ? 'PUT' : 'POST', '/api/subjects' + (s.id ? '/' + s.id : ''), f).then(function (saved) {
          state.subject = saved.id;
          return reloadMeta().then(function () { route(); return 'Subject saved.'; });
        });
      } }]);
  }

  function topicForm(t, topics, sid) {
    t = t || { name: '', unit: 1, order: 1, expectedSeconds: 60, tip: '', prerequisiteIds: [], active: true };
    var others = topics.filter(function (x) { return x.topic.id !== t.id; });
    modal(t.id ? 'Edit topic' : 'New topic',
      field('Topic name', input('name', t.name, 'maxlength="80"')) +
      '<div class="form-row">' + field('Unit', input('unit', t.unit, 'type="number" min="1" max="12"')) + field('Order in unit', input('order', t.order, 'type="number" min="1" max="50"')) +
      field('Expected seconds', input('expectedSeconds', t.expectedSeconds, 'type="number" min="10" max="600"'), 'per question') + '</div>' +
      '<div class="field">Learn these first (prerequisites)<div class="checks">' + (others.length ? others.map(function (x) {
        return '<label><input type="checkbox" name="prerequisiteIds" data-multi value="' + esc(x.topic.id) + '"' + (t.prerequisiteIds.indexOf(x.topic.id) >= 0 ? ' checked' : '') + '> ' + esc(x.topic.name) + '</label>';
      }).join('') : '<span class="muted">No other topics yet.</span>') + '</div></div>' +
      field('Study tip', '<textarea name="tip" maxlength="300">' + esc(t.tip) + '</textarea>', 'Shown in students\' study routes.') +
      (t.id ? '<div class="checks"><label><input type="checkbox" name="active"' + (t.active ? ' checked' : '') + '> Active</label></div>' : ''),
      [{ label: 'Save', primary: true, run: function (f) {
        f.subjectId = sid;
        f.prerequisiteIds = f.prerequisiteIds || [];
        if (!t.id) f.active = true;
        return api(t.id ? 'PUT' : 'POST', '/api/topics' + (t.id ? '/' + t.id : ''), f).then(function () { route(); return 'Topic saved.'; });
      } }]);
  }

  function questionForm(q, topics) {
    q = q || { topicId: state.bankTopic || (topics[0] && topics[0].topic.id), text: '', options: ['', '', '', ''], answer: 0, level: 2, hint: '', explanation: '', active: true };
    var opts = q.options.slice();
    while (opts.length < 4) opts.push('');
    var letters = 'ABCDE';
    modal(q.id ? 'Edit question' : 'New question',
      field('Topic', select('topicId', topics.map(function (t) { return [t.topic.id, t.topic.name]; }), q.topicId)) +
      field('Question', '<textarea name="text" maxlength="600">' + esc(q.text) + '</textarea>') +
      '<div class="field">Options — tick the right one' + opts.map(function (o, i) {
        return '<div class="opt-edit"><input type="radio" name="answer" value="' + i + '"' + (q.answer === i ? ' checked' : '') + ' aria-label="Option ' + letters[i] + ' is right"><input name="opt' + i + '" value="' + esc(o) + '" maxlength="200" placeholder="Option ' + letters[i] + (i >= 2 ? ' (optional)' : '') + '"></div>';
      }).join('') + '</div>' +
      '<div class="form-row">' + field('Level', select('level', [[1, 'Easy'], [2, 'Medium'], [3, 'Hard']], q.level)) + '</div>' +
      field('Hint', input('hint', q.hint, 'maxlength="300"'), 'Using it is recorded as a signal.') +
      field('Explanation', '<textarea name="explanation" maxlength="600">' + esc(q.explanation) + '</textarea>', 'Shown after the answer.') +
      (q.id ? '<div class="checks"><label><input type="checkbox" name="active"' + (q.active ? ' checked' : '') + '> Active (used in quizzes)</label></div>' : ''),
      [{ label: 'Save', primary: true, run: function (f) {
        var options = [], answer = -1;
        for (var i = 0; i < 4; i++) {
          var v = (f['opt' + i] || '').trim();
          if (v) { if (String(i) === f.answer) answer = options.length; options.push(v); }
        }
        var body = { topicId: f.topicId, text: f.text, options: options, answer: answer, level: +f.level, hint: f.hint, explanation: f.explanation, active: q.id ? !!f.active : true };
        return api(q.id ? 'PUT' : 'POST', '/api/questions' + (q.id ? '/' + q.id : ''), body).then(function () { route(); return 'Question saved.'; });
      } }]);
  }

  // ------------------------------------------------------------------ admin
  on('users', ['ADMIN'], function () {
    return api('GET', '/api/users').then(function (list) {
      var html = '<div class="page-head"><div><p class="eyebrow mono">Sheet D1</p><h1>Logins</h1><p>Teachers, students and admins. Turning a login off signs that person out at once.</p></div><div class="actions"><button class="btn primary" type="button" id="addUser">Add login</button></div></div>';
      html += '<section class="panel"><div class="table-wrap"><table class="tbl"><thead><tr><th>Name</th><th>Username</th><th>Role</th><th>Roll no</th><th>Batch</th><th>Status</th><th></th></tr></thead><tbody>' + list.map(function (u) {
        return '<tr><td><b>' + esc(u.name) + '</b></td><td class="mono">' + esc(u.username) + '</td><td>' + esc(u.roleLabel) + '</td><td class="mono small">' + esc(u.rollNo) + '</td><td class="small">' + esc(u.batch) + '</td>' +
          '<td>' + (u.active ? '<span class="chip">active</span>' : '<span class="chip bad">off</span>') + '</td><td class="nowrap"><button class="btn small" type="button" data-editu="' + esc(u.id) + '">Edit</button> ' +
          '<button class="btn small" type="button" data-toggleu="' + esc(u.id) + '" data-to="' + (!u.active) + '">' + (u.active ? 'Turn off' : 'Turn on') + '</button></td></tr>';
      }).join('') + '</tbody></table></div></section>';
      draw('Sheet D1 · Logins', html);
      $('#addUser').addEventListener('click', function () { userForm(null); });
      $$('[data-editu]').forEach(function (b) { b.addEventListener('click', function () { userForm(list.filter(function (u) { return u.id === b.getAttribute('data-editu'); })[0]); }); });
      $$('[data-toggleu]').forEach(function (b) {
        b.addEventListener('click', function () {
          api('POST', '/api/users/' + b.getAttribute('data-toggleu') + '/active', { active: b.getAttribute('data-to') === 'true' })
            .then(function () { toast('Login updated.'); route(); }).catch(function (e) { toast(e.message); });
        });
      });
    });
  });

  function userForm(u) {
    u = u || { username: '', name: '', role: 'STUDENT', rollNo: '', batch: META.batches[0] || '' };
    modal(u.id ? 'Edit login' : 'Add login',
      '<div class="form-row">' + field('Full name', input('name', u.name, 'maxlength="60"')) + field('Username', input('username', u.username, 'maxlength="30" autocomplete="off"')) + '</div>' +
      '<div class="form-row">' + field('Role', select('role', [['STUDENT', 'Student'], ['TEACHER', 'Teacher'], ['ADMIN', 'Admin']], u.role)) +
      field('Roll no', input('rollNo', u.rollNo, 'maxlength="30"'), 'Students only') + field('Batch', input('batch', u.batch, 'maxlength="30" list="batchList"')) + '</div>' +
      '<datalist id="batchList">' + META.batches.map(function (b) { return '<option value="' + esc(b) + '">'; }).join('') + '</datalist>' +
      field(u.id ? 'New password (leave empty to keep)' : 'Password', '<input type="password" name="password" autocomplete="new-password">', 'At least 8 characters with a letter and a number.'),
      [{ label: 'Save', primary: true, run: function (f) {
        return api(u.id ? 'PUT' : 'POST', '/api/users' + (u.id ? '/' + u.id : ''), f).then(function () {
          return reloadMeta().then(function () { route(); return 'Login saved.'; });
        });
      } }]);
  }

  on('settings', ['ADMIN'], function () {
    return api('GET', '/api/settings').then(function (s) {
      var w = function (name, label, help) { return field(label, input(name, s[name], 'type="number" min="0" max="100" step="1"'), help); };
      var html = '<div class="page-head"><div><p class="eyebrow mono">Sheet D2</p><h1>Detector settings</h1><p>How much each signal adds to the Difficulty Index, and where each height zone begins. Weights are scaled to add up to 100%, so only their sizes compared with each other matter.</p></div></div>';
      html += '<form id="setForm" class="grid2"><section class="panel"><h2>Signal weights</h2><div class="form-row">' +
        w('wWrong', 'Wrong answers', 'biggest signal') + w('wSlow', 'Slow answers', 'vs expected time') + w('wHints', 'Hints used', 'share of questions') +
        w('wSureWrong', 'Wrong while sure', 'misconception') + w('wSelf', 'Student says hard', '1–5 rating') + w('wDecline', 'Getting worse', 'recent vs earlier') + '</div>' +
        '<div class="signals" id="weightBars"></div></section>' +
        '<section class="panel"><h2>Height zones</h2><div class="form-row">' +
        field('Moderate from', input('moderateFrom', s.moderateFrom, 'type="number" min="1" max="99"')) +
        field('Difficult from', input('difficultFrom', s.difficultFrom, 'type="number" min="1" max="99"')) +
        field('Very difficult from', input('criticalFrom', s.criticalFrom, 'type="number" min="1" max="99"')) + '</div>' +
        '<div class="form-row">' + field('Minimum answers', input('minAnswers', s.minAnswers, 'type="number" min="1" max="20"'), 'fewer = "not enough data"') +
        field('Half-life (days)', input('halfLifeDays', s.halfLifeDays, 'type="number" min="1" max="365"'), 'an answer this old counts half') + '</div>' +
        '<h2 class="mt">Institution</h2>' + field('Institution', input('institution', s.institution, 'maxlength="100"')) + field('Department', input('department', s.department, 'maxlength="100"')) +
        '<p class="err-text" id="setErr"></p><button class="btn primary" type="submit">Save settings</button></section></form>';
      draw('Sheet D2 · Settings', html);
      var paint = function () {
        var f = formData($('#setForm')), keys = ['wWrong', 'wSlow', 'wHints', 'wSureWrong', 'wSelf', 'wDecline'], tot = 0;
        keys.forEach(function (k) { tot += Math.max(0, +f[k] || 0); });
        $('#weightBars').innerHTML = '<h3 class="small mono muted mt">SHARE OF THE INDEX</h3>' + keys.map(function (k) {
          var v = tot ? Math.max(0, +f[k] || 0) / tot : 0;
          return '<div class="signal"><span>' + esc(WEIGHT_NAMES[k]) + '</span>' + bar(v, 1, 'z-MODERATE') + '<span class="v">' + Math.round(v * 100) + '%</span></div>';
        }).join('');
        $$('[data-w]', $('#weightBars')).forEach(function (el) { el.style.width = el.getAttribute('data-w') + '%'; });
      };
      paint();
      $('#setForm').addEventListener('input', paint);
      $('#setForm').addEventListener('submit', function (e) {
        e.preventDefault();
        $('#setErr').textContent = '';
        api('PUT', '/api/settings', formData($('#setForm'))).then(function () {
          return reloadMeta().then(function () { paintKey(); boot(); toast('Settings saved. Every map is re-surveyed with the new settings.'); });
        }).catch(function (err) { $('#setErr').textContent = err.message; });
      });
    });
  });

  on('log', ['ADMIN'], function () {
    return api('GET', '/api/events').then(function (list) {
      draw('Sheet D3 · History', '<div class="page-head"><div><p class="eyebrow mono">Sheet D3</p><h1>History</h1><p>The newest 300 actions.</p></div></div><section class="panel"><div class="table-wrap"><table class="tbl"><thead><tr><th>When</th><th>Who</th><th>What</th><th>Details</th></tr></thead><tbody>' +
        list.map(function (e) { return '<tr><td class="nowrap small">' + esc(niceTime(e.at)) + '</td><td>' + esc(e.user) + '</td><td>' + esc(e.action) + '</td><td class="small">' + esc(e.detail) + '</td></tr>'; }).join('') +
        '</tbody></table></div></section>');
    });
  });

  // ------------------------------------------------------------------ start
  $$('[data-theme-toggle]').forEach(function (b) { b.addEventListener('click', toggleTheme); });
  paintThemeButtons();
  boot().catch(function () { showLogin(); });
})();
