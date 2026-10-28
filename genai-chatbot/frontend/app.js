/* GenAI Chatbot - frontend.
   Plain JavaScript, no frameworks. Every piece of data is escaped with esc() before it goes into HTML,
   and no inline styles or scripts are used (the server's Content-Security-Policy blocks them).
   Answers are streamed: fetch() reads the server's Server-Sent Events piece by piece. */
'use strict';

const S = {
  token: null, user: null, meta: null, users: [],
  chats: [], chat: null, chatQuery: '', draftPersona: 'general', streaming: null,
  timer: null, routeSeq: 0
};
const $ = (sel, root = document) => root.querySelector(sel);
const $$ = (sel, root = document) => Array.from(root.querySelectorAll(sel));
const view = () => $('#view');
const isAdmin = () => S.user && S.user.role === 'ADMIN';
const canTrain = () => S.user && (S.user.role === 'ADMIN' || S.user.role === 'TRAINER');

// ------------------------------------------------------------------ helpers
const store = {
  get(k) { try { return sessionStorage.getItem(k); } catch { return null; } },
  set(k, v) { try { v == null ? sessionStorage.removeItem(k) : sessionStorage.setItem(k, v); } catch { /* ignore */ } },
  getLocal(k) { try { return localStorage.getItem(k); } catch { return null; } },
  setLocal(k, v) { try { localStorage.setItem(k, v); } catch { /* ignore */ } }
};

function esc(v) {
  return String(v ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}
function num(n, d = 0) {
  if (n == null || isNaN(n)) return '-';
  return Number(n).toLocaleString('en-IN', { maximumFractionDigits: d });
}
const pct = (a, b) => b > 0 ? Math.round(a / b * 100) : 0;
const secs = ms => ms == null ? '' : (ms < 1000 ? ms + ' ms' : num(ms / 1000, 1) + ' s');

function when(iso) {
  if (!iso) return '';
  const d = new Date(iso);
  const today = new Date();
  const same = d.toDateString() === today.toDateString();
  return same ? d.toLocaleTimeString('en-IN', { hour: '2-digit', minute: '2-digit' })
    : d.toLocaleString('en-IN', { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' });
}
function dayLabel(iso) {
  return new Date(iso + 'T00:00:00').toLocaleDateString('en-IN', { day: 'numeric', month: 'short' });
}
function ago(iso) {
  if (!iso) return '';
  const m = Math.round((Date.now() - new Date(iso).getTime()) / 60000);
  if (m < 1) return 'just now';
  if (m < 60) return m + ' min ago';
  if (m < 1440) return Math.round(m / 60) + ' h ago';
  const d = Math.round(m / 1440);
  return d < 8 ? d + ' d ago' : new Date(iso).toLocaleDateString('en-IN', { day: 'numeric', month: 'short' });
}

function toast(msg, err = false) {
  const t = document.createElement('div');
  t.className = 'toast' + (err ? ' err' : '');
  t.setAttribute('role', 'status');
  t.textContent = msg;
  document.body.appendChild(t);
  setTimeout(() => t.remove(), err ? 5000 : 2600);
}

/** Thrown when an answer arrives for a page the person has already left (it is then ignored). */
class StaleError extends Error {}

async function api(method, path, body) {
  const seq = S.routeSeq;
  const opt = { method, headers: {} };
  if (S.token) opt.headers.Authorization = 'Bearer ' + S.token;
  if (body !== undefined) { opt.headers['Content-Type'] = 'application/json'; opt.body = JSON.stringify(body); }
  const res = await fetch(path, opt);
  if (res.status === 401 && path !== '/api/auth/login') { signOut(true); throw new Error('Your session ended. Please sign in again.'); }
  const ct = res.headers.get('content-type') || '';
  const data = ct.includes('json') ? await res.json() : await res.text();
  if (!res.ok) throw new Error((data && data.error) || 'Request failed (' + res.status + ')');
  if (method === 'GET' && seq !== S.routeSeq) throw new StaleError('page changed');
  return data;
}

async function run(btn, fn) {
  if (btn) btn.disabled = true;
  try { return await fn(); } catch (e) { if (!(e instanceof StaleError)) toast(e.message, true); } finally { if (btn) btn.disabled = false; }
}

function options(list, selected, blank) {
  let h = blank != null ? `<option value="">${esc(blank)}</option>` : '';
  for (const o of list) {
    const id = typeof o === 'object' ? o.id : o;
    const lb = typeof o === 'object' ? (o.label ?? o.name) : o;
    h += `<option value="${esc(id)}" ${String(id) === String(selected ?? '') ? 'selected' : ''}>${esc(lb)}</option>`;
  }
  return h;
}

/** Sets bar sizes from data-w (width %) and data-h (height %) after HTML is placed. */
function paintBars(root = document) {
  requestAnimationFrame(() => {
    $$('[data-w]', root).forEach(e => { e.style.width = clamp(e.dataset.w) + '%'; });
    $$('[data-h]', root).forEach(e => { e.style.height = clamp(e.dataset.h) + '%'; });
  });
}
const clamp = v => Math.max(0, Math.min(100, Number(v) || 0));
function bar(value, max, cls = '') {
  return `<div class="bar ${cls}" role="img" aria-label="${pct(value, max)}%"><span data-w="${max > 0 ? value / max * 100 : 0}"></span></div>`;
}
function hbars(items, cls = '') {
  if (!items.length) return '<div class="empty">No data yet.</div>';
  const max = Math.max(0.0001, ...items.map(i => i.value));
  return '<div class="hbars">' + items.map(i => `
    <div class="hbar">
      <div class="top"><span>${esc(i.label)}</span><span class="num muted">${esc(i.text)}</span></div>
      ${bar(i.value, max, cls)}
    </div>`).join('') + '</div>';
}
/** Simple vertical bar chart. cols: [{label, v, title}] */
function vchart(cols, aria) {
  const max = Math.max(0.0001, ...cols.map(c => c.v)) * 1.1;
  const every = cols.length > 10 ? 2 : 1;
  return `<div class="vchart" role="img" aria-label="${esc(aria)}">${cols.map(c => `
      <div class="col" title="${esc(c.title)}"><div class="val">${c.v ? esc(num(c.v)) : ''}</div>
        <div class="stack-bars" data-h="${c.v / max * 100}"><span class="seg-single" data-h="100"></span></div></div>`).join('')}</div>
    <div class="vlabels">${cols.map((c, i) => `<span class="${i % every === 0 ? '' : 'blank'}">${esc(c.label)}</span>`).join('')}</div>`;
}

/** Downloads a file from an API address that needs the sign-in token. */
async function downloadFile(path, fallbackName) {
  const res = await fetch(path, { headers: { Authorization: 'Bearer ' + S.token } });
  if (!res.ok) throw new Error('Download failed (' + res.status + ')');
  const blob = await res.blob();
  const cd = res.headers.get('content-disposition') || '';
  const m = /filename="([^"]+)"/.exec(cd);
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = m ? m[1] : fallbackName;
  document.body.appendChild(a); a.click(); a.remove();
  setTimeout(() => URL.revokeObjectURL(a.href), 2000);
}

async function copyText(text, btn) {
  try {
    await navigator.clipboard.writeText(text);
  } catch {
    const ta = document.createElement('textarea'); ta.value = text; document.body.appendChild(ta); ta.select();
    try { document.execCommand('copy'); } catch { /* ignore */ } ta.remove();
  }
  if (btn) { const old = btn.innerHTML; btn.textContent = 'Copied'; setTimeout(() => { btn.innerHTML = old; }, 1200); } else toast('Copied');
}

const ICON = {
  copy: '<svg viewBox="0 0 24 24"><rect x="9" y="9" width="11" height="11" rx="2"/><path d="M5 15V5a2 2 0 0 1 2-2h10"/></svg>',
  up: '<svg viewBox="0 0 24 24"><path d="M7 10v11H4V10zM7 10l4-7a2 2 0 0 1 3 2l-1 5h6a2 2 0 0 1 2 2.3l-1.4 7A2 2 0 0 1 17.6 21H7"/></svg>',
  down: '<svg viewBox="0 0 24 24"><path d="M17 14V3h3v11zM17 14l-4 7a2 2 0 0 1-3-2l1-5H5a2 2 0 0 1-2-2.3l1.4-7A2 2 0 0 1 6.4 3H17"/></svg>',
  again: '<svg viewBox="0 0 24 24"><path d="M3 12a9 9 0 0 1 15.5-6.2L21 8M21 3v5h-5M21 12a9 9 0 0 1-15.5 6.2L3 16M3 21v-5h5"/></svg>',
  edit: '<svg viewBox="0 0 24 24"><path d="M12 20h9M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4z"/></svg>'
};

// ------------------------------------------------------------------ markdown (safe)
/**
 * Turns the AI's Markdown into HTML. Text is escaped FIRST, so nothing the model writes can become a script.
 * Supports: ```code blocks```, `code`, **bold**, *italic*, headings, lists, quotes, tables, links (http/https only)
 * and citations [1] that open the knowledge-base source.
 */
function md(src, nSources = 0) {
  const out = [];
  const re = /```([^\n`]*)\n?([\s\S]*?)(?:```|$)/g;
  let last = 0, m;
  while ((m = re.exec(src)) !== null) {
    if (m.index > last) out.push(mdBlocks(src.slice(last, m.index), nSources));
    out.push(`<div class="code"><div class="code-head"><span>${esc(m[1].trim() || 'code')}</span><button type="button" data-copy-code>Copy</button></div><pre><code>${esc(m[2].replace(/\n$/, ''))}</code></pre></div>`);
    last = re.lastIndex;
    if (last >= src.length) break;
  }
  if (last < src.length) out.push(mdBlocks(src.slice(last), nSources));
  return out.join('');
}

function mdBlocks(text, n) {
  const lines = text.split('\n');
  const out = [];
  let para = [];
  const flush = () => { if (para.length) { out.push('<p>' + para.map(l => mdInline(l, n)).join('<br>') + '</p>'); para = []; } };
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    let mm;
    if (!line.trim()) { flush(); continue; }
    if ((mm = /^(#{1,6})\s+(.*)$/.exec(line))) { flush(); const lv = Math.min(mm[1].length + 1, 4); out.push(`<h${lv}>${mdInline(mm[2], n)}</h${lv}>`); continue; }
    if (/^\s*(-{3,}|\*{3,}|_{3,})\s*$/.test(line)) { flush(); out.push('<hr>'); continue; }
    if (/^\s*\|.*\|\s*$/.test(line) && i + 1 < lines.length && /^\s*\|?\s*:?-{2,}/.test(lines[i + 1])) {
      flush();
      const cells = l => l.trim().replace(/^\||\|$/g, '').split('|').map(c => c.trim());
      const head = cells(line);
      i += 2;
      const rows = [];
      while (i < lines.length && /^\s*\|.*\|\s*$/.test(lines[i])) { rows.push(cells(lines[i])); i++; }
      i--;
      out.push(`<div class="table-wrap"><table><thead><tr>${head.map(h => `<th>${mdInline(h, n)}</th>`).join('')}</tr></thead><tbody>${
        rows.map(r => `<tr>${r.map(c => `<td>${mdInline(c, n)}</td>`).join('')}</tr>`).join('')}</tbody></table></div>`);
      continue;
    }
    if (/^\s*>/.test(line)) {
      flush();
      const q = [];
      while (i < lines.length && /^\s*>/.test(lines[i])) { q.push(lines[i].replace(/^\s*>\s?/, '')); i++; }
      i--;
      out.push('<blockquote>' + q.map(l => mdInline(l, n)).join('<br>') + '</blockquote>');
      continue;
    }
    const ul = /^\s*[-*+]\s+/, ol = /^\s*\d+[.)]\s+/;
    if (ul.test(line) || ol.test(line)) {
      flush();
      const isOl = ol.test(line) && !ul.test(line);
      const rx = isOl ? ol : ul;
      const items = [];
      while (i < lines.length && rx.test(lines[i])) { items.push(lines[i].replace(rx, '')); i++; }
      i--;
      const tag = isOl ? 'ol' : 'ul';
      out.push(`<${tag}>${items.map(it => `<li>${mdInline(it, n)}</li>`).join('')}</${tag}>`);
      continue;
    }
    para.push(line);
  }
  flush();
  return out.join('');
}

function mdInline(s, n) {
  let t = esc(s);
  const codes = [];
  t = t.replace(/`([^`]+)`/g, (_, c) => { codes.push(c); return '\u0000' + (codes.length - 1) + '\u0000'; });
  t = t.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');
  t = t.replace(/(^|[^*\w])\*([^*\s][^*]*?)\*(?!\w)/g, '$1<em>$2</em>');
  t = t.replace(/\[([^\]]+)\]\((https?:\/\/[^\s)]+)\)/g, (_, label, url) => `<a href="${url}" target="_blank" rel="noopener noreferrer">${label}</a>`);
  t = t.replace(/\[(\d{1,2})\]/g, (all, d) => (n && +d >= 1 && +d <= n) ? `<button type="button" class="cite" data-cite="${d}" title="Show source ${d}">${d}</button>` : all);
  t = t.replace(/\u0000(\d+)\u0000/g, (_, i) => `<code>${codes[+i]}</code>`);
  return t;
}

