import { useEffect, useMemo, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import {
  getJson,
  type EventResults,
  type GridRow,
  type ResultRow,
  type SessionResults,
} from '../../lib/api'
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

function ResultsTable({ rows }: { rows: ResultRow[] }) {
  const { classFilter } = useSeason()
  const shown = classFilter ? rows.filter((r) => r.className === classFilter) : rows
  if (shown.length === 0) return <p className="muted">No classified results.</p>
  return (
    <div
      className="grid-scroll"
      style={{ maxHeight: 'none' }}
      tabIndex={0}
      role="region"
      aria-label="Session classification"
    >
      <table className="grid-table">
        <caption className="sr-only">Session classification</caption>
        <thead>
          <tr>
            <th className="num-cell">Pos</th>
            <th className="num-cell">PIC</th>
            <th>Class</th>
            <th className="num-cell">#</th>
            <th>Team</th>
            <th className="soak">Drivers</th>
            <th>Car</th>
            <th className="num-cell">Laps</th>
            <th>Time / Gap</th>
            <th className="num-cell">Fastest</th>
            <th>Status</th>
          </tr>
        </thead>
        <tbody>
          {shown.map((r) => (
            <tr key={r.carNumber + (r.posOverall ?? '')}>
              <td className="pos-cell">{r.posOverall ?? '—'}</td>
              <td className="num-cell">{r.posInClass ?? '—'}</td>
              <ClassCell className={r.className} />
              <td className="car-no">{r.carNumber}</td>
              <td className="name-cell" title={r.teamName ?? undefined}>
                <TeamLink name={r.teamName} />
              </td>
              <td className="name-cell soak" style={{ maxWidth: 320 }} title={r.drivers ?? undefined}>
                <DriverLinks names={r.drivers} />
              </td>
              <td className="name-cell" style={{ maxWidth: 200 }} title={r.vehicle ?? undefined}>
                {r.vehicle}
              </td>
              <td className="num-cell">{r.laps ?? ''}</td>
              <td className="num-cell">{r.posOverall === 1 ? r.elapsedTime : r.gapFirst}</td>
              <td className="num-cell">{r.fastestLapTime ?? ''}</td>
              <td>
                {r.status && r.status.toLowerCase() !== 'running' ? (
                  <span className="status-dnf">{r.status}</span>
                ) : (
                  r.status
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function GridTable({ rows }: { rows: GridRow[] }) {
  const { classFilter } = useSeason()
  const shown = classFilter ? rows.filter((r) => r.className === classFilter) : rows
  if (shown.length === 0) return <p className="muted">No grid imported.</p>
  const hasTimes = shown.some((r) => r.qualifyingTime)
  return (
    <div
      className="grid-scroll"
      style={{ maxHeight: 'none' }}
      tabIndex={0}
      role="region"
      aria-label="Starting grid"
    >
      <table className="grid-table">
        <caption className="sr-only">Starting grid</caption>
        <thead>
          <tr>
            <th className="num-cell">Pos</th>
            <th className="num-cell">PIC</th>
            <th>Class</th>
            <th className="num-cell">#</th>
            <th className="soak">Team</th>
            {hasTimes && <th className="num-cell">Qualifying</th>}
          </tr>
        </thead>
        <tbody>
          {shown.map((r) => (
            <tr key={r.carNumber + (r.posOverall ?? '')}>
              <td className="pos-cell">{r.posOverall ?? '—'}</td>
              <td className="num-cell">{r.posInClass ?? '—'}</td>
              <ClassCell className={r.className} />
              <td className="car-no">{r.carNumber}</td>
              <td className="name-cell soak" title={r.teamName ?? undefined}>
                <TeamLink name={r.teamName} />
              </td>
              {hasTimes && <td className="num-cell">{r.qualifyingTime ?? ''}</td>}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function SessionBlock({ session, raceCount }: { session: SessionResults; raceCount: number }) {
  const isRace = session.sessionType === 'RACE'
  const title = isRace && raceCount === 1 ? 'Race' : session.name
  return (
    <div className="session-block">
      <h3>{title}</h3>
      {isRace && session.grid.length > 0 && (
        <>
          <h4>Starting grid</h4>
          <GridTable rows={session.grid} />
        </>
      )}
      {session.results.length > 0 && (
        <>
          {isRace && session.grid.length > 0 && <h4>Classification</h4>}
          <ResultsTable rows={session.results} />
        </>
      )}
      {session.results.length === 0 && session.grid.length === 0 && (
        <p className="muted">Nothing imported for this session.</p>
      )}
    </div>
  )
}

export default function ResultsPage() {
  const { hub } = useSeason()
  const [searchParams, setSearchParams] = useSearchParams()
  const [results, setResults] = useState<EventResults | null>(null)
  const [error, setError] = useState<string | null>(null)

  // Rounds that have sessions to show, latest first pick.
  const rounds = useMemo(
    () => hub.events.filter((e) => e.roundOrdinal != null && e.sessionCount > 0),
    [hub.events],
  )

  const eventParam = Number(searchParams.get('event'))
  const selected =
    rounds.find((e) => e.id === eventParam) ?? rounds[rounds.length - 1] ?? null

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

  if (rounds.length === 0) {
    return (
      <div className="empty-state">
        No session results yet — import a results or grid file from the Imports tab.
      </div>
    )
  }

  function pick(id: number) {
    const next = new URLSearchParams(searchParams)
    next.set('event', String(id))
    setSearchParams(next, { replace: true })
  }

  const races = results?.sessions.filter((s) => s.sessionType === 'RACE') ?? []

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
            onClick={() => pick(e.id)}
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
          {results.sessions.length === 0 ? (
            <div className="empty-state">No qualifying or race sessions imported for this event.</div>
          ) : (
            results.sessions.map((s) => (
              <SessionBlock key={s.sessionId} session={s} raceCount={races.length} />
            ))
          )}
        </>
      )}
    </div>
  )
}
