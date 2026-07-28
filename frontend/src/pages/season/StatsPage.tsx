import { useEffect, useMemo, useState } from 'react'
import {
  getJson,
  type FormatInfo,
  type FormatLine,
  type QualiLine,
  type StatsTable,
  type TeamStatsTable,
} from '../../lib/api'
import { useSeason } from './SeasonLayout'
import { useInfoModal } from '../../components/infoModal'

/**
 * Per-driver or per-team tallies (starts, wins, podiums, top-5s, DNFs) split
 * by race format, plus qualifying (poles, top-5s). A win is in-class P1; poles
 * come only from qualifying results — a reversed feature grid's front row is
 * not a pole, so these counts can rightly disagree with the recap's start
 * chips. The All-time scope aggregates every season of the series into the
 * same format buckets. Team counting is per car-entry (a two-car team scores
 * two starts a race), and a team's quali claim needs no driver attribution.
 */

type Scope = 'season' | 'alltime'
type Mode = 'drivers' | 'teams'

/** One table row in either mode: a driver × class or a team × class. */
interface Row {
  id: number
  name: string
  car: string | null
  className: string
  byFormat: FormatLine[]
  quali: QualiLine
}

interface TableData {
  formats: FormatInfo[]
  rows: Row[]
}

const RACE_COLS = [
  { key: 'starts', label: 'St', title: 'Starts (classified entries)' },
  { key: 'wins', label: 'W', title: 'Wins' },
  { key: 'podiums', label: 'P3', title: 'Podiums (top 3)' },
  { key: 'top5s', label: 'T5', title: 'Top 5s' },
  { key: 'dnfs', label: 'DNF', title: 'Did not finish' },
] as const

/* -- sorting -------------------------------------------------------------- */

type SortDir = 'asc' | 'desc'
/** A column key: the two ident columns, or `fmt:<formatId>:<stat>`, or quali. */
type SortKey = string
interface Sort {
  key: SortKey
  dir: SortDir
}

/** Text columns read A→Z first; a tally column is asked "who has the most",
 * so it opens descending. */
function defaultDir(key: SortKey): SortDir {
  return key === 'name' || key === 'car' ? 'asc' : 'desc'
}

function flipDir(dir: SortDir): SortDir {
  return dir === 'asc' ? 'desc' : 'asc'
}

/** The value a row sorts by, or null for "never contested" — which is not a
 * zero and never sorts among them (see nulls-last in `sortRows`). */
function sortValue(row: Row, key: SortKey): number | string | null {
  if (key === 'name') return row.name.toLowerCase()
  if (key === 'car') return row.car ?? null
  if (key === 'quali:poles') return row.quali.sessions > 0 ? row.quali.poles : null
  if (key === 'quali:top5s') return row.quali.sessions > 0 ? row.quali.top5s : null
  const [, id, col] = key.split(':')
  const line = row.byFormat.find((l) => String(l.formatId ?? 'none') === id)
  return line ? line[col as (typeof RACE_COLS)[number]['key']] : null
}

/** Car numbers are TEXT with significant leading zeros ("04" ≠ "4"), so they
 * order by numeric value first and by the literal string only to break the
 * tie — which keeps 4 and 04 adjacent and the order stable. */
function compareCar(a: string, b: string): number {
  const na = Number.parseInt(a, 10)
  const nb = Number.parseInt(b, 10)
  if (Number.isNaN(na) || Number.isNaN(nb)) return a.localeCompare(b)
  return na - nb || a.localeCompare(b)
}

/** Sorted copy. A driver who never contested a format sorts to the bottom in
 * BOTH directions: "didn't enter" is not a score of zero, and floating it to
 * the top of an ascending sort would read as one. Ties keep the incoming
 * order (Array#sort is stable), which is the backend's own ranking. */
function sortRows(rows: Row[], sort: Sort | null): Row[] {
  if (!sort) return rows
  const flip = sort.dir === 'asc' ? 1 : -1
  return [...rows].sort((ra, rb) => {
    const a = sortValue(ra, sort.key)
    const b = sortValue(rb, sort.key)
    if (a == null || b == null) return a == null ? (b == null ? 0 : 1) : -1
    if (typeof a === 'string' && typeof b === 'string') {
      return (sort.key === 'car' ? compareCar(a, b) : a.localeCompare(b)) * flip
    }
    return ((a as number) - (b as number)) * flip
  })
}

