import { test } from 'node:test'
import assert from 'node:assert/strict'
import { points, assignPosition, project, baselineIssue, supportedChampionship } from '../src/lib/championshipCalculator.ts'
import type { Recap, ChampionshipSummary } from '../src/lib/api.ts'
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
