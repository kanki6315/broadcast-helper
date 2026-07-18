import { useEffect, useMemo, useState } from 'react'
import { Link, useLocation } from 'react-router-dom'
import { getJson, type Lineups, type Recap, type ReferenceTable, type StatsTable } from '../../lib/api'
import { shortName } from '../../lib/names'
import ChampionshipGrid, { fetchRecap, formatPoints, useChampSelection } from './ChampionshipGrid'
import { useSeason } from './SeasonLayout'

function ClassTag({ className }: { className: string | null }) {
  const { classColor } = useSeason()
  // Always render an element so aligned widget grids keep their cell count.
  if (!className) return <span />
  return (
    <span className="class-tag" style={{ '--class-color': classColor(className) } as React.CSSProperties}>
      {className}
    </span>
  )
}

/** "Jul 12" reads faster on air than 2026-07-12; the year is already the page
 * context. Falls back to the raw string on unparseable input. */
function shortDate(iso: string): string {
  const d = new Date(`${iso}T00:00:00`)
  if (Number.isNaN(d.getTime())) return iso
  return new Intl.DateTimeFormat('en-US', { month: 'short', day: 'numeric' }).format(d)
}

function NextRoundWidget({
  lineups,
  reference,
}: {
  lineups: Lineups | null
  reference: ReferenceTable | null
}) {
  const { hub, classFilter } = useSeason()
  const { search } = useLocation()
  const today = new Date().toISOString().slice(0, 10)
  const upcoming = hub.events.find((e) => e.eventDate != null && e.eventDate >= today)
  const shown = upcoming ?? hub.events[hub.events.length - 1]

  // hub.events only holds IMPORTED events, so the newest one here is rarely the
  // season's final round — "Latest" states that honestly where "Last" asserted a
  // finality the recap (still showing later rounds) contradicts.
  const heading = upcoming ? 'Next round' : 'Latest round'

  // When the newest event has no finishes yet, say so: otherwise this widget
  // reads "Latest round · CTMP" beside "Last winners · WGI" — two venues, no
  // explanation, and the broadcaster doubts both.
  const awaitingResults =
    !upcoming &&
    shown != null &&
    reference != null &&
    reference.rounds.some((r) => r.eventId === shown.id) &&
    !reference.classes.some((cls) =>
      cls.entries.some((e) =>
        Object.entries(e.byRound).some(
          ([ord, races]) =>
            reference.rounds.find((r) => r.eventId === shown.id)?.ordinal === Number(ord) &&
            races.some((race) => race.finish != null),
        ),
      ),
    )

  // Under a class filter the event's all-class entry count would contradict the
  // Entries widget beside it ("33 entries" next to "14 cars"), so scope it.
  let entryLabel: string | null = null
  if (shown) {
    if (!classFilter) {
      entryLabel = shown.entryCount > 0 ? `${shown.entryCount} entries` : null
    } else if (lineups) {
      const round = lineups.rounds.find((r) => r.eventId === shown.id)
      const cls = lineups.classes.find((c) => c.className === classFilter)
      if (round && cls) {
        const n = cls.cars.filter((c) => c.byRound[round.ordinal]?.length).length
        entryLabel = n > 0 ? `${n} ${classFilter} entries` : null
      }
    }
  }

  return (
    <div className="widget">
      <div className="widget-head">
        <h2>{heading}</h2>
        <Link to={{ pathname: 'schedule', search }}>Schedule →</Link>
      </div>
      {shown ? (
        <div className="widget-feature">
          <div>
            <div className="widget-feature-name">{shown.name}</div>
            <div className="widget-feature-meta">
              {shown.circuitName}
              {shown.eventDate ? ` · ${shortDate(shown.eventDate)}` : ''}
              {entryLabel ? ` · ${entryLabel}` : ''}
              {awaitingResults && (
                <>
                  <br />
                  Results not imported yet
                </>
              )}
            </div>
          </div>
          {shown.roundOrdinal != null && (
            <div className="widget-round-no">
              <small>Round</small>
              {shown.roundOrdinal}
            </div>
          )}
        </div>
      ) : (
        <p className="widget-empty">No events imported yet.</p>
      )}
    </div>
  )
}

