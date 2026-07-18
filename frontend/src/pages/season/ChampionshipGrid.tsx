import { useEffect, useMemo, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { getJson, type ChampionshipSummary, type Recap } from '../../lib/api'
import { useInfoModal } from '../../components/infoModal'
import RaceLine from '../../components/RaceLine'
import { useSeason } from './SeasonLayout'

/* Recaps are immutable between imports; cache per championship for the session
 * so filter toggles don't refetch. */
const recapCache = new Map<number, Promise<Recap>>()

export function fetchRecap(id: number): Promise<Recap> {
  let p = recapCache.get(id)
  if (!p) {
    p = getJson<Recap>(`/api/championships/${id}/recap`)
    recapCache.set(id, p)
  }
  return p
}

export function formatPoints(points: number): string {
  // NUMERIC totals arrive as JS numbers; String() already drops a trailing .0
  // and keeps real half-points ("19.5").
  return String(points)
}

interface Family {
  family: string
  label: string
  isCup: boolean
}

/** Short segment labels: the season's own championship reads "Championship";
 * cups drop the series-name prefix ("IMSA Michelin Endurance Cup" → "Michelin
 * Endurance Cup"). */
function familyLabel(family: string, seriesName: string): string {
  if (family === seriesName) return 'Championship'
  let label = family
  if (label.startsWith(seriesName)) label = label.slice(seriesName.length).trim()
  const firstWord = seriesName.split(' ')[0]
  if (label === family && label.startsWith(firstWord + ' ')) {
    label = label.slice(firstWord.length + 1)
  }
  return label || family
}

function kindLabel(kind: string): string {
  const lower = kind.toLowerCase()
  return lower.charAt(0).toUpperCase() + lower.slice(1)
}

export interface ChampSelection {
  families: Family[]
  family: string | null
  setFamily: (f: string) => void
  kinds: string[]
  kind: string | null
  setKind: (k: string) => void
  /** championships matching family+kind (all classes), display-ordered */
  selected: ChampionshipSummary[]
}

export function useChampSelection(): ChampSelection {
  const { hub, classes } = useSeason()
  const [searchParams, setSearchParams] = useSearchParams()

  const withRows = useMemo(
    () => hub.championships.filter((c) => c.rowCount > 0),
    [hub.championships],
  )

  const families = useMemo<Family[]>(() => {
    const seen = new Map<string, Family>()
    for (const c of withRows) {
      const family = c.groupTitle ?? c.title
      if (!seen.has(family)) {
        seen.set(family, {
          family,
          label: familyLabel(family, hub.seriesName),
          isCup: c.isCup,
        })
      }
    }
    return [...seen.values()]
  }, [withRows, hub.seriesName])

  const familyParam = searchParams.get('champ')
  const family =
    families.find((f) => f.family === familyParam)?.family ?? families[0]?.family ?? null

  const kinds = useMemo(() => {
    const seen: string[] = []
    for (const c of withRows) {
      if ((c.groupTitle ?? c.title) === family && c.kind && !seen.includes(c.kind)) {
        seen.push(c.kind)
      }
    }
    // Teams first — matches the sheet's champ-column preference.
    return seen.sort((a, b) => rank(a) - rank(b))
  }, [withRows, family])

  const kindParam = searchParams.get('kind')
  const kind = kinds.includes(kindParam ?? '') ? kindParam : (kinds[0] ?? null)

  const selected = useMemo(() => {
    const classOrder = new Map(classes.map((c, i) => [c.name, i]))
    return withRows
      .filter((c) => (c.groupTitle ?? c.title) === family && c.kind === kind)
      .sort(
        (a, b) =>
          (classOrder.get(a.className ?? '') ?? 99) - (classOrder.get(b.className ?? '') ?? 99),
      )
  }, [withRows, family, kind, classes])

  function put(key: string, value: string) {
    const next = new URLSearchParams(searchParams)
    next.set(key, value)
    setSearchParams(next, { replace: true })
  }

  // Switching championship keeps the Teams/Drivers choice when the new family
  // also offers it — dropping it silently reset the user's selection to Teams.
  function switchFamily(f: string) {
    const next = new URLSearchParams(searchParams)
    next.set('champ', f)
    const nextKinds = new Set(
      withRows.filter((c) => (c.groupTitle ?? c.title) === f).map((c) => c.kind),
    )
    if (kind && nextKinds.has(kind)) next.set('kind', kind)
    else next.delete('kind')
    setSearchParams(next, { replace: true })
  }

  return {
    families,
    family,
    setFamily: switchFamily,
    kinds,
    kind,
    setKind: (k) => put('kind', k),
    selected,
  }
}

function rank(kind: string): number {
  switch (kind) {
    case 'TEAMS':
      return 0
    case 'DRIVERS':
      return 1
    default:
      return 2
  }
}

export function ChampFilterBar({ sel, legend }: { sel: ChampSelection; legend: boolean }) {
  return (
    <div className="filter-bar">
      {sel.families.length > 1 && (
        <div className="seg" role="group" aria-label="Championship">
          {sel.families.map((f) => (
            <button
              key={f.family}
              type="button"
              className={sel.family === f.family ? 'seg-btn active' : 'seg-btn'}
              aria-pressed={sel.family === f.family}
              onClick={() => sel.setFamily(f.family)}
            >
              {f.label}
            </button>
          ))}
        </div>
      )}
      {sel.kinds.length > 1 && (
        <div className="seg" role="group" aria-label="Competitor type">
          {sel.kinds.map((k) => (
            <button
              key={k}
              type="button"
              className={sel.kind === k ? 'seg-btn active' : 'seg-btn'}
              aria-pressed={sel.kind === k}
              onClick={() => sel.setKind(k)}
            >
              {kindLabel(k)}
            </button>
          ))}
        </div>
      )}
      {legend && (
        <>
          <span className="spacer" />
          <div className="legend" aria-label="Cell colours">
            <span className="l-win">
              <i /> Win
            </span>
            <span className="l-top3">
              <i /> Top 3
            </span>
            <span className="l-top5">
              <i /> Top 5
            </span>
            <span className="l-dnf">
              <i /> DNF
            </span>
            <span className="l-note">start/finish in class</span>
            <span className="l-pole">P = pole</span>
            <span>R = retired</span>
            <span>· = no entry</span>
          </div>
        </>
      )}
    </div>
  )
}

/* ------------------------------------------------------------------------- */

function ClassGrid({ champ, mode }: { champ: ChampionshipSummary; mode: 'recap' | 'points' }) {
  const { classColor } = useSeason()
  const { openDriverByName, openTeam } = useInfoModal()
  const [recap, setRecap] = useState<Recap | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    fetchRecap(champ.id)
      .then((r) => !cancelled && setRecap(r))
      .catch((e) => !cancelled && setError(e instanceof Error ? e.message : 'Failed to load'))
    return () => {
      cancelled = true
    }
  }, [champ.id])

  if (error) return <p className="error-panel">{error}</p>
  if (!recap) {
    return (
      <div className="skeleton-block">
        <span className="skeleton" />
        <span className="skeleton" />
        <span className="skeleton" />
      </div>
    )
  }

  const rounds = recap.rounds
  const leaderPoints = recap.rows.length > 0 ? Math.max(...recap.rows.map((r) => r.totalPoints)) : 0
  const drivers = recap.championship.kind === 'DRIVERS'
  const color = classColor(champ.className)

  // Sticky identity columns need explicit offsets. Widths are border-box (see
  // season.css) so they include the 20px cell padding — the cumulated offsets
  // below are only correct because of that.
  const identCols =
    mode === 'recap'
      ? [
          { key: 'pos', label: 'Pos', w: 52, cls: 'pos-cell' },
          { key: 'pts', label: 'Pts', w: 68, cls: 'num-cell' },
          { key: 'back', label: 'Back', w: 68, cls: 'num-cell back-cell' },
          { key: 'car', label: '#', w: 60, cls: 'car-no' },
          {
            key: 'name',
            label: drivers ? 'Driver' : 'Team',
            w: 260,
            cls: 'name-cell',
          },
        ]
      : [
          { key: 'pos', label: 'Pos', w: 52, cls: 'pos-cell' },
          { key: 'car', label: '#', w: 60, cls: 'car-no' },
          {
            key: 'name',
            label: drivers ? 'Driver' : 'Team',
            w: 260,
            cls: 'name-cell',
          },
        ]
  const lefts: number[] = []
  identCols.reduce((acc, c) => {
    lefts.push(acc)
    return acc + c.w
  }, 0)

  // The offset goes out as a custom property, never an inline `left` — the
  // stylesheet owns `left` so the narrow-screen rule can unpin the header.
  const identStyle = (i: number): React.CSSProperties =>
    ({
      '--ident-left': `${lefts[i]}px`,
      minWidth: identCols[i].w,
      maxWidth: identCols[i].key === 'name' ? identCols[i].w : undefined,
    }) as React.CSSProperties

  const gridLabel = `${champ.className} ${kindLabel(champ.kind ?? '')} — ${
    mode === 'recap' ? 'season recap, start and finish by round' : 'championship points by round'
  }`

  return (
    <table className="grid-table">
      <caption className="sr-only">{gridLabel}</caption>
      <thead>
        <tr>
          {identCols.map((c, i) => (
            <th
              key={c.key}
              className="ident"
              scope="col"
              style={identStyle(i)}
              title={c.key === 'back' ? 'Points behind the class leader' : undefined}
            >
              {c.label}
            </th>
          ))}
          {rounds.map((r) => (
            <th key={r.round} className="round-head" scope="col">
              <span className="venue">{r.venue}</span>
              <span className="rd">Rd {r.round}</span>
            </th>
          ))}
          {mode === 'points' && (
            <>
              <th className="num-cell">Total</th>
              <th className="num-cell" title="Points behind the class leader">
                Back
              </th>
            </>
          )}
          <th className="grid-soak" aria-hidden="true" />
        </tr>
      </thead>
      <tbody>
        <tr className="class-band">
          <td
            colSpan={identCols.length + rounds.length + (mode === 'points' ? 2 : 0) + 1}
            style={{ '--class-color': color } as React.CSSProperties}
          >
            <span className="band-label">
              {champ.className} · {kindLabel(champ.kind ?? '')}
            </span>
          </td>
        </tr>
        {recap.rows.map((row) => {
          const back = leaderPoints - row.totalPoints
          const name = drivers ? (row.competitorName ?? row.competitorKey) : row.teamName
          return (
            <tr key={row.competitorKey}>
              {identCols.map((c, i) => {
                const style = identStyle(i)
                switch (c.key) {
                  case 'pos':
                    return (
                      <td key={c.key} className={`ident ${c.cls}`} style={style}>
                        {row.position}
                      </td>
                    )
                  case 'pts':
                    return (
                      <td key={c.key} className={`ident ${c.cls}`} style={style}>
                        {formatPoints(row.totalPoints)}
                      </td>
                    )
                  case 'back':
                    return (
                      <td key={c.key} className={`ident ${c.cls}`} style={style}>
                        {back === 0 ? '—' : formatPoints(back)}
                      </td>
                    )
                  case 'car':
                    return (
                      <td key={c.key} className={`ident ${c.cls}`} style={style}>
                        {row.carNumber ?? ''}
                      </td>
                    )
                  default:
                    return (
                      <td
                        key={c.key}
                        className={`ident ${c.cls}`}
                        style={style}
                        title={name ?? undefined}
                      >
                        {name ? (
                          // The row header opens the competitor's info
                          // modal — driver or team by championship kind.
                          <button
                            type="button"
                            className="drv-link"
                            onClick={() => (drivers ? openDriverByName(name) : openTeam(name))}
                          >
                            {name}
                          </button>
                        ) : (
                          name
                        )}
                      </td>
                    )
                }
              })}
              {rounds.map((r) => {
                if (mode === 'points') {
                  const pts = row.pointsByRound[r.round]
                  return (
                    <td key={r.round} className="num-cell" style={{ textAlign: 'center' }}>
                      {pts != null ? formatPoints(pts) : <span className="muted">—</span>}
                    </td>
                  )
                }
                const races = row.cells[r.round]
                return (
                  <td key={r.round} className="race-cell">
                    {races && races.length > 0 ? (
                      races.map((race) => <RaceLine key={race.race} r={race} />)
                    ) : (
                      <span className="cell-skip" title="Did not enter this round">
                        ·
                      </span>
                    )}
                  </td>
                )
              })}
              {mode === 'points' && (
                <>
                  <td className="num-cell" style={{ fontWeight: 700 }}>
                    {formatPoints(row.totalPoints)}
                  </td>
                  <td className="num-cell back-cell">{back === 0 ? '—' : formatPoints(back)}</td>
                </>
              )}
              <td className="grid-soak" />
            </tr>
          )
        })}
      </tbody>
    </table>
  )
}

export default function ChampionshipGrid({ mode }: { mode: 'recap' | 'points' }) {
  const { classFilter } = useSeason()
  const sel = useChampSelection()

  const shown = classFilter ? sel.selected.filter((c) => c.className === classFilter) : sel.selected

  if (sel.families.length === 0) {
    return (
      <div className="empty-state">
        No standings imported yet — bring in a standings file from the{' '}
        <Link to="/imports">Imports</Link> tab and the season recap builds itself.
      </div>
    )
  }

  return (
    <div>
      <ChampFilterBar sel={sel} legend={mode === 'recap'} />
      {shown.length === 0 ? (
        <div className="empty-state">
          No {classFilter} standings in this championship — pick another class or championship.
        </div>
      ) : (
        shown.map((c) => <ClassGrid key={c.id} champ={c} mode={mode} />)
      )}
    </div>
  )
}
