import { useEffect, useMemo, useRef, useState, type CSSProperties } from 'react'
import { useLivePoll } from '../lib/useLivePoll'
import {
  driverName,
  duration,
  lapTime,
  pct,
  ratingName,
  type CarDetail,
  type LapRow,
  type SectorsResponse,
} from '../lib/liveTiming'
import './live-car-modal.css'

/**
 * One car's session from the timing page: every lap (newest first — live,
 * the lap that matters is the one just done) and every stint. Polls every
 * 10 s while open, which is the only time anyone reads it; the tower keeps
 * polling underneath.
 *
 * A native <dialog>, like the starting-grid modal: the one surface on the
 * timing page allowed to scroll inside itself.
 */
export default function LiveCarModal({
  carNumber,
  teamName,
  className,
  classColor,
  sessionDbId,
  onClose,
}: {
  carNumber: string
  teamName: string | null
  className: string
  classColor: string | null
  sessionDbId: number | null
  onClose: () => void
}) {
  const dialogRef = useRef<HTMLDialogElement>(null)
  const [view, setView] = useState<'laps' | 'stints'>('laps')
  const path = `/api/live/cars/${encodeURIComponent(carNumber)}${sessionDbId != null ? `?session=${sessionDbId}` : ''}`
  const { value: car, error } = useLivePoll<CarDetail>(path, 10_000)
  // The class's best sectors, to mark this car's that match them (a 304 between laps).
  const { value: sectors } = useLivePoll<SectorsResponse>(
    `/api/live/sectors${sessionDbId != null ? `?session=${sessionDbId}` : ''}`,
    10_000,
  )
  const classBest = useMemo(() => {
    // Optional chaining all the way: a failed or odd answer must not take the panel down with it.
    const cls = sectors?.classes?.find((c) => c.bests?.cars?.some((x) => x.carNumber === carNumber))
    return cls?.bests.classBestSectorMs ?? []
  }, [sectors, carNumber])

  useEffect(() => {
    const d = dialogRef.current
    if (d && !d.open) d.showModal()
  }, [])

  const drivers = useMemo(() => new Map((car?.drivers ?? []).map((d) => [d.driverOrder, d])), [car])
  const short = (order: number | null) => {
    if (order == null) return '—'
    const d = drivers.get(order)
    // Surnames, not the feed's three-letter codes ("Ilo"): there is room, and a
    // code has to be decoded mid-broadcast.
    return d?.lastName ?? d?.shortName ?? `Driver ${order}`
  }

  return (
    <dialog
      className="lc no-print"
      ref={dialogRef}
      aria-label={`Car ${carNumber} laps and stints`}
      onCancel={(e) => {
        e.preventDefault()
        onClose()
      }}
      onClick={(e) => {
        if (e.target === dialogRef.current) onClose()
      }}
    >
      <header className="lc-head">
        <div className="lc-id">
          <h2 className="lc-title">
            <span className="class-tag" style={{ '--class-color': classColor ?? undefined } as CSSProperties}>
              {className}
            </span>
            <span className="lc-num">#{carNumber}</span>
            {teamName && <span className="lc-team">{teamName}</span>}
          </h2>
          {car && car.drivers.length > 0 && (
            <ol className="lc-drivers" aria-label="Crew">
              {car.drivers.map((d) => (
                <li key={d.driverOrder}>
                  <span className="lc-order">{d.driverOrder}</span>
                  {driverName(d)}
                  {d.rating && (
                    <span className="lc-rating" title={ratingName(d.rating) ?? undefined}>
                      {d.rating}
                    </span>
                  )}
                </li>
              ))}
            </ol>
          )}
        </div>
        <button className="btn lc-close" onClick={onClose} aria-label="Close">
          Close
        </button>
      </header>

      <div className="lc-bar">
        <div className="seg" role="tablist" aria-label="Car detail">
          {(['laps', 'stints'] as const).map((v) => (
            <button
              key={v}
              role="tab"
              aria-selected={view === v}
              className={`seg-btn${view === v ? ' active' : ''}`}
              onClick={() => setView(v)}
            >
              {v === 'laps' ? `Laps${car ? ` (${car.laps.length})` : ''}` : `Stints${car ? ` (${car.stints.length})` : ''}`}
            </button>
          ))}
        </div>
        {error && car && <span className="lc-stale">Not updating: {error}</span>}
      </div>

      <div className="lc-body">
        {!car && error ? (
          <div className="empty-state">
            {error.includes('404')
              ? 'No laps recorded for this car in this session yet.'
              : `Could not load this car: ${error}`}
          </div>
        ) : !car ? (
          <div className="skeleton-block" aria-label="Loading laps">
            <span className="skeleton" />
            <span className="skeleton" />
            <span className="skeleton" />
          </div>
        ) : view === 'laps' ? (
          <LapTable laps={car.laps} driverLabel={short} classBest={classBest} />
        ) : (
          <StintTable car={car} driverLabel={short} />
        )}
      </div>
    </dialog>
  )
}

