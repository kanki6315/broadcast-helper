import { Fragment, useEffect, useMemo, useRef, useState, type CSSProperties, type PointerEvent, type RefObject } from 'react'
import { useLivePoll } from '../lib/useLivePoll'
import {
  duration,
  gapAt,
  lapTime,
  cautionLapsLabel,
  energyLeftOut,
  marginLabel,
  type AnalysisCar,
  type EnergyAverage,
  type EnergyFinishInfo,
  type EnergyResponse,
  type GapCar,
  type GapClass,
  type GapsResponse,
  type PitCar,
  type PitsResponse,
  type SectorsResponse,
  type SessionSummary,
} from '../lib/liveTiming'
import LiveCarModal from '../components/LiveCarModal'

/**
 * The timing page's analysis views — gap to the class leader lap by lap,
 * best sectors, pit stops and energy use — over one recorded session. All three are
 * recomputed by the backend from the stored laps and stints on every poll
 * (see LiveAnalysis.java), so they fill in as the session runs and correct
 * themselves when the feed corrects a lap.
 */

type OpenCar = { car: AnalysisCar; color: string | null }

function useOpenCar(session: SessionSummary) {
  const [open, setOpen] = useState<OpenCar | null>(null)
  const modal = open && (
    <LiveCarModal
      carNumber={open.car.carNumber}
      teamName={open.car.teamName}
      className={open.car.className}
      classColor={open.color}
      sessionDbId={session.sessionDbId}
      onClose={() => setOpen(null)}
    />
  )
  return { open: (car: AnalysisCar, color: string | null) => setOpen({ car, color }), modal }
}

function Loading({ label, error }: { label: string; error: string | null }) {
  return error ? (
    <div className="error-panel">Could not load {label}: {error}</div>
  ) : (
    <div className="skeleton-block" aria-label={`Loading ${label}`}>
      <span className="skeleton" />
      <span className="skeleton" />
      <span className="skeleton" />
    </div>
  )
}

const pollEvery = (s: SessionSummary) => (s.current ? 15_000 : 120_000)

function CarButton({ car, onOpen }: { car: AnalysisCar; onOpen: () => void }) {
  return (
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
  )
}

// ---- gaps -----------------------------------------------------------------------------

/** Categorical slots 1–8 (validated adjacent-pairs in both themes); more cars stay grey context. */
const SLOTS = 8
/** Followed on first open: the class's top three, so the chart starts uncluttered. */
const DEFAULT_PICKS = 3

export function GapsView({ session }: { session: SessionSummary }) {
  const { value, error } = useLivePoll<GapsResponse>(`/api/live/gaps?session=${session.sessionDbId}`, pollEvery(session))
  const [className, setClassName] = useState<string | null>(null)
  const { open, modal } = useOpenCar(session)
  if (!value) return <Loading label="gaps" error={error} />
  const classes = value.classes.filter((c) => c.gaps.some((g) => g.gapMs.length > 0))
  if (classes.length === 0) return <div className="empty-state">No timed laps recorded for this session yet.</div>
  const cls = classes.find((c) => c.className === className) ?? classes[0]
  return (
    <>
      {error && <p className="timing-stale">Not updating: {error}</p>}
      {classes.length > 1 && (
        <div className="seg an-classes" role="group" aria-label="Class">
          {classes.map((c) => (
            <button
              key={c.className}
              className={`seg-btn${c === cls ? ' active' : ''}`}
              aria-pressed={c === cls}
              onClick={() => setClassName(c.className)}
            >
              {c.className}
            </button>
          ))}
        </div>
      )}
      <GapPanel key={cls.className} cls={cls} onOpen={(car) => open(car, cls.color)} />
      {modal}
    </>
  )
}

