import { useState } from 'react'
import { Area, AreaChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { timeOnly, timeShort } from '../utils/format'

/**
 * Area chart for one or more metrics over time.
 * series: [{ key, label, color, format }]
 * Click a legend item to hide/show that series.
 */
export default function MetricChart({ data = [], series, height = 280, percent = true, live = false, longRange = false }) {
  const [hidden, setHidden] = useState({})
  const fmt = series[0]?.format || ((v) => `${v.toFixed(1)}${percent ? '%' : ''}`)
  const toggle = (k) => setHidden((h) => ({ ...h, [k]: !h[k] }))

  return (
    <div>
      {series.length > 1 && (
        <div className="mb-3 flex flex-wrap gap-2" role="group" aria-label="Toggle series">
          {series.map((s) => (
            <button key={s.key} onClick={() => toggle(s.key)} aria-pressed={!hidden[s.key]}
              className={`inline-flex items-center gap-2 rounded-lg border border-line px-2.5 py-1 text-xs font-medium transition ${hidden[s.key] ? 'text-muted opacity-50' : 'text-ink-2 hover:bg-surface-2'}`}>
              <span className="h-2.5 w-2.5 rounded-sm" style={{ background: s.color }} />
              {s.label}
            </button>
          ))}
        </div>
      )}
      <div style={{ height }}>
        <ResponsiveContainer width="100%" height="100%">
          <AreaChart data={data} margin={{ top: 6, right: 8, left: -8, bottom: 0 }}>
            <defs>
              {series.map((s) => (
                <linearGradient key={s.key} id={`grad-${s.key}`} x1="0" y1="0" x2="0" y2="1">
                  <stop offset="0%" stopColor={s.color} stopOpacity={0.28} />
                  <stop offset="100%" stopColor={s.color} stopOpacity={0} />
                </linearGradient>
              ))}
            </defs>
            <CartesianGrid stroke="var(--grid)" strokeDasharray="0" vertical={false} />
            <XAxis dataKey="time" type="number" scale="time" domain={['dataMin', 'dataMax']}
              tickFormatter={longRange ? timeShort : live ? timeOnly : timeShort}
              stroke="var(--axis)" tick={{ fill: 'var(--muted)', fontSize: 11 }} tickLine={false}
              minTickGap={48} />
            <YAxis domain={percent ? [0, 100] : [0, 'auto']} stroke="var(--axis)" tickLine={false} axisLine={false}
              tick={{ fill: 'var(--muted)', fontSize: 11 }} width={48}
              tickFormatter={(v) => (percent ? `${v}%` : fmt(v))} />
            <Tooltip content={<ChartTooltip series={series} hidden={hidden} fmt={fmt} />}
              cursor={{ stroke: 'var(--axis)', strokeWidth: 1 }} isAnimationActive={false} />
            {series.map((s) => !hidden[s.key] && (
              <Area key={s.key} type="monotone" dataKey={s.key} name={s.label} stroke={s.color} strokeWidth={2}
                fill={`url(#grad-${s.key})`} dot={false}
                activeDot={{ r: 4.5, stroke: 'var(--surface)', strokeWidth: 2 }}
                isAnimationActive={!live} animationDuration={600} />
            ))}
          </AreaChart>
        </ResponsiveContainer>
      </div>
    </div>
  )
}

function ChartTooltip({ active, payload, label, series, hidden, fmt }) {
  if (!active || !payload?.length) return null
  const row = payload[0].payload
  return (
    <div className="rounded-xl border border-line bg-surface px-3 py-2 shadow-lg">
      <p className="mb-1.5 text-[11px] text-muted">{new Date(label).toLocaleString([], { dateStyle: 'medium', timeStyle: 'medium' })}</p>
      {series.filter((s) => !hidden[s.key]).map((s) => (
        <div key={s.key} className="flex items-center justify-between gap-6 text-xs">
          <span className="flex items-center gap-2 text-ink-2">
            <span className="h-2 w-2 rounded-sm" style={{ background: s.color }} />{s.label}
          </span>
          <span className="tnum font-semibold text-ink">{(s.format || fmt)(row[s.key] ?? 0)}</span>
        </div>
      ))}
    </div>
  )
}
