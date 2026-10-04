import { useEffect, useRef, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import jsQR from 'jsqr'
import { ShieldCheck, ShieldX, CheckCircle2, XCircle, Camera, Upload, Link2, Download, EyeOff, Building2, User } from 'lucide-react'
import { api } from '../api.js'
import { Mono, Spinner } from '../components/ui.jsx'
import { fmtDate, fmtValue } from '../util.js'
import ThemeToggle from '../components/ThemeToggle.jsx'

function Result({ r, onReset }) {
  const download = () => {
    const url = URL.createObjectURL(new Blob([r.presentation], { type: 'application/json' }))
    Object.assign(document.createElement('a'), { href: url, download: `presentation-${r.credential.id}.json` }).click()
    setTimeout(() => URL.revokeObjectURL(url), 2000)
  }
  return (
    <div className="verify-result">
      <div className={`verdict big ${r.valid ? 'ok' : 'bad'}`}>
        {r.valid ? <ShieldCheck size={40} /> : <ShieldX size={40} />}
        <div><h2>{r.valid ? 'Verified' : 'Not verified'}</h2><p>{r.reason}</p></div>
      </div>

      {r.claims?.length > 0 && (
        <section className="card">
          <h3>{r.credential.title}</h3>
          <dl className="claims">
            {r.claims.map(c => (
              <div key={c.name} className={c.matches ? '' : 'bad'}>
                <dt>{c.label}</dt>
                <dd>{fmtValue(c.value)} {c.matches ? <CheckCircle2 size={15} className="okc" /> : <XCircle size={15} className="badc" />}</dd>
              </div>
            ))}
          </dl>
          {r.credential.hiddenClaims > 0 && <p className="hint"><EyeOff size={13} /> {r.credential.hiddenClaims} other detail(s) were kept private by the holder.</p>}
        </section>
      )}

      {r.checks?.length > 0 && (
        <section className="card">
          <h3>Checks performed</h3>
          <ul className="checks">
            {r.checks.map(c => (
              <li key={c.name} className={c.ok ? 'ok' : 'bad'}>
                {c.ok ? <CheckCircle2 size={18} /> : <XCircle size={18} />}
                <div><b>{c.name}</b><p>{c.detail}</p></div>
              </li>
            ))}
          </ul>
        </section>
      )}

      {r.issuer && (
        <section className="card grid2 tight">
          <div>
            <h4><Building2 size={15} /> Issuer</h4>
            <p><b>{r.issuer.name}</b></p>
            <Mono className="wrap">{r.issuer.did}</Mono>
            {r.issuer.keyFingerprint && <p className="hint">Key {r.issuer.keyFingerprint}</p>}
          </div>
          <div>
            <h4><User size={15} /> Holder</h4>
            <Mono className="wrap">{r.holder.did}</Mono>
            <p className="hint">Shared with "{r.holder.audience}"{r.share?.purpose ? ` for: ${r.share.purpose}` : ''}</p>
            <p className="hint">Issued {fmtDate(r.credential.issuedAt)}{r.credential.expiresAt ? ` · expires ${fmtDate(r.credential.expiresAt)}` : ''}</p>
            {r.share?.viewsLeft != null && <p className="hint">{r.share.viewsLeft} view(s) left on this link</p>}
          </div>
        </section>
      )}

      <div className="actions">
        {r.presentation && <button className="btn" onClick={download}><Download size={16} />Save signed copy (JSON)</button>}
        <button className="btn ghost" onClick={onReset}>Verify another</button>
      </div>
    </div>
  )
}

function Scanner({ onCode, onClose }) {
  const video = useRef(), canvas = useRef()
  const [err, setErr] = useState('')
  useEffect(() => {
    let stream, raf, stopped = false
    navigator.mediaDevices?.getUserMedia({ video: { facingMode: 'environment' } }).then(s => {
      stream = s
      video.current.srcObject = s
      video.current.play()
      const tick = () => {
        if (stopped) return
        const v = video.current
        if (v && v.readyState === v.HAVE_ENOUGH_DATA) {
          const c = canvas.current, ctx = c.getContext('2d', { willReadFrequently: true })
          c.width = v.videoWidth; c.height = v.videoHeight
          ctx.drawImage(v, 0, 0)
          const code = jsQR(ctx.getImageData(0, 0, c.width, c.height).data, c.width, c.height)
          if (code?.data) { onCode(code.data); return }
        }
        raf = requestAnimationFrame(tick)
      }
      tick()
    }).catch(() => setErr('Camera not available. Allow camera access, or paste the link instead.'))
    return () => { stopped = true; cancelAnimationFrame(raf); stream?.getTracks().forEach(t => t.stop()) }
  }, [onCode])
  return (
    <div className="scanner">
      {err ? <div className="alert bad">{err}</div> : <video ref={video} playsInline muted />}
      <canvas ref={canvas} hidden />
      <button className="btn ghost" onClick={onClose}>Cancel</button>
    </div>
  )
}

export default function Verify({ embedded }) {
  const { token } = useParams()
  const nav = useNavigate()
  const [result, setResult] = useState(null)
  const [busy, setBusy] = useState(false)
  const [err, setErr] = useState('')
  const [input, setInput] = useState('')
  const [scan, setScan] = useState(false)
  const fileRef = useRef()
  const ran = useRef(null)

  const byToken = async (t) => {
    setBusy(true); setErr('')
    try { setResult(await api(`/public/share/${encodeURIComponent(t)}`)) } catch (e) { setErr(e.message) } finally { setBusy(false) }
  }
  // StrictMode runs effects twice in dev; guard so one visit counts as one view
  useEffect(() => { if (token && ran.current !== token) { ran.current = token; byToken(token) } }, [token])

  const fromText = (text) => {
    const m = text.trim().match(/\/v\/([A-Za-z0-9_-]+)/) || text.trim().match(/^([A-Za-z0-9_-]{20,})$/)
    if (m) { token ? nav(`/v/${m[1]}`) : byToken(m[1]) } else setErr('That doesn\'t look like an IdentityWallet share link')
  }

  async function onFile(f) {
    if (!f) return
    setBusy(true); setErr('')
    try { const text = await f.text(); setResult({ ...(await api('/public/verify', { method: 'POST', body: text })), presentation: text }) }
    catch (e) { setErr(e.message) } finally { setBusy(false) }
  }

  const body = (
    <div className={embedded ? 'page' : 'verify-page'}>
      {!embedded && (
        <header className="verify-head">
          <Link to="/" className="brand"><img src="/favicon.svg" alt="" width="28" height="28" /><b>IdentityWallet</b></Link>
          <div className="right"><span className="pill muted">Verifier</span><ThemeToggle /></div>
        </header>
      )}
      <div className="verify-box">
        {busy ? <Spinner label="Checking signatures…" /> : result ? (
          <Result r={result} onReset={() => { setResult(null); setInput(''); if (token) nav('/verify') }} />
        ) : (
          <>
            <h1>Verify a credential</h1>
            <p className="muted">Scan the holder's QR code, paste their share link, or upload a saved presentation file. No account needed.</p>
            {scan ? <Scanner onCode={(c) => { setScan(false); fromText(c) }} onClose={() => setScan(false)} /> : (
              <div className="verify-options">
                <button className="option" onClick={() => setScan(true)}><Camera size={26} /><b>Scan QR code</b><small>Uses your camera</small></button>
                <button className="option" onClick={() => fileRef.current.click()}><Upload size={26} /><b>Upload JSON</b><small>A saved signed copy</small></button>
                <input ref={fileRef} type="file" accept="application/json,.json" hidden onChange={e => onFile(e.target.files[0])} />
              </div>
            )}
            <form className="link-row" onSubmit={e => { e.preventDefault(); fromText(input) }}>
              <Link2 size={18} />
              <input placeholder="Paste share link" value={input} onChange={e => setInput(e.target.value)} />
              <button className="btn primary" disabled={!input}>Verify</button>
            </form>
            {err && <div className="alert bad">{err}</div>}
          </>
        )}
      </div>
    </div>
  )
  return body
}
