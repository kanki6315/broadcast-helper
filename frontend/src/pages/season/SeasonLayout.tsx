import { useEffect, useMemo, useState } from 'react'
import {
  NavLink,
  Outlet,
  useLocation,
  useNavigate,
  useOutletContext,
  useParams,
  useSearchParams,
} from 'react-router-dom'
import {
  getJson,
  type ClassStylesResponse,
  type SeasonHub,
  type SeasonSummary,
} from '../../lib/api'
import '../season.css'

export interface ClassInfo {
  name: string
  color: string
}

export interface SeasonContext {
  hub: SeasonHub
  classes: ClassInfo[]
  /** null = all classes */
  classFilter: string | null
  classColor: (className: string | null | undefined) => string
}

export function useSeason(): SeasonContext {
  return useOutletContext<SeasonContext>()
}

const DEFAULT_CLASS_COLOR = '#5e626e'

const SUB_PAGES = [
  { to: '', label: 'Overview', end: true },
  { to: 'schedule', label: 'Schedule', end: false },
  { to: 'standings', label: 'Standings', end: false },
  { to: 'stats', label: 'Stats', end: false },
  { to: 'results', label: 'Results', end: false },
  { to: 'entries', label: 'Entries', end: false },
  { to: 'photos', label: 'Photos', end: false },
]

