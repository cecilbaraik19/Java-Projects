import { useCallback, useEffect, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { motion } from 'framer-motion'
import { Bar, BarChart, CartesianGrid, Cell, Pie, PieChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { CalendarClock, ChevronRight, Clock, DollarSign, Lightbulb, PiggyBank, Pencil, Shrink, TrendingUp, Wallet } from 'lucide-react'
import api, { errorMessage } from '../api/client'
import { useAuth } from '../context/AuthContext'
import ResourceIcon, { ProviderTag } from '../components/ResourceIcon'
import { AnimatedNumber, Button, Card, EmptyState, Modal, SectionTitle, Skeleton, StatusBadge } from '../components/ui'
import { money, PROVIDER_LABEL, timeShort, TYPE_LABEL } from '../utils/format'

/** Fixed colour per provider so it never changes between charts. */
const PROVIDER_COLOR = { AWS: 'var(--s-mem)', AZURE: 'var(--s-cpu)', GCP: 'var(--s-disk)', LOCAL: 'var(--s-4)' }
const REFRESH_MS = 15000

const container = { hidden: {}, show: { transition: { staggerChildren: 0.06 } } }
const item = { hidden: { opacity: 0, y: 14 }, show: { opacity: 1, y: 0, transition: { type: 'spring', stiffness: 260, damping: 24 } } }

export default function Costs() {
  const { user } = useAuth()
  const isAdmin = user?.role === 'ADMIN'
  const navigate = useNavigate()
  const [report, setReport] = useState(null)
  const [error, setError] = useState('')
  const [budgetOpen, setBudgetOpen] = useState(false)
  const [sort, setSort] = useState({ key: 'monthly', dir: -1 })

  const load = useCallback(() => {
    api.get('/costs')
      .then((r) => { setReport(r.data); setError('') })
      .catch((e) => setError(errorMessage(e, 'Could not load costs')))
  }, [])

  useEffect(() => {
    load()
    const t = setInterval(load, REFRESH_MS)
    return () => clearInterval(t)
  }, [load])

  const rows = useMemo(() => {
    if (!report) return []
    return [...report.resources].sort((a, b) => {
      const av = a[sort.key] ?? -1
      const bv = b[sort.key] ?? -1
      return typeof av === 'string' ? av.localeCompare(bv) * sort.dir : (av - bv) * sort.dir
    })
  }, [report, sort])

  if (!report) {
    return error ? <Card><EmptyState icon={DollarSign} title="Costs unavailable" text={error} /></Card> : <CostsSkeleton />
  }

  const over = report.budgetUsedPct >= 100
  const near = report.budgetUsedPct >= 85

  return (
    <motion.div variants={container} initial="hidden" animate="show" className="space-y-5">
      <motion.div variants={item}>
        <h1 className="text-2xl font-bold tracking-tight">Costs</h1>
        <p className="mt-1 text-sm text-muted">
          What your cloud costs, where the money goes and how to spend less · refreshes every 15 seconds
        </p>
      </motion.div>

      {/* ---------- stat cards ---------- */}
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <Stat Icon={Clock} label="Burn rate" value={<AnimatedNumber value={report.hourlyBurn} format={(v) => money(v)} />}
          unit="/ hour" foot="Cost of everything running right now" />
        <Stat Icon={CalendarClock} label="Projected this month" value={<AnimatedNumber value={report.projectedMonthly} format={(v) => money(v, 0)} />}
          foot="Burn rate × 730 hours" />
        <Stat Icon={TrendingUp} label="Spent in last 24 hours" value={<AnimatedNumber value={report.last24h} format={(v) => money(v)} />}
          foot="Based on when each resource was running" />
        <Stat Icon={PiggyBank} label="Possible savings" value={<AnimatedNumber value={report.potentialSavings} format={(v) => money(v, 0)} />}
          unit="/ month" foot={`${report.recommendations.length} suggestion${report.recommendations.length === 1 ? '' : 's'} below`} good />
      </div>

      {/* ---------- budget ---------- */}
      <motion.div variants={item}>
        <Card>
          <div className="flex flex-wrap items-start justify-between gap-3">
            <div className="flex items-center gap-3">
              <span className="flex h-10 w-10 items-center justify-center rounded-xl bg-surface-2 text-ink-2"><Wallet size={18} /></span>
              <div>
                <h2 className="text-[15px] font-semibold">Monthly budget</h2>
                <p className="text-xs text-muted">
                  Projected {money(report.projectedMonthly, 0)} of {money(report.monthlyBudget, 0)}
                  {over
                    ? <> · <b style={{ color: 'var(--crit)' }}>over by {money(report.projectedMonthly - report.monthlyBudget, 0)}</b></>
                    : <> · {money(report.monthlyBudget - report.projectedMonthly, 0)} left</>}
                </p>
              </div>
            </div>
            {isAdmin && <Button onClick={() => setBudgetOpen(true)}><Pencil size={14} /> Change budget</Button>}
          </div>
          <div className="mt-4 h-3 w-full overflow-hidden rounded-full bg-surface-2"
            role="progressbar" aria-valuenow={report.budgetUsedPct} aria-valuemin={0} aria-valuemax={100} aria-label="Budget used">
            <motion.div className="h-full rounded-full"
              style={{ background: over ? 'var(--crit)' : near ? 'var(--warn)' : 'var(--good)' }}
              initial={{ width: 0 }} animate={{ width: `${Math.min(100, report.budgetUsedPct)}%` }}
              transition={{ type: 'spring', stiffness: 90, damping: 20 }} />
          </div>
          <div className="mt-2 flex justify-between text-xs text-muted">
            <span className="tnum font-semibold text-ink-2">{report.budgetUsedPct.toFixed(1)}% used</span>
            {report.stoppedSavings > 0 && <span>Stopped resources are saving you {money(report.stoppedSavings, 0)}/month</span>}
          </div>
        </Card>
      </motion.div>

      {/* ---------- hourly spend + provider split ---------- */}
      <div className="grid grid-cols-1 gap-4 xl:grid-cols-3">
        <motion.div variants={item} className="xl:col-span-2">
          <Card className="h-full">
            <SectionTitle title="Spend per hour" subtitle="Last 24 hours · grey gaps mean the app was not running, so there is no data" />
            <HourlyChart data={report.hourly} />
          </Card>
        </motion.div>
        <motion.div variants={item}>
          <Card className="h-full">
            <SectionTitle title="By provider" subtitle="Projected monthly cost" />
            <ProviderDonut slices={report.byProvider} total={report.projectedMonthly} />
          </Card>
        </motion.div>
      </div>

      {/* ---------- recommendations + type split ---------- */}
      <div className="grid grid-cols-1 gap-4 xl:grid-cols-3">
        <motion.div variants={item} className="xl:col-span-2">
          <Card className="h-full">
            <SectionTitle title="Ways to save" subtitle="Based on the last 24 hours of usage" />
            {report.recommendations.length === 0 ? (
              <EmptyState icon={Lightbulb} title="Nothing to suggest right now" text="Every paid resource is well used." />
            ) : (
              <ul className="space-y-2">
                {report.recommendations.map((r) => (
                  <motion.li key={`${r.kind}-${r.resourceId}`} layout initial={{ opacity: 0, x: -8 }} animate={{ opacity: 1, x: 0 }}>
                    <button onClick={() => navigate(`/resources/${r.resourceId}`)}
                      className="flex w-full items-start gap-3 rounded-xl border border-line p-3 text-left transition hover:bg-surface-2">
                      <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl bg-surface-2 text-accent">
                        {r.kind === 'SCHEDULE' ? <CalendarClock size={17} /> : <Shrink size={17} />}
                      </span>
                      <span className="min-w-0 flex-1">
                        <span className="block text-sm font-semibold">{r.title}</span>
                        <span className="mt-0.5 block text-xs text-muted">{r.detail}</span>
                      </span>
                      <span className="shrink-0 text-right">
                        <span className="block text-sm font-bold tnum" style={{ color: 'var(--good-ink)' }}>−{money(r.monthlySaving, 0)}</span>
                        <span className="text-[11px] text-muted">per month</span>
                      </span>
                      <ChevronRight size={15} className="mt-2.5 shrink-0 text-muted" />
                    </button>
                  </motion.li>
                ))}
              </ul>
            )}
          </Card>
        </motion.div>
        <motion.div variants={item}>
          <Card className="h-full">
            <SectionTitle title="By resource type" subtitle="Projected monthly cost" />
            <TypeBars slices={report.byType} />
          </Card>
        </motion.div>
      </div>

      {/* ---------- per-resource table ---------- */}
      <motion.div variants={item}>
        <Card className="overflow-x-auto !p-0">
          <div className="px-5 pt-5"><SectionTitle title="Cost per resource" subtitle="Click a column to sort, a row to open the resource" /></div>
          <table className="w-full min-w-[820px] text-sm">
            <thead>
              <tr className="border-y border-line text-left text-xs text-muted">
                <Th k="name" sort={sort} setSort={setSort}>Resource</Th>
                <Th>Status</Th>
                <Th>Provider</Th>
                <Th k="hourly" sort={sort} setSort={setSort} right>Per hour</Th>
                <Th k="monthly" sort={sort} setSort={setSort} right>Per month</Th>
                <Th k="sharePct" sort={sort} setSort={setSort}>Share of bill</Th>
                <Th k="avgCpu24h" sort={sort} setSort={setSort} right>Avg CPU (24h)</Th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <motion.tr layout key={r.id} onClick={() => navigate(`/resources/${r.id}`)}
                  className="cursor-pointer border-b border-line last:border-0 hover:bg-surface-2">
                  <td className="px-4 py-3">
                    <div className="flex items-center gap-3">
                      <ResourceIcon type={r.type} size={16} />
                      <div className="min-w-0">
                        <p className="font-medium">{r.name}</p>
                        <p className="text-xs text-muted">{TYPE_LABEL[r.type]} · {r.instanceType}</p>
                      </div>
                    </div>
                  </td>
                  <td className="px-4"><StatusBadge status={r.status} /></td>
                  <td className="px-4"><ProviderTag provider={r.provider} /></td>
                  <td className="px-4 text-right text-xs text-ink-2 tnum">{r.hourly > 0 ? money(r.hourly, 3) : 'Free'}</td>
                  <td className="px-4 text-right font-semibold tnum">
                    {r.status === 'STOPPED' ? <span className="text-xs font-normal text-muted">Not billed</span> : money(r.monthly)}
                  </td>
                  <td className="w-44 px-4">
                    <div className="flex items-center gap-2">
                      <div className="h-1.5 flex-1 overflow-hidden rounded-full bg-surface-2">
                        <motion.div className="h-full rounded-full" style={{ background: PROVIDER_COLOR[r.provider] || 'var(--accent)' }}
                          initial={false} animate={{ width: `${r.sharePct}%` }} />
                      </div>
                      <span className="w-11 text-right text-xs text-ink-2 tnum">{r.sharePct.toFixed(1)}%</span>
                    </div>
                  </td>
                  <td className="px-4 text-right text-xs text-ink-2 tnum">{r.avgCpu24h == null ? '–' : `${r.avgCpu24h.toFixed(1)}%`}</td>
                </motion.tr>
              ))}
            </tbody>
          </table>
        </Card>
      </motion.div>

      <BudgetModal open={budgetOpen} onClose={() => setBudgetOpen(false)} current={report.monthlyBudget}
        projected={report.projectedMonthly} onSaved={() => { setBudgetOpen(false); load() }} />
    </motion.div>
  )
}

