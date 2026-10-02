import { Fragment, useEffect, useMemo, useRef, useState, type CSSProperties, type KeyboardEvent } from 'react'
import { useSearchParams } from 'react-router-dom'
import './season.css'
import './timing.css'
import { useIsAdmin } from '../lib/auth'
import { useLivePoll } from '../lib/useLivePoll'
import { useTimingNav, type TimingNav } from '../lib/timingNav'
import {
  RATINGS,
  classBest,
  duration,
  feedNow,
  flagLabel,
  flagTone,
  gap,
  lapTime,
  fieldCounts,
  lastLapMark,
  placesGained,
  sectorMark,
  parseRuleTime,
  ratingName,
  ruleTime,
  sessionClock,
  trackTime,
  type DriveTimeResponse,
  type LiveStatus,
  type DriveTimeResult,
  type DriveTimeRule,
  type SessionSummary,
  type Tower,
  type TowerCar,
  type TowerClass,
  type SectorTime,
  type WeekendChampionship,
} from '../lib/liveTiming'
import LiveCarModal from '../components/LiveCarModal'
import { GapsView, PitsView, SectorsView } from './TimingAnalysis'
import { RaceControlStrip, RaceControlView } from './RaceControl'

/**
 * The live timing page — for a Pit Pass event (`/timing/:eventId`) or for one
 * series weekend as the feed has it, filed or not (`/timing/weekend/:id`):
 * the tower; gaps, best sectors and pit stops over a recorded session
 * (TimingAnalysis); drive time per driver against the filed event's rules; and
 * race control's messages (RaceControl). Chrome-less like the sheet — it is kept
 * open on a second screen in the booth.
 *
 * Reads, apart from two admin-only controls: the shared connect/disconnect
 * switch (as on the iPad) and the drive-time rules. The tower polls every 2 s
 * (a 304 when nothing moved), drive time every 10 s, the analysis views every
 * 15 s while their session is live.
 */
type View = 'tower' | 'gaps' | 'sectors' | 'pits' | 'drive' | 'control'

const VIEWS: { id: View; label: string }[] = [
  { id: 'tower', label: 'Tower' },
  { id: 'gaps', label: 'Gaps' },
  { id: 'sectors', label: 'Sectors' },
  { id: 'pits', label: 'Pits' },
  { id: 'drive', label: 'Drive time' },
  { id: 'control', label: 'Race control' },
]

/** Either a Pit Pass event's sessions, or one series weekend's (Al Kamel's feed event). */
export type TimingScope = { kind: 'event'; eventId: number } | { kind: 'weekend'; feedEventDbId: number }

const scopePath = (scope: TimingScope, nav: TimingNav) =>
  scope.kind === 'weekend' ? nav.weekend(scope.feedEventDbId) : nav.event ? nav.event(scope.eventId) : nav.home

