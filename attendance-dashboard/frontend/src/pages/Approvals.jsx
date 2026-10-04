import { useCallback, useEffect, useState } from 'react';
import { api, fmtDate, fmtSpan, LEAVE_LABEL, STATUS_LABEL } from '../api.js';
import { useToast } from '../components/Toast.jsx';
import Modal from '../components/Modal.jsx';
import Person from '../components/Person.jsx';
import RequestBadge from '../components/RequestBadge.jsx';
import { IconCheck, IconX } from '../components/Icons.jsx';

const range = (a, b) => (a === b ? fmtDate(a, { weekday: 'short', day: 'numeric', month: 'short' })
  : `${fmtDate(a)} – ${fmtDate(b)}`);

function RejectModal({ title, onClose, onConfirm }) {
  const [comment, setComment] = useState('');
  const [error, setError] = useState('');
  const go = async (e) => {
    e?.preventDefault();
    if (!comment.trim()) { setError('Please give a reason'); return; }
    try { await onConfirm(comment); } catch (err) { setError(err.message); }
  };
  return (
    <Modal title={title} onClose={onClose}
           footer={<><button className="btn" onClick={onClose}>Cancel</button>
             <button className="btn btn-danger" onClick={go}><IconX />Reject</button></>}>
      <form className="field" onSubmit={go}>
        <label htmlFor="rc">Reason (shown to the employee)</label>
        <input id="rc" className="input" autoFocus value={comment} maxLength={300} onChange={(e) => setComment(e.target.value)} />
      </form>
      {error && <div className="error-text">{error}</div>}
    </Modal>
  );
}

export default function Approvals({ onChanged }) {
  const toast = useToast();
  const [status, setStatus] = useState('PENDING');
  const [leave, setLeave] = useState(null);
  const [corrections, setCorrections] = useState(null);
  const [rejecting, setRejecting] = useState(null); // { kind, item }
  const [busy, setBusy] = useState(null);

  const load = useCallback(async () => {
    try {
      const [l, c] = await Promise.all([api.leaveQueue(status), api.correctionQueue(status)]);
      setLeave(l); setCorrections(c);
    } catch (e) { toast(e.message, 'error'); }
  }, [status, toast]);
  useEffect(() => { load(); }, [load]);

  const review = async (kind, item, approve, comment) => {
    setBusy(`${kind}${item.id}`);
    try {
      await (kind === 'leave' ? api.reviewLeave : api.reviewCorrection)(item.id, approve, comment);
      toast(`${approve ? 'Approved' : 'Rejected'}: ${item.employeeName}`);
      setRejecting(null);
      load();
      onChanged?.();
    } catch (e) {
      if (!approve) throw e; // shown inside the reject dialog
      toast(e.message, 'error');
    } finally {
      setBusy(null);
    }
  };

  const pending = status === 'PENDING';
  const actions = (kind, item) => item.status === 'PENDING' ? (
    <div style={{ display: 'inline-flex', gap: 6 }}>
      <button className="btn btn-sm btn-primary" disabled={busy === `${kind}${item.id}`} onClick={() => review(kind, item, true)}><IconCheck />Approve</button>
      <button className="btn btn-sm" onClick={() => setRejecting({ kind, item })}><IconX />Reject</button>
    </div>
  ) : <span className="muted">{item.reviewedBy ? `by ${item.reviewedBy}` : ''}</span>;

  return (
    <>
      <div className="page-head">
        <div><h1>Approvals</h1><p>Leave and correction requests from your team. Your own requests go to an admin.</p></div>
        <div className="seg" role="group" aria-label="Show">
          <button aria-pressed={pending} onClick={() => setStatus('PENDING')}>Pending</button>
          <button aria-pressed={!pending} onClick={() => setStatus('ALL')}>All recent</button>
        </div>
      </div>

      <section className="card" style={{ marginBottom: 18 }}>
        <div className="card-head"><div><h2>Leave requests</h2><p>{leave ? `${leave.length} ${pending ? 'waiting' : 'shown'}` : ''}</p></div></div>
        <div className="table-wrap" style={{ marginTop: 8 }}>
          <table>
            <thead><tr><th>Employee</th><th>Dates</th><th>Type</th><th className="num">Days</th><th>Reason</th>{!pending && <th>Status</th>}<th className="num">Action</th></tr></thead>
            <tbody>
              {leave?.map((l) => (
                <tr key={l.id}>
                  <td><Person name={l.employeeName} sub={`${l.employeeCode} · ${l.department}`} /></td>
                  <td>{range(l.fromDate, l.toDate)}</td>
                  <td>{LEAVE_LABEL[l.type]}</td>
                  <td className="num">{l.days}</td>
                  <td style={{ whiteSpace: 'normal', minWidth: 180 }}>{l.reason}</td>
                  {!pending && <td><RequestBadge status={l.status} /></td>}
                  <td className="num">{actions('leave', l)}</td>
                </tr>
              ))}
              {leave && !leave.length && <tr><td colSpan="7" className="empty">{pending ? 'Nothing waiting for you' : 'No requests'}</td></tr>}
              {!leave && <tr><td colSpan="7" className="empty">Loading…</td></tr>}
            </tbody>
          </table>
        </div>
      </section>

      <section className="card">
        <div className="card-head"><div><h2>Correction requests</h2><p>{corrections ? `${corrections.length} ${pending ? 'waiting' : 'shown'}` : ''}</p></div></div>
        <div className="table-wrap" style={{ marginTop: 8 }}>
          <table>
            <thead><tr><th>Employee</th><th>Date</th><th>Currently</th><th>Requested</th><th>Reason</th>{!pending && <th>Status</th>}<th className="num">Action</th></tr></thead>
            <tbody>
              {corrections?.map((c) => (
                <tr key={c.id}>
                  <td><Person name={c.employeeName} sub={`${c.employeeCode} · ${c.department}`} /></td>
                  <td>{fmtDate(c.date, { weekday: 'short', day: 'numeric', month: 'short' })}</td>
                  <td className="tabular muted">
                    {c.previousCheckIn ? fmtSpan(c.previousCheckIn, c.previousCheckOut) : (STATUS_LABEL[c.previousStatus] ?? 'No record')}
                  </td>
                  <td className="tabular"><strong>{fmtSpan(c.requestedCheckIn, c.requestedCheckOut)}</strong></td>
                  <td style={{ whiteSpace: 'normal', minWidth: 180 }}>{c.reason}</td>
                  {!pending && <td><RequestBadge status={c.status} /></td>}
                  <td className="num">{actions('correction', c)}</td>
                </tr>
              ))}
              {corrections && !corrections.length && <tr><td colSpan="7" className="empty">{pending ? 'Nothing waiting for you' : 'No requests'}</td></tr>}
              {!corrections && <tr><td colSpan="7" className="empty">Loading…</td></tr>}
            </tbody>
          </table>
        </div>
      </section>

      {rejecting && (
        <RejectModal title={`Reject ${rejecting.kind} request · ${rejecting.item.employeeName}`}
                     onClose={() => setRejecting(null)}
                     onConfirm={(comment) => review(rejecting.kind, rejecting.item, false, comment)} />
      )}
    </>
  );
}
