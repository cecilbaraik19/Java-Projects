import { useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { Upload, Download, Send, Pickaxe, LogIn, FileSpreadsheet, CheckCircle2, XCircle } from 'lucide-react'
import { api } from '../api.js'
import { useAuth } from '../auth.jsx'
import { useToast } from '../components/Toast.jsx'
import { parseCsv, toCsv, toRequests, downloadText, TEMPLATE } from '../csv.js'

/** Issue many credentials at once from a CSV file (Excel: File > Save As > CSV). */
export default function BulkIssue() {
  const { user } = useAuth()
  const toast = useToast()
  const fileRef = useRef()
  const [fileName, setFileName] = useState(null)
  const [items, setItems] = useState(null)
  const [error, setError] = useState(null)
  const [progress, setProgress] = useState(null) // { done, total }
  const [results, setResults] = useState(null)
  const [dragging, setDragging] = useState(false)

  if (user?.role !== 'ISSUER') {
    return (
      <div className="page narrow">
        <header className="page-head"><div><h1>Bulk issue</h1><p>Issue certificates for a whole class from one CSV file.</p></div></header>
        <div className="card form">
          <p>Only college staff can issue credentials.</p>
          {!user && <Link className="btn primary" to="/login" state={{ from: '/bulk' }}><LogIn size={16} /> Sign in</Link>}
        </div>
      </div>
    )
  }

  const load = async (file) => {
    if (!file) return
    setError(null); setItems(null); setResults(null); setFileName(file.name)
    if (!/\.(csv|txt)$/i.test(file.name)) {
      setError('Please upload a .csv file. In Excel use File → Save As → "CSV UTF-8 (Comma delimited)".')
      return
    }
    const parsed = toRequests(parseCsv(await file.text()))
    if (parsed.error) { setError(parsed.error); return }
    // flag duplicates inside the file
    const seen = new Map()
    parsed.items.forEach((it) => {
      const key = `${it.req.studentId.toLowerCase()}|${it.req.credentialType.toLowerCase()}|${it.req.program.toLowerCase()}`
      if (seen.has(key)) it.errors.push(`duplicate of row ${seen.get(key)}`)
      else seen.set(key, it.row)
    })
    setItems(parsed.items)
  }

  const valid = items?.filter((i) => i.errors.length === 0) || []
  const invalid = items ? items.length - valid.length : 0

  const issueAll = async () => {
    setProgress({ done: 0, total: valid.length })
    const out = []
    for (const it of valid) {
      try {
        const view = await api.issue(it.req)
        out.push({ ...it, ok: true, id: view.credential.credentialId })
      } catch (e) {
        out.push({ ...it, ok: false, message: e.message })
      }
      setProgress({ done: out.length, total: valid.length })
    }
    setResults(out)
    setProgress(null)
    const okCount = out.filter((r) => r.ok).length
    toast(`${okCount} of ${out.length} credentials issued — mine a block to confirm them`, okCount === out.length ? 'success' : 'error')
  }

  const mine = async () => {
    try { toast((await api.mine()).message, 'success') } catch (e) { toast(e.message, 'error') }
  }

  const downloadResults = () => {
    const rows = [['row', 'studentName', 'studentId', 'credentialType', 'status', 'credentialId', 'verifyLink', 'message']]
    results.forEach((r) => rows.push([r.row, r.req.studentName, r.req.studentId, r.req.credentialType,
      r.ok ? 'ISSUED' : 'FAILED', r.id || '', r.id ? `${window.location.origin}/verify/${r.id}` : '', r.message || '']))
    downloadText(toCsv(rows), `issued-credentials-${new Date().toISOString().slice(0, 10)}.csv`)
  }

  return (
    <div className="page">
      <header className="page-head">
        <div><h1>Bulk issue</h1><p>Issue certificates for a whole class from one CSV file · {user.institutionName}</p></div>
        <button className="btn" onClick={() => downloadText(toCsv(TEMPLATE), 'credentials-template.csv')}>
          <Download size={16} /> Download template
        </button>
      </header>

      <div className="card">
        <div
          className={`dropzone ${dragging ? 'drag' : ''}`}
          onClick={() => fileRef.current.click()}
          onDragOver={(e) => { e.preventDefault(); setDragging(true) }}
          onDragLeave={() => setDragging(false)}
          onDrop={(e) => { e.preventDefault(); setDragging(false); load(e.dataTransfer.files[0]) }}
        >
          <FileSpreadsheet size={30} />
          <p><strong>{fileName || 'Drop your CSV file here'}</strong> or click to choose</p>
          <p className="muted small">Columns: credentialType, studentName, studentId, program, major, grade, issueDate (yyyy-mm-dd or dd-mm-yyyy). Max 500 rows.</p>
          <p className="muted small">From Excel: File → Save As → <em>CSV UTF-8 (Comma delimited)</em>.</p>
          <input ref={fileRef} type="file" accept=".csv,text/csv" hidden onChange={(e) => { load(e.target.files[0]); e.target.value = '' }} />
        </div>
        {error && <p className="danger-text">{error}</p>}
      </div>

      {items && !results && (
        <section className="card">
          <div className="card-head">
            <h2>Preview · {items.length} rows</h2>
            <div className="actions">
              <span className="badge ok">{valid.length} ready</span>
              {invalid > 0 && <span className="badge bad">{invalid} with errors (skipped)</span>}
              <button className="btn primary" disabled={!valid.length || progress} onClick={issueAll}>
                <Send size={16} /> {progress ? `Issuing ${progress.done}/${progress.total}…` : `Sign & issue ${valid.length}`}
              </button>
            </div>
          </div>
          {progress && (
            <div className="progress"><div style={{ width: `${(progress.done / progress.total) * 100}%` }} /></div>
          )}
          <div className="table-scroll">
            <table className="table">
              <thead><tr><th>Row</th><th>Student</th><th>Credential</th><th>Grade</th><th>Issue date</th><th>Check</th></tr></thead>
              <tbody>
                {items.map((it) => (
                  <tr key={it.row}>
                    <td className="muted">{it.row}</td>
                    <td>{it.req.studentName || <span className="muted">—</span>}<div className="muted small">{it.req.studentId}</div></td>
                    <td>{it.req.credentialType}<div className="muted small">{it.req.program}{it.req.major ? ` · ${it.req.major}` : ''}</div></td>
                    <td>{it.req.grade}</td>
                    <td>{it.req.issueDate || <span className="muted">today</span>}</td>
                    <td>{it.errors.length === 0
                      ? <span className="ok-text"><CheckCircle2 size={14} /> OK</span>
                      : <span className="danger-text small"><XCircle size={14} /> {it.errors.join('; ')}</span>}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      )}

      {results && (
        <section className="card">
          <div className="card-head">
            <h2>Result · {results.filter((r) => r.ok).length} issued, {results.filter((r) => !r.ok).length} failed</h2>
            <div className="actions">
              <button className="btn primary" onClick={mine}><Pickaxe size={16} /> Mine block now</button>
              <button className="btn" onClick={downloadResults}><Download size={16} /> Download results CSV</button>
              <button className="btn ghost" onClick={() => { setItems(null); setResults(null); setFileName(null) }}><Upload size={16} /> Upload another</button>
            </div>
          </div>
          <div className="table-scroll">
            <table className="table">
              <thead><tr><th>Row</th><th>Student</th><th>Status</th><th>Credential ID</th></tr></thead>
              <tbody>
                {results.map((r) => (
                  <tr key={r.row}>
                    <td className="muted">{r.row}</td>
                    <td>{r.req.studentName}<div className="muted small">{r.req.studentId}</div></td>
                    <td>{r.ok ? <span className="badge ok">Issued</span> : <span className="badge bad" title={r.message}>Failed</span>}
                      {!r.ok && <div className="danger-text small">{r.message}</div>}</td>
                    <td>{r.id ? <Link className="mono small" to={`/credential/${r.id}`}>{r.id}</Link> : '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      )}
    </div>
  )
}
