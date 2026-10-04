import { useCallback, useEffect, useState } from 'react';
import { api, buildProof, fmtDate, fmtShift, fmtTime, toIso, todayIso } from '../api.js';
import { useAuth } from '../auth.jsx';
import { useToast } from '../components/Toast.jsx';
import StatusBadge from '../components/StatusBadge.jsx';
import CorrectionModal from '../components/CorrectionModal.jsx';
import { IconEdit, IconLogIn, IconLogOut } from '../components/Icons.jsx';

const RANGES = [[7, 'Last 7 days'], [30, 'Last 30 days'], [90, 'Last 90 days']];

function useClock() {
  const [now, setNow] = useState(new Date());
  useEffect(() => { const id = setInterval(() => setNow(new Date()), 1000); return () => clearInterval(id); }, []);
  return now;
}

function CheckInCard({ today, onChange }) {
  const toast = useToast();
  const now = useClock();
  const [busy, setBusy] = useState(false);
  const [policy, setPolicy] = useState(null);
  const [code, setCode] = useState('');
  useEffect(() => { api.checkInPolicy().then(setPolicy).catch(() => {}); }, []);
  const status = today?.status ?? 'NOT_MARKED';
  const canIn = !today?.checkIn && status !== 'ON_LEAVE';
  const canOut = today?.checkIn && !today?.checkOut;

  const needCode = policy?.requireQr && (canIn || canOut);
  const act = async (fn, verb) => {
    if (needCode && code.length !== 6) { toast('Enter the 6-digit code shown on the office screen', 'error'); return; }
    setBusy(true);
    try {
      const r = await fn(await buildProof(policy, code));
      toast(`${verb} at ${fmtTime(verb === 'Checked in' ? r.checkIn : r.checkOut)}`);
      setCode('');
      onChange();
    } catch (e) { toast(e.message, 'error'); }
    setBusy(false);
  };

  return (
    <section className="card checkin-card">
      <div>
        <div className="stat-label">Today · {now.toLocaleDateString('en-IN', { weekday: 'long', day: 'numeric', month: 'long' })}</div>
        <div className="checkin-clock tabular">{now.toLocaleTimeString('en-IN', { hour: '2-digit', minute: '2-digit', second: '2-digit' })}</div>
        <div className="checkin-meta">
          <StatusBadge status={status} />
          {today?.checkIn && <span className="muted">In {fmtTime(today.checkIn)}</span>}
          {today?.checkOut && <span className="muted">· Out {fmtTime(today.checkOut)} · {today.hoursWorked.toFixed(1)} h</span>}
        </div>
        {policy && (policy.requireQr || policy.requireLocation || policy.requireNetwork) && (canIn || canOut) && (
          <div className="muted" style={{ fontSize: 12.5, marginTop: 6 }}>
            Requires {[policy.requireQr && 'the code from the office screen (or scan its QR)', policy.requireLocation && 'your location',
              policy.requireNetwork && 'the office network'].filter(Boolean).join(', ')}
          </div>
        )}
      </div>
      <div className="checkin-actions">
        {needCode && (
          <input className="input code-input tabular" inputMode="numeric" autoComplete="one-time-code" maxLength={6}
                 placeholder="6-digit code" aria-label="6-digit code from the office screen"
                 value={code} onChange={(e) => setCode(e.target.value.replace(/\D/g, '').slice(0, 6))} />
        )}
        {canIn && <button className="btn btn-primary btn-lg" disabled={busy} onClick={() => act(api.myCheckIn, 'Checked in')}><IconLogIn />Check in</button>}
        {canOut && <button className="btn btn-lg" disabled={busy} onClick={() => act(api.myCheckOut, 'Checked out')}><IconLogOut />Check out</button>}
        {!canIn && !canOut && <span className="muted">{status === 'ON_LEAVE' ? 'You are on leave today' : 'Done for today'}</span>}
      </div>
    </section>
  );
}

