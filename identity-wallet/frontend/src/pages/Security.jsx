import { useEffect, useState } from 'react'
import { QRCodeSVG } from 'qrcode.react'
import { ShieldCheck, ShieldOff, KeyRound, Smartphone, History } from 'lucide-react'
import { api } from '../api.js'
import { useAuth } from '../auth.jsx'
import { Mono, Spinner } from '../components/ui.jsx'
import { useToast } from '../components/Toast.jsx'
import { fmtDateTime, humanize } from '../util.js'

export default function Security() {
  const { user, setUser } = useAuth()
  const toast = useToast()
  const [setup, setSetup] = useState(null)
  const [code, setCode] = useState('')
  const [dis, setDis] = useState({ password: '', code: '' })
  const [pw, setPw] = useState({ currentPassword: '', newPassword: '', confirm: '' })
  const [log, setLog] = useState(null)

  useEffect(() => { api('/auth/activity').then(setLog).catch(() => setLog([])) }, [user.mfaEnabled])

  const start = async () => { try { setSetup(await api('/auth/mfa/setup', { method: 'POST' })); setCode('') } catch (e) { toast(e.message, 'bad') } }
  async function confirm(e) {
    e.preventDefault()
    try { setUser(await api('/auth/mfa/confirm', { method: 'POST', body: { code } })); setSetup(null); toast('Two-factor sign-in is on') }
    catch (ex) { toast(ex.message, 'bad') }
  }
  async function disable(e) {
    e.preventDefault()
    try { setUser(await api('/auth/mfa/disable', { method: 'POST', body: dis })); setDis({ password: '', code: '' }); toast('Two-factor sign-in turned off') }
    catch (ex) { toast(ex.message, 'bad') }
  }
  async function changePw(e) {
    e.preventDefault()
    if (pw.newPassword !== pw.confirm) { toast('New passwords do not match', 'bad'); return }
    try { await api('/auth/password', { method: 'POST', body: pw }); setPw({ currentPassword: '', newPassword: '', confirm: '' }); toast('Password changed') }
    catch (ex) { toast(ex.message, 'bad') }
  }

  return (
    <div className="page">
      <div className="page-head"><div><h1>Security</h1><p className="muted">Sign-in protection, your keys and the tamper-evident activity log.</p></div></div>

      <div className="grid2">
        <section className="card">
          <h2><Smartphone size={18} /> Two-factor sign-in</h2>
          {user.mfaEnabled ? (
            <>
              <div className="verdict ok"><ShieldCheck size={20} /><span>On. Sign-in needs your password and a code from your authenticator app.</span></div>
              <form onSubmit={disable} className="inline-form">
                <label>Password<input type="password" required value={dis.password} onChange={e => setDis({ ...dis, password: e.target.value })} /></label>
                <label>Current code<input inputMode="numeric" maxLength={6} required value={dis.code} onChange={e => setDis({ ...dis, code: e.target.value.replace(/\D/g, '') })} /></label>
                <button className="btn danger ghost"><ShieldOff size={16} />Turn off</button>
              </form>
            </>
          ) : setup ? (
            <form onSubmit={confirm}>
              <ol className="steps">
                <li>Open Google Authenticator, Microsoft Authenticator or Authy and scan:</li>
              </ol>
              <div className="qr center"><QRCodeSVG value={setup.otpauthUri} size={180} includeMargin /></div>
              <p className="hint">Can't scan? Enter this key: <Mono>{setup.secret.match(/.{1,4}/g).join(' ')}</Mono></p>
              <ol className="steps" start={2}><li>Enter the 6-digit code it shows:</li></ol>
              <input className="otp" inputMode="numeric" maxLength={6} value={code} onChange={e => setCode(e.target.value.replace(/\D/g, ''))} placeholder="000000" />
              <button className="btn primary full" disabled={code.length !== 6}>Confirm and turn on</button>
            </form>
          ) : (
            <>
              <div className="verdict warn"><ShieldOff size={20} /><span>Off. Anyone with your password can sign in.</span></div>
              <p className="muted">Uses time-based one-time codes (TOTP, RFC 6238) that change every 30 seconds.</p>
              <button className="btn primary" onClick={start}>Set up authenticator app</button>
            </>
          )}
        </section>

        <section className="card">
          <h2><KeyRound size={18} /> Identity & keys</h2>
          <dl className="kv">
            <dt>DID</dt><dd><Mono className="wrap">{user.did}</Mono></dd>
            <dt>Key fingerprint</dt><dd><Mono>{user.keyFingerprint}</Mono></dd>
            <dt>Key type</dt><dd>ECDSA P-256, private key encrypted at rest</dd>
            <dt>Last sign-in</dt><dd>{fmtDateTime(user.lastLoginAt)}</dd>
          </dl>
          <h3>Change password</h3>
          <form onSubmit={changePw}>
            <label>Current password<input type="password" required autoComplete="current-password" value={pw.currentPassword} onChange={e => setPw({ ...pw, currentPassword: e.target.value })} /></label>
            <div className="row2">
              <label>New password<input type="password" required autoComplete="new-password" value={pw.newPassword} onChange={e => setPw({ ...pw, newPassword: e.target.value })} /></label>
              <label>Repeat<input type="password" required autoComplete="new-password" value={pw.confirm} onChange={e => setPw({ ...pw, confirm: e.target.value })} /></label>
            </div>
            <button className="btn">Update password</button>
          </form>
        </section>
      </div>

      <section className="card">
        <h2><History size={18} /> Activity log</h2>
        <p className="hint">Each entry stores the SHA-256 hash of the one before it, so edits to the log are detectable.</p>
        {!log ? <Spinner /> : (
          <div className="table-wrap">
            <table className="table">
              <thead><tr><th>When</th><th>Event</th><th>Detail</th><th>IP</th><th>Hash</th></tr></thead>
              <tbody>{log.map(a => (
                <tr key={a.id} className={a.action.includes('FAILED') ? 'bad-row' : ''}>
                  <td>{fmtDateTime(a.at)}</td><td>{humanize(a.action)}</td><td>{a.detail}</td><td>{a.ip}</td><td><Mono>{a.hash.slice(0, 10)}…</Mono></td>
                </tr>
              ))}</tbody>
            </table>
          </div>
        )}
      </section>
    </div>
  )
}
