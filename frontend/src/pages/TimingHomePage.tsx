import { useEffect, useState } from 'react'
import './season.css'
import './timing.css'
import { useIsAdmin } from '../lib/auth'
import { useLivePoll } from '../lib/useLivePoll'
import { useTimingNav, type TimingNav } from '../lib/timingNav'
import type { LiveStatus, SessionSummary, Weekend, WeekendChampionship } from '../lib/liveTiming'
import { LiveConnectControl } from './TimingPage'

/**
 * `/timing`: what the feed is carrying now, and every series weekend it has
 * recorded — filed under a Pit Pass event or not — so a series Pit Pass does
 * not follow still has its timing (docs/LIVE_TIMING_ALL_SERIES_PLAN.md,
 * slice 4). Admins connect here without choosing an event (sessions are filed
 * by championship) and can say where a series weekend belongs.
 */
export default function TimingHomePage() {
  const nav = useTimingNav()
  const isAdmin = useIsAdmin() && !nav.shared
  const [changed, setChanged] = useState(0)
  const { value: status } = useLivePoll<LiveStatus>('/api/live/status', 5000, changed)
  const { value: weekends, error } = useLivePoll<Weekend[]>('/api/live/weekends', 30_000, changed)

  useEffect(() => {
    document.title = 'Timing'
  }, [])

  return (
    <div className="timing">
      <div className="timing-topbar">
        {!nav.shared && (
          <a className="timing-back" href="#/">
            ← Pit Pass
          </a>
        )}
        {!nav.shared && <LiveConnectControl eventId={null} onChanged={() => setChanged((n) => n + 1)} />}
      </div>
      <header className="timing-head">
        <h1>Timing</h1>
        {status && <OnTrack status={status} nav={nav} />}
      </header>

      {weekends == null ? (
        error ? (
          <div className="error-panel">Could not load recorded sessions: {error}</div>
        ) : (
          <div className="skeleton-block" aria-label="Loading recorded sessions">
            <span className="skeleton" />
            <span className="skeleton" />
          </div>
        )
      ) : weekends.length === 0 ? (
        <div className="empty-state">No sessions recorded in the last 60 days.</div>
      ) : (
        weekends.map((w) => (
          <section key={w.championships.map((c) => c.feedEventDbId).join('-')} className="timing-weekend">
            <h2>
              {w.track ?? 'Unnamed track'}
              <span className="muted"> · {dateRange(w.fromMs, w.toMs)}</span>
            </h2>
            <table className="grid-table timing-weekend-table">
              <thead>
                <tr>
                  <th scope="col">Series (feed)</th>
                  <th scope="col">Filed under</th>
                  <th scope="col">Sessions</th>
                </tr>
              </thead>
              <tbody>
                {w.championships.map((c) => (
                  <ChampionshipRow
                    key={c.feedEventDbId}
                    c={c}
                    nav={nav}
                    isAdmin={isAdmin}
                    onChanged={() => setChanged((n) => n + 1)}
                  />
                ))}
              </tbody>
            </table>
          </section>
        ))
      )}
    </div>
  )
}

/** The session the feed is carrying, and where it is filed. */
function OnTrack({ status, nav }: { status: LiveStatus; nav: TimingNav }) {
  const session = status.session
  if (!status.desiredConnected || !session) {
    return <p className="timing-meta">{status.desiredConnected ? 'Connected. Waiting for a session.' : 'Live timing is off.'}</p>
  }
  const href =
    status.filedEventId != null && nav.event
      ? nav.event(status.filedEventId)
      : session.feedEventDbId != null
        ? nav.weekend(session.feedEventDbId)
        : null
  const label = [session.championship, session.name].filter(Boolean).join(' · ') || 'A session'
  return (
    <p className="timing-meta">
      On track: {href ? <a href={href}>{label}</a> : label}
      <span className="muted">
        {' '}
        · {status.filedEventId != null ? `filed under ${status.filedEventName ?? 'an event'}` : 'not filed under a Pit Pass event'}
      </span>
    </p>
  )
}

