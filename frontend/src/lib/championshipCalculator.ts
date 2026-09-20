import type { ChampionshipSummary, LiveChampionship, LiveRunning, Recap, RecapRow } from './api'

// IMSA 2026 IWSC sporting regulations §12.20, §40 and Attachment 6.
// Deliberately pure: scenario values never enter the standings/recap store.
export const SCORING_SOURCE = 'https://www.imsa.com/wp-content/uploads/sites/32/2026/05/20/2026-IMSA-SPORTING-REGULATIONS-and-SSR-IWSC-Blackline-031126.pdf'
export const PILOT_SCORING_SOURCE = SCORING_SOURCE.replace('IWSC', 'IMPC')
export const isPilotChallenge = (name: string) => /pilot challenge/i.test(name) || name.toUpperCase() === 'IMPC'
export const isImsa = (name: string) => /weathertech/i.test(name) || name.toUpperCase() === 'IMSA'
export const supportedChampionship = (c: ChampionshipSummary) =>
  (isImsa(c.seriesName) || isPilotChallenge(c.seriesName)) && c.kind?.toUpperCase() === 'TEAMS' && c.rowCount > 0 &&
  (!c.isCup || (!isPilotChallenge(c.seriesName) && /endurance|IMEC/i.test(c.groupTitle ?? c.title)))

export function points(position: number, phase: 'qualifying' | 'race' | 'checkpoint'): number {
  if (!Number.isInteger(position) || position < 1) return 0
  if (phase === 'checkpoint') return position === 1 ? 5 : position === 2 ? 4 : position === 3 ? 3 : 2
  const qualifying = [35, 32, 30, 28, 26][position - 1] ?? Math.max(1, 31 - position)
  return qualifying * (phase === 'race' ? 10 : 1)
}
export interface ScenarioEntry { positions: number[]; adjustment: number }
export type Scenario = Record<string, ScenarioEntry>
export function assignPosition(scenario: Scenario, key: string, phase: number, position: number): Scenario {
  const old = scenario[key]?.positions[phase] ?? 0
  return Object.fromEntries(Object.entries(scenario).map(([id, entry]) => {
    const positions = [...entry.positions]
    if (id === key) positions[phase] = position
    else if (position > 0 && positions[phase] === position) positions[phase] = old
    return [id, { ...entry, positions }]
  }))
}
export function project(recap: Recap, scenario: Scenario, cup: boolean, phaseCount: number) {
  const rows = recap.rows.filter(r => scenario[r.competitorKey]).map(row => {
    const entry = scenario[row.competitorKey]
    const awards = Array.from({ length: phaseCount }, (_, i) => points(entry.positions[i] ?? 0, cup ? 'checkpoint' : i === 0 && !isPilotChallenge(recap.championship.seriesName ?? '') ? 'qualifying' : 'race'))
    const added = awards.reduce((a, b) => a + b, 0) + entry.adjustment
    return { ...row, awards, added, total: row.totalPoints + added }
  }).sort((a, b) => b.total - a.total || a.position - b.position || a.competitorKey.localeCompare(b.competitorKey))
  return rows.map(row => ({ ...row, rank: 1 + rows.filter(r => r.total > row.total).length,
    tied: rows.filter(r => r.total === row.total).length > 1, gap: (rows[0]?.total ?? 0) - row.total }))
}
/** Block an event already covered by the latest ledger, including zero-point participation.
 * Cup round ordinals differ from season ordinals: use the recap's event mapping. */
export function baselineIssue(recap: Recap, eventId: number): string | null {
  const target = recap.rounds.find(r => r.eventId === eventId)
  if (!target) return 'This event is not mapped to the imported championship calendar yet.'
  const covered = recap.rounds.some(round => round.round >= target.round && recap.rows.some(row =>
    Object.hasOwn(row.pointsByRound, round.round) || round.sessions.some(s => row.sessionPoints[s.sessionIndex]?.contested)))
  return covered ? 'The imported standings already include this event or a later round. Choose an unscored event to avoid counting points twice.' : null
}

