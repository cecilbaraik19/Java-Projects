import { useEffect, useRef, useState } from 'react'
import { AnimatePresence, motion } from 'framer-motion'
import { Bell, Check, DollarSign, Download, FileText, Loader2, Server } from 'lucide-react'
import { downloadFile, errorMessage } from '../api/client'

const ITEMS = [
  { url: '/reports/summary.pdf', file: 'cloudpulse-report.pdf', label: 'Full report', sub: 'PDF · summary, costs, resources, alerts', Icon: FileText },
  { url: '/reports/resources.csv', file: 'cloudpulse-resources.csv', label: 'Resources', sub: 'CSV · open in Excel', Icon: Server },
  { url: '/reports/alerts.csv', file: 'cloudpulse-alerts.csv', label: 'Alerts', sub: 'CSV · last 500 alerts', Icon: Bell },
  { url: '/reports/costs.csv', file: 'cloudpulse-costs.csv', label: 'Costs', sub: 'CSV · cost per resource', Icon: DollarSign },
]

/** "Export" button in the top bar with a dropdown of downloadable reports. */
export default function ExportMenu() {
  const [open, setOpen] = useState(false)
  const [busy, setBusy] = useState(null)
  const [done, setDone] = useState(null)
  const [error, setError] = useState('')
  const ref = useRef(null)

  useEffect(() => {
    if (!open) return
    const onDown = (e) => { if (ref.current && !ref.current.contains(e.target)) setOpen(false) }
    const onKey = (e) => e.key === 'Escape' && setOpen(false)
    document.addEventListener('mousedown', onDown)
    document.addEventListener('keydown', onKey)
    return () => { document.removeEventListener('mousedown', onDown); document.removeEventListener('keydown', onKey) }
  }, [open])

  const download = async (item) => {
    setBusy(item.url)
    setError('')
    try {
      await downloadFile(item.url, item.file)
      setDone(item.url)
      setTimeout(() => setDone(null), 2000)
    } catch (e) {
      setError(errorMessage(e, 'Download failed'))
    } finally {
      setBusy(null)
    }
  }

  return (
    <div className="relative" ref={ref}>
      <button onClick={() => setOpen((o) => !o)} aria-haspopup="menu" aria-expanded={open}
        className="flex items-center gap-1.5 rounded-lg border border-line bg-surface px-2.5 py-1.5 text-sm font-medium text-ink-2 hover:bg-surface-2">
        <Download size={15} /> <span className="hidden sm:inline">Export</span>
      </button>
      <AnimatePresence>
        {open && (
          <motion.div role="menu"
            initial={{ opacity: 0, y: -6, scale: 0.98 }} animate={{ opacity: 1, y: 0, scale: 1 }} exit={{ opacity: 0, y: -6, scale: 0.98 }}
            transition={{ duration: 0.15 }}
            className="absolute right-0 z-50 mt-2 w-72 rounded-2xl border border-line bg-surface p-1.5 shadow-2xl">
            {ITEMS.map((item) => {
              const { Icon } = item
              return (
                <button key={item.url} role="menuitem" onClick={() => download(item)} disabled={!!busy}
                  className="flex w-full items-center gap-3 rounded-xl p-2.5 text-left transition hover:bg-surface-2 disabled:opacity-60">
                  <span className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-surface-2 text-accent"><Icon size={16} /></span>
                  <span className="min-w-0 flex-1">
                    <span className="block text-sm font-medium text-ink">{item.label}</span>
                    <span className="block text-[11px] text-muted">{item.sub}</span>
                  </span>
                  {busy === item.url && <Loader2 size={15} className="animate-spin text-muted" />}
                  {done === item.url && <Check size={15} style={{ color: 'var(--good)' }} />}
                </button>
              )
            })}
            {error && <p className="px-2.5 pt-1 pb-2 text-xs" style={{ color: 'var(--crit)' }}>{error}</p>}
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  )
}
