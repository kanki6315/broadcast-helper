import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'

interface ChampionshipSummary {
  id: number
  title: string
  groupTitle: string | null
  className: string | null
  kind: string | null
  year: number
  seasonId: number
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

export default function StandingsDetailPage() {
  const { championshipId } = useParams()
  const [detail, setDetail] = useState<ChampionshipDetail | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    void fetch(`/api/championships/${championshipId}`)
      .then((r) => (r.ok ? r.json() : Promise.reject(new Error(`Backend returned ${r.status}`))))
      .then(setDetail)
      .catch((e) => setError(e instanceof Error ? e.message : 'Failed to reach backend'))
  }, [championshipId])

  if (error) return <p className="error">{error}</p>
  if (!detail) return <p>Loading…</p>

  const isDrivers = detail.championship.kind === 'DRIVERS'
  return (
    <section>
      <p>
        <Link to={`/seasons/${detail.championship.seasonId}`}>
          ← {detail.championship.seriesName} {detail.championship.year}
        </Link>
      </p>
      <h2>{detail.championship.title}</h2>
      <table>
        <thead>
          <tr>
            <th>Pos</th>
            <th>{isDrivers ? 'Driver' : '#'}</th>
            {!isDrivers && <th>Team</th>}
            <th>Points</th>
          </tr>
        </thead>
        <tbody>
          {detail.rows.map((r) => (
            <tr key={r.competitorKey}>
              <td>{r.position}</td>
              <td>{r.competitorKey}</td>
              {!isDrivers && <td>{r.competitorName}</td>}
              <td>{r.totalPoints}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  )
}
