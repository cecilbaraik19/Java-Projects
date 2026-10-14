/* AI Code Review Assistant - frontend
 * Plain JavaScript (no framework). Talks to the Java backend through /api/... calls.
 * Pages: #/review  #/report  #/rules  #/history  #/settings
 */
'use strict';

// ------------------------------------------------------------------ state

const SEVERITIES = ['CRITICAL', 'MAJOR', 'MINOR', 'INFO'];
const SEV_TEXT = {
  CRITICAL: 'Must fix now - security hole or certain bug',
  MAJOR: 'Should fix - likely bug or serious design problem',
  MINOR: 'Nice to fix - code smell or maintainability problem',
  INFO: 'Suggestion - style or small improvement'
};
const CODE_EXT = /\.(java|js|jsx|mjs|ts|tsx|py)$/i;

const state = {
  info: null,
  rules: [],
  rulesById: {},
  files: [],            // [{name, content}]
  active: 0,            // index of the open file
  mode: 'edit',         // 'edit' or 'review'
  report: null,         // last review report from the backend
  reportByFile: {},     // file name -> file report
  stale: false,         // code changed after the last review
  filter: new Set(SEVERITIES),
  selected: null,       // selected issue key "file|line|rule"
  busy: false,
  settings: loadSettings()
};

const view = document.getElementById('view');

// ------------------------------------------------------------- utilities

function esc(s) {
  return String(s == null ? '' : s)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}

function store(key, value) {
  try { localStorage.setItem(key, JSON.stringify(value)); } catch (e) { /* storage may be blocked */ }
}

function restore(key, fallback) {
  try {
    const v = localStorage.getItem(key);
    return v == null ? fallback : JSON.parse(v);
  } catch (e) {
    return fallback;
  }
}

function loadSettings() {
  const s = restore('acr.settings', {});
  return Object.assign({ minSeverity: 'INFO', disabledRules: [], saveHistory: true, limits: {} }, s);
}

function saveSettings() { store('acr.settings', state.settings); }

function saveDraft() {
  // keep the open files in this browser so a page refresh does not lose them
  const total = state.files.reduce((n, f) => n + f.content.length, 0);
  if (total < 2000000) store('acr.files', { files: state.files, active: state.active });
}

let toastTimer;
function toast(msg, isError) {
  const t = document.getElementById('toast');
  t.textContent = msg;
  t.className = 'toast' + (isError ? ' err' : '');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => t.classList.add('hidden'), isError ? 6000 : 3000);
}

async function api(path, body, method) {
  const opts = { method: method || (body ? 'POST' : 'GET'), headers: {} };
  if (body) {
    opts.headers['Content-Type'] = 'application/json';
    opts.body = JSON.stringify(body);
  }
  let res;
  try {
    res = await fetch(path, opts);
  } catch (e) {
    throw new Error('Cannot reach the backend. Is run.bat still running?');
  }
  const text = await res.text();
  let data;
  try { data = text ? JSON.parse(text) : {}; } catch (e) { data = { raw: text }; }
  if (!res.ok) throw new Error(data.error || ('Request failed (' + res.status + ')'));
  return data;
}

function langOf(name) {
  const n = name.toLowerCase();
  if (n.endsWith('.py')) return 'python';
  if (/\.(js|jsx|mjs)$/.test(n)) return 'javascript';
  if (/\.(ts|tsx)$/.test(n)) return 'typescript';
  return 'java';
}

function sevRank(s) { return SEVERITIES.indexOf(s); }

function issueKey(i, file) { return file + '|' + i.line + '|' + i.rule; }

function gradeClass(g) { return 'g' + (g || 'F'); }

function activeFile() { return state.files[state.active]; }

function reportPayload(extra) {
  const limits = {};
  Object.keys(state.settings.limits || {}).forEach(k => {
    const v = Number(state.settings.limits[k]);
    if (v > 0) limits[k] = v;
  });
  return Object.assign({
    files: state.files,
    settings: Object.assign({ minSeverity: state.settings.minSeverity, disabledRules: state.settings.disabledRules }, limits)
  }, extra || {});
}

// ------------------------------------------------------------------ modal

function openModal(title, bodyHtml, buttons) {
  document.getElementById('modalTitle').textContent = title;
  document.getElementById('modalBody').innerHTML = bodyHtml;
  const foot = document.getElementById('modalFoot');
  foot.innerHTML = '';
  (buttons || [{ label: 'Close' }]).forEach(b => {
    const el = document.createElement('button');
    el.textContent = b.label;
    if (b.primary) el.className = 'primary';
    if (b.danger) el.className = 'danger';
    el.onclick = async () => {
      if (b.onClick) {
        const keepOpen = await b.onClick();
        if (keepOpen === true) return;
      }
      closeModal();
    };
    foot.appendChild(el);
  });
  document.getElementById('modal').classList.remove('hidden');
  const first = document.querySelector('#modalBody input, #modalFoot button.primary');
  if (first) first.focus();
}

function closeModal() { document.getElementById('modal').classList.add('hidden'); }

document.getElementById('modalClose').onclick = closeModal;
document.getElementById('modal').addEventListener('click', e => { if (e.target.id === 'modal') closeModal(); });
document.addEventListener('keydown', e => { if (e.key === 'Escape') closeModal(); });

// ------------------------------------------------------------ theme

function applyTheme(t) {
  if (t === 'dark' || t === 'light') document.documentElement.setAttribute('data-theme', t);
  else document.documentElement.removeAttribute('data-theme');
  const dark = t === 'dark' || (t !== 'light' && window.matchMedia('(prefers-color-scheme: dark)').matches);
  document.getElementById('themeBtn').textContent = dark ? 'Light mode' : 'Dark mode';
}

document.getElementById('themeBtn').onclick = () => {
  const dark = document.getElementById('themeBtn').textContent === 'Light mode';
  const t = dark ? 'light' : 'dark';
  store('acr.theme', t);
  applyTheme(t);
};

// close the "+ Add file" menu when clicking anywhere else
document.addEventListener('click', e => {
  const menu = document.getElementById('addMenu');
  if (menu && menu.open && !menu.contains(e.target)) menu.open = false;
});

// sidebar: slim (icons only) or wide, remembered in this browser
function applySidebar() {
  const slim = restore('acr.slim', true);
  document.querySelector('.shell').classList.toggle('slim', slim);
  document.getElementById('slimBtn').textContent = slim ? '\u00bb' : '\u00ab';
  document.getElementById('slimBtn').title = slim ? 'Expand menu' : 'Collapse menu';
}
document.getElementById('slimBtn').onclick = () => { store('acr.slim', !restore('acr.slim', true)); applySidebar(); };
applySidebar();

document.getElementById('menuBtn').onclick = () => document.getElementById('side').classList.toggle('open');

// ------------------------------------------------------- syntax highlight