// ------------------------------------------------------------------ modal
const modal = {
  el: $('#modal'),
  submit: null,
  open({ title, body, submitText = 'Save', danger = false, onSubmit, onOpen, wide = false }) {
    $('#modalTitle').textContent = title;
    $('#modalBody').innerHTML = body;
    $('#modalErr').textContent = '';
    $('#modalFoot').innerHTML = `<button type="button" id="modalCancel">${onSubmit ? 'Cancel' : 'Close'}</button>` +
      (onSubmit ? `<button type="submit" class="${danger ? 'danger' : 'primary'}" id="modalOk">${esc(submitText)}</button>` : '');
    $('#modalCancel').addEventListener('click', () => modal.close());
    this.submit = onSubmit;
    this.el.classList.toggle('wide', wide);
    if (!this.el.open) this.el.showModal();
    if (onOpen) onOpen($('#modalBody'));
    const first = $('#modalBody input:not([type=hidden]):not([disabled]):not([type=range]), #modalBody select, #modalBody textarea');
    if (first) first.focus();
  },
  close() { if (this.el.open) this.el.close(); this.submit = null; }
};
$('#modalClose').addEventListener('click', () => modal.close());
$('#modalForm').addEventListener('submit', async e => {
  e.preventDefault();
  if (!modal.submit) { modal.close(); return; }
  const btn = $('#modalOk');
  btn.disabled = true;
  $('#modalErr').textContent = '';
  try {
    const data = Object.fromEntries(new FormData($('#modalForm')).entries());
    $$('#modalForm input[type=checkbox]').forEach(c => { data[c.name] = c.checked; });
    await modal.submit(data);
    modal.close();
  } catch (err) {
    $('#modalErr').textContent = err.message;
  } finally { btn.disabled = false; }
});

function confirmBox(title, text, okText, fn) {
  modal.open({ title, body: `<p>${esc(text)}</p>`, submitText: okText, danger: true, onSubmit: fn });
}

/** Range slider + number shown next to it. */
function wireRanges(root) {
  $$('input[type=range]', root).forEach(r => {
    const out = r.parentElement.querySelector('output');
    const show = () => { if (out) out.textContent = r.value === '' ? '-' : Number(r.value).toFixed(2); };
    r.addEventListener('input', show); show();
  });
}

// ------------------------------------------------------------------ light / dark mode
function isDark() {
  const t = document.documentElement.getAttribute('data-theme');
  return t ? t === 'dark' : matchMedia('(prefers-color-scheme: dark)').matches;
}
function showThemeButtons() {
  const dark = isDark();
  $$('[data-theme-toggle]').forEach(b => {
    b.textContent = dark ? 'Light mode' : 'Dark mode';
    b.setAttribute('aria-pressed', String(dark));
    b.title = dark ? 'Switch to light mode' : 'Switch to dark mode';
  });
}
$$('[data-theme-toggle]').forEach(b => b.addEventListener('click', () => {
  const next = isDark() ? 'light' : 'dark';
  document.documentElement.setAttribute('data-theme', next);
  store.setLocal('theme', next);
  showThemeButtons();
}));
// follow the computer's setting while the person has not chosen one
matchMedia('(prefers-color-scheme: dark)').addEventListener('change', showThemeButtons);
showThemeButtons();

// ------------------------------------------------------------------ auth
$('#loginForm').addEventListener('submit', async e => {
  e.preventDefault();
  $('#loginErr').textContent = '';
  const btn = e.submitter || $('#loginForm button[type=submit]');
  btn.disabled = true;
  try {
    const r = await api('POST', '/api/auth/login', { username: $('#lu').value.trim(), password: $('#lp').value });
    S.token = r.token; S.user = r.user; store.set('token', r.token);
    $('#lp').value = '';
    await boot();
  } catch (err) { $('#loginErr').textContent = err.message; } finally { btn.disabled = false; }
});
$$('[data-demo]').forEach(b => b.addEventListener('click', () => {
  const [u, p] = b.dataset.demo.split(':');
  $('#lu').value = u; $('#lp').value = p;
  $('#loginForm').requestSubmit();
}));
$('#logoutBtn').addEventListener('click', async () => {
  try { await api('POST', '/api/auth/logout', {}); } catch { /* ignore */ }
  signOut();
});

function signOut(expired) {
  if (S.streaming) S.streaming.ctrl.abort();
  S.token = null; S.user = null; S.chat = null; S.chats = []; store.set('token', null);
  clearTimer();
  modal.close();
  $('#app').classList.add('hidden'); $('#login').classList.remove('hidden');
  $('#loginErr').textContent = expired ? 'Your session ended. Please sign in again.' : '';
}

async function loadMeta() {
  S.meta = await api('GET', '/api/meta');
  $('#botName').textContent = 'Bot: ' + S.meta.botName;
  modelChip();
}

function modelChip() {
  const p = defaultProvider();
  const c = $('#modelChip');
  if (!p) { c.textContent = ''; return; }
  c.innerHTML = `<span class="dot ${p.ready ? 'ready' : 'off'}"></span>${esc(p.name)}`;
  c.title = 'Default AI model: ' + p.name + ' (' + p.model + ') - ' + p.status;
}
const provider = id => S.meta.providers.find(p => p.id === id);
const defaultProvider = () => provider(S.meta.defaultProviderId) || S.meta.providers[0];
const persona = id => S.meta.personas.find(p => p.id === id) || S.meta.personas.find(p => p.id === 'general') || S.meta.personas[0];
const initials = name => esc((name || '?').split(/\s+/).filter(w => /^[\p{L}\p{N}]/u.test(w)).map(w => w[0]).join('').slice(0, 2).toUpperCase() || '?');
const avatar = (p, small) => `<span class="p-avatar ${small ? 'small' : ''} pc-${esc(p ? p.color : 'teal')}" aria-hidden="true">${initials(p ? p.name : '?')}</span>`;

async function boot() {
  S.token = S.token || store.get('token');
  if (!S.token) { signOut(); return; }
  try {
    S.user = await api('GET', '/api/auth/me');
    await loadMeta();
  } catch { signOut(); return; }
  $('#login').classList.add('hidden'); $('#app').classList.remove('hidden');
  $('#who').textContent = S.user.displayName + ' · ' + S.user.roleLabel;
  $$('.admin-only').forEach(a => a.classList.toggle('hidden', !isAdmin()));
  if (!location.hash || location.hash === '#/') location.hash = '#/chat';
  route();
}

