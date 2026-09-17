import { useEffect, useMemo, useRef, useState } from 'react'
import './alkamel-import-modal.css'
import ConfirmImportStep from './ConfirmImportStep'
import { useSeriesEvents } from '../lib/useSeriesEvents'

/**
 * Fetch from the Al Kamel results site — IMSA's timing provider publishes an
 * open index of every session's results, grids, flags and standings back to
 * 2016 (docs/ALKAMEL_IMPORT.md). "Past season" plans one year (all recognised
 * series or one), shows what each weekend would import, then stages the ticked
 * weekends one at a time so progress is visible and a run can stop between
 * them. Every request to the site is behind this button — nothing polls.
 */

interface PlanFile {
  path: string
  name: string
  kind: string
  format: string | null
  status: string
  amendment: number
  modified: string | null
  recommended: boolean
  note: string | null
}
interface PlanSession {
  path: string
  start: string
  label: string
  type: string
  results: PlanFile | null
  grid: PlanFile | null
  flags: PlanFile | null
}
interface PlanWeekend {
  sourceEvent: string
  eventPath: string
  eventName: string
  year: number
  seriesFolder: string | null
  seriesId: number | null
  seriesName: string | null
  loose: boolean
  f1Weekend: boolean
  existingEventId: number | null
  sessions: PlanSession[]
  standings: PlanFile[]
  finalStandings: boolean
  entryList: PlanFile | null
  error: string | null
}
interface YearPlan {
  year: number
  weekends: PlanWeekend[]
  unmatchedSeriesFolders: string[]
}
interface StagedBatch {
  id: number
  kind: string
  filename: string
  summary: string | null
}
interface Failure {
  path: string
  name: string
  reason: string
}
interface StageResult {
  requested: number
  staged: number
  batches: StagedBatch[]
  failures: Failure[]
}
interface FileRef {
  path: string
  kind: string
  format: string
  modified: string | null
  sessionStart: string | null
  sessionLabel: string | null
}

type Phase = 'setup' | 'planning' | 'plan' | 'staging' | 'confirm'

interface Options {
  standings: boolean
  entryLists: boolean
  flags: boolean
}

// The files a weekend stages under the current options.
function filesFor(w: PlanWeekend, opts: Options): FileRef[] {
  const out: FileRef[] = []
  for (const s of w.sessions) {
    for (const f of [s.results, s.grid, opts.flags ? s.flags : null]) {
      if (f?.format) {
        out.push({ path: f.path, kind: f.kind, format: f.format, modified: f.modified, sessionStart: s.start, sessionLabel: s.label })
      }
    }
  }
  if (opts.entryLists && w.entryList?.format) {
    out.push({ path: w.entryList.path, kind: 'ENTRY_LIST', format: w.entryList.format, modified: w.entryList.modified, sessionStart: null, sessionLabel: null })
  }
  if (opts.standings && w.finalStandings) {
    for (const f of w.standings) {
      if (f.recommended && f.format) {
        out.push({ path: f.path, kind: 'STANDINGS', format: f.format, modified: f.modified, sessionStart: null, sessionLabel: null })
      }
    }
  }
  return out
}

function weekendDate(w: PlanWeekend): string | null {
  const starts = w.sessions.map((s) => s.start).sort()
  if (starts.length === 0) return null
  const d = new Date(starts[0])
  return Number.isNaN(d.getTime()) ? null : d.toLocaleDateString(undefined, { day: 'numeric', month: 'short' })
}

// "Q · CSV" / "R · JSON +grid +flags" per session, and what nothing reads.
function sessionChip(s: PlanSession): { text: string; gap: string | null } {
  const letter = s.type === 'QUALIFYING' ? 'Q' : 'R'
  const parts: string[] = []
  const gaps: string[] = []
  if (s.results?.format) parts.push(fmt(s.results.format))
  else if (s.results) gaps.push(`results: ${s.results.note ?? 'unreadable'}`)
  else gaps.push('no results')
  if (s.grid?.format) parts.push('+grid')
  else if (s.grid) gaps.push(`grid: ${s.grid.note ?? 'unreadable'}`)
  if (s.flags?.format) parts.push('+flags')
  return { text: `${letter} · ${parts.join(' ') || '—'}`, gap: gaps.length ? gaps.join('; ') : null }
}