export default function TimingPage({ scope }: { scope: TimingScope }) {
  const nav = useTimingNav()
  const [params, setParams] = useSearchParams()
  const view: View = VIEWS.find((v) => v.id === params.get('view'))?.id ?? 'tower'
  // Bumped after an admin connects or disconnects, so the tower follows at once.
  const [feedChanged, setFeedChanged] = useState(0)
  const { value: tower, error: towerError } = useLivePoll<Tower>('/api/live/timing', 2000, feedChanged)
  const { value: sessions } = useLivePoll<SessionSummary[]>(
    scope.kind === 'event' ? `/api/live/sessions?eventId=${scope.eventId}` : `/api/live/sessions?feedEvent=${scope.feedEventDbId}`,
    30_000,
  )
  // The page's own name, for when the feed is following something else.
  // undefined = still asking; null = could not find out.
  const [ownName, setOwnName] = useState<string | null | undefined>(undefined)
  const [weekend, setWeekend] = useState<WeekendChampionship | null>(null)
  const scopeKey = scope.kind === 'event' ? `e${scope.eventId}` : `w${scope.feedEventDbId}`
  useEffect(() => {
    setOwnName(undefined)
    if (scope.kind === 'event') {
      void fetch(`/api/events/${scope.eventId}`)
        .then((r) => (r.ok ? r.json() : null))
        .then((d) => setOwnName(d?.event?.name ?? null))
        .catch(() => setOwnName(null))
    } else {
      void fetch(`/api/live/feed-events/${scope.feedEventDbId}`)
        .then((r) => (r.ok ? (r.json() as Promise<WeekendChampionship>) : null))
        .then((w) => {
          setWeekend(w)
          setOwnName(w ? [w.champName, w.feedEventName].filter(Boolean).join(' · ') || 'Series weekend' : null)
        })
        .catch(() => setOwnName(null))
    }
    // scopeKey stands for scope: an object prop would refetch on every render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [scopeKey])

  // The session on track belongs to an event page when it is filed there or,
  // filed nowhere, when the connection is bound there (its own teams, flagged
  // below); to a weekend page when it is one of that weekend's sessions.
  const followingThis =
    tower != null &&
    (scope.kind === 'event'
      ? (tower.filedEventId ?? tower.eventId) === scope.eventId
      : tower.session?.feedEventDbId === scope.feedEventDbId)
  const eventId = scope.kind === 'event' ? scope.eventId : (weekend?.eventId ?? null)
  const title =
    scope.kind === 'event' && followingThis && tower.filedEventName
      ? tower.filedEventName
      : ownName === undefined
        ? undefined
        : (ownName ?? 'Live timing')
  useEffect(() => {
    document.title = title ? `Timing · ${title}` : 'Timing'
  }, [title])

  const setView = (v: View) =>
    setParams(
      (p) => {
        const next = new URLSearchParams(p)
        if (v === 'tower') next.delete('view')
        else next.set('view', v)
        return next
      },
      { replace: true },
    )

  return (
    <div className="timing">
      <div className="timing-topbar">
        {scope.kind === 'event' ? (
          <a className="timing-back" href={`#/events/${scope.eventId}`}>
            ← Event
          </a>
        ) : (
          <a className="timing-back" href={nav.home}>
            ← Timing
          </a>
        )}
        <ViewTabs view={view} onChange={setView} />
        {tower && <FeedStatus tower={tower} followingThis={followingThis} />}
        {towerError && tower && <span className="timing-stale">Not updating: {towerError}</span>}
        <LiveConnectControl eventId={eventId} onChanged={() => setFeedChanged((n) => n + 1)} />
      </div>

      <header className="timing-head">
        <div className="timing-head-text">
          <h1>{title ?? <span className="skeleton timing-title-skeleton" aria-label="Loading" />}</h1>
          {followingThis && tower.session && <SessionLine session={tower.session} />}
        </div>
        {followingThis && <SessionClockView tower={tower} />}
      </header>

      {view === 'tower' ? (
        <TowerView tower={tower} error={towerError} scope={scope} followingThis={followingThis} />
      ) : (
        <SessionViews view={view} scope={scope} sessions={sessions} tower={tower} />
      )}
    </div>
  )
}

function ViewTabs({ view, onChange }: { view: View; onChange: (v: View) => void }) {
  const views = VIEWS
  const onKey = (e: KeyboardEvent) => {
    if (e.key !== 'ArrowRight' && e.key !== 'ArrowLeft') return
    e.preventDefault()
    const i = views.findIndex((v) => v.id === view)
    const next = views[(i + (e.key === 'ArrowRight' ? 1 : views.length - 1)) % views.length]
    onChange(next.id)
    document.getElementById(`timing-tab-${next.id}`)?.focus()
  }
  return (
    <div className="seg" role="tablist" aria-label="Timing view" onKeyDown={onKey}>
      {views.map((v) => (
        <button
          key={v.id}
          id={`timing-tab-${v.id}`}
          role="tab"
          aria-selected={view === v.id}
          tabIndex={view === v.id ? 0 : -1}
          className={`seg-btn${view === v.id ? ' active' : ''}`}
          onClick={() => onChange(v.id)}
        >
          {v.label}
        </button>
      ))}
    </div>
  )
}

const STATE_TEXT: Record<Tower['state'], string> = {
  LIVE: 'Live',
  CONNECTING: 'Connecting',
  BACKING_OFF: 'Reconnecting · last known',
  STANDBY: 'Standby',
  OFF: 'Off',
  NOT_CONFIGURED: 'Not configured',
}

function FeedStatus({ tower, followingThis }: { tower: Tower; followingThis: boolean }) {
  const tone = tower.state === 'LIVE' ? 'live' : tower.state === 'BACKING_OFF' ? 'warn' : 'idle'
  return (
    <span className={`timing-status timing-status--${tone}`} role="status">
      <i aria-hidden="true" />
      {STATE_TEXT[tower.state]}
      {!followingThis && (tower.filedEventId ?? tower.eventId) != null && tower.state !== 'OFF' && tower.state !== 'NOT_CONFIGURED' && (
        <span className="muted"> · following another event</span>
      )}
    </span>
  )
}

/**
 * The shared switch, for admins only: connect the one Al Kamel login and bind
 * it to this event, rebind it here, or disconnect it — for everyone, so that
 * asks first. Same wording and states as the iPad's LiveTimingBar; viewers
 * see only the status beside it.
 */
export function LiveConnectControl({ eventId, onChanged }: { eventId: number | null; onChanged: () => void }) {
  const isAdmin = useIsAdmin()
  const [refresh, setRefresh] = useState(0)
  const { value: status } = useLivePoll<LiveStatus>(isAdmin ? '/api/live/status' : null, 5000, refresh)
  const [busy, setBusy] = useState(false)
  const [confirming, setConfirming] = useState(false)
  const [problem, setProblem] = useState<string | null>(null)

  if (!isAdmin || !status?.configured) return null

  const send = async (path: string, body?: unknown) => {
    setBusy(true)
    setProblem(null)
    try {
      const res = await fetch(path, {
        method: 'POST',
        headers: body ? { 'Content-Type': 'application/json' } : undefined,
        body: body ? JSON.stringify(body) : undefined,
      })
      if (!res.ok) {
        const msg = await res.json().then((b) => b?.message).catch(() => null)
        setProblem(msg ?? `The server answered ${res.status}.`)
        return
      }
      setConfirming(false)
      setRefresh((n) => n + 1)
      onChanged()
    } catch (e) {
      setProblem(`Could not reach the server: ${(e as Error).message}`)
    } finally {
      setBusy(false)
    }
  }
  // An event is only a hint for filing; with none, every series is filed by championship.
  const connect = () => send('/api/live/connect', eventId == null ? {} : { eventId })
  const disconnect = () => send('/api/live/disconnect')

  return (
    <div className="timing-control" aria-label="Live timing connection">
      {confirming ? (
        <>
          <span className="timing-control-ask" role="alert">
            Disconnect for everyone? The server holds one connection for every Pit Pass user; live timing and points
            stop for all of them.
          </span>
          <button
            className="btn"
            autoFocus
            disabled={busy}
            onClick={() => setConfirming(false)}
            onKeyDown={(e) => e.key === 'Escape' && setConfirming(false)}
          >
            Keep connected
          </button>
          <button className="btn btn-danger" disabled={busy} onClick={disconnect}>
            {busy ? 'Disconnecting…' : 'Disconnect for everyone'}
          </button>
        </>
      ) : !status.desiredConnected ? (
        <button className="btn btn-primary" disabled={busy} onClick={connect}>
          {busy ? 'Connecting…' : eventId == null ? 'Connect' : 'Connect for this event'}
        </button>
      ) : (
        <>
          {eventId != null && status.eventId !== eventId && (
            <button className="btn" disabled={busy} onClick={connect} title={`Now scoring ${status.eventName ?? 'another event'}`}>
              {busy ? 'Switching…' : 'Score this event'}
            </button>
          )}
          <button className="btn" disabled={busy} onClick={() => setConfirming(true)}>
            Disconnect
          </button>
        </>
      )}
      {problem && <span className="timing-control-problem">{problem}</span>}
    </div>
  )
}

function SessionLine({ session }: { session: NonNullable<Tower['session']> }) {
  const flag = flagLabel(session.flag)
  return (
    <p className="timing-meta">
      {[session.championship, session.name].filter(Boolean).join(' · ')}
      {flag && <span className={`timing-flag timing-flag--${flagTone(session.flag)}`}>{flag}</span>}
      {session.finished && <span className="timing-flag timing-flag--neutral">Finished</span>}
    </p>
  )
}

/**
 * The session clock, counted down in the browser from the feed's status: time
 * to go (frozen and labelled while a red flag stops it), or the leader's lap
 * of a lap-limited race, with the time of day at the track beneath.
 */
function SessionClockView({ tower }: { tower: Tower }) {
  const wall = useTick(tower.state === 'LIVE')
  const reading = sessionClock(tower, wall)
  // The time at the track only while the feed is current: a replay's "now" is not today's.
  const local = feedNow(tower, wall) === wall ? trackTime(wall, tower.session?.clock?.utcOffsetHours) : null
  const counts = tower.state === 'LIVE' && tower.classes.length > 0 ? fieldCounts(tower) : null
  if (!reading && !local && !counts) return null
  return (
    <div className={`timing-clock${reading?.stopped ? ' timing-clock--stopped' : ''}`}>
      {reading && (
        <p className="timing-clock-main">
          <span className="timing-clock-time">{reading.time}</span>
          {reading.note && <span className="timing-clock-note">{reading.note}</span>}
        </p>
      )}
      {(reading?.laps || local) && (
        <p className="timing-clock-sub">{[reading?.laps, local && `${local} at the track`].filter(Boolean).join(' · ')}</p>
      )}
      {counts && (
        <p className="timing-clock-sub timing-counts">
          {[
            `${counts.onTrack} on track`,
            `${counts.inPit} in pit`,
            counts.stopped != null && `${counts.stopped} stopped`,
            `${counts.retired} retired`,
          ]
            .filter(Boolean)
            .join(' · ')}
        </p>
      )}
    </div>
  )
}

/** Re-renders once a second while something on screen is counting up. */
function useTick(active: boolean): number {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    if (!active) return
    const id = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(id)
  }, [active])
  return now
}

