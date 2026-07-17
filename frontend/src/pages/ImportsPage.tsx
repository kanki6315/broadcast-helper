import { Fragment, useEffect, useRef, useState } from 'react'
import IRacingImportModal from '../components/IRacingImportModal'

interface ImportBatch {
  id: number
  kind: string
  format: string
  filename: string
  status: string
  summary: string | null
  createdAt: string
}

interface SeriesOption {
  id: number
  name: string
  abbreviation: string | null
}
interface EventOption {
  id: number
  name: string
  eventDate: string | null
}
interface TargetGuess {
  seriesId: number | null
  seriesName: string | null
  seasonYear: number | null
  eventId: number | null
  eventName: string | null
  circuit: string | null
  eventDate: string | null
  classCode: string | null
  kind: string | null
  isCup: boolean | null
  familyName: string | null
}
interface ClassReview {
  knownClasses: string[]
  unknownClasses: string[]
}
interface ImportReview {
  kind: string
  guess: TargetGuess | null
  seriesOptions: SeriesOption[]
  eventOptions: EventOption[]
  classReview: ClassReview
  needsSession: boolean
}

// The reviewer's editable choices for one batch, seeded from the guess.
interface TargetState {
  seriesId: number | 'new' | ''
  newSeriesName: string
  eventId: number | 'new' | ''
  classCode: string
  kind: string
  isCup: boolean
  familyName: string
  seasonYear: string
  sessionType: string
  sessionOrdinal: number
  classMapping: Record<string, string>
}

function initTarget(r: ImportReview): TargetState {
  const g = r.guess
  return {
    seriesId: g?.seriesId ?? '',
    newSeriesName: '',
    // A metadata-less file must attach to an existing event; force a choice.
    eventId: g?.eventId ?? (r.needsSession ? '' : 'new'),
    classCode: g?.classCode ?? '',
    // The guess derives the kind from the title's last word, which is garbage
    // for a title the shape wasn't built for ("(OVERALL)", "TROPHY)"). Keep it
    // only if it names a real kind: a select holding a value with no matching
    // option renders blank while still committing the garbage behind it.
    kind: CHAMPIONSHIP_KINDS.some(([v]) => v === g?.kind) ? (g?.kind as string) : '',
    isCup: g?.isCup ?? false,
    familyName: g?.familyName ?? '',
    seasonYear: g?.seasonYear != null ? String(g.seasonYear) : '',
    sessionType: 'RACE',
    sessionOrdinal: 1,
    classMapping: {},
  }
}

// A standings JSON states its season; a points PDF only infers one from the
// sheet's creation date, which is wrong for a full season republished in
// January. So that year is confirmed, not assumed.
function needsYear(b: ImportBatch, r: ImportReview | undefined): boolean {
  return r?.kind === 'STANDINGS' && b.format === 'IMSA_POINTS_PDF'
}

function validYear(value: string): boolean {
  return /^\d{4}$/.test(value.trim())
}

const KIND_LABEL: Record<string, string> = {
  RACE_RESULTS: 'Results',
  ENTRY_LIST: 'Entry list',
  STANDINGS: 'Standings',
  GRID: 'Starting grid',
  FLAGS: 'Flags / race control',
}

// Upload formats: which parser family reads the file. AUTO covers the
// historical JSON/PDF detection; CSVs must be chosen explicitly.
// Only the formats Auto-detect can't reach on its own: a grid CSV looks like any
// other CSV, and a points PDF looks like any other PDF until Python opens it.
const FORMAT_OPTIONS: [string, string][] = [
  ['AUTO', 'Auto-detect'],
  ['IMSA_CSV', 'IMSA — Grid CSV'],
  ['IMSA_POINTS_PDF', 'IMSA — Championship points PDF'],
]

const SESSION_TYPES: [string, string][] = [
  ['RACE', 'Race'],
  ['QUALIFYING', 'Qualifying'],
  ['PRACTICE', 'Practice'],
]

