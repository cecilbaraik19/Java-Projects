import { useCallback, useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { LogIn, Send, Ban, FileText } from 'lucide-react'
import { api } from '../api.js'
import { useAuth } from '../auth.jsx'
import { useToast } from '../components/Toast.jsx'
import StatusBadge from '../components/StatusBadge.jsx'
import CredentialCertificate from '../components/CredentialCertificate.jsx'
import { downloadCertificatePdf } from '../certificatePdf.js'

const TYPES = ["Bachelor's Degree", "Master's Degree", 'Diploma', 'Certificate', 'Transcript', 'Doctorate']
// Today's date in the user's own timezone, as yyyy-mm-dd (used to block future dates)
const today = () => new Date().toLocaleDateString('en-CA')

const EMPTY = { credentialType: TYPES[0], studentName: '', studentId: '', program: '', major: '', grade: '', issueDate: '' }

export default function IssuerPortal() {
  const { user } = useAuth()
  const isIssuer = user?.role === 'ISSUER'
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
    if (isIssuer) loadMine()
  }, [isIssuer, loadMine])

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

  if (!isIssuer) {
    return (
      <div className="page narrow">
        <header className="page-head"><div><h1>Issuer Portal</h1><p>College staff sign in to issue and revoke credentials.</p></div></header>
        <div className="card form">
          <p>{user?.role === 'ADMIN'
            ? 'You are signed in as the administrator. Only college staff can issue credentials — sign out and sign in with a college account.'
            : 'Please sign in with your college staff account.'}</p>
          {!user && <Link className="btn primary" to="/login" state={{ from: '/issue' }}><LogIn size={16} /> Sign in</Link>}
          <p className="muted small">Demo: <code>registrar@demo-institute.edu</code> / <code>demo123</code></p>
        </div>
      </div>
    )
  }

  return (
    <div className="page">
      <header className="page-head">
        <div><h1>Issuer Portal</h1><p>Signed in as <strong>{user.name}</strong> · {user.institutionName} <span className="muted">({user.institutionId})</span></p></div>
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
                <div className="actions">
                  <button className="btn" onClick={() => navigate(`/credential/${issued.credential.credentialId}`)}>Open credential page</button>
                  <button className="btn" onClick={() => downloadCertificatePdf(issued).catch((e) => toast(e.message, 'error'))}><FileText size={16} /> Download PDF</button>
                </div>
              </div>
            )
            : <div className="card placeholder">Issued credentials will preview here.</div>}
        </div>
      </div>

      <section className="card">
        <div className="card-head"><h2>Credentials issued by {user.institutionName}</h2></div>
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
