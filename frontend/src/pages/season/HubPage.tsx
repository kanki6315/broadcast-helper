import { useEffect, useMemo, useState } from 'react'
import { Link, useLocation } from 'react-router-dom'
import { getJson, type Lineups, type Recap, type ReferenceTable } from '../../lib/api'
import { shortName } from '../../lib/names'
import ChampionshipGrid, {
  fetchRecap,
  formatPoints,
  invalidateRecap,
  kindLabel,
  useChampSelection,
} from './ChampionshipGrid'
import { useSeason } from './SeasonLayout'

function ClassTag({ className }: { className: string | null }) {
  const { classColor } = useSeason()
  // Always render an element so the aligned strip grids keep their cell count.
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

/** A sub-page link that carries the season's current filters, optionally
 * pinning a class. */
function subPage(pathname: string, search: string, className?: string | null) {
  const p = new URLSearchParams(search)
  if (className) p.set('class', className)
  const q = p.toString()
  return { pathname, search: q ? `?${q}` : '' }
}

/* Reference and lineups are immutable between imports, but HubPage unmounts
 * whenever the user visits a sub-page — so every return to Overview was
 * re-downloading them (25kB + 60kB for season 3). Cache per URL for the
 * session, the same way ChampionshipGrid caches recaps, and evict on failure so
 * a dropped request can't become permanent. */
const hubCache = new Map<string, Promise<unknown>>()

function fetchCached<T>(url: string): Promise<T> {
  let p = hubCache.get(url) as Promise<T> | undefined
  if (!p) {
    p = getJson<T>(url).catch((e) => {
      hubCache.delete(url)
      throw e
    })
    hubCache.set(url, p)
  }
  return p
}

/* ---- shared derivations -------------------------------------------------- */

/** The event the hub is about: the next scheduled one, else the newest imported.
 * `hub.events` only holds IMPORTED events, so the newest is rarely the season's
 * final round — "Latest" states that honestly where "Last" asserted a finality
 * the recap (still showing later rounds) contradicts. */
function useShownEvent(reference: ReferenceTable | null) {
  const { hub } = useSeason()
  const today = new Date().toISOString().slice(0, 10)
  const upcoming = hub.events.find((e) => e.eventDate != null && e.eventDate >= today)
  const shown = upcoming ?? hub.events[hub.events.length - 1]

  // When the newest event has no finishes yet, say so: otherwise this reads
  // "Latest round · CTMP" beside "Winners · WGI" — two venues, no explanation,
  // and the broadcaster doubts both.
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

  return { shown, heading: upcoming ? 'Next round' : 'Latest round', awaitingResults }
}

/** Car counts per class this season, scoped to the active class filter. */
function classCounts(lineups: Lineups | null, classFilter: string | null) {
  if (!lineups) return []
  return lineups.classes
    .filter((c) => !classFilter || c.className === classFilter)
    .map((c) => ({ className: c.className, cars: c.cars.length }))
}

/** Cars of one class AT ONE ROUND. The season-wide figure is a different fact
 * and must never be printed under a "Latest round" heading — 68 cars ran this
 * season, 32 of them ran Canadian Tire. */
function roundCarCount(
  lineups: Lineups | null,
  eventId: number | undefined,
  className: string,
): number {
  if (!lineups || eventId == null) return 0
  const round = lineups.rounds.find((r) => r.eventId === eventId)
  const cls = lineups.classes.find((c) => c.className === className)
  if (!round || !cls) return 0
  return cls.cars.filter((car) => car.byRound[round.ordinal]?.length).length
}

/** Lineup changes at the latest round that has entries. */
function lineupChangeNote(lineups: Lineups | null, classFilter: string | null): string | null {
  if (!lineups) return null
  const ordinals = lineups.rounds.map((r) => r.ordinal)
  const last = ordinals[ordinals.length - 1]
  if (last == null) return null
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
  if (changed === 0) return null
  const round = lineups.rounds.find((r) => r.ordinal === last)
  return `${changed} lineup change${changed !== 1 ? 's' : ''} at ${round?.venue ?? `Rd ${last}`}`
}

/** Latest round where anyone has a finish recorded, plus its winners — ONE PER
 * RACE, not one per entry. A weekend that runs two races has two winners per
 * class, and listing both unlabelled under "Winners · IMS" presents two
 * different cars as the same fact. Where the round ran more than one race each
 * winner carries its race tag, exactly as the recap's own lines do. */
function latestWinners(reference: ReferenceTable | null, classFilter: string | null) {
  let roundOrdinal: number | null = null
  let venue = ''
  let raceCount = 1
  const winners: {
    className: string
    carNumber: string
    team: string | null
    raceOrdinal: number
  }[] = []
  if (!reference) return { roundOrdinal, venue, winners, raceCount }
  for (const round of [...reference.rounds].reverse()) {
    const hasFinish = reference.classes.some((cls) =>
      cls.entries.some((e) => e.byRound[round.ordinal]?.some((r) => r.finish != null)),
    )
    if (hasFinish) {
      roundOrdinal = round.ordinal
      venue = round.venue
      raceCount = round.raceCount
      break
    }
  }
  if (roundOrdinal != null) {
    for (const cls of reference.classes) {
      if (classFilter && cls.className !== classFilter) continue
      for (const e of cls.entries) {
        for (const r of e.byRound[roundOrdinal] ?? []) {
          if (r.finish === 1) {
            winners.push({
              className: cls.className,
              carNumber: e.carNumber,
              team: e.team,
              raceOrdinal: r.raceOrdinal,
            })
          }
        }
      }
    }
    // Class order is the reference's; within a class, race order.
    winners.sort((a, b) => a.raceOrdinal - b.raceOrdinal)
    winners.sort(
      (a, b) =>
        reference.classes.findIndex((c) => c.className === a.className) -
        reference.classes.findIndex((c) => c.className === b.className),
    )
  }
  return { roundOrdinal, venue, winners, raceCount }
}

/** The second line of a winner row, or null when it would just repeat the first.
 * A one-driver entry is often entered under the driver's own name, which made
 * these rows read "G. Carroll" over "Graham Carroll" — the same fact twice,
 * spending a line the band is budgeted for. */
function subLine(primary: string | null, secondary: string | null): string | null {
  if (!secondary || !primary) return secondary || null
  const norm = (v: string) => v.toLowerCase().replace(/[^a-z0-9]/g, '')
  if (norm(secondary) === norm(primary)) return null
  if (norm(shortName(secondary)) === norm(primary)) return null
  return secondary
}

/** The winning crew, from the lineups matrix. */
function crewOf(
  lineups: Lineups | null,
  roundOrdinal: number | null,
  className: string,
  carNumber: string,
): string[] {
  if (!lineups || roundOrdinal == null) return []
  const cls = lineups.classes.find((c) => c.className === className)
  const car = cls?.cars.find((c) => c.carNumber === carNumber)
  return (car?.byRound[roundOrdinal] ?? []).filter((d) => !d.isTbd).map((d) => shortName(d.name))
}

/** Championship leaders for the current family/kind selection. */
function useLeaders() {
  const { classFilter } = useSeason()
  const sel = useChampSelection()
  const [leaders, setLeaders] = useState<Recap[] | null>(null)
  const [failed, setFailed] = useState(false)
  const [attempt, setAttempt] = useState(0)

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
  }, [champs, attempt])

  // The title names BOTH axes of what's actually shown. The family was already
  // named (Endurance Cup points under a "Championship" heading would invite an
  // on-air misquote) but the kind is the more misquotable one: the board defaults
  // to Teams, and "Cadillac Whelen · 2145" read out under a bare "Championship
  // leaders" is the drivers' title to anyone listening.
  const familyLabel = sel.families.find((f) => f.family === sel.family)?.label
  const scope =
    familyLabel && familyLabel !== 'Championship' ? `${familyLabel} leaders` : 'Championship leaders'
  const title = sel.kind ? `${scope} · ${kindLabel(sel.kind)}` : scope

  // "No standings imported" is only true when the SEASON has none; a filtered
  // class with none gets copy that says so.
  const emptyCopy = failed
    ? 'Couldn’t load standings — try reloading.'
    : sel.selected.length === 0
      ? 'No standings imported yet.'
      : classFilter
        ? `No ${classFilter} standings in this ${familyLabel === 'Championship' ? 'championship' : familyLabel ?? 'championship'}.`
        : 'No standings imported yet.'

  // Rows are filtered to the CURRENT selection rather than trusting whatever
  // the last resolved fetch left behind. Flipping a class chip re-runs the
  // effect, and until it resolves `leaders` still holds the previous class's
  // recaps — rendering those would put four boards under a one-class filter.
  const wanted = new Set(champs.map((c) => c.id))
  const rows = (leaders ?? [])
    .filter((r) => wanted.has(r.championship.id))
    .map((r) => {
      const leader = r.rows.find((row) => row.position === 1) ?? r.rows[0]
      if (!leader) return null
      const drivers = r.championship.kind === 'DRIVERS'
      return {
        key: r.championship.id,
        className: r.championship.className,
        carNumber: leader.carNumber ?? '',
        name: (drivers ? leader.competitorName ?? leader.competitorKey : leader.teamName) ?? '',
        points: `${formatPoints(leader.totalPoints)} pts`,
      }
    })
    .filter((r): r is NonNullable<typeof r> => r != null)

  const retry = () => {
    for (const c of champs) invalidateRecap(c.id)
    setAttempt((n) => n + 1)
  }

  return {
    rows,
    title,
    emptyCopy,
    failed,
    retry,
    loading: !leaders,
    empty: champs.length === 0,
  }
}

