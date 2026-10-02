import { useEffect, useState } from 'react'
import { Building2, Plus, KeyRound } from 'lucide-react'
import { api, session } from '../api.js'
import Hash from '../components/Hash.jsx'
import { useToast } from '../components/Toast.jsx'

export default function Institutions() {
  const [list, setList] = useState([])
  const [form, setForm] = useState({ name: '', email: '', website: '' })
  const [created, setCreated] = useState(null)
  const toast = useToast()

  const load = () => api.institutions().then(setList).catch((e) => toast(e.message, 'error'))
  useEffect(() => { load() }, [])

  const submit = async (e) => {
    e.preventDefault()
    try {
      const res = await api.registerInstitution({ ...form, email: form.email || null })
      setCreated(res)
      setForm({ name: '', email: '', website: '' })
      toast('Institution registered — its public key is now on the blockchain', 'success')
      load()
    } catch (err) { toast(err.message, 'error') }
  }

  const set = (k) => (e) => setForm({ ...form, [k]: e.target.value })

  return (
    <div className="page">
      <header className="page-head"><div><h1>Institutions</h1><p>Trusted issuers. Each one's ECDSA public key is anchored on-chain via a REGISTER_ISSUER transaction.</p></div></header>

      <div className="grid-2">
        <form className="card form" onSubmit={submit}>
          <h2><Plus size={18} /> Register an institution</h2>
          <label>Name<input value={form.name} onChange={set('name')} required /></label>
          <label>Email<input type="email" value={form.email} onChange={set('email')} /></label>
          <label>Website<input value={form.website} onChange={set('website')} /></label>
          <button className="btn primary">Generate keys & register</button>
        </form>

        {created ? (
          <div className="card highlight">
            <h2><KeyRound size={18} /> Save this API key</h2>
            <p>It's shown <strong>only once</strong>. {created.institution.name} uses it to sign in to the Issuer Portal.</p>
            <code className="secret">{created.apiKey}</code>
            <button className="btn primary" onClick={() => { session.apiKey = created.apiKey; toast('Key saved for this session — open Issuer Portal', 'success') }}>
              Use it now
            </button>
          </div>
        ) : <div className="card placeholder">A new key pair is generated for each institution.</div>}
      </div>

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
