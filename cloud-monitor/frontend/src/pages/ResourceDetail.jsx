import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { motion } from 'framer-motion'
import { ArrowDownToLine, ArrowUpFromLine, Bell, ChevronLeft, Clock, DollarSign, Globe, MapPin, Monitor, ScrollText } from 'lucide-react'
import api from '../api/client'
import { useLive } from '../context/LiveContext'
import useHistory, { RANGES } from '../hooks/useHistory'
import Gauge from '../components/Gauge'
import MetricChart from '../components/MetricChart'
import ResourceActions from '../components/ResourceActions'
import ResourceIcon, { ProviderTag } from '../components/ResourceIcon'
import { Card, EmptyState, SectionTitle, SeverityBadge, Skeleton, StatusBadge, Tabs } from '../components/ui'
import { PROVIDER_LABEL, TYPE_LABEL, ago, money, rate, uptime } from '../utils/format'

const UTIL_SERIES = [
  { key: 'cpu', label: 'CPU', color: 'var(--s-cpu)' },
  { key: 'memory', label: 'Memory', color: 'var(--s-mem)' },
  { key: 'disk', label: 'Disk used', color: 'var(--s-disk)' },
]
const NET_SERIES = [
  { key: 'networkIn', label: 'Inbound', color: 'var(--s-cpu)', format: rate },
  { key: 'networkOut', label: 'Outbound', color: 'var(--s-mem)', format: rate },
]