$('#who').addEventListener('click', () => {
  modal.open({
    title: 'Change your password',
    body: `<div class="field"><label for="pc">Current password</label><input id="pc" name="current" type="password" autocomplete="current-password" required></div>
      <div class="field"><label for="pn">New password</label><input id="pn" name="next" type="password" autocomplete="new-password" minlength="8" required>
      <div class="hint">At least 8 characters. You stay signed in here; other devices are signed out.</div></div>`,
    submitText: 'Change password',
    onSubmit: async d => { await api('POST', '/api/auth/password', d); toast('Password changed'); }
  });
});

// ------------------------------------------------------------------ router
window.addEventListener('hashchange', route);
function closeMenu() { $('#side').classList.remove('open'); $('#sideShade').classList.add('hidden'); }
$('#menuBtn').addEventListener('click', () => {
  const open = !$('#side').classList.contains('open');
  $('#side').classList.toggle('open', open); $('#sideShade').classList.toggle('hidden', !open);
});
$('#sideShade').addEventListener('click', closeMenu);
$('#searchForm').addEventListener('submit', e => {
  e.preventDefault();
  S.chatQuery = $('#globalSearch').value.trim();
  if (location.hash.startsWith('#/chat')) route(); else location.hash = '#/chat';
});
$('#newChatBtn').addEventListener('click', () => newChat());

function clearTimer() { if (S.timer) { clearInterval(S.timer); S.timer = null; } }

async function route() {
  if (!S.user) return;
  S.routeSeq++;
  closeMenu();
  clearTimer();
  const parts = location.hash.replace(/^#\/?/, '').split('/').map(decodeURIComponent);
  const page = parts[0] || 'chat';
  $$('#nav a').forEach(a => a.classList.toggle('active', a.dataset.nav === page));
  const pages = {
    chat: () => pageChat(parts[1]), assistants: pageAssistants, knowledge: pageKnowledge, dashboard: pageDashboard,
    models: pageModels, settings: pageSettings, team: pageTeam
  };
  const fn = pages[page];
  $('#app').classList.toggle('chat-mode', page === 'chat');
  view().className = 'page';
  view().innerHTML = '<div class="empty">Loading...</div>';
  if (!fn) { view().innerHTML = '<div class="empty">Page not found. <a href="#/chat">Go to chat</a></div>'; return; }
  try { await fn(); } catch (e) { if (e instanceof StaleError) return; view().innerHTML = `<div class="card card-pad"><p class="bad-text">${esc(e.message)}</p><a href="#/chat">Back to chat</a></div>`; }
}

// ================================================================== chat
const TYPING = '<span class="typing" aria-label="Thinking"><i></i><i></i><i></i></span>';

async function newChat() {
  if (location.hash === '#/chat') { S.chat = null; if (view().classList.contains('chat-page')) renderConvo(); else route(); }
  else location.hash = '#/chat';
  closeMenu();
}

async function pageChat(id) {
  view().className = 'page chat-page';
  S.chats = await api('GET', '/api/chats' + (S.chatQuery ? '?q=' + encodeURIComponent(S.chatQuery) : ''));
  if (id) {
    if (S.streaming && S.streaming.chat.id === id) S.chat = S.streaming.chat;
    else {
      try { S.chat = await api('GET', '/api/chats/' + encodeURIComponent(id)); } catch (e) {
        if (e instanceof StaleError) throw e;
        toast(e.message, true); location.hash = '#/chat'; return;
      }
    }
  } else S.chat = null;
  view().innerHTML = '<aside class="chat-list" id="chatList" aria-label="Your chats"></aside><section class="convo" id="convo"></section>';
  renderChatList();
  renderConvo();
  wireConvo();
}

function renderChatList() {
  const el = $('#chatList'); if (!el) return;
  const items = S.chats.map(c => {
    const p = persona(c.personaId);
    return `<a class="chat-item ${S.chat && S.chat.id === c.id ? 'active' : ''}" href="#/chat/${esc(c.id)}">
      ${avatar(p, true)}<div class="grow"><div class="ct">${c.pinned ? '<span class="pin" title="Pinned">★</span>' : ''}<span>${esc(c.title)}</span></div>
      <div class="cp">${esc(ago(c.updatedAt))}${c.busy ? ' · answering…' : ''} · ${esc(c.preview || 'No messages yet')}</div></div></a>`;
  }).join('');
  el.innerHTML = `
    <div class="chat-list-head">
      <input type="search" id="chatSearch" placeholder="Search chats" aria-label="Search chats" value="${esc(S.chatQuery)}">
    </div>
    <div class="chat-items">${items || `<div class="empty">${S.chatQuery ? 'No chats match "' + esc(S.chatQuery) + '".' : 'No chats yet. Say hello!'}</div>`}</div>
    <div class="chat-list-foot"><span>${S.chats.length} chat${S.chats.length === 1 ? '' : 's'}</span>
      ${S.chats.some(c => !c.pinned) && !S.chatQuery ? '<button type="button" class="link" id="clearChats">Delete unpinned</button>' : ''}</div>`;
  let timer;
  $('#chatSearch').addEventListener('input', e => {
    clearTimeout(timer);
    timer = setTimeout(async () => {
      S.chatQuery = e.target.value.trim();
      $('#globalSearch').value = S.chatQuery;
      try { S.chats = await api('GET', '/api/chats' + (S.chatQuery ? '?q=' + encodeURIComponent(S.chatQuery) : '')); } catch { return; }
      renderChatList();
      const s = $('#chatSearch'); s.focus(); s.setSelectionRange(s.value.length, s.value.length);
    }, 250);
  });
  const clr = $('#clearChats');
  if (clr) clr.addEventListener('click', () => confirmBox('Delete chats?', 'Delete all your chats that are not pinned? This cannot be undone.', 'Delete', async () => {
    const r = await api('DELETE', '/api/chats');
    toast(r.deleted + ' chat(s) deleted');
    location.hash = '#/chat'; route();
  }));
  $$('.chat-item', el).forEach(a => a.addEventListener('click', () => view().classList.remove('show-list')));
}

async function reloadChatList() {
  try { S.chats = await api('GET', '/api/chats' + (S.chatQuery ? '?q=' + encodeURIComponent(S.chatQuery) : '')); } catch { return; }
  renderChatList();
}

function providerLine(c) {
  const pid = (c && c.providerId) || S.meta.defaultProviderId;
  const p = provider(pid) || defaultProvider();
  const model = (c && c.model) || p.model;
  return { p, model };
}

function renderConvo() {
  const el = $('#convo'); if (!el) return;
  const c = S.chat;
  if (!c) { renderWelcome(el); return; }
  const p = persona(c.personaId);
  const { p: prov, model } = providerLine(c);
  el.innerHTML = `
    <div class="convo-head">
      <button type="button" class="small list-toggle" id="showList">Chats</button>
      ${avatar(p)}
      <div class="grow">
        <button type="button" class="title-btn" id="renameBtn" title="Rename"><h2>${esc(c.title)}</h2></button>
        <div class="sub">${esc(p.name)} · <span class="dot ${prov.ready ? 'ready' : 'off'}"></span> ${esc(prov.name)} / ${esc(model)}${c.temperature != null ? ' · creativity ' + esc(c.temperature) : ''} · knowledge base ${c.useKnowledge ? 'on' : 'off'}</div>
      </div>
      <div class="row">
        <button type="button" class="small" id="settingsBtn">Chat settings</button>
        <button type="button" class="small" id="pinBtn">${c.pinned ? 'Unpin' : 'Pin'}</button>
        <button type="button" class="small" id="exportBtn">Export</button>
        <button type="button" class="small danger" id="delBtn">Delete</button>
      </div>
    </div>
    <div class="messages" id="messages"><div class="messages-inner" id="msgInner"></div></div>
    ${composerHtml(c)}`;
  renderMessages(true);
  wireComposer();
  $('#showList').addEventListener('click', () => view().classList.toggle('show-list'));
  $('#renameBtn').addEventListener('click', () => modal.open({
    title: 'Rename chat',
    body: `<div class="field"><label for="ct">Title</label><input id="ct" name="title" maxlength="80" required value="${esc(c.title)}"></div>`,
    onSubmit: async d => { const r = await api('PUT', '/api/chats/' + c.id, { title: d.title }); c.title = r.title; renderConvo(); reloadChatList(); }
  }));
  $('#settingsBtn').addEventListener('click', () => chatSettings(c));
  $('#pinBtn').addEventListener('click', e => run(e.currentTarget, async () => {
    const r = await api('PUT', '/api/chats/' + c.id, { pinned: !c.pinned }); c.pinned = r.pinned; renderConvo(); reloadChatList();
  }));
  $('#exportBtn').addEventListener('click', () => modal.open({
    title: 'Export this chat',
    body: `<p>Download the whole conversation.</p><div class="row">
      <button type="button" class="primary" id="exMd">Markdown (.md)</button><button type="button" id="exJson">JSON (.json)</button></div>
      <p class="hint">Markdown opens in any text editor, VS Code or GitHub. JSON keeps every detail (tokens, model, sources).</p>`,
    onOpen: b => {
      $('#exMd', b).addEventListener('click', e => run(e.currentTarget, () => downloadFile('/api/chats/' + c.id + '/export.md', 'chat.md')));
      $('#exJson', b).addEventListener('click', e => run(e.currentTarget, () => downloadFile('/api/chats/' + c.id + '/export.json', 'chat.json')));
    }
  }));
  $('#delBtn').addEventListener('click', () => confirmBox('Delete this chat?', '"' + c.title + '" and all its messages will be deleted.', 'Delete', async () => {
    await api('DELETE', '/api/chats/' + c.id);
    S.chat = null; toast('Chat deleted'); location.hash = '#/chat';
  }));
}

function composerHtml(c) {
  const { p, model } = providerLine(c);
  const kb = c ? c.useKnowledge : persona(S.draftPersona).useKnowledge;
  const busy = !!S.streaming;
  return `<div class="composer"><div class="composer-inner">
    <form class="composer-box" id="composer">
      <label for="msgInput" class="hidden">Message</label>
      <textarea id="msgInput" rows="1" maxlength="${S.meta.maxMessage}" placeholder="Message ${esc(S.meta.botName)}…"></textarea>
      <button type="submit" class="${busy ? 'stop-btn' : 'primary'}" id="sendBtn">${busy ? 'Stop' : 'Send'}</button>
    </form>
    <div class="composer-info">
      <span><span class="dot ${p.ready ? 'ready' : 'off'}"></span> ${esc(p.name)} · ${esc(model)}${p.ready ? '' : ' (not ready - demo will answer)'}</span>
      ${c ? '<button type="button" class="link" id="modelBtn">change model</button>' : ''}
      <span>Knowledge base: ${kb ? 'on' : 'off'}</span>
      <span>Enter to send · Shift+Enter for a new line</span>
      <span id="charCount"></span>
    </div></div></div>`;
}

function renderWelcome(el) {
  const p = persona(S.draftPersona);
  const active = S.meta.personas.filter(x => x.active);
  el.innerHTML = `
    <div class="convo-head"><button type="button" class="small list-toggle" id="showList">Chats</button><div class="grow"><h2>New chat</h2></div>
      <label for="draftProv" class="small muted">Model</label>
      <select id="draftProv" class="small-select">${options(S.meta.providers.filter(x => x.enabled).map(x => ({ id: x.id, label: x.name + (x.ready ? '' : ' (not ready)') })), S.draftProvider || S.meta.defaultProviderId)}</select>
    </div>
    <div class="messages"><div class="welcome">
      ${avatar(p)}
      <h1>${esc(p.name)}</h1>
      <p>${esc(p.description)}</p>
      <div class="persona-pick" role="group" aria-label="Choose an assistant">${active.map(x => `<button type="button" data-persona="${esc(x.id)}" class="${x.id === p.id ? 'on' : ''}">${esc(x.name)}</button>`).join('')}</div>
      <div class="starters">${p.starters.map(s => `<button type="button" class="starter" data-starter="${esc(s)}">${esc(s)}</button>`).join('')}</div>
    </div></div>
    ${composerHtml(null)}`;
  $('#showList').addEventListener('click', () => view().classList.toggle('show-list'));
  $$('[data-persona]', el).forEach(b => b.addEventListener('click', () => { S.draftPersona = b.dataset.persona; renderWelcome(el); }));
  $$('[data-starter]', el).forEach(b => b.addEventListener('click', () => send('new', b.dataset.starter)));
  $('#draftProv').addEventListener('change', e => { S.draftProvider = e.target.value; renderWelcome(el); });
  wireComposer();
}

function wireComposer() {
  const ta = $('#msgInput'), form = $('#composer');
  const grow = () => { ta.style.height = 'auto'; ta.style.height = Math.min(ta.scrollHeight, 224) + 'px'; };
  const count = () => {
    const n = ta.value.length;
    $('#charCount').textContent = n > S.meta.maxMessage * 0.8 ? n + ' / ' + S.meta.maxMessage : '';
  };
  ta.addEventListener('input', () => { grow(); count(); });
  ta.addEventListener('keydown', e => {
    if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) { e.preventDefault(); form.requestSubmit(); }
  });
  form.addEventListener('submit', e => {
    e.preventDefault();
    if (S.streaming) { stopAnswer(); return; }
    const text = ta.value.trim();
    if (!text) return;
    ta.value = ''; grow(); count();
    send('new', text);
  });
  const mb = $('#modelBtn');
  if (mb) mb.addEventListener('click', () => chatSettings(S.chat));
  if (!S.streaming && matchMedia('(min-width: 700px)').matches) ta.focus();
}

