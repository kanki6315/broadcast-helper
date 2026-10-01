// The shareable timing link (`#/live/<token>`). While a shared page is open,
// every /api request carries the token as X-Pit-Pass-Share (added by the
// global fetch wrapper in authRedirect.ts), so the timing components need no
// changes to work signed-out. The token is read from the URL fragment at the
// moment of each request — nothing to set or forget as pages mount — and
// browsers never send the part after `#` to a server, so it stays out of
// access logs and Referer headers.
//
// A 401 while shared means the link was revoked or replaced: the wrapper
// reports it here instead of bouncing to the login screen, and the shared
// page says the link no longer works.

export const SHARE_HEADER = 'X-Pit-Pass-Share'

const SHARED = /^#\/live\/([^/?]+)/

let rejectedToken: string | null = null
const listeners = new Set<() => void>()

/** The link's token when a shared page is open, else null. */
export function shareToken(): string | null {
  const m = SHARED.exec(window.location.hash)
  return m ? decodeURIComponent(m[1]) : null
}

/** Whether the open link has been refused (revoked or replaced). */
export function shareRejected(): boolean {
  return rejectedToken != null && rejectedToken === shareToken()
}

export function reportShareRejected(token: string) {
  if (rejectedToken === token) return
  rejectedToken = token
  listeners.forEach((l) => l())
}

export function onShareChange(listener: () => void): () => void {
  listeners.add(listener)
  return () => listeners.delete(listener)
}