const KEYWORDS = {
  java: 'abstract assert boolean break byte case catch char class const continue default do double else enum extends final finally float for if implements import instanceof int interface long native new package private protected public return short static strictfp super switch synchronized this throw throws transient try var void volatile while true false null record',
  javascript: 'async await break case catch class const continue debugger default delete do else export extends finally for function if import in instanceof let new return super switch this throw try typeof var void while with yield true false null undefined of',
  python: 'and as assert async await break class continue def del elif else except finally for from global if import in is lambda nonlocal not or pass raise return try while with yield True False None self'
};
KEYWORDS.typescript = KEYWORDS.javascript + ' interface type enum implements private public protected readonly';
const KW_SETS = {};
Object.keys(KEYWORDS).forEach(k => { KW_SETS[k] = new Set(KEYWORDS[k].split(' ')); });

/** Highlights all lines of a file. Returns an array of HTML strings (one per line). */
function highlight(text, lang) {
  const kw = KW_SETS[lang] || KW_SETS.java;
  const py = lang === 'python';
  const out = [];
  let inBlock = false;
  for (const line of text.split('\n')) {
    let html = '';
    let i = 0;
    while (i < line.length) {
      if (inBlock) {
        const end = line.indexOf('*/', i);
        const stop = end < 0 ? line.length : end + 2;
        html += '<span class="tk-com">' + esc(line.slice(i, stop)) + '</span>';
        i = stop;
        if (end >= 0) inBlock = false;
        continue;
      }
      const c = line[i];
      const rest = line.slice(i);
      if ((!py && rest.startsWith('//')) || (py && c === '#')) {
        html += '<span class="tk-com">' + esc(rest) + '</span>';
        break;
      }
      if (!py && rest.startsWith('/*')) { inBlock = true; continue; }
      if (c === '"' || c === '\'' || c === '`') {
        let j = i + 1;
        while (j < line.length && line[j] !== c) j += line[j] === '\\' ? 2 : 1;
        html += '<span class="tk-str">' + esc(line.slice(i, j + 1)) + '</span>';
        i = j + 1;
        continue;
      }
      const word = /^[A-Za-z_$][\w$]*/.exec(rest);
      if (word) {
        const w = word[0];
        html += kw.has(w) ? '<span class="tk-kw">' + w + '</span>' : esc(w);
        i += w.length;
        continue;
      }
      const num = /^\d[\d_.]*[lLfFdD]?/.exec(rest);
      if (num) {
        html += '<span class="tk-num">' + esc(num[0]) + '</span>';
        i += num[0].length;
        continue;
      }
      html += esc(c);
      i++;
    }
    out.push(html || ' ');
  }
  return out;
}

// --------------------------------------------------------------- router

const routes = { review: renderReview, report: renderReport, rules: renderRules, history: renderHistory, settings: renderSettings };

function route() {
  const name = (location.hash.replace('#/', '') || 'review').split('?')[0];
  const fn = routes[name] || renderReview;
  document.querySelectorAll('#nav a').forEach(a => a.classList.toggle('active', a.dataset.nav === name));
  document.getElementById('side').classList.remove('open');
  fn();
  view.focus({ preventScroll: true });
}

window.addEventListener('hashchange', route);

// ========================================================== REVIEW PAGE

function renderReview() {
  view.innerHTML = `
    <div class="workspace ${state.settings.focus ? 'focus' : ''}" id="workspace">
      <section class="card editor-card">
        <div class="tabs-bar">
          <div class="file-tabs" id="fileList" role="tablist" aria-label="Open files"></div>
          <details class="menu" id="addMenu">
            <summary class="btn">+ Add file</summary>
            <div class="menu-list">
              <button id="newBtn">New / paste code</button>
              <button id="uploadBtn">Upload files</button>
              <button id="folderBtn">Upload a folder</button>
              <button id="pathBtn">Open path on this computer</button>
              <button id="sampleBtn">Load sample files</button>
              <hr>
              <button id="clearBtn" class="danger">Remove all files</button>
            </div>
          </details>
        </div>
        <div class="editor-bar">
          <div class="mode-tabs" role="tablist">
            <button id="tabEdit" role="tab">Edit</button>
            <button id="tabReview" role="tab">Reviewed</button>
          </div>
          <label class="toggle small" title="Wrap long lines so the whole code is visible"><input type="checkbox" id="wrapCb" ${state.settings.wrap !== false ? 'checked' : ''}> Wrap</label>
          <div class="spacer"></div>
          <button class="primary" id="reviewBtn">Review</button>
          <button id="fixBtn" title="Safely fix simple issues">Auto-fix</button>
          <select id="exportSel" aria-label="Export report">
            <option value="">Export</option>
            <option value="html">HTML</option>
            <option value="md">Markdown</option>
            <option value="json">JSON</option>
          </select>
          <button id="focusBtn" title="Show or hide the issues panel">${state.settings.focus ? 'Show issues' : 'Full width'}</button>
        </div>
        <div id="editorHost" class="editor-host"></div>
      </section>
      <section class="results" id="results"></section>
    </div>`;

  const menu = document.getElementById('addMenu');
  const closeMenu = () => { menu.open = false; };
  const bind = (id, fn) => { document.getElementById(id).onclick = () => { closeMenu(); fn(); }; };
  bind('newBtn', newFile);
  bind('uploadBtn', () => document.getElementById('fileInput').click());
  bind('folderBtn', () => document.getElementById('folderInput').click());
  bind('pathBtn', openPathDialog);
  bind('sampleBtn', loadSamples);
  bind('clearBtn', clearAll);
  document.getElementById('reviewBtn').onclick = runReview;
  document.getElementById('fixBtn').onclick = runFix;
  document.getElementById('exportSel').onchange = e => { const f = e.target.value; e.target.value = ''; if (f) runExport(f); };
  document.getElementById('tabEdit').onclick = () => setMode('edit');
  document.getElementById('tabReview').onclick = () => setMode('review');
  document.getElementById('wrapCb').onchange = e => { state.settings.wrap = e.target.checked; saveSettings(); drawEditor(); };
  document.getElementById('focusBtn').onclick = () => {
    state.settings.focus = !state.settings.focus;
    saveSettings();
    document.getElementById('workspace').classList.toggle('focus', state.settings.focus);
    document.getElementById('focusBtn').textContent = state.settings.focus ? 'Show issues' : 'Full width';
  };
  drawFileList();
  drawEditor();
  drawResults();
}

function setMode(m) {
  if (m === 'review' && !state.report) { toast('Press Review first.'); return; }
  state.mode = m;
  drawEditor();
}

// ------------------------------------------------------------ file list