/* ---- row shapes ---------------------------------------------------------- */

/** One aligned line: class pill · car number · linked name · value.
 * The name is the deep link (padded to a ≥24px hit target) — wrapping the whole
 * row would break the `display: contents` column alignment. */
function LineRow({
  className,
  carNumber,
  name,
  value,
  to,
  /** Accessible name, when the visible text isn't self-describing out of
   * context. "12 cars" is a fine label beside its class pill and a useless one
   * in a screen reader's list of links, which has no pill. Must contain the
   * visible text (WCAG 2.5.3, Label in Name). */
  fullName,
}: {
  className: string | null
  carNumber?: string
  name: string
  value?: string | null
  to: { pathname: string; search: string }
  fullName?: string
}) {
  return (
    <div className="hs-row">
      <ClassTag className={className} />
      {carNumber != null && <span className="car-no num">{carNumber}</span>}
      <span className="wr-name">
        <Link
          className="wr-link"
          to={to}
          title={fullName ?? name}
          aria-label={fullName}
        >
          {name}
        </Link>
      </span>
      {value != null && <span className="num wr-val">{value}</span>}
    </div>
  )
}

/** A winner: entrant over crew. Two lines, because at strip width a crew of two
 * plus a team name does not fit one — and a clipped "J. Hawkswo…" is worse than
 * the ~45px the second line costs. */
