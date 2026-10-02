import { createContext, useCallback, useContext, useEffect, useState } from 'react'
import api, { tokenStore } from '../api/client'

const AuthContext = createContext(null)

export function AuthProvider({ children }) {
  const [user, setUser] = useState(null)
  const [loading, setLoading] = useState(() => !!tokenStore.get())

  // On page load, check if a saved token is still valid
  useEffect(() => {
    if (!tokenStore.get()) return
    api.get('/auth/me')
      .then((r) => setUser(r.data))
      .catch(() => tokenStore.set(null))
      .finally(() => setLoading(false))
  }, [])

  useEffect(() => {
    const onLogout = () => setUser(null)
    window.addEventListener('auth:logout', onLogout)
    return () => window.removeEventListener('auth:logout', onLogout)
  }, [])

  const login = useCallback(async (username, password) => {
    const { data } = await api.post('/auth/login', { username, password })
    tokenStore.set(data.token)
    setUser(data)
    return data
  }, [])

  const logout = useCallback(() => {
    tokenStore.set(null)
    setUser(null)
  }, [])

  const isAdmin = user?.role === 'ADMIN'

  return (
    <AuthContext.Provider value={{ user, loading, login, logout, isAdmin }}>
      {children}
    </AuthContext.Provider>
  )
}

// eslint-disable-next-line react-refresh/only-export-components
export const useAuth = () => useContext(AuthContext)