export default function SeasonLayout() {
  const { seasonId } = useParams()
  const navigate = useNavigate()
  const location = useLocation()
  const [searchParams, setSearchParams] = useSearchParams()
  const [hub, setHub] = useState<SeasonHub | null>(null)
  const [seasons, setSeasons] = useState<SeasonSummary[]>([])
  const [styles, setStyles] = useState<ClassStylesResponse | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    setHub(null)
    setError(null)
    getJson<SeasonHub>(`/api/seasons/${seasonId}`)
      .then((h) => {
        if (cancelled) return
        setHub(h)
        void getJson<ClassStylesResponse>(`/api/series/${h.seriesId}/class-styles`)
          .then((s) => !cancelled && setStyles(s))
          .catch(() => !cancelled && setStyles({ styles: [], unconfiguredClasses: [] }))
      })
      .catch((e) => !cancelled && setError(e instanceof Error ? e.message : 'Failed to load'))
    getJson<SeasonSummary[]>('/api/seasons')
      .then((s) => !cancelled && setSeasons(s))
      .catch(() => undefined)
    return () => {
      cancelled = true
    }
  }, [seasonId])

  // The season's classes in configured order (class_style first, then any the
  // config doesn't know, in neutral colour) — but only classes that actually
  // have data this season (entries or standings). A class configured in
  // class_style with no data would otherwise be a permanent dead-end button
  // whose empty states read as "import failed".
  const classes = useMemo<ClassInfo[]>(() => {
    if (!hub) return []
    const present = new Set<string>(hub.entryClasses ?? [])
    for (const c of hub.championships) {
      if (c.className) present.add(c.className)
    }
    const known = new Map<string, string>()
    for (const st of styles?.styles ?? []) known.set(st.classCode, st.color)
    const seen = new Set<string>()
    const out: ClassInfo[] = []
    for (const st of styles?.styles ?? []) {
      if (!present.has(st.classCode)) continue
      out.push({ name: st.classCode, color: st.color })
      seen.add(st.classCode)
    }
    for (const name of present) {
      if (!seen.has(name)) {
        seen.add(name)
        out.push({ name, color: known.get(name) ?? DEFAULT_CLASS_COLOR })
      }
    }
    return out
  }, [hub, styles])

  // Name the tab. Prepping an event means several seasons open at once, and
  // every tab reading "Pit Pass" means clicking each one to find out which is
  // which. Year first: it is short, so it survives the tab's truncation and
  // separates 2026 from 2025 of the same series at a glance.
  useEffect(() => {
    if (!hub) return
    document.title = `${hub.year} · ${hub.seriesName} · Pit Pass`
    return () => {
      document.title = 'Pit Pass'
    }
  }, [hub])

  // A stale bookmark may carry a class this season can't answer; degrade to
  // "All classes" rather than filtering every surface into emptiness.
  const classParam = searchParams.get('class')
  const classFilter =
    classParam && classes.some((c) => c.name === classParam) ? classParam : null

  function setClassFilter(name: string | null) {
    const next = new URLSearchParams(searchParams)
    if (name) next.set('class', name)
    else next.delete('class')
    setSearchParams(next, { replace: true })
  }

  function switchSeason(id: string) {
    // Preserve the sub-page and filters when jumping between years.
    const subPath = location.pathname.replace(/^\/seasons\/\d+/, '')
    navigate(`/seasons/${id}${subPath}${location.search}`)
  }

  if (error) return <p className="error-panel">{error}</p>
  if (!hub) {
    return (
      <div className="skeleton-block" aria-label="Loading season">
        <span className="skeleton" />
        <span className="skeleton" style={{ height: '8rem' }} />
        <span className="skeleton" />
      </div>
    )
  }

  const sameSeries = seasons
    .filter((s) => s.seriesName === hub.seriesName)
    .sort((a, b) => b.year - a.year)
  // Keep the current year visible if the seasons index could not be fetched.
  const visibleSeasons: Array<Pick<SeasonSummary, 'id' | 'year'>> =
    sameSeries.length > 0
      ? sameSeries
      : [{ id: hub.id, year: hub.year }]
  const context: SeasonContext = {
    hub,
    classes,
    classFilter,
    classColor: (name) =>
      (name && classes.find((c) => c.name === name)?.color) || DEFAULT_CLASS_COLOR,
  }

  return (
    <section>
      <header className="season-head">
        <div className="season-toolbar">
          <div className="season-title-row">
            <h1>{hub.seriesName}</h1>
            <div className="seg season-year-strip" role="group" aria-label="Season year">
              {visibleSeasons.map((s) => {
                const active = s.id === hub.id
                return (
                  <button
                    key={s.id}
                    type="button"
                    className={active ? 'seg-btn active' : 'seg-btn'}
                    aria-pressed={active}
                    onClick={() => switchSeason(String(s.id))}
                  >
                    {s.year}
                  </button>
                )
              })}
            </div>
          </div>
          {classes.length > 1 && (
            <div className="class-chips" role="group" aria-label="Class filter">
              <button
                type="button"
                className={classFilter === null ? 'class-chip active' : 'class-chip'}
                aria-pressed={classFilter === null}
                onClick={() => setClassFilter(null)}
              >
                All classes
              </button>
              {classes.map((c) => (
                <button
                  key={c.name}
                  type="button"
                  className={classFilter === c.name ? 'class-chip active' : 'class-chip'}
                  aria-pressed={classFilter === c.name}
                  style={{ '--chip-color': c.color } as React.CSSProperties}
                  onClick={() => setClassFilter(classFilter === c.name ? null : c.name)}
                >
                  <i className="swatch" />
                  {c.name}
                </button>
              ))}
            </div>
          )}
        </div>
        <nav className="seg" aria-label="Season pages">
          {SUB_PAGES.map((p) => (
            <NavLink
              key={p.label}
              to={{ pathname: p.to, search: location.search }}
              end={p.end}
              className={({ isActive }) => (isActive ? 'seg-btn active' : 'seg-btn')}
            >
              {p.label}
            </NavLink>
          ))}
        </nav>
      </header>
      {/* One chip click rewrites all four strip cells and the whole recap. Nothing
        * announced that, so a screen-reader user heard only the button's own
        * pressed state change (WCAG 4.1.3). */}
      <p className="sr-only" role="status" aria-live="polite">
        {classFilter ? `Showing ${classFilter} only` : 'Showing all classes'}
      </p>
      <Outlet context={context} />
    </section>
  )
}
