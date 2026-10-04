import { useEffect, useRef, useState } from 'react'
import { Lock, Plus, FileText, Download, Trash2, Pencil, Eye, EyeOff, Upload, ShieldCheck } from 'lucide-react'
import { api } from '../api.js'
import { Empty, Modal, Mono, Spinner } from '../components/ui.jsx'
import { useToast } from '../components/Toast.jsx'
import { fmtBytes, fmtDate, humanize } from '../util.js'

const CATS = ['PASSPORT', 'NATIONAL_ID', 'PAN_CARD', 'DRIVING_LICENSE', 'VOTER_ID', 'BIRTH_CERTIFICATE', 'EDUCATION', 'MEDICAL', 'FINANCE', 'OTHER']

function Editor({ item, onClose, onSaved }) {
  const toast = useToast()
  const [f, setF] = useState({ title: item?.title || '', category: item?.category || 'PASSPORT', documentNumber: item?.documentNumber || '',
    notes: item?.notes || '', expiryDate: item?.expiryDate || '' })
  const [file, setFile] = useState(null)
  const [busy, setBusy] = useState(false)
  const input = useRef()
  const set = (k) => (e) => setF({ ...f, [k]: e.target.value })

  async function submit(e) {
    e.preventDefault()
    if (file && file.size > 5 * 1024 * 1024) { toast('File is larger than 5 MB', 'bad'); return }
    setBusy(true)
    const form = new FormData()
    Object.entries(f).forEach(([k, v]) => form.append(k, v))
    if (file) form.append('file', file)
    try {
      await api(item ? `/vault/${item.id}` : '/vault', { method: 'POST', form })
      toast(item ? 'Document updated' : 'Document encrypted and saved')
      onSaved()
    } catch (ex) { toast(ex.message, 'bad') } finally { setBusy(false) }
  }

  return (
    <Modal title={item ? 'Edit document' : 'Add a document'} onClose={onClose}>
      <form onSubmit={submit}>
        <label>Title<input required maxLength={120} value={f.title} onChange={set('title')} placeholder="e.g. Passport" /></label>
        <div className="row2">
          <label>Category<select value={f.category} onChange={set('category')}>{CATS.map(c => <option key={c} value={c}>{humanize(c)}</option>)}</select></label>
          <label>Expiry date<input type="date" value={f.expiryDate} onChange={set('expiryDate')} /></label>
        </div>
        <label>Document number<input maxLength={100} value={f.documentNumber} onChange={set('documentNumber')} autoComplete="off" /></label>
        <label>Notes<textarea maxLength={2000} rows={3} value={f.notes} onChange={set('notes')} /></label>
        <div className={`drop ${file ? 'has' : ''}`} onClick={() => input.current.click()}
             onDragOver={e => e.preventDefault()} onDrop={e => { e.preventDefault(); setFile(e.dataTransfer.files[0]) }}>
          <Upload size={20} />
          <span>{file ? `${file.name} (${fmtBytes(file.size)})` : item?.hasFile ? `Replace ${item.fileName}` : 'Drop a PDF / image here or click to choose (max 5 MB)'}</span>
          <input ref={input} type="file" hidden accept="application/pdf,image/png,image/jpeg,image/webp" onChange={e => setFile(e.target.files[0])} />
        </div>
        <p className="hint"><ShieldCheck size={13} /> Encrypted with AES-256-GCM using your personal key before it's stored.</p>
        <button className="btn primary full" disabled={busy}>{busy ? 'Encrypting…' : 'Save'}</button>
      </form>
    </Modal>
  )
}

