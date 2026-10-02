import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import { AuthProvider, useAuth } from './context/AuthContext'
import { ThemeProvider } from './context/ThemeContext'
import { LiveProvider } from './context/LiveContext'
import Layout from './components/Layout'
import Login from './pages/Login'
import Dashboard from './pages/Dashboard'
import Resources from './pages/Resources'
import ResourceDetail from './pages/ResourceDetail'
import Alerts from './pages/Alerts'

function Protected() {
  const { user, loading } = useAuth()
  if (loading) {
    return (
      <div className="flex h-full items-center justify-center">
        <span className="h-8 w-8 animate-spin rounded-full border-2 border-line border-t-accent" />
      </div>
    )
  }
  if (!user) return <Navigate to="/login" replace />
  return (
    <LiveProvider>
      <Layout />
    </LiveProvider>
  )
}

export default function App() {
  return (
    <ThemeProvider>
      <AuthProvider>
        <BrowserRouter>
          <Routes>
            <Route path="/login" element={<Login />} />
            <Route element={<Protected />}>
              <Route path="/" element={<Dashboard />} />
              <Route path="/resources" element={<Resources />} />
              <Route path="/resources/:id" element={<ResourceDetail />} />
              <Route path="/alerts" element={<Alerts />} />
            </Route>
            <Route path="*" element={<Navigate to="/" replace />} />
          </Routes>
        </BrowserRouter>
      </AuthProvider>
    </ThemeProvider>
  )
}
