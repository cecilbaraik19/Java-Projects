// Small fetch wrapper. The login token lives in sessionStorage: it disappears when the tab
// closes, and isn't shared across tabs (safer than localStorage for an identity wallet).
const KEY = 'idw.token'

export const tokenStore = {
  get: () => { try { return sessionStorage.getItem(KEY) } catch { return null } },
  set: (t) => { try { t ? sessionStorage.setItem(KEY, t) : sessionStorage.removeItem(KEY) } catch { /* ignore */ } }
}

let onUnauthorized = () => {}
export const setUnauthorizedHandler = (fn) => { onUnauthorized = fn }

export async function api(path, { method = 'GET', body, form, raw } = {}) {
  const headers = {}
  const token = tokenStore.get()
  if (token) headers.Authorization = `Bearer ${token}`
  let payload
  if (form) payload = form
  else if (typeof body === 'string') { payload = body; headers['Content-Type'] = 'application/json' }
  else if (body !== undefined) { payload = JSON.stringify(body); headers['Content-Type'] = 'application/json' }

  const res = await fetch(`/api${path}`, { method, headers, body: payload })
  if (res.status === 401 && token && !path.startsWith('/auth/login') && !path.startsWith('/auth/mfa')) onUnauthorized()
  if (raw) {
    if (!res.ok) throw new Error((await res.json().catch(() => ({}))).error || res.statusText)
    return res
  }
  const data = await res.json().catch(() => ({}))
  if (!res.ok) throw new Error(data.error || `Request failed (${res.status})`)
  return data
}
