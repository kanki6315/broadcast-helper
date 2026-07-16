// Typed fetch layer for the browse/season endpoints. All read-only.

export interface SeasonSummary {
  id: number
  year: number
  seriesName: string
  roundCount: number
  championshipCount: number
}

export interface SeriesInfo {
  id: number
  name: string
  abbreviation: string | null
  aliases: string[]
}

export interface ClassStyle {
  classCode: string
  ordinal: number
  color: string
}

export interface ClassStylesResponse {
  styles: ClassStyle[]
  unconfiguredClasses: string[]
}

export interface CalendarEvent {
  id: number
  name: string
  circuitName: string | null
  eventDate: string | null
  roundOrdinal: number | null
  entryCount: number
  sessionCount: number
}

export interface ChampionshipSummary {
  id: number
  title: string
  groupTitle: string | null
  className: string | null
  kind: string | null
  isCup: boolean
  year: number
  seasonId: number
  seriesName: string
  rowCount: number
}

export interface SeasonHub {
  id: number
  year: number
  seriesId: number
  seriesName: string
  events: CalendarEvent[]
  championships: ChampionshipSummary[]
  /** Distinct entry class names present this season — the UI only offers
   * classes that can answer. */
  entryClasses: string[]
}

/* -- recap ------------------------------------------------------------- */

export interface RecapRound {
  round: number
  venue: string
  eventId: number | null
  raceCount: number
}

export interface RecapRace {
  race: number
  start: number | null
  finish: number | null
  status: string | null
  notFinished: boolean
}

export interface RecapRow {
  position: number
  competitorKey: string
  competitorName: string | null
  carNumber: string | null
  teamName: string | null
  totalPoints: number
  pointsByRound: Record<number, number>
  cells: Record<number, RecapRace[]>
}

export interface Recap {
  championship: {
    id: number
    title: string
    className: string | null
    kind: string | null
    family: string
    isCup: boolean
    seasonId: number
    year: number
    seriesName: string
  }
  rounds: RecapRound[]
  rows: RecapRow[]
}

/* -- lineups ------------------------------------------------------------ */

export interface LineupRound {
  ordinal: number
  venue: string
  eventId: number
  eventName: string
  eventDate: string | null
}

export interface LineupDriver {
  name: string
  rating: string | null
  country: string | null
  isTbd: boolean
}

export interface LineupCar {
  carNumber: string
  teamName: string | null
  isGuest: boolean
  byRound: Record<number, LineupDriver[]>
}

export interface LineupClass {
  className: string
  color: string
  cars: LineupCar[]
}

export interface Lineups {
  seasonId: number
  rounds: LineupRound[]
  classes: LineupClass[]
}

/* -- event results -------------------------------------------------------- */

export interface ResultRow {
  posOverall: number | null
  posInClass: number | null
  carNumber: string
  className: string | null
  teamName: string | null
  drivers: string | null
  vehicle: string | null
  status: string | null
  laps: number | null
  elapsedTime: string | null
  gapFirst: string | null
  fastestLapTime: string | null
  pitStops: number | null
}

export interface GridRow {
  posOverall: number | null
  posInClass: number | null
  carNumber: string
  className: string | null
  teamName: string | null
  qualifyingTime: string | null
}

export interface SessionResults {
  sessionId: number
  sessionType: 'QUALIFYING' | 'RACE'
  name: string
  results: ResultRow[]
  grid: GridRow[]
}

export interface EventResults {
  eventId: number
  eventName: string
  circuitName: string | null
  eventDate: string | null
  roundOrdinal: number | null
  seasonId: number
  year: number
  seriesName: string
  sessions: SessionResults[]
}

/* -- season reference (existing endpoint) ----------------------------------- */

export interface RefRound {
  ordinal: number
  venue: string
  circuitName: string | null
  eventId: number
  raceCount: number
}

export interface RefRace {
  raceOrdinal: number
  start: number | null
  finish: number | null
  status: string | null
}

export interface RefEntry {
  carNumber: string
  team: string | null
  isGuest: boolean
  byRound: Record<number, RefRace[]>
}

export interface RefClass {
  className: string
  color: string
  entries: RefEntry[]
}

export interface ReferenceTable {
  seasonId: number
  year: number
  seriesName: string
  rounds: RefRound[]
  classes: RefClass[]
}

/* -- driver profile ------------------------------------------------------- */

export interface DriverSearchHit {
  id: number
  name: string
  country: string | null
  rating: string | null
  carNumber: string | null
  teamName: string | null
  className: string | null
  year: number | null
  seriesName: string | null
}

export interface DriverChampMatrix {
  championshipId: number
  title: string
  className: string | null
  seriesName: string
  year: number
  seasonId: number
  position: number
  totalPoints: number
  carNumber: string | null
  teamName: string | null
  rounds: RecapRound[]
  cells: Record<number, RecapRace[]>
  pointsByRound: Record<number, number>
}

export interface DriverProfile {
  id: number
  name: string
  country: string | null
  hometown: string | null
  dateOfBirth: string | null
  placeOfBirth: string | null
  pronunciation: string | null
  notes: string | null
  photoVersion: number | null
  rating: string | null
  carNumber: string | null
  teamName: string | null
  className: string | null
  year: number | null
  seriesName: string | null
  championships: DriverChampMatrix[]
}

/* -- team profile ----------------------------------------------------------- */

export interface TeamSearchHit {
  teamName: string
  carNumbers: string | null
  classNames: string | null
  year: number | null
  seriesName: string | null
}

export interface SearchResults {
  drivers: DriverSearchHit[]
  teams: TeamSearchHit[]
}

export interface TeamRosterDriver {
  driverId: number | null
  name: string
  rating: string | null
  isTbd: boolean
}

export interface TeamRosterCar {
  entryId: number
  carNumber: string
  className: string
  classColor: string
  vehicle: string | null
  imageVersion: number | null
  drivers: TeamRosterDriver[]
}

export interface TeamRosterSeason {
  seasonId: number
  year: number
  seriesName: string
  eventName: string
  cars: TeamRosterCar[]
}

export interface TeamChampEntry {
  carNumber: string
  position: number
  totalPoints: number
  cells: Record<number, RecapRace[]>
  pointsByRound: Record<number, number>
}

export interface TeamChampMatrix {
  championshipId: number
  title: string
  className: string | null
  seriesName: string
  year: number
  seasonId: number
  rounds: RecapRound[]
  entries: TeamChampEntry[]
}

export interface TeamProfile {
  name: string
  notes: string | null
  roster: TeamRosterSeason[]
  championships: TeamChampMatrix[]
}

export async function getJson<T>(url: string): Promise<T> {
  const r = await fetch(url)
  if (!r.ok) throw new Error(`Backend returned ${r.status}`)
  return (await r.json()) as T
}
