import './import-status-icon.css'

/**
 * The per-item status glyph shared by the upload queue and the confirm-commit
 * list: a quiet dot at rest, a spinner while working, a check on success, an ✕
 * on failure, a dash when skipped. Colour comes from the parent's status class
 * (so it stays with the domain vocabulary); this only draws the shape.
 */
export type ImportIconState = 'idle' | 'busy' | 'ok' | 'error' | 'skip'

export default function ImportStatusIcon({ state }: { state: ImportIconState }) {
  if (state === 'ok') {
    return (
      <svg width="15" height="15" viewBox="0 0 15 15" fill="none" aria-hidden="true">
        <path d="M3 8 L6 11 L12 4" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" />
      </svg>
    )
  }
  if (state === 'error') {
    return (
      <svg width="15" height="15" viewBox="0 0 15 15" fill="none" aria-hidden="true">
        <path d="M4 4 L11 11 M11 4 L4 11" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
      </svg>
    )
  }
  if (state === 'skip') {
    return (
      <svg width="15" height="15" viewBox="0 0 15 15" fill="none" aria-hidden="true">
        <path d="M3.5 7.5 H11.5" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
      </svg>
    )
  }
  if (state === 'busy') {
    return (
      <svg className="isi-spin" width="15" height="15" viewBox="0 0 15 15" fill="none" aria-hidden="true">
        <circle cx="7.5" cy="7.5" r="5.5" stroke="currentColor" strokeWidth="1.6" strokeOpacity="0.25" />
        <path d="M7.5 2 A5.5 5.5 0 0 1 13 7.5" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
      </svg>
    )
  }
  // idle — a quiet dot
  return (
    <svg width="15" height="15" viewBox="0 0 15 15" fill="none" aria-hidden="true">
      <circle cx="7.5" cy="7.5" r="2.5" fill="currentColor" />
    </svg>
  )
}
