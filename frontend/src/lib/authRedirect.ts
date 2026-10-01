// Global 401 → login screen.
//
// The app checks auth once on load (Layout's /api/me call), so a session that
// expires mid-use would otherwise just surface API errors on whatever page you're
// on. Wrap fetch so any 401 from our API routes the browser back to the app root,
// where the Layout renders the minimal login screen (its /api/me now returns no
// email). We deliberately do NOT bounce straight to Google — re-login is always an
// explicit button click. Installed once, before the app renders.
//
// Only triggers on a real 401 (which only happens when auth is enabled and the
// session is gone), and skips /api/me itself (it's public, so it never 401s, but
// excluding it avoids any chance of a redirect loop).

import { SHARE_HEADER, reportShareRejected, shareToken } from './shareLink'

const nativeFetch = window.fetch.bind(window)
let redirecting = false

function urlOf(input: RequestInfo | URL): string {
  if (typeof input === 'string') return input
  if (input instanceof URL) return input.href
  if (input instanceof Request) return input.url
  return String(input)
}

window.fetch = async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
  const url = urlOf(input)
  // On a shared timing page (lib/shareLink.ts): our API calls carry the link's token,
  // and a 401 means the link stopped working — the page says so; no login bounce.
  const shared = shareToken()
  if (shared && url.includes('/api/')) {
    const headers = new Headers(init?.headers ?? (input instanceof Request ? input.headers : undefined))
    headers.set(SHARE_HEADER, shared)
    const res = await nativeFetch(input, { ...init, headers })
    if (res.status === 401) reportShareRejected(shared)
    return res
  }
  const res = await nativeFetch(input, init)
  if (res.status === 401 && url.includes('/api/') && !url.includes('/api/me') && !redirecting) {
    redirecting = true
    // Back to the app root; Layout re-checks /api/me (now signed out) and shows the
    // login screen. Not a straight jump to Google — re-login is an explicit click.
    window.location.href = '/'
  }
  return res
}
