/**
 * The live timing page's API shapes (backend: live/LiveTimingPageService,
 * DriveTime, DriveTimeRuleController) and the formatting every cell of it
 * goes through. Pure, so the formats are one decision, not one per cell.
 */

export type LiveState = 'NOT_CONFIGURED' | 'OFF' | 'STANDBY' | 'CONNECTING' | 'LIVE' | 'BACKING_OFF'

/** `GET /api/live/status`, as far as the connect control needs it. */
export interface LiveStatus {
  state: LiveState
  configured: boolean
  desiredConnected: boolean
  /** The event the connection is bound to: a filing hint, and where the connect control stands. */
  eventId: number | null
  eventName: string | null
  /** The event the session on track is filed under; null = filed nowhere (the feed's own teams). */
  filedEventId: number | null
  filedEventName: string | null
  lastError: string | null
}

export interface FeedSession {
  championship: string | null
  event: string | null
  name: string | null
  type: string | null
  flag: string | null
  running: boolean
  finished: boolean
}

export interface TowerCar {
  position: number
  carNumber: string
  entryId: number | null
  teamName: string | null
  vehicle: string | null
  manufacturer: string | null
  status: string | null
  laps: number | null
  gapToLeaderMs: number | null
  gapToLeaderLaps: number | null
  intervalMs: number | null
  intervalLaps: number | null
  driverOrder: number | null
  driverName: string | null
  driverShortName: string | null
  driverRating: string | null
  lastLap: number | null
  lastLapMs: number | null
  bestLap: number | null
  bestLapMs: number | null
  inPit: boolean
  stintStartMs: number | null
  stintLaps: number | null
  /** IMSA telemetry, % remaining; null when off, unseen or stale. */
  energyPct: number | null
  /** energyPct over this stint's average use per lap; null until two laps are sampled. */
  energyLapsLeft: number | null
}

export interface TowerClass {
  className: string
  feedClass: string
  color: string | null
  cars: TowerCar[]
}

export interface Tower {
  state: LiveState
  eventId: number | null
  eventName: string | null
  session: FeedSession | null
  sessionDbId: number | null
  feedClockMs: number | null
  classes: TowerClass[]
  matched: number
  total: number
  /** Where teams, colours and drivers come from; null = filed nowhere, so they are the feed's own. */
  filedEventId: number | null
  filedEventName: string | null
}

export interface LapRow {
  lap: number
  driverOrder: number | null
  driverLap: number | null
  position: number | null
  startTimeMs: number | null
  lapTimeMs: number | null
  sectorMs: (number | null)[] | null
  sectorFlags: (string | null)[] | null
  valid: boolean | null
  longLap: boolean | null
  shortLap: boolean | null
  trackLimits: number | null
  topSpeed: number | null
  pitInMs: number | null
  pitOutMs: number | null
  /** IMSA telemetry at the line after this lap, and the drop from the lap before (null over a refill). */
  energyPct: number | null
  energyUsedPct: number | null
}

export interface StintRow {
  startTimeMs: number
  type: string | null
  pitType: string | null
  driverOrder: number | null
  openLap: number | null
  closeLap: number | null
  finishTimeMs: number | null
  driverAccumSessionTrackMs: number | null
  driverAccumSessionMs: number | null
  driverAccumTrackMs: number | null
  driverAccumMs: number | null
  avgEnergyPerLapPct: number | null
}

export interface CarDriver {
  driverOrder: number
  firstName: string | null
  lastName: string | null
  shortName: string | null
  license: string | null
  rating: string | null
  driverId: number | null
}

export interface CarDetail {
  sessionDbId: number
  carNumber: string
  drivers: CarDriver[]
  laps: LapRow[]
  stints: StintRow[]
}

export interface DriveTimeRule {
  className: string
  rating: string | null
  minMs: number | null
  maxMs: number | null
  note: string | null
}

export type DriveStatus = 'OK' | 'UNDER_MIN' | 'OVER_MAX' | 'NO_RULE'

export interface DriveTimeResult {
  car: string
  driverOrder: number
  name: string | null
  rating: string | null
  driverId: number | null
  className: string | null
  driveMs: number
  inCar: boolean
  minMs: number | null
  maxMs: number | null
  status: DriveStatus
  owedMs: number | null
  remainingMs: number | null
  overMs: number | null
}