function WinnerRow({
  className,
  carNumber,
  primary,
  secondary,
  raceTag,
  to,
}: {
  className: string
  carNumber: string
  primary: string
  secondary: string | null
  /** Which race of the round this won. Null on single-race rounds — and then
   * the cell renders no tag column at all, so the common case is unchanged.
   * Every row in a tagged list renders the cell, empty or not: the rows are
   * `display: contents`, so an uneven cell count would shear the columns. */
  raceTag: string | null
  to: { pathname: string; search: string }
}) {
  return (
    <div className="hs-row">
      <ClassTag className={className} />
      <span className="car-no num">{carNumber}</span>
      {raceTag !== null && <span className="race-tag">{raceTag}</span>}
      <span className="wr-name">
        <Link
          className="wr-link"
          to={to}
          title={
            [raceTag && `Race ${raceTag.replace(/^R/, '')}`, primary, secondary]
              .filter(Boolean)
              .join(' — ')
          }
        >
          {primary}
        </Link>
        {secondary && <span className="wr-crew">{secondary}</span>}
      </span>
    </div>
  )
}

/* The strip's whole premise is a band under ~226px so the recap clears the fold.
 * These caps are what stop unusual-but-real data from spending the recap's
 * screen: a six-heat weekend is five winners per class, and a series can add
 * classes. Nothing is hidden silently — the overflow line says how many and
 * links to the page that lists them all. */
const MAX_LEADER_ROWS = 6
const MAX_WINNER_ROWS = 4
const MAX_ENTRY_ROWS = 6
const MAX_MISSING_NAMES = 3

function MoreLine({ n, to, what }: { n: number; to: { pathname: string; search: string }; what: string }) {
  return (
    <p className="hs-note hs-more-line">
      <Link className="wr-link" to={to}>
        {n} more {what} →
      </Link>
    </p>
  )
}

function CellError({ what, onRetry }: { what: string; onRetry: () => void }) {
  return (
    <p className="hs-empty">
      Couldn’t load {what}.{' '}
      <button type="button" className="hs-retry" onClick={onRetry}>
        Retry
      </button>
    </p>
  )
}

