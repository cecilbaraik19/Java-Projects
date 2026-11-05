/* Prashna frontend. Plain JavaScript, no libraries. Talks to the Java backend under /api.
   Pages are chosen by the address bar: #/ask, #/catalogue, #/history, #/shelf, #/taught and, for admins,
   #/bench, #/people, #/settings.
   Security note: every piece of text from the server (questions, data, names) is inserted with esc() or
   textContent, never as raw HTML. The page has no inline scripts or styles (strict Content-Security-Policy). */
"use strict";

const $ = (s, root = document) => root.querySelector(s);
const $$ = (s, root = document) => [...root.querySelectorAll(s)];

const state = {
  me: null,
  dbs: [],
  db: null,            // current database id
  last: null,          // last answer from the server
  lastQuestion: "",
  page: 0,
  view: "table",
  histScope: "mine",
  catDb: null,
  taughtDb: null,
  schema: {},          // schema cache by database id
  editing: false,
};
const PAGE_SIZE = 50;

const PAGES_ALL = [["ask", "Ask"], ["catalogue", "Catalogue"], ["history", "Job log"], ["shelf", "Shelf"], ["taught", "Taught"]];
const PAGES_ADMIN = [["bench", "Test bench"], ["people", "People"], ["settings", "Settings"]];
const SOURCE_NAME = { RULES: "Offline engine", LEARNED: "Learned from a correction", CLAUDE: "Written by Claude", MANUAL: "Your own SQL" };

// ------------------------------------------------------------------ helpers
async function api(path, options = {}) {
  const opts = { method: options.method || "GET", headers: {}, credentials: "same-origin" };
  if (options.body !== undefined) {
    opts.headers["Content-Type"] = "application/json";
    opts.body = JSON.stringify(options.body);
  } else if (opts.method !== "GET") {
    opts.headers["Content-Type"] = "application/json";
    opts.body = "{}";
  }
  const res = await fetch("/api" + path, opts);
  const data = await res.json().catch(() => ({}));
  if (res.status === 401 && path !== "/login") { showLogin(); throw new Error(data.error || "Please sign in."); }
  if (!res.ok) { const e = new Error(data.error || "The server could not complete that request."); e.data = data; throw e; }
  return data;
}

function toast(msg) {
  const t = $("#toast");
  t.textContent = msg;
  t.classList.add("is-up");
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => t.classList.remove("is-up"), 3000);
}

function esc(s) {
  return String(s ?? "").replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
}

function fmt(v) {
  if (v === null || v === undefined) return "NULL";
  if (typeof v === "number") {
    if (Number.isInteger(v)) return Math.abs(v) >= 10000 ? v.toLocaleString("en-IN") : String(v);
    return v.toLocaleString("en-IN", { maximumFractionDigits: 2 });
  }
  return String(v);
}

function when(iso) {
  if (!iso) return "";
  const d = new Date(iso);
  return d.toLocaleDateString(undefined, { day: "2-digit", month: "short" }) + " " + d.toLocaleTimeString(undefined, { hour: "2-digit", minute: "2-digit" });
}

function dbTitle(id) { return state.dbs.find(d => d.id === id)?.title || id; }

function askConfirm(text, yesLabel = "Remove") {
  return new Promise(resolve => {
    const d = $("#confirmDialog");
    $("#confirmText").textContent = text;
    $("#confirmYes").textContent = yesLabel;
    const done = v => { d.close(); resolve(v); };
    $("#confirmForm").onsubmit = e => { e.preventDefault(); done(true); };
    $("#confirmNo").onclick = () => done(false);
    d.oncancel = () => resolve(false);
    d.showModal();
  });
}

/** CSP blocks style="" in HTML, so sizes are written as data-w / data-rot and applied here. */
function applySizes(root = document) {
  $$("[data-w]", root).forEach(el => { el.style.width = el.dataset.w + "%"; });
}

function busy(on) { $(".console .lamps")?.classList.toggle("busy", on); }

// ------------------------------------------------------------------ theme
function currentTheme() {
  const set = document.documentElement.getAttribute("data-theme");
  if (set) return set;
  return matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light";
}
function paintThemeButtons() {
  const label = currentTheme() === "dark" ? "Light mode" : "Dark mode";
  $$("[data-theme-toggle]").forEach(b => { b.textContent = label; });
}
$$("[data-theme-toggle]").forEach(b => b.addEventListener("click", () => {
  const next = currentTheme() === "dark" ? "light" : "dark";
  document.documentElement.setAttribute("data-theme", next);
  try { localStorage.setItem("prashna-theme", next); } catch { /* storage blocked: lasts this visit */ }
  paintThemeButtons();
}));
paintThemeButtons();

// ------------------------------------------------------------------ sign in
function showLogin() {
  state.me = null;
  $("#app").hidden = true;
  $("#login").hidden = false;
  api("/info").then(i => { $("#loginOrg").textContent = i.organisation; }).catch(() => {});
  setTimeout(() => $("#lu").focus(), 0);
}

$$(".demo-row").forEach(b => b.addEventListener("click", () => {
  const [u, p] = b.dataset.demo.split(":");
  $("#lu").value = u;
  $("#lp").value = p;
  $("#loginForm button[type=submit]").focus();
}));

