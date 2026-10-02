import type { LiveStatus } from '../lib/api'
import type { Tower } from '../lib/liveTiming'
import { useLivePoll } from '../lib/useLivePoll'
import { useTimingNav } from '../lib/timingNav'
import { LiveCalculator } from './season/LiveCalculator'
import { classColorOf, SeasonContextOverride, useSeasonHub } from './season/SeasonLayout'
import './season/calculator.css'

/**
 * The timing page's Points view: the season calculator's Live mode, beside the
 * tower. It projects the championships of the season the session on track is
 * filed under — the same event the calculator scores against — so it only
 * shows on the page that is following that session. Members only: the shared
 * link opens the timing reads, not the standings.
 */
export function PointsView({ tower, followingThis }: { tower: Tower | null; followingThis: boolean }) {
  const nav = useTimingNav()
  const { value: status, error: statusError } = useLivePoll<LiveStatus>(followingThis ? '/api/live/status' : null, 5000)
  const filedEventId = status?.filedEventId ?? null
  const seasonId = status?.filedSeasonId ?? null
  const { hub, classes, error: hubError } = useSeasonHub(seasonId)

  if (!followingThis) {
    const elsewhere =
      tower?.filedEventId != null && nav.event
        ? nav.event(tower.filedEventId)
        : tower?.session?.feedEventDbId != null
          ? nav.weekend(tower.session.feedEventDbId)
          : null
    return (
      <div className="empty-state">
        Live points follow the session on track, which is not this page’s.
        {elsewhere && (
          <>
            {' '}
            <a href={elsewhere}>Open the timing page that is following it</a>.
          </>
        )}
      </div>
    )
  }
  if (!status) {
    return statusError ? (
      <p className="error-panel" role="alert">
        {statusError}
      </p>
    ) : (
      <div className="skeleton-block" aria-label="Loading live points">
        <span className="skeleton" />
        <span className="skeleton" />
      </div>
    )
  }

  let body
  if (!status.desiredConnected) {
    body = <p className="empty-state">Live timing is off. Once it is connected, the standings are projected here as the field runs.</p>
  } else if (status.state !== 'LIVE' && status.state !== 'BACKING_OFF') {
    body = <p role="status">Connecting to live timing…</p>
  } else if (filedEventId == null) {
    body = (
      <p className="empty-state">
        {status.session?.championship ?? 'The session on track'} is not filed under a Pit Pass event, so no standings
        are projected.
      </p>
    )
  } else if (hubError) {
    body = (
      <p className="error-panel" role="alert">
        {hubError}
      </p>
    )
  } else if (!hub) {
    body = (
      <div className="skeleton-block" aria-label="Loading standings">
        <span className="skeleton" />
        <span className="skeleton" />
      </div>
    )
  } else {
    // Rendered only with a season to read: the calculator reads it before anything else.
    body = <LiveCalculator status={status} />
  }

  return (
    <SeasonContextOverride.Provider
      value={hub ? { hub, classes, classFilter: null, classColor: classColorOf(classes) } : null}
    >
      <section className="calculator timing-points">
        {hub && filedEventId != null && (
          <p className="calculator-note">
            As it stands: the {hub.year} {hub.seriesName} standings, projected from where the field is running.{' '}
            <a href={`#/seasons/${hub.id}/calculator`}>Season calculator</a>
          </p>
        )}
        {body}
      </section>
    </SeasonContextOverride.Provider>
  )
}
