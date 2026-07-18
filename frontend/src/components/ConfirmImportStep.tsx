import { useEffect, useMemo, useRef, useState } from 'react'
import './confirm-import-step.css'
import ImportStatusIcon from './ImportStatusIcon'
import SeriesEventPicker from './SeriesEventPicker'
import { useSeriesEvents, type EventOption } from '../lib/useSeriesEvents'
import { KIND_LABEL, batchDetail, formatEventDate, groupName } from '../lib/importGroups'

/**
 * The confirm-and-commit step both import modals hand off to after staging.
 * It shows the proposed event grouping (one card per event, its sessions inside),
 * lets the user drag sessions between events (with a keyboard "Move to…" path),
 * previews the season's round numbers, then commits everything in one call via
 * POST /api/imports/commit-group. Items that still need review (unknown classes,
 * a metadata-less grid, a standings row with no kind) are set aside for the
 * table below rather than committed here.
 */

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
interface ImportReview {
  kind: string
  guess: TargetGuess | null
  classReview: { knownClasses: string[]; unknownClasses: string[] }
  needsSession: boolean
}
interface BatchListItem {
  id: number
  kind: string
  filename: string
  summary: string | null
  status: string
}

const EVENT_KINDS = new Set(['RACE_RESULTS', 'GRID', 'FLAGS', 'ENTRY_LIST'])
const CHAMPIONSHIP_KINDS = new Set(['DRIVERS', 'TEAMS', 'MANUFACTURERS'])

// One staged batch, enriched with its review.
interface ConfirmBatch {
  id: number
  kind: string
  detail: string
  guess: TargetGuess | null
  unknownClasses: string[]
  needsSession: boolean
}
// The draggable unit: one file / subsession (its batches share a filename).
interface ConfirmItem {
  key: string
  label: string
  date: string | null
  circuit: string | null
  seriesId: number | null
  batches: ConfirmBatch[]
  excludedReason: string | null
}
// A proposed event: attach to an existing one (eventId set) or create it.
interface EventGroupDraft {
  key: string
  eventId: number | null
  name: string
  date: string | null
  itemKeys: string[]
}

type Phase = 'loading' | 'review' | 'committing' | 'done'
type BatchState = { status: 'idle' | 'busy' | 'ok' | 'error' | 'skip'; message?: string }

interface BatchResult {
  batchId: number
  status: string
  message: string | null
  eventId: number | null
}

function excludeReason(b: ConfirmBatch): string | null {
  if (b.unknownClasses.length > 0) return 'unrecognized class'
  if (b.needsSession) return 'needs a session chosen'
  if (b.kind === 'STANDINGS') {
    // The guess reads kind and year from the standings title; a generically named
    // iRacing season ("League 6004 season 99330") yields a garbage kind and no
    // year. Send those to the review table — which has the kind picker and year
    // input — rather than committing here, where both would 422 at commit.
    if (!b.guess?.kind || !CHAMPIONSHIP_KINDS.has(b.guess.kind)) return 'championship kind unset'
    if (b.guess.seasonYear == null) return 'season year unknown'
  }
  return null
}

let groupSeq = 0
function newGroupKey(): string {
  return `g${++groupSeq}`
}

