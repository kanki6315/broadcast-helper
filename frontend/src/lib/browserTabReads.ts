// Browser tabs read fresh; the installed app reads cached.
//
// Usage model (user's call, 2026-07-31): all editing happens in normal
// browser tabs at a desk; the installed iPad PWA is a read-only device
// (plus the scratchpad). Stale-while-revalidate is therefore right on the
// iPad — instant paints on trackside internet — and wrong in a browser tab,
// where a commit should be followed by reading your own write, not a cached
// snapshot and a "newer data" nudge.
//
// A service worker is registered per ORIGIN, so once the installed app has
// run, plain browser tabs are controlled by the very same worker — there is
// no platform lever to keep them out of its caches. The lever is
// per-request instead: when this page is NOT running standalone, every
// same-origin /api read is tagged with a header, and the api-data
// stale-while-revalidate route in vite.config.ts declines tagged requests
// (no route match → plain network). Untagged = cached is the deliberate
// polarity: if the tag ever fails to install, the app degrades to
// offline-capable, never to offline-broken on the device at the track.
//
// Only GET/HEAD /api requests are tagged: writes are never cached, and the
// NetworkFirst routes (api-me, scratchpad) never serve stale while online,
// so they need no gate. String/URL inputs only — a Request-object input
// passes through untouched (rebuilding one risks locking its body, and no
// call site here reads the API that way).

export const isStandalone =
  window.matchMedia('(display-mode: standalone)').matches ||
  ('standalone' in navigator && (navigator as { standalone?: boolean }).standalone === true)

/** Checked (case-insensitively) by the api-data matcher in vite.config.ts —
 *  keep the two in sync. */
export const BROWSER_TAB_HEADER = 'X-Pit-Pass-Browser-Tab'

export function tagBrowserTabApiReads(): void {
  if (isStandalone) return
  const wrapped = window.fetch.bind(window)
  window.fetch = (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    if (typeof input === 'string' || input instanceof URL) {
      const url = new URL(input, window.location.href)
      const method = (init?.method ?? 'GET').toUpperCase()
      if (
        url.origin === window.location.origin &&
        url.pathname.startsWith('/api/') &&
        (method === 'GET' || method === 'HEAD')
      ) {
        const headers = new Headers(init?.headers)
        headers.set(BROWSER_TAB_HEADER, '1')
        return wrapped(input, { ...init, headers })
      }
    }
    return wrapped(input, init)
  }
}
