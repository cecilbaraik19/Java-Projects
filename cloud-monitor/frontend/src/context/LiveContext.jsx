import { createContext, useCallback, useContext, useEffect, useRef, useState } from 'react'
import { Client } from '@stomp/stompjs'
import api, { tokenStore } from '../api/client'

const LiveContext = createContext(null)
const MAX_POINTS = 100 // ~5 minutes at one update every 3s

/**
 * Keeps one WebSocket open to the backend and shares the latest data with every page:
 * resources, overview, a rolling "live" history, and incoming alerts.
 */
export function LiveProvider({ children }) {
  const [resources, setResources] = useState([])
  const [overview, setOverview] = useState(null)
  const [series, setSeries] = useState({ overview: [], byId: {} })
  const [connected, setConnected] = useState(false)
  const [lastUpdate, setLastUpdate] = useState(null)
  const [toasts, setToasts] = useState([])
  const [paused, setPaused] = useState(false)
  const pausedRef = useRef(false)
  const alertListeners = useRef(new Set())

  useEffect(() => { pausedRef.current = paused }, [paused])

  const dismissToast = useCallback((toastId) => setToasts((t) => t.filter((x) => x.toastId !== toastId)), [])

  const applyUpdate = useCallback((ov, list) => {
    setOverview(ov)
    setResources(list)
    setLastUpdate(ov.timestamp)
    setSeries((prev) => {
      const time = ov.timestamp
      const overviewPts = [...prev.overview, {
        time, cpu: ov.avgCpu, memory: ov.avgMemory, disk: ov.avgDisk,
        networkIn: ov.totalNetworkIn, networkOut: ov.totalNetworkOut,
      }].slice(-MAX_POINTS)
      const byId = { ...prev.byId }
      for (const r of list) {
        byId[r.id] = [...(byId[r.id] || []), {
          time, cpu: r.cpu, memory: r.memory, disk: r.disk,
          networkIn: r.networkIn, networkOut: r.networkOut,
        }].slice(-MAX_POINTS)
      }
      return { overview: overviewPts, byId }
    })
  }, [])

  // Initial snapshot over REST so the screen isn't empty while the socket connects
  useEffect(() => {
    Promise.all([api.get('/overview'), api.get('/resources')])
      .then(([o, r]) => applyUpdate(o.data, r.data))
      .catch(() => {})
  }, [applyUpdate])

  useEffect(() => {
    const proto = window.location.protocol === 'https:' ? 'wss' : 'ws'
    const client = new Client({
      brokerURL: `${proto}://${window.location.host}/ws`,
      connectHeaders: { Authorization: `Bearer ${tokenStore.get()}` },
      reconnectDelay: 3000,
      heartbeatIncoming: 10000,
      heartbeatOutgoing: 10000,
      onConnect: () => {
        setConnected(true)
        client.subscribe('/topic/live', (msg) => {
          if (pausedRef.current) return
          const { overview: ov, resources: list } = JSON.parse(msg.body)
          applyUpdate(ov, list)
        })
        client.subscribe('/topic/alerts', (msg) => {
          const alert = JSON.parse(msg.body)
          alertListeners.current.forEach((fn) => fn(alert))
          const id = `${alert.id}-${Date.now()}`
          setToasts((t) => [...t.slice(-3), { ...alert, toastId: id }])
          setTimeout(() => setToasts((t) => t.filter((x) => x.toastId !== id)), 6500)
        })
      },
      onWebSocketClose: () => setConnected(false),
      onStompError: () => setConnected(false),
    })
    client.activate()
    return () => { client.deactivate() }
  }, [applyUpdate])

  /** Lets a page react to new alerts (e.g. refresh its list). Returns an unsubscribe fn. */
  const onAlert = useCallback((fn) => {
    alertListeners.current.add(fn)
    return () => alertListeners.current.delete(fn)
  }, [])

  /** Replace one resource immediately after an action (start/stop) without waiting for the next tick. */
  const patchResource = useCallback((r) => {
    setResources((list) => list.map((x) => (x.id === r.id ? r : x)))
  }, [])

  return (
    <LiveContext.Provider value={{
      resources, overview, series, connected, lastUpdate, paused, setPaused,
      toasts, dismissToast, onAlert, patchResource,
    }}>
      {children}
    </LiveContext.Provider>
  )
}

// eslint-disable-next-line react-refresh/only-export-components
export const useLive = () => useContext(LiveContext)