// What a championship ranks. Closed on purpose: this was a free-text box, and a
// "DRIVER" typo silently created a second award group alongside "DRIVERS".
// These three are what the code actually distinguishes — nothing branches on
// any other value. A sheet's own wording can differ (Mustang Challenge prints
// "Entrants" for what IWSC calls "Teams", a PACCA Dealer Trophy is a Teams
// championship); the reviewer maps it to the kind here at import.
const CHAMPIONSHIP_KINDS: [string, string][] = [
  ['DRIVERS', 'Drivers'],
  ['TEAMS', 'Teams'],
  ['MANUFACTURERS', 'Manufacturers'],
]

// Kinds that attach to an event (vs. a championship) and so pick an event target.
const EVENT_KINDS = ['RACE_RESULTS', 'ENTRY_LIST', 'GRID', 'FLAGS']

export default function ImportsPage() {
  const [batches, setBatches] = useState<ImportBatch[]>([])
  const [reviews, setReviews] = useState<Record<number, ImportReview>>({})
  const [targets, setTargets] = useState<Record<number, TargetState>>({})
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [format, setFormat] = useState('AUTO')
  const [iracingOpen, setIracingOpen] = useState(false)
  const fileInput = useRef<HTMLInputElement>(null)

  async function loadBatches() {
    const res = await fetch('/api/imports')
    if (!res.ok) return
    const list: ImportBatch[] = await res.json()
    setBatches(list)

    const staged = list.filter((b) => b.status === 'STAGED')
    const entries = await Promise.all(
      staged.map(async (b) => {
        const r = await fetch(`/api/imports/${b.id}/review`)
        return [b.id, r.ok ? ((await r.json()) as ImportReview) : null] as const
      }),
    )
    const nextReviews: Record<number, ImportReview> = {}
    setTargets((prev) => {
      const nextTargets = { ...prev }
      for (const [id, review] of entries) {
        if (!review) continue
        nextReviews[id] = review
        if (!(id in nextTargets)) nextTargets[id] = initTarget(review) // keep in-progress edits
      }
      return nextTargets
    })
    setReviews(nextReviews)
  }

  useEffect(() => {
    void loadBatches()
  }, [])

  async function uploadFiles(files: FileList) {
    setBusy(true)
    setError(null)
    for (const file of Array.from(files)) {
      const form = new FormData()
      form.append('file', file)
      form.append('format', format)
      const res = await fetch('/api/imports', { method: 'POST', body: form })
      if (!res.ok) {
        const body = await res.json().catch(() => null)
        setError(`${file.name}: ${body?.message ?? `upload failed (${res.status})`}`)
      }
    }
    if (fileInput.current) fileInput.current.value = ''
    await loadBatches()
    setBusy(false)
  }

  function patch(id: number, change: Partial<TargetState>) {
    setTargets((t) => ({ ...t, [id]: { ...t[id], ...change } }))
  }

  // A metadata-less batch can't resolve its season alone, so the class review
  // is recomputed against the chosen event's season.
  async function chooseEvent(id: number, eventId: number | 'new' | '') {
    patch(id, { eventId })
    if (!reviews[id]?.needsSession || typeof eventId !== 'number') return
    await refetchReview(id, `eventId=${eventId}`)
  }

  // Correcting the year moves the batch to a different season, so the classes
  // must be re-checked against that one — otherwise a mapping the commit will
  // demand never gets offered here.
  async function chooseYear(id: number, seasonYear: string) {
    patch(id, { seasonYear })
    if (!validYear(seasonYear)) return
    await refetchReview(id, `seasonYear=${seasonYear.trim()}`)
  }

  async function refetchReview(id: number, query: string) {
    const res = await fetch(`/api/imports/${id}/review?${query}`)
    if (!res.ok) return
    const review = (await res.json()) as ImportReview
    setReviews((r) => ({ ...r, [id]: review })) // target edits live in `targets`, untouched
  }

  function unresolvedClasses(id: number): string[] {
    const unknown = reviews[id]?.classReview.unknownClasses ?? []
    const chosen = targets[id]?.classMapping ?? {}
    return unknown.filter((c) => !chosen[c])
  }

  function seriesChosen(t: TargetState): boolean {
    if (t.seriesId === 'new') return t.newSeriesName.trim().length > 0
    return t.seriesId !== ''
  }

  function canCommit(b: ImportBatch): boolean {
    const t = targets[b.id]
    if (!t || unresolvedClasses(b.id).length > 0) return false
    if (needsYear(b, reviews[b.id]) && !validYear(t.seasonYear)) return false
    // Kind has no safe default — it decides how the standings are ranked and
    // matched — and the guess leaves it unset for a title it can't read.
    if (reviews[b.id]?.kind === 'STANDINGS' && !t.kind) return false
    // A metadata-less file commits against a chosen existing event (which
    // implies the series); other kinds need the series chosen.
    if (reviews[b.id]?.needsSession) return typeof t.eventId === 'number'
    return seriesChosen(t)
  }

  async function commit(b: ImportBatch) {
    if (!canCommit(b)) return
    const id = b.id
    const t = targets[id]
    const review = reviews[id]
    const body: Record<string, unknown> = {
      seriesId: t.seriesId === 'new' || t.seriesId === '' ? null : t.seriesId,
      newSeriesName: t.seriesId === 'new' ? t.newSeriesName.trim() : null,
      classMapping: t.classMapping,
    }
    if (EVENT_KINDS.includes(review.kind)) {
      body.eventId = t.eventId === 'new' || t.eventId === '' ? null : t.eventId
    }
    if (review.needsSession) {
      body.sessionType = t.sessionType
      body.sessionOrdinal = t.sessionOrdinal
    }
    if (review.kind === 'STANDINGS') {
      // Blank is the answer for a championship with no class of its own (an
      // overall or a teams/dealer one), so send it as null rather than "" — the
      // class check treats a blank string as a class named "", which nothing
      // matches.
      body.classCode = t.classCode.trim() || null
      body.kind = t.kind
      body.isCup = t.isCup
      body.familyName = t.familyName.trim() || null
      // Only sent where it was confirmed; otherwise the payload's own year stands.
      body.seasonYear = needsYear(b, review) ? Number(t.seasonYear) : null
    }
    setBusy(true)
    setError(null)
    const res = await fetch(`/api/imports/${id}/commit`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    })
    if (!res.ok) {
      const err = await res.json().catch(() => null)
      setError(`Batch ${id}: ${err?.message ?? `commit failed (${res.status})`}`)
    }
    await loadBatches()
    setBusy(false)
  }

  async function discard(id: number) {
    setBusy(true)
    setError(null)
    await fetch(`/api/imports/${id}/discard`, { method: 'POST' })
    await loadBatches()
    setBusy(false)
  }

  return (
    <section>
      <p>
        Upload results/standings JSON files, an iRacing subsession result, an entry list PDF, or a
        starting-grid CSV. Each file is staged; confirm what it belongs to (series, event or
        championship — pre-filled with a best guess) and commit. One file can stage several batches:
        an iRacing subsession carries qualifying, every race, and each race's grid.
      </p>
      <label className="target-row">
        <span className="target-label">Format</span>
        <select value={format} disabled={busy} onChange={(e) => setFormat(e.target.value)}>
          {FORMAT_OPTIONS.map(([value, label]) => (
            <option key={value} value={value}>
              {label}
            </option>
          ))}
        </select>
      </label>
      <input
        ref={fileInput}
        type="file"
        accept=".json,.pdf,.csv,application/json,application/pdf,text/csv"
        multiple
        disabled={busy}
        onChange={(e) => e.target.files && uploadFiles(e.target.files)}
      />
      <div className="import-iracing-row">
        <span className="import-or">or</span>
        <button type="button" className="btn-primary" disabled={busy} onClick={() => setIracingOpen(true)}>
          Fetch from iRacing
        </button>
        <span className="muted">pull results, grids, and standings straight from the Data API.</span>
      </div>
      {error && <p className="error">{error}</p>}
      {iracingOpen && (
        <IRacingImportModal onClose={() => setIracingOpen(false)} onStaged={loadBatches} />
      )}

      {batches.length === 0 ? (
        <p>No imports yet.</p>
      ) : (
        <table>
          <thead>
            <tr>
              <th>#</th>
              <th>File</th>
              <th>Kind</th>
              <th>Format</th>
              <th>Summary</th>
              <th>Status</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {batches.map((b) => {
              const review = reviews[b.id]
              const t = targets[b.id]
              const staged = b.status === 'STAGED'
              return (
                <Fragment key={b.id}>
                  <tr>
                    <td>{b.id}</td>
                    <td>{b.filename}</td>
                    <td>{KIND_LABEL[b.kind] ?? b.kind}</td>
                    <td>{b.format}</td>
                    <td>{b.summary}</td>
                    <td>{b.status}</td>
                    <td>
                      {staged && (
                        <button disabled={busy} onClick={() => discard(b.id)}>
                          Discard
                        </button>
                      )}
                    </td>
                  </tr>
                  {staged && review && t && (
                    <tr key={`${b.id}-target`}>
                      <td></td>
                      <td colSpan={6}>
                        <div className="import-target">
                          {!review.needsSession && (
                            <label className="target-row">
                              <span className="target-label">Series</span>
                              <select
                                value={t.seriesId}
                                disabled={busy}
                                onChange={(e) =>
                                  patch(b.id, {
                                    seriesId: e.target.value === 'new' ? 'new' : e.target.value === '' ? '' : Number(e.target.value),
                                  })
                                }
                              >
                                <option value="">choose…</option>
                                {review.seriesOptions.map((s) => (
                                  <option key={s.id} value={s.id}>
                                    {s.name}
                                  </option>
                                ))}
                                <option value="new">+ new series…</option>
                              </select>
                              {t.seriesId === 'new' && (
                                <input
                                  placeholder="New series name"
                                  value={t.newSeriesName}
                                  disabled={busy}
                                  onChange={(e) => patch(b.id, { newSeriesName: e.target.value })}
                                />
                              )}
                            </label>
                          )}

                          {EVENT_KINDS.includes(review.kind) && (
                            <label className="target-row">
                              <span className="target-label">Event</span>
                              <select
                                value={t.eventId}
                                disabled={busy}
                                onChange={(e) =>
                                  chooseEvent(
                                    b.id,
                                    e.target.value === 'new' ? 'new' : e.target.value === '' ? '' : Number(e.target.value),
                                  )
                                }
                              >
                                {review.needsSession && <option value="">choose…</option>}
                                {review.eventOptions.map((ev) => (
                                  <option key={ev.id} value={ev.id}>
                                    {ev.name}
                                    {ev.eventDate ? ` (${ev.eventDate})` : ''}
                                  </option>
                                ))}
                                {/* A file with no metadata has no date to create an event from. */}
                                {!review.needsSession && (
                                  <option value="new">
                                    + new event{review.guess?.eventName ? `: ${review.guess.eventName}` : ''}
                                  </option>
                                )}
                              </select>
                            </label>
                          )}

                          {review.needsSession && (
                            <label className="target-row">
                              <span className="target-label">Session</span>
                              <select
                                value={t.sessionType}
                                disabled={busy}
                                onChange={(e) => patch(b.id, { sessionType: e.target.value })}
                              >
                                {SESSION_TYPES.map(([value, label]) => (
                                  <option key={value} value={value}>
                                    {label}
                                  </option>
                                ))}
                              </select>
                              <input
                                className="target-narrow"
                                type="number"
                                min={1}
                                title="Which race/session of the weekend (Race 2 → 2)"
                                value={t.sessionOrdinal}
                                disabled={busy}
                                onChange={(e) => patch(b.id, { sessionOrdinal: Math.max(1, Number(e.target.value) || 1) })}
                              />
                            </label>
                          )}

                          {needsYear(b, review) && (
                            <label className="target-row">
                              <span className="target-label">Season</span>
                              <input
                                className="target-narrow"
                                title="Season year"
                                placeholder="YYYY"
                                inputMode="numeric"
                                value={t.seasonYear}
                                disabled={busy}
                                onChange={(e) => void chooseYear(b.id, e.target.value)}
                              />
                              <span className="target-hint">
                                {validYear(t.seasonYear)
                                  ? 'Guessed from the PDF date — confirm it matches the season.'
                                  : 'Four-digit year required.'}
                              </span>
                            </label>
                          )}

                          {review.kind === 'STANDINGS' && (
                            <label className="target-row">
                              <span className="target-label">Championship</span>
                              <input
                                className="target-narrow"
                                title="Class — leave blank for a championship that has no class of its own (an overall or a teams/dealer one)"
                                placeholder="Class (blank = all)"
                                value={t.classCode}
                                disabled={busy}
                                onChange={(e) => patch(b.id, { classCode: e.target.value })}
                              />
                              <select
                                className="target-narrow"
                                title="Kind"
                                value={t.kind}
                                disabled={busy}
                                onChange={(e) => patch(b.id, { kind: e.target.value })}
                              >
                                <option value="">choose…</option>
                                {CHAMPIONSHIP_KINDS.map(([value, label]) => (
                                  <option key={value} value={value}>
                                    {label}
                                  </option>
                                ))}
                              </select>
                              <label className="target-checkbox">
                                <input
                                  type="checkbox"
                                  checked={t.isCup}
                                  disabled={busy}
                                  onChange={(e) => patch(b.id, { isCup: e.target.checked })}
                                />
                                cup
                              </label>
                              {t.isCup && (
                                <input
                                  title="Cup / family name"
                                  placeholder="Cup name"
                                  value={t.familyName}
                                  disabled={busy}
                                  onChange={(e) => patch(b.id, { familyName: e.target.value })}
                                />
                              )}
                            </label>
                          )}

                          {(review.classReview.unknownClasses ?? []).length > 0 && (
                            <div className="class-review">
                              <strong>Unrecognized class{review.classReview.unknownClasses.length > 1 ? 'es' : ''}</strong>{' '}
                              — map to a known class:
                              {review.classReview.unknownClasses.map((c) => (
                                <label key={c} className="class-map-row">
                                  <span className="class-map-source">{c}</span> →{' '}
                                  <select
                                    value={t.classMapping[c] ?? ''}
                                    disabled={busy}
                                    onChange={(e) =>
                                      patch(b.id, { classMapping: { ...t.classMapping, [c]: e.target.value } })
                                    }
                                  >
                                    <option value="" disabled>
                                      choose…
                                    </option>
                                    {review.classReview.knownClasses.map((k) => (
                                      <option key={k} value={k}>
                                        {k}
                                      </option>
                                    ))}
                                  </select>
                                </label>
                              ))}
                            </div>
                          )}

                          <button
                            disabled={busy || !canCommit(b)}
                            title={
                              review.needsSession && typeof t.eventId !== 'number'
                                ? 'Choose an event first'
                                : !review.needsSession && !seriesChosen(t)
                                  ? 'Choose a series first'
                                  : review.kind === 'STANDINGS' && !t.kind
                                    ? 'Choose what the championship ranks first'
                                    : undefined
                            }
                            onClick={() => commit(b)}
                          >
                            Commit
                          </button>
                        </div>
                      </td>
                    </tr>
                  )}
                </Fragment>
              )
            })}
          </tbody>
        </table>
      )}
    </section>
  )
}