function CellSkeleton() {
  return (
    <div className="skeleton-block">
      <span className="skeleton" />
      <span className="skeleton" />
    </div>
  )
}

function StripCell({
  id,
  title,
  to,
  linkLabel,
  children,
}: {
  id: string
  title: string
  to: { pathname: string; search: string }
  linkLabel: string
  children: React.ReactNode
}) {
  return (
    <section className="hs-cell" aria-labelledby={id}>
      <div className="hs-head">
        <h2 id={id}>{title}</h2>
        <Link className="hs-more" to={to}>
          {linkLabel} <span aria-hidden="true">→</span>
        </Link>
      </div>
      {children}
    </section>
  )
}

/* ---- the strip ----------------------------------------------------------- */

function HubStrip({
  reference,
  lineups,
  refFailed,
  lineupsFailed,
  onRetry,
}: {
  reference: ReferenceTable | null
  lineups: Lineups | null
  refFailed: boolean
  lineupsFailed: boolean
  onRetry: () => void
}) {
  const { classFilter, classes } = useSeason()
  const { search } = useLocation()
  const { shown, heading, awaitingResults } = useShownEvent(reference)
  const leaders = useLeaders()
  const { roundOrdinal, venue, winners, raceCount } = latestWinners(reference, classFilter)
  const tagWinners = raceCount > 1
  const shownWinners = winners.slice(0, MAX_WINNER_ROWS)
  const counts = classCounts(lineups, classFilter)
  const note = lineupChangeNote(lineups, classFilter)

  // A class with no winner at this round is printed, not omitted — silence
  // reads as "nothing imported" when it means "didn't win here".
  const missingAll = classes
    .filter((c) => !classFilter || c.name === classFilter)
    .filter((c) => !winners.some((w) => w.className === c.name))
    .map((c) => c.name)
  // Naming every absent class stops being useful once it's most of the grid.
  const missing =
    missingAll.length > MAX_MISSING_NAMES
      ? `${missingAll.length} classes have`
      : `${missingAll.join(', ')} ${missingAll.length === 1 ? 'has' : 'have'}`

  // Scoped to THIS round. Without a filter the event's own count is
  // authoritative; under one, only the lineups matrix can answer.
  const roundEntries = (() => {
    if (!shown) return null
    if (!classFilter) return shown.entryCount > 0 ? `${shown.entryCount} entries` : null
    const n = roundCarCount(lineups, shown.id, classFilter)
    return n > 0 ? `${n} ${classFilter} entries` : null
  })()

  return (
    <div className="hub-strip">
      <StripCell
        id="hs-round"
        title={shown?.roundOrdinal != null ? `${heading} · Rd ${shown.roundOrdinal}` : heading}
        to={subPage('schedule', search)}
        linkLabel="Schedule"
      >
        {shown ? (
          <div className="hs-stack">
            <span className="hs-strong">{shown.name}</span>
            <span className="hs-sub">
              {shown.circuitName}
              {shown.eventDate ? ` · ${shortDate(shown.eventDate)}` : ''}
              {roundEntries ? ` · ${roundEntries}` : ''}
            </span>
            {awaitingResults && <span className="hs-sub">Results not imported yet</span>}
            {note && <span className="hs-note">{note}</span>}
          </div>
        ) : (
          <p className="hs-empty">No events imported yet.</p>
        )}
      </StripCell>

      <StripCell
        id="hs-leaders"
        title={leaders.title}
        to={subPage('standings', search)}
        linkLabel="Standings"
      >
        {leaders.failed ? (
          <CellError what="standings" onRetry={leaders.retry} />
        ) : leaders.empty ? (
          <p className="hs-empty">{leaders.emptyCopy}</p>
        ) : leaders.loading ? (
          <CellSkeleton />
        ) : (
          <div className="hs-rows hs-leaders">
            {leaders.rows.slice(0, MAX_LEADER_ROWS).map((r) => (
              <LineRow
                key={r.key}
                className={r.className}
                carNumber={r.carNumber}
                name={r.name}
                value={r.points}
                to={subPage('standings', search, r.className)}
              />
            ))}
            {leaders.rows.length > MAX_LEADER_ROWS && (
              <MoreLine
                n={leaders.rows.length - MAX_LEADER_ROWS}
                what="championships"
                to={subPage('standings', search)}
              />
            )}
          </div>
        )}
      </StripCell>

      <StripCell
        id="hs-winners"
        title={roundOrdinal != null ? `Winners · ${venue}` : 'Winners'}
        to={subPage('results', search)}
        linkLabel="Results"
      >
        {refFailed ? (
          <CellError what="results" onRetry={onRetry} />
        ) : !reference ? (
          <CellSkeleton />
        ) : winners.length === 0 ? (
          <p className="hs-empty">
            {roundOrdinal != null && classFilter
              ? `No ${classFilter} winner at ${venue}.`
              : 'No race results imported yet.'}
          </p>
        ) : (
          <div className={tagWinners ? 'hs-rows hs-winners hs-winners-tagged' : 'hs-rows hs-winners'}>
            {shownWinners.map((w) => {
              const crew = crewOf(lineups, roundOrdinal, w.className, w.carNumber)
              const primary = crew.length === 1 ? crew[0] : w.team
              const secondary = subLine(primary, crew.length === 1 ? w.team : crew.join(' · '))
              return (
                <WinnerRow
                  key={`${w.className}-${w.carNumber}-${w.raceOrdinal}`}
                  className={w.className}
                  carNumber={w.carNumber}
                  primary={primary ?? ''}
                  secondary={secondary || null}
                  raceTag={tagWinners ? `R${w.raceOrdinal}` : null}
                  to={subPage('results', search, w.className)}
                />
              )
            })}
            {winners.length > shownWinners.length && (
              <MoreLine
                n={winners.length - shownWinners.length}
                what={`winners at ${venue}`}
                to={subPage('results', search)}
              />
            )}
            {missingAll.length > 0 && (
              <p className="hs-note">
                {missing} no winner at {venue}
              </p>
            )}
          </div>
        )}
      </StripCell>

      <StripCell id="hs-entries" title="Entries" to={subPage('entries', search)} linkLabel="Entries">
        {lineupsFailed ? (
          <CellError what="lineups" onRetry={onRetry} />
        ) : !lineups ? (
          <CellSkeleton />
        ) : counts.length === 0 ? (
          <p className="hs-empty">
            {classFilter && lineups.classes.length > 0
              ? `No ${classFilter} entries this season.`
              : 'No entry lists imported yet.'}
          </p>
        ) : (
          <div className="hs-rows hs-entries">
            {counts.slice(0, MAX_ENTRY_ROWS).map((c) => (
              <LineRow
                key={c.className}
                className={c.className}
                name={`${c.cars} cars`}
                fullName={`${c.className} — ${c.cars} cars`}
                to={subPage('entries', search, c.className)}
              />
            ))}
            {counts.length > MAX_ENTRY_ROWS && (
              <MoreLine
                n={counts.length - MAX_ENTRY_ROWS}
                what="classes"
                to={subPage('entries', search)}
              />
            )}
          </div>
        )}
      </StripCell>
    </div>
  )
}