$("#loginForm").addEventListener("submit", async e => {
  e.preventDefault();
  $("#loginErr").textContent = "";
  const btn = $("#loginForm button[type=submit]");
  btn.disabled = true;
  try {
    const me = await api("/login", { method: "POST", body: { username: $("#lu").value.trim(), password: $("#lp").value } });
    $("#lp").value = "";
    await startApp(me);
  } catch (err) {
    $("#loginErr").textContent = err.message;
  } finally {
    btn.disabled = false;
  }
});

$("#logoutBtn").addEventListener("click", async () => {
  await api("/logout", { method: "POST" }).catch(() => {});
  state.last = null;
  $("#answer").hidden = true;
  location.hash = "";
  showLogin();
});

async function startApp(me) {
  state.me = me;
  $("#login").hidden = true;
  $("#app").hidden = false;
  $("#meName").textContent = me.displayName;
  $("#meRole").textContent = me.role;
  buildNav();
  await loadDatabases();
  route();
}

function buildNav() {
  const admin = state.me.role === "ADMIN";
  const link = ([id, name]) => `<a href="#/${id}" data-page="${id}">${name}</a>`;
  $("#nav").innerHTML = PAGES_ALL.map(link).join("") + (admin ? '<span class="split" aria-hidden="true"></span>' + PAGES_ADMIN.map(link).join("") : "");
}

async function loadDatabases() {
  state.dbs = await api("/databases");
  if (!state.db || !state.dbs.some(d => d.id === state.db)) state.db = state.dbs[0]?.id || null;
  if (!state.catDb || !state.dbs.some(d => d.id === state.catDb)) state.catDb = state.db;
  if (!state.taughtDb || !state.dbs.some(d => d.id === state.taughtDb)) state.taughtDb = state.db;
}

// ------------------------------------------------------------------ routing
window.addEventListener("hashchange", route);

function route() {
  if (!state.me) return;
  let page = (location.hash.replace(/^#\/?/, "") || "ask").split("/")[0];
  const allowed = PAGES_ALL.map(p => p[0]).concat(state.me.role === "ADMIN" ? PAGES_ADMIN.map(p => p[0]) : []);
  if (!allowed.includes(page)) page = "ask";
  $$(".view").forEach(v => { v.hidden = v.id !== "view-" + page; });
  $$("#nav a").forEach(a => a.classList.toggle("is-active", a.dataset.page === page));
  if (page === "ask") renderAsk();
  if (page === "catalogue") renderCatalogue();
  if (page === "history") loadHistory();
  if (page === "shelf") loadShelf();
  if (page === "taught") renderTaught();
  if (page === "people") loadPeople();
  if (page === "settings") loadSettings();
  window.scrollTo({ top: 0 });
}

// ------------------------------------------------------------------ database "drives"
const REEL = `<svg class="reel" viewBox="0 0 30 30" aria-hidden="true"><circle cx="15" cy="15" r="13" stroke-width="2"/>
  <circle cx="15" cy="15" r="8" stroke-width="1.5" stroke-dasharray="4 3"/><circle class="hub" cx="15" cy="15" r="3"/></svg>`;

function renderDrives(el, current, onPick) {
  el.innerHTML = state.dbs.map((d, i) => `<button type="button" class="drive" role="tab" data-db="${esc(d.id)}" aria-selected="${d.id === current}">
      ${REEL}<span><b>${esc(d.title)}</b><i>DRIVE ${String.fromCharCode(65 + i)} · ${d.tables.length} tables · ${fmt(d.rows)} rows</i></span></button>`).join("");
  $$(".drive", el).forEach(b => b.addEventListener("click", () => onPick(b.dataset.db)));
}

// ------------------------------------------------------------------ ASK page
async function renderAsk() {
  renderDrives($("#drives"), state.db, id => {
    state.db = id;
    state.last = null;
    $("#answer").hidden = true;
    $("#askError").hidden = true;
    $("#holes").innerHTML = "";
    renderAsk();
  });
  $("#askDbName").textContent = "DATABASE: " + dbTitle(state.db).toUpperCase();
  if (!state.db) return;
  try {
    const sug = await api(`/databases/${encodeURIComponent(state.db)}/suggestions`);
    $("#chits").innerHTML = sug.map(s => `<button type="button" class="chit">${esc(s)}</button>`).join("");
    $$("#chits .chit").forEach(c => c.addEventListener("click", () => { $("#q").value = c.textContent; ask(); }));
  } catch { /* suggestions are optional */ }
  if (!state.last) setTimeout(() => $("#q").focus(), 0);
}

$("#q").addEventListener("keydown", e => {
  if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); ask(); }
});
$("#askForm").addEventListener("submit", e => { e.preventDefault(); ask(); });

async function ask(question) {
  const q = (question ?? $("#q").value).trim();
  if (!q) { $("#q").focus(); return; }
  $("#q").value = q;
  state.lastQuestion = q;
  $("#askBtn").disabled = true;
  busy(true);
  try {
    const r = await api("/ask", { method: "POST", body: { db: state.db, question: q } });
    showAnswer(r);
  } catch (err) {
    toast(err.message);
  } finally {
    $("#askBtn").disabled = false;
    busy(false);
  }
}

async function runOwnSql(sql, question) {
  busy(true);
  $("#runSql").disabled = true;
  try {
    const r = await api("/run", { method: "POST", body: { db: state.db, sql, question } });
    r.understanding = state.last?.understanding;
    showAnswer(r, true);
  } catch (err) {
    $("#sqlErr").textContent = err.message;
  } finally {
    busy(false);
    $("#runSql").disabled = false;
  }
}

