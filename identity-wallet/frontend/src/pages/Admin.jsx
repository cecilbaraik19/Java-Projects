import { useEffect, useState } from 'react'
import { Building2, Users, ScrollText, ShieldCheck, ShieldAlert, Plus, RefreshCw } from 'lucide-react'
import { api } from '../api.js'
import { Modal, Mono, Spinner, Status } from '../components/ui.jsx'
import { useToast } from '../components/Toast.jsx'
import { fmtDate, fmtDateTime, humanize } from '../util.js'

const CATS = ['GOVERNMENT', 'EDUCATION', 'EMPLOYER', 'TRANSPORT', 'HEALTHCARE', 'FINANCE', 'OTHER']

export default function Admin() {
  const toast = useToast()
  const [tab, setTab] = useState('issuers')
  const [stats, setStats] = useState(null)
  const [issuers, setIssuers] = useState([])
  const [users, setUsers] = useState([])
  const [audit, setAudit] = useState([])
  const [chain, setChain] = useState(null)
  const [adding, setAdding] = useState(false)
  const [f, setF] = useState({ name: '', category: 'GOVERNMENT', website: '', staffName: '', staffEmail: '', staffPassword: '' })

  const load = () => Promise.all([
    api('/admin/stats').then(s => { setStats(s); setChain(s.auditChain) }),
    api('/admin/issuers').then(setIssuers), api('/admin/users').then(setUsers), api('/admin/audit').then(setAudit)
  ]).catch(e => toast(e.message, 'bad'))
  useEffect(() => { load() }, []) // eslint-disable-line react-hooks/exhaustive-deps

  async function create(e) {
    e.preventDefault()
    try { await api('/admin/issuers', { method: 'POST', body: f }); toast(`${f.name} registered with a new signing key`); setAdding(false)
      setF({ name: '', category: 'GOVERNMENT', website: '', staffName: '', staffEmail: '', staffPassword: '' }); load() }
    catch (ex) { toast(ex.message, 'bad') }
  }
  const trust = async (i) => { try { await api(`/admin/issuers/${i.id}/trusted`, { method: 'POST', body: { value: !i.trusted } }); load() } catch (e) { toast(e.message, 'bad') } }
  const active = async (u) => { try { await api(`/admin/users/${u.id}/active`, { method: 'POST', body: { value: !u.active } }); load() } catch (e) { toast(e.message, 'bad') } }
  const verify = async () => { try { setChain(await api('/admin/audit/verify')); toast('Audit chain re-checked') } catch (e) { toast(e.message, 'bad') } }
  const set = (k) => (e) => setF({ ...f, [k]: e.target.value })

  if (!stats) return <Spinner />
  return (
    <div className="page">
      <div className="page-head"><div><h1>Administration</h1><p className="muted">Trust registry, accounts and the platform audit trail.</p></div></div>
      <div className="stats">
        {[['Wallet holders', stats.users], ['Issuers', stats.issuers], ['Credentials', stats.credentials], ['Revoked', stats.revoked], ['Shares', stats.shares]].map(([l, v]) => (
          <div key={l} className="stat"><div className="stat-v">{v}</div><div className="stat-l">{l}</div></div>
        ))}
        <div className={`stat ${chain?.valid ? 'ok' : 'bad'}`}>
          {chain?.valid ? <ShieldCheck size={20} /> : <ShieldAlert size={20} />}
          <div className="stat-l">Audit chain {chain?.valid ? 'intact' : `broken at #${chain?.brokenAt}`}</div>
          <small>{chain?.events} events</small>
        </div>
      </div>

      <div className="seg">
        <button className={tab === 'issuers' ? 'on' : ''} onClick={() => setTab('issuers')}><Building2 size={15} />Trust registry</button>
        <button className={tab === 'users' ? 'on' : ''} onClick={() => setTab('users')}><Users size={15} />Users</button>
        <button className={tab === 'audit' ? 'on' : ''} onClick={() => setTab('audit')}><ScrollText size={15} />Audit log</button>
      </div>

      {tab === 'issuers' && (
        <section className="card">
          <div className="card-head"><h2>Registered issuers</h2><button className="btn primary sm" onClick={() => setAdding(true)}><Plus size={15} />Register issuer</button></div>
          <div className="table-wrap"><table className="table">
            <thead><tr><th>Organisation</th><th>Category</th><th>Key</th><th>Issued</th><th>Staff</th><th>Status</th><th /></tr></thead>
            <tbody>{issuers.map(i => (
              <tr key={i.id}>
                <td><b>{i.name}</b><br /><Mono>{i.did}</Mono></td><td>{humanize(i.category)}</td><td><Mono>{i.keyFingerprint}</Mono></td>
                <td>{i.issued}</td><td>{i.staff.map(s => <div key={s.id}><small>{s.email}</small></div>)}</td>
                <td><Status value={i.trusted ? 'ACTIVE' : 'REVOKED'} /></td>
                <td><button className={`btn sm ${i.trusted ? 'danger ghost' : ''}`} onClick={() => trust(i)}>{i.trusted ? 'Suspend' : 'Restore'}</button></td>
              </tr>
            ))}</tbody>
          </table></div>
          <p className="hint">Suspending an issuer makes every credential it signed fail verification until restored.</p>
        </section>
      )}

      {tab === 'users' && (
        <section className="card"><div className="table-wrap"><table className="table">
          <thead><tr><th>Name</th><th>Email</th><th>Role</th><th>2FA</th><th>Joined</th><th>Last sign-in</th><th /></tr></thead>
          <tbody>{users.map(u => (
            <tr key={u.id} className={u.active ? '' : 'dim-row'}>
              <td>{u.fullName}</td><td>{u.email}</td><td>{humanize(u.role)}</td><td>{u.mfaEnabled ? 'On' : 'Off'}</td>
              <td>{fmtDate(u.createdAt)}</td><td>{fmtDateTime(u.lastLoginAt)}</td>
              <td>{u.role !== 'ADMIN' && <button className={`btn sm ${u.active ? 'danger ghost' : ''}`} onClick={() => active(u)}>{u.active ? 'Disable' : 'Enable'}</button>}</td>
            </tr>
          ))}</tbody>
        </table></div></section>
      )}

      {tab === 'audit' && (
        <section className="card">
          <div className="card-head"><h2>Platform audit log (latest 200)</h2><button className="btn sm" onClick={verify}><RefreshCw size={15} />Verify hash chain</button></div>
          <div className="table-wrap"><table className="table">
            <thead><tr><th>#</th><th>When</th><th>Actor</th><th>Event</th><th>Detail</th><th>IP</th><th>Hash</th></tr></thead>
            <tbody>{audit.map(a => (
              <tr key={a.id} className={a.action.includes('FAILED') ? 'bad-row' : ''}>
                <td>{a.id}</td><td>{fmtDateTime(a.at)}</td><td>{a.actor}</td><td>{humanize(a.action)}</td><td>{a.detail}</td><td>{a.ip}</td><td><Mono>{a.hash.slice(0, 10)}…</Mono></td>
              </tr>
            ))}</tbody>
          </table></div>
        </section>
      )}

      {adding && (
        <Modal title="Register an issuer" onClose={() => setAdding(false)}>
          <form onSubmit={create}>
            <label>Organisation name<input required value={f.name} onChange={set('name')} /></label>
            <div className="row2">
              <label>Category<select value={f.category} onChange={set('category')}>{CATS.map(c => <option key={c} value={c}>{humanize(c)}</option>)}</select></label>
              <label>Website<input value={f.website} onChange={set('website')} placeholder="https://" /></label>
            </div>
            <h4>First staff login</h4>
            <label>Name<input required value={f.staffName} onChange={set('staffName')} /></label>
            <div className="row2">
              <label>Email<input type="email" required value={f.staffEmail} onChange={set('staffEmail')} /></label>
              <label>Password<input type="password" required value={f.staffPassword} onChange={set('staffPassword')} autoComplete="new-password" /></label>
            </div>
            <p className="hint">A new ECDSA P-256 key pair is generated for the organisation; the private key is encrypted with the master key.</p>
            <button className="btn primary full">Register</button>
          </form>
        </Modal>
      )}
    </div>
  )
}