/**
 * Sector cells: the car's own best sector (valid laps only) in weight, the
 * class's best on the violet tint — the tower's personal-best / fastest-in-
 * class vocabulary. A sector run under a flag keeps its flag tint.
 */
function LapTable({
  laps,
  driverLabel,
  classBest,
}: {
  laps: LapRow[]
  driverLabel: (order: number | null) => string
  classBest: (number | null)[]
}) {
  if (laps.length === 0) return <div className="empty-state">No laps recorded yet.</div>
  const sectors = Math.max(0, ...laps.map((l) => l.sectorMs?.length ?? 0))
  let best: number | null = null
  const ownBest: (number | null)[] = Array.from({ length: sectors }, () => null)
  for (const l of laps) {
    if (l.valid === false) continue
    if (l.lapTimeMs != null && l.lapTimeMs > 0 && (best == null || l.lapTimeMs < best)) best = l.lapTimeMs
    l.sectorMs?.forEach((ms, i) => {
      if (ms != null && ms > 0 && (ownBest[i] == null || ms < ownBest[i]!)) ownBest[i] = ms
    })
  }
  const theoretical = ownBest.length > 0 && ownBest.every((v) => v != null) ? ownBest.reduce<number>((a, v) => a + v!, 0) : null
  const newestFirst = [...laps].reverse()
  const hasEnergy = laps.some((l) => l.energyPct != null)
  return (
    <table className="grid-table lc-table">
      {theoretical != null && best != null && (
        <caption className="lc-caption">
          Best lap {lapTime(best)} · theoretical best {lapTime(theoretical)}
          {best > theoretical && <span className="muted"> ({lapTime(best - theoretical)} in hand)</span>}
        </caption>
      )}
      <thead>
        <tr>
          <th className="num">Lap</th>
          <th>Driver</th>
          <th className="num">Time</th>
          {Array.from({ length: sectors }, (_, i) => (
            <th key={i} className="num">
              S{i + 1}
            </th>
          ))}
          <th className="num">Pos</th>
          <th className="num">Top speed</th>
          {hasEnergy && (
            <>
              <th className="num" title="Energy remaining at the line after the lap (IMSA telemetry)">
                Energy
              </th>
              <th className="num" title="Energy used on the lap">
                Used
              </th>
            </>
          )}
          <th>Notes</th>
        </tr>
      </thead>
      <tbody>
        {newestFirst.map((l) => {
          const isBest = best != null && l.lapTimeMs === best && l.valid !== false
          const notes = [
            l.pitInMs != null ? 'Pit in' : null,
            l.pitOutMs != null ? 'Pit out' : null,
            l.valid === false ? 'Invalid' : null,
            l.trackLimits ? `Track limits${l.trackLimits > 1 ? ` ×${l.trackLimits}` : ''}` : null,
          ].filter(Boolean)
          return (
            <tr key={l.lap} className={l.valid === false ? 'lc-invalid' : undefined}>
              <td className="num">{l.lap}</td>
              <td>{driverLabel(l.driverOrder)}</td>
              <td className={`num${isBest ? ' lc-best' : ''}`}>
                {l.lapTimeMs == null ? <span className="muted">In progress</span> : lapTime(l.lapTimeMs)}
                {isBest && <span className="sr-only"> (best lap)</span>}
              </td>
              {Array.from({ length: sectors }, (_, i) => {
                const flag = l.sectorFlags?.[i] ?? null
                const ms = l.sectorMs?.[i] ?? null
                const counts = ms != null && l.valid !== false
                const isClassBest = counts && ms === classBest[i]
                const isOwnBest = counts && !isClassBest && ms === ownBest[i]
                const tone =
                  (flag && !/green/i.test(flag) ? ' lc-sector-flag' : '') +
                  (isClassBest ? ' lc-sector-class-best' : isOwnBest ? ' lc-sector-pb' : '')
                const note = isClassBest ? 'Fastest in class' : isOwnBest ? 'Personal best' : null
                return (
                  <td key={i} className={`num${tone}`} title={[flag, note].filter(Boolean).join(' · ') || undefined}>
                    {lapTime(ms)}
                    {note && <span className="sr-only"> ({note.toLowerCase()})</span>}
                  </td>
                )
              })}
              <td className="num">{l.position ?? ''}</td>
              <td className="num">{l.topSpeed != null ? l.topSpeed.toFixed(1) : ''}</td>
              {hasEnergy && (
                <>
                  <td className="num">{pct(l.energyPct)}</td>
                  <td className="num">{pct(l.energyUsedPct)}</td>
                </>
              )}
              <td className="lc-notes">{notes.join(' · ')}</td>
            </tr>
          )
        })}
      </tbody>
    </table>
  )
}

