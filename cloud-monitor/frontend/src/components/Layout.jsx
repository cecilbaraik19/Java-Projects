import { useState } from 'react'
import { NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom'
import { AnimatePresence, motion } from 'framer-motion'
import { Activity, Bell, LayoutDashboard, LogOut, Menu, Moon, Pause, Play, Server, Sun, X } from 'lucide-react'
import { useAuth } from '../context/AuthContext'
import { useTheme } from '../context/ThemeContext'
import { useLive } from '../context/LiveContext'
import { timeOnly } from '../utils/format'
import Toasts from './Toasts'

const NAV = [
  { to: '/', label: 'Dashboard', Icon: LayoutDashboard, end: true },
  { to: '/resources', label: 'Resources', Icon: Server },
  { to: '/alerts', label: 'Alerts', Icon: Bell },
]

export default function Layout() {
  const { user, logout } = useAuth()
  const { theme, toggle } = useTheme()
  const { connected, lastUpdate, overview, paused, setPaused } = useLive()
  const [mobileOpen, setMobileOpen] = useState(false)
  const location = useLocation()
  const navigate = useNavigate()
  const activeAlerts = overview?.activeAlerts ?? 0

  const sidebar = (
    <div className="flex h-full flex-col">
      <div className="flex items-center gap-2.5 px-5 py-5">
        <span className="flex h-9 w-9 items-center justify-center rounded-xl bg-accent text-white">
          <Activity size={19} strokeWidth={2.5} />
        </span>
        <div>
          <p className="text-[15px] leading-tight font-bold">CloudPulse</p>
          <p className="text-[11px] text-muted">Resource Monitor</p>
        </div>
      </div>
      <nav className="mt-2 flex-1 space-y-1 px-3">
        {NAV.map(({ to, label, Icon, end }) => (
          <NavLink key={to} to={to} end={end} onClick={() => setMobileOpen(false)}
            className={({ isActive }) => `relative flex items-center gap-3 rounded-xl px-3 py-2.5 text-sm font-medium transition-colors ${isActive ? 'text-ink' : 'text-muted hover:text-ink-2'}`}>
            {({ isActive }) => (
              <>
                {isActive && (
                  <motion.span layoutId="nav-active" className="absolute inset-0 rounded-xl bg-surface-2"
                    transition={{ type: 'spring', stiffness: 500, damping: 38 }} />
                )}
                <Icon size={18} className="relative" />
                <span className="relative flex-1">{label}</span>
                {label === 'Alerts' && activeAlerts > 0 && (
                  <span className="relative rounded-full bg-[var(--crit)] px-1.5 py-0.5 text-[10px] font-bold text-white tnum">
                    {activeAlerts > 99 ? '99+' : activeAlerts}
                  </span>
                )}
              </>
            )}
          </NavLink>
        ))}
      </nav>
      <div className="m-3 rounded-xl border border-line p-3">
        <div className="flex items-center gap-2.5">
          <span className="flex h-8 w-8 items-center justify-center rounded-full bg-surface-2 text-sm font-semibold uppercase">
            {user?.displayName?.[0] || 'U'}
          </span>
          <div className="min-w-0 flex-1">
            <p className="truncate text-sm font-medium">{user?.displayName}</p>
            <p className="text-[11px] text-muted">{user?.role === 'ADMIN' ? 'Admin · full access' : 'Viewer · read only'}</p>
          </div>
          <button onClick={logout} title="Log out" className="rounded-lg p-1.5 text-muted hover:bg-surface-2 hover:text-ink">
            <LogOut size={16} />
          </button>
        </div>
      </div>
    </div>
  )

  return (
    <div className="flex h-full">
      <aside className="hidden w-60 shrink-0 border-r border-line bg-surface lg:block">{sidebar}</aside>

      <AnimatePresence>
        {mobileOpen && (
          <>
            <motion.div className="fixed inset-0 z-40 bg-black/50 lg:hidden" onClick={() => setMobileOpen(false)}
              initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} />
            <motion.aside className="fixed inset-y-0 left-0 z-50 w-64 border-r border-line bg-surface lg:hidden"
              initial={{ x: -280 }} animate={{ x: 0 }} exit={{ x: -280 }} transition={{ type: 'spring', stiffness: 400, damping: 40 }}>
              <button className="absolute top-5 right-3 p-1 text-muted" onClick={() => setMobileOpen(false)} aria-label="Close menu"><X size={18} /></button>
              {sidebar}
            </motion.aside>
          </>
        )}
      </AnimatePresence>

      <div className="flex min-w-0 flex-1 flex-col">
        <header className="sticky top-0 z-30 flex items-center gap-3 border-b border-line bg-[color-mix(in_srgb,var(--page)_85%,transparent)] px-4 py-3 backdrop-blur-md sm:px-6">
          <button className="rounded-lg p-1.5 text-ink-2 hover:bg-surface-2 lg:hidden" onClick={() => setMobileOpen(true)} aria-label="Open menu">
            <Menu size={20} />
          </button>

          <div className="flex items-center gap-2 rounded-full border border-line bg-surface px-3 py-1.5 text-xs">
            <span className={`h-2 w-2 rounded-full ${connected && !paused ? 'live-dot bg-[var(--good)]' : paused ? 'bg-[var(--warn)]' : 'bg-[var(--crit)]'}`} />
            <span className="font-medium text-ink-2">{paused ? 'Paused' : connected ? 'Live' : 'Reconnecting…'}</span>
            {lastUpdate && <span className="hidden text-muted tnum sm:inline">· {timeOnly(lastUpdate)}</span>}
          </div>

          <button onClick={() => setPaused(!paused)} title={paused ? 'Resume live updates' : 'Pause live updates'}
            className="rounded-lg border border-line bg-surface p-1.5 text-ink-2 hover:bg-surface-2">
            {paused ? <Play size={15} /> : <Pause size={15} />}
          </button>

          <div className="flex-1" />

          <button onClick={() => navigate('/alerts')} title="Alerts" className="relative rounded-lg p-2 text-ink-2 hover:bg-surface-2">
            <motion.span key={activeAlerts} animate={activeAlerts ? { rotate: [0, -15, 12, -8, 0] } : {}} transition={{ duration: 0.6 }} className="block">
              <Bell size={19} />
            </motion.span>
            {activeAlerts > 0 && (
              <span className="absolute top-1 right-1 h-2 w-2 rounded-full bg-[var(--crit)] ring-2 ring-[var(--page)]" />
            )}
          </button>

          <button onClick={toggle} title="Toggle theme" className="rounded-lg p-2 text-ink-2 hover:bg-surface-2">
            <AnimatePresence mode="wait" initial={false}>
              <motion.span key={theme} className="block" initial={{ rotate: -90, opacity: 0 }} animate={{ rotate: 0, opacity: 1 }}
                exit={{ rotate: 90, opacity: 0 }} transition={{ duration: 0.2 }}>
                {theme === 'dark' ? <Sun size={19} /> : <Moon size={19} />}
              </motion.span>
            </AnimatePresence>
          </button>
        </header>

        <main className="flex-1 overflow-y-auto px-4 py-6 sm:px-6">
          <motion.div key={location.pathname} className="mx-auto max-w-[1400px]"
            initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.25 }}>
            <Outlet />
          </motion.div>
        </main>
      </div>
      <Toasts />
    </div>
  )
}