function GapPanel({ cls, onOpen }: { cls: GapClass; onOpen: (car: AnalysisCar) => void }) {
  const timed = cls.gaps.filter((g) => g.gapMs.length > 0)
  // Picked cars keep their colour slot until unpicked: colour follows the car, not its rank.
  const [picked, setPicked] = useState<{ car: string; slot: number }[]>(() =>
    timed.slice(0, DEFAULT_PICKS).map((g, i) => ({ car: g.carNumber, slot: i })),
  )
  const [asTable, setAsTable] = useState(false)
  const info = new Map(cls.cars.map((c) => [c.carNumber, c]))
  const toggle = (car: string) =>
    setPicked((p) => {
      if (p.some((x) => x.car === car)) return p.filter((x) => x.car !== car)
      const kept = p.length >= SLOTS ? p.slice(1) : p
      const used = new Set(kept.map((x) => x.slot))
      const slot = [...Array(SLOTS).keys()].find((s) => !used.has(s)) ?? 0
      return [...kept, { car, slot }]
    })
  const slotOf = new Map(picked.map((p) => [p.car, p.slot]))

  return (
    <section className="an-gaps" aria-label={`${cls.className} gap to the class leader`}>
      <div className="an-gap-bar">
        <p className="an-hint">
          Gap to the {cls.className} leader after each lap. Pick up to eight cars to follow; the rest stay in grey.
        </p>
        <div className="seg" role="group" aria-label="Show as">
          <button className={`seg-btn${!asTable ? ' active' : ''}`} aria-pressed={!asTable} onClick={() => setAsTable(false)}>
            Chart
          </button>
          <button className={`seg-btn${asTable ? ' active' : ''}`} aria-pressed={asTable} onClick={() => setAsTable(true)}>
            Table
          </button>
        </div>
      </div>
      <div className="an-picks" role="group" aria-label="Cars to follow">
        {timed.map((g) => {
          const slot = slotOf.get(g.carNumber)
          return (
            <button
              key={g.carNumber}
              className={`an-pick${slot != null ? ` an-pick--on an-s${slot + 1}` : ''}`}
              aria-pressed={slot != null}
              title={info.get(g.carNumber)?.teamName ?? undefined}
              onClick={() => toggle(g.carNumber)}
            >
              <i aria-hidden="true" />#{g.carNumber}
            </button>
          )
        })}
      </div>
      {asTable ? (
        <GapTable cls={cls} picked={picked} onOpen={onOpen} />
      ) : (
        <GapChart gaps={timed} picked={picked} />
      )}
    </section>
  )
}

const HEIGHT = 340
const PAD = { top: 12, right: 56, bottom: 28, left: 56 }

/** Rounds up to a tidy axis maximum (seconds): 1, 2, 5 × 10^n. */
function niceCeil(v: number): number {
  if (v <= 0) return 1
  const p = 10 ** Math.floor(Math.log10(v))
  for (const m of [1, 2, 5, 10]) if (v <= m * p) return m * p
  return 10 * p
}

function useWidth<T extends HTMLElement>(): [RefObject<T | null>, number] {
  const ref = useRef<T>(null)
  const [width, setWidth] = useState(800)
  useEffect(() => {
    const el = ref.current
    if (!el) return
    const ro = new ResizeObserver(([e]) => setWidth(Math.max(320, Math.floor(e.contentRect.width))))
    ro.observe(el)
    return () => ro.disconnect()
  }, [])
  return [ref, width]
}

/** A car's line as path segments, broken wherever it was lapped or a lap went untimed. */
function linePath(g: GapCar, x: (lap: number) => number, y: (s: number) => number): string {
  let d = ''
  let pen = false
  g.gapMs.forEach((ms, i) => {
    if (ms == null) {
      pen = false
      return
    }
    d += `${pen ? 'L' : 'M'}${x(g.firstLap + i).toFixed(1)},${y(ms / 1000).toFixed(1)}`
    pen = true
  })
  return d
}

