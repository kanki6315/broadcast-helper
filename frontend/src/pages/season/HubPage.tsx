import { useEffect, useMemo, useState } from 'react'
import { Link, useLocation } from 'react-router-dom'
import { getJson, type Lineups, type Recap, type ReferenceTable } from '../../lib/api'
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

function NextRoundWidget() {
  const { hub } = useSeason()
  const { search } = useLocation()
  const today = new Date().toISOString().slice(0, 10)
  const upcoming = hub.events.find((e) => e.eventDate != null && e.eventDate >= today)
  const shown = upcoming ?? hub.events[hub.events.length - 1]

  return (
    <div className="widget">
      <div className="widget-head">
        <h3>{upcoming ? 'Next round' : 'Last round'}</h3>
        <Link to={{ pathname: 'schedule', search }}>Schedule →</Link>
      </div>
      {shown ? (
        <div className="widget-feature">
          <div>
            <div className="widget-feature-name">{shown.name}</div>
            <div className="widget-feature-meta">
              {shown.circuitName}
              {shown.eventDate ? ` · ${shown.eventDate}` : ''}
              {shown.entryCount > 0 ? ` · ${shown.entryCount} entries` : ''}
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

  const champs = useMemo(
    () => (classFilter ? sel.selected.filter((c) => c.className === classFilter) : sel.selected),
    [sel.selected, classFilter],
  )

  useEffect(() => {
    let cancelled = false
    Promise.all(champs.map((c) => fetchRecap(c.id)))
      .then((r) => !cancelled && setLeaders(r))
      .catch(() => !cancelled && setLeaders([]))
    return () => {
      cancelled = true
    }
  }, [champs])

  return (
    <div className="widget">
      <div className="widget-head">
        <h3>Championship leaders</h3>
        <Link to={{ pathname: 'standings', search }}>Standings →</Link>
      </div>
      {champs.length === 0 ? (
        <p className="widget-empty">No standings imported yet.</p>
      ) : !leaders ? (
        <div className="skeleton-block">
          <span className="skeleton" />
          <span className="skeleton" />
        </div>
      ) : (
        <div className="widget-rows aligned cols-leaders">
          {leaders.map((r) => {
            const leader = r.rows.find((row) => row.position === 1) ?? r.rows[0]
            if (!leader) return null
            const drivers = r.championship.kind === 'DRIVERS'
            const name = drivers ? leader.competitorName ?? leader.competitorKey : leader.teamName
            return (
              <div key={r.championship.id} className="widget-row">
                <span className="wr-cell">
                  <ClassTag className={r.championship.className} />
                </span>
                <span className="car-no num">{leader.carNumber ?? ''}</span>
                <span className="grow" title={name ?? undefined}>
                  {name}
                </span>
                <span className="num">{formatPoints(leader.totalPoints)} pts</span>
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
}: {
  reference: ReferenceTable | null
  lineups: Lineups | null
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
        <h3>{roundOrdinal != null ? `Last winners · ${venue}` : 'Race results'}</h3>
        <Link to={{ pathname: 'results', search }}>Results →</Link>
      </div>
      {!reference ? (
        <div className="skeleton-block">
          <span className="skeleton" />
          <span className="skeleton" />
        </div>
      ) : winners.length === 0 ? (
        <p className="widget-empty">No race results imported yet.</p>
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

function EntriesWidget({ lineups }: { lineups: Lineups | null }) {
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
        <h3>Entries</h3>
        <Link to={{ pathname: 'entries', search }}>Lineups →</Link>
      </div>
      {!lineups ? (
        <div className="skeleton-block">
          <span className="skeleton" />
          <span className="skeleton" />
        </div>
      ) : counts.length === 0 ? (
        <p className="widget-empty">No entry lists imported yet.</p>
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

export default function HubPage() {
  const { hub } = useSeason()
  const [reference, setReference] = useState<ReferenceTable | null>(null)
  const [lineups, setLineups] = useState<Lineups | null>(null)

  useEffect(() => {
    let cancelled = false
    getJson<ReferenceTable>(`/api/seasons/${hub.id}/reference`)
      .then((r) => !cancelled && setReference(r))
      .catch(() => !cancelled && setReference({ seasonId: hub.id, year: hub.year, seriesName: hub.seriesName, rounds: [], classes: [] }))
    getJson<Lineups>(`/api/seasons/${hub.id}/lineups`)
      .then((l) => !cancelled && setLineups(l))
      .catch(() => !cancelled && setLineups({ seasonId: hub.id, rounds: [], classes: [] }))
    return () => {
      cancelled = true
    }
  }, [hub.id, hub.year, hub.seriesName])

  return (
    <div>
      <div className="widget-grid">
        <NextRoundWidget />
        <LeadersWidget />
        <LastWinnersWidget reference={reference} lineups={lineups} />
        <EntriesWidget lineups={lineups} />
      </div>
      <div className="page-title-row">
        <h2>Season recap</h2>
      </div>
      <ChampionshipGrid mode="recap" />
    </div>
  )
}
