import { useEffect, useMemo, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import {
  getJson,
  type ChampionshipSummary,
  type Recap,
  type RecapSession,
  type RecapSessionPoints,
} from '../../lib/api'
import { useInfoModal } from '../../components/infoModal'
import RaceLine from '../../components/RaceLine'
import { useSeason } from './SeasonLayout'

/* Recaps are immutable between imports; cache per championship for the session
 * so filter toggles don't refetch. */
const recapCache = new Map<number, Promise<Recap>>()

export function fetchRecap(id: number): Promise<Recap> {
  let p = recapCache.get(id)
  if (!p) {
    p = getJson<Recap>(`/api/championships/${id}/recap`)
    recapCache.set(id, p)
  }
  return p
}

export function formatPoints(points: number): string {
  // NUMERIC totals arrive as JS numbers; String() already drops a trailing .0
  // and keeps real half-points ("19.5").
  return String(points)
}

/* -- points breakdown ---------------------------------------------------- */

/** Compact per-session tags for a multi-session round: "Q" for qualifying,
 * races numbered R1..Rn ("R" when the round has only one). A single-session
 * round gets no tag — the number alone reads fine. */
function sessionTags(sessions: RecapSession[]): (string | null)[] {
  if (sessions.length <= 1) return sessions.map(() => null)
  const raceTotal = sessions.filter((s) => !/qual/i.test(s.name)).length
  let raceNo = 0
  return sessions.map((s) => (/qual/i.test(s.name) ? 'Q' : raceTotal > 1 ? `R${++raceNo}` : 'R'))
}

interface PtsMark {
  glyph: string
  cls: string
  text: string
}

/** Bonus/penalty marks for one session's earnings, values printed in full
 * ("+1P"): the line reads race points + each extra, so the arithmetic is on
 * the table, not in a tooltip. */
function marksFor(sp: RecapSessionPoints): PtsMark[] {
  const marks: PtsMark[] = []
  if (sp.pole > 0)
    marks.push({ glyph: `+${formatPoints(sp.pole)}P`, cls: 'mk-pole', text: `pole +${formatPoints(sp.pole)}` })
  if (sp.fastestLap > 0)
    marks.push({
      glyph: `+${formatPoints(sp.fastestLap)}F`,
      cls: 'mk-fl',
      text: `fastest lap +${formatPoints(sp.fastestLap)}`,
    })
  // No letter code: the PDF's lumped extra doesn't say pole or fastest lap,
  // so a bare "+10" is the honest mark.
  if (sp.bonus > 0)
    marks.push({ glyph: `+${formatPoints(sp.bonus)}`, cls: 'mk-bonus', text: `bonus +${formatPoints(sp.bonus)}` })
  if (sp.penalty > 0)
    marks.push({ glyph: `−${formatPoints(sp.penalty)}`, cls: 'mk-pen', text: `penalty −${formatPoints(sp.penalty)}` })
  return marks
}

const SKIPPED: RecapSessionPoints = {
  total: 0,
  race: 0,
  pole: 0,
  fastestLap: 0,
  penalty: 0,
  bonus: 0,
  contested: false,
}

/** One session's earnings inside a round cell. marksCh reserves a shared marks
 * gutter (in ch) so digits stay in a straight column when some lines carry
 * marks and others don't; 0 renders no gutter at all. */
function PtsLine({
  tag,
  name,
  sp,
  marksCh,
}: {
  tag: string | null
  name: string
  sp: RecapSessionPoints
  marksCh: number
}) {
  const gutter =
    marksCh > 0 ? { minWidth: `${marksCh}ch` } : undefined
  if (!sp.contested) {
    return (
      <span className="pts-line" title={`${name}: did not run`}>
        {tag && <span className="pts-tag">{tag}</span>}
        <span className="pts-val pts-zero" aria-hidden="true">
          ·
        </span>
        <span className="sr-only">did not run</span>
        {gutter && <span className="pts-marks" style={gutter} />}
      </span>
    )
  }
  const marks = marksFor(sp)
  // With marks, the printed number is the RACE points and the marks carry the
  // extras — the whole sum sits on the table. Without marks they're the same
  // number (components always reconcile), so print the total.
  const value = marks.length ? sp.race : sp.total
  const title = marks.length
    ? `${name}: ${formatPoints(sp.total)} total`
    : tag
      ? name
      : undefined
  return (
    <span className="pts-line" title={title}>
      {tag && <span className="pts-tag">{tag}</span>}
      <span className={sp.total === 0 ? 'pts-val pts-zero' : 'pts-val'}>
        {formatPoints(value)}
      </span>
      {gutter && (
        <span className="pts-marks" style={gutter}>
          {marks.map((m, i) => (
            <span key={m.cls}>
              {i > 0 && ' '}
              <span className={m.cls} aria-hidden="true">
                {m.glyph}
              </span>
              <span className="sr-only">{m.text}</span>
            </span>
          ))}
        </span>
      )}
    </span>
  )
}

interface Family {
  family: string
  label: string
  isCup: boolean
}

/** Short segment labels: the season's own championship reads "Championship";
 * cups drop the series-name prefix ("IMSA Michelin Endurance Cup" → "Michelin
 * Endurance Cup"). */
function familyLabel(family: string, seriesName: string): string {
  if (family === seriesName) return 'Championship'
  let label = family
  if (label.startsWith(seriesName)) label = label.slice(seriesName.length).trim()
  const firstWord = seriesName.split(' ')[0]
  if (label === family && label.startsWith(firstWord + ' ')) {
    label = label.slice(firstWord.length + 1)
  }
  return label || family
}

function kindLabel(kind: string): string {
  const lower = kind.toLowerCase()
  return lower.charAt(0).toUpperCase() + lower.slice(1)
}

export interface ChampSelection {
  families: Family[]
  family: string | null
  setFamily: (f: string) => void
  kinds: string[]
  kind: string | null
  setKind: (k: string) => void
  /** championships matching family+kind (all classes), display-ordered */
  selected: ChampionshipSummary[]
}

export function useChampSelection(): ChampSelection {
  const { hub, classes } = useSeason()
  const [searchParams, setSearchParams] = useSearchParams()

  const withRows = useMemo(
    () => hub.championships.filter((c) => c.rowCount > 0),
    [hub.championships],
  )

  const families = useMemo<Family[]>(() => {
    const seen = new Map<string, Family>()
    for (const c of withRows) {
      const family = c.groupTitle ?? c.title
      if (!seen.has(family)) {
        seen.set(family, {
          family,
          label: familyLabel(family, hub.seriesName),
          isCup: c.isCup,
        })
      }
    }
    return [...seen.values()]
  }, [withRows, hub.seriesName])

  const familyParam = searchParams.get('champ')
  const family =
    families.find((f) => f.family === familyParam)?.family ?? families[0]?.family ?? null

  const kinds = useMemo(() => {
    const seen: string[] = []
    for (const c of withRows) {
      if ((c.groupTitle ?? c.title) === family && c.kind && !seen.includes(c.kind)) {
        seen.push(c.kind)
      }
    }
    // Teams first — matches the sheet's champ-column preference.
    return seen.sort((a, b) => rank(a) - rank(b))
  }, [withRows, family])

  const kindParam = searchParams.get('kind')
  const kind = kinds.includes(kindParam ?? '') ? kindParam : (kinds[0] ?? null)

  const selected = useMemo(() => {
    const classOrder = new Map(classes.map((c, i) => [c.name, i]))
    return withRows
      .filter((c) => (c.groupTitle ?? c.title) === family && c.kind === kind)
      .sort(
        (a, b) =>
          (classOrder.get(a.className ?? '') ?? 99) - (classOrder.get(b.className ?? '') ?? 99),
      )
  }, [withRows, family, kind, classes])

  function put(key: string, value: string) {
    const next = new URLSearchParams(searchParams)
    next.set(key, value)
    setSearchParams(next, { replace: true })
  }

  // Switching championship keeps the Teams/Drivers choice when the new family
  // also offers it — dropping it silently reset the user's selection to Teams.
  function switchFamily(f: string) {
    const next = new URLSearchParams(searchParams)
    next.set('champ', f)
    const nextKinds = new Set(
      withRows.filter((c) => (c.groupTitle ?? c.title) === f).map((c) => c.kind),
    )
    if (kind && nextKinds.has(kind)) next.set('kind', kind)
    else next.delete('kind')
    setSearchParams(next, { replace: true })
  }

  return {
    families,
    family,
    setFamily: switchFamily,
    kinds,
    kind,
    setKind: (k) => put('kind', k),
    selected,
  }
}

function rank(kind: string): number {
  switch (kind) {
    case 'TEAMS':
      return 0
    case 'DRIVERS':
      return 1
    default:
      return 2
  }
}

/** Which notation actually appears in the shown standings — the points legend
 * only decodes marks that exist, so a plain-points league gets no legend. */
interface PtsLegendFlags {
  q: boolean
  races: boolean
  pole: boolean
  fl: boolean
  bonus: boolean
  penalty: boolean
  skip: boolean
}

function ptsLegendFlags(recaps: Recap[]): PtsLegendFlags | null {
  const f: PtsLegendFlags = {
    q: false,
    races: false,
    pole: false,
    fl: false,
    bonus: false,
    penalty: false,
    skip: false,
  }
  for (const recap of recaps) {
    for (const r of recap.rounds) {
      if (r.sessions.length > 1) {
        if (r.sessions.some((s) => /qual/i.test(s.name))) f.q = true
        if (r.sessions.some((s) => !/qual/i.test(s.name))) f.races = true
      }
    }
    for (const row of recap.rows) {
      for (const sp of Object.values(row.sessionPoints)) {
        if (sp.pole > 0) f.pole = true
        if (sp.fastestLap > 0) f.fl = true
        if (sp.bonus > 0) f.bonus = true
        if (sp.penalty > 0) f.penalty = true
        if (!sp.contested) f.skip = true
      }
    }
  }
  return Object.values(f).some(Boolean) ? f : null
}

function PointsLegend({ f }: { f: PtsLegendFlags }) {
  return (
    <div className="legend" aria-label="Points notation">
      {f.q && <span>Q = qualifying</span>}
      {f.races && <span>R = race</span>}
      {f.pole && <span className="l-pole">P = pole</span>}
      {f.fl && <span>F = fastest lap</span>}
      {f.bonus && <span>+n = bonus</span>}
      {f.penalty && <span className="l-pen">− = penalty</span>}
      {f.skip && <span>· = did not run</span>}
      <span>— = no entry</span>
    </div>
  )
}

function RecapLegend() {
  return (
    <div className="legend" aria-label="Cell colours">
      <span className="l-win">
        <i /> Win
      </span>
      <span className="l-top3">
        <i /> Top 3
      </span>
      <span className="l-top5">
        <i /> Top 5
      </span>
      <span className="l-dnf">
        <i /> DNF
      </span>
      <span className="l-note">start/finish in class</span>
      <span className="l-pole">P = pole</span>
      <span>R = retired</span>
      <span>· = no entry</span>
    </div>
  )
}

export function ChampFilterBar({ sel, legend }: { sel: ChampSelection; legend: React.ReactNode }) {
  return (
    <div className="filter-bar">
      {sel.families.length > 1 && (
        <div className="seg" role="group" aria-label="Championship">
          {sel.families.map((f) => (
            <button
              key={f.family}
              type="button"
              className={sel.family === f.family ? 'seg-btn active' : 'seg-btn'}
              aria-pressed={sel.family === f.family}
              onClick={() => sel.setFamily(f.family)}
            >
              {f.label}
            </button>
          ))}
        </div>
      )}
      {sel.kinds.length > 1 && (
        <div className="seg" role="group" aria-label="Competitor type">
          {sel.kinds.map((k) => (
            <button
              key={k}
              type="button"
              className={sel.kind === k ? 'seg-btn active' : 'seg-btn'}
              aria-pressed={sel.kind === k}
              onClick={() => sel.setKind(k)}
            >
              {kindLabel(k)}
            </button>
          ))}
        </div>
      )}
      {legend && (
        <>
          <span className="spacer" />
          {legend}
        </>
      )}
    </div>
  )
}

/* ------------------------------------------------------------------------- */

function ClassGrid({ champ, mode }: { champ: ChampionshipSummary; mode: 'recap' | 'points' }) {
  const { classColor } = useSeason()
  const { openDriverByName, openTeam } = useInfoModal()
  const [recap, setRecap] = useState<Recap | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    fetchRecap(champ.id)
      .then((r) => !cancelled && setRecap(r))
      .catch((e) => !cancelled && setError(e instanceof Error ? e.message : 'Failed to load'))
    return () => {
      cancelled = true
    }
  }, [champ.id])

  if (error) return <p className="error-panel">{error}</p>
  if (!recap) {
    return (
      <div className="skeleton-block">
        <span className="skeleton" />
        <span className="skeleton" />
        <span className="skeleton" />
      </div>
    )
  }

  const rounds = recap.rounds
  const leaderPoints = recap.rows.length > 0 ? Math.max(...recap.rows.map((r) => r.totalPoints)) : 0
  const drivers = recap.championship.kind === 'DRIVERS'
  const color = classColor(champ.className)

  // Points mode: per-round session tags, and the widest marks run in this
  // class — every line reserves that gutter so digits stay in one column.
  const tagsByRound = new Map<number, (string | null)[]>()
  let marksCh = 0
  if (mode === 'points') {
    for (const r of rounds) tagsByRound.set(r.round, sessionTags(r.sessions))
    for (const row of recap.rows) {
      for (const sp of Object.values(row.sessionPoints)) {
        const line = marksFor(sp)
          .map((m) => m.glyph)
          .join(' ')
        marksCh = Math.max(marksCh, line.length)
      }
    }
  }

  // "-245 (-13)": deficit to the class leader, then the gap to the row above —
  // the on-air question is usually "how far to the car ahead", not the leader.
  const backText = (back: number, prevPoints: number | null, points: number) => {
    if (back === 0) return <span className="muted">—</span>
    const gap = prevPoints != null ? prevPoints - points : null
    return (
      <>
        -{formatPoints(back)}
        {gap != null && <span className="back-gap"> ({gap === 0 ? '0' : `-${formatPoints(gap)}`})</span>}
      </>
    )
  }

  // Sticky identity columns need explicit offsets. Widths are border-box (see
  // season.css) so they include the 20px cell padding — the cumulated offsets
  // below are only correct because of that.
  const identCols =
    mode === 'recap'
      ? [
          { key: 'pos', label: 'Pos', w: 52, cls: 'pos-cell' },
          { key: 'pts', label: 'Pts', w: 68, cls: 'num-cell' },
          // Wide enough for "-245 (-13)" without wrapping.
          { key: 'back', label: 'Back', w: 112, cls: 'num-cell back-cell' },
          { key: 'car', label: '#', w: 60, cls: 'car-no' },
          {
            key: 'name',
            label: drivers ? 'Driver' : 'Team',
            w: 260,
            cls: 'name-cell',
          },
        ]
      : [
          { key: 'pos', label: 'Pos', w: 52, cls: 'pos-cell' },
          { key: 'car', label: '#', w: 60, cls: 'car-no' },
          {
            key: 'name',
            label: drivers ? 'Driver' : 'Team',
            w: 260,
            cls: 'name-cell',
          },
        ]
  const lefts: number[] = []
  identCols.reduce((acc, c) => {
    lefts.push(acc)
    return acc + c.w
  }, 0)

  // The offset goes out as a custom property, never an inline `left` — the
  // stylesheet owns `left` so the narrow-screen rule can unpin the header.
  const identStyle = (i: number): React.CSSProperties =>
    ({
      '--ident-left': `${lefts[i]}px`,
      minWidth: identCols[i].w,
      maxWidth: identCols[i].key === 'name' ? identCols[i].w : undefined,
    }) as React.CSSProperties

  const gridLabel = `${champ.className} ${kindLabel(champ.kind ?? '')} — ${
    mode === 'recap' ? 'season recap, start and finish by round' : 'championship points by round'
  }`

  return (
    <table className="grid-table">
      <caption className="sr-only">{gridLabel}</caption>
      <thead>
        <tr>
          {identCols.map((c, i) => (
            <th
              key={c.key}
              className="ident"
              scope="col"
              style={identStyle(i)}
              title={c.key === 'back' ? 'Points behind the class leader' : undefined}
            >
              {c.label}
            </th>
          ))}
          {rounds.map((r) => (
            <th key={r.round} className="round-head" scope="col">
              <span className="venue">{r.venue}</span>
              <span className="rd">Rd {r.round}</span>
            </th>
          ))}
          {mode === 'points' && (
            <>
              <th className="num-cell">Total</th>
              <th className="num-cell" title="Points behind the class leader">
                Back
              </th>
            </>
          )}
          <th className="grid-soak" aria-hidden="true" />
        </tr>
      </thead>
      <tbody>
        <tr className="class-band">
          <td
            colSpan={identCols.length + rounds.length + (mode === 'points' ? 2 : 0) + 1}
            style={{ '--class-color': color } as React.CSSProperties}
          >
            <span className="band-label">
              {champ.className} · {kindLabel(champ.kind ?? '')}
            </span>
          </td>
        </tr>
        {recap.rows.map((row, rowIdx) => {
          const back = leaderPoints - row.totalPoints
          const prevPoints = rowIdx > 0 ? recap.rows[rowIdx - 1].totalPoints : null
          const name = drivers ? (row.competitorName ?? row.competitorKey) : row.teamName
          return (
            <tr key={row.competitorKey}>
              {identCols.map((c, i) => {
                const style = identStyle(i)
                switch (c.key) {
                  case 'pos':
                    return (
                      <td key={c.key} className={`ident ${c.cls}`} style={style}>
                        {row.position}
                      </td>
                    )
                  case 'pts':
                    return (
                      <td key={c.key} className={`ident ${c.cls}`} style={style}>
                        {formatPoints(row.totalPoints)}
                      </td>
                    )
                  case 'back':
                    return (
                      <td key={c.key} className={`ident ${c.cls}`} style={style}>
                        {backText(back, prevPoints, row.totalPoints)}
                      </td>
                    )
                  case 'car':
                    return (
                      <td key={c.key} className={`ident ${c.cls}`} style={style}>
                        {row.carNumber ?? ''}
                      </td>
                    )
                  default:
                    return (
                      <td
                        key={c.key}
                        className={`ident ${c.cls}`}
                        style={style}
                        title={name ?? undefined}
                      >
                        {name ? (
                          // The row header opens the competitor's info
                          // modal — driver or team by championship kind.
                          <button
                            type="button"
                            className="drv-link"
                            onClick={() => (drivers ? openDriverByName(name) : openTeam(name))}
                          >
                            {name}
                          </button>
                        ) : (
                          name
                        )}
                      </td>
                    )
                }
              })}
              {rounds.map((r) => {
                if (mode === 'points') {
                  const pts = row.pointsByRound[r.round]
                  if (pts == null) {
                    return (
                      <td key={r.round} className="pts-cell">
                        <span className="muted" title="Did not enter this round">
                          —
                        </span>
                      </td>
                    )
                  }
                  const roundTags = tagsByRound.get(r.round) ?? []
                  return (
                    <td
                      key={r.round}
                      className="pts-cell"
                      // The sum leaves the cell for the breakdown, so the round
                      // total stays one hover away.
                      title={r.sessions.length > 1 ? `${formatPoints(pts)} total` : undefined}
                    >
                      {r.sessions.length === 0
                        ? formatPoints(pts)
                        : r.sessions.map((s, si) => (
                            <PtsLine
                              key={s.sessionIndex}
                              tag={roundTags[si]}
                              name={s.name}
                              sp={row.sessionPoints[s.sessionIndex] ?? SKIPPED}
                              marksCh={marksCh}
                            />
                          ))}
                    </td>
                  )
                }
                const races = row.cells[r.round]
                return (
                  <td key={r.round} className="race-cell">
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
              {mode === 'points' && (
                <>
                  <td className="num-cell" style={{ fontWeight: 700 }}>
                    {formatPoints(row.totalPoints)}
                  </td>
                  <td className="num-cell back-cell">
                    {backText(back, prevPoints, row.totalPoints)}
                  </td>
                </>
              )}
              <td className="grid-soak" />
            </tr>
          )
        })}
      </tbody>
    </table>
  )
}

export default function ChampionshipGrid({ mode }: { mode: 'recap' | 'points' }) {
  const { classFilter } = useSeason()
  const sel = useChampSelection()
  const [ptsFlags, setPtsFlags] = useState<PtsLegendFlags | null>(null)

  const shown = useMemo(
    () => (classFilter ? sel.selected.filter((c) => c.className === classFilter) : sel.selected),
    [sel.selected, classFilter],
  )

  // The points legend decodes only the notation actually on screen — recaps
  // are already being fetched by the class grids, so this rides the cache.
  useEffect(() => {
    if (mode !== 'points' || shown.length === 0) {
      setPtsFlags(null)
      return
    }
    let cancelled = false
    Promise.all(shown.map((c) => fetchRecap(c.id)))
      .then((recaps) => !cancelled && setPtsFlags(ptsLegendFlags(recaps)))
      .catch(() => !cancelled && setPtsFlags(null))
    return () => {
      cancelled = true
    }
  }, [mode, shown])

  if (sel.families.length === 0) {
    return (
      <div className="empty-state">
        No standings imported yet — bring in a standings file from the{' '}
        <Link to="/imports">Imports</Link> tab and the season recap builds itself.
      </div>
    )
  }

  return (
    <div>
      <ChampFilterBar
        sel={sel}
        legend={
          mode === 'recap' ? <RecapLegend /> : ptsFlags ? <PointsLegend f={ptsFlags} /> : null
        }
      />
      {shown.length === 0 ? (
        <div className="empty-state">
          No {classFilter} standings in this championship — pick another class or championship.
        </div>
      ) : (
        shown.map((c) => <ClassGrid key={c.id} champ={c} mode={mode} />)
      )}
    </div>
  )
}