function GapChart({ gaps, picked }: { gaps: GapCar[]; picked: { car: string; slot: number }[] }) {
  const [wrap, width] = useWidth<HTMLDivElement>()
  const [hoverLap, setHoverLap] = useState<number | null>(null)
  const byCar = useMemo(() => new Map(gaps.map((g) => [g.carNumber, g])), [gaps])
  const focus = picked.map((p) => ({ ...p, g: byCar.get(p.car) })).filter((p): p is typeof p & { g: GapCar } => !!p.g)

  const lastLap = Math.max(1, ...gaps.map((g) => g.firstLap + g.gapMs.length - 1))
  const firstLap = Math.min(...gaps.map((g) => g.firstLap))
  // Scale to the followed cars, so one car a minute down after a long stop
  // does not flatten the fight; grey lines past the edge are clipped.
  const scaleOn = focus.length > 0 ? focus.map((p) => p.g) : gaps
  const maxGap = Math.max(0, ...scaleOn.flatMap((g) => g.gapMs.filter((v): v is number => v != null).map((v) => v / 1000)))
  const yMax = niceCeil(maxGap * 1.05)

  const plotW = width - PAD.left - PAD.right
  const plotH = HEIGHT - PAD.top - PAD.bottom
  const x = (lap: number) => PAD.left + (lastLap === firstLap ? plotW / 2 : ((lap - firstLap) / (lastLap - firstLap)) * plotW)
  const y = (s: number) => PAD.top + (s / yMax) * plotH // the leader at the top, further behind further down

  const yTicks = [0, 0.25, 0.5, 0.75, 1].map((f) => f * yMax)
  const lapStep = niceCeil(Math.max(1, (lastLap - firstLap) / Math.max(2, Math.floor(plotW / 90))))
  const xTicks: number[] = []
  for (let l = Math.ceil(firstLap / lapStep) * lapStep; l <= lastLap; l += lapStep) xTicks.push(Math.max(l, firstLap))

  // Direct labels at each followed line's last point, nudged apart so they never overlap.
  const labels = focus
    .map((p) => {
      let i = p.g.gapMs.length - 1
      while (i >= 0 && p.g.gapMs[i] == null) i--
      return i < 0 ? null : { ...p, lap: p.g.firstLap + i, y: y(Math.min(yMax, p.g.gapMs[i]! / 1000)) }
    })
    .filter((l): l is NonNullable<typeof l> => l != null)
    .sort((a, b) => a.y - b.y)
  for (let i = 1; i < labels.length; i++) labels[i].y = Math.max(labels[i].y, labels[i - 1].y + 14)
  // Then back up from the bottom edge, so a bunch of trailing cars never spills under the axis.
  for (let i = labels.length - 1; i >= 0; i--)
    labels[i].y = Math.min(labels[i].y, i === labels.length - 1 ? PAD.top + plotH : labels[i + 1].y - 14)

  const onMove = (e: PointerEvent<SVGRectElement>) => {
    const box = e.currentTarget.getBoundingClientRect()
    const f = (e.clientX - box.left) / box.width
    setHoverLap(Math.round(firstLap + f * (lastLap - firstLap)))
  }
  const tipRows =
    hoverLap == null
      ? []
      : focus
          .map((p) => ({ ...p, text: gapAt(p.g, hoverLap), pit: p.g.pitLaps.includes(hoverLap) }))
          .filter((r) => r.text != null)
  const tipLeft = hoverLap == null ? 0 : x(hoverLap)

  return (
    <div className="an-chart" ref={wrap}>
      <svg width={width} height={HEIGHT} role="img" aria-label="Gap to the class leader by lap. The table view has the numbers.">
        <defs>
          <clipPath id="an-plot">
            <rect x={PAD.left} y={PAD.top - 2} width={plotW} height={plotH + 4} />
          </clipPath>
        </defs>
        {yTicks.map((t) => (
          <g key={t}>
            <line className="an-grid" x1={PAD.left} x2={PAD.left + plotW} y1={y(t)} y2={y(t)} />
            <text className="an-tick" x={PAD.left - 8} y={y(t)} textAnchor="end" dominantBaseline="middle">
              {t === 0 ? 'Leader' : `+${t % 1 === 0 ? t : t.toFixed(1)}s`}
            </text>
          </g>
        ))}
        {xTicks.map((l) => (
          <text key={l} className="an-tick" x={x(l)} y={HEIGHT - 8} textAnchor="middle">
            {l}
          </text>
        ))}
        <text className="an-tick" x={PAD.left + plotW} y={HEIGHT - 8} textAnchor="start" dx={8}>
          Lap
        </text>
        <g clipPath="url(#an-plot)">
          {gaps
            .filter((g) => !focus.some((p) => p.car === g.carNumber))
            .map((g) => (
              <path key={g.carNumber} className="an-context" d={linePath(g, x, y)} />
            ))}
          {focus.map((p) => (
            <path key={p.car} className={`an-line an-s${p.slot + 1}`} d={linePath(p.g, x, y)} />
          ))}
          {focus.flatMap((p) =>
            p.g.pitLaps.map((lap) => {
              const ms = p.g.gapMs[lap - p.g.firstLap]
              return ms == null ? null : (
                <circle key={`${p.car}-${lap}`} className={`an-pit an-s${p.slot + 1}`} cx={x(lap)} cy={y(ms / 1000)} r={4} />
              )
            }),
          )}
        </g>
        {labels.map((l) => (
          <g key={l.car} className={`an-label an-s${l.slot + 1}`}>
            <circle cx={x(l.lap) + 10} cy={l.y} r={4} />
            <text x={x(l.lap) + 18} y={l.y} dominantBaseline="middle">
              #{l.car}
            </text>
          </g>
        ))}
        {hoverLap != null && <line className="an-cross" x1={tipLeft} x2={tipLeft} y1={PAD.top} y2={PAD.top + plotH} />}
        <rect
          className="an-hit"
          x={PAD.left}
          y={PAD.top}
          width={plotW}
          height={plotH}
          onPointerMove={onMove}
          onPointerLeave={() => setHoverLap(null)}
        />
      </svg>
      {hoverLap != null && tipRows.length > 0 && (
        <div
          className="an-tip"
          style={{ left: tipLeft, transform: tipLeft > width * 0.6 ? 'translateX(calc(-100% - 12px))' : 'translateX(12px)' }}
          role="status"
        >
          <div className="an-tip-lap">Lap {hoverLap}</div>
          {tipRows
            .sort((a, b) => (a.g.gapMs[hoverLap - a.g.firstLap] ?? Infinity) - (b.g.gapMs[hoverLap - b.g.firstLap] ?? Infinity))
            .map((r) => (
              <div key={r.car} className={`an-tip-row an-s${r.slot + 1}`}>
                <i aria-hidden="true" />
                <span>#{r.car}</span>
                <span className="num">{r.text}</span>
                {r.pit && <span className="an-tip-pit">Pit</span>}
              </div>
            ))}
        </div>
      )}
      <p className="an-legend-note">
        <svg width="10" height="10" aria-hidden="true">
          <circle cx="5" cy="5" r="4" className="an-pit an-legend-pit" />
        </svg>{' '}
        Pit stop, on the lap the car came in. A line breaks where the car was a lap or more down.
      </p>
    </div>
  )
}