function drawFileList() {
  const bar = document.getElementById('fileList');
  if (!bar) return;
  if (!state.files.length) {
    bar.innerHTML = '<span class="muted small" style="padding:.4rem .6rem">No files yet - use <b>+ Add file</b></span>';
    return;
  }
  bar.innerHTML = state.files.map((f, i) => {
    const r = state.reportByFile[f.name];
    const grade = r ? `<span class="grade-pill ${gradeClass(r.grade)}" title="${r.score}/100">${r.grade}</span>` : '';
    const short = f.name.split('/').pop();
    return `<div class="ftab ${i === state.active ? 'active' : ''}" data-i="${i}" title="${esc(f.name)} (double-click to rename)" role="tab">
      ${grade}<span class="fname">${esc(short)}</span>
      <button class="x" data-del="${i}" aria-label="Close ${esc(short)}">&times;</button></div>`;
  }).join('');
  bar.querySelectorAll('.ftab').forEach(t => {
    t.onclick = e => {
      if (e.target.dataset.del !== undefined) { removeFile(Number(e.target.dataset.del)); return; }
      state.active = Number(t.dataset.i);
      saveDraft();
      drawFileList();
      drawEditor();
      drawResults();
    };
  });
  bar.querySelectorAll('.ftab').forEach(t => {
    t.ondblclick = () => {
      const f = state.files[Number(t.dataset.i)];
      openModal('Rename file', `<div class="field"><label for="rn">File name</label><input id="rn" value="${esc(f.name)}" spellcheck="false">
        <span class="hint">The extension picks the language: .java, .js, .ts or .py</span></div>`,
        [{ label: 'Cancel' }, { label: 'Rename', primary: true, onClick: () => { state.active = Number(t.dataset.i); renameActive(document.getElementById('rn').value); drawEditor(); drawResults(); } }]);
    };
  });
  const act = bar.querySelector('.ftab.active');
  if (act) act.scrollIntoView({ block: 'nearest', inline: 'nearest' });
}

function addFiles(list, replace) {
  if (replace) state.files = [];
  list.forEach(f => {
    const existing = state.files.findIndex(x => x.name === f.name);
    if (existing >= 0) state.files[existing] = f; else state.files.push(f);
  });
  state.active = Math.max(0, state.files.findIndex(x => x.name === (list[0] && list[0].name)));
  markStale();
  saveDraft();
  drawFileList();
  drawEditor();
  drawResults();
}

function newFile() {
  openModal('New file', `
    <div class="field"><label for="nf">File name</label>
      <input id="nf" value="Main.java" spellcheck="false">
      <span class="hint">The extension picks the language: .java, .js, .ts or .py</span></div>`,
    [{ label: 'Cancel' }, {
      label: 'Create', primary: true, onClick: () => {
        const name = document.getElementById('nf').value.trim() || 'Main.java';
        addFiles([{ name, content: '' }]);
        state.mode = 'edit';
        drawEditor();
        const ta = document.getElementById('code');
        if (ta) ta.focus();
      }
    }]);
}

function removeFile(i) {
  state.files.splice(i, 1);
  if (state.active >= state.files.length) state.active = Math.max(0, state.files.length - 1);
  saveDraft();
  drawFileList();
  drawEditor();
  drawResults();
}

function renameActive(name) {
  const f = activeFile();
  name = name.trim();
  if (!f || !name) return;
  f.name = name;
  markStale();
  saveDraft();
  drawFileList();
}

function clearAll() {
  if (!state.files.length) return;
  openModal('Remove all files?', '<p>This only clears the list in the browser. Files on your computer are not touched.</p>',
    [{ label: 'Cancel' }, {
      label: 'Remove all', danger: true, onClick: () => {
        state.files = [];
        state.report = null;
        state.reportByFile = {};
        state.mode = 'edit';
        updateScoreChip();
        saveDraft();
        renderReview();
      }
    }]);
}

async function readFiles(fileList) {
  const picked = Array.from(fileList).filter(f => CODE_EXT.test(f.name) && !/(^|\/)(node_modules|target|build|out|\.git)\//.test(f.webkitRelativePath || ''));
  if (!picked.length) { toast('No .java / .js / .ts / .py files found.', true); return; }
  if (picked.length > 500) { toast('Too many files (' + picked.length + '). Choose a smaller folder.', true); return; }
  const files = await Promise.all(picked.map(async f => ({ name: f.webkitRelativePath || f.name, content: await f.text() })));
  files.sort((a, b) => a.name.localeCompare(b.name));
  addFiles(files, false);
  toast(files.length + ' file(s) added. Press Review.');
}

document.getElementById('fileInput').onchange = e => { readFiles(e.target.files); e.target.value = ''; };
document.getElementById('folderInput').onchange = e => { readFiles(e.target.files); e.target.value = ''; };

async function loadSamples() {
  try {
    const data = await api('/api/samples');
    if (!data.files.length) { toast('No sample files found in the samples folder.', true); return; }
    addFiles(data.files, false);
    toast('Sample files loaded. Press Review.');
  } catch (e) {
    toast(e.message, true);
  }
}

function openPathDialog() {
  const last = restore('acr.lastPath', '');
  openModal('Load from a folder on this computer', `
    <div class="field"><label for="pp">File or folder path</label>
      <input id="pp" value="${esc(last)}" placeholder="D:\\Web-Development\\Java Projects\\my-project" spellcheck="false">
      <span class="hint">The backend reads the files directly, so you can paste any path.</span></div>
    <label class="toggle"><input type="checkbox" id="pgit"> Only files changed in Git (like a pull request)</label>
    <div class="field" style="margin-top:.8rem"><label for="pbase">Compare with branch (optional)</label>
      <input id="pbase" placeholder="empty = my uncommitted changes, or e.g. master" spellcheck="false"></div>`,
    [{ label: 'Cancel' }, {
      label: 'Load files', primary: true, onClick: async () => {
        const path = document.getElementById('pp').value.trim();
        if (!path) { toast('Enter a path.', true); return true; }
        store('acr.lastPath', path);
        try {
          const data = await api('/api/load-path', { path, git: document.getElementById('pgit').checked, base: document.getElementById('pbase').value.trim() });
          if (!data.files.length) { toast('No code files found there.', true); return true; }
          addFiles(data.files, true);
          toast(data.files.length + ' file(s) loaded' + (data.skipped ? ', ' + data.skipped + ' too large skipped' : '') + '. Press Review.');
        } catch (e) {
          toast(e.message, true);
          return true;
        }
      }
    }]);
}

// --------------------------------------------------------------- editor

function drawEditor() {
  const host = document.getElementById('editorHost');
  if (!host) return;
  const f = activeFile();
  document.getElementById('tabEdit').classList.toggle('on', state.mode === 'edit');
  document.getElementById('tabReview').classList.toggle('on', state.mode === 'review');

  if (!f) {
    host.innerHTML = `<div class="empty-state" style="height:100%;display:grid;place-content:center">
      <b>Start by adding some code</b>
      Click <b>+ Add file</b> to paste code, upload files or a whole folder,<br>or load the sample files.</div>`;
    return;
  }

  if (state.mode === 'edit') {
    const wrap = state.settings.wrap !== false;
    host.innerHTML = `<div class="editor ${wrap ? 'wrap' : ''}"><div class="gutter" id="gutter"></div>
      <textarea id="code" spellcheck="false" autocapitalize="off" autocomplete="off" aria-label="Code editor"></textarea></div>`;
    const ta = document.getElementById('code');
    const gutter = document.getElementById('gutter');
    ta.value = f.content;
    const updateGutter = () => {
      const n = ta.value.split('\n').length;
      let s = '';
      for (let i = 1; i <= n; i++) s += i + '\n';
      gutter.textContent = s;
    };
    updateGutter();
    ta.addEventListener('input', () => {
      f.content = ta.value;
      updateGutter();
      markStale();
      saveDraftSoon();
    });
    ta.addEventListener('scroll', () => { gutter.scrollTop = ta.scrollTop; });
    ta.addEventListener('keydown', e => {
      if (e.key === 'Tab' && !e.shiftKey) {
        e.preventDefault();
        const s = ta.selectionStart;
        ta.setRangeText('    ', s, ta.selectionEnd, 'end');
        ta.dispatchEvent(new Event('input'));
      } else if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) {
        e.preventDefault();
        runReview();
      }
    });
    return;
  }

  // reviewed view: highlighted code with colored lines
  const r = state.reportByFile[f.name];
  const byLine = {};
  if (r) r.issues.forEach(i => { (byLine[i.line] = byLine[i.line] || []).push(i); });
  const lines = highlight(f.content, langOf(f.name));
  host.innerHTML = `<div class="codeview ${state.settings.wrap !== false ? 'wrap' : ''}" id="codeview">${lines.map((h, idx) => {
    const n = idx + 1;
    const list = byLine[n];
    if (!list) return `<div class="cl" id="L${n}"><span class="no">${n}</span><span class="tx">${h}</span></div>`;
    const worst = list.map(i => i.severity).sort((a, b) => sevRank(a) - sevRank(b))[0];
    const title = list.map(i => '[' + i.severity + '] ' + i.message).join('\n');
    return `<div class="cl has s-${worst}" id="L${n}" data-line="${n}" title="${esc(title)}"><span class="no">${n}</span><span class="tx">${h}</span></div>`;
  }).join('')}</div>`;
  host.querySelectorAll('.cl.has').forEach(el => {
    el.onclick = () => {
      const line = Number(el.dataset.line);
      const issue = r.issues.find(i => i.line === line);
      if (issue) selectIssue(f.name, issue, true);
    };
  });
  if (state.selected) showInline();
}