// ---- tower ------------------------------------------------------------------------

function TowerView({
  tower,
  error,
  scope,
  followingThis,
}: {
  tower: Tower | null
  error: string | null
  scope: TimingScope
  followingThis: boolean
}) {
  const nav = useTimingNav()
  const base = scopePath(scope, nav)
  const here = scope.kind === 'event' ? 'this event' : 'this series weekend'
  const [open, setOpen] = useState<{ car: TowerCar; cls: TowerClass } | null>(null)
  const live = tower?.state === 'LIVE'
  const wall = useTick(live && followingThis)
  const moves = useMoves(tower)
  const [shown, setShown] = useColumnChoice()
  const [order, setOrder] = useOrderChoice()

  if (!tower) {
    return error ? (
      <div className="error-panel">Could not reach live timing: {error}</div>
    ) : (
      <div className="skeleton-block" aria-label="Loading the tower">
        <span className="skeleton" />
        <span className="skeleton" />
        <span className="skeleton" />
        <span className="skeleton" />
      </div>
    )
  }
  if (tower.state === 'NOT_CONFIGURED') {
    return <div className="empty-state">Live timing is not set up on this server.</div>
  }
  if (tower.state === 'OFF' || tower.state === 'STANDBY') {
    return (
      <div className="empty-state">
        Live timing is off. Once an admin connects it, the tower fills in here — no need to reload.
        <br />
        Recorded sessions stay under <a href={`${base}?view=gaps`}>Gaps</a>,{' '}
        <a href={`${base}?view=sectors`}>Sectors</a>, <a href={`${base}?view=pits`}>Pits</a> and{' '}
        <a href={`${base}?view=drive`}>Drive time</a>.
      </div>
    )
  }
  if (!followingThis) {
    const onTrack = tower.session
    const there =
      tower.filedEventId != null && nav.event
        ? { href: nav.event(tower.filedEventId), label: tower.filedEventName ?? 'another event' }
        : onTrack?.feedEventDbId != null
          ? { href: nav.weekend(onTrack.feedEventDbId), label: onTrack.championship ?? 'another series' }
          : tower.eventId != null && nav.event
            ? { href: nav.event(tower.eventId), label: tower.eventName ?? 'another event' }
            : null
    return (
      <div className="empty-state">
        Live timing is following {there ? <a href={there.href}>{there.label}</a> : 'no event'}, not {here}.
        <br />
        Recorded sessions here are under <a href={`${base}?view=gaps`}>Gaps</a>,{' '}
        <a href={`${base}?view=sectors`}>Sectors</a>, <a href={`${base}?view=pits`}>Pits</a> and{' '}
        <a href={`${base}?view=drive`}>Drive time</a>.
      </div>
    )
  }
  if (tower.classes.length === 0) {
    return <div className="empty-state">Connected. Waiting for the feed's first running order.</div>
  }

  const now = feedNow(tower, wall)
  // Filed nowhere: every car is "not entered", so say it once rather than per row.
  const unfiled = tower.filedEventId == null
  const hasEnergy = tower.classes.some((c) => c.cars.some((car) => car.energyPct != null))
  const hasLaps = tower.classes.some((c) => c.cars.some((car) => car.lastLapMs != null || car.stintStartMs != null))
  const hasPits = tower.classes.some((c) => c.cars.some((car) => car.pitStops != null))
  const hasStarts = tower.classes.some((c) => c.cars.some((car) => car.startPosition != null))
  const hasTopSpeed = tower.classes.some((c) => c.cars.some((car) => car.topSpeed != null))
  const feedSectors = Math.max(0, ...tower.classes.flatMap((c) => c.cars.map((car) => car.sectors?.length ?? 0)))
  const available: Record<OptionalColumn, boolean> = {
    sectors: feedSectors > 0,
    topSpeed: hasTopSpeed,
    pits: hasPits,
    energy: hasEnergy,
  }
  const show = (c: OptionalColumn) => available[c] && shown[c]
  const sectorCount = show('sectors') ? feedSectors : 0
  const columns = 8 + (hasLaps ? 3 : 0) + sectorCount + (show('topSpeed') ? 1 : 0) + (show('pits') ? 1 : 0) + (show('energy') ? 1 : 0)
  // Overall needs the feed's overall standings: class gaps cannot be compared across classes.
  const hasOverall = tower.classes.some((c) => c.cars.some((car) => car.overallPosition != null))
  const overall = order === 'overall' && hasOverall

  // What each car is marked against is always its own class, in either order.
  const marks = new Map(
    tower.classes.map((cls) => [
      cls.className,
      { best: classBest(cls.cars), fastest: Math.max(0, ...cls.cars.map((c) => c.topSpeed ?? 0)) || null },
    ]),
  )
  const row = (car: TowerCar, cls: TowerClass) => {
    const m = marks.get(cls.className)!
    return (
      <TowerRow
        key={`${car.carNumber}-${moves.get(car.carNumber) ?? 0}`}
        moved={moves.has(car.carNumber)}
        hasStarts={hasStarts && !overall}
        car={car}
        place={
          overall
            ? {
                position: car.overallPosition,
                gapMs: car.overallGapMs,
                gapLaps: car.overallGapLaps,
                intervalMs: car.overallIntervalMs,
                intervalLaps: car.overallIntervalLaps,
              }
            : {
                position: car.position,
                gapMs: car.gapToLeaderMs,
                gapLaps: car.gapToLeaderLaps,
                intervalMs: car.intervalMs,
                intervalLaps: car.intervalLaps,
              }
        }
        classTag={overall ? cls : null}
        classBestMs={m.best}
        hasLaps={hasLaps}
        sectorCount={sectorCount}
        classSectors={cls.bestSectors}
        topSpeed={show('topSpeed') ? { fastest: m.fastest, unit: tower.speedUnit } : null}
        hasPits={show('pits')}
        hasEnergy={show('energy')}
        now={now}
        unfiled={unfiled}
        onOpen={() => setOpen({ car, cls })}
      />
    )
  }
  // Overall: every car in the feed's overall order; a car it has not placed yet goes last, in class order.
  const overallRows = overall
    ? tower.classes
        .flatMap((cls, ci) => cls.cars.map((car) => ({ car, cls, ci })))
        .sort(
          (a, b) =>
            (a.car.overallPosition ?? Infinity) - (b.car.overallPosition ?? Infinity) ||
            a.ci - b.ci ||
            a.car.position - b.car.position,
        )
    : []

  return (
    <>
      {unfiled && (
        <p className="timing-unfiled" role="status">
          {scope.kind === 'event'
            ? `${tower.session?.championship ?? 'This session'} is not filed under this event: its cars do not match the entry list. Teams and drivers are the feed's own.`
            : "Not filed under a Pit Pass event. Teams and drivers are the feed's own."}
        </p>
      )}
      <RaceControlStrip
        now={tower.raceControl}
        utcOffsetHours={tower.session?.clock?.utcOffsetHours}
        logHref={`${base}?view=control`}
      />
      <div className="tower-tools">
        <OrderChoice order={overall ? 'overall' : 'class'} hasOverall={hasOverall} onChange={setOrder} />
        <ColumnChoice available={available} shown={shown} onChange={setShown} />
      </div>
      <table
        className={`grid-table tower${sectorCount > 0 ? ' tower--sectors' : ''}${overall ? ' tower--overall' : ''}`}
        aria-label={overall ? 'Running order overall' : 'Running order by class'}
      >
        <thead>
          <tr>
            <th className="num" scope="col" title={overall ? 'Position overall' : 'Position in class'}>
              Pos
            </th>
            <th className="num" scope="col">
              #
            </th>
            {overall && (
              <th scope="col" title="Class, and position in it">
                Class
              </th>
            )}
            <th scope="col">Driver</th>
            <th scope="col" className="tower-team">
              Team
            </th>
            <th className="num" scope="col">
              Laps
            </th>
            <th className="num" scope="col">
              Gap
            </th>
            <th className="num tower-int" scope="col">
              Int
            </th>
            {hasLaps && (
              <>
                <th className="num tower-last" scope="col">
                  Last
                </th>
                <th className="num" scope="col">
                  Best
                </th>
                {Array.from({ length: sectorCount }, (_, i) => (
                  <th key={i} className="num tower-sector" scope="col" title={`Sector ${i + 1}, as it is run`}>
                    S{i + 1}
                  </th>
                ))}
                {show('topSpeed') && (
                  <th className="num tower-speed" scope="col" title={`Best speed trap of the session${tower.speedUnit ? ` (${tower.speedUnit})` : ''}`}>
                    Top
                  </th>
                )}
                <th className="num tower-stint" scope="col" title="Laps and time in the current stint">
                  Stint
                </th>
              </>
            )}
            {show('pits') && (
              <th className="num" scope="col" title="The last stop's pit-lane time, then how many stops so far">
                Last pit
              </th>
            )}
            {show('energy') && (
              <th className="num" scope="col" title="Energy remaining (IMSA telemetry)">
                Energy
              </th>
            )}
            <th scope="col">
              <span className="sr-only">Pit or status</span>
            </th>
          </tr>
        </thead>
        {overall ? (
          <tbody>{overallRows.map(({ car, cls }) => row(car, cls))}</tbody>
        ) : (
          tower.classes.map((cls) => (
            <tbody key={cls.className} style={{ '--class-color': cls.color ?? undefined } as CSSProperties}>
              <tr className="class-band">
                <td colSpan={columns}>
                  <span className="band-label">
                    {cls.className}
                    {cls.feedClass !== cls.className && <span className="band-feed"> ({cls.feedClass})</span>}
                  </span>
                  <BestSectors cls={cls} />
                </td>
              </tr>
              {cls.cars.map((car) => row(car, cls))}
            </tbody>
          ))
        )}
      </table>
      <p className="timing-foot">
        {!unfiled && `${tower.matched} of ${tower.total} cars matched to ${tower.filedEventName ?? 'the event'}'s entries.`}
        {hasLaps ? ' Select a car for its laps and stints.' : ' Lap and stint columns appear once lap data is recorded.'}
      </p>
      {open && (
        <LiveCarModal
          carNumber={open.car.carNumber}
          teamName={open.car.teamName}
          className={open.cls.className}
          classColor={open.cls.color}
          sessionDbId={tower.sessionDbId}
          onClose={() => setOpen(null)}
        />
      )}
    </>
  )
}

