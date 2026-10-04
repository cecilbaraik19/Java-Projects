import { useEffect, useMemo, useState } from 'react'
import { Stamp, Plus, X, Ban, Search, Building2 } from 'lucide-react'
import { api } from '../api.js'
import { CredIcon, Modal, Mono, Spinner, Status } from '../components/ui.jsx'
import { useToast } from '../components/Toast.jsx'
import { fmtDate, humanize } from '../util.js'

export default function IssuerPortal() {
  const toast = useToast()
  const [me, setMe] = useState(null)
  const [templates, setTemplates] = useState([])
  const [issued, setIssued] = useState(null)
  const [type, setType] = useState('NATIONAL_ID')
  const [holderEmail, setHolderEmail] = useState('')
  const [values, setValues] = useState({})
  const [custom, setCustom] = useState([])
  const [validity, setValidity] = useState('')
  const [busy, setBusy] = useState(false)
  const [revoking, setRevoking] = useState(null)
  const [reason, setReason] = useState('')
  const [q, setQ] = useState('')

  const load = () => api('/issuer/credentials').then(setIssued).catch(e => toast(e.message, 'bad'))
  useEffect(() => {
    api('/issuer/me').then(setMe).catch(e => toast(e.message, 'bad'))
    api('/issuer/templates').then(setTemplates)
    load()
  }, []) // eslint-disable-line react-hooks/exhaustive-deps

  const tpl = useMemo(() => templates.find(t => t.type === type), [templates, type])

  async function issue(e) {
    e.preventDefault(); setBusy(true)
    const claims = { ...values }
    custom.forEach(c => { if (c.name) claims[c.name] = c.value })
    try {
      const r = await api('/issuer/credentials', { method: 'POST', body: {
        holderEmail, type, claims, validityDays: validity === '' ? null : Number(validity) } })
      toast(`Issued ${r.title} to ${r.holderEmail}`)
      setValues({}); setCustom([]); setHolderEmail('')
      load()
    } catch (ex) { toast(ex.message, 'bad') } finally { setBusy(false) }
  }

  async function revoke(e) {
    e.preventDefault()
    try { await api(`/issuer/credentials/${revoking.id}/revoke`, { method: 'POST', body: { reason } }); toast('Credential revoked'); setRevoking(null); setReason(''); load() }
    catch (ex) { toast(ex.message, 'bad') }
  }

  if (!me || !issued) return <Spinner />
  const shown = issued.filter(c => (c.holderEmail + c.holderName + c.title + c.id).toLowerCase().includes(q.toLowerCase()))
  return (
    <div className="page">
      <div className="page-head">
        <div><h1>Issuer portal</h1><p className="muted">Issue signed credentials straight into people's wallets.</p></div>
      </div>
      <div className="did-card">
        <div>
          <small><Building2 size={13} /> {humanize(me.category)} issuer {me.trusted ? '' : '(suspended)'}</small>
          <b className="org">{me.name}</b>
          <small>Signing key <Mono>{me.keyFingerprint}</Mono> · {me.did}</small>
        </div>
        <Status value={me.trusted ? 'ACTIVE' : 'REVOKED'} />
      </div>

      <div className="grid2 wide-left">
        <section className="card">
          <h2><Stamp size={18} /> Issue a credential</h2>
          <form onSubmit={issue}>
            <div className="type-pick">
              {templates.map(t => (
                <button type="button" key={t.type} className={type === t.type ? 'on' : ''} onClick={() => { setType(t.type); setValues({}) }}>
                  <CredIcon type={t.type} size={18} /><span>{t.label}</span>
                </button>
              ))}
            </div>
            {tpl && <p className="hint">{tpl.description}. Default validity {tpl.validityDays} days.</p>}
            <label>Holder's wallet email<input type="email" required value={holderEmail} onChange={e => setHolderEmail(e.target.value)} placeholder="person@example.com" /></label>
            <div className="row2 wrap">
              {tpl?.fields.map(f => (
                <label key={f.name}>{f.label}{f.required && ' *'}
                  <input type={f.input} required={f.required} value={values[f.name] || ''} onChange={e => setValues({ ...values, [f.name]: e.target.value })} />
                </label>
              ))}
            </div>
            {custom.map((c, i) => (
              <div className="custom-row" key={i}>
                <input placeholder="claimName" value={c.name} onChange={e => setCustom(custom.map((x, j) => j === i ? { ...x, name: e.target.value.replace(/[^A-Za-z0-9_]/g, '') } : x))} />
                <input placeholder="value" value={c.value} onChange={e => setCustom(custom.map((x, j) => j === i ? { ...x, value: e.target.value } : x))} />
                <button type="button" className="icon-btn" onClick={() => setCustom(custom.filter((_, j) => j !== i))} aria-label="Remove"><X size={16} /></button>
              </div>
            ))}
            <div className="row2">
              <button type="button" className="btn sm ghost" onClick={() => setCustom([...custom, { name: '', value: '' }])}><Plus size={15} />Extra claim</button>
              <label className="compact">Validity (days, 0 = never)<input type="number" min="0" max="36500" placeholder={tpl?.validityDays} value={validity} onChange={e => setValidity(e.target.value)} /></label>
            </div>
            {values.dateOfBirth && <p className="hint">"Age over 18" and "Age over 21" will be added automatically from the date of birth.</p>}
            <button className="btn primary full" disabled={busy || !me.trusted}>{busy ? 'Signing…' : 'Sign & issue'}</button>
          </form>
        </section>

        <section className="card">
          <h2>What happens</h2>
          <ol className="steps">
            <li>Each detail gets a random salt and is hashed (SHA-256).</li>
            <li>Only the hashes go into the credential, which is signed with your organisation's private key (ECDSA P-256).</li>
            <li>The details are encrypted with the holder's own key and delivered to their wallet.</li>
            <li>The holder decides which details to reveal to whom. You can revoke at any time.</li>
          </ol>
          <div className="stats mini">
            <div className="stat"><div className="stat-v">{issued.length}</div><div className="stat-l">Issued</div></div>
            <div className="stat"><div className="stat-v">{issued.filter(c => c.status === 'REVOKED').length}</div><div className="stat-l">Revoked</div></div>
          </div>
        </section>
      </div>

      <section className="card">
        <div className="card-head">
          <h2>Issued credentials</h2>
          <div className="search"><Search size={16} /><input placeholder="Search" value={q} onChange={e => setQ(e.target.value)} /></div>
        </div>
        <div className="table-wrap">
          <table className="table">
            <thead><tr><th>Credential</th><th>Holder</th><th>Issued</th><th>Expires</th><th>Status</th><th /></tr></thead>
            <tbody>{shown.map(c => (
              <tr key={c.id}>
                <td><b>{c.title}</b><br /><Mono>{c.id}</Mono></td>
                <td>{c.holderName}<br /><small className="muted">{c.holderEmail}</small></td>
                <td>{fmtDate(c.issuedAt)}</td><td>{fmtDate(c.expiresAt)}</td>
                <td><Status value={c.status} />{c.revocationReason && <small className="muted block">{c.revocationReason}</small>}</td>
                <td>{c.status !== 'REVOKED' && <button className="btn sm danger ghost" onClick={() => setRevoking(c)}><Ban size={14} />Revoke</button>}</td>
              </tr>
            ))}</tbody>
          </table>
        </div>
      </section>

      {revoking && (
        <Modal title="Revoke credential" onClose={() => setRevoking(null)}>
          <form onSubmit={revoke}>
            <p>Revoke <b>{revoking.title}</b> held by {revoking.holderName}? Every verifier will see it as revoked from now on.</p>
            <label>Reason<input required maxLength={300} value={reason} onChange={e => setReason(e.target.value)} placeholder="e.g. Issued in error" /></label>
            <button className="btn danger full">Revoke</button>
          </form>
        </Modal>
      )}
    </div>
  )
}
