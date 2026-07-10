import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'

interface Season {
  id: number
  year: number
  seriesName: string
  roundCount: number
  championshipCount: number
}

export default function SeasonsLandingPage() {
  const [seasons, setSeasons] = useState<Season[]>([])
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    void fetch('/api/seasons')
      .then((r) => (r.ok ? r.json() : Promise.reject(new Error(`Backend returned ${r.status}`))))
      .then(setSeasons)
      .catch((e) => setError(e instanceof Error ? e.message : 'Failed to reach backend'))
  }, [])

  return (
    <section>
      {error && <p className="error">{error}</p>}
      {seasons.length === 0 ? (
        <p>No seasons yet — import a results, standings, or entry-list file to get started.</p>
      ) : (
        <div className="season-grid">
          {seasons.map((s) => (
            <Link key={s.id} to={`/seasons/${s.id}`} className="season-card">
              <span className="season-year">{s.year}</span>
              <span className="season-name">{s.seriesName}</span>
              <span className="season-meta">
                {s.roundCount} round{s.roundCount !== 1 ? 's' : ''} · {s.championshipCount} championship
                {s.championshipCount !== 1 ? 's' : ''}
              </span>
            </Link>
          ))}
        </div>
      )}
    </section>
  )
}
