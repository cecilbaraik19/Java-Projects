import { createContext, useCallback, useContext, useEffect, useState } from 'react';
import { api, getToken, setToken } from './api.js';

const AuthContext = createContext(null);

export function AuthProvider({ children }) {
  const [user, setUser] = useState(null);
  const [checking, setChecking] = useState(() => !!getToken());
  const [expired, setExpired] = useState(false);

  // On load, validate any saved token with the server
  useEffect(() => {
    if (!getToken()) return;
    api.me()
      .then(setUser)
      .catch(() => setToken(null))
      .finally(() => setChecking(false));
  }, []);

  const logout = useCallback(() => {
    setToken(null);
    setUser(null);
  }, []);

  // Any 401 from the API (expired token, password changed elsewhere, account disabled)
  useEffect(() => {
    const onExpired = () => {
      if (getToken()) setExpired(true);
      logout();
    };
    window.addEventListener('auth:expired', onExpired);
    return () => window.removeEventListener('auth:expired', onExpired);
  }, [logout]);

  const login = async (username, password) => {
    const res = await api.login(username, password);
    setToken(res.token);
    setUser(res.user);
    setExpired(false);
  };

  /** After a password change the server issues a new token (old ones are revoked). */
  const replaceSession = (res) => {
    setToken(res.token);
    setUser(res.user);
  };

  return (
    <AuthContext.Provider value={{ user, checking, expired, login, logout, replaceSession }}>
      {children}
    </AuthContext.Provider>
  );
}

export const useAuth = () => useContext(AuthContext);
