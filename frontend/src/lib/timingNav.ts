import { createContext, useContext } from 'react'

/**
 * Where the timing pages link to. Signed in, that is /timing and its event
 * and weekend pages. On the shareable link (`#/live/<token>`) every link stays
 * under the link — the shared view has weekend pages only, since an event page
 * reads Pit Pass's event, which the link does not open — so `event` is null
 * and callers link the series weekend instead.
 */
export interface TimingNav {
  shared: boolean
  home: string
  weekend: (feedEventDbId: number) => string
  event: ((eventId: number) => string) | null
}

export const MEMBER_NAV: TimingNav = {
  shared: false,
  home: '#/timing',
  weekend: (id) => `#/timing/weekend/${id}`,
  event: (id) => `#/timing/${id}`,
}

export function sharedNav(token: string): TimingNav {
  const base = `#/live/${encodeURIComponent(token)}`
  return { shared: true, home: base, weekend: (id) => `${base}/weekend/${id}`, event: null }
}

export const TimingNavContext = createContext<TimingNav>(MEMBER_NAV)

export function useTimingNav(): TimingNav {
  return useContext(TimingNavContext)
}
