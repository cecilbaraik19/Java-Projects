/* Pothi – RAG College Knowledge Assistant (frontend)
   Plain JavaScript, no libraries. One page app with hash routes (#/ask, #/library ...).
   All text from the server is escaped with esc() before it is put into the page. */
(function () {
  'use strict';

  // ================================================================== helpers
  const $ = (s, r = document) => r.querySelector(s);
  const $$ = (s, r = document) => Array.from(r.querySelectorAll(s));
  const esc = (s) => String(s == null ? '' : s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const pct = (x) => Math.round((x || 0) * 100) + '%';
  const view = $('#view');
  let ME = null;
  let gapCount = 0;

  const CATS = ['Academics', 'Examinations', 'Fees & Scholarships', 'Hostel & Campus Life', 'Library',
    'Placements', 'Rules & Conduct', 'Courses & Syllabus', 'Offices & Contacts', 'Calendar'];
  const catTab = (c) => Math.max(1, CATS.indexOf(c) + 1);

  async function api(method, path, body) {
    const opt = { method, credentials: 'same-origin', headers: {} };
    if (body !== undefined) { opt.headers['Content-Type'] = 'application/json'; opt.body = JSON.stringify(body); }
    let res;
    try { res = await fetch('/api' + path, opt); } catch (e) { throw new Error('The server is not reachable. Is the app still running?'); }
    let data = null;
    try { data = await res.json(); } catch (e) { /* empty */ }
    if (res.status === 401 && path !== '/login') { showLogin(); throw new Error((data && data.error) || 'Please sign in.'); }
    if (!res.ok) throw new Error((data && data.error) || ('Request failed (' + res.status + ')'));
    return data;
  }

  let toastTimer;
  function toast(msg, bad) {
    const t = $('#toast');
    t.textContent = msg;
    t.className = 'toast show' + (bad ? ' bad' : '');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => { t.className = 'toast'; }, 3200);
  }

  function fmtDate(ms) {
    if (!ms) return '–';
    return new Date(ms).toLocaleDateString('en-IN', { day: 'numeric', month: 'short', year: 'numeric' });
  }
  function fmtDay(iso) {
    if (!iso) return '–';
    return new Date(iso + 'T00:00:00').toLocaleDateString('en-IN', { day: 'numeric', month: 'short', year: 'numeric' });
  }
  function ago(ms) {
    const s = Math.max(0, (Date.now() - ms) / 1000);
    if (s < 60) return 'just now';
    if (s < 3600) return Math.floor(s / 60) + ' min ago';
    if (s < 86400) return Math.floor(s / 3600) + ' h ago';
    if (s < 86400 * 7) return Math.floor(s / 86400) + ' d ago';
    return fmtDate(ms);
  }

  /** CSP blocks inline style attributes, so sizes and colours are set here through the DOM. */
  function applyDyn(root) {
    $$('[data-w]', root).forEach((el) => { el.style.width = Math.max(0, Math.min(100, +el.dataset.w)) + '%'; });
    $$('[data-tc]', root).forEach((el) => { el.style.setProperty('--tc', 'var(--t' + el.dataset.tc + ')'); });
  }

  /** Puts a page into the view. A fresh wrapper each time, so old click handlers go away with the old page. */
  function setView(html) {
    const root = document.createElement('div');
    root.innerHTML = html;
    view.replaceChildren(root);
    applyDyn(root);
    return root;
  }

  // ------------------------------------------------------------------ modal
  const modal = $('#modal');
  let modalAction = null;
  function openModal(title, bodyHtml, okLabel, onOk, opts) {
    opts = opts || {};
    $('#modalTitle').textContent = title;
    $('#modalBody').innerHTML = bodyHtml;
    applyDyn($('#modalBody'));
    $('#modalErr').textContent = '';
    const foot = $('#modalFoot');
    foot.innerHTML = '';
    const cancel = document.createElement('button');
    cancel.type = 'button'; cancel.className = 'btn'; cancel.textContent = okLabel ? 'Cancel' : 'Close';
    cancel.addEventListener('click', () => modal.close());
    foot.appendChild(cancel);
    modalAction = onOk;
    if (okLabel) {
      const ok = document.createElement('button');
      ok.type = 'submit'; ok.className = 'btn ' + (opts.danger ? 'danger' : 'primary'); ok.textContent = okLabel; ok.id = 'modalOk';
      foot.appendChild(ok);
    }
    modal.showModal();
    const first = $('#modalBody input, #modalBody textarea, #modalBody select');
    if (first) first.focus();
    if (opts.onOpen) opts.onOpen($('#modalBody'));
  }
  $('#modalClose').addEventListener('click', () => modal.close());
  $('#modalForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    if (!modalAction) { modal.close(); return; }
    const ok = $('#modalOk');
    if (ok) ok.disabled = true;
    try {
      const keep = await modalAction($('#modalBody'));
      if (keep !== true) modal.close();
    } catch (err) {
      $('#modalErr').textContent = err.message;
    } finally {
      if (ok) ok.disabled = false;
    }
  });
  const val = (root, name) => { const el = root.querySelector('[name="' + name + '"]'); return el ? (el.type === 'checkbox' ? el.checked : el.value) : undefined; };

  // ------------------------------------------------------------------ theme
  function currentTheme() {
    const set = document.documentElement.getAttribute('data-theme');
    if (set) return set;
    return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
  }
  function paintThemeButtons() {
    const t = currentTheme();
    $$('[data-theme-toggle]').forEach((b) => { b.textContent = t === 'dark' ? 'Light mode' : 'Dark mode'; });
  }
  document.addEventListener('click', (e) => {
    const b = e.target.closest('[data-theme-toggle]');
    if (!b) return;
    const next = currentTheme() === 'dark' ? 'light' : 'dark';
    document.documentElement.setAttribute('data-theme', next);
    try { localStorage.setItem('pothi-theme', next); } catch (err) { /* ignore */ }
    paintThemeButtons();
  });
  paintThemeButtons();

  // ================================================================== sign in
  function showLogin() {
    $('#app').classList.add('hidden');
    $('#login').classList.remove('hidden');
    ME = null;
    setTimeout(() => $('#lu').focus(), 30);
  }
  $$('.demo-row').forEach((b) => b.addEventListener('click', () => {
    const [u, p] = b.dataset.demo.split(':');
    $('#lu').value = u; $('#lp').value = p;
    $('#loginForm').requestSubmit();
  }));
  $('#loginForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    $('#loginErr').textContent = '';
    try {
      ME = await api('POST', '/login', { username: $('#lu').value, password: $('#lp').value });
      $('#lp').value = '';
      startApp();
    } catch (err) { $('#loginErr').textContent = err.message; }
  });
  $('#logoutBtn').addEventListener('click', async () => {
    try { await api('POST', '/logout'); } catch (e) { /* ignore */ }
    location.hash = '#/ask';
    showLogin();
  });
  $('#who').addEventListener('click', () => {
    openModal('Change password',
      '<label class="field">Current password<input name="current" type="password" autocomplete="current-password" required></label>' +
      '<label class="field">New password<input name="password" type="password" autocomplete="new-password" required><span class="hint">At least 8 characters, with a letter and a number.</span></label>',
      'Change password', async (b) => {
        await api('POST', '/me/password', { current: val(b, 'current'), password: val(b, 'password') });
        toast('Password changed');
      });
  });
  $('#menuBtn').addEventListener('click', () => {
    const open = $('#cover').classList.toggle('open');
    $('#menuBtn').setAttribute('aria-expanded', String(open));
  });

  // ================================================================== routes
  const ROUTES = {
    ask: { title: 'Ask', tab: 3, render: renderAsk },
    history: { title: 'My questions', tab: 2, render: renderHistory },
    library: { title: 'Library', tab: 1, render: renderLibrary },
    doc: { title: 'Document', tab: 1, nav: 'library', render: renderDoc },
    verified: { title: 'Verified answers', tab: 5, render: renderVerified },
    trace: { title: 'How answers are found', tab: 4, render: renderTrace },
    gaps: { title: 'Gaps', tab: 6, curate: true, render: renderGaps },
    insights: { title: 'Insights', tab: 7, curate: true, render: renderInsights },
    eval: { title: 'Test bench', tab: 8, admin: true, render: renderEval },
    people: { title: 'People', tab: 9, admin: true, render: renderPeople },
    settings: { title: 'Settings', tab: 10, admin: true, render: renderSettings },
    how: { title: 'How Pothi works', tab: 10, render: renderHow },
  };

  function parseHash() {
    const h = location.hash.replace(/^#\/?/, '');
    const [path, qs] = h.split('?');
    const parts = path.split('/').filter(Boolean);
    const params = new URLSearchParams(qs || '');
    return { name: parts[0] || 'ask', id: parts[1] ? decodeURIComponent(parts[1]) : null, params };
  }

  function buildNav() {
    const r = parseHash();
    const active = (ROUTES[r.name] && ROUTES[r.name].nav) || r.name;
    const link = (name, label, extra) => {
      const route = ROUTES[name];
      return '<a href="#/' + name + '" data-tc="' + route.tab + '"' + (active === name ? ' aria-current="page"' : '') + '><span>' + esc(label) + '</span>' + (extra || '') + '</a>';
    };
    let html = link('ask', 'Ask') + link('history', 'My questions') + link('library', 'Library') +
      link('verified', 'Verified answers') + link('trace', 'How answers are found');
    if (ME.can.curate) {
      html += '<div class="group">For faculty</div>' + link('gaps', 'Gaps', gapCount ? '<span class="count">' + gapCount + '</span>' : '') + link('insights', 'Insights');
    }
    if (ME.can.admin) {
      html += '<div class="group">Admin</div>' + link('eval', 'Test bench') + link('people', 'People') + link('settings', 'Settings');
    }
    html += '<div class="group">Help</div>' + link('how', 'How Pothi works');
    $('#nav').innerHTML = html;
    applyDyn($('#nav'));
  }

  async function route() {
    if (!ME) return;
    const r = parseHash();
    let def = ROUTES[r.name];
    if (!def || (def.curate && !ME.can.curate) || (def.admin && !ME.can.admin)) { location.hash = '#/ask'; return; }
    $('#pageTitle').textContent = def.title;
    document.title = def.title + ' – Pothi';
    $('#cover').classList.remove('open');
    buildNav();
    view.innerHTML = '<p class="loading">Loading…</p>';
    try {
      await def.render(r);
    } catch (err) {
      setView('<div class="empty">' + esc(err.message) + '</div>');
    }
    if (!r.params.get('keepfocus')) view.focus({ preventScroll: true });
  }
  window.addEventListener('hashchange', route);

  async function startApp() {
    $('#login').classList.add('hidden');
    $('#app').classList.remove('hidden');
    $('#collegeName').textContent = ME.college;
    $('#who').textContent = ME.user.name + ' (' + ME.user.roleLabel + ')';
    const pill = $('#modePill');
    pill.textContent = ME.claude.on && ME.claude.available ? 'Answers written by Claude' : 'Offline answers';
    pill.classList.toggle('on', ME.claude.on && ME.claude.available);
    paintThemeButtons();
    if (ME.can.curate) refreshGapCount();
    if (!location.hash) location.hash = '#/ask';
    else route();
  }

  async function refreshGapCount() {
    try { const g = await api('GET', '/gaps'); gapCount = g.open; buildNav(); } catch (e) { /* ignore */ }
  }

  // ================================================================== answer rendering

  /** Turns "text [1] more [2]" into text with coloured flag buttons; "- " lines become a list. */
  function answerHtml(text, nSources) {
    const lines = esc(text).split('\n');
    let html = '', inList = false;
    for (const line of lines) {
      const li = line.match(/^- (.*)$/);
      if (li) {
        if (!inList) { html += '<ul>'; inList = true; }
        html += '<li>' + li[1] + '</li>';
      } else {
        if (inList) { html += '</ul>'; inList = false; }
        if (line.trim()) html += line + '\n';
      }
    }
    if (inList) html += '</ul>';
    return html.replace(/\[(\d)\]/g, (m, n) => +n <= Math.max(nSources, 0) && +n <= 5
      ? '<button type="button" class="cite c' + n + '" data-cite="' + n + '" aria-label="Source ' + n + '">' + n + '</button>' : m);
  }

  /** Marks the question's words inside a source passage. */
  function highlight(text, question) {
    const words = (question || '').toLowerCase().match(/[a-z0-9₹]{4,}/g) || [];
    const stop = new Set(['what', 'when', 'where', 'which', 'does', 'that', 'this', 'have', 'there', 'with', 'from', 'about', 'much', 'many', 'college', 'need', 'needed', 'required']);
    let html = esc(text);
    const uniq = [...new Set(words.filter((w) => !stop.has(w)))].slice(0, 8);
    for (const w of uniq) {
      const stem = w.length > 5 ? w.slice(0, w.length - 2) : w;
      html = html.replace(new RegExp('(^|[^a-z0-9&;])(' + stem.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '[a-z]*)', 'gi'), '$1<mark>$2</mark>');
    }
    return html;
  }

  function turnHtml(rec) {
    const n = (rec.sources || []).length;
    const cls = rec.mode === 'none' ? ' none' : rec.mode === 'verified' ? ' verified' : '';
    const modeText = rec.mode === 'verified' ? 'Verified answer by ' + esc(rec.verifiedBy || 'faculty')
      : rec.mode === 'claude' ? 'Written by Claude from ' + n + ' source' + (n === 1 ? '' : 's')
        : rec.mode === 'none' ? 'Not found in the documents'
          : 'Taken word-for-word from ' + n + ' source' + (n === 1 ? '' : 's');
    const traceQ = rec.standalone || rec.question;
    let h = '<article class="turn" data-id="' + esc(rec.id) + '">';
    h += '<p class="q">' + esc(rec.question) + '</p>';
    if (rec.standalone) h += '<p class="rewritten">Read as a follow-up: “' + esc(rec.standalone) + '”</p>';
    h += '<div class="answer' + cls + '">';
    if (rec.shortAnswer) h += '<p class="short">' + esc(rec.shortAnswer) + '</p>';
    h += '<div class="answer-text">' + answerHtml(rec.answer, n) + '</div>';
    if (rec.note) h += '<p class="note-bar">' + esc(rec.note) + '</p>';
    if (rec.unsupported && rec.unsupported.length) h += '<p class="unsupported">Check this: ' + rec.unsupported.length + ' sentence(s) could not be matched to the sources.</p>';
    if (rec.suggestions && rec.suggestions.length) {
      h += '<p class="suggest">The closest documents are: ' + rec.suggestions.map((s) => '<a href="#/doc/' + encodeURIComponent(s.docId) + '">' + esc(s.title) + '</a>').join('') + '</p>';
    }
    h += '<div class="answer-meta">';
    h += '<span class="conf ' + esc(rec.confidenceLabel).replace(/\s/g, '') + '"><span class="bars" aria-hidden="true"><i></i><i></i><i></i></span>' +
      (rec.mode === 'none' ? 'No answer' : esc(rec.confidenceLabel) + ' confidence') + '</span>';
    h += '<span>' + modeText + '</span>';
    h += '<a href="#/trace?q=' + encodeURIComponent(traceQ) + '">How this was found</a>';
    if (ME.can.curate && rec.mode !== 'verified') h += '<button type="button" class="linkish" data-make-verified>Save as verified answer</button>';
    if (rec.id && rec.id !== 'dry') {
      h += '<span class="vote"><button type="button" class="btn small" data-vote="up" aria-pressed="' + (rec.feedback === 'up') + '">Helpful</button>' +
        '<button type="button" class="btn small" data-vote="down" aria-pressed="' + (rec.feedback === 'down') + '">Not helpful</button></span>';
    }
    h += '</div></div>';
    if (n) {
      // sources the answer points to are shown; the other passages that were checked are folded away
      const cited = new Set((rec.answer.match(/\[(\d)\]/g) || []).map((x) => +x.slice(1, -1)));
      const slip = (s) => '<section class="slip c' + Math.min(5, s.n) + '" data-n="' + s.n + '" id="slip-' + esc(rec.id) + '-' + s.n + '">' +
          '<h4><a href="#/doc/' + encodeURIComponent(s.docId) + '">' + esc(s.title) + '</a></h4>' +
          '<div class="sec">' + esc(s.section) + (s.effectiveDate ? ', in force from ' + fmtDay(s.effectiveDate) : '') + '</div>' +
          '<div class="passage">' + highlight(s.text, traceQ) + '</div>' +
          '<button type="button" class="linkish more" data-more>Show the whole passage</button></section>';
      const shown = rec.sources.filter((s) => cited.has(s.n));
      const rest = rec.sources.filter((s) => !cited.has(s.n));
      if (shown.length) h += '<div class="slips">' + shown.map(slip).join('') + '</div>';
      if (rest.length) {
        h += '<details class="other-slips"' + (shown.length ? '' : ' open') + '><summary>' + (shown.length ? 'Other passages that were checked (' + rest.length + ')' : 'Related passages (' + rest.length + ')') +
          '</summary><div class="slips">' + rest.map(slip).join('') + '</div></details>';
      }
    }
    h += '</article>';
    return h;
  }

  function wireTurn(el, rec) {
    el.addEventListener('click', async (e) => {
      const cite = e.target.closest('[data-cite]');
      if (cite) {
        const slip = $('#slip-' + CSS.escape(rec.id) + '-' + cite.dataset.cite);
        if (slip) {
          const det = slip.closest('details');
          if (det) det.open = true;
          slip.classList.add('open');
          slip.scrollIntoView({ behavior: 'smooth', block: 'center' });
          slip.classList.remove('flash'); void slip.offsetWidth; slip.classList.add('flash');
        }
        return;
      }
      const more = e.target.closest('[data-more]');
      if (more) {
        const slip = more.closest('.slip');
        slip.classList.toggle('open');
        more.textContent = slip.classList.contains('open') ? 'Show less' : 'Show the whole passage';
        return;
      }
      const vote = e.target.closest('[data-vote]');
      if (vote) {
        const v = vote.getAttribute('aria-pressed') === 'true' ? '' : vote.dataset.vote;
        const send = async (note) => {
          await api('POST', '/questions/' + encodeURIComponent(rec.id) + '/feedback', { vote: v, note: note || '' });
          rec.feedback = v;
          $$('[data-vote]', el).forEach((b) => b.setAttribute('aria-pressed', String(b.dataset.vote === v)));
          toast(v === 'up' ? 'Thanks – marked as helpful' : v === 'down' ? 'Thanks – the faculty will see this in Gaps' : 'Vote removed');
        };
        if (v === 'down') {
          openModal('What was wrong?', '<label class="field">Tell the faculty what was missing or wrong (optional)<textarea name="note" maxlength="300"></textarea></label>',
            'Send', async (b) => send(val(b, 'note')));
        } else {
          try { await send(''); } catch (err) { toast(err.message, true); }
        }
        return;
      }
      if (e.target.closest('[data-make-verified]')) verifiedDialog(null, { question: rec.question, answer: rec.answer.replace(/\s*\[\d\]/g, ''), docIds: (rec.sources || []).map((s) => s.docId) });
    });
  }

  // ================================================================== Ask page
  let askState = { session: '', turns: [] };

  async function renderAsk(r) {
    const sid = r.params.get('s') || '';
    const prefill = r.params.get('q') || '';
    const [front, sessions, turns] = await Promise.all([
      api('GET', '/front'), api('GET', '/sessions'), sid ? api('GET', '/sessions/' + encodeURIComponent(sid)) : Promise.resolve([])]);
    askState = { session: sid, turns };

    const cats = '<option value="">All documents</option>' + ME.categories.map((c) => '<option>' + esc(c) + '</option>').join('');
    let html = '<div class="ask-layout"><div class="thread"><div id="turns"></div>';
    html += '<form class="composer" id="composer"><div class="composer-box">' +
      '<textarea id="qbox" aria-label="Your question" maxlength="500" placeholder="Ask about fees, exams, hostel, library, placements…" rows="2"></textarea>' +
      '<div class="composer-tools"><select id="qcat" aria-label="Search in">' + cats + '</select>' +
      '<span class="hint">Enter to ask, Shift+Enter for a new line</span><span class="spacer"></span>' +
      (sid ? '<a class="btn small" href="#/ask">New chat</a>' : '') +
      '<button class="btn primary" type="submit" id="askBtn">Ask</button></div></div></form></div>';

    html += '<aside class="rail">';
    html += '<section><h3>Recent chats</h3>' + (sessions.length ? '<ul>' + sessions.slice(0, 8).map((s) =>
      '<li><a href="#/ask?s=' + encodeURIComponent(s.id) + '"' + (s.id === sid ? ' class="current"' : '') + '>' + esc(s.title) +
      '<span class="sub">' + s.count + ' question' + (s.count === 1 ? '' : 's') + ', ' + ago(s.last) + '</span></a></li>').join('') + '</ul>'
      : '<p class="muted small">Your chats will appear here.</p>') + '</section>';
    html += '<section><h3>Asked most this fortnight</h3><ol>' + front.trending.slice(0, 6).map((t) =>
      '<li><button type="button" data-ask="' + esc(t.question) + '">' + esc(t.question) + '<span class="sub">asked ' + t.count + ' times</span></button></li>').join('') + '</ol></section>';
    html += '<section><h3>Recently updated documents</h3><ul>' + front.latest.map((d) =>
      '<li><a href="#/doc/' + encodeURIComponent(d.id) + '">' + esc(d.title) + '<span class="sub">' + esc(d.category) + ', ' + fmtDate(d.updatedAt) + '</span></a></li>').join('') + '</ul></section>';
    html += '</aside></div>';
    const root = setView(html);

    const box = $('#turns');
    if (!turns.length) {
      box.innerHTML = '<div class="welcome"><h2>What do you want to know about college?</h2>' +
        '<p>Pothi answers from ' + front.docs + ' official college documents (' + front.chunks + ' searchable passages). ' +
        'Every answer shows a coloured flag for each source, so you can check the exact rule yourself.</p>' +
        '<div class="starters">' + front.trending.slice(0, 6).map((t) => '<button type="button" class="starter" data-ask="' + esc(t.question) + '">' + esc(t.question) + '</button>').join('') + '</div></div>';
    } else {
      turns.forEach((t) => appendTurn(t));
    }

    const qbox = $('#qbox');
    qbox.value = prefill;
    qbox.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); $('#composer').requestSubmit(); }
    });
    $('#composer').addEventListener('submit', (e) => { e.preventDefault(); askNow(qbox.value); });
    root.addEventListener('click', (e) => {
      const b = e.target.closest('[data-ask]');
      if (b) askNow(b.dataset.ask);
    });
    if (turns.length) setTimeout(() => box.lastElementChild && box.lastElementChild.scrollIntoView({ block: 'start' }), 20);
    setTimeout(() => qbox.focus(), 30);
  }

  function appendTurn(rec) {
    const box = $('#turns');
    const wel = $('.welcome', box);
    if (wel) wel.remove();
    const wrap = document.createElement('div');
    wrap.innerHTML = turnHtml(rec);
    const el = wrap.firstElementChild;
    box.appendChild(el);
    wireTurn(el, rec);
    return el;
  }

  async function askNow(q) {
    q = (q || '').trim();
    if (q.length < 3) { toast('Please type a question first.', true); return; }
    const btn = $('#askBtn');
    const qbox = $('#qbox');
    btn.disabled = true;
    qbox.value = '';
    const box = $('#turns');
    const wel = $('.welcome', box);
    if (wel) wel.remove();
    const pending = document.createElement('div');
    pending.className = 'turn';
    pending.innerHTML = '<p class="q">' + esc(q) + '</p><p class="muted"><span class="thinking" aria-label="Searching"><i></i><i></i><i></i></span> Searching the college documents…</p>';
    box.appendChild(pending);
    pending.scrollIntoView({ behavior: 'smooth', block: 'end' });
    try {
      const rec = await api('POST', '/ask', { question: q, sessionId: askState.session, category: $('#qcat').value });
      pending.remove();
      const el = appendTurn(rec);
      el.scrollIntoView({ behavior: 'smooth', block: 'start' });
      if (!askState.session) {
        askState.session = rec.sessionId;
        history.replaceState(null, '', '#/ask?s=' + encodeURIComponent(rec.sessionId));
      }
      if (rec.status === 'unanswered' && ME.can.curate) refreshGapCount();
    } catch (err) {
      pending.remove();
      qbox.value = q;
      toast(err.message, true);
    } finally {
      btn.disabled = false;
      qbox.focus();
    }
  }

  // ================================================================== My questions
  async function renderHistory(r) {
    const q = r.params.get('q') || '';
    const list = await api('GET', '/history?q=' + encodeURIComponent(q));
    let html = '<p class="intro">Everything you have asked, newest first. Open a question to continue that chat.</p>';
    html += '<form class="toolbar" id="hsearch"><input type="search" name="q" placeholder="Search your questions" value="' + esc(q) + '"><button class="btn">Search</button></form>';
    if (!list.length) html += '<div class="empty">No questions yet. <a href="#/ask">Ask your first question</a>.</div>';
    else {
      html += '<div class="table-wrap"><table class="table"><thead><tr><th>Question</th><th>Answer</th><th>Confidence</th><th>Your vote</th><th>When</th></tr></thead><tbody>';
      for (const x of list) {
        html += '<tr><td><a href="#/ask?s=' + encodeURIComponent(x.sessionId) + '">' + esc(x.question) + '</a></td>' +
          '<td class="small">' + esc((x.shortAnswer ? x.shortAnswer + ' – ' : '') + x.answer.replace(/\s*\[\d\]/g, '')).slice(0, 140) + '</td>' +
          '<td>' + (x.mode === 'none' ? '<span class="pill warn">Not found</span>' : x.mode === 'verified' ? '<span class="pill ok">Verified</span>' : esc(x.confidenceLabel)) + '</td>' +
          '<td>' + (x.feedback === 'up' ? 'Helpful' : x.feedback === 'down' ? 'Not helpful' : '–') + '</td>' +
          '<td class="small muted">' + ago(x.at) + '</td></tr>';
      }
      html += '</tbody></table></div>';
    }
    setView(html);
    $('#hsearch').addEventListener('submit', (e) => { e.preventDefault(); location.hash = '#/history?q=' + encodeURIComponent(val(e.target, 'q')); });
  }

  // ================================================================== Library
  let libFilter = { cat: '', q: '', status: '' };

  async function renderLibrary(r) {
    const docs = await api('GET', '/docs');
    const counts = {};
    docs.forEach((d) => { counts[d.category] = (counts[d.category] || 0) + 1; });
    let html = '<p class="intro">The college documents Pothi answers from. Each one is cut into small passages (chunks) for searching. ' +
      (ME.can.curate ? 'Only <b>published</b> documents are used for answers; drafts and archived ones are kept out.' : '') + '</p>';
    html += '<div class="toolbar"><input type="search" id="libq" placeholder="Filter by title or text" value="' + esc(libFilter.q) + '">';
    if (ME.can.curate) {
      html += '<select id="libst" aria-label="Status"><option value="">Any status</option><option value="published">Published</option><option value="draft">Draft</option><option value="archived">Archived</option></select>';
      html += '<span class="spacer"></span><button class="btn primary" id="addDoc" type="button">Add document</button>';
    }
    html += '</div><div class="lib"><div class="cat-tabs" id="cats"><button type="button" data-cat="" data-tc="10" aria-pressed="' + (!libFilter.cat) + '"><span>All documents</span><span>' + docs.length + '</span></button>';
    CATS.forEach((c) => {
      if (!counts[c]) return;
      html += '<button type="button" data-cat="' + esc(c) + '" data-tc="' + catTab(c) + '" aria-pressed="' + (libFilter.cat === c) + '"><span>' + esc(c) + '</span><span>' + counts[c] + '</span></button>';
    });
    html += '</div><div class="doc-list" id="docList"></div></div>';
    setView(html);
    if (ME.can.curate) { $('#libst').value = libFilter.status; $('#libst').addEventListener('change', (e) => { libFilter.status = e.target.value; draw(); }); }

    function draw() {
      const q = libFilter.q.toLowerCase();
      const list = docs.filter((d) => (!libFilter.cat || d.category === libFilter.cat) && (!libFilter.status || d.status === libFilter.status) &&
        (!q || d.title.toLowerCase().includes(q) || d.preview.toLowerCase().includes(q)));
      $('#docList').innerHTML = list.length ? list.map((d) =>
        '<article class="doc-row ' + esc(d.status) + '" data-tc="' + catTab(d.category) + '">' +
        '<div><h3><a href="#/doc/' + encodeURIComponent(d.id) + '">' + esc(d.title) + '</a></h3>' +
        '<div class="meta"><span>' + esc(d.category) + '</span><span>' + esc(d.department) + '</span>' +
        (d.effectiveDate ? '<span>In force from ' + fmtDay(d.effectiveDate) + '</span>' : '') + '<span>Version ' + d.version + '</span></div></div>' +
        '<div class="side">' + statusPill(d) + (d.audience !== 'all' ? '<span class="pill info">' + (d.audience === 'faculty' ? 'Faculty only' : 'Students only') + '</span>' : '') +
        (d.reviewDue ? '<span class="pill warn">Review due</span>' : '') + (d.flagged ? '<span class="pill bad">' + d.flagged + ' blocked passage(s)</span>' : '') +
        '<span class="small muted">' + d.chunks + ' chunks, cited in ' + d.cited + ' answers</span></div>' +
        '<p class="preview">' + esc(d.preview) + '</p></article>').join('') : '<div class="empty">No documents match.</div>';
      applyDyn($('#docList'));
    }
    draw();
    $('#libq').addEventListener('input', (e) => { libFilter.q = e.target.value; draw(); });
    $('#cats').addEventListener('click', (e) => {
      const b = e.target.closest('[data-cat]');
      if (!b) return;
      libFilter.cat = b.dataset.cat;
      $$('#cats button').forEach((x) => x.setAttribute('aria-pressed', String(x === b)));
      draw();
    });
    if (ME.can.curate) $('#addDoc').addEventListener('click', () => docDialog(null));
    if (r.params.get('add') && ME.can.curate) docDialog(null);
  }

  function statusPill(d) {
    if (d.status === 'published') return '<span class="pill ok">Published</span>';
    if (d.status === 'draft') return '<span class="pill warn">Draft – not used</span>';
    return '<span class="pill">Archived – not used</span>';
  }

  function docForm(d) {
    d = d || { category: 'Academics', audience: 'all', status: 'published', department: '' };
    const opts = (list, cur) => list.map((x) => '<option value="' + esc(x[0]) + '"' + (x[0] === cur ? ' selected' : '') + '>' + esc(x[1]) + '</option>').join('');
    let h = '';
    if (!d.id) {
      h += '<div class="drop" id="drop" tabindex="0" role="button"><b>Choose a file</b> or drop it here<br><span class="small">.txt, .md, .csv, .html, .docx or .pdf – up to 8 MB</span>' +
        '<input type="file" id="file" accept=".txt,.md,.markdown,.csv,.html,.htm,.docx,.pdf" hidden></div>' +
        '<p class="small muted" id="fileInfo">Or paste the text below. Use # for headings and | for table rows if you like.</p>';
    }
    h += '<label class="field">Title<input name="title" maxlength="120" value="' + esc(d.title || '') + '" placeholder="Taken from the file if left empty"></label>';
    h += '<div class="grid2"><label class="field">Category<select name="category">' + opts(CATS.map((c) => [c, c]), d.category) + '</select></label>';
    h += '<label class="field">Department or office<input name="department" maxlength="80" value="' + esc(d.department || '') + '"></label></div>';
    h += '<div class="grid2"><label class="field">Who can read it<select name="audience">' + opts([['all', 'Everyone'], ['students', 'Students only'], ['faculty', 'Faculty only']], d.audience) + '</select></label>';
    h += '<label class="field">Status<select name="status">' + opts([['published', 'Published – used for answers'], ['draft', 'Draft – not used yet'], ['archived', 'Archived – old, not used']], d.status) + '</select></label></div>';
    h += '<div class="grid2"><label class="field">In force from<input type="date" name="effectiveDate" value="' + esc(d.effectiveDate || '') + '"></label>';
    h += '<label class="field">Check again by<input type="date" name="reviewDate" value="' + esc(d.reviewDate || '') + '"></label></div>';
    h += '<label class="field">Text<textarea class="doc-text" name="text" placeholder="# Notice title&#10;&#10;## Section&#10;Write the rule here.">' + esc(d.text || '') + '</textarea></label>';
    return h;
  }

  function docDialog(d) {
    let file = null;
    openModal(d ? 'Edit document' : 'Add a document', docForm(d), d ? 'Save changes' : 'Add document', async (b) => {
      const body = {
        title: val(b, 'title'), category: val(b, 'category'), department: val(b, 'department'), audience: val(b, 'audience'),
        status: val(b, 'status'), effectiveDate: val(b, 'effectiveDate'), reviewDate: val(b, 'reviewDate'),
      };
      if (file && !val(b, 'text').trim()) { body.fileName = file.name; body.dataBase64 = file.data; } else { body.text = val(b, 'text'); if (file) body.fileName = file.name; }
      if (d) {
        await api('PUT', '/docs/' + encodeURIComponent(d.id), body);
        toast('Saved. The search index was rebuilt.');
        route();
      } else {
        const saved = await api('POST', '/docs', body);
        toast('Added "' + saved.title + '" – ' + saved.chunks + ' chunks indexed' + (saved.flagged ? ', ' + saved.flagged + ' blocked' : ''));
        location.hash = '#/doc/' + encodeURIComponent(saved.id);
      }
    }, {
      onOpen: (b) => {
        const drop = $('#drop', b);
        if (!drop) return;
        const input = $('#file', b);
        const take = (f) => {
          if (!f) return;
          if (f.size > 8 * 1024 * 1024) { $('#modalErr').textContent = 'The file is too big (8 MB at most).'; return; }
          const reader = new FileReader();
          reader.onload = () => {
            file = { name: f.name, data: String(reader.result).split(',')[1] || '' };
            $('#fileInfo', b).textContent = 'Selected: ' + f.name + ' (' + Math.ceil(f.size / 1024) + ' KB). The text is read on the server. Leave the box below empty to use the file.';
            drop.querySelector('b').textContent = f.name;
          };
          reader.readAsDataURL(f);
        };
        drop.addEventListener('click', () => input.click());
        drop.addEventListener('keydown', (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); input.click(); } });
        input.addEventListener('change', () => take(input.files[0]));
        drop.addEventListener('dragover', (e) => { e.preventDefault(); drop.classList.add('over'); });
        drop.addEventListener('dragleave', () => drop.classList.remove('over'));
        drop.addEventListener('drop', (e) => { e.preventDefault(); drop.classList.remove('over'); take(e.dataTransfer.files[0]); });
      },
    });
  }

  /** Very small Markdown reader: headings, lists, tables and paragraphs. */
  function mdHtml(text) {
    const lines = text.split('\n');
    let out = '', list = false, table = null;
    const flush = () => {
      if (list) { out += '</ul>'; list = false; }
      if (table) {
        out += '<table><thead><tr>' + table[0].map((c) => '<th>' + esc(c) + '</th>').join('') + '</tr></thead><tbody>' +
          table.slice(1).map((r) => '<tr>' + r.map((c) => '<td>' + esc(c) + '</td>').join('') + '</tr>').join('') + '</tbody></table>';
        table = null;
      }
    };
    for (const raw of lines) {
      const l = raw.trim();
      if (l.startsWith('|')) {
        if (/^\|[\s:|-]+\|?$/.test(l)) continue;
        if (list) { out += '</ul>'; list = false; }
        table = table || [];
        table.push(l.replace(/^\||\|$/g, '').split('|').map((c) => c.trim()));
        continue;
      }
      if (table) flush();
      let m;
      if ((m = l.match(/^(#{1,3})\s+(.*)$/))) { flush(); out += '<h' + m[1].length + '>' + esc(m[2]) + '</h' + m[1].length + '>'; }
      else if ((m = l.match(/^[-*•]\s+(.*)$/))) { if (!list) { out += '<ul>'; list = true; } out += '<li>' + esc(m[1]) + '</li>'; }
      else if (!l) flush();
      else { flush(); out += '<p>' + esc(l) + '</p>'; }
    }
    flush();
    return out;
  }

  async function renderDoc(r) {
    const d = await api('GET', '/docs/' + encodeURIComponent(r.id));
    $('#pageTitle').textContent = d.title;
    let html = '<p class="small"><a href="#/library">Back to the library</a></p><div class="doc-view"><div>';
    html += '<div class="doc-paper">' + (/^#\s/.test(d.text) ? '' : '<h1>' + esc(d.title) + '</h1>') + mdHtml(d.text) + '</div></div>';
    html += '<aside class="rail"><section><h3>About this document</h3><dl class="dl">' +
      '<dt>Status</dt><dd>' + statusPill(d) + '</dd>' +
      '<dt>Category</dt><dd>' + esc(d.category) + '</dd>' +
      '<dt>From</dt><dd>' + esc(d.department) + '</dd>' +
      '<dt>Readers</dt><dd>' + (d.audience === 'all' ? 'Everyone' : d.audience === 'faculty' ? 'Faculty only' : 'Students only') + '</dd>' +
      '<dt>In force</dt><dd>' + fmtDay(d.effectiveDate) + '</dd>' +
      '<dt>Check by</dt><dd>' + fmtDay(d.reviewDate) + (d.reviewDue ? ' <span class="pill warn">Due</span>' : '') + '</dd>' +
      '<dt>Version</dt><dd>' + d.version + ', ' + d.words + ' words</dd>' +
      '<dt>File</dt><dd>' + esc(d.fileName) + '</dd>' +
      '<dt>Updated</dt><dd>' + fmtDate(d.updatedAt) + ' by ' + esc(d.updatedBy) + '</dd></dl>';
    if (ME.can.curate) {
      html += '<div class="row" style-free>' +
        '<button class="btn small" id="editDoc" type="button">Edit</button>' +
        (d.status !== 'published' ? '<button class="btn small" data-status="published" type="button">Publish</button>' : '<button class="btn small" data-status="archived" type="button">Archive</button>') +
        (ME.can.admin ? '<button class="btn small danger" id="delDoc" type="button">Delete</button>' : '') + '</div>';
    }
    html += '</section><section><h3>How the search sees it: ' + d.chunks.length + ' chunks</h3>' +
      (d.inIndex ? '' : '<p class="small muted">This document is not published, so these chunks are not searched.</p>');
    html += d.chunks.map((c) => '<div class="chunk' + (c.flagged ? ' flagged' : '') + '"><header><span>Chunk ' + c.ordinal + ': ' + esc(c.section) + '</span><span>' + c.words + ' words</span></header>' +
      (c.flagged ? '<p class="small"><b>Blocked:</b> ' + esc(c.flagReason) + '</p>' : '') + '<p>' + esc(c.text) + '</p></div>').join('');
    html += '</section></aside></div>';
    setView(html.replace(' style-free', ''));
    if (ME.can.curate) {
      $('#editDoc').addEventListener('click', () => docDialog(d));
      $$('[data-status]').forEach((b) => b.addEventListener('click', async () => {
        try { await api('PUT', '/docs/' + encodeURIComponent(d.id), { status: b.dataset.status }); toast(b.dataset.status === 'published' ? 'Published' : 'Archived'); route(); }
        catch (err) { toast(err.message, true); }
      }));
      if (ME.can.admin) $('#delDoc').addEventListener('click', () => openModal('Delete this document?',
        '<p>“' + esc(d.title) + '” will be removed and its passages will no longer be used for answers. Old answers keep their copy of the text.</p>',
        'Delete document', async () => { await api('DELETE', '/docs/' + encodeURIComponent(d.id)); toast('Deleted'); location.hash = '#/library'; }, { danger: true }));
    }
  }

  // ================================================================== Verified answers
  function verifiedDialog(v, prefill) {
    const x = v || prefill || {};
    api('GET', '/docs').then((docs) => {
      const chosen = new Set(x.docIds || []);
      openModal(v ? 'Edit verified answer' : 'New verified answer',
        '<p class="small muted">When someone asks almost the same question, this answer is shown first, marked with your name.</p>' +
        '<label class="field">Question<input name="question" maxlength="300" value="' + esc(x.question || '') + '" required></label>' +
        '<label class="field">Answer<textarea name="answer" maxlength="2000" required>' + esc(x.answer || '') + '</textarea></label>' +
        '<fieldset class="field"><legend>Based on these documents</legend>' + docs.filter((d) => d.status === 'published').map((d) =>
          '<label class="check"><input type="checkbox" name="doc" value="' + esc(d.id) + '"' + (chosen.has(d.id) ? ' checked' : '') + '>' + esc(d.title) + '</label>').join('') + '</fieldset>',
        v ? 'Save' : 'Save verified answer', async (b) => {
          const body = { question: val(b, 'question'), answer: val(b, 'answer'), docIds: $$('input[name="doc"]:checked', b).map((i) => i.value) };
          if (v) await api('PUT', '/verified/' + encodeURIComponent(v.id), body); else await api('POST', '/verified', body);
          toast('Verified answer saved');
          if (parseHash().name === 'verified') route();
        });
    }).catch((err) => toast(err.message, true));
  }

  async function renderVerified() {
    const list = await api('GET', '/verified');
    let html = '<p class="intro">Answers checked and written by faculty. When a new question is almost the same as one of these, ' +
      'Pothi shows the verified answer first instead of building a new one.</p>';
    if (ME.can.curate) html += '<div class="toolbar"><button class="btn primary" type="button" id="addV">New verified answer</button></div>';
    html += list.length ? list.map((v) => '<article class="vcard" data-id="' + esc(v.id) + '"><h3>' + esc(v.question) + '</h3><p>' + esc(v.answer) + '</p>' +
      '<div class="meta"><span>By ' + esc(v.byName) + ', ' + fmtDate(v.at) + '</span><span>Used ' + v.uses + ' times</span>' +
      (v.docs.length ? '<span>Sources: ' + v.docs.map((d) => '<a href="#/doc/' + encodeURIComponent(d.id) + '">' + esc(d.title) + '</a>').join(', ') + '</span>' : '') +
      (ME.can.curate ? '<span class="spacer"></span><button class="btn small" data-edit type="button">Edit</button><button class="btn small danger" data-del type="button">Delete</button>' : '') +
      '</div></article>').join('') : '<div class="empty">No verified answers yet.</div>';
    const root = setView(html);
    if (!ME.can.curate) return;
    $('#addV').addEventListener('click', () => verifiedDialog(null));
    root.addEventListener('click', (e) => {
      const card = e.target.closest('.vcard');
      if (!card) return;
      const v = list.find((x) => x.id === card.dataset.id);
      if (e.target.closest('[data-edit]')) verifiedDialog(v);
      if (e.target.closest('[data-del]')) openModal('Delete this verified answer?', '<p>“' + esc(v.question) + '”</p>', 'Delete', async () => {
        await api('DELETE', '/verified/' + encodeURIComponent(v.id)); toast('Deleted'); route();
      }, { danger: true });
    });
  }

  // ================================================================== How answers are found (trace)
  function hitRows(list, cols) {
    if (!list.length) return '<p class="muted small">Nothing found at this step.</p>';
    return '<div class="table-wrap"><table class="table"><thead><tr><th>#</th><th>Passage</th>' + cols.map((c) => '<th class="r">' + c[0] + '</th>').join('') + '</tr></thead><tbody>' +
      list.map((h, i) => '<tr><td class="num">' + (i + 1) + '</td><td><a href="#/doc/' + encodeURIComponent(h.docId) + '">' + esc(h.docTitle) + '</a><div class="small muted">' + esc(h.section) + '</div></td>' +
        cols.map((c) => '<td class="r">' + c[1](h) + '</td>').join('') + '</tr>').join('') + '</tbody></table></div>';
  }
  const meter = (x, max) => '<span class="meter"><i data-w="' + (100 * x / (max || 1)) + '"></i></span>';

  async function renderTrace(r) {
    const q = r.params.get('q') || '';
    const mode = r.params.get('mode') || 'FULL';
    let html = '<p class="intro">See every step Pothi takes to answer a question: which words it looks for, how two kinds of search score the passages, ' +
      'how the results are combined and re-ranked, and what would be given to the AI. Try any question.</p>';
    html += '<form class="toolbar" id="tform"><input name="q" maxlength="500" value="' + esc(q) + '" placeholder="Type a question, for example: hostel fee for twin sharing" required>' +
      '<select name="mode" aria-label="Search set-up"><option value="FULL">Full pipeline</option><option value="HYBRID">Hybrid, no re-rank</option><option value="BM25">Keywords only (BM25)</option><option value="VECTOR">Vectors only (LSA)</option></select>' +
      '<button class="btn primary">Show the steps</button></form><div id="tout"></div>';
    setView(html);
    $('#tform [name="mode"]').value = mode;
    $('#tform').addEventListener('submit', (e) => {
      e.preventDefault();
      location.hash = '#/trace?q=' + encodeURIComponent(val(e.target, 'q')) + '&mode=' + val(e.target, 'mode');
    });
    if (!q) {
      $('#tout').innerHTML = '<div class="starters">' + ['What is the hostel fee for twin sharing?', 'Can I get my answer sheet rechecked?', 'What are the gym timings?', 'How many casual leaves do faculty get?']
        .map((x) => '<a class="starter" href="#/trace?q=' + encodeURIComponent(x) + '">' + esc(x) + '</a>').join('') + '</div>';
      return;
    }
    const t = await api('GET', '/trace?q=' + encodeURIComponent(q) + '&mode=' + encodeURIComponent(mode));
    const us = (k) => (t.micros[k] || 0) >= 1000 ? ((t.micros[k] / 1000).toFixed(1) + ' ms') : ((t.micros[k] || 0) + ' µs');
    const maxBm = Math.max(1, ...t.bm25.map((h) => h.bm25));
    let s = '<ol class="steps">';
    s += '<li><h3>Find the important words</h3><p class="why">Small words like “the” and “is” are dropped and each word is cut to its root (“timings” → “timing”). ' +
      'Campus synonyms are added at half weight (dashed). The number is the IDF: rare words count more. Words like “required” or “available” count only a little (faded). ' +
      'The question asks for <b>' + esc(t.questionTypeLabel) + '</b>.</p><div class="words">' +
      t.queryWeights.map((w) => '<span class="word' + (w.synonym ? ' syn' : '') + (w.soft ? ' soft' : '') + '">' + esc(w.word) + '<small>' + w.idf.toFixed(1) + '</small></span>').join('') + '</div></li>';
    s += '<li><h3>Keep only what you may read</h3><p class="why">' + t.searched + ' passages are searched.' +
      (t.hidden ? ' ' + t.hidden + ' passages from documents for other readers are hidden from you, so they can never leak into your answer.' : ' None are hidden for your role.') + '</p></li>';
    if (mode !== 'VECTOR') {
      s += '<li><h3>Keyword search (BM25)</h3><p class="why">Scores passages that contain the words, with rare words and repeated words counting more, and long passages not favoured. Took ' + us('bm25') + '.</p>' +
        hitRows(t.bm25, [['BM25 score', (h) => meter(h.bm25, maxBm) + h.bm25.toFixed(2)]]) + '</li>';
    }
    if (mode !== 'BM25') {
      s += '<li><h3>Meaning search (vectors)</h3><p class="why">Every passage and the question are turned into a list of 48 numbers (LSA). Passages pointing the same way as the question are similar in meaning, even with different words. Took ' + us('vectors') + '.</p>' +
        hitRows(t.vector, [['Cosine', (h) => meter(h.cosine, 1) + h.cosine.toFixed(2)]]) + '</li>';
    }
    if (mode === 'FULL' || mode === 'HYBRID') {
      s += '<li><h3>Combine both lists (Reciprocal Rank Fusion)</h3><p class="why">Each passage gets 1 ÷ (60 + its place) from each list, added together. A passage near the top of both lists wins.</p>' +
        hitRows(t.fused, [['Keyword place', (h) => h.bm25Rank || '–'], ['Vector place', (h) => h.vectorRank || '–'], ['RRF × 1000', (h) => h.rrf.toFixed(1)]]) + '</li>';
    }
    if (mode === 'FULL') {
      const w = t.weights;
      s += '<li><h3>Re-rank with six checks</h3><p class="why">Score = ' + pct(w.coverage) + ' word coverage + ' + pct(w.bm25) + ' keyword score + ' + pct(w.cosine) + ' meaning + ' +
        pct(w.proximity) + ' words close together + ' + pct(w.heading) + ' words in the heading + ' + pct(w.type) + ' has the kind of value asked for. Took ' + us('rerank') + '.</p>' +
        hitRows(t.ranked, [['Coverage', (h) => pct(h.coverage)], ['Close', (h) => pct(h.proximity)], ['Heading', (h) => pct(h.heading)], ['Value', (h) => h.typeMatch === 1 ? 'yes' : h.typeMatch === 0 ? 'no' : '–'], ['Score', (h) => meter(h.score, 1) + h.score.toFixed(2)]]) + '</li>';
    }
    s += '<li><h3>Pick the sources (MMR)</h3><p class="why">Up to ' + t.topK + ' passages are chosen that are relevant but not repeating each other. ' +
      'An answer is given only if the best passage scores at least ' + t.minScore.toFixed(2) + ' and contains at least ' + pct(t.minCoverage) + ' of the important words.</p>' +
      hitRows(t.selected, [['Score', (h) => h.score.toFixed(2)], ['Coverage', (h) => pct(h.coverage)]]) +
      '<p class="verdict ' + (t.answerable ? 'yes' : 'no') + '">' + (t.answerable ? 'Answerable: these passages are used for the answer.' : 'Not answerable: the documents do not seem to cover this, so Pothi says so instead of guessing.') + '</p></li>';
    if (t.answerable) {
      s += '<li><h3>Write the answer</h3><p class="why">Offline, Pothi copies the best sentences with their flags:</p><div class="answer"><div class="answer-text">' + answerHtml(t.answerPreview, t.selected.length) + '</div></div>' +
        '<p class="why" >With Claude switched on, this is exactly what is sent. The rules tell it to use only the sources and to reply NOT_FOUND when they do not have the answer:</p><pre class="prompt">' + esc(t.prompt) + '</pre></li>';
    }
    s += '</ol>';
    $('#tout').innerHTML = s;
    applyDyn($('#tout'));
  }

  // ================================================================== Gaps
  async function renderGaps(r) {
    const showAll = r.params.get('all') === '1';
    const g = await api('GET', '/gaps' + (showAll ? '?resolved=1' : ''));
    gapCount = g.open; buildNav();
    let html = '<p class="intro">Questions the documents could not answer, and answers people marked “not helpful”. Similar questions are grouped. ' +
      'Fix a gap by adding or updating a document, or by writing a verified answer, then mark it resolved.</p>';
    html += '<div class="toolbar"><span>' + g.open + ' open question(s) in ' + g.groups.filter((x) => !x.resolved).length + ' group(s)</span><span class="spacer"></span>' +
      '<a class="btn small" href="#/gaps' + (showAll ? '' : '?all=1') + '">' + (showAll ? 'Hide resolved' : 'Show resolved too') + '</a></div>';
    html += g.groups.length ? g.groups.map((grp, i) => '<article class="gap' + (grp.resolved ? ' resolved' : '') + '" data-i="' + i + '">' +
      '<div class="tally" title="times asked">' + grp.count + '</div>' +
      '<div><h3>' + esc(grp.question) + '</h3><div class="meta">' + esc(grp.kind) + (grp.category ? ', nearest topic: ' + esc(grp.category) : '') + ', last asked ' + ago(grp.last) +
      (grp.resolved ? ' <span class="pill ok">Resolved</span>' : '') + '</div></div>' +
      '<div class="actions"><button class="btn small" data-act="verify" type="button">Write verified answer</button><a class="btn small" href="#/library?add=1">Add document</a>' +
      (grp.resolved ? '' : '<button class="btn small primary" data-act="resolve" type="button">Mark resolved</button>') + '</div>' +
      '<details><summary>' + grp.items.length + ' time(s) asked</summary><ul>' + grp.items.map((it) => '<li>“' + esc(it.question) + '” – ' + esc(it.kind) + ' (' + esc(it.asker) + ', ' + ago(it.at) + ')' +
        (it.note ? ' – note: ' + esc(it.note) : '') + (it.resolvedNote ? ' – <i>' + esc(it.resolvedNote) + '</i>' : '') + '</li>').join('') + '</ul></details></article>').join('')
      : '<div class="empty">No gaps. Every question was answered and nobody marked an answer as not helpful.</div>';
    const root = setView(html);
    root.addEventListener('click', (e) => {
      const b = e.target.closest('[data-act]');
      if (!b) return;
      const grp = g.groups[+b.closest('.gap').dataset.i];
      if (b.dataset.act === 'verify') verifiedDialog(null, { question: grp.question, answer: '', docIds: [] });
      if (b.dataset.act === 'resolve') openModal('Mark as resolved', '<p>“' + esc(grp.question) + '” (' + grp.count + ' question(s))</p><label class="field">What did you do?<input name="note" maxlength="300" placeholder="e.g. Added the Sports Complex rules document"></label>',
        'Mark resolved', async (bd) => {
          await api('POST', '/gaps/resolve', { ids: grp.items.map((it) => it.id), note: val(bd, 'note') });
          toast('Marked resolved'); route();
        });
    });
  }

  // ================================================================== Insights
  function dailyChart(daily) {
    const W = 640, H = 210, L = 30, B = 24, T = 10;
    const max = Math.max(4, ...daily.map((d) => d.answered + d.unanswered));
    const step = (W - L) / daily.length, bw = Math.max(3, step * 0.66);
    let s = '<svg viewBox="0 0 ' + W + ' ' + H + '" role="img" aria-label="Questions per day">';
    for (let i = 0; i <= 4; i++) {
      const y = T + (H - B - T) * (1 - i / 4);
      s += '<line class="gridline" x1="' + L + '" x2="' + W + '" y1="' + y + '" y2="' + y + '"/><text class="axis" x="' + (L - 6) + '" y="' + (y + 4) + '" text-anchor="end">' + Math.round(max * i / 4) + '</text>';
    }
    daily.forEach((d, i) => {
      const x = L + i * step + (step - bw) / 2;
      const ha = (H - B - T) * d.answered / max, hu = (H - B - T) * d.unanswered / max;
      s += '<rect x="' + x + '" y="' + (H - B - ha) + '" width="' + bw + '" height="' + ha + '" rx="1.5" fill="var(--f3)"><title>' + d.date + ': ' + d.answered + ' answered</title></rect>';
      if (hu) s += '<rect x="' + x + '" y="' + (H - B - ha - hu) + '" width="' + bw + '" height="' + hu + '" rx="1.5" fill="var(--f4)"><title>' + d.date + ': ' + d.unanswered + ' not found</title></rect>';
      if (i % Math.ceil(daily.length / 8) === 0) s += '<text class="axis" x="' + (x + bw / 2) + '" y="' + (H - 6) + '" text-anchor="middle">' + fmtDay(d.date).replace(/ \d{4}$/, '') + '</text>';
    });
    return s + '</svg>';
  }

  function hoursChart(hours) {
    const W = 360, H = 120, B = 18;
    const max = Math.max(1, ...hours);
    const bw = W / 24;
    let s = '<svg viewBox="0 0 ' + W + ' ' + H + '" role="img" aria-label="Questions by hour of day">';
    hours.forEach((v, i) => {
      const h = (H - B - 4) * v / max;
      s += '<rect x="' + (i * bw + 1) + '" y="' + (H - B - h) + '" width="' + (bw - 2) + '" height="' + h + '" rx="1" fill="var(--f5)"><title>' + i + ':00 – ' + v + ' questions</title></rect>';
      if (i % 3 === 0) s += '<text class="axis" x="' + (i * bw + bw / 2) + '" y="' + (H - 4) + '" text-anchor="middle">' + i + '</text>';
    });
    return s + '</svg>';
  }

  async function renderInsights(r) {
    const days = +(r.params.get('days') || 30);
    const d = await api('GET', '/insights?days=' + days);
    const delta = d.prevTotal ? (d.answerRate - d.prevAnswerRate) : 0;
    let html = '<div class="toolbar"><span>' + fmtDay(d.from) + ' to ' + fmtDay(d.to) + '</span><span class="spacer"></span><select id="days" aria-label="Period">' +
      [7, 14, 30, 60, 90].map((n) => '<option value="' + n + '"' + (n === d.days ? ' selected' : '') + '>Last ' + n + ' days</option>').join('') + '</select></div>';
    html += '<div class="stat-strip">' +
      '<div class="stat"><b>' + d.total + '</b><span>questions from ' + d.people + ' people</span></div>' +
      '<div class="stat"><b>' + pct(d.answerRate) + '</b><span>answered from the documents</span>' + (d.prevTotal ? '<div class="delta ' + (delta >= 0 ? 'muted' : '') + '">' + (delta >= 0 ? '+' : '') + Math.round(delta * 100) + ' points vs previous period</div>' : '') + '</div>' +
      '<div class="stat"><b>' + pct(d.helpfulRate) + '</b><span>of votes were “helpful” (' + d.helpful + ' / ' + (d.helpful + d.notHelpful) + ')</span></div>' +
      '<div class="stat"><b>' + pct(d.avgConfidence) + '</b><span>average confidence</span></div>' +
      '<div class="stat"><b>' + Math.round(d.avgMs) + ' ms</b><span>average time to answer</span></div>' +
      '<div class="stat"><b>' + d.verifiedUsed + '</b><span>verified answers used</span></div></div>';
    html += '<div class="charts"><section class="section chart"><h2>Questions per day</h2>' + dailyChart(d.daily) +
      '<div class="legend"><span><i data-c="f3"></i>Answered</span><span><i data-c="f4"></i>Not found</span></div></section>';
    const maxCat = Math.max(1, ...d.categories.map((c) => c.count));
    html += '<section class="section"><h2>What people ask about</h2>' + d.categories.map((c) =>
      '<div class="hbar" data-tc="' + catTab(c.category) + '"><span class="label">' + esc(c.category) + '</span><span class="track"><i data-w="' + (100 * c.count / maxCat) + '"></i></span><span class="num r">' + c.count + '</span></div>').join('') + '</section></div>';
    const conf = d.confidence, totalConf = Math.max(1, Object.values(conf).reduce((a, b) => a + b, 0));
    html += '<div class="charts"><section class="section"><h2>Most cited documents</h2><div class="table-wrap"><table class="table"><tbody>' +
      d.topDocs.map((x) => '<tr><td><a href="#/doc/' + encodeURIComponent(x.id) + '">' + esc(x.title) + '</a><div class="small muted">' + esc(x.category) + '</div></td><td class="r">' + x.count + ' answers</td></tr>').join('') + '</tbody></table></div></section>';
    html += '<section class="section"><h2>Answer confidence</h2>' + Object.keys(conf).map((k, i) =>
      '<div class="hbar" data-tc="' + [2, 1, 4, 6][i] + '"><span class="label">' + esc(k) + '</span><span class="track"><i data-w="' + (100 * conf[k] / totalConf) + '"></i></span><span class="num r">' + conf[k] + '</span></div>').join('') +
      '<h2 class="small" >When people ask (hour of day)</h2><div class="chart">' + hoursChart(d.hours) + '</div></section></div>';
    html += '<div class="charts"><section class="section"><h2>Documents to check</h2>' +
      (d.reviewDue.length ? '<ul>' + d.reviewDue.map((x) => '<li><a href="#/doc/' + encodeURIComponent(x.id) + '">' + esc(x.title) + '</a> – check by ' + fmtDay(x.reviewDate) + '</li>').join('') + '</ul>' : '<p class="muted">No document is due for review in the next 30 days.</p>') +
      (d.neverCited.length ? '<p class="small muted">Never used in an answer in this period: ' + d.neverCited.map((x) => esc(x.title)).join(', ') + '.</p>' : '') + '</section>';
    const ix = d.index;
    html += '<section class="section"><h2>Search index</h2><dl class="dl"><dt>Documents</dt><dd>' + ix.published + ' published, ' + ix.drafts + ' draft, ' + ix.archived + ' archived</dd>' +
      '<dt>Chunks</dt><dd>' + ix.chunks + ' (about ' + ix.avgChunkWords + ' words each; target ' + ix.chunkWords + ', overlap ' + ix.overlapWords + ')</dd>' +
      '<dt>Blocked</dt><dd>' + ix.flagged + ' chunk(s) that looked like instructions to the AI</dd>' +
      '<dt>Vocabulary</dt><dd>' + ix.vocabulary + ' words (' + ix.lsaVocabulary + ' used by vectors)</dd>' +
      '<dt>Vectors</dt><dd>' + ix.vectorDims + ' numbers per chunk</dd><dt>Built</dt><dd>' + ago(ix.builtAt) + ' in ' + ix.buildMs + ' ms</dd></dl></section></div>';
    setView(html);
    $$('.legend i[data-c]').forEach((i) => { i.style.background = 'var(--' + i.dataset.c + ')'; });
    $('#days').addEventListener('change', (e) => { location.hash = '#/insights?days=' + e.target.value; });
  }

  // ================================================================== Test bench (evaluation)
  async function renderEval() {
    const e = await api('GET', '/eval');
    const docName = {};
    e.docs.forEach((d) => { docName[d.id] = d.title; });
    let html = '<p class="intro">A fixed set of test questions, each with the document that holds the answer and words the answer must contain. ' +
      'Running it compares four search set-ups, so you can see what each part of the pipeline adds.</p>';
    html += '<div class="toolbar"><button class="btn primary" id="runEval" type="button">Run the test (' + e.cases.length + ' questions)</button>' +
      '<button class="btn" id="addCase" type="button">Add a test question</button>' +
      (e.lastRun ? '<span class="muted small">Last run ' + ago(e.lastRun.at) + '</span>' : '') + '</div><div id="evalOut"></div>';
    setView(html);
    const show = (run) => {
      if (!run) { $('#evalOut').innerHTML = '<div class="empty">Not run yet. Press “Run the test”.</div>'; return; }
      let s = '<div class="table-wrap"><table class="table"><thead><tr><th>Set-up</th><th class="r">First result right (Hit@1)</th><th class="r">Right document in the ' + run.topK + ' sources</th><th class="r">MRR</th><th class="r">Answer has the expected words</th><th class="r">Time per question</th></tr></thead><tbody>' +
        run.summary.map((x) => '<tr' + (x.mode === 'FULL' ? ' class="sel"' : '') + '><td>' + esc(x.label) + '</td><td class="r">' + meter(x.hit1, 1) + pct(x.hit1) + '</td><td class="r">' + meter(x.recall, 1) + pct(x.recall) + '</td><td class="r">' + x.mrr.toFixed(3) + '</td><td class="r">' +
          (x.answerAccuracy != null ? meter(x.answerAccuracy, 1) + pct(x.answerAccuracy) : '–') + '</td><td class="r">' + x.msPerQuestion.toFixed(2) + ' ms</td></tr>').join('') + '</tbody></table></div>';
      s += '<p class="small muted">MRR = average of 1 ÷ (place of the first right passage). 1.000 means the right document always came first.</p>';
      s += '<section class="section"><h2>Each question</h2><div class="table-wrap"><table class="table"><thead><tr><th>Question</th><th>Should come from</th><th class="r">BM25</th><th class="r">Vectors</th><th class="r">Hybrid</th><th class="r">Full</th><th>Answer</th><th></th></tr></thead><tbody>' +
        run.details.map((x) => '<tr' + (x.answerOk ? '' : ' class="sel"') + '><td>' + esc(x.question) + '</td><td class="small">' + esc(docName[x.expectedDoc] || x.expectedDoc) + '</td>' +
          ['BM25', 'VECTOR', 'HYBRID', 'FULL'].map((m) => '<td class="r num">' + (x.ranks[m] ? x.ranks[m] : '–') + '</td>').join('') +
          '<td>' + (x.answerOk ? '<span class="pill ok">Has the words</span>' : x.answerable ? '<span class="pill bad">Missing words</span>' : '<span class="pill warn">No answer</span>') + '</td>' +
          '<td><button class="btn small" data-delcase="' + esc(x.id) + '" type="button">Remove</button></td></tr>').join('') + '</tbody></table></div>' +
        '<p class="small muted">The numbers are the place of the first passage from the right document (1 = first). Rows marked on the left did not pass.</p></section>';
      $('#evalOut').innerHTML = s;
      applyDyn($('#evalOut'));
    };
    show(e.lastRun);
    $('#runEval').addEventListener('click', async (ev) => {
      ev.target.disabled = true;
      try { show(await api('POST', '/eval/run')); toast('Test finished'); } catch (err) { toast(err.message, true); } finally { ev.target.disabled = false; }
    });
    $('#addCase').addEventListener('click', () => openModal('Add a test question',
      '<label class="field">Question<input name="question" maxlength="300" required></label>' +
      '<label class="field">Document that has the answer<select name="expectedDoc">' + e.docs.filter((d) => d.status === 'published').map((d) => '<option value="' + esc(d.id) + '">' + esc(d.title) + '</option>').join('') + '</select></label>' +
      '<label class="field">Words the answer must contain<input name="keywords" placeholder="for example: ₹750, 7 days"><span class="hint">Separate with commas.</span></label>',
      'Add question', async (b) => {
        await api('POST', '/eval/cases', { question: val(b, 'question'), expectedDoc: val(b, 'expectedDoc'), keywords: val(b, 'keywords') });
        toast('Added. Run the test to include it.'); route();
      }));
    $('#evalOut').addEventListener('click', async (ev) => {
      const b = ev.target.closest('[data-delcase]');
      if (!b) return;
      try { await api('DELETE', '/eval/cases/' + encodeURIComponent(b.dataset.delcase)); b.closest('tr').remove(); toast('Removed from the test set'); } catch (err) { toast(err.message, true); }
    });
  }

  // ================================================================== People
  async function renderPeople() {
    const list = await api('GET', '/users');
    let html = '<div class="toolbar"><input type="search" id="pq" placeholder="Filter by name or username"><span class="spacer"></span><button class="btn primary" id="addUser" type="button">Add person</button></div>';
    html += '<div class="table-wrap"><table class="table"><thead><tr><th>Name</th><th>Username</th><th>Role</th><th>Department / programme</th><th class="r">Questions</th><th>Last sign-in</th><th></th></tr></thead><tbody id="ptbody"></tbody></table></div>';
    setView(html);
    const draw = () => {
      const q = $('#pq').value.toLowerCase();
      $('#ptbody').innerHTML = list.filter((u) => !q || u.name.toLowerCase().includes(q) || u.username.includes(q)).map((u) =>
        '<tr><td>' + esc(u.name) + (u.active ? '' : ' <span class="pill bad">Switched off</span>') + '</td><td>' + esc(u.username) + '</td><td>' + esc(u.roleLabel) + '</td>' +
        '<td class="small">' + esc(u.programme || u.dept) + '</td><td class="r">' + u.questions + '</td><td class="small muted">' + (u.lastLogin ? ago(u.lastLogin) : 'never') + '</td>' +
        '<td><button class="btn small" data-uid="' + esc(u.id) + '" type="button">Edit</button></td></tr>').join('');
    };
    draw();
    $('#pq').addEventListener('input', draw);
    const form = (u) => {
      u = u || { role: 'STUDENT', active: true };
      return '<div class="grid2"><label class="field">Full name<input name="name" maxlength="60" value="' + esc(u.name || '') + '" required></label>' +
        (u.id ? '<label class="field">Username<input value="' + esc(u.username) + '" disabled></label>' : '<label class="field">Username<input name="username" maxlength="30" required></label>') + '</div>' +
        '<div class="grid2"><label class="field">Role<select name="role">' + ['STUDENT', 'FACULTY', 'ADMIN'].map((r) => '<option value="' + r + '"' + (u.role === r ? ' selected' : '') + '>' + r.charAt(0) + r.slice(1).toLowerCase() + '</option>').join('') + '</select></label>' +
        '<label class="field">Department<input name="dept" maxlength="60" value="' + esc(u.dept || '') + '"></label></div>' +
        '<label class="field">Programme (students)<input name="programme" maxlength="60" value="' + esc(u.programme || '') + '" placeholder="e.g. B.Sc. IT - Semester 3"></label>' +
        '<label class="field">' + (u.id ? 'New password (leave empty to keep)' : 'Password') + '<input name="password" type="password" autocomplete="new-password"' + (u.id ? '' : ' required') + '><span class="hint">At least 8 characters, with a letter and a number.</span></label>' +
        (u.id ? '<label class="check"><input type="checkbox" name="active"' + (u.active ? ' checked' : '') + '> Account can sign in</label>' : '');
    };
    $('#addUser').addEventListener('click', () => openModal('Add person', form(null), 'Add person', async (b) => {
      await api('POST', '/users', { name: val(b, 'name'), username: val(b, 'username'), role: val(b, 'role'), dept: val(b, 'dept'), programme: val(b, 'programme'), password: val(b, 'password') });
      toast('Added'); route();
    }));
    $('#ptbody').addEventListener('click', (e) => {
      const b = e.target.closest('[data-uid]');
      if (!b) return;
      const u = list.find((x) => x.id === b.dataset.uid);
      openModal('Edit ' + u.name, form(u), 'Save', async (bd) => {
        await api('PUT', '/users/' + encodeURIComponent(u.id), { name: val(bd, 'name'), role: val(bd, 'role'), dept: val(bd, 'dept'), programme: val(bd, 'programme'), password: val(bd, 'password'), active: val(bd, 'active') });
        toast('Saved'); route();
      });
    });
  }

  // ================================================================== Settings
  async function renderSettings() {
    const [s, ix] = await Promise.all([api('GET', '/settings'), api('GET', '/index')]);
    const num = (name, label, hint, step, min, max) => '<label class="field">' + label + '<input type="number" name="' + name + '" value="' + s[name] + '" step="' + step + '" min="' + min + '" max="' + max + '"><span class="hint">' + hint + '</span></label>';
    const chk = (name, label) => '<label class="check"><input type="checkbox" name="' + name + '"' + (s[name] ? ' checked' : '') + '> ' + label + '</label>';
    let html = '<form id="sform" class="howto">';
    html += '<section class="section"><h2>College</h2><label class="field">College name<input name="collegeName" maxlength="80" value="' + esc(s.collegeName) + '"></label></section>';
    html += '<section class="section"><h2>Cutting documents into chunks</h2><p class="small muted">Changing these rebuilds the index (takes less than a second).</p><div class="grid2">' +
      num('chunkWords', 'Words per chunk', 'Smaller chunks = more exact sources, but less context. 80–150 works well.', 10, 40, 400) +
      num('overlapWords', 'Overlap words', 'Words repeated from the previous chunk so ideas are not cut in half.', 5, 0, 200) + '</div></section>';
    html += '<section class="section"><h2>Searching</h2><div class="grid2">' +
      num('candidatePool', 'Results from each search', 'How many passages BM25 and vectors each bring back.', 1, 5, 60) +
      num('topK', 'Sources per answer (K)', 'How many passages are used to write the answer.', 1, 1, 8) +
      num('minScore', 'Lowest score to answer', 'Below this, Pothi says it could not find the answer.', 0.01, 0.05, 0.9) +
      num('mmrLambda', 'Relevance vs. variety (MMR λ)', '1 = only relevance, 0 = only variety.', 0.01, 0, 1) +
      num('vectorDims', 'Numbers per vector', 'Size of the LSA meaning vectors. Rebuilds the index.', 4, 8, 128) +
      num('verifiedMatch', 'Match needed for a verified answer', 'How similar a question must be (0.5 to 1).', 0.01, 0.5, 1) + '</div>' +
      chk('useSynonyms', 'Add campus synonyms to the question') + chk('useVectors', 'Use meaning search (vectors) together with keywords') + chk('useRerank', 'Re-rank results with the six checks') + '</section>';
    html += '<section class="section"><h2>Writing the answer</h2>' + chk('useClaude', 'Let Claude write the answers (falls back to offline answers if it cannot be reached)') +
      '<label class="field">Claude model<input name="claudeModel" value="' + esc(s.claudeModel) + '"></label>' +
      '<p class="small ' + (s.claudeAvailable ? 'muted' : '') + '">' + (s.claudeAvailable ? 'An API key was found when the server started.' :
        'No API key found. To use Claude, stop the app, set ANTHROPIC_API_KEY, and start it again. Until then, answers are built offline.') + '</p></section>';
    html += '<div class="row"><button class="btn primary">Save settings</button><button class="btn" type="button" id="rebuild">Rebuild index now</button>' +
      '<span class="small muted">Index: ' + ix.chunks + ' chunks from ' + ix.published + ' documents, built ' + ago(ix.builtAt) + '.</span></div></form>';
    setView(html);
    $('#sform').addEventListener('submit', async (e) => {
      e.preventDefault();
      const f = e.target;
      const body = {};
      ['collegeName', 'claudeModel'].forEach((k) => { body[k] = val(f, k); });
      ['chunkWords', 'overlapWords', 'candidatePool', 'topK', 'minScore', 'mmrLambda', 'vectorDims', 'verifiedMatch'].forEach((k) => { body[k] = Number(val(f, k)); });
      ['useSynonyms', 'useVectors', 'useRerank', 'useClaude'].forEach((k) => { body[k] = val(f, k); });
      try {
        const res = await api('PUT', '/settings', body);
        ME = await api('GET', '/me');
        startApp();
        toast(res.rebuilt ? 'Saved. The index was rebuilt with the new chunk settings.' : 'Settings saved');
      } catch (err) { toast(err.message, true); }
    });
    $('#rebuild').addEventListener('click', async () => {
      try { const x = await api('POST', '/index/rebuild'); toast('Rebuilt: ' + x.chunks + ' chunks in ' + x.buildMs + ' ms'); route(); } catch (err) { toast(err.message, true); }
    });
  }

  // ================================================================== How it works
  async function renderHow() {
    const html = '<div class="howto">' +
      '<p class="intro">Pothi is a <b>RAG</b> system: Retrieval-Augmented Generation. Instead of answering from memory (and sometimes making things up), ' +
      'it first <b>retrieves</b> the right paragraphs from the college documents, then <b>generates</b> a short answer using only those paragraphs, and shows them as sources.</p>' +
      '<div class="pipe">' +
      '<div data-tc="1"><b>1. Chunk</b>Each document is cut into passages of about 110 words, never across a heading.</div>' +
      '<div data-tc="2"><b>2. Index</b>Keyword index (BM25) and meaning vectors (LSA) are built for every passage.</div>' +
      '<div data-tc="3"><b>3. Search</b>The question is searched both ways, and the two lists are combined (RRF).</div>' +
      '<div data-tc="4"><b>4. Re-rank</b>Six checks put the best passage first; MMR picks K that do not repeat.</div>' +
      '<div data-tc="5"><b>5. Answer</b>The best sentences (or Claude) form the answer, with a flag for each source.</div>' +
      '<div data-tc="6"><b>6. Check</b>Every sentence must match a source; otherwise it is flagged.</div></div>' +
      '<h2>Why the answer has coloured flags</h2><p>Flag 1 in the answer has the same colour as source slip 1 below it. Click a flag to jump to the exact passage. ' +
      'If an answer looks wrong, you can always read the rule yourself.</p>' +
      '<h2>When Pothi says “not found”</h2><p>If no passage scores high enough, or the important words of your question are in no document (for example “gym”), Pothi does not guess. ' +
      'Your question goes to the faculty in the <b>Gaps</b> list, so they can add the missing document.</p>' +
      '<h2>Follow-up questions</h2><p>In one chat you can ask “What is the hostel fee?” and then “and the mess charges?”. The short follow-up is joined with the words of the question before it.</p>' +
      '<h2>Who can see what</h2><p>Some documents are for faculty only. They are filtered out <i>before</i> searching, so a student\'s answer can never quote them. Drafts and archived documents are never used.</p>' +
      '<h2>Safety</h2><ul><li>Passages that try to give orders to the AI (like “ignore previous instructions”) are blocked when the document is added.</li>' +
      '<li>When Claude is used, the prompt says the sources are data, not instructions, and Claude must reply NOT_FOUND when the answer is missing.</li>' +
      '<li>Passwords are stored as PBKDF2 hashes; sessions use HttpOnly cookies; every API call checks your role.</li></ul>' +
      '<p><a href="#/trace">Open “How answers are found”</a> to watch every step for any question.</p></div>';
    setView(html);
  }

  // ================================================================== start
  (async function boot() {
    try {
      ME = await api('GET', '/me');
      startApp();
    } catch (e) {
      showLogin();
    }
  })();
})();
