import { useMemo, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { AnimatePresence, motion } from 'framer-motion'
import { LayoutGrid, List, Search, Server, X } from 'lucide-react'
import { useLive } from '../context/LiveContext'
import ResourceIcon, { ProviderTag } from '../components/ResourceIcon'
import ResourceActions from '../components/ResourceActions'
import Sparkline from '../components/Sparkline'
import { Card, EmptyState, StatusBadge, Tabs, UsageBar } from '../components/ui'
import { PROVIDER_LABEL, TYPE_LABEL, money, rate } from '../utils/format'

const STATUSES = ['ALL', 'RUNNING', 'WARNING', 'CRITICAL', 'STOPPED']
const SORTS = { name: 'Name', cpu: 'CPU', memory: 'Memory', disk: 'Disk', hourlyCost: 'Cost' }

export default function Resources() {
  const { resources, series } = useLive()
  const [params, setParams] = useSearchParams()
  const navigate = useNavigate()
  const [query, setQuery] = useState('')
  const [view, setView] = useState(() => { try { return localStorage.getItem('resView') || 'grid' } catch { return 'grid' } })
  const [sort, setSort] = useState({ key: 'name', dir: 1 })
  const provider = params.get('provider') || 'ALL'
  const status = params.get('status') || 'ALL'
  const type = params.get('type') || 'ALL'

  const setParam = (k, v) => {
    const next = new URLSearchParams(params)
    if (v === 'ALL') next.delete(k)
    else next.set(k, v)
    setParams(next, { replace: true })
  }
  const changeView = (v) => { setView(v); try { localStorage.setItem('resView', v) } catch { /* ignore */ } }

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase()
    return resources
      .filter((r) => provider === 'ALL' || r.provider === provider)
      .filter((r) => status === 'ALL' || r.status === status)
      .filter((r) => type === 'ALL' || r.type === type)
      .filter((r) => !q || [r.name, r.region, r.instanceType, r.ipAddress, r.os].some((f) => f?.toLowerCase().includes(q)))
      .sort((a, b) => {
        const x = a[sort.key], y = b[sort.key]
        return (typeof x === 'string' ? x.localeCompare(y) : x - y) * sort.dir
      })
  }, [resources, provider, status, type, query, sort])

  const providers = ['ALL', ...new Set(resources.map((r) => r.provider))]
  const types = ['ALL', ...new Set(resources.map((r) => r.type))]
  const anyFilter = provider !== 'ALL' || status !== 'ALL' || type !== 'ALL' || query

  return (
    <div className="space-y-5">
      <div>
        <h1 className="text-2xl font-bold tracking-tight">Resources</h1>
        <p className="mt-1 text-sm text-muted">{filtered.length} of {resources.length} resources · live metrics</p>
      </div>

      {/* ---------- filters (one row) ---------- */}
      <Card className="!p-3">
        <div className="flex flex-wrap items-center gap-2">
          <div className="relative min-w-[200px] flex-1">
            <Search size={16} className="absolute top-1/2 left-3 -translate-y-1/2 text-muted" />
            <input value={query} onChange={(e) => setQuery(e.target.value)} placeholder="Search name, region, IP, OS…"
              className="w-full rounded-xl border border-line bg-surface-2 py-2 pr-3 pl-9 text-sm outline-none focus:border-accent" />
          </div>
          <Select value={provider} onChange={(v) => setParam('provider', v)} options={providers} label={(p) => (p === 'ALL' ? 'All providers' : PROVIDER_LABEL[p])} />
          <Select value={type} onChange={(v) => setParam('type', v)} options={types} label={(t) => (t === 'ALL' ? 'All types' : TYPE_LABEL[t])} />
          <Select value={sort.key} onChange={(v) => setSort({ key: v, dir: v === 'name' ? 1 : -1 })} options={Object.keys(SORTS)} label={(k) => `Sort: ${SORTS[k]}`} />
          <Tabs id="view" value={view} onChange={changeView}
            options={[{ value: 'grid', label: <LayoutGrid size={15} /> }, { value: 'table', label: <List size={15} /> }]} />
        </div>
        <div className="mt-3 flex flex-wrap items-center gap-1.5">
          {STATUSES.map((s) => {
            const count = s === 'ALL' ? resources.length : resources.filter((r) => r.status === s).length
            const active = status === s
            return (
              <button key={s} onClick={() => setParam('status', s)}
                className={`rounded-full border px-3 py-1 text-xs font-medium transition ${active ? 'border-accent bg-accent text-white' : 'border-line text-ink-2 hover:bg-surface-2'}`}>
                {s === 'ALL' ? 'All' : s[0] + s.slice(1).toLowerCase()} <span className="tnum opacity-70">{count}</span>
              </button>
            )
          })}
          {anyFilter && (
            <button onClick={() => { setQuery(''); setParams({}, { replace: true }) }} className="ml-1 inline-flex items-center gap-1 text-xs text-muted hover:text-ink">
              <X size={13} /> Clear filters
            </button>
          )}
        </div>
      </Card>

      {filtered.length === 0 ? (
        <Card><EmptyState icon={Server} title="No resources match" text="Try clearing the filters." /></Card>
      ) : view === 'grid' ? (
        <motion.div layout className="grid grid-cols-1 gap-4 md:grid-cols-2 2xl:grid-cols-3">
          <AnimatePresence>
            {filtered.map((r) => (
              <motion.div key={r.id} layout initial={{ opacity: 0, scale: 0.96 }} animate={{ opacity: 1, scale: 1 }} exit={{ opacity: 0, scale: 0.96 }}
                transition={{ type: 'spring', stiffness: 300, damping: 28 }}>
                <Card hover className={`cursor-pointer ${r.status === 'STOPPED' ? 'opacity-70' : ''}`} onClick={() => navigate(`/resources/${r.id}`)}>
                  <div className="flex items-start gap-3">
                    <ResourceIcon type={r.type} />
                    <div className="min-w-0 flex-1">
                      <p className="truncate font-semibold">{r.name}</p>
                      <p className="truncate text-xs text-muted">{r.instanceType} · {r.region}</p>
                    </div>
                    <StatusBadge status={r.status} />
                  </div>
                  <div className="mt-4 grid grid-cols-3 gap-3">
                    <UsageBar label="CPU" value={r.cpu} />
                    <UsageBar label="Mem" value={r.memory} />
                    <UsageBar label="Disk used" value={r.disk} />
                  </div>
                  <div className="mt-3">
                    <Sparkline data={series.byId[r.id] || []} dataKey="cpu" color="var(--s-cpu)" height={40} />
                  </div>
                  <div className="mt-2 flex items-center justify-between gap-2">
                    <div className="flex items-center gap-2 text-xs text-muted">
                      <ProviderTag provider={r.provider} />
                      <span className="tnum">↓ {rate(r.networkIn)}</span>
                      {r.hourlyCost > 0 && <span className="tnum">{money(r.hourlyCost * 730, 0)}/mo</span>}
                    </div>
                    <ResourceActions resource={r} compact />
                  </div>
                </Card>
              </motion.div>
            ))}
          </AnimatePresence>
        </motion.div>
      ) : (
        <Card className="overflow-x-auto !p-0">
          <table className="w-full min-w-[860px] text-sm">
            <thead>
              <tr className="border-b border-line text-left text-xs text-muted">
                <Th>Resource</Th><Th>Status</Th><Th>Provider</Th>
                <Th sortKey="cpu" sort={sort} setSort={setSort}>CPU</Th>
                <Th sortKey="memory" sort={sort} setSort={setSort}>Memory</Th>
                <Th sortKey="disk" sort={sort} setSort={setSort}>Disk used</Th>
                <Th>Trend</Th><Th>Network in</Th><Th sortKey="hourlyCost" sort={sort} setSort={setSort}>Cost/mo</Th><Th />
              </tr>
            </thead>
            <tbody>
              {filtered.map((r) => (
                <motion.tr layout key={r.id} onClick={() => navigate(`/resources/${r.id}`)}
                  className="cursor-pointer border-b border-line last:border-0 hover:bg-surface-2">
                  <td className="px-4 py-3">
                    <div className="flex items-center gap-3">
                      <ResourceIcon type={r.type} size={16} />
                      <div className="min-w-0"><p className="font-medium">{r.name}</p><p className="text-xs text-muted">{TYPE_LABEL[r.type]} · {r.region}</p></div>
                    </div>
                  </td>
                  <td className="px-4"><StatusBadge status={r.status} /></td>
                  <td className="px-4"><ProviderTag provider={r.provider} /></td>
                  <td className="w-28 px-4"><UsageBar label=" " value={r.cpu} /></td>
                  <td className="w-28 px-4"><UsageBar label=" " value={r.memory} /></td>
                  <td className="w-28 px-4"><UsageBar label=" " value={r.disk} /></td>
                  <td className="w-28 px-4"><Sparkline data={series.byId[r.id] || []} height={30} color="var(--s-cpu)" /></td>
                  <td className="px-4 text-xs text-ink-2 tnum">{rate(r.networkIn)}</td>
                  <td className="px-4 text-xs text-ink-2 tnum">{money(r.hourlyCost * 730, 0)}</td>
                  <td className="px-4"><ResourceActions resource={r} compact /></td>
                </motion.tr>
              ))}
            </tbody>
          </table>
        </Card>
      )}
    </div>
  )
}

function Select({ value, onChange, options, label }) {
  return (
    <select value={value} onChange={(e) => onChange(e.target.value)}
      className="rounded-xl border border-line bg-surface-2 px-3 py-2 text-sm text-ink-2 outline-none focus:border-accent">
      {options.map((o) => <option key={o} value={o}>{label(o)}</option>)}
    </select>
  )
}

function Th({ children, sortKey, sort, setSort }) {
  if (!sortKey) return <th className="px-4 py-3 font-medium">{children}</th>
  const active = sort.key === sortKey
  return (
    <th className="px-4 py-3 font-medium">
      <button onClick={() => setSort({ key: sortKey, dir: active ? -sort.dir : -1 })} className={`inline-flex items-center gap-1 hover:text-ink ${active ? 'text-ink' : ''}`}>
        {children}{active && (sort.dir === 1 ? ' ↑' : ' ↓')}
      </button>
    </th>
  )
}