let draftTimer;
function saveDraftSoon() {
  clearTimeout(draftTimer);
  draftTimer = setTimeout(saveDraft, 600);
}

function markStale() {
  if (state.report && !state.stale) {
    state.stale = true;
    const note = document.getElementById('staleNote');
    if (note) note.classList.remove('hidden');
  }
}

/** Shows the explanation of the selected issue right under its line. */
function showInline() {
  document.querySelectorAll('.inline-issue').forEach(e => e.remove());
  if (!state.selected) return;
  const [file, line, rule] = state.selected.split('|');
  const f = activeFile();
  if (!f || f.name !== file) return;
  const r = state.reportByFile[file];
  const issue = r && r.issues.find(i => String(i.line) === line && i.rule === rule);
  const row = document.getElementById('L' + line);
  if (!issue || !row) return;
  const box = document.createElement('div');
  box.className = 'inline-issue';
  box.innerHTML = `<span class="sev-badge sev-${issue.severity}">${issue.severity}</span> <b>${esc(issue.message)}</b><br>
    <span class="muted">Fix:</span> ${esc(issue.suggestion)}`;
  row.after(box);
  row.classList.remove('flash');
  void row.offsetWidth;
  row.classList.add('flash');
  const cv = document.getElementById('codeview');
  cv.scrollTop = Math.max(0, row.offsetTop - cv.clientHeight / 3);
}

// -------------------------------------------------------------- results

function scoreRing(score, grade, size) {
  const r = 38;
  const c = 2 * Math.PI * r;
  const color = score >= 80 ? 'var(--good)' : score >= 70 ? 'var(--minor)' : 'var(--critical)';
  return `<div class="ring" style="${size ? 'width:' + size + 'px;height:' + size + 'px' : ''}">
    <svg viewBox="0 0 84 84" style="${size ? 'width:' + size + 'px;height:' + size + 'px' : ''}">
      <circle class="track" cx="42" cy="42" r="${r}"/>
      <circle cx="42" cy="42" r="${r}" style="stroke:${color}" stroke-dasharray="${(c * score / 100).toFixed(1)} ${c.toFixed(1)}"/>
    </svg>
    <div class="val"><div><b>${esc(grade)}</b><span>${Number(score).toFixed(1)}</span></div></div></div>`;
}

function verdict(score) {
  if (score >= 90) return 'Excellent - ready to merge';
  if (score >= 80) return 'Good - small fixes recommended';
  if (score >= 70) return 'Fair - fix the important issues first';
  if (score >= 60) return 'Poor - needs real changes';
  return 'Failing - do not merge yet';
}

