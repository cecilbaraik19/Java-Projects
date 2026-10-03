// Thin wrapper over fetch for the Spring Boot API.
const TOKEN_KEY = 'credchain.token'
const USER_KEY = 'credchain.user'

// Signed-in session (kept in sessionStorage: cleared when the browser tab is closed)
export const session = {
  get token() {
    try { return sessionStorage.getItem(TOKEN_KEY) } catch { return null }
  },
  get user() {
    try { return JSON.parse(sessionStorage.getItem(USER_KEY)) } catch { return null }
  },
  save(token, user) {
    try {
      sessionStorage.setItem(TOKEN_KEY, token)
      sessionStorage.setItem(USER_KEY, JSON.stringify(user))
    } catch { /* ignore */ }
  },
  clear() {
    try {
      sessionStorage.removeItem(TOKEN_KEY)
      sessionStorage.removeItem(USER_KEY)
    } catch { /* ignore */ }
  },
}

async function request(method, path, body, { auth = false } = {}) {
  const headers = {}
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  if (auth && session.token) headers['Authorization'] = `Bearer ${session.token}`
  let res
  try {
    res = await fetch(`/api${path}`, { method, headers, body: body !== undefined ? JSON.stringify(body) : undefined })
  } catch {
    throw new Error('Cannot reach the backend. Is Spring Boot running on port 8080?')
  }
  const text = await res.text()
  const data = text ? JSON.parse(text) : null
  if (res.status === 401 && auth) {
    // token missing or expired: sign out everywhere
    session.clear()
    window.dispatchEvent(new Event('credchain:signedout'))
  }
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
  registerInstitution: (body) => request('POST', '/institutions', body, { auth: true }),
  login: (email, password) => request('POST', '/auth/login', { email, password }),
  me: () => request('GET', '/auth/me', undefined, { auth: true }),
  changePassword: (currentPassword, newPassword) => request('POST', '/auth/change-password', { currentPassword, newPassword }, { auth: true }),

  recentCredentials: () => request('GET', '/credentials'),
  byStudent: (studentId) => request('GET', `/credentials?studentId=${encodeURIComponent(studentId)}`),
  myCredentials: () => request('GET', '/credentials/mine', undefined, { auth: true }),
  credential: (id) => request('GET', `/credentials/${encodeURIComponent(id)}`),
  document: (id) => request('GET', `/credentials/${encodeURIComponent(id)}/document`),
  issue: (body) => request('POST', '/credentials', body, { auth: true }),
  revoke: (id, reason) => request('POST', `/credentials/${encodeURIComponent(id)}/revoke`, { reason }, { auth: true }),

  verify: (id) => request('GET', `/verify/${encodeURIComponent(id)}`),
  verifyDocument: (doc) => request('POST', '/verify/document', doc),

  tamperCredential: (id) => request('POST', `/demo/tamper-credential/${encodeURIComponent(id)}`, undefined, { auth: true }),
  tamperBlock: (i) => request('POST', `/demo/tamper-block/${i}`, undefined, { auth: true }),
  restore: () => request('POST', '/demo/restore', undefined, { auth: true }),
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
