import { test } from 'node:test'
import assert from 'node:assert/strict'
import { points, assignPosition, project, baselineIssue, supportedChampionship, supportedLiveChampionship, liveScenario, liveLines, liveName, liveGap } from '../src/lib/championshipCalculator.ts'
import type { Recap, ChampionshipSummary, LiveChampionship, LiveRunning, RecapRow } from '../src/lib/api.ts'
const recap = {
  championship: { isCup: false },
  rounds: [{ round: 1, eventId: 11, venue: 'DAY', sessions: [] }, { round: 2, eventId: 22, venue: 'SEB', sessions: [] }],
  rows: [
    { competitorKey: '6', position: 1, totalPoints: 1000, pointsByRound: { 1: 1000 }, sessionPoints: {} },
    { competitorKey: '7', position: 2, totalPoints: 980, pointsByRound: { 1: 980 }, sessionPoints: {} },
    { competitorKey: '31', position: 3, totalPoints: 970, pointsByRound: { 1: 970 }, sessionPoints: {} },
  ],
} as unknown as Recap

test('scoring boundaries and empty positions', () => {
  assert.deepEqual([0, 1, 2, 5, 6, 29, 30, 40].map(p => points(p, 'qualifying')), [0, 35, 32, 26, 25, 2, 1, 1])
  assert.equal(points(1, 'race'), 350)
  assert.equal(points(40, 'race'), 10)
  assert.equal(points(1.5, 'race'), 0)
  assert.deepEqual([0, 1, 2, 3, 4, 20].map(p => points(p, 'checkpoint')), [0, 5, 4, 3, 2, 2])
})
test('selected teams only, independent qualifying, penalty, gaps and immutability', () => {
  const before = JSON.stringify(recap)
  const rows = project(recap, { '6': { positions: [1, 5], adjustment: -10 }, '7': { positions: [2, 1], adjustment: 0 } }, false, 2)
  assert.deepEqual(rows.map(r => [r.competitorKey, r.total, r.gap, r.rank]), [['7', 1362, 0, 1], ['6', 1285, 77, 2]])
  assert.equal(JSON.stringify(recap), before)
  assert.equal(project(recap, { '6': { positions: [1, 0], adjustment: 0 } }, false, 2)[0].total, 1035)
})
test('swaps only occupied positions in the same phase', () => {
  const scenario = { '6': { positions: [1, 5], adjustment: 0 }, '7': { positions: [2, 1], adjustment: 0 } }
  const changed = assignPosition(scenario, '6', 1, 1)
  assert.deepEqual(changed['7'].positions, [2, 5])
  assert.deepEqual(scenario['6'].positions, [1, 5])
  assert.deepEqual(assignPosition(changed, '6', 1, 0)['7'].positions, [2, 5])
})
test('ties are not broken by imported rank; cups score each checkpoint separately', () => {
  const rows = project(recap, { '6': { positions: [0, 0], adjustment: 0 }, '7': { positions: [0, 0], adjustment: 20 } }, false, 2)
  assert.ok(rows.every(r => r.rank === 1 && r.tied && r.gap === 0))
  assert.equal(project(recap, { '6': { positions: [1, 3, 4, 2], adjustment: 0 } }, true, 4)[0].added, 14)
})
test('blocks double-counting, later scored rounds and missing mappings', () => {
  assert.ok(baselineIssue(recap, 11))
  assert.equal(baselineIssue(recap, 22), null)
  assert.ok(baselineIssue(recap, 99))
  const zeroPoints = structuredClone(recap)
  zeroPoints.rows[0].pointsByRound[2] = 0
  assert.ok(baselineIssue(zeroPoints, 22))
})
test('only supported IMSA team championships are offered', () => {
  const c = { seriesName: 'IMSA', kind: 'TEAMS', rowCount: 3, isCup: false } as ChampionshipSummary
  assert.ok(supportedChampionship(c))
  assert.ok(!supportedChampionship({ ...c, kind: 'DRIVERS' }))
  assert.ok(!supportedChampionship({ ...c, seriesName: 'Mustang Challenge' }))
})

test('Pilot Challenge teams score race only, with penalties and no baseline mutation', () => {
  const c = { seriesName: 'IMSA Michelin Pilot Challenge', kind: 'TEAMS', rowCount: 3, isCup: false } as ChampionshipSummary
  assert.ok(supportedChampionship(c))
  assert.ok(supportedChampionship({ ...c, seriesName: 'IMPC' }))
  assert.ok(!supportedChampionship({ ...c, isCup: true, groupTitle: 'Bronze Cup' }))
  assert.ok(!supportedChampionship({ ...c, kind: 'DRIVERS' }))
  const pilot = { ...recap, championship: { ...recap.championship, seriesName: c.seriesName } }
  const before = JSON.stringify(pilot)
  const rows = project(pilot, { '6': { positions: [1], adjustment: -10 }, '7': { positions: [2], adjustment: 0 } }, false, 1)
  assert.deepEqual(rows.map(r => [r.total, r.added, r.gap]), [[1340, 340, 0], [1300, 320, 40]])
  assert.equal(project(pilot, { '6': { positions: [0], adjustment: 0 } }, false, 1)[0].added, 0)
  assert.equal(JSON.stringify(pilot), before)
})

