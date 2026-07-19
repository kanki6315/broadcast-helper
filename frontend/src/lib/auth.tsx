import { createContext, useContext, useEffect, useState, type ReactNode } from 'react'

export interface Me {
  authEnabled: boolean
  email: string | null
  isAdmin: boolean
}

const MeContext = createContext<Me | null>(null)

/**
 * Owns the single /api/me fetch and shares the answer app-wide. Mounted in
 * App.tsx above the routes, so the standalone sheet route and the ⌘K info
 * modals (both outside Layout's outlet) see the same auth state.
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  const [me, setMe] = useState<Me | null>(null)
  useEffect(() => {
    void fetch('/api/me')
      .then((r) => (r.ok ? r.json() : Promise.reject(new Error(String(r.status)))))
      .then(setMe)
      // Backend unreachable → behave like dev mode (auth off = full UI).
      .catch(() => setMe({ authEnabled: false, email: null, isAdmin: true }))
  }, [])
  return <MeContext.Provider value={me}>{children}</MeContext.Provider>
}

/** null while /api/me is in flight. */
export function useMe(): Me | null {
  return useContext(MeContext)
}

export function useIsAdmin(): boolean {
  const me = useContext(MeContext)
  return me?.isAdmin ?? false
}
