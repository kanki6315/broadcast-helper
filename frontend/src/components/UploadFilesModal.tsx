import { useEffect, useRef, useState } from 'react'
import './upload-files-modal.css'
import SeriesEventPicker, { type EventOption, type Series } from './SeriesEventPicker'

/**
 * The file-upload entry point for the Imports page. Pins one series + event as
 * the shared target for a batch, takes files by drag-and-drop / browse / paste,
 * stages each through POST /api/imports, and hands the staged batch ids back so
 * the review table below lands pre-targeted at that series + event.
 *
 * Staging itself carries no target — series/event are applied at commit time —
 * so this modal's job is to group the upload and seed the review, not to commit.
 */

interface StagedBatch {
  id: number
  kind: string
  format: string
  filename: string
  status: string
  summary: string | null
  createdAt: string
}

type FileStatus = 'queued' | 'uploading' | 'staged' | 'error'

interface QueueItem {
  localId: number
  file: File
  status: FileStatus
  error?: string
  batches?: StagedBatch[]
}

// Only the formats Auto-detect can't reach on its own — mirrors ImportsPage.
const FORMAT_OPTIONS: [string, string][] = [
  ['AUTO', 'Auto-detect'],
  ['IMSA_CSV', 'IMSA — Grid CSV'],
  ['IMSA_POINTS_PDF', 'IMSA — Championship points PDF'],
]

const KIND_LABEL: Record<string, string> = {
  RACE_RESULTS: 'Results',
  ENTRY_LIST: 'Entry list',
  STANDINGS: 'Standings',
  GRID: 'Grid',
  FLAGS: 'Flags',
}

const ACCEPT = '.json,.pdf,.csv,application/json,application/pdf,text/csv'

function StatusIcon({ status }: { status: FileStatus }) {
  if (status === 'staged') {
    return (
      <svg width="15" height="15" viewBox="0 0 15 15" fill="none" aria-hidden="true">
        <path d="M3 8 L6 11 L12 4" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" />
      </svg>
    )
  }
  if (status === 'error') {
    return (
      <svg width="15" height="15" viewBox="0 0 15 15" fill="none" aria-hidden="true">
        <path d="M4 4 L11 11 M11 4 L4 11" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
      </svg>
    )
  }
  if (status === 'uploading') {
    return (
      <svg className="uf-spin" width="15" height="15" viewBox="0 0 15 15" fill="none" aria-hidden="true">
        <circle cx="7.5" cy="7.5" r="5.5" stroke="currentColor" strokeWidth="1.6" strokeOpacity="0.25" />
        <path d="M7.5 2 A5.5 5.5 0 0 1 13 7.5" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
      </svg>
    )
  }
  // queued — a quiet dot
  return (
    <svg width="15" height="15" viewBox="0 0 15 15" fill="none" aria-hidden="true">
      <circle cx="7.5" cy="7.5" r="2.5" fill="currentColor" />
    </svg>
  )
}

