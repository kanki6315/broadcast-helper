import { test } from 'node:test'
import assert from 'node:assert/strict'
import { historyDrivers, type HistorySeason } from '../src/lib/multiSeasonRecap.ts'

function season(id: number, key: string, resolved: boolean): HistorySeason {
  return { id, year: 2023 + id, driverIds: resolved ? { [key]: 7 } : {},
    stats: { formats: [], rows: [{ driverId: 7, driverName: 'Canonical Driver', className: 'F4', carNumber: String(id), teamName: `Team ${id}`, byFormat: [{ formatId: null, starts: 8, wins: 2, podiums: 4, top5s: 5, dnfs: 1 }], quali: { sessions: 8, poles: 1, top5s: 3 } }] },
    recap: { championship: {}, rounds: [], rows: [{ competitorKey: key, competitorName: 'Old Name', position: id, totalPoints: 100, cells: {} }] } } as unknown as HistorySeason
}
test('driver identity survives changed source key, number and team; selected seasons define totals', () => {
  const seasons = [season(1, 'old', true), season(2, 'new', true)]
  const before = JSON.stringify(seasons)
  const rows = historyDrivers(seasons)
  assert.equal(rows.length, 1)
  assert.equal(rows[0].name, 'Canonical Driver')
  assert.equal(rows[0].starts, 16)
  assert.equal(rows[0].wins, 4)
  assert.equal(rows[0].poles, 2)
  assert.equal(rows[0].years.get(1)?.recap?.position, 1)
  assert.equal(rows[0].years.get(2)?.recap?.position, 2)
  assert.equal(historyDrivers([seasons[1]])[0].starts, 8)
  assert.equal(JSON.stringify(seasons), before)
})
test('unresolved standings stay separate even when source keys repeat; stats-only drivers remain visible', () => {
  const rows = historyDrivers([season(1, '7', false), season(2, '7', false)])
  assert.equal(rows.length, 3)
  assert.equal(rows.filter(r => r.driverId == null).length, 2)
  assert.equal(rows.find(r => r.driverId === 7)?.starts, 16)
})
