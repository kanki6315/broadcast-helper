import { useEffect, useMemo, useRef, useState, type CSSProperties } from 'react'
import { createPortal } from 'react-dom'
import { Link } from 'react-router-dom'
import { getJson, type ClassStylesResponse, type SeasonHub } from '../lib/api'
import {
  ChampFilterBar,
  ClassGrid,
  RecapLegend,
  champFamilies,
  champKinds,
  fetchRecap,
  posScopeOf,
  raceLegendTags,
  selectedChamps,
  type ChampSelection,
  type PosScope,
} from '../pages/season/ChampionshipGrid'
import {
  DEFAULT_CLASS_COLOR,
  seasonClasses,
  type ClassInfo,
} from '../pages/season/SeasonLayout'
import '../pages/season.css'
import './recap-modal.css'

/* Season header data is immutable between imports; cache per season/series for
 * the session (the same convention as HubPage), and evict on failure so a
 * dropped request on booth wifi can't become permanent. The recaps themselves
 * ride ChampionshipGrid's shared cache, so a season already browsed on the hub
 * opens here without refetching a byte. */
const seasonHubCache = new Map<number, Promise<SeasonHub>>()
const classStylesCache = new Map<number, Promise<ClassStylesResponse>>()

function fetchSeasonHub(seasonId: number): Promise<SeasonHub> {
  let p = seasonHubCache.get(seasonId)
  if (!p) {
    p = getJson<SeasonHub>(`/api/seasons/${seasonId}`).catch((e) => {
      seasonHubCache.delete(seasonId)
      throw e
    })
    seasonHubCache.set(seasonId, p)
  }
  return p
}

function fetchClassStyles(seriesId: number): Promise<ClassStylesResponse> {
  let p = classStylesCache.get(seriesId)
  if (!p) {
    p = getJson<ClassStylesResponse>(`/api/series/${seriesId}/class-styles`).catch((e) => {
      classStylesCache.delete(seriesId)
      throw e
    })
    classStylesCache.set(seriesId, p)
  }
  return p
}

/**
 * The season recap as a modal over the event sheet — the same ClassGrid,
 * filter vocabulary, and legend as the hub's recap tab, with the class chips
 * on top. Selection lives in local state, not the URL: a modal's filters
 * should not rewrite the sheet's address or history.
 */
