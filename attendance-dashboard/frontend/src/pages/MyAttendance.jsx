import { useEffect, useState } from 'react';
import { api, fmtDate, fmtTime, toIso, todayIso } from '../api.js';
import { useAuth } from '../auth.jsx';
import StatusBadge from '../components/StatusBadge.jsx';

const RANGES = [[7, 'Last 7 days'], [30, 'Last 30 days'], [90, 'Last 90 days']];

export default function MyAttendance() {
  const { user } = useAuth();
  const [days, setDays] = useState(30);
  const [rows, setRows] = useState(null);
  const [error, setError] = useState('');

  useEffect(() => {
    const from = new Date(); from.setDate(from.getDate() - (days - 1));
    setRows(null);
    api.myAttendance(toIso(from), todayIso()).then(setRows).catch((e) => { setError(e.message); setRows([]); });
  }, [days]);

  const list = rows ?? [];
  const count = (s) => list.filter((r) => r.status === s).length;
  const attended = count('PRESENT') + count('LATE') + count('HALF_DAY');
  const hours = list.reduce((s, r) => s + r.hoursWorked, 0);
  const today = list.find((r) => r.date === todayIso());

  return (
    <>
      <div className="page-head">
        <div>
          <h1>My Attendance</h1>
          <p>{user.employeeName}{user.department ? ` · ${user.department}` : ''}</p>
        </div>
        <div className="seg" role="group" aria-label="Period">
          {RANGES.map(([d, l]) => <button key={d} aria-pressed={days === d} onClick={() => setDays(d)}>{l}</button>)}
        </div>
      </div>

      {error && <div className="card card-pad error-text" style={{ marginBottom: 18 }}>{error}</div>}

      <div className="stats">
        <div className="card stat">
          <div className="stat-label">Today</div>
          <div className="stat-value" style={{ fontSize: 20, marginTop: 8 }}>
            {today ? <StatusBadge status={today.status} /> : <StatusBadge status="NOT_MARKED" />}
          </div>
          <div className="stat-sub">{today?.checkIn ? `In ${fmtTime(today.checkIn)}${today.checkOut ? ` · Out ${fmtTime(today.checkOut)}` : ''}` : 'Not checked in yet'}</div>
        </div>
        <div className="card stat"><div className="stat-label">Days attended</div><div className="stat-value">{attended}</div></div>
        <div className="card stat"><div className="stat-label">Late arrivals</div><div className="stat-value">{count('LATE')}</div></div>
        <div className="card stat"><div className="stat-label">Leave days</div><div className="stat-value">{count('ON_LEAVE')}</div></div>
        <div className="card stat"><div className="stat-label">Hours worked</div><div className="stat-value">{Math.round(hours)}</div></div>
      </div>

      <section className="card">
        <div className="card-head"><div><h2>History</h2><p>Self check-in from this page is coming in the next update</p></div></div>
        <div className="table-wrap" style={{ marginTop: 8 }}>
          <table>
            <thead><tr><th>Date</th><th>Check in</th><th>Check out</th><th className="num">Hours</th><th>Status</th><th>Note</th></tr></thead>
            <tbody>
              {list.map((r) => (
                <tr key={r.id}>
                  <td>{fmtDate(r.date, { weekday: 'short', day: 'numeric', month: 'short' })}</td>
                  <td className="tabular">{fmtTime(r.checkIn)}</td>
                  <td className="tabular">{fmtTime(r.checkOut)}</td>
                  <td className="num">{r.hoursWorked ? r.hoursWorked.toFixed(1) : '—'}</td>
                  <td><StatusBadge status={r.status} /></td>
                  <td className="muted">{r.note ?? ''}</td>
                </tr>
              ))}
              {rows && !list.length && <tr><td colSpan="6" className="empty">No records in this period</td></tr>}
              {!rows && <tr><td colSpan="6" className="empty">Loading…</td></tr>}
            </tbody>
          </table>
        </div>
      </section>
    </>
  );
}