function drawResults() {
  const host = document.getElementById('results');
  if (!host) return;
  const rep = state.report;
  if (!rep) {
    host.innerHTML = `<div class="card"><div class="empty-state"><b>No review yet</b>
      Results appear here: score, grade and every issue with a fix.<br><br>
      <span class="small">Tip: press <b>Ctrl + Enter</b> in the editor to review.</span></div></div>`;
    return;
  }
  const f = activeFile();
  const fr = f && state.reportByFile[f.name];
  const scope = fr ? fr : null;
  const counts = {};
  SEVERITIES.forEach(s => { counts[s] = 0; });
  const issues = scope ? scope.issues : [];
  issues.forEach(i => counts[i.severity]++);
  const shown = issues.filter(i => state.filter.has(i.severity));

  host.innerHTML = `
    <div class="card card-pad">
      ${scope ? `<div class="scorebox">${scoreRing(scope.score, scope.grade)}
        <div><div class="verdict">${verdict(scope.score)}</div>
          <div class="small muted">${esc(scope.file)} &middot; ${scope.metrics.codeLines} code lines &middot; ${scope.metrics.methods} methods</div>
          <div class="sevcounts">${SEVERITIES.map(s => `<span class="sev-badge sev-${s}${counts[s] ? '' : ' zero'}" title="${SEV_TEXT[s]}">${counts[s]} ${s}</span>`).join('')}</div>
        </div></div>` : '<p class="muted">This file was not part of the last review. Press Review again.</p>'}
      ${state.files.length > 1 ? `<p class="small muted" style="margin:.7rem 0 0">All ${rep.files.length} files: <b>${rep.grade}</b> (${rep.overallScore.toFixed(1)}/100) &middot; <a href="#/report">full report</a></p>` : ''}
      <p class="small" id="staleNote" style="margin:.6rem 0 0;color:var(--major)" ${state.stale ? '' : 'class="hidden"'}>Code changed since the last review. Press Review again.</p>
    </div>
    <div class="card" style="display:flex;flex-direction:column;min-height:0;flex:1">
      <div class="card-head"><h3>Issues <span class="muted small">(${shown.length}${shown.length !== issues.length ? ' of ' + issues.length : ''})</span></h3>
        ${scope && scope.issues.some(i => i.autoFixable) ? '<span class="chip">some are auto-fixable</span>' : ''}</div>
      <div class="filterbar">${SEVERITIES.map(s => `<button class="${state.filter.has(s) ? 'on' : ''}" data-f="${s}">${s} ${counts[s]}</button>`).join('')}</div>
      <div class="issues" id="issueList">${shown.length ? shown.map(i => issueHtml(i, scope.file)).join('') :
        `<div class="empty-state">${issues.length ? 'All issues are hidden by the filter.' : '<b>No issues found</b>This file follows all enabled rules. Nice work!'}</div>`}</div>
    </div>
    ${scope && scope.aiReview ? `<div class="card card-pad"><h3 style="margin-bottom:.5rem">Claude AI review</h3><div class="claude-box">${esc(scope.aiReview)}</div></div>` : ''}`;

  if (!state.stale) { const n = document.getElementById('staleNote'); if (n) n.classList.add('hidden'); }
  host.querySelectorAll('.filterbar button').forEach(b => {
    b.onclick = () => {
      const s = b.dataset.f;
      if (state.filter.has(s)) state.filter.delete(s); else state.filter.add(s);
      drawResults();
    };
  });
  host.querySelectorAll('.issue').forEach(el => {
    el.onclick = e => {
      if (e.target.closest('.detail')) return;
      const issue = issues.find(i => issueKey(i, scope.file) === el.dataset.key);
      if (issue) selectIssue(scope.file, issue, false);
    };
  });
}

function issueHtml(i, file) {
  const key = issueKey(i, file);
  const sel = state.selected === key;
  const rule = state.rulesById[i.rule];
  let detail = '';
  if (sel) {
    detail = `<div class="detail">
      <p><b>How to fix:</b> ${esc(i.suggestion)}</p>
      ${rule && rule.why ? `<p><b>Why it matters:</b> ${esc(rule.why)}</p>
        <div class="ba"><small>Before</small><pre class="bad">${esc(rule.before)}</pre>
        <small>After</small><pre class="good">${esc(rule.after)}</pre></div>` : (rule ? `<p class="muted">${esc(rule.description)}</p>` : '')}
      ${i.autoFixable ? '<p class="small muted" style="margin-top:.5rem">This one can be fixed with the <b>Auto-fix</b> button.</p>' : ''}
    </div>`;
  }
  return `<div class="issue ${sel ? 'sel' : ''}" data-key="${esc(key)}">
    <div class="top"><span class="sev-badge sev-${i.severity}">${i.severity}</span>
      <span class="chip">${esc(i.category)}</span>
      <span class="meta">line ${i.line}</span></div>
    <div class="msg">${esc(i.message)}</div>
    <div class="meta">${esc(i.rule)}${i.autoFixable ? ' &middot; auto-fixable' : ''}</div>
    ${detail}</div>`;
}

function selectIssue(file, issue, fromCode) {
  const key = issueKey(issue, file);
  state.selected = state.selected === key && !fromCode ? null : key;
  const idx = state.files.findIndex(f => f.name === file);
  if (idx >= 0 && idx !== state.active) {
    state.active = idx;
    drawFileList();
  }
  if (state.mode !== 'review' || idx !== state.active) state.mode = 'review';
  drawEditor();
  drawResults();
  const el = document.querySelector('.issue.sel');
  if (el && fromCode) el.scrollIntoView({ block: 'nearest' });
}

function updateScoreChip() {
  const chip = document.getElementById('scoreChip');
  const rep = state.report;
  if (!rep) { chip.classList.add('hidden'); return; }
  chip.classList.remove('hidden');
  chip.innerHTML = `<span class="grade-pill ${gradeClass(rep.grade)}">${rep.grade}</span>
    <span class="num">${rep.overallScore.toFixed(1)}/100</span>
    <span class="muted small">${rep.files.length} file(s), ${rep.files.reduce((n, f) => n + f.issues.length, 0)} issues</span>`;
}

// -------------------------------------------------------------- actions

function setBusy(btnId, busy, label) {
  const b = document.getElementById(btnId);
  if (!b) return;
  if (busy) {
    b.dataset.label = b.textContent;
    b.innerHTML = '<span class="spinner"></span> ' + label;
    b.disabled = true;
  } else {
    b.textContent = b.dataset.label || b.textContent;
    b.disabled = false;
  }
}

async function runReview() {
  if (state.busy) return;
  if (!state.files.length) { toast('Add some code first.', true); return; }
  const useClaude = !!(state.settings.useClaude && state.info && state.info.claudeAvailable);
  state.busy = true;
  setBusy('reviewBtn', true, useClaude ? 'Asking Claude...' : 'Reviewing...');
  try {
    const first = state.files[0].name;
    const folder = first.includes('/') && state.files.every(f => f.name.split('/')[0] === first.split('/')[0]) ? first.split('/')[0] + '/' : '';
    const target = state.files.length === 1 ? first
      : (folder || state.files.slice(0, 2).map(f => f.name).join(', ') + (state.files.length > 2 ? ', ...' : '')) + ' (' + state.files.length + ' files)';
    const data = await api('/api/review', reportPayload({ useClaude, saveHistory: state.settings.saveHistory, target }));
    state.report = data.report;
    state.reportByFile = {};
    data.report.files.forEach(fr => { state.reportByFile[fr.file] = fr; });
    state.stale = false;
    state.selected = null;
    state.mode = 'review';
    updateScoreChip();
    drawFileList();
    drawEditor();
    drawResults();
    const total = data.report.files.reduce((n, f) => n + f.issues.length, 0);
    toast('Review done: grade ' + data.report.grade + ', ' + total + ' issue(s).');
  } catch (e) {
    toast(e.message, true);
  } finally {
    state.busy = false;
    setBusy('reviewBtn', false);
  }
}

