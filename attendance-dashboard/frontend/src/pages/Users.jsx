import { useCallback, useEffect, useState } from 'react';
import { api, fmtDateTime, ROLE_LABEL } from '../api.js';
import { useAuth } from '../auth.jsx';
import { useToast } from '../components/Toast.jsx';
import Modal from '../components/Modal.jsx';
import Person from '../components/Person.jsx';
import { IconEdit, IconKey, IconPlus, IconTrash } from '../components/Icons.jsx';

const ROLES = ['ADMIN', 'MANAGER', 'EMPLOYEE'];
const PW_HINT = 'At least 8 characters with upper case, lower case and a number; must not contain the username.';

function UserForm({ initial, employees, users, onClose, onSaved }) {
  const toast = useToast();
  const isNew = !initial.id;
  const [form, setForm] = useState({
    username: initial.username ?? '', password: '', role: initial.role ?? 'EMPLOYEE',
    employeeId: initial.employeeId ?? '', enabled: initial.enabled ?? true,
  });
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const set = (k) => (e) => setForm({ ...form, [k]: e.target.type === 'checkbox' ? e.target.checked : e.target.value });

  // employees that don't already have an account (plus the one linked to this user)
  const linked = new Set(users.filter((u) => u.id !== initial.id && u.employeeId).map((u) => u.employeeId));
  const available = employees.filter((e) => !linked.has(e.id));

  const save = async (e) => {
    e?.preventDefault();
    setBusy(true); setError('');
    const employeeId = form.employeeId ? Number(form.employeeId) : null;
    try {
      if (isNew) await api.createUser({ username: form.username, password: form.password, role: form.role, employeeId });
      else await api.updateUser(initial.id, { role: form.role, enabled: form.enabled, employeeId });
      toast(isNew ? `Account ${form.username} created` : 'Account updated');
      onSaved();
    } catch (err) { setError(err.message); }
    setBusy(false);
  };

  return (
    <Modal title={isNew ? 'New account' : `Edit ${initial.username}`} onClose={onClose}
           footer={<><button className="btn" onClick={onClose}>Cancel</button>
             <button className="btn btn-primary" onClick={save} disabled={busy}>{busy ? 'Saving…' : 'Save'}</button></>}>
      <form className="form-grid" onSubmit={save}>
        {isNew && <>
          <div className="field"><label htmlFor="un">Username *</label>
            <input id="un" className="input" value={form.username} onChange={set('username')} maxLength={40} autoComplete="off" /></div>
          <div className="field"><label htmlFor="pw">Password *</label>
            <input id="pw" type="password" className="input" value={form.password} onChange={set('password')} autoComplete="new-password" /></div>
          <p className="muted full" style={{ margin: '-6px 0 0', fontSize: 12.5 }}>{PW_HINT}</p>
        </>}
        <div className="field"><label htmlFor="rl">Role *</label>
          <select id="rl" className="select" value={form.role} onChange={set('role')}>
            {ROLES.map((r) => <option key={r} value={r}>{ROLE_LABEL[r]}</option>)}
          </select></div>
        <div className="field"><label htmlFor="em">Linked employee {form.role !== 'ADMIN' && '*'}</label>
          <select id="em" className="select" value={form.employeeId} onChange={set('employeeId')}>
            <option value="">{form.role === 'ADMIN' ? '— none —' : 'Select…'}</option>
            {available.map((e) => <option key={e.id} value={e.id}>{e.fullName} ({e.department})</option>)}
          </select></div>
        <p className="muted full" style={{ margin: '-6px 0 0', fontSize: 12.5 }}>
          Managers see their linked employee's department. Employees see only their own attendance.
        </p>
        {!isNew && (
          <label className="full" style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
            <input type="checkbox" checked={form.enabled} onChange={set('enabled')} /> Account enabled (enabling also unlocks it)
          </label>
        )}
        <button type="submit" hidden />
      </form>
      {error && <div className="error-text">{error}</div>}
    </Modal>
  );
}

