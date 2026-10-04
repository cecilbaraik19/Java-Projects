import { useCallback, useEffect, useState } from 'react';
import { NavLink, Navigate, Route, Routes, useLocation } from 'react-router-dom';
import { useTheme } from './theme.jsx';
import { useAuth } from './auth.jsx';
import { api, ROLE_LABEL } from './api.js';
import { useToast } from './components/Toast.jsx';
import Modal from './components/Modal.jsx';
import {
  IconCalendar, IconCheck, IconClock, IconDashboard, IconEdit, IconKey, IconList, IconLogOut, IconMoon, IconReport,
  IconLock, IconShield, IconSun, IconUsers,
} from './components/Icons.jsx';
import Dashboard from './pages/Dashboard.jsx';
import Attendance from './pages/Attendance.jsx';
import Employees from './pages/Employees.jsx';
import Reports from './pages/Reports.jsx';
import Users from './pages/Users.jsx';
import AuditLog from './pages/AuditLog.jsx';
import MyAttendance from './pages/MyAttendance.jsx';
import MyRequests from './pages/MyRequests.jsx';
import Approvals from './pages/Approvals.jsx';
import Login from './pages/Login.jsx';
import Kiosk from './pages/Kiosk.jsx';
import QrCheckIn from './pages/QrCheckIn.jsx';
import CheckInSecurity from './pages/CheckInSecurity.jsx';
import WorkRules from './pages/WorkRules.jsx';

// Full-screen pages without the sidebar
const BARE = [
  { path: '/kiosk', roles: ['ADMIN', 'MANAGER'], element: <Kiosk /> },
  { path: '/checkin', roles: ['EMPLOYEE', 'MANAGER'], element: <QrCheckIn /> },
];

// Which pages each role can open
function buildNav(refreshCounts) {
  return [
    { to: '/', label: 'Dashboard', icon: IconDashboard, end: true, roles: ['ADMIN', 'MANAGER'], element: <Dashboard /> },
    { to: '/attendance', label: 'Attendance', icon: IconClock, roles: ['ADMIN', 'MANAGER'], element: <Attendance /> },
    { to: '/approvals', label: 'Approvals', icon: IconCheck, roles: ['ADMIN', 'MANAGER'], badge: true, element: <Approvals onChanged={refreshCounts} /> },
    { to: '/employees', label: 'Employees', icon: IconUsers, roles: ['ADMIN', 'MANAGER'], element: <Employees /> },
    { to: '/reports', label: 'Reports', icon: IconReport, roles: ['ADMIN', 'MANAGER'], element: <Reports /> },
    { to: '/my', label: 'My Attendance', icon: IconCalendar, roles: ['EMPLOYEE', 'MANAGER'], element: <MyAttendance /> },
    { to: '/requests', label: 'My Requests', icon: IconEdit, roles: ['EMPLOYEE', 'MANAGER'], element: <MyRequests /> },
    { to: '/users', label: 'Users', icon: IconShield, roles: ['ADMIN'], element: <Users /> },
    { to: '/work-rules', label: 'Holidays & shifts', icon: IconCalendar, roles: ['ADMIN'], element: <WorkRules /> },
    { to: '/security', label: 'Check-in security', icon: IconLock, roles: ['ADMIN'], element: <CheckInSecurity /> },
    { to: '/audit', label: 'Audit log', icon: IconList, roles: ['ADMIN'], element: <AuditLog /> },
  ];
}

/** Pending approvals count for the nav badge (refreshed every minute). */
function usePendingCount(enabled) {
  const [count, setCount] = useState(0);
  const refresh = useCallback(() => {
    if (!enabled) return;
    api.approvalCounts().then((c) => setCount(c.leave + c.corrections)).catch(() => {});
  }, [enabled]);
  useEffect(() => {
    refresh();
    if (!enabled) return undefined;
    const id = setInterval(refresh, 60000);
    return () => clearInterval(id);
  }, [refresh, enabled]);
  return [count, refresh];
}

function ThemeSwitch() {
  const { theme, setTheme } = useTheme();
  return (
    <div className="theme-switch" role="group" aria-label="Color theme">
      <button aria-pressed={theme === 'light'} onClick={() => setTheme('light')} title="Light mode">
        <IconSun /><span>Light</span>
      </button>
      <button aria-pressed={theme === 'dark'} onClick={() => setTheme('dark')} title="Dark mode">
        <IconMoon /><span>Dark</span>
      </button>
    </div>
  );
}

