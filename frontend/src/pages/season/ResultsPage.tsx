import { useEffect, useMemo, useRef, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import {
  getJson,
  type EventResults,
  type FlagRecord,
  type ResultRow,
  type SessionResults,
} from '../../lib/api'
import StartingGridModal from '../../components/StartingGridModal'
import { useInfoModal } from '../../components/infoModal'
import { useSeason } from './SeasonLayout'
import { venueOf } from './venue'

function TeamLink({ name }: { name: string | null }) {
  const { openTeam } = useInfoModal()
  if (!name) return null
  return (
    <button type="button" className="drv-link" onClick={() => openTeam(name)}>
      {name}
    </button>
  )
}

function TeamCell({ name }: { name: string | null }) {
  return (
    <td className="name-cell" title={name ?? undefined}>
      <TeamLink name={name} />
    </td>
  )
}

/** The backend joins crew names with ", " — split them back into modal links.
 * TBD seats stay plain text. */
function DriverLinks({ names }: { names: string | null }) {
  const { openDriverByName } = useInfoModal()
  if (!names) return null
  return (
    <>
      {names.split(', ').map((name, i) => (
        <span key={`${name}-${i}`}>
          {i > 0 && ', '}
          {name === 'TBD' ? (
            name
          ) : (
            <button type="button" className="drv-link" onClick={() => openDriverByName(name)}>
              {name}
            </button>
          )}
        </span>
      ))}
    </>
  )
}

function ClassCell({ className }: { className: string | null }) {
  const { classColor } = useSeason()
  if (!className) return <td />
  return (
    <td>
      <span
        className="class-tag"
        style={{ '--class-color': classColor(className) } as React.CSSProperties}
      >
        {className}
      </span>
    </td>
  )
}

/**
 * The crew, or — under an attributed column — the one driver the session
 * credits: the qualifying driver of record (grid file) first, else the
 * fastest-lap seat. Where neither names anyone we still print the crew rather
 * than a dash, and say so on hover: "one of these two" is the honest answer,
 * and silence isn't.
 */
function DriverCell({
  row,
  fastestBy,
  qualifiedBy,
}: {
  row: ResultRow
  fastestBy: boolean
  qualifiedBy: boolean
}) {
  const attributed =
    qualifiedBy && row.qualifyingDriver != null
      ? row.qualifyingDriver
      : fastestBy && row.fastestLapDriver != null
        ? row.fastestLapDriver
        : null
  const names = attributed ?? row.drivers
  return (
    <td
      className="name-cell soak"
      style={{ maxWidth: 320 }}
      title={
        (qualifiedBy || fastestBy) && !attributed
          ? `No driver credited — showing the full crew: ${row.drivers ?? '—'}`
          : (names ?? undefined)
      }
    >
      <DriverLinks names={names} />
    </td>
  )
}

/* ---- gaps ---------------------------------------------------------------- */

/**
 * A published gap as whole milliseconds. Qualifying prints the leader's own gap
 * as "-" (zero, not missing) and everyone else's as "+1.234"; the "+M:SS.mmm"
 * form only shows up in races, but parse it anyway so this can't silently return
 * null if a qualifying session ever runs long enough to need it. A lap-count gap
 * ("2 Laps") has no millisecond value at all — that's a null, not a zero.
 */
function gapMs(gap: string | null): number | null {
  if (!gap) return null
  if (gap === '-') return 0
  const m = /^\+?(?:(\d+):)?(\d+)\.(\d{1,3})$/.exec(gap.trim())
  if (!m) return null
  const [, min, sec, frac] = m
  return (Number(min ?? 0) * 60 + Number(sec)) * 1000 + Number(frac.padEnd(3, '0'))
}

/** Inverse of gapMs, in the provider's own shape so both gap columns read alike. */
function formatGap(ms: number): string {
  const sign = ms < 0 ? '-' : '+'
  const t = Math.abs(ms)
  const frac = String(t % 1000).padStart(3, '0')
  const whole = Math.floor(t / 1000)
  if (whole < 60) return `${sign}${whole}.${frac}`
  return `${sign}${Math.floor(whole / 60)}:${String(whole % 60).padStart(2, '0')}.${frac}`
}

/**
 * Gap to the class leader, for a session whose gaps are all measured off the
 * same overall leader — so the class gap is exact integer subtraction, never a
 * re-timing. Returns null where either car's gap has no millisecond value,
 * rather than guessing a number this table would be trusted on.
 */
function classGaps(rows: ResultRow[]): Map<ResultRow, string> {
  const leaderMs = new Map<string, number>()
  for (const r of rows) {
    const ms = gapMs(r.gapFirst)
    if (r.className == null || ms == null) continue
    const best = leaderMs.get(r.className)
    if (best == null || ms < best) leaderMs.set(r.className, ms)
  }
  const out = new Map<ResultRow, string>()
  for (const r of rows) {
    const ms = gapMs(r.gapFirst)
    const lead = r.className != null ? leaderMs.get(r.className) : undefined
    if (ms == null || lead == null) continue
    out.set(r, ms === lead ? '-' : formatGap(ms - lead))
  }
  return out
}

/* ---- stewards' notes ----------------------------------------------------- */

/**
 * Map each affected result row's car number to the note texts naming it.
 * Exact match first, then leading-zeros-stripped ("04" = "4") — the same
 * concession the team-sheets matching makes, because the stewards and the
 * entry list don't always agree on the zeros. A note naming a car that isn't
 * in the session still shows in the panel; it just marks no row.
 */
function notesByCar(session: SessionResults): Map<string, string[]> {
  const out = new Map<string, string[]>()
  if (session.notes.length === 0) return out
  const strip = (n: string) => n.replace(/^0+(?=\d)/, '')
  const byExact = new Set<string>()
  const byStripped = new Map<string, string>()
  for (const r of session.results) {
    byExact.add(r.carNumber)
    const s = strip(r.carNumber)
    if (!byStripped.has(s)) byStripped.set(s, r.carNumber)
  }
  for (const note of session.notes) {
    for (const num of note.carNumbers) {
      const key = byExact.has(num) ? num : byStripped.get(strip(num))
      if (!key) continue
      out.set(key, [...(out.get(key) ?? []), note.text])
    }
  }
  return out
}

/** The stewards' notes, verbatim, one line each. The report mark rides along as
 * a quiet pill only when it isn't "Official" — Official is the steady state, and
 * a pill on every session would be one more thing to read past. */
function SessionNotesPanel({ session }: { session: SessionResults }) {
  if (session.notes.length === 0) return null
  const mark = session.reportMark
  return (
    <section className="session-notes" aria-label="Stewards' notes">
      <header className="session-notes-head">
        <h3>Stewards&rsquo; notes</h3>
        {mark && mark !== 'Official' && <span className="report-mark">{mark}</span>}
      </header>
      <ul>
        {session.notes.map((n) => (
          <li key={n.text}>{n.text}</li>
        ))}
      </ul>
    </section>
  )
}

/* ---- race control -------------------------------------------------------- */

/** Provider rec_type → what the chip prints. Unknown future values fall back to
 * the record's own flag text, never to silence. */
const FLAG_LABEL: Record<string, string> = {
  GF: 'Green',
  FCY: 'Full course yellow',
  FF: 'Chequered',
}

/**
 * The session's flag periods and race-control log, imported from the provider's
 * flags report. Collapsed by default — it's reference depth, not the headline —
 * and fetched only on first open so the results payload stays light. Keyed by
 * session at the call site, so switching tabs resets it.
 */
function RaceControl({ sessionId }: { sessionId: number }) {
  const [open, setOpen] = useState(false)
  const [flags, setFlags] = useState<FlagRecord[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [carFilter, setCarFilter] = useState<string | null>(null)

  useEffect(() => {
    if (!open || flags !== null || error !== null) return
    let cancelled = false
    getJson<FlagRecord[]>(`/api/sessions/${sessionId}/flags`)
      .then((f) => !cancelled && setFlags(f))
      .catch((e) => !cancelled && setError(e instanceof Error ? e.message : 'Failed to load'))
    return () => {
      cancelled = true
    }
  }, [open, flags, error, sessionId])

  const periods = flags?.filter((f) => f.recType !== 'RCMessage') ?? []
  const allLog = flags?.filter((f) => f.recType === 'RCMessage') ?? []
  // Every car the stream names, in running-number order — "what happened to
  // car 27?" is one click. Flag periods stay visible regardless of the filter:
  // they're the race's shape, not a car's story.
  const mentionedCars = [...new Set(allLog.flatMap((f) => f.carNumbers))].sort(
    (a, b) => Number(a) - Number(b) || a.localeCompare(b),
  )
  const log = carFilter ? allLog.filter((f) => f.carNumbers.includes(carFilter)) : allLog

  return (
    <section className="race-control">
      <button
        type="button"
        className="rc-toggle"
        aria-expanded={open}
        onClick={() => setOpen((o) => !o)}
      >
        <span className="rc-chevron" aria-hidden="true">
          {open ? '▾' : '▸'}
        </span>
        Race control
      </button>
      {open && (
        <div className="rc-body">
          {error && <p className="error-panel">{error}</p>}
          {!flags && !error && (
            <div className="skeleton-block">
              <span className="skeleton" />
              <span className="skeleton" />
            </div>
          )}
          {flags && (
            <>
              {periods.length > 0 && (
                <ul className="rc-periods" aria-label="Flag periods">
                  {periods.map((f) => (
                    <li key={f.seq} className={`rc-period rc-${f.recType.toLowerCase()}`}>
                      <b>{FLAG_LABEL[f.recType] ?? f.flag ?? f.recType}</b>
                      {f.lap != null && f.lap > 0 && <span> · Lap {f.lap}</span>}
                      {f.flagTime && f.flagTime !== '-' && <span> · {f.flagTime}</span>}
                    </li>
                  ))}
                </ul>
              )}
              {mentionedCars.length > 0 && (
                <div className="rc-cars" role="group" aria-label="Filter messages by car">
                  <button
                    type="button"
                    className={carFilter === null ? 'rc-car-chip active' : 'rc-car-chip'}
                    aria-pressed={carFilter === null}
                    onClick={() => setCarFilter(null)}
                  >
                    All cars
                  </button>
                  {mentionedCars.map((car) => (
                    <button
                      key={car}
                      type="button"
                      className={carFilter === car ? 'rc-car-chip active' : 'rc-car-chip'}
                      aria-pressed={carFilter === car}
                      onClick={() => setCarFilter(carFilter === car ? null : car)}
                    >
                      {car}
                    </button>
                  ))}
                </div>
              )}
              {log.length > 0 ? (
                <ol className="rc-log" aria-label="Race control messages">
                  {log.map((f) => (
                    <li key={f.seq}>
                      <span className="rc-time">
                        {f.elapsed && f.elapsed !== '-' ? f.elapsed : f.wallTime}
                      </span>
                      <span className="rc-msg">{f.message}</span>
                    </li>
                  ))}
                </ol>
              ) : (
                <p className="muted">
                  {carFilter
                    ? `No race-control messages name car ${carFilter}.`
                    : 'No race-control messages in this session.'}
                </p>
              )}
            </>
          )}
        </div>
      )}
    </section>
  )
}

/** A finish that needs no comment. Everything else is the exception the Status
 * column exists to surface, so only those carry the error mark — a wall of red
 * "Classified" is exactly the second-guess this table must never invite. */
function isClassified(status: string | null): boolean {
  const s = status?.toLowerCase()
  return s === 'classified' || s === 'running'
}

/** Positions gained from the published starting grid. A positive value is a
 * gain (started P8, finished P5 → +3); a negative value is a loss. Missing
 * grid/finish positions stay unknown rather than being presented as zero. */
function positionChange(row: ResultRow, gridByCar: Map<string, number>): number | null {
  const start = gridByCar.get(row.carNumber)
  return start == null || row.posOverall == null ? null : start - row.posOverall
}

function formatPositionChange(change: number | null): string {
  if (change == null) return ''
  return change > 0 ? `+${change}` : String(change)
}

/**
 * One session's classification. Qualifying and race are the same table with
 * different questions asked of it, so the columns follow the session:
 * qualifying names who put the car on the grid and calls the lap a lap;
 * the race reports elapsed time, the crew, and its fastest lap.
 *
 * Columns whose data the import didn't carry are dropped rather than printed
 * empty — an empty column is a column of doubt in a lookup tool.
 */
function ResultsTable({ session }: { session: SessionResults }) {
  const { classFilter } = useSeason()
  const isQualifying = session.sessionType === 'QUALIFYING'
  const rows: ResultRow[] = classFilter
    ? session.results.filter((r) => r.className === classFilter)
    : session.results

  const label = isQualifying ? 'Qualifying classification' : 'Race classification'
  // Qualifying is a gap column and nothing else: the provider carries no
  // elapsed time for it, and the leader's own "-" is the reference the rest of
  // the column is read against. A race leads with its winning time instead.
  const timeOf = (r: ResultRow) =>
    isQualifying ? r.gapFirst : r.posOverall === 1 ? r.elapsedTime : r.gapFirst
  // Measured against the whole session, not the filtered rows: the column set is
  // a property of the session, and a table that changes shape — or renames a
  // header — as you flip class chips is a table you have to re-read every time.
  // Class gaps come from the whole session too: the class leader is the class
  // leader whether or not the current filter is showing them.
  const all = session.results
  const gridByCar = new Map(
    session.grid.flatMap((g) => (g.posOverall == null ? [] : [[g.carNumber, g.posOverall] as const])),
  )
  const gapInClass = classGaps(all)
  const carNotes = notesByCar(session)
  const has = {
    laps: all.some((r) => r.laps != null),
    time: all.some((r) => timeOf(r)),
    fastest: all.some((r) => r.fastestLapTime),
    onLap: all.some((r) => r.fastestLapNumber != null),
    status: all.some((r) => !isClassified(r.status)),
    // Race-only, and only where at least one classified row has a matching
    // published grid position. The class filter must not reshape the table.
    positionChange:
      !isQualifying && all.some((r) => positionChange(r, gridByCar) != null),
    // Providers either populate the whole session or none of it. Requiring a
    // complete race prevents a missing value from looking like a zero-stop run.
    pitStops:
      !isQualifying && all.length > 0 && all.every((r) => r.pitStops != null),
    // Qualifying only: an entry the provider attributed to a seat. Falls back to
    // the full crew where it named none, so the cell is never a dead end.
    fastestBy: all.some((r) => r.fastestLapDriver),
    // Qualifying only: the qualifying driver of record from the grid file's
    // attribution — the official answer, preferred over the fastest-lap seat.
    qualifiedBy: all.some((r) => r.qualifyingDriver),
    // Qualifying only. Every qualifying gap is plain seconds off the same
    // overall pole, so the class gap is exact subtraction. A race mixes those
    // with lap-count gaps ("9 Laps") that carry no time at all, which would
    // leave a third of the column blank — a lap-aware race gap is its own job.
    // Single-class sessions are excluded: the class gap just restates the
    // overall one.
    classGap:
      isQualifying &&
      new Set(all.map((r) => r.className)).size > 1 &&
      all.some((r) => gapInClass.has(r)),
  }

  // Where the Team column sits relative to the driver(s) — or whether it shows at
  // all. A team name that only ever restates the driver (hosted iRacing sets
  // team_name to the driver's own name) carries nothing, so drop the column. When
  // it does say something, its side depends on who the star of the row is: a
  // multi-driver car is a team entry, so team leads and the crew follows; a
  // single-seat entry is a driver, so the driver leads and the team trails.
  const teamInformative = all.some((r) => {
    const t = r.teamName?.trim()
    if (!t) return false
    return (
      t !== r.drivers?.trim() && t !== r.fastestLapDriver?.trim() && t !== r.qualifyingDriver?.trim()
    )
  })
  const multiDriver = all.some((r) => (r.drivers ?? '').includes(', '))
  const teamPos: 'hidden' | 'before' | 'after' = !teamInformative
    ? 'hidden'
    : multiDriver
      ? 'before'
      : 'after'

  if (rows.length === 0) {
    return (
      <div className="empty-state">
        {session.results.length === 0
          ? 'Nothing imported for this session yet — import a results file from Manage → Imports.'
          : `No ${classFilter} cars in this session.`}
      </div>
    )
  }

  return (
    <div className="grid-scroll">
      <table className="grid-table">
      <caption className="sr-only">{label}</caption>
      <thead>
        <tr>
          <th className="num-cell" scope="col">
            Pos
          </th>
          <th className="num-cell" scope="col">
            PIC
          </th>
          {has.positionChange && (
            <th
              className="num-cell"
              scope="col"
              aria-label="Positions gained or lost from starting grid"
              title="Positions gained or lost from starting grid"
            >
              ± Pos
            </th>
          )}
          <th scope="col">Class</th>
          <th className="num-cell" scope="col">
            #
          </th>
          {teamPos === 'before' && <th scope="col">Team</th>}
          <th className="soak" scope="col">
            {isQualifying && has.qualifiedBy
              ? 'Qualified by'
              : isQualifying && has.fastestBy
                ? 'Fastest lap by'
                : 'Drivers'}
          </th>
          {teamPos === 'after' && <th scope="col">Team</th>}
          <th scope="col">Car</th>
          {has.laps && (
            <th className="num-cell" scope="col">
              Laps
            </th>
          )}
          {has.pitStops && (
            <th className="num-cell" scope="col">
              Pit stops
            </th>
          )}
          {has.fastest && (
            <th className="num-cell" scope="col">
              {isQualifying ? 'Best lap' : 'Fastest'}
            </th>
          )}
          {has.onLap && (
            <th className="num-cell" scope="col">
              On lap
            </th>
          )}
          {has.time && (
            <th className="num-cell" scope="col">
              {isQualifying ? 'Gap' : 'Time / Gap'}
            </th>
          )}
          {has.classGap && (
            <th className="num-cell" scope="col">
              Class gap
            </th>
          )}
          {has.status && <th scope="col">Status</th>}
        </tr>
      </thead>
      <tbody>
        {rows.map((r) => (
          // Separated: car 5 in P43 and car 54 in P3 both concatenate to "543".
          <tr key={`${r.carNumber}-${r.posOverall ?? ''}`}>
            <td className="pos-cell">{r.posOverall ?? '—'}</td>
            <td className="num-cell">{r.posInClass ?? '—'}</td>
            {has.positionChange && (
              <td className="num-cell">{formatPositionChange(positionChange(r, gridByCar))}</td>
            )}
            <ClassCell className={r.className} />
            <td className="car-no">
              {r.carNumber}
              {carNotes.has(r.carNumber) && (
                <span
                  className="note-flag"
                  title={carNotes.get(r.carNumber)!.join('\n')}
                  aria-label={`Stewards' note: ${carNotes.get(r.carNumber)!.join('; ')}`}
                >
                  ※
                </span>
              )}
            </td>
            {teamPos === 'before' && <TeamCell name={r.teamName} />}
            <DriverCell
              row={r}
              fastestBy={isQualifying && has.fastestBy}
              qualifiedBy={isQualifying && has.qualifiedBy}
            />
            {teamPos === 'after' && <TeamCell name={r.teamName} />}
            <td className="name-cell" style={{ maxWidth: 200 }} title={r.vehicle ?? undefined}>
              {r.vehicle}
            </td>
            {has.laps && <td className="num-cell">{r.laps ?? ''}</td>}
            {has.pitStops && <td className="num-cell">{r.pitStops}</td>}
            {has.fastest && <td className="num-cell">{r.fastestLapTime ?? ''}</td>}
            {has.onLap && <td className="num-cell back-cell">{r.fastestLapNumber ?? ''}</td>}
            {has.time && <td className="num-cell">{timeOf(r)}</td>}
            {has.classGap && <td className="num-cell">{gapInClass.get(r) ?? ''}</td>}
            {has.status && (
              <td>
                {isClassified(r.status) ? '' : <span className="status-dnf">{r.status}</span>}
              </td>
            )}
          </tr>
        ))}
      </tbody>
      </table>
    </div>
  )
}

/** "Qualifying" / "Race", or "Race 1" / "Race 2" where the weekend had two. */
function sessionLabel(s: SessionResults, raceCount: number): string {
  if (s.sessionType !== 'RACE') return 'Qualifying'
  return raceCount === 1 ? 'Race' : s.name
}

export default function ResultsPage() {
  const { hub, classFilter, classColor } = useSeason()
  const [searchParams, setSearchParams] = useSearchParams()
  const [results, setResults] = useState<EventResults | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [gridOpen, setGridOpen] = useState(false)
  const tabsRef = useRef<HTMLDivElement>(null)

  // Rounds that have sessions to show, latest first pick.
  const rounds = useMemo(
    () => hub.events.filter((e) => e.roundOrdinal != null && e.sessionCount > 0),
    [hub.events],
  )

  const eventParam = Number(searchParams.get('event'))
  const selected = rounds.find((e) => e.id === eventParam) ?? rounds[rounds.length - 1] ?? null

  useEffect(() => {
    if (!selected) return
    let cancelled = false
    setResults(null)
    setError(null)
    getJson<EventResults>(`/api/events/${selected.id}/results`)
      .then((r) => !cancelled && setResults(r))
      .catch((e) => !cancelled && setError(e instanceof Error ? e.message : 'Failed to load'))
    return () => {
      cancelled = true
    }
  }, [selected])

  const sessions = results?.sessions ?? []
  const races = sessions.filter((s) => s.sessionType === 'RACE')

  // Session ids are per-event, so a `session` param carried across a round
  // change simply doesn't match — fall through to the race, which is the
  // headline result of any weekend that has one.
  const sessionParam = Number(searchParams.get('session'))
  const active =
    sessions.find((s) => s.sessionId === sessionParam) ?? races[0] ?? sessions[0] ?? null

  // The grid belongs to the race it starts; switching away closes it.
  useEffect(() => {
    setGridOpen(false)
  }, [active])

  if (rounds.length === 0) {
    return (
      <div className="empty-state">
        No session results yet — import a results or grid file from Manage → Imports.
      </div>
    )
  }

  function setParam(key: string, value: string) {
    const next = new URLSearchParams(searchParams)
    next.set(key, value)
    if (key === 'event') next.delete('session')
    setSearchParams(next, { replace: true })
  }

  // Roving arrow keys across the tablist, per the tabs pattern: Left/Right move
  // and select, Home/End jump to the ends.
  function onTabKey(e: React.KeyboardEvent) {
    const keys = ['ArrowLeft', 'ArrowRight', 'Home', 'End']
    if (!keys.includes(e.key)) return
    e.preventDefault()
    const i = sessions.findIndex((s) => s.sessionId === active?.sessionId)
    const next =
      e.key === 'Home'
        ? 0
        : e.key === 'End'
          ? sessions.length - 1
          : (i + (e.key === 'ArrowRight' ? 1 : -1) + sessions.length) % sessions.length
    setParam('session', String(sessions[next].sessionId))
    tabsRef.current?.querySelectorAll('button')[next]?.focus()
  }

  const hasGrid = active?.sessionType === 'RACE' && active.grid.length > 0

  return (
    <div>
      <div className="round-chips" role="group" aria-label="Round">
        {rounds.map((e) => (
          <button
            key={e.id}
            type="button"
            className={selected?.id === e.id ? 'round-chip active' : 'round-chip'}
            aria-pressed={selected?.id === e.id}
            title={e.name}
            onClick={() => setParam('event', String(e.id))}
          >
            <span className="venue">{venueOf(e.name, e.circuitName)}</span>
            <span className="rd">Rd {e.roundOrdinal}</span>
          </button>
        ))}
      </div>
      {error && <p className="error-panel">{error}</p>}
      {!results && !error && (
        <div className="skeleton-block">
          <span className="skeleton" />
          <span className="skeleton" />
          <span className="skeleton" />
          <span className="skeleton" />
        </div>
      )}
      {results && (
        <>
          <div className="page-title-row">
            <h2>{results.eventName}</h2>
            <span className="muted">
              {results.circuitName}
              {results.eventDate ? ` · ${results.eventDate}` : ''}
            </span>
          </div>
          {!active ? (
            <div className="empty-state">
              No qualifying or race sessions imported for this event.
            </div>
          ) : (
            <>
              <div className="session-bar">
                {/* A tablist of one is a label, not a control — the event title
                    above already says which session this is. */}
                {sessions.length > 1 && (
                  <div className="seg" role="tablist" aria-label="Session" ref={tabsRef}>
                    {sessions.map((s) => {
                      const on = s.sessionId === active.sessionId
                      return (
                        <button
                          key={s.sessionId}
                          type="button"
                          role="tab"
                          id={`session-tab-${s.sessionId}`}
                          aria-selected={on}
                          aria-controls={`session-panel-${s.sessionId}`}
                          tabIndex={on ? 0 : -1}
                          className={on ? 'seg-btn active' : 'seg-btn'}
                          onKeyDown={onTabKey}
                          onClick={() => setParam('session', String(s.sessionId))}
                        >
                          {sessionLabel(s, races.length)}
                        </button>
                      )
                    })}
                  </div>
                )}
                {hasGrid && (
                  <button type="button" className="btn" onClick={() => setGridOpen(true)}>
                    Starting grid
                  </button>
                )}
              </div>
              <div
                role="tabpanel"
                id={`session-panel-${active.sessionId}`}
                aria-labelledby={`session-tab-${active.sessionId}`}
                tabIndex={-1}
              >
                <SessionNotesPanel session={active} />
                <ResultsTable session={active} />
                {active.hasFlags && (
                  <RaceControl key={active.sessionId} sessionId={active.sessionId} />
                )}
              </div>
              {gridOpen && hasGrid && (
                <StartingGridModal
                  rows={active.grid}
                  title={`${results.eventName} · ${sessionLabel(active, races.length)}`}
                  classColor={classColor}
                  classFilter={classFilter}
                  onClose={() => setGridOpen(false)}
                />
              )}
            </>
          )}
        </>
      )}
    </div>
  )
}
