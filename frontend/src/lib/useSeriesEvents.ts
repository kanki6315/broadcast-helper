import { useEffect, useState } from 'react'
import { reportApiResponse } from './connectivity'

/** A series row from GET /api/series. */
export interface Series {
  id: number
  name: string
  abbreviation: string | null
  aliases: string[]
}

/** The subset of GET /api/events an import target needs. The endpoint returns
 *  more (session/entry counts); those are ignored here. */
export interface EventOption {
  id: number
  name: string
  circuitName: string | null
  eventDate: string | null
  year: number
  seriesName: string
  /** The owning season's kind: events in a QUALIFIER season never mix into
   *  round previews or name de-collision (imports land in MAIN seasons), but
   *  stay pickable — attaching to one is how a flipped season gets more data. */
  seasonKind: 'MAIN' | 'QUALIFIER'
  seasonLabel: string | null
}

/**
 * Loads the series and events lists once (both are small enough to filter
 * client-side) and shares them across the import surfaces — the SeriesEventPicker
 * typeaheads and the ConfirmImportStep grouping. Returns null while loading so
 * callers can show a skeleton, and `addSeries` to fold in a just-created series.
 */
export function useSeriesEvents(onError?: (msg: string) => void) {
  const [allSeries, setAllSeries] = useState<Series[] | null>(null)
  const [allEvents, setAllEvents] = useState<EventOption[] | null>(null)

  useEffect(() => {
    void (async () => {
      try {
        const [sRes, eRes] = await Promise.all([fetch('/api/series'), fetch('/api/events')])
        if (sRes.ok) reportApiResponse(sRes)
        if (eRes.ok) reportApiResponse(eRes)
        setAllSeries(sRes.ok ? ((await sRes.json()) as Series[]) : [])
        setAllEvents(eRes.ok ? ((await eRes.json()) as EventOption[]) : [])
      } catch {
        setAllSeries([])
        setAllEvents([])
        onError?.('Could not load series and events.')
      }
    })()
    // Fetch once on mount; onError identity is irrelevant.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  function addSeries(s: Series) {
    setAllSeries((prev) => [...(prev ?? []), s].sort((a, b) => a.name.localeCompare(b.name)))
  }

  return { allSeries, allEvents, addSeries }
}
