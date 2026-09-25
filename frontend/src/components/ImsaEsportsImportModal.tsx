import { useEffect, useMemo, useRef, useState } from 'react'
import './imsa-esports-import-modal.css'
import { getJson, type SeasonSummary } from '../lib/api'

/**
 * The IMSA Esports correction (docs/IMSA_ESPORTS_CORRECTION.md). Rounds are
 * imported from iRacing first; this lays the official classification and
 * standings from artifactracing.com over them. The backend plans (read-only)
 * and applies; everything the reviewer decides here travels with the next
 * plan, so the server stays the one judge of what is ready. Decisions re-plan
 * automatically — the backend caches the site for a minute, so that's cheap.
 */

interface League {
  id: string
  name: string
  seriesName: string | null
  seasonNumber: number | null
  active: boolean
}
interface EntryOption {
  entryId: number
  carNumber: string
  teamName: string
  drivers: string[]
}
interface LineupLine {
  siteName: string | null
  driverId: number | null
  pitPassName: string | null
  match: 'EXACT' | 'CLOSE' | 'MISSING' | 'EXTRA'
}
interface Change {
  field: string
  from: string | null
  to: string | null
}
type Match = 'EXACT_DRIVERS' | 'CONFIRMED' | 'CLOSE_DRIVERS' | 'CAR_NUMBER' | 'AMBIGUOUS' | 'NONE' | 'SKIPPED'
interface CarPlan {
  siteResultId: string
  carNumber: string
  className: string
  teamName: string
  drivers: string[]
  overallPosition: number
  classPosition: number
  entryId: number | null
  entryCarNumber: string | null
  entryTeamName: string | null
  entryClassName: string | null
  match: Match
  needsConfirmation: boolean
  candidates: EntryOption[]
  lineup: LineupLine[]
  changes: Change[]
  classPositionDelta: number | null
}
interface EntryRef {
  entryId: number
  carNumber: string
  teamName: string
  className: string
  drivers: string[]
  hasRaceResult: boolean
  mustDrop: boolean
  dropping: boolean
}
interface RoundPlan {
  round: number
  siteEventId: string
  track: string
  raceStart: string | null
  siteResults: number
  event: { id: number; name: string; date: string | null; roundOrdinal: number | null } | null
  eventMatch: 'SOURCE' | 'DRIVERS' | 'OVERRIDE' | 'NONE'
  raceSessionId: number | null
  cars: CarPlan[]
  unpairedEntries: EntryRef[]
  blocking: string[]
}
interface Plan {
  leagueId: string
  leagueName: string
  seasonId: number
  seasonLabel: string
  rounds: RoundPlan[]
  classes: { siteClass: string; pitPassClass: string | null; source: 'PAIRED' | 'MAPPED' | 'NONE' }[]
  standings: { siteClass: string; pitPassClass: string | null; championshipName: string; rows: number; rounds: number; discrepancies: string[] }[]
  teamFolds: { fromTeamId: number; fromName: string; toName: string; folding: boolean }[]
  warnings: string[]
  blocking: string[]
  ready: boolean
}
interface ApplyResult {
  roundsCorrected: number
  resultsWritten: number
  entriesRenumbered: number
  teamsRenamed: number
  teamsFolded: number
  entriesDropped: number
  driversConsolidated: number
  standingsChampionships: number
}

// The reviewer's decisions, sent with every plan and the apply.
interface Decisions {
  pairings: Record<string, number | null>
  consolidations: string[] // `${driverId}|${name}`
  drops: number[]
  classMapping: Record<string, string>
  keepTeams: number[]
}
const NO_DECISIONS: Decisions = { pairings: {}, consolidations: [], drops: [], classMapping: {}, keepTeams: [] }

const LEAVE_OUT = 'leave-out'

function yearOf(name: string): number | null {
  const m = name.match(/\b(20\d\d)\b/)
  return m ? Number(m[1]) : null
}

function seasonName(s: SeasonSummary): string {
  return `${s.seriesName} ${s.label ?? s.year}`
}

