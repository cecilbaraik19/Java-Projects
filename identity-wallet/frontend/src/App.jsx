import { Navigate, Route, Routes } from 'react-router-dom'
import { useAuth } from './auth.jsx'
import Layout from './components/Layout.jsx'
import { Spinner } from './components/ui.jsx'
import Login from './pages/Login.jsx'
import Dashboard from './pages/Dashboard.jsx'
import WalletPage from './pages/Wallet.jsx'
import CredentialPage from './pages/CredentialPage.jsx'
import Shares from './pages/Shares.jsx'
import Vault from './pages/Vault.jsx'
import Security from './pages/Security.jsx'
import IssuerPortal from './pages/IssuerPortal.jsx'
import Admin from './pages/Admin.jsx'
import Verify from './pages/Verify.jsx'

const HOME = { USER: '/dashboard', ISSUER: '/issuer', ADMIN: '/admin' }

function Guard({ roles, children }) {
  const { user } = useAuth()
  if (!user) return <Navigate to="/login" replace />
  if (roles && !roles.includes(user.role)) return <Navigate to={HOME[user.role]} replace />
  return children
}

export default function App() {
  const { user, ready } = useAuth()
  if (!ready) return <div className="center-page"><Spinner /></div>
  return (
    <Routes>
      <Route path="/login" element={user ? <Navigate to={HOME[user.role]} replace /> : <Login />} />
      <Route path="/v/:token" element={<Verify />} />
      <Route path="/verify" element={user ? <Guard><Layout /></Guard> : <Verify />}>
        {user && <Route index element={<Verify embedded />} />}
      </Route>
      <Route element={<Guard><Layout /></Guard>}>
        <Route path="/dashboard" element={<Guard roles={['USER']}><Dashboard /></Guard>} />
        <Route path="/wallet" element={<Guard roles={['USER']}><WalletPage /></Guard>} />
        <Route path="/wallet/:id" element={<Guard roles={['USER']}><CredentialPage /></Guard>} />
        <Route path="/shares" element={<Guard roles={['USER']}><Shares /></Guard>} />
        <Route path="/vault" element={<Guard roles={['USER']}><Vault /></Guard>} />
        <Route path="/security" element={<Security />} />
        <Route path="/issuer" element={<Guard roles={['ISSUER']}><IssuerPortal /></Guard>} />
        <Route path="/admin" element={<Guard roles={['ADMIN']}><Admin /></Guard>} />
      </Route>
      <Route path="*" element={<Navigate to={user ? HOME[user.role] : '/login'} replace />} />
    </Routes>
  )
}
