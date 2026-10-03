import { useEffect, useRef, useState, type PointerEvent, type RefObject } from 'react'
import { useLivePoll } from '../lib/useLivePoll'
import {
  messageTime,
  pressure,
  temperature,
  wind,
  type SessionSummary,
  type WeatherLog,
  type WeatherReading,
} from '../lib/liveTiming'

/**
 * The track's weather station from the Al Kamel feed: a strip above the
 * tower with the latest reading, and the session's readings (one a minute)
 * as their own view — track and air temperature over the session, how far
 * each has moved since the first reading, and every reading in a table.
 * Units follow the feed's: °F, mph and inHg when it times in mph.
 */

/** "Air 24.3° · Track 38.3° · Humidity 61% · Wind 14 km/h SW · 1012 mbar", as parts; the station's gaps left out. */
function parts(r: WeatherReading, us: boolean): { label: string; value: string }[] {
  const out: { label: string; value: string | null }[] = [
    { label: 'Track', value: temperature(r.trackC, r.trackF, us) },
    { label: 'Air', value: temperature(r.airC, r.airF, us) },
    { label: 'Humidity', value: r.humidityPct == null ? null : `${Math.round(r.humidityPct)}%` },
    { label: 'Wind', value: wind(r, us) },
    { label: 'Pressure', value: pressure(r, us) },
  ]
  return out.filter((p): p is { label: string; value: string } => p.value != null)
}

export function WeatherStrip({
  now,
  us,
  href,
}: {
  now: WeatherReading | null | undefined
  us: boolean
  href: string
}) {
  if (!now) return null
  const shown = parts(now, us)
  if (shown.length === 0) return null
  return (
    <section className="wx-strip" aria-label="Weather">
      <span className="wx-label">Weather</span>
      <dl className="wx-now">
        {shown.map((p) => (
          <div key={p.label}>
            <dt>{p.label}</dt>
            <dd>{p.value}</dd>
          </div>
        ))}
      </dl>
      <a className="wx-all" href={href}>
        Over the session
      </a>
    </section>
  )
}

type Series = { key: 'track' | 'air'; label: string; slot: 1 | 2; at: (r: WeatherReading) => number | null }

const SERIES = (us: boolean): Series[] => [
  { key: 'track', label: 'Track', slot: 2, at: (r) => (us ? r.trackF : r.trackC) },
  { key: 'air', label: 'Air', slot: 1, at: (r) => (us ? r.airF : r.airC) },
]

const signed = (v: number) => `${v > 0 ? '+' : v < 0 ? '−' : '±'}${Math.abs(v).toFixed(1)}°`

