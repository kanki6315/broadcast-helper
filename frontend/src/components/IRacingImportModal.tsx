import { useEffect, useMemo, useRef, useState } from 'react'
import './iracing-import-modal.css'
import SeriesEventPicker from './SeriesEventPicker'
import ConfirmImportStep from './ConfirmImportStep'

/**
 * The one place iRacing imports start. Two ways in — a hand-picked list of
 * subsession ids, or a whole league season — both landing on the same grouped
 * result so a season reads as a season, not a pile of loose batches. It stages
 * only; the staged rounds drop into the Imports table below for review + commit.
 */

interface StagedBatch {
  id: number
  kind: string
  filename: string
  summary: string | null
  status: string
}
interface Failure {
  subsessionId: number
  track: string | null
  reason: string
}
interface IRacingImport {
  requested: number
  staged: number
  batches: StagedBatch[]
  failures: Failure[]
}
interface LeagueRound {
  subsessionId: number
  launchAt: string | null
  trackName: string | null
  winnerName: string | null
  hasResults: boolean
  entryCount: number
}

type Mode = 'subsession' | 'league'

// iRacing's error bodies arrive raw ('404 Not Found: "{ "message": "…" }"').
// Surface the human sentence, fall back to the part before the JSON.
function cleanReason(reason: string | null): string {
  if (!reason) return 'Import failed.'
  const m = reason.match(/"message"\s*:\s*"([^"]+)"/)
  if (m) return m[1]
  const brace = reason.indexOf('{')
  const head = (brace >= 0 ? reason.slice(0, brace) : reason).trim().replace(/[:\s]+$/, '')
  return head || reason
}

function parseIds(text: string): number[] {
  const ids = (text.match(/\d+/g) ?? []).map(Number)
  return [...new Set(ids)]
}

function formatDate(iso: string | null): string {
  if (!iso) return ''
  const d = new Date(iso)
  return Number.isNaN(d.getTime())
    ? ''
    : d.toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: 'numeric' })
}

