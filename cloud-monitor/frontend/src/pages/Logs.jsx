import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { ArrowDown, Download, Eraser, Pause, Play, ScrollText, Search, X } from 'lucide-react'
import api, { downloadFile, errorMessage } from '../api/client'
import { useLive } from '../context/LiveContext'
import { Button, Card, EmptyState, Tabs } from '../components/ui'

const LEVELS = ['DEBUG', 'INFO', 'WARN', 'ERROR']
const LEVEL_STYLE = {
  DEBUG: { color: 'var(--muted)', label: 'DEBUG' },
  INFO: { color: 'var(--accent)', label: 'INFO' },
  WARN: { color: 'var(--warn)', label: 'WARN' },
  ERROR: { color: 'var(--crit)', label: 'ERROR' },
}
const MAX_LINES = 1500

const time = (ms) => {
  const d = new Date(ms)
  return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false }) +
    '.' + String(d.getMilliseconds()).padStart(3, '0')
}

export default function Logs() {
  const { resources, onLog } = useLive()
  const [params, setParams] = useSearchParams()
  const resourceId = params.get('resource') || ''
  const [level, setLevel] = useState('DEBUG')
  const [query, setQuery] = useState('')
  const [search, setSearch] = useState('')
  const [lines, setLines] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [following, setFollowing] = useState(true)
  const [pending, setPending] = useState(0)
  const [atBottom, setAtBottom] = useState(true)
  const boxRef = useRef(null)
  const filtersRef = useRef({ resourceId, level, search, following })
  const queuedRef = useRef([])

  useEffect(() => { filtersRef.current = { resourceId, level, search, following } }, [resourceId, level, search, following])

  // Debounce the search box so we don't hit the server on every key press
  useEffect(() => {
    const t = setTimeout(() => setSearch(query.trim()), 300)
    return () => clearTimeout(t)
  }, [query])

  // Load matching lines whenever a filter changes
  useEffect(() => {
    let cancelled = false
    setLoading(true)
    api.get('/logs', { params: { resourceId: resourceId || undefined, level, q: search || undefined, limit: 500 } })
      .then((r) => { if (!cancelled) { setLines(r.data); setError(''); queuedRef.current = []; setPending(0) } })
      .catch((e) => { if (!cancelled) setError(errorMessage(e, 'Could not load logs')) })
      .finally(() => { if (!cancelled) setLoading(false) })
    return () => { cancelled = true }
  }, [resourceId, level, search])

  // Live lines from the WebSocket
  useEffect(() => onLog((line) => {
    const f = filtersRef.current
    if (f.resourceId && String(line.resourceId) !== f.resourceId) return
    if (LEVELS.indexOf(line.level) < LEVELS.indexOf(f.level)) return
    if (f.search) {
      const hay = `${line.message} ${line.resourceName || ''} ${line.source}`.toLowerCase()
      if (!hay.includes(f.search.toLowerCase())) return
    }
    if (!f.following) {
      queuedRef.current.push(line)
      setPending((n) => n + 1)
      return
    }
    setLines((prev) => [...prev, { ...line, fresh: true }].slice(-MAX_LINES))
  }), [onLog])

  // Keep the newest line in view, unless the user scrolled up to read
  useEffect(() => {
    const box = boxRef.current
    if (box && atBottom) box.scrollTop = box.scrollHeight
  }, [lines, atBottom])

  const onScroll = () => {
    const box = boxRef.current
    if (!box) return
    setAtBottom(box.scrollHeight - box.scrollTop - box.clientHeight < 40)
  }

  const resume = () => {
    setLines((prev) => [...prev, ...queuedRef.current.map((l) => ({ ...l, fresh: true }))].slice(-MAX_LINES))
    queuedRef.current = []
    setPending(0)
    setFollowing(true)
    setAtBottom(true)
  }

  const jumpToLatest = () => {
    const box = boxRef.current
    if (box) box.scrollTop = box.scrollHeight
    setAtBottom(true)
  }

  const setResource = (id) => {
    const next = new URLSearchParams(params)
    if (id) next.set('resource', id)
    else next.delete('resource')
    setParams(next, { replace: true })
  }

  const download = useCallback(() => {
    const qs = new URLSearchParams()
    if (resourceId) qs.set('resourceId', resourceId)
    qs.set('level', level)
    if (search) qs.set('q', search)
    downloadFile(`/logs/download?${qs}`, 'cloudpulse-logs.log').catch((e) => setError(errorMessage(e, 'Download failed')))
  }, [resourceId, level, search])

  const counts = useMemo(() => {
    const c = { DEBUG: 0, INFO: 0, WARN: 0, ERROR: 0 }
    for (const l of lines) c[l.level] = (c[l.level] || 0) + 1
    return c
  }, [lines])

  const sortedResources = useMemo(() => [...resources].sort((a, b) => a.name.localeCompare(b.name)), [resources])
  const selected = resources.find((r) => String(r.id) === resourceId)

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-2xl font-bold tracking-tight">Logs</h1>
          <p className="mt-1 text-sm text-muted">
            {selected ? <>Live output from <b className="text-ink-2">{selected.name}</b></> : 'Live output from every resource'} · last 5,000 lines are kept
          </p>
        </div>
        <div className="flex flex-wrap gap-2">
          <Button onClick={following ? () => setFollowing(false) : resume} title={following ? 'Pause the live stream' : 'Resume the live stream'}>
            {following ? <><Pause size={15} /> Pause</> : <><Play size={15} /> Resume{pending > 0 && <span className="tnum"> ({pending} new)</span>}</>}
          </Button>
          <Button onClick={() => { setLines([]); queuedRef.current = []; setPending(0) }} title="Clear the view (logs stay on the server)">
            <Eraser size={15} /> Clear
          </Button>
          <Button onClick={download}><Download size={15} /> Download .log</Button>
        </div>
      </div>

      {/* ---------- filters ---------- */}
      <Card className="!p-4">
        <div className="flex flex-wrap items-center gap-3">
          <select value={resourceId} onChange={(e) => setResource(e.target.value)} aria-label="Resource"
            className="rounded-xl border border-line bg-surface-2 px-3 py-2 text-sm text-ink-2 outline-none focus:border-accent">
            <option value="">All resources</option>
            {sortedResources.map((r) => <option key={r.id} value={r.id}>{r.name}</option>)}
          </select>
          <Tabs id="log-level" value={level} onChange={setLevel}
            options={[{ value: 'DEBUG', label: 'All' }, { value: 'INFO', label: 'Info +' }, { value: 'WARN', label: 'Warn +' }, { value: 'ERROR', label: 'Errors' }]} />
          <div className="flex min-w-[220px] flex-1 items-center gap-2 rounded-xl border border-line bg-surface-2 px-3 focus-within:border-accent">
            <Search size={15} className="text-muted" />
            <input value={query} onChange={(e) => setQuery(e.target.value)} placeholder="Search messages, e.g. timeout, 502, deadlock"
              className="w-full bg-transparent py-2 text-sm outline-none" aria-label="Search logs" />
            {query && <button onClick={() => setQuery('')} className="text-muted hover:text-ink" aria-label="Clear search"><X size={15} /></button>}
          </div>
          <div className="flex gap-3 text-xs text-muted">
            {LEVELS.map((l) => (
              <span key={l} className="flex items-center gap-1.5">
                <span className="h-2 w-2 rounded-full" style={{ background: LEVEL_STYLE[l].color }} />
                <span className="tnum">{counts[l]}</span> {l.toLowerCase()}
              </span>
            ))}
          </div>
        </div>
      </Card>

      {/* ---------- terminal ---------- */}
      <Card className="relative !p-0 overflow-hidden">
        <div className="flex items-center justify-between border-b border-line px-4 py-2 text-xs text-muted">
          <span className="flex items-center gap-2">
            <span className={`h-2 w-2 rounded-full ${following ? 'live-dot bg-[var(--good)]' : 'bg-[var(--warn)]'}`} />
            {following ? 'Streaming' : 'Paused'} · {lines.length} lines shown
          </span>
          <span className="hidden sm:inline">Scroll up to read; new lines keep arriving below</span>
        </div>
        <div ref={boxRef} onScroll={onScroll} role="log" aria-live="off"
          className="h-[calc(100vh-330px)] min-h-[360px] overflow-y-auto bg-page px-2 py-2 font-mono text-[12.5px] leading-relaxed">
          {error ? (
            <EmptyState icon={ScrollText} title="Couldn't load logs" text={error} />
          ) : !loading && lines.length === 0 ? (
            <EmptyState icon={ScrollText} title="No log lines match" text="Try another resource, level or search word." />
          ) : (
            lines.map((l) => <LogLine key={l.id} line={l} search={search} />)
          )}
        </div>
        {!atBottom && lines.length > 0 && (
          <button onClick={jumpToLatest}
            className="absolute right-5 bottom-5 inline-flex items-center gap-1.5 rounded-full bg-accent px-3.5 py-2 text-xs font-semibold text-white shadow-lg hover:brightness-110">
            <ArrowDown size={14} /> Jump to latest
          </button>
        )}
      </Card>
    </div>
  )
}

