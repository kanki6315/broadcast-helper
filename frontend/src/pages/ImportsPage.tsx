import { Fragment, useEffect, useRef, useState } from 'react'

interface ImportBatch {
  id: number
  kind: string
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
}

// The reviewer's editable choices for one batch, seeded from the guess.
interface TargetState {
  seriesId: number | 'new' | ''
  newSeriesName: string
  eventId: number | 'new'
  classCode: string
  kind: string
  isCup: boolean
  familyName: string
  classMapping: Record<string, string>
}

function initTarget(r: ImportReview): TargetState {
  const g = r.guess
  return {
    seriesId: g?.seriesId ?? '',
    newSeriesName: '',
    eventId: g?.eventId ?? 'new',
    classCode: g?.classCode ?? '',
    kind: g?.kind ?? '',
    isCup: g?.isCup ?? false,
    familyName: g?.familyName ?? '',
    classMapping: {},
  }
}

const KIND_LABEL: Record<string, string> = {
  RACE_RESULTS: 'Results',
  ENTRY_LIST: 'Entry list',
  STANDINGS: 'Standings',
  GRID: 'Starting grid',
}

// Kinds that attach to an event (vs. a championship) and so pick an event target.
const EVENT_KINDS = ['RACE_RESULTS', 'ENTRY_LIST', 'GRID']

export default function ImportsPage() {
  const [batches, setBatches] = useState<ImportBatch[]>([])
  const [reviews, setReviews] = useState<Record<number, ImportReview>>({})
  const [targets, setTargets] = useState<Record<number, TargetState>>({})
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
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

  function unresolvedClasses(id: number): string[] {
    const unknown = reviews[id]?.classReview.unknownClasses ?? []
    const chosen = targets[id]?.classMapping ?? {}
    return unknown.filter((c) => !chosen[c])
  }

  function seriesChosen(t: TargetState): boolean {
    if (t.seriesId === 'new') return t.newSeriesName.trim().length > 0
    return t.seriesId !== ''
  }

  function canCommit(id: number): boolean {
    const t = targets[id]
    return !!t && seriesChosen(t) && unresolvedClasses(id).length === 0
  }

  async function commit(id: number) {
    if (!canCommit(id)) return
    const t = targets[id]
    const review = reviews[id]
    const body: Record<string, unknown> = {
      seriesId: t.seriesId === 'new' || t.seriesId === '' ? null : t.seriesId,
      newSeriesName: t.seriesId === 'new' ? t.newSeriesName.trim() : null,
      classMapping: t.classMapping,
    }
    if (EVENT_KINDS.includes(review.kind)) {
      body.eventId = t.eventId === 'new' ? null : t.eventId
    }
    if (review.kind === 'STANDINGS') {
      body.classCode = t.classCode
      body.kind = t.kind
      body.isCup = t.isCup
      body.familyName = t.familyName.trim() || null
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
        Upload results/standings JSON files or an entry list PDF. Each file is staged; confirm what
        it belongs to (series, event or championship — pre-filled with a best guess) and commit.
      </p>
      <input
        ref={fileInput}
        type="file"
        accept=".json,.pdf,application/json,application/pdf"
        multiple
        disabled={busy}
        onChange={(e) => e.target.files && uploadFiles(e.target.files)}
      />
      {error && <p className="error">{error}</p>}

      {batches.length === 0 ? (
        <p>No imports yet.</p>
      ) : (
        <table>
          <thead>
            <tr>
              <th>#</th>
              <th>File</th>
              <th>Kind</th>
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
                      <td colSpan={5}>
                        <div className="import-target">
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

                          {EVENT_KINDS.includes(review.kind) && (
                            <label className="target-row">
                              <span className="target-label">Event</span>
                              <select
                                value={t.eventId}
                                disabled={busy}
                                onChange={(e) =>
                                  patch(b.id, { eventId: e.target.value === 'new' ? 'new' : Number(e.target.value) })
                                }
                              >
                                {review.eventOptions.map((ev) => (
                                  <option key={ev.id} value={ev.id}>
                                    {ev.name}
                                    {ev.eventDate ? ` (${ev.eventDate})` : ''}
                                  </option>
                                ))}
                                <option value="new">
                                  + new event{review.guess?.eventName ? `: ${review.guess.eventName}` : ''}
                                </option>
                              </select>
                            </label>
                          )}

                          {review.kind === 'STANDINGS' && (
                            <label className="target-row">
                              <span className="target-label">Championship</span>
                              <input
                                className="target-narrow"
                                title="Class"
                                placeholder="Class"
                                value={t.classCode}
                                disabled={busy}
                                onChange={(e) => patch(b.id, { classCode: e.target.value })}
                              />
                              <input
                                className="target-narrow"
                                title="Kind"
                                placeholder="Kind"
                                value={t.kind}
                                disabled={busy}
                                onChange={(e) => patch(b.id, { kind: e.target.value.toUpperCase() })}
                              />
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
                            disabled={busy || !canCommit(b.id)}
                            title={!seriesChosen(t) ? 'Choose a series first' : undefined}
                            onClick={() => commit(b.id)}
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