export default function IRacingImportModal({
  onClose,
  onStaged,
  onCommitted,
}: {
  onClose: () => void
  /** Hands the staged batch ids back with the pinned target (series may be null
   *  when nothing is pinned) so the review table lands pre-filled. */
  onStaged: (batchIds: number[], seriesId: number | null, eventId: number | null) => void | Promise<void>
  /** After the confirm step commits, refresh the table and seed any leftovers. */
  onCommitted: (r: {
    committedIds: number[]
    leftoverIds: number[]
    seriesId: number | null
    eventId: number | null
  }) => void | Promise<void>
}) {
  const dialogRef = useRef<HTMLDialogElement>(null)
  const [mode, setMode] = useState<Mode>('subsession')

  // Optional pin: stage these imports straight onto one series + event.
  const [seriesId, setSeriesId] = useState<number | null>(null)
  const [eventId, setEventId] = useState<number | null>(null)

  const [idsText, setIdsText] = useState('')
  const [leagueId, setLeagueId] = useState('')
  const [seasonId, setSeasonId] = useState('')

  const [rounds, setRounds] = useState<LeagueRound[] | null>(null)
  const [picked, setPicked] = useState<Set<number>>(new Set())
  const [listing, setListing] = useState(false)

  const [busy, setBusy] = useState<string | null>(null) // the action label while running
  const [error, setError] = useState<string | null>(null)
  const [result, setResult] = useState<IRacingImport | null>(null)

  useEffect(() => {
    const d = dialogRef.current
    if (d && !d.open) d.showModal()
  }, [])

  const ids = useMemo(() => parseIds(idsText), [idsText])
  const leaguePair = leagueId.trim() && seasonId.trim()

  async function run(action: string, req: () => Promise<Response>) {
    setBusy(action)
    setError(null)
    try {
      const res = await req()
      const body = await res.json().catch(() => null)
      if (!res.ok) {
        setError(body?.message ?? `Request failed (${res.status})`)
        return
      }
      // The subsession/season endpoints answer with an IRacingImport
      // ({ batches, failures }); the standings endpoint answers with a bare
      // list of staged batches. Normalize the latter so the confirm step —
      // which reads result.failures and result.batches — never sees undefined.
      const imported: IRacingImport = Array.isArray(body)
        ? { requested: body.length, staged: body.length, batches: body as StagedBatch[], failures: [] }
        : (body as IRacingImport)
      setResult(imported)
      await onStaged(imported.batches.map((b) => b.id), seriesId, eventId)
    } catch {
      setError('Could not reach the server.')
    } finally {
      setBusy(null)
    }
  }

  function importSubsessions() {
    if (!ids.length) return
    void run(`Fetching ${ids.length}`, () =>
      fetch('/api/imports/iracing/subsessions', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ subsessionIds: ids }),
      }),
    )
  }

  async function listRounds() {
    if (!leaguePair) return
    setListing(true)
    setError(null)
    setRounds(null)
    try {
      const res = await fetch(
        `/api/imports/iracing/league/${leagueId.trim()}/season/${seasonId.trim()}/rounds`,
      )
      const body = await res.json().catch(() => null)
      if (!res.ok) {
        setError(body?.message ?? `Could not list rounds (${res.status})`)
        return
      }
      const list = body as LeagueRound[]
      setRounds(list)
      setPicked(new Set(list.filter((r) => r.hasResults).map((r) => r.subsessionId)))
    } catch {
      setError('Could not reach the server.')
    } finally {
      setListing(false)
    }
  }

  function importPicked() {
    const chosen = [...picked]
    if (!chosen.length) return
    void run(`Fetching ${chosen.length}`, () =>
      fetch('/api/imports/iracing/subsessions', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ subsessionIds: chosen }),
      }),
    )
  }

  function importStandings() {
    if (!leaguePair) return
    void run('Fetching standings', () =>
      fetch(
        `/api/imports/iracing/league/${leagueId.trim()}/season/${seasonId.trim()}/standings`,
        { method: 'POST' },
      ),
    )
  }

  function togglePick(id: number) {
    setPicked((p) => {
      const next = new Set(p)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
  }

  function reset() {
    setResult(null)
    setError(null)
  }

  const runnable = rounds?.filter((r) => r.hasResults).length ?? 0
  const allPicked = runnable > 0 && picked.size === runnable

  return (
    <dialog
      className="ir"
      ref={dialogRef}
      aria-label="Import from iRacing"
      onCancel={(e) => {
        e.preventDefault()
        onClose()
      }}
      onClick={(e) => {
        if (e.target === dialogRef.current) onClose()
      }}
    >
      <header className="ir-head">
        <div className="ir-id">
          <h2 className="ir-title">Import from iRacing</h2>
          <p className="ir-sub">Fetch a hand-picked set of races, or a whole league season.</p>
        </div>
        <button type="button" className="ir-close" aria-label="Close" onClick={onClose}>
          ✕
        </button>
      </header>

      <div className="ir-body">
        {result ? (
          <>
            {result.failures.length > 0 && (
              <div className="ir-failures" role="alert">
                <p className="ir-failures-head">{result.failures.length} couldn’t be imported</p>
                <ul>
                  {result.failures.map((f) => (
                    <li key={f.subsessionId}>
                      <span className="num">#{f.subsessionId}</span>
                      {f.track ? ` ${f.track}` : ''} — {cleanReason(f.reason)}
                    </li>
                  ))}
                </ul>
              </div>
            )}
            <ConfirmImportStep
              batchIds={result.batches.map((b) => b.id)}
              pinnedSeriesId={seriesId}
              pinnedEventId={eventId}
              onCommitted={onCommitted}
              onBack={reset}
              onDone={onClose}
            />
          </>
        ) : (
          <>
            <div className="ir-pin">
              <div className="ir-pin-head">
                <span className="ir-pin-title">Pin to</span>
                <span className="ir-pin-note">optional — otherwise each import places itself</span>
              </div>
              <SeriesEventPicker
                idPrefix="ir"
                seriesId={seriesId}
                eventId={eventId}
                autoLabel="Each round places itself"
                onSeriesChange={(id) => setSeriesId(id)}
                onEventChange={(id) => setEventId(id)}
                onError={setError}
              />
            </div>

            <div className="ir-modes" role="tablist" aria-label="Import source">
              <button
                type="button"
                role="tab"
                aria-selected={mode === 'subsession'}
                className={mode === 'subsession' ? 'ir-mode on' : 'ir-mode'}
                onClick={() => setMode('subsession')}
              >
                By subsession
              </button>
              <button
                type="button"
                role="tab"
                aria-selected={mode === 'league'}
                className={mode === 'league' ? 'ir-mode on' : 'ir-mode'}
                onClick={() => setMode('league')}
              >
                By league season
              </button>
            </div>

            {error && (
              <p className="error-panel ir-error" role="alert">
                {error}
              </p>
            )}

            {mode === 'subsession' ? (
              <div className="ir-panel">
                <label className="ir-field">
                  <span className="ir-field-label">Subsession IDs</span>
                  <textarea
                    className="ir-textarea"
                    rows={3}
                    placeholder="Paste subsession IDs — e.g. 81796281, 81460299 81460300"
                    value={idsText}
                    disabled={!!busy}
                    onChange={(e) => setIdsText(e.target.value)}
                    aria-describedby="ir-ids-hint"
                  />
                </label>
                <div className="ir-actions">
                  <span id="ir-ids-hint" className="ir-count">
                    {ids.length === 0
                      ? 'Separate by spaces, commas, or new lines.'
                      : `${ids.length} subsession${ids.length === 1 ? '' : 's'} recognized.`}
                  </span>
                  <button
                    type="button"
                    className="btn-primary"
                    disabled={!ids.length || !!busy}
                    onClick={importSubsessions}
                  >
                    {busy && busy.startsWith('Fetching') ? `${busy}…` : 'Fetch & stage'}
                  </button>
                </div>
              </div>
            ) : (
              <div className="ir-panel">
                <div className="ir-league-ids">
                  <label className="ir-field">
                    <span className="ir-field-label">League ID</span>
                    <input
                      className="ir-input"
                      inputMode="numeric"
                      placeholder="e.g. 6004"
                      value={leagueId}
                      disabled={!!busy}
                      onChange={(e) => setLeagueId(e.target.value)}
                    />
                  </label>
                  <label className="ir-field">
                    <span className="ir-field-label">Season ID</span>
                    <input
                      className="ir-input"
                      inputMode="numeric"
                      placeholder="e.g. 114713"
                      value={seasonId}
                      disabled={!!busy}
                      onChange={(e) => setSeasonId(e.target.value)}
                    />
                  </label>
                  <button
                    type="button"
                    className="btn"
                    disabled={!leaguePair || listing || !!busy}
                    onClick={listRounds}
                  >
                    {listing ? 'Listing…' : 'List rounds'}
                  </button>
                </div>

                {listing && (
                  <ul className="ir-rounds" aria-hidden="true">
                    {[0, 1, 2, 3].map((i) => (
                      <li key={i} className="ir-round">
                        <span className="skeleton ir-skel" />
                      </li>
                    ))}
                  </ul>
                )}

                {rounds && !listing && rounds.length === 0 && (
                  <p className="empty-state">No rounds found for that league and season.</p>
                )}

                {rounds && rounds.length > 0 && !listing && (
                  <>
                    <div className="ir-rounds-head">
                      <button
                        type="button"
                        className="ir-selectall"
                        disabled={!!busy || runnable === 0}
                        onClick={() =>
                          setPicked(
                            allPicked
                              ? new Set()
                              : new Set(rounds.filter((r) => r.hasResults).map((r) => r.subsessionId)),
                          )
                        }
                      >
                        {allPicked ? 'Clear' : 'Select all'}
                      </button>
                      <span className="ir-count">{picked.size} selected</span>
                    </div>
                    <ul className="ir-rounds">
                      {rounds.map((r, i) => (
                        <li key={r.subsessionId} className={r.hasResults ? 'ir-round' : 'ir-round pending'}>
                          <label className="ir-round-pick">
                            <input
                              type="checkbox"
                              checked={picked.has(r.subsessionId)}
                              disabled={!r.hasResults || !!busy}
                              onChange={() => togglePick(r.subsessionId)}
                            />
                          </label>
                          <span className="ir-round-no num">{i + 1}</span>
                          <span className="ir-round-main">
                            <span className="ir-round-track">{r.trackName ?? `Subsession ${r.subsessionId}`}</span>
                            <span className="ir-round-meta">
                              {formatDate(r.launchAt)}
                              {r.winnerName && (
                                <>
                                  {formatDate(r.launchAt) && ' · '}
                                  won by {r.winnerName}
                                </>
                              )}
                              {!r.hasResults && <span className="ir-pending-tag">no results yet</span>}
                            </span>
                          </span>
                        </li>
                      ))}
                    </ul>
                    <div className="ir-actions ir-actions-league">
                      <button type="button" className="btn" disabled={!!busy} onClick={importStandings}>
                        {busy === 'Fetching standings' ? 'Fetching standings…' : 'Import standings'}
                      </button>
                      <button
                        type="button"
                        className="btn-primary"
                        disabled={!picked.size || !!busy}
                        onClick={importPicked}
                      >
                        {busy && busy.startsWith('Fetching') && busy !== 'Fetching standings'
                          ? `${busy}…`
                          : `Import ${picked.size} round${picked.size === 1 ? '' : 's'}`}
                      </button>
                    </div>
                  </>
                )}
              </div>
            )}
          </>
        )}
      </div>
    </dialog>
  )
}
