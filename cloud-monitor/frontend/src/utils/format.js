export const pct = (v) => `${(v ?? 0).toFixed(1)}%`

export const rate = (kbps = 0) => {
  if (kbps >= 1024 * 1024) return `${(kbps / 1024 / 1024).toFixed(2)} GB/s`
  if (kbps >= 1024) return `${(kbps / 1024).toFixed(1)} MB/s`
  return `${kbps.toFixed(0)} KB/s`
}

export const money = (v = 0, digits = 2) =>
  `$${v.toLocaleString('en-US', { minimumFractionDigits: digits, maximumFractionDigits: digits })}`

export const timeOnly = (ms) =>
  new Date(ms).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' })

export const timeShort = (ms) =>
  new Date(ms).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })

export const ago = (iso) => {
  const s = Math.max(0, Math.round((Date.now() - new Date(iso).getTime()) / 1000))
  if (s < 60) return `${s}s ago`
  if (s < 3600) return `${Math.floor(s / 60)}m ago`
  if (s < 86400) return `${Math.floor(s / 3600)}h ago`
  return `${Math.floor(s / 86400)}d ago`
}

export const uptime = (iso) => {
  if (!iso) return '-'
  let s = Math.max(0, Math.floor((Date.now() - new Date(iso).getTime()) / 1000))
  const d = Math.floor(s / 86400); s -= d * 86400
  const h = Math.floor(s / 3600); s -= h * 3600
  const m = Math.floor(s / 60); s -= m * 60
  return d ? `${d}d ${h}h ${m}m` : h ? `${h}h ${m}m ${s}s` : `${m}m ${s}s`
}

export const TYPE_LABEL = {
  VM: 'Virtual machine', DATABASE: 'Database', CONTAINER: 'Container',
  STORAGE: 'Storage', FUNCTION: 'Function', LOCAL_HOST: 'This PC',
}

export const PROVIDER_LABEL = { AWS: 'AWS', AZURE: 'Azure', GCP: 'Google Cloud', LOCAL: 'Local' }

/** Colour for a 0-100 utilisation value, using the status palette. */
export const levelColor = (v) => (v >= 90 ? 'var(--crit)' : v >= 75 ? 'var(--warn)' : 'var(--good)')
