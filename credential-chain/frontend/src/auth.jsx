import { createContext, useCallback, useContext, useEffect, useState } from 'react'
import { api, session } from './api.js'

const AuthContext = createContext(null)
export const useAuth = () => useContext(AuthContext)

/** Holds the signed-in user for the whole app. */
export function AuthProvider({ children }) {
  const [user, setUser] = useState(session.user)

  // Check the saved token is still valid when the app opens
  useEffect(() => {
    if (session.token) api.me().then(setUser).catch(() => { session.clear(); setUser(null) })
  }, [])

  useEffect(() => {
    const onSignedOut = () => setUser(null)
    window.addEventListener('credchain:signedout', onSignedOut)
    return () => window.removeEventListener('credchain:signedout', onSignedOut)
  }, [])

  const login = useCallback(async (email, password) => {
    const res = await api.login(email, password)
    session.save(res.token, res.user)
    setUser(res.user)
    return res.user
  }, [])

  const logout = useCallback(() => {
    session.clear()
    setUser(null)
  }, [])

  return <AuthContext.Provider value={{ user, login, logout }}>{children}</AuthContext.Provider>
}
