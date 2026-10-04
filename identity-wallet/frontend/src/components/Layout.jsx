import { NavLink, Outlet } from 'react-router-dom'
import { LayoutDashboard, Wallet, Share2, Lock, ShieldCheck, Stamp, Building2, ScanLine, LogOut, Menu } from 'lucide-react'
import { useState } from 'react'
import { useAuth } from '../auth.jsx'

const NAV = {
  USER: [
    ['/dashboard', 'Dashboard', LayoutDashboard],
    ['/wallet', 'My credentials', Wallet],
    ['/shares', 'Sharing & consent', Share2],
    ['/vault', 'Document vault', Lock],
    ['/security', 'Security', ShieldCheck]
  ],
  ISSUER: [
    ['/issuer', 'Issue credentials', Stamp],
    ['/security', 'Security', ShieldCheck]
  ],
  ADMIN: [
    ['/admin', 'Administration', Building2],
    ['/security', 'Security', ShieldCheck]
  ]
}

export default function Layout() {
  const { user, logout } = useAuth()
  const [open, setOpen] = useState(false)
  return (
    <div className="shell">
      <aside className={`side ${open ? 'open' : ''}`}>
        <div className="brand">
          <img src="/favicon.svg" alt="" width="30" height="30" />
          <div><b>IdentityWallet</b><small>Digital identity</small></div>
        </div>
        <nav onClick={() => setOpen(false)}>
          {NAV[user.role].map(([to, label, I]) => (
            <NavLink key={to} to={to} className={({ isActive }) => isActive ? 'active' : ''}><I size={18} />{label}</NavLink>
          ))}
          <div className="nav-sep" />
          <NavLink to="/verify"><ScanLine size={18} />Verify a credential</NavLink>
        </nav>
        <div className="me">
          <div className="avatar">{user.fullName.split(' ').map(p => p[0]).slice(0, 2).join('')}</div>
          <div className="me-text"><b>{user.fullName}</b><small>{user.email}</small></div>
          <button className="icon-btn" title="Sign out" aria-label="Sign out" onClick={logout}><LogOut size={18} /></button>
        </div>
      </aside>
      <div className="main">
        <header className="topbar">
          <button className="icon-btn mobile-only" onClick={() => setOpen(!open)} aria-label="Menu"><Menu size={20} /></button>
          <span className="role-chip">{user.role === 'USER' ? 'Wallet holder' : user.role === 'ISSUER' ? 'Issuer staff' : 'Administrator'}</span>
          {user.mfaEnabled ? <span className="pill ok">2FA on</span> : <span className="pill warn">2FA off</span>}
        </header>
        <main className="content"><Outlet /></main>
      </div>
      {open && <div className="scrim" onClick={() => setOpen(false)} />}
    </div>
  )
}
