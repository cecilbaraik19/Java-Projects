/* Patra frontend. Plain JavaScript, no libraries. Talks to the Java backend under /api.
   Pages are chosen by the address bar: #/inbox, #/saved, #/insights, #/profile, and for admins
   #/bench, #/people, #/settings.
   Security note: all text from emails is inserted with esc() or textContent, never as raw HTML. */
"use strict";

const $ = (s, root = document) => root.querySelector(s);
const $$ = (s, root = document) => [...root.querySelectorAll(s)];

const state = {
  me: null,
  intents: [],
  emails: [],
  filter: "all",
  current: null,      // the open email
  analysis: null,
  suggestions: [],
  tone: null,         // FORMAL | FRIENDLY | BRIEF
  stance: null,       // POSITIVE | NEED_INFO | DECLINE
  intent: null,
  rating: 0,
  savedId: null,
  source: "OFFLINE",  // OFFLINE or CLAUDE (after polishing)
  claudeReady: false,
  scope: "mine",
};

const TONE_NAME = { FORMAL: "Formal", FRIENDLY: "Friendly", BRIEF: "Brief" };
const MOOD_WORD = { POSITIVE: "Positive", NEUTRAL: "Neutral", NEGATIVE: "Negative" };
const PAGES_ALL = [["inbox", "Inbox"], ["saved", "Saved replies"], ["insights", "Insights"], ["profile", "Signature"]];
const PAGES_ADMIN = [["bench", "Test bench"], ["people", "People"], ["settings", "Settings"]];

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
  if (res.status === 204) return null;
  const data = await res.json().catch(() => ({}));
  if (res.status === 401 && path !== "/login") { showLogin(); throw new Error(data.error || "Please sign in."); }
  if (!res.ok) throw new Error(data.error || "The server could not complete that request.");
  return data;
}

function toast(msg) {
  const t = $("#toast");
  t.textContent = msg;
  t.classList.add("is-up");
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => t.classList.remove("is-up"), 2800);
}

function esc(s) {
  return String(s ?? "").replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
}

function timeAgo(iso) {
  if (!iso) return "";
  const d = new Date(iso);
  const mins = Math.round((Date.now() - d.getTime()) / 60000);
  if (mins < 1) return "just now";
  if (mins < 60) return mins + " min ago";
  if (mins < 60 * 24) return Math.round(mins / 60) + " h ago";
  return d.toLocaleDateString(undefined, { day: "numeric", month: "short" });
}

const intentLabel = id => state.intents.find(i => i.id === id)?.label || id;
const pct = x => Math.round(x * 100) + "%";

/** CSP blocks style="" in HTML, so widths are written as data-w and applied here. */
function applyWidths(root) {
  $$("[data-w]", root).forEach(el => { el.style.width = el.dataset.w + "%"; });
  $$("[data-h]", root).forEach(el => { el.style.height = el.dataset.h + "%"; });
}

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
  try { localStorage.setItem("patra-theme", next); } catch { /* storage blocked: lasts this visit */ }
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
  state.current = null;
  location.hash = "";
  showLogin();
});

async function startApp(me) {
  state.me = me;
  state.claudeReady = me.claudeReady;
  $("#login").hidden = true;
  $("#app").hidden = false;
  $("#orgName").textContent = me.organisation;
  buildNav();
  state.intents = await api("/intents");
  $("#intentSelect").innerHTML = state.intents.map(i => `<option value="${i.id}">${esc(i.label)}</option>`).join("");
  await loadInbox();
  route();
}

function buildNav() {
  const admin = state.me.role === "ADMIN";
  const link = ([id, name]) => `<a href="#/${id}" data-page="${id}">${name}${id === "inbox" ? ' <span class="count" id="unreadCount">0</span>' : ""}</a>`;
  $("#nav").innerHTML = PAGES_ALL.map(link).join("") + (admin ? '<span class="admin-split" aria-hidden="true"></span>' + PAGES_ADMIN.map(link).join("") : "");
}

