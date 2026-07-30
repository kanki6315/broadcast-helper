import { useEffect, useMemo, useState } from 'react'
import './combobox.css'

/**
 * The shared typeahead combobox — the `.sp` search grammar as a form field.
 * Used by the SeriesEventPicker pair inside the import modals (inline results,
 * so a host dialog never clips them) and by the Imports page review rows
 * (floating results, so an open list doesn't shove the batch table around).
 *
 * Option variants:
 * - 'auto':   a muted default row ("Each import places itself").
 * - 'action': a fixed accent row that survives filtering ("+ New event: …").
 * - 'create': reserved for the internal "+ Create '<query>'" row, offered when
 *             `onCreate` is set and the query matches no option exactly.
 */

export interface ComboOption {
  key: string
  label: string
  hint?: string
  variant?: 'auto' | 'create' | 'action'
}

/** Case- and diacritic-insensitive matching: the catalog is full of Nürburgrings
 *  and Autódromos, and the reviewer types "nurburgring". */
function fold(s: string): string {
  return s
    .toLowerCase()
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
}

function SearchIcon() {
  return (
    <svg className="sep-combo-icon" width="14" height="14" viewBox="0 0 15 15" fill="none" aria-hidden="true">
      <circle cx="6.5" cy="6.5" r="4.75" stroke="currentColor" strokeWidth="1.5" />
      <path d="M10.5 10.5 L13.5 13.5" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
    </svg>
  )
}

