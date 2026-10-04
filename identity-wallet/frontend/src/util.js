export const fmtDate = (d) => d ? new Date(d).toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: 'numeric' }) : '—'
export const fmtDateTime = (d) => d ? new Date(d).toLocaleString(undefined, { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' }) : '—'

export function timeAgo(d) {
  if (!d) return ''
  const s = Math.round((Date.now() - new Date(d).getTime()) / 1000)
  const f = (n, u) => `${n} ${u}${n === 1 ? '' : 's'}`
  if (s < 0) {
    const a = -s
    if (a < 3600) return `in ${f(Math.round(a / 60), 'min')}`
    if (a < 86400) return `in ${f(Math.round(a / 3600), 'hour')}`
    return `in ${f(Math.round(a / 86400), 'day')}`
  }
  if (s < 60) return 'just now'
  if (s < 3600) return `${f(Math.round(s / 60), 'min')} ago`
  if (s < 86400) return `${f(Math.round(s / 3600), 'hour')} ago`
  return `${f(Math.round(s / 86400), 'day')} ago`
}

export const fmtBytes = (n) => n < 1024 ? `${n} B` : n < 1048576 ? `${(n / 1024).toFixed(1)} KB` : `${(n / 1048576).toFixed(1)} MB`

export const humanize = (s) => (s || '').toLowerCase().replace(/_/g, ' ').replace(/^\w/, c => c.toUpperCase())

/** Visual theme per credential type. */
export const CRED_THEMES = {
  NATIONAL_ID: { grad: 'linear-gradient(135deg,#4338ca,#6d28d9)', icon: 'IdCard' },
  DRIVING_LICENSE: { grad: 'linear-gradient(135deg,#0e7490,#0f766e)', icon: 'Car' },
  STUDENT_ID: { grad: 'linear-gradient(135deg,#b45309,#c2410c)', icon: 'GraduationCap' },
  EMPLOYMENT: { grad: 'linear-gradient(135deg,#1d4ed8,#0369a1)', icon: 'Briefcase' },
  HEALTH_INSURANCE: { grad: 'linear-gradient(135deg,#be123c,#9d174d)', icon: 'HeartPulse' },
  ADDRESS_PROOF: { grad: 'linear-gradient(135deg,#4d7c0f,#15803d)', icon: 'Home' }
}
export const credTheme = (t) => CRED_THEMES[t] || { grad: 'linear-gradient(135deg,#334155,#475569)', icon: 'BadgeCheck' }

export const fmtValue = (v) => v === true ? 'Yes' : v === false ? 'No' : String(v)

export async function copy(text) {
  try { await navigator.clipboard.writeText(text); return true } catch { return false }
}
