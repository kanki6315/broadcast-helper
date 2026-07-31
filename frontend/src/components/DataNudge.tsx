import { useEffect, useRef, useState } from 'react'

/** After a dismiss, stay quiet this long even if more updates arrive. */
const SNOOZE_MS = 5 * 60 * 1000

/**
 * Surfaces the service worker's BroadcastUpdate messages — posted when a
 * stale-while-revalidate refetch found an /api payload actually changed
 * (see the api-data route in vite.config.ts). Deliberately a nudge, not a
 * silent re-render: broadcast prep must never shuffle under the reader
 * mid-sentence. Refresh repaints from the just-updated cache, so it is
 * instant even on trackside internet.
 */
export default function DataNudge() {
  const [show, setShow] = useState(false)
  const snoozedUntil = useRef(0)

  useEffect(() => {
    if (!('serviceWorker' in navigator)) return
    function onMessage(e: MessageEvent) {
      const data = e.data as {
        type?: string
        meta?: string
        payload?: { cacheName?: string }
      } | null
      if (
        data?.type === 'CACHE_UPDATED' &&
        data.meta === 'workbox-broadcast-update' &&
        data.payload?.cacheName === 'api-data' &&
        Date.now() >= snoozedUntil.current
      ) {
        setShow(true)
      }
    }
    navigator.serviceWorker.addEventListener('message', onMessage)
    return () => navigator.serviceWorker.removeEventListener('message', onMessage)
  }, [])

  if (!show) return null

  return (
    <div className="update-banner data-nudge" role="status" aria-live="polite">
      <span>Newer data is available.</span>
      <div className="update-banner-actions">
        <button
          type="button"
          className="update-banner-reload"
          onClick={() => window.location.reload()}
        >
          Refresh
        </button>
        <button
          type="button"
          className="update-banner-dismiss"
          onClick={() => {
            snoozedUntil.current = Date.now() + SNOOZE_MS
            setShow(false)
          }}
        >
          Dismiss
        </button>
      </div>
    </div>
  )
}
