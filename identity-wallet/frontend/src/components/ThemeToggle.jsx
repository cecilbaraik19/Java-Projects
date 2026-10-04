import { useState } from 'react'
import { Sun, Moon, Monitor } from 'lucide-react'
import { getTheme, setTheme } from '../theme.js'

const OPTIONS = [['light', Sun, 'Light theme'], ['dark', Moon, 'Dark theme'], ['system', Monitor, 'Use system setting']]

/** Three-way switch: Light / Dark / System. `onDark` styles it for dark hero backgrounds. */
export default function ThemeToggle({ onDark = false, className = '' }) {
  const [theme, set] = useState(getTheme)
  const choose = (t) => { setTheme(t); set(t) }
  return (
    <div className={`theme-toggle ${onDark ? 'on-dark' : ''} ${className}`} role="radiogroup" aria-label="Theme">
      {OPTIONS.map(([value, Icon, label]) => (
        <button key={value} type="button" role="radio" aria-checked={theme === value} aria-label={label} title={label}
                className={theme === value ? 'on' : ''} onClick={() => choose(value)}>
          <Icon size={15} />
        </button>
      ))}
    </div>
  )
}
