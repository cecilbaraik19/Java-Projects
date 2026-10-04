import { useCallback, useEffect, useState } from 'react';
import { api, fmtDate, fmtTime, toIso, todayIso } from '../api.js';
import { useToast } from '../components/Toast.jsx';
import { useAuth } from '../auth.jsx';
import Modal from '../components/Modal.jsx';
import Person from '../components/Person.jsx';
import StatusBadge from '../components/StatusBadge.jsx';
import { IconCalendar, IconEdit, IconPlus, IconTrash } from '../components/Icons.jsx';

const EMPTY = { employeeCode: '', fullName: '', email: '', department: '', designation: '', joinDate: '', active: true };

function EmployeeForm({ initial, departments, onClose, onSaved }) {
  const toast = useToast();
  const [form, setForm] = useState({ ...EMPTY, ...initial, joinDate: initial?.joinDate ?? '', designation: initial?.designation ?? '' });
  const [error, setError] = useState('');
  const [saving, setSaving] = useState(false);
  const set = (k) => (e) => setForm({ ...form, [k]: e.target.type === 'checkbox' ? e.target.checked : e.target.value });

  const save = async (e) => {
    e?.preventDefault();
    setSaving(true); setError('');
    const body = { ...form, joinDate: form.joinDate || null, designation: form.designation || null };
    try {
      initial?.id ? await api.updateEmployee(initial.id, body) : await api.createEmployee(body);
      toast(initial?.id ? 'Employee updated' : 'Employee added');
      onSaved();
    } catch (err) {
      setError(err.message);
    } finally {
      setSaving(false);
    }
  };

  return (
    <Modal title={initial?.id ? 'Edit employee' : 'Add employee'} onClose={onClose}
           footer={<><button className="btn" onClick={onClose}>Cancel</button>
             <button className="btn btn-primary" onClick={save} disabled={saving}>{saving ? 'Saving…' : 'Save'}</button></>}>
      <form className="form-grid" onSubmit={save}>
        <div className="field"><label htmlFor="code">Employee code *</label>
          <input id="code" className="input" value={form.employeeCode} onChange={set('employeeCode')} placeholder="EMP025" required maxLength={20} /></div>
        <div className="field"><label htmlFor="name">Full name *</label>
          <input id="name" className="input" value={form.fullName} onChange={set('fullName')} required maxLength={100} /></div>
        <div className="field full"><label htmlFor="email">Email *</label>
          <input id="email" type="email" className="input" value={form.email} onChange={set('email')} required maxLength={120} /></div>
        <div className="field"><label htmlFor="dept">Department *</label>
          <input id="dept" className="input" list="dept-list" value={form.department} onChange={set('department')} required maxLength={60} />
          <datalist id="dept-list">{departments.map((d) => <option key={d} value={d} />)}</datalist></div>
        <div className="field"><label htmlFor="des">Designation</label>
          <input id="des" className="input" value={form.designation} onChange={set('designation')} maxLength={80} /></div>
        <div className="field"><label htmlFor="jd">Join date</label>
          <input id="jd" type="date" className="input" value={form.joinDate} onChange={set('joinDate')} max={todayIso()} /></div>
        <div className="field" style={{ justifyContent: 'flex-end' }}>
          <label style={{ display: 'flex', gap: 8, alignItems: 'center', fontSize: 14, color: 'var(--text)' }}>
            <input type="checkbox" checked={form.active} onChange={set('active')} /> Active
          </label>
        </div>
        <button type="submit" hidden />
      </form>
      {error && <div className="error-text">{error}</div>}
    </Modal>
  );
}

function HistoryModal({ employee, onClose }) {
  const [rows, setRows] = useState(null);
  useEffect(() => {
    const to = new Date();
    const from = new Date(); from.setDate(from.getDate() - 30);
    api.employeeHistory(employee.id, toIso(from), toIso(to)).then(setRows).catch(() => setRows([]));
  }, [employee.id]);

  const attended = rows?.filter((r) => ['PRESENT', 'LATE', 'HALF_DAY'].includes(r.status)).length ?? 0;
  const late = rows?.filter((r) => r.status === 'LATE').length ?? 0;
  const hours = rows?.reduce((s, r) => s + r.hoursWorked, 0) ?? 0;

  return (
    <Modal title={`${employee.fullName} · last 30 days`} onClose={onClose}>
      <div className="stats" style={{ gridTemplateColumns: 'repeat(3, 1fr)' }}>
        <div className="card stat"><div className="stat-label">Days in</div><div className="stat-value">{attended}</div></div>
        <div className="card stat"><div className="stat-label">Late</div><div className="stat-value">{late}</div></div>
        <div className="card stat"><div className="stat-label">Hours</div><div className="stat-value">{Math.round(hours)}</div></div>
      </div>
      <div className="table-wrap" style={{ maxHeight: 320, border: '1px solid var(--border)', borderRadius: 10 }}>
        <table>
          <thead><tr><th>Date</th><th>In</th><th>Out</th><th>Status</th></tr></thead>
          <tbody>
            {rows?.map((r) => (
              <tr key={r.id}>
                <td>{fmtDate(r.date, { weekday: 'short', day: 'numeric', month: 'short' })}</td>
                <td className="tabular">{fmtTime(r.checkIn)}</td>
                <td className="tabular">{fmtTime(r.checkOut)}</td>
                <td><StatusBadge status={r.status} /></td>
              </tr>
            ))}
            {rows && !rows.length && <tr><td colSpan="4" className="empty">No records</td></tr>}
            {!rows && <tr><td colSpan="4" className="empty">Loading…</td></tr>}
          </tbody>
        </table>
      </div>
    </Modal>
  );
}

