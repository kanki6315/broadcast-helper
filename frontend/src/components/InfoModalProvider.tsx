import { useMemo, useState, type ReactNode } from 'react'
import { getJson, type DriverSearchHit } from '../lib/api'
import { InfoModalCtx, type InfoModalApi } from './infoModal'
import DriverModal from './DriverModal'
import TeamModal from './TeamModal'

type Open = { type: 'driver'; id: number } | { type: 'team'; name: string } | null

/** Hosts the driver and team info modals (one at a time — opening one from
 * inside the other replaces it, which reads as drill-through navigation). */
export default function InfoModalProvider({ children }: { children: ReactNode }) {
  const [open, setOpen] = useState<Open>(null)

  const api = useMemo<InfoModalApi>(
    () => ({
      openDriver: (id) => setOpen({ type: 'driver', id }),
      openDriverByName: (name) => {
        const wanted = name.trim().toLowerCase()
        if (!wanted || wanted === 'tbd') return
        void getJson<DriverSearchHit[]>(
          `/api/drivers/search?q=${encodeURIComponent(name.trim())}&limit=5`,
        )
          .then((hits) => {
            const hit = hits.find((h) => h.name.trim().toLowerCase() === wanted) ?? hits[0]
            if (hit) setOpen({ type: 'driver', id: hit.id })
          })
          .catch(() => {
            /* an unresolvable name stays a plain label */
          })
      },
      openTeam: (name) => {
        if (name.trim()) setOpen({ type: 'team', name: name.trim() })
      },
    }),
    [],
  )

  return (
    <InfoModalCtx.Provider value={api}>
      {children}
      {open?.type === 'driver' && (
        <DriverModal driverId={open.id} onClose={() => setOpen(null)} />
      )}
      {open?.type === 'team' && <TeamModal teamName={open.name} onClose={() => setOpen(null)} />}
    </InfoModalCtx.Provider>
  )
}