function holesHtml(chips) {
  return (chips || []).map(c => {
    const kind = c.kind || "stop";
    const meaning = kind === "stop" || !c.meaning ? "" : `<small>${esc(c.meaning)}</small>`;
    return `<span class="hole k-${esc(kind)}" title="${esc(c.meaning || "")}">${esc(c.text)}${meaning}</span>`;
  }).join("");
}

function showAnswer(r, ownSql = false) {
  state.last = r;
  state.page = 0;
  state.editing = false;
  const u = r.understanding;
  $("#holes").innerHTML = u && !ownSql ? holesHtml(u.chips) : "";

  // nothing could be written at all
  if (!r.sql && r.error && !r.result) {
    $("#answer").hidden = true;
    $("#askError").hidden = false;
    $("#askErrorText").textContent = r.error;
    $("#errHoles").innerHTML = u ? holesHtml(u.chips) : "";
    $("#errChits").innerHTML = (r.suggestions || []).map(s => `<button type="button" class="chit">${esc(s)}</button>`).join("");
    $$("#errChits .chit").forEach(c => c.addEventListener("click", () => { $("#q").value = c.textContent; ask(); }));
    return;
  }
  $("#askError").hidden = true;
  $("#answer").hidden = false;

  // ---- meter
  const conf = typeof r.confidence === "number" ? r.confidence : 0;
  const angle = -90 + 180 * Math.max(0, Math.min(1, conf));
  $("#needle").style.transform = `rotate(${angle}deg)`;
  $("#confNum").textContent = Math.round(conf * 100) + "%";
  const lab = $("#confLabel");
  if (r.source === "MANUAL") { lab.textContent = "Your SQL"; lab.className = "meter-label ok"; }
  else if (r.notSure) { lab.textContent = "Not sure – please check"; lab.className = "meter-label bad"; }
  else if (conf < 0.8) { lab.textContent = "Fairly sure"; lab.className = "meter-label warn"; }
  else { lab.textContent = "Sure"; lab.className = "meter-label ok"; }
  $("#sourceTag").textContent = SOURCE_NAME[r.source] || r.source || "";

  const notes = [];
  if (r.note) notes.push(["warn", r.note]);
  if (r.learnedFrom) notes.push(["", `Reused what was taught for "${r.learnedFrom.question}" (${Math.round(r.learnedFrom.similarity * 100)}% similar).`]);
  if (u && !ownSql && r.source === "RULES") {
    (u.assumptions || []).forEach(a => notes.push(["", a]));
    if (u.unknown?.length) notes.push(["warn", "Not understood: " + u.unknown.map(w => `"${w}"`).join(", ") + ". The answer may miss what these words mean."]);
  }
  if (r.notSure && r.source === "RULES") notes.push(["warn", "Check the SQL below. If it is wrong, " + (state.me.canWriteSql ? "fix it and press Teach." : "mark it ✗ Wrong.")]);
  $("#notes").innerHTML = notes.map(([c, t]) => `<li class="${c}">${esc(t)}</li>`).join("");

  // ---- SQL card
  const canEdit = state.me.canWriteSql;
  $("#sqlCard").hidden = !r.sql && !canEdit;
  $("#sqlView").innerHTML = r.sql ? highlight(r.sql) : "<i>(hidden for viewers)</i>";
  $("#sqlView").hidden = false;
  $("#sqlEditWrap").hidden = true;
  $("#editSql").hidden = !canEdit;
  $("#editSql").textContent = "Edit SQL";
  $("#runSql").hidden = true;
  $("#teachBtn").hidden = !(canEdit && r.source === "MANUAL" && !r.error && state.lastQuestion);
  $("#sqlErr").textContent = r.error && r.sql ? r.error : "";

  // ---- flowchart
  $("#flow").innerHTML = (r.explanation || []).map(s => {
    const cls = s.clause === "FROM" ? "term" : (s.clause === "WHERE" || s.clause === "HAVING") ? "diamond" : "";
    return `<li class="${cls}"><b>${esc(s.clause)}</b><span>${esc(s.text)}</span></li>`;
  }).join("") || "<li><span>No steps.</span></li>";

  // ---- feedback buttons
  $$(".thumb").forEach(t => t.classList.remove("is-on"));
  $("#saveBtn").disabled = !r.historyId;
  $("#csvBtn").disabled = !r.result;

  renderPrintout();
}

function highlight(sql) {
  const kw = /\b(SELECT|DISTINCT|FROM|WHERE|AND|OR|NOT|JOIN|LEFT|INNER|OUTER|ON|GROUP|BY|HAVING|ORDER|ASC|DESC|LIMIT|OFFSET|AS|IN|BETWEEN|LIKE|IS|NULL|CASE|WHEN|THEN|ELSE|END)\b/gi;
  const parts = [];
  const re = /'(?:[^']|'')*'|\b\d+(?:\.\d+)?\b|\b(?:COUNT|SUM|AVG|MIN|MAX|ROUND|UPPER|LOWER|LENGTH|YEAR|MONTH|COALESCE|SUBSTR|CONCAT|TRIM|ABS)(?=\()/gi;
  let last = 0, m;
  while ((m = re.exec(sql))) {
    parts.push(esc(sql.slice(last, m.index)).replace(kw, '<span class="kw">$1</span>'));
    const t = m[0];
    const cls = t.startsWith("'") ? "str" : /^\d/.test(t) ? "num" : "fn";
    parts.push(`<span class="${cls}">${esc(t)}</span>`);
    last = m.index + t.length;
  }
  parts.push(esc(sql.slice(last)).replace(kw, '<span class="kw">$1</span>'));
  return parts.join("");
}

