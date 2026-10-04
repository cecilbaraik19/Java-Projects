import { QRCodeSVG } from 'qrcode.react'
import { Copy, ExternalLink } from 'lucide-react'
import { copy } from '../util.js'
import { useToast } from './Toast.jsx'

export default function ShareLink({ link }) {
  const toast = useToast()
  return (
    <div className="share-link">
      <div className="qr"><QRCodeSVG value={link} size={196} level="M" includeMargin /></div>
      <div className="link-row">
        <input readOnly value={link} onFocus={e => e.target.select()} aria-label="Share link" />
        <button className="btn sm" onClick={async () => toast(await copy(link) ? 'Link copied' : 'Copy failed', 'ok')}><Copy size={15} />Copy</button>
        <a className="btn sm ghost" href={link} target="_blank" rel="noreferrer"><ExternalLink size={15} />Open</a>
      </div>
      <p className="hint">Opening the link counts as a view and shows up in your access log.</p>
    </div>
  )
}
