import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { Wallet, Share2, Lock, Eye, CheckCircle2, XCircle, CalendarClock, Activity, Copy } from 'lucide-react'
import { api } from '../api.js'
import { Spinner, Mono } from '../components/ui.jsx'
import { useToast } from '../components/Toast.jsx'
import { copy, fmtDate, humanize, timeAgo } from '../util.js'

function ScoreRing({ score }) {
  const r = 52, c = 2 * Math.PI * r
  const color = score >= 80 ? 'var(--ok)' : score >= 50 ? 'var(--warn)' : 'var(--bad)'
  return (
    <svg viewBox="0 0 130 130" className="ring" role="img" aria-label={`Security score ${score} out of 100`}>
      <circle cx="65" cy="65" r={r} className="ring-track" />
      <circle cx="65" cy="65" r={r} stroke={color} className="ring-val"
              strokeDasharray={c} strokeDashoffset={c - (c * score) / 100} />
      <text x="65" y="62" textAnchor="middle" className="ring-num">{score}</text>
      <text x="65" y="82" textAnchor="middle" className="ring-sub">/ 100</text>
    </svg>
  )
}

export default function Dashboard() {
  const [d, setD] = useState(null)
  const toast = useToast()
  useEffect(() => { api('/wallet/dashboard').then(setD).catch(e => toast(e.message, 'bad')) }, [toast])
  if (!d) return <Spinner />

  const stats = [
    ['Credentials', d.activeCredentials, `${d.credentials} total`, Wallet, '/wallet'],
    ['Active shares', d.activeShares, 'links people can open', Share2, '/shares'],
    ['Times viewed', d.totalViews, 'by verifiers', Eye, '/shares'],
    ['Vault documents', d.vaultItems, 'encrypted', Lock, '/vault']
  ]
  return (
    <div className="page">
      <div className="page-head">
        <div>
          <h1>Hello, {d.user.fullName.split(' ')[0]}</h1>
          <p className="muted">Here's the state of your digital identity.</p>
        </div>
      </div>

      <div className="did-card">
        <div>
          <small>Your decentralized identifier (DID)</small>
          <Mono className="big">{d.user.did}</Mono>
          <small>Key fingerprint {d.user.keyFingerprint} · ECDSA P-256</small>
        </div>
        <button className="btn ghost light" onClick={async () => toast(await copy(d.user.did) ? 'DID copied' : 'Copy failed')}><Copy size={16} />Copy</button>
      </div>

      <div className="stats">
        {stats.map(([l, v, s, I, to]) => (
          <Link to={to} key={l} className="stat">
            <I size={20} />
            <div className="stat-v">{v}</div>
            <div className="stat-l">{l}</div>
            <small>{s}</small>
          </Link>
        ))}
      </div>

      <div className="grid2">
        <section className="card">
          <h2>Security score</h2>
          <div className="score">
            <ScoreRing score={d.securityScore} />
            <ul className="checklist">
              {d.checklist.map(c => (
                <li key={c.label} className={c.ok ? 'ok' : 'bad'}>
                  {c.ok ? <CheckCircle2 size={18} /> : <XCircle size={18} />}
                  <span>{c.label}</span><small>+{c.points}</small>
                </li>
              ))}
            </ul>
          </div>
          {!d.user.mfaEnabled && <Link to="/security" className="btn primary sm">Turn on two-factor sign-in</Link>}
        </section>

        <section className="card">
          <h2><CalendarClock size={18} /> Expiring within 60 days</h2>
          {d.expiring.length === 0 ? <p className="muted">Nothing is expiring soon.</p> : (
            <ul className="list">
              {d.expiring.map(x => (
                <li key={x.kind + x.id}>
                  <Link to={x.kind === 'credential' ? `/wallet/${x.id}` : '/vault'}>{x.title}</Link>
                  <span className={new Date(x.date) < new Date() ? 'pill bad' : 'pill warn'}>{fmtDate(x.date)}</span>
                </li>
              ))}
            </ul>
          )}
        </section>
      </div>

      <section className="card">
        <h2><Activity size={18} /> Recent activity</h2>
        <ul className="timeline">
          {d.activity.map(a => (
            <li key={a.id}>
              <span className={`dot ${a.action.includes('FAILED') ? 'bad' : ''}`} />
              <div><b>{humanize(a.action)}</b><p>{a.detail}</p></div>
              <small title={a.at}>{timeAgo(a.at)}</small>
            </li>
          ))}
        </ul>
        <Link to="/security" className="link">Full activity log →</Link>
      </section>
    </div>
  )
}