async function runFix() {
  if (!state.files.length) { toast('Add some code first.', true); return; }
  setBusy('fixBtn', true, 'Checking...');
  try {
    const data = await api('/api/fix', reportPayload());
    if (!data.files.length) { toast('Nothing can be fixed automatically. The remaining issues need a human.'); return; }
    const body = `<p>These <b>${data.totalChanges}</b> safe change(s) will be made. Security issues are never auto-changed - they need your decision.</p>` +
      data.files.map(f => `<h3 style="margin:1rem 0 .4rem">${esc(f.name)}</h3>` + f.changes.map(c => `
        <div class="diff"><div class="h">${c.line > 0 ? 'Line ' + c.line : 'Whole file'} &middot; ${esc(c.rule)}</div>
        <div class="m">- ${esc(c.before.trim())}</div><div class="p">+ ${esc(c.after.trim())}</div></div>`).join('')).join('');
    openModal('Auto-fix preview', body, [{ label: 'Cancel' }, {
      label: 'Apply fixes', primary: true, onClick: async () => {
        data.files.forEach(fx => {
          const f = state.files.find(x => x.name === fx.name);
          if (f) f.content = fx.content;
        });
        saveDraft();
        toast('Fixes applied. Reviewing again...');
        await runReview();
      }
    }]);
  } catch (e) {
    toast(e.message, true);
  } finally {
    setBusy('fixBtn', false);
  }
}

async function runExport(format) {
  if (!state.files.length) { toast('Add some code first.', true); return; }
  try {
    const res = await fetch('/api/export?format=' + encodeURIComponent(format), {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(reportPayload())
    });
    if (!res.ok) throw new Error((await res.json()).error || 'Export failed');
    const blob = await res.blob();
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = 'review-report.' + format;
    document.body.appendChild(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 5000);
    toast('Report downloaded: review-report.' + format);
  } catch (e) {
    toast(e.message, true);
  }
}

// ========================================================== REPORT PAGE

function renderReport() {
  const rep = state.report;
  if (!rep) {
    view.innerHTML = `<div class="page-head"><div><h1>Full report</h1><p>The summary of your last review.</p></div></div>
      <div class="card"><div class="empty-state"><b>No review yet</b>Go to <a href="#/review">Review code</a>, add some code and press Review.</div></div>`;
    return;
  }
  const cats = {};
  const all = [];
  rep.files.forEach(f => f.issues.forEach(i => { all.push(Object.assign({ file: f.file }, i)); cats[i.category] = (cats[i.category] || 0) + 1; }));
  const catOrder = ['Security', 'Bug Risk', 'Performance', 'Maintainability', 'Style', 'Best Practice', 'Documentation'];
  const maxCat = Math.max(1, ...catOrder.map(c => cats[c] || 0));
  const methods = [];
  rep.files.forEach(f => f.methodDetails.forEach(m => methods.push(Object.assign({ file: f.file }, m))));
  methods.sort((a, b) => b.complexity - a.complexity);
  const claude = rep.files.filter(f => f.aiReview);

  view.innerHTML = `
    <div class="page-head">
      <div><h1>Full report</h1><p>${esc(rep.mode)} &middot; ${new Date(rep.createdAt).toLocaleString()}</p></div>
      <div class="editor-tools"><button id="repHtml">Download HTML</button><button id="repMd">Download Markdown</button></div>
    </div>
    <div class="grid" style="grid-template-columns:auto 1fr;align-items:center;margin-bottom:1rem">
      <div class="card card-pad">${scoreRing(rep.overallScore, rep.grade, 110)}</div>
      <div class="grid cols-4">${SEVERITIES.map(s => `<div class="card stat ${s}"><b class="num">${rep.counts[s]}</b><span>${s}</span><small>${SEV_TEXT[s]}</small></div>`).join('')}</div>
    </div>
    <div class="grid cols-2" style="margin-bottom:1rem">
      <div class="card card-pad"><h2 style="margin-bottom:.6rem">AI reviewer summary</h2>
        <ul class="summary-list">${rep.summary.map(l => `<li class="${/^\d+\./.test(l) ? 'sub' : ''}">${esc(l)}</li>`).join('')}</ul></div>
      <div class="card card-pad"><h2 style="margin-bottom:.6rem">Issues by category</h2>
        <div class="bars">${catOrder.map(c => `<div class="bar"><span>${c}</span><div class="track"><div class="fill" style="width:${((cats[c] || 0) * 100 / maxCat).toFixed(0)}%"></div></div><b>${cats[c] || 0}</b></div>`).join('')}</div></div>
    </div>
    <div class="card" style="margin-bottom:1rem">
      <div class="card-head"><h2>Files</h2><span class="muted small">Click a file to open it</span></div>
      <div class="table-wrap"><table>
        <thead><tr><th>Grade</th><th>File</th><th class="r">Score</th><th class="r">Issues</th><th class="r">Critical</th><th class="r">Major</th><th class="r">Code lines</th><th class="r">Methods</th><th class="r">Avg complexity</th><th class="r">Comments</th></tr></thead>
        <tbody>${rep.files.slice().sort((a, b) => a.score - b.score).map(f => {
          const c = n => f.issues.filter(i => i.severity === n).length;
          return `<tr class="click" data-file="${esc(f.file)}"><td><span class="grade-pill ${gradeClass(f.grade)}">${f.grade}</span></td>
            <td class="mono small">${esc(f.file)}</td><td class="r num">${f.score.toFixed(1)}</td><td class="r num">${f.issues.length}</td>
            <td class="r num">${c('CRITICAL')}</td><td class="r num">${c('MAJOR')}</td><td class="r num">${f.metrics.codeLines}</td>
            <td class="r num">${f.metrics.methods}</td><td class="r num">${f.metrics.averageComplexity.toFixed(1)}</td>
            <td class="r num">${f.metrics.codeLines + f.metrics.commentLines ? Math.round(f.metrics.commentLines * 100 / (f.metrics.codeLines + f.metrics.commentLines)) : 0}%</td></tr>`;
        }).join('')}</tbody></table></div>
    </div>
    <div class="card" style="margin-bottom:1rem">
      <div class="card-head"><h2>Most complex methods</h2><span class="muted small">Cyclomatic complexity = 1 + number of decisions (if, for, while, case, catch, &amp;&amp;, ||, ?)</span></div>
      <div class="table-wrap"><table>
        <thead><tr><th>Method</th><th>File</th><th class="r">Line</th><th class="r">Lines</th><th class="r">Params</th><th class="r">Complexity</th><th class="r">Nesting</th></tr></thead>
        <tbody>${methods.slice(0, 15).map(m => `<tr><td class="mono small">${esc(m.name)}()</td><td class="mono small">${esc(m.file)}</td>
          <td class="r num">${m.line}</td><td class="r num">${m.lines}</td><td class="r num">${m.params}</td>
          <td class="r num ${m.complexity > (state.info ? state.info.defaults.maxComplexity : 10) ? 'hot' : ''}">${m.complexity}</td><td class="r num">${m.nesting}</td></tr>`).join('') ||
          '<tr><td colspan="7" class="muted">No methods found.</td></tr>'}</tbody></table></div>
    </div>
    ${claude.length ? `<div class="card card-pad"><h2 style="margin-bottom:.6rem">Claude AI reviews</h2>
      ${claude.map(f => `<h3 class="mono" style="margin:.8rem 0 .4rem">${esc(f.file)}</h3><div class="claude-box">${esc(f.aiReview)}</div>`).join('')}</div>` : ''}`;

  view.querySelectorAll('tr[data-file]').forEach(tr => {
    tr.onclick = () => {
      const idx = state.files.findIndex(f => f.name === tr.dataset.file);
      if (idx >= 0) { state.active = idx; state.mode = 'review'; }
      location.hash = '#/review';
    };
  });
  document.getElementById('repHtml').onclick = () => runExport('html');
  document.getElementById('repMd').onclick = () => runExport('md');
}