function fmt(format: string): string {
  if (format.endsWith('_JSON')) return 'JSON'
  if (format.endsWith('_CSV')) return 'CSV'
  return 'PDF'
}

function cleanReason(reason: string): string {
  return reason.replace(/^\d{3}\s+[A-Z_]+\s+"?/, '').replace(/"$/, '')
}

export default function AlKamelImportModal({
  onClose,
  onStaged,
  onCommitted,
}: {
  onClose: () => void
  onStaged: (batchIds: number[], seriesId: number | null, eventId: number | null) => void | Promise<void>
  onCommitted: (r: {
    committedIds: number[]
    leftoverIds: number[]
    seriesId: number | null
    eventId: number | null
  }) => void | Promise<void>
}) {
  const dialogRef = useRef<HTMLDialogElement>(null)
  const { allSeries, addSeries } = useSeriesEvents()

  const [years, setYears] = useState<number[] | null>(null)
  const [year, setYear] = useState<number | null>(null)
  const [seriesId, setSeriesId] = useState<number | null>(null)
  const [phase, setPhase] = useState<Phase>('setup')
  const [error, setError] = useState<string | null>(null)
  const [plan, setPlan] = useState<YearPlan | null>(null)
  const [picked, setPicked] = useState<Set<string>>(new Set())
  const [looseSeries, setLooseSeries] = useState<Record<string, number>>({})
  const [opts, setOpts] = useState<Options>({ standings: true, entryLists: true, flags: true })
  const [mapping, setMapping] = useState<Record<string, number>>({})
  const [mappingBusy, setMappingBusy] = useState<string | null>(null)

  // Staging progress.
  const [progress, setProgress] = useState<{ done: number; total: number; label: string } | null>(null)
  const [batchIds, setBatchIds] = useState<number[]>([])
  const [failures, setFailures] = useState<(Failure & { weekend: string })[]>([])
  const cancelRef = useRef(false)

  useEffect(() => {
    const d = dialogRef.current
    if (d && !d.open) d.showModal()
  }, [])

  useEffect(() => {
    void (async () => {
      try {
        const res = await fetch('/api/imports/alkamel/years')
        const body = await res.json().catch(() => null)
        if (!res.ok) {
          setError(body?.message ?? `Could not list seasons (${res.status})`)
          setYears([])
          return
        }
        const list = body as number[]
        setYears(list)
        setYear(list.length ? list[list.length - 1] : null)
      } catch {
        setError('Could not reach the server.')
        setYears([])
      }
    })()
  }, [])

  const weekendKey = (w: PlanWeekend) => `${w.sourceEvent}|${w.seriesFolder ?? 'loose'}`

  async function buildPlan() {
    if (year == null) return
    setPhase('planning')
    setError(null)
    setPlan(null)
    try {
      const q = seriesId != null ? `&seriesId=${seriesId}` : ''
      const res = await fetch(`/api/imports/alkamel/plan?year=${year}${q}`)
      const body = await res.json().catch(() => null)
      if (!res.ok) {
        setError(body?.message ?? `Could not plan ${year} (${res.status})`)
        setPhase('setup')
        return
      }
      const p = body as YearPlan
      setPlan(p)
      // Default tick: weekends with something to read that aren't already here.
      setPicked(new Set(p.weekends
        .filter((w) => !w.error && w.existingEventId == null && filesFor(w, opts).length > 0)
        .map(weekendKey)))
      const loose: Record<string, number> = {}
      if (seriesId != null) for (const w of p.weekends) if (w.loose) loose[weekendKey(w)] = seriesId
      setLooseSeries(loose)
      setPhase('plan')
    } catch {
      setError('Could not reach the server.')
      setPhase('setup')
    }
  }

  async function mapFolder(folder: string) {
    const target = mapping[folder]
    if (target == null) return
    setMappingBusy(folder)
    setError(null)
    try {
      const res = await fetch(`/api/series/${target}/aliases`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ alias: folder }),
      })
      const body = await res.json().catch(() => null)
      if (!res.ok) {
        setError(body?.message ?? `Could not add the alias (${res.status})`)
        return
      }
      if (body?.id != null) addSeries(body)
      await buildPlan()
    } catch {
      setError('Could not reach the server.')
    } finally {
      setMappingBusy(null)
    }
  }

  function togglePick(key: string) {
    setPicked((p) => {
      const next = new Set(p)
      if (next.has(key)) next.delete(key)
      else next.add(key)
      return next
    })
  }

  const runnable = useMemo(() => (plan?.weekends ?? []).filter((w) => !w.error && filesFor(w, opts).length > 0), [plan, opts])
  const chosen = runnable.filter((w) => picked.has(weekendKey(w)))
  const chosenFiles = chosen.reduce((n, w) => n + filesFor(w, opts).length, 0)
  const missingSeries = chosen.filter((w) => w.seriesId == null && looseSeries[weekendKey(w)] == null)

  async function stageAll() {
    if (!chosen.length || missingSeries.length) return
    setPhase('staging')
    setError(null)
    cancelRef.current = false
    const ids: number[] = []
    const failed: (Failure & { weekend: string })[] = []
    setBatchIds([])
    setFailures([])
    for (let i = 0; i < chosen.length; i++) {
      const w = chosen[i]
      const label = `${w.eventName}${w.seriesName ? ` · ${w.seriesName}` : ''}`
      setProgress({ done: i, total: chosen.length, label })
      const sid = w.seriesId ?? looseSeries[weekendKey(w)]
      try {
        const res = await fetch('/api/imports/alkamel/stage', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ year: w.year, sourceEvent: w.sourceEvent, eventName: w.eventName, seriesId: sid, files: filesFor(w, opts) }),
        })
        const body = await res.json().catch(() => null)
        if (!res.ok) {
          failed.push({ path: w.eventPath, name: label, reason: body?.message ?? `Failed (${res.status})`, weekend: label })
        } else {
          const r = body as StageResult
          ids.push(...r.batches.map((b) => b.id))
          failed.push(...r.failures.map((f) => ({ ...f, weekend: label })))
        }
      } catch {
        failed.push({ path: w.eventPath, name: label, reason: 'Could not reach the server.', weekend: label })
      }
      setBatchIds([...ids])
      setFailures([...failed])
      if (cancelRef.current) break
    }
    setProgress(null)
    if (ids.length > 0) {
      await onStaged(ids, seriesId, null)
      setPhase('confirm')
    } else {
      setError(failed.length ? 'Nothing could be staged.' : 'Nothing was staged.')
      setPhase('plan')
    }
  }

  return (
    <dialog
      className="ak"
      ref={dialogRef}
      aria-label="Fetch from Al Kamel"
      onCancel={(e) => {
        e.preventDefault()
        if (phase !== 'staging') onClose()
      }}
      onClick={(e) => {
        if (e.target === dialogRef.current && phase !== 'staging') onClose()
      }}
    >
      <header className="ak-head">
        <div>
          <h2 className="ak-title">Fetch from Al Kamel</h2>
          <p className="ak-sub">
            Import a past season from the timing provider's results site, weekend by weekend.
          </p>
        </div>
        <button type="button" className="ak-close" aria-label="Close" disabled={phase === 'staging'} onClick={onClose}>
          ✕
        </button>
      </header>

      <div className="ak-body">
        {error && (
          <p className="error-panel ak-error" role="alert">
            {error}
          </p>
        )}

        {phase === 'confirm' ? (
          <>
            {failures.length > 0 && (
              <div className="ak-failures" role="alert">
                <p className="ak-failures-head">{failures.length} file{failures.length === 1 ? '' : 's'} couldn’t be staged</p>
                <ul>
                  {failures.map((f, i) => (
                    <li key={`${f.path}-${i}`}>
                      <span className="ak-failure-weekend">{f.weekend}</span> {f.name} — {cleanReason(f.reason)}
                    </li>
                  ))}
                </ul>
              </div>
            )}
            <ConfirmImportStep
              batchIds={batchIds}
              pinnedSeriesId={seriesId}
              pinnedEventId={null}
              onCommitted={onCommitted}
              onBack={() => setPhase('plan')}
              onDone={onClose}
            />
          </>
        ) : (
          <>
            <div className="ak-setup">
              <label className="ak-field">
                <span className="ak-field-label">Season</span>
                <select
                  className="ak-select"
                  value={year ?? ''}
                  disabled={!years?.length || phase !== 'setup' && phase !== 'plan'}
                  onChange={(e) => setYear(Number(e.target.value))}
                >
                  {years === null && <option value="">Loading…</option>}
                  {years?.map((y) => (
                    <option key={y} value={y}>{y}</option>
                  ))}
                </select>
              </label>
              <label className="ak-field ak-field-grow">
                <span className="ak-field-label">Series</span>
                <select
                  className="ak-select"
                  value={seriesId ?? ''}
                  disabled={phase !== 'setup' && phase !== 'plan'}
                  onChange={(e) => setSeriesId(e.target.value === '' ? null : Number(e.target.value))}
                >
                  <option value="">All recognised series</option>
                  {allSeries?.map((s) => (
                    <option key={s.id} value={s.id}>{s.name}</option>
                  ))}
                </select>
              </label>
              <button
                type="button"
                className="btn"
                disabled={year == null || phase === 'planning' || phase === 'staging'}
                onClick={() => void buildPlan()}
              >
                {phase === 'planning' ? 'Reading the site…' : plan ? 'Re-plan' : 'Build plan'}
              </button>
            </div>

            {phase === 'planning' && (
              <p className="ak-note">Listing {year}’s folders — a whole season is a minute or so; the site is read one folder at a time.</p>
            )}

            {plan && phase !== 'planning' && (
              <>
                {plan.unmatchedSeriesFolders.length > 0 && (
                  <div className="ak-unmatched">
                    <p className="ak-unmatched-head">
                      Series folders that match nothing here — map each to a series (this records an alias) or leave it out:
                    </p>
                    <ul>
                      {plan.unmatchedSeriesFolders.map((folder) => (
                        <li key={folder} className="ak-unmatched-row">
                          <span className="ak-unmatched-name">{folder}</span>
                          <select
                            className="ak-select ak-select-sm"
                            value={mapping[folder] ?? ''}
                            disabled={mappingBusy != null || phase === 'staging'}
                            onChange={(e) => setMapping((m) => ({ ...m, [folder]: Number(e.target.value) }))}
                          >
                            <option value="">Choose a series…</option>
                            {allSeries?.map((s) => (
                              <option key={s.id} value={s.id}>{s.name}</option>
                            ))}
                          </select>
                          <button
                            type="button"
                            className="btn btn-sm"
                            disabled={mapping[folder] == null || mappingBusy != null || phase === 'staging'}
                            onClick={() => void mapFolder(folder)}
                          >
                            {mappingBusy === folder ? 'Mapping…' : 'Map'}
                          </button>
                        </li>
                      ))}
                    </ul>
                  </div>
                )}

                <div className="ak-options">
                  <label className="ak-check">
                    <input type="checkbox" checked={opts.standings} disabled={phase === 'staging'} onChange={(e) => setOpts({ ...opts, standings: e.target.checked })} />
                    Final standings
                  </label>
                  <label className="ak-check">
                    <input type="checkbox" checked={opts.entryLists} disabled={phase === 'staging'} onChange={(e) => setOpts({ ...opts, entryLists: e.target.checked })} />
                    Entry lists
                  </label>
                  <label className="ak-check">
                    <input type="checkbox" checked={opts.flags} disabled={phase === 'staging'} onChange={(e) => setOpts({ ...opts, flags: e.target.checked })} />
                    Flags
                  </label>
                  <span className="ak-count">
                    {plan.weekends.length === 0
                      ? 'No weekends for this series.'
                      : `${chosen.length} of ${runnable.length} weekend${runnable.length === 1 ? '' : 's'} ticked · ${chosenFiles} file${chosenFiles === 1 ? '' : 's'}`}
                  </span>
                </div>

                {plan.weekends.length > 0 && (
                  <ul className="ak-weekends" aria-label="Weekends">
                    {plan.weekends.map((w) => {
                      const key = weekendKey(w)
                      const files = filesFor(w, opts)
                      const runnableRow = !w.error && files.length > 0
                      const on = runnableRow && picked.has(key)
                      return (
                        <li key={key} className={runnableRow ? 'ak-weekend' : 'ak-weekend off'}>
                          <label className="ak-weekend-pick">
                            <input
                              type="checkbox"
                              checked={on}
                              disabled={!runnableRow || phase === 'staging'}
                              onChange={() => togglePick(key)}
                              aria-label={`${w.eventName} ${w.seriesName ?? ''}`}
                            />
                          </label>
                          <div className="ak-weekend-main">
                            <div className="ak-weekend-title">
                              <span className="ak-weekend-name">{w.eventName}</span>
                              {weekendDate(w) && <span className="ak-weekend-date">{weekendDate(w)}</span>}
                              {w.existingEventId != null && <span className="badge ak-badge">imported</span>}
                              {w.f1Weekend && <span className="badge ak-badge">F1 weekend</span>}
                              {w.finalStandings && opts.standings && <span className="badge ak-badge">standings</span>}
                            </div>
                            <div className="ak-weekend-meta">
                              {w.loose ? (
                                <select
                                  className="ak-select ak-select-sm"
                                  value={looseSeries[key] ?? ''}
                                  disabled={phase === 'staging'}
                                  onChange={(e) => setLooseSeries((m) => ({ ...m, [key]: Number(e.target.value) }))}
                                  aria-label="Series for this weekend"
                                >
                                  <option value="">No series folder — choose one…</option>
                                  {allSeries?.map((s) => (
                                    <option key={s.id} value={s.id}>{s.name}</option>
                                  ))}
                                </select>
                              ) : (
                                <span className="ak-weekend-series">{w.seriesName ?? w.seriesFolder}</span>
                              )}
                              {w.error && <span className="ak-weekend-error">{w.error}</span>}
                              {!w.error && w.sessions.length === 0 && <span className="ak-weekend-error">no qualifying or race sessions</span>}
                            </div>
                            {w.sessions.length > 0 && (
                              <div className="ak-sessions">
                                {w.sessions.map((s) => {
                                  const chip = sessionChip(s)
                                  return (
                                    <span key={s.path} className={chip.gap ? 'ak-chip gap' : 'ak-chip'} title={chip.gap ?? s.label}>
                                      {chip.text}
                                    </span>
                                  )
                                })}
                                {w.entryList && opts.entryLists && <span className="ak-chip">entry list</span>}
                              </div>
                            )}
                          </div>
                        </li>
                      )
                    })}
                  </ul>
                )}

                {phase === 'staging' && progress && (
                  <div className="ak-progress" role="status">
                    <div className="ak-progress-bar">
                      <div className="ak-progress-fill" style={{ transform: `scaleX(${progress.done / progress.total})` }} />
                    </div>
                    <span className="ak-progress-text">
                      Staging {progress.label} ({progress.done + 1} of {progress.total})…
                    </span>
                    <button type="button" className="btn btn-sm" onClick={() => { cancelRef.current = true }}>
                      Stop after this one
                    </button>
                  </div>
                )}

                {failures.length > 0 && phase !== 'staging' && (
                  <div className="ak-failures" role="alert">
                    <p className="ak-failures-head">{failures.length} file{failures.length === 1 ? '' : 's'} couldn’t be staged</p>
                    <ul>
                      {failures.map((f, i) => (
                        <li key={`${f.path}-${i}`}>
                          <span className="ak-failure-weekend">{f.weekend}</span> {f.name} — {cleanReason(f.reason)}
                        </li>
                      ))}
                    </ul>
                  </div>
                )}

                <div className="ak-actions">
                  <span className="ak-count">
                    {missingSeries.length > 0
                      ? `${missingSeries.length} ticked weekend${missingSeries.length === 1 ? ' needs' : 's need'} a series.`
                      : 'Staged files go to the confirm step next; nothing commits until you confirm.'}
                  </span>
                  <button
                    type="button"
                    className="btn-primary"
                    disabled={!chosen.length || missingSeries.length > 0 || phase === 'staging'}
                    onClick={() => void stageAll()}
                  >
                    {phase === 'staging' ? 'Staging…' : `Fetch & stage ${chosen.length} weekend${chosen.length === 1 ? '' : 's'}`}
                  </button>
                </div>
              </>
            )}
          </>
        )}
      </div>
    </dialog>
  )
}
