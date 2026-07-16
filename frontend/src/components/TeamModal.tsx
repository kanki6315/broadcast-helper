import { useCallback, useEffect, useRef, useState, type CSSProperties } from 'react'
import './driver-modal.css'
import {
  getJson,
  type TeamChampMatrix,
  type TeamProfile,
  type TeamRosterSeason,
} from '../lib/api'
import { formatPoints } from '../pages/season/ChampionshipGrid'
import { useInfoModal } from './infoModal'
import NotesSection from './NotesSection'
import RaceLine from './RaceLine'

/* ------------------------------------------------------------------------- */
/* Roster: the team's current cars and crews                                   */
/* ------------------------------------------------------------------------- */

function RosterBlock({
  season,
  onDriver,
}: {
  season: TeamRosterSeason
  onDriver: (driverId: number) => void
}) {
  return (
    <section className="dm-roster" aria-label={`${season.seriesName} ${season.year} cars`}>
      <p className="dm-roster-head">
        {season.seriesName} {season.year}
        <span className="dm-roster-asof"> · as of {season.eventName}</span>
      </p>
      {season.cars.map((car) => (
        <div key={car.entryId} className="dm-roster-car">
          {car.imageVersion != null && (
            <img
              className="dm-roster-livery"
              src={`/api/entries/${car.entryId}/image?variant=sheet&v=${car.imageVersion}`}
              alt=""
              loading="lazy"
            />
          )}
          <span className="dm-car dm-roster-no">#{car.carNumber}</span>
          <span
            className="class-tag"
            style={{ '--class-color': car.classColor } as CSSProperties}
          >
            {car.className}
          </span>
          <span className="dm-roster-crew">
            {car.drivers.length === 0 && <span className="muted">No drivers announced</span>}
            {car.drivers.map((d, i) => (
              <span key={`${d.name}-${i}`}>
                {i > 0 && ', '}
                {d.isTbd || d.driverId == null ? (
                  d.isTbd ? 'TBD' : d.name
                ) : (
                  <button type="button" className="drv-link" onClick={() => onDriver(d.driverId!)}>
                    {d.name}
                  </button>
                )}
                {d.rating && <span className="dm-roster-rating"> ({d.rating})</span>}
              </span>
            ))}
          </span>
          {car.vehicle && <span className="dm-roster-vehicle">{car.vehicle}</span>}
        </div>
      ))}
    </section>
  )
}

/* ------------------------------------------------------------------------- */
/* Championship matrix — one Result/Pts row pair per car                       */
/* ------------------------------------------------------------------------- */