function LeadersWidget() {
  const { classFilter } = useSeason()
  const { search } = useLocation()
  const sel = useChampSelection()
  const [leaders, setLeaders] = useState<Recap[] | null>(null)
  const [failed, setFailed] = useState(false)

  const champs = useMemo(
    () => (classFilter ? sel.selected.filter((c) => c.className === classFilter) : sel.selected),
    [sel.selected, classFilter],
  )

  useEffect(() => {
    let cancelled = false
    setFailed(false)
    Promise.all(champs.map((c) => fetchRecap(c.id)))
      .then((r) => !cancelled && setLeaders(r))
      .catch(() => {
        if (!cancelled) {
          setLeaders([])
          setFailed(true)
        }
      })
    return () => {
      cancelled = true
    }
  }, [champs])

  // The title names what's actually shown — Endurance Cup points under a
  // "Championship" heading would invite an on-air misquote.
  const familyLabel = sel.families.find((f) => f.family === sel.family)?.label
  const title =
    familyLabel && familyLabel !== 'Championship' ? `${familyLabel} leaders` : 'Championship leaders'

  // "No standings imported" is only true when the SEASON has none; a filtered
  // class with none gets copy that says so.
  const emptyCopy = failed
    ? 'Couldn’t load standings — try reloading.'
    : sel.selected.length === 0
      ? 'No standings imported yet.'
      : classFilter
        ? `No ${classFilter} standings in this ${familyLabel === 'Championship' ? 'championship' : familyLabel ?? 'championship'}.`
        : 'No standings imported yet.'

  return (
    <div className="widget">
      <div className="widget-head">
        <h2>{title}</h2>
        <Link to={{ pathname: 'standings', search }}>Standings →</Link>
      </div>
      {failed || champs.length === 0 ? (
        <p className="widget-empty">{emptyCopy}</p>
      ) : !leaders ? (
        <div className="skeleton-block">
          <span className="skeleton" />
          <span className="skeleton" />
        </div>
      ) : (
        <div className="widget-rows aligned cols-winners">
          {leaders.map((r) => {
            const leader = r.rows.find((row) => row.position === 1) ?? r.rows[0]
            if (!leader) return null
            const drivers = r.championship.kind === 'DRIVERS'
            const name = drivers ? leader.competitorName ?? leader.competitorKey : leader.teamName
            return (
              <div key={r.championship.id} className="widget-row wr-two">
                <span className="wr-ident">
                  <ClassTag className={r.championship.className} />
                  <span className="car-no num">{leader.carNumber ?? ''}</span>
                </span>
                <span className="grow">
                  <span className="wr-main" title={name ?? undefined}>
                    {name}
                  </span>
                  <span className="wr-sub num">{formatPoints(leader.totalPoints)} pts</span>
                </span>
              </div>
            )
          })}
        </div>
      )}
    </div>
  )
}

function LastWinnersWidget({
  reference,
  lineups,
  failed,
}: {
  reference: ReferenceTable | null
  lineups: Lineups | null
  failed: boolean
}) {
  const { classFilter } = useSeason()
  const { search } = useLocation()

  let roundOrdinal: number | null = null
  let venue = ''
  const winners: { className: string; carNumber: string; team: string | null }[] = []
  if (reference) {
    // Latest round where anyone has a finish recorded.
    for (const round of [...reference.rounds].reverse()) {
      const hasFinish = reference.classes.some((cls) =>
        cls.entries.some((e) => e.byRound[round.ordinal]?.some((r) => r.finish != null)),
      )
      if (hasFinish) {
        roundOrdinal = round.ordinal
        venue = round.venue
        break
      }
    }
    if (roundOrdinal != null) {
      for (const cls of reference.classes) {
        if (classFilter && cls.className !== classFilter) continue
        for (const e of cls.entries) {
          if (e.byRound[roundOrdinal]?.some((r) => r.finish === 1)) {
            winners.push({ className: cls.className, carNumber: e.carNumber, team: e.team })
          }
        }
      }
    }
  }

  // The winning crew, from the lineups matrix. A single driver takes the top
  // line (the team drops to the sub-line); a full crew lists under the team.
  function crewOf(className: string, carNumber: string): string[] {
    if (!lineups || roundOrdinal == null) return []
    const cls = lineups.classes.find((c) => c.className === className)
    const car = cls?.cars.find((c) => c.carNumber === carNumber)
    return (car?.byRound[roundOrdinal] ?? [])
      .filter((d) => !d.isTbd)
      .map((d) => shortName(d.name))
  }

  return (
    <div className="widget">
      <div className="widget-head">
        <h2>{roundOrdinal != null ? `Last winners · ${venue}` : 'Race results'}</h2>
        <Link to={{ pathname: 'results', search }}>Results →</Link>
      </div>
      {failed ? (
        <p className="widget-empty">Couldn’t load results — try reloading.</p>
      ) : !reference ? (
        <div className="skeleton-block">
          <span className="skeleton" />
          <span className="skeleton" />
        </div>
      ) : winners.length === 0 ? (
        <p className="widget-empty">
          {roundOrdinal != null && classFilter
            ? `No ${classFilter} result at ${venue}.`
            : 'No race results imported yet.'}
        </p>
      ) : (
        <div className="widget-rows aligned cols-winners">
          {winners.map((w) => {
            const crew = crewOf(w.className, w.carNumber)
            const primary = crew.length === 1 ? crew[0] : w.team
            const secondary = crew.length === 1 ? w.team : crew.join(' · ')
            return (
              <div key={`${w.className}-${w.carNumber}`} className="widget-row wr-two">
                <span className="wr-ident">
                  <ClassTag className={w.className} />
                  <span className="car-no num">{w.carNumber}</span>
                </span>
                <span className="grow">
                  <span className="wr-main" title={primary ?? undefined}>
                    {primary}
                  </span>
                  {secondary && (
                    <span className="wr-sub" title={secondary}>
                      {secondary}
                    </span>
                  )}
                </span>
              </div>
            )
          })}
        </div>
      )}
    </div>
  )
}

