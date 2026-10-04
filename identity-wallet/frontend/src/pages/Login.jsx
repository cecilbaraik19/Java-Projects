import { useState } from 'react'
import { Link } from 'react-router-dom'
import { ShieldCheck, Fingerprint, EyeOff, KeyRound, ScanLine } from 'lucide-react'
import { api } from '../api.js'
import { useAuth } from '../auth.jsx'
import ThemeToggle from '../components/ThemeToggle.jsx'

const DEMO = [
  ['Wallet holder', 'aarav@wallet.demo', 'User@1234'],
  ['Issuer (Govt.)', 'registrar@identity.demo', 'Issuer@123'],
  ['Admin', 'admin@idwallet.local', 'Admin@123']
]

export default function Login() {
  const { acceptSession } = useAuth()
  const [mode, setMode] = useState('login')
  const [f, setF] = useState({ fullName: '', email: '', password: '' })
  const [ticket, setTicket] = useState(null)
  const [code, setCode] = useState('')
  const [err, setErr] = useState('')
  const [busy, setBusy] = useState(false)
  const set = (k) => (e) => setF({ ...f, [k]: e.target.value })

  async function submit(e) {
    e.preventDefault()
    setErr(''); setBusy(true)
    try {
      if (ticket) {
        acceptSession(await api('/auth/mfa', { method: 'POST', body: { ticket, code } }))
      } else {
        const res = await api(mode === 'login' ? '/auth/login' : '/auth/register', { method: 'POST', body: f })
        if (res.mfaRequired) setTicket(res.mfaTicket)
        else acceptSession(res)
      }
    } catch (ex) { setErr(ex.message) } finally { setBusy(false) }
  }

  return (
    <div className="auth-page">
      <ThemeToggle className="auth-toggle" />
      <section className="auth-hero">
        <div className="brand big"><img src="/favicon.svg" alt="" width="40" height="40" /><b>IdentityWallet</b></div>
        <h1>Your identity, in your hands.</h1>
        <p>Keep government, education and employment credentials in one encrypted wallet, and share only what's needed.</p>
        <ul className="hero-points">
          <li><Fingerprint size={20} /><span><b>Cryptographically signed</b> by issuers with ECDSA P-256, so they can't be forged</span></li>
          <li><EyeOff size={20} /><span><b>Selective disclosure</b>: prove you're over 18 without revealing your birth date</span></li>
          <li><KeyRound size={20} /><span><b>AES-256-GCM encrypted vault</b> with per-user keys and 2-factor sign-in</span></li>
        </ul>
        <Link to="/verify" className="btn ghost light"><ScanLine size={18} />Verify a shared credential</Link>
      </section>

      <section className="auth-card">
        {ticket ? (
          <form onSubmit={submit}>
            <h2><ShieldCheck size={22} /> Two-factor check</h2>
            <p className="muted">Enter the 6-digit code from your authenticator app.</p>
            <input className="otp" inputMode="numeric" autoComplete="one-time-code" maxLength={6} autoFocus
                   value={code} onChange={e => setCode(e.target.value.replace(/\D/g, ''))} placeholder="000000" />
            {err && <div className="alert bad">{err}</div>}
            <button className="btn primary full" disabled={busy || code.length !== 6}>Verify</button>
            <button type="button" className="btn ghost full" onClick={() => { setTicket(null); setCode(''); setErr('') }}>Back</button>
          </form>
        ) : (
          <form onSubmit={submit}>
            <div className="tabs">
              <button type="button" className={mode === 'login' ? 'on' : ''} onClick={() => { setMode('login'); setErr('') }}>Sign in</button>
              <button type="button" className={mode === 'register' ? 'on' : ''} onClick={() => { setMode('register'); setErr('') }}>Create wallet</button>
            </div>
            {mode === 'register' && (
              <label>Full name<input value={f.fullName} onChange={set('fullName')} required autoComplete="name" /></label>
            )}
            <label>Email<input type="email" value={f.email} onChange={set('email')} required autoComplete="username" /></label>
            <label>Password<input type="password" value={f.password} onChange={set('password')} required
                                  autoComplete={mode === 'login' ? 'current-password' : 'new-password'} /></label>
            {mode === 'register' && <p className="hint">At least 8 characters with a letter, a digit and a symbol. Your wallet gets its own signing key and DID.</p>}
            {err && <div className="alert bad">{err}</div>}
            <button className="btn primary full" disabled={busy}>{busy ? 'Please wait…' : mode === 'login' ? 'Sign in' : 'Create my wallet'}</button>
            {mode === 'login' && (
              <div className="demo">
                <small>Demo accounts</small>
                <div className="demo-row">
                  {DEMO.map(([l, e, p]) => (
                    <button type="button" key={e} className="chip" onClick={() => setF({ ...f, email: e, password: p })}>{l}</button>
                  ))}
                </div>
              </div>
            )}
          </form>
        )}
      </section>
    </div>
  )
}
