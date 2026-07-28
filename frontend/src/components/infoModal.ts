import { createContext, useContext } from 'react'

/** Opens the driver/team info modals from anywhere in the app. Lives in its
 * own module so the provider and the modals can share it without an import
 * cycle. */
export interface InfoModalApi {
  openDriver: (id: number) => void
  /** Open by the exact full name printed in tables (drivers are unique by
   * name in the DB). Silently no-ops if the name doesn't resolve (TBD seats,
   * unparsed lines). */
  openDriverByName: (name: string) => void
  /** Teams are name-keyed — the backend normalizes casing/whitespace. */
  openTeam: (name: string) => void
  /** Open a team by its entity id (stats tables, lineage links). */
  openTeamById: (id: number) => void
}

export const InfoModalCtx = createContext<InfoModalApi | null>(null)

export function useInfoModal(): InfoModalApi {
  const api = useContext(InfoModalCtx)
  if (!api) throw new Error('useInfoModal outside InfoModalProvider')
  return api
}