function Stat({ Icon, label, value, unit, foot, good }) {
  return (
    <motion.div variants={item}>
      <Card hover className="h-full">
        <div className="flex items-center justify-between">
          <span className="text-xs font-medium text-muted">{label}</span>
          <span className="flex h-8 w-8 items-center justify-center rounded-lg bg-surface-2 text-ink-2"><Icon size={16} /></span>
        </div>
        <p className="mt-2 text-[28px] leading-tight font-bold tnum" style={good ? { color: 'var(--good-ink)' } : undefined}>
          {value}{unit && <span className="ml-1 text-sm font-medium text-muted">{unit}</span>}
        </p>
        <p className="mt-2 text-xs text-muted">{foot}</p>
      </Card>
    </motion.div>
  )
}

function HourlyChart({ data }) {
  // Hours without data are drawn as a short grey stub so the gap is visible but not mistaken for $0
  const rows = data.map((h) => ({ ...h, value: h.cost ?? 0, missing: h.cost == null }))
  const max = Math.max(1, ...rows.map((r) => r.value))
  const chartRows = rows.map((r) => ({ ...r, value: r.missing ? max * 0.04 : r.value }))
  return (
    <div style={{ height: 260 }}>
      <ResponsiveContainer width="100%" height="100%">
        <BarChart data={chartRows} margin={{ top: 6, right: 8, left: -4, bottom: 0 }}>
          <CartesianGrid stroke="var(--grid)" vertical={false} />
          <XAxis dataKey="time" tickFormatter={timeShort} stroke="var(--axis)" tick={{ fill: 'var(--muted)', fontSize: 11 }}
            tickLine={false} minTickGap={36} />
          <YAxis stroke="var(--axis)" tick={{ fill: 'var(--muted)', fontSize: 11 }} tickLine={false} axisLine={false}
            width={52} tickFormatter={(v) => money(v, 0)} />
          <Tooltip cursor={{ fill: 'var(--surface-2)' }} isAnimationActive={false}
            content={({ active, payload }) => {
              if (!active || !payload?.length) return null
              const h = payload[0].payload
              const end = new Date(h.time + 3600000)
              return (
                <div className="rounded-xl border border-line bg-surface px-3 py-2 text-xs shadow-lg">
                  <p className="mb-1 text-[11px] text-muted">{timeShort(h.time)} – {timeShort(end)}</p>
                  {h.missing
                    ? <p className="text-ink-2">No data · app was not running</p>
                    : <>
                      <p><b className="tnum text-ink">{money(h.cost)}</b> <span className="text-muted">spent</span></p>
                      <p className="text-muted">{h.runningResources} resources running</p>
                    </>}
                </div>
              )
            }} />
          <Bar dataKey="value" radius={[4, 4, 0, 0]} maxBarSize={22} animationDuration={600}>
            {chartRows.map((r) => <Cell key={r.time} fill={r.missing ? 'var(--axis)' : 'var(--accent)'} />)}
          </Bar>
        </BarChart>
      </ResponsiveContainer>
    </div>
  )
}

