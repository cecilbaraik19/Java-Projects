import { useCallback, useEffect, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { AnimatePresence, motion } from 'framer-motion'
import { AlertTriangle, Bell, Check, CheckCircle2, Mail, Plus, Send, Trash2, X } from 'lucide-react'
import api, { errorMessage } from '../api/client'
import { useAuth } from '../context/AuthContext'
import { useLive } from '../context/LiveContext'
import { Button, Card, EmptyState, Modal, SeverityBadge, Tabs } from '../components/ui'
import { ago } from '../utils/format'

const METRICS = { CPU: 'CPU %', MEMORY: 'Memory %', DISK: 'Disk used %', NETWORK_IN: 'Network in KB/s', NETWORK_OUT: 'Network out KB/s' }

export default function Alerts() {
  const [tab, setTab] = useState('active')
  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-2xl font-bold tracking-tight">Alerts</h1>
          <p className="mt-1 text-sm text-muted">Alerts fire when a resource breaks a rule. New ones appear instantly.</p>
        </div>
        <Tabs id="alerts" value={tab} onChange={setTab}
          options={[{ value: 'active', label: 'Active' }, { value: 'all', label: 'History' }, { value: 'rules', label: 'Rules' }, { value: 'email', label: 'Email' }]} />
      </div>
      {tab === 'rules' ? <Rules /> : tab === 'email' ? <EmailSettings /> : <AlertList mode={tab} />}
    </div>
  )
}

function AlertList({ mode }) {
  const { onAlert } = useLive()
  const navigate = useNavigate()
  const [alerts, setAlerts] = useState([])
  const [severity, setSeverity] = useState('ALL')
  const [loaded, setLoaded] = useState(false)

  const load = useCallback(() => {
    const req = mode === 'active' ? api.get('/alerts/active') : api.get('/alerts', { params: { limit: 200 } })
    req.then((r) => { setAlerts(r.data); setLoaded(true) }).catch(() => setLoaded(true))
  }, [mode])

  useEffect(() => { load(); return onAlert(load) }, [load, onAlert])

  const ack = async (id) => {
    setAlerts((list) => mode === 'active' ? list.filter((a) => a.id !== id) : list.map((a) => a.id === id ? { ...a, acknowledged: true } : a))
    await api.post(`/alerts/${id}/ack`).catch(load)
  }
  const ackAll = async () => { await api.post('/alerts/ack-all'); load() }

  const shown = useMemo(() => alerts.filter((a) => severity === 'ALL' || a.severity === severity), [alerts, severity])

  return (
    <Card className="!p-0">
      <div className="flex flex-wrap items-center justify-between gap-2 border-b border-line p-4">
        <Tabs id="sev" value={severity} onChange={setSeverity}
          options={[{ value: 'ALL', label: 'All' }, { value: 'CRITICAL', label: 'Critical' }, { value: 'WARNING', label: 'Warning' }, { value: 'INFO', label: 'Info' }]} />
        {mode === 'active' && alerts.length > 0 && <Button onClick={ackAll}><CheckCircle2 size={15} /> Acknowledge all</Button>}
      </div>
      {loaded && shown.length === 0 ? (
        <EmptyState icon={mode === 'active' ? CheckCircle2 : Bell} title={mode === 'active' ? 'All clear' : 'No alerts yet'}
          text={mode === 'active' ? 'No unacknowledged alerts right now.' : 'Alerts will show here as they fire.'} />
      ) : (
        <ul>
          <AnimatePresence initial={false}>
            {shown.map((a) => (
              <motion.li key={a.id} layout initial={{ opacity: 0, height: 0 }} animate={{ opacity: 1, height: 'auto' }}
                exit={{ opacity: 0, x: 40, height: 0 }} transition={{ duration: 0.25 }}
                className="border-b border-line last:border-0">
                <div className={`flex flex-wrap items-center gap-3 px-4 py-3 ${a.acknowledged ? 'opacity-55' : ''}`}>
                  <SeverityBadge severity={a.severity} />
                  <button className="min-w-0 flex-1 text-left" onClick={() => a.resourceId && navigate(`/resources/${a.resourceId}`)}>
                    <p className="text-sm font-medium hover:text-accent">{a.message}</p>
                    <p className="text-xs text-muted">
                      {a.ruleName} · {new Date(a.createdAt).toLocaleString()} ({ago(a.createdAt)})
                      {a.acknowledged && ` · acknowledged by ${a.acknowledgedBy}`}
                    </p>
                  </button>
                  {!a.acknowledged && <Button variant="ghost" onClick={() => ack(a.id)}><Check size={15} /> Acknowledge</Button>}
                </div>
              </motion.li>
            ))}
          </AnimatePresence>
        </ul>
      )}
    </Card>
  )
}