export default function Combobox({
  label,
  showLabel = true,
  required,
  disabled,
  loading,
  placeholder,
  emptyText,
  options,
  selectedKey,
  ghost,
  floating,
  maxVisible,
  onPick,
  onClear,
  onCreate,
  createHint = 'new',
  inputId,
  className,
}: {
  label: string
  /** Render the visible field label. Off when the host supplies its own label
   *  text (the review rows' `.target-label`); the input keeps an aria-label. */
  showLabel?: boolean
  required?: boolean
  disabled?: boolean
  loading?: boolean
  placeholder: string
  emptyText: string
  options: ComboOption[]
  selectedKey: string | null
  ghost?: boolean
  /** Absolute-position the result list over the page instead of in flow.
   *  In-page fields float; fields inside a dialog stay inline (clipping). */
  floating?: boolean
  /** Cap the filtered list, with a "keep typing" row naming the overflow. */
  maxVisible?: number
  onPick: (key: string) => void
  onClear?: () => void
  /** When set, a non-matching query offers a "+ Create '<query>'" row. */
  onCreate?: (name: string) => void
  /** Hint on the create row — what picking it makes ("new series"). */
  createHint?: string
  inputId: string
  className?: string
}) {
  const [query, setQuery] = useState('')
  const [open, setOpen] = useState(false)
  const [active, setActive] = useState(0)

  const selected = options.find((o) => o.key === selectedKey) ?? null
  // When closed, the input shows the selection; when open, it shows what's typed.
  const display = open ? query : selected?.label ?? ''

  const { visible, overflow } = useMemo(() => {
    const q = fold(query.trim())
    // Typing filters; an empty box (or a box still showing the selection) lists all.
    // Action rows are pinned — they answer "none of these", which filtering must not hide.
    const showAll = !open || q === '' || q === fold(selected?.label ?? '')
    const actions = options.filter((o) => o.variant === 'action')
    const rest = options.filter((o) => o.variant !== 'action')
    const filtered = showAll
      ? rest
      : rest.filter((o) => fold(o.label).includes(q) || (o.hint != null && fold(o.hint).includes(q)))
    const capped = maxVisible != null && filtered.length > maxVisible ? filtered.slice(0, maxVisible) : filtered
    // A novel name (no exact label match) offers a create row at the end.
    const trimmed = query.trim()
    const canCreate = !!onCreate && trimmed !== '' && !options.some((o) => fold(o.label) === fold(trimmed))
    const rows = [
      ...actions,
      ...capped,
      ...(canCreate ? [{ key: '__create__', label: trimmed, variant: 'create' as const }] : []),
    ]
    return { visible: rows, overflow: filtered.length - capped.length }
  }, [options, query, open, selected, onCreate, maxVisible])

  useEffect(() => {
    setActive(0)
  }, [query, open])

  function commit(key: string) {
    if (key === '__create__' && onCreate) onCreate(query.trim())
    else onPick(key)
    setQuery('')
    setOpen(false)
  }

  function onKeyDown(e: React.KeyboardEvent) {
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      e.preventDefault()
      if (!open) {
        setOpen(true)
        return
      }
      if (visible.length === 0) return
      const delta = e.key === 'ArrowDown' ? 1 : -1
      setActive((a) => (a + delta + visible.length) % visible.length)
    } else if (e.key === 'Enter') {
      if (open && visible[active]) {
        e.preventDefault()
        commit(visible[active].key)
      }
    } else if (e.key === 'Escape') {
      if (open) {
        // Close the list, not the host dialog.
        e.preventDefault()
        e.stopPropagation()
        setOpen(false)
        setQuery('')
      }
    }
  }

  const listId = `${inputId}-list`
  const showGhost = ghost && !open && selected?.variant === 'auto'

  return (
    <div className={className ? `sep-field ${className}` : 'sep-field'}>
      {showLabel && (
        <label className="sep-field-label" htmlFor={inputId}>
          {label}
          {required && <span className="sep-req" aria-hidden="true">*</span>}
        </label>
      )}
      <div className={disabled ? 'sep-combo disabled' : 'sep-combo'}>
        <div className="sep-combo-row">
          <SearchIcon />
          <input
            id={inputId}
            className={showGhost ? 'sep-combo-input ghost' : 'sep-combo-input'}
            type="text"
            role="combobox"
            aria-label={showLabel ? undefined : label}
            aria-expanded={open}
            aria-controls={listId}
            aria-activedescendant={open && visible[active] ? `${inputId}-opt-${active}` : undefined}
            aria-autocomplete="list"
            autoComplete="off"
            disabled={disabled}
            placeholder={placeholder}
            value={display}
            onChange={(e) => {
              setQuery(e.target.value)
              setOpen(true)
            }}
            onFocus={() => setOpen(true)}
            onBlur={() => {
              // Let an option's mousedown land first, then close.
              window.setTimeout(() => setOpen(false), 120)
            }}
            onKeyDown={onKeyDown}
          />
          {selected && onClear && !disabled && (
            <button
              type="button"
              className="sep-combo-clear"
              aria-label={`Clear ${label.toLowerCase()}`}
              onMouseDown={(e) => e.preventDefault()}
              onClick={() => {
                onClear()
                setQuery('')
              }}
            >
              ✕
            </button>
          )}
        </div>

        {open && !disabled && (
          <ul
            className={floating ? 'sep-combo-list floating' : 'sep-combo-list'}
            id={listId}
            role="listbox"
            aria-label={label}
          >
            {loading ? (
              <li className="sep-combo-loading" aria-label="Loading">
                <span className="skeleton" />
                <span className="skeleton" />
              </li>
            ) : visible.length === 0 ? (
              <li className="sep-combo-empty">{emptyText}</li>
            ) : (
              <>
                {visible.map((o, i) => (
                  <li key={o.key}>
                    <button
                      id={`${inputId}-opt-${i}`}
                      type="button"
                      role="option"
                      aria-selected={i === active}
                      className={`sep-opt${i === active ? ' active' : ''}${o.variant ? ` ${o.variant}` : ''}`}
                      onMouseEnter={() => setActive(i)}
                      onMouseDown={(e) => e.preventDefault()}
                      onClick={() => commit(o.key)}
                    >
                      {(o.variant === 'create' || o.variant === 'action') && (
                        <span className="sep-opt-plus" aria-hidden="true">+ </span>
                      )}
                      <span className="sep-opt-name">
                        {o.variant === 'create' ? `Create “${o.label}”` : o.label}
                      </span>
                      {o.variant === 'create' && <span className="sep-opt-hint">{createHint}</span>}
                      {o.hint && <span className="sep-opt-hint">{o.hint}</span>}
                    </button>
                  </li>
                ))}
                {overflow > 0 && (
                  <li className="sep-combo-more" aria-live="polite">
                    {overflow} more — keep typing to narrow
                  </li>
                )}
              </>
            )}
          </ul>
        )}
      </div>
    </div>
  )
}
