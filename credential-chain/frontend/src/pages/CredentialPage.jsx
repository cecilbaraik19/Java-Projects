import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { Download, Printer, ShieldCheck, Link2, FileText } from 'lucide-react'
import { api, downloadJson } from '../api.js'
import CredentialCertificate from '../components/CredentialCertificate.jsx'
import { useToast } from '../components/Toast.jsx'
import { downloadCertificatePdf } from '../certificatePdf.js'

export default function CredentialPage() {
  const { id } = useParams()
  const [view, setView] = useState(null)
  const [error, setError] = useState(null)
  const toast = useToast()

  useEffect(() => {
    api.credential(id).then(setView).catch((e) => setError(e.message))
  }, [id])

  const download = async () => {
    try {
      downloadJson(await api.document(id), `${id}.json`)
    } catch (e) { toast(e.message, 'error') }
  }

  const downloadPdf = async () => {
    try {
      await downloadCertificatePdf(view)
      toast('PDF certificate downloaded', 'success')
    } catch (e) { toast('Could not create PDF: ' + e.message, 'error') }
  }

  const copyLink = async () => {
    try {
      await navigator.clipboard.writeText(`${window.location.origin}/verify/${id}`)
      toast('Verification link copied', 'success')
    } catch { toast('Could not access clipboard', 'error') }
  }

  if (error) return <div className="card error-card">{error}</div>
  if (!view) return <div className="loading">Loading…</div>

  return (
    <div className="page narrow">
      <header className="page-head no-print">
        <div><h1>Credential</h1><p className="mono">{id}</p></div>
      </header>
      <CredentialCertificate view={view} />
      <div className="actions no-print">
        <Link className="btn primary" to={`/verify/${id}`}><ShieldCheck size={16} /> Verify now</Link>
        <button className="btn" onClick={downloadPdf}><FileText size={16} /> Download PDF</button>
        <button className="btn" onClick={download}><Download size={16} /> Download signed JSON</button>
        <button className="btn" onClick={copyLink}><Link2 size={16} /> Copy verify link</button>
        <button className="btn" onClick={() => window.print()}><Printer size={16} /> Print</button>
      </div>
    </div>
  )
}