export default function ResourceDetail() {
  const { id } = useParams()
  const { resources, series, onAlert } = useLive()
  const [range, setRange] = useState('live')
  const history = useHistory(`/resources/${id}/metrics`, range)
  const [alerts, setAlerts] = useState([])
  const [, tick] = useState(0)
  const r = resources.find((x) => String(x.id) === id)

  useEffect(() => {
    const load = () => api.get(`/resources/${id}/alerts`).then((res) => setAlerts(res.data)).catch(() => {})
    load()
    const off = onAlert((a) => String(a.resourceId) === id && load())
    const t = setInterval(() => tick((n) => n + 1), 1000) // keeps uptime counting
    return () => { off(); clearInterval(t) }
  }, [id, onAlert])

  if (!r) {
    return resources.length ? (
      <Card><EmptyState icon={Monitor} title="Resource not found" text={<Link to="/resources" className="text-accent">Back to resources</Link>} /></Card>
    ) : <Skeleton className="h-96" />
  }

  const live = series.byId[r.id] || []
  const data = range === 'live' ? live : history.data
  const stopped = r.status === 'STOPPED'

  return (
    <div className="space-y-5">
      <Link to="/resources" className="inline-flex items-center gap-1 text-sm text-muted hover:text-ink"><ChevronLeft size={16} /> Resources</Link>

      {/* ---------- header ---------- */}
      <Card>
        <div className="flex flex-wrap items-start gap-4">
          <ResourceIcon type={r.type} size={22} />
          <div className="min-w-0 flex-1">
            <div className="flex flex-wrap items-center gap-2">
              <h1 className="text-xl font-bold tracking-tight">{r.name}</h1>
              <StatusBadge status={r.status} size="lg" />
            </div>
            <div className="mt-2 flex flex-wrap gap-x-5 gap-y-1.5 text-xs text-muted">
              <span className="flex items-center gap-1.5"><ProviderTag provider={r.provider} /> {PROVIDER_LABEL[r.provider]}</span>
              <span className="flex items-center gap-1.5"><MapPin size={13} />{r.region}</span>
              <span className="flex items-center gap-1.5"><Monitor size={13} />{TYPE_LABEL[r.type]} · {r.instanceType}</span>
              <span className="flex items-center gap-1.5"><Globe size={13} />{r.ipAddress}</span>
              <span className="flex items-center gap-1.5"><Clock size={13} />{stopped ? 'Stopped' : `Up ${uptime(r.startedAt)}`}</span>
              {r.hourlyCost > 0 && <span className="flex items-center gap-1.5"><DollarSign size={13} />{money(r.hourlyCost, 3)}/h · {money(r.hourlyCost * 730)}/mo</span>}
            </div>
            <p className="mt-1.5 text-xs text-muted">{r.os}</p>
          </div>
          <div className="flex items-center gap-2">
            <Link to={`/logs?resource=${r.id}`}
              className="inline-flex items-center gap-1.5 rounded-xl border border-line bg-surface px-3.5 py-2 text-sm font-medium text-ink hover:bg-surface-2">
              <ScrollText size={15} /> Logs
            </Link>
            <ResourceActions resource={r} />
          </div>
        </div>
      </Card>

      {/* ---------- gauges ---------- */}
      <div className="grid grid-cols-1 gap-4 md:grid-cols-4">
        <Card className="md:col-span-3">
          <SectionTitle title="Current utilisation" subtitle={stopped ? 'Resource is stopped' : 'Updated live'} />
          <div className="grid grid-cols-1 place-items-center gap-6 sm:grid-cols-3">
            <Gauge value={r.cpu} label="CPU" />
            <Gauge value={r.memory} label="Memory" />
            <Gauge value={r.disk} label="Disk used" />
          </div>
        </Card>
        <Card>
          <SectionTitle title="Network" subtitle="Current rate" />
          <div className="space-y-4">
            <NetStat Icon={ArrowDownToLine} label="Inbound" value={rate(r.networkIn)} />
            <NetStat Icon={ArrowUpFromLine} label="Outbound" value={rate(r.networkOut)} />
          </div>
        </Card>
      </div>

      {/* ---------- charts ---------- */}
      <Card>
        <SectionTitle title="Utilisation over time" subtitle={range === 'live' ? 'Last few minutes, streaming' : `Last ${range}`}
          right={<Tabs id="detail" options={RANGES} value={range} onChange={setRange} />} />
        {range !== 'live' && history.loading ? <Skeleton className="h-[280px]" /> : data.length < 2 ? (
          <EmptyState icon={Clock} title="Collecting data…" text={r.type === 'LOCAL_HOST' ? 'History for this PC is recorded every 15 seconds.' : 'Points appear every few seconds.'} />
        ) : (
          <MetricChart data={data} series={UTIL_SERIES} live={range === 'live'} longRange={range === '6h' || range === '24h'} />
        )}
      </Card>

      <div className="grid grid-cols-1 gap-4 xl:grid-cols-2">
        <Card>
          <SectionTitle title="Network traffic" subtitle={range === 'live' ? 'Streaming' : `Last ${range}`} />
          {data.length < 2 ? <EmptyState icon={Clock} title="Collecting data…" /> : (
            <MetricChart data={data} series={NET_SERIES} percent={false} height={220} live={range === 'live'} longRange={range === '6h' || range === '24h'} />
          )}
        </Card>
        <Card>
          <SectionTitle title="Alerts for this resource" subtitle="Most recent first" />
          {alerts.length === 0 ? <EmptyState icon={Bell} title="No alerts" text="This resource hasn't breached any rule." /> : (
            <ul className="max-h-[260px] space-y-1 overflow-y-auto pr-1">
              {alerts.map((a) => (
                <motion.li key={a.id} initial={{ opacity: 0 }} animate={{ opacity: 1 }}
                  className={`flex items-start gap-3 rounded-xl p-2 ${a.acknowledged ? 'opacity-55' : ''}`}>
                  <SeverityBadge severity={a.severity} />
                  <div className="min-w-0 flex-1">
                    <p className="text-xs font-medium">{a.message}</p>
                    <p className="text-[11px] text-muted">{a.ruleName} · {ago(a.createdAt)}{a.acknowledged ? ` · acknowledged by ${a.acknowledgedBy}` : ''}</p>
                  </div>
                </motion.li>
              ))}
            </ul>
          )}
        </Card>
      </div>
    </div>
  )
}

function NetStat({ Icon, label, value }) {
  return (
    <div className="flex items-center gap-3 rounded-xl bg-surface-2 p-3">
      <span className="flex h-9 w-9 items-center justify-center rounded-lg bg-surface text-accent"><Icon size={17} /></span>
      <div>
        <p className="text-xs text-muted">{label}</p>
        <p className="text-lg font-semibold tnum">{value}</p>
      </div>
    </div>
  )
}
