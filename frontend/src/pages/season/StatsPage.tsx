import { useEffect, useMemo, useState } from 'react'
import { getJson, type DriverStatsRow, type StatsTable } from '../../lib/api'
import { useSeason } from './SeasonLayout'
import { useInfoModal } from '../../components/infoModal'

/**
 * Per-driver tallies (starts, wins, podiums, top-5s, DNFs) split by race
 * format, plus qualifying (poles, top-5s). A win is in-class P1; poles come
 * only from qualifying results — a reversed feature grid's front row is not a
 * pole, so these counts can rightly disagree with the recap's start chips.
 * The All-time scope aggregates every season of the series into the same
 * format buckets.
 */

type Scope = 'season' | 'alltime'

const RACE_COLS = [
  { key: 'starts', label: 'St', title: 'Starts (classified entries)' },
  { key: 'wins', label: 'W', title: 'Wins' },
  { key: 'podiums', label: 'P3', title: 'Podiums (top 3)' },
  { key: 'top5s', label: 'T5', title: 'Top 5s' },
  { key: 'dnfs', label: 'DNF', title: 'Did not finish' },
] as const

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

export default function StatsPage() {
  const { hub, classes, classFilter, classColor } = useSeason()
  const { openDriverByName } = useInfoModal()
  const [scope, setScope] = useState<Scope>('season')
  const [data, setData] = useState<StatsTable | null>(null)
  // Column groups the user has toggled off — keyed 'quali', 'none' (Unassigned)
  // or the format id. Offered groups are only ever those present in the scope's
  // data, so a format another series uses can't be selected here.
  const [hidden, setHidden] = useState<Set<string>>(new Set())
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    setData(null)
    setError(null)
    setHidden(new Set())
    const url =
      scope === 'season' ? `/api/seasons/${hub.id}/stats` : `/api/series/${hub.seriesId}/stats`
    getJson<StatsTable>(url)
      .then((d) => {
        if (!cancelled) setData(d)
      })
      .catch(() => {
        if (!cancelled) setError('Could not load the stats.')
      })
    return () => {
      cancelled = true
    }
  }, [hub.id, hub.seriesId, scope])

  // Rows grouped by class (band rows, like the recap grids), honoring the
  // class filter. Backend order (wins, then podiums) is kept within a class.
  const byClass = useMemo(() => {
    if (!data) return []
    const groups = new Map<string, DriverStatsRow[]>()
    for (const row of data.rows) {
      if (classFilter && row.className !== classFilter) continue
      if (!groups.has(row.className)) groups.set(row.className, [])
      groups.get(row.className)!.push(row)
    }
    // Present classes in the season's configured order where known.
    const order = classes.map((c) => c.name)
    return [...groups.entries()].sort(
      (a, b) =>
        (order.indexOf(a[0]) + 1 || order.length + 1) - (order.indexOf(b[0]) + 1 || order.length + 1),
    )
  }, [data, classFilter, classes])

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
      }
      return next
    })
  }

  const colCount = 2 + formats.length * RACE_COLS.length + (showQuali ? 2 : 0)

  return (
    <section aria-label="Stats">
      <div className="filter-bar">
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
              Driver stats by race format
              {scope === 'alltime' ? ` — ${hub.seriesName} all-time` : ` — ${hub.year} season`}
            </caption>
            <thead>
              <tr>
                <th className="ident" scope="col" rowSpan={2}>
                  Driver
                </th>
                <th className="num-cell" scope="col" rowSpan={2}>
                  #
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
                  RACE_COLS.map((c, ci) => (
                    <th
                      key={`${f.id ?? 'none'}-${c.key}`}
                      className={`num-cell sub-head ${ci === 0 ? 'grp-start' : ''}`.trim()}
                      scope="col"
                      title={c.title}
                    >
                      {c.label}
                    </th>
                  )),
                )}
                {showQuali && (
                  <>
                    <th
                      className="num-cell sub-head grp-start"
                      scope="col"
                      title="Poles (qualifying P1 in class)"
                    >
                      Pole
                    </th>
                    <th className="num-cell sub-head" scope="col" title="Qualifying top 5s">
                      T5
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
                    <tr key={`${row.driverId}-${row.className}`}>
                      <td className="ident name-cell" title={row.driverName}>
                        <button
                          type="button"
                          className="drv-link"
                          onClick={() => openDriverByName(row.driverName)}
                        >
                          {row.driverName}
                        </button>
                      </td>
                      <td className="num-cell car-no">{row.carNumber ?? ''}</td>
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
