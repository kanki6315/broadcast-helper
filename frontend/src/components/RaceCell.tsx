import type { RecapRace } from '../lib/api'
import RaceLine from './RaceLine'

/** Numeric-first car ordering: 7 before 17 before 77, letters after numbers. */
function carSort(a: string, b: string): number {
  const na = Number(a)
  const nb = Number(b)
  if (Number.isFinite(na) && Number.isFinite(nb)) return na - nb
  if (Number.isFinite(na)) return -1
  if (Number.isFinite(nb)) return 1
  return a.localeCompare(b)
}

/** The lines of one round cell — the recap grid's and both modals'.
 *
 * A car- or driver-keyed row carries one line per race, stacked, each tagged
 * with its race ("R1", "H2") where the round ran several. A team-keyed row
 * (Mustang's DH Entrants) gathers every car the team ran, so the same race
 * ordinal shows up once per car; those cells read one line per car instead,
 * the number leading and that car's races following in running order. */
export default function RaceCell({
  races,
  raceTags,
}: {
  races: RecapRace[] | undefined
  raceTags: Map<number, string | null>
}) {
  if (!races || races.length === 0) {
    return (
      <span className="cell-skip" title="Did not enter this round">
        ·
      </span>
    )
  }
  const byCar = new Map<string, RecapRace[]>()
  for (const race of races) {
    const car = race.carNumber ?? ''
    const list = byCar.get(car)
    if (list) list.push(race)
    else byCar.set(car, [race])
  }
  if (byCar.size <= 1) {
    return races.map((race) => (
      <RaceLine key={`${race.carNumber ?? ''}-${race.race}`} r={race} tag={raceTags.get(race.race)} />
    ))
  }
  const cars = [...byCar.keys()].sort(carSort)
  return cars.map((car) => (
    <span key={car} className="race-car-row">
      <span className="race-car" title={`Car #${car}`}>
        {car}
      </span>
      {byCar
        .get(car)!
        .slice()
        .sort((a, b) => a.race - b.race)
        .map((race) => (
          <RaceLine key={race.race} r={race} tag={raceTags.get(race.race)} />
        ))}
    </span>
  ))
}