function GapTable({
  cls,
  picked,
  onOpen,
}: {
  cls: GapClass
  picked: { car: string; slot: number }[]
  onOpen: (car: AnalysisCar) => void
}) {
  const cars = (picked.length ? picked.map((p) => p.car) : cls.gaps.slice(0, DEFAULT_PICKS).map((g) => g.carNumber))
    .map((c) => cls.gaps.find((g) => g.carNumber === c))
    .filter((g): g is GapCar => !!g)
  const info = new Map(cls.cars.map((c) => [c.carNumber, c]))
  const lastLap = Math.max(0, ...cars.map((g) => g.firstLap + g.gapMs.length - 1))
  const firstLap = Math.min(...cars.map((g) => g.firstLap))
  const laps: number[] = []
  for (let l = lastLap; l >= firstLap; l--) laps.push(l)
  return (
    <table className="grid-table tower an-table" aria-label="Gap to the class leader by lap">
      <thead>
        <tr>
          <th className="num" scope="col">
            Lap
          </th>
          {cars.map((g) => {
            const car = info.get(g.carNumber) ?? { carNumber: g.carNumber, teamName: null, className: cls.className }
            return (
              <th key={g.carNumber} className="num" scope="col">
                <CarButton car={car} onOpen={() => onOpen(car)} />
              </th>
            )
          })}
        </tr>
      </thead>
      <tbody>
        {laps.map((lap) => (
          <tr key={lap}>
            <td className="num">{lap}</td>
            {cars.map((g) => (
              <td key={g.carNumber} className="num">
                {gapAt(g, lap) ?? ''}
                {g.pitLaps.includes(lap) && <span className="an-cell-pit">Pit</span>}
              </td>
            ))}
          </tr>
        ))}
      </tbody>
    </table>
  )
}

// ---- sectors --------------------------------------------------------------------------

export function SectorsView({ session }: { session: SessionSummary }) {
  const { value, error } = useLivePoll<SectorsResponse>(`/api/live/sectors?session=${session.sessionDbId}`, pollEvery(session))
  const { open, modal } = useOpenCar(session)
  if (!value) return <Loading label="sectors" error={error} />
  const classes = value.classes.filter((c) => c.bests.sectors > 0)
  if (classes.length === 0) return <div className="empty-state">No sector times recorded for this session yet.</div>
  const sectors = Math.max(...classes.map((c) => c.bests.sectors))
  const columns = 5 + sectors
  return (
    <>
      {error && <p className="timing-stale">Not updating: {error}</p>}
      <table className="grid-table tower" aria-label="Best sectors by class">
        <thead>
          <tr>
            <th className="num" scope="col">
              #
            </th>
            <th scope="col">Team</th>
            {Array.from({ length: sectors }, (_, i) => (
              <th key={i} className="num" scope="col" title="Best valid time in the sector">
                S{i + 1}
              </th>
            ))}
            <th className="num" scope="col" title="The car's best sectors added up">
              Theoretical
            </th>
            <th className="num" scope="col">
              Best lap
            </th>
            <th className="num" scope="col" title="Best lap minus the theoretical best: time left on the table">
              Left
            </th>
          </tr>
        </thead>
        {classes.map((cls) => {
          const info = new Map(cls.cars.map((c) => [c.carNumber, c]))
          const classTheoretical = cls.bests.classBestSectorMs.every((v) => v != null)
            ? cls.bests.classBestSectorMs.reduce<number>((a, v) => a + (v ?? 0), 0)
            : null
          const bestTheoretical = Math.min(...cls.bests.cars.map((c) => c.theoreticalMs ?? Infinity))
          const bestLap = Math.min(...cls.bests.cars.map((c) => c.bestLapMs ?? Infinity))
          return (
            <tbody key={cls.className} style={{ '--class-color': cls.color ?? undefined } as CSSProperties}>
              <tr className="class-band">
                <td colSpan={columns}>
                  <span className="band-label">{cls.className}</span>
                </td>
              </tr>
              <tr className="an-ideal">
                <td />
                <td>Best in class</td>
                {Array.from({ length: sectors }, (_, i) => (
                  <td key={i} className="num">
                    {lapTime(cls.bests.classBestSectorMs[i] ?? null)}
                  </td>
                ))}
                <td className="num" title="Every class-best sector added up">
                  {lapTime(classTheoretical)}
                </td>
                <td className="num">{lapTime(Number.isFinite(bestLap) ? bestLap : null)}</td>
                <td />
              </tr>
              {cls.bests.cars.map((c) => {
                const car = info.get(c.carNumber) ?? { carNumber: c.carNumber, teamName: null, className: cls.className }
                const left = c.bestLapMs != null && c.theoreticalMs != null ? c.bestLapMs - c.theoreticalMs : null
                return (
                  <tr key={c.carNumber} className="tower-row" onClick={() => open(car, cls.color)}>
                    <td className="num tower-car">
                      <CarButton car={car} onOpen={() => open(car, cls.color)} />
                    </td>
                    <td className="tower-team">{car.teamName ?? <span className="muted">—</span>}</td>
                    {Array.from({ length: sectors }, (_, i) => {
                      const ms = c.bestSectorMs[i] ?? null
                      const isBest = ms != null && ms === cls.bests.classBestSectorMs[i]
                      return (
                        <td
                          key={i}
                          className={`num${isBest ? ' tower-class-best' : ''}`}
                          title={c.bestSectorLap[i] != null ? `Lap ${c.bestSectorLap[i]}${isBest ? ' · fastest in class' : ''}` : undefined}
                        >
                          {lapTime(ms)}
                          {isBest && <span className="sr-only"> (fastest in class)</span>}
                        </td>
                      )
                    })}
                    <td className={`num${c.theoreticalMs != null && c.theoreticalMs === bestTheoretical ? ' tower-pb' : ''}`}>
                      {lapTime(c.theoreticalMs)}
                    </td>
                    <td className={`num${c.bestLapMs != null && c.bestLapMs === bestLap ? ' tower-class-best' : ''}`} title={c.bestLap != null ? `Lap ${c.bestLap}` : undefined}>
                      {lapTime(c.bestLapMs)}
                    </td>
                    <td className="num muted">{left != null && left > 0 ? `+${lapTime(left)}` : ''}</td>
                  </tr>
                )
              })}
            </tbody>
          )
        })}
      </table>
      <p className="timing-foot an-foot">
        Invalid laps (track limits) count for nothing. Fastest in class on the violet tint; hover a time for its lap.
      </p>
      {modal}
    </>
  )
}

