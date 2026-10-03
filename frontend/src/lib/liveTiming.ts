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
  /** What the feed says is running. */
  session?: FeedSession | null
}

export interface FeedSession {
  championship: string | null
  event: string | null
  name: string | null
  type: string | null
  flag: string | null
  running: boolean
  finished: boolean
  /** Al Kamel's event id: the series weekend this session belongs to. */
  feedEventDbId: number | null
  clock: SessionClock | null
}

/**
 * timing.session.status's clock, for the client to count down. startMs is
 * null before the start; stopMs is set only while the clock is stopped (red
 * flag); stoppedMs is the time stopped so far. finalType is BY_TIME, BY_LAPS,
 * BY_LAPS_WITH_MAX_TIME, BY_TIME_PLUS_LAPS or MANUAL.
 */
export interface SessionClock {
  finalType: string | null
  startMs: number | null
  finalMs: number | null
  finalLaps: number | null
  currentLap: number | null
  stopMs: number | null
  stoppedMs: number
  utcOffsetHours: number | null
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
  /** Al Kamel PIT stints so far, as the Pits view counts them; null with no session recorded. */
  pitStops: number | null
  /** Pit-lane time of the newest finished stop. */
  lastPitMs: number | null
  /** From participant details (null when that channel is off): BOX, OUT_LAP, TRACK or STOPPED. */
  trackStatus: string | null
  currentSector: number | null
  /** Sector 1 first; null where the car has no time yet. */
  sectors: (SectorTime | null)[] | null
  bestSectorMs: (number | null)[] | null
  /** The car's own best sectors summed; null until it has one in every sector. */
  idealMs: number | null
  /** Its place in its class on the starting grid, in a race; null off the grid. */
  startPosition: number | null
  /** Best speed trap of the session, in the tower's speedUnit. */
  topSpeed: number | null
  /** Place and gaps across every class, from the feed's overall standings; null until they arrive. */
  overallPosition: number | null
  overallGapMs: number | null
  overallGapLaps: number | null
  overallIntervalMs: number | null
  overallIntervalLaps: number | null
  /** Has taken the chequered flag. */
  checkered: boolean
  /** Full name of the driver who set bestLapMs; null when not known. */
  bestLapDriver: string | null
}

/** A sector's newest time. currentLap false = the previous lap's, until the car runs that sector again. */
export interface SectorTime {
  ms: number
  valid: boolean | null
  currentLap: boolean
}

/** A class's fastest time in one sector, the car that holds it and its driver's surname. */
export interface ClassSector {
  ms: number | null
  car: string | null
  driver: string | null
}

export interface TowerClass {
  className: string
  feedClass: string
  color: string | null
  cars: TowerCar[]
  /** Per sector; empty without participant details. */
  bestSectors: ClassSector[]
  idealMs: number | null
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
  /** "mph" or "km/h", from the feed's unit of measure; null before the feed says. */
  speedUnit: string | null
  /** Race control's screen now and its newest message; null when the feed sends no race control. */
  raceControl?: RaceControlNow | null
  /** The track's weather station, latest reading; null when the feed sends no weather. */
  weather?: WeatherReading | null
}

/**
 * One weather station reading, in both unit systems (the backend converts
 * whichever the station did not send). windDirection is degrees as the
 * station sends it — taken as where the wind comes from, unverified.
 */
export interface WeatherReading {
  dayTimeMs: number | null
  airC: number | null
  airF: number | null
  trackC: number | null
  trackF: number | null
  humidityPct: number | null
  pressureMbar: number | null
  pressureInHg: number | null
  windDirection: number | null
  windKmh: number | null
  windMph: number | null
}

export interface WeatherLog {
  sessionDbId: number
  /** One a minute, oldest first. */
  readings: WeatherReading[]
}

/**
 * One race control message. dayTimeMs is when it was shown (null on the
 * screen's lines); colours are race control's own, #rrggbb or null.
 */
export interface RaceControlMessage {
  key: string
  dayTimeMs: number | null
  text: string
  /** Usually a class name, when race control addresses one. */
  group: string | null
  line: number | null
  foreground: string | null
  background: string | null
  blink: boolean
}

export interface RaceControlNow {
  /** What race control's screen shows now, in line order. Often empty. */
  lines: RaceControlMessage[]
  latest: RaceControlMessage | null
}

