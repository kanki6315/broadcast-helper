import { useRegisterSW } from 'virtual:pwa-register/react'

const UPDATE_CHECK_MS = 30 * 60 * 1000 // poll for a new deploy every 30 minutes

/**
 * Registers the service worker and surfaces a "new version" banner when a fresh
 * deploy is waiting. We use registerType:'prompt' (see vite.config) so the app
 * never reloads itself mid-use — the user taps Reload when it's safe. While the
 * app is open and foregrounded, it polls for updates every 30 minutes, so you
 * don't have to relaunch to pick a new build up.
 */
export default function UpdatePrompt() {
  const {
    needRefresh: [needRefresh, setNeedRefresh],
    updateServiceWorker,
  } = useRegisterSW({
    immediate: true,
    onRegisteredSW(_url, registration) {
      if (registration) {
        setInterval(() => void registration.update(), UPDATE_CHECK_MS)
      }
    },
  })

  if (!needRefresh) return null

  return (
    <div className="update-banner" role="status" aria-live="polite">
      <span>A new version is available.</span>
      <div className="update-banner-actions">
        <button type="button" className="update-banner-reload" onClick={() => void updateServiceWorker(true)}>
          Reload
        </button>
        <button type="button" className="update-banner-dismiss" onClick={() => setNeedRefresh(false)}>
          Later
        </button>
      </div>
    </div>
  )
}
