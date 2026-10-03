import { useCallback, useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { ShieldCheck, ShieldX, FlaskConical, RotateCcw, Database, Boxes, ChevronRight, Lock } from 'lucide-react'
import { api, fmtTime } from '../api.js'
import Hash from '../components/Hash.jsx'
import { useToast } from '../components/Toast.jsx'
import { useAuth } from '../auth.jsx'

export default function Explorer() {
  const { index } = useParams()
  const navigate = useNavigate()
  const toast = useToast()
  const { user } = useAuth()
  const isAdmin = user?.role === 'ADMIN'
  const [chain, setChain] = useState([])
  const [report, setReport] = useState(null)
  const [credentialId, setCredentialId] = useState('')
  const [tamperIndex, setTamperIndex] = useState(1)

  const selected = index != null ? chain.find((b) => b.index === Number(index)) : chain[chain.length - 1]

  const load = useCallback(async () => {
    try { setChain(await api.chain()) } catch (e) { toast(e.message, 'error') }
  }, [toast])

  useEffect(() => { load() }, [load])

  const validate = async () => {
    try {
      const r = await api.validate()
      setReport(r)
      toast(r.valid ? 'Every block and signature checks out' : `${r.issues.length} integrity problem(s) found`, r.valid ? 'success' : 'error')
    } catch (e) { toast(e.message, 'error') }
  }

  const act = async (fn) => {
    try {
      const r = await fn()
      toast(r.message, 'info')
      setReport(null)
      load()
    } catch (e) { toast(e.message, 'error') }
  }

  const badBlocks = new Set(report?.issues.map((i) => i.blockIndex) || [])

  return (
    <div className="page">
      <header className="page-head">
        <div><h1>Chain Explorer</h1><p>{chain.length} blocks · click a block to inspect it</p></div>
        <button className="btn primary" onClick={validate}><ShieldCheck size={16} /> Validate entire chain</button>
      </header>

      {report && (
        <div className={`card validation ${report.valid ? 'ok' : 'bad'}`}>
          <div className="card-head">
            <h2>{report.valid ? <ShieldCheck size={20} /> : <ShieldX size={20} />} {report.valid ? 'Chain is valid' : 'Chain has been tampered with'}</h2>
            <span className="muted small">{report.blocksChecked} blocks · {report.transactionsChecked} transactions checked</span>
          </div>
          {report.issues.length > 0 && (
            <ul className="issues">
              {report.issues.map((i, k) => <li key={k}><strong>Block #{i.blockIndex}</strong> — {i.check}: {i.detail}</li>)}
            </ul>
          )}
        </div>
      )}

      <div className="chain-scroll">
        {chain.map((b, i) => (
          <div className="strip-item" key={b.index}>
            <button
              className={`block-card ${selected?.index === b.index ? 'selected' : ''} ${badBlocks.has(b.index) ? 'broken' : ''}`}
              onClick={() => navigate(`/explorer/${b.index}`)}
            >
              <div className="block-no">Block #{b.index}{b.index === 0 && ' · genesis'}</div>
              <label>Hash</label><span className="mono small">{b.hash.slice(0, 16)}…</span>
              <label>Prev</label><span className="mono small">{b.previousHash.slice(0, 16)}…</span>
              <span className="muted small">{b.transactions.length} tx</span>
            </button>
            {i < chain.length - 1 && <ChevronRight className="chain-arrow" size={22} />}
          </div>
        ))}
      </div>

      <div className="grid-2 wide-left">
        {selected && (
          <section className="card">
            <div className="card-head"><h2><Boxes size={20} /> Block #{selected.index}</h2></div>
            <dl className="details">
              <dt>Hash</dt><dd><Hash value={selected.hash} full /></dd>
              <dt>Previous hash</dt><dd><Hash value={selected.previousHash} full /></dd>
              <dt>Merkle root</dt><dd><Hash value={selected.merkleRoot} full /></dd>
              <dt>Timestamp</dt><dd>{fmtTime(selected.timestamp)}</dd>
              <dt>Nonce</dt><dd>{selected.nonce.toLocaleString()}</dd>
              <dt>Difficulty</dt><dd>{selected.difficulty} leading zeros</dd>
              <dt>Mining time</dt><dd>{selected.miningTimeMs} ms</dd>
            </dl>
            <h3>Transactions ({selected.transactions.length})</h3>
            {selected.transactions.length === 0 ? <p className="muted">Genesis block has no transactions.</p> : (
              <ul className="tx-cards">
                {selected.transactions.map((t) => (
                  <li key={t.id}>
                    <div className="tx-row">
                      <span className={`tx-type ${t.type}`}>{t.type.replace('_', ' ')}</span>
                      {t.credentialId && (
                        <button className="link mono small" onClick={() => navigate(`/verify/${t.credentialId}`)}>{t.credentialId}</button>
                      )}
                    </div>
                    <div className="tx-grid">
                      <label>Tx id</label><Hash value={t.id} n={14} />
                      <label>Issuer</label><span className="mono small">{t.issuerId}</span>
                      <label>Data hash</label><Hash value={t.dataHash} n={14} />
                      {t.type === 'REVOKE' && <><label>Reason</label><span>{t.payload}</span></>}
                      {t.type === 'REGISTER_ISSUER' && <><label>Institution</label><span>{t.payload.split('|')[1]}</span></>}
                      <label>Signature</label><Hash value={t.signature} n={14} />
                    </div>
                  </li>
                ))}
              </ul>
            )}
          </section>
        )}

        <section className="card attack-lab">
          <div className="card-head"><h2><FlaskConical size={20} /> Attack lab</h2></div>
          {!isAdmin ? (
            <div className="locked">
              <Lock size={18} />
              <p className="small">The attack lab changes real data, so only the administrator can use it.</p>
              <Link className="btn primary" to="/login" state={{ from: '/explorer' }}>Sign in as admin</Link>
            </div>
          ) : (
            <>
            <p className="muted small">Simulate attacks, then validate the chain or verify a credential to watch them get caught.</p>

            <h3><Database size={16} /> Edit the database</h3>
            <p className="small">Change a student's grade directly in the off-chain DB — like a corrupt insider would.</p>
            <div className="search">
              <input value={credentialId} onChange={(e) => setCredentialId(e.target.value)} placeholder="Credential ID" />
              <button className="btn danger" onClick={() => act(() => api.tamperCredential(credentialId.trim()))} disabled={!credentialId.trim()}>Forge grade</button>
            </div>

            <h3><Boxes size={16} /> Edit a mined block</h3>
            <p className="small">Alter a transaction inside a block that's already on the chain.</p>
            <div className="search">
              <input type="number" min="1" value={tamperIndex} onChange={(e) => setTamperIndex(e.target.value)} />
              <button className="btn danger" onClick={() => act(() => api.tamperBlock(tamperIndex))}>Tamper block</button>
            </div>

            <button className="btn ghost full" onClick={() => act(api.restore)}><RotateCcw size={16} /> Restore everything</button>
            </>
          )}
        </section>
      </div>
    </div>
  )
}