export interface RaceControlLog {
  sessionDbId: number
  /** Newest first. */
  messages: RaceControlMessage[]
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

/**
 * What the header clock shows. Time-limited sessions count down: the time
 * run is now (or the red-flag stop) less the start and the time stopped. A
 * lap-limited one shows the leader's lap. null when there is nothing to
 * show: no clock, a MANUAL session, or a finished one (the Finished chip
 * says so).
 *
 * "Now" is the wall clock while the feed is live and the session's
 * scheduled end is still ahead of it; otherwise the feed's own newest time,
 * so a replay shows the clock as it stood rather than 0:00. The clock keeps
 * running under a practice red flag (Road Atlanta, 2026-09-30); only
 * isSessionRunning false stops it.
 */
export function sessionClock(
  tower: Pick<Tower, 'state' | 'session' | 'feedClockMs'>,
  wallMs: number,
): { time: string; note: string; stopped: boolean; laps: string | null } | null {
  const session = tower.session
  const c = session?.clock
  if (!session || !c || session.finished) return null
  const laps =
    c.finalLaps != null && (c.finalType === 'BY_LAPS' || c.finalType === 'BY_LAPS_WITH_MAX_TIME')
      ? c.currentLap != null
        ? `Lap ${Math.min(c.currentLap, c.finalLaps)} of ${c.finalLaps}`
        : `${c.finalLaps} laps`
      : null
  const timed = c.finalMs != null && c.finalType !== 'BY_LAPS' && c.finalType !== 'MANUAL'
  if (!timed) return laps ? { time: laps, note: '', stopped: false, laps: null } : null
  const finalMs = c.finalMs as number
  if (c.startMs == null) return { time: duration(finalMs), note: 'Not started', stopped: false, laps }
  const scheduledEnd = c.startMs + finalMs + c.stoppedMs
  const now = c.stopMs ?? (tower.state === 'LIVE' && wallMs < scheduledEnd ? wallMs : feedNow(tower, wallMs))
  if (now == null) return null
  const left = Math.max(0, finalMs - (now - c.startMs - c.stoppedMs))
  return { time: duration(left), note: c.stopMs != null ? 'Clock stopped' : 'to go', stopped: c.stopMs != null, laps }
}

/** Time of day at the track (24 h, h:mm:ss), from the feed's UTC offset; null without one. */
export function trackTime(wallMs: number, utcOffsetHours: number | null | undefined): string | null {
  if (utcOffsetHours == null) return null
  const d = new Date(wallMs + utcOffsetHours * 3_600_000)
  return `${d.getUTCHours()}:${pad(d.getUTCMinutes())}:${pad(d.getUTCSeconds())}`
}

/** The feed's units: US when it reports speeds in mph. */
export const usUnits = (speedUnit: string | null | undefined) => speedUnit === 'mph'

/** A temperature in the feed's units, to a tenth: "38.3°". Null when the station sent none. */
export function temperature(c: number | null, f: number | null, us: boolean): string | null {
  const v = us ? f : c
  return v == null ? null : `${v.toFixed(1)}°`
}

const COMPASS = ['N', 'NNE', 'NE', 'ENE', 'E', 'ESE', 'SE', 'SSE', 'S', 'SSW', 'SW', 'WSW', 'W', 'WNW', 'NW', 'NNW']

/** Degrees as a 16-point compass direction: 225 → "SW". */
export function compass(degrees: number | null): string | null {
  if (degrees == null) return null
  return COMPASS[Math.round((((degrees % 360) + 360) % 360) / 22.5) % 16]
}

/** Wind in the feed's units: "14 km/h SW", or just the direction or speed the station sent. */
export function wind(r: Pick<WeatherReading, 'windKmh' | 'windMph' | 'windDirection'>, us: boolean): string | null {
  const speed = us ? r.windMph : r.windKmh
  const dir = compass(r.windDirection)
  const parts = [speed == null ? null : `${Math.round(speed)} ${us ? 'mph' : 'km/h'}`, dir].filter(Boolean)
  return parts.length ? parts.join(' ') : null
}

/** Pressure in the feed's units: "29.90 inHg" or "1012 mbar". */
export function pressure(r: Pick<WeatherReading, 'pressureMbar' | 'pressureInHg'>, us: boolean): string | null {
  if (us) return r.pressureInHg == null ? null : `${r.pressureInHg.toFixed(2)} inHg`
  return r.pressureMbar == null ? null : `${Math.round(r.pressureMbar)} mbar`
}

/**
 * When a race control message was shown: the track's time of day when its
 * offset is known, else the viewer's own clock (24-hour, to the second).
 */
export function messageTime(dayTimeMs: number | null, utcOffsetHours: number | null | undefined): string | null {
  if (dayTimeMs == null) return null
  const atTrack = trackTime(dayTimeMs, utcOffsetHours)
  if (atTrack) return atTrack
  const d = new Date(dayTimeMs)
  return `${d.getHours()}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

/**
 * How a last lap reads: 'class' when it set the class's fastest lap, 'pb'
 * when it was the car's own best, else null. Timing screens' purple and
 * green.
 */
export function lastLapMark(car: Pick<TowerCar, 'lastLapMs' | 'bestLapMs'>, classBestMs: number | null): 'class' | 'pb' | null {
  if (car.lastLapMs == null || car.lastLapMs <= 0 || car.lastLapMs !== car.bestLapMs) return null
  return car.lastLapMs === classBestMs ? 'class' : 'pb'
}

/**
 * How a sector time reads, as a lap does: 'class' when it is the class's
 * fastest in that sector, 'pb' when it is the car's own best there.
 */
export function sectorMark(ms: number | null | undefined, carBest: number | null | undefined,
  classBest: number | null | undefined): 'class' | 'pb' | null {
  if (ms == null || ms <= 0) return null
  if (ms === classBest) return 'class'
  return ms === carBest ? 'pb' : null
}

/** A driver's name for a narrow tower: the first name to its initial, the rest as it is ("F. Schandorff"). */
export function initialedName(name: string): string {
  const [first, ...rest] = name.trim().split(/\s+/)
  return rest.length === 0 ? first : `${first.charAt(0)}. ${rest.join(' ')}`
}

/** Places gained in class since the start: positive = up. null without a start position. */
export function placesGained(car: Pick<TowerCar, 'position' | 'startPosition'>): number | null {
  return car.startPosition == null ? null : car.startPosition - car.position
}

/**
 * The field at a glance. stopped is null without participant details (only
 * they say a car has stopped on track); retired counts RETIRED alone.
 */
export function fieldCounts(tower: Pick<Tower, 'classes'>): { onTrack: number; inPit: number; stopped: number | null; retired: number } {
  const cars = tower.classes.flatMap((c) => c.cars)
  const running = (c: TowerCar) => !c.status || c.status === 'CLASSIFIED' || c.status === 'RUNNING'
  const stopped = (c: TowerCar) => c.trackStatus === 'STOPPED'
  return {
    onTrack: cars.filter((c) => running(c) && !c.inPit && !stopped(c)).length,
    inPit: cars.filter((c) => running(c) && c.inPit).length,
    stopped: cars.some((c) => c.trackStatus != null) ? cars.filter((c) => running(c) && !c.inPit && stopped(c)).length : null,
    retired: cars.filter((c) => c.status === 'RETIRED').length,
  }
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

/** An event an admin could file a series weekend under. */
export interface EventOption {
  id: number
  name: string
  seriesName: string
  date: string | null
}

/**
 * One series at one weekend (Al Kamel's feed event). boundBy: null = not filed
 * yet (automatic filing keeps trying), AUTO, ADMIN, ADMIN_NONE = not in Pit Pass.
 */
export interface WeekendChampionship {
  feedEventDbId: number
  champDbId: number | null
  champName: string | null
  feedEventName: string | null
  track: string | null
  eventId: number | null
  eventName: string | null
  boundBy: 'AUTO' | 'ADMIN' | 'ADMIN_NONE' | null
  boundByEmail: string | null
  firstSessionMs: number | null
  lastSessionMs: number | null
  sessions: SessionSummary[]
  candidates: EventOption[]
}

/** `GET /api/live/weekends`: every series at one track within a few days. */
export interface Weekend {
  track: string | null
  fromMs: number | null
  toMs: number | null
  championships: WeekendChampionship[]
}