function ChampionshipRow({
  c,
  nav,
  isAdmin,
  onChanged,
}: {
  c: WeekendChampionship
  nav: TimingNav
  isAdmin: boolean
  onChanged: () => void
}) {
  const base = c.eventId != null && nav.event ? nav.event(c.eventId) : nav.weekend(c.feedEventDbId)
  // Oldest first reads like the weekend's schedule; the API lists newest first.
  const sessions = [...c.sessions].reverse()
  return (
    <tr>
      <th scope="row">
        <a href={nav.weekend(c.feedEventDbId)}>{c.champName ?? `Feed event ${c.feedEventDbId}`}</a>
        {c.feedEventName && <div className="muted timing-weekend-sub">{c.feedEventName}</div>}
      </th>
      <td>
        {isAdmin ? <BindControl c={c} onChanged={onChanged} /> : <FiledUnder c={c} nav={nav} />}
      </td>
      <td>
        <span className="timing-weekend-sessions">
          {sessions.map((s) => (
            <SessionLink key={s.sessionDbId} base={base} s={s} />
          ))}
        </span>
      </td>
    </tr>
  )
}

function SessionLink({ base, s }: { base: string; s: SessionSummary }) {
  const href = s.current ? base : `${base}?view=gaps&session=${s.sessionDbId}`
  return (
    <a className="timing-weekend-session" href={href} title={`${s.laps} laps · ${s.cars} cars`}>
      {s.name ?? `Session ${s.sessionDbId}`}
      {s.current && <span className="timing-session-live"> · live</span>}
    </a>
  )
}

function FiledUnder({ c, nav }: { c: WeekendChampionship; nav: TimingNav }) {
  if (c.eventId != null) {
    return nav.event ? <a href={nav.event(c.eventId)}>{c.eventName ?? 'An event'}</a> : <span>{c.eventName ?? 'An event'}</span>
  }
  return <span className="muted">{c.boundBy === 'ADMIN_NONE' ? 'Not in Pit Pass' : 'Not filed yet'}</span>
}

/**
 * Where an admin files a series weekend: automatic (by championship, the
 * default), an event, or "not in Pit Pass". The latter two hold until changed.
 */
function BindControl({ c, onChanged }: { c: WeekendChampionship; onChanged: () => void }) {
  const [busy, setBusy] = useState(false)
  const [problem, setProblem] = useState<string | null>(null)
  const value = c.boundBy === 'ADMIN' && c.eventId != null ? String(c.eventId) : c.boundBy === 'ADMIN_NONE' ? 'none' : 'auto'
  const autoLabel =
    c.boundBy === 'AUTO' && c.eventId != null ? `Automatic: ${c.eventName ?? 'an event'}` : 'Automatic: not filed yet'
  const options = c.candidates.some((e) => e.id === c.eventId) || c.eventId == null
    ? c.candidates
    : [{ id: c.eventId, name: c.eventName ?? `Event ${c.eventId}`, seriesName: '', date: null }, ...c.candidates]

  const choose = async (next: string) => {
    setBusy(true)
    setProblem(null)
    const body = next === 'auto' ? { auto: true } : next === 'none' ? { none: true } : { eventId: Number(next) }
    try {
      const res = await fetch(`/api/live/feed-events/${c.feedEventDbId}/event`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      })
      if (!res.ok) {
        const msg = await res.json().then((b) => b?.message).catch(() => null)
        setProblem(msg ?? `The server answered ${res.status}.`)
      }
      onChanged()
    } catch (e) {
      setProblem(`Could not reach the server: ${(e as Error).message}`)
    } finally {
      setBusy(false)
    }
  }

  return (
    <span className="timing-bind">
      <select
        aria-label={`Filed under, for ${c.champName ?? 'this series'}`}
        value={value}
        disabled={busy}
        onChange={(e) => void choose(e.target.value)}
      >
        <option value="auto">{autoLabel}</option>
        <option value="none">Not in Pit Pass</option>
        {options.map((e) => (
          <option key={e.id} value={String(e.id)}>
            {[e.name, e.seriesName, e.date].filter(Boolean).join(' · ')}
          </option>
        ))}
      </select>
      {c.eventId != null && (
        <a className="timing-bind-open" href={`#/timing/${c.eventId}`}>
          Open
        </a>
      )}
      {problem && <span className="timing-control-problem">{problem}</span>}
    </span>
  )
}

function dateRange(fromMs: number | null, toMs: number | null): string {
  if (fromMs == null) return 'no date'
  const fmt = (ms: number) => new Date(ms).toLocaleDateString(undefined, { day: 'numeric', month: 'short' })
  const year = new Date(fromMs).getFullYear()
  return toMs == null || fmt(toMs) === fmt(fromMs) ? `${fmt(fromMs)} ${year}` : `${fmt(fromMs)} – ${fmt(toMs)} ${year}`
}