/* -- live mode ----------------------------------------------------------------
 * The scenario is not typed in: it is where everyone is running. Same scales,
 * same project() — a live position is scored exactly as one set by hand.
 * Unlike a hand-built scenario every standings row takes part, and all three
 * kinds are covered: the server has already turned the running order into each
 * kind's scoring position. Mirrors ios/PitPass/Season/LiveProjection.swift. */

export const LIVE_KINDS = ['TEAMS', 'DRIVERS', 'MANUFACTURERS']
/** No cups: the Endurance Cup scores at checkpoints, which live mode does not model yet. */
export const supportedLiveChampionship = (c: ChampionshipSummary) =>
  (isImsa(c.seriesName) || isPilotChallenge(c.seriesName)) && LIVE_KINDS.includes(c.kind?.toUpperCase() ?? '') &&
  !c.isCup && c.rowCount > 0 && !!c.className
/** A weekend's scoring columns: qualifying pays in WeatherTech, not in Pilot Challenge. */
export const livePhases = (seriesName: string) => isPilotChallenge(seriesName) ? ['Race'] : ['Qualifying', 'Race']

/** During a race: live race position plus the imported qualifying position.
 * During qualifying: the live qualifying position. Otherwise nothing scores. */
export function liveScenario(live: LiveChampionship, seriesName: string): Scenario {
  const columns = livePhases(seriesName)
  return Object.fromEntries(live.rows.map(row => [row.competitorKey, { adjustment: 0, positions: columns.map(column =>
    column === 'Race' && live.livePhase === 'RACE' ? row.live?.position ?? 0
      : column === 'Qualifying' && live.livePhase === 'RACE' ? row.qualifyingPosition ?? 0
        : column === 'Qualifying' && live.livePhase === 'QUALIFYING' ? row.live?.position ?? 0 : 0) }]))
}

export function liveLines(recap: Recap, live: LiveChampionship) {
  const series = recap.championship.seriesName ?? ''
  const scenario = liveScenario(live, series)
  const byKey = new Map(live.rows.map(r => [r.competitorKey, r]))
  // Movement compares like with like: both ranks count the rows strictly ahead
  // on points. The source's own `position` numbers tied co-drivers 1, 1, 2, 2
  // where project() ranks them 1, 1, 3, 3 — every crew behind a tie would
  // read as having lost places.
  return project(recap, scenario, false, livePhases(series).length).map(row => ({
    ...row,
    running: byKey.get(row.competitorKey)?.live ?? null,
    positions: scenario[row.competitorKey]?.positions ?? [],
    movement: 1 + recap.rows.filter(r => r.totalPoints > row.totalPoints).length - row.rank,
  }))
}

/** A drivers recap row carries the car and team it last raced with, which is not who it is. */
export const liveName = (row: RecapRow, kind: string) => kind === 'DRIVERS' || kind === 'MANUFACTURERS'
  ? row.competitorName || row.competitorKey
  : `${row.carNumber ? '#' + row.carNumber + ' · ' : ''}${row.teamName ?? row.competitorName ?? row.competitorKey}`

/** "Leader", "+1.830", "+1:02.4", "+2 laps". */
export function liveGap(running: LiveRunning): string {
  if ((running.gapToLeaderLaps ?? 0) > 0) return `+${running.gapToLeaderLaps} ${running.gapToLeaderLaps === 1 ? 'lap' : 'laps'}`
  const ms = running.gapToLeaderMs ?? 0
  if (ms <= 0) return running.position === 1 ? 'Leader' : ''
  const seconds = ms / 1000
  if (seconds < 60) return `+${seconds.toFixed(3)}`
  const minutes = Math.floor(seconds / 60)
  return `+${minutes}:${(seconds - minutes * 60).toFixed(1).padStart(4, '0')}`
}
