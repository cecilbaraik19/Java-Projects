import { useCallback, useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { Blocks, FileCheck2, Ban, Building2, Hourglass, Pickaxe, ShieldCheck, ShieldX, ArrowRight } from 'lucide-react'
import { api, fmtTime } from '../api.js'
import Hash from '../components/Hash.jsx'
import StatusBadge from '../components/StatusBadge.jsx'
import { useToast } from '../components/Toast.jsx'

export default function Dashboard() {
  const [stats, setStats] = useState(null)
  const [chain, setChain] = useState([])
  const [pending, setPending] = useState([])
  const [recent, setRecent] = useState([])
  const [mining, setMining] = useState(false)
  const [error, setError] = useState(null)
  const toast = useToast()
  const navigate = useNavigate()

  const load = useCallback(async () => {
    try {
      const [s, c, p, r] = await Promise.all([api.stats(), api.chain(), api.pending(), api.recentCredentials()])
      setStats(s); setChain(c); setPending(p); setRecent(r); setError(null)
    } catch (e) { setError(e.message) }
  }, [])

  useEffect(() => {
    load()
    const t = setInterval(load, 5000)
    return () => clearInterval(t)
  }, [load])

  const mine = async () => {
    setMining(true)
    try {
      const res = await api.mine()
      toast(res.message, 'success')
      load()
    } catch (e) { toast(e.message, 'error') } finally { setMining(false) }
  }

  if (error) return <div className="card error-card"><h2>Backend unreachable</h2><p>{error}</p></div>
  if (!stats) return <div className="loading">Loading ledger…</div>

  const tiles = [
    { label: 'Blocks', value: stats.blocks, icon: Blocks },
    { label: 'Credentials on chain', value: stats.credentialsOnChain, icon: FileCheck2 },
    { label: 'Revoked', value: stats.revoked, icon: Ban },
    { label: 'Institutions', value: stats.institutions, icon: Building2 },
    { label: 'Pending tx', value: stats.pending, icon: Hourglass },
  ]

  return (
    <div className="page">
      <header className="page-head">
        <div>
          <h1>Dashboard</h1>
          <p>Tamper-proof academic credentials anchored on a proof-of-work blockchain.</p>
        </div>
        <div className={`health ${stats.chainValid ? 'ok' : 'bad'}`}>
          {stats.chainValid ? <ShieldCheck size={20} /> : <ShieldX size={20} />}
          {stats.chainValid ? 'Chain integrity verified' : 'Chain integrity BROKEN'}
        </div>
      </header>

      <section className="tiles">
        {tiles.map(({ label, value, icon: Icon }) => (
          <div className="tile" key={label}>
            <Icon size={20} />
            <div className="tile-value">{value}</div>
            <div className="tile-label">{label}</div>
          </div>
        ))}
      </section>

      <section className="card">
        <div className="card-head">
          <h2>Latest blocks</h2>
          <Link to="/explorer" className="link">Open explorer <ArrowRight size={14} /></Link>
        </div>
        <div className="chain-strip">
          {chain.slice(-6).map((b, i, arr) => (
            <div className="strip-item" key={b.index}>
              <button className="block-chip" onClick={() => navigate(`/explorer/${b.index}`)}>
                <span className="block-no">#{b.index}</span>
                <Hash value={b.hash} n={8} />
                <span className="muted small">{b.transactions.length} tx · nonce {b.nonce}</span>
              </button>
              {i < arr.length - 1 && <span className="chain-link" />}
            </div>
          ))}
        </div>
      </section>

      <div className="grid-2">
        <section className="card">
          <div className="card-head">
            <h2>Pending pool</h2>
            <button className="btn primary" onClick={mine} disabled={mining || pending.length === 0}>
              <Pickaxe size={16} /> {mining ? 'Mining…' : 'Mine block'}
            </button>
          </div>
          {pending.length === 0
            ? <p className="muted">No pending transactions. Issue or revoke a credential to create one.</p>
            : (
              <ul className="tx-list">
                {pending.map((t) => (
                  <li key={t.id}>
                    <span className={`tx-type ${t.type}`}>{t.type.replace('_', ' ')}</span>
                    <span className="mono small">{t.credentialId || t.issuerId}</span>
                    <span className="muted small">{fmtTime(t.timestamp)}</span>
                  </li>
                ))}
              </ul>
            )}
          <p className="muted small">Auto-mining runs every 30 s · difficulty {stats.difficulty}</p>
        </section>

        <section className="card">
          <div className="card-head"><h2>Recently issued</h2></div>
          {recent.length === 0 ? <p className="muted">No credentials yet.</p> : (
            <table className="table">
              <thead><tr><th>Student</th><th>Credential</th><th>Status</th></tr></thead>
              <tbody>
                {recent.slice(0, 6).map((v) => (
                  <tr key={v.credential.credentialId} onClick={() => navigate(`/credential/${v.credential.credentialId}`)} className="clickable">
                    <td>{v.credential.studentName}<div className="muted small">{v.credential.studentId}</div></td>
                    <td>{v.credential.credentialType}<div className="muted small">{v.credential.issuerName}</div></td>
                    <td><StatusBadge status={v.status} /></td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </section>
      </div>
    </div>
  )
}
