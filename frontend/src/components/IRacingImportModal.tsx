import { useEffect, useMemo, useRef, useState } from 'react'
import './iracing-import-modal.css'

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

// "subsession-80968360.json" → 80968360, for grouping batches by their round.
function subsessionOf(filename: string): string {
  return filename.replace(/^subsession-/, '').replace(/\.json$/, '')
}

// The circuit / championship name a batch summary leads with, before the " — ".
function groupName(summary: string | null): string {
  return summary?.split(' — ')[0] ?? 'Import'
}

// The half of the summary after the circuit — "Qualifying, 30 classified entries".
function batchDetail(summary: string | null): string {
  const i = summary?.indexOf(' — ') ?? -1
  return i >= 0 ? (summary as string).slice(i + 3) : (summary ?? '')
}

const KIND_LABEL: Record<string, string> = {
  RACE_RESULTS: 'Results',
  GRID: 'Grid',
  STANDINGS: 'Standings',
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
}: {
  onClose: () => void
  onStaged: () => void
}) {
  const dialogRef = useRef<HTMLDialogElement>(null)
  const [mode, setMode] = useState<Mode>('subsession')

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

  // Batches grouped by their subsession, in first-seen order — the season's shape.
  const groups = useMemo(() => {
    if (!result) return []
    const map = new Map<string, { name: string; sub: string; batches: StagedBatch[] }>()
    for (const b of result.batches) {
      const sub = subsessionOf(b.filename)
      if (!map.has(b.filename)) map.set(b.filename, { name: groupName(b.summary), sub, batches: [] })
      map.get(b.filename)!.batches.push(b)
    }
    return [...map.values()]
  }, [result])

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
      setResult(body as IRacingImport)
      onStaged()
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
          <ResultView result={result} groups={groups} onImportMore={reset} onDone={onClose} />
        ) : (
          <>
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

function ResultView({
  result,
  groups,
  onImportMore,
  onDone,
}: {
  result: IRacingImport
  groups: { name: string; sub: string; batches: StagedBatch[] }[]
  onImportMore: () => void
  onDone: () => void
}) {
  return (
    <div className="ir-result">
      <p className="ir-result-line">
        Staged <strong>{result.staged}</strong> of {result.requested}
        {result.requested === 1 ? ' subsession' : ' subsessions'} — {result.batches.length} batch
        {result.batches.length === 1 ? '' : 'es'}.
      </p>

      {groups.length > 0 && (
        <ul className="ir-groups">
          {groups.map((g) => (
            <li key={g.sub} className="ir-group">
              <div className="ir-group-head">
                <span className="ir-group-name">{g.name}</span>
                <span className="ir-group-sub num">#{g.sub}</span>
              </div>
              <ul className="ir-group-batches">
                {g.batches.map((b) => (
                  <li key={b.id} className="ir-batch">
                    <span className="ir-batch-kind">{KIND_LABEL[b.kind] ?? b.kind}</span>
                    <span className="ir-batch-detail">{batchDetail(b.summary)}</span>
                  </li>
                ))}
              </ul>
            </li>
          ))}
        </ul>
      )}

      {result.failures.length > 0 && (
        <div className="ir-failures" role="alert">
          <p className="ir-failures-head">
            {result.failures.length} couldn’t be imported
          </p>
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

      <p className="ir-result-note">
        Staged rounds are listed below — confirm the series and event for each, then commit.
      </p>
      <div className="ir-actions">
        <button type="button" className="btn" onClick={onImportMore}>
          Import more
        </button>
        <button type="button" className="btn-primary" onClick={onDone}>
          Done
        </button>
      </div>
    </div>
  )
}