export default function RecapModal({
  seasonId,
  currentEventId,
  onClose,
}: {
  seasonId: number
  /** The sheet's event — its round column is marked in every grid. */
  currentEventId: number
  onClose: () => void
}) {
  const dialogRef = useRef<HTMLDialogElement>(null)
  const [hub, setHub] = useState<SeasonHub | null>(null)
  const [styles, setStyles] = useState<ClassStylesResponse | null>(null)
  const [failed, setFailed] = useState(false)
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    const d = dialogRef.current
    if (d && !d.open) d.showModal()
  }, [])

  useEffect(() => {
    let cancelled = false
    setFailed(false)
    fetchSeasonHub(seasonId)
      .then((h) => {
        if (cancelled) return
        setHub(h)
        // Class styles are cosmetic — a failure falls back to neutral colours
        // rather than blocking the recap (same degradation as SeasonLayout).
        void fetchClassStyles(h.seriesId)
          .then((s) => !cancelled && setStyles(s))
          .catch(() => !cancelled && setStyles({ styles: [], unconfiguredClasses: [] }))
      })
      .catch(() => !cancelled && setFailed(true))
    return () => {
      cancelled = true
    }
  }, [seasonId, attempt])

  const classes = useMemo<ClassInfo[]>(() => (hub ? seasonClasses(hub, styles) : []), [hub, styles])
  const classColor = (name: string | null | undefined) =>
    (name && classes.find((c) => c.name === name)?.color) || DEFAULT_CLASS_COLOR

  const [classFilter, setClassFilter] = useState<string | null>(null)

  const withRows = useMemo(
    () => (hub ? hub.championships.filter((c) => c.rowCount > 0) : []),
    [hub],
  )
  const families = useMemo(
    () => champFamilies(withRows, hub?.seriesName ?? ''),
    [withRows, hub?.seriesName],
  )
  const [familySel, setFamilySel] = useState<string | null>(null)
  const family = families.find((f) => f.family === familySel)?.family ?? families[0]?.family ?? null
  const kinds = useMemo(() => champKinds(withRows, family), [withRows, family])
  const [kindSel, setKindSel] = useState<string | null>(null)
  const kind = kinds.includes(kindSel ?? '') ? kindSel : (kinds[0] ?? null)
  const selected = useMemo(
    () => selectedChamps(withRows, family, kind, classes),
    [withRows, family, kind, classes],
  )

  // Switching championship keeps the Teams/Drivers choice when the new family
  // also offers it — the same behaviour as the hub's URL-backed selection.
  function setFamily(f: string) {
    const nextKinds = new Set(
      withRows.filter((c) => (c.groupTitle ?? c.title) === f).map((c) => c.kind),
    )
    setKindSel(kind && nextKinds.has(kind) ? kind : null)
    setFamilySel(f)
  }

  const sel: ChampSelection = {
    families,
    family,
    setFamily,
    kinds,
    kind,
    setKind: setKindSel,
    selected,
  }

  const [showTeams, setShowTeams] = useState(false)

  const shown = useMemo(
    () => (classFilter ? selected.filter((c) => c.className === classFilter) : selected),
    [selected, classFilter],
  )

  // The legend decodes only the notation actually on screen — the recaps are
  // already being fetched by the class grids, so this rides the cache.
  const [raceTags, setRaceTags] = useState<Map<string, string>>(new Map())
  const [posScope, setPosScope] = useState<PosScope>('class')
  useEffect(() => {
    if (shown.length === 0) {
      setRaceTags(new Map())
      return
    }
    let cancelled = false
    Promise.all(shown.map((c) => fetchRecap(c.id)))
      .then((recaps) => {
        if (cancelled) return
        setRaceTags(raceLegendTags(recaps))
        setPosScope(posScopeOf(recaps))
      })
      .catch(() => {
        if (cancelled) return
        setRaceTags(new Map())
        setPosScope('class')
      })
    return () => {
      cancelled = true
    }
  }, [shown])

  const title = hub ? `${hub.seriesName} ${hub.year}` : null

  // Portaled out of the sheet's DOM: the host page scopes its own table
  // vocabulary to `.sheet` descendants (table-layout: fixed, centered cells),
  // which would collapse the recap grid's content-driven columns.
  return createPortal(
    <dialog
      className="rm no-print"
      ref={dialogRef}
      aria-label={`Season recap${title ? `: ${title}` : ''}`}
      onCancel={(e) => {
        e.preventDefault()
        onClose()
      }}
      onClick={(e) => {
        if (e.target === dialogRef.current) onClose()
      }}
    >
      <header className="rm-head">
        <div className="rm-id">
          <h2 className="rm-title">Season recap</h2>
          {title && <p className="rm-sub">{title}</p>}
        </div>
        <button type="button" className="rm-close" aria-label="Close" onClick={onClose}>
          ✕
        </button>
      </header>

      {failed ? (
        <div className="rm-body">
          <p className="error-panel">
            Couldn’t load the season recap.{' '}
            <button
              type="button"
              className="hs-retry"
              onClick={() => {
                seasonHubCache.delete(seasonId)
                setAttempt((n) => n + 1)
              }}
            >
              Retry
            </button>
          </p>
        </div>
      ) : !hub ? (
        <div className="rm-body">
          <div className="skeleton-block" aria-label="Loading season recap">
            <span className="skeleton" />
            <span className="skeleton" />
            <span className="skeleton" />
          </div>
        </div>
      ) : families.length === 0 ? (
        <div className="rm-body">
          <div className="empty-state">
            No standings imported for {hub.seriesName} {hub.year} yet — bring in a standings file
            from the <Link to="/manage/imports">Imports</Link> under Manage and the season recap
            builds itself.
          </div>
        </div>
      ) : (
        <>
          <div className="rm-filters">
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
                    style={{ '--chip-color': c.color } as CSSProperties}
                    onClick={() => setClassFilter(classFilter === c.name ? null : c.name)}
                  >
                    <i className="swatch" />
                    {c.name}
                  </button>
                ))}
              </div>
            )}
            <ChampFilterBar
              sel={sel}
              teams={kind === 'DRIVERS' ? { shown: showTeams, setShown: setShowTeams } : null}
              legend={<RecapLegend tags={raceTags} scope={posScope} />}
            />
            {/* One chip click rewrites every grid below; announce it, as the
              * season header does (WCAG 4.1.3). */}
            <p className="sr-only" role="status" aria-live="polite">
              {classFilter ? `Showing ${classFilter} only` : 'Showing all classes'}
            </p>
          </div>
          <div className="rm-body">
            {shown.length === 0 ? (
              <div className="empty-state">
                No {classFilter} standings in this championship — pick another class or
                championship.
              </div>
            ) : (
              shown.map((c) => (
                <ClassGrid
                  key={c.id}
                  champ={c}
                  mode="recap"
                  showTeams={showTeams}
                  view="breakdown"
                  classColor={classColor}
                  currentEventId={currentEventId}
                />
              ))
            )}
          </div>
        </>
      )}
    </dialog>,
    document.body,
  )
}
