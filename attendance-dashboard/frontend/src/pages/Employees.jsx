import { useCallback, useEffect, useState } from 'react';
import { api, fmtDate, fmtShift, fmtTime, toIso, todayIso } from '../api.js';
import { useToast } from '../components/Toast.jsx';
import { useAuth } from '../auth.jsx';
import Modal from '../components/Modal.jsx';
import Person from '../components/Person.jsx';
import CalendarGrid from '../components/CalendarGrid.jsx';
import { IconCalendar, IconEdit, IconPlus, IconTrash } from '../components/Icons.jsx';

const EMPTY = { employeeCode: '', fullName: '', email: '', department: '', designation: '', joinDate: '', active: true };

function EmployeeForm({ initial, departments, shifts, onClose, onSaved }) {
  const toast = useToast();
  const [form, setForm] = useState({ ...EMPTY, ...initial, joinDate: initial?.joinDate ?? '', designation: initial?.designation ?? '',
    shiftId: initial?.shift?.id ?? '' });
  const [error, setError] = useState('');
  const [saving, setSaving] = useState(false);
  const set = (k) => (e) => setForm({ ...form, [k]: e.target.type === 'checkbox' ? e.target.checked : e.target.value });

  const save = async (e) => {
    e?.preventDefault();
    setSaving(true); setError('');
    const body = { ...form, joinDate: form.joinDate || null, designation: form.designation || null,
      shiftId: form.shiftId ? Number(form.shiftId) : null };
    delete body.shift;
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
        <div className="field"><label htmlFor="sh">Shift</label>
          <select id="sh" className="select" value={form.shiftId} onChange={set('shiftId')}>
            <option value="">Default hours</option>
            {shifts.map((s) => <option key={s.id} value={s.id}>{fmtShift(s)}</option>)}
          </select></div>
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
  const load = useCallback((month) => api.employeeCalendar(employee.id, month), [employee.id]);
  return (
    <Modal title={`${employee.fullName} · attendance`} onClose={onClose}>
      <p className="muted" style={{ marginTop: 0, fontSize: 13 }}>
        {employee.employeeCode} · {employee.department} · Shift: {employee.shift ? fmtShift(employee.shift) : 'Default hours'}
      </p>
      <CalendarGrid load={load} />
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
  const [shifts, setShifts] = useState([]);

  const load = useCallback(async () => {
    try {
      const [e, d, s] = await Promise.all([api.employees(), api.departments(), api.shifts().catch(() => [])]);
      setList(e); setDepartments(d); setShifts(s);
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
            <thead><tr><th>Employee</th><th>Code</th><th>Department</th><th>Shift</th><th>Email</th><th>Joined</th><th>Status</th><th className="num">Actions</th></tr></thead>
            <tbody>
              {filtered.map((e) => (
                <tr key={e.id}>
                  <td><Person name={e.fullName} sub={e.designation} /></td>
                  <td className="muted tabular">{e.employeeCode}</td>
                  <td>{e.department}</td>
                  <td className="muted">{e.shift ? fmtShift(e.shift) : 'Default'}</td>
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

      {editing && <EmployeeForm initial={editing} departments={departments} shifts={shifts} onClose={() => setEditing(null)}
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