function ProviderDonut({ slices, total }) {
  if (!slices.length) return <EmptyState icon={DollarSign} title="No paid resources running" />
  return (
    <div>
      <div className="relative mx-auto h-44 w-44">
        <ResponsiveContainer width="100%" height="100%">
          <PieChart>
            <Pie data={slices} dataKey="monthly" nameKey="key" innerRadius="68%" outerRadius="100%"
              paddingAngle={3} cornerRadius={4} stroke="var(--surface)" strokeWidth={2} animationDuration={700}>
              {slices.map((s) => <Cell key={s.key} fill={PROVIDER_COLOR[s.key] || 'var(--accent)'} />)}
            </Pie>
            <Tooltip content={({ active, payload }) => active && payload?.length ? (
              <div className="rounded-lg border border-line bg-surface px-2.5 py-1.5 text-xs shadow-lg">
                <span className="text-ink-2">{PROVIDER_LABEL[payload[0].name] || payload[0].name}: </span>
                <b className="text-ink tnum">{money(payload[0].value, 0)}/mo</b>
              </div>) : null} />
          </PieChart>
        </ResponsiveContainer>
        <div className="pointer-events-none absolute inset-0 flex flex-col items-center justify-center">
          <span className="text-xl font-bold tnum">{money(total, 0)}</span>
          <span className="text-[11px] text-muted">per month</span>
        </div>
      </div>
      <ul className="mt-5 space-y-2">
        {slices.map((s) => (
          <li key={s.key} className="flex items-center justify-between rounded-lg bg-surface-2 px-3 py-2 text-xs">
            <span className="flex items-center gap-2 text-ink-2">
              <span className="h-2.5 w-2.5 rounded-sm" style={{ background: PROVIDER_COLOR[s.key] || 'var(--accent)' }} />
              {PROVIDER_LABEL[s.key] || s.key} <span className="text-muted">· {s.count}</span>
            </span>
            <span className="tnum">
              <b>{money(s.monthly, 0)}</b>
              <span className="ml-1.5 text-muted">{total > 0 ? Math.round((s.monthly / total) * 100) : 0}%</span>
            </span>
          </li>
        ))}
      </ul>
    </div>
  )
}