/** Columns a viewer may hide or show; the rest are the tower itself. */
type OptionalColumn = 'sectors' | 'topSpeed' | 'pits' | 'energy'

const OPTIONAL_COLUMNS: { id: OptionalColumn; label: string }[] = [
  { id: 'sectors', label: 'Sectors' },
  { id: 'topSpeed', label: 'Top speed' },
  { id: 'pits', label: 'Pits' },
  { id: 'energy', label: 'Energy' },
]

/** Top speed is opt-in: with every other column on it would push the tower past 1024px. */
const DEFAULT_SHOWN: Record<OptionalColumn, boolean> = { sectors: true, topSpeed: false, pits: true, energy: true }
const COLUMNS_KEY = 'pitpass.timing.columns'

/** Which optional columns this viewer wants, remembered in this browser only (a convenience, never shared). */
function useColumnChoice(): [Record<OptionalColumn, boolean>, (next: Record<OptionalColumn, boolean>) => void] {
  const [shown, setShown] = useState<Record<OptionalColumn, boolean>>(() => {
    try {
      const saved = JSON.parse(localStorage.getItem(COLUMNS_KEY) ?? 'null')
      return saved && typeof saved === 'object' ? { ...DEFAULT_SHOWN, ...saved } : DEFAULT_SHOWN
    } catch {
      return DEFAULT_SHOWN
    }
  })
  const save = (next: Record<OptionalColumn, boolean>) => {
    setShown(next)
    try {
      localStorage.setItem(COLUMNS_KEY, JSON.stringify(next))
    } catch {
      // Private window or blocked storage: the choice lasts until reload.
    }
  }
  return [shown, save]
}

/** A small disclosure above the tower listing only the optional columns the feed has data for. */
function ColumnChoice({
  available,
  shown,
  onChange,
}: {
  available: Record<OptionalColumn, boolean>
  shown: Record<OptionalColumn, boolean>
  onChange: (next: Record<OptionalColumn, boolean>) => void
}) {
  const offered = OPTIONAL_COLUMNS.filter((c) => available[c.id])
  if (offered.length === 0) return null
  return (
    <details className="tower-columns">
      <summary>Columns</summary>
      <fieldset>
        <legend className="sr-only">Optional columns</legend>
        {offered.map((c) => (
          <label key={c.id}>
            <input type="checkbox" checked={shown[c.id]} onChange={(e) => onChange({ ...shown, [c.id]: e.target.checked })} />
            {c.label}
          </label>
        ))}
      </fieldset>
    </details>
  )
}

type Order = 'class' | 'overall'
const ORDER_KEY = 'pitpass.timing.order'

/** By class or overall, remembered in this browser only, like the column choice. */
function useOrderChoice(): [Order, (next: Order) => void] {
  const [order, setOrder] = useState<Order>(() => {
    try {
      return localStorage.getItem(ORDER_KEY) === 'overall' ? 'overall' : 'class'
    } catch {
      return 'class'
    }
  })
  const save = (next: Order) => {
    setOrder(next)
    try {
      localStorage.setItem(ORDER_KEY, next)
    } catch {
      // Private window or blocked storage: the choice lasts until reload.
    }
  }
  return [order, save]
}

/**
 * The tower by class (a band per class) or overall (one list in the feed's
 * overall order, for battles on track between classes). Overall waits for
 * the feed's overall standings.
 */
function OrderChoice({ order, hasOverall, onChange }: { order: Order; hasOverall: boolean; onChange: (next: Order) => void }) {
  return (
    <div className="seg tower-order" role="group" aria-label="Running order">
      {(['class', 'overall'] as const).map((o) => (
        <button
          key={o}
          className={`seg-btn${order === o ? ' active' : ''}`}
          aria-pressed={order === o}
          disabled={o === 'overall' && !hasOverall}
          title={o === 'overall' && !hasOverall ? "The feed's overall order has not arrived yet" : undefined}
          onClick={() => onChange(o)}
        >
          {o === 'class' ? 'By class' : 'Overall'}
        </button>
      ))}
    </div>
  )
}