function TeamChampSection({ champ }: { champ: TeamChampMatrix }) {
  const label = `${champ.title} — start and finish by round`
  return (
    <section className="dm-champ">
      <div className="dm-champ-head">
        <h3 title={champ.title}>{champ.title}</h3>
      </div>
      {champ.rounds.length === 0 ? (
        <p className="dm-quiet">No calendar published for this championship yet.</p>
      ) : (
        <div className="dm-matrix-scroll" tabIndex={0} role="region" aria-label={label}>
          <table className="dm-matrix">
            <caption className="sr-only">{label}</caption>
            <thead>
              <tr>
                <th className="dm-mx-rowhead" scope="col">
                  <span className="sr-only">Car</span>
                </th>
                {champ.rounds.map((r) => (
                  <th key={r.round} scope="col">
                    <span className="venue">{r.venue}</span>
                    <span className="rd">Rd {r.round}</span>
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {champ.entries.map((entry) => {
                const hasPoints = Object.keys(entry.pointsByRound).length > 0
                return (
                  <RosterEntryRows
                    key={entry.carNumber}
                    entry={entry}
                    rounds={champ.rounds}
                    hasPoints={hasPoints}
                  />
                )
              })}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}

function RosterEntryRows({
  entry,
  rounds,
  hasPoints,
}: {
  entry: TeamChampMatrix['entries'][number]
  rounds: TeamChampMatrix['rounds']
  hasPoints: boolean
}) {
  return (
    <>
      <tr>
        <th className="dm-mx-rowhead dm-mx-carhead" scope="row">
          <span className="dm-car">#{entry.carNumber}</span>
          <span className="dm-mx-standing">
            P{entry.position} · {formatPoints(entry.totalPoints)}
          </span>
        </th>
        {rounds.map((r) => {
          const races = entry.cells[r.round]
          return (
            <td key={r.round} className="dm-mx-cell">
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
      </tr>
      {hasPoints && (
        <tr className="dm-mx-ptsrow">
          <th className="dm-mx-rowhead" scope="row">
            Pts
          </th>
          {rounds.map((r) => {
            const pts = entry.pointsByRound[r.round]
            return (
              <td key={r.round} className="dm-mx-pts">
                {pts != null ? formatPoints(pts) : <span className="muted">—</span>}
              </td>
            )
          })}
        </tr>
      )}
    </>
  )
}

/* ------------------------------------------------------------------------- */
/* The modal                                                                   */
/* ------------------------------------------------------------------------- */

export default function TeamModal({
  teamName,
  onClose,
}: {
  teamName: string
  onClose: () => void
}) {
  const dialogRef = useRef<HTMLDialogElement>(null)
  const { openDriver } = useInfoModal()
  const [profile, setProfile] = useState<TeamProfile | null>(null)
  const [error, setError] = useState<string | null>(null)
  const flushNotes = useRef<() => void>(() => {})

  useEffect(() => {
    const d = dialogRef.current
    if (d && !d.open) d.showModal()
  }, [])

  useEffect(() => {
    let cancelled = false
    setError(null)
    getJson<TeamProfile>(`/api/teams/profile?name=${encodeURIComponent(teamName)}`)
      .then((p) => !cancelled && setProfile(p))
      .catch((e) => !cancelled && setError(e instanceof Error ? e.message : 'Failed to load team'))
    return () => {
      cancelled = true
    }
  }, [teamName])

  const saveNotes = useCallback(
    async (text: string) => {
      const r = await fetch('/api/teams/notes', {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name: teamName, notes: text }),
        keepalive: true,
      })
      if (!r.ok) throw new Error(String(r.status))
    },
    [teamName],
  )

  const close = useCallback(() => {
    flushNotes.current()
    onClose()
  }, [onClose])

  // Drill-through to a driver replaces this modal; save the draft first.
  function goToDriver(driverId: number) {
    flushNotes.current()
    openDriver(driverId)
  }

  return (
    <dialog
      className="dm no-print"
      ref={dialogRef}
      aria-label={profile ? `Team: ${profile.name}` : 'Team'}
      onCancel={(e) => {
        e.preventDefault()
        close()
      }}
      onClick={(e) => {
        if (e.target === dialogRef.current) close()
      }}
    >
      <div className="dm-body">
        {error && (
          <div className="dm-pad">
            <p className="error-panel">{error}</p>
            <button type="button" className="btn" onClick={close}>
              Close
            </button>
          </div>
        )}

        {!error && !profile && (
          <div className="dm-pad" aria-label="Loading team">
            <span className="skeleton dm-skel-name" />
            <span className="skeleton" />
            <span className="skeleton" />
            <span className="skeleton dm-skel-wide" />
          </div>
        )}

        {profile && (
          <>
            <header className="dm-head">
              <div className="dm-id">
                <h2 className="dm-name">{profile.name}</h2>
                {profile.roster.length > 0 && (
                  <p className="dm-seat">
                    {profile.roster
                      .map((s) => `${s.seriesName} ${s.year}`)
                      .join(' · ')}
                  </p>
                )}
              </div>
              <button type="button" className="dm-close" aria-label="Close" onClick={close}>
                ✕
              </button>
            </header>

            <div className="dm-scroll dm-scroll-team">
              {profile.roster.length === 0 ? (
                <p className="dm-quiet dm-pad-x">No entries for this team yet.</p>
              ) : (
                profile.roster.map((season) => (
                  <RosterBlock key={season.seasonId} season={season} onDriver={goToDriver} />
                ))
              )}

              {profile.championships.length === 0 ? (
                <p className="dm-quiet dm-pad-x">
                  No teams-championship standings yet — they appear once a standings file is
                  imported.
                </p>
              ) : (
                profile.championships.map((c) => (
                  <TeamChampSection key={c.championshipId} champ={c} />
                ))
              )}

              <NotesSection initial={profile.notes ?? ''} save={saveNotes} flushRef={flushNotes} />
            </div>
          </>
        )}
      </div>
    </dialog>
  )
}
