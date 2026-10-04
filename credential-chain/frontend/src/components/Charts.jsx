import { useMemo, useState } from 'react'

/*
 * Dashboard charts drawn as plain SVG (no chart library).
 * Colors come from CSS tokens (--series-1..3) validated for colour-blind safety
 * in both light and dark mode. Every chart has a hover tooltip and visible
 * labels/legend, so colour is never the only way to read it.
 */

const SERIES = [
  { key: 'ISSUE', label: 'Issued', color: 'var(--series-1)' },
  { key: 'REVOKE', label: 'Revoked', color: 'var(--series-2)' },
  { key: 'REGISTER_ISSUER', label: 'Institution registered', color: 'var(--series-3)' },
]

function Tooltip({ tip }) {
  if (!tip) return null
  return (
    <div className="chart-tip" style={{ left: tip.x, top: tip.y }}>
      <strong>{tip.title}</strong>
      {tip.rows.map((r) => (
        <div key={r.label} className="chart-tip-row">
          {r.color && <span className="swatch" style={{ background: r.color }} />}
          <span>{r.label}</span><b>{r.value}</b>
        </div>
      ))}
    </div>
  )
}

function Legend({ items }) {
  return (
    <div className="chart-legend">
      {items.map((s) => (
        <span key={s.label}><span className="swatch" style={{ background: s.color }} />{s.label}{s.value != null && <b> {s.value}</b>}</span>
      ))}
    </div>
  )
}

/** Rounded-top bar path: 4px radius on the data end, square at the baseline. */
function barPath(x, y, w, h, r = 4) {
  if (h <= 0) return ''
  const rr = Math.min(r, w / 2, h)
  return `M${x},${y + h} V${y + rr} Q${x},${y} ${x + rr},${y} H${x + w - rr} Q${x + w},${y} ${x + w},${y + rr} V${y + h} Z`
}

function niceMax(v) {
  if (v <= 4) return 4
  const p = Math.pow(10, Math.floor(Math.log10(v)))
  for (const m of [1, 2, 2.5, 5, 10]) if (m * p >= v) return m * p
  return v
}