// ---- printout
function chartColumns(res) {
  if (!res || res.rows.length < 2 || res.rows.length > 40) return null;
  let label = -1, value = -1;
  res.columns.forEach((c, i) => {
    if (!res.numeric[i] && label < 0) label = i;
    if (res.numeric[i] && value < 0 && !/_id$|^id$/.test(c) && i !== label) value = i;
  });
  if (value < 0) return null;
  if (label < 0) {   // numeric label (e.g. semester): use the first column if it is not the value
    if (res.columns.length >= 2 && value !== 0) label = 0; else return null;
  }
  return { label, value };
}

function renderPrintout() {
  const r = state.last;
  const res = r.result;
  const stamp = new Date().toLocaleString(undefined, { dateStyle: "medium", timeStyle: "short" }).toUpperCase();
  $("#printHead").textContent = `JOB: ${(r.question || "OWN SQL").toUpperCase()}\nDRIVE: ${dbTitle(r.db).toUpperCase()}   ${stamp}`;
  const chart = chartColumns(res);
  $("#chartBtn").disabled = !chart;
  if (!chart && state.view === "chart") state.view = "table";
  $$(".seg-btn[data-view]").forEach(b => b.classList.toggle("is-on", b.dataset.view === state.view));

  if (!res) {
    $("#tableWrap").innerHTML = `<p class="big-caption">*** QUERY STOPPED ***</p><p>${esc(r.error || "No result.")}</p>`;
    $("#chartWrap").hidden = true;
    $("#tableWrap").hidden = false;
    $("#printFoot").textContent = "";
    return;
  }
  $("#printLabel").textContent = `PRINTOUT · ${res.totalRows} ROW${res.totalRows === 1 ? "" : "S"}`;
  $("#printFoot").textContent = `*** END OF REPORT ***  ${res.totalRows} row(s)${res.truncated ? " (first " + res.rows.length + " shown)" : ""} · looked at ${fmt(res.rowsScanned)} rows · ${res.millis} ms`;

  if (state.view === "chart" && chart) {
    $("#tableWrap").hidden = true;
    $("#chartWrap").hidden = false;
    $("#chartWrap").textContent = lineChart(res, chart);
    return;
  }
  $("#chartWrap").hidden = true;
  $("#tableWrap").hidden = false;

  if (res.rows.length === 1 && res.columns.length === 1) {
    $("#tableWrap").innerHTML = `<p class="big-number">${esc(fmt(res.rows[0][0]))}</p><p class="big-caption">${esc(res.columns[0].replace(/_/g, " "))}</p>`;
    return;
  }
  if (res.rows.length === 0) {
    $("#tableWrap").innerHTML = `<p class="big-caption">*** NO ROWS MATCHED ***</p><p>Nothing in the data fits these conditions. Check the WHERE step on the left.</p>`;
    return;
  }
  const start = state.page * PAGE_SIZE;
  const rows = res.rows.slice(start, start + PAGE_SIZE);
  const head = res.columns.map((c, i) => `<th class="${res.numeric[i] ? "num" : ""}">${esc(c)}</th>`).join("");
  const body = rows.map(row => "<tr>" + row.map((v, i) =>
    `<td class="${res.numeric[i] ? "num" : ""}${v === null ? " null" : ""}" title="${esc(fmt(v))}">${esc(fmt(v))}</td>`).join("") + "</tr>").join("");
  const pages = Math.ceil(res.rows.length / PAGE_SIZE);
  const pager = pages > 1 ? `<div class="pager"><button type="button" id="pgPrev" ${state.page === 0 ? "disabled" : ""}>◀ prev</button>
      <span>PAGE ${state.page + 1} / ${pages}</span><button type="button" id="pgNext" ${state.page >= pages - 1 ? "disabled" : ""}>next ▶</button></div>` : "";
  $("#tableWrap").innerHTML = `<table class="print-table"><thead><tr>${head}</tr></thead><tbody>${body}</tbody></table>${pager}`;
  $("#pgPrev")?.addEventListener("click", () => { state.page--; renderPrintout(); });
  $("#pgNext")?.addEventListener("click", () => { state.page++; renderPrintout(); });
}

/** A line-printer bar chart: bars are made of █ characters, like reports printed in the 1970s. */
function lineChart(res, { label, value }) {
  const rows = res.rows.map(r => [fmt(r[label]), Number(r[value]) || 0]);
  const max = Math.max(...rows.map(r => Math.abs(r[1])), 0) || 1;
  const lw = Math.min(28, Math.max(...rows.map(r => r[0].length)));
  const width = 40;
  const lines = rows.map(([l, v]) => {
    const n = Math.round(Math.abs(v) / max * width);
    const name = l.length > lw ? l.slice(0, lw - 1) + "…" : l.padEnd(lw);
    return `${name} │${"█".repeat(n)}${n === 0 && v !== 0 ? "▏" : ""} ${fmt(v)}`;
  });
  const axis = " ".repeat(lw) + " └" + "─".repeat(width + 2);
  return `${res.columns[value].toUpperCase()} BY ${res.columns[label].toUpperCase()}\n\n` + lines.join("\n") + "\n" + axis;
}

