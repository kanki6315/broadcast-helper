import { useMemo, useSyncExternalStore, type ReactNode } from 'react'
import './timing.css'
import { onShareChange, shareRejected } from '../lib/shareLink'
import { TimingNavContext, sharedNav } from '../lib/timingNav'
import TimingHomePage from './TimingHomePage'
import TimingPage from './TimingPage'

/**
 * The shareable timing link: `#/live/<token>` (the timing home) and
 * `#/live/<token>/weekend/<id>` (one series weekend). Signed-out and
 * chrome-less; the token in the URL rides on every API call (lib/shareLink.ts) and
 * reads what a member reads, the scratchpads apart. A revoked or replaced link answers 401, and
 * the page says so instead of sending anyone to sign in.
 */
export default function SharedTiming({ token, feedEventDbId }: { token: string; feedEventDbId?: number }) {
  const nav = useMemo(() => sharedNav(token), [token])
  const rejected = useSyncExternalStore(onShareChange, shareRejected)

  if (rejected) {
    return (
      <Frame>
        <div className="timing">
          <header className="timing-head">
            <h1>Timing</h1>
          </header>
          <div className="empty-state" role="alert">
            This timing link no longer works. Ask whoever shared it for the new one.
          </div>
        </div>
      </Frame>
    )
  }
  return (
    <TimingNavContext.Provider value={nav}>
      <Frame>
        {feedEventDbId != null ? (
          <TimingPage scope={{ kind: 'weekend', feedEventDbId }} />
        ) : (
          <TimingHomePage />
        )}
      </Frame>
    </TimingNavContext.Provider>
  )
}

function Frame({ children }: { children: ReactNode }) {
  return (
    <>
      {children}
      <p className="timing-credit">Timing data © Al Kamel Systems</p>
    </>
  )
}
