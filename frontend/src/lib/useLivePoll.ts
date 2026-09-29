import { useEffect, useState } from 'react'

/**
 * A document only worth having while it is current: polled, never cached. The
 * live endpoints carry no timestamps, so the API's content ETag answers 304
 * until the order actually changes — If-None-Match is sent by hand because
 * /api responses are no-store and the browser keeps nothing to revalidate.
 *
 * `path` null = not polling. Polling pauses while the tab is hidden. A failed
 * poll keeps the last value and reports the error; the next success clears it.
 * Changing `refreshKey` polls again at once (after a save), keeping the value
 * on screen until the answer lands.
 */
export function useLivePoll<T>(path: string | null, intervalMs = 3000, refreshKey?: unknown): { value: T | null; error: string | null } {
  const [state, setState] = useState<{ path: string | null; value: T | null; error: string | null }>({ path, value: null, error: null })
  useEffect(() => {
    if (!path) return
    let cancelled = false
    let etag: string | null = null
    let timer: ReturnType<typeof setTimeout> | undefined
    let first = true
    let inFlight = false
    const tick = async () => {
      clearTimeout(timer)
      inFlight = true
      // The first poll runs even in a hidden tab (a page opened in the
      // background must not sit on a skeleton), later ones only while visible.
      if (first || !document.hidden) {
        first = false
        try {
          const response = await fetch(path, etag ? { headers: { 'If-None-Match': etag } } : undefined)
          if (cancelled) return
          if (response.status === 304) {
            setState(old => old.error ? { ...old, error: null } : old)
          } else if (response.ok) {
            const value = (await response.json()) as T
            etag = response.headers.get('ETag')
            if (!cancelled) setState({ path, value, error: null })
          } else {
            setState(old => ({ ...old, path, error: `Backend returned ${response.status}` }))
          }
        } catch (e) {
          if (!cancelled) setState(old => ({ ...old, path, error: String((e as Error).message ?? e) }))
        }
      }
      inFlight = false
      if (!cancelled) timer = setTimeout(tick, intervalMs)
    }
    // Coming back to the tab polls at once rather than at the next tick.
    const onVisible = () => {
      // Mid-poll, the poll in flight schedules the next one; a second chain would double the rate.
      if (!document.hidden && !cancelled && !inFlight) void tick()
    }
    document.addEventListener('visibilitychange', onVisible)
    tick()
    return () => {
      cancelled = true
      clearTimeout(timer)
      document.removeEventListener('visibilitychange', onVisible)
    }
  }, [path, intervalMs, refreshKey])
  // A value fetched for another path is not this path's value.
  return state.path === path ? { value: state.value, error: state.error } : { value: null, error: null }
}
