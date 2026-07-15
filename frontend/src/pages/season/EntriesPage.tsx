import { useEffect, useMemo, useState } from 'react'
import { getJson, type LineupCar, type LineupDriver, type Lineups } from '../../lib/api'
import { shortName } from '../../lib/names'
import { useSeason } from './SeasonLayout'

function sameCrew(a: LineupDriver[], b: LineupDriver[]): boolean {
  if (a.length !== b.length) return false
  const names = new Set(b.map((d) => d.name))
  return a.every((d) => names.has(d.name))
}

function LineupRow({
  car,
  ordinals,
  identStyle,
}: {
  car: LineupCar
  ordinals: number[]
  identStyle: (i: number) => React.CSSProperties
}) {
  return (
    <tr>
      <td className="ident car-no" style={identStyle(0)}>
        {car.carNumber}
        {car.isGuest && <span className="badge muted">G</span>}
      </td>
      <td className="ident name-cell" style={identStyle(1)} title={car.teamName ?? undefined}>
        {car.teamName}
      </td>
      {ordinals.map((ord, idx) => {
        const crew = car.byRound[ord]
        if (!crew || crew.length === 0) {
          return (
            <td key={ord} className="cell-skip" title="Did not enter this round">
              —
            </td>
          )
        }
        // Changed vs the car's previous entered round (skipped rounds don't count).
        const prevOrd = ordinals.slice(0, idx).reverse().find((o) => car.byRound[o]?.length)
        const changed = prevOrd != null && !sameCrew(crew, car.byRound[prevOrd] ?? [])
        return (
          <td
            key={ord}
            className={changed ? 'lineup-cell lineup-changed' : 'lineup-cell'}
            title={changed ? 'Lineup changed vs previous round' : undefined}
          >
            {crew.map((d, i) => (
              <span key={`${d.name}-${i}`} className="drv">
                {i === 0 && changed && <i className="change-dot" aria-hidden />}
                {d.isTbd ? 'TBD' : shortName(d.name)}
                {d.rating && <span className="rating">{d.rating}</span>}
              </span>
            ))}
          </td>
        )
      })}
    </tr>
  )
}

export default function EntriesPage() {
  const { hub, classFilter, classColor } = useSeason()
  const [lineups, setLineups] = useState<Lineups | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    getJson<Lineups>(`/api/seasons/${hub.id}/lineups`)
      .then((l) => !cancelled && setLineups(l))
      .catch((e) => !cancelled && setError(e instanceof Error ? e.message : 'Failed to load'))
    return () => {
      cancelled = true
    }
  }, [hub.id])

  const classes = useMemo(
    () =>
      (lineups?.classes ?? []).filter((c) => !classFilter || c.className === classFilter),
    [lineups, classFilter],
  )

  if (error) return <p className="error-panel">{error}</p>
  if (!lineups) {
    return (
      <div className="skeleton-block">
        <span className="skeleton" />
        <span className="skeleton" />
        <span className="skeleton" />
        <span className="skeleton" />
      </div>
    )
  }
  if (lineups.rounds.length === 0 || classes.length === 0) {
    return (
      <div className="empty-state">
        No entry lists imported yet — driver lineups appear here per round once entry lists come
        in.
      </div>
    )
  }

  const ordinals = lineups.rounds.map((r) => r.ordinal)
  const identCols = [
    { w: 64 },
    { w: 220 },
  ]
  const identStyle = (i: number): React.CSSProperties => ({
    left: i === 0 ? 0 : identCols[0].w,
    minWidth: identCols[i].w,
    maxWidth: i === 1 ? identCols[i].w : undefined,
  })

  return (
    <div>
      <p className="muted" style={{ fontSize: 'var(--text-sm)', margin: 'var(--space-3) 0' }}>
        Crew per car per round. A highlighted cell is a lineup change from the car’s previous
        round; “—” means the car skipped the round.
      </p>
      <div className="grid-scroll">
        <table className="grid-table">
          <thead>
            <tr>
              <th className="ident" style={identStyle(0)}>
                #
              </th>
              <th className="ident" style={identStyle(1)}>
                Team
              </th>
              {lineups.rounds.map((r) => (
                <th key={r.ordinal} className="round-head" title={r.eventName}>
                  <span className="venue">{r.venue}</span>
                  <span className="rd">Rd {r.ordinal}</span>
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {classes.map((cls) => (
              <FragmentRows
                key={cls.className}
                className={cls.className}
                color={classColor(cls.className)}
                cars={cls.cars}
                ordinals={ordinals}
                identStyle={identStyle}
                colSpan={2 + lineups.rounds.length}
              />
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}

function FragmentRows({
  className,
  color,
  cars,
  ordinals,
  identStyle,
  colSpan,
}: {
  className: string
  color: string
  cars: LineupCar[]
  ordinals: number[]
  identStyle: (i: number) => React.CSSProperties
  colSpan: number
}) {
  return (
    <>
      <tr className="class-band">
        <td colSpan={colSpan} style={{ '--class-color': color } as React.CSSProperties}>
          {className}
        </td>
      </tr>
      {cars.map((car) => (
        <LineupRow key={car.carNumber} car={car} ordinals={ordinals} identStyle={identStyle} />
      ))}
    </>
  )
}
