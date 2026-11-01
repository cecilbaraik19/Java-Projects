/* =====================================================================
   Bahi - AI-Based Invoice Data Extractor - the website
   Plain JavaScript, no libraries. Talks to the Java backend under /api/.
   Pages are chosen by the part of the address after "#", e.g. #/invoice/IX0047.
   ===================================================================== */
'use strict';
(function () {
  const $ = (s, root) => (root || document).querySelector(s);
  const $$ = (s, root) => Array.from((root || document).querySelectorAll(s));
  const view = $('#view');
  const state = { me: null, vendors: null };

  // ------------------------------------------------------------ helpers

  /** Make text safe to put inside HTML. */
  const esc = (s) => String(s == null ? '' : s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

  /** Call the backend. Throws an Error with the server's message when something is wrong. */
  async function api(method, url, body) {
    const opt = { method, headers: {}, credentials: 'same-origin' };
    if (body !== undefined) { opt.headers['Content-Type'] = 'application/json'; opt.body = JSON.stringify(body); }
    let res;
    try { res = await fetch(url, opt); } catch (e) { throw new Error('Cannot reach the server. Is it still running?'); }
    let data = null;
    try { data = await res.json(); } catch (e) { /* not JSON */ }
    if (res.status === 401 && url !== '/api/login') { showLogin(); throw new Error((data && data.error) || 'Please sign in.'); }
    if (!res.ok) throw new Error((data && data.error) || ('Error ' + res.status));
    return data;
  }

  const nf2 = new Intl.NumberFormat('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  const inr = (n) => '₹ ' + nf2.format(Number(n) || 0);
  /** Short money: 12,34,567 -> "12.3 L" */
  function inrShort(n) {
    n = Number(n) || 0;
    if (n >= 1e7) return '₹' + (n / 1e7).toFixed(1) + ' Cr';
    if (n >= 1e5) return '₹' + (n / 1e5).toFixed(1) + ' L';
    if (n >= 1e3) return '₹' + (n / 1e3).toFixed(1) + 'k';
    return '₹' + Math.round(n);
  }
  const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
  function day(iso) {
    if (!iso) return '';
    const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(iso);
    return m ? (+m[3]) + ' ' + MONTHS[+m[2] - 1] + ' ' + m[1] : iso;
  }
  function when(iso) {
    if (!iso) return '';
    const t = iso.length > 10 ? ' ' + iso.substring(11, 16) : '';
    return day(iso) + t;
  }
  const pct = (x) => Math.round((Number(x) || 0) * 100) + '%';

  /** Bars and meters get their size from data-w / data-h after the HTML is in the page
      (the page's security policy does not allow inline style="" attributes). */
  function sizes(root) {
    $$('[data-w]', root).forEach((e) => { e.style.width = Math.max(0, Math.min(100, +e.dataset.w)) + '%'; });
    $$('[data-h]', root).forEach((e) => { e.style.height = Math.max(0, Math.min(100, +e.dataset.h)) + '%'; });
  }

  function ink(c, corrected) {
    if (corrected) return '<span class="ink full" title="Typed or checked by a person"><span class="tube"><i data-w="100"></i></span>100</span>';
    const v = Math.round((Number(c) || 0) * 100);
    const cls = v >= 85 ? '' : v >= 60 ? 'mid' : 'low';
    return '<span class="ink ' + cls + '" title="How sure the reader is"><span class="tube"><i data-w="' + v + '"></i></span>' + v + '</span>';
  }

  const STAMP = { TO_REVIEW: ['s-review', 'To review'], APPROVED: ['s-approved', 'Approved'], REJECTED: ['s-rejected', 'Rejected'], EXPORTED: ['s-exported', 'Exported'] };
  function stamp(status, auto, big) {
    const s = STAMP[status] || ['s-review', status];
    const label = auto && status === 'APPROVED' ? 'Auto-checked' : s[1];
    return '<span class="stamp ' + (auto && status === 'APPROVED' ? 's-auto' : s[0]) + (big ? ' big' : '') + '">' + esc(label) + '</span>';
  }

  function toast(msg, bad) {
    const t = $('#toast');
    t.textContent = msg;
    t.className = 'toast show' + (bad ? ' bad' : '');
    clearTimeout(toast.timer);
    toast.timer = setTimeout(() => { t.className = 'toast' + (bad ? ' bad' : ''); }, bad ? 4200 : 2600);
  }

  const can = {
    approve: () => state.me && ['ADMIN', 'ACCOUNTANT'].includes(state.me.user.role),
    admin: () => state.me && state.me.user.role === 'ADMIN',
  };

  /** Which highlighter colour each field uses on the bill. */
  const HL = {
    invoiceNo: 'id', poNumber: 'id',
    invoiceDate: 'date', dueDate: 'date', paymentTerms: 'date',
    vendorName: 'party', vendorGstin: 'party', buyerName: 'party', buyerGstin: 'party',
    subtotal: 'amount', total: 'amount', amountInWords: 'amount', currency: 'amount',
    cgst: 'tax', sgst: 'tax', igst: 'tax', taxTotal: 'tax',
  };
  const AMOUNTS = ['subtotal', 'cgst', 'sgst', 'igst', 'taxTotal', 'total'];
  const DATES = ['invoiceDate', 'dueDate'];

  // ------------------------------------------------------------ modal

  /**
   * Open the dialog. fields: html for the body. buttons: [{label, cls, action}] - action gets the form
   * and returns a promise; if it throws, the error is shown and the dialog stays open.
   */
  function modal(title, html, buttons) {
    const dlg = $('#modal');
    $('#modalTitle').textContent = title;
    $('#modalBody').innerHTML = html;
    $('#modalErr').textContent = '';
    const foot = $('#modalFoot');
    foot.innerHTML = '';
    (buttons || []).forEach((b) => {
      const el = document.createElement('button');
      el.type = 'button';
      el.className = 'btn ' + (b.cls || '');
      el.textContent = b.label;
      el.addEventListener('click', async () => {
        if (!b.action) { dlg.close(); return; }
        el.disabled = true;
        try {
          const keep = await b.action($('#modalForm'));
          if (keep !== true) dlg.close();
        } catch (e) { $('#modalErr').textContent = e.message; }
        el.disabled = false;
      });
      foot.appendChild(el);
    });
    sizes($('#modalBody'));
    if (!dlg.open) dlg.showModal();
    const first = $('input, select, textarea', $('#modalBody'));
    if (first) first.focus();
  }
  $('#modalClose').addEventListener('click', () => $('#modal').close());
  $('#modalForm').addEventListener('submit', (e) => e.preventDefault());

  // ------------------------------------------------------------ theme

  function setThemeLabels() {
    const dark = document.documentElement.getAttribute('data-theme') === 'dark'
      || (!document.documentElement.getAttribute('data-theme') && matchMedia('(prefers-color-scheme: dark)').matches);
    const SUN = '<svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="4.5"/><path d="M12 1.5v3M12 19.5v3M1.5 12h3M19.5 12h3M4.6 4.6l2.1 2.1M17.3 17.3l2.1 2.1M4.6 19.4l2.1-2.1M17.3 6.7l2.1-2.1"/></svg>';
    const MOON = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M20.5 14.5A8.5 8.5 0 0 1 9.5 3.5a8.5 8.5 0 1 0 11 11z"/></svg>';
    $$('[data-theme-toggle]').forEach((b) => {
      // the button shows the mode you will switch TO
      b.innerHTML = (dark ? SUN : MOON) + '<span>' + (dark ? 'Light mode' : 'Dark mode') + '</span>';
      b.setAttribute('aria-label', dark ? 'Switch to light mode' : 'Switch to dark mode');
      b.title = dark ? 'Switch to light mode' : 'Switch to dark mode';
    });
    return dark;
  }
  $$('[data-theme-toggle]').forEach((b) => b.addEventListener('click', () => {
    const next = setThemeLabels() ? 'light' : 'dark';
    document.documentElement.setAttribute('data-theme', next);
    try { localStorage.setItem('bahi-theme', next); } catch (e) { /* ignore */ }
    setThemeLabels();
  }));
  setThemeLabels();

  // ------------------------------------------------------------ sign in / out

  function showLogin() {
    state.me = null;
    $('#app').classList.add('hidden');
    $('#login').classList.remove('hidden');
    $('#lu').focus();
  }

  $('#loginForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    $('#loginErr').textContent = '';
    const btn = $('button[type=submit]', e.target);
    btn.disabled = true;
    btn.textContent = 'Opening...';
    try {
      await api('POST', '/api/login', { username: $('#lu').value.trim(), password: $('#lp').value });
      $('#lp').value = '';
      await start();
    } catch (err) { $('#loginErr').textContent = err.message; }
    btn.disabled = false;
    btn.textContent = 'Sign in';
  });
  $$('.demo-row').forEach((b) => b.addEventListener('click', () => {
    const [u, p] = b.dataset.demo.split(':');
    $('#lu').value = u; $('#lp').value = p;
    $('#loginForm').requestSubmit();
  }));
  $('#logoutBtn').addEventListener('click', async () => {
    try { await api('POST', '/api/logout'); } catch (e) { /* ignore */ }
    location.hash = '';
    showLogin();
  });
  $('#who').addEventListener('click', () => modal('Change my password',
    '<label class="field">Current password<input type="password" name="current" autocomplete="current-password"></label>'
    + '<label class="field">New password (8+ characters, a letter and a number)<input type="password" name="next" autocomplete="new-password"></label>',
    [{ label: 'Cancel' }, { label: 'Change password', cls: 'primary', action: async (f) => {
      await api('POST', '/api/me/password', { current: f.current.value, next: f.next.value });
      toast('Password changed.');
    } }]));
  $('#menuBtn').addEventListener('click', () => {
    const open = $('#binding').classList.toggle('open');
    $('#menuBtn').setAttribute('aria-expanded', open);
  });

  async function start() {
    state.me = await api('GET', '/api/me');
    $('#login').classList.add('hidden');
    $('#app').classList.remove('hidden');
    $('#coName').textContent = state.me.company;
    $('#who').textContent = state.me.user.name + ' · ' + state.me.user.roleLabel;
    $('#foot').textContent = state.me.company + (state.me.companyGstin ? ' · GSTIN ' + state.me.companyGstin : '')
      + ' · reader: ' + (state.me.claude ? 'Local AI + Claude' : 'Local AI (offline)');
    if (!location.hash || location.hash === '#/') location.hash = '#/home';
    else route();
  }

  async function refreshMe() {
    try { state.me = await api('GET', '/api/me'); nav(); } catch (e) { /* ignore */ }
  }

  // ------------------------------------------------------------ navigation

  const PAGES = [
    { id: 'home', ix: 'I', label: 'Day Book' },
    { id: 'upload', ix: 'II', label: 'Scan Desk' },
    { id: 'inbox', ix: 'III', label: 'Review Tray', count: true },
    { id: 'ledger', ix: 'IV', label: 'Ledger' },
    { id: 'vendors', ix: 'V', label: 'Vendors' },
    { id: 'model', ix: 'VI', label: 'How Bahi Reads' },
    { sep: 'Office', roles: ['ADMIN', 'ACCOUNTANT'] },
    { id: 'history', ix: 'H', label: 'History', roles: ['ADMIN', 'ACCOUNTANT'] },
    { id: 'users', ix: 'L', label: 'Logins', roles: ['ADMIN'] },
    { id: 'settings', ix: 'S', label: 'Settings', roles: ['ADMIN'] },
  ];

  function nav(active) {
    if (active) nav.active = active;
    const role = state.me.user.role;
    $('#nav').innerHTML = PAGES.filter((p) => !p.roles || p.roles.includes(role)).map((p) => {
      if (p.sep) return '<div class="sep">' + esc(p.sep) + '</div>';
      const count = p.count && state.me.waiting ? '<span class="count">' + state.me.waiting + '</span>' : '';
      return '<a href="#/' + p.id + '" class="' + (p.id === nav.active ? 'active' : '') + '"><span class="ix">' + p.ix + '</span>' + esc(p.label) + count + '</a>';
    }).join('');
  }

  function folio(text) {
    const d = state.me ? day(state.me.today) : '';
    $('#folio').textContent = text + (d ? '  ·  ' + d : '');
  }

  function parseHash() {
    const h = location.hash.replace(/^#\/?/, '');
    const [path, qs] = h.split('?');
    const parts = path.split('/');
    const q = {};
    new URLSearchParams(qs || '').forEach((v, k) => { q[k] = v; });
    return { page: parts[0] || 'home', id: parts[1], q };
  }

  async function route() {
    if (!state.me) return;
    const r = parseHash();
    $('#binding').classList.remove('open');
    $('#topTools').innerHTML = '';
    nav(r.page === 'invoice' ? 'inbox' : r.page);
    view.innerHTML = '<p class="empty">Turning the page...</p>';
    try {
      switch (r.page) {
        case 'home': await pageHome(); break;
        case 'upload': await pageUpload(); break;
        case 'inbox': await pageInbox(r.q); break;
        case 'invoice': await pageInvoice(r.id); break;
        case 'ledger': await pageLedger(r.q); break;
        case 'vendors': await pageVendors(); break;
        case 'model': await pageModel(); break;
        case 'history': await pageHistory(); break;
        case 'users': await pageUsers(); break;
        case 'settings': await pageSettings(); break;
        default: location.hash = '#/home';
      }
      sizes(view);
    } catch (e) {
      view.innerHTML = '<div class="sheet"><p class="err-text">' + esc(e.message) + '</p></div>';
    }
    view.focus({ preventScroll: true });
  }
  window.addEventListener('hashchange', route);

  function head(title, sub, actions) {
    return '<div class="page-head"><div><h1>' + esc(title) + '</h1>' + (sub ? '<p>' + sub + '</p>' : '') + '</div>'
      + (actions ? '<div class="actions">' + actions + '</div>' : '') + '</div>';
  }

  // ================================================================ I. Day Book (home)

  async function pageHome() {
    folio('Folio I · Day book');
    const d = await api('GET', '/api/home');
    const s = d.byStatus;
    let h = head('Day Book', 'Today’s picture of ' + esc(state.me.company) + '’s supplier bills: what is waiting, what was spent and how well Bahi is reading.',
      '<a class="btn primary" href="#/upload">Scan a bill</a>');
    h += '<div class="totals">'
      + '<div><small>Waiting for review</small><b>' + s.TO_REVIEW + '</b><span class="sub">' + inr(d.pendingAmount) + ' in the tray</span></div>'
      + '<div><small>Approved, last 30 days</small><b>' + inrShort(d.monthSpend) + '</b><span class="sub">' + d.monthCount + ' approved · GST ' + inr(d.monthTax) + '</span></div>'
      + '<div><small>Auto-checked (30 days)</small><b>' + pct(d.autoRate30) + '</b><span class="sub">of decided bills needed no person</span></div>'
      + '<div><small>Average confidence</small><b>' + pct(d.avgConfidence30) + '</b><span class="sub">' + d.uploaded30 + ' bills read in 30 days</span></div>'
      + '<div><small>Reading time</small><b>' + d.avgMillis + ' ms</b><span class="sub">per bill, on this computer</span></div>'
      + '</div>';

    const maxM = Math.max(1, ...d.months.map((m) => m.total));
    const bars = d.months.map((m) => '<div class="col"><span class="v">' + (m.total ? inrShort(m.total) : '') + '</span>'
      + '<div class="bar" data-h="' + (m.total / maxM * 100) + '" title="' + esc(m.label + ': ' + inr(m.total) + ', ' + m.count + ' bills') + '">'
      + '<span class="tax" data-h="' + (m.total ? m.tax / m.total * 100 : 0) + '"></span></div></div>').join('');
    const queue = d.queue.length ? d.queue.slice(0, 7).map(slip).join('') : '<p class="empty">The tray is empty. Every bill is checked.</p>';
    h += '<div class="sheets cols-2">'
      + '<section class="sheet"><header><h2>Spend by month</h2><span class="muted">approved bills, by invoice date</span></header>'
      + '<div class="bars">' + bars + '</div><div class="bars-x">' + d.months.map((m) => '<span>' + esc(m.label) + '</span>').join('') + '</div>'
      + '<div class="legend"><span><i class="l-sub"></i>Bill total</span><span><i class="l-tax"></i>GST part</span></div></section>'
      + '<section class="sheet"><header><h2>Review tray</h2><span class="muted">oldest first</span><a class="right" href="#/inbox">Open tray</a></header><div class="tray">' + queue + '</div></section>'
      + '</div>';

    const maxC = Math.max(1, ...d.categories.map((c) => c.total));
    const maxV = Math.max(1, ...d.topVendors.map((c) => c.total));
    h += '<div class="sheets cols-3">'
      + '<section class="sheet"><header><h2>Where the money went</h2><span class="muted">90 days</span></header><div class="hbars">'
      + (d.categories.map((c) => '<div class="hbar"><span class="lbl">' + esc(c.category) + '</span><span class="track"><i data-w="' + (c.total / maxC * 100) + '"></i></span><span class="num">' + inrShort(c.total) + '</span></div>').join('') || '<p class="empty">No approved bills yet.</p>')
      + '</div></section>'
      + '<section class="sheet"><header><h2>Top vendors</h2><span class="muted">90 days</span></header><div class="hbars">'
      + (d.topVendors.map((v) => '<div class="hbar"><span class="lbl" title="' + esc(v.vendor) + '">' + esc(v.vendor) + '</span><span class="track"><i data-w="' + (v.total / maxV * 100) + '"></i></span><span class="num">' + inrShort(v.total) + '</span></div>').join('') || '<p class="empty">No vendors yet.</p>')
      + '</div></section>'
      + '<section class="sheet"><header><h2>Read right first time</h2><span class="muted">no correction needed</span></header><div class="acc-list">'
      + d.accuracy.map((a) => '<div class="acc"><span>' + esc(a.label) + '</span><span class="track"><i data-w="' + a.accuracy * 100 + '"></i></span><span class="num">' + (a.seen ? pct(a.accuracy) : '–') + '</span></div>').join('')
      + '</div></section></div>';

    const due = d.dueSoon.length ? '<div class="table-wrap"><table class="ledger"><thead><tr><th>Due</th><th>Vendor</th><th>Invoice</th><th class="num">Amount</th></tr></thead><tbody>'
      + d.dueSoon.map((r) => '<tr class="click" data-go="' + esc(r.id) + '"><td>' + day(r.dueDate) + '</td><td class="vendor">' + esc(r.vendorName) + '</td><td class="mono">' + esc(r.invoiceNo) + '</td><td class="num">' + inr(r.total) + '</td></tr>').join('')
      + '</tbody></table></div>' : '<p class="empty">Nothing due in the next two weeks.</p>';
    h += '<div class="sheets cols-2">'
      + '<section class="sheet"><header><h2>Due in the next 14 days</h2><span class="muted">approved bills to pay</span></header>' + due + '</section>'
      + '<section class="sheet"><header><h2>Recent entries</h2></header>' + logList(d.recent) + '</section></div>';
    view.innerHTML = h;
    bindRows();
  }

  function slip(r) {
    const checks = r.fails ? '<span class="pill fail">' + r.fails + ' failed</span>' : r.warns ? '<span class="pill warn">' + r.warns + ' to look at</span>' : '<span class="pill ok">checks pass</span>';
    return '<a class="slip" href="#/invoice/' + esc(r.id) + '"><span class="ref">' + esc(r.id) + '</span><span><span class="who">' + esc(r.vendorName || 'Unknown vendor') + '</span> '
      + (r.newVendor ? '<span class="pill new">new vendor</span>' : '') + '<br><span class="what">' + esc(r.invoiceNo || 'no number') + ' · ' + day(r.invoiceDate) + ' · ' + checks + '</span></span>'
      + '<span><span class="amt">' + inr(r.total) + '</span><br>' + ink(r.confidence) + '</span></a>';
  }

  function logList(events) {
    if (!events.length) return '<p class="empty">Nothing yet.</p>';
    return '<ul class="log">' + events.map((e) => '<li><time>' + when(e.at) + '</time><span><b>' + esc(e.user) + '</b> – ' + esc(e.action) + (e.detail ? ': <span class="muted">' + esc(e.detail) + '</span>' : '') + '</span></li>').join('') + '</ul>';
  }

  function bindRows(root) {
    $$('tr[data-go]', root || view).forEach((tr) => tr.addEventListener('click', () => { location.hash = '#/invoice/' + tr.dataset.go; }));
  }

  // ================================================================ II. Scan desk (upload)

  async function pageUpload() {
    folio('Folio II · Scan desk');
    const samples = await api('GET', '/api/samples');
    const claude = state.me.claude;
    let h = head('Scan Desk', 'Give Bahi a supplier bill. Text and PDF files made by billing software are read on this computer, '
      + 'with no internet. Photos and scanned PDFs need Claude.');
    h += '<div class="desk-grid"><section class="sheet">'
      + '<label class="drop" id="drop" tabindex="0"><input type="file" id="file" class="hidden" accept=".txt,.text,.csv,.pdf,.png,.jpg,.jpeg,.webp,text/plain,application/pdf,image/*">'
      + '<svg viewBox="0 0 48 48" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><path d="M12 4h17l9 9v31H12z"/><path d="M29 4v9h9M18 22h14M18 28h14M18 34h9"/></svg>'
      + '<b>Drop a bill here</b><span class="muted small">or click to choose · .txt, .pdf' + (claude ? ', .png, .jpg' : '') + ' · up to 8 MB</span>'
      + '<span class="mono small" id="chosen"></span></label>'
      + '<div class="or">or paste the bill’s text</div>'
      + '<label class="field">Invoice text<textarea class="inp" id="paste" rows="12" placeholder="Paste the text of an invoice here, or pick a sample on the right"></textarea></label>'
      + '<div class="engine" role="radiogroup" aria-label="Reader">'
      + '<label><span><input type="radio" name="engine" value="local" checked><b>Local AI</b></span>Built-in reader: labels, fuzzy matching, scoring and checks. Offline and instant.</label>'
      + '<label class="' + (claude ? '' : 'off') + '"><span><input type="radio" name="engine" value="claude" ' + (claude ? '' : 'disabled') + '><b>Claude</b></span>'
      + (claude ? 'Anthropic’s model reads text, PDFs and photos. Its answer is still checked by Bahi.' : 'Off. The admin can switch it on in Settings when ANTHROPIC_API_KEY is set.') + '</label>'
      + '</div>'
      + '<p class="err-text" id="upErr" role="alert"></p>'
      + '<button class="btn primary wide" id="readBtn" type="button">Read this bill</button>'
      + '<div id="reading" class="reading hidden"><span class="scan"></span><span><b>Reading...</b><br><span class="muted small">finding labels, scoring values, checking the sums</span></span></div>'
      + '</section>'
      + '<section class="sheet"><header><h2>Try a sample</h2><span class="muted">made-up bills in different layouts</span></header><div class="samples">'
      + samples.map((s) => '<button type="button" class="sample" data-sample="' + esc(s.key) + '"><b>' + esc(s.label) + '</b><span>' + esc(s.note) + '</span></button>').join('')
      + '</div><p class="muted small">The <span class="mono">samples</span> folder of the project also has .txt and .pdf bills to upload.</p></section></div>';
    view.innerHTML = h;

    let file = null, fileName = '';
    const chosen = $('#chosen');
    function pick(f) {
      if (!f) return;
      if (f.size > 8 * 1024 * 1024) { $('#upErr').textContent = 'That file is bigger than 8 MB.'; return; }
      file = f;
      fileName = f.name;
      chosen.textContent = 'Chosen: ' + f.name + ' (' + Math.ceil(f.size / 1024) + ' KB)';
      $('#paste').value = '';
      $('#upErr').textContent = '';
    }
    $('#file').addEventListener('change', (e) => pick(e.target.files[0]));
    const drop = $('#drop');
    drop.addEventListener('keydown', (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); $('#file').click(); } });
    ['dragenter', 'dragover'].forEach((ev) => drop.addEventListener(ev, (e) => { e.preventDefault(); drop.classList.add('over'); }));
    ['dragleave', 'drop'].forEach((ev) => drop.addEventListener(ev, (e) => { e.preventDefault(); drop.classList.remove('over'); }));
    drop.addEventListener('drop', (e) => pick(e.dataTransfer.files[0]));
    $('#paste').addEventListener('input', () => { if (file) { file = null; chosen.textContent = ''; } fileName = ''; });
    $$('.sample').forEach((b) => b.addEventListener('click', async () => {
      const s = await api('GET', '/api/samples/' + b.dataset.sample);
      file = null; chosen.textContent = '';
      $('#paste').value = s.text;
      fileName = s.fileName;
      $('#paste').scrollTop = 0;
      toast('Sample filled in. Press "Read this bill".');
    }));

    $('#readBtn').addEventListener('click', async () => {
      $('#upErr').textContent = '';
      const engine = ($('input[name=engine]:checked') || {}).value || 'local';
      let body;
      try {
        if (file) body = await fileBody(file, engine);
        else {
          const text = $('#paste').value;
          if (!text.trim()) throw new Error('Choose a file or paste the text of a bill first.');
          body = { kind: 'text', text, fileName: fileName, engine };
        }
      } catch (e) { $('#upErr').textContent = e.message; return; }
      $('#readBtn').disabled = true;
      $('#reading').classList.remove('hidden');
      try {
        const r = await api('POST', '/api/invoices', body);
        await refreshMe();
        location.hash = '#/invoice/' + r.id;
      } catch (e) {
        $('#upErr').textContent = e.message;
        $('#readBtn').disabled = false;
        $('#reading').classList.add('hidden');
      }
    });
  }

  function readFile(f, asText) {
    return new Promise((resolve, reject) => {
      const r = new FileReader();
      r.onload = () => resolve(r.result);
      r.onerror = () => reject(new Error('The file could not be read.'));
      if (asText) r.readAsText(f); else r.readAsDataURL(f);
    });
  }

  async function fileBody(f, engine) {
    const name = f.name.toLowerCase();
    if (/\.(txt|text|csv)$/.test(name) || f.type.startsWith('text/')) return { kind: 'text', text: await readFile(f, true), fileName: f.name, engine };
    const dataUrl = await readFile(f, false);
    const data = String(dataUrl).split(',')[1] || '';
    if (name.endsWith('.pdf') || f.type === 'application/pdf') return { kind: 'pdf', data, fileName: f.name, engine };
    if (/^image\//.test(f.type)) return { kind: 'image', data, mediaType: f.type === 'image/jpg' ? 'image/jpeg' : f.type, fileName: f.name, engine };
    throw new Error('Bahi reads .txt and .pdf files (and photos when Claude is on).');
  }

  // ================================================================ III. Review tray (list)

  async function pageInbox(q) {
    folio('Folio III · Review tray');
    const status = q.status !== undefined ? q.status : 'TO_REVIEW';
    const params = new URLSearchParams();
    if (status) params.set('status', status);
    if (q.q) params.set('q', q.q);
    if (q.vendor) params.set('vendor', q.vendor);
    if (q.category) params.set('category', q.category);
    const [rows, all] = await Promise.all([api('GET', '/api/invoices?' + params), api('GET', '/api/invoices')]);
    const counts = { TO_REVIEW: 0, APPROVED: 0, EXPORTED: 0, REJECTED: 0 };
    all.forEach((r) => { counts[r.status]++; });
    const tabs = [['TO_REVIEW', 'To review'], ['APPROVED', 'Approved'], ['EXPORTED', 'Exported'], ['REJECTED', 'Rejected'], ['', 'All']];
    const vendorName = q.vendor && rows[0] ? rows[0].vendorName : '';
    let h = head('Review Tray', 'Every bill Bahi has read. Open one to see the bill next to what was read, fix anything, and stamp it.'
      + (q.vendor ? ' Showing only <b>' + esc(vendorName || q.vendor) + '</b> – <a href="#/inbox?status=">show all vendors</a>.' : ''),
      '<a class="btn primary" href="#/upload">Scan a bill</a>');
    h += '<div class="tabs" role="tablist">' + tabs.map(([k, l]) => '<button type="button" data-status="' + k + '" class="' + (k === status ? 'on' : '') + '">' + l
      + '<span class="n">' + (k ? counts[k] : all.length) + '</span></button>').join('') + '</div>';
    h += '<div class="filters"><input class="inp" id="q" type="search" placeholder="Search vendor, number, amount..." value="' + esc(q.q || '') + '">'
      + '<select class="inp" id="cat"><option value="">All categories</option>' + state.me.categories.map((c) => '<option' + (c === q.category ? ' selected' : '') + '>' + esc(c) + '</option>').join('') + '</select></div>';
    if (!rows.length) h += '<div class="sheet"><p class="empty">' + (status === 'TO_REVIEW' ? 'Nothing waiting. The tray is empty.' : 'No bills here.') + '</p></div>';
    else {
      h += '<div class="sheet table-wrap"><table class="ledger"><thead><tr><th>Ref</th><th>Vendor</th><th>Invoice no.</th><th>Date</th><th class="num">Total</th><th>Read</th><th>Checks</th><th>Received</th><th></th></tr></thead><tbody>'
        + rows.map((r) => '<tr class="click" data-go="' + esc(r.id) + '"><td class="mono">' + esc(r.id) + '</td>'
          + '<td class="vendor">' + esc(r.vendorName || '–') + (r.newVendor ? ' <span class="pill new">new</span>' : '') + '<span class="sub">' + esc(r.category) + '</span></td>'
          + '<td class="mono">' + esc(r.invoiceNo || '–') + '</td><td>' + day(r.invoiceDate) + '</td><td class="num">' + inr(r.total) + '</td>'
          + '<td>' + ink(r.confidence) + '<span class="sub">' + esc(r.engine) + (r.corrected ? ' · ' + r.corrected + ' fixed' : '') + '</span></td>'
          + '<td>' + (r.fails ? '<span class="pill fail">' + r.fails + ' failed</span> ' : '') + (r.warns ? '<span class="pill warn">' + r.warns + ' warn</span>' : '') + (!r.fails && !r.warns ? '<span class="pill ok">pass</span>' : '') + '</td>'
          + '<td class="small">' + when(r.uploadedAt) + '<span class="sub">' + esc(r.uploadedBy) + '</span></td>'
          + '<td>' + stamp(r.status, r.autoApproved) + '</td></tr>').join('')
        + '</tbody></table></div>';
    }
    view.innerHTML = h;
    bindRows();
    const go = (extra) => {
      const p = new URLSearchParams();
      const st = extra.status !== undefined ? extra.status : status;
      p.set('status', st);
      const text = extra.q !== undefined ? extra.q : $('#q').value.trim();
      if (text) p.set('q', text);
      if (q.vendor) p.set('vendor', q.vendor);
      const c = extra.category !== undefined ? extra.category : $('#cat').value;
      if (c) p.set('category', c);
      location.hash = '#/inbox?' + p;
    };
    $$('.tabs button').forEach((b) => b.addEventListener('click', () => go({ status: b.dataset.status })));
    $('#q').addEventListener('keydown', (e) => { if (e.key === 'Enter') go({}); });
    $('#q').addEventListener('search', () => go({}));
    $('#cat').addEventListener('change', () => go({}));
  }

  // ================================================================ the review screen

  async function pageInvoice(id) {
    folio('Folio III · Bill ' + id);
    const d = await api('GET', '/api/invoices/' + encodeURIComponent(id));
    renderInvoice(d);
  }

  function renderInvoice(d, thump) {
    const editable = d.status === 'TO_REVIEW';
    const byName = {};
    d.fields.forEach((f) => { byName[f.name] = f; });

    // ---- the bill, with highlighter marks on the lines values came from
    const marks = {};
    d.fields.forEach((f) => { if (f.line >= 0 && f.value && !marks[f.line]) marks[f.line] = HL[f.name]; });
    const itemLines = new Set(d.items.map((it) => it.line).filter((n) => n >= 0));
    const paper = d.rawLines.length
      ? '<pre>' + d.rawLines.map((l, i) => '<span class="ln' + (marks[i] ? ' mark hl-' + marks[i] : '') + (itemLines.has(i) ? ' items' : '') + '" data-n="' + (i + 1) + '" id="ln' + i + '">' + esc(l) + '</span>').join('\n') + '</pre>'
      : '<p class="empty">No text for this bill (read by Claude from a photo).</p>';

    const fails = d.checks.filter((c) => c.result === 'FAIL').length;
    const warns = d.checks.filter((c) => c.result === 'WARN').length;
    let notes = '';
    if (d.duplicate) notes += '<div class="note-box bad">Looks like a <b>duplicate</b> of <a href="#/invoice/' + esc(d.duplicate.id) + '">' + esc(d.duplicate.id) + '</a> (' + esc(d.duplicate.vendorName) + ' ' + esc(d.duplicate.invoiceNo) + ', ' + inr(d.duplicate.total) + ', ' + esc(d.duplicate.statusLabel) + ').</div>';
    if (d.newVendor && editable) notes += '<div class="note-box">This vendor is <b>new</b> to Bahi. It will be added to Vendors when the bill is approved, and Bahi will learn its layout from any corrections.</div>';
    if (d.note) notes += '<div class="note-box' + (d.status === 'REJECTED' ? ' bad' : '') + '"><b>Note:</b> ' + esc(d.note) + '</div>';
    if (d.status === 'APPROVED' && d.autoApproved) notes += '<div class="note-box good">Approved by <b>auto-check</b>: a known vendor, every check passed and Bahi was at least ' + pct(0.95) + ' sure.</div>';
    if (d.vendor && Object.keys(d.vendor.labelHints || {}).length && editable)
      notes += '<div class="note-box good">Bahi used what it learned about <b>' + esc(d.vendor.name) + '</b>: ' + Object.entries(d.vendor.labelHints).map(([k, v]) => '“' + esc(v) + '” = ' + esc(labelOf(k))).join(', ') + '.</div>';

    const section = (title, names) => '<p class="section-label">' + title + '</p>' + names.map((n) => fieldRow(byName[n], editable)).join('');
    const catOptions = state.me.categories.map((c) => '<option' + (c === d.category ? ' selected' : '') + '>' + esc(c) + '</option>').join('');
    const scores = Object.entries(d.categoryScores || {}).map(([c, v]) => '<div class="catrow"><span>' + esc(c) + '</span><span class="track"><i data-w="' + v * 100 + '"></i></span><span class="num small">' + pct(v) + '</span></div>').join('');

    let decide = '';
    if (editable) {
      decide = '<button type="button" class="btn" id="saveBtn" disabled>Save corrections</button>'
        + '<button type="button" class="btn" id="rereadBtn" title="Read the bill again with everything Bahi has learned (your corrections are kept)">Read again</button><span class="spacer"></span>';
      if (can.approve()) decide += '<button type="button" class="btn danger" id="rejectBtn">Reject</button><button type="button" class="btn ok" id="approveBtn">Approve</button>';
      else decide += '<span class="muted small">An accountant approves bills. Fix anything you can and save.</span>';
    } else if (can.approve() && d.status !== 'EXPORTED') {
      decide = '<span class="muted small">' + (d.status === 'APPROVED' ? 'Approved by ' : 'Rejected by ') + esc(d.reviewedBy) + ' on ' + when(d.reviewedAt) + '.</span><span class="spacer"></span><button type="button" class="btn" id="reopenBtn">Reopen for review</button>';
    } else {
      decide = '<span class="muted small">' + esc(d.statusLabel) + (d.reviewedBy ? ' · checked by ' + esc(d.reviewedBy) + ' on ' + when(d.reviewedAt) : '') + '.</span>';
    }

    view.innerHTML = '<p class="small"><a href="#/inbox">← Review tray</a></p>'
      + '<div class="review-head"><div><h1>' + esc(d.vendorName || 'Unknown vendor') + '</h1>'
      + '<div class="meta"><span class="mono">' + esc(d.id) + '</span> · ' + esc(d.fileName) + ' · read by ' + esc(d.engine) + (d.extractMillis ? ' in ' + d.extractMillis + ' ms' : '')
      + ' · received ' + when(d.uploadedAt) + ' from ' + esc(d.uploadedBy) + '</div></div>'
      + '<div class="stamp-slot">' + stamp(d.status, d.autoApproved, true) + '</div></div>'
      + notes
      + '<div class="review">'
      + '<section class="slipdoc"><div class="tools"><b>The bill</b><span class="keys">'
      + '<span class="hl-id"><i></i>numbers</span><span class="hl-date"><i></i>dates</span><span class="hl-party"><i></i>parties</span><span class="hl-amount"><i></i>amounts</span><span class="hl-tax"><i></i>tax</span></span></div>'
      + '<div class="paper" id="paper">' + paper + '</div></section>'
      + '<section class="sheet form-sheet"><header><h2>What Bahi read</h2><span class="right">' + ink(d.confidence) + '</span></header>'
      + '<p class="muted small">Click a label to see where it was found. ' + (editable ? 'Type over anything that is wrong – Bahi learns from corrections when the bill is approved.' : '') + '</p>'
      + '<div class="fields">'
      + section('Vendor and buyer', ['vendorName', 'vendorGstin', 'buyerName', 'buyerGstin'])
      + section('Invoice', ['invoiceNo', 'invoiceDate', 'dueDate', 'poNumber', 'paymentTerms', 'currency'])
      + section('Amounts', ['subtotal', 'cgst', 'sgst', 'igst', 'taxTotal', 'total', 'amountInWords'])
      + '</div>'
      + '<p class="section-label">Line items</p>' + itemsTable(d.items, editable)
      + '<p class="section-label">Expense category</p><div class="catbox"><select class="inp" id="category" ' + (editable ? '' : 'disabled') + '>' + (d.category ? '' : '<option value="">–</option>') + catOptions + '</select>'
      + (d.categoryCorrected ? '<span class="muted small">Chosen by a person.</span>' : scores ? '<span class="muted small">Naive Bayes guess from the item words' + (d.vendor && d.vendor.defaultCategory ? ' and the vendor’s usual category' : '') + ':</span>' + scores : '') + '</div>'
      + '<p class="section-label">Checks · ' + (d.checks.length - fails - warns) + ' pass' + (warns ? ', ' + warns + ' to look at' : '') + (fails ? ', <span class="fail">' + fails + ' failed</span>' : '') + '</p>'
      + '<ul class="checks">' + d.checks.map((c) => '<li><span class="tick ' + c.result + '">' + (c.result === 'PASS' ? '✓' : c.result === 'WARN' ? '!' : '✗') + '</span><span><b>' + esc(c.label) + '</b><span>' + esc(c.detail) + '</span></span></li>').join('') + '</ul>'
      + '<div class="decide">' + decide + '</div>'
      + '</section></div>';
    sizes(view);
    if (thump) { const st = $('.stamp-slot .stamp'); if (st) st.classList.add('thump'); }

    // ---- interactions
    $$('.frow label').forEach((l) => l.addEventListener('click', (e) => { e.preventDefault(); locate(+l.dataset.line); }));
    if (!editable) {
      const ro = $('#reopenBtn');
      if (ro) ro.addEventListener('click', async () => {
        try { renderInvoice(await api('POST', '/api/invoices/' + d.id + '/reopen')); await refreshMe(); toast('Back in the review tray.'); } catch (e) { toast(e.message, true); }
      });
      return;
    }
    const save = $('#saveBtn');
    const dirty = () => { save.disabled = !changes(d).any; };
    $$('.frow input, .items-table input, #category').forEach((el) => el.addEventListener('input', () => {
      const row = el.closest('.frow');
      if (row) row.classList.toggle('changed', el.value !== el.dataset.orig);
      dirty();
    }));
    $('#category').addEventListener('change', dirty);
    $('#addItem').addEventListener('click', () => {
      const tb = $('.items-table tbody');
      tb.insertAdjacentHTML('beforeend', itemRow({ description: '', hsn: '', qty: 1, rate: 0, amount: 0 }, true));
      bindItemRow(tb.lastElementChild, dirty);
      dirty();
    });
    $$('.items-table tbody tr').forEach((tr) => bindItemRow(tr, dirty));

    save.addEventListener('click', async () => {
      const c = changes(d);
      if (!c.any) return;
      save.disabled = true;
      try { renderInvoice(await api('PUT', '/api/invoices/' + d.id, c.body)); toast('Corrections saved and checks run again.'); } catch (e) { toast(e.message, true); save.disabled = false; }
    });
    $('#rereadBtn').addEventListener('click', async () => {
      try {
        if (changes(d).any) await api('PUT', '/api/invoices/' + d.id, changes(d).body);
        renderInvoice(await api('POST', '/api/invoices/' + d.id + '/reread'));
        toast('Read again.');
      } catch (e) { toast(e.message, true); }
    });
    const rej = $('#rejectBtn');
    if (rej) rej.addEventListener('click', () => modal('Reject this bill?',
      '<p class="muted">Rejected bills are not paid and not exported. Say why, so the clerk can tell the vendor.</p>'
      + '<label class="field">Reason<textarea class="inp" name="reason" rows="3">' + (d.duplicate ? 'Duplicate of ' + esc(d.duplicate.id) + '.' : '') + '</textarea></label>',
      [{ label: 'Cancel' }, { label: 'Reject', cls: 'danger', action: async (f) => {
        const r = await api('POST', '/api/invoices/' + d.id + '/reject', { reason: f.reason.value });
        await refreshMe();
        renderInvoice(r, true);
      } }]));
    const app = $('#approveBtn');
    if (app) app.addEventListener('click', async () => {
      try {
        if (changes(d).any) d = await api('PUT', '/api/invoices/' + d.id, changes(d).body);
        const nowFails = d.checks.filter((c) => c.result === 'FAIL');
        if (nowFails.length) {
          renderInvoice(d);
          modal('A check failed – approve anyway?',
            '<ul class="checks">' + nowFails.map((c) => '<li><span class="tick FAIL">✗</span><span><b>' + esc(c.label) + '</b><span>' + esc(c.detail) + '</span></span></li>').join('') + '</ul>'
            + '<label class="field">Why is it fine? (saved with the bill)<textarea class="inp" name="note" rows="3"></textarea></label>',
            [{ label: 'Cancel' }, { label: 'Approve anyway', cls: 'ok', action: async (f) => {
              const r = await api('POST', '/api/invoices/' + d.id + '/approve', { override: true, note: f.note.value });
              await refreshMe();
              renderInvoice(r, true);
              toast('Approved and added to the ledger.');
            } }]);
          return;
        }
        const r = await api('POST', '/api/invoices/' + d.id + '/approve', {});
        await refreshMe();
        renderInvoice(r, true);
        toast('Approved and added to the ledger.' + (d.newVendor ? ' New vendor saved.' : ''));
      } catch (e) { toast(e.message, true); }
    });
  }

  function labelOf(name) {
    const f = (state.me.fields || []).find((x) => x.name === name);
    return f ? f.label : name;
  }

  function shown(f) {
    if (!f.value) return '';
    if (AMOUNTS.includes(f.name)) return nf2.format(Number(f.value));
    return f.value;
  }

  function fieldRow(f, editable) {
    const type = DATES.includes(f.name) ? 'date' : 'text';
    const val = shown(f);
    let how;
    if (f.corrected) how = '<span class="fix">Corrected by a person</span>' + (f.original ? ' · Bahi read “' + esc(AMOUNTS.includes(f.name) && f.original ? nf2.format(+f.original) : f.original) + '”' : ' · Bahi found nothing');
    else if (!f.value) how = 'Not found on the bill';
    else how = (f.line >= 0 ? 'line ' + (f.line + 1) + ' · ' : '') + (f.foundLabel ? 'label “' + esc(f.foundLabel) + '” · ' : '') + esc(f.method);
    return '<div class="frow hl-' + HL[f.name] + (f.value ? '' : ' missing') + '"><span class="sw"></span>'
      + '<label for="f_' + f.name + '" data-line="' + f.line + '">' + esc(f.label) + '</label>'
      + '<input id="f_' + f.name + '" type="' + type + '" data-field="' + f.name + '" data-orig="' + esc(val) + '" value="' + esc(val) + '" ' + (editable ? '' : 'disabled') + ' placeholder="' + (editable ? 'not found – type it' : '') + '" autocomplete="off">'
      + ink(f.confidence, f.corrected) + '<span class="how">' + how + '</span></div>';
  }

  function itemsTable(items, editable) {
    return '<div class="table-wrap"><table class="items-table"><thead><tr><th>Description</th><th>HSN/SAC</th><th class="num">Qty</th><th class="num">Rate</th><th class="num">Amount</th>' + (editable ? '<th></th>' : '') + '</tr></thead><tbody>'
      + items.map((it) => itemRow(it, editable)).join('') + '</tbody></table></div>'
      + (editable ? '<button type="button" class="link-btn small" id="addItem">+ Add a row</button>' : (items.length ? '' : '<p class="muted small">No item table was found.</p>'));
  }

  function itemRow(it, editable) {
    const dis = editable ? '' : ' disabled';
    const bad = it.description && !it.mathOk && Math.abs(it.qty * it.rate - it.amount) > 1;
    return '<tr' + (bad ? ' class="bad" title="Qty x rate does not equal the amount"' : '') + (it.line >= 0 ? ' data-line="' + it.line + '"' : '') + '>'
      + '<td class="desc"><input data-k="description" value="' + esc(it.description) + '"' + dis + '></td>'
      + '<td><input data-k="hsn" value="' + esc(it.hsn) + '"' + dis + ' size="7"></td>'
      + '<td><input data-k="qty" class="num" value="' + esc(it.qty) + '"' + dis + ' size="4"></td>'
      + '<td><input data-k="rate" class="num" value="' + esc(nf2.format(it.rate)) + '"' + dis + ' size="9"></td>'
      + '<td><input data-k="amount" class="num" value="' + esc(nf2.format(it.amount)) + '"' + dis + ' size="10"></td>'
      + (editable ? '<td><button type="button" class="x" title="Remove row" aria-label="Remove row">×</button></td>' : '') + '</tr>';
  }

  function bindItemRow(tr, dirty) {
    const x = $('.x', tr);
    if (x) x.addEventListener('click', () => { tr.remove(); dirty(); });
    $$('input', tr).forEach((i) => {
      i.addEventListener('input', dirty);
      i.addEventListener('focus', () => { if (tr.dataset.line) locate(+tr.dataset.line); });
    });
  }

  /** What the person changed on the review screen. */
  function changes(d) {
    const body = {};
    const fields = {};
    $$('.frow input').forEach((i) => { if (i.value !== i.dataset.orig) fields[i.dataset.field] = i.value; });
    if (Object.keys(fields).length) body.fields = fields;
    const num = (s) => Number(String(s).replace(/[^0-9.\-]/g, '')) || 0;
    const rows = $$('.items-table tbody tr').map((tr) => {
      const g = (k) => $('input[data-k=' + k + ']', tr).value;
      return { description: g('description').trim(), hsn: g('hsn').trim(), qty: num(g('qty')), rate: num(g('rate')), amount: num(g('amount')) };
    });
    const same = rows.length === d.items.length && rows.every((r, i) => {
      const o = d.items[i];
      return r.description === o.description && r.hsn === o.hsn && r.qty === o.qty && Math.abs(r.rate - o.rate) < 0.005 && Math.abs(r.amount - o.amount) < 0.005;
    });
    if (!same) body.items = rows;
    const cat = $('#category') ? $('#category').value : d.category;
    if (cat && cat !== d.category) body.category = cat;
    return { any: Object.keys(body).length > 0, body };
  }

  function locate(line) {
    if (!(line >= 0)) return;
    const el = $('#ln' + line);
    const paper = $('#paper');
    if (!el || !paper) return;
    paper.scrollTo({ top: el.offsetTop - paper.clientHeight / 3, behavior: 'smooth' });
    el.classList.remove('flash');
    void el.offsetWidth;
    el.classList.add('flash');
  }

  // ================================================================ IV. Ledger

  async function pageLedger(q) {
    folio('Folio IV · Ledger');
    if (!state.vendors) state.vendors = await api('GET', '/api/vendors');
    const p = new URLSearchParams();
    ['from', 'to', 'status', 'category', 'vendor'].forEach((k) => { if (q[k]) p.set(k, q[k]); });
    const d = await api('GET', '/api/ledger?' + p);
    const exp = new URLSearchParams();
    if (q.from) exp.set('from', q.from);
    if (q.to) exp.set('to', q.to);
    if (q.status) exp.set('status', q.status);
    const actions = can.approve()
      ? '<a class="btn" href="/api/export/register.csv?' + exp + '" download>Register (CSV)</a><a class="btn" href="/api/export/items.csv?' + exp + '" download>Line items (CSV)</a>'
        + '<a class="btn" href="/api/export/invoices.json?' + exp + '" download>JSON</a><button type="button" class="btn primary" id="exportBtn">Export to accounts</button>'
      : '';
    let h = head('Ledger', 'Approved bills, in date order – the register your accounts team posts from. Exports open in Excel or any accounting app.', actions);
    h += '<div class="filters"><label class="small muted">From <input class="inp" type="date" id="from" value="' + esc(q.from || '') + '"></label>'
      + '<label class="small muted">To <input class="inp" type="date" id="to" value="' + esc(q.to || '') + '"></label>'
      + '<select class="inp" id="st"><option value="">Approved + exported</option><option value="APPROVED"' + (q.status === 'APPROVED' ? ' selected' : '') + '>Not yet exported</option><option value="EXPORTED"' + (q.status === 'EXPORTED' ? ' selected' : '') + '>Exported</option></select>'
      + '<select class="inp" id="cat"><option value="">All categories</option>' + state.me.categories.map((c) => '<option' + (c === q.category ? ' selected' : '') + '>' + esc(c) + '</option>').join('') + '</select>'
      + '<select class="inp" id="ven"><option value="">All vendors</option>' + state.vendors.map((v) => '<option value="' + esc(v.id) + '"' + (v.id === q.vendor ? ' selected' : '') + '>' + esc(v.name) + '</option>').join('') + '</select></div>';
    if (!d.rows.length) h += '<div class="sheet"><p class="empty">No approved bills match.</p></div>';
    else {
      h += '<div class="sheet table-wrap"><table class="ledger"><thead><tr><th>Date</th><th>Ref</th><th>Vendor</th><th>Invoice no.</th><th>Category</th><th class="num">Taxable</th><th class="num">GST</th><th class="num">Total</th><th></th></tr></thead><tbody>'
        + d.rows.map((r) => '<tr class="click" data-go="' + esc(r.id) + '"><td>' + day(r.invoiceDate) + '</td><td class="mono">' + esc(r.id) + '</td><td class="vendor">' + esc(r.vendorName) + '</td>'
          + '<td class="mono">' + esc(r.invoiceNo) + '</td><td>' + esc(r.category) + '</td><td class="num">' + nf2.format(r.subtotal) + '</td><td class="num">' + nf2.format(r.taxTotal) + '</td>'
          + '<td class="num">' + nf2.format(r.total) + '</td><td>' + stamp(r.status, r.autoApproved) + '</td></tr>').join('')
        + '</tbody><tfoot><tr><td colspan="5">' + d.rows.length + ' bills</td><td class="num">' + nf2.format(d.subtotal) + '</td><td class="num">' + nf2.format(d.tax) + '</td><td class="num">' + inr(d.total) + '</td><td></td></tr></tfoot></table></div>';
    }
    view.innerHTML = h;
    bindRows();
    const go = () => {
      const n = new URLSearchParams();
      [['from', '#from'], ['to', '#to'], ['status', '#st'], ['category', '#cat'], ['vendor', '#ven']].forEach(([k, s]) => { if ($(s).value) n.set(k, $(s).value); });
      location.hash = '#/ledger?' + n;
    };
    ['#from', '#to', '#st', '#cat', '#ven'].forEach((s) => $(s).addEventListener('change', go));
    const ex = $('#exportBtn');
    if (ex) ex.addEventListener('click', () => {
      const waiting = d.rows.filter((r) => r.status === 'APPROVED');
      modal('Export to accounts', waiting.length
        ? '<p>Download the register of the <b>' + waiting.length + '</b> approved bill' + (waiting.length === 1 ? '' : 's') + ' not yet exported (' + inr(waiting.reduce((a, r) => a + r.total, 0)) + '), and stamp them <b>Exported</b> so they are not posted twice.</p>'
        : '<p>Every approved bill here was already exported.</p>',
      waiting.length ? [{ label: 'Cancel' }, { label: 'Download and stamp', cls: 'primary', action: async () => {
        const e2 = new URLSearchParams(exp);
        e2.set('status', 'APPROVED');
        e2.set('mark', '1');
        const a = document.createElement('a');
        a.href = '/api/export/register.csv?' + e2;
        a.download = '';
        document.body.appendChild(a);
        a.click();
        a.remove();
        setTimeout(() => { route(); toast('Exported. The bills are stamped.'); }, 900);
      } }] : [{ label: 'Close' }]);
    });
  }

  // ================================================================ V. Vendors

  async function pageVendors() {
    folio('Folio V · Vendor khata');
    const list = await api('GET', '/api/vendors');
    state.vendors = list;
    let h = head('Vendors', 'Each supplier gets its own khata (account). Vendors are added when their first bill is approved. '
      + 'When you correct a field, Bahi remembers the words that vendor prints in front of it – that is the “learned” box.');
    if (!list.length) h += '<div class="sheet"><p class="empty">No vendors yet. Approve a bill to add its vendor.</p></div>';
    h += '<div class="khata">' + list.map((v) => '<article class="kcard"><div class="spine"><b>' + esc(v.name) + '</b><small>' + esc(v.gstin || 'no GSTIN') + (v.gstin && !v.gstinValid ? ' – invalid' : '') + '</small></div>'
      + '<div class="body"><dl>'
      + '<dt>State</dt><dd>' + esc(v.state || '–') + '</dd>'
      + '<dt>Usual category</dt><dd>' + esc(v.defaultCategory || '–') + '</dd>'
      + '<dt>Bills</dt><dd>' + v.invoices + (v.waiting ? ' (' + v.waiting + ' waiting)' : '') + '</dd>'
      + '<dt>Approved spend</dt><dd>' + inr(v.spend) + '</dd>'
      + '<dt>Average confidence</dt><dd>' + pct(v.avgConfidence) + '</dd>'
      + '<dt>Auto-checked</dt><dd>' + v.autoApproved + '</dd>'
      + '<dt>Last bill</dt><dd>' + day(v.lastInvoice) + '</dd>'
      + '</dl>'
      + (v.labelHints.length ? v.labelHints.map((l) => '<div class="learned"><b>Learned:</b> “' + esc(l.words) + '” means ' + esc(l.label.toLowerCase())
        + (can.approve() ? ' <button type="button" class="link-btn small" data-forget="' + esc(l.field) + '" data-v="' + esc(v.id) + '">forget</button>' : '') + '</div>').join('') : '')
      + (v.aliases.length ? '<div class="small muted">Also written as: ' + v.aliases.map(esc).join(', ') + '</div>' : '')
      + (v.corrections ? '<div class="small muted">' + v.corrections + ' field' + (v.corrections === 1 ? '' : 's') + ' corrected by people so far.</div>' : '')
      + '</div><footer><a class="btn sm" href="#/inbox?status=&vendor=' + esc(v.id) + '">Bills</a>'
      + (can.approve() ? '<button type="button" class="btn sm" data-edit="' + esc(v.id) + '">Edit</button>' : '') + '</footer></article>').join('') + '</div>';
    view.innerHTML = h;
    $$('[data-edit]').forEach((b) => b.addEventListener('click', () => {
      const v = list.find((x) => x.id === b.dataset.edit);
      modal('Edit vendor',
        '<label class="field">Name<input name="name" value="' + esc(v.name) + '"></label>'
        + '<label class="field">GSTIN<input name="gstin" value="' + esc(v.gstin) + '" class="mono"></label>'
        + '<label class="field">Usual category<select name="cat"><option value="">–</option>' + state.me.categories.map((c) => '<option' + (c === v.defaultCategory ? ' selected' : '') + '>' + esc(c) + '</option>').join('') + '</select></label>',
        [{ label: 'Cancel' }, { label: 'Save', cls: 'primary', action: async (f) => {
          await api('PUT', '/api/vendors/' + v.id, { name: f.name.value, gstin: f.gstin.value, defaultCategory: f.cat.value });
          toast('Vendor saved.');
          route();
        } }]);
    }));
    $$('[data-forget]').forEach((b) => b.addEventListener('click', async () => {
      const v = list.find((x) => x.id === b.dataset.v);
      try {
        await api('PUT', '/api/vendors/' + v.id, { name: v.name, gstin: v.gstin, defaultCategory: v.defaultCategory, forget: b.dataset.forget });
        toast('Forgotten.');
        route();
      } catch (e) { toast(e.message, true); }
    }));
  }

  // ================================================================ VI. How Bahi reads (the AI)

  async function pageModel() {
    folio('Folio VI · How Bahi reads');
    const m = await api('GET', '/api/model');
    const avgField = m.fieldAccuracy.filter((a) => a.seen).reduce((a, b, i, arr) => a + b.accuracy / arr.length, 0);
    let h = head('How Bahi Reads', 'The reader is built into the Java backend – no outside library and no internet. Here is what it does and what it has learned so far.',
      can.admin() ? '<button type="button" class="btn" id="retrain">Retrain category model</button>' : '');
    h += '<div class="explain">'
      + '<div><b>Clean</b>Split the bill into lines and repair scan mistakes inside numbers (O→0, l→1, S→5), never inside a GSTIN.</div>'
      + '<div><b>Find</b>Look for about 80 label phrases (“Invoice No”, “Bill Date”, “Grand Total”). Fuzzy matching (Levenshtein) still finds “lnvoice N0”. The value is taken to the right, or under the label in the same column.</div>'
      + '<div><b>Score</b>Every candidate value gets points for label strength, position and shape, plus a bonus for words this vendor taught Bahi. Amounts must agree: items = subtotal, subtotal + GST = total.</div>'
      + '<div><b>Check &amp; learn</b>Eleven checks (GSTIN check digit, CGST/SGST vs IGST, rates, words, duplicates). Approving a bill trains the category model and remembers corrected labels.</div>'
      + '</div>';
    h += '<div class="totals">'
      + '<div><small>Fields right first time</small><b>' + pct(avgField) + '</b><span class="sub">average over 8 key fields</span></div>'
      + '<div><small>Category right first time</small><b>' + pct(m.categoryAccuracy) + '</b><span class="sub">of approved bills</span></div>'
      + '<div><small>Category model</small><b>' + m.trainedOn + '</b><span class="sub">examples (' + m.starterExamples + ' starter + approved bills)</span></div>'
      + '<div><small>Vocabulary</small><b>' + m.vocabulary + '</b><span class="sub">different words learned</span></div>'
      + '<div><small>Learned vendor labels</small><b>' + m.learnedLabels.length + '</b><span class="sub">from people’s corrections</span></div>'
      + '</div>';
    h += '<div class="sheets cols-2">'
      + '<section class="sheet"><header><h2>Try the category guesser</h2><span class="muted">Multinomial Naive Bayes</span></header>'
      + '<label class="field">Words from a bill<textarea class="inp" id="tt" rows="3">2 x laptop toner cartridge and a wireless mouse</textarea></label>'
      + '<button type="button" class="btn primary" id="testBtn">Guess the category</button><div id="testOut" class="catbox"></div></section>'
      + '<section class="sheet"><header><h2>Getting better each month</h2><span class="muted">approved bills by upload month</span></header><div class="trend">'
      + m.trend.map((t) => '<div><small>' + esc(t.label) + ' · ' + t.invoices + ' bills</small><b>' + (t.invoices ? pct(t.clean) : '–') + '</b><small>needed no fix</small><br><small>' + (t.invoices ? pct(t.auto) : '–') + ' auto-checked</small></div>').join('')
      + '</div><p class="muted small">Auto-check was switched on part-way through; vendors seen before are read with what Bahi learned about them.</p>'
      + '<p class="small">Claude: ' + (m.claudeOn ? '<span class="pill ok">on</span> photos and scans can be read.' : m.claudeKey ? '<span class="pill warn">key found, switched off</span> (Settings).' : '<span class="pill">not set up</span> set ANTHROPIC_API_KEY before starting the app.') + '</p></section></div>';
    h += '<section class="sheet"><header><h2>What each category is learned from</h2><span class="muted">words that point most strongly to it</span></header><div class="table-wrap"><table class="ledger"><thead><tr><th>Category</th><th class="num">Examples</th><th class="num">Approved bills</th><th class="num">Guessed right</th><th>Strongest words</th></tr></thead><tbody>'
      + m.categories.map((c) => '<tr><td class="vendor">' + esc(c.category) + '</td><td class="num">' + c.examples + '</td><td class="num">' + c.invoices + '</td><td class="num">' + (c.invoices ? pct(c.rightFirstTime / c.invoices) : '–') + '</td><td><span class="words">' + c.topWords.map((w) => '<span>' + esc(w) + '</span>').join('') + '</span></td></tr>').join('')
      + '</tbody></table></div></section>';
    h += '<div class="sheets cols-2">'
      + '<section class="sheet"><header><h2>Labels learned from corrections</h2></header>'
      + (m.learnedLabels.length ? '<table class="ledger"><thead><tr><th>Vendor</th><th>Prints</th><th>Means</th></tr></thead><tbody>'
        + m.learnedLabels.map((l) => '<tr><td>' + esc(l.vendor) + '</td><td class="mono">“' + esc(l.words) + '”</td><td>' + esc(l.field) + '</td></tr>').join('') + '</tbody></table>'
        : '<p class="empty">Nothing yet. Correct a field on a bill and approve it.</p>') + '</section>'
      + '<section class="sheet"><header><h2>Read right first time, by field</h2></header><div class="acc-list">'
      + m.fieldAccuracy.map((a) => '<div class="acc"><span>' + esc(a.label) + ' <span class="muted small">(' + a.corrected + ' of ' + a.seen + ' fixed)</span></span><span class="track"><i data-w="' + a.accuracy * 100 + '"></i></span><span class="num">' + (a.seen ? pct(a.accuracy) : '–') + '</span></div>').join('')
      + '</div></section></div>';
    view.innerHTML = h;
    const test = async () => {
      try {
        const r = await api('POST', '/api/model/test', { text: $('#tt').value });
        const rows = Object.entries(r.scores).slice(0, 5);
        $('#testOut').innerHTML = '<p class="small muted">Words used: ' + (r.words.length ? r.words.map(esc).join(', ') : 'none') + '</p>'
          + rows.map(([c, v]) => '<div class="catrow"><span>' + esc(c) + '</span><span class="track"><i data-w="' + v * 100 + '"></i></span><span class="num small">' + pct(v) + '</span></div>').join('');
        sizes($('#testOut'));
      } catch (e) { $('#testOut').innerHTML = '<p class="err-text">' + esc(e.message) + '</p>'; }
    };
    $('#testBtn').addEventListener('click', test);
    test();
    const rt = $('#retrain');
    if (rt) rt.addEventListener('click', async () => {
      try { const r = await api('POST', '/api/model/retrain'); toast('Retrained on ' + r.invoices + ' approved bills.'); route(); } catch (e) { toast(e.message, true); }
    });
  }

  // ================================================================ office pages

  async function pageHistory() {
    folio('History');
    const ev = await api('GET', '/api/events');
    let h = head('History', 'Who did what, newest first. Entries by “Learning” show what Bahi learned from approved bills.');
    h += '<div class="filters"><input class="inp" id="hq" type="search" placeholder="Filter..."></div>';
    h += '<div class="sheet table-wrap"><table class="ledger"><thead><tr><th>When</th><th>Who</th><th>What</th><th>Details</th></tr></thead><tbody id="hb">'
      + ev.map((e) => '<tr><td class="mono small">' + when(e.at) + '</td><td>' + esc(e.user) + '</td><td>' + esc(e.action) + '</td><td class="small muted">' + esc(e.detail) + '</td></tr>').join('') + '</tbody></table></div>';
    view.innerHTML = h;
    $('#hq').addEventListener('input', () => {
      const t = $('#hq').value.toLowerCase();
      $$('#hb tr').forEach((tr) => { tr.classList.toggle('hidden', t && !tr.textContent.toLowerCase().includes(t)); });
    });
  }

  async function pageUsers() {
    folio('Logins');
    const list = await api('GET', '/api/users');
    let h = head('Logins', 'Clerks upload and correct bills. Accountants also approve, reject and export. The admin also manages logins and settings.',
      '<button type="button" class="btn primary" id="addUser">Add a login</button>');
    h += '<div class="sheet table-wrap"><table class="ledger"><thead><tr><th>Name</th><th>Username</th><th>Role</th><th>Status</th><th></th></tr></thead><tbody>'
      + list.map((u) => '<tr><td class="vendor">' + esc(u.name) + '</td><td class="mono">' + esc(u.username) + '</td><td>' + esc(u.roleLabel) + '</td>'
        + '<td>' + (u.active ? '<span class="pill ok">active</span>' : '<span class="pill">turned off</span>') + '</td>'
        + '<td class="num"><button type="button" class="btn sm" data-edit="' + esc(u.id) + '">Edit</button> <button type="button" class="btn sm" data-act="' + esc(u.id) + '">' + (u.active ? 'Turn off' : 'Turn on') + '</button></td></tr>').join('')
      + '</tbody></table></div>';
    view.innerHTML = h;
    const form = (u) => modal(u ? 'Edit login' : 'Add a login',
      '<label class="field">Full name<input name="name" value="' + esc(u ? u.name : '') + '"></label>'
      + '<label class="field">Username<input name="username" value="' + esc(u ? u.username : '') + '" autocomplete="off"></label>'
      + '<label class="field">Role<select name="role">' + [['CLERK', 'Clerk'], ['ACCOUNTANT', 'Accountant'], ['ADMIN', 'Admin']].map(([k, l]) => '<option value="' + k + '"' + (u && u.role === k ? ' selected' : '') + '>' + l + '</option>').join('') + '</select></label>'
      + '<label class="field">' + (u ? 'New password (leave empty to keep)' : 'Password (8+ characters, a letter and a number)') + '<input name="password" type="password" autocomplete="new-password"></label>',
      [{ label: 'Cancel' }, { label: 'Save', cls: 'primary', action: async (f) => {
        const body = { name: f.name.value, username: f.username.value, role: f.role.value, password: f.password.value };
        if (u) await api('PUT', '/api/users/' + u.id, body); else await api('POST', '/api/users', body);
        toast('Saved.');
        route();
      } }]);
    $('#addUser').addEventListener('click', () => form(null));
    $$('[data-edit]').forEach((b) => b.addEventListener('click', () => form(list.find((u) => u.id === b.dataset.edit))));
    $$('[data-act]').forEach((b) => b.addEventListener('click', async () => {
      const u = list.find((x) => x.id === b.dataset.act);
      try { await api('POST', '/api/users/' + u.id + '/active', { active: !u.active }); route(); } catch (e) { toast(e.message, true); }
    }));
  }

  async function pageSettings() {
    folio('Settings');
    const r = await api('GET', '/api/settings');
    const s = r.settings;
    let h = head('Settings', 'Your company details are used to tell your GSTIN from the vendor’s, and to check CGST/SGST against IGST.');
    h += '<form class="sheet" id="sf"><div class="grid2">'
      + '<label class="field">Company name (the buyer on bills)<input name="companyName" value="' + esc(s.companyName) + '"></label>'
      + '<label class="field">Our GSTIN<input name="companyGstin" class="mono" value="' + esc(s.companyGstin) + '"></label>'
      + '<label class="field">City<input name="companyCity" value="' + esc(s.companyCity) + '"></label><span></span></div>'
      + '<p class="section-label">Auto-check</p>'
      + '<label class="check-row"><input type="checkbox" name="autoApprove"' + (s.autoApprove ? ' checked' : '') + '> Approve bills automatically when the vendor is known, every check passes and Bahi is sure enough</label>'
      + '<div class="grid2"><label class="field">Minimum confidence (%)<input name="autoApproveConfidence" type="number" min="50" max="99" value="' + Math.round(s.autoApproveConfidence * 100) + '"></label>'
      + '<label class="field">Only for bills up to (₹)<input name="autoApproveMaxTotal" type="number" min="0" step="100" value="' + s.autoApproveMaxTotal + '"></label>'
      + '<label class="field">Rounding allowed in sums (₹)<input name="amountTolerance" type="number" min="0" max="100" step="0.5" value="' + s.amountTolerance + '"></label>'
      + '<label class="field">Same vendor + same total within (days) = possible duplicate<input name="duplicateDays" type="number" min="1" max="365" value="' + s.duplicateDays + '"></label></div>'
      + '<p class="section-label">Claude (optional)</p>'
      + '<p class="small muted">' + (r.claudeKey ? 'An ANTHROPIC_API_KEY was found when the app started.' : 'No ANTHROPIC_API_KEY was found. Set it before starting the app (see the README) to read photos and scans with Claude.') + '</p>'
      + '<label class="check-row"><input type="checkbox" name="useClaude"' + (s.useClaude ? ' checked' : '') + (r.claudeKey ? '' : ' disabled') + '> Offer Claude on the Scan Desk</label>'
      + '<label class="field">Claude model<input name="claudeModel" class="mono" value="' + esc(s.claudeModel) + '"></label>'
      + '<p class="err-text" id="sErr"></p><button class="btn primary" type="submit">Save settings</button></form>';
    view.innerHTML = h;
    $('#sf').addEventListener('submit', async (e) => {
      e.preventDefault();
      const f = e.target;
      try {
        await api('PUT', '/api/settings', {
          companyName: f.companyName.value, companyGstin: f.companyGstin.value, companyCity: f.companyCity.value,
          autoApprove: f.autoApprove.checked, autoApproveConfidence: Number(f.autoApproveConfidence.value) / 100,
          autoApproveMaxTotal: Number(f.autoApproveMaxTotal.value), amountTolerance: Number(f.amountTolerance.value),
          duplicateDays: Number(f.duplicateDays.value), useClaude: f.useClaude.checked, claudeModel: f.claudeModel.value,
        });
        $('#sErr').textContent = '';
        toast('Settings saved.');
        await refreshMe();
        $('#coName').textContent = state.me.company;
      } catch (err) { $('#sErr').textContent = err.message; }
    });
  }

  // ------------------------------------------------------------ go
  start().catch(() => showLogin());
})();
