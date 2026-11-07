import type { Recap, RecapRow, StatsTable } from './api'

export const hasMultiSeasonRecap = (name: string) => name === 'FIA F4 Esports Global Championship'

export interface HistorySeason {
  id: number
  year: number
  recap: Recap | null
  stats: StatsTable
  driverIds: Record<string, number>
}
export interface SeriesHistory {
  seriesId: number
  seriesName: string
  seasons: HistorySeason[]
}
export interface HistoryDriver {
  key: string
  driverId: number | null
  name: string
  statsAvailable: boolean
  starts: number
  wins: number
  podiums: number
  poles: number
  qualifyingSessions: number
  years: Map<number, { recap?: RecapRow; starts?: number; wins?: number; podiums?: number }>
}

/** Aggregate by canonical driver, never car number or team. Unresolved standings
 * remain visible and season-scoped; unknown stats must not turn into zero. */
export function historyDrivers(seasons: HistorySeason[]): HistoryDriver[] {
  const drivers = new Map<string, HistoryDriver>()
  function get(key: string, driverId: number | null, name: string) {
    let row = drivers.get(key)
    if (!row) {
      row = { key, driverId, name, statsAvailable: false, starts: 0, wins: 0, podiums: 0, poles: 0, qualifyingSessions: 0, years: new Map() }
      drivers.set(key, row)
    }
    return row
  }
  for (const season of seasons) {
    for (const st of season.stats.rows) {
      const row = get(`driver:${st.driverId}`, st.driverId, st.driverName)
      row.statsAvailable = true
      const year = row.years.get(season.id) ?? {}
      for (const f of st.byFormat) {
        row.starts += f.starts; row.wins += f.wins; row.podiums += f.podiums
        year.starts = (year.starts ?? 0) + f.starts
        year.wins = (year.wins ?? 0) + f.wins
        year.podiums = (year.podiums ?? 0) + f.podiums
      }
      row.poles += st.quali.poles
      row.qualifyingSessions += st.quali.sessions
      row.years.set(season.id, year)
    }
    for (const recap of season.recap?.rows ?? []) {
      const id = season.driverIds[recap.competitorKey] ?? null
      const key = id == null ? `unresolved:${season.id}:${recap.competitorKey}` : `driver:${id}`
      const row = get(key, id, recap.competitorName ?? recap.competitorKey)
      row.years.set(season.id, { ...row.years.get(season.id), recap })
    }
  }
  return [...drivers.values()]
}
