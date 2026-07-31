import { useEffect, useState, type ChangeEvent, type CSSProperties } from 'react'
import './pit-lane-modal.css'

interface Landmark {
  afterBox: number
  label: string
}

interface AssignmentRow {
  boxNumber: number
  carNumber: string
  teamName: string | null
  entryId: number | null
  entryTeam: string | null
  className: string | null
}

interface PitAssignments {
  filename: string | null
  uploadedAt: string
  version: number
  versionNote: string | null
  rows: AssignmentRow[]
  landmarks: Landmark[]
}

interface Proposal {
  versionNote: string | null
  seriesColumn: string
  matchCounts: Record<string, number>
  rows: AssignmentRow[]
  landmarks: Landmark[]
}

export interface PitLaneEntry {
  entryId: number
  carNumber: string
  teamName: string
  className: string
}

/** Landmark styling by what the label names, not its exact wording — IMSA
 *  rephrases these sheet to sheet. */
function landmarkKind(label: string): string {
  const l = label.toUpperCase()
  if (l.includes('PENALTY')) return 'penalty'
  if (l.replace(/\s/g, '').includes('S/F') || l.includes('TIMING')) return 'sf'
  if (l.startsWith('PIT IN') || l.startsWith('PIT OUT')) return 'end'
  return 'break'
}

/** The lane in physical order: rows, landmark dividers, and collapsed runs of
 *  boxes used only by the other series sharing the lane. */
function laneItems(rows: AssignmentRow[], landmarks: Landmark[]) {
  const rowByBox = new Map(rows.map((r) => [r.boxNumber, r]))
  const marksAfter = new Map<number, Landmark[]>()
  landmarks.forEach((m) => {
    const list = marksAfter.get(m.afterBox) ?? []
    list.push(m)
    marksAfter.set(m.afterBox, list)
  })
  const maxBox = Math.max(0, ...rows.map((r) => r.boxNumber), ...landmarks.map((m) => m.afterBox))

  const items: ({ kind: 'row'; row: AssignmentRow } | { kind: 'mark'; mark: Landmark } | { kind: 'gap'; count: number })[] = []
  let gap = 0
  const flushGap = () => {
    if (gap > 0) items.push({ kind: 'gap', count: gap })
    gap = 0
  }
  for (const mark of marksAfter.get(0) ?? []) items.push({ kind: 'mark', mark })
  for (let box = 1; box <= maxBox; box++) {
    const row = rowByBox.get(box)
    if (row) {
      flushGap()
      items.push({ kind: 'row', row })
    } else {
      gap++
    }
    const marks = marksAfter.get(box)
    if (marks) {
      flushGap()
      marks.forEach((mark) => items.push({ kind: 'mark', mark }))
    }
  }
  flushGap()
  return items
}

