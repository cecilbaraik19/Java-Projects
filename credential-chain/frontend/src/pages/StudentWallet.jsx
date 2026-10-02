import { useState } from 'react'
import { Search } from 'lucide-react'
import { Link } from 'react-router-dom'
import { api } from '../api.js'
import CredentialCertificate from '../components/CredentialCertificate.jsx'

/** Students look up all credentials issued to their roll number. */
export default function StudentWallet() {
  const [studentId, setStudentId] = useState('')
  const [results, setResults] = useState(null)
  const [error, setError] = useState(null)

  const search = async (e) => {
    e.preventDefault()
    setError(null)
    try { setResults(await api.byStudent(studentId.trim())) } catch (err) { setError(err.message) }
  }

  return (
    <div className="page">
      <header className="page-head"><div><h1>Student Wallet</h1><p>Find every credential issued to your student / roll ID and share it with employers.</p></div></header>
      <form className="card search" onSubmit={search}>
        <input value={studentId} onChange={(e) => setStudentId(e.target.value)} placeholder="e.g. 2022-BSCIT/011" required />
        <button className="btn primary"><Search size={16} /> Search</button>
      </form>
      {error && <div className="card error-card">{error}</div>}
      {results && results.length === 0 && <div className="card muted">No credentials found for that ID.</div>}
      <div className="wallet-grid">
        {results?.map((v) => (
          <Link key={v.credential.credentialId} to={`/credential/${v.credential.credentialId}`} className="plain">
            <CredentialCertificate view={v} />
          </Link>
        ))}
      </div>
    </div>
  )
}