/** The Pit Pass season a site league most likely corrects: same year, an esports series. */
function guessSeason(league: League | undefined, seasons: SeasonSummary[]): number | null {
  if (!league) return null
  const year = yearOf(league.name)
  const esports = seasons.filter((s) => /imsa.*e-?sports/i.test(s.seriesName))
  const pick =
    esports.find((s) => s.year === year && s.kind === 'MAIN') ?? esports.find((s) => s.year === year) ?? null
  return pick?.id ?? null
}

function signed(n: number): string {
  return n > 0 ? `+${n}` : n < 0 ? `−${-n}` : '0'
}

const DECIDING: Match[] = ['AMBIGUOUS', 'NONE', 'CONFIRMED', 'SKIPPED']

function needsPick(c: CarPlan, decided: Record<string, number | null>): boolean {
  return c.needsConfirmation || DECIDING.includes(c.match) || c.siteResultId in decided
}

export default function ImsaEsportsImportModal({ onClose }: { onClose: () => void }) {
  const dialogRef = useRef<HTMLDialogElement>(null)
  const [leagues, setLeagues] = useState<League[] | null>(null)
  const [seasons, setSeasons] = useState<SeasonSummary[] | null>(null)
  const [leagueId, setLeagueId] = useState<string>('')
  const [seasonId, setSeasonId] = useState<number | null>(null)
  const [standings, setStandings] = useState(true)
  const [decisions, setDecisions] = useState<Decisions>(NO_DECISIONS)
  const [plan, setPlan] = useState<Plan | null>(null)
  const [planning, setPlanning] = useState(false)
  const [applying, setApplying] = useState(false)
  const [result, setResult] = useState<ApplyResult | null>(null)
  const [error, setError] = useState<string | null>(null)
  // Every entry a site row has been offered, kept across re-plans: once a
  // pairing is confirmed the backend stops listing its alternatives.
  const [options, setOptions] = useState<Record<string, EntryOption[]>>({})
  const planSeq = useRef(0)
  const decisionsRef = useRef<Decisions>(NO_DECISIONS)

  useEffect(() => {
    const d = dialogRef.current
    if (d && !d.open) d.showModal()
  }, [])

  useEffect(() => {
    void (async () => {
      try {
        const [ls, ss] = await Promise.all([
          (async () => {
            const res = await fetch('/api/imports/artifact/leagues')
            const body = await res.json().catch(() => null)
            if (!res.ok) throw new Error(body?.message ?? `Could not reach artifactracing.com (${res.status})`)
            return body as League[]
          })(),
          getJson<SeasonSummary[]>('/api/seasons'),
        ])
        setLeagues(ls)
        setSeasons(ss)
        const first = ls.find((l) => /imsa/i.test(l.name) && !l.active && yearOf(l.name) != null) ?? ls[0]
        if (first) {
          setLeagueId(first.id)
          setSeasonId(guessSeason(first, ss))
        }
      } catch (e) {
        setError(e instanceof Error ? e.message : 'Could not load seasons.')
        setLeagues((l) => l ?? [])
        setSeasons((s) => s ?? [])
      }
    })()
  }, [])

  function body(d: Decisions) {
    return {
      leagueId,
      seasonId,
      pairings: d.pairings,
      consolidations: d.consolidations.map((k) => {
        const cut = k.indexOf('|')
        return { driverId: Number(k.slice(0, cut)), name: k.slice(cut + 1) }
      }),
      dropEntryIds: d.drops,
      classMapping: d.classMapping,
      importStandings: standings,
      keepTeamIds: d.keepTeams,
    }
  }

  async function runPlan(d: Decisions) {
    if (!leagueId || seasonId == null) return
    const seq = ++planSeq.current
    setPlanning(true)
    setError(null)
    try {
      const res = await fetch('/api/imports/artifact/plan', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body(d)),
      })
      const json = await res.json().catch(() => null)
      if (seq !== planSeq.current) return // a newer decision already re-planned
      if (!res.ok) {
        setError(json?.message ?? `Could not check the season (${res.status})`)
        return
      }
      const p = json as Plan
      setPlan(p)
      setOptions((prev) => {
        const next = { ...prev }
        for (const r of p.rounds) {
          for (const c of r.cars) {
            const seen = [...(next[c.siteResultId] ?? [])]
            const add = (o: EntryOption) => {
              if (!seen.some((x) => x.entryId === o.entryId)) seen.push(o)
            }
            if (c.entryId != null) {
              add({ entryId: c.entryId, carNumber: c.entryCarNumber ?? '', teamName: c.entryTeamName ?? '', drivers: [] })
            }
            c.candidates.forEach(add)
            r.unpairedEntries.forEach((u) => add({ entryId: u.entryId, carNumber: u.carNumber, teamName: u.teamName, drivers: u.drivers }))
            next[c.siteResultId] = seen
          }
        }
        return next
      })
    } catch {
      if (seq === planSeq.current) setError('Could not reach the server.')
    } finally {
      if (seq === planSeq.current) setPlanning(false)
    }
  }

  // A decision re-plans straight away; the server says whether it's ready.
  function decide(update: (d: Decisions) => Decisions) {
    const next = update(decisionsRef.current)
    decisionsRef.current = next
    setDecisions(next)
    void runPlan(next)
  }

  function resetDecisions() {
    decisionsRef.current = NO_DECISIONS
    setDecisions(NO_DECISIONS)
  }

  function startOver() {
    setPlan(null)
    setResult(null)
    setOptions({})
    resetDecisions()
    void runPlan(NO_DECISIONS)
  }

  async function apply() {
    setApplying(true)
    setError(null)
    try {
      const res = await fetch('/api/imports/artifact/apply', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body(decisions)),
      })
      const json = await res.json().catch(() => null)
      if (!res.ok) {
        setError(json?.message ?? `The correction was not applied (${res.status})`)
        void runPlan(decisions)
        return
      }
      setResult(json as ApplyResult)
    } catch {
      setError('Could not reach the server.')
    } finally {
      setApplying(false)
    }
  }

  const league = leagues?.find((l) => l.id === leagueId)
  const busy = planning || applying
  const decidedCount = plan ? plan.blocking.length : 0
  const changedCars = useMemo(
    () => plan?.rounds.reduce((n, r) => n + r.cars.filter((c) => c.changes.length > 0).length, 0) ?? 0,
    [plan],
  )

  return (
    <dialog
      className="ie"
      ref={dialogRef}
      aria-label="IMSA Esports official results"
      onCancel={(e) => {
        e.preventDefault()
        if (!applying) onClose()
      }}
      onClick={(e) => {
        if (e.target === dialogRef.current && !applying) onClose()
      }}
    >
      <header className="ie-head">
        <div>
          <h2 className="ie-title">IMSA Esports official results</h2>
          <p className="ie-sub">
            Correct imported iRacing rounds with the post-penalty classification and standings from artifactracing.com.
          </p>
        </div>
        <button type="button" className="ie-close" aria-label="Close" disabled={applying} onClick={onClose}>
          ✕
        </button>
      </header>

      <div className="ie-body">
        {error && (
          <p className="error-panel ie-error" role="alert">
            {error}
          </p>
        )}

        {result ? (
          <AppliedSummary result={result} plan={plan} />
        ) : (
          <>
            <div className="ie-setup">
              <label className="ie-field">
                <span className="ie-field-label">Site season</span>
                <select
                  className="ie-select"
                  value={leagueId}
                  disabled={!leagues?.length || busy}
                  onChange={(e) => {
                    const l = leagues?.find((x) => x.id === e.target.value)
                    setLeagueId(e.target.value)
                    if (seasons) setSeasonId(guessSeason(l, seasons))
                    setPlan(null)
                    resetDecisions()
                  }}
                >
                  {leagues === null && <option value="">Loading…</option>}
                  {leagues?.map((l) => (
                    <option key={l.id} value={l.id}>
                      {l.name}
                      {l.active ? ' (current)' : ''}
                    </option>
                  ))}
                </select>
              </label>
              <label className="ie-field ie-field-grow">
                <span className="ie-field-label">Pit Pass season</span>
                <select
                  className="ie-select"
                  value={seasonId ?? ''}
                  disabled={!seasons?.length || busy}
                  onChange={(e) => {
                    setSeasonId(e.target.value === '' ? null : Number(e.target.value))
                    setPlan(null)
                    resetDecisions()
                  }}
                >
                  <option value="">Choose a season…</option>
                  {seasons?.map((s) => (
                    <option key={s.id} value={s.id}>
                      {seasonName(s)}
                      {s.kind === 'QUALIFIER' ? ' (qualifier)' : ''}
                    </option>
                  ))}
                </select>
              </label>
              <label className="ie-check">
                <input
                  type="checkbox"
                  checked={standings}
                  disabled={busy}
                  onChange={(e) => {
                    setStandings(e.target.checked)
                    setPlan(null)
                  }}
                />
                Standings
              </label>
              <button type="button" className="btn" disabled={!league || seasonId == null || busy} onClick={startOver}>
                {planning && !plan ? 'Reading the site…' : plan ? 'Check again' : 'Check against site'}
              </button>
            </div>

            {planning && !plan && (
              <p className="ie-note">Reading every round of {league?.name} and laying it against the season…</p>
            )}

            {plan && (
              <PlanView
                plan={plan}
                decisions={decisions}
                options={options}
                disabled={applying}
                decide={decide}
              />
            )}
          </>
        )}
      </div>

      <footer className="ie-foot">
        {result ? (
          <>
            <span className="ie-status ok">Applied.</span>
            <button type="button" className="btn btn-primary" onClick={onClose}>
              Done
            </button>
          </>
        ) : (
          <>
            <span className={plan?.ready ? 'ie-status ok' : 'ie-status'} role="status">
              {!plan
                ? 'Nothing checked yet.'
                : planning
                  ? 'Re-checking…'
                  : plan.ready
                    ? `Ready: ${changedCars} ${changedCars === 1 ? 'car changes' : 'cars change'} across ${plan.rounds.length} ${plan.rounds.length === 1 ? 'round' : 'rounds'}.`
                    : `${decidedCount} ${decidedCount === 1 ? 'thing' : 'things'} to decide before applying.`}
            </span>
            <button type="button" className="btn" disabled={applying} onClick={onClose}>
              Cancel
            </button>
            <button
              type="button"
              className="btn btn-primary"
              disabled={!plan?.ready || busy}
              onClick={() => void apply()}
            >
              {applying ? 'Applying…' : 'Apply correction'}
            </button>
          </>
        )}
      </footer>
    </dialog>
  )
}

