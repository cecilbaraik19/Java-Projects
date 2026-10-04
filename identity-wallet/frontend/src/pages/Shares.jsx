import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { Share2, QrCode, Ban, ChevronDown, ChevronUp, Eye } from 'lucide-react'
import { api } from '../api.js'
import { Empty, Modal, Spinner, Status } from '../components/ui.jsx'
import ShareLink from '../components/ShareLink.jsx'
import { useToast } from '../components/Toast.jsx'
import { fmtDateTime, timeAgo } from '../util.js'

export default function Shares() {
  const toast = useToast()
  const [list, setList] = useState(null)
  const [qr, setQr] = useState(null)
  const [open, setOpen] = useState({})
  const [tab, setTab] = useState('ACTIVE')
  const load = () => api('/wallet/shares').then(setList).catch(e => toast(e.message, 'bad'))
  useEffect(() => { load() }, []) // eslint-disable-line react-hooks/exhaustive-deps

  async function revoke(s) {
    if (!window.confirm(`Stop sharing with ${s.recipient}? The link stops working immediately.`)) return
    try { await api(`/wallet/shares/${s.id}/revoke`, { method: 'POST' }); toast('Sharing stopped'); load() }
    catch (e) { toast(e.message, 'bad') }
  }

  if (!list) return <Spinner />
  const shown = list.filter(s => tab === 'ALL' || (tab === 'ACTIVE' ? s.status === 'ACTIVE' : s.status !== 'ACTIVE'))
  return (
    <div className="page">
      <div className="page-head">
        <div><h1>Sharing & consent</h1><p className="muted">Everyone you've shared details with, what they can see, and every time they looked.</p></div>
      </div>
      <div className="seg">
        {[['ACTIVE', 'Active'], ['ENDED', 'Ended'], ['ALL', 'All']].map(([k, l]) => (
          <button key={k} className={tab === k ? 'on' : ''} onClick={() => setTab(k)}>{l}</button>
        ))}
      </div>
      {shown.length === 0 ? (
        <Empty icon={Share2} title="Nothing here">Open a <Link to="/wallet" className="link">credential</Link> and choose "Share selected details".</Empty>
      ) : (
        <div className="share-list">
          {shown.map(s => (
            <article key={s.id} className="card share">
              <div className="share-main">
                <div>
                  <h3>{s.recipient}</h3>
                  <p className="muted">{s.credentialTitle}{s.purpose ? ` · ${s.purpose}` : ''}</p>
                  <div className="tags">{s.claims.map(c => <span key={c.name} className="tag">{c.label}</span>)}</div>
                </div>
                <div className="share-meta">
                  <Status value={s.status} />
                  <span><Eye size={14} /> {s.views}{s.maxViews ? ` / ${s.maxViews}` : ''} views</span>
                  <small>{s.status === 'ACTIVE' ? `expires ${timeAgo(s.expiresAt)}` : `created ${timeAgo(s.createdAt)}`}</small>
                </div>
              </div>
              <div className="actions">
                {s.status === 'ACTIVE' && <button className="btn sm" onClick={() => setQr(s)}><QrCode size={15} />Show QR</button>}
                {s.status === 'ACTIVE' && <button className="btn sm danger ghost" onClick={() => revoke(s)}><Ban size={15} />Stop sharing</button>}
                <button className="btn sm ghost" onClick={() => setOpen({ ...open, [s.id]: !open[s.id] })}>
                  {open[s.id] ? <ChevronUp size={15} /> : <ChevronDown size={15} />}Access log ({s.logs.length})
                </button>
              </div>
              {open[s.id] && (s.logs.length === 0 ? <p className="muted">Not opened yet.</p> : (
                <table className="table">
                  <thead><tr><th>When</th><th>Outcome</th><th>IP</th><th>Device</th></tr></thead>
                  <tbody>{s.logs.map((l, i) => (
                    <tr key={i}><td>{fmtDateTime(l.at)}</td><td><Status value={l.outcome} /></td><td>{l.ip}</td><td className="ua">{l.userAgent}</td></tr>
                  ))}</tbody>
                </table>
              ))}
            </article>
          ))}
        </div>
      )}
      {qr && <Modal title={`Share with ${qr.recipient}`} onClose={() => setQr(null)}><ShareLink link={qr.link} /></Modal>}
    </div>
  )
}
