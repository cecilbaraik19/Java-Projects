import { NavLink, Route, Routes } from 'react-router-dom'
import { Blocks, LayoutDashboard, ShieldCheck, Stamp, Building2, GraduationCap, Hexagon } from 'lucide-react'
import Dashboard from './pages/Dashboard.jsx'
import IssuerPortal from './pages/IssuerPortal.jsx'
import Verify from './pages/Verify.jsx'
import Explorer from './pages/Explorer.jsx'
import Institutions from './pages/Institutions.jsx'
import CredentialPage from './pages/CredentialPage.jsx'
import StudentWallet from './pages/StudentWallet.jsx'

const NAV = [
  { to: '/', label: 'Dashboard', icon: LayoutDashboard, end: true },
  { to: '/issue', label: 'Issuer Portal', icon: Stamp },
  { to: '/verify', label: 'Verify', icon: ShieldCheck },
  { to: '/student', label: 'Student Wallet', icon: GraduationCap },
  { to: '/explorer', label: 'Chain Explorer', icon: Blocks },
  { to: '/institutions', label: 'Institutions', icon: Building2 },
]

export default function App() {
  return (
    <div className="app">
      <aside className="sidebar">
        <div className="brand">
          <Hexagon size={26} />
          <div>
            <strong>CredentialChain</strong>
            <span>Verifiable academic records</span>
          </div>
        </div>
        <nav>
          {NAV.map(({ to, label, icon: Icon, end }) => (
            <NavLink key={to} to={to} end={end} className={({ isActive }) => (isActive ? 'active' : '')}>
              <Icon size={18} /> <span>{label}</span>
            </NavLink>
          ))}
        </nav>
        <div className="sidebar-foot">SHA-256 · ECDSA P-256 · PoW</div>
      </aside>
      <main className="content">
        <Routes>
          <Route path="/" element={<Dashboard />} />
          <Route path="/issue" element={<IssuerPortal />} />
          <Route path="/verify" element={<Verify />} />
          <Route path="/verify/:id" element={<Verify />} />
          <Route path="/student" element={<StudentWallet />} />
          <Route path="/explorer" element={<Explorer />} />
          <Route path="/explorer/:index" element={<Explorer />} />
          <Route path="/institutions" element={<Institutions />} />
          <Route path="/credential/:id" element={<CredentialPage />} />
          <Route path="*" element={<div className="card"><h2>Page not found</h2></div>} />
        </Routes>
      </main>
    </div>
  )
}
