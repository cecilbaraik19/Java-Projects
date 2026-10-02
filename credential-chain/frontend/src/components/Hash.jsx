import { useState } from 'react'
import { Copy, Check } from 'lucide-react'
import { shortHash } from '../api.js'

/** Monospace hash with click-to-copy. */
export default function Hash({ value, n = 10, full = false }) {
  const [copied, setCopied] = useState(false)
  if (!value) return <span className="mono muted">—</span>
  const copy = async (e) => {
    e.stopPropagation()
    try {
      await navigator.clipboard.writeText(value)
      setCopied(true)
      setTimeout(() => setCopied(false), 1200)
    } catch { /* clipboard blocked */ }
  }
  return (
    <span className="hash mono" title={value} onClick={copy}>
      {full ? value : shortHash(value, n)}
      {copied ? <Check size={12} /> : <Copy size={12} />}
    </span>
  )
}