/** How long a row that changed place stays marked. */
const MOVE_MS = 4000

/**
 * Cars whose place in the running order changed on the latest poll, each with
 * a stamp (the row's key includes it, so a second move restarts the flash).
 * Nothing flashes on the first tower seen: there is nothing to compare with.
 */
function useMoves(tower: Tower | null): Map<string, number> {
  const previous = useRef<Map<string, string> | null>(null)
  const timers = useRef<number[]>([])
  const [moves, setMoves] = useState<Map<string, number>>(() => new Map())
  useEffect(() => {
    if (!tower) return
    const now = new Map<string, string>()
    tower.classes.forEach((c) => c.cars.forEach((car) => now.set(car.carNumber, `${c.className}|${car.position}`)))
    const before = previous.current
    previous.current = now
    if (!before) return
    const changed = [...now].filter(([car, place]) => before.has(car) && before.get(car) !== place).map(([car]) => car)
    if (changed.length === 0) return
    const stamp = Date.now()
    setMoves((m) => new Map([...m, ...changed.map((car) => [car, stamp] as const)]))
    timers.current.push(
      window.setTimeout(
        () => setMoves((m) => new Map([...m].filter(([car, s]) => !(changed.includes(car) && s === stamp)))),
        MOVE_MS,
      ),
    )
  }, [tower])
  useEffect(() => () => timers.current.forEach((t) => window.clearTimeout(t)), [])
  return moves
}

/** Where a row stands in the order shown: in its class, or overall. */
interface Place {
  position: number | null
  gapMs: number | null
  gapLaps: number | null
  intervalMs: number | null
  intervalLaps: number | null
}

function TowerRow({
  moved,
  hasStarts,
  car,
  place,
  classTag,
  classBestMs,
  hasLaps,
  sectorCount,
  classSectors,
  topSpeed,
  hasPits,
  hasEnergy,
  now,
  unfiled,
  onOpen,
}: {
  moved: boolean
  hasStarts: boolean
  car: TowerCar
  place: Place
  /** Overall: the car's class, shown on the row since there is no band. */
  classTag: TowerClass | null
  classBestMs: number | null
  hasLaps: boolean
  sectorCount: number
  classSectors: TowerClass['bestSectors']
  /** Shown when set: the class's fastest trap, to mark, and the unit. */
  topSpeed: { fastest: number | null; unit: string | null } | null
  hasPits: boolean
  hasEnergy: boolean
  now: number | null
  unfiled: boolean
  onOpen: () => void
}) {
  const running = !car.status || car.status === 'CLASSIFIED' || car.status === 'RUNNING'
  const isClassBest = classBestMs != null && car.bestLapMs === classBestMs
  const lastMark = lastLapMark(car, classBestMs)
  const stintTime = car.stintStartMs != null && now != null ? duration(now - car.stintStartMs) : null
  const leader = place.position === 1
  return (
    <tr
      className={`tower-row${running ? '' : ' tower-row--out'}${moved ? ' tower-row--moved' : ''}`}
      style={classTag ? ({ '--class-color': classTag.color ?? undefined } as CSSProperties) : undefined}
      onClick={onOpen}
    >
      <td className="num tower-pos">
        {place.position ?? ''}
        {hasStarts && <Gained places={placesGained(car)} />}
      </td>
      <td className="num tower-car">
        <button
          type="button"
          className="tower-carlink"
          aria-label={`#${car.carNumber}${car.teamName ? ` ${car.teamName}` : ''}: laps and stints`}
          onClick={(e) => {
            e.stopPropagation()
            onOpen()
          }}
        >
          {car.carNumber}
        </button>
      </td>
      {classTag && (
        <td className="tower-class">
          <span className="class-tag">{classTag.className}</span>{' '}
          <span className="tower-class-pos" title={`P${car.position} in ${classTag.className}`}>
            {car.position}
          </span>
        </td>
      )}
      <td className="tower-driver">
        {car.driverName ?? <span className="muted">—</span>}
        {car.driverRating && (
          <span className="tower-rating" title={ratingName(car.driverRating) ?? undefined}>
            {car.driverRating}
          </span>
        )}
      </td>
      <td className="tower-team">
        {car.teamName ?? <span className="muted">—</span>}
        {car.entryId == null && !unfiled && (
          <span className="tower-unmatched" title="Not in this event's entry list">
            not entered
          </span>
        )}
      </td>
      <td className="num">{car.laps ?? ''}</td>
      <td className="num">{leader ? '' : gap(place.gapMs, place.gapLaps)}</td>
      <td className="num tower-int">{leader ? '' : gap(place.intervalMs, place.intervalLaps)}</td>
      {hasLaps && (
        <>
          <td
            className={`num tower-last${lastMark === 'class' ? ' tower-class-best' : lastMark === 'pb' ? ' tower-pb' : ''}`}
            title={lastMark === 'class' ? 'Fastest in class' : lastMark === 'pb' ? 'Personal best' : undefined}
          >
            {lapTime(car.lastLapMs)}
            {lastMark && <span className="sr-only">{lastMark === 'class' ? ' (fastest in class)' : ' (personal best)'}</span>}
          </td>
          <td
            className={`num${isClassBest ? ' tower-class-best' : ''}`}
            title={
              [
                isClassBest && 'Fastest in class',
                car.bestLapDriver && `Set by ${car.bestLapDriver}`,
                car.idealMs != null && `Ideal ${lapTime(car.idealMs)}`,
              ]
                .filter(Boolean)
                .join(' · ') || undefined
            }
          >
            {lapTime(car.bestLapMs)}
            {isClassBest && <span className="sr-only"> (fastest in class)</span>}
          </td>
          {Array.from({ length: sectorCount }, (_, i) => (
            <SectorCell key={i} sector={car.sectors?.[i] ?? null} carBest={car.bestSectorMs?.[i]} classBest={classSectors[i]?.ms} />
          ))}
          {topSpeed && (
            <td
              className={`num tower-speed${car.topSpeed != null && car.topSpeed === topSpeed.fastest ? ' tower-class-best' : ''}`}
              title={car.topSpeed != null && car.topSpeed === topSpeed.fastest ? 'Fastest in class' : undefined}
            >
              {car.topSpeed != null ? car.topSpeed.toFixed(1) : ''}
              {car.topSpeed != null && topSpeed.unit && <span className="sr-only"> {topSpeed.unit}</span>}
            </td>
          )}
          <td className="num tower-stint">
            <span className="tower-pair">
              {car.stintLaps != null && !car.inPit && <span>{car.stintLaps} L</span>}
              {stintTime && <span className="muted">{stintTime}</span>}
            </span>
          </td>
        </>
      )}
      {hasPits && (
        <td className="num tower-pits">
          <span className="tower-pair">
            {car.lastPitMs != null && <span title="The last stop's pit-lane time">{duration(car.lastPitMs)}</span>}
            {car.pitStops != null && car.pitStops > 0 && (
              <span className="muted" title={`${car.pitStops} ${car.pitStops === 1 ? 'stop' : 'stops'} so far`}>
                ×{car.pitStops}
                <span className="sr-only"> stops</span>
              </span>
            )}
          </span>
        </td>
      )}
      {hasEnergy && (
        <td className="num tower-energy">
          <span className="tower-pair">
            {car.energyPct != null && <span>{Math.round(car.energyPct)}%</span>}
            {car.energyLapsLeft != null && (
              <span className="muted" title="Laps left at this stint's average use per lap">
                ~{Math.floor(car.energyLapsLeft)} L
              </span>
            )}
          </span>
        </td>
      )}
      <td className="tower-state">
        {!running ? (
          <span className="tower-status">{car.status?.toLowerCase().replace(/_/g, ' ')}</span>
        ) : car.checkered ? (
          <span className="tower-flag-mark" title="Has taken the chequered flag">
            <i aria-hidden="true" />
            <span className="sr-only">Has taken the chequered flag</span>
          </span>
        ) : car.inPit ? (
          <span className="tower-pit-mark">Pit</span>
        ) : car.trackStatus === 'OUT_LAP' ? (
          <span className="tower-out-mark" title="Out lap">Out</span>
        ) : (
          car.trackStatus === 'STOPPED' && <span className="tower-status">stopped</span>
        )}
      </td>
    </tr>
  )
}

