import { useEffect, useRef, useState, type ChangeEvent, type CSSProperties } from 'react'
import { averageFixes, guide, guidanceText, type FixSample } from '../lib/pitLaneGeo'
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

interface Anchor {
  boxNumber: number
  lat: number
  lng: number
  accuracyM: number | null
}

interface PitAssignments {
  filename: string | null
  uploadedAt: string
  version: number
  versionNote: string | null
  rows: AssignmentRow[]
  landmarks: Landmark[]
  anchors: Anchor[]
}

interface Fix {
  lat: number
  lng: number
  accuracy: number
}

/** Guidance target: a saved row the user tapped. */
interface Target {
  boxNumber: number
  carNumber: string
  team: string
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
  const [anchorsOpen, setAnchorsOpen] = useState(false)
  const [anchorBox, setAnchorBox] = useState('')
  const [anchorError, setAnchorError] = useState<string | null>(null)
  const [target, setTarget] = useState<Target | null>(null)
  const [fix, setFix] = useState<Fix | null>(null)
  const [geoError, setGeoError] = useState<string | null>(null)
  const watchRef = useRef<number | null>(null)
  const [sampling, setSampling] = useState<{ box: number; secondsLeft: number; count: number; acc: number | null } | null>(null)
  const samplesRef = useRef<FixSample[]>([])
  const sampleTimersRef = useRef<{ watch: number; tick: ReturnType<typeof setInterval> } | null>(null)

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

  // One position watch for as long as a guidance target is set; released on
  // dismiss/close so the modal never drains a battery in a pocket.
  useEffect(() => {
    if (!target) {
      setFix(null)
      setGeoError(null)
      return
    }
    if (!('geolocation' in navigator)) {
      setGeoError('This device offers no location access.')
      return
    }
    const id = navigator.geolocation.watchPosition(
      (pos) => {
        setGeoError(null)
        setFix({ lat: pos.coords.latitude, lng: pos.coords.longitude, accuracy: pos.coords.accuracy })
      },
      (err) =>
        setGeoError(
          err.code === err.PERMISSION_DENIED
            ? 'Location is blocked — allow it for this site to get guidance.'
            : 'No GPS fix yet — step away from the garage overhang and retry.',
        ),
      { enableHighAccuracy: true, maximumAge: 2000, timeout: 15000 },
    )
    watchRef.current = id
    return () => {
      navigator.geolocation.clearWatch(id)
      watchRef.current = null
    }
  }, [target])

  /** How long "Mark my location" samples before averaging. Long enough for
   *  the receiver to settle and jitter to cancel, short enough to stand still
   *  for in a working pit lane. */
  const SAMPLE_SECONDS = 10

  function stopSampling() {
    const timers = sampleTimersRef.current
    if (timers) {
      navigator.geolocation.clearWatch(timers.watch)
      clearInterval(timers.tick)
      sampleTimersRef.current = null
    }
    setSampling(null)
  }

