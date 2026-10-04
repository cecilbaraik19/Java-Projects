// Theme preference: 'light' | 'dark' | 'system'. Stored per browser; 'system' follows the OS setting.
const KEY = 'idw.theme'

export function getTheme() {
  try { const t = localStorage.getItem(KEY); return t === 'light' || t === 'dark' ? t : 'system' } catch { return 'system' }
}

export function applyTheme(t) {
  const root = document.documentElement
  if (t === 'light' || t === 'dark') root.setAttribute('data-theme', t)
  else root.removeAttribute('data-theme')
}

export function setTheme(t) {
  try { t === 'system' ? localStorage.removeItem(KEY) : localStorage.setItem(KEY, t) } catch { /* private mode: still apply */ }
  applyTheme(t)
}