// ---- pits -----------------------------------------------------------------------------

export function PitsView({ session }: { session: SessionSummary }) {
  const { value, error } = useLivePoll<PitsResponse>(`/api/live/pits?session=${session.sessionDbId}`, pollEvery(session))
  const { open, modal } = useOpenCar(session)
  const [expanded, setExpanded] = useState<Set<string>>(new Set())
  if (!value) return <Loading label="pit stops" error={error} />
  const classes = value.classes.filter((c) => c.pits.length > 0)
  if (classes.length === 0 || classes.every((c) => c.pits.every((p) => p.stops.length === 0))) {
    return <div className="empty-state">No pit stops recorded for this session yet.</div>
  }
  const name = (car: string, order: number | null) =>
    order == null ? null : (value.drivers[car]?.[String(order)] ?? `Driver ${order}`)
  const toggle = (car: string) =>
    setExpanded((s) => {
      const next = new Set(s)
      if (next.has(car)) next.delete(car)
      else next.add(car)
      return next
    })
  const columns = 9
  return (
    <>
      {error && <p className="timing-stale">Not updating: {error}</p>}
      <table className="grid-table tower an-pits" aria-label="Pit stops by class">
        <thead>
          <tr>
            <th className="num" scope="col">
              #
            </th>
            <th scope="col">Team</th>
            <th className="num" scope="col">
              Stops
            </th>
            <th className="num" scope="col" title="Pit-lane time over every finished stop">
              Pit lane
            </th>
            <th className="num" scope="col">
              Average
            </th>
            <th className="num" scope="col">
              Last stop
            </th>
            <th scope="col">Driver change</th>
            <th className="num" scope="col" title="Laps completed since the last stop">
              Since
            </th>
            <th scope="col">
              <span className="sr-only">Stop list</span>
            </th>
          </tr>
        </thead>
        {classes.map((cls) => {
          const info = new Map(cls.cars.map((c) => [c.carNumber, c]))
          return (
            <tbody key={cls.className} style={{ '--class-color': cls.color ?? undefined } as CSSProperties}>
              <tr className="class-band">
                <td colSpan={columns}>
                  <span className="band-label">{cls.className}</span>
                </td>
              </tr>
              {cls.pits.map((p) => {
                const car = info.get(p.carNumber) ?? { carNumber: p.carNumber, teamName: null, className: cls.className }
                const last = p.stops.at(-1)
                const isOpen = expanded.has(p.carNumber)
                return (
                  <Fragment key={p.carNumber}>
                    <tr className="tower-row" onClick={() => open(car, cls.color)}>
                      <td className="num tower-car">
                        <CarButton car={car} onOpen={() => open(car, cls.color)} />
                      </td>
                      <td className="tower-team">{car.teamName ?? <span className="muted">—</span>}</td>
                      <td className="num">{p.stops.length}</td>
                      <td className="num">{p.stops.length ? duration(p.totalMs) : ''}</td>
                      <td className="num">{p.averageMs != null ? duration(p.averageMs) : ''}</td>
                      <td className="num">
                        {last && <LastStop stop={last} />}
                      </td>
                      <td>{last && <Change p={last} car={p.carNumber} name={name} />}</td>
                      <td className="num">
                        {p.inPit ? <span className="tower-pit-mark">Pit</span> : p.lapsSinceStop != null ? `${p.lapsSinceStop} L` : ''}
                      </td>
                      <td>
                        {p.stops.length > 1 && (
                          <button
                            className="btn an-expand"
                            aria-expanded={isOpen}
                            onClick={(e) => {
                              e.stopPropagation()
                              toggle(p.carNumber)
                            }}
                          >
                            {isOpen ? 'Hide stops' : `All ${p.stops.length}`}
                          </button>
                        )}
                      </td>
                    </tr>
                    {isOpen && <StopList pit={p} name={name} />}
                  </Fragment>
                )
              })}
            </tbody>
          )
        })}
      </table>
      <p className="timing-foot an-foot">
        Stops count as on Al Kamel's tower: leaving the garage at the start is not one, and a stop a red flag
        interrupts is one. Pit-lane time is entry to exit. Penalty and safety-car stops are marked.
      </p>
      {modal}
    </>
  )
}

