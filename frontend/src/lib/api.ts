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
  /** Epoch-millis stamp of the current series logo, or null when none uploaded. */
  logoVersion: number | null
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
  /** The round's points-scoring sessions in calendar order — the standings
   * page prints one earnings line per session, not one summed number. */
  sessions: RecapSession[]
  /** Every race the round ran, in running order. Result lines are tagged from
   * this list, not from the races a given competitor contested, so someone who
   * ran only the fourth heat is labelled "H4" rather than "H". */
  races: RecapRaceRef[]
}

export interface RecapRaceRef {
  ordinal: number
  name: string | null
}

export interface RecapSession {
  sessionIndex: number
  name: string
}

export interface RecapRace {
  race: number
  /** The race session's own name ("Heat 1", "Feature"), abbreviated into a
   * per-line tag where a round ran more than one race. Null for rounds
   * imported before the name was carried. */
  name: string | null
  start: number | null
  finish: number | null
  status: string | null
  notFinished: boolean
}

/** How one session paid: components sum to total. contested is false for
 * did_not_race — that session shows a skip mark, not a 0. */
export interface RecapSessionPoints {
  total: number
  race: number
  pole: number
  fastestLap: number
  penalty: number
  bonus: number
  contested: boolean
}

export interface RecapRow {
  position: number
  competitorKey: string
  competitorName: string | null
  carNumber: string | null
  teamName: string | null
  /** Distinct teams represented during the season, ordered by first appearance. */
  teamNames?: string[]
  totalPoints: number
  pointsByRound: Record<number, number>
  /** Keyed by RecapSession.sessionIndex; only sessions of scored, contested rounds. */
  sessionPoints: Record<number, RecapSessionPoints>
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
    /** Scores the whole field across classes; cells carry overall positions. */
    isOverall: boolean
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
  /** Crew member who set this entry's fastest lap of the session. NOT the
   *  qualifying driver — see the backend record for why the two differ. */
  fastestLapDriver: string | null
  /** Qualifying driver of record, from the grid file's attribution. Null for
   *  grids imported before V27 (re-import to fill) and iRacing sources. */
  qualifyingDriver: string | null
  vehicle: string | null
  status: string | null
  laps: number | null
  elapsedTime: string | null
  gapFirst: string | null
  fastestLapTime: string | null
  /** Lap the fastest lap was set on. */
  fastestLapNumber: number | null
  pitStops: number | null
}

export interface GridRow {
  posOverall: number | null
  posInClass: number | null
  carNumber: string
  className: string | null
  teamName: string | null
  qualifyingTime: string | null
  /** Crew member taking the start, per the grid file. Null for pre-V27 grids
   *  and sources that name no one (iRacing). */
  startingDriver: string | null
  /** Qualifying driver of record, per the grid file. */
  qualifyingDriver: string | null
}

/** One stewards' note line, tagged with the car numbers it names (may be empty). */
export interface SessionNote {
  text: string
  carNumbers: string[]
}

export interface SessionResults {
  sessionId: number
  sessionType: 'QUALIFYING' | 'RACE'
  name: string
  /** 'Official' | 'Provisional' | 'Unofficial' | null — the report's status. */
  reportMark: string | null
  notes: SessionNote[]
  /** True when a flags/RC-message stream was imported for this session. */
  hasFlags: boolean
  results: ResultRow[]
  grid: GridRow[]
}

/** One record of a session's flag/RC-message stream, in source order. */
export interface FlagRecord {
  seq: number
  wallTime: string | null
  elapsed: string | null
  /** 'GF' | 'FCY' | 'FF' | 'RCMessage' | future provider values verbatim. */
  recType: string
  flag: string | null
  message: string | null
  flagTime: string | null
  accumTime: string | null
  lap: number | null
  /** Car numbers the message names, extracted server-side (empty for flag records). */
  carNumbers: string[]
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

/* -- stats ------------------------------------------------------------------ */

export interface FormatInfo {
  id: number | null
  name: string
  ordinal: number
}

export interface FormatLine {
  formatId: number | null
  starts: number
  wins: number
  podiums: number
  top5s: number
  dnfs: number
}

export interface QualiLine {
  sessions: number
  poles: number
  top5s: number
}

export interface DriverStatsRow {
  driverId: number
  driverName: string
  className: string
  carNumber: string | null
  teamName: string | null
  byFormat: FormatLine[]
  quali: QualiLine
}

export interface StatsTable {
  formats: FormatInfo[]
  rows: DriverStatsRow[]
}

export interface NamedFormatLine {
  formatId: number | null
  formatName: string
  starts: number
  wins: number
  podiums: number
  top5s: number
  dnfs: number
}

export interface SeasonStatLine {
  seasonId: number
  year: number
  seriesName: string
  className: string
  byFormat: NamedFormatLine[]
  quali: QualiLine
}

export interface SeriesStatLine {
  seriesId: number
  seriesName: string
  byFormat: NamedFormatLine[]
  quali: QualiLine
}

export interface CareerTotals {
  starts: number
  wins: number
  podiums: number
  top5s: number
  poles: number
  qualiTop5s: number
  dnfs: number
}

export interface DriverStats {
  driverId: number
  career: CareerTotals
  bySeries: SeriesStatLine[]
  seasons: SeasonStatLine[]
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