function PlanView({
  plan,
  decisions,
  options,
  disabled,
  decide,
}: {
  plan: Plan
  decisions: Decisions
  options: Record<string, EntryOption[]>
  disabled: boolean
  decide: (update: (d: Decisions) => Decisions) => void
}) {
  const knownClasses = [...new Set(plan.classes.map((c) => c.pitPassClass).filter((c): c is string => !!c))]
  return (
    <>
      {plan.warnings.length > 0 && (
        <ul className="ie-warnings" role="status">
          {plan.warnings.map((w) => (
            <li key={w}>{w}</li>
          ))}
        </ul>
      )}
      {plan.rounds.length === 0 && (
        <p className="empty-state">The site has no published results for this season yet.</p>
      )}
      {plan.rounds.map((r) => (
        <RoundView key={r.siteEventId} round={r} decisions={decisions} options={options} disabled={disabled} decide={decide} />
      ))}

      {plan.teamFolds.length > 0 && (
        <section className="ie-section">
          <h3 className="ie-section-head">
            Team merges <span className="ie-count">{plan.teamFolds.filter((f) => f.folding).length} of {plan.teamFolds.length}</span>
          </h3>
          <p className="ie-hint">
            iRacing team names nothing else uses. A merged name becomes an alias of the registered team, so the next iRacing
            import lands on it.
          </p>
          <ul className="ie-list">
            {plan.teamFolds.map((f) => (
              <li key={f.fromTeamId}>
                <label className="ie-check">
                  <input
                    type="checkbox"
                    checked={f.folding}
                    disabled={disabled}
                    onChange={(e) =>
                      decide((d) => ({
                        ...d,
                        keepTeams: e.target.checked
                          ? d.keepTeams.filter((id) => id !== f.fromTeamId)
                          : [...d.keepTeams, f.fromTeamId],
                      }))
                    }
                  />
                  <span className="ie-from">{f.fromName}</span>
                  <span className="ie-arrow" aria-hidden="true">→</span>
                  <span>{f.toName}</span>
                </label>
              </li>
            ))}
          </ul>
        </section>
      )}

      {plan.classes.length > 0 && (
        <section className="ie-section">
          <h3 className="ie-section-head">Classes</h3>
          <ul className="ie-list">
            {plan.classes.map((c) => (
              <li key={c.siteClass} className="ie-class-row">
                <span className="ie-class-site">{c.siteClass}</span>
                <span className="ie-arrow" aria-hidden="true">→</span>
                <input
                  key={`${c.siteClass}-${c.pitPassClass ?? ''}`}
                  className="ie-input"
                  list="ie-known-classes"
                  aria-label={`Pit Pass class for the site's ${c.siteClass}`}
                  defaultValue={c.pitPassClass ?? ''}
                  placeholder="Pit Pass class"
                  disabled={disabled}
                  onBlur={(e) => {
                    const v = e.target.value.trim()
                    if (v === (c.pitPassClass ?? '')) return
                    decide((d) => {
                      const classMapping = { ...d.classMapping }
                      if (v) classMapping[c.siteClass] = v
                      else delete classMapping[c.siteClass]
                      return { ...d, classMapping }
                    })
                  }}
                />
                <span className="ie-hint">
                  {c.source === 'PAIRED' ? 'from the paired cars' : c.source === 'MAPPED' ? 'your mapping' : 'needs a class'}
                </span>
              </li>
            ))}
          </ul>
          <datalist id="ie-known-classes">
            {knownClasses.map((k) => (
              <option key={k} value={k} />
            ))}
          </datalist>
        </section>
      )}

      {plan.standings.length > 0 && (
        <section className="ie-section">
          <h3 className="ie-section-head">Standings</h3>
          <ul className="ie-list">
            {plan.standings.map((s) => (
              <li key={s.siteClass}>
                <span className="ie-strong">{s.pitPassClass ?? s.siteClass}</span> team championship · {s.rows} cars ·{' '}
                {s.rounds} {s.rounds === 1 ? 'round' : 'rounds'}
                {s.discrepancies.length > 0 && (
                  <ul className="ie-discrepancies">
                    {s.discrepancies.map((d) => (
                      <li key={d}>{d}</li>
                    ))}
                  </ul>
                )}
              </li>
            ))}
          </ul>
          <p className="ie-hint">Replaces these championships wholesale.</p>
        </section>
      )}
    </>
  )
}