function ResetPasswordModal({ user, onClose }) {
  const toast = useToast();
  const [pw, setPw] = useState('');
  const [error, setError] = useState('');
  const save = async (e) => {
    e?.preventDefault();
    try {
      await api.resetPassword(user.id, pw);
      toast(`Password reset for ${user.username}. Their open sessions were signed out.`);
      onClose();
    } catch (err) { setError(err.message); }
  };
  return (
    <Modal title={`Reset password · ${user.username}`} onClose={onClose}
           footer={<><button className="btn" onClick={onClose}>Cancel</button><button className="btn btn-primary" onClick={save}>Reset</button></>}>
      <form onSubmit={save} className="field">
        <label htmlFor="rp">New password</label>
        <input id="rp" type="password" className="input" value={pw} onChange={(e) => setPw(e.target.value)} autoComplete="new-password" autoFocus />
        <p className="muted" style={{ margin: '4px 0 0', fontSize: 12.5 }}>{PW_HINT}</p>
      </form>
      {error && <div className="error-text">{error}</div>}
    </Modal>
  );
}

export default function Users() {
  const toast = useToast();
  const { user: me } = useAuth();
  const [users, setUsers] = useState([]);
  const [employees, setEmployees] = useState([]);
  const [editing, setEditing] = useState(null);
  const [resetting, setResetting] = useState(null);
  const [deleting, setDeleting] = useState(null);

  const load = useCallback(async () => {
    try {
      const [u, e] = await Promise.all([api.users(), api.employees(true)]);
      setUsers(u); setEmployees(e);
    } catch (err) { toast(err.message, 'error'); }
  }, [toast]);
  useEffect(() => { load(); }, [load]);

  const remove = async () => {
    try { await api.deleteUser(deleting.id); toast(`${deleting.username} deleted`); setDeleting(null); load(); }
    catch (err) { toast(err.message, 'error'); }
  };

  return (
    <>
      <div className="page-head">
        <div><h1>Users</h1><p>Login accounts and what each person can access</p></div>
        <button className="btn btn-primary" onClick={() => setEditing({})}><IconPlus />New account</button>
      </div>

      <section className="card">
        <div className="table-wrap">
          <table>
            <thead><tr><th>Username</th><th>Role</th><th>Linked employee</th><th>Status</th><th>Last login</th><th className="num">Actions</th></tr></thead>
            <tbody>
              {users.map((u) => (
                <tr key={u.id}>
                  <td><strong>{u.username}</strong>{u.id === me.id && <span className="muted"> (you)</span>}</td>
                  <td><span className={`badge role-${u.role}`}>{ROLE_LABEL[u.role]}</span></td>
                  <td>{u.employeeName ? <Person name={u.employeeName} sub={u.department} /> : <span className="muted">—</span>}</td>
                  <td>
                    {!u.enabled ? <span className="badge inactive">Disabled</span>
                      : u.locked ? <span className="badge ABSENT">Locked</span>
                        : <span className="badge PRESENT">Active</span>}
                  </td>
                  <td className="muted">{fmtDateTime(u.lastLoginAt)}</td>
                  <td className="num">
                    <button className="icon-btn" title="Edit" aria-label={`Edit ${u.username}`} onClick={() => setEditing(u)}><IconEdit /></button>
                    <button className="icon-btn" title="Reset password" aria-label={`Reset password for ${u.username}`} onClick={() => setResetting(u)}><IconKey /></button>
                    {u.id !== me.id && <button className="icon-btn" title="Delete" aria-label={`Delete ${u.username}`} onClick={() => setDeleting(u)}><IconTrash /></button>}
                  </td>
                </tr>
              ))}
              {!users.length && <tr><td colSpan="6" className="empty">No accounts</td></tr>}
            </tbody>
          </table>
        </div>
      </section>

      {editing && <UserForm initial={editing} employees={employees} users={users}
                            onClose={() => setEditing(null)} onSaved={() => { setEditing(null); load(); }} />}
      {resetting && <ResetPasswordModal user={resetting} onClose={() => setResetting(null)} />}
      {deleting && (
        <Modal title="Delete account?" onClose={() => setDeleting(null)}
               footer={<><button className="btn" onClick={() => setDeleting(null)}>Cancel</button>
                 <button className="btn btn-danger" onClick={remove}><IconTrash />Delete</button></>}>
          <strong>{deleting.username}</strong> will no longer be able to sign in. Their attendance records are kept.
        </Modal>
      )}
    </>
  );
}
