import { Fragment, useEffect, useMemo, useState } from 'react'
import Combobox, { type ComboOption } from '../components/Combobox'
import IRacingImportModal from '../components/IRacingImportModal'
import UploadFilesModal from '../components/UploadFilesModal'
import { formatEventDate } from '../lib/importGroups'
import { useSeriesEvents } from '../lib/useSeriesEvents'

interface ImportBatch {
  id: number
  kind: string
  format: string
  filename: string
  status: string
  summary: string | null
  createdAt: string
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
  classReview: ClassReview
  needsSession: boolean
  // Pre-fills for the session picker: a results CSV knows race from qualifying
  // by its header, a grid PDF names which race it starts. Null without a hint.
  sessionTypeHint: string | null
  sessionOrdinalHint: number | null
  // GRID only: every slot is untimed — qualifying never ran, so the reviewer
  // should say what set the grid.
  gridTimesAllBlank: boolean
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
  // Held as raw text so "1" can be deleted before "2" is typed; validated by
  // canCommit rather than clamped on every keystroke.
  sessionOrdinal: string
  classMapping: Record<string, string>
  gridBasis: string
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
    sessionType: r.sessionTypeHint ?? 'RACE',
    sessionOrdinal: String(r.sessionOrdinalHint ?? 1),
    classMapping: {},
    gridBasis: '',
  }
}

// A standings' season year is confirmed, not assumed: a points PDF only infers
// one from the sheet's creation date (wrong for a season republished in
// January), and an iRacing season with a generic name ("League 6004 season
// 99330") states none at all. So every standings batch takes a confirmed year —
// pre-filled from the guess when it has one — which the commit requires. (Kept
// independent of the guessed year so entering one doesn't hide the field: the
// review refetch on year change would otherwise flip this false.)
function needsYear(r: ImportReview | undefined): boolean {
  return r?.kind === 'STANDINGS'
}

function validYear(value: string): boolean {
  return /^\d{4}$/.test(value.trim())
}

function validOrdinal(value: string): boolean {
  return /^[1-9]\d*$/.test(value.trim())
}

