// Thin wrapper over fetch for the Spring Boot API.
const KEY_STORAGE = 'credchain.apiKey'

export const session = {
  get apiKey() {
    try { return sessionStorage.getItem(KEY_STORAGE) } catch { return null }
  },
  set apiKey(v) {
    try { v ? sessionStorage.setItem(KEY_STORAGE, v) : sessionStorage.removeItem(KEY_STORAGE) } catch { /* ignore */ }
  }
}

async function request(method, path, body, { auth = false } = {}) {
  const headers = {}
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  if (auth && session.apiKey) headers['X-API-Key'] = session.apiKey
  let res
  try {
    res = await fetch(`/api${path}`, { method, headers, body: body !== undefined ? JSON.stringify(body) : undefined })
  } catch {
    throw new Error('Cannot reach the backend. Is Spring Boot running on port 8080?')
  }
  const text = await res.text()
  const data = text ? JSON.parse(text) : null
  if (!res.ok) throw new Error(data?.message || `${res.status} ${res.statusText}`)
  return data
}

export const api = {
  stats: () => request('GET', '/stats'),
  chain: () => request('GET', '/chain'),
  block: (i) => request('GET', `/chain/blocks/${i}`),
  pending: () => request('GET', '/chain/pending'),
  mine: () => request('POST', '/chain/mine'),
  validate: () => request('GET', '/chain/validate'),

  institutions: () => request('GET', '/institutions'),
  registerInstitution: (body) => request('POST', '/institutions', body),
  login: (apiKey) => request('POST', '/auth/login', { apiKey }),

  recentCredentials: () => request('GET', '/credentials'),
  byStudent: (studentId) => request('GET', `/credentials?studentId=${encodeURIComponent(studentId)}`),
  myCredentials: () => request('GET', '/credentials/mine', undefined, { auth: true }),
  credential: (id) => request('GET', `/credentials/${encodeURIComponent(id)}`),
  document: (id) => request('GET', `/credentials/${encodeURIComponent(id)}/document`),
  issue: (body) => request('POST', '/credentials', body, { auth: true }),
  revoke: (id, reason) => request('POST', `/credentials/${encodeURIComponent(id)}/revoke`, { reason }, { auth: true }),

  verify: (id) => request('GET', `/verify/${encodeURIComponent(id)}`),
  verifyDocument: (doc) => request('POST', '/verify/document', doc),

  tamperCredential: (id) => request('POST', `/demo/tamper-credential/${encodeURIComponent(id)}`),
  tamperBlock: (i) => request('POST', `/demo/tamper-block/${i}`),
  restore: () => request('POST', '/demo/restore'),
}

export const shortHash = (h, n = 10) => (h ? `${h.slice(0, n)}…${h.slice(-6)}` : '—')
export const fmtTime = (ms) => (ms ? new Date(ms).toLocaleString() : '—')

export function downloadJson(obj, filename) {
  const blob = new Blob([JSON.stringify(obj, null, 2)], { type: 'application/json' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  a.click()
  URL.revokeObjectURL(url)
}
