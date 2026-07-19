import { useEffect, useMemo, useState } from 'react'
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
  abbreviation: string | null
  logoVersion: number | null
  latest: SeasonSummary
  past: SeasonSummary[]
  classes: { code: string; color: string }[]
}

/** A compact identity mark for a series with no uploaded logo: the abbreviation
 * when it's short enough to read as a badge, otherwise the initials of the
 * significant words. Keeps the grid looking deliberate before any logo lands. */
function monogram(g: SeriesGroup): string {
  const abbr = g.abbreviation?.trim()
  if (abbr && abbr.length <= 5) return abbr.toUpperCase()
  const initials = g.name
    .split(/\s+/)
    .filter((w) => !/^(the|of|and|cup|series|championship)$/i.test(w))
    .map((w) => w[0])
    .join('')
    .slice(0, 3)
  return (initials || g.name.slice(0, 2)).toUpperCase()
}

export default function SeriesDirectoryPage() {
  const [groups, setGroups] = useState<SeriesGroup[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [query, setQuery] = useState('')

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
            abbreviation: info?.abbreviation ?? null,
            logoVersion: info?.logoVersion ?? null,
            latest: s,
            past: [],
            classes: [],
          })
        } else {
          g.past.push(s)
        }
      }
      // Active seasons first (year desc), then alphabetical — the current field
      // is what the broadcaster reaches for; dormant series settle to the bottom.
      const list = [...byName.values()].sort(
        (a, b) => b.latest.year - a.latest.year || a.name.localeCompare(b.name),
      )

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

  const filtered = useMemo(() => {
    if (!groups) return null
    const q = query.trim().toLowerCase()
    if (!q) return groups
    return groups.filter(
      (g) =>
        g.name.toLowerCase().includes(q) ||
        (g.abbreviation?.toLowerCase().includes(q) ?? false),
    )
  }, [groups, query])

  if (error) return <p className="error-panel">{error}</p>
  if (!groups || !filtered) {
    return (
      <div className="dir-grid" aria-label="Loading series" aria-busy="true">
        {Array.from({ length: 6 }).map((_, i) => (
          <div key={i} className="dir-card dir-card-skeleton">
            <span className="skeleton" style={{ height: '2.75rem', width: '55%' }} />
            <span className="skeleton" style={{ height: '1.25rem', width: '80%' }} />
            <span className="skeleton" style={{ height: '1rem', width: '45%' }} />
          </div>
        ))}
      </div>
    )
  }

  if (groups.length === 0) {
    return (
      <div className="empty-state">
        No series yet — <Link to="/manage/imports">import</Link> a results, standings, or entry-list file
        to get started.
      </div>
    )
  }

  const showFilter = groups.length > 6

  return (
    <section>
      <div className="dir-header">
        <h1 className="dir-title">Series</h1>
        {showFilter && (
          <div className="dir-filter">
            <input
              type="search"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder={`Filter ${groups.length} series…`}
              aria-label="Filter series by name"
            />
          </div>
        )}
      </div>

      {filtered.length === 0 ? (
        <p className="dir-nomatch">
          No series match “{query.trim()}”.
        </p>
      ) : (
        <div className="dir-grid">
          {filtered.map((g) => (
            <div key={g.name} className="dir-card">
              <div className="dir-card-body">
                <div className="dir-card-head">
                  {g.seriesId != null && g.logoVersion != null ? (
                    <img
                      className="dir-logo"
                      src={`/api/series/${g.seriesId}/logo/data?v=${g.logoVersion}`}
                      alt={`${g.name} logo`}
                    />
                  ) : (
                    <span className="dir-monogram" aria-hidden="true">
                      {monogram(g)}
                    </span>
                  )}
                  <span className="dir-card-go" aria-hidden="true">
                    →
                  </span>
                </div>

                <span className="dir-card-name">{g.name}</span>

                {g.classes.length > 1 && (
                  <span className="dir-card-classes">
                    {g.classes.map((c) => (
                      <span key={c.code} className="dir-class">
                        <i className="swatch" style={{ background: c.color }} />
                        {c.code}
                      </span>
                    ))}
                  </span>
                )}
              </div>

              <div className="dir-card-foot">
                <div className="dir-card-season">
                  <span className="dir-card-year">{g.latest.year}</span>
                  <span className="dir-card-meta">
                    {g.latest.roundCount} round{g.latest.roundCount !== 1 ? 's' : ''} ·{' '}
                    {g.latest.championshipCount} championship
                    {g.latest.championshipCount !== 1 ? 's' : ''}
                  </span>
                </div>
                <div className="dir-card-earlier">
                  {g.past.length > 0 ? (
                    <>
                      <span className="dir-card-earlier-label">Earlier</span>
                      {g.past.map((p) => (
                        <Link
                          key={p.id}
                          to={`/seasons/${p.id}`}
                          className="dir-season-chip"
                        >
                          {p.year}
                        </Link>
                      ))}
                    </>
                  ) : (
                    <span className="dir-card-earlier-empty">No earlier seasons</span>
                  )}
                </div>
              </div>

              <Link
                to={`/seasons/${g.latest.id}`}
                className="dir-card-cover"
                aria-label={`Open ${g.name} ${g.latest.year} season hub`}
              />
            </div>
          ))}
        </div>
      )}
    </section>
  )
}
