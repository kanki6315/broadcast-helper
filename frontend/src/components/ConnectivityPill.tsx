import { useEffect, useState } from 'react'
import { useLocation } from 'react-router-dom'
import { resetDataFreshness, startConnectivityMonitor, useConnectivity } from '../lib/connectivity'

const STATUS_LABEL = { live: 'Live', degraded: 'Slow', offline: 'Offline' } as const

const STATUS_TITLE = {
  live: 'Backend reachable',
  degraded: 'Backend responding slowly',
  offline: 'Backend unreachable',
} as const

function ageLabel(asOf: number): string {
  const minutes = Math.round((Date.now() - asOf) / 60_000)
  if (minutes < 60) return `${Math.max(1, minutes)}m`
  const hours = Math.round(minutes / 60)
  return hours < 24 ? `${hours}h` : `${Math.round(hours / 24)}d`
}

/**
 * Topbar answer to "am I looking at live data or the offline cache?" —
 * a heartbeat-driven Live/Slow/Offline dot, plus the age of the oldest
 * cache-served response on the current page when there is one.
 */
export default function ConnectivityPill() {
  const { status, dataAsOf } = useConnectivity()
  const { pathname } = useLocation()

  useEffect(() => startConnectivityMonitor(), [])
  useEffect(() => resetDataFreshness(), [pathname])

  // While cached data is on screen, re-render every 30s so the age ticks.
  const [, setTick] = useState(0)
  useEffect(() => {
    if (dataAsOf === null) return
    const timer = setInterval(() => setTick((n) => n + 1), 30_000)
    return () => clearInterval(timer)
  }, [dataAsOf])

  const title =
    dataAsOf === null
      ? STATUS_TITLE[status]
      : `${STATUS_TITLE[status]}. This page includes cached data last fetched ${new Date(dataAsOf).toLocaleString()}.`

  return (
    <span className={`conn-pill conn-${status}`} role="status" title={title}>
      <span className="conn-dot" aria-hidden="true" />
      {STATUS_LABEL[status]}
      {dataAsOf !== null && <span className="conn-age">· cached {ageLabel(dataAsOf)}</span>}
    </span>
  )
}