function RoundView({
  round,
  decisions,
  options,
  disabled,
  decide,
}: {
  round: RoundPlan
  decisions: Decisions
  options: Record<string, EntryOption[]>
  disabled: boolean
  decide: (update: (d: Decisions) => Decisions) => void
}) {
  const picks = round.cars.filter((c) => needsPick(c, decisions.pairings))
  const nearMisses = round.cars.flatMap((c) =>
    c.lineup.filter((l) => l.match === 'CLOSE').map((l) => ({ car: c, line: l })),
  )
  const changed = round.cars.filter((c) => c.changes.length > 0)
  const open = round.blocking.length > 0 || picks.length > 0 || nearMisses.length > 0

  return (
    <details className="ie-round" open={open}>
      <summary className="ie-round-head">
        <span className="ie-round-no">R{round.round}</span>
        <span className="ie-round-track">{round.track}</span>
        <span className="ie-round-event">
          {round.event ? (
            <>
              → {round.event.name}
              {round.event.roundOrdinal != null && <span className="ie-muted"> · Pit Pass Rd {round.event.roundOrdinal}</span>}
            </>
          ) : (
            <span className="ie-bad">no matching event</span>
          )}
        </span>
        <span className="ie-round-tally">
          {round.blocking.length > 0 ? (
            <span className="ie-bad">{round.blocking.length} to decide</span>
          ) : (
            <span className="ie-muted">
              {changed.length} of {round.cars.length} change
            </span>
          )}
        </span>
      </summary>

      <div className="ie-round-body">
        {round.event == null && round.blocking.map((b) => <p key={b} className="ie-block">{b}</p>)}

        {picks.length > 0 && (
          <div className="ie-block-group">
            <h4 className="ie-sub-head">Pairings to confirm</h4>
            <ul className="ie-list">
              {picks.map((c) => {
                const opts = options[c.siteResultId] ?? []
                const decided = c.siteResultId in decisions.pairings
                const value = decided
                  ? decisions.pairings[c.siteResultId] === null
                    ? LEAVE_OUT
                    : String(decisions.pairings[c.siteResultId])
                  : ''
                const why =
                  c.match === 'CLOSE_DRIVERS'
                    ? 'driver names are a near miss'
                    : c.match === 'CAR_NUMBER'
                      ? 'only the car number matches'
                      : c.match === 'AMBIGUOUS'
                        ? 'several entries share its drivers'
                        : c.match === 'NONE'
                          ? 'no entry shares its drivers or number'
                          : null
                return (
                  <li key={c.siteResultId} className="ie-pick">
                    <div className="ie-pick-car">
                      <span className="ie-num">#{c.carNumber}</span> {c.teamName}
                      <span className="ie-muted"> · {c.drivers.join(', ') || 'no drivers listed'}</span>
                      {why && <span className="ie-why">{why}</span>}
                    </div>
                    <select
                      className="ie-select ie-select-sm"
                      aria-label={`Pit Pass entry for site #${c.carNumber}`}
                      value={value}
                      disabled={disabled}
                      onChange={(e) => {
                        const v = e.target.value
                        decide((d) => {
                          const pairings = { ...d.pairings }
                          if (v === '') delete pairings[c.siteResultId]
                          else pairings[c.siteResultId] = v === LEAVE_OUT ? null : Number(v)
                          return { ...d, pairings }
                        })
                      }}
                    >
                      <option value="">{c.entryId != null ? `Confirm… (proposed #${c.entryCarNumber})` : 'Choose an entry…'}</option>
                      {opts.map((o) => (
                        <option key={o.entryId} value={o.entryId}>
                          Pit Pass #{o.carNumber} {o.teamName}
                        </option>
                      ))}
                      <option value={LEAVE_OUT}>Leave this car out</option>
                    </select>
                  </li>
                )
              })}
            </ul>
          </div>
        )}

        {nearMisses.length > 0 && (
          <div className="ie-block-group">
            <h4 className="ie-sub-head">Near-miss driver names</h4>
            <ul className="ie-list">
              {nearMisses.map(({ car, line }) => {
                const key = `${line.driverId}|${line.siteName}`
                const on = decisions.consolidations.includes(key)
                return (
                  <li key={`${car.siteResultId}-${key}`}>
                    <label className="ie-check">
                      <input
                        type="checkbox"
                        checked={on}
                        disabled={disabled}
                        onChange={(e) =>
                          decide((d) => ({
                            ...d,
                            consolidations: e.target.checked
                              ? [...d.consolidations, key]
                              : d.consolidations.filter((k) => k !== key),
                          }))
                        }
                      />
                      <span>
                        Use the site’s spelling <span className="ie-strong">{line.siteName}</span> for Pit Pass’s{' '}
                        <span className="ie-from">{line.pitPassName}</span>
                        <span className="ie-muted"> · #{car.carNumber}</span>
                      </span>
                    </label>
                  </li>
                )
              })}
            </ul>
          </div>
        )}

        {round.unpairedEntries.length > 0 && (
          <div className="ie-block-group">
            <h4 className="ie-sub-head">Not in the site’s classification</h4>
            <ul className="ie-list">
              {round.unpairedEntries.map((u) => (
                <li key={u.entryId}>
                  <label className="ie-check">
                    <input
                      type="checkbox"
                      checked={u.dropping}
                      disabled={disabled}
                      onChange={(e) =>
                        decide((d) => ({
                          ...d,
                          drops: e.target.checked ? [...d.drops, u.entryId] : d.drops.filter((id) => id !== u.entryId),
                        }))
                      }
                    />
                    <span>
                      Drop Pit Pass <span className="ie-num">#{u.carNumber}</span> {u.teamName}
                      <span className="ie-muted">
                        {' '}
                        · {u.drivers.join(', ') || 'no drivers'}
                        {u.mustDrop ? '' : ' · optional, it has no race result'}
                      </span>
                    </span>
                  </label>
                </li>
              ))}
            </ul>
          </div>
        )}

        {changed.length > 0 ? (
          <table className="ie-changes">
            <thead>
              <tr>
                <th className="num">Pos</th>
                <th>Class</th>
                <th className="num">#</th>
                <th>Team</th>
                <th className="num">In class</th>
                <th>Changes</th>
              </tr>
            </thead>
            <tbody>
              {changed.map((c) => {
                const rest = c.changes.filter((ch) => ch.field !== 'overall' && ch.field !== 'class position')
                const overall = c.changes.find((ch) => ch.field === 'overall')
                return (
                  <tr key={c.siteResultId}>
                    <td className="num">
                      {c.overallPosition}
                      {overall?.from && <span className="ie-was"> was {overall.from}</span>}
                    </td>
                    <td>{c.className}</td>
                    <td className="num">{c.carNumber}</td>
                    <td className="ie-team">{c.teamName}</td>
                    <td className="num">
                      P{c.classPosition}
                      {c.classPositionDelta != null && c.classPositionDelta !== 0 && (
                        <span className={c.classPositionDelta > 0 ? 'ie-delta up' : 'ie-delta down'}>
                          {signed(c.classPositionDelta)}
                        </span>
                      )}
                    </td>
                    <td className="ie-change-list">
                      {rest.map((ch) => (
                        <span key={ch.field} className="ie-change">
                          <span className="ie-change-field">{ch.field}</span> <span className="ie-from">{ch.from ?? '—'}</span>{' '}
                          <span aria-hidden="true">→</span> {ch.to ?? '—'}
                        </span>
                      ))}
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        ) : (
          round.event && <p className="ie-muted ie-quiet">Every car already matches the site.</p>
        )}
      </div>
    </details>
  )
}

function AppliedSummary({ result, plan }: { result: ApplyResult; plan: Plan | null }) {
  const lines: [number, string, string][] = [
    [result.roundsCorrected, 'round corrected', 'rounds corrected'],
    [result.resultsWritten, 'race result written', 'race results written'],
    [result.entriesRenumbered, 'car renumbered', 'cars renumbered'],
    [result.teamsRenamed, 'team name corrected', 'team names corrected'],
    [result.teamsFolded, 'iRacing team merged', 'iRacing teams merged'],
    [result.entriesDropped, 'entry dropped', 'entries dropped'],
    [result.driversConsolidated, 'driver renamed or merged', 'drivers renamed or merged'],
    [result.standingsChampionships, 'standings table replaced', 'standings tables replaced'],
  ]
  return (
    <div className="ie-applied">
      <p className="ie-applied-head">
        {plan ? `${plan.leagueName} applied to ${plan.seasonLabel}.` : 'Correction applied.'}
      </p>
      <ul className="ie-list">
        {lines
          .filter(([n]) => n > 0)
          .map(([n, one, many]) => (
            <li key={many}>
              <span className="ie-num">{n}</span> {n === 1 ? one : many}
            </li>
          ))}
      </ul>
      <p className="ie-hint">
        Re-importing a round from iRacing undoes its correction. Run this again afterwards, or whenever the site changes.
      </p>
    </div>
  )
}
