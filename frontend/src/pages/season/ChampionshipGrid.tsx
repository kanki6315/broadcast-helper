import { useEffect, useMemo, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { getJson, type ChampionshipSummary, type Recap, type RecapRace } from '../../lib/api'
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
  return Number.isInteger(points) ? String(points) : String(points)
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
        seen.set(family, { family, label: familyLabel(family, hub.seriesName), isCup: c.isCup })
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
    if (key === 'champ') next.delete('kind') // kinds differ per family
    setSearchParams(next, { replace: true })
  }

  return {
    families,
    family,
    setFamily: (f) => put('champ', f),
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

export function ChampFilterBar({
  sel,
  legend,
}: {
  sel: ChampSelection
  legend: boolean
}) {
  return (
    <div className="filter-bar">
      {sel.families.length > 1 && (
        <div className="seg" role="group" aria-label="Championship">
          {sel.families.map((f) => (
            <button
              key={f.family}
              type="button"
              className={sel.family === f.family ? 'seg-btn active' : 'seg-btn'}
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
            <span className="l-pole">P = pole</span>
          </div>
        </>
      )}
    </div>
  )
}

/* ------------------------------------------------------------------------- */

function raceTier(r: RecapRace): string {
  if (r.notFinished) return 'res-dnf'
  if (r.finish === 1) return 'res-win'
  if (r.finish != null && r.finish <= 3) return 'res-top3'
  if (r.finish != null && r.finish <= 5) return 'res-top5'
  return ''
}

function isDns(r: RecapRace): boolean {
  return (r.status ?? '').toLowerCase().includes('not started')
}

function RaceLine({ r }: { r: RecapRace }) {
  if (isDns(r)) {
    return <span className="race-line muted">DNS</span>
  }
  // No grid imported → just the finish; a start→finish pair only when both are
  // known (pole renders as P). A known start with no finish yet is "4/–": the
  // grid for a race still to run.
  const startPart =
    r.start === 1 ? (
      <span className="pole" title="Started from pole">
        P
      </span>
    ) : (
      r.start
    )
  return (
    <span className={`race-line ${raceTier(r)}`.trim()}>
      {r.start != null ? (
        <>
          {startPart}/{r.finish ?? '–'}
        </>
      ) : (
        (r.finish ?? '–')
      )}
    </span>
  )
}

function ClassGrid({
  champ,
  mode,
}: {
  champ: ChampionshipSummary
  mode: 'recap' | 'points'
}) {
  const { classColor } = useSeason()
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

  // Sticky identity columns need explicit offsets; widths are fixed per column.
  const identCols =
    mode === 'recap'
      ? [
          { key: 'pos', label: 'Pos', w: 48, cls: 'pos-cell' },
          { key: 'pts', label: 'Pts', w: 64, cls: 'num-cell' },
          { key: 'back', label: 'Back', w: 64, cls: 'num-cell back-cell' },
          { key: 'car', label: '#', w: 52, cls: 'car-no' },
          { key: 'name', label: drivers ? 'Driver' : 'Team', w: 240, cls: 'name-cell' },
        ]
      : [
          { key: 'pos', label: 'Pos', w: 48, cls: 'pos-cell' },
          { key: 'car', label: '#', w: 52, cls: 'car-no' },
          { key: 'name', label: drivers ? 'Driver' : 'Team', w: 240, cls: 'name-cell' },
        ]
  const lefts: number[] = []
  identCols.reduce((acc, c) => {
    lefts.push(acc)
    return acc + c.w
  }, 0)

  const identStyle = (i: number): React.CSSProperties => ({
    left: lefts[i],
    minWidth: identCols[i].w,
    maxWidth: identCols[i].key === 'name' ? identCols[i].w : undefined,
  })

  return (
    <div className="grid-scroll">
      <table className="grid-table">
        <thead>
          <tr>
            {identCols.map((c, i) => (
              <th key={c.key} className="ident" style={identStyle(i)}>
                {c.label}
              </th>
            ))}
            {rounds.map((r) => (
              <th key={r.round} className="round-head">
                <span className="venue">{r.venue}</span>
                <span className="rd">Rd {r.round}</span>
              </th>
            ))}
            {mode === 'points' && (
              <>
                <th className="num-cell">Total</th>
                <th className="num-cell">Back</th>
              </>
            )}
          </tr>
        </thead>
        <tbody>
          <tr className="class-band">
            <td
              colSpan={identCols.length + rounds.length + (mode === 'points' ? 2 : 0)}
              style={{ '--class-color': color } as React.CSSProperties}
            >
              {champ.className} · {kindLabel(champ.kind ?? '')}
            </td>
          </tr>
          {recap.rows.map((row) => {
            const back = leaderPoints - row.totalPoints
            const name = drivers ? row.competitorName ?? row.competitorKey : row.teamName
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
                        <td key={c.key} className={`ident ${c.cls}`} style={style} title={name ?? undefined}>
                          {name}
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
                        <span className="cell-skip">·</span>
                      )}
                    </td>
                  )
                })}
                {mode === 'points' && (
                  <>
                    <td className="num-cell" style={{ fontWeight: 700 }}>
                      {formatPoints(row.totalPoints)}
                    </td>
                    <td className="num-cell back-cell">
                      {back === 0 ? '—' : formatPoints(back)}
                    </td>
                  </>
                )}
              </tr>
            )
          })}
        </tbody>
      </table>
    </div>
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