function StintTable({ car, driverLabel }: { car: CarDetail; driverLabel: (order: number | null) => string }) {
  if (car.stints.length === 0) return <div className="empty-state">No stints recorded yet.</div>
  const newestFirst = [...car.stints].reverse()
  const hasEnergy = car.stints.some((s) => s.avgEnergyPerLapPct != null)
  const clock = (ms: number) =>
    new Date(ms).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false })
  return (
    <table className="grid-table lc-table">
      <thead>
        <tr>
          <th>Driver</th>
          <th>Type</th>
          <th className="num">Laps</th>
          <th className="num">Started</th>
          <th className="num">Length</th>
          <th className="num" title="The driver's track time in the session after this stint (pit lane excluded)">
            Driver track time
          </th>
          {hasEnergy && (
            <th className="num" title="Average energy used per lap in the stint (IMSA telemetry)">
              Energy / lap
            </th>
          )}
        </tr>
      </thead>
      <tbody>
        {newestFirst.map((s) => {
          const open = s.finishTimeMs == null || s.finishTimeMs <= 0
          const laps =
            s.openLap != null && s.closeLap != null
              ? `${s.openLap}–${s.closeLap}`
              : s.openLap != null
                ? `${s.openLap}–`
                : ''
          const type = (s.type ?? 'TRACK').toUpperCase() === 'PIT' ? 'Pit' : 'Track'
          return (
            <tr key={s.startTimeMs}>
              <td>{driverLabel(s.driverOrder)}</td>
              <td>
                {type}
                {s.pitType && <span className="muted"> · {s.pitType.toLowerCase()}</span>}
                {open && <span className="lc-open">Current</span>}
              </td>
              <td className="num">{laps}</td>
              <td className="num">{clock(s.startTimeMs)}</td>
              <td className="num">{open ? '' : duration(s.finishTimeMs! - s.startTimeMs)}</td>
              <td className="num">{duration(s.driverAccumSessionTrackMs)}</td>
              {hasEnergy && <td className="num">{pct(s.avgEnergyPerLapPct, 2)}</td>}
            </tr>
          )
        })}
      </tbody>
    </table>
  )
}
