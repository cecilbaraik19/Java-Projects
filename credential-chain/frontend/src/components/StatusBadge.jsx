const LABELS = {
  VALID: 'Valid', ACTIVE: 'Active', PENDING: 'Pending', REVOKED: 'Revoked',
  INVALID: 'Invalid', NOT_FOUND: 'Not found', NOT_ON_CHAIN: 'Not on chain',
}
const TONE = {
  VALID: 'ok', ACTIVE: 'ok', PENDING: 'warn', REVOKED: 'bad', INVALID: 'bad', NOT_FOUND: 'muted', NOT_ON_CHAIN: 'bad',
}

export default function StatusBadge({ status }) {
  return <span className={`badge ${TONE[status] || 'muted'}`}>{LABELS[status] || status}</span>
}
