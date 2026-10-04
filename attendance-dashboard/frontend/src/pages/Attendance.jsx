import { useCallback, useEffect, useMemo, useState } from 'react';
import { api, fmtDate, fmtTime, STATUS_LABEL, todayIso } from '../api.js';
import { useToast } from '../components/Toast.jsx';
import StatusBadge from '../components/StatusBadge.jsx';
import Person from '../components/Person.jsx';
import Modal from '../components/Modal.jsx';
import { IconEdit, IconLogIn, IconLogOut, IconTrash } from '../components/Icons.jsx';

const STATUSES = ['PRESENT', 'LATE', 'HALF_DAY', 'ON_LEAVE', 'ABSENT'];

function EditRecordModal({ row, date, onClose, onSaved }) {
  const toast = useToast();
  const [form, setForm] = useState({
    status: row.status === 'NOT_MARKED' ? 'PRESENT' : row.status,
    checkIn: row.checkIn?.slice(0, 5) ?? '09:30',
    checkOut: row.checkOut?.slice(0, 5) ?? '',
    note: row.note ?? '',
  });
  const [error, setError] = useState('');
  const [saving, setSaving] = useState(false);
  const needsTimes = ['PRESENT', 'LATE', 'HALF_DAY'].includes(form.status);
  const set = (k) => (e) => setForm({ ...form, [k]: e.target.value });

  const save = async () => {
    setSaving(true); setError('');
    try {
      await api.saveRecord({
        employeeId: row.employeeId, date, status: form.status,
        checkIn: needsTimes ? form.checkIn || null : null,
        checkOut: needsTimes ? form.checkOut || null : null,
        note: form.note || null,
      });
      toast(`Saved attendance for ${row.employeeName}`);
      onSaved();
    } catch (e) {
      setError(e.message);
    } finally {
      setSaving(false);
    }
  };

  return (
    <Modal title={`${row.employeeName} · ${fmtDate(date, { day: 'numeric', month: 'short', year: 'numeric' })}`}
           onClose={onClose}
           footer={<><button className="btn" onClick={onClose}>Cancel</button>
             <button className="btn btn-primary" onClick={save} disabled={saving}>{saving ? 'Saving…' : 'Save'}</button></>}>
      <div className="form-grid">
        <div className="field full">
          <label htmlFor="st">Status</label>
          <select id="st" className="select" value={form.status} onChange={set('status')}>
            {STATUSES.map((s) => <option key={s} value={s}>{STATUS_LABEL[s]}</option>)}
          </select>
        </div>
        {needsTimes && <>
          <div className="field"><label htmlFor="ci">Check in</label>
            <input id="ci" type="time" className="input" value={form.checkIn} onChange={set('checkIn')} /></div>
          <div className="field"><label htmlFor="co">Check out</label>
            <input id="co" type="time" className="input" value={form.checkOut} onChange={set('checkOut')} /></div>
        </>}
        <div className="field full"><label htmlFor="nt">Note</label>
          <input id="nt" className="input" value={form.note} onChange={set('note')} placeholder="e.g. Sick leave, client visit" maxLength={255} /></div>
      </div>
      {error && <div className="error-text">{error}</div>}
    </Modal>
  );
}

