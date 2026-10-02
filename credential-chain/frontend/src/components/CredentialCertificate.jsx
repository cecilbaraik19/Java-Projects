import { QRCodeSVG } from 'qrcode.react'
import { Award } from 'lucide-react'
import StatusBadge from './StatusBadge.jsx'
import Hash from './Hash.jsx'

/** Certificate-style rendering of a credential, with a QR code that opens the verify page. */
export default function CredentialCertificate({ view }) {
  const c = view.credential
  const verifyUrl = `${window.location.origin}/verify/${c.credentialId}`
  return (
    <div className={`certificate ${view.status === 'REVOKED' ? 'revoked' : ''}`}>
      {view.status === 'REVOKED' && <div className="stamp">REVOKED</div>}
      <div className="cert-head">
        <Award size={36} />
        <div>
          <div className="cert-issuer">{c.issuerName}</div>
          <div className="cert-type">{c.credentialType}</div>
        </div>
        <StatusBadge status={view.status} />
      </div>
      <p className="cert-line">This is to certify that</p>
      <h2 className="cert-name">{c.studentName}</h2>
      <p className="cert-line">
        ({c.studentId}) has successfully completed <strong>{c.program}</strong>
        {c.major ? <> with specialisation in <strong>{c.major}</strong></> : null}
      </p>
      <div className="cert-grid">
        <div><label>Grade</label><span>{c.grade}</span></div>
        <div><label>Issued on</label><span>{c.issueDate}</span></div>
        <div><label>Credential ID</label><span className="mono">{c.credentialId}</span></div>
        <div><label>Block</label><span>{view.blockIndex != null ? `#${view.blockIndex}` : 'pending'}</span></div>
      </div>
      <div className="cert-foot">
        <div>
          <label>SHA-256 fingerprint</label>
          <Hash value={view.credentialHash} n={16} />
          {view.revocationReason && <p className="danger-text">Revoked: {view.revocationReason}</p>}
        </div>
        <div className="qr">
          <QRCodeSVG value={verifyUrl} size={96} bgColor="transparent" fgColor="currentColor" />
          <span>Scan to verify</span>
        </div>
      </div>
    </div>
  )
}
