import { useEffect, useState } from 'react'
import { getJson, type ChampionshipSummary, type LiveChampionship, type LiveStatus, type Recap } from '../../lib/api'
import { baselineIssue, LIVE_KINDS, liveGap, liveLines, liveName, livePhases, supportedLiveChampionship } from '../../lib/championshipCalculator'
import { useLivePoll } from '../../lib/useLivePoll'
import { useSeason } from './SeasonLayout'

const words = (text: string) => text.replaceAll('_', ' ').toLowerCase().replace(/^./, c => c.toUpperCase())

/** Where the shared connection stands. Read-only on the web: the feed's one
 * login is connected and disconnected from the iPad app. */
export function LiveStatusLine({ status }: { status: LiveStatus }) {
  const state = status.desiredConnected ? status.state : 'OFF'
  const session = [status.session?.name, status.session?.flag && words(status.session.flag)].filter(Boolean).join(' · ')
  const headline = state === 'LIVE' ? (session ? `Live · ${session}` : 'Live timing connected')
    : state === 'BACKING_OFF' ? 'Live timing lost — retrying'
      : state === 'OFF' ? 'Live timing is off' : 'Connecting to live timing…'
  const detail = state === 'OFF' ? 'An admin connects it from the iPad app during a session.'
    : state === 'BACKING_OFF' ? status.lastError ?? 'Showing the last known order.'
      : [`Scoring ${status.eventName ?? 'an event'}`, status.session?.championship, status.replaying && 'replay'].filter(Boolean).join(' · ')
  return <p className={`calculator-live-status is-${state.toLowerCase()}`} role="status">
    <i className="calculator-live-dot" aria-hidden="true" /><strong>{headline}</strong><span>{detail}</span>
  </p>
}

/** Live mode: every class of one championship kind, projected from where the
 * field is running. Read-only — there is nothing to set, and no event to pick:
 * the event is the one the feed is being scored against. */
export function LiveCalculator({ status }: { status: LiveStatus }) {
  const { hub, classes, classColor } = useSeason()
  const supported = hub.championships.filter(supportedLiveChampionship)
  const kinds = LIVE_KINDS.filter(k => supported.some(c => c.kind?.toUpperCase() === k))
  const [kind, setKind] = useState(kinds[0] ?? 'TEAMS')
  const [hidden, setHidden] = useState<string[]>([])
  const shownKind = kinds.includes(kind) ? kind : kinds[0] ?? 'TEAMS'
  const championships = supported.filter(c => c.kind?.toUpperCase() === shownKind)
    .sort((a, b) => classes.findIndex(c => c.name === a.className) - classes.findIndex(c => c.name === b.className))
    .filter((c, i, all) => all.findIndex(other => other.className === c.className) === i)
  const visible = championships.filter(c => !hidden.includes(c.className ?? ''))
  const shown = visible.length ? visible : championships.slice(0, 1)
  const kindLabel = (k: string) => supported.find(c => c.kind?.toUpperCase() === k)?.kindLabel ?? words(k)

  if (!status.desiredConnected) return <p className="empty-state">Live timing is off. Once it is connected for an event of this season, the standings are projected here as the field runs.</p>
  const eventId = status.eventId
  if (!eventId || !hub.events.some(e => e.id === eventId)) return <p className="empty-state">Live timing is scoring {status.eventName ?? 'another event'}, which is not an event of this season.</p>
  if (status.state !== 'LIVE' && status.state !== 'BACKING_OFF') return <p role="status">Connecting to live timing…</p>
  if (!championships.length) return <p className="empty-state">Import IMSA WeatherTech or Michelin Pilot Challenge standings to project them live.</p>

  return <>
    <div className="calculator-context">
      <div className="seg" role="group" aria-label="Championship kind">
        {kinds.map(k => <button key={k} type="button" className={`seg-btn${k === shownKind ? ' active' : ''}`} aria-pressed={k === shownKind} onClick={() => setKind(k)}>{kindLabel(k)}</button>)}
      </div>
    </div>
    <div className="class-chips" role="group" aria-label="Visible live classes">
      {championships.map(c => {
        const name = c.className ?? ''
        const active = shown.includes(c)
        return <button key={c.id} type="button" className={`class-chip${active ? ' active' : ''}`} aria-pressed={active}
          aria-label={`Show ${name}`} disabled={active && shown.length === 1}
          style={{ '--chip-color': classColor(name) } as React.CSSProperties}
          onClick={() => setHidden(active ? [...hidden, name] : hidden.filter(n => n !== name))}><i className="swatch" />{name}</button>
      })}
    </div>
    <div className="calculator-panels">
      {shown.map(c => <section key={c.id} className="calculator-panel" aria-label={`${c.className} live ${kindLabel(shownKind).toLowerCase()}`}>
        <h3>{c.className}</h3>
        <LiveChampionshipPanel championship={c} eventId={eventId} />
      </section>)}
    </div>
    <p className="calculator-note">Provisional. Projected = imported + points for the positions as they run. Guest eligibility, drive-time minimums, penalties still to come and official tie-breaks are not applied; equal totals remain tied. The Endurance Cup is not projected live.</p>
  </>
}