export interface DriveTimeResponse {
  sessionDbId: number
  eventId: number | null
  rules: DriveTimeRule[]
  drivers: DriveTimeResult[]
}

export interface SessionSummary {
  sessionDbId: number
  eventId: number | null
  name: string | null
  type: string | null
  dateMs: number | null
  laps: number
  cars: number
  current: boolean
}

// ---- analysis (backend: live/LiveAnalysisService, LiveAnalysis) -------------------------

export interface AnalysisCar {
  carNumber: string
  teamName: string | null
  className: string
}

/**
 * One car's gap to its class leader after each lap, laps firstLap… in order.
 * gapMs is null where the car was lapped (lapsDown > 0) or the lap untimed;
 * lapsDown is null only where the lap was untimed.
 */
export interface GapCar {
  carNumber: string
  firstLap: number
  gapMs: (number | null)[]
  lapsDown: (number | null)[]
  pitLaps: number[]
}

export interface GapClass {
  className: string
  color: string | null
  cars: AnalysisCar[]
  /** Running order: most laps, then earliest to finish the last. */
  gaps: GapCar[]
}

export interface GapsResponse {
  sessionDbId: number
  classes: GapClass[]
}

/** By sector, index 0 = S1; null where the car has no valid time. */
export interface SectorCar {
  carNumber: string
  bestSectorMs: (number | null)[]
  bestSectorLap: (number | null)[]
  bestLap: number | null
  bestLapMs: number | null
  theoreticalMs: number | null
}

export interface SectorClass {
  className: string
  color: string | null
  cars: AnalysisCar[]
  bests: { sectors: number; classBestSectorMs: (number | null)[]; cars: SectorCar[] }
}

export interface SectorsResponse {
  sessionDbId: number
  classes: SectorClass[]
}

export interface PitStop {
  number: number
  startTimeMs: number
  /** Pit-lane time; null while the car is still in. */
  durationMs: number | null
  lap: number | null
  pitType: string | null
  driverIn: number | null
  driverOut: number | null
  driverChange: boolean
}

export interface PitCar {
  carNumber: string
  stops: PitStop[]
  totalMs: number
  averageMs: number | null
  inPit: boolean
  lapsSinceStop: number | null
}

export interface PitClass {
  className: string
  color: string | null
  cars: AnalysisCar[]
  pits: PitCar[]
}

export interface PitsResponse {
  sessionDbId: number
  /** Car → driver order → surname. */
  drivers: Record<string, Record<string, string | null>>
  classes: PitClass[]
}

/** A gap in seconds for a chart axis or tooltip: +12.345, or +1 lap when lapped. */
export function gapAt(car: GapCar, lap: number): string | null {
  const i = lap - car.firstLap
  if (i < 0 || i >= car.gapMs.length) return null
  const down = car.lapsDown[i]
  if (down == null) return null
  if (down > 0) return `+${down} ${down === 1 ? 'lap' : 'laps'}`
  const ms = car.gapMs[i]
  return ms == null ? null : ms === 0 ? 'Leader' : `+${lapTime(ms)}`
}

export const RATINGS: { code: string; name: string }[] = [
  { code: 'B', name: 'Bronze' },
  { code: 'S', name: 'Silver' },
  { code: 'G', name: 'Gold' },
  { code: 'P', name: 'Platinum' },
]

const pad = (n: number, width = 2) => String(n).padStart(width, '0')

/** A lap or sector time: 1:38.765, or 38.765 under a minute; h:mm:ss.sss past an hour (a red-flag lap). */
export function lapTime(ms: number | null | undefined): string {
  if (ms == null || ms <= 0) return '—'
  const total = Math.round(ms)
  const h = Math.floor(total / 3_600_000)
  const m = Math.floor((total % 3_600_000) / 60_000)
  const s = Math.floor((total % 60_000) / 1000)
  const frac = pad(total % 1000, 3)
  if (h > 0) return `${h}:${pad(m)}:${pad(s)}.${frac}`
  if (m > 0) return `${m}:${pad(s)}.${frac}`
  return `${s}.${frac}`
}

/** A gap or interval: +4.200, +1:02.345, or +2 laps. Nothing to show for the leader. */
export function gap(ms: number | null | undefined, laps: number | null | undefined): string {
  if (laps != null && laps !== 0) return `+${Math.abs(laps)} ${Math.abs(laps) === 1 ? 'lap' : 'laps'}`
  if (ms == null || ms === 0) return ''
  return `+${lapTime(Math.abs(ms))}`
}

