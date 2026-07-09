import { useEffect, useState } from 'react'

interface ChampionshipSummary {
  id: number
  title: string
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

  return (
    <section>
      {error && <p className="error">{error}</p>}
      {championships.length === 0 ? (
        <p>No standings yet — import a standings file first.</p>
      ) : (
        <table>
          <thead>
            <tr>
              <th>Championship</th>
              <th>Class</th>
              <th>Type</th>
              <th>Season</th>
              <th>Competitors</th>
            </tr>
          </thead>
          <tbody>
            {championships.map((c) => (
              <tr key={c.id} className="clickable" onClick={() => open(c.id)}>
                <td>{c.title}</td>
                <td>{c.className}</td>
                <td>{c.kind}</td>
                <td>{c.year}</td>
                <td>{c.rowCount}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  )
}