export default function ConfirmImportStep({
  batchIds,
  pinnedSeriesId,
  pinnedEventId,
  onCommitted,
  onBack,
  onDone,
}: {
  batchIds: number[]
  pinnedSeriesId: number | null
  pinnedEventId: number | null
  onCommitted: (r: {
    committedIds: number[]
    leftoverIds: number[]
    seriesId: number | null
    eventId: number | null
  }) => void
  onBack: () => void
  onDone: () => void
}) {
  const [phase, setPhase] = useState<Phase>('loading')
  const [error, setError] = useState<string | null>(null)
  const [items, setItems] = useState<ConfirmItem[]>([])
  const [standings, setStandings] = useState<ConfirmBatch[]>([])
  const [groups, setGroups] = useState<EventGroupDraft[]>([])
  const [seriesId, setSeriesId] = useState<number | null>(pinnedSeriesId)
  const [seriesName, setSeriesName] = useState<string | null>(null)
  const [batchState, setBatchState] = useState<Record<number, BatchState>>({})
  const [dragKey, setDragKey] = useState<string | null>(null)
  const [dropTarget, setDropTarget] = useState<string | null>(null)

  const { allSeries, allEvents } = useSeriesEvents(setError)

  // Build the initial grouping once the reviews and lists have loaded.
  const loadedRef = useRef(false)
  useEffect(() => {
    if (loadedRef.current || allEvents === null || allSeries === null) return
    loadedRef.current = true
    void (async () => {
      try {
        const listRes = await fetch('/api/imports')
        const all = (listRes.ok ? await listRes.json() : []) as BatchListItem[]
        const byId = new Map(all.map((b) => [b.id, b]))
        const reviews = await Promise.all(
          batchIds.map(async (id) => {
            const r = await fetch(`/api/imports/${id}/review`)
            return [id, r.ok ? ((await r.json()) as ImportReview) : null] as const
          }),
        )
        const reviewById = new Map(reviews)
        buildInitial(batchIds, byId, reviewById, allEvents, pinnedEventId)
      } catch {
        setError('Could not load the staged imports.')
        setPhase('review')
      }
    })()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [allEvents, allSeries])

  function buildInitial(
    ids: number[],
    byId: Map<number, BatchListItem>,
    reviewById: Map<number, ImportReview | null>,
    events: EventOption[],
    pinnedEvent: number | null,
  ) {
    const standingsBatches: ConfirmBatch[] = []
    // Group event-kind batches by filename into items; collect standings apart.
    const itemMap = new Map<string, ConfirmItem>()
    for (const id of ids) {
      const b = byId.get(id)
      if (!b) continue
      const review = reviewById.get(id) ?? null
      const cb: ConfirmBatch = {
        id,
        kind: b.kind,
        detail: batchDetail(b.summary),
        guess: review?.guess ?? null,
        unknownClasses: review?.classReview.unknownClasses ?? [],
        needsSession: review?.needsSession ?? false,
      }
      if (!EVENT_KINDS.has(b.kind)) {
        standingsBatches.push(cb)
        continue
      }
      const key = b.filename
      if (!itemMap.has(key)) {
        itemMap.set(key, {
          key,
          label: cb.guess?.eventName ?? groupName(b.summary),
          date: cb.guess?.eventDate ?? null,
          circuit: cb.guess?.circuit ?? null,
          seriesId: cb.guess?.seriesId ?? null,
          batches: [],
          excludedReason: null,
        })
      }
      itemMap.get(key)!.batches.push(cb)
    }

    const built = [...itemMap.values()].map((it) => ({
      ...it,
      // An item with any problem batch goes to the table (a group commits atomically).
      excludedReason: it.batches.map(excludeReason).find(Boolean) ?? null,
    }))

    // Effective series: the pin, else a series all items agree on.
    const guessedSeries = new Set(built.map((it) => it.seriesId).filter((s): s is number => s != null))
    const effectiveSeries = pinnedSeriesId ?? (guessedSeries.size === 1 ? [...guessedSeries][0] : null)
    setSeriesId(effectiveSeries)

    setItems(built)
    setStandings(standingsBatches)
    setGroups(initialGroups(built, events, pinnedEvent, effectiveSeries))
    setPhase('review')
  }

  // Assign each item to a group: the pinned event, an existing event its guess
  // matched, or a fresh create-group bucketed by circuit + date.
  function initialGroups(
    built: ConfirmItem[],
    events: EventOption[],
    pinnedEvent: number | null,
    series: number | null,
  ): EventGroupDraft[] {
    const eligible = built.filter((it) => !it.excludedReason)
    if (pinnedEvent != null) {
      const ev = events.find((e) => e.id === pinnedEvent)
      return [
        {
          key: `attach-${pinnedEvent}`,
          eventId: pinnedEvent,
          name: ev?.name ?? 'Pinned event',
          date: ev?.eventDate ?? null,
          itemKeys: eligible.map((it) => it.key),
        },
      ]
    }
    const drafts: EventGroupDraft[] = []
    const bucketKey = new Map<string, string>() // circuit|date -> group key
    for (const it of eligible) {
      const guessedEvent = it.batches[0]?.guess?.eventId ?? null
      if (guessedEvent != null) {
        const gk = `attach-${guessedEvent}`
        let g = drafts.find((d) => d.key === gk)
        if (!g) {
          const ev = events.find((e) => e.id === guessedEvent)
          g = { key: gk, eventId: guessedEvent, name: ev?.name ?? it.label, date: ev?.eventDate ?? it.date, itemKeys: [] }
          drafts.push(g)
        }
        g.itemKeys.push(it.key)
        continue
      }
      const bk = `${(it.circuit ?? '').toLowerCase()}|${it.date ?? ''}`
      let gk = bucketKey.get(bk)
      if (!gk) {
        gk = newGroupKey()
        bucketKey.set(bk, gk)
        drafts.push({ key: gk, eventId: null, name: it.label, date: it.date, itemKeys: [] })
      }
      drafts.find((d) => d.key === gk)!.itemKeys.push(it.key)
    }
    return decollide(drafts, events, series)
  }

  // Suffix a create-group's name with its date when it collides with a sibling
  // create-group or an existing event in the same season.
  function decollide(drafts: EventGroupDraft[], events: EventOption[], series: number | null): EventGroupDraft[] {
    const existingNames = new Set(
      events
        .filter((e) => series == null || allSeries?.find((s) => s.id === series)?.name === e.seriesName)
        .map((e) => e.name.toLowerCase()),
    )
    const seen = new Map<string, number>()
    for (const d of drafts) {
      if (d.eventId != null) continue
      seen.set(d.name.toLowerCase(), (seen.get(d.name.toLowerCase()) ?? 0) + 1)
    }
    return drafts.map((d) => {
      if (d.eventId != null) return d
      const clash = (seen.get(d.name.toLowerCase()) ?? 0) > 1 || existingNames.has(d.name.toLowerCase())
      if (clash && d.date) {
        const suffix = new Date(d.date)
        if (!Number.isNaN(suffix.getTime())) {
          return { ...d, name: `${d.name} (${suffix.toLocaleDateString(undefined, { month: 'short', day: 'numeric' })})` }
        }
      }
      return d
    })
  }

  const itemByKey = useMemo(() => new Map(items.map((it) => [it.key, it])), [items])
  const seriesLabel = allSeries?.find((s) => s.id === seriesId)?.name ?? seriesName ?? null

  // --- drag / move between groups -----------------------------------------

  function moveItem(itemKey: string, targetGroupKey: string | 'new') {
    setGroups((prev) => {
      const without = prev.map((g) => ({ ...g, itemKeys: g.itemKeys.filter((k) => k !== itemKey) }))
      if (targetGroupKey === 'new') {
        const it = itemByKey.get(itemKey)
        without.push({ key: newGroupKey(), eventId: null, name: it?.label ?? 'New event', date: it?.date ?? null, itemKeys: [itemKey] })
      } else {
        const g = without.find((x) => x.key === targetGroupKey)
        if (g) g.itemKeys.push(itemKey)
      }
      // Drop create-groups left empty (an emptied attach-group is kept only if it still has items).
      return without.filter((g) => g.itemKeys.length > 0)
    })
  }

  function renameGroup(key: string, name: string) {
    setGroups((prev) => prev.map((g) => (g.key === key ? { ...g, name } : g)))
  }

  function attachGroupToEvent(key: string, eventId: number | null) {
    setGroups((prev) =>
      prev.map((g) => {
        if (g.key !== key) return g
        if (eventId == null) return { ...g, eventId: null }
        const ev = allEvents?.find((e) => e.id === eventId)
        return { ...g, eventId, name: ev?.name ?? g.name, date: ev?.eventDate ?? g.date }
      }),
    )
  }

  // --- round-ordinal preview ----------------------------------------------

  // Merge existing season events with the create-groups and sort by date, so the
  // user sees the round numbers the commit-time renumber will produce.
  const preview = useMemo(() => {
    if (seriesId == null || allEvents === null) return null
    const sName = allSeries?.find((s) => s.id === seriesId)?.name
    if (!sName) return null
    const createGroups = groups.filter((g) => g.eventId == null && g.itemKeys.length > 0)
    if (createGroups.length === 0) return null
    // Bucket by year (undated groups can't be previewed).
    const yearOf = (d: string | null) => (d ? new Date(d).getFullYear() : null)
    const years = new Set(createGroups.map((g) => yearOf(g.date)).filter((y): y is number => y != null))
    const rows: { year: number; entries: { name: string; date: string | null; isNew: boolean }[] }[] = []
    for (const year of [...years].sort()) {
      const existing = allEvents
        .filter((e) => e.seriesName === sName && (e.eventDate ? new Date(e.eventDate).getFullYear() : e.year) === year)
        .map((e) => ({ name: e.name, date: e.eventDate, isNew: false }))
      const created = createGroups
        .filter((g) => yearOf(g.date) === year)
        .map((g) => ({ name: g.name, date: g.date, isNew: true }))
      const merged = [...existing, ...created].sort((a, b) => {
        if (a.date === b.date) return a.isNew === b.isNew ? 0 : a.isNew ? 1 : -1
        if (!a.date) return 1
        if (!b.date) return -1
        return a.date < b.date ? -1 : 1
      })
      rows.push({ year, entries: merged })
    }
    return rows
  }, [groups, seriesId, allEvents, allSeries])

  // --- commit --------------------------------------------------------------

  const includedItems = items.filter((it) => !it.excludedReason)
  const includedStandings = standings.filter((b) => !excludeReason(b))
  const leftoverIds = [
    ...items.filter((it) => it.excludedReason).flatMap((it) => it.batches.map((b) => b.id)),
    ...standings.filter((b) => excludeReason(b)).map((b) => b.id),
  ]
  const commitCount = includedItems.reduce((n, it) => n + it.batches.length, 0) + includedStandings.length
  const canCommit = seriesId != null && commitCount > 0 && phase === 'review'

  async function commit(retryFailedOnly = false) {
    if (seriesId == null) return
    setPhase('committing')
    setError(null)

    const wantGroups = groups.filter((g) => g.itemKeys.length > 0)
    const failedIds = new Set(
      Object.entries(batchState).filter(([, s]) => s.status === 'error').map(([id]) => Number(id)),
    )

    const eventPayload = wantGroups.map((g) => ({ key: g.key, eventId: g.eventId, name: g.name, eventDate: g.date }))
    const batchPayload: { batchId: number; eventKey: string | null; target: unknown }[] = []
    const eventTarget = () => ({
      seriesId,
      newSeriesName: null,
      eventId: null,
      eventName: null, // the group supplies the name
      classCode: null,
      kind: null,
      isCup: null,
      familyName: null,
      seasonYear: null,
      sessionType: null,
      sessionOrdinal: null,
      classMapping: {},
    })
    for (const g of wantGroups) {
      for (const key of g.itemKeys) {
        const it = itemByKey.get(key)
        if (!it) continue
        for (const b of it.batches) {
          if (retryFailedOnly && !failedIds.has(b.id)) continue
          batchPayload.push({ batchId: b.id, eventKey: g.key, target: eventTarget() })
        }
      }
    }
    for (const b of includedStandings) {
      if (retryFailedOnly && !failedIds.has(b.id)) continue
      batchPayload.push({
        batchId: b.id,
        eventKey: null,
        target: {
          seriesId,
          newSeriesName: null,
          eventId: null,
          eventName: null,
          classCode: b.guess?.classCode ?? null,
          kind: b.guess?.kind ?? null,
          isCup: b.guess?.isCup ?? false,
          familyName: b.guess?.familyName ?? null,
          seasonYear: b.guess?.seasonYear ?? null,
          sessionType: null,
          sessionOrdinal: null,
          classMapping: {},
        },
      })
    }

    const committingState: Record<number, BatchState> = { ...batchState }
    for (const bp of batchPayload) committingState[bp.batchId] = { status: 'busy' }
    setBatchState(committingState)

    try {
      const res = await fetch('/api/imports/commit-group', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ events: eventPayload, batches: batchPayload }),
      })
      if (!res.ok) {
        const body = await res.json().catch(() => null)
        setError(body?.message ?? `Commit failed (${res.status})`)
        setBatchState((prev) => {
          const next = { ...prev }
          for (const bp of batchPayload) next[bp.batchId] = { status: 'error', message: 'not committed' }
          return next
        })
        setPhase('review')
        return
      }
      const result = (await res.json()) as { results: BatchResult[] }
      setBatchState((prev) => {
        const next = { ...prev }
        for (const r of result.results) {
          next[r.batchId] =
            r.status === 'COMMITTED' ? { status: 'ok' } : { status: 'error', message: r.message ?? 'failed' }
        }
        return next
      })
      setPhase('done')
    } catch {
      setError('Could not reach the server.')
      setPhase('review')
    }
  }

  const committedIds = Object.entries(batchState)
    .filter(([, s]) => s.status === 'ok')
    .map(([id]) => Number(id))
  const anyFailed = Object.values(batchState).some((s) => s.status === 'error')

  function finish() {
    onCommitted({ committedIds, leftoverIds, seriesId, eventId: pinnedEventId })
    onDone()
  }

  // --- render --------------------------------------------------------------

  if (phase === 'loading') {
    return (
      <div className="cis">
        <div className="cis-skel" aria-label="Loading staged imports">
          <span className="skeleton" />
          <span className="skeleton" />
          <span className="skeleton" />
        </div>
      </div>
    )
  }

  const eventsForSeries = seriesLabel ? (allEvents ?? []).filter((e) => e.seriesName === seriesLabel) : []
  const stateOf = (id: number): BatchState => batchState[id] ?? { status: 'idle' }

  return (
    <div className="cis">
      {error && (
        <p className="error-panel cis-error" role="alert">
          {error}
        </p>
      )}

      {seriesId == null ? (
        <div className="cis-series-need">
          <p className="cis-need-note">Choose the series these imports belong to.</p>
          <SeriesEventPicker
            idPrefix="cis"
            required
            seriesId={seriesId}
            eventId={null}
            autoLabel="Each import places itself"
            onSeriesChange={(id, s) => {
              setSeriesId(id)
              setSeriesName(s?.name ?? null)
            }}
            onEventChange={() => {}}
            onError={setError}
          />
        </div>
      ) : (
        <p className="cis-intro">
          Committing to <strong>{seriesLabel}</strong>. Check each session is under the right event —
          drag to move, or use “Move to…”. Round numbers are set by date.
        </p>
      )}

      <div className="cis-groups">
        {groups
          .filter((g) => g.itemKeys.length > 0)
          .map((g) => (
            <section
              key={g.key}
              className={`cis-group${g.eventId != null ? ' attach' : ''}${dropTarget === g.key ? ' drop-active' : ''}`}
              onDragOver={(e) => {
                if (dragKey) {
                  e.preventDefault()
                  setDropTarget(g.key)
                }
              }}
              onDragLeave={(e) => {
                if (e.currentTarget === e.target) setDropTarget(null)
              }}
              onDrop={(e) => {
                e.preventDefault()
                if (dragKey) moveItem(dragKey, g.key)
                setDropTarget(null)
                setDragKey(null)
              }}
            >
              <header className="cis-group-head">
                {g.eventId != null ? (
                  <span className="cis-group-name attached" title="Existing event">
                    {g.name}
                  </span>
                ) : (
                  <input
                    className="cis-group-name"
                    value={g.name}
                    aria-label="Event name"
                    onChange={(e) => renameGroup(g.key, e.target.value)}
                  />
                )}
                <span className="cis-group-date">{g.date ? formatEventDate(g.date, new Date(g.date).getFullYear()) : 'no date'}</span>
                {g.eventId != null && <span className="cis-tag">existing</span>}
                <select
                  className="cis-attach"
                  aria-label="Attach to an existing event"
                  value={g.eventId ?? ''}
                  onChange={(e) => attachGroupToEvent(g.key, e.target.value === '' ? null : Number(e.target.value))}
                >
                  <option value="">New event</option>
                  {eventsForSeries.map((e) => (
                    <option key={e.id} value={e.id}>
                      {e.name}
                    </option>
                  ))}
                </select>
              </header>

              <ul className="cis-items">
                {g.itemKeys.map((key) => {
                  const it = itemByKey.get(key)
                  if (!it) return null
                  return (
                    <li
                      key={key}
                      className="cis-item"
                      draggable
                      aria-roledescription="Draggable session"
                      onDragStart={(e) => {
                        e.dataTransfer.setData('text/plain', key)
                        e.dataTransfer.effectAllowed = 'move'
                        setDragKey(key)
                      }}
                      onDragEnd={() => {
                        setDragKey(null)
                        setDropTarget(null)
                      }}
                    >
                      <span className="cis-grip" aria-hidden="true">
                        ⋮⋮
                      </span>
                      <span className="cis-item-main">
                        <span className="cis-item-label">{it.label}</span>
                        <span className="cis-item-batches">
                          {it.batches.map((b) => (
                            <span key={b.id} className="cis-batch">
                              {KIND_LABEL[b.kind] ?? b.kind}
                              {b.detail ? ` · ${b.detail}` : ''}
                            </span>
                          ))}
                        </span>
                      </span>
                      <label className="cis-move">
                        <span className="sr-only">Move {it.label} to…</span>
                        <select
                          value={g.key}
                          onChange={(e) => moveItem(key, e.target.value === '__new__' ? 'new' : e.target.value)}
                        >
                          {groups
                            .filter((x) => x.itemKeys.length > 0)
                            .map((x) => (
                              <option key={x.key} value={x.key}>
                                {x.name}
                              </option>
                            ))}
                          <option value="__new__">New event…</option>
                        </select>
                      </label>
                    </li>
                  )
                })}
              </ul>
            </section>
          ))}
      </div>

      {preview && (
        <div className="cis-preview">
          {preview.map((pr) => (
            <div key={pr.year} className="cis-preview-year">
              <span className="cis-preview-title">{pr.year} rounds after import</span>
              <ol className="cis-preview-list">
                {pr.entries.map((en, i) => (
                  <li key={`${en.name}-${i}`} className={en.isNew ? 'cis-preview-row new' : 'cis-preview-row'}>
                    <span className="cis-rd">Rd {i + 1}</span>
                    <span className="cis-preview-name">{en.name}</span>
                  </li>
                ))}
              </ol>
            </div>
          ))}
        </div>
      )}

      {includedStandings.length > 0 && (
        <div className="cis-standings">
          <span className="cis-standings-title">Championship standings</span>
          <ul className="cis-items">
            {includedStandings.map((b) => (
              <li key={b.id} className="cis-item plain">
                <span className={`cis-item-status ${stateOf(b.id).status}`} aria-hidden="true">
                  <ImportStatusIcon state={stateOf(b.id).status} />
                </span>
                <span className="cis-item-main">
                  <span className="cis-item-label">{b.detail || KIND_LABEL[b.kind]}</span>
                </span>
              </li>
            ))}
          </ul>
        </div>
      )}

      {leftoverIds.length > 0 && (
        <p className="cis-leftover">
          {leftoverIds.length} batch{leftoverIds.length === 1 ? '' : 'es'} need review — they stay staged
          in the table below.
        </p>
      )}

      <footer className="cis-foot">
        {phase === 'done' ? (
          <>
            <p className={anyFailed ? 'cis-foot-note err' : 'cis-foot-note ok'}>
              {anyFailed
                ? `Committed ${committedIds.length}; some failed — see the table below.`
                : `Committed ${committedIds.length} batch${committedIds.length === 1 ? '' : 'es'}.`}
            </p>
            {anyFailed && (
              <button type="button" className="btn" onClick={() => void commit(true)}>
                Retry failed
              </button>
            )}
            <button type="button" className="btn btn-primary" onClick={finish}>
              Done
            </button>
          </>
        ) : (
          <>
            <p className="cis-foot-note">
              {commitCount > 0
                ? `${commitCount} batch${commitCount === 1 ? '' : 'es'} in ${groups.filter((g) => g.itemKeys.length > 0).length} event${groups.filter((g) => g.itemKeys.length > 0).length === 1 ? '' : 's'}.`
                : 'Nothing here can be committed — finish in the table below.'}
            </p>
            <button type="button" className="btn" onClick={onBack}>
              Back
            </button>
            <button
              type="button"
              className="btn btn-primary"
              disabled={!canCommit}
              onClick={() => void commit(false)}
            >
              {phase === 'committing' ? 'Committing…' : `Commit ${commitCount || ''}`.trim()}
            </button>
          </>
        )}
      </footer>
    </div>
  )
}
