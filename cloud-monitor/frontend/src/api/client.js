import axios from 'axios'

const api = axios.create({ baseURL: '/api' })

export const tokenStore = {
  get: () => { try { return localStorage.getItem('token') } catch { return null } },
  set: (t) => {
    try {
      if (t) localStorage.setItem('token', t)
      else localStorage.removeItem('token')
    } catch { /* ignore */ }
  },
}

api.interceptors.request.use((config) => {
  const token = tokenStore.get()
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

// If the token expired, go back to the login page
api.interceptors.response.use(
  (res) => res,
  (err) => {
    if (err.response?.status === 401 && !err.config.url.includes('/auth/login')) {
      tokenStore.set(null)
      window.dispatchEvent(new Event('auth:logout'))
    }
    return Promise.reject(err)
  },
)

export const errorMessage = (err, fallback = 'Something went wrong') =>
  err?.response?.data?.message || err?.response?.data?.error || err?.message || fallback

export default api
