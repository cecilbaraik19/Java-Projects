import { useState } from 'react'
import { Link } from 'react-router-dom'
import { KeyRound, LogIn } from 'lucide-react'
import { api } from '../api.js'
import { useAuth } from '../auth.jsx'
import { useToast } from '../components/Toast.jsx'

/** Shows the signed-in account and lets the user change their password. */
export default function Account() {
  const { user } = useAuth()
  const toast = useToast()
  const [form, setForm] = useState({ currentPassword: '', newPassword: '', confirm: '' })
  const [error, setError] = useState(null)
  const [busy, setBusy] = useState(false)

  if (!user) {
    return (
      <div className="page narrow">
        <div className="card form"><p>Please sign in first.</p>
          <Link className="btn primary" to="/login" state={{ from: '/account' }}><LogIn size={16} /> Sign in</Link></div>
      </div>
    )
  }

  const set = (k) => (e) => setForm({ ...form, [k]: e.target.value })
  const submit = async (e) => {
    e.preventDefault()
    setError(null)
    if (form.newPassword !== form.confirm) { setError('The new passwords do not match'); return }
    setBusy(true)
    try {
      await api.changePassword(form.currentPassword, form.newPassword)
      toast('Password changed', 'success')
      setForm({ currentPassword: '', newPassword: '', confirm: '' })
    } catch (err) { setError(err.message) } finally { setBusy(false) }
  }

  return (
    <div className="page narrow">
      <header className="page-head"><div><h1>My account</h1><p>{user.name}</p></div></header>
      <div className="card">
        <dl className="details">
          <dt>Email</dt><dd className="mono">{user.email}</dd>
          <dt>Role</dt><dd>{user.role === 'ADMIN' ? 'Administrator' : 'College staff'}</dd>
          {user.institutionName && <><dt>Institution</dt><dd>{user.institutionName} <span className="muted">({user.institutionId})</span></dd></>}
        </dl>
      </div>
      <form className="card form" onSubmit={submit}>
        <h2><KeyRound size={18} /> Change password</h2>
        <label>Current password<input type="password" autoComplete="current-password" value={form.currentPassword} onChange={set('currentPassword')} required /></label>
        <label>New password (at least 8 characters)<input type="password" autoComplete="new-password" minLength={8} value={form.newPassword} onChange={set('newPassword')} required /></label>
        <label>Confirm new password<input type="password" autoComplete="new-password" minLength={8} value={form.confirm} onChange={set('confirm')} required /></label>
        {error && <p className="danger-text small">{error}</p>}
        <button className="btn primary" disabled={busy}>{busy ? 'Saving…' : 'Change password'}</button>
        <p className="muted small">Change the demo passwords before sharing the app on the internet.</p>
      </form>
    </div>
  )
}