function LogLine({ line, search }) {
  const s = LEVEL_STYLE[line.level] || LEVEL_STYLE.INFO
  const strong = line.level === 'ERROR' || line.level === 'WARN'
  return (
    <div className={`flex flex-wrap gap-x-3 rounded-md px-2 py-0.5 hover:bg-surface-2 ${line.fresh ? 'log-new' : ''}`}
      style={line.level === 'ERROR' ? { background: 'color-mix(in srgb, var(--crit) 8%, transparent)' } : undefined}>
      <span className="shrink-0 text-muted tnum">{time(line.timestamp)}</span>
      <span className="w-12 shrink-0 font-semibold" style={{ color: s.color }}>{s.label}</span>
      {line.resourceId ? (
        <Link to={`/resources/${line.resourceId}`} className="shrink-0 text-accent hover:underline">{line.resourceName}</Link>
      ) : (
        <span className="shrink-0 text-ink-2">cloudpulse</span>
      )}
      <span className="shrink-0 text-muted">{line.source}:</span>
      <span className={`min-w-0 basis-full break-words sm:basis-0 sm:flex-1 ${strong ? 'text-ink' : 'text-ink-2'}`}><Highlight text={line.message} term={search} /></span>
    </div>
  )
}

function Highlight({ text, term }) {
  if (!term) return text
  const i = text.toLowerCase().indexOf(term.toLowerCase())
  if (i < 0) return text
  return (
    <>
      {text.slice(0, i)}
      <mark className="rounded bg-[var(--warn)] px-0.5 text-black">{text.slice(i, i + term.length)}</mark>
      {text.slice(i + term.length)}
    </>
  )
}
