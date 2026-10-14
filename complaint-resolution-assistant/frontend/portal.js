/* Customer portal: submit a complaint, track it, rate the resolution. */
'use strict';

const $ = s => document.querySelector(s);
const esc = v => String(v ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const nice = s => esc(String(s || '').replace(/_/g, ' ').toLowerCase().replace(/^\w/, c => c.toUpperCase()));
const date = iso => iso ? new Date(iso).toLocaleString('en-IN', { day: 'numeric', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit' }) : '';

// theme toggle (same setting as the agent console)
function applyTheme(t) {
  if (t) document.documentElement.setAttribute('data-theme', t);
  const dark = t ? t === 'dark' : matchMedia('(prefers-color-scheme: dark)').matches;
  $('#themeBtn').textContent = dark ? 'Light mode' : 'Dark mode';
}
let saved = null;
try { saved = localStorage.getItem('theme'); } catch { /* ignore */ }
applyTheme(saved);
$('#themeBtn').addEventListener('click', () => {
  const cur = document.documentElement.getAttribute('data-theme') || (matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light');
  const next = cur === 'dark' ? 'light' : 'dark';
  try { localStorage.setItem('theme', next); } catch { /* ignore */ }
  applyTheme(next);
});

async function post(path, body) {
  const res = await fetch(path, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
  const data = await res.json();
  if (!res.ok) throw new Error(data.error || 'Something went wrong. Please try again.');
  return data;
}

// ---- submit
$('#cForm').addEventListener('submit', async e => {
  e.preventDefault();
  const btn = e.submitter; btn.disabled = true; $('#cErr').textContent = '';
  try {
    const data = Object.fromEntries(new FormData(e.target).entries());
    const r = await post('/api/public/complaints', data);
    e.target.classList.add('hidden');
    $('#cDone').classList.remove('hidden');
    $('#cDone').innerHTML = `
      <div class="banner info" style="margin-top:1rem">Your complaint has been received.</div>
      <p style="margin-top:1rem">Your reference number is</p>
      <p style="font-size:2rem;font-weight:700" class="num">${esc(r.id)}</p>
      <p class="muted">We have filed it under <b>${esc(r.category)}</b> and you can expect an answer by <b>${esc(date(r.expectedBy))}</b>.
      Keep the reference number to track progress.</p>
      <button id="again">Raise another complaint</button>`;
    $('#tId').value = r.id; $('#tEmail').value = data.customerEmail;
    $('#again').addEventListener('click', () => { e.target.reset(); e.target.classList.remove('hidden'); $('#cDone').classList.add('hidden'); });
  } catch (err) { $('#cErr').textContent = err.message; } finally { btn.disabled = false; }
});

// ---- track
const STAGES = ['OPEN', 'IN_PROGRESS', 'RESOLVED', 'CLOSED'];
function showStatus(r, email) {
  const stage = r.status === 'ESCALATED' ? 1 : STAGES.indexOf(r.status);
  const statusText = {
    OPEN: 'We have received it and an agent will pick it up soon.',
    IN_PROGRESS: 'An agent is working on it.',
    ESCALATED: 'A senior team member is looking into it.',
    RESOLVED: 'We have resolved it. Please tell us how we did.',
    CLOSED: 'This complaint is closed.'
  }[r.status];
  const canRate = r.status === 'RESOLVED' || r.status === 'CLOSED';
  $('#tResult').innerHTML = `
    <div style="border-top:1px solid var(--line);padding-top:1rem">
      <h3>${esc(r.subject)}</h3>
      <p class="small muted">${esc(r.id)}, filed ${esc(date(r.createdAt))}</p>
      <div class="steps-track" aria-hidden="true">${[0, 1, 2].map(i => `<span class="${i <= stage ? 'done' : ''}"></span>`).join('')}</div>
      <p><b>${nice(r.status)}.</b> ${esc(statusText)}</p>
      ${r.status !== 'RESOLVED' && r.status !== 'CLOSED' ? `<p class="small muted">Expected by ${esc(date(r.expectedBy))}</p>` : ''}
      ${r.response ? `<h3 style="margin-top:1rem">Our reply</h3><div class="note" style="margin-top:.4rem">${esc(r.response)}</div>` : ''}
      ${canRate ? `<h3 style="margin-top:1rem">How happy are you with the outcome?</h3>
        <div class="stars" role="group" aria-label="Rate from 1 to 5">${[1, 2, 3, 4, 5].map(n =>
          `<button type="button" data-r="${n}" class="${r.satisfactionRating && n <= r.satisfactionRating ? 'on' : ''}" aria-label="${n} out of 5">&#9733;</button>`).join('')}</div>
        ${r.satisfactionRating ? `<p class="small muted">You rated this ${r.satisfactionRating} out of 5. Thank you.</p>` : ''}` : ''}
    </div>`;
  document.querySelectorAll('[data-r]').forEach(b => b.addEventListener('click', async () => {
    try { showStatus(await post('/api/public/rate', { id: r.id, email, rating: Number(b.dataset.r) }), email); }
    catch (err) { $('#tErr').textContent = err.message; }
  }));
}

$('#tForm').addEventListener('submit', async e => {
  e.preventDefault();
  const btn = e.submitter; btn.disabled = true; $('#tErr').textContent = '';
  const id = $('#tId').value.trim(); const email = $('#tEmail').value.trim();
  try { showStatus(await post('/api/public/track', { id, email }), email); }
  catch (err) { $('#tErr').textContent = err.message; $('#tResult').innerHTML = ''; }
  finally { btn.disabled = false; }
});