function setBusy(busy) {
  const b = $('#sendBtn');
  if (!b) return;
  b.textContent = busy ? 'Stop' : 'Send';
  b.className = busy ? 'stop-btn' : 'primary';
}

const nearBottom = () => { const m = $('#messages'); return !m || m.scrollHeight - m.scrollTop - m.clientHeight < 140; };
const toBottom = () => { const m = $('#messages'); if (m) m.scrollTop = m.scrollHeight; };

function renderMessages(scroll) {
  const box = $('#msgInner'); if (!box || !S.chat) return;
  const msgs = S.chat.messages;
  let lastUser = -1, lastBot = -1;
  msgs.forEach((m, i) => { if (m.role === 'user') lastUser = i; else lastBot = i; });
  box.innerHTML = msgs.length ? msgs.map((m, i) => msgHtml(m, i === lastUser, i === lastBot && i > lastUser)).join('')
    : `<div class="welcome"><p>Say something to start. ${esc(persona(S.chat.personaId).name)} is ready.</p></div>`;
  if (scroll) requestAnimationFrame(toBottom);
}

function flagBadges(m) {
  const map = {
    REDACTED: ['warn', 'Personal details hidden'], INJECTION: ['bad', 'Possible prompt injection'],
    FALLBACK: ['warn', 'Demo fallback'], STOPPED: ['', 'Stopped'], ERROR: ['bad', 'Error']
  };
  return (m.flags || []).map(f => map[f] ? `<span class="badge ${map[f][0]}">${esc(map[f][1])}</span>` : '').join('');
}

function msgHtml(m, isLastUser, isLastBot) {
  const streaming = !!m.streaming;
  if (m.role === 'user') {
    return `<div class="msg user" data-mid="${esc(m.id)}"><div class="body">
      <div class="bubble">${esc(m.content)}</div>
      ${m.note ? `<div class="note">${esc(m.note)}</div>` : ''}
      <div class="meta">${flagBadges(m)}<span>${esc(when(m.at))}${m.editedAt ? ' · edited' : ''}</span></div>
      <div class="actions">
        <button type="button" class="icon-btn" data-act="copy" title="Copy">${ICON.copy} Copy</button>
        ${isLastUser && !S.streaming ? `<button type="button" class="icon-btn" data-act="edit" title="Edit and send again">${ICON.edit} Edit</button>` : ''}
      </div></div></div>`;
  }
  const p = persona(S.chat.personaId);
  const n = (m.sources || []).length;
  const content = m.content ? md(m.content, n) : (streaming ? TYPING : '<p class="muted">(no answer)</p>');
  const sources = n ? `<div class="sources">${m.sources.map(s => `<button type="button" class="source-chip" data-src="${esc(s.n)}"><b>[${esc(s.n)}]</b>${esc(s.title)}</button>`).join('')}</div>` : '';
  const err = (m.flags || []).includes('ERROR');
  const meta = streaming ? `<span>${esc(m.providerName || '')}${m.model ? ' / ' + esc(m.model) : ''} · writing…</span>`
    : `${flagBadges(m)}<span title="Which AI wrote this">${esc(m.providerName || '')}${m.model ? ' / ' + esc(m.model) : ''}</span>
       <span title="Tokens read / written">${num(m.promptTokens)} → ${num(m.outputTokens)} tokens</span><span>${esc(secs(m.ms))}</span><span>${esc(when(m.at))}</span>`;
  const speaker = S.meta.botName + ' · ' + p.name;
  return `<div class="msg bot" data-mid="${esc(m.id)}" data-speaker="${esc(speaker)}">${avatar(p)}<div class="body">
    <div class="bubble md ${streaming && m.content ? 'cursor' : ''}">${content}</div>
    ${sources}
    ${m.note ? `<div class="note ${err ? 'err' : ''}">${esc(m.note)}</div>` : ''}
    <div class="meta">${meta}</div>
    ${streaming ? '' : `<div class="actions">
      <button type="button" class="icon-btn" data-act="copy" title="Copy">${ICON.copy} Copy</button>
      <button type="button" class="icon-btn ${m.feedback > 0 ? 'on' : ''}" data-act="up" title="Good answer" aria-pressed="${m.feedback > 0}">${ICON.up}</button>
      <button type="button" class="icon-btn ${m.feedback < 0 ? 'on-bad' : ''}" data-act="down" title="Bad answer" aria-pressed="${m.feedback < 0}">${ICON.down}</button>
      ${isLastBot && !S.streaming ? `<button type="button" class="icon-btn" data-act="again" title="Write the answer again">${ICON.again} Regenerate</button>` : ''}
    </div>`}
  </div></div>`;
}

/** One click handler for everything inside the conversation (buttons are re-drawn often). */
function wireConvo() {
  const el = $('#convo');
  el.addEventListener('click', e => {
    const codeBtn = e.target.closest('[data-copy-code]');
    if (codeBtn) { copyText(codeBtn.closest('.code').querySelector('code').textContent, codeBtn); return; }
    const cite = e.target.closest('[data-cite], [data-src]');
    if (cite) { showSource(cite.closest('[data-mid]').dataset.mid, Number(cite.dataset.cite || cite.dataset.src)); return; }
    const btn = e.target.closest('[data-act]');
    if (!btn || !S.chat) return;
    const id = btn.closest('[data-mid]').dataset.mid;
    const m = S.chat.messages.find(x => x.id === id);
    if (!m) return;
    const act = btn.dataset.act;
    if (act === 'copy') copyText(m.content, btn);
    else if (act === 'up') feedback(m, m.feedback > 0 ? 0 : 1, '');
    else if (act === 'down') {
      if (m.feedback < 0) { feedback(m, 0, ''); return; }
      modal.open({
        title: 'What was wrong with this answer?',
        body: `<div class="field"><label for="fn">Tell the AI trainer (optional)</label><textarea id="fn" name="note" maxlength="300" placeholder="Wrong facts, too long, did not answer the question…"></textarea></div>`,
        submitText: 'Send feedback',
        onSubmit: async d => { await feedback(m, -1, d.note); }
      });
    } else if (act === 'again') send('regenerate', '');
    else if (act === 'edit') editMessage(m);
  });
}

async function feedback(m, value, note) {
  const r = await api('POST', `/api/chats/${S.chat.id}/messages/${m.id}/feedback`, { value, note });
  m.feedback = r.feedback; m.feedbackNote = r.feedbackNote;
  renderMessages(false);
  if (value) toast(value > 0 ? 'Thanks for the feedback' : 'Feedback saved for the AI trainer');
}

