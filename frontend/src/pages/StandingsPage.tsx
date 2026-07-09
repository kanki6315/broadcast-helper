import { useEffect, useState } from 'react'

interface ChampionshipSummary {
  id: number
  title: string
  groupTitle: string | null
  className: string | null
  kind: string | null
  year: number
  seriesName: string
  rowCount: number
}

interface StandingsEntry {
  position: number
  competitorKey: string
  competitorName: string | null
  totalPoints: number
}

interface ChampionshipDetail {
  championship: ChampionshipSummary
  rows: StandingsEntry[]
}

export default function StandingsPage() {
  const [championships, setChampionships] = useState<ChampionshipSummary[]>([])
  const [detail, setDetail] = useState<ChampionshipDetail | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    void fetch('/api/championships')
      .then((r) => {
        if (!r.ok) throw new Error(`Backend returned ${r.status}`)
        return r.json()
      })
      .then(setChampionships)
      .catch((e) => setError(e instanceof Error ? e.message : 'Failed to reach backend'))
  }, [])

  async function open(id: number) {
    const res = await fetch(`/api/championships/${id}`)
    if (res.ok) setDetail(await res.json())
  }

  if (detail) {
    return (
      <section>
        <button onClick={() => setDetail(null)}>← All championships</button>
        <h2>{detail.championship.title}</h2>
        <table>
          <thead>
            <tr>
              <th>Pos</th>
              <th>{detail.championship.kind === 'DRIVERS' ? 'Driver' : '#'}</th>
              {detail.championship.kind !== 'DRIVERS' && <th>Team</th>}
              <th>Points</th>
            </tr>
          </thead>
          <tbody>
            {detail.rows.map((r) => (
              <tr key={r.competitorKey}>
                <td>{r.position}</td>
                <td>{r.competitorKey}</td>
                {detail.championship.kind !== 'DRIVERS' && <td>{r.competitorName}</td>}
                <td>{r.totalPoints}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>
    )
  }

  // Group by season, then by championship family within it
  // (e.g. the series' own championships vs the Michelin Endurance Cup).
  const seasons = [...new Set(championships.map((c) => `${c.seriesName} ${c.year}`))]

  return (
    <section>
      {error && <p className="error">{error}</p>}
      {championships.length === 0 ? (
        <p>No standings yet — import a standings file first.</p>
      ) : (
        seasons.map((season) => {
          const inSeason = championships.filter((c) => `${c.seriesName} ${c.year}` === season)
          const groups = [...new Set(inSeason.map((c) => c.groupTitle ?? c.title))]
          return (
            <div key={season}>
              <h2>{season}</h2>
              {groups.map((group) => (
                <div key={group}>
                  <h3>{group}</h3>
                  <table>
                    <thead>
                      <tr>
                        <th>Class</th>
                        <th>Type</th>
                        <th>Competitors</th>
                      </tr>
                    </thead>
                    <tbody>
                      {inSeason
                        .filter((c) => (c.groupTitle ?? c.title) === group)
                        .map((c) => (
                          <tr key={c.id} className="clickable" onClick={() => open(c.id)}>
                            <td>{c.className}</td>
                            <td>{c.kind}</td>
                            <td>{c.rowCount}</td>
                          </tr>
                        ))}
                    </tbody>
                  </table>
                </div>
              ))}
            </div>
          )
        })
      )}
    </section>
  )
}