/** Places gained (▲, success green) or lost (▼, error red) in class since the start; the words are for screen readers. */
function Gained({ places }: { places: number | null }) {
  // Always a slot, so positions line up whether or not a car has moved.
  if (places == null || places === 0) return <span className="tower-gain" aria-hidden="true" />
  const up = places > 0
  return (
    <span className={`tower-gain tower-gain--${up ? 'up' : 'down'}`} title={`${up ? 'Up' : 'Down'} ${Math.abs(places)} in class since the start`}>
      <span aria-hidden="true">{up ? '▲' : '▼'}{Math.abs(places)}</span>
      <span className="sr-only"> ({up ? 'up' : 'down'} {Math.abs(places)} since the start)</span>
    </span>
  )
}

/**
 * One sector's newest time: purple for the class's fastest, green for the
 * car's own best, as the lap columns. A time left from the previous lap (the
 * car has not run that sector again yet) is muted; an invalid one struck
 * through.
 */
function SectorCell({ sector, carBest, classBest }: { sector: SectorTime | null; carBest: number | null | undefined; classBest: number | null | undefined }) {
  if (!sector) return <td className="num tower-sector" />
  const mark = sectorMark(sector.ms, carBest, classBest)
  const cls = [
    'num tower-sector',
    mark === 'class' ? 'tower-class-best' : mark === 'pb' ? 'tower-pb' : '',
    !sector.currentLap && !mark ? 'tower-sector--old' : '',
    sector.valid === false ? 'tower-sector--invalid' : '',
  ].filter(Boolean).join(' ')
  const words = [mark === 'class' ? 'fastest in class' : mark === 'pb' ? 'personal best' : null,
    sector.valid === false ? 'invalid' : null, sector.currentLap ? null : 'previous lap'].filter(Boolean)
  return (
    <td className={cls} title={words.length ? words.join(', ') : undefined}>
      {lapTime(sector.ms)}
      {words.length > 0 && <span className="sr-only"> ({words.join(', ')})</span>}
    </td>
  )
}

/** The class's best sectors and who holds them, and their sum, on the class band. */
function BestSectors({ cls }: { cls: TowerClass }) {
  if (!cls.bestSectors?.some((s) => s.ms != null)) return null
  return (
    <span className="band-bests">
      {cls.bestSectors.map((s, i) =>
        s.ms == null ? null : (
          <span key={i}>
            S{i + 1} <span className="band-num">{lapTime(s.ms)}</span> #{s.car}
            {s.driver && <span className="band-driver"> {s.driver}</span>}
          </span>
        ),
      )}
      {cls.idealMs != null && (
        <span title="The class's best sectors added up">
          Ideal <span className="band-num">{lapTime(cls.idealMs)}</span>
        </span>
      )}
    </span>
  )
}

// ---- recorded sessions ------------------------------------------------------------------

/**
 * The views over one recorded session — gaps, sectors, pits, drive time —
 * share a session picker: the one asked for in the URL, else the one being
 * fed, else the newest.
 */
function SessionViews({
  view,
  scope,
  sessions,
  tower,
}: {
  view: Exclude<View, 'tower'>
  scope: TimingScope
  sessions: SessionSummary[] | null
  tower: Tower | null
}) {
  const [params, setParams] = useSearchParams()
  const asked = params.get('session')
  const chosen = useMemo(() => {
    if (!sessions || sessions.length === 0) return null
    const pick = sessions.find((s) => String(s.sessionDbId) === asked)
    return pick ?? sessions.find((s) => s.current) ?? sessions[0]
  }, [sessions, asked])

  if (sessions == null) {
    return (
      <div className="skeleton-block" aria-label="Loading sessions">
        <span className="skeleton" />
        <span className="skeleton" />
      </div>
    )
  }

  return (
    <>
      {sessions.length > 1 && (
        <div className="seg timing-sessions" role="group" aria-label="Session">
          {sessions.map((s) => (
            <button
              key={s.sessionDbId}
              className={`seg-btn${chosen?.sessionDbId === s.sessionDbId ? ' active' : ''}`}
              aria-pressed={chosen?.sessionDbId === s.sessionDbId}
              onClick={() =>
                setParams(
                  (p) => {
                    const next = new URLSearchParams(p)
                    next.set('session', String(s.sessionDbId))
                    return next
                  },
                  { replace: true },
                )
              }
            >
              {s.name ?? `Session ${s.sessionDbId}`}
              {s.current && <span className="timing-session-live"> · live</span>}
            </button>
          ))}
        </div>
      )}

      {!chosen ? (
        <div className="empty-state">
          No timed sessions recorded for {scope.kind === 'event' ? 'this event' : 'this series weekend'} yet. Laps
          and stints are recorded while live timing is connected.
        </div>
      ) : view === 'gaps' ? (
        <GapsView key={chosen.sessionDbId} session={chosen} />
      ) : view === 'sectors' ? (
        <SectorsView key={chosen.sessionDbId} session={chosen} />
      ) : view === 'pits' ? (
        <PitsView key={chosen.sessionDbId} session={chosen} />
      ) : view === 'control' ? (
        <RaceControlView
          key={chosen.sessionDbId}
          session={chosen}
          utcOffsetHours={chosen.current && tower?.sessionDbId === chosen.sessionDbId ? tower.session?.clock?.utcOffsetHours : null}
        />
      ) : (
        <DriveTimeView session={chosen} tower={tower} />
      )}
    </>
  )
}

// ---- drive time -----------------------------------------------------------------------

