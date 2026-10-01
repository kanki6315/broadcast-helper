import type { CSSProperties } from 'react'
import { useLivePoll } from '../lib/useLivePoll'
import {
  messageTime,
  type RaceControlLog,
  type RaceControlMessage,
  type RaceControlNow,
  type SessionSummary,
} from '../lib/liveTiming'

/**
 * Race control's messages from the Al Kamel feed: a strip above the tower
 * with what race control's screen shows now (or, when it shows nothing, its
 * newest message), and the session's whole log as its own view. Colours are
 * race control's own, used only as a bar beside the text so ink contrast
 * stays ours.
 */

const bar = (m: RaceControlMessage) => ({ '--rc-color': m.background ?? undefined }) as CSSProperties

export function RaceControlStrip({
  now,
  utcOffsetHours,
  logHref,
}: {
  now: RaceControlNow | null | undefined
  utcOffsetHours: number | null | undefined
  logHref: string
}) {
  if (!now || (now.lines.length === 0 && !now.latest)) return null
  const latest = now.latest
  // The newest message is usually on the screen already; say it once.
  const latestOnScreen = latest != null && now.lines.some((l) => l.text === latest.text)
  return (
    <section className="rc-strip" aria-label="Race control">
      <span className="rc-label">Race control</span>
      <div className="rc-body" aria-live="polite">
        {now.lines.length > 0 && (
          <ul className="rc-lines">
            {now.lines.map((m) => (
              <li key={m.key} className={`rc-msg${m.blink ? ' rc-msg--blink' : ''}`} style={bar(m)}>
                {m.group && <span className="rc-group">{m.group}</span>}
                {m.text}
              </li>
            ))}
          </ul>
        )}
        {latest && !latestOnScreen && (
          <p className="rc-latest" style={bar(latest)}>
            {latest.dayTimeMs != null && <time className="rc-time">{messageTime(latest.dayTimeMs, utcOffsetHours)}</time>}
            {latest.group && <span className="rc-group">{latest.group}</span>}
            {latest.text}
          </p>
        )}
      </div>
      <a className="rc-all" href={logHref}>
        All messages
      </a>
    </section>
  )
}

/** The session's log, newest first. Times are at the track when its offset is known (the live session). */
export function RaceControlView({
  session,
  utcOffsetHours,
}: {
  session: SessionSummary
  utcOffsetHours: number | null | undefined
}) {
  const { value, error } = useLivePoll<RaceControlLog>(
    `/api/live/race-control?session=${session.sessionDbId}`,
    session.current ? 5_000 : 120_000,
  )
  if (!value) {
    return error ? (
      <div className="error-panel">Could not load race control messages: {error}</div>
    ) : (
      <div className="skeleton-block" aria-label="Loading race control messages">
        <span className="skeleton" />
        <span className="skeleton" />
        <span className="skeleton" />
      </div>
    )
  }
  if (value.messages.length === 0) {
    return <div className="empty-state">No race control messages recorded for this session.</div>
  }
  const atTrack = utcOffsetHours != null
  return (
    <>
      {error && <p className="timing-stale">Not updating: {error}</p>}
      <table className="grid-table rc-log" aria-label="Race control messages, newest first">
        <thead>
          <tr>
            <th className="num" scope="col" title={atTrack ? 'Time of day at the track' : 'Your local time'}>
              Time
            </th>
            <th scope="col">Class</th>
            <th scope="col">Message</th>
          </tr>
        </thead>
        <tbody>
          {value.messages.map((m) => (
            <tr key={m.key}>
              <td className="num rc-log-time" style={bar(m)}>
                {messageTime(m.dayTimeMs, utcOffsetHours) ?? '—'}
              </td>
              <td className="rc-log-group">{m.group ?? ''}</td>
              <td className="rc-log-text">{m.text}</td>
            </tr>
          ))}
        </tbody>
      </table>
      {!atTrack && <p className="timing-foot">Times are your own clock: the track's offset is known only while the session is live.</p>}
    </>
  )
}
