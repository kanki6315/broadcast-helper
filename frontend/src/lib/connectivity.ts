import { useSyncExternalStore } from 'react'

/**
 * Trackside connectivity + data-freshness signals for the header pill
 * (components/ConnectivityPill.tsx). Two independent signals:
 *
 *  - status: a heartbeat proves the backend is actually reachable right now.
 *    navigator.onLine is not trusted on its own — it happily reports "online"
 *    on a connection too poor to use, which is exactly the trackside failure
 *    mode this exists to surface.
 *
 *  - dataAsOf: the service worker serves GET /api/* stale-while-revalidate
 *    (see vite.config.ts), so a cache hit is silent and instant. The one
 *    honest record of when rendered data last came from the server is the
 *    Date header the cached response kept from its original fetch; getJson
 *    reports it here, and the pill shows an age when it predates this page
 *    load by more than clock-skew noise.
 */

export type ConnectivityStatus = 'live' | 'degraded' | 'offline'

export interface Connectivity {
  status: ConnectivityStatus
  /** Epoch ms of the oldest cache-served API response on the current page,
   *  or null when everything so far came fresh off the network. */
  dataAsOf: number | null
}

const HEARTBEAT_MS = 30_000
const HEARTBEAT_TIMEOUT_MS = 4_000
/** A heartbeat that answers, but slower than this, reads as degraded. */
const SLOW_MS = 2_500
/** Age at serve time beyond which a response counts as "from cache" —
 *  generous so ordinary client/server clock skew never trips it. */
const STALE_AT_SERVE_MS = 90_000

let snapshot: Connectivity = { status: 'live', dataAsOf: null }
const listeners = new Set<() => void>()

function update(next: Partial<Connectivity>) {
  const merged = { ...snapshot, ...next }
  if (merged.status === snapshot.status && merged.dataAsOf === snapshot.dataAsOf) return
  snapshot = merged
  listeners.forEach((notify) => notify())
}

/** Called with every successful API read (see getJson). Tracks the oldest
 *  Date header among responses that were already stale when served. */
export function reportApiResponse(res: Response) {
  const date = res.headers.get('date')
  if (!date) return
  const served = Date.parse(date)
  if (Number.isNaN(served)) return
  if (Date.now() - served < STALE_AT_SERVE_MS) return // fresh from the network
  if (snapshot.dataAsOf === null || served < snapshot.dataAsOf) update({ dataAsOf: served })
}

/** Route changed — the new page's fetches re-report their own freshness. */
export function resetDataFreshness() {
  update({ dataAsOf: null })
}

async function ping() {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), HEARTBEAT_TIMEOUT_MS)
  const started = Date.now()
  try {
    // HEAD dodges every service-worker route (they all match GET only), so
    // this is a true network probe that can never be answered from cache;
    // cache:'no-store' keeps the browser HTTP cache out of it too. Spring
    // answers HEAD on any @GetMapping. Any status proves reach EXCEPT the
    // gateway trio — a reverse proxy answering 502/503/504 for a dead
    // backend is still "offline" as far as fresh data is concerned.
    const res = await fetch('/api/me', { method: 'HEAD', cache: 'no-store', signal: controller.signal })
    if ([502, 503, 504].includes(res.status)) {
      update({ status: 'offline' })
    } else {
      update({ status: Date.now() - started > SLOW_MS ? 'degraded' : 'live' })
    }
  } catch {
    update({ status: 'offline' })
  } finally {
    clearTimeout(timer)
  }
}

let started = false

/** Idempotent; the pill starts it on mount. Pings immediately, then every
 *  30s, plus on online/offline flips and on returning to the foreground
 *  (iOS relaunches the PWA constantly — the pill should be honest within
 *  a beat of the app reappearing, not 30s later). */
export function startConnectivityMonitor() {
  if (started) return
  started = true
  void ping()
  setInterval(() => void ping(), HEARTBEAT_MS)
  window.addEventListener('online', () => void ping())
  window.addEventListener('offline', () => update({ status: 'offline' }))
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible') void ping()
  })
}

/** Non-React subscription (scratchpadSync replays offline ink on the
 *  offline→live flip). Returns the unsubscribe. */
export function subscribeConnectivity(notify: () => void): () => void {
  listeners.add(notify)
  return () => {
    listeners.delete(notify)
  }
}

export function getConnectivity(): Connectivity {
  return snapshot
}

export function useConnectivity(): Connectivity {
  return useSyncExternalStore(subscribeConnectivity, getConnectivity)
}