// ------------------------------------------------------------------ routing
window.addEventListener("hashchange", route);

function route() {
  if (!state.me) return;
  let page = (location.hash.replace(/^#\/?/, "") || "inbox").split("/")[0];
  const allowed = PAGES_ALL.map(p => p[0]).concat(state.me.role === "ADMIN" ? PAGES_ADMIN.map(p => p[0]) : []);
  if (!allowed.includes(page)) page = "inbox";
  $$(".view").forEach(v => { v.hidden = v.id !== "view-" + page; });
  $$("#nav a").forEach(a => a.classList.toggle("is-active", a.dataset.page === page));
  if (page === "saved") loadSaved();
  if (page === "insights") loadInsights();
  if (page === "profile") fillProfile();
  if (page === "people") loadPeople();
  if (page === "settings") loadSettings();
  window.scrollTo({ top: 0 });
}

// ------------------------------------------------------------------ inbox
async function loadInbox() {
  state.emails = await api("/emails");
  renderInbox();
}

function renderInbox() {
  const list = $("#envelopes");
  const f = state.filter;
  const shown = state.emails.filter(e =>
    f === "all" ? true : f === "open" ? !e.replied : f === "HIGH" ? e.urgency === "HIGH" : e.sentiment === f);
  const count = $("#unreadCount");
  if (count) count.textContent = state.emails.filter(e => !e.replied).length;

  if (!shown.length) {
    list.innerHTML = `<li class="empty-note">${state.emails.length ? "No emails match this filter." : "Your inbox is empty. Paste an email to get started."}</li>`;
    return;
  }
  list.innerHTML = shown.map(e => `
    <li><button type="button" class="envelope ${state.current?.id === e.id ? "is-open" : ""} ${e.replied ? "is-replied" : ""}" data-id="${e.id}" data-urgency="${esc(e.urgency)}">
      <div class="env-top"><span class="env-from">${esc(e.senderName || e.senderEmail || "Unknown sender")}</span><span>${timeAgo(e.receivedAt)}</span></div>
      <div class="env-subject">${esc(e.subject)}</div>
      <div class="env-tags">
        <span class="tag">${esc(intentLabel(e.intent))}</span>
        ${e.sentiment === "NEGATIVE" ? '<span class="tag tag-neg">Upset</span>' : e.sentiment === "POSITIVE" ? '<span class="tag tag-pos">Positive</span>' : ""}
        ${e.urgency === "HIGH" && !e.replied ? '<span class="tag tag-neg">Urgent</span>' : ""}
        ${e.replied ? '<span class="tag tag-done">Replied</span>' : ""}
      </div>
    </button></li>`).join("");
  $$(".envelope", list).forEach(b => b.addEventListener("click", () => openEmail(Number(b.dataset.id))));
}

$$(".chip").forEach(c => c.addEventListener("click", () => {
  $$(".chip").forEach(x => x.classList.toggle("is-on", x === c));
  state.filter = c.dataset.filter;
  renderInbox();
}));

// ------------------------------------------------------------------ open an email
async function openEmail(id) {
  const e = state.emails.find(x => x.id === id);
  if (!e) return;
  state.current = e;
  state.rating = 0;
  state.savedId = null;
  $$(".thumb[data-rate]").forEach(t => t.classList.remove("is-picked"));
  renderInbox();

  $("#deskEmpty").hidden = true;
  $("#deskFull").hidden = false;
  $("#view-inbox").classList.add("has-open");
  $("#inFrom").textContent = e.senderName || "Unknown sender";
  $("#inEmail").textContent = e.senderEmail ? `<${e.senderEmail}>` : "";
  $("#inSubject").textContent = e.subject;
  $("#inWhen").textContent = e.receivedAt ? new Date(e.receivedAt).toLocaleString() : "";
  $("#inBody").textContent = e.body;

  try {
    const a = await api(`/emails/${e.id}/analysis`);
    state.analysis = a;
    state.intent = a.intent;
    state.stance = a.recommendedStance;
    renderAnalysis(a);
    await loadSuggestions();
  } catch (err) {
    toast(err.message);
  }
  if (window.innerWidth <= 860) window.scrollTo({ top: 0 });
}

function scoreList(items) {
  return items.map(t => `<li><span>${esc(t.label)}</span><span class="mini"><i data-w="${Math.round(t.probability * 100)}"></i></span><b>${pct(t.probability)}</b></li>`).join("");
}

function renderAnalysis(a) {
  // postmark
  const wrap = $("#postmarkWrap");
  wrap.dataset.mood = a.sentiment.label;
  wrap.dataset.unsure = a.unsure;
  const today = new Date().toLocaleDateString("en-GB", { day: "2-digit", month: "short", year: "numeric" }).toUpperCase();
  $("#pmArcText").textContent = `PATRA SORTING OFFICE ✦ ${today} ✦ `;
  const short = a.intentLabel.length > 13 ? a.intentLabel.split(/[ /]/)[0] : a.intentLabel;
  $("#pmIntent").textContent = a.unsure ? "Not sure" : short;
  $("#pmConf").textContent = a.unsure ? "CHECK TYPE" : pct(a.confidence) + " SURE";
  wrap.classList.remove("is-stamping"); void wrap.offsetWidth; wrap.classList.add("is-stamping");
  $("#unsureNote").hidden = !a.unsure;

  // mood
  $("#rdMood").textContent = `${MOOD_WORD[a.sentiment.label]}, ${a.sentiment.mood.toLowerCase()}`;
  $("#rdMood").className = "read-value is-" + a.sentiment.label;
  $("#moodDot").style.left = ((a.sentiment.score + 1) / 2 * 100) + "%";
  const words = [...a.sentiment.negativeWords, ...a.sentiment.positiveWords].slice(0, 5);
  $("#rdMoodWords").textContent = words.length ? "Words noticed: " + words.join(", ") : "No strong feeling words.";

  // urgency
  const u = a.urgency;
  $("#rdUrgency").textContent = { HIGH: "Reply today", MEDIUM: "Reply soon", LOW: "No rush" }[u.level];
  $("#rdUrgency").className = "read-value is-" + u.level;
  $("#rdUrgencyWhy").textContent = u.score > 0 ? "Because it " + u.reasons.slice(0, 3).join(", ") + "." : "No deadline or pressure words found.";

  // details
  const en = a.entities;
  const items = [
    ...en.dates.map(v => ["Date", v]), ...en.times.map(v => ["Time", v]),
    ...en.amounts.map(v => ["Amount", v]), ...en.references.map(v => ["Reference", v]),
    ...en.emails.map(v => ["Email", v]), ...en.phones.map(v => ["Phone", v]),
    ...(en.senderName ? [["Signed", en.senderName]] : []),
  ];
  $("#rdDetails").innerHTML = items.length
    ? items.map(([k, v]) => `<li><b>${k}</b>${esc(v)}</li>`).join("")
    : "<li>No dates, amounts or reference numbers.</li>";

  // why this type
  $("#rdAltsShort").textContent = a.topIntents.map(t => `${t.label} ${pct(t.probability)}`).join(", ");
  $("#whyFinal").innerHTML = scoreList(a.topIntents);
  $("#whyNb").innerHTML = scoreList(a.why.naiveBayes);
  $("#whyCues").innerHTML = scoreList(a.why.cues);
  $("#whyCueWords").textContent = a.why.cueWords.length ? "Cue words found: " + a.why.cueWords.join(", ") : "No cue words for this type.";
  applyWidths($(".read-why"));

  $("#intentSelect").value = a.intent;
  setStanceButtons();
}

// ------------------------------------------------------------------ suggestions
async function loadSuggestions() {
  const e = state.current;
  const res = await api(`/emails/${e.id}/suggest`, { method: "POST", body: { intent: state.intent, stance: state.stance } });
  state.suggestions = res.suggestions;
  state.claudeReady = res.claudeAvailable;
  $("#polishBtn").hidden = !state.claudeReady;
  state.tone = state.suggestions[0].tone;     // best fit first
  renderTones();
  putSuggestionInEditor();
}

function renderTones() {
  const best = state.suggestions[0].tone;
  $("#tones").innerHTML = ["FORMAL", "FRIENDLY", "BRIEF"].map(t => {
    const s = state.suggestions.find(x => x.tone === t);
    return `<button class="tone-tab" role="tab" type="button" data-tone="${t}" aria-selected="${t === state.tone}">
      <strong>${TONE_NAME[t]}</strong>
      <small>${t === best ? '<span class="best">Best match</span>, ' : ""}${s.fit}% fit</small></button>`;
  }).join("");
  $$(".tone-tab").forEach(b => b.addEventListener("click", () => {
    state.tone = b.dataset.tone;
    $$(".tone-tab").forEach(x => x.setAttribute("aria-selected", String(x === b)));
    putSuggestionInEditor();
  }));
}

function putSuggestionInEditor() {
  const s = state.suggestions.find(x => x.tone === state.tone);
  $("#replySubject").value = s.subject;
  $("#replyBody").value = s.body;
  state.savedId = null;
  state.source = "OFFLINE";
  checkTone();
}

function setStanceButtons() {
  $$("#stance button").forEach(b => b.setAttribute("aria-checked", String(b.dataset.stance === state.stance)));
}
$$("#stance button").forEach(b => b.addEventListener("click", async () => {
  state.stance = b.dataset.stance;
  setStanceButtons();
  try { await loadSuggestions(); } catch (err) { toast(err.message); }
}));
$("#intentSelect").addEventListener("change", async e => {
  state.intent = e.target.value;
  try { await loadSuggestions(); } catch (err) { toast(err.message); }
});

// ------------------------------------------------------------------ live professional score
let toneTimer;
$("#replyBody").addEventListener("input", () => {
  clearTimeout(toneTimer);
  toneTimer = setTimeout(checkTone, 350);
});

async function checkTone() {
  let r;
  try { r = await api("/tone-check", { method: "POST", body: { draft: $("#replyBody").value } }); }
  catch (err) { toast(err.message); return; }
  showToneReport(r);
}

function showToneReport(r) {
  $("#gScore").textContent = r.score;
  $("#gGrade").textContent = r.grade;
  const fill = $("#gaugeFill");
  fill.style.strokeDashoffset = 157.08 * (1 - r.score / 100);
  fill.classList.toggle("mid", r.score < 75 && r.score >= 50);
  fill.classList.toggle("low", r.score < 50);
  $("#gFormality").textContent = r.formality;
  $("#gRead").textContent = `${r.readingLevel} (${r.readability})`;
  $("#gWords").textContent = r.words;
  $("#gIssues").innerHTML = r.issues.length
    ? r.issues.slice(0, 5).map(i => `<li>${esc(i.message)}<span>${esc(i.fix)}</span></li>`).join("")
    : '<li class="ok">Looks professional. Ready to send.</li>';
  updateMailLink();
}

function updateMailLink() {
  const to = state.current?.senderEmail || "";
  $("#mailReply").href = `mailto:${encodeURIComponent(to)}?subject=${encodeURIComponent($("#replySubject").value)}&body=${encodeURIComponent($("#replyBody").value)}`;
}
$("#replySubject").addEventListener("input", updateMailLink);

// ------------------------------------------------------------------ actions
$("#copyReply").addEventListener("click", async () => {
  try {
    await navigator.clipboard.writeText($("#replyBody").value);
    toast("Copied to clipboard");
  } catch {
    $("#replyBody").select();
    toast("Press Ctrl+C to copy the selected text");
  }
});

$("#polishBtn").addEventListener("click", async () => {
  const btn = $("#polishBtn");
  btn.disabled = true;
  btn.textContent = "Polishing…";
  try {
    const r = await api(`/emails/${state.current.id}/polish`, { method: "POST", body: { draft: $("#replyBody").value, tone: state.tone } });
    if (r.ok) {
      $("#replyBody").value = r.body;
      state.source = "CLAUDE";
      showToneReport(r.check);
      toast("Polished by Claude. All dates, amounts and numbers were kept.");
    } else {
      toast(r.message);
    }
  } catch (err) { toast(err.message); }
  btn.disabled = false;
  btn.textContent = "Polish with Claude";
});

$("#saveReply").addEventListener("click", async () => {
  const e = state.current;
  try {
    const saved = await api("/replies", {
      method: "POST",
      body: { emailId: e.id, subject: $("#replySubject").value, intent: state.intent, tone: state.tone,
              stance: state.stance, body: $("#replyBody").value, rating: state.rating, source: state.source },
    });
    state.savedId = saved.id;
    e.replied = true;
    e.intent = state.intent;
    renderInbox();
    toast(state.rating === 1 ? "Reply saved, and Patra learned from this email" : "Reply saved");
  } catch (err) { toast(err.message); }
});

$$(".thumb[data-rate]").forEach(t => t.addEventListener("click", async () => {
  state.rating = Number(t.dataset.rate);
  $$(".thumb[data-rate]").forEach(x => x.classList.toggle("is-picked", x === t));
  if (state.savedId) {
    try {
      await api(`/replies/${state.savedId}`, { method: "PATCH", body: { rating: state.rating } });
      toast(state.rating === 1 ? "Thanks. Patra learned from this email" : "Thanks for the feedback");
    } catch (err) { toast(err.message); }
  } else {
    toast("Noted. Your feedback is sent when you save the reply");
  }
}));

$("#deleteEmail").addEventListener("click", async () => {
  if (!state.current || !(await askConfirm("Remove this email from your inbox?"))) return;
  try {
    await api(`/emails/${state.current.id}`, { method: "DELETE" });
    state.current = null;
    $("#deskFull").hidden = true;
    $("#deskEmpty").hidden = false;
    $("#view-inbox").classList.remove("has-open");
    await loadInbox();
    toast("Email removed");
  } catch (err) { toast(err.message); }
});

$("#backToInbox").addEventListener("click", () => $("#view-inbox").classList.remove("has-open"));

// ------------------------------------------------------------------ paste dialog
const dlg = $("#pasteDialog");
$("#openPaste").addEventListener("click", () => { $("#pasteError").textContent = ""; dlg.showModal(); });
$("#cancelPaste").addEventListener("click", () => dlg.close());
$("#pasteForm").addEventListener("submit", async ev => {
  ev.preventDefault();
  const data = Object.fromEntries(new FormData(ev.target));
  if (!data.body.trim()) { $("#pasteError").textContent = "Paste the message text first."; return; }
  try {
    const saved = await api("/emails", { method: "POST", body: data });
    dlg.close();
    ev.target.reset();
    await loadInbox();
    openEmail(saved.id);
  } catch (err) { $("#pasteError").textContent = err.message; }
});

// ------------------------------------------------------------------ saved replies
async function loadSaved() {
  const list = await api("/replies");
  const ul = $("#savedList");
  if (!list.length) {
    ul.innerHTML = '<li class="empty-note">No saved replies yet. Open an email in the inbox and choose Save reply.</li>';
    return;
  }
  ul.innerHTML = list.map(r => `
    <li class="saved">
      <div class="saved-top"><span>To ${esc(r.toName || r.toEmail || "sender")}, ${esc(TONE_NAME[r.tone] || r.tone)}</span><span>${timeAgo(r.savedAt)}</span></div>
      <h3>${esc(r.subject)}</h3>
      <div class="typed">${esc(r.body)}</div>
      <div class="saved-foot">
        <span class="score-pill" title="Professional score">${r.professionalism}/100</span>
        ${r.source === "CLAUDE" ? '<span class="src-pill">Claude</span>' : ""}
        <button class="thumb ${r.rating === 1 ? "is-picked" : ""}" type="button" data-id="${r.id}" data-r="1" aria-label="Useful">👍</button>
        <button class="thumb ${r.rating === -1 ? "is-picked" : ""}" type="button" data-id="${r.id}" data-r="-1" aria-label="Not useful">👎</button>
        <button class="text-btn" type="button" data-del="${r.id}">Delete</button>
      </div>
    </li>`).join("");
  $$(".saved .thumb").forEach(b => b.addEventListener("click", async () => {
    try {
      await api(`/replies/${b.dataset.id}`, { method: "PATCH", body: { rating: Number(b.dataset.r) } });
      loadSaved();
    } catch (err) { toast(err.message); }
  }));
  $$("[data-del]").forEach(b => b.addEventListener("click", async () => {
    if (!(await askConfirm("Delete this saved reply?", "Delete"))) return;
    try {
      await api(`/replies/${b.dataset.del}`, { method: "DELETE" });
      toast("Reply deleted");
      loadSaved();
    } catch (err) { toast(err.message); }
  }));
}

// ------------------------------------------------------------------ insights
$$("#scopeSwitch button").forEach(b => b.addEventListener("click", () => {
  state.scope = b.dataset.scope;
  $$("#scopeSwitch button").forEach(x => x.setAttribute("aria-checked", String(x === b)));
  loadInsights();
}));

async function loadInsights() {
  $("#scopeSwitch").hidden = state.me.role !== "ADMIN";
  const s = await api("/insights" + (state.scope === "office" ? "?scope=office" : ""));
  $("#insightsSub").textContent = s.scope === "office"
    ? "Every inbox in the office together."
    : "Your inbox: what kind of mail arrives, how senders feel, and how good your replies have been.";
  const mins = s.avgReplyMinutes;
  const time = mins === 0 ? "-" : mins < 60 ? mins + " min" : mins < 1440 ? Math.round(mins / 60) + " h" : Math.round(mins / 1440) + " days";
  $("#tally").innerHTML = [
    [s.totalEmails, "emails received"], [s.waiting, "waiting for a reply"], [s.urgentWaiting, "urgent and waiting"],
    [s.savedReplies, "replies saved"], [s.avgProfessionalism || "-", "average professional score"], [time, "average time to reply"],
  ].map(([n, l]) => `<div><b>${esc(n)}</b><span>${l}</span></div>`).join("");

  const days = Object.entries(s.perDay);
  const maxDay = Math.max(1, ...days.map(d => d[1]));
  $("#perDay").innerHTML = days.map(([d, v]) => {
    const dt = new Date(d + "T00:00");
    return `<div class="day" title="${esc(d)}: ${v}"><b>${v || ""}</b><i data-h="${Math.max(2, v / maxDay * 85)}"></i><span>${dt.getDate()}</span></div>`;
  }).join("");

  bars("#barIntent", s.byIntent, () => "");
  bars("#barMood", s.bySentiment, k => ({ POSITIVE: "green", NEGATIVE: "red", NEUTRAL: "grey" }[k]), k => MOOD_WORD[k]);
  bars("#barTone", s.byTone, () => "red", k => TONE_NAME[k]);
  applyWidths($("#view-insights"));
}

function bars(sel, obj, colour, label = k => k) {
  const max = Math.max(1, ...Object.values(obj));
  $(sel).innerHTML = Object.entries(obj).map(([k, v]) => `
    <div class="bar"><span>${esc(label(k))}</span>
      <div class="bar-track"><div class="bar-fill ${colour(k)}" data-w="${v / max * 100}"></div></div>
      <b>${v}</b></div>`).join("");
}

// ------------------------------------------------------------------ profile
function fillProfile() {
  $("#myName").value = state.me.displayName;
  $("#myTitle").value = state.me.title || "";
  previewSig();
}
function previewSig() {
  $("#sigPrevName").textContent = $("#myName").value || "Your name";
  $("#sigPrevTitle").textContent = $("#myTitle").value;
}
$("#myName").addEventListener("input", previewSig);
$("#myTitle").addEventListener("input", previewSig);
$("#sigForm").addEventListener("submit", async ev => {
  ev.preventDefault();
  $("#sigErr").textContent = "";
  try {
    state.me = await api("/me", { method: "PUT", body: { displayName: $("#myName").value, title: $("#myTitle").value } });
    toast("Signature saved");
  } catch (err) { $("#sigErr").textContent = err.message; }
});
$("#pwForm").addEventListener("submit", async ev => {
  ev.preventDefault();
  $("#pwErr").textContent = "";
  try {
    const r = await api("/me/password", { method: "POST", body: { oldPassword: $("#pwOld").value, newPassword: $("#pwNew").value } });
    ev.target.reset();
    toast(r.message);
    showLogin();
  } catch (err) { $("#pwErr").textContent = err.message; }
});

// ------------------------------------------------------------------ admin: test bench
$("#runBench").addEventListener("click", async () => {
  const btn = $("#runBench");
  btn.disabled = true;
  try {
    const r = await api("/admin/test-bench");
    renderBench(r);
  } catch (err) { toast(err.message); }
  btn.disabled = false;
});

let lastBench = null;
let benchSet = "final";

function renderBench(r) {
  lastBench = r;
  const bestFinal = Math.max(...r.comparison.map(c => c.finalAccuracy));
  const cmp = `<div class="table-wrap"><table class="table">
    <thead><tr><th>Method</th><th class="num">Final set (honest)</th><th class="num">Development set</th><th class="num">Final set F1</th></tr></thead>
    <tbody>${r.comparison.map(c => `<tr class="${c.finalAccuracy === bestFinal ? "is-best" : ""}"><td>${esc(c.mode)}</td>
      <td class="num">${pct(c.finalAccuracy)} <small>(${c.finalCorrect}/${c.finalTotal})</small></td>
      <td class="num">${pct(c.devAccuracy)} <small>(${c.devCorrect}/${c.devTotal})</small></td>
      <td class="num">${c.finalMacroF1.toFixed(2)}</td></tr>`).join("")}
    </tbody></table></div>`;

  const set = r.sets.find(x => x.id === benchSet) || r.sets[0];
  const switcher = `<div class="segmented small" role="radiogroup" aria-label="Test set">${r.sets.map(x =>
    `<button type="button" role="radio" data-set="${x.id}" aria-checked="${x.id === set.id}">${esc(x.name)} (${x.total})</button>`).join("")}</div>`;

  const per = `<div class="table-wrap"><table class="table">
    <thead><tr><th>Email type</th><th class="num">Test emails</th><th class="num">Precision</th><th class="num">Recall</th><th class="num">F1</th></tr></thead>
    <tbody>${set.perIntent.map(p => `<tr><td>${esc(p.label)}</td><td class="num">${p.support}</td>
      <td class="num">${pct(p.precision)}</td><td class="num">${pct(p.recall)}</td>
      <td class="num ${p.f1 >= 0.9 ? "pill-ok" : p.f1 < 0.7 ? "pill-bad" : ""}">${p.f1.toFixed(2)}</td></tr>`).join("")}
    </tbody></table></div>`;

  const short = l => l.split(/[ /]/)[0];
  const conf = `<div class="table-wrap"><table class="table confusion">
    <thead><tr><th>Real type</th>${r.labels.map(l => `<th class="rot">${esc(short(l))}</th>`).join("")}</tr></thead>
    <tbody>${set.confusion.map((row, i) => `<tr><th>${esc(r.labels[i])}</th>${row.map((v, j) =>
      `<td class="${i === j ? "diag" : v > 0 ? "miss" : "zero"}">${v}</td>`).join("")}</tr>`).join("")}
    </tbody></table></div>`;

  const mistakes = set.mistakes.length
    ? `<ul class="mistakes">${set.mistakes.map(m => `<li>"${esc(m.text)}"<span>Really ${esc(m.expected)}, Patra said ${esc(m.predicted)} (${pct(m.confidence)})</span></li>`).join("")}</ul>`
    : '<p class="pill-ok">No mistakes on this test set.</p>';

  $("#benchOut").innerHTML = `
    <div class="bench-block"><h2>Which method works best?</h2>
      <p>Naive Bayes learns from examples; cue words are hand-written hints; the app mixes them 65 : 35.
      Cue words alone look great on the development set but fall apart on new wording. Finished in ${r.millis} ms.</p>${cmp}</div>
    <div class="bench-block"><div class="bench-head"><h2>Hybrid in detail: ${esc(set.name)}, ${pct(set.accuracy)} correct</h2>${switcher}</div>
      <p>${esc(set.note)}</p></div>
    <div class="bench-block"><h2>Score for each email type</h2>
      <p>Precision: when Patra says "Complaint", how often it is right. Recall: of the real complaints, how many it found.</p>${per}</div>
    <div class="bench-block"><h2>Confusion matrix</h2>
      <p>Rows are the real type, columns are Patra's answer. Green diagonal = correct. Red = mixed up.</p>${conf}</div>
    <div class="bench-block"><h2>Mistakes</h2>${mistakes}</div>`;
  $$("#benchOut [data-set]").forEach(b => b.addEventListener("click", () => { benchSet = b.dataset.set; renderBench(lastBench); }));
}

// ------------------------------------------------------------------ admin: people
async function loadPeople() {
  const list = await api("/admin/people");
  renderPeople(list);
}
function renderPeople(list) {
  $("#peopleTable").innerHTML = `<thead><tr><th>Name</th><th>Username</th><th>Role</th><th>Office</th>
    <th class="num">Inbox</th><th class="num">Waiting</th><th class="num">Replies</th><th>Account</th></tr></thead>
    <tbody>${list.map(p => `<tr><td>${esc(p.displayName)}</td><td>${esc(p.username)}</td><td>${p.role === "ADMIN" ? "Admin" : "Agent"}</td>
      <td>${esc(p.title || "")}</td><td class="num">${p.inbox}</td><td class="num">${p.waiting}</td><td class="num">${p.replies}</td>
      <td>${p.locked ? `<button class="btn btn-line" type="button" data-unlock="${esc(p.username)}">Unlock</button>` : '<span class="pill-ok">Active</span>'}</td></tr>`).join("")}</tbody>`;
  $$("[data-unlock]").forEach(b => b.addEventListener("click", async () => {
    try {
      renderPeople(await api(`/admin/people/${encodeURIComponent(b.dataset.unlock)}/unlock`, { method: "POST" }));
      toast("Account unlocked");
    } catch (err) { toast(err.message); }
  }));
}

// ------------------------------------------------------------------ admin: settings
async function loadSettings() {
  const s = await api("/admin/settings");
  $("#setOrg").value = s.organisation;
  $("#setLearn").checked = s.learnFromFeedback;
  $("#setClaude").checked = s.claudeEnabled;
  $("#setClaude").disabled = !s.claudeHasKey;
  $("#claudeNote").textContent = s.claudeHasKey
    ? `An API key was found. Model: ${s.claudeModel}. Claude only rewords Patra's draft; if it changes any number or date, the original is kept.`
    : "To use Claude, set ANTHROPIC_API_KEY before starting the app (see README). Patra works fully offline without it.";
}
$("#settingsForm").addEventListener("submit", async ev => {
  ev.preventDefault();
  try {
    const s = await api("/admin/settings", { method: "PUT", body: {
      organisation: $("#setOrg").value, learnFromFeedback: $("#setLearn").checked, claudeEnabled: $("#setClaude").checked } });
    $("#orgName").textContent = s.organisation;
    state.me.organisation = s.organisation;
    toast("Settings saved");
  } catch (err) { toast(err.message); }
});

// ------------------------------------------------------------------ start
(async function init() {
  try {
    const me = await api("/me");
    await startApp(me);
  } catch {
    showLogin();
  }
})();