/** The session's readings. Times are at the track when its offset is known (the live session). */
export function WeatherView({
  session,
  us,
  utcOffsetHours,
}: {
  session: SessionSummary
  us: boolean
  utcOffsetHours: number | null | undefined
}) {
  const { value, error } = useLivePoll<WeatherLog>(
    `/api/live/weather?session=${session.sessionDbId}`,
    session.current ? 30_000 : 120_000,
  )
  if (!value) {
    return error ? (
      <div className="error-panel">Could not load the weather: {error}</div>
    ) : (
      <div className="skeleton-block" aria-label="Loading the weather">
        <span className="skeleton" />
        <span className="skeleton" />
        <span className="skeleton" />
      </div>
    )
  }
  const readings = value.readings.filter((r) => r.dayTimeMs != null)
  if (readings.length === 0) {
    return <div className="empty-state">No weather recorded for this session.</div>
  }
  const atTrack = utcOffsetHours != null
  const series = SERIES(us)
  return (
    <>
      {error && <p className="timing-stale">Not updating: {error}</p>}
      <dl className="wx-summary">
        {series.map((s) => {
          const vals = readings.map(s.at).filter((v): v is number => v != null)
          if (vals.length === 0) return null
          const first = vals[0]
          const last = vals[vals.length - 1]
          return (
            <div key={s.key} className={`wx-stat an-s${s.slot}`}>
              <dt>
                <i aria-hidden="true" />
                {s.label}
              </dt>
              <dd>
                <span className="wx-stat-now">{last.toFixed(1)}°</span>{' '}
                <span className="wx-stat-change">{signed(last - first)} since {first.toFixed(1)}°</span>
                <span className="wx-stat-range">
                  Low {Math.min(...vals).toFixed(1)}° · high {Math.max(...vals).toFixed(1)}°
                </span>
              </dd>
            </div>
          )
        })}
      </dl>
      {readings.length > 1 && <WeatherChart readings={readings} series={series} utcOffsetHours={utcOffsetHours} />}
      <details className="wx-readings">
        <summary>Every reading ({readings.length})</summary>
        <table className="grid-table wx-table" aria-label="Weather readings, newest first">
          <thead>
            <tr>
              <th className="num" scope="col" title={atTrack ? 'Time of day at the track' : 'Your local time'}>
                Time
              </th>
              <th className="num" scope="col">Track</th>
              <th className="num" scope="col">Air</th>
              <th className="num" scope="col">Humidity</th>
              <th scope="col">Wind</th>
              <th className="num" scope="col">Pressure</th>
            </tr>
          </thead>
          <tbody>
            {[...readings].reverse().map((r) => (
              <tr key={r.dayTimeMs}>
                <td className="num">{messageTime(r.dayTimeMs, utcOffsetHours)}</td>
                <td className="num">{temperature(r.trackC, r.trackF, us) ?? '—'}</td>
                <td className="num">{temperature(r.airC, r.airF, us) ?? '—'}</td>
                <td className="num">{r.humidityPct == null ? '—' : `${Math.round(r.humidityPct)}%`}</td>
                <td>{wind(r, us) ?? '—'}</td>
                <td className="num">{pressure(r, us) ?? '—'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </details>
      <p className="timing-foot wx-foot">
        Degrees {us ? 'Fahrenheit' : 'Celsius'}, as the feed times. Wind is the direction it blows from.
        {!atTrack && ' Times are your own clock: the track’s offset is known only while the session is live.'}
      </p>
    </>
  )
}

const HEIGHT = 260
const PAD = { top: 12, right: 64, bottom: 28, left: 48 }

function useWidth(): [RefObject<HTMLDivElement | null>, number] {
  const ref = useRef<HTMLDivElement>(null)
  const [width, setWidth] = useState(800)
  useEffect(() => {
    const el = ref.current
    if (!el) return
    const ro = new ResizeObserver(([e]) => setWidth(Math.max(300, Math.floor(e.contentRect.width))))
    ro.observe(el)
    return () => ro.disconnect()
  }, [])
  return [ref, width]
}

/** Clock time without seconds, for axis ticks. */
const hm = (ms: number, offset: number | null | undefined) => messageTime(ms, offset)?.replace(/:\d\d$/, '') ?? ''

/** Track and air temperature over the session: one line each, labelled at their ends, a crosshair for any minute. */
function WeatherChart({
  readings,
  series,
  utcOffsetHours,
}: {
  readings: WeatherReading[]
  series: Series[]
  utcOffsetHours: number | null | undefined
}) {
  const [wrap, width] = useWidth()
  const [hover, setHover] = useState<number | null>(null)
  const t0 = readings[0].dayTimeMs!
  const t1 = readings[readings.length - 1].dayTimeMs!
  const all = readings.flatMap((r) => series.map((s) => s.at(r))).filter((v): v is number => v != null)
  if (all.length === 0) return null
  // Whole degrees, at least 4° tall, so a quiet afternoon is not drawn as a storm.
  let lo = Math.floor(Math.min(...all))
  let hi = Math.ceil(Math.max(...all))
  if (hi - lo < 4) {
    const mid = (hi + lo) / 2
    lo = Math.floor(mid - 2)
    hi = lo + 4
  }
  const step = [1, 2, 5, 10, 20].find((s) => (hi - lo) / s <= 5) ?? 20
  lo = Math.floor(lo / step) * step
  hi = Math.ceil(hi / step) * step

  const plotW = width - PAD.left - PAD.right
  const plotH = HEIGHT - PAD.top - PAD.bottom
  const x = (t: number) => PAD.left + (t1 === t0 ? plotW / 2 : ((t - t0) / (t1 - t0)) * plotW)
  const y = (v: number) => PAD.top + ((hi - v) / (hi - lo)) * plotH

  const yTicks: number[] = []
  for (let v = lo; v <= hi; v += step) yTicks.push(v)
  // Time ticks on whole 5/10/15/30/60/120 minutes, about one per 110px.
  const spanMin = (t1 - t0) / 60_000
  const every = [5, 10, 15, 30, 60, 120, 240].find((m) => spanMin / m <= Math.max(2, plotW / 110)) ?? 240
  const xTicks: number[] = []
  for (let t = Math.ceil(t0 / (every * 60_000)) * every * 60_000; t <= t1; t += every * 60_000) xTicks.push(t)

  // A line breaks where a reading is missing a value, as the gaps chart breaks where a car was lapped.
  const path = (s: Series) => {
    let d = ''
    let pen = false
    for (const r of readings) {
      const v = s.at(r)
      if (v == null) {
        pen = false
        continue
      }
      d += `${pen ? 'L' : 'M'}${x(r.dayTimeMs!).toFixed(1)},${y(v).toFixed(1)}`
      pen = true
    }
    return d
  }
  const labels = series
    .map((s) => {
      const last = [...readings].reverse().find((r) => s.at(r) != null)
      return last ? { s, x: x(last.dayTimeMs!), y: y(s.at(last)!) } : null
    })
    .filter((l): l is NonNullable<typeof l> => l != null)
    .sort((a, b) => a.y - b.y)
  for (let i = 1; i < labels.length; i++) labels[i].y = Math.max(labels[i].y, labels[i - 1].y + 14)

  const onMove = (e: PointerEvent<SVGRectElement>) => {
    const box = e.currentTarget.getBoundingClientRect()
    const t = t0 + ((e.clientX - box.left) / box.width) * (t1 - t0)
    let best = 0
    readings.forEach((r, i) => {
      if (Math.abs(r.dayTimeMs! - t) < Math.abs(readings[best].dayTimeMs! - t)) best = i
    })
    setHover(best)
  }
  const at = hover == null ? null : readings[hover]
  const tipLeft = at ? x(at.dayTimeMs!) : 0

  return (
    <div className="an-chart an-gaps wx-chart" ref={wrap}>
      <svg
        width={width}
        height={HEIGHT}
        role="img"
        aria-label="Track and air temperature over the session. Every reading is in the table below."
      >
        {yTicks.map((v) => (
          <g key={v}>
            <line className="an-grid" x1={PAD.left} x2={PAD.left + plotW} y1={y(v)} y2={y(v)} />
            <text className="an-tick" x={PAD.left - 8} y={y(v)} textAnchor="end" dominantBaseline="middle">
              {v}°
            </text>
          </g>
        ))}
        {xTicks.map((t) => (
          <text key={t} className="an-tick" x={x(t)} y={HEIGHT - 8} textAnchor="middle">
            {hm(t, utcOffsetHours)}
          </text>
        ))}
        {series.map((s) => (
          <path key={s.key} className={`an-line an-s${s.slot}`} d={path(s)} />
        ))}
        {labels.map((l) => (
          <g key={l.s.key} className={`an-label an-s${l.s.slot}`}>
            <circle cx={l.x + 10} cy={l.y} r={4} />
            <text x={l.x + 18} y={l.y} dominantBaseline="middle">
              {l.s.label}
            </text>
          </g>
        ))}
        {at && <line className="an-cross" x1={tipLeft} x2={tipLeft} y1={PAD.top} y2={PAD.top + plotH} />}
        <rect
          className="an-hit"
          x={PAD.left}
          y={PAD.top}
          width={plotW}
          height={plotH}
          onPointerMove={onMove}
          onPointerLeave={() => setHover(null)}
        />
      </svg>
      {at && (
        <div
          className="an-tip"
          style={{ left: tipLeft, transform: tipLeft > width * 0.6 ? 'translateX(calc(-100% - 12px))' : 'translateX(12px)' }}
          role="status"
        >
          <div className="an-tip-lap">{messageTime(at.dayTimeMs, utcOffsetHours)}</div>
          {series.map((s) => {
            const v = s.at(at)
            return v == null ? null : (
              <div key={s.key} className={`an-tip-row an-s${s.slot}`}>
                <i aria-hidden="true" />
                <span>{s.label}</span>
                <span className="num">{v.toFixed(1)}°</span>
              </div>
            )
          })}
        </div>
      )}
    </div>
  )
}