function showSource(mid, n) {
  const m = S.chat && S.chat.messages.find(x => x.id === mid);
  const s = m && (m.sources || []).find(x => Number(x.n) === n);
  if (!s) return;
  modal.open({
    title: `[${n}] ${s.title}`,
    body: `<p class="hint">This part of the knowledge base was given to the AI with your question.</p><div class="doc-text">${esc(s.snippet)}</div>
      <p><a href="#/knowledge">Open the knowledge base</a></p>`
  });
}

function editMessage(m) {
  const el = $(`[data-mid="${CSS.escape(m.id)}"] .body`);
  el.innerHTML = `<div class="edit-box"><textarea id="editText" maxlength="${S.meta.maxMessage}">${esc(m.content)}</textarea>
    <div class="row"><div class="spacer"></div><button type="button" id="editCancel">Cancel</button><button type="button" class="primary" id="editSend">Save &amp; send</button></div></div>`;
  const ta = $('#editText'); ta.focus(); ta.setSelectionRange(ta.value.length, ta.value.length);
  $('#editCancel').addEventListener('click', () => renderMessages(false));
  $('#editSend').addEventListener('click', () => { const t = ta.value.trim(); if (t) send('edit', t); });
}

function chatSettings(c) {
  const provs = S.meta.providers.filter(p => p.enabled);
  const cur = c.providerId || '';
  modal.open({
    title: 'Chat settings',
    body: `
      <div class="field"><label for="csP">Assistant</label><select id="csP" name="personaId">${options(S.meta.personas.filter(p => p.active).map(p => ({ id: p.id, label: p.name })), c.personaId)}</select>
        <div class="hint">Changing it changes the bot's instructions from the next message on.</div></div>
      <div class="grid2">
        <div class="field"><label for="csV">AI provider</label><select id="csV" name="providerId">${options(provs.map(p => ({ id: p.id, label: p.name + (p.ready ? '' : ' (not ready)') })), cur, 'Default (' + defaultProvider().name + ')')}</select></div>
        <div class="field"><label for="csM">Model</label><input id="csM" name="model" list="csML" maxlength="100" value="${esc(c.model || '')}" placeholder="provider default" autocomplete="off"><datalist id="csML"></datalist></div>
      </div>
      <div class="field"><label class="check"><input type="checkbox" id="csTD" name="tempDefault" ${c.temperature == null ? 'checked' : ''}> Use the assistant's creativity (${esc(persona(c.personaId).temperature)})</label></div>
      <div class="field"><label for="csT">Creativity (temperature)</label>
        <div class="range-row"><input id="csT" name="temperature" type="range" min="0" max="1.5" step="0.05" value="${esc(c.temperature ?? persona(c.personaId).temperature)}"><output></output></div>
        <div class="hint">0 = focused and repeatable (facts, code). 0.7 = balanced. 1+ = more creative (stories, ideas).</div></div>
      <label class="check"><input type="checkbox" name="useKnowledge" ${c.useKnowledge ? 'checked' : ''}> Search the knowledge base for every question (RAG)</label>`,
    onOpen: b => {
      wireRanges(b);
      const fill = () => {
        const p = provider($('#csV', b).value) || defaultProvider();
        $('#csML', b).innerHTML = (p.models || []).map(m => `<option value="${esc(m)}">`).join('');
        $('#csM', b).placeholder = 'default: ' + p.model;
      };
      $('#csV', b).addEventListener('change', () => { $('#csM', b).value = ''; fill(); });
      fill();
      const td = $('#csTD', b), t = $('#csT', b);
      const lock = () => { t.disabled = td.checked; };
      td.addEventListener('change', lock); lock();
    },
    onSubmit: async d => {
      const body = { personaId: d.personaId, providerId: d.providerId, model: d.model.trim(), useKnowledge: d.useKnowledge,
        temperature: d.tempDefault ? null : Number($('#csT').value) };
      const r = await api('PUT', '/api/chats/' + c.id, body);
      Object.assign(c, r);
      renderConvo(); reloadChatList();
    }
  });
}

// ------------------------------------------------------------------ sending + streaming
/**
 * Sends a message and shows the answer while it is written.
 * The server answers with Server-Sent Events: start, many delta pieces, then done (or error).
 */
async function send(mode, text) {
  if (S.streaming) return;
  let c = S.chat;
  try {
    if (!c) {
      c = await api('POST', '/api/chats', { personaId: S.draftPersona, providerId: S.draftProvider || '' });
      S.chat = c;
      history.replaceState(null, '', '#/chat/' + c.id);
      renderConvo();
      reloadChatList();
    }
  } catch (e) { toast(e.message, true); return; }

  // show it straight away (the server's copy replaces it when it arrives)
  const msgs = c.messages;
  let lastUser = -1;
  msgs.forEach((m, i) => { if (m.role === 'user') lastUser = i; });
  if (mode === 'new') msgs.push({ id: '__user', role: 'user', content: text, at: new Date().toISOString(), flags: [] });
  else {
    if (lastUser < 0) return;
    msgs.splice(lastUser + 1);
    if (mode === 'edit') msgs[lastUser].content = text;
  }
  const bot = { id: '__bot', role: 'assistant', content: '', streaming: true, flags: [], sources: [] };
  msgs.push(bot);
  const ctrl = new AbortController();
  S.streaming = { chat: c, ctrl, bot };
  renderMessages(true);
  setBusy(true);

  let finished = false;
  try {
    const res = await fetch(`/api/chats/${encodeURIComponent(c.id)}/send`, {
      method: 'POST', signal: ctrl.signal,
      headers: { Authorization: 'Bearer ' + S.token, 'Content-Type': 'application/json' },
      body: JSON.stringify({ mode, text })
    });
    if (res.status === 401) { signOut(true); return; }
    if (!(res.headers.get('content-type') || '').includes('event-stream')) {
      const d = await res.json().catch(() => ({}));
      throw new Error(d.error || 'Request failed (' + res.status + ')');
    }
    await readEvents(res.body, (ev, data) => {
      if (ev === 'start') {
        const u = msgs.findIndex(m => m.id === '__user' || (mode !== 'new' && m.id === data.userMessage.id));
        if (u >= 0) msgs[u] = data.userMessage;
        bot.providerName = data.providerName; bot.model = data.model; bot.sources = data.sources || [];
        bot.note = (data.notes || []).join(' ') || null;
        if (data.chatTitle && data.chatTitle !== c.title) {
          c.title = data.chatTitle;
          const h = $('#renameBtn h2'); if (h && S.chat === c) h.textContent = c.title;
          if ($('#chatList')) reloadChatList();
        }
        renderMessages(nearBottom());
      } else if (ev === 'delta') {
        bot.content += data.t;
        paintStreaming();
      } else if (ev === 'done') {
        finished = true;
        const i = msgs.indexOf(bot);
        if (i >= 0) msgs[i] = data.message;
        if (data.chatTitle) c.title = data.chatTitle;
      } else if (ev === 'error') {
        finished = true;
        bot.streaming = false; bot.flags = ['ERROR']; bot.note = data.error;
      }
    });
  } catch (e) {
    if (e.name !== 'AbortError') {
      toast(e.message, true);
    }
  } finally {
    S.streaming = null;
    bot.streaming = false;
    if (!finished) {
      // stopped or cut off: the server saves what was written; fetch its copy
      await new Promise(r => setTimeout(r, 400));
      try { const fresh = await fetchChat(c.id); if (fresh) Object.assign(c, fresh); } catch { /* ignore */ }
    }
    if (S.chat === c && $('#convo')) { renderConvo(); }
    if ($('#chatList')) reloadChatList();
  }
}

/** GET a chat without the "page changed" check (used after streaming). */
async function fetchChat(id) {
  const res = await fetch('/api/chats/' + encodeURIComponent(id), { headers: { Authorization: 'Bearer ' + S.token } });
  return res.ok ? res.json() : null;
}

async function readEvents(body, onEvent) {
  const reader = body.getReader();
  const dec = new TextDecoder();
  let buf = '';
  for (;;) {
    const { value, done } = await reader.read();
    if (done) break;
    buf += dec.decode(value, { stream: true });
    let i;
    while ((i = buf.indexOf('\n\n')) >= 0) {
      const raw = buf.slice(0, i);
      buf = buf.slice(i + 2);
      let ev = 'message', data = '';
      for (const line of raw.split('\n')) {
        if (line.startsWith('event:')) ev = line.slice(6).trim();
        else if (line.startsWith('data:')) data += line.slice(5).trim();
      }
      if (data) onEvent(ev, JSON.parse(data));
    }
  }
}

let paintPending = false;
function paintStreaming() {
  if (paintPending) return;
  paintPending = true;
  requestAnimationFrame(() => {
    paintPending = false;
    const st = S.streaming;
    if (!st) return;
    const el = $('[data-mid="__bot"] .bubble');
    if (!el) return;
    const near = nearBottom();
    el.innerHTML = st.bot.content ? md(st.bot.content, st.bot.sources.length) : TYPING;
    el.classList.toggle('cursor', !!st.bot.content);
    if (near) toBottom();
  });
}

function stopAnswer() {
  const st = S.streaming;
  if (!st) return;
  fetch(`/api/chats/${encodeURIComponent(st.chat.id)}/stop`, { method: 'POST', headers: { Authorization: 'Bearer ' + S.token, 'Content-Type': 'application/json' }, body: '{}' })
    .catch(() => { /* ignore */ });
  // if the server does not finish quickly, close the connection
  setTimeout(() => { if (S.streaming === st) st.ctrl.abort(); }, 2500);
}

