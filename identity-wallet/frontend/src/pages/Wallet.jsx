import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { Wallet as WalletIcon, Search } from 'lucide-react'
import { api } from '../api.js'
import { CredIcon, Empty, Spinner, Status } from '../components/ui.jsx'
import { useToast } from '../components/Toast.jsx'
import { credTheme, fmtDate } from '../util.js'

export function CredentialCard({ c }) {
  return (
    <Link to={`/wallet/${c.id}`} className={`cred-card ${c.status !== 'ACTIVE' ? 'dim' : ''}`} style={{ background: credTheme(c.type).grad }}>
      <div className="cred-top">
        <CredIcon type={c.type} />
        {c.status !== 'ACTIVE' && <Status value={c.status} />}
      </div>
      <div className="cred-title">{c.title}</div>
      <div className="cred-issuer">{c.issuer?.name}</div>
      <div className="cred-foot">
        <span>{c.claimNames.length} details</span>
        <span>{c.expiresAt ? `Valid to ${fmtDate(c.expiresAt)}` : 'No expiry'}</span>
      </div>
      <div className="chip-glint" />
    </Link>
  )
}

export default function WalletPage() {
  const [list, setList] = useState(null)
  const [q, setQ] = useState('')
  const [filter, setFilter] = useState('ALL')
  const toast = useToast()
  useEffect(() => { api('/wallet/credentials').then(setList).catch(e => toast(e.message, 'bad')) }, [toast])
  if (!list) return <Spinner />

  const shown = list.filter(c => (filter === 'ALL' || c.status === filter) &&
    (c.title + ' ' + (c.issuer?.name || '')).toLowerCase().includes(q.toLowerCase()))
  return (
    <div className="page">
      <div className="page-head">
        <div><h1>My credentials</h1><p className="muted">Issued and signed by trusted organisations. Tap one to view or share it.</p></div>
      </div>
      <div className="toolbar">
        <div className="search"><Search size={16} /><input placeholder="Search credentials" value={q} onChange={e => setQ(e.target.value)} /></div>
        <div className="seg">
          {['ALL', 'ACTIVE', 'EXPIRED', 'REVOKED'].map(s => (
            <button key={s} className={filter === s ? 'on' : ''} onClick={() => setFilter(s)}>{s[0] + s.slice(1).toLowerCase()}</button>
          ))}
        </div>
      </div>
      {list.length === 0 ? (
        <Empty icon={WalletIcon} title="Your wallet is empty">
          Ask an issuer (government office, college, employer) to issue a credential to <b>your account email</b>.
        </Empty>
      ) : shown.length === 0 ? <p className="muted">No credentials match.</p> : (
        <div className="cred-grid">{shown.map(c => <CredentialCard key={c.id} c={c} />)}</div>
      )}
    </div>
  )
}