// Non-zero win/podium/top-5 counts wear the recap's result tints (the number is
// always printed — colour never encodes alone). Starts and DNFs stay plain.
function StatValue({
  col,
  value,
}: {
  col: 'starts' | 'wins' | 'podiums' | 'top5s' | 'dnfs' | 'poles' | 'qualiTop5s'
  value: number | null
}) {
  if (value == null) return <span className="stat-zero">·</span>
  const tier =
    value > 0
      ? col === 'wins' || col === 'poles'
        ? 'res-win'
        : col === 'podiums'
          ? 'res-top3'
          : col === 'top5s' || col === 'qualiTop5s'
            ? 'res-top5'
            : ''
      : ''
  if (tier) return <span className={`stat-chip ${tier}`}>{value}</span>
  return <span className={value === 0 ? 'stat-zero' : undefined}>{value}</span>
}

/** A sortable column label. The caret is absolutely positioned beside the
 * label, so turning sort on and off never changes a column's width or the
 * header's height — the table is dense enough that a reserved arrow gutter on
 * 27 columns would be a visible cost. Every sub-head measures at least 9px of
 * slack either side of its label, which is where the caret sits. */
function SortHead({
  label,
  title,
  sortKey,
  sort,
  onSort,
}: {
  label: string
  title: string
  sortKey: SortKey
  sort: Sort | null
  onSort: (key: SortKey) => void
}) {
  const active = sort?.key === sortKey
  const dir = active ? sort.dir : defaultDir(sortKey)
  return (
    <button
      type="button"
      className={`sh-sort${active ? ' is-sorted' : ''} sh-${dir}`}
      title={title}
      onClick={() => onSort(sortKey)}
    >
      {/* The label is its own box so the caret can anchor to the text while
       * the button itself spans the whole cell for a real click target. */}
      <span className="sh-label">{label}</span>
    </button>
  )
}