export default function Attendance() {
  const toast = useToast();
  const [date, setDate] = useState(todayIso());
  const [rows, setRows] = useState([]);
  const [loading, setLoading] = useState(true);
  const [q, setQ] = useState('');
  const [dept, setDept] = useState('');
  const [status, setStatus] = useState('');
  const [editing, setEditing] = useState(null);
  const [busy, setBusy] = useState(null);
  const isToday = date === todayIso();

  const load = useCallback(async () => {
    setLoading(true);
    try { setRows(await api.daily(date)); } catch (e) { toast(e.message, 'error'); }
    setLoading(false);
  }, [date, toast]);

  useEffect(() => { load(); }, [load]);

  const departments = useMemo(() => [...new Set(rows.map((r) => r.department))].sort(), [rows]);
  const filtered = rows.filter((r) =>
    (!q || `${r.employeeName} ${r.employeeCode}`.toLowerCase().includes(q.toLowerCase())) &&
    (!dept || r.department === dept) &&
    (!status || r.status === status));

  const counts = rows.reduce((acc, r) => ({ ...acc, [r.status]: (acc[r.status] ?? 0) + 1 }), {});

  const act = async (fn, row, verb) => {
    setBusy(row.employeeId);
    try {
      const r = await fn(row.employeeId);
      toast(`${row.employeeName} ${verb} at ${fmtTime(r[verb === 'checked in' ? 'checkIn' : 'checkOut'])}`);
      load();
    } catch (e) { toast(e.message, 'error'); }
    setBusy(null);
  };

  const remove = async (row) => {
    try { await api.deleteRecord(row.id); toast('Record cleared'); load(); } catch (e) { toast(e.message, 'error'); }
  };

  return (
    <>
      <div className="page-head">
        <div>
          <h1>Attendance</h1>
          <p>Mark check-ins and check-outs, or correct past records</p>
        </div>
        <div className="head-actions">
          <input type="date" className="input" value={date} max={todayIso()} onChange={(e) => e.target.value && setDate(e.target.value)} aria-label="Date" />
          {!isToday && <button className="btn" onClick={() => setDate(todayIso())}>Today</button>}
        </div>
      </div>

      <section className="card">
        <div className="toolbar">
          <input className="input grow" placeholder="Search name or code…" value={q} onChange={(e) => setQ(e.target.value)} />
          <select className="select" value={dept} onChange={(e) => setDept(e.target.value)} aria-label="Department">
            <option value="">All departments</option>
            {departments.map((d) => <option key={d}>{d}</option>)}
          </select>
          <select className="select" value={status} onChange={(e) => setStatus(e.target.value)} aria-label="Status">
            <option value="">All statuses ({rows.length})</option>
            {[...STATUSES, 'NOT_MARKED'].map((s) => <option key={s} value={s}>{STATUS_LABEL[s]} ({counts[s] ?? 0})</option>)}
          </select>
        </div>
        <div className="table-wrap">
          <table>
            <thead>
              <tr><th>Employee</th><th>Code</th><th>Check in</th><th>Check out</th><th className="num">Hours</th><th>Status</th><th>Note</th><th className="num">Actions</th></tr>
            </thead>
            <tbody>
              {filtered.map((r) => (
                <tr key={r.employeeId}>
                  <td><Person name={r.employeeName} sub={r.department} /></td>
                  <td className="muted tabular">{r.employeeCode}</td>
                  <td className="tabular">{fmtTime(r.checkIn)}</td>
                  <td className="tabular">{fmtTime(r.checkOut)}</td>
                  <td className="num">{r.hoursWorked ? r.hoursWorked.toFixed(1) : '—'}</td>
                  <td><StatusBadge status={r.status} /></td>
                  <td className="muted" style={{ maxWidth: 180, overflow: 'hidden', textOverflow: 'ellipsis' }}>{r.note ?? ''}</td>
                  <td className="num">
                    <div style={{ display: 'inline-flex', gap: 4, alignItems: 'center' }}>
                      {isToday && !r.checkIn && r.status !== 'ON_LEAVE' && (
                        <button className="btn btn-sm btn-primary" disabled={busy === r.employeeId}
                                onClick={() => act(api.checkIn, r, 'checked in')}><IconLogIn />Check in</button>)}
                      {isToday && r.checkIn && !r.checkOut && (
                        <button className="btn btn-sm" disabled={busy === r.employeeId}
                                onClick={() => act(api.checkOut, r, 'checked out')}><IconLogOut />Check out</button>)}
                      <button className="icon-btn" title="Edit record" aria-label="Edit record" onClick={() => setEditing(r)}><IconEdit /></button>
                      {r.id && <button className="icon-btn" title="Clear record" aria-label="Clear record" onClick={() => remove(r)}><IconTrash /></button>}
                    </div>
                  </td>
                </tr>
              ))}
              {!loading && !filtered.length && <tr><td colSpan="8" className="empty">No employees match these filters</td></tr>}
              {loading && !rows.length && <tr><td colSpan="8" className="empty">Loading…</td></tr>}
            </tbody>
          </table>
        </div>
      </section>

      {editing && <EditRecordModal row={editing} date={date} onClose={() => setEditing(null)}
                                   onSaved={() => { setEditing(null); load(); }} />}
    </>
  );
}