$$(".seg-btn[data-view]").forEach(b => b.addEventListener("click", () => {
  if (b.disabled) return;
  state.view = b.dataset.view;
  renderPrintout();
}));

$("#copySql").addEventListener("click", async () => {
  const sql = state.last?.sql;
  if (!sql) return;
  try { await navigator.clipboard.writeText(sql); toast("SQL copied."); }
  catch { toast("Copy is blocked here – select the text instead."); }
});

$("#editSql").addEventListener("click", () => {
  state.editing = !state.editing;
  $("#sqlEditWrap").hidden = !state.editing;
  $("#sqlView").hidden = state.editing;
  $("#runSql").hidden = !state.editing;
  $("#editSql").textContent = state.editing ? "Cancel edit" : "Edit SQL";
  if (state.editing) { $("#sqlEdit").value = state.last?.sql || ""; $("#sqlEdit").focus(); }
});
$("#sqlEdit").addEventListener("keydown", e => {
  if (e.key === "Enter" && (e.ctrlKey || e.metaKey)) { e.preventDefault(); $("#runSql").click(); }
});
$("#runSql").addEventListener("click", () => {
  $("#sqlErr").textContent = "";
  runOwnSql($("#sqlEdit").value, state.lastQuestion);
});
$("#teachBtn").addEventListener("click", async () => {
  if (!state.last?.historyId) return;
  try {
    const r = await api("/teach", { method: "POST", body: { historyId: state.last.historyId, sql: state.last.sql } });
    toast(r.message);
    $("#teachBtn").hidden = true;
  } catch (err) { $("#sqlErr").textContent = err.message; }
});

$$(".thumb").forEach(t => t.addEventListener("click", async () => {
  if (!state.last?.historyId) return;
  const v = Number(t.dataset.fb);
  try {
    await api(`/history/${state.last.historyId}/feedback`, { method: "POST", body: { value: v } });
    $$(".thumb").forEach(x => x.classList.toggle("is-on", x === t));
    if (v < 0 && state.me.canWriteSql) {
      toast("Thanks. Fix the SQL, run it, then press Teach so Prashna learns.");
      if (!state.editing) $("#editSql").click();
    } else toast(v > 0 ? "Thanks – marked as right." : "Thanks – marked as wrong. An analyst can teach the right SQL.");
  } catch (err) { toast(err.message); }
}));

$("#csvBtn").addEventListener("click", () => {
  if (!state.last?.historyId) return;
  const a = document.createElement("a");
  a.href = `/api/history/${encodeURIComponent(state.last.historyId)}/csv`;
  a.download = "prashna-result.csv";
  document.body.appendChild(a);
  a.click();
  a.remove();
});

$("#saveBtn").addEventListener("click", () => {
  if (!state.last) return;
  $("#saveName").value = (state.last.question || "My query").slice(0, 80);
  $("#saveDialog").showModal();
});
$("#saveCancel").addEventListener("click", () => $("#saveDialog").close());
$("#saveForm").addEventListener("submit", async e => {
  e.preventDefault();
  const r = state.last;
  try {
    await api("/saved", { method: "POST", body: { db: r.db, name: $("#saveName").value, question: r.source === "MANUAL" ? state.lastQuestion : r.question,
      sql: r.source === "MANUAL" ? r.sql : null } });
    $("#saveDialog").close();
    toast("Saved to your shelf.");
  } catch (err) { toast(err.message); }
});

// ------------------------------------------------------------------ CATALOGUE
async function renderCatalogue() {
  renderDrives($("#catDrives"), state.catDb, id => { state.catDb = id; renderCatalogue(); });
  $("#uploadPanel").hidden = !state.me.canUpload;
  $("#drawerPanel").hidden = true;
  if (!state.catDb) { $("#cabinet").innerHTML = ""; return; }
  try {
    const s = await api(`/databases/${encodeURIComponent(state.catDb)}/schema`);
    state.schema[s.id] = s;
    $("#cabinet").innerHTML = s.tables.map(t => `<button type="button" class="drawer" data-t="${esc(t.name)}">
        <span class="label-holder">${esc(t.name)}<small>${fmt(t.rows)} rows · ${t.columns.length} cols</small></span></button>`).join("")
      || `<p class="muted">No tables yet. Upload a CSV below.</p>`;
    $$("#cabinet .drawer").forEach(d => d.addEventListener("click", () => openDrawer(s, d.dataset.t)));
  } catch (err) { toast(err.message); }
}

