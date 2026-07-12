import { useEffect, useState } from 'react'
import { raceText, type FormRace } from '../lib/raceForm'

interface RefRound {
  ordinal: number
  venue: string
  circuitName: string | null
  eventId: number
  raceCount: number
}

type RefRace = FormRace

interface RefEntry {
  carNumber: string
  team: string | null
  isGuest: boolean
  byRound: Record<string, RefRace[]>
}

interface RefClass {
  className: string
  color: string
  entries: RefEntry[]
}

interface ReferenceTable {
  seasonId: number
  year: number
  seriesName: string
  rounds: RefRound[]
  classes: RefClass[]
}

export default function SeasonReferenceTable({ seasonId }: { seasonId: number }) {
  const [table, setTable] = useState<ReferenceTable | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    void fetch(`/api/seasons/${seasonId}/reference`)
      .then((r) => (r.ok ? r.json() : Promise.reject(new Error(`Backend returned ${r.status}`))))
      .then(setTable)
      .catch((e) => setError(e instanceof Error ? e.message : 'Failed to reach backend'))
  }, [seasonId])

  if (error) return <p className="error">{error}</p>
  if (!table) return <p>Loading…</p>
  if (table.rounds.length === 0) return <p>No races imported yet.</p>

  const hasStartData = table.classes.some((c) =>
    c.entries.some((e) => Object.values(e.byRound).some((races) => races.some((r) => r.start != null))),
  )

  return (
    <div className="reference">
      <p className="muted">
        Each cell is start&nbsp;→&nbsp;finish in class for that round.
        {!hasStartData && ' Import starting grids to add start positions.'}
      </p>
      <div className="reference-scroll">
        {table.classes.map((cls) => (
          <table key={cls.className} className="reference-table">
            <thead>
              <tr>
                <th className="ref-class" style={{ background: cls.color }} colSpan={2}>
                  {cls.className}
                </th>
                {table.rounds.map((rnd) => (
                  <th key={rnd.ordinal} className="ref-round" title={rnd.circuitName ?? undefined}>
                    <span className="ref-round-no">R{rnd.ordinal}</span>
                    <span className="ref-round-venue">{rnd.venue}</span>
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {cls.entries.map((e) => (
                <tr key={e.carNumber}>
                  <td className="ref-num">
                    {e.carNumber}
                    {e.isGuest && <span className="badge">GUEST</span>}
                  </td>
                  <td className="ref-team">{e.team}</td>
                  {table.rounds.map((rnd) => {
                    const races = e.byRound[rnd.ordinal] ?? []
                    return (
                      <td key={rnd.ordinal} className="ref-cell">
                        {races.map((r) => (
                          <span key={r.raceOrdinal} className="ref-race">
                            {rnd.raceCount > 1 && <span className="ref-race-label">R{r.raceOrdinal}</span>}
                            {raceText(r)}
                          </span>
                        ))}
                      </td>
                    )
                  })}
                </tr>
              ))}
            </tbody>
          </table>
        ))}
      </div>
    </div>
  )
}