function LastStop({ stop }: { stop: PitCar['stops'][number] }) {
  return (
    <span className="tower-pair">
      {stop.lap != null && <span className="muted">L{stop.lap}</span>}
      <span>{stop.durationMs != null ? duration(stop.durationMs) : 'In pit'}</span>
      {stop.pitType && <span className="an-pit-type">{stop.pitType.toLowerCase().replace(/_/g, ' ')}</span>}
    </span>
  )
}

function Change({
  p,
  car,
  name,
}: {
  p: PitCar['stops'][number]
  car: string
  name: (car: string, order: number | null) => string | null
}) {
  if (!p.driverChange) return p.driverOut == null ? null : <span className="muted">No change</span>
  return (
    <span className="an-change">
      {name(car, p.driverIn)} → {name(car, p.driverOut)}
    </span>
  )
}

function StopList({ pit, name }: { pit: PitCar; name: (car: string, order: number | null) => string | null }) {
  return (
    <>
      {[...pit.stops].reverse().map((s) => (
        <tr key={s.startTimeMs} className="an-stop">
          <td />
          <td className="muted">Stop {s.number}</td>
          <td />
          <td />
          <td />
          <td className="num">
            <LastStop stop={s} />
          </td>
          <td>
            <Change p={s} car={pit.carNumber} name={name} />
          </td>
          <td />
          <td />
        </tr>
      ))}
    </>
  )
}

// ---- energy ---------------------------------------------------------------------------

const perLap = (a: EnergyAverage | null) => (a ? `${a.perLapPct.toFixed(2)}%` : '')

/**
 * Every car's energy use side by side: green use over the last 10 and 5 green
 * laps, what that rests on, caution use, and green laps left on the energy it
 * has now. Opening a car shows each lap and whether it counted.
 */
/** The scenario inputs as typed: blank fields are "not given". */
type ScenarioForm = { reserve: string; fromStop: boolean; cautionUse: string; cautionLapSec: string }

const num = (s: string) => (s.trim() === '' || !Number.isFinite(Number(s)) ? null : Number(s))

function energyPath(session: number, f: ScenarioForm): string {
  const q = new URLSearchParams({ session: String(session) })
  const reserve = num(f.reserve)
  if (reserve != null && reserve > 0 && reserve <= 100) q.set('reserve', String(reserve))
  if (f.fromStop) q.set('from', 'stop')
  const use = num(f.cautionUse)
  const lap = num(f.cautionLapSec)
  if (use != null && use >= 0 && use <= 100 && lap != null && lap > 0) {
    q.set('cautionUse', String(use))
    q.set('cautionLapMs', String(Math.round(lap * 1000)))
  }
  return `/api/live/energy?${q}`
}

