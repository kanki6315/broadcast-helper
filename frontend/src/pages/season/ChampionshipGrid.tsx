import { useEffect, useMemo, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import {
  getJson,
  type ChampionshipSummary,
  type Recap,
  type RecapRound,
  type RecapSession,
  type RecapSessionPoints,
} from '../../lib/api'
import { useInfoModal } from '../../components/infoModal'
import { raceTagsByOrdinal, sessionTagList } from '../../lib/raceForm'
import RaceLine from '../../components/RaceLine'
import { useSeason } from './SeasonLayout'

/* Recaps are immutable between imports; cache per championship for the session
 * so filter toggles don't refetch. */
const recapCache = new Map<number, Promise<Recap>>()

export function fetchRecap(id: number): Promise<Recap> {
  let p = recapCache.get(id)
  if (!p) {
    // Evict on failure. Caching the REJECTED promise made one dropped request
    // permanent: every later call re-awaited the same rejection, so a momentary
    // network blip killed standings until a full page reload.
    p = getJson<Recap>(`/api/championships/${id}/recap`).catch((e) => {
      recapCache.delete(id)
      throw e
    })
    recapCache.set(id, p)
  }
  return p
}

/** Drop a cached recap after something changed how it renders — the series
 *  settings modal's overall-championship toggle re-scopes the whole grid, and
 *  SPA navigation back to the season would otherwise serve the stale promise. */
export function invalidateRecap(id: number) {
  recapCache.delete(id)
}

/** Drop every cached recap. The car-number-alias editor changes how a whole
 *  season's recaps match cells but only knows the season, not which
 *  championship ids are cached — clearing everything is cheap and correct. */
export function invalidateAllRecaps() {
  recapCache.clear()
}

export function formatPoints(points: number): string {
  // NUMERIC totals arrive as JS numbers; String() already drops a trailing .0
  // and keeps real half-points ("19.5").
  return String(points)
}

/* -- points breakdown ---------------------------------------------------- */

/** The round's session tags keyed by session index — `sessionTagList` in the
 * shared result vocabulary, addressed the way the points cells need it. */
function sessionTags(sessions: RecapSession[]): Map<number, string | null> {
  const list = sessionTagList(sessions.map((s) => s.name))
  return new Map(sessions.map((s, i) => [s.sessionIndex, list[i]]))
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

/** The sessions of one round this competitor actually ran, in calendar order.
 * A session they sat out contributes no line at all — six blank markers for a
 * driver who ran one heat of a six-race weekend cost a lot of vertical space
 * to say nothing. The tag on each surviving line says which session it was. */
function contestedSessions(
  round: RecapRound,
  sessionPoints: Record<number, RecapSessionPoints>,
): { session: RecapSession; sp: RecapSessionPoints }[] {
  return round.sessions
    .map((session) => ({ session, sp: sessionPoints[session.sessionIndex] }))
    .filter((s): s is { session: RecapSession; sp: RecapSessionPoints } => s.sp?.contested === true)
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

export function kindLabel(kind: string): string {
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
 * only decodes what exists, so a plain-points league gets no legend at all.
 * `tags` maps each session initial to the word it abbreviates, read from the
 * real session names, so "F = feature" is stated rather than assumed. */
interface PtsLegendFlags {
  tags: Map<string, string>
  pole: boolean
  fl: boolean
  bonus: boolean
  penalty: boolean
}

function ptsLegendFlags(recaps: Recap[]): PtsLegendFlags | null {
  const f: PtsLegendFlags = {
    tags: new Map(),
    pole: false,
    fl: false,
    bonus: false,
    penalty: false,
  }
  for (const recap of recaps) {
    for (const r of recap.rounds) {
      if (r.sessions.length <= 1) continue
      const tags = sessionTags(r.sessions)
      for (const s of r.sessions) {
        // The tag carries any positional number ("H1"); the legend explains the
        // letter, so strip the digits back off.
        const letter = (tags.get(s.sessionIndex) ?? '').replace(/\d+$/, '')
        const word = s.name.trim().split(/\s+/)[0].toLowerCase()
        if (letter && !f.tags.has(letter)) f.tags.set(letter, word)
      }
    }
    for (const row of recap.rows) {
      for (const sp of Object.values(row.sessionPoints)) {
        if (sp.pole > 0) f.pole = true
        if (sp.fastestLap > 0) f.fl = true
        if (sp.bonus > 0) f.bonus = true
        if (sp.penalty > 0) f.penalty = true
      }
    }
  }
  const any = f.tags.size > 0 || f.pole || f.fl || f.bonus || f.penalty
  return any ? f : null
}

function PointsLegend({ f }: { f: PtsLegendFlags }) {
  return (
    <div className="legend" aria-label="Points notation">
      {[...f.tags].map(([letter, word]) => (
        <span key={letter}>
          {letter} = {word}
        </span>
      ))}
      {/* The marks keep their +/− prefix here: a Feature tags "F", and its
       * fastest-lap bonus reads "+1F" — the sign is what tells them apart. */}
      {f.pole && <span className="l-pole">+nP = pole</span>}
      {f.fl && <span>+nF = fastest lap</span>}
      {f.bonus && <span>+n = bonus</span>}
      {f.penalty && <span className="l-pen">−n = penalty</span>}
      <span>— = no entry</span>
    </div>
  )
}

/** Whether the chips on screen are in-class positions, whole-field positions
 *  (overall championships), or a mix of grids showing each. */
type PosScope = 'class' | 'overall' | 'mixed'

function posScopeOf(recaps: Recap[]): PosScope {
  if (recaps.every((r) => r.championship.isOverall)) return 'overall'
  if (recaps.every((r) => !r.championship.isOverall)) return 'class'
  return 'mixed'
}

const POS_SCOPE_NOTE: Record<PosScope, string> = {
  class: 'start/finish in class',
  overall: 'start/finish overall',
  mixed: 'start/finish in class · overall standings: whole field',
}

function RecapLegend({ tags, scope }: { tags: Map<string, string>; scope: PosScope }) {
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
      <span className="l-note">{POS_SCOPE_NOTE[scope]}</span>
      {/* The race tags come first: they read left-to-right before the chip,
       * and "C = consolation" is not guessable. Listed only where a round
       * actually ran more than one race. */}
      {[...tags].map(([letter, word]) => (
        <span key={letter}>
          {letter} = {word}
        </span>
      ))}
      <span className="l-pole">P = pole</span>
      {/* Superscript on the chip, so it is spelled out that way — a round of
       * "Race 1"/"Race 2" tags R1/R2 in the same cell. */}
      <span>
        <sup>R</sup> = retired
      </span>
      <span>· = no entry</span>
    </div>
  )
}

/** Session words present in the shown recaps' multi-race rounds, keyed by the
 * tag letter — the recap legend names what it actually shows, like the points
 * one. Empty where every round ran a single race. */
function raceLegendTags(recaps: Recap[]): Map<string, string> {
  const tags = new Map<string, string>()
  for (const recap of recaps) {
    for (const r of recap.rounds) {
      if (r.races.length <= 1) continue
      const byOrdinal = raceTagsByOrdinal(r.races)
      for (const race of r.races) {
        const letter = (byOrdinal.get(race.ordinal) ?? '').replace(/\d+$/, '')
        const word = (race.name ?? '').trim().split(/\s+/)[0].toLowerCase()
        if (letter && word && !tags.has(letter)) tags.set(letter, word)
      }
    }
  }
  return tags
}

export function ChampFilterBar({
  sel,
  legend,
  teams,
  view,
}: {
  sel: ChampSelection
  legend: React.ReactNode
  teams?: { shown: boolean; setShown: (shown: boolean) => void } | null
  view?: { view: PtsView; setView: (v: PtsView) => void } | null
}) {
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
      {view && (
        <div className="seg" role="group" aria-label="Points detail">
          {(
            [
              ['breakdown', 'Breakdown'],
              ['total', 'Round total'],
            ] as const
          ).map(([v, label]) => (
            <button
              key={v}
              type="button"
              className={view.view === v ? 'seg-btn active' : 'seg-btn'}
              aria-pressed={view.view === v}
              onClick={() => view.setView(v)}
            >
              {label}
            </button>
          ))}
        </div>
      )}
      {teams && (
        <div className="seg" role="group" aria-label="Recap display">
          <button
            type="button"
            className={teams.shown ? 'seg-btn active' : 'seg-btn'}
            aria-pressed={teams.shown}
            onClick={() => teams.setShown(!teams.shown)}
          >
            Show teams
          </button>
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

/** Standings cells either break a round down by session or print its one
 * total — the high-level read for a quick championship glance. */
type PtsView = 'breakdown' | 'total'

function ClassGrid({
  champ,
  mode,
  showTeams,
  view,
}: {
  champ: ChampionshipSummary
  mode: 'recap' | 'points'
  showTeams: boolean
  view: PtsView
}) {
  const { classColor } = useSeason()
  const { openDriverByName, openTeam } = useInfoModal()
  const [recap, setRecap] = useState<Recap | null>(null)
  const [failed, setFailed] = useState(false)
  // The effect keys on champ.id, which never changes for a mounted grid — so
  // without an explicit attempt counter a single dropped request left this
  // class dead until the page was reloaded, taking the season's filter with it.
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    let cancelled = false
    setFailed(false)
    fetchRecap(champ.id)
      .then((r) => !cancelled && setRecap(r))
      .catch(() => !cancelled && setFailed(true))
    return () => {
      cancelled = true
    }
  }, [champ.id, attempt])

  if (failed) {
    // Never the raw exception: "TypeError: Failed to fetch" tells the user
    // nothing and reads like data corruption on a page full of numbers.
    return (
      <p className="error-panel">
        Couldn’t load the {champ.className} {kindLabel(champ.kind ?? '').toLowerCase()} recap.{' '}
        <button
          type="button"
          className="hs-retry"
          onClick={() => {
            invalidateRecap(champ.id)
            setAttempt((n) => n + 1)
          }}
        >
          Retry
        </button>
      </p>
    )
  }
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

  // Recap mode: which race of the weekend each result line is, tagged over the
  // round's whole race list (see raceTagsByOrdinal).
  const raceTagsByRound = new Map<number, Map<number, string | null>>()
  if (mode === 'recap') {
    for (const r of rounds) raceTagsByRound.set(r.round, raceTagsByOrdinal(r.races))
  }

  // Breakdown mode: per-round session tags, and the widest marks run in this
  // class — every line reserves that gutter so digits stay in one column.
  const breakdown = mode === 'points' && view === 'breakdown'
  const tagsByRound = new Map<number, Map<number, string | null>>()
  let marksCh = 0
  if (breakdown) {
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
          { key: 'pos', label: 'Pos', w: 44, cls: 'pos-cell' },
          // Points with the deficit to the class leader folded in as "(-N)" — the
          // old standalone Back column is gone. Width matches that old Back column
          // (112px), which reliably fit "-1156 (-126)"; the merged "points (-gap)"
          // is no wider. Widths are border-box, so this MUST exceed the widest
          // content or the cumulated sticky-left offsets drift.
          { key: 'pts', label: 'Pts', w: 112, cls: 'num-cell' },
          { key: 'car', label: '#', w: 44, cls: 'car-no' },
          {
            key: 'name',
            label: drivers ? 'Driver' : 'Team',
            w: 216,
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
  const identStyle = (i: number): React.CSSProperties => {
    const col = identCols[i]
    // The name column is the last sticky column, so its width feeds no offsets;
    // hand it entirely to CSS (a responsive clamp in season.css) so it shrinks
    // with the viewport. The numeric columns keep fixed inline widths because
    // the cumulated sticky-left offsets depend on them.
    if (col.key === 'name') return { '--ident-left': `${lefts[i]}px` } as React.CSSProperties
    return { '--ident-left': `${lefts[i]}px`, minWidth: col.w } as React.CSSProperties
  }

  const gridLabel = `${champ.className} ${kindLabel(champ.kind ?? '')} — ${
    mode === 'recap' ? 'season recap, start and finish by round' : 'championship points by round'
  }`

  return (
    <div className="grid-scroll">
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
          const teamNames = drivers
            ? (row.teamNames?.length ? row.teamNames : row.teamName ? [row.teamName] : [])
            : []
          const shownTeamNames = showTeams ? teamNames : []
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
                        {back > 0 && <span className="back-gap"> (-{formatPoints(back)})</span>}
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
                        title={name ? (shownTeamNames.length ? `${name} — ${shownTeamNames.join(', ')}` : name) : undefined}
                      >
                        {name ? (
                          <span className="recap-identity">
                            {/* The primary label opens the championship competitor. */}
                            <button
                              type="button"
                              className="drv-link"
                              onClick={() => (drivers ? openDriverByName(name) : openTeam(name))}
                            >
                              {name}
                            </button>
                            {shownTeamNames.length > 0 && (
                              <span className="recap-teams">
                                {shownTeamNames.map((teamName, index) => (
                                  <span key={teamName}>
                                    {index > 0 && <span className="recap-team-separator" aria-hidden="true"> · </span>}
                                    {teamName === 'Privateer' ? teamName : (
                                      <button type="button" className="drv-link" onClick={() => openTeam(teamName)}>
                                        {teamName}
                                      </button>
                                    )}
                                  </span>
                                ))}
                              </span>
                            )}
                          </span>
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
                  const ran = breakdown ? contestedSessions(r, row.sessionPoints) : []
                  // Total view, or a round the source scores as a whole: one
                  // number. `ran` can also come back empty if a round's points
                  // survived the recap's gate with no session marked contested;
                  // the round total is still the honest answer.
                  if (!breakdown || ran.length === 0) {
                    return (
                      <td key={r.round} className="pts-cell">
                        <span className="pts-line">
                          <span className={pts === 0 ? 'pts-val pts-zero' : 'pts-val'}>
                            {formatPoints(pts)}
                          </span>
                        </span>
                      </td>
                    )
                  }
                  const roundTags = tagsByRound.get(r.round)
                  return (
                    <td
                      key={r.round}
                      className="pts-cell"
                      // The sum leaves the cell for the breakdown, so the round
                      // total stays one hover away.
                      title={ran.length > 1 ? `${formatPoints(pts)} total` : undefined}
                    >
                      {ran.map(({ session, sp }) => (
                        <PtsLine
                          key={session.sessionIndex}
                          tag={roundTags?.get(session.sessionIndex) ?? null}
                          name={session.name}
                          sp={sp}
                          marksCh={marksCh}
                        />
                      ))}
                    </td>
                  )
                }
                const races = row.cells[r.round]
                const raceTags = raceTagsByRound.get(r.round) ?? new Map()
                return (
                  <td key={r.round} className="race-cell">
                    {races && races.length > 0 ? (
                      races.map((race) => (
                        <RaceLine key={race.race} r={race} tag={raceTags.get(race.race)} />
                      ))
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
    </div>
  )
}

export default function ChampionshipGrid({ mode }: { mode: 'recap' | 'points' }) {
  const { classFilter } = useSeason()
  const sel = useChampSelection()
  const [searchParams, setSearchParams] = useSearchParams()
  const [ptsFlags, setPtsFlags] = useState<PtsLegendFlags | null>(null)
  const [raceTags, setRaceTags] = useState<Map<string, string>>(new Map())
  const [posScope, setPosScope] = useState<PosScope>('class')

  // Like every other selection on this page, the view lives in the URL so a
  // filtered board stays bookmarkable and survives sub-page navigation.
  const view: PtsView = searchParams.get('pts') === 'total' ? 'total' : 'breakdown'
  const showTeams = mode === 'recap' && searchParams.get('teams') === 'show'
  const setView = (v: PtsView) => {
    const next = new URLSearchParams(searchParams)
    if (v === 'breakdown') next.delete('pts')
    else next.set('pts', v)
    setSearchParams(next, { replace: true })
  }
  const setShowTeams = (shown: boolean) => {
    const next = new URLSearchParams(searchParams)
    if (shown) next.set('teams', 'show')
    else next.delete('teams')
    setSearchParams(next, { replace: true })
  }

  const shown = useMemo(
    () => (classFilter ? sel.selected.filter((c) => c.className === classFilter) : sel.selected),
    [sel.selected, classFilter],
  )

  // Both legends decode only the notation actually on screen — recaps are
  // already being fetched by the class grids, so this rides the cache.
  useEffect(() => {
    if (shown.length === 0) {
      setPtsFlags(null)
      setRaceTags(new Map())
      return
    }
    let cancelled = false
    Promise.all(shown.map((c) => fetchRecap(c.id)))
      .then((recaps) => {
        if (cancelled) return
        setPtsFlags(mode === 'points' ? ptsLegendFlags(recaps) : null)
        setRaceTags(mode === 'recap' ? raceLegendTags(recaps) : new Map())
        setPosScope(posScopeOf(recaps))
      })
      .catch(() => {
        if (cancelled) return
        setPtsFlags(null)
        setRaceTags(new Map())
        setPosScope('class')
      })
    return () => {
      cancelled = true
    }
  }, [mode, shown])

  if (sel.families.length === 0) {
    return (
      <div className="empty-state">
        No standings imported yet — bring in a standings file from the{' '}
        <Link to="/manage/imports">Imports</Link> under Manage and the season recap builds itself.
      </div>
    )
  }

  return (
    <div>
      <ChampFilterBar
        sel={sel}
        teams={mode === 'recap' && sel.kind === 'DRIVERS' ? { shown: showTeams, setShown: setShowTeams } : null}
        // Only offered where the two views actually differ: with nothing to
        // break down, a toggle that changes nothing is noise.
        view={mode === 'points' && ptsFlags ? { view, setView } : null}
        legend={
          mode === 'recap' ? (
            <RecapLegend tags={raceTags} scope={posScope} />
          ) : ptsFlags && view === 'breakdown' ? (
            <PointsLegend f={ptsFlags} />
          ) : null
        }
      />
      {shown.length === 0 ? (
        <div className="empty-state">
          No {classFilter} standings in this championship — pick another class or championship.
        </div>
      ) : (
        shown.map((c) => <ClassGrid key={c.id} champ={c} mode={mode} showTeams={showTeams} view={view} />)
      )}
    </div>
  )
}