function openDrawer(s, name) {
  const t = s.tables.find(x => x.name === name);
  if (!t) return;
  $$("#cabinet .drawer").forEach(d => d.classList.toggle("is-open", d.dataset.t === name));
  $("#drawerPanel").hidden = false;
  $("#drawerTitle").textContent = `${t.name}  (${t.alias})`;
  $("#drawerDesc").textContent = t.description + (t.synonyms.length ? `  Also called: ${t.synonyms.join(", ")}.` : "");
  $("#dropTable").hidden = !s.mine;
  $("#dropTable").onclick = async () => {
    if (!(await askConfirm(`Remove the table "${t.name}" from My uploads?`))) return;
    try {
      await api(`/databases/${encodeURIComponent(s.id)}/tables/${encodeURIComponent(t.name)}`, { method: "DELETE" });
      toast("Table removed.");
      await loadDatabases();
      renderCatalogue();
    } catch (err) { toast(err.message); }
  };
  $("#drawerCols").innerHTML = t.columns.map(c => {
    const tags = [`<span class="tag type">${esc(c.type)}</span>`];
    if (c.key) tags.push(`<span class="tag key">PRIMARY KEY</span>`);
    if (c.link) tags.push(`<span class="tag link">→ ${esc(c.link)}</span>`);
    if (c.private) tags.push(`<span class="tag priv">${c.hidden ? "PRIVATE · hidden for you" : "PRIVATE"}</span>`);
    const words = [c.label, ...c.synonyms].filter((w, i, a) => w && a.indexOf(w) === i);
    const vals = c.valueWords?.length ? `<br><b>Values with other words:</b> ${esc(c.valueWords.join(", "))}` : "";
    return `<div class="index-card"><div class="cname">${esc(c.name)} ${tags.join("")}</div>
      <p><b>Words:</b> ${esc(words.join(", "))}${c.unit ? ` · unit: ${esc(c.unit)}` : ""}${vals}</p></div>`;
  }).join("");
  const head = t.columns.map(c => `<th>${esc(c.name)}</th>`).join("");
  const body = t.sample.map(r => "<tr>" + r.map(v => `<td>${esc(fmt(v))}</td>`).join("") + "</tr>").join("");
  $("#drawerRows").innerHTML = `<table class="print-table"><thead><tr>${head}</tr></thead><tbody>${body}</tbody></table>`;
  $("#drawerPanel").scrollIntoView({ behavior: "smooth", block: "start" });
}

$("#csvFile").addEventListener("change", () => {
  const f = $("#csvFile").files[0];
  $("#csvName").textContent = f ? `${f.name} (${Math.ceil(f.size / 1024)} KB)` : "No file chosen";
  $("#uploadBtn").disabled = !f;
  $("#uploadErr").textContent = "";
});
$("#uploadForm").addEventListener("submit", async e => {
  e.preventDefault();
  const f = $("#csvFile").files[0];
  if (!f) return;
  if (f.size > 1.5 * 1024 * 1024) { $("#uploadErr").textContent = "Please use a file under 1.5 MB."; return; }
  $("#uploadBtn").disabled = true;
  try {
    const text = await f.text();
    const r = await api("/upload", { method: "POST", body: { name: f.name, csv: text } });
    toast(`Added table ${r.table} with ${r.rows} rows.`);
    await loadDatabases();
    state.catDb = r.db;
    $("#csvFile").value = "";
    $("#csvName").textContent = "No file chosen";
    renderCatalogue();
  } catch (err) {
    $("#uploadErr").textContent = err.message;
  } finally { $("#uploadBtn").disabled = !$("#csvFile").files[0]; }
});

// ------------------------------------------------------------------ HISTORY
$("#histScope").addEventListener("click", e => {
  const b = e.target.closest("[data-scope]");
  if (!b) return;
  state.histScope = b.dataset.scope;
  $$("#histScope .seg-btn").forEach(x => x.classList.toggle("is-on", x === b));
  loadHistory();
});
$("#clearHist").addEventListener("click", async () => {
  if (!(await askConfirm("Clear your whole job log? Saved cards are kept.", "Clear"))) return;
  await api("/history", { method: "DELETE" }).catch(err => toast(err.message));
  loadHistory();
});

async function loadHistory() {
  $("#histScope").hidden = state.me.role !== "ADMIN";
  try {
    const items = await api("/history" + (state.histScope === "all" ? "?scope=all" : ""));
    if (!items.length) { $("#histList").innerHTML = `<p class="log-empty">*** LOG IS EMPTY ***  Ask a question first.</p>`; return; }
    $("#histList").innerHTML = items.map((h, i) => {
      const fb = h.feedback > 0 ? "✓" : h.feedback < 0 ? "✗" : "";
      const who = state.histScope === "all" ? `${esc(h.user)} · ` : "";
      const meta = h.error ? `<span class="bad">ERROR</span>` : `<span>${h.rows} row${h.rows === 1 ? "" : "s"}</span>`;
      return `<button type="button" class="log-row" data-i="${i}">
        <span class="lt">${esc(when(h.at))}</span>
        <span class="lq">${who}${esc(h.question)}</span>
        <span class="lm">${meta}<span>${esc(h.source)}</span><span>${Math.round(h.confidence * 100)}%</span><span>${fb}</span>${h.taught ? "<span>TAUGHT</span>" : ""}</span>
      </button>`;
    }).join("");
    $$("#histList .log-row").forEach(b => b.addEventListener("click", () => reopen(items[Number(b.dataset.i)])));
  } catch (err) { toast(err.message); }
}

function reopen(h) {
  if (!state.dbs.some(d => d.id === h.db)) { toast("That database is no longer available."); return; }
  state.db = h.db;
  location.hash = "#/ask";
  setTimeout(() => {
    $("#q").value = h.question === "(own SQL)" ? "" : h.question;
    state.lastQuestion = $("#q").value;
    if (h.source === "MANUAL" && h.sql && state.me.canWriteSql) runOwnSql(h.sql, state.lastQuestion);
    else if (h.question && h.question !== "(own SQL)") ask(h.question);
  }, 30);
}