export function EnergyView({ session }: { session: SessionSummary }) {
  const [form, setForm] = useState<ScenarioForm>({ reserve: '', fromStop: false, cautionUse: '', cautionLapSec: '' })
  // Typing settles before it asks: one request per pause, not per keystroke.
  const [asked, setAsked] = useState(form)
  useEffect(() => {
    const t = setTimeout(() => setAsked(form), 400)
    return () => clearTimeout(t)
  }, [form])
  const { value: fresh, error } = useLivePoll<EnergyResponse>(energyPath(session.sessionDbId, asked), pollEvery(session))
  // A changed scenario asks again; the last answer stays on screen until the new one lands.
  const last = useRef<EnergyResponse | null>(null)
  if (fresh) last.current = fresh
  const value = fresh ?? last.current
  const { open, modal } = useOpenCar(session)
  if (!value) return <Loading label="energy" error={error} />
  if (value.classes.length === 0) {
    return <div className="empty-state">No energy telemetry recorded for this session. Only IMSA WeatherTech cars send it.</div>
  }
  const live = value.live
  const finish = value.finish
  const columns = (live ? 10 : 8) + (finish ? 3 : 0)
  return (
    <>
      {error && <p className="timing-stale">Not updating: {error}</p>}
      {finish && <FinishControls finish={finish} form={form} onChange={setForm} />}
      <table className="grid-table tower an-energy" aria-label="Energy use by class">
        <thead>
          <tr>
            <th className="num" scope="col">
              #
            </th>
            <th scope="col">Team</th>
            {live && (
              <th className="num" scope="col" title="Energy remaining now (IMSA telemetry)">
                Energy
              </th>
            )}
            <th className="num" scope="col" title="Average energy used per green lap, over the last 10 green laps">
              Green / lap
            </th>
            <th className="num" scope="col" title="The same over the last 5 green laps: a change shows here first">
              Last 5
            </th>
            <th scope="col" title="How many green laps the average rests on, and whether they span a driver change">
              Based on
            </th>
            {live && (
              <th className="num" scope="col" title="Green laps left on the energy now, at the 10-lap green use">
                Green laps left
              </th>
            )}
            <th className="num" scope="col" title="Average energy used per full-course-yellow lap: the car's own, else its class's">
              Caution / lap
            </th>
            <th className="num" scope="col" title="Laps run since the car's newest green lap">
              Since green
            </th>
            <th scope="col" title="Laps whose energy does not count, and why">
              Left out
            </th>
            {finish && (
              <>
                <th className="num" scope="col" title="Laps still to run to the chequered flag at green pace">
                  To flag
                </th>
                <th className="num" scope="col" title="Energy left over at the flag if it stays green, less the reserve (minus: short)">
                  Green margin
                </th>
                <th scope="col" title="The fewest caution laps that get it to the flag">
                  To make it
                </th>
              </>
            )}
          </tr>
        </thead>
        {value.classes.map((cls) => {
          const info = new Map(cls.cars.map((c) => [c.carNumber, c]))
          return (
            <tbody key={cls.className} style={{ '--class-color': cls.color ?? undefined } as CSSProperties}>
              <tr className="class-band">
                <td colSpan={columns}>
                  <span className="band-label">{cls.className}</span>
                  {cls.caution && (
                    <span className="band-bests" title="Every car's full-course-yellow laps in the class, pooled">
                      <span>
                        Caution <span className="band-num">{perLap(cls.caution)}</span> a lap over {cls.caution.laps} laps
                      </span>
                    </span>
                  )}
                </td>
              </tr>
              {cls.energy.map((e) => {
                const car = info.get(e.carNumber) ?? { carNumber: e.carNumber, teamName: null, className: cls.className }
                const thin = e.green != null && e.green.laps < 10
                return (
                  <tr key={e.carNumber} className="tower-row" onClick={() => open(car, cls.color)}>
                    <td className="num tower-car">
                      <CarButton car={car} onOpen={() => open(car, cls.color)} />
                    </td>
                    <td className="tower-team">{car.teamName ?? <span className="muted">—</span>}</td>
                    {live && <td className="num">{e.energyPct != null ? `${Math.round(e.energyPct)}%` : ''}</td>}
                    <td className={`num${thin ? ' tower-energy-thin' : ''}`}>{perLap(e.green)}</td>
                    <td className="num muted">{perLap(e.greenShort)}</td>
                    <td>
                      {e.green ? (
                        <>
                          {e.green.laps} {e.green.laps === 1 ? 'lap' : 'laps'}
                          {e.green.driverChange && <span className="muted"> · 2+ drivers</span>}
                        </>
                      ) : (
                        <span className="muted">Needs 3 green laps</span>
                      )}
                    </td>
                    {live && <td className="num">{e.greenLapsLeft != null ? `${Math.floor(e.greenLapsLeft)} L` : ''}</td>}
                    <td className="num">
                      {perLap(e.caution)}
                      {e.cautionSource === 'CLASS' && <span className="muted"> class</span>}
                    </td>
                    <td className="num">{e.lapsSinceGreen != null ? `${e.lapsSinceGreen} L` : ''}</td>
                    <td className="muted an-energy-out">{energyLeftOut(e.laps)}</td>
                    {finish && (
                      <>
                        <td className="num">{e.finish ? e.finish.result.lapsToFlag.toFixed(1) : ''}</td>
                        <td className={`num${e.finish ? (e.finish.result.marginPct >= 0 ? ' an-energy-ok' : ' an-energy-short') : ''}`}>
                          {e.finish ? marginLabel(e.finish.result.marginPct) : ''}
                        </td>
                        <td>
                          {e.finish ? (
                            <>
                              {cautionLapsLabel(e.finish)}
                              {e.finish.result.cautionLaps != null && e.finish.result.cautionLaps > 0 && e.finish.cautionSource && (
                                <span className="muted">
                                  {' '}
                                  · {e.finish.cautionSource === 'CAR' ? 'own caution use' : e.finish.cautionSource === 'CLASS' ? 'class caution use' : 'your caution figure'}
                                </span>
                              )}
                            </>
                          ) : (
                            <span className="muted">{e.green ? '' : 'Needs 3 green laps'}</span>
                          )}
                        </td>
                      </>
                    )}
                  </tr>
                )
              })}
            </tbody>
          )
        })}
      </table>
      <p className="timing-foot an-foot">
        A lap counts when it is run under one flag — green, or full-course yellow for caution — clear of the pits,
        with IMSA's energy reading seen at the line before and after it. Green use averages the car's last 10 green
        laps of the session across pit stops; under 10 it is underlined, under 3 there is none. A car with fewer than
        3 caution laps of its own uses its class's. Open a car to see every lap and why it counted or not.
      </p>
      {finish && (
        <p className="timing-foot an-foot">
          {finish.type === 'TIME'
            ? 'The flag falls on the overall leader’s first crossing after the clock runs out, projected at its green pace; each car finishes on its next crossing. A caution lap uses less energy and more of the clock, so fewer laps fit before the flag.'
            : 'The leader’s laps left, less the laps a car is down. A caution lap replaces a green one and uses less.'}{' '}
          Not counted: the time a stop takes (so a stop now is given the laps as if it had not — the cautious side).
        </p>
      )}
      {modal}
    </>
  )
}

