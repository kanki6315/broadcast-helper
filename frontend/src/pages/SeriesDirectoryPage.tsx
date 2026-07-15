import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import {
  getJson,
  type ClassStylesResponse,
  type SeasonSummary,
  type SeriesInfo,
} from '../lib/api'
import './season.css'

interface SeriesGroup {
  name: string
  seriesId: number | null
  latest: SeasonSummary
  past: SeasonSummary[]
  classes: { code: string; color: string }[]
}

export default function SeriesDirectoryPage() {
  const [groups, setGroups] = useState<SeriesGroup[] | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    async function load() {
      const [seasons, series] = await Promise.all([
        getJson<SeasonSummary[]>('/api/seasons'),
        getJson<SeriesInfo[]>('/api/series').catch(() => [] as SeriesInfo[]),
      ])

      // /api/seasons is already year-desc; first season per series is current.
      const byName = new Map<string, SeriesGroup>()
      for (const s of seasons) {
        const g = byName.get(s.seriesName)
        if (!g) {
          const info = series.find((x) => x.name === s.seriesName)
          byName.set(s.seriesName, {
            name: s.seriesName,
            seriesId: info?.id ?? null,
            latest: s,
            past: [],
            classes: [],
          })
        } else {
          g.past.push(s)
        }
      }
      const list = [...byName.values()]

      // Class chips per series (colour + code — colour never stands alone).
      await Promise.all(
        list.map(async (g) => {
          if (g.seriesId == null) return
          try {
            const styles = await getJson<ClassStylesResponse>(
              `/api/series/${g.seriesId}/class-styles`,
            )
            g.classes = styles.styles.map((st) => ({ code: st.classCode, color: st.color }))
          } catch {
            // class chips are decoration-with-meaning, not load-bearing
          }
        }),
      )

      if (!cancelled) setGroups(list)
    }
    load().catch((e) => {
      if (!cancelled) setError(e instanceof Error ? e.message : 'Failed to reach backend')
    })
    return () => {
      cancelled = true
    }
  }, [])

  if (error) return <p className="error-panel">{error}</p>
  if (!groups) {
    return (
      <div className="skeleton-block" aria-label="Loading series">
        <span className="skeleton" />
        <span className="skeleton" style={{ height: '5rem' }} />
        <span className="skeleton" style={{ height: '5rem' }} />
      </div>
    )
  }

  if (groups.length === 0) {
    return (
      <div className="empty-state">
        No series yet — <Link to="/imports">import</Link> a results, standings, or entry-list file
        to get started.
      </div>
    )
  }

  return (
    <section>
      <div className="dir-list">
        {groups.map((g) => (
          <div key={g.name} className="dir-panel">
            <Link to={`/seasons/${g.latest.id}`} className="dir-main">
              <div className="dir-info">
                <span className="dir-series-name">{g.name}</span>
                {g.classes.length > 1 && (
                  <span className="dir-classes">
                    {g.classes.map((c) => (
                      <span key={c.code} className="dir-class">
                        <i className="swatch" style={{ background: c.color }} />
                        {c.code}
                      </span>
                    ))}
                  </span>
                )}
              </div>
              <div className="dir-season">
                <span className="dir-year">{g.latest.year}</span>
                <span className="dir-season-meta">
                  {g.latest.roundCount} round{g.latest.roundCount !== 1 ? 's' : ''} ·{' '}
                  {g.latest.championshipCount} championship
                  {g.latest.championshipCount !== 1 ? 's' : ''}
                </span>
                <span className="dir-open">Open season hub →</span>
              </div>
            </Link>
            {g.past.length > 0 && (
              <div className="dir-past">
                <span>Earlier seasons</span>
                {g.past.map((p) => (
                  <Link key={p.id} to={`/seasons/${p.id}`}>
                    {p.year}
                  </Link>
                ))}
              </div>
            )}
          </div>
        ))}
      </div>
    </section>
  )
}