function DriveTimeView({ session, tower }: { session: SessionSummary; tower: Tower | null }) {
  // Rules and class colours belong to the event the session is filed under, if any.
  const eventId = session.eventId
  const [refresh, setRefresh] = useState(0)
  const { value: drive, error } = useLivePoll<DriveTimeResponse>(
    `/api/live/drive-time?session=${session.sessionDbId}`,
    10_000,
    refresh,
  )

  const classColors = new Map(
    (eventId != null && tower?.filedEventId === eventId ? tower.classes : []).map((c) => [c.className.toLowerCase(), c.color]),
  )

  if (!drive) {
    return error ? (
      <div className="error-panel">Could not load drive time: {error}</div>
    ) : (
      <div className="skeleton-block" aria-label="Loading drive time">
        <span className="skeleton" />
        <span className="skeleton" />
        <span className="skeleton" />
      </div>
    )
  }
  return (
    <>
      {error && <p className="timing-stale">Not updating: {error}</p>}
      <DriveTable drive={drive} classColors={classColors} />
      {eventId != null ? (
        <RulesPanel eventId={eventId} rules={drive.rules} drive={drive} onSaved={() => setRefresh((n) => n + 1)} />
      ) : (
        <p className="timing-foot">Drive-time rules belong to a Pit Pass event; this session is not filed under one.</p>
      )}
    </>
  )
}

const STATUS_ORDER = ['OVER_MAX', 'UNDER_MIN', 'OK', 'NO_RULE']

/**
 * The cars a number search keeps: each comma- or space-separated number picks
 * the car with exactly that number when there is one (#4 is not #04 or #44),
 * otherwise every car whose number starts with it, so typing narrows as it goes.
 */