export default function Employees() {
  const toast = useToast();
  const isAdmin = useAuth().user.role === 'ADMIN';
  const [list, setList] = useState([]);
  const [departments, setDepartments] = useState([]);
  const [q, setQ] = useState('');
  const [dept, setDept] = useState('');
  const [editing, setEditing] = useState(null);   // {} for new, employee for edit
  const [deleting, setDeleting] = useState(null);
  const [history, setHistory] = useState(null);

  const load = useCallback(async () => {
    try {
      const [e, d] = await Promise.all([api.employees(), api.departments()]);
      setList(e); setDepartments(d);
    } catch (err) { toast(err.message, 'error'); }
  }, [toast]);
  useEffect(() => { load(); }, [load]);

  const filtered = list.filter((e) =>
    (!q || `${e.fullName} ${e.employeeCode} ${e.email}`.toLowerCase().includes(q.toLowerCase())) &&
    (!dept || e.department === dept));

  const confirmDelete = async () => {
    try { await api.deleteEmployee(deleting.id); toast(`${deleting.fullName} removed`); setDeleting(null); load(); }
    catch (err) { toast(err.message, 'error'); }
  };

  return (
    <>
      <div className="page-head">
        <div><h1>Employees</h1><p>{list.filter((e) => e.active).length} active of {list.length}</p></div>
        {isAdmin && <button className="btn btn-primary" onClick={() => setEditing({})}><IconPlus />Add employee</button>}
      </div>

      <section className="card">
        <div className="toolbar">
          <input className="input grow" placeholder="Search name, code or email…" value={q} onChange={(e) => setQ(e.target.value)} />
          <select className="select" value={dept} onChange={(e) => setDept(e.target.value)} aria-label="Department">
            <option value="">All departments</option>
            {departments.map((d) => <option key={d}>{d}</option>)}
          </select>
        </div>
        <div className="table-wrap">
          <table>
            <thead><tr><th>Employee</th><th>Code</th><th>Department</th><th>Email</th><th>Joined</th><th>Status</th><th className="num">Actions</th></tr></thead>
            <tbody>
              {filtered.map((e) => (
                <tr key={e.id}>
                  <td><Person name={e.fullName} sub={e.designation} /></td>
                  <td className="muted tabular">{e.employeeCode}</td>
                  <td>{e.department}</td>
                  <td className="muted">{e.email}</td>
                  <td className="muted">{e.joinDate ? fmtDate(e.joinDate, { day: 'numeric', month: 'short', year: 'numeric' }) : '—'}</td>
                  <td>{e.active ? <span className="badge PRESENT">Active</span> : <span className="badge inactive">Inactive</span>}</td>
                  <td className="num">
                    <button className="icon-btn" title="Attendance history" aria-label="Attendance history" onClick={() => setHistory(e)}><IconCalendar /></button>
                    {isAdmin && <>
                      <button className="icon-btn" title="Edit" aria-label="Edit" onClick={() => setEditing(e)}><IconEdit /></button>
                      <button className="icon-btn" title="Delete" aria-label="Delete" onClick={() => setDeleting(e)}><IconTrash /></button>
                    </>}
                  </td>
                </tr>
              ))}
              {!filtered.length && <tr><td colSpan="7" className="empty">No employees found</td></tr>}
            </tbody>
          </table>
        </div>
      </section>

      {editing && <EmployeeForm initial={editing} departments={departments} onClose={() => setEditing(null)}
                                onSaved={() => { setEditing(null); load(); }} />}
      {history && <HistoryModal employee={history} onClose={() => setHistory(null)} />}
      {deleting && (
        <Modal title="Delete employee?" onClose={() => setDeleting(null)}
               footer={<><button className="btn" onClick={() => setDeleting(null)}>Cancel</button>
                 <button className="btn btn-danger" onClick={confirmDelete}><IconTrash />Delete</button></>}>
          This permanently removes <strong>{deleting.fullName}</strong> and all of their attendance records.
          To keep history, edit the employee and untick <em>Active</em> instead.
        </Modal>
      )}
    </>
  );
}