// =========================================================== RULES PAGE

function renderRules() {
  view.innerHTML = `
    <div class="page-head">
      <div><h1>Rules</h1><p>${state.rules.length} checks. Untick a rule to switch it off for your reviews.</p></div>
      <div class="editor-tools"><button id="enableAll">Enable all</button></div>
    </div>
    <div class="rules-tools">
      <input type="search" id="ruleSearch" placeholder="Search rules, e.g. sql, password, loop" aria-label="Search rules">
      <select id="ruleCat" aria-label="Category"><option value="">All categories</option>
        ${[...new Set(state.rules.map(r => r.category))].map(c => `<option>${esc(c)}</option>`).join('')}</select>
      <select id="ruleSev" aria-label="Severity"><option value="">All severities</option>${SEVERITIES.map(s => `<option>${s}</option>`).join('')}</select>
    </div>
    <div class="card" id="ruleList"></div>`;

  const draw = () => {
    const q = document.getElementById('ruleSearch').value.toLowerCase();
    const cat = document.getElementById('ruleCat').value;
    const sev = document.getElementById('ruleSev').value;
    const disabled = new Set(state.settings.disabledRules);
    const list = state.rules.filter(r => (!cat || r.category === cat) && (!sev || r.severity === sev) &&
      (!q || (r.id + ' ' + r.name + ' ' + r.description + ' ' + r.why).toLowerCase().includes(q)));
    let html = '';
    let lastCat = '';
    list.forEach(r => {
      if (r.category !== lastCat) { html += `<div class="cat-head">${esc(r.category)}</div>`; lastCat = r.category; }
      html += `<div class="rule ${disabled.has(r.id) ? 'disabled' : ''}">
        <input type="checkbox" data-rule="${r.id}" ${disabled.has(r.id) ? '' : 'checked'} aria-label="Enable ${esc(r.name)}" style="margin-top:.2rem;width:1.05rem;height:1.05rem;accent-color:var(--accent)">
        <div><h3>${esc(r.name)} <span class="sev-badge sev-${r.severity}">${r.severity}</span>
          <span class="meta mono small muted">${r.id}</span>${r.autoFixable ? '<span class="chip">auto-fix</span>' : ''}</h3>
          <p>${esc(r.description)}</p>
          ${r.why ? `<details><summary>Why it matters + example</summary><p style="margin:.4rem 0">${esc(r.why)}</p>
            <div class="ba"><small>Before</small><pre class="bad">${esc(r.before)}</pre><small>After</small><pre class="good">${esc(r.after)}</pre></div></details>` : ''}
        </div></div>`;
    });
    document.getElementById('ruleList').innerHTML = html || '<div class="empty-state">No rule matches your search.</div>';
    document.querySelectorAll('[data-rule]').forEach(cb => {
      cb.onchange = () => {
        const set = new Set(state.settings.disabledRules);
        if (cb.checked) set.delete(cb.dataset.rule); else set.add(cb.dataset.rule);
        state.settings.disabledRules = [...set];
        saveSettings();
        cb.closest('.rule').classList.toggle('disabled', !cb.checked);
        toast(cb.checked ? 'Rule enabled.' : 'Rule switched off. Press Review again to apply.');
      };
    });
  };
  ['ruleSearch', 'ruleCat', 'ruleSev'].forEach(id => document.getElementById(id).addEventListener('input', draw));
  document.getElementById('enableAll').onclick = () => { state.settings.disabledRules = []; saveSettings(); draw(); toast('All rules enabled.'); };
  draw();
}

// ========================================================= HISTORY PAGE

async function renderHistory() {
  view.innerHTML = `<div class="page-head"><div><h1>History</h1><p>Every review score is saved, so you can see your code getting better.</p></div>
    <div class="editor-tools"><button id="clearHist" class="danger">Clear history</button></div></div>
    <div id="histBody"><div class="card"><div class="empty-state"><span class="spinner"></span></div></div></div>`;
  document.getElementById('clearHist').onclick = () => openModal('Clear history?', '<p>All saved scores will be deleted.</p>',
    [{ label: 'Cancel' }, { label: 'Clear', danger: true, onClick: async () => { await api('/api/history', null, 'DELETE'); renderHistory(); } }]);
  let entries;
  try {
    entries = (await api('/api/history')).entries;
  } catch (e) {
    document.getElementById('histBody').innerHTML = `<div class="card"><div class="empty-state">${esc(e.message)}</div></div>`;
    return;
  }
  if (!entries.length) {
    document.getElementById('histBody').innerHTML = '<div class="card"><div class="empty-state"><b>No history yet</b>Run a review and it will show up here.</div></div>';
    return;
  }
  const last = entries.slice(-30);
  const W = 800, H = 220, P = 34;
  const x = i => P + (last.length === 1 ? (W - 2 * P) / 2 : i * (W - 2 * P) / (last.length - 1));
  const y = v => H - P - (v / 100) * (H - 2 * P);
  const pts = last.map((e, i) => x(i).toFixed(1) + ',' + y(Number(e.score)).toFixed(1)).join(' ');
  const chart = `<svg class="chart" viewBox="0 0 ${W} ${H}" preserveAspectRatio="xMidYMid meet" role="img" aria-label="Score over time">
    ${[0, 50, 70, 90, 100].map(v => `<line class="axis" x1="${P}" x2="${W - P}" y1="${y(v)}" y2="${y(v)}"/><text class="lbl" x="4" y="${y(v) + 4}">${v}</text>`).join('')}
    <polyline class="line" points="${pts}"/>
    ${last.map((e, i) => `<circle class="pt" cx="${x(i)}" cy="${y(Number(e.score))}" r="4"><title>${esc(e.target)}: ${e.score} (${e.grade})</title></circle>`).join('')}
  </svg>`;
  document.getElementById('histBody').innerHTML = `
    <div class="card card-pad" style="margin-bottom:1rem"><h2 style="margin-bottom:.4rem">Score over time</h2>
      <p class="small muted">Last ${last.length} reviews. 90+ is grade A, below 60 is F.</p>${chart}</div>
    <div class="card"><div class="table-wrap"><table>
      <thead><tr><th>When</th><th>What</th><th>Grade</th><th class="r">Score</th><th class="r">Files</th><th class="r">Code lines</th><th class="r">Critical</th><th class="r">Major</th><th class="r">Minor</th><th class="r">Info</th></tr></thead>
      <tbody>${entries.slice().reverse().map(e => `<tr><td class="small">${esc(e.timestamp.replace('T', ' '))}</td>
        <td class="mono small">${esc(e.target.replace(/^web:/, ''))}</td><td><span class="grade-pill ${gradeClass(e.grade)}">${esc(e.grade)}</span></td>
        <td class="r num">${esc(e.score)}</td><td class="r num">${esc(e.files)}</td><td class="r num">${esc(e.codeLines)}</td>
        <td class="r num">${esc(e.critical)}</td><td class="r num">${esc(e.major)}</td><td class="r num">${esc(e.minor)}</td><td class="r num">${esc(e.info)}</td></tr>`).join('')}</tbody>
    </table></div></div>`;
}