function EntriesWidget({ lineups, failed }: { lineups: Lineups | null; failed: boolean }) {
  const { classFilter } = useSeason()
  const { search } = useLocation()

  let counts: { className: string; cars: number }[] = []
  let changeNote: string | null = null
  if (lineups) {
    counts = lineups.classes
      .filter((c) => !classFilter || c.className === classFilter)
      .map((c) => ({ className: c.className, cars: c.cars.length }))

    // Lineup changes at the latest round that has entries.
    const ordinals = lineups.rounds.map((r) => r.ordinal)
    const last = ordinals[ordinals.length - 1]
    if (last != null) {
      let changed = 0
      for (const cls of lineups.classes) {
        if (classFilter && cls.className !== classFilter) continue
        for (const car of cls.cars) {
          const now = car.byRound[last]
          if (!now) continue
          const prevRound = [...ordinals].reverse().find((o) => o < last && car.byRound[o])
          if (prevRound == null) continue
          const a = new Set(now.map((d) => d.name))
          const b = new Set((car.byRound[prevRound] ?? []).map((d) => d.name))
          if (a.size !== b.size || [...a].some((n) => !b.has(n))) changed++
        }
      }
      if (changed > 0) {
        const round = lineups.rounds.find((r) => r.ordinal === last)
        changeNote = `${changed} lineup change${changed !== 1 ? 's' : ''} at ${round?.venue ?? `Rd ${last}`}`
      }
    }
  }

  return (
    <div className="widget">
      <div className="widget-head">
        <h2>Entries</h2>
        <Link to={{ pathname: 'entries', search }}>Lineups →</Link>
      </div>
      {failed ? (
        <p className="widget-empty">Couldn’t load lineups — try reloading.</p>
      ) : !lineups ? (
        <div className="skeleton-block">
          <span className="skeleton" />
          <span className="skeleton" />
        </div>
      ) : counts.length === 0 ? (
        <p className="widget-empty">
          {classFilter && lineups.classes.length > 0
            ? `No ${classFilter} entries this season.`
            : 'No entry lists imported yet.'}
        </p>
      ) : (
        <>
          <div className="widget-rows">
            {counts.map((c) => (
              <div key={c.className} className="widget-row">
                <ClassTag className={c.className} />
                <span className="grow" />
                <span className="num">{c.cars} cars</span>
              </div>
            ))}
          </div>
          {changeNote && <p className="widget-empty">{changeNote}</p>}
        </>
      )}
    </div>
  )
}