/** Stacked bars: transactions in each block, by type. */
export function TransactionsPerBlock({ chain }) {
  const [tip, setTip] = useState(null)
  const data = useMemo(() => chain.map((b) => {
    const counts = { ISSUE: 0, REVOKE: 0, REGISTER_ISSUER: 0 }
    b.transactions.forEach((t) => { counts[t.type] = (counts[t.type] || 0) + 1 })
    return { index: b.index, counts, total: b.transactions.length }
  }), [chain])
  const totals = useMemo(() => SERIES.map((s) => ({ ...s, value: data.reduce((a, d) => a + d.counts[s.key], 0) })), [data])

  const W = 560, H = 220, padL = 32, padB = 26, padT = 10, padR = 8
  const max = niceMax(Math.max(1, ...data.map((d) => d.total)))
  const plotW = W - padL - padR, plotH = H - padT - padB
  const slot = plotW / Math.max(data.length, 1)
  const barW = Math.max(6, Math.min(36, slot * 0.6))
  const y = (v) => padT + plotH - (v / max) * plotH
  const ticks = [0, max / 2, max]
  const labelEvery = Math.ceil(data.length / 12)

  return (
    <div className="chart">
      <Legend items={totals} />
      <div className="chart-box">
        <svg viewBox={`0 0 ${W} ${H}`} role="img" aria-label="Transactions per block, stacked by type" onMouseLeave={() => setTip(null)}>
          {ticks.map((t) => (
            <g key={t}>
              <line x1={padL} x2={W - padR} y1={y(t)} y2={y(t)} className="grid" />
              <text x={padL - 6} y={y(t) + 4} className="axis" textAnchor="end">{t}</text>
            </g>
          ))}
          {data.map((d, i) => {
            const cx = padL + slot * i + slot / 2
            let acc = 0
            const segs = SERIES.map((s) => {
              const v = d.counts[s.key]
              if (!v) return null
              const y0 = y(acc), y1 = y(acc + v)
              acc += v
              const top = acc === d.total
              const h = y0 - y1 - (top ? 0 : 2) // 2px surface gap between stacked segments
              return top
                ? <path key={s.key} d={barPath(cx - barW / 2, y1, barW, h)} fill={s.color} />
                : <rect key={s.key} x={cx - barW / 2} y={y1 + 2} width={barW} height={Math.max(0, h)} fill={s.color} />
            })
            return (
              <g key={d.index}>
                {segs}
                {i % labelEvery === 0 && <text x={cx} y={H - 8} className="axis" textAnchor="middle">#{d.index}</text>}
                {/* hit target bigger than the bar */}
                <rect x={cx - slot / 2} y={padT} width={slot} height={plotH} fill="transparent"
                  onMouseMove={(e) => {
                    const r = e.currentTarget.ownerSVGElement.getBoundingClientRect()
                    setTip({
                      x: e.clientX - r.left + 12, y: e.clientY - r.top - 10, title: `Block #${d.index}`,
                      rows: d.total === 0 ? [{ label: 'No transactions (genesis)', value: '' }]
                        : SERIES.filter((s) => d.counts[s.key]).map((s) => ({ label: s.label, value: d.counts[s.key], color: s.color })),
                    })
                  }} />
              </g>
            )
          })}
          <line x1={padL} x2={W - padR} y1={y(0)} y2={y(0)} className="baseline" />
        </svg>
        <Tooltip tip={tip} />
      </div>
    </div>
  )
}

/** One horizontal part-to-whole bar: active / revoked / pending credentials. */
export function StatusBreakdown({ stats }) {
  const [tip, setTip] = useState(null)
  const revoked = stats.revoked
  const active = Math.max(0, stats.credentialsOnChain - stats.revoked)
  const pending = Math.max(0, stats.credentials - stats.credentialsOnChain)
  const parts = [
    { label: 'Active', value: active, color: 'var(--series-1)' },
    { label: 'Revoked', value: revoked, color: 'var(--series-2)' },
    { label: 'Pending', value: pending, color: 'var(--series-3)' },
  ]
  const total = parts.reduce((a, p) => a + p.value, 0)
  if (total === 0) return <p className="muted">No credentials yet.</p>

  let x = 0
  return (
    <div className="chart">
      <Legend items={parts} />
      <div className="chart-box">
        <div className="status-bar" onMouseLeave={() => setTip(null)}>
          {parts.filter((p) => p.value > 0).map((p) => {
            const pct = (p.value / total) * 100
            const left = x
            x += pct
            return (
              <div key={p.label} className="status-seg" style={{ width: `${pct}%`, background: p.color }}
                onMouseMove={(e) => {
                  const r = e.currentTarget.parentElement.getBoundingClientRect()
                  setTip({ x: e.clientX - r.left + 12, y: e.clientY - r.top - 50, title: p.label,
                    rows: [{ label: 'Credentials', value: p.value }, { label: 'Share', value: `${Math.round(pct)}%` }] })
                }}
                aria-label={`${p.label}: ${p.value}`} data-left={left} />
            )
          })}
        </div>
        <Tooltip tip={tip} />
      </div>
      <p className="muted small">{total} credentials · {Math.round((active / total) * 100)}% currently valid</p>
    </div>
  )
}

/** Single-series bars: proof-of-work nonce (attempts) per block. */
export function MiningEffort({ chain }) {
  const [tip, setTip] = useState(null)
  const data = chain.map((b) => ({ index: b.index, nonce: b.nonce, ms: b.miningTimeMs }))
  const W = 560, H = 200, padL = 52, padB = 26, padT = 10, padR = 8
  const max = niceMax(Math.max(1, ...data.map((d) => d.nonce)))
  const plotW = W - padL - padR, plotH = H - padT - padB
  const slot = plotW / Math.max(data.length, 1)
  const barW = Math.max(6, Math.min(36, slot * 0.6))
  const y = (v) => padT + plotH - (v / max) * plotH
  const fmt = (v) => (v >= 1000 ? `${Math.round(v / 1000)}k` : v)
  const avg = data.length ? Math.round(data.reduce((a, d) => a + d.nonce, 0) / data.length) : 0
  const labelEvery = Math.ceil(data.length / 12)

  return (
    <div className="chart">
      <div className="chart-box">
        <svg viewBox={`0 0 ${W} ${H}`} role="img" aria-label="Proof-of-work attempts (nonce) per block" onMouseLeave={() => setTip(null)}>
          {[0, max / 2, max].map((t) => (
            <g key={t}>
              <line x1={padL} x2={W - padR} y1={y(t)} y2={y(t)} className="grid" />
              <text x={padL - 6} y={y(t) + 4} className="axis" textAnchor="end">{fmt(t)}</text>
            </g>
          ))}
          {data.map((d, i) => {
            const cx = padL + slot * i + slot / 2
            return (
              <g key={d.index}>
                <path d={barPath(cx - barW / 2, y(d.nonce), barW, y(0) - y(d.nonce))} fill="var(--series-1)" />
                {i % labelEvery === 0 && <text x={cx} y={H - 8} className="axis" textAnchor="middle">#{d.index}</text>}
                <rect x={cx - slot / 2} y={padT} width={slot} height={plotH} fill="transparent"
                  onMouseMove={(e) => {
                    const r = e.currentTarget.ownerSVGElement.getBoundingClientRect()
                    setTip({ x: e.clientX - r.left + 12, y: e.clientY - r.top - 10, title: `Block #${d.index}`,
                      rows: [{ label: 'Attempts (nonce)', value: d.nonce.toLocaleString() }, { label: 'Mining time', value: `${d.ms} ms` }] })
                  }} />
              </g>
            )
          })}
          <line x1={padL} x2={W - padR} y1={y(0)} y2={y(0)} className="baseline" />
        </svg>
        <Tooltip tip={tip} />
      </div>
      <p className="muted small">Average {avg.toLocaleString()} attempts per block to find a hash starting with the required zeros.</p>
    </div>
  )
}
