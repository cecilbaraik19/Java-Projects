import { useEffect, useMemo, useState } from 'react'
import { useNavigate, useParams, Link } from 'react-router-dom'
import { ArrowLeft, Share2, Eye, EyeOff, ShieldCheck, ShieldAlert, Trash2, Code2, Sparkles } from 'lucide-react'
import { api } from '../api.js'
import { CredIcon, Modal, Mono, Spinner, Status } from '../components/ui.jsx'
import ShareLink from '../components/ShareLink.jsx'
import { useToast } from '../components/Toast.jsx'
import { credTheme, fmtDate, fmtValue } from '../util.js'

const EXPIRY = [[15, '15 minutes'], [60, '1 hour'], [1440, '1 day'], [10080, '7 days'], [43200, '30 days']]
const PRESETS = [
  ['Age check only', ['age_over_18']],
  ['Name + age', ['fullName', 'age_over_18']],
  ['Everything', null]
]

function ShareDialog({ cred, onClose }) {
  const toast = useToast()
  const [picked, setPicked] = useState(() => new Set(cred.claims.filter(c => c.name === 'fullName').map(c => c.name)))
  const [f, setF] = useState({ recipient: '', purpose: '', expiresInMinutes: 1440, maxViews: 3 })
  const [result, setResult] = useState(null)
  const [busy, setBusy] = useState(false)
  const toggle = (n) => setPicked(p => { const s = new Set(p); s.has(n) ? s.delete(n) : s.add(n); return s })
  const names = cred.claims.map(c => c.name)

  async function submit(e) {
    e.preventDefault(); setBusy(true)
    try {
      setResult(await api('/wallet/shares', { method: 'POST', body: {
        credentialId: cred.id, claims: [...picked], recipient: f.recipient, purpose: f.purpose,
        expiresInMinutes: Number(f.expiresInMinutes), maxViews: f.maxViews === '' ? null : Number(f.maxViews)
      } }))
      toast('Share link created')
    } catch (ex) { toast(ex.message, 'bad') } finally { setBusy(false) }
  }

  if (result) return (
    <Modal title="Ready to share" onClose={onClose}>
      <p>Show this QR code to <b>{result.recipient}</b>, or send them the link. They'll see only:</p>
      <div className="tags">{result.claims.map(c => <span key={c.name} className="tag">{c.label}</span>)}</div>
      <ShareLink link={result.link} />
    </Modal>
  )

  return (
    <Modal title={`Share from ${cred.title}`} onClose={onClose} wide>
      <form onSubmit={submit} className="share-form">
        <div>
          <h4>1. What should they see?</h4>
          <div className="presets">
            {PRESETS.map(([l, set]) => {
              const s = set ? set.filter(n => names.includes(n)) : names
              return s.length ? <button type="button" key={l} className="chip" onClick={() => setPicked(new Set(s))}>{l}</button> : null
            })}
          </div>
          <div className="claim-pick">
            {cred.claims.map(c => (
              <label key={c.name} className={`pick ${picked.has(c.name) ? 'on' : ''}`}>
                <input type="checkbox" checked={picked.has(c.name)} onChange={() => toggle(c.name)} />
                <span><b>{c.label}</b><small>{fmtValue(c.value)}</small></span>
                {c.derived && <span className="tag ok" title="Proves a fact without revealing your birth date"><Sparkles size={12} />Privacy-safe</span>}
              </label>
            ))}
          </div>
          <p className="hint">{cred.claims.length - picked.size} detail(s) stay hidden. The verifier can't see or guess them.</p>
        </div>
        <div>
          <h4>2. Who and for how long?</h4>
          <label>Shared with<input required maxLength={120} placeholder="e.g. HDFC Bank KYC desk" value={f.recipient} onChange={e => setF({ ...f, recipient: e.target.value })} /></label>
          <label>Purpose (optional)<input maxLength={300} placeholder="e.g. Opening a savings account" value={f.purpose} onChange={e => setF({ ...f, purpose: e.target.value })} /></label>
          <div className="row2">
            <label>Link expires after
              <select value={f.expiresInMinutes} onChange={e => setF({ ...f, expiresInMinutes: e.target.value })}>
                {EXPIRY.map(([v, l]) => <option key={v} value={v}>{l}</option>)}
              </select>
            </label>
            <label>View limit
              <select value={f.maxViews} onChange={e => setF({ ...f, maxViews: e.target.value })}>
                {[1, 3, 5, 10].map(v => <option key={v} value={v}>{v} view{v > 1 ? 's' : ''}</option>)}
                <option value="">Unlimited</option>
              </select>
            </label>
          </div>
          <div className="consent">
            <ShieldCheck size={18} />
            <span>Your wallet signs this share with your private key, binding it to <b>{f.recipient || 'this recipient'}</b>. You can stop sharing at any time.</span>
          </div>
          <button className="btn primary full" disabled={busy || picked.size === 0}><Share2 size={16} />Create share link</button>
        </div>
      </form>
    </Modal>
  )
}

