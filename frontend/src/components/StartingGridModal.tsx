import { useEffect, useRef } from 'react'
import type { GridRow } from '../lib/api'
import './starting-grid-modal.css'

/**
 * The starting grid as a grid — pole at the front, cars staggered left/right the
 * way they actually line up, rather than as another 40-row table stacked above
 * the classification.
 *
 * The class filter deliberately does not remove slots: a grid is a fact about
 * the whole field, and holes in the stagger would misrepresent it. Filtered-in
 * cars lift onto `--bg` instead (the same "active segment" vocabulary as `.seg`),
 * so the rest stays legible context rather than dimmed-to-unreadable.
 */
export default function StartingGridModal({
  rows,
  title,
  classColor,
  classFilter,
  onClose,
}: {
  rows: GridRow[]
  title: string
  classColor: (className: string | null | undefined) => string
  classFilter: string | null
  onClose: () => void
}) {
  const dialogRef = useRef<HTMLDialogElement>(null)

  useEffect(() => {
    const d = dialogRef.current
    if (d && !d.open) d.showModal()
  }, [])

  const hasTimes = rows.some((r) => r.qualifyingTime)

  return (
    <dialog
      className="sg no-print"
      ref={dialogRef}
      aria-label={`Starting grid: ${title}`}
      onCancel={(e) => {
        e.preventDefault()
        onClose()
      }}
      onClick={(e) => {
        if (e.target === dialogRef.current) onClose()
      }}
    >
      <header className="sg-head">
        <div className="sg-id">
          <h2 className="sg-title">Starting grid</h2>
          <p className="sg-sub">
            {title}
            {classFilter && <> · all classes shown</>}
          </p>
        </div>
        <button type="button" className="sg-close" aria-label="Close" onClick={onClose}>
          ✕
        </button>
      </header>

      <div className="sg-scroll">
        <p className="sg-line" aria-hidden="true">
          <span>Start line</span>
        </p>
        <ol className="sg-slots">
          {rows.map((r) => {
            const on = !classFilter || r.className === classFilter
            return (
              <li
                key={`${r.carNumber}-${r.posOverall ?? ''}`}
                className={on ? 'sg-slot on' : 'sg-slot'}
                style={{ '--class-color': classColor(r.className) } as React.CSSProperties}
              >
                <span className="sg-pos">{r.posOverall ?? '—'}</span>
                <span className="sg-car">
                  <span className="sg-car-head">
                    <span className="sg-no">{r.carNumber}</span>
                    {r.className && <span className="class-tag">{r.className}</span>}
                  </span>
                  <span className="sg-team" title={r.teamName ?? undefined}>
                    {r.teamName ?? '—'}
                  </span>
                  {hasTimes && <span className="sg-time">{r.qualifyingTime ?? '—'}</span>}
                </span>
              </li>
            )
          })}
        </ol>
      </div>
    </dialog>
  )
}
