import { CheckCircle2, XCircle, ShieldCheck, ShieldAlert, ShieldX, Clock, HelpCircle } from 'lucide-react'
import { Link } from 'react-router-dom'
import Hash from './Hash.jsx'
import { fmtTime } from '../api.js'

const HEAD = {
  VALID: { icon: ShieldCheck, cls: 'ok', title: 'Credential verified' },
  PENDING: { icon: Clock, cls: 'warn', title: 'Authentic — awaiting block confirmation' },
  REVOKED: { icon: ShieldAlert, cls: 'bad', title: 'Credential revoked' },
  INVALID: { icon: ShieldX, cls: 'bad', title: 'Verification failed' },
  NOT_FOUND: { icon: HelpCircle, cls: 'muted', title: 'Credential not found' },
}

export default function VerificationReport({ result }) {
  const h = HEAD[result.status] || HEAD.INVALID
  const Icon = h.icon
  const c = result.credential
  return (
    <div className="report">
      <div className={`report-banner ${h.cls}`}>
        <Icon size={40} />
        <div>
          <h2>{h.title}</h2>
          <p>{result.message}</p>
        </div>
      </div>

      <div className="report-body">
        <div>
          <h3>Verification steps</h3>
          <ul className="checks">
            {result.checks.map((ch, i) => (
              <li key={ch.name} className={ch.passed ? 'pass' : 'fail'} style={{ animationDelay: `${i * 120}ms` }}>
                {ch.passed ? <CheckCircle2 size={20} /> : <XCircle size={20} />}
                <div><strong>{ch.name}</strong><span>{ch.detail}</span></div>
              </li>
            ))}
          </ul>
        </div>

        {c && (
          <div>
            <h3>Credential details</h3>
            <dl className="details">
              <dt>Student</dt><dd>{c.studentName} <span className="muted">({c.studentId})</span></dd>
              <dt>Credential</dt><dd>{c.credentialType}</dd>
              <dt>Program</dt><dd>{c.program}{c.major ? ` — ${c.major}` : ''}</dd>
              <dt>Grade</dt><dd>{c.grade}</dd>
              <dt>Issued</dt><dd>{c.issueDate}</dd>
              <dt>Issuer</dt><dd>{c.issuerName} <span className="muted">({c.issuerId})</span></dd>
              <dt>Hash</dt><dd><Hash value={result.credentialHash} n={14} /></dd>
              <dt>Transaction</dt><dd><Hash value={result.transactionId} /></dd>
              <dt>Block</dt>
              <dd>{result.blockIndex != null
                ? <Link to={`/explorer/${result.blockIndex}`}>#{result.blockIndex}</Link>
                : 'pending'} {result.anchoredAt && <span className="muted">· {fmtTime(result.anchoredAt)}</span>}</dd>
            </dl>
          </div>
        )}
      </div>
    </div>
  )
}