// ======================================================== SETTINGS PAGE

const LIMITS = [
  ['maxMethodLines', 'Max method length (lines)', 'Longer methods are reported as LONG_METHOD'],
  ['maxComplexity', 'Max cyclomatic complexity', 'Higher is reported as HIGH_COMPLEXITY'],
  ['maxParameters', 'Max parameters per method', 'More is reported as TOO_MANY_PARAMETERS'],
  ['maxNesting', 'Max nesting depth', 'Deeper is reported as DEEP_NESTING'],
  ['maxLineLength', 'Max line length (characters)', 'Longer lines are reported as LONG_LINE'],
  ['maxClassLines', 'Max file length (lines)', 'Longer files are reported as LARGE_FILE'],
  ['duplicateBlockLines', 'Duplicate block size (lines)', 'Copy-pasted blocks this long are reported']
];

function renderSettings() {
  const d = state.info ? state.info.defaults : {};
  const s = state.settings;
  view.innerHTML = `
    <div class="page-head"><div><h1>Settings</h1><p>Saved in this browser. They apply to your next review.</p></div>
      <div class="editor-tools"><button id="resetSet">Reset to defaults</button></div></div>
    <div class="card card-pad" style="margin-bottom:1rem">
      <h2 style="margin-bottom:.8rem">General</h2>
      <div class="settings-grid">
        <div class="field"><label for="minSev">Show issues from</label>
          <select id="minSev">${SEVERITIES.map(v => `<option value="${v}" ${s.minSeverity === v ? 'selected' : ''}>${v} and above</option>`).join('')}</select>
          <span class="hint">Choose MAJOR to hide small style notes.</span></div>
        <div class="field"><label for="themeSel">Theme</label>
          <select id="themeSel"><option value="">Same as my system</option><option value="light">Light</option><option value="dark">Dark</option></select></div>
      </div>
      <label class="toggle"><input type="checkbox" id="saveHist" ${s.saveHistory ? 'checked' : ''}> Save every review score to History</label>
    </div>
    <div class="card card-pad" style="margin-bottom:1rem">
      <h2 style="margin-bottom:.8rem">Limits</h2>
      <div class="settings-grid">${LIMITS.map(([k, label, hint]) => `<div class="field"><label for="lim-${k}">${label}</label>
        <input type="number" min="1" id="lim-${k}" data-lim="${k}" value="${esc((s.limits && s.limits[k]) || d[k] || '')}">
        <span class="hint">${hint}. Default ${d[k] || '-'}.</span></div>`).join('')}</div>
    </div>
    <div class="card card-pad">
      <h2 style="margin-bottom:.5rem">Claude AI</h2>
      ${state.info && state.info.claudeAvailable
        ? `<p><span class="dot on"></span>Connected (${esc(state.info.claudeModel)}).</p>
           <label class="toggle"><input type="checkbox" id="useClaudeSet" ${s.useClaude ? 'checked' : ''}> Also ask Claude for a deeper review</label>`
        : `<p><span class="dot off"></span>Not set up. The offline AI engine is used, which works fine on its own.</p>
           <p class="small muted">To turn it on, close the backend and start it again like this:</p>
           <pre class="claude-box mono small">set ANTHROPIC_API_KEY=your-key-here\nrun.bat</pre>`}
      <p class="small muted">Disabled rules: ${s.disabledRules.length ? esc(s.disabledRules.join(', ')) : 'none'} &middot; <a href="#/rules">manage rules</a></p>
    </div>`;

  document.getElementById('themeSel').value = restore('acr.theme', '') || '';
  document.getElementById('themeSel').onchange = e => { store('acr.theme', e.target.value); applyTheme(e.target.value); };
  document.getElementById('minSev').onchange = e => { s.minSeverity = e.target.value; saveSettings(); toast('Saved.'); };
  const uc = document.getElementById('useClaudeSet');
  if (uc) uc.onchange = e => { s.useClaude = e.target.checked; saveSettings(); toast('Saved.'); };
  document.getElementById('saveHist').onchange = e => { s.saveHistory = e.target.checked; saveSettings(); toast('Saved.'); };
  view.querySelectorAll('[data-lim]').forEach(inp => {
    inp.onchange = () => {
      const v = parseInt(inp.value, 10);
      s.limits = s.limits || {};
      if (v > 0) s.limits[inp.dataset.lim] = v; else delete s.limits[inp.dataset.lim];
      saveSettings();
      toast('Saved.');
    };
  });
  document.getElementById('resetSet').onclick = () => {
    state.settings = { minSeverity: 'INFO', disabledRules: [], saveHistory: true, limits: {} };
    saveSettings();
    renderSettings();
    toast('Settings reset.');
  };
}

// ----------------------------------------------------------------- start

async function init() {
  applyTheme(restore('acr.theme', ''));
  window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => applyTheme(restore('acr.theme', '')));
  const draft = restore('acr.files', null);
  if (draft && Array.isArray(draft.files)) {
    state.files = draft.files.filter(f => f && typeof f.name === 'string' && typeof f.content === 'string');
    state.active = Math.min(draft.active || 0, Math.max(0, state.files.length - 1));
  }
  route();
  const foot = document.getElementById('aiMode');
  try {
    const [info, rules] = await Promise.all([api('/api/info'), api('/api/rules')]);
    state.info = info;
    state.rules = rules;
    rules.forEach(r => { state.rulesById[r.id] = r; });
    document.getElementById('ruleCount').textContent = rules.length;
    foot.innerHTML = `<span class="dot on"></span><b>Backend connected</b><br>v${esc(info.version)} &middot; ${info.ruleCount} rules<br>` +
      (info.claudeAvailable ? 'Claude AI: ready' : 'AI: offline engine');
    if (!state.files.length) {
      const s = await api('/api/samples');
      const demo = s.files.find(f => f.name === 'BankService.java') || s.files[0];
      if (demo) { state.files = [demo]; state.active = 0; }
    }
    route();
  } catch (e) {
    foot.innerHTML = '<span class="dot off"></span><b>Backend not running</b><br>Start it with run.bat';
    toast(e.message, true);
  }
}

init();