// ================================================================== assistants
async function pageAssistants() {
  const list = await api('GET', '/api/personas');
  S.meta.personas = list;
  view().innerHTML = `
    <div class="page-head"><div><h1>Assistants</h1>
      <p>Each assistant is the same AI model with different hidden instructions (a <b>system prompt</b>) and creativity.${canTrain() ? '' : ' AI trainers and the admin can change them.'}</p></div>
      <div class="spacer"></div>${canTrain() ? '<button class="primary" id="addP" type="button">+ New assistant</button>' : ''}</div>
    <div class="persona-grid">${list.map(p => `
      <article class="card persona-card pc-${esc(p.color)} ${p.active ? '' : 'inactive'}">
        <div class="top">${avatar(p)}<div class="grow"><b>${esc(p.name)}</b>
          <span class="small muted">Creativity ${esc(p.temperature)} · ${num(p.chatCount)} chat${p.chatCount === 1 ? '' : 's'}</span></div>
          ${p.useKnowledge ? '<span class="badge good">Knowledge base</span>' : ''}${p.active ? '' : '<span class="badge">Turned off</span>'}</div>
        <p class="desc">${esc(p.description)}</p>
        ${canTrain() ? `<div class="prompt" title="System prompt">${esc(p.systemPrompt)}</div>` : ''}
        <div class="row">
          ${p.active ? `<button type="button" class="primary small" data-start="${esc(p.id)}">Start chat</button>` : ''}
          ${canTrain() ? `<button type="button" class="small" data-edit="${esc(p.id)}">Edit</button>
            ${p.id === 'general' ? '' : `<button type="button" class="small danger" data-del="${esc(p.id)}">Delete</button>`}` : ''}
        </div>
      </article>`).join('')}</div>`;
  $$('[data-start]').forEach(b => b.addEventListener('click', () => { S.draftPersona = b.dataset.start; S.chat = null; location.hash = '#/chat'; }));
  if (!canTrain()) return;
  $('#addP').addEventListener('click', () => personaForm(null));
  $$('[data-edit]').forEach(b => b.addEventListener('click', () => personaForm(list.find(p => p.id === b.dataset.edit))));
  $$('[data-del]').forEach(b => b.addEventListener('click', () => {
    const p = list.find(x => x.id === b.dataset.del);
    confirmBox('Delete ' + p.name + '?', 'Chats that use it will move to the General assistant.', 'Delete', async () => {
      await api('DELETE', '/api/personas/' + p.id); toast('Assistant deleted'); await loadMeta(); pageAssistants();
    });
  }));
}

function personaForm(p) {
  const colors = ['teal', 'blue', 'violet', 'amber', 'rose', 'green', 'slate'];
  modal.open({
    title: p ? 'Edit ' + p.name : 'New assistant', wide: true,
    body: `
      <div class="grid2">
        <div class="field"><label for="pn1">Name</label><input id="pn1" name="name" maxlength="40" required value="${esc(p ? p.name : '')}"></div>
        <div class="field"><label for="pc1">Colour</label><select id="pc1" name="color">${options(colors, p ? p.color : 'violet')}</select></div>
      </div>
      <div class="field"><label for="pd1">Short description</label><input id="pd1" name="description" maxlength="160" value="${esc(p ? p.description : '')}"></div>
      <div class="field"><label for="ps1">Instructions (system prompt)</label>
        <textarea id="ps1" name="systemPrompt" class="tall" maxlength="4000" required placeholder="You are a … Explain … Always …">${esc(p ? p.systemPrompt : '')}</textarea>
        <div class="hint">Tell the AI who it is, how to answer and what not to do. It is sent before every chat, hidden from the person.</div></div>
      <div class="field"><label for="pt1">Creativity (temperature)</label>
        <div class="range-row"><input id="pt1" name="temperature" type="range" min="0" max="1.5" step="0.05" value="${esc(p ? p.temperature : 0.7)}"><output></output></div></div>
      <div class="field"><label for="pst">Starter questions (one per line, up to 6)</label>
        <textarea id="pst" name="starters" maxlength="1200">${esc(p ? p.starters.join('\n') : '')}</textarea></div>
      <label class="check"><input type="checkbox" name="useKnowledge" ${p && p.useKnowledge ? 'checked' : ''}> Search the knowledge base by default (RAG)</label>
      ${p ? `<label class="check"><input type="checkbox" name="active" ${p.active ? 'checked' : ''} ${p.id === 'general' ? 'disabled' : ''}> Turned on (people can choose it)</label>` : ''}`,
    onOpen: wireRanges,
    onSubmit: async d => {
      const body = { ...d, temperature: Number(d.temperature), starters: d.starters.split('\n').map(x => x.trim()).filter(Boolean) };
      if (p && p.id === 'general') body.active = true;
      if (p) await api('PUT', '/api/personas/' + p.id, body); else await api('POST', '/api/personas', body);
      toast('Saved'); await loadMeta(); pageAssistants();
    }
  });
}

// ================================================================== knowledge base
async function pageKnowledge() {
  const kb = await api('GET', '/api/knowledge');
  view().innerHTML = `
    <div class="page-head"><div><h1>Knowledge base</h1>
      <p>Notes the bot can look up. When the knowledge base is on in a chat, the best matching parts are sent to the AI with the question and cited like [1].</p></div>
      <div class="spacer"></div>${canTrain() ? '<button class="primary" id="addDoc" type="button">+ Add note</button>' : ''}</div>
    <div class="split">
      <section class="card">
        <div class="card-head"><h2>Notes</h2><span class="sub">${kb.docs.length} notes · ${kb.chunks} searchable parts</span></div>
        <div class="table-wrap"><table>
          <thead><tr><th>Title</th><th>Tags</th><th class="num">Words</th><th>Updated</th><th></th></tr></thead>
          <tbody>${kb.docs.map(d => `<tr><td><a href="#" data-view="${esc(d.id)}">${esc(d.title)}</a></td><td class="small muted">${esc(d.tags)}</td>
            <td class="num">${num(d.words)}</td><td class="nowrap small">${esc(ago(d.updatedAt))}</td>
            <td class="nowrap">${canTrain() ? `<button class="small" data-edoc="${esc(d.id)}">Edit</button> <button class="small danger" data-ddoc="${esc(d.id)}">Delete</button>` : ''}</td></tr>`).join('')
            || '<tr><td colspan="5"><div class="empty">No notes yet.</div></td></tr>'}</tbody></table></div>
      </section>
      <div class="stack">
        <section class="card">
          <div class="card-head"><h2>Test the search</h2></div>
          <div class="card-body">
            <form id="kbSearch" class="row"><input id="kbQ" type="search" placeholder="e.g. late fee for library books" aria-label="Question"><button class="primary" type="submit">Search</button></form>
            <p class="hint">Shows exactly which parts the bot would use, with their BM25 score (higher = better match). A part is used only if it has at least 1 in 3 of the question's main words.</p>
          </div>
          <div id="kbHits"></div>
        </section>
        <section class="card card-pad">
          <h3>How RAG works here</h3>
          <ol class="steps">
            <li><b>Split</b>: each note is cut into parts of about 700 characters.</li>
            <li><b>Search</b>: the question's words are matched with BM25 (rare words count more).</li>
            <li><b>Augment</b>: the top 3 parts are added to the system prompt as [1], [2], [3].</li>
            <li><b>Generate</b>: the AI answers from them and cites the number. Click a citation to see the part.</li>
          </ol>
        </section>
      </div>
    </div>`;
  $('#kbSearch').addEventListener('submit', async e => {
    e.preventDefault();
    const q = $('#kbQ').value.trim(); if (!q) return;
    const hits = await api('GET', '/api/knowledge/search?k=5&q=' + encodeURIComponent(q));
    $('#kbHits').innerHTML = hits.length ? hits.map(h => `<div class="hit"><div class="h-top"><b>[${h.n}] ${esc(h.title)}</b><span class="badge good">score ${esc(h.score)}</span></div><p>${esc(h.text)}</p></div>`).join('')
      : '<div class="empty">Nothing matched well enough. The bot would answer without notes.</div>';
  });
  $$('[data-view]').forEach(a => a.addEventListener('click', async e => {
    e.preventDefault();
    const d = await api('GET', '/api/knowledge/' + a.dataset.view);
    modal.open({ title: d.title, wide: true, body: `<p class="hint">Tags: ${esc(d.tags || '-')} · updated ${esc(ago(d.updatedAt))}</p><div class="doc-text">${esc(d.text)}</div>` });
  }));
  if (!canTrain()) return;
  $('#addDoc').addEventListener('click', () => docForm(null));
  $$('[data-edoc]').forEach(b => b.addEventListener('click', async () => docForm(await api('GET', '/api/knowledge/' + b.dataset.edoc))));
  $$('[data-ddoc]').forEach(b => b.addEventListener('click', () => {
    const d = kb.docs.find(x => x.id === b.dataset.ddoc);
    confirmBox('Delete "' + d.title + '"?', 'The bot will no longer find this note.', 'Delete', async () => { await api('DELETE', '/api/knowledge/' + d.id); toast('Note deleted'); pageKnowledge(); });
  }));
}

function docForm(d) {
  modal.open({
    title: d ? 'Edit note' : 'Add a note', wide: true,
    body: `
      <div class="grid2">
        <div class="field"><label for="dt">Title</label><input id="dt" name="title" maxlength="100" required value="${esc(d ? d.title : '')}"></div>
        <div class="field"><label for="dg">Tags (help the search)</label><input id="dg" name="tags" maxlength="120" value="${esc(d ? d.tags : '')}" placeholder="library, fees"></div>
      </div>
      <div class="field"><label for="dx">Text</label><textarea id="dx" name="text" class="tall mono" required>${esc(d ? d.text : '')}</textarea>
        <div class="hint">Leave a blank line between topics - each paragraph becomes a searchable part.</div></div>
      <div class="field"><label for="df">…or load a .txt / .md file</label><input id="df" type="file" accept=".txt,.md,.csv,text/plain,text/markdown"></div>`,
    onOpen: b => {
      $('#df', b).addEventListener('change', e => {
        const f = e.target.files[0]; if (!f) return;
        if (f.size > 200000) { toast('File is larger than 200 KB. Split it into parts.', true); return; }
        const r = new FileReader();
        r.onload = () => { $('#dx', b).value = String(r.result); if (!$('#dt', b).value) $('#dt', b).value = f.name.replace(/\.[^.]+$/, ''); };
        r.readAsText(f);
      });
    },
    onSubmit: async x => {
      const body = { title: x.title, tags: x.tags, text: x.text };
      if (d) await api('PUT', '/api/knowledge/' + d.id, body); else await api('POST', '/api/knowledge', body);
      toast('Saved'); pageKnowledge();
    }
  });
}

