import { useEffect, useRef, useState } from 'react'
import 'flag-icons/css/flag-icons.min.css'
import './search-palette.css'
import {
  getJson,
  type DriverSearchHit,
  type SearchResults,
  type TeamSearchHit,
} from '../lib/api'
import { flagCode } from '../lib/countries'
import { useInfoModal } from './infoModal'

export const isMacLike = /Mac|iPhone|iPad/.test(navigator.platform || navigator.userAgent)

function SearchIcon() {
  return (
    <svg
      className="sp-icon"
      width="15"
      height="15"
      viewBox="0 0 15 15"
      fill="none"
      aria-hidden="true"
    >
      <circle cx="6.5" cy="6.5" r="4.75" stroke="currentColor" strokeWidth="1.5" />
      <path d="M10.5 10.5 L13.5 13.5" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
    </svg>
  )
}

export { SearchIcon }

type PaletteItem =
  | { kind: 'driver'; driver: DriverSearchHit }
  | { kind: 'team'; team: TeamSearchHit }

function DriverRow({ hit }: { hit: DriverSearchHit }) {
  const flag = flagCode(hit.country)
  return (
    <>
      <span className={`fi ${flag ? `fi-${flag}` : ''} sp-flag`} aria-hidden="true" />
      <span className="sp-name">
        {hit.name}
        {hit.rating && <span className="sp-rating"> ({hit.rating})</span>}
      </span>
      <span className="sp-ctx">
        {hit.carNumber && <span className="sp-car">#{hit.carNumber}</span>}
        {hit.teamName && <> {hit.teamName}</>}
        {hit.className && <> · {hit.className}</>}
        {hit.year != null && <> · {hit.year}</>}
      </span>
    </>
  )
}

function TeamRow({ hit }: { hit: TeamSearchHit }) {
  return (
    <>
      {/* Same-width slot as the driver flag so names align across groups. */}
      <span className="sp-flag" aria-hidden="true" />
      <span className="sp-name">{hit.teamName}</span>
      <span className="sp-ctx">
        {hit.carNumbers && (
          <span className="sp-car">{hit.carNumbers.split(' ').map((n) => `#${n}`).join(' ')}</span>
        )}
        {hit.classNames && <> {hit.classNames}</>}
        {hit.seriesName && (
          <>
            {' '}
            · {hit.seriesName} {hit.year}
          </>
        )}
      </span>
    </>
  )
}

/** ⌘K search over drivers and teams. Results are grouped; one flat keyboard
 * order runs top to bottom across both groups. Enter (or click) opens the
 * matching info modal. */
export default function SearchPalette({ open, onClose }: { open: boolean; onClose: () => void }) {
  const dialogRef = useRef<HTMLDialogElement>(null)
  const inputRef = useRef<HTMLInputElement>(null)
  const seq = useRef(0)
  const { openDriver, openTeam } = useInfoModal()

  const [q, setQ] = useState('')
  const [items, setItems] = useState<PaletteItem[]>([])
  const [searching, setSearching] = useState(false)
  const [active, setActive] = useState(0)

  useEffect(() => {
    const d = dialogRef.current
    if (!d) return
    if (open && !d.open) {
      d.showModal()
      setQ('')
      setItems([])
      setActive(0)
      // showModal focuses the first focusable element, but be explicit so the
      // user can type immediately.
      inputRef.current?.focus()
    } else if (!open && d.open) {
      d.close()
    }
  }, [open])

  // Debounced search; a stale response never overwrites a newer one.
  useEffect(() => {
    if (!open) return
    const query = q.trim()
    if (!query) {
      setItems([])
      setSearching(false)
      return
    }
    setSearching(true)
    const mySeq = ++seq.current
    const t = window.setTimeout(() => {
      getJson<SearchResults>(`/api/search?q=${encodeURIComponent(query)}`)
        .then((r) => {
          if (seq.current !== mySeq) return
          setItems([
            ...r.drivers.map((driver) => ({ kind: 'driver', driver }) as PaletteItem),
            ...r.teams.map((team) => ({ kind: 'team', team }) as PaletteItem),
          ])
          setActive(0)
          setSearching(false)
        })
        .catch(() => {
          if (seq.current !== mySeq) return
          setItems([])
          setSearching(false)
        })
    }, 120)
    return () => window.clearTimeout(t)
  }, [q, open])

  function pick(item: PaletteItem) {
    onClose()
    if (item.kind === 'driver') openDriver(item.driver.id)
    else openTeam(item.team.teamName)
  }

  function onKeyDown(e: React.KeyboardEvent) {
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      e.preventDefault()
      if (items.length === 0) return
      const delta = e.key === 'ArrowDown' ? 1 : -1
      setActive((a) => (a + delta + items.length) % items.length)
    } else if (e.key === 'Enter') {
      e.preventDefault()
      const item = items[active]
      if (item) pick(item)
    }
  }

  const trimmed = q.trim()
  const firstTeamIndex = items.findIndex((i) => i.kind === 'team')

  return (
    <dialog
      className="sp no-print"
      ref={dialogRef}
      aria-label="Search drivers and teams"
      onCancel={(e) => {
        e.preventDefault()
        onClose()
      }}
      onClick={(e) => {
        if (e.target === dialogRef.current) onClose()
      }}
      onKeyDown={onKeyDown}
    >
      <div className="sp-body">
        <div className="sp-input-row">
          <SearchIcon />
          <input
            ref={inputRef}
            className="sp-input"
            type="text"
            value={q}
            placeholder="Search drivers, teams, car numbers…"
            role="combobox"
            aria-expanded={items.length > 0}
            aria-controls="sp-results"
            aria-activedescendant={items[active] ? `sp-opt-${active}` : undefined}
            aria-autocomplete="list"
            onChange={(e) => setQ(e.target.value)}
          />
          <kbd className="sp-kbd">esc</kbd>
        </div>

        <div className="sp-results" id="sp-results" role="listbox" aria-label="Drivers and teams">
          {trimmed === '' && (
            <p className="sp-hint-empty">Type a driver, a team, or a car number.</p>
          )}
          {trimmed !== '' && searching && items.length === 0 && (
            <div className="sp-skel" aria-label="Searching">
              <span className="skeleton" />
              <span className="skeleton" />
              <span className="skeleton" />
            </div>
          )}
          {trimmed !== '' && !searching && items.length === 0 && (
            <p className="sp-hint-empty">
              Nothing for “{trimmed}” — drivers and teams appear here once entry lists or results
              are imported.
            </p>
          )}
          {items.map((item, i) => (
            <div key={item.kind === 'driver' ? `d${item.driver.id}` : `t${item.team.teamName}`}>
              {i === 0 && item.kind === 'driver' && <p className="sp-group">Drivers</p>}
              {i === firstTeamIndex && <p className="sp-group">Teams</p>}
              <button
                id={`sp-opt-${i}`}
                type="button"
                role="option"
                aria-selected={i === active}
                className={i === active ? 'sp-item active' : 'sp-item'}
                onMouseEnter={() => setActive(i)}
                onClick={() => pick(item)}
              >
                {item.kind === 'driver' ? (
                  <DriverRow hit={item.driver} />
                ) : (
                  <TeamRow hit={item.team} />
                )}
              </button>
            </div>
          ))}
        </div>

        <div className="sp-hints" aria-hidden="true">
          <span>
            <kbd>↑</kbd>
            <kbd>↓</kbd> navigate
          </span>
          <span>
            <kbd>↵</kbd> open
          </span>
        </div>
      </div>
    </dialog>
  )
}
