import type { ChampionshipSummary, Recap } from './api'

// IMSA 2026 IWSC sporting regulations §12.20, §40 and Attachment 6.
// Deliberately pure: scenario values never enter the standings/recap store.
export const SCORING_SOURCE = 'https://www.imsa.com/wp-content/uploads/sites/32/2026/05/20/2026-IMSA-SPORTING-REGULATIONS-and-SSR-IWSC-Blackline-031126.pdf'
export const isImsa = (name: string) => /weathertech/i.test(name) || name.toUpperCase() === 'IMSA'
export const supportedChampionship = (c: ChampionshipSummary) =>
  isImsa(c.seriesName) && c.kind?.toUpperCase() === 'TEAMS' && c.rowCount > 0 &&
  (!c.isCup || /endurance|IMEC/i.test(c.groupTitle ?? c.title))

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
    const awards = Array.from({ length: phaseCount }, (_, i) => points(entry.positions[i] ?? 0, cup ? 'checkpoint' : i === 0 ? 'qualifying' : 'race'))
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
