import { useEffect, useState } from 'react'

import { api } from '@/lib/api'
import type { HealthResponse } from '@/lib/types'

/** Polls /api/health; faster while the docs index is still being built. */
export function useHealth(): HealthResponse | null {
  const [health, setHealth] = useState<HealthResponse | null>(null)

  useEffect(() => {
    let cancelled = false
    let timer: number | undefined
    const poll = async () => {
      const next = await api.health().catch(() => null)
      if (cancelled) return
      setHealth(next)
      const indexing = next?.components?.index?.details?.state === 'INDEXING'
      timer = window.setTimeout(() => void poll(), indexing ? 5_000 : 60_000)
    }
    void poll()
    return () => {
      cancelled = true
      window.clearTimeout(timer)
    }
  }, [])

  return health
}