// ================================================================== dashboard
async function pageDashboard() {
  const d = await api('GET', '/api/dashboard');
  const me = d.me;
  const quota = me.quota > 0 ? `${num(me.tokensToday)} <small>of ${num(me.quota)}</small>` : num(me.tokensToday);
  const team = d.team, fb = d.feedback;
  view().innerHTML = `
    <div class="page-head"><div><h1>Dashboard</h1><p>${d.scope === 'team' ? 'Usage for the whole team.' : 'Your usage.'} Every AI answer is logged with its model, tokens and time.</p></div></div>
    <div class="kpis">
      <div class="card kpi accent"><div class="k-label">My questions today</div><div class="k-value">${num(me.questionsToday)}</div><div class="k-sub">${num(me.questions)} in total, ${num(me.chats)} chats</div></div>
      <div class="card kpi"><div class="k-label">Tokens used today</div><div class="k-value">${quota}</div>
        ${me.quota > 0 ? bar(me.tokensToday, me.quota, me.tokensToday > me.quota * 0.9 ? 'bad' : '') + '<div class="k-sub">Daily limit, resets at midnight</div>' : '<div class="k-sub">No daily limit</div>'}</div>
      <div class="card kpi"><div class="k-label">Average answer time</div><div class="k-value">${esc(secs(me.avgMs))}</div><div class="k-sub">From question to last word</div></div>
      <div class="card kpi"><div class="k-label">Speed limit</div><div class="k-value">${me.ratePerMinute ? num(me.ratePerMinute) + ' <small>/ min</small>' : 'None'}</div><div class="k-sub">Messages per minute</div></div>
    </div>
    <div class="split">
      <section class="card"><div class="card-head"><h2>AI calls, last 14 days</h2><span class="sub">${d.scope === 'team' ? 'whole team' : 'you'}</span></div>
        <div class="card-body">${vchart(d.days.map(x => ({ label: dayLabel(x.date), v: x.calls, title: `${dayLabel(x.date)}: ${x.calls} calls, ${x.tokens} tokens${x.errors ? ', ' + x.errors + ' errors' : ''}` })), 'AI calls per day')}</div></section>
      <section class="card"><div class="card-head"><h2>AI models</h2></div>
        <ul class="list">${d.providers.map(p => `<li><span class="dot ${p.ready ? 'ready' : 'off'}"></span><div class="grow"><span class="title">${esc(p.name)}${p.isDefault ? ' <span class="badge good">Default</span>' : ''}</span>
          <span class="meta">${esc(p.model)} · ${esc(p.status)}</span></div></li>`).join('')}</ul>
        ${isAdmin() ? '<div class="card-body"><a href="#/models">Set up AI models</a></div>' : ''}</section>
    </div>
    ${team ? `
    <div class="kpis">
      <div class="card kpi"><div class="k-label">People</div><div class="k-value">${num(team.users)}</div></div>
      <div class="card kpi"><div class="k-label">Chats / messages</div><div class="k-value">${num(team.chats)} <small>/ ${num(team.messages)}</small></div></div>
      <div class="card kpi"><div class="k-label">Tokens (all time)</div><div class="k-value">${num(team.tokens)}</div><div class="k-sub">${num(team.calls)} AI calls, ${num(team.errors)} failed</div></div>
      <div class="card kpi"><div class="k-label">Safety</div><div class="k-value">${num(team.redacted)} <small>hidden</small></div>
        <div class="k-sub">${num(team.injection)} injection attempts · ${num(team.fallback)} demo fallbacks · ${num(team.stopped)} stopped</div></div>
    </div>
    <div class="cards cols-3">
      <section class="card"><div class="card-head"><h2>By AI model (30 days)</h2></div><div class="table-wrap"><table>
        <thead><tr><th>Model</th><th class="num">Calls</th><th class="num">Tokens</th><th class="num">Avg time</th></tr></thead>
        <tbody>${team.byProvider.map(p => `<tr><td>${esc(p.name)}${p.errors ? `<div class="tiny bad-text">${num(p.errors)} failed</div>` : ''}</td><td class="num">${num(p.calls)}</td><td class="num">${num(p.tokens)}</td><td class="num">${esc(secs(p.avgMs))}</td></tr>`).join('') || '<tr><td colspan="4"><div class="empty">No calls yet.</div></td></tr>'}</tbody></table></div></section>
      <section class="card"><div class="card-head"><h2>Top users (30 days)</h2></div><div class="card-body">${hbars(team.byUser.map(u => ({ label: u.name, value: u.tokens, text: num(u.tokens) + ' tokens' })))}</div></section>
      <section class="card"><div class="card-head"><h2>Chats per assistant</h2></div><div class="card-body">${hbars(team.byAssistant.map(a => ({ label: a.name, value: a.chats, text: num(a.chats) })), 'spark')}</div></section>
    </div>` : ''}
    ${fb ? `
    <div class="split">
      <section class="card"><div class="card-head"><h2>Answers marked bad</h2><span class="sub">Use these to improve the assistants' instructions</span></div>
        ${fb.recentDown.length ? `<ul class="list">${fb.recentDown.map(r => `<li><div class="grow"><span class="title">${esc(r.question || '(no question)')}</span>
          <span class="meta">${esc(r.user)} · ${esc(r.assistant)} · ${esc(r.model)} · ${esc(ago(r.at))}</span>
          <div class="small">${esc(r.answer)}</div>${r.note ? `<div class="small bad-text">"${esc(r.note)}"</div>` : ''}</div></li>`).join('')}</ul>` : '<div class="empty">No bad answers reported.</div>'}</section>
      <section class="card"><div class="card-head"><h2>Feedback</h2><span class="sub">${num(fb.up)} good · ${num(fb.down)} bad</span></div>
        <div class="card-body">${fb.up + fb.down ? bar(fb.up, fb.up + fb.down, '') + `<p class="hint">${pct(fb.up, fb.up + fb.down)}% of rated answers were good.</p>` : ''}
        ${hbars(fb.byAssistant.map(a => ({ label: a.name, value: a.up + a.down, text: a.up + ' good / ' + a.down + ' bad' })))}</div></section>
    </div>` : ''}`;
  paintBars(view());
}

// ================================================================== AI models (admin)
async function pageModels() {
  if (!isAdmin()) { view().innerHTML = '<div class="card card-pad">Only the admin can set up AI models.</div>'; return; }
  const list = await api('GET', '/api/providers');
  view().innerHTML = `
    <div class="page-head"><div><h1>AI models</h1>
      <p>Where the answers come from. Paste an API key (or set the environment variable), press <b>Test</b>, then <b>Make default</b>.
        Keys stay on the server and are never sent to the browser.</p></div>
      <div class="spacer"></div><button class="primary" id="addProv" type="button">+ Add provider</button></div>
    <div class="prov-grid">${list.map(p => `
      <article class="card prov ${p.isDefault ? 'is-default' : ''}">
        <div class="top"><span class="dot ${p.ready ? 'ready' : 'off'}"></span><b>${esc(p.name)}</b><div class="spacer"></div>
          ${p.isDefault ? '<span class="badge good">Default</span>' : ''}<span class="badge ${p.ready ? 'good' : 'warn'}">${esc(p.status)}</span></div>
        <dl>
          <dt>Type</dt><dd>${esc(p.kindLabel)}</dd>
          <dt>Model</dt><dd class="mono">${esc(p.model)}</dd>
          ${p.kind === 'DEMO' ? '' : `<dt>Base URL</dt><dd class="mono">${esc(p.baseUrl)}</dd>
          <dt>API key</dt><dd>${p.needsKey ? (p.keySource === 'none' ? '<span class="warn-text">none yet</span>' : esc(p.keyHint) + ' <span class="muted">(' + (p.keySource === 'env' ? 'from ' + esc(p.envKey) : 'saved') + ')</span>') : 'not needed'}</dd>`}
        </dl>
        <p class="help">${esc(p.help)}</p>
        <p class="test-out" id="out-${esc(p.id)}" role="status"></p>
        <div class="row">
          <button type="button" class="small" data-test="${esc(p.id)}">Test</button>
          ${p.kind === 'DEMO' ? '' : `<button type="button" class="small" data-edit="${esc(p.id)}">Edit / API key</button>`}
          ${p.isDefault || !p.enabled ? '' : `<button type="button" class="small" data-default="${esc(p.id)}">Make default</button>`}
          ${p.kind === 'DEMO' ? '' : `<button type="button" class="small danger" data-del="${esc(p.id)}">Remove</button>`}
        </div>
      </article>`).join('')}</div>`;
  $('#addProv').addEventListener('click', () => providerForm(null));
  $$('[data-test]').forEach(b => b.addEventListener('click', () => run(b, async () => {
    const out = $('#out-' + CSS.escape(b.dataset.test));
    out.className = 'test-out muted'; out.textContent = 'Testing…';
    const r = await api('POST', '/api/providers/' + b.dataset.test + '/test', {});
    out.className = 'test-out ' + (r.ok ? 'good-text' : 'bad-text');
    out.textContent = (r.ok ? '✓ ' : '✗ ') + r.message + (r.reply ? ' Reply: "' + r.reply.trim() + '"' : '');
  })));
  $$('[data-edit]').forEach(b => b.addEventListener('click', () => providerForm(list.find(p => p.id === b.dataset.edit))));
  $$('[data-default]').forEach(b => b.addEventListener('click', () => run(b, async () => {
    await api('PUT', '/api/settings', { defaultProviderId: b.dataset.default });
    toast('Default model changed'); await loadMeta(); pageModels();
  })));
  $$('[data-del]').forEach(b => b.addEventListener('click', () => {
    const p = list.find(x => x.id === b.dataset.del);
    confirmBox('Remove ' + p.name + '?', 'Chats that use it will use the default model instead.', 'Remove', async () => {
      await api('DELETE', '/api/providers/' + p.id); toast('Removed'); await loadMeta(); pageModels();
    });
  }));
}

