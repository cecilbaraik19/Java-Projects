import { useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { LogIn, Eye, EyeOff, ShieldCheck } from 'lucide-react'
import { useAuth } from '../auth.jsx'
import { useToast } from '../components/Toast.jsx'

// Demo logins are only shown when the app is opened on this computer, not over the internet
const IS_LOCAL = ['localhost', '127.0.0.1'].includes(window.location.hostname)

const DEMO = [
  { label: 'College staff', email: 'registrar@demo-institute.edu', password: 'demo123' },
  { label: 'Admin', email: 'admin@credchain.local', password: 'admin123' },
]

export default function Login() {
  const { login } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const toast = useToast()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [show, setShow] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)

  const submit = async (e) => {
    e.preventDefault()
    setBusy(true); setError(null)
    try {
      const user = await login(email.trim(), password)
      toast(`Welcome, ${user.name}`, 'success')
      const next = location.state?.from || (user.role === 'ADMIN' ? '/institutions' : '/issue')
      navigate(next, { replace: true })
    } catch (err) { setError(err.message) } finally { setBusy(false) }
  }

  return (
    <div className="page narrow login-page">
      <header className="page-head">
        <div><h1>Sign in</h1><p>For college staff and administrators. Verifying a credential needs no login.</p></div>
      </header>
      <form className="card form" onSubmit={submit}>
        <label>Email
          <input type="email" autoComplete="username" value={email} onChange={(e) => setEmail(e.target.value)} required />
        </label>
        <label>Password
          <div className="password-field">
            <input type={show ? 'text' : 'password'} autoComplete="current-password" value={password}
              onChange={(e) => setPassword(e.target.value)} required />
            <button type="button" className="icon-btn" onClick={() => setShow(!show)} aria-label={show ? 'Hide password' : 'Show password'}>
              {show ? <EyeOff size={18} /> : <Eye size={18} />}
            </button>
          </div>
        </label>
        {error && <p className="danger-text small">{error}</p>}
        <button className="btn primary" disabled={busy}><LogIn size={16} /> {busy ? 'Signing in…' : 'Sign in'}</button>
      </form>

      {IS_LOCAL && <div className="card">
        <h3 style={{ marginTop: 0 }}><ShieldCheck size={16} /> Demo accounts</h3>
        <table className="table">
          <tbody>
            {DEMO.map((d) => (
              <tr key={d.email} className="clickable" onClick={() => { setEmail(d.email); setPassword(d.password) }}>
                <td>{d.label}</td><td className="mono small">{d.email}</td><td className="mono small">{d.password}</td>
              </tr>
            ))}
          </tbody>
        </table>
        <p className="muted small">Click a row to fill the form. Passwords are stored as salted PBKDF2 hashes, never as plain text.</p>
      </div>}
    </div>
  )
}