export default function CredentialPage() {
  const { id } = useParams()
  const nav = useNavigate()
  const toast = useToast()
  const [c, setC] = useState(null)
  const [reveal, setReveal] = useState(false)
  const [sharing, setSharing] = useState(false)
  const [tech, setTech] = useState(false)

  useEffect(() => { api(`/wallet/credentials/${id}`).then(setC).catch(e => { toast(e.message, 'bad'); nav('/wallet') }) }, [id, nav, toast])
  const payload = useMemo(() => c ? JSON.stringify(JSON.parse(c.payload), null, 2) : '', [c])
  if (!c) return <Spinner />

  async function remove() {
    if (!window.confirm('Remove this credential from your wallet? Any active share links for it stop working.')) return
    try { await api(`/wallet/credentials/${id}`, { method: 'DELETE' }); toast('Credential removed'); nav('/wallet') }
    catch (e) { toast(e.message, 'bad') }
  }

  const mask = (v) => reveal ? fmtValue(v) : (typeof v === 'boolean' ? fmtValue(v) : '•'.repeat(Math.min(12, String(v).length)))

  return (
    <div className="page">
      <Link to="/wallet" className="link back"><ArrowLeft size={16} />All credentials</Link>
      <div className="cred-hero" style={{ background: credTheme(c.type).grad }}>
        <CredIcon type={c.type} size={30} />
        <div>
          <h1>{c.title}</h1>
          <p>Issued by <b>{c.issuer.name}</b> on {fmtDate(c.issuedAt)} · {c.expiresAt ? `valid until ${fmtDate(c.expiresAt)}` : 'no expiry'}</p>
        </div>
        <Status value={c.status} />
      </div>

      {c.status === 'REVOKED' && <div className="alert bad">Revoked by the issuer: {c.revocationReason}</div>}

      <div className="grid2 wide-left">
        <section className="card">
          <div className="card-head">
            <h2>Details</h2>
            <button className="btn sm ghost" onClick={() => setReveal(!reveal)}>{reveal ? <EyeOff size={15} /> : <Eye size={15} />}{reveal ? 'Hide' : 'Reveal'}</button>
          </div>
          <dl className="claims">
            {c.claims.map(cl => (
              <div key={cl.name}>
                <dt>{cl.label}{cl.derived && <span className="tag ok sm"><Sparkles size={11} />derived</span>}</dt>
                <dd>{mask(cl.value)}</dd>
              </div>
            ))}
          </dl>
          <div className="actions">
            <button className="btn primary" disabled={c.status !== 'ACTIVE'} onClick={() => setSharing(true)}><Share2 size={16} />Share selected details</button>
            <button className="btn danger ghost" onClick={remove}><Trash2 size={16} />Remove</button>
          </div>
        </section>

        <section className="card">
          <h2>Authenticity</h2>
          <div className={`verdict ${c.signatureValid ? 'ok' : 'bad'}`}>
            {c.signatureValid ? <ShieldCheck size={22} /> : <ShieldAlert size={22} />}
            <span>{c.signatureValid ? 'Issuer signature is valid' : 'Signature check failed'}</span>
          </div>
          <dl className="kv">
            <dt>Issuer DID</dt><dd><Mono>{c.issuer.did}</Mono></dd>
            <dt>Issuer key</dt><dd><Mono>{c.issuer.keyFingerprint}</Mono></dd>
            <dt>Algorithm</dt><dd>ECDSA P-256 (ES256) + SHA-256 salted digests</dd>
            <dt>Credential ID</dt><dd><Mono>{c.id}</Mono></dd>
            <dt>Shared</dt><dd>{c.shareCount} time(s) · <Link to="/shares" className="link">manage</Link></dd>
          </dl>
          <button className="btn sm ghost" onClick={() => setTech(!tech)}><Code2 size={15} />{tech ? 'Hide' : 'Show'} signed data</button>
          {tech && (
            <>
              <p className="hint">This is exactly what the issuer signed. Notice it holds only digests (<code>_sd</code>), never your actual details.</p>
              <pre className="code">{payload}</pre>
              <small className="muted">Signature</small>
              <pre className="code">{c.signature}</pre>
            </>
          )}
        </section>
      </div>
      {sharing && <ShareDialog cred={c} onClose={() => setSharing(false)} />}
    </div>
  )
}
