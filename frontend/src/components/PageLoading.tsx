import { useEffect, useState } from 'react'
import './page-loading.css'

/** An indeterminate wait: the lights signal activity, never completion. */
export default function PageLoading({ label }: { label: string }) {
  const [wait, setWait] = useState<'short' | 'warming' | 'long'>('short')
  const [hidden, setHidden] = useState(() => document.hidden)

  useEffect(() => {
    const warming = window.setTimeout(() => setWait('warming'), 3_000)
    const long = window.setTimeout(() => setWait('long'), 15_000)
    const visibilityChanged = () => setHidden(document.hidden)
    document.addEventListener('visibilitychange', visibilityChanged)
    return () => {
      window.clearTimeout(warming)
      window.clearTimeout(long)
      document.removeEventListener('visibilitychange', visibilityChanged)
    }
  }, [])

  return (
    <div className="page-loading" data-paused={hidden || undefined}>
      <div className="page-loading-signal" aria-hidden="true">
        <div className="page-loading-lights">
          {Array.from({ length: 5 }, (_, index) => <span key={index} />)}
        </div>
        <div className="page-loading-track"><span /></div>
      </div>
      <div role="status" aria-live="polite" aria-atomic="true">
        <p className="page-loading-label">{label}</p>
        <p className="page-loading-hint">
          {wait === 'long'
            ? 'Still waiting for the server. Your page will open when it responds.'
            : wait === 'warming'
              ? 'The server may be waking up. The first connection can take a few seconds.'
              : 'Waiting for your race data…'}
        </p>
      </div>
    </div>
  )
}
