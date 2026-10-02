import { useEffect, useRef, useState } from 'react'
import { AnimatePresence, motion } from 'framer-motion'
import { CircleCheck, OctagonAlert, Square, TriangleAlert, X } from 'lucide-react'

/** Surface card with a soft hover lift. */
export function Card({ className = '', children, hover = false, ...rest }) {
  return (
    <motion.div
      whileHover={hover ? { y: -3 } : undefined}
      transition={{ type: 'spring', stiffness: 400, damping: 30 }}
      className={`rounded-2xl border border-line bg-surface p-5 shadow-sm ${className}`}
      {...rest}
    >
      {children}
    </motion.div>
  )
}

export function SectionTitle({ title, subtitle, right }) {
  return (
    <div className="mb-4 flex flex-wrap items-start justify-between gap-3">
      <div>
        <h2 className="text-[15px] font-semibold text-ink">{title}</h2>
        {subtitle && <p className="mt-0.5 text-xs text-muted">{subtitle}</p>}
      </div>
      {right}
    </div>
  )
}

/** Smoothly counts from the previous value to the new one. */
export function AnimatedNumber({ value = 0, format = (v) => v.toFixed(0), duration = 600 }) {
  const [display, setDisplay] = useState(value)
  const from = useRef(value)
  useEffect(() => {
    const start = performance.now()
    const a = from.current
    let raf
    const step = (now) => {
      const t = Math.min(1, (now - start) / duration)
      const eased = 1 - Math.pow(1 - t, 3)
      setDisplay(a + (value - a) * eased)
      if (t < 1) raf = requestAnimationFrame(step)
      else from.current = value
    }
    raf = requestAnimationFrame(step)
    return () => { cancelAnimationFrame(raf); from.current = value }
  }, [value, duration])
  return <>{format(display)}</>
}

const STATUS = {
  RUNNING: { label: 'Running', color: 'var(--good)', Icon: CircleCheck },
  WARNING: { label: 'Warning', color: 'var(--warn)', Icon: TriangleAlert },
  CRITICAL: { label: 'Critical', color: 'var(--crit)', Icon: OctagonAlert },
  STOPPED: { label: 'Stopped', color: 'var(--muted)', Icon: Square },
}

/** Status is always icon + label + colour, never colour alone. */
export function StatusBadge({ status, size = 'sm' }) {
  const s = STATUS[status] || STATUS.STOPPED
  const { Icon } = s
  return (
    <span
      className={`inline-flex items-center gap-1.5 rounded-full border border-line bg-surface-2 font-medium text-ink-2 ${size === 'lg' ? 'px-3 py-1 text-sm' : 'px-2 py-0.5 text-xs'}`}
    >
      <Icon size={size === 'lg' ? 15 : 13} style={{ color: s.color }} strokeWidth={2.4} />
      {s.label}
    </span>
  )
}

const SEVERITY = {
  INFO: { label: 'Info', color: 'var(--accent)' },
  WARNING: { label: 'Warning', color: 'var(--warn)' },
  CRITICAL: { label: 'Critical', color: 'var(--crit)' },
}

export function SeverityBadge({ severity }) {
  const s = SEVERITY[severity] || SEVERITY.INFO
  const Icon = severity === 'CRITICAL' ? OctagonAlert : severity === 'WARNING' ? TriangleAlert : CircleCheck
  return (
    <span className="inline-flex items-center gap-1 rounded-md border border-line bg-surface-2 px-2 py-0.5 text-xs font-medium text-ink-2">
      <Icon size={13} style={{ color: s.color }} strokeWidth={2.4} /> {s.label}
    </span>
  )
}

