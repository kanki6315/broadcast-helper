import { useEffect, useMemo, useRef, useState } from 'react'
import './alkamel-import-modal.css'
import ConfirmImportStep from './ConfirmImportStep'
import SeriesEventPicker from './SeriesEventPicker'
import { useSeriesEvents } from '../lib/useSeriesEvents'

/**
 * Fetch from the Al Kamel results site — IMSA's timing provider publishes an
 * open index of every session's results, grids, flags and standings back to
 * 2016 (docs/ALKAMEL_IMPORT.md). Two modes:
 *
 *  - "Past season" plans one year (all recognised series or one), shows what
 *    each weekend would import, then stages the ticked weekends one at a time
 *    so progress is visible and a run can stop between them.
 *  - "Refresh event" reads one event's weekend folder afresh and marks every
 *    file new / updated / unchanged against what was already imported from it
 *    — the during-the-weekend flow, also reachable from the event page.
 *
 * Every request to the site is behind this dialog — nothing polls.
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
  state: 'NEW' | 'UPDATED' | 'UNCHANGED' | null
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
  // The Roar, a test, a prologue: importable, not a round, unticked by default.
  preseason: boolean
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
interface EventPlan {
  eventId: number
  eventName: string
  year: number
  seriesId: number
  seriesName: string
  sourceRef: string | null
  weekend: PlanWeekend | null
  candidates: PlanWeekend[]
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

export type AlKamelMode = 'season' | 'refresh'
type Phase = 'setup' | 'planning' | 'plan' | 'staging' | 'confirm'

interface Options {
  standings: boolean
  entryLists: boolean
  flags: boolean
}

// One tickable file of a weekend, with the session it belongs to.
interface WeekendFile {
  ref: FileRef
  file: PlanFile
  label: string
}

// Every readable file of a weekend, in the order they stage.
function weekendFiles(w: PlanWeekend, opts: Options): WeekendFile[] {
  const out: WeekendFile[] = []
  for (const s of w.sessions) {
    const kinds: [PlanFile | null, string][] = [
      [s.results, 'results'],
      [s.grid, 'grid'],
      [opts.flags ? s.flags : null, 'flags'],
    ]
    for (const [f, noun] of kinds) {
      const format = f?.format
      if (f && format) {
        out.push({
          file: f,
          label: `${s.label} · ${noun}`,
          ref: { path: f.path, kind: f.kind, format, modified: f.modified, sessionStart: s.start, sessionLabel: s.label },
        })
      }
    }
  }
  const entry = w.entryList
  const entryFormat = entry?.format
  if (opts.entryLists && entry && entryFormat) {
    out.push({ file: entry, label: 'Entry list', ref: { path: entry.path, kind: 'ENTRY_LIST', format: entryFormat, modified: entry.modified, sessionStart: null, sessionLabel: null } })
  }
  if (opts.standings && w.finalStandings) {
    for (const f of w.standings) {
      if (f.format) {
        out.push({ file: f, label: `Standings · ${f.name}`, ref: { path: f.path, kind: 'STANDINGS', format: f.format, modified: f.modified, sessionStart: null, sessionLabel: null } })
      }
    }
  }
  return out
}

// A season import takes a weekend's recommended files.
function filesFor(w: PlanWeekend, opts: Options): FileRef[] {
  return weekendFiles(w, opts).filter((x) => x.file.recommended).map((x) => x.ref)
}

function weekendDate(w: PlanWeekend): string | null {
  const starts = w.sessions.map((s) => s.start).sort()
  if (starts.length === 0) return null
  const d = new Date(starts[0])
  return Number.isNaN(d.getTime()) ? null : d.toLocaleDateString(undefined, { day: 'numeric', month: 'short' })
}

// "official", "provisional · amended 2", or nothing for a file whose name says nothing.
function statusText(f: PlanFile): string {
  const status = f.status === 'UNMARKED' ? '' : f.status.toLowerCase()
  const amended = f.amendment > 0 ? `amended ${f.amendment}` : ''
  return [status, amended].filter(Boolean).join(' · ')
}

// "12 standings JSON official" — the sheets a season import ticks, which
// share one format and status (the best copy of each). Null when none.
function standingsChip(w: PlanWeekend): string | null {
  const picked = w.standings.filter((f) => f.recommended)
  if (picked.length === 0) return null
  const first = picked[0]
  const noun = picked.length === 1 ? 'standings' : `${picked.length} standings`
  return [noun, first.format ? fmt(first.format) : '', statusText(first)].filter(Boolean).join(' ')
}

// "Q · CSV official" / "R · JSON official · grid provisional · flags" per
// session — each file with its own publication status — and what nothing reads.
function sessionChip(s: PlanSession): { text: string; gap: string | null } {
  const letter = s.type === 'QUALIFYING' ? 'Q' : 'R'
  const parts: string[] = []
  const gaps: string[] = []
  const withStatus = (label: string, f: PlanFile) => [label, statusText(f)].filter(Boolean).join(' ')
  if (s.results?.format) parts.push(withStatus(fmt(s.results.format), s.results))
  else if (s.results) gaps.push(`results: ${s.results.note ?? 'unreadable'}`)
  else gaps.push('no results')
  if (s.grid?.format) parts.push(withStatus('grid', s.grid))
  else if (s.grid) gaps.push(`grid: ${s.grid.note ?? 'unreadable'}`)
  if (s.flags?.format) parts.push(withStatus('flags', s.flags))
  return { text: `${letter} · ${parts.join(' · ') || '—'}`, gap: gaps.length ? gaps.join('; ') : null }
}

function fmt(format: string): string {
  if (format.endsWith('_JSON')) return 'JSON'
  if (format.endsWith('_CSV')) return 'CSV'
  return 'PDF'
}

function cleanReason(reason: string): string {
  return reason.replace(/^\d{3}\s+[A-Z_]+\s+"?/, '').replace(/"$/, '')
}

const STATE_LABEL: Record<string, string> = { NEW: 'new', UPDATED: 'updated', UNCHANGED: 'unchanged' }

export default function AlKamelImportModal({
  onClose,
  onStaged,
  onCommitted,
  initialMode = 'season',
  initialSeriesId = null,
  initialEventId = null,
}: {
  onClose: () => void
  onStaged: (batchIds: number[], seriesId: number | null, eventId: number | null) => void | Promise<void>
  onCommitted: (r: {
    committedIds: number[]
    leftoverIds: number[]
    seriesId: number | null
    eventId: number | null
  }) => void | Promise<void>
  initialMode?: AlKamelMode
  initialSeriesId?: number | null
  /** Opens straight onto the refresh of this event (the event page's button). */
  initialEventId?: number | null
}) {
  const dialogRef = useRef<HTMLDialogElement>(null)
  const { allSeries, addSeries } = useSeriesEvents()

  const [mode, setMode] = useState<AlKamelMode>(initialMode)
  const [phase, setPhase] = useState<Phase>('setup')
  const [error, setError] = useState<string | null>(null)
  const [opts, setOpts] = useState<Options>({ standings: true, entryLists: true, flags: true })

  // Season mode.
  const [years, setYears] = useState<number[] | null>(null)
  const [year, setYear] = useState<number | null>(null)
  const [seriesId, setSeriesId] = useState<number | null>(initialSeriesId)
  const [plan, setPlan] = useState<YearPlan | null>(null)
  const [picked, setPicked] = useState<Set<string>>(new Set())
  const [looseSeries, setLooseSeries] = useState<Record<string, number>>({})
  const [mapping, setMapping] = useState<Record<string, number>>({})
  const [mappingBusy, setMappingBusy] = useState<string | null>(null)

  // Refresh mode.
  const [refSeriesId, setRefSeriesId] = useState<number | null>(initialSeriesId)
  const [refEventId, setRefEventId] = useState<number | null>(initialEventId)
  const [eventPlan, setEventPlan] = useState<EventPlan | null>(null)
  const [refPicked, setRefPicked] = useState<Set<string>>(new Set())

  // Staging progress (both modes).
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

  // Opened from an event page: go straight to reading the site.
  const autoRef = useRef(false)
  useEffect(() => {
    if (mode === 'refresh' && initialEventId != null && !autoRef.current) {
      autoRef.current = true
      void checkEvent(initialEventId, null)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [mode])

  const idle = phase === 'setup' || phase === 'plan'

  function switchMode(next: AlKamelMode) {
    if (next === mode || !idle) return
    setMode(next)
    setError(null)
    setPhase('setup')
    setFailures([])
  }

  const weekendKey = (w: PlanWeekend) => `${w.sourceEvent}|${w.seriesFolder ?? 'loose'}`

  // --- season -------------------------------------------------------------

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
      // Default tick: weekends with something to read that aren't already here
      // and are rounds — the Roar and the tests wait for a deliberate tick.
      setPicked(new Set(p.weekends
        .filter((w) => !w.error && !w.preseason && w.existingEventId == null && filesFor(w, opts).length > 0)
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

  // --- refresh ------------------------------------------------------------

  async function checkEvent(eventId: number, sourceEvent: string | null) {
    setPhase('planning')
    setError(null)
    setEventPlan(null)
    try {
      const q = sourceEvent ? `?sourceEvent=${encodeURIComponent(sourceEvent)}` : ''
      const res = await fetch(`/api/imports/alkamel/events/${eventId}/plan${q}`)
      const body = await res.json().catch(() => null)
      if (!res.ok) {
        setError(body?.message ?? `Could not read the site (${res.status})`)
        setPhase('setup')
        return
      }
      const p = body as EventPlan
      setEventPlan(p)
      setRefSeriesId(p.seriesId)
      if (p.weekend) {
        setRefPicked(new Set(weekendFiles(p.weekend, opts).filter((x) => x.file.recommended).map((x) => x.file.path)))
      }
      setPhase('plan')
    } catch {
      setError('Could not reach the server.')
      setPhase('setup')
    }
  }

  const refFiles = useMemo(() => (eventPlan?.weekend ? weekendFiles(eventPlan.weekend, opts) : []), [eventPlan, opts])
  const refChosen = refFiles.filter((x) => refPicked.has(x.file.path))

  function toggleRefPick(path: string) {
    setRefPicked((p) => {
      const next = new Set(p)
      if (next.has(path)) next.delete(path)
      else next.add(path)
      return next
    })
  }

  // --- staging (shared) ---------------------------------------------------

  interface Job {
    label: string
    body: { year: number; sourceEvent: string; eventName: string; seriesId: number; files: FileRef[] }
  }

  async function runJobs(jobs: Job[], pinSeries: number | null, pinEvent: number | null) {
    if (!jobs.length) return
    setPhase('staging')
    setError(null)
    cancelRef.current = false
    const ids: number[] = []
    const failed: (Failure & { weekend: string })[] = []
    setBatchIds([])
    setFailures([])
    for (let i = 0; i < jobs.length; i++) {
      const job = jobs[i]
      setProgress({ done: i, total: jobs.length, label: job.label })
      try {
        const res = await fetch('/api/imports/alkamel/stage', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(job.body),
        })
        const body = await res.json().catch(() => null)
        if (!res.ok) {
          failed.push({ path: job.body.sourceEvent, name: job.label, reason: body?.message ?? `Failed (${res.status})`, weekend: job.label })
        } else {
          const r = body as StageResult
          ids.push(...r.batches.map((b) => b.id))
          failed.push(...r.failures.map((f) => ({ ...f, weekend: job.label })))
        }
      } catch {
        failed.push({ path: job.body.sourceEvent, name: job.label, reason: 'Could not reach the server.', weekend: job.label })
      }
      setBatchIds([...ids])
      setFailures([...failed])
      if (cancelRef.current) break
    }
    setProgress(null)
    if (ids.length > 0) {
      await onStaged(ids, pinSeries, pinEvent)
      setPhase('confirm')
    } else {
      setError(failed.length ? 'Nothing could be staged.' : 'Nothing was staged.')
      setPhase('plan')
    }
  }

  function stageSeason() {
    if (!chosen.length || missingSeries.length) return
    void runJobs(
      chosen.map((w) => ({
        label: `${w.eventName}${w.seriesName ? ` · ${w.seriesName}` : ''}`,
        body: { year: w.year, sourceEvent: w.sourceEvent, eventName: w.eventName, seriesId: (w.seriesId ?? looseSeries[weekendKey(w)])!, files: filesFor(w, opts) },
      })),
      seriesId,
      null,
    )
  }

  function stageRefresh() {
    if (!eventPlan?.weekend || !refChosen.length) return
    const w = eventPlan.weekend
    void runJobs(
      [{ label: eventPlan.eventName, body: { year: w.year, sourceEvent: w.sourceEvent, eventName: eventPlan.eventName, seriesId: eventPlan.seriesId, files: refChosen.map((x) => x.ref) } }],
      eventPlan.seriesId,
      eventPlan.eventId,
    )
  }

  const pinnedEvent = mode === 'refresh' ? eventPlan?.eventId ?? null : null
  const pinnedSeries = mode === 'refresh' ? eventPlan?.seriesId ?? refSeriesId : seriesId

  const failuresPanel = failures.length > 0 && (
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
  )

  const optionsRow = (
    <div className="ak-options">
      <label className="ak-check">
        <input type="checkbox" checked={opts.standings} disabled={phase === 'staging'} onChange={(e) => setOpts({ ...opts, standings: e.target.checked })} />
        {mode === 'season' ? 'Final standings' : 'Standings'}
      </label>
      <label className="ak-check">
        <input type="checkbox" checked={opts.entryLists} disabled={phase === 'staging'} onChange={(e) => setOpts({ ...opts, entryLists: e.target.checked })} />
        Entry lists
      </label>
      <label className="ak-check">
        <input type="checkbox" checked={opts.flags} disabled={phase === 'staging'} onChange={(e) => setOpts({ ...opts, flags: e.target.checked })} />
        Flags
      </label>
      {mode === 'season' && plan && (
        <span className="ak-count">
          {plan.weekends.length === 0
            ? 'No weekends for this series.'
            : `${chosen.length} of ${runnable.length} weekend${runnable.length === 1 ? '' : 's'} ticked · ${chosenFiles} file${chosenFiles === 1 ? '' : 's'}`}
        </span>
      )}
      {mode === 'refresh' && eventPlan?.weekend && (
        <span className="ak-count">{refChosen.length} of {refFiles.length} file{refFiles.length === 1 ? '' : 's'} ticked</span>
      )}
    </div>
  )

  const progressRow = phase === 'staging' && progress && (
    <div className="ak-progress" role="status">
      <div className="ak-progress-bar">
        <div className="ak-progress-fill" style={{ transform: `scaleX(${progress.done / progress.total})` }} />
      </div>
      <span className="ak-progress-text">
        Staging {progress.label} ({progress.done + 1} of {progress.total})…
      </span>
      {progress.total > 1 && (
        <button type="button" className="btn btn-sm" onClick={() => { cancelRef.current = true }}>
          Stop after this one
        </button>
      )}
    </div>
  )

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
            {mode === 'season'
              ? 'Import a past season from the timing provider’s results site, weekend by weekend.'
              : 'Read an event’s weekend folder on the timing provider’s site and import what is new or changed.'}
          </p>
        </div>
        <button type="button" className="ak-close" aria-label="Close" disabled={phase === 'staging'} onClick={onClose}>
          ✕
        </button>
      </header>

      <div className="ak-body">
        {phase !== 'confirm' && (
          <div className="ak-modes" role="tablist" aria-label="What to fetch">
            <button type="button" role="tab" aria-selected={mode === 'season'} className={mode === 'season' ? 'ak-mode on' : 'ak-mode'} disabled={!idle} onClick={() => switchMode('season')}>
              Past season
            </button>
            <button type="button" role="tab" aria-selected={mode === 'refresh'} className={mode === 'refresh' ? 'ak-mode on' : 'ak-mode'} disabled={!idle} onClick={() => switchMode('refresh')}>
              Refresh event
            </button>
          </div>
        )}

        {error && (
          <p className="error-panel ak-error" role="alert">
            {error}
          </p>
        )}

        {phase === 'confirm' ? (
          <>
            {failuresPanel}
            <ConfirmImportStep
              batchIds={batchIds}
              pinnedSeriesId={pinnedSeries}
              pinnedEventId={pinnedEvent}
              onCommitted={onCommitted}
              onBack={() => setPhase('plan')}
              onDone={onClose}
            />
          </>
        ) : mode === 'season' ? (
          <>
            <div className="ak-setup">
              <label className="ak-field">
                <span className="ak-field-label">Season</span>
                <select className="ak-select" value={year ?? ''} disabled={!years?.length || !idle} onChange={(e) => setYear(Number(e.target.value))}>
                  {years === null && <option value="">Loading…</option>}
                  {years?.map((y) => (
                    <option key={y} value={y}>{y}</option>
                  ))}
                </select>
              </label>
              <label className="ak-field ak-field-grow">
                <span className="ak-field-label">Series</span>
                <select className="ak-select" value={seriesId ?? ''} disabled={!idle} onChange={(e) => setSeriesId(e.target.value === '' ? null : Number(e.target.value))}>
                  <option value="">All recognised series</option>
                  {allSeries?.map((s) => (
                    <option key={s.id} value={s.id}>{s.name}</option>
                  ))}
                </select>
              </label>
              <button type="button" className="btn" disabled={year == null || !idle} onClick={() => void buildPlan()}>
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
                          <select className="ak-select ak-select-sm" value={mapping[folder] ?? ''} disabled={mappingBusy != null || !idle} onChange={(e) => setMapping((m) => ({ ...m, [folder]: Number(e.target.value) }))}>
                            <option value="">Choose a series…</option>
                            {allSeries?.map((s) => (
                              <option key={s.id} value={s.id}>{s.name}</option>
                            ))}
                          </select>
                          <button type="button" className="btn btn-sm" disabled={mapping[folder] == null || mappingBusy != null || !idle} onClick={() => void mapFolder(folder)}>
                            {mappingBusy === folder ? 'Mapping…' : 'Map'}
                          </button>
                        </li>
                      ))}
                    </ul>
                  </div>
                )}

                {optionsRow}

                {plan.weekends.length > 0 && (
                  <ul className="ak-weekends" aria-label="Weekends">
                    {plan.weekends.map((w) => {
                      const key = weekendKey(w)
                      const runnableRow = !w.error && filesFor(w, opts).length > 0
                      const on = runnableRow && picked.has(key)
                      return (
                        <li key={key} className={runnableRow ? 'ak-weekend' : 'ak-weekend off'}>
                          <label className="ak-weekend-pick">
                            <input type="checkbox" checked={on} disabled={!runnableRow || !idle} onChange={() => togglePick(key)} aria-label={`${w.eventName} ${w.seriesName ?? ''}`} />
                          </label>
                          <div className="ak-weekend-main">
                            <div className="ak-weekend-title">
                              <span className="ak-weekend-name">{w.eventName}</span>
                              {weekendDate(w) && <span className="ak-weekend-date">{weekendDate(w)}</span>}
                              {w.existingEventId != null && <span className="badge ak-badge">imported</span>}
                              {w.preseason && <span className="badge ak-badge" title="Not a round: imports as its own event without a round number">pre-season</span>}
                              {w.f1Weekend && <span className="badge ak-badge">F1 weekend</span>}
                            </div>
                            <div className="ak-weekend-meta">
                              {w.loose ? (
                                <select className="ak-select ak-select-sm" value={looseSeries[key] ?? ''} disabled={!idle} onChange={(e) => setLooseSeries((m) => ({ ...m, [key]: Number(e.target.value) }))} aria-label="Series for this weekend">
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
                                {w.entryList && opts.entryLists && (
                                  <span className="ak-chip">{['entry list', statusText(w.entryList)].filter(Boolean).join(' ')}</span>
                                )}
                                {w.finalStandings && opts.standings && standingsChip(w) && (
                                  <span className="ak-chip" title={w.standings.filter((f) => f.recommended).map((f) => f.name).join(', ')}>
                                    {standingsChip(w)}
                                  </span>
                                )}
                              </div>
                            )}
                          </div>
                        </li>
                      )
                    })}
                  </ul>
                )}

                {progressRow}
                {phase !== 'staging' && failuresPanel}

                <div className="ak-actions">
                  <span className="ak-count">
                    {missingSeries.length > 0
                      ? `${missingSeries.length} ticked weekend${missingSeries.length === 1 ? ' needs' : 's need'} a series.`
                      : 'Staged files go to the confirm step next; nothing commits until you confirm.'}
                  </span>
                  <button type="button" className="btn-primary" disabled={!chosen.length || missingSeries.length > 0 || !idle} onClick={stageSeason}>
                    {phase === 'staging' ? 'Staging…' : `Fetch & stage ${chosen.length} weekend${chosen.length === 1 ? '' : 's'}`}
                  </button>
                </div>
              </>
            )}
          </>
        ) : (
          <>
            <div className="ak-pin">
              <SeriesEventPicker
                idPrefix="ak"
                seriesId={refSeriesId}
                eventId={refEventId}
                required
                autoLabel="Choose the event to refresh"
                onSeriesChange={(id) => { setRefSeriesId(id); setRefEventId(null); setEventPlan(null) }}
                onEventChange={(id) => { setRefEventId(id); setEventPlan(null) }}
                onError={setError}
              />
              <div className="ak-actions ak-actions-flat">
                <span className="ak-count">Reads the event’s folder afresh each time.</span>
                <button type="button" className="btn" disabled={refEventId == null || !idle} onClick={() => refEventId != null && void checkEvent(refEventId, null)}>
                  {phase === 'planning' ? 'Reading the site…' : eventPlan ? 'Check again' : 'Check the site'}
                </button>
              </div>
            </div>

            {eventPlan && !eventPlan.weekend && (
              <div className="ak-unmatched">
                <p className="ak-unmatched-head">
                  {eventPlan.eventName} isn’t linked to a folder on the site yet. Pick its weekend — the link is kept once these files commit:
                </p>
                {eventPlan.candidates.length === 0 ? (
                  <p className="ak-note">No {eventPlan.year} {eventPlan.seriesName} weekends were found on the site.</p>
                ) : (
                  <ul>
                    {eventPlan.candidates.map((c) => (
                      <li key={c.sourceEvent} className="ak-unmatched-row">
                        <span className="ak-unmatched-name">
                          {c.eventName}
                          {weekendDate(c) && <span className="ak-weekend-date"> · {weekendDate(c)}</span>}
                        </span>
                        <button type="button" className="btn btn-sm" disabled={!idle} onClick={() => void checkEvent(eventPlan.eventId, c.sourceEvent)}>
                          Use this folder
                        </button>
                      </li>
                    ))}
                  </ul>
                )}
              </div>
            )}

            {eventPlan?.weekend && (
              <>
                {optionsRow}
                <ul className="ak-files" aria-label="Files">
                  {refFiles.map((x) => (
                    <li key={x.file.path} className={x.file.state === 'UNCHANGED' ? 'ak-file off' : 'ak-file'}>
                      <label className="ak-weekend-pick">
                        <input type="checkbox" checked={refPicked.has(x.file.path)} disabled={!idle} onChange={() => toggleRefPick(x.file.path)} aria-label={x.label} />
                      </label>
                      <div className="ak-weekend-main">
                        <div className="ak-weekend-title">
                          <span className="ak-weekend-name">{x.label}</span>
                          {statusText(x.file) && (
                            <span className={`ak-chip status-${x.file.status.toLowerCase()}`}>{statusText(x.file)}</span>
                          )}
                          {x.file.state && <span className={`ak-chip state-${x.file.state.toLowerCase()}`}>{STATE_LABEL[x.file.state]}</span>}
                        </div>
                        <div className="ak-weekend-meta">
                          <span>{x.file.name}</span>
                          {x.file.status === 'UNMARKED' && <span>no status in the name</span>}
                          {x.file.modified && <span>posted {x.file.modified}</span>}
                          {x.file.note && <span>{x.file.note}</span>}
                        </div>
                      </div>
                    </li>
                  ))}
                  {refFiles.length === 0 && <li className="ak-note">Nothing readable in this folder yet.</li>}
                </ul>
                {eventPlan.weekend.sessions.some((s) => (s.results && !s.results.format) || (s.grid && !s.grid.format)) && (
                  <p className="ak-note">
                    Some files exist only in a format nothing reads:{' '}
                    {eventPlan.weekend.sessions.flatMap((s) => [s.results, s.grid, s.flags]).filter((f): f is PlanFile => !!f && !f.format).map((f) => `${f.name} (${f.note})`).join('; ')}
                  </p>
                )}
                {progressRow}
                {phase !== 'staging' && failuresPanel}
                <div className="ak-actions">
                  <span className="ak-count">Staged files go to the confirm step, pinned to {eventPlan.eventName}.</span>
                  <button type="button" className="btn-primary" disabled={!refChosen.length || !idle} onClick={stageRefresh}>
                    {phase === 'staging' ? 'Staging…' : `Fetch & stage ${refChosen.length} file${refChosen.length === 1 ? '' : 's'}`}
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
