import { useEffect, useState } from 'react'

// Chrome/Android fire this; iOS/iPadOS Safari does not (no programmatic
// install), so there we fall back to an instructional banner.
interface BeforeInstallPromptEvent extends Event {
  prompt: () => Promise<void>
  userChoice: Promise<{ outcome: 'accepted' | 'dismissed' }>
}

const DISMISS_KEY = 'bh-install-dismissed'

function isStandalone(): boolean {
  return (
    window.matchMedia('(display-mode: standalone)').matches ||
    // iOS Safari's non-standard flag for home-screen launches.
    (navigator as unknown as { standalone?: boolean }).standalone === true
  )
}

function isIosSafari(): boolean {
  const ua = navigator.userAgent
  // iPadOS 13+ masquerades as Mac, so also treat a touch-capable "Mac" as iPad.
  const iOS =
    /iphone|ipad|ipod/i.test(ua) ||
    (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1)
  return iOS && !/crios|fxios|edgios/i.test(ua)
}

/**
 * Nudges the user to install the PWA. Two paths:
 *  - iOS/iPadOS Safari: an instructional banner (no install API exists there).
 *  - Chrome/Android/desktop: a one-tap Install button via beforeinstallprompt.
 * Hidden once installed (standalone) or dismissed (remembered in localStorage,
 * same pattern as the bh-theme key).
 */
export default function InstallHint() {
  const [prompt, setPrompt] = useState<BeforeInstallPromptEvent | null>(null)
  const [dismissed, setDismissed] = useState(() => localStorage.getItem(DISMISS_KEY) === '1')

  useEffect(() => {
    function onPrompt(e: Event) {
      e.preventDefault() // keep the browser's own mini-infobar from showing
      setPrompt(e as BeforeInstallPromptEvent)
    }
    window.addEventListener('beforeinstallprompt', onPrompt)
    return () => window.removeEventListener('beforeinstallprompt', onPrompt)
  }, [])

  if (dismissed || isStandalone()) return null

  const showIos = isIosSafari()
  if (!prompt && !showIos) return null // no install path on this browser yet

  function close() {
    localStorage.setItem(DISMISS_KEY, '1')
    setDismissed(true)
  }

  async function install() {
    if (!prompt) return
    await prompt.prompt()
    await prompt.userChoice
    close()
  }

  return (
    <div className="install-hint" role="note">
      <span className="install-hint-text">
        {prompt ? (
          'Install Pit Pass for offline access trackside.'
        ) : (
          <>
            Install for offline access: tap <strong>Share</strong> then{' '}
            <strong>Add to Home Screen</strong>.
          </>
        )}
      </span>
      {prompt && (
        <button type="button" className="install-hint-action" onClick={install}>
          Install
        </button>
      )}
      <button
        type="button"
        className="install-hint-close"
        aria-label="Dismiss install prompt"
        onClick={close}
      >
        ×
      </button>
    </div>
  )
}