const KIND_LABEL: Record<string, string> = {
  RACE_RESULTS: 'Results',
  ENTRY_LIST: 'Entry list',
  STANDINGS: 'Standings',
  GRID: 'Starting grid',
  FLAGS: 'Flags / race control',
}

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
  const [iracingOpen, setIracingOpen] = useState(false)
  const [uploadOpen, setUploadOpen] = useState(false)

  // One shared series/events fetch powers every review row's typeahead — the
  // review payload's own option lists no longer drive the UI, so a file whose
  // guess resolves nothing still gets a searchable list instead of a dump.
  const { allSeries, allEvents } = useSeriesEvents((m) => setError(m))

  const seriesComboOptions: ComboOption[] = useMemo(
    () =>
      (allSeries ?? []).map((s) => ({
        key: String(s.id),
        label: s.name,
        hint: s.abbreviation ?? undefined,
      })),
    [allSeries],
  )

  // A seed pins the just-staged batches to the series + event chosen in the
  // upload modal, so those rows land pre-targeted (their own guess still fills
  // session/kind/class). Applied only to batches new this load — in-progress
  // edits are never overwritten.
  async function loadBatches(seed?: { ids: Set<number>; seriesId: number; eventId: number | null }) {
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
        if (!(id in nextTargets)) {
          let t = initTarget(review) // keep in-progress edits
          if (seed?.ids.has(id)) {
            t = { ...t, seriesId: seed.seriesId, ...(seed.eventId != null ? { eventId: seed.eventId } : {}) }
          }
          nextTargets[id] = t
        }
      }
      return nextTargets
    })
    setReviews(nextReviews)
  }

  useEffect(() => {
    void loadBatches()
  }, [])

  // A modal stages batches and hands their ids back with the series + event it
  // pinned; refresh the table, seeding those rows. A null series means no pin
  // (the iRacing modal allows it) — just refresh, each batch reviewed fresh.
  async function onBatchesStaged(batchIds: number[], seriesId: number | null, eventId: number | null) {
    if (seriesId === null) {
      await loadBatches()
      return
    }
    await loadBatches({ ids: new Set(batchIds), seriesId, eventId })
  }

  // The confirm step committed a batch as grouped events; refresh the table so
  // the committed rows update and any leftovers land seeded with the series/event.
  async function onBatchesCommitted(r: {
    committedIds: number[]
    leftoverIds: number[]
    seriesId: number | null
    eventId: number | null
  }) {
    if (r.seriesId === null || r.leftoverIds.length === 0) {
      await loadBatches()
      return
    }
    await loadBatches({ ids: new Set(r.leftoverIds), seriesId: r.seriesId, eventId: r.eventId })
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

  /** The event typeahead's options. With a series chosen the list narrows to
   *  it (oldest first, like a season page); with none it spans every event,
   *  newest first, hinted with its series so typing either name matches. */
  function eventComboOptions(t: TargetState, review: ImportReview): ComboOption[] {
    const series = typeof t.seriesId === 'number' ? allSeries?.find((s) => s.id === t.seriesId) : undefined
    const events = allEvents ?? []
    const list = series
      ? events
          .filter((e) => e.seriesName === series.name)
          .sort((a, b) => (a.eventDate ?? '9999').localeCompare(b.eventDate ?? '9999'))
      : [...events].sort((a, b) => (b.eventDate ?? '').localeCompare(a.eventDate ?? ''))
    const opts: ComboOption[] = list.map((e) => ({
      key: String(e.id),
      label: e.name,
      hint: [series ? e.circuitName : e.seriesName, formatEventDate(e.eventDate, e.year)]
        .filter(Boolean)
        .join(' · '),
    }))
    // A file with no metadata has no date to create an event from.
    if (!review.needsSession) {
      opts.unshift({
        key: 'new',
        label: `New event${review.guess?.eventName ? `: ${review.guess.eventName}` : ''}`,
        variant: 'action',
      })
    }
    return opts
  }

  function pickSeries(b: ImportBatch, key: string) {
    const s = allSeries?.find((x) => x.id === Number(key))
    if (!s) return
    const t = targets[b.id]
    const ev = typeof t?.eventId === 'number' ? allEvents?.find((e) => e.id === t.eventId) : undefined
    // An event chosen from another series contradicts the pick; fall back to "new".
    const resetEvent = EVENT_KINDS.includes(reviews[b.id]?.kind ?? '') && ev && ev.seriesName !== s.name
    patch(b.id, { seriesId: s.id, ...(resetEvent ? { eventId: 'new' as const } : {}) })
  }

  /** The create-row keeps today's deferred semantics: the series is named now
   *  and created at commit, so discarding the batch leaves nothing behind. */
  function pickNewSeries(b: ImportBatch, name: string) {
    const existing = allSeries?.find((s) => s.name.toLowerCase() === name.toLowerCase())
    if (existing) {
      pickSeries(b, String(existing.id))
      return
    }
    const t = targets[b.id]
    // A brand-new series has no events yet, so any picked event contradicts it.
    const resetEvent = EVENT_KINDS.includes(reviews[b.id]?.kind ?? '') && typeof t?.eventId === 'number'
    patch(b.id, { seriesId: 'new', newSeriesName: name, ...(resetEvent ? { eventId: 'new' as const } : {}) })
  }

  function pickEvent(b: ImportBatch, key: string) {
    if (key === 'new') {
      patch(b.id, { eventId: 'new' })
      return
    }
    const id = Number(key)
    const review = reviews[b.id]
    const e = allEvents?.find((ev) => ev.id === id)
    // Global search pays for itself here: the event names its series.
    if (e && review && !review.needsSession) {
      const s = allSeries?.find((x) => x.name === e.seriesName)
      if (s) patch(b.id, { seriesId: s.id })
    }
    void chooseEvent(b.id, id)
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
    if (needsYear(reviews[b.id]) && !validYear(t.seasonYear)) return false
    // Kind has no safe default — it decides how the standings are ranked and
    // matched — and the guess leaves it unset for a title it can't read.
    if (reviews[b.id]?.kind === 'STANDINGS' && !t.kind) return false
    // A metadata-less file commits against a chosen existing event (which
    // implies the series); other kinds need the series chosen.
    if (reviews[b.id]?.needsSession) return typeof t.eventId === 'number' && validOrdinal(t.sessionOrdinal)
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
      body.sessionOrdinal = Number(t.sessionOrdinal.trim())
    }
    if (review.kind === 'GRID') {
      body.gridBasis = t.gridBasis.trim() || null
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
      body.seasonYear = needsYear(review) ? Number(t.seasonYear) : null
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
    <section className="imports-page">
      <h2>Imports</h2>
      <p>
        Upload results/standings JSON files, an iRacing subsession result, an entry list PDF, or a
        timing CSV (starting grid, race results, or qualifying results). Each file is staged; confirm
        what it belongs to (series, event or championship — pre-filled with a best guess) and commit.
        One file can stage several batches: an iRacing subsession carries qualifying, every race, and
        each race's grid.
      </p>
      <div className="import-actions">
        <button type="button" className="btn btn-primary" disabled={busy} onClick={() => setUploadOpen(true)}>
          Upload files
        </button>
        <span className="import-or">or</span>
        <button type="button" className="btn" disabled={busy} onClick={() => setIracingOpen(true)}>
          Fetch from iRacing
        </button>
        <span className="muted">pull results, grids, and standings straight from the Data API.</span>
      </div>
      {error && <p className="error">{error}</p>}
      {uploadOpen && (
        <UploadFilesModal
          onClose={() => setUploadOpen(false)}
          onStaged={onBatchesStaged}
          onCommitted={onBatchesCommitted}
        />
      )}
      {iracingOpen && (
        <IRacingImportModal
          onClose={() => setIracingOpen(false)}
          onStaged={onBatchesStaged}
          onCommitted={onBatchesCommitted}
        />
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
                            <div className="target-row">
                              <span className="target-label">Series</span>
                              <Combobox
                                inputId={`imp-${b.id}-series`}
                                label="Series"
                                showLabel={false}
                                className="target-combo"
                                floating
                                disabled={busy}
                                loading={allSeries === null}
                                placeholder="Search series…"
                                emptyText="No matching series."
                                options={
                                  t.seriesId === 'new'
                                    ? [
                                        ...seriesComboOptions,
                                        { key: 'new', label: t.newSeriesName || 'New series', hint: 'new series' },
                                      ]
                                    : seriesComboOptions
                                }
                                selectedKey={t.seriesId === '' ? null : String(t.seriesId)}
                                onPick={(key) => pickSeries(b, key)}
                                onClear={t.seriesId !== '' ? () => patch(b.id, { seriesId: '' }) : undefined}
                                onCreate={(name) => pickNewSeries(b, name)}
                                createHint="new series"
                              />
                              {t.seriesId === 'new' && (
                                <input
                                  aria-label="New series name"
                                  placeholder="New series name"
                                  value={t.newSeriesName}
                                  disabled={busy}
                                  onChange={(e) => patch(b.id, { newSeriesName: e.target.value })}
                                />
                              )}
                            </div>
                          )}

                          {EVENT_KINDS.includes(review.kind) && (
                            <div className="target-row">
                              <span className="target-label">Event</span>
                              <Combobox
                                inputId={`imp-${b.id}-event`}
                                label="Event"
                                showLabel={false}
                                className="target-combo"
                                floating
                                disabled={busy}
                                loading={allEvents === null}
                                placeholder={
                                  typeof t.seriesId === 'number' ? 'Search events…' : 'Search all events…'
                                }
                                emptyText="No matching events."
                                maxVisible={40}
                                options={eventComboOptions(t, review)}
                                selectedKey={
                                  t.eventId === 'new' ? 'new' : t.eventId === '' ? null : String(t.eventId)
                                }
                                onPick={(key) => pickEvent(b, key)}
                                onClear={
                                  typeof t.eventId === 'number'
                                    ? () => patch(b.id, { eventId: review.needsSession ? '' : 'new' })
                                    : undefined
                                }
                              />
                            </div>
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
                                onChange={(e) => patch(b.id, { sessionOrdinal: e.target.value })}
                              />
                              {!validOrdinal(t.sessionOrdinal) && (
                                <span className="target-hint">Which race of the weekend — 1, 2, …</span>
                              )}
                            </label>
                          )}

                          {review.kind === 'GRID' && (
                            <label className="target-row">
                              <span className="target-label">Grid basis</span>
                              <input
                                title="How this grid was set, when not by qualifying"
                                placeholder="e.g. 2nd fastest qualifying lap"
                                value={t.gridBasis}
                                disabled={busy}
                                onChange={(e) => patch(b.id, { gridBasis: e.target.value })}
                              />
                              {review.gridTimesAllBlank && (
                                <span className="target-hint">
                                  No times on this grid — qualifying never ran, so note what set the
                                  order (points, a prior race, …).
                                </span>
                              )}
                            </label>
                          )}

                          {needsYear(review) && (
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
                                : review.needsSession && !validOrdinal(t.sessionOrdinal)
                                  ? 'Enter which race of the weekend (1, 2, …)'
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
