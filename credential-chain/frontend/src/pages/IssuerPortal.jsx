import { useCallback, useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { KeyRound, LogOut, Send, Ban } from 'lucide-react'
import { api, session } from '../api.js'
import { useToast } from '../components/Toast.jsx'
import StatusBadge from '../components/StatusBadge.jsx'
import CredentialCertificate from '../components/CredentialCertificate.jsx'

const TYPES = ["Bachelor's Degree", "Master's Degree", 'Diploma', 'Certificate', 'Transcript', 'Doctorate']
// Today's date in the user's own timezone, as yyyy-mm-dd (used to block future dates)
const today = () => new Date().toLocaleDateString('en-CA')

const EMPTY = { credentialType: TYPES[0], studentName: '', studentId: '', program: '', major: '', grade: '', issueDate: '' }

export default function IssuerPortal() {
  const [issuer, setIssuer] = useState(null)
  const [keyInput, setKeyInput] = useState('')
  const [form, setForm] = useState(EMPTY)
  const [issued, setIssued] = useState(null)
  const [mine, setMine] = useState([])
  const [busy, setBusy] = useState(false)
  const toast = useToast()
  const navigate = useNavigate()

  const loadMine = useCallback(async () => {
    try { setMine(await api.myCredentials()) } catch { /* ignore */ }
  }, [])

  useEffect(() => {
    if (session.apiKey) {
      api.login(session.apiKey).then((i) => { setIssuer(i); loadMine() }).catch(() => { session.apiKey = null })
    }
  }, [loadMine])

  const login = async (e) => {
    e.preventDefault()
    try {
      const inst = await api.login(keyInput.trim())
      session.apiKey = keyInput.trim()
      setIssuer(inst)
      toast(`Signed in as ${inst.name}`, 'success')
      loadMine()
    } catch (err) { toast(err.message, 'error') }
  }

  const logout = () => { session.apiKey = null; setIssuer(null); setMine([]); setIssued(null) }

  const submit = async (e) => {
    e.preventDefault()
    setBusy(true)
    try {
      const view = await api.issue(form)
      setIssued(view)
      setForm({ ...EMPTY, credentialType: form.credentialType, program: form.program })
      toast('Credential signed and submitted to the blockchain', 'success')
      loadMine()
    } catch (err) { toast(err.message, 'error') } finally { setBusy(false) }
  }

  const revoke = async (id) => {
    const reason = window.prompt('Reason for revocation?')
    if (reason === null) return
    try {
      await api.revoke(id, reason)
      toast('Revocation submitted to the blockchain', 'success')
      loadMine()
    } catch (err) { toast(err.message, 'error') }
  }

  const set = (k) => (e) => setForm({ ...form, [k]: e.target.value })

  if (!issuer) {
    return (
      <div className="page narrow">
        <header className="page-head"><div><h1>Issuer Portal</h1><p>Institutions sign in with their API key to issue credentials.</p></div></header>
        <form className="card form" onSubmit={login}>
          <label>API key
            <input value={keyInput} onChange={(e) => setKeyInput(e.target.value)} placeholder="e.g. demo-issuer-key" required />
          </label>
          <button className="btn primary"><KeyRound size={16} /> Sign in</button>
          <p className="muted small">Demo key: <code>demo-issuer-key</code>. New institutions get a key on the Institutions page.</p>
        </form>
      </div>
    )
  }

  return (
    <div className="page">
      <header className="page-head">
        <div><h1>Issuer Portal</h1><p>Signed in as <strong>{issuer.name}</strong> <span className="muted">({issuer.id})</span></p></div>
        <button className="btn ghost" onClick={logout}><LogOut size={16} /> Sign out</button>
      </header>

      <div className="grid-2">
        <form className="card form" onSubmit={submit}>
          <h2>Issue a credential</h2>
          <label>Credential type
            <select value={form.credentialType} onChange={set('credentialType')}>
              {TYPES.map((t) => <option key={t}>{t}</option>)}
            </select>
          </label>
          <div className="row">
            <label>Student name<input value={form.studentName} onChange={set('studentName')} required /></label>
            <label>Student / roll ID<input value={form.studentId} onChange={set('studentId')} required /></label>
          </div>
          <label>Program<input value={form.program} onChange={set('program')} placeholder="B.Sc. Information Technology" required /></label>
          <div className="row">
            <label>Major / specialisation<input value={form.major} onChange={set('major')} /></label>
            <label>Grade / CGPA<input value={form.grade} onChange={set('grade')} placeholder="8.9 CGPA" required /></label>
          </div>
          <label>Issue date<input type="date" value={form.issueDate} max={today()} onChange={set('issueDate')} /></label>
          <button className="btn primary" disabled={busy}><Send size={16} /> {busy ? 'Signing…' : 'Sign & issue'}</button>
          <p className="muted small">The record is hashed (SHA-256), signed with your ECDSA private key, and only the hash goes on-chain.</p>
        </form>

        <div>
          {issued
            ? (
              <div className="stack">
                <CredentialCertificate view={issued} />
                <button className="btn" onClick={() => navigate(`/credential/${issued.credential.credentialId}`)}>Open credential page</button>
              </div>
            )
            : <div className="card placeholder">Issued credentials will preview here.</div>}
        </div>
      </div>

      <section className="card">
        <div className="card-head"><h2>Credentials issued by {issuer.name}</h2></div>
        {mine.length === 0 ? <p className="muted">None yet.</p> : (
          <table className="table">
            <thead><tr><th>ID</th><th>Student</th><th>Credential</th><th>Block</th><th>Status</th><th /></tr></thead>
            <tbody>
              {mine.map((v) => (
                <tr key={v.credential.credentialId}>
                  <td className="mono small clickable" onClick={() => navigate(`/credential/${v.credential.credentialId}`)}>{v.credential.credentialId}</td>
                  <td>{v.credential.studentName}<div className="muted small">{v.credential.studentId}</div></td>
                  <td>{v.credential.credentialType}<div className="muted small">{v.credential.program}</div></td>
                  <td>{v.blockIndex != null ? `#${v.blockIndex}` : '—'}</td>
                  <td><StatusBadge status={v.status} /></td>
                  <td>{v.status !== 'REVOKED' && (
                    <button className="btn small danger" onClick={() => revoke(v.credential.credentialId)}><Ban size={14} /> Revoke</button>
                  )}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
    </div>
  )
}