export default function Vault() {
  const toast = useToast()
  const [list, setList] = useState(null)
  const [editing, setEditing] = useState(null)
  const [viewing, setViewing] = useState(null)
  const [showNo, setShowNo] = useState(false)
  const load = () => api('/vault').then(setList).catch(e => toast(e.message, 'bad'))
  useEffect(() => { load() }, []) // eslint-disable-line react-hooks/exhaustive-deps

  const open = async (id) => { setShowNo(false); try { setViewing(await api(`/vault/${id}`)) } catch (e) { toast(e.message, 'bad') } }
  const edit = async (id) => { try { setEditing(await api(`/vault/${id}`)) } catch (e) { toast(e.message, 'bad') } }

  async function download(v) {
    try {
      const res = await api(`/vault/${v.id}/file`, { raw: true })
      const url = URL.createObjectURL(await res.blob())
      const a = Object.assign(document.createElement('a'), { href: url, download: v.fileName })
      a.click(); setTimeout(() => URL.revokeObjectURL(url), 2000)
    } catch (e) { toast(e.message, 'bad') }
  }

  async function remove(v) {
    if (!window.confirm(`Delete "${v.title}" permanently?`)) return
    try { await api(`/vault/${v.id}`, { method: 'DELETE' }); toast('Deleted'); setViewing(null); load() } catch (e) { toast(e.message, 'bad') }
  }

  if (!list) return <Spinner />
  const expired = (d) => d && new Date(d) < new Date()
  return (
    <div className="page">
      <div className="page-head">
        <div><h1>Document vault</h1><p className="muted">Scans and numbers of your physical IDs, encrypted with a key only your account can unwrap.</p></div>
        <button className="btn primary" onClick={() => setEditing({})}><Plus size={16} />Add document</button>
      </div>
      {list.length === 0 ? <Empty icon={Lock} title="Your vault is empty">Add your passport, PAN card or certificates.</Empty> : (
        <div className="vault-grid">
          {list.map(v => (
            <button key={v.id} className="card vault-item" onClick={() => open(v.id)}>
              <FileText size={22} />
              <div>
                <b>{v.title}</b>
                <small>{humanize(v.category)}{v.hasFile ? ` · ${fmtBytes(v.fileSize)}` : ''}</small>
              </div>
              {v.expiryDate && <span className={`pill ${expired(v.expiryDate) ? 'bad' : 'muted'}`}>{expired(v.expiryDate) ? 'Expired' : `Exp. ${fmtDate(v.expiryDate)}`}</span>}
            </button>
          ))}
        </div>
      )}

      {viewing && (
        <Modal title={viewing.title} onClose={() => setViewing(null)}>
          <dl className="kv">
            <dt>Category</dt><dd>{humanize(viewing.category)}</dd>
            <dt>Number</dt>
            <dd className="reveal">{viewing.documentNumber ? (showNo ? <Mono>{viewing.documentNumber}</Mono> : '••••••••') : '—'}
              {viewing.documentNumber && <button className="icon-btn" onClick={() => setShowNo(!showNo)} aria-label="Toggle number">{showNo ? <EyeOff size={15} /> : <Eye size={15} />}</button>}
            </dd>
            <dt>Expiry</dt><dd>{fmtDate(viewing.expiryDate)}</dd>
            <dt>Notes</dt><dd>{viewing.notes || '—'}</dd>
            {viewing.hasFile && <><dt>File</dt><dd>{viewing.fileName} ({fmtBytes(viewing.fileSize)})</dd>
              <dt>SHA-256</dt><dd><Mono className="wrap">{viewing.fileSha256}</Mono></dd></>}
            <dt>Updated</dt><dd>{fmtDate(viewing.updatedAt)}</dd>
          </dl>
          <div className="actions">
            {viewing.hasFile && <button className="btn" onClick={() => download(viewing)}><Download size={16} />Download</button>}
            <button className="btn ghost" onClick={() => { setViewing(null); edit(viewing.id) }}><Pencil size={16} />Edit</button>
            <button className="btn danger ghost" onClick={() => remove(viewing)}><Trash2 size={16} />Delete</button>
          </div>
        </Modal>
      )}
      {editing && <Editor item={editing.id ? editing : null} onClose={() => setEditing(null)} onSaved={() => { setEditing(null); load() }} />}
    </div>
  )
}
