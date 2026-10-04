import { createContext, useCallback, useContext, useEffect, useState } from 'react'
import { api, setUnauthorizedHandler, tokenStore } from './api.js'

const AuthCtx = createContext(null)

export function AuthProvider({ children }) {
  const [user, setUser] = useState(null)
  const [ready, setReady] = useState(false)

  const logout = useCallback(() => { tokenStore.set(null); setUser(null) }, [])

  useEffect(() => {
    setUnauthorizedHandler(logout)
    if (!tokenStore.get()) { setReady(true); return }
    api('/auth/me').then(setUser).catch(logout).finally(() => setReady(true))
  }, [logout])

  const acceptSession = (res) => { tokenStore.set(res.token); setUser(res.user) }
  const refresh = () => api('/auth/me').then(setUser)

  return <AuthCtx.Provider value={{ user, ready, logout, acceptSession, refresh, setUser }}>{children}</AuthCtx.Provider>
}

export const useAuth = () => useContext(AuthCtx)