const EMPTY_RULE = { name: '', metric: 'CPU', operator: '>', threshold: 80, resourceId: '', severity: 'WARNING', enabled: true }

function Rules() {
  const { isAdmin } = useAuth()
  const { resources } = useLive()
  const [rules, setRules] = useState([])
  const [editing, setEditing] = useState(null)
  const [error, setError] = useState('')

  const load = () => api.get('/alerts/rules').then((r) => setRules(r.data)).catch(() => {})
  useEffect(() => { load() }, [])

  const toBody = (r) => ({ ...r, threshold: Number(r.threshold), resourceId: r.resourceId === '' || r.resourceId == null ? null : Number(r.resourceId) })

  const save = async () => {
    setError('')
    try {
      if (editing.id) await api.put(`/alerts/rules/${editing.id}`, toBody(editing))
      else await api.post('/alerts/rules', toBody(editing))
      setEditing(null)
      load()
    } catch (err) { setError(errorMessage(err)) }
  }

  const toggle = async (rule) => {
    setRules((list) => list.map((x) => x.id === rule.id ? { ...x, enabled: !x.enabled } : x))
    await api.put(`/alerts/rules/${rule.id}`, toBody({ ...rule, enabled: !rule.enabled })).catch(load)
  }

  const remove = async (rule) => {
    setRules((list) => list.filter((x) => x.id !== rule.id))
    await api.delete(`/alerts/rules/${rule.id}`).catch(load)
  }

  const resourceName = (id) => id == null ? 'All resources' : resources.find((r) => r.id === id)?.name || `#${id}`
  const isPercent = ['CPU', 'MEMORY', 'DISK'].includes(editing?.metric)

  return (
    <>
      <Card className="!p-0">
        <div className="flex items-center justify-between border-b border-line p-4">
          <p className="text-sm text-muted">{rules.length} rules · {isAdmin ? 'click a rule to edit' : 'read-only for viewers'}</p>
          {isAdmin && <Button variant="primary" onClick={() => { setError(''); setEditing({ ...EMPTY_RULE }) }}><Plus size={15} /> New rule</Button>}
        </div>
        <ul>
          <AnimatePresence initial={false}>
            {rules.map((rule) => (
              <motion.li key={rule.id} layout exit={{ opacity: 0, height: 0 }} className="border-b border-line last:border-0">
                <div className={`flex flex-wrap items-center gap-4 px-4 py-3 ${isAdmin ? 'cursor-pointer hover:bg-surface-2' : ''}`}
                  onClick={() => isAdmin && (setError(''), setEditing({ ...rule, resourceId: rule.resourceId ?? '' }))}>
                  <Switch on={rule.enabled} disabled={!isAdmin} onChange={() => toggle(rule)} />
                  <div className="min-w-0 flex-1">
                    <p className="text-sm font-medium">{rule.name}</p>
                    <p className="text-xs text-muted tnum">
                      {METRICS[rule.metric]} {rule.operator} {rule.threshold} · {resourceName(rule.resourceId)}
                    </p>
                  </div>
                  <SeverityBadge severity={rule.severity} />
                  {isAdmin && (
                    <button onClick={(e) => { e.stopPropagation(); remove(rule) }} className="rounded-lg p-1.5 text-muted hover:bg-surface-2 hover:text-[var(--crit)]" aria-label="Delete rule">
                      <Trash2 size={16} />
                    </button>
                  )}
                </div>
              </motion.li>
            ))}
          </AnimatePresence>
        </ul>
      </Card>

      <Modal open={!!editing} onClose={() => setEditing(null)} title={editing?.id ? 'Edit rule' : 'New alert rule'}>
        {editing && (
          <div className="space-y-4">
            <Field label="Name">
              <input value={editing.name} onChange={(e) => setEditing({ ...editing, name: e.target.value })} placeholder="e.g. High CPU on web servers" className={inputCls} />
            </Field>
            <div className="grid grid-cols-3 gap-3">
              <Field label="Metric" className="col-span-2">
                <select value={editing.metric} onChange={(e) => setEditing({ ...editing, metric: e.target.value })} className={inputCls}>
                  {Object.entries(METRICS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
                </select>
              </Field>
              <Field label="Condition">
                <select value={editing.operator} onChange={(e) => setEditing({ ...editing, operator: e.target.value })} className={inputCls}>
                  <option value=">">above</option><option value="<">below</option>
                </select>
              </Field>
            </div>
            <Field label={`Threshold: ${editing.threshold}${isPercent ? '%' : ' KB/s'}`}>
              {isPercent ? (
                <input type="range" min="0" max="100" value={editing.threshold} onChange={(e) => setEditing({ ...editing, threshold: e.target.value })} className="w-full accent-[var(--accent)]" />
              ) : (
                <input type="number" min="0" value={editing.threshold} onChange={(e) => setEditing({ ...editing, threshold: e.target.value })} className={inputCls} />
              )}
            </Field>
            <div className="grid grid-cols-2 gap-3">
              <Field label="Applies to">
                <select value={editing.resourceId} onChange={(e) => setEditing({ ...editing, resourceId: e.target.value })} className={inputCls}>
                  <option value="">All resources</option>
                  {resources.map((r) => <option key={r.id} value={r.id}>{r.name}</option>)}
                </select>
              </Field>
              <Field label="Severity">
                <select value={editing.severity} onChange={(e) => setEditing({ ...editing, severity: e.target.value })} className={inputCls}>
                  <option value="INFO">Info</option><option value="WARNING">Warning</option><option value="CRITICAL">Critical</option>
                </select>
              </Field>
            </div>
            {error && <p className="text-xs font-medium text-[var(--crit)]">{error}</p>}
            <div className="flex justify-end gap-2 pt-2">
              <Button onClick={() => setEditing(null)}>Cancel</Button>
              <Button variant="primary" onClick={save} disabled={!editing.name.trim()}>Save rule</Button>
            </div>
          </div>
        )}
      </Modal>
    </>
  )
}

const EMAIL_RE = /^[^@\s]+@[^@\s]+\.[^@\s]+$/

function EmailSettings() {
  const { isAdmin } = useAuth()
  const [cfg, setCfg] = useState(null)
  const [form, setForm] = useState(null)
  const [draft, setDraft] = useState('')
  const [busy, setBusy] = useState('')
  const [msg, setMsg] = useState(null)

  const apply = (data) => { setCfg(data); setForm({ enabled: data.enabled, recipients: data.recipients, minSeverity: data.minSeverity }) }
  useEffect(() => { api.get('/settings/email').then((r) => apply(r.data)).catch((e) => setMsg({ ok: false, text: errorMessage(e) })) }, [])

  if (!cfg || !form) return <Card><EmptyState icon={Mail} title="Loading email settings…" /></Card>

  const dirty = form.enabled !== cfg.enabled || form.minSeverity !== cfg.minSeverity || form.recipients.join(',') !== cfg.recipients.join(',')
  const draftValid = EMAIL_RE.test(draft.trim())

  const addRecipient = () => {
    const v = draft.trim()
    if (!EMAIL_RE.test(v) || form.recipients.includes(v) || form.recipients.length >= 5) return
    setForm({ ...form, recipients: [...form.recipients, v] })
    setDraft('')
  }

  const save = async () => {
    setBusy('save'); setMsg(null)
    try {
      const r = await api.put('/settings/email', form)
      apply(r.data); setMsg({ ok: true, text: 'Settings saved' })
    } catch (e) { setMsg({ ok: false, text: errorMessage(e) }) } finally { setBusy('') }
  }

  const test = async () => {
    setBusy('test'); setMsg(null)
    try {
      const r = await api.post('/settings/email/test')
      apply(r.data); setMsg({ ok: true, text: `Test email sent to ${r.data.recipients.join(', ')}. Check the inbox (and spam).` })
    } catch (e) { setMsg({ ok: false, text: errorMessage(e) }) } finally { setBusy('') }
  }

  return (
    <div className="grid grid-cols-1 gap-4 xl:grid-cols-3">
      <Card className="xl:col-span-2">
        <div className="mb-5 flex items-start justify-between gap-4">
          <div className="flex items-center gap-3">
            <span className="flex h-10 w-10 items-center justify-center rounded-xl bg-surface-2 text-accent"><Mail size={18} /></span>
            <div>
              <h2 className="text-[15px] font-semibold">Email alerts</h2>
              <p className="text-xs text-muted">Get an email when an alert fires, even when the dashboard is closed</p>
            </div>
          </div>
          <Switch on={form.enabled} disabled={!isAdmin || !cfg.configured} onChange={() => setForm({ ...form, enabled: !form.enabled })} />
        </div>

        {!cfg.configured && (
          <div className="mb-5 flex gap-3 rounded-xl border border-line bg-surface-2 p-4 text-sm">
            <AlertTriangle size={18} className="mt-0.5 shrink-0" style={{ color: 'var(--warn)' }} />
            <div>
              <p className="font-medium">Email is not set up on the server yet</p>
              <p className="mt-1 text-xs text-muted">
                In IntelliJ, open the run configuration and add the environment variables <code className="rounded bg-surface px-1">MAIL_USERNAME</code> (your Gmail address)
                and <code className="rounded bg-surface px-1">MAIL_PASSWORD</code> (a 16-character Gmail App password), then restart the backend.
              </p>
            </div>
          </div>
        )}

        <fieldset disabled={!isAdmin} className="space-y-5">
          <Field label="Send to (up to 5 addresses)">
            <div className="flex flex-wrap gap-2">
              {form.recipients.map((r) => (
                <span key={r} className="inline-flex items-center gap-1.5 rounded-lg border border-line bg-surface-2 px-2.5 py-1 text-sm">
                  {r}
                  {isAdmin && (
                    <button type="button" aria-label={`Remove ${r}`} onClick={() => setForm({ ...form, recipients: form.recipients.filter((x) => x !== r) })}
                      className="text-muted hover:text-[var(--crit)]"><X size={14} /></button>
                  )}
                </span>
              ))}
              {form.recipients.length === 0 && <span className="text-xs text-muted">No recipients yet</span>}
            </div>
            {isAdmin && form.recipients.length < 5 && (
              <div className="mt-2 flex gap-2">
                <input type="email" value={draft} onChange={(e) => setDraft(e.target.value)} placeholder="name@example.com"
                  onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); addRecipient() } }} className={inputCls} />
                <Button type="button" onClick={addRecipient} disabled={!draftValid}><Plus size={15} /> Add</Button>
              </div>
            )}
          </Field>

          <Field label="Which alerts">
            <Tabs id="email-sev" value={form.minSeverity} onChange={(v) => isAdmin && setForm({ ...form, minSeverity: v })}
              options={[{ value: 'CRITICAL', label: 'Critical only' }, { value: 'WARNING', label: 'Warning and critical' }, { value: 'INFO', label: 'Everything' }]} />
          </Field>
        </fieldset>

        {msg && (
          <p className="mt-5 flex items-center gap-2 text-sm" style={{ color: msg.ok ? 'var(--good-ink)' : 'var(--crit)' }}>
            {msg.ok ? <CheckCircle2 size={16} /> : <AlertTriangle size={16} />} {msg.text}
          </p>
        )}

        {isAdmin ? (
          <div className="mt-5 flex flex-wrap justify-end gap-2 border-t border-line pt-5">
            <Button onClick={test} disabled={!cfg.configured || !!busy || dirty} title={dirty ? 'Save your changes first' : undefined}>
              <Send size={15} /> {busy === 'test' ? 'Sending…' : 'Send test email'}
            </Button>
            <Button variant="primary" onClick={save} disabled={!dirty || !!busy}>{busy === 'save' ? 'Saving…' : 'Save settings'}</Button>
          </div>
        ) : (
          <p className="mt-5 text-xs text-muted">Only admins can change email settings.</p>
        )}
      </Card>

      <Card>
        <h2 className="mb-4 text-[15px] font-semibold">Status</h2>
        <dl className="space-y-3 text-sm">
          <StatusRow label="Server email setup" value={cfg.configured ? 'Ready' : 'Not set up'} good={cfg.configured} />
          <StatusRow label="Sending from" value={cfg.sender || '–'} />
          <StatusRow label="Email alerts" value={cfg.enabled ? 'On' : 'Off'} good={cfg.enabled} />
          <StatusRow label="Sent in the last hour" value={`${cfg.sentLastHour} of ${cfg.maxPerHour} max`} />
          <StatusRow label="Last email" value={cfg.lastSentAt ? ago(new Date(cfg.lastSentAt).toISOString()) : 'Never'} />
        </dl>
        {cfg.lastError && (
          <div className="mt-4 rounded-xl border border-line bg-surface-2 p-3 text-xs">
            <p className="mb-1 font-medium" style={{ color: 'var(--crit)' }}>Last error</p>
            <p className="text-ink-2">{cfg.lastError}</p>
          </div>
        )}
        <p className="mt-4 text-xs text-muted">
          A limit of {cfg.maxPerHour} emails per hour stops a burst of alerts from flooding your inbox. Each rule repeats at most every 2 minutes per resource.
        </p>
      </Card>
    </div>
  )
}