function carsMatching(query: string, cars: string[]): Set<string> | null {
  const wanted = query
    .split(/[\s,]+/)
    .map((t) => t.replace(/^#/, '').toLowerCase())
    .filter(Boolean)
  if (wanted.length === 0) return null
  const keep = new Set<string>()
  for (const w of wanted) {
    const exact = cars.filter((c) => c.toLowerCase() === w)
    for (const c of exact.length > 0 ? exact : cars.filter((c) => c.toLowerCase().startsWith(w))) keep.add(c)
  }
  return keep
}

function DriveTable({ drive, classColors }: { drive: DriveTimeResponse; classColors: Map<string, string | null> }) {
  const [query, setQuery] = useState('')
  if (drive.drivers.length === 0) {
    return <div className="empty-state">No drivers recorded for this session yet.</div>
  }
  const allCars = [...new Set(drive.drivers.map((d) => d.car))]
  const keep = carsMatching(query, allCars)
  // Class → car → drivers, classes in first-seen order, cars by number.
  const classes = new Map<string, Map<string, DriveTimeResult[]>>()
  for (const d of drive.drivers) {
    if (keep && !keep.has(d.car)) continue
    const cls = d.className ?? 'Unmatched'
    if (!classes.has(cls)) classes.set(cls, new Map())
    const cars = classes.get(cls)!
    if (!cars.has(d.car)) cars.set(d.car, [])
    cars.get(d.car)!.push(d)
  }
  const counts = STATUS_ORDER.map((s) => [s, drive.drivers.filter((d) => d.status === s).length] as const)
  const hasRules = drive.rules.length > 0
  return (
    <>
      <div className="drive-search">
        <input
          type="search"
          inputMode="numeric"
          value={query}
          placeholder="Car numbers, e.g. 4, 911"
          aria-label="Filter by car number"
          onChange={(e) => setQuery(e.target.value)}
        />
        {keep && (
          <span className="muted" aria-live="polite">
            {keep.size === 0 ? 'No car with that number' : `${keep.size} of ${allCars.length} cars`}
          </span>
        )}
      </div>
      {hasRules && (
        <p className="timing-summary" aria-label="Drive time summary">
          {counts
            .filter(([s, n]) => n > 0 && s !== 'NO_RULE')
            .map(([s, n]) => (
              <span key={s} className={`dt-count dt-count--${s.toLowerCase()}`}>
                {n} {s === 'OVER_MAX' ? 'over the maximum' : s === 'UNDER_MIN' ? 'short of the minimum' : 'within the rules'}
              </span>
            ))}
        </p>
      )}
      {classes.size > 0 && (
        <table className="grid-table drive" aria-label="Drive time by driver">
          <thead>
            <tr>
              <th className="num" scope="col">
                #
              </th>
              <th scope="col">Driver</th>
              <th scope="col">Rating</th>
              <th className="num" scope="col" title="Track time: pit lane excluded, as the rule counts it">
                Drive time
              </th>
              <th scope="col">
                <span className="sr-only">Against the rule</span>
              </th>
              <th className="num" scope="col">
                Min
              </th>
              <th className="num" scope="col">
                Max
              </th>
              <th scope="col">Status</th>
            </tr>
          </thead>
          {[...classes.entries()].map(([cls, cars]) => (
            <tbody key={cls} style={{ '--class-color': classColors.get(cls.toLowerCase()) ?? undefined } as CSSProperties}>
              <tr className="class-band">
                <td colSpan={8}>
                  <span className="band-label">{cls}</span>
                </td>
              </tr>
              {[...cars.entries()]
                .sort(([a], [b]) => a.localeCompare(b, undefined, { numeric: true }))
                .map(([car, drivers]) => (
                  <Fragment key={car}>
                    {drivers.map((d, i) => (
                      <DriveRow key={d.driverOrder} d={d} showCar={i === 0} lastOfCar={i === drivers.length - 1} />
                    ))}
                  </Fragment>
                ))}
            </tbody>
          ))}
        </table>
      )}
    </>
  )
}

function DriveRow({ d, showCar, lastOfCar }: { d: DriveTimeResult; showCar: boolean; lastOfCar: boolean }) {
  const scale = Math.max(d.maxMs ?? 0, d.minMs ?? 0, d.driveMs, 1)
  const fill = Math.min(100, (d.driveMs / scale) * 100)
  const minAt = d.minMs != null ? (d.minMs / scale) * 100 : null
  return (
    <tr className={`drive-row${lastOfCar ? ' drive-row--car-end' : ''}`}>
      <td className="num drive-car">{showCar ? d.car : ''}</td>
      <td className="drive-name">
        {d.name ?? <span className="muted">Driver {d.driverOrder}</span>}
        {d.inCar && <span className="drive-incar">In car</span>}
      </td>
      <td className="drive-rating">{ratingName(d.rating) ?? <span className="muted">—</span>}</td>
      <td className="num drive-time">{duration(d.driveMs)}</td>
      <td className="drive-meter-cell">
        {(d.maxMs != null || d.minMs != null) && (
          <span className={`drive-meter drive-meter--${d.status.toLowerCase()}`} aria-hidden="true">
            <span className="drive-meter-fill" style={{ width: `${fill}%` }} />
            {minAt != null && <span className="drive-meter-min" style={{ left: `${minAt}%` }} />}
          </span>
        )}
      </td>
      <td className="num muted">{ruleTime(d.minMs)}</td>
      <td className="num muted">{ruleTime(d.maxMs)}</td>
      <td className={`drive-status drive-status--${d.status.toLowerCase()}`}>
        {d.status === 'OVER_MAX'
          ? `Over by ${duration(d.overMs)}`
          : d.status === 'UNDER_MIN'
            ? `${duration(d.owedMs)} to go`
            : d.status === 'OK'
              ? d.remainingMs != null
                ? `OK · ${duration(d.remainingMs)} left`
                : 'OK'
              : 'No rule'}
      </td>
    </tr>
  )
}

// ---- rules ----------------------------------------------------------------------------

interface DraftRule {
  className: string
  rating: string
  min: string
  max: string
  note: string
}

const toDraft = (r: DriveTimeRule): DraftRule => ({
  className: r.className,
  rating: r.rating ?? '',
  min: ruleTime(r.minMs),
  max: ruleTime(r.maxMs),
  note: r.note ?? '',
})

function RulesPanel({
  eventId,
  rules,
  drive,
  onSaved,
}: {
  eventId: number
  rules: DriveTimeRule[]
  drive: DriveTimeResponse
  onSaved: () => void
}) {
  const isAdmin = useIsAdmin()
  const [draft, setDraft] = useState<DraftRule[] | null>(null)
  const [saving, setSaving] = useState(false)
  const [problem, setProblem] = useState<string | null>(null)
  const classes = [...new Set(drive.drivers.map((d) => d.className).filter((c): c is string => !!c))]
  const ruleEvent = drive.eventId ?? eventId

  const describe = (r: DriveTimeRule) =>
    [
      r.minMs != null ? `min ${ruleTime(r.minMs)}` : null,
      r.maxMs != null ? `max ${ruleTime(r.maxMs)}` : null,
    ]
      .filter(Boolean)
      .join(' · ')

  const save = async () => {
    if (!draft) return
    const body: DriveTimeRule[] = []
    for (const [i, r] of draft.entries()) {
      const min = parseRuleTime(r.min)
      const max = parseRuleTime(r.max)
      if (min === undefined || max === undefined) {
        setProblem(`Row ${i + 1}: write times as hours ("4"), h:mm ("1:30") or h:mm:ss.`)
        return
      }
      body.push({ className: r.className.trim(), rating: r.rating || null, minMs: min, maxMs: max, note: r.note.trim() || null })
    }
    setSaving(true)
    setProblem(null)
    try {
      const res = await fetch(`/api/events/${ruleEvent}/drive-time-rules`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      })
      if (!res.ok) {
        const msg = await res.json().then((b) => b?.message).catch(() => null)
        setProblem(msg ?? `Saving failed (${res.status}).`)
        return
      }
      setDraft(null)
      onSaved()
    } catch (e) {
      setProblem(`Saving failed: ${(e as Error).message}`)
    } finally {
      setSaving(false)
    }
  }

  if (!draft) {
    return (
      <section className="rules" aria-labelledby="rules-title">
        <div className="rules-head">
          <h2 id="rules-title">Drive-time rules</h2>
          {isAdmin && (
            <button className="btn" onClick={() => setDraft(rules.length ? rules.map(toDraft) : [{ className: classes[0] ?? '', rating: '', min: '', max: '', note: '' }])}>
              {rules.length ? 'Edit rules' : 'Add rules'}
            </button>
          )}
        </div>
        {rules.length === 0 ? (
          <p className="muted">
            No rules for this event.{' '}
            {isAdmin ? 'Add the minimum and maximum drive times per class from the regulations.' : 'An admin can add them.'}
          </p>
        ) : (
          <ul className="rules-list">
            {rules.map((r) => (
              <li key={`${r.className}|${r.rating}`}>
                <span className="rules-class">{r.className}</span>
                <span className="rules-who">{r.rating ? ratingName(r.rating) : 'Every rating'}</span>
                <span className="rules-bounds">{describe(r)}</span>
                {r.note && <span className="muted">{r.note}</span>}
              </li>
            ))}
          </ul>
        )}
      </section>
    )
  }

  const update = (i: number, patch: Partial<DraftRule>) =>
    setDraft((d) => d && d.map((r, j) => (j === i ? { ...r, ...patch } : r)))

  return (
    <section className="rules rules--editing" aria-labelledby="rules-title">
      <div className="rules-head">
        <h2 id="rules-title">Drive-time rules</h2>
      </div>
      <p className="muted rules-help">
        A rating's rule overrides the class rule; a bound it leaves blank comes from the class rule. Times as hours
        (<code>4</code>), <code>h:mm</code> or <code>h:mm:ss</code>.
      </p>
      <datalist id="rule-classes">
        {classes.map((c) => (
          <option key={c} value={c} />
        ))}
      </datalist>
      <table className="rules-edit">
        <thead>
          <tr>
            <th scope="col">Class</th>
            <th scope="col">Applies to</th>
            <th scope="col">Minimum</th>
            <th scope="col">Maximum</th>
            <th scope="col">Note</th>
            <th scope="col">
              <span className="sr-only">Remove</span>
            </th>
          </tr>
        </thead>
        <tbody>
          {draft.map((r, i) => (
            <tr key={i}>
              <td>
                <input
                  aria-label={`Rule ${i + 1} class`}
                  list="rule-classes"
                  value={r.className}
                  onChange={(e) => update(i, { className: e.target.value })}
                />
              </td>
              <td>
                <select aria-label={`Rule ${i + 1} applies to`} value={r.rating} onChange={(e) => update(i, { rating: e.target.value })}>
                  <option value="">Every rating</option>
                  {RATINGS.map((x) => (
                    <option key={x.code} value={x.code}>
                      {x.name}
                    </option>
                  ))}
                </select>
              </td>
              <td>
                <input
                  aria-label={`Rule ${i + 1} minimum`}
                  className="num"
                  inputMode="numeric"
                  placeholder="none"
                  value={r.min}
                  onChange={(e) => update(i, { min: e.target.value })}
                />
              </td>
              <td>
                <input
                  aria-label={`Rule ${i + 1} maximum`}
                  className="num"
                  inputMode="numeric"
                  placeholder="none"
                  value={r.max}
                  onChange={(e) => update(i, { max: e.target.value })}
                />
              </td>
              <td>
                <input aria-label={`Rule ${i + 1} note`} value={r.note} onChange={(e) => update(i, { note: e.target.value })} />
              </td>
              <td>
                <button className="btn" aria-label={`Remove rule ${i + 1}`} onClick={() => setDraft((d) => d && d.filter((_, j) => j !== i))}>
                  Remove
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {problem && <div className="error-panel">{problem}</div>}
      <div className="rules-actions">
        <button
          className="btn"
          onClick={() => setDraft((d) => [...(d ?? []), { className: classes[0] ?? '', rating: '', min: '', max: '', note: '' }])}
        >
          Add rule
        </button>
        <span className="rules-spacer" />
        <button
          className="btn"
          onClick={() => {
            setDraft(null)
            setProblem(null)
          }}
          disabled={saving}
        >
          Cancel
        </button>
        <button className="btn btn-primary" onClick={save} disabled={saving}>
          {saving ? 'Saving…' : 'Save rules'}
        </button>
      </div>
    </section>
  )
}
