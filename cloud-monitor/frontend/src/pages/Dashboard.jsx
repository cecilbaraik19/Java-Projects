import { useEffect, useMemo, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { motion } from 'framer-motion'
import { Cell, Pie, PieChart, ResponsiveContainer, Tooltip } from 'recharts'
import {
  ArrowDownToLine, ArrowUpFromLine, Bell, ChevronRight, Cpu, DollarSign, MemoryStick, Server,
} from 'lucide-react'
import { useLive } from '../context/LiveContext'
import api from '../api/client'
import useHistory, { RANGES } from '../hooks/useHistory'
import MetricChart from '../components/MetricChart'
import Sparkline from '../components/Sparkline'
import ResourceIcon, { ProviderTag } from '../components/ResourceIcon'
import { AnimatedNumber, Card, SectionTitle, SeverityBadge, Skeleton, Tabs, UsageBar } from '../components/ui'
import { ago, money, rate } from '../utils/format'

const UTIL_SERIES = [
  { key: 'cpu', label: 'CPU', color: 'var(--s-cpu)' },
  { key: 'memory', label: 'Memory', color: 'var(--s-mem)' },
  { key: 'disk', label: 'Disk used', color: 'var(--s-disk)' },
]
const NET_SERIES = [
  { key: 'networkIn', label: 'Inbound', color: 'var(--s-cpu)', format: rate },
  { key: 'networkOut', label: 'Outbound', color: 'var(--s-mem)', format: rate },
]

const container = { hidden: {}, show: { transition: { staggerChildren: 0.06 } } }
const item = { hidden: { opacity: 0, y: 14 }, show: { opacity: 1, y: 0, transition: { type: 'spring', stiffness: 260, damping: 24 } } }

export default function Dashboard() {
  const { overview, resources, series, onAlert } = useLive()
  const [range, setRange] = useState('live')
  const [netRange, setNetRange] = useState('live')
  const util = useHistory('/overview/history', range)
  const net = useHistory('/overview/history', netRange)
  const [alerts, setAlerts] = useState([])
  const navigate = useNavigate()

  useEffect(() => {
    const load = () => api.get('/alerts', { params: { limit: 6 } }).then((r) => setAlerts(r.data)).catch(() => {})
    load()
    return onAlert(load)
  }, [onAlert])

  const top = useMemo(() => [...resources].filter((r) => r.status !== 'STOPPED').sort((a, b) => b.cpu - a.cpu).slice(0, 5), [resources])

  if (!overview) return <DashboardSkeleton />

  const health = [
    { name: 'Running', value: overview.running, color: 'var(--good)' },
    { name: 'Warning', value: overview.warning, color: 'var(--warn)' },
    { name: 'Critical', value: overview.critical, color: 'var(--crit)' },
    { name: 'Stopped', value: overview.stopped, color: 'var(--axis)' },
  ]

  return (
    <motion.div variants={container} initial="hidden" animate="show" className="space-y-5">
      <motion.div variants={item} className="flex flex-wrap items-end justify-between gap-2">
        <div>
          <h1 className="text-2xl font-bold tracking-tight">Infrastructure overview</h1>
          <p className="mt-1 text-sm text-muted">
            {overview.totalResources} resources across {Object.keys(overview.byProvider).length} providers · updates every 3 seconds
          </p>
        </div>
      </motion.div>

      {/* ---------- stat cards ---------- */}
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-5">
        <StatCard Icon={Server} label="Resources" onClick={() => navigate('/resources')}
          value={<AnimatedNumber value={overview.totalResources} />}
          foot={<span className="flex flex-wrap gap-x-3 gap-y-1">
            <Dot c="var(--good)" t={`${overview.running} ok`} /><Dot c="var(--warn)" t={`${overview.warning} warn`} />
            <Dot c="var(--crit)" t={`${overview.critical} crit`} /><Dot c="var(--axis)" t={`${overview.stopped} off`} />
          </span>} />
        <StatCard Icon={Cpu} label="Avg CPU" value={<><AnimatedNumber value={overview.avgCpu} format={(v) => v.toFixed(1)} /><Unit>%</Unit></>}
          foot={<Sparkline data={series.overview} dataKey="cpu" color="var(--s-cpu)" />} />
        <StatCard Icon={MemoryStick} label="Avg memory" value={<><AnimatedNumber value={overview.avgMemory} format={(v) => v.toFixed(1)} /><Unit>%</Unit></>}
          foot={<Sparkline data={series.overview} dataKey="memory" color="var(--s-mem)" />} />
        <StatCard Icon={ArrowDownToLine} label="Network throughput"
          value={<AnimatedNumber value={overview.totalNetworkIn + overview.totalNetworkOut} format={rate} />}
          foot={<span className="flex gap-3 text-xs text-muted">
            <span className="flex items-center gap-1"><ArrowDownToLine size={12} /> {rate(overview.totalNetworkIn)}</span>
            <span className="flex items-center gap-1"><ArrowUpFromLine size={12} /> {rate(overview.totalNetworkOut)}</span>
          </span>} />
        <StatCard Icon={DollarSign} label="Est. monthly cost"
          value={<AnimatedNumber value={overview.monthlyCostEstimate} format={(v) => money(v, 0)} />}
          foot={<span className="text-xs text-muted">{money(overview.hourlyCost)} / hour for running resources</span>} />
      </div>

      {/* ---------- utilisation chart + health ---------- */}
      <div className="grid grid-cols-1 gap-4 xl:grid-cols-3">
        <motion.div variants={item} className="xl:col-span-2">
          <Card>
            <SectionTitle title="Average utilisation" subtitle={range === 'live' ? 'Streaming live from the server' : `Last ${range}, averaged across running resources`}
              right={<Tabs id="util" options={RANGES} value={range} onChange={setRange} />} />
            <MetricChart data={range === 'live' ? series.overview : util.data} series={UTIL_SERIES}
              live={range === 'live'} longRange={range === '6h' || range === '24h'} />
          </Card>
        </motion.div>

        <motion.div variants={item}>
          <Card className="h-full">
            <SectionTitle title="Health" subtitle="Resources by status" />
            <div className="relative mx-auto h-48 w-48">
              <ResponsiveContainer width="100%" height="100%">
                <PieChart>
                  <Pie data={health.filter((h) => h.value > 0)} dataKey="value" innerRadius="70%" outerRadius="100%"
                    paddingAngle={3} cornerRadius={4} stroke="var(--surface)" strokeWidth={2} animationDuration={700}>
                    {health.filter((h) => h.value > 0).map((h) => <Cell key={h.name} fill={h.color} />)}
                  </Pie>
                  <Tooltip content={({ active, payload }) => active && payload?.length ? (
                    <div className="rounded-lg border border-line bg-surface px-2.5 py-1.5 text-xs shadow-lg">
                      <span className="text-ink-2">{payload[0].name}: </span><b className="text-ink">{payload[0].value}</b>
                    </div>) : null} />
                </PieChart>
              </ResponsiveContainer>
              <div className="pointer-events-none absolute inset-0 flex flex-col items-center justify-center">
                <span className="text-3xl font-bold tnum">
                  {Math.round(((overview.running) / Math.max(1, overview.totalResources - overview.stopped)) * 100)}%
                </span>
                <span className="text-xs text-muted">healthy</span>
              </div>
            </div>
            <div className="mt-5 grid grid-cols-2 gap-2">
              {health.map((h) => (
                <div key={h.name} className="flex items-center justify-between rounded-lg bg-surface-2 px-3 py-2 text-xs">
                  <span className="flex items-center gap-2 text-ink-2"><span className="h-2.5 w-2.5 rounded-sm" style={{ background: h.color }} />{h.name}</span>
                  <b className="tnum">{h.value}</b>
                </div>
              ))}
            </div>
          </Card>
        </motion.div>
      </div>

      {/* ---------- top consumers / alerts / network ---------- */}
      <div className="grid grid-cols-1 gap-4 xl:grid-cols-3">
        <motion.div variants={item}>
          <Card className="h-full">
            <SectionTitle title="Top CPU consumers" subtitle="Click a row for details"
              right={<Link to="/resources" className="text-xs font-medium text-accent hover:underline">View all</Link>} />
            <div className="space-y-1">
              {top.map((r) => (
                <motion.button layout key={r.id} onClick={() => navigate(`/resources/${r.id}`)}
                  className="flex w-full items-center gap-3 rounded-xl p-2 text-left transition hover:bg-surface-2">
                  <ResourceIcon type={r.type} size={16} />
                  <div className="min-w-0 flex-1">
                    <div className="mb-1 flex items-center justify-between gap-2">
                      <span className="truncate text-sm font-medium">{r.name}</span>
                      <span className="tnum text-xs font-semibold text-ink-2">{r.cpu.toFixed(1)}%</span>
                    </div>
                    <UsageBar value={r.cpu} />
                  </div>
                </motion.button>
              ))}
            </div>
          </Card>
        </motion.div>

        <motion.div variants={item}>
          <Card className="h-full">
            <SectionTitle title="Recent alerts" subtitle={`${overview.activeAlerts} unacknowledged`}
              right={<Link to="/alerts" className="text-xs font-medium text-accent hover:underline">Manage</Link>} />
            {alerts.length === 0 ? (
              <div className="flex flex-col items-center py-10 text-center text-sm text-muted"><Bell size={24} className="mb-2" />No alerts yet</div>
            ) : (
              <ul className="space-y-1">
                {alerts.map((a) => (
                  <motion.li key={a.id} layout initial={{ opacity: 0, x: -10 }} animate={{ opacity: 1, x: 0 }}>
                    <button onClick={() => a.resourceId && navigate(`/resources/${a.resourceId}`)}
                      className={`flex w-full items-start gap-3 rounded-xl p-2 text-left transition hover:bg-surface-2 ${a.acknowledged ? 'opacity-55' : ''}`}>
                      <SeverityBadge severity={a.severity} />
                      <span className="min-w-0 flex-1">
                        <span className="block truncate text-xs font-medium text-ink">{a.message}</span>
                        <span className="text-[11px] text-muted">{ago(a.createdAt)}</span>
                      </span>
                      <ChevronRight size={14} className="mt-1 text-muted" />
                    </button>
                  </motion.li>
                ))}
              </ul>
            )}
          </Card>
        </motion.div>

        <motion.div variants={item}>
          <Card className="h-full">
            <SectionTitle title="Network traffic" subtitle="All resources combined"
              right={<Tabs id="net" options={RANGES.filter((r) => ['live', '1h', '24h'].includes(r.value))} value={netRange} onChange={setNetRange} />} />
            <MetricChart data={netRange === 'live' ? series.overview : net.data} series={NET_SERIES} percent={false}
              height={200} live={netRange === 'live'} longRange={netRange === '24h'} />
          </Card>
        </motion.div>
      </div>

      {/* ---------- provider breakdown ---------- */}
      <motion.div variants={item}>
        <Card>
          <SectionTitle title="By provider" subtitle="Where your resources run" />
          <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
            {Object.entries(overview.byProvider).map(([p, n]) => {
              const list = resources.filter((r) => r.provider === p)
              const cost = list.filter((r) => r.status !== 'STOPPED').reduce((s, r) => s + r.hourlyCost, 0) * 730
              return (
                <motion.button key={p} whileHover={{ y: -2 }} onClick={() => navigate(`/resources?provider=${p}`)}
                  className="rounded-xl border border-line p-4 text-left transition hover:bg-surface-2">
                  <ProviderTag provider={p} />
                  <p className="mt-3 text-2xl font-bold tnum">{n}</p>
                  <p className="text-xs text-muted">resources · {money(cost, 0)}/mo</p>
                </motion.button>
              )
            })}
          </div>
        </Card>
      </motion.div>
    </motion.div>
  )
}

function StatCard({ Icon, label, value, foot, onClick }) {
  return (
    <motion.div variants={item}>
      <Card hover className={`h-full ${onClick ? 'cursor-pointer' : ''}`} onClick={onClick}>
        <div className="flex items-center justify-between">
          <span className="text-xs font-medium text-muted">{label}</span>
          <span className="flex h-8 w-8 items-center justify-center rounded-lg bg-surface-2 text-ink-2"><Icon size={16} /></span>
        </div>
        <p className="mt-2 text-[28px] leading-tight font-bold tnum">{value}</p>
        <div className="mt-3 min-h-9">{foot}</div>
      </Card>
    </motion.div>
  )
}

const Unit = ({ children }) => <span className="ml-0.5 text-base font-medium text-muted">{children}</span>
const Dot = ({ c, t }) => <span className="flex items-center gap-1 text-xs text-muted"><span className="h-2 w-2 rounded-full" style={{ background: c }} />{t}</span>

function DashboardSkeleton() {
  return (
    <div className="space-y-5">
      <Skeleton className="h-8 w-64" />
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-5">
        {Array.from({ length: 5 }).map((_, i) => <Skeleton key={i} className="h-36" />)}
      </div>
      <Skeleton className="h-80" />
    </div>
  )
}
