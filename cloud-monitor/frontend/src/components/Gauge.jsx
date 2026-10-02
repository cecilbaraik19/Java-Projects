import { motion } from 'framer-motion'
import { AnimatedNumber } from './ui'

/** 270° radial gauge. Arc colour shifts to warning/critical at 75/90%. */
export default function Gauge({ value = 0, label, size = 150, sub }) {
  const stroke = 12
  const r = (size - stroke) / 2
  const circ = 2 * Math.PI * r
  const arc = circ * 0.75
  const v = Math.max(0, Math.min(100, value))
  const color = v >= 90 ? 'var(--crit)' : v >= 75 ? 'var(--warn)' : 'var(--accent)'
  return (
    <div className="flex flex-col items-center">
      <div className="relative" style={{ width: size, height: size }}>
        <svg width={size} height={size} className="rotate-[135deg]">
          <circle cx={size / 2} cy={size / 2} r={r} fill="none" stroke="var(--surface-2)" strokeWidth={stroke}
            strokeDasharray={`${arc} ${circ}`} strokeLinecap="round" />
          <motion.circle cx={size / 2} cy={size / 2} r={r} fill="none" stroke={color} strokeWidth={stroke}
            strokeLinecap="round" strokeDasharray={`${arc} ${circ}`}
            initial={{ strokeDashoffset: arc }}
            animate={{ strokeDashoffset: arc - (arc * v) / 100, stroke: color }}
            transition={{ type: 'spring', stiffness: 60, damping: 15 }} />
        </svg>
        <div className="absolute inset-0 flex flex-col items-center justify-center">
          <span className="tnum text-3xl font-semibold text-ink">
            <AnimatedNumber value={v} format={(x) => x.toFixed(1)} />
            <span className="text-base text-muted">%</span>
          </span>
          {sub && <span className="mt-0.5 text-[11px] text-muted">{sub}</span>}
        </div>
      </div>
      <span className="-mt-3 text-sm font-medium text-ink-2">{label}</span>
    </div>
  )
}
