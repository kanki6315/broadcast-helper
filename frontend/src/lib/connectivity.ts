/**
 * Backend-reachability heartbeat. Its one remaining customer is the
 * scratchpad: the modal words a failed save as "offline" rather than an
 * error, and scratchpadSync replays stranded ink on the offline→live flip.
 * navigator.onLine is not trusted on its own — it happily reports "online"
 * on a connection too poor to use. (The header pill and the cache-age half
 * of this module went with the service worker; the iPad app has its own.)
 */

export type ConnectivityStatus = 'live' | 'degraded' | 'offline'

export interface Connectivity {
  status: ConnectivityStatus
}

const HEARTBEAT_MS = 30_000
const HEARTBEAT_TIMEOUT_MS = 4_000
/** A heartbeat that answers, but slower than this, reads as degraded. */
const SLOW_MS = 2_500

let snapshot: Connectivity = { status: 'live' }
const listeners = new Set<() => void>()

function update(next: Connectivity) {
  if (next.status === snapshot.status) return
  snapshot = next
  listeners.forEach((notify) => notify())
}

async function ping() {
  if (document.visibilityState !== 'visible') return
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), HEARTBEAT_TIMEOUT_MS)
  const started = Date.now()
  try {
    // cache:'no-store' keeps the browser HTTP cache out of it, so this is a
    // true network probe. Spring answers HEAD on any @GetMapping. Any status proves reach EXCEPT the
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
let heartbeat: ReturnType<typeof setInterval> | undefined

function pauseHeartbeat() {
  if (heartbeat !== undefined) {
    clearInterval(heartbeat)
    heartbeat = undefined
  }
}

function resumeHeartbeat() {
  pauseHeartbeat()
  if (document.visibilityState !== 'visible') return
  void ping()
  heartbeat = setInterval(() => void ping(), HEARTBEAT_MS)
}

/** Idempotent; scratchpadSync starts it. Pings immediately, then every
 *  30s while visible, and immediately on returning to the foreground. */
export function startConnectivityMonitor() {
  if (started) return
  started = true
  resumeHeartbeat()
  window.addEventListener('online', resumeHeartbeat)
  window.addEventListener('offline', () => {
    pauseHeartbeat()
    update({ status: 'offline' })
  })
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible') resumeHeartbeat()
    else pauseHeartbeat()
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
