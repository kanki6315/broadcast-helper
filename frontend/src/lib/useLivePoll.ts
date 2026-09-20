import { useEffect, useState } from 'react'

/**
 * A document only worth having while it is current: polled, never cached. The
 * live endpoints carry no timestamps, so the API's content ETag answers 304
 * until the order actually changes — If-None-Match is sent by hand because
 * /api responses are no-store and the browser keeps nothing to revalidate.
 *
 * `path` null = not polling. Polling pauses while the tab is hidden. A failed
 * poll keeps the last value and reports the error; the next success clears it.
 */
export function useLivePoll<T>(path: string | null, intervalMs = 3000): { value: T | null; error: string | null } {
  const [state, setState] = useState<{ path: string | null; value: T | null; error: string | null }>({ path, value: null, error: null })
  useEffect(() => {
    if (!path) return
    let cancelled = false
    let etag: string | null = null
    let timer: ReturnType<typeof setTimeout> | undefined
    const tick = async () => {
      if (!document.hidden) {
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
      if (!cancelled) timer = setTimeout(tick, intervalMs)
    }
    tick()
    return () => { cancelled = true; clearTimeout(timer) }
  }, [path, intervalMs])
  // A value fetched for another path is not this path's value.
  return state.path === path ? { value: state.value, error: state.error } : { value: null, error: null }
}