// ------------------------------------------------------------------ SHELF
async function loadShelf() {
  try {
    const items = await api("/saved");
    if (!items.length) { $("#shelf").innerHTML = `<p class="muted">Nothing saved yet. Ask a question and press <b>Save card</b>.</p>`; return; }
    $("#shelf").innerHTML = items.map((s, i) => `<div class="punch-card" data-i="${i}" tabindex="0" role="button">
        <div class="card-strip" aria-hidden="true"></div>
        <p class="card-title">SAVED CARD ${String(i + 1).padStart(3, "0")}</p>
        <h3>${esc(s.name)}</h3>
        <p>${esc(s.question || s.sql)}</p>
        <div class="card-actions"><span class="db-mini">${esc(dbTitle(s.db))}${s.sql ? " · own SQL" : ""}</span>
          <button type="button" class="link-btn" data-del="${esc(s.id)}">Remove</button></div>
      </div>`).join("");
    $$("#shelf .punch-card").forEach(c => {
      const s = items[Number(c.dataset.i)];
      const run = () => reopen({ db: s.db, question: s.question || "(own SQL)", source: s.sql ? "MANUAL" : "RULES", sql: s.sql });
      c.addEventListener("click", e => { if (!e.target.closest("[data-del]")) run(); });
      c.addEventListener("keydown", e => { if (e.key === "Enter") run(); });
    });
    $$("#shelf [data-del]").forEach(b => b.addEventListener("click", async () => {
      if (!(await askConfirm("Remove this card from your shelf?"))) return;
      await api(`/saved/${b.dataset.del}`, { method: "DELETE" }).catch(err => toast(err.message));
      loadShelf();
    }));
  } catch (err) { toast(err.message); }
}

// ------------------------------------------------------------------ TAUGHT
async function renderTaught() {
  renderDrives($("#taughtDrives"), state.taughtDb, id => { state.taughtDb = id; renderTaught(); });
  try {
    const list = await api(`/examples?db=${encodeURIComponent(state.taughtDb)}`);
    if (!list.length) {
      $("#taughtList").innerHTML = `<section class="panel"><p class="muted">Nothing taught for this database yet.
        ${state.me.canWriteSql ? "When an answer is wrong: press ✗ Wrong, fix the SQL, run it, then press <b>Teach Prashna this</b>." : "Analysts can teach Prashna by correcting its SQL."}</p></section>`;
      return;
    }
    $("#taughtList").innerHTML = list.map(e => `<section class="panel taught-item">
        <div><b>${esc(e.question)}</b><p class="muted small">Taught by ${esc(e.createdBy)} · used ${e.uses} time${e.uses === 1 ? "" : "s"} · slots: ${esc(e.kinds.join(", ") || "none")}</p>
        <pre>${esc(e.template).replace(/\{\{(\d+)\}\}/g, '<span class="slot">slot $1</span>')}</pre></div>
        ${state.me.role === "ADMIN" ? `<button class="key-btn small" type="button" data-forget="${esc(e.id)}">Forget</button>` : ""}
      </section>`).join("");
    $$("[data-forget]").forEach(b => b.addEventListener("click", async () => {
      if (!(await askConfirm("Forget this taught question?", "Forget"))) return;
      await api(`/examples/${b.dataset.forget}`, { method: "DELETE" }).catch(err => toast(err.message));
      renderTaught();
    }));
  } catch (err) { toast(err.message); }
}

// ------------------------------------------------------------------ TEST BENCH
$("#benchBtn").addEventListener("click", async () => {
  $("#benchBtn").disabled = true;
  busy(true);
  $("#benchOut").innerHTML = `<p class="muted">Running…</p>`;
  try {
    const r = await api("/bench");
    const block = (rep, final) => `<section class="panel">
        <h2 class="panel-title">${esc(rep.name)}${final ? " – the honest number" : " – used while building"}</h2>
        <div class="score ${final ? "final" : ""}"><p class="big-number">${Math.round(rep.accuracy * 100)}%</p><p>${rep.passed} of ${rep.total} questions right</p></div>
        <div class="bar-track"><div class="bar-fill" data-w="${Math.round(rep.accuracy * 100)}"></div></div>
        ${rep.outcomes.map(o => `<div class="bench-row ${o.pass ? "pass" : "fail"}"><span class="mark">${o.pass ? "✓" : "✗"}</span>
          <div>${esc(o.question)} <span class="muted small">(${esc(o.db)}, ${Math.round(o.confidence * 100)}%)</span>
          <details><summary>${esc(o.reason)}</summary><pre>ENGINE: ${esc(o.sql || "—")}\n\nGOLD:   ${esc(o.goldSql)}</pre></details></div></div>`).join("")}
      </section>`;
    $("#benchOut").innerHTML = `<p class="muted">Took ${r.millis} ms. A question passes when the engine's answer has the same rows as the correct SQL (extra columns allowed, order ignored).</p>
      <div class="bench-grid">${block(r.final, true)}${block(r.dev, false)}</div>`;
    applySizes($("#benchOut"));
  } catch (err) {
    $("#benchOut").innerHTML = "";
    toast(err.message);
  } finally {
    $("#benchBtn").disabled = false;
    busy(false);
  }
});

