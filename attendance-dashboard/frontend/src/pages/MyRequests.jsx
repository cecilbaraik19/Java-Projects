import { useCallback, useEffect, useState } from 'react';
import { api, countWeekdays, fmtDate, fmtSpan, LEAVE_LABEL, nextWorkdayIso, todayIso, toIso } from '../api.js';
import { useToast } from '../components/Toast.jsx';
import Modal from '../components/Modal.jsx';
import RequestBadge from '../components/RequestBadge.jsx';
import CorrectionModal from '../components/CorrectionModal.jsx';
import { IconCalendar, IconEdit, IconPlus } from '../components/Icons.jsx';

const range = (a, b) => (a === b ? fmtDate(a, { day: 'numeric', month: 'short', year: 'numeric' })
  : `${fmtDate(a)} – ${fmtDate(b, { day: 'numeric', month: 'short', year: 'numeric' })}`);

function ApplyLeaveModal({ balances, holidays, onClose, onSaved }) {
  const toast = useToast();
  const minDate = (() => { const d = new Date(); d.setDate(d.getDate() - 30); return toIso(d); })();
  const [form, setForm] = useState({ type: 'CASUAL', fromDate: nextWorkdayIso(), toDate: nextWorkdayIso(), reason: '' });
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const set = (k) => (e) => {
    const next = { ...form, [k]: e.target.value };
    if (k === 'fromDate' && next.toDate < next.fromDate) next.toDate = next.fromDate;
    setForm(next);
  };
  const days = countWeekdays(form.fromDate, form.toDate, new Set(holidays.map((h) => h.date)));
  const bal = balances.find((b) => b.type === form.type);
  const left = bal?.remaining == null ? null : bal.remaining - bal.pending;

  const save = async (e) => {
    e?.preventDefault();
    setBusy(true); setError('');
    try {
      await api.applyLeave(form);
      toast('Leave request sent to your manager');
      onSaved();
    } catch (err) { setError(err.message); }
    setBusy(false);
  };

  return (
    <Modal title="Apply for leave" onClose={onClose}
           footer={<><button className="btn" onClick={onClose}>Cancel</button>
             <button className="btn btn-primary" onClick={save} disabled={busy}>{busy ? 'Sending…' : 'Send request'}</button></>}>
      <form className="form-grid" onSubmit={save}>
        <div className="field full"><label htmlFor="lt">Leave type</label>
          <select id="lt" className="select" value={form.type} onChange={set('type')}>
            {balances.map((b) => (
              <option key={b.type} value={b.type}>
                {LEAVE_LABEL[b.type]}{b.remaining != null ? ` — ${b.remaining - b.pending} day(s) available` : ' — no limit'}
              </option>
            ))}
          </select></div>
        <div className="field"><label htmlFor="lf">From</label>
          <input id="lf" type="date" className="input" value={form.fromDate} min={minDate} onChange={set('fromDate')} /></div>
        <div className="field"><label htmlFor="lto">To</label>
          <input id="lto" type="date" className="input" value={form.toDate} min={form.fromDate} onChange={set('toDate')} /></div>
        <p className="full" style={{ margin: 0, fontSize: 13 }}>
          <strong>{days}</strong> working day{days === 1 ? '' : 's'}
          {left != null && days > left && <span className="error-text" style={{ marginLeft: 8 }}>more than your {left} available</span>}
        </p>
        <div className="field full"><label htmlFor="lr">Reason *</label>
          <input id="lr" className="input" value={form.reason} onChange={set('reason')} maxLength={300} placeholder="e.g. Family function" /></div>
        <button type="submit" hidden />
      </form>
      {error && <div className="error-text">{error}</div>}
    </Modal>
  );
}