/** A duration on the clock: 2:04:09, or 4:09 under an hour. Negative durations clamp to zero. */
export function duration(ms: number | null | undefined): string {
  if (ms == null) return '—'
  const total = Math.max(0, Math.floor(ms / 1000))
  const h = Math.floor(total / 3600)
  const m = Math.floor((total % 3600) / 60)
  const s = total % 60
  return h > 0 ? `${h}:${pad(m)}:${pad(s)}` : `${m}:${pad(s)}`
}

/** A rule bound, always with hours so a column of them aligns: 1:30:00. */
export function ruleTime(ms: number | null | undefined): string {
  if (ms == null) return ''
  const total = Math.max(0, Math.round(ms / 1000))
  return `${Math.floor(total / 3600)}:${pad(Math.floor((total % 3600) / 60))}:${pad(total % 60)}`
}

/**
 * What an admin types for a rule bound: "4" or "4h" (hours), "1:30" (h:mm),
 * "1:30:00", or "90m". Blank = no bound (null). Anything else = undefined,
 * which the editor reports rather than guessing.
 */
export function parseRuleTime(text: string): number | null | undefined {
  const t = text.trim().toLowerCase()
  if (t === '') return null
  let m = /^(\d+(?:\.\d+)?)\s*h?$/.exec(t)
  if (m) return Math.round(Number(m[1]) * 3_600_000)
  m = /^(\d+)\s*m(in)?$/.exec(t)
  if (m) return Number(m[1]) * 60_000
  m = /^(\d+):([0-5]\d)(?::([0-5]\d))?$/.exec(t)
  if (m) return Number(m[1]) * 3_600_000 + Number(m[2]) * 60_000 + Number(m[3] ?? 0) * 1000
  return undefined
}

/** An energy percentage to one decimal ("62.4%"), or nothing. */
export function pct(value: number | null | undefined, digits = 1): string {
  return value == null ? '' : `${value.toFixed(digits)}%`
}

/** GREEN → "Green", FULL_YELLOW → "Full yellow". */
export function flagLabel(flag: string | null | undefined): string | null {
  if (!flag) return null
  const words = flag.toLowerCase().split(/[_\s]+/).filter(Boolean)
  if (words.length === 0) return null
  return [words[0][0].toUpperCase() + words[0].slice(1), ...words.slice(1)].join(' ')
}

/** Which tint a flag chip wears: the race-control vocabulary (green / yellow / red), else neutral. */
export function flagTone(flag: string | null | undefined): 'green' | 'yellow' | 'red' | 'neutral' {
  const f = (flag ?? '').toUpperCase()
  if (f.includes('RED')) return 'red'
  if (f.includes('YELLOW') || f.includes('SC') || f.includes('CODE')) return 'yellow'
  if (f.includes('GREEN')) return 'green'
  return 'neutral'
}

export function ratingName(code: string | null | undefined): string | null {
  if (!code) return null
  return RATINGS.find((r) => r.code === code.toUpperCase())?.name ?? code
}

/**
 * "Now" for counting a stint up: the wall clock while the feed is live and
 * current, otherwise the newest time the feed reported — a replay or a
 * finished session must not keep counting against today's clock.
 */
export function feedNow(tower: Pick<Tower, 'state' | 'session' | 'feedClockMs'>, wallMs: number): number | null {
  const clock = tower.feedClockMs
  if (clock == null || clock <= 0) return tower.state === 'LIVE' ? wallMs : null
  const live = tower.state === 'LIVE' && !tower.session?.finished && wallMs >= clock && wallMs - clock < 10 * 60_000
  return live ? wallMs : clock
}

/** The class's fastest best lap, to mark in the tower. */
export function classBest(cars: TowerCar[]): number | null {
  let best: number | null = null
  for (const c of cars) if (c.bestLapMs != null && c.bestLapMs > 0 && (best == null || c.bestLapMs < best)) best = c.bestLapMs
  return best
}

export function driverName(d: Pick<CarDriver, 'firstName' | 'lastName'>): string {
  return [d.firstName, d.lastName].filter(Boolean).join(' ') || '—'
}