export default function MyAttendance() {
  const { user } = useAuth();
  const [days, setDays] = useState(30);
  const [rows, setRows] = useState(null);
  const [error, setError] = useState('');
  const [correcting, setCorrecting] = useState(null); // row or {} for a blank form

  const load = useCallback(() => {
    const from = new Date(); from.setDate(from.getDate() - (days - 1));
    api.myAttendance(toIso(from), todayIso()).then(setRows).catch((e) => { setError(e.message); setRows([]); });
  }, [days]);
  useEffect(() => { setRows(null); load(); }, [load]);

  const list = rows ?? [];
  const count = (s) => list.filter((r) => r.status === s).length;
  const attended = count('PRESENT') + count('LATE') + count('HALF_DAY');
  const hours = list.reduce((s, r) => s + r.hoursWorked, 0);
  const overtime = list.reduce((s, r) => s + (r.overtimeHours || 0), 0);
  const today = list.find((r) => r.date === todayIso());

  return (
    <>
      <div className="page-head">
        <div>
          <h1>My Attendance</h1>
          <p>{user.employeeName}{user.department ? ` · ${user.department}` : ''} · Shift: {fmtShift(user.shift)}</p>
        </div>
        <div className="seg" role="group" aria-label="Period">
          {RANGES.map(([d, l]) => <button key={d} aria-pressed={days === d} onClick={() => setDays(d)}>{l}</button>)}
        </div>
      </div>

      {error && <div className="card card-pad error-text" style={{ marginBottom: 18 }}>{error}</div>}

      {rows && <CheckInCard today={today} onChange={load} />}

      <div className="stats">
        <div className="card stat"><div className="stat-label">Days attended</div><div className="stat-value">{attended}</div></div>
        <div className="card stat"><div className="stat-label">Late arrivals</div><div className="stat-value">{count('LATE')}</div></div>
        <div className="card stat"><div className="stat-label">Leave days</div><div className="stat-value">{count('ON_LEAVE')}</div></div>
        <div className="card stat"><div className="stat-label">Hours worked</div><div className="stat-value">{Math.round(hours)}</div></div>
        <div className="card stat"><div className="stat-label">Overtime</div><div className="stat-value">{overtime.toFixed(1)} h</div></div>
      </div>

      <section className="card">
        <div className="card-head">
          <div><h2>History</h2><p>Something wrong? Request a correction and your manager will review it.</p></div>
          <button className="btn" onClick={() => setCorrecting({})}><IconEdit />Request correction</button>
        </div>
        <div className="table-wrap" style={{ marginTop: 8 }}>
          <table>
            <thead><tr><th>Date</th><th>Check in</th><th>Check out</th><th className="num">Hours</th><th className="num">Overtime</th><th>Status</th><th>Note</th><th className="num"></th></tr></thead>
            <tbody>
              {list.map((r) => (
                <tr key={r.id}>
                  <td>{fmtDate(r.date, { weekday: 'short', day: 'numeric', month: 'short' })}</td>
                  <td className="tabular">{fmtTime(r.checkIn)}</td>
                  <td className="tabular">{fmtTime(r.checkOut)}</td>
                  <td className="num">{r.hoursWorked ? r.hoursWorked.toFixed(1) : '—'}</td>
                  <td className="num">{r.overtimeHours ? `+${r.overtimeHours.toFixed(1)}` : '—'}</td>
                  <td><StatusBadge status={r.status} /></td>
                  <td className="muted" style={{ maxWidth: 240, overflow: 'hidden', textOverflow: 'ellipsis' }}>{r.note ?? ''}</td>
                  <td className="num">
                    {r.status !== 'ON_LEAVE' && (
                      <button className="icon-btn" title="Request correction" aria-label={`Request correction for ${r.date}`}
                              onClick={() => setCorrecting(r)}><IconEdit /></button>
                    )}
                  </td>
                </tr>
              ))}
              {rows && !list.length && <tr><td colSpan="8" className="empty">No records in this period</td></tr>}
              {!rows && <tr><td colSpan="8" className="empty">Loading…</td></tr>}
            </tbody>
          </table>
        </div>
      </section>

      {correcting && <CorrectionModal row={correcting.date ? correcting : null} onClose={() => setCorrecting(null)}
                                      onSaved={() => setCorrecting(null)} />}
    </>
  );
}