export default function MyRequests() {
  const toast = useToast();
  const [balances, setBalances] = useState([]);
  const [leave, setLeave] = useState(null);
  const [corrections, setCorrections] = useState(null);
  const [applying, setApplying] = useState(false);
  const [correcting, setCorrecting] = useState(false);
  const [holidays, setHolidays] = useState([]);

  useEffect(() => {
    const y = new Date().getFullYear();
    Promise.all([api.holidays(y), api.holidays(y + 1)])
      .then(([a, b]) => setHolidays([...new Map([...a, ...b].map((h) => [h.id, h])).values()]))
      .catch(() => {});
  }, []);

  const load = useCallback(async () => {
    try {
      const [b, l, c] = await Promise.all([api.myLeaveBalance(), api.myLeave(), api.myCorrections()]);
      setBalances(b); setLeave(l); setCorrections(c);
    } catch (e) { toast(e.message, 'error'); }
  }, [toast]);
  useEffect(() => { load(); }, [load]);

  const cancel = async (fn, id, what) => {
    try { await fn(id); toast(`${what} cancelled`); load(); } catch (e) { toast(e.message, 'error'); }
  };
  const today = todayIso();

  return (
    <>
      <div className="page-head">
        <div><h1>My Requests</h1><p>Leave balance, leave requests and attendance corrections</p></div>
        <div className="head-actions">
          <button className="btn" onClick={() => setCorrecting(true)}><IconEdit />Request correction</button>
          <button className="btn btn-primary" onClick={() => setApplying(true)} disabled={!balances.length}><IconPlus />Apply for leave</button>
        </div>
      </div>

      <div className="stats">
        {balances.map((b) => (
          <div className="card stat" key={b.type}>
            <div className="stat-label"><IconCalendar width="14" height="14" />{LEAVE_LABEL[b.type]} leave</div>
            <div className="stat-value">{b.remaining ?? b.used}<span className="stat-of">{b.allowance != null ? ` / ${b.allowance}` : ' used'}</span></div>
            <div className="stat-sub">
              {b.allowance != null ? `${b.used} used` : 'No yearly limit'}{b.pending ? ` · ${b.pending} pending` : ''}
            </div>
            {b.allowance != null && (
              <div className="bar-track" style={{ width: '100%', marginTop: 10 }}>
                <span className="bar-fill" style={{ display: 'block', width: `${Math.min(100, (b.used / b.allowance) * 100)}%` }} />
              </div>
            )}
          </div>
        ))}
      </div>

      <section className="card" style={{ marginBottom: 18 }}>
        <div className="card-head"><div><h2>Leave requests</h2><p>Pending requests, and approved leave that hasn't started, can be cancelled</p></div></div>
        <div className="table-wrap" style={{ marginTop: 8 }}>
          <table>
            <thead><tr><th>Dates</th><th>Type</th><th className="num">Days</th><th>Reason</th><th>Status</th><th>Reviewer note</th><th className="num"></th></tr></thead>
            <tbody>
              {leave?.map((l) => (
                <tr key={l.id}>
                  <td>{range(l.fromDate, l.toDate)}</td>
                  <td>{LEAVE_LABEL[l.type]}</td>
                  <td className="num">{l.days}</td>
                  <td style={{ whiteSpace: 'normal', minWidth: 160 }}>{l.reason}</td>
                  <td><RequestBadge status={l.status} /></td>
                  <td className="muted" style={{ whiteSpace: 'normal' }}>{l.reviewComment ?? (l.reviewedBy ? `by ${l.reviewedBy}` : '')}</td>
                  <td className="num">
                    {(l.status === 'PENDING' || (l.status === 'APPROVED' && l.fromDate > today)) && (
                      <button className="btn btn-sm" onClick={() => cancel(api.cancelLeave, l.id, 'Leave request')}>Cancel</button>
                    )}
                  </td>
                </tr>
              ))}
              {leave && !leave.length && <tr><td colSpan="7" className="empty">No leave requests yet</td></tr>}
              {!leave && <tr><td colSpan="7" className="empty">Loading…</td></tr>}
            </tbody>
          </table>
        </div>
      </section>

      <section className="card">
        <div className="card-head"><div><h2>Correction requests</h2><p>Changes to your check-in/out times</p></div></div>
        <div className="table-wrap" style={{ marginTop: 8 }}>
          <table>
            <thead><tr><th>Date</th><th>Was</th><th>Requested</th><th>Reason</th><th>Status</th><th>Reviewer note</th><th className="num"></th></tr></thead>
            <tbody>
              {corrections?.map((c) => (
                <tr key={c.id}>
                  <td>{fmtDate(c.date, { weekday: 'short', day: 'numeric', month: 'short' })}</td>
                  <td className="tabular muted">{c.previousCheckIn ? fmtSpan(c.previousCheckIn, c.previousCheckOut) : 'no record'}</td>
                  <td className="tabular">{fmtSpan(c.requestedCheckIn, c.requestedCheckOut)}</td>
                  <td style={{ whiteSpace: 'normal', minWidth: 160 }}>{c.reason}</td>
                  <td><RequestBadge status={c.status} /></td>
                  <td className="muted" style={{ whiteSpace: 'normal' }}>{c.reviewComment ?? (c.reviewedBy ? `by ${c.reviewedBy}` : '')}</td>
                  <td className="num">
                    {c.status === 'PENDING' && <button className="btn btn-sm" onClick={() => cancel(api.cancelCorrection, c.id, 'Correction')}>Cancel</button>}
                  </td>
                </tr>
              ))}
              {corrections && !corrections.length && <tr><td colSpan="7" className="empty">No correction requests</td></tr>}
              {!corrections && <tr><td colSpan="7" className="empty">Loading…</td></tr>}
            </tbody>
          </table>
        </div>
      </section>

      <section className="card" style={{ marginTop: 18 }}>
        <div className="card-head"><div><h2>Upcoming holidays</h2><p>These days don't count against your leave</p></div></div>
        <div className="holiday-chips">
          {holidays.filter((h) => h.date >= todayIso()).slice(0, 8).map((h) => (
            <div key={h.id} className="holiday-chip">
              <strong className="tabular">{fmtDate(h.date, { day: 'numeric', month: 'short' })}</strong>
              <span>{h.name}</span>
              <small className="muted">{fmtDate(h.date, { weekday: 'long' })}</small>
            </div>
          ))}
          {!holidays.some((h) => h.date >= todayIso()) && <span className="muted">No upcoming holidays have been added yet.</span>}
        </div>
      </section>

      {applying && <ApplyLeaveModal balances={balances} holidays={holidays} onClose={() => setApplying(false)} onSaved={() => { setApplying(false); load(); }} />}
      {correcting && <CorrectionModal onClose={() => setCorrecting(false)} onSaved={() => { setCorrecting(false); load(); }} />}
    </>
  );
}