export default function HubPage() {
  const { hub } = useSeason()
  const [reference, setReference] = useState<ReferenceTable | null>(null)
  const [lineups, setLineups] = useState<Lineups | null>(null)
  // A failed request must never masquerade as "nothing imported" — the cells
  // say "couldn't load" instead of an empty state that reads as data loss.
  const [refFailed, setRefFailed] = useState(false)
  const [lineupsFailed, setLineupsFailed] = useState(false)
  // Bumped by the cells' Retry. A dropped request on a booth wifi should cost a
  // click, not a page reload that also throws away the class filter.
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    let cancelled = false
    setRefFailed(false)
    setLineupsFailed(false)
    fetchCached<ReferenceTable>(`/api/seasons/${hub.id}/reference`)
      .then((r) => !cancelled && setReference(r))
      .catch(() => !cancelled && setRefFailed(true))
    fetchCached<Lineups>(`/api/seasons/${hub.id}/lineups`)
      .then((l) => !cancelled && setLineups(l))
      .catch(() => !cancelled && setLineupsFailed(true))
    return () => {
      cancelled = true
    }
  }, [hub.id, attempt])

  return (
    <div>
      <HubStrip
        reference={reference}
        lineups={lineups}
        refFailed={refFailed}
        lineupsFailed={lineupsFailed}
        onRetry={() => {
          hubCache.delete(`/api/seasons/${hub.id}/reference`)
          hubCache.delete(`/api/seasons/${hub.id}/lineups`)
          setAttempt((n) => n + 1)
        }}
      />
      <div className="page-title-row">
        <h2>Season recap</h2>
      </div>
      <ChampionshipGrid mode="recap" />
    </div>
  )
}
