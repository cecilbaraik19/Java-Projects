import { useEffect, useRef, useState } from 'react'
import { api } from './api.js'

const ACCEPT = '.pdf,.docx,.txt,.md,.csv,.json,.html'

const SAMPLE_QUESTIONS = [
  'Tell me everything',
  'What is this document about?',
  'What are the main rules or requirements?',
  'Are there any deadlines or dates?',
]

function formatSize(bytes) {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

function useTheme() {
  const [theme, setTheme] = useState(() => {
    try {
      const saved = localStorage.getItem('docqa-theme')
      if (saved) return saved
    } catch {
      /* storage blocked */
    }
    return window.matchMedia?.('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
  })
  useEffect(() => {
    document.documentElement.dataset.theme = theme
    try {
      localStorage.setItem('docqa-theme', theme)
    } catch {
      /* ignore */
    }
  }, [theme])
  return [theme, () => setTheme((t) => (t === 'dark' ? 'light' : 'dark'))]
}

/* ---------- icons (tiny inline SVGs) ---------- */
const Icon = {
  sun: (
    <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
      <circle cx="12" cy="12" r="4" />
      <path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4" />
    </svg>
  ),
  moon: (
    <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
      <path d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z" />
    </svg>
  ),
  upload: (
    <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
      <path d="M12 16V4M7 9l5-5 5 5M4 16v3a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-3" />
    </svg>
  ),
  trash: (
    <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
      <path d="M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3" />
    </svg>
  ),
  eye: (
    <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
      <path d="M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7S2 12 2 12z" />
      <circle cx="12" cy="12" r="3" />
    </svg>
  ),
  plus: (
    <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round">
      <path d="M12 5v14M5 12h14" />
    </svg>
  ),
  send: (
    <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M5 12h14M13 6l6 6-6 6" />
    </svg>
  ),
}

/* ---------- answer text with clickable [1] citations ---------- */
function AnswerText({ text, onCite, activeCite }) {
  const parts = text.split(/(\[\d+\])/g)
  return (
    <p className="answer-text">
      {parts.map((part, i) => {
        const m = part.match(/^\[(\d+)\]$/)
        if (!m) return <span key={i}>{part}</span>
        const n = Number(m[1])
        return (
          <button
            key={i}
            className={`cite ${activeCite === n ? 'active' : ''}`}
            onClick={() => onCite(n)}
            title={`Show source ${n}`}
          >
            {n}
          </button>
        )
      })}
    </p>
  )
}

function highlight(snippet, question) {
  const words = question
    .toLowerCase()
    .split(/[^\p{L}\p{N}]+/u)
    .filter((w) => w.length > 3)
    .map((w) => w.slice(0, Math.max(4, w.length - 3)))
  if (!words.length) return snippet
  const re = new RegExp(`(\\b(?:${words.map((w) => w.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('|')})\\w*)`, 'gi')
  return snippet.split(re).map((p, i) => (i % 2 === 1 ? <mark key={i}>{p}</mark> : p))
}

function AnswerCard({ item }) {
  const [openSource, setOpenSource] = useState(null)
  const toggle = (n) => setOpenSource((cur) => (cur === n ? null : n))
  const source = item.sources?.find((s) => s.number === openSource)

  return (
    <article className="qa">
      <div className="question">{item.question}</div>
      <div className="answer">
        {item.pending ? (
          <div className="thinking">
            <span /> <span /> <span /> Reading your documents…
          </div>
        ) : item.error ? (
          <p className="answer-error">{item.error}</p>
        ) : (
          <>
            <AnswerText text={item.answer} onCite={toggle} activeCite={openSource} />
            {item.note && <p className="note">{item.note}</p>}
            {source && (
              <blockquote className="source-open">
                <div className="source-head">
                  <span className="mono">[{source.number}]</span> {source.fileName}
                  {source.pageNumber ? ` · page ${source.pageNumber}` : ''}
                </div>
                <p>{highlight(source.snippet, item.question)}</p>
              </blockquote>
            )}
            <div className="meta">
              <span className={`chip conf-${item.confidence}`}>
                {item.confidence === 'none'
                  ? 'no match'
                  : item.confidence === 'summary'
                    ? 'summary'
                    : `${item.confidence} match`}
              </span>
              <span className="chip">{item.mode === 'claude' ? 'Claude AI' : 'Offline'}</span>
              <span className="chip mono">{item.durationMs} ms</span>
              {item.sources?.length > 0 && (
                <span className="sources-list">
                  Sources:
                  {item.sources.map((s) => (
                    <button
                      key={s.number}
                      className={`source-pill ${openSource === s.number ? 'active' : ''}`}
                      onClick={() => toggle(s.number)}
                    >
                      {s.number}. {s.fileName}
                      {s.pageNumber ? ` p.${s.pageNumber}` : ''}
                    </button>
                  ))}
                </span>
              )}
            </div>
          </>
        )}
      </div>
    </article>
  )
}

/* ---------- document preview ---------- */
function Preview({ docId, onClose }) {
  const [data, setData] = useState(null)
  const [error, setError] = useState(null)
  useEffect(() => {
    api.getDocument(docId).then(setData).catch((e) => setError(e.message))
  }, [docId])
  useEffect(() => {
    const onKey = (e) => e.key === 'Escape' && onClose()
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])
  return (
    <div className="overlay" onClick={onClose}>
      <div className="modal" onClick={(e) => e.stopPropagation()} role="dialog" aria-modal="true">
        <header>
          <h3>{data?.document.fileName ?? 'Loading…'}</h3>
          <button className="ghost" onClick={onClose} aria-label="Close">
            ✕
          </button>
        </header>
        {error && <p className="answer-error">{error}</p>}
        {data && (
          <>
            <p className="muted small">
              {data.document.pageCount} page(s) · {data.document.wordCount.toLocaleString()} words ·{' '}
              {data.document.chunkCount} chunks
            </p>
            <pre className="doc-text">{data.text}</pre>
            {data.truncated && <p className="muted small">Preview shows the first 20,000 characters.</p>}
          </>
        )}
      </div>
    </div>
  )
}

/* ---------- main app ---------- */
export default function App() {
  const [theme, toggleTheme] = useTheme()
  const [status, setStatus] = useState(null)
  const [docs, setDocs] = useState([])
  const [selected, setSelected] = useState(new Set())
  const [uploads, setUploads] = useState([]) // {name, state, error}
  const [dragging, setDragging] = useState(false)
  const [thread, setThread] = useState([])
  const [question, setQuestion] = useState('')
  const [asking, setAsking] = useState(false)
  const [preview, setPreview] = useState(null)
  const [toast, setToast] = useState(null)
  const fileInput = useRef(null)
  const bottom = useRef(null)

  const refresh = async () => {
    try {
      const [s, d] = await Promise.all([api.status(), api.listDocuments()])
      setStatus(s)
      setDocs(d)
      setSelected((cur) => new Set([...cur].filter((id) => d.some((x) => x.id === id))))
    } catch (e) {
      setStatus({ offline: true })
      showToast('Cannot reach the backend. Is the Spring Boot app running?')
    }
  }

  const showToast = (msg) => {
    setToast(msg)
    setTimeout(() => setToast(null), 4000)
  }

  useEffect(() => {
    refresh()
    // Only show questions asked after the last "New chat"
    let after = 0
    try {
      after = Number(localStorage.getItem('docqa-chat-after')) || 0
    } catch {
      /* storage blocked */
    }
    api
      .history()
      .then((h) => setThread([...h].reverse().filter((x) => x.id > after)))
      .catch(() => {})
  }, [])

  useEffect(() => {
    bottom.current?.scrollIntoView({ behavior: 'smooth', block: 'end' })
  }, [thread])

  const uploadFiles = async (files) => {
    for (const file of files) {
      setUploads((u) => [...u, { name: file.name, state: 'uploading' }])
      try {
        await api.uploadDocument(file)
        setUploads((u) => u.filter((x) => x.name !== file.name))
      } catch (e) {
        setUploads((u) => u.map((x) => (x.name === file.name ? { ...x, state: 'error', error: e.message } : x)))
        setTimeout(() => setUploads((u) => u.filter((x) => x.name !== file.name)), 6000)
      }
    }
    refresh()
  }

  const onDrop = (e) => {
    e.preventDefault()
    setDragging(false)
    if (e.dataTransfer.files.length) uploadFiles([...e.dataTransfer.files])
  }

  const removeDoc = async (doc) => {
    if (!window.confirm(`Delete "${doc.fileName}"?`)) return
    try {
      await api.deleteDocument(doc.id)
      refresh()
    } catch (e) {
      showToast(e.message)
    }
  }

  const toggleSelect = (id) =>
    setSelected((cur) => {
      const next = new Set(cur)
      next.has(id) ? next.delete(id) : next.add(id)
      return next
    })

  const ask = async (q = question) => {
    const text = q.trim()
    if (!text || asking) return
    const key = Date.now()
    setThread((t) => [...t, { key, question: text, pending: true }])
    setQuestion('')
    setAsking(true)
    try {
      const res = await api.ask(text, [...selected])
      setThread((t) => t.map((x) => (x.key === key ? { ...res, key } : x)))
    } catch (e) {
      setThread((t) => t.map((x) => (x.key === key ? { key, question: text, error: e.message } : x)))
    } finally {
      setAsking(false)
    }
  }

  // Start a fresh chat: clears the screen, keeps documents and saved history
  const newChat = () => {
    const lastId = Math.max(0, ...thread.map((x) => x.id || 0))
    try {
      localStorage.setItem('docqa-chat-after', String(lastId))
    } catch {
      /* ignore */
    }
    setThread([])
    setQuestion('')
  }

  const clearHistory = async () => {
    if (!window.confirm('Clear all questions and answers?')) return
    await api.clearHistory().catch(() => {})
    setThread([])
  }

  const scopeLabel =
    selected.size === 0
      ? docs.length === 0
        ? 'no documents yet'
        : `all ${docs.length} document${docs.length === 1 ? '' : 's'}`
      : `${selected.size} selected document${selected.size === 1 ? '' : 's'}`

  return (
    <div className="app">
      <aside className="sidebar">
        <div className="brand">
          <div className="logo" aria-hidden>
            Q
          </div>
          <div>
            <h1>DocQA</h1>
            <p className="muted small">Ask questions from your documents</p>
          </div>
        </div>

        <div
          className={`dropzone ${dragging ? 'drag' : ''}`}
          onDragOver={(e) => {
            e.preventDefault()
            setDragging(true)
          }}
          onDragLeave={() => setDragging(false)}
          onDrop={onDrop}
          onClick={() => fileInput.current?.click()}
          role="button"
          tabIndex={0}
          onKeyDown={(e) => (e.key === 'Enter' || e.key === ' ') && fileInput.current?.click()}
        >
          {Icon.upload}
          <strong>Drop files or click to upload</strong>
          <span className="muted small">PDF, Word, TXT, Markdown, CSV · up to 20 MB</span>
          <input
            ref={fileInput}
            type="file"
            accept={ACCEPT}
            multiple
            hidden
            onChange={(e) => {
              uploadFiles([...e.target.files])
              e.target.value = ''
            }}
          />
        </div>

        {uploads.map((u) => (
          <div key={u.name} className={`upload-row ${u.state}`}>
            <span className="ellipsis">{u.name}</span>
            <span className="small">{u.state === 'uploading' ? 'Reading…' : u.error}</span>
          </div>
        ))}

        <div className="section-head">
          <h2>Documents</h2>
          {selected.size > 0 && (
            <button className="link" onClick={() => setSelected(new Set())}>
              Use all
            </button>
          )}
        </div>

        <ul className="doc-list">
          {docs.length === 0 && <li className="muted small empty">No documents yet.</li>}
          {docs.map((d) => (
            <li key={d.id} className={selected.has(d.id) ? 'selected' : ''}>
              <label>
                <input type="checkbox" checked={selected.has(d.id)} onChange={() => toggleSelect(d.id)} />
                <span className={`type type-${d.fileType}`}>{d.fileType}</span>
                <span className="doc-info">
                  <span className="ellipsis name">{d.fileName}</span>
                  <span className="muted small">
                    {formatSize(d.sizeBytes)} · {d.pageCount} pg · {d.chunkCount} chunks
                  </span>
                </span>
              </label>
              <div className="doc-actions">
                <button className="ghost" title="Preview text" onClick={() => setPreview(d.id)}>
                  {Icon.eye}
                </button>
                <button className="ghost danger" title="Delete" onClick={() => removeDoc(d)}>
                  {Icon.trash}
                </button>
              </div>
            </li>
          ))}
        </ul>

        <div className="status">
          <span className={`dot ${status?.offline ? 'down' : status?.aiMode === 'claude' ? 'ai' : 'local'}`} />
          {status?.offline
            ? 'Backend not reachable'
            : status?.aiMode === 'claude'
              ? `Claude AI · ${status.model}`
              : 'Offline mode (no API key)'}
        </div>
      </aside>

      <main className="main">
        <header className="topbar">
          <div className="muted small">
            Asking from <strong>{scopeLabel}</strong>
          </div>
          <div className="topbar-actions">
            {thread.length > 0 && (
              <button className="link muted-link" onClick={clearHistory}>
                Clear history
              </button>
            )}
            <button className="new-chat" onClick={newChat} disabled={thread.length === 0 || asking}>
              {Icon.plus} New chat
            </button>
            <button className="icon-btn" onClick={toggleTheme} aria-label="Switch theme" title="Switch light / dark">
              {theme === 'dark' ? Icon.sun : Icon.moon}
            </button>
          </div>
        </header>

        <section className="thread">
          {thread.length === 0 ? (
            <div className="welcome">
              <h2>
                Ask anything about <em>your documents.</em>
              </h2>
              <ol className="steps">
                <li>
                  <span>1</span> Upload a PDF, Word or text file on the left.
                </li>
                <li>
                  <span>2</span> Type a question below.
                </li>
                <li>
                  <span>3</span> Get an answer with the exact sources — click a number to see the text.
                </li>
              </ol>
              {docs.length > 0 && (
                <div className="samples">
                  {SAMPLE_QUESTIONS.map((q) => (
                    <button key={q} className="sample" onClick={() => ask(q)}>
                      {q}
                    </button>
                  ))}
                </div>
              )}
            </div>
          ) : (
            thread.map((item) => <AnswerCard key={item.key ?? item.id} item={item} />)
          )}
          <div ref={bottom} />
        </section>

        <form
          className="composer"
          onSubmit={(e) => {
            e.preventDefault()
            ask()
          }}
        >
          <textarea
            value={question}
            onChange={(e) => setQuestion(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter' && !e.shiftKey) {
                e.preventDefault()
                ask()
              }
            }}
            placeholder={docs.length ? 'Ask a question about your documents…' : 'Upload a document first…'}
            rows={1}
            maxLength={1000}
          />
          <button type="submit" className="send" disabled={!question.trim() || asking} aria-label="Ask">
            {Icon.send}
          </button>
        </form>
      </main>

      {preview && <Preview docId={preview} onClose={() => setPreview(null)} />}
      {toast && <div className="toast">{toast}</div>}
    </div>
  )
}