  function saveAnchor(box: number, lat: number, lng: number, accuracyM: number) {
    setBusy(true)
    void fetch(`/api/events/${eventId}/pit-assignments/anchors/${box}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ lat, lng, accuracyM }),
    })
      .then(async (r) => {
        if (!r.ok) {
          const body = await r.json().catch(() => null)
          throw new Error(body?.message ?? `Backend returned ${r.status}`)
        }
        setSaved(await r.json())
        setAnchorBox('')
      })
      .catch((e) => setAnchorError(e instanceof Error ? e.message : 'Save failed'))
      .finally(() => setBusy(false))
  }

  /** Sample fixes for SAMPLE_SECONDS and save the weighted average — one
   *  snapshot fix proved too jumpy against pit-building multipath. */
  function markAnchor() {
    const box = Number(anchorBox)
    if (!Number.isInteger(box) || box < 1) {
      setAnchorError('Enter the box number you are standing at.')
      return
    }
    if (!('geolocation' in navigator)) {
      setAnchorError('This device offers no location access.')
      return
    }
    setAnchorError(null)
    samplesRef.current = []
    const watch = navigator.geolocation.watchPosition(
      (pos) => {
        samplesRef.current.push({
          lat: pos.coords.latitude,
          lng: pos.coords.longitude,
          accuracy: pos.coords.accuracy,
        })
        setSampling((s) =>
          s && { ...s, count: samplesRef.current.length, acc: pos.coords.accuracy },
        )
      },
      (err) => {
        // A transient timeout mid-window shouldn't void the fixes already
        // collected; only a hard denial ends the capture.
        if (err.code === err.PERMISSION_DENIED) {
          stopSampling()
          setAnchorError('Location is blocked — allow it for this site to mark anchors.')
        }
      },
      { enableHighAccuracy: true, maximumAge: 0, timeout: 15000 },
    )
    let secondsLeft = SAMPLE_SECONDS
    const tick = setInterval(() => {
      secondsLeft -= 1
      if (secondsLeft > 0) {
        setSampling((s) => s && { ...s, secondsLeft })
        return
      }
      stopSampling()
      const averaged = averageFixes(samplesRef.current)
      if (!averaged) {
        setAnchorError('No GPS fixes arrived — try again in the open.')
        return
      }
      saveAnchor(box, averaged.lat, averaged.lng, averaged.accuracyM)
    }, 1000)
    sampleTimersRef.current = { watch, tick }
    setSampling({ box, secondsLeft: SAMPLE_SECONDS, count: 0, acc: null })
  }

  // A capture abandoned by closing the modal must not leave a GPS watch (and
  // a pending save) running behind the sheet.
  useEffect(() => stopSampling, [])

  function removeAnchor(box: number) {
    setBusy(true)
    setAnchorError(null)
    void fetch(`/api/events/${eventId}/pit-assignments/anchors/${box}`, { method: 'DELETE' })
      .then(async (r) => {
        if (!r.ok) throw new Error(`Backend returned ${r.status}`)
        setSaved(await r.json())
      })
      .catch((e) => setAnchorError(e instanceof Error ? e.message : 'Delete failed'))
      .finally(() => setBusy(false))
  }

  const confirmed = saved != null && saved.rows.length > 0
  const unmatched = proposal?.rows.filter((r) => r.entryId == null).length ?? 0
  const anchors = saved?.anchors ?? []
  const guidance = target && fix && anchors.length >= 2 ? guide(anchors, fix, target.boxNumber) : null

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
                const team = r.entryTeam ?? r.teamName ?? ''
                return (
                  <li key={i} className="pl-row" style={{ '--class-color': color ?? 'var(--border)' } as CSSProperties}>
                    <button
                      type="button"
                      className="pl-rowbtn"
                      aria-label={`Guide me to box ${r.boxNumber}, #${r.carNumber} ${team}`}
                      onClick={() => setTarget({ boxNumber: r.boxNumber, carNumber: r.carNumber, team })}
                    >
                      <span className="pl-box">{r.boxNumber}</span>
                      <span className="pl-car">#{r.carNumber}</span>
                      <span className="pl-team">{team}</span>
                      {r.className && <span className="pl-class">{r.className}</span>}
                    </button>
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

        {target && (
          <div className="pl-guide" role="status">
            <div className="pl-guide-line">
              <strong>
                #{target.carNumber} {target.team}
              </strong>
              {' · box '}
              {target.boxNumber}
            </div>
            <div className="pl-guide-line">
              {anchors.length < 2 ? (
                isAdmin ? (
                  'Set at least two GPS anchors below to enable guidance.'
                ) : (
                  'GPS guidance is not set up for this event yet.'
                )
              ) : geoError ? (
                geoError
              ) : !guidance ? (
                'Locating…'
              ) : guidance.arrived ? (
                <strong>You're at box {target.boxNumber}</strong>
              ) : (
                <>
                  {guidanceText(guidance)} toward <strong>{guidance.direction === 'pit-in' ? 'pit in' : 'pit out'}</strong>
                  {' · '}you're near box {Math.round(guidance.currentBox)}
                  {fix && fix.accuracy > 25 && (
                    <span className="pl-guide-weak"> · GPS weak (±{Math.round(fix.accuracy * 3.28084)} ft)</span>
                  )}
                </>
              )}
            </div>
            <button className="pl-close" onClick={() => setTarget(null)} aria-label="Stop guidance">
              ✕
            </button>
          </div>
        )}

        {isAdmin && anchorsOpen && !proposal && saved != null && (
          <div className="pl-anchors">
            <div className="pl-anchor-list">
              {anchors.length === 0 && <span className="pl-anchor-hint">No anchors yet — stand at a box and mark it.</span>}
              {anchors.map((a) => (
                <span key={a.boxNumber} className="pl-anchor">
                  box {a.boxNumber}
                  {a.accuracyM != null && <span className="pl-anchor-acc"> ±{Math.round(a.accuracyM)} m</span>}
                  <button
                    type="button"
                    onClick={() => removeAnchor(a.boxNumber)}
                    disabled={busy}
                    aria-label={`Remove anchor at box ${a.boxNumber}`}
                  >
                    ✕
                  </button>
                </span>
              ))}
            </div>
            <div className="pl-anchor-add">
              {sampling ? (
                <>
                  <span className="pl-sampling" role="status">
                    Sampling box {sampling.box}… {sampling.secondsLeft}s · {sampling.count}{' '}
                    {sampling.count === 1 ? 'fix' : 'fixes'}
                    {sampling.acc != null && <> · ±{Math.round(sampling.acc)} m</>}
                    {' — '}stand still
                  </span>
                  <button className="btn" onClick={stopSampling}>
                    Cancel
                  </button>
                </>
              ) : (
                <>
                  <input
                    type="number"
                    min={1}
                    placeholder="box #"
                    value={anchorBox}
                    onChange={(ev) => setAnchorBox(ev.target.value)}
                    disabled={busy}
                    aria-label="Box number you are standing at"
                  />
                  <button className="btn" onClick={markAnchor} disabled={busy}>
                    Mark my location
                  </button>
                </>
              )}
            </div>
            {anchorError && <p className="pl-error">{anchorError}</p>}
          </div>
        )}

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
                    <button className="btn" onClick={() => setAnchorsOpen((open) => !open)} aria-expanded={anchorsOpen}>
                      GPS anchors ({anchors.length})
                    </button>
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
