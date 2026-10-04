import { useEffect, useState } from 'react'
import { Monitor, Sun, Moon } from 'lucide-react'

const KEY = 'credchain.theme'
const OPTIONS = [
  { value: 'auto', label: 'Auto', icon: Monitor, title: 'Follow my computer setting' },
  { value: 'light', label: 'Light', icon: Sun, title: 'Light mode' },
  { value: 'dark', label: 'Dark', icon: Moon, title: 'Dark mode' },
]

function readSaved() {
  try { return localStorage.getItem(KEY) || 'auto' } catch { return 'auto' }
}

/** Applies a theme to <html data-theme="...">; "auto" removes it so the OS setting wins. */
export function applyTheme(theme) {
  const root = document.documentElement
  if (theme === 'light' || theme === 'dark') root.setAttribute('data-theme', theme)
  else root.removeAttribute('data-theme')
}

// apply the saved choice as early as possible (before React renders)
applyTheme(readSaved())

/** Auto / Light / Dark switch shown in the sidebar. The choice is remembered in this browser. */
export default function ThemeToggle() {
  const [theme, setTheme] = useState(readSaved)

  useEffect(() => {
    applyTheme(theme)
    try { localStorage.setItem(KEY, theme) } catch { /* private mode: just don't remember */ }
  }, [theme])

  return (
    <div className="theme-toggle" role="radiogroup" aria-label="Colour theme">
      {OPTIONS.map(({ value, label, icon: Icon, title }) => (
        <button key={value} type="button" role="radio" aria-checked={theme === value} title={title}
          className={theme === value ? 'active' : ''} onClick={() => setTheme(value)}>
          <Icon size={14} /> <span>{label}</span>
        </button>
      ))}
    </div>
  )
}