function StatLeadersWidget({ stats, failed }: { stats: StatsTable | null; failed: boolean }) {
  const { classFilter } = useSeason()
  const { search } = useLocation()

  // Wins summed across formats — the headline number; poles from qualifying
  // results only (absent for series with no quali imported, e.g. IMSA).
  const { winLeaders, poleLeaders } = useMemo(() => {
    const rows = (stats?.rows ?? []).filter((r) => !classFilter || r.className === classFilter)
    const wins = rows
      .map((r) => ({ row: r, n: r.byFormat.reduce((s, l) => s + l.wins, 0) }))
      .filter((x) => x.n > 0)
      .sort((a, b) => b.n - a.n)
      .slice(0, 3)
    const poles = rows
      .map((r) => ({ row: r, n: r.quali.poles }))
      .filter((x) => x.n > 0)
      .sort((a, b) => b.n - a.n)
      .slice(0, 3)
    return { winLeaders: wins, poleLeaders: poles }
  }, [stats, classFilter])

  return (
    <div className="widget">
      <div className="widget-head">
        <h2>Stat leaders</h2>
        <Link to={{ pathname: 'stats', search }}>Stats →</Link>
      </div>
      {failed ? (
        <p className="widget-empty">Couldn’t load stats — try reloading.</p>
      ) : !stats ? (
        <div className="skeleton-block">
          <span className="skeleton" />
          <span className="skeleton" />
        </div>
      ) : winLeaders.length === 0 && poleLeaders.length === 0 ? (
        <p className="widget-empty">
          {classFilter ? `No ${classFilter} wins recorded yet.` : 'No wins recorded yet.'}
        </p>
      ) : (
        <>
          {winLeaders.length > 0 && (
            <>
              <p className="widget-mini-head">Most wins</p>
              <div className="widget-rows">
                {winLeaders.map(({ row, n }) => (
                  <div key={`w-${row.driverId}-${row.className}`} className="widget-row">
                    <ClassTag className={row.className} />
                    <span className="grow wr-main" title={row.driverName}>
                      {row.driverName}
                    </span>
                    <span className="num">{n}</span>
                  </div>
                ))}
              </div>
            </>
          )}
          {poleLeaders.length > 0 && (
            <>
              <p className="widget-mini-head">Most poles</p>
              <div className="widget-rows">
                {poleLeaders.map(({ row, n }) => (
                  <div key={`p-${row.driverId}-${row.className}`} className="widget-row">
                    <ClassTag className={row.className} />
                    <span className="grow wr-main" title={row.driverName}>
                      {row.driverName}
                    </span>
                    <span className="num">{n}</span>
                  </div>
                ))}
              </div>
            </>
          )}
        </>
      )}
    </div>
  )
}

export default function HubPage() {
  const { hub } = useSeason()
  const [reference, setReference] = useState<ReferenceTable | null>(null)
  const [lineups, setLineups] = useState<Lineups | null>(null)
  const [stats, setStats] = useState<StatsTable | null>(null)
  // A failed request must never masquerade as "nothing imported" — the widgets
  // say "couldn't load" instead of an empty state that reads as data loss.
  const [refFailed, setRefFailed] = useState(false)
  const [lineupsFailed, setLineupsFailed] = useState(false)
  const [statsFailed, setStatsFailed] = useState(false)

  useEffect(() => {
    let cancelled = false
    setRefFailed(false)
    setLineupsFailed(false)
    setStatsFailed(false)
    getJson<ReferenceTable>(`/api/seasons/${hub.id}/reference`)
      .then((r) => !cancelled && setReference(r))
      .catch(() => !cancelled && setRefFailed(true))
    getJson<Lineups>(`/api/seasons/${hub.id}/lineups`)
      .then((l) => !cancelled && setLineups(l))
      .catch(() => !cancelled && setLineupsFailed(true))
    getJson<StatsTable>(`/api/seasons/${hub.id}/stats`)
      .then((s) => !cancelled && setStats(s))
      .catch(() => !cancelled && setStatsFailed(true))
    return () => {
      cancelled = true
    }
  }, [hub.id])

  return (
    <div>
      <div className="widget-grid">
        <NextRoundWidget lineups={lineups} reference={reference} />
        <LeadersWidget />
        <LastWinnersWidget reference={reference} lineups={lineups} failed={refFailed} />
        <EntriesWidget lineups={lineups} failed={lineupsFailed} />
        <StatLeadersWidget stats={stats} failed={statsFailed} />
      </div>
      <div className="page-title-row">
        <h2>Season recap</h2>
      </div>
      <ChampionshipGrid mode="recap" />
    </div>
  )
}