function StatusRow({ label, value, good }) {
  return (
    <div className="flex items-center justify-between gap-3">
      <dt className="text-muted">{label}</dt>
      <dd className="truncate text-right font-medium" style={good === undefined ? undefined : { color: good ? 'var(--good-ink)' : 'var(--muted)' }}>{value}</dd>
    </div>
  )
}

const inputCls = 'w-full rounded-xl border border-line bg-surface-2 px-3 py-2 text-sm outline-none focus:border-accent'

function Field({ label, children, className = '' }) {
  return <label className={`block ${className}`}><span className="mb-1.5 block text-xs font-medium text-ink-2">{label}</span>{children}</label>
}

function Switch({ on, onChange, disabled }) {
  return (
    <button role="switch" aria-checked={on} disabled={disabled} onClick={(e) => { e.stopPropagation(); onChange() }}
      className={`relative h-6 w-11 shrink-0 rounded-full transition-colors disabled:cursor-not-allowed ${on ? 'bg-accent' : 'bg-[var(--axis)]'}`}>
      <motion.span layout transition={{ type: 'spring', stiffness: 600, damping: 35 }}
        className={`absolute top-0.5 h-5 w-5 rounded-full bg-white shadow ${on ? 'right-0.5' : 'left-0.5'}`} />
    </button>
  )
}