/** The scenario's inputs and the flag it runs to, above the Energy table in a live race. */
function FinishControls({
  finish,
  form,
  onChange,
}: {
  finish: EnergyFinishInfo
  form: ScenarioForm
  onChange: (f: ScenarioForm) => void
}) {
  const refill = `${finish.refillPct.toFixed(1)}%${finish.refillSource === 'ASSUMED' ? ', assumed: no refill seen yet' : ', where refills have landed'}`
  return (
    <div className="an-finish" role="group" aria-label="Finish scenario">
      <p className="an-finish-flag">
        {finish.type === 'TIME' ? (
          <>
            Clock: <strong>{duration(finish.clockLeftMs)}</strong> left · leader #{finish.leader} takes the flag in about{' '}
            <strong>{duration(finish.flagInMs)}</strong>
          </>
        ) : (
          <>
            Leader #{finish.leader}: <strong>{finish.leaderLapsLeft}</strong> {finish.leaderLapsLeft === 1 ? 'lap' : 'laps'} left
          </>
        )}
        {finish.inputs.fromStop && (
          <span className="muted">
            {' '}
            · every car from a stop now, at {refill}
          </span>
        )}
      </p>
      <label>
        Reserve{' '}
        <input
          type="number"
          inputMode="decimal"
          min={0}
          max={100}
          step={0.5}
          value={form.reserve}
          placeholder="0"
          onChange={(e) => onChange({ ...form, reserve: e.target.value })}
        />
        %
      </label>
      <div className="seg" role="radiogroup" aria-label="Start from">
        <button
          type="button"
          role="radio"
          className={`seg-btn${form.fromStop ? '' : ' active'}`}
          aria-checked={!form.fromStop}
          onClick={() => onChange({ ...form, fromStop: false })}
        >
          Energy now
        </button>
        <button
          type="button"
          role="radio"
          className={`seg-btn${form.fromStop ? ' active' : ''}`}
          aria-checked={form.fromStop}
          title={`A stop now, refilled to ${refill}`}
          onClick={() => onChange({ ...form, fromStop: true })}
        >
          A stop now
        </button>
      </div>
      <fieldset className="an-finish-caution">
        <legend>Caution figure, where a car and its class have none</legend>
        <label>
          <input
            type="number"
            inputMode="decimal"
            min={0}
            max={100}
            step={0.1}
            value={form.cautionUse}
            placeholder="—"
            onChange={(e) => onChange({ ...form, cautionUse: e.target.value })}
          />
          % a lap
        </label>
        <label>
          <input
            type="number"
            inputMode="decimal"
            min={1}
            step={1}
            value={form.cautionLapSec}
            placeholder="—"
            onChange={(e) => onChange({ ...form, cautionLapSec: e.target.value })}
          />
          s a lap
        </label>
      </fieldset>
    </div>
  )
}