function ChangePasswordModal({ onClose }) {
  const { replaceSession } = useAuth();
  const toast = useToast();
  const [form, setForm] = useState({ current: '', next: '', confirm: '' });
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const set = (k) => (e) => setForm({ ...form, [k]: e.target.value });

  const save = async (e) => {
    e?.preventDefault();
    if (form.next !== form.confirm) { setError('New passwords do not match'); return; }
    setBusy(true); setError('');
    try {
      replaceSession(await api.changePassword(form.current, form.next));
      toast('Password changed. Other sessions have been signed out.');
      onClose();
    } catch (err) { setError(err.message); }
    setBusy(false);
  };

  return (
    <Modal title="Change password" onClose={onClose}
           footer={<><button className="btn" onClick={onClose}>Cancel</button>
             <button className="btn btn-primary" onClick={save} disabled={busy}>{busy ? 'Saving…' : 'Change password'}</button></>}>
      <form className="form-grid" onSubmit={save}>
        <div className="field full"><label htmlFor="cp">Current password</label>
          <input id="cp" type="password" className="input" autoComplete="current-password" value={form.current} onChange={set('current')} /></div>
        <div className="field full"><label htmlFor="np">New password</label>
          <input id="np" type="password" className="input" autoComplete="new-password" value={form.next} onChange={set('next')} /></div>
        <div className="field full"><label htmlFor="np2">Confirm new password</label>
          <input id="np2" type="password" className="input" autoComplete="new-password" value={form.confirm} onChange={set('confirm')} /></div>
        <p className="muted full" style={{ margin: 0, fontSize: 12.5 }}>At least 8 characters with upper case, lower case and a number.</p>
        <button type="submit" hidden />
      </form>
      {error && <div className="error-text">{error}</div>}
    </Modal>
  );
}

function UserMenu() {
  const { user, logout } = useAuth();
  const [changing, setChanging] = useState(false);
  const initials = (user.employeeName || user.username).split(/\s+/).slice(0, 2).map((p) => p[0]).join('').toUpperCase();
  return (
    <div className="user-menu">
      <div className="person">
        <div className="avatar">{initials}</div>
        <div style={{ minWidth: 0 }}>
          <div className="user-name">{user.employeeName || user.username}</div>
          <small>{ROLE_LABEL[user.role]}{user.department ? ` · ${user.department}` : ''}</small>
        </div>
      </div>
      <div className="user-actions">
        <button className="icon-btn" title="Change password" aria-label="Change password" onClick={() => setChanging(true)}><IconKey /></button>
        <button className="icon-btn" title="Sign out" aria-label="Sign out" onClick={logout}><IconLogOut /></button>
      </div>
      {changing && <ChangePasswordModal onClose={() => setChanging(false)} />}
    </div>
  );
}

export default function App() {
  const { user, checking } = useAuth();
  const reviewer = user?.role === 'ADMIN' || user?.role === 'MANAGER';
  const [pendingCount, refreshCounts] = usePendingCount(reviewer);
  const location = useLocation();

  if (checking) return <div className="empty" style={{ paddingTop: '30vh' }}>Loading…</div>;
  if (!user) return <Login />;

  const bare = BARE.find((b) => b.path === location.pathname && b.roles.includes(user.role));
  if (bare) return bare.element;

  const allowed = buildNav(refreshCounts).filter((n) => n.roles.includes(user.role));
  const home = allowed[0]?.to ?? '/';

  return (
    <div className="app">
      <aside className="sidebar">
        <div className="brand">
          <div className="brand-mark"><IconClock width="17" height="17" /></div>
          <span>AttendTrack</span>
        </div>
        {allowed.map(({ to, label, icon: Icon, end, badge }) => (
          <NavLink key={to} to={to} end={end} className="nav-link" title={label}>
            <Icon /><span>{label}</span>
            {badge && pendingCount > 0 && <em className="nav-badge" aria-label={`${pendingCount} pending`}>{pendingCount}</em>}
          </NavLink>
        ))}
        <div className="sidebar-foot">
          <UserMenu />
          <ThemeSwitch />
        </div>
      </aside>

      <main className="main">
        <Routes>
          {allowed.map((n) => <Route key={n.to} path={n.to} element={n.element} />)}
          <Route path="*" element={<Navigate to={home} replace />} />
        </Routes>
      </main>
    </div>
  );
}
