import { useEffect, useState } from 'react';
import { api } from '../api.js';
import { useToast } from '../components/Toast.jsx';

const ACTIONS = [
  'LOGIN', 'LOGIN_FAILED', 'ACCOUNT_LOCKED', 'PASSWORD_CHANGED', 'PASSWORD_RESET',
  'USER_CREATED', 'USER_UPDATED', 'USER_DELETED',
  'EMPLOYEE_CREATED', 'EMPLOYEE_UPDATED', 'EMPLOYEE_DELETED',
  'CHECK_IN', 'CHECK_OUT', 'ATTENDANCE_EDITED', 'ATTENDANCE_DELETED',
  'LEAVE_REQUESTED', 'LEAVE_APPROVED', 'LEAVE_REJECTED', 'LEAVE_CANCELLED',
  'CORRECTION_REQUESTED', 'CORRECTION_APPROVED', 'CORRECTION_REJECTED', 'CORRECTION_CANCELLED',
];
const SECURITY = new Set(['LOGIN_FAILED', 'ACCOUNT_LOCKED', 'PASSWORD_RESET', 'USER_DELETED', 'EMPLOYEE_DELETED', 'ATTENDANCE_DELETED', 'LEAVE_REJECTED', 'CORRECTION_REJECTED']);
const label = (a) => a.charAt(0) + a.slice(1).toLowerCase().replaceAll('_', ' ');

export default function AuditLog() {
  const toast = useToast();
  const [username, setUsername] = useState('');
  const [action, setAction] = useState('');
  const [page, setPage] = useState(0);
  const [data, setData] = useState(null);

  useEffect(() => {
    const t = setTimeout(() => {
      api.audit({ username, action, page, size: 50 }).then(setData).catch((e) => toast(e.message, 'error'));
    }, 250); // debounce typing
    return () => clearTimeout(t);
  }, [username, action, page, toast]);

  const rows = data?.content ?? [];

  return (
    <>
      <div className="page-head">
        <div><h1>Audit log</h1><p>Every sign-in and change, newest first{data ? ` · ${data.totalElements} entries` : ''}</p></div>
      </div>

      <section className="card">
        <div className="toolbar">
          <input className="input grow" placeholder="Filter by username…" value={username}
                 onChange={(e) => { setUsername(e.target.value); setPage(0); }} />
          <select className="select" value={action} onChange={(e) => { setAction(e.target.value); setPage(0); }} aria-label="Action">
            <option value="">All actions</option>
            {ACTIONS.map((a) => <option key={a} value={a}>{label(a)}</option>)}
          </select>
        </div>
        <div className="table-wrap">
          <table>
            <thead><tr><th>When</th><th>User</th><th>Action</th><th>Details</th><th>IP</th></tr></thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.id}>
                  <td className="tabular muted">{new Date(r.timestamp).toLocaleString('en-IN', { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit', second: '2-digit' })}</td>
                  <td><strong>{r.username}</strong></td>
                  <td><span className={`badge ${SECURITY.has(r.action) ? 'ABSENT' : 'NOT_MARKED'}`}>{label(r.action)}</span></td>
                  <td style={{ whiteSpace: 'normal', minWidth: 240 }}>{r.details ?? <span className="muted">—</span>}</td>
                  <td className="muted tabular">{r.ipAddress ?? '—'}</td>
                </tr>
              ))}
              {data && !rows.length && <tr><td colSpan="5" className="empty">No matching entries</td></tr>}
              {!data && <tr><td colSpan="5" className="empty">Loading…</td></tr>}
            </tbody>
          </table>
        </div>
        {data && data.totalPages > 1 && (
          <div className="toolbar" style={{ borderBottom: 0, borderTop: '1px solid var(--border)', justifyContent: 'flex-end' }}>
            <span className="muted">Page {data.page + 1} of {data.totalPages}</span>
            <button className="btn btn-sm" disabled={page === 0} onClick={() => setPage((p) => p - 1)}>Previous</button>
            <button className="btn btn-sm" disabled={page + 1 >= data.totalPages} onClick={() => setPage((p) => p + 1)}>Next</button>
          </div>
        )}
      </section>
    </>
  );
}
