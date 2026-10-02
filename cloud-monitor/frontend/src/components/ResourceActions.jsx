import { useState } from 'react'
import { Play, RotateCw, Square } from 'lucide-react'
import api, { errorMessage } from '../api/client'
import { useAuth } from '../context/AuthContext'
import { useLive } from '../context/LiveContext'
import { Button, Modal } from './ui'

/** Start / Stop / Restart buttons with a confirmation dialog. Shown to admins only. */
export default function ResourceActions({ resource, compact = false }) {
  const { isAdmin } = useAuth()
  const { patchResource } = useLive()
  const [confirm, setConfirm] = useState(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  if (!isAdmin || resource.type === 'LOCAL_HOST') return null
  const stopped = resource.status === 'STOPPED'

  const run = async () => {
    setBusy(true); setError('')
    try {
      const { data } = await api.post(`/resources/${resource.id}/action`, { action: confirm })
      patchResource(data)
      setConfirm(null)
    } catch (err) {
      setError(errorMessage(err))
    } finally {
      setBusy(false)
    }
  }

  const stop = (e, a) => { e.stopPropagation(); setError(''); setConfirm(a) }
  const size = compact ? 'px-2 py-1.5' : ''

  return (
    <div className="flex gap-2" onClick={(e) => e.stopPropagation()}>
      {stopped ? (
        <Button variant="primary" className={size} onClick={(e) => stop(e, 'START')} title="Start"><Play size={15} />{!compact && 'Start'}</Button>
      ) : (
        <>
          <Button className={size} onClick={(e) => stop(e, 'RESTART')} title="Restart"><RotateCw size={15} />{!compact && 'Restart'}</Button>
          <Button className={size} onClick={(e) => stop(e, 'STOP')} title="Stop"><Square size={15} />{!compact && 'Stop'}</Button>
        </>
      )}
      <Modal open={!!confirm} onClose={() => setConfirm(null)} title={`${confirm?.[0]}${confirm?.slice(1).toLowerCase()} ${resource.name}?`}>
        <p className="text-sm text-ink-2">
          {confirm === 'STOP' && 'The resource will stop serving traffic and stop costing money until you start it again.'}
          {confirm === 'START' && 'The resource will boot and start reporting metrics within a few seconds.'}
          {confirm === 'RESTART' && 'The resource will be rebooted. Load spikes will clear.'}
        </p>
        {error && <p className="mt-3 text-xs font-medium text-[var(--crit)]">{error}</p>}
        <div className="mt-6 flex justify-end gap-2">
          <Button onClick={() => setConfirm(null)}>Cancel</Button>
          <Button variant={confirm === 'STOP' ? 'danger' : 'primary'} onClick={run} disabled={busy}>
            {busy ? 'Working…' : 'Confirm'}
          </Button>
        </div>
      </Modal>
    </div>
  )
}
