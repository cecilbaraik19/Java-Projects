import { useEffect } from 'react'
import { X, Loader2, IdCard, Car, GraduationCap, Briefcase, HeartPulse, Home, BadgeCheck } from 'lucide-react'
import { credTheme, humanize } from '../util.js'

export function Modal({ title, onClose, children, wide }) {
  useEffect(() => {
    const k = (e) => e.key === 'Escape' && onClose()
    window.addEventListener('keydown', k)
    return () => window.removeEventListener('keydown', k)
  }, [onClose])
  return (
    <div className="modal-back" onMouseDown={e => e.target === e.currentTarget && onClose()}>
      <div className={`modal ${wide ? 'wide' : ''}`} role="dialog" aria-modal="true" aria-label={title}>
        <div className="modal-head">
          <h3>{title}</h3>
          <button className="icon-btn" onClick={onClose} aria-label="Close"><X size={18} /></button>
        </div>
        <div className="modal-body">{children}</div>
      </div>
    </div>
  )
}

export const Spinner = ({ label = 'Loading…' }) => (
  <div className="spinner"><Loader2 className="spin" size={20} /> {label}</div>
)

export function Status({ value }) {
  const map = { ACTIVE: 'ok', VERIFIED: 'ok', REVOKED: 'bad', EXPIRED: 'warn', USED_UP: 'warn', FAILED_CHECKS: 'bad' }
  return <span className={`pill ${map[value] || 'muted'}`}>{humanize(value)}</span>
}

const ICONS = { IdCard, Car, GraduationCap, Briefcase, HeartPulse, Home, BadgeCheck }

export function CredIcon({ type, size = 22 }) {
  const C = ICONS[credTheme(type).icon] || BadgeCheck
  return <C size={size} />
}

export function Empty({ icon: I, title, children }) {
  return (
    <div className="empty">
      {I && <I size={36} />}
      <h3>{title}</h3>
      {children && <p>{children}</p>}
    </div>
  )
}

export function Mono({ children, className = '' }) {
  return <code className={`mono ${className}`}>{children}</code>
}