/* -- live mode -- */
const liveRecap = (kind: string, rows: [string, number, number?][], seriesName = 'IMSA') => ({
  championship: { isCup: false, kind, seriesName },
  rounds: [{ round: 1, eventId: 11, venue: 'DAY', sessions: [] }, { round: 2, eventId: 22, venue: 'ATL', sessions: [] }],
  rows: rows.map(([competitorKey, totalPoints, position], i) => ({ competitorKey, competitorName: null, carNumber: '7', teamName: 'Porsche Penske Motorsport',
    position: position ?? i + 1, totalPoints, pointsByRound: { 1: totalPoints }, sessionPoints: {} })),
}) as unknown as Recap
const running = (position: number, carNumber = '7', gapToLeaderMs: number | null = null, gapToLeaderLaps: number | null = null): LiveRunning =>
  ({ position, carNumber, teamName: null, status: 'CLASSIFIED', laps: 70, gapToLeaderMs, gapToLeaderLaps })
const liveDoc = (kind: string, livePhase: string | null, rows: LiveChampionship['rows']): LiveChampionship =>
  ({ state: 'LIVE', eventId: 22, session: null, championshipId: 1, kind, className: 'GTP', livePhase, qualifyingImported: true, rows, newcomers: [] })

test('a race adds live race points and the imported qualifying position; every row takes part', () => {
  const recap = liveRecap('MANUFACTURERS', [['Cadillac', 2562], ['Porsche', 2532], ['BMW', 2228]])
  const lines = liveLines(recap, liveDoc('MANUFACTURERS', 'RACE', [
    { competitorKey: 'Cadillac', live: running(2, '31'), qualifyingPosition: 2 },
    { competitorKey: 'Porsche', live: running(1), qualifyingPosition: 1 },
    { competitorKey: 'BMW', live: null, qualifyingPosition: null }]))
  assert.deepEqual(lines.map(l => [l.competitorKey, l.total, l.movement]), [['Porsche', 2532 + 35 + 350, 1], ['Cadillac', 2562 + 32 + 320, -1], ['BMW', 2228, 0]])
  assert.equal(lines[1].gap, 3)
  assert.equal(lines[2].running, null)
  assert.deepEqual(lines[0].positions, [1, 1])
})
test('qualifying fills the qualifying column only; practice and Pilot Challenge qualifying score nothing', () => {
  const rows = [{ competitorKey: '7', live: running(3), qualifyingPosition: 9 }]
  assert.deepEqual(liveScenario(liveDoc('TEAMS', 'QUALIFYING', rows), 'IMSA')['7'].positions, [3, 0])
  assert.deepEqual(liveScenario(liveDoc('TEAMS', 'RACE', rows), 'IMSA')['7'].positions, [9, 3])
  assert.deepEqual(liveScenario(liveDoc('TEAMS', null, rows), 'IMSA')['7'].positions, [0, 0])
  assert.deepEqual(liveScenario(liveDoc('TEAMS', 'RACE', rows), 'IMSA Michelin Pilot Challenge')['7'].positions, [3])
  assert.deepEqual(liveScenario(liveDoc('TEAMS', 'QUALIFYING', rows), 'IMSA Michelin Pilot Challenge')['7'].positions, [0])
})
test('movement ignores how the standings source numbers ties', () => {
  // Co-drivers tied on points: the source numbers them 1, 1, 2, 2.
  const recap = liveRecap('DRIVERS', [['Ellis', 2373, 1], ['Ward', 2373, 1], ['Gallagher', 2337, 2], ['Foley', 2337, 2]])
  const lines = liveLines(recap, liveDoc('DRIVERS', 'RACE', [
    ...['Ellis', 'Ward'].map(competitorKey => ({ competitorKey, live: running(1, '57'), qualifyingPosition: null })),
    ...['Gallagher', 'Foley'].map(competitorKey => ({ competitorKey, live: running(2, '96'), qualifyingPosition: null }))]))
  assert.deepEqual(lines.map(l => l.rank), [1, 1, 3, 3])
  assert.deepEqual(lines.map(l => l.movement), [0, 0, 0, 0])
})
test('live mode covers all three kinds but no cups; the scenario calculator stays teams-only', () => {
  const summary = (over: Partial<ChampionshipSummary>) => ({ seriesName: 'IMSA WeatherTech SportsCar Championship', kind: 'DRIVERS', rowCount: 5, isCup: false, className: 'GTP', title: 't', ...over }) as ChampionshipSummary
  assert.ok(['TEAMS', 'DRIVERS', 'MANUFACTURERS'].every(kind => supportedLiveChampionship(summary({ kind }))))
  assert.equal(supportedLiveChampionship(summary({ kind: 'TEAMS', isCup: true })), false)
  assert.equal(supportedLiveChampionship(summary({ seriesName: 'Porsche Carrera Cup' })), false)
  assert.equal(supportedLiveChampionship(summary({ rowCount: 0 })), false)
  assert.equal(supportedLiveChampionship(summary({ className: null })), false)
  assert.equal(supportedChampionship(summary({})), false)
})
test('names by kind, and gaps', () => {
  const row = { competitorKey: 'Felipe Nasr', competitorName: null, carNumber: '7', teamName: 'Porsche Penske Motorsport' } as RecapRow
  assert.equal(liveName(row, 'DRIVERS'), 'Felipe Nasr')
  assert.equal(liveName(row, 'TEAMS'), '#7 · Porsche Penske Motorsport')
  assert.deepEqual([running(1), running(2, '7', 1830), running(9, '7', 62_400), running(12, '7', null, 1), running(14, '7', 500, 3)].map(liveGap),
    ['Leader', '+1.830', '+1:02.4', '+1 lap', '+3 laps'])
})
