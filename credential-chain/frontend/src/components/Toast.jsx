import { createContext, useCallback, useContext, useState } from 'react'
import { CheckCircle2, XCircle, Info } from 'lucide-react'

const ToastContext = createContext(() => {})
export const useToast = () => useContext(ToastContext)

export function ToastProvider({ children }) {
  const [toasts, setToasts] = useState([])
  const push = useCallback((message, type = 'info') => {
    const id = Math.random().toString(36).slice(2)
    setToasts((t) => [...t, { id, message, type }])
    setTimeout(() => setToasts((t) => t.filter((x) => x.id !== id)), 4500)
  }, [])
  const Icon = { success: CheckCircle2, error: XCircle, info: Info }
  return (
    <ToastContext.Provider value={push}>
      {children}
      <div className="toasts">
        {toasts.map((t) => {
          const I = Icon[t.type] || Info
          return (
            <div key={t.id} className={`toast ${t.type}`}><I size={18} /> {t.message}</div>
          )
        })}
      </div>
    </ToastContext.Provider>
  )
}
