import { useEffect, useRef, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { Search, Upload, FileJson } from 'lucide-react'
import { api } from '../api.js'
import VerificationReport from '../components/VerificationReport.jsx'

/** Public verification page — used by employers. No login needed. */
export default function Verify() {
  const { id } = useParams()
  const navigate = useNavigate()
  const [tab, setTab] = useState('id')
  const [input, setInput] = useState(id || '')
  const [result, setResult] = useState(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState(null)
  const [dragging, setDragging] = useState(false)
  const fileRef = useRef()

  const run = async (fn) => {
    setLoading(true); setError(null); setResult(null)
    try { setResult(await fn()) } catch (e) { setError(e.message) } finally { setLoading(false) }
  }

  useEffect(() => {
    if (id) { setInput(id); setTab('id'); run(() => api.verify(id)) }
  }, [id])

  const submitId = (e) => {
    e.preventDefault()
    const v = input.trim()
    if (v) navigate(`/verify/${encodeURIComponent(v)}`)
  }

  const handleFile = async (file) => {
    if (!file) return
    run(async () => {
      let doc
      try { doc = JSON.parse(await file.text()) } catch { throw new Error('That file is not valid JSON') }
      return api.verifyDocument(doc)
    })
  }

  return (
    <div className="page">
      <header className="page-head">
        <div><h1>Verify a credential</h1><p>Check any credential against the blockchain in seconds — scan its QR code, enter its ID, or upload the JSON file.</p></div>
      </header>

      <div className="card">
        <div className="tabs">
          <button className={tab === 'id' ? 'active' : ''} onClick={() => setTab('id')}><Search size={16} /> By credential ID</button>
          <button className={tab === 'file' ? 'active' : ''} onClick={() => setTab('file')}><FileJson size={16} /> Upload credential file</button>
        </div>

        {tab === 'id' ? (
          <form className="search" onSubmit={submitId}>
            <input value={input} onChange={(e) => setInput(e.target.value)} placeholder="CRED-2026-XXXXXXXXXX" />
            <button className="btn primary" disabled={loading}><Search size={16} /> Verify</button>
          </form>
        ) : (
          <div
            className={`dropzone ${dragging ? 'drag' : ''}`}
            onClick={() => fileRef.current.click()}
            onDragOver={(e) => { e.preventDefault(); setDragging(true) }}
            onDragLeave={() => setDragging(false)}
            onDrop={(e) => { e.preventDefault(); setDragging(false); handleFile(e.dataTransfer.files[0]) }}
          >
            <Upload size={28} />
            <p><strong>Drop the credential .json here</strong> or click to choose a file</p>
            <p className="muted small">The file is re-hashed and its signature checked against the issuer's on-chain public key.</p>
            <input ref={fileRef} type="file" accept=".json,application/json" hidden onChange={(e) => handleFile(e.target.files[0])} />
          </div>
        )}
      </div>

      {loading && <div className="loading">Checking the blockchain…</div>}
      {error && <div className="card error-card">{error}</div>}
      {result && <VerificationReport result={result} />}
    </div>
  )
}
