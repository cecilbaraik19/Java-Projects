import { useEffect, useState } from 'react'
import api from '../api/client'

/** Loads stored history for a time range and refreshes it every 30s. Skipped for range "live". */
export default function useHistory(url, range) {
  const [data, setData] = useState([])
  const [loading, setLoading] = useState(false)

  useEffect(() => {
    if (range === 'live' || !url) return
    let cancelled = false
    const load = (first) => {
      if (first) setLoading(true)
      api.get(url, { params: { range } })
        .then((r) => { if (!cancelled) setData(r.data) })
        .catch(() => {})
        .finally(() => { if (!cancelled && first) setLoading(false) })
    }
    load(true)
    const t = setInterval(() => load(false), 30000)
    return () => { cancelled = true; clearInterval(t) }
  }, [url, range])

  return { data, loading }
}

export const RANGES = [
  { value: 'live', label: 'Live' },
  { value: '15m', label: '15m' },
  { value: '1h', label: '1h' },
  { value: '6h', label: '6h' },
  { value: '24h', label: '24h' },
]
