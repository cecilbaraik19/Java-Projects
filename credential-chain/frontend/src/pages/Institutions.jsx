import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { Building2, Plus, KeyRound, LogIn } from 'lucide-react'
import { api } from '../api.js'
import { useAuth } from '../auth.jsx'
import Hash from '../components/Hash.jsx'
import { useToast } from '../components/Toast.jsx'

export default function Institutions() {
  const [list, setList] = useState([])
  const { user } = useAuth()
  const isAdmin = user?.role === 'ADMIN'
  const [form, setForm] = useState({ name: '', email: '', website: '', password: '' })
  const [created, setCreated] = useState(null)
  const toast = useToast()

  const load = () => api.institutions().then(setList).catch((e) => toast(e.message, 'error'))
  useEffect(() => { load() }, [])

  const submit = async (e) => {
    e.preventDefault()
    try {
      const res = await api.registerInstitution(form)
      setCreated({ ...res, password: form.password })
      setForm({ name: '', email: '', website: '', password: '' })
      toast('Institution registered — its public key is now on the blockchain', 'success')
      load()
    } catch (err) { toast(err.message, 'error') }
  }

  const set = (k) => (e) => setForm({ ...form, [k]: e.target.value })

  return (
    <div className="page">
      <header className="page-head"><div><h1>Institutions</h1><p>Trusted issuers. Each one's ECDSA public key is anchored on-chain via a REGISTER_ISSUER transaction.</p></div></header>

      {isAdmin ? (
        <div className="grid-2">
          <form className="card form" onSubmit={submit}>
            <h2><Plus size={18} /> Register an institution</h2>
            <label>Name<input value={form.name} onChange={set('name')} required /></label>
            <label>Staff login email<input type="email" value={form.email} onChange={set('email')} required /></label>
            <label>Staff login password<input type="password" minLength={6} value={form.password} onChange={set('password')} required /></label>
            <label>Website<input value={form.website} onChange={set('website')} /></label>
            <button className="btn primary">Generate keys & register</button>
            <p className="muted small">An ECDSA key pair is generated and the public key is anchored on the blockchain.</p>
          </form>

          {created ? (
            <div className="card highlight">
              <h2><KeyRound size={18} /> {created.institution.name} is registered</h2>
              <p>Give these sign-in details to the college's staff:</p>
              <dl className="details">
                <dt>Email</dt><dd className="mono">{created.loginEmail}</dd>
                <dt>Password</dt><dd className="mono">{created.password}</dd>
              </dl>
              <p className="muted small" style={{ marginTop: 14 }}>API key for scripts (shown only once):</p>
              <code className="secret">{created.apiKey}</code>
            </div>
          ) : <div className="card placeholder">Each institution gets its own key pair and staff login.</div>}
        </div>
      ) : (
        <div className="card">
          <p style={{ marginTop: 0 }}>Only the administrator can register new institutions.</p>
          {!user && <Link className="btn primary" to="/login" state={{ from: '/institutions' }}><LogIn size={16} /> Sign in as admin</Link>}
        </div>
      )}

      <section className="card">
        <table className="table">
          <thead><tr><th>Institution</th><th>ID</th><th>Public key</th><th>Registration tx</th><th>Since</th></tr></thead>
          <tbody>
            {list.map((i) => (
              <tr key={i.id}>
                <td><Building2 size={14} /> {i.name}<div className="muted small">{i.email}</div></td>
                <td className="mono small">{i.id}</td>
                <td><Hash value={i.publicKey} n={12} /></td>
                <td><Hash value={i.registrationTxId} /></td>
                <td className="small">{new Date(i.createdAt).toLocaleDateString()}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>
    </div>
  )
}