export default function UploadFilesModal({
  onClose,
  onStaged,
}: {
  onClose: () => void
  /** Hands staged batch ids to the page with the pinned target so the review
   *  table lands pre-filled. eventId is null when the batch isn't pinned. */
  onStaged: (batchIds: number[], seriesId: number | null, eventId: number | null) => void | Promise<void>
}) {
  const dialogRef = useRef<HTMLDialogElement>(null)
  const fileInputRef = useRef<HTMLInputElement>(null)
  const nextId = useRef(0)

  const [seriesId, setSeriesId] = useState<number | null>(null)
  const [seriesObj, setSeriesObj] = useState<Series | null>(null)
  const [eventId, setEventId] = useState<number | null>(null)
  const [eventObj, setEventObj] = useState<EventOption | null>(null)

  const [format, setFormat] = useState('AUTO')
  const [queue, setQueue] = useState<QueueItem[]>([])
  const [dragging, setDragging] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    const d = dialogRef.current
    if (d && !d.open) d.showModal()
  }, [])

  function addFiles(files: File[]) {
    if (files.length === 0) return
    setError(null)
    setQueue((q) => [
      ...q,
      ...files.map((file) => ({ localId: nextId.current++, file, status: 'queued' as const })),
    ])
  }

  function removeItem(localId: number) {
    setQueue((q) => q.filter((it) => it.localId !== localId))
  }

  function setItem(localId: number, patch: Partial<QueueItem>) {
    setQueue((q) => q.map((it) => (it.localId === localId ? { ...it, ...patch } : it)))
  }

  const pending = queue.filter((it) => it.status === 'queued' || it.status === 'error')
  const stagedItems = queue.filter((it) => it.status === 'staged')
  const canStage = seriesId !== null && pending.length > 0 && !busy
  const allDone = queue.length > 0 && pending.length === 0

  async function stageAll() {
    if (seriesId === null) return
    setBusy(true)
    setError(null)
    const staged: number[] = []
    for (const it of queue) {
      if (it.status !== 'queued' && it.status !== 'error') {
        // already staged in a prior pass — carry its ids forward
        it.batches?.forEach((b) => staged.push(b.id))
        continue
      }
      setItem(it.localId, { status: 'uploading', error: undefined })
      try {
        const form = new FormData()
        form.append('file', it.file)
        form.append('format', format)
        const res = await fetch('/api/imports', { method: 'POST', body: form })
        if (!res.ok) {
          const body = await res.json().catch(() => null)
          setItem(it.localId, { status: 'error', error: body?.message ?? `Upload failed (${res.status})` })
          continue
        }
        const batches = (await res.json()) as StagedBatch[]
        setItem(it.localId, { status: 'staged', batches, error: undefined })
        batches.forEach((b) => staged.push(b.id))
      } catch {
        setItem(it.localId, { status: 'error', error: 'Could not reach the server.' })
      }
    }
    setBusy(false)
    if (staged.length > 0) await onStaged(staged, seriesId, eventId)
  }

  function onDrop(e: React.DragEvent) {
    e.preventDefault()
    setDragging(false)
    addFiles(Array.from(e.dataTransfer.files))
  }

  function onPaste(e: React.ClipboardEvent) {
    const files = Array.from(e.clipboardData.files)
    if (files.length > 0) {
      e.preventDefault()
      addFiles(files)
    }
  }

  const eventLabel = eventObj
    ? eventObj.name
    : seriesId !== null
      ? 'Each file places itself'
      : '—'

  return (
    <dialog
      className="uf"
      ref={dialogRef}
      aria-label="Upload files"
      onCancel={(e) => {
        e.preventDefault()
        onClose()
      }}
      onClick={(e) => {
        if (e.target === dialogRef.current) onClose()
      }}
      onPaste={onPaste}
    >
      <header className="uf-head">
        <div>
          <h2 className="uf-title">Upload files</h2>
          <p className="uf-sub">
            Stage results, standings, grids or entry lists — grouped under one series and event.
          </p>
        </div>
        <button type="button" className="uf-close" aria-label="Close" onClick={onClose}>
          ✕
        </button>
      </header>

      <div className="uf-body">
        {error && (
          <p className="error-panel uf-error" role="alert">
            {error}
          </p>
        )}

        <SeriesEventPicker
          idPrefix="uf"
          required
          seriesId={seriesId}
          eventId={eventId}
          autoLabel="Each file places itself"
          onSeriesChange={(id, s) => {
            setSeriesId(id)
            setSeriesObj(s)
          }}
          onEventChange={(id, ev) => {
            setEventId(id)
            setEventObj(ev)
          }}
          onError={setError}
        />

        <div
          className={dragging ? 'uf-drop dragging' : 'uf-drop'}
          role="button"
          tabIndex={0}
          aria-label="Add files — drop here, or browse"
          onClick={() => fileInputRef.current?.click()}
          onKeyDown={(e) => {
            if (e.key === 'Enter' || e.key === ' ') {
              e.preventDefault()
              fileInputRef.current?.click()
            }
          }}
          onDragOver={(e) => {
            e.preventDefault()
            setDragging(true)
          }}
          onDragLeave={(e) => {
            if (e.currentTarget === e.target) setDragging(false)
          }}
          onDrop={onDrop}
        >
          <svg className="uf-drop-icon" width="26" height="26" viewBox="0 0 24 24" fill="none" aria-hidden="true">
            <path d="M12 15 V4 M12 4 L8 8 M12 4 L16 8" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" />
            <path d="M4 14 v4 a2 2 0 0 0 2 2 h12 a2 2 0 0 0 2 -2 v-4" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
          </svg>
          <span className="uf-drop-lead">
            Drop files here, or <span className="uf-drop-browse">browse</span>
          </span>
          <span className="uf-drop-types">JSON results · standings · entry-list PDF · grid CSV</span>
          <input
            ref={fileInputRef}
            className="uf-file-input"
            type="file"
            accept={ACCEPT}
            multiple
            onChange={(e) => {
              if (e.target.files) addFiles(Array.from(e.target.files))
              e.target.value = ''
            }}
          />
        </div>

        <details className="uf-format">
          <summary>Format: {FORMAT_OPTIONS.find(([v]) => v === format)?.[1] ?? 'Auto-detect'}</summary>
          <div className="uf-format-body">
            <select value={format} disabled={busy} onChange={(e) => setFormat(e.target.value)}>
              {FORMAT_OPTIONS.map(([value, lbl]) => (
                <option key={value} value={value}>
                  {lbl}
                </option>
              ))}
            </select>
            <span className="uf-format-note">
              Auto-detect reads JSON and PDF. Choose a format only for a grid CSV or a points PDF.
            </span>
          </div>
        </details>

        {queue.length > 0 && (
          <div className="uf-group">
            <div className="uf-group-head">
              <span className="uf-group-series">{seriesObj ? seriesObj.name : 'No series yet'}</span>
              <span className="uf-group-sep" aria-hidden="true">
                ·
              </span>
              <span className="uf-group-event">{eventLabel}</span>
              <span className="uf-group-count">
                {queue.length} file{queue.length === 1 ? '' : 's'}
              </span>
            </div>
            <ul className="uf-files">
              {queue.map((it) => (
                <li key={it.localId} className="uf-file">
                  <span className={`uf-file-status ${it.status}`} aria-hidden="true">
                    <StatusIcon status={it.status} />
                  </span>
                  <span className="uf-file-main">
                    <span className="uf-file-name">{it.file.name}</span>
                    <span className={it.status === 'error' ? 'uf-file-meta err' : 'uf-file-meta'}>
                      {it.status === 'error'
                        ? it.error
                        : it.status === 'uploading'
                          ? 'Uploading…'
                          : it.status === 'staged'
                            ? (it.batches?.[0]?.summary ?? 'Staged')
                            : 'Ready to stage'}
                    </span>
                  </span>
                  {it.status === 'staged' && it.batches && it.batches.length > 0 && (
                    <span className="uf-file-kinds">
                      {[...new Set(it.batches.map((b) => KIND_LABEL[b.kind] ?? b.kind))].map((k) => (
                        <span key={k} className="uf-kind-tag">
                          {k}
                        </span>
                      ))}
                    </span>
                  )}
                  {(it.status === 'queued' || it.status === 'error') && (
                    <button
                      type="button"
                      className="uf-file-remove"
                      aria-label={`Remove ${it.file.name}`}
                      onClick={() => removeItem(it.localId)}
                    >
                      ✕
                    </button>
                  )}
                </li>
              ))}
            </ul>
          </div>
        )}
      </div>

      <footer className="uf-foot">
        {allDone && stagedItems.length > 0 ? (
          <p className="uf-foot-note done">
            Staged {stagedItems.length} file{stagedItems.length === 1 ? '' : 's'} — review below to
            commit.
          </p>
        ) : (
          <p className="uf-foot-note">
            {seriesId === null
              ? 'Choose a series to stage.'
              : pending.length > 0
                ? `${pending.length} file${pending.length === 1 ? '' : 's'} ready.`
                : 'Drop files to begin.'}
          </p>
        )}
        <button type="button" className="btn" onClick={onClose}>
          {allDone ? 'Done' : 'Cancel'}
        </button>
        <button type="button" className="btn btn-primary" disabled={!canStage} onClick={stageAll}>
          {busy
            ? 'Staging…'
            : pending.length > 0
              ? `Stage ${pending.length} file${pending.length === 1 ? '' : 's'}`
              : 'Stage files'}
        </button>
      </footer>
    </dialog>
  )
}
