import { AnimatePresence, motion } from 'framer-motion'
import { OctagonAlert, TriangleAlert, Info, X } from 'lucide-react'
import { useNavigate } from 'react-router-dom'
import { useLive } from '../context/LiveContext'

const STYLE = {
  CRITICAL: { Icon: OctagonAlert, color: 'var(--crit)', label: 'Critical' },
  WARNING: { Icon: TriangleAlert, color: 'var(--warn)', label: 'Warning' },
  INFO: { Icon: Info, color: 'var(--accent)', label: 'Info' },
}

/** Pop-up notifications for alerts as they arrive over the WebSocket. */
export default function Toasts() {
  const { toasts, dismissToast } = useLive()
  const navigate = useNavigate()
  return (
    <div className="pointer-events-none fixed right-4 bottom-4 z-50 flex w-[min(360px,calc(100vw-2rem))] flex-col gap-2" aria-live="polite">
      <AnimatePresence initial={false}>
        {toasts.map((t) => {
          const s = STYLE[t.severity] || STYLE.INFO
          return (
            <motion.div key={t.toastId} layout
              initial={{ opacity: 0, x: 60, scale: 0.95 }} animate={{ opacity: 1, x: 0, scale: 1 }}
              exit={{ opacity: 0, x: 60, scale: 0.95 }}
              transition={{ type: 'spring', stiffness: 400, damping: 30 }}
              className="pointer-events-auto relative cursor-pointer overflow-hidden rounded-xl border border-line bg-surface p-3 pr-9 shadow-xl"
              onClick={() => { if (t.resourceId) navigate(`/resources/${t.resourceId}`); dismissToast(t.toastId) }}
            >
              <span className="absolute inset-y-0 left-0 w-1" style={{ background: s.color }} />
              <div className="flex gap-2.5 pl-1.5">
                <s.Icon size={18} style={{ color: s.color }} className="mt-0.5 shrink-0" />
                <div className="min-w-0">
                  <p className="text-xs font-semibold text-ink">{s.label} · {t.ruleName}</p>
                  <p className="mt-0.5 text-xs text-ink-2">{t.message}</p>
                </div>
              </div>
              <button className="absolute top-2 right-2 rounded p-0.5 text-muted hover:text-ink" aria-label="Dismiss"
                onClick={(e) => { e.stopPropagation(); dismissToast(t.toastId) }}>
                <X size={14} />
              </button>
            </motion.div>
          )
        })}
      </AnimatePresence>
    </div>
  )
}