/** One class: imported standings fetched once, live positions polled. */
function LiveChampionshipPanel({ championship, eventId }: { championship: ChampionshipSummary; eventId: number }) {
  const [recap, setRecap] = useState<Recap | null>(null)
  const [error, setError] = useState('')
  const [attempt, setAttempt] = useState(0)
  const [showAll, setShowAll] = useState(false)
  const live = useLivePoll<LiveChampionship>(`/api/live/championships/${championship.id}`)
  useEffect(() => {
    let cancelled = false
    setError('')
    getJson<Recap>(`/api/championships/${championship.id}/calculator`).then(data => { if (!cancelled) setRecap(data) })
      .catch(e => { if (!cancelled) setError(String(e.message ?? e)) })
    return () => { cancelled = true }
  }, [championship.id, attempt])

  if (error) return <div role="alert" className="error-panel">{error} <button onClick={() => setAttempt(n => n + 1)}>Retry</button></div>
  if (!recap) return <p role="status">Loading imported standings…</p>
  const issue = baselineIssue(recap, eventId)
  if (issue) return <p className="error-panel" role="status">{issue}</p>
  if (!live.value) return live.error ? <p className="error-panel" role="alert">{live.error}</p> : <p role="status">Waiting for the running order…</p>
  return <LiveTable recap={recap} live={live.value} showAll={showAll} setShowAll={setShowAll} />
}

const SHORT_LIST = 12

export function LiveTable({ recap, live, showAll, setShowAll }: { recap: Recap; live: LiveChampionship; showAll: boolean; setShowAll: (all: boolean) => void }) {
  const phases = livePhases(recap.championship.seriesName ?? '')
  const lines = liveLines(recap, live)
  const who = live.kind === 'DRIVERS' ? 'Driver' : live.kind === 'MANUFACTURERS' ? 'Manufacturer' : 'Team'
  const notices = [
    live.state === 'BACKING_OFF' && 'Connection lost — this is the last known order.',
    live.livePhase === 'RACE' && phases.includes('Qualifying') && !live.qualifyingImported &&
      'This weekend’s qualifying result is not imported, so its points are missing from the projection. Import it to include them.',
    live.livePhase === 'QUALIFYING' && (phases.includes('Qualifying')
      ? 'Qualifying is running: positions fill the qualifying column. Classes that are not on track show none.'
      : 'Qualifying does not pay championship points in this series.'),
    !live.livePhase && 'The session on track does not pay points. The table fills when qualifying or the race is running.',
  ].filter((n): n is string => !!n)
  return <>
    {notices.map(n => <p key={n} className="calculator-note" role="status">{n}</p>)}
    {(live.livePhase || live.qualifyingImported) && <>
      <div className="calculator-scroll"><table className="calculator-table calculator-live-table">
        <caption className="sr-only">{recap.championship.className} {who.toLowerCase()} standings projected from the running order</caption>
        <thead><tr><th scope="col">Rank</th><th scope="col"><abbr title="Places gained or lost against the imported standings">±</abbr></th><th scope="col">{who}</th><th scope="col">Projected</th><th scope="col">Gap</th><th scope="col">Imported</th>{phases.map(p => <th scope="col" key={p}>{p}</th>)}<th scope="col">Running</th></tr></thead>
        <tbody>{(showAll ? lines : lines.slice(0, SHORT_LIST)).map(line => <tr key={line.competitorKey}>
          <td>{line.tied ? '=' : ''}{line.rank}</td>
          <td className={line.movement > 0 ? 'is-up' : line.movement < 0 ? 'is-down' : ''}>{line.movement === 0 ? <span aria-label="No change">–</span>
            : <span aria-label={`${line.movement > 0 ? 'Up' : 'Down'} ${Math.abs(line.movement)}`}>{line.movement > 0 ? '▲' : '▼'}{Math.abs(line.movement)}</span>}</td>
          <th scope="row">{liveName(line, live.kind)}</th>
          <td className="calculator-total">{line.total}</td><td>{line.gap}</td><td>{line.totalPoints}</td>
          {phases.map((phase, i) => <td key={phase}>{line.positions[i] ? <>P{line.positions[i]}<small>+{line.awards[i]}</small></> : '–'}</td>)}
          <td className="calculator-live-running">{line.running ? <>#{line.running.carNumber}{liveGap(line.running) && ` · ${liveGap(line.running)}`}
            {line.running.status && line.running.status !== 'CLASSIFIED' && <small>{words(line.running.status)}</small>}</> : <small>Not running</small>}</td>
        </tr>)}</tbody>
      </table></div>
      {lines.length > SHORT_LIST && <button type="button" className="calculator-live-more" onClick={() => setShowAll(!showAll)}>{showAll ? `Show the top ${SHORT_LIST}` : `Show all ${lines.length}`}</button>}
    </>}
    {live.newcomers.length > 0 && <p className="calculator-note">Scoring without a standings row (baseline 0): {[...live.newcomers].sort((a, b) => a.position - b.position).map(n => `${n.name} · #${n.carNumber} · P${n.position}`).join('; ')}</p>}
  </>
}
