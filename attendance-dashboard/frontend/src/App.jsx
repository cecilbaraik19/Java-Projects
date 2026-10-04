import { NavLink, Navigate, Route, Routes } from 'react-router-dom';
import { useTheme } from './theme.jsx';
import { IconClock, IconDashboard, IconMoon, IconReport, IconSun, IconUsers } from './components/Icons.jsx';
import Dashboard from './pages/Dashboard.jsx';
import Attendance from './pages/Attendance.jsx';
import Employees from './pages/Employees.jsx';
import Reports from './pages/Reports.jsx';

const NAV = [
  { to: '/', label: 'Dashboard', icon: IconDashboard, end: true },
  { to: '/attendance', label: 'Attendance', icon: IconClock },
  { to: '/employees', label: 'Employees', icon: IconUsers },
  { to: '/reports', label: 'Reports', icon: IconReport },
];

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

export default function App() {
  return (
    <div className="app">
      <aside className="sidebar">
        <div className="brand">
          <div className="brand-mark"><IconClock width="17" height="17" /></div>
          <span>AttendTrack</span>
        </div>
        {NAV.map(({ to, label, icon: Icon, end }) => (
          <NavLink key={to} to={to} end={end} className="nav-link" title={label}>
            <Icon /><span>{label}</span>
          </NavLink>
        ))}
        <div className="sidebar-foot"><ThemeSwitch /></div>
      </aside>

      <main className="main">
        <Routes>
          <Route path="/" element={<Dashboard />} />
          <Route path="/attendance" element={<Attendance />} />
          <Route path="/employees" element={<Employees />} />
          <Route path="/reports" element={<Reports />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </main>
    </div>
  );
}