function TypeBars({ slices }) {
  if (!slices.length) return <EmptyState icon={DollarSign} title="No paid resources running" />
  const max = Math.max(...slices.map((s) => s.monthly))
  return (
    <ul className="space-y-4">
      {slices.map((s) => (
        <li key={s.key}>
          <div className="mb-1.5 flex items-center justify-between text-xs">
            <span className="text-ink-2">{TYPE_LABEL[s.key] || s.key} <span className="text-muted">· {s.count}</span></span>
            <b className="tnum">{money(s.monthly, 0)}</b>
          </div>
          <div className="h-2 w-full overflow-hidden rounded-full bg-surface-2">
            <motion.div className="h-full rounded-full bg-accent" initial={{ width: 0 }}
              animate={{ width: `${(s.monthly / max) * 100}%` }} transition={{ type: 'spring', stiffness: 100, damping: 20 }} />
          </div>
        </li>
      ))}
    </ul>
  )
}

function BudgetModal({ open, onClose, current, projected, onSaved }) {
  const [value, setValue] = useState(String(current))
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState('')

  // Reset the field every time the dialog opens
  const [lastOpen, setLastOpen] = useState(open)
  if (open !== lastOpen) {
    setLastOpen(open)
    if (open) { setValue(String(Math.round(current))); setError('') }
  }

  const num = Number(value)
  const valid = value !== '' && Number.isFinite(num) && num >= 1

  const save = async (e) => {
    e.preventDefault()
    if (!valid) return
    setSaving(true)
    setError('')
    try {
      await api.put('/costs/budget', { monthlyBudget: num })
      onSaved()
    } catch (err) {
      setError(errorMessage(err, 'Could not save the budget'))
    } finally {
      setSaving(false)
    }
  }

  return (
    <Modal open={open} onClose={onClose} title="Monthly budget">
      <form onSubmit={save} className="space-y-4">
        <label className="block">
          <span className="mb-1.5 block text-xs font-medium text-ink-2">Budget in USD per month</span>
          <div className="flex items-center rounded-xl border border-line bg-surface-2 px-3 focus-within:border-accent">
            <span className="text-muted">$</span>
            <input type="number" min="1" step="1" autoFocus value={value} onChange={(e) => setValue(e.target.value)}
              className="w-full bg-transparent px-2 py-2.5 text-sm outline-none tnum" />
          </div>
        </label>
        {valid && (
          <p className="text-xs text-muted">
            Your projected spend of {money(projected, 0)} would be <b className="tnum text-ink-2">{((projected / num) * 100).toFixed(1)}%</b> of this budget.
          </p>
        )}
        {error && <p className="text-xs" style={{ color: 'var(--crit)' }}>{error}</p>}
        <div className="flex justify-end gap-2">
          <Button type="button" onClick={onClose}>Cancel</Button>
          <Button type="submit" variant="primary" disabled={!valid || saving}>{saving ? 'Saving…' : 'Save budget'}</Button>
        </div>
      </form>
    </Modal>
  )
}

function Th({ children, k, sort, setSort, right }) {
  const cls = `px-4 py-3 font-medium ${right ? 'text-right' : ''}`
  if (!k) return <th className={cls}>{children}</th>
  const active = sort.key === k
  return (
    <th className={cls} aria-sort={active ? (sort.dir === 1 ? 'ascending' : 'descending') : 'none'}>
      <button onClick={() => setSort({ key: k, dir: active ? -sort.dir : (k === 'name' ? 1 : -1) })}
        className={`inline-flex items-center gap-1 hover:text-ink ${active ? 'text-ink' : ''}`}>
        {children}{active && (sort.dir === 1 ? ' ↑' : ' ↓')}
      </button>
    </th>
  )
}

function CostsSkeleton() {
  return (
    <div className="space-y-5">
      <Skeleton className="h-8 w-40" />
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-4">
        {Array.from({ length: 4 }).map((_, i) => <Skeleton key={i} className="h-32" />)}
      </div>
      <Skeleton className="h-28" />
      <Skeleton className="h-80" />
    </div>
  )
}
