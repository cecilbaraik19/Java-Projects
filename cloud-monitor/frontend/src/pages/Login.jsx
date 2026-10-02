import { useState } from 'react'
import { Navigate } from 'react-router-dom'
import { motion } from 'framer-motion'
import { Activity, Eye, EyeOff, ShieldCheck } from 'lucide-react'
import { useAuth } from '../context/AuthContext'
import { errorMessage } from '../api/client'

export default function Login() {
  const { user, login } = useAuth()
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [show, setShow] = useState(false)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const [shake, setShake] = useState(0)

  if (user) return <Navigate to="/" replace />

  const submit = async (e) => {
    e.preventDefault()
    setBusy(true); setError('')
    try {
      await login(username.trim(), password)
    } catch (err) {
      setError(err.response?.status === 401 ? 'Invalid username or password' : errorMessage(err, 'Cannot reach the server. Is the backend running?'))
      setShake((s) => s + 1)
    } finally {
      setBusy(false)
    }
  }

  const fill = (u, p) => { setUsername(u); setPassword(p) }

  return (
    <div className="relative flex min-h-full items-center justify-center overflow-hidden p-4">
      {/* animated background grid */}
      <div className="pointer-events-none absolute inset-0 opacity-60"
        style={{ backgroundImage: 'linear-gradient(var(--grid) 1px, transparent 1px), linear-gradient(90deg, var(--grid) 1px, transparent 1px)', backgroundSize: '44px 44px', maskImage: 'radial-gradient(ellipse at center, black 30%, transparent 75%)' }} />
      <motion.div className="pointer-events-none absolute h-[520px] w-[520px] rounded-full blur-3xl"
        style={{ background: 'radial-gradient(circle, color-mix(in srgb, var(--accent) 28%, transparent), transparent 70%)' }}
        animate={{ x: [-120, 120, -120], y: [-60, 80, -60] }} transition={{ duration: 18, repeat: Infinity, ease: 'easeInOut' }} />

      <motion.div key={shake} initial={{ opacity: 0, y: 20 }}
        animate={shake ? { opacity: 1, y: 0, x: [0, -10, 10, -6, 6, 0] } : { opacity: 1, y: 0 }}
        transition={{ duration: 0.45 }}
        className="relative w-full max-w-sm rounded-3xl border border-line bg-surface p-8 shadow-2xl">
        <div className="mb-7 flex flex-col items-center text-center">
          <motion.span initial={{ scale: 0, rotate: -30 }} animate={{ scale: 1, rotate: 0 }} transition={{ type: 'spring', delay: 0.1 }}
            className="mb-4 flex h-14 w-14 items-center justify-center rounded-2xl bg-accent text-white shadow-lg">
            <Activity size={28} strokeWidth={2.5} />
          </motion.span>
          <h1 className="text-xl font-bold">CloudPulse</h1>
          <p className="mt-1 text-sm text-muted">Sign in to monitor your cloud resources</p>
        </div>

        <form onSubmit={submit} className="space-y-4">
          <label className="block">
            <span className="mb-1.5 block text-xs font-medium text-ink-2">Username</span>
            <input value={username} onChange={(e) => setUsername(e.target.value)} autoFocus autoComplete="username" required
              className="w-full rounded-xl border border-line bg-surface-2 px-3.5 py-2.5 text-sm outline-none transition focus:border-accent focus:ring-2 focus:ring-[color-mix(in_srgb,var(--accent)_25%,transparent)]" />
          </label>
          <label className="block">
            <span className="mb-1.5 block text-xs font-medium text-ink-2">Password</span>
            <div className="relative">
              <input type={show ? 'text' : 'password'} value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="current-password" required
                className="w-full rounded-xl border border-line bg-surface-2 px-3.5 py-2.5 pr-10 text-sm outline-none transition focus:border-accent focus:ring-2 focus:ring-[color-mix(in_srgb,var(--accent)_25%,transparent)]" />
              <button type="button" onClick={() => setShow(!show)} className="absolute top-1/2 right-2.5 -translate-y-1/2 p-1 text-muted hover:text-ink" aria-label={show ? 'Hide password' : 'Show password'}>
                {show ? <EyeOff size={16} /> : <Eye size={16} />}
              </button>
            </div>
          </label>

          {error && <motion.p initial={{ opacity: 0, height: 0 }} animate={{ opacity: 1, height: 'auto' }} className="text-xs font-medium text-[var(--crit)]">{error}</motion.p>}

          <motion.button whileTap={{ scale: 0.97 }} disabled={busy}
            className="flex w-full items-center justify-center gap-2 rounded-xl bg-accent py-2.5 text-sm font-semibold text-white transition hover:brightness-110 disabled:opacity-60">
            {busy ? <span className="h-4 w-4 animate-spin rounded-full border-2 border-white/40 border-t-white" /> : <ShieldCheck size={16} />}
            {busy ? 'Signing in…' : 'Sign in'}
          </motion.button>
        </form>

        <div className="mt-6 rounded-xl border border-dashed border-line p-3 text-xs text-muted">
          <p className="mb-2 font-medium text-ink-2">Demo accounts</p>
          <div className="flex gap-2">
            <button onClick={() => fill('admin', 'admin123')} className="flex-1 rounded-lg bg-surface-2 py-1.5 font-medium text-ink-2 hover:text-ink">Admin</button>
            <button onClick={() => fill('viewer', 'viewer123')} className="flex-1 rounded-lg bg-surface-2 py-1.5 font-medium text-ink-2 hover:text-ink">Viewer</button>
          </div>
        </div>
      </motion.div>
    </div>
  )
}