// ------------------------------------------------------------------ PEOPLE
async function loadPeople() {
  try {
    const users = await api("/users");
    $("#peopleTable").innerHTML = `<thead><tr><th>Username</th><th>Name</th><th>Role</th><th>Status</th><th></th></tr></thead><tbody>` +
      users.map(u => `<tr><td class="mono">${esc(u.username)}</td><td>${esc(u.displayName)}</td><td>${esc(u.role)}</td>
        <td>${u.locked ? '<span class="tag priv">LOCKED</span>' : "active"}</td>
        <td><button class="key-btn small" type="button" data-edit="${esc(u.username)}">Change</button>
        ${u.username !== state.me.username ? `<button class="key-btn small" type="button" data-del="${esc(u.username)}">Delete</button>` : ""}</td></tr>`).join("") + "</tbody>";
    $$("#peopleTable [data-edit]").forEach(b => b.addEventListener("click", () => editUser(users.find(u => u.username === b.dataset.edit))));
    $$("#peopleTable [data-del]").forEach(b => b.addEventListener("click", async () => {
      if (!(await askConfirm(`Delete the account "${b.dataset.del}"?`, "Delete"))) return;
      await api(`/users/${encodeURIComponent(b.dataset.del)}`, { method: "DELETE" }).catch(err => toast(err.message));
      loadPeople();
    }));
  } catch (err) { toast(err.message); }
}

$("#addUser").addEventListener("submit", async e => {
  e.preventDefault();
  const f = new FormData(e.target);
  $("#userErr").textContent = "";
  try {
    await api("/users", { method: "POST", body: Object.fromEntries(f) });
    e.target.reset();
    toast("Account added.");
    loadPeople();
  } catch (err) { $("#userErr").textContent = err.message; }
});

function editUser(u) {
  const form = $("#userForm");
  $("#userDialogName").textContent = u.username;
  form.displayName.value = u.displayName;
  form.role.value = u.role;
  form.password.value = "";
  $("#userDialogErr").textContent = "";
  $("#userDialog").showModal();
  form.onsubmit = async e => {
    e.preventDefault();
    try {
      await api(`/users/${encodeURIComponent(u.username)}`, { method: "POST",
        body: { displayName: form.displayName.value, role: form.role.value, password: form.password.value } });
      $("#userDialog").close();
      toast("Saved.");
      loadPeople();
    } catch (err) { $("#userDialogErr").textContent = err.message; }
  };
}
$("#userCancel").addEventListener("click", () => $("#userDialog").close());

// ------------------------------------------------------------------ SETTINGS
async function loadSettings() {
  try {
    const [s, ins] = await Promise.all([api("/settings"), api("/insights")]);
    const f = $("#settingsForm");
    f.organisation.value = s.organisation;
    $$("input[name=claudeMode]", f).forEach(r => { r.checked = r.value === s.claudeMode; r.disabled = !s.claudeReady && r.value !== "OFF"; });
    f.viewersSeeSql.checked = s.viewersSeeSql;
    $("#claudeState").textContent = s.claudeReady ? "API key found. Claude only ever writes a SELECT; it never sees the data rows."
      : "No ANTHROPIC_API_KEY was set when the app started, so only the offline engine can be used.";
    const src = Object.entries(ins.bySource).map(([k, v]) => `<div class="stat"><b>${v}</b><span>${esc(SOURCE_NAME[k] || k)}</span></div>`).join("");
    $("#insights").innerHTML = `<div class="stat-row"><div class="stat"><b>${ins.total}</b><span>questions</span></div>
        <div class="stat"><b>${ins.thumbsUp}</b><span>marked right</span></div><div class="stat"><b>${ins.thumbsDown}</b><span>marked wrong</span></div>
        <div class="stat"><b>${ins.notSure}</b><span>not sure</span></div><div class="stat"><b>${ins.errors}</b><span>errors</span></div>
        <div class="stat"><b>${ins.learned}</b><span>taught</span></div></div>
      <div class="stat-row">${src}</div>
      <h3 class="sub">Words people used that Prashna did not understand</h3>
      <p class="muted small">Add these as synonyms (in the database's schema) or teach the questions.</p>
      <div class="word-cloud">${ins.unknownWords.map(w => `<span>${esc(w.word)} ×${w.count}</span>`).join("") || '<i class="muted">None yet.</i>'}</div>`;
  } catch (err) { toast(err.message); }
}

$("#settingsForm").addEventListener("submit", async e => {
  e.preventDefault();
  const f = e.target;
  $("#settingsErr").textContent = "";
  try {
    const s = await api("/settings", { method: "POST", body: {
      organisation: f.organisation.value, claudeMode: (f.querySelector("input[name=claudeMode]:checked") || {}).value || "OFF",
      viewersSeeSql: f.viewersSeeSql.checked } });
    state.me.organisation = s.organisation;
    toast("Settings saved.");
  } catch (err) { $("#settingsErr").textContent = err.message; }
});

// ------------------------------------------------------------------ meter ticks + start
(function drawTicks() {
  let html = "";
  for (let i = 0; i <= 10; i++) {
    const ang = Math.PI - (i / 10) * Math.PI;
    const r2 = i % 5 === 0 ? 36 : 40;
    const x1 = 60 + Math.cos(ang) * 44, y1 = 62 - Math.sin(ang) * 44;
    const x2 = 60 + Math.cos(ang) * r2, y2 = 62 - Math.sin(ang) * r2;
    html += `<line class="tick" x1="${x1.toFixed(1)}" y1="${y1.toFixed(1)}" x2="${x2.toFixed(1)}" y2="${y2.toFixed(1)}"/>`;
  }
  $("#meterTicks").innerHTML = html;
})();

api("/me").then(startApp).catch(() => showLogin());