export default function StatsPage() {
  const { hub, classes, classFilter, classColor } = useSeason()
  const { openDriverByName, openTeamById } = useInfoModal()
  const [scope, setScope] = useState<Scope>('season')
  const [mode, setMode] = useState<Mode>('drivers')
  const [data, setData] = useState<TableData | null>(null)
  // Column groups the user has toggled off — keyed 'quali', 'none' (Unassigned)
  // or the format id. Offered groups are only ever those present in the scope's
  // data, so a format another series uses can't be selected here.
  const [hidden, setHidden] = useState<Set<string>>(new Set())
  const [sort, setSort] = useState<Sort | null>(null)
  const [error, setError] = useState<string | null>(null)

  // Third click clears the sort rather than cycling back to ascending: the
  // default order is the backend's composite ranking (wins, then podiums),
  // which no single column reproduces, so it has to stay reachable.
  function toggleSort(key: SortKey) {
    setSort((prev) => {
      if (prev?.key !== key) return { key, dir: defaultDir(key) }
      return prev.dir === defaultDir(key) ? { key, dir: flipDir(prev.dir) } : null
    })
  }

  useEffect(() => {
    let cancelled = false
    setData(null)
    setError(null)
    setHidden(new Set())
    // Season and all-time expose different format groups, so a sort keyed to
    // one scope's column may not exist in the other; a mode switch changes the
    // ident columns too.
    setSort(null)
    const path = mode === 'drivers' ? 'stats' : 'team-stats'
    const url =
      scope === 'season'
        ? `/api/seasons/${hub.id}/${path}`
        : `/api/series/${hub.seriesId}/${path}`
    const load =
      mode === 'drivers'
        ? getJson<StatsTable>(url).then((d) => ({
            formats: d.formats,
            rows: d.rows.map((r) => ({
              id: r.driverId,
              name: r.driverName,
              car: r.carNumber,
              className: r.className,
              byFormat: r.byFormat,
              quali: r.quali,
            })),
          }))
        : getJson<TeamStatsTable>(url).then((d) => ({
            formats: d.formats,
            rows: d.rows.map((r) => ({
              id: r.teamId,
              name: r.teamName,
              car: r.carNumbers,
              className: r.className,
              byFormat: r.byFormat,
              quali: r.quali,
            })),
          }))
    load
      .then((d) => {
        if (!cancelled) setData(d)
      })
      .catch(() => {
        if (!cancelled) setError('Could not load the stats.')
      })
    return () => {
      cancelled = true
    }
  }, [hub.id, hub.seriesId, scope, mode])

  // Rows grouped by class (band rows, like the recap grids), honoring the
  // class filter. Backend order (wins, then podiums) is kept within a class
  // until a column is sorted — and sorting reorders WITHIN each class, never
  // across them: the class band is a structural division, not a row property.
  const byClass = useMemo(() => {
    if (!data) return []
    const groups = new Map<string, Row[]>()
    for (const row of data.rows) {
      if (classFilter && row.className !== classFilter) continue
      if (!groups.has(row.className)) groups.set(row.className, [])
      groups.get(row.className)!.push(row)
    }
    // Present classes in the season's configured order where known.
    const order = classes.map((c) => c.name)
    return [...groups.entries()]
      .sort(
        (a, b) =>
          (order.indexOf(a[0]) + 1 || order.length + 1) -
          (order.indexOf(b[0]) + 1 || order.length + 1),
      )
      .map(([name, rows]) => [name, sortRows(rows, sort)] as [string, Row[]])
  }, [data, classFilter, classes, sort])

  const hasQuali = useMemo(
    () => (data?.rows ?? []).some((r) => r.quali.sessions > 0),
    [data],
  )

  if (error) {
    return (
      <p className="error-panel" role="alert">
        {error}
      </p>
    )
  }

  const allFormats = data?.formats ?? []
  const keyOf = (id: number | null) => (id == null ? 'none' : String(id))
  const formats = allFormats.filter((f) => !hidden.has(keyOf(f.id)))
  const showQuali = hasQuali && !hidden.has('quali')
  const groupCount = allFormats.length + (hasQuali ? 1 : 0)
  const visibleCount = formats.length + (showQuali ? 1 : 0)

  function toggleGroup(key: string) {
    setHidden((prev) => {
      const next = new Set(prev)
      if (next.has(key)) {
        next.delete(key)
      } else {
        // The last visible group stays — an all-hidden table answers nothing.
        if (visibleCount <= 1) return prev
        next.add(key)
        // Hiding the group that owns the sorted column would leave the rows
        // ordered by a column nobody can see.
        setSort((s) =>
          s && (key === 'quali' ? s.key.startsWith('quali:') : s.key.startsWith(`fmt:${key}:`))
            ? null
            : s,
        )
      }
      return next
    })
  }

  const colCount = 2 + formats.length * RACE_COLS.length + (showQuali ? 2 : 0)

  const ariaSort = (key: SortKey): 'ascending' | 'descending' | 'none' =>
    sort?.key === key ? (sort.dir === 'asc' ? 'ascending' : 'descending') : 'none'

  return (
    <section aria-label="Stats">
      <div className="filter-bar">
        <div className="seg" role="group" aria-label="Stats for">
          <button
            type="button"
            className={mode === 'drivers' ? 'seg-btn active' : 'seg-btn'}
            aria-pressed={mode === 'drivers'}
            onClick={() => setMode('drivers')}
          >
            Drivers
          </button>
          <button
            type="button"
            className={mode === 'teams' ? 'seg-btn active' : 'seg-btn'}
            aria-pressed={mode === 'teams'}
            onClick={() => setMode('teams')}
          >
            Teams
          </button>
        </div>
        <div className="seg" role="group" aria-label="Stats scope">
          <button
            type="button"
            className={scope === 'season' ? 'seg-btn active' : 'seg-btn'}
            aria-pressed={scope === 'season'}
            onClick={() => setScope('season')}
          >
            {hub.year} season
          </button>
          <button
            type="button"
            className={scope === 'alltime' ? 'seg-btn active' : 'seg-btn'}
            aria-pressed={scope === 'alltime'}
            onClick={() => setScope('alltime')}
          >
            All-time
          </button>
        </div>
        {groupCount > 1 && (
          <div className="seg" role="group" aria-label="Formats shown">
            {allFormats.map((f) => {
              const key = keyOf(f.id)
              const on = !hidden.has(key)
              return (
                <button
                  key={key}
                  type="button"
                  className={on ? 'seg-btn active' : 'seg-btn'}
                  aria-pressed={on}
                  onClick={() => toggleGroup(key)}
                >
                  {f.name}
                </button>
              )
            })}
            {hasQuali && (
              <button
                type="button"
                className={!hidden.has('quali') ? 'seg-btn active' : 'seg-btn'}
                aria-pressed={!hidden.has('quali')}
                onClick={() => toggleGroup('quali')}
              >
                Qualifying
              </button>
            )}
          </div>
        )}
        <span className="spacer" />
        <div className="legend" aria-label="Column key">
          <span>St = starts</span>
          <span>W = wins</span>
          <span>P3 = podiums</span>
          <span>T5 = top 5s</span>
          {hasQuali && <span className="l-pole">poles from qualifying only</span>}
        </div>
      </div>

      {data === null ? (
        <div className="grid-scroll" aria-busy="true">
          <div className="skeleton" style={{ height: '12rem' }} />
        </div>
      ) : (
        <div className="grid-scroll">
          <table className="grid-table stats-table">
            <caption className="sr-only">
              {mode === 'drivers' ? 'Driver' : 'Team'} stats by race format
              {scope === 'alltime' ? ` — ${hub.seriesName} all-time` : ` — ${hub.year} season`}
            </caption>
            <thead>
              <tr>
                <th className="ident" scope="col" rowSpan={2} aria-sort={ariaSort('name')}>
                  <SortHead
                    label={mode === 'drivers' ? 'Driver' : 'Team'}
                    title={mode === 'drivers' ? 'Driver name' : 'Team name'}
                    sortKey="name"
                    sort={sort}
                    onSort={toggleSort}
                  />
                </th>
                <th className="num-cell" scope="col" rowSpan={2} aria-sort={ariaSort('car')}>
                  <SortHead
                    label="#"
                    title={mode === 'drivers' ? 'Car number' : 'Car numbers'}
                    sortKey="car"
                    sort={sort}
                    onSort={toggleSort}
                  />
                </th>
                {formats.map((f) => (
                  <th
                    key={f.id ?? 'none'}
                    className="fmt-head grp-start"
                    scope="colgroup"
                    colSpan={RACE_COLS.length}
                  >
                    {f.name}
                  </th>
                ))}
                {showQuali && (
                  <th className="fmt-head grp-start" scope="colgroup" colSpan={2}>
                    Qualifying
                  </th>
                )}
              </tr>
              <tr>
                {formats.flatMap((f) =>
                  RACE_COLS.map((c, ci) => {
                    const key = `fmt:${f.id ?? 'none'}:${c.key}`
                    return (
                      <th
                        key={`${f.id ?? 'none'}-${c.key}`}
                        className={`num-cell sub-head ${ci === 0 ? 'grp-start' : ''}`.trim()}
                        scope="col"
                        aria-sort={ariaSort(key)}
                      >
                        <SortHead
                          label={c.label}
                          title={`${f.name} — ${c.title}`}
                          sortKey={key}
                          sort={sort}
                          onSort={toggleSort}
                        />
                      </th>
                    )
                  }),
                )}
                {showQuali && (
                  <>
                    <th
                      className="num-cell sub-head grp-start"
                      scope="col"
                      aria-sort={ariaSort('quali:poles')}
                    >
                      <SortHead
                        label="Pole"
                        title="Poles (qualifying P1 in class)"
                        sortKey="quali:poles"
                        sort={sort}
                        onSort={toggleSort}
                      />
                    </th>
                    <th className="num-cell sub-head" scope="col" aria-sort={ariaSort('quali:top5s')}>
                      <SortHead
                        label="T5"
                        title="Qualifying top 5s"
                        sortKey="quali:top5s"
                        sort={sort}
                        onSort={toggleSort}
                      />
                    </th>
                  </>
                )}
              </tr>
            </thead>
            {byClass.map(([className, rows]) => (
              <tbody key={className}>
                {byClass.length > 1 || !classFilter ? (
                  <tr className="class-band">
                    <td
                      colSpan={colCount}
                      style={{ '--class-color': classColor(className) } as React.CSSProperties}
                    >
                      <span className="band-label">{className}</span>
                    </td>
                  </tr>
                ) : null}
                {rows.map((row) => {
                  const lineByFormat = new Map(row.byFormat.map((l) => [l.formatId, l]))
                  return (
                    <tr key={`${row.id}-${row.className}`}>
                      <td className="ident name-cell" title={row.name}>
                        <button
                          type="button"
                          className="drv-link"
                          onClick={() =>
                            mode === 'drivers' ? openDriverByName(row.name) : openTeamById(row.id)
                          }
                        >
                          {row.name}
                        </button>
                      </td>
                      <td className="num-cell car-no">{row.car ?? ''}</td>
                      {formats.flatMap((f) => {
                        const line = lineByFormat.get(f.id)
                        return RACE_COLS.map((c, ci) => (
                          <td
                            key={`${f.id ?? 'none'}-${c.key}`}
                            className={`num-cell stat-cell ${ci === 0 ? 'grp-start' : ''}`.trim()}
                          >
                            <StatValue col={c.key} value={line ? line[c.key] : null} />
                          </td>
                        ))
                      })}
                      {showQuali && (
                        <>
                          <td className="num-cell stat-cell grp-start">
                            <StatValue
                              col="poles"
                              value={row.quali.sessions > 0 ? row.quali.poles : null}
                            />
                          </td>
                          <td className="num-cell stat-cell">
                            <StatValue
                              col="qualiTop5s"
                              value={row.quali.sessions > 0 ? row.quali.top5s : null}
                            />
                          </td>
                        </>
                      )}
                    </tr>
                  )
                })}
              </tbody>
            ))}
          </table>
        </div>
      )}
    </section>
  )
}