/** Thin utilisation bar that changes colour at 75% and 90%. */
export function UsageBar({ value = 0, label }) {
  const color = value >= 90 ? 'var(--crit)' : value >= 75 ? 'var(--warn)' : 'var(--accent)'
  return (
    <div className="w-full">
      {label && (
        <div className="mb-1 flex justify-between text-xs">
          <span className="text-muted">{label}</span>
          <span className="tnum font-medium text-ink-2">{value.toFixed(1)}%</span>
        </div>
      )}
      <div className="h-1.5 w-full overflow-hidden rounded-full bg-surface-2">
        <motion.div
          className="h-full rounded-full"
          style={{ background: color }}
          initial={false}
          animate={{ width: `${Math.min(100, value)}%` }}
          transition={{ type: 'spring', stiffness: 120, damping: 20 }}
        />
      </div>
    </div>
  )
}

/** Segmented control with a sliding highlight. */
export function Tabs({ options, value, onChange, id }) {
  return (
    <div className="inline-flex rounded-xl border border-line bg-surface-2 p-1">
      {options.map((o) => {
        const v = typeof o === 'string' ? o : o.value
        const label = typeof o === 'string' ? o : o.label
        const active = v === value
        return (
          <button
            key={v}
            onClick={() => onChange(v)}
            className={`relative rounded-lg px-3 py-1 text-xs font-medium transition-colors ${active ? 'text-ink' : 'text-muted hover:text-ink-2'}`}
          >
            {active && (
              <motion.span
                layoutId={`tab-${id}`}
                className="absolute inset-0 rounded-lg bg-surface shadow-sm"
                transition={{ type: 'spring', stiffness: 500, damping: 35 }}
              />
            )}
            <span className="relative">{label}</span>
          </button>
        )
      })}
    </div>
  )
}

export function Button({ variant = 'secondary', className = '', children, ...rest }) {
  const styles = {
    primary: 'bg-accent text-white hover:brightness-110',
    secondary: 'border border-line bg-surface text-ink hover:bg-surface-2',
    danger: 'bg-[var(--crit)] text-white hover:brightness-110',
    ghost: 'text-ink-2 hover:bg-surface-2',
  }
  return (
    <motion.button
      whileTap={{ scale: 0.96 }}
      className={`inline-flex items-center justify-center gap-1.5 rounded-xl px-3.5 py-2 text-sm font-medium transition disabled:cursor-not-allowed disabled:opacity-50 ${styles[variant]} ${className}`}
      {...rest}
    >
      {children}
    </motion.button>
  )
}

export function Modal({ open, onClose, title, children, width = 'max-w-md' }) {
  useEffect(() => {
    if (!open) return
    const onKey = (e) => e.key === 'Escape' && onClose()
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [open, onClose])
  return (
    <AnimatePresence>
      {open && (
        <motion.div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4 backdrop-blur-sm"
          initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }}
          onMouseDown={onClose}
        >
          <motion.div
            role="dialog" aria-modal="true"
            className={`w-full ${width} rounded-2xl border border-line bg-surface p-6 shadow-2xl`}
            initial={{ scale: 0.94, y: 12, opacity: 0 }}
            animate={{ scale: 1, y: 0, opacity: 1 }}
            exit={{ scale: 0.96, y: 8, opacity: 0 }}
            transition={{ type: 'spring', stiffness: 380, damping: 30 }}
            onMouseDown={(e) => e.stopPropagation()}
          >
            <div className="mb-4 flex items-center justify-between">
              <h3 className="text-base font-semibold">{title}</h3>
              <button onClick={onClose} className="rounded-lg p-1 text-muted hover:bg-surface-2 hover:text-ink" aria-label="Close">
                <X size={18} />
              </button>
            </div>
            {children}
          </motion.div>
        </motion.div>
      )}
    </AnimatePresence>
  )
}

export function Skeleton({ className = '' }) {
  return <div className={`animate-pulse rounded-lg bg-surface-2 ${className}`} />
}

export function EmptyState({ icon: Icon, title, text }) {
  return (
    <div className="flex flex-col items-center justify-center py-12 text-center">
      {Icon && <Icon size={28} className="mb-3 text-muted" />}
      <p className="text-sm font-medium text-ink-2">{title}</p>
      {text && <p className="mt-1 text-xs text-muted">{text}</p>}
    </div>
  )
}