export default function PitLaneModal({
  eventId,
  entries,
  classColors,
  isAdmin,
  onClose,
  onDataChanged,
}: {
  eventId: number
  entries: PitLaneEntry[]
  classColors: Record<string, string>
  isAdmin: boolean
  onClose: () => void
  /** The sheet caches pitAssignmentsVersion; saves and deletes invalidate it. */
  onDataChanged: () => void
}) {
  const [saved, setSaved] = useState<PitAssignments | null>(null)
  const [proposal, setProposal] = useState<Proposal | null>(null)
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    void fetch(`/api/events/${eventId}/pit-assignments`)
      .then((r) =>
        r.ok ? r.json() : r.status === 404 ? null : Promise.reject(new Error(`Backend returned ${r.status}`)),
      )
      .then((data) => {
        if (!cancelled) setSaved(data)
      })
      .catch((e) => {
        if (!cancelled) setError(e instanceof Error ? e.message : 'Failed to load pit assignments')
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [eventId])

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    document.addEventListener('keydown', onKey)
    const previousOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    return () => {
      document.removeEventListener('keydown', onKey)
      document.body.style.overflow = previousOverflow
    }
  }, [onClose])

  function upload(ev: ChangeEvent<HTMLInputElement>) {
    const file = ev.target.files?.[0]
    ev.target.value = '' // allow re-selecting the same file
    if (!file) return
    const form = new FormData()
    form.append('file', file)
    setBusy(true)
    setError(null)
    void fetch(`/api/events/${eventId}/pit-assignments/upload`, { method: 'POST', body: form })
      .then(async (r) => {
        if (!r.ok) {
          const body = await r.json().catch(() => null)
          throw new Error(body?.message ?? `Backend returned ${r.status}`)
        }
        setProposal(await r.json())
      })
      .catch((e) => setError(e instanceof Error ? e.message : 'Upload failed'))
      .finally(() => setBusy(false))
  }

  function setRowEntry(boxNumber: number, entryId: number | null) {
    if (!proposal) return
    const entry = entries.find((e) => e.entryId === entryId) ?? null
    setProposal({
      ...proposal,
      rows: proposal.rows.map((r) =>
        r.boxNumber === boxNumber
          ? {
              ...r,
              entryId,
              entryTeam: entry ? entry.teamName : null,
              className: entry ? entry.className : null,
            }
          : r,
      ),
    })
  }

  function confirm() {
    if (!proposal) return
    setBusy(true)
    setError(null)
    void fetch(`/api/events/${eventId}/pit-assignments`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        rows: proposal.rows.map((r) => ({
          boxNumber: r.boxNumber,
          carNumber: r.carNumber,
          teamName: r.teamName,
          entryId: r.entryId,
        })),
        landmarks: proposal.landmarks,
      }),
    })
      .then(async (r) => {
        if (!r.ok) {
          const body = await r.json().catch(() => null)
          throw new Error(body?.message ?? `Backend returned ${r.status}`)
        }
        setSaved(await r.json())
        setProposal(null)
        onDataChanged()
      })
      .catch((e) => setError(e instanceof Error ? e.message : 'Save failed'))
      .finally(() => setBusy(false))
  }

  function remove() {
    setBusy(true)
    setError(null)
    void fetch(`/api/events/${eventId}/pit-assignments`, { method: 'DELETE' })
      .then((r) => {
        if (!r.ok && r.status !== 404) throw new Error(`Backend returned ${r.status}`)
        setSaved(null)
        setProposal(null)
        onDataChanged()
      })
      .catch((e) => setError(e instanceof Error ? e.message : 'Delete failed'))
      .finally(() => setBusy(false))
  }

  const confirmed = saved != null && saved.rows.length > 0
  const unmatched = proposal?.rows.filter((r) => r.entryId == null).length ?? 0

  return (
    <div className="pl-overlay no-print" onClick={onClose}>
      <div className="pl-dialog" onClick={(e) => e.stopPropagation()} role="dialog" aria-label="Pit lane assignments">
        <header className="pl-header">
          <div>
            <span className="pl-title">Pit lane</span>
            {proposal ? (
              <span className="pl-sub">
                reviewing {proposal.versionNote ?? 'uploaded PDF'} · column {proposal.seriesColumn}
              </span>
            ) : (
              confirmed && (
                <span className="pl-sub">
                  {saved.versionNote ?? saved.filename ?? 'assignments'}
                  {' · uploaded '}
                  {new Date(saved.uploadedAt).toLocaleDateString()}
                </span>
              )
            )}
          </div>
          <button className="pl-close" onClick={onClose} aria-label="Close">
            ✕
          </button>
        </header>

        <div className="pl-body">
          {error && <p className="pl-error">{error}</p>}
          {loading && <p className="pl-status">Loading pit assignments…</p>}

          {!loading && proposal && (
            <>
              <p className="pl-review-hint">
                {proposal.rows.length} boxes from the PDF
                {unmatched > 0
                  ? `; ${unmatched} car${unmatched === 1 ? '' : 's'} didn't match an entry — fix or leave off the lane.`
                  : ' — every car matched an entry.'}
              </p>
              <div className="pl-scroll">
                <table className="pl-review">
                <thead>
                  <tr>
                    <th>Box</th>
                    <th>Car (PDF)</th>
                    <th>Team (PDF)</th>
                    <th>Entry</th>
                  </tr>
                </thead>
                <tbody>
                  {proposal.rows.map((r) => (
                    <tr key={r.boxNumber} className={r.entryId == null ? 'pl-unmatched' : undefined}>
                      <td>{r.boxNumber}</td>
                      <td>#{r.carNumber}</td>
                      <td>{r.teamName}</td>
                      <td>
                        <select
                          value={r.entryId ?? ''}
                          disabled={busy}
                          onChange={(ev) =>
                            setRowEntry(r.boxNumber, ev.target.value === '' ? null : Number(ev.target.value))
                          }
                        >
                          <option value="">— not on the sheet —</option>
                          {entries.map((e) => (
                            <option key={e.entryId} value={e.entryId}>
                              #{e.carNumber} {e.teamName} ({e.className})
                            </option>
                          ))}
                        </select>
                      </td>
                    </tr>
                  ))}
                  </tbody>
                </table>
              </div>
            </>
          )}

          {!loading && !proposal && confirmed && (
            <ol className="pl-lane">
              {laneItems(saved.rows, saved.landmarks).map((item, i) => {
                if (item.kind === 'mark') {
                  return (
                    <li key={i} className={`pl-mark pl-mark-${landmarkKind(item.mark.label)}`}>
                      {item.mark.label}
                    </li>
                  )
                }
                if (item.kind === 'gap') {
                  return (
                    <li key={i} className="pl-gap" aria-label={`${item.count} boxes used by other series`}>
                      {item.count === 1 ? '1 box' : `${item.count} boxes`} · other series
                    </li>
                  )
                }
                const r = item.row
                const color = r.className != null ? classColors[r.className] : undefined
                return (
                  <li key={i} className="pl-row" style={{ '--class-color': color ?? 'var(--border)' } as CSSProperties}>
                    <span className="pl-box">{r.boxNumber}</span>
                    <span className="pl-car">#{r.carNumber}</span>
                    <span className="pl-team">{r.entryTeam ?? r.teamName ?? ''}</span>
                    {r.className && <span className="pl-class">{r.className}</span>}
                  </li>
                )
              })}
            </ol>
          )}

          {!loading && !proposal && !confirmed && !error && (
            <p className="pl-status">
              {isAdmin
                ? 'No pit assignments yet — upload the event’s IMSA pit-lane-assignments PDF below.'
                : 'No pit assignments for this event yet.'}
            </p>
          )}
        </div>

        {isAdmin && (
          <footer className="pl-footer">
            {proposal ? (
              <>
                <button className="btn" onClick={confirm} disabled={busy}>
                  Save assignments
                </button>
                <button className="btn" onClick={() => setProposal(null)} disabled={busy}>
                  Cancel
                </button>
              </>
            ) : (
              <>
                <label className="pl-upload">
                  {confirmed ? 'Replace PDF (new version):' : 'Upload PDF:'}{' '}
                  <input type="file" accept="application/pdf" onChange={upload} disabled={busy} />
                </label>
                {saved != null && (
                  <>
                    <a href={`/api/events/${eventId}/pit-assignments/data?v=${saved.version}`} target="_blank" rel="noreferrer">
                      open PDF
                    </a>
                    <button className="btn" onClick={remove} disabled={busy}>
                      Remove
                    </button>
                  </>
                )}
              </>
            )}
          </footer>
        )}
      </div>
    </div>
  )
}