function providerForm(p) {
  modal.open({
    title: p ? 'Edit ' + p.name : 'Add an AI provider', wide: true,
    body: `
      <div class="grid2">
        <div class="field"><label for="vn">Name</label><input id="vn" name="name" maxlength="40" required value="${esc(p ? p.name : '')}" placeholder="OpenRouter"></div>
        <div class="field"><label for="vk">Type (API style)</label><select id="vk" name="kind" ${p ? 'disabled' : ''}>${options(S.meta.providerKinds.filter(k => k.id !== 'DEMO'), p ? p.kind : 'OPENAI')}</select></div>
      </div>
      <div class="field"><label for="vu">Base URL</label><input id="vu" name="baseUrl" class="mono" required value="${esc(p ? p.baseUrl : 'https://')}" placeholder="https://openrouter.ai/api/v1">
        <div class="hint">OpenAI-compatible examples: https://api.groq.com/openai/v1 · https://openrouter.ai/api/v1 · http://localhost:11434/v1 (Ollama)</div></div>
      <div class="grid2">
        <div class="field"><label for="vm">Default model</label><input id="vm" name="model" class="mono" required maxlength="100" value="${esc(p ? p.model : '')}"></div>
        <div class="field"><label for="vms">Other models to offer (comma separated)</label><input id="vms" name="models" class="mono" value="${esc(p ? p.models.join(', ') : '')}"></div>
      </div>
      <div class="grid2">
        <div class="field"><label for="va">API key</label><input id="va" name="apiKey" type="password" autocomplete="off" placeholder="${p && p.keySource === 'saved' ? 'saved ' + esc(p.keyHint) + ' - leave empty to keep' : 'paste the key'}">
          <div class="hint">Type CLEAR to remove a saved key.</div></div>
        <div class="field"><label for="ve">…or environment variable</label><input id="ve" name="envKey" class="mono" maxlength="60" value="${esc(p ? p.envKey : '')}" placeholder="MY_API_KEY">
          <div class="hint">Safer: the key is not stored in the data folder.</div></div>
      </div>
      <label class="check"><input type="checkbox" name="needsKey" ${!p || p.needsKey ? 'checked' : ''}> Needs an API key (untick for local Ollama / LM Studio)</label>
      <label class="check"><input type="checkbox" name="enabled" ${!p || p.enabled ? 'checked' : ''}> Turned on</label>`,
    onSubmit: async d => {
      if (p) await api('PUT', '/api/providers/' + p.id, d); else await api('POST', '/api/providers', d);
      toast('Saved'); await loadMeta(); pageModels();
    }
  });
}

// ================================================================== AI settings (admin)
async function pageSettings() {
  if (!isAdmin()) { view().innerHTML = '<div class="card card-pad">Only the admin can change AI settings.</div>'; return; }
  const s = await api('GET', '/api/settings');
  view().innerHTML = `
    <div class="page-head"><div><h1>AI settings</h1><p>Bot name, safety filters and limits for everyone.</p></div></div>
    <form id="setForm" class="cards cols-2" novalidate>
      <section class="card card-pad">
        <fieldset><legend>Bot</legend>
          <div class="field"><label for="sb">Bot name</label><input id="sb" name="botName" maxlength="30" value="${esc(s.botName)}"></div>
          <div class="field"><label for="sd">Default AI model</label><select id="sd" name="defaultProviderId">${options(S.meta.providers.filter(p => p.enabled).map(p => ({ id: p.id, label: p.name + (p.ready ? '' : ' (not ready)') })), s.defaultProviderId)}</select></div>
          <label class="check"><input type="checkbox" name="fallbackToDemo" ${s.fallbackToDemo ? 'checked' : ''}> If the online model fails, let the built-in demo answer</label>
        </fieldset>
        <fieldset><legend>Limits</legend>
          <div class="grid2">
            <div class="field"><label for="sc">Chat memory sent (tokens)</label><input id="sc" name="maxContextTokens" type="number" min="500" max="200000" value="${esc(s.maxContextTokens)}">
              <div class="hint">Older messages beyond this are left out.</div></div>
            <div class="field"><label for="sr">Longest answer (tokens)</label><input id="sr" name="maxReplyTokens" type="number" min="50" max="16000" value="${esc(s.maxReplyTokens)}"></div>
            <div class="field"><label for="sm">Messages per minute (per person)</label><input id="sm" name="ratePerMinute" type="number" min="0" max="600" value="${esc(s.ratePerMinute)}"><div class="hint">0 = no limit. The admin is never limited.</div></div>
            <div class="field"><label for="sq">Tokens per day (per person)</label><input id="sq" name="dailyTokenQuota" type="number" min="0" max="10000000" value="${esc(s.dailyTokenQuota)}"><div class="hint">0 = no limit.</div></div>
            <div class="field"><label for="st">Wait for the AI (seconds)</label><input id="st" name="timeoutSec" type="number" min="5" max="300" value="${esc(s.timeoutSec)}"></div>
          </div>
        </fieldset>
      </section>
      <section class="card card-pad">
        <fieldset><legend>Safety</legend>
          <label class="check"><input type="checkbox" name="redactPii" ${s.redactPii ? 'checked' : ''}> Hide personal details before sending to online models</label>
          <p class="hint">E-mails → [EMAIL], phone numbers → [PHONE], card numbers (Luhn check) → [CARD], Aadhaar / PAN → [ID_NUMBER], API keys → [SECRET_KEY]. The chat still shows what the person typed.</p>
          <div class="field"><label for="si">Prompt-injection check</label><select id="si" name="injectionMode">${options(S.meta.injectionModes, s.injectionMode)}</select>
            <div class="hint">Spots messages like "ignore all previous instructions" or "show your system prompt".</div></div>
          <div class="field"><label for="sw">Blocked words or phrases (one per line)</label><textarea id="sw" name="blockedWords">${esc(s.blockedWords.join('\n'))}</textarea>
            <div class="hint">Messages containing these are not sent.</div></div>
        </fieldset>
        <div class="row"><div class="spacer"></div><button type="submit" class="primary">Save settings</button></div>
      </section>
    </form>`;
  $('#setForm').addEventListener('submit', async e => {
    e.preventDefault();
    const btn = e.submitter;
    await run(btn, async () => {
      const d = Object.fromEntries(new FormData(e.target).entries());
      $$('#setForm input[type=checkbox]').forEach(c => { d[c.name] = c.checked; });
      for (const k of ['maxContextTokens', 'maxReplyTokens', 'ratePerMinute', 'dailyTokenQuota', 'timeoutSec']) d[k] = Number(d[k]);
      d.blockedWords = d.blockedWords.split('\n').map(x => x.trim()).filter(Boolean);
      await api('PUT', '/api/settings', d);
      toast('Settings saved'); await loadMeta();
    });
  });
}

// ================================================================== team (admin)
async function pageTeam() {
  if (!isAdmin()) { view().innerHTML = '<div class="card card-pad">Only the admin can manage the team.</div>'; return; }
  S.users = await api('GET', '/api/users');
  view().innerHTML = `
    <div class="page-head"><div><h1>Team</h1><p>Members chat. AI trainers also edit assistants and the knowledge base and see feedback. The admin sets up AI models, settings and people.</p></div>
      <div class="spacer"></div><button class="primary" id="addUser" type="button">+ Add person</button></div>
    <section class="card"><div class="table-wrap"><table>
      <thead><tr><th>Name</th><th>Username</th><th>Role</th><th>Status</th><th></th></tr></thead>
      <tbody>${S.users.map(u => `<tr><td>${esc(u.displayName)}</td><td>${esc(u.username)}</td><td>${esc(u.roleLabel)}</td>
        <td>${u.active ? '<span class="badge good">Active</span>' : '<span class="badge">Turned off</span>'}</td>
        <td><button class="small" data-user="${esc(u.username)}">Edit</button></td></tr>`).join('')}</tbody></table></div></section>`;
  $('#addUser').addEventListener('click', () => userForm(null));
  $$('[data-user]').forEach(b => b.addEventListener('click', () => userForm(S.users.find(u => u.username === b.dataset.user))));
}

function userForm(u) {
  modal.open({
    title: u ? 'Edit ' + u.displayName : 'Add a person',
    body: `
      <div class="grid2">
        <div class="field"><label for="tn">Name</label><input id="tn" name="displayName" maxlength="60" required value="${esc(u ? u.displayName : '')}"></div>
        <div class="field"><label for="tu">Username</label><input id="tu" name="username" maxlength="30" ${u ? 'disabled' : 'required'} value="${esc(u ? u.username : '')}" autocomplete="off">
          ${u ? '' : '<div class="hint">3-30 small letters, numbers, . _ -</div>'}</div>
        <div class="field"><label for="tr">Role</label><select id="tr" name="role">${options(S.meta.roles, u ? u.role : 'MEMBER')}</select></div>
        <div class="field"><label for="tp">${u ? 'New password (leave empty to keep)' : 'Password'}</label><input id="tp" name="password" type="password" minlength="8" autocomplete="new-password" ${u ? '' : 'required'}></div>
      </div>
      ${u ? `<label class="check"><input type="checkbox" name="active" ${u.active ? 'checked' : ''}> Account is active (can sign in)</label>` : ''}`,
    onSubmit: async d => {
      if (u) await api('PUT', '/api/users/' + encodeURIComponent(u.username), d); else await api('POST', '/api/users', d);
      toast('Saved'); pageTeam();
    }
  });
}

// ------------------------------------------------------------------ start
// Re-drawing a page that the person already left is simply ignored; other surprises are shown as a message.
window.addEventListener('unhandledrejection', e => {
  if (e.reason instanceof StaleError) { e.preventDefault(); return; }
  if (e.reason && e.reason.message) toast(e.reason.message, true);
});
boot();
